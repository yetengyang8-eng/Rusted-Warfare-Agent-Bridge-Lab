package io.rwagent.client;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Frozen-game capability candidates from the verified knowledge packet. This is domain
 * compatibility only: route, range, individual weapons and actual damage are separate facts.
 */
public final class TargetCatalog {
    public static final String GAME_SHA256 =
            "8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9";
    public static final String PACKET_SHA256 =
            "263cdaaa3e923e8307c37f059e50bee529b8ecd7c3e215937ce2adf615a0e236";
    private static final String RESOURCE = "/knowledge/UNIT_CATALOG_CANDIDATES.json";
    private static final String SOURCE_ID =
            "knowledge_packet_2026-09-29/UNIT_CATALOG_CANDIDATES.json#sha256:" + PACKET_SHA256;
    private static final TargetCatalog INSTANCE = load();
    private final Map<String, Unit> units;
    private final Map<String, String> aliases;
    private final boolean valid;

    private TargetCatalog(Map<String, Unit> units, Map<String, String> aliases, boolean valid) {
        this.units = units; this.aliases = aliases; this.valid = valid;
    }
    private static TargetCatalog unavailable() {
        return new TargetCatalog(Collections.<String, Unit>emptyMap(),
                Collections.<String, String>emptyMap(), false);
    }
    public static boolean available() { return INSTANCE.valid; }
    public static String catalogSha256() { return INSTANCE.valid ? PACKET_SHA256 : null; }
    public static String sourceId() { return SOURCE_ID; }
    public static String movementType(String type) {
        Unit unit = INSTANCE.unit(type);
        return unit == null ? null : unit.movement;
    }
    private Unit unit(String type) {
        if (!valid || type == null) return null;
        String canonical = aliases.get(type);
        return canonical == null ? null : units.get(canonical);
    }
    public static final class Decision {
        public final String status, reason, sourceId;
        private Decision(String status, String reason, String sourceId) {
            this.status = status; this.reason = reason; this.sourceId = sourceId;
        }
    }
    private static Decision result(String status, String reason) {
        return new Decision(status, reason, SOURCE_ID);
    }
    /**
     * fresh means the target's AIR/SUBMERGED/SURFACE state came from this legal observation.
     * A last-seen instance may still be a search hypothesis, but its old dynamic state is UNKNOWN.
     */
    public static Decision evaluate(String attackerType, String targetDomain,
                                    Boolean touchingWater, boolean fresh, boolean trusted) {
        if (!trusted || !INSTANCE.valid) return result("UNKNOWN", "CATALOG_OR_GAME_IDENTITY_UNVERIFIED");
        Unit attacker = INSTANCE.unit(attackerType);
        if (attacker == null) return result("UNKNOWN", "ATTACKER_TYPE_NOT_IN_CATALOG");
        if (attacker.armed == null) return result("UNKNOWN", "ATTACK_SWITCH_UNVERIFIED");
        if (!attacker.armed.booleanValue()) return result("INCOMPATIBLE", "ATTACKER_HAS_NO_WEAPON");
        if (!fresh) return result("UNKNOWN", "TARGET_DYNAMIC_STATE_NOT_CURRENTLY_OBSERVED");
        if ("AIR".equals(targetDomain)) {
            return domain(attacker.air, "AIR");
        }
        if ("SUBMERGED".equals(targetDomain)) {
            return domain(attacker.submerged, "SUBMERGED");
        }
        if ("SURFACE".equals(targetDomain)) {
            Decision surface = domain(attacker.surface, "SURFACE");
            if (!"COMPATIBLE".equals(surface.status)) return surface;
            if (attacker.notTouchingWater == null)
                return result("UNKNOWN", "WATER_TOUCH_RULE_UNVERIFIED");
            if (!attacker.notTouchingWater.booleanValue()) {
                if (touchingWater == null)
                    return result("UNKNOWN", "TARGET_TOUCHING_WATER_UNOBSERVED");
                if (!touchingWater.booleanValue())
                    return result("INCOMPATIBLE", "TARGET_NOT_TOUCHING_WATER");
            }
            return surface;
        }
        return result("UNKNOWN", "TARGET_DOMAIN_UNOBSERVED");
    }
    private static Decision domain(Boolean allowed, String name) {
        if (allowed == null) return result("UNKNOWN", "DOMAIN_" + name + "_UNVERIFIED");
        return allowed.booleanValue()
                ? result("COMPATIBLE", "DOMAIN_" + name + "_SUPPORTED")
                : result("INCOMPATIBLE", "DOMAIN_" + name + "_UNSUPPORTED");
    }
    private static final class Unit {
        final String movement;
        final Boolean armed, surface, air, submerged, notTouchingWater;
        Unit(Map<?, ?> fields) {
            movement = textField(fields, "movementType");
            armed = booleanField(fields, "canAttack");
            surface = booleanField(fields, "canAttackLand");
            air = booleanField(fields, "canAttackAir");
            submerged = booleanField(fields, "canAttackUnderwater");
            notTouchingWater = booleanField(fields, "canAttackNotTouchingWater");
        }
    }
    private static boolean supported(Object status) {
        if (!(status instanceof String)) return false;
        String s = (String) status;
        return s.startsWith("VERIFIED_STATIC_") || s.startsWith("DERIVED_STATIC")
                || "DERIVED_EFFECTIVE_CAPABILITY".equals(s);
    }
    private static Object value(Map<?, ?> fields, String name) {
        Object raw = fields.get(name);
        if (!(raw instanceof Map<?, ?>)) return null;
        Map<?, ?> field = (Map<?, ?>) raw;
        return supported(field.get("status")) ? field.get("value") : null;
    }
    private static Boolean booleanField(Map<?, ?> fields, String name) {
        Object v = value(fields, name);
        return v instanceof Boolean ? (Boolean) v : null;
    }
    private static String textField(Map<?, ?> fields, String name) {
        Object v = value(fields, name);
        return v instanceof String ? (String) v : null;
    }
    private static TargetCatalog load() {
        try (InputStream input = TargetCatalog.class.getResourceAsStream(RESOURCE)) {
            if (input == null) return unavailable();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192]; int read;
            while ((read = input.read(chunk)) != -1) bytes.write(chunk, 0, read);
            byte[] data = bytes.toByteArray();
            StringBuilder hash = new StringBuilder(64);
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data))
                hash.append(String.format("%02x", Integer.valueOf(b & 255)));
            if (!PACKET_SHA256.equals(hash.toString())) return unavailable();
            Object parsed = Json.parse(new String(data, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?>)) return unavailable();
            Map<?, ?> root = (Map<?, ?>) parsed;
            if (!(root.get("schemaVersion") instanceof Number)
                    || ((Number) root.get("schemaVersion")).intValue() != 1
                    || !GAME_SHA256.equals(root.get("gameJarSha256"))
                    || !(root.get("aliasMap") instanceof Map<?, ?>)
                    || !(root.get("units") instanceof List<?>)) return unavailable();
            Map<String, String> aliases = new HashMap<String, String>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) root.get("aliasMap")).entrySet())
                if (e.getKey() instanceof String && e.getValue() instanceof String)
                    aliases.put((String) e.getKey(), (String) e.getValue());
            Map<String, Unit> units = new HashMap<String, Unit>();
            for (Object entry : (List<?>) root.get("units")) {
                if (!(entry instanceof Map<?, ?>)) return unavailable();
                Map<?, ?> unit = (Map<?, ?>) entry;
                if (!(unit.get("id") instanceof String) || !(unit.get("fields") instanceof Map<?, ?>))
                    return unavailable();
                units.put((String) unit.get("id"), new Unit((Map<?, ?>) unit.get("fields")));
            }
            if (units.size() != 10 || aliases.size() != 14) return unavailable();
            return new TargetCatalog(units, aliases, true);
        } catch (Exception error) {
            return unavailable();
        }
    }
}
