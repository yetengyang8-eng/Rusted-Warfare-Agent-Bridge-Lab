"""External Java client's decisions against independent HTTP timelines; no game simulation."""
import http.server, json, pathlib, subprocess, sys, tempfile, threading, unittest
from urllib.parse import urlsplit, parse_qs
JAR=pathlib.Path(sys.argv.pop(1)).resolve()
class EconomyTests(unittest.TestCase):
    def case(self, scenario, expected_commands, reason=None):
        product='tank' if scenario=='vanilla' else 'c_tank'
        f=dict(frame=0,stage='initial',polls=0,requests=[],last_progress=0)
        def unit(id,type,x=250,y=250,progress=1,queue=-1,dead=False):
            return dict(id=id,type=type,x=x,y=y,buildProgress=progress,productionQueue=queue,dead=dead)
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*a):pass
            def reply(self,data,status=200):
                raw=json.dumps(data).encode();self.send_response(status);self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
            def do_GET(self):
                if self.path=='/health':return self.reply(dict(version='0.07-alpha1'))
                if self.path.startswith('/economy/plan'):
                    if scenario=='poor':return self.reply(dict(message='insufficient credits'),409)
                    return self.reply(dict(sessionId='session',builderId=4,factoryType='landFactory',targetX=250,targetY=250,productType=product))
                f['frame']+=30;f['polls']+=1
                units=[unit(4,'builder',150,250),unit(9,product),unit(8,'landFactory')]
                if f['stage']=='build':
                    progress=[.2,.99999,1][min(f['polls']-1,2)];f['last_progress']=progress
                    if scenario=='old_factory':
                        if f['polls']>=3:return self.reply(dict(message='fixture ends without new factory'),409)
                    elif scenario=='destroyed' and f['polls']>1:pass
                    else:units.append(unit(50,'landFactory',progress=progress,queue=0))
                    if scenario=='builder_dead':units[0]['dead']=True
                elif f['stage']=='produce':
                    q=1 if f['polls']==1 else 0
                    if scenario=='interference':q=2
                    if scenario=='no_queue':q=0
                    units.append(unit(50,'landFactory',queue=q))
                    if f['polls']>1 and scenario!='old_tank':units.append(unit(70,product))
                    if scenario=='ambiguous' and f['polls']>1:units.append(unit(71,product))
                    if scenario=='no_queue' and f['polls']>=3:return self.reply(dict(message='fixture ends without queue cycle'),409)
                self.reply(dict(status='running',sessionId='changed' if scenario=='session' and f['stage']!='initial' else 'session',frame=f['frame'],player=dict(teamId=0),networked=False,replay=False,ownUnits=units))
            def do_POST(self):
                p=urlsplit(self.path);q=parse_qs(p.query);f['requests'].append((p.path,q))
                if p.path=='/command/build-factory':f['stage']='build'
                elif p.path=='/command/produce-tank':
                    if f['last_progress']!=1:return self.reply(dict(message='production before completion'),409)
                    if scenario=='unknown':return self.reply(dict(message='result unknown'),503)
                    f['stage']='produce'
                else:return self.reply({},404)
                f['polls']=0;self.reply(dict(status='queued',targetX=250,targetY=250,type='landFactory' if f['stage']=='build' else product))
        server=http.server.HTTPServer(('127.0.0.1',0),Handler);worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
        try:
            with tempfile.TemporaryDirectory() as d:
                p=subprocess.run(['java','-Drwagent.port='+str(server.server_port),'-cp',str(JAR),'io.rwagent.client.EconomyClient'],cwd=d,capture_output=True,text=True,timeout=20)
                events=[json.loads(x) for x in next(pathlib.Path(d).glob('rw-agent-reports/*.jsonl')).read_text().splitlines()]
                summary=events[-1]['data'];self.assertEqual(events[-1]['event'],'summary')
                self.assertEqual(summary['outcome'],'PASS' if scenario in ('normal','vanilla') else 'FAIL',p.stdout+p.stderr)
                self.assertEqual(p.returncode,0 if scenario in ('normal','vanilla') else 1,p.stdout+p.stderr)
                self.assertEqual(len(f['requests']),expected_commands,p.stdout+p.stderr)
                if reason:self.assertIn(reason,summary['reason'])
                if scenario in ('normal','vanilla'):
                    kinds=[e['event'] for e in events]
                    self.assertLess(kinds.index('factory_completed'),kinds.index('production_started'))
                    self.assertEqual(next(e['data']['unitId'] for e in events if e['event']=='factory_completed'),50)
                    self.assertEqual(next(e['data']['unitId'] for e in events if e['event']=='tank_completed'),70)
                for path,q in f['requests']:self.assertEqual(q['unitId'],['4' if path.endswith('build-factory') else '50'])
        finally:server.shutdown();server.server_close();worker.join()
    def test_new_factory_then_new_tank(self):self.case('normal',2)
    def test_legacy_tank_without_replacement(self):self.case('vanilla',2)
    def test_poor_no_orders(self):self.case('poor',0,'insufficient')
    def test_factory_destroyed_no_production(self):self.case('destroyed',1,'destroyed')
    def test_builder_dead(self):self.case('builder_dead',1,'Builder died')
    def test_session_change(self):self.case('session',1,'Session changed')
    def test_existing_factory_not_accepted(self):self.case('old_factory',1,'without new factory')
    def test_existing_tank_not_accepted(self):self.case('old_tank',2,'without a new tank')
    def test_unknown_command_not_retried(self):self.case('unknown',2,'HTTP 503')
    def test_other_queue_orders_rejected(self):self.case('interference',2,'other orders')
    def test_ambiguous_tanks_rejected(self):self.case('ambiguous',2,'Ambiguous')
    def test_new_tank_without_queue_cycle_not_accepted(self):self.case('no_queue',2,'without queue cycle')
if __name__=='__main__':unittest.main(verbosity=2)
