import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;

/**
 * Replicates BOTH movement-class resolution orders and reports, per accessor, (a) what it returned and
 * (b) whether the global registry size changed - i.e. whether the diagnostic created a unit.
 */
public final class MovementResolutionProbe {
    static final Class<?> MOVE;
    static { Class<?> c = null; try { c = Class.forName("com.corrodinggames.rts.game.units.ao"); } catch (Throwable t) {} MOVE = c; }

    static Object call(Object target, String name) {
        try { return target.getClass().getMethod(name).invoke(target); }
        catch (Throwable t) { return "ERR " + t.getClass().getSimpleName(); }
    }

    static void scan(String label, Object type, boolean stopAtFirstMatch) {
        System.out.println("-- " + label);
        for (String name : new String[]{"u","k","n","m","e","d","c","b","a"}) {
            int before = am.bE.size();
            Object value = call(type, name);
            int after = am.bE.size();
            boolean isMove = value != null && MOVE != null && MOVE.isInstance(value);
            System.out.println(String.format("   %-2s -> %-46s registryDelta=%+d%s",
                    name, String.valueOf(value).length() > 44 ? String.valueOf(value).substring(0, 44) : String.valueOf(value),
                    after - before, isMove ? "   <== MATCH (" + value + ")" : ""));
            if (isMove && stopAtFirstMatch) { System.out.println("   (scan would STOP here)"); return; }
        }
    }

    public static void main(String[] a) throws Exception {
        EconomyHarness.test();
        EconomyHarness.set(Class.forName("com.corrodinggames.rts.game.units.e.n"), "b",
                BridgeHarness.construct("com.corrodinggames.rts.gameFramework.m.e"));
        ar type = ar.valueOf("tank"); type.h();
        am unit = type.a(true);
        unit.eh = 900; unit.bX = EconomyHarness.engine.bs; unit.cm = 1;
        unit.eo = 941; unit.ep = 1781;
        am.bE.add(unit);
        System.out.println("registrySize with fixture unit = " + am.bE.size());
        System.out.println();
        System.out.println("unit.h() = " + call(unit, "h") + "   (current build tries this FIRST)");
        System.out.println("unit.r() = " + call(unit, "r"));
        System.out.println();
        scan("INCIDENT ORDER (r() then field scan u,k,n,m,e,d,c,b,a)", call(unit, "r"), true);
        System.out.println();
        scan("FULL SCAN (no early stop)", call(unit, "r"), false);
        System.out.println();
        System.out.println("registrySize final = " + am.bE.size());
        System.exit(0);
    }
}
