"""Native builder bootstrap HTTP timelines: one purchase, queue cycle, own product, fresh preflight."""
import http.server
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from urllib.parse import parse_qs, urlsplit

JAR = pathlib.Path(sys.argv.pop(1)).resolve()
TOOLS = pathlib.Path(__file__).resolve().parents[2] / 'tools'
sys.path.insert(0, str(TOOLS))
from analyze_reports import analyze_file
from audit_operations import audit


class MatchBootstrapTests(unittest.TestCase):
    def case(self, scenario, match=False):
        f = dict(tick=0, calls=[], orders=[], errors=[], ordered_at=None, preflights=0, health_calls=0)
        pending = scenario in ('queued', 'queued_other_producer')
        producer = 92 if scenario == 'queued_other_producer' else 91

        def unit(i, kind, progress=1, queue=-1):
            return dict(id=i, type=kind, x=400, y=400, hp=170, maxHp=170,
                        buildProgress=progress, productionQueue=queue, dead=False)

        def timing():
            start = 0 if pending else f['ordered_at']
            elapsed = None if start is None else f['tick'] - start
            active = elapsed is not None and elapsed < 3
            done = elapsed is not None and elapsed >= 3 and scenario not in ('cancelled', 'timeout')
            if scenario in ('timeout', 'paused'):
                active = start is not None
                done = False
            if scenario == 'fast_completion' and start is not None:
                active = False
                done = elapsed >= 1
            return active, done

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args): pass

            def reply(self, body, status=200):
                raw = json.dumps(body).encode()
                self.send_response(status)
                self.send_header('Content-Length', str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def do_GET(self):
                path = urlsplit(self.path).path
                f['calls'].append(path)
                if path == '/state':
                    f['tick'] += 1
                    active, done = timing()
                    units = [unit(91, 'commandCenter', queue=1 if active and producer == 91 else 0)]
                    if producer == 92: units.append(unit(92, 'commandCenter', queue=1 if active else 0))
                    if scenario == 'producer_lost' and f['tick'] >= 4: units = []
                    if scenario in ('existing', 'existing_custom') or done:
                        units.append(unit(7, 'custom_builder' if scenario == 'existing_custom' else 'builder'))
                    if scenario in ('final_builder_lost', 'final_builder_replaced') and f['tick'] >= 6:
                        units = [u for u in units if u['id'] != 7]
                        if scenario == 'final_builder_replaced': units.append(unit(8, 'builder'))
                    if done and scenario == 'ambiguous': units.append(unit(8, 'builder'))
                    if scenario == 'partial_builder' and f['tick'] == 5:
                        units = [u for u in units if u['id'] != 7] + [unit(7, 'builder', .99999)]
                    poor = scenario in ('insufficient', 'budget_recovers') and f['tick'] < (99 if scenario == 'insufficient' else 4)
                    return self.reply(dict(status='running', sessionId='changed' if scenario == 'session' and f['tick'] >= 4 or scenario == 'final_session' and f['tick'] >= 6 else 'session',
                                           frame=10 if scenario == 'paused' else f['tick'] * 30,
                                           gameTimeMs=1000 if scenario == 'paused' else f['tick'] * 2000,
                                           player=dict(teamId=2 if scenario == 'player' and f['tick'] >= 4 or scenario == 'final_player' and f['tick'] >= 6 else 0,
                                                       credits=100 if poor else 10000),
                                           networked=scenario == 'network', replay=scenario == 'replay', ownUnits=units))
                if path == '/economy/preflight':
                    f['preflights'] += 1
                    _, done = timing()
                    has_builder = done or scenario in ('existing', 'existing_custom')
                    return self.reply(dict(sessionId='session', commandsAllowed=scenario != 'guard',
                                           recommendation='RUN_DEVELOP' if match or has_builder else 'PREPARE_BUILDER'))
                if path == '/economy/builder-production':
                    if scenario == 'existing': f['errors'].append('existing builder queried a production menu')
                    if scenario == 'no_menu': return self.reply(dict(message='no completed own building exposes an available native builder action'), 409)
                    active, done = timing()
                    busy = scenario == 'busy' and f['tick'] < 4
                    return self.reply(dict(sessionId='other' if scenario == 'plan_session' else 'session',
                                           producerId=producer, producerType='commandCenter', queueCount=1 if active or busy else 0,
                                           buildersQueued=1 if active else 0, totalBuildersQueued=1 if active else 0,
                                           existingBuilders=1 if done or scenario == 'existing_custom' else 0,
                                           builderOrderPending=active,
                                           builderType='custom_builder' if scenario == 'existing_custom' else 'builder',
                                           builderCost=500, builderActionAvailable=True, builderActionAffordable=True))
                # Match's second health request reaches Battle after a committed bootstrap.
                if path == '/health':
                    f['health_calls'] += 1
                    if f['health_calls'] > 1: return self.reply(dict(version='stop-after-bootstrap'), 409)
                    return self.reply(dict(version='0.07-alpha1', allowCommands=scenario != 'commands_disabled'))
                f['errors'].append('unexpected GET ' + path)
                self.reply(dict(message='unexpected GET'), 404)

            def do_POST(self):
                path = urlsplit(self.path).path
                query = parse_qs(urlsplit(self.path).query)
                f['calls'].append(path)
                f['orders'].append((path, query))
                if path != '/command/produce-builder': f['errors'].append('unexpected command ' + path)
                if f['ordered_at'] is not None or pending: f['errors'].append('duplicate builder order')
                if query.get('sessionId') != ['session']: f['errors'].append('wrong command session')
                if query.get('unitId') != [str(producer)]: f['errors'].append('wrong producer')
                f['ordered_at'] = f['tick']
                if scenario == 'unknown_receipt': return self.reply(dict(message='result unknown'), 503)
                return self.reply(dict(status='queued', sessionId='session', unitId=producer,
                                       type='wrong_builder' if scenario == 'wrong_type' else 'builder'))

        server = http.server.HTTPServer(('127.0.0.1', 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True); worker.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                command = ['java', '-Drwagent.port=' + str(server.server_port),
                           '-Drwagent.bootstrapPollMs=20', '-Drwagent.bootstrapGameSeconds=24',
                           '-Drwagent.bootstrapWallSeconds=' + ('1' if scenario == 'paused' else '10'),
                           '-cp', str(JAR), 'io.rwagent.client.MatchClient' if match else 'io.rwagent.client.BootstrapClient']
                if match: command.append('120')
                result = subprocess.run(command, cwd=directory, capture_output=True, text=True, timeout=15)
                reports = list(pathlib.Path(directory).glob('rw-agent-reports/bootstrap-*.jsonl'))
                evidence = os.environ.get('RW_MATCH_BOOTSTRAP_EVIDENCE_DIR')
                if evidence:
                    dest = pathlib.Path(evidence) / (scenario + ('-match' if match else ''))
                    dest.mkdir(parents=True, exist_ok=True)
                    for artifact in pathlib.Path(directory).glob('rw-agent-reports/*.jsonl'):
                        shutil.copyfile(artifact, dest / artifact.name)
                    (dest / 'fixture.json').write_text(json.dumps(f, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
                    (dest / 'client-output.txt').write_text(result.stdout + result.stderr, encoding='utf-8')
                self.assertEqual(len(reports), 1, result.stdout + result.stderr)
                events = [json.loads(line) for line in reports[0].read_text(encoding='utf-8').splitlines()]
                summary = events[-1]['data']
                analyzed = analyze_file(reports[0])
                self.assertEqual(analyzed['task'], 'bootstrap')
                self.assertFalse(analyzed['issues'], analyzed['issues'])
                audited = audit(reports[0])
                self.assertEqual(audited['status'], 'PASS', audited)
                good = scenario in ('new', 'fast_completion', 'existing', 'existing_custom', 'queued', 'queued_other_producer',
                                    'budget_recovers', 'busy', 'partial_builder', 'final_builder_replaced')
                self.assertEqual(summary['outcome'], 'PASS' if good else 'FAIL', result.stdout + result.stderr)
                self.assertEqual(result.returncode, 1 if match or not good else 0, result.stdout + result.stderr)
                self.assertFalse(f['errors'], f['errors'])
                no_purchase = scenario in ('existing', 'existing_custom', 'queued', 'queued_other_producer',
                                          'insufficient', 'no_menu', 'plan_session', 'guard', 'network', 'replay', 'commands_disabled')
                self.assertEqual(len(f['orders']), 0 if no_purchase else 1)
                if good:
                    self.assertGreaterEqual(f['preflights'], 2, 'fresh preflight missing after confirmation')
                    self.assertEqual(summary['commands'], 0 if no_purchase else 1)
                    expected = 'EXISTING_BUILDER' if scenario in ('existing', 'existing_custom') else 'EXISTING_BUILDER_QUEUE' if pending else 'AUTOMATIC_NATIVE_PRODUCTION'
                    self.assertEqual(summary['bootstrapMode'], expected)
                    if expected != 'EXISTING_BUILDER':
                        self.assertEqual(summary['queueObserved'], scenario != 'fast_completion')
                        self.assertEqual(sum(e['event'] == 'bootstrap_builder_completed' for e in events), 1)
                        self.assertEqual(summary['completionEvidence'], 'NEW_READY_BUILDER_AFTER_ACCEPTED_ORDER' if scenario == 'fast_completion' else 'RESELECTED_READY_OWN_BUILDER' if scenario == 'final_builder_replaced' else 'NEW_OWN_UNIT_AND_NATIVE_QUEUE_CYCLE')
                        if scenario == 'final_builder_replaced':
                            self.assertEqual(summary['builderId'], 8)
                            self.assertTrue(any(e['event'] == 'bootstrap_builder_reselected' for e in events))
                elif scenario in ('session', 'player', 'producer_lost', 'plan_session', 'unknown_receipt', 'wrong_type', 'final_session', 'final_player'):
                    needle = dict(session='Session changed', player='Local player changed', producer_lost='producer lost',
                                  plan_session='Session changed', unknown_receipt='HTTP 503', wrong_type='type changed',
                                  final_session='Session changed', final_player='Local player changed')[scenario]
                    self.assertIn(needle, summary['reason'])
                elif scenario in ('insufficient', 'timeout', 'paused'):
                    self.assertIn('timeout', summary['reason'])
                elif scenario == 'cancelled': self.assertIn('queue ended without', summary['reason'])
                elif scenario == 'ambiguous': self.assertIn('Ambiguous', summary['reason'])
                elif scenario == 'final_builder_lost': self.assertIn('No completed own builder remains', summary['reason'])
                if match:
                    self.assertEqual(f['health_calls'], 2)
                    health_positions = [i for i, path in enumerate(f['calls']) if path == '/health']
                    self.assertLess(f['calls'].index('/command/produce-builder'), health_positions[1])
        finally:
            server.shutdown(); server.server_close(); worker.join()

    def test_no_builder_single_native_queue_then_completed_unit(self): self.case('new')
    def test_completed_between_polls_uses_explicit_weaker_evidence(self): self.case('fast_completion')
    def test_existing_builder_no_production_command(self): self.case('existing')
    def test_resolved_custom_builder_no_production_command(self): self.case('existing_custom')
    def test_existing_queue_no_duplicate_command(self): self.case('queued')
    def test_other_producer_queue_no_duplicate_command(self): self.case('queued_other_producer')
    def test_insufficient_credits_bounded_no_purchase(self): self.case('insufficient')
    def test_credit_recovery_then_one_purchase(self): self.case('budget_recovers')
    def test_busy_producer_waits_without_extra_order(self): self.case('busy')
    def test_incomplete_builder_not_accepted(self): self.case('partial_builder')
    def test_no_native_menu_explicit_failure(self): self.case('no_menu')
    def test_producer_loss_stops_without_retry(self): self.case('producer_lost')
    def test_session_change_stops_without_retry(self): self.case('session')
    def test_player_change_stops_without_retry(self): self.case('player')
    def test_plan_session_change_zero_orders(self): self.case('plan_session')
    def test_unknown_receipt_not_retried(self): self.case('unknown_receipt')
    def test_wrong_product_type_not_retried(self): self.case('wrong_type')
    def test_queue_cancelled_without_product_explicit_failure(self): self.case('cancelled')
    def test_production_timeout_one_order(self): self.case('timeout')
    def test_paused_simulation_wall_budget(self): self.case('paused')
    def test_preflight_guard_zero_orders(self): self.case('guard')
    def test_network_zero_orders(self): self.case('network')
    def test_replay_zero_orders(self): self.case('replay')
    def test_disabled_command_bridge_zero_orders(self): self.case('commands_disabled')
    def test_multiple_new_builders_no_unproven_attribution(self): self.case('ambiguous')
    def test_final_ready_builder_loss_blocks_following_stage(self): self.case('final_builder_lost')
    def test_final_other_ready_builder_reselected_with_role_evidence(self): self.case('final_builder_replaced')
    def test_final_session_change_blocks_following_stage(self): self.case('final_session')
    def test_final_player_change_blocks_following_stage(self): self.case('final_player')
    def test_match_idle_factory_without_builder_bootstraps_first(self): self.case('new', match=True)


class OperationsEvidenceTests(unittest.TestCase):
    def report(self, events, bootstrap=False):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / ('bootstrap-audit.jsonl' if bootstrap else 'operations.jsonl')
            rows = [dict(wallTimeMs=i + 1, event=name, data=data) for i, (name, data) in enumerate(events)]
            path.write_text(''.join(json.dumps(row) + '\n' for row in rows), encoding='utf-8')
            before = path.read_bytes()
            result, analyzed = audit(path), analyze_file(path)
            self.assertEqual(path.read_bytes(), before, 'read-only audit changed its input')
            return result, analyzed

    def worker(self, uid, kind='heavyTank'):
        return dict(id=uid, type=kind, hp=100, dead=False, buildProgress=1,
                    mobile=kind == 'heavyTank', canAttack=kind == 'heavyTank', productionQueue=0)

    def test_bootstrap_claim_requires_live_correct_role_and_ready_preflight(self):
        for mutation in ('none', 'dead_hp', 'wrong_role', 'denied_preflight', 'queue_overclaim'):
            unit = self.worker(7, 'builder')
            if mutation == 'dead_hp': unit['hp'] = 0
            if mutation == 'wrong_role': unit['type'] = 'heavyTank'
            summary = dict(outcome='PASS', commands=0, automaticProductionCommands=0, observations=1,
                           bootstrapMode='EXISTING_BUILDER', builderId=7, resolvedBuilderType='builder',
                           queueObserved=False, completionEvidence='EXISTING_READY_BUILDER')
            if mutation == 'queue_overclaim': summary['completionEvidence'] = 'NEW_OWN_UNIT_AND_NATIVE_QUEUE_CYCLE'
            rows = [('health', dict(version='0.07-alpha1')),
                    ('observation', dict(ownUnits=[unit])),
                    ('bootstrap_existing_builder', dict(builderId=7)),
                    ('preflight', dict(commandsAllowed=mutation != 'denied_preflight', recommendation='RUN_DEVELOP')),
                    ('bootstrap_ready', dict(builderId=7, recommendation='RUN_DEVELOP')),
                    ('summary', summary)]
            result, analyzed = self.report(rows, True)
            self.assertEqual(analyzed['task'], 'bootstrap')
            self.assertEqual(bool(analyzed['issues']), mutation != 'none', analyzed)
            if mutation != 'queue_overclaim': self.assertEqual(result['status'], 'PASS' if mutation == 'none' else 'FAIL', result)

    def army_rows(self, duplicate=False, outsider=False):
        actors = list(range(7, 13)) + ([13] if outsider else [])
        old = list(range(1, 7)) + ([7] if duplicate else [])
        return [('observation', dict(gameTimeMs=1000, ownUnits=[self.worker(i) for i in range(1, 14)])),
                ('local_army_membership', dict(cohortId=1, unitIds=list(range(1, 13)), reason='LOCAL_FORMATION')),
                # A split emits the new group before the source's final membership. Audit the batch.
                ('local_army_membership', dict(cohortId=2, unitIds=list(range(7, 13)), reason='SEPARATED_FORMATION')),
                ('local_army_membership', dict(cohortId=1, unitIds=old, reason='MEMBERS_TRANSFERRED')),
                ('action', dict(path='/command/attack-move?unitIds=' + ','.join(map(str, actors)) + '&requestId=one')),
                ('command_result', dict(status='queued', requestId='one')),
                ('local_army_order', dict(cohortId=2, unitIds=actors, receiptStatus='queued', requestId='one')),
                ('summary', dict(outcome='PARTIAL'))]

    def test_membership_transfer_batch_preserves_unique_actors(self):
        result, _ = self.report(self.army_rows())
        self.assertEqual(result['status'], 'PASS', result)
        self.assertEqual(result['metrics']['maximumActiveCohorts'], 2)

    def test_duplicate_cohort_and_unowned_command_actor_are_detected(self):
        result, _ = self.report(self.army_rows(True, True))
        self.assertIn('COHORT_MEMBER_DUPLICATED', result['violationCounts'])
        self.assertIn('LOCAL_ORDER_ACTORS_OUTSIDE_COHORT', result['violationCounts'])

    def quality_rows(self, lose_ghost=False, over_budget=False):
        own = [self.worker(i) for i in range(100, 124)] + [self.worker(5, 'landFactory')]
        data = dict(selected=True, reason='SURPLUS_SIEGE_ROLE', producerId=5, product='heavyArtillery', actionId='artillery',
                    nativeCost=4700, ordinaryUnitNativeCost=800, credits=6000 if over_budget else 20000,
                    allReserved=500, requiredCredits=6800, ordinaryForce=24,
                    artilleryObserved=0, artilleryPending=0, artilleryQuota=3, committedArmed=24,
                    armyTarget=36, hardSafetyCap=128, homeEmergency=False, gameTimeMs=1000,
                    receipt=dict(status='queued', requestId='one'))
        return [('observation', dict(gameTimeMs=1000, ownUnits=own)),
                ('production_menu', dict(factories=[dict(id=5, actions=[dict(type='heavyArtillery', actionId='artillery', cost=4700, affordable=True)])])),
                ('action', dict(path='/command/queue?unitId=5&actionId=artillery&requestId=one')),
                ('command_result', dict(status='queued', requestId='one')),
                ('surplus_role_ordered', data),
                ('spend', dict(itemType='heavyArtillery', cost=4700, producerOrBuilderId=5)),
                ('observation', dict(gameTimeMs=3000, ownUnits=own)),
                ('surplus_spending_evaluated', dict(selected=False, artilleryPending=1, committedArmed=24 if lose_ghost else 25)),
                ('summary', dict(outcome='PARTIAL'))]

    def test_native_quote_and_paid_empty_queue_gap_are_audited(self):
        result, _ = self.report(self.quality_rows())
        self.assertEqual(result['status'], 'PASS', result)
        self.assertEqual(result['metrics']['maximumPaidGhostSlots'], 1)
        self.assertEqual(result['coverage']['surplusProductObservations'], 'NOT_TRIGGERED')
        for kwargs, reason in ((dict(lose_ghost=True), 'PAID_GHOST_ARMY_SLOT_MISSING'),
                               (dict(over_budget=True), 'SURPLUS_NATIVE_BUDGET_BREACHED')):
            result, _ = self.report(self.quality_rows(**kwargs))
            self.assertIn(reason, result['violationCounts'])

    def test_missing_terminal_summary_cannot_pass_audit(self):
        result, _ = self.report([('observation', dict(ownUnits=[]))])
        self.assertEqual(result['status'], 'FAIL')
        self.assertTrue(result['integrityIssues'])


if __name__ == '__main__': unittest.main()
