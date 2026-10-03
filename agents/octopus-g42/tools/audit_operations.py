#!/usr/bin/env python3
"""Read-only streaming audit of bootstrap, local army, and bounded surplus contracts.

Checks recorded acceptance and observed roles, never inferred kills, hidden enemies,
producer birthplace, or combat benefit. Missing branches remain explicit coverage gaps.
"""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def alive(unit):
    return isinstance(unit, dict) and not unit.get('dead') and number(unit.get('hp')) and unit['hp'] > 0


MINE_PAYBACK_DEFERRAL_REASONS = frozenset((
    'UNSUPPORTED_NATIVE_UPGRADE', 'OWN_MINE_QUOTE_MISMATCH',
    'LOCAL_RISK_OR_HEALTH_UNCONFIRMED', 'LOCAL_CLEAR_WINDOW_TOO_SHORT',
    'MINE_UPGRADE_ALREADY_COMMITTED', 'NATIVE_QUOTE_UNKNOWN',
    'PROTECTED_BUDGET_UNKNOWN', 'MILITARY_FLOOR_RECOVERY',
    'CHEAPER_LOCAL_NEW_MINE_FIRST', 'REPLACEMENTS_OR_RESERVES_PROTECTED',
    'PAYBACK_HORIZON_TOO_SHORT',
))
MINE_INCOME_MODEL = 'MEASURED_T1_12_07_X_FROZEN_GENERATION_DELTA_OVER_8'
MINE_SURVIVAL_MODEL = 'OBSERVED_LOCAL_QUIET_WINDOW_AND_BUDGET_HORIZON_NOT_A_SURVIVAL_PREDICTION'


def audit(path):
    path = Path(path)
    digest, size = hashlib.sha256(), 0
    events, reasons = collections.Counter(), collections.Counter()
    violations, integrity, gaps = [], [], collections.Counter()
    state, own, cohorts, leases, menu = {}, {}, {}, {}, {}
    pending, matched = [], set()
    latest_action, last_accepted, summary, provenance = None, None, {}, {}
    last_event, game_time, membership_dirty, first_own = None, 0, False, None
    bootstrap_completed, bootstrap_ready, bootstrap_queue, preflight = [], [], 0, {}
    cohort_orders, cohort_max, quoted_spend, ghost_max, accepted_commands = 0, 0, 0, 0, 0
    waiting_spend = None
    legacy_mine_holds, payback_mine_deferrals = 0, 0

    def bad(line, reason, **detail):
        reasons[reason] += 1
        if len(violations) < 500:
            violations.append(dict(line=line, reason=reason, **detail))

    def flush_membership(line):
        nonlocal membership_dirty, cohort_max
        if not membership_dirty:
            return
        membership_dirty = False
        cohort_max = max(cohort_max, len(cohorts))
        if len(cohorts) > 4:
            bad(line, 'COHORT_COUNT_OVER_FOUR', count=len(cohorts))
        owners = {}
        for cohort, members in cohorts.items():
            if len(members) > 48:
                bad(line, 'COHORT_MEMBERS_OVER_48', cohortId=cohort)
            for uid in members:
                if uid in owners:
                    bad(line, 'COHORT_MEMBER_DUPLICATED', unitId=uid, cohortIds=[owners[uid], cohort])
                owners[uid] = cohort

    def pending_ghosts():
        return sum(1 for order in pending if order['producer'] in own and own[order['producer']].get('productionQueue') == 0)

    def audit_quality(data, line, ordered):
        nonlocal ghost_max
        # Denials with unknown prices are valid conservative decisions. Only selected purchases
        # require all price/budget and quota values; all recorded evaluations still track ghosts.
        if number(data.get('artilleryPending')) and data['artilleryPending'] != len(pending):
            bad(line, 'ARTILLERY_PENDING_LEDGER_MISMATCH', recorded=data['artilleryPending'], reconstructed=len(pending))
        ghosts = pending_ghosts()
        ghost_max = max(ghost_max, ghosts)
        native = sum(1 for u in own.values() if alive(u) and u.get('mobile') and u.get('canAttack'))
        native += sum(max(0, u.get('productionQueue', 0)) for u in own.values()
                      if alive(u) and u.get('type') == 'landFactory' and number(u.get('productionQueue')))
        if own and number(data.get('committedArmed')) and data['committedArmed'] < native + ghosts:
            bad(line, 'PAID_GHOST_ARMY_SLOT_MISSING', recorded=data['committedArmed'], native=native, paidGhosts=ghosts)
        if not ordered and not data.get('selected'):
            return
        fields = ('nativeCost', 'ordinaryUnitNativeCost', 'credits', 'allReserved', 'requiredCredits',
                  'ordinaryForce', 'artilleryObserved', 'artilleryPending', 'artilleryQuota',
                  'committedArmed', 'armyTarget', 'hardSafetyCap')
        if not all(number(data.get(k)) for k in fields):
            bad(line, 'SURPLUS_BUDGET_INPUT_MISSING')
            return
        expected = data['nativeCost'] + 2 * data['ordinaryUnitNativeCost'] + data['allReserved']
        if (data['nativeCost'] <= 0 or data['ordinaryUnitNativeCost'] <= 0 or data['allReserved'] < 0
                or not math.isclose(data['requiredCredits'], expected, rel_tol=1e-9, abs_tol=1e-6)
                or data['credits'] + 1e-6 < expected):
            bad(line, 'SURPLUS_NATIVE_BUDGET_BREACHED', required=expected, recorded=data['requiredCredits'], credits=data['credits'])
        if (data['ordinaryForce'] < 24 or data.get('homeEmergency') is not False
                or data['committedArmed'] >= min(data['armyTarget'], data['hardSafetyCap'])
                or data['artilleryObserved'] + data['artilleryPending'] >= data['artilleryQuota']
                or data['artilleryQuota'] > min(6, math.floor(data['armyTarget'] / 12))):
            bad(line, 'SURPLUS_ROLE_GATE_BREACHED')

    with path.open('rb') as source:
        for line, raw in enumerate(source, 1):
            digest.update(raw); size += len(raw)
            if not raw.strip():
                continue
            try:
                row = json.loads(raw.decode('utf-8-sig') if line == 1 else raw.decode('utf-8'),
                                 parse_constant=lambda value: (_ for _ in ()).throw(ValueError('nonfinite ' + value)))
                event, data = row['event'], row['data']
                if not isinstance(event, str) or not isinstance(data, dict) or not number(row.get('wallTimeMs')):
                    raise ValueError('invalid event schema')
            except (ValueError, KeyError, TypeError, UnicodeError) as error:
                if len(integrity) < 500: integrity.append(dict(line=line, reason='BAD_EVENT', detail=str(error)))
                continue
            if event != 'local_army_membership': flush_membership(line)
            if last_event == 'summary': integrity.append(dict(line=line, reason='EVENT_AFTER_SUMMARY'))
            events[event] += 1; last_event = event
            if number(data.get('gameTimeMs')): game_time = data['gameTimeMs']
            if event == 'health': provenance = data.get('provenance', {})
            elif event == 'report_provenance': provenance.update(data)
            elif event == 'observation':
                state = data
                own = {u['id']: u for u in data.get('ownUnits', []) if isinstance(u, dict) and 'id' in u}
                if first_own is None: first_own = set(own)
            elif event == 'preflight': preflight = data
            elif event == 'action': latest_action = urlsplit(data.get('path', ''))
            elif event == 'command_result' and data.get('status') == 'queued':
                accepted_commands += 1
                last_accepted = dict(receipt=data, action=latest_action)
            elif event == 'task_ownership_acquired':
                owner = data.get('owner', 'recon:' + str(data.get('taskId')))
                leases[owner] = data.get('unitId')
            elif event == 'task_ownership_released':
                leases.pop(data.get('owner', 'recon:' + str(data.get('taskId'))), None)
            elif event == 'bootstrap_queue_observed': bootstrap_queue += 1
            elif event in ('bootstrap_existing_builder', 'bootstrap_builder_completed'):
                bootstrap_completed.append(dict(line=line, event=event, **data))
            elif event == 'bootstrap_ready': bootstrap_ready.append(dict(line=line, **data))
            elif event == 'local_army_membership':
                cohort, members = data.get('cohortId'), data.get('unitIds')
                if not isinstance(members, list) or any(not isinstance(uid, int) or isinstance(uid, bool) for uid in members):
                    bad(line, 'COHORT_MEMBERS_SCHEMA_INVALID', cohortId=cohort); continue
                if len(members) != len(set(members)): bad(line, 'COHORT_MEMBER_DUPLICATED_IN_GROUP', cohortId=cohort)
                if data.get('reason') == 'RELEASED_BELOW_THREE': cohorts.pop(cohort, None)
                else: cohorts[cohort] = set(members)
                membership_dirty = True
            elif event == 'local_army_order':
                cohort_orders += 1
                cohort, members = data.get('cohortId'), data.get('unitIds', [])
                if not members or len(members) > 48 or len(members) != len(set(members)) or not set(members).issubset(cohorts.get(cohort, set())):
                    bad(line, 'LOCAL_ORDER_ACTORS_OUTSIDE_COHORT', cohortId=cohort, unitIds=members)
                if data.get('receiptStatus') != 'queued' or not last_accepted:
                    bad(line, 'LOCAL_ORDER_NOT_ACCEPTED', cohortId=cohort)
                else:
                    action = last_accepted['action']; receipt = last_accepted['receipt']
                    query = parse_qs(action.query) if action else {}
                    commanded = query.get('unitIds', [''])[0].split(',')
                    if (not action or action.path != '/command/attack-move' or set(commanded) != {str(uid) for uid in members}
                            or data.get('requestId') != receipt.get('requestId')):
                        bad(line, 'LOCAL_ORDER_RECEIPT_ACTORS_MISMATCH', cohortId=cohort)
                for uid in members:
                    unit = own.get(uid)
                    if (not alive(unit) or not unit.get('mobile') or not unit.get('canAttack')
                            or not number(unit.get('buildProgress')) or unit['buildProgress'] < 1 or uid in leases.values()):
                        bad(line, 'LOCAL_ORDER_ACTOR_UNAVAILABLE', cohortId=cohort, unitId=uid)
            elif event == 'production_menu':
                menu = {u.get('id'): u for u in data.get('factories', []) if isinstance(u, dict)}
            elif event == 'surplus_spending_evaluated': audit_quality(data, line, False)
            elif event == 'surplus_role_ordered':
                audit_quality(data, line, True)
                producer, action_id = data.get('producerId'), data.get('actionId')
                action = next((a for a in menu.get(producer, {}).get('actions', []) if a.get('actionId') == action_id), None)
                if action is None: gaps['SURPLUS_NATIVE_MENU_NOT_OBSERVED'] += 1
                elif action.get('type') != 'heavyArtillery' or action.get('cost') != data.get('nativeCost') or not action.get('affordable'):
                    bad(line, 'SURPLUS_NATIVE_MENU_MISMATCH', producerId=producer)
                receipt = data.get('receipt', {})
                if receipt.get('status') != 'queued' or not last_accepted or receipt.get('requestId') != last_accepted['receipt'].get('requestId'):
                    bad(line, 'SURPLUS_ORDER_NOT_ACCEPTED', producerId=producer)
                else:
                    submitted = last_accepted['action']; query = parse_qs(submitted.query) if submitted else {}
                    if not submitted or submitted.path != '/command/queue' or query.get('unitId') != [str(producer)] or query.get('actionId') != [str(action_id)]:
                        bad(line, 'SURPLUS_RECEIPT_ACTION_MISMATCH', producerId=producer)
                pending.append(dict(producer=producer, at=game_time, before=set(own), line=line))
                quoted_spend += data.get('nativeCost', 0) if number(data.get('nativeCost')) else 0
                waiting_spend = dict(producer=producer, cost=data.get('nativeCost'), line=line)
            elif event == 'spend' and waiting_spend and data.get('itemType') == 'heavyArtillery':
                quote = waiting_spend['cost']
                if (not number(quote) or data.get('cost') != math.floor(quote + .5)
                        or data.get('producerOrBuilderId') != waiting_spend['producer']):
                    bad(line, 'SURPLUS_SPEND_QUOTE_MISMATCH')
                waiting_spend = None
            elif event == 'surplus_role_commitment_update':
                order = next((order for order in pending if order['producer'] == data.get('producerId')), None)
                if order is None: bad(line, 'SURPLUS_COMMITMENT_WITHOUT_ACCEPTED_ORDER', producerId=data.get('producerId')); continue
                reason, uid = data.get('reason'), data.get('unitId')
                if reason == 'OBSERVED_AVAILABLE_PRODUCT':
                    unit = own.get(uid)
                    if (not alive(unit) or unit.get('type') != 'heavyArtillery' or unit.get('buildProgress', 0) < 1
                            or uid in order['before'] or uid in matched): bad(line, 'SURPLUS_PRODUCT_MATCH_UNSUPPORTED', unitId=uid)
                    if data.get('assignmentSemantics') != 'OBSERVED_AVAILABLE_ROLE_NOT_FACTORY_PROVENANCE':
                        bad(line, 'SURPLUS_PRODUCT_PROVENANCE_OVERCLAIM', unitId=uid)
                    matched.add(uid)
                elif reason == 'PRODUCER_LOST':
                    if alive(own.get(order['producer'])): bad(line, 'SURPLUS_LIVE_PRODUCER_RELEASED')
                elif reason == 'PRODUCT_OBSERVATION_TIMEOUT':
                    if game_time - order['at'] < 180000: bad(line, 'SURPLUS_COMMITMENT_RELEASED_EARLY')
                else: bad(line, 'SURPLUS_COMMITMENT_RELEASE_REASON_UNKNOWN', releaseReason=reason)
                pending.remove(order)
            elif event == 'mine_income_investment_deferred':
                # Preserve the old saturation contract. The new payback policy
                # has its own inputs; armyDeficit is not part of that model.
                if data.get('reason') != 'INCOME_ALREADY_SURPLUS_NEAR_ARMY_TARGET':
                    payback_mine_deferrals += 1
                    if data.get('reason') not in MINE_PAYBACK_DEFERRAL_REASONS:
                        bad(line, 'MINE_PAYBACK_DEFERRAL_REASON_UNKNOWN'); continue
                    if (data.get('selected') is not False or data.get('incomeModel') != MINE_INCOME_MODEL
                            or data.get('survivalModel') != MINE_SURVIVAL_MODEL):
                        bad(line, 'MINE_PAYBACK_DEFERRAL_SCHEMA_MISMATCH'); continue
                    keys = ('mineId', 'nativeCost', 'ordinaryUnitNativeCost', 'credits', 'allReserved',
                            'requiredCredits', 'observedLocalClearGameSeconds', 'requiredLocalClearGameSeconds',
                            'incomeGainEstimate', 'paybackEstimateGameSeconds', 'upgradeTimeEstimateGameSeconds',
                            'survivalMarginGameSeconds', 'requiredRemainingGameSeconds', 'remainingGameSeconds',
                            'armed', 'militaryFloor')
                    if (not all(number(data.get(k)) for k in keys)
                            or not isinstance(data.get('sourceType'), str) or not isinstance(data.get('product'), str)
                            or not isinstance(data.get('cheaperSiteReady'), bool)):
                        bad(line, 'MINE_PAYBACK_DEFERRAL_INPUT_MISSING'); continue
                    # Conservative refusal may legitimately record unknown prices
                    # as -1. Full price/window/reserve proofs are audited separately.
                    gaps['MINE_PAYBACK_CONTRACT_REQUIRES_FEEDBACK_PROGRESS_AUDIT'] += 1
                    continue
                legacy_mine_holds += 1
                keys = ('credits', 'allReserved', 'nativeUpgradeCost', 'ordinaryUnitNativeCost', 'incomeEstimate',
                        'productionConsumption', 'armyDeficit', 'nearTargetDeficitLimit', 'cashBuffer', 'cashAfterInvestment')
                if not all(number(data.get(k)) for k in keys): bad(line, 'SURPLUS_MINE_HOLD_INPUT_MISSING'); continue
                buffer = max(30 * data['incomeEstimate'], 2 * data['ordinaryUnitNativeCost'])
                after = data['credits'] - data['allReserved'] - data['nativeUpgradeCost']
                if (data.get('reason') != 'INCOME_ALREADY_SURPLUS_NEAR_ARMY_TARGET'
                        or not math.isclose(buffer, data['cashBuffer'], rel_tol=1e-9, abs_tol=1e-6)
                        or not math.isclose(after, data['cashAfterInvestment'], rel_tol=1e-9, abs_tol=1e-6)
                        or after < buffer or data['armyDeficit'] > data['nearTargetDeficitLimit']
                        or data['incomeEstimate'] <= data['productionConsumption']): bad(line, 'SURPLUS_MINE_HOLD_CONTRACT_MISMATCH')
            elif event == 'summary': summary = data
    flush_membership(line if size else 0)
    if path.name.endswith('.partial'): integrity.append(dict(reason='UNCOMMITTED_REPORT'))
    if events['summary'] != 1 or last_event != 'summary': integrity.append(dict(reason='TERMINAL_SUMMARY_MISSING_OR_MULTIPLE'))
    if waiting_spend: bad(waiting_spend['line'], 'SURPLUS_SPEND_EVENT_MISSING')

    bootstrap_present = any(name.startswith('bootstrap_') for name in events) or path.name.startswith('bootstrap-')
    if bootstrap_present:
        commands = accepted_commands
        if commands > 1 or summary.get('automaticProductionCommands') != commands: bad(0, 'BOOTSTRAP_COMMAND_BOUND_BREACHED')
        if summary.get('outcome') == 'PASS':
            unit = own.get(summary.get('builderId'))
            if (not alive(unit) or unit.get('type') != summary.get('resolvedBuilderType') or unit.get('buildProgress', 0) < 1
                    or not bootstrap_completed or len(bootstrap_ready) != 1
                    or bootstrap_ready[0].get('builderId') != summary.get('builderId')): bad(0, 'BOOTSTRAP_READY_EVIDENCE_MISSING')
            if not preflight.get('commandsAllowed') or preflight.get('recommendation') not in ('RUN_DEVELOP', 'RUN_ECONOMY_OR_OPENING'):
                bad(0, 'BOOTSTRAP_READY_PREFLIGHT_INVALID')
            if summary.get('bootstrapMode') == 'EXISTING_BUILDER_QUEUE' and commands: bad(0, 'BOOTSTRAP_DUPLICATE_EXISTING_QUEUE')
            if summary.get('queueObserved') and not bootstrap_queue: bad(0, 'BOOTSTRAP_QUEUE_OBSERVATION_MISSING')

    return dict(schemaVersion=1, audit='OPERATIONS_2026_10_01_V1', file=str(path.resolve()),
                sha256=digest.hexdigest(), bytes=size, status='FAIL' if violations or integrity else 'PASS',
                reportedOutcome=summary.get('outcome', 'UNKNOWN'), provenance=provenance,
                coverage=dict(bootstrap='OBSERVED' if bootstrap_present else 'NOT_TRIGGERED',
                              localArmyMembership='OBSERVED' if events['local_army_membership'] else 'NOT_TRIGGERED',
                              localArmyAcceptedOrders='OBSERVED' if cohort_orders else 'NOT_TRIGGERED',
                              surplusEvaluations='OBSERVED' if events['surplus_spending_evaluated'] else 'NOT_TRIGGERED',
                              surplusAcceptedOrders='OBSERVED' if events['surplus_role_ordered'] else 'NOT_TRIGGERED',
                              surplusProductObservations='OBSERVED' if matched else 'NOT_TRIGGERED',
                              mineSurplusDeferral='OBSERVED' if events['mine_income_investment_deferred'] else 'NOT_TRIGGERED',
                              legacyMineSaturationContract='OBSERVED' if legacy_mine_holds else 'NOT_TRIGGERED',
                              minePaybackDeferral='SCHEMA_ONLY_REQUIRES_FEEDBACK_PROGRESS_AUDIT' if payback_mine_deferrals else 'NOT_TRIGGERED'),
                metrics=dict(acceptedLocalOrders=cohort_orders, maximumActiveCohorts=cohort_max,
                             acceptedSurplusOrders=events['surplus_role_ordered'], surplusQuotedSpend=quoted_spend,
                             matchedArtilleryProducts=len(matched), pendingArtilleryProducts=len(pending), maximumPaidGhostSlots=ghost_max,
                             legacyMineSaturationHolds=legacy_mine_holds, paybackMineDeferrals=payback_mine_deferrals),
                eventCounts=dict(events), violationCounts=dict(reasons), violations=violations,
                integrityIssues=integrity, evidenceGaps=dict(gaps),
                limitations=['Recorded contract audit only; existing fog/target legality audit remains separate.',
                             'New mine-payback deferrals validate schema only; full payback/quiet/reserve proofs require audit_feedback_progress.py.',
                             'No producer birthplace, kills, combat benefit, win-rate, or absent-branch claim.'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('reports', nargs='+', type=Path)
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    results = [audit(path) for path in args.reports]
    data = dict(schemaVersion=1, reports=results, status='PASS' if all(r['status'] == 'PASS' for r in results) else 'FAIL')
    raw = json.dumps(data, ensure_ascii=False, indent=2) + '\n'
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True); args.out.write_text(raw, encoding='utf-8')
    else: print(raw, end='')
    return 0 if data['status'] == 'PASS' else 1


if __name__ == '__main__': raise SystemExit(main())
