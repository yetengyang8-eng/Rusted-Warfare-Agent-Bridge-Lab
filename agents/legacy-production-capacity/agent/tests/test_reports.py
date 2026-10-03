import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('reports', ROOT / 'tools/analyze_reports.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

class ReportsTests(unittest.TestCase):
    def sample(self):
        return [
            {'wallTimeMs': 0, 'event': 'health', 'data': {'version': 'test'}},
            {'wallTimeMs': 1, 'event': 'observation', 'data': {'ownUnits': []}},
            {'wallTimeMs': 2, 'event': 'extractor_started', 'data': {'unitId': 9007199254740993}},
            {'wallTimeMs': 3, 'event': 'tank_completed', 'data': {'unitId': 7}},
            {'wallTimeMs': 4, 'event': 'extractor_completed', 'data': {'unitId': 9007199254740993}},
            {'wallTimeMs': 5, 'event': 'summary', 'data': {'outcome': 'PASS', 'phase': 'done', 'commands': 0,
             'observations': 1, 'completedMines': 1, 'completedTanks': 1, 'expansionStatus': 'LIMIT_REACHED'}}]
    def analyze(self, rows=None, raw=None):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'development-test.jsonl'
            before = raw if raw is not None else ''.join(json.dumps(r) + '\n' for r in rows).encode()
            p.write_bytes(before); result = m.analyze_file(p)
            self.assertEqual(p.read_bytes(), before)
            return result
    def codes(self, result):
        return {x['code'] for x in result['issues']}
    def test_historical_acceptance(self):
        results = [m.analyze_file(p) for p in sorted((ROOT / 'docs/acceptance-0.04').glob('*.jsonl'))]
        self.assertEqual([r['classification'] for r in results], ['PRECONDITION_REJECTED', 'PASS', 'PASS'])
        self.assertEqual([(r['newMines'],r['newTanks']) for r in results], [(0,0),(0,8),(1,8)])
        self.assertEqual([r['wallDurationSeconds'] for r in results], [.014,69.926,69.922])
        self.assertEqual(results[2]['overlapTankCount'], 2)
        self.assertEqual(results[2]['extractorIntervals'][0]['tankIdsCompletedDuringConstruction'], [17,18])
    def test_earlier_reports(self):
        for name, counts in [('acceptance-0.02-alpha2.jsonl',(0,1,1)),('acceptance-0.03-alpha2.jsonl',(1,1,3))]:
            r=m.analyze_file(ROOT/'docs'/name)
            self.assertEqual(r['result'],'PASS');self.assertEqual((r['newMines'],r['newFactories'],r['newTanks']),counts)
    def test_preserve_summary_and_large_ids(self):
        rows=self.sample();rows[-1]['data']['futureField']={'value':'保留'}
        r=self.analyze(rows);self.assertEqual(r['result'],'PASS');self.assertEqual(r['summary'],rows[-1]['data'])
        self.assertEqual(r['extractorIntervals'][0]['unitId'],9007199254740993)
    def test_missing_summary(self):
        r=self.analyze(self.sample()[:-1]);self.assertIn('MISSING_SUMMARY',self.codes(r));self.assertEqual(r['result'],'INVALID')
    def test_bad_line(self):
        raw=''.join(json.dumps(r)+'\n' for r in self.sample()).encode()+b'broken\n'
        r=self.analyze(raw=raw);self.assertIn('BAD_JSON_LINE',self.codes(r));self.assertEqual(r['result'],'INVALID')
    def test_truncated_tail(self):
        r=self.analyze(raw=b'{"wallTimeMs": 0, "event": ');self.assertIn('TRUNCATED_JSONL',self.codes(r));self.assertIn('MISSING_SUMMARY',self.codes(r))
    def test_duplicate_completion(self):
        rows=self.sample();rows.insert(-1,copy.deepcopy(rows[3]));rows[-2]['wallTimeMs']=4
        r=self.analyze(rows);self.assertIn('DUPLICATE_COMPLETION_ID',self.codes(r));self.assertEqual(r['newTanks'],1)
    def test_count_mismatch(self):
        rows=self.sample();rows[-1]['data']['completedTanks']=2
        self.assertIn('COUNT_MISMATCH',self.codes(self.analyze(rows)))
    def test_multiple_summaries(self):
        rows=self.sample();rows.append(copy.deepcopy(rows[-1]))
        self.assertIn('MULTIPLE_SUMMARIES',self.codes(self.analyze(rows)))
    def test_events_after_summary(self):
        rows=self.sample();rows.append({'wallTimeMs':6,'event':'health','data':{}})
        self.assertIn('EVENTS_AFTER_SUMMARY',self.codes(self.analyze(rows)))
    def test_preexisting_unit_not_counted_as_valid_pass(self):
        rows=self.sample();rows[1]['data']['ownUnits']=[{'id':7}]
        self.assertIn('PREEXISTING_COMPLETION_ID',self.codes(self.analyze(rows)))
    def test_clock_regression(self):
        rows=self.sample();rows[3]['wallTimeMs']=-1
        self.assertIn('NONMONOTONIC_WALL_TIME',self.codes(self.analyze(rows)))
    def test_partial_mine_is_not_complete(self):
        rows=self.sample();del rows[4];rows[-1]['data']['completedMines']=0
        r=self.analyze(rows);self.assertEqual(r['newMines'],0);self.assertIn('INCOMPLETE_EXTRACTOR_INTERVAL',self.codes(r))
    def test_nonfinite_and_invalid_schema(self):
        r=self.analyze(raw=b'{"wallTimeMs":NaN,"event":"summary","data":{}}\n[]\n')
        self.assertIn('BAD_JSON_LINE',self.codes(r));self.assertIn('INVALID_EVENT_SCHEMA',self.codes(r))
    def test_uncommitted_report(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'development-test.jsonl.partial'
            p.write_text(''.join(json.dumps(r)+'\n' for r in self.sample()),encoding='utf-8')
            r=m.analyze_file(p);self.assertEqual(r['result'],'INVALID');self.assertIn('UNCOMMITTED_REPORT',self.codes(r))
    def test_cli_strict_and_outputs(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'input';p.mkdir();(p/'opening-broken.jsonl').write_bytes(b'broken\n')
            out=Path(d)/'out';self.assertEqual(m.main([str(p),'--out',str(out),'--strict']),2)
            self.assertTrue(all((out/('reports.'+ext)).exists() for ext in ('csv','json','md')))

if __name__ == '__main__': unittest.main(verbosity=2)
