# Frozen Agent Adapters

`legacy` runs the original `io.rwagent.client.MatchClient` lifecycle: bootstrap,
preflight, economy/development as recommended, then its bounded battle loop.
`octopus` runs the original `io.rwagent.client.BattleClient`, including its normal
bootstrap, world observation and execution pipeline. No strategy thresholds,
roles, decision ordering, production policy or game-time budget are changed.

The frozen snapshots and binary JARs remain unchanged. `overlay.export_sources`
validates exact baseline artifact and source SHA256 values, copies only the client
classes containing multiplayer guards into the build directory, and replaces the
guards with `NativeTransportGuard`. It also admits verified native observations
to Octopus's existing native spending witness. Guards still reject replay,
unverified network transport, a missing local player, changed identity and local
matches when `rwagent.nativeNetworkRequired=true`.

Classpath puts `build/adapters/<agent>.jar` ahead of the baseline binary. This
overlay admits only the advertised, verified `nativeNetworkPlayerV1` transport;
it does not pretend the bridge is single-player. Both loops use the same gateway,
whose compatibility routes preserve their existing HTTP payloads. Their internal
architectures remain private to the agent process.

Harness API:

```python
from adapters.launch import command, gateway_start_command
gateway_argv = gateway_start_command(47653, 47753, "match-id", "legacy",
                                     player_id=0, evidence="run/A/gateway.jsonl")
agent_argv = command("legacy", 47753, seconds=120)
```

Run each process with its own working directory. Frozen agent reports stay in
that process's `rw-agent-reports/`. Original agents require at least 120 game
seconds; shorter focused transport experiments may terminate them from the
harness. A terminated agent is not a completed battle or native result.

Tests in `tests/test_adapters_protocol.py` verify authorization and guard export
with fixtures. They do not constitute native multiplayer evidence. Native
acceptance must be recorded separately by the same-game harness, including an
actual initialization, advancing observation and command submission by each
original loop.
