"""Real BattleClient HTTP timelines for specialist isolation and bounded investigation.

The fixture supplies legal observations and clears completed native orders. It does
not run a game or equate an accepted command with damage or objective completion.
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


class SpecialistLifecycleTests(unittest.TestCase):
    def run_case(self, scenario):
        tick = [0]
        orders, observations, motions, structures = [], [], {}, []
        final_tick = 42 if scenario == 'fresh_contact' else 34

        def now():
            return tick[0] * 4000

        def visible():
            return scenario == 'economic_isolation' or tick[0] < 8 or scenario == 'fresh_contact' and tick[0] >= 25

        def unit(uid, kind, x=100, y=100):
            building = kind in ('commandCenter', 'landFactory', 'extractorT1')
            result = dict(id=uid, type=kind, x=x, y=y, hp=1000, maxHp=1000,
                          dead=False, buildProgress=1, mobile=not building,
                          canAttack=kind in ('heavyTank', 'combatEngineer'), building=building,
                          techLevel=2 if kind == 'landFactory' else 1, productionQueue=0,
                          orderType=None)
            result.update(motions.get(uid, {}))
            if scenario == 'return_home' and uid in (8, 9):
                result.update(hp=200, x=900 if 8 <= tick[0] < 18 else 110, y=100)
                if tick[0] in (1, 8, 12, 18):
                    result.update(orderType=None, orderX=None, orderY=None)
                    motions[uid] = dict(orderType=None)
            return result

        def own_units():
            own = [unit(3, 'commandCenter'), unit(4, 'builder', 120, 120),
                   unit(5, 'landFactory', 180, 100),
                   unit(9, 'combatEngineer', 200 if scenario == 'economic_isolation' else 1400, 1000)]
            if scenario in ('economic_isolation', 'return_home'):
                own.append(unit(8, 'builder', 220, 120))
            own += [unit(10 + i, 'extractorT1', 100 + i * 30, 200) for i in range(3)]
            own += [unit(20 + i, 'heavyTank', 500, 500) for i in range(8)]
            for structure in structures:
                built = unit(structure['id'], 'extractorT1', structure['x'], structure['y'])
                built['buildProgress'] = 0.5  # A committed job remains in progress during this short timeline.
                own.append(built)
            return own

        def contact():
            seen = now() if visible() else 28000
            return dict(id=74, type='seaFactory', x=2400, y=1000, hp=1000, maxHp=1000,
                        dead=False, building=True, canAttack=False, targetDomain='SURFACE',
                        touchingWater=True, domainObservedAtGameTimeMs=seen, lastSeenGameTimeMs=seen)

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
                    # Completed movement is absent in the next native observation;
                    # the latest order determines the position, including a return home.
                    if scenario in ('lost_contact', 'fresh_contact'):
                        for order in orders:
                            if order['path'] in ('/command/attack-move', '/command/move') and order['unitIds'] == [9] and order['tick'] < tick[0]:
                                motions[9] = dict(x=order['x'], y=order['y'], orderType=None)
                    terminal = tick[0] >= final_tick
                    own = own_units()
                    observations.append(dict(gameTimeMs=now(), targetVisible=visible(),
                                             specialists=[u for u in own if u['id'] in (8, 9)]))
                    return self.reply(dict(status='running', sessionId='s', frame=tick[0], gameTimeMs=now(),
                                           networked=False, replay=False, player=dict(teamId=0, credits=20000),
                                           map=dict(width=4000, height=4000, tilesWide=200, tilesHigh=200,
                                                    tileWidth=20, tileHeight=20),
                                           match=dict(outcome='DEFEAT' if terminal else 'ONGOING', nativeDefeat=terminal,
                                                      nativeVictory=False, source='native_result_screen'), ownUnits=own))
                if parsed.path == '/combat/observe':
                    contacts = [] if scenario == 'return_home' else [contact()]
                    return self.reply(dict(status='observed', sessionId='s', gameTimeMs=now(),
                                           catalogSha256=CATALOG_SHA, catalogGameJarMatched=True,
                                           visibleEnemies=contacts if visible() else [], rememberedEnemies=contacts,
                                           enemyIntel=[] if visible() or scenario == 'return_home' else [dict(
                                               id=74, status='UNKNOWN', lastSeenGameTimeMs=28000)]))
                if parsed.path == '/combat/engagement':
                    actors = []
                    for uid in map(int, query['unitIds'][0].split(',')):
                        allowed = uid == 9 and scenario != 'economic_isolation'
                        actors.append(dict(unitId=uid,
                                           status='APPROACH_PATH_KNOWN' if allowed and visible() else 'UNKNOWN' if allowed else 'BLOCKED_TERRAIN',
                                           compatibility='COMPATIBLE' if visible() else 'UNKNOWN',
                                           lastKnownPositionApproachStatus='APPROACH_PATH_KNOWN' if allowed else 'BLOCKED_TERRAIN',
                                           approachX=2200, approachY=1000))
                    return self.reply(dict(status='observed', sessionId='s', gameTimeMs=now(), targetId=74,
                                           targetVisible=visible(), targetObservedAtGameTimeMs=now() if visible() else 28000,
                                           targetX=2400, targetY=1000, actors=actors))
                if parsed.path == '/combat/production':
                    return self.reply(dict(status='observed', sessionId='s', factories=[dict(id=5, tier=2, queue=0, actions=[])]))
                if parsed.path == '/scout/observe':
                    resources = [dict(tile=999, x=1100, y=300)] if scenario == 'economic_isolation' else []
                    return self.reply(dict(status='observed', sessionId='s', frame=tick[0], gameTimeMs=now(),
                                           newlyObservedTiles=0, resources=resources, visibleThreats=[], rememberedThreats=[]))
                if parsed.path == '/combat/capabilities':
                    return self.reply(dict(status='observed', sessionId='s', capabilities=[]))
                if parsed.path == '/economy/investments':
                    return self.reply(dict(status='observed', sessionId='s', units=[]))
                if parsed.path == '/expansion/plan' and scenario == 'economic_isolation':
                    uid = int(query.get('unitId', ['4'])[0])
                    if uid in (8, 9):
                        return self.reply(dict(status='planned', sessionId='s', builderId=uid,
                                               extractorType='extractorT1', extractorX=400 + uid * 30,
                                               extractorY=300, extractorCost=700, factoryCost=700,
                                               resourceCandidates=1, diagnostics=dict(visibleResourceCandidates=1,
                                                                                   legalCandidates=1, notReachable=0)))
                if parsed.path == '/scout/resource-approach' and scenario == 'economic_isolation':
                    return self.reply(dict(status='observed', sessionId='s', pathKnown=True, x=1100, y=300))
                return self.reply(dict(status='error', sessionId='s', message='No legal fixture plan'), 409)

            def do_POST(self):
                parsed = urlsplit(self.path)
                query = parse_qs(parsed.query)
                if parsed.path in ('/command/move', '/command/attack-move', '/command/build-extractor'):
                    ids = list(map(int, query.get('unitIds', query.get('unitId'))[0].split(',')))
                    x, y = float(query['x'][0]), float(query['y'][0])
                    orders.append(dict(path=parsed.path, unitIds=ids, x=x, y=y, tick=tick[0], gameTimeMs=now()))
                    kind = 'attackMove' if parsed.path == '/command/attack-move' else 'move'
                    for uid in ids:
                        motions[uid] = dict(orderType=kind, orderX=x, orderY=y)
                    if parsed.path == '/command/build-extractor':
                        structures.append(dict(id=500 + len(structures), x=x, y=y))
                    return self.reply(dict(status='queued', sessionId='s', requestId=query['requestId'][0],
                                           unitId=ids[0], unitIds=ids, frame=tick[0], targetX=x, targetY=y, orderType=kind))
                return self.reply(dict(status='error', sessionId='s', message='Unsupported fixture order'), 409)

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as cwd:
                command = ['java', '-Dfile.encoding=UTF-8', '-Drwagent.pollMs=60',
                           '-Drwagent.reconEnabled=false', '-Drwagent.reachabilitySample=false',
                           '-Drwagent.mineTarget=1', '-Drwagent.landFactoryTarget=1',
                           '-Drwagent.port=' + str(server.server_port), '-cp', JAR,
                           'io.rwagent.client.BattleClient', '300']
                process = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE,
                                         stderr=subprocess.STDOUT, timeout=25)
                reports = list(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                rows = [json.loads(line) for line in reports[0].read_text(encoding='utf-8').splitlines()] if reports else []
                evidence = os.environ.get('RW_SPECIALIST_LIFECYCLE_EVIDENCE_DIR')
                if evidence:
                    directory = pathlib.Path(evidence)
                    directory.mkdir(parents=True, exist_ok=True)
                    (directory / (scenario + '.json')).write_text(json.dumps(dict(
                        scenario=scenario, agentJar=JAR, exitCode=process.returncode,
                        orders=orders, observations=observations, events=rows), ensure_ascii=False, indent=2), encoding='utf-8')
                    (directory / (scenario + '.stdout.txt')).write_bytes(process.stdout)
                self.assertEqual(process.returncode, 0, process.stdout.decode('utf-8', errors='replace'))
                self.assertTrue(rows, 'BattleClient must commit its report')
                self.assertEqual(rows[-1]['data']['matchOutcome'], 'DEFEAT')
                self.assertFalse(self.events(rows, 'command_rejected'), 'The fixture must support all issued commands')
                if scenario != 'return_home':
                    self.assertTrue(self.events(rows, 'capability_need_created'))
                return orders, rows
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    @staticmethod
    def events(rows, kind):
        return [row['data'] for row in rows if row['event'] == kind]

    def test_unexecutable_need_keeps_engineer_out_of_ordinary_economy(self):
        orders, rows = self.run_case('economic_isolation')
        self.assertFalse([order for order in orders if 9 in order['unitIds']
                          and order['path'] in ('/command/build-extractor', '/command/move')],
                         'A capability specialist must not be repurposed for ordinary expansion or prospecting')
        self.assertTrue([order for order in orders if order['unitIds'] == [8]
                         and order['path'] == '/command/build-extractor'],
                        'Ordinary extra builders must retain their independent economic work')
        self.assertFalse([event for event in self.events(rows, 'strategy_prospect_ordered') if event['unitId'] == 9])

    def test_arrived_hidden_contact_exhausts_investigation_without_false_completion(self):
        orders, rows = self.run_case('lost_contact')
        exhausted = self.events(rows, 'strategy_investigation_exhausted')
        self.assertEqual(len(exhausted), 1, 'A legally unknown site must have one bounded investigation')
        self.assertGreaterEqual(exhausted[0]['gameTimeMs'], 32000 + 15000)
        self.assertLessEqual(exhausted[0]['gameTimeMs'], 32000 + 24000)
        self.assertFalse(self.events(rows, 'capability_need_resolved'), 'Missing contact remains UNKNOWN')
        self.assertFalse(self.events(rows, 'capability_need_released'), 'Unseen is not cleared')
        self.assertFalse([event for event in self.events(rows, 'strategy_task_blocked')
                          if event.get('reason') == 'NO_OBSERVED_PROGRESS'])
        self.assertEqual(rows[-1]['data']['strategy']['needsResolvedByLegalEvidence'], 0)
        self.assertEqual(rows[-1]['data']['strategy']['capabilityNeedsAtEnd'], 1)
        self.assertFalse([order for order in orders if order['path'] == '/command/attack-move'
                          and order['unitIds'] == [9] and order['gameTimeMs'] >= 32000],
                         'Arrival in fog must not keep replacing an already completed native order')

    def test_fresh_visible_contact_can_reassign_an_exhausted_investigation(self):
        orders, rows = self.run_case('fresh_contact')
        self.assertTrue(self.events(rows, 'strategy_investigation_exhausted'))
        assignments = [event for event in self.events(rows, 'strategy_task_assigned') if event['unitId'] == 9]
        self.assertTrue([event for event in assignments if event['gameTimeMs'] >= 100000],
                        'A newer legal observation must allow the same specialist to serve the unresolved need again')
        self.assertTrue([order for order in orders if order['unitIds'] == [9]
                         and order['path'] == '/command/attack-move' and order['gameTimeMs'] >= 100000])
        self.assertFalse(self.events(rows, 'capability_need_resolved'))

    def test_return_at_home_is_quiet_but_interrupted_far_return_recovers(self):
        orders, rows = self.run_case('return_home')
        returns = [order for order in orders if order['path'] == '/command/move' and order['unitIds'][0] in (8, 9)]
        self.assertFalse([order for order in returns if order['gameTimeMs'] < 32000 or order['gameTimeMs'] >= 72000],
                         'Low-health workers already at home must not repeatedly consume the command slot')
        for uid in (8, 9):
            self.assertGreaterEqual(len([order for order in returns if order['unitIds'] == [uid]]), 2,
                                    'A cleared native return order far from home must be restored for unit ' + str(uid))


if __name__ == '__main__':
    unittest.main()
