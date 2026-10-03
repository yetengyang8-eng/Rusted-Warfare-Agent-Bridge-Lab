"""Real BattleClient HTTP timelines for bounded local main-army goals.

Legal own/enemy observations are fixtures. Frontier motion is an execution fixture;
target-selection tests are open loop and do not claim damage, victory or native benefit.
"""
import http.server
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import threading
import unittest
from urllib.parse import parse_qs, urlsplit
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2]/'tools'))
from analyze_reports import analyze_file

JAR = str(pathlib.Path(sys.argv.pop(1)).resolve())
CATALOG_SHA = '263cdaaa3e923e8307c37f059e50bee529b8ecd7c3e215937ce2adf615a0e236'


class LocalArmyTests(unittest.TestCase):
    def run_case(self, scenario):
        tick = [0]
        orders, attempts, motions, delayed = [], [], {}, []
        frontier_counts = {0: 0, 1: 0}
        rejected = [False]
        factory_queued_at = {}

        def unit(uid, kind, x, y):
            building = kind == 'commandCenter' or kind.startswith('extractor') or kind == 'landFactory'
            u = dict(id=uid, type=kind, x=x, y=y, hp=1000, maxHp=1000,
                     dead=False, buildProgress=1, mobile=not building,
                     canAttack=kind in ('heavyTank', 'c_tank', 'combatEngineer'),
                     building=building, techLevel=1, productionQueue=0, orderType=None)
            u.update(motions.get(uid, {}))
            return u

        def own():
            units = [unit(3, 'commandCenter', 100, 100)]
            units += [unit(20+i, 'heavyTank', 1000+i*5, 1000) for i in range(8)]
            if scenario != 'single_group':
                units += [unit(100+i, 'c_tank', 5000+i*5, 1000) for i in range(8)]
            if scenario == 'legality_and_specialist':
                units.append(unit(9, 'combatEngineer', 1000, 1000))
            if scenario.startswith('raid'):
                units.append(unit(5, 'extractor', 600, 1000))
                units.append(unit(9, 'combatEngineer', 620, 1000))
            if scenario == 'production_starvation':
                for uid in (6, 7):
                    factory = unit(uid, 'landFactory', 100, 200+uid*5)
                    factory['productionQueue'] = int(tick[0] == factory_queued_at.get(uid, -100)+1)
                    units.append(factory)
                for u in units:
                    if u['mobile']:
                        u.update(orderType=None, orderX=None, orderY=None)
            return units

        def enemy(uid, x, domain='SURFACE'):
            return dict(id=uid, type='helicopter' if domain == 'AIR' else 'tank',
                        x=x, y=4000 if scenario == 'remote_contact' else 1000, hp=1000, maxHp=1000, dead=False,
                        building=False, canAttack=True, targetDomain=domain,
                        touchingWater=domain == 'SUBMERGED',
                        domainObservedAtGameTimeMs=tick[0]*1000,
                        lastSeenGameTimeMs=tick[0]*1000)

        def enemies():
            if scenario == 'frontier':
                return []
            if scenario == 'remote_contact':
                return [enemy(700, 1000), enemy(800, 5000)]
            if scenario == 'stalled_fronts':
                return [enemy(700, 1100), enemy(800, 5100)]
            if scenario.startswith('raid'):
                contacts = [enemy(700, 1350), enemy(800, 5350)]
                if scenario != 'raid_loss' or tick[0] < 7:
                    domain = 'UNKNOWN' if scenario == 'raid_unknown' else 'SUBMERGED' if scenario == 'raid_incompatible' else 'SURFACE'
                    raid = enemy(970, 650, domain)
                    raid.update(hp=100, maxHp=100)
                    contacts.append(raid)
                return contacts
            contacts = [enemy(700, 1350, 'UNKNOWN' if scenario == 'legality_and_specialist' else 'SURFACE')]
            if scenario != 'single_group':
                contacts.append(enemy(800, 5350))
            if scenario == 'legality_and_specialist':
                contacts.append(enemy(900, 5050, 'SUBMERGED'))
            if scenario == 'two_fronts' and tick[0] >= 7:
                # Both a nearer local contact and a centrally revealed distant contact appear.
                # Neither should pull an already assigned local group off its current target.
                contacts += [enemy(702, 1100), enemy(950, 3000)]
            return contacts

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, body, status=200):
                payload = json.dumps(body).encode('utf-8')
                self.send_response(status)
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def do_GET(self):
                parsed = urlsplit(self.path)
                query = parse_qs(parsed.query)
                now = tick[0]*1000
                if parsed.path == '/health':
                    return self.reply(dict(status='ok', version='0.07-alpha1', strategyContractVersion=1))
                if parsed.path == '/state':
                    tick[0] += 1
                    now = tick[0]*1000
                    for finish, ids, x, y in list(delayed):
                        if tick[0] >= finish:
                            for uid in ids:
                                motions[uid] = dict(x=x, y=y, orderType=None)
                            delayed.remove((finish, ids, x, y))
                    terminal = tick[0] >= (60 if scenario == 'production_starvation' else 18)
                    return self.reply(dict(status='running', sessionId='s', frame=tick[0],
                        gameTimeMs=now, networked=False, replay=False,
                        player=dict(teamId=0, credits=100000 if scenario == 'production_starvation' else 0),
                        map=dict(width=10000, height=10000, tilesWide=500, tilesHigh=500,
                                 tileWidth=20, tileHeight=20),
                        match=dict(outcome='DEFEAT' if terminal else 'ONGOING',
                                   nativeDefeat=terminal, nativeVictory=False,
                                   source='native_result_screen'), ownUnits=own()))
                if parsed.path == '/combat/observe':
                    return self.reply(dict(status='observed', sessionId='s', gameTimeMs=now,
                        catalogSha256=CATALOG_SHA, catalogGameJarMatched=True,
                        visibleEnemies=enemies(), rememberedEnemies=enemies(), enemyIntel=[]))
                if parsed.path == '/combat/engagement':
                    # All supplied compatible main actors can approach; no capability need is opened.
                    actors = [dict(unitId=int(uid), status='APPROACH_PATH_KNOWN',
                                   compatibility='COMPATIBLE', lastKnownPositionApproachStatus='APPROACH_PATH_KNOWN',
                                   approachX=1300, approachY=1000)
                              for uid in query['unitIds'][0].split(',')]
                    if scenario == 'raid_native_unknown' and int(query['targetId'][0]) == 970:
                        for a in actors:
                            a.update(status='UNKNOWN', compatibility='UNKNOWN')
                    target = next(e for e in enemies() if e['id'] == int(query['targetId'][0]))
                    return self.reply(dict(status='observed', sessionId='s', gameTimeMs=now,
                        targetId=int(query['targetId'][0]), targetVisible=True,
                        targetX=target['x'], targetY=target['y'],
                        targetObservedAtGameTimeMs=now, actors=actors))
                if parsed.path == '/combat/production':
                    if scenario == 'production_starvation':
                        return self.reply(dict(status='observed', sessionId='s', factories=[
                            dict(id=uid, type='landFactory', tier=2,
                                 queue=int(tick[0] == factory_queued_at.get(uid, -100)+1), actions=[
                                     dict(actionId='tank', type='c_tank', affordable=True, cost=350)]) for uid in (6, 7)]))
                    return self.reply(dict(status='observed', sessionId='s', factories=[]))
                if parsed.path == '/scout/observe':
                    return self.reply(dict(status='observed', sessionId='s', gameTimeMs=now,
                        frame=tick[0], newlyObservedTiles=0, resources=[],
                        visibleThreats=[], rememberedThreats=[]))
                if parsed.path == '/scout/plan' and scenario == 'frontier':
                    uid = int(query['unitId'][0])
                    front = 0 if uid < 100 else 1
                    frontier_counts[front] += 1
                    x = 1000+front*4000+frontier_counts[front]*600
                    return self.reply(dict(status='planned', sessionId='s', targetX=x, pathKnown=True, anchorUnitId=uid,
                        targetY=1300, targetTile=10000+front*100+frontier_counts[front]))
                return self.reply(dict(status='no_frontier', sessionId='s'))

            def do_POST(self):
                parsed = urlsplit(self.path)
                query = parse_qs(parsed.query)
                ids = [int(uid) for uid in query.get('unitIds', query.get('unitId', ['0']))[0].split(',')]
                order = dict(path=parsed.path, unitIds=ids, tick=tick[0], gameTimeMs=tick[0]*1000,
                             x=float(query.get('x', ['0'])[0]), y=float(query.get('y', ['0'])[0]))
                attempts.append(order)
                if scenario in ('rejected_order', 'raid_rejected') and not rejected[0] and parsed.path == '/command/attack-move':
                    rejected[0] = True
                    return self.reply(dict(status='rejected', reason='E2_NATIVE_TRANSIENT'), 409)
                orders.append(order)
                if parsed.path == '/command/queue':
                    factory_queued_at[ids[0]] = tick[0]
                if parsed.path == '/command/attack-move':
                    for uid in ids:
                        motions[uid] = dict(orderType='attackMove', orderX=order['x'], orderY=order['y'])
                    if scenario == 'frontier':
                        delayed.append((tick[0]+2, ids, order['x'], order['y']))
                return self.reply(dict(status='queued', sessionId='s', requestId=query['requestId'][0],
                    frame=tick[0], unitId=ids[0], unitIds=ids, targetX=order['x'],
                    targetY=order['y'], orderType='attackMove'))

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as cwd:
                command = ['java', '-Drwagent.pollMs=60', '-Drwagent.reconEnabled=false',
                           '-Drwagent.reachabilitySample=false',
                           '-Drwagent.port='+str(server.server_port), '-cp', JAR,
                           'io.rwagent.client.BattleClient', '120']
                if scenario == 'stalled_fronts':
                    command.insert(1, '-Drwagent.noProgressWindowMs=4000')
                result = subprocess.run(command, cwd=cwd, capture_output=True, timeout=40)
                report = next(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                raw = report.read_bytes()
                rows = [json.loads(line) for line in raw.splitlines()]
                evidence = os.environ.get('RW_LOCAL_ARMY_EVIDENCE_DIR')
                if evidence:
                    dest = pathlib.Path(evidence)/scenario
                    dest.mkdir(parents=True, exist_ok=True)
                    (dest/'battle.jsonl').write_bytes(raw)
                    (dest/'orders.json').write_text(json.dumps(dict(orders=orders, attempts=attempts), indent=2)+'\n', encoding='utf-8')
                    (dest/'process.txt').write_text(result.stdout.decode(errors='replace')+result.stderr.decode(errors='replace'), encoding='utf-8')
                self.assertEqual(result.returncode, 0, result.stdout.decode(errors='replace')+result.stderr.decode(errors='replace'))
                analysis = analyze_file(report)
                self.assertEqual(analysis['issues'], [], analysis['issues'])
                return orders, attempts, rows
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def assert_separate_orders(self, orders):
        left, right = set(range(20, 28)), set(range(100, 108))
        attacks = [o for o in orders if o['path'] == '/command/attack-move']
        self.assertTrue(attacks)
        for order in attacks:
            assigned = set(order['unitIds'])
            self.assertTrue(assigned <= left or assigned <= right, order)
            self.assertLessEqual(len(assigned), 48)
        for previous, order in zip(orders, orders[1:]):
            self.assertGreaterEqual(order['gameTimeMs']-previous['gameTimeMs'], 1000)
        return attacks

    def test_two_fronts_keep_independent_targets_and_ignore_remote_pull(self):
        orders, _, rows = self.run_case('two_fronts')
        attacks = self.assert_separate_orders(orders)
        self.assertTrue([o for o in attacks if set(o['unitIds']) == set(range(20, 28)) and o['x'] == 1350])
        self.assertTrue([o for o in attacks if set(o['unitIds']) == set(range(100, 108)) and o['x'] == 5350])
        self.assertFalse([o for o in attacks if o['x'] in (1100, 3000)], 'new sightings cannot replace a still-local accepted target')
        self.assertEqual(len({r['data']['cohortId'] for r in rows if r['event'] == 'local_army_order'}), 2)

    def test_frontier_motion_advances_both_local_groups_without_enemies(self):
        orders, _, rows = self.run_case('frontier')
        self.assert_separate_orders(orders)
        arrivals = [r['data'] for r in rows if r['event'] == 'local_army_frontier_arrived']
        self.assertEqual(len({a['cohortId'] for a in arrivals}), 2)
        goals = {}
        for row in rows:
            if row['event'] == 'local_army_order':
                goals.setdefault(row['data']['cohortId'], set()).add(row['data']['targetX'])
        self.assertTrue(all(len(xs) >= 2 for xs in goals.values()), goals)

    def test_unknown_and_incompatible_semantics_exclude_specialist(self):
        orders, _, rows = self.run_case('legality_and_specialist')
        attacks = self.assert_separate_orders(orders)
        self.assertFalse([o for o in attacks if 9 in o['unitIds']], 'engineer ownership must stay outside every main cohort')
        self.assertTrue([r for r in rows if r['event'] == 'target_guard' and r['data']['targetId'] == 700 and r['data']['status'] == 'UNKNOWN'])
        self.assertTrue([o for o in attacks if o['x'] == 1350], 'UNKNOWN remains usable under the existing guard')
        rejected = [r['data'] for r in rows if r['event'] == 'target_suppression_started' and r['data']['targetId'] == 900]
        self.assertTrue(rejected, 'the existing negative-evidence gate remains authoritative')
        self.assertTrue(all(a['status'] == 'INCOMPATIBLE' for a in rejected[0]['actorDecisions']))
        self.assertFalse([o for o in attacks if o['x'] == 5050], 'submerged incompatibility must remain a rejection')

    def test_native_rejection_does_not_advance_cohort_cooldown_or_rotation(self):
        orders, attempts, rows = self.run_case('rejected_order')
        self.assert_separate_orders(orders)
        self.assertGreater(len(attempts), len(orders))
        self.assertEqual(attempts[0]['unitIds'], orders[0]['unitIds'])
        self.assertEqual(attempts[0]['x'], orders[0]['x'])
        self.assertEqual(orders[0]['gameTimeMs']-attempts[0]['gameTimeMs'], 1000)
        self.assertFalse([r for r in rows if r['event'] == 'local_army_order' and r['data']['gameTimeMs'] == attempts[0]['gameTimeMs']])

    def test_single_group_preserves_existing_main_tactics(self):
        orders, _, rows = self.run_case('single_group')
        self.assertTrue([o for o in orders if o['path'] == '/command/attack-move' and o['x'] == 1350])
        self.assertFalse([r for r in rows if r['event'] == 'local_army_order'])

    def test_current_distant_contacts_advance_when_no_frontier_remains(self):
        orders, _, rows = self.run_case('remote_contact')
        attacks = self.assert_separate_orders(orders)
        self.assertTrue([o for o in attacks if o['x'] == 1000 and o['y'] == 4000 and set(o['unitIds']) == set(range(20, 28))])
        self.assertTrue([o for o in attacks if o['x'] == 5000 and o['y'] == 4000 and set(o['unitIds']) == set(range(100, 108))])
        self.assertEqual({r['data']['cohortId'] for r in rows if r['event'] == 'local_army_order' and r['data']['reason'] == 'REMOTE_VISIBLE_CONTACT'}, {1, 2})

    def test_progress_observation_is_not_starved_by_other_cohort_orders(self):
        orders, _, rows = self.run_case('stalled_fronts')
        self.assert_separate_orders(orders)
        stalled = [r['data'] for r in rows if r['event'] == 'target_no_progress']
        self.assertEqual({r['targetId'] for r in stalled}, {700, 800})
        for event in stalled:
            x = 1100 if event['targetId'] == 700 else 5100
            self.assertFalse([o for o in orders if o['x'] == x and o['gameTimeMs'] >= event['gameTimeMs']], 'the existing no-progress cooldown stops further orders')

    def test_near_mine_raid_uses_nearest_small_detachment_and_preserves_main(self):
        orders, _, rows = self.run_case('raid')
        start = next(r['data'] for r in rows if r['event'] == 'local_crisis_started')
        self.assertEqual(start['unitIds'], [20, 21])
        self.assertEqual(start['visibleThreats'], 1)
        self.assertGreaterEqual(start['mainRemaining'], 6)
        crisis_orders = [o for o in orders if o['x'] == 650]
        self.assertTrue(crisis_orders)
        self.assertTrue(all(o['unitIds'] == [20, 21] for o in crisis_orders))
        self.assertFalse([o for o in orders if 9 in o['unitIds']], 'real specialist owner is excluded')
        for r in rows:
            if r['event'] == 'local_army_order':
                self.assertFalse({20, 21} & set(r['data']['unitIds']), 'leased responders cannot also receive main orders')
        acquired = [r['data'] for r in rows if r['event'] == 'task_ownership_acquired' and r['data'].get('role') == 'LOCAL_CRISIS']
        released = [r['data'] for r in rows if r['event'] == 'task_ownership_released' and r['data'].get('role') == 'LOCAL_CRISIS']
        self.assertEqual(len({r['taskId'] for r in acquired}), len(acquired))
        self.assertEqual({(r['taskId'], r['unitId']) for r in acquired}, {(r['taskId'], r['unitId']) for r in released})
        self.assertTrue([o for o in orders if o['x'] == 1350 and set(o['unitIds']) == set(range(22, 28))])

    def test_unknown_catalog_incompatible_and_native_unknown_cannot_promise_crisis_response(self):
        for scenario in ('raid_unknown', 'raid_incompatible', 'raid_native_unknown'):
            with self.subTest(scenario=scenario):
                _, _, rows = self.run_case(scenario)
                self.assertFalse([r for r in rows if r['event'] == 'local_crisis_started'])

    def test_contact_loss_returns_and_releases_without_claiming_kill(self):
        _, _, rows = self.run_case('raid_loss')
        returns = [r['data'] for r in rows if r['event'] == 'local_crisis_return']
        self.assertEqual(returns[0]['reason'], 'CONTACT_LOST')
        self.assertEqual(returns[0]['contactSemantics'], 'LOST_VISIBILITY_IS_UNKNOWN_NOT_KILL_PROOF')
        self.assertTrue([r for r in rows if r['event'] == 'local_crisis_order' and r['data']['phase'] == 'RETURN'])
        self.assertTrue([r for r in rows if r['event'] == 'local_crisis_finished'])

    def test_rejected_crisis_order_does_not_advance_accepted_cadence(self):
        orders, attempts, rows = self.run_case('raid_rejected')
        self.assertEqual(attempts[0]['unitIds'], [20, 21])
        self.assertEqual(orders[0]['unitIds'], [20, 21])
        self.assertEqual(orders[0]['gameTimeMs']-attempts[0]['gameTimeMs'], 1000)
        self.assertFalse([r for r in rows if r['event'] == 'local_crisis_order' and r['data']['gameTimeMs'] == attempts[0]['gameTimeMs']])

    def test_real_no_order_cohorts_receive_bounded_slots_during_continuous_production(self):
        orders, _, rows = self.run_case('production_starvation')
        queues = [o for o in orders if o['path'] == '/command/queue']
        self.assertGreater(len(queues), 10, 'fixture keeps ordinary production continuously ready')
        fair = [r['data'] for r in rows if r['event'] == 'local_army_fairness_slot']
        self.assertEqual({r['cohortId'] for r in fair}, {1, 2})
        for first, second in zip(fair, fair[1:]):
            self.assertGreaterEqual(second['gameTimeMs']-first['gameTimeMs'], 8000)
        diagnostics = [r['data'] for r in rows if r['event'] == 'local_army_diagnostic']
        self.assertTrue([r for r in diagnostics if r['gatePath'] == '/command/queue' and not r['globalGateReady']])
        self.assertTrue(all(r['unitsWithNoOrder'] == r['members'] for r in diagnostics))
        self.assertTrue(all('lastAcceptedAgeMs' in r and 'legalTarget' in r and 'frontierAvailable' in r
                            and 'cooldownRemainingMs' in r for r in diagnostics))
        for a, b in zip(orders, orders[1:]):
            self.assertGreaterEqual(b['gameTimeMs']-a['gameTimeMs'], 1000)


if __name__ == '__main__':
    unittest.main()
