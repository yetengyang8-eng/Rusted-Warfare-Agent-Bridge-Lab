"""G4 actual BattleClient main-loop HTTP fixture contracts (not native warfare).
Reuse the mature local-army endpoint fixture; assert G4 owners and new-unit state.
"""
import copy
import http.server
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import unittest
from unittest import mock

JAR = str(pathlib.Path(sys.argv.pop(1)).resolve())
with mock.patch.object(sys, 'argv', ['test_local_army.py', JAR]):
    spec = importlib.util.spec_from_file_location('g4_local_fixture', pathlib.Path(__file__).with_name('test_local_army.py'))
    fixture = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixture)


class G4RuntimeTests(unittest.TestCase):
    def run_main(self, *, empty_initial=False, trace=True):
        original_init = http.server.ThreadingHTTPServer.__init__
        original_run = subprocess.run

        def server_init(server, address, handler, *args, **kwargs):
            class Observed(handler):
                def reply(self, body, status=200):
                    if body.get('status') == 'running' and 'ownUnits' in body:
                        body = copy.deepcopy(body)
                        if empty_initial and body['frame'] == 1:
                            body['ownUnits'] = [u for u in body['ownUnits'] if not u['canAttack']]
                        if body['frame'] >= 3:
                            body['ownUnits'].append(dict(id=901, type='heavyTank', x=1100, y=1000,
                                hp=1000, maxHp=1000, dead=False, buildProgress=1, mobile=True,
                                canAttack=True, building=False, techLevel=1, productionQueue=-1, orderType=None))
                    return super().reply(body, status)
            return original_init(server, address, Observed, *args, **kwargs)

        def launch(command, *args, **kwargs):
            command = list(command)
            if command and pathlib.Path(command[0]).stem.lower() == 'java':
                command[1:1] = ['-Drwagent.g3Execution=true', '-Drwagent.g4Forces=true',
                    '-Drwagent.earlyOperations=false',
                    '-Drwagent.g1Trace=' + str(trace).lower(), '-Drwagent.globalStrategy=false']
            result = original_run(command, *args, **kwargs)
            if os.environ.get('RW_G4_EVIDENCE_DIR'):
                folder = pathlib.Path(os.environ['RW_G4_EVIDENCE_DIR']) / ('empty' if empty_initial else 'seeded') / str(trace)
                folder.mkdir(parents=True, exist_ok=True)
                for report in pathlib.Path(kwargs['cwd']).glob('rw-agent-reports/battle-*.jsonl'):
                    (folder / 'battle.jsonl').write_bytes(report.read_bytes())
                (folder / 'process.txt').write_bytes(result.stdout + result.stderr)
            return result

        with mock.patch.object(http.server.ThreadingHTTPServer, '__init__', server_init), mock.patch.object(subprocess, 'run', launch):
            orders, _, rows = fixture.LocalArmyTests().run_case('two_fronts')
        return orders, rows

    def test_main_loop_independent_generals_and_newborn_free(self):
        _, rows = self.run_main()
        states = [r['data'] for r in rows if r['event'] == 'g4_force_state']
        self.assertTrue(states, 'main loop must run the actual G4 adapter and flush')
        self.assertTrue(all(len(s['generals']) == 2 for s in states))
        newborn = [u for s in states for u in s['units'] if u['unitId'] == 901]
        self.assertTrue(newborn)
        self.assertTrue(all(u['allocation'] == 'FREE' and u['membership'] == 'UNATTACHED' for u in newborn))
        accepted = [r['data'] for r in rows if r['event'] == 'g3_execution' and r['data']['nativeAccepted'] and r['data']['lane'] == 'GENERAL']
        self.assertEqual({x['owner'] for x in accepted}, {'general:1', 'general:2'})
        self.assertTrue(all(901 not in x['actorIds'] for x in accepted))
        for row in rows:
            if row['event'] == 'g4_force_transition' and 'before' in row['data']:
                self.assertIn('after', row['data'])
                self.assertTrue(row['data']['reason'])

    def test_empty_initial_force_has_no_accumulation_driven_general_birth(self):
        _, rows = self.run_main(empty_initial=True)
        states = [r['data'] for r in rows if r['event'] == 'g4_force_state']
        self.assertTrue(states)
        self.assertTrue(all(not s['generals'] for s in states))
        self.assertTrue(all(u['allocation'] == 'FREE' and u['membership'] == 'UNATTACHED' for s in states for u in s['units']))

    def test_trace_toggle_preserves_g4_native_command_choices(self):
        on, on_rows = self.run_main(trace=True)
        off, off_rows = self.run_main(trace=False)
        self.assertEqual(on, off)
        self.assertTrue([r for r in on_rows if r.get('trace')])
        self.assertFalse([r for r in off_rows if r.get('trace')])


if __name__ == '__main__':
    unittest.main(verbosity=2)
