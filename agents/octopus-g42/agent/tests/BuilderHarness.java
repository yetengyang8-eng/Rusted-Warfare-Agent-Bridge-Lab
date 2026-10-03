import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;
import com.corrodinggames.rts.game.units.y;
import java.util.*;

/**
 * P2-C1 native checks (输出9 §2): the dedicated builder-production endpoints.
 *
 * The command centre exposes exactly one builder action - ActionId(u_builder), type=builder, cost=500 -
 * and that action belongs to the native FACTORY family, so the queue semantics of landFactory apply.
 * This harness proves the bridge reuses them instead of inventing a second queue system, and that the
 * endpoint refuses to order a second builder while one exists or is already in production.
 */
public final class BuilderHarness {
    static void require(boolean b, String why) { BridgeHarness.require(b, why); }
    static String check(String method, String path, int status, String contains) throws Exception {
        return BridgeHarness.check(method, path, status, contains);
    }

    public static void main(String[] args) throws Exception {
        try {
            OpeningHarness.test();
            com.corrodinggames.rts.game.i engine = EconomyHarness.engine;
            engine.bs.o = 10000;
            am builder = EconomyHarness.builder;
            require(builder != null, "fixture builder available");

            ar centre = ar.valueOf("commandCenter");
            centre.h();
            am cc = centre.a(true);
            am.bF.put(centre, cc);
            cc.eh = 91; cc.bX = engine.bs; cc.cm = 1; cc.eo = 900; cc.ep = 1800;
            am.bE.add(cc);

            String session = (String) EconomyHarness.obj(check("GET", "/state", 200, "commandCenter")).get("sessionId");

            String plan = check("GET", "/economy/builder-production", 200, "u_builder");
            Map<?, ?> p = EconomyHarness.obj(plan);
            require(((Number) p.get("producerId")).longValue() == 91, "the command centre is the selected producer");
            require("commandCenter".equals(p.get("producerType")), "the producer type is reported");
            require(((Number) p.get("builderCost")).intValue() == 500, "builderCost comes from the native action (500)");
            require("builder".equals(p.get("builderType")), "the produced unit type is reported");
            require(((Number) p.get("existingBuilders")).intValue() == 1, "the existing builder is counted");
            require(((Number) p.get("queueCount")).intValue() == 0, "the producer queue is reported empty");
            require(Boolean.FALSE.equals(p.get("builderOrderPending")), "nothing is in production yet");

            // Read-only action inventory (输出10 §6): the builder's own native menu.
            String actions = check("GET", "/economy/builder-actions?unitId=4", 200, "hasRecoveryPath");
            Map<?, ?> a = EconomyHarness.obj(actions);
            require("SAME_BUILD_COMMAND".equals(a.get("recoveryPath")),
                    "the only recovery path is the same-name build command");
            require(((Number) a.get("actionCount")).intValue() > 0, "the builder reports its native actions");
            require(((List<?>) a.get("actions")).size() > 0, "the action list is not empty");
            check("GET", "/economy/builder-actions?unitId=99", 409, "not found");
            check("GET", "/economy/builder-actions", 400, "required");

            // Read-only capability snapshot (输出10 §13): booleans of a live instance plus native price.
            String caps = check("GET", "/combat/capabilities?types=builder,unknownUnit", 200, "booleans");
            Map<?, ?> c = EconomyHarness.obj(caps);
            List<?> units = (List<?>) c.get("units");
            require(units.size() == 1 && "builder".equals(((Map<?, ?>) units.get(0)).get("type")),
                    "a representative own instance is reported");
            require(((Map<?, ?>) units.get(0)).get("price") instanceof Number, "the native price is included");
            require(!((Map<?, ?>) ((Map<?, ?>) units.get(0)).get("booleans")).isEmpty(), "live boolean fields are dumped");
            require(((List<?>) c.get("unavailable")).contains("unknownUnit"), "an unknown type is listed as unavailable");
            StringBuilder many = new StringBuilder();
            for (int i = 0; i < 17; i++) many.append(i > 0 ? ",t" : "t").append(i);
            check("GET", "/combat/capabilities?types=" + many, 400, "at most 16");

            String order = "/command/produce-builder?unitId=91&sessionId=" + session + "&requestId=builder-1";
            check("POST", order, 409, "already exists");

            // With no builder left, the replacement is ordered and the native queue reports it.
            am.bE.remove(builder);
            String before = check("GET", "/economy/builder-production", 200, "existingBuilders\":0");
            require(((Number) EconomyHarness.obj(before).get("existingBuilders")).intValue() == 0, "no builder remains");
            check("POST", order, 200, "\"type\":\"builder\"");
            check("POST", order, 200, "queued");
            // The harness has no game loop, so the queued native command is executed by hand - this is
            // what the engine does on the next frame and what makes the queue observable.
            engine.bV = new com.corrodinggames.rts.gameFramework.aa();
            for (Object queued : engine.cf.b) ((com.corrodinggames.rts.gameFramework.e) queued).k();
            String inProduction = check("GET", "/economy/builder-production", 200, "builderOrderPending\":true");
            require(((Number) EconomyHarness.obj(inProduction).get("buildersQueued")).intValue() == 1,
                    "the native queue counts the queued builder");
            // An idle second command centre must not hide or duplicate the first centre's builder.
            am second = centre.a(true); second.eh = 92; second.bX = engine.bs; second.cm = 1;
            am.bE.add(second);
            int liveBeforePlan = am.bE.size(), ordersBeforePlan = engine.cf.b.size(); double creditsBeforePlan = engine.bs.o;
            Map<?, ?> global = EconomyHarness.obj(check("GET", "/economy/builder-production", 200, "totalBuildersQueued"));
            require(((Number) global.get("producerId")).longValue() == 91, "the existing builder queue is selected before an idle second producer");
            require(((Number) global.get("totalBuildersQueued")).intValue() == 1, "builder queue count covers all own producers");
            require(am.bE.size() == liveBeforePlan && engine.cf.b.size() == ordersBeforePlan && engine.bs.o == creditsBeforePlan,
                    "global builder queue inventory does not create units, commands, or charges");
            check("POST", order.replace("unitId=91", "unitId=92").replace("builder-1", "builder-second"), 409, "already in production");
            check("POST", order.replace("builder-1", "builder-2"), 409, "already in production");
            check("POST", order.replace("unitId=91", "unitId=99").replace("builder-1", "builder-foreign"), 409, "not found");
            check("POST", order.replace(session, "old"), 409, "session changed");
            check("GET", "/economy/builder-production?unitId=91", 400, "no query fields");

            // The action price is real: with too little money the native action is not usable.
            engine.bs.o = 100;
            Map<?, ?> poorPlan = EconomyHarness.obj(check("GET", "/economy/builder-production", 200, "builderActionAffordable"));
            require(Boolean.FALSE.equals(poorPlan.get("builderActionAffordable")), "insufficient native credits are reported independently of menu availability");
            check("POST", order.replace("builder-1", "builder-poor"), 409, "unavailable");
            engine.bs.o = 10000;

            System.out.println("BUILDER_NATIVE_TEST_OK checks=" + BridgeHarness.checks);
            System.exit(0);
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}
