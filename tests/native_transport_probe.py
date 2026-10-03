#!/usr/bin/env python3
"""Black-box M0 proof using only each player's legal HTTP bridge.

Run while a probe/probe match is alive. Cross-player observations are kept only
in the test evidence; neither player receives the other player's observation.
Rendezvous targets are fixed map-center coordinates, never hidden enemy targets.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import time
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from protocol.gateway import PlayerGateway


class Probe:
    def __init__(self, participants, out):
        self.players = participants
        self.out = Path(out)
        self.out.mkdir(parents=True, exist_ok=True)
        self.raw = (self.out / "legal-observations.jsonl").open("w", encoding="utf-8")
        self.checks = []
        self.commands = []
        self.gateways = {}

    def request(self, side, path, params=None, method="GET"):
        suffix = "?" + urllib.parse.urlencode(params) if params else ""
        request = urllib.request.Request(
            f"http://127.0.0.1:{self.players[side]['apiPort']}{path}{suffix}", method=method)
        try:
            with urllib.request.urlopen(request, timeout=8) as response:
                status, body = response.status, json.load(response)
        except urllib.error.HTTPError as error:
            status, body = error.code, json.load(error)
        self.raw.write(json.dumps({"wallTime": time.time(), "side": side, "path": path,
                                   "method": method, "params": params, "httpStatus": status,
                                   "body": body}) + "\n")
        self.raw.flush()
        return status, body

    def get(self, side, path, params=None):
        status, body = self.request(side, path, params)
        if status != 200:
            raise AssertionError(f"side {side} {path}: HTTP {status}: {body}")
        return body

    def check(self, name, passed, evidence=None):
        self.checks.append({"name": name, "status": "PASS" if passed else "FAIL", "evidence": evidence})
        if not passed:
            raise AssertionError(name)

    def move(self, side, state, unit, x, y, request_id=None):
        fields = {"unitId": unit["id"], "x": x, "y": y, "sessionId": state["sessionId"],
                  "requestId": request_id or uuid.uuid4().hex}
        if side not in self.gateways:
            self.gateways[side] = PlayerGateway(self.players[side]["apiPort"], state["matchId"],
                "native-m0-probe", player_id=state["playerId"], evidence=self.out / f"public-v1-side-{side}.jsonl",
                external_digest="sha256:" + hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
        gateway = self.gateways[side]
        observation = gateway.observe()
        action = {"protocolVersion": 1, "identity": observation["identity"],
                  "observationSequence": observation["observationSequence"],
                  "actionSequence": len(self.commands) + 1, "requestId": fields["requestId"],
                  "kind": "move", "unitIds": [unit["id"]], "x": x, "y": y}
        # Real public v1 handler -> HTTP player bridge -> native command controller.
        status, envelope = gateway.dispatch("POST", "/v1/actions", action)
        receipt = envelope["nativeReceipt"]
        self.check(f"side_{side}_move_queued_{len(self.commands)}", status == 200 and receipt.get("status") == "queued", receipt)
        self.commands.append({"side": side, "request": fields, "receipt": receipt, "publicV1Receipt": envelope})
        return receipt, fields

    def run(self, timeout=90, surrender=False):
        states = [self.get(i, "/state") for i in range(2)]
        self.check("native_network_capability", all(s.get("networked") is True
                   and s.get("nativeNetworkPlayerV1") is True for s in states))
        self.check("distinct_players_and_teams", states[0]["playerId"] != states[1]["playerId"]
                   and states[0]["teamId"] != states[1]["teamId"],
                   [{k: s[k] for k in ("playerId", "teamId", "sessionId", "matchId")} for s in states])
        self.check("separate_sessions_same_match", states[0]["sessionId"] != states[1]["sessionId"]
                   and states[0]["matchId"] == states[1]["matchId"])
        self.check("adjacent_native_frames", abs(states[0]["frame"]-states[1]["frame"]) <= 60,
                   [s["frame"] for s in states])
        own_ids = [{u["id"] for u in s["ownUnits"]} for s in states]
        self.check("disjoint_command_authority", bool(own_ids[0]) and bool(own_ids[1]) and not own_ids[0] & own_ids[1])
        builders = [next(u for u in s["ownUnits"] if u["type"] == "builder" and u.get("mobile")) for s in states]
        combats = [self.get(i, "/combat/observe") for i in range(2)]
        self.check("enemy_starts_hidden", all(not ({u["id"] for u in combats[i]["visibleEnemies"]} & own_ids[1-i]) for i in range(2)))
        map_info = states[0]["map"]
        tiles = [int(u["x"] / map_info["tileWidth"]) * map_info["tilesHigh"]
                 + int(u["y"] / map_info["tileHeight"]) for u in builders]
        fog = [self.get(i, "/scout/visible", {"tiles": ",".join(map(str, tiles))}) for i in range(2)]
        self.check("different_local_fog", [t["visible"] for t in fog[0]["tiles"]] == [True, False]
                   and [t["visible"] for t in fog[1]["tiles"]] == [False, True], fog)
        for side in range(2):
            forbidden = {"unitId": builders[1-side]["id"], "x": 100, "y": 100,
                         "sessionId": states[side]["sessionId"], "requestId": uuid.uuid4().hex}
            code, foreign = self.request(side, "/command/move", forbidden, "POST")
            self.check(f"side_{side}_foreign_control_rejected", code == 409, foreign)
            forbidden.update(unitId=9223372036854775000, requestId=uuid.uuid4().hex)
            code, unknown = self.request(side, "/command/move", forbidden, "POST")
            self.check(f"side_{side}_foreign_existence_not_disclosed", code == 409 and foreign == unknown)
            forbidden.update(unitId=builders[side]["id"], sessionId=states[1-side]["sessionId"], requestId=uuid.uuid4().hex)
            code, stale = self.request(side, "/command/move", forbidden, "POST")
            self.check(f"side_{side}_foreign_session_rejected", code == 409, stale)
        # Both commands are based solely on static map dimensions and own unit IDs.
        center = (map_info["width"] / 2, map_info["height"] / 2)
        for side in range(2):
            receipt, fields = self.move(side, states[side], builders[side], center[0] + side * 40, center[1])
            code, repeated = self.request(side, "/command/move", fields, "POST")
            self.check(f"side_{side}_duplicate_receipt_stable", code == 200 and receipt == repeated)
            changed = dict(fields, x=fields["x"] + 1)
            code, rejected = self.request(side, "/command/move", changed, "POST")
            self.check(f"side_{side}_conflicting_request_rejected", code == 409, rejected)
        deadline = time.monotonic() + timeout
        rendezvous = None
        while time.monotonic() < deadline:
            observations = [self.get(i, "/combat/observe") for i in range(2)]
            contacts = [next((u for u in observations[i]["visibleEnemies"] if u["id"] == builders[1-i]["id"]), None) for i in range(2)]
            if all(contacts):
                rendezvous = contacts
                break
            time.sleep(.25)
        self.check("mutual_current_legal_visibility", rendezvous is not None, rendezvous)
        # Stop at current own positions through native commands, then wait for motion to settle.
        for side in range(2):
            current = self.get(side, "/state")
            unit = next(u for u in current["ownUnits"] if u["id"] == builders[side]["id"])
            self.move(side, current, unit, unit["x"], unit["y"])
        time.sleep(1.0)
        for side in range(2):
            observer = 1-side
            before = self.get(observer, "/combat/observe")
            previous = next(u for u in before["visibleEnemies"] if u["id"] == builders[side]["id"])
            current = self.get(side, "/state")
            unit = next(u for u in current["ownUnits"] if u["id"] == builders[side]["id"])
            target_x = unit["x"] + (60 if side == 0 else -60)
            receipt, _ = self.move(side, current, unit, target_x, unit["y"])
            confirmed = None
            deadline = time.monotonic() + min(timeout, 20)
            while time.monotonic() < deadline:
                observed = self.get(observer, "/combat/observe")
                contact = next((u for u in observed["visibleEnemies"] if u["id"] == unit["id"]), None)
                if contact and observed["frame"] > receipt["frame"]:
                    dx = contact["x"] - previous["x"]
                    if (dx > 15 if side == 0 else dx < -15):
                        confirmed = {"receipt": receipt, "remoteObservationFrame": observed["frame"],
                                     "before": previous, "remoteCurrentVisible": contact}
                        break
                time.sleep(.15)
            self.check(f"side_{side}_command_effect_seen_by_remote_player", confirmed is not None, confirmed)
        final = [self.get(i, "/state") for i in range(2)]
        self.check("native_result_is_readable", all(s["match"].get("source") in
                   ("native_result_screen", "native_player_flags") for s in final), [s["match"] for s in final])
        if surrender:
            (Path(self.players[1]["workDirectory"]) / "surrender.request").touch()
            deadline = time.monotonic() + 20
            outcomes = []
            while time.monotonic() < deadline:
                final = [self.get(i, "/state") for i in range(2)]
                outcomes = [s["match"]["outcome"] for s in final]
                if outcomes == ["VICTORY", "DEFEAT"]:
                    break
                time.sleep(.25)
            self.check("native_surrender_winner_loser_agree", outcomes == ["VICTORY", "DEFEAT"], [s["match"] for s in final])
        return states[0]["matchId"]


def run_probe(participants, out, timeout=90, surrender=False):
    probe = Probe(participants, out)
    result = {"evidenceLevel": "NATIVE_DUAL_PROCESS", "status": "FAIL",
              "scope": "M0 native multiplayer; command probes, not Agent strength or desktop validation"}
    try:
        result["matchId"] = probe.run(timeout, surrender)
        result["status"] = "PASS"
    except Exception as error:
        result["error"] = f"{type(error).__name__}: {error}"
    finally:
        probe.raw.close()
        result.update(checks=probe.checks, commandReceipts=probe.commands)
        (Path(out) / "summary.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--match-report", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--timeout", type=int, default=90)
    parser.add_argument("--surrender", action="store_true")
    args = parser.parse_args()
    report = json.loads(args.match_report.read_text())
    result = run_probe(report["participants"], args.out, args.timeout, args.surrender)
    print(json.dumps({"status": result["status"], "checks": len(result["checks"]), "error": result.get("error")}))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
