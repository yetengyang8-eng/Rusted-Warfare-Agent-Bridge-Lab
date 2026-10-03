package io.rwagent.bootstrap;

import com.corrodinggames.rts.game.n;
import com.corrodinggames.rts.game.i;

/** One process, one native local player. This is never a switchable PlayerContext. */
final class PlayerBinding {
    final i engine;
    final Object network;
    final n player;
    final Object map;
    final String matchId;
    final int playerId, teamId;
    private boolean revoked;

    private PlayerBinding(i engine, String matchId) {
        this.engine=engine; network=engine.bX; player=engine.bs; map=engine.bL;
        this.matchId=matchId; playerId=player.k; teamId=player.r;
    }

    static PlayerBinding nativeNetwork(i engine, String matchId, int expectedPlayerSlot) {
        if(matchId==null || !matchId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("matchId must contain 1..128 letters, digits, _ or -");
        if(engine==null || !engine.bG || engine.bL==null || engine.bX==null || !engine.bX.B
                || engine.bs==null || engine.bs.k<0 || engine.bs.b() || engine.bs.k!=expectedPlayerSlot
                || engine.bX.z!=engine.bs || engine.cf==null
                || (engine.cb!=null && engine.cb.j()))
            throw new IllegalStateException("loaded native network local-player identity was not verified");
        return new PlayerBinding(engine,matchId);
    }

    /** A disconnect, reload or identity/team change permanently revokes this match capability. */
    boolean valid() {
        if(!revoked && (!engine.bG || engine.bL!=map || engine.bX!=network || !engine.bX.B
                || engine.bX.z!=player || engine.bs!=player || player.k!=playerId || player.r!=teamId
                || (engine.cb!=null && engine.cb.j()))) revoked=true;
        return !revoked;
    }
}
