import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;
import com.corrodinggames.rts.gameFramework.utility.u;
import java.util.*;

/**
 * 输出26 §2-§6: sandbox bisect of the live reachability diagnostic.
 *
 * It runs the SAME endpoint against live fixture objects, one arm at a time, and after every call prints a
 * before/after snapshot of the global world-unit registry (am.bE) plus each unit's liveness fields. The
 * purpose is to separate the five outcomes 输出26 §3 lists - removed / flag changed / live-complete changed /
 * HP changed / no change - which the endpoint's own 400 cannot distinguish.
 *
 * Arms (输出26 §4): L0 field reads, L1 movementObject cold, L2 movementObject warm, B, C, D, E, and the
 * INCIDENT_EQUIVALENT_PATH replay of 0beaf504 (all groups, legacy resolution order).
 *
 * Verdict vocabulary is fixed by 输出26 §6. No arm is called "safe" or "culprit" here.
 */
public final class ReachabilitySandboxHarness {
    static int checks;
    static void require(boolean ok, String why) {
        checks++;
        if (!ok) throw new IllegalStateException("FAIL: " + why);
        System.out.println("PASS " + why);
    }

    static final String[] VERDICTS = {"NO_CHANGE_OBSERVED", "STATE_MUTATION_OBSERVED", "OBJECT_REMOVED",
            "LIVE_FLAG_CHANGED", "HP_CHANGED", "INCONCLUSIVE_EXCEPTION", "NOT_EXECUTED"};

    static final class Snapshot {
        int registrySize;
        int attackerIndex = -1, targetIndex = -1;
        boolean attackerSameInstance, targetSameInstance;
        boolean attackerEj, attackerBv, targetEj, targetBv;
        boolean attackerComplete, targetComplete;
        float attackerHp, attackerMaxHp, targetHp, targetMaxHp;
        long attackerEh, targetEh;
        Object attackerRef, targetRef;

        static Snapshot of(am attacker, am target) {
            Snapshot s = new Snapshot();
            s.attackerRef = attacker;
            s.targetRef = target;
            s.registrySize = am.bE.size();
            am[] units = am.bE.a();
            for (int i = 0; i < am.bE.size(); i++) {
                am unit = units[i];
                if (unit == attacker) {
                    s.attackerIndex = i;
                    s.attackerSameInstance = true;
                }
                if (unit == target) {
                    s.targetIndex = i;
                    s.targetSameInstance = true;
                }
            }
            s.attackerEj = attacker.ej;
            s.attackerBv = attacker.bV;
            s.targetEj = target.ej;
            s.targetBv = target.bV;
            s.attackerComplete = !attacker.cW();
            s.targetComplete = !target.cW();
            s.attackerHp = attacker.cu;
            s.attackerMaxHp = attacker.cv;
            s.targetHp = target.cu;
            s.targetMaxHp = target.cv;
            s.attackerEh = attacker.eh;
            s.targetEh = target.eh;
            return s;
        }

        String row(String arm, String mode, String result, String exception, String verdict) {
            return String.format(
                    "| %-24s | %-5s | %-28s | %-6s | %-5s | %-6s | %-6s | %-9s | %-6s | %-5s | %-6s | %-6s | %-9s | %-9s | %s | %s |",
                    arm, mode, result,
                    attackerSameInstance, targetSameInstance,
                    attackerIndex, targetIndex, registrySize + "",
                    attackerEj, attackerBv, targetEj, targetBv,
                    hp(attackerHp) + "/" + hp(attackerMaxHp), hp(targetHp) + "/" + hp(targetMaxHp),
                    exception, verdict);
        }

        static String hp(float value) {
            return String.format("%.0f", value);
        }
    }

    static Snapshot before;

    /** One sandbox arm: snapshot, call, snapshot, verdict by the 输出26 §6 vocabulary. */
    static void arm(String name, String mode, String query, am attacker, am target) {
        Snapshot pre = Snapshot.of(attacker, target);
        String result, exception = "-", verdict;
        try {
            String body = BridgeHarness.check("GET", query, 200, "\"status\":\"raw\"");
            Map<?, ?> parsed = EconomyHarness.obj(body);
            result = String.valueOf(parsed.get("pathMode")) + "/" + String.valueOf(parsed.get("groupsEnabled"));
            if (String.valueOf(parsed.get("engineAqAResult")).contains("THREW")
                    || String.valueOf(parsed.get("engineAqBResult")).contains("THREW")) {
                result = result + " aqThrew";
            }
            exception = "-";
        } catch (Throwable error) {
            result = "HTTP_FAILURE";
            exception = rootCause(error);
        }
        Snapshot post = Snapshot.of(attacker, target);
        verdict = verdictOf(pre, post, exception);
        System.out.println("arm " + name + " preReg=" + pre.registrySize + " postReg=" + post.registrySize
                + " attackerIdx " + pre.attackerIndex + "->" + post.attackerIndex
                + " targetIdx " + pre.targetIndex + "->" + post.targetIndex);
        System.out.println("ROW" + post.row(name, mode, result, exception, verdict));
    }

    /**
     * Verdict by the 输出26 §6 vocabulary. Only the categories the snapshot can actually distinguish are
     * used; the caller is responsible for reading them with the arm's context.
     */
    static String verdictOf(Snapshot pre, Snapshot post, String exception) {
        if (!"-".equals(exception)) return "INCONCLUSIVE_EXCEPTION";
        if (!post.attackerSameInstance || !post.targetSameInstance) return "OBJECT_REMOVED";
        if (post.attackerEj != pre.attackerEj || post.attackerBv != pre.attackerBv
                || post.targetEj != pre.targetEj || post.targetBv != pre.targetBv) return "LIVE_FLAG_CHANGED";
        if (post.attackerComplete != pre.attackerComplete || post.targetComplete != pre.targetComplete)
            return "STATE_MUTATION_OBSERVED";
        if (post.attackerHp != pre.attackerHp || post.targetHp != pre.targetHp) return "HP_CHANGED";
        if (post.registrySize != pre.registrySize) return "STATE_MUTATION_OBSERVED";
        return "NO_CHANGE_OBSERVED";
    }

    static String rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage()).replace('|', '/');
    }

    public static void main(String[] args) throws Exception {
        try {
            EconomyHarness.test();
            com.corrodinggames.rts.game.i engine = EconomyHarness.engine;
            EconomyHarness.set(Class.forName("com.corrodinggames.rts.game.units.e.n"), "b",
                    BridgeHarness.construct("com.corrodinggames.rts.gameFramework.m.e"));
            String session = (String) EconomyHarness.obj(
                    BridgeHarness.check("GET", "/state", 200, "sessionId")).get("sessionId");

            ar targetType = ar.valueOf("commandCenter");
            targetType.h();
            am target = targetType.a(true);
            am.bF.put(targetType, target);
            target.eh = 771; target.bX = new com.corrodinggames.rts.game.e(1, false);
            target.cm = 1; target.eo = 1200; target.ep = 1700;
            am.bE.add(target);

            ar attackerType = ar.valueOf("tank");
            attackerType.h();
            am attacker = attackerType.a(true);
            am.bF.put(attackerType, attacker);
            attacker.eh = 772; attacker.bX = engine.bs; attacker.cm = 1; attacker.eo = 941; attacker.ep = 1781;
            am.bE.add(attacker);

            System.out.println("registry size = " + am.bE.size());
            System.out.println();
            String header = String.format(
                    "| %-24s | %-5s | %-28s | %-6s | %-5s | %-6s | %-6s | %-9s | %-6s | %-5s | %-6s | %-6s | %-9s | %-5s | %-5s | %s | %s |",
                    "arm", "mode", "requestResult", "aSame", "tSame", "aIdx", "tIdx", "regSize",
                    "aEj", "aBv", "tEj", "tBv", "aHp", "tHp", "tmp", "exception", "verdict");
            System.out.println("TABLE_BEGIN");
            System.out.println(header);
            before = Snapshot.of(attacker, target);
            System.out.println("ROW" + before.row("BASELINE", "-", "not executed", "-", "NOT_EXECUTED"));

            String base = "/combat/reachability?unitId=772&targetId=771";
            // 输出26 §4 arm order: field reads, then the cold/warm lazy movement lookup, then each group,
            // then the incident-equivalent replay of the quarantined candidate.
            arm("L0_field_reads", "cold", base, attacker, target);
            arm("L1_movement_cold", "cold", base + "&groups=B", attacker, target);
            arm("L2_movement_warm", "warm", base + "&groups=B", attacker, target);
            arm("B_passability", "warm", base + "&groups=B", attacker, target);
            arm("C_outer_query", "warm", base + "&groups=C", attacker, target);
            arm("D_aq_a_y_am", "warm", base + "&groups=D", attacker, target);
            arm("E_aq_b_y_am", "warm", base + "&groups=E", attacker, target);
            arm("INCIDENT_ALL_GROUPS", "warm", base + "&incident=1", attacker, target);
            arm("INCIDENT_SAFE_RESOLUTION", "warm", base, attacker, target);
            System.out.println("TABLE_END");

            System.out.println();
            System.out.println("movementResolutionTrace (cold path detail):");
            Map<?, ?> tracePayload = EconomyHarness.obj(
                    BridgeHarness.check("GET", base + "&groups=B", 200, "movementResolutionTrace"));
            System.out.println("   " + tracePayload.get("movementResolutionTrace"));
            System.out.println("registrySize at end = " + am.bE.size());

            System.out.println();
            System.out.println("REACHABILITY_SANDBOX_OK checks=" + checks + " session=" + session);
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
