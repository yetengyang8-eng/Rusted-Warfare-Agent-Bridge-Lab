"""Opt-in real native peer-exit test; keeps its complete evidence on disk.

RWBRIDGE_NATIVE_TESTS=1 python -m unittest discover -s tests -p 'test_orchestrator_native.py' -v
Requires the authorized loopback socket/native execution profile.
"""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(os.environ.get("RWBRIDGE_NATIVE_TESTS") == "1", "opt-in real native host/join experiment")
class NativeDisconnectTests(unittest.TestCase):
    def test_peer_exit_is_disconnect_without_awarding_victory(self):
        out = ROOT / "headless-runs" / ("native-disconnect-focused-" + uuid.uuid4().hex[:10])
        command = [sys.executable, str(ROOT / "orchestrator/run_match.py"),
                   "--agent-a", "probe", "--agent-b", "probe", "--timeout", "20",
                   "--startup-timeout", "45", "--out", str(out)]
        proc = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        report_path = out / "match-001/match.json"
        try:
            deadline = time.monotonic() + 50
            while time.monotonic() < deadline:
                try:
                    report = json.loads(report_path.read_text())
                except (OSError, ValueError):
                    report = {}
                if report.get("startedUtc") and report.get("sameGameEvidence", {}).get("status") == "PASS":
                    break
                if proc.poll() is not None:
                    self.fail("Harness exited before readiness: " + proc.stdout.read())
                time.sleep(.1)
            else:
                self.fail("Native readiness did not occur")
            join = next(p for p in report["participants"] if p["role"] == "join")
            os.kill(join["enginePid"], signal.SIGTERM)
            stdout, _ = proc.communicate(timeout=10)
            report = json.loads(report_path.read_text())
            self.assertEqual(report["result"]["status"], "DISCONNECT", (report["result"], stdout))
            self.assertIsNone(report["result"]["winner"])
            self.assertTrue(report["disconnectEvidence"])
            self.assertEqual(report["disconnectEvidence"][0]["source"], "native_host_peer_departure")
            self.assertTrue(report["crashes"])
            claims = json.loads((out / "live-claims.json").read_text())
            self.assertTrue(all(p["state"] == "EXITED" for p in claims["processes"]))
            print("Native disconnect evidence: " + str(report_path), flush=True)
        finally:
            if proc.poll() is None:
                proc.send_signal(signal.SIGINT)
                try:
                    proc.communicate(timeout=10)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.communicate()
                    subprocess.run([sys.executable, str(ROOT / "orchestrator/run_match.py"),
                                    "--reap", str(out)], cwd=ROOT, check=False)


if __name__ == "__main__":
    unittest.main()
