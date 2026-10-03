# Octopus G4 — General / FREE / LocalResponse / JOINING contract

G4.1 (2026-10-03) extends absence-driven first formation and early static-map logistics: [Early Operations](OCTOPUS_G41_EARLY_OPERATIONS.md), [Static Map Knowledge](OCTOPUS_G41_STATIC_MAP_KNOWLEDGE.md). The original G4 contract below remains historical; its zero-army no-birth boundary is superseded in the enabled G4.1 path. Ownership, response clear and later joining witnesses remain intact.

2026-10-02. G3 native green checkpoint `b3d0a3b`; runtime switch `rwagent.g4Forces`, enabled by default when G3 execution is enabled. This is the minimal own-force migration; no G5 combat/crisis ledger or new General birth policy.

## Authority and independent control

`GeneralRegistry` owns membership/allocation, and `CommandArbiter` remains command-ownership/generation authority. A General has an independent `GeneralId`, real `general:N` owner, ATTACHED roster, PENDING/JOINING reservations, current own centroid and accepted goal/direction. A LocalArmy cohort is only a possible migration seed, never the command owner.

BattleClient seeds only ordinary combat troops already ready in the entry observation. It uses the existing 400-world-unit spatial envelope and 48-member native group bound, without the old four-cohort cap or any General-count cap. Strategy first claims specialists; those actors stay outside ordinary forces. Later ready products are admitted FREE, including ready completion rather than merely first sighting of an unfinished unit. Initial-size replenishment counts ATTACHED + reservations. No dynamic formation sizing, fixed FREE reserve, accumulation-driven General birth or automatic product absorption.

## Orthogonal unit facts

| Axis | Values / meaning |
| --- | --- |
| command owner | `force:free`, `general:N`, `local-response:N`, `join:N`, or a borrowed external Recon owner; exactly one real arbiter owner |
| allocation | FREE / PENDING_JOIN / ASSIGNED |
| membership | UNATTACHED / JOINING / ATTACHED |
| temporary task | NONE / LOCAL_RESPONSE; PENDING_JOIN can coexist with response |
| health role | NORMAL / RECOVERING; facts are independent of ownership |

| Trigger | Result / owner |
| --- | --- |
| Ordinary new ready product | FREE + UNATTACHED / `force:free` |
| Formal vacancy allocation, in a later frame than admission | PENDING_JOIN reservation; if response active its owner/task continue |
| Pending with no response | JOINING / `join:N`; reservation retained |
| Relevant enemy absent from a complete, fresh, same-session legal current visibility packet | Response ends immediately; pending → JOINING, no pending → FREE; no last-known search, clear timer or death inference |
| Missing/stale/foreign combat coverage | UNKNOWN; cannot clear response or become current enemy knowledge |
| JOINING move accepted | Exact actual native receipt frame retained; receipt does not ATTACH |
| Later current own position within 180 of target General's current own centroid | ATTACHED + ASSIGNED / `general:N`; reservation removed |
| Target General invalid / no current attached main force | Pending/join reservation removed; JOINING → FREE. An unrelated active response can retain its task while allocation becomes FREE |
| General temporarily lends actors to ordinary response | True detach and transfer; clears former membership. On clear → FREE, never automatic return. Explicit allocator may reassign in a later frame |
| Recon borrow/release | Only eligible FREE actors; real per-actor transfer. Release → FREE; readiness statistics count the ordinary army rather than requiring seven FREE candidates |

Every owner change increments per-actor generation. Captured older Intent generations are rejected before native transport, including owner A → B → A. `releaseActor` changes only that actor. One unit cannot be ATTACHED twice or have two reservations. Trace carries reason, before/after, observation identity and source frame/game time; General create/goal/invalidate also carry old/new state.

## Actual runtime ordering

Within each existing decision gate:

1. Strategy observation/leases → G3 batch → G4 current-own admission/centroid/arrival and formal allocation.
2. Existing builder-recovery lane may dispatch immediately.
3. All G4 force proposals are collected with immutable owner-generation snapshots; actual BattleClient flush sorts priority/lane/Intent ID **before** native dispatch. Critical retreat 90, LocalResponse 70, JOINING 50, General 30; optional FREE activity would be lower priority. No fake queued receipts. Only actual accepted native receipts invoke callbacks/cooldowns.
4. Mature Strategy/economy/Recon/ordinary production continue **immediate dispatch in caller traversal** with remaining tokens. Their numeric lane priorities are metadata within this immediate path, not a global priority guarantee against the collected force batch.

Priority therefore has real runtime meaning among G4 force controllers. It is not collect-all/global arbitration over the entire agent. Real native tests reverse actual collected proposals and show response winning the last token; sorted scheduler unit tests alone are not offered as runtime evidence.

## Minimal behavior and boundaries

Ordinary response uses the existing lawful native compatibility/path adapter, HP floor/distance envelope and HP coverage factor 1.5, with minimal sufficient actors (1..6), FREE/pending first. There is no six-FREE floor. General fallback preserves the existing three-member detachment protection. This is not a damage simulator, HOT/COLD score or severe crisis interruption rule.

Generals use current visible targets or the mature lawful native army frontier plan. Frontier path evidence proves the anchor, not every member; same-type actors retain existing native final guards. SearchArea stays contract-only. FREE receives no speculative wander order when a useful lawful home route is unavailable; it can answer ordinary harassment or be formally allocated.

An initially empty ordinary force creates no General, and later products remain FREE until a separately authorized birth policy. This is explicit G4 scope, covered by a main-loop test, not hidden auto-birth. A surviving General fills initial-size losses/detach vacancies; surplus remains FREE. JOINING remains autonomous even with a changed health role; injury/crisis interruption and reservation cancellation policy are deferred rather than invented here.

Native fixtures use original 1.15 objects/HTTP/command processing with explicit clocks, queue progress and positions. They are not natural match/desktop acceptance. Spain bridge terrain evidence, physical mode, desired movement and attack permission remain separated as described in `OCTOPUS_G3_NATIVE_ACCEPTANCE.md`; natural entry/firing is NEEDS_EVIDENCE.
