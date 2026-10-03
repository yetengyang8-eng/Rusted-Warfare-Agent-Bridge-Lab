#!/usr/bin/env python3
"""Build reproducible Java 8 overlays without changing either frozen Agent or engine.

Requires a JDK 17+ (uses its compiler module), and the verified engine resource pack.
The bridge overlay precedes the Octopus baseline on the engine classpath; each client
overlay precedes its own frozen baseline in a separate Agent JVM.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
ENGINE_SHA = "8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9"
BASELINES = {
    "octopus": ("octopus-g42-83c09fb.jar", "efc150e8822d69511c5ff92477a6d5153c9f2673b128baa3be5b110eb82fa885"),
    "octopus-g5": ("octopus-g5-ca2fc92.jar", "b5d87aff499028a32738eefc6b99710c6b6a9d90faafb04d6b5093b6b769acad"),
    "legacy": ("legacy-production-capacity.jar", "a392f692e010c8429a7a93072e60323c83a9deb1ed471bbcbddbd3bbbaf6adb6"),
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(java: str, name: str, sources: list[Path], classpath: list[Path | str], out: Path) -> dict:
    if not sources:
        raise RuntimeError(f"No sources for {name}")
    classes = out / "classes" / name
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    command = [java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "8",
               "-encoding", "UTF-8", "-cp", os.pathsep.join(map(str, classpath)), "-d", str(classes)]
    subprocess.run(command + [str(p) for p in sources], check=True)
    target = out / ("bridge.jar" if name == "bridge" else f"adapters/{name}.jar")
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as jar:
        for path in sorted(classes.rglob("*.class")):
            item = zipfile.ZipInfo(path.relative_to(classes).as_posix(), (2020, 1, 1, 0, 0, 0))
            item.external_attr = 0o644 << 16
            item.compress_type = zipfile.ZIP_DEFLATED
            jar.writestr(item, path.read_bytes())
    return {"path": str(target.relative_to(ROOT)) if target.is_relative_to(ROOT) else str(target),
            "sha256": sha(target), "sources": {
                str(p.relative_to(ROOT)) if p.is_relative_to(ROOT) else str(p): sha(p) for p in sources}}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game-dir", type=Path, default=ROOT / ".engine/rw115")
    parser.add_argument("--out", type=Path, default=ROOT / "build")
    parser.add_argument("--java", default=shutil.which("java") or "java")
    parser.add_argument("--bridge-only", action="store_true")
    args = parser.parse_args()
    game = args.game_dir.resolve()
    out = args.out.resolve()
    if sha(game / "game-lib.jar") != ENGINE_SHA:
        raise RuntimeError("Unsupported engine fingerprint")
    baseline_paths = {}
    for name, (filename, expected) in BASELINES.items():
        path = ROOT / "binaries" / filename
        if sha(path) != expected:
            raise RuntimeError(f"Frozen {name} baseline fingerprint mismatch")
        baseline_paths[name] = path
    artifacts = {"bridge": build(args.java, "bridge", sorted((ROOT / "bridge/src").rglob("*.java")),
                                  [baseline_paths["octopus"], game / "game-lib.jar", str(game / "libs/*")], out)}
    if not args.bridge_only:
        from adapters.overlay import export_sources
        for name in BASELINES:
            sources = export_sources(name, out / "adapters-src" / name)
            artifacts[name] = build(args.java, name, sources, [baseline_paths[name]], out)
    manifest = {"schemaVersion": 1, "gameJarSha256": ENGINE_SHA,
                "baselines": {name: {"file": path.name, "sha256": sha(path)} for name, path in baseline_paths.items()},
                "artifacts": artifacts}
    out.mkdir(parents=True, exist_ok=True)
    (out / "build-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "BUILT", "artifacts": {n: a["sha256"] for n, a in artifacts.items()}}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
