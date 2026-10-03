#!/usr/bin/env python3
"""Build verified upgrade/full ZIPs from an Agent tree and an assembled game tree.
All hashes are computed from actual bytes. Game assets remain private to this package.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import zipfile

def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1048576),b''):h.update(chunk)
    return h.hexdigest()

def project_files(root):
    files={}
    for p in root.rglob('*'):
        if not p.is_file() or p.is_symlink():continue
        rel=p.relative_to(root)
        if '__pycache__' in rel.parts or rel.parts[:2] in (('developer','build'),('developer','dist')):continue
        if re.fullmatch(r'tmp[a-z0-9_]{6,12}',rel.parts[0]) or p.suffix=='.lock':continue
        if rel.as_posix() in ('SHA256SUMS.txt','rw-agent-bootstrap.log'):continue
        files[rel.as_posix()]=p
    return files

def archive(path, prefix, files):
    temp=path.with_suffix('.zip.partial')
    checks=[]
    with temp.open('wb') as out:
        with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=6,allowZip64=True) as z:
            for rel,source in sorted(files.items()):
                value=digest(source);checks.append(value+'  '+rel)
                z.write(source,prefix+'/'+rel)
            z.writestr(prefix+'/SHA256SUMS.txt','\n'.join(checks)+'\n')
        out.flush();os.fsync(out.fileno())
    temp.replace(path)
    with zipfile.ZipFile(path) as z:
        corrupt=z.testzip()
        if corrupt:raise ValueError('ZIP CRC failure: '+corrupt)
        expected={s.split('  ',1)[1]:s.split('  ',1)[0] for s in checks}
        for rel,value in expected.items():
            h=hashlib.sha256()
            with z.open(prefix+'/'+rel) as f:
                for chunk in iter(lambda:f.read(1048576),b''):h.update(chunk)
            if h.hexdigest()!=value:raise ValueError('ZIP hash mismatch: '+rel)
    return {'file':path.name,'bytes':path.stat().st_size,'sha256':digest(path),'entries':len(files)+1,'crcAndSha256Verified':True}

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--project',required=True,type=Path);ap.add_argument('--game',required=True,type=Path)
    ap.add_argument('--assembly-manifest',required=True,type=Path);ap.add_argument('--out',required=True,type=Path)
    ap.add_argument('--upgrade-only',action='store_true')
    args=ap.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    project=project_files(args.project)
    original=json.loads(args.assembly_manifest.read_text(encoding='utf-8'))
    full={}
    for item in original['files']:
        rel=item['path'];p=args.game/rel
        if p.stat().st_size!=item['bytes'] or digest(p)!=item['sha256']:raise ValueError('Original file changed: '+rel)
        if rel in ('SHA256SUMS','SHA256SUMS.txt'):
            full['docs/history/original-game-'+rel]=p
        else:full[rel]=p
    full.update(project)
    results=[archive(args.out/'Rusted-Warfare-Agent-0.07-Upgrade.zip','Rusted-Warfare-Agent-0.07-alpha1',project)]
    if not args.upgrade_only:results.append(archive(args.out/'Rusted-Warfare-1.15-Agent-0.07-Full.zip','Rusted-Warfare-1.15-Agent-0.07',full))
    shutil.copy2(args.project/'docs/DELIVERY_CN.md',args.out/'DELIVERY_CN.md')
    results.append({'file':'DELIVERY_CN.md','bytes':(args.out/'DELIVERY_CN.md').stat().st_size,'sha256':digest(args.out/'DELIVERY_CN.md')})
    (args.out/'SHA256SUMS.txt').write_text('\n'.join(r['sha256']+'  '+r['file'] for r in results)+'\n',encoding='utf-8')
    print(json.dumps(results,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
