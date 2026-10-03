"""E2 HTTP timelines: finite income must reach a bounded native capability purchase.

Runs the real BattleClient against legal observations and a debit-on-acceptance menu.
No game process is started. An accepted engineer order is not claimed as a completed
unit, successful response, damage, or a win.
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

JAR = str(pathlib.Path(sys.argv.pop(1)).resolve())
CATALOG_SHA = '263cdaaa3e923e8307c37f059e50bee529b8ecd7c3e215937ce2adf615a0e236'


class StrategyFundingTests(unittest.TestCase):
    def run_case(self, scenario):
        tick = [0]
        credits = [1000]
        queue = [None]
        recruits = []
        structures = []
        orders, menus, observations, motions = [], [], [], {}
        income = 0 if scenario == 'income_stalls' else 200
        changed_at = 8
        final_tick = 38

        def now():
            return tick[0] * 4000

        def unit(uid, kind, x=100, y=100):
            building = kind in ('commandCenter', 'landFactory', 'extractorT1')
            value = dict(id=uid, type=kind, x=x, y=y, hp=1000, maxHp=1000,
                         dead=False, buildProgress=1, mobile=not building,
                         canAttack=kind in ('heavyTank', 'c_tank'), building=building,
                         techLevel=2 if kind == 'landFactory' else 1,
                         productionQueue=1 if kind == 'landFactory' and queue[0] else 0,
                         orderType=None)
            value.update(motions.get(uid, {}))
            return value

        def own_units():
            force = 3 if scenario == 'low_force' and tick[0] >= changed_at else 8
            own = ([unit(3, 'commandCenter'), unit(4, 'builder', 120, 120),
                     unit(5, 'landFactory', 180, 100)]
                    + [unit(10 + i, 'extractorT1', 100 + i * 30, 200) for i in range(3)]
                    + [unit(20 + i, 'heavyTank', 500, 500) for i in range(force)]
                    + [unit(uid, 'c_tank', 500, 500) for uid in recruits])
            for structure in structures:
                if tick[0] > structure['tick']:
                    created = unit(structure['id'], 'extractorT1', structure['x'], structure['y'])
                    created['buildProgress'] = 1 if tick[0] >= structure['tick'] + 2 else 0.5
                    own.append(created)
            return own

        def cleared():
            return scenario == 'need_cleared' and tick[0] >= changed_at

        def enemies():
            result = [] if cleared() else [dict(
                id=74, type='seaFactory', x=2400, y=1000, hp=1000, maxHp=1000,
                dead=False, building=True, canAttack=False, targetDomain='SURFACE',
                touchingWater=True, domainObservedAtGameTimeMs=now(), lastSeenGameTimeMs=now())]
            if scenario == 'home_threat' and tick[0] >= changed_at:
                result.append(dict(id=75, type='c_tank', x=150, y=120, hp=1000, maxHp=1000,
                                   dead=False, building=False, canAttack=True, targetDomain='SURFACE',
                                   touchingWater=False, domainObservedAtGameTimeMs=now(), lastSeenGameTimeMs=now()))
            return result

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
                if parsed.path == '/health':
                    return self.reply(dict(status='ok', version='0.07-alpha1', strategyContractVersion=1))
                if parsed.path == '/state':
                    tick[0] += 1
                    if tick[0] > 1:
                        credits[0] += income
                    if queue[0] and queue[0]['type'] == 'c_tank' and tick[0] >= queue[0]['tick'] + 2:
                        recruits.append(100 + len(recruits))
                        queue[0] = None
                    terminal = tick[0] >= final_tick
                    state = dict(status='running', sessionId='s', frame=tick[0], gameTimeMs=now(),
                                 networked=False, replay=False, player=dict(teamId=0, credits=credits[0]),
                                 map=dict(width=4000, height=4000, tilesWide=200, tilesHigh=200,
                                          tileWidth=20, tileHeight=20),
                                 match=dict(outcome='DEFEAT' if terminal else 'ONGOING', nativeDefeat=terminal,
                                            nativeVictory=False, source='native_result_screen'), ownUnits=own_units())
                    observations.append(dict(gameTimeMs=now(), credits=credits[0], armed=sum(u['canAttack'] for u in state['ownUnits'])))
                    return self.reply(state)
                if parsed.path == '/combat/production':
                    actions = [dict(actionId='u_c_tank', type='c_tank', cost=350, affordable=credits[0] >= 350)]
                    if scenario != 'no_engineer_menu':
                        actions.append(dict(actionId='u_combatEngineer', type='combatEngineer', cost=3500,
                                            affordable=credits[0] >= 3500))
                    menus.append(dict(gameTimeMs=now(), credits=credits[0], queue=1 if queue[0] else 0, actions=actions))
                    return self.reply(dict(status='observed', sessionId='s', factories=[
                        dict(id=5, tier=2, queue=1 if queue[0] else 0, actions=actions)]))
                if parsed.path == '/combat/observe':
                    visible = enemies()
                    intel = [dict(id=74, status='CLEARED', clearedAtGameTimeMs=now())] if cleared() else []
                    return self.reply(dict(status='observed', sessionId='s', gameTimeMs=now(),
                                           catalogSha256=CATALOG_SHA, catalogGameJarMatched=True,
                                           visibleEnemies=visible, rememberedEnemies=visible, enemyIntel=intel))
                if parsed.path == '/combat/engagement':
                    target = int(query['targetId'][0])
                    blocked = target == 74
                    actors = [dict(unitId=int(uid), status='BLOCKED_TERRAIN' if blocked else 'APPROACH_PATH_KNOWN',
                                   compatibility='COMPATIBLE', approachX=200, approachY=120)
                              for uid in query['unitIds'][0].split(',')]
                    return self.reply(dict(status='observed', sessionId='s', gameTimeMs=now(), targetId=target,
                                           targetVisible=not (blocked and cleared()), targetObservedAtGameTimeMs=now(),
                                           targetX=2400 if blocked else 150, targetY=1000 if blocked else 120, actors=actors))
                if parsed.path == '/scout/observe':
                    threats = [enemy for enemy in enemies() if enemy['canAttack']]
                    return self.reply(dict(status='observed', sessionId='s', frame=tick[0], gameTimeMs=now(),
                                           newlyObservedTiles=0, resources=[], visibleThreats=threats, rememberedThreats=threats))
                if parsed.path == '/combat/capabilities':
                    return self.reply(dict(status='observed', sessionId='s', capabilities=[]))
                if parsed.path == '/economy/investments':
                    return self.reply(dict(status='observed', sessionId='s', units=[]))
                if parsed.path == '/expansion/plan' and scenario == 'legal_expansion':
                    return self.reply(dict(status='planned', sessionId='s', builderId=4, extractorType='extractorT1',
                                           extractorX=300 + len(structures) * 40, extractorY=160, extractorCost=700,
                                           factoryCost=700, resourceCandidates=1,
                                           diagnostics=dict(visibleResourceCandidates=1, legalCandidates=1, notReachable=0)))
                return self.reply(dict(status='error', sessionId='s', message='No legal fixture plan'), 409)

            def do_POST(self):
                parsed = urlsplit(self.path)
                query = parse_qs(parsed.query)
                if parsed.path == '/command/build-extractor' and scenario == 'legal_expansion':
                    if credits[0] < 700:
                        return self.reply(dict(status='error', sessionId='s', message='Unaffordable native mine'), 409)
                    before = credits[0]
                    credits[0] -= 700
                    structures.append(dict(id=500 + len(structures), tick=tick[0],
                                           x=float(query['x'][0]), y=float(query['y'][0])))
                    orders.append(dict(type='extractorT1', cost=700, creditsBefore=before, creditsAfter=credits[0],
                                       gameTimeMs=now(), tick=tick[0], actionId='b_extractor'))
                    return self.reply(dict(status='queued', sessionId='s', requestId=query['requestId'][0],
                                           unitId=4, frame=tick[0], type='extractorT1'))
                if parsed.path == '/command/queue':
                    action = query['actionId'][0]
                    product, cost = ('combatEngineer', 3500) if action == 'u_combatEngineer' else ('c_tank', 350)
                    if action not in ('u_combatEngineer', 'u_c_tank') or queue[0] or credits[0] < cost:
                        return self.reply(dict(status='error', sessionId='s', message='Unavailable or unaffordable native action'), 409)
                    if product == 'combatEngineer' and scenario == 'no_engineer_menu':
                        return self.reply(dict(status='error', sessionId='s', message='Native menu has no engineer action'), 409)
                    before = credits[0]
                    credits[0] -= cost
                    queue[0] = dict(type=product, tick=tick[0])
                    orders.append(dict(type=product, cost=cost, creditsBefore=before, creditsAfter=credits[0],
                                       gameTimeMs=now(), tick=tick[0], actionId=action))
                    return self.reply(dict(status='queued', sessionId='s', requestId=query['requestId'][0],
                                           unitId=5, frame=tick[0], type=product))
                if parsed.path == '/command/attack-move':
                    assigned = [int(uid) for uid in query['unitIds'][0].split(',')]
                    x, y = float(query['x'][0]), float(query['y'][0])
                    for uid in assigned:
                        motions[uid] = dict(orderType='attackMove', orderX=x, orderY=y)
                    return self.reply(dict(status='queued', sessionId='s', requestId=query['requestId'][0],
                                           unitId=assigned[0], unitIds=assigned, frame=tick[0],
                                           targetX=x, targetY=y, orderType='attackMove'))
                if parsed.path == '/command/move' and scenario == 'legal_expansion':
                    uid = int(query['unitId'][0])
                    x, y = float(query['x'][0]), float(query['y'][0])
                    motions[uid] = dict(x=x, y=y, orderType='move', orderX=x, orderY=y)
                    return self.reply(dict(status='queued', sessionId='s', requestId=query['requestId'][0],
                                           unitId=uid, frame=tick[0], targetX=x, targetY=y, orderType='move'))
                return self.reply(dict(status='error', sessionId='s', message='Unsupported fixture order'), 409)

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as cwd:
                command = ['java', '-Dfile.encoding=UTF-8', '-Drwagent.pollMs=60',
                           '-Drwagent.reconEnabled=false', '-Drwagent.reachabilitySample=false',
                           '-Drwagent.port=' + str(server.server_port)]
                if scenario == 'legal_expansion':
                    command += ['-Drwagent.mineTarget=1', '-Drwagent.landFactoryTarget=1']
                command += ['-cp', JAR, 'io.rwagent.client.BattleClient', '300']
                process = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE,
                                         stderr=subprocess.STDOUT, timeout=20)
                reports = list(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                rows = [json.loads(line) for line in reports[0].read_text(encoding='utf-8').splitlines()] if reports else []
                evidence = os.environ.get('RW_STRATEGY_FUNDING_EVIDENCE_DIR')
                if evidence:
                    directory = pathlib.Path(evidence)
                    directory.mkdir(parents=True, exist_ok=True)
                    (directory / (scenario + '.json')).write_text(json.dumps(dict(
                        scenario=scenario, agentJar=JAR, exitCode=process.returncode, orders=orders,
                        menus=menus, observations=observations, events=rows), ensure_ascii=False, indent=2), encoding='utf-8')
                    (directory / (scenario + '.stdout.txt')).write_bytes(process.stdout)
                self.assertEqual(process.returncode, 0, process.stdout.decode('utf-8', errors='replace'))
                self.assertEqual(rows[-1]['data']['matchOutcome'], 'DEFEAT')
                self.assertTrue(self.events(rows, 'capability_need_created'), 'The real strategy must first observe a legal unmet capability need')
                return orders, rows
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    @staticmethod
    def events(rows, kind):
        return [row['data'] for row in rows if row['event'] == kind]

    def assert_resumed_after_release(self, orders, rows, reason):
        self.assertTrue(self.events(rows, 'strategy_capability_reserve_started'))
        releases = [event for event in self.events(rows, 'strategy_capability_reserve_released') if event['reason'] == reason]
        self.assertTrue(releases, self.events(rows, 'strategy_capability_reserve_released'))
        self.assertTrue([order for order in orders if order['type'] == 'c_tank'
                         and order['gameTimeMs'] >= releases[0]['gameTimeMs']],
                        'Cancellation must release money for an actual accepted ordinary production order')
        return releases[0]

    def test_finite_income_is_saved_until_native_engineer_purchase(self):
        orders, rows = self.run_case('save_then_purchase')
        engineers = [order for order in orders if order['type'] == 'combatEngineer']
        self.assertEqual(len(engineers), 1, 'Ordinary production must not consume every increment before the 3500 native engineer can be afforded: ' + str(orders))
        self.assertEqual(engineers[0]['creditsBefore'] - engineers[0]['creditsAfter'], 3500)
        starts = self.events(rows, 'strategy_capability_reserve_started')
        self.assertTrue(starts)
        self.assertEqual(starts[0]['cost'], 3500)
        deferred = [event for event in self.events(rows, 'production_deferred') if event.get('reservedFor') == 'CAPABILITY_PURCHASE']
        self.assertTrue(deferred, 'An affordable ordinary action must explicitly defer to the capability purchase')
        releases = self.events(rows, 'strategy_capability_reserve_released')
        self.assertTrue(any(event['reason'] == 'PURCHASE_ACCEPTED' for event in releases))

    def test_new_armed_home_threat_cancels_savings_and_allows_defense(self):
        orders, rows = self.run_case('home_threat')
        self.assert_resumed_after_release(orders, rows, 'HOME_EMERGENCY')
        self.assertFalse([order for order in orders if order['type'] == 'combatEngineer'])

    def test_force_below_six_cancels_savings_and_allows_recovery(self):
        orders, rows = self.run_case('low_force')
        self.assert_resumed_after_release(orders, rows, 'FORCE_BELOW_MINIMUM')

    def test_missing_engineer_menu_never_freezes_ordinary_production(self):
        orders, rows = self.run_case('no_engineer_menu')
        self.assertFalse(self.events(rows, 'strategy_capability_reserve_started'))
        self.assertGreaterEqual(len([order for order in orders if order['type'] == 'c_tank']), 3)
        self.assertFalse([event for event in self.events(rows, 'production_deferred') if event.get('reservedFor') == 'CAPABILITY_PURCHASE'])

    def test_legal_clearance_of_need_releases_savings(self):
        orders, rows = self.run_case('need_cleared')
        self.assert_resumed_after_release(orders, rows, 'NEED_UNAVAILABLE')
        self.assertTrue(self.events(rows, 'capability_need_resolved'))
        self.assertFalse([order for order in orders if order['type'] == 'combatEngineer'])

    def test_deadline_releases_savings_when_modelled_income_does_not_arrive(self):
        orders, rows = self.run_case('income_stalls')
        released = self.assert_resumed_after_release(orders, rows, 'TIMEOUT')
        self.assertGreaterEqual(released['ageGameMs'], 90000)
        self.assertLess(released['ageGameMs'], 98000)
        self.assertEqual(len(self.events(rows, 'strategy_capability_reserve_started')), 1,
                         'The same persistent need must not reacquire a reserve during its failure cooldown')
        self.assertFalse([order for order in orders if order['type'] == 'combatEngineer'])

    def test_new_above_floor_mine_cannot_spend_the_capability_reserve(self):
        orders, rows = self.run_case('legal_expansion')
        self.assertFalse(self.events(rows, 'command_rejected'), 'The legal construction fixture must support its movement and build orders')
        starts = self.events(rows, 'strategy_capability_reserve_started')
        self.assertTrue(starts)
        releases = self.events(rows, 'strategy_capability_reserve_released')
        first_release = next(event for event in releases if event['gameTimeMs'] >= starts[0]['gameTimeMs'])
        invading = [order for order in orders if order['type'] == 'extractorT1'
                    and starts[0]['gameTimeMs'] <= order['gameTimeMs'] < first_release['gameTimeMs']
                    and order['creditsAfter'] < starts[0]['cost']]
        self.assertFalse(invading, 'A new discretionary mine must leave the capability purchase funded: ' + str(invading))
        self.assertTrue([event for event in self.events(rows, 'economy_expansion_blocked')
                         if event.get('reason') == 'CAPABILITY_PURCHASE_RESERVED'],
                        'The legal above-floor plan must reach the capability funding guard')
        self.assertTrue([order for order in orders if order['type'] == 'combatEngineer'],
                        'A legal expansion menu must not prevent the accepted capability purchase')


if __name__ == '__main__':
    unittest.main()
