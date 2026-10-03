"""E2: exercise target-domain filtering through the real BattleClient and command path."""
import http.server
import json
import pathlib
import subprocess
import sys
import tempfile
import threading
import unittest
from urllib.parse import parse_qs, urlsplit
from target_policy_fixture import run_frames

JAR = str(pathlib.Path(sys.argv.pop(1)).resolve())
CATALOG_SHA = '263cdaaa3e923e8307c37f059e50bee529b8ecd7c3e215937ce2adf615a0e236'


class TargetCompatibilityIntegration(unittest.TestCase):
    def run_case(self, attackers, domain, *, water=None, stale=False,
                 session_change_at=None):
        step = [0]
        orders = []
        ids = [20+i for i in range(len(attackers))]

        def unit(uid, kind):
            building = kind == 'commandCenter'
            return dict(id=uid, type=kind, x=900, y=1000, hp=1000, maxHp=1000,
                        dead=False, buildProgress=1, mobile=not building,
                        canAttack=not building, building=building, techLevel=1,
                        productionQueue=0, orderType=None)

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, body):
                data = json.dumps(body).encode('utf-8')
                self.send_response(200)
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_POST(self):
                path = urlsplit(self.path)
                query = parse_qs(path.query)
                if path.path == '/command/attack-move':
                    assigned = [int(v) for v in query['unitIds'][0].split(',')]
                    orders.append(dict(unitIds=assigned, x=float(query['x'][0]),
                                       y=float(query['y'][0]), gameTimeMs=step[0]*2000))
                    self.reply(dict(status='queued', sessionId='s',
                                    requestId=query['requestId'][0], frame=step[0],
                                    unitId=assigned[0], unitIds=assigned,
                                    targetX=float(query['x'][0]), targetY=float(query['y'][0]),
                                    orderType='attackMove'))
                else:
                    self.reply(dict(status='queued', sessionId='s',
                                    requestId=query['requestId'][0], frame=step[0], unitId=4))

            def do_GET(self):
                path = urlsplit(self.path).path
                now = step[0]*2000
                if path == '/health':
                    return self.reply(dict(status='ok', version='0.07-alpha1'))
                if path == '/state':
                    step[0] += 1
                    now = step[0]*2000
                    end = step[0] >= 7
                    own = [unit(3, 'commandCenter')]
                    own[0]['x'] = 100
                    own += [unit(uid, kind) for uid, kind in zip(ids, attackers)]
                    current_session = ('s2' if session_change_at is not None
                                       and step[0] >= session_change_at else 's')
                    return self.reply(dict(status='running', sessionId=current_session, frame=step[0],
                        gameTimeMs=now, networked=False, replay=False, player={'credits':0},
                        map={'width':2200, 'height':2200, 'tilesWide':110, 'tilesHigh':110,
                             'tileWidth':20, 'tileHeight':20},
                        match=dict(outcome='DEFEAT' if end else 'ONGOING', nativeDefeat=end,
                                   nativeVictory=False, source='native_result_screen'), ownUnits=own))
                if path == '/combat/production':
                    return self.reply(dict(status='observed', sessionId='s', factories=[]))
                if path == '/combat/observe':
                    seen_at = 2000 if stale else now
                    enemy = dict(id=74, type='aircraft', x=1000, y=1000, hp=1000,
                                 maxHp=1000, dead=False, building=False, canAttack=True,
                                 lastSeenGameTimeMs=seen_at, targetDomain=domain,
                                 touchingWater=water, domainObservedAtGameTimeMs=seen_at)
                    return self.reply(dict(status='observed', sessionId='s', gameTimeMs=now,
                        catalogSha256=CATALOG_SHA, catalogGameJarMatched=True,
                        visibleEnemies=[] if stale else [enemy], rememberedEnemies=[enemy]))
                if path == '/scout/observe':
                    return self.reply(dict(status='observed', sessionId='s', frame=step[0],
                                           gameTimeMs=now, newlyObservedTiles=0,
                                           resources=[], visibleThreats=[], rememberedThreats=[]))
                return self.reply(dict(status='no_frontier', sessionId='s'))

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as cwd:
                command = ['java', '-Drwagent.pollMs=60', '-Drwagent.reconEnabled=false',
                           '-Drwagent.reachabilitySample=false',
                           '-Drwagent.port='+str(server.server_port), '-cp', JAR,
                           'io.rwagent.client.BattleClient', '120']
                run = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, timeout=20)
                self.assertEqual(run.returncode, 1 if session_change_at is not None else 0,
                                 run.stdout.decode('utf-8', errors='replace'))
                report = next(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                rows = [json.loads(line) for line in report.read_text(encoding='utf-8').splitlines()]
                if session_change_at is None:
                    self.assertEqual(rows[-1]['data']['matchOutcome'], 'DEFEAT')
                else:
                    self.assertEqual(rows[-1]['data']['outcome'], 'FAIL')
                    self.assertIn('Session changed', rows[-1]['data']['reason'])
                return orders, [r['data'] for r in rows if r['event'] == 'target_guard']
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def assert_guard(self, events, status):
        matching = [event for event in events if event.get('targetId') == 74]
        self.assertTrue(matching, events)
        event = matching[0]
        self.assertEqual(event['status'], status)
        self.assertTrue(event['reason'])
        self.assertTrue(event['sourceId'])
        self.assertEqual(event['catalogSha256'], CATALOG_SHA)
        self.assertIn('observedAtGameTimeMs', event)
        return event

    def test_tanks_reject_visible_air_and_submerged(self):
        for domain in ('AIR', 'SUBMERGED'):
            with self.subTest(domain=domain):
                orders, events = self.run_case(['tank']*6, domain)
                guard = self.assert_guard(events, 'INCOMPATIBLE')
                self.assertFalse(guard['assignedUnitIds'])
                self.assertFalse([o for o in orders if (o['x'], o['y']) == (1000, 1000)])

    def test_heavy_tank_and_scout_keep_mixed_air_squad_active(self):
        attackers = ['tank']*5 + ['heavyTank', 'scout', 'builder']
        orders, events = self.run_case(attackers, 'AIR')
        guard = self.assert_guard(events, 'COMPATIBLE')
        self.assertEqual(set(guard['candidateUnitIds']), set(range(20, 28)))
        self.assertEqual(set(guard['assignedUnitIds']), {25, 26})
        aimed = [o for o in orders if (o['x'], o['y']) == (1000, 1000)]
        self.assertTrue(aimed)
        self.assertEqual(set(aimed[0]['unitIds']), {25, 26})

    def test_tanks_keep_surface_water_target(self):
        orders, events = self.run_case(['tank']*6, 'SURFACE', water=True)
        guard = self.assert_guard(events, 'COMPATIBLE')
        self.assertEqual(set(guard['assignedUnitIds']), set(range(20, 26)))
        self.assertTrue([o for o in orders if set(o['unitIds']) == set(range(20, 26))
                         and (o['x'], o['y']) == (1000, 1000)])

    def test_missing_dynamic_domain_degrades_without_deadlock(self):
        orders, events = self.run_case(['tank']*6, 'UNKNOWN')
        guard = self.assert_guard(events, 'UNKNOWN')
        self.assertEqual(set(guard['assignedUnitIds']), set(range(20, 26)))
        self.assertTrue([o for o in orders if (o['x'], o['y']) == (1000, 1000)])

    def test_stale_air_domain_cannot_be_reused_as_current_state(self):
        orders, events = self.run_case(['tank']*6, 'AIR', stale=True)
        guard = self.assert_guard(events, 'UNKNOWN')
        self.assertEqual(guard['observedAtGameTimeMs'], 2000)
        self.assertTrue([o for o in orders if (o['x'], o['y']) == (1000, 1000)])

    def test_session_change_stops_before_reusing_prior_guard_decision(self):
        orders, events = self.run_case(['tank']*6, 'SURFACE',
                                       session_change_at=3)
        self.assert_guard(events, 'COMPATIBLE')
        self.assertTrue(orders, 'the first session made a real order')
        self.assertTrue(all(o['gameTimeMs'] < 6000 for o in orders),
                        'no command may use the first session after sessionId changes')


class TargetEligibilityTransitions(unittest.TestCase):
    @staticmethod
    def frame(now, *, domain='SUBMERGED', visible=True, seen=None, force=None,
              memory=True, alternative=False, trusted=True):
        own = [dict(id=3, type='commandCenter', x=100, y=100, hp=4000, maxHp=4000,
                    dead=False, buildProgress=1, mobile=False, canAttack=True,
                    building=True, techLevel=1, productionQueue=0, orderType=None)]
        for i, kind in enumerate(force if force is not None else ['tank']*6):
            own.append(dict(id=20+i, type=kind, x=900, y=1000, hp=1000, maxHp=1000,
                dead=False, buildProgress=1, mobile=True, canAttack=True,
                building=False, techLevel=1, productionQueue=0, orderType=None))
        sighting = now if seen is None else seen
        enemy = dict(id=74, type='lightSub', x=1000, y=1000, hp=1000,
            building=False, canAttack=True, lastSeenGameTimeMs=sighting,
            targetDomain=domain, touchingWater=True, domainObservedAtGameTimeMs=sighting,
            domainSourceId='fixture_legal_visibility')
        current = [enemy] if visible else []
        remembered = [enemy] if memory else []
        if alternative:
            other = dict(enemy, id=75, type='tank', targetDomain='SURFACE',
                         x=1900, y=1000, lastSeenGameTimeMs=now, domainObservedAtGameTimeMs=now)
            current.append(other)
            remembered.append(other)
        return dict(state=dict(status='running', sessionId='s', frame=now//10,
            gameTimeMs=now, networked=False, replay=False, player=dict(teamId=0, credits=0),
            ownUnits=own, match=dict(outcome='ONGOING', nativeDefeat=False, nativeVictory=False),
            map=dict(width=2400, height=2400, tilesWide=120, tilesHigh=120, tileWidth=20, tileHeight=20)),
            combat=dict(status='observed', sessionId='s', gameTimeMs=now,
                catalogSha256=CATALOG_SHA, catalogGameJarMatched=trusted,
                visibleEnemies=current, rememberedEnemies=remembered))

    def run_timeline(self, frames):
        return run_frames(JAR, [frames[0]]+frames)

    @staticmethod
    def events(rows, name):
        return [r['data'] for r in rows if r['event'] == name]

    @staticmethod
    def orders_to_sub(orders):
        return [o for o in orders if o['path'] == '/command/attack-move'
                and (o['x'], o['y']) == (1000, 1000)]

    def test_rejection_survives_fog_time_same_type_reinforcements_and_catalog_gap(self):
        orders, rows = self.run_timeline([
            self.frame(10000), self.frame(20000, visible=False, seen=10000, alternative=True),
            self.frame(80000, visible=False, seen=10000, force=['tank']*8),
            self.frame(300000, visible=False, seen=10000, trusted=False)])
        self.assertFalse(self.orders_to_sub(orders))
        self.assertEqual(len(self.events(rows, 'target_suppression_started')), 1)
        self.assertFalse(self.events(rows, 'target_suppression_released'))
        self.assertTrue(any(g['status'] == 'UNKNOWN' for g in self.events(rows, 'target_guard')
                            if g['targetId'] == 74), 'fog still means UNKNOWN, never fabricated current rejection')
        self.assertTrue(any(o['x'] == 1900 for o in orders), 'available visible target remains actionable')

    def test_unchanged_reacquisition_does_not_reopen_target(self):
        orders, rows = self.run_timeline([self.frame(10000),
            self.frame(20000, visible=False, seen=10000), self.frame(30000),
            self.frame(40000, visible=False, seen=30000)])
        self.assertFalse(self.orders_to_sub(orders))
        self.assertEqual(len(self.events(rows, 'target_suppression_started')), 1)
        self.assertFalse(self.events(rows, 'target_suppression_released'))

    def test_new_visible_surface_observation_releases(self):
        orders, rows = self.run_timeline([self.frame(10000),
            self.frame(20000, visible=False, seen=10000), self.frame(30000, domain='SURFACE')])
        self.assertEqual([o['gameTimeMs'] for o in self.orders_to_sub(orders)], [30000])
        release = self.events(rows, 'target_suppression_released')
        self.assertEqual(len(release), 1)
        self.assertEqual(release[0]['previousRejectionObservedAtGameTimeMs'], 10000)
        self.assertEqual(release[0]['observedTargetDomain'], 'SURFACE')

    def test_unknown_observation_and_unknown_new_attacker_do_not_erase_rejection(self):
        orders, rows = self.run_timeline([self.frame(10000),
            self.frame(20000, domain='UNKNOWN'),
            self.frame(30000, force=['tank']*6+['uncataloguedUnit'])])
        self.assertFalse(self.orders_to_sub(orders))
        self.assertFalse(self.events(rows, 'target_suppression_released'))

    def test_new_capable_force_requires_current_target_evidence_then_uses_subset(self):
        force = ['tank']*6+['heavyTank']
        orders, rows = self.run_timeline([self.frame(10000, domain='AIR'),
            self.frame(20000, domain='AIR', visible=False, seen=10000, force=force),
            self.frame(30000, domain='AIR', force=force)])
        aimed = self.orders_to_sub(orders)
        self.assertEqual([o['gameTimeMs'] for o in aimed], [30000])
        self.assertEqual(aimed[0]['unitIds'], [26])
        self.assertEqual(len(self.events(rows, 'target_suppression_released')), 1)

    def test_visible_candidate_outranks_nearer_remembered_candidate(self):
        orders, rows = self.run_timeline([
            self.frame(10000, domain='SURFACE', visible=False, seen=1000, alternative=True)])
        self.assertFalse(self.orders_to_sub(orders))
        self.assertTrue(any(o['x'] == 1900 for o in orders))
        self.assertFalse(self.events(rows, 'target_suppression_started'))

    def test_never_rejected_unknown_target_keeps_fallback(self):
        orders, rows = self.run_timeline([self.frame(10000, visible=False, seen=1000)])
        self.assertTrue(self.orders_to_sub(orders))
        self.assertFalse(self.events(rows, 'target_suppression_started'))

    def test_empty_force_does_not_create_negative_evidence(self):
        orders, rows = self.run_timeline([self.frame(10000, force=[]),
            self.frame(20000, visible=False, seen=10000)])
        self.assertTrue(self.orders_to_sub(orders))
        self.assertFalse(self.events(rows, 'target_suppression_started'))

    def test_existing_search_objective_cannot_bypass_later_rejection(self):
        orders, rows = self.run_timeline([self.frame(10000, domain='SURFACE'),
            self.frame(20000), self.frame(30000, visible=False, seen=20000, memory=False)])
        self.assertEqual([o['gameTimeMs'] for o in self.orders_to_sub(orders)], [10000])
        self.assertEqual(len(self.events(rows, 'search_target_suppressed')), 1)


if __name__ == '__main__':
    unittest.main(verbosity=2)
