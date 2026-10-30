# Implementation report (30 October 2026)

## Summary

The real model has now been converted, validated, and published. Successful conversion run:
https://github.com/hshareef77/dhivehi-offline-TTS/actions/runs/41018337742
(source commit `55092e6ba8c51a8b4d564c5fb231eb8488612f57` on `ccr-e6a84a0a-pdlhzq`).
The public download is `Hshareef77/csm-1b-dhivehi-5spk-onnx`, manifest version **5**,
package **csm-1b-dhivehi-q4**, with all five speakers. Files were uploaded first and the
manifest last. The app can now download the real voice package.

## Measured real-model validation (30 October 2026)

- Download size: **1,443,314,430 bytes** (1.443 GB / 1.344 GiB), 12 verified package files.
- Original-model greedy parity: 12 frames, token match **1.0000**, codebook-0 match **1.0000**.
- Mimi audio parity: maximum absolute difference **3.8743e-7** (limit 1e-3).
- Int8 embedding greedy match against dense embeddings: **0.705729** (informational;
  autoregressive greedy trajectories can diverge). Real speech quality is measured below.
- Kotlin tokenizer: 9 cases identical to Hugging Face tokenizers; both greedy reference
  cases matched Python exactly (**1.0000**). Real-model engine tests passed, including
  cancellation and two-sentence WAV generation for all five speakers.
- All 45 quality samples produced audio and finite CER scores; no segment hit its frame limit.

| evaluated package | mean character error rate | mean real-time factor, GitHub CPU | runaway segments |
|---|---:|---:|---:|
| fp32 baseline | 0.087 (8.7%) | 27.23 | 0 |
| q8 | 0.060 (6.0%) | 15.25 | 0 |
| q4, published | 0.067 (6.7%) | 11.17 | 0 |

The q4 result is within the allowed 0.08 absolute CER increase over baseline, so the
workflow selected q4. These are three sentences per voice with fixed sampling seeds,
not a broad language benchmark. The baseline follows the existing pipeline: fp32
MatMul weights, exported int8 embedding tables, and shipping fp16 depth heads. The
recogniser used raw CTC because its optional KenLM decoder was unavailable; recognition
scores are an objective proxy, not a listening assessment. No subjective listening
check or real-phone test has been performed here.

A real-time factor of 11.17 means about 11.17 seconds of computation per second of audio
on this GitHub runner; this is **not** a Samsung Galaxy S23 FE speed measurement.
Kotlin long-text runs produced 4.49–8.17 seconds of audio in 45.9–82.0 seconds.

The `voice-model-report` artifact contains parity.json, both package manifests, the
quality reports, 45 evaluation WAVs, five Kotlin WAVs, and passing test XML reports.

### Conversion fixes made during this run

1. The private-repository runner had only 7 GiB RAM and 13 GiB free disk after swap,
   rather than the assumed 16 GiB RAM. Export exhausted its disk and prevented log upload.
   Removing unused preinstalled SDKs/tool caches and disabling pip/Xet caches left
   about 28 GiB free after 20 GiB swap; the workflow now requires at least 25 GiB free.
2. CER normalization now preserves Thaana combining vowel marks. Missing recogniser
   initialization, empty audio, and missing/non-finite CER now fail closed, rather than
   allowing an unmeasured package to be selected. Local checks covered unchanged Thaana,
   differing vowel marks, punctuation/whitespace, and Python syntax.
3. Android package name, app/engine source, stable signing key, and all five speaker IDs
   were preserved. Work remains on the requested branch; it has not been merged into main.

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

## Package layout and original size estimates

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

**Speed:** the actual GitHub CPU results are listed above; phone speed is not yet measured. Each 80 ms frame needs one backbone step (1B parameters)
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
6. Export parity holds with both transformers 5.22 and 4.57; 4.57 uses the source
   checkpoint's own key layout. The conversion workflow falls back to 4.57 automatically.
7. **APK build:** GitHub Actions compiles and signs the app (run 15: engine tests passed,
   debug APK about 30 MB, artifact `dhivehi-offline-tts-apk`). Every future build is signed
   with the same committed key, so updates install in place.

## Verified by the successful real-model CI run

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

* **Speed** is the main risk: generation is expected to be slower than real time. With
  "Speak each part as soon as it is ready" (on by default) the first sentence plays as soon
  as it is generated while later sentences are generated, with pauses between parts when
  generation is slower than speech. Next options if speed is unacceptable: ONNX Runtime
  XNNPACK or NNAPI execution providers, and thread tuning for big/little cores.
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

1. Install the fresh **Android APK** artifact built after model publication and follow
   `docs/INSTALL_AND_TEST.md`, especially model download/resume, airplane mode, all five
   voices, playback/export, long-text cancellation, and TalkBack/Jieshuo+ navigation.
2. Listen to the published-model samples and assess quality on the phone.
3. Measure phone speed, memory, heat, and stability. If needed, tune threads and execution
   providers in the order specified in the handoff; do not reduce quality without consent.
4. Optional future scope: Android `TextToSpeechService` and Dhivehi number normalisation.

## Update: numbers, speed setting, smaller model updates

* **Numbers are read aloud.** `engine/.../DhivehiNumbers.kt` writes digits out as Dhivehi
  words before generation: 11 → އެގާރަ, 2025 → ދެހާސް ފަންސަވީސް, 3.5 → ތިނެއް ޕޮއިންޓް ފަހެއް,
  50% → ފަންސާސް އިންސައްތަ, 1,500 → ހާސް ފަސްސަތޭކަ. Numbers with a leading zero or more
  than 12 digits are read digit by digit. Arabic-Indic digits are converted. The word lists
  sit in one place for correction by a native speaker (please report any wrong word).
* **Speed and quality setting.** Best quality (32 codebooks), Balanced (24), Fastest (16).
  Each codebook is one depth-decoder step per 80 ms frame; that decoder is about three
  quarters of the computing. Expected time saved: about 20% (24) and 40% (16). The Mimi
  decoder now accepts any number of codebooks from 1 to 32 in the same file; it matches
  Hugging Face decoding for 32 and 16 codebooks (max difference 2·10⁻⁶). Kotlin and Python
  agree exactly with 16 codebooks. The conversion run measures speech-recogniser error and
  speed for 32/24/16 (`q4`, `q4-cb24`, `q4-cb16` in quality_report.md).
* **Model updates reuse unchanged files.** Files whose SHA-256 is unchanged are linked from the
  installed model instead of downloaded, so this update should fetch only the new Mimi decoder
  (about 230 MB), provided the other exported files are byte-identical.
