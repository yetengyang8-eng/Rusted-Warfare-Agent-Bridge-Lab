# Focused player scope checks

```sh
python tests/player_scope/run.py
python tools/build_bridge.py --bridge-only
python tests/player_scope/run.py --native-placement
```

The first harness uses real 1.15 native data objects and tests authorization,
original network `cf.d` queue selection, retry idempotency, foreign-ID rejection,
fog loss memory, hidden building footprint exclusion, dynamic blocker poisoning,
and permanent identity revocation. It does not advance a multiplayer simulation.

The optional test loads the original Small Island map into an isolated engine
working directory. It reproduces the exact factory site `(1090,1730)` that
intersects resource tile `(53,89)`. Native built-in `landFactory.o()` is `NONE`,
but original placement uses the LAND grid. The bridge therefore maps placement
movement exactly, rejects the resource overlap, and returns the next candidate
`(990,1830)`, which the original native placement check accepts. The global native
placement check exists only in this referee test, never in a public player API.

Frozen strategy and engine sources are not edited. Multiplayer remote effects
must be verified separately by `orchestrator.run_match` and native evidence.
