# Octopus G4.1 — Early Operations contract

2026-10-03; starting point `5562ec7`. Extends G4 with the first formation and early logistics support. No G5 combat/crisis ledger, HOT-COLD, ThreatTask, dynamic formation sizing, General-count cap or SearchArea activation.

## One General, FORMING → ACTIVE

When no valid General remains and a currently observed healthy ready ordinary FREE actor exists, create one empty-roster FORMING General with a real stable `general:N` identity. Creation neither ATTACHes nor transfers the first actor. Its target strength is `max(activeArmyTarget, LocalArmyDirector.FORM_MINIMUM)`, fixed at birth; no one-member target trap or proxy owner swap.

The observed own home position is a formation anchor, not an army member or attached centroid. `army()` / `armed()` are unchanged. Newly admitted actors retain the same-frame FREE barrier, then use FREE → PENDING_JOIN → JOINING → ATTACHED, including the first actor. FORMING JOINING targets the explicit rally anchor; the actual accepted native receipt frame must precede a fresh same-player own position witness within 180. Receipt/time alone cannot attach. ACTIVE uses its current attached centroid, as in G4.

One through five healthy ATTACHED members stay FORMING. Six members with current health facts, current own presence, real General ownership and ATTACHED/NONE membership upgrade the same General to ACTIVE. Reservations and stale NORMAL facts do not count. Activation changes no owner generation and does not automatically downgrade later. Existing mature entry seeds retain the G4 migration; small entry seeds use FORMING and the configured main-force target. An empty FORMING roster can persist while its ordinary actors are pending/responding; loss of all ordinary actors invalidates it. Subsequent healthy FREE actors can create a new ID without a startup-only latch.

FORMING cannot propose ordinary visible-target attacks or army frontier excursions. Existing Critical Retreat and lawful LocalResponse remain available. Ordinary response may detach the minimum sufficient FORMING actors even from a one-to-three-member formation; ACTIVE retains the existing three-member protection. Detached response clear → FREE; FREE-origin PENDING_JOIN + response clear → JOINING. Current visibility disappearance clears immediately, UNKNOWN retains the task. Severe JOINING interruption remains undefined and is not added here.

## ExpansionNeed and low-priority support

`EarlyExpansionNeed` is one local logistics target, bound to a fixed resource tile, static knowledge identity, exact source observation ID and own builder. REQUESTED means a static candidate exists, with safety, enemy occupancy and dynamic reachability UNKNOWN. APPROACHING records only an actual accepted move frame. LEGAL_SITE_OBSERVED requires the existing native current-site planner; CONSTRUCTION_OBSERVED and COMPLETE require current own unfinished/ready extractor facts. Accepted build receipts cannot produce COMPLETE.

The economic lane first tries its mature current-visible native `/expansion/plan`. If that refuses, G4.1 can use fixed resource candidates outside the old ±600 window, excluding legally known own extractors and existing known-threat refusals. Static approach determines only terrain connectivity and a bounded waypoint. The original native plan, price provenance, footprint/fog/collision checks and command guards still authorize actual building. Arrival at a static rally with continued native refusal cancels that attempt; it never asserts a free/safe site.

Current legal visible threat/occupancy near the target cancels logistics and lets existing response operate. LastObservation is not current enemy knowledge. Own builder loss, a different selected native site and an own ready extractor provide explicit cancel/complete reasons. No new periodic global planner is introduced; the existing economy lane and local controller reconcile these state changes.

Each healthy FORMING ATTACHED actor can independently read a static approach for the current need and propose a **single-unit** native move. `/command/move` does not accept a group `unitIds` request. A route UNKNOWN or missing receipt for one actor does not stall others. Receipt only starts that actor's cooldown, without claiming collective arrival, safety or progress. ACTIVE returns directly to existing G4 General behavior.

## Runtime ordering and evidence

G4 primary force proposals retain actual collection, frozen generations and priority dispatch: Critical90 / LocalResponse70 / JOINING50 / General30. Optional FORMING_SCREEN20 is retained until after mature Strategy/economy/Recon/ordinary production. Builder EARLY_EXPANSION15 is then collected; optional candidates use only remaining shared tokens and are sorted before dispatch. No fake queued receipts or token/credit/slot refunds are introduced.

Economics and production remain immediate caller dispatch; this is not a collect-all global scheduler. Optional support cannot take the last token before that observation's eligible production attempt. Required JOINING, response or retreat can still consume tokens as authorized G4 force tasks. Spending, paid-before-visible/ghost and unsettled-credit witnesses are unchanged.

Optional proposals bind the actual ExpansionNeed object at collection. Final dispatch requires that exact object still be current and live. A same-observation economy cancellation or replacement drops the old candidate before scheduler admission, HTTP, token consumption or callbacks; `g4_force_cancelled` records the original Intent and captured evidence. Recreating the same tile does not revive an old candidate, and the frozen owner generation is never rebuilt.

Collected trace freezes observation IDs, static-approach request path and knowledge/need identity before later reads overwrite endpoint-last observation pointers. General phase and ExpansionNeed changes record before/after, reason and detection observation. Detection/source observation time is not an invented native event time.

Native fixtures, synthetic HTTP main-loop timelines and natural simulation are distinct evidence layers. Queue progression, birth, position, height or clocks explicitly manipulated by fixtures do not prove natural march/production/construction duration or match benefit. Final gate counts and hashes belong in the candidate manifest and handoff, rather than this timeless contract.

`rwagent.earlyOperations=false` bypasses new absence-driven birth and static logistics support for historical G4 HTTP coverage; shared Registry phase fields remain. `rwagent.g4Forces=false` disables this force migration, and the existing `g3Execution=false` legacy path remains available.
