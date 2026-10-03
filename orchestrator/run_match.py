#!/usr/bin/env python3
"""Run two independent native clients in ONE multiplayer match.

Reuses the proven headless runner's staging, atomic reporting, session ownership
and bounded cleanup patterns. Unlike parallel-pair, host and join share a native
network port. No observation is forwarded between players.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import shutil
import signal
FORCE_SIGNAL = getattr(signal, "SIGKILL", 9)
import socket
import subprocess
import sys
import time
import uuid
from urllib.request import urlopen
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from referee.results import classify, native_outcome, same_game_evidence, synchronization_evidence

ENGINE_SHA = "8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9"
DEFAULT_MAP = "maps/skirmish/[p2]Small_Island (2p).tmx"
MAIN_CLASS = "io.rwbridge.engine.NativeNetworkRunner"


def utc():
    return datetime.now(timezone.utc).isoformat()


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1048576), b""):
            digest.update(block)
    return digest.hexdigest()


def write_json(path, payload):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as output:
        json.dump(payload, output, ensure_ascii=False, indent=2)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    for attempt in range(6):
        try:
            temporary.replace(path)
            return
        except PermissionError:
            if attempt == 5:
                raise
            time.sleep(.1 * (attempt + 1))


def verify_match_report(entry):
    """Check the persisted final report against the independently held summary."""
    path = Path(entry["report"])
    result = {"status": "FAIL", "report": str(path), "checkedUtc": utc(), "errors": []}
    try:
        raw = path.read_bytes()
        report = json.loads(raw)
        result["observedSha256"] = hashlib.sha256(raw).hexdigest()
        if entry.get("reportSha256") and result["observedSha256"] != entry["reportSha256"]:
            result["errors"].append("final report SHA256 differs from the recorded final payload")
        for key in ("matchId", "result", "sameGameEvidence", "synchronizationEvidence"):
            if key in entry and report.get(key) != entry[key]:
                result["errors"].append("persisted " + key + " differs from final batch summary")
        if not report.get("endedUtc") or report.get("result") is None:
            result["errors"].append("persisted match report is not finalized")
        if entry.get("endedUtc") and report.get("endedUtc") != entry["endedUtc"]:
            result["errors"].append("persisted endedUtc differs from final batch summary")
    except (OSError, ValueError, TypeError) as error:
        result["errors"].append(str(error))
    if not result["errors"]:
        result["status"] = "PASS"
    return result


def verify_run(out):
    """Independent post-run audit; preserve raw reports even when inconsistent."""
    out = Path(out).resolve()
    batch = json.loads((out / "batch.json").read_text(encoding="utf-8"))
    checks = [verify_match_report(entry) for entry in batch.get("matches", [])]
    result = {"schemaVersion": 1, "status": "PASS" if checks and all(c["status"] == "PASS" for c in checks) else "FAIL",
              "checkedUtc": utc(), "runDirectory": str(out), "checks": checks,
              "scope": "persisted final match report versus independently recorded batch summary and digest"}
    if not batch.get("endedUtc"):
        result["status"] = "FAIL"
        result["error"] = "batch is not finalized"
    write_json(out / "report-verification.json", result)
    print(json.dumps(result), flush=True)
    return 0 if result["status"] == "PASS" else 1


def write_final_match(path, report):
    write_json(path, report)
    persisted = json.loads(Path(path).read_text(encoding="utf-8"))
    if persisted != report:
        raise RuntimeError("Final match report readback differs from the finalized in-memory payload")
    return sha(path)


def read_runtime(work):
    try:
        result = json.loads((Path(work) / "runtime.json").read_text(encoding="utf-8"))
        return result if isinstance(result, dict) else {}
    except (OSError, ValueError):
        return {}


def stage(game, work, bridge, baseline):
    """Link only original engine resources; never copy preferences or saves."""
    work.mkdir(parents=True, exist_ok=False)
    staged = []
    for name in ("game-lib.jar", "libs", "assets", "res"):
        source, target = game / name, work / name
        if not source.exists():
            raise FileNotFoundError(source)
        try:
            target.symlink_to(source, target_is_directory=source.is_dir())
            method = "symlink"
        except OSError:
            if source.is_dir():
                shutil.copytree(source, target)
            else:
                shutil.copy2(source, target)
            method = "copy"
        staged.append({"source": str(source), "target": str(target), "method": method})
    for source, name in ((bridge, "bridge.jar"), (baseline, "rw-agent-bootstrap.jar")):
        shutil.copy2(source, work / name)
        staged.append({"source": str(source), "target": str(work / name),
                       "method": "copy", "sha256": sha(source)})
    write_json(work / "staging.json", {"schemaVersion": 1, "items": staged})


class PortReservations:
    """Reserve all TCP ports until immediately before their owning child starts."""
    def __init__(self, requests):
        self.sockets = []
        self.ports = []
        try:
            for requested in requests:
                sock = socket.socket()
                self.sockets.append(sock)
                sock.bind(("127.0.0.1", requested))
                self.ports.append(sock.getsockname()[1])
        except BaseException:
            self.close()
            raise

    def release(self, index):
        self.sockets[index].close()

    def close(self):
        for sock in self.sockets:
            sock.close()


class Supervisor:
    def __init__(self, out):
        self.out = Path(out)
        self.children = []
        self.claims = {"schemaVersion": 1, "batchDirectory": str(self.out),
                       "orchestratorPid": os.getpid(), "startedUtc": utc(), "processes": []}
        self.save()

    def save(self):
        self.claims["updatedUtc"] = utc()
        write_json(self.out / "live-claims.json", self.claims)

    def launch(self, command, cwd, log, role, env=None):
        output = Path(log).open("wb")
        options = {"start_new_session": True} if os.name != "nt" else {
            "creationflags": subprocess.CREATE_NEW_PROCESS_GROUP}
        try:
            proc = subprocess.Popen(command, cwd=cwd, stdout=output,
                                    stderr=subprocess.STDOUT, env=env, **options)
        except BaseException:
            output.close()
            raise
        entry = {"pid": proc.pid, "role": role, "workDirectory": str(cwd),
                 "command": command, "log": str(log), "startedUtc": utc(), "state": "RUNNING"}
        if os.name != "nt":
            entry["processStartTicks"] = process_start_ticks(proc.pid)
        self.children.append((proc, output, entry))
        self.claims["processes"].append(entry)
        self.save()
        return proc

    def stop_all(self):
        errors = []
        # Agent/gateway must stop before engine teardown.
        for proc, output, entry in reversed(self.children):
            if entry["state"] == "EXITED":
                continue
            try:
                stop_process(proc, Path(entry["workDirectory"]), entry["role"])
                entry.update(state="EXITED", exitCode=proc.returncode, endedUtc=utc())
            except Exception as error:
                errors.append({"pid": proc.pid, "error": str(error)})
            finally:
                output.close()
                self.save()
        return errors


def process_start_ticks(pid):
    try:
        # comm may contain spaces or parentheses; fields after its final ')' start at field 3.
        return Path("/proc/%d/stat" % pid).read_text().rsplit(")", 1)[1].split()[19]
    except OSError:
        return None


def terminate_group(proc, sig):
    if os.name != "nt":
        try:
            os.killpg(proc.pid, sig)
        except ProcessLookupError:
            pass
    elif sig == signal.SIGTERM:
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
    else:
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)


def stop_process(proc, work, role):
    if role == "engine" and proc.poll() is None:
        try:
            (work / "stop.request").touch()
            proc.wait(timeout=3)
        except (OSError, subprocess.TimeoutExpired):
            pass
    # Kill the owned process group even if its leader exited, to reap descendants.
    terminate_group(proc, signal.SIGTERM)
    try:
        proc.wait(timeout=3)
    except subprocess.TimeoutExpired:
        terminate_group(proc, FORCE_SIGNAL)
        proc.wait(timeout=5)
    if os.name != "nt":
        terminate_group(proc, FORCE_SIGNAL)


def reap_run(out):
    """Crash recovery: verify Linux PID birth, cwd and full argv before signaling."""
    out = Path(out).resolve()
    claims = json.loads((out / "live-claims.json").read_text(encoding="utf-8"))
    if claims.get("batchDirectory") != str(out):
        raise RuntimeError("Process claim directory differs from requested run")
    result = {"schemaVersion": 1, "utc": utc(), "reaped": [], "skipped": [], "errors": []}
    for entry in claims.get("processes", []):
        if entry.get("state") != "RUNNING":
            continue
        pid = entry["pid"]
        birth = process_start_ticks(pid)
        if birth is None:
            result["skipped"].append({"pid": pid, "reason": "not running"})
            continue
        try:
            if os.name == "nt":
                raise RuntimeError("Verified crash recovery currently requires Linux; ordinary cleanup supports Windows")
            if not entry.get("processStartTicks") or entry["processStartTicks"] != birth:
                raise RuntimeError("PID birth differs; refusing to signal a reused PID")
            work = Path(entry["workDirectory"]).resolve()
            work.relative_to(out)
            if Path("/proc/%d/cwd" % pid).resolve() != work:
                raise RuntimeError("Process working directory differs")
            argv = Path("/proc/%d/cmdline" % pid).read_bytes().rstrip(b"\0").decode().split("\0")
            if argv != entry["command"] or os.getpgid(pid) != pid:
                raise RuntimeError("Process argv or owned group differs")
            os.killpg(pid, signal.SIGTERM)
            deadline = time.monotonic() + 3
            while process_start_ticks(pid) == birth and time.monotonic() < deadline:
                time.sleep(.05)
            if process_start_ticks(pid) == birth:
                os.killpg(pid, FORCE_SIGNAL)
            result["reaped"].append(pid)
            entry.update(state="REAPED", endedUtc=utc())
        except (OSError, ValueError, RuntimeError) as error:
            result["errors"].append({"pid": pid, "error": str(error)})
    write_json(out / "live-claims.json", claims)
    write_json(out / "reap-report.json", result)
    print(json.dumps(result), flush=True)
    return 1 if result["errors"] else 0


def engine_command(args, role, port, network_port, match_id, probe=False):
    cp = os.pathsep.join(("bridge.jar", "rw-agent-bootstrap.jar", "game-lib.jar", "libs/*"))
    return [args.java, "-Djava.awt.headless=true", "-cp", cp, MAIN_CLASS,
            "--role", role, "--connect", "127.0.0.1:%d" % network_port,
            "--network-port", str(network_port), "--port", str(port), "--map", args.map,
            "--match-id", match_id, "--speed", str(args.speed),
            "--max-wall-seconds", str(math.ceil(args.timeout + args.startup_timeout +
                                                (getattr(args, "proof_timeout", 90) if getattr(args, "transport_proof", False) else 0) + 15)),
            "--auto-start", "true", "--fog", str(args.fog), "--credits", str(args.credits),
            "--probe", str(probe).lower()]


def owned_runtime(runtime, match_id):
    if runtime and runtime.get("matchId") != match_id:
        raise RuntimeError("Native runtime belongs to a different match")


def ready(runtime):
    return (runtime.get("networked") is True and runtime.get("networkStarted") is True
            and runtime.get("localPlayerBound") is True and runtime.get("playerId") is not None)


def sample_lifecycle(participant, match_id):
    """Read a local player's legal API; retain only lifecycle/result metadata."""
    with urlopen("http://127.0.0.1:%d/state" % participant["apiPort"], timeout=2) as response:
        state = json.load(response)
    runtime = participant["runtime"]
    if state.get("matchId") != match_id or state.get("playerId") != runtime.get("playerId"):
        raise RuntimeError("Player API identity does not match the owned native client")
    if state.get("networked") is not True or state.get("nativeNetworkPlayerV1") is not True:
        raise RuntimeError("Player API did not attest the native network player binding")
    session = state.get("sessionId")
    if not session:
        raise RuntimeError("Player API has no session identity")
    if participant.get("sessionId") and participant["sessionId"] != session:
        raise RuntimeError("Player API session changed during match")
    participant["sessionId"] = session
    participant["apiLifecycle"] = {key: state.get(key) for key in
                                   ("matchId", "sessionId", "playerId", "teamId", "frame", "gameTimeMs", "commandTransport")}
    participant["apiLifecycle"]["nativeResult"] = state.get("match", "UNKNOWN")
    runtime["sessionId"] = session
    runtime["nativeResult"] = state.get("match", "UNKNOWN")


def collect_adapter_evidence(participant, transport_proof=None):
    from protocol.gateway import COMMAND_KINDS
    work = Path(participant["workDirectory"])
    evidence = work / "adapter/gateway-events.jsonl"
    counts = {"status": "OBSERVED", "attempted": 0, "nativeQueued": 0,
              "rejected": 0, "nativeRejected": 0, "gatewayCommandRejected": 0,
              "noncommandRequestRejections": 0, "byKind": {}, "executedEffects": "UNKNOWN",
              "semantics": "HTTP command submissions; native queue receipt is not executed effect"}
    if evidence.is_file():
        with evidence.open(encoding="utf-8") as source:
            for line in source:
                row = json.loads(line)
                if row.get("identity", {}).get("matchId") != participant["runtime"].get("matchId"):
                    raise RuntimeError("Gateway evidence belongs to another match")
                if row.get("event") in ("player_action", "compatibility_player_action"):
                    counts["attempted"] += 1
                    kind = row.get("kind", row.get("action", {}).get("kind", "unknown"))
                    counts["byKind"][kind] = counts["byKind"].get(kind, 0) + 1
                    receipt = row.get("nativeReceipt", row.get("receipt", {}))
                    if row.get("nativeStatus") == 200 and receipt.get("status") == "queued":
                        counts["nativeQueued"] += 1
                    elif row.get("nativeStatus", 0) >= 400:
                        counts["nativeRejected"] += 1
                        counts["rejected"] += 1
                elif row.get("event") == "player_request_rejected":
                    route = urlsplit(row.get("path", "")).path
                    if route in COMMAND_KINDS or route.startswith("/command/") or route == "/v1/actions":
                        counts["attempted"] += 1
                        counts["gatewayCommandRejected"] += 1
                        counts["rejected"] += 1
                        kind = COMMAND_KINDS.get(route, "unknown")
                        counts["byKind"][kind] = counts["byKind"].get(kind, 0) + 1
                    else:
                        counts["noncommandRequestRejections"] += 1
        participant["commandCounts"] = counts
        participant["logs"]["gatewayEvidence"] = str(evidence)
    elif participant["agentId"] == "probe" and transport_proof:
        receipts = [row for row in transport_proof.get("commandReceipts", [])
                    if row.get("side") == participant["side"]]
        queued = sum(row.get("receipt", {}).get("status") == "queued" for row in receipts)
        effect_check = "side_%d_command_effect_seen_by_remote_player" % participant["side"]
        effect = next((row for row in transport_proof.get("checks", []) if row.get("name") == effect_check), {})
        participant["commandCounts"] = {"status": "OBSERVED_PUBLIC_API_PROBE", "attempted": len(receipts),
                                        "nativeQueued": queued, "byKind": {"move": len(receipts)},
                                        "executedEffects": {"scope": "probe moves only; consult the legal-observation checks",
                                                            "status": effect.get("status", "NEEDS_EVIDENCE"),
                                                            "checkName": effect_check,
                                                            "reportPath": transport_proof.get("reportPath")}}
    elif participant["agentId"] == "probe":
        queued = participant["runtime"].get("probeQueued") is True
        participant["commandCounts"] = {"status": "OBSERVED_NATIVE_PROBE", "nativeQueued": int(queued),
                                        "executedEffects": "OWN_DISTANCE_ONLY", "ownMoveDistance": participant["runtime"].get("probeDistance")}
    participant["reports"] = [{"path": str(path), "sha256": sha(path)}
                              for path in sorted(work.glob("adapter/rw-agent-reports/*")) if path.is_file()]


def disconnect_evidence(participants):
    """An established native peer leaving is a disconnect, without a winner."""
    evidence = []
    for p in participants:
        runtime = p.get("runtime", {})
        if runtime.get("status") == "DISCONNECTED" or (p.get("nativeReadyUtc") and runtime.get("networked") is False):
            evidence.append({"participantId": p["participantId"], "source": "native_network_lifecycle",
                             "status": runtime.get("status"), "networked": runtime.get("networked")})
        elif p.get("role") == "host" and p.get("nativeConnectionsAtStart", 0) > 0 and runtime.get("nativeConnections") == 0:
            evidence.append({"participantId": p["participantId"], "source": "native_host_peer_departure",
                             "connectionsAtStart": p["nativeConnectionsAtStart"], "connectionsNow": 0})
    return evidence


def run_one(args, index, supervisor, on_ready=None):
    match_id = "rwbridge-" + uuid.uuid4().hex
    match_dir = args.out / ("match-%03d" % index)
    match_dir.mkdir()
    order = [args.agent_a, args.agent_b]
    ids = ["A", "B"]
    if args.swap_sides and index % 2 == 0:
        order.reverse()
        ids.reverse()
    report = {"schemaVersion": 1, "matchId": match_id, "matchIndex": index,
              "map": args.map, "fog": args.fog, "startingCredits": args.credits,
              "speed": args.speed, "createdUtc": utc(), "startedUtc": None, "endedUtc": None,
              "participants": [], "events": [], "result": None}
    report_path = match_dir / "match.json"
    reservations = PortReservations([args.network_port, args.port_a, args.port_b, 0, 0])
    network_port, port_a, port_b, gateway_a, gateway_b = reservations.ports
    report["networkPort"] = network_port
    children_before = len(supervisor.children)
    reason = "unknown"
    try:
        for slot, (agent, identity, api_port, gateway_port) in enumerate(zip(order, ids, (port_a, port_b), (gateway_a, gateway_b))):
            work = match_dir / ("player-%s" % identity)
            stage(args.game_dir, work, args.bridge_jar, args.baseline_jar)
            lock = work / "match.lock"
            lock.write_text(match_id + "\n", encoding="utf-8")
            if agent == "probe":
                artifact = args.baseline_jar
            else:
                from adapters.overlay import artifact as frozen_artifact
                artifact = Path(frozen_artifact(agent, ROOT)["binary"])
            participant = {"participantId": identity, "agentId": agent, "side": slot,
                           "role": "host" if slot == 0 else "join", "apiPort": api_port,
                           "gatewayPort": gateway_port, "workDirectory": str(work),
                           "lockPath": str(lock), "artifact": str(artifact),
                           "artifactSha256": sha(artifact), "bridgeSha256": sha(args.bridge_jar),
                           "runtime": {}, "commandCounts": {"status": "UNKNOWN"}, "logs": {}}
            report["participants"].append(participant)
            reservations.release(slot + 1)
            if slot == 0:
                reservations.release(0)
            log = work / "engine.log"
            command = engine_command(args, participant["role"], api_port, network_port, match_id,
                                     probe=agent == "probe" and not args.transport_proof)
            proc = supervisor.launch(command, work, log, "engine", args.child_env)
            participant.update(enginePid=proc.pid, engineCommand=command)
            participant["logs"]["engine"] = str(log)
            participant["_process"] = proc
            write_json(report_path, public_report(report))
            if slot == 0:
                # Give the native server time to bind; readiness is verified below.
                wait_until = time.monotonic() + min(args.startup_timeout, 20)
                while not read_runtime(work) and proc.poll() is None and time.monotonic() < wait_until:
                    time.sleep(.1)
        deadline = time.monotonic() + args.startup_timeout
        while True:
            for p in report["participants"]:
                p["runtime"] = read_runtime(p["workDirectory"])
                owned_runtime(p["runtime"], match_id)
            if all(ready(p["runtime"]) for p in report["participants"]):
                break
            if any(p["_process"].poll() is not None for p in report["participants"]):
                reason = "crash"
                raise RuntimeError("A native client exited before both clients became ready")
            if time.monotonic() >= deadline:
                reason = "startup_timeout"
                raise TimeoutError("Native host/join readiness timed out")
            time.sleep(args.poll_ms / 1000)
        report["startedUtc"] = utc()
        for p in report["participants"]:
            sample_lifecycle(p, match_id)
            p["nativeConnectionsAtStart"] = p["runtime"].get("nativeConnections", 0)
            p["nativeReadyUtc"] = report["startedUtc"]
        report["events"].append({"event": "both_native_clients_ready", "utc": utc()})
        report["sameGameEvidence"] = same_game_evidence(report["participants"])
        if report["sameGameEvidence"]["status"] != "PASS":
            raise RuntimeError("Distinct players and common native server identity are not proven")
        if on_ready is not None:
            on_ready(report, args)
        for slot, p in enumerate(report["participants"]):
            if p["agentId"] != "probe":
                start_adapter(args, p, supervisor, reservations, slot)
        write_json(report_path, public_report(report))
        deadline = time.monotonic() + args.timeout
        engine_exit_detected_at = None
        api_failure_since = {}
        while True:
            for p in report["participants"]:
                p["runtime"] = read_runtime(p["workDirectory"])
                owned_runtime(p["runtime"], match_id)
            # Refresh BOTH clients before any API access, since a disconnected
            # bridge correctly refuses reads that cannot attest local binding.
            native_classification = classify(report["participants"], "native_result")
            if native_classification["status"] in ("NATIVE_RESULT", "NATIVE_DRAW"):
                reason = "native_result"
                break
            departed = disconnect_evidence(report["participants"])
            if departed:
                report["disconnectEvidence"] = departed
                reason = "disconnect"
                break
            exited = [p for p in report["participants"] if p["_process"].poll() is not None]
            for p in report["participants"]:
                if p["_process"].poll() is None and p["runtime"].get("networked") is True:
                    try:
                        sample_lifecycle(p, match_id)
                        api_failure_since.pop(p["participantId"], None)
                    except OSError as error:
                        # The peer can leave between reading its runtime file
                        # and reading HTTP. Let native lifecycle catch up first.
                        first_failure = api_failure_since.setdefault(p["participantId"], time.monotonic())
                        p["lastApiReadError"] = str(error)
                        p["apiReadFailureCount"] = p.get("apiReadFailureCount", 0) + 1
                        if time.monotonic() - first_failure >= 2:
                            raise
            classification = classify(report["participants"], "native_result")
            if classification["status"] in ("NATIVE_RESULT", "NATIVE_DRAW"):
                reason = "native_result"
                break
            if exited:
                if engine_exit_detected_at is None:
                    engine_exit_detected_at = time.monotonic()
                # Allow the remaining original engine to publish the peer loss.
                if time.monotonic() - engine_exit_detected_at >= 2:
                    reason = "crash"
                    break
            if any(proc.poll() is not None and proc.returncode != 0 for proc, _, entry in supervisor.children[children_before:] if entry["role"] != "engine"):
                reason = "crash"
                break
            if time.monotonic() >= deadline:
                reason = "timeout"
                break
            write_json(report_path, public_report(report))
            time.sleep(args.poll_ms / 1000)
    except KeyboardInterrupt:
        reason = "interrupted"
        report["error"] = "Interrupted by user"
    except Exception as error:
        if reason == "unknown":
            reason = "crash"
        report["error"] = str(error)
    finally:
        reservations.close()
        for p in report["participants"]:
            p["engineExitedBeforeCleanup"] = "_process" in p and p["_process"].poll() is not None
        # Stop just this match; earlier batch children are already reaped.
        for proc, output, entry in reversed(supervisor.children[children_before:]):
            try:
                stop_process(proc, Path(entry["workDirectory"]), entry["role"])
                entry.update(state="EXITED", exitCode=proc.returncode, endedUtc=utc())
            except Exception as error:
                report.setdefault("cleanupErrors", []).append(str(error))
            finally:
                output.close()
        supervisor.save()
        for p in report["participants"]:
            final = read_runtime(p["workDirectory"])
            if final.get("matchId") == match_id:
                p["runtime"] = final
            p["engineExitCode"] = p["_process"].returncode if "_process" in p else None
            p["sessionId"] = p.get("sessionId", p["runtime"].get("sessionId"))
            if p.get("apiLifecycle"):
                # A later original runner result can be stronger than the last
                # API snapshot taken just before peer disconnect.
                if native_outcome(p["runtime"]) == "unknown":
                    p["runtime"]["nativeResult"] = p["apiLifecycle"]["nativeResult"]
                p["runtime"]["sessionId"] = p["sessionId"]
            p["playerId"] = p["runtime"].get("playerId")
            p["teamId"] = p["runtime"].get("teamId")
            p["nativeResult"] = p["runtime"].get("nativeResult", "UNKNOWN")
            p["commandCounts"] = p["runtime"].get("commandCounts", {"status": "UNKNOWN"})
            try:
                collect_adapter_evidence(p, report.get("transportProof"))
            except Exception as error:
                report.setdefault("evidenceErrors", []).append(str(error))
            lock = Path(p["lockPath"])
            if lock.exists():
                lock.unlink()
        report.update(endedUtc=utc(), result=classify(report["participants"], reason))
        report["sameGameEvidence"] = same_game_evidence(report["participants"])
        report["synchronizationEvidence"] = synchronization_evidence(report["participants"])
        report["crashes"] = [{"participantId": p["participantId"], "pid": p.get("enginePid"),
                              "exitCode": p.get("engineExitCode"), "log": p.get("logs", {}).get("engine")}
                             for p in report["participants"] if p.get("engineExitedBeforeCleanup") and p.get("engineExitCode") not in (None, 0)]
        write_final_match(report_path, public_report(report))
    return public_report(report)


def public_report(report):
    # subprocess handles must never enter reports; units never enter this structure.
    result = dict(report)
    result["participants"] = [{k: v for k, v in p.items() if not k.startswith("_")}
                              for p in report["participants"]]
    return result


def start_adapter(args, participant, supervisor, reservations, slot):
    # The adapter implementation owns the compatibility API and internal Agent classes.
    from adapters.launch import command, gateway_start_command
    work = Path(participant["workDirectory"]) / "adapter"
    work.mkdir()
    reservations.release(slot + 3)
    gateway = gateway_start_command(participant["apiPort"], participant["gatewayPort"],
                                    participant["runtime"]["matchId"], participant["agentId"], ROOT,
                                    evidence=work / "gateway-events.jsonl", player_id=participant["runtime"]["playerId"])
    gateway_log = work / "gateway.log"
    gateway_proc = supervisor.launch(gateway, work, gateway_log, "gateway", args.child_env)
    participant["gatewayPid"] = gateway_proc.pid
    participant["logs"]["gateway"] = str(gateway_log)
    deadline = time.monotonic() + 10
    while True:
        try:
            with urlopen("http://127.0.0.1:%d/v1/identity" % participant["gatewayPort"], timeout=.5) as response:
                gateway_identity = json.load(response)
            expected = {"matchId": participant["runtime"]["matchId"], "playerId": participant["runtime"]["playerId"],
                        "teamId": participant["runtime"]["teamId"], "sessionId": participant["sessionId"]}
            if gateway_identity.get("identity") != expected:
                raise RuntimeError("Adapter gateway identity does not match owned player/session")
            participant["adapterArtifact"] = gateway_identity.get("agent", {})
            break
        except OSError:
            if gateway_proc.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError("Adapter gateway did not become ready")
            time.sleep(.1)
    agent_command = command(participant["agentId"], participant["gatewayPort"],
                            max(120, math.ceil(args.timeout * args.speed)), ROOT)
    log = work / "agent.log"
    proc = supervisor.launch(agent_command, work, log, "agent", args.child_env)
    participant.update(agentPid=proc.pid, agentCommand=agent_command)
    participant["logs"]["agent"] = str(log)


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--game-dir", type=Path, default=ROOT / ".engine/rw115")
    p.add_argument("--bridge-jar", type=Path, default=ROOT / "build/bridge.jar")
    p.add_argument("--baseline-jar", type=Path, default=ROOT / "binaries/octopus-g42-83c09fb.jar")
    p.add_argument("--java", default="java")
    p.add_argument("--agent-a", choices=("probe", "legacy", "octopus", "octopus-g5"), default="probe")
    p.add_argument("--agent-b", choices=("probe", "legacy", "octopus", "octopus-g5"), default="probe")
    p.add_argument("--matches", type=int, default=1)
    p.add_argument("--swap-sides", action="store_true", help="swap A/B native host/join slots every other match")
    p.add_argument("--transport-proof", action="store_true", help="run the legal-observation M0 proof and native surrender after readiness (probe/probe only)")
    p.add_argument("--proof-timeout", type=int, default=90)
    p.add_argument("--map", default=DEFAULT_MAP)
    p.add_argument("--fog", type=int, choices=(0, 1, 2), default=2)
    p.add_argument("--credits", type=int, default=4000)
    p.add_argument("--speed", type=float, default=1)
    p.add_argument("--timeout", type=float, default=60)
    p.add_argument("--startup-timeout", type=float, default=60)
    p.add_argument("--poll-ms", type=int, default=250)
    p.add_argument("--network-port", type=int, default=0)
    p.add_argument("--port-a", type=int, default=0)
    p.add_argument("--port-b", type=int, default=0)
    p.add_argument("--out", type=Path, default=None)
    p.add_argument("--reap", type=Path, help="recover owned processes after an interrupted Linux harness")
    p.add_argument("--verify-run", type=Path, help="audit final match reports against persisted batch summaries and hashes")
    return p


def transport_proof_callback(report, args):
    spec = importlib.util.spec_from_file_location("native_transport_probe", ROOT / "tests/native_transport_probe.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    out = Path(report["participants"][0]["workDirectory"]).parent / "transport-proof"
    report["transportProof"] = module.run_probe(report["participants"], out,
                                               timeout=args.proof_timeout, surrender=True)
    report["transportProof"]["reportPath"] = str(out / "summary.json")
    if report["transportProof"]["status"] != "PASS":
        raise RuntimeError("Native transport proof failed: " + report["transportProof"].get("error", "unknown"))


def main(argv=None):
    p = parser()
    args = p.parse_args(argv)
    if args.verify_run:
        return verify_run(args.verify_run)
    if args.reap:
        return reap_run(args.reap)
    if args.matches < 1 or args.matches > 100 or not 0 < args.timeout <= 3600 or not 1 <= args.startup_timeout <= 300 or not 0 < args.speed <= 8 or not 50 <= args.poll_ms <= 1000:
        p.error("Invalid bounded runtime option")
    if not 1 <= args.proof_timeout <= 300 or args.timeout + args.startup_timeout + (args.proof_timeout if args.transport_proof else 0) + 15 > 3600:
        p.error("Total native runtime budget must be <=3600 seconds")
    if args.transport_proof and (args.agent_a != "probe" or args.agent_b != "probe"):
        p.error("Transport proof requires probe/probe participants")
    if args.credits < 0:
        p.error("Starting credits must be nonnegative; native engine validates supported presets")
    if any(port != 0 and not 1024 <= port <= 65535 for port in (args.network_port, args.port_a, args.port_b)):
        p.error("Ports must be 0 (automatic) or 1024..65535")
    for key in ("game_dir", "bridge_jar", "baseline_jar"):
        setattr(args, key, getattr(args, key).resolve())
    if not (args.game_dir / "game-lib.jar").is_file() or sha(args.game_dir / "game-lib.jar") != ENGINE_SHA:
        p.error("Original RW 1.15 engine missing or fingerprint differs")
    for jar in (args.bridge_jar, args.baseline_jar):
        if not jar.is_file():
            p.error("Build or provide artifact first: " + str(jar))
    map_path = Path(args.map)
    if map_path.is_absolute() or ".." in map_path.parts or "\\" in args.map or not args.map.startswith("maps/") or not (args.game_dir / "assets" / map_path).is_file():
        p.error("Map must be an existing original assets/maps file")
    args.out = (args.out or ROOT / "headless-runs" / ("same-game-" + uuid.uuid4().hex[:12])).resolve()
    args.out.mkdir(parents=True, exist_ok=False)
    args.child_env = os.environ.copy()
    if sys.platform.startswith("linux"):
        native_libs = "/usr/lib/jvm/java-17-openjdk-amd64/lib:/usr/lib/jvm/java-17-openjdk-amd64/lib/server"
        args.child_env["LD_LIBRARY_PATH"] = native_libs + (":" + args.child_env["LD_LIBRARY_PATH"] if args.child_env.get("LD_LIBRARY_PATH") else "")
    supervisor = Supervisor(args.out)
    batch = {"schemaVersion": 1, "startedUtc": utc(), "engineSha256": ENGINE_SHA,
             "outDirectory": str(args.out), "matches": [], "wins": {"A": 0, "B": 0}}
    try:
        for index in range(1, args.matches + 1):
            match = run_one(args, index, supervisor,
                            on_ready=transport_proof_callback if args.transport_proof else None)
            batch["matches"].append({"matchId": match["matchId"], "report": str(args.out / ("match-%03d" % index) / "match.json"),
                                     "result": match["result"], "sameGameEvidence": match["sameGameEvidence"],
                                     "synchronizationEvidence": match["synchronizationEvidence"],
                                     "endedUtc": match["endedUtc"], "reportSha256": sha(args.out / ("match-%03d" % index) / "match.json")})
            batch["matches"][-1]["reportPersistence"] = verify_match_report(batch["matches"][-1])
            if batch["matches"][-1]["reportPersistence"]["status"] != "PASS":
                raise RuntimeError("Final report and match summary are inconsistent")
            if match["result"]["winner"] is not None:
                batch["wins"][match["result"]["winner"]] += 1
            write_json(args.out / "batch.json", batch)
            print(json.dumps(batch["matches"][-1]), flush=True)
            if match["result"]["status"] in ("CRASH", "INTERRUPTED") or match.get("cleanupErrors"):
                break
    finally:
        batch["cleanupErrors"] = supervisor.stop_all()
        batch["endedUtc"] = utc()
        write_json(args.out / "batch.json", batch)
    persistence_status = verify_run(args.out)
    return 0 if persistence_status == 0 and batch["matches"] and all(m["sameGameEvidence"]["status"] == "PASS" and m["result"]["status"] not in ("CRASH", "INTERRUPTED") for m in batch["matches"]) and not batch["cleanupErrors"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
