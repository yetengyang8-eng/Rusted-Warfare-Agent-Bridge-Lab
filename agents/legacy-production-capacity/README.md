# Rusted Warfare Agent

A research/engineering project for a controllable Rusted Warfare PC 1.15 Agent. The project prioritizes legal observation, explainable state, reproducible evidence, bounded ownership, and native-command execution.

## Current status

- Frozen lineage baseline: `RW-BASELINE-2026-09-30-GS-v1`.
- Active engineering candidate: **RW-CANDIDATE-2026-10-01-PRODUCTION-CAPACITY-v1**; desktop acceptance pending.
- Candidate JAR SHA256: `a392f692e010c8429a7a93072e60323c83a9deb1ed471bbcbddbd3bbbaf6adb6`.
- Current [handoff](handoff/HANDOFF_Codex_ProductionCapacity_v1_2026-10-01.md), [evidence](evidence/production-capacity-2026-10-01/README.md), and [delivery manifest](deliveries/production-capacity-2026-10-01/candidate-manifest.json). Previous FEEDBACK-v1 remains frozen at SHA256 `0116e7c67f6fbd778d6956a9770205b31a458365c196d4063caa625f643c0cc7`.
- Frozen compatible `game-lib.jar` SHA256: `8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9`.
- Latest independent desktop acceptance includes a ~4801.6s Spain run and ~2402.4s Big Island run. Both were `PARTIAL / ONGOING`; see `evidence/desktop-feedback-2026-10-01/`.

The current bottleneck is no longer simply obtaining economy. The Agent can build a large economy, but production capacity and concurrent operational throughput do not scale fast enough to consume it.

Current macro direction:

`economic growth → production capacity → predictable/interchangeable production routes → unit delivery → parallel operations → map control → more economic growth`

## For Astra / remote engineering agents

**Start with `ASTRA_START_HERE.md`.** Remote agents should assume GitHub is their entire project view and should not depend on local `G:` paths.

The repository now contains the latest desktop-derived context that older Astra mission files did not contain.
## Canonical reading order

1. `AGENTS.md` — operating contract and evidence rules.
2. `project-state/ASTRA_CONTEXT.md` — latest desktop facts and remote context.
3. `project-state/CURRENT_STATE.md` — what is current now.
4. `project-state/NEXT_STAGE_PLAN.md` — what should be built next.
5. `agent/` and `tools/` — production source and analysis/runner tooling.
6. `evidence/` — candidate and desktop evidence.

## Repository layout

- `agent/` — Java Agent source, test harnesses, build scripts.
- `tools/` — parsing, auditing, native/headless runners and analysis tooling.
- `project-state/` — current control plane plus archived state snapshots.
- `evidence/` — evidence indexed by candidate/run family.
- `deliveries/` — fixed candidate deliverables and usage notes.
- `knowledge/` — static mechanics/terrain/capability knowledge; never substitutes for legal live observation.
- `handoff/` — engineering handoffs; current state/plan override old handoffs.
- `project-history/` — history only.
- `astra-relay/` — remote/native relay resources where legally distributable.

## Build

Supply your own compatible Rusted Warfare 1.15 `game-lib.jar`. See `agent/README_CN.md` for build and regression workflow.

Commercial game binaries/assets are not project source. Fog-of-war, UNKNOWN state, ownership, and evidence boundaries remain part of the engineering contract.
