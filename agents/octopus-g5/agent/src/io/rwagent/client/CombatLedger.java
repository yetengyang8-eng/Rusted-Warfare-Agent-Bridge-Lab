package io.rwagent.client;

import java.util.*;

/** Per-General lawful observation ledger. HP trends are weak exchange evidence, never kills/KD. */
public final class CombatLedger {
    public enum Crisis {NORMAL,PRESSURED,LOSING_EXCHANGE,OVERMATCHED,RETREATING,REGROUPING}
    static final class OwnSample {
        final long generation; final double hp;
        OwnSample(long generation,double hp){this.generation=generation;this.hp=hp;}
    }
    static final class Damage {
        final long time;final double own,enemy;final int losses;
        Damage(long time,double own,double enemy,int losses){this.time=time;this.own=own;this.enemy=enemy;this.losses=losses;}
    }
    final GeneralRegistry.GeneralId id;
    final Map<Long,OwnSample> own=new LinkedHashMap<Long,OwnSample>();
    final Map<Long,Double> visibleHp=new HashMap<Long,Double>();
    final Deque<Damage> recent=new ArrayDeque<Damage>();
    final Map<Long,Long> acceptedAt=new HashMap<Long,Long>(),acceptedRevision=new HashMap<Long,Long>(),retreatReceiptFrame=new HashMap<Long,Long>();
    final Map<Long,Long> retreatReceiptGeneration=new HashMap<Long,Long>();
    Crisis crisis=Crisis.NORMAL; boolean retreating,rallyKnown,currentVisibleKnown,ownCurrent,rallyBoundsKnown,damageWindowKnown;
    double rallyX,rallyY,ownHp,ownMaxHp,visiblePressure,pressureRatio,recentOwnDamage,weakVisibleHpDecrease;
    int currentHealthy,currentMembers,observedOwnDeaths;
    double unknownWindowOwnHpDecrease,unknownWindowWeakHpDecrease;
    long frame=-1,time=-1,revision,commandRevision,regroupSince=-1,lastMeaningfulTime=-1,lastObservationGapMs=-1;
    String lastMeaningfulEvent="INITIAL",trigger="NONE",session,player;
    Long lastTarget;
    CombatLedger(GeneralRegistry.GeneralId id){this.id=id;}
    void event(String reason,long now){lastMeaningfulEvent=reason;lastMeaningfulTime=now;revision++;}
    void change(Crisis next,long now){if(crisis!=next){crisis=next;event("CRISIS_"+next.name(),now);}}
    public Map<String,Object> view(){Map<String,Object> out=new LinkedHashMap<String,Object>();
        out.put("generalId",id.value);out.put("crisis",crisis.name());out.put("retreating",retreating);
        out.put("rallyKnown",rallyKnown);out.put("rallyX",rallyKnown?rallyX:null);out.put("rallyY",rallyKnown?rallyY:null);
        out.put("rallySource",rallyKnown?"HOME_SIDE_RETREAT_GEOMETRY_DYNAMIC_UNKNOWN":"UNKNOWN");
        out.put("rallyBoundsKnown",rallyBoundsKnown);out.put("rallyGeometry",rallyKnown&&rallyBoundsKnown?"CURRENT_MAP_BOUNDED":"GEOMETRY_BOUNDS_UNKNOWN");
        out.put("rallySafety","UNKNOWN");out.put("ownHp",ownCurrent?ownHp:null);out.put("ownMaxHp",ownCurrent?ownMaxHp:null);
        out.put("currentHealthy",currentHealthy);out.put("currentMembers",currentMembers);out.put("ownCurrent",ownCurrent);
        out.put("visibleCurrent",currentVisibleKnown);out.put("visiblePressure",currentVisibleKnown?visiblePressure:null);
        out.put("pressureRatio",currentVisibleKnown&&Double.isFinite(pressureRatio)?pressureRatio:null);out.put("recentOwnDamage",recentOwnDamage);
        out.put("observedOwnDeaths",observedOwnDeaths);out.put("weakVisibleHpDecrease",weakVisibleHpDecrease);
        out.put("hpDeltaWindow",damageWindowKnown?"OBSERVATION_INTERVAL_WITHIN_5000MS":"UNKNOWN_OBSERVATION_GAP");out.put("lastObservationGapMs",lastObservationGapMs);
        out.put("unknownWindowOwnHpDecrease",unknownWindowOwnHpDecrease);out.put("unknownWindowWeakHpDecrease",unknownWindowWeakHpDecrease);
        out.put("hpDeltaTimeSemantics","DETECTED_AT_SOURCE_OBSERVATION_NOT_NATIVE_DAMAGE_TIME");
        out.put("exchangeEvidence","WEAK_CURRENT_VISIBLE_HP_DELTA_NOT_ATTRIBUTED");out.put("trigger",trigger);
        out.put("lastMeaningfulEvent",lastMeaningfulEvent);out.put("lastMeaningfulGameTimeMs",lastMeaningfulTime);
        out.put("sourceFrame",frame);out.put("gameTimeMs",time);out.put("sessionId",session);out.put("sourceSessionId",session);out.put("sourcePlayerKey",player);out.put("revision",revision);out.put("commandRevision",commandRevision);
        out.put("pressureRadius",GeneralCombatDirector.PRESSURE_RADIUS);out.put("overmatchHpRatio",GeneralCombatDirector.OVERMATCH_RATIO);
        out.put("losingDamageFraction",GeneralCombatDirector.LOSING_DAMAGE_FRACTION);out.put("recoveryPressureRatio",GeneralCombatDirector.RECOVERY_RATIO);
        out.put("regroupHoldMs",GeneralCombatDirector.REGROUP_HOLD_MS);out.put("policyEvidence","CONSERVATIVE_HP_PROXY_NOT_COMBAT_STRENGTH");
        return Collections.unmodifiableMap(out);
    }
}
