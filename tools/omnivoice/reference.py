"""Independent NumPy reference for validating Kotlin OmniVoice inference.

Based on the Apache-2.0 OmniVoice decoding algorithm (Xiaomi, 2026).
Uses only local ONNX models; no Python runtime is embedded in the Android app.
"""
import argparse
import json
import math
import time
from pathlib import Path
import numpy as np
import onnxruntime as ort
import soundfile as sf
from tokenizers import Tokenizer

TEXT = "މިއަދު މޫސުން ވަރަށް ރަނގަޅު."


def generate(folder, text=TEXT, voice=None, steps=32):
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 3
    opts.inter_op_num_threads = 1
    lm = ort.InferenceSession(str(folder / "lm.onnx"), opts)
    decoder = ort.InferenceSession(str(folder / "decoder.onnx"), opts)
    tok = Tokenizer.from_file(str(folder / "tokenizer.json"))
    # Same duration estimator as upstream for Thaana/Latin, including its short-input boost.
    import unicodedata
    def weight(t):
        total = 0.0
        for c in t:
            category = unicodedata.category(c)
            if c == '\u0640' or category.startswith('M'): continue
            if c.isspace(): total += .2
            elif c.isdigit(): total += 3.5
            elif category[0] in 'PS': total += .5
            elif 0x590 <= ord(c) <= 0x8ff: total += 1.5
            else: total += 1
        return total
    ref_text = voice['text'] if voice else 'Nice to meet you.'
    ref_frames = len(voice['codes'][0]) if voice else 25
    raw = weight(text) * ref_frames / weight(ref_text)
    n = max(1, int(50 * (raw / 50) ** (1 / 3) if raw < 50 else raw))
    style = ('<|denoise|>' if voice else '') + '<|lang_start|>dv<|lang_end|><|instruct_start|>None<|instruct_end|>'
    full = (voice['text'] + ' ' if voice else '') + text
    prompt = tok.encode(style, add_special_tokens=False).ids + tok.encode('<|text_start|>'+full+'<|text_end|>', add_special_tokens=False).ids
    ref_n = ref_frames if voice else 0
    start = len(prompt)+ref_n
    length = start+n
    ids = np.full((2,8,length),1024,dtype=np.int64)
    ids[0,:,:len(prompt)] = prompt
    if voice: ids[0,:,len(prompt):start] = voice['codes']
    mask = np.zeros((2,length),dtype=bool)
    mask[0,len(prompt):] = True; mask[1,:n] = True
    attention = np.zeros((2,length),dtype=np.int64);attention[0] = 1;attention[1,:n] = 1
    positions = np.tile(np.arange(length,dtype=np.int64),(2,1))
    codes = np.full((8,n),1024,dtype=np.int64)
    remaining = codes.size
    def shift(t): return .1*t/(1-.9*t)
    started = time.monotonic()
    for step in range(steps):
        count = remaining if step == steps-1 else min(remaining,math.ceil(codes.size*(shift((step+1)/steps)-shift(step/steps))))
        if count == 0: continue
        logits = lm.run(None,{'input_ids':ids,'audio_mask':mask,'attention_mask':attention,'position_ids':positions})[0]
        guided = 3*logits[0,:,start:].astype(np.float64)-2*logits[1,:,:n].astype(np.float64)
        maximum=guided.max(-1,keepdims=True)
        logz=maximum[...,0]+np.log(np.exp(guided-maximum).sum(-1))
        pred=guided[...,:1024].argmax(-1)
        scores=np.take_along_axis(guided,pred[...,None],-1)[...,0]-logz-np.arange(8)[:,None]*5
        scores[codes!=1024]=-np.inf
        # Stable index tie-breaking matches the JVM deterministic verification mode.
        selected=np.argsort(-scores.flatten(),kind='stable')[:count]
        codes.flat[selected]=pred.flatten()[selected]
        ids[0,:,start:]=codes;ids[1,:,:n]=codes
        remaining-=count
        print(f'Step {step+1}/{steps}',flush=True)
    assert remaining==0 and np.all(codes<1024)
    audio=decoder.run(None,{'audio_codes':codes[None]})[0].flatten()
    assert audio.size and np.isfinite(audio).all() and np.max(np.abs(audio))>1e-6
    elapsed=time.monotonic()-started
    return codes,audio,{'text':text,'frames':n,'seconds':len(audio)/24000,'generation_seconds':elapsed,'rtf':elapsed/(len(audio)/24000)}


def main():
    p=argparse.ArgumentParser();p.add_argument('--model',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    args=p.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    tok=Tokenizer.from_file(str(args.model/'tokenizer.json'))
    cases=[TEXT,'ދިވެހި ރާއްޖެ','Hello 123!','cafe\u0301','<|lang_start|>dv<|lang_end|>','<|text_start|>'+TEXT+'<|text_end|>','\nމިއަދު\t ރަނގަޅު.']
    (args.out/'tokenizer_cases.json').write_text(json.dumps([{'text':t,'ids':tok.encode(t,add_special_tokens=False).ids} for t in cases],ensure_ascii=False))
    codes,audio,report=generate(args.model)
    (args.out/'expected_codes.json').write_text(json.dumps({'text':TEXT,'codes':codes.tolist()}))
    sf.write(args.out/'python_dhivehi.wav',audio,24000,subtype='PCM_16')
    (args.out/'reference_report.json').write_text(json.dumps(report,indent=2))
    encoder_opts = ort.SessionOptions(); encoder_opts.intra_op_num_threads = 3
    encoder = ort.InferenceSession(str(args.model/'encoder.onnx'),encoder_opts)
    reference_audio = (np.sin(np.arange(78720)*.04)*.1).astype(np.float32)
    voice_codes = encoder.run(None, {encoder.get_inputs()[0].name: reference_audio[None,None]})[0][0]
    del encoder
    voice = {'format':1,'text':'މިއަދު މޫސުން ރަނގަޅު.','codes':voice_codes.tolist(),'rms':float(np.sqrt(np.mean(reference_audio**2)))}
    (args.out/'test_voice.json').write_text(json.dumps(voice,ensure_ascii=False))
    clone_codes,clone_audio,clone_report=generate(args.model,voice=voice)
    (args.out/'expected_clone_codes.json').write_text(json.dumps({'text':TEXT,'codes':clone_codes.tolist()}))
    clone_report['reference_kind']='Synthetic sine wave for numerical pipeline verification only; not a voice-quality sample'
    (args.out/'clone_report.json').write_text(json.dumps(clone_report,indent=2))
    print(json.dumps(report),flush=True)
if __name__=='__main__':main()
