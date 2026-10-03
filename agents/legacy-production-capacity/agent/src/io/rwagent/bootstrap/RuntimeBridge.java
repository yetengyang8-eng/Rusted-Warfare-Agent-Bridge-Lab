package io.rwagent.bootstrap;

import com.corrodinggames.rts.game.n;
import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.y;
import com.corrodinggames.rts.gameFramework.e;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.net.URLDecoder;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class RuntimeBridge {
    private static RuntimeBridge instance;

    final com.corrodinggames.rts.game.i engine;
    ScoutBridge scout;
    private final int port;
    private final boolean allowCommands;
    private HttpServer server;
    // Accessed only on the game thread.
    private Object previousMap, previousPlayer;
    private boolean previousLoaded;
    private int previousFrame = -1;
    String sessionId = UUID.randomUUID().toString();
    private final Map<String, CachedMove> recentMoves = new LinkedHashMap<String, CachedMove>();
    private volatile String provenanceCache;
    private volatile String gameJarSha256Cache;

    String gameJarSha256() {
        String cached = gameJarSha256Cache;
        if (cached == null) {
            cached = sha256Of(codeSourcePath(engine == null ? null : engine.getClass()));
            if (cached != null) gameJarSha256Cache = cached;
        }
        return cached;
    }

    /**
     * Provenance of the process that is actually running, so any report can prove which version,
     * which agent JAR and which game JAR produced it. Read-only: never orders, reads fog or terrain.
     */
    private String provenanceJson() {
        String cached = provenanceCache;
        if (cached != null) {
            return cached;
        }
        String agentJar = codeSourcePath(RwAgent.class);
        String gameJar = codeSourcePath(engine == null ? null : engine.getClass());
        StringBuilder json = new StringBuilder(384);
        json.append("{\"agentVersion\":\"0.07-alpha1\"");
        json.append(",\"agentJar\":").append(jsonStringOrNull(agentJar));
        json.append(",\"agentJarSha256\":").append(jsonStringOrNull(sha256Of(agentJar)));
        json.append(",\"gameLibJar\":").append(jsonStringOrNull(gameJar));
        json.append(",\"gameLibJarSha256\":").append(jsonStringOrNull(gameJarSha256()));
        json.append(",\"workingDirectory\":").append(jsonStringOrNull(System.getProperty("user.dir")));
        json.append(",\"reportDirectory\":")
                .append(jsonStringOrNull(new File("rw-agent-reports").getAbsolutePath()));
        json.append(",\"osName\":").append(jsonStringOrNull(System.getProperty("os.name")));
        json.append(",\"javaVersion\":").append(jsonStringOrNull(System.getProperty("java.version")));
        json.append('}');
        cached = json.toString();
        provenanceCache = cached;
        return cached;
    }

    private static String codeSourcePath(Class<?> type) {
        if (type == null) {
            return null;
        }
        try {
            java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            return new File(source.getLocation().toURI()).getAbsolutePath();
        } catch (Throwable error) {
            return null;
        }
    }

    private static String sha256Of(String path) {
        if (path == null) {
            return null;
        }
        File file = new File(path);
        if (!file.isFile()) {
            return null;
        }
        FileInputStream input = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            input = new FileInputStream(file);
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (Throwable error) {
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // Provenance must never break a request.
                }
            }
        }
    }

    private static String jsonStringOrNull(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    void refreshSession() {
        if (previousMap != engine.bL || previousPlayer != engine.bs
                || previousLoaded != engine.bG || engine.bx < previousFrame) {
            sessionId = UUID.randomUUID().toString();
            recentMoves.clear();
        }
        previousMap = engine.bL;
        previousPlayer = engine.bs;
        previousLoaded = engine.bG;
        previousFrame = engine.bx;
    }


    private RuntimeBridge(
            com.corrodinggames.rts.game.i engine,
            int port,
            boolean allowCommands) {
        this.engine = engine;
        this.port = port;
        this.allowCommands = allowCommands;
    }

    public static synchronized void start(
            com.corrodinggames.rts.game.i engine,
            int port,
            boolean allowCommands) throws IOException {
        if (instance != null) {
            return;
        }
        RuntimeBridge bridge = new RuntimeBridge(engine, port, allowCommands);
        bridge.startServer();
        instance = bridge;
        RwAgent.log("Provenance " + bridge.provenanceJson());
    }

    private void startServer() throws IOException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port);
        server = HttpServer.create(address, 0);
        server.createContext("/health", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respond(exchange, 405, jsonError("GET required"));
                    return;
                }
                respond(exchange, 200,
                        "{\"status\":\"ok\",\"version\":\"0.07-alpha1\",\"port\":"
                                + port + ",\"strategyContractVersion\":1,\"allowCommands\":" + allowCommands
                                + ",\"provenance\":" + provenanceJson() + "}");
            }
        });
        server.createContext("/state", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respond(exchange, 405, jsonError("GET required"));
                    return;
                }
                try {
                    respond(exchange, 200, onGameThread(new Callable<String>() {
                        @Override
                        public String call() {
                            return snapshotJson();
                        }
                    }));
                } catch (Exception error) {
                    RwAgent.log("State request failed", error);
                    respond(exchange, 503, jsonError(error.toString()));
                }
            }
        });
        server.createContext("/test/move-first", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respond(exchange, 405, jsonError("POST required"));
                    return;
                }
                try {
                    CommandResult result = onGameThread(new Callable<CommandResult>() {
                        @Override
                        public CommandResult call() {
                            return moveFirstOwnMobileUnit();
                        }
                    });
                    respond(exchange, result.httpStatus, result.json);
                } catch (Exception error) {
                    RwAgent.log("Move test failed", error);
                    respond(exchange, 503, jsonError(error.toString()));
                }
            }
        });

        server.createContext("/command/move", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (!"/command/move".equals(exchange.getRequestURI().getPath())) {
                    respond(exchange, 404, jsonError("unknown endpoint"));
                    return;
                }
                if (!"POST".equals(exchange.getRequestMethod())) {
                    respond(exchange, 405, jsonError("POST required"));
                    return;
                }
                if (exchange.getRequestHeaders().getFirst("Origin") != null) {
                    respond(exchange, 403, jsonError("browser-origin commands are disabled"));
                    return;
                }
                try {
                    final MoveRequest request = MoveRequest.parse(exchange.getRequestURI().getRawQuery());
                    CommandResult result = onGameThread(new Callable<CommandResult>() {
                        @Override public CommandResult call() { return move(request); }
                    });
                    respond(exchange, result.httpStatus, result.json);
                } catch (IllegalArgumentException error) {
                    respond(exchange, 400, jsonError(error.getMessage()));
                } catch (Exception error) {
                    RwAgent.log("Move request failed", error);
                    respond(exchange, 503, jsonError("result unknown; observe before issuing another command"));
                }
            }
        });

        new EconomyBridge(this).install(server);
        scout=new ScoutBridge(this);scout.install(server);
        new GuardBridge(this).install(server);
        new CombatBridge(this).install(server);

        ExecutorService executor = Executors.newFixedThreadPool(4, new ThreadFactory() {
            private int nextId;

            @Override
            public synchronized Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "rw-agent-http-" + (++nextId));
                thread.setDaemon(true);
                return thread;
            }
        });
        server.setExecutor(executor);
        server.start();
        RwAgent.log("Local Agent API ready at http://127.0.0.1:" + port);
    }

    <T> T onGameThread(Callable<T> callable)
            throws InterruptedException, ExecutionException, TimeoutException {
        FutureTask<T> task = new FutureTask<T>(callable);
        engine.k.add(task);
        try {
            return task.get(5L, TimeUnit.SECONDS);
        } catch (TimeoutException error) {
            task.cancel(false);
            engine.k.remove(task);
            throw error;
        } catch (InterruptedException error) {
            task.cancel(false);
            engine.k.remove(task);
            Thread.currentThread().interrupt();
            throw error;
        }
    }

    private String snapshotJson() {
        refreshSession();
        n player = engine.bs;
        boolean loaded = engine.bG;
        boolean networked = engine.bX != null && engine.bX.B;
        boolean replay = engine.cb != null && engine.cb.j();

        StringBuilder json = new StringBuilder(4096);
        json.append('{');
        json.append("\"status\":\"").append(loaded ? "running" : "menu").append('\"');
        json.append(",\"schemaVersion\":1");
        json.append(",\"sessionId\":\"").append(sessionId).append('"');
        json.append(",\"map\":{\"width\":").append(format(mapWidth()))
                .append(",\"height\":").append(format(mapHeight()))
                .append(",\"tilesWide\":").append(engine.bL == null ? 0 : engine.bL.C)
                .append(",\"tilesHigh\":").append(engine.bL == null ? 0 : engine.bL.D)
                .append(",\"tileWidth\":").append(engine.bL == null ? 0 : engine.bL.n)
                .append(",\"tileHeight\":").append(engine.bL == null ? 0 : engine.bL.o).append('}');
        json.append(",\"frame\":").append(engine.bx);
        json.append(",\"gameTimeMs\":").append(engine.by);
        json.append(",\"networked\":").append(networked);
        json.append(",\"replay\":").append(replay);
        if (player == null || !loaded) {
            json.append(",\"player\":null,\"ownUnits\":[]}");
            return json.toString();
        }

        json.append(",\"player\":{");
        json.append("\"teamId\":").append(player.k);
        json.append(",\"name\":\"").append(escape(player.v)).append('\"');
        json.append(",\"credits\":").append(format(player.o));
        json.append('}');
        json.append(",\"match\":").append(CombatBridge.matchJson(this));
        json.append(",\"ownUnits\":[");

        am[] units = am.bE.a();
        int size = am.bE.size();
        boolean first = true;
        for (int index = 0; index < size; index++) {
            am unit = units[index];
            if (unit == null || unit.bX != player || unit.ej) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            boolean mobile = movable(unit);
            String type;
            try {
                type = unit.r() == null ? "<unknown>" : unit.r().i();
            } catch (Throwable ignored) {
                type = unit.getClass().getName();
            }
            json.append('{');
            json.append("\"id\":").append(unit.eh);
            json.append(",\"type\":\"").append(escape(type)).append('\"');
            json.append(",\"x\":").append(format(unit.eo));
            json.append(",\"y\":").append(format(unit.ep));
            json.append(",\"hp\":").append(format(unit.cu));
            json.append(",\"maxHp\":").append(format(unit.cv));
            json.append(",\"buildProgress\":").append(Float.toString(unit.cm));
            json.append(",\"productionQueue\":").append(EconomyBridge.queueCount(unit));
            json.append(",\"dead\":").append(unit.bV);
            json.append(",\"mobile\":").append(mobile);
            json.append(",\"canAttack\":").append(unit.l());
            json.append(",\"building\":").append(unit.bI());
            json.append(",\"techLevel\":").append(unit.V());
            com.corrodinggames.rts.game.units.au order=unit instanceof y?((y)unit).ar():null;
            json.append(",\"orderType\":").append(order==null?"null":"\""+escape(order.d().name())+"\"");
            if(order!=null && ("attackMove".equals(order.d().name()) || "move".equals(order.d().name()))) {
                json.append(",\"orderX\":").append(format(order.g())).append(",\"orderY\":").append(format(order.h()));
            }
            am guardTarget=order!=null && "guard".equals(order.d().name())?order.i():null;
            json.append(",\"guardTargetId\":").append(guardTarget!=null && guardTarget.bX==player?Long.toString(guardTarget.eh):"null");
            json.append('}');
        }
        json.append("]}");
        return json.toString();
    }

    private CommandResult moveFirstOwnMobileUnit() {
        refreshSession();
        CommandResult guard = commandGuard();
        if (guard != null) return guard;
        n player = engine.bs;

        y selected = null;
        am[] units = am.bE.a();
        int size = am.bE.size();
        for (int index = 0; index < size; index++) {
            am unit = units[index];
            if (unit instanceof y
                    && unit.bX == player
                    && !unit.bV
                    && !unit.ej
                    && !unit.cW()
                    && ((y) unit).I()) {
                selected = (y) unit;
                break;
            }
        }
        if (selected == null) {
            return CommandResult.error(409, "no movable own unit was found");
        }

        float mapWidth = mapWidth();
        float mapHeight = mapHeight();
        if (!(selected.eo >= 0 && selected.ep >= 0
                && selected.eo < mapWidth && selected.ep < mapHeight)) {
            return CommandResult.error(409, "unit position is inconsistent with map dimensions; no test move sent");
        }
        float targetX = selected.eo + (mapWidth <= 0.0f || selected.eo < mapWidth / 2.0f
                ? 120.0f : -120.0f);
        float targetY = selected.ep + (mapHeight <= 0.0f || selected.ep < mapHeight / 2.0f
                ? 60.0f : -60.0f);
        if (mapWidth > 0.0f) {
            targetX = clamp(targetX, 20.0f, Math.max(20.0f, mapWidth - 20.0f));
        }
        if (mapHeight > 0.0f) {
            targetY = clamp(targetY, 20.0f, Math.max(20.0f, mapHeight - 20.0f));
        }
        if (Math.hypot(targetX - selected.eo, targetY - selected.ep) > 160.0) {
            return CommandResult.error(409, "test move exceeds 160 world units; no test move sent");
        }

        return move(new MoveRequest(selected.eh, targetX, targetY, sessionId,
                UUID.randomUUID().toString()));
    }

    // Native i()/j() return C*n and D*o. p/q are HALF-TILE OFFSETS, not map counts.
    private float mapWidth() { return engine.bL == null ? 0f : engine.bL.i(); }
    private float mapHeight() { return engine.bL == null ? 0f : engine.bL.j(); }

    private static boolean movable(am unit) {
        return unit instanceof y && !unit.bV && !unit.ej && !unit.cW() && ((y) unit).I();
    }

    CommandResult commandGuard() {
        if (!allowCommands) return CommandResult.error(403, "commands are disabled");
        if (!engine.bG) return CommandResult.error(409, "no level is currently loaded");
        if (engine.bX != null && engine.bX.B)
            return CommandResult.error(409, "network games are not supported");
        if (engine.cb != null && engine.cb.j())
            return CommandResult.error(409, "replay commands are not supported");
        if (engine.bs == null || engine.bs.k < 0)
            return CommandResult.error(409, "no controllable local player was found");
        if (engine.cf == null) return CommandResult.error(409, "command controller is unavailable");
        return null;
    }

    private CommandResult move(MoveRequest request) {
        refreshSession();
        CommandResult guard = commandGuard();
        if (guard != null) return guard;
        if (!sessionId.equals(request.session))
            return CommandResult.error(409, "session changed; read state again");
        CachedMove cached = recentMoves.get(request.requestId);
        if (cached != null) {
            if (!cached.request.sameMove(request))
                return CommandResult.error(409, "requestId reused with different coordinates or unit");
            return cached.result;
        }
        float width = mapWidth(), height = mapHeight();
        if (!(width > 0 && height > 0)) return CommandResult.error(409, "map dimensions unavailable");
        if (request.x < 0 || request.y < 0 || request.x >= width || request.y >= height)
            return CommandResult.error(400, "target is outside map bounds");
        y selected = null;
        am[] units = am.bE.a();
        for (int i = 0; i < am.bE.size(); i++) {
            am unit = units[i];
            // Do not reveal whether a foreign unit ID exists.
            if (unit != null && unit.eh == request.unitId && unit.bX == engine.bs && movable(unit)) {
                selected = (y) unit;
                break;
            }
        }
        if (selected == null) return CommandResult.error(409, "controllable own mobile unit not found");
        e command = engine.cf.b(engine.bs);
        command.a(selected);
        command.a(request.x, request.y);
        CommandResult result = CommandResult.ok("{\"status\":\"queued\",\"requestId\":\""
                + escape(request.requestId) + "\",\"sessionId\":\"" + sessionId
                + "\",\"frame\":" + engine.bx + ",\"unitId\":" + selected.eh
                + ",\"targetX\":" + format(request.x) + ",\"targetY\":" + format(request.y) + "}");
        recentMoves.put(request.requestId, new CachedMove(request, result));
        if (recentMoves.size() > 256) recentMoves.remove(recentMoves.keySet().iterator().next());
        RwAgent.log("Queued move: request=" + request.requestId + " unit=" + selected.eh
                + " target=(" + request.x + "," + request.y + ") frame=" + engine.bx);
        return result;
    }

    private static final class CachedMove {
        final MoveRequest request;
        final CommandResult result;
        CachedMove(MoveRequest request, CommandResult result) { this.request = request; this.result = result; }
    }

    private static final class MoveRequest {
        final long unitId;
        final float x, y;
        final String session, requestId;
        MoveRequest(long unitId, float x, float y, String session, String requestId) {
            this.unitId = unitId; this.x = x; this.y = y; this.session = session; this.requestId = requestId;
        }
        boolean sameMove(MoveRequest other) {
            return unitId == other.unitId && Float.compare(x, other.x) == 0 && Float.compare(y, other.y) == 0;
        }
        static MoveRequest parse(String raw) {
            if (raw == null || raw.length() > 1024) throw new IllegalArgumentException("invalid query");
            Map<String, String> fields = new LinkedHashMap<String, String>();
            for (String pair : raw.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length != 2) throw new IllegalArgumentException("invalid query field");
                try {
                    String key = URLDecoder.decode(parts[0], "UTF-8");
                    String value = URLDecoder.decode(parts[1], "UTF-8");
                    if (fields.put(key, value) != null) throw new IllegalArgumentException("duplicate query field");
                } catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
            }
            if (fields.size() != 5 || !fields.keySet().containsAll(
                    java.util.Arrays.asList("unitId", "x", "y", "sessionId", "requestId")))
                throw new IllegalArgumentException("required: unitId, x, y, sessionId, requestId");
            try {
                long id = Long.parseLong(fields.get("unitId"));
                float x = Float.parseFloat(fields.get("x")), y = Float.parseFloat(fields.get("y"));
                if (Float.isNaN(x) || Float.isInfinite(x) || Float.isNaN(y) || Float.isInfinite(y))
                    throw new IllegalArgumentException("coordinates must be finite");
                String session = fields.get("sessionId"), request = fields.get("requestId");
                if (!session.matches("[a-zA-Z0-9-]{1,64}") || !request.matches("[a-zA-Z0-9_-]{1,64}"))
                    throw new IllegalArgumentException("invalid sessionId or requestId");
                return new MoveRequest(id, x, y, session, request);
            } catch (NumberFormatException error) { throw new IllegalArgumentException("invalid unitId or coordinates"); }
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static String format(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return "null";
        }
        return String.format(Locale.US, "%.3f", value);
    }

    static String format(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "null";
        }
        return String.format(Locale.US, "%.3f", value);
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.US, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.toString();
    }

    static String jsonError(String message) {
        return "{\"status\":\"error\",\"message\":\"" + escape(message) + "\"}";
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream output = exchange.getResponseBody();
        try {
            output.write(bytes);
        } finally {
            output.close();
            exchange.close();
        }
    }

    static final class CommandResult {
        final int httpStatus;
        final String json;

        CommandResult(int httpStatus, String json) {
            this.httpStatus = httpStatus;
            this.json = json;
        }

        static CommandResult ok(String json) {
            return new CommandResult(200, json);
        }

        static CommandResult error(int status, String message) {
            return new CommandResult(status, jsonError(message));
        }
    }
}
