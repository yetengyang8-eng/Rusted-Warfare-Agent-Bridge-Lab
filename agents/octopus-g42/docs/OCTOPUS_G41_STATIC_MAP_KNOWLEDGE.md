# Octopus G4.1 — Static Map Knowledge contract

2026-10-03. The user authorizes fixed map priors independently of current fog. Dynamic enemy information still requires legal current visibility.

## Authority and cache

`bootstrap.StaticMapKnowledge` snapshots the current loaded original map's Ground, Items and PathingOverride tile definitions. It copies static flags/raw costs and fixed Items resource coordinates. Repository `knowledge/TERRAIN_MAP_CANDIDATES.json` is an oracle/cross-check, never a runtime map loader. `TerrainSemantics.staticCost` mirrors the verified frozen native `gameFramework.k.i.d` terrain-only ordering, including override replacement rather than OR/max.

Cache identity includes session, native map object, dimensions/tile size and engine SHA. Each identity scans native static tiles once. A game SHA mismatch returns UNKNOWN. Fog, visibility, enemies, building/unit obstruction grids and native global path grids are excluded from the snapshot kernel. Only the approach endpoint's separate legal own-mobile validation accesses live actors; it samples no enemy attributes. Native grids may be compared/poisoned in tests, never consumed by the runtime static kernel.

Static route fields are memoized by movement domain and target tile within that snapshot. A reverse multi-source BFS starts from lawful static resource-rally candidates once; different actors and changing start positions reuse its distance/next/goal arrays. An LRU of eight fields bounds memory. Responses expose routeFieldBuildCount/cacheSize for verification. There is no full native-tile scan or new full-grid BFS for each actor observation; session/map/SHA invalidation clears the fields.

Movement-domain connectivity describes fixed terrain only. It cannot prove dynamic reachability, safety, occupation, action legality or arrival. Native final movement/build guards remain authoritative. Desired unit movement type does not establish an amphibious actor's physical mode or weapon/terrain attack permission.

## Endpoints

| GET | Response |
| --- | --- |
| `/static-map/observe` | KNOWN/UNKNOWN, session/frame/gameTime, knowledgeId, mapPath/SHA, bounds, fixed resourceTiles, source layers and explicit UNKNOWN dynamic fields |
| `/static-map/observe?tile=N` | Same packet plus this tile's separate Ground/Items/override flags and movement terrain costs; no full terrain JSON per observation |
| `/static-map/approach?unitId=ID&tile=N` | Legal ready own mobile only; matching source identity, target/domain, statically connected rally/approach and bounded waypoint, or UNKNOWN |

Tile indexing is column-major `x*height+y`; world centers are `((x+.5)*tileWidth,(y+.5)*tileHeight)`. Static resource approach uses a passable point 60..120 world units from the resource. A short waypoint is bounded by 1200 world units of static path length. Four-neighbor connectivity is a conservative static proof, not full native movement mechanics.

All static responses explicitly retain `safe`, `occupied`, `buildable`, `dynamicReachability` = UNKNOWN. Frame/gameTime are the bridge read clock; they are not a newly inferred resource/terrain event time. The same immutable knowledgeId survives fog changes. Session/map/SHA changes invalidate it.

Old ScoutBridge visibility history, legally seen `resources`, remembered threats and path evidence remain their own channel. Full-map prior does not make a tile currently visible, clear a LocalResponse, expose a hidden unit, or authorize a hidden building footprint. SearchArea remains inactive.

## Verification boundary

Fog-on/off must yield identical fixed resource/terrain data while combat enemy exports change with legal visibility. At least one real map's runtime resource coordinates are compared separately with repository knowledge. Native pure terrain grids are test oracles only; dynamic building/unit grids are explicitly excluded. Synthetic pathing overrides exercise replacement semantics, independently from unmodified map samples. Tests must distinguish cached static routing and artificial position witnesses from natural travel.
