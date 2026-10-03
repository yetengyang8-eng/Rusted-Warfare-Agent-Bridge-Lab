#!/usr/bin/env python3
import argparse, hashlib, json, subprocess
from pathlib import Path

def sha256(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(1<<20), b''): h.update(b)
    return h.hexdigest()

def main():
    p=argparse.ArgumentParser(); p.add_argument('game_dir',type=Path); p.add_argument('archive',type=Path); p.add_argument('out',type=Path); a=p.parse_args()
    game=a.game_dir.resolve(); files=[game/'game-lib.jar']
    for root in ('assets','libs','res'): files += sorted((game/root).rglob('*'))
    files=[x for x in files if x.is_file()]
    rows=[{'path':x.relative_to(game).as_posix(),'size':x.stat().st_size,'sha256':sha256(x)} for x in files]
    blob=subprocess.check_output(['git','hash-object',str(game/'game-lib.jar')],text=True).strip()
    obj={'schemaVersion':1,'baseGame':'Rusted Warfare PC 1.15 Build #28 / Game Code 176','archive':a.archive.name,'archiveSha256':sha256(a.archive),'gameLibSha256':sha256(game/'game-lib.jar'),'gameLibGitBlobSha1':blob,'publicEquivalent':{'repository':'TapeRTS/Tape','path':'1.15/game-lib.jar','gitBlobSha1':'4d4034ac49311bfcb8870f49a56d4d01d622b3ec','url':'https://github.com/TapeRTS/Tape/blob/master/1.15/game-lib.jar'},'requiredTopLevel':['game-lib.jar','assets/','libs/','res/'],'fileCount':len(rows),'files':rows}
    a.out.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'files':len(rows),'archiveSha256':obj['archiveSha256'],'gameLibSha256':obj['gameLibSha256'],'gameLibGitBlobSha1':blob},ensure_ascii=False))
if __name__=='__main__': main()
