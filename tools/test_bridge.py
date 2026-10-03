#!/usr/bin/env python3
"""Focused protocol, supervisor, referee and native-data authority tests.

These fixtures are separate from the real M0 dual-process transport proof:
  python orchestrator/run_match.py --transport-proof --speed 4 --timeout 5
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--game-dir", type=Path, default=ROOT / ".engine/rw115")
    parser.add_argument("--out", type=Path, default=ROOT / "build/focused-tests")
    args = parser.parse_args()
    args.out = args.out.resolve()
    args.game_dir = args.game_dir.resolve()
    args.out.mkdir(parents=True, exist_ok=True)
    classes = args.out / "classes"
    classes.mkdir(exist_ok=True)
    cp = os.pathsep.join(map(str, [ROOT / "build/bridge.jar", args.game_dir / "game-lib.jar",
                                  args.game_dir / "libs/*", ROOT / "binaries/octopus-g42-83c09fb.jar"]))
    steps = [
        ("python", [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-p", "test_*.py", "-v"], ROOT),
        # The test-only data fixture uses Unsafe for empty native objects; production overlays use --release 8.
        ("authority-compile", [args.java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-source", "8",
          "-target", "8", "-cp", cp, "-d", str(classes), str(ROOT / "tests/player_scope/PlayerScopeHarness.java"),
          str(ROOT / "tests/player_scope/NativePlacementHarness.java"),
          str(ROOT / "agents/octopus-g42/agent/tests/TerrainNativeCostHarness.java")], ROOT),
        ("authority", [args.java, "-Djava.awt.headless=true", "-cp", str(classes)+os.pathsep+cp,
                       "io.rwagent.bootstrap.PlayerScopeHarness"], ROOT),
    ]
    from orchestrator.run_match import stage
    placement_work = args.out / "placement-work"
    if not placement_work.exists():
        stage(args.game_dir, placement_work, ROOT / "build/bridge.jar", ROOT / "binaries/octopus-g42-83c09fb.jar")
    steps.append(("placement", [args.java, "-Djava.awt.headless=true", "-cp", str(classes)+os.pathsep+cp,
                               "io.rwagent.bootstrap.NativePlacementHarness"], placement_work))
    results = []
    for name, command, work in steps:
        with (args.out / (name + ".log")).open("w", encoding="utf-8") as log:
            completed = subprocess.run(command, cwd=work, stdout=log, stderr=subprocess.STDOUT, timeout=90)
        results.append({"name": name, "exitCode": completed.returncode, "log": name + ".log"})
        print(f"{name}: {'PASS' if completed.returncode == 0 else 'FAIL'}", flush=True)
        if completed.returncode:
            break
    summary = {"status": "PASS" if len(results) == len(steps) and all(r["exitCode"] == 0 for r in results) else "FAIL",
               "evidenceLevel": "FOCUSED_FIXTURES_AND_NATIVE_MAP",
               "nativeDisconnectEnabled": os.environ.get("RWBRIDGE_NATIVE_TESTS") == "1",
               "scope": "Protocol/referee fixtures, native-data authority, native map placement; optional real host/join disconnect. M0 transport proof is separate.",
               "tests": results}
    (args.out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    return 0 if summary["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
