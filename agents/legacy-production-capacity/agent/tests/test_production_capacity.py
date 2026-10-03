"""Real BattleClient HTTP contracts for military and producer capacity.

Native-shaped quotes debit on acceptance; queue gaps and factory construction are
observed separately. These deterministic fixtures are not natural game outcomes.
"""
import http.server,json,os,pathlib,subprocess,sys,tempfile,threading,unittest
from urllib.parse import parse_qs,urlsplit

JAR=str(pathlib.Path(sys.argv.pop(1)).resolve())

class ProductionCapacityTests(unittest.TestCase):
    def run_case(self,scenario):
        tick=[0];credits=[1000.0 if scenario=='army_reserve' else 100000.0];orders=[];queues={};products=[];motions={};factory=[None]
        army_size=40 if scenario.startswith('army') else 128 if scenario=='hard' else 24
        producer_busy=not scenario.startswith('army') and scenario not in ('idle','single_busy')
        final_tick=100
        def now():return tick[0]*2000
        def unit(uid,kind,x=100,y=100,bp=1,queue=0):
            building=kind in ('commandCenter','landFactory','extractorT1')
            result=dict(id=uid,type=kind,x=x,y=y,hp=1000,maxHp=1000,dead=False,buildProgress=bp,
                mobile=not building,canAttack=kind=='heavyTank',building=building,techLevel=2,
                productionQueue=queue,orderType=None)
            result.update(motions.get(uid,{}));return result
        def busy(uid):
            return uid in queues or producer_busy or scenario=='single_busy' and tick[0]>97
        def own():
            own=[unit(3,'commandCenter')]+([] if scenario=='army_recovery' else [unit(4,'builder',140,100),unit(7,'builder',150,100)])
            own += [unit(5,'landFactory',150,100,queue=int(busy(5))),unit(6,'landFactory',230,100,queue=int(busy(6)))]
            mine_count=8 if scenario!='income_spike' or tick[0]>97 else 0
            own += [unit(10+i,'extractorT1',100+i*30,300) for i in range(mine_count)]
            own += [unit(1000+i,'heavyTank',900,900) for i in range(army_size)]
            own += [unit(uid,'heavyTank',900,900) for uid in products]
            if factory[0] and tick[0]>factory[0]:own.append(unit(90,'landFactory',180,100,bp=.5 if tick[0]<factory[0]+3 else 1))
            return own
        def contacts():
            if scenario=='no_demand':return []
            return [dict(id=74,type='landFactory',x=2400,y=1000,hp=1000,maxHp=1000,dead=False,
                building=True,canAttack=False,lastSeenGameTimeMs=now(),targetDomain='SURFACE')]
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def reply(self,data,status=200):
                payload=json.dumps(data).encode();self.send_response(status);self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
            def do_GET(self):
                path=urlsplit(self.path).path;q=parse_qs(urlsplit(self.path).query)
                if path=='/health':return self.reply(dict(status='ok',version='0.07-alpha1',strategyContractVersion=1))
                if path=='/state':
                    tick[0]+=1
                    for uid,started in list(queues.items()):
                        # A one-observation paid queue gap exercises conservative pending accounting.
                        if tick[0]>=started+4:products.append(3000+len(products));del queues[uid]
                    end=tick[0]>=final_tick
                    return self.reply(dict(status='running',sessionId='s',frame=tick[0],gameTimeMs=now(),networked=False,replay=False,
                        player=dict(teamId=0,credits=credits[0]),map=dict(width=4000,height=4000,tilesWide=110,tilesHigh=110,tileWidth=20,tileHeight=20),
                        match=dict(outcome='DEFEAT' if end else 'ONGOING',nativeDefeat=end,nativeVictory=False,source='native_result_screen'),ownUnits=own()))
                if path=='/combat/observe':return self.reply(dict(status='observed',sessionId='s',gameTimeMs=now(),visibleEnemies=contacts(),rememberedEnemies=[],enemyIntel=[],rememberedBuildings=[]))
                if path=='/combat/engagement':
                    status='UNKNOWN' if scenario in ('unknown','tech_unknown') else 'BLOCKED_TERRAIN' if scenario=='blocked' else 'APPROACH_PATH_KNOWN'
                    return self.reply(dict(status='observed',sessionId='s',targetId=74,targetVisible=True,targetX=2400,targetY=1000,targetObservedAtGameTimeMs=now(),
                        actors=[dict(unitId=int(uid),status=status,compatibility='COMPATIBLE',approachX=2200,approachY=1000) for uid in q['unitIds'][0].split(',')]))
                if path=='/combat/production':
                    actions=[] if scenario=='route_missing' else [dict(actionId='upgrade',type='upgrade',cost=2000,affordable=True)] if scenario in ('tech','tech_unknown') else [dict(actionId='heavy',type='heavyTank',cost=800,affordable=scenario!='native_unaffordable')]
                    return self.reply(dict(status='observed',sessionId='s',factories=[dict(id=uid,tier=2,queue=int(busy(uid)),actions=actions) for uid in (5,6)]))
                if path=='/scout/observe':return self.reply(dict(status='observed',sessionId='s',gameTimeMs=now(),resources=[],visibleThreats=[],rememberedThreats=[],newlyObservedTiles=0))
                if path=='/scout/visible':return self.reply(dict(status='observed',sessionId='s',tiles=[]))
                if path=='/economy/builder-actions':return self.reply(dict(status='planned',sessionId='s',actions=[dict(type='landFactory',actionId='factory',cost=700,available=True,affordable=True,buildAction=True)]))
                if path=='/economy/plan':return self.reply(dict(status='planned',sessionId='s',factoryCost=700,targetX=180,targetY=100))
                if path in ('/economy/investments','/combat/capabilities'):return self.reply(dict(status='observed',sessionId='s',units=[],capabilities=[]))
                return self.reply(dict(status='no_frontier',sessionId='s'))
            def do_POST(self):
                path=urlsplit(self.path).path;q=parse_qs(urlsplit(self.path).query);uid=int(q.get('unitId',['0'])[0])
                if path=='/command/queue':
                    if scenario in ('idle','single_busy'):return self.reply(dict(status='error',sessionId='s'),409)
                    if uid in queues:return self.reply(dict(status='error',sessionId='s'),409)
                    credits[0]-=800;queues[uid]=tick[0]
                if path=='/command/build-factory':credits[0]-=700;factory[0]=tick[0]
                if path=='/command/move':motions[uid]=dict(x=float(q['x'][0]),y=float(q['y'][0]))
                orders.append(dict(path=path,gameTimeMs=now(),credits=credits[0],unitId=uid))
                return self.reply(dict(status='queued',sessionId='s',requestId=q['requestId'][0],unitId=uid,frame=tick[0]))
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            with tempfile.TemporaryDirectory() as cwd:
                args=['java','-Dfile.encoding=UTF-8','-Drwagent.pollMs=40','-Drwagent.economyWindowMs=60000','-Drwagent.expansionIntervalGameMs=2000',
                    '-Drwagent.port='+str(server.server_port)]
                if scenario=='income_spike':args+=['-Drwagent.baseIncome=0']
                args+=['-cp',JAR,'io.rwagent.client.BattleClient','240']
                result=subprocess.run(args,cwd=cwd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=45)
                report=next(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                rows=[json.loads(line) for line in report.read_text(encoding='utf-8').splitlines()]
                self.assertEqual(result.returncode,0,result.stdout.decode('utf-8',errors='replace')+json.dumps(rows[-4:]))
                evidence=os.environ.get('RW_CAPACITY_EVIDENCE')
                if evidence:
                    out=pathlib.Path(evidence);out.mkdir(parents=True,exist_ok=True)
                    selected=[r for r in rows if r['event'] in ('production_capacity_assessment','military_capacity_increased','factory_target_increased',
                        'production_facility_committed','production_facility_planned','production_facility_order_accepted','production_facility_ready','production_capacity_after_expansion','summary')]
                    (out/(scenario+'.json')).write_text(json.dumps(dict(scenario=scenario,orders=orders,events=selected),indent=2),encoding='utf-8')
                return orders,rows
        finally:server.shutdown();server.server_close()
    def events(self,rows,name):return [r['data'] for r in rows if r['event']==name]
    def test_strategy_target_releases_bounded_slots_and_restarts_production(self):
        orders,rows=self.run_case('army');raised=self.events(rows,'military_capacity_increased')
        self.assertTrue(raised);self.assertEqual(raised[0]['bottleneck'],'ARMY_CAPACITY_LIMIT')
        self.assertGreater(raised[0]['newTarget'],raised[0]['oldTarget']);self.assertLessEqual(raised[0]['slotsAdded'],8)
        self.assertTrue(any(o['path']=='/command/queue' and o['gameTimeMs']>=raised[0]['gameTimeMs'] for o in orders))
        self.assertFalse(self.events(rows,'factory_target_increased'))
    def test_saturated_producers_commit_order_and_observe_ready(self):
        orders,rows=self.run_case('producer');raised=self.events(rows,'factory_target_increased')
        self.assertEqual((raised[0]['from'],raised[0]['to']),(2,3));self.assertEqual(raised[0]['bottleneck'],'PRODUCER_THROUGHPUT_LIMIT')
        accepted=self.events(rows,'production_facility_order_accepted');ready=self.events(rows,'production_facility_ready')
        self.assertEqual(len(accepted),1);self.assertEqual(len(ready),1)
        self.assertEqual(raised[0]['factoryCommitmentId'],accepted[0]['factoryCommitmentId']);self.assertEqual(accepted[0]['factoryCommitmentId'],ready[0]['factoryCommitmentId'])
        self.assertEqual(sum(o['path']=='/command/build-factory' for o in orders),1)
        self.assertTrue(self.events(rows,'production_capacity_after_expansion'))
    def assert_no_expansion(self,scenario,expected=None):
        orders,rows=self.run_case(scenario)
        self.assertFalse(self.events(rows,'military_capacity_increased'));self.assertFalse(self.events(rows,'factory_target_increased'))
        if expected:self.assertIn(expected,{r['bottleneck'] for r in self.events(rows,'production_capacity_assessment')})
    def test_busy_hard_cap_never_expands(self):self.assert_no_expansion('hard','HARD_SAFETY_CAP')
    def test_no_visible_useful_demand_never_expands(self):self.assert_no_expansion('no_demand','NO_USEFUL_DEMAND')
    def test_unknown_legality_stays_unknown(self):self.assert_no_expansion('unknown','UNKNOWN')
    def test_blocked_ordinary_route_never_expands(self):self.assert_no_expansion('blocked','ROUTE_UNAVAILABLE')
    def test_missing_native_route_never_expands(self):self.assert_no_expansion('route_missing','ROUTE_UNAVAILABLE')
    def test_native_unaffordable_does_not_expand_from_cash(self):self.assert_no_expansion('native_unaffordable','UNKNOWN')
    def test_idle_producers_with_free_slots_remain_unknown(self):self.assert_no_expansion('idle','UNKNOWN')
    def test_one_busy_frame_is_insufficient(self):self.assert_no_expansion('single_busy')
    def test_no_sustainable_income_never_expands(self):self.assert_no_expansion('income_spike')
    def test_army_limit_cannot_spend_investment_reserve(self):self.assert_no_expansion('army_reserve','RESERVE_PROTECTED')
    def test_army_limit_cannot_expand_during_builder_recovery(self):self.assert_no_expansion('army_recovery','RESERVE_PROTECTED')
    def test_native_tech_route_is_diagnosed_without_purchase(self):self.assert_no_expansion('tech','PRODUCER_TECH_LIMIT')
    def test_unknown_approach_does_not_become_tech_demand(self):self.assert_no_expansion('tech_unknown','UNKNOWN')

if __name__=='__main__':unittest.main()
