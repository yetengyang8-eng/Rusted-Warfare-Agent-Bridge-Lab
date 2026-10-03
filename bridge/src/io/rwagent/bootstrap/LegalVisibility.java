package io.rwagent.bootstrap;

import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ao;
import com.corrodinggames.rts.game.units.as;
import com.corrodinggames.rts.game.units.ar;
import com.corrodinggames.rts.game.units.y;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;

/** Observation-only obstacle samples. Never query the omniscient dynamic path grid. */
final class LegalVisibility {
    private LegalVisibility() {}
    /** Native d.d.a placement mapping: ordinary NONE/BUILDING factories use the LAND grid. */
    static ao placementMovement(as type) {
        ao movement=type.o();
        if(type==ar.d || movement==ao.e) return ao.e;
        if(movement==ao.d || movement==ao.f || movement==ao.g || movement==ao.h) return movement;
        return ao.b;
    }
    static boolean observed(RuntimeBridge bridge, am unit) {
        if(unit==null || bridge.player()==null) return false;
        return unit.bX==bridge.player() || (bridge.engine.bL!=null
                && unit.d(bridge.player()) && bridge.engine.bL.a(unit.eo,unit.ep,bridge.player()));
    }
    static List<am> observedBuildings(RuntimeBridge bridge) {
        List<am> result=new ArrayList<am>();
        for(Object raw:am.bE) {
            am unit=(am)raw;
            // Fog gate BEFORE querying live building footprint or collision state.
            if(!observed(bridge,unit)) continue;
            if(!unit.ej && !unit.bV && !unit.cW() && unit.bI()) result.add(unit);
        }
        return result;
    }
    static boolean buildingBlocks(RuntimeBridge bridge,List<am> buildings,int col,int row,ao movement) {
        if(movement==ao.a || movement==ao.d) return false;
        int tw=bridge.engine.bL.n,th=bridge.engine.bL.o;
        for(am unit:buildings) {
            Rect footprint=unit.cd();
            int c=(int)Math.floor((unit.eo-unit.cZ()+1)/tw);
            int r=(int)Math.floor((unit.ep-unit.da()+1)/th);
            if(col>=c+footprint.a && col<=c+footprint.c && row>=r+footprint.b && row<=r+footprint.d)
                return true;
        }
        return false;
    }
    static byte staticTerrainCost(RuntimeBridge bridge,ao movement,int col,int row) {
        com.corrodinggames.rts.gameFramework.k.i costs=bridge.engine.bU.a(movement);
        int tile=col*bridge.engine.bL.D+row;
        if(costs==null || costs.d==null || tile<0 || tile>=costs.d.length)
            throw new IllegalStateException("static terrain costs unavailable");
        // gameFramework.k.i.d is terrain-only; e/f/g contain dynamic blockers and are forbidden.
        return costs.d[tile];
    }
}
