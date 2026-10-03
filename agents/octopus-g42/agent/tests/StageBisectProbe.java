import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;

/** Staged bisect: which request stage writes cu = -1? */
public final class StageBisectProbe {
    static am attacker, target;
    static void show(String label) {
        System.out.println(String.format("   %-42s attacker cu/cv=%.0f/%.0f target cu/cv=%.0f/%.0f",
                label, attacker.cu, attacker.cv, target.cu, target.cv));
    }
    public static void main(String[] a) throws Exception {
        EconomyHarness.test();
        com.corrodinggames.rts.game.i engine = EconomyHarness.engine;
        EconomyHarness.set(Class.forName("com.corrodinggames.rts.game.units.e.n"), "b",
                BridgeHarness.construct("com.corrodinggames.rts.gameFramework.m.e"));
        ar t1 = ar.valueOf("tank"); t1.h();
        attacker = t1.a(true); am.bF.put(t1, attacker);
        attacker.eh = 772; attacker.bX = engine.bs; attacker.cm = 1; attacker.eo = 941; attacker.ep = 1781;
        am.bE.add(attacker);
        ar t2 = ar.valueOf("commandCenter"); t2.h();
        target = t2.a(true); am.bF.put(t2, target);
        target.eh = 771; target.bX = new com.corrodinggames.rts.game.e(1, false);
        target.cm = 1; target.eo = 1200; target.ep = 1700;
        am.bE.add(target);
        String base = "/combat/reachability?unitId=772&targetId=771";
        System.out.println("STAGE_BISECT_BEGIN");
        show("baseline");
        BridgeHarness.check("GET", base + "&stages=resolve", 200, "resolve"); show("after stages=resolve");
        BridgeHarness.check("GET", base + "&stages=type", 200, "type"); show("after stages=type (r.i)");
        BridgeHarness.check("GET", base + "&stages=building", 200, "building"); show("after stages=building (bI)");
        BridgeHarness.check("GET", base + "&stages=floats", 200, "floats"); show("after stages=floats (scan)");
        BridgeHarness.check("GET", base + "&stages=radius", 200, "radius"); show("after stages=radius (cj)");
        BridgeHarness.check("GET", base + "&stages=dump", 200, "dump"); show("after stages=dump (float dump)");
        BridgeHarness.check("GET", base, 200, "status"); show("after full default request");
        System.out.println("STAGE_BISECT_END");
        System.exit(0);
    }
}
