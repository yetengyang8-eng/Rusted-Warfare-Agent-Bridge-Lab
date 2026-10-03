"""G2 projection through the real BattleClient and existing legal HTTP fixtures.

Every path compares G2 on/off, including every HTTP request/response and legacy
outcome. RW_G2_BASELINE_JAR adds the immutable ff693c8 G1 baseline (trace on).
RW_G2_EVIDENCE_DIR preserves raw packets, complete reports and JVM output.
These synthetic timelines establish observation/behavior contracts, not combat benefit.
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
from urllib.parse import urlsplit

JAR = str(pathlib.Path(sys.argv.pop(1)).resolve())
BASELINE_JAR = os.environ.get('RW_G2_BASELINE_JAR')
EVIDENCE_DIR = os.environ.get('RW_G2_EVIDENCE_DIR')
_spec = importlib.util.spec_from_file_location('g2_g1_oracle',
    pathlib.Path(__file__).with_name('test_g1_trace.py'))
oracle = importlib.util.module_from_spec(_spec)
with mock.patch.object(sys, 'argv', ['test_g1_trace.py', JAR]):
    _spec.loader.exec_module(oracle)


class G2WorldStateTests(unittest.TestCase):
    maxDiff = 14000

    def run_fixture(self, label, jar, enabled, variant, *, mutation=None, expected_error=None):
        name, class_name, args, kwargs, rows_index = oracle.FIXTURES[label]
        module = oracle.fixture_module(name)
        module.JAR = str(pathlib.Path(jar).resolve())
        packets, output, captured_rows = [], {}, []
        original_init = http.server.ThreadingHTTPServer.__init__
        original_run = subprocess.run

        def server_init(server, address, handler, *arguments, **options):
            # Observe the actual response boundary; no production code or fixture
            # policy is mocked. Only guard-specific tests alter a source packet.
            reply_name = 'send_json' if hasattr(handler, 'send_json') else 'reply'
            original_reply = getattr(handler, reply_name)

            def reply(request, body, *reply_args, **reply_options):
                payload = copy.deepcopy(body)
                # Older policy fixtures omit teamId; native /state includes it.
                # Supply the same legal player identity to all three variants.
                if urlsplit(request.path).path == '/state' and isinstance(payload.get('player'), dict):
                    payload['player'].setdefault('teamId', 0)
                if mutation is not None:
                    mutation(urlsplit(request.path).path, payload)
                status = reply_args[0] if reply_args else reply_options.get('status', 200)
                packets.append(dict(method=request.command, path=request.path,
                                    status=status, body=copy.deepcopy(payload)))
                return original_reply(request, payload, *reply_args, **reply_options)

            observed_handler = type('G2Observed' + handler.__name__, (handler,), {reply_name: reply})
            return original_init(server, address, observed_handler, *arguments, **options)

        def launch(command, *arguments, **options):
            command = list(command)
            is_java = bool(command and pathlib.Path(command[0]).stem.lower() == 'java')
            if is_java:
                command[1:1] = ['-Drwagent.g1Trace=true',
                                '-Drwagent.g2WorldState=' + str(enabled).lower()]
            result = original_run(command, *arguments, **options)
            if is_java:
                output['command'] = command
                output['returncode'] = result.returncode
                for stream in ('stdout', 'stderr'):
                    data = getattr(result, stream, None)
                    text = data.decode('utf-8', errors='replace') if isinstance(data, bytes) else data or ''
                    output[stream] = text
                    self.assertNotIn('G1 trace sidecar failure', text)
                    self.assertNotIn('G2 world sidecar failure', text)
                # Guard fixtures intentionally terminate with exit 1. Preserve
                # the report before the existing fixture cleans its temp cwd.
                if expected_error:
                    reports = list(pathlib.Path(options['cwd']).glob('rw-agent-reports/battle-*.jsonl'))
                    self.assertEqual(len(reports), 1)
                    captured_rows.extend(json.loads(line) for line in
                        reports[0].read_text(encoding='utf-8').splitlines())
            return result

        with mock.patch.object(http.server.ThreadingHTTPServer, '__init__', server_init), \
                mock.patch.object(subprocess, 'run', launch):
            if expected_error:
                # Adapt only the reused fixture's success-exit expectation; the
                # actual process result is untouched and must prove this guard.
                with self.assertRaisesRegex(AssertionError, '1 != 0'):
                    getattr(module, class_name)().run_case(*args, **kwargs)
                self.assertEqual(output['returncode'], 1)
                self.assertIn('java.lang.IllegalStateException: ' + expected_error,
                              output['stdout'] + output['stderr'])
                self.assertTrue(captured_rows)
                rows, raw_outcomes = captured_rows, dict(expectedGuardError=expected_error)
            else:
                result = getattr(module, class_name)().run_case(*args, **kwargs)
                rows = result[rows_index]
                raw_outcomes = [value for index, value in enumerate(result) if index != rows_index]
        if EVIDENCE_DIR:
            directory = pathlib.Path(EVIDENCE_DIR)
            directory.mkdir(parents=True, exist_ok=True)
            stem = label + '-' + variant
            (directory / (stem + '.jsonl')).write_text(
                ''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows), encoding='utf-8')
            (directory / (stem + '-http.json')).write_text(
                json.dumps(packets, ensure_ascii=False, indent=2), encoding='utf-8')
            (directory / (stem + '-outcomes.json')).write_text(
                json.dumps(dict(jar=module.JAR, g1Trace=True, g2WorldState=enabled,
                    rawFixtureOutcomes=raw_outcomes, process=output,
                    commands=oracle.command_stream(rows), legacy=oracle.legacy_events(rows)),
                    ensure_ascii=False, indent=2), encoding='utf-8')
        return rows, packets

    def compare(self, label, *, mutation=None, suffix=''):
        enabled, wire = self.run_fixture(label, JAR, True, 'g2-on' + suffix, mutation=mutation)
        disabled, off_wire = self.run_fixture(label, JAR, False, 'g2-off' + suffix, mutation=mutation)
        self.assertFalse([r for r in disabled if r['event'].startswith('g2_')])
        self.assertTrue([r for r in disabled if r['event'].startswith('g1_')],
                        'G2 toggle must preserve the existing G1 trace')
        variants = [(disabled, off_wire)]
        if BASELINE_JAR:
            variants.append(self.run_fixture(label, BASELINE_JAR, False,
                'ff693c8' + suffix, mutation=mutation))
        for rows, packets in variants:
            self.assertEqual(oracle.command_stream(rows), oracle.command_stream(enabled),
                             'G2 cannot change any wire command')
            self.assertEqual(oracle.legacy_events(rows), oracle.legacy_events(enabled),
                             'G2 cannot change any legacy event or outcome')
            self.assertEqual(oracle.normalize(packets), oracle.normalize(wire),
                             'every actual HTTP request and raw response must remain identical')
        # Keep the existing causal assertions, including accepted-not-executed,
        # later witnesses, producer lineage and queue-empty-not-ready boundaries.
        oracle.G1TraceTests().assert_causal_chain(enabled, label)
        self.assert_world_links(enabled)
        return enabled, wire

    def assert_world_links(self, rows):
        observations = {}
        for index, row in enumerate(rows):
            trace = row.get('trace', {})
            if (trace.get('phase') == 'OBSERVATION' and trace.get('observation')
                    and not row['event'].startswith('g2_')):
                observation = trace['observation']
                observations[observation['observationId']] = (index, observation)
        updates = [(i, r) for i, r in enumerate(rows) if r['event'] == 'g2_world_update']
        events = [(i, r) for i, r in enumerate(rows) if r['event'] == 'g2_event']
        self.assertTrue(updates, 'real reads must reach the G2 adapter')
        self.assertTrue(events, 'legal timeline must produce derived observation events')
        for endpoint in ('/state', '/combat/observe', '/scout/observe', '/combat/production'):
            self.assertTrue([r for _, r in updates if r['data']['accepted']
                             and r['trace']['observation']['endpoint'] == endpoint],
                            'fixture needs an accepted source: ' + endpoint)
        event_ids = [r['data']['eventId'] for _, r in events]
        self.assertEqual(len(event_ids), len(set(event_ids)), 'G2 event identity must be unique')
        for index, row in updates + events:
            self.assertIsNone(row['trace']['occurredAtGameTimeMs'])
            observation = row['trace'].get('observation')
            self.assertIsInstance(observation, dict)
            original_index, original = observations[observation['observationId']]
            self.assertLess(original_index, index, 'derived observations must follow their raw source')
            self.assertEqual(original, observation, 'no source frame/time/session may be rewritten')
            data = row['data']
            self.assertEqual(data['observationId'], observation['observationId'])
            self.assertFalse(data['atomicAcrossSources'])
            if row['event'] == 'g2_event':
                self.assertEqual(row['trace']['evidenceLevel'], data['evidenceLevel'])
                self.assertEqual(data['sourceEndpoint'], observation['endpoint'])
                self.assertEqual(data['sourceGameTimeMs'], observation['sourceGameTimeMs'])
                self.assertEqual(data['sourceFrame'], observation['sourceFrame'])
                self.assertIsNone(data['occurredAtGameTimeMs'])
                if data['previousObservationId'] is not None:
                    earlier, previous = observations[data['previousObservationId']]
                    self.assertLess(earlier, index)
                    self.assertEqual(previous['endpoint'], observation['endpoint'])
                    self.assertEqual(previous['requestPath'], observation['requestPath'])
                    self.assertEqual(data['sourceRangeStartGameTimeMs'], previous['sourceGameTimeMs'])
                self.assertEqual(data['sourceRangeEndGameTimeMs'], observation['sourceGameTimeMs'])
            elif data['accepted']:
                self.assertEqual(data['sourceFreshness']['observation'], observation)
                self.assertFalse(data['commitmentsReconciled'])

    def test_recon_projection_preserves_original_behavior(self):
        self.compare('recon')

    def test_crisis_projection_preserves_original_behavior(self):
        self.compare('local_crisis')

    def test_provider_projection_preserves_original_behavior(self):
        self.compare('strategy_provider')

    def test_ordinary_production_projection_preserves_original_behavior(self):
        rows, _ = self.compare('ordinary_production')
        empty = [r['data']['data'] for r in rows if r['event'] == 'g2_event'
                 and r['data']['kind'] == 'QUEUE_BECAME_EMPTY']
        self.assertTrue(empty, 'fixture must exercise a nonempty-to-empty own queue')
        for data in empty:
            self.assertFalse(data['productReadyProven'])
            self.assertFalse(data['producerProductLineageConfirmed'])
            self.assertFalse(data['acceptedUnobservedOccupancyReleased'])

    def test_native_missing_source_times_stay_missing(self):
        def native_stamps(path, body):
            if path == '/scout/observe':
                body.pop('gameTimeMs', None)
            if path == '/combat/production':
                body.pop('gameTimeMs', None)
                body.pop('frame', None)
        rows, _ = self.compare('recon', mutation=native_stamps, suffix='-native-stamps')
        for endpoint in ('/scout/observe', '/combat/production'):
            updates = [r for r in rows if r['event'] == 'g2_world_update'
                       and r['trace']['observation']['endpoint'] == endpoint]
            self.assertTrue(updates, endpoint)
            for row in updates:
                self.assertIsNone(row['trace']['observation']['sourceGameTimeMs'])
                if row['data']['accepted']:
                    self.assertIsNone(row['data']['sourceFreshness']['ageGameTimeMs'])
                    self.assertEqual(row['data']['sourceFreshness']['gameFreshness'],
                                     'UNKNOWN_SOURCE_GAME_TIME')
                if endpoint == '/combat/production':
                    self.assertIsNone(row['trace']['observation']['sourceFrame'])

    def assert_guard_rejected(self, guard):
        endpoint = '/combat/observe' if guard == 'session' else '/state'
        def reject(path, body):
            if path != endpoint or body.get('frame', 0) < 3:
                return
            if guard in ('networked', 'replay'):
                body[guard] = True
            elif guard == 'rollback':
                body.update(frame=0, gameTimeMs=0)
            else:
                body['sessionId'] = 'foreign-session'
        error = {'session': 'Session changed', 'rollback': 'Frame went backwards'}.get(
            guard, 'Local active match required')
        rows, packets = self.run_fixture('recon', JAR, True, 'guard-' + guard,
                                        mutation=reject, expected_error=error)
        variants = [(JAR, 'g2-off')]
        if BASELINE_JAR:
            variants.append((BASELINE_JAR, 'ff693c8'))
        for jar, variant in variants:
            other, other_packets = self.run_fixture('recon', jar, False,
                'guard-' + guard + '-' + variant, mutation=reject, expected_error=error)
            self.assertFalse([r for r in other if r['event'].startswith('g2_')])
            self.assertEqual(oracle.command_stream(other), oracle.command_stream(rows))
            self.assertEqual(oracle.legacy_events(other), oracle.legacy_events(rows))
            self.assertEqual(oracle.normalize(other_packets), oracle.normalize(packets),
                             'G2 must preserve the original rejected-input wire behavior')
        candidates = [r for r in rows if r.get('trace', {}).get('phase') == 'OBSERVATION'
                      and r['trace']['observation']['endpoint'] == endpoint]
        rejected = [r for r in candidates if r['data'].get(guard) is True
                    or (guard == 'rollback' and r['data'].get('frame') == 0)
                    or (guard == 'session' and r['data'].get('sessionId') == 'foreign-session')]
        self.assertTrue(rejected, 'guard packet must be retained in G1 raw observations')
        rejected_ids = {r['trace']['observation']['observationId'] for r in rejected}
        self.assertTrue([r for r in rows if r['event'] == 'g2_world_update'],
                        'valid initial observation must reach G2')
        for row in rows:
            if row['event'].startswith('g2_'):
                self.assertNotIn(row['trace']['observation']['observationId'], rejected_ids,
                                 'legacy rejected input must not enter G2')
        rejected_packet = next(i for i, p in enumerate(packets)
            if urlsplit(p['path']).path == endpoint and (
                p['body'].get(guard) is True
                or (guard == 'rollback' and p['body'].get('frame') == 0)
                or (guard == 'session' and p['body'].get('sessionId') == 'foreign-session')))
        self.assertFalse([p for p in packets[rejected_packet + 1:] if p['method'] == 'POST'],
                         'no command may follow the rejected source packet')

    def test_network_match_guard_precedes_g2(self):
        self.assert_guard_rejected('networked')

    def test_replay_guard_precedes_g2(self):
        self.assert_guard_rejected('replay')

    def test_frame_rollback_guard_precedes_g2(self):
        self.assert_guard_rejected('rollback')

    def test_foreign_combat_session_guard_precedes_g2(self):
        self.assert_guard_rejected('session')


if __name__ == '__main__':
    unittest.main(verbosity=2)
