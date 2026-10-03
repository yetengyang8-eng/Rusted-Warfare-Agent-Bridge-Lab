import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;

/** Decisive minimal test: does ONE default endpoint call change cu/cv? */
public final class EndpointMutationProbe {
    static am attacker, target;
    static String hp() {
        return String.format("attacker cu/cv=%.0f/%.0f  target cu/cv=%.0f/%.0f  reg=%d",
                attacker.cu, attacker.cv, target.cu, target.cv, am.bE.size());
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
        System.out.println("before            : " + hp());
        String body = BridgeHarness.check("GET", "/combat/reachability?unitId=772&targetId=771", 200, "status");
        System.out.println("after ONE call    : " + hp());
        System.out.println("payload hp fields : " + (body.contains("\"cu\"") ? "payload contains cu" : "payload has no cu field"));
        String body2 = BridgeHarness.check("GET", "/combat/reachability?unitId=772&targetId=771", 200, "status");
        System.out.println("after SECOND call : " + hp());
        System.out.println("observation hp    : " + BridgeHarness.check("GET", "/state", 200, "hp").replaceAll(".*\"hp\":([0-9.]+).*", "$1"));
        System.exit(0);
    }
}
