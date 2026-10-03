#!/usr/bin/env python3
"""Export the legally observed battle trace as a self-contained, offline tactical viewer."""
import argparse,html,json
from pathlib import Path
from analyze_reports import analyze_file

def export(source,target,title):
    report=analyze_file(source)
    if report['issues'] or report['task']!='battle':raise ValueError('A structurally valid battle report is required')
    rows=[json.loads(line) for line in Path(source).read_text(encoding='utf-8').splitlines() if line.strip()]
    frames=[];enemies=[];events=[];state=None
    for row in rows:
        event,d=row['event'],row['data']
        if event=='combat_observation':enemies=d.get('visibleEnemies',[])
        if event=='observation':
            state=d
            own=[[u[k] for k in ('id','type','x','y','hp','maxHp','building','canAttack')]+[u.get('orderX'),u.get('orderY')]
                 for u in d['ownUnits'] if not u.get('dead') and u['hp']>0]
            visible=[[u[k] for k in ('id','type','x','y','hp','building','canAttack')] for u in enemies]
            for u in own+visible:u[0]=str(u[0])
            frames.append([d['gameTimeMs'],d['player']['credits'],d['match']['outcome'],own,visible])
        if event in ('own_loss','upgrade_completed','combat_retreat','army_regroup','match_terminal') and state:
            events.append([state['gameTimeMs'],event,d])
    if not frames:raise ValueError('No observations')
    data={'title':title,'source':Path(source).name,'sha256':report['sha256'],'map':state['map'],'frames':frames,'events':events,'summary':report['summary']}
    payload=json.dumps(data,ensure_ascii=False,separators=(',',':')).replace('<','\\u003c')
    template=Path(__file__).with_name('battle_viewer.html').read_text(encoding='utf-8')
    target=Path(target);target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(template.replace('__TITLE__',html.escape(title)).replace('__DATA__',payload),encoding='utf-8')
    return {'file':str(target),'frames':len(frames),'sourceSha256':report['sha256']}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('report',type=Path);p.add_argument('--out',type=Path,required=True);p.add_argument('--title',default='RW Agent 战术日志')
    a=p.parse_args();print(json.dumps(export(a.report,a.out,a.title),ensure_ascii=False))
if __name__=='__main__':main()
