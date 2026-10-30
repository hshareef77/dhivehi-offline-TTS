"""Generate real Dhivehi speech with converted packages and measure intelligibility.

For each package directory: all five speakers speak the test sentences (sampling as on
the phone, fixed seeds). The audio is transcribed with a Dhivehi speech recogniser
(alakxender/w2v-bert-2.0-dhivehi-cv) and compared to the input text (character error
rate, CER). Lower CER = more intelligible. Comparing fp32 vs quantized packages shows the
quality cost of quantization.

Usage:
  python conversion/evaluate_quality.py --tokenizer-json hf/tokenizer.json --out eval \
      --package fp32=build/fp32 --package q4=build/q4 [--choose q4,q8,fp32 --max-cer-increase 0.08]
"""

import argparse
import json
import os
import re
import time
import unicodedata

import numpy as np
from tokenizers import Tokenizer

from csm_pipeline import CsmOnnx, Sampler, write_wav

SENTENCES = [
    "ދިވެހިރާއްޖެ އަކީ ރީތި ޤައުމެކެވެ.",
    "މެލޭޝިއާގައި އިތުރުކުރާ ޓެކްސް، ދިވެހި ދަރިވަރުންނަށް ބުރައަކަށް ނުވާނެ ގޮތެއް ހޯދައިދޭނަން",
    "މިއަދު މޫސުން ވަރަށް ރަނގަޅު، ކަނޑު މަޑު.",
]


def normalize(t):
    # Thaana fili are combining marks; removing them hides vowel errors.
    t = unicodedata.normalize("NFC", t)
    t = "".join(c if c.isalnum() or c == "_" or c.isspace()
                or unicodedata.category(c).startswith("M") else " " for c in t)
    return re.sub(r"\s+", " ", t).strip()


def cer(ref, hyp):
    r, h = normalize(ref), normalize(hyp)
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)] / max(1, len(r))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tokenizer-json", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--package", action="append", required=True, help="name=dir")
    ap.add_argument("--speakers", default="0,1,2,3,4")
    ap.add_argument("--asr", default="alakxender/w2v-bert-2.0-dhivehi-cv")
    ap.add_argument("--choose", default=None, help="preference order, e.g. q4,q8,fp32")
    ap.add_argument("--baseline", default="fp32")
    ap.add_argument("--max-cer-increase", type=float, default=0.08)
    ap.add_argument("--threads", type=int, default=4)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    tok = Tokenizer.from_file(args.tokenizer_json)

    asr = None
    try:
        from scipy.signal import resample_poly
        from transformers import pipeline
        asr = pipeline("automatic-speech-recognition", model=args.asr, device="cpu")
    except Exception as e:
        raise RuntimeError("Dhivehi recogniser could not load; refusing unmeasured quality evaluation") from e

    report = {}
    for spec in args.package:
        name, path = spec.split("=", 1)
        pipe = CsmOnnx(path, threads=args.threads)
        cfg = pipe.cfg
        rows = []
        for spk in [int(s) for s in args.speakers.split(",")]:
            for n, text in enumerate(SENTENCES):
                ids = [cfg["bos_token_id"]] + tok.encode(f"[{spk}]{text}", add_special_tokens=False).ids + [cfg["eos_token_id"]]
                t0 = time.time()
                frames = pipe.generate_frames(ids, 30 + int(len(text) * 1.6), Sampler(seed=1000 * spk + n))
                gen_t = time.time() - t0
                audio = pipe.decode(frames)
                if not len(audio):
                    raise RuntimeError(f"{name} speaker {spk} sentence {n}: empty speech output")
                total_t = time.time() - t0
                secs = len(audio) / cfg["sample_rate"]
                wav = os.path.join(args.out, f"{name}_speaker{spk}_s{n}.wav")
                write_wav(wav, audio, cfg["sample_rate"])
                row = {"speaker": spk, "sentence": n, "frames": len(frames), "audio_s": round(secs, 2),
                       "gen_s": round(gen_t, 2), "rtf": round(total_t / max(secs, 1e-6), 2),
                       "hit_max_frames": len(frames) >= 30 + int(len(text) * 1.6)}
                if asr is not None and len(audio):
                    hyp = asr(resample_poly(audio, 2, 3).astype(np.float32))["text"]
                    row["transcript"] = hyp
                    row["cer"] = round(cer(text, hyp), 3)
                rows.append(row)
                print(name, row, flush=True)
        cers = [r["cer"] for r in rows if "cer" in r]
        if len(cers) != len(rows) or not cers or not np.isfinite(cers).all():
            raise RuntimeError(f"{name}: missing or invalid CER scores; refusing publication")
        report[name] = {
            "rows": rows,
            "mean_cer": round(float(np.mean(cers)), 3) if cers else None,
            "mean_rtf": round(float(np.mean([r["rtf"] for r in rows])), 2),
            "chars_per_second": round(float(np.mean([len(SENTENCES[r["sentence"]]) / max(r["audio_s"], 0.1) for r in rows])), 2),
            "runaway_segments": sum(r["hit_max_frames"] for r in rows),
        }
        print(f"== {name}: mean CER {report[name]['mean_cer']}, mean real-time factor {report[name]['mean_rtf']}", flush=True)

    chosen = None
    if args.choose:
        base = report.get(args.baseline, {}).get("mean_cer")
        if base is None or not np.isfinite(base):
            raise RuntimeError("Baseline CER is missing or invalid; refusing publication")
        for name in args.choose.split(","):
            r = report.get(name)
            if r is None:
                continue
            if r["mean_cer"] is not None and np.isfinite(r["mean_cer"]) and r["mean_cer"] <= base + args.max_cer_increase:
                chosen = name
                break
        chosen = chosen or args.baseline
        report["chosen"] = chosen
        print("Chosen package:", chosen)
    json.dump(report, open(os.path.join(args.out, "quality_report.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    with open(os.path.join(args.out, "quality_report.md"), "w", encoding="utf-8") as f:
        f.write("| package | mean CER | mean real-time factor (CPU) | runaway segments |\n|---|---|---|---|\n")
        for name, r in report.items():
            if isinstance(r, dict):
                f.write(f"| {name} | {r['mean_cer']} | {r['mean_rtf']} | {r['runaway_segments']} |\n")
        if chosen:
            f.write(f"\nChosen for publishing: **{chosen}**\n")
    if chosen and "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a") as f:
            f.write(f"chosen={chosen}\n")


if __name__ == "__main__":
    main()
