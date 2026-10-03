import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
from audit_feedback_progress import audit
from audit_operations import audit as operations_audit, MINE_INCOME_MODEL, MINE_SURVIVAL_MODEL


def unit(uid, kind='heavyTank', **extra):
    return dict(id=uid,type=kind,hp=600,maxHp=600,buildProgress=1,productionQueue=0,
                mobile=True,canAttack=True,dead=False,x=0,y=0,orderType=None,**extra)


def observation(own, time=1000):
    return ('observation',dict(gameTimeMs=time,sessionId='fixture',ownUnits=own,
                               player=dict(teamId=0,credits=10000)))


def command(path, request, actors, owner='rule-main', **receipt):
    sep='&' if '?' in path else '?'
    return [('action',dict(owner=owner,path=path+sep+'requestId='+request,gameTimeMs=1000)),
            ('command_result',dict(status='queued',requestId=request,**actors,**receipt))]


def engagement(uid=1, target=200, time=1000, mode=False):
    actor=dict(unitId=uid,compatibility='COMPATIBLE',status='APPROACH_PATH_KNOWN')
    if mode:actor.update(compatibility='INCOMPATIBLE',requiredMode='DIVE',
                         modeApproachStatus='APPROACH_PATH_KNOWN',modeActionReady=True,modeActionId='152')
    return dict(sessionId='fixture',gameTimeMs=time,targetId=target,targetVisible=True,targetObservedAtGameTimeMs=time,
                targetX=200,targetY=100,actors=[actor])


class FeedbackAuditTests(unittest.TestCase):
    def run_rows(self, rows):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'battle-fixture.jsonl'
            path.write_text(''.join(json.dumps(dict(event=e,data=d,wallTimeMs=i))+'\n'
                                    for i,(e,d) in enumerate(rows)),encoding='utf-8')
            return audit(path)

    @staticmethod
    def crisis_rows():
        own=[unit(i) for i in range(1,13)]+[unit(50,'extractorT1')]
        evidence=engagement();evidence['actors'].append(engagement(uid=2)['actors'][0])
        rows=[observation(own),('local_army_membership',dict(cohortId=1,unitIds=list(range(1,13))))]
        rows += [('task_ownership_acquired',dict(taskId=-i,crisisTaskId=7,unitId=i,owner='local-crisis:7')) for i in (1,2)]
        rows += [('local_crisis_engagement',evidence),('local_crisis_started',dict(taskId=7,owner='local-crisis:7',
                 targetId=200,assetId=50,unitIds=[1,2],visibleThreats=1,visibleThreatHp=300,
                 durabilityFloorHp=450,mainRemaining=10,gameTimeMs=1000)),
                 ('tactical_intent',dict(taskId=7,owner='local-crisis:7',unitIds=[1,2],targetX=200,targetY=100))]
        rows += command('/command/attack-move?unitIds=1,2&x=200&y=100','crisis-1',dict(unitIds=[1,2]),
                        owner='local-crisis:7',targetX=200,targetY=100)
        rows += [('local_crisis_order',dict(taskId=7,owner='local-crisis:7',unitIds=[1,2],targetId=200,
                  phase='RESPOND',requestId='crisis-1',gameTimeMs=1000)),('summary',dict(outcome='PARTIAL'))]
        return rows

    @staticmethod
    def provider_rows():
        own=[unit(10,'combatEngineer')]+[unit(i) for i in range(1,9)]
        quote=dict(actionId='u_amphibiousJet',cost=2000,affordable=True)
        rows=[observation(own),('task_ownership_acquired',dict(taskId=77,unitId=10,owner='strategy:77')),
              ('strategy_worker_committed',dict(taskId=77,unitId=10,needId=200,reason='REAR_CAPABILITY_PROVIDER'))]
        rows += command('/command/construct?unitId=10&actionId=u_amphibiousJet&x=0&y=0','build-1',dict(unitId=10),owner='strategy:77')
        rows += [('strategy_construction_ordered',dict(taskId=77,unitId=10,product='amphibiousJet',x=0,y=0,evidence=quote))]
        jet=unit(30,'amphibiousJet')
        rows += [observation(own+[jet],time=2000),('strategy_construction_observed',dict(taskId=77,builderId=10,product='amphibiousJet',unit=jet)),
                 ('task_ownership_acquired',dict(taskId=78,unitId=30,owner='strategy:78')),
                 ('strategy_worker_committed',dict(taskId=78,unitId=30,needId=200,reason='OBSERVED_SUPPORT_RESPONDER')),
                 ('strategy_support_transferred',dict(taskId=77,unitId=10,responderId=30,needId=200))]
        mode=engagement(uid=30,time=2000,mode=True)
        rows += [('response_engagement_observation',mode),('strategy_responder_mode_planned',dict(taskId=78,unitId=30,targetId=200,mode='DIVE',evidence=mode))]
        rows += command('/command/unit-mode?unitId=30&actionId=152','mode-1',dict(unitId=30),owner='strategy:78',mode='DIVE')
        rows += [('strategy_responder_mode_ordered',dict(taskId=78,unitId=30,targetId=200,mode='DIVE',evidence=mode)),observation(own+[jet],time=3000)]
        compatible=engagement(uid=30,time=3000)
        rows += [('response_engagement_observation',compatible),('strategy_responder_mode_observed',dict(taskId=78,unitId=30,targetId=200,mode='DIVE',evidence=compatible))]
        rows += command('/command/attack-move?unitIds=30&x=200&y=100','jet-1',dict(unitIds=[30]),owner='strategy:78',targetX=200,targetY=100)
        rows += [('strategy_response_ordered',dict(taskId=78,unitId=30,targetId=200,receipt=dict(requestId='jet-1'))),('summary',dict(outcome='PARTIAL'))]
        return rows

    @staticmethod
    def mine_rows(t3=False):
        source,product,gain,conversion,margin,quiet=('extractorT2','extractorT3',12.07,54,180,180) if t3 else ('extractorT1','extractorT2',6.035,28,90,45)
        cost=4700 if t3 else 1700  # Deliberately different from frozen prices.
        evidence=dict(mineId=20,sourceType=source,product=product,nativeCost=cost,ordinaryUnitNativeCost=800,
                      credits=10000,allReserved=700,requiredCredits=cost+700+1600,
                      observedLocalClearGameSeconds=quiet+10,requiredLocalClearGameSeconds=quiet,
                      incomeGainEstimate=gain,paybackEstimateGameSeconds=cost/gain,
                      upgradeTimeEstimateGameSeconds=conversion,survivalMarginGameSeconds=margin,
                      requiredRemainingGameSeconds=cost/gain+conversion+margin,remainingGameSeconds=2000,
                      armed=12,militaryFloor=6,cheaperSiteReady=False,selected=True,reason='LOCAL_PAYBACK_WINDOW_ACCEPTED')
        quote=dict(id=20,type=source,product=product,cost=cost,affordable=True,actionId='native_upgrade')
        allocation=dict(evidence,selected='MINE_T3_INCOME_INVESTMENT' if t3 else 'MINE_T2_INCOME_INVESTMENT',action=quote)
        rows=[observation([unit(20,source)]),('mine_income_investment_evaluated',evidence),('strategy_allocation',allocation)]
        rows += command('/command/invest?unitId=20&actionId=native_upgrade','mine-1',dict(unitId=20))
        rows += [('spend',dict(category='MINE_UPGRADE',cost=cost)),('summary',dict(outcome='PARTIAL'))]
        return rows

    @staticmethod
    def mutate(rows,event,key,value):
        rows=copy.deepcopy(rows)
        next(d for e,d in rows if e==event)[key]=value
        return rows

    def assert_violation(self, rows, reason):
        result=self.run_rows(rows)
        self.assertEqual(result['status'],'FAIL',result)
        self.assertIn(reason,result['violationCounts'])

    def test_complete_crisis_exact_accepted_order(self):
        result=self.run_rows(self.crisis_rows());self.assertEqual(result['status'],'PASS',result)
        self.assertEqual(result['metrics']['crisisRespondOrders'],1)

    def test_crisis_responder_bound(self):
        self.assert_violation(self.mutate(self.crisis_rows(),'local_crisis_started','unitIds',[1]),'CRISIS_RESPONSE_BOUNDS_OR_MAIN_RESERVE_BREACHED')

    def test_crisis_main_reserve(self):
        self.assert_violation(self.mutate(self.crisis_rows(),'local_crisis_started','mainRemaining',5),'CRISIS_RESPONSE_BOUNDS_OR_MAIN_RESERVE_BREACHED')

    def test_crisis_cohort_reserve(self):
        self.assert_violation(self.mutate(self.crisis_rows(),'local_army_membership','unitIds',[1,2,3,4]),'CRISIS_COHORT_RESERVE_BREACHED')

    def test_crisis_owner_theft(self):
        self.assert_violation(self.mutate(self.crisis_rows(),'action','owner','rule-main'),'COMMAND_STOLE_LEASE')

    def test_crisis_exact_actor_receipt(self):
        self.assert_violation(self.mutate(self.crisis_rows(),'command_result','unitIds',[1,3]),'NATIVE_RECEIPT_ACTORS_MISMATCH')

    def test_crisis_exact_target_coordinate_receipt(self):
        self.assert_violation(self.mutate(self.crisis_rows(),'command_result','targetX',999),'NATIVE_TARGET_COORDINATE_MISMATCH')

    def test_crisis_current_contact_required(self):
        rows=self.crisis_rows();next(d for e,d in rows if e=='local_crisis_engagement')['targetVisible']=False
        self.assert_violation(rows,'ENGAGEMENT_TARGET_NOT_CURRENT')

    def test_crisis_freshness_required(self):
        rows=self.crisis_rows();next(d for e,d in rows if e=='local_crisis_engagement')['targetObservedAtGameTimeMs']=999
        self.assert_violation(rows,'ENGAGEMENT_EVIDENCE_STALE')

    def test_async_native_query_newer_than_decision_stamp_is_valid(self):
        rows=self.crisis_rows();d=next(d for e,d in rows if e=='local_crisis_engagement')
        d['gameTimeMs']=d['targetObservedAtGameTimeMs']=1500
        self.assertEqual(self.run_rows(rows)['status'],'PASS')

    def test_contact_must_be_current_to_async_query(self):
        rows=self.crisis_rows();next(d for e,d in rows if e=='local_crisis_engagement')['gameTimeMs']=1500
        self.assert_violation(rows,'ENGAGEMENT_CONTACT_NOT_CURRENT_TO_QUERY')

    def test_native_query_session_matches_current_own(self):
        rows=self.crisis_rows();next(d for e,d in rows if e=='local_crisis_engagement')['sessionId']='foreign'
        self.assert_violation(rows,'ENGAGEMENT_SESSION_MISMATCH')

    def test_query_time_missing_is_explicit_gap(self):
        rows=self.crisis_rows();del next(d for e,d in rows if e=='local_crisis_engagement')['gameTimeMs']
        result=self.run_rows(rows);self.assertEqual(result['status'],'INCOMPLETE')
        self.assertIn('ENGAGEMENT_QUERY_TIME_MISSING',result['evidenceGaps'])

    def test_unknown_is_not_compatible(self):
        rows=self.crisis_rows();next(d for e,d in rows if e=='local_crisis_engagement')['actors'][0]['compatibility']='UNKNOWN'
        self.assert_violation(rows,'RESPONSE_NOT_COMPATIBLE_KNOWN_APPROACH')

    def test_missing_engagement_is_incomplete_not_pass(self):
        result=self.run_rows([r for r in self.crisis_rows() if r[0]!='local_crisis_engagement'])
        self.assertEqual(result['status'],'INCOMPLETE');self.assertIn('ENGAGEMENT_EVIDENCE_MISSING',result['evidenceGaps'])

    def test_owner_conflict(self):
        rows=self.crisis_rows();rows.insert(4,('task_ownership_acquired',dict(unitId=1,owner='different')))
        self.assert_violation(rows,'ACTOR_LEASE_CONFLICT')

    def test_legacy_recon_owner_still_blocks_main_theft(self):
        rows=[observation([unit(1)]),('task_ownership_acquired',dict(taskId=3,unitId=1))]
        rows += command('/command/attack-move?unitIds=1&x=200&y=100','main-1',dict(unitIds=[1]),targetX=200,targetY=100)
        rows += [('summary',dict(outcome='PARTIAL'))]
        self.assert_violation(rows,'COMMAND_STOLE_LEASE')

    def test_legacy_recon_release_uses_same_canonical_owner(self):
        rows=[observation([unit(1)]),('task_ownership_acquired',dict(taskId=3,unitId=1)),
              ('task_ownership_released',dict(taskId=3,unitId=1))]
        rows += command('/command/attack-move?unitIds=1&x=200&y=100','main-1',dict(unitIds=[1]),targetX=200,targetY=100)
        rows += [('summary',dict(outcome='PARTIAL'))]
        self.assertEqual(self.run_rows(rows)['status'],'PASS')

    def test_provider_ready_mode_and_response_chain(self):
        result=self.run_rows(self.provider_rows());self.assertEqual(result['status'],'PASS',result)
        self.assertEqual(result['metrics']['observedSupportJets'],1);self.assertEqual(result['metrics']['jetResponseOrders'],1)

    def test_unfinished_product_not_ready(self):
        rows=self.provider_rows();next(d for e,d in rows if e=='strategy_construction_observed')['unit']['buildProgress']=.7
        self.assert_violation(rows,'SUPPORT_PRODUCT_NOT_NEW_READY_OWN_UNIT')

    def test_product_already_present(self):
        rows=self.provider_rows();rows[0][1]['ownUnits'].append(unit(30,'amphibiousJet'))
        self.assert_violation(rows,'SUPPORT_PRODUCT_WAS_ALREADY_PRESENT')

    def test_unique_product_matching(self):
        rows=self.provider_rows();i=next(i for i,r in enumerate(rows) if r[0]=='strategy_construction_observed');rows.insert(i+1,copy.deepcopy(rows[i]))
        self.assert_violation(rows,'SUPPORT_PRODUCT_ASSIGNED_TWICE')

    def test_transfer_without_ready(self):
        self.assert_violation([r for r in self.provider_rows() if r[0]!='strategy_construction_observed'],'SUPPORT_TRANSFER_WITHOUT_READY_PRODUCT')

    def test_need_binding_exact(self):
        self.assert_violation(self.mutate(self.provider_rows(),'strategy_support_transferred','needId',999),'SUPPORT_RESPONDER_NEED_MISMATCH')

    def test_mode_native_quote_not_hardcoded(self):
        rows=self.provider_rows();next(d for e,d in rows if e=='strategy_responder_mode_ordered')['evidence']['actors'][0]['modeActionId']='different'
        self.assert_violation(rows,'DIVE_ACTION_NOT_NATIVE_MENU')

    def test_mode_requires_native_readiness(self):
        rows=self.provider_rows();next(d for e,d in rows if e=='strategy_responder_mode_ordered')['evidence']['actors'][0]['modeActionReady']=False
        self.assert_violation(rows,'DIVE_NATIVE_ACTION_NOT_READY')

    def test_mode_wait_deduplicated(self):
        rows=self.provider_rows();i=next(i for i,r in enumerate(rows) if r[0]=='strategy_responder_mode_ordered');rows.insert(i+1,copy.deepcopy(rows[i]))
        self.assert_violation(rows,'DUPLICATE_DIVE_WHILE_WAITING_OBSERVATION')

    def test_attack_waits_for_compatible_mode(self):
        self.assert_violation([r for r in self.provider_rows() if r[0]!='strategy_responder_mode_observed'],'JET_ATTACK_BEFORE_COMPATIBLE_MODE_OBSERVATION')

    def test_t2_real_quote_payback(self):
        result=self.run_rows(self.mine_rows());self.assertEqual(result['status'],'PASS',result);self.assertEqual(result['metrics']['acceptedMineInvestments'],1)

    def test_t3_real_quote_payback(self):
        self.assertEqual(self.run_rows(self.mine_rows(t3=True))['status'],'PASS')

    def test_mine_budget_reserves(self):
        self.assert_violation(self.mutate(self.mine_rows(),'mine_income_investment_evaluated','credits',3999),'MINE_RESERVES_OR_REPLACEMENTS_BREACHED')

    def test_mine_model_gain_exact(self):
        self.assert_violation(self.mutate(self.mine_rows(),'mine_income_investment_evaluated','incomeGainEstimate',99),'MINE_PAYBACK_HORIZON_INVALID')

    def test_mine_time_budget_strictly_longer(self):
        rows=self.mine_rows();e=next(d for n,d in rows if n=='mine_income_investment_evaluated');e['remainingGameSeconds']=e['requiredRemainingGameSeconds']
        self.assert_violation(rows,'MINE_PAYBACK_HORIZON_INVALID')

    def test_mine_local_quiet_window(self):
        self.assert_violation(self.mutate(self.mine_rows(t3=True),'mine_income_investment_evaluated','observedLocalClearGameSeconds',179),'MINE_LOCAL_QUIET_OR_MILITARY_FLOOR_INVALID')

    def test_mine_spend_uses_actual_quote(self):
        self.assert_violation(self.mutate(self.mine_rows(),'spend','cost',1400),'MINE_SPEND_NOT_NATIVE_QUOTE')

    def test_mine_unknown_quote_refusal_valid(self):
        result=self.run_rows([('mine_income_investment_evaluated',dict(selected=False,nativeCost=-1,reason='NATIVE_QUOTE_UNKNOWN')),('summary',dict(outcome='PARTIAL'))])
        self.assertEqual(result['status'],'PASS');self.assertEqual(result['coverage']['acceptedMineInvestment'],'NOT_TRIGGERED')

    def test_missing_mine_model_does_not_pass(self):
        rows=self.mine_rows();del next(d for e,d in rows if e=='mine_income_investment_evaluated')['incomeGainEstimate']
        self.assertEqual(self.run_rows(rows)['status'],'INCOMPLETE')

    def test_fairness_requires_acceptance(self):
        self.assert_violation([('local_army_fairness_slot',dict(cohortId=1,gameTimeMs=1000)),('summary',dict(outcome='PARTIAL'))],'FAIRNESS_SLOT_WITHOUT_ACCEPTED_LOCAL_ORDER')

    def test_diagnostic_not_a_victory_or_idle_claim(self):
        rows=[('local_army_order',dict(cohortId=1,gameTimeMs=1000)),('local_army_fairness_slot',dict(cohortId=1,gameTimeMs=1000)),
              ('local_army_diagnostic',dict(cohortId=1,members=12,unitsWithNoOrder=10,unitsWithNoOrderAwayFromGoal=9,
                 lastAcceptedAgeMs=33000,legalTarget='COMPATIBLE',frontierAvailable='ACCEPTED_ACTIVE',globalGateReady=False,cooldownRemainingMs=0)),
              ('summary',dict(outcome='PARTIAL'))]
        result=self.run_rows(rows);self.assertEqual(result['status'],'PASS');self.assertEqual(result['reportedOutcome'],'PARTIAL')
        self.assertEqual(result['metrics']['longestNoOrderAcceptedAgeSeconds'],33)

    def test_truncated_report_integrity(self):
        self.assertEqual(self.run_rows(self.crisis_rows()[:-1])['status'],'FAIL')

    def test_unknown_branches_not_triggered(self):
        result=self.run_rows([('summary',dict(outcome='PARTIAL'))]);self.assertEqual(result['status'],'PASS')
        self.assertTrue(all(q=='NOT_TRIGGERED' for q in result['coverage'].values()))


class OperationsMineSchemaTests(unittest.TestCase):
    def run_deferral(self, data):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'battle-fixture.jsonl'
            rows=[('mine_income_investment_deferred',data),('summary',dict(outcome='PARTIAL'))]
            path.write_text(''.join(json.dumps(dict(event=e,data=d,wallTimeMs=i))+'\n'
                                    for i,(e,d) in enumerate(rows)),encoding='utf-8')
            return operations_audit(path)

    @staticmethod
    def legacy():
        return dict(reason='INCOME_ALREADY_SURPLUS_NEAR_ARMY_TARGET',credits=20000,allReserved=700,
                    nativeUpgradeCost=1400,ordinaryUnitNativeCost=800,incomeEstimate=100,
                    productionConsumption=50,armyDeficit=2,nearTargetDeficitLimit=3,
                    cashBuffer=3000,cashAfterInvestment=17900)

    @staticmethod
    def payback():
        data=copy.deepcopy(next(d for e,d in FeedbackAuditTests.mine_rows() if e=='mine_income_investment_evaluated'))
        data.update(selected=False,reason='LOCAL_CLEAR_WINDOW_TOO_SHORT',observedLocalClearGameSeconds=0,
                    incomeModel=MINE_INCOME_MODEL,survivalModel=MINE_SURVIVAL_MODEL)
        return data

    def test_old_saturation_schema_remains_strict_and_passes(self):
        result=self.run_deferral(self.legacy());self.assertEqual(result['status'],'PASS',result)
        self.assertEqual(result['metrics']['legacyMineSaturationHolds'],1)
        self.assertEqual(result['coverage']['legacyMineSaturationContract'],'OBSERVED')

    def test_old_saturation_invalid_buffer_still_fails(self):
        data=self.legacy();data['cashBuffer']=3001
        result=self.run_deferral(data);self.assertEqual(result['status'],'FAIL')
        self.assertIn('SURPLUS_MINE_HOLD_CONTRACT_MISMATCH',result['violationCounts'])

    def test_new_payback_schema_has_no_old_deficit_false_positive(self):
        result=self.run_deferral(self.payback());self.assertEqual(result['status'],'PASS',result)
        self.assertEqual(result['violationCounts'],{})
        self.assertIn('MINE_PAYBACK_CONTRACT_REQUIRES_FEEDBACK_PROGRESS_AUDIT',result['evidenceGaps'])
        self.assertEqual(result['coverage']['minePaybackDeferral'],'SCHEMA_ONLY_REQUIRES_FEEDBACK_PROGRESS_AUDIT')

    def test_new_unknown_quote_refusal_preserves_minus_one(self):
        data=self.payback();data.update(nativeCost=-1,paybackEstimateGameSeconds=-1,reason='NATIVE_QUOTE_UNKNOWN')
        self.assertEqual(self.run_deferral(data)['status'],'PASS')

    def test_new_unknown_reason_is_rejected(self):
        data=self.payback();data['reason']='UNVERIFIED_SAFE_TO_BUY'
        result=self.run_deferral(data);self.assertEqual(result['status'],'FAIL')
        self.assertIn('MINE_PAYBACK_DEFERRAL_REASON_UNKNOWN',result['violationCounts'])

    def test_new_accepted_purchase_cannot_be_logged_as_deferral(self):
        data=self.payback();data['selected']=True
        result=self.run_deferral(data);self.assertEqual(result['status'],'FAIL')
        self.assertIn('MINE_PAYBACK_DEFERRAL_SCHEMA_MISMATCH',result['violationCounts'])

    def test_new_model_name_must_match_recorded_contract(self):
        data=self.payback();data['survivalModel']='PREDICTED_SURVIVAL'
        result=self.run_deferral(data);self.assertEqual(result['status'],'FAIL')
        self.assertIn('MINE_PAYBACK_DEFERRAL_SCHEMA_MISMATCH',result['violationCounts'])


if __name__=='__main__':unittest.main()
