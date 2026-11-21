# Dhivehi with existing Eloquence-style voices: experiment

This repository already contains an Android Dhivehi TTS prototype. **Do not replace it with the Free Eloquence application**, because that would erase the existing implementation and settings.

## Source being evaluated

The user supplied `free-eloquence-tts-source.zip` (an Android Kotlin/JNI OpenEVV-based TTS project). Relevant upstream components include `LanguageRouter.kt`, `LanguageIdentifier.kt`, pronunciation rules, and an Android `TextToSpeechService`. Its English voices might be usable to *approximate* Dhivehi after text preprocessing. **This source archive is not yet imported into this repository.**

## First implementation

`app/src/main/kotlin/com/hussainshareef/dhivehiofflinetts/dhivehi/DhivehiPronunciation.kt` implements an **experimental, incomplete** pure-Kotlin Thaana converter. It is currently an isolated component; no existing UI, synth engine, or voice settings have been changed. It converts common consonants and vowel signs, preserves punctuation and non-Thaana characters, and detects Thaana. It does not guarantee authentic pronunciation, especially for sukun on alifu, vowel length, retroflex consonants, Arabic-derived letters, schwa, numerals, or mixed-script text.

## Next steps before announcing working speech

1. Verify licensing of IBM-derived voice/language resources before shipping or publishing them; source-code licenses alone are not sufficient.
2. Import permitted Eloquence/OpenEVV engine source into a separate module or directory without replacing the current app. The supplied archive is about 11 MB compressed and contains over 1,000 entries.
3. Add unit tests and a real phoneme/pronunciation test corpus with native Dhivehi speaker review.
4. Connect a preview screen to a verified, working Eloquence/OpenEVV synthesis backend (not yet done).
5. Benchmark intelligibility, onset latency, fast stop/restart, low-memory behavior, and TalkBack/Jieshuo+ use on physical devices.
6. Support user-corrected pronunciations in an offline dictionary; only advertise `dv-MV` when speech requests really work.

Do not mistake Latin transliteration for a completed Dhivehi speech engine.
