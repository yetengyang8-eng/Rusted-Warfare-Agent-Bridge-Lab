package io.rwagent.client;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** External observation/action loop. Requires only the game's bundled JVM. */
public final class ControlLoop {
    private final int port = Integer.getInteger("rwagent.port", 47653);
    private BufferedWriter log;
    private FileOutputStream reportOutput;
    private String session;
    private int observations, commands, arrivals;
    private long lastFrame = -1;
    private long progressAt;
    private long startedAt;

    public static void main(String[] args) {
        int result = new ControlLoop().run(args);
        System.exit(result);
    }

    private int run(String[] args) {
        File report = null;
        String outcome = "FAIL", reason = "not started";
        int exit = 1;
        try {
            if (args.length < 1) throw new IllegalArgumentException("Usage: ControlLoop roundtrip [unitId [x y]] | record [seconds] | move unitId x y");
            String mode = args[0];
            if (!(mode.equals("roundtrip") && (args.length <= 2 || args.length == 4))
                    && !(mode.equals("record") && args.length <= 2)
                    && !(mode.equals("move") && args.length == 4))
                throw new IllegalArgumentException("Invalid command or argument count");
            File dir = new File("rw-agent-reports");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create reports directory");
            report = new File(dir, mode + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".jsonl");
            reportOutput=ReportFiles.open(report);
            log = new BufferedWriter(new OutputStreamWriter(reportOutput, StandardCharsets.UTF_8));
            System.out.println("Report: " + report.getAbsolutePath());
            System.out.println("Ctrl+C stops this client; the unit keeps its last queued order.");
            startedAt = System.nanoTime();
            String healthBody = request("GET", "/health");
            Map<String, Object> health = object(Json.parse(healthBody));
            event("health", healthBody);
            if (!"0.07-alpha1".equals(health.get("version"))) throw new IllegalStateException("Install 0.07-alpha1 and restart the game first");
            if (!mode.equals("record") && !Boolean.TRUE.equals(health.get("allowCommands")))
                throw new IllegalStateException("Commands are disabled");
            Map<String, Object> initial = observe();
            validate(initial);
            session = (String) initial.get("sessionId");
            if (mode.equals("record")) {
                int seconds = args.length == 2 ? Integer.parseInt(args[1]) : 60;
                if (seconds < 1 || seconds > 600) throw new IllegalArgumentException("Record duration: 1..600 seconds");
                while (elapsedSeconds(startedAt) < seconds) {
                    Thread.sleep(500);
                    validate(observe());
                }
                outcome = "RECORDED"; reason = "Observation capture completed; no orders issued";
            } else if (mode.equals("move")) {
                long id = Long.parseLong(args[1]);
                double x = Double.parseDouble(args[2]), y = Double.parseDouble(args[3]);
                sendMove(id, x, y);
                outcome = "QUEUED"; reason = "Order queued; arrival not checked by move mode";
            } else {
                Map<String, Object> unit = choose(initial, args.length >= 2 ? Long.valueOf(args[1]) : null);
                long id = number(unit, "id").longValue();
                double x = number(unit, "x").doubleValue(), y = number(unit, "y").doubleValue();
                Map<String, Object> map = object(initial.get("map"));
                double width = number(map, "width").doubleValue(), height = number(map, "height").doubleValue();
                if (width < 80 || height < 80) throw new IllegalStateException("Map too small for roundtrip");
                if (!inside(x, y, width, height))
                    throw new IllegalStateException("Unit position outside reported map; no test order sent. Check map dimensions.");
                boolean manual = args.length == 4;
                double tx = manual ? Double.parseDouble(args[2])
                        : Math.max(20, Math.min(width - 20, x + (x < width / 2 ? 120 : -120)));
                double ty = manual ? Double.parseDouble(args[3])
                        : Math.max(20, Math.min(height - 20, y + (y < height / 2 ? 60 : -60)));
                if (!inside(tx, ty, width, height))
                    throw new IllegalArgumentException("Test target outside map or non-finite; no test order sent");
                double plannedDistance = Math.hypot(tx - x, ty - y);
                if (plannedDistance < 40 || plannedDistance > 160)
                    throw new IllegalStateException("Roundtrip distance must be 40..160 world units; no test order sent");
                event("plan", "{\"unitId\":" + id + ",\"startX\":" + x + ",\"startY\":" + y
                        + ",\"targetX\":" + tx + ",\"targetY\":" + ty + ",\"tolerance\":20"
                        + ",\"mapWidth\":" + width + ",\"mapHeight\":" + height
                        + ",\"plannedDistance\":" + plannedDistance + ",\"manualTarget\":" + manual + "}");
                System.out.println("Map " + width + " x " + height + "; start (" + x + ", " + y
                        + ") -> target (" + tx + ", " + ty + "); distance " + plannedDistance);
                System.out.println("Unit " + id + " (" + unit.get("type") + "): outbound then return. Keep the game running.");
                sendMove(id, tx, ty);
                awaitArrival(id, tx, ty, x, y);
                sendMove(id, x, y);
                awaitArrival(id, x, y, tx, ty);
                outcome = "PASS"; reason = "Both legs arrived within 20 world units; each leg observed at least 30 world units displacement";
            }
            exit = 0;
        } catch (Exception error) {
            reason = error.toString();
            System.err.println(reason);
        } finally {
            try {
                if (log != null) {
                    event("summary", "{\"outcome\":" + Json.quote(outcome) + ",\"reason\":" + Json.quote(reason)
                            + ",\"observations\":" + observations + ",\"commands\":" + commands + ",\"arrivals\":" + arrivals + "}");
                    ReportFiles.finish(log,reportOutput,report);
                }
            } catch (Exception error) { exit = 1; System.err.println("Report write failed: " + error); }
        }
        System.out.println(outcome + ": " + reason);
        if (report != null) System.out.println("Send back: " + report.getAbsolutePath());
        return exit;
    }

    private void awaitArrival(long id, double tx, double ty, double fromX, double fromY) throws Exception {
        long legStart = System.nanoTime();
        boolean displaced = false;
        int nearSamples = 0;
        while (elapsedSeconds(legStart) < 45) {
            Thread.sleep(500);
            Map<String, Object> state = observe(); validate(state);
            Map<String, Object> unit = choose(state, Long.valueOf(id));
            double x = number(unit, "x").doubleValue(), y = number(unit, "y").doubleValue();
            displaced |= Math.hypot(x - fromX, y - fromY) >= 30;
            double distance = Math.hypot(x - tx, y - ty);
            nearSamples = distance <= 20 && displaced ? nearSamples + 1 : 0;
            if (nearSamples >= 2) {
                arrivals++;
                event("arrival", "{\"unitId\":" + id + ",\"x\":" + x + ",\"y\":" + y + ",\"distance\":" + distance + "}");
                System.out.println("Arrival " + arrivals + "/2 confirmed"); return;
            }
        }
        throw new IllegalStateException("Arrival timeout (45s): target may be blocked, unreachable, or order overridden");
    }

    private void validate(Map<String, Object> state) {
        if (!"running".equals(state.get("status"))) throw new IllegalStateException("No active match");
        if (Boolean.TRUE.equals(state.get("networked")) || Boolean.TRUE.equals(state.get("replay")))
            throw new IllegalStateException("Use a local single-player match, not network/replay");
        if (state.get("player") == null) throw new IllegalStateException("No local player");
        if (session != null && !session.equals(state.get("sessionId"))) throw new IllegalStateException("Match/session changed");
        long frame = number(state, "frame").longValue();
        if (lastFrame >= 0 && frame < lastFrame) throw new IllegalStateException("Simulation frame went backwards");
        if (lastFrame != frame) { progressAt = System.nanoTime(); lastFrame = frame; }
        else if (elapsedSeconds(progressAt) > 10) throw new IllegalStateException("Simulation did not advance for 10s; resume/unpause the game");
    }

    private Map<String, Object> observe() throws Exception {
        String raw = request("GET", "/state");
        Map<String, Object> state = object(Json.parse(raw));
        observations++; event("observation", raw); return state;
    }
    private void sendMove(long id, double x, double y) throws Exception {
        if (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(y) || Double.isInfinite(y))
            throw new IllegalArgumentException("Coordinates must be finite");
        String requestId = UUID.randomUUID().toString();
        String path = "/command/move?unitId=" + id + "&x=" + x + "&y=" + y + "&sessionId=" + session + "&requestId=" + requestId;
        event("action", "{\"unitId\":" + id + ",\"x\":" + x + ",\"y\":" + y + ",\"requestId\":" + Json.quote(requestId) + "}");
        String response = request("POST", path);
        Map<String, Object> result = object(Json.parse(response));
        event("command_result", response);
        if (!"queued".equals(result.get("status"))) throw new IllegalStateException("Command was not queued");
        commands++;
    }
    private String request(String method, String path) throws Exception {
        AgentClient.Response r = AgentClient.request(method, "http://127.0.0.1:" + port + path);
        if (r.status != 200) {
            event("http_error", "{\"status\":" + r.status + ",\"body\":" + Json.quote(r.body) + "}");
            throw new IllegalStateException("HTTP " + r.status + ": " + r.body);
        }
        return r.body;
    }
    private void event(String type, String rawJson) throws Exception {
        log.write("{\"wallTimeMs\":" + System.currentTimeMillis() + ",\"event\":" + Json.quote(type) + ",\"data\":" + rawJson + "}");
        log.newLine(); log.flush();
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String, Object>) value;
    }
    private static Number number(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Missing numeric field: " + key);
        if (Double.isNaN(((Number)value).doubleValue()) || Double.isInfinite(((Number)value).doubleValue()))
            throw new IllegalArgumentException("Non-finite numeric field: " + key);
        return (Number) value;
    }
    private static boolean inside(double x, double y, double width, double height) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }
    private static Map<String, Object> choose(Map<String, Object> state, Long id) {
        Object units = state.get("ownUnits");
        if (!(units instanceof List)) throw new IllegalArgumentException("Missing ownUnits");
        for (Object raw : (List<?>) units) {
            Map<String, Object> unit = object(raw);
            if ((id == null || number(unit, "id").longValue() == id.longValue())
                    && Boolean.TRUE.equals(unit.get("mobile")) && !Boolean.TRUE.equals(unit.get("dead"))) return unit;
        }
        throw new IllegalStateException("Own movable unit unavailable, dead, or transported: " + id);
    }
    private static double elapsedSeconds(long start) { return (System.nanoTime() - start) / 1e9; }
}
