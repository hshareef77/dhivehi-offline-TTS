"""Load the approved Dhivehi F5 checkpoint and generate one diagnostic sample."""
import gc
import hashlib
import json
import os
import time
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
import torch
from huggingface_hub import HfApi, snapshot_download, hf_hub_download

ROOT = Path(os.environ["F5_WORK"])
OUT = Path("f5-report")
OUT.mkdir(exist_ok=True)
report = {"source": "Serialtechlab/f5-tts-dhivehi", "status": "started",
          "android_integrated": False}
def save():
    (OUT / "load_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))

def main():
    torch.set_num_threads(min(4, os.cpu_count() or 2))
    info = HfApi().model_info(report["source"], files_metadata=True)
    report["revision"] = info.sha
    report["license"] = (info.card_data.to_dict() if info.card_data else {}).get("license")
    report["files"] = [{"path": f.rfilename, "size": f.size} for f in info.siblings]
    save()
    model_dir = ROOT / "model"
    snapshot_download(report["source"], revision=info.sha, local_dir=model_dir,
                      allow_patterns=["model.pt", "vocab.txt", "config.json", "README.md"])
    report["status"] = "downloaded"
    report["checkpoint_bytes"] = (model_dir / "model.pt").stat().st_size
    report["config"] = json.loads((model_dir / "config.json").read_text())
    with (model_dir / "model.pt").open("rb") as f:
        report["checkpoint_sha256"] = hashlib.file_digest(f, "sha256").hexdigest()
    checkpoint = torch.load(model_dir / "model.pt", map_location="cpu", weights_only=True, mmap=True)
    report["checkpoint_keys"] = list(checkpoint.keys())
    if "ema_model_state_dict" not in checkpoint:
        raise RuntimeError("Checkpoint has no EMA weights required by the model card")
    report["ema_tensor_count"] = sum(torch.is_tensor(v) for v in checkpoint["ema_model_state_dict"].values())
    del checkpoint
    gc.collect()
    save()

    from f5_tts.api import F5TTS
    start = time.monotonic()
    tts = F5TTS(model="F5TTS_v1_Base", ckpt_file=str(model_dir / "model.pt"),
                vocab_file=str(model_dir / "vocab.txt"), device="cpu")
    report["load_seconds"] = round(time.monotonic() - start, 2)
    report["parameter_count"] = sum(p.numel() for p in tts.ema_model.parameters())
    report["status"] = "loaded"
    save()
    print("Real F5 checkpoint and Vocos loaded successfully.", flush=True)

    # Generate fresh synthetic reference speech; do not depend on expiring CI artifacts.
    ref_text = "މެލޭޝިއާގައި އިތުރުކުރާ ޓެކްސް، ދިވެހި ދަރިވަރުންނަށް ބުރައަކަށް ނުވާނެ ގޮތެއް ހޯދައިދޭނަން"
    gen_text = Path("tools/f5/input.txt").read_text(encoding="utf-8").strip()
    csm_repo = "Hshareef77/csm-1b-dhivehi-5spk-onnx"
    csm_revision = HfApi().model_info(csm_repo).sha
    csm_dir = ROOT / "reference-model"
    manifest_file = hf_hub_download(csm_repo, "manifest.json", revision=csm_revision, local_dir=csm_dir)
    manifest = json.loads(Path(manifest_file).read_text())
    paths = [entry["path"] for entry in manifest["files"]]
    if any(not (csm_dir / p).resolve().is_relative_to(csm_dir.resolve()) for p in paths):
        raise RuntimeError("Unsafe reference-package path")
    snapshot_download(csm_repo, revision=csm_revision, local_dir=csm_dir, allow_patterns=paths)
    for entry in manifest["files"]:
        path = csm_dir / entry["path"]
        with path.open("rb") as f:
            digest = hashlib.file_digest(f, "sha256").hexdigest()
        if path.stat().st_size != entry["size"] or digest != entry["sha256"]:
            raise RuntimeError("Reference-package checksum mismatch: " + entry["path"])
    from tokenizers import Tokenizer
    tokenizer_path = hf_hub_download("alakxender/csm-1b-dhivehi-5-spk-gd", "tokenizer.json")
    tokenizer = Tokenizer.from_file(tokenizer_path)
    sys.path.insert(0, str(Path("conversion").resolve()))
    from csm_pipeline import CsmOnnx, Sampler
    print("Generating fresh synthetic Dhivehi reference.", flush=True)
    reference_engine = CsmOnnx(str(csm_dir), threads=min(4, os.cpu_count() or 2))
    cfg = reference_engine.cfg
    ids = [cfg["bos_token_id"]] + tokenizer.encode("[4]" + ref_text, add_special_tokens=False).ids + [cfg["eos_token_id"]]
    frames = reference_engine.generate_frames(ids, 180, Sampler(seed=4001))
    reference_audio = reference_engine.decode(frames)
    if not len(reference_audio) or not np.isfinite(reference_audio).all() or len(frames) >= 180:
        raise RuntimeError("Synthetic reference generation failed validation")
    ref_path = OUT / "synthetic_reference.wav"
    sf.write(ref_path, reference_audio, cfg["sample_rate"], subtype="PCM_16")
    del reference_engine, tokenizer, reference_audio
    gc.collect()
    report["reference"] = {"kind": "fresh synthetic CSM speech", "repository": csm_repo,
                           "revision": csm_revision, "speaker": 4, "text": ref_text}
    report["generated_text"] = gen_text
    start = time.monotonic()
    # Bound chunk length by UTF-8 bytes, as used by F5's duration estimator.
    words = gen_text.split()
    chunks, current = [], ""
    for word in words:
        candidate = (current + " " + word).strip()
        if current and len(candidate.encode("utf-8")) > 420:
            chunks.append(current)
            current = word
        else:
            current = candidate
    if current:
        chunks.append(current)
    report["chunks"] = []
    segments = []
    for index, chunk in enumerate(chunks):
        print(f"Generating section {index + 1}/{len(chunks)}", flush=True)
        chunk_start = time.monotonic()
        wav, sample_rate, _ = tts.infer(
            ref_file=str(ref_path), ref_text=ref_text, gen_text=chunk,
            nfe_step=32, seed=42 + index)
        wav = np.asarray(wav, dtype=np.float32)
        if not wav.size or not np.isfinite(wav).all() or np.max(np.abs(wav)) <= 1e-6:
            raise RuntimeError(f"Invalid generated audio in section {index + 1}")
        sf.write(OUT / f"section_{index + 1:03d}.wav", wav, sample_rate, subtype="PCM_16")
        segments.append(wav)
        report["chunks"].append({"index": index + 1, "text": chunk,
                                 "audio_seconds": round(wav.size / sample_rate, 3),
                                 "inference_seconds": round(time.monotonic() - chunk_start, 2)})
        save()
    silence = np.zeros(int(sample_rate * 0.25), dtype=np.float32)
    wav = np.concatenate([part for i, segment in enumerate(segments)
                          for part in ([silence, segment] if i else [segment])])
    sf.write(OUT / "f5_dhivehi_sample.wav", wav, sample_rate, subtype="PCM_16")
    seconds = wav.size / sample_rate
    elapsed = time.monotonic() - start
    report.update(status="speech_generated", sample_rate=sample_rate,
                  audio_seconds=round(seconds, 3), inference_seconds=round(elapsed, 2),
                  real_time_factor=round(elapsed / seconds, 2),
                  quality_note="Loading and non-silent speech generation verified; no listening or Android validation yet.")
    save()
    (OUT / "README.txt").write_text(
        "Dhivehi F5-TTS loading test\n"
        "f5_dhivehi_sample.wav is generated using the approved real checkpoint.\n"
        "synthetic_reference.wav is CSM-generated reference speech, not a human recording.\n"
        "See load_report.json for measurements. This is not an Android-converted model.\n")
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        report["error_type"] = type(exc).__name__
        # Do not retain request URLs or authentication-related exception text.
        report["failed_stage"] = report["status"]
        report["status"] = "failed"
        save()
        raise
