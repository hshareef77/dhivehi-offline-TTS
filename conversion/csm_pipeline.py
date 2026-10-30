"""Reference ONNX Runtime implementation of CSM generation.

This is the Python twin of the Kotlin engine (engine/src/main/kotlin/.../CsmEngine.kt).
It is used to validate converted packages on a desktop/CI machine and to cross-check
the Kotlin implementation (same algorithm, same tensor names).
"""

import json
import os
import struct
import wave

import numpy as np
import onnxruntime as ort


def load_q8_table(path):
    with open(path, "rb") as f:
        assert f.read(4) == b"DVQ8"
        _, rows, dim = struct.unpack("<iii", f.read(12))
    scales = np.memmap(path, dtype="<f4", mode="r", offset=16, shape=(rows,))
    data = np.memmap(path, dtype=np.int8, mode="r", offset=16 + 4 * rows, shape=(rows, dim))
    return Q8Table(scales, data)


class Q8Table:
    def __init__(self, scales, data):
        self.scales, self.data = scales, data

    def row(self, i):
        return self.data[i].astype(np.float32) * np.float32(self.scales[i])


class DenseTable:
    def __init__(self, w):
        self.w = np.asarray(w, dtype=np.float32)

    def row(self, i):
        return self.w[i]


class Sampler:
    # Mimi has 2048 codes; ids 2048..2050 are padding/special and must never be decoded.
    def __init__(self, temperature=0.9, top_k=50, seed=0, greedy=False, valid=2048):
        self.t, self.k, self.greedy, self.valid = temperature, top_k, greedy, valid
        self.rng = np.random.default_rng(seed)

    def __call__(self, logits):
        logits = np.asarray(logits, dtype=np.float64).reshape(-1)
        if self.valid:
            logits = logits[: self.valid]
        if self.greedy:
            return int(np.argmax(logits))
        logits = logits / self.t
        k = min(self.k, logits.size)
        idx = np.argpartition(-logits, k - 1)[:k]
        sel = logits[idx]
        p = np.exp(sel - sel.max())
        p /= p.sum()
        return int(idx[self.rng.choice(k, p=p)])


def session(path, threads=4):
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])


class CsmOnnx:
    def __init__(self, model_dir, text_table=None, audio_table=None, depth_table=None, threads=4,
                 load_mimi=True):
        self.dir = model_dir
        self.cfg = json.load(open(os.path.join(model_dir, "model_config.json")))
        self.backbone = session(os.path.join(model_dir, "backbone.onnx"), threads)
        self.depth = session(os.path.join(model_dir, "depth_decoder.onnx"), threads)
        self.mimi = session(os.path.join(model_dir, "mimi_decoder.onnx"), threads) if load_mimi else None
        self.text = text_table or load_q8_table(os.path.join(model_dir, "text_embeddings.bin"))
        self.audio = audio_table or load_q8_table(os.path.join(model_dir, "audio_embeddings.bin"))
        dpath = os.path.join(model_dir, "depth_embeddings.bin")
        self.depth_emb = depth_table or (load_q8_table(dpath) if os.path.exists(dpath) else self.audio)
        self.ncb = self.cfg["num_codebooks"]
        self.vocab = self.cfg["audio_vocab_size"]
        self.hidden = self.cfg["hidden_size"]

    @staticmethod
    def _empty_past(n_layers, kv_heads, head_dim):
        return [np.zeros((1, kv_heads, 0, head_dim), np.float32) for _ in range(2 * n_layers)]

    def _run(self, sess, embeds, past):
        feeds = {"inputs_embeds": embeds[None].astype(np.float32)}
        for i in range(len(past) // 2):
            feeds[f"past_key_values.{i}.key"] = past[2 * i]
            feeds[f"past_key_values.{i}.value"] = past[2 * i + 1]
        return sess.run(None, feeds)

    def frame_embedding(self, codes):
        e = np.zeros(self.hidden, np.float32)
        for c, t in enumerate(codes):
            e += self.audio.row(c * self.vocab + int(t))
        return e

    def generate_frames(self, token_ids, max_frames, sampler, depth_sampler=None, on_frame=None, codebooks=None):
        depth_sampler = depth_sampler or sampler
        b = self.cfg["backbone"]
        d = self.cfg["depth_decoder"]
        past = self._empty_past(b["layers"], b["kv_heads"], b["head_dim"])
        embeds = np.stack([self.text.row(int(t)) for t in token_ids])
        frames = []
        for _ in range(max_frames):
            out = self._run(self.backbone, embeds, past)
            hidden, logits, past = out[0][0], out[1][0], out[2:]
            c0 = sampler(logits)
            codes = [c0]
            dpast = self._empty_past(d["layers"], d["kv_heads"], d["head_dim"])
            dinp = np.stack([hidden, self.depth_emb.row(c0)])
            ncb = codebooks or self.ncb
            for cb in range(1, ncb):
                dout = self._run(self.depth, dinp, dpast)
                dlogits, dpast = dout[0][0], dout[1:]
                tok = depth_sampler(dlogits)
                codes.append(tok)
                dinp = self.depth_emb.row(cb * self.vocab + tok)[None]
            if all(c == self.cfg["codebook_eos_token_id"] for c in codes[: min(len(codes), self.ncb - 1)]):
                break
            frames.append(codes)
            if on_frame:
                on_frame(len(frames))
            embeds = self.frame_embedding(codes)[None]
        return frames

    def decode(self, frames):
        if not frames:
            return np.zeros(0, np.float32)
        codes = np.array(frames, dtype=np.int64).T[None]  # [1, 32, F]
        return self.mimi.run(None, {"codes": codes})[0][0]


def prompt_tokens(tokenizer, speaker, text):
    """Matches the model's chat template: <|begin_of_text|>[speaker]text<|end_of_text|>."""
    return tokenizer.encode(f"<|begin_of_text|>[{speaker}]{text}<|end_of_text|>", add_special_tokens=False)


def write_wav(path, audio, sr=24000):
    pcm = (np.clip(audio, -1.0, 1.0) * 32767.0).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())
