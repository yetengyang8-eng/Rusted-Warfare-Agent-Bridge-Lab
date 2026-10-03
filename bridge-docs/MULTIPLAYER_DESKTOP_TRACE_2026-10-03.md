# Multiplayer Desktop Trace — 2026-10-03

Source: one real Windows desktop session launched with `RW-Agent-Start.bat`, using the same frozen `game-lib.jar` SHA256 `8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9` and Octopus bootstrap JAR SHA256 `efc150e8822d69511c5ff92477a6d5153c9f2673b128baa3be5b110eb82fa885`.

This document is sanitized. Raw public-server IPs, server UUIDs, direct-join room code, and the local player nickname are intentionally omitted.

## PROVED

1. The desktop Java runtime can load the public server list without Steam. The game log explicitly reports `steam not requested` and later performs master-server requests successfully.
2. Public-list refresh calls two master-server endpoints in parallel: `gs1.corrodinggames.com/masterserver/1.4` and `gs4.corrodinggames.net/masterserver/1.4`.
3. One refresh returned roughly 350–376 listed servers per master response during this session.
4. Selecting a public-list entry produced a sanitized join token shaped like `get|<server-id>|<value>|false|<port>`; the engine resolved that token through the master service to a concrete endpoint and then opened a TCP connection.
5. After connection, the engine entered the multiplayer battleroom and emitted `sendRegisterConnection...`.
6. A joined public match started as a real native network game, not a local simulation. The log emitted `Starting new network game (...)` and `applyPendingNetworkUnits: Applying new network units from server (121 units)`.
7. The joined game loaded a native skirmish map, initialized team fog, recorded a replay, and advanced network frames. When the client fell behind, the network engine fast-forwarded toward the server frame range.
8. The joined public game later ended because the remote server timed out/disconnected; this is a real remote-network lifecycle witness.
## Relay / host path witness

A second public-list connection reached the official relay path. The engine logged a relay protocol version and then received a native `become server` packet.

After that packet, the same desktop client gained room-host/admin behavior: it could select a map, add/configure an AI slot, and call `mp.multiplayerStart()`.

Starting the room emitted `Sending start game....` followed by another `Starting new network game (...)`, then loaded Ice Island with team fog and ran a complete native match to AI defeat.

The relay also issued a temporary direct-join room code to the host. That code is not included here.

This is strong evidence that host/join lifecycle logic exists in the Java/game runtime and does not require Steam initialization in this desktop path.

## Direct-join witness

A user-entered relay-style direct-join token was converted by the game into the official relay host path and attempted over TCP. That particular attempt connected but then timed out. This proves parsing/routing of the direct-join form, not that every relay room is reachable.

## Artifacts retained locally

Two native replays were generated during the session: one from the joined public multiplayer game and one from the relay-hosted Ice Island game. Raw replays remain local because they can contain player/chat metadata.

The raw `rw-agent-game.log` also remains local. It contains endpoint/IP/server identifiers that are unnecessary for the public bridge repository.
## What this means for Astra

The most promising first implementation path is now more concrete:

- reuse the existing original Java network stack;
- reproduce public/master resolution or direct relay join without relying on UI clicks;
- reproduce the native relay `become server` / host path for one process;
- bind a second process as a normal joined player;
- only after same-game transport exists, enable player-scoped Agent command submission.

The desktop evidence does **not** yet prove that the current `HeadlessRunner` initialization can execute these network flows unchanged. The runner uses null graphics / no-input initialization and explicitly keeps its current instance non-networked. Astra still needs a focused headless host/join experiment.

It also does not prove that existing `RuntimeBridge` command calls are network-safe. Current source intentionally rejects `networked=true`; command synchronization must be demonstrated separately.

## Probe note

The first deployment of the auxiliary multiplayer probe had a packaging mistake: the desktop BAT files referenced a local `multiplayer-probe/` script directory that had not been copied beside them. Therefore this session did **not** produce `/state` samples or live socket snapshots from the probe.

The missing script folder has now been deployed locally for any later run. The multiplayer facts above come from the native game log and native replay creation, not from the failed probe.

## High-value next proof

The next desktop/headless proof should capture `/state` while a network match is active and verify:

- `networked=true`;
- local `player.teamId` identity;
- session/frame progression;
- own-unit scope;
- fog-gated enemy visibility;
- no hidden-state leakage.

Only after that should an Agent command be enabled in multiplayer, preferably first with a harmless move command and a second-client observation witness.
