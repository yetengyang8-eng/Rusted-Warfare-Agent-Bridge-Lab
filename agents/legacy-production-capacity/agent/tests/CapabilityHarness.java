import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;
import java.lang.reflect.*;
import java.util.*;

/**
 * Read-only native capability probe (输出8 §6 and §2).
 *
 * Two questions it answers from the engine instead of from experience:
 *   1. unit capabilities that have no ini file - the built-in units such as heavyTank are compiled into
 *      game-lib.jar, so their air/ground flags can only be read from the loaded metadata;
 *   2. which own buildings expose a "produce builder" action, with the native action id and cost, so
 *      P2-C1 can be built on real menu data.
 *
 * It never issues a command. Discovery is best-effort: the shared headless fixture cannot initialise
 * every unit class (turret graphics, lazily built action lists), so anything it cannot probe is listed
 * explicitly as UNPROBED instead of silently guessing. The metadata price check doubles as a self test,
 * because the live production menu already reports c_tank=350 and heavyTank=800.
 */
public final class CapabilityHarness {
    static final Class<?> ACTION = cls("com.corrodinggames.rts.game.units.a.s");
    static final Method TYPE = method(ACTION, "i"), ACTION_ID = method(ACTION, "N"), COST = method(ACTION, "c");
    static final Method AVAILABLE = method(ACTION, "b", am.class), AFFORDABLE = method(ACTION, "a", am.class, boolean.class);
    static final Class<?> FACTORY = cls("com.corrodinggames.rts.game.units.d.l");
    static final String[] UNITS = {"tank", "heavyTank", "hoverTank", "antiAirTurret", "gunship", "builder", "commandCenter"};
    static final String[] PRODUCERS = {"commandCenter", "landFactory"};
    static final List<String> UNPROBED = new ArrayList<String>();
    static final Map<String, Object> PRICES = new LinkedHashMap<String, Object>();
    static int probed;

    public static void main(String[] args) throws Exception {
        try {
            OpeningHarness.test();
            EconomyHarness.engine.bs.o = 10000;
            prepareTurretImages();
            for (String name : UNITS) attempt(name);
            for (String name : PRODUCERS) attempt(name);
            for (Map.Entry<String, Object> price : PRICES.entrySet())
                System.out.println("PRICE " + price.getKey() + "=" + price.getValue());
            // Self test: these two prices are already known from the live production menu.
            BridgeHarness.require(Integer.valueOf(350).equals(PRICES.get("tank")), "tank price is 350");
            BridgeHarness.require(Integer.valueOf(800).equals(PRICES.get("heavyTank")), "heavyTank price is 800");
            BridgeHarness.require(probed > 0, "the capability probe read at least one unit type");
            System.out.println("CAPABILITY_TEST_OK checks=" + BridgeHarness.checks
                    + " probed=" + probed + " unprobed=" + UNPROBED);
            System.exit(0);
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }

    /**
     * Turret-equipped units build their turret graphics in the constructor and the shared fixture only
     * allocates an empty image array; without image objects those constructors fail. Filling the array
     * keeps the probe possible without touching the game world.
     */
    static void prepareTurretImages() throws Exception {
        Field holder = Class.forName("com.corrodinggames.rts.game.units.d.g").getDeclaredField("e");
        holder.setAccessible(true);
        Object images = holder.get(null);
        if (images == null || !images.getClass().isArray()) return;
        for (int i = 0; i < Array.getLength(images); i++)
            if (Array.get(images, i) == null)
                Array.set(images, i, BridgeHarness.construct("com.corrodinggames.rts.gameFramework.m.e"));
    }

    static void attempt(String name) {
        try { probe(name); }
        catch (Throwable error) {
            UNPROBED.add(name + " (" + error.getClass().getSimpleName() + ")");
            System.out.println("UNPROBED " + name + ": " + error);
        }
    }

    static void probe(String name) throws Exception {
        ar type;
        try { type = ar.valueOf(name); } catch (Throwable error) {
            UNPROBED.add(name + " (not a built-in type name)");
            System.out.println("UNPROBED " + name + ": not a built-in type name in this build");
            return;
        }
        if (type == null) { UNPROBED.add(name); return; }
        System.out.println("UNIT " + name);
        printBooleans("  metadata", type);
        printNumbers("  metadata", type);
        Object shared = null;
        try { shared = type.u(); } catch (Throwable ignored) { }
        if (shared != null) {
            Object price = field(shared, "b");
            if (price instanceof Number) PRICES.put(name, Integer.valueOf(((Number) price).intValue()));
            dumpTree("  meta.u", shared, 2);
        }
        try {
            try { type.h(); } catch (Throwable ignored) { }   // h() builds the type's native action list
            am unit = type.a(true);
            System.out.println("  instance class=" + unit.getClass().getName());
            printBooleans("  instance", unit);
            printNumbers("  instance", unit);
            if (FACTORY.isInstance(unit)) System.out.println("  isFactory=true");
            actions(name, unit);
        } catch (Throwable error) {
            System.out.println("  instance/actions unavailable in this fixture: " + error);
        }
        probed++;
    }

    static void actions(String name, am unit) {
        unit.bX = EconomyHarness.engine.bs;
        unit.cm = 1;
        List<?> items;
        try { items = unit.N(); } catch (Throwable error) {
            System.out.println("  action list unavailable: " + error);
            return;
        }
        for (Object item : items) {
            try {
                String kind = String.valueOf(invoke(TYPE, item));
                String id = String.valueOf(invoke(ACTION_ID, item));
                Object cost = invoke(COST, item);
                boolean available = Boolean.TRUE.equals(invoke(AVAILABLE, item, unit));
                boolean affordable = Boolean.TRUE.equals(invoke(AFFORDABLE, item, unit, true));
                boolean builder = "builder".equals(kind) || id.toLowerCase().contains("builder")
                        || String.valueOf(item).toLowerCase().contains("builder");
                System.out.println("  action " + name + " class=" + item.getClass().getSimpleName() + " type=" + kind
                        + " id=" + id + " cost=" + cost + " available=" + available + " affordable=" + affordable
                        + (builder ? "  <== BUILDER" : ""));
            } catch (Throwable error) {
                System.out.println("  action unreadable: " + error);
            }
        }
    }

    static Object field(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable error) { return null; }
    }

    static void printBooleans(String prefix, Object target) {
        for (Field f : fields(target.getClass())) {
            if (f.getType() != boolean.class && f.getType() != Boolean.class) continue;
            if (!readable(f)) continue;
            try { System.out.println(prefix + " " + f.getName() + "=" + f.get(target)); } catch (Throwable ignored) { }
        }
    }

    static void printNumbers(String prefix, Object target) {
        for (Field f : fields(target.getClass())) {
            Class<?> t = f.getType();
            if (t != int.class && t != float.class && t != double.class && t != short.class) continue;
            if (!readable(f)) continue;
            try {
                Object value = f.get(target);
                if (value instanceof Number && ((Number) value).doubleValue() == 0) continue;
                System.out.println(prefix + " " + f.getName() + "=" + value);
            } catch (Throwable ignored) { }
        }
    }

    static boolean readable(Field f) {
        try { f.setAccessible(true); return true; } catch (Throwable error) { return false; }
    }

    /** Prints booleans/numbers of an object and, one level deeper, of the game objects it holds. */
    static void dumpTree(String prefix, Object target, int depth) {
        printBooleans(prefix, target);
        printNumbers(prefix, target);
        if (depth <= 0) return;
        for (Field f : fields(target.getClass())) {
            if (f.getType().isPrimitive() || f.getType().getName().startsWith("java.")) continue;
            if (!readable(f)) continue;
            Object value;
            try { value = f.get(target); } catch (Throwable ignored) { continue; }
            if (value == null || !value.getClass().getName().startsWith("com.corrodinggames")) continue;
            System.out.println("  " + prefix + "." + f.getName() + " class=" + value.getClass().getName());
            dumpTree("  " + prefix + "." + f.getName(), value, depth - 1);
        }
    }

    static List<Field> fields(Class<?> type) {
        List<Field> out = new ArrayList<Field>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().startsWith("java.")) break;   // java.lang.Enum internals are not ours to read
            for (Field f : c.getDeclaredFields())
                if (!Modifier.isStatic(f.getModifiers())) out.add(f);
        }
        Collections.sort(out, new Comparator<Field>() {
            public int compare(Field a, Field b) { return a.getName().compareTo(b.getName()); }
        });
        return out;
    }

    static Class<?> cls(String name) { try { return Class.forName(name); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    static Method method(Class<?> type, String name, Class<?>... params) {
        try { return type.getMethod(name, params); } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    static Object invoke(Method method, Object target, Object... args) {
        try { return method.invoke(target, args); } catch (Exception e) { throw new IllegalStateException("native probe failed: " + method, e); }
    }
}
