#!/usr/bin/env python3
"""Offline RW Agent JSONL analysis; Python 3.9+, standard library only.
Never connects to the game or changes input files. An anomalous report is not PASS.
"""
import argparse
import collections
import csv
import hashlib
import json
import math
from pathlib import Path
from urllib.parse import urlsplit,parse_qs

KINDS = ('bootstrap', 'battle', 'frontier', 'development', 'opening', 'economy', 'roundtrip', 'record', 'move')
COMPLETED = {'extractor_completed': 'mines', 'factory_completed': 'factories', 'tank_completed': 'tanks'}

def numeric(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)

def task_kind(path, rows):
    for k in KINDS:
        if path.name.startswith(k + '-'):
            return k
    kinds = {r['event'] for r in rows}
    if 'targets' in kinds or 'production_plan' in kinds or 'expansion_finished' in kinds:
        return 'development'
    if 'extractor_completed' in kinds or any('extractorType' in r['data'] for r in rows if r['event'] == 'plan'):
        return 'opening'
    if 'factory_completed' in kinds or any('factoryType' in r['data'] for r in rows if r['event'] == 'plan'):
        return 'economy'
    if 'arrival' in kinds:
        return 'roundtrip'
    return 'unknown'

def analyze_file(path, base=None):
    path = Path(path)
    raw = path.read_bytes()
    issues = []
    def issue(code, detail):
        issues.append({'code': code, 'detail': detail})
    if path.name.endswith(".partial"):
        issue("UNCOMMITTED_REPORT", "Client did not commit this report")
    rows = []
    lines = raw.splitlines()
    nonempty = [i for i, line in enumerate(lines, 1) if line.strip()]
    last_nonempty = nonempty[-1] if nonempty else 0
    for number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line.decode('utf-8-sig') if number == 1 else line.decode('utf-8'),
                             parse_constant=lambda x: (_ for _ in ()).throw(ValueError('nonfinite ' + x)))
        except (ValueError, UnicodeError) as exc:
            truncated = number == last_nonempty and not raw.endswith((b'\n', b'\r'))
            issue('TRUNCATED_JSONL' if truncated else 'BAD_JSON_LINE', 'line %d: %s' % (number, exc))
            continue
        if (not isinstance(row, dict) or not isinstance(row.get('event'), str)
                or not isinstance(row.get('data'), dict) or not numeric(row.get('wallTimeMs'))):
            issue('INVALID_EVENT_SCHEMA', 'line %d requires event, object data, finite wallTimeMs' % number)
            continue
        rows.append(row)
    kind = task_kind(path, rows)
    counts = collections.Counter(r['event'] for r in rows)
    summaries = [r for r in rows if r['event'] == 'summary']
    summary = summaries[-1]['data'] if summaries else {}
    if not summaries:
        issue('MISSING_SUMMARY', 'No terminal summary event')
    if len(summaries) > 1:
        issue('MULTIPLE_SUMMARIES', 'Found %d summaries' % len(summaries))
    if summaries and rows[-1]['event'] != 'summary':
        issue('EVENTS_AFTER_SUMMARY', 'Events exist after terminal summary')
    if summary and not isinstance(summary.get('outcome'), str):
        issue('INVALID_SUMMARY', 'Missing outcome')
    times = [r['wallTimeMs'] for r in rows]
    if any(b < a for a, b in zip(times, times[1:])):
        issue('NONMONOTONIC_WALL_TIME', 'System clock moved backwards')
    observed = {k: set() for k in COMPLETED.values()}
    starts = {}; intervals = []; tanks = []; identities = {}
    initial_units = None
    for r in rows:
        event, data, timestamp = r['event'], r['data'], r['wallTimeMs']
        if event == 'observation' and initial_units is None:
            units = data.get('ownUnits', [])
            if isinstance(units, list):
                initial_units = {u.get('id') for u in units if isinstance(u, dict) and isinstance(u.get('id'), int)}
        if event == 'extractor_started':
            uid = data.get('unitId')
            if not isinstance(uid, int) or isinstance(uid, bool):
                issue('INVALID_UNIT_ID', 'extractor_started requires integer unitId'); continue
            if uid in starts:
                issue('DUPLICATE_START_ID', str(uid))
            starts[uid] = timestamp
        if event not in COMPLETED:
            continue
        uid = data.get('unitId')
        if not isinstance(uid, int) or isinstance(uid, bool):
            issue('INVALID_UNIT_ID', event + ' requires integer unitId'); continue
        category = COMPLETED[event]
        if uid in identities:
            issue('DUPLICATE_COMPLETION_ID', '%s %s already completed as %s' % (event, uid, identities[uid]))
            continue
        identities[uid] = category
        observed[category].add(uid)
        if initial_units is not None and uid in initial_units:
            issue('PREEXISTING_COMPLETION_ID', '%s was present at first observation' % uid)
        if event == 'tank_completed':
            tanks.append((uid, timestamp))
        elif event == 'extractor_completed':
            if uid not in starts:
                issue('MISSING_EXTRACTOR_START', str(uid))
            else:
                start = starts.pop(uid)
                intervals.append({'unitId': uid, 'startWallTimeMs': start, 'endWallTimeMs': timestamp})
    actual = {k: len(v) for k, v in observed.items()}
    observed_commands = sum(1 for r in rows if r['event'] == 'command_result' and r['data'].get('status') == 'queued')
    comparisons = {'commands': observed_commands, 'observations': counts['observation'],
                   'completedMines': actual['mines'], 'completedTanks': actual['tanks'],
                   'completedFactories': actual['factories'], 'completedBuildings': actual['mines'] + actual['factories']}
    for name, expected in comparisons.items():
        if name in summary and (not numeric(summary[name]) or summary[name] != expected):
            issue('COUNT_MISMATCH', '%s: summary=%r events=%r' % (name, summary[name], expected))
    if kind in ('development','frontier') and summary:
        for name in ('commands', 'observations', 'completedMines', 'completedTanks', 'expansionStatus'):
            if name not in summary:
                issue('MISSING_SUMMARY_FIELD', name)
    scout_moves={};scout_arrivals=set();discoveries=set();initial_resources=None;visibility=None;latest_state=None
    explored=[];scouted_mines=0;targets={};assigned={};confirmed=set();last_order=None;pending_order=None
    for row in rows:
        event,data=row['event'],row['data']
        if event=='targets':targets=data
        if event=='action':pending_order=urlsplit(data.get('path',''))
        if event=='command_result' and data.get('status')=='queued':last_order=pending_order;pending_order=None
        if event=='observation':latest_state=data
        if event=='scout_visibility':
            visibility=data
            if numeric(data.get('exploredTiles')):explored.append(data['exploredTiles'])
            if initial_resources is None:initial_resources={s.get('tile') for s in data.get('resources',[])}
        if event=='scout_started':
            move=data.get('move')
            if move in scout_moves:issue('DUPLICATE_SCOUT_MOVE',str(move))
            scout_moves[move]=data
            if last_order is None or last_order.path!='/command/move':issue('SCOUT_MOVE_NOT_QUEUED',str(move))
        if event=='scout_arrived':
            move=data.get('move');start=scout_moves.get(move)
            if move in scout_arrivals or start is None:issue('INVALID_SCOUT_ARRIVAL',str(move))
            scout_arrivals.add(move)
            unit=next((u for u in (latest_state or {}).get('ownUnits',[]) if u.get('id')==data.get('unitId')),None)
            if start and unit and all(numeric(v) for v in [unit.get('x'),unit.get('y'),start.get('startX'),start.get('startY'),start.get('targetX'),start.get('targetY')]):
                displacement=math.hypot(unit['x']-start['startX'],unit['y']-start['startY'])
                distance=math.hypot(unit['x']-start['targetX'],unit['y']-start['targetY'])
                if displacement<60 or distance>30.01:issue('SCOUT_ARRIVAL_NOT_OBSERVED',str(move))
            else:issue('MISSING_SCOUT_OBSERVATION',str(move))
        if event=='scout_discovery':
            tile=data.get('resourceTile');start=scout_moves.get(data.get('scoutMove'))
            site=next((s for s in (visibility or {}).get('resources',[]) if s.get('tile')==tile),None)
            if (initial_resources is None or tile in initial_resources or tile in discoveries or start is None
                    or site is None or not site.get('currentlyVisible') or not numeric(start.get('frame'))
                    or not numeric(site.get('firstSeenFrame')) or site.get('firstSeenFrame',-1)<start['frame']):
                issue('UNSUPPORTED_SCOUT_DISCOVERY',str(tile))
            discoveries.add(tile)
        if event=='extractor_completed' and data.get('discoveredByScouting'):
            scouted_mines+=1
            if data.get('resourceTile') not in discoveries:issue('UNSUPPORTED_SCOUTED_MINE',str(data.get('unitId')))
        if event=='escort_assigned':
            uid=data.get('unitId');target=data.get('targetId')
            q=parse_qs(last_order.query) if last_order else {}
            if uid in assigned or last_order is None or last_order.path!='/command/guard' or q.get('unitId')!=[str(uid)] or q.get('targetId')!=[str(target)]:
                issue('ESCORT_NOT_QUEUED',str(uid))
            assigned[uid]=target
        if event=='escort_confirmed':
            uid=data.get('unitId');unit=next((u for u in (latest_state or {}).get('ownUnits',[]) if u.get('id')==uid),None)
            if uid in confirmed or uid not in assigned or unit is None or unit.get('orderType')!='guard' or unit.get('guardTargetId')!=assigned[uid]:
                issue('ESCORT_NOT_OBSERVED',str(uid))
            confirmed.add(uid)
    explored_gain=explored[-1]-explored[0] if explored else 0
    if any(b<a for a,b in zip(explored,explored[1:])):issue('EXPLORATION_MEMORY_RESET','exploredTiles decreased')
    if kind=='frontier':
        for name,expected in {'scoutMoves':len(scout_moves),'scoutArrivals':len(scout_arrivals),'scoutBlocked':counts['scout_blocked'],
                              'scoutedMines':scouted_mines,'newlyExploredTiles':explored_gain,'scoutRetreats':counts['scout_retreat'],
                              'escortsAssigned':len(assigned),'escortsConfirmed':len(confirmed)}.items():
            if summary.get(name)!=expected:issue('SCOUT_COUNT_MISMATCH',name)
        if summary.get('outcome')=='PASS':
            if actual['mines']!=targets.get('maxNewMines') or actual['tanks']!=targets.get('newTanks'):
                issue('FRONTIER_TARGET_NOT_MET','PASS requires both requested production and expansion targets')
    if kind=='battle':
        from battle_reports import validate_battle
        validate_battle(rows,summary,issue)
    if kind=='bootstrap':
        modes={'EXISTING_BUILDER','EXISTING_BUILDER_QUEUE','AUTOMATIC_NATIVE_PRODUCTION','UNDETERMINED'}
        if summary.get('bootstrapMode') not in modes:issue('BOOTSTRAP_MODE_UNKNOWN','Missing explicit startup conditions')
        commands=summary.get('automaticProductionCommands')
        if commands not in (0,1) or commands!=observed_commands:issue('BOOTSTRAP_COMMAND_COUNT','At most one accepted native production order')
        if summary.get('outcome')=='PASS':
            completed=[r['data'] for r in rows if r['event'] in ('bootstrap_existing_builder','bootstrap_builder_completed')]
            ready=[r['data'] for r in rows if r['event']=='bootstrap_ready']
            own=next((r['data'].get('ownUnits',[]) for r in reversed(rows) if r['event']=='observation'),[])
            unit=next((u for u in own if u.get('id')==summary.get('builderId')),None)
            if not completed or len(ready)!=1 or ready[0].get('builderId')!=summary.get('builderId'):
                issue('BOOTSTRAP_READY_EVIDENCE_MISSING','PASS requires observed available builder and fresh preflight')
            if (unit is None or unit.get('dead') or not numeric(unit.get('buildProgress')) or unit['buildProgress']<1
                    or not numeric(unit.get('hp')) or unit['hp']<=0
                    or not isinstance(summary.get('resolvedBuilderType'),str) or unit.get('type')!=summary['resolvedBuilderType']):
                issue('BOOTSTRAP_READY_UNIT_MISSING','Selected builder must remain complete in final own observation')
            final_preflight=next((r['data'] for r in reversed(rows) if r['event']=='preflight'),{})
            if (not final_preflight.get('commandsAllowed') or final_preflight.get('recommendation') not in ('RUN_DEVELOP','RUN_ECONOMY_OR_OPENING')
                    or not ready or ready[-1].get('recommendation')!=final_preflight.get('recommendation')):
                issue('BOOTSTRAP_READY_PREFLIGHT_INVALID','PASS requires a fresh allowed continuation preflight')
            evidence=summary.get('completionEvidence')
            if evidence=='NEW_OWN_UNIT_AND_NATIVE_QUEUE_CYCLE' and not summary.get('queueObserved'):
                issue('BOOTSTRAP_UNOBSERVED_QUEUE_CLAIM','Queue cycle evidence requires an observed native queue')
            if evidence=='NEW_READY_BUILDER_AFTER_ACCEPTED_ORDER' and (commands!=1 or summary.get('queueObserved')):
                issue('BOOTSTRAP_FAST_COMPLETION_EVIDENCE_INVALID','Fast completion requires one accepted order and an explicit unobserved queue')
            if summary.get('bootstrapMode')=='AUTOMATIC_NATIVE_PRODUCTION' and commands!=1:
                issue('BOOTSTRAP_AUTOMATIC_ORDER_MISSING','Automatic bootstrap requires one accepted order')
            if summary.get('bootstrapMode')=='EXISTING_BUILDER_QUEUE' and commands!=0:
                issue('BOOTSTRAP_DUPLICATE_EXISTING_QUEUE','Existing pending builder must not trigger a second order')
    overlaps = set()
    for interval in intervals:
        ids = [uid for uid, timestamp in tanks if interval['startWallTimeMs'] <= timestamp <= interval['endWallTimeMs']]
        interval['tankIdsCompletedDuringConstruction'] = ids
        overlaps.update(ids)
    if summary.get('outcome') == 'PASS' and starts:
        issue('INCOMPLETE_EXTRACTOR_INTERVAL', 'Unclosed extractor starts: %s' % sorted(starts))
    version = next((r['data'].get('version') for r in rows if r['event'] == 'health'), None)
    outcome = summary.get('outcome', 'UNKNOWN')
    result = 'INVALID' if issues else outcome
    classification = result
    if result == 'FAIL' and summary.get('phase') == 'setup' and summary.get('commands') == 0:
        if any(r['event'] == 'http_error' and r['data'].get('status') == 409 for r in rows):
            classification = 'PRECONDITION_REJECTED'
    display = path.relative_to(base).as_posix() if base else path.name
    return {'file': display, 'sha256': hashlib.sha256(raw).hexdigest(), 'version': version, 'task': kind,
            'result': result, 'classification': classification, 'reportedOutcome': outcome,
            'phase': summary.get('phase'), 'reason': summary.get('reason'),
            'wallDurationSeconds': round((times[-1] - times[0]) / 1000, 3) if times and times[-1] >= times[0] else None,
            'commands': summary.get('commands'), 'observations': summary.get('observations'),
            'eventCommands': observed_commands, 'eventObservations': counts['observation'],
            'newMines': actual['mines'], 'newFactories': actual['factories'], 'newTanks': actual['tanks'],
            'expansionStatus': summary.get('expansionStatus'), 'overlapTankCount': len(overlaps),
            'scoutMoves':len(scout_moves),'scoutArrivals':len(scout_arrivals),'scoutedMines':scouted_mines,'newlyExploredTiles':explored_gain,
            'scoutRetreats':counts['scout_retreat'],'escortsAssigned':len(assigned),'escortsConfirmed':len(confirmed),
            'extractorIntervals': intervals, 'unfinishedExtractorIds': sorted(starts),
            'issues': issues, 'summary': summary, 'allSummaries': [r['data'] for r in summaries],
            'eventCounts': dict(counts), 'validEventCount': len(rows), 'nonemptyLineCount': len(nonempty)}

def markdown(results):
    def cell(v):
        return str('—' if v is None else v).replace('|', '\\|').replace('\n', ' ').replace('\r', ' ')
    out = ['# 铁锈战争 Agent 离线报告汇总', '',
           '结果由事件核对。INVALID 表示报告不完整或计数异常，即使原始 outcome 为 PASS 也不计入通过。', '',
           '| 报告 | 版本 | 任务 | 判定 | 现实秒数 | 指令 | 观察 | 新矿 | 新厂 | 新坦克 | 建矿时完成坦克 |',
           '| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |']
    for r in results:
        fields = ['file', 'version', 'task', 'classification', 'wallDurationSeconds', 'commands', 'observations', 'newMines', 'newFactories', 'newTanks', 'overlapTankCount']
        out.append('| ' + ' | '.join(cell(r[f]) for f in fields) + ' |')
    out += ['', '现实耗时从首个有效事件到最后一个有效事件计算，不是游戏内时间。建矿出兵重叠仅指日志观察区间：同一矿的 extractor_started 到 extractor_completed 之间出现 tank_completed；不等于性能或胜率证明。', '']
    battle=[r for r in results if r['task']=='battle']
    if battle:
        out += ['| 对战报告 | 原生结算 | 对战游戏秒数 | 新观察战斗单位 | 己方损失 | 已确认进攻指令 | 工厂升级 |', '| --- | --- | ---: | ---: | ---: | ---: | ---: |']
        for r in battle:
            s=r['summary'];out.append('| '+cell(r['file'])+' | '+cell(s.get('matchOutcome'))+' | '+str(s.get('battleGameTimeMs',0)/1000)+' | '+' | '.join(cell(s.get(k)) for k in ('newCombatUnits','ownLosses','attackOrdersConfirmed','upgradesCompleted'))+' |')
        out += ['', '对战 PASS 只表示已观察到原生结算。VICTORY 才是获胜；DEFEAT 是完整败局；PARTIAL 是仍在进行的限时样本。', '']
    frontier=[r for r in results if r['task']=='frontier']
    if frontier:
        out += ['| 侦察报告 | 到达 / 移动 | 新观察地块 | 侦察发现并建成的矿 | 已确认护卫 | 撤回次数 |',
                '| --- | ---: | ---: | ---: | ---: | ---: |']
        for r in frontier:out.append('| '+cell(r['file'])+' | '+str(r['scoutArrivals'])+' / '+str(r['scoutMoves'])+' | '+
                                    ' | '.join(str(r[k]) for k in ('newlyExploredTiles','scoutedMines','escortsConfirmed','scoutRetreats'))+' |')
        out += ['', '侦察矿计数要求：初始观测中没有该矿；移动期间首次观察到该资源；随后出现新建矿的完成事件。护卫计数要求在单位实际指令中观察到 guard 和对应己方目标。PARTIAL 表示完整目标尚未达成。', '']
    for r in results:
        out += ['## ' + cell(r['file']), '', '**阶段** ' + cell(r['phase']) + '；**扩张** ' + cell(r['expansionStatus']), '', cell(r['reason']), '']
        if r['issues']:
            out += ['- ' + x['code'] + ': ' + cell(x['detail']) for x in r['issues']]
            out.append('')
        out += ['原始 summary', '', '```json', json.dumps(r['summary'], ensure_ascii=False, indent=2), '```', '']
    return '\n'.join(out)

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('--out', type=Path, default=Path('analysis'))
    parser.add_argument('--strict', action='store_true', help='exit 2 for INVALID reports (ordinary game FAIL is not a parsing error)')
    args = parser.parse_args(argv)
    root = args.input.resolve()
    if not root.exists():
        parser.error('Input does not exist')
    paths = [root] if root.is_file() else sorted(set(root.rglob('*.jsonl')) | set(root.rglob('*.jsonl.partial')))
    if not paths:
        parser.error('No .jsonl reports found')
    base = root.parent if root.is_file() else root
    results = [analyze_file(p, base) for p in paths]
    args.out.mkdir(parents=True, exist_ok=True)
    output_files = [args.out / ('reports.' + ext) for ext in ('json', 'csv', 'md')]
    if any(p.resolve() in paths for p in output_files):
        parser.error('Output would overwrite an input')
    output_files[0].write_text(json.dumps({'schemaVersion': 1, 'reports': results}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    fields = ['file', 'version', 'task', 'result', 'classification', 'reportedOutcome', 'phase', 'reason', 'wallDurationSeconds', 'commands', 'observations', 'newMines', 'newFactories', 'newTanks', 'expansionStatus', 'overlapTankCount', 'scoutMoves','scoutArrivals','newlyExploredTiles','scoutedMines','scoutRetreats','escortsAssigned','escortsConfirmed','issues', 'summary']
    with output_files[1].open('w', encoding='utf-8-sig', newline='') as f:
        writer = csv.DictWriter(f, fields); writer.writeheader()
        for r in results:
            row = {k: r[k] for k in fields}
            for k in ('issues', 'summary'):
                row[k] = json.dumps(row[k], ensure_ascii=False)
            writer.writerow(row)
    output_files[2].write_text(markdown(results), encoding='utf-8')
    print(json.dumps({'reports': len(results), 'results': dict(collections.Counter(r['classification'] for r in results)), 'output': str(args.out)}, ensure_ascii=False))
    return 2 if args.strict and any(r['issues'] for r in results) else 0

if __name__ == '__main__':
    raise SystemExit(main())
