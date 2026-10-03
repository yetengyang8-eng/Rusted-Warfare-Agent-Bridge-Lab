"""Independent evidence checks for completed and interrupted battle reports."""
import math
import struct
from decimal import Decimal, ROUND_HALF_UP, InvalidOperation
from urllib.parse import urlsplit,parse_qs

def native_coordinate_matches(requested,recorded):
    """Match the frozen bridge's Float.parseFloat followed by Locale.US %.3f.

    Reproduce its quantization; do not grant a broad coordinate-error tolerance.
    Identity, actor lists and observation provenance are still checked separately.
    """
    try:
        if isinstance(requested,bool) or isinstance(recorded,bool):return False
        value=float(requested)
        if not math.isfinite(value) or not isinstance(recorded,(int,float)) or not math.isfinite(recorded):return False
        native=struct.unpack('!f',struct.pack('!f',value))[0]
        expected=float(Decimal.from_float(native).quantize(Decimal('.001'),rounding=ROUND_HALF_UP))
        return recorded==expected
    except (TypeError,ValueError,OverflowError,InvalidOperation):return False

def validate_battle(rows,summary,issue):
    state=None;enemies=None;plan=None;intent=None;action=None
    cohort_members={};cohort_plans={};accepted_frontiers={}
    intent_frontier=None;action_intent=None;action_frontier=None
    session=None;last_time=-1;last_action=None;initial=None
    receipts={};confirmed=set();new=set();losses=set();upgrades=set()
    # Units that are known to be ours. combat_unit_observed only marks armed mobile units, so it can
    # never be the sole birth evidence for mines and factories; those are known from any observation
    # they appear in, or from the completion the client recorded for them.
    seen=set()
    for row in rows:
        event,d=row['event'],row['data']
        if event=='observation':
            if session is None:session=d.get('sessionId')
            if d.get('sessionId')!=session:issue('BATTLE_SESSION_CHANGED','observation')
            t=d.get('gameTimeMs')
            if not isinstance(t,(int,float)) or t<last_time:issue('BATTLE_TIME_INVALID',str(t))
            else:last_time=t
            state=d
            if initial is None:initial={u.get('id') for u in d.get('ownUnits',[])}
            seen.update(u.get('id') for u in d.get('ownUnits',[]))
        elif event=='combat_observation':
            enemies=d
            if d.get('sessionId')!=session:issue('BATTLE_SESSION_CHANGED','combat observation')
        elif event=='army_frontier_plan':plan=d
        elif event=='local_army_membership':
            cohort=d.get('cohortId')
            if d.get('reason')=='RELEASED_BELOW_THREE':
                cohort_members.pop(cohort,None);cohort_plans.pop(cohort,None);accepted_frontiers.pop(cohort,None)
            else:cohort_members[cohort]=set(d.get('unitIds',[]))
        elif event=='local_army_mode' and d.get('active') is False:
            cohort_plans.clear()
            accepted_frontiers={key:value for key,value in accepted_frontiers.items() if key is None}
        elif event=='local_army_frontier_plan':
            # The native planner response has an own anchor, not a cohort label. Bind it through
            # recorded own membership so another group's most recent plan cannot approve this one.
            anchor=d.get('anchorUnitId',d.get('builderId'))
            owners=[key for key,members in cohort_members.items() if anchor in members]
            if len(owners)==1:
                cohort=owners[0]
                if d.get('cohortId',cohort)==cohort:cohort_plans[cohort]=d
            elif d.get('status')!='planned':cohort_plans.clear()
        elif event in ('army_frontier_arrived','army_frontier_blocked','local_army_frontier_arrived','local_army_frontier_blocked'):
            accepted_frontiers.pop(d.get('cohortId'),None)
        elif event=='tactical_intent':
            intent=d;intent_frontier=None
            if d.get('reason') in ('OBSERVED_ENEMY','REMOTE_VISIBLE_CONTACT'):
                enemy=d.get('enemy') or {}
                old=next((u for u in (enemies or {}).get('rememberedEnemies',[]) if u.get('id')==enemy.get('id')),None)
                if old is None or any(old.get(k)!=enemy.get(k) for k in ('x','y','lastSeenGameTimeMs')):
                    issue('BATTLE_TARGET_NOT_OBSERVED',str(enemy.get('id')))
                if d.get('reason')=='REMOTE_VISIBLE_CONTACT':
                    visible=next((u for u in (enemies or {}).get('visibleEnemies',[]) if u.get('id')==enemy.get('id')),None)
                    if visible is None or visible.get('lastSeenGameTimeMs')!=(enemies or {}).get('gameTimeMs'):
                        issue('BATTLE_TARGET_NOT_OBSERVED','remote contact must be currently visible')
                if enemy.get('x')!=d.get('targetX') or enemy.get('y')!=d.get('targetY'):
                    issue('BATTLE_TARGET_COORDINATES','enemy intent')
            if d.get('reason')=='KNOWN_FRONTIER':
                cohort=d.get('cohortId')
                candidate=plan if cohort is None else cohort_plans.pop(cohort,None)
                if (not candidate or candidate.get('status')!='planned' or candidate.get('pathKnown') is not True
                    or any(candidate.get(k)!=d.get(k) for k in ('targetX','targetY'))
                    or (cohort is not None and candidate.get('anchorUnitId',candidate.get('builderId')) not in d.get('unitIds',[]))):
                    issue('BATTLE_FRONTIER_NOT_PLANNED','intent')
                else:intent_frontier=candidate
            elif d.get('reason')=='REINFORCE':
                accepted=accepted_frontiers.get(d.get('cohortId'))
                if not accepted or any(accepted.get(k)!=d.get(k) for k in ('targetX','targetY')):
                    issue('BATTLE_REINFORCE_NO_ACCEPTED_FRONTIER',str(d.get('cohortId')))
                else:intent_frontier=accepted
        elif event=='action':
            t=d.get('gameTimeMs')
            if not isinstance(t,(int,float)) or (last_action is not None and t-last_action<1000):
                issue('BATTLE_COMMAND_RATE','minimum gap is 1000 game ms')
            last_action=t;action=urlsplit(d.get('path',''));action_intent=intent;action_frontier=intent_frontier
        elif event=='command_rejected':
            # Intent and a rejected POST are not an accepted frontier. In particular a later
            # bookkeeping event must not borrow the rejected action's identity or grant a goal.
            action=None;action_intent=None;action_frontier=None;intent=None;intent_frontier=None
        elif event=='command_result' and d.get('status')=='queued':
            if action is None:issue('BATTLE_RECEIPT_NO_ACTION',str(d.get('requestId')));continue
            q=parse_qs(action.query)
            identity_ok=q.get('requestId')==[d.get('requestId')] and q.get('sessionId')==[session] and d.get('sessionId')==session
            if not identity_ok:
                issue('BATTLE_RECEIPT_IDENTITY',str(d.get('requestId')))
            if action.path=='/command/attack-move':
                try:
                    ids=[int(i) for i in q['unitIds'][0].split(',')]
                    receipt_ok=ids==d.get('unitIds') and native_coordinate_matches(q['x'][0],d.get('targetX')) and native_coordinate_matches(q['y'][0],d.get('targetY'))
                    if not receipt_ok:
                        issue('BATTLE_RECEIPT_MISMATCH','attack move')
                    intent_ok=action_intent is not None and all(native_coordinate_matches(action_intent.get(k),d.get(k)) for k in ('targetX','targetY'))
                    if not intent_ok:
                        issue('BATTLE_ATTACK_NO_INTENT',str(d.get('requestId')))
                    cohort=(action_intent or {}).get('cohortId')
                    if cohort is not None and (ids!=(action_intent or {}).get('unitIds') or not set(ids)<=cohort_members.get(cohort,set())):
                        issue('BATTLE_COHORT_ACTORS_NOT_MEMBERS',str(cohort));intent_ok=False
                    if identity_ok and receipt_ok and intent_ok:
                        if action_frontier is not None:accepted_frontiers[cohort]=action_frontier
                        else:accepted_frontiers.pop(cohort,None)
                    receipts[d['requestId']]=d
                except (KeyError,ValueError,TypeError):issue('BATTLE_INVALID_ATTACK','receipt')
            action=None;action_intent=None;action_frontier=None;intent=None;intent_frontier=None
        elif event=='attack_order_confirmed':
            receipt=receipts.get(d.get('requestId'));ids=d.get('unitIds',[])
            if not receipt or not ids or d.get('requestId') in confirmed:issue('BATTLE_CONFIRMATION_INVALID',str(d.get('requestId')));continue
            confirmed.add(d.get('requestId'))
            for uid in ids:
                u=next((u for u in (state or {}).get('ownUnits',[]) if u.get('id')==uid),None)
                if (uid not in receipt.get('unitIds',[]) or u is None or u.get('dead') or u.get('orderType')!='attackMove'
                    or any(not isinstance(u.get(k),(int,float)) for k in ('orderX','orderY'))
                    or math.hypot(u['orderX']-receipt['targetX'],u['orderY']-receipt['targetY'])>=1):
                    issue('BATTLE_ATTACK_NOT_EXECUTED',str(uid))
        elif event=='combat_unit_observed':
            uid=d.get('id');u=next((u for u in (state or {}).get('ownUnits',[]) if u.get('id')==uid),None)
            if uid in (initial or set()) or uid in new or u is None or u.get('dead') or not u.get('canAttack') or not u.get('mobile') or u.get('buildProgress',0)<1:
                issue('BATTLE_NEW_UNIT_NOT_OBSERVED',str(uid))
            new.add(uid)
        elif event in ('extractor_completed','factory_completed','tank_completed'):
            # A completed own unit is ours even if it never survived into an observation.
            seen.add(d.get('unitId'))
        elif event=='own_loss':
            uid=d.get('unitId');u=next((u for u in (state or {}).get('ownUnits',[]) if u.get('id')==uid),None)
            if uid in losses or uid not in seen or (u and not u.get('dead') and u.get('hp',0)>0):
                issue('BATTLE_LOSS_NOT_OBSERVED',str(uid))
            losses.add(uid)
        elif event=='upgrade_completed':
            uid=d.get('factoryId');u=next((u for u in (state or {}).get('ownUnits',[]) if u.get('id')==uid),None)
            if u is None or u.get('techLevel')!=d.get('tier') or d.get('tier',0)<2:issue('BATTLE_UPGRADE_NOT_OBSERVED',str(uid))
            upgrades.add((uid,d.get('tier')))
        elif event=='match_terminal':
            native=(state or {}).get('match',{})
            if d!=native:issue('BATTLE_RESULT_NOT_OBSERVED','terminal event differs from latest state')
    for key,actual in {'ownLosses':len(losses),'newCombatUnits':len(new),'attackOrders':len(receipts),
                       'attackOrdersConfirmed':len(confirmed),'upgradesCompleted':len(upgrades),
                       'retreatOrders':sum(r['event']=='combat_retreat' for r in rows)}.items():
        if summary.get(key)!=actual:issue('BATTLE_COUNT_MISMATCH',key)
    terminals=[r['data'] for r in rows if r['event']=='match_terminal']
    if summary.get('outcome')=='PASS':
        outcome=summary.get('matchOutcome');native=(state or {}).get('match',{})
        if (len(terminals)!=1 or outcome not in ('VICTORY','DEFEAT') or native.get('outcome')!=outcome
            or native.get('source')!='native_result_screen'
            or (outcome=='VICTORY' and (native.get('nativeVictory') is not True or native.get('nativeDefeat') is not False))
            or (outcome=='DEFEAT' and native.get('nativeDefeat') is not True)):
            issue('BATTLE_NO_NATIVE_TERMINAL','PASS requires a native VICTORY or DEFEAT, not a time limit')
