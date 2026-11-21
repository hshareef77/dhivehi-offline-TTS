# Dhivehi direct-phoneme experiment — listening protocol

## Current engineering path
There are now two independent linguistic processing stages. DhivehiPhonology.kt converts Thaana spelling into a Dhivehi-oriented IPA representation, preserving short/long vowels, dental/retroflex distinctions and some sukun behavior. DhivehiPhoneticEngine.kt maps those units into explicit OpenEVV SPR phoneme annotations. The native synth enables the OpenEVV ECI input-annotation parameter (eciInputType = 1). No English spelling interpretation is needed for native Thaana words. The earlier Latin fallback remains available for comparisons.

## Verified native engine experiment
Using the user-supplied Free Eloquence ZIP, the C engine was compiled on Linux, the native ECI phoneme generator was queried for sample phoneme symbols, and an instrumented native CLI with annotations enabled produced a **5.90-second** WAV (16-bit mono 11025 Hz).

Test text: ދިވެހި ރާއްޖެ މާލެ. އައްސަލާމު ޢަލައިކުމް. ދިވެހި މާލެ.

Explicit SPR test words (prefix each with a backtick and an opening bracket, and close with a bracket):

| Thaana | SPR inside annotation brackets |
| --- | --- |
| ދިވެހި | .1di.0vE.0hi |
| ރާއްޖެ | .1raa.0JJE |
| މާލެ | .1maa.0lE |
| އައްސަލާމު | .1a.0ssa.0laa.0mu |
| ޢަލައިކުމް | .1a.0la.0i.0kum |

## What remains unproven
This **is direct Eloquence phoneme synthesis**, not Latin transliteration, but it **is not yet accurate Dhivehi TTS**. The explicit phoneme symbols are taken from an English acoustic inventory. Dental stops, retroflex consonants, the distinctive Dhivehi shaviyani, vowel quality/length, glottal closure and word rhythm remain approximate. The engine's English phonemes cannot automatically represent missing Dhivehi sounds. Android arm64 native compilation and screen-reader latency have not been verified.

## Linguistic listening assessment
Evaluate whether each word can be recognized without a reference, and flag inaccurate vowel quantity, wrong consonant type, unnatural pauses, and missing glottal/geminate effects. Prefer many short comparison samples with a single change per experiment. Do not claim completion before review by fluent Dhivehi speakers.

## Technical background
User ZIP: free-eloquence-tts-source.zip, particularly native/openevv/docs/api.md, docs/notes/heteronyms.md and include/eci.h.
Dhivehi phonology references:
- https://www.qaumiyyath.gov.mv/docs/whitepapers/language/GENERAL%20OVERVIEW%20OF%20DHIVEHI%20LANGUAGE.pdf
- https://r12a.github.io/scripts/thaa/dv

These describe phonological distinctions and engine input syntax, not a verified Dhivehi acoustic mapping.
