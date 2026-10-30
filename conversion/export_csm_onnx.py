"""Convert the CSM-1B Dhivehi checkpoint into the on-device package used by the Android app.

Usage:
    python conversion/export_csm_onnx.py --model alakxender/csm-1b-dhivehi-5-spk-gd --out build/fp32

Produces (fp32, before quantization):
    backbone.onnx (+ .data)     Llama-1B backbone step with explicit KV cache
    depth_decoder.onnx (+.data) depth decoder step (codebooks 1..31)
    mimi_decoder.onnx (+.data)  Mimi codec decoder, codes -> 24 kHz audio
    text_embeddings.bin         int8 rows + fp32 per-row scales (see write_q8_table)
    audio_embeddings.bin        same format, 32 * 2051 rows (codebook-major)
    tokenizer/vocab.txt         byte-level BPE vocabulary, one token per line, id order
    tokenizer/merges.txt        BPE merges in rank order
    tokenizer/special_tokens.txt  "id<TAB>content" for added tokens
    model_config.json           constants the app needs
"""

import argparse
import json
import os
import struct

import numpy as np
import torch

from csm_modules import BackboneStep, DepthStep, MimiDecode, kv_names

OPSET = 17


def write_q8_table(path, weight):
    """Header: b"DVQ8" magic, int32 version=1, int32 rows, int32 dim (little endian),
    then rows float32 scales, then rows*dim int8 values. value = int8 * scale."""
    w = weight.detach().float().cpu().numpy()
    rows, dim = w.shape
    scales = np.abs(w).max(axis=1) / 127.0
    scales[scales == 0] = 1.0
    q = np.clip(np.rint(w / scales[:, None]), -127, 127).astype(np.int8)
    with open(path, "wb") as f:
        f.write(b"DVQ8")
        f.write(struct.pack("<iii", 1, rows, dim))
        f.write(scales.astype("<f4").tobytes())
        f.write(q.tobytes())
    err = np.abs(q.astype(np.float32) * scales[:, None] - w).max()
    print(f"  {os.path.basename(path)}: {rows}x{dim}, max abs quantization error {err:.5f}")


def export(module, args, input_names, output_names, dynamic_axes, path):
    print(f"Exporting {path} ...")
    with torch.no_grad():
        torch.onnx.export(
            module,
            args,
            path,
            input_names=input_names,
            output_names=output_names,
            dynamic_axes=dynamic_axes,
            opset_version=OPSET,
            do_constant_folding=True,
            dynamo=False,
        )
    # Re-save with external data so >2 GB graphs work and the loader can mmap weights.
    import onnx

    m = onnx.load(path)
    for f in os.listdir(os.path.dirname(path)):
        if f.startswith(os.path.basename(path)) and f != os.path.basename(path):
            os.remove(os.path.join(os.path.dirname(path), f))
    onnx.save_model(
        m, path, save_as_external_data=True, all_tensors_to_one_file=True,
        location=os.path.basename(path) + ".data", size_threshold=1024,
    )
    # torch's own scattered external files (if any) are no longer referenced
    for f in os.listdir(os.path.dirname(path)):
        full = os.path.join(os.path.dirname(path), f)
        if f not in (os.path.basename(path), os.path.basename(path) + ".data") and not f.endswith(
            (".onnx", ".data", ".bin", ".json", ".txt")
        ) and os.path.isfile(full):
            os.remove(full)


def export_backbone(model, out):
    cfg = model.config
    n = cfg.num_hidden_layers
    head_dim = cfg.head_dim
    step = BackboneStep(model).eval()
    past = []
    for _ in range(n):
        past += [torch.zeros(1, cfg.num_key_value_heads, 3, head_dim)] * 2
    x = torch.randn(1, 4, cfg.hidden_size)
    in_kv = kv_names("past_key_values", n)
    out_kv = kv_names("present", n)
    dyn = {"inputs_embeds": {1: "seq"}}
    for name in in_kv:
        dyn[name] = {2: "past"}
    for name in out_kv:
        dyn[name] = {2: "total"}
    export(step, (x, *past), ["inputs_embeds", *in_kv], ["hidden", "logits", *out_kv], dyn,
           os.path.join(out, "backbone.onnx"))


def export_depth(model, out, head_dtype):
    dcfg = model.depth_decoder.config
    n = dcfg.num_hidden_layers
    step = DepthStep(model, head_dtype).eval()
    past = []
    for _ in range(n):
        past += [torch.zeros(1, dcfg.num_key_value_heads, 2, dcfg.head_dim)] * 2
    x = torch.randn(1, 1, dcfg.backbone_hidden_size)
    in_kv = kv_names("past_key_values", n)
    out_kv = kv_names("present", n)
    dyn = {"inputs_embeds": {1: "seq"}}
    for name in in_kv:
        dyn[name] = {2: "past"}
    for name in out_kv:
        dyn[name] = {2: "total"}
    export(step, (x, *past), ["inputs_embeds", *in_kv], ["logits", *out_kv], dyn,
           os.path.join(out, "depth_decoder.onnx"))


def export_mimi(model, out):
    dec = MimiDecode(model.codec_model).eval()
    codes = torch.randint(0, 2048, (1, model.config.num_codebooks, 20), dtype=torch.long)
    export(dec, (codes,), ["codes"], ["audio"], {"codes": {1: "codebooks", 2: "frames"}, "audio": {1: "samples"}},
           os.path.join(out, "mimi_decoder.onnx"))


def export_tokenizer(model_dir, out):
    tok_dir = os.path.join(out, "tokenizer")
    os.makedirs(tok_dir, exist_ok=True)
    with open(os.path.join(model_dir, "tokenizer.json"), encoding="utf-8") as f:
        tj = json.load(f)
    m = tj["model"]
    assert m["type"] == "BPE", m["type"]
    vocab = m["vocab"]
    inv = [None] * len(vocab)
    for tok, idx in vocab.items():
        assert "\n" not in tok and " " not in tok, repr(tok)
        inv[idx] = tok
    assert all(t is not None for t in inv)
    with open(os.path.join(tok_dir, "vocab.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(inv) + "\n")
    with open(os.path.join(tok_dir, "merges.txt"), "w", encoding="utf-8", newline="\n") as f:
        for mg in m["merges"]:
            a, b = mg if isinstance(mg, list) else mg.split(" ", 1)
            f.write(f"{a} {b}\n")
    with open(os.path.join(tok_dir, "special_tokens.txt"), "w", encoding="utf-8", newline="\n") as f:
        for t in tj.get("added_tokens", []):
            f.write(f"{t['id']}\t{t['content']}\n")
    info = {
        "ignore_merges": bool(m.get("ignore_merges", False)),
        "pre_tokenizer": tj.get("pre_tokenizer"),
        "normalizer": tj.get("normalizer"),
    }
    with open(os.path.join(tok_dir, "tokenizer_info.json"), "w") as f:
        json.dump(info, f, indent=1)
    print(f"  tokenizer: {len(inv)} tokens, {len(m['merges'])} merges, ignore_merges={info['ignore_merges']}")


def write_config(model, out, gen_cfg):
    cfg = model.config
    d = model.depth_decoder.config
    conf = {
        "format": "dhivehi-csm-onnx",
        "format_version": 1,
        "source_model": "alakxender/csm-1b-dhivehi-5-spk-gd",
        "sample_rate": cfg.codec_config.sampling_rate,
        "frame_rate": 12.5,
        "samples_per_frame": int(cfg.codec_config.sampling_rate / 12.5),
        "num_codebooks": cfg.num_codebooks,
        "audio_vocab_size": cfg.vocab_size,
        "codebook_eos_token_id": cfg.codebook_eos_token_id,
        "text_vocab_size": cfg.text_vocab_size,
        "hidden_size": cfg.hidden_size,
        "bos_token_id": cfg.bos_token_id,
        "eos_token_id": 128001,
        "backbone": {"layers": cfg.num_hidden_layers, "kv_heads": cfg.num_key_value_heads,
                     "head_dim": cfg.head_dim, "max_positions": cfg.max_position_embeddings},
        "depth_decoder": {"layers": d.num_hidden_layers, "kv_heads": d.num_key_value_heads,
                          "head_dim": d.head_dim},
        "sampling": {"temperature": gen_cfg.get("temperature", 0.9), "top_k": 50,
                     "depth_temperature": gen_cfg.get("depth_decoder_temperature", 0.9), "depth_top_k": 50},
        "default_max_frames": gen_cfg.get("max_new_tokens", 125),
        "speakers": [
            {"id": 0, "name": "Female voice 1"},
            {"id": 1, "name": "Male voice 1"},
            {"id": 2, "name": "Female voice 2"},
            {"id": 3, "name": "Male voice 2"},
            {"id": 4, "name": "Voice 5"},
        ],
    }
    with open(os.path.join(out, "model_config.json"), "w") as f:
        json.dump(conf, f, indent=1)


def load_model(path):
    """Loads the checkpoint in fp32 and fails loudly if any weight was not loaded."""
    from transformers import CsmForConditionalGeneration

    model, info = CsmForConditionalGeneration.from_pretrained(path, dtype=torch.float32, output_loading_info=True)
    missing = [k for k in info.get("missing_keys", []) if "embed_tokens" not in k or "depth_decoder" not in k]
    if missing:
        raise SystemExit(f"Checkpoint weights were not loaded (would export random weights): {sorted(missing)[:20]}")
    if info.get("unexpected_keys"):
        print("  note: unused checkpoint keys:", sorted(info["unexpected_keys"])[:10])
    # The checkpoint stores the shared audio-codebook embeddings once (under the depth decoder);
    # make sure the backbone uses the same table.
    bb = model.backbone_model.embed_tokens.embed_audio_tokens
    dd = model.depth_decoder.model.embed_tokens
    if not torch.equal(bb.weight, dd.weight):
        if model.config.tie_codebooks_embeddings:
            print("  tying backbone audio embeddings to the stored depth-decoder table")
            bb.weight = dd.weight
        else:
            print("  note: backbone and depth-decoder audio embeddings are separate tables")
    return model.eval()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True, help="local directory of the HF checkpoint")
    ap.add_argument("--out", required=True)
    ap.add_argument("--skip", nargs="*", default=[], help="parts to skip: backbone depth mimi emb tok")
    ap.add_argument("--depth-head-dtype", default="float16", choices=["float16", "float32"])
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    model = load_model(args.model)
    gen_path = os.path.join(args.model, "generation_config.json")
    gen_cfg = json.load(open(gen_path)) if os.path.exists(gen_path) else {}

    if "emb" not in args.skip:
        print("Embeddings:")
        audio_w = model.backbone_model.embed_tokens.embed_audio_tokens.weight
        depth_w = model.depth_decoder.model.embed_tokens.weight
        if not torch.equal(audio_w, depth_w):
            # Should be tied (tie_codebooks_embeddings); export separately if not.
            print("  WARNING: depth decoder embeddings differ from backbone audio embeddings; exporting both")
            write_q8_table(os.path.join(args.out, "depth_embeddings.bin"), depth_w)
        write_q8_table(os.path.join(args.out, "text_embeddings.bin"), model.embed_text_tokens.weight)
        write_q8_table(os.path.join(args.out, "audio_embeddings.bin"), audio_w)
    if "tok" not in args.skip and os.path.exists(os.path.join(args.model, "tokenizer.json")):
        export_tokenizer(args.model, args.out)
    write_config(model, args.out, gen_cfg)
    if "mimi" not in args.skip:
        export_mimi(model, args.out)
    if "depth" not in args.skip:
        export_depth(model, args.out, getattr(torch, args.depth_head_dtype))
    if "backbone" not in args.skip:
        # Drop parts no longer needed to reduce peak memory during the largest export.
        model.codec_model = None
        model.depth_decoder = None
        model.embed_text_tokens = None
        model.backbone_model.embed_tokens = None
        export_backbone(model, args.out)
    print("Done.")


if __name__ == "__main__":
    main()
