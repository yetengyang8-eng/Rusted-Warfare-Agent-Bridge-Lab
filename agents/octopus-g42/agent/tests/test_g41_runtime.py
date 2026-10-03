"""G4.1 actual BattleClient main-loop HTTP fixtures, never native match acceptance.

The legal packet shapes follow test_local_army/test_g4_runtime. Queue effects,
ready births, positions, deaths and result screen are explicitly synthetic. The
client, observation validation, owner transitions and HTTP dispatch are real.
No original fixture or runtime source is patched by this suite.
"""
import http.server
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from urllib.parse import parse_qs, urlsplit

JAR = str(pathlib.Path(sys.argv.pop(1)).resolve())
JAVA13 = pathlib.Path(__file__).resolve().parents[4] / '游戏环境/P1F-GPTSol61-ProductionCapacity-2026-10-02/jvm64/bin/java.exe'
JAVA = os.environ.get('RW_JAVA') or (str(JAVA13) if JAVA13.exists() else 'java')
CATALOG_SHA = '263cdaaa3e923e8307c37f059e50bee529b8ecd7c3e215937ce2adf615a0e236'
KNOWLEDGE = 'fixture-static-map:fixed-resource:1'
RESOURCE_TILE = 75005


class G41RuntimeTests(unittest.TestCase):
    maxDiff = 10000

    @staticmethod
    def events(rows, event):
        return [row['data'] for row in rows if row['event'] == event]

    def run_case(self, label, *, static=False, approach='known', raid=False,
                 visible_resource=False, deaths=False, trace=True, ticks=30,
                 cancel_need_same_observation=False):
        frame = 0
        credits = 100000
        next_actor = 501
        production = None
        tanks, moves = {}, []
        orders, reads, effects = [], [], []
        initial_ordinary = None

        def now():
            return frame * 2500

        def unit(uid, kind, x=100, y=100):
            building = kind in ('commandCenter', 'landFactory') or kind.startswith('extractor')
            return dict(id=uid, type=kind, x=x, y=y, hp=1000, maxHp=1000,
                        dead=False, buildProgress=1, mobile=not building,
                        canAttack=kind == 'heavyTank', building=building,
                        techLevel=2 if kind == 'landFactory' else 1,
                        productionQueue=0 if kind == 'landFactory' else -1, orderType=None)

        builder = unit(2, 'builder', 140, 100)

        def own():
            factory = unit(50, 'landFactory', 180, 160)
            factory['productionQueue'] = int(production is not None and frame > production['acceptedFrame'])
            return [unit(1, 'commandCenter'), dict(builder), factory] + [dict(u) for u in tanks.values()]

        def contacts():
            # First FREE-born response can become pending; the later single attached
            # member detaches during the production pause, then visibly clears FREE.
            if raid and frame in (4, 5, 10, 11):
                return [dict(id=970, type='tank', x=110, y=100, hp=100, maxHp=100,
                             dead=False, building=False, canAttack=True, targetDomain='SURFACE',
                             touchingWater=False, lastSeenGameTimeMs=now(),
                             domainObservedAtGameTimeMs=now())]
            return []

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, body, status=200):
                data = json.dumps(body).encode('utf-8')
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                nonlocal frame, production, next_actor, initial_ordinary
                parsed = urlsplit(self.path)
                query = parse_qs(parsed.query)
                reads.append(dict(path=parsed.path, query=query, frame=frame, gameTimeMs=now()))
                if parsed.path == '/health':
                    return self.reply(dict(status='ok', version='0.07-alpha1', strategyContractVersion=1))
                if parsed.path == '/state':
                    frame += 1
                    if production is not None and frame >= production['acceptedFrame'] + 2:
                        uid = next_actor
                        next_actor += 1
                        tanks[uid] = unit(uid, 'heavyTank', 260, 100)
                        effects.append(dict(kind='EXPLICIT_READY_BIRTH_AFTER_HTTP_QUEUE', frame=frame,
                                            unitId=uid, queueAcceptedFrame=production['acceptedFrame']))
                        production = None
                    for motion in list(moves):
                        if frame >= motion['applyFrame']:
                            for uid in motion['actors']:
                                actor = builder if uid == 2 else tanks.get(uid)
                                if actor is not None:
                                    actor.update(x=motion['x'], y=motion['y'], orderType=None)
                            effects.append(dict(kind='EXPLICIT_OWN_POSITION_AFTER_HTTP_MOVE', frame=frame, **motion))
                            moves.remove(motion)
                    if deaths and frame == 20:
                        effects.append(dict(kind='EXPLICIT_ALL_ORDINARY_DEATH', frame=frame, actors=list(tanks)))
                        tanks.clear()
                    if cancel_need_same_observation and frame == 8:
                        # A legal own-state position sample, explicitly synthetic: the
                        # old live Need is collected first, then economy's independent
                        # current-site 409 cancels it at this same rally observation.
                        builder.update(x=1500, y=100, orderType=None)
                        effects.append(dict(kind='EXPLICIT_BUILDER_AT_STATIC_RALLY', frame=frame,
                                            unitId=2, x=1500, y=100))
                    current = own()
                    if initial_ordinary is None:
                        initial_ordinary = [u['id'] for u in current if u['canAttack']]
                    terminal = frame >= ticks
                    return self.reply(dict(status='running', sessionId='s', frame=frame, gameTimeMs=now(),
                        networked=False, replay=False, player=dict(teamId=0, credits=credits),
                        map=dict(width=10000, height=10000, tilesWide=500, tilesHigh=500, tileWidth=20, tileHeight=20),
                        match=dict(outcome='DEFEAT' if terminal else 'ONGOING', nativeDefeat=terminal,
                                   nativeVictory=False, source='native_result_screen'), ownUnits=current))
                if parsed.path == '/combat/observe':
                    return self.reply(dict(status='observed', sessionId='s', frame=frame, gameTimeMs=now(),
                        catalogSha256=CATALOG_SHA, catalogGameJarMatched=True, visibleEnemies=contacts(),
                        rememberedEnemies=contacts(), enemyIntel=[], rememberedBuildings=[]))
                if parsed.path == '/combat/engagement':
                    target = next((u for u in contacts() if u['id'] == int(query['targetId'][0])), None)
                    actors = [dict(unitId=int(uid), compatibility='COMPATIBLE', status='APPROACH_PATH_KNOWN',
                                   lastKnownPositionApproachStatus='APPROACH_PATH_KNOWN', approachX=110, approachY=100)
                              for uid in query['unitIds'][0].split(',')]
                    return self.reply(dict(status='observed', sessionId='s', frame=frame, gameTimeMs=now(),
                        targetId=int(query['targetId'][0]), targetVisible=target is not None, actors=actors,
                        targetX=110, targetY=100, targetObservedAtGameTimeMs=now()))
                if parsed.path == '/combat/production':
                    paused = raid and 3 <= frame <= 12
                    actions = [] if paused else [dict(actionId='heavy', type='heavyTank', cost=800, affordable=credits >= 800)]
                    return self.reply(dict(status='observed', sessionId='s', frame=frame, gameTimeMs=now(),
                        factories=[dict(id=50, type='landFactory', tier=2,
                            queue=int(production is not None and frame > production['acceptedFrame']), actions=actions)]))
                if parsed.path == '/scout/observe':
                    resources = [dict(tile=RESOURCE_TILE, x=1500, y=100, visible=True)] if visible_resource else []
                    return self.reply(dict(status='observed', sessionId='s', frame=frame, gameTimeMs=now(),
                        newlyObservedTiles=0, resources=resources, visibleThreats=[], rememberedThreats=[]))
                if parsed.path == '/scout/visible':
                    return self.reply(dict(status='observed', sessionId='s', tiles=[RESOURCE_TILE] if visible_resource else []))
                if parsed.path == '/static-map/observe':
                    return self.reply(dict(status='KNOWN' if static else 'UNKNOWN', sessionId='s', frame=frame,
                        gameTimeMs=now(), staticOnly=True, knowledgeId=KNOWLEDGE,
                        resourceTiles=[dict(tile=RESOURCE_TILE, x=1500, y=100)] if static else [],
                        safe='UNKNOWN', enemyOccupancy='UNKNOWN', buildable='UNKNOWN'))
                if parsed.path == '/static-map/approach':
                    uid = int(query['unitId'][0])
                    actor = builder if uid == 2 else tanks.get(uid, unit(uid, 'heavyTank'))
                    return self.reply(dict(status='UNKNOWN' if approach == 'unknown' else 'KNOWN', sessionId='s',
                        frame=frame, gameTimeMs=now(), staticOnly=True, staticPathKnown=approach != 'unknown',
                        knowledgeId='foreign-static-packet' if approach == 'wrong_knowledge' else KNOWLEDGE,
                        unitId=uid, targetTile=RESOURCE_TILE, waypointX=min(actor['x'] + 300, 1200), waypointY=100,
                        x=1500, y=100, safe='UNKNOWN', enemyOccupancy='UNKNOWN', dynamicReachability='UNKNOWN', buildable='UNKNOWN'))
                if parsed.path == '/expansion/plan':
                    # Visibility and approach acceptance do not replace this independent
                    # current-site planner, which explicitly refuses this fixture site.
                    return self.reply(dict(status='rejected', sessionId='s', reason='FIXTURE_CURRENT_NATIVE_SITE_NOT_LEGAL'), 409)
                if parsed.path == '/scout/plan':
                    uid = int(query.get('unitId', ['0'])[0])
                    return self.reply(dict(status='planned', sessionId='s', frame=frame, gameTimeMs=now(),
                        pathKnown=True, anchorUnitId=uid, targetX=2500, targetY=700, targetTile=125035))
                if parsed.path in ('/economy/investments', '/combat/capabilities'):
                    return self.reply(dict(status='observed', sessionId='s', units=[], capabilities=[]))
                return self.reply(dict(status='no_frontier', sessionId='s', frame=frame, gameTimeMs=now()))

            def do_POST(self):
                nonlocal credits, production
                parsed = urlsplit(self.path)
                query = parse_qs(parsed.query)
                ids = [int(uid) for uid in query.get('unitIds', query.get('unitId', ['0']))[0].split(',')]
                order = dict(path=parsed.path, unitIds=ids, frame=frame, gameTimeMs=now(),
                             x=float(query.get('x', ['0'])[0]), y=float(query.get('y', ['0'])[0]),
                             requestId=query.get('requestId', [None])[0], accepted=False)
                orders.append(order)
                if parsed.path == '/command/queue':
                    if ids != [50] or query.get('actionId') != ['heavy'] or production is not None or credits < 800:
                        return self.reply(dict(status='rejected', sessionId='s', reason='FIXTURE_QUEUE_GUARD'), 409)
                    order.update(actionId='heavy', quotedNativeCost=800, creditsBefore=credits)
                    credits -= 800
                    order['creditsAfter'] = credits
                    production = dict(acceptedFrame=frame)
                elif parsed.path in ('/command/move', '/command/attack-move'):
                    if any(uid != 2 and uid not in tanks for uid in ids):
                        return self.reply(dict(status='rejected', sessionId='s', reason='FIXTURE_OWN_ACTOR_GUARD'), 409)
                    for uid in ids:
                        actor = builder if uid == 2 else tanks[uid]
                        actor.update(orderType='move' if parsed.path == '/command/move' else 'attackMove',
                                     orderX=order['x'], orderY=order['y'])
                    if parsed.path == '/command/move':
                        moves.append(dict(applyFrame=frame + 2, actors=ids, x=order['x'], y=order['y'], receiptFrame=frame))
                else:
                    return self.reply(dict(status='rejected', sessionId='s', reason='FIXTURE_NO_LEGAL_CONSTRUCTION'), 409)
                order['accepted'] = True
                return self.reply(dict(status='queued', sessionId='s', requestId=query['requestId'][0],
                    frame=frame, unitId=ids[0], unitIds=ids, targetX=order['x'], targetY=order['y'],
                    orderType='move' if parsed.path == '/command/move' else 'attackMove'))

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory(prefix='rw-g41-http-') as cwd:
                command = [JAVA, '-Dfile.encoding=UTF-8', '-Drwagent.pollMs=60', '-Drwagent.g3Execution=true',
                    '-Drwagent.g4Forces=true', '-Drwagent.earlyOperations=true', '-Drwagent.globalStrategy=false',
                    '-Drwagent.reconEnabled=false', '-Drwagent.reachabilitySample=false', '-Drwagent.landFactoryTarget=1',
                    '-Drwagent.g1Trace=' + str(trace).lower(), '-Drwagent.port=' + str(server.server_port),
                    '-cp', JAR, 'io.rwagent.client.BattleClient', '180']
                started = time.perf_counter()
                result = subprocess.run(command, cwd=cwd, capture_output=True, timeout=35)
                elapsed = time.perf_counter() - started
                reports = list(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                raw = reports[0].read_bytes() if reports else b''
                rows = [json.loads(line) for line in raw.splitlines()]
                details = dict(label=label, evidence='E2_HTTP_MAIN_LOOP_SYNTHETIC_QUEUE_BIRTH_POSITION_NO_NATIVE_MATCH',
                    initialOrdinaryActors=initial_ordinary, orders=orders, reads=reads, effects=effects,
                    command=command, elapsedWallSeconds=elapsed, exitCode=result.returncode, initialCredits=100000, finalCredits=credits)
                evidence = os.environ.get('RW_G41_RUNTIME_EVIDENCE_DIR')
                if evidence:
                    dest = pathlib.Path(evidence) / label
                    dest.mkdir(parents=True, exist_ok=True)
                    (dest / 'battle.jsonl').write_bytes(raw)
                    (dest / 'fixture.json').write_text(json.dumps(details, ensure_ascii=False, indent=2), encoding='utf-8')
                    (dest / 'process.txt').write_bytes(result.stdout + result.stderr)
                self.assertEqual(result.returncode, 0, (result.stdout + result.stderr).decode('utf-8', errors='replace'))
                self.assertTrue(rows, 'actual BattleClient must produce a report')
                self.assertEqual(initial_ordinary, [], 'entry observation has no ready ordinary force')
                self.assertFalse([o for o in orders if not o['accepted']], 'client may not fabricate construction or double-spend fixture queue')
                return rows, details
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def assert_real_join_and_activation(self, rows, details):
        transitions = self.events(rows, 'g4_force_transition')
        births = [t for t in transitions if t['reason'] == 'GENERAL_FORMATION_CREATED']
        self.assertTrue(births)
        self.assertEqual(births[0]['after']['desiredStrength'], 24)
        self.assertEqual(births[0]['after']['members'], [])
        self.assertEqual(births[0]['after']['phase'], 'FORMING')
        arrivals = [t for t in transitions if t['reason'] == 'JOIN_ARRIVAL_OWN_POSITION_WITNESSED']
        self.assertGreaterEqual(len(arrivals), 6)
        for arrival in arrivals:
            self.assertGreater(arrival['sourceFrame'], arrival['before']['joinAcceptedFrame'])
            self.assertEqual(arrival['before']['membership'], 'JOINING')
            self.assertEqual(arrival['after']['membership'], 'ATTACHED')
            self.assertGreater(arrival['after']['ownerGeneration'], arrival['before']['ownerGeneration'])
        activation = next(t for t in transitions if t['reason'] == 'GENERAL_FORMATION_ACTIVE')
        self.assertEqual(activation['after']['healthyAttachedStrength'], 6)
        self.assertEqual(activation['before']['generalId'], activation['after']['generalId'])
        states = self.events(rows, 'g4_force_state')
        for count in range(1, 6):
            self.assertTrue([g for s in states for g in s['generals'] if g['phase'] == 'FORMING' and len(g['members']) == count], count)
        frontier = [r for r in details['reads'] if r['path'] == '/scout/plan' and r['query'].get('role') == ['army']]
        self.assertTrue(frontier, 'mature General must use existing lawful native frontier planner')
        self.assertTrue(all(r['frame'] >= activation['sourceFrame'] for r in frontier), 'one-to-five FORMING cannot use mature frontier')
        self.assertTrue([o for o in details['orders'] if o['path'] == '/command/queue'])
        return activation

    def test_empty_force_real_production_forms_and_activates_at_six_later_arrivals(self):
        rows, details = self.run_case('zero_to_six')
        self.assert_real_join_and_activation(rows, details)
        executions = self.events(rows, 'g3_execution')
        queued = [x for x in executions if x['nativeAccepted'] and x['kind'] == '/command/queue']
        self.assertTrue(queued)
        self.assertTrue(all(x['costSourceRequestPath'] == '/combat/production' and x['costSourceObservationId'] for x in queued))
        self.assertTrue(self.events(rows, 'g3_credit_settled'), 'later actual HTTP queue effect settles accepted spending')

    def test_unseen_static_resource_builder_screen_and_production_optional_order(self):
        rows, details = self.run_case('static_resource', static=True)
        self.assert_real_join_and_activation(rows, details)
        needs = self.events(rows, 'early_expansion_transition')
        self.assertTrue(needs)
        self.assertTrue(all(t['after']['safe'] == 'UNKNOWN' and t['after']['enemyOccupancy'] == 'UNKNOWN' for t in needs))
        accepted = [x for x in self.events(rows, 'g3_execution') if x['nativeAccepted']]
        builder = [x for x in accepted if x['lane'] == 'EARLY_EXPANSION']
        screen = [x for x in accepted if x['lane'] == 'FORMING_SCREEN']
        self.assertTrue(builder)
        self.assertTrue(screen)
        self.assertTrue(all(x['actorIds'] == [2] for x in builder))
        self.assertTrue(all(len(x['actorIds']) == 1 and x['owner'].startswith('general:') for x in screen))
        # Price sources use quote IDs and movement uses state IDs. Link the actual
        # successful receipt requestId to the server's POST sequence instead.
        indexed = {o['requestId']: (index, o) for index, o in enumerate(details['orders'])}
        same_tick = []
        for execution in builder + screen:
            index, optional = indexed[execution['receipt']['requestId']]
            self.assertEqual(optional['path'], '/command/move')
            self.assertEqual(optional['unitIds'], execution['actorIds'])
            production = [(i, o) for i, o in enumerate(details['orders'])
                          if o['frame'] == optional['frame'] and o['path'] == '/command/queue']
            if production:
                self.assertTrue(all(i < index for i, _ in production), 'production POST precedes every optional move in a shared observation')
                same_tick.append(optional['frame'])
        self.assertTrue(same_tick, 'actual optional move must share a tick with an earlier production POST')
        first_screen = min(indexed[x['receipt']['requestId']][1]['frame'] for x in screen)
        self.assertTrue([o for o in details['orders'] if o['path'] == '/command/queue' and o['frame'] > first_screen], 'ordinary production continues after FORMING support starts')
        self.assertFalse([t for t in needs if t['after']['state'] in ('LEGAL_SITE_OBSERVED', 'CONSTRUCTION_OBSERVED', 'COMPLETE')])

    def test_forming_free_and_detached_response_clear_then_later_membership(self):
        rows, details = self.run_case('forming_raids', raid=True)
        self.assert_real_join_and_activation(rows, details)
        transitions = self.events(rows, 'g4_force_transition')
        pending_lr = [t for t in transitions if t['reason'] == 'JOIN_RESERVED' and t['after']['temporaryTask'] == 'LOCAL_RESPONSE']
        self.assertTrue(pending_lr, 'FREE-born local responder may carry a formal pending reservation')
        clears = [t for t in transitions if t['reason'] == 'LOCAL_RESPONSE_CLEARED_JOINING']
        self.assertTrue(clears)
        self.assertTrue(all(t['after']['owner'].startswith('join:') for t in clears))
        detached = [t for t in transitions if t['reason'] == 'LOCAL_RESPONSE_STARTED' and t['after']['localResponseDetachedFromGeneral']]
        self.assertTrue(detached)
        free = [t for t in transitions if t['reason'] == 'LOCAL_RESPONSE_CLEARED_FREE' and t['before']['localResponseDetachedFromGeneral']]
        self.assertTrue(free)
        for clear in free:
            self.assertEqual(clear['after']['owner'], 'force:free')
            self.assertEqual(clear['after']['membership'], 'UNATTACHED')
            later = next(t for t in transitions if t['reason'] == 'JOIN_RESERVED' and t['unitId'] == clear['unitId'] and t['sourceFrame'] > clear['sourceFrame'])
            self.assertGreater(later['sourceFrame'], clear['sourceFrame'])
        self.assertFalse([r for r in details['reads'] if r['path'] == '/combat/engagement' and r['frame'] not in (4, 5, 10, 11)], 'lost target has no last-known chase')

    def test_visibility_and_receipt_do_not_override_current_native_site_refusal(self):
        rows, details = self.run_case('visible_but_illegal', static=True, visible_resource=True, ticks=20)
        self.assertTrue([x for x in self.events(rows, 'g3_execution') if x['nativeAccepted'] and x['lane'] == 'EARLY_EXPANSION'])
        self.assertTrue([r for r in details['reads'] if r['path'] == '/expansion/plan'])
        self.assertFalse([o for o in details['orders'] if o['path'] in ('/command/build-extractor', '/command/construct')])
        self.assertFalse([t for t in self.events(rows, 'early_expansion_transition') if t['after']['state'] in ('LEGAL_SITE_OBSERVED', 'CONSTRUCTION_OBSERVED', 'COMPLETE')])

    def test_unknown_and_wrong_static_knowledge_cannot_issue_optional_movement(self):
        for mode in ('unknown', 'wrong_knowledge'):
            with self.subTest(mode=mode):
                rows, details = self.run_case('static_' + mode, static=True, approach=mode, ticks=18)
                self.assertTrue([r for r in details['reads'] if r['path'] == '/static-map/approach'])
                self.assertFalse(self.events(rows, 'early_expansion_transition'))
                self.assertFalse([x for x in self.events(rows, 'g3_execution') if x['nativeAttempted'] and x['lane'] in ('EARLY_EXPANSION', 'FORMING_SCREEN')])
                self.assertTrue([o for o in details['orders'] if o['path'] == '/command/queue'], 'UNKNOWN does not disable unrelated lawful production')

    def test_all_ordinary_death_invalidates_then_new_ready_free_births_new_id(self):
        rows, details = self.run_case('death_rebirth', deaths=True)
        transitions = self.events(rows, 'g4_force_transition')
        born = [t for t in transitions if t['reason'] == 'GENERAL_FORMATION_CREATED']
        self.assertGreaterEqual(len(born), 2)
        self.assertGreater(born[1]['after']['generalId'], born[0]['after']['generalId'])
        self.assertEqual(born[1]['after']['members'], [])
        self.assertTrue([t for t in transitions if t['reason'] == 'GENERAL_INVALIDATED' and t['sourceFrame'] == 20])
        self.assertTrue([e for e in details['effects'] if e['kind'] == 'EXPLICIT_ALL_ORDINARY_DEATH'])

    def test_deferred_screen_cancelled_need_same_observation_never_dispatches(self):
        rows, details = self.run_case('deferred_screen_cancelled_need', static=True,
                                      cancel_need_same_observation=True, ticks=18)
        collected = [(index, row) for index, row in enumerate(rows)
                     if row['event'] == 'g4_force_collected'
                     and row['data']['intent']['lane'] == 'FORMING_SCREEN'
                     and row['trace']['observation']['sourceFrame'] == 8]
        self.assertTrue(collected, 'the stale screen must really be collected before economy cancels its Need')
        need_cancel = [(index, row['data']) for index, row in enumerate(rows)
                       if row['event'] == 'early_expansion_transition'
                       and row['data']['sourceFrame'] == 8
                       and row['data']['reason'] == 'CURRENT_NATIVE_SITE_REFUSED_AT_STATIC_RALLY']
        self.assertEqual(len(need_cancel), 1)
        cancel_index, need = need_cancel[0]
        self.assertEqual(need['before']['state'], 'APPROACHING')
        self.assertEqual(need['after']['state'], 'CANCELLED')
        self.assertTrue([r for r in details['reads'] if r['path'] == '/expansion/plan' and r['frame'] == 8])
        for collect_index, collection in collected:
            intent = collection['data']['intent']
            self.assertLess(collect_index, cancel_index)
            cancelled = [(index, row) for index, row in enumerate(rows)
                         if row['event'] == 'g4_force_cancelled'
                         and row['data']['intent']['intentId'] == intent['intentId']]
            self.assertEqual(len(cancelled), 1)
            dispatch_index, cancellation = cancelled[0]
            self.assertGreater(dispatch_index, cancel_index)
            self.assertEqual(cancellation['trace']['observation']['sourceFrame'], 8)
            self.assertEqual(cancellation['data']['reason'], 'EXPANSION_NEED_NO_LONGER_CURRENT')
            self.assertFalse(cancellation['data']['nativeAttempted'])
            self.assertEqual(cancellation['data']['currentExpansionNeed']['state'], 'CANCELLED')
            self.assertEqual(cancellation['data']['inputEvidence']['expansionNeed']['tile'], need['after']['tile'])
            # The captured Intent is discarded before scheduler admission, hence it
            # cannot consume a command token or be rebuilt into a fresh command.
            self.assertFalse([row for row in rows if row['event'] in ('g3_intent', 'g3_execution')
                              and row['data'].get('intentId') == intent['intentId']])
            self.assertFalse([o for o in details['orders'] if o['frame'] == 8
                              and o['path'] == intent['kind'] and o['unitIds'] == intent['actorIds']])
        self.assertTrue([o for o in details['orders'] if o['frame'] == 8 and o['path'] == '/command/queue'],
                        'same-observation Need cancellation must retain ordinary production')
        self.assertTrue([o for o in details['orders'] if o['frame'] > 8 and o['path'] == '/command/queue'])
        self.assertTrue([e for e in details['effects'] if e['kind'] == 'EXPLICIT_BUILDER_AT_STATIC_RALLY'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
