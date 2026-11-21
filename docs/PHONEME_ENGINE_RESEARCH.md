# Direct phoneme and native speech integration investigation

Source: the uploaded `free-eloquence-tts-source.zip`, examined locally, especially `native/openevv/docs/api.md`, `docs/status.md`, `docs/language.md`, `reference/phontry.c`, `app/src/main/cpp/evv_jni.c` and `LanguageRouter.kt`.

## Evidence from the engine

- Eloquence/OpenEVV supports phoneme annotations using its own alphabet: a backtick plus bracketed pronunciation, shown in upstream documentation as \`[phoneme-sequence].
- An internal `eciGeneratePhonemes` API reports the phonemes derived from text, but only with a registered callback, queued synthesis mode, and text already queued.
- The engine is a traditional synthesis architecture, not a neural Dhivehi acoustic model. Dhivehi needs independent phoneme analysis and approximate mappings into existing voice inventories.
- The upstream reference has a phoneme test harness (`reference/phontry.c` and `test/harness/phonemes.c`) and documented language-authoring notes.
- Direct phoneme input **must be tested through the native engine**. The Android platform `TextToSpeech` API does *not* promise to interpret Eloquence-specific markup, and the current app does not send such markup.

## Experimental test plan

1. Establish a known working Eloquence/OpenEVV voice with suitable permitted language assets on a personal test device.
2. Record actual phoneme responses for reference English words using `eciGeneratePhonemes`.
3. Create a Dhivehi pronunciation inventory with native-speaker verification, including short/long vowels, nasals, retroflexes, gemination, and glottal stops.
4. Pair each Dhivehi sound with either a real OpenEVV phoneme, a tested phoneme combination, or mark it as unsupported (rather than pretending that spelling substitutions are accurate).
5. Compare pronunciation of words with and without phoneme annotations. Measure intelligibility and onset latency.
6. If enough sounds are supported, implement a new `dv-MV` native language front end; do not advertise this locale to screen readers before it works.
7. Test full interruption behavior and synthesis-to-WAV, including cancellation, long text and UI responsiveness.

## Current boundary

The Java/Kotlin Android app currently calls whichever Android TTS engine is installed and selected by the user. Native engine integration and proper OpenEVV phoneme tests are still pending. It has a basic Thaana transliterator, including an experimental common-case alifu-sukun gemination rule, and user-entered word corrections.

## Redistribution

The original archive contains a signing keystore with embedded credentials and voice/data assets subject to separate license terms. Do not copy signing credentials into GitHub. Public repositories are public distribution regardless of whether the developer currently plans to use the app privately. The code-only import snapshot is intentionally separate from the application.

## Direct native synthesizer implementation and host validation (21 Nov 2026)

Using the exact **user-uploaded Free Eloquence ZIP**, extracted in a local working directory, the native OpenEVV English synthesis engine was compiled **successfully for Linux** with:

```
cd native/openevv
make LANGS="lang/enus" RULES=bytecode -j8
./build/evv -o hello.wav "assalaamu alaikum"
./build/evv -o dhivehi-test.wav "dhivehi raajje maale"
```

The resulting files were inspected as RIFF/WAVE, **16-bit mono PCM, 11025 Hz**. This proves that the upstream native English engine can be built and synthesize speech on the test machine; it **does not** prove Android arm64 compilation, correct Dhivehi pronunciation, or screen-reader latency.

The Android source now contains a direct JNI wrapper `EvvNative`, a streaming `NativeEloquenceBackend`, a direct `NativePreviewPlayer`, and a `NativeWaveExporter`. The optional `native_engine` GitHub workflow mode is configured to build the actual arm64 native library with restored English voice assets in an ephemeral runner. A successful native-enabled GitHub build still needs confirmation.

The native voice speaks the **output of Thaana transliteration**, not Thaana bytes directly. Accurate phonemic mapping for Dhivehi retroflexes, glottal stops, vowel length, loanword rules, and variation remains an unsolved linguistic task. Do not advertise this as a completed Dhivehi phoneme model.
