#!/usr/bin/env python3
"""Stream a Battle JSONL into a read-only, line-linked capability funding analysis."""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path


def analyze(path):
    path = Path(path)
    digest = hashlib.sha256()
    counts = collections.Counter()
    state, enemies, heartbeat, capacity = {}, {}, {}, {}
    state_line = heartbeat_line = capacity_line = 0
    start = None
    lifecycles, active, task_targets = [], {}, {}
    menus, allocations, spends, windows = [], [], [], []
    current = None
    maxima = {}
    samples = 0
    first_need_seen = False
    summary = provenance = None

    def context(line, data=None):
        timestamp = (data or {}).get('gameTimeMs', state.get('gameTimeMs'))
        return {'line': line, 'gameTimeMs': timestamp,
                'battleSeconds': None if start is None or timestamp is None else round((timestamp-start)/1000, 6)}

    def finish_window(boundary):
        nonlocal current
        if current is not None:
            current['endBoundaryLineExclusive'] = boundary
            windows.append(current)
            current = None

    with path.open('rb') as source:
        for number, raw in enumerate(source, 1):
            digest.update(raw)
            row = json.loads(raw)
            event, data = row['event'], row['data']
            counts[event] += 1
            if event == 'health':
                provenance = data.get('provenance')
            elif event == 'summary':
                summary = data
            elif event == 'observation':
                state, state_line = data, number
                if start is None:
                    start = data['gameTimeMs']
                point = dict(context(number, data), credits=data['player']['credits'])
                for key, enabled in [('allObservations', True), ('afterFirstNeed', first_need_seen), ('whileNeedActive', bool(active))]:
                    if enabled and point['credits'] > maxima.get(key, {}).get('credits', -1):
                        maxima[key] = point
            elif event == 'agent_tick_alive':
                heartbeat, heartbeat_line = data, number
            elif event == 'strategy_capacity':
                capacity, capacity_line = data, number
            elif event == 'spend':
                spends.append(dict(context(number, data), **{key: data[key] for key in ('category', 'cost', 'itemType', 'producerOrBuilderId')}))
            elif event == 'strategy_allocation':
                allocations.append(dict(context(number, data), **{key: data.get(key) for key in ('selected', 'freeCredits', 'protectedFunds', 'action', 'unservedCapabilityNeed', 'engineers')}))
            elif event == 'capability_need_created':
                first_need_seen = True
                need = {'targetId': data['targetId'], 'targetType': data.get('targetType'), 'reason': data.get('reason'),
                        'created': context(number, data), 'events': [], 'status': 'OPEN_AT_REPORT_END'}
                lifecycles.append(need)
                active[data['targetId']] = need
            elif event in ('capability_need_released', 'capability_need_resolved'):
                need = active.pop(data['targetId'], None)
                if need is not None:
                    need['ended'] = dict(context(number, data), event=event, reason=data.get('reason'))
                    need['status'] = 'RESOLVED_BY_LEGAL_EVIDENCE' if event.endswith('resolved') else 'RELEASED_BY_NEW_APPROACH_EVIDENCE'

            if event == 'strategy_task_assigned':
                task_targets[data['taskId']] = data['targetId']
            if event.startswith(('strategy_task_', 'strategy_response_', 'strategy_target_damage_')):
                target = data.get('targetId', task_targets.get(data.get('taskId')))
                need = active.get(target) or next((n for n in reversed(lifecycles) if n['targetId'] == target), None)
                if need is not None:
                    entry = dict(context(number, data), event=event)
                    entry.update({key: data[key] for key in ('taskId', 'unitId', 'reason', 'attempts', 'previousJob', 'job', 'objectiveSemantics', 'attribution') if key in data})
                    entry['targetIdSource'] = 'EVENT' if 'targetId' in data else 'LAST_ASSIGNMENT_FOR_TASK'
                    need['events'].append(entry)
                if event == 'strategy_task_completed':
                    task_targets.pop(data.get('taskId'), None)

            if event == 'strategy_production_menu':
                factories = []
                for factory in data.get('factories', []):
                    actions = [a for a in factory.get('actions', []) if a.get('type') == 'combatEngineer']
                    if actions:
                        factories.append({'id': factory['id'], 'tier': factory.get('tier'), 'queue': factory.get('queue'), 'actions': actions})
                menus.append(dict(context(number), observationLine=state_line,
                                  creditsFromPriorObservation=state.get('player', {}).get('credits'), factories=factories))

            if event == 'combat_observation':
                enemies = data
                if not state:
                    continue
                units = state['ownUnits']
                live = [u for u in units if not u.get('dead') and u.get('hp', 0) > 0]
                force = sum(bool(u.get('mobile') and u.get('canAttack') and u.get('buildProgress', 0) >= 1) for u in live)
                home = next((u for u in units if u.get('type') == 'commandCenter'), None)
                emergency = None if home is None else any(e.get('canAttack') and math.hypot(e['x']-home['x'], e['y']-home['y']) < 500 for e in enemies.get('visibleEnemies', []))
                t2 = [u for u in live if u.get('type') == 'landFactory' and u.get('techLevel', 0) >= 2 and u.get('buildProgress', 0) >= 1]
                point = dict(context(number, state), observationLine=state_line, force=force,
                             credits=state['player']['credits'], homeEmergency=emergency,
                             activeNeeds=sorted(active), readyT2Factories=[u['id'] for u in t2],
                             emptyT2Factories=[u['id'] for u in t2 if u.get('productionQueue') == 0],
                             modelledIncome=capacity.get('incomeEstimate'), capacityLine=capacity_line,
                             builderReserveAtLastHeartbeat=heartbeat.get('builderReserve'),
                             buildPendingAtLastHeartbeat=heartbeat.get('buildPending'), heartbeatLine=heartbeat_line)
                samples += 1
                # Partial gates only: assignment/failure budgets and exact strategy.act timing are not reconstructed.
                eligible = bool(active) and force >= 6 and emergency is False and bool(t2)
                if eligible:
                    if current is None:
                        current = {'samples': []}
                    current['samples'].append(point)
                else:
                    finish_window(number)
    if start is None:
        raise ValueError('Report contains no observation event')
    finish_window(number + 1)

    for need in active.values():
        need['reportEnd'] = context(number, state)
    for window in windows:
        points = window.pop('samples')
        first, last = points[0], points[-1]
        eligible_spends = [s for s in spends if first['line'] <= s['line'] < window['endBoundaryLineExclusive']]
        ordinary = [s for s in eligible_spends if s['category'] == 'UNIT_PRODUCTION']
        costs = collections.Counter()
        for spend in eligible_spends:
            costs[spend['category']] += spend['cost']
        incomes = [p['modelledIncome'] for p in points if p['modelledIncome'] is not None]
        reserves = [p['builderReserveAtLastHeartbeat'] for p in points if p['builderReserveAtLastHeartbeat'] is not None]
        window.update(start=first, end=last, observedSpanSeconds=round((last['gameTimeMs']-first['gameTimeMs'])/1000, 6),
                      observationSamples=len(points), forceMin=min(p['force'] for p in points), forceMax=max(p['force'] for p in points),
                      modelledIncomeMin=min(incomes) if incomes else None, modelledIncomeMax=max(incomes) if incomes else None,
                      maximumSampledBuilderReserve=max(reserves) if reserves else None,
                      sampledBuildPendingValues=sorted({str(p['buildPendingAtLastHeartbeat']) for p in points}),
                      spendByCategory=dict(costs), ordinaryProductionCount=len(ordinary),
                      ordinaryProductionCost=sum(s['cost'] for s in ordinary), ordinaryProductionSpends=ordinary,
                      bookkeepingThresholds=[])
        cost_evidence = {}
        for menu in menus:
            if menu['line'] >= window['endBoundaryLineExclusive']:
                continue
            for factory in menu['factories']:
                for action in factory['actions']:
                    cost_evidence.setdefault(action['cost'], menu['line'])
        known_costs = sorted(cost_evidence)
        for cost in known_costs:
            for buffer in (0, 700):
                threshold = {'menuCost': cost, 'menuCostEvidenceLine': cost_evidence[cost], 'illustrativeAdditionalBuffer': buffer, 'targetCash': cost+buffer, 'firstCrossing': None}
                for point in points:
                    if point['line'] <= cost_evidence[cost]:
                        continue
                    # A spend in this same decision occurs AFTER observation: raw line ordering avoids double counting.
                    prior = [s for s in ordinary if s['line'] < point['observationLine']]
                    held = sum(s['cost'] for s in prior)
                    if point['credits'] + held >= cost + buffer:
                        threshold['firstCrossing'] = dict(context(point['line'], point), observationLine=point['observationLine'],
                            secondsFromWindowStart=round((point['gameTimeMs']-first['gameTimeMs'])/1000, 6),
                            actualCredits=point['credits'], retainedOrdinarySpend=held, bookkeepingCash=point['credits']+held,
                            observedForce=point['force'], spendLines=[s['line'] for s in prior])
                        break
                window['bookkeepingThresholds'].append(threshold)
    windows.sort(key=lambda w: w['observedSpanSeconds'], reverse=True)

    stats = {'count': len(menus), 'withEngineerMenu': 0, 'withEmptyEngineerFactory': 0, 'withAffordableEngineer': 0, 'withEmptyAndAffordableEngineerFactory': 0}
    for menu in menus:
        options = [(f, a) for f in menu['factories'] for a in f['actions']]
        stats['withEngineerMenu'] += bool(options)
        stats['withEmptyEngineerFactory'] += any(f['queue'] == 0 for f, a in options)
        stats['withAffordableEngineer'] += any(a.get('affordable') is True for f, a in options)
        stats['withEmptyAndAffordableEngineerFactory'] += any(f['queue'] == 0 and a.get('affordable') is True for f, a in options)
    return {'schemaVersion': 1, 'source': path.name, 'sourceSha256': digest.hexdigest(), 'sourceLines': number,
            'candidateProvenance': provenance, 'battleStartGameTimeMs': start, 'eventCounts': dict(counts),
            'creditsMaxima': maxima, 'productionMenuSummary': stats, 'productionMenus': menus,
            'needLifecycles': lifecycles, 'strategyAllocations': allocations, 'fundingGateWindows': windows,
            'combatObservationSamples': samples, 'reportedSummary': summary,
            'interpretation': {
                'windowGate': 'ACTIVE_NEED_AND_FORCE_GTE_6_AND_NO_VISIBLE_ARMED_ENEMY_WITHIN_500_OF_HOME_AND_READY_T2_FACTORY',
                'windowIsPartialAdmissionOnly': True,
                'bounds': [
                    'Consecutive observed gates do not prove tactical safety between observations or after withholding production.',
                    'Windows do not reconstruct assigned responders, retry limits, pending purchases, shared command slots, or the 8-second allocation timer.',
                    'The A report has no strategy task assignment or engineer investment; do not assume that for other reports.',
                    'Heartbeat builder reserve and buildPending are incomplete samples, not exact protectedFunds; the 700 buffer is illustrative, not inferred hard reserve.',
                    'Bookkeeping cash adds only earlier ordinary UNIT_PRODUCTION spending to observed cash. It keeps the historical income/loss trajectory fixed and is not a counterfactual game.',
                    'Modelled income is strategy_capacity.incomeEstimate, not a fresh measurement.',
                    'Open needs at a PARTIAL/ONGOING budget boundary are unfinished, not necessarily rejected or failed.',
                    'Menu affordability is the native action flag; prior observation credits can precede that menu in game time.',
                ]}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.report.resolve() == args.out.resolve():
        parser.error('--out must not overwrite --report')
    result = analyze(args.report)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'source': result['source'], 'sourceSha256': result['sourceSha256'],
                      'productionMenus': result['productionMenuSummary'],
                      'needs': len(result['needLifecycles']), 'windows': len(result['fundingGateWindows'])}, ensure_ascii=False))


if __name__ == '__main__':
    main()
