import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;

/** Which single engine call of the DEFAULT endpoint path writes cu = -1? */
public final class CuMutationTraceProbe {
    static am attacker, target;
    static boolean dirty = false;
    static void after(String step) {
        boolean now = attacker.cu < 0 || target.cu < 0;
        System.out.println(String.format("   %-38s attacker cu=%-8.0f target cu=%-8.0f %s",
                step, attacker.cu, target.cu, now && !dirty ? "  <== cu CHANGED HERE" : ""));
        dirty = dirty || now;
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
        System.out.println("CU_TRACE_BEGIN");
        after("baseline");
        System.out.println("   attacker.cW()=" + attacker.cW() + " target.cW()=" + target.cW()); after("cW() liveness check");
        System.out.println("   attacker.r()=" + attacker.r()); after("attacker.r()");
        System.out.println("   attacker.r().i()=" + attacker.r().i()); after("attacker.r().i()");
        System.out.println("   target.r()=" + target.r()); after("target.r()");
        System.out.println("   target.r().i()=" + target.r().i()); after("target.r().i()");
        System.out.println("   target.bI()=" + target.bI()); after("target.bI()");
        System.out.println("   attacker.h()=" + attacker.h()); after("attacker.h()");
        System.out.println("   engine.bL.D=" + engine.bL.D + " engine.bL.C=" + engine.bL.C); after("map dimensions");
        System.out.println("   attacker.cj=" + attacker.cj + " target.cj=" + target.cj); after("cj");
        System.out.println("   field scan u()=" + t1.getClass().getMethod("u").invoke(t1)); after("type.u()");
        System.out.println("   field scan d()=" + t1.getClass().getMethod("d").invoke(t1)); after("type.d()");
        System.out.println("CU_TRACE_END");
        System.exit(0);
    }
}
