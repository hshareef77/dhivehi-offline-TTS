"""Check that the exported ONNX graphs reproduce the Hugging Face CSM model.

1. Greedy generation with the HF model vs the ONNX pipeline using the exact fp32 embedding
   tables: the generated codebook tokens must be identical (or nearly, for big models
   where float rounding can flip a near-tie argmax).
2. Mimi decoding: ONNX vs HF codec on the generated codes.
3. Effect of the int8 embedding tables on the same greedy run (informational).

Usage: python conversion/verify_parity.py --model <hf dir> --onnx <export dir> [--text "..."]
Exit status is non-zero if parity fails.
"""

import argparse
import json
import sys

import numpy as np
import torch

from csm_pipeline import CsmOnnx, DenseTable, Sampler, prompt_tokens


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--onnx", required=True)
    ap.add_argument("--text", default="ދިވެހިރާއްޖެ އަކީ ރީތި ޤައުމެކެވެ.")
    ap.add_argument("--speaker", type=int, default=0)
    ap.add_argument("--frames", type=int, default=12)
    ap.add_argument("--min-match", type=float, default=0.9,
                    help="required fraction of identical greedy tokens (1.0 for exact)")
    ap.add_argument("--report", default=None)
    args = ap.parse_args()

    from transformers import AutoTokenizer

    from export_csm_onnx import load_model

    model = load_model(args.model)
    try:
        tok = AutoTokenizer.from_pretrained(args.model)
        ids = prompt_tokens(tok, args.speaker, args.text)
        try:
            from transformers import AutoProcessor
            proc = AutoProcessor.from_pretrained(args.model)
            conv = [{"role": str(args.speaker), "content": [{"type": "text", "text": args.text}]}]
            pids = proc.apply_chat_template(conv, tokenize=True, return_dict=True)["input_ids"]
            pids = pids[0].tolist() if hasattr(pids[0], "tolist") else list(pids[0])
            print("processor ids == pipeline ids:", pids == ids)
            if pids != ids:
                print("  processor:", pids)
                print("  pipeline: ", ids)
                sys.exit("Prompt tokenisation does not match the processor")
        except Exception as e:  # tiny test models have no processor files
            print("processor comparison skipped:", type(e).__name__, e)
    except Exception:
        rng = np.random.default_rng(0)
        ids = [128000] + rng.integers(0, 128000, 20).tolist() + [128001]
    print("prompt tokens:", len(ids))

    input_ids = torch.tensor([ids])
    with torch.no_grad():
        hf = model.generate(input_ids=input_ids, attention_mask=torch.ones_like(input_ids),
                            max_new_tokens=args.frames, do_sample=False, depth_decoder_do_sample=False)
    hf_frames = hf[0].tolist()
    eos = model.config.codebook_eos_token_id
    cut = next((i for i, f in enumerate(hf_frames) if all(c == eos for c in f[:-1])), len(hf_frames))
    hf_frames = hf_frames[:cut]

    text_t = DenseTable(model.embed_text_tokens.weight.detach().numpy())
    audio_t = DenseTable(model.backbone_model.embed_tokens.embed_audio_tokens.weight.detach().numpy())
    depth_t = DenseTable(model.depth_decoder.model.embed_tokens.weight.detach().numpy())
    pipe = CsmOnnx(args.onnx, text_t, audio_t, depth_t)
    ox_frames = pipe.generate_frames(ids, args.frames, Sampler(greedy=True, valid=None))

    n = min(len(hf_frames), len(ox_frames))
    a = np.array(hf_frames[:n]); b = np.array(ox_frames[:n])
    match = float((a == b).mean()) if n else 0.0
    first_c0 = float((a[:, 0] == b[:, 0]).mean()) if n else 0.0
    print(f"HF frames {len(hf_frames)}, ONNX frames {len(ox_frames)}, token match {match:.4f}, codebook-0 match {first_c0:.4f}")
    first_diff = next((i for i in range(n) if hf_frames[i] != ox_frames[i]), None)
    print("first differing frame:", first_diff)

    # Mimi parity on HF frames
    hf_frames_c = [[min(c, 2047) for c in f] for f in hf_frames]  # random test weights may emit specials
    codes = torch.tensor(hf_frames_c).T[None]
    with torch.no_grad():
        ref_audio = model.codec_model.decode(codes).audio_values[0, 0].numpy()
    ox_audio = pipe.decode(hf_frames_c)
    mimi_err = float(np.abs(ref_audio - ox_audio).max()) if len(hf_frames) else 0.0
    print(f"Mimi decoder: {len(ox_audio)} samples, max abs diff vs HF {mimi_err:.2e}")
    # Fewer codebooks (speed setting): same graph, first 16 codebooks only
    with torch.no_grad():
        ref16 = model.codec_model.decode(codes[:, :16]).audio_values[0, 0].numpy()
    ox16 = pipe.mimi.run(None, {"codes": np.array(hf_frames_c, dtype=np.int64).T[None, :16]})[0][0]
    mimi16_err = float(np.abs(ref16 - ox16).max()) if len(hf_frames) else 0.0
    print(f"Mimi decoder, 16 codebooks: max abs diff vs HF {mimi16_err:.2e}")
    mimi_err = max(mimi_err, mimi16_err)

    q8 = CsmOnnx(args.onnx, load_mimi=False)
    q8_frames = q8.generate_frames(ids, args.frames, Sampler(greedy=True, valid=None))
    m = min(len(q8_frames), len(hf_frames))
    q8_match = float((np.array(q8_frames[:m]) == np.array(hf_frames[:m])).mean()) if m else 0.0
    print(f"int8 embedding tables: token match vs fp32 {q8_match:.4f} (informational)")

    report = {"hf_frames": len(hf_frames), "onnx_frames": len(ox_frames), "token_match": match,
              "codebook0_match": first_c0, "first_diff_frame": first_diff, "mimi_max_abs_diff": mimi_err,
              "q8_token_match": q8_match}
    if args.report:
        json.dump(report, open(args.report, "w"), indent=1)
    ok = match >= args.min_match and mimi_err < 1e-3 and len(ox_frames) > 0
    print("PARITY", "OK" if ok else "FAILED")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
