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

## VERIFIED BY THIS BRIDGE IMPLEMENTATION — 2026-10-03 UTC

- Two headless original 1.15 processes host/join the same native server/session.
- Native local-player binding distinguishes player slots and alliance groups.
- M0: both players' move commands are observed through the remote peer's legal current fog observation; distinct initial fog and cross-player command rejection are verified.
- Original surrender produces native VICTORY/DEFEAT consensus; native peer departure is classified as DISCONNECT without inventing a winner.
- Legacy and Octopus frozen loops initialize, receive legal observations and submit native actions through the common player gateway; native batch side swap is implemented and exercised.
- Lobby loading/ready acknowledgement, process cleanup, result/identity reporting and bounded match orchestration work in the remote Linux environment.

Exact candidate identities, raw logs, incomplete/failed iterations and the final test scope are in `evidence/native-bridge-20261003/`.

## STILL NOT PROVEN / NOT IMPLEMENTED

- Human-vs-Agent desktop interoperability.
- Windows GUI, Steam invitation flow, password-protected lobbies and NAT traversal.
- All command types' remote visible effects: M0 specifically proves move; other command receipts remain queue acceptance until subsequent legal evidence supports their effect.
- Automatic reconnect/rebinding, arbitrary custom maps/mods, single-process PlayerContext self-play and exhaustive multiplayer/team-map coverage.
- Agent strength or win-rate improvements; bounded validation games are not a strength benchmark.

Do not silently upgrade any NOT YET PROVEN item into a capability claim. Prove it with a native experiment or leave it explicitly unresolved.
