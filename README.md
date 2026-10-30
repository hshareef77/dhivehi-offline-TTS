# Dhivehi Offline TTS

Accessible Android app that generates Dhivehi (Thaana) speech **on the phone, without
internet**, using the five-speaker CSM-1B Dhivehi model
([alakxender/csm-1b-dhivehi-5-spk-gd](https://huggingface.co/alakxender/csm-1b-dhivehi-5-spk-gd)).
The voice model is downloaded once after installation; everything after that is offline.
Built for TalkBack and Jieshuo+ users.

## How it works

```
Thaana text ─► Llama-3 BPE tokenizer (Kotlin) ─► "[speaker]text" prompt
            ─► CSM backbone (1B, ONNX, 4-bit)  ─► codebook 0 of each 80 ms audio frame
            ─► depth decoder (ONNX)            ─► codebooks 1..31 of the frame
            ─► repeat until the model ends the sentence
            ─► Mimi decoder (ONNX)             ─► 24 kHz audio ─► WAV / playback
```

* `conversion/` — Python scripts that convert the original 6.6 GB checkpoint into the
  on-device package (ONNX graphs with explicit KV cache, int8 embedding tables, 4-bit
  weight-only quantization, SHA-256 manifest), check parity against Hugging Face
  Transformers, and measure intelligibility with a Dhivehi speech recogniser.
* `engine/` — pure Kotlin speech engine on the ONNX Runtime Java API: tokenizer,
  memory-mapped embeddings, sampling, generation loop, Mimi decoding, long-text
  segmentation, WAV writer. The same code runs on Android and in JVM tests.
* `app/` — the Android app: model download manager, background generation, playback,
  export, saved recordings, accessibility.
* `.github/workflows/` — `Android APK` builds the APK (and runs engine tests) on every push;
  `Convert voice model` converts, validates and publishes the voice model package.

## Status

See [docs/IMPLEMENTATION_REPORT.md](docs/IMPLEMENTATION_REPORT.md) for exactly what has been
verified and what still needs testing on a real phone, and
[docs/INSTALL_AND_TEST.md](docs/INSTALL_AND_TEST.md) for installation and the phone test list.

## Features

* Enter, paste, share-to-app, or import a UTF-8 TXT file of Dhivehi text.
* Five voices: Female voice 1, Male voice 1, Female voice 2, Male voice 2, Voice 5
  (model speaker ids 0–4).
* Generate speech locally; long text is split into sentences and generated part by part.
* Cancel generation; progress is announced (at most every 15 seconds).
* Optional "speak each part as soon as it is ready" for long texts.
* Play, pause, stop, back/forward 10 seconds, position slider, speed 0.5×–2.0×.
* Recordings are saved automatically; open, delete, or export them as WAV files.
* Voice model manager: shows size and free storage, downloads in the background with
  resume, verifies every file's SHA-256 checksum, installs atomically, checks for
  updates, deletes the model.

## Building

GitHub Actions builds the APK on every push (`Android APK` workflow, artifact
`dhivehi-offline-tts-apk`). Local build: JDK 17, Android SDK 35, Gradle 8.10:
`gradle :app:assembleDebug`. Engine tests without Android: `gradle -p engine test`.

## Licence

App code: see repository. Voice model: Apache-2.0 (source model and CSM-1B), Mimi codec by
Kyutai. The source model is described as being for research and educational use and must
not be used for impersonation or deception; this app is for personal, non-commercial use.
