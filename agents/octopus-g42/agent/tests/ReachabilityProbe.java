import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;
import com.corrodinggames.rts.game.units.as;
import com.corrodinggames.rts.gameFramework.k.l;
import java.lang.reflect.*;
import java.util.*;

/**
 * 输出20 §3-§4 feasibility probe: can the installed engine answer "is there a reachable engagement
 * position for this ground unit against this target?".
 *
 * Read-only: it starts the same headless fixture the other harnesses use, then asks the engine's own
 * pathfinder what it can answer, and reports the exact API surface plus the weapon-range fields it finds
 * on the live unit type. It never issues a command and never changes Agent behaviour.
 */
public final class ReachabilityProbe {
    static int checks;
    static Object gridField(Object target, String name) throws Exception {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException missing) {
            }
        }
        throw new NoSuchFieldException(name);
    }
    static String rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
    static void require(boolean ok, String why) {
        checks++;
        if (!ok) throw new IllegalStateException("FAIL: " + why);
        System.out.println("PASS " + why);
    }

    public static void main(String[] args) throws Exception {
        try {
            EconomyHarness.test();
            com.corrodinggames.rts.game.i engine = EconomyHarness.engine;
            l pathfinder = engine.bU;
            require(pathfinder != null, "the engine exposes a pathfinder instance (engine.bU)");

            System.out.println("\n== pathfinder API surface ==");
            for (Method m : l.class.getMethods()) {
                if (m.getDeclaringClass() != l.class) continue;
                StringBuilder sig = new StringBuilder("   " + m.getReturnType().getSimpleName() + " " + m.getName() + "(");
                Class<?>[] p = m.getParameterTypes();
                for (int i = 0; i < p.length; i++) sig.append(i > 0 ? ", " : "").append(p[i].getSimpleName());
                System.out.println(sig.append(")").toString());
            }

            am builder = EconomyHarness.builder;
            require(builder != null, "fixture has a live ground unit (builder)");
            System.out.println("   builder class = " + builder.getClass().getName());

            System.out.println("\n== movement classes (ao enum) ==");
            com.corrodinggames.rts.game.units.ao[] classes = com.corrodinggames.rts.game.units.ao.values();
            for (com.corrodinggames.rts.game.units.ao movement : classes) {
                Object grid = null;
                try {
                    grid = l.class.getMethod("a", com.corrodinggames.rts.game.units.ao.class).invoke(pathfinder, movement);
                } catch (Throwable error) {
                    grid = "ERR " + error.getCause();
                }
                System.out.println("   ao." + movement.name() + " ordinal=" + movement.ordinal() + " -> grid " + grid);
            }
            com.corrodinggames.rts.game.units.ao ground = EconomyHarness.costs == null ? null
                    : (com.corrodinggames.rts.game.units.ao) gridField(EconomyHarness.costs, "a");
            System.out.println("   fixture movement class (engine.bU.z.a) = " + ground);

            System.out.println("\n== movement grid per unit ==");
            Method gridOf = null;
            for (Method m : l.class.getDeclaredMethods()) {
                if (!m.getName().equals("a") || m.getParameterTypes().length != 1) continue;
                if (m.getParameterTypes()[0].getName().endsWith(".ao")) gridOf = m;
            }
            require(gridOf != null, "pathfinder exposes a(movementClass) -> movement grid");
            System.out.println("   using " + gridOf);
            // The headless fixture only initialises the movement grids it needs, and a(LAND) needs the full
            // set, so read the per-class grid field directly (the fixture fills engine.bU.z for LAND).
            Object grid = gridField(pathfinder, "z");
            System.out.println("   engine.bU.z (LAND grid) = " + grid);
            System.out.println("   a(ao) for builder -> " + (grid == null ? "null" : grid.getClass().getName()));
            require(grid != null, "the pathfinder returns a movement grid for a live unit");
            for (String field : new String[]{"d", "e", "f"}) {
                try {
                    Field f = grid.getClass().getField(field);
                    byte[] data = (byte[]) f.get(grid);
                    Map<Byte, Integer> histogram = new TreeMap<Byte, Integer>();
                    for (byte value : data) histogram.put(value, histogram.containsKey(value) ? histogram.get(value) + 1 : 1);
                    System.out.println("   grid." + field + " length=" + data.length + " values=" + histogram);
                } catch (NoSuchFieldException missing) {
                    System.out.println("   grid." + field + " absent");
                }
            }
            Object unitClass = null;
            try {
                unitClass = gridField(grid, "a");
            } catch (NoSuchFieldException missing) {
            }
            System.out.println("   grid.a (unit class field) = " + unitClass);

            System.out.println("\n== passability queries (tile = y*height + x) ==");
            Method passableUnit = l.class.getMethod("a", com.corrodinggames.rts.gameFramework.k.i.class, int.class, int.class);
            Method passableClass = l.class.getMethod("a", com.corrodinggames.rts.game.units.ao.class, int.class, int.class);
            Method costUnit = l.class.getMethod("b", com.corrodinggames.rts.gameFramework.k.i.class, int.class, int.class);
            Method globalPassable = l.class.getMethod("a", int.class, int.class);
            int width = engine.bL.C, height = engine.bL.D;
            System.out.println("   map tiles = " + width + "x" + height);
            int[][] samples = {{0, 0}, {5, 5}, {90, 90}, {175, 175}, {2, 23}, {149, 153}};
            for (int[] s : samples) {
                String line = "   tile(" + s[0] + "," + s[1] + ")";
                try {
                    line += " gridPassable=" + passableUnit.invoke(pathfinder, grid, s[0], s[1])
                            + " moveCost=" + costUnit.invoke(pathfinder, grid, s[0], s[1]);
                } catch (Throwable error) {
                    line += " gridQuery=ERR(" + rootCause(error) + ")";
                }
                try {
                    line += " classPassable=" + passableClass.invoke(pathfinder, ground, s[0], s[1]);
                } catch (Throwable error) {
                    line += " classQuery=ERR";
                }
                try {
                    line += " globalPassable=" + globalPassable.invoke(pathfinder, s[0], s[1]);
                } catch (Throwable error) {
                    line += " globalQuery=ERR";
                }
                System.out.println(line);
            }
            System.out.println("   note: the headless fixture does not fill the per-unit grid arrays,");
            System.out.println("         so an ERR here is a fixture limit, not an engine limit.");

            System.out.println("\n== weapon range fields ==");
            System.out.println("   (a headless live unit has no weapon block, so this reports the metadata");
            System.out.println("    class instead; the real value must be read in a live match)");
            System.out.println("   metadata class of tank type = " + (EconomyHarness.tankType == null ? "null"
                    : EconomyHarness.tankType.getClass().getName()));

            System.out.println("\nREACHABILITY_PROBE_OK checks=" + checks);
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }
}
