"""E2 open-loop decision replay. Inputs are fixed; emitted orders do NOT evolve the world.

This intentionally isolates BattleClient target policy from economy and Recon scheduling.
Even when inputs came from E4, replay output is not native execution or a new match outcome.
"""
import copy
import http.server
import json
from pathlib import Path
import subprocess
import tempfile
import threading
from urllib.parse import parse_qs, urlsplit


def run_frames(jar, frames, evidence_dir=None):
    index = [-1]
    orders = []
    session = frames[0]['state']['sessionId']

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def reply(self, data):
            raw = json.dumps(data).encode()
            self.send_response(200)
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            path = urlsplit(self.path).path
            if path == '/health':
                return self.reply({'status': 'ok', 'version': '0.07-alpha1'})
            if path == '/state':
                index[0] += 1
                state = copy.deepcopy(frames[min(index[0], len(frames)-1)]['state'])
                if index[0] >= len(frames):
                    state['frame'] += 1
                    state['gameTimeMs'] += 1000
                    state['match'] = {'outcome': 'DEFEAT', 'source': 'E2_TRANSCRIPT_END',
                                      'nativeDefeat': False, 'nativeVictory': False}
                return self.reply(state)
            current = frames[min(max(index[0], 0), len(frames)-1)]
            if path == '/combat/observe':
                return self.reply(current['combat'])
            if path == '/scout/observe':
                return self.reply(dict(status='observed', sessionId=session,
                    gameTimeMs=current['state']['gameTimeMs'],
                    visibleThreats=[], rememberedThreats=[], resources=[], newlyObservedTiles=0))
            if path == '/combat/production':
                return self.reply(dict(status='observed', sessionId=session, factories=[]))
            if path == '/scout/visible':
                return self.reply(dict(status='observed', sessionId=session, tiles=[]))
            return self.reply(dict(status='no_frontier', sessionId=session))

        def do_POST(self):
            query = parse_qs(urlsplit(self.path).query)
            state = frames[min(index[0], len(frames)-1)]['state']
            ids = [int(i) for i in query.get('unitIds', query.get('unitId', ['0']))[0].split(',')]
            orders.append(dict(path=urlsplit(self.path).path, unitIds=ids,
                x=float(query.get('x', ['0'])[0]), y=float(query.get('y', ['0'])[0]),
                gameTimeMs=state['gameTimeMs']))
            self.reply(dict(status='queued', sessionId=session, requestId=query['requestId'][0],
                frame=state['frame'], unitId=ids[0], unitIds=ids,
                targetX=orders[-1]['x'], targetY=orders[-1]['y'], orderType='attackMove'))

    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory() as directory:
            command = ['java', '-Drwagent.pollMs=1', '-Drwagent.reconEnabled=false',
                       '-Drwagent.reachabilitySample=false', '-Drwagent.port='+str(server.server_port),
                       '-cp', str(Path(jar).resolve()), 'io.rwagent.client.BattleClient', '1800']
            run = subprocess.run(command, cwd=directory, capture_output=True, timeout=180)
            report = next(Path(directory).glob('rw-agent-reports/battle-*.jsonl')).read_bytes()
            rows = [json.loads(line) for line in report.splitlines()]
            if evidence_dir:
                dest = Path(evidence_dir)
                dest.mkdir(parents=True, exist_ok=True)
                (dest/'decision-replay.jsonl').write_bytes(report)
                (dest/'orders.json').write_text(json.dumps(orders, indent=2)+'\n')
            if run.returncode != 0:
                raise AssertionError(run.stdout.decode(errors='replace')+run.stderr.decode(errors='replace'))
            return orders, rows
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def desktop_frames(rows):
    frames = []
    state = None
    for row in rows:
        if row['event'] == 'observation':
            state = row['data']
        elif row['event'] == 'combat_observation' and state is not None:
            frames.append({'state': state, 'combat': row['data']})
    # BattleClient's initial state acquisition does not evaluate policy.
    return [frames[0]]+frames
