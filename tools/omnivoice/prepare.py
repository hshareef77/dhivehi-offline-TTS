"""Download pinned components, build a validated dynamic encoder, and prepare a manifest.

The original community encoder is deliberately not shipped: it has a fixed 1-second input.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path
from huggingface_hub import hf_hub_download, HfApi

SOURCE_REVISION = '3fc979d1cd72492cd679c5ac2611e0d7ac047ba1'

def main():
    p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--publish',action='store_true')
    args=p.parse_args();root=Path(__file__).resolve().parents[2]
    work=args.work.resolve();pkg=work/'package';pkg.mkdir(parents=True,exist_ok=True)
    manifest=json.loads((root/'models/omnivoice/manifest.json').read_text())
    for f in manifest['files']:
        if f['path'] in ['encoder.onnx','model_config.json']: continue
        path=pkg/f['path']
        if not path.exists(): urllib.request.urlretrieve(f['url'],path)
        with path.open('rb') as stream: digest=hashlib.file_digest(stream,'sha256').hexdigest()
        assert path.stat().st_size==f['size'] and digest==f['sha256'], f['path']
    source=work/'original_codec';source.mkdir(exist_ok=True)
    for filename in ['config.json','model.safetensors']:
        hf_hub_download('k2-fsa/OmniVoice','audio_tokenizer/'+filename,revision=SOURCE_REVISION,local_dir=source)
    subprocess.run([sys.executable,str(root/'tools/omnivoice/export_encoder.py'),'--source',str(source/'audio_tokenizer'),'--out',str(pkg)],check=True)
    shutil.copy(root/'models/omnivoice/model_config.json',pkg/'model_config.json')
    shutil.copy(root/'THIRD_PARTY_NOTICES.md',pkg/'README.md')
    report=work/'report';report.mkdir(exist_ok=True)
    shutil.copy(pkg/'encoder_validation.json',report/'encoder_validation.json')
    subprocess.run([sys.executable,str(root/'tools/omnivoice/reference.py'),'--model',str(pkg),'--out',str(report)],check=True)
    # Validate exactly the JVM implementation that the Android app includes.
    subprocess.run(['gradle','-p',str(root/'engine'),'test',f'-Domni.model.dir={pkg}',f'-Domni.reference.dir={report}','--stacktrace'],check=True)
    target='Hshareef77/omnivoice-dhivehi-onnx'
    manifest['files']=[f for f in manifest['files'] if f['path'] not in ['encoder.onnx','model_config.json']]
    for name in ['encoder.onnx','model_config.json']:
        file=pkg/name
        with file.open('rb') as stream: digest=hashlib.file_digest(stream,'sha256').hexdigest()
        manifest['files'].append({'path':name,'size':file.stat().st_size,'sha256':digest,
                                 'url':f'https://huggingface.co/{target}/resolve/main/{name}'})
    manifest['total_size']=sum(f['size'] for f in manifest['files'])
    manifest['sources']['k2-fsa/OmniVoice audio encoder']=SOURCE_REVISION
    (pkg/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    shutil.copy(pkg/'manifest.json',report/'manifest.json')
    print('Complete offline download:',manifest['total_size'],'bytes',flush=True)
    if args.publish:
        api=HfApi();api.create_repo(target,repo_type='model',private=False,exist_ok=True)
        for name in ['encoder.onnx','model_config.json','README.md','encoder_validation.json']:
            api.upload_file(repo_id=target,path_or_fileobj=str(pkg/name),path_in_repo=name)
        # Pin newly published component URLs before making the manifest visible.
        revision=api.model_info(target).sha
        for f in manifest['files']:
            if f['path'] in ['encoder.onnx','model_config.json']:
                f['url']=f'https://huggingface.co/{target}/resolve/{revision}/'+f['path']
        (pkg/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
        api.upload_file(repo_id=target,path_or_fileobj=str(pkg/'manifest.json'),path_in_repo='manifest.json')
        shutil.copy(pkg/'manifest.json',report/'manifest.json')
        print('Validated OmniVoice package published.',flush=True)
if __name__=='__main__':main()
