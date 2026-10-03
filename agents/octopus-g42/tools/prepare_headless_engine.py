#!/usr/bin/env python3
import argparse, hashlib, json, shutil, tempfile, zipfile
from pathlib import Path, PurePosixPath

def sha256(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(1<<20), b''): h.update(b)
    return h.hexdigest()

def main():
    p=argparse.ArgumentParser(description='Extract and verify the public RW 1.15 headless resource pack.')
    p.add_argument('--archive',type=Path,default=Path('astra-relay/headless-engine-1.15.zip'))
    p.add_argument('--manifest',type=Path,default=Path('astra-relay/HEADLESS_ENGINE_MANIFEST.json'))
    p.add_argument('--out',type=Path,default=Path('.engine/rw115'))
    p.add_argument('--verify-only',action='store_true')
    a=p.parse_args(); manifest=json.loads(a.manifest.read_text(encoding='utf-8-sig'))
    if sha256(a.archive)!=manifest['archiveSha256']: raise SystemExit('archive SHA256 mismatch')
    if not a.verify_only:
        if a.out.exists(): shutil.rmtree(a.out)
        a.out.parent.mkdir(parents=True,exist_ok=True)
        # The relay ZIP was written on Windows and contains backslash entry names.
        # Normalize separators before extraction; retain all byte/hash checks below.
        with zipfile.ZipFile(a.archive) as z:
            for info in z.infolist():
                name=PurePosixPath(info.filename.replace('\\', '/'))
                if name.is_absolute() or '..' in name.parts or ':' in str(name):
                    raise SystemExit('unsafe archive path: '+info.filename)
                target=a.out.joinpath(*name.parts)
                if info.filename.endswith(('/', '\\')):
                    target.mkdir(parents=True,exist_ok=True)
                else:
                    target.parent.mkdir(parents=True,exist_ok=True)
                    with z.open(info) as src, target.open('wb') as dst:
                        shutil.copyfileobj(src,dst)
    root=a.out
    if not root.is_dir(): raise SystemExit('output directory missing: '+str(root))
    checked=0
    for row in manifest['files']:
        path=root/Path(row['path'])
        if not path.is_file(): raise SystemExit('missing file: '+row['path'])
        if path.stat().st_size!=row['size']: raise SystemExit('size mismatch: '+row['path'])
        if sha256(path)!=row['sha256']: raise SystemExit('SHA256 mismatch: '+row['path'])
        checked+=1
    game=root/'game-lib.jar'
    if sha256(game)!=manifest['gameLibSha256']: raise SystemExit('game-lib.jar identity mismatch')
    print(json.dumps({'status':'PASS','files':checked,'gameDir':str(root.resolve()),'gameLibSha256':manifest['gameLibSha256']},ensure_ascii=False))

if __name__=='__main__': main()
