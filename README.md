# Dhivehi Offline TTS — OmniVoice

Accessible native Kotlin Android app for Dhivehi speech generation and optional voice
cloning. The active engine is now OmniVoice, replacing the earlier five-speaker CSM engine
at the owner's request. Package name and stable signing key are preserved for updates.

## Installation

Install the APK from the repository's **OmniVoice prerelease** after validation completes.
The APK contains no model weights. Use **Download voice model** inside the app; the exact
complete size and required free storage are shown before downloading (about 1.37 GB).
Downloads resume, all files are SHA-256 checked, and installation replaces the old CSM
package only after the new package is complete. Saved recordings are kept.

After the download, generation and reference-voice preparation work in airplane mode.
There is no Python runtime, paid speech API, or web view in the app.

## Features

- Enter, paste, share or import Dhivehi text. Numbers are expanded to Dhivehi words.
- Automatic voice or a saved reference voice; these replace CSM's five fixed speaker IDs.
- Clone a voice from a clear **3–10 second PCM16 or float WAV** and its exact transcript.
  Mono/stereo WAV at 8–96 kHz is converted locally to mono 24 kHz. MP3 is not supported
  for reference import in this version.
- Long text is split at sentence/clause/word boundaries with a bounded audio duration per
  section. Sections are written incrementally into one WAV; cancelling keeps completed audio.
- Optional playback of sections while later sections generate, progress, cancellation,
  playback controls, saved recordings and WAV export.
- Standard accessible Android controls and labelled fields for TalkBack/Jieshuo+.

## Implementation and validation

`engine/` contains the native Qwen BPE tokenizer, 32-step OmniVoice diffusion loop,
classifier-free guidance, audio codec inference, reference WAV reader and saved voice format.
ONNX Runtime is shared between desktop JVM tests and Android. No CSM graph is loaded by the app.

`tools/omnivoice/` downloads pinned, checksum-verified main/decoder components, exports a
**dynamic** encoder from the original codec weights, checks encoder parity at unseen lengths,
and compares native Kotlin audio codes with an independent NumPy reference.

Workflow **Validate OmniVoice and publish APK** publishes a model manifest only after these
checks pass, then builds an APK using the existing stable signing key and publishes a prerelease.
The ordinary Android APK workflow also runs unit tests on pushes.

Numerical correctness is not a listening evaluation. Phone speed, heat, long-text quality and
screen-reader behaviour still need testing on the actual Samsung phone. See
[installation and testing](docs/INSTALL_AND_TEST.md) and [model notices](THIRD_PARTY_NOTICES.md).

## Building

JDK 17, Android SDK 35, Gradle 8.10.2: `gradle :app:assembleDebug`.
JVM tests: `gradle -p engine test`. Real-model parity additionally requires
`-Domni.model.dir=... -Domni.reference.dir=...` (see the validation workflow).

The original CSM sources and historical reports remain in git for reproducibility.
