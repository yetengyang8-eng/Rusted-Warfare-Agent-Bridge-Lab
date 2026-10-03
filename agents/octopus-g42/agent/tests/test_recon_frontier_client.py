"""Deterministic Frontier Recon policy tests against a legal-observation HTTP bridge.

The bridge reports only own units, currently visible enemies, and map-memory
summaries. A command receipt never changes the map-memory result by itself:
the fixture first exposes the scout's own move order and position progress.
"""

import http.server
import hashlib
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


class FrontierReconPolicyTests(unittest.TestCase):
    SCOUT_ID = 20
    FRONTIER_TILE = 1221
    FRONTIER_X = 230.0
    FRONTIER_Y = 230.0
    ROUTE_TILES = tuple([c * 110 + 5 for c in range(5, 12)] +
                        [11 * 110 + r for r in range(6, 12)])
    SECOND_FRONTIER_TILE = 1887
    SECOND_ROUTE_TILES = tuple([c * 110 + 11 for c in range(11, 18)] +
                               [17 * 110 + r for r in range(12, 18)])
    PENDING_SECOND_FRONTIER_TILE = 1888
    PENDING_SECOND_ROUTE_TILES = tuple([c * 110 + 12 for c in range(11, 18)] +
                                       [17 * 110 + r for r in range(13, 19)])

    def run_case(self, *, heavy=False, emergency=False, disabled=False,
                 reveal_region=True, preempt_after_move=False,
                 reject_move=False, early_region_timestamp=False,
                 lost_site=False, persistent_frontier=False,
                 slow_first_leg=False, drift_before_first_order=False,
                 adjacent_tile_before_first_order=False,
                 lost_on_emergency=False, stale_adjacent_plan=False,
                 stale_forward_on_route=False, pending_emergency=False,
                 existing_expendable_pending=False,
                 moving_old_order=False, instant_completion=False,
                 intra_poll_refresh=False, drop_moves=False,
                 short_first_leg=False, between_decisions=False, game_step_ms=2000, region_delay=2,
                 polls=34):
        frame = [0]
        calls = []
        move_orders = []
        outbound_moves = []
        builder_orders = []
        recon_plan_requests = []
        actual_frontier = [self.FRONTIER_TILE]
        delay_for_builder = (drift_before_first_order or
                             adjacent_tile_before_first_order)

        def own_unit(uid, kind, x, y, hp, order=None, can_attack=True):
            result = dict(id=uid, type=kind, x=x, y=y, hp=hp, maxHp=1000,
                          dead=False, buildProgress=1, mobile=True,
                          canAttack=can_attack, building=False, techLevel=1,
                          productionQueue=0, orderType=None)
            if order is not None:
                result.update(orderType=order.get('orderType', 'move'), orderX=order['x'],
                              orderY=order['y'])
            return result

        def scout_position():
            if not move_orders:
                if moving_old_order:
                    return (100.0 + frame[0] * 40, 100.0,
                            dict(x=1800.0, y=100.0, orderType='attackMove'))
                if stale_adjacent_plan or stale_forward_on_route:
                    return ((110.0, 110.0, None) if recon_plan_requests
                            else (130.0, 100.0, None) if stale_forward_on_route
                            else (100.0, 80.0, None))
                if delay_for_builder and builder_orders:
                    return ((100.0, 130.0, None) if adjacent_tile_before_first_order
                            else (180.0, 130.0, None))
                return (119.5, 110.0, None) if short_first_leg else (100.0, 100.0, None)
            if (existing_expendable_pending and len(recon_plan_requests) >= 2
                    and len(outbound_moves) == 2):
                return 230.0, 250.0, None
            order = move_orders[-1]
            elapsed = frame[0] - order['frame']
            if elapsed <= 0:
                return order['startX'], order['startY'], None
            if between_decisions:
                if elapsed == 1:
                    return ((order['startX'] + order['x']) / 2,
                            (order['startY'] + order['y']) / 2, order)
                return order['x'], order['y'], None
            if instant_completion:
                return order['x'], order['y'], None
            first_leg = outbound_moves and order is outbound_moves[0]
            fraction = min(1.0, elapsed * (0.16 if slow_first_leg and first_leg
                                           else 0.35))
            x = order['startX'] + (order['x'] - order['startX']) * fraction
            y = order['startY'] + (order['y'] - order['startY']) * fraction
            return x, y, order

        def region_revealed():
            return bool(reveal_region and not preempt_after_move and
                        len(outbound_moves) >= (1 if moving_old_order else 2) and
                        frame[0] >= outbound_moves[0 if moving_old_order else 1]['frame'] +
                        (1 if intra_poll_refresh else region_delay))

        def emergency_now():
            return bool(emergency or (pending_emergency and recon_plan_requests
                                      and not outbound_moves) or
                        (preempt_after_move and
                        len(outbound_moves) >= 2 and
                        frame[0] >= outbound_moves[1]['frame'] + 2))

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, body, status=200):
                raw = json.dumps(body).encode('utf-8')
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def do_POST(self):
                parsed = urlsplit(self.path)
                query = parse_qs(parsed.query)
                calls.append(dict(method='POST', path=parsed.path,
                                  query=query, frame=frame[0]))
                receipt = dict(status='queued', sessionId='s', frame=frame[0],
                               requestId=query.get('requestId', ['fixture'])[0])
                if parsed.path == '/command/produce-builder':
                    builder_orders.append(frame[0])
                    receipt.update(unitId=3, type='builder')
                elif parsed.path == '/command/move':
                    x, y = float(query['x'][0]), float(query['y'][0])
                    if reject_move and (x != 100.0 or y != 100.0):
                        return self.reply(dict(message='fixture rejected move'), 409)
                    start_x, start_y, _ = scout_position()
                    order = dict(unitId=int(query['unitId'][0]),
                                 x=x, y=y, startX=start_x, startY=start_y,
                                 frame=frame[0])
                    if not drop_moves:
                        move_orders.append(order)
                        if ((order['x'] != 100.0 or order['y'] != 100.0) and
                                abs(x - start_x) + abs(y - start_y) > 1):
                            outbound_moves.append(order)
                    receipt.update(unitId=order['unitId'], targetX=order['x'],
                                   targetY=order['y'], orderType='move')
                elif parsed.path == '/command/attack-move':
                    ids = [int(value) for value in query['unitIds'][0].split(',')]
                    receipt.update(unitId=ids[0], unitIds=ids,
                                   targetX=float(query['x'][0]),
                                   targetY=float(query['y'][0]),
                                   orderType='attackMove')
                return self.reply(receipt)

            def do_GET(self):
                parsed = urlsplit(self.path)
                query = parse_qs(parsed.query)
                path = parsed.path
                calls.append(dict(method='GET', path=path,
                                  query=query, frame=frame[0]))
                now = frame[0] * game_step_ms
                if path == '/health':
                    return self.reply(dict(status='ok', version='0.07-alpha1'))
                if path == '/state':
                    frame[0] += 1
                    now = frame[0] * game_step_ms
                    terminal = frame[0] >= polls
                    own = [dict(id=3, type='commandCenter', x=100.0, y=100.0,
                                hp=1000, maxHp=1000, dead=False, buildProgress=1,
                                mobile=False, canAttack=False, building=True,
                                techLevel=1, productionQueue=0, orderType=None)]
                    for uid in range(20, 28):
                        if uid == 20 and lost_on_emergency and emergency_now():
                            continue
                        kind = 'heavyTank' if heavy and uid == 20 else 'c_tank'
                        hp = 400 if uid == 20 else 1000
                        x, y, order = 100.0, 100.0, None
                        if uid == 20:
                            x, y, order = scout_position()
                        ready = not (existing_expendable_pending and
                                     len(recon_plan_requests) >= 2 and uid == 27)
                        own.append(own_unit(uid, kind, x, y, hp, order,
                                            can_attack=ready))
                    return self.reply(dict(
                        status='running', sessionId='s', frame=frame[0],
                        gameTimeMs=now, networked=False, replay=False,
                        player=dict(credits=1000 if delay_for_builder else 0),
                        map=dict(width=2200, height=2200, tilesWide=110,
                                 tilesHigh=110, tileWidth=20, tileHeight=20),
                        match=dict(outcome='DEFEAT' if terminal else 'ONGOING',
                                   nativeDefeat=terminal, nativeVictory=False,
                                   source='native_result_screen'), ownUnits=own))
                if path == '/combat/observe':
                    far_enemy = dict(id=90, type='landFactory', x=1800.0,
                                     y=1700.0, hp=1000, maxHp=1000,
                                     dead=False, building=True, canAttack=False,
                                     lastSeenGameTimeMs=now)
                    near_enemy = dict(id=91, type='c_tank', x=180.0,
                                      y=100.0, hp=1000, maxHp=1000,
                                      dead=False, building=False, canAttack=True,
                                      lastSeenGameTimeMs=now)
                    intel = [dict(id=90, status='VISIBLE',
                                  lastKnownType='landFactory',
                                  firstSeenGameTimeMs=now,
                                  lastSeenGameTimeMs=now,
                                  lastKnownX=1800.0,
                                  lastKnownY=1700.0,
                                  lastKnownHp=1000,
                                  lastKnownBuilding=True,
                                  lastKnownCanAttack=False,
                                  lastKnownSiteVisible=True,
                                  clearedGameTimeMs=None)]
                    if lost_site:
                        intel.append(dict(id=70, status='LOST_CONTACT',
                                          lastKnownType='commandCenter',
                                          firstSeenGameTimeMs=500,
                                          lastSeenGameTimeMs=1000,
                                          lastKnownX=1500.0,
                                          lastKnownY=900.0,
                                          lastKnownHp=1000,
                                          lastKnownBuilding=True,
                                          lastKnownCanAttack=False,
                                          lastKnownSiteVisible=False,
                                          clearedGameTimeMs=None))
                    return self.reply(dict(
                        status='observed', sessionId='s', frame=frame[0],
                        gameTimeMs=now, visibleEnemies=[far_enemy],
                        rememberedEnemies=[near_enemy] if emergency_now() else [far_enemy],
                        enemyIntel=intel,
                        enemyIntelVisible=1,
                        enemyIntelLostContact=int(lost_site), enemyIntelCleared=0,
                        enemyIntelEvicted=0))
                if path == '/combat/production':
                    return self.reply(dict(status='observed', sessionId='s',
                                           factories=[]))
                if path == '/economy/builder-production' and delay_for_builder:
                    return self.reply(dict(status='planned', sessionId='s',
                                           producerId=3, builderCost=100,
                                           queueCount=0))
                if path == '/scout/observe':
                    requested_tile = int(query['tile'][0]) if 'tile' in query else None
                    revealed = (region_revealed() and requested_tile not in
                                (FrontierReconPolicyTests.SECOND_FRONTIER_TILE,
                                 FrontierReconPolicyTests.PENDING_SECOND_FRONTIER_TILE))
                    return self.reply(dict(
                        status='observed', sessionId='s', frame=frame[0],
                        gameTimeMs=now, fogEnabled=True, lineOfSightFog=True,
                        visibleTiles=120, exploredTiles=108 if revealed else 100,
                        newlyObservedTiles=8 if revealed else 0,
                        resources=[], visibleThreats=[], rememberedThreats=[],
                        region=dict(tile=actual_frontier[0],
                                    currentlyVisible=revealed,
                                    revealedDeepOrNeverSince=8 if revealed else 0,
                                    revealedStaleSince=0,
                                    latestRelevantRevealGameTimeMs=(
                                        outbound_moves[0]['frame'] * game_step_ms
                                        if revealed and early_region_timestamp else
                                        (outbound_moves[-1]['frame'] * game_step_ms + game_step_ms // 2)
                                        if revealed and intra_poll_refresh else
                                        now if revealed else -1))))
                if path == '/scout/plan' and query.get('role') == ['recon']:
                    recon_plan_requests.append(frame[0])
                    if region_revealed() and not (persistent_frontier or existing_expendable_pending):
                        return self.reply(dict(status='no_frontier', sessionId='s',
                                               reason='NO_HIGH_VALUE_RECON_FRONTIER'))
                    if short_first_leg:
                        actual_frontier[0] = 666
                        return self.reply(dict(
                            status='planned', sessionId='s', anchorUnitId=20,
                            anchorX=119.5, anchorY=110.0, targetX=130.0, targetY=130.0,
                            targetTile=666, frontierTile=666, routeTiles=[555, 665, 666],
                            frontierMemoryClass='DEEP_FOG', potentialInfoScore=120,
                            potentialNeverSeenTiles=0, potentialDeepFogTiles=24,
                            potentialStaleFogTiles=0, pathKnown=True, pathVisible=False))
                    if moving_old_order:
                        sx, sy, _ = scout_position()
                        col, row = int(sx // 20), int(sy // 20)
                        route = [col * 110 + r for r in range(row, row + 7)]
                        actual_frontier[0] = route[-1]
                        return self.reply(dict(
                            status='planned', sessionId='s', anchorUnitId=20,
                            anchorX=sx, anchorY=sy, targetX=(col + .5) * 20,
                            targetY=(row + 6.5) * 20, targetTile=route[-1],
                            frontierTile=route[-1], routeTiles=route,
                            frontierMemoryClass='DEEP_FOG', potentialInfoScore=120,
                            potentialNeverSeenTiles=0, potentialDeepFogTiles=24,
                            potentialStaleFogTiles=0, pathKnown=True, pathVisible=False))
                    second = region_revealed() and (persistent_frontier or existing_expendable_pending)
                    pending_second = second and existing_expendable_pending
                    return self.reply(dict(
                        status='planned', sessionId='s',
                        anchorUnitId=int(query['unitId'][0]),
                        anchorX=(230.0 if pending_second else 110.0
                                 if stale_adjacent_plan or stale_forward_on_route else 100.0),
                        anchorY=(250.0 if pending_second else 110.0
                                 if stale_adjacent_plan or stale_forward_on_route else 100.0),
                        targetX=350.0 if second else FrontierReconPolicyTests.FRONTIER_X,
                        targetY=(370.0 if pending_second else 350.0 if second
                                 else FrontierReconPolicyTests.FRONTIER_Y),
                        targetTile=(FrontierReconPolicyTests.PENDING_SECOND_FRONTIER_TILE
                                    if pending_second else FrontierReconPolicyTests.SECOND_FRONTIER_TILE
                                    if second else FrontierReconPolicyTests.FRONTIER_TILE),
                        frontierTile=(FrontierReconPolicyTests.PENDING_SECOND_FRONTIER_TILE
                                      if pending_second else FrontierReconPolicyTests.SECOND_FRONTIER_TILE
                                      if second else FrontierReconPolicyTests.FRONTIER_TILE),
                        frontierMemoryClass='DEEP_FOG',
                        potentialNeverSeenTiles=0,
                        potentialDeepFogTiles=24,
                        potentialStaleFogTiles=0,
                        potentialInfoScore=120,
                        pathKnown=True, pathVisible=False,
                        routeTiles=list(FrontierReconPolicyTests.PENDING_SECOND_ROUTE_TILES
                                        if pending_second else FrontierReconPolicyTests.SECOND_ROUTE_TILES
                                        if second else FrontierReconPolicyTests.ROUTE_TILES)))
                if path == '/scout/plan':
                    return self.reply(dict(status='no_frontier', sessionId='s',
                                           reason='NO_REACHABLE_UNVISITED_FRONTIER'))
                return self.reply(dict(status='no_frontier', sessionId='s'))

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory(dir=pathlib.Path.cwd()) as cwd:
                args = ['java', '-Drwagent.pollMs=60',
                        '-Drwagent.reachabilitySample=false',
                        '-Drwagent.builderTarget=' +
                        ('1' if delay_for_builder else '0'),
                        '-Drwagent.port=' + str(server.server_port)]
                if disabled:
                    args.append('-Drwagent.reconFrontierEnabled=false')
                completed = subprocess.run(
                    args + ['-cp', JAR, 'io.rwagent.client.BattleClient', '120'],
                    cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    timeout=30)
                self.assertEqual(completed.returncode, 0,
                                 completed.stdout.decode('utf-8', errors='replace'))
                reports = list(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                self.assertEqual(len(reports), 1)
                rows = [json.loads(line) for line in
                        reports[0].read_text(encoding='utf-8').splitlines()]
                if os.environ.get('RWAGENT_TEST_EVIDENCE_DIR'):
                    target = pathlib.Path(os.environ['RWAGENT_TEST_EVIDENCE_DIR']) / self._testMethodName
                    target.mkdir(parents=True, exist_ok=True)
                    (target / 'trace.jsonl').write_text(reports[0].read_text(encoding='utf-8'), encoding='utf-8')
                    (target / 'fixture.json').write_text(json.dumps(dict(
                        evidenceClass='E2_HTTP_SIMULATION_NOT_NATIVE', jar=JAR,
                        jarSha256=hashlib.sha256(pathlib.Path(JAR).read_bytes()).hexdigest(),
                        gameStepMs=game_step_ms, betweenDecisions=between_decisions,
                        shortFirstLeg=short_first_leg,
                        movingOldOrder=moving_old_order, instantCompletion=instant_completion,
                        intraPollRefresh=intra_poll_refresh, dropMoves=drop_moves,
                        calls=calls), indent=2) + '\n', encoding='utf-8')
                return calls, rows
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    @staticmethod
    def events(rows, event):
        return [row['data'] for row in rows if row['event'] == event]

    def test_moving_actor_is_taken_over_before_frontier_planning(self):
        calls, rows = self.run_case(moving_old_order=True, polls=25)
        self.assertTrue(self.events(rows, 'recon_order_queued'))
        self.assertTrue(self.events(rows, 'recon_frontier_refreshed'))
        names = [row['event'] for row in rows]
        self.assertLess(names.index('task_ownership_acquired'), names.index('recon_takeover_queued'))
        self.assertLess(names.index('recon_takeover_queued'), names.index('recon_takeover_observed'))
        self.assertLess(names.index('recon_takeover_observed'), names.index('recon_frontier_plan'))
        for call in calls:
            if call['method'] == 'POST' and call['path'] == '/command/attack-move':
                self.assertNotIn('20', call['query']['unitIds'][0].split(','))

    def test_short_move_completed_between_polls_advances_the_route(self):
        _, rows = self.run_case(instant_completion=True, intra_poll_refresh=True, polls=20)
        self.assertGreaterEqual(len(self.events(rows, 'recon_order_queued')), 2)
        self.assertGreaterEqual(len(self.events(rows, 'recon_move_completed_between_samples')), 2)
        self.assertTrue(self.events(rows, 'recon_frontier_satisfied'))
        self.assertFalse(self.events(rows, 'recon_order_observed'), 'no active native order was sampled')
        self.assertFalse(self.events(rows, 'recon_frontier_refreshed'), 'strict temporal attribution is unavailable')

    def test_accepted_but_unexecuted_takeover_cannot_authorize_a_route(self):
        calls, rows = self.run_case(moving_old_order=True, drop_moves=True, polls=25)
        self.assertTrue(self.events(rows, 'recon_takeover_queued'))
        self.assertFalse(self.events(rows, 'recon_takeover_observed'))
        self.assertFalse(self.events(rows, 'recon_order_queued'))
        self.assertFalse([c for c in calls if c['path'] == '/scout/plan' and c['query'].get('role') == ['recon']])
        self.assertIn('TAKEOVER_TIMEOUT', [e['reason'] for e in self.events(rows, 'recon_takeover_cancelled')])

    def test_order_visible_only_between_decisions_is_still_observed(self):
        _, rows = self.run_case(between_decisions=True, game_step_ms=500, polls=55)
        self.assertGreaterEqual(len(self.events(rows, 'recon_order_observed')), 2)
        self.assertGreaterEqual(len(self.events(rows, 'recon_order_queued')), 2)
        self.assertTrue(self.events(rows, 'recon_frontier_refreshed'))

    def test_ten_world_unit_first_leg_can_finish_without_active_order_sample(self):
        _, rows = self.run_case(short_first_leg=True, instant_completion=True,
                                intra_poll_refresh=True, polls=22)
        self.assertGreaterEqual(len(self.events(rows, 'recon_order_queued')), 2)
        self.assertGreaterEqual(len(self.events(rows, 'recon_move_completed_between_samples')), 2)
        self.assertTrue(self.events(rows, 'recon_frontier_satisfied'))

    def test_vision_grace_starts_at_arrival_not_at_submission(self):
        _, rows = self.run_case(instant_completion=True, region_delay=4, polls=25)
        self.assertTrue(self.events(rows, 'recon_frontier_satisfied'))
        self.assertFalse(self.events(rows, 'recon_frontier_blocked'))

    def test_low_value_damaged_unit_sweeps_deep_fog_and_stays_out_of_army(self):
        calls, rows = self.run_case()
        transfers = self.events(rows, 'recon_expendable_transfer')
        assigned = self.events(rows, 'recon_assigned')
        queued = self.events(rows, 'recon_order_queued')
        observed = self.events(rows, 'recon_order_observed')
        progress = self.events(rows, 'recon_progress')
        refreshed = self.events(rows, 'recon_frontier_refreshed')
        self.assertEqual(len(transfers), 1)
        self.assertEqual(transfers[0]['unitId'], self.SCOUT_ID)
        self.assertEqual(len(assigned), 1)
        self.assertEqual(assigned[0]['unitId'], self.SCOUT_ID)
        self.assertEqual(assigned[0].get('kind', assigned[0].get('taskKind')),
                         'FRONTIER_SWEEP')
        self.assertGreaterEqual(len(queued), 2,
                                'each straight leg needs its own move')
        self.assertGreaterEqual(len(observed), 2,
                                'both own-state move orders must be observed')
        self.assertGreaterEqual(len(progress), 2,
                                'own position must advance on both legs')
        self.assertEqual(len(refreshed), 1,
                         'legal region observation closes the frontier task')
        self.assertTrue(any(c['path'] == '/scout/plan' and
                            c['query'].get('role') == ['recon'] and
                            c['query'].get('unitId') == [str(self.SCOUT_ID)]
                            for c in calls))
        self.assertTrue(any(c['path'] == '/scout/observe' and
                            c['query'].get('tile') == [str(self.FRONTIER_TILE)] and
                            'since' in c['query']
                            for c in calls))
        moves = [c for c in calls if c['method'] == 'POST' and
                 c['path'] == '/command/move' and
                 c['query']['unitId'] == [str(self.SCOUT_ID)] and
                 float(c['query']['x'][0]) != 100.0]
        self.assertGreaterEqual(len(moves), 2)
        first_x = float(moves[0]['query']['x'][0])
        first_y = float(moves[0]['query']['y'][0])
        second_x = float(moves[1]['query']['x'][0])
        second_y = float(moves[1]['query']['y'][0])
        self.assertAlmostEqual(first_x, 230.0)
        self.assertAlmostEqual(first_y, 110.0,
                               msg='first waypoint must stop at the turn')
        self.assertAlmostEqual(second_x, 230.0)
        self.assertAlmostEqual(second_y, 230.0)
        self.assertEqual(queued[0]['routeWaypointIndex'], 6)
        self.assertEqual(queued[1]['routeWaypointIndex'], 12)
        self.assertTrue(self.events(rows, 'recon_waypoint_reached'))
        ordered_events = [row['event'] for row in rows]
        self.assertLess(ordered_events.index('recon_expendable_transfer'),
                        ordered_events.index('recon_order_queued'))
        self.assertLess(ordered_events.index('recon_order_queued'),
                        ordered_events.index('recon_order_observed'))
        self.assertLess(ordered_events.index('recon_order_observed'),
                        ordered_events.index('recon_frontier_refreshed'))
        second_order_index = [i for i, row in enumerate(rows)
                              if row['event'] == 'recon_order_queued'][1]
        self.assertLess(ordered_events.index('recon_progress'),
                        second_order_index,
                        'second leg waits for observed own movement')
        self.assertLess(ordered_events.index('recon_waypoint_reached'),
                        second_order_index)
        assigned_frame = moves[0]['frame']
        main_orders = [c for c in calls if c['method'] == 'POST' and
                       c['path'] == '/command/attack-move' and
                       c['frame'] >= assigned_frame]
        self.assertTrue(main_orders, 'the remaining main force must keep fighting')
        for order in main_orders:
            ids = {int(value) for value in order['query']['unitIds'][0].split(',')}
            self.assertNotIn(self.SCOUT_ID, ids,
                             'transferred scout must stay outside the attack group')
            self.assertGreaterEqual(len(ids), 6)

    def test_receipt_and_movement_without_region_update_do_not_claim_refresh(self):
        calls, rows = self.run_case(reveal_region=False, polls=18)
        self.assertGreaterEqual(len(self.events(rows, 'recon_order_queued')), 2)
        self.assertTrue(self.events(rows, 'recon_order_observed'))
        self.assertTrue(self.events(rows, 'recon_progress'))
        self.assertFalse(self.events(rows, 'recon_frontier_refreshed'))
        self.assertTrue(any(c['path'] == '/scout/observe' and
                            c['query'].get('tile') == [str(self.FRONTIER_TILE)]
                            for c in calls))

    def test_frontier_and_lost_building_alternate_when_both_remain_available(self):
        _, rows = self.run_case(lost_site=True, persistent_frontier=True,
                                slow_first_leg=True, polls=30)
        created = self.events(rows, 'recon_task_created')
        self.assertGreaterEqual(len(created), 2)
        self.assertEqual(created[0]['kind'], 'FRONTIER_SWEEP')
        self.assertEqual(created[0]['frontierTile'], self.FRONTIER_TILE)
        self.assertEqual(len(self.events(rows, 'recon_frontier_refreshed')), 1)
        self.assertEqual(created[1]['kind'], 'RECHECK_INTEL',
                         'a fresh second frontier must not starve a legal old-site recheck')
        self.assertEqual(created[1]['enemyId'], 70)
        self.assertGreaterEqual(created[1]['gameTimeMs'] - created[0]['gameTimeMs'],
                                15000, 'second frontier planning should already be eligible')
        first_refresh = next(i for i, row in enumerate(rows)
                             if row['event'] == 'recon_frontier_refreshed')
        second_created = [i for i, row in enumerate(rows)
                          if row['event'] == 'recon_task_created'][1]
        self.assertLess(first_refresh, second_created)
        nearby_own = [row['data'] for row in rows[:second_created]
                      if row['event'] == 'observation' and
                      row['data'].get('gameTimeMs') == created[1]['gameTimeMs']]
        self.assertTrue(nearby_own)
        scout = next(unit for unit in nearby_own[-1]['ownUnits']
                     if unit['id'] == self.SCOUT_ID)
        self.assertLessEqual(((scout['x'] - 230.0) ** 2 +
                              (scout['y'] - 230.0) ** 2) ** 0.5, 40.0,
                             'the next legal route remains reachable from the transferred scout')

    def test_region_revealed_before_own_progress_is_not_attributed_to_scout(self):
        _, rows = self.run_case(early_region_timestamp=True, polls=20)
        self.assertTrue(self.events(rows, 'recon_order_observed'))
        self.assertTrue(self.events(rows, 'recon_progress'))
        self.assertFalse(self.events(rows, 'recon_frontier_memory_update'))
        self.assertFalse(self.events(rows, 'recon_frontier_refreshed'))

    def test_damaged_heavy_tank_is_not_expended(self):
        calls, rows = self.run_case(heavy=True, polls=16)
        self.assertFalse(self.events(rows, 'recon_expendable_transfer'))
        self.assertFalse(self.events(rows, 'recon_assigned'))
        self.assertFalse([c for c in calls if c['method'] == 'POST' and
                          c['path'] == '/command/move' and
                          c['query']['unitId'] == [str(self.SCOUT_ID)]])

    def test_emergency_after_transfer_releases_for_defense_and_recalls(self):
        calls, rows = self.run_case(preempt_after_move=True,
                                    reveal_region=False, polls=28)
        self.assertEqual(len(self.events(rows, 'recon_expendable_transfer')), 1)
        self.assertGreaterEqual(len(self.events(rows, 'recon_order_queued')), 2)
        self.assertTrue(self.events(rows, 'recon_order_observed'))
        releases = self.events(rows, 'recon_unassigned')
        self.assertEqual(len(releases), 1)
        self.assertEqual(releases[0]['reason'], 'DEFENSE_PREEMPTION')
        self.assertEqual(releases[0]['unitId'], self.SCOUT_ID)
        released = self.events(rows, 'recon_expendable_released_for_defense')
        self.assertEqual(len(released), 1)
        self.assertEqual(released[0]['unitId'], self.SCOUT_ID)
        self.assertEqual(released[0]['reason'], 'DEFENSE_PREEMPTION')
        recalls = self.events(rows, 'recon_recall_queued')
        self.assertEqual(len(recalls), 1)
        self.assertEqual(recalls[0]['unitId'], self.SCOUT_ID)
        self.assertFalse(self.events(rows, 'recon_frontier_refreshed'))
        recall_calls = [c for c in calls if c['method'] == 'POST' and
                        c['path'] == '/command/move' and
                        c['query']['unitId'] == [str(self.SCOUT_ID)] and
                        float(c['query']['x'][0]) == 100.0 and
                        float(c['query']['y'][0]) == 100.0]
        self.assertEqual(len(recall_calls), 1)
        recall_frame = recall_calls[0]['frame']
        resumed_orders = [c for c in calls if c['method'] == 'POST' and
                          c['path'] == '/command/attack-move' and
                          c['frame'] >= recall_frame and
                          self.SCOUT_ID in {int(v) for v in
                                            c['query']['unitIds'][0].split(',')}]
        self.assertTrue(resumed_orders,
                        'released tank must be available to the main force')
        home_observed = any(
            row['event'] == 'observation' and
            row['data'].get('gameTimeMs', 0) > recall_frame * 2000 and
            any(unit.get('id') == self.SCOUT_ID and
                abs(unit.get('x', 0) - 100.0) < 10 and
                abs(unit.get('y', 0) - 100.0) < 10
                for unit in row['data'].get('ownUnits', []))
            for row in rows)
        self.assertTrue(home_observed, 'own-state must show recall arrival')

    def test_lost_scout_on_emergency_frame_is_not_recorded_as_preempted(self):
        _, rows = self.run_case(preempt_after_move=True,
                                lost_on_emergency=True,
                                reveal_region=False, polls=25)
        self.assertEqual(len(self.events(rows, 'recon_expendable_transfer')), 1)
        self.assertIn('EMERGENCY',
                      [event['state'] for event in
                       self.events(rows, 'military_urgency')])
        lost = [event for event in self.events(rows, 'recon_attempt_blocked')
                if event['reason'] == 'SCOUT_LOST']
        self.assertEqual(len(lost), 1)
        self.assertEqual(lost[0]['unitId'], self.SCOUT_ID)
        self.assertFalse(self.events(rows, 'recon_unassigned'),
                         'a missing scout cannot be counted as defense preemption')
        self.assertFalse(self.events(rows, 'recon_expendable_released_for_defense'))
        self.assertFalse(self.events(rows, 'recon_recall_queued'))

    def test_move_409_does_not_log_successful_expendable_transfer(self):
        calls, rows = self.run_case(reject_move=True, polls=18)
        self.assertTrue([c for c in calls if c['method'] == 'POST' and
                         c['path'] == '/command/move' and
                         c['query']['unitId'] == [str(self.SCOUT_ID)]])
        self.assertTrue(self.events(rows, 'command_rejected'))
        self.assertFalse(self.events(rows, 'recon_expendable_transfer'))
        self.assertFalse(self.events(rows, 'recon_assigned'))
        self.assertFalse(self.events(rows, 'recon_order_queued'))
        self.assertIn('COMMAND_REJECTED',
                      [event['reason'] for event in
                       self.events(rows, 'recon_frontier_blocked')])
        summary = self.events(rows, 'summary')[-1]
        self.assertEqual(summary['expendableTransfers'], 0)
        self.assertEqual(summary['expendableScoutsAtEnd'], 0)

    def test_actor_drift_before_first_order_rejects_stale_route(self):
        calls, rows = self.run_case(drift_before_first_order=True, polls=18)
        self.assertTrue([c for c in calls if c['method'] == 'POST' and
                         c['path'] == '/command/produce-builder'],
                        'one unrelated command must postpone the Recon move')
        self.assertEqual(self.events(rows, 'recon_task_created')[0]['kind'],
                         'FRONTIER_SWEEP')
        self.assertIn('ROUTE_ANCHOR_DRIFT_BEFORE_ASSIGNMENT',
                      [event['reason'] for event in
                       self.events(rows, 'recon_frontier_blocked')])
        self.assertFalse(self.events(rows, 'recon_expendable_transfer'))
        self.assertFalse(self.events(rows, 'recon_assigned'))
        self.assertFalse(self.events(rows, 'recon_order_queued'))
        self.assertFalse([c for c in calls if c['method'] == 'POST' and
                          c['path'] == '/command/move' and
                          c['query']['unitId'] == [str(self.SCOUT_ID)]])

    def test_adjacent_tile_before_first_order_rejects_wrong_route_cursor(self):
        calls, rows = self.run_case(adjacent_tile_before_first_order=True,
                                    polls=18)
        self.assertTrue([c for c in calls if c['method'] == 'POST' and
                         c['path'] == '/command/produce-builder'])
        self.assertEqual(self.events(rows, 'recon_task_created')[0]['kind'],
                         'FRONTIER_SWEEP')
        self.assertLess(((100.0 - 110.0) ** 2 + (130.0 - 110.0) ** 2) ** 0.5,
                        40.0, 'a distance-only anchor gate would accept this wrong tile')
        self.assertIn('ROUTE_ANCHOR_DRIFT_BEFORE_ASSIGNMENT',
                      [event['reason'] for event in
                       self.events(rows, 'recon_frontier_blocked')])
        self.assertFalse(self.events(rows, 'recon_expendable_transfer'))
        self.assertFalse(self.events(rows, 'recon_assigned'))
        self.assertFalse(self.events(rows, 'recon_order_queued'))
        self.assertFalse([c for c in calls if c['method'] == 'POST' and
                          c['path'] == '/command/move' and
                          c['query']['unitId'] == [str(self.SCOUT_ID)]])

    def test_one_tile_stale_own_observation_waits_for_bridge_route_anchor(self):
        calls, rows = self.run_case(stale_adjacent_plan=True, polls=28)
        created = self.events(rows, 'recon_task_created')
        self.assertTrue(created)
        self.assertEqual(created[0]['kind'], 'FRONTIER_SWEEP')
        self.assertEqual(created[0]['frontierTile'], self.FRONTIER_TILE)
        self.assertIn('WAITING_FOR_FRESH_ROUTE_ANCHOR',
                      [event['reason'] for event in
                       self.events(rows, 'recon_deferred')])
        moves = [c for c in calls if c['method'] == 'POST' and
                 c['path'] == '/command/move' and
                 c['query']['unitId'] == [str(self.SCOUT_ID)] and
                 float(c['query']['x'][0]) != 100.0]
        self.assertTrue(moves)
        self.assertGreater(moves[0]['frame'] * 2000, created[0]['gameTimeMs'],
                           'old own observation must never authorize the move')
        transfers = self.events(rows, 'recon_expendable_transfer')
        self.assertEqual(len(transfers), 1)
        self.assertGreater(transfers[0]['gameTimeMs'], created[0]['gameTimeMs'])
        self.assertTrue(self.events(rows, 'recon_order_queued'))
        pending_orders = [c for c in calls if c['method'] == 'POST' and
                          c['path'] == '/command/attack-move' and
                          created[0]['gameTimeMs'] <= c['frame'] * 2000 <
                          transfers[0]['gameTimeMs']]
        self.assertTrue(pending_orders,
                        'the main force must still act while the scout waits for fresh own state')
        for order in pending_orders:
            ids = {int(value) for value in order['query']['unitIds'][0].split(',')}
            self.assertNotIn(self.SCOUT_ID, ids,
                             'a pending frontier actor must not be redirected by the main army')
        old_state = [event for event in self.events(rows, 'observation')
                     if event.get('gameTimeMs') == created[0]['gameTimeMs']]
        fresh_state = [event for event in self.events(rows, 'observation')
                       if event.get('gameTimeMs') == moves[0]['frame'] * 2000]
        self.assertTrue(old_state)
        self.assertTrue(fresh_state)
        old_scout = next(unit for unit in old_state[-1]['ownUnits']
                         if unit['id'] == self.SCOUT_ID)
        fresh_scout = next(unit for unit in fresh_state[-1]['ownUnits']
                           if unit['id'] == self.SCOUT_ID)
        self.assertEqual((int(old_scout['x'] // 20), int(old_scout['y'] // 20)),
                         (5, 4))
        self.assertEqual((int(fresh_scout['x'] // 20), int(fresh_scout['y'] // 20)),
                         (5, 5))

    def test_stale_own_tile_on_first_route_leg_still_waits_one_observation(self):
        calls, rows = self.run_case(stale_forward_on_route=True, polls=28)
        created = self.events(rows, 'recon_task_created')
        self.assertTrue(created)
        self.assertEqual(created[0]['kind'], 'FRONTIER_SWEEP')
        self.assertTrue(created[0]['awaitFreshOwnObservation'])
        self.assertIn('WAITING_FOR_FRESH_ROUTE_ANCHOR',
                      [event['reason'] for event in
                       self.events(rows, 'recon_deferred')])
        old_state = [event for event in self.events(rows, 'observation')
                     if event.get('gameTimeMs') == created[0]['gameTimeMs']]
        self.assertTrue(old_state)
        old_scout = next(unit for unit in old_state[-1]['ownUnits']
                         if unit['id'] == self.SCOUT_ID)
        old_tile = int(old_scout['x'] // 20) * 110 + int(old_scout['y'] // 20)
        self.assertEqual(old_tile, self.ROUTE_TILES[1],
                         'old own position is already on the first straight route leg')
        moves = [c for c in calls if c['method'] == 'POST' and
                 c['path'] == '/command/move' and
                 c['query']['unitId'] == [str(self.SCOUT_ID)] and
                 float(c['query']['x'][0]) != 100.0]
        self.assertTrue(moves)
        self.assertGreater(moves[0]['frame'] * 2000, created[0]['gameTimeMs'],
                           'the bridge anchor cannot authorize a same-tick first order')
        transfers = self.events(rows, 'recon_expendable_transfer')
        self.assertEqual(len(transfers), 1)
        self.assertGreater(transfers[0]['gameTimeMs'], created[0]['gameTimeMs'])

    def test_pending_frontier_closes_for_emergency_without_transfer(self):
        calls, rows = self.run_case(stale_adjacent_plan=True,
                                    pending_emergency=True, polls=24)
        created = self.events(rows, 'recon_task_created')
        self.assertEqual(len(created), 1)
        self.assertEqual(created[0]['kind'], 'FRONTIER_SWEEP')
        self.assertTrue(created[0]['awaitFreshOwnObservation'])
        self.assertIn('EMERGENCY',
                      [event['state'] for event in
                       self.events(rows, 'military_urgency')])
        closed = [event for event in self.events(rows, 'recon_frontier_blocked')
                  if event['reason'] == 'DEFENSE_PREEMPTION']
        self.assertEqual(len(closed), 1)
        self.assertIsNone(closed[0]['unitId'])
        self.assertFalse(self.events(rows, 'recon_expendable_transfer'))
        self.assertFalse(self.events(rows, 'recon_assigned'))
        self.assertFalse(self.events(rows, 'recon_order_queued'))
        self.assertFalse(self.events(rows, 'recon_recall_queued'))
        self.assertFalse([c for c in calls if c['method'] == 'POST' and
                          c['path'] == '/command/move' and
                          c['query']['unitId'] == [str(self.SCOUT_ID)]])
        resumed_main = [c for c in calls if c['method'] == 'POST' and
                        c['path'] == '/command/attack-move' and
                        c['frame'] * 2000 >= closed[0]['gameTimeMs'] and
                        self.SCOUT_ID in {int(v) for v in
                                          c['query']['unitIds'][0].split(',')}]
        self.assertTrue(resumed_main,
                        'pending scout candidate must be returned to the defending army')

    def test_existing_expendable_pending_keeps_six_ready_main_units(self):
        _, rows = self.run_case(existing_expendable_pending=True, polls=45)
        created = [event for event in self.events(rows, 'recon_task_created')
                   if event['kind'] == 'FRONTIER_SWEEP']
        self.assertGreaterEqual(len(created), 2)
        self.assertEqual(len(self.events(rows, 'recon_expendable_transfer')), 1)
        self.assertTrue(self.events(rows, 'recon_frontier_refreshed'))
        second = created[1]
        self.assertEqual(second['frontierTile'], self.PENDING_SECOND_FRONTIER_TILE)
        self.assertTrue(second['awaitFreshOwnObservation'])
        assigned = [event for event in self.events(rows, 'recon_assigned')
                    if event['taskId'] == second['taskId']]
        queued = [event for event in self.events(rows, 'recon_order_queued')
                  if event['taskId'] == second['taskId']]
        self.assertEqual(len(assigned), 1)
        self.assertEqual(assigned[0]['unitId'], self.SCOUT_ID)
        self.assertEqual(assigned[0]['mainForceAfterAssignment'], 6)
        self.assertTrue(queued,
                        'a previously transferred scout may depart with six ready main units')
        self.assertGreater(queued[0]['gameTimeMs'], second['gameTimeMs'],
                           'stale bridge anchor still needs a fresh own observation')
        preempted = [event for event in self.events(rows, 'recon_frontier_blocked')
                     if event['taskId'] == second['taskId'] and
                     event['reason'] == 'DEFENSE_PREEMPTION']
        self.assertFalse(preempted)

    def test_emergency_keeps_damaged_unit_with_main_force(self):
        calls, rows = self.run_case(emergency=True, polls=16)
        self.assertIn('EMERGENCY',
                      [event['state'] for event in
                       self.events(rows, 'military_urgency')])
        self.assertFalse(self.events(rows, 'recon_expendable_transfer'))
        self.assertFalse(self.events(rows, 'recon_assigned'))
        self.assertFalse([c for c in calls if c['method'] == 'POST' and
                          c['path'] == '/command/move' and
                          c['query']['unitId'] == [str(self.SCOUT_ID)]])

    def test_frontier_toggle_preserves_existing_non_frontier_behavior(self):
        calls, rows = self.run_case(disabled=True, polls=16)
        self.assertFalse(self.events(rows, 'recon_expendable_transfer'))
        self.assertFalse(self.events(rows, 'recon_frontier_refreshed'))
        self.assertFalse([c for c in calls if c['path'] == '/scout/plan' and
                          c['query'].get('role') == ['recon']])
        self.assertFalse([c for c in calls if c['method'] == 'POST' and
                          c['path'] == '/command/move' and
                          c['query']['unitId'] == [str(self.SCOUT_ID)]])


if __name__ == '__main__':
    unittest.main()
