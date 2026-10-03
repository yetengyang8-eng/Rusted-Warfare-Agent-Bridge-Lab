import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;
public final class AllowlistTraceProbe {
    public static void main(String[] a) throws Exception {
        EconomyHarness.test();
        com.corrodinggames.rts.game.i engine = EconomyHarness.engine;
        EconomyHarness.set(Class.forName("com.corrodinggames.rts.game.units.e.n"), "b",
                BridgeHarness.construct("com.corrodinggames.rts.gameFramework.m.e"));
        ar t1 = ar.valueOf("tank"); t1.h();
        am attacker = t1.a(true); am.bF.put(t1, attacker);
        attacker.eh = 772; attacker.bX = engine.bs; attacker.cm = 1; attacker.eo = 941; attacker.ep = 1781;
        am.bE.add(attacker);
        ar t2 = ar.valueOf("commandCenter"); t2.h();
        am target = t2.a(true); am.bF.put(t2, target);
        target.eh = 771; target.bX = new com.corrodinggames.rts.game.e(1, false);
        target.cm = 1; target.eo = 1200; target.ep = 1700;
        am.bE.add(target);
        System.out.println(BridgeHarness.check("GET",
            "/combat/reachability?unitId=772&targetId=771&stages=allowlist", 200, "allowlist"));
        System.exit(0);
    }
}
