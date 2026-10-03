import json,sys,tempfile,unittest,zipfile
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'tools'))
from analyze_reports import analyze_file
from collect_reports import collect

class FrontierReportsTests(unittest.TestCase):
    def sample(self):
        events=[]
        def event(kind,**data):events.append(dict(wallTimeMs=len(events),event=kind,data=data))
        def command(path):event('action',path=path);event('command_result',status='queued')
        event('health',version='test');event('observation',ownUnits=[dict(id=4,x=150,y=250)])
        event('scout_visibility',exploredTiles=100,resources=[])
        event('targets',newTanks=1,maxNewMines=1)
        command('/command/produce-tank?unitId=50');event('tank_completed',unitId=70)
        command('/command/guard?unitId=70&targetId=4');event('escort_assigned',unitId=70,targetId=4)
        event('observation',ownUnits=[dict(id=4,x=150,y=250),dict(id=70,x=150,y=250,orderType='guard',guardTargetId=4)])
        event('escort_confirmed',unitId=70,targetId=4)
        command('/command/move?unitId=4&x=350&y=250')
        event('scout_started',move=1,unitId=4,startX=150,startY=250,targetX=350,targetY=250,frame=10)
        event('observation',ownUnits=[dict(id=4,x=350,y=250)])
        event('scout_visibility',exploredTiles=200,resources=[dict(tile=10,firstSeenFrame=11,currentlyVisible=True)])
        event('scout_discovery',scoutMove=1,resourceTile=10)
        event('scout_arrived',move=1,unitId=4,displacement=200,distance=0)
        command('/command/build-extractor?unitId=4&x=350&y=250');event('extractor_started',unitId=40)
        event('extractor_completed',unitId=40,resourceTile=10,discoveredByScouting=True)
        event('summary',outcome='PASS',commands=4,observations=3,completedMines=1,completedTanks=1,expansionStatus='LIMIT_REACHED',
              scoutMoves=1,scoutArrivals=1,scoutBlocked=0,scoutRetreats=0,scoutedMines=1,newlyExploredTiles=100,escortsAssigned=1,escortsConfirmed=1)
        return events
    def analyze(self,rows):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'frontier-test.jsonl';p.write_text(''.join(json.dumps(e)+'\n' for e in rows));return analyze_file(p)
    def codes(self,rows):return {i['code'] for i in self.analyze(rows)['issues']}
    def test_complete_evidence(self):self.assertEqual(self.analyze(self.sample())['result'],'PASS')
    def test_preknown_mine_cannot_be_called_scout_discovery(self):
        rows=self.sample();rows[2]['data']['resources']=[dict(tile=10)];self.assertIn('UNSUPPORTED_SCOUT_DISCOVERY',self.codes(rows))
    def test_arrival_requires_observed_movement(self):
        rows=self.sample();next(e for e in rows if e['event']=='scout_arrived')['data']['unitId']=999
        self.assertIn('MISSING_SCOUT_OBSERVATION',self.codes(rows))
    def test_claimed_guard_requires_native_state(self):
        rows=self.sample();next(e for e in rows if e['event']=='escort_confirmed')['data']['unitId']=71
        self.assertIn('ESCORT_NOT_OBSERVED',self.codes(rows))
    def test_partial_goal_cannot_pass(self):
        rows=self.sample();next(e for e in rows if e['event']=='targets')['data']['maxNewMines']=2
        self.assertIn('FRONTIER_TARGET_NOT_MET',self.codes(rows))
    def write_session(self,reports,index,session,phases):
        """One session's reports, named <phase>-<epoch>-<id>.jsonl like the real client writes them."""
        base=1790250000000
        names=[]
        for number,phase in enumerate(phases):
            rows=self.sample()
            rows.insert(1,dict(wallTimeMs=1,event='observation',data={'sessionId':session}))
            name='%s-%d-%04x.jsonl'%(phase,base+index*100000+number*1000,index*10+number)
            (reports/name).write_text(''.join(json.dumps(e)+'\n' for e in rows))
            names.append(name)
        return names
    def test_feedback_zip_keeps_raw_and_excludes_game_files(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);reports=root/'reports';reports.mkdir();raw=''.join(json.dumps(e)+'\n' for e in self.sample()).encode()
            (reports/'frontier-test.jsonl').write_bytes(raw);(reports/'private-save.rwsave').write_bytes(b'private')
            bundles=collect(reports,root/'out')
            self.assertEqual(len(bundles),1)
            with zipfile.ZipFile(bundles[0]['path']) as z:
                self.assertEqual(z.read('raw/frontier-test.jsonl'),raw);self.assertFalse(any('rwsave' in p for p in z.namelist()))
                self.assertEqual(json.loads(z.read('reports.json'))['reports'][0]['result'],'PASS');self.assertIsNone(z.testzip())
    def test_one_session_is_never_split_across_bundles(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);reports=root/'reports';reports.mkdir()
            first=self.write_session(reports,0,'session-aaa',['economy','development','battle'])
            second=self.write_session(reports,1,'session-bbb',['economy','battle'])
            bundles=collect(reports,root/'out',target_bytes=1)
            self.assertEqual(len(bundles),2,"each session gets its own bundle when nothing fits together")
            seen={}
            for bundle in bundles:
                with zipfile.ZipFile(bundle['path']) as z:
                    meta=json.loads(z.read('metadata.json'))
                    names=[n for n in z.namelist() if n.startswith('raw/')]
                self.assertEqual(len(meta['bundle']['sessions']),1,"one session per bundle here")
                self.assertTrue(meta['bundle']['singleSession'])
                session=meta['bundle']['sessions'][0]
                self.assertNotIn(session,seen,"a session may not appear in two bundles")
                seen[session]=names
            self.assertEqual(sorted(seen['session-aaa']),sorted('raw/'+n for n in first))
            self.assertEqual(sorted(seen['session-bbb']),sorted('raw/'+n for n in second))
            self.assertFalse(set(seen['session-aaa'])&set(seen['session-bbb']),"phases never mix between sessions")
    def test_an_oversize_session_is_alone_and_marked(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);reports=root/'reports';reports.mkdir()
            big=self.write_session(reports,0,'session-big',['economy','development','battle'])
            small=self.write_session(reports,1,'session-small',['battle'])
            small_bytes=sum((reports/n).stat().st_size for n in small)
            bundles=collect(reports,root/'out',target_bytes=small_bytes)
            oversize=[b for b in bundles if b['oversize']]
            self.assertEqual(len(oversize),1,"the session above the target is marked")
            self.assertIn('oversize',oversize[0]['name'])
            with zipfile.ZipFile(oversize[0]['path']) as z:
                meta=json.loads(z.read('metadata.json'))
                names=[n for n in z.namelist() if n.startswith('raw/')]
            self.assertTrue(meta['bundle']['oversize'])
            self.assertEqual(meta['bundle']['sessions'],['session-big'])
            self.assertEqual(sorted(names),sorted('raw/'+n for n in big),"the oversized session is not split")
            self.assertEqual(len(bundles),2,"the other session is collected separately")
    def test_bundle_index_lists_bundle_session_phase_and_size(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);reports=root/'reports';reports.mkdir()
            self.write_session(reports,0,'session-aaa',['economy','development','battle'])
            self.write_session(reports,1,'session-bbb',['battle'])
            bundles=collect(reports,root/'out',target_bytes=1)
            index=json.loads((root/'out'/'bundle_index.json').read_text())
            self.assertEqual(index['bundleCount'],len(bundles))
            rows=[r for b in index['bundles'] for r in b['reports']]
            self.assertEqual(len(rows),4,"one row per raw report")
            self.assertEqual({r['sessionId'] for r in rows},{'session-aaa','session-bbb'})
            self.assertEqual({r['phase'] for r in rows},{'economy','development','battle'})
            self.assertTrue(all(r['wallTimeMs']>0 and r['bytes']>0 for r in rows))
            self.assertTrue(all(r['wallTimeUtc'] for r in rows))
            csv=(root/'out'/'bundle_index.csv').read_text().splitlines()
            self.assertEqual(csv[0],'bundle,sessionId,reportFile,phase,wallTimeMs,bytes,oversize')
            self.assertEqual(len(csv),5,"header plus one row per report")
            self.assertTrue(all(b['name'] in csv[i+1] for i in range(len(rows)) for b in index['bundles']
                                if any(r['file']==rows[i]['file'] for r in b['reports'])))
    def test_every_volume_stays_within_the_target(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);reports=root/'reports';reports.mkdir()
            for index in range(6):
                self.write_session(reports,index,'session-%03d'%index,['economy','battle'])
            target=20000
            bundles=collect(reports,root/'out',target_bytes=target)
            self.assertGreater(len(bundles),1,"the reports really were split")
            for bundle in bundles:
                if not bundle['oversize']:
                    self.assertLessEqual(bundle['rawBytes'],target,"a normal volume respects the target")
                index_row=json.loads((root/'out'/'bundle_index.json').read_text())
                self.assertLessEqual(bundle['bytes'],index_row['hardCapBytes'])
            sessions=[s for b in bundles for s in b['sessions']]
            self.assertEqual(len(sessions),len(set(sessions)),"no session is spread over two volumes")

if __name__=='__main__':unittest.main(verbosity=2)
