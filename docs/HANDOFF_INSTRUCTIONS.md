> Update, 20 November 2026: The owner explicitly requested replacing CSM with OmniVoice.
> The historical CSM instructions below are retained for context. For the active engine,
> read README.md, docs/OMNIVOICE_RELEASE.md and .github/workflows/omnivoice.yml.
> Preserve the same Android package/signing key; no merge into main is authorized.

# Hand-off instructions: finish the Dhivehi Offline TTS voice model

Give this whole document to the platform or developer who will continue the work.

---

You are continuing an existing, working project. Do not restart it, redesign it, or replace
the speech engine. The Android app, Kotlin inference engine and conversion scripts are
complete and tested. The only remaining work is to **convert the real model, validate it,
publish it, and check the app with it**.

## Project

- GitHub repository: https://github.com/hshareef77/dhivehi-offline-TTS
- Branch with all current work: `ccr-e6a84a0a-pdlhzq` (not yet merged into `main`)
- Source model (gated, auto-approved after accepting terms):
  https://huggingface.co/alakxender/csm-1b-dhivehi-5-spk-gd (fp32, 6.6 GB, Apache-2.0)
- Target Hugging Face repository for the converted package:
  `Hshareef77/csm-1b-dhivehi-5spk-onnx` (public). The app downloads from
  `https://huggingface.co/Hshareef77/csm-1b-dhivehi-5spk-onnx/resolve/main/manifest.json`.
- Owner: a blind user who works only from an Android phone (Samsung Galaxy S23 FE,
  8 GB RAM, TalkBack and Jieshuo+). Keep messages short and plain.

Read first: `README.md`, `docs/IMPLEMENTATION_REPORT.md`, `docs/INSTALL_AND_TEST.md`,
`.github/workflows/convert-model.yml`.

## Architecture (already implemented, do not change without reason)

- `conversion/export_csm_onnx.py` exports three ONNX graphs with explicit KV cache:
  `backbone.onnx`, `depth_decoder.onnx`, `mimi_decoder.onnx`. It also exports int8 embedding
  tables (`text_embeddings.bin`, `audio_embeddings.bin`, "DVQ8" format), the Llama-3
  tokenizer (`tokenizer/vocab.txt`, `merges.txt`, `special_tokens.txt`) and
  `model_config.json`. It refuses to export if any checkpoint weight fails to load.
- `conversion/verify_parity.py` checks greedy generation parity against Hugging Face
  Transformers, plus Mimi audio parity.
- `conversion/quantize_package.py` applies 4-bit or 8-bit MatMulNBits quantization and writes
  `manifest.json` (size and SHA-256 per file).
- `conversion/evaluate_quality.py` generates Dhivehi speech for all five speakers and
  computes the character error rate with the speech recogniser
  `alakxender/w2v-bert-2.0-dhivehi-cv`. It chooses q4 if q4's error rate is within 0.08 of
  fp32, otherwise q8.
- `conversion/make_reference.py` plus `gradle -p engine test` check that the Kotlin engine
  matches Python with the real tokenizer and package.
- `engine/` is the Kotlin engine (ONNX Runtime Java API 1.31.1); `app/` is the Android app.

Already verified: exact parity on a random-weight CSM with transformers 5.22.0 and 4.57.1;
the Kotlin engine equals the Python pipeline; the APK builds in GitHub Actions.

## Task

### Option A (preferred): run the existing GitHub Actions workflow

1. The repository needs the Actions secret `HF_TOKEN`: a Hugging Face **write** token from
   account `Hshareef77`, which must have accepted the source model's terms. Only the owner can
   create it. Never print, log or commit the token.
2. Trigger workflow **Convert voice model** on branch `ccr-e6a84a0a-pdlhzq`: re-run the latest
   run, or push a small change under `conversion/`.
3. Monitor it (about 1–2 hours on `ubuntu-latest`: 4 CPUs, 16 GB RAM, plus 20 GB swap). If a
   step fails, read the log, fix the cause in the scripts or workflow with a minimal change,
   push, and re-run. Likely failure points:
   - memory during the backbone export or quantization;
   - Transformers loading keys (the workflow already retries with 4.57.1);
   - Mimi or ONNX export errors;
   - the speech recogniser failing to load (then CER is "None"; fix it, do not ignore it).
4. When it succeeds, download the `voice-model-report` artifact. Report the following:
   - the parity result and mean CER for fp32, q8 and q4;
   - which package was published, and its real-time factor;
   - the total package size.
   Listen-check a few WAV samples if you can.

### Option B: run the same scripts on another machine

Use a Linux machine with at least 16 GB RAM (plus swap) and about 30 GB of free disk; no GPU
needed. Follow the steps in `.github/workflows/convert-model.yml` exactly, in order:

```
pip install --index-url https://download.pytorch.org/whl/cpu torch==2.15.0
pip install transformers==5.22.0 onnx==1.23.2 onnxruntime==1.31.1 onnxscript safetensors tokenizers huggingface_hub soundfile scipy numpy
huggingface-cli login      # account that accepted the model terms
python - <<'EOF'
from huggingface_hub import snapshot_download
snapshot_download("alakxender/csm-1b-dhivehi-5-spk-gd", local_dir="work/hf")
EOF
cd conversion
python export_csm_onnx.py --model ../work/hf --out ../work/fp32 --depth-head-dtype float32
python verify_parity.py --model ../work/hf --onnx ../work/fp32 --frames 12 --min-match 0.9
python export_csm_onnx.py --model ../work/hf --out ../work/fp32 --skip backbone mimi emb tok
python quantize_package.py --src ../work/fp32 --out ../work/q4 --backbone-bits 4 --depth-bits 4 --version 1
python quantize_package.py --src ../work/fp32 --out ../work/q8 --backbone-bits 8 --depth-bits 8 --version 1
python evaluate_quality.py --tokenizer-json ../work/hf/tokenizer.json --out ../work/eval \
  --package fp32=../work/fp32 --package q8=../work/q8 --package q4=../work/q4 --choose q4,q8,fp32
python make_reference.py --package ../work/q4 --tokenizer-json ../work/hf/tokenizer.json --out ../work/ref
cd .. && gradle -p engine test -Dcsm.model.dir=work/q4 -Dcsm.reference.dir=work/ref -Dcsm.output.dir=work/eval/kotlin
```

If export fails because weights did not load, run `pip install transformers==4.57.1` and
repeat from the export step.

Publish the chosen package folder to `Hshareef77/csm-1b-dhivehi-5spk-onnx`:
1. Copy `conversion/MODEL_CARD.md` into the folder as `README.md`, and add
   `quality_report.md`.
2. Upload every file except `manifest.json` first.
3. Upload `manifest.json` last.

The publish step at the end of the workflow file does exactly this.

## Rules

- Do not fake anything. Do not claim conversion, publishing or speech generation succeeded
  unless the files exist and the checks passed. Report exact errors.
- Do not put model weights in GitHub source history.
- Do not use online or paid speech services as a substitute; all speech must come from the
  installed model on the phone.
- Do not change the Android package name (`com.hussainshareef.dhivehiofflinetts`) or the
  committed debug signing key (`app/debug-stable.keystore`). Changing either breaks in-place
  updates.
- Keep all five speakers (ids 0–4).
- Commit fixes to branch `ccr-e6a84a0a-pdlhzq`. Merge into `main` only with the owner's
  consent.

## After the model is published

1. Run workflow **Android APK** on the branch. Give the owner the newest artifact
   `dhivehi-offline-tts-apk` and the test list in `docs/INSTALL_AND_TEST.md`.
2. If the owner reports that generation is too slow on the phone, optimise in this order,
   measuring each change:
   - thread count (2/3/4) in `TtsApp.ensureEngine`;
   - ONNX Runtime XNNPACK execution provider for the backbone and depth decoder;
   - 8-bit vs 4-bit package speed on ARM.
   Do not reduce quality without the owner's agreement.
3. Update `docs/IMPLEMENTATION_REPORT.md` with the measured results.
