"""Focused G3 real-HTTP execution contracts and bounded synthetic throughput.

RW_G3_BASELINE_JAR optionally includes the accepted immutable G2 candidate.
RW_G3_EVIDENCE_DIR preserves raw reports, native orders and actual elapsed time.
Measurements include JVM startup, HTTP and configured polling; they are wall time,
not CPU, natural game throughput or proof of tactical improvement.
"""
import collections
import copy
import http.server
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock
from urllib.parse import parse_qs, urlsplit

JAR = str(pathlib.Path(sys.argv.pop(1)).resolve())
BASELINE_JAR = os.environ.get('RW_G3_BASELINE_JAR')
EVIDENCE_DIR = os.environ.get('RW_G3_EVIDENCE_DIR')


class G3ExecutionTests(unittest.TestCase):
    maxDiff = 10000

    @staticmethod
    def events(rows, kind):
        return [row['data'] for row in rows if row['event'] == kind]

    def run_factories(self, *, enabled=True, jar=JAR, credits=100000,
                      army=8, hard_cap=40, trace=True, world=True, diagnostics=True, label='factories'):
        ticks = [1000, 3500, 7000, 67000, 69500, 73000, 75500, 78000, 80500]
        frame = [0]
        cash = [credits]
        orders, reads, service = [], [], []
        accepted = {}
        factory_ids = list(range(50, 62))

        def now():
            return ticks[min(frame[0] - 1, len(ticks) - 1)]

        def unit(uid, kind, x=100, y=100):
            building = kind in ('commandCenter', 'landFactory', 'extractorT1')
            return dict(id=uid, type=kind, x=x, y=y, hp=1000, maxHp=1000,
                        dead=False, buildProgress=1, mobile=not building,
                        canAttack=kind == 'heavyTank', building=building,
                        techLevel=2 if kind == 'landFactory' else 1,
                        productionQueue=0, orderType=None)

        def queue(uid):
            # Native acceptance is paid immediately; it becomes visible three
            # observations later. A zero count cannot release this ghost slot.
            return int(uid in accepted and frame[0] >= accepted[uid] + 3)

        def own():
            units = [unit(1, 'commandCenter'), unit(2, 'builder', 120), unit(3, 'builder', 140)]
            units += [unit(10 + i, 'extractorT1', 100 + 30 * i, 250) for i in range(3)]
            units += [unit(100 + i, 'heavyTank', 800, 800) for i in range(army)]
            for uid in factory_ids:
                factory = unit(uid, 'landFactory', 180 + (uid - 50) * 30)
                factory['productionQueue'] = queue(uid)
                units.append(factory)
            return units

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, body, status=200):
                started = time.perf_counter()
                payload = json.dumps(body).encode('utf-8')
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
                service.append(dict(method=self.command, path=urlsplit(self.path).path,
                                    elapsedSeconds=time.perf_counter() - started))

            def do_GET(self):
                path = urlsplit(self.path).path
                reads.append(dict(path=path, frame=frame[0], gameTimeMs=now() if frame[0] else None))
                if path == '/health':
                    return self.reply(dict(status='ok', version='0.07-alpha1', strategyContractVersion=1))
                if path == '/state':
                    frame[0] += 1
                    terminal = frame[0] >= len(ticks)
                    return self.reply(dict(status='running', sessionId='s', frame=frame[0], gameTimeMs=now(),
                        networked=False, replay=False, player=dict(teamId=0, credits=cash[0]),
                        map=dict(width=4000, height=4000, tilesWide=200, tilesHigh=200, tileWidth=20, tileHeight=20),
                        match=dict(outcome='DEFEAT' if terminal else 'ONGOING', nativeDefeat=terminal,
                                   nativeVictory=False, source='native_result_screen'), ownUnits=own()))
                if path == '/combat/observe':
                    return self.reply(dict(status='observed', sessionId='s', gameTimeMs=now(),
                        visibleEnemies=[], rememberedEnemies=[], enemyIntel=[], rememberedBuildings=[]))
                if path == '/combat/production':
                    return self.reply(dict(status='observed', sessionId='s', factories=[dict(id=uid, tier=2,
                        queue=queue(uid), actions=[dict(actionId='heavy', type='heavyTank', cost=800,
                                                       affordable=cash[0] >= 800)]) for uid in factory_ids]))
                if path == '/scout/observe':
                    return self.reply(dict(status='observed', sessionId='s', frame=frame[0], resources=[],
                        visibleThreats=[], rememberedThreats=[], newlyObservedTiles=0))
                if path == '/scout/visible':
                    return self.reply(dict(status='observed', sessionId='s', tiles=[]))
                if path in ('/economy/investments', '/combat/capabilities'):
                    return self.reply(dict(status='observed', sessionId='s', units=[], capabilities=[]))
                return self.reply(dict(status='no_frontier', sessionId='s'))

            def do_POST(self):
                path = urlsplit(self.path).path
                query = parse_qs(urlsplit(self.path).query)
                uid = int(query.get('unitId', ['0'])[0])
                if path != '/command/queue' or query.get('actionId') != ['heavy']:
                    return self.reply(dict(status='rejected', sessionId='s', message='Unsupported legal fixture action'), 409)
                if uid in accepted or cash[0] < 800:
                    orders.append(dict(path=path, unitId=uid, frame=frame[0], gameTimeMs=now(),
                                       accepted=False, creditsBefore=cash[0]))
                    return self.reply(dict(status='rejected', sessionId='s', message='Duplicate or overspend'), 409)
                before = cash[0]
                cash[0] -= 800
                accepted[uid] = frame[0]
                orders.append(dict(path=path, unitId=uid, frame=frame[0], gameTimeMs=now(),
                                   accepted=True, creditsBefore=before, creditsAfter=cash[0], actionId='heavy'))
                return self.reply(dict(status='queued', sessionId='s', requestId=query['requestId'][0],
                                       unitId=uid, frame=frame[0]))

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory(prefix='rw-g3-http-') as cwd:
                command = ['java', '-Dfile.encoding=UTF-8', '-Drwagent.pollMs=60',
                    '-Drwagent.g3Execution=' + str(enabled).lower(),
                    '-Drwagent.g4Forces=false',
                    '-Drwagent.g1Trace=' + str(trace).lower(), '-Drwagent.g2WorldState=' + str(world and trace).lower(),
                    '-Drwagent.additionalDiagnostics=' + str(diagnostics).lower(),
                    '-Drwagent.reachabilitySample=false', '-Drwagent.reconEnabled=false',
                    '-Drwagent.mineTarget=1', '-Drwagent.landFactoryTarget=12',
                    '-Drwagent.mobileUnitHardCap=' + str(hard_cap),
                    '-Drwagent.port=' + str(server.server_port), '-cp', str(jar),
                    'io.rwagent.client.BattleClient', '180']
                started = time.perf_counter()
                process = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
                elapsed = time.perf_counter() - started
                reports = list(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                rows = [json.loads(line) for line in reports[0].read_text(encoding='utf-8').splitlines()] if reports else []
                details = dict(label=label, jar=str(jar), executionEnabled=enabled, traceEnabled=trace,
                    worldStateEnabled=world and trace, additionalDiagnostics=diagnostics, elapsedWallSeconds=elapsed, commands=command,
                    nativeOrders=orders, nativeReads=reads, fixtureResponseService=service,
                    clientElapsedCosts=self.events(rows, 'g3_cost_summary'),
                    reportBytes=reports[0].stat().st_size if reports else 0, exitCode=process.returncode,
                    finalCredits=cash[0], initialCredits=credits, syntheticGameTimes=ticks)
                if EVIDENCE_DIR:
                    directory = pathlib.Path(EVIDENCE_DIR)
                    directory.mkdir(parents=True, exist_ok=True)
                    (directory / (label + '.json')).write_text(json.dumps(details, ensure_ascii=False, indent=2), encoding='utf-8')
                    (directory / (label + '.stdout.log')).write_bytes(process.stdout)
                    if reports:
                        (directory / (label + '.jsonl')).write_bytes(reports[0].read_bytes())
                self.assertEqual(process.returncode, 0, process.stdout.decode('utf-8', errors='replace'))
                self.assertTrue(rows, 'real BattleClient report must be committed')
                self.assertFalse([order for order in orders if not order['accepted']], 'batch cannot submit stale overspend/duplicate orders')
                return orders, rows, details
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def assert_execution_links(self, rows):
        intents = {data['intentId']: data for data in self.events(rows, 'g3_intent')}
        executions = self.events(rows, 'g3_execution')
        self.assertTrue(intents)
        self.assertTrue(executions)
        for execution in executions:
            self.assertIn(execution['intentId'], intents)
            self.assertTrue(execution['ownerGenerations'])
            self.assertTrue(execution['actorIds'])
            self.assertIsNone(execution['executionWitness'], 'receipt is not later execution proof')
            if execution['commitment']['spending'] and execution['nativeAttempted']:
                self.assertTrue(execution['costSourceObservationId'])
                self.assertTrue(execution['costSourceRequestPath'])
                self.assertNotEqual(execution['costSourceRequestPath'].split('?')[0], '/state',
                    'native price provenance cannot be supplied by the state snapshot')
        return executions

    def test_game_time_jumps_multi_factory_and_long_gap_burst_cap(self):
        orders, rows, _ = self.run_factories(label='g3-jumps')
        counts = collections.Counter(order['gameTimeMs'] for order in orders)
        self.assertGreaterEqual(counts[3500], 2, '2500ms elapsed must admit independent factories in one observation')
        self.assertGreaterEqual(counts[7000], 3, '3500ms jump accumulates independent actor budget')
        self.assertEqual(counts[67000], 4, 'a long gap saturates the bounded burst with enough independent actors')
        self.assertLessEqual(max(counts.values()), 4, 'long gaps cannot exceed the default burst')
        for when in counts:
            batch = [order['unitId'] for order in orders if order['gameTimeMs'] == when]
            self.assertEqual(len(batch), len(set(batch)), 'same actor cannot execute conflicting orders in one observation')
        self.assert_execution_links(rows)

    def test_batch_credits_and_paid_queue_gap_remain_protected(self):
        orders, rows, details = self.run_factories(credits=1600, label='g3-credits')
        self.assertEqual(len(orders), 2)
        self.assertEqual([order['gameTimeMs'] for order in orders], [3500, 3500], 'both funded actors share one observation')
        self.assertEqual(details['finalCredits'], 0)
        self.assertEqual(len({order['unitId'] for order in orders}), 2, 'paid invisible queues cannot be spent again')
        self.assertTrue(all(order['creditsAfter'] >= 0 for order in orders))
        self.assert_execution_links(rows)

    def test_batch_military_slots_include_paid_before_queue_visible(self):
        # The mature controller clamps the hard cap to its active army target
        # (24). Preserve that policy and leave two slots with 22 existing units.
        orders, rows, _ = self.run_factories(army=22, hard_cap=24, label='g3-slots')
        self.assertEqual(len(orders), 2, '22 existing soldiers leave exactly two committed armed slots')
        self.assertEqual([order['gameTimeMs'] for order in orders], [3500, 3500], 'both remaining military slots are accounted in the same batch')
        self.assertEqual(len({order['unitId'] for order in orders}), 2)
        self.assert_execution_links(rows)

    def test_trace_and_diagnostics_do_not_change_native_choices(self):
        enabled, _, on = self.run_factories(label='g3-cost-trace-on')
        g1_only, g1_rows, g1 = self.run_factories(world=False, label='g3-cost-g1-only')
        disabled, off_rows, off = self.run_factories(trace=False, diagnostics=False, label='g3-cost-trace-off-diagnostics-off')
        self.assertEqual(enabled, g1_only, 'G2 export toggle preserves all native choices')
        self.assertTrue([row for row in g1_rows if row['event'].startswith('g1_')])
        self.assertFalse([row for row in g1_rows if row['event'].startswith('g2_')])
        self.assertEqual(enabled, disabled, 'export toggles preserve all native actions and acceptance costs')
        self.assertFalse([row for row in off_rows if row['event'].startswith(('g1_', 'g2_'))])
        self.assertGreater(on['elapsedWallSeconds'], 0)
        self.assertGreater(g1['elapsedWallSeconds'], 0)
        self.assertGreater(off['elapsedWallSeconds'], 0)
        for measurement in (on, g1, off):
            costs = measurement['clientElapsedCosts']
            self.assertEqual(len(costs), 1)
            for category in ('sampling', 'world', 'execution'):
                self.assertGreater(costs[0][category + 'Calls'], 0)
                self.assertGreater(costs[0][category + 'Nanos'], 0)

    def test_accepted_g2_and_g3_throughput_uses_same_native_product(self):
        current, _, _ = self.run_factories(label='g3-throughput')
        legacy, _, _ = self.run_factories(enabled=False, label='legacy-gate-current-jar')
        variants = [legacy]
        if BASELINE_JAR:
            baseline, _, _ = self.run_factories(enabled=False, jar=BASELINE_JAR, label='accepted-g2-throughput')
            self.assertEqual(legacy, baseline, 'explicit legacy path preserves accepted G2 native queue stream')
            variants.append(baseline)
        for variant in variants:
            self.assertTrue(all(order['actionId'] == 'heavy' for order in variant + current))
            self.assertGreater(sum(order['gameTimeMs'] <= 7000 for order in current),
                               sum(order['gameTimeMs'] <= 7000 for order in variant))
            self.assertLessEqual(max(collections.Counter(order['gameTimeMs'] for order in variant).values()), 1)

    def fixture(self, name):
        spec = importlib.util.spec_from_file_location('g3_legacy_' + name,
            pathlib.Path(__file__).with_name(name + '.py'))
        module = importlib.util.module_from_spec(spec)
        with mock.patch.object(sys, 'argv', [name + '.py', JAR]):
            spec.loader.exec_module(module)
        return module

    def run_existing(self, name, class_name, method, *, mutation=None):
        module = self.fixture(name)
        original_run = subprocess.run
        original_server_init = http.server.ThreadingHTTPServer.__init__
        packets = []

        def server_init(server, address, handler, *args, **kwargs):
            reply_name = 'send_json' if hasattr(handler, 'send_json') else 'reply'
            original_reply = getattr(handler, reply_name)

            def reply(request, body, *reply_args, **reply_kwargs):
                payload = copy.deepcopy(body)
                if mutation:
                    mutation(urlsplit(request.path).path, payload)
                packets.append(dict(method=request.command, path=request.path, body=payload))
                return original_reply(request, payload, *reply_args, **reply_kwargs)

            observed = type('G3Focused' + handler.__name__, (handler,), {reply_name: reply})
            return original_server_init(server, address, observed, *args, **kwargs)

        def launch(command, *args, **kwargs):
            command = list(command)
            if command and pathlib.Path(command[0]).stem.lower() == 'java':
                command.insert(1, '-Drwagent.g3Execution=true')
                command.insert(1, '-Drwagent.g4Forces=false')
            result = original_run(command, *args, **kwargs)
            if EVIDENCE_DIR and command and pathlib.Path(command[0]).stem.lower() == 'java':
                directory = pathlib.Path(EVIDENCE_DIR)
                directory.mkdir(parents=True, exist_ok=True)
                stem = name + '-' + method + ('-native-queues' if mutation else '')
                reports = list(pathlib.Path(kwargs['cwd']).glob('rw-agent-reports/battle-*.jsonl'))
                if reports:
                    (directory / (stem + '.jsonl')).write_bytes(reports[0].read_bytes())
                (directory / (stem + '-http.json')).write_text(json.dumps(packets, ensure_ascii=False, indent=2), encoding='utf-8')
                data = result.stdout or b''
                (directory / (stem + '.stdout.log')).write_bytes(data if isinstance(data, bytes) else data.encode('utf-8'))
            return result

        with mock.patch.object(subprocess, 'run', launch), \
                mock.patch.object(http.server.ThreadingHTTPServer, '__init__', server_init):
            getattr(getattr(module, class_name)(), method)()

    def test_existing_recon_legal_clear_witness_under_g3(self):
        self.run_existing('test_recon_client', 'ReconPolicyTests',
                          'test_lost_factory_uses_one_unit_and_requires_own_evidence_before_clear')

    def test_existing_local_crisis_lost_contact_semantics_under_g3(self):
        self.run_existing('test_local_army', 'LocalArmyTests',
                          'test_contact_loss_returns_and_releases_without_claiming_kill')

    def test_existing_provider_funding_and_native_mode_under_g3(self):
        self.run_existing('test_engineer_provider', 'EngineerProviderTests',
                          'test_complete_underwater_response_requires_ready_product_and_observed_mode')
        self.run_existing('test_engineer_provider', 'EngineerProviderTests',
                          'test_funding_holds_native_quote_then_releases_on_accepted_build')

    @staticmethod
    def native_nonfactory_queues(path, body):
        if path == '/state':
            for unit in body.get('ownUnits', []):
                # Mobile builders/engineers/combat units are not native queue
                # factories. Extractors and command centers retain their legal
                # queues: the native investment/builder-production guard uses them.
                if unit.get('mobile') is True:
                    unit['productionQueue'] = -1

    def test_native_nonfactory_negative_queue_preserves_provider_chain(self):
        self.run_existing('test_engineer_provider', 'EngineerProviderTests',
                          'test_complete_underwater_response_requires_ready_product_and_observed_mode',
                          mutation=self.native_nonfactory_queues)

    def test_native_nonfactory_negative_queue_preserves_mine_commitments(self):
        self.run_existing('test_surplus_spending', 'SurplusSpendingTests',
                          'test_near_target_rich_economy_invests_when_local_payback_window_is_long',
                          mutation=self.native_nonfactory_queues)

    def test_existing_ordinary_production_reserve_under_g3(self):
        self.run_existing('test_production_capacity', 'ProductionCapacityTests',
                          'test_army_limit_cannot_spend_investment_reserve')


if __name__ == '__main__':
    unittest.main(verbosity=2)
