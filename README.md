# Dhivehi Pronunciation Lab (Android)

**Current goal:** explore fast, offline Dhivehi speech through the existing Eloquence-style voices, with a native accessible Android interface. The previous 6.6 GB CSM model and five-speaker offline narration prototype are **no longer the current project direction**.

## New priority: direct Dhivehi phonemes (November 2026)

**Direct phoneme synthesis is now the default when the native Eloquence library is present.** Two new pieces separate Dhivehi linguistics from English voice mappings: [DhivehiPhonology.kt](app/src/main/kotlin/com/hussainshareef/dhivehiofflinetts/dhivehi/DhivehiPhonology.kt) represents Dhivehi phonemes and contrasts using IPA, while [DhivehiPhoneticEngine.kt](app/src/main/kotlin/com/hussainshareef/dhivehiofflinetts/dhivehi/DhivehiPhoneticEngine.kt) produces native OpenEVV phoneme annotations. JNI explicitly enables the engine's phonetic annotation input mode for direct Speak, system TTS and native WAV export. This bypasses ordinary English spelling-to-pronunciation rules for Thaana input.

A separate checkbox allows returning to **Latin fallback** for comparison. The UI can show the Dhivehi IPA analysis, the exact native SPR commands, and store corrected SPR pronunciations per word. We added phonology and phoneme safety regression tests.

An actual host-native OpenEVV WAV demonstration lasting **5.90 seconds** has been generated using these experimental native phoneme instructions. See [listening experiment and test text](docs/DHIVEHI_DIRECT_PHONEME_EXPERIMENT.md). The native phoneme path is real, **but the English Eloquence voice does not yet produce linguistically verified Dhivehi pronunciation**, and the Android-native build has not passed verification.

## Current working design

The app lets you enter or import Thaana text, inspect an **experimental** Latin phonetic conversion, play it with an installed Android TTS engine, choose among the selected engine's reported offline voices, stop speech, save per-word pronunciation corrections offline, adjust speaking speed, play longer text in bounded segments, and request WAV audio generation using the Android system TTS API. Automated JVM checks cover sample spellings, word overrides, basic sukun handling, mixed-script handling and chunking. WAV file generation currently has a text-length limit; long passages can be spoken but cannot yet be exported as one WAV. It uses the phone's currently selected TTS engine, so **install and select Eloquence in Android TTS settings** to hear the experiment with Eloquence voices.

**Important:** The consolidated source-code changes have been committed and CI runs tests plus an APK build on pushes, but passing build status and physical-device speech testing have not yet been confirmed. The app does not yet include its own functioning embedded Eloquence engine. It will not generate correct Dhivehi speech until pronunciation rules and the voice/phoneme mappings have been tested and refined. English-language TTS voices are imperfect substitutes for Dhivehi.

## Source reference

An automated import of public [evvdroid](https://github.com/trypsynth/evvdroid) reference code has populated `third_party/eloquence-reference`. This is **not** a byte-identical upload of the user's supplied `free-eloquence-tts-source.zip`. The import excludes IBM-derived voice resources, proprietary-derived tables, signing credentials and build artifacts. See [import notice](third_party/eloquence-reference/IMPORT_NOTICE.md).

Native phoneme feasibility notes: [docs/PHONEME_ENGINE_RESEARCH.md](docs/PHONEME_ENGINE_RESEARCH.md).\n\n## Nine native Eloquence voices (current development)

The app now exposes the original voice presets **Reed, Shelley, Bobby, Rocko, Glen, Sandy, Grandma, Grandpa and Luna** through an accessible selector. The selection is persisted locally and used in:

- Direct native Speak playback;
- Direct native Generate audio (WAV);
- The experimental system-wide Dhivehi TTS service.

The preset mapping follows the uploaded Free Eloquence `Eci.kt` and `EvvEngine.kt`: preset 1–8 is copied to scratch voice 9, then to current voice 0. Luna uses the Grandpa-derived custom shape from upstream. These voices are only actually heard when an APK includes a successfully compiled native `libevvjni.so` and its required English-language speech data. Switching voices **does not** automatically solve Dhivehi phoneme accuracy.

The implementation also fixes the native voice test button's Kotlin closure syntax. A successful Android CI build is still not confirmed.

## Direct native Eloquence integration (new)

A **direct synthesis implementation now exists in the Android source**:

- `EvvNative.kt` uses the JNI function names from the uploaded Free Eloquence source.
- `NativeEloquenceBackend.kt` streams PCM directly into Android's system TTS callback at 11025 Hz; the native path does not depend on a second TTS engine or intermediate WAV.
- `NativePreviewPlayer.kt` plays the native speech through Android `AudioTrack` on a worker thread.
- `NativeWaveExporter.kt` saves direct native PCM as a proper WAV file. The Generate audio button selects it automatically when the native library is installed.
- Fast Stop requests signal the native engine instead of blocking Android's UI thread.

**Build distinction:** The ordinary debug APK still uses the selected external Android TTS engine because it has no `libevvjni.so`. To build an APK *with* the actual native engine, choose Actions → **Android APK build and pronunciation tests** → **Run workflow** → **native_engine = true**. This manual mode restores the full OpenEVV source and English voice data in the GitHub runner, uses Android NDK r26 to compile the arm64 native library, and packages it into the APK. Native files are not checked into this public repository, and this native mode does not publish a GitHub Release. The produced GitHub Actions artifact should only be used in accordance with the rights to the voice resources.

The direct code is committed but **native-enabled compilation and actual speech have not been verified**. This is not yet a certified Dhivehi voice. Our Thaana language rules still approximate Dhivehi using English voice phonemes, and fidelity needs real listening tests.

## Experimental system-wide TTS adapter (new)

A standard Android `TextToSpeechService` is registered but **disabled by default** so it cannot accidentally replace a screen reader's reliable voice. It exposes an experimental `dv-MV` voice by translating Thaana to an approximate Latin pronunciation, delegating synthesis to a **different installed offline-capable TTS engine**, verifying its WAV PCM output, and relaying it to Android's speech callback.

In the app: choose an underlying installed speech engine, tap **Use selected engine**, choose its offline voice, and test **Speak**. Only then can you choose **Enable experimental system TTS**. The app also provides **Disable experimental system TTS**. Never select this experimental service as TalkBack's only voice during initial testing; keep a known-working TTS engine installed. This prototype is **not** a native Eloquence integration and its utterances may start slowly because it synthesizes whole WAV files before emitting audio. Do not depend on it for daily screen-reader navigation yet.

See [system-engine source](app/src/main/java/com/hshareef77/dhivehiofflinetts/DhivehiTtsService.kt) and [WAV parser](app/src/main/kotlin/com/hussainshareef/dhivehiofflinetts/dhivehi/WavePcm.kt).

## APK builds and signing

GitHub Actions executes JVM tests and builds a debug APK on each main-branch push. In Actions → Android APK build and pronunciation tests → Run workflow, a manual `publish_preview` switch publishes a **prerelease** to the Releases page only if tests, signed APK build, and configured signing secrets succeed. If a complete set of keystore, password, alias and key-password secrets exists, the workflow additionally builds a signed release APK (without copying the key into the repository). Accepted secret name pairs: `ANDROID_KEYSTORE_BASE64` or `KEYSTORE_BASE64`; `ANDROID_KEYSTORE_PASSWORD` or `KEYSTORE_PASSWORD`; `ANDROID_KEY_ALIAS` or `KEY_ALIAS`; `ANDROID_KEY_PASSWORD` or `KEY_PASSWORD`. The release build is skipped if any required value is missing. Successful build status is **not yet confirmed**.

## Development sequence

1. Validate an Android debug build and the phonetic experiment against a real installed TTS voice.
2. Build a reviewed Dhivehi consonant/vowel/sukun pronunciation corpus and automated unit tests.
3. Add exact phoneme input mapping for Eloquence/OpenEVV; avoid assuming English transliteration is sufficient.
4. Integrate a verified on-device speech backend and expose genuine Dhivehi (`dv-MV`) once it passes tests.
5. Benchmark latency, rapid speech interruption, sentence segmentation, long text, voice controls and WAV output.
6. Add a user pronunciation dictionary with accessible corrections and mixed Dhivehi/English handling.
7. Evaluate redistributability separately before any public release of restricted voice assets.

## How to test

Install a working Android text-to-speech engine first. In Android text-to-speech settings choose the desired engine. Open this app, enter Dhivehi text, select **Show pronunciation** and then **Speak**. **Generate audio** requests WAV synthesis from the installed engine and opens Android's file-saving interface when completed.

The legacy CSM model is not downloaded or used by this app.

## Source and runtime limitations (November 2026)

The exact user-provided Free Eloquence source ZIP is not present as a complete repository archive. Direct integration uses its documented JNI API and the upstream reference source. The separate public reference snapshot does not contain the IBM-derived acoustic/language assets needed to run native OpenEVV. **Native Eloquence integration code has been implemented, but no native-enabled APK has passed verification yet.** Standard builds still require a separate installed offline TTS engine; opt-in native builds are designed to bundle the native engine. The experimental system service is disabled by default for screen-reader safety. When the native library is unavailable it buffers a WAV from another engine, which is not designed for low-latency navigation; the native path streams PCM directly.

Building and speech testing against a real Android device remain unverified in this chat. Do not label a generated artifact as a validated Dhivehi engine without an actual speech test.
