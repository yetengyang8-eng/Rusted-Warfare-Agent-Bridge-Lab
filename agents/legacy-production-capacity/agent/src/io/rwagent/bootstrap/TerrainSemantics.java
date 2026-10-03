package io.rwagent.bootstrap;

import com.corrodinggames.rts.game.units.ao;

/** Meaning of a legally observed native tile; no static map grid is loaded at runtime. */
final class TerrainSemantics {
    static final String PACKET_SHA256 =
            "ec38ef06c7ba378db7d4eb420a087f5205183a0bf02df400085db4d80196beb9";
    static final String SOURCE_ID =
            "native_visible_tile_flags+terrain_d;knowledge_packet_2026-09-29/TERRAIN_MAP_CANDIDATES.json#sha256:"
            + PACKET_SHA256;
    static final int WATER=1,BRIDGE=2,LAVA=4,CLIFF=8,LARGE_OBSTACLE=16,RESOURCE=32,
            SMALL_ROCK=64,HARD_BLOCK=128,BLOCK_BUILDINGS=256;
    private TerrainSemantics() {}
    static int flags(boolean water,boolean bridge,boolean lava,boolean cliff,
                     boolean largeObstacle,boolean resource,byte rawCost,boolean blockBuildings) {
        int result=0;
        if(water)result|=WATER;
        if(bridge)result|=BRIDGE;
        if(lava)result|=LAVA;
        if(cliff)result|=CLIFF;
        if(largeObstacle)result|=LARGE_OBSTACLE;
        if(resource)result|=RESOURCE;
        if(rawCost==40)result|=SMALL_ROCK;
        if(rawCost==-1)result|=HARD_BLOCK;
        if(blockBuildings)result|=BLOCK_BUILDINGS;
        return result;
    }
    static String movementName(ao movement) {
        if(movement==ao.a)return "NONE";
        if(movement==ao.b)return "LAND";
        if(movement==ao.c)return "BUILDING";
        if(movement==ao.d)return "AIR";
        if(movement==ao.e)return "WATER";
        if(movement==ao.f)return "HOVER";
        if(movement==ao.g)return "OVER_CLIFF";
        if(movement==ao.h)return "OVER_CLIFF_WATER";
        return "UNKNOWN";
    }
    static String blockReason(int flags,ao movement){
        if((flags&LAVA)!=0)return "LAVA";
        if((flags&HARD_BLOCK)!=0)return "LARGE_ROCK_OR_BLOCK_LAND";
        if((flags&LARGE_OBSTACLE)!=0&&movement!=ao.g&&movement!=ao.h)
            return "LARGE_CLIFF_OR_TREES";
        if((flags&CLIFF)!=0&&(movement==ao.b||movement==ao.c||movement==ao.e))
            return "CLIFF";
        if((flags&WATER)!=0&&(movement==ao.b||movement==ao.c||movement==ao.g))
            return "WATER";
        if(movement==ao.e&&(flags&(WATER|BRIDGE))==0)return "NOT_WATER";
        if(movement==ao.b&&(flags&RESOURCE)!=0)return "RESOURCE_POOL";
        return "NATIVE_TERRAIN_BLOCKED";
    }
    static String json(int flags){
        return "{\"water\":"+((flags&WATER)!=0)+",\"waterBridge\":"+((flags&BRIDGE)!=0)
                +",\"lava\":"+((flags&LAVA)!=0)+",\"cliff\":"+((flags&CLIFF)!=0)
                +",\"largeCliffOrTrees\":"+((flags&LARGE_OBSTACLE)!=0)
                +",\"resource\":"+((flags&RESOURCE)!=0)+",\"smallRock\":"+((flags&SMALL_ROCK)!=0)
                +",\"largeRockOrBlockLand\":"+((flags&HARD_BLOCK)!=0)
                +",\"blockBuildings\":"+((flags&BLOCK_BUILDINGS)!=0)+"}";
    }
}
