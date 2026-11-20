# Dhivehi F5-TTS loading test — 20 November 2026

The approved real checkpoint loaded successfully and generated one diagnostic audio sample.
This is a CPU Python test, not Android integration or model conversion.

Successful run: https://github.com/hshareef77/dhivehi-offline-TTS/actions/runs/44708219726
Tested source commit: `ae2dc335d211d02544058e4ba7002c9204f11063`.
Artifact: `f5-model-check`, ID `14617225074`.

## Model checked

- Repository: `Serialtechlab/f5-tts-dhivehi`.
- Revision: `64209f9b371af026a348b20aeb364cc64b68e4ea`.
- Checkpoint SHA-256: `01e4c7d8244604211eaa71b52d07b43a4482bb2fa106449672b336bf0a09911b`.
- Original checkpoint: 5,394,674,340 bytes (5.39 GB).
- Architecture: F5TTS_v1_Base / DiT; 337,127,012 model parameters.
- Custom vocabulary: 2,604 entries, including the added Thaana characters.
- Vocoder: Vocos at 24,000 Hz.
- Model license recorded by the publisher: CC-BY-NC-4.0.
- The original checkpoint includes model, EMA, optimizer, scheduler, and update state.
  Its 5.39 GB size is not a measured Android inference-package size.

The checkpoint was opened with PyTorch's weights-only loader. The official F5 implementation
then loaded its EMA weights with strict state-dict matching. Model and vocoder loading
completed in 5.24 seconds on the test runner.

## Speech generation

Input text:

> މިއަދު މޫސުން ވަރަށް ރަނގަޅު.

- Output: `f5_dhivehi_sample.wav`, mono 24 kHz PCM16.
- Audio duration: 1.728 seconds.
- Generation time: 135.67 seconds.
- Real-time factor: 78.51 on GitHub CPU, not a phone measurement.
- Inference: 32 steps, seed 42.
- Output checked for non-empty, finite, non-silent samples.

Reference audio was freshly generated with this project's published CSM package,
speaker 4, revision `0ace956d1089927847d7c5d584c6a0ada0cf498f`. Package file sizes and
SHA-256 checksums were verified before generation. No human recording was used.
The diagnostic previously depended on an unavailable old CI artifact; it now generates
the reference itself. FFmpeg is installed explicitly for the official audio preprocessing.

## Limits and continuation

- Loading and basic waveform generation passed. Intelligibility, pronunciation, voice
  consistency, and quality have not been established by listening or ASR testing.
- The sample uses a synthetic reference; it is not a benchmark using a natural reference.
- CPU generation was slow. No Android speed, peak-memory, or quality claim follows from this run.
- F5-TTS has not been exported or quantized for the Android engine and has not been added
  to the APK. The existing CSM app, package name, signing key, and five voices are unchanged.
- Future Android work requires a separate F5/Vocos conversion and inference path, numerical
  validation, suitable reference-audio handling, and device performance testing.
