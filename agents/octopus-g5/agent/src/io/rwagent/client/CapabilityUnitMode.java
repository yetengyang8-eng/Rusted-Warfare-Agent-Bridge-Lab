package io.rwagent.client;

import java.util.Map;

/** One actor's execution phase. Requested transitions never prove native mode completion. */
public final class CapabilityUnitMode {
    public enum Phase { UNKNOWN, AIR_READY, MOVE_TO_WATER, DIVE_REQUESTED, SUBMERGED_READY, FLY_REQUESTED, REPOSITIONING }
    public final long unitId;
    private Phase phase=Phase.UNKNOWN;
    private String nativeMode="UNKNOWN",evidence="NEEDS_EVIDENCE",desiredMovement="UNKNOWN";
    private Long witnessedAt;
    public CapabilityUnitMode(long unitId){if(unitId<0)throw new IllegalArgumentException("unitId");this.unitId=unitId;}
    public Phase phase(){return phase;}
    public String nativeMode(){return nativeMode;}
    public void approachingWater(){phase=Phase.MOVE_TO_WATER;}
    public void diveAccepted(){phase=Phase.DIVE_REQUESTED;}
    public void flyAccepted(){phase=Phase.FLY_REQUESTED;}
    public void repositioning(){if(phase!=Phase.FLY_REQUESTED)phase=Phase.REPOSITIONING;}
    /** Only a validated own amphibiousJet unit-modes Boolean proves the native submerged threshold.
     * Frozen native ae() = Q() = (height < -1). It does not export exact height or prove attack
     * permission/terrain safety. Native movementType also reflects desired Dive/Fly, so it only
     * records the requested navigation mode, never a completed physical transition. */
    public boolean witness(Map<String,Object> response,Map<String,Object> actor,long now){
        Long time=GameClock.number(response.get("gameTimeMs"));
        if(time==null||time<now)return false;
        Long id=GameClock.number(actor==null?response.get("unitId"):actor.get("unitId"));
        if(id==null||id!=unitId)return false;
        if(actor!=null){Object movement=actor.get("movementType");
            if("WATER".equals(movement)||"AIR".equals(movement))desiredMovement=(String)movement;
            return false;
        }
        Object submerged=response.get("submergedWeaponAvailable");
        if(!(submerged instanceof Boolean))return false;
        String observed=Boolean.TRUE.equals(submerged)?"SUBMERGED":"AIR";
        nativeMode=observed;witnessedAt=((Number)time).longValue();
        evidence="NATIVE_OWN_SUBMERGED_WEAPON_FIELD";
        if("SUBMERGED".equals(observed)&&phase!=Phase.FLY_REQUESTED)phase=Phase.SUBMERGED_READY;
        if("AIR".equals(observed)&&phase!=Phase.DIVE_REQUESTED&&phase!=Phase.MOVE_TO_WATER)phase=Phase.AIR_READY;
        return true;
    }
    public Map<String,Object> snapshot(){return StrategyDirector.map("unitId",unitId,"phase",phase.name(),"nativeMode",nativeMode,
            "modeEvidence",evidence,"witnessedAtGameTimeMs",witnessedAt,"desiredMovementType",desiredMovement,
            "desiredMovementIsPhysicalProof",false,"physicalModeSemantics","NATIVE_EQ_LT_MINUS_ONE_THRESHOLD_NOT_EXACT_HEIGHT",
            "terrainAttackPermission","NEEDS_SEPARATE_NATIVE_ENGAGEMENT_EVIDENCE",
            "nativeModeEvidenceSemantics","LAST_EXPLICIT_NATIVE_WITNESS_NOT_CURRENT_PREDICTION","receiptIsNativeModeProof",false);}
}
