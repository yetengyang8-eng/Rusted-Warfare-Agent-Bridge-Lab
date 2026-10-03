#!/usr/bin/env python3
"""Read-only event audit. Native/desktop grades must come from the supplied run provenance."""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


def audit(path):
    path = Path(path)
    events = collections.Counter()
    products = collections.Counter()
    leases, tasks, blocked, target_positions, needs = {}, {}, {}, {}, {}
    violations = []
    own, visible, intent, summary, config = {}, {}, {}, {}, {}
    source_sha = hashlib.sha256()
    max_army = max_builders = max_engineers = 0
    last_credits = None
    provenance = None
    suppression_hits = collections.Counter()
    progress_targets, damage_targets, assigned_targets = set(), set(), set()
    phases = collections.Counter()
    coordinate_scope_skips = []
    with path.open('rb') as source:
        for number, raw in enumerate(source, 1):
            source_sha.update(raw)
            try:
                row = json.loads(raw)
                event, data = row['event'], row['data']
            except (ValueError, KeyError, TypeError) as error:
                violations.append({'line': number, 'reason': 'BAD_EVENT', 'detail': str(error)})
                continue
            events[event] += 1

            def fail(reason, detail):
                violations.append({'line': number, 'reason': reason, 'detail': detail})

            if event == 'health':
                provenance = data.get('provenance')
            elif event == 'battle_config':
                config = data
            elif event == 'observation':
                own = {u['id']: u for u in data['ownUnits'] if not u.get('dead') and u.get('hp', 0) > 0}
                max_army = max(max_army, sum(u.get('mobile') and u.get('canAttack') and u.get('buildProgress', 0) >= 1 for u in own.values()))
                max_builders = max(max_builders, sum(u.get('type') == 'builder' for u in own.values()))
                max_engineers = max(max_engineers, sum(u.get('type') == 'combatEngineer' for u in own.values()))
                last_credits = data.get('player', {}).get('credits')
            elif event == 'combat_observation':
                visible = {u['id']: u for u in data['visibleEnemies']}
            elif (event == 'engagement_assessment' or
                  event == 'engagement_observation' and config.get('engagementAssessmentContract') != 'COMMITTED_FORMATION_V1') and data.get('targetVisible') is True:
                tid = data['targetId']
                xy = (data['targetX'], data['targetY'])
                if tid in target_positions and math.dist(target_positions[tid], xy) > 20:
                    blocked[tid] = set()
                target_positions[tid] = xy
                denied = blocked.setdefault(tid, set())
                for actor in data['actors']:
                    if actor['status'] == 'BLOCKED_TERRAIN':
                        denied.add(actor['unitId'])
                    elif actor['status'] == 'APPROACH_PATH_KNOWN':
                        denied.discard(actor['unitId'])
            elif event == 'capability_need_created':
                proof = data.get('evidence', {})
                actors = proof.get('actors', [])
                if not actors or proof.get('targetVisible') is not True:
                    fail('NEED_WITHOUT_VISIBLE_PROOF', data.get('targetId'))
                elif not (all(a.get('status') == 'BLOCKED_TERRAIN' for a in actors)
                          or all(a.get('compatibility') == 'INCOMPATIBLE' for a in actors)):
                    fail('NEED_WITHOUT_FORMATION_GAP', data.get('targetId'))
                needs[data['targetId']] = {k: data.get(k) for k in ('targetType', 'reason', 'observedAtGameTimeMs')}
            elif event == 'engagement_selection_blocked':
                suppression_hits[data['targetId']] += 1
            elif event == 'task_ownership_acquired':
                task, unit = data['taskId'], data['unitId']
                if unit in leases or task in tasks:
                    fail('DUPLICATE_LEASE', {'task': task, 'unit': unit})
                owner = data.get('owner', 'recon:' + str(task))
                tasks[task] = unit
                leases[unit] = owner
            elif event == 'task_ownership_released':
                unit = tasks.pop(data['taskId'], None)
                if unit is None:
                    fail('UNPAIRED_RELEASE', data['taskId'])
                else:
                    leases.pop(unit, None)
            elif event == 'tactical_intent':
                intent = data
            elif event == 'action':
                action = urlsplit(data['path'])
                query = parse_qs(action.query)
                ids = [int(x) for x in query.get('unitIds', query.get('unitId', ['']))[0].split(',') if x]
                owner = data.get('owner')
                if owner is not None:
                    for unit in ids:
                        if unit in leases and leases[unit] != owner:
                            fail('COMMAND_STOLE_LEASE', {'unit': unit, 'owner': owner, 'lease': leases[unit]})
                        if owner != 'rule-main' and leases.get(unit) != owner:
                            fail('COMMAND_WITHOUT_LEASE', {'unit': unit, 'owner': owner})
                if action.path == '/command/attack-move' and intent.get('reason') == 'OBSERVED_ENEMY':
                    tid = (intent.get('enemy') or {}).get('id')
                    overlap = set(ids) & blocked.get(tid, set())
                    target = intent.get('enemy') or {}
                    # A moving contact can cross >20 world units between the 48-actor requests.
                    # A negative for one position cannot be applied to a different ordered position.
                    if overlap and tid in target_positions and 'x' in target and 'y' in target and math.dist(target_positions[tid], (target['x'], target['y'])) > 20:
                        coordinate_scope_skips.append({'line': number, 'targetId': tid,
                                                       'evidencePosition': target_positions[tid],
                                                       'orderedPosition': [target['x'], target['y']]})
                        overlap = set()
                    if overlap:
                        fail('MAIN_FORCE_ORDER_AFTER_TERRAIN_REJECTION', {'targetId': tid, 'unitIds': sorted(overlap)})
            elif event == 'strategy_task_assigned':
                assigned_targets.add(data['targetId'])
                phases[data.get('objectiveSemantics', 'CURRENT_CONTACT_APPROACH')] += 1
                proof = data.get('evidence', {})
                actors = proof.get('actors', [])
                if not actors:
                    fail('RESPONSE_WITHOUT_ACTOR_PROOF', data['taskId'])
                elif data.get('objectiveSemantics') == 'LAST_KNOWN_SITE_INVESTIGATION':
                    if actors[0].get('lastKnownPositionApproachStatus') != 'APPROACH_PATH_KNOWN':
                        fail('INVESTIGATION_WITHOUT_KNOWN_APPROACH', data['taskId'])
                elif proof.get('targetVisible') is not True or actors[0].get('status') != 'APPROACH_PATH_KNOWN' or actors[0].get('compatibility') != 'COMPATIBLE':
                    fail('RESPONSE_WITHOUT_CURRENT_APPROACH', data['taskId'])
            elif event == 'strategy_response_progress':
                progress_targets.add(data['targetId'])
                actor = own.get(data['unitId'])
                if actor is None or any(actor.get(k) != data.get('unit', {}).get(k) for k in ('x', 'y', 'hp')):
                    fail('UNOBSERVED_RESPONSE_PROGRESS', data['unitId'])
            elif event == 'strategy_target_damage_observed':
                damage_targets.add(data['targetId'])
                target = visible.get(data['targetId'])
                if target is None or target.get('hp') != data.get('hpNow') or not data['hpNow'] < data['hpBefore']:
                    fail('UNOBSERVED_TARGET_DAMAGE', data['targetId'])
            elif event in ('mine_upgrade_observed', 'strategy_construction_observed'):
                unit = data.get('unit', {})
                current = own.get(unit.get('id'))
                if current is None or current != unit or unit.get('buildProgress', 0) < 1:
                    fail('UNOBSERVED_INVESTMENT_COMPLETION', unit.get('id'))
                if event == 'mine_upgrade_observed':
                    expected = data.get('product', 'extractorT2')
                    if expected not in ('extractorT2', 'extractorT3') or unit.get('type') != expected:
                        fail('UPGRADE_TYPE_MISMATCH', unit.get('id'))
                products[unit.get('type')] += 1
            elif event == 'summary':
                summary = data
    if not summary:
        violations.append({'reason': 'MISSING_SUMMARY'})
    elif leases:
        violations.append({'reason': 'LEASES_HELD_AT_END', 'detail': leases})
    return {'schemaVersion': 1, 'source': path.name, 'sourceSha256': source_sha.hexdigest(),
            'evidenceScope': 'EVENT_AUDIT_NATIVE_OR_DESKTOP_GRADE_REQUIRES_RUN_PROVENANCE',
            'candidateProvenance': provenance, 'battleConfig': config, 'summary': summary,
            'maxObservedMobileArmed': max_army, 'maxObservedBuilders': max_builders,
            'maxObservedCombatEngineers': max_engineers, 'endCredits': last_credits,
            'capabilityNeeds': needs, 'terrainSelectionBlocksByTarget': dict(suppression_hits),
            'assignedResponseTargets': sorted(assigned_targets), 'progressTargets': sorted(progress_targets),
            'damageTargets': sorted(damage_targets), 'responseObjectiveSemantics': dict(phases),
            'observedInvestmentProducts': dict(products),
            'coordinateScopeSkips': coordinate_scope_skips,
            'events': {k: v for k, v in events.items() if k.startswith(('strategy_', 'capability_need_', 'engagement_', 'mine_upgrade_', 'task_ownership_'))},
            'violations': violations, 'pass': not violations}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.report)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'pass': result['pass'], 'violations': result['violations'], 'out': str(args.out)}, ensure_ascii=False))
    return 0 if result['pass'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
