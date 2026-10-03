# Environment Capability Matrix

## AVAILABLE REMOTELY

- Java 17 / Java 8 bytecode workflow.
- Rusted Warfare PC 1.15 Build #28 / Game Code 176 headless resources.
- `game-lib.jar` + `assets/` + `libs/` + `res/` packaged in `engine-relay/headless-engine-1.15.zip`.
- Original simulation ticks, map loading, pathing, economy, construction, production and vanilla AI through existing `HeadlessRunner`.
- Independent two-process orchestration through existing `tools/run_headless.py --parallel-pair`.
- Isolated ports, sessions, working directories, reports and process identity checks.
- Frozen Octopus G4.2 source/JAR.
- Frozen Legacy Production Capacity source/JAR.

## NOT AVAILABLE REMOTELY

- User's visual Windows desktop game session.
- Steam account/session, user saves, replays, preferences or private data.
- A proven Windows GUI multiplayer workflow.
- Current local Codex G5 work in progress; it is intentionally excluded.
- The user's latest Spain custom map unless separately supplied later.

## NOT YET PROVEN

- Headless native host/join between two engine processes.
- Correct network command submission through existing local bridge calls.
- Per-player fog isolation in the same network match.
- Agent-vs-Agent same-game execution.
- Human-vs-Agent desktop interoperability.
- Automated lobby/slot/map/start lifecycle.

Do not silently upgrade any NOT YET PROVEN item into a capability claim. Prove it with a native experiment or leave it explicitly unresolved.
