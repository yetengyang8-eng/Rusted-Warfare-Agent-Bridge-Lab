"""Small native-report-shaped fixtures for the read-only fixed-condition A/B aggregator."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
import aggregate_ab as ab
from analyze_reports import analyze_file


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


class AggregationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.agent = self.root / 'agent.jar'
        with zipfile.ZipFile(self.agent, 'w') as jar:
            jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n')
            jar.writestr('io/rwagent/Test.class', b'fixed candidate bytes')
        self.game = self.root / 'game-lib.jar'
        self.game.write_bytes(b'fixed game bytes')
        self.fixed = {
            'agentContentDigest': ab.jar_content_digest(self.agent),
            'agentJarSha256': ab.file_sha(self.agent),
            'gameJarSha256': ab.file_sha(self.game),
            'map': 'maps/skirmish/[p2]Small_Island (2p).tmx',
            'aiDifficulty': 0, 'requestedSpeed': 4.0,
            'timeoutSeconds': 1200, 'battleSeconds': 900,
            'fog': {'fogEnabled': True, 'lineOfSightFog': True},
        }
        self.profiles = {}
        for slot, cap in (('A', 32), ('B', 40)):
            name = 'cap-' + str(cap)
            props = {'rwagent.mobileUnitHardCap': cap}
            canonical = ab.canonical({'name': name, 'jvmProperties': props})
            self.profiles[slot] = {
                'name': name, 'jvmProperties': props, 'canonicalJson': canonical,
                'digest': hashlib.sha256(canonical.encode('utf-8')).hexdigest(),
            }

    def make_battle(self, path, session, cap, outcome, port, *, spend_cost=800,
                    config_cap_override=None, corrupt_economy=False,
                    bad_provenance=False):
        work = path.parent.parent
        agent_sha = 'f' * 64 if bad_provenance else self.fixed['agentJarSha256']
        provenance = {'agentJarSha256': agent_sha,
                      'gameLibJarSha256': self.fixed['gameJarSha256'],
                      'workingDirectory': str(work),
                      'agentJar': str(work / 'rw-agent-bootstrap.jar')}
        health = {'status': 'ok', 'version': '0.07-alpha1', 'port': port,
                  'allowCommands': True,
                  'provenance': {**provenance,
                                 'reportDirectory': str(work / 'rw-agent-reports'),
                                 'gameLibJar': str(work / 'game-lib.jar')}}
        native = ({'outcome': 'ONGOING'} if outcome == 'ONGOING' else
                  {'outcome': outcome, 'source': 'native_result_screen',
                   'nativeVictory': outcome == 'VICTORY', 'nativeDefeat': outcome == 'DEFEAT'})
        first = {'sessionId': session, 'gameTimeMs': 0,
                 'ownUnits': [{'id': 5}], 'match': {'outcome': 'ONGOING'}}
        last = {'sessionId': session, 'gameTimeMs': 120000,
                'ownUnits': [{'id': 7, 'dead': False, 'canAttack': True,
                              'mobile': True, 'buildProgress': 1.0}], 'match': native}
        config = {'gameSeconds': 900,
                  'mobileUnitHardCap': config_cap_override if config_cap_override is not None else cap,
                  'armyCap': 24}
        summary = {'outcome': 'PARTIAL' if outcome == 'ONGOING' else 'PASS',
                   'reason': ('Game-time budget reached without native result' if outcome == 'ONGOING'
                              else 'Native result screen: ' + outcome),
                   'phase': 'battle', 'matchOutcome': outcome, 'commands': 0,
                   'observations': 2, 'ownLosses': 1, 'newCombatUnits': 1,
                   'attackOrders': 0, 'attackOrdersConfirmed': 0,
                   'retreatOrders': 0, 'upgradesCompleted': 0,
                   'completedMines': 0, 'completedFactories': 0,
                   'battleGameTimeMs': 120000, 'spendTotal': spend_cost + (1 if corrupt_economy else 0),
                   'spendByCategory': {'UNIT_PRODUCTION': {'orders': 1, 'cost': spend_cost}},
                   'investmentIntentions': 1, 'investmentCompletions': 1,
                   'investmentCancellations': 0, 'factoryTargetIncreases': 0,
                   'factoryTargetIncreaseBlocks': 0, 'expansionBlocked': 0,
                   'expansionRefusals': 0, 'factoryBlocks': 0,
                   'builderRecoveries': 0, 'builderOrders': 0,
                   'minesReadyAtEnd': 1, 'factorySaturationPct': 80.0,
                   'measuredIncomePerGameSecond': 30.0}
        payloads = [
            ('health', health),
            ('report_provenance', provenance),
            ('battle_config', config),
            ('observation', first),
            ('spend', {'gameTimeMs': 1000, 'category': 'UNIT_PRODUCTION',
                       'cost': spend_cost, 'itemType': 'c_tank', 'producerOrBuilderId': 6}),
            ('investment_intent', {'target': 'NEW_MINE', 'gameTimeMs': 2000}),
            ('investment_released', {'target': 'NEW_MINE', 'reason': 'COMPLETED',
                                     'gameTimeMs': 3000}),
            ('observation', last),
            ('combat_unit_observed', {'id': 7}),
            ('own_loss', {'unitId': 5, 'type': 'c_tank', 'gameTimeMs': 120000}),
            ('summary', summary),
        ]
        if outcome != 'ONGOING':
            payloads.insert(-1, ('match_terminal', native))
        rows = [{'event': event, 'data': data, 'wallTimeMs': 1000 + index}
                for index, (event, data) in enumerate(payloads)]
        path.write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows),
                        encoding='utf-8')
        return config

    def make_pair(self, label, order='AB', *, config_cap_override=None,
                  corrupt_economy=False, partial_slot=None, bad_provenance=False):
        run = self.root / label
        run.mkdir()
        episodes = []
        for number, slot in enumerate(order, 1):
            work = run / ('episode-%03d' % number)
            work.mkdir()
            report_dir = work / 'rw-agent-reports'
            report_dir.mkdir()
            report_path = report_dir / ('battle-%s.jsonl' % number)
            self.agent_data = self.agent.read_bytes()
            (work / 'rw-agent-bootstrap.jar').write_bytes(self.agent_data)
            (work / 'game-lib.jar').write_bytes(self.game.read_bytes())
            session = '%s-session-%s' % (label, number)
            port = 50000 + number
            cap = self.profiles[slot]['jvmProperties']['rwagent.mobileUnitHardCap']
            outcome = ('ONGOING' if slot == partial_slot else
                       'VICTORY' if slot == 'A' else 'DEFEAT')
            config = self.make_battle(report_path, session, cap, outcome, port,
                                      spend_cost=800 if slot == 'A' else 1600,
                                      config_cap_override=(config_cap_override if slot == 'A'
                                                           else None),
                                      corrupt_economy=(corrupt_economy and slot == 'A'),
                                      bad_provenance=(bad_provenance and slot == 'A'))
            verified = analyze_file(report_path, report_dir)
            self.assertEqual(verified['issues'], [])
            args = ['-Dfile.encoding=UTF-8', '-Drwagent.port=' + str(port),
                    '-Drwagent.mobileUnitHardCap=' + str(cap), '-Drwagent.pollMs=125']
            profile = {'slot': slot, **self.profiles[slot], 'actualJvmArgs': args}
            command = ['java', '--map', self.fixed['map'], '--port', str(port),
                       '--speed', '4.0', '--difficulty', '0',
                       '--max-wall-seconds', '1200']
            started = '2026-09-27T00:00:00+00:00'
            write_json(work / 'engine-process.json',
                       {'pid': 10000 + number, 'port': port, 'workDirectory': str(work),
                        'startedUtc': started, 'command': command})
            phase = {'name': 'match', 'exitCode': 0, 'clientJvmArgs': args,
                     'battleConfig': config,
                     'verifiedReports': {'schemaVersion': 1, 'reports': [verified]},
                     'reportEvidence': [{'path': str(report_path),
                                         'sha256': ab.file_sha(report_path),
                                         'sessionId': session}]}
            episode = {'episode': number, 'mode': 'match',
                       'status': 'FAIL' if outcome == 'ONGOING' else 'PASS',
                       'map': self.fixed['map'], 'sessionId': session,
                       'port': port, 'enginePid': 10000 + number, 'startedUtc': started,
                       'reportDirectory': str(report_dir),
                       'lockPath': str(report_dir / 'economy.lock'),
                       'agentJarSha256': self.fixed['agentJarSha256'],
                       'gameJarSha256': self.fixed['gameJarSha256'],
                       'workDirectory': work.name, 'workDirectoryAbsolute': str(work),
                       'fixedConditions': copy.deepcopy(self.fixed),
                       'abProfile': profile, 'phases': [phase],
                       'matchOutcome': outcome, 'matchCompleted': outcome != 'ONGOING',
                       'runtime': {'map': self.fixed['map'], 'aiDifficulty': 0, 'port': port,
                                   'requestedSpeed': 4.0,
                                   'gameJarSha256': self.fixed['gameJarSha256'],
                                   'fogEnabled': True, 'lineOfSightFog': True,
                                   'seed': number + 100, 'seedReproducibilityVerified': False}}
            write_json(work / 'runtime.json', episode['runtime'])
            write_json(work / 'episode.json', episode)
            episodes.append(episode)
        proof = {'schemaVersion': 1, 'status': 'PASS',
                 'instances': [
                     {'episode': episode['episode'], 'enginePid': episode['enginePid'],
                      'port': episode['port'], 'sessionId': episode['sessionId'],
                      'workDirectory': episode['workDirectoryAbsolute'],
                      'reportDirectory': episode['reportDirectory'],
                      'lockPath': episode['lockPath'], 'frameBefore': 1, 'frameAfter': 10}
                     for episode in episodes]}
        write_json(run / 'parallel-proof.json', proof)
        batch = {'schemaVersion': 1, 'mode': 'match', 'parallelPair': True,
                 'parallelProof': proof, 'episodes': episodes,
                 'fixedConditions': copy.deepcopy(self.fixed),
                 'abProfiles': {'schemaVersion': 1, 'profiles': copy.deepcopy(self.profiles),
                                'profileOrder': order, 'sourceSha256': 'a' * 64}}
        write_json(run / 'batch.json', batch)
        return run

    def test_swapped_pairs_aggregate_only_battle_metrics(self):
        first = self.make_pair('first', 'AB')
        second = self.make_pair('second', 'BA')
        result = ab.aggregate([first, second])
        self.assertEqual(result['batchCount'], 2)
        self.assertEqual(result['profileOrderCounts'], {'AB': 1, 'BA': 1})
        self.assertEqual(result['profiles']['A']['victories'], 2)
        self.assertEqual(result['profiles']['B']['defeats'], 2)
        self.assertEqual(result['profiles']['A']['nativeCompletionRate'], 1)
        self.assertEqual(result['profiles']['A']['metrics']['battleGameSeconds']['mean'], 120)
        self.assertEqual(result['profiles']['A']['metrics']['newCombatUnits']['mean'], 1)
        self.assertEqual(result['profiles']['A']['metrics']['unitProductionOrders']['mean'], 1)
        self.assertEqual(result['profiles']['A']['metrics']['spendTotal']['mean'], 800)
        self.assertEqual(result['profiles']['B']['metrics']['spendTotal']['mean'], 1600)
        self.assertTrue(all(row['reportSha256'] and row['sessionId'] for row in result['episodes']))
        self.assertNotIn('winner', result)

    def test_single_pair_inspection_requires_explicit_internal_opt_in(self):
        first = self.make_pair('first', 'AB')
        with self.assertRaisesRegex(ab.AggregationError, 'at least 2 distinct'):
            ab.aggregate([first])
        result = ab.aggregate([first], require_crossover=False)
        self.assertEqual(result['batchCount'], 1)
        self.assertEqual(result['profiles']['A']['validBattleReports'], 1)
        self.assertEqual(result['profiles']['B']['validBattleReports'], 1)

    def test_swapped_engine_process_files_are_rejected(self):
        first = self.make_pair('first', 'AB')
        one = first / 'episode-001' / 'engine-process.json'
        two = first / 'episode-002' / 'engine-process.json'
        one_data, two_data = one.read_bytes(), two.read_bytes()
        one.write_bytes(two_data); two.write_bytes(one_data)
        with self.assertRaisesRegex(ab.AggregationError, 'engine-process PID differs'):
            ab.aggregate([first], require_crossover=False)

    def test_runtime_file_must_match_captured_episode_runtime(self):
        first = self.make_pair('first', 'AB')
        runtime_path = first / 'episode-001' / 'runtime.json'
        runtime = json.loads(runtime_path.read_text(encoding='utf-8'))
        runtime['seed'] += 1
        write_json(runtime_path, runtime)
        with self.assertRaisesRegex(ab.AggregationError, 'runtime.json differs'):
            ab.aggregate([first], require_crossover=False)

    def test_fixed_condition_mismatch_refuses_merge(self):
        first = self.make_pair('first')
        second = self.make_pair('second', 'BA')
        batch_path = second / 'batch.json'
        batch = json.loads(batch_path.read_text(encoding='utf-8'))
        batch['fixedConditions']['map'] = 'different.tmx'
        write_json(batch_path, batch)
        with self.assertRaisesRegex(ab.AggregationError, 'fixed conditions differ'):
            ab.aggregate([first, second])

    def test_candidate_content_digest_mismatch_refuses_merge(self):
        first = self.make_pair('first')
        second = self.make_pair('second', 'BA')
        batch_path = second / 'batch.json'
        batch = json.loads(batch_path.read_text(encoding='utf-8'))
        batch['fixedConditions']['agentContentDigest'] = 'f' * 64
        write_json(batch_path, batch)
        with self.assertRaisesRegex(ab.AggregationError, 'fixed conditions differ'):
            ab.aggregate([first, second])

    def test_swapped_positions_are_required(self):
        first = self.make_pair('first')
        second = self.make_pair('second')
        with self.assertRaisesRegex(ab.AggregationError, 'AB pair and one BA pair'):
            ab.aggregate([first, second])

    def test_parallel_proof_must_be_pass_and_match_file(self):
        first = self.make_pair('first')
        second = self.make_pair('second', 'BA')
        proof_path = first / 'parallel-proof.json'
        proof = json.loads(proof_path.read_text(encoding='utf-8'))
        proof['instances'][0]['frameAfter'] = 99
        write_json(proof_path, proof)
        with self.assertRaisesRegex(ab.AggregationError, 'parallel-proof.json differs'):
            ab.aggregate([first, second])
        batch_path = first / 'batch.json'
        batch = json.loads(batch_path.read_text(encoding='utf-8'))
        batch['parallelProof']['status'] = 'FAIL'
        write_json(batch_path, batch)
        with self.assertRaisesRegex(ab.AggregationError, 'parallel proof is not PASS'):
            ab.aggregate([first, second])

    def test_sha_tamper_retains_attempt_with_null_metrics(self):
        first = self.make_pair('first')
        second = self.make_pair('second', 'BA')
        raw = first / 'episode-001' / 'rw-agent-reports' / 'battle-1.jsonl'
        raw.write_bytes(raw.read_bytes() + b'\n')
        result = ab.aggregate([first, second])
        row = next(row for row in result['episodes']
                   if row['runDirectory'] == str(first.resolve()) and row['episode'] == 1)
        self.assertEqual(row['reportStatus'], 'INVALID')
        self.assertIn('SHA', row['reportIssue'])
        self.assertIsNone(row['metrics']['spendTotal'])
        self.assertEqual(result['profiles']['A']['attempted'], 2)
        self.assertEqual(result['profiles']['A']['metrics']['spendTotal']['n'], 1)

    def test_missing_raw_report_retains_attempt(self):
        first = self.make_pair('first')
        second = self.make_pair('second', 'BA')
        (first / 'episode-001' / 'rw-agent-reports' / 'battle-1.jsonl').unlink()
        result = ab.aggregate([first, second])
        row = next(row for row in result['episodes']
                   if row['runDirectory'] == str(first.resolve()) and row['episode'] == 1)
        self.assertEqual(row['reportStatus'], 'MISSING')
        self.assertIsNone(row['metrics']['battleGameSeconds'])
        self.assertEqual(result['profiles']['A']['nativeCompletionRate'], .5)

    def test_actual_battle_config_cap_must_match_profile(self):
        first = self.make_pair('first', config_cap_override=99)
        second = self.make_pair('second', 'BA')
        with self.assertRaisesRegex(ab.AggregationError, 'battle_config mobileUnitHardCap'):
            ab.aggregate([first, second])

    def test_economy_summary_mismatch_invalidates_metrics(self):
        first = self.make_pair('first', corrupt_economy=True)
        second = self.make_pair('second', 'BA')
        result = ab.aggregate([first, second])
        row = next(row for row in result['episodes']
                   if row['runDirectory'] == str(first.resolve()) and row['episode'] == 1)
        self.assertEqual(row['reportStatus'], 'INVALID')
        self.assertIn('spendTotal', row['reportIssue'])
        self.assertIsNone(row['metrics']['spendTotal'])

    def test_raw_provenance_jar_mismatch_invalidates_report(self):
        first = self.make_pair('first', bad_provenance=True)
        second = self.make_pair('second', 'BA')
        result = ab.aggregate([first, second])
        row = next(row for row in result['episodes']
                   if row['runDirectory'] == str(first.resolve()) and row['episode'] == 1)
        self.assertEqual(row['reportStatus'], 'INVALID')
        self.assertIn('provenance JAR SHA', row['reportIssue'])
        self.assertIsNone(row['metrics']['spendTotal'])

    def test_noninteger_seed_invalidates_report_metrics(self):
        first = self.make_pair('first')
        second = self.make_pair('second', 'BA')
        episode_path = first / 'episode-001' / 'episode.json'
        episode = json.loads(episode_path.read_text(encoding='utf-8'))
        episode['runtime']['seed'] = 'unknown'
        write_json(episode_path, episode)
        write_json(first / 'episode-001' / 'runtime.json', episode['runtime'])
        batch_path = first / 'batch.json'
        batch = json.loads(batch_path.read_text(encoding='utf-8'))
        batch['episodes'][0] = episode
        write_json(batch_path, batch)
        result = ab.aggregate([first, second])
        row = next(row for row in result['episodes']
                   if row['runDirectory'] == str(first.resolve()) and row['episode'] == 1)
        self.assertEqual(row['reportStatus'], 'INVALID')
        self.assertIn('seed', row['reportIssue'])
        self.assertIsNone(row['metrics']['battleGameSeconds'])

    def test_cleanup_failure_keeps_native_result_but_not_runner_pass(self):
        first = self.make_pair('first')
        second = self.make_pair('second', 'BA')
        episode_path = first / 'episode-001' / 'episode.json'
        episode = json.loads(episode_path.read_text(encoding='utf-8'))
        episode['status'] = 'FAIL'; episode['matchCompleted'] = False
        episode['error'] = 'cleanup failed after native result'
        write_json(episode_path, episode)
        batch_path = first / 'batch.json'
        batch = json.loads(batch_path.read_text(encoding='utf-8'))
        batch['episodes'][0] = episode
        write_json(batch_path, batch)
        result = ab.aggregate([first, second])
        group = result['profiles']['A']
        self.assertEqual(group['nativeCompleted'], 2)
        self.assertEqual(group['runnerPassed'], 1)
        self.assertEqual(group['metrics']['battleGameSeconds']['n'], 2)

    def test_partial_is_censored_and_kept_separate_from_completed_metrics(self):
        first = self.make_pair('first', partial_slot='A')
        second = self.make_pair('second', 'BA')
        result = ab.aggregate([first, second])
        group = result['profiles']['A']
        self.assertEqual(group['attempted'], 2)
        self.assertEqual(group['nativeCompleted'], 1)
        self.assertEqual(group['partial'], 1)
        self.assertEqual(group['metrics']['battleGameSeconds']['n'], 1)
        self.assertEqual(group['partialMetrics']['battleGameSeconds']['n'], 1)
        self.assertEqual(group['nativeCompletionRate'], .5)

    def test_output_file_is_outside_input_runs(self):
        first = self.make_pair('first')
        second = self.make_pair('second', 'BA')
        output = self.root / 'summary.json'
        self.assertEqual(ab.main([str(first), str(second), '--out', str(output)]), 0)
        data = json.loads(output.read_text(encoding='utf-8'))
        self.assertEqual(len(data['episodes']), 4)


if __name__ == '__main__':
    unittest.main()
