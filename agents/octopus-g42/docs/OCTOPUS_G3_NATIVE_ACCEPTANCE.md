# G3 native acceptance / hardening — 2026-10-02

Starting point: branch `codex/octopus-g3-execution-20261002`, accepted `b7c5f51`.
Stage gate: **GREEN** before G4 implementation. No deployment, push or desktop game control.

## Corrections proved before G4

1. Accepted/transport-unknown spending now retains an intent-specific **unsettled credit** hold across game-time changes. A receipt, timer, wallet decrease, missing actor or empty queue cannot release it. A later legal own-state queue/tier effect, or an unambiguous construction site effect with the builder's current build coordinates, can settle the credit hold. Settlement does not refund the current batch, token or producer/military slot. Construction association is the existing type/site envelope, not confirmed producer lineage; ambiguous or unavailable evidence remains held. `/state` adds legal own build-order coordinates only.
2. Amphibious `movementType=AIR/WATER` records desired navigation mode only. Actual own `/combat/unit-modes` Boolean, with matching actor/session and fresh source time, witnesses the frozen jet's native `eq < -1` threshold. Receipt, physical threshold, terrain legality and target attack permission remain distinct. G3 enabled Strategy obtains this independent mode packet; the explicit legacy toggle preserves its GET sequence.
3. Spending Intents retain true `costSourceObservationId` + full `costSourceRequestPath` from the original validated production/investment menu, construction-plan or expansion-plan response identity. Missing sources remain UNKNOWN and G3-enabled spending admission is withheld; the explicit legacy toggle preserves its old gate. `/state` is never a price source; bridge final price/action checks remain authoritative.

## Same-JAR isolated native evidence

JAR: `G:\deepseek 工作台\_validation\octopus-g4-20261002\native\focused-final.jar`.
SHA256: `4378b137307fa32b20c473a17b79f41a7c18bc63358b2cacb55ab84c04c63c59`.
Frozen original engine SHA256: `8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9`.
Evidence class: **E2_NATIVE_FIXTURE_WITH_REAL_HTTP / NO_NATURAL_MATCH**. Real 1.15 units, RuntimeBridge HTTP, BattleClient callers, scheduler and native command processing; explicit fixture clocks/positions/heights, no natural simulation ticks or measured desktop 5× speed.

| Case | Result | Evidence boundary |
| --- | --- | --- |
| Production | PASS 168 | 12 T2 producers, 2500/3500/60000ms source jumps, 3/4/4 unique producer orders; burst <=4. Native queue/payment witnessed separately. Across-clock delayed execution with 1600 credits admits only two 800 orders. Pending military slots hold. Native construction helper pays 700 and creates a real unfinished factory; later site effect settles the credit hold, not completion. |
| Independent modes | PASS 30 | Wet and dry jets advance independently. Desired Dive with height still air is not READY. One member's Fly does not alter another. Actual threshold requires a later independent own-mode packet. |
| Caller lane competition | PASS 31 | Actual LocalCrisis → Recon → production consumes three tokens in caller traversal order. This is **immediate dispatch**, not runtime global priority; `dispatchBatch` sorting tests are not substituted for this result. |
| Spain water bridge | PASS_BOUNDED_ACCESSORS 9 | Attached custom map `[10p] 10p 西班牙混战_by_MP97.tmx`, not an original built-in map. Unmodified tile70 at (26,230) is land/water passable but overWater=false: direct Dive receives 409; a stably submerged fixture placed there remains physically submerged. Native target selection rejects a non-water ground tank, but accepts a submerged jet. Therefore “cannot attack any target” is not established. Natural movement entry and firing/damage remain NEEDS_EVIDENCE. No strategy changed from this hypothesis. |

Raw logs and JSONL: the same native directory's `final-production`, `final-modes`, `final-competition`, `final-spain` directories and `final-*.log`. Original strict mode failure and real delayed-payment overspend failure are retained in earlier attempt directories. Spain original/copy TMX SHA256: `342db6d8a8b8320b6a271b9e3c8a4c29c96a203e206c4be690c44bbef5882746`.

Focused: ExecutionScheduler 130, NativeCreditWitness 109, CapabilityLifecycle 44, quote provenance 72, EngineerProvider 61, StrategyContract 169 checks PASS. Hardened-JAR G3 HTTP suite **11/11 PASS** (`root-focused/g3-hardened.log`). Full Windows regression is reserved for the integrated G4 candidate.

Known conservative boundary: effects missed entirely between snapshots, unresolved transport outcomes, actor loss before attributable effects or ambiguous construction retain funds as NEEDS_EVIDENCE. They are not silently refunded. Queue nonempty settles submitted spending; queue empty is never product ready.
