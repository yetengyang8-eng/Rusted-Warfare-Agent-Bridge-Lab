"""BattleClient HTTP timelines for rear provider -> ready product -> actual native mode.

These deterministic legal observations do not claim natural game combat effectiveness.
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


class EngineerProviderTests(unittest.TestCase):
    @staticmethod
    def events(rows, event):
        return [row['data'] for row in rows if row['event'] == event]

    def run_case(self, case):
        tick = [0]
        built_at, dived_at = [None], [None]
        orders, observations, motions = [], [], {}
        final_tick = 50

        def now():
            return tick[0] * 4000

        def credits():
            return 1000 if case == 'funding' and tick[0] < 7 else 20000

        def jet_ready():
            return built_at[0] is not None and tick[0] >= built_at[0] + 3 and case != 'unfinished'

        def submerged():
            return dived_at[0] is not None and tick[0] >= dived_at[0] + 3 and case != 'mode_timeout'

        def visible():
            moved = any(order['unitIds'] == [81] and order['path'] == '/command/move' for order in orders)
            return not (case == 'hidden' and moved)

        def unit(uid, kind, x=100, y=100):
            building = kind in ('commandCenter', 'landFactory', 'extractorT1')
            result = dict(id=uid, type=kind, x=x, y=y, hp=1000, maxHp=1000,
                          buildProgress=1, productionQueue=0, mobile=not building,
                          canAttack=kind in ('heavyTank', 'combatEngineer', 'amphibiousJet'),
                          building=building, dead=False, orderType=None,
                          techLevel=2 if kind == 'landFactory' else 1)
            result.update(motions.get(uid, {}))
            return result

        def own_units():
            own = [unit(1, 'commandCenter'), unit(2, 'builder', 120, 100),
                   unit(5, 'landFactory', 180, 100),
                   unit(80, 'combatEngineer', 1500 if case == 'rear' else 130, 100)]
            own += [unit(10 + i, 'heavyTank', 400, 300) for i in range(8)]
            own += [unit(30 + i, 'extractorT1', 100 + i * 30, 200) for i in range(3)]
            if built_at[0] is not None and tick[0] > built_at[0] and case != 'missing_product':
                jet = unit(81, 'amphibiousJet', 190, 100)
                jet['buildProgress'] = 1 if jet_ready() else .5
                own.append(jet)
            return own

        def contact():
            attacks = [order for order in orders if order['path'] == '/command/attack-move' and order['unitIds'] == [81]]
            # Stable fresh team damage avoids exercising the unrelated ordinary no-progress return.
            hp = max(100, 1000 - 8 * max(0, tick[0] - attacks[0]['tick'])) if attacks else 1000
            return dict(id=230, type='attackSubmarine', x=2200, y=1000, hp=hp, maxHp=1000,
                        dead=False, building=False, canAttack=True, targetDomain='SUBMERGED',
                        touchingWater=True, domainObservedAtGameTimeMs=now() if visible() else 12000,
                        lastSeenGameTimeMs=now() if visible() else 12000)

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
                    for order in orders:
                        if order['path'] == '/command/move' and order['tick'] < tick[0]:
                            uid = order['unitIds'][0]
                            motions[uid].update(x=order['x'], y=order['y'], orderType=None)
                    terminal = tick[0] >= final_tick
                    own = own_units()
                    observations.append(dict(gameTimeMs=now(), targetVisible=visible(),
                                             jetSubmerged=submerged(), credits=credits(), ownUnits=own))
                    return self.reply(dict(status='running', sessionId='s', frame=tick[0], gameTimeMs=now(),
                                           networked=False, replay=False, player=dict(teamId=0, credits=credits()),
                                           map=dict(width=4000, height=4000, tilesWide=200, tilesHigh=200,
                                                    tileWidth=20, tileHeight=20),
                                           match=dict(outcome='DEFEAT' if terminal else 'ONGOING', nativeDefeat=terminal,
                                                      nativeVictory=False, source='native_result_screen'), ownUnits=own))
                if parsed.path == '/combat/observe':
                    return self.reply(dict(status='observed', sessionId='s', gameTimeMs=now(),
                                           catalogSha256=CATALOG_SHA, catalogGameJarMatched=True,
                                           visibleEnemies=[contact()] if visible() else [], rememberedEnemies=[contact()],
                                           enemyIntel=[] if visible() else [dict(id=230, status='LOST_CONTACT', lastSeenGameTimeMs=12000)]))
                if parsed.path == '/combat/engagement':
                    actors = []
                    for uid in map(int, query['unitIds'][0].split(',')):
                        jet = uid == 81
                        actor = dict(unitId=uid, compatibility='UNKNOWN' if not visible() else 'COMPATIBLE' if jet and submerged() else 'INCOMPATIBLE',
                                     status='APPROACH_PATH_KNOWN' if visible() else 'UNKNOWN',
                                     lastKnownPositionApproachStatus='APPROACH_PATH_KNOWN', approachX=2160, approachY=1000)
                        if jet and visible() and not submerged():
                            jet_unit = next((u for u in own_units() if u['id'] == 81), {})
                            actor.update(requiredMode='DIVE', modeActionId='152',
                                         modeApproachStatus='UNKNOWN' if case == 'unknown_water' else 'APPROACH_PATH_KNOWN',
                                         modeApproachX=2160, modeApproachY=1000,
                                         modeActionReady=jet_unit.get('x') == 2160 and jet_unit.get('y') == 1000)
                        actors.append(actor)
                    return self.reply(dict(status='observed', sessionId='s', gameTimeMs=now(), targetId=230,
                                           targetVisible=visible(), targetObservedAtGameTimeMs=now() if visible() else 12000,
                                           targetX=2200, targetY=1000, actors=actors))
                if parsed.path == '/economy/construction-plan':
                    if query.get('type') != ['amphibiousJet']:
                        return self.reply(dict(status='error', message='No fixture action'), 409)
                    engineer = next(u for u in own_units() if u['id'] == 80)
                    return self.reply(dict(status='planned', sessionId='s', unitId=80, type='amphibiousJet',
                                           actionId='u_amphibiousJet', cost=2000, affordable=credits() >= 2000,
                                           x=engineer['x'] + 60, y=engineer['y']))
                if parsed.path == '/combat/unit-modes':
                    return self.reply(dict(status='observed', sessionId='s', gameTimeMs=now(), unitId=81,
                                           submergedWeaponAvailable=submerged(), actions=[dict(
                                               mode='FLY', actionId='151', cost=0, available=True, affordable=submerged())]))
                if parsed.path == '/combat/production':
                    return self.reply(dict(status='observed', sessionId='s', factories=[dict(id=5, tier=2, queue=0, actions=[])]))
                if parsed.path == '/scout/observe':
                    return self.reply(dict(status='observed', sessionId='s', frame=tick[0], gameTimeMs=now(),
                                           newlyObservedTiles=0, resources=[], visibleThreats=[], rememberedThreats=[]))
                if parsed.path == '/combat/capabilities':
                    return self.reply(dict(status='observed', sessionId='s', capabilities=[]))
                if parsed.path == '/economy/investments':
                    return self.reply(dict(status='observed', sessionId='s', units=[]))
                return self.reply(dict(status='error', sessionId='s', message='No legal fixture plan'), 409)

            def do_POST(self):
                parsed = urlsplit(self.path)
                query = parse_qs(parsed.query)
                ids = list(map(int, query.get('unitIds', query.get('unitId', ['']))[0].split(',')))
                order = dict(path=parsed.path, unitIds=ids, tick=tick[0], gameTimeMs=now(),
                             actionId=query.get('actionId', [None])[0], targetVisible=visible(), jetSubmerged=submerged())
                if 'x' in query:
                    order.update(x=float(query['x'][0]), y=float(query['y'][0]))
                orders.append(order)
                if parsed.path == '/command/construct':
                    if case == 'rejected_build':
                        return self.reply(dict(status='error', message='Native fixture refusal'), 409)
                    built_at[0] = tick[0]
                elif parsed.path == '/command/unit-mode':
                    if order['actionId'] == '152':
                        dived_at[0] = tick[0]
                    elif order['actionId'] == '151':
                        dived_at[0] = None
                elif parsed.path in ('/command/move', '/command/attack-move'):
                    for uid in ids:
                        motions[uid] = dict(orderType='move' if parsed.path == '/command/move' else 'attackMove',
                                            orderX=order['x'], orderY=order['y'])
                else:
                    return self.reply(dict(status='error', message='Unsupported fixture command'), 409)
                return self.reply(dict(status='queued', sessionId='s', requestId=query['requestId'][0],
                                       unitId=ids[0], unitIds=ids, frame=tick[0],
                                       targetX=order.get('x', 0), targetY=order.get('y', 0)))

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as cwd:
                command = ['java', '-Dfile.encoding=UTF-8', '-Drwagent.pollMs=30',
                           '-Drwagent.reconEnabled=false', '-Drwagent.reachabilitySample=false',
                           '-Drwagent.mineTarget=1', '-Drwagent.landFactoryTarget=1',
                           '-Drwagent.port=' + str(server.server_port), '-cp', JAR,
                           'io.rwagent.client.BattleClient', '300']
                process = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=25)
                reports = list(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                rows = [json.loads(line) for line in reports[0].read_text(encoding='utf-8').splitlines()] if reports else []
                evidence = os.environ.get('RW_ENGINEER_PROVIDER_EVIDENCE_DIR')
                if evidence:
                    directory = pathlib.Path(evidence)
                    directory.mkdir(parents=True, exist_ok=True)
                    (directory / (case + '.json')).write_text(json.dumps(dict(
                        case=case, agentJar=JAR, exitCode=process.returncode, orders=orders,
                        observations=observations, events=rows), ensure_ascii=False, indent=2), encoding='utf-8')
                    (directory / (case + '.stdout.txt')).write_bytes(process.stdout)
                    if reports:
                        (directory / (case + '.jsonl')).write_bytes(reports[0].read_bytes())
                self.assertEqual(process.returncode, 0, process.stdout.decode('utf-8', errors='replace'))
                self.assertTrue(rows, 'BattleClient must commit its report')
                self.assertTrue(self.events(rows, 'capability_need_created'))
                self.assertFalse([order for order in orders if order['path'] == '/command/attack-move' and 80 in order['unitIds']],
                                 'The provider must never attack the weapon-domain gap itself')
                self.assertEqual(rows[-1]['data']['strategy']['needsResolvedByLegalEvidence'], 0)
                return orders, rows, observations
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_complete_underwater_response_requires_ready_product_and_observed_mode(self):
        orders, rows, observations = self.run_case('chain')
        self.assertEqual(len(self.events(rows, 'strategy_support_transferred')), 1)
        self.assertEqual(len(self.events(rows, 'strategy_responder_mode_observed')), 1)
        dives = [o for o in orders if o['path'] == '/command/unit-mode' and o['actionId'] == '152']
        attacks = [o for o in orders if o['path'] == '/command/attack-move' and o['unitIds'] == [81]]
        self.assertEqual(len(dives), 1)
        self.assertTrue(attacks)
        self.assertTrue(all(o['jetSubmerged'] and o['targetVisible'] for o in attacks))
        self.assertGreater(attacks[0]['gameTimeMs'], dives[0]['gameTimeMs'])

    def test_forward_provider_returns_to_rear_before_construction(self):
        orders, rows, observations = self.run_case('rear')
        provider = [o for o in orders if o['unitIds'] == [80]]
        self.assertEqual(provider[0]['path'], '/command/move')
        self.assertEqual(provider[0]['x'], 100)
        construction = [o for o in provider if o['path'] == '/command/construct']
        self.assertTrue(construction)
        self.assertLessEqual(construction[0]['x'], 250)
        self.assertTrue(self.events(rows, 'strategy_provider_repositioned'))

    def test_funding_holds_native_quote_then_releases_on_accepted_build(self):
        orders, rows, observations = self.run_case('funding')
        self.assertEqual(len(self.events(rows, 'strategy_support_reserve_started')), 1)
        self.assertEqual(self.events(rows, 'strategy_support_reserve_started')[0]['cost'], 2000)
        self.assertTrue([e for e in self.events(rows, 'strategy_support_reserve_released') if e['reason'] == 'CONSTRUCTION_ACCEPTED'])
        self.assertFalse([o for o in orders if o['path'] == '/command/construct' and o['tick'] < 7])

    def test_unfinished_product_cannot_handover_or_respond(self):
        orders, rows, observations = self.run_case('unfinished')
        self.assertFalse(self.events(rows, 'strategy_support_transferred'))
        self.assertFalse([o for o in orders if 81 in o['unitIds']])
        self.assertTrue([e for e in self.events(rows, 'strategy_task_blocked') if e['reason'] == 'CONSTRUCTION_TIMEOUT'])

    def test_missing_paid_product_times_out_without_false_ready(self):
        orders, rows, observations = self.run_case('missing_product')
        self.assertFalse(self.events(rows, 'strategy_construction_observed'))
        self.assertTrue([e for e in self.events(rows, 'strategy_task_blocked') if e['reason'] == 'CONSTRUCTION_TIMEOUT'])

    def test_unknown_water_path_cannot_authorize_move_or_dive(self):
        orders, rows, observations = self.run_case('unknown_water')
        self.assertTrue(self.events(rows, 'strategy_support_transferred'))
        self.assertFalse([o for o in orders if 81 in o['unitIds']])

    def test_lost_contact_before_water_arrival_does_not_dive_or_attack(self):
        orders, rows, observations = self.run_case('hidden')
        self.assertTrue([o for o in orders if o['path'] == '/command/move' and o['unitIds'] == [81]])
        self.assertFalse([o for o in orders if o['path'] == '/command/unit-mode' and o['actionId'] == '152'])
        self.assertFalse([o for o in orders if o['path'] == '/command/attack-move' and o['unitIds'] == [81]])

    def test_accepted_mode_without_native_readiness_has_bounded_wait(self):
        orders, rows, observations = self.run_case('mode_timeout')
        dives = [o for o in orders if o['path'] == '/command/unit-mode' and o['actionId'] == '152']
        self.assertGreaterEqual(len(dives), 1)
        self.assertLessEqual(len(dives), 3, 'The unresolved need retains its bounded three-attempt budget')
        self.assertTrue(all(b['gameTimeMs'] - a['gameTimeMs'] >= 45000 for a, b in zip(dives, dives[1:])),
                        'An accepted unconfirmed mode cannot be immediately repeated while waiting')
        self.assertFalse(self.events(rows, 'strategy_responder_mode_observed'))
        self.assertFalse([o for o in orders if o['path'] == '/command/attack-move' and o['unitIds'] == [81]])
        self.assertTrue([e for e in self.events(rows, 'strategy_task_blocked') if e['reason'] == 'MODE_TRANSITION_NO_PROGRESS'])


if __name__ == '__main__':
    unittest.main()
