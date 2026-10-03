"""Loopback player gateway: public v1 contract plus frozen-client compatibility.

Only player-scoped legal native endpoints are forwarded. No engine debug,
referee, test, omniscient snapshot, or agent strategy interface is public.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from adapters.overlay import artifact

VERSION = 1
TRANSPORT = "native-network-local-player"
IDENTITY_KEYS = ("matchId", "sessionId", "playerId", "teamId")
COMPAT_READS = frozenset({
    "/health", "/state", "/combat/observe", "/combat/production", "/combat/capabilities",
    "/combat/reachability", "/combat/engagement", "/combat/unit-modes",
    "/scout/observe", "/scout/visible", "/scout/plan", "/economy/preflight",
    "/economy/plan", "/economy/production-plan", "/opening/plan", "/expansion/plan",
    "/economy/builder-production", "/economy/builder-actions", "/economy/investments",
    "/economy/construction-plan", "/static-map/observe", "/static-map/approach", "/scout/resource-approach",
})
COMMAND_KINDS = {
    "/command/move": "move", "/command/attack-move": "attackMove", "/command/guard": "guard",
    "/command/build-extractor": "build", "/command/build-factory": "build",
    "/command/construct": "build", "/command/produce-builder": "produce",
    "/command/produce-tank": "produce", "/command/queue": "produce",
    "/command/invest": "upgrade", "/command/unit-mode": "unitMode",
}
REQUEST_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")

class ProtocolError(Exception):
    def __init__(self, message, status=409):
        super().__init__(message)
        self.status = status

def _integer(value, name, minimum=0):
    if type(value) is not int or value < minimum:
        raise ProtocolError(f"{name} must be an integer >= {minimum}", 400)
    return value

def _finite(value, name):
    if type(value) not in (int, float) or not math.isfinite(value):
        raise ProtocolError(f"{name} must be finite", 400)
    return value

def _digest(path):
    return "sha256:" + hashlib.sha256(Path(path).read_bytes()).hexdigest()

def agent_identity(agent, repo_root=ROOT, external_digest=None):
    if agent in ("legacy", "octopus", "octopus-g5"):
        baseline = artifact(agent, repo_root)
        overlay = Path(repo_root) / "build/adapters" / (agent + ".jar")
        overlay_digest = _digest(overlay)
        components = {"baselineDigest": baseline["artifactDigest"], "overlayDigest": overlay_digest}
        composite = hashlib.sha256(json.dumps(components, sort_keys=True).encode()).hexdigest()
        return {"agentId": agent, "artifactDigest": "sha256:" + composite, **components,
                "adapterKind": "frozen-client-compatibility", "strategyModified": False}
    if external_digest is not None and not re.fullmatch(r"sha256:[0-9a-f]{64}", external_digest):
        raise ValueError("artifact digest must be sha256:<64 lowercase hex digits>")
    return {"agentId": agent, "artifactDigest": external_digest or "UNKNOWN",
            "adapterKind": "public-player-v1"}

class PlayerGateway:
    def __init__(self, upstream_port, match_id, agent="external", *, player_id=None,
                 evidence=None, repo_root=ROOT, external_digest=None, upstream=None):
        self.upstream_port = int(upstream_port)
        self.expected_match = match_id
        self.expected_player = player_id
        self.agent = agent_identity(agent, repo_root, external_digest)
        self.identity = None
        self.observation_sequence = 0
        self.action_sequence = 0
        self.last_frame = -1
        self.last_time = -1
        self.receipts = {}
        self.public_observations = set()
        self.lock = threading.RLock()
        self.evidence = Path(evidence) if evidence else None
        if self.evidence:
            self.evidence.parent.mkdir(parents=True, exist_ok=True)
        self._upstream_override = upstream
        self._server = None

    def record(self, event, **values):
        if self.evidence:
            row = {"event": event, "wallTimeNs": time.time_ns(), "protocolVersion": VERSION,
                   "identity": self.identity, "agent": self.agent, **values}
            with self.evidence.open("a", encoding="utf8") as out:
                out.write(json.dumps(row, separators=(",", ":"), allow_nan=False) + "\n")

    def upstream(self, method, path):
        if self._upstream_override:
            return self._upstream_override(method, path)
        try:
            request = Request(f"http://127.0.0.1:{self.upstream_port}{path}", method=method)
            with urlopen(request, timeout=15) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read())
        except (URLError, TimeoutError, OSError, ValueError) as error:
            raise ProtocolError(f"Native player endpoint unavailable: {error}", 503) from error

    def _state(self):
        status, state = self.upstream("GET", "/state")
        if status != 200:
            raise ProtocolError("Native state unavailable", status)
        self._bind(state)
        return state

    def _bind(self, state):
        if (state.get("status") != "running" or state.get("player") is None
                or state.get("networked") is not True or state.get("replay") is True
                or state.get("protocolVersion") != VERSION
                or state.get("nativeNetworkPlayerV1") is not True
                or state.get("transport") != TRANSPORT):
            raise ProtocolError("Verified active native network player required")
        identity = {key: state.get(key) for key in IDENTITY_KEYS}
        if (not isinstance(identity["sessionId"], str) or not identity["sessionId"]
                or identity["matchId"] != self.expected_match
                or type(identity["playerId"]) is not int or type(identity["teamId"]) is not int):
            raise ProtocolError("Native match/session/player identity mismatch")
        binding = state.get("identity", {})
        if (binding.get("bindingValid") is not True or binding.get("localPlayerVerified") is not True
                or any(binding.get(key) != identity[key] for key in ("matchId", "playerId", "teamId"))):
            raise ProtocolError("Native local player binding invalid")
        if self.expected_player is not None and identity["playerId"] != self.expected_player:
            raise ProtocolError("Unexpected native player slot")
        if self.identity is not None and identity != self.identity:
            raise ProtocolError("Reconnect/player rebind requires a new gateway; commands disabled")
        if self.identity is None:
            self.identity = identity
            self.record("player_connected")
        frame, game_time = _integer(state.get("frame"), "frame"), _integer(state.get("gameTimeMs"), "gameTimeMs")
        if frame < self.last_frame or game_time < self.last_time:
            raise ProtocolError("Native simulation time moved backwards")
        self.last_frame, self.last_time = frame, game_time

    def _same_session(self, reply):
        if reply.get("sessionId") != self.identity["sessionId"]:
            raise ProtocolError("Native response changed player session")

    def capabilities(self):
        self._state()
        return {"protocolVersion": VERSION, "identity": self.identity, "agent": self.agent,
                "transport": TRANSPORT, "observation": ["ownUnits", "economy", "currentVisibleEnemies", "mapBounds", "nativeResult", "nativeOwnActionMenus"],
                "actions": sorted(set(COMMAND_KINDS.values())),
                "receiptSemantics": "NATIVE_QUEUE_ACCEPTANCE_ONLY_NOT_EXECUTED_EFFECT",
                "effectEvidence": "NEEDS_LATER_LEGAL_OBSERVATION",
                "reconnect": "NEW_GATEWAY_REQUIRED", "compatibilityEndpoints": self.agent["adapterKind"] == "frozen-client-compatibility"}

    def observe(self):
        with self.lock:
            state = self._state()
            status, combat = self.upstream("GET", "/combat/observe")
            if status != 200:
                raise ProtocolError("Legal current enemy observation unavailable", status)
            self._same_session(combat)
            # A native request may advance frames between snapshots; never claim atomicity.
            self._state()
            self.observation_sequence += 1
            self.public_observations.add(self.observation_sequence)
            # Bound replay-reference storage; sequence remains monotonic.
            if len(self.public_observations) > 1024:
                self.public_observations.remove(min(self.public_observations))
            observation = {"protocolVersion": VERSION, "identity": dict(self.identity),
                "observationSequence": self.observation_sequence,
                "frame": state["frame"], "gameTimeMs": state["gameTimeMs"],
                "sourceFrames": {"own": state["frame"], "visibleEnemies": combat.get("frame")},
                "ownUnits": state.get("ownUnits", []), "economy": {"credits": state["player"].get("credits")},
                "currentVisibleEnemies": combat.get("visibleEnemies", []),
                "visibilitySemantics": "CURRENT_NATIVE_LOCAL_PLAYER_FOG_ONLY",
                "mapStatic": {key: state.get("map", {}).get(key) for key in ("width", "height", "tilesWide", "tilesHigh", "tileWidth", "tileHeight")},
                "nativeResult": state.get("match", {"outcome": "UNKNOWN"})}
            self.record("player_observation", observation=observation)
            return observation

    def lifecycle(self):
        state = self._state()
        return {"protocolVersion": VERSION, "identity": self.identity,
                "state": "ENDED" if state.get("match", {}).get("outcome") in ("VICTORY", "DEFEAT", "DRAW") else "RUNNING",
                "nativeResult": state.get("match", {"outcome": "UNKNOWN"}),
                "disconnect": state.get("disconnect", "UNKNOWN")}

    def action_menu(self, query):
        """Expose only whitelisted fields from owned native menus, never agent objects."""
        state = self._state()
        category = query.get("kind", [""])[0]
        if set(query) - {"kind", "unitId"} or any(len(values) != 1 for values in query.values()):
            raise ProtocolError("Action-menu accepts kind and optional unitId only", 400)
        own = {unit.get("id") for unit in state.get("ownUnits", []) if not unit.get("dead", False)}
        unit_id = None
        if "unitId" in query:
            try:
                unit_id = int(query["unitId"][0])
            except ValueError:
                raise ProtocolError("unitId must be an own unit ID", 400)
            if unit_id not in own:
                raise ProtocolError("Native action menus are limited to own units")
        if category == "produce":
            path = "/combat/production"
        elif category == "upgrade":
            path = "/economy/investments"
        elif category in ("build", "unitMode") and unit_id is not None:
            path = ("/economy/builder-actions" if category == "build" else "/combat/unit-modes") + "?" + urlencode({"unitId": unit_id})
        else:
            raise ProtocolError("kind=produce|upgrade|build|unitMode required; build/unitMode need unitId", 400)
        status, native = self.upstream("GET", path)
        if status != 200:
            raise ProtocolError("Native own action menu unavailable", status)
        self._same_session(native)
        records = []
        allowed = ("actionId", "type", "product", "mode", "cost", "available", "affordable", "buildAction")
        if category == "produce":
            for producer in native.get("factories", []):
                actor = producer.get("id")
                if actor in own and (unit_id is None or unit_id == actor):
                    for entry in producer.get("actions", []):
                        records.append({"unitId": actor, **{key: entry[key] for key in allowed if key in entry}})
        elif category == "upgrade":
            for entry in native.get("units", []):
                actor = entry.get("id")
                if actor in own and (unit_id is None or unit_id == actor):
                    records.append({"unitId": actor, **{key: entry[key] for key in allowed if key in entry}})
        else:
            for entry in native.get("actions", []):
                if category == "build" and not entry.get("buildAction", False):
                    continue
                records.append({"unitId": unit_id, **{key: entry[key] for key in allowed if key in entry}})
        return {"protocolVersion": VERSION, "identity": dict(self.identity), "kind": category,
                "nativeOwnActions": records, "availabilitySemantics": "NATIVE_MENU_NOT_PLACEMENT_OR_EFFECT_PROOF"}

    def _action_path(self, action):
        kind = action.get("kind")
        ids = action.get("unitIds")
        if not isinstance(ids, list) or not 1 <= len(ids) <= 48 or len(set(ids)) != len(ids):
            raise ProtocolError("unitIds must contain 1..48 distinct own unit IDs", 400)
        ids = [_integer(item, "unitId") for item in ids]
        plural = kind == "attackMove"
        if not plural and len(ids) != 1:
            raise ProtocolError("This native action requires exactly one unit", 400)
        params = {"unitIds" if plural else "unitId": ",".join(map(str, ids)) if plural else ids[0]}
        if kind in ("move", "attackMove", "build"):
            params.update(x=_finite(action.get("x"), "x"), y=_finite(action.get("y"), "y"))
        if kind == "move":
            route = "/command/move"
        elif kind == "attackMove":
            route = "/command/attack-move"
        elif kind == "guard":
            route = "/command/guard"
            params["targetId"] = _integer(action.get("targetId"), "targetId")
        elif kind == "build" and action.get("unitType") in ("extractor", "landFactory"):
            route = "/command/build-extractor" if action["unitType"] == "extractor" else "/command/build-factory"
        elif kind in ("produce", "upgrade", "unitMode", "build"):
            route = {"produce": "/command/queue", "upgrade": "/command/invest",
                     "unitMode": "/command/unit-mode", "build": "/command/construct"}[kind]
            native_action = action.get("nativeActionId")
            if not isinstance(native_action, str) or not native_action or len(native_action) > 128:
                raise ProtocolError("nativeActionId must come from legal native action menu", 400)
            params["actionId"] = native_action
        else:
            raise ProtocolError("Unsupported native action kind", 400)
        return route, params, ids

    def action(self, action):
        with self.lock:
            if not isinstance(action, dict) or action.get("protocolVersion") != VERSION:
                raise ProtocolError("protocolVersion=1 required", 400)
            state = self._state()
            if action.get("identity") != self.identity:
                raise ProtocolError("Action identity does not match bound player")
            sequence = _integer(action.get("actionSequence"), "actionSequence", 1)
            observation = _integer(action.get("observationSequence"), "observationSequence", 1)
            if observation not in self.public_observations:
                raise ProtocolError("Action must reference a gateway-issued player observation")
            request_id = action.get("requestId")
            if not isinstance(request_id, str) or not REQUEST_ID.fullmatch(request_id):
                raise ProtocolError("Valid requestId required", 400)
            fingerprint = json.dumps(action, sort_keys=True, separators=(",", ":"), allow_nan=False)
            cached = self.receipts.get(request_id)
            if cached:
                if cached[0] != fingerprint:
                    raise ProtocolError("requestId reused for a different action")
                return cached[1], cached[2]
            if sequence <= self.action_sequence:
                raise ProtocolError("actionSequence must increase")
            route, params, ids = self._action_path(action)
            own = {unit.get("id") for unit in state.get("ownUnits", []) if not unit.get("dead", False)}
            if any(unit not in own for unit in ids):
                raise ProtocolError("Action authority is limited to current own units")
            params.update(sessionId=self.identity["sessionId"], requestId=request_id)
            status, reply = self.upstream("POST", route + "?" + urlencode(params))
            if status == 200:
                self._same_session(reply)
            self.action_sequence = sequence
            receipt = {"protocolVersion": VERSION, "identity": dict(self.identity),
                       "actionSequence": sequence, "observationSequence": observation,
                       "requestId": request_id, "receiptId": f"{self.identity['sessionId']}:{request_id}",
                       "status": "QUEUED" if status == 200 and reply.get("status") == "queued" else "REJECTED",
                       "executedEffect": "UNKNOWN", "nativeReceipt": reply}
            if status == 200 and receipt["status"] != "QUEUED":
                raise ProtocolError("Unexpected native command acknowledgement", 502)
            self.receipts[request_id] = (fingerprint, status, receipt)
            self.record("player_action", action=action, receipt=receipt, nativeStatus=status)
            return status, receipt

    def compatibility(self, method, path):
        with self.lock:
            if self.agent["adapterKind"] != "frozen-client-compatibility":
                raise ProtocolError("Compatibility routes require an explicit frozen-agent adapter", 404)
            parts = urlsplit(path)
            route = parts.path
            if method == "GET" and route not in COMPAT_READS or method == "POST" and route not in COMMAND_KINDS:
                raise ProtocolError("Endpoint is outside the player contract", 404)
            if method not in ("GET", "POST"):
                raise ProtocolError("Unsupported method", 405)
            state = self._state()
            if method == "POST":
                query = parse_qs(parts.query, keep_blank_values=True)
                if any(len(values) != 1 for values in query.values()):
                    raise ProtocolError("Duplicate compatibility query parameters", 400)
                supplied_session = query.get("sessionId", [self.identity["sessionId"]])[0]
                if supplied_session != self.identity["sessionId"]:
                    raise ProtocolError("Compatibility action session mismatch")
                query["sessionId"] = [supplied_session]
                raw_ids = query.get("unitIds", query.get("unitId", [""]))[0]
                try:
                    ids = [int(value) for value in raw_ids.split(",")]
                except ValueError:
                    raise ProtocolError("Compatibility action requires own unit IDs", 400)
                own = {unit.get("id") for unit in state.get("ownUnits", []) if not unit.get("dead", False)}
                if not 1 <= len(ids) <= 48 or any(unit not in own for unit in ids):
                    raise ProtocolError("Compatibility command cannot control other players")
                path = route + "?" + urlencode({key: values[0] for key, values in query.items()})
            if route == "/state":
                status, reply = 200, state
            else:
                status, reply = self.upstream(method, path)
                if status == 200 and route != "/health":
                    self._same_session(reply)
            if route == "/health" and status == 200:
                reply = {**reply, "protocolVersion": VERSION, "identity": self.identity,
                         "agent": self.agent, "transport": TRANSPORT}
            if method == "POST":
                self.action_sequence += 1
                self.record("compatibility_player_action", actionSequence=self.action_sequence,
                            kind=COMMAND_KINDS[route], requestPath=path,
                            nativeReceipt=reply, nativeStatus=status, executedEffect="UNKNOWN")
            elif route in ("/state", "/combat/observe", "/scout/observe"):
                self.observation_sequence += 1
                self.record("compatibility_player_observation", observationSequence=self.observation_sequence,
                            requestPath=route, sourceFrame=reply.get("frame"),
                            ownUnitCount=len(reply.get("ownUnits", [])),
                            currentVisibleEnemyCount=len(reply.get("visibleEnemies", [])))
            return status, reply

    def dispatch(self, method, path, body=None):
        with self.lock:
            route = urlsplit(path).path
            if route.startswith("/v1/"):
                if method == "GET" and route == "/v1/capabilities":
                    return 200, self.capabilities()
                if method == "GET" and route == "/v1/identity":
                    self._state()
                    return 200, {"protocolVersion": VERSION, "identity": self.identity, "agent": self.agent}
                if method == "GET" and route == "/v1/observe":
                    return 200, self.observe()
                if method == "GET" and route == "/v1/lifecycle":
                    return 200, self.lifecycle()
                if method == "GET" and route == "/v1/action-menu":
                    return 200, self.action_menu(parse_qs(urlsplit(path).query, keep_blank_values=True))
                if method == "POST" and route == "/v1/actions":
                    return self.action(body)
                raise ProtocolError("Unknown v1 player endpoint", 404)
            return self.compatibility(method, path)

    def serve(self, port):
        gateway = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_GET(self):
                self.handle_request()
            def do_POST(self):
                self.handle_request()
            def handle_request(self):
                try:
                    if self.headers.get("Origin") is not None:
                        raise ProtocolError("Browser-origin control disabled", 403)
                    body = None
                    if self.command == "POST" and urlsplit(self.path).path == "/v1/actions":
                        length = int(self.headers.get("Content-Length", "0"))
                        if not 1 <= length <= 65536:
                            raise ProtocolError("Action JSON body length must be 1..65536", 400)
                        body = json.loads(self.rfile.read(length))
                    status, result = gateway.dispatch(self.command, self.path, body)
                except ProtocolError as error:
                    status, result = error.status, {"protocolVersion": VERSION, "error": str(error)}
                    gateway.record("player_request_rejected", path=self.path, reason=str(error), status=status)
                except (ValueError, TypeError, KeyError) as error:
                    status, result = 400, {"protocolVersion": VERSION, "error": str(error)}
                raw = json.dumps(result, separators=(",", ":"), allow_nan=False).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)
        self._server = ThreadingHTTPServer(("127.0.0.1", int(port)), Handler)
        self._server.daemon_threads = True
        self._server.serve_forever()

    def shutdown(self):
        if self._server:
            self._server.shutdown()
            self._server.server_close()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream-port", type=int, required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--match-id", required=True)
    parser.add_argument("--player-id", type=int)
    parser.add_argument("--agent", default="external")
    parser.add_argument("--artifact-digest")
    parser.add_argument("--evidence")
    args = parser.parse_args()
    gateway = PlayerGateway(args.upstream_port, args.match_id, args.agent,
                            player_id=args.player_id, evidence=args.evidence,
                            external_digest=args.artifact_digest)
    try:
        gateway.serve(args.port)
    except KeyboardInterrupt:
        pass
    finally:
        if gateway._server:
            gateway._server.server_close()

if __name__ == "__main__":
    main()
