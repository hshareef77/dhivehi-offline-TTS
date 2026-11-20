"""Export the original OmniVoice audio encoder with a genuinely dynamic audio length.

Verifies original vs ONNX audio codes at several unseen lengths before publishing.
The upstream HuBERT padding mask is unnecessary for a single unpadded mono clip.
"""
import argparse
import json
import time
from pathlib import Path
import numpy as np
import onnxruntime as ort
import torch
from transformers import HiggsAudioV2TokenizerModel
from transformers.models.hubert import modeling_hubert


def main():
    p = argparse.ArgumentParser(); p.add_argument('--source',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    args = p.parse_args(); args.out.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(3)
    model, info = HiggsAudioV2TokenizerModel.from_pretrained(
        str(args.source), attn_implementation='eager', output_loading_info=True)
    assert not info.get('missing_keys') and not info.get('unexpected_keys') and not info.get('mismatched_keys'), info
    model.eval()
    original_mask = modeling_hubert.create_bidirectional_mask
    def unpadded_mask(*a, **kw):
        if kw.get('attention_mask') is None: return None
        return original_mask(*a, **kw)
    modeling_hubert.create_bidirectional_mask = unpadded_mask
    class Encoder(torch.nn.Module):
        def __init__(self): super().__init__(); self.codec=model
        def forward(self,audio): return self.codec.encode(audio,return_dict=True).audio_codes
    wrapper=Encoder().eval()
    path=args.out/'encoder.onnx'
    dummy=torch.zeros(1,1,72000)
    with torch.inference_mode():
        torch.onnx.export(wrapper,(dummy,),str(path),input_names=['input_values'],output_names=['audio_codes'],
                          dynamic_axes={'input_values':{2:'num_samples'},'audio_codes':{2:'num_frames'}},
                          opset_version=17,dynamo=False)
    options=ort.SessionOptions();options.intra_op_num_threads=3
    session=ort.InferenceSession(str(path),options)
    checks=[]
    for frames in [75,82,125,225,250]:
        # Test structured audio plus noise, not only silent inputs.
        n=frames*960
        rng=np.random.default_rng(frames)
        t=np.arange(n)/24000
        audio=(.08*np.sin(2*np.pi*167*t)+.01*rng.standard_normal(n)).astype(np.float32)[None,None]
        with torch.inference_mode(): expected=wrapper(torch.from_numpy(audio)).numpy()
        actual=session.run(None,{'input_values':audio})[0]
        match=float(np.mean(actual==expected))
        assert actual.shape==expected.shape and match>=.99,(frames,actual.shape,expected.shape,match)
        checks.append({'seconds':n/24000,'shape':list(actual.shape),'code_match':match})
        print(checks[-1],flush=True)
    (args.out/'encoder_validation.json').write_text(json.dumps({'checks':checks,'bytes':path.stat().st_size},indent=2))
    print('Dynamic encoder validated',path.stat().st_size,flush=True)
if __name__=='__main__':main()
