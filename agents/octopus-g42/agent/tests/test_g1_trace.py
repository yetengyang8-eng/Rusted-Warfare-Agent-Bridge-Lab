"""G1 causal traces and unchanged wire behavior on existing legal HTTP timelines.

The four fixtures remain the behavior oracle. Every normal run compares trace on
against trace off. Set RW_G1_BASELINE_JAR to also compare an immutable pre-G1 JAR;
set RW_G1_EVIDENCE_DIR to preserve the exact reports and normalized comparisons.
These synthetic timelines establish equivalence, not natural combat benefit.
"""
import importlib.util
import json
import os
import pathlib
import re
import subprocess
import sys
import unittest
from unittest import mock
from urllib.parse import parse_qs, urlsplit

JAR = str(pathlib.Path(sys.argv.pop(1)).resolve())
BASELINE_JAR = os.environ.get('RW_G1_BASELINE_JAR')
EVIDENCE_DIR = os.environ.get('RW_G1_EVIDENCE_DIR')
FIXTURES = {
    'recon': ('test_recon_client', 'ReconPolicyTests', (), {}, 1),
    'local_crisis': ('test_local_army', 'LocalArmyTests', ('raid_loss',), {}, 2),
    'strategy_provider': ('test_engineer_provider', 'EngineerProviderTests', ('chain',), {}, 1),
    'ordinary_production': ('test_production_capacity', 'ProductionCapacityTests', ('army',), {}, 1),
}
_MODULES = {}
_UUID = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', re.I)
_WALL_DIAGNOSTICS = {'gameSecondsPerWallSecond', 'observedGameSecondsPerWallSecond',
                     'requestWallTimeMs'}


def fixture_module(name):
    if name not in _MODULES:
        spec = importlib.util.spec_from_file_location('g1_fixture_' + name,
            pathlib.Path(__file__).with_name(name + '.py'))
        module = importlib.util.module_from_spec(spec)
        # Existing standalone fixtures consume their JAR positional argument.
        with mock.patch.object(sys, 'argv', [name + '.py', JAR]):
            spec.loader.exec_module(module)
        _MODULES[name] = module
    return _MODULES[name]


def normalize(value):
    if isinstance(value, dict):
        return {key: normalize(item) for key, item in value.items()
                if key not in _WALL_DIAGNOSTICS}
    if isinstance(value, list):
        return [normalize(item) for item in value]
    if isinstance(value, str):
        return _UUID.sub('<request-id>', value)
    return value


def command_stream(rows):
    result = []
    for row in rows:
        if row['event'] != 'action':
            continue
        data = row['data']
        parsed = urlsplit(data['path'])
        query = parse_qs(parsed.query)
        query.pop('requestId', None)
        result.append(dict(path=parsed.path, query=query,
                           gameTimeMs=data['gameTimeMs'], owner=data['owner']))
    return result


def legacy_events(rows):
    return [dict(event=row['event'], data=normalize(row['data']))
            for row in rows if not row['event'].startswith(('g1_', 'g2_'))]


class G1TraceTests(unittest.TestCase):
    maxDiff = 12000

    def run_fixture(self, label, jar, enabled, variant):
        name, class_name, args, kwargs, rows_index = FIXTURES[label]
        module = fixture_module(name)
        module.JAR = str(pathlib.Path(jar).resolve())
        original_run = subprocess.run

        def launch(command, *arguments, **options):
            command = list(command)
            is_java = bool(command and pathlib.Path(command[0]).stem.lower() == 'java')
            if is_java:
                command.insert(1, '-Drwagent.g1Trace=' + str(enabled).lower())
            result = original_run(command, *arguments, **options)
            if is_java:
                for stream_name in ('stdout', 'stderr'):
                    stream = getattr(result, stream_name, None)
                    text = stream.decode('utf-8', errors='replace') if isinstance(stream, bytes) else stream or ''
                    self.assertNotIn('G1 trace sidecar failure', text, label + ' ' + variant + ' ' + stream_name)
                    if EVIDENCE_DIR:
                        directory = pathlib.Path(EVIDENCE_DIR)
                        directory.mkdir(parents=True, exist_ok=True)
                        (directory / (label + '-' + variant + '-' + stream_name + '.log')).write_text(text, encoding='utf-8')
            return result

        with mock.patch.object(subprocess, 'run', launch):
            rows = getattr(module, class_name)().run_case(*args, **kwargs)[rows_index]
        if EVIDENCE_DIR:
            directory = pathlib.Path(EVIDENCE_DIR)
            directory.mkdir(parents=True, exist_ok=True)
            (directory / (label + '-' + variant + '.jsonl')).write_text(
                ''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows), encoding='utf-8')
            (directory / (label + '-' + variant + '-normalized.json')).write_text(
                json.dumps(dict(jar=module.JAR, traceEnabled=enabled,
                                commands=command_stream(rows), legacy=legacy_events(rows)),
                           ensure_ascii=False, indent=2), encoding='utf-8')
        return rows

    def assert_equivalence(self, label):
        traced = self.run_fixture(label, JAR, True, 'trace-on')
        disabled = self.run_fixture(label, JAR, False, 'trace-off')
        self.assertFalse([row for row in disabled if row['event'].startswith(('g1_', 'g2_'))])
        self.assertFalse([row for row in disabled if 'trace' in row])
        self.assertEqual(command_stream(disabled), command_stream(traced), 'trace cannot change wire commands')
        self.assertEqual(legacy_events(disabled), legacy_events(traced), 'trace cannot change legacy events/outcomes')
        if BASELINE_JAR:
            before = self.run_fixture(label, BASELINE_JAR, False, 'pre-g1')
            self.assertEqual(command_stream(before), command_stream(traced), 'G1 cannot change pre-G1 wire behavior')
            self.assertEqual(legacy_events(before), legacy_events(traced), 'G1 cannot change pre-G1 outcomes')
        self.assert_causal_chain(traced, label)

    def assert_causal_chain(self, rows, label):
        # Observations preserve independent endpoint identity; they are not an
        # assertion that state/combat/scout/menus formed an atomic engine frame.
        observations = {}
        event_ids = set()
        for index, row in enumerate(rows):
            trace = row.get('trace')
            self.assertIsInstance(trace, dict, row['event'])
            self.assertIsNone(trace.get('occurredAtGameTimeMs'))
            self.assertNotIn(trace['eventId'], event_ids, 'every log row has an independent EventId')
            event_ids.add(trace['eventId'])
            observation = trace.get('observation')
            if observation:
                observations.setdefault(observation['observationId'], (index, observation))
                self.assertIn('sourceGameTimeMs', observation)
                self.assertIn('sourceFrame', observation)
                self.assertIn('sourceRangeStartGameTimeMs', observation)
                self.assertIn('sourceRangeEndGameTimeMs', observation)
                self.assertTrue(observation['endpoint'].startswith('/'))
                if trace['phase'] == 'OBSERVATION' and not row['event'].startswith('g2_'):
                    self.assertEqual(row['data'].get('gameTimeMs'), observation['sourceGameTimeMs'])
                    self.assertEqual(row['data'].get('frame'), observation['sourceFrame'])
                    self.assertEqual(row['data'].get('sessionId'), observation['sourceSessionId'])
                    self.assertFalse(observation['atomicWithOtherEndpoints'])
                    if observation['endpoint'] == '/combat/production':
                        self.assertIsNone(observation['sourceGameTimeMs'], 'native production menu has no game time')
                        self.assertIsNone(observation['sourceFrame'], 'native production menu has no frame')
        attempts = [(index, row) for index, row in enumerate(rows)
                    if row['event'] == 'g1_command_attempt']
        self.assertTrue(attempts, 'existing path must emit command attempts')
        accepted = []
        for index, row in attempts:
            command = row['trace']['command']
            self.assertTrue(command['intentId'])
            self.assertTrue(command['commandId'])
            self.assertIsNone(command['ownerGeneration'])
            self.assertFalse(command['receiptProvesExecution'])
            self.assertTrue(command['inputObservationIds'])
            for observation_id in command['inputObservationIds']:
                self.assertIn(observation_id, observations)
                self.assertLess(observations[observation_id][0], index)
            receipts = [(i, receipt) for i, receipt in enumerate(rows)
                        if receipt['event'] == 'command_result'
                        and receipt.get('trace', {}).get('command', {}).get('commandId') == command['commandId']]
            for receipt_index, receipt in receipts:
                self.assertGreater(receipt_index, index)
                receipt_command = receipt['trace']['command']
                self.assertEqual(command['intentId'], receipt_command['intentId'])
                self.assertEqual(receipt['data']['requestId'], receipt_command['nativeRequestId'])
                self.assertEqual('queued', receipt_command['nativeReceiptStatus'])
                self.assertFalse(receipt_command['receiptProvesExecution'])
                accepted.append((receipt_index, command['commandId']))
        self.assertTrue(accepted, 'accepted command must correlate to its attempt')
        witnesses = [(index, row) for index, row in enumerate(rows)
                     if row['event'].startswith('g1_' + label.split('_')[0])
                     and 'witness' in row['event']]
        if label == 'ordinary_production':
            witnesses = [(index, row) for index, row in enumerate(rows)
                         if row['event'] == 'g1_production_witness']
        self.assertTrue(witnesses, 'path needs explicit later evidence: ' + label)
        linked = False
        for witness_index, row in witnesses:
            command = row['trace'].get('command')
            if command:
                self.assertFalse(command['receiptProvesExecution'])
                matches = [receipt_index for receipt_index, command_id in accepted
                           if command_id == command['commandId']]
                self.assertTrue(matches, 'witness must reference an accepted command')
                self.assertLess(min(matches), witness_index)
                if label in ('local_crisis', 'recon'):
                    observed_frame = row['trace']['observation']['sourceFrame']
                    accepted_frame = command['nativeReceiptFrame']
                    if observed_frame is not None and accepted_frame is not None:
                        self.assertGreater(observed_frame, accepted_frame, 'witness needs a later native frame')
                linked = True
        self.assertTrue(linked, 'at least one later witness must link back to its attempt/receipt')
        if label == 'strategy_provider':
            ready = [row for row in rows if row['event'] == 'g1_strategy_ready_match_witness']
            self.assertTrue(ready)
            for row in ready:
                data = row['data']
                self.assertEqual(data['unitId'], 80, 'fixture provider is existing engineer')
                self.assertEqual(data['selectedUnitId'], 81, 'fixture new product is selected jet')
                self.assertFalse(data['producerLineageConfirmed'], 'matching does not prove strict producer lineage')
                command_id = row['trace']['command']['commandId']
                source_attempts = [attempt for _, attempt in attempts
                                   if attempt['trace']['command']['commandId'] == command_id]
                self.assertEqual(len(source_attempts), 1)
                self.assertEqual(source_attempts[0]['data']['commitmentId'], data['commitmentId'])
                self.assertEqual(source_attempts[0]['trace']['command']['actors'], [80])
        if label == 'ordinary_production':
            empty = [row for _, row in witnesses
                     if row['data'].get('witness') == 'QUEUE_EMPTY_AFTER_NONEMPTY_OBSERVED']
            self.assertTrue(empty, 'fixture must exercise queue empty after accepted production')
            for row in empty:
                self.assertEqual(row['data']['queueCount'], 0)
                self.assertFalse(row['data']['productReadyProven'])
                self.assertFalse(row['data']['producerProductLineageConfirmed'])

    def test_recon_trace_preserves_original_commands(self):
        self.assert_equivalence('recon')

    def test_local_crisis_trace_preserves_original_commands(self):
        self.assert_equivalence('local_crisis')

    def test_provider_trace_preserves_original_commands(self):
        self.assert_equivalence('strategy_provider')

    def test_ordinary_production_trace_preserves_original_commands(self):
        self.assert_equivalence('ordinary_production')


if __name__ == '__main__':
    unittest.main(verbosity=2)
