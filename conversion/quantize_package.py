"""Quantize the fp32 export and assemble the downloadable model package.

Usage:
    python conversion/quantize_package.py --src build/fp32 --out build/package \
        --backbone-bits 4 --depth-bits 4 --version 1

Weight-only quantization with ONNX Runtime's MatMulNBits (block-wise, symmetric):
  * backbone + depth decoder: 4-bit (default) or 8-bit, accuracy_level 4 so ARM64 CPUs
    run the matmuls with int8 dot-product kernels.
  * Mimi decoder: kept fp32 (small, quality critical, conv-heavy).
  * embeddings: already int8 per-row (export step).

Writes manifest.json listing every file with size and SHA-256; the Android app verifies
each file against it after download.
"""

import argparse
import hashlib
import json
import os
import shutil

import onnx


def quantize(src, dst, bits, block_size):
    from onnxruntime.quantization import QuantFormat
    from onnxruntime.quantization.matmul_nbits_quantizer import DefaultWeightOnlyQuantConfig, MatMulNBitsQuantizer

    print(f"Quantizing {os.path.basename(src)} to {bits}-bit (block {block_size}) ...")
    model = onnx.load(src)
    cfg = DefaultWeightOnlyQuantConfig(block_size=block_size, is_symmetric=True, accuracy_level=4,
                                       quant_format=QuantFormat.QOperator, op_types_to_quantize=("MatMul",),
                                       bits=bits)
    q = MatMulNBitsQuantizer(model, algo_config=cfg)
    q.process()
    out = q.model.model
    n = sum(1 for node in out.graph.node if node.op_type == "MatMulNBits")
    print(f"  {n} MatMul nodes quantized")
    save(out, dst)


def save(model, dst):
    data = os.path.basename(dst) + ".data"
    if os.path.exists(os.path.join(os.path.dirname(dst), data)):
        os.remove(os.path.join(os.path.dirname(dst), data))
    onnx.save_model(model, dst, save_as_external_data=True, all_tensors_to_one_file=True,
                    location=data, size_threshold=1024)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--backbone-bits", type=int, default=4, choices=[4, 8, 32])
    ap.add_argument("--depth-bits", type=int, default=4, choices=[4, 8, 32])
    ap.add_argument("--block-size", type=int, default=32)
    ap.add_argument("--version", type=int, default=1)
    ap.add_argument("--name", default="csm-1b-dhivehi-q4")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    for name, bits in (("backbone", args.backbone_bits), ("depth_decoder", args.depth_bits)):
        src = os.path.join(args.src, name + ".onnx")
        dst = os.path.join(args.out, name + ".onnx")
        if bits == 32:
            save(onnx.load(src), dst)
        else:
            quantize(src, dst, bits, args.block_size)
    save(onnx.load(os.path.join(args.src, "mimi_decoder.onnx")), os.path.join(args.out, "mimi_decoder.onnx"))

    for f in ("text_embeddings.bin", "audio_embeddings.bin", "depth_embeddings.bin", "model_config.json"):
        if os.path.exists(os.path.join(args.src, f)):
            shutil.copy(os.path.join(args.src, f), os.path.join(args.out, f))
    if os.path.isdir(os.path.join(args.src, "tokenizer")):
        shutil.copytree(os.path.join(args.src, "tokenizer"), os.path.join(args.out, "tokenizer"), dirs_exist_ok=True)

    cfg_path = os.path.join(args.out, "model_config.json")
    cfg = json.load(open(cfg_path))
    cfg["quantization"] = {"backbone_bits": args.backbone_bits, "depth_bits": args.depth_bits,
                           "block_size": args.block_size, "embeddings": "int8-per-row", "mimi": "fp32"}
    json.dump(cfg, open(cfg_path, "w"), indent=1)

    files = []
    for root, _, names in os.walk(args.out):
        for n in sorted(names):
            if n in ("manifest.json", "tokenizer_info.json"):
                continue
            p = os.path.join(root, n)
            rel = os.path.relpath(p, args.out).replace(os.sep, "/")
            files.append({"path": rel, "size": os.path.getsize(p), "sha256": sha256(p)})
    files.sort(key=lambda f: f["path"])
    manifest = {"format": "dhivehi-csm-onnx", "format_version": 1, "name": args.name,
                "version": args.version, "total_size": sum(f["size"] for f in files), "files": files}
    json.dump(manifest, open(os.path.join(args.out, "manifest.json"), "w"), indent=1)
    print(f"Package {args.name} v{args.version}: {len(files)} files, {manifest['total_size'] / 1e9:.3f} GB")
    for f in files:
        print(f"  {f['size'] / 1e6:10.1f} MB  {f['path']}")


if __name__ == "__main__":
    main()
