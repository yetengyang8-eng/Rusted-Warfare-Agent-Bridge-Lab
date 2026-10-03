import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;

/**
 * 输出27 §3 (Q1): staged isolation - where exactly does cu become -1?
 *
 * The bridge's own HP export is unit.cu / unit.cv (RuntimeBridge.java:351-352), so "cu == -1" would be a
 * real anomaly if it happened. This probe replays the default request's stages one at a time on the live
 * fixture objects and prints the HP triple after every stage, so the first stage that changes it is
 * identified instead of guessed. Pure reads only.
 */
public final class FieldReadIsolationProbe {
    static am attacker, target;

    static void snapshot(String stage) {
        System.out.println(String.format("   %-34s attacker cu/cv=%s/%s  target cu/cv=%s/%s",
                stage, f(attacker.cu), f(attacker.cv), f(target.cu), f(target.cv)));
    }

    static String f(float v) {
        return String.format("%.0f", v);
    }

    static Object field(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field fld = c.getDeclaredField(name);
                fld.setAccessible(true);
                return fld.get(o);
            } catch (NoSuchFieldException missing) {
            } catch (Throwable t) {
                return "ERR " + t.getClass().getSimpleName();
            }
        }
        return "ABSENT";
    }

    static int floatFieldCount(Object o) {
        int count = 0;
        for (Class<?> c = o.getClass(); c != null && c != Object.class && !c.getName().startsWith("java."); c = c.getSuperclass()) {
            for (java.lang.reflect.Field fld : c.getDeclaredFields()) {
                if (fld.getType() == float.class) {
                    try { fld.setAccessible(true); fld.get(o); count++; } catch (Throwable ignored) { }
                }
            }
        }
        return count;
    }

    public static void main(String[] args) throws Exception {
        EconomyHarness.test();
        com.corrodinggames.rts.game.i engine = EconomyHarness.engine;
        EconomyHarness.set(Class.forName("com.corrodinggames.rts.game.units.e.n"), "b",
                BridgeHarness.construct("com.corrodinggames.rts.gameFramework.m.e"));
        ar type = ar.valueOf("tank"); type.h();
        attacker = type.a(true);
        am.bF.put(type, attacker);
        attacker.eh = 772; attacker.bX = engine.bs; attacker.cm = 1; attacker.eo = 941; attacker.ep = 1781;
        am.bE.add(attacker);
        ar tType = ar.valueOf("commandCenter"); tType.h();
        target = tType.a(true);
        am.bF.put(tType, target);
        target.eh = 771; target.bX = new com.corrodinggames.rts.game.e(1, false);
        target.cm = 1; target.eo = 1200; target.ep = 1700;
        am.bE.add(target);

        System.out.println("FIELD_READ_ISOLATION_BEGIN");
        snapshot("S0 fixture create");
        snapshot("S0b second snapshot (idempotence)");
        System.out.println("   own()/anyUnit() equivalent: " + (am.bE.size()) + " units scanned");
        snapshot("S1 resolve (list walk only)");
        Object idA = field(attacker, "eh"), idT = field(target, "eh");
        snapshot("S2 identity Field.get  [" + idA + "/" + idT + "]");
        Object posA = field(attacker, "eo"), posT = field(target, "eo");
        snapshot("S2b position Field.get  [" + posA + "/" + posT + "]");
        Object cuA = field(attacker, "cu");
        snapshot("S3 cu Field.get       [" + cuA + "]");
        Object cvA = field(attacker, "cv");
        snapshot("S4 cv Field.get       [" + cvA + "]");
        System.out.println("   S5 declared float dump: attacker=" + floatFieldCount(attacker)
                + " fields, target=" + floatFieldCount(target) + " fields");
        snapshot("S5 declared float dump");
        Object cj = field(attacker, "cj");
        snapshot("S6 collisionRadius Field.get  [" + cj + "]");
        String typeName = String.valueOf(attacker.r() == null ? "unknown" : attacker.r().i());
        boolean building = target.bI();
        snapshot("S7 type/building metadata  [" + typeName + "/" + building + "]");
        String json = "{\"hp\":" + attacker.cu + ",\"maxHp\":" + attacker.cv + "}";
        snapshot("S8 JSON serialization  [" + json + "]");

        System.out.println();
        System.out.println("engine methods used by the DEFAULT endpoint path (source-verified):");
        System.out.println("   own()/anyUnit(): indexed walk of am.bE plus u.cW() liveness check");
        System.out.println("   attacker.r() / attacker.r().i()  -> attackerType string");
        System.out.println("   target.r()   / target.r().i()    -> targetType string");
        System.out.println("   target.bI()                      -> targetBuilding");
        System.out.println("FIELD_READ_ISOLATION_END");
        System.exit(0);
    }
}
