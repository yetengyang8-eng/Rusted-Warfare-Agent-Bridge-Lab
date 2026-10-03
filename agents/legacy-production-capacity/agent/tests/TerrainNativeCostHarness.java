import android.content.ServerContext;
import com.corrodinggames.rts.game.units.ao;
import com.corrodinggames.rts.gameFramework.l;
import io.rwagent.client.Json;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * E2-only comparison of native, initialized terrain d[] with the frozen packet.
 * Run in a copied game directory, one map per JVM. No game ticks, commands, or path queries.
 */
public final class TerrainNativeCostHarness {
    private static final String GAME_SHA = "8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9";
    private static final ao[] TERRAIN_TYPES = {ao.b, ao.c, ao.e, ao.f, ao.g, ao.h};
    private static final class NoInputView extends com.corrodinggames.rts.java.d {
        private final com.corrodinggames.rts.appFramework.m input = new com.corrodinggames.rts.appFramework.m();
        NoInputView() { a = 1280; b = 720; }
        @Override public com.corrodinggames.rts.appFramework.m k() { return input; }
    }

    private static void require(boolean good, String reason) {
        if (!good) throw new IllegalStateException(reason);
    }

    private static String sha(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = new byte[65536];
            for (int count; (count = input.read(bytes)) >= 0; ) digest.update(bytes, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static int number(Object value) { return ((Number) value).intValue(); }

    private static int[] decode(List<?> runs, int length) {
        int[] costs = new int[length];
        int offset = 0;
        for (Object entry : runs) {
            List<?> pair = (List<?>) entry;
            require(pair.size() == 2, "RLE pair length");
            int run = number(pair.get(1));
            require(run > 0 && run <= length - offset, "RLE overrun");
            Arrays.fill(costs, offset, offset + run, number(pair.get(0)));
            offset += run;
        }
        require(offset == length, "RLE length " + offset + " != " + length);
        return costs;
    }

    private static com.corrodinggames.rts.game.i initialize(String map) throws Exception {
        android.os.Looper.a();
        l.aU = true;
        l.bb = true;
        l.aB = true;
        l.aJ = true;
        l.ck = new android.graphics.Point(1280, 720);
        // Exact, established headless bootstrap calls from HeadlessRunner; no method probing.
        Class.forName("com.corrodinggames.rts.gameFramework.a.e").getField("c").set(
                null, Class.forName("com.corrodinggames.rts.gameFramework.a.f").newInstance());
        Class.forName("com.corrodinggames.rts.gameFramework.am").getField("a").set(
                null, Class.forName("com.corrodinggames.rts.gameFramework.av").newInstance());
        com.corrodinggames.rts.game.i engine = (com.corrodinggames.rts.game.i) l.a(new ServerContext(), null);
        l.aW = true;
        engine.bQ.aiDifficulty = 0;
        engine.bQ.enableSounds = false;
        engine.bQ.musicVolume = 0;
        engine.bX.ay.d = 2;
        engine.dl = map;
        engine.a(true, com.corrodinggames.rts.gameFramework.s.b);
        engine.ap = new NoInputView();
        require(engine.bG && engine.bs != null && engine.bL != null && engine.bL.E && engine.bL.F,
                "Native match/fog initialization failed");
        require(engine.bU != null, "Native path engine missing");
        return engine;
    }

    @SuppressWarnings("unchecked")
    private static void run(String[] args) throws Exception {
        require(args.length == 3, "usage: TerrainNativeCostHarness MAP PACKET_GRID baseline|override");
        String map = args[0], mode = args[2];
        require(map.startsWith("maps/skirmish/") && !map.contains("..") && !map.contains("\\")
                && map.endsWith(".tmx"), "map must be local skirmish TMX");
        require("baseline".equals(mode) || "override".equals(mode), "unknown test mode");
        Path mapFile = Paths.get("assets", map);
        require(Files.isRegularFile(mapFile), "map file missing: " + mapFile);
        require(GAME_SHA.equals(sha(Paths.get("game-lib.jar"))), "game-lib.jar SHA mismatch");
        Map<String, Object> packet = (Map<String, Object>) Json.parse(
                new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8));
        require("TMX_ROW_MAJOR_Y_TIMES_WIDTH_PLUS_X".equals(packet.get("order")), "packet RLE order changed");
        require("x*height+y".equals(packet.get("engineIndex")), "packet engine index changed");
        if ("baseline".equals(mode))
            require(String.valueOf(packet.get("mapSha256")).equals(sha(mapFile)), "source map SHA mismatch");
        com.corrodinggames.rts.game.i engine = initialize(map);
        int width = number(packet.get("width")), height = number(packet.get("height"));
        require(width == engine.bL.C && height == engine.bL.D,
                "map dimensions differ: packet " + width + "x" + height + " engine " + engine.bL.C + "x" + engine.bL.D);
        if ("baseline".equals(mode)) {
            require(engine.bL.x == null, "baseline map unexpectedly contains PathingOverride");
        } else {
            require(width == 110 && height == 110, "override fixture dimensions changed");
            require(engine.bL.x != null, "PathingOverride layer was not bound");
            int filled = 0;
            for (int x = 0; x < width; x++) for (int y = 0; y < height; y++)
                if (engine.bL.x.a(x, y) != null) filled++;
            require(filled == 2, "override layer nonempty cells=" + filled + " expected 2");
            require(engine.bL.x.a(10, 10) != null && engine.bL.x.a(55, 55) != null,
                    "override fixture cells not instantiated");
            require(engine.bL.u.a(10, 10).e && engine.bL.e(10, 10) == null,
                    "clearing fixture must start from unoccupied water");
            require(!engine.bL.u.a(55, 55).e && engine.bL.e(55, 55) == null,
                    "blocking fixture must start from unoccupied land");
            require(engine.bL.x.a(55, 55).j == -1, "blocking override must carry native j=-1");
        }
        Map<String, Object> allRle = (Map<String, Object>) packet.get("costRle");
        int total = 0;
        for (ao type : TERRAIN_TYPES) {
            com.corrodinggames.rts.gameFramework.k.i grid = engine.bU.a(type);
            require(grid != null && grid.b == width && grid.c == height && grid.d != null
                    && grid.d.length == width * height, "missing or malformed native grid " + type.name());
            int[] expected = decode((List<?>) allRle.get(type.name()), width * height);
            if ("override".equals(mode) && type == ao.b)
                require(expected[10 * width + 10] == -1 && expected[55 * width + 55] == 0,
                        "LAND fixture baseline changed");
            int mismatches = 0;
            StringBuilder first = new StringBuilder();
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int want = expected[y * width + x];
                if ("override".equals(mode)) {
                    if (x == 10 && y == 10) want = type == ao.e ? -1 : 0;
                    if (x == 55 && y == 55) want = -1;
                }
                int got = grid.d[x * height + y];
                if (got != want) {
                    mismatches++;
                    if (mismatches <= 8) first.append(" (").append(x).append(',').append(y)
                            .append(":expected=").append(want).append(",native=").append(got).append(')');
                }
            }
            require(mismatches == 0, "terrain grid mismatch map=" + map + " movement=" + type.name()
                    + " count=" + mismatches + first);
            total += width * height;
            System.out.println("E2_GRID_PASS mode=" + mode + " map=" + map + " movement=" + type.name()
                    + " compared=" + (width * height));
        }
        if ("override".equals(mode)) {
            com.corrodinggames.rts.gameFramework.k.i land = engine.bU.a(ao.b);
            require(land.d[10 * height + 10] == 0 && land.d[55 * height + 55] == -1,
                    "LAND override positive and negative controls failed");
        }
        System.out.println("E2_TERRAIN_PASS mode=" + mode + " map=" + map + " gameSha=" + GAME_SHA
                + " comparedCells=" + total + " noTicks=true");
    }

    public static void main(String[] args) {
        try { run(args); System.exit(0); }
        catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}
