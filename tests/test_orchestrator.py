"""Focused harness checks; synthetic fixtures are not native multiplayer evidence."""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from orchestrator.run_match import (PortReservations, Supervisor, engine_command,
                                    collect_adapter_evidence, disconnect_evidence,
                                    owned_runtime, sample_lifecycle, stage, sha,
                                    verify_match_report, write_final_match, write_json)
from referee.results import classify, same_game_evidence, synchronization_evidence


class RefereeTests(unittest.TestCase):
    def participants(self, result_a="unknown", result_b="unknown"):
        return [{"participantId": "A", "runtime": {"nativeResult": result_a}},
                {"participantId": "B", "runtime": {"nativeResult": result_b}}]

    def test_timeout_does_not_award_victory(self):
        result = classify(self.participants(), "timeout")
        self.assertEqual(result["status"], "TIMEOUT")
        self.assertIsNone(result["winner"])

    def test_disconnect_does_not_award_victory(self):
        result = classify(self.participants(), "disconnect")
        self.assertEqual(result["status"], "DISCONNECT")
        self.assertIsNone(result["winner"])

    def test_one_sided_native_result_remains_unconfirmed(self):
        result = classify(self.participants("victory"), "timeout")
        self.assertEqual(result["status"], "NATIVE_RESULT_UNCONFIRMED")
        self.assertIsNone(result["winner"])

    def test_conflicting_native_result_is_not_winner(self):
        self.assertIsNone(classify(self.participants("victory", "victory"), "native_result")["winner"])

    def test_native_consensus_uses_participant_identity_after_swap(self):
        participants = self.participants({"outcome": "DEFEAT"}, {"outcome": "VICTORY"})
        participants[0]["participantId"], participants[1]["participantId"] = "B", "A"
        result = classify(participants, "native_result")
        self.assertEqual((result["winner"], result["loser"]), ("A", "B"))

    def test_harness_match_id_alone_is_not_same_game_proof(self):
        participants = self.participants()
        for participant in participants:
            participant["runtime"]["matchId"] = "same-invented-id"
        self.assertEqual(same_game_evidence(participants)["status"], "NEEDS_EVIDENCE")

    def test_distinct_native_server_ids_are_not_same_game(self):
        participants = self.participants()
        for index, participant in enumerate(participants):
            participant["runtime"].update(networked=True, networkStarted=True,
                                          localPlayerBound=True, playerId=index, nativeServerId=str(index))
        self.assertEqual(same_game_evidence(participants)["status"], "NEEDS_EVIDENCE")

    def test_different_checksum_frames_are_not_compared(self):
        participants = self.participants()
        participants[0]["runtime"].update(checksumFrame=301, nativeChecksum=50, desyncErrors=0, resyncCount=0)
        participants[1]["runtime"].update(checksumFrame=601, nativeChecksum=90, desyncErrors=0, resyncCount=0)
        summary = synchronization_evidence(participants)
        self.assertEqual(summary["checksumComparison"], "NOT_COMPARABLE")
        self.assertNotEqual(summary["status"], "CHECKSUM_MISMATCH")

    def test_native_desync_and_resync_are_reported(self):
        participants = self.participants()
        participants[0]["runtime"].update(checksumFrame=301, nativeChecksum=50, desyncErrors=1, resyncCount=0)
        participants[1]["runtime"].update(checksumFrame=301, nativeChecksum=50, desyncErrors=0, resyncCount=2)
        summary = synchronization_evidence(participants)
        self.assertEqual(summary["status"], "DESYNC_REPORTED")
        self.assertEqual(summary["resyncCounts"], [0, 2])
        self.assertEqual(summary["checksumComparison"], "MATCH")


class HarnessTests(unittest.TestCase):
    def test_port_reservation_unique_and_released(self):
        reservation = PortReservations([0, 0, 0])
        self.assertEqual(len(set(reservation.ports)), 3)
        with socket.socket() as sock:
            with self.assertRaises(OSError):
                sock.bind(("127.0.0.1", reservation.ports[0]))
        reservation.close()
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", reservation.ports[0]))

    def test_staging_is_separate_and_excludes_private_state(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            game = root / "game"
            game.mkdir()
            for name in ("assets", "libs", "res", "saves"):
                (game / name).mkdir()
            (game / "game-lib.jar").write_bytes(b"engine fixture")
            bridge, baseline = root / "bridge.jar", root / "baseline.jar"
            bridge.write_bytes(b"bridge fixture")
            baseline.write_bytes(b"baseline fixture")
            for identity in ("A", "B"):
                stage(game, root / identity, bridge, baseline)
                self.assertFalse((root / identity / "saves").exists())
            (root / "A" / "stop.request").touch()
            self.assertFalse((root / "B" / "stop.request").exists())

    def test_mismatched_runtime_rejected(self):
        with self.assertRaises(RuntimeError):
            owned_runtime({"matchId": "old-match"}, "new-match")

    def test_host_join_share_one_native_port_with_distinct_apis(self):
        args = SimpleNamespace(java="java", map="maps/skirmish/test.tmx", speed=1,
                               timeout=10, startup_timeout=20, fog=2, credits=4000)
        commands = [engine_command(args, role, port, 5123, "match")
                    for role, port in (("host", 5001), ("join", 5002))]
        for command in commands:
            self.assertEqual(command[command.index("--network-port") + 1], "5123")
            self.assertEqual(command[command.index("--connect") + 1], "127.0.0.1:5123")
        self.assertNotEqual(commands[0][commands[0].index("--port") + 1],
                            commands[1][commands[1].index("--port") + 1])

    def test_wrong_api_player_and_session_are_rejected(self):
        participant = {"apiPort": 5001, "runtime": {"playerId": 0}, "sessionId": "session-a"}
        class Response:
            def __init__(self, data):
                self.data = json.dumps(data).encode()
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def read(self):
                return self.data
        state = {"matchId": "match", "playerId": 1, "sessionId": "session-a",
                 "networked": True, "nativeNetworkPlayerV1": True}
        with patch("orchestrator.run_match.urlopen", return_value=Response(state)):
            with self.assertRaises(RuntimeError):
                sample_lifecycle(participant, "match")
        state.update(playerId=0, sessionId="session-b")
        with patch("orchestrator.run_match.urlopen", return_value=Response(state)):
            with self.assertRaises(RuntimeError):
                sample_lifecycle(participant, "match")

    def test_native_host_peer_loss_requires_prior_connection(self):
        participant = {"participantId": "A", "role": "host", "runtime": {"nativeConnections": 0}}
        self.assertEqual(disconnect_evidence([participant]), [])
        participant["nativeConnectionsAtStart"] = 1
        self.assertEqual(disconnect_evidence([participant])[0]["source"], "native_host_peer_departure")

    def test_public_probe_counts_use_actual_receipts_after_swap(self):
        with tempfile.TemporaryDirectory() as temp:
            participant = {"participantId": "A", "agentId": "probe", "side": 1,
                           "workDirectory": temp, "runtime": {"probeQueued": False}, "logs": {}}
            proof = {"reportPath": "proof/summary.json", "commandReceipts": [
                        {"side": 0, "receipt": {"status": "queued"}},
                        {"side": 1, "receipt": {"status": "queued"}},
                        {"side": 1, "receipt": {"status": "queued"}}],
                     "checks": [{"name": "side_1_command_effect_seen_by_remote_player", "status": "PASS"}]}
            collect_adapter_evidence(participant, proof)
            self.assertEqual(participant["commandCounts"]["nativeQueued"], 2)
            self.assertEqual(participant["commandCounts"]["executedEffects"]["reportPath"], "proof/summary.json")

    def test_gateway_command_refusal_is_a_submission_but_read_refusal_is_not(self):
        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp) / "adapter/gateway-events.jsonl"
            evidence.parent.mkdir()
            rows = [{"event": "compatibility_player_action", "identity": {"matchId": "match"},
                     "kind": "move", "nativeStatus": 200, "nativeReceipt": {"status": "queued"}},
                    {"event": "player_request_rejected", "identity": {"matchId": "match"},
                     "path": "/command/move?unitId=2", "status": 409},
                    {"event": "player_request_rejected", "identity": {"matchId": "match"},
                     "path": "/combat/reachability", "status": 403}]
            evidence.write_text("\n".join(json.dumps(row) for row in rows) + "\n")
            participant = {"agentId": "legacy", "workDirectory": temp, "runtime": {"matchId": "match"}, "logs": {}}
            collect_adapter_evidence(participant)
            counts = participant["commandCounts"]
            self.assertEqual((counts["attempted"], counts["nativeQueued"], counts["rejected"]), (2, 1, 1))
            self.assertEqual((counts["nativeRejected"], counts["gatewayCommandRejected"], counts["noncommandRequestRejections"]), (0, 1, 1))

    def test_final_report_hash_and_summary_detect_later_stale_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "match.json"
            report = {"matchId": "match", "endedUtc": "end", "result": {"status": "TIMEOUT"},
                      "sameGameEvidence": {"status": "PASS"}, "synchronizationEvidence": {"status": "NEEDS_EVIDENCE"}}
            digest = write_final_match(path, report)
            entry = {**report, "report": str(path), "reportSha256": digest}
            self.assertEqual(verify_match_report(entry)["status"], "PASS")
            write_json(path, {**report, "result": None, "endedUtc": None})
            verification = verify_match_report(entry)
            self.assertEqual(verification["status"], "FAIL")
            self.assertTrue(any("SHA256" in error for error in verification["errors"]))

    def test_final_report_readback_refuses_unpersisted_success(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "match.json"
            write_json(path, {"matchId": "match", "result": None})
            with patch("orchestrator.run_match.write_json"):
                with self.assertRaises(RuntimeError):
                    write_final_match(path, {"matchId": "match", "result": {"status": "TIMEOUT"}})
    @unittest.skipIf(os.name == "nt", "Linux process-group regression")
    def test_cleanup_reaps_process_group_when_leader_already_exited(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            supervisor = Supervisor(root)
            script = ("import subprocess,sys; p=subprocess.Popen([sys.executable,'-c',"
                      "'import time; time.sleep(60)']); print(p.pid,flush=True)")
            proc = supervisor.launch([sys.executable, "-c", script], root,
                                     root / "process.log", "agent")
            proc.wait(timeout=5)
            child_pid = int((root / "process.log").read_text().strip())
            self.assertTrue(Path("/proc/%d" % child_pid).exists())
            self.assertEqual(supervisor.stop_all(), [])
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline:
                stat = Path("/proc/%d/stat" % child_pid)
                if not stat.exists() or stat.read_text().rsplit(")", 1)[1].split()[0] == "Z":
                    break
                time.sleep(.05)
            else:
                self.fail("Owned descendant remained alive after cleanup")
            claims = json.loads((root / "live-claims.json").read_text())
            self.assertEqual(claims["processes"][0]["state"], "EXITED")


if __name__ == "__main__":
    unittest.main()
