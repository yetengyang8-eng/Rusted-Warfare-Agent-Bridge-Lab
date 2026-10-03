"""Test the external loop with a deterministic HTTP fixture, not game simulation."""
import http.server, json, pathlib, subprocess, sys, tempfile, threading, unittest
JAR = pathlib.Path(sys.argv.pop(1)).resolve() if len(sys.argv)>1 and not sys.argv[1].startswith('-') else pathlib.Path(__file__).resolve().parents[1]/'dist/rw-agent-bootstrap.jar'
class ClientTests(unittest.TestCase):
    def case(self, scenario, expected, expected_commands, mode='roundtrip', origin=(100.,100.), dimensions=(1000,1000), extra_args=()):
        fixture = dict(frame=0, x=origin[0], y=origin[1], requests=[], observations=0)
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def reply(self, data, status=200):
                raw=json.dumps(data).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
            def do_GET(self):
                if self.path=='/health':return self.reply(dict(version='0.07-alpha1',allowCommands=True))
                fixture['observations']+=1
                if scenario!='paused':fixture['frame']+=30
                self.reply(dict(status='running',sessionId='changed' if scenario=='change' and fixture['requests'] else 'session-1',frame=fixture['frame'],networked=scenario=='network',replay=False,player=dict(teamId=0),map=dict(width=dimensions[0],height=dimensions[1]),ownUnits=[dict(id=9007199254740993,type='builder',x=fixture['x'],y=fixture['y'],mobile=True,dead=scenario=='dead' and bool(fixture['requests']))]))
            def do_POST(self):
                from urllib.parse import parse_qs,urlsplit
                q=parse_qs(urlsplit(self.path).query);fixture['requests'].append(q)
                if scenario=='unavailable':return self.reply(dict(status='error',message='unknown'),503)
                if not (0<=float(q['x'][0])<dimensions[0] and 0<=float(q['y'][0])<dimensions[1]):
                    return self.reply(dict(status='error',message='target is outside map bounds'),400)
                if scenario not in ('paused',):fixture['x']=float(q['x'][0]);fixture['y']=float(q['y'][0])
                self.reply(dict(status='queued',unitId=int(q['unitId'][0])))
        server=http.server.HTTPServer(('127.0.0.1',0),Handler)
        worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
        try:
            with tempfile.TemporaryDirectory() as d:
                cmd=['java','-Drwagent.port='+str(server.server_port),'-cp',str(JAR),'io.rwagent.client.ControlLoop',mode]
                if mode=='record':cmd+=['1']
                cmd+=list(extra_args)
                p=subprocess.run(cmd,cwd=d,capture_output=True,text=True,timeout=18)
                lines=[json.loads(x) for x in next(pathlib.Path(d).glob('rw-agent-reports/*.jsonl')).read_text().splitlines()]
                self.assertEqual(lines[-1]['data']['outcome'],expected,p.stdout+p.stderr)
                self.assertEqual(p.returncode,0 if expected in ('PASS','RECORDED') else 1,p.stdout+p.stderr)
                self.assertEqual(len(fixture['requests']),expected_commands)
                for r in fixture['requests']:self.assertEqual(r['unitId'],['9007199254740993'])
                if expected=='PASS':
                    self.assertEqual(lines[-1]['data']['arrivals'],2)
                    self.assertEqual(fixture['x'],origin[0])
                    self.assertEqual(fixture['y'],origin[1])
                    plan=next(r['data'] for r in lines if r['event']=='plan')
                    self.assertLessEqual(plan['plannedDistance'],160)
                    self.assertGreaterEqual(plan['plannedDistance'],40)
                    self.assertEqual(plan['mapWidth'],dimensions[0])
                    self.assertEqual(plan['mapHeight'],dimensions[1])
                    if not extra_args and dimensions==(2200,2200):
                        self.assertNotEqual((plan['targetX'],plan['targetY']),(180,180))
                        self.assertAlmostEqual(plan['targetX'],origin[0]+120)
                        self.assertAlmostEqual(plan['targetY'],origin[1]-60)
                if scenario=='paused':self.assertIn('did not advance',lines[-1]['data']['reason'])
        finally:server.shutdown();server.server_close();worker.join()
    def test_roundtrip(self):self.case('normal','PASS',2)
    def test_record_no_commands(self):self.case('normal','RECORDED',0,'record')
    def test_network_rejected(self):self.case('network','FAIL',0)
    def test_session_change_stops_before_return(self):self.case('change','FAIL',1)
    def test_dead_unit_stops_before_return(self):self.case('dead','FAIL',1)
    def test_http_failure_no_retry(self):self.case('unavailable','FAIL',1)
    def test_paused_stops(self):self.case('paused','FAIL',1)
    def test_three_reported_origins(self):
        for origin in [(990,1730),(988.95,1862.397),(940,1780)]:
            with self.subTest(origin=origin):self.case('normal','PASS',2,origin=origin,dimensions=(2200,2200))
    def test_wrong_200x200_map_stops_before_any_order(self):
        for origin in [(990,1730),(988.95,1862.397),(940,1780)]:
            with self.subTest(origin=origin):self.case('normal','FAIL',0,origin=origin,dimensions=(200,200))
    def test_manual_nearby_target(self):self.case('normal','PASS',2,extra_args=('9007199254740993','100','200'))
    def test_manual_long_or_invalid_targets_send_nothing(self):
        for target in [('900','900'),('110','110'),('NaN','100'),('Infinity','100'),('-1','100'),('1000','100')]:
            with self.subTest(target=target):self.case('normal','FAIL',0,extra_args=('9007199254740993',)+target)
if __name__=='__main__':unittest.main(verbosity=2)
