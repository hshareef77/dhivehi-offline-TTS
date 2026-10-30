# Offline CSM-1B Dhivehi feasibility gate

Model: https://huggingface.co/alakxender/csm-1b-dhivehi-5-spk-gd

User-tested quality: first two voices of the five-speaker online demonstration are understandable, although imperfect.

Model weight shards displayed: ~4.96 GB and ~1.64 GB (approximately 6.60 GB total).
Target handset: Samsung Galaxy S23 FE with 8 GB RAM.
Do not download the unoptimized weights to the handset before offline runtime compatibility is proven.

## Hard requirements before enabling Generate

1. Verify actual model license and redistribution terms.
2. Reproduce generation using model checkpoint, decoder, tokenizer, and speaker IDs in an isolated environment.
3. Identify a supported **on-device** runtime for every part of CSM synthesis, including acoustic/audio decoder.
4. Quantize and benchmark working model; verify memory footprint, output quality, startup and generation latency on 8 GB Android hardware.
5. Verify all five voices are preserved and compare intelligibility with hosted demo.
6. Run with airplane mode enabled and prove no network calls.
7. Implement authenticated/resumable model download, integrity verification, storage check, and safe extraction.
8. Only then enable Generate and WAV export. Never advertise online inference as offline.

Current status: stages 1–7 are NOT complete; Android prototype UI only.
