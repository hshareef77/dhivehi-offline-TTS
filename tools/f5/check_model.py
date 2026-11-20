"""Load the approved Dhivehi F5 checkpoint and generate one diagnostic sample."""
import gc
import hashlib
import io
import json
import os
import time
import zipfile
from pathlib import Path

import numpy as np
import requests
import soundfile as sf
import torch
from huggingface_hub import HfApi, snapshot_download

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

    # Reference is synthetic speech produced by this project's earlier validated CSM run.
    # No human's voice or identity is used as a new reference.
    url = "https://api.github.com/repos/hshareef77/dhivehi-offline-TTS/actions/artifacts/12963957636/zip"
    response = requests.get(url, headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"],
                                         "Accept": "application/vnd.github+json"}, timeout=120)
    response.raise_for_status()
    archive = response.content
    if hashlib.sha256(archive).hexdigest() != "c3552d8cfe9ad8c8efb3e8c128170bdb5bb256da6794509d33a71d6ecb8d137f":
        raise RuntimeError("Synthetic reference archive checksum mismatch")
    ref_name = "q8_speaker4_s1.wav"
    ref_path = OUT / "synthetic_reference.wav"
    with zipfile.ZipFile(io.BytesIO(archive)) as z:
        ref_path.write_bytes(z.read(ref_name))
    ref_text = "މެލޭޝިއާގައި އިތުރުކުރާ ޓެކްސް، ދިވެހި ދަރިވަރުންނަށް ބުރައަކަށް ނުވާނެ ގޮތެއް ހޯދައިދޭނަން"
    gen_text = "މިއަދު މޫސުން ވަރަށް ރަނގަޅު."
    report["reference"] = {"kind": "synthetic CSM speech", "artifact_id": 12963957636,
                           "file": ref_name, "text": ref_text}
    report["generated_text"] = gen_text
    start = time.monotonic()
    wav, sample_rate, _ = tts.infer(ref_file=str(ref_path), ref_text=ref_text,
                                   gen_text=gen_text, nfe_step=32, seed=42)
    wav = np.asarray(wav, dtype=np.float32)
    if not wav.size or not np.isfinite(wav).all() or np.max(np.abs(wav)) <= 1e-6:
        raise RuntimeError("Generated audio is empty, non-finite, or silent")
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
