# Source Identities

## Octopus bridge baseline

- source repository: `yetengyang8-eng/Rusted-Warfare-Agent`
- source commit: `83c09fbbadee2640137cc7431d628c8682509140`
- purpose: completed G0–G4.1 plus natural zero-factory bootstrap fix used for bridge integration
- snapshot path: `agents/octopus-g42/`
- binary: `binaries/octopus-g42-83c09fb.jar`
- binary SHA256: `EFC150E8822D69511C5FF92477A6D5153C9F2673B128BAA3BE5B110EB82FA885`

This snapshot intentionally does **not** contain the local uncommitted G5 work (`CombatLedger`, `CommanderDirector`, `GeneralCombatDirector`, `ThreatTask`, human observer changes, etc.). The bridge must not depend on G5 internals.

## Legacy Production Capacity baseline

- source repository: `yetengyang8-eng/Rusted-Warfare-Agent`
- source commit: `be7ba9485c9a589483d9a586c4a35a975e061a5b`
- snapshot path: `agents/legacy-production-capacity/`
- binary: `binaries/legacy-production-capacity.jar`
- binary SHA256: `A392F692E010C8429A7A93072E60323C83A9DEB1ED471BBCBDDBD3BBBAF6ADB6`

## Original engine relay

- Rusted Warfare PC 1.15 Build #28 / Game Code 176
- `game-lib.jar` SHA256: `8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9`
- package: `engine-relay/headless-engine-1.15.zip`
- package SHA256: `6E73DA52173F12F3873FF092081287B58A0177B1B1B985092CC5B32B959E6E30`
- per-file manifest: `engine-relay/HEADLESS_ENGINE_MANIFEST.json`

The engine relay is for remote headless/native experiments. It is not evidence that same-game multiplayer already works.
