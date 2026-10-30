"""Create a small randomly initialised CSM checkpoint with the same structure as CSM-1B.

Used to test the export, the reference pipeline and the Kotlin engine without the gated
6.6 GB checkpoint. The Mimi codec keeps its real configuration (random weights), the
backbone and depth decoder are shrunk. Speech produced by it is of course noise.
"""

import argparse
import json
import os

import torch


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()
    torch.manual_seed(args.seed)
    from transformers import CsmConfig, CsmForConditionalGeneration

    cfg = CsmConfig(
        hidden_size=256, intermediate_size=512, num_hidden_layers=2, num_attention_heads=8,
        num_key_value_heads=2, head_dim=32, max_position_embeddings=2048,
        rope_parameters={"rope_type": "llama3", "rope_theta": 500000, "factor": 32.0,
                         "high_freq_factor": 0.5, "low_freq_factor": 0.125,
                         "original_max_position_embeddings": 1024},
        depth_decoder_config={
            "hidden_size": 128, "intermediate_size": 256, "num_hidden_layers": 2,
            "num_attention_heads": 4, "num_key_value_heads": 2, "head_dim": 32,
            "backbone_hidden_size": 256, "num_codebooks": 32, "vocab_size": 2051,
            "max_position_embeddings": 33,
            "rope_parameters": {"rope_type": "llama3", "rope_theta": 500000, "factor": 32.0,
                                "high_freq_factor": 0.0078125, "low_freq_factor": 0.001953125,
                                "original_max_position_embeddings": 16},
        },
        codec_config={"model_type": "mimi"},
        tie_word_embeddings=False,
    )
    model = CsmForConditionalGeneration(cfg).eval()
    # random init leaves small std; scale embeddings so logits are not flat
    with torch.no_grad():
        model.embed_text_tokens.weight.normal_(0, 1.0)
        model.backbone_model.embed_tokens.embed_audio_tokens.weight.normal_(0, 0.3)
        model.depth_decoder.codebooks_head.weight.normal_(0, 0.2)
    model.save_pretrained(args.out)
    with open(os.path.join(args.out, "generation_config.json"), "w") as f:
        json.dump({"temperature": 0.9, "depth_decoder_temperature": 0.9, "max_new_tokens": 125}, f)
    print("saved", args.out)


if __name__ == "__main__":
    main()
