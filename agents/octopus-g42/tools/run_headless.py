#!/usr/bin/env python3
"""Run bounded native RW 1.15 experiments in isolated working directories.
Python standard library; Java 8+ runtime with jdk.httpserver (Java 11+ recommended).
Does not control an already running desktop game. Uses a new port and process per episode.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeout, as_completed
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import threading
import time
import zipfile
import urllib.error
from urllib.parse import parse_qs, urlsplit
import urllib.request
from datetime import datetime, timezone
from analyze_reports import analyze_file

EXPECTED_GAME = '8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9'
DEFAULT_MAP = 'maps/skirmish/[p2]Small_Island (2p).tmx'
PROFILE_PROPERTIES = {'rwagent.mobileUnitHardCap': (24, 80)}

def _unique_json_pairs(pairs):
    result={}
    for key,value in pairs:
        if key in result:raise ValueError('Duplicate profile JSON key: '+key)
        result[key]=value
    return result

def load_ab_profiles(path):
    """Validate the experiment-only JVM property whitelist before starting any process."""
    path=Path(path)
    raw=path.read_bytes()
    source=json.loads(raw.decode('utf-8-sig'),object_pairs_hook=_unique_json_pairs)
    if not isinstance(source,dict) or set(source)!={'schemaVersion','profiles'} or type(source['schemaVersion']) is not int or source['schemaVersion']!=1:
        raise ValueError('Profile file must contain schemaVersion=1 and profiles only')
    definitions=source['profiles']
    if not isinstance(definitions,dict) or set(definitions)!={'A','B'}:
        raise ValueError('Profile file must define exactly A and B')
    profiles={}
    for slot in ('A','B'):
        item=definitions[slot]
        if not isinstance(item,dict) or set(item)!={'name','jvmProperties'}:
            raise ValueError('Profile '+slot+' requires name and jvmProperties only')
        name=item['name'];properties=item['jvmProperties']
        if not isinstance(name,str) or not name or len(name)>64 or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-' for c in name):
            raise ValueError('Profile '+slot+' has an invalid name')
        if not isinstance(properties,dict) or set(properties)!={'rwagent.mobileUnitHardCap'}:
            raise ValueError('Profile '+slot+' has properties outside the experiment whitelist')
        for key,value in properties.items():
            low,high=PROFILE_PROPERTIES[key]
            if type(value) is not int or not low<=value<=high:
                raise ValueError('Profile '+slot+' '+key+' must be an integer in '+str((low,high)))
        normalized={'name':name,'jvmProperties':properties}
        canonical=json.dumps(normalized,ensure_ascii=False,sort_keys=True,separators=(',',':'))
        profiles[slot]={**normalized,'canonicalJson':canonical,
                        'digest':hashlib.sha256(canonical.encode('utf-8')).hexdigest()}
    if profiles['A']['name']==profiles['B']['name'] or profiles['A']['jvmProperties']==profiles['B']['jvmProperties']:
        raise ValueError('A and B must have distinct names and experimental property values')
    return {'schemaVersion':1,'profiles':profiles,'sourceSha256':hashlib.sha256(raw).hexdigest()}

def order_profiles(loaded,order):
    if order not in ('AB','BA'):raise ValueError('Profile order must be AB or BA')
    return tuple(loaded['profiles'][slot] for slot in order)

def jar_content_digest(path):
    """Same content identity as _analysis/sha_lineage.py; ignore manifest and ZIP metadata."""
    with zipfile.ZipFile(path) as archive:
        entries={name:hashlib.sha256(archive.read(name)).hexdigest()
                 for name in archive.namelist() if name!='META-INF/MANIFEST.MF'}
    content='\n'.join('%s %s'%(name,entries[name]) for name in sorted(entries))
    return hashlib.sha256(content.encode('utf-8')).hexdigest()

def validate_match_report_tasks(tasks):
    """Accept the legacy three-stage workflow and the bounded builder bootstrap extension."""
    if sorted(task for task in tasks if task != 'bootstrap') != ['battle', 'development', 'economy'] or tasks.count('bootstrap') > 1:
        raise RuntimeError('match workflow must produce one battle, development and economy report, with at most one bootstrap report; partial workflow evidence archived')


def battle_config(path):
    rows=[]
    with path.open(encoding='utf-8-sig') as source:
        for line in source:
            if line.strip():
                row=json.loads(line)
                if row.get('event')=='battle_config':rows.append(row.get('data'))
    if len(rows)!=1 or not isinstance(rows[0],dict):
        raise RuntimeError('Battle report must have exactly one battle_config event')
    return rows[0]

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(1048576), b''):h.update(b)
    return h.hexdigest()

def windows_junction(source, target):
    """Try a directory junction without interpolating paths into shell code."""
    if os.name != 'nt':return False
    env=os.environ.copy()
    env['RW_STAGE_SOURCE']=str(source.resolve())
    env['RW_STAGE_TARGET']=str(target.absolute())
    command=['powershell.exe','-NoProfile','-NonInteractive','-Command',
             'New-Item -ItemType Junction -Path $env:RW_STAGE_TARGET -Target $env:RW_STAGE_SOURCE -ErrorAction Stop | Out-Null']
    try:
        result=subprocess.run(command,env=env,stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL,timeout=15)
    except (OSError,subprocess.SubprocessError):return False
    return result.returncode==0 and target.is_dir() and target.resolve()==source.resolve()

def stage(game, jar, work, *, allow_junction=False):
    work.mkdir(parents=True, exist_ok=False)
    staged=[]
    for name in ('game-lib.jar','libs','assets','res'):
        source=game/name; target=work/name
        if not source.exists(): raise FileNotFoundError(source)
        try:
            target.symlink_to(source, target_is_directory=source.is_dir())
            method='symlink'
        except OSError:
            if source.is_dir() and allow_junction and windows_junction(source,target):method='junction'
            else:
                # A failed junction must not be overwritten or traversed by copytree.
                if target.exists() or target.is_symlink():raise RuntimeError('Staging target exists after link failure: '+str(target))
                if source.is_dir():shutil.copytree(source,target)
                else:shutil.copy2(source,target)
                method='copy'
        staged.append({'name':name,'source':str(source.resolve()),
                       'target':str(target.absolute()),'method':method})
    shutil.copy2(jar,work/'rw-agent-bootstrap.jar')
    staged.append({'name':'rw-agent-bootstrap.jar','source':str(jar.resolve()),
                   'target':str((work/'rw-agent-bootstrap.jar').absolute()),'method':'copy',
                   'sha256':sha(work/'rw-agent-bootstrap.jar')})
    write_json(work/'staging.json',{'schemaVersion':1,'items':staged})
    # Never copy the user's preferences, saves, mods or replays.

def get(port, path):
    with urllib.request.urlopen('http://127.0.0.1:%d%s'%(port,path),timeout=2) as r:
        return json.load(r)

def write_json(path, data):
    write_bytes(path,(json.dumps(data,ensure_ascii=False,indent=2)+'\n').encode('utf-8'))

def write_bytes(path,data):
    """原子写：临时文件 + fsync + replace。

    Windows 上 `os.replace` 会在**目标文件正被别的进程读取**时以
    `[WinError 5] 拒绝访问` 失败（读者句柄默认不含 FILE_SHARE_DELETE）。
    2026-09-27 的 campaign 就是这样被阻塞的：artifact 里 `campaign.json.tmp` 留着完整的 BLOCKED 状态，
    而 `campaign.json` 停在更早的 RUNNING —— 我用"并发只读句柄 + replace"复现出了同一条错误
    （`_analysis/winerror5_forensic.py`）。状态写入对**瞬态**占用做有界重试：
    占用方释放后即可成功；占用持续存在时仍然按原样抛出（绝不静默成功）。
    """
    temp=path.with_suffix(path.suffix+'.tmp')
    with temp.open('wb') as output:output.write(data);output.flush();os.fsync(output.fileno())
    attempts=max(1,int(os.environ.get('RW_WRITE_REPLACE_ATTEMPTS','6')))
    for attempt in range(attempts):
        try:
            temp.replace(path);return
        except PermissionError:
            if attempt+1>=attempts:raise
            time.sleep(0.25*(attempt+1))

def same_path(a,b):
    return os.path.normcase(os.path.abspath(str(a)))==os.path.normcase(os.path.abspath(str(b)))

def verify_owner(health,state,port,work,agent_sha,game_sha):
    """Refuse to send commands to a service that does not prove this episode owns it."""
    provenance=health.get('provenance') or {}
    expected={'workingDirectory':work,'reportDirectory':work/'rw-agent-reports',
              'agentJar':work/'rw-agent-bootstrap.jar','gameLibJar':work/'game-lib.jar'}
    if health.get('status')!='ok' or health.get('version')!='0.07-alpha1' or health.get('port')!=port or not health.get('allowCommands'):
        raise RuntimeError('API health identity does not match requested port/version')
    for key,path in expected.items():
        if not provenance.get(key) or not same_path(provenance[key],path):
            raise RuntimeError('API '+key+' belongs to another working directory')
    if provenance.get('agentJarSha256')!=agent_sha or provenance.get('gameLibJarSha256')!=game_sha:
        raise RuntimeError('API JAR identity does not match staged files')
    if state.get('status')!='running' or not state.get('sessionId'):
        raise RuntimeError('API has no running session')

def report_sessions(path):
    """Collect session evidence from observations and command URLs in one raw report."""
    sessions=set()
    with path.open(encoding='utf-8-sig') as source:
        for line in source:
            if not line.strip():continue
            row=json.loads(line)
            data=row.get('data') or {}
            if isinstance(data.get('sessionId'),str):sessions.add(data['sessionId'])
            if row.get('event')=='action' and isinstance(data.get('path'),str):
                sessions.update(parse_qs(urlsplit(data['path']).query).get('sessionId',[]))
    return sessions

def verify_live_owner(proc,work,port,session,agent_sha,game_sha):
    if proc.poll() is not None:raise RuntimeError('This episode engine exited before the next command')
    health=get(port,'/health');state=get(port,'/state')
    verify_owner(health,state,port,work,agent_sha,game_sha)
    if state['sessionId']!=session:raise RuntimeError('This episode session changed before the next command')
    return state

def process_info(pids):
    """{pid: command line}, or None when this platform cannot verify process identity."""
    wanted=[int(p) for p in pids if p]
    if not wanted:return {}
    if os.name!='nt':return None
    script=('[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;'
            '$out=@();foreach($p in @(%s)){'
            '$c=Get-CimInstance Win32_Process -Filter ("ProcessId=$p") -ErrorAction SilentlyContinue;'
            'if($c){$out+=[pscustomobject]@{pid=$p;cmd=$c.CommandLine}}};'
            'if($out.Count -eq 0){"[]"}else{$out|ConvertTo-Json -Compress}'
            % ','.join(str(pid) for pid in wanted))
    try:
        completed=subprocess.run(['powershell','-NoProfile','-NonInteractive','-Command',script],
                                 capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30)
    except Exception:return None
    text=(completed.stdout or '').strip()
    if not text:return {}
    try:data=json.loads(text)
    except ValueError:return None
    if isinstance(data,dict):data=[data]
    return {int(row['pid']):(row.get('cmd') or '') for row in data if isinstance(row,dict) and row.get('pid') is not None}

def port_owners(ports):
    """{port: owning pid}, or None when this platform cannot verify port ownership."""
    wanted=sorted({int(p) for p in ports if p})
    if not wanted:return {}
    if os.name!='nt':return None
    script=('[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;'
            '$out=@();foreach($p in @(%s)){'
            '$c=Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue;'
            'foreach($x in $c){$out+=[pscustomobject]@{port=$p;pid=$x.OwningProcess}}};'
            'if($out.Count -eq 0){"[]"}else{$out|ConvertTo-Json -Compress}'
            % ','.join(str(port) for port in wanted))
    try:
        completed=subprocess.run(['powershell','-NoProfile','-NonInteractive','-Command',script],
                                 capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30)
    except Exception:return None
    text=(completed.stdout or '').strip()
    if not text:return {}
    try:data=json.loads(text)
    except ValueError:return None
    if isinstance(data,dict):data=[data]
    return {int(row['port']):int(row['pid']) for row in data
            if isinstance(row,dict) and row.get('port') is not None and row.get('pid') is not None}

ENGINE_MARKER='io.rwagent.headless.HeadlessRunner'
CLIENT_MARKER='io.rwagent.client.'

def command_token(command,token):
    """Match one complete OS command-line token, not a numeric prefix of another port."""
    return re.search(r'(?:^|[\s"])'+re.escape(token)+r'(?=$|[\s"])',command) is not None

def claims_identity(entry,command,port_owner):
    """(ok, reason) for one claims entry. ASCII-only checks, so a mojibake path cannot defeat them."""
    role=entry.get('role');port=entry.get('port')
    if role=='engine':
        if ENGINE_MARKER not in command:return False,'command line is not a headless engine'
        if port is not None and not (command_token(command,'--port %d'%port) or command_token(command,'--port=%d'%port)):
            return False,'engine command line does not carry the claimed port'
        if port is not None and port_owner!=int(entry['pid']):
            return False,'claimed port is not proven to be listened on by this pid'
    elif role=='client':
        if CLIENT_MARKER not in command:return False,'command line is not an agent client'
        if port is not None and not command_token(command,'-Drwagent.port=%d'%port):
            return False,'client command line does not carry the claimed port'
    else:
        return False,'role %r is never reaped'%role
    return True,'ok'

class LiveClaims:
    """Process registry for one batch (orchestrator crash recovery v0).

    A killed orchestrator cannot clean up after itself, so it records every process it starts -
    role, pid, port, working directory, episode, session - and updates that file as processes are
    created and as they exit. `--reap <run dir>` later uses ONLY this file, and re-verifies the
    command line and port ownership of every candidate before killing it, so an unrelated java
    process can never be selected. The orchestrator registers itself too, with a role that the
    reaper refuses on principle - a PID reused by something else is therefore never mistaken for it.
    """
    SCHEMA=1
    def __init__(self,run):
        self.run=run
        self.path=Path(run)/'live-claims.json'
        self.lock=threading.Lock()
        self.processes={}
        self.header={'schemaVersion':self.SCHEMA,'batchDirectory':str(run),'orchestratorPid':os.getpid(),
                     'startedUtc':datetime.now(timezone.utc).isoformat()}
        self.write()
    def add(self,role,pid,port=None,work=None,episode=None,session=None,marker=None):
        with self.lock:
            self.processes[int(pid)]={'role':role,'pid':int(pid),'port':port,
                'workDirectory':str(work) if work is not None else None,'episode':episode,
                'sessionId':session,'commandMarker':marker,'state':'RUNNING',
                'startedUtc':datetime.now(timezone.utc).isoformat()}
            self.write_locked()
    def exited(self,pid,returncode=None):
        with self.lock:
            entry=self.processes.get(int(pid))
            if entry is not None and entry.get('state')=='RUNNING':
                entry['state']='EXITED';entry['exitCode']=returncode
                entry['finishedUtc']=datetime.now(timezone.utc).isoformat()
                self.write_locked()
    def write_locked(self):
        payload=dict(self.header)
        payload['processes']=[self.processes[key] for key in sorted(self.processes)]
        payload['updatedUtc']=datetime.now(timezone.utc).isoformat()
        write_json(self.path,payload)
    def write(self):
        with self.lock:self.write_locked()

def reap_run(run,timeout=15.0):
    """Kill only the processes registered in <run>/live-claims.json, after re-verifying identity.

    Idempotent: a second call finds nothing alive and changes nothing. Returns 0 when every claimed
    pid is gone and every claimed engine port is released, 1 otherwise.
    """
    run=Path(run).resolve()
    report={'schemaVersion':1,'runDirectory':str(run),
            'reapedUtc':datetime.now(timezone.utc).isoformat(),'killed':[],'skipped':[],'verified':[]}
    claims_path=run/'live-claims.json'
    if not claims_path.exists():
        report['status']='PASS';report['note']='no live-claims.json; nothing to reap'
        write_json(run/'reap-report.json',report)
        print(json.dumps({'reap':str(run),'status':'PASS','killed':0,'note':report['note']},ensure_ascii=False),flush=True)
        return 0
    claims=json.loads(claims_path.read_text(encoding='utf-8'))
    entries=[entry for entry in (claims.get('processes') or []) if entry.get('pid')]
    identity_errors=[]
    if not same_path(claims.get('batchDirectory',''),run):
        identity_errors.append('claims batchDirectory differs from requested run directory')
    for entry in entries:
        if entry.get('role') in ('engine','client'):
            episode=entry.get('episode')
            expected=run/('episode-%03d'%episode) if type(episode) is int and episode>0 else None
            if expected is None or not same_path(entry.get('workDirectory',''),expected):
                identity_errors.append('pid %s workDirectory does not belong to its episode in this run'%entry.get('pid'))
    if identity_errors:
        report['status']='FAIL';report['verified']=identity_errors
        write_json(run/'reap-report.json',report)
        print(json.dumps({'reap':str(run),'status':'FAIL','killed':0,'verified':identity_errors},ensure_ascii=False),flush=True)
        return 1
    info=process_info([entry['pid'] for entry in entries])
    owners=port_owners([entry.get('port') for entry in entries])
    if info is None or owners is None:
        report['status']='FAIL'
        report['verified']=['process or port identity lookup unavailable; no process was killed']
        write_json(run/'reap-report.json',report)
        print(json.dumps({'reap':str(run),'status':'FAIL','killed':0,'verified':report['verified']},ensure_ascii=False),flush=True)
        return 1
    for entry in entries:
        pid=int(entry['pid'])
        if pid not in info:
            report['skipped'].append({'pid':pid,'role':entry.get('role'),'reason':'process no longer running'})
            if entry.get('state')=='RUNNING':entry['state']='GONE'
            continue
        ok,reason=claims_identity(entry,info[pid],owners.get(entry.get('port')))
        if not ok:
            report['skipped'].append({'pid':pid,'role':entry.get('role'),'reason':reason})
            continue
        try:
            subprocess.run(['taskkill','/PID',str(pid),'/F'],capture_output=True,text=True,timeout=timeout)
        except (OSError,subprocess.SubprocessError) as error:
            report['skipped'].append({'pid':pid,'role':entry.get('role'),'reason':'kill failed: %s'%error})
            continue
        report['killed'].append({'pid':pid,'role':entry.get('role'),'port':entry.get('port'),
                                 'episode':entry.get('episode')})
        entry['state']='REAPED';entry['reapedUtc']=datetime.now(timezone.utc).isoformat()
    time.sleep(0.5)
    killed_pids={item['pid'] for item in report['killed']}
    alive=process_info(sorted(killed_pids)) if killed_pids else {}
    owners_after=port_owners([entry.get('port') for entry in entries
                              if int(entry['pid']) in killed_pids]) if killed_pids else {}
    failures=[]
    if alive is None:failures.append('post-reap process identity lookup unavailable')
    if owners_after is None:failures.append('post-reap port ownership lookup unavailable')
    for entry in entries:
        pid=int(entry['pid'])
        if pid in killed_pids and alive is not None and pid in alive:
            failures.append('pid %d still alive after reap'%pid)
        port=entry.get('port')
        if pid in killed_pids and entry.get('role')=='engine' and owners_after is not None and port in owners_after:
            failures.append('claimed engine port %d still listening (pid %s)'%(port,owners_after[port]))
    report['status']='PASS' if not failures else 'FAIL'
    report['verified']=failures or ['every claimed pid is gone and every claimed engine port is released']
    write_json(claims_path,claims)
    write_json(run/'reap-report.json',report)
    print(json.dumps({'reap':str(run),'status':report['status'],'killed':len(report['killed']),
                      'skipped':len(report['skipped']),'verified':report['verified']},ensure_ascii=False),flush=True)
    return 0 if not failures else 1

class ParallelPair:
    """Exactly two episodes; proof is sampled while both engines wait at the ready barrier."""
    def __init__(self,run):
        self.run=run
        self.lock=threading.Lock()
        self.ports=set() # Keep every port used in this batch, including after a failed episode.
        self.ready={}
        self.engines={}
        self.clients={}
        self.cancelled=threading.Event()
        self.proof={'schemaVersion':1,'status':'PENDING'}
        self.barrier=threading.Barrier(2,action=self.prove_overlap)

    def reserve_port(self):
        with self.lock:
            for _ in range(64):
                with socket.socket() as reservation:
                    reservation.bind(('127.0.0.1',0))
                    port=reservation.getsockname()[1]
                if port not in self.ports:
                    self.ports.add(port)
                    return port
        raise RuntimeError('Could not reserve a distinct port for the pair')

    def register_engine(self,number,work,proc):
        with self.lock:self.engines[number]=(work,proc)

    def register_client(self,number,proc):
        with self.lock:self.clients[number]=proc

    def clear_client(self,number,proc):
        with self.lock:
            if self.clients.get(number) is proc:self.clients.pop(number,None)

    def cancel(self):
        """Release the ready barrier and ask only this batch's engines to stop."""
        self.cancelled.set()
        self.barrier.abort()
        with self.lock:engines=list(self.engines.values())
        for work,proc in engines:
            if proc.poll() is None:
                try:(work/'stop.request').touch()
                except OSError:pass

    def force_stop(self):
        """Last resort for a worker that did not finish after cancellation."""
        with self.lock:
            children=list(self.clients.values())+[proc for _,proc in self.engines.values()]
        for proc in children:
            if proc.poll() is None:
                try:proc.kill()
                except OSError:pass

    def wait_for_peer(self,number,work,port,proc,session,agent_sha,game_sha):
        with self.lock:
            self.ready[number]={'episode':number,'work':work,'port':port,'proc':proc,
                                'sessionId':session,'agentJarSha256':agent_sha,'gameJarSha256':game_sha}
        try:self.barrier.wait(timeout=45)
        except threading.BrokenBarrierError:
            if self.proof.get('reason','').startswith('episode '):return False
            raise RuntimeError('Parallel proof barrier failed: '+self.proof.get('reason','peer did not become ready'))
        if self.proof['status']!='PASS':raise RuntimeError('Parallel ownership proof failed: '+self.proof.get('reason','unknown'))
        return True

    def mark_failed(self,number,error):
        proof_to_write=None
        with self.lock:
            if self.proof['status']=='PENDING':
                self.proof={'schemaVersion':1,'status':'FAIL','reason':'episode %s failed before pair proof: %s'%(number,error)}
                proof_to_write=self.proof
        try:
            if proof_to_write is not None:write_json(self.run/'parallel-proof.json',proof_to_write)
        finally:self.barrier.abort()

    def prove_overlap(self):
        """Called once by the barrier before either client starts ordering."""
        proof={'schemaVersion':1,'status':'FAIL','sampleStartedUtc':datetime.now(timezone.utc).isoformat()}
        try:
            pair=[self.ready[1],self.ready[2]]
            for key in ('port','sessionId'):
                if pair[0][key]==pair[1][key]:raise RuntimeError('Episodes share '+key)
            for key in ('work','proc'):
                if pair[0][key]==pair[1][key]:raise RuntimeError('Episodes share '+key)
            first=[]
            for entry in pair:
                if entry['proc'].poll() is not None:raise RuntimeError('Engine exited before overlap sample')
                health=get(entry['port'],'/health');state=get(entry['port'],'/state')
                verify_owner(health,state,entry['port'],entry['work'],entry['agentJarSha256'],entry['gameJarSha256'])
                if state['sessionId']!=entry['sessionId']:raise RuntimeError('Session changed before overlap sample')
                first.append(state)
            time.sleep(.4)
            instances=[]
            for entry,before in zip(pair,first):
                if entry['proc'].poll() is not None:raise RuntimeError('Engine exited during overlap sample')
                after=verify_live_owner(entry['proc'],entry['work'],entry['port'],entry['sessionId'],
                                        entry['agentJarSha256'],entry['gameJarSha256'])
                if after.get('sessionId')!=entry['sessionId'] or after.get('frame',-1)<=before.get('frame',-1):
                    raise RuntimeError('Episode %s did not advance in its own session'%entry['episode'])
                work=entry['work']
                instances.append({'episode':entry['episode'],'enginePid':entry['proc'].pid,
                                  'port':entry['port'],'sessionId':entry['sessionId'],
                                  'workDirectory':str(work),'reportDirectory':str(work/'rw-agent-reports'),
                                  'lockPath':str(work/'rw-agent-reports'/'economy.lock'),
                                  'frameBefore':before['frame'],'frameAfter':after['frame']})
            proof.update(status='PASS',sampleFinishedUtc=datetime.now(timezone.utc).isoformat(),instances=instances)
        except Exception as error:
            proof.update(reason=str(error),sampleFinishedUtc=datetime.now(timezone.utc).isoformat())
        try:write_json(self.run/'parallel-proof.json',proof)
        except Exception as error:
            self.proof={'schemaVersion':1,'status':'FAIL','reason':'Could not persist parallel proof: '+str(error)}
            raise
        self.proof=proof

def stop(proc, work):
    if proc.poll() is not None:return
    try:(work/'stop.request').touch()
    except OSError:pass # A broken work directory must not prevent process cleanup.
    try:proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.terminate()
        try:proc.wait(timeout=5)
        except subprocess.TimeoutExpired:proc.kill();proc.wait()

def run_client(cmd,work,timeout,log_path,pair,number,port=None,claims=None):
    """Pair mode polls for cancellation; every client is reaped before return."""
    if pair is None:return subprocess.run(cmd,cwd=work,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
    child=subprocess.Popen(cmd,cwd=work,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    try:
        pair.register_client(number,child)
        if claims is not None:claims.add('client',child.pid,port=port,work=work,episode=number,marker=CLIENT_MARKER)
        deadline=time.monotonic()+timeout
        while True:
            if pair.cancelled.is_set():raise RuntimeError('Parallel batch interrupted')
            left=deadline-time.monotonic()
            if left<=0:raise subprocess.TimeoutExpired(cmd,timeout)
            try:
                output,_=child.communicate(timeout=min(.5,left))
                return subprocess.CompletedProcess(cmd,child.returncode,output)
            except subprocess.TimeoutExpired:continue
    except (Exception,KeyboardInterrupt) as error:
        if child.poll() is None:child.kill()
        output,_=child.communicate()
        write_bytes(log_path,output or b'')
        if isinstance(error,subprocess.TimeoutExpired):
            raise subprocess.TimeoutExpired(cmd,timeout,output=output)
        raise
    finally:
        pair.clear_client(number,child)
        if claims is not None:claims.exited(child.pid,child.returncode)

def run_episode(args, game, jar, java, work, number, pair=None, claims=None):
    cp=os.pathsep.join(('game-lib.jar','libs/*','rw-agent-bootstrap.jar'))
    speed=0 if args.mode=='benchmark' else args.speed
    frames=args.frames if args.mode=='benchmark' else 0
    record={'episode':number,'mode':args.mode,'map':args.map,'status':'RUNNING','phases':[],
            'pollWallTimeMs':getattr(args,'poll_ms',500),'battleSafetyGameSeconds':getattr(args,'battle_safety_seconds',7200),
            'startedUtc':datetime.now(timezone.utc).isoformat(),'workDirectory':work.name,
            'workDirectoryAbsolute':str(work)}
    loaded=getattr(args,'ab_profiles',None)
    profile=order_profiles(loaded,args.profile_order)[number-1] if loaded else None
    if profile:
        record['abProfile']={**profile,'slot':args.profile_order[number-1],'actualJvmArgs':[]}
        record['fixedConditions']=args.fixed_conditions
    start=time.monotonic();proc=None;port=None
    try:
        stage(game,jar,work,allow_junction=getattr(args,'junction_dirs',False))
        if pair and pair.cancelled.is_set():raise RuntimeError('Parallel batch interrupted during staging')
        if pair:port=pair.reserve_port()
        else:
            with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
        record.update(gameJarSha256=sha(game/'game-lib.jar'),agentJarSha256=sha(jar),port=port)
        if profile and (record['gameJarSha256']!=args.fixed_conditions['gameJarSha256']
                        or record['agentJarSha256']!=args.fixed_conditions['agentJarSha256']
                        or jar_content_digest(work/'rw-agent-bootstrap.jar')!=args.fixed_conditions['agentContentDigest']):
            raise RuntimeError('Staged candidate or game identity changed before episode start')
        command=[java,'-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-cp',cp,'io.rwagent.headless.HeadlessRunner',
                 '--map',args.map,'--port',str(port),'--speed',str(speed),'--max-wall-seconds',str(args.timeout),
                 '--frames',str(frames),'--difficulty',str(args.difficulty)]
        with (work/'engine.log').open('w',encoding='utf-8') as log:
            proc=subprocess.Popen(command,cwd=work,stdout=log,stderr=subprocess.STDOUT)
            record['enginePid']=proc.pid
            if pair:pair.register_engine(number,work,proc)
            if claims is not None:claims.add('engine',proc.pid,port=port,work=work,episode=number,
                                             marker=ENGINE_MARKER)
            write_json(work/'engine-process.json',{'pid':proc.pid,'port':port,'startedUtc':record['startedUtc'],
                                                   'workDirectory':str(work),'command':command})
            if args.mode=='benchmark':
                proc.wait(timeout=args.timeout+45)
                if proc.returncode:raise RuntimeError('Engine exit %s; see engine.log'%proc.returncode)
                runtime=json.loads((work/'runtime.json').read_text())
                if runtime['status']!='FRAME_LIMIT' or runtime['frame']<args.frames:raise RuntimeError('Benchmark did not complete its frame target')
            else:
                ready_by=time.monotonic()+45
                while True:
                    if pair and pair.cancelled.is_set():raise RuntimeError('Parallel batch interrupted during startup')
                    if proc.poll() is not None:raise RuntimeError('Engine exited during startup; see engine.log')
                    text=(work/'engine.log').read_text(encoding='utf-8',errors='replace')
                    if 'RW_HEADLESS_READY ' in text:
                        try:
                            health=get(port,'/health');state=get(port,'/state')
                            if health.get('version')=='0.07-alpha1' and state.get('status')=='running':
                                if pair:verify_owner(health,state,port,work,record['agentJarSha256'],record['gameJarSha256'])
                                break
                        except (OSError,ValueError):pass
                    if time.monotonic()>ready_by:raise TimeoutError('Engine startup deadline')
                    time.sleep(.1)
                write_json(work/'initial-state.json',state)
                record['sessionId']=state['sessionId']
                record['readyUtc']=datetime.now(timezone.utc).isoformat()
                record['reportDirectory']=str(work/'rw-agent-reports')
                record['lockPath']=str(work/'rw-agent-reports'/'economy.lock')
                if pair:
                    record['pairReady']=pair.wait_for_peer(number,work,port,proc,record['sessionId'],
                                                          record['agentJarSha256'],record['gameJarSha256'])
                    verify_live_owner(proc,work,port,record['sessionId'],record['agentJarSha256'],record['gameJarSha256'])
                phases=[('preflight','PreflightClient',[])]
                if args.mode=='smoke':phases += [('roundtrip','ControlLoop',['roundtrip'])]
                elif args.mode=='opening':phases += [('opening','OpeningClient',[])]
                elif args.mode=='development':phases += [('economy','EconomyClient',[]),('development','DevelopmentClient',[str(args.tanks),str(args.mines)])]
                elif args.mode=='frontier':phases += [('economy','EconomyClient',[]),('frontier','FrontierClient',[str(args.tanks),str(args.mines),str(args.scout_moves)])]
                elif args.mode=='autopilot':phases += [('autopilot','AutopilotClient',[str(args.tanks),str(args.mines),str(args.scout_moves)])]
                elif args.mode=='match':phases += [('match','MatchClient',[str(args.battle_seconds)])]
                elif args.mode=='suite':phases += [('roundtrip','ControlLoop',['roundtrip']),('opening','OpeningClient',[]),('development','DevelopmentClient',[str(args.tanks),str(args.mines)])]
                for label,client,arguments in phases:
                    if pair and pair.cancelled.is_set():raise RuntimeError('Parallel batch interrupted before '+label)
                    left=args.timeout-(time.monotonic()-start)
                    if left<=0:raise TimeoutError('Episode wall deadline')
                    if pair:verify_live_owner(proc,work,port,record['sessionId'],record['agentJarSha256'],record['gameJarSha256'])
                    cmd=[java,'-Dfile.encoding=UTF-8','-Drwagent.port='+str(port),'-cp','rw-agent-bootstrap.jar','io.rwagent.client.'+client]+arguments
                    if client in ('FrontierClient','AutopilotClient','MatchClient','BattleClient'):
                        cmd.insert(1,'-Drwagent.pollMs='+str(getattr(args,'poll_ms',500)))
                    if client in ('MatchClient','BattleClient'):
                        cmd[1:1]=['-Drwagent.battleSafetyGameSeconds='+str(getattr(args,'battle_safety_seconds',7200)),
                                  '-Drwagent.battleSafetyWallSeconds='+str(args.timeout)]
                    if profile:
                        cmd[1:1]=['-D%s=%s'%(key,value) for key,value in sorted(profile['jvmProperties'].items())]
                        if label=='match':record['abProfile']['actualJvmArgs']=[part for part in cmd if part.startswith('-D')]
                    before_reports=set((work/'rw-agent-reports').glob('*.jsonl'))
                    phase_start=time.monotonic()
                    try:
                        completed=run_client(cmd,work,left,work/(label+'.log'),pair,number,port,claims)
                        write_bytes(work/(label+'.log'),completed.stdout)
                    except subprocess.TimeoutExpired as error:
                        write_bytes(work/(label+'.log'),error.stdout or b'');raise
                    phase_record={'name':label,'exitCode':completed.returncode,'wallSeconds':round(time.monotonic()-phase_start,3)}
                    if profile:phase_record['clientJvmArgs']=[part for part in cmd if part.startswith('-D')]
                    record['phases'].append(phase_record)
                    if label!='preflight':
                        new_reports=set((work/'rw-agent-reports').glob('*.jsonl'))-before_reports
                        expected=3 if label=='match' else 2 if label=='autopilot' else 1
                        # Preserve every committed report even if an earlier child stopped the workflow.
                        verified=[]
                        # Capture each completed raw report in the same process that verifies it.
                        evidence_path=work/(label+'-evidence.zip')
                        with zipfile.ZipFile(evidence_path,'w',zipfile.ZIP_DEFLATED) as evidence:
                            for report_path in sorted(new_reports):
                                raw=report_path.read_bytes();evidence.writestr(report_path.name,raw)
                                report=analyze_file(report_path);verified.append(report)
                                if profile and report.get('task')=='battle':
                                    config=battle_config(report_path)
                                    record['phases'][-1]['battleConfig']=config
                                    if config.get('mobileUnitHardCap')!=profile['jvmProperties']['rwagent.mobileUnitHardCap'] or config.get('gameSeconds')!=args.battle_seconds:
                                        raise RuntimeError('Battle report did not receive the requested profile and time limit')
                                if pair:
                                    sessions=report_sessions(report_path)
                                    if sessions!={record['sessionId']}:
                                        raise RuntimeError(label+' report belongs to sessions '+repr(sorted(sessions)))
                                    record['phases'][-1].setdefault('reportEvidence',[]).append(
                                        {'path':str(report_path.resolve()),'sha256':sha(report_path),'sessionId':record['sessionId']})
                            report=verified[0] if len(verified)==1 else {'schemaVersion':1,'reports':verified}
                            evidence.writestr('verification.json',json.dumps(report,ensure_ascii=False,indent=2))
                        with evidence_path.open('r+b') as evidence_file:os.fsync(evidence_file.fileno())
                        record['phases'][-1]['verifiedReport' if len(verified)==1 else 'verifiedReports']=report
                        write_json(work/(label+'-verified.json'),report)
                        if label=='match':
                            validate_match_report_tasks([item.get('task') for item in verified])
                        elif len(new_reports)!=expected:raise RuntimeError(label+' produced '+str(len(new_reports))+' / '+str(expected)+' expected reports; partial workflow evidence archived')
                        for report in verified:
                            if report['result']!='PASS':raise RuntimeError(label+' '+report['result']+': '+str(report['reason'])+'; integrity issues: '+str(report['issues']))
                    if completed.returncode:raise RuntimeError(label+' failed; see '+label+'.log and rw-agent-reports')
                final=verify_live_owner(proc,work,port,record['sessionId'],
                                        record['agentJarSha256'],record['gameJarSha256']) if pair else get(port,'/state')
                if final['sessionId']!=record['sessionId'] or final['frame']<=state['frame']:raise RuntimeError('Session reset or native simulation did not advance')
                record['finalFrame']=final['frame']
                write_json(work/'final-state.json',final)
                write_json(work/'final-preflight.json',get(port,'/economy/preflight'))
                stop(proc,work)
                if proc.returncode:raise RuntimeError('Engine failed at shutdown')
            record['status']='PASS'
    except (Exception,KeyboardInterrupt) as error:
        record['status']='FAIL';record['error']=str(error)
        if isinstance(error,KeyboardInterrupt):record['interrupted']=True
        if pair:
            try:pair.mark_failed(number,error)
            except Exception as note_error:record['pairNoteError']=str(note_error)
    finally:
        cleanup_errors=[]
        if proc is not None:
            try:stop(proc,work)
            except Exception as error:cleanup_errors.append('engine stop: '+str(error))
            record['engineExitCode']=proc.poll()
            record['engineAliveAfterCleanup']=proc.poll() is None
            if claims is not None:claims.exited(proc.pid,proc.poll())
        if (work/'engine.log').exists():
            try:write_bytes(work/'engine.log',(work/'engine.log').read_bytes())
            except Exception as error:cleanup_errors.append('engine log commit: '+str(error))
        if (work/'runtime.json').exists():
            try:record['runtime']=json.loads((work/'runtime.json').read_text(encoding='utf-8'))
            except Exception as error:cleanup_errors.append('runtime read: '+str(error))
        if profile:
            runtime=record.get('runtime')
            expected_runtime={'map':args.map,'aiDifficulty':args.difficulty,
                              'requestedSpeed':args.speed,'fogEnabled':True,
                              'lineOfSightFog':True,'seedReproducibilityVerified':False}
            if not isinstance(runtime,dict) or any(runtime.get(key)!=value for key,value in expected_runtime.items()) or runtime.get('seed') is None:
                cleanup_errors.append('profile fixed-condition runtime evidence missing or mismatched')
        if cleanup_errors:
            record['status']='FAIL';record['cleanupErrors']=cleanup_errors
            if pair:
                try:pair.mark_failed(number,'; '.join(cleanup_errors))
                except Exception:pass
        record['wallSeconds']=round(time.monotonic()-start,3)
        record['finishedUtc']=datetime.now(timezone.utc).isoformat()
        if args.mode=='match':
            candidates=[]
            for phase in record['phases']:
                wrapped=phase.get('verifiedReports',{})
                candidates.extend(wrapped.get('reports',[]) if isinstance(wrapped,dict) else [])
            battle=next((r for r in candidates if r.get('task')=='battle'),None)
            record['matchOutcome']=('INVALID' if battle.get('issues') else battle.get('summary',{}).get('matchOutcome','UNKNOWN')) if battle else 'NO_REPORT'
            record['matchCompleted']=bool(record['status']=='PASS' and battle and battle.get('result')=='PASS' and record['matchOutcome'] in ('VICTORY','DEFEAT'))
        try:
            work.mkdir(parents=True,exist_ok=True)
            write_json(work/'episode.json',record)
        except Exception as error:
            record['status']='FAIL';record['recordError']=str(error)
    print(json.dumps({'episode':number,'status':record['status'],'wallSeconds':record['wallSeconds'],'error':record.get('error'),'matchOutcome':record.get('matchOutcome')},ensure_ascii=False),flush=True)
    return record

def batch_payload(args,records,pair=None):
    ordered=sorted(records,key=lambda row:row['episode'])
    payload={'schemaVersion':1,'mode':args.mode,'episodes':ordered,'completed':len(ordered),
             'passed':sum(r['status']=='PASS' for r in ordered),
             'matchResults':{outcome:sum(r.get('matchOutcome')==outcome for r in ordered)
                             for outcome in ('VICTORY','DEFEAT','ONGOING','INVALID','NO_REPORT')},
             'completedMatches':sum(r.get('matchCompleted',False) for r in ordered),
             'note':'PASS means task completion. In match mode, inspect battle matchOutcome: VICTORY, DEFEAT or ONGOING. Neither is a trained agent.'}
    if pair:
        payload['parallelPair']=True
        payload['parallelProof']=pair.proof
    if getattr(args,'ab_profiles',None):
        payload['abProfiles']={**args.ab_profiles,'profileOrder':args.profile_order}
        payload['fixedConditions']=args.fixed_conditions
    return payload

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--game-dir',type=Path,help='Game directory (not needed with --reap)')
    p.add_argument('--agent-jar',type=Path,default=Path(__file__).resolve().parents[1]/'rw-agent-bootstrap.jar')
    p.add_argument('--java',help='Java executable; defaults to bundled Windows Java or PATH java')
    p.add_argument('--mode',choices=('smoke','opening','development','frontier','autopilot','match','suite','benchmark'),default='suite')
    p.add_argument('--episodes',type=int,default=1)
    p.add_argument('--parallel-pair',action='store_true',help='Run exactly two isolated episodes concurrently and prove overlap')
    p.add_argument('--profiles',type=Path,help='Strict A/B experiment profiles (match parallel pair only)')
    p.add_argument('--profile-order',choices=('AB','BA'),default='AB',help='Assign A/B to episode 1/2')
    p.add_argument('--junction-dirs',action='store_true',help='Use Windows junctions for directory staging; use only with a disposable game resource cache')
    p.add_argument('--speed',type=float,default=4)
    p.add_argument('--timeout',type=int,help='Wall seconds; default 1200 for match, 180 otherwise')
    p.add_argument('--frames',type=int,default=6000)
    p.add_argument('--map',default=DEFAULT_MAP)
    p.add_argument('--difficulty',type=int,default=0)
    p.add_argument('--tanks',type=int,default=8);p.add_argument('--mines',type=int,default=3)
    p.add_argument('--battle-seconds',type=int,default=900)
    p.add_argument('--battle-safety-seconds',type=int,default=7200,help='Explicit game-time safety ceiling, at most 21600')
    p.add_argument('--poll-ms',type=int,default=500,help='Wall-clock observation interval, independent of simulation speed (60..1000)')
    p.add_argument('--scout-moves',type=int,default=24)
    p.add_argument('--out',type=Path,default=Path('headless-runs'))
    p.add_argument('--reap',type=Path,metavar='RUN_DIR',
                   help='Kill only the processes registered in RUN_DIR/live-claims.json (idempotent)')
    args=p.parse_args(argv)
    if args.reap is not None:return reap_run(args.reap)
    if args.game_dir is None:p.error('--game-dir is required unless --reap is used')
    if args.timeout is None:args.timeout=1200 if args.mode=='match' else 180
    if not (1<=args.episodes<=1000 and 0<args.speed<=8 and 1<=args.timeout<=3600 and 1<=args.tanks<=30 and 0<=args.mines<=10 and 1<=args.scout_moves<=48 and args.frames>0 and -2<=args.difficulty<=3 and 1800<=args.battle_safety_seconds<=21600 and 120<=args.battle_seconds<=args.battle_safety_seconds and 60<=args.poll_ms<=1000):p.error('Invalid experiment bounds')
    if args.parallel_pair and (args.episodes!=2 or args.mode=='benchmark'):
        p.error('--parallel-pair requires --episodes 2 and a session/report-producing mode')
    if args.profiles and (not args.parallel_pair or args.mode!='match'):
        p.error('--profiles requires --mode match --episodes 2 --parallel-pair')
    if not args.profiles and args.profile_order!='AB':
        p.error('--profile-order requires --profiles')
    args.ab_profiles=None
    if args.profiles:
        try:args.ab_profiles=load_ab_profiles(args.profiles)
        except (OSError,UnicodeError,ValueError) as error:p.error('Invalid A/B profiles: '+str(error))
    game=args.game_dir.resolve();jar=args.agent_jar.resolve()
    if not jar.is_file() or not (game/'game-lib.jar').is_file():p.error('Game or Agent JAR missing')
    if sha(game/'game-lib.jar')!=EXPECTED_GAME:p.error('Unsupported game-lib.jar fingerprint')
    if args.ab_profiles:
        try:content_digest=jar_content_digest(jar)
        except (OSError,zipfile.BadZipFile,ValueError) as error:p.error('Cannot identify Agent JAR content: '+str(error))
        args.fixed_conditions={'agentContentDigest':content_digest,'agentJarSha256':sha(jar),
            'gameJarSha256':sha(game/'game-lib.jar'),'map':args.map,'aiDifficulty':args.difficulty,
            'requestedSpeed':args.speed,'timeoutSeconds':args.timeout,'battleSeconds':args.battle_seconds,
            'fog':{'fogEnabled':True,'lineOfSightFog':True}}
    java=args.java
    if not java:
        bundled=game/'jvm64/bin/java.exe';java=str(bundled) if os.name=='nt' and bundled.exists() else shutil.which('java')
    if not java:p.error('Java executable unavailable; specify --java')
    java=str(Path(java).resolve()) if Path(java).exists() else java
    run=args.out.resolve()/('run-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')+'-'+os.urandom(3).hex());run.mkdir(parents=True)
    claims=LiveClaims(run)
    claims.add('orchestrator',os.getpid(),marker='run_headless.py')
    records=[]
    pair=ParallelPair(run) if args.parallel_pair else None
    if pair:
        interrupted=False
        with ThreadPoolExecutor(max_workers=2) as executor:
            pending={executor.submit(run_episode,args,game,jar,java,run/('episode-%03d'%number),number,pair,claims):number
                     for number in (1,2)}
            try:
                for future in as_completed(pending):
                    number=pending[future]
                    try:record=future.result()
                    except Exception as error:
                        pair.mark_failed(number,error)
                        record={'episode':number,'status':'FAIL','error':'Uncaught worker error: '+str(error)}
                    records.append(record)
                    write_json(run/'batch.json',batch_payload(args,records,pair))
            except KeyboardInterrupt:
                interrupted=True;pair.cancel()
                for future,number in pending.items():
                    if any(row['episode']==number for row in records):continue
                    try:record=future.result(timeout=20)
                    except FutureTimeout:
                        pair.force_stop()
                        try:record=future.result(timeout=20)
                        except Exception as error:record={'episode':number,'status':'FAIL','error':'Interrupted worker: '+str(error)}
                    except Exception as error:record={'episode':number,'status':'FAIL','error':'Interrupted worker: '+str(error)}
                    records.append(record)
                    write_json(run/'batch.json',batch_payload(args,records,pair))
        if interrupted:
            payload=batch_payload(args,records,pair);payload['interrupted']=True
            write_json(run/'batch.json',payload)
    else:
        for number in range(1,args.episodes+1):
            record=run_episode(args,game,jar,java,run/('episode-%03d'%number),number,None,claims);records.append(record)
            write_json(run/'batch.json',batch_payload(args,records))
            if record.get('interrupted'):break
    running={pid:entry for pid,entry in claims.processes.items() if entry.get('state')=='RUNNING'}
    if running:
        infos=process_info(list(running))
        owners=port_owners([entry.get('port') for entry in running.values()])
        for pid,entry in (running.items() if infos is not None and owners is not None else []):
            # Gone, or this pid now belongs to something that is not our child (pid reuse): either way
            # the batch must not leave a stale RUNNING claim behind.
            if pid not in infos or not claims_identity(entry,infos[pid],owners.get(entry.get('port')))[0]:
                claims.exited(pid,entry.get('exitCode'))
    print('Results: '+str(run),flush=True)
    return 0 if len(records)==args.episodes and all(r['status']=='PASS' for r in records) and (not pair or (not interrupted and pair.proof['status']=='PASS')) else 1

if __name__=='__main__':raise SystemExit(main())
