---
license: cc-by-nc-4.0
language:
- dv
pipeline_tag: text-to-speech
---
# OmniVoice for Dhivehi Offline TTS

This application uses the real **k2-fsa/OmniVoice** pretrained model and the
Higgs Audio V2 codec. This is an Android integration, not a newly trained voice.

- OmniVoice code and decoding algorithm: Copyright 2026 Xiaomi Corp., Apache-2.0.
  https://github.com/k2-fsa/OmniVoice
- Original OmniVoice weights: **CC-BY-NC**, as stated by the upstream model card.
  https://huggingface.co/k2-fsa/OmniVoice
  The community ONNX repository's Apache metadata does not supersede the original
  pretrained weight licence. Use this package for personal, non-commercial purposes.
- Main INT8 ONNX network: converted by gluschenko, revision
  `4d4bb31790c2de902de0d19645fd13cf2d71a88b`.
  https://huggingface.co/gluschenko/omnivoice-onnx
- Audio decoder: gluschenko/higgs-audio-v2-tokenizer-onnx, revision
  `1f2d022a6c999eef9d3cf5e7080df4cc028037ee`.
- Audio encoder: exported from the original OmniVoice codec revision
  `3fc979d1cd72492cd679c5ac2611e0d7ac047ba1`, preserving its weights, with dynamic
  clip length. Higgs Audio codec licence: Boson AI Community License.
  https://huggingface.co/gluschenko/higgs-audio-v2-tokenizer-onnx/blob/main/LICENSE
- ONNX Runtime: Microsoft, MIT. https://github.com/microsoft/onnxruntime

The native decoding implementation follows OmniVoice's 32-step schedule, guidance,
codebook confidence penalties and Qwen text tokenization. Android uses no Python
or online inference service. Model downloads are separate from the APK.

Dhivehi listening quality, long-text consistency and Samsung phone speed must be
assessed on the phone. Successful file generation and numerical parity do not
establish intelligibility. Voice cloning requires a reference recording and its
exact transcript; an automatic voice is also available.
