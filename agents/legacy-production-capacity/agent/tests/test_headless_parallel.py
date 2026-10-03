"""Pure Python checks for two-episode orchestration; no game JVM is started.

Set RW_HEADLESS_RUNNER_UNDER_TEST to a candidate file before it is installed.
Without the override, these tests exercise tools/run_headless.py in this tree.
"""
import copy
from concurrent.futures import ThreadPoolExecutor
import hashlib
import importlib.util
import itertools
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest import mock
import zipfile


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
RUNNER_PATH = Path(os.environ.get('RW_HEADLESS_RUNNER_UNDER_TEST', ROOT / 'tools/run_headless.py'))
spec = importlib.util.spec_from_file_location('headless_parallel_under_test', RUNNER_PATH)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def identity(work, port, session='session-a', agent_sha='agent-hash', game_sha='game-hash'):
    health = {
        'status': 'ok', 'version': '0.07-alpha1', 'port': port, 'allowCommands': True,
        'provenance': {
            'workingDirectory': str(work),
            'reportDirectory': str(work / 'rw-agent-reports'),
            'agentJar': str(work / 'rw-agent-bootstrap.jar'),
            'gameLibJar': str(work / 'game-lib.jar'),
            'agentJarSha256': agent_sha,
            'gameLibJarSha256': game_sha,
        },
    }
    state = {'status': 'running', 'sessionId': session, 'frame': 1}
    return health, state


def ab_profile_document():
    return {'schemaVersion': 1, 'profiles': {
        'A': {'name': 'cap32', 'jvmProperties': {'rwagent.mobileUnitHardCap': 32}},
        'B': {'name': 'cap40', 'jvmProperties': {'rwagent.mobileUnitHardCap': 40}},
    }}


class AbProfileTests(unittest.TestCase):
    def test_canonical_profile_digest_is_stable_across_json_format_and_key_order(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            first, second = base / 'first.json', base / 'second.json'
            first.write_text(json.dumps(ab_profile_document(), ensure_ascii=False, indent=2), encoding='utf-8')
            changed_order = {'profiles': {
                'B': {'jvmProperties': {'rwagent.mobileUnitHardCap': 40}, 'name': 'cap40'},
                'A': {'jvmProperties': {'rwagent.mobileUnitHardCap': 32}, 'name': 'cap32'},
            }, 'schemaVersion': 1}
            second.write_text(json.dumps(changed_order, separators=(',', ':')), encoding='utf-8')

            loaded_first = runner.load_ab_profiles(first)
            loaded_second = runner.load_ab_profiles(second)
            self.assertEqual(loaded_first['schemaVersion'], 1)
            self.assertNotEqual(loaded_first['sourceSha256'], loaded_second['sourceSha256'])
            for arm, expected_cap in (('A', 32), ('B', 40)):
                with self.subTest(arm=arm):
                    profile = loaded_first['profiles'][arm]
                    reordered = loaded_second['profiles'][arm]
                    self.assertEqual(profile['jvmProperties'], {'rwagent.mobileUnitHardCap': expected_cap})
                    self.assertEqual(profile['canonicalJson'], reordered['canonicalJson'])
                    self.assertEqual(profile['digest'], reordered['digest'])
                    self.assertEqual(profile['digest'], hashlib.sha256(profile['canonicalJson'].encode('utf-8')).hexdigest())

    def test_unknown_keys_and_invalid_cap_values_are_rejected_before_launch(self):
        invalid = {
            'unknown JVM property': lambda d: d['profiles']['A']['jvmProperties'].update({'rwagent.port': 51001}),
            'arbitrary JVM property': lambda d: d['profiles']['A']['jvmProperties'].update({'java.security.manager': 'allow'}),
            'extra profile field': lambda d: d['profiles']['A'].update({'jvmArgs': ['-javaagent:other.jar']}),
            'boolean cap': lambda d: d['profiles']['A']['jvmProperties'].update({'rwagent.mobileUnitHardCap': True}),
            'string cap': lambda d: d['profiles']['A']['jvmProperties'].update({'rwagent.mobileUnitHardCap': '32'}),
            'nonpositive cap': lambda d: d['profiles']['A']['jvmProperties'].update({'rwagent.mobileUnitHardCap': 0}),
            'missing arm': lambda d: d['profiles'].pop('B'),
        }
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'profiles.json'
            for label, change in invalid.items():
                with self.subTest(label=label):
                    document = ab_profile_document()
                    change(document)
                    path.write_text(json.dumps(document), encoding='utf-8')
                    with self.assertRaises(ValueError):
                        runner.load_ab_profiles(path)

    def test_ab_and_ba_swap_episode_positions_without_changing_profile_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'profiles.json'
            path.write_text(json.dumps(ab_profile_document()), encoding='utf-8')
            loaded = runner.load_ab_profiles(path)
            ab = runner.order_profiles(loaded, 'AB')
            ba = runner.order_profiles(loaded, 'BA')
            self.assertEqual([profile['name'] for profile in ab], ['cap32', 'cap40'])
            self.assertEqual([profile['name'] for profile in ba], ['cap40', 'cap32'])
            self.assertEqual([profile['digest'] for profile in ba],
                             [profile['digest'] for profile in reversed(ab)])
            with self.assertRaises(ValueError):
                runner.order_profiles(loaded, 'AA')


class AbProfileLaunchTests(unittest.TestCase):
    def _run_profile_match_fixture(self, report_kinds):
        """Exercise the CLI and inspect the launched JVM commands without starting a game."""
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            game = base / 'game'
            game.mkdir()
            (game / 'game-lib.jar').write_bytes(b'known game jar fixture')
            for name in ('libs', 'assets', 'res'):
                (game / name).mkdir()
            jar = base / 'agent.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n')
                archive.writestr('io/rwagent/Fixture.class', b'fixture')
            profiles = base / 'profiles.json'
            profiles.write_text(json.dumps(ab_profile_document()), encoding='utf-8')
            output = base / 'runs'
            ports = itertools.count(51011)
            engines = {}
            client_commands = []
            engine_commands = []

            class FakeEngine:
                def __init__(self, command, *, cwd, stdout, stderr):
                    self.work = Path(cwd)
                    self.number = int(self.work.name[-3:])
                    self.port = int(command[command.index('--port') + 1])
                    self.pid = 7000 + self.number
                    self.returncode = None
                    self.frame = 0
                    engines[self.port] = self
                    engine_commands.append(command)
                    (self.work / 'runtime.json').write_text(json.dumps({
                        'map': 'maps/skirmish/[p2]Small_Island (2p).tmx',
                        'aiDifficulty': 0, 'requestedSpeed': 4,
                        'fogEnabled': True, 'lineOfSightFog': True,
                        'seed': 1000 + self.number, 'seedReproducibilityVerified': False,
                    }), encoding='utf-8')
                    stdout.write('RW_HEADLESS_READY fixture\n')
                    stdout.flush()

                def poll(self):
                    return self.returncode

                def wait(self, timeout=None):
                    if not (self.work / 'stop.request').is_file():
                        raise AssertionError('engine stopped without its own stop request')
                    self.returncode = 0
                    return 0

            class FakeClient:
                def __init__(self, command, *, cwd, stdout, stderr):
                    self.command = command
                    self.work = Path(cwd)
                    self.number = int(self.work.name[-3:])
                    self.is_match = 'io.rwagent.client.MatchClient' in command
                    self.pid = 8000 + self.number * 10 + int(self.is_match)
                    self.returncode = None
                    client_commands.append((self.number, command))

                def communicate(self, timeout=None):
                    if self.is_match:
                        reports = self.work / 'rw-agent-reports'
                        reports.mkdir(exist_ok=True)
                        cap = int(next(arg.split('=', 1)[1] for arg in self.command
                                       if arg.startswith('-Drwagent.mobileUnitHardCap=')))
                        for kind in report_kinds:
                            rows = [{'event': 'battle_config', 'data': {'mobileUnitHardCap': cap,
                                                                        'gameSeconds': 900}},
                                    {'event': 'observation', 'data': {'sessionId': 'session-%d' % self.number}}]
                            (reports / (kind + '-fixture.jsonl')).write_text(
                                ''.join(json.dumps(row) + '\n' for row in rows), encoding='utf-8')
                    self.returncode = 0
                    return b'fixture client complete\n', None

                def poll(self):
                    return self.returncode

                def kill(self):
                    self.returncode = -9

            def fake_popen(command, *, cwd, stdout, stderr):
                if 'io.rwagent.headless.HeadlessRunner' in command:
                    return FakeEngine(command, cwd=cwd, stdout=stdout, stderr=stderr)
                return FakeClient(command, cwd=cwd, stdout=stdout, stderr=stderr)

            def fake_get(port, path):
                engine = engines[port]
                if path == '/economy/preflight':
                    return {'status': 'ok'}
                health, state = identity(engine.work, port, 'session-%d' % engine.number,
                                         runner.sha(jar), runner.sha(game / 'game-lib.jar'))
                if path == '/health':
                    return health
                self.assertEqual(path, '/state')
                engine.frame += 1
                state['frame'] = engine.frame
                return state

            def fake_analyze(path):
                kind = path.name.split('-', 1)[0]
                return {'result': 'PASS', 'task': kind, 'reason': 'fixture', 'issues': [],
                        'summary': {'matchOutcome': 'VICTORY'}}

            args = ['--game-dir', str(game), '--agent-jar', str(jar), '--java', 'fixture-java',
                    '--parallel-pair', '--episodes', '2', '--mode', 'match',
                    '--profiles', str(profiles), '--profile-order', 'AB', '--out', str(output)]
            with mock.patch.object(runner, 'EXPECTED_GAME', runner.sha(game / 'game-lib.jar')), \
                 mock.patch.object(runner.ParallelPair, 'reserve_port', lambda self: next(ports)), \
                 mock.patch.object(runner.subprocess, 'Popen', side_effect=fake_popen), \
                 mock.patch.object(runner, 'get', side_effect=fake_get), \
                 mock.patch.object(runner, 'analyze_file', side_effect=fake_analyze), \
                 mock.patch.object(runner, 'process_info', return_value={}), \
                 mock.patch.object(runner, 'port_owners', return_value={}):
                exit_code = runner.main(args)

            run = next(output.glob('run-*'))
            batch = json.loads((run / 'batch.json').read_text(encoding='utf-8'))
            if 'battle' not in report_kinds:
                self.assertEqual(exit_code, 1, batch['episodes'])
                self.assertEqual((batch['completed'], batch['passed'], batch['completedMatches']), (2, 0, 0))
                for record in batch['episodes']:
                    self.assertEqual(record['status'], 'FAIL')
                    self.assertFalse(record['matchCompleted'])
                    work = run / ('episode-%03d' % record['episode'])
                    self.assertTrue((work / 'episode.json').is_file())
                    self.assertTrue(all((work / 'rw-agent-reports' / (kind + '-fixture.jsonl')).is_file()
                                        for kind in report_kinds), 'the failed run keeps all raw reports')
                return
            self.assertEqual(exit_code, 0, batch['episodes'])
            self.assertEqual((batch['completed'], batch['passed']), (2, 2))
            self.assertEqual(batch['parallelProof']['status'], 'PASS')
            self.assertEqual(len(engine_commands), 2)
            self.assertTrue(all(not any(arg.startswith('-Drwagent.mobileUnitHardCap=') for arg in command)
                                for command in engine_commands), 'the cap belongs to the client JVM')
            match_commands = {number: command for number, command in client_commands
                              if 'io.rwagent.client.MatchClient' in command}
            self.assertEqual(set(match_commands), {1, 2})
            for number, cap, name in ((1, 32, 'cap32'), (2, 40, 'cap40')):
                with self.subTest(episode=number):
                    command = match_commands[number]
                    property_arg = '-Drwagent.mobileUnitHardCap=%d' % cap
                    self.assertIn(property_arg, command)
                    self.assertLess(command.index(property_arg), command.index('io.rwagent.client.MatchClient'))
                    episode = json.loads((run / ('episode-%03d' % number) / 'episode.json').read_text(encoding='utf-8'))
                    self.assertEqual(episode['abProfile']['name'], name)
                    self.assertEqual(episode['abProfile']['jvmProperties'], {'rwagent.mobileUnitHardCap': cap})
                    self.assertEqual(episode['fixedConditions'], batch['fixedConditions'])

    def test_parallel_match_passes_each_profile_to_its_own_client_jvm(self):
        self._run_profile_match_fixture(('battle', 'development', 'economy'))

    def test_parallel_profile_match_rejects_three_reports_without_battle(self):
        self._run_profile_match_fixture(('economy', 'development', 'opening'))


class OwnerAndReportTests(unittest.TestCase):
    def test_match_reports_accept_bootstrap_and_preserve_legacy_contract(self):
        runner.validate_match_report_tasks(['battle', 'economy', 'development'])
        runner.validate_match_report_tasks(['bootstrap', 'battle', 'economy', 'development'])
        for tasks in (['bootstrap', 'battle', 'economy'], ['bootstrap', 'bootstrap', 'battle', 'economy', 'development'],
                      ['battle', 'battle', 'economy', 'development'], ['other', 'battle', 'economy', 'development']):
            with self.subTest(tasks=tasks), self.assertRaises(RuntimeError):
                runner.validate_match_report_tasks(tasks)
    def test_health_must_prove_the_exact_work_and_report_owner(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary) / '实例 A'
            health, state = identity(work, 51001)
            runner.verify_owner(health, state, 51001, work, 'agent-hash', 'game-hash')

            changes = {
                'wrong port': lambda h, s: h.update(port=51002),
                'command disabled': lambda h, s: h.update(allowCommands=False),
                'other working directory': lambda h, s: h['provenance'].update(workingDirectory=str(work.parent / 'B')),
                'other report directory': lambda h, s: h['provenance'].update(reportDirectory=str(work.parent / 'B' / 'rw-agent-reports')),
                'other agent jar': lambda h, s: h['provenance'].update(agentJar=str(work.parent / 'B' / 'rw-agent-bootstrap.jar')),
                'other game jar': lambda h, s: h['provenance'].update(gameLibJar=str(work.parent / 'B' / 'game-lib.jar')),
                'other agent hash': lambda h, s: h['provenance'].update(agentJarSha256='other'),
                'other game hash': lambda h, s: h['provenance'].update(gameLibJarSha256='other'),
                'no running session': lambda h, s: s.update(sessionId=''),
            }
            for label, change in changes.items():
                with self.subTest(label=label):
                    altered_health, altered_state = copy.deepcopy((health, state))
                    change(altered_health, altered_state)
                    with self.assertRaises(RuntimeError):
                        runner.verify_owner(altered_health, altered_state, 51001, work, 'agent-hash', 'game-hash')

    def test_raw_report_exposes_cross_session_commands(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / 'battle.jsonl'
            rows = [
                {'event': 'observation', 'data': {'sessionId': 'session-a'}},
                {'event': 'action', 'data': {'path': '/command/move?unitId=1&sessionId=session-b&requestId=r'}},
            ]
            report.write_text(''.join(json.dumps(row) + '\n' for row in rows), encoding='utf-8')
            self.assertEqual(runner.report_sessions(report), {'session-a', 'session-b'})


class OverlapProofTests(unittest.TestCase):
    def sample_pair(self, wrong_owner=False):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        pair = runner.ParallelPair(root)
        works = {1: root / 'episode-001', 2: root / 'episode-002'}
        ports = {1: 51001, 2: 51002}
        sessions = {1: 'session-a', 2: 'session-b'}
        processes = {n: SimpleNamespace(pid=1000 + n, poll=lambda: None) for n in (1, 2)}
        frames = {1: 0, 2: 0}

        def fake_get(port, path):
            number = next(n for n in (1, 2) if ports[n] == port)
            owner = works[1] if wrong_owner and number == 2 else works[number]
            health, state = identity(owner, port, sessions[number])
            if path == '/health':
                return health
            self.assertEqual(path, '/state')
            frames[number] += 1
            state['frame'] = frames[number]
            return state

        def wait(number):
            return pair.wait_for_peer(number, works[number], ports[number], processes[number],
                                      sessions[number], 'agent-hash', 'game-hash')

        with mock.patch.object(runner, 'get', side_effect=fake_get):
            with ThreadPoolExecutor(max_workers=2) as pool:
                futures = [pool.submit(wait, number) for number in (1, 2)]
                results = []
                for future in futures:
                    try:
                        results.append(future.result(timeout=5))
                    except RuntimeError as error:
                        results.append(error)
        return pair, root, results

    def test_two_live_owners_advance_during_the_same_barrier_sample(self):
        pair, root, results = self.sample_pair()
        self.assertEqual(results, [True, True])
        saved = json.loads((root / 'parallel-proof.json').read_text(encoding='utf-8'))
        self.assertEqual(saved['status'], 'PASS')
        instances = saved['instances']
        for field in ('enginePid', 'port', 'sessionId', 'workDirectory', 'reportDirectory', 'lockPath'):
            with self.subTest(field=field):
                self.assertEqual(len({entry[field] for entry in instances}), 2)
        self.assertTrue(all(entry['frameAfter'] > entry['frameBefore'] for entry in instances))
        self.assertEqual(pair.proof, saved)

    def test_crosswired_health_cannot_be_certified_as_parallel(self):
        pair, root, results = self.sample_pair(wrong_owner=True)
        self.assertEqual(pair.proof['status'], 'FAIL')
        self.assertIn('workingDirectory', pair.proof['reason'])
        self.assertTrue(all(isinstance(result, RuntimeError) for result in results))
        self.assertEqual(json.loads((root / 'parallel-proof.json').read_text(encoding='utf-8'))['status'], 'FAIL')


class FailedEpisodeDoesNotCancelPeerTests(unittest.TestCase):
    def test_early_a_exit_preserves_failure_while_b_finishes_and_keeps_its_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            game = base / 'game'
            game.mkdir()
            (game / 'game-lib.jar').write_bytes(b'known game jar fixture')
            for name in ('libs', 'assets', 'res'):
                (game / name).mkdir()
            (game / 'preferences.ini').write_text('private', encoding='utf-8')
            jar = base / 'agent.jar'
            jar.write_bytes(b'known agent jar fixture')
            out = base / 'output'
            ports = itertools.count(51001)
            by_port = {}
            client_calls = []
            engine_objects = []

            class FakeEngine:
                def __init__(self, command, *, cwd, stdout, stderr):
                    self.work = Path(cwd)
                    self.port = int(command[command.index('--port') + 1])
                    self.pid = 9000 + len(engine_objects) + 1
                    self.returncode = 17 if self.work.name == 'episode-001' else None
                    by_port[self.port] = self
                    engine_objects.append(self)
                    stdout.write('startup failure\n' if self.returncode is not None else 'RW_HEADLESS_READY fixture\n')
                    stdout.flush()

                def poll(self):
                    return self.returncode

                def wait(self, timeout=None):
                    if self.returncode is None:
                        if not (self.work / 'stop.request').exists():
                            raise AssertionError('B was stopped without its own stop request')
                        self.returncode = 0
                    return self.returncode

                def terminate(self):
                    self.returncode = -1

                def kill(self):
                    self.returncode = -9

            def fake_get(port, path):
                engine = by_port[port]
                self.assertEqual(engine.work.name, 'episode-002', 'A must never be treated as healthy')
                health, state = identity(engine.work, port, 'session-b', runner.sha(jar),
                                         runner.sha(game / 'game-lib.jar'))
                if path == '/health':
                    return health
                if path == '/state':
                    engine.frame = getattr(engine, 'frame', 0) + 1
                    state['frame'] = engine.frame
                    return state
                self.assertEqual(path, '/economy/preflight')
                return {'status': 'ok'}

            class FakeClient:
                def __init__(self, command, *, cwd, stdout, stderr):
                    self.work = Path(cwd)
                    self.command = command
                    self.returncode = None
                    self.pid = 9100 + len(client_calls) + 1
                    self.assert_owner()
                    client_calls.append(command)

                def assert_owner(self):
                    self_outer.assertEqual(self.work.name, 'episode-002', 'A client should not run after its engine exits')
                    port = int(next(argument.split('=', 1)[1] for argument in self.command
                                    if argument.startswith('-Drwagent.port=')))
                    self_outer.assertEqual(port, next(p for p, engine in by_port.items() if engine.work == self.work))

                def communicate(self, timeout=None):
                    if 'io.rwagent.client.ControlLoop' in self.command:
                        reports = self.work / 'rw-agent-reports'
                        reports.mkdir(exist_ok=True)
                        (reports / 'roundtrip-fixture.jsonl').write_text(
                            json.dumps({'event': 'observation', 'data': {'sessionId': 'session-b'}}) + '\n',
                            encoding='utf-8')
                    self.returncode = 0
                    return b'fixture client complete\n', None

                def poll(self):
                    return self.returncode

                def kill(self):
                    self.returncode = -9

            self_outer = self

            def fake_popen(command, *, cwd, stdout, stderr):
                if 'io.rwagent.headless.HeadlessRunner' in command:
                    return FakeEngine(command, cwd=cwd, stdout=stdout, stderr=stderr)
                return FakeClient(command, cwd=cwd, stdout=stdout, stderr=stderr)

            args = ['--game-dir', str(game), '--agent-jar', str(jar), '--java', 'fixture-java',
                    '--parallel-pair', '--episodes', '2', '--mode', 'smoke', '--out', str(out)]
            with mock.patch.object(runner, 'EXPECTED_GAME', runner.sha(game / 'game-lib.jar')), \
                 mock.patch.object(runner.ParallelPair, 'reserve_port', lambda self: next(ports)), \
                 mock.patch.object(runner.subprocess, 'Popen', side_effect=fake_popen), \
                 mock.patch.object(runner, 'get', side_effect=fake_get), \
                 mock.patch.object(runner, 'analyze_file', return_value={'result': 'PASS', 'task': 'roundtrip', 'reason': 'ok'}):
                self.assertEqual(runner.main(args), 1, 'the batch must fail because A failed')

            run = next(out.glob('run-*'))
            batch = json.loads((run / 'batch.json').read_text(encoding='utf-8'))
            a, b = batch['episodes']
            self.assertEqual((a['episode'], a['status'], a['engineExitCode']), (1, 'FAIL', 17))
            self.assertEqual((b['episode'], b['status'], b['engineExitCode']), (2, 'PASS', 0))
            self.assertFalse(a['engineAliveAfterCleanup'])
            self.assertFalse(b['engineAliveAfterCleanup'])
            self.assertEqual(batch['parallelProof']['status'], 'FAIL')
            self.assertTrue((run / 'parallel-proof.json').is_file())
            self.assertEqual(len(client_calls), 2, 'B runs both smoke phases after A fails')
            self.assertEqual(len(engine_objects), 2)
            self.assertTrue((run / 'episode-001' / 'episode.json').is_file())
            self.assertTrue((run / 'episode-002' / 'episode.json').is_file())
            self.assertTrue((run / 'episode-002' / 'roundtrip-evidence.zip').is_file())
            self.assertEqual(runner.report_sessions(run / 'episode-002' / 'rw-agent-reports' / 'roundtrip-fixture.jsonl'),
                             {'session-b'})
            self.assertFalse((run / 'episode-001' / 'preferences.ini').exists())
            self.assertFalse((run / 'episode-002' / 'preferences.ini').exists())
            claims = json.loads((run / 'live-claims.json').read_text(encoding='utf-8'))
            roles = [entry['role'] for entry in claims['processes']]
            self.assertEqual(roles.count('engine'), 2, 'both engines are registered while the batch runs')
            self.assertEqual(roles.count('orchestrator'), 1)
            self.assertEqual(len({entry['port'] for entry in claims['processes'] if entry['role'] == 'engine'}),
                             2, 'the registry records each engine port')
            self.assertTrue(all(entry['state'] != 'RUNNING' for entry in claims['processes']
                                if entry['role'] != 'orchestrator'),
                            'finished children are not left looking alive')

    def test_a_report_with_another_session_is_rejected_and_retained(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            game = base / 'game'
            game.mkdir()
            (game / 'game-lib.jar').write_bytes(b'game fixture')
            for name in ('libs', 'assets', 'res'):
                (game / name).mkdir()
            jar = base / 'agent.jar'
            jar.write_bytes(b'agent fixture')
            work = base / 'episode-001'
            failures = []
            pair = runner.ParallelPair(base)

            class FakeEngine:
                pid = 9901
                returncode = None

                def __init__(self, command, *, cwd, stdout, stderr):
                    stdout.write('RW_HEADLESS_READY fixture\n')
                    stdout.flush()

                def poll(self):
                    return self.returncode

                def wait(self, timeout=None):
                    self.returncode = 0
                    return 0

            frames = itertools.count(1)

            def fake_get(port, path):
                self.assertEqual(port, 51003)
                health, state = identity(work, port, 'session-a', runner.sha(jar),
                                         runner.sha(game / 'game-lib.jar'))
                if path == '/health':
                    return health
                if path == '/state':
                    state['frame'] = next(frames)
                    return state
                self.assertEqual(path, '/economy/preflight')
                return {'status': 'ok'}

            class FakeClient:
                returncode = None
                pid = 9902

                def __init__(self, command, *, cwd, stdout, stderr):
                    self.command = command

                def communicate(self, timeout=None):
                    if 'io.rwagent.client.ControlLoop' in self.command:
                        reports = work / 'rw-agent-reports'
                        reports.mkdir(exist_ok=True)
                        rows = [
                            {'event': 'observation', 'data': {'sessionId': 'session-a'}},
                            {'event': 'action', 'data': {'path': '/command/move?sessionId=session-b&requestId=r'}},
                        ]
                        (reports / 'roundtrip-crosswired.jsonl').write_text(
                            ''.join(json.dumps(row) + '\n' for row in rows), encoding='utf-8')
                    self.returncode = 0
                    return b'fixture client complete\n', None

                def poll(self):
                    return self.returncode

                def kill(self):
                    self.returncode = -9

            def fake_popen(command, *, cwd, stdout, stderr):
                if 'io.rwagent.headless.HeadlessRunner' in command:
                    return FakeEngine(command, cwd=cwd, stdout=stdout, stderr=stderr)
                return FakeClient(command, cwd=cwd, stdout=stdout, stderr=stderr)

            args = SimpleNamespace(mode='smoke', speed=4, frames=6000, map='fixture-map', timeout=10,
                                   difficulty=0, tanks=8, mines=3, scout_moves=24, battle_seconds=900)
            with mock.patch.object(pair, 'reserve_port', return_value=51003), \
                 mock.patch.object(pair, 'wait_for_peer', return_value=False), \
                 mock.patch.object(pair, 'mark_failed', side_effect=lambda *args: failures.append(args)), \
                 mock.patch.object(runner.subprocess, 'Popen', side_effect=fake_popen), \
                 mock.patch.object(runner, 'get', side_effect=fake_get), \
                 mock.patch.object(runner, 'analyze_file', return_value={'result': 'PASS', 'task': 'roundtrip', 'reason': 'ok'}):
                record = runner.run_episode(args, game, jar, 'fixture-java', work, 1, pair)

            self.assertEqual(record['status'], 'FAIL')
            self.assertIn('sessions', record['error'])
            self.assertTrue(failures)
            raw = work / 'rw-agent-reports' / 'roundtrip-crosswired.jsonl'
            self.assertTrue(raw.is_file(), 'failed report remains available for diagnosis')
            self.assertEqual(runner.report_sessions(raw), {'session-a', 'session-b'})
            with zipfile.ZipFile(work / 'roundtrip-evidence.zip') as evidence:
                self.assertIn(raw.name, evidence.namelist())
            self.assertEqual(json.loads((work / 'episode.json').read_text(encoding='utf-8'))['status'], 'FAIL')
            self.assertFalse(record['engineAliveAfterCleanup'])


class CancellationCleanupTests(unittest.TestCase):
    def test_cancel_during_client_wait_kills_and_reaps_only_registered_children(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            work = root / 'episode-001'
            work.mkdir()
            pair = runner.ParallelPair(root)
            engine = SimpleNamespace(pid=201, poll=lambda: None)
            pair.register_engine(1, work, engine)

            class HangingClient:
                pid = 202
                returncode = None
                killed = False
                reaped = False

                def poll(self):
                    return self.returncode

                def communicate(self, timeout=None):
                    if timeout is not None:
                        pair.cancel()  # Simulate Ctrl+C while this client is still running.
                        raise runner.subprocess.TimeoutExpired(['fixture-client'], timeout)
                    self.reaped = True
                    return b'partial client evidence\n', None

                def kill(self):
                    self.killed = True
                    self.returncode = -9

            child = HangingClient()
            with mock.patch.object(runner.subprocess, 'Popen', return_value=child):
                with self.assertRaisesRegex(RuntimeError, 'interrupted'):
                    runner.run_client(['fixture-client'], work, 5, work / 'client.log', pair, 1)

            self.assertTrue(pair.cancelled.is_set())
            self.assertTrue((work / 'stop.request').is_file(), 'the registered engine receives a stop request')
            self.assertTrue(child.killed)
            self.assertTrue(child.reaped)
            self.assertEqual(pair.clients, {}, 'no stale client handle remains registered')
            self.assertEqual((work / 'client.log').read_bytes(), b'partial client evidence\n')

    def test_client_claim_write_failure_still_kills_and_reaps_the_spawned_child(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary) / 'episode-001'
            work.mkdir()
            pair = runner.ParallelPair(work.parent)

            class FakeClient:
                pid = 303
                returncode = None
                killed = False
                reaped = False

                def poll(self):
                    return self.returncode

                def kill(self):
                    self.killed = True
                    self.returncode = -9

                def communicate(self, timeout=None):
                    self.reaped = True
                    return b'partial startup log\n', None

            class FailingClaims:
                def add(self, *args, **kwargs):
                    raise OSError('claims file unavailable')

                def exited(self, *args):
                    pass

            child = FakeClient()
            with mock.patch.object(runner.subprocess, 'Popen', return_value=child):
                with self.assertRaisesRegex(OSError, 'claims file unavailable'):
                    runner.run_client(['fixture-client'], work, 5, work / 'client.log', pair, 1,
                                      port=51001, claims=FailingClaims())
            self.assertTrue(child.killed)
            self.assertTrue(child.reaped)
            self.assertEqual(pair.clients, {})
            self.assertEqual((work / 'client.log').read_bytes(), b'partial startup log\n')


class AtomicStateWriteTests(unittest.TestCase):
    """Windows denies os.replace while a reader holds the destination; state writes must survive that.

    Reproduced 2026-09-27: a campaign was BLOCKED with
    `[WinError 5] 拒绝访问: campaign.json.tmp -> campaign.json` while a concurrent reader held the state
    file. The write now retries a transient denial, and must still fail loudly if the reader never leaves.
    """
    @unittest.skipUnless(os.name == 'nt', 'requires Windows FILE_SHARE_DELETE semantics')
    def test_a_transient_reader_is_retried_until_it_releases(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / 'campaign.json'
            target.write_text('{"state": "old"}', encoding='utf-8')
            handle = target.open('r', encoding='utf-8')      # 不含 FILE_SHARE_DELETE，正是阻塞 replace 的形态
            handle.read()
            released = []
            def release():
                time.sleep(0.4)
                handle.close()
                released.append(True)
            thread = threading.Thread(target=release)
            thread.start()
            try:
                runner.write_bytes(target, b'{"state": "new"}')
            finally:
                thread.join(timeout=5)
                if not handle.closed:
                    handle.close()
            self.assertTrue(released, 'the holder released during the retry window')
            self.assertEqual(json.loads(target.read_text(encoding='utf-8'))['state'], 'new')
            self.assertFalse((Path(temporary) / 'campaign.json.tmp').exists(), 'no temp file is left behind')

    @unittest.skipUnless(os.name == 'nt', 'requires Windows FILE_SHARE_DELETE semantics')
    def test_a_permanent_reader_still_fails_loudly(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / 'campaign.json'
            target.write_text('{"state": "old"}', encoding='utf-8')
            with mock.patch.dict(os.environ, {'RW_WRITE_REPLACE_ATTEMPTS': '2'}):
                with target.open('r', encoding='utf-8') as handle:
                    handle.read()
                    with self.assertRaises(PermissionError):
                        runner.write_bytes(target, b'{"state": "new"}')
            self.assertEqual(json.loads(target.read_text(encoding='utf-8'))['state'], 'old',
                             'the previous state survives a denied write')
            self.assertTrue((Path(temporary) / 'campaign.json.tmp').exists(),
                            'the pending bytes stay on disk for forensic recovery')

    def test_injected_transient_denial_retries_and_commits(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / 'campaign.json'
            target.write_bytes(b'old')
            native_replace = Path.replace
            calls = []

            def replace(source, destination):
                calls.append((source, destination))
                if len(calls) < 3:
                    raise PermissionError('injected reader lock')
                return native_replace(source, destination)

            with mock.patch.dict(os.environ, {'RW_WRITE_REPLACE_ATTEMPTS': '3'}), \
                 mock.patch.object(Path, 'replace', replace), \
                 mock.patch.object(runner.time, 'sleep') as delay:
                runner.write_bytes(target, b'new')
            self.assertEqual(len(calls), 3)
            self.assertEqual(delay.call_args_list, [mock.call(0.25), mock.call(0.5)])
            self.assertEqual(target.read_bytes(), b'new')
            self.assertFalse(target.with_suffix('.json.tmp').exists())

    def test_injected_permanent_denial_preserves_old_and_pending_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / 'campaign.json'
            target.write_bytes(b'old')
            with mock.patch.dict(os.environ, {'RW_WRITE_REPLACE_ATTEMPTS': '2'}), \
                 mock.patch.object(Path, 'replace', side_effect=PermissionError('injected reader lock')) as replace, \
                 mock.patch.object(runner.time, 'sleep') as delay:
                with self.assertRaises(PermissionError):
                    runner.write_bytes(target, b'new')
            self.assertEqual(replace.call_count, 2)
            delay.assert_called_once_with(0.25)
            self.assertEqual(target.read_bytes(), b'old')
            self.assertEqual(target.with_suffix('.json.tmp').read_bytes(), b'new')


class LiveClaimsAndReapTests(unittest.TestCase):
    """Orchestrator crash recovery v0: a claims file plus a reaper that never guesses.

    The premise (measured 2026-09-27) is that killing the Python orchestrator leaves both game engines
    running and holding their ports, so recovery must work from a registry the orchestrator wrote while
    it was alive - and must re-verify identity, because a PID can be reused by an unrelated process.
    """
    def claims_file(self, root, processes, header=None):
        entries = copy.deepcopy(processes)
        for entry in entries:
            if entry.get('workDirectory'):
                entry['workDirectory'] = str(Path(entry['workDirectory']).resolve())
        payload = {'schemaVersion': 1, 'batchDirectory': str(root.resolve()), 'orchestratorPid': os.getpid(),
                   'processes': entries}
        if header:
            payload.update(header)
        (root / 'live-claims.json').write_text(json.dumps(payload, ensure_ascii=False), encoding='utf-8')
        return root / 'live-claims.json'

    def test_reap_without_claims_is_a_no_op(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = runner.reap_run(Path(temporary))
            self.assertEqual(report, 0, 'nothing to reap is success, not an error')
            saved = json.loads((Path(temporary) / 'reap-report.json').read_text(encoding='utf-8'))
            self.assertEqual(saved['status'], 'PASS')
            self.assertEqual(saved['killed'], [])

    @mock.patch.object(runner, 'process_info', return_value={})
    @mock.patch.object(runner, 'port_owners', return_value={})
    def test_reap_is_idempotent_and_reports_released_resources(self, _owners, _info):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            # Successful empty identity lookups prove absence independently of the host OS.
            absent = 999000001
            self.claims_file(root, [{'role': 'engine', 'pid': absent, 'port': 51001, 'state': 'RUNNING',
                                     'episode': 1, 'workDirectory': str(root / 'episode-001')}])
            self.assertEqual(runner.reap_run(root), 0)
            first = json.loads((root / 'reap-report.json').read_text(encoding='utf-8'))
            self.assertEqual(first['killed'], [])
            self.assertIn('no longer running', first['skipped'][0]['reason'])
            self.assertEqual(runner.reap_run(root), 0, 'a second reap must also succeed')
            second = json.loads((root / 'reap-report.json').read_text(encoding='utf-8'))
            self.assertEqual(second['status'], 'PASS')
            self.assertEqual(second['killed'], [])
            claims = json.loads((root / 'live-claims.json').read_text(encoding='utf-8'))
            self.assertEqual(claims['processes'][0]['state'], 'GONE', 'the registry records what happened')

    def test_an_unrelated_live_process_is_never_reaped(self):
        # A real, live, unrelated process registered as if it were an engine: the identity checks must
        # refuse it, and it must still be running afterwards.
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bystander = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
            self.addCleanup(lambda: (bystander.kill(), bystander.wait()) if bystander.poll() is None else None)
            self.claims_file(root, [{'role': 'engine', 'pid': bystander.pid, 'port': 51002,
                                     'state': 'RUNNING', 'episode': 1,
                                     'workDirectory': str(root / 'episode-001')}])
            claims_before = (root / 'live-claims.json').read_bytes()
            self.assertEqual(runner.reap_run(root), 0 if os.name == 'nt' else 1)
            self.assertIsNone(bystander.poll(), 'an unrelated process must survive the reaper')
            report = json.loads((root / 'reap-report.json').read_text(encoding='utf-8'))
            self.assertEqual(report['killed'], [])
            if os.name == 'nt':
                self.assertIn('not a headless engine', report['skipped'][0]['reason'])
            else:
                self.assertEqual(report['status'], 'FAIL')
                self.assertIn('lookup unavailable', ' '.join(report['verified']))
                self.assertEqual((root / 'live-claims.json').read_bytes(), claims_before,
                                 'unavailable identity must never mark a live process GONE')

    def test_unsupported_identity_lookup_is_unknown_not_empty(self):
        with mock.patch.object(runner.os, 'name', 'posix'), \
             mock.patch.object(runner.subprocess, 'run') as query:
            self.assertIsNone(runner.process_info([12345]))
            self.assertIsNone(runner.port_owners([51002]))
            self.assertEqual(runner.process_info([]), {})
            self.assertEqual(runner.port_owners([]), {})
        query.assert_not_called()

    def test_a_pid_reused_by_another_process_is_refused_by_port_ownership(self):
        # Same command line, but the claimed port is owned by a different pid: still refuse.
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            entry = {'role': 'engine', 'pid': 4242, 'port': 51003, 'state': 'RUNNING'}
            ok, reason = runner.claims_identity(
                entry, 'java -cp x %s --map m --port 51003' % runner.ENGINE_MARKER, 9999)
            self.assertFalse(ok)
            self.assertIn('not proven', reason)
            ok, reason = runner.claims_identity(
                entry, 'java -cp x %s --map m --port 51004' % runner.ENGINE_MARKER, 4242)
            self.assertFalse(ok)
            self.assertIn('claimed port', reason)
            ok, reason = runner.claims_identity(
                entry, 'java -cp x %s --map m --port 51003' % runner.ENGINE_MARKER, 4242)
            self.assertTrue(ok, reason)
            ok, reason = runner.claims_identity(
                entry, 'java -cp x %s --map m --port 51003' % runner.ENGINE_MARKER, None)
            self.assertFalse(ok, 'a missing LISTEN owner cannot prove this engine is the claimed pid')
            ok, reason = runner.claims_identity({'role': 'orchestrator', 'pid': 4242},
                'python tools/run_headless.py --parallel-pair', None)
            self.assertFalse(ok, 'the orchestrator entry is never reaped, even if its pid is reused')

    def test_client_claim_requires_exact_port_property_not_a_numeric_prefix(self):
        entry = {'role': 'client', 'pid': 4243, 'port': 1234, 'state': 'RUNNING'}
        wrong = 'java -Drwagent.port=12345 -cp x io.rwagent.client.MatchClient'
        ok, reason = runner.claims_identity(entry, wrong, None)
        self.assertFalse(ok, 'a client for port 12345 is not the claimed client for port 1234')
        self.assertIn('port', reason)
        exact = 'java -Drwagent.port=1234 -cp x io.rwagent.client.MatchClient'
        ok, reason = runner.claims_identity(entry, exact, None)
        self.assertTrue(ok, reason)

    def test_reap_refuses_to_kill_when_initial_identity_lookup_is_unavailable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            entry = {'role': 'engine', 'pid': 4242, 'port': 51005, 'state': 'RUNNING',
                     'episode': 1, 'workDirectory': str(root / 'episode-001')}
            for failed_lookup in ('process', 'port'):
                with self.subTest(failed_lookup=failed_lookup):
                    self.claims_file(root, [entry])
                    info = None if failed_lookup == 'process' else {4242: 'java %s --port 51005' % runner.ENGINE_MARKER}
                    owners = None if failed_lookup == 'port' else {51005: 4242}
                    with mock.patch.object(runner, 'process_info', return_value=info), \
                         mock.patch.object(runner, 'port_owners', return_value=owners), \
                         mock.patch.object(runner.subprocess, 'run') as kill:
                        self.assertEqual(runner.reap_run(root), 1)
                    kill.assert_not_called()
                    report = json.loads((root / 'reap-report.json').read_text(encoding='utf-8'))
                    self.assertEqual(report['status'], 'FAIL')
                    self.assertEqual(report['killed'], [])
                    self.assertIn('unavailable', ' '.join(report['verified']))

    def test_reap_cannot_report_pass_when_postkill_verification_is_unavailable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pid, port = 4242, 51006
            entry = {'role': 'engine', 'pid': pid, 'port': port, 'state': 'RUNNING',
                     'episode': 1, 'workDirectory': str(root / 'episode-001')}
            self.claims_file(root, [entry])
            command = 'java %s --port %d' % (runner.ENGINE_MARKER, port)
            with mock.patch.object(runner, 'process_info', side_effect=[{pid: command}, None]), \
                 mock.patch.object(runner, 'port_owners', side_effect=[{port: pid}, None]), \
                 mock.patch.object(runner.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as kill, \
                 mock.patch.object(runner.time, 'sleep'):
                self.assertEqual(runner.reap_run(root), 1)
            kill.assert_called_once()
            report = json.loads((root / 'reap-report.json').read_text(encoding='utf-8'))
            self.assertEqual(report['status'], 'FAIL')
            self.assertEqual([item['pid'] for item in report['killed']], [pid])
            self.assertIn('unavailable', ' '.join(report['verified']))

    def test_reap_refuses_a_claims_file_for_a_different_batch(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'run-a'
            root.mkdir()
            foreign = root.parent / 'run-b'
            pid, port = 4242, 51007
            entry = {'role': 'engine', 'pid': pid, 'port': port, 'state': 'RUNNING',
                     'episode': 1, 'workDirectory': str(foreign / 'episode-001')}
            self.claims_file(root, [entry], {'batchDirectory': str(foreign)})
            with mock.patch.object(runner, 'process_info', return_value={pid: 'java %s --port %d' % (runner.ENGINE_MARKER, port)}), \
                 mock.patch.object(runner, 'port_owners', return_value={port: pid}), \
                 mock.patch.object(runner.subprocess, 'run') as kill:
                self.assertEqual(runner.reap_run(root), 1)
            kill.assert_not_called()
            report = json.loads((root / 'reap-report.json').read_text(encoding='utf-8'))
            self.assertEqual(report['status'], 'FAIL')
            self.assertEqual(report['killed'], [])

    def test_the_registry_records_roles_ports_and_exits(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            claims = runner.LiveClaims(root)
            claims.add('orchestrator', 111, marker='run_headless.py')
            claims.add('engine', 222, port=51004, work=root / 'episode-001', episode=1,
                       marker=runner.ENGINE_MARKER)
            claims.add('client', 333, port=51004, work=root / 'episode-001', episode=1,
                       marker=runner.CLIENT_MARKER)
            saved = json.loads((root / 'live-claims.json').read_text(encoding='utf-8'))
            self.assertEqual([entry['role'] for entry in saved['processes']],
                             ['orchestrator', 'engine', 'client'], 'sorted by pid, every role present')
            engine = next(entry for entry in saved['processes'] if entry['role'] == 'engine')
            self.assertEqual((engine['port'], engine['episode'], engine['state']), (51004, 1, 'RUNNING'))
            claims.exited(222, 0)
            saved = json.loads((root / 'live-claims.json').read_text(encoding='utf-8'))
            engine = next(entry for entry in saved['processes'] if entry['role'] == 'engine')
            self.assertEqual((engine['state'], engine['exitCode']), ('EXITED', 0))
            claims.exited(222, -9)
            saved = json.loads((root / 'live-claims.json').read_text(encoding='utf-8'))
            engine = next(entry for entry in saved['processes'] if entry['role'] == 'engine')
            self.assertEqual(engine['exitCode'], 0, 'the first recorded exit is the authoritative one')


if __name__ == '__main__':
    unittest.main()
