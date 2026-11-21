# Dhivehi Pronunciation Lab (Android)

**Current goal:** explore fast, offline Dhivehi speech through the existing Eloquence-style voices, with a native accessible Android interface. The previous 6.6 GB CSM model and five-speaker offline narration prototype are **no longer the current project direction**.

## Current working design

The app lets you enter or import Thaana text, inspect an **experimental** Latin phonetic conversion, play it with an installed Android TTS engine, choose among the selected engine's reported offline voices, stop speech, save per-word pronunciation corrections offline, adjust speaking speed, play longer text in bounded segments, and request WAV audio generation using the Android system TTS API. Automated JVM checks cover sample spellings, word overrides, basic sukun handling, mixed-script handling and chunking. WAV file generation currently has a text-length limit; long passages can be spoken but cannot yet be exported as one WAV. It uses the phone's currently selected TTS engine, so **install and select Eloquence in Android TTS settings** to hear the experiment with Eloquence voices.

**Important:** The consolidated source-code changes have been committed and CI runs tests plus an APK build on pushes, but passing build status and physical-device speech testing have not yet been confirmed. The app does not yet include its own functioning embedded Eloquence engine. It will not generate correct Dhivehi speech until pronunciation rules and the voice/phoneme mappings have been tested and refined. English-language TTS voices are imperfect substitutes for Dhivehi.

## Source reference

An automated import of public [evvdroid](https://github.com/trypsynth/evvdroid) reference code has populated `third_party/eloquence-reference`. This is **not** a byte-identical upload of the user's supplied `free-eloquence-tts-source.zip`. The import excludes IBM-derived voice resources, proprietary-derived tables, signing credentials and build artifacts. See [import notice](third_party/eloquence-reference/IMPORT_NOTICE.md).

Native phoneme feasibility notes: [docs/PHONEME_ENGINE_RESEARCH.md](docs/PHONEME_ENGINE_RESEARCH.md).\n\n## Experimental system-wide TTS adapter (new)

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

The exact user-provided Free Eloquence source ZIP is not present as a complete repository archive. The separate public reference snapshot does not contain the IBM-derived acoustic/language assets needed to run native OpenEVV. **No independent native Eloquence voice has been integrated or verified.** Current speech/audio output requires a separate installed offline TTS engine. The experimental system service is disabled by default for screen-reader safety; it buffers a WAV before playback and is not designed for low-latency navigation.

Building and speech testing against a real Android device remain unverified in this chat. Do not label a generated artifact as a validated Dhivehi engine without an actual speech test.
