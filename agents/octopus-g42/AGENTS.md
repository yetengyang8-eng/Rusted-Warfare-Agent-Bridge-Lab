# Remote Agent Operating Contract

This repository is the complete working surface for remote Astra / Codex / other engineering agents. Remote agents must assume they **cannot access the user's local `G:\deepseek 工作台`** unless a file has been copied into GitHub.

## Read order

1. `ASTRA_START_HERE.md`
2. `project-state/ASTRA_CONTEXT.md`
3. `project-state/CURRENT_STATE.md`
4. `project-state/NEXT_STAGE_PLAN.md`
5. the source and evidence referenced by the current task

Do not start from historical mission files, old handoffs, old candidate names, or Git history unless the current files explicitly send you there.

## Source of truth

- Production source: `agent/` and `tools/` on latest `main` or a task branch from it.
- Current state: `project-state/CURRENT_STATE.md`.
- Current direction: `project-state/NEXT_STAGE_PLAN.md`.
- Remote-project context and latest desktop facts: `project-state/ASTRA_CONTEXT.md`.
- Evidence: `evidence/`.
- Historical material is context only and never overrides current state.

Frozen baseline `RW-BASELINE-2026-09-30-GS-v1` is retained for lineage. The active candidate is newer and must not be reset merely because source drift exists.
## Engineering rules

- Preserve fog-of-war and legal-observation boundaries. UNKNOWN remains UNKNOWN. Static assets may describe unit mechanics; they may not reveal hidden live enemy state.
- Do not modify or redistribute commercial Rusted Warfare binaries/assets. Use existing manifests and user-supplied compatible resources.
- Preserve validated behavior unless the task explicitly replaces it: bootstrap builder recovery, bounded local cohorts, local crisis response, rear engineer providers, amphibious Dive chain, T2/T3 mine investment, surplus artillery, ownership/lease rules, Target Guard, and bounded fairness scheduling.
- Runtime prices/actions should come from legal native menus when available. Frozen INI/JAR/community mechanism notes are validation inputs, not automatic hard-coded truth.
- Do not infer kills, win rate, causal improvement, or hidden-target resolution from proxy metrics.
- Keep failures, PARTIAL runs, parser corrections, and evidence limitations visible.

## Scope discipline

Work on one coherent engineering breakpoint per implementation round. Do not simultaneously redesign economy, combat, recon, and the entire scheduler unless the task explicitly requires their integration.

If a new issue is discovered outside the current task, record it as a follow-up rather than silently expanding scope.

## Validation expectations

For production changes: add/update focused tests; run affected regression; run the broader matrix when subsystems cross; preserve raw outputs; distinguish synthetic fixtures from natural native runs; update state/plan/evidence when a stable candidate is reached.

A parser/auditor PASS is not a game victory. A desktop observation is not automatically a controlled A/B result.

## Git workflow

Prefer `astra/<task>-YYYYMMDD` for substantial Astra work. Commit in reviewable units. Do not force-push `main`. At handoff report branch, commit SHA(s), files changed, tests run, evidence, limitations, and one recommended next breakpoint.