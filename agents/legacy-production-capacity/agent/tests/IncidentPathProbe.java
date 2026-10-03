import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;
import java.util.*;

/** 输出27 Q4: run the true incident-equivalent endpoint path and report its step accounting. */
public final class IncidentPathProbe {
    static am attacker, target;
    static void show(String label) {
        System.out.println(String.format("   %-40s attacker cu/cv=%.0f/%.0f target cu/cv=%.0f/%.0f reg=%d",
                label, attacker.cu, attacker.cv, target.cu, target.cv, am.bE.size()));
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
        System.out.println("INCIDENT_PATH_BEGIN");
        show("baseline");
        String body = BridgeHarness.check("GET", base + "&stages=incident", 200, "INCIDENT_EQUIVALENT_ENDPOINT_PATH");
        Map<?, ?> parsed = EconomyHarness.obj(body);
        System.out.println("   pathMode      : " + parsed.get("pathMode"));
        System.out.println("   executedSteps : " + parsed.get("executedSteps"));
        System.out.println("   skippedSteps  : " + parsed.get("skippedSteps"));
        System.out.println("   threwSteps    : " + parsed.get("threwSteps"));
        System.out.println("   detail        : " + parsed.get("detail"));
        show("after incident path");
        BridgeHarness.check("GET", base, 200, "status");
        show("after default path (post-fix)");
        System.out.println("INCIDENT_PATH_END");
        System.exit(0);
    }
}
