# CSM-1B Dhivehi: feasibility decision (30 Oct 2026)

## Status: NOT YET RUNNABLE OFFLINE ON ANDROID

The five-speaker Dhivehi checkpoint is at:
https://huggingface.co/alakxender/csm-1b-dhivehi-5-spk-gd

The checkpoint contains two safetensors shards of roughly 4.96 GB and 1.64 GB. Hugging Face's reference implementation runs the model with Python Transformers and a GPU. The architecture consists of a text-conditioned backbone, autoregressive audio-codebook prediction, and the Mimi audio decoder. An arbitrary Llama/GGUF engine cannot produce playable speech from the checkpoint alone.

This project does **not** have a complete Android native implementation of the CSM model or its Mimi audio-codec pipeline. It must not advertise model downloading or synthesis as working until the complete stack has been tested on-device. We should not ask the user to download 6.6 GB onto an 8 GB RAM handset as an experiment.

## Specific engineering gates

1. Legally access the gated checkpoint and verify license and redistribution conditions.
2. Verify baseline reproducibility on GPU, all five speaker IDs, and sample rate/output.
3. Implement or integrate a supported mobile runtime for both autoregressive decoder stages and Mimi codec; identify exact model tensor mapping, KV cache, tokenizer and codebooks.
4. Convert/checkpoint quantization, evaluate quality regression (intelligibility of Thaana, number reading) using matched samples.
5. Test on a real Galaxy S23 FE (8 GB): memory peak, initial load, duration per second of speech, thermal behavior, and airplane mode.
6. Only after a functional on-device proof, implement the separate model-download flow with free-space preflight, authenticated downloads, integrity hashes, cancellation/retries, and atomic install.
7. Integrate local speech generation and WAV export, preserve all five speaker choices, then Android system TTS service.

## Known limitations

- Current Gradle Android app is an accessible text/audio utility prototype, not a TTS synthesizer.
- Google Colab test notebook is an optional reproducibility aid; the user's prior online listening evaluation need not be repeated.
- 4-bit weight-size estimates do not prove that Android can run the quantized model or decode Mimi.
- The model card lists Apache-2.0 metadata but also says research/educational use; consult complete publisher conditions before distributing weights.
- Source documentation lists role 4 inconsistently (male/female in different sections); label or verify by listening rather than assuming.

## Sources

- https://huggingface.co/alakxender/csm-1b-dhivehi-5-spk-gd
- https://huggingface.co/alakxender/csm-1b-dhivehi-5-spk-gd/tree/main
- https://github.com/SesameAILabs/csm
- https://github.com/huggingface/transformers/blob/main/docs/source/en/model_doc/csm.md
