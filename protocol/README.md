# Local Player Protocol v1

The gateway serves one verified native network player's loopback endpoint. It never
changes `networked=true` into single-player. Different clients require different
gateway processes, ports, sessions, evidence files and observation memory. Native
multiplayer is the transport; HTTP is only the local player control interface.

| Method | Endpoint | Response or request |
|---|---|---|
| GET | `/v1/capabilities` | Version, bound identity, agent artifact identity, available command forms and receipt semantics |
| GET | `/v1/identity` | Immutable match/session/player/team identity and artifact digests |
| GET | `/v1/observe` | Own units, own credits, current legally visible enemies, static map bounds, native result |
| GET | `/v1/lifecycle` | Running/ended classification, native result, disconnect status if supplied by native bridge |
| GET | `/v1/action-menu?kind=produce` | Own native production action IDs, products, costs and affordability |
| GET | `/v1/action-menu?kind=upgrade` | Own native upgrade actions; optional own `unitId` filter |
| GET | `/v1/action-menu?kind=build&unitId=ID` | One own constructor's native build actions |
| GET | `/v1/action-menu?kind=unitMode&unitId=ID` | One own unit's native mode actions |
| POST | `/v1/actions` | Identity-bound action and native queue acknowledgement |

`identity` is `{matchId, sessionId, playerId, teamId}`. `playerId` is the native
slot (`n.k`); `teamId` is the native alliance group (`n.r`), **not** the legacy
`player.teamId` field which older clients use for the slot. Match identity comes
from the native runner; session identity is independently created by each player's
bridge. A running local-player binding must be verified before any response or
command. Identity changes fail closed and require a new gateway; there is no
automatic reconnect or player rebind in v1.

Example action, after GET `/v1/observe`:

```json
{
  "protocolVersion": 1,
  "identity": {"matchId": "example", "sessionId": "from-observation", "playerId": 0, "teamId": 0},
  "observationSequence": 1,
  "actionSequence": 1,
  "requestId": "move-1",
  "kind": "move",
  "unitIds": [123],
  "x": 400,
  "y": 500
}
```

| Kind | Required parameters beyond common identity/sequence fields | Native endpoint |
|---|---|---|
| `move` | One own unit, `x`, `y` | `/command/move` |
| `attackMove` | 1–48 distinct own units, `x`, `y` | `/command/attack-move` |
| `guard` | One own unit, `targetId` | `/command/guard` |
| `build` | One own unit, `x`, `y`, `unitType=extractor/landFactory` or a legal `nativeActionId` | Build extractor/factory or construct |
| `produce` | One own unit, legal `nativeActionId` | `/command/queue` |
| `upgrade` | One own unit, legal `nativeActionId` | `/command/invest` |
| `unitMode` | One own unit, legal `nativeActionId` | `/command/unit-mode` |

These are accepted command **forms**, not claims that every form has native remote
effect evidence. The native bridge still validates action availability, ownership,
visibility, resources and placement. Queued receipts always carry
`executedEffect=UNKNOWN`. Only a later legal observation can establish an effect;
queue acceptance does not prove arrival, damage, a kill, or production completion.
Action sequences must increase. Identical retries with one request ID return the
same cached receipt; a conflicting reuse is rejected. Commands must reference a
gateway-issued observation, retained for the latest 1024 observations.
Request IDs must match the native command intersection: 1–64 ASCII letters,
digits, underscores or hyphens.

`currentVisibleEnemies` excludes remembered enemies and lost contacts. Own and
enemy observations are consecutive native requests rather than an atomic engine
snapshot; `sourceFrames` states the two sample frames. Static map bounds contain
no dynamic occupancy or hidden path blockers. Result state is native; a timeout
in the harness is not converted to victory or defeat. Missing disconnect evidence
is `UNKNOWN`.

The public boundary contains no strategy object or internal agent state. The
existing frozen Java agents additionally use explicitly allowlisted compatibility
endpoints on this same gateway. These preserve their original observation and
decision loops; they are an adapter binding, not a stable third-party interface.
Third-party gateway identities cannot access compatibility routes. Full public
action menus select whitelisted action ID, product, cost and availability fields
from own native units only; they omit runtime classes and diagnostic internals.
Availability in a menu does not establish build placement or later command effect.

Gateway start:

```sh
python protocol/gateway.py --upstream-port 47653 --port 47753 \
  --match-id MATCH --player-id 0 --agent octopus --evidence run/player-A/gateway.jsonl
python -m adapters.launch --agent octopus --port 47753 --seconds 120
```

The gateway is loopback-only and rejects browser-origin requests. It is not an
authenticated remote service. Do not expose its port beyond a trusted local
machine. Build overlays using the repository build tool before starting a frozen
agent gateway. Each frozen adapter reports a composite artifact digest over the
original JAR digest plus the capability overlay JAR digest; the source manifest
records original and patched source digests.
