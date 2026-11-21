# Dhivehi Pronunciation Lab (Android)

**Current goal:** explore fast, offline Dhivehi speech through the existing Eloquence-style voices, with a native accessible Android interface. The previous 6.6 GB CSM model and five-speaker offline narration prototype are **no longer the current project direction**.

## Current working design

The app now lets you enter or import Thaana text, inspect a **basic experimental** Latin phonetic conversion, play the conversion through the phone's installed Android TTS engine, stop playback, and request audio generation and save it as WAV using the Android system TTS API. It uses the phone's currently selected TTS engine, so **install and select Eloquence in Android TTS settings** to hear the experiment with Eloquence voices.

**Important:** The source-code changes have been committed, but a successful APK build and speech testing have not been confirmed. The app does not yet include its own functioning embedded Eloquence engine. It will not generate correct Dhivehi speech until pronunciation rules and the voice/phoneme mappings have been tested and refined. English-language TTS voices are imperfect substitutes for Dhivehi.

## Source reference

An automated import of public [evvdroid](https://github.com/trypsynth/evvdroid) reference code is under `third_party/eloquence-reference`. This is **not** a byte-identical upload of the user's supplied `free-eloquence-tts-source.zip`. The import excludes IBM-derived voice resources, proprietary-derived tables, signing credentials and build artifacts. See [import notice](third_party/eloquence-reference/IMPORT_NOTICE.md).

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
