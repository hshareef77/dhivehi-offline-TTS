"""Export-friendly re-implementation of the CSM-1B inference graph.

The Hugging Face `CsmForConditionalGeneration` relies on dynamic caches and Python-side
control flow that do not export cleanly to ONNX. These modules reuse the *same weight
tensors* (no copies) but express each step with explicit key/value cache inputs and
outputs, so that the Android app can drive the autoregressive loop itself.

Three graphs are produced:

* backbone: Llama-1B style transformer (16 layers). Input: summed frame/text embeddings.
  Output: final normed hidden state of the last position, codebook-0 logits, new KV cache.
* depth decoder: Llama-100M style transformer (4 layers) that predicts codebooks 1..31
  for one audio frame. Output: logits of the codebook selected by the position.
* mimi decoder: Mimi codec decoder turning 32 x F codes into 24 kHz audio.

Embedding tables are *not* part of the graphs; they are exported separately (int8 rows)
and looked up by the app through a memory-mapped file.
"""

import torch
import torch.nn as nn


def rotate_half(x):
    half = x.shape[-1] // 2
    return torch.cat((-x[..., half:], x[..., :half]), dim=-1)


class ExportAttention(nn.Module):
    def __init__(self, hf_attn, num_heads, num_kv_heads, head_dim):
        super().__init__()
        self.q_proj = hf_attn.q_proj
        self.k_proj = hf_attn.k_proj
        self.v_proj = hf_attn.v_proj
        self.o_proj = hf_attn.o_proj
        self.num_heads = num_heads
        self.num_kv_heads = num_kv_heads
        self.head_dim = head_dim
        self.n_rep = num_heads // num_kv_heads
        self.scale = head_dim ** -0.5

    def forward(self, x, cos, sin, mask, past_k, past_v):
        b, t, _ = x.shape
        q = self.q_proj(x).view(b, t, self.num_heads, self.head_dim).transpose(1, 2)
        k = self.k_proj(x).view(b, t, self.num_kv_heads, self.head_dim).transpose(1, 2)
        v = self.v_proj(x).view(b, t, self.num_kv_heads, self.head_dim).transpose(1, 2)
        q = q * cos + rotate_half(q) * sin
        k = k * cos + rotate_half(k) * sin
        k = torch.cat([past_k, k], dim=2)
        v = torch.cat([past_v, v], dim=2)
        present_k, present_v = k, v
        if self.n_rep > 1:
            s = k.shape[2]
            k = k[:, :, None, :, :].expand(b, self.num_kv_heads, self.n_rep, s, self.head_dim)
            k = k.reshape(b, self.num_heads, s, self.head_dim)
            v = v[:, :, None, :, :].expand(b, self.num_kv_heads, self.n_rep, s, self.head_dim)
            v = v.reshape(b, self.num_heads, s, self.head_dim)
        scores = torch.matmul(q, k.transpose(2, 3)) * self.scale + mask
        probs = torch.softmax(scores, dim=-1)
        out = torch.matmul(probs, v).transpose(1, 2).reshape(b, t, self.num_heads * self.head_dim)
        return self.o_proj(out), present_k, present_v


class RMSNorm(nn.Module):
    def __init__(self, hf_norm):
        super().__init__()
        self.weight = hf_norm.weight
        self.eps = hf_norm.variance_epsilon

    def forward(self, x):
        var = x.pow(2).mean(-1, keepdim=True)
        return self.weight * (x * torch.rsqrt(var + self.eps))


class ExportLayer(nn.Module):
    def __init__(self, hf_layer, cfg):
        super().__init__()
        head_dim = getattr(cfg, "head_dim", None) or cfg.hidden_size // cfg.num_attention_heads
        self.attn = ExportAttention(hf_layer.self_attn, cfg.num_attention_heads, cfg.num_key_value_heads, head_dim)
        self.ln1 = RMSNorm(hf_layer.input_layernorm)
        self.ln2 = RMSNorm(hf_layer.post_attention_layernorm)
        self.mlp = hf_layer.mlp

    def forward(self, x, cos, sin, mask, past_k, past_v):
        h, pk, pv = self.attn(self.ln1(x), cos, sin, mask, past_k, past_v)
        x = x + h
        x = x + self.mlp(self.ln2(x))
        return x, pk, pv


class ExportTransformer(nn.Module):
    """Shared Llama-style stack with explicit KV cache and in-graph positions/mask."""

    def __init__(self, hf_layers, hf_norm, rotary_emb, cfg):
        super().__init__()
        self.layers = nn.ModuleList([ExportLayer(l, cfg) for l in hf_layers])
        self.norm = RMSNorm(hf_norm)
        self.register_buffer("inv_freq", rotary_emb.inv_freq.detach().clone().float(), persistent=False)
        self.attention_scaling = float(rotary_emb.attention_scaling)

    def run(self, x, past):
        t = x.shape[1]
        p = past[0].shape[2]
        pos = torch.arange(t, dtype=torch.float32) + p
        freqs = pos[:, None] * self.inv_freq[None, :]
        emb = torch.cat([freqs, freqs], dim=-1)
        cos = (emb.cos() * self.attention_scaling)[None, None]
        sin = (emb.sin() * self.attention_scaling)[None, None]
        # causal mask [t, p + t]: query i (absolute p+i) may see keys j <= p+i
        q_idx = torch.arange(t)[:, None] + p
        k_idx = torch.arange(p + t)[None, :]
        mask = torch.where(k_idx <= q_idx, torch.tensor(0.0), torch.tensor(-1e9))[None, None]
        presents = []
        for i, layer in enumerate(self.layers):
            x, pk, pv = layer(x, cos, sin, mask, past[2 * i], past[2 * i + 1])
            presents += [pk, pv]
        return self.norm(x), presents


class BackboneStep(nn.Module):
    """inputs_embeds [1, T, 2048] + past KV -> last hidden [1, 2048], c0 logits [1, 2051], presents."""

    def __init__(self, model):
        super().__init__()
        bb = model.backbone_model
        self.tf = ExportTransformer(bb.layers, bb.norm, bb.rotary_emb, model.config)
        self.lm_head = model.lm_head

    def forward(self, inputs_embeds, *past):
        h, presents = self.tf.run(inputs_embeds, list(past))
        last = h[:, -1, :]
        return (last, self.lm_head(last), *presents)


class DepthStep(nn.Module):
    """Raw (2048-d) embeddings [1, T, 2048] + past KV -> logits of the codebook at the last position.

    Position 0 holds the backbone hidden state, position k (k >= 1) holds the embedding of
    codebook k-1. The logits at the last absolute position n use codebook head n-1, i.e.
    they predict codebook n.
    """

    def __init__(self, model, head_dtype=torch.float32):
        super().__init__()
        dd = model.depth_decoder
        self.tf = ExportTransformer(dd.model.layers, dd.model.norm, dd.model.rotary_emb, dd.config)
        self.projector = dd.model.inputs_embeds_projector
        # [31, 1024, 2051]; stored at head_dtype (fp16 halves its size) and cast after gather
        self.register_buffer("heads", dd.codebooks_head.weight.detach().to(head_dtype), persistent=True)

    def forward(self, inputs_embeds, *past):
        h, presents = self.tf.run(self.projector(inputs_embeds), list(past))
        last_pos = past[0].shape[2] + inputs_embeds.shape[1] - 1
        w = self.heads[last_pos - 1].float()
        logits = torch.matmul(h[:, -1, :], w)
        return (logits, *presents)


class MimiDecode(nn.Module):
    """codes [1, 32, F] int64 -> audio [1, F * 1920] float32 at 24 kHz.

    Re-implements the decoder transformer's forward (sliding-window causal attention with
    rotary embeddings) with an explicit mask, because the library's mask construction is
    not traceable. Works with both the transformers 4.x and 5.x Mimi module layouts.
    """

    def __init__(self, codec):
        super().__init__()
        self.quantizer = codec.quantizer
        dec = getattr(codec, "decoder", None)
        if dec is not None and hasattr(dec, "decoder_transformer"):  # transformers >= 5
            self.upsample, tf, self.seanet = dec.upsample, dec.decoder_transformer, dec.decoder_seanet
        else:  # transformers 4.x
            self.upsample, tf, self.seanet = codec.upsample, codec.decoder_transformer, codec.decoder
        self.layers = tf.layers
        # transformers 5.x keeps one rotary embedding on the model, 4.x one per attention layer
        rotary = getattr(tf, "rotary_emb", None) or tf.layers[0].self_attn.rotary_emb
        self.register_buffer("inv_freq", rotary.inv_freq.detach().clone().float(), persistent=False)
        self.attention_scaling = float(rotary.attention_scaling)
        cfg = tf.config
        self.window = cfg.sliding_window
        self.num_heads = cfg.num_attention_heads
        self.head_dim = cfg.head_dim

    def _attn(self, attn, x, cos, sin, mask):
        b, t, _ = x.shape
        q = attn.q_proj(x).view(b, t, self.num_heads, self.head_dim).transpose(1, 2)
        k = attn.k_proj(x).view(b, t, self.num_heads, self.head_dim).transpose(1, 2)
        v = attn.v_proj(x).view(b, t, self.num_heads, self.head_dim).transpose(1, 2)
        q = q * cos + rotate_half(q) * sin
        k = k * cos + rotate_half(k) * sin
        scores = torch.matmul(q, k.transpose(2, 3)) * (self.head_dim ** -0.5) + mask
        out = torch.matmul(torch.softmax(scores, dim=-1), v).transpose(1, 2).reshape(b, t, -1)
        return attn.o_proj(out)

    def forward(self, codes):
        latents = self.quantizer.decode(codes)
        h = self.upsample(latents).transpose(1, 2)
        t = h.shape[1]
        pos = torch.arange(t, dtype=torch.float32)
        freqs = pos[:, None] * self.inv_freq[None, :]
        emb = torch.cat([freqs, freqs], dim=-1)
        cos = (emb.cos() * self.attention_scaling)[None, None]
        sin = (emb.sin() * self.attention_scaling)[None, None]
        qi = torch.arange(t)[:, None]
        kj = torch.arange(t)[None, :]
        allowed = (kj <= qi) & (kj > qi - self.window)
        mask = torch.where(allowed, torch.tensor(0.0), torch.tensor(-1e9))[None, None]
        for layer in self.layers:
            r = h
            h = layer.self_attn_layer_scale(self._attn(layer.self_attn, layer.input_layernorm(h), cos, sin, mask)) + r
            r = h
            h = layer.mlp_layer_scale(layer.mlp(layer.post_attention_layernorm(h))) + r
        audio = self.seanet(h.transpose(1, 2))
        return audio[:, 0, :]


def kv_names(prefix, n_layers):
    names = []
    for i in range(n_layers):
        names += [f"{prefix}.{i}.key", f"{prefix}.{i}.value"]
    return names
