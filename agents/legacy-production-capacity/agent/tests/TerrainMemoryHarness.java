import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ao;
import com.corrodinggames.rts.game.units.ar;
import com.corrodinggames.rts.gameFramework.k.i;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Focused E2 fixture for legal terrain memory and Recon terrain provenance. */
public final class TerrainMemoryHarness {
    private static final int HEIGHT = 110;
    private static final String PACKET_SHA = "ec38ef06c7ba378db7d4eb420a087f5205183a0bf02df400085db4d80196beb9";

    private static void require(boolean ok, String why) { BridgeHarness.require(ok, why); }
    private static Map<?, ?> get(String path) throws Exception {
        return EconomyHarness.obj(BridgeHarness.check("GET", path, 200, "sessionId"));
    }
    private static Map<?, ?> child(Map<?, ?> map, String key) { return (Map<?, ?>) map.get(key); }
    private static int tile(int x, int y) { return x * HEIGHT + y; }
    private static int integer(Object value) { return ((Number) value).intValue(); }

    public static void main(String[] args) throws Exception {
        try {
            OpeningHarness.test();
            com.corrodinggames.rts.game.i engine = EconomyHarness.engine;
            engine.cf.b.clear();
            engine.bx = 20;
            engine.by = 1234;
            for (byte[] column : engine.bs.N) Arrays.fill(column, (byte) 10);
            for (int x = 35; x <= 60; x++) for (int y = 75; y <= 103; y++) engine.bs.N[x][y] = 0;

            // Extend the established OpeningHarness map palette with two visible native tile flags.
            Class<?> tileClass = Class.forName("com.corrodinggames.rts.game.b.g");
            Constructor<?> tileConstructor = tileClass.getDeclaredConstructor();
            tileConstructor.setAccessible(true);
            Object water = tileConstructor.newInstance(); EconomyHarness.set(water, "e", true);
            Object cliff = tileConstructor.newInstance(); EconomyHarness.set(cliff, "h", true);
            Field paletteField = engine.bL.getClass().getDeclaredField("B");
            paletteField.setAccessible(true);
            Object existingPalette = paletteField.get(engine.bL);
            Object palette = Array.newInstance(tileClass, 4);
            Array.set(palette, 1, Array.get(existingPalette, 1));
            Array.set(palette, 2, water);
            Array.set(palette, 3, cliff);
            paletteField.set(engine.bL, palette);
            short[] ground = engine.bL.u.q;
            ground[tile(40, 80)] = 2;
            ground[tile(41, 80)] = 3;
            // The source packet knows Small Island tile 0, but no unseen map cell is prefilled.
            // Poisoning the hidden tile makes an accidental native Ground lookup fail loudly.
            ground[0] = 32767;

            i land = (i) EconomyHarness.costs;
            i hover = (i) EconomyHarness.empty("com.corrodinggames.rts.gameFramework.k.i");
            EconomyHarness.set(hover, "a", ao.f);
            hover.d = new byte[110 * 110];
            hover.e = new byte[110 * 110];
            hover.f = new byte[110 * 110];
            land.d[tile(40, 80)] = -1;
            land.d[tile(41, 80)] = -1;
            // The hover terrain cost stays 0 at both water and ordinary cliff.
            EconomyHarness.set(engine.bU, "v", new i[]{land, hover});

            Map<?, ?> waterObservation = get("/scout/observe?tile=" + tile(40, 80));
            Map<?, ?> waterTile = child(waterObservation, "terrainTile");
            require("KNOWN".equals(waterTile.get("status"))
                    && Boolean.TRUE.equals(child(waterTile, "ground").get("water")),
                    "visible water is retained with its native Ground flag");
            Map<?, ?> waterCost = child(waterTile, "terrainCost");
            require(integer(waterCost.get("LAND")) == -1 && integer(waterCost.get("HOVER")) == 0,
                    "same visible water tile differs for LAND and HOVER");
            require(String.valueOf(waterTile.get("sourceId")).contains(PACKET_SHA),
                    "visible terrain records native and packet rule provenance");

            Map<?, ?> cliffTile = child(get("/scout/observe?tile=" + tile(41, 80)), "terrainTile");
            require("KNOWN".equals(cliffTile.get("status"))
                    && Boolean.TRUE.equals(child(cliffTile, "ground").get("cliff")),
                    "visible ordinary cliff is retained with its native Ground flag");
            Map<?, ?> cliffCost = child(cliffTile, "terrainCost");
            require(integer(cliffCost.get("LAND")) == -1 && integer(cliffCost.get("HOVER")) == 0,
                    "same visible cliff tile differs for LAND and HOVER");

            Map<?, ?> hiddenTile = child(get("/scout/observe?tile=0"), "terrainTile");
            require("UNKNOWN".equals(hiddenTile.get("status")) && !hiddenTile.containsKey("ground")
                    && !hiddenTile.containsKey("terrainCost") && !hiddenTile.containsKey("sourceId"),
                    "unseen terrain remains UNKNOWN without reading packet map or poisoned native tile");

            // A real native tank gives the Recon plan a LAND movement source and a route record.
            EconomyHarness.set(Class.forName("com.corrodinggames.rts.game.units.e.n"), "b",
                    BridgeHarness.construct("com.corrodinggames.rts.gameFramework.m.e"));
            ar tankType = ar.valueOf("tank"); tankType.h();
            am tank = tankType.a(true);
            tank.eh = 191; tank.bX = engine.bs; tank.eo = 940; tank.ep = 1780; tank.cm = 1;
            am.bE.add(tank);
            Map<?, ?> plan = get("/scout/plan?role=recon&unitId=191");
            require("planned".equals(plan.get("status")), "Recon retains an available frontier plan");
            Map<?, ?> route = child(plan, "routeTerrain");
            require("LAND".equals(plan.get("movementType")) && "LAND".equals(route.get("movementType")),
                    "Recon route states its actual native movement type");
            require("KNOWN".equals(route.get("status")) && integer(route.get("unknownCostTiles")) == 0,
                    "Recon route uses legally observed terrain costs");
            require(String.valueOf(route.get("sourceId")).contains(PACKET_SHA)
                    && route.get("sourceId").equals(plan.get("terrainSourceId")),
                    "Recon route and plan identify the same terrain evidence source");
            require(!((List<?>) plan.get("routeTiles")).isEmpty(), "Recon route has concrete known tiles");

            engine.bs.N[40][80] = 10;
            Map<?, ?> remembered = child(get("/scout/observe?tile=" + tile(40, 80)), "terrainTile");
            require("KNOWN".equals(remembered.get("status"))
                    && Boolean.FALSE.equals(remembered.get("currentlyVisible"))
                    && integer(child(remembered, "terrainCost").get("LAND")) == -1,
                    "legally observed water remains session memory after visibility is lost");
            String previousSession = String.valueOf(waterObservation.get("sessionId"));
            engine.bx = 0; // RuntimeBridge.refreshSession rotates on frame reset.
            Map<?, ?> afterSwitch = get("/scout/observe?tile=" + tile(40, 80));
            require(!previousSession.equals(afterSwitch.get("sessionId")), "frame reset creates a new session");
            require("UNKNOWN".equals(child(afterSwitch, "terrainTile").get("status")),
                    "session change invalidates cached terrain for a now hidden tile");
            require(engine.cf.b.isEmpty(), "terrain observation and Recon plan issued no commands");
            System.out.println("TERRAIN_MEMORY_E2_OK checks=" + BridgeHarness.checks);
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }
}
