import copy,sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'tools'))
from battle_reports import validate_battle, native_coordinate_matches

def sample(outcome='VICTORY'):
    match={'outcome':outcome,'nativeVictory':outcome=='VICTORY','nativeDefeat':outcome=='DEFEAT','source':'native_result_screen'}
    unit={'id':9,'type':'c_tank','hp':200,'dead':False,'mobile':True,'canAttack':True,'buildProgress':1,'orderType':'attackMove','orderX':100,'orderY':200}
    rows=[('observation',{'sessionId':'s','gameTimeMs':0,'ownUnits':[unit],'match':{'outcome':'ONGOING'}}),
          ('army_frontier_plan',{'status':'planned','pathKnown':True,'targetX':100,'targetY':200}),
          ('tactical_intent',{'reason':'KNOWN_FRONTIER','targetX':100,'targetY':200}),
          ('action',{'gameTimeMs':1000,'path':'/command/attack-move?unitIds=9&x=100&y=200&sessionId=s&requestId=r'}),
          ('command_result',{'status':'queued','sessionId':'s','requestId':'r','unitIds':[9],'targetX':100,'targetY':200}),
          ('attack_order_confirmed',{'requestId':'r','unitIds':[9]}),
          ('observation',{'sessionId':'s','gameTimeMs':2000,'ownUnits':[unit],'match':match}),
          ('match_terminal',match)]
    summary={'outcome':'PASS','matchOutcome':outcome,'ownLosses':0,'newCombatUnits':0,'attackOrders':1,'attackOrdersConfirmed':1,'upgradesCompleted':0,'retreatOrders':0}
    return [{'event':e,'data':copy.deepcopy(d)} for e,d in rows],summary

def local_sample():
    rows,summary=sample()
    rows.insert(1,{'event':'local_army_membership','data':{'cohortId':1,'unitIds':[9],'reason':'LOCAL_FORMATION'}})
    plan=next(r for r in rows if r['event']=='army_frontier_plan')
    plan['event']='local_army_frontier_plan';plan['data'].update(anchorUnitId=9,targetTile=17)
    intent=next(r for r in rows if r['event']=='tactical_intent')
    intent['data'].update(cohortId=1,unitIds=[9])
    return rows,summary

def append_order(rows,summary,cohort=1,reason='REINFORCE',uid=9,x=100,y=200,request='r2',time=3000):
    rows[-2]['data']['gameTimeMs']=time+1000
    rows[-2:-2]=[
        {'event':'tactical_intent','data':{'cohortId':cohort,'reason':reason,'unitIds':[uid],'targetX':x,'targetY':y}},
        {'event':'action','data':{'gameTimeMs':time,'path':'/command/attack-move?unitIds=%s&x=%s&y=%s&sessionId=s&requestId=%s'%(uid,x,y,request)}},
        {'event':'command_result','data':{'status':'queued','sessionId':'s','requestId':request,'unitIds':[uid],'targetX':x,'targetY':y}}]
    summary['attackOrders']+=1

class BattleEvidence(unittest.TestCase):
    def issues(self,rows,summary):
        issues=[];validate_battle(rows,summary,lambda code,detail:issues.append(code));return issues
    def test_victory(self):
        self.assertEqual(self.issues(*sample()),[])
    def test_native_float32_and_three_decimal_quantization(self):
        self.assertTrue(native_coordinate_matches(6291.5715,6291.571))
        self.assertTrue(native_coordinate_matches(6250.0625,6250.063))
        self.assertTrue(native_coordinate_matches(100.00024,100))
        self.assertFalse(native_coordinate_matches(6291.5715,6291.572))
        self.assertFalse(native_coordinate_matches(float('nan'),0))
        self.assertFalse(native_coordinate_matches(1e40,0))
    def test_quantized_native_receipt_keeps_the_same_observed_intent(self):
        rows,s=sample()
        for row in rows:
            d=row['data']
            if row['event'] in ('army_frontier_plan','tactical_intent'):d['targetX']=6291.5715
            elif row['event']=='action':d['path']=d['path'].replace('x=100&','x=6291.5715&')
            elif row['event']=='command_result':d['targetX']=6291.571
            elif row['event']=='observation':d['ownUnits'][0]['orderX']=6291.571
        self.assertEqual(self.issues(rows,s),[])
        next(row['data'] for row in rows if row['event']=='command_result')['targetX']=6291.572
        self.assertIn('BATTLE_RECEIPT_MISMATCH',self.issues(rows,s))
    def test_defeat_is_complete_not_victory(self):
        self.assertEqual(self.issues(*sample('DEFEAT')),[])
    def test_no_result_not_pass(self):
        rows,s=sample();rows.pop();rows[-1]['data']['match']={'outcome':'ONGOING'}
        self.assertIn('BATTLE_NO_NATIVE_TERMINAL',self.issues(rows,s))
    def test_forged_result(self):
        rows,s=sample();rows[-1]['data']['nativeVictory']=False
        self.assertIn('BATTLE_RESULT_NOT_OBSERVED',self.issues(rows,s))
    def test_forged_confirmation(self):
        rows,s=sample();rows[0]['data']['ownUnits'][0]['orderType']='move'
        self.assertIn('BATTLE_ATTACK_NOT_EXECUTED',self.issues(rows,s))
    def test_unseen_target(self):
        rows,s=sample();rows[2]['data'].update(reason='OBSERVED_ENEMY',enemy={'id':88,'x':100,'y':200})
        self.assertIn('BATTLE_TARGET_NOT_OBSERVED',self.issues(rows,s))
    def test_command_spacing(self):
        rows,s=sample();rows.insert(5,{'event':'action','data':{'gameTimeMs':1500,'path':'/command/move'}})
        self.assertIn('BATTLE_COMMAND_RATE',self.issues(rows,s))
    def g3_rate_sample(self,commands):
        rows,s=sample()
        config={'g3Execution':True,'commandBudgetIntervalGameMs':1000,'commandBudgetBurst':4,
                'commandBudgetInitialTokens':1,'commandBudgetAnchorGameTimeMs':0}
        rows.insert(1,{'event':'battle_config','data':config})
        rows[5:5]=[{'event':'action','data':{'gameTimeMs':t,'path':'/command/move?unitId='+str(actor)}} for t,actor in commands]
        return rows,s
    def test_g3_independent_actors_can_use_accumulated_budget(self):
        rows,s=self.g3_rate_sample([(1000,10)])
        self.assertNotIn('BATTLE_COMMAND_RATE',self.issues(rows,s))
        self.assertNotIn('BATTLE_ACTOR_CONFLICT',self.issues(rows,s))
    def test_g3_no_unlimited_long_gap_burst(self):
        rows,s=self.g3_rate_sample([(60000,10+i) for i in range(5)])
        self.assertIn('BATTLE_COMMAND_RATE',self.issues(rows,s))
    def test_g3_same_actor_conflict_is_not_legal_parallelism(self):
        rows,s=self.g3_rate_sample([(1000,9)])
        self.assertIn('BATTLE_ACTOR_CONFLICT',self.issues(rows,s))
    def test_g3_budget_declaration_must_be_bounded(self):
        rows,s=self.g3_rate_sample([]);rows[1]['data']['commandBudgetBurst']=100000
        self.assertIn('BATTLE_EXECUTION_BUDGET_INVALID',self.issues(rows,s))
    def test_fake_new_unit(self):
        rows,s=sample();rows.insert(1,{'event':'combat_unit_observed','data':{'id':9}})
        self.assertIn('BATTLE_NEW_UNIT_NOT_OBSERVED',self.issues(rows,s))
    def test_count_mismatch(self):
        rows,s=sample();s['attackOrdersConfirmed']=5
        self.assertIn('BATTLE_COUNT_MISMATCH',self.issues(rows,s))
    def test_session_reset(self):
        rows,s=sample();rows[-2]['data']['sessionId']='new'
        self.assertIn('BATTLE_SESSION_CHANGED',self.issues(rows,s))
    def test_unproven_upgrade(self):
        rows,s=sample();rows.insert(1,{'event':'upgrade_completed','data':{'factoryId':42,'tier':2}})
        self.assertIn('BATTLE_UPGRADE_NOT_OBSERVED',self.issues(rows,s))
    def building_sample(self):
        """A mine built during the battle: visible in one observation, gone from the next."""
        rows,s=sample()
        tank=rows[0]['data']['ownUnits'][0]
        mine={'id':42,'type':'extractorT1','hp':400,'dead':False,'mobile':False,'canAttack':False,'buildProgress':1,'orderType':None}
        rows.insert(6,{'event':'observation','data':{'sessionId':'s','gameTimeMs':1500,'ownUnits':[copy.deepcopy(tank),copy.deepcopy(mine)],'match':{'outcome':'ONGOING'}}})
        rows.insert(len(rows)-1,{'event':'own_loss','data':{'unitId':42,'gameTimeMs':1800}})
        s['ownLosses']=1
        return rows,s
    def test_lost_building_is_observed(self):
        # Mines and factories never raise combat_unit_observed, so a report must not become INVALID
        # merely because a building the client had already shown us was destroyed.
        self.assertEqual(self.issues(*self.building_sample()),[])
    def test_loss_of_a_never_seen_unit_is_still_rejected(self):
        rows,s=self.building_sample();rows[8]['data']['unitId']=77
        self.assertIn('BATTLE_LOSS_NOT_OBSERVED',self.issues(rows,s))
    def test_loss_while_still_alive_is_still_rejected(self):
        rows,s=self.building_sample()
        rows.insert(1,{'event':'own_loss','data':{'unitId':9,'gameTimeMs':100}});s['ownLosses']=2
        self.assertIn('BATTLE_LOSS_NOT_OBSERVED',self.issues(rows,s))
    def test_completed_building_loss_needs_no_observation(self):
        # A building may be finished and destroyed between two observations; the completion the client
        # recorded is still proof that the unit was ours.
        rows,s=sample()
        rows.insert(len(rows)-1,{'event':'extractor_completed','data':{'unitId':55,'type':'extractorT1','minesCompleted':1}})
        rows.insert(len(rows)-1,{'event':'own_loss','data':{'unitId':55,'gameTimeMs':1800}})
        s['ownLosses']=1
        self.assertEqual(self.issues(rows,s),[])
    def test_local_frontier_binds_plan_through_own_cohort_anchor(self):
        self.assertEqual(self.issues(*local_sample()),[])
    def test_local_frontier_cannot_borrow_another_cohorts_plan(self):
        rows,s=local_sample()
        rows.insert(2,{'event':'local_army_membership','data':{'cohortId':2,'unitIds':[10],'reason':'LOCAL_FORMATION'}})
        next(r for r in rows if r['event']=='local_army_frontier_plan')['data']['anchorUnitId']=10
        self.assertIn('BATTLE_FRONTIER_NOT_PLANNED',self.issues(rows,s))
    def test_local_frontier_unknown_path_is_still_rejected(self):
        rows,s=local_sample()
        next(r for r in rows if r['event']=='local_army_frontier_plan')['data']['pathKnown']=False
        self.assertIn('BATTLE_FRONTIER_NOT_PLANNED',self.issues(rows,s))
    def test_accepted_local_frontier_survives_another_cohorts_new_plan(self):
        rows,s=local_sample()
        other=copy.deepcopy(rows[0]['data']['ownUnits'][0]);other['id']=10
        rows[0]['data']['ownUnits'].append(other)
        rows[-2:-2]=[
            {'event':'local_army_membership','data':{'cohortId':2,'unitIds':[10],'reason':'LOCAL_FORMATION'}},
            {'event':'local_army_frontier_plan','data':{'status':'planned','pathKnown':True,'anchorUnitId':10,'targetX':400,'targetY':500}}]
        append_order(rows,s)
        self.assertEqual(self.issues(rows,s),[])
    def test_rejected_frontier_and_bookkeeping_do_not_grant_reinforcement(self):
        rows,s=local_sample()
        receipt=next(r for r in rows if r['event']=='command_result')
        receipt.update(event='command_rejected',data={'status':409,'body':'native rejection'})
        rows[:]=[r for r in rows if r['event']!='attack_order_confirmed']
        s.update(attackOrders=0,attackOrdersConfirmed=0)
        rows[-2:-2]=[{'event':'local_army_order','data':{'cohortId':1,'requestId':'r','reason':'KNOWN_FRONTIER','receiptStatus':'queued','targetX':100,'targetY':200}}]
        append_order(rows,s)
        self.assertIn('BATTLE_REINFORCE_NO_ACCEPTED_FRONTIER',self.issues(rows,s))
    def test_rejected_action_cannot_lend_identity_to_a_later_queued_receipt(self):
        rows,s=local_sample()
        queued=copy.deepcopy(next(r for r in rows if r['event']=='command_result'))
        next(r for r in rows if r['event']=='command_result').update(event='command_rejected',data={'status':409})
        rows[:]=[r for r in rows if r['event']!='attack_order_confirmed']
        rows[-2:-2]=[queued];s.update(attackOrders=0,attackOrdersConfirmed=0)
        self.assertIn('BATTLE_RECEIPT_NO_ACTION',self.issues(rows,s))
    def test_rejected_intent_cannot_approve_a_new_action_without_fresh_intent(self):
        rows,s=local_sample()
        queued=copy.deepcopy(next(r for r in rows if r['event']=='command_result'))
        queued['data']['requestId']='r2'
        next(r for r in rows if r['event']=='command_result').update(event='command_rejected',data={'status':409})
        rows[:]=[r for r in rows if r['event']!='attack_order_confirmed'];s.update(attackOrders=1,attackOrdersConfirmed=0)
        rows[-2:-2]=[{'event':'action','data':{'gameTimeMs':2000,'path':'/command/attack-move?unitIds=9&x=100&y=200&sessionId=s&requestId=r2'}},queued]
        append_order(rows,s,request='r3',time=3000)
        issues=self.issues(rows,s)
        self.assertIn('BATTLE_ATTACK_NO_INTENT',issues)
        self.assertIn('BATTLE_REINFORCE_NO_ACCEPTED_FRONTIER',issues)
    def test_reinforcement_requires_the_same_cohorts_accepted_goal(self):
        rows,s=local_sample()
        rows.insert(2,{'event':'local_army_membership','data':{'cohortId':2,'unitIds':[10],'reason':'LOCAL_FORMATION'}})
        append_order(rows,s,cohort=2,uid=10)
        self.assertIn('BATTLE_REINFORCE_NO_ACCEPTED_FRONTIER',self.issues(rows,s))
    def test_frontier_arrival_or_another_accepted_task_ends_the_goal(self):
        for end in ('arrival','other-task'):
            with self.subTest(end=end):
                rows,s=local_sample()
                if end=='arrival':
                    rows[-2:-2]=[{'event':'local_army_frontier_arrived','data':{'cohortId':1,'targetTile':17}}]
                    append_order(rows,s)
                else:
                    append_order(rows,s,reason='REGROUP',x=300,y=400)
                    append_order(rows,s,request='r3',time=5000)
                self.assertIn('BATTLE_REINFORCE_NO_ACCEPTED_FRONTIER',self.issues(rows,s))
    def test_remote_contact_requires_current_visible_evidence(self):
        for visible,seen in ((False,0),(True,0),(True,1000)):
            with self.subTest(visible=visible,seen=seen):
                rows,s=sample();enemy={'id':88,'x':100,'y':200,'lastSeenGameTimeMs':seen}
                rows[2]['data'].update(reason='REMOTE_VISIBLE_CONTACT',enemy=enemy)
                rows.insert(1,{'event':'combat_observation','data':{'sessionId':'s','gameTimeMs':1000,
                    'rememberedEnemies':[enemy],'visibleEnemies':[enemy] if visible else []}})
                found=self.issues(rows,s)
                if visible and seen==1000:self.assertEqual(found,[])
                else:self.assertIn('BATTLE_TARGET_NOT_OBSERVED',found)
if __name__=='__main__':unittest.main()
