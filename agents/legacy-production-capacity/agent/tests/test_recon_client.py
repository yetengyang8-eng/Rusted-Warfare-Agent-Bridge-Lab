"""Deterministic Recon v0.1 policy tests against a local, legal-observation bridge."""

import http.server
import json
import pathlib
import subprocess
import sys
import tempfile
import threading
import unittest
from urllib.parse import parse_qs, urlsplit


JAR = str(pathlib.Path(sys.argv.pop(1)).resolve())


class ReconPolicyTests(unittest.TestCase):
    SITE_ID = 70
    SITE_X = 1500.0
    SITE_Y = 1000.0

    def run_case(self, *, emergency=False, armed_site=False, disabled=False,
                 army_count=8, preempt=None, damage_hp=None, clear_site=True, polls=22):
        """A single old building sighting survives after tactical memory has expired.

        A move receipt alone is insufficient to clear it. The next own-state observations
        expose the assigned unit's move order and then its actual progress. Only after
        those observations does the bridge report that the former site is visible and
        empty. The same stale LOST_CONTACT record is replayed afterward to check that
        a closed task cannot be revived without a newer sighting.
        """
        calls = []
        frame = [0]
        outbound_moves = []
        recall_moves = []
        first_clear_frame = [None]

        def unit(uid, kind, x, y, *, building=False, armed=False, hp=1000, order=None):
            result = dict(id=uid, type=kind, x=x, y=y, hp=hp, maxHp=1000,
                          dead=False, buildProgress=1, mobile=not building,
                          canAttack=armed, building=building, techLevel=1,
                          productionQueue=0, orderType=None)
            if order is not None:
                result.update(orderType='move', orderX=order['x'], orderY=order['y'])
            return result

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def send_json(self, body):
                payload = json.dumps(body).encode('utf-8')
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def do_POST(self):
                path = urlsplit(self.path)
                query = parse_qs(path.query)
                calls.append(dict(path=path.path, query=query, frame=frame[0]))
                receipt = dict(status='queued', sessionId='s', frame=frame[0],
                               requestId=query['requestId'][0])
                if path.path == '/command/move':
                    order = dict(unitId=int(query['unitId'][0]),
                                 x=float(query['x'][0]), y=float(query['y'][0]),
                                 frame=frame[0])
                    if order['x'] == 100.0 and order['y'] == 100.0:
                        recall_moves.append(order)
                    else:
                        outbound_moves.append(order)
                    receipt.update(unitId=order['unitId'], targetX=order['x'],
                                   targetY=order['y'], orderType='move')
                elif path.path == '/command/attack-move':
                    ids = [int(value) for value in query['unitIds'][0].split(',')]
                    receipt.update(unitId=ids[0], unitIds=ids,
                                   targetX=float(query['x'][0]),
                                   targetY=float(query['y'][0]), orderType='attackMove')
                self.send_json(receipt)

            def do_GET(self):
                path = urlsplit(self.path).path
                now = frame[0] * 2000
                if path == '/health':
                    return self.send_json(dict(status='ok', version='0.07-alpha1'))
                if path == '/state':
                    frame[0] += 1
                    now = frame[0] * 2000
                    terminal = frame[0] >= polls
                    own = [unit(3, 'commandCenter', 100, 100, building=True)]
                    assigned = outbound_moves[0] if outbound_moves else None
                    after_assignment = (assigned is not None
                                        and frame[0] >= assigned['frame'] + 2)
                    ids = list(range(20, 20 + army_count))
                    inactive = set()
                    if preempt == 'force_drop' and after_assignment:
                        inactive = set([uid for uid in reversed(ids)
                                        if uid != assigned['unitId']][:2])
                    for uid in ids:
                        x, y, order = 100.0, 100.0, None
                        if assigned is not None and uid == assigned['unitId']:
                            elapsed = frame[0] - assigned['frame']
                            if elapsed >= 1:
                                fraction = 0.5 if elapsed == 1 else 1.0
                                x = 100.0 + (assigned['x'] - 100.0) * fraction
                                y = 100.0 + (assigned['y'] - 100.0) * fraction
                                order = assigned
                        if recall_moves and uid == recall_moves[0]['unitId']:
                            recalled = recall_moves[0]
                            if frame[0] > recalled['frame']:
                                x, y, order = 100.0, 100.0, recalled
                        hp = damage_hp if (preempt == 'damage' and after_assignment
                                           and uid == assigned['unitId']) else 1000
                        own.append(unit(uid, 'c_tank', x, y, armed=uid not in inactive,
                                        hp=hp, order=order))
                    return self.send_json(dict(
                        status='running', sessionId='s', frame=frame[0],
                        gameTimeMs=now, networked=False, replay=False,
                        player=dict(credits=0),
                        map=dict(width=2200, height=2200, tilesWide=110,
                                 tilesHigh=110, tileWidth=20, tileHeight=20),
                        match=dict(outcome='DEFEAT' if terminal else 'ONGOING',
                                   nativeDefeat=terminal, nativeVictory=False,
                                   source='native_result_screen'), ownUnits=own))
                if path == '/combat/observe':
                    assigned = outbound_moves[0] if outbound_moves else None
                    after_assignment = (assigned is not None
                                        and frame[0] >= assigned['frame'] + 2)
                    emergency_now = emergency or (preempt == 'emergency' and after_assignment)
                    cleared = (clear_site and not emergency_now and assigned is not None
                               and frame[0] >= assigned['frame'] + 3)
                    if cleared and first_clear_frame[0] is None:
                        first_clear_frame[0] = frame[0]
                    # Two CLEARED observations, then a deliberately stale bridge record.
                    replay_stale = (first_clear_frame[0] is not None
                                    and frame[0] >= first_clear_frame[0] + 2)
                    status = 'CLEARED' if cleared and not replay_stale else 'LOST_CONTACT'
                    intel = dict(id=ReconPolicyTests.SITE_ID,
                                 lastKnownType='landFactory', status=status,
                                 firstSeenGameTimeMs=1000, lastSeenGameTimeMs=1000,
                                 lastKnownX=ReconPolicyTests.SITE_X,
                                 lastKnownY=ReconPolicyTests.SITE_Y,
                                 lastKnownHp=1000, lastKnownBuilding=True,
                                 lastKnownCanAttack=armed_site,
                                 lastKnownSiteVisible=status == 'CLEARED',
                                 clearedGameTimeMs=now if status == 'CLEARED' else None)
                    threat = dict(id=99, type='c_tank', x=180, y=100,
                                  hp=1000, maxHp=1000, dead=False,
                                  building=False, canAttack=True,
                                  lastSeenGameTimeMs=now)
                    return self.send_json(dict(
                        status='observed', sessionId='s', frame=frame[0],
                        gameTimeMs=now, visibleEnemies=[],
                        rememberedEnemies=[threat] if emergency_now else [],
                        enemyIntel=[intel], enemyIntelVisible=0,
                        enemyIntelLostContact=int(status == 'LOST_CONTACT'),
                        enemyIntelCleared=int(status == 'CLEARED'),
                        enemyIntelEvicted=0))
                if path == '/combat/production':
                    return self.send_json(dict(status='observed', sessionId='s',
                                               factories=[]))
                if path == '/scout/observe':
                    return self.send_json(dict(status='observed', sessionId='s',
                                               frame=frame[0], gameTimeMs=now,
                                               newlyObservedTiles=0, resources=[],
                                               visibleThreats=[], rememberedThreats=[]))
                return self.send_json(dict(status='no_frontier', sessionId='s'))

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory(dir=pathlib.Path.cwd()) as cwd:
                args = ['java', '-Drwagent.pollMs=60',
                        '-Drwagent.reachabilitySample=false',
                        '-Drwagent.builderTarget=0',
                        '-Drwagent.port=' + str(server.server_port)]
                if disabled:
                    args.append('-Drwagent.reconEnabled=false')
                completed = subprocess.run(
                    args + ['-cp', JAR, 'io.rwagent.client.BattleClient', '120'],
                    cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    timeout=25)
                self.assertEqual(completed.returncode, 0,
                                 completed.stdout.decode('utf-8', errors='replace'))
                report = next(pathlib.Path(cwd).glob('rw-agent-reports/battle-*.jsonl'))
                rows = [json.loads(line) for line in report.read_text(encoding='utf-8').splitlines()]
                held = set()
                for row in rows:
                    if row['event'] == 'task_ownership_acquired':
                        self.assertNotIn(row['data']['taskId'], held)
                        held.add(row['data']['taskId'])
                    elif row['event'] == 'task_ownership_released':
                        self.assertIn(row['data']['taskId'], held, 'release must match an outstanding acquire')
                        held.remove(row['data']['taskId'])
                self.assertFalse(held, 'controller must release remaining task ownership')
                return calls, rows
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    @staticmethod
    def events(rows, kind):
        return [row['data'] for row in rows if row['event'] == kind]

    @staticmethod
    def move_calls(calls, *, home):
        def is_home(call):
            query = call['query']
            return float(query['x'][0]) == 100.0 and float(query['y'][0]) == 100.0
        return [call for call in calls if call['path'] == '/command/move'
                and is_home(call) == home]

    def assert_recalled(self, calls, rows, *, release_event, release_reason):
        assigned = self.events(rows, 'recon_assigned')
        self.assertEqual(len(assigned), 1)
        scout_id = assigned[0]['unitId']
        self.assertTrue(self.events(rows, 'recon_order_observed'),
                        'own move-order evidence must precede preemption')
        released = self.events(rows, release_event)
        self.assertEqual(len(released), 1)
        self.assertEqual(released[0]['reason'], release_reason)
        self.assertEqual(released[0]['unitId'], scout_id)
        recalls = self.events(rows, 'recon_recall_queued')
        self.assertEqual(len(recalls), 1)
        self.assertEqual(recalls[0]['unitId'], scout_id)
        outbound_calls = self.move_calls(calls, home=False)
        recall_calls = self.move_calls(calls, home=True)
        self.assertEqual(len(outbound_calls), 1,
                         'preempted task must not launch another outbound move')
        self.assertEqual(len(recall_calls), 1)
        self.assertEqual(int(recall_calls[0]['query']['unitId'][0]), scout_id)
        release_index = next(i for i, row in enumerate(rows)
                             if row['event'] == release_event)
        recall_index = next(i for i, row in enumerate(rows)
                            if row['event'] == 'recon_recall_queued')
        self.assertLess(release_index, recall_index)
        self.assertFalse(self.events(rows[release_index + 1:], 'recon_order_queued'),
                         'no outbound Recon order may follow preemption')
        return scout_id

    def test_lost_factory_uses_one_unit_and_requires_own_evidence_before_clear(self):
        calls, rows = self.run_case()
        created = self.events(rows, 'recon_task_created')
        assigned = self.events(rows, 'recon_assigned')
        queued = self.events(rows, 'recon_order_queued')
        observed = self.events(rows, 'recon_order_observed')
        progress = self.events(rows, 'recon_progress')
        cleared = self.events(rows, 'recon_site_cleared')
        self.assertEqual(len(created), 1, 'stale intel must not recreate the task')
        self.assertEqual(len(assigned), 1)
        self.assertTrue(queued, 'Recon must issue an actual single-unit move')
        self.assertTrue(observed, 'the next own-state snapshot must confirm the move order')
        self.assertTrue(progress, 'own-position change must be reported as progress')
        self.assertEqual(len(cleared), 1, 'the former site is cleared only once')
        moves = [call for call in calls if call['path'] == '/command/move']
        self.assertTrue(moves)
        scout_ids = {int(call['query']['unitId'][0]) for call in moves}
        self.assertEqual(len(scout_ids), 1, 'one task owns one scouting unit')
        self.assertIn(next(iter(scout_ids)), range(20, 28))
        queued_index = next(i for i, row in enumerate(rows)
                            if row['event'] == 'recon_order_queued')
        observed_index = next(i for i, row in enumerate(rows)
                              if row['event'] == 'recon_order_observed')
        cleared_index = next(i for i, row in enumerate(rows)
                             if row['event'] == 'recon_site_cleared')
        self.assertLess(queued_index, observed_index)
        self.assertLess(observed_index, cleared_index)
        first_recon_move_frame = moves[0]['frame']
        for call in calls:
            if (call['path'] == '/command/attack-move'
                    and call['frame'] >= first_recon_move_frame):
                tactical_ids = {int(value) for value in
                                call['query']['unitIds'][0].split(',')}
                self.assertFalse(scout_ids & tactical_ids,
                                 'the tactical army must not reuse its Recon unit')

    def test_armed_site_is_deferred_during_emergency(self):
        calls, rows = self.run_case(emergency=True, armed_site=True)
        self.assertIn('EMERGENCY',
                      [event['state'] for event in self.events(rows, 'military_urgency')])
        self.assertTrue(self.events(rows, 'recon_deferred'))
        self.assertFalse(self.events(rows, 'recon_assigned'))
        self.assertFalse([call for call in calls if call['path'] == '/command/move'])

    def test_six_ready_units_defer_without_assigning_a_scout(self):
        calls, rows = self.run_case(army_count=6, clear_site=False, polls=12)
        self.assertEqual(self.events(rows, 'battle_config')[0]['reconMinReadyArmy'], 7)
        self.assertTrue(self.events(rows, 'recon_task_created'))
        self.assertIn('ARMY_BELOW_SEVEN_READY',
                      [event['reason'] for event in self.events(rows, 'recon_deferred')])
        self.assertFalse(self.events(rows, 'recon_assigned'))
        self.assertFalse(self.move_calls(calls, home=False))

    def test_emergency_after_assignment_releases_and_recalls_scout(self):
        calls, rows = self.run_case(preempt='emergency', clear_site=False, polls=14)
        self.assertIn('EMERGENCY',
                      [event['state'] for event in self.events(rows, 'military_urgency')])
        self.assert_recalled(calls, rows, release_event='recon_unassigned',
                             release_reason='DEFENSE_PREEMPTION')

    def test_ready_force_drop_after_assignment_releases_and_recalls_scout(self):
        calls, rows = self.run_case(preempt='force_drop', clear_site=False, polls=14)
        self.assertNotIn('EMERGENCY',
                         [event['state'] for event in self.events(rows, 'military_urgency')])
        self.assert_recalled(calls, rows, release_event='recon_unassigned',
                             release_reason='DEFENSE_PREEMPTION')
        self.assertIn('ARMY_BELOW_SEVEN_READY',
                      [event['reason'] for event in self.events(rows, 'recon_deferred')])

    def test_damaged_assigned_scout_is_rested_and_recalled(self):
        calls, rows = self.run_case(preempt='damage', damage_hp=300,
                                    clear_site=False, polls=12)
        scout_id = self.assert_recalled(calls, rows,
                                        release_event='recon_attempt_blocked',
                                        release_reason='SCOUT_DAMAGED')
        self.assertFalse([call for call in calls
                          if call['path'] == '/command/attack-move'
                          and scout_id in {int(value) for value in
                                           call['query']['unitIds'][0].split(',')}
                          and call['frame'] >= self.move_calls(calls, home=True)[0]['frame']],
                         'a 30% HP scout belongs to the rest/recall lane, not the tactical force')

    def test_toggle_disables_recon_without_moving_a_scout(self):
        calls, rows = self.run_case(disabled=True)
        self.assertFalse([row for row in rows if row['event'].startswith('recon_')])
        self.assertFalse([call for call in calls if call['path'] == '/command/move'])


if __name__ == '__main__':
    unittest.main()
