# Same-game match harness

The harness launches one original native network host and one join client. Both
clients load the same network match, each binds its own local player, and each
has a separate legal observation API. This is not the historical independent
`run_headless.py --parallel-pair` experiment.

Build the bridge and compatibility overlays first:

```sh
python tools/build_bridge.py
```

The default resource directory is `.engine/rw115`. Recover it from the supplied
relay archive with the existing `prepare_headless_engine.py` if necessary. The
harness rejects any other `game-lib.jar` fingerprint. Java 17 and Python 3 are
required. On Linux it adds the Java 17 JVM native library directories to child
`LD_LIBRARY_PATH`.

Run the public-API native transport proof:

```sh
python orchestrator/run_match.py --transport-proof --proof-timeout 90 --timeout 5 --startup-timeout 45 --out headless-runs/m0-native-proof
```

This uses two probe participants, invokes `tests/native_transport_probe.py`
after both native clients and their player APIs are ready, and archives the
test's raw legal observations separately. The built-in native movement probe
is disabled in this mode so it cannot overwrite the public API test's orders.
The proof may surrender one player using the original native command path.
Native result attribution still requires both clients to agree.

Run both frozen decision loops in a shared native match and swap their slots:

```sh
python orchestrator/run_match.py --agent-a legacy --agent-b octopus --matches 2 --swap-sides --speed 4 --timeout 40 --out headless-runs/legacy-octopus-swap

# Frozen G5 profile
python orchestrator/run_match.py --agent-a legacy --agent-b octopus-g5 --speed 4 --timeout 40 --out headless-runs/legacy-octopus-g5
```

`--timeout` is the wall-clock play budget, separate from `--startup-timeout`.
The frozen strategy clients retain their minimum 120 game-second budget. For
short focused runs the supervisor terminates them at the wall-clock deadline.
`--swap-sides` swaps participants A/B between native host slot 0 and join slot
1 on every second match. Participant A remains A in reports and win counts.

Map, fog, speed and starting credit options are passed to the native host.
Maps must exist under the supplied engine's `assets/maps`; credit presets are
validated by the original engine. The default is Small Island, LOS fog (`2`),
4000 credits, speed 1. Selecting fog `0` uses the game's no-fog rules.

Each match gets a new match ID, independent work directories and lock files,
one native network port, two player API ports, and two adapter gateway ports.
Ports are automatically selected and briefly reserved; explicit ports can be
provided using `--network-port`, `--port-a`, and `--port-b`. Each gateway is
verified against the expected match/player/team/session before its Agent is
launched. Neither gateway is connected to the other player's API.

Reports:

| Path | Evidence |
| --- | --- |
| `batch.json` | Match list, result classifications and confirmed native wins |
| `live-claims.json` | Owned process IDs, exact arguments, directories and lifecycle |
| `report-verification.json` | Final report readback/audit against independently held batch summaries and report SHA256 values |
| `match-NNN/match.json` | Participant/artifact identities, map/fog, start/end, native state, API lifecycle, command counts and report/log paths |
| `match-NNN/player-A/runtime.json` | Native runner's lifecycle and transport metadata |
| `match-NNN/player-A/adapter/gateway-events.jsonl` | Player-scoped protocol observations, actions and native queue receipts |
| `match-NNN/player-A/adapter/rw-agent-reports/` | Original strategy client's reports, when produced |
| `match-NNN/transport-proof/` | M0 probe summary and raw legal HTTP evidence, when selected |

Player B has the same directory structure. `nativeQueued` counts only queue
receipts; it does not claim execution, arrival, kills or completed production.
`attempted` counts HTTP command submissions including commands rejected by the
gateway before reaching the native API. `rejected` is the sum of
`nativeRejected` and `gatewayCommandRejected`; rejected observation/debug reads
are tracked separately as `noncommandRequestRejections`.
In `--transport-proof` mode, participant counts come from the proof's actual
public API command receipts, grouped by native side. The effect field links
to the named remote legal-observation check; disabled direct native probes do
not appear as misleading zero command counts.
For the built-in command probe, distance evidence is its own unit's movement;
it is not evidence that the other player legally saw the effect.

The finalized match is read back in full before it can enter the batch summary.
Batch entries retain its SHA256, ending timestamp and immediate consistency
check. The harness also audits every match after the batch is finalized.
To detect a report overwritten after its producing process ended, run:

```sh
python orchestrator/run_match.py --verify-run headless-runs/legacy-octopus-swap
```

Audit failures return nonzero and create a separate `report-verification.json`.
They preserve the inconsistent raw match report for investigation. Old runs
without recorded SHA256 values still receive result, identity, ending timestamp
and synchronization-summary consistency checks.

All ordinary exits, failures and interrupts stop Agents and gateways before
native clients, request graceful native stop, and then terminate the owned
process trees. A killed Linux orchestrator can be recovered with:

```sh
python orchestrator/run_match.py --reap headless-runs/legacy-octopus-swap
```

Recovery verifies recorded PID birth time, working directory, exact arguments
and owned process group before signaling. Windows ordinary cleanup uses
`taskkill /T`; verified recovery after a killed orchestrator is Linux-only.

The thin referee consumes lifecycle/result metadata. A matching harness ID
alone is never same-game proof: both clients must report a common native
server identity, distinct local player IDs, and completed native network
start/player binding. Command synchronization, legal enemy visibility and
fog isolation are separate proof obligations. Timeout, crash and disconnect
never imply a winner. One-sided or conflicting native results remain
unconfirmed. A controlled surrender proof is not an Agent strength result.
`synchronizationEvidence` explicitly reports both desync counters, both resync
counters, native checksum frames and values. It compares checksums only when
their native checksum frames are equal and valid. Different checksum frames
remain `NOT_COMPARABLE`. A peer departing an established native host connection
is classified `DISCONNECT`; the departing process's abnormal exit is retained
as a separate crash diagnostic and does not award a winner.

Focused synthetic tests:

```sh
python -m unittest discover -s tests -p 'test_orchestrator*.py' -v
```

The real native disconnect check is opt-in and preserves its report directory:

```sh
RWBRIDGE_NATIVE_TESTS=1 python -m unittest discover -s tests -p 'test_orchestrator_native.py' -v
```

These test result evidence, identity rejection, independent staging/ports and
owned descendant cleanup. They are distinct from native multiplayer evidence.
In restricted execution environments, loopback sockets and `/proc` access
need the authorized native-experiment execution profile.
