"""Export minimal capability guard overlays; frozen snapshots are never edited.

The build compiles only these changed client classes ahead of the baseline jar.
No decision loop, strategy parameters, or production policy is changed.
"""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[1]
BASELINES = {
    "octopus": ("octopus-g42", "octopus-g42-83c09fb.jar", "efc150e8822d69511c5ff92477a6d5153c9f2673b128baa3be5b110eb82fa885"),
    "legacy": ("legacy-production-capacity", "legacy-production-capacity.jar", "a392f692e010c8429a7a93072e60323c83a9deb1ed471bbcbddbd3bbbaf6adb6"),
}
SOURCE_HASHES = {
    "octopus": {"BattleClient": "a85002080e35817f3df1541edf63a607bd1d9ea8e7aaaf0a40717a6a5e19cfb9",
                "NativeCreditWitness": "cb43a6c1e018b95e724a245d52ebe0762a387a76b976ebb51584dd34232c21be"},
    "legacy": {"BattleClient": "47aed52030f26928651d6c81d4ef78246df9d9a60813ef0f0a4f883440ab1d48"},
    "common": {"BootstrapClient": "8c6c2a9a0f809aa80262161129e46140b08b5a2ba8191094bc9cc99273c1b44b",
               "EconomyClient": "3ddfab552248014fbfc55e5dc2a082ec3e078001aa8be9a1435a8b32f7f2efa5",
               "DevelopmentClient": "89d3fb0ce4d2a4855737ab0c9fb3210bb1d320f6b054ce13a81bc6cdc45963e8",
               "ControlLoop": "b164e52a061bdd30492bf73671b2d1ceec3f5df5323bdfa2b8d1430aac290509"},
}

# Exact replacements fail closed if a future baseline differs.
GUARDS = {
    "BattleClient": ('if(!"running".equals(s.get("status"))||s.get("player")==null||Boolean.TRUE.equals(s.get("networked"))||Boolean.TRUE.equals(s.get("replay")))throw new IllegalStateException("Local active match required");',
                     'NativeTransportGuard.require(s);\n        if(!"running".equals(s.get("status"))||s.get("player")==null)throw new IllegalStateException("Active bound match required");'),
    "BootstrapClient": ('if(Boolean.TRUE.equals(state.get("networked"))||Boolean.TRUE.equals(state.get("replay")))throw new IllegalStateException("Network/replay bootstrap is disabled");', 'NativeTransportGuard.require(state);'),
    "EconomyClient": ('if(Boolean.TRUE.equals(s.get("networked")) || Boolean.TRUE.equals(s.get("replay")))throw new IllegalStateException("Network/replay tests are disabled");', 'NativeTransportGuard.require(s);'),
    "DevelopmentClient": ('if(Boolean.TRUE.equals(s.get("networked")) || Boolean.TRUE.equals(s.get("replay")))throw new IllegalStateException("Network/replay disabled");', 'NativeTransportGuard.require(s);'),
    "ControlLoop": ('if (Boolean.TRUE.equals(state.get("networked")) || Boolean.TRUE.equals(state.get("replay")))\n            throw new IllegalStateException("Use a local single-player match, not network/replay");', 'NativeTransportGuard.require(state);'),
}

def artifact(agent, repo_root=ROOT):
    _, binary, digest = BASELINES[agent]
    path = Path(repo_root) / "binaries" / binary
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != digest:
        raise ValueError(f"Frozen {agent} artifact digest mismatch: {actual}")
    return {"agentId": agent, "artifactDigest": "sha256:" + digest, "binary": str(path)}

def export_sources(agent, output_dir, repo_root=ROOT):
    """Return source Paths for root's build tool, with explicit narrow modifications."""
    artifact(agent, repo_root)
    snapshot = BASELINES[agent][0]
    target = Path(output_dir) / "io/rwagent/client"
    target.mkdir(parents=True, exist_ok=True)
    files, provenance = [], []
    guards = dict(GUARDS)
    if agent == "octopus":
        guards["NativeCreditWitness"] = ('||Boolean.TRUE.equals(state.get("networked"))||Boolean.TRUE.equals(state.get("replay"))',
                                         '||!NativeTransportGuard.allowed(state)||Boolean.TRUE.equals(state.get("replay"))')
    for name, (before, after) in guards.items():
        source = Path(repo_root) / "agents" / snapshot / "agent/src/io/rwagent/client" / (name + ".java")
        original = source.read_text()
        source_digest = hashlib.sha256(source.read_bytes()).hexdigest()
        if source_digest != SOURCE_HASHES.get(agent, {}).get(name, SOURCE_HASHES["common"].get(name)):
            raise ValueError(f"Frozen source digest mismatch: {source}")
        if original.count(before) != 1:
            raise ValueError(f"Expected exactly one frozen capability guard in {source}")
        exported = target / source.name
        exported.write_text(original.replace(before, after))
        files.append(exported)
        provenance.append({"source": str(source.relative_to(repo_root)), "sourceSha256": source_digest,
                           "overlaySha256": hashlib.sha256(exported.read_bytes()).hexdigest(),
                           "change": "verified-native-transport-capability-guard-only"})
    common = Path(repo_root) / "adapters/java/common/io/rwagent/client/NativeTransportGuard.java"
    exported = target / common.name
    exported.write_text(common.read_text())
    files.append(exported)
    provenance.append({"source": str(common.relative_to(repo_root)),
                       "overlaySha256": hashlib.sha256(exported.read_bytes()).hexdigest(), "change": "new-capability-guard"})
    (Path(output_dir) / "overlay-sources.json").write_text(json.dumps(provenance, indent=2) + "\n")
    return files
