"""Real BattleClient HTTP checks for bounded quality spending and local mine payback.

Debit-on-acceptance native menus, two producers, and an empty-queue/new-product gap
exercise the accepted-product commitment rather than assuming queued equals complete.
No game is started and no combat gain is claimed from these synthetic timelines.
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


class SurplusSpendingTests(unittest.TestCase):
    def run_case(self, scenario):
        mine_scene = scenario.startswith('mine_')
        tick, credits = [0], [50000.0 if scenario == 'mine_t3' else 20000.0]
        queues, motions, orders, observations, menus = {}, {}, [], [], []
        products, flights, upgraded, tier3, ordinary_products = [], [], set(), set(), []
        final_tick = 160 if scenario == 'mine_t3' else 100 if scenario == 'hard_slot' else 30
        ordinary_force = 127 if scenario == 'hard_slot' else 39 if mine_scene else 23 if scenario == 'low_force' else 24
        artillery_before = 0 if mine_scene or scenario == 'hard_slot' else 2
        artillery_cost = 4700  # Deliberately different from the frozen version's price: quote governs.

        def now():
            return tick[0] * 4000

        def unit(uid, kind, x=100, y=100):
            building = kind in ('commandCenter', 'landFactory', 'extractorT1', 'extractorT2', 'extractorT3')
            result = dict(id=uid, type=kind, x=x, y=y, hp=600, maxHp=600, dead=False,
                          buildProgress=1, mobile=not building, canAttack=kind in ('heavyTank', 'heavyArtillery'),
                          building=building, techLevel=2 if kind in ('landFactory', 'extractorT2') else 1,
                          productionQueue=1 if uid in queues else 0, orderType=None)
            result.update(motions.get(uid, {}))
            return result

        def own_units():
            return ([unit(3, 'commandCenter'), unit(4, 'builder', 120, 120),
                     unit(5, 'landFactory', 180, 100), unit(6, 'landFactory', 250, 100)]
                    + [unit(10 + i, 'extractorT3' if 10 + i in tier3 else 'extractorT2' if 10 + i in upgraded else 'extractorT1', 100 + i * 30, 200) for i in range(3)]
                    + [unit(1000 + i, 'heavyTank', 500, 500) for i in range(ordinary_force)]
                    + [unit(200 + i, 'heavyArtillery', 550, 500) for i in range(artillery_before)]
                    + [unit(uid, 'heavyArtillery', 200, 150) for uid in products]
                    + [unit(uid, 'heavyTank', 500, 500) for uid in ordinary_products])

        def contacts():
            result = [dict(id=74, type='landFactory' if scenario != 'air_only' else 'c_helicopter',
                           x=2400, y=1000, hp=1200, maxHp=1200, dead=False,
                           building=scenario != 'air_only', canAttack=scenario == 'air_only',
                           targetDomain='SURFACE' if scenario != 'air_only' else 'AIR', touchingWater=False,
                           domainObservedAtGameTimeMs=now(), lastSeenGameTimeMs=now())]
            if scenario in ('home_emergency', 'mine_threat'):
                result.append(dict(id=75, type='c_tank', x=150, y=120, hp=210, maxHp=210,
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
                parsed, query = urlsplit(self.path), parse_qs(urlsplit(self.path).query)
                if parsed.path == '/health':
                    return self.reply(dict(status='ok', version='0.07-alpha1', strategyContractVersion=1))
                if parsed.path == '/state':
                    tick[0] += 1
                    for producer, entry in list(queues.items()):
                        if entry['type'] == 'heavyArtillery' and tick[0] >= entry['tick'] + 2:
                            flights.append(dict(tick=entry['tick'] + 5, uid=500 + len(products) + len(flights)))
                            del queues[producer]
                        elif entry['type'] == 'extractorT2' and tick[0] >= entry['tick'] + 2:
                            upgraded.add(producer)
                            del queues[producer]
                        elif entry['type'] == 'extractorT3' and tick[0] >= entry['tick'] + 2:
                            tier3.add(producer)
                            del queues[producer]
                        elif entry['type'] == 'heavyTank' and scenario == 'mine_t3' and tick[0] >= entry['tick'] + 2:
                            ordinary_products.append(4000 + len(ordinary_products))
                            del queues[producer]
                    for product in list(flights):
                        if tick[0] >= product['tick']:
                            products.append(product['uid'])
                            flights.remove(product)
                    terminal = tick[0] >= final_tick
                    own = own_units()
                    observations.append(dict(gameTimeMs=now(), credits=credits[0], units=own))
                    return self.reply(dict(status='running', sessionId='s', frame=tick[0], gameTimeMs=now(),
                                           networked=False, replay=False, player=dict(teamId=0, credits=credits[0]),
                                           map=dict(width=4000, height=4000, tilesWide=200, tilesHigh=200,
                                                    tileWidth=20, tileHeight=20),
                                           match=dict(outcome='DEFEAT' if terminal else 'ONGOING', nativeDefeat=terminal,
                                                      nativeVictory=False, source='native_result_screen'), ownUnits=own))
                if parsed.path == '/combat/production':
                    factories = []
                    for producer in (5, 6):
                        actions = [dict(actionId='u_heavyTank', type='heavyTank', cost=800, affordable=credits[0] >= 800),
                                   dict(actionId='u_c_tank', type='c_tank', cost=350, affordable=credits[0] >= 350)]
                        if scenario == 'mine_noquote':
                            actions = []
                        elif not mine_scene:
                            actions.append(dict(actionId='u_heavyArtillery', type='heavyArtillery', cost=artillery_cost,
                                                affordable=scenario != 'native_unaffordable' and credits[0] >= artillery_cost))
                        factories.append(dict(id=producer, tier=2, queue=1 if producer in queues else 0, actions=actions))
                    menus.append(dict(gameTimeMs=now(), factories=factories))
                    return self.reply(dict(status='observed', sessionId='s', factories=factories))
                if parsed.path == '/combat/observe':
                    visible = contacts()
                    return self.reply(dict(status='observed', sessionId='s', gameTimeMs=now(), catalogSha256=CATALOG_SHA,
                                           catalogGameJarMatched=True, visibleEnemies=visible, rememberedEnemies=visible, enemyIntel=[]))
                if parsed.path == '/combat/engagement':
                    target = int(query['targetId'][0])
                    return self.reply(dict(status='observed', sessionId='s', gameTimeMs=now(), targetId=target,
                                           targetVisible=True, targetObservedAtGameTimeMs=now(), targetX=2400, targetY=1000,
                                           actors=[dict(unitId=int(uid), status='APPROACH_PATH_KNOWN', compatibility='COMPATIBLE',
                                                        approachX=2200, approachY=1000) for uid in query['unitIds'][0].split(',')]))
                if parsed.path == '/scout/observe':
                    threats = [u for u in contacts() if u['canAttack']]
                    return self.reply(dict(status='observed', sessionId='s', frame=tick[0], gameTimeMs=now(), newlyObservedTiles=0,
                                           resources=[], visibleThreats=threats, rememberedThreats=threats))
                if parsed.path == '/combat/capabilities':
                    return self.reply(dict(status='observed', sessionId='s', capabilities=[]))
                if parsed.path == '/economy/investments':
                    offers = [dict(id=10 + i, type='extractorT2' if 10 + i in upgraded else 'extractorT1',
                                   product='extractorT3' if 10 + i in upgraded else 'extractorT2',
                                   actionId='extractorT3_0' if 10 + i in upgraded else 'extractorT2_0',
                                   cost=5300 if 10 + i in upgraded else 1400,
                                   queue=1 if 10 + i in queues else 0, affordable=credits[0] >= (5300 if 10 + i in upgraded else 1400))
                              for i in range(3) if 10 + i not in tier3 and (scenario == 'mine_t3' or 10 + i not in upgraded)] if mine_scene and tick[0] >= 2 else []
                    return self.reply(dict(status='observed', sessionId='s', units=offers))
                return self.reply(dict(status='error', sessionId='s', message='No legal fixture plan'), 409)

            def do_POST(self):
                parsed, query = urlsplit(self.path), parse_qs(urlsplit(self.path).query)
                if parsed.path in ('/command/queue', '/command/invest'):
                    producer, action = int(query['unitId'][0]), query['actionId'][0]
                    product, cost = {'u_heavyTank': ('heavyTank', 800), 'u_c_tank': ('c_tank', 350),
                                     'u_heavyArtillery': ('heavyArtillery', artillery_cost),
                                     'extractorT2_0': ('extractorT2', 1400), 'extractorT3_0': ('extractorT3', 5300)}[action]
                    if producer in queues or credits[0] < cost or product == 'heavyArtillery' and scenario == 'native_unaffordable':
                        return self.reply(dict(status='error', sessionId='s', message='Native queue or affordability guard'), 409)
                    before = credits[0]
                    credits[0] -= cost
                    queues[producer] = dict(type=product, tick=tick[0])
                    orders.append(dict(path=parsed.path, producerId=producer, type=product, cost=cost, gameTimeMs=now(),
                                       creditsBefore=before, creditsAfter=credits[0]))
                    return self.reply(dict(status='queued', sessionId='s', requestId=query['requestId'][0],
                                           unitId=producer, frame=tick[0], type=product))
                if parsed.path in ('/command/attack-move', '/command/move'):
                    ids = [int(uid) for uid in query.get('unitIds', query.get('unitId'))[0].split(',')]
                    x, y = float(query['x'][0]), float(query['y'][0])
                    for uid in ids:
                        motions[uid] = dict(orderType='attackMove' if parsed.path == '/command/attack-move' else 'move', orderX=x, orderY=y)
                    return self.reply(dict(status='queued', sessionId='s', requestId=query['requestId'][0], unitId=ids[0], unitIds=ids,
                                           frame=tick[0], targetX=x, targetY=y, orderType=motions[ids[0]]['orderType']))
                return self.reply(dict(status='error', sessionId='s', message='Unsupported fixture command'), 409)

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as cwd:
                command = ['java', '-Dfile.encoding=UTF-8', '-Drwagent.pollMs=60',
                           '-Drwagent.reconEnabled=false', '-Drwagent.reachabilitySample=false',
                           '-Drwagent.mineTarget=1', '-Drwagent.landFactoryTarget=1',
                           '-Drwagent.port=' + str(server.server_port)]
                if scenario == 'hard_slot':
                    command += ['-Drwagent.mobileUnitHardCap=128']
                elif mine_scene:
                    # This scene holds a one-slot military gap for the entire timeline.
                    # An unrestricted income-based target increase would create a real
                    # new deficit and legitimately allow later mining investment.
                    command += ['-Drwagent.mobileUnitHardCap=40']
                command += ['-cp', JAR, 'io.rwagent.client.BattleClient', '1800' if scenario == 'mine_t3' else '300' if scenario == 'mine_short' else '600']
                process = subprocess.run(command, cwd=cwd,
                                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=25)
                reports = list(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                rows = [json.loads(line) for line in reports[0].read_text(encoding='utf-8').splitlines()] if reports else []
                evidence = os.environ.get('RW_SURPLUS_SPENDING_EVIDENCE_DIR')
                if evidence:
                    directory = pathlib.Path(evidence)
                    directory.mkdir(parents=True, exist_ok=True)
                    (directory / (scenario + '.json')).write_text(json.dumps(dict(scenario=scenario, agentJar=JAR,
                        exitCode=process.returncode, orders=orders, observations=observations, menus=menus, events=rows),
                        ensure_ascii=False, indent=2), encoding='utf-8')
                    (directory / (scenario + '.stdout.txt')).write_bytes(process.stdout)
                self.assertEqual(process.returncode, 0, process.stdout.decode('utf-8', errors='replace'))
                self.assertTrue(rows)
                self.assertEqual(rows[-1]['data']['matchOutcome'], 'DEFEAT')
                self.assertFalse(self.events(rows, 'command_rejected'))
                return orders, rows
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    @staticmethod
    def events(rows, kind):
        return [row['data'] for row in rows if row['event'] == kind]

    def test_surplus_buys_native_quote_and_observation_gap_does_not_duplicate(self):
        orders, rows = self.run_case('rich')
        artillery = [order for order in orders if order['type'] == 'heavyArtillery']
        self.assertEqual(len(artillery), 1, 'Two observed artillery plus one paid role must exhaust target40 quota3')
        self.assertEqual(artillery[0]['creditsBefore'] - artillery[0]['creditsAfter'], 4700)
        self.assertGreaterEqual(artillery[0]['creditsAfter'], 1600, 'Two native ordinary replacements stay funded')
        self.assertTrue(self.events(rows, 'surplus_role_ordered'))
        self.assertTrue([event for event in self.events(rows, 'surplus_spending_evaluated')
                         if event.get('reason') == 'ARTILLERY_QUOTA_REACHED'])

    def test_native_unaffordable_action_keeps_ordinary_production(self):
        orders, _ = self.run_case('native_unaffordable')
        self.assertFalse([order for order in orders if order['type'] == 'heavyArtillery'])
        self.assertTrue([order for order in orders if order['type'] == 'heavyTank'])

    def test_air_only_contact_does_not_buy_ground_siege_role(self):
        orders, _ = self.run_case('air_only')
        self.assertFalse([order for order in orders if order['type'] == 'heavyArtillery'])

    def test_ordinary_force_below_twenty_four_keeps_recovery_first(self):
        orders, _ = self.run_case('low_force')
        self.assertFalse([order for order in orders if order['type'] == 'heavyArtillery'])
        self.assertTrue([order for order in orders if order['type'] == 'heavyTank'])

    def test_home_emergency_blocks_optional_quality_spending(self):
        orders, _ = self.run_case('home_emergency')
        self.assertFalse([order for order in orders if order['type'] == 'heavyArtillery'])

    def test_near_target_rich_economy_invests_when_local_payback_window_is_long(self):
        orders, rows = self.run_case('mine_saturated')
        upgrades = [order for order in orders if order['type'] == 'extractorT2']
        self.assertEqual(len(upgrades), 3, 'Stable long-lived mines may grow even with surplus cash and near-target army')
        self.assertTrue(all(order['cost'] == 1400 for order in upgrades))
        refusals = self.events(rows, 'mine_income_investment_deferred')
        self.assertTrue(refusals, 'Initial clear-window wait must remain explicit')
        self.assertTrue(all(event['reason'] in ('LOCAL_CLEAR_WINDOW_TOO_SHORT', 'MINE_UPGRADE_ALREADY_COMMITTED') for event in refusals))
        self.assertTrue(all(event['armyTarget'] == 40 for event in self.events(rows, 'strategy_capacity')),
                        'The fixture must preserve its near-target premise throughout')

    def test_short_horizon_does_not_pay_for_unrecoverable_upgrade(self):
        orders, rows = self.run_case('mine_short')
        self.assertFalse([order for order in orders if order['path'] == '/command/invest'])
        self.assertTrue([event for event in self.events(rows, 'mine_income_investment_deferred')
                         if event['reason'] == 'PAYBACK_HORIZON_TOO_SHORT'])

    def test_current_local_raid_blocks_upgrade_without_a_global_income_veto(self):
        orders, rows = self.run_case('mine_threat')
        self.assertFalse([order for order in orders if order['path'] == '/command/invest'])
        self.assertTrue([event for event in self.events(rows, 'mine_income_investment_deferred')
                         if event['reason'] == 'LOCAL_RISK_OR_HEALTH_UNCONFIRMED'])

    def test_missing_ordinary_quote_keeps_replacement_budget_unknown(self):
        orders, rows = self.run_case('mine_noquote')
        self.assertFalse([order for order in orders if order['path'] == '/command/invest'])
        self.assertTrue([event for event in self.events(rows, 'mine_income_investment_deferred')
                         if event['reason'] == 'PROTECTED_BUDGET_UNKNOWN'])

    def test_native_t3_quote_and_conversion_observed_after_a_new_clear_window(self):
        orders, rows = self.run_case('mine_t3')
        upgrades = [order for order in orders if order['path'] == '/command/invest']
        self.assertEqual([order['type'] for order in upgrades].count('extractorT2'), 3)
        self.assertEqual([order['type'] for order in upgrades].count('extractorT3'), 3)
        self.assertEqual(sum(order['cost'] for order in upgrades), 3 * (1400 + 5300))
        allocations = [event for event in self.events(rows, 'strategy_allocation')
                       if event.get('selected') == 'MINE_T3_INCOME_INVESTMENT']
        self.assertEqual(len(allocations), 3)
        self.assertTrue(all(abs(event['paybackEstimateGameSeconds'] - 5300 / 12.07) < .001 for event in allocations))
        self.assertEqual(len(self.events(rows, 'mine_upgrade_observed')), 6)

    def test_paid_observation_gap_protects_last_hard_cap_slot_from_ordinary_production(self):
        orders, rows = self.run_case('hard_slot')
        production = [order for order in orders if order['path'] == '/command/queue']
        self.assertEqual([order['type'] for order in production], ['heavyArtillery'],
                         'The one paid artillery slot must also block ordinary orders during empty-queue observation gap')
        observed = [row['data'] for row in rows if row['event'] == 'observation']
        self.assertLessEqual(max(sum(unit['mobile'] and unit['canAttack'] for unit in state['ownUnits']) for state in observed), 128)


if __name__ == '__main__':
    unittest.main()
