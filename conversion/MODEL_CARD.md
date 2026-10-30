---
license: apache-2.0
language:
- dv
tags:
- text-to-speech
- dhivehi-tts
- onnx
- android
base_model: alakxender/csm-1b-dhivehi-5-spk-gd
library_name: onnxruntime
---

# CSM-1B Dhivehi (5 speakers) — ONNX package for offline Android use

This repository contains a converted and quantized copy of
[`alakxender/csm-1b-dhivehi-5-spk-gd`](https://huggingface.co/alakxender/csm-1b-dhivehi-5-spk-gd)
(itself fine-tuned from [`sesame/csm-1b`](https://huggingface.co/sesame/csm-1b)) for the
[Dhivehi Offline TTS Android app](https://github.com/hshareef77/dhivehi-offline-TTS).
It is downloaded once by the app; speech is then generated on the phone without internet.

Conversion scripts, parity tests and the quality report are in the GitHub repository
(`conversion/`). No retraining or distillation was done: the weights are the original
fine-tuned weights, re-exported and quantized.

## Contents

| file | content |
|---|---|
| `backbone.onnx` (+ `.data`) | Llama-1B backbone step with explicit KV cache, weight-only quantized (MatMulNBits) |
| `depth_decoder.onnx` (+ `.data`) | depth decoder step (codebooks 1–31), weight-only quantized; codebook heads in fp16 |
| `mimi_decoder.onnx` (+ `.data`) | Mimi codec decoder (fp32), 32 × F codes → 24 kHz audio |
| `text_embeddings.bin`, `audio_embeddings.bin` | int8 per-row embedding tables (`DVQ8` format) |
| `tokenizer/` | Llama 3 byte-level BPE vocabulary and merges |
| `model_config.json` | constants (speakers 0–4, sampling temperature 0.9, top-k 50) |
| `manifest.json` | size and SHA-256 of every file, checked by the app |

Quantization details and measured quality (speech-recogniser character error rate vs the
fp32 export) are in `quality_report.md` in this repository.

## Speakers

`0` female, `1` male, `2` female, `3` male, `4` (listed inconsistently in the source card).

## License and attribution

Apache-2.0, inherited from the source model and CSM-1B. Mimi codec by Kyutai.
The source model card states that the checkpoint is intended for research and educational
use, and must not be used for impersonation, deepfakes, deception, harassment,
misinformation or manipulation. All voices are synthetic. This converted copy is published
for personal, non-commercial accessibility use (a blind user's offline Dhivehi screen-reading aid).
