"""Deterministic policy regression: affordable upgrades must not starve behind tank production."""
import http.server,json,pathlib,subprocess,sys,tempfile,threading,unittest
JAR=str(pathlib.Path(sys.argv.pop(1)).resolve())

class Policy(unittest.TestCase):
    def run_case(self,count,credits):
        calls=[];step=[0];upgraded=[False];queued=[False]
        def unit(uid,kind,x=100,y=100):
            building=kind in ('commandCenter','landFactory')
            return dict(id=uid,type=kind,x=x,y=y,hp=1000,maxHp=1000,dead=False,buildProgress=1,
                        mobile=not building,canAttack=kind=='c_tank',building=building,
                        techLevel=2 if kind=='landFactory' and upgraded[0] else 1,productionQueue=1 if kind=='landFactory' and queued[0] else 0,orderType=None)
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*a):pass
            def do_POST(self):
                from urllib.parse import urlsplit,parse_qs
                q=parse_qs(urlsplit(self.path).query);calls.append(self.path);queued[0]=True
                self.send(dict(status='queued',sessionId='s',requestId=q['requestId'][0],unitId=5,frame=step[0],type='upgrade'))
            def do_GET(self):
                if self.path=='/health':return self.send(dict(status='ok',version='0.07-alpha1'))
                if self.path=='/state':
                    step[0]+=1
                    if step[0]>=4 and calls:queued[0]=False;upgraded[0]=True
                    end=step[0]>=5
                    return self.send(dict(status='running',sessionId='s',frame=step[0],gameTimeMs=step[0]*2000,networked=False,replay=False,
                        player={'credits':credits},map={'width':2200,'height':2200},
                        match=dict(outcome='DEFEAT' if end else 'ONGOING',nativeDefeat=end,nativeVictory=False,source='native_result_screen'),
                        ownUnits=[unit(3,'commandCenter'),unit(5,'landFactory')]+[unit(i+20,'c_tank') for i in range(count)]))
                if self.path=='/combat/production':
                    actions=[dict(actionId='upgrade',type='upgrade',cost=2000,affordable=credits>=2000),
                             dict(actionId='tank',type='c_tank',cost=350,affordable=True)] if not upgraded[0] else []
                    return self.send(dict(status='observed',sessionId='s',factories=[dict(id=5,tier=2 if upgraded[0] else 1,queue=1 if queued[0] else 0,actions=actions)]))
                if self.path=='/combat/observe':return self.send(dict(status='observed',sessionId='s',gameTimeMs=step[0]*2000,visibleEnemies=[],rememberedEnemies=[]))
                return self.send(dict(status='no_frontier',sessionId='s'))
            def send(self,d):
                b=json.dumps(d).encode();self.send_response(200);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            with tempfile.TemporaryDirectory(dir=pathlib.Path.cwd()) as cwd:
                p=subprocess.run(['java','-Drwagent.pollMs=60','-Drwagent.port='+str(server.server_port),'-cp',JAR,'io.rwagent.client.BattleClient','120'],cwd=cwd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=20)
                self.assertEqual(p.returncode,0,p.stdout.decode())
                report=next(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                rows=[json.loads(x) for x in report.read_text().splitlines()]
                self.assertEqual(rows[-1]['data']['matchOutcome'],'DEFEAT')
                return calls,rows
        finally:server.shutdown();server.server_close()
    def test_upgrade_affordable_below_old_reserve(self):
        calls,rows=self.run_case(7,2740)
        self.assertIn('actionId=upgrade',calls[0])
        self.assertEqual(rows[-1]['data']['upgradesCompleted'],1)
    def test_upgrade_at_army_cap(self):
        calls,_=self.run_case(24,2000)
        self.assertIn('actionId=upgrade',calls[0])
    def test_banking_does_not_spend_upgrade_income(self):
        calls,_=self.run_case(10,1900)
        self.assertFalse(calls)
class ProductionLadderTests(unittest.TestCase):
    """P1-D: the old hard 24 cap becomes active target + reserve + a real safety cap."""
    def run_case(self,tanks,credits=5000,hard_cap=None):
        calls=[];step=[0]
        def unit(uid,kind,x=100,y=100):
            building=kind in ('commandCenter','landFactory')
            return dict(id=uid,type=kind,x=x,y=y,hp=1000,maxHp=1000,dead=False,buildProgress=1,
                        mobile=not building,canAttack=kind=='c_tank',building=building,techLevel=2,productionQueue=0,orderType=None)
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*a):pass
            def do_POST(self):
                from urllib.parse import urlsplit,parse_qs
                q=parse_qs(urlsplit(self.path).query);calls.append(self.path)
                self.send(dict(status='queued',sessionId='s',requestId=q['requestId'][0],unitId=5,frame=step[0],type='c_tank'))
            def do_GET(self):
                if self.path=='/health':return self.send(dict(status='ok',version='0.07-alpha1'))
                if self.path=='/state':
                    step[0]+=1;end=step[0]>=4
                    return self.send(dict(status='running',sessionId='s',frame=step[0],gameTimeMs=step[0]*2000,networked=False,replay=False,
                        player={'credits':credits},map={'width':2200,'height':2200,'tilesWide':110,'tilesHigh':110,'tileWidth':20,'tileHeight':20},
                        match=dict(outcome='DEFEAT' if end else 'ONGOING',nativeDefeat=end,nativeVictory=False,source='native_result_screen'),
                        ownUnits=[unit(3,'commandCenter'),unit(5,'landFactory')]+[unit(20+i,'c_tank') for i in range(tanks)]))
                if self.path=='/combat/production':
                    return self.send(dict(status='observed',sessionId='s',factories=[dict(id=5,tier=2,queue=0,
                        actions=[dict(actionId='tank',type='c_tank',cost=350,affordable=True)])]))
                if self.path=='/combat/observe':
                    return self.send(dict(status='observed',sessionId='s',gameTimeMs=step[0]*2000,visibleEnemies=[],rememberedEnemies=[]))
                return self.send(dict(status='no_frontier',sessionId='s'))
            def send(self,d):
                b=json.dumps(d).encode();self.send_response(200);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            with tempfile.TemporaryDirectory(dir=pathlib.Path.cwd()) as cwd:
                args=['java','-Drwagent.pollMs=60','-Drwagent.port='+str(server.server_port)]
                if hard_cap is not None:args.append('-Drwagent.mobileUnitHardCap='+str(hard_cap))
                p=subprocess.run(args+['-cp',JAR,'io.rwagent.client.BattleClient','120'],cwd=cwd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=20)
                self.assertEqual(p.returncode,0,p.stdout.decode())
                report=next(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                return calls,[json.loads(x) for x in report.read_text().splitlines()]
        finally:server.shutdown();server.server_close()
    def test_reserve_is_still_produced_after_the_active_target(self):
        calls,rows=self.run_case(24)
        self.assertTrue(calls,"reaching the active target must not stop production")
        self.assertIn('actionId=tank',calls[0])
        self.assertTrue(any(r['event']=='production_reserve_started' for r in rows),"the reserve transition is recorded")
    def test_hard_cap_stops_production_with_an_explicit_reason(self):
        # 输出19 §2: 40 is the development default now, so this asserts the mechanism at an explicit
        # override (32) and that the override reaches the report unchanged.
        calls,rows=self.run_case(32,hard_cap=32)
        self.assertFalse(calls,"a full mobile force must not keep ordering units")
        idle=[r for r in rows if r['event']=='production_idle']
        self.assertTrue(idle,"an idle production system must say why")
        self.assertEqual(idle[-1]['data']['reason'],'MOBILE_UNIT_HARD_CAP_REACHED')
        self.assertEqual(idle[-1]['data']['mobileUnitHardCap'],32)
    def test_development_default_cap_is_40(self):
        # 输出19 §2: the A/B result made 40 the development default; the report must show it, and the same
        # fixture at 32 units must now still be allowed to produce.
        calls,rows=self.run_case(24)
        config=[r for r in rows if r['event']=='battle_config']
        self.assertTrue(config,"the run records its configuration")
        self.assertEqual(config[0]['data']['mobileUnitHardCap'],40)
        self.assertTrue(calls,"24 units is below the new default cap, so production continues")
        calls32,rows32=self.run_case(40)
        self.assertFalse(calls32,"40 units reaches the new default cap")
        idle=[r for r in rows32 if r['event']=='production_idle']
        self.assertEqual(idle[-1]['data']['reason'],'MOBILE_UNIT_HARD_CAP_REACHED')
        self.assertEqual(idle[-1]['data']['mobileUnitHardCap'],40)


class LastSeenSearchTests(unittest.TestCase):
    """P1-B: the last-seen objective, its consumption by legal vision and its tombstone."""
    TILE=5550  # tile of (1000,1000) on a 110x110 map with 20 unit tiles
    def run_case(self):
        calls=[];step=[0];target_tile=self.TILE
        def unit(uid,kind,x=100,y=100):
            building=kind in ('commandCenter','landFactory')
            return dict(id=uid,type=kind,x=x,y=y,hp=1000,maxHp=1000,dead=False,buildProgress=1,
                        mobile=not building,canAttack=kind=='c_tank',building=building,techLevel=2,productionQueue=0,orderType=None)
        def enemy(seen):
            return dict(id=7,type='builder',x=1000,y=1000,hp=45,maxHp=40,dead=False,building=False,canAttack=False,lastSeenGameTimeMs=seen)
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*a):pass
            def do_POST(self):
                from urllib.parse import urlsplit,parse_qs
                q=parse_qs(urlsplit(self.path).query);calls.append(self.path)
                self.send(dict(status='queued',sessionId='s',requestId=q['requestId'][0],unitId=20,frame=step[0],
                               unitIds=[20+i for i in range(8)],targetX=float(q['x'][0]),targetY=float(q['y'][0]),orderType='attackMove'))
            def do_GET(self):
                if self.path=='/health':return self.send(dict(status='ok',version='0.07-alpha1'))
                now=step[0]*2000
                if self.path=='/state':
                    step[0]+=1;now=step[0]*2000;end=step[0]>=14
                    return self.send(dict(status='running',sessionId='s',frame=step[0],gameTimeMs=now,networked=False,replay=False,
                        player={'credits':0},map={'width':2200,'height':2200,'tilesWide':110,'tilesHigh':110,'tileWidth':20,'tileHeight':20},
                        match=dict(outcome='DEFEAT' if end else 'ONGOING',nativeDefeat=end,nativeVictory=False,source='native_result_screen'),
                        ownUnits=[unit(3,'commandCenter')]+[unit(20+i,'c_tank') for i in range(8)]))
                if self.path=='/combat/production':
                    return self.send(dict(status='observed',sessionId='s',factories=[]))
                if self.path.startswith('/scout/visible'):
                    return self.send(dict(status='observed',sessionId='s',frame=step[0],gameTimeMs=now,
                        tiles=[dict(tile=target_tile,x=1010,y=1010,visible=step[0]>=6)]))
                if self.path=='/combat/observe':
                    if step[0]<=2:                                   # fresh legal observation
                        return self.send(dict(status='observed',sessionId='s',gameTimeMs=now,visibleEnemies=[enemy(now)],rememberedEnemies=[enemy(now)]))
                    if step[0]<=9:                                   # memory gone; later the same stale reading returns
                        visible=[enemy(4000)] if step[0]>=7 else []
                        return self.send(dict(status='observed',sessionId='s',gameTimeMs=now,visibleEnemies=visible,rememberedEnemies=visible))
                    return self.send(dict(status='observed',sessionId='s',gameTimeMs=now,visibleEnemies=[enemy(now)],rememberedEnemies=[enemy(now)]))
                return self.send(dict(status='no_frontier',sessionId='s'))
            def send(self,d):
                b=json.dumps(d).encode();self.send_response(200);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            with tempfile.TemporaryDirectory(dir=pathlib.Path.cwd()) as cwd:
                p=subprocess.run(['java','-Drwagent.pollMs=60','-Drwagent.port='+str(server.server_port),'-cp',JAR,'io.rwagent.client.BattleClient','120'],cwd=cwd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=20)
                self.assertEqual(p.returncode,0,p.stdout.decode())
                report=next(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                return calls,[json.loads(x) for x in report.read_text().splitlines()]
        finally:server.shutdown();server.server_close()
    def test_objective_is_created_marched_to_consumed_and_tombstoned(self):
        calls,rows=self.run_case()
        empty=[i for i,r in enumerate(rows) if r['event']=='search_target_confirmed_empty']
        self.assertEqual(len(empty),1,"re-entering legal vision without the enemy consumes the objective")
        self.assertEqual(rows[empty[0]]['data']['observationGameTime'],4000)
        created=[r for r in rows[:empty[0]] if r['event']=='search_target_created']
        self.assertEqual(len(created),1,"one legal observation creates one objective")
        self.assertEqual(created[0]['data']['sourceEnemyId'],7)
        self.assertEqual(created[0]['data']['tile'],self.TILE)
        intents=[r for r in rows if r['event']=='tactical_intent' and r['data']['reason']=='LAST_SEEN']
        self.assertTrue(intents,"after the tactical memory expires the army must march to the last-seen place")
        self.assertAlmostEqual(intents[0]['data']['targetX'],1000.0)
        after=[r for r in rows[empty[0]:] if r['event']=='search_target_created']
        self.assertEqual(len(after),1,"the stale observation must never revive an objective")
        self.assertGreater(after[0]['data']['lastSeenGameTime'],4000,"only a newer observation may create one")
        self.assertEqual(len([r for r in rows[empty[0]+1:] if r['event']=='search_target_confirmed_empty']),0)


class EconomyExpansionTests(unittest.TestCase):
    """P2-A: a second resource point is planned, walked to, built, reported and paid for."""
    def run_case(self,credits_early=2000,credits_late=None,plan_after_arrival=False,ready_mines=1,unfinished_mines=0):
        calls=[];step=[0];builder=[1000,1000];spawn=[None];purse=[credits_early]
        def unit(uid,kind,x=100,y=100,bp=1.0):
            building=kind in ('commandCenter','landFactory')
            return dict(id=uid,type=kind,x=x,y=y,hp=1000,maxHp=1000,dead=False,buildProgress=bp,
                        mobile=not building,canAttack=kind=='c_tank',building=building,techLevel=1,productionQueue=0,orderType=None)
        def own():
            st=step[0]
            units=[unit(3,'commandCenter'),unit(5,'landFactory'),
                   unit(4,'builder',builder[0],builder[1])]
            units+=[unit(11+i,'extractorT1') for i in range(ready_mines)]
            # A half-built mine is not a mine: it must not satisfy the mine target.
            units+=[unit(30+i,'extractorT1',500+i*20,500,0.5) for i in range(unfinished_mines)]
            units+=[unit(20+i,'c_tank') for i in range(8)]
            if spawn[0] is not None and st>=spawn[0]:
                units.append(unit(77,'extractorT1',1500,1000,0.5 if st<spawn[0]+2 else 1.0))
            return units
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*a):pass
            def do_POST(self):
                from urllib.parse import urlsplit,parse_qs
                q=parse_qs(urlsplit(self.path).query);calls.append((self.path,purse[0]))
                if 'build-extractor' in self.path:spawn[0]=step[0]+1        # the real engine only builds after the order
                if 'command/move' in self.path:builder[0]=1500;builder[1]=1000
                self.send(dict(status='queued',sessionId='s',requestId=q['requestId'][0],unitId=5,frame=step[0],type='c_tank'))
            def do_GET(self):
                if self.path=='/health':return self.send(dict(status='ok',version='0.07-alpha1'))
                if self.path=='/state':
                    step[0]+=1;end=step[0]>=12;now=step[0]*2000
                    purse[0]=credits_early if (credits_late is None or step[0]<5) else credits_late
                    return self.send(dict(status='running',sessionId='s',frame=step[0],gameTimeMs=now,networked=False,replay=False,
                        player={'credits':purse[0]},
                        map={'width':2200,'height':2200,'tilesWide':110,'tilesHigh':110,'tileWidth':20,'tileHeight':20},
                        match=dict(outcome='DEFEAT' if end else 'ONGOING',nativeDefeat=end,nativeVictory=False,source='native_result_screen'),
                        ownUnits=own()))
                if self.path.startswith('/expansion/plan'):
                    if plan_after_arrival and builder[0]!=1500:
                        return self.send(dict(status='error',
                            message='no legal opening site; extractorType=extractorT1; eligibleBuilders=1; visibleResourceCandidates=1; searchRange=600',
                            diagnostics=dict(searchRange=600,visibleResourceCandidates=1,legalCandidates=0)),409)
                    return self.send(dict(status='planned',sessionId='s',builderId=4,extractorType='extractorT1',
                        extractorX=1500,extractorY=1000,extractorCost=700,resourceCandidates=1,
                        diagnostics={'visibleResourceCandidates':1,'legalCandidates':1,'notReachable':0}))
                if self.path=='/scout/observe':
                    res=[dict(tile=777,x=1500,y=1000,firstSeenFrame=3,initiallyVisible=False,currentlyVisible=False)] if plan_after_arrival else []
                    return self.send(dict(status='observed',sessionId='s',frame=step[0],gameTimeMs=step[0]*2000,
                        fogEnabled=True,lineOfSightFog=True,visibleTiles=100,exploredTiles=5000,initialVisibleTiles=100,
                        newlyObservedTiles=0,resources=res,visibleThreats=[],rememberedThreats=[]))
                if self.path=='/combat/production':
                    return self.send(dict(status='observed',sessionId='s',factories=[dict(id=5,tier=2,queue=0,
                        actions=[dict(actionId='tank',type='c_tank',cost=350,affordable=True)])]))
                if self.path=='/combat/observe':
                    return self.send(dict(status='observed',sessionId='s',gameTimeMs=step[0]*2000,visibleEnemies=[],rememberedEnemies=[]))
                return self.send(dict(status='no_frontier',sessionId='s'))
            def send(self,d,status=200):
                b=json.dumps(d).encode();self.send_response(status);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            with tempfile.TemporaryDirectory(dir=pathlib.Path.cwd()) as cwd:
                p=subprocess.run(['java','-Drwagent.pollMs=60','-Drwagent.expansionIntervalGameMs=2000','-Drwagent.port='+str(server.server_port),'-cp',JAR,'io.rwagent.client.BattleClient','120'],cwd=cwd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=20)
                self.assertEqual(p.returncode,0,p.stdout.decode())
                report=next(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                return calls,[json.loads(x) for x in report.read_text().splitlines()]
        finally:server.shutdown();server.server_close()
    def test_second_resource_point_is_built_and_reported(self):
        calls,rows=self.run_case()
        planned=[r for r in rows if r['event']=='economy_expansion_planned']
        self.assertTrue(planned,"the expansion decision and its site are recorded")
        self.assertEqual(planned[0]['data']['reason'],'BELOW_MINE_TARGET')
        self.assertTrue(any('move' in c and 'x=1500.0' in c for c,_ in calls),"the builder walks into build range")
        self.assertTrue(any('build-extractor' in c and 'x=1500.0' in c for c,_ in calls),"the planned site is actually built")
        observed=[r for r in rows if r['event']=='economy_expansion_observed']
        self.assertEqual(observed[0]['data']['unitId'],77)
        done=[r for r in rows if r['event']=='extractor_completed']
        self.assertEqual(len(done),1,"one new resource point completes")
        self.assertEqual(done[0]['data']['unitId'],77)
        self.assertEqual(done[0]['data']['minesCompleted'],1)
        self.assertEqual(rows[-1]['data']['completedMines'],1,"the summary is cross-checkable against the events")
        self.assertEqual(rows[-1]['data']['newMines'],1)
    def test_planned_resource_point_reserves_credits_before_production(self):
        calls,rows=self.run_case(credits_early=500,credits_late=2000)
        self.assertTrue(any('build-extractor' in c for c,_ in calls),"the mine is still built once it is affordable")
        self.assertFalse([c for c,credits in calls if 'actionId=tank' in c and credits<1050],
                         "unit production may not spend the reserved resource-point cost")
        self.assertTrue(any(r['event']=='economy_expansion_deferred' for r in rows),"the blocked investment says why")


    def test_remembered_resource_is_prospected_when_the_planner_sees_nothing(self):
        calls,rows=self.run_case(plan_after_arrival=True)
        blocked=[r for r in rows if r['event']=='economy_expansion_blocked']
        self.assertTrue(blocked,"the planner refusing a site is recorded")
        self.assertEqual(blocked[0]['data']['reason'],'NO_VISIBLE_LEGAL_SITE')
        prospect=[r for r in rows if r['event']=='economy_expansion_prospect']
        self.assertTrue(prospect,"a legally remembered resource is used as the navigation target")
        self.assertEqual(prospect[0]['data']['tile'],777)
        self.assertTrue(any('move' in c and 'x=1500.0' in c for c,_ in calls),"the builder walks to the remembered resource")
        self.assertTrue(any('build-extractor' in c for c,_ in calls),"the site is built once it enters vision")
        self.assertTrue(any(r['event']=='extractor_completed' for r in rows))
    def test_a_half_built_mine_does_not_satisfy_the_target(self):
        # 输出7 §10: mineTarget counts finished mines only, otherwise a stalled site would look like
        # completed economy and silently switch the lane over to factory expansion.
        calls,rows=self.run_case(ready_mines=2,unfinished_mines=1)
        self.assertTrue([r for r in rows if r['event']=='economy_expansion_planned'],
                        "with only two finished mines the lane still expands the economy")
        self.assertFalse([r for r in rows if r['event']=='production_facility_planned'],
                         "the unfinished third mine must not be treated as a completed building")
        ticks=[r['data'] for r in rows if r['event']=='agent_tick_alive']
        self.assertTrue(any(t.get('mines')==3 and t.get('minesReady')==2 for t in ticks),
                        "the heartbeat separates alive sites from finished mines")


class ProductionFacilityTests(unittest.TestCase):
    """P2-B: a second land factory is funded from surplus while the first one stays saturated."""
    def run_case(self,queue_busy=True,credits=5000,builder_start=(150,100),army_size=2,kill_builder=False,stall_site=False):
        calls=[];step=[0];builder=[builder_start[0],builder_start[1]];spawn=[None];purse=[credits];alive=[True]
        def unit(uid,kind,x=100,y=100,bp=1.0):
            building=kind in ('commandCenter','landFactory')
            return dict(id=uid,type=kind,x=x,y=y,hp=1000,maxHp=1000,dead=False,buildProgress=bp,
                        mobile=not building,canAttack=kind=='c_tank',building=building,techLevel=1,productionQueue=0,orderType=None)
        def own():
            st=step[0]
            units=[unit(3,'commandCenter'),unit(5,'landFactory',150,100),
                   unit(11,'extractorT1'),unit(12,'extractorT1',300,400),unit(13,'extractorT1',500,600)]
            if alive[0]:units.append(unit(4,'builder',builder[0],builder[1]))
            units+=[unit(20+i,'c_tank') for i in range(army_size)]
            if spawn[0] is not None and st>=spawn[0]:
                # stall_site: the new factory is ordered, then the builder dies and the site never finishes.
                progress=0.4 if stall_site else (0.5 if st<spawn[0]+2 else 1.0)
                units.append(unit(88,'landFactory',200,100,progress))
            return units
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*a):pass
            def do_POST(self):
                from urllib.parse import urlsplit,parse_qs
                q=parse_qs(urlsplit(self.path).query);calls.append((self.path,purse[0]))
                if 'build-factory' in self.path:
                    if stall_site:
                        spawn[0]=step[0]+1                   # the site appears ...
                        alive[0]=False                       # ... and the builder dies before finishing it
                    elif kill_builder:
                        alive[0]=False                       # the builder dies on the way; nothing is built
                    else:
                        spawn[0]=step[0]+1                   # the real engine only builds after the order
                if 'command/move' in self.path:builder[0],builder[1]=100,100
                self.send(dict(status='queued',sessionId='s',requestId=q['requestId'][0],unitId=4,frame=step[0],type='c_tank'))
            def do_GET(self):
                if self.path=='/health':return self.send(dict(status='ok',version='0.07-alpha1'))
                if self.path=='/state':
                    step[0]+=1;end=step[0]>=12;now=step[0]*2000
                    return self.send(dict(status='running',sessionId='s',frame=step[0],gameTimeMs=now,networked=False,replay=False,
                        player={'credits':purse[0]},
                        map={'width':2200,'height':2200,'tilesWide':110,'tilesHigh':110,'tileWidth':20,'tileHeight':20},
                        match=dict(outcome='DEFEAT' if end else 'ONGOING',nativeDefeat=end,nativeVictory=False,source='native_result_screen'),
                        ownUnits=own()))
                if self.path.startswith('/economy/plan'):
                    return self.send(dict(status='planned',sessionId='s',builderId=4,factoryType='landFactory',productType='c_tank',
                        targetX=200,targetY=100,factoryCost=1000,tankCost=350,credits=purse[0]))
                if self.path=='/scout/observe':
                    return self.send(dict(status='observed',sessionId='s',frame=step[0],gameTimeMs=step[0]*2000,
                        fogEnabled=True,lineOfSightFog=True,visibleTiles=100,exploredTiles=5000,initialVisibleTiles=100,
                        newlyObservedTiles=0,resources=[],visibleThreats=[],rememberedThreats=[]))
                if self.path=='/combat/production':
                    return self.send(dict(status='observed',sessionId='s',factories=[dict(id=5,tier=2,queue=1 if queue_busy else 0,
                        actions=[dict(actionId='tank',type='c_tank',cost=350,affordable=True),
                                 dict(actionId='heavy',type='heavyTank',cost=900,affordable=credits>=900)])]))
                if self.path=='/combat/observe':
                    return self.send(dict(status='observed',sessionId='s',gameTimeMs=step[0]*2000,visibleEnemies=[],rememberedEnemies=[]))
                return self.send(dict(status='no_frontier',sessionId='s'))
            def send(self,d,status=200):
                b=json.dumps(d).encode();self.send_response(status);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            with tempfile.TemporaryDirectory(dir=pathlib.Path.cwd()) as cwd:
                p=subprocess.run(['java','-Drwagent.pollMs=60','-Drwagent.expansionIntervalGameMs=2000','-Drwagent.port='+str(server.server_port),'-cp',JAR,'io.rwagent.client.BattleClient','120'],cwd=cwd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=20)
                self.assertEqual(p.returncode,0,p.stdout.decode())
                report=next(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                return calls,[json.loads(x) for x in report.read_text().splitlines()]
        finally:server.shutdown();server.server_close()
    def test_surplus_with_a_saturated_factory_builds_a_second_one(self):
        calls,rows=self.run_case()
        planned=[r for r in rows if r['event']=='production_facility_planned']
        self.assertTrue(planned,"the investment decision and its site are recorded")
        self.assertEqual(planned[0]['data']['reason'],'SURPLUS_WITH_BUSY_FACTORY')
        self.assertEqual(planned[0]['data']['factories'],1)
        self.assertEqual(planned[0]['data']['factoryCost'],1000)
        self.assertEqual(planned[0]['data']['preferredUnit'],'heavyTank',"the surplus is measured against the unit the factory would really build")
        self.assertEqual(planned[0]['data']['preferredUnitCost'],900)
        self.assertTrue(any('build-factory' in c and 'x=200.0' in c for c,_ in calls),"the planned site is actually built")
        self.assertEqual(len([c for c,_ in calls if 'build-factory' in c]),1,"exactly one extra factory is ordered")
        spawned=[r for r in rows if r['event']=='production_facility_observed']
        self.assertEqual(spawned[0]['data']['unitId'],88)
        done=[r for r in rows if r['event']=='factory_completed']
        self.assertEqual(len(done),1,"one new factory completes")
        self.assertEqual(done[0]['data']['factoriesCompleted'],1)
        self.assertTrue(any(r['event']=='production_facility_finished' for r in rows),"reaching the target is recorded")
        self.assertEqual(rows[-1]['data']['completedFactories'],1,"the summary is cross-checkable against the events")
        self.assertEqual(rows[-1]['data']['newFactories'],1)
        self.assertEqual(rows[-1]['data']['landFactoryTarget'],2)
    def test_the_surplus_diagnostic_names_its_evidence(self):
        calls,rows=self.run_case()
        surplus=[r for r in rows if r['event']=='production_surplus']
        self.assertTrue(surplus,"the saturation that justifies the investment is measured, not assumed")
        data=surplus[0]['data']
        self.assertEqual(data['credits'],5000)
        self.assertEqual(data['completedMines'],0)
        self.assertEqual(data['completedFactories'],0)
        self.assertTrue(data['factoryQueueNonEmpty'])
        self.assertEqual(data['preferredUnit'],'heavyTank')
        self.assertEqual(data['preferredUnitCost'],900)
    def test_an_idle_factory_is_not_duplicated(self):
        # A factory that is idle because the army is already at its cap is not a demand signal.
        # 输出19 §2: the cap default is 40 now, so the fixture must sit at the real cap.
        calls,rows=self.run_case(queue_busy=False,army_size=40)
        blocked=[r for r in rows if r['event']=='production_facility_blocked']
        self.assertTrue(blocked,"a refused investment is recorded")
        self.assertEqual(blocked[0]['data']['reason'],'FACTORY_QUEUE_EMPTY')
        self.assertFalse([c for c,_ in calls if 'build-factory' in c],"production capacity is only added under real demand")
    def test_the_builder_returns_to_the_rear_before_the_factory_is_planned(self):
        calls,rows=self.run_case(builder_start=(900,900))
        move=[r for r in rows if r['event']=='production_facility_move']
        self.assertTrue(move,"the site must be chosen from the rear, not the front line")
        self.assertEqual(move[0]['data']['reason'],'RETURNING_TO_REAR')
        moves=[i for i,(c,_) in enumerate(calls) if 'command/move' in c]
        builds=[i for i,(c,_) in enumerate(calls) if 'build-factory' in c]
        self.assertTrue(moves and builds and moves[0]<builds[0],"the factory order follows the move home")
        self.assertEqual([r for r in rows if r['event']=='production_facility_planned'][0]['data']['reason'],'SURPLUS_WITH_BUSY_FACTORY')
    def test_a_lost_builder_abandons_a_plan_that_never_started(self):
        calls,rows=self.run_case(kill_builder=True)
        state=[r for r in rows if r['event']=='production_facility_state']
        self.assertEqual(len(state),1,"the state change is reported once, not on every tick")
        self.assertEqual(state[0]['data']['state'],'ABANDONED_NO_BUILDER')
        self.assertEqual(state[0]['data']['reason'],'BUILDER_LOST_BEFORE_CONSTRUCTION')
        after=[r for r in rows if r['event']=='production_facility_blocked' and r['data']['reason']=='NO_BUILDER']
        self.assertTrue(after,"the lane keeps reporting why nothing can be built")
        self.assertFalse([c for c,_ in calls if 'build-factory' in c][1:],"a site is ordered at most once")
    def test_an_unfinished_site_is_parked_when_the_builder_dies(self):
        # 输出7 §10: a real half-built site is not forgotten and not retried forever; it is parked with
        # its site and progress so a later builder can continue it.
        calls,rows=self.run_case(stall_site=True)
        state=[r for r in rows if r['event']=='production_facility_state']
        self.assertEqual(len(state),1,"one transition report for the stalled site")
        self.assertEqual(state[0]['data']['state'],'STALLED_NO_BUILDER')
        self.assertEqual(state[0]['data']['reason'],'BUILDER_LOST_WITH_UNFINISHED_SITE')
        self.assertEqual(state[0]['data']['unitId'],88,"the unfinished site is kept by id")
        self.assertEqual(state[0]['data']['x'],200.0,"and by site")
        self.assertTrue(0<state[0]['data']['buildProgress']<1.0,"and by progress")
        self.assertEqual(len([r for r in rows if r['event']=='production_facility_planned']),1,
                         "a parked job must not re-plan every interval")
        stalled_at=[i for i,r in enumerate(rows) if r['event']=='production_facility_state'][0]
        self.assertFalse([r for r in rows[stalled_at+1:] if r['event']=='production_facility_blocked'],
                         "the parked lane does not spam blocked events either")
        ticks=[r['data'] for r in rows if r['event']=='agent_tick_alive']
        self.assertTrue(any(t.get('buildStalled') is True for t in ticks),"the stall is visible in the heartbeat")


# ProductionDecisionTests moved into ProductionBudgetTests: P2-B2 forbids the primary producer from
# falling back to a cheap unit, so the fallback case is now a secondary-producer scenario.

class BuilderRecoveryTests(unittest.TestCase):
    """P2-C1: a dead builder reserves its replacement price and is ordered as soon as production frees."""
    def run_case(self):
        calls=[];step=[0];spawn=[None]
        def unit(uid,kind,x=100,y=100,bp=1.0):
            building=kind in ('commandCenter','landFactory')
            return dict(id=uid,type=kind,x=x,y=y,hp=1000,maxHp=1000,dead=False,buildProgress=bp,
                        mobile=not building,canAttack=False,building=building,techLevel=2,
                        productionQueue=0,orderType=None)
        def credits_at(st):return 600 if st<5 else (400 if st<8 else 2000)
        def own(st):
            units=[unit(3,'commandCenter'),unit(5,'landFactory'),unit(11,'extractorT1')]
            units+=[unit(20+i,'c_tank') for i in range(4)]
            if spawn[0] is not None and st>=spawn[0]:units.append(unit(9,'builder'))
            return units
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*a):pass
            def do_POST(self):
                from urllib.parse import urlsplit,parse_qs
                q=parse_qs(urlsplit(self.path).query);calls.append(self.path)
                if 'produce-builder' in self.path:spawn[0]=step[0]+2
                self.send(dict(status='queued',sessionId='s',requestId=q['requestId'][0],unitId=3,frame=step[0],type='builder'))
            def do_GET(self):
                st=step[0]
                if self.path=='/health':return self.send(dict(status='ok',version='0.07-alpha1'))
                if self.path=='/economy/builder-production':
                    # The producer is busy first, then free; money arrives late.
                    return self.send(dict(status='planned',sessionId='s',producerId=3,producerType='commandCenter',
                        queueCount=1 if st<5 else 0,buildersQueued=0,builderActionId='ActionId(u_builder)',
                        builderActionAvailable=True,builderCost=500,builderType='builder',
                        availableCredits=600,existingBuilders=0,builderOrderPending=False))
                if self.path=='/state':
                    step[0]+=1;end=step[0]>=14;now=step[0]*2000
                    credits=credits_at(step[0])
                    return self.send(dict(status='running',sessionId='s',frame=step[0],gameTimeMs=now,networked=False,replay=False,
                        player={'credits':credits},
                        map={'width':2200,'height':2200,'tilesWide':110,'tilesHigh':110,'tileWidth':20,'tileHeight':20},
                        match=dict(outcome='DEFEAT' if end else 'ONGOING',nativeDefeat=end,nativeVictory=False,source='native_result_screen'),
                        ownUnits=own(step[0])))
                if self.path=='/combat/production':
                    credits=credits_at(step[0])
                    return self.send(dict(status='observed',sessionId='s',factories=[dict(id=5,tier=2,queue=0,actions=[
                        dict(actionId='tank',type='c_tank',cost=350,affordable=True),
                        dict(actionId='heavy',type='heavyTank',cost=800,affordable=credits>=800)])]))
                if self.path=='/scout/observe':
                    return self.send(dict(status='observed',sessionId='s',frame=step[0],gameTimeMs=step[0]*2000,
                        fogEnabled=True,lineOfSightFog=True,visibleTiles=100,exploredTiles=5000,initialVisibleTiles=100,
                        newlyObservedTiles=0,resources=[],visibleThreats=[],rememberedThreats=[]))
                if self.path=='/combat/observe':
                    return self.send(dict(status='observed',sessionId='s',gameTimeMs=step[0]*2000,visibleEnemies=[],rememberedEnemies=[]))
                return self.send(dict(status='no_frontier',sessionId='s'))
            def send(self,d):
                b=json.dumps(d).encode();self.send_response(200);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            with tempfile.TemporaryDirectory(dir=pathlib.Path.cwd()) as cwd:
                p=subprocess.run(['java','-Drwagent.pollMs=60','-Drwagent.port='+str(server.server_port),'-cp',JAR,'io.rwagent.client.BattleClient','120'],cwd=cwd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=20)
                self.assertEqual(p.returncode,0,p.stdout.decode())
                report=next(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                return calls,[json.loads(x) for x in report.read_text().splitlines()]
        finally:server.shutdown();server.server_close()
    def test_recovery_reserves_money_then_orders_and_completes(self):
        calls,rows=self.run_case()
        started=[r['data'] for r in rows if r['event']=='builder_recovery_started']
        self.assertTrue(started,"losing the only builder starts recovery")
        self.assertEqual(started[0]['aliveBuilders'],0)
        waiting=[r['data'] for r in rows if r['event']=='builder_recovery_waiting']
        reasons=[w['reason'] for w in waiting]
        self.assertIn('PRODUCER_BUSY',reasons,"a busy command centre is not preempted, it is waited for")
        self.assertIn('INSUFFICIENT_CREDITS',reasons,"the reserve is held while the money is not there yet")
        self.assertEqual(waiting[0]['builderCost'],500,"the native price is reported")
        self.assertEqual(waiting[0]['queueCount'],1)
        deferred=[r['data'] for r in rows if r['event']=='production_deferred']
        self.assertTrue(deferred,"combat production records why it holds money back")
        self.assertEqual(deferred[0]['reason'],'RESERVED_FOR_BUILDER_RECOVERY')
        self.assertEqual(deferred[0]['reservedFor'],'BUILDER_RECOVERY')
        self.assertEqual(deferred[0]['reservedCost'],500)
        self.assertEqual(deferred[0]['candidateUnit'],'c_tank')
        firstQueue=next((i for i,c in enumerate(calls) if 'command/queue' in c),None)
        firstBuilder=next(i for i,c in enumerate(calls) if 'produce-builder' in c)
        self.assertTrue(firstQueue is None or firstQueue>firstBuilder,
                        "no combat unit is bought out of the money reserved for the builder")
        self.assertTrue(any('produce-builder' in c and 'unitId=3' in c for c in calls),"the replacement is ordered")
        ordered=[r['data'] for r in rows if r['event']=='builder_recovery_ordered']
        self.assertEqual(ordered[0]['builderCost'],500)
        done=[r['data'] for r in rows if r['event']=='builder_recovery_completed']
        self.assertTrue(done,"recovery completes when the new builder exists")
        self.assertEqual(done[0]['aliveBuilders'],1)
        ticks=[r['data'] for r in rows if r['event']=='agent_tick_alive']
        self.assertTrue(any(t.get('builderReserve')==500 for t in ticks),"the heartbeat shows the held reserve")
        self.assertTrue(any(t.get('builderRecovery') is True for t in ticks))


class RecoveryProbeTests(unittest.TestCase):
    """输出10 §1-§8: one probe per stalled site; only real progress counts as recovery."""
    def run_case(self,probe_accepted=True,double_charge=False):
        calls=[];step=[0];builder_alive=[True];builder_position=[1000,1000];mine=[None];newbuilder=[None];purse=[3000];probed=[False]
        def unit(uid,kind,x=100,y=100,bp=1.0):
            building=kind in ('commandCenter','landFactory')
            return dict(id=uid,type=kind,x=x,y=y,hp=1000,maxHp=1000,dead=False,buildProgress=bp,
                        mobile=not building,canAttack=False,building=building,techLevel=1,productionQueue=0,orderType=None)
        def own(st):
            units=[unit(3,'commandCenter'),unit(5,'landFactory'),unit(11,'extractorT1')]
            units+=[unit(20+i,'c_tank') for i in range(4)]
            if builder_alive[0]:units.append(unit(4,'builder',builder_position[0],builder_position[1]))
            if newbuilder[0] is not None and st>=newbuilder[0]:units.append(unit(9,'builder',1500,1000))
            if mine[0] is not None:
                progress=0.8 if probed[0] else 0.4          # the probe only succeeds if this rises
                units.append(unit(77,'extractorT1',1500,1000,progress))
            return units
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*a):pass
            def do_POST(self):
                from urllib.parse import urlsplit,parse_qs
                q=parse_qs(urlsplit(self.path).query);calls.append(self.path)
                if 'produce-builder' in self.path:newbuilder[0]=step[0]+1
                if 'command/move' in self.path:builder_position[0],builder_position[1]=1500,1000
                if 'build-extractor' in self.path:
                    if mine[0] is None:mine[0]=step[0]+1
                    else:
                        if not probe_accepted:                   # the probe is refused by the native side
                            return self.send(dict(status='error',message='no visible legal building footprint'),
                                             409)
                        probed[0]=True                           # the native side continues the site ...
                        if double_charge:purse[0]-=700           # ... but 输出11 §2: charged the whole price again
                self.send(dict(status='queued',sessionId='s',requestId=q['requestId'][0],unitId=4,frame=step[0],type='extractorT1'))
            def do_GET(self):
                st=step[0]
                if self.path=='/health':return self.send(dict(status='ok',version='0.07-alpha1'))
                if self.path=='/state':
                    step[0]+=1
                    # The builder dies only after the half-built mine has actually been observed.
                    if mine[0] is not None and step[0]>=mine[0]+1:builder_alive[0]=False
                    end=step[0]>=14
                    return self.send(dict(status='running',sessionId='s',frame=step[0],gameTimeMs=step[0]*2000,networked=False,replay=False,
                        player={'credits':purse[0]},
                        map={'width':2200,'height':2200,'tilesWide':110,'tilesHigh':110,'tileWidth':20,'tileHeight':20},
                        match=dict(outcome='DEFEAT' if end else 'ONGOING',nativeDefeat=end,nativeVictory=False,source='native_result_screen'),
                        ownUnits=own(step[0])))
                if self.path.startswith('/expansion/plan'):
                    return self.send(dict(status='planned',sessionId='s',builderId=4,extractorType='extractorT1',
                        extractorX=1500,extractorY=1000,extractorCost=700,resourceCandidates=1,
                        diagnostics={'visibleResourceCandidates':1,'legalCandidates':1,'notReachable':0}))
                if self.path=='/economy/builder-production':
                    return self.send(dict(status='planned',sessionId='s',producerId=3,producerType='commandCenter',
                        queueCount=0,buildersQueued=0,builderActionId='ActionId(u_builder)',builderActionAvailable=True,
                        builderCost=500,builderType='builder',availableCredits=purse[0],existingBuilders=0,builderOrderPending=False))
                if self.path.startswith('/economy/builder-actions'):
                    return self.send(dict(status='planned',sessionId='s',unitId=9,unitType='builder',actionCount=2,
                        hasRecoveryPath=False,recoveryPath='NONE',actions=[dict(class_='v',type='extractor',actionId='b_extractor',cost=700,
                        available=True,affordable=True,buildAction=True,recoveryCandidate=True)]))
                if self.path=='/combat/production':
                    return self.send(dict(status='observed',sessionId='s',factories=[dict(id=5,tier=2,queue=0,
                        actions=[dict(actionId='tank',type='c_tank',cost=350,affordable=True)])]))
                if self.path=='/scout/observe':
                    return self.send(dict(status='observed',sessionId='s',frame=step[0],gameTimeMs=step[0]*2000,
                        fogEnabled=True,lineOfSightFog=True,visibleTiles=100,exploredTiles=5000,initialVisibleTiles=100,
                        newlyObservedTiles=0,resources=[],visibleThreats=[],rememberedThreats=[]))
                if self.path=='/combat/observe':
                    return self.send(dict(status='observed',sessionId='s',gameTimeMs=step[0]*2000,visibleEnemies=[],rememberedEnemies=[]))
                return self.send(dict(status='no_frontier',sessionId='s'))
            def send(self,d,status=200):
                b=json.dumps(d).encode();self.send_response(status);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            with tempfile.TemporaryDirectory(dir=pathlib.Path.cwd()) as cwd:
                p=subprocess.run(['java','-Drwagent.pollMs=60','-Drwagent.expansionIntervalGameMs=2000','-Drwagent.port='+str(server.server_port),'-cp',JAR,'io.rwagent.client.BattleClient','120'],cwd=cwd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=20)
                self.assertEqual(p.returncode,0,p.stdout.decode())
                report=next(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                return calls,[json.loads(x) for x in report.read_text().splitlines()]
        finally:server.shutdown();server.server_close()
    def test_accepted_probe_only_recovers_after_real_progress(self):
        calls,rows=self.run_case(probe_accepted=True)
        stalled=[r['data'] for r in rows if r['event']=='economy_expansion_state']
        self.assertEqual([s['state'] for s in stalled],['STALLED_NO_BUILDER'],"a half-built site is parked, never faked active")
        probes=[r['data'] for r in rows if r['event']=='economy_expansion_recovery_probe']
        self.assertEqual(len(probes),1,"the stalled site is probed exactly once")
        self.assertEqual(probes[0]['buildProgress'],0.4)
        accepted=[r['data'] for r in rows if r['event']=='economy_expansion_recovery_probe_accepted']
        self.assertEqual([a['state'] for a in accepted],['RECOVERY_PROBE_ACCEPTED'],"acceptance is not recovery")
        resumed=[r['data'] for r in rows if r['event']=='economy_expansion_recovery_resumed']
        self.assertTrue(resumed,"progress on the same candidate resumes the job")
        self.assertEqual(resumed[0]['buildProgressAfter'],0.8)
        self.assertEqual(resumed[0]['buildProgressBefore'],0.4)
        self.assertEqual(resumed[0]['resumedFrom'],0.4)
        self.assertEqual(resumed[0]['creditsBeforeProbe'],3000)
        self.assertEqual(resumed[0]['creditsAfterProbe'],3000,"continuing the site must not charge again")
        self.assertEqual(resumed[0]['probeCostDelta'],0)
        self.assertLess([r['wallTimeMs'] for r in rows if r['event']=='economy_expansion_recovery_probe_accepted'][0],
                        [r['wallTimeMs'] for r in rows if r['event']=='economy_expansion_recovery_resumed'][0],
                        "the resume report comes after the accepted probe")
        builds=[c for c in calls if 'build-extractor' in c]
        self.assertEqual(len(builds),2,"one original build plus one probe, never a retry loop")
    def test_rejected_probe_abandons_the_site_and_blocks_it(self):
        calls,rows=self.run_case(probe_accepted=False)
        rejected=[r['data'] for r in rows if r['event']=='economy_expansion_recovery_probe_rejected']
        self.assertEqual(len(rejected),1,"a refused probe is recorded once")
        actions=[r['data'] for r in rows if r['event']=='builder_recovery_actions']
        self.assertTrue(actions,"the builder's native actions are inspected before giving up")
        self.assertFalse(actions[0]['hasRecoveryPath'])
        abandoned=[r['data'] for r in rows if r['event']=='economy_expansion_abandoned_unrecoverable']
        self.assertEqual(len(abandoned),1,"the job is abandoned explicitly exactly once")
        self.assertEqual(abandoned[0]['state'],'ABANDONED_UNRECOVERABLE')
        self.assertEqual(abandoned[0]['blockedSite'],'1500,1000')
        blocked=[r['data'] for r in rows if r['event']=='economy_expansion_blocked']
        self.assertTrue(any(b['reason']=='UNUSABLE_UNFINISHED_SITE' for b in blocked),
                        "the planner refuses to rebuild on the unfinished debris")
        self.assertFalse([r for r in rows if r['event']=='economy_expansion_recovery_resumed'],
                         "a refused probe never reports recovery")
    def test_a_second_full_charge_is_not_accepted_as_recovery(self):
        # 输出11 §2/§3: resuming a half-built site may not cost the building price twice. If the native
        # side charges again, the probe is not a valid recovery path and the site is abandoned instead.
        calls,rows=self.run_case(double_charge=True)
        rejected=[r['data'] for r in rows if r['event']=='economy_expansion_recovery_probe_rejected']
        self.assertEqual(len(rejected),1,"the second charge is reported exactly once")
        self.assertEqual(rejected[0]['reason'],'DOUBLE_CHARGED_BUILD_COST')
        self.assertEqual(rejected[0]['creditsBeforeProbe'],3000)
        self.assertEqual(rejected[0]['creditsAfterProbe'],2300)
        self.assertEqual(rejected[0]['probeCostDelta'],700)
        self.assertEqual(rejected[0]['buildCost'],700)
        self.assertEqual(rejected[0]['buildProgressBefore'],0.4)
        self.assertEqual(rejected[0]['buildProgressAfter'],0.8)
        self.assertFalse([r for r in rows if r['event']=='economy_expansion_recovery_resumed'],
                         "paying the full price again must never be reported as recovery")
        abandoned=[r['data'] for r in rows if r['event']=='economy_expansion_abandoned_unrecoverable']
        self.assertEqual(len(abandoned),1,"an unacceptable probe falls back to the clean failure path")
        self.assertEqual(abandoned[0]['blockedSite'],'1500,1000')


class ProductionBudgetTests(unittest.TestCase):
    """P2-B2: the primary producer never degrades, secondaries may only consume surplus."""
    def run_case(self,factories,credits,army=4,reject_queue=False):
        calls=[];step=[0]
        def unit(uid,kind,x=100,y=100):
            building=kind in ('commandCenter','landFactory')
            return dict(id=uid,type=kind,x=x,y=y,hp=1000,maxHp=1000,dead=False,buildProgress=1,
                        mobile=not building,canAttack=kind=='c_tank',building=building,techLevel=1,
                        productionQueue=0,orderType=None)
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*a):pass
            def do_POST(self):
                from urllib.parse import urlsplit,parse_qs
                q=parse_qs(urlsplit(self.path).query)
                calls.append(self.path)
                if reject_queue:
                    return self.send(dict(status='error',message='action unavailable, locked, or insufficient credits'),409)
                self.send(dict(status='queued',sessionId='s',requestId=q['requestId'][0],unitId=5,frame=step[0],type='c_tank'))
            def do_GET(self):
                if self.path=='/health':return self.send(dict(status='ok',version='0.07-alpha1'))
                if self.path=='/state':
                    step[0]+=1;end=step[0]>=5
                    return self.send(dict(status='running',sessionId='s',frame=step[0],gameTimeMs=step[0]*2000,networked=False,replay=False,
                        player={'credits':credits},map={'width':2200,'height':2200},
                        match=dict(outcome='DEFEAT' if end else 'ONGOING',nativeDefeat=end,nativeVictory=False,source='native_result_screen'),
                        ownUnits=[unit(3,'commandCenter'),unit(4,'builder',150,100),unit(11,'extractorT1'),
                                  unit(12,'extractorT1',300,400),unit(13,'extractorT1',500,600)]
                                 +[unit(20+i,'c_tank') for i in range(army)]))
                if self.path=='/combat/production':
                    return self.send(dict(status='observed',sessionId='s',factories=[
                        dict(id=f['id'],tier=f['tier'],queue=f['queue'],
                             actions=[dict(actionId=a[0],type=a[1],cost=a[2],affordable=a[3]) for a in f['actions']])
                        for f in factories]))
                if self.path=='/economy/builder-production':
                    return self.send(dict(status='planned',sessionId='s',producerId=3,producerType='commandCenter',
                        queueCount=0,builderCost=500,builderActionAvailable=True,existingBuilders=1,builderOrderPending=False))
                if self.path=='/combat/capabilities':
                    return self.send(dict(status='observed',sessionId='s',units=[],unavailable=[]))
                if self.path=='/combat/observe':return self.send(dict(status='observed',sessionId='s',gameTimeMs=step[0]*2000,visibleEnemies=[],rememberedEnemies=[]))
                if self.path=='/scout/observe':
                    return self.send(dict(status='observed',sessionId='s',frame=step[0],gameTimeMs=step[0]*2000,fogEnabled=True,
                        lineOfSightFog=True,visibleTiles=100,exploredTiles=5000,initialVisibleTiles=100,newlyObservedTiles=0,
                        resources=[],visibleThreats=[],rememberedThreats=[]))
                return self.send(dict(status='no_frontier',sessionId='s'))
            def send(self,d,code=200):
                b=json.dumps(d).encode();self.send_response(code);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            with tempfile.TemporaryDirectory(dir=pathlib.Path.cwd()) as cwd:
                p=subprocess.run(['java','-Drwagent.pollMs=60','-Drwagent.port='+str(server.server_port),'-cp',JAR,'io.rwagent.client.BattleClient','120'],cwd=cwd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=20)
                self.assertEqual(p.returncode,0,p.stdout.decode())
                report=next(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                return calls,[json.loads(x) for x in report.read_text().splitlines()]
        finally:server.shutdown();server.server_close()
    def test_primary_never_degrades_and_its_money_is_protected(self):
        # T2 primary wants heavyTank(800) but only has 500; a T1 secondary could buy c_tank(350).
        # P2-B2 must buy neither: the money stays reserved for the mainline unit.
        calls,rows=self.run_case([
            dict(id=5,tier=2,queue=0,actions=[('heavy','heavyTank',800,False),('tank','c_tank',350,True)]),
            dict(id=9,tier=1,queue=0,actions=[('tank','c_tank',350,True)])],
            credits=500)
        self.assertFalse([c for c in calls if 'command/queue' in c],
                         "no cheap unit is bought while the mainline is unaffordable")
        deferred=[r['data'] for r in rows if r['event']=='production_deferred']
        self.assertTrue(deferred,"the held money is recorded")
        primary=[d for d in deferred if d['factoryId']==5]
        self.assertEqual(primary[0]['reservedFor'],'PRIMARY_PRODUCTION')
        self.assertEqual(primary[0]['reason'],'RESERVED_FOR_PRIMARY_PRODUCTION')
        self.assertEqual(primary[0]['candidateUnit'],'heavyTank')
        self.assertEqual(primary[0]['candidateCost'],800)
        self.assertEqual(primary[0]['credits'],500)
        secondary=[d for d in deferred if d['factoryId']==9]
        self.assertEqual(secondary[0]['reservedFor'],'PRIMARY_PRODUCTION',"the secondary is also held back")
        ticks=[r['data'] for r in rows if r['event']=='agent_tick_alive']
        self.assertTrue(any(t.get('productionIdleReason')=='BANKING_FOR_PRIMARY_UNIT' for t in ticks))
    def test_secondary_may_spend_while_the_primary_is_producing(self):
        calls,rows=self.run_case([
            dict(id=5,tier=2,queue=1,actions=[('heavy','heavyTank',800,True)]),
            dict(id=9,tier=1,queue=0,actions=[('tank','c_tank',350,True)])],
            credits=900)
        self.assertTrue(any('unitId=9' in c for c in calls),"the secondary produces when the primary is busy")
        self.assertFalse([r for r in rows if r['event']=='production_deferred'],
                         "nothing is held back while the mainline factory is already working")
    def test_primary_buys_the_mainline_when_it_can_afford_it(self):
        calls,rows=self.run_case([
            dict(id=5,tier=2,queue=0,actions=[('heavy','heavyTank',800,True),('tank','c_tank',350,True)]),
            dict(id=9,tier=1,queue=1,actions=[('tank','c_tank',350,True)])],
            credits=900)
        self.assertTrue(any('unitId=5' in c for c in calls),"the primary orders its mainline unit")
        self.assertFalse([r for r in rows if r['event']=='production_deferred'])
    def test_secondary_upgrade_waits_for_surplus(self):
        calls,rows=self.run_case([
            dict(id=5,tier=2,queue=0,actions=[('heavy','heavyTank',800,False)]),
            dict(id=9,tier=1,queue=0,actions=[('upgrade','upgrade',700,True),('tank','c_tank',350,True)])],
            credits=900,army=6)
        deferred=[r['data'] for r in rows if r['event']=='production_deferred']
        investment=[d for d in deferred if d['reservedFor']=='SECONDARY_INVESTMENT']
        self.assertTrue(investment,"the secondary upgrade is treated as an investment, not a priority")
        self.assertEqual(investment[0]['reason'],'SECONDARY_INVESTMENT_DEFERRED')
        self.assertEqual(investment[0]['factoryId'],9)
        self.assertFalse([c for c in calls if 'command/queue' in c],
                         "neither the upgrade nor a cheap unit may take the primary reserve")
    def test_primary_tech_banking_is_distinguished(self):
        # Only a T1 factory exists, so the upgrade is what unlocks the mainline unit: banking for it is
        # primary tech enablement, not a secondary investment.
        calls,rows=self.run_case([
            dict(id=5,tier=1,queue=0,actions=[('upgrade','upgrade',900,False),('tank','c_tank',350,True)])],
            credits=1200,army=10)
        deferred=[r['data'] for r in rows if r['event']=='production_deferred']
        tech=[d for d in deferred if d['reservedFor']=='PRIMARY_TECH']
        self.assertTrue(tech,"banking for the enabling upgrade is recorded as primary tech")
        self.assertEqual(tech[0]['reason'],'PRIMARY_TECH_BANKING')
        self.assertTrue(any(r['data'].get('reason')=='BANKING_FOR_UPGRADE' for r in rows if r['event']=='production_idle'))
    def test_secondary_cheap_fallback_is_recorded(self):
        # With the primary busy its reserve is not held, so a secondary factory may buy a cheap unit when
        # its own preferred one is unaffordable - and that fallback is still reported as such.
        calls,rows=self.run_case([
            dict(id=5,tier=2,queue=1,actions=[('heavy','heavyTank',800,True)]),
            dict(id=9,tier=2,queue=0,actions=[('heavy','heavyTank',800,False),('tank','c_tank',350,True)])],
            credits=500)
        decisions=[r['data'] for r in rows if r['event']=='production_decision']
        self.assertTrue(decisions,"the secondary fallback is recorded")
        self.assertEqual(decisions[0]['reason'],'PREFERRED_UNAFFORDABLE_FALLBACK')
        self.assertEqual(decisions[0]['factoryId'],9)
        self.assertEqual(decisions[0]['preferredUnit'],'heavyTank')
        self.assertEqual(decisions[0]['chosenUnit'],'c_tank')
        self.assertTrue(any('unitId=9' in c for c in calls),"the secondary actually places the order")
    def test_affordable_preferred_unit_is_not_a_fallback(self):
        calls,rows=self.run_case([
            dict(id=5,tier=2,queue=0,actions=[('heavy','heavyTank',800,True),('tank','c_tank',350,True)]),
            dict(id=9,tier=1,queue=1,actions=[('tank','c_tank',350,True)])],
            credits=2000)
        self.assertTrue(any('actionId=heavy' in c for c in calls),"the primary orders the mainline unit")
        self.assertFalse([r for r in rows if r['event']=='production_decision'])
    def test_secondary_upgrade_deferral_still_allows_the_cheap_unit(self):
        # 输出15 §2: the secondary's upgrade is opportunistic. With the primary busy no reserve is held, so
        # the 2000 upgrade stays unaffordable while the 350 combat unit is legal: the factory must build it
        # instead of idling for the upgrade.
        calls,rows=self.run_case([
            dict(id=5,tier=2,queue=1,actions=[('heavy','heavyTank',800,True)]),
            dict(id=9,tier=1,queue=0,actions=[('upgrade','upgrade',2000,False),('tank','c_tank',350,True)])],
            credits=500,army=11)
        deferred=[r['data'] for r in rows if r['event']=='production_deferred']
        investment=[d for d in deferred if d['reservedFor']=='SECONDARY_INVESTMENT']
        self.assertTrue(investment,"the unaffordable upgrade is still recorded as a surplus investment")
        self.assertEqual(investment[0]['reason'],'SECONDARY_INVESTMENT_DEFERRED')
        self.assertEqual(investment[0]['reservedCost'],0,"no primary reserve is held while it is producing")
        fallback=[r['data'] for r in rows if r['event']=='SECONDARY_INVESTMENT_FALLBACK']
        self.assertTrue(fallback,"the cheap unit is recorded as the deferred upgrade's replacement")
        self.assertEqual(fallback[0]['factoryId'],9)
        self.assertEqual(fallback[0]['chosenUnit'],'c_tank')
        self.assertEqual(fallback[0]['chosenCost'],350)
        self.assertEqual(fallback[0]['deferredUpgradeCost'],2000)
        self.assertEqual(fallback[0]['primaryReserve'],0)
        self.assertTrue(any('unitId=9' in c for c in calls),"the secondary still builds its cheap unit")
    def test_secondary_cheap_unit_still_blocked_by_the_primary_reserve(self):
        # The cheap fallback is only legal out of surplus. While the idle primary still needs 800 for its
        # mainline unit, neither the upgrade nor the 350 fallback may spend the reserve down to nothing.
        calls,rows=self.run_case([
            dict(id=5,tier=2,queue=0,actions=[('heavy','heavyTank',800,False),('tank','c_tank',350,True)]),
            dict(id=9,tier=1,queue=0,actions=[('tank','c_tank',350,True)])],
            credits=500)
        self.assertFalse([c for c in calls if 'unitId=9' in c],
                         "the secondary may not spend the money the primary is saving")
        self.assertFalse([r for r in rows if r['event']=='SECONDARY_INVESTMENT_FALLBACK'],
                         "no fallback is recorded when the cheap unit itself breaches the reserve")
        held=[d for d in (r['data'] for r in rows if r['event']=='production_deferred') if d['factoryId']==9]
        self.assertTrue(held,"the secondary's refusal is recorded")
        self.assertEqual(held[0]['reservedFor'],'PRIMARY_PRODUCTION')
        self.assertEqual(held[0]['reason'],'RESERVED_FOR_PRIMARY_PRODUCTION')
        self.assertEqual(held[0]['reservedCost'],800)
    def test_secondary_cheap_unit_allowed_once_the_reserve_is_covered(self):
        # 1400 credits, a 350 unit and an 800 reserve: after the unit is paid for the reserve is still
        # intact, so the surplus genuinely exists and the order goes through.
        calls,rows=self.run_case([
            dict(id=5,tier=2,queue=0,actions=[('heavy','heavyTank',800,False)]),
            dict(id=9,tier=1,queue=0,actions=[('upgrade','upgrade',2000,False),('tank','c_tank',350,True)])],
            credits=1400)
        fallback=[r['data'] for r in rows if r['event']=='SECONDARY_INVESTMENT_FALLBACK']
        self.assertTrue(fallback,"the covered reserve makes the cheap unit a legal surplus buy")
        self.assertEqual(fallback[0]['factoryId'],9)
        self.assertEqual(fallback[0]['credits'],1400)
        self.assertEqual(fallback[0]['primaryReserve'],800)
        self.assertGreaterEqual(1400-350,fallback[0]['primaryReserve'],"the unit is paid for out of surplus")
        self.assertTrue(any('unitId=9' in c for c in calls),"the affordable surplus unit is ordered")
    def test_secondary_upgrade_is_preferred_when_it_keeps_the_reserve(self):
        # 2000 credits and the frozen scoring gate (army>=6, so the upgrade scores 5): the upgrade is
        # payable and 2000-1200 leaves the 800 reserve intact, so the secondary invests in its own
        # capacity instead of buying yet another combat unit.
        calls,rows=self.run_case([
            dict(id=5,tier=2,queue=0,actions=[('heavy','heavyTank',800,False)]),
            dict(id=9,tier=1,queue=0,actions=[('upgrade','upgrade',1200,True),('tank','c_tank',350,True)])],
            credits=2000,army=6)
        self.assertFalse([r for r in rows if r['event']=='SECONDARY_INVESTMENT_FALLBACK'],
                         "an upgrade that keeps the reserve is not replaced by a cheap unit")
        self.assertTrue(any('unitId=9' in c and 'actionId=upgrade' in c for c in calls),
                        "the secondary buys the upgrade that still keeps the reserve")
        self.assertFalse([c for c in calls if 'unitId=9' in c and 'actionId=tank' in c],
                         "no cheap unit is bought while the upgrade is legal")
    def test_unaffordable_secondary_upgrade_still_leaves_the_cheap_unit(self):
        # Same menu, but the upgrade now costs more than the whole treasury: it is deferred as an
        # investment and the cheap unit is bought out of the surplus above the primary reserve.
        calls,rows=self.run_case([
            dict(id=5,tier=2,queue=0,actions=[('heavy','heavyTank',800,False)]),
            dict(id=9,tier=1,queue=0,actions=[('upgrade','upgrade',2500,False),('tank','c_tank',350,True)])],
            credits=2000,army=6)
        fallback=[r['data'] for r in rows if r['event']=='SECONDARY_INVESTMENT_FALLBACK']
        self.assertTrue(fallback,"the unaffordable upgrade leaves the cheap unit legal")
        self.assertEqual(fallback[0]['deferredUpgradeCost'],2500)
        self.assertEqual(fallback[0]['chosenUnit'],'c_tank')
        self.assertEqual(fallback[0]['primaryReserve'],800)
        self.assertTrue(any('unitId=9' in c and 'actionId=tank' in c for c in calls),
                        "the cheap unit is ordered instead of waiting for the upgrade")
    def test_t2_secondary_with_no_upgrade_menu_never_reports_an_upgrade_deferral(self):
        # A secondary that already reached T2 has no upgrade action left, so there is nothing to defer.
        # It must simply build its combat unit - no SECONDARY_INVESTMENT_DEFERRED and no fallback event
        # claiming an upgrade was held back (found in the 900s Giant Island sample).
        calls,rows=self.run_case([
            dict(id=5,tier=2,queue=1,actions=[('heavy','heavyTank',800,True)]),
            dict(id=9,tier=2,queue=0,actions=[('heavy','heavyTank',800,True),('tank','c_tank',350,True)])],
            credits=2000,army=12)
        self.assertFalse([r for r in rows if r['event']=='SECONDARY_INVESTMENT_FALLBACK'],
                         "no phantom upgrade fallback is recorded")
        self.assertFalse([d for d in (r['data'] for r in rows if r['event']=='production_deferred')
                          if d['reservedFor']=='SECONDARY_INVESTMENT'],
                         "no phantom secondary-investment deferral is recorded")
        self.assertTrue(any('unitId=9' in c and 'actionId=heavy' in c for c in calls),
                        "the T2 secondary still produces its mainline unit")
    def test_one_fallback_decision_writes_exactly_one_event(self):
        # 输出16 §2B / 输出18 §D: the investment block and the older banking block both used to report the
        # same fallback. The fallback is now a behaviour event: exactly one per accepted fallback order.
        calls,rows=self.run_case([
            dict(id=5,tier=2,queue=1,actions=[('heavy','heavyTank',800,True)]),
            dict(id=9,tier=1,queue=0,actions=[('upgrade','upgrade',2000,False),('tank','c_tank',350,True)])],
            credits=500,army=11)
        fallbacks=[r['data'] for r in rows if r['event']=='SECONDARY_INVESTMENT_FALLBACK']
        queue=[c for c in calls if '/command/queue' in c]
        self.assertTrue(fallbacks,"the fallback is still reported")
        self.assertEqual(len(fallbacks),len(queue),
                         "one event per accepted fallback order (no 10s throttle hole, no duplicate)")
        keys={(f['gameTimeMs'],f['chosenUnit'],f['primaryReserve']) for f in fallbacks}
        self.assertEqual(len(keys),len(fallbacks),"no decision is reported twice")
        deferred=[d for d in (r['data'] for r in rows if r['event']=='production_deferred')
                  if d['reservedFor']=='SECONDARY_INVESTMENT']
        self.assertTrue(deferred,"the throttled state event is still written")
        self.assertLessEqual(len(deferred),len(fallbacks),
                             "the deferral is a state event and may be throttled below the order count")
    def test_three_quick_fallback_orders_write_three_events(self):
        # 输出18 §D: three successful fallback orders inside the old 10-second window must produce three
        # events - the throttle must not swallow behaviour.
        calls,rows=self.run_case([
            dict(id=5,tier=2,queue=1,actions=[('heavy','heavyTank',800,True)]),
            dict(id=9,tier=1,queue=0,actions=[('upgrade','upgrade',2000,False),('tank','c_tank',350,True)])],
            credits=500,army=11)
        fallbacks=[r['data'] for r in rows if r['event']=='SECONDARY_INVESTMENT_FALLBACK']
        queue=[c for c in calls if '/command/queue' in c]
        self.assertEqual(len(queue),3,"the fixture places three orders in three ticks")
        self.assertEqual(len(fallbacks),3,"every accepted fallback order gets its own event")
        self.assertEqual([f['gameTimeMs'] for f in fallbacks],[4000,6000,8000])
    def test_rejected_fallback_order_writes_no_event(self):
        # 输出18 §D: a fallback that is decided but rejected by the bridge must not be reported as a
        # behaviour event, because no order was placed.
        calls,rows=self.run_case([
            dict(id=5,tier=2,queue=1,actions=[('heavy','heavyTank',800,True)]),
            dict(id=9,tier=1,queue=0,actions=[('upgrade','upgrade',2000,False),('tank','c_tank',350,True)])],
            credits=500,army=11,reject_queue=True)
        self.assertTrue([c for c in calls if '/command/queue' in c],"the order is attempted")
        self.assertFalse([r for r in rows if r['event']=='SECONDARY_INVESTMENT_FALLBACK'],
                         "a rejected order is not counted as a fallback behaviour")
    def test_primary_tech_banking_still_does_not_fall_back(self):
        # Only a T1 factory exists, so its upgrade unlocks the mainline unit: that is primary tech
        # enablement, the money keeps being saved and no cheap substitute may be bought.
        calls,rows=self.run_case([
            dict(id=5,tier=1,queue=0,actions=[('upgrade','upgrade',900,False),('tank','c_tank',350,True)])],
            credits=1200,army=10)
        self.assertFalse([r for r in rows if r['event']=='SECONDARY_INVESTMENT_FALLBACK'],
                         "technology enablement never degrades into a cheap unit")
        self.assertFalse([c for c in calls if 'command/queue' in c],
                         "the enablement money is protected instead of being spent on a fallback")
        tech=[d for d in (r['data'] for r in rows if r['event']=='production_deferred')
              if d['reservedFor']=='PRIMARY_TECH']
        self.assertTrue(tech,"the enablement saving is recorded as primary tech")
        self.assertEqual(tech[0]['reason'],'PRIMARY_TECH_BANKING')


class ReachabilityReportTests(unittest.TestCase):
    """Astra 对话29 §5 / 输出29: the report contract for reachability sampling was never tested.

    ReachabilityHarness covers the endpoint; nothing covered what BattleClient actually puts in the
    report. These tests drive the real client against a fake bridge and assert on the written JSONL:
      * the guard counters the endpoint publishes survive into report_reachability.raw;
      * the collision radius in the report is a Field.get read, not a method call;
      * the client only ever requests the live-safe default, i.e. it never sends groups=/stages=/incident=;
      * at most reachabilityTypeLimit representatives are sampled per batch.
    """
    def run_case(self):
        calls=[];step=[0];bodies=[]
        def unit(uid,kind):
            building=kind=='commandCenter'
            return dict(id=uid,type=kind,x=1000,y=1000,hp=1000,maxHp=1000,dead=False,buildProgress=1,
                        mobile=not building,canAttack=not building,building=building,techLevel=1,
                        productionQueue=0,orderType=None)
        def enemy():
            return dict(id=7,type='builder',x=1010,y=1010,hp=45,maxHp=40,dead=False,building=False,
                        canAttack=False,lastSeenGameTimeMs=step[0]*2000)
        def reachability(q):
            # The shape the real endpoint returns, including the Astra contract-gap fields.
            return dict(status='raw',sessionId='s',concept='output21-raw-reachability-v0',
                        attackerId=int(q['unitId'][0]),attackerType='c_tank',
                        attackerMovementClass=None,attackerMovementClassSource='GROUP_B_DISABLED',
                        targetId=int(q['targetId'][0]),targetType='builder',targetBuilding=False,
                        collisionRadiusRaw=dict(field='cj',readVia='Field.get',attacker=11.0,target=9.0),
                        reflectionGuards=dict(voidMethodsSkipped=0,factoryAccessorsSkipped=0,
                            notAllowedSkipped=2,counterScope='session_cumulative_process_lifetime',
                            notAllowedSignatures=['com.corrodinggames.rts.gameFramework.k.l#a',
                                                  'com.corrodinggames.rts.gameFramework.k.l#b']),
                        diagnosticStatus='RAW_ONLY_NOT_A_VERDICT',pathMode='CURRENT_SAFE_PATH')
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*a):pass
            def do_GET(self):
                from urllib.parse import urlsplit,parse_qs
                path=urlsplit(self.path).path
                if self.path=='/health':return self.send(dict(status='ok',version='0.07-alpha1'))
                if path=='/combat/reachability':
                    calls.append(self.path);body=reachability(parse_qs(urlsplit(self.path).query));bodies.append(body)
                    return self.send(body)
                if path=='/state':
                    step[0]+=1;end=step[0]>=6
                    return self.send(dict(status='running',sessionId='s',frame=step[0],gameTimeMs=step[0]*2000,
                        networked=False,replay=False,player={'credits':0},
                        map={'width':2200,'height':2200,'tilesWide':110,'tilesHigh':110,'tileWidth':20,'tileHeight':20},
                        match=dict(outcome='DEFEAT' if end else 'ONGOING',nativeDefeat=end,nativeVictory=False,
                                   source='native_result_screen'),
                        ownUnits=[unit(3,'commandCenter'),unit(20,'c_tank'),unit(21,'c_tank'),unit(22,'heavyTank')]))
                if path=='/combat/production':return self.send(dict(status='observed',sessionId='s',factories=[]))
                if path=='/combat/observe':
                    return self.send(dict(status='observed',sessionId='s',gameTimeMs=step[0]*2000,
                                          visibleEnemies=[enemy()],rememberedEnemies=[enemy()]))
                if path=='/scout/observe':
                    return self.send(dict(status='observed',sessionId='s',frame=step[0],gameTimeMs=step[0]*2000,
                                          newlyObservedTiles=0,resources=[],visibleThreats=[],rememberedThreats=[]))
                return self.send(dict(status='no_frontier',sessionId='s'))
            def send(self,d):
                b=json.dumps(d).encode();self.send_response(200);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            with tempfile.TemporaryDirectory(dir=pathlib.Path.cwd()) as cwd:
                p=subprocess.run(['java','-Drwagent.pollMs=60','-Drwagent.reachabilitySampleIntervalMs=1000',
                                  '-Drwagent.port='+str(server.server_port),'-cp',JAR,'io.rwagent.client.BattleClient','120'],
                                 cwd=cwd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=20)
                self.assertEqual(p.returncode,0,p.stdout.decode())
                report=next(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                rows=[json.loads(x) for x in report.read_text().splitlines()]
                return calls,rows
        finally:server.shutdown();server.server_close()
    def test_the_report_carries_the_reflection_guards(self):
        calls,rows=self.run_case()
        sampled=[r for r in rows if r['event']=='report_reachability']
        self.assertTrue(sampled,"a visible enemy and an armed army must produce a sample")
        for row in sampled:
            guards=row['data']['raw']['reflectionGuards']
            self.assertEqual(guards['notAllowedSkipped'],2,
                             "the endpoint's refusal count must reach the report unchanged")
            self.assertEqual(guards['counterScope'],'session_cumulative_process_lifetime')
            self.assertEqual(len(guards['notAllowedSignatures']),2,
                             "the refused signatures must reach the report unchanged")
    def test_the_report_records_the_field_read_route(self):
        calls,rows=self.run_case()
        row=[r for r in rows if r['event']=='report_reachability'][0]
        radius=row['data']['raw']['collisionRadiusRaw']
        self.assertEqual(radius['readVia'],'Field.get',
                         "the collision radius must be reported as a field read, never a method call")
    def test_the_client_never_requests_a_non_passive_group(self):
        calls,rows=self.run_case()
        self.assertTrue(calls,"the endpoint must actually be called")
        for path in calls:
            for forbidden in ('groups=','stages=','incident='):
                self.assertNotIn(forbidden,path,
                                 "the live client must stay on the live-safe default request")
            self.assertIn('unitId=',path);self.assertIn('targetId=',path)
        # dump=full is the one documented opt-in (输出23 §6): it widens the response with field reads only
        # and is taken at most once per combat type, on that type's first sample.
        dumped={}
        for path in calls:
            if 'dump=full' not in path:continue
            unit=path.split('unitId=')[1].split('&')[0]
            dumped[unit]=dumped.get(unit,0)+1
        for unit,count in dumped.items():
            self.assertEqual(count,1,"full float dump is taken once per representative type")
    def test_at_most_the_configured_number_of_representatives_per_batch(self):
        calls,rows=self.run_case()
        batches={}
        for row in rows:
            if row['event']!='report_reachability':continue
            batches.setdefault(row['data']['sampleBatchId'],[]).append(row['data']['representativeIndex'])
        self.assertTrue(batches)
        for batch,indexes in batches.items():
            self.assertLessEqual(len(indexes),3,"reachabilityTypeLimit is 3")
            self.assertEqual(sorted(indexes),list(range(len(indexes))),
                             "representativeIndex is dense and starts at 0 within a batch")


class NoProgressTargetTests(unittest.TestCase):
    """输出30 §4 NO_PROGRESS_TARGET_HANDLING v0.

    A visible target that the main force has closed in on, and whose HP does not fall inside the stated
    window, must be temporarily deprioritised (search or another target) and retried after a cooldown.
    These tests drive the real client against a scripted bridge; the window/cooldown are shortened so a
    deterministic fixture can exercise many windows in a few game seconds.

    Fixture: 8 armed c_tank (the tactical layer only marches with >= 6), a home commandCenter, and two
    visible enemies. Enemy A (74) is closest, so it wins target selection until it is benched; enemy B
    (96) is the alternative. Coordinates are set so the whole force is already inside engage range
    unless the test deliberately moves it away.
    """
    WINDOW=2000;COOLDOWN=6000
    def run_case(self,polls=26,enemy_hp=None,remembered_only=False,force_far=False,omit_hp=False,
                 sparse_selection=False,game_step_ms=2000):
        # game_step_ms is the game time each poll advances. At a fixed 500 ms wall poll this is exactly
        # the knob that emulates a higher game speed, so a coarse decision cadence can be tested without
        # actually playing at 5x.
        calls=[];step=[0];orders=[]
        def unit(uid,kind,x,y):
            building=kind=='commandCenter'
            return dict(id=uid,type=kind,x=x,y=y,hp=1000,maxHp=1000,dead=False,buildProgress=1,
                        mobile=not building,canAttack=not building,building=building,techLevel=1,
                        productionQueue=0,orderType=None)
        def army_xy():
            if sparse_selection:
                # One visit to A every 20 ticks, at A's current spot; the rest of the time the army sits
                # on B. Geometry matters: during the gap A is still within engage range (so a clock that
                # measures elapsed time would keep running) but B is closer, so B is the one selected.
                return enemy_pos(74) if step[0]%20==10 else (1200.0,1000.0)
            if force_far:return 3400,3400          # far outside the 300 engage range
            return 1000.0,1000.0
        def enemy_pos(eid):
            if eid==74:
                # Alternating by 170 units: the >160 move rule guarantees an order is issued on every
                # visit without needing 8 s of silence, while both spots stay within engage range.
                return (1000.0,1000.0) if not sparse_selection or (step[0]//20)%2==0 else (1000.0,1170.0)
            return (1200.0,1000.0) if sparse_selection else (1400.0,1400.0)
        def enemy_hp_for(eid,fixed):
            # In the sparse fixture B is the continuously engaged target, so its HP must keep falling or
            # it would be benched too - and a benched B would promote A to continuous selection, which is
            # exactly the property this test is trying to isolate.
            if sparse_selection and eid==96:return max(1,1000-step[0]*5)
            return fixed
        def enemy(eid,hp,x,y,fresh):
            entry=dict(id=eid,type='c_tank',x=x,y=y,hp=hp,maxHp=1000,dead=False,building=False,
                       canAttack=False,lastSeenGameTimeMs=step[0]*game_step_ms if fresh else 0)
            if omit_hp:del entry['hp']
            return entry
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*a):pass
            def do_POST(self):
                from urllib.parse import urlsplit,parse_qs
                q=parse_qs(urlsplit(self.path).query);calls.append(self.path)
                if 'attack-move' in self.path:
                    ids=[int(v) for v in q['unitIds'][0].split(',')]
                    orders.append(dict(gameTimeMs=step[0]*game_step_ms,unitIds=ids,
                                       x=float(q['x'][0]),y=float(q['y'][0])))
                    return self.send(dict(status='queued',sessionId='s',requestId=q['requestId'][0],unitId=ids[0],
                                          frame=step[0],unitIds=ids,targetX=float(q['x'][0]),targetY=float(q['y'][0]),
                                          orderType='attackMove'))
                return self.send(dict(status='queued',sessionId='s',requestId=q['requestId'][0],unitId=4,frame=step[0]))
            def do_GET(self):
                from urllib.parse import urlsplit,parse_qs
                path=urlsplit(self.path).path;now=step[0]*game_step_ms
                if self.path=='/health':return self.send(dict(status='ok',version='0.07-alpha1'))
                if path=='/state':
                    step[0]+=1;now=step[0]*game_step_ms;end=step[0]>=polls
                    ax,ay=army_xy()
                    return self.send(dict(status='running',sessionId='s',frame=step[0],gameTimeMs=now,networked=False,replay=False,
                        player={'credits':0},map={'width':3600,'height':3600,'tilesWide':180,'tilesHigh':180,'tileWidth':20,'tileHeight':20},
                        match=dict(outcome='DEFEAT' if end else 'ONGOING',nativeDefeat=end,nativeVictory=False,source='native_result_screen'),
                        ownUnits=[unit(3,'commandCenter',1000,1100)]+[unit(20+i,'c_tank',ax,ay) for i in range(8)]))
                if path=='/combat/production':return self.send(dict(status='observed',sessionId='s',factories=[]))
                if path=='/combat/observe':
                    hp_a=1000 if enemy_hp is None else enemy_hp(step[0])
                    ax,ay=enemy_pos(74);bx,by=enemy_pos(96)
                    a=enemy(74,enemy_hp_for(74,hp_a),ax,ay,not remembered_only)
                    b=enemy(96,enemy_hp_for(96,1000),bx,by,not remembered_only)
                    if remembered_only:
                        return self.send(dict(status='observed',sessionId='s',gameTimeMs=now,visibleEnemies=[],rememberedEnemies=[a,b]))
                    return self.send(dict(status='observed',sessionId='s',gameTimeMs=now,visibleEnemies=[a,b],rememberedEnemies=[a,b]))
                if path=='/scout/observe':
                    return self.send(dict(status='observed',sessionId='s',frame=step[0],gameTimeMs=now,
                                          newlyObservedTiles=0,resources=[],visibleThreats=[],rememberedThreats=[]))
                return self.send(dict(status='no_frontier',sessionId='s'))
            def send(self,d):
                b=json.dumps(d).encode();self.send_response(200);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            with tempfile.TemporaryDirectory(dir=pathlib.Path.cwd()) as cwd:
                p=subprocess.run(['java','-Drwagent.pollMs=60','-Drwagent.reachabilitySample=false',
                                  '-Drwagent.noProgressWindowMs=%d'%self.WINDOW,
                                  '-Drwagent.noProgressCooldownMs=%d'%self.COOLDOWN,
                                  '-Drwagent.port='+str(server.server_port),'-cp',JAR,'io.rwagent.client.BattleClient','120'],
                                 cwd=cwd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=25)
                self.assertEqual(p.returncode,0,p.stdout.decode())
                report=next(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                rows=[json.loads(x) for x in report.read_text().splitlines()]
                return calls,rows,orders
        finally:server.shutdown();server.server_close()
    @staticmethod
    def events(rows,kind):return [r['data'] for r in rows if r['event']==kind]
    def test_a_stalled_target_is_deprioritised_and_the_army_switches(self):
        calls,rows,orders=self.run_case()
        stalled=self.events(rows,'target_no_progress')
        self.assertTrue(stalled,"a target whose HP never moves must be reported as stalled")
        self.assertEqual(stalled[0]['targetId'],74)
        self.assertGreaterEqual(stalled[0]['stalledMs'],self.WINDOW)
        self.assertEqual(stalled[0]['ordersIssued']>=1,True)
        self.assertTrue(self.events(rows,'target_deprioritized'),"the stall must bench the target")
        switches=[s for s in self.events(rows,'target_switch') if s['to']==96]
        self.assertTrue(switches,"the army must move on to the other visible target")
        self.assertEqual(switches[0]['from'],74)
    def test_the_benched_target_is_retried_not_abandoned(self):
        calls,rows,orders=self.run_case()
        retries=self.events(rows,'target_retry')
        self.assertTrue(retries,"a benched target must come back after its cooldown, never be dropped")
        self.assertEqual(retries[0]['targetId'],74)
        self.assertEqual(retries[0]['afterCooldownMs'],self.COOLDOWN)
        later=[o for o in orders if o['gameTimeMs']>retries[0].get('gameTimeMs',0)]
        self.assertTrue(later,"the retry must be followed by real orders, not just an event")
    def test_a_target_that_loses_hp_is_never_deprioritised(self):
        # HP must keep falling for the whole fixture: an earlier version floored it at 200, which turned
        # the "control" arm into a genuine stall and the feature correctly benched it.
        calls,rows,orders=self.run_case(polls=34,enemy_hp=lambda step:max(1,1000-step*25))
        self.assertFalse(self.events(rows,'target_no_progress'),
                         "falling HP is progress: this is the control arm for the whole feature")
        self.assertTrue(self.events(rows,'target_progress'),
                        "the first observed damage after engaging is reported")
    def test_remembered_intel_alone_can_never_trigger_the_window(self):
        # Stale intel cannot tell us whether the target is being hurt, so the clock must stay suspended.
        calls,rows,orders=self.run_case(remembered_only=True)
        self.assertFalse(self.events(rows,'target_no_progress'),
                         "only fresh observations may conclude 'no progress'")
    def test_the_army_must_actually_be_close_for_the_window_to_run(self):
        calls,rows,orders=self.run_case(force_far=True)
        self.assertFalse(self.events(rows,'target_no_progress'),
                         "'the main force has closed in' is part of the criterion, not decoration")
    def test_a_missing_observation_field_never_ends_the_match(self):
        # The whole progress layer is optional book-keeping. If the bridge stops reporting a number this
        # code needs, it must suspend the window and keep fighting - not throw and turn the match into FAIL.
        calls,rows,orders=self.run_case(omit_hp=True)
        self.assertEqual(rows[-1]['data']['outcome'],'PASS',
                         "a missing enemy hp field must not fail the battle")
        self.assertFalse(self.events(rows,'target_no_progress'),
                         "without an hp reading there is no evidence, so no window may elapse")
        self.assertTrue(orders,"the army must keep operating normally")
    def test_a_target_we_only_visit_now_and_then_is_not_benched(self):
        # Live 对话31 caught the clock accumulating *elapsed* time instead of *engaged* time: a target
        # selected once every ~50 s reported stalledMs=49643 against a 20 s window. Only continuous
        # engagement may conclude "no progress", so sparse visits must not produce a trigger at all.
        calls,rows,orders=self.run_case(polls=58,sparse_selection=True)
        stalled=[e for e in self.events(rows,'target_no_progress') if e['targetId']==74]
        self.assertFalse(stalled,"a target we do not continuously engage is not stalled")
        for event in self.events(rows,'target_no_progress'):
            self.assertLessEqual(event['stalledMs'],self.WINDOW+3000,
                                 "the reported stall is engaged time, never elapsed time")
    def test_the_reported_stall_never_exceeds_the_window_by_more_than_a_tick(self):
        calls,rows,orders=self.run_case()
        tolerance=rows[-1]['data']['noProgressAttentionGapMs']
        for event in self.events(rows,'target_no_progress'):
            self.assertLessEqual(event['stalledMs'],self.WINDOW+tolerance)
    def test_the_attention_tolerance_scales_with_the_decision_interval(self):
        # Speed readiness (对话35): the tolerance used to be a hard-coded 3000 game ms, which silently
        # assumed the 1x loop rate. At a coarse cadence (4 game seconds per poll - a faster game or a
        # slower loop) every tick exceeded it, so the clock reset on every tick and the detector reported
        # "no stalls" forever without failing. The tolerance must now come from the measured interval.
        calls,rows,orders=self.run_case(polls=24,game_step_ms=4000)
        stalled=self.events(rows,'target_no_progress')
        self.assertTrue(stalled,"a coarse decision cadence must not silently disable the detector")
        summary=rows[-1]['data']
        self.assertGreaterEqual(summary['effectiveDecisionIntervalGameMs'],3000,
                                "the measured decision interval reflects the coarse cadence")
        self.assertGreater(summary['noProgressAttentionGapMs'],3000,
                           "the tolerance grew with the interval instead of staying hard-coded")
        self.assertLessEqual(stalled[0]['stalledMs'],self.WINDOW+summary['noProgressAttentionGapMs'],
                             "the reported stall stays inside window + the derived tolerance")
        self.assertGreaterEqual(summary['maxObservedGameTimeJump'],3000,
                                "the largest observed game-time step is reported for speed accounting")
    def test_a_benched_target_is_not_re_engaged_inside_its_cooldown(self):
        # "防抖动" has to be checkable from the report alone, so every target_* event carries a game time.
        # The closest target is benched first; nothing may be ordered at it again before its retry time.
        calls,rows,orders=self.run_case()
        benched=self.events(rows,'target_deprioritized')
        self.assertTrue(benched)
        retry_at=benched[0]['retryAtGameTimeMs']
        self.assertEqual(benched[0]['gameTimeMs']+self.COOLDOWN,retry_at)
        benched_at=benched[0]['gameTimeMs']
        early=[s for s in self.events(rows,'target_switch')
               if s['to']==74 and benched_at<=s['gameTimeMs']<retry_at]
        self.assertFalse(early,"a benched target must not be re-engaged inside its cooldown")
        retries=self.events(rows,'target_retry')
        self.assertTrue(retries)
        self.assertGreaterEqual(retries[0]['gameTimeMs'],retry_at,
                                "the retry may not happen before the cooldown expires")
    def test_the_knobs_and_the_acceptance_counters_are_reported(self):
        calls,rows,orders=self.run_case()
        config=self.events(rows,'battle_config')[0]
        self.assertEqual(config['noProgressWindowMs'],self.WINDOW)
        self.assertEqual(config['noProgressCooldownMs'],self.COOLDOWN)
        self.assertEqual(config['noProgressEngageRange'],300)
        summary=rows[-1]['data']
        self.assertGreaterEqual(summary['targetNoProgressTriggers'],1)
        self.assertGreaterEqual(summary['targetRetries'],1)
        self.assertGreaterEqual(summary['targetSwitches'],1)

class EconomyAboveFloorTests(unittest.TestCase):
    """Economy v0/v0.1/v1a (对话32-37): mineTarget is a floor, not a destination.

    v0  evidence: in three long matches the mine count froze at 3 by ~240 game seconds while 8-12 resource
        points were already scouted and up to 16k credits piled up.
    v0.1 evidence: /expansion/plan only searches +-600 around the builder, so above the floor the builder
        must prospect remembered resource points or the lane can never see a site at all.
    v1a  evidence (对话37 共识 §7): an above-floor mine is now an INVESTMENT - it needs a chosen intent
        whose cost the production lane may not spend, which is what lets it happen below the army cap.

    Fixture: a finished economy at the floor (commandCenter + builder + 3 completed extractors + a normal
    fighting army), a bridge that offers a legal site, and a controllable balance. Enemies sit FAR from
    home so MILITARY_URGENCY is OPEN_EXPANSION; site danger comes from rememberedThreats, which is a
    different source (scout) than the urgency read (combat observe), so the two can be tested apart.
    """
    def run_case(self,credits=9000,threat_x=None,plan_status='planned',polls=14,ready_mines=3,
                 factories=1,plan_switch_after=None,army=26,enemy_at=(3000.0,3000.0),
                 credits_late=None,credits_switch_at=None,complete_mine_after=None,
                 investment_timeout_ms=None,menu_empty=False):
        calls=[];step=[0];ordered=[None]
        def current_credits():
            if credits_late is None or credits_switch_at is None:return credits
            return credits if step[0]<credits_switch_at else credits_late
        def unit(uid,kind,x=1000,y=1000):
            building=kind in ('commandCenter','landFactory') or kind.startswith('extractor')
            return dict(id=uid,type=kind,x=x,y=y,hp=1000,maxHp=1000,dead=False,buildProgress=1,
                        mobile=not building,canAttack=kind=='c_tank',building=building,techLevel=1,
                        productionQueue=0,orderType=None)
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*a):pass
            def do_POST(self):
                from urllib.parse import urlsplit,parse_qs
                q=parse_qs(urlsplit(self.path).query);calls.append((self.path,current_credits()))
                if 'build-extractor' in self.path:
                    if ordered[0] is None:ordered[0]=step[0]
                    return self.send(dict(status='queued',sessionId='s',requestId=q['requestId'][0],unitId=4,frame=step[0]))
                if 'attack-move' in self.path:
                    ids=[int(v) for v in q['unitIds'][0].split(',')]
                    return self.send(dict(status='queued',sessionId='s',requestId=q['requestId'][0],unitId=ids[0],
                                          frame=step[0],unitIds=ids,targetX=float(q['x'][0]),targetY=float(q['y'][0]),
                                          orderType='attackMove'))
                self.send(dict(status='queued',sessionId='s',requestId=q['requestId'][0],unitId=4,frame=step[0],
                               type='c_tank'))
            def do_GET(self):
                from urllib.parse import urlsplit
                path=urlsplit(self.path).path;now=step[0]*2000
                if self.path=='/health':return self.send(dict(status='ok',version='0.07-alpha1'))
                if path=='/state':
                    step[0]+=1;now=step[0]*2000;end=step[0]>=polls
                    # complete_mine_after emulates the builder: the engine creates the site as an UNFINISHED
                    # building as soon as the order lands, and it finishes N ticks later. Modelling the
                    # unfinished stage matters - without it the client keeps re-issuing the order because no
                    # candidate ever appears.
                    extra=[]
                    if complete_mine_after is not None and ordered[0] is not None:
                        done=unit(90,'extractor',1050,1000)
                        done['buildProgress']=1.0 if step[0]>=ordered[0]+complete_mine_after else 0.1
                        extra=[done]
                    return self.send(dict(status='running',sessionId='s',frame=step[0],gameTimeMs=now,networked=False,replay=False,
                        player={'credits':current_credits()},map={'width':3600,'height':3600,'tilesWide':180,'tilesHigh':180,'tileWidth':20,'tileHeight':20},
                        match=dict(outcome='DEFEAT' if end else 'ONGOING',nativeDefeat=end,nativeVictory=False,source='native_result_screen'),
                        ownUnits=[unit(3,'commandCenter'),unit(4,'builder')]
                                 +[unit(20+i,'landFactory',1000+40*i,1100) for i in range(factories)]
                                 +[unit(30+i,'extractor',900+300*i,900) for i in range(ready_mines)]
                                 +[unit(40+i,'c_tank',1050,1050) for i in range(army)]
                                 +extra))
                if path=='/expansion/plan':
                    # The site is 50 units from the builder, i.e. already inside build range, so the
                    # fixture does not depend on a movement model it cannot simulate faithfully.
                    # plan_switch_after flips the planner to "no legal site" mid-run, which is how the
                    # live matches actually looked once the builder sat next to our own mines.
                    status=plan_status if (plan_switch_after is None or step[0]<plan_switch_after) else 'no_site'
                    return self.send(dict(status=status,sessionId='s',extractorX=1050.0,extractorY=1000.0,
                                          extractorCost=350,factoryCost=700,targetTile=9000,
                                          diagnostics='fixture') if status=='planned'
                                     else dict(status=status,sessionId='s'))
                if path=='/combat/production':
                    acts=[] if menu_empty else [dict(actionId='tank',type='c_tank',cost=350,affordable=True)]
                    return self.send(dict(status='observed',sessionId='s',factories=[dict(id=20,tier=1,queue=0,
                        actions=acts)]))
                if path=='/combat/observe':
                    e=[dict(id=500,type='c_tank',x=enemy_at[0],y=enemy_at[1],hp=1000,maxHp=1000,dead=False,
                            building=False,canAttack=True,lastSeenGameTimeMs=now)]
                    return self.send(dict(status='observed',sessionId='s',gameTimeMs=now,visibleEnemies=[],rememberedEnemies=e))
                if path=='/scout/observe':
                    threats=[] if threat_x is None else [dict(x=threat_x,y=1000.0,type='c_tank',lastSeenGameTimeMs=now)]
                    res=[dict(tile=7001,x=2400.0,y=2400.0),dict(tile=7002,x=2600.0,y=2400.0)]
                    return self.send(dict(status='observed',sessionId='s',frame=step[0],gameTimeMs=now,
                                          newlyObservedTiles=0,resources=res,visibleThreats=[],rememberedThreats=threats))
                if path=='/economy/plan':return self.send(dict(status='unavailable',sessionId='s'))
                return self.send(dict(status='no_frontier',sessionId='s'))
            def send(self,d):
                b=json.dumps(d).encode();self.send_response(200);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            with tempfile.TemporaryDirectory(dir=pathlib.Path.cwd()) as cwd:
                cmd=['java','-Drwagent.pollMs=60','-Drwagent.reachabilitySample=false']
                if investment_timeout_ms is not None:
                    cmd.append('-Drwagent.investmentTimeoutGameMs=%d'%investment_timeout_ms)
                cmd+=['-Drwagent.port='+str(server.server_port),'-cp',JAR,'io.rwagent.client.BattleClient','120']
                p=subprocess.run(cmd,
                                 cwd=cwd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=25)
                self.assertEqual(p.returncode,0,p.stdout.decode())
                report=next(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                rows=[json.loads(x) for x in report.read_text().splitlines()]
                return calls,rows
        finally:server.shutdown();server.server_close()
    @staticmethod
    def events(rows,kind):return [r['data'] for r in rows if r['event']==kind]
    def test_a_fourth_mine_is_taken_when_there_is_real_surplus(self):
        # factories=2 because "factories before extra mines" is now enforced on the build path too, not
        # only on the prospect path.
        calls,rows=self.run_case(credits=9000,factories=2)
        planned=self.events(rows,'economy_expansion_planned')
        self.assertTrue(planned,"above the floor, surplus plus a legal site must produce a mine")
        self.assertEqual(planned[0]['reason'],'ABOVE_MINE_FLOOR')
        self.assertIs(planned[0]['beyondFloor'],True)
        self.assertEqual(planned[0]['mines'],3)
        self.assertEqual(planned[0]['extractorCost'],350)
        self.assertTrue(any('build-extractor' in c for c,_ in calls),"the site is actually built")
        self.assertEqual(rows[-1]['data']['minesBeyondFloor'],1)
    def test_no_surplus_means_no_mine_and_a_stated_reason(self):
        # Builder Utilization v0: with an intent held the bar is the mine's own price (700 by default until
        # a plan reports the real one), not that price plus a preferred unit. 200 is below even the mine,
        # so the refusal is genuine; the old "unit price unknown" refusal is gone because it is no longer
        # needed for this decision.
        calls,rows=self.run_case(credits=200,factories=2,polls=20)
        self.assertFalse([r for r in self.events(rows,'economy_expansion_planned') if r['beyondFloor']],
                         "above the floor a mine may not starve the production line")
        reasons=[r['reason'] for r in self.events(rows,'economy_expansion_blocked') if r['beyondFloor']]
        self.assertIn('NO_SURPLUS',reasons,"a mine that cannot be paid for is refused, with the reason")
        self.assertNotIn('UNKNOWN_UNIT_COST',reasons,
                         "the unit price is no longer part of this decision")
        self.assertFalse(any('build-extractor' in c for c,_ in calls))
        self.assertGreaterEqual(rows[-1]['data']['expansionRefusals'],1)
    def test_a_site_next_to_a_remembered_threat_is_refused(self):
        calls,rows=self.run_case(credits=9000,threat_x=1050.0,polls=20)
        reasons=[r['reason'] for r in self.events(rows,'economy_expansion_blocked') if r['beyondFloor']]
        self.assertIn('SITE_UNSAFE',reasons)
        self.assertFalse([r for r in self.events(rows,'economy_expansion_planned') if r['beyondFloor']])
    def test_a_remembered_resource_is_prospected_above_the_floor(self):
        # /expansion/plan only searches +-600 around the builder, so once the nearby tiles are used up the
        # only way to a 4th mine is to walk to a remembered resource. Live 对话33: 45/49 above-floor probes
        # were refused because the single resource in range was the tile our own mine already occupied.
        calls,rows=self.run_case(credits=9000,factories=2,plan_status='no_site',polls=20)
        prospect=[r for r in self.events(rows,'economy_expansion_prospect') if r['beyondFloor']]
        self.assertTrue(prospect,"above the floor the builder must walk to a remembered resource")
        self.assertEqual(prospect[0]['reason'],'PLANNER_ONLY_SEES_CURRENT_VISIBILITY')
        self.assertTrue(any('command/move' in c and 'x=2400.0' in c for c,_ in calls),
                        "the builder is actually sent there")
        reasons=[r['reason'] for r in self.events(rows,'economy_expansion_blocked') if r['beyondFloor']]
        self.assertIn('NO_VISIBLE_LEGAL_SITE',reasons,"the observed refusal reason is still recorded")
    def test_the_builder_is_not_pulled_away_before_the_second_factory(self):
        # planProductionFacility recalls a builder further than rearFactoryRadiusWorld from home, so an
        # above-floor prospect started before the factory is up would make the lanes trade move orders.
        calls,rows=self.run_case(credits=9000,factories=1,plan_switch_after=4,polls=20)
        reasons=[r['reason'] for r in self.events(rows,'economy_expansion_blocked') if r['beyondFloor']]
        self.assertIn('BUILDER_NEEDED_FOR_FACTORY',reasons)
        self.assertFalse([r for r in self.events(rows,'economy_expansion_prospect') if r['beyondFloor']],
                         "no walk is started while the factory target is unmet")
    def test_no_surplus_above_the_floor_does_not_send_the_builder_walking(self):
        # Builder Utilization v0: with an intent held, the bar is the mine's own price, not that price
        # plus a preferred unit. So the refusal now needs credits BELOW the mine cost (350 in the fixture).
        calls,rows=self.run_case(credits=200,factories=2,plan_switch_after=4,polls=20)
        reasons=[r['reason'] for r in self.events(rows,'economy_expansion_blocked') if r['beyondFloor']]
        self.assertIn('NO_SURPLUS',reasons,"a march we cannot pay for must not start")
        self.assertFalse([r for r in self.events(rows,'economy_expansion_prospect') if r['beyondFloor']])
        self.assertFalse(any('command/move' in c and 'x=2400.0' in c for c,_ in calls))
    def test_the_factory_lane_still_runs_when_the_mine_is_refused(self):
        # A refusal above the floor must not strand the economy lane, and above-floor probing must not
        # consume the rate limit the factory lane is gated on.
        calls,rows=self.run_case(credits=400,polls=20)
        self.assertTrue(self.events(rows,'economy_expansion_blocked'))
        self.assertTrue(self.events(rows,'production_facility_blocked') or
                        self.events(rows,'production_facility_planned'),
                        "the lane falls through to the factory decision instead of stopping")
    def test_the_v1a_chain_runs_end_to_end(self):
        """The acceptance chain from 对话37 共识 §7, in one match.

        intent -> intentional_banking (a normal order deferred for the investment) -> the mine completes
        while the army is still BELOW its hard cap -> reserve released -> production can resume.
        The point of the whole iteration is the "below the cap" part: v0.1 could only ever mine after the
        army had saturated and cash piled up, which is exactly when a mine cannot pay itself back.
        """
        calls,rows=self.run_case(credits=400,credits_late=1200,credits_switch_at=4,
                                 factories=2,plan_switch_after=None,polls=24,complete_mine_after=2)
        intent=self.events(rows,'investment_intent')
        self.assertTrue(intent,"an investment intent is opened")
        self.assertEqual(intent[0]['target'],'NEW_MINE')
        self.assertEqual(intent[0]['urgency'],'OPEN_EXPANSION')
        self.assertLess(intent[0]['army'],intent[0]['hardCap'],
                        "the intent is opened while the army is still below its cap")
        deferred=[r for r in self.events(rows,'production_deferred') if r['reservedFor']=='INVESTMENT']
        self.assertTrue(deferred,"a normal production order is held back for the investment")
        self.assertEqual(deferred[0]['reason'],'RESERVED_FOR_INVESTMENT')
        # The reserve is self-consistent with the intent, and the engine's real price corrects it when the
        # first plan reports one (700 is only the configured default until then).
        corrected=self.events(rows,'investment_reserve_corrected')
        self.assertTrue(corrected,"the reserve is corrected once the engine reports the real mine price")
        self.assertEqual(corrected[0]['reserve'],350)
        self.assertEqual(deferred[-1]['reservedCost'],350)
        done=self.events(rows,'extractor_completed')
        self.assertTrue(done,"the mine is actually completed")
        self.assertIs(done[0]['belowHardCap'],True,
                      "the acceptance moment: the mine finishes while army < mobileUnitHardCap")
        self.assertLess(done[0]['armyAtCompletion'],done[0]['hardCap'])
        released=[r for r in self.events(rows,'investment_released') if r['reason']=='COMPLETED']
        self.assertTrue(released,"the reserve is released when the mine completes")
        summary=rows[-1]['data']
        self.assertEqual(summary['investmentCompletions'],1)
        self.assertEqual(summary['investmentReserveAtEnd'],0,
                         "no money may be left frozen at the end of the match")
        self.assertFalse(self.events(rows,'investment_reserve_stuck'))
    def test_a_stuck_investment_is_released_and_never_left_frozen(self):
        # The failure mode I most wanted to guard against: an intent whose mine never happens would hold
        # the money and throttle production for the rest of the match. The game-time timeout must end it.
        calls,rows=self.run_case(credits=400,factories=2,polls=22,plan_status='no_site',
                                 investment_timeout_ms=30000)
        self.assertTrue(self.events(rows,'investment_intent'))
        released=[r['reason'] for r in self.events(rows,'investment_released')]
        self.assertIn('TIMEOUT',released,"an unexecutable intent times out instead of freezing the money")
        self.assertEqual(rows[-1]['data']['investmentReserveAtEnd'],0)
        self.assertEqual(rows[-1]['data']['investmentCancellations'],1)
        self.assertFalse(self.events(rows,'investment_reserve_stuck'))
    def test_military_pressure_cancels_the_investment(self):
        # Money committed to an economy investment must come back the moment the base is threatened.
        calls,rows=self.run_case(credits=400,factories=2,plan_status='no_site',polls=22,
                                 enemy_at=(1010.0,1000.0))
        intent=self.events(rows,'investment_intent')
        if intent:
            released=[r['reason'] for r in self.events(rows,'investment_released')]
            self.assertIn('MILITARY_PRESSURE',released,
                          "an intent is dropped when an enemy is at the base")
        else:
            self.assertTrue([r for r in self.events(rows,'economy_expansion_blocked')
                             if r['reason']=='NO_INVESTMENT_INTENT'],
                            "under pressure no intent is opened at all")
    def test_the_spend_ledger_and_measured_income_are_reported(self):
        # 对话37 共识 §8: income = delta credits + known spend over game time, which is what calibrates
        # the mechanics-library prior (T1 mine ~= 12.1 credits per normal game second).
        calls,rows=self.run_case(credits=9000,factories=2)
        summary=rows[-1]['data']
        self.assertGreaterEqual(summary['spendTotal'],0)
        self.assertIn('spendByCategory',summary)
        self.assertGreater(summary['measuredIncomePerGameSecond'],0,
                           "a match that spends money has a positive measured income")
        for r in self.events(rows,'spend'):
            self.assertIn('category',r);self.assertIn('cost',r);self.assertIn('gameTimeMs',r)
            self.assertIn(r['category'],('UNIT_PRODUCTION','BUILDER_RECOVERY','NEW_MINE','NEW_FACTORY',
                                         'FACTORY_UPGRADE'))
    def test_a_completed_investment_does_not_inherit_the_retry_cooldown(self):
        # Builder Utilization v0 (对话41): measured 136-171 builder-idle game seconds per 900 s match
        # because a SUCCESSFUL mine still triggered the 60 s backoff meant for timeouts and pressure.
        # Success must release immediately, so the next intent may be opened on the very next decision.
        calls,rows=self.run_case(credits=1200,factories=2,polls=24,complete_mine_after=2)
        done=[r for r in self.events(rows,'investment_released') if r['reason']=='COMPLETED']
        self.assertTrue(done,"the first mine completes")
        intents=self.events(rows,'investment_intent')
        self.assertGreaterEqual(len(intents),2,
                                "a successful investment must not pause the lane for a minute")
        after=[i for i in intents if i['gameTimeMs']>done[0]['gameTimeMs']]
        self.assertTrue(after,"a new intent is opened after the completion")
        self.assertLess(after[0]['gameTimeMs']-done[0]['gameTimeMs'],60000,
                        "and well inside the old 60 s backoff")
    def test_a_reserve_held_against_an_empty_menu_never_crashes(self):
        # 对话39: three live matches died with a NullPointerException on exactly this shape - an investment
        # reserve held while the factory menu offered no affordable combat action, so the "candidate" naming
        # the deferred order was an empty map with a null cost. Diagnostic book-keeping must never end a match.
        calls,rows=self.run_case(credits=9000,factories=2,menu_empty=True,polls=20,
                                 investment_timeout_ms=30000)
        summary=rows[-1]['data']
        self.assertEqual(summary['outcome'],'PASS',
                         "an empty production menu must not fail the battle")
        self.assertNotEqual(summary['reason'],'java.lang.NullPointerException')
        self.assertTrue(self.events(rows,'investment_intent'),"the intent is still opened")
        self.assertEqual(summary['investmentReserveAtEnd'],0,
                         "and the reserve is still released, never left frozen")
        self.assertFalse(self.events(rows,'investment_reserve_stuck'))
    def test_an_intent_pending_at_match_end_is_not_a_defect(self):
        # Live 对话40: the last match ended ~44 game seconds after an intent was opened, well inside its
        # 180 s timeout, and the guardrail called that "stuck". A pending intent at the end is normal; only
        # a reserve that OUTLIVED its deadline is a defect. It is released as MATCH_ENDED and flagged.
        calls,rows=self.run_case(credits=900,factories=2,plan_status='no_site',polls=12)
        summary=rows[-1]['data']
        self.assertEqual(summary['outcome'],'PASS')
        self.assertIs(summary['investmentPendingAtEnd'],True,
                      "the report says an investment was still pending, rather than hiding it")
        self.assertEqual(summary['investmentReserveAtEnd'],0,
                         "and it is released at the end so no money is left notionally frozen")
        released=[r['reason'] for r in self.events(rows,'investment_released')]
        self.assertIn('MATCH_ENDED',released)
        self.assertFalse(self.events(rows,'investment_reserve_stuck'),
                         "an intent inside its deadline is not a stall")
    def test_the_floor_behaviour_is_unchanged(self):
        # Below the floor the surplus gate must NOT apply: the baseline economy is not an investment.
        # Two finished mines and only 400 credits still buys a third mine, exactly as before Economy v0.
        calls,rows=self.run_case(credits=400,ready_mines=2)
        planned=self.events(rows,'economy_expansion_planned')
        self.assertTrue(planned,"below the floor the lane still expands regardless of surplus")
        self.assertIs(planned[0]['beyondFloor'],False)
        self.assertEqual(planned[0]['reason'],'BELOW_MINE_TARGET')
        self.assertFalse([r for r in self.events(rows,'economy_expansion_blocked')
                          if r['beyondFloor']],"no above-floor gate may run below the floor")


class FactoryTargetIncreaseTests(unittest.TestCase):
    """Economy v1b (对话38 裁决 §2): one more land factory only under a long-term throughput bottleneck.

    The signal that must NOT work is "the army is at its cap and the money has nowhere to go": at the cap
    every queue empties by policy, so a single frame of that state is the opposite of a demand for capacity.
    Hence every reading here is time-weighted over a window, and the gate is only allowed to fire when all
    four conditions hold at once.
    """
    def run_case(self,factory_count=2,extractors=8,credits=5000,army_size=2,ticks=40,step_ms=2000,
                 window_ms=60000,idle_ticks=(),menu_affordable=False,idle_from_tick=None,
                 credits_after=None,plan_after_tick=None,extra_args=()):
        calls=[];step=[0];builder=[150,100];purse=[credits];idle=set(idle_ticks)
        def unit(uid,kind,x=100,y=100,bp=1.0,queue=0):
            building=kind in ('commandCenter','landFactory')
            return dict(id=uid,type=kind,x=x,y=y,hp=1000,maxHp=1000,dead=False,buildProgress=bp,
                        mobile=not building,canAttack=kind=='c_tank',building=building,techLevel=1,
                        productionQueue=queue,orderType=None)
        def busy(q):
            if idle_from_tick is not None and q>=idle_from_tick:
                return 0
            return 0 if q in idle else 1
        def purse_at(q):
            if credits_after is not None and q>=credits_after[0]:
                return credits_after[1]
            return credits
        def own():
            st=step[0]
            units=[unit(3,'commandCenter'),unit(4,'builder',builder[0],builder[1])]
            units+=[unit(11+i,'extractorT1',200+30*i,400) for i in range(extractors)]
            units+=[unit(20+i,'c_tank') for i in range(army_size)]
            units+=[unit(5+k,'landFactory',150+80*k,100,1.0,busy(st)) for k in range(factory_count)]
            return units
        def menu():
            return dict(status='observed',sessionId='s',factories=[
                dict(id=5+k,tier=1,queue=busy(step[0]),
                     actions=[dict(actionId='tank',type='c_tank',cost=350,affordable=menu_affordable),
                              dict(actionId='heavy',type='heavyTank',cost=900,affordable=menu_affordable)])
                for k in range(factory_count)])
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*a):pass
            def do_POST(self):
                from urllib.parse import urlsplit,parse_qs
                q=parse_qs(urlsplit(self.path).query);calls.append((self.path,purse_at(step[0])))
                if 'command/move' in self.path:builder[0],builder[1]=100,100
                self.send(dict(status='queued',sessionId='s',requestId=q['requestId'][0],unitId=4,frame=step[0],type='c_tank'))
            def do_GET(self):
                if self.path=='/health':return self.send(dict(status='ok',version='0.07-alpha1'))
                if self.path=='/state':
                    step[0]+=1;end=step[0]>ticks;now=step[0]*step_ms
                    return self.send(dict(status='running',sessionId='s',frame=step[0],gameTimeMs=now,networked=False,replay=False,
                        player={'credits':purse_at(step[0])},
                        map={'width':2200,'height':2200,'tilesWide':110,'tilesHigh':110,'tileWidth':20,'tileHeight':20},
                        match=dict(outcome='DEFEAT' if end else 'ONGOING',nativeDefeat=end,nativeVictory=False,source='native_result_screen'),
                        ownUnits=own()))
                if self.path.startswith('/economy/plan'):
                    if plan_after_tick is not None and step[0]<plan_after_tick:
                        return self.send(dict(status='no_frontier',sessionId='s'))
                    return self.send(dict(status='planned',sessionId='s',builderId=4,factoryType='landFactory',productType='c_tank',
                        targetX=200,targetY=100,factoryCost=700,tankCost=350,credits=purse_at(step[0])))
                if self.path=='/scout/observe':
                    return self.send(dict(status='observed',sessionId='s',frame=step[0],gameTimeMs=step[0]*step_ms,
                        fogEnabled=True,lineOfSightFog=True,visibleTiles=100,exploredTiles=5000,initialVisibleTiles=100,
                        newlyObservedTiles=0,resources=[],visibleThreats=[],rememberedThreats=[]))
                if self.path=='/combat/production':return self.send(menu())
                if self.path=='/combat/observe':
                    return self.send(dict(status='observed',sessionId='s',gameTimeMs=step[0]*step_ms,visibleEnemies=[],rememberedEnemies=[]))
                return self.send(dict(status='no_frontier',sessionId='s'))
            def send(self,d,status=200):
                b=json.dumps(d).encode();self.send_response(status);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            with tempfile.TemporaryDirectory(dir=pathlib.Path.cwd()) as cwd:
                args=['java','-Drwagent.pollMs=40','-Drwagent.expansionIntervalGameMs=2000',
                      '-Drwagent.economyWindowMs='+str(window_ms),'-Drwagent.port='+str(server.server_port)]
                args+=list(extra_args)
                args+=['-cp',JAR,'io.rwagent.client.BattleClient',str(max(120,int(ticks*step_ms/1000)+4))]
                p=subprocess.run(args,cwd=cwd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=90)
                self.assertEqual(p.returncode,0,p.stdout.decode())
                report=next(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                return calls,[json.loads(x) for x in report.read_text().splitlines()]
        finally:server.shutdown();server.server_close()
    def events(self,rows,name):
        return [r['data'] for r in rows if r['event']==name]

    def test_a_sustained_bottleneck_raises_the_target_and_builds_the_factory(self):
        # Two factories queue-busy for the whole window, income 26.9 + 12.07*8 = 123.5/s, no accepted
        # production order at all, price covered and both hard reserves empty.
        calls,rows=self.run_case()
        raised=self.events(rows,'factory_target_increased')
        self.assertTrue(raised,"a long-term throughput bottleneck must add capacity")
        d=raised[0]
        self.assertEqual(d['from'],2)
        self.assertEqual(d['to'],3)
        self.assertEqual(d['readyFactories'],2)
        self.assertGreaterEqual(d['saturationPct'],80)
        self.assertEqual(d['modelledIncomePerGameSecond'],123.5)
        self.assertEqual(d['productionConsumptionPerGameSecond'],0.0)
        self.assertEqual(d['sustainableSurplusPerGameSecond'],123.5)
        self.assertEqual(d['builderReserve'],0)
        self.assertEqual(d['investmentReserve'],0)
        self.assertTrue(any('build-factory' in c for c,_ in calls),
                        "raising the target must lead to a real build order, not just a number")
        self.assertEqual(rows[-1]['data']['landFactoryTargetAtEnd'],3)
        self.assertEqual(rows[-1]['data']['factoryTargetIncreases'],1)
    def test_a_single_busy_frame_is_not_demand(self):
        # Busy only in the last two ticks: the time-weighted share stays far below the threshold.
        calls,rows=self.run_case(idle_ticks=range(0,38))
        self.assertFalse(self.events(rows,'factory_target_increased'),
                         "one busy frame at the end of the match is not a saturated factory")
        blocked=self.events(rows,'factory_target_increase_blocked')
        self.assertTrue(blocked,"the refusal is recorded, so a quiet match is still explainable")
        reasons=[r['reason'] for r in blocked]
        self.assertIn('FACTORY_NOT_SATURATED',reasons)
        self.assertEqual(reasons[-1],'FACTORY_NOT_SATURATED',"once the window is warm the reason is saturation")
        self.assertFalse([c for c,_ in calls if 'build-factory' in c])
    def test_an_army_at_its_cap_with_idle_factories_is_not_demand(self):
        # The exact signal 对话38 §2 forbids: queue empty because the army is at the cap, money piling up.
        calls,rows=self.run_case(army_size=40,idle_ticks=range(0,60),menu_affordable=True)
        self.assertFalse(self.events(rows,'factory_target_increased'),
                         "an army at its hard cap empties the queues by policy; that is not a bottleneck")
        reasons=[r['reason'] for r in self.events(rows,'factory_target_increase_blocked')]
        self.assertIn('FACTORY_NOT_SATURATED',reasons)
        self.assertEqual(reasons[-1],'FACTORY_NOT_SATURATED')
    def test_capacity_is_not_added_when_income_does_not_cover_consumption(self):
        # Income forced to ~0 while accepted production orders keep entering the ledger.
        calls,rows=self.run_case(extractors=3,idle_ticks={32},menu_affordable=True,
                                 extra_args=['-Drwagent.baseIncome=0','-Drwagent.incomePerMine=0'])
        self.assertFalse(self.events(rows,'factory_target_increased'),
                         "no surplus means no capacity: the factories are not the bottleneck")
        reasons=[r['reason'] for r in self.events(rows,'factory_target_increase_blocked')]
        self.assertIn('NO_SUSTAINABLE_SURPLUS',reasons)
        self.assertEqual(reasons[-1],'NO_SUSTAINABLE_SURPLUS')
    def test_the_investment_reserve_is_never_spent_on_the_new_factory(self):
        # A mine intent holds 700; the factory target may only rise once the price is covered ON TOP of it.
        calls,rows=self.run_case(extractors=3,army_size=24,credits=1399,
                                 extra_args=['-Drwagent.baseIncome=5000'])
        self.assertFalse(self.events(rows,'factory_target_increased'),
                         "700 factory + 700 reserved = 1400 is not covered by 1399")
        reasons=[r['reason'] for r in self.events(rows,'factory_target_increase_blocked')]
        self.assertIn('INSUFFICIENT_CREDITS_FOR_FACTORY',reasons)
        self.assertEqual(reasons[-1],'INSUFFICIENT_CREDITS_FOR_FACTORY')
        calls,rows=self.run_case(extractors=3,army_size=24,credits=1400,
                                 extra_args=['-Drwagent.baseIncome=5000'])
        raised=self.events(rows,'factory_target_increased')
        self.assertTrue(raised,"one credit more and the reserve is still intact")
        self.assertEqual(raised[0]['investmentReserve'],700)
        self.assertGreaterEqual(raised[0]['credits'],raised[0]['landFactoryCost']+raised[0]['investmentReserve'])
    def test_a_committed_target_is_not_re_vetoed_by_one_empty_queue_frame(self):
        # 对话39 裁决 §2: the long window already proved the bottleneck, so this increase is a capacity
        # commitment. A single frame of "the queue happens to be empty right now" must not veto it
        # (measured live as a 47.3 second stall between the increase and the build order).
        calls,rows=self.run_case(idle_from_tick=36)
        self.assertTrue(self.events(rows,'factory_target_increased'),
                        "the long window fires while the factories were still busy")
        self.assertTrue(any('build-factory' in c for c,_ in calls),
                        "a committed target must still be built even when the queue reads empty right now")
        blocked=[r for r in self.events(rows,'production_facility_blocked')
                 if r.get('landFactoryTarget',0)>=3 and r['reason']=='FACTORY_QUEUE_EMPTY']
        self.assertFalse(blocked,"the committed path must not fall back to the single-frame gate")
    def test_the_committed_target_still_waits_for_credits_and_reserves(self):
        # Commitment is not a licence to overspend: price plus both hard reserves must still be covered.
        calls,rows=self.run_case(idle_from_tick=36,credits_after=(36,100),plan_after_tick=38)
        self.assertTrue(self.events(rows,'factory_target_increased'),"the increase itself was affordable")
        self.assertFalse([c for c,_ in calls if 'build-factory' in c],
                         "100 credits cannot buy a 700 factory")
        reasons=[r['reason'] for r in self.events(rows,'production_facility_blocked')
                 if r.get('landFactoryTarget',0)>=3]
        self.assertIn('INSUFFICIENT_CREDITS_FOR_FACTORY',reasons)
    def test_the_original_second_factory_still_needs_a_busy_queue(self):
        # The frozen P2-B path is deliberately untouched: with the target still at its configured 2, an idle
        # factory is not a demand signal, and no commitment exists that could override that.
        # army at the cap keeps the client from ordering anything, so no pending entry can make the
        # idle factory look busy (the fixture's own order would otherwise set the busy flag).
        calls,rows=self.run_case(factory_count=1,army_size=40,idle_ticks=range(0,60),menu_affordable=True)
        self.assertFalse(self.events(rows,'factory_target_increased'))
        self.assertIn('FACTORY_QUEUE_EMPTY',
                      [r['reason'] for r in self.events(rows,'production_facility_blocked')])
        self.assertFalse([c for c,_ in calls if 'build-factory' in c])
    def test_the_report_states_where_the_economy_model_came_from(self):
        calls,rows=self.run_case()
        model=self.events(rows,'report_provenance')[0]
        self.assertEqual(model['economyBaseIncome'],26.9)
        self.assertEqual(model['economyIncomePerMine'],12.07)
        self.assertEqual(model['economyWindowGameMs'],60000)
        self.assertEqual(model['factorySaturationMinPct'],80)
        self.assertTrue(model['economyModelSource'].startswith('MEASURED_4_MATCHES'),
                        "a measured constant must travel with its measurement")
        config=self.events(rows,'battle_config')[0]
        self.assertEqual(config['landFactoryTargetMax'],5)
    def test_the_target_stops_at_the_configured_maximum(self):
        calls,rows=self.run_case(extra_args=['-Drwagent.landFactoryTargetMax=2'])
        self.assertFalse(self.events(rows,'factory_target_increased'))
        self.assertEqual(self.events(rows,'factory_target_increase_blocked')[-1]['reason'],'TARGET_AT_MAX')


if __name__=='__main__':unittest.main()
