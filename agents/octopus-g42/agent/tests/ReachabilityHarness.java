import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;
import com.corrodinggames.rts.game.units.y;
import java.util.*;

/**
 * 输出21 §13.6: plumbing and raw-shape regression for the diagnostics-only /combat/reachability v0.
 *
 * This harness proves the endpoint exists, validates its arguments, refuses unknown units, and returns a
 * raw-only payload whose fields are all present and clearly marked raw. It deliberately does NOT assert a
 * semantic value for engineAqAResult / engineAqBResult / exactTargetPathRawResult: 输出21 §3 forbids
 * collapsing targetable / path-to-coordinate / reachable-engagement-position into one boolean before the
 * live calibration of 输出21 §4 has run.
 */
public final class ReachabilityHarness {
    static void require(boolean b, String why) { BridgeHarness.require(b, why); }
    static String check(String method, String path, int status, String contains) throws Exception {
        return BridgeHarness.check(method, path, status, contains);
    }
    /** Test-local copy of the bridge's notation, so the test does not read the code under test. */
    static String parametricDescriptor(java.lang.reflect.Method m) {
        StringBuilder s = new StringBuilder("(");
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) s.append(i > 0 ? "," : "").append(params[i].getName());
        return s.append(")->").append(m.getReturnType().getName()).toString();
    }
    static Class<?> classOrNull(String name) {
        try { return Class.forName(name); } catch (Throwable missing) { return null; }
    }

    public static void main(String[] args) throws Exception {
        try {
            EconomyHarness.test();
            com.corrodinggames.rts.game.i engine = EconomyHarness.engine;
            String session = (String) EconomyHarness.obj(check("GET", "/state", 200, "sessionId")).get("sessionId");
            am builder = EconomyHarness.builder;
            require(builder != null, "fixture builder available");

            // A visible enemy target so the endpoint has something real to point at.
            ar centre = ar.valueOf("commandCenter");
            centre.h();
            am enemy = centre.a(true);
            am.bF.put(centre, enemy);
            enemy.eh = 771; enemy.bX = new com.corrodinggames.rts.game.e(1, false);
            enemy.cm = 1; enemy.eo = 1200; enemy.ep = 1700;
            am.bE.add(enemy);

            // An armed own unit as the attacker (the builder is unarmed, so use a tank type). The tank
            // constructor needs the same graphics stub CombatHarness installs.
            EconomyHarness.set(Class.forName("com.corrodinggames.rts.game.units.e.n"), "b",
                    BridgeHarness.construct("com.corrodinggames.rts.gameFramework.m.e"));
            ar tankType = ar.valueOf("tank");
            tankType.h();
            y tank = (y) tankType.a(true);
            am.bF.put(tankType, tank);
            tank.eh = 772; tank.bX = engine.bs; tank.cm = 1; tank.eo = 941; tank.ep = 1781;
            am.bE.add(tank);

            System.out.println("== plumbing ==");
            check("GET", "/combat/reachability", 400, "required");
            check("GET", "/combat/reachability?unitId=772", 400, "required");
            check("GET", "/combat/reachability?unitId=abc&targetId=771", 400, "numeric");
            check("GET", "/combat/reachability?unitId=999&targetId=771", 400, "own armed mobile");
            check("GET", "/combat/reachability?unitId=772&targetId=999", 400, "target unit not found");
            check("POST", "/combat/reachability?unitId=772&targetId=771", 405, "GET required");
            check("GET", "/combat/reachability?unitId=772&targetId=771&extra=1", 400, "required");

            System.out.println("== raw payload shape ==");
            String body = check("GET", "/combat/reachability?unitId=772&targetId=771", 200, "diagnosticStatus");
            Map<?, ?> raw = EconomyHarness.obj(body);
            boolean armed = Boolean.parseBoolean(System.getProperty("rwagent.reachabilityDiagnostics", "false"));
            System.out.println("   diagnostics armed: " + armed);
            require("raw".equals(raw.get("status")), "the endpoint declares itself raw, not a verdict");
            require("RAW_ONLY_NOT_A_VERDICT".equals(raw.get("diagnosticStatus")),
                    "the payload carries an explicit raw-only marker");
            require("output21-raw-reachability-v0".equals(raw.get("concept")), "the payload names its concept");
            for (String field : new String[]{"attackerId", "attackerType", "attackerMovementClass",
                    "attackerMovementClassSource", "attackerPosition", "attackerTile", "targetId",
                    "targetType", "targetMovementClass", "targetMovementClassSource", "targetBuilding",
                    "targetPosition", "targetTile", "engineAqAResult", "engineAqBResult",
                    "exactTargetPathRawResult", "weaponRangeRaw", "collisionRadiusRaw", "targetRadiusRaw",
                    "targetFootprintRaw", "movementGridClass", "rawPassabilitySamples", "twoArgCandidates",
                    "outerCandidates"}) {
                require(raw.containsKey(field), "raw payload includes " + field);
            }
            Map<?, ?> radius = (Map<?, ?>) raw.get("collisionRadiusRaw");
            require("cj".equals(radius.get("field")), "the collision radius names its source field (cj)");
            require(radius.containsKey("attacker") && radius.containsKey("target"),
                    "the collision radius is reported for both units");
            require(!body.contains("engagementRadius") && !body.contains("effectiveTargetRadius"),
                    "the collision radius is not renamed into an engagement radius");
            require(!((List<?>) raw.get("rawPassabilitySamples")).isEmpty() == (armed && body.contains("B_MOVEMENT_CLASS_AND_PASSABILITY")),
                    armed ? "the sandbox run may read passability when group B is requested"
                          : "the live-safe default reads no passability");
            if (!armed) {
                require(raw.get("attackerMovementClass") == null
                                || "null".equals(String.valueOf(raw.get("attackerMovementClass"))),
                        "the live-safe default reports no movement class instead of calling the engine");
                require("GROUP_B_DISABLED".equals(raw.get("attackerMovementClassSource")),
                        "the live-safe default says why the movement class is missing");
                require(!body.contains("B_MOVEMENT_CLASS_AND_PASSABILITY"),
                        "the live-safe default does not enable group B");
            }
            if (armed) {
                String withB = check("GET", "/combat/reachability?unitId=772&targetId=771&groups=B", 200,
                        "B_MOVEMENT_CLASS_AND_PASSABILITY");
                Map<?, ?> withBMap = EconomyHarness.obj(withB);
                require(!((List<?>) withBMap.get("rawPassabilitySamples")).isEmpty(),
                        "the sandbox passability group returns samples");
                Map<?, ?> sample = (Map<?, ?>) ((List<?>) withBMap.get("rawPassabilitySamples")).get(0);
                for (String field : new String[]{"where", "tileX", "tileY", "globalPassable", "classPassable",
                        "gridMoveCost"}) {
                    require(sample.containsKey(field), "passability sample includes " + field);
                }
                // The guard summary must cover this very response. It is serialized last for exactly that
                // reason: while it was emitted before the passability samples, it reported the refusals as
                // they stood before the refusals in its own body had happened.
                require(withB.indexOf("reflectionGuards") > withB.indexOf("rawPassabilitySamples"),
                        "the guard summary is serialized after the calls it summarises");
                int skippedInBody = 0;
                for (int at = withB.indexOf("SKIPPED_NOT_ALLOWLISTED"); at >= 0;
                        at = withB.indexOf("SKIPPED_NOT_ALLOWLISTED", at + 1)) {
                    skippedInBody++;
                }
                Map<?, ?> guardsWithB = (Map<?, ?>) withBMap.get("reflectionGuards");
                require(((Number) guardsWithB.get("notAllowedSkipped")).intValue() >= skippedInBody,
                        "the refusal count covers every refusal in this response (" + skippedInBody + ")");
            } else {
                require(((List<?>) raw.get("rawPassabilitySamples")).isEmpty(),
                        "no passability sample is produced by the live-safe default");
            }
            require(raw.get("attackerPosition") instanceof List, "attacker position is a pair");
            require(((Number) raw.get("attackerTile")).longValue() > 0, "attacker tile is computed");

            System.out.println("== 输出24 §P0: banned groups are off by default and cannot be enabled live ==");
            require(raw.containsKey("groupsEnabled") && raw.containsKey("bannedGroups"),
                    "the payload states which groups ran and which are banned");
            require("true".equals(String.valueOf(raw.get("liveMatchSafe"))) == !armed,
                    "the payload states whether the bridge is live-match safe");
            require(((List<?>) raw.get("bannedGroups")).size() == 3, "groups C, D and E are listed as banned");
            require(((List<?>) raw.get("outerCandidates")).isEmpty(),
                    "the outer query (group C) does not run without its group");
            require("BANNED_GROUP_D_DISABLED".equals(String.valueOf(raw.get("engineAqAResult"))),
                    "aq.a(y,am) is reported as banned, not called");
            require("BANNED_GROUP_E_DISABLED".equals(String.valueOf(raw.get("engineAqBResult"))),
                    "aq.b(y,am) is reported as banned, not called");
            require("BANNED_GROUP_C_DISABLED".equals(String.valueOf(raw.get("exactTargetPathRawResult"))),
                    "the exact path query is reported as banned, not called");
            if (armed) {
                check("GET", "/combat/reachability?unitId=772&targetId=771&groups=D", 200, "D_AQ_A_Y_AM");
                check("GET", "/combat/reachability?unitId=772&targetId=771&groups=C,E", 200, "enginePatternMode0Args");
                check("GET", "/combat/reachability?unitId=772&targetId=771&groups=Z", 400, "unknown reachability group");
            } else {
                check("GET", "/combat/reachability?unitId=772&targetId=771&groups=D", 400, "disabled during a live match");
                check("GET", "/combat/reachability?unitId=772&targetId=771&groups=C,E", 400, "disabled during a live match");
                check("GET", "/combat/reachability?unitId=772&targetId=771&groups=Z", 400, "unknown reachability group");
            }
            check("GET", "/combat/reachability?unitId=772&targetId=771&groups=A", 200, "groupsEnabled");

            System.out.println("== full float dump is opt-in and per request ==");
            String withDump = check("GET", "/combat/reachability?unitId=772&targetId=771&dump=full", 200,
                    "fullFloatDump");
            require(withDump.contains("\"fullFloatDump\""), "dump=full returns the full float dump");
            require(!body.contains("\"fullFloatDump\""), "the default response omits the full dump");
            check("GET", "/combat/reachability?unitId=772&targetId=771&unknown=1", 400, "required");

            System.out.println("== Astra 对话29 §5: the allowlist is compared, not name-matched ==");
            // The invariant is checked against the REAL allowlist and the REAL engine classes, so it covers
            // same-name overloads wherever the engine happens to declare them: a method may be admitted only
            // when its full parametric descriptor is present on an audited owner in its own superclass chain.
            Class<?> bridge = Class.forName("io.rwagent.bootstrap.CombatBridge");
            java.lang.reflect.Field allowField = bridge.getDeclaredField("ALLOWED_ENGINE_CALLS");
            allowField.setAccessible(true);
            Map<?, ?> allowlist = (Map<?, ?>) allowField.get(null);
            require(!allowlist.isEmpty(), "the allowlist is non-empty");
            java.lang.reflect.Method isAllowlisted = bridge.getDeclaredMethod("isAllowlisted", java.lang.reflect.Method.class);
            isAllowlisted.setAccessible(true);
            int entries = 0, overloadsSeen = 0, rejected = 0;
            for (Object key : allowlist.keySet()) {
                Map<?, ?> descriptors = (Map<?, ?>) allowlist.get(key);
                String name = String.valueOf(key).substring(String.valueOf(key).indexOf('#') + 1);
                Class<?> owner = Class.forName(String.valueOf(key).substring(0, String.valueOf(key).indexOf('#')));
                for (java.lang.reflect.Method m : owner.getDeclaredMethods()) {
                    if (!m.getName().equals(name) || !java.lang.reflect.Modifier.isPublic(m.getModifiers())
                            || m.isSynthetic()) continue;
                    boolean admitted = (Boolean) isAllowlisted.invoke(null, m);
                    if (descriptors.containsKey(parametricDescriptor(m))) {
                        entries++;
                        require(admitted, "allowlisted signature is admitted: " + key + " " + parametricDescriptor(m));
                    } else {
                        // a same-name sibling whose descriptor was NOT audited must be refused
                        overloadsSeen++;
                        if (!admitted) rejected++;
                    }
                }
            }
            require(entries > 0, "at least one real engine method passes the allowlist");
            require(rejected == overloadsSeen,
                    "every non-audited same-name overload is refused (" + rejected + "/" + overloadsSeen + ")");
            // Named negative controls: the three classes of call the project has permanently banned.
            Class<?> grid = classOrNull("com.corrodinggames.rts.gameFramework.k.i");
            Class<?> move = classOrNull("com.corrodinggames.rts.gameFramework.l.o");
            Object[][] negatives = new Object[][]{
                    {"com.corrodinggames.rts.game.units.am", "cj", new Class<?>[0], "void mutator (body: cu = -1)"},
                    {"com.corrodinggames.rts.game.units.ar", "a", new Class<?>[0], "unit factory accessor"},
                    {"com.corrodinggames.rts.gameFramework.k.l", "a", move == null ? null : new Class<?>[]{move},
                            "pathfinder accessor whose body writes the grid"},
                    {"com.corrodinggames.rts.gameFramework.k.l", "b",
                            grid == null ? null : new Class<?>[]{grid, int.class, int.class}, "un-audited owner"}};
            int resolved = 0;
            for (Object[] negative : negatives) {
                Class<?>[] params = (Class<?>[]) negative[2];
                if (params == null) continue;
                java.lang.reflect.Method m;
                try {
                    m = Class.forName((String) negative[0]).getMethod((String) negative[1], params);
                } catch (NoSuchMethodException absent) {
                    continue;
                }
                resolved++;
                require(!((Boolean) isAllowlisted.invoke(null, m)),
                        "refused: " + negative[0] + "#" + negative[1] + " " + negative[3]);
            }
            require(resolved >= 2, "the named negative controls resolved against the real engine (got " + resolved + ")");

            System.out.println("== Astra 对话29 §5: the default payload reports its own refusals ==");
            Map<?, ?> guards = (Map<?, ?>) raw.get("reflectionGuards");
            require(guards != null, "the default payload carries reflectionGuards");
            for (String field : new String[]{"voidMethodsSkipped", "factoryAccessorsSkipped", "notAllowedSkipped",
                    "counterScope", "notAllowedSignatures"}) {
                require(guards.containsKey(field), "reflectionGuards reports " + field);
            }
            require(guards.get("notAllowedSkipped") instanceof Number,
                    "the allowlist refusal count is a number, not prose");

            System.out.println("== the endpoint never claims a verdict ==");
            for (String forbidden : new String[]{"\"reachable\"", "\"engageable\"", "\"canAttack\"",
                    "reachableEngagementPosition"}) {
                require(!body.contains(forbidden), "payload does not invent " + forbidden);
            }

            System.out.println("== passive groups still answer ==");
            require(raw.get("attackerPosition") instanceof List, "attacker position is still reported");
            require(((Number) raw.get("attackerTile")).longValue() > 0, "attacker tile is still computed");
            Map<?, ?> radiusNow = (Map<?, ?>) raw.get("collisionRadiusRaw");
            require(radiusNow != null && "cj".equals(radiusNow.get("field")),
                    "the collision radius is still reported from a plain field read");

            System.out.println("REACHABILITY_ENDPOINT_TEST_OK checks=" + BridgeHarness.checks
                    + " session=" + session);
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
