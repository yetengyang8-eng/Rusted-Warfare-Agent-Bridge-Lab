#!/usr/bin/env python3
"""Audit the desktop oscillation and re-feed its fixed observations to real BattleClient.

E2 policy-only replay, never a native counterfactual trajectory or win-rate comparison.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'agent/tests'))
from target_policy_fixture import desktop_frames, run_frames

DESKTOP_SHA = '3d6ba9bd309e52eb55366509c46fedd780efcdb6658fd93e03b838028eb92cb3'


def summarize(rows):
    intents, switches, guards = Counter(), Counter(), Counter()
    reentries = []
    rejected = {}
    current = {}
    for line, row in enumerate(rows, 1):
        data = row['data']
        event = row['event']
        if event == 'combat_observation':
            current = data
        elif event == 'target_guard':
            guards[(data['targetId'], data['status'])] += 1
            if data['status'] == 'INCOMPATIBLE':
                rejected[data['targetId']] = {'line': line, 'evidence': data}
            elif data['status'] == 'COMPATIBLE':
                rejected.pop(data['targetId'], None)
        elif event == 'target_switch':
            switches[data['to']] += 1
        elif event == 'tactical_intent' and data.get('enemy'):
            enemy = data['enemy']
            target = enemy['id']
            intents[target] += 1
            if target in rejected and not any(x['id'] == target for x in current['visibleEnemies']):
                reentries.append(dict(line=line, targetId=target,
                    observationGameTimeMs=current['gameTimeMs'],
                    lastSeenGameTimeMs=enemy['lastSeenGameTimeMs'],
                    priorRejectionLine=rejected[target]['line'],
                    visibleAlternatives=[x['id'] for x in current['visibleEnemies']]))
    return dict(targetedIntents=dict(intents), switchesTo=dict(switches),
                guards=[dict(targetId=k[0], status=k[1], count=v) for k, v in sorted(guards.items())],
                rememberedReentriesAfterRejection=reentries,
                events=dict(Counter(r['event'] for r in rows)))


def audit_suppression(rows):
    """Check recorded evidence transitions and ownership; does not assign a native evidence grade."""
    active, current = {}, {}
    held = set()
    starts, violations, ownership = [], [], []
    fog, unknown = Counter(), Counter()
    for line, row in enumerate(rows, 1):
        data, event = row['data'], row['event']
        if event == 'combat_observation':
            current = data
            visible = {e['id'] for e in data['visibleEnemies']}
            for enemy in data['rememberedEnemies']:
                if enemy['id'] in active and enemy['id'] not in visible:
                    fog[enemy['id']] += 1
        elif event in ('target_suppression_started', 'target_suppression_released'):
            enemy = next((e for e in current.get('visibleEnemies', []) if e['id'] == data['targetId']), None)
            fresh = (enemy is not None and current.get('sessionId') == data.get('sessionId')
                     and enemy.get('domainObservedAtGameTimeMs') == data['observedAtGameTimeMs'] == current.get('gameTimeMs')
                     and enemy.get('lastSeenGameTimeMs') == current.get('gameTimeMs')
                     and current.get('catalogGameJarMatched') is True
                     and current.get('catalogSha256') == data.get('catalogSha256'))
            decisions = data.get('actorDecisions', [])
            if event == 'target_suppression_started':
                if not fresh or not decisions or not all(d['status'] == 'INCOMPATIBLE' for d in decisions):
                    violations.append(dict(line=line, reason='REJECTION_WITHOUT_CURRENT_NEGATIVE_EVIDENCE'))
                active[data['targetId']] = data
                starts.append({k: v for k, v in data.items() if k != 'actorDecisions'})
            else:
                if (not fresh or data['targetId'] not in active
                        or not any(d['status'] == 'COMPATIBLE' for d in decisions)
                        or data['observedAtGameTimeMs'] <= data['previousRejectionObservedAtGameTimeMs']):
                    violations.append(dict(line=line, reason='RELEASE_WITHOUT_NEW_COMPATIBLE_EVIDENCE'))
                active.pop(data['targetId'], None)
        elif event == 'target_guard' and data['targetId'] in active and data['status'] == 'UNKNOWN':
            unknown[data['targetId']] += 1
        elif event == 'tactical_intent' and data.get('enemy') and data['enemy']['id'] in active:
            violations.append(dict(line=line, reason='SUPPRESSED_TARGET_SELECTED'))
        if event == 'task_ownership_acquired':
            if data['taskId'] in held:
                ownership.append(dict(line=line, reason='DUPLICATE_ACQUIRE'))
            held.add(data['taskId'])
        elif event == 'task_ownership_released':
            if data['taskId'] not in held:
                ownership.append(dict(line=line, reason='UNMATCHED_RELEASE'))
            held.discard(data['taskId'])
    return dict(targetSuppressionStarts=starts, suppressedRememberedObservationCounts=dict(fog),
                suppressedUnknownGuardCounts=dict(unknown), targetEligibilityViolations=violations,
                ownershipIssues=ownership, ownershipHeldAtEnd=sorted(held),
                events=dict(Counter(r['event'] for r in rows)),
                battleSummary=rows[-1]['data'] if rows[-1]['event'] == 'summary' else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--archive', type=Path, default=ROOT/'evidence/astra-recon-desktop-acceptance-2026-09-29/desktop-raw-reports-2runs.zip')
    parser.add_argument('--audit-report', type=Path, help='Audit one completed report instead of replaying the desktop trace')
    args = parser.parse_args()
    if args.audit_report:
        raw = args.audit_report.read_bytes()
        rows = [json.loads(line) for line in raw.splitlines()]
        result = audit_suppression(rows)
        result.update(battleSha256=hashlib.sha256(raw).hexdigest(), rows=len(rows),
                      evidence='RECORDED_EVENT_AUDIT_SEE_RUN_PROVENANCE')
        args.out.mkdir(parents=True, exist_ok=True)
        (args.out/'summary.json').write_text(json.dumps(result, indent=2)+'\n')
        print(json.dumps({k: result[k] for k in ('battleSha256', 'targetEligibilityViolations', 'ownershipIssues', 'ownershipHeldAtEnd')}))
        if result['battleSummary'] is None or result['targetEligibilityViolations'] or result['ownershipIssues'] or result['ownershipHeldAtEnd']:
            raise SystemExit(1)
        return
    with zipfile.ZipFile(args.archive) as archive:
        raw = next(archive.read(n) for n in archive.namelist() if n.endswith('battle-1790695718778-c223b78f.jsonl'))
    if hashlib.sha256(raw).hexdigest() != DESKTOP_SHA:
        raise SystemExit('Desktop report identity mismatch')
    rows = [json.loads(line) for line in raw.splitlines()]
    result = dict(sourceBattleSha256=DESKTOP_SHA, original=summarize(rows))
    if args.jar:
        orders, replay = run_frames(args.jar, desktop_frames(rows), args.out)
        result.update(evidence='E2_OPEN_LOOP_POLICY_REPLAY_OF_E4_INPUTS',
            limits='Fixed own positions/enemy visibility; Economy/Recon disabled; no native execution or outcome evidence.',
            candidateSha256=hashlib.sha256(args.jar.read_bytes()).hexdigest(),
            replay=summarize(replay), emittedOrders=len(orders))
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out/'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({'sourceBattleSha256': DESKTOP_SHA,
        'originalLightSubOrders': result['original']['targetedIntents'].get(1026, 0),
        'replayLightSubOrders': result.get('replay', {}).get('targetedIntents', {}).get(1026, 0)}, indent=2))


if __name__ == '__main__':
    main()
