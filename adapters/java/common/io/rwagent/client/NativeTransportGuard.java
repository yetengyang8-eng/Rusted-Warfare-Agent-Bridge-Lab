package io.rwagent.client;

import java.util.Map;

/** Capability-gated adapter only. Does not rewrite game mode or strategy decisions. */
final class NativeTransportGuard {
    private static String binding;
    static boolean allowed(Map<?, ?> state) {
        try { require(state); return true; }
        catch (IllegalStateException unsupported) { return false; }
    }
    static synchronized void require(Map<?, ?> state) {
        if (Boolean.TRUE.equals(state.get("replay")))
            throw new IllegalStateException("Replay control is disabled");
        boolean networked = Boolean.TRUE.equals(state.get("networked"));
        if (!networked) {
            if (Boolean.getBoolean("rwagent.nativeNetworkRequired"))
                throw new IllegalStateException("Native network match required by this adapter");
            return;
        }
        if (!Boolean.TRUE.equals(state.get("nativeNetworkPlayerV1"))
                || !"native-network-local-player".equals(state.get("transport"))
                || !(state.get("protocolVersion") instanceof Number)
                || ((Number) state.get("protocolVersion")).intValue() != 1
                || !(state.get("playerId") instanceof Number)
                || !(state.get("teamId") instanceof Number)
                || !(state.get("sessionId") instanceof String)
                || !(state.get("matchId") instanceof String)
                || state.get("player") == null)
            throw new IllegalStateException("Verified native local-player capability required");
        Object identityObject = state.get("identity");
        if (!(identityObject instanceof Map))
            throw new IllegalStateException("Native player binding evidence required");
        Map<?, ?> identity = (Map<?, ?>) identityObject;
        if (!Boolean.TRUE.equals(identity.get("bindingValid"))
                || !Boolean.TRUE.equals(identity.get("localPlayerVerified"))
                || !state.get("playerId").equals(identity.get("playerId"))
                || !state.get("teamId").equals(identity.get("teamId"))
                || !state.get("matchId").equals(identity.get("matchId")))
            throw new IllegalStateException("Native player binding is invalid");
        String current = state.get("sessionId") + "/" + state.get("matchId")
                + "/" + state.get("playerId") + "/" + state.get("teamId");
        if (binding == null) binding = current;
        else if (!binding.equals(current))
            throw new IllegalStateException("Adapter session/player binding changed");
    }
}
