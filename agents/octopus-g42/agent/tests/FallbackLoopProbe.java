import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 输出16 §2B probe: drive the real BattleClient decision cycle against a mock bridge whose game time
 * never advances, so "one decision" can be isolated from the loop's cadence. Prints the fallback and
 * deferral events the record actually contains (no reader-side dedup).
 *
 * Usage: java -cp target/classes:dist/rw-agent-bootstrap.jar FallbackLoopProbe <jar> <credits>
 */
public class FallbackLoopProbe {
    static String state(double credits) {
        StringBuilder units = new StringBuilder();
        units.append("{\"id\":3,\"type\":\"commandCenter\",\"x\":100,\"y\":100,\"hp\":1000,\"maxHp\":1000,\"dead\":false,\"buildProgress\":1.0,\"mobile\":false,\"canAttack\":false,\"building\":true,\"techLevel\":1,\"productionQueue\":0,\"orderType\":null},");
        units.append("{\"id\":4,\"type\":\"builder\",\"x\":150,\"y\":100,\"hp\":170,\"maxHp\":170,\"dead\":false,\"buildProgress\":1.0,\"mobile\":true,\"canAttack\":false,\"building\":false,\"techLevel\":1,\"productionQueue\":-1,\"orderType\":null},");
        for (int i = 0; i < 4; i++)
            units.append("{\"id\":").append(11 + i).append(",\"type\":\"extractorT1\",\"x\":300,\"y\":400,\"hp\":600,\"maxHp\":600,\"dead\":false,\"buildProgress\":1.0,\"mobile\":false,\"canAttack\":false,\"building\":true,\"techLevel\":1,\"productionQueue\":-1,\"orderType\":null},");
        for (int i = 0; i < 4; i++)
            units.append("{\"id\":").append(20 + i).append(",\"type\":\"c_tank\",\"x\":100,\"y\":100,\"hp\":600,\"maxHp\":600,\"dead\":false,\"buildProgress\":1.0,\"mobile\":true,\"canAttack\":true,\"building\":false,\"techLevel\":1,\"productionQueue\":-1,\"orderType\":null},");
        units.setLength(units.length() - 1);
        return "{\"status\":\"running\",\"sessionId\":\"s\",\"frame\":1,\"gameTimeMs\":600000,"
                + "\"networked\":false,\"replay\":false,\"player\":{\"credits\":" + credits + "},"
                + "\"map\":{\"width\":2200,\"height\":2200},"
                + "\"match\":{\"outcome\":\"ONGOING\",\"nativeDefeat\":false,\"nativeVictory\":false,\"source\":\"native_result_screen\"},"
                + "\"ownUnits\":[" + units + "]}";
    }

    static void reply(com.sun.net.httpserver.HttpExchange exchange, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    public static void main(String[] args) throws Exception {
        String jar = args[0];
        double credits = Double.parseDouble(args[1]);
        int polls = args.length > 2 ? Integer.parseInt(args[2]) : 5;

        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        List<String> queueCalls = new ArrayList<String>();
        server.createContext("/health", e -> reply(e, "{\"status\":\"ok\",\"version\":\"0.07-alpha1\"}"));
        server.createContext("/state", e -> reply(e, state(credits)));
        server.createContext("/combat/production", e -> reply(e,
                "{\"status\":\"observed\",\"sessionId\":\"s\",\"factories\":["
                        + "{\"id\":5,\"tier\":2,\"queue\":1,\"actions\":[{\"actionId\":\"heavy\",\"type\":\"heavyTank\",\"cost\":800,\"affordable\":true}]},"
                        + "{\"id\":9,\"tier\":1,\"queue\":0,\"actions\":[{\"actionId\":\"upgrade\",\"type\":\"upgrade\",\"cost\":2000,\"affordable\":false},"
                        + "{\"actionId\":\"tank\",\"type\":\"c_tank\",\"cost\":350,\"affordable\":true}]}]}"));
        server.createContext("/economy/builder-production", e -> reply(e,
                "{\"status\":\"planned\",\"sessionId\":\"s\",\"producerId\":3,\"producerType\":\"commandCenter\","
                        + "\"queueCount\":0,\"builderCost\":500,\"builderActionAvailable\":true,\"existingBuilders\":1,\"builderOrderPending\":false}"));
        server.createContext("/combat/capabilities", e -> reply(e, "{\"status\":\"observed\",\"sessionId\":\"s\",\"units\":[],\"unavailable\":[]}"));
        server.createContext("/combat/observe", e -> reply(e, "{\"status\":\"observed\",\"sessionId\":\"s\",\"gameTimeMs\":600000,\"visibleEnemies\":[],\"rememberedEnemies\":[]}"));
        server.createContext("/scout/observe", e -> reply(e, "{\"status\":\"observed\",\"sessionId\":\"s\",\"frame\":1,\"gameTimeMs\":600000,\"fogEnabled\":true,\"lineOfSightFog\":true,\"visibleTiles\":100,\"exploredTiles\":5000,\"initialVisibleTiles\":100,\"newlyObservedTiles\":0,\"resources\":[],\"visibleThreats\":[],\"rememberedThreats\":[]}"));
        server.createContext("/command/queue", e -> {
            queueCalls.add(e.getRequestURI().toString());
            try {
                reply(e, "{\"status\":\"queued\",\"sessionId\":\"s\",\"requestId\":\"r\",\"unitId\":9,\"frame\":1,\"type\":\"c_tank\"}");
            } catch (Exception ignored) {
            }
        });
        server.createContext("/", e -> reply(e, "{\"status\":\"no_frontier\",\"sessionId\":\"s\"}"));
        server.start();

        Path cwd = Files.createTempDirectory("fallback-probe");
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder builder = new ProcessBuilder(java, "-Drwagent.pollMs=60", "-Drwagent.port=" + server.getAddress().getPort(),
                "-cp", jar, "io.rwagent.client.BattleClient", "120");
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        process.getOutputStream().close();
        StringBuilder output = new StringBuilder();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = process.getInputStream().read(buffer)) > 0) {
            output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            if (output.length() > 40000) break;
        }
        process.waitFor();
        server.stop(0);

        Path report = Files.list(cwd.resolve("rw-agent-reports"))
                .filter(p -> p.getFileName().toString().startsWith("battle-"))
                .max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        System.out.println("queue calls = " + queueCalls.size() + " " + queueCalls);
        if (report == null) {
            System.out.println("no report; child output:\n" + output);
            return;
        }
        List<String> fallbacks = new ArrayList<String>();
        List<String> deferred = new ArrayList<String>();
        for (String line : Files.readAllLines(report, StandardCharsets.UTF_8)) {
            if (line.contains("SECONDARY_INVESTMENT_FALLBACK")) fallbacks.add(line);
            if (line.contains("production_deferred")) deferred.add(line);
        }
        System.out.println("fallback events = " + fallbacks.size());
        for (String line : fallbacks) System.out.println("   " + line);
        System.out.println("deferred(secondary) events = "
                + deferred.stream().filter(l -> l.contains("SECONDARY_INVESTMENT")).count());
        for (String line : deferred) if (line.contains("SECONDARY_INVESTMENT")) System.out.println("   " + line);
        System.out.println("report = " + report);
    }
}
