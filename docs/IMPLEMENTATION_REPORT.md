# Implementation report (30 October 2026)

## Summary

The complete offline speech pipeline for CSM-1B Dhivehi is implemented: model conversion,
quantization, an on-device Kotlin inference engine on ONNX Runtime, the voice-model
downloader and the accessible Android app. The pipeline has been verified on a
random-weight CSM model with the real architecture. The **real model has not yet been
converted**: this development environment cannot reach Hugging Face. The conversion
workflow runs on GitHub Actions and needs the `HF_TOKEN` secret (see
[INSTALL_AND_TEST.md](INSTALL_AND_TEST.md), section 1). Until it has run, the app correctly
reports that no voice model is published, and it cannot speak yet.

## Model architecture (as implemented)

| part | details |
|---|---|
| text | Llama 3 byte-level BPE (128,256 tokens). Prompt `<|begin_of_text|>[speaker]text<|end_of_text|>`, speaker 0–4 |
| backbone | Llama-1B: 16 layers, hidden 2048, 32 heads / 8 KV heads, head dim 64, llama3 RoPE. Predicts codebook 0 of each frame |
| depth decoder | Llama-100M: 4 layers, hidden 1024, 8 heads / 2 KV heads. Input: backbone hidden state + code embeddings, projected 2048→1024. 31 per-position heads predict codebooks 1–31 |
| frames | 32 codebooks × 2051 ids (2048 Mimi codes + specials), 12.5 frames/s (80 ms each) |
| stopping | when codebooks 0–30 of a frame are all 0 |
| sampling | temperature 0.9, top-k 50 (backbone and depth decoder) |
| audio | Mimi decoder: 32 × F codes → 24 kHz mono, 1920 samples per frame |

The checkpoint is fp32: 1.63 billion parameters = 6.6 GB, including the Mimi codec.

## Why ONNX Runtime

* One runtime covers all three networks, including the Mimi decoder (convolutions, transposed
  convolutions, a sliding-window transformer). GGUF/llama.cpp has no CSM depth decoder or Mimi
  support. ExecuTorch would need a custom export of each stage and gives no tooling advantage
  here.
* Same Java API on Android (`onnxruntime-android`) and desktop, so the exact Kotlin engine code
  that ships in the app is tested on the JVM against the real converted model in CI.
* MatMulNBits 4-bit/8-bit weight-only quantization with ARM64 int8 dot-product kernels
  (`accuracy_level=4`).

The Hugging Face model does not export directly: dynamic caches, Python control flow and
untraceable mask construction. `conversion/csm_modules.py` re-implements the inference graphs
over the **same weight tensors**, with explicit KV-cache inputs and outputs, so the app drives
the autoregressive loop itself.

## Package layout and expected sizes

| file | precision | estimated size |
|---|---|---|
| backbone.onnx | 4-bit weights (block 32), fp32 compute | ~0.55 GB |
| depth_decoder.onnx | 4-bit layers, fp16 codebook heads | ~0.20 GB |
| mimi_decoder.onnx | fp32 | ~0.16–0.25 GB |
| text_embeddings.bin | int8 per row, memory-mapped | ~0.26 GB |
| audio_embeddings.bin | int8 per row, memory-mapped (shared with the depth decoder, tied weights) | ~0.13 GB |
| tokenizer, config, manifest | | ~3 MB |
| **total download** | | **~1.3–1.4 GB** (the 8-bit fallback is ~2.4 GB) |

The exact sizes are printed by the conversion run and stored in the published `manifest.json`.

**Estimated phone memory:** about 1.0–1.3 GB of native memory while generating. Embedding
tables are memory-mapped, so only the rows used are loaded. The KV cache is under 25 MB per
segment. This fits an 8 GB phone with a wide margin; the app refuses to load the model if
Android reports less than 1.2 GB free and asks the user to close apps.

**Speed:** not yet measured on a phone. Each 80 ms frame needs one backbone step (1B parameters)
plus 31 depth-decoder steps (100M parameters). My estimate for the S23 FE CPU is 3–6 seconds of
computing per second of speech, so a sentence takes several seconds. The CI quality report
measures the real-time factor on the GitHub CPU.

## Verified (in this environment, on the JVM/desktop CPU)

1. **Export parity, exact:** greedy generation with Hugging Face Transformers
   `CsmForConditionalGeneration.generate` vs the exported ONNX graphs on a random-weight CSM
   with the real structure and the real Mimi configuration. 30 frames × 32 codebooks were
   token-for-token identical. Mimi decoder output matched to 2·10⁻⁶. With the shipping fp16
   codebook heads, logits differ by about 0.002, which is expected.
2. **Quantization:** 4-bit MatMulNBits packages load and generate in ONNX Runtime 1.31.1.
3. **Kotlin engine:** reproduces the Python ONNX pipeline exactly (greedy token match 1.0000).
   Long text is segmented into a single WAV for each of the five speakers. Cancellation works.
4. **Kotlin tokenizer:** identical to the Hugging Face `tokenizers` library on 9 test strings
   (Thaana, fili, English, digits, punctuation, emoji, whitespace), using a byte-level BPE
   trained with the exact Llama 3 pre-tokenizer settings.
5. Text segmentation, WAV writing and top-k sampling: unit tests.

## Verified by CI once `HF_TOKEN` is added (Convert voice model workflow)

1. All checkpoint weights load. The run fails if any weight would be random.
2. Prompt tokenization matches the model's own processor/chat template.
3. Greedy parity of the real fp32 export against Hugging Face, and Mimi parity.
4. Real Dhivehi speech from all five speakers for fp32, 8-bit and 4-bit packages; WAV samples
   in the run's artifact.
5. Intelligibility: character error rate from a Dhivehi speech recogniser
   (`alakxender/w2v-bert-2.0-dhivehi-cv`), quantized vs fp32. The 4-bit package is published
   only if its error rate is within 0.08 of fp32; otherwise the 8-bit package is published;
   if neither passes, nothing is published.
6. Kotlin engine on the real package: real Llama 3 tokenizer equivalence, generation parity
   with Python, WAV output for all five speakers.
7. Upload to `Hshareef77/csm-1b-dhivehi-5spk-onnx`, with the manifest uploaded last so the app
   never sees a half-uploaded package.

## Not verified yet (needs your phone)

* Download and installation of the real package on the phone, and resuming after
  interruption.
* Speed, peak memory, heat and stability on the Galaxy S23 FE.
* TalkBack and Jieshuo+ behaviour. The UI uses standard widgets, headings, labels, a polite
  live region and explicit announcements, but has not been tried with a screen reader.
* Airplane-mode operation (the code makes no network calls after the download).
* Audio quality of the 4-bit package as you hear it (the recogniser score is an objective
  proxy).

## Known limitations and risks

* **Speed** is the main risk: generation is expected to be slower than real time. The app
  generates the whole text, then plays it; it does not yet play part 1 while generating part 2.
  If speed is unacceptable, the next steps are: play while generating (the engine already
  reports each finished part), ONNX Runtime XNNPACK or NNAPI execution providers, and fewer
  threads to avoid big/little core contention.
* Each sentence is generated independently (no audio context), so voice timbre can vary
  slightly between sentences.
* Numbers, abbreviations and Latin text are read however the model learned them; there is no
  text normalisation (for example, digits to Dhivehi words).
* Speaker 4's gender is listed inconsistently in the source model card; it is labelled
  "Voice 5".
* The model card says "research and educational use". Apache-2.0 permits redistribution with
  attribution; the published copy carries the original attribution and restrictions and is
  used for personal, non-commercial accessibility.
* A system-wide Android TTS engine (so other apps could use these voices) is not implemented.

## Continuation path

1. Add `HF_TOKEN` and re-run **Convert voice model**. Read its quality report and listen to the
   samples.
2. Install the newest **Android APK** artifact and run the phone test list.
3. Based on the measured speed: add playback during generation and tune threads/execution
   providers.
4. Optional: Android `TextToSpeechService` integration, and Dhivehi number normalisation.
