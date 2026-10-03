"""Independent HTTP timelines for exploration, native guard acknowledgement and bounded failures."""
import http.server,json,pathlib,subprocess,sys,tempfile,threading,unittest
from urllib.parse import urlsplit,parse_qs
JAR=pathlib.Path(sys.argv.pop(1)).resolve()
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[2]/'tools'))
from analyze_reports import analyze_file

class FrontierTests(unittest.TestCase):
    def case(self,scenario):
        f=dict(tick=0,x=150.,orders=[],tanks=[],tank=None,mine=None,mines=[],guards={},moves=0,goal=None,reveal=None)
        def unit(i,t,x=350.,progress=1,q=-1):
            return dict(id=i,type=t,x=x,y=250.,hp=170.,maxHp=170.,buildProgress=progress,productionQueue=q,dead=False,
                        mobile=True,orderType='guard' if i in f['guards'] and scenario!='unconfirmed_guard' else None,guardTargetId=f['guards'].get(i))
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def reply(self,value,status=200):
                raw=json.dumps(value).encode();self.send_response(status);self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
            def do_GET(self):
                path=urlsplit(self.path).path
                if path=='/health':return self.reply(dict(version='0.07-alpha1'))
                if path=='/economy/production-plan':return self.reply(dict(sessionId='s',factoryId=50,factoryType='landFactory',productType='c_tank',tankCost=350))
                if path=='/scout/observe':
                    if f['moves'] and f['reveal'] is None and scenario in ('success','build_threat'):f['reveal']=f['tick']*30
                    sites=[] if f['reveal'] is None else [dict(tile=22,x=450,y=250,firstSeenFrame=f['reveal'],initiallyVisible=False,currentlyVisible=True)]
                    threats=[dict(id=90,x=f['x']+100,y=250,range=140)] if (scenario=='retreat' and f['moves']==1) or (scenario=='build_threat' and f['mine'] and f['moves']==1) else []
                    return self.reply(dict(sessionId='s',frame=f['tick']*30,exploredTiles=100+(100 if f['reveal'] else 0),resources=sites,visibleThreats=threats))
                if path=='/expansion/plan':
                    if f['reveal'] is not None and f['goal'] is None and scenario in ('success','build_threat'):return self.reply(dict(sessionId='s',builderId=4,extractorType='extractorT1',extractorX=450,extractorY=250,extractorCost=700))
                    return self.reply(dict(message='no legal opening site; eligibleBuilders=1'),409)
                if path=='/scout/plan':
                    if scenario=='no_frontier' or (scenario=='retreat' and f['moves']>=2):return self.reply(dict(status='no_frontier',sessionId='s',builderId=4))
                    return self.reply(dict(status='planned',sessionId='s',builderId=4,targetX=450,targetY=250,targetTile=22,pathVisible=True,pathKnown=True))
                f['tick']+=1
                if f['goal'] is not None and scenario!='stalled':
                    delta=f['goal']-f['x'];f['x']+=max(-130,min(130,delta))
                    if f['x']==f['goal']:f['goal']=None
                q=0
                if f['tank']:
                    f['tank']['polls']+=1;q=1
                    if f['tank']['polls']>=3:f['tanks'].append(f['tank']['id']);f['tank']=None;q=0
                units=[unit(4,'builder',f['x']),unit(50,'landFactory',q=q)]
                for tid in f['tanks']:units.append(unit(tid,'c_tank',f['x'] if tid in f['guards'] else 350))
                if f['mine']:
                    f['mine']+=1
                    units.append(unit(40,'extractorT1',450,progress=1 if f['mine']>=4 else .3))
                self.reply(dict(status='running',sessionId='changed' if scenario=='session' and f['moves'] else 's',
                                frame=f['tick']*30,gameTimeMs=f['tick']*1000,player=dict(credits=10000),ownUnits=units,networked=False,replay=False))
            def do_POST(self):
                path=urlsplit(self.path).path;q=parse_qs(urlsplit(self.path).query);f['orders'].append(path)
                if path.endswith('produce-tank'):f['tank']=dict(id=70+len(f['tanks']),polls=0);return self.reply(dict(status='queued',type='c_tank'))
                if path.endswith('guard'):
                    f['guards'][int(q['unitId'][0])]=int(q['targetId'][0]);return self.reply(dict(status='queued',orderType='guard'))
                if path.endswith('move'):
                    f['moves']+=1
                    if scenario=='unknown':return self.reply(dict(message='result unknown'),503)
                    f['goal']=float(q['x'][0]);return self.reply(dict(status='queued'))
                if path.endswith('build-extractor'):f['mine']=1;return self.reply(dict(status='queued',type='extractorT1',targetX=450,targetY=250))
                raise AssertionError(path)
        server=http.server.HTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            with tempfile.TemporaryDirectory() as d:
                result=subprocess.run(['java','-Drwagent.pollMs=100','-Drwagent.port='+str(server.server_port),'-cp',str(JAR),
                                       'io.rwagent.client.FrontierClient','2','1','2'],cwd=d,capture_output=True,text=True,timeout=25)
                path=next(pathlib.Path(d).glob('rw-agent-reports/frontier-*.jsonl'));report=analyze_file(path)
                expected='PASS' if scenario=='success' else ('FAIL' if scenario in ('unknown','session') else 'PARTIAL')
                self.assertEqual(report['result'],expected,result.stdout+result.stderr+str(report['issues']))
                self.assertEqual(result.returncode,{'PASS':0,'FAIL':1,'PARTIAL':2}[expected])
                self.assertEqual(f['orders'].count('/command/guard'),0 if scenario=='no_frontier' else 2)
                if scenario=='success':
                    self.assertEqual(report['scoutedMines'],1);self.assertEqual(report['scoutArrivals'],1)
                    self.assertEqual(report['summary']['escortsConfirmed'],2);self.assertEqual(report['newMines'],1)
                if scenario=='unknown':self.assertEqual(f['moves'],1,'unknown response must never be blindly retried')
                if scenario=='stalled':self.assertEqual(report['summary']['scoutBlocked'],2)
                if scenario in ('retreat','build_threat'):self.assertEqual(report['summary']['scoutRetreats'],1)
                if scenario=='unconfirmed_guard':self.assertEqual(f['moves'],0,'unacknowledged guards must not authorize scouting')
        finally:server.shutdown();server.server_close();thread.join()
    def test_new_mine_requires_observed_discovery_and_completed_build(self):self.case('success')
    def test_unreachable_frontier_is_partial_not_pass(self):self.case('no_frontier')
    def test_unknown_move_response_is_not_retried(self):self.case('unknown')
    def test_session_change_stops_control(self):self.case('session')
    def test_stalled_scout_stops_at_move_budget(self):self.case('stalled')
    def test_visible_threat_triggers_bounded_retreat(self):self.case('retreat')
    def test_threat_during_construction_cancels_expansion_and_waits_for_retreat(self):self.case('build_threat')
    def test_guard_command_must_be_observed_before_departure(self):self.case('unconfirmed_guard')

if __name__=='__main__':unittest.main(verbosity=2)
