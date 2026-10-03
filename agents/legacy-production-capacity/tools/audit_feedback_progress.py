#!/usr/bin/env python3
"""Stream recorded local-defense, rear-provider, mode, and mine-payback contracts.

This is an evidence audit. Missing triggered evidence is INCOMPLETE, not an inferred
success. PARTIAL game results, legal observation limits, and non-causal metrics remain
explicit. No file is modified, and no live game or hidden state is accessed.
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


def ready(unit):
    return (isinstance(unit, dict) and not unit.get('dead') and number(unit.get('hp'))
            and unit['hp'] > 0 and number(unit.get('buildProgress')) and unit['buildProgress'] >= 1)


def near(a, b):
    if not all(number(q.get(k)) for q in (a, b) for k in ('x', 'y')):
        return None
    return math.hypot(a['x'] - b['x'], a['y'] - b['y'])


def audit(path):
    path = Path(path)
    before = path.stat()
    digest, size, line = hashlib.sha256(), 0, 0
    events, reasons, gaps = collections.Counter(), collections.Counter(), collections.Counter()
    violations, integrity = [], []
    state, own, combat, scout, cohorts, leases = {}, {}, {}, {}, {}, {}
    engagements, crisis, construction, bindings, mode_state = {}, {}, {}, {}, {}
    matched_products, support_responders = set(), set()
    accepted = collections.OrderedDict()
    latest_action, latest_accept, latest_local, summary, provenance = None, None, None, {}, {}
    pending_mine, local_intent = None, None
    metrics = dict(crisisStarted=0, crisisRespondOrders=0, crisisReturnOrders=0,
                   providerJetConstructions=0, observedSupportJets=0, supportTransfers=0,
                   acceptedDiveModes=0, observedCompatibleDiveModes=0, jetResponseOrders=0,
                   minePaybackEvaluations=0, acceptedMineInvestments=0,
                   fairnessSlots=0, cohortDiagnostics=0,
                   longestNoOrderAcceptedAgeSeconds=0, longestDiagnosticAcceptedAgeSeconds=0)
    coverage = {}
    previous_event = None

    def bad(reason, **detail):
        reasons[reason] += 1
        if len(violations) < 500:
            violations.append(dict(line=line, reason=reason, **detail))

    def missing(reason):
        gaps[reason] += 1

    def ids(data, key='unitIds'):
        value = data.get(key)
        if not isinstance(value, list) or not all(number(i) and int(i) == i and i >= 0 for i in value):
            missing('ACTOR_IDS_MISSING_OR_INVALID')
            return None
        if len(set(value)) != len(value):
            bad('DUPLICATE_ACTOR_IDS')
        return set(value)

    def fresh_evidence(evidence, target, actors, mode=False):
        if not isinstance(evidence, dict):
            missing('ENGAGEMENT_EVIDENCE_MISSING')
            return False
        fields = ('targetId', 'targetVisible', 'targetObservedAtGameTimeMs', 'actors')
        if not all(k in evidence for k in fields) or not number(evidence.get('targetObservedAtGameTimeMs')):
            missing('ENGAGEMENT_FRESHNESS_FIELDS_MISSING')
            return False
        if evidence['targetId'] != target or evidence['targetVisible'] is not True:
            bad('ENGAGEMENT_TARGET_NOT_CURRENT', targetId=target)
            return False
        now = state.get('gameTimeMs')
        if number(now) and evidence['targetObservedAtGameTimeMs'] < now:
            bad('ENGAGEMENT_EVIDENCE_STALE', observed=evidence['targetObservedAtGameTimeMs'], ownTime=now)
            return False
        query_time=evidence.get('gameTimeMs')
        if not number(query_time):
            missing('ENGAGEMENT_QUERY_TIME_MISSING')
        elif ((number(now) and query_time<now)
              or evidence['targetObservedAtGameTimeMs']!=query_time):
            bad('ENGAGEMENT_CONTACT_NOT_CURRENT_TO_QUERY',queryTime=query_time,
                targetObservedAt=evidence['targetObservedAtGameTimeMs'],ownTime=now)
            return False
        if not isinstance(evidence.get('sessionId'),str):
            missing('ENGAGEMENT_SESSION_MISSING')
        elif isinstance(state.get('sessionId'),str) and evidence['sessionId']!=state['sessionId']:
            bad('ENGAGEMENT_SESSION_MISMATCH')
            return False
        if not isinstance(evidence['actors'], list):
            missing('ENGAGEMENT_ACTORS_MISSING')
            return False
        index = {a.get('unitId'): a for a in evidence['actors'] if isinstance(a, dict)}
        for uid in actors:
            actor = index.get(uid)
            if actor is None:
                bad('ENGAGEMENT_ACTOR_NOT_EVIDENCED', unitId=uid)
                return False
            if mode:
                if actor.get('requiredMode') != 'DIVE' or actor.get('modeApproachStatus') != 'APPROACH_PATH_KNOWN':
                    bad('MODE_NATIVE_APPROACH_NOT_KNOWN', unitId=uid)
                    return False
            elif actor.get('compatibility') != 'COMPATIBLE' or actor.get('status') != 'APPROACH_PATH_KNOWN':
                bad('RESPONSE_NOT_COMPATIBLE_KNOWN_APPROACH', unitId=uid)
                return False
        return True

    def receipt_for(request=None, endpoint=None, actor_ids=None, owner=None):
        record = accepted.get(request) if request else latest_accept
        if record is None:
            missing('ACCEPTED_RECEIPT_MISSING')
            return None
        action, receipt, query = record['action'], record['receipt'], record['query']
        if endpoint and urlsplit(action.get('path', '')).path != endpoint:
            bad('ACCEPTED_COMMAND_ENDPOINT_MISMATCH', expected=endpoint, actual=action.get('path'))
            return None
        if owner is not None and action.get('owner') != owner:
            bad('ACCEPTED_COMMAND_OWNER_MISMATCH', expected=owner, actual=action.get('owner'))
        if actor_ids is not None:
            command_actors = set(record['actors'])
            if command_actors != set(actor_ids):
                bad('ACCEPTED_COMMAND_ACTORS_MISMATCH', expected=sorted(actor_ids), actual=sorted(command_actors))
            if 'unitIds' in receipt:
                exact = ids(receipt)
                if exact is not None and exact != set(actor_ids):
                    bad('NATIVE_RECEIPT_ACTORS_MISMATCH')
            elif 'unitId' in receipt:
                if {receipt['unitId']} != set(actor_ids): bad('NATIVE_RECEIPT_ACTORS_MISMATCH')
            else:
                missing('NATIVE_RECEIPT_ACTORS_NOT_RECORDED')
        return record

    def check_mine(data, ordered=False):
        metrics['minePaybackEvaluations'] += not ordered
        if not data.get('selected') and not ordered:
            return  # Unknown native prices and defensive refusals are valid conservative branches.
        keys = ('nativeCost', 'ordinaryUnitNativeCost', 'credits', 'allReserved', 'requiredCredits',
                'observedLocalClearGameSeconds', 'requiredLocalClearGameSeconds', 'incomeGainEstimate',
                'paybackEstimateGameSeconds', 'upgradeTimeEstimateGameSeconds', 'survivalMarginGameSeconds',
                'requiredRemainingGameSeconds', 'remainingGameSeconds', 'armed', 'militaryFloor')
        if not all(number(data.get(k)) for k in keys):
            missing('MINE_PAYBACK_INPUT_MISSING')
            return
        product, source = data.get('product'), data.get('sourceType')
        model = {('extractorT1', 'extractorT2'): (6.035, 28, 90, 45),
                 ('extractorT2', 'extractorT3'): (12.07, 54, 180, 180)}.get((source, product))
        if model is None:
            bad('MINE_NATIVE_UPGRADE_UNSUPPORTED')
            return
        gain, conversion, margin, quiet = model
        expected_budget = data['nativeCost'] + data['allReserved'] + 2 * data['ordinaryUnitNativeCost']
        expected_payback = data['nativeCost'] / gain
        expected_window = expected_payback + conversion + margin
        if (data['nativeCost'] <= 0 or data['ordinaryUnitNativeCost'] <= 0 or data['allReserved'] < 0
                or not math.isclose(data['requiredCredits'], expected_budget, abs_tol=1e-6)
                or data['credits'] < expected_budget):
            bad('MINE_RESERVES_OR_REPLACEMENTS_BREACHED')
        if (not math.isclose(data['incomeGainEstimate'], gain, abs_tol=1e-6)
                or not math.isclose(data['paybackEstimateGameSeconds'], expected_payback, abs_tol=1e-6)
                or not math.isclose(data['upgradeTimeEstimateGameSeconds'], conversion, abs_tol=1e-6)
                or not math.isclose(data['survivalMarginGameSeconds'], margin, abs_tol=1e-6)
                or not math.isclose(data['requiredRemainingGameSeconds'], expected_window, abs_tol=1e-6)
                or data['remainingGameSeconds'] <= expected_window):
            bad('MINE_PAYBACK_HORIZON_INVALID')
        if (data['observedLocalClearGameSeconds'] < quiet
                or data['requiredLocalClearGameSeconds'] != quiet
                or data['armed'] < 6 or data['militaryFloor'] != 6
                or data.get('cheaperSiteReady') is not False):
            bad('MINE_LOCAL_QUIET_OR_MILITARY_FLOOR_INVALID')
        mine = own.get(data.get('mineId'))
        if mine is None:
            missing('MINE_OWN_OBSERVATION_MISSING')
        elif not ready(mine) or mine.get('type') != source or mine.get('productionQueue') != 0:
            bad('MINE_OWN_ACTOR_NOT_READY_OR_IDLE')

    with path.open('rb') as source:
        for line, raw in enumerate(source, 1):
            digest.update(raw); size += len(raw)
            if not raw.strip(): continue
            try:
                row = json.loads(raw.decode('utf-8-sig') if line == 1 else raw.decode('utf-8'),
                                 parse_constant=lambda q: (_ for _ in ()).throw(ValueError('nonfinite ' + q)))
                event, data = row['event'], row['data']
                if not isinstance(event, str) or not isinstance(data, dict) or not number(row.get('wallTimeMs')):
                    raise ValueError('invalid event schema')
            except (ValueError, KeyError, TypeError, UnicodeError) as error:
                if len(integrity) < 500: integrity.append(dict(line=line, reason='BAD_EVENT', detail=str(error)))
                continue
            if previous_event == 'summary': integrity.append(dict(line=line, reason='EVENT_AFTER_SUMMARY'))
            previous_event = event; events[event] += 1
            if event == 'report_provenance': provenance = data
            elif event == 'observation': state=data; own={u.get('id'):u for u in data.get('ownUnits',[]) if isinstance(u,dict)}
            elif event == 'combat_observation': combat=data
            elif event == 'scout_visibility': scout=data
            elif event in ('local_crisis_engagement','response_engagement_observation'):
                engagements[data.get('targetId')] = data
            elif event == 'local_army_membership':
                if data.get('reason') == 'RELEASED_BELOW_THREE': cohorts.pop(data.get('cohortId'),None)
                else: cohorts[data.get('cohortId')] = set(data.get('unitIds',[]))
            elif event == 'task_ownership_acquired':
                uid, owner = data.get('unitId'), data.get('owner')
                # Existing Recon events predate the explicit owner field. Their
                # native lease is canonically recon:<positive taskId>.
                if owner is None and isinstance(data.get('taskId'),int) and not isinstance(data['taskId'],bool) and data['taskId']>0:
                    owner='recon:'+str(data['taskId'])
                if uid is None or not isinstance(owner,str): missing('OWNERSHIP_ACQUIRE_FIELDS_MISSING'); continue
                if uid in leases and leases[uid] != owner: bad('ACTOR_LEASE_CONFLICT',unitId=uid)
                leases[uid]=owner
            elif event == 'task_ownership_released':
                owner=data.get('owner')
                if owner is None and isinstance(data.get('taskId'),int) and not isinstance(data['taskId'],bool) and data['taskId']>0:
                    owner='recon:'+str(data['taskId'])
                released=data.get('unitIds',[data['unitId']] if 'unitId' in data else None)
                if released is None:released=[u for u,o in leases.items() if o==owner]
                for uid in released:
                    if leases.get(uid)==owner:leases.pop(uid,None)
            elif event == 'action':
                latest_action=dict(data,line=line)
            elif event == 'command_result' and data.get('status')=='queued':
                if latest_action is None: missing('ACTION_BEFORE_RECEIPT_MISSING'); continue
                query=parse_qs(urlsplit(latest_action.get('path','')).query)
                request=query.get('requestId',[None])[0]
                if request and data.get('requestId') != request: bad('REQUEST_RECEIPT_ID_MISMATCH')
                try:
                    actors=[int(i) for i in query.get('unitIds',query.get('unitId',['']))[0].split(',') if i]
                except ValueError:
                    bad('COMMAND_ACTOR_IDS_INVALID'); actors=[]
                for uid in actors:
                    if uid in leases and latest_action.get('owner') != leases[uid]: bad('COMMAND_STOLE_LEASE',unitId=uid)
                latest_accept=dict(action=latest_action,receipt=data,query=query,actors=actors,line=line)
                if request:
                    accepted[request]=latest_accept
                    if len(accepted)>2048: accepted.popitem(last=False)
                latest_action=None
            elif event == 'tactical_intent' and str(data.get('owner','')).startswith('local-crisis:'):
                local_intent=data
            elif event == 'local_crisis_started':
                actors=ids(data); owner=data.get('owner'); metrics['crisisStarted']+=1
                if actors is None: continue
                if not 2<=len(actors)<=6:
                    bad('CRISIS_RESPONSE_BOUNDS_OR_MAIN_RESERVE_BREACHED')
                if not number(data.get('mainRemaining')):missing('CRISIS_MAIN_RESERVE_FIELD_MISSING')
                elif data['mainRemaining']<6:bad('CRISIS_RESPONSE_BOUNDS_OR_MAIN_RESERVE_BREACHED')
                if any(leases.get(uid)!=owner for uid in actors): bad('CRISIS_ACTOR_NOT_EXCLUSIVELY_LEASED')
                for cid,members in cohorts.items():
                    if actors&members and len(members-actors)<3: bad('CRISIS_COHORT_RESERVE_BREACHED',cohortId=cid)
                if not number(data.get('visibleThreats')):missing('CRISIS_CLUSTER_SIZE_FIELD_MISSING')
                elif not 1<=data['visibleThreats']<=3:
                    bad('CRISIS_THREAT_CLUSTER_NOT_SMALL')
                if not number(data.get('durabilityFloorHp')) or not number(data.get('visibleThreatHp')):
                    missing('CRISIS_DURABILITY_INPUT_MISSING')
                elif (not math.isclose(data['durabilityFloorHp'],1.5*data['visibleThreatHp'],abs_tol=1e-6)
                      or sum(own.get(uid,{}).get('hp',0) for uid in actors)<data['durabilityFloorHp']):
                    bad('CRISIS_DURABILITY_FLOOR_BREACHED')
                asset=own.get(data.get('assetId'))
                if asset is None: missing('CRISIS_ASSET_OBSERVATION_MISSING')
                elif not ready(asset): bad('CRISIS_ASSET_NOT_READY')
                crisis[data.get('taskId')]=dict(data,actors=actors)
            elif event == 'local_crisis_order':
                actors=ids(data); c=crisis.get(data.get('taskId'))
                if actors is None:continue
                if c is None: missing('CRISIS_START_NOT_RECORDED');continue
                if not actors<=c['actors'] or any(leases.get(uid)!=data.get('owner') for uid in actors):bad('CRISIS_ORDER_ACTOR_OR_OWNER_MISMATCH')
                record=receipt_for(data.get('requestId'),'/command/attack-move',actors,data.get('owner'))
                target=data.get('targetId')
                if data.get('phase')=='RESPOND':
                    metrics['crisisRespondOrders']+=1
                    if target!=c.get('targetId'):bad('CRISIS_TARGET_CHANGED_WITHOUT_NEW_TASK')
                    fresh_evidence(engagements.get(target),target,actors)
                elif data.get('phase')=='RETURN':
                    metrics['crisisReturnOrders']+=1
                    if target is not None:bad('CRISIS_RETURN_STILL_TARGETS_ENEMY')
                else:bad('CRISIS_ORDER_PHASE_UNKNOWN')
                if local_intent is None or ids(local_intent)!=actors or local_intent.get('taskId')!=data.get('taskId'):
                    missing('CRISIS_EXACT_INTENT_NOT_RECORDED')
                elif record:
                    q=record['query']; r=record['receipt']
                    for axis in ('X','Y'):
                        intent=local_intent.get('target'+axis); actual=q.get(axis.lower(),[None])[0]
                        if not number(intent) or actual is None:missing('CRISIS_TARGET_COORDINATES_MISSING');continue
                        try:value=float(actual)
                        except ValueError:bad('CRISIS_COMMAND_COORDINATE_INVALID');continue
                        if not math.isclose(intent,value,abs_tol=1e-3):bad('CRISIS_COMMAND_INTENT_COORDINATE_MISMATCH')
                        if not number(r.get('target'+axis)):missing('NATIVE_TARGET_COORDINATE_NOT_RECORDED')
                        elif not math.isclose(r['target'+axis],value,abs_tol=1e-3):bad('NATIVE_TARGET_COORDINATE_MISMATCH')
            elif event == 'local_crisis_finished': crisis.pop(data.get('taskId'),None)
            elif event == 'strategy_worker_committed': bindings[data.get('unitId')]=data
            elif event == 'strategy_construction_ordered' and data.get('product')=='amphibiousJet':
                uid=data.get('unitId'); evidence=data.get('evidence',{})
                record=receipt_for(endpoint='/command/construct',actor_ids={uid})
                metrics['providerJetConstructions']+=1
                if own.get(uid,{}).get('type')!='combatEngineer':bad('SUPPORT_PROVIDER_NOT_ENGINEER')
                if not number(evidence.get('cost')) or evidence.get('cost',0)<=0 or evidence.get('affordable') is not True:
                    missing('SUPPORT_NATIVE_QUOTE_NOT_RECORDED')
                if record and str(evidence.get('actionId')) != record['query'].get('actionId',[None])[0]:bad('SUPPORT_ACTION_NOT_NATIVE_QUOTE')
                provider_binding=bindings.get(uid)
                if provider_binding is None:missing('SUPPORT_PROVIDER_NEED_BINDING_MISSING')
                construction[data.get('taskId')]=dict(data,before=set(own),line=line,
                    needId=provider_binding.get('needId') if provider_binding else None)
            elif event == 'strategy_construction_observed' and data.get('product')=='amphibiousJet':
                product=data.get('unit',{}); uid=product.get('id'); build=construction.get(data.get('taskId'))
                if build is None:missing('SUPPORT_CONSTRUCTION_ACCEPTANCE_MISSING')
                elif uid in build['before']:bad('SUPPORT_PRODUCT_WAS_ALREADY_PRESENT')
                if build and data.get('builderId')!=build.get('unitId'):bad('SUPPORT_COMPLETION_PROVIDER_MISMATCH')
                if uid in matched_products:bad('SUPPORT_PRODUCT_ASSIGNED_TWICE')
                if (not ready(product) or product.get('type')!='amphibiousJet' or not ready(own.get(uid))
                        or own.get(uid,{}).get('type')!='amphibiousJet'):
                    bad('SUPPORT_PRODUCT_NOT_NEW_READY_OWN_UNIT')
                if build and near(product,{'x':build.get('x'),'y':build.get('y')}) is not None and near(product,{'x':build.get('x'),'y':build.get('y')})>=120:bad('SUPPORT_PRODUCT_OUTSIDE_OBSERVED_BUILD_AREA')
                matched_products.add(uid); metrics['observedSupportJets']+=1
            elif event == 'strategy_support_transferred':
                uid=data.get('responderId');metrics['supportTransfers']+=1
                if uid not in matched_products:bad('SUPPORT_TRANSFER_WITHOUT_READY_PRODUCT')
                if uid in support_responders:bad('SUPPORT_RESPONDER_REASSIGNED')
                support_responders.add(uid)
                binding=bindings.get(uid)
                if binding is None:missing('SUPPORT_RESPONDER_NEED_BINDING_MISSING')
                elif binding.get('needId')!=data.get('needId'):bad('SUPPORT_RESPONDER_NEED_MISMATCH')
                build=construction.get(data.get('taskId'))
                if build and (build.get('unitId')!=data.get('unitId') or build.get('needId')!=data.get('needId')):
                    bad('SUPPORT_PROVIDER_HANDOVER_NEED_MISMATCH')
            elif event == 'strategy_responder_mode_planned' and data.get('mode')=='DIVE':
                fresh_evidence(data.get('evidence'),data.get('targetId'),{data.get('unitId')},mode=True)
            elif event == 'strategy_responder_mode_ordered':
                uid=data.get('unitId'); record=receipt_for(endpoint='/command/unit-mode',actor_ids={uid})
                if data.get('mode')=='DIVE':
                    metrics['acceptedDiveModes']+=1
                    if mode_state.get(uid)=='WAIT':bad('DUPLICATE_DIVE_WHILE_WAITING_OBSERVATION')
                    evidence=data.get('evidence'); fresh_evidence(evidence,data.get('targetId'),{uid},mode=True)
                    actor=next((q for q in evidence.get('actors',[]) if q.get('unitId')==uid),{}) if isinstance(evidence,dict) else {}
                    if actor.get('modeActionReady') is not True:bad('DIVE_NATIVE_ACTION_NOT_READY')
                    if record and str(actor.get('modeActionId'))!=record['query'].get('actionId',[None])[0]:bad('DIVE_ACTION_NOT_NATIVE_MENU')
                    mode_state[uid]='WAIT'
                elif data.get('mode')=='FLY':mode_state[uid]='FLY'
            elif event == 'strategy_responder_mode_observed' and data.get('mode')=='DIVE':
                uid=data.get('unitId')
                if fresh_evidence(data.get('evidence'),data.get('targetId'),{uid}):
                    mode_state[uid]='COMPATIBLE';metrics['observedCompatibleDiveModes']+=1
            elif event in ('strategy_worker_commitment_released','strategy_task_lost'):
                if data.get('unitId') in mode_state:mode_state.pop(data['unitId'],None)
            elif event=='strategy_task_blocked' and data.get('reason')=='MODE_TRANSITION_NO_PROGRESS':
                mode_state.pop(data.get('unitId'),None)
            elif event == 'strategy_response_ordered' and own.get(data.get('unitId'),{}).get('type')=='amphibiousJet':
                uid=data['unitId'];metrics['jetResponseOrders']+=1
                receipt=data.get('receipt',{})
                receipt_for(receipt.get('requestId'),'/command/attack-move',{uid})
                if mode_state.get(uid)=='WAIT':bad('JET_ATTACK_BEFORE_COMPATIBLE_MODE_OBSERVATION')
                fresh_evidence(engagements.get(data.get('targetId')),data.get('targetId'),{uid})
            elif event == 'mine_income_investment_evaluated':check_mine(data)
            elif event == 'strategy_allocation' and str(data.get('selected','')).startswith('MINE_T'):
                check_mine(data,ordered=True);pending_mine=data
                quote=data.get('action',{})
                if quote.get('id')!=data.get('mineId') or quote.get('product')!=data.get('product') or quote.get('cost')!=data.get('nativeCost') or quote.get('affordable') is not True:
                    bad('MINE_ALLOCATION_NATIVE_QUOTE_MISMATCH')
            elif event == 'spend' and data.get('category')=='MINE_UPGRADE' and pending_mine:
                record=receipt_for(endpoint='/command/invest',actor_ids={pending_mine.get('mineId')})
                if record and record['query'].get('actionId',[None])[0]!=str(pending_mine.get('action',{}).get('actionId')):bad('MINE_INVEST_ACTION_NOT_NATIVE_QUOTE')
                if data.get('cost')!=pending_mine.get('nativeCost'):bad('MINE_SPEND_NOT_NATIVE_QUOTE')
                metrics['acceptedMineInvestments']+=1;pending_mine=None
            elif event == 'local_army_order':latest_local=dict(data,line=line)
            elif event == 'local_army_fairness_slot':
                metrics['fairnessSlots']+=1
                if not latest_local or data.get('cohortId')!=latest_local.get('cohortId') or data.get('gameTimeMs')!=latest_local.get('gameTimeMs'):
                    bad('FAIRNESS_SLOT_WITHOUT_ACCEPTED_LOCAL_ORDER')
            elif event == 'local_army_diagnostic':
                metrics['cohortDiagnostics']+=1
                age=data.get('lastAcceptedAgeMs')
                if number(age):
                    metrics['longestDiagnosticAcceptedAgeSeconds']=max(metrics['longestDiagnosticAcceptedAgeSeconds'],age/1000)
                    if number(data.get('unitsWithNoOrderAwayFromGoal')) and data['unitsWithNoOrderAwayFromGoal']>0:
                        metrics['longestNoOrderAcceptedAgeSeconds']=max(metrics['longestNoOrderAcceptedAgeSeconds'],age/1000)
                if not all(k in data for k in ('members','unitsWithNoOrder','legalTarget','frontierAvailable','globalGateReady','cooldownRemainingMs')):
                    missing('COHORT_DIAGNOSTIC_FIELDS_MISSING')
            elif event == 'summary':summary=data
    if (before.st_size,before.st_mtime_ns)!=(path.stat().st_size,path.stat().st_mtime_ns):integrity.append(dict(reason='SOURCE_CHANGED_DURING_READ'))
    if events['summary']!=1 or previous_event!='summary':integrity.append(dict(reason='TERMINAL_SUMMARY_MISSING_OR_MULTIPLE'))
    if path.name.endswith('.partial'):integrity.append(dict(reason='UNCOMMITTED_REPORT'))
    if pending_mine:missing('SELECTED_MINE_INVESTMENT_NOT_ACCEPTED_OR_SPENT')
    for branch,count in [('localCrisis',metrics['crisisStarted']),('crisisRespondAcceptance',metrics['crisisRespondOrders']),
                         ('providerJetConstruction',metrics['providerJetConstructions']),('readySupportProduct',metrics['observedSupportJets']),
                         ('supportNeedTransfer',metrics['supportTransfers']),('diveModeAcceptance',metrics['acceptedDiveModes']),
                         ('compatibleModeObservation',metrics['observedCompatibleDiveModes']),('jetResponseAcceptance',metrics['jetResponseOrders']),
                         ('minePaybackEvaluation',metrics['minePaybackEvaluations']),('acceptedMineInvestment',metrics['acceptedMineInvestments']),
                         ('cohortDiagnostics',metrics['cohortDiagnostics']),('fairnessGrant',metrics['fairnessSlots'])]:coverage[branch]='OBSERVED' if count else 'NOT_TRIGGERED'
    status='FAIL' if violations or integrity else 'INCOMPLETE' if gaps else 'PASS'
    return dict(schemaVersion=1,audit='FEEDBACK_PROGRESS_2026_10_01_V1',file=str(path.resolve()),sha256=digest.hexdigest(),bytes=size,
                status=status,reportedOutcome=summary.get('outcome','UNKNOWN'),provenance=provenance,
                coverage=coverage,metrics=metrics,eventCounts=dict(events),violationCounts=dict(reasons),violations=violations,
                integrityIssues=integrity,evidenceGaps=dict(gaps),limitations=[
                    'Recorded evidence only. Missing triggered proof is INCOMPLETE; absent branches are NOT_TRIGGERED.',
                    'Native compatibility is not attack/kill success. Observed role matching does not prove producer birthplace.',
                    'Quiet windows and payback models do not predict survival. No win-rate or causal combat-benefit claim.',
                    'Order age plus sampled missing orders is diagnostic; timing alone does not prove idleness or gate cause.'])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('reports',nargs='+',type=Path)
    parser.add_argument('--out',type=Path)
    args=parser.parse_args()
    results=[audit(p) for p in args.reports]
    status='FAIL' if any(r['status']=='FAIL' for r in results) else 'INCOMPLETE' if any(r['status']=='INCOMPLETE' for r in results) else 'PASS'
    output=json.dumps(dict(schemaVersion=1,status=status,reports=results),ensure_ascii=False,indent=2)+'\n'
    if args.out:
        args.out.parent.mkdir(parents=True,exist_ok=True);args.out.write_text(output,encoding='utf-8')
    else:print(output,end='')
    return {'PASS':0,'FAIL':1,'INCOMPLETE':2}[status]


if __name__=='__main__':raise SystemExit(main())
