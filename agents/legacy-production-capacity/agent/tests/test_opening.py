"""Independent HTTP scenarios for a five-order opening; excludes full game simulation."""
import http.server,json,pathlib,subprocess,sys,tempfile,threading,unittest
from urllib.parse import urlsplit,parse_qs
JAR=pathlib.Path(sys.argv.pop(1)).resolve()
class OpeningTests(unittest.TestCase):
    def case(self,scenario,orders,reason=None):
        extractor='extractor' if scenario=='legacy' else 'extractorT1'
        f=dict(stage='initial',polls=0,frame=0,orders=[],errors=[],tanks={},cycle=0,credits=4000,initial_polls=0,low_polls=0,mine_done=False,factory_done=False)
        def u(id,type,x=350,y=250,progress=1,queue=-1):return dict(id=id,type=type,x=x,y=y,buildProgress=progress,productionQueue=queue,dead=False)
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*a):pass
            def reply(self,data,status=200):
                raw=json.dumps(data).encode();self.send_response(status);self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
            def do_GET(self):
                path=urlsplit(self.path).path
                if path=='/health':return self.reply(dict(version='0.07-alpha1'))
                if path=='/opening/plan':
                    if scenario=='no_mine':return self.reply(dict(message='no visible free resource site'),409)
                    return self.reply(dict(sessionId='session',builderId=4,extractorType=extractor,extractorX=250,extractorY=250,extractorCost=700,factoryCost=700,tankCost=350,productType='c_tank',targetTanks=3,totalCost=2450))
                if path=='/economy/plan':
                    if not f['mine_done']:f['errors'].append('factory planning before mine finished')
                    return self.reply(dict(sessionId='session',builderId=4,factoryType='landFactory',targetX=350,targetY=250,factoryCost=700,tankCost=350,productType='c_tank'))
                f['frame']+=30;f['polls']+=1
                units=[u(4,'builder',150),u(8,extractor,250),u(9,'c_tank')]
                if scenario=='income' and f['stage']=='initial':
                    f['initial_polls']+=1;f['credits']=100 if f['initial_polls']<4 else 4000
                if f['stage']=='extractor':
                    if scenario=='old_mine':
                        if f['polls']>=3:return self.reply(dict(message='fixture ends; no new extractor'),409)
                    else:
                        progress=.99999 if f['polls']==1 else 1;f['mine_done']=progress==1
                        units.append(u(40,extractor,250,progress=progress,queue=0))
                elif f['mine_done']:units.append(u(40,extractor,250,queue=0))
                if f['stage']=='factory':
                    progress=.99999 if f['polls']==1 else 1;f['factory_done']=progress==1
                    units.append(u(50,'landFactory',progress=progress,queue=0))
                elif f['factory_done']:
                    q=1 if f['stage']=='produce' and f['polls']==1 else 0
                    units.append(u(50,'landFactory',queue=q))
                if f['stage']=='produce' and f['polls']>=2:
                    f['tanks'][70+f['cycle']]=u(70+f['cycle'],'c_tank')
                    if scenario=='income' and f['cycle']==1:
                        f['low_polls']+=1;f['credits']=50 if f['low_polls']<4 else 4000
                    if scenario=='mine_dies' and f['cycle']==2:units=[item for item in units if item['id']!=40]
                units+=list(f['tanks'].values())
                self.reply(dict(status='running',sessionId='changed' if scenario=='session' and f['stage']=='extractor' else 'session',frame=f['frame'],player=dict(teamId=0,credits=f['credits']),networked=False,replay=False,ownUnits=units))
            def do_POST(self):
                path=urlsplit(self.path).path;q=parse_qs(urlsplit(self.path).query);f['orders'].append((path,q))
                if path.endswith('build-extractor'):
                    if f['credits']<700:f['errors'].append('mine ordered without budget')
                    f['stage']='extractor';x=250;kind=extractor
                elif path.endswith('build-factory'):
                    if not f['mine_done']:f['errors'].append('factory ordered before completed extractor')
                    if scenario=='unknown':return self.reply(dict(message='result unknown'),503)
                    f['stage']='factory';x=350;kind='landFactory'
                elif path.endswith('produce-tank'):
                    if not f['factory_done']:f['errors'].append('production before completed factory')
                    if f['credits']<350:f['errors'].append('production without budget')
                    if f['cycle'] and 70+f['cycle'] not in f['tanks']:f['errors'].append('next tank before previous completed')
                    f['stage']='produce';f['cycle']+=1;x=350;kind='c_tank'
                else:f['errors'].append('unexpected command '+path);return self.reply({},404)
                f['polls']=0;self.reply(dict(status='queued',type=kind,targetX=x,targetY=250))
        server=http.server.HTTPServer(('127.0.0.1',0),Handler);worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
        try:
            with tempfile.TemporaryDirectory() as d:
                p=subprocess.run(['java','-Drwagent.port='+str(server.server_port),'-cp',str(JAR),'io.rwagent.client.OpeningClient'],cwd=d,capture_output=True,text=True,timeout=20)
                events=[json.loads(x) for x in next(pathlib.Path(d).glob('rw-agent-reports/opening-*.jsonl')).read_text().splitlines()]
                passed=scenario in ('normal','income','legacy');summary=events[-1]['data']
                self.assertEqual(summary['outcome'],'PASS' if passed else 'FAIL',p.stdout+p.stderr)
                self.assertEqual(p.returncode,0 if passed else 1,p.stdout+p.stderr)
                self.assertEqual(len(f['orders']),orders,p.stdout+p.stderr);self.assertFalse(f['errors'],f['errors'])
                if reason:self.assertIn(reason,summary['reason'])
                if passed:
                    self.assertEqual(summary['completedBuildings'],2);self.assertEqual(summary['completedTanks'],3)
                    self.assertEqual([e['data']['unitId'] for e in events if e['event']=='tank_completed'],[71,72,73])
                    self.assertEqual(len([e for e in events if e['event']=='production_started']),3)
                    self.assertEqual(len(set(q['requestId'][0] for _,q in f['orders'])),5)
                if scenario=='income':self.assertGreaterEqual(len([e for e in events if e['event']=='budget_wait']),2)
        finally:server.shutdown();server.server_close();worker.join()
    def test_full_opening_and_distinct_tanks(self):self.case('normal',5)
    def test_legacy_extractor_without_replacement(self):self.case('legacy',5)
    def test_wait_income_before_mine_and_second_tank(self):self.case('income',5)
    def test_no_resource_sends_nothing(self):self.case('no_mine',0,'no visible')
    def test_completed_mine_lost_during_second_tank_stops(self):self.case('mine_dies',4,'extractor died')
    def test_unknown_factory_command_not_retried(self):self.case('unknown',2,'HTTP 503')
    def test_session_changed_stops(self):self.case('session',1,'Session changed')
    def test_preexisting_extractor_not_accepted(self):self.case('old_mine',1,'no new extractor')
if __name__=='__main__':unittest.main(verbosity=2)
