# Octopus G5 bridge integration — 2026-10-03

## Frozen identity

- Rusted-Warfare-Agent commit: `ca2fc92d36ef67beedf91d72cea9606fdb1544e4`
- G5 candidate JAR: `b5d87aff499028a32738eefc6b99710c6b6a9d90faafb04d6b5093b6b769acad`
- Bridge agent ID: `octopus-g5`
- Historical G4.2 profile remains available as `octopus`.

The new profile uses the same public player v1 gateway. Only verified native-network capability guards are overlaid; G5 strategy classes are untouched.

## Windows integration checks

`python tools/build_bridge.py` builds bridge + Legacy + G4.2 + G5 overlays successfully.

`python tools/test_bridge.py` passes Python protocol/referee tests, native-data authority checks and native placement checks with the G5 frozen source included in exact digest validation.

A real native same-game smoke was run on Windows:

- Legacy host vs `octopus-g5` join
- Small Island, LOS fog, 4000 credits, requested speed 4
- same native server ID and player IDs 0/1: PASS
- G5 artifact identity and overlay identity: PASS
- Legacy commands: 12 attempted / 12 native queued
- G5 commands: 15 attempted / 14 native queued / 1 rejected
- checksum frame: `6923` on both clients
- native checksum: `38173116` on both clients
- desync/resync: `0 / 0` on both clients
- result: TIMEOUT, no winner assigned
- final report persistence verification: PASS

The first Windows smoke exposed a bridge-harness cleanup portability bug: Python on Windows does not expose `signal.SIGKILL`. The match itself had same-game PASS and G5 commands, but cleanup recorded errors. The harness now uses a cross-platform force-signal token; the repeated smoke exited 0 with no cleanup errors.

This document does **not** claim Human-vs-Agent GUI validation. Windows desktop Human ↔ G5 remains the next local acceptance step.
