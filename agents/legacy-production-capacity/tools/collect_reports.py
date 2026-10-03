#!/usr/bin/env python3
"""Create feedback ZIP volume(s) containing raw Agent reports and verified offline summaries.

A single match produces several reports (economy / development / battle) that share one sessionId, so
reports are grouped by session first and only then packed into volumes. A session is never split across
bundles; a session larger than the volume target becomes its own bundle and is marked oversize. Nothing
is deleted or modified: the collector only reads the report directory and writes new ZIPs.

Every volume carries the same provenance block as before (metadata.json with the candidate JAR SHA,
reports.json / reports.md and SHA256SUMS.txt). In addition a bundle_index.json and bundle_index.csv are
written next to the volumes, listing bundle name, sessionId, report file, phase, wall time and size.
"""
import argparse,hashlib,json,os,re,zipfile
from datetime import datetime,timezone
from pathlib import Path
from analyze_reports import analyze_file,markdown

MIB=1024*1024
DEFAULT_TARGET_MIB=85
DEFAULT_HARD_CAP_MIB=100
PHASE_ORDER={'economy':0,'development':1,'battle':2}
SESSION=re.compile(rb'"sessionId"\s*:\s*"([A-Za-z0-9_-]{1,64})"')
EPOCH=re.compile(r'^[a-z]+-(\d{10,})-')

def recover(path):
    """Complete only a verified collector ZIP; never accept an incomplete manifest."""
    path=Path(path).resolve()
    if not path.name.endswith('.zip.partial'):raise ValueError('Recovery requires a .zip.partial file')
    target=path.with_suffix('')
    if target.exists():raise ValueError('Recovery target already exists: '+str(target))
    with zipfile.ZipFile(path) as archive:
        names=archive.namelist()
        if len(names)!=len(set(names)) or archive.testzip() is not None:raise ValueError('ZIP CRC or duplicate-entry failure')
        expected={}
        for line in archive.read('SHA256SUMS.txt').decode('utf-8').splitlines():
            value,name=line.split('  ',1)
            if name in expected:raise ValueError('Duplicate checksum name')
            expected[name]=value
        if set(expected)!=(set(names)-{'SHA256SUMS.txt'}):raise ValueError('Incomplete checksum coverage')
        for name,value in expected.items():
            if hashlib.sha256(archive.read(name)).hexdigest()!=value:raise ValueError('ZIP hash mismatch: '+name)
        meta=json.loads(archive.read('metadata.json'))
        if meta.get('rawReports')!=sum(n.startswith('raw/') for n in names):raise ValueError('Raw report count mismatch')
    path.rename(target)
    return target

def phase_of(path):return path.name.split('-',1)[0]
def epoch_of(path):
    match=EPOCH.match(path.name)
    return int(match.group(1)) if match else 0
def session_of(raw):
    """The session a report belongs to. Read from a bounded prefix first, then from the whole file."""
    match=SESSION.search(raw[:262144]) or SESSION.search(raw)
    return match.group(1).decode('ascii') if match else 'unknown'

def discover(root):
    """Every raw report, with partials superseded by a committed report already filtered out."""
    reports=sorted(set(root.glob('*.jsonl'))|set(root.glob('*.jsonl.partial')))
    reports=[p for p in reports if not (p.name.endswith('.jsonl.partial') and p.with_suffix('').is_file()
                                        and not p.with_suffix('').is_symlink() and not analyze_file(p.with_suffix(''))['issues'])]
    if not reports:raise ValueError('No raw .jsonl reports found')
    if any(p.is_symlink() for p in reports):raise ValueError('Linked report files are not included')
    return reports

def survey(reports):
    """Session, phase, wall time and analysis for every report, without holding its bytes."""
    entries=[]
    for path in reports:
        raw=path.read_bytes()
        result=analyze_file(path)
        if hashlib.sha256(raw).hexdigest()!=result['sha256']:
            raise ValueError('Report changed while collecting; wait for the controller to finish: '+path.name)
        entries.append({'path':path,'bytes':len(raw),'sha256':result['sha256'],'result':result,
                        'session':session_of(raw),'phase':phase_of(path),'wallTimeMs':epoch_of(path)})
        del raw
    return entries

def group(entries):
    """One group per real session; session-less reports are grouped individually so nothing is forced
    together. Reports inside a group are ordered by wall time, then phase, then file name."""
    sessions={}
    for index,entry in enumerate(entries):
        key=entry['session'] if entry['session']!='unknown' else 'unknown#%d'%index
        sessions.setdefault(key,[]).append(entry)
    groups=[]
    for key,items in sessions.items():
        items.sort(key=lambda e:(e['wallTimeMs'],PHASE_ORDER.get(e['phase'],9),e['path'].name))
        groups.append({'key':key,'sessionId':items[0]['session'],'entries':items,
                       'bytes':sum(e['bytes'] for e in items),
                       'firstWallTimeMs':min(e['wallTimeMs'] for e in items),
                       'lastWallTimeMs':max(e['wallTimeMs'] for e in items)})
    groups.sort(key=lambda g:(g['sessionId'],g['firstWallTimeMs']))
    return groups

def pack(groups,target):
    """Fill volumes with whole sessions only; an oversized session gets a bundle of its own."""
    volumes=[]
    for group in groups:
        if not volumes or volumes[-1]['bytes']+group['bytes']>target or volumes[-1]['oversize']:
            volumes.append({'groups':[group],'bytes':group['bytes'],'oversize':group['bytes']>target})
        else:
            volumes[-1]['groups'].append(group);volumes[-1]['bytes']+=group['bytes']
    return volumes

def collect(root,out,agent=None,target_bytes=DEFAULT_TARGET_MIB*MIB,hard_cap_bytes=DEFAULT_HARD_CAP_MIB*MIB):
    root=Path(root).resolve();out=Path(out).resolve()
    if not root.is_dir():raise ValueError('Report directory does not exist: '+str(root))
    entries=survey(discover(root))
    volumes=pack(group(entries),target_bytes)
    stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ');run=os.urandom(3).hex()
    out.mkdir(parents=True,exist_ok=True)
    jar=hashlib.sha256(Path(agent).read_bytes()).hexdigest() if agent and Path(agent).is_file() else None
    bundles=[]
    for number,volume in enumerate(volumes,1):
        sessions=[g for g in volume['groups']]
        single=len(sessions)==1
        name='RW-Agent-Reports-%s-%s-p%dof%d%s.zip'%(stamp,run,number,len(volumes),
                                                    '-oversize' if volume['oversize'] else '')
        target=out/name;temp=out/(name+'.partial')
        results=[];files={};sizes={}
        for item in sessions:
            for entry in item['entries']:
                raw=entry['path'].read_bytes()
                if hashlib.sha256(raw).hexdigest()!=entry['sha256']:
                    raise ValueError('Report changed while collecting: '+entry['path'].name)
                files['raw/'+entry['path'].name]=raw;sizes[entry['path'].name]=len(raw)
                results.append(entry['result'])
                del raw
        results.sort(key=lambda r:r['file'])
        files['reports.json']=(json.dumps({'schemaVersion':1,'reports':results},ensure_ascii=False,indent=2)+'\n').encode('utf-8')
        files['reports.md']=markdown(results).encode('utf-8')
        metadata={'schemaVersion':2,'createdUtc':datetime.now(timezone.utc).isoformat(),
                  'rawReports':len(results),'agentJarSha256':jar,
                  'bundle':{'name':name,'index':number,'count':len(volumes),'rawBytes':volume['bytes'],
                            'targetBytes':target_bytes,'hardCapBytes':hard_cap_bytes,
                            'oversize':bool(volume['oversize']),'singleSession':single,
                            'sessions':[g['sessionId'] for g in sessions],
                            'phases':sorted({e['phase'] for g in sessions for e in g['entries']}),
                            'firstWallTimeMs':min(g['firstWallTimeMs'] for g in sessions),
                            'lastWallTimeMs':max(g['lastWallTimeMs'] for g in sessions)},
                  'note':'Offline copy only. No game installation, saved games, credentials or network requests.'}
        files['metadata.json']=(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n').encode('utf-8')
        files['SHA256SUMS.txt']=''.join(hashlib.sha256(data).hexdigest()+'  '+n+'\n' for n,data in sorted(files.items())).encode()
        with temp.open('wb') as stream:
            with zipfile.ZipFile(stream,'w',zipfile.ZIP_DEFLATED) as archive:
                for n,data in files.items():archive.writestr(n,data)
            stream.flush();os.fsync(stream.fileno())
        with zipfile.ZipFile(temp) as archive:
            if archive.testzip() is not None:raise ValueError('ZIP verification failed')
            for n,data in files.items():
                if archive.read(n)!=data:raise ValueError('ZIP content mismatch: '+n)
        zip_bytes=temp.stat().st_size
        if zip_bytes>hard_cap_bytes:
            raise ValueError('Volume %s is %d bytes, above the %d byte cap; a single session is too large to upload'
                             %(name,zip_bytes,hard_cap_bytes))
        temp.replace(target)
        bundles.append({'name':name,'path':target,'bytes':zip_bytes,'rawBytes':volume['bytes'],
                        'oversize':bool(volume['oversize']),'sessions':[g['sessionId'] for g in sessions],
                        'reports':[{'file':e['path'].name,'sessionId':e['session'],'phase':e['phase'],
                                    'wallTimeMs':e['wallTimeMs'],'bytes':sizes[e['path'].name],'sha256':e['sha256']}
                                   for g in sessions for e in g['entries']]})
    write_index(out,bundles,target_bytes,hard_cap_bytes)
    return bundles

def write_index(out,bundles,target_bytes,hard_cap_bytes):
    """Master index of this run's volumes plus any older bundle already sitting in the directory."""
    previous=sorted(p.name for p in out.glob('RW-Agent-Reports-*.zip') if p.name not in {b['name'] for b in bundles})
    index={'schemaVersion':1,'createdUtc':datetime.now(timezone.utc).isoformat(),
           'targetBytes':target_bytes,'hardCapBytes':hard_cap_bytes,'bundleCount':len(bundles),
           'totalRawBytes':sum(b['rawBytes'] for b in bundles),'totalZipBytes':sum(b['bytes'] for b in bundles),
           'bundles':[{'name':b['name'],'bytes':b['bytes'],'rawBytes':b['rawBytes'],'oversize':b['oversize'],
                       'sessions':b['sessions'],
                       'reports':[{**r,'wallTimeUtc':datetime.fromtimestamp(r['wallTimeMs']/1000.0,timezone.utc).isoformat()
                                   if r['wallTimeMs'] else None} for r in b['reports']]} for b in bundles],
           'previousBundles':previous,
           'note':'One row per raw report. A session is never split across bundles.'}
    (out/'bundle_index.json').write_text(json.dumps(index,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    lines=['bundle,sessionId,reportFile,phase,wallTimeMs,bytes,oversize']
    for b in bundles:
        for r in b['reports']:
            lines.append('%s,%s,%s,%s,%d,%d,%s'%(b['name'],r['sessionId'],r['file'],r['phase'],
                                                 r['wallTimeMs'],r['bytes'],str(b['oversize']).lower()))
    (out/'bundle_index.csv').write_text('\n'.join(lines)+'\n',encoding='utf-8')

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('input',nargs='?',type=Path,default=Path('rw-agent-reports'))
    p.add_argument('--out',type=Path,default=Path('report-bundles'))
    p.add_argument('--agent-jar',type=Path,default=Path(__file__).resolve().parents[1]/'rw-agent-bootstrap.jar')
    p.add_argument('--target-mib',type=float,default=DEFAULT_TARGET_MIB,help='raw report bytes per volume (default 85)')
    p.add_argument('--hard-cap-mib',type=float,default=DEFAULT_HARD_CAP_MIB,help='absolute volume ceiling (default 100)')
    p.add_argument('--recover',type=Path,help='Verify all checksums and finish a .zip.partial rename')
    args=p.parse_args(argv)
    try:
        if args.recover:print('Recovered: '+str(recover(args.recover)));return 0
        bundles=collect(args.input,args.out,args.agent_jar,
                        target_bytes=int(args.target_mib*MIB),hard_cap_bytes=int(args.hard_cap_mib*MIB))
        print('Send all %d volume(s) from %s:'%(len(bundles),args.out))
        for b in bundles:
            print('  %-64s %8.1f MiB raw  %6.1f MiB zip  sessions=%s%s'%(
                b['name'],b['rawBytes']/MIB,b['bytes']/MIB,','.join(b['sessions']),
                '  OVERSIZE (single large session, own bundle)' if b['oversize'] else ''))
        print('Index: %s  and  %s'%((args.out/'bundle_index.json'),(args.out/'bundle_index.csv')))
        return 0
    except (OSError,ValueError,KeyError,zipfile.BadZipFile) as error:p.exit(1,str(error)+'\n')

if __name__=='__main__':raise SystemExit(main())
