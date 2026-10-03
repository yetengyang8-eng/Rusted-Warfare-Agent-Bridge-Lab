#!/usr/bin/env python3
"""Archive explicit run evidence without engine resources, preferences or binaries."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SUFFIXES = {".json", ".jsonl", ".partial", ".log", ".txt", ".md"}
EXCLUDED = {"cache", "assets", "libs", "res", "replays", "saves", "classes"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, action="append", required=True)
    parser.add_argument("--focused", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    sources = args.run + [args.focused]
    rows, matches = [], []
    archive = args.out / "raw-evidence.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for directory in sources:
            for path in sorted(directory.rglob("*")):
                relative = path.relative_to(directory)
                if path.is_symlink() or not path.is_file() or path.suffix not in SUFFIXES or any(p in EXCLUDED for p in relative.parts):
                    continue
                name = directory.name + "/" + relative.as_posix()
                data = path.read_bytes()
                output.writestr(name, data)
                rows.append({"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
                if path.name == "match.json":
                    match = json.loads(data)
                    matches.append({"run": directory.name, "matchId": match["matchId"],
                        "map": match["map"], "fog": match["fog"], "result": match["result"],
                        "sameGameEvidence": match.get("sameGameEvidence"),
                        "transportProof": match.get("transportProof", {}).get("status"),
                        "synchronizationEvidence": match.get("synchronizationEvidence"),
                        "participants": [{key: p.get(key) for key in
                            ("participantId", "agentId", "playerId", "teamId", "sessionId", "artifactSha256", "adapterArtifact", "bridgeSha256", "commandCounts", "engineExitCode")}
                            for p in match["participants"]]})
        manifest = ROOT / "build/build-manifest.json"
        if manifest.exists():
            (args.out / "build-manifest.json").write_bytes(manifest.read_bytes())
    summary = {"schemaVersion": 1, "archive": archive.name,
               "archiveSha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
               "files": len(rows), "matches": matches,
               "focused": json.loads((args.focused / "summary.json").read_text(encoding='utf-8'))}
    (args.out / "files.json").write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")
    (args.out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"archive": str(archive), "files": len(rows), "bytes": archive.stat().st_size,
                      "sha256": summary["archiveSha256"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
