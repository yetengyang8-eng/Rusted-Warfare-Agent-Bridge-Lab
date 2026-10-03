"""Launch the actual frozen decision loops over one bound player gateway."""
from pathlib import Path
import argparse
import os
import subprocess
import sys
from .overlay import ROOT, artifact

MAINS = {"legacy": "io.rwagent.client.MatchClient", "octopus": "io.rwagent.client.BattleClient",
         "octopus-g5": "io.rwagent.client.BattleClient"}

def command(agent, port, seconds=120, repo_root=ROOT):
    identity = artifact(agent, repo_root)
    overlay = Path(repo_root) / "build/adapters" / (agent + ".jar")
    if not overlay.is_file():
        raise FileNotFoundError(f"Build the client capability overlay first: {overlay}")
    if seconds < 120:
        raise ValueError("Frozen clients require >=120 game seconds; harness may terminate a shorter focused run")
    return ["java", f"-Drwagent.port={int(port)}", "-Drwagent.pollMs=100",
            "-Drwagent.nativeNetworkRequired=true", "-cp",
            str(overlay) + os.pathsep + identity["binary"], MAINS[agent], str(int(seconds))]

def gateway_start_command(upstream_port, port, match_id, agent, repo_root=ROOT,
                          evidence=None, player_id=None):
    result = [sys.executable, str(Path(repo_root) / "protocol/gateway.py"),
              "--upstream-port", str(upstream_port), "--port", str(port),
              "--match-id", match_id, "--agent", agent]
    if evidence is not None:
        result += ["--evidence", str(evidence)]
    if player_id is not None:
        result += ["--player-id", str(player_id)]
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent", choices=MAINS, required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--seconds", type=int, default=120)
    args = parser.parse_args()
    return subprocess.call(command(args.agent, args.port, args.seconds))

if __name__ == "__main__":
    raise SystemExit(main())
