# Dhivehi native voice — naturalness improvement pass (21 November 2026)

## Outcome and scope

The new focus is *accurate Dhivehi pronunciation*, not spelling Thaana in Latin letters. The native path now runs:

Thaana text → independent Dhivehi IPA phonology → explicit Eloquence phoneme mapping → phrase rhythm → native Eloquence PCM playback / WAV export.

It does not invoke the Eloquence English spelling-to-phoneme converter for Thaana words. The English acoustic inventory is still an important limitation; no unsupported Dhivehi acoustic phone has been magically created.

## Substantive changes

1. One phonology parser now handles vowel quantity, dental versus retroflex consonants, prenasalization, certain gemination patterns and three special sukun contexts. The native mapper consumes these phonemic phones; the prior duplicate spelling interpretation was removed.
2. Native sound mapping flags unavailable/approximate phones (retroflexes, glottal closure, long-vowel realization and Arabic sounds). The user can correct specific words with explicit SPR annotations in the pronunciation dictionary.
3. Leftmost-heavy syllable stress was introduced: prefer the first long-vowel or consonant-geminate syllable; otherwise the first syllable. Word-specific exceptions and dialect variation need native-speaker corrections.
4. Added **Connected speech** and **Clear speech** timings. The native engine receives explicit punctuation pauses without changing syllable dots inside SPR codes. Connected has shorter clause/sentence breaks; Clear pauses longer. These are listening experiments, not verified naturalness improvements.
5. The extra duplicate Test native voice button was removed. **Speak** uses the native engine whenever installed; **Generate audio** uses direct native PCM for WAV in the same mode.

## Host-native listening samples (real OpenEVV C synthesis)

The same sequence was synthesized with explicit native phonemes using the locally compiled OpenEVV English voice:

Dhivehi reference text: **ދިވެހި ރާއްޖެ މާލެ. ކަނޑު އެންމެ. އައްސަލާމު ޢަލައިކުމް. ދިވެހި ރާއްޖެ މާލެ.**

- Connected: **7.04 seconds**, 11,025 Hz mono 16-bit WAV, rate value 49, 160 ms long punctuation pauses and 85 ms short pauses.
- Clear: **7.91 seconds**, same format, rate value 46, 280 ms long and 165 ms short pauses.

These are produced locally and not included in this public source repository. Actual listening evaluation is needed. Acoustic intelligibility and naturalness are **not proven** by WAV generation.

## Next research priorities

- Transcribe a reviewed 50–100 word test set with fluent Dhivehi speakers, including minimal pairs for ތ/ޓ, ދ/ޑ, ލ/ޅ, ށ/ޝ, vowel length, glottal closure, and prenasalization.
- Investigate the native engine phoneme inventories to locate better acoustic substitutes; where none exists, evaluate modifying sound tables rather than blindly substituting English sounds.
- Expand systematic phonological transformations for Dhivehi-specific syllable stress exceptions, nasal assimilation, glottal closure, and word-to-word sandhi.
- Native Android ARM64 compilation, voice assets and real screen-reader latency remain to be proven. Keep the experimental system-wide voice disabled by default.
- Only label a voice "Dhivehi" as a production-ready TTS when fluent-speaker listening, stability, and Android device testing pass.

## Grounding for phonological rules

- [Dhivehi orthography overview, Richard Ishida](https://r12a.github.io/scripts/thaa/dv.html): vowel signs, gemination, special sukuns, prenasalization and glottal closure.
- [Dhivehi: The Language of the Maldives (Gnanadesikan)](https://saruna.mnu.edu.mv/server/api/core/bitstreams/aecf5741-9311-44bc-899e-1747d644ca1c/content): syllable weights and stress patterns.
- [Maniku's A Concise Etymological Vocabulary of Dhivehi Language](https://dsal.uchicago.edu/dictionaries/maniku/frontmatter/frontmatter.html): exceptional sukun pronunciations.
- User-provided source: free-eloquence-tts-source.zip, native/openevv/docs/api.md, includes direct phonetic annotation syntax.

These are research starting points, not a substitute for recordings by native Dhivehi speakers.
