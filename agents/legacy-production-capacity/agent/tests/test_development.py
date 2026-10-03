"""Independent HTTP timelines: overlapping work, actual types, budget reservation and stop gates."""
import http.server,json,pathlib,subprocess,sys,tempfile,threading,unittest
from urllib.parse import urlsplit,parse_qs
JAR=pathlib.Path(sys.argv.pop(1)).resolve()

class DevelopmentTests(unittest.TestCase):
    def case(self,scenario,target=3,limit=2):
        f=dict(tick=0,credits=1050 if scenario=='reserve' else 5000,orders=[],errors=[],mine=None,tank=None,mines=[],tanks=[],overlap=False,mine_calls=0)
        def unit(i,t,x=350,y=250,progress=1,q=-1):return dict(id=i,type=t,x=x,y=y,buildProgress=progress,productionQueue=q,dead=False)
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*a):pass
            def reply(self,data,status=200):
                raw=json.dumps(data).encode();self.send_response(status);self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
            def do_GET(self):
                path=urlsplit(self.path).path
                if path=='/health':return self.reply(dict(version='0.07-alpha1'))
                if path=='/economy/production-plan':
                    if scenario=='no_factory':return self.reply(dict(message='no completed own idle landFactory'),409)
                    return self.reply(dict(sessionId='session',factoryId=50,factoryType='landFactory',productType='c_tank',tankCost=350))
                if path=='/opening/plan':
                    f['mine_calls']+=1
                    if scenario=='plan_error':return self.reply(dict(message='result unknown'),503)
                    if scenario=='no_mine' or (scenario=='exhausted' and len(f['mines'])==1):return self.reply(dict(message='no legal opening site; extractorType=extractorT1; eligibleBuilders=1; visibleResourceCandidates=0; searchRange=600'),409)
                    if f['mine']:f['errors'].append('mine replanned before completion')
                    return self.reply(dict(sessionId='session',builderId=4,extractorType='extractorT1',extractorX=250+len(f['mines'])*40,extractorY=250,extractorCost=700))
                f['tick']+=1
                if scenario=='reserve' and f['tick']==15:f['credits']+=5000
                if scenario=='old_mine' and f['tick']>=9:return self.reply(dict(message='fixture ends without new extractor'),409)
                units=[unit(4,'builder',150),unit(8,'extractorT1',250),unit(9,'c_tank')];queue=0
                if f['mine']:
                    m=f['mine'];m['polls']+=1;delay=12 if scenario=='reserve' else 1
                    if m['polls']>=delay and scenario!='old_mine':
                        if not m['charged']:f['credits']-=700;m['charged']=True
                        progress=.99999 if m['polls']==delay+2 else (1 if m['polls']>=delay+3 else .2)
                        u=unit(m['id'],'extractorT1',m['x'],progress=progress);units.append(u)
                        if progress==1:f['mines'].append(u);f['mine']=None
                if f['tank']:
                    t=f['tank'];t['polls']+=1
                    if t['polls']<=2:queue=2 if scenario=='interference' else 1
                    else:
                        f['tanks'].append(unit(t['id'],'c_tank'));f['tank']=None
                # Avoid duplicate entries when a mine completes during this observation.
                existing={u['id'] for u in units}
                units += [u for u in f['mines'] if u['id'] not in existing]+f['tanks']
                if scenario=='mine_dies' and f['mines'] and f['mine_calls']>=2:units=[u for u in units if u['id']!=40]
                units.append(unit(50,'landFactory',q=queue))
                self.reply(dict(status='running',sessionId='changed' if scenario=='session' and f['orders'] else 'session',frame=f['tick']*30,player=dict(credits=f['credits']),ownUnits=units,networked=False,replay=False))
            def do_POST(self):
                path=urlsplit(self.path).path;q=parse_qs(urlsplit(self.path).query);f['orders'].append((path,q))
                if path.endswith('build-extractor'):
                    if f['mine']:f['errors'].append('overlapping mine orders')
                    if f['credits']<700:f['errors'].append('mine overspend')
                    f['mine']=dict(id=40+len(f['mines']),x=float(q['x'][0]),polls=0,charged=False);kind='extractorT1';x=float(q['x'][0])
                elif path.endswith('produce-tank'):
                    if scenario=='unknown':return self.reply(dict(message='result unknown'),503)
                    if f['tank']:f['errors'].append('overlapping production orders')
                    reserve=700 if f['mine'] and not f['mine']['charged'] else 0
                    if f['credits']-reserve<350:f['errors'].append('spent pending mine reservation')
                    f['credits']-=350;f['tank']=dict(id=70+len(f['tanks']),polls=0);kind='c_tank';x=350
                else:f['errors'].append('unexpected order '+path);return self.reply({},404)
                if f['mine'] and f['tank']:f['overlap']=True
                self.reply(dict(status='queued',type='wrong_type' if scenario=='wrong_type' else kind,targetX=x,targetY=250))
        server=http.server.HTTPServer(('127.0.0.1',0),Handler);worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
        try:
            with tempfile.TemporaryDirectory() as d:
                args=[] if scenario=='default' else [str(target),str(limit)]
                p=subprocess.run(['java','-Drwagent.port='+str(server.server_port),'-cp',str(JAR),'io.rwagent.client.DevelopmentClient']+args,cwd=d,capture_output=True,text=True,timeout=30)
                events=[json.loads(x) for x in next(pathlib.Path(d).glob('rw-agent-reports/development-*.jsonl')).read_text().splitlines()]
                summary=events[-1]['data'];passed=scenario in ('default','reserve','exhausted','no_mine','production_only')
                self.assertEqual(summary['outcome'],'PASS' if passed else 'FAIL',p.stdout+p.stderr)
                self.assertEqual(p.returncode,0 if passed else 1,p.stdout+p.stderr);self.assertFalse(f['errors'],f['errors'])
                self.assertEqual(len({q['requestId'][0] for _,q in f['orders']}),len(f['orders']))
                if passed:
                    n=8 if scenario=='default' else target;m=3 if scenario=='default' else (0 if scenario in ('no_mine','production_only') else (1 if scenario=='exhausted' else limit))
                    self.assertEqual(summary['completedTanks'],n);self.assertEqual(summary['completedMines'],m)
                    self.assertEqual(summary['commands'],n+m);self.assertEqual(len(f['orders']),n+m)
                    self.assertEqual(summary['expansionStatus'],'NO_VISIBLE_LEGAL_SITE' if scenario in ('no_mine','exhausted') else 'LIMIT_REACHED')
                    if m:self.assertTrue(f['overlap'],'controller serialized both lanes')
                    if scenario=='production_only':self.assertEqual(f['mine_calls'],0)
                    if scenario=='reserve':self.assertTrue(any(e['event']=='budget_wait' for e in events))
                elif scenario in ('no_factory','plan_error'):self.assertEqual(len(f['orders']),0)
                elif scenario in ('unknown','session','interference'):self.assertEqual(len(f['orders']),2)
                elif scenario=='wrong_type':self.assertEqual(len(f['orders']),1)
                elif scenario=='old_mine':self.assertEqual(summary['completedMines'],0)
                if scenario=='mine_dies':self.assertIn('extractor 1 died',summary['reason'])
        finally:server.shutdown();server.server_close();worker.join()

    def test_default_eight_tanks_three_mines_overlap(self):self.case('default')
    def test_pending_mine_budget_reserved_until_native_charge(self):self.case('reserve',2,1)
    def test_exhausted_visible_mines_preserves_production(self):self.case('exhausted')
    def test_no_visible_mine_reports_zero(self):self.case('no_mine',2)
    def test_production_only_option(self):self.case('production_only',2,0)
    def test_missing_factory_zero_orders(self):self.case('no_factory')
    def test_plan_503_stops_zero_orders(self):self.case('plan_error')
    def test_unknown_production_never_retried(self):self.case('unknown')
    def test_session_change_stops_both_lanes(self):self.case('session')
    def test_queue_interference_stops_both_lanes(self):self.case('interference')
    def test_type_mismatch_stops_before_other_lane_order(self):self.case('wrong_type')
    def test_preexisting_mine_not_accepted(self):self.case('old_mine')
    def test_completed_mine_death_stops(self):self.case('mine_dies')

if __name__=='__main__':unittest.main(verbosity=2)
