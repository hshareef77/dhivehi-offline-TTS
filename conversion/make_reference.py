"""Produce reference data for the Kotlin engine tests (engine/src/test).

Writes into --out:
  tokenizer_cases.json  [{text, ids}] from the Hugging Face `tokenizers` library
  reference.json        [{text, speaker, prompt_ids, frames}] greedy generations of the
                        Python ONNX pipeline (csm_pipeline.py) on the given package
  ref_<n>.wav           decoded audio of each case

The Kotlin test must reproduce the same prompt ids and the same greedy frames.
"""

import argparse
import json
import os

from tokenizers import Tokenizer

from csm_pipeline import CsmOnnx, Sampler, write_wav

TOKENIZER_CASES = [
    "ދިވެހިރާއްޖެ އަކީ ރީތި ޤައުމެކެވެ.",
    "މެލޭޝިއާގައި އިތުރުކުރާ ޓެކްސް، ދިވެހި ދަރިވަރުންނަށް ބުރައަކަށް ނުވާނެ ގޮތެއް ހޯދައިދޭނަން: ހައިދަރު",
    "[0]އައްސަލާމު ޢަލައިކުމް!",
    "[4]ކިހިނެއްތަ؟  2025 ވަނަ އަހަރު",
    "Hello, world! It's 3:45pm — ok?",
    "Mixed ދިވެހި and English 123456789 text.\n\nNew paragraph\twith tab",
    "   leading and trailing spaces   ",
    "ޝުކުރިއްޔާ 🙂 emoji and ﷲ ligature",
    "'S 'll 'RE apostrophes don't",
]

GEN_CASES = [
    ("ދިވެހިރާއްޖެ އަކީ ރީތި ޤައުމެކެވެ.", 0),
    ("މާލެ އަކީ ވެރިރަށެވެ.", 3),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--package", required=True)
    ap.add_argument("--tokenizer-json", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--frames", type=int, default=8)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    tok = Tokenizer.from_file(args.tokenizer_json)

    cases = [{"text": t, "ids": tok.encode(t, add_special_tokens=False).ids} for t in TOKENIZER_CASES]
    json.dump(cases, open(os.path.join(args.out, "tokenizer_cases.json"), "w", encoding="utf-8"), ensure_ascii=False,
              indent=1)

    pipe = CsmOnnx(args.package)
    cfg = pipe.cfg
    refs = []
    for n, (text, spk) in enumerate(GEN_CASES):
        ids = [cfg["bos_token_id"]] + tok.encode(f"[{spk}]{text}", add_special_tokens=False).ids + [cfg["eos_token_id"]]
        frames = pipe.generate_frames(ids, args.frames, Sampler(greedy=True))
        audio = pipe.decode(frames)
        write_wav(os.path.join(args.out, f"ref_{n}.wav"), audio, cfg["sample_rate"])
        refs.append({"text": text, "speaker": spk, "prompt_ids": ids, "frames": frames, "samples": int(len(audio))})
        print(f"case {n}: {len(ids)} prompt tokens, {len(frames)} frames, {len(audio)} samples")
    json.dump(refs, open(os.path.join(args.out, "reference.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
