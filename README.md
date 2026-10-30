# Dhivehi Offline TTS

Accessible Android Kotlin prototype for offline Dhivehi text-to-speech.

## Status (30 October 2026)
- Initial Android project and accessible Thaana text editor.
- Import UTF-8 TXT files using Android file picker.
- Five voice selections reflecting the five-speaker online demo.
- Import and play an existing audio file offline; pause, stop, change playback speed, and copy/export the original audio file (no WAV conversion).
- Android GitHub Actions debug APK workflow.
- **Speech generation is NOT implemented yet.** The user-tested CSM-1B Dhivehi model has not been demonstrated on 8 GB Android hardware, and there is no Android-native CSM runtime included.
- No paid API or server connection in the prototype.

Model feasibility and testing requirements: [docs/MODEL_FEASIBILITY.md](docs/MODEL_FEASIBILITY.md).

## Model
https://huggingface.co/alakxender/csm-1b-dhivehi-5-spk-gd

Model weights total roughly 6.6 GB. Do not download them to Android until on-device execution is verified. A model's published license and any download terms must be checked before redistribution.

## Build
GitHub Actions: **Android Debug APK** workflow (runs on push). Download the `dhivehi-offline-tts-prototype` artifact when successful.

Local build uses JDK 17 and Gradle 8.9: `gradle :app:assembleDebug`.

## Planned
Prove local Android speech synthesis with all required model components; test memory and voice quality on Samsung Galaxy S23 FE (8 GB RAM); add verified resumable download, synthesis, playback, WAV export, then optional system-wide Android TTS.
