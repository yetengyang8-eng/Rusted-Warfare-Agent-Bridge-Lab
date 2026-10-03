import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;

/** Which single-letter accessors on the unit TYPE create/register new units? Read-only observation. */
public final class TypeAccessorProbe {
    public static void main(String[] a) throws Exception {
        EconomyHarness.test();
        com.corrodinggames.rts.game.i engine = EconomyHarness.engine;
        EconomyHarness.set(Class.forName("com.corrodinggames.rts.game.units.e.n"), "b",
                BridgeHarness.construct("com.corrodinggames.rts.gameFramework.m.e"));
        ar type = ar.valueOf("tank"); type.h();
        int base = am.bE.size();
        System.out.println("registrySize base = " + base);
        String[] names = {"u","k","n","m","e","d","c","b","a"};
        for (String name : names) {
            int before = am.bE.size();
            Object value; String err = null;
            try {
                java.lang.reflect.Method m = type.getClass().getMethod(name);
                value = m.invoke(type);
            } catch (Throwable t) { value = null; err = t.getClass().getSimpleName() + ": " + t.getMessage(); }
            int after = am.bE.size();
            System.out.println(String.format("%-2s -> delta=%+d  size %d->%d  value=%s%s",
                    name, after - before, before, after,
                    value == null ? "null" : value.getClass().getName(),
                    err == null ? "" : "  ERR " + err));
        }
        System.out.println("registrySize final = " + am.bE.size());
        System.exit(0);
    }
}
