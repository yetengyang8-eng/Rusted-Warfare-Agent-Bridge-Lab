import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;
import java.lang.reflect.*;

/** Minimal contrast: direct field read vs reflection Field.get vs Class.getMethod, and the effect on cu. */
public final class ReadContrastProbe {
    static am u;
    static void show(String label) {
        System.out.println(String.format("   %-46s cu=%-8.0f cv=%-8.0f", label, u.cu, u.cv));
    }
    public static void main(String[] a) throws Exception {
        EconomyHarness.test();
        com.corrodinggames.rts.game.i engine = EconomyHarness.engine;
        EconomyHarness.set(Class.forName("com.corrodinggames.rts.game.units.e.n"), "b",
                BridgeHarness.construct("com.corrodinggames.rts.gameFramework.m.e"));
        ar t = ar.valueOf("tank"); t.h();
        u = t.a(true); u.eh = 772; u.bX = engine.bs; u.cm = 1; u.eo = 941; u.ep = 1781;
        am.bE.add(u);
        System.out.println("READ_CONTRAST_BEGIN");
        show("baseline");
        float direct = u.cj;
        show("after DIRECT field read  u.cj=" + direct);
        Field f = am.class.getDeclaredField("cj"); f.setAccessible(true);
        Object v1 = f.get(u);
        show("after reflective Field.get(cj)=" + v1);
        Method m = null;
        try { m = am.class.getMethod("cj"); } catch (NoSuchMethodException e) { }
        show("after Class.getMethod(\"cj\") -> " + m);
        Object v2 = (m == null) ? null : m.invoke(u);
        show("after invoke/null -> " + v2);
        // 输出27 §2/Q3: call the void mutator directly - immediate effect, and idempotence check.
        System.out.println("   direct am.cj() calls:");
        u.cj();
        show("after u.cj() #1");
        u.cj();
        show("after u.cj() #2 (idempotent)");
        System.out.println("   (engine tick / delayed effect: DELAYED_EFFECT_UNTESTED_FIXTURE_LIMITATION - no game loop in this fixture)");
        System.out.println("READ_CONTRAST_END");
        System.exit(0);
    }
}
