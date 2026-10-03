package io.rwagent.client;
import java.io.*;
import java.net.URLEncoder;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded rule opponent: legal observations, native orders, losses and replenishment. */
public final class BattleClient implements StrategyDirector.Host {
    private final int port=Integer.getInteger("rwagent.port",47653);
    private BufferedWriter log;private FileOutputStream output;private String session;
    private int commands,observations,losses,recruits,attacks,confirmed,retreats,techs;
    private long time,lastDecision=-1000,lastTactic=-10000,startTime,lastFrame=-1,frameAt=System.nanoTime();
    private final CommandArbiter execution=new CommandArbiter();
    private final StrategyDirector strategy=new StrategyDirector(this,execution);
    private final LocalArmyDirector localArmies=new LocalArmyDirector();
    private boolean localArmyActive;
    private LocalCrisis localCrisis;
    private long crisisSequence,lastCrisisProbe=-100000,lastCohortDiagnostic=-100000,lastCommandAt=-100000,lastFairCommand=-100000;
    private String lastCommandOwner,lastCommandPath;
    private final Map<Long,Long> crisisRetryAfter=new LinkedHashMap<Long,Long>();
    private static final class LocalCrisis {
        final long task,target,asset,created;final String owner;
        final List<Long> actors;final Map<Long,Long> leases=new LinkedHashMap<Long,Long>();final double returnX,returnY;
        long lastSeen,lastAccepted=-100000,lastIdleRecovery=-100000,returnAt=-1,returnAccepted=-1;
        double goalX,goalY;String returnReason;
        LocalCrisis(long task,long target,long asset,long now,List<Long> actors,double x,double y){
            this.task=task;this.target=target;this.asset=asset;created=lastSeen=now;
            owner="local-crisis:"+task;this.actors=actors;returnX=x;returnY=y;
            int index=0;for(Long uid:actors)leases.put(uid,-(task*100+(++index)));
        }
    }
    private final Set<Long> seenOwn=new HashSet<Long>(),lost=new HashSet<Long>();
    private final Map<Long,Pending> pending=new LinkedHashMap<Long,Pending>();
    private SurplusSpendingPolicy.Ledger artilleryLedger=SurplusSpendingPolicy.Ledger.empty();
    private final Map<Long,Long> lastSurplusEvaluation=new HashMap<Long,Long>();
    private final Map<Long,Long> resting=new LinkedHashMap<Long,Long>();
    private final List<Long> avoided=new ArrayList<Long>();
    private double homeX,homeY,targetX,targetY,best=Double.MAX_VALUE;
    /**
     * 杈撳嚭23 搂2: the enemy ID the last attack order was actually issued against. The raw reachability
     * diagnostic binds to this ID when it is available, so the sample explains the Agent's real action
     * instead of a spatial guess.
     */
    private Long selectedTargetEnemyId;
    private long targetAt,progressAt,leader=-1,targetTile=-1;
    /** P1-D: the old hardcoded 24 is split into a main force, a reserve and a real safety cap. */
    private final int activeArmyTarget=Math.max(1,Integer.getInteger("rwagent.activeArmyTarget",24));
    private final int reserveTarget=Math.max(0,Integer.getInteger("rwagent.reserveTarget",8));
    /**
     * 杈撳嚭19 搂2: 40 is the current development default, adopted from the cap 32 -> 40 Giant Island A/B
     * (army>=32 heartbeat share 22.7% -> 61.4%). It is NOT claimed to be the optimum: the same sample kept
     * a large credit pool unspent at the cap, so the economic-investment question is still open. The JVM
     * override stays available for 32/40 regression runs.
     */
    private int mobileUnitHardCap=Math.max(activeArmyTarget,Integer.getInteger("rwagent.mobileUnitHardCap",40));
    private long lastHeartbeat=-100000,lastIdleEmit=-100000;
    private Map<String,Object> lastScout,lastEnemies,lastPlan;
    /**
     * 杈撳嚭21 搂8 / 杈撳嚭22 搂1-搂2: raw reachability calibration sampling. It is ON by default because the
     * current phase is exactly the collection of calibration data; {@code -Drwagent.reachabilitySample=false}
     * turns it off completely. The interval and the representative limit are reported in battle_config so
     * the report always states what was actually used.
     */
    private final boolean reachabilitySampleEnabled=!Boolean.FALSE.toString()
            .equalsIgnoreCase(System.getProperty("rwagent.reachabilitySample","true"));
    private final long reachabilitySampleIntervalMs=Math.max(1000,Integer.getInteger("rwagent.reachabilitySampleIntervalMs",30000));
    private final int reachabilityTypeLimit=Math.max(1,Integer.getInteger("rwagent.reachabilityTypeLimit",3));
    /** 杈撳嚭21 搂8: rate limit and de-duplication for the raw reachability calibration samples. */
    private long lastReachabilitySample=-100000;
    private String lastReachabilityTarget;
    /** 杈撳嚭23 搂6: combat types whose full float dump has already been taken this match. */
    private final Set<String> fullFloatDumped=new LinkedHashSet<String>();
    /** 杈撳嚭26 搂8: Agent-side sample numbering, reported only. */
    private long reachabilityBatchCounter;
    private String productionIdleReason;
    private boolean reserveAnnounced;
    private boolean targetReady,marching;
    /**
     * P1-B search objective: a hypothesis that an enemy was seen at a place, so the place is worth
     * re-checking. It is never a claim about where that enemy is now, and it may only be created
     * from a fresh legal observation and consumed once its tile is legally visible again.
     */
    private static final class SearchTarget {
        final long targetId,sourceEnemyId,createdGameTime;final String sourceType;
        double x,y;long lastSeenGameTime,tile;
        String status="ACTIVE";int attemptCount;
        SearchTarget(long targetId,long sourceEnemyId,String sourceType,double x,double y,long lastSeenGameTime,long createdGameTime,long tile){
            this.targetId=targetId;this.sourceEnemyId=sourceEnemyId;this.sourceType=sourceType;this.x=x;this.y=y;
            this.lastSeenGameTime=lastSeenGameTime;this.createdGameTime=createdGameTime;this.tile=tile;
        }
    }
    private SearchTarget searchTarget;
    private long searchTargetCounter,searchTargetAt,searchProgressAt;
    private double searchBest=Double.MAX_VALUE;
    private boolean searchNeedsOrder=true;
    /** One Recon task owns an actor at a time; both kinds share command and preemption control. */
    private static final class ReconTask {
        static final String RECHECK_INTEL="RECHECK_INTEL",FRONTIER_SWEEP="FRONTIER_SWEEP";
        final String kind;
        final long taskId,enemyId,lastSeen,createdAt;
        final String sourceType;
        final double x,y;
        final boolean armedAtLastSighting;
        final long preferredUnitId,frontierTile,targetTile;
        final String frontierMemoryClass;
        final String movementType,routeTerrainStatus,terrainSourceId;
        final int potentialInfoScore,potentialNeverSeenTiles,potentialDeepFogTiles,potentialStaleFogTiles;
        final List<Integer> routeTiles;
        int routeCursor,routeWaypointIndex;
        long unitId=-1,lastOrderAt=-100000,firstOrderAt=-1,firstProgressAt=-1,waypointArrivedAt=-1,retryAt,progressAt,lastProgressEventAt=-100000;
        double waypointX,waypointY,bestDistance=Double.MAX_VALUE;
        double segmentStartX,segmentStartY;
        int orders,progressEvents;
        boolean orderObserved,everOrderObserved,executionObserved,everExecutionObserved,awaitFreshOwnObservation;
        MoveExecution move;
        final Set<Long> failedUnits=new LinkedHashSet<Long>();
        String approach="DIRECT";
        ReconTask(long taskId,Map<String,Object> intel,long now){
            this.taskId=taskId;kind=RECHECK_INTEL;enemyId=id(intel);sourceType=stringOrNull(intel,"lastKnownType");
            x=n(intel,"lastKnownX");y=n(intel,"lastKnownY");
            lastSeen=((Number)intel.get("lastSeenGameTimeMs")).longValue();createdAt=progressAt=now;
            armedAtLastSighting=Boolean.TRUE.equals(intel.get("lastKnownCanAttack"));
            preferredUnitId=-1;frontierTile=-1;targetTile=-1;frontierMemoryClass=null;
            movementType=routeTerrainStatus=terrainSourceId=null;
            potentialInfoScore=potentialNeverSeenTiles=potentialDeepFogTiles=potentialStaleFogTiles=0;
            routeTiles=Collections.emptyList();
            waypointX=x;waypointY=y;
        }
        ReconTask(long taskId,Map<String,Object> plan,long actorId,long now){
            this.taskId=taskId;kind=FRONTIER_SWEEP;enemyId=-1;sourceType="MAP_MEMORY";
            x=n(plan,"targetX");y=n(plan,"targetY");lastSeen=createdAt=progressAt=now;
            armedAtLastSighting=false;preferredUnitId=actorId;
            frontierTile=((Number)plan.get("frontierTile")).longValue();
            targetTile=((Number)plan.get("targetTile")).longValue();
            frontierMemoryClass=stringOrNull(plan,"frontierMemoryClass");
            movementType=stringOrNull(plan,"movementType");
            Map<String,Object> terrain=plan.get("routeTerrain") instanceof Map<?,?>
                    ?obj(plan.get("routeTerrain")):Collections.<String,Object>emptyMap();
            routeTerrainStatus=stringOrNull(terrain,"status");
            terrainSourceId=stringOrNull(terrain,"sourceId");
            potentialInfoScore=((Number)plan.get("potentialInfoScore")).intValue();
            potentialNeverSeenTiles=((Number)plan.get("potentialNeverSeenTiles")).intValue();
            potentialDeepFogTiles=((Number)plan.get("potentialDeepFogTiles")).intValue();
            potentialStaleFogTiles=((Number)plan.get("potentialStaleFogTiles")).intValue();
            routeTiles=new ArrayList<Integer>();
            for(Object value:(List<?>)plan.get("routeTiles"))routeTiles.add(Integer.valueOf(((Number)value).intValue()));
            waypointX=x;waypointY=y;
        }
        boolean frontier(){return FRONTIER_SWEEP.equals(kind);}
    }
    private final boolean reconEnabled=!Boolean.FALSE.toString().equalsIgnoreCase(System.getProperty("rwagent.reconEnabled","true"));
    private final boolean reconFrontierEnabled=!Boolean.FALSE.toString().equalsIgnoreCase(System.getProperty("rwagent.reconFrontierEnabled","true"));
    /** Expendable assignment survives task completion so a low-value actor never re-enters army attack-move. */
    private final Set<Long> expendableScouts=new LinkedHashSet<Long>();
    private ReconTask reconTask;
    /** Ownership precedes planning when an old native command is still moving the actor. */
    private static final class ReconAcquisition {
        final long taskId,unitId,startedAt;
        MoveExecution hold;
        ReconAcquisition(long taskId,long unitId,long now){this.taskId=taskId;this.unitId=unitId;startedAt=now;}
    }
    private ReconAcquisition reconAcquisition;
    private long reconMovesCompletedBetweenSamples,frontierTasksSatisfied;
    private long reconRecallUnitId=-1,reconRecallQueuedAt=-1;
    private long reconTaskCounter,reconCreated,reconOrders,reconOrdersObserved,reconResolvedAfterObservedMove,reconReacquired,reconCleared,reconBlocked,lastReconDeferredAt=-100000;
    private long frontierTasksCreated,frontierTasksRefreshed,frontierTasksAdvanced,frontierTasksBlocked,frontierTasksPreempted,expendableTransfers,lastFrontierPlanAt=-100000;
    private final Map<Long,Long> frontierBlockedUntil=new LinkedHashMap<Long,Long>();
    private boolean recheckNextAfterFrontier;
    private final Map<Long,Long> reconClosedObservations=new LinkedHashMap<Long,Long>();
    /**
     * 杈撳嚭30 搂4 NO_PROGRESS_TARGET_HANDLING v0: one visible target, engaged by the main force, whose HP
     * does not fall inside a stated window, is temporarily deprioritised so the army searches or fights
     * something else. It is a retry, never an abandonment, and it uses only legal visible information 鈥?     * the stall clock runs on *fresh* observations of *our own* units' proximity, and it is suspended
     * (not merely paused) whenever the target is only remembered, because stale intel cannot tell us
     * whether the target is being hurt.
     */
    private static final class TargetProgress {
        final long enemyId;final String type;
        long lastSeenAt,lastOrderedAt,engageStartedAt,lastTrackedAt;
        long visibleSince;                 // start of the current visible+close+unchanged stretch
        long deprioritizedUntil;
        double lowestHp=Double.MAX_VALUE;
        int ordersIssued,deprioritizeCount,engagements;
        boolean active=true,damageObserved=true,retryAnnounced=true;
        TargetProgress(long enemyId,String type){this.enemyId=enemyId;this.type=type;}
    }
    private final Map<Long,TargetProgress> targetProgress=new LinkedHashMap<Long,TargetProgress>();
    /** A policy exclusion, not a claim that an unseen target is still in the same domain.
     * No timeout: fog, missing catalog data and same-type reinforcements cannot erase a rejection.
     * Only a newer legal visible COMPATIBLE observation can release it (including a changed force).
     * Instance-local like session/player ownership; never shared across controllers or matches.
     */
    private static final class TargetSuppression {
        long observedAt;Map<String,Object> evidence;boolean holdReported;
        TargetSuppression(long observedAt,Map<String,Object> evidence){this.observedAt=observedAt;this.evidence=evidence;}
    }
    private final Map<Long,TargetSuppression> targetSuppressions=new LinkedHashMap<Long,TargetSuppression>();
    private long targetSuppressionsStarted,targetSuppressionsReleased,targetSuppressedSelections;
    /** 杈撳嚭30 搂4: the window, the cooldown and "the main force is close" are all explicit and tunable. */
    private final long noProgressWindowMs=Math.max(2000L,Integer.getInteger("rwagent.noProgressWindowMs",20000));
    private final long noProgressCooldownMs=Math.max(2000L,Integer.getInteger("rwagent.noProgressCooldownMs",30000));
    private final double noProgressEngageRange=Math.max(40,Integer.getInteger("rwagent.noProgressEngageRange",300));
    /**
     * The window measures CONTINUOUS engagement, so a target we stopped ordering at (because another
     * target won selection) must not keep accumulating the wall time in between. Live 对话31 caught the
     * original elapsed-time version: a target selected only once every ~50 s reported stalledMs=49643
     * against a 20 s window. The tolerance is the derived one (see noteDecisionInterval).
     */
    private long noProgressTriggers,noProgressRetries,targetSwitches;
    /**
     * Four coarse states from legal signals only: when we last lost a unit, how close the nearest known
     * enemy is to home, and whether the army is at its normal fighting size. Deliberately NOT a threat
     * model (对话37 共识 §7: do not expand this into a master-level battlefield assessment).
     */
    private void updateMilitaryUrgency(Map<String,Object> state,Map<String,Object> enemies){
        double nearest=Double.MAX_VALUE;
        if(enemies!=null)for(Map<String,Object> e:list(enemies,"rememberedEnemies")){
            if(!(e.get("x") instanceof Number)||!(e.get("y") instanceof Number))continue;
            nearest=Math.min(nearest,Math.hypot(n(e,"x")-homeX,n(e,"y")-homeY));
        }
        int army=mainArmy(state).size();
        long sinceLoss=time-lastOwnLossAt;
        String next;
        if(nearest<=300||sinceLoss<=15000)next=URGENCY_EMERGENCY;
        else if(nearest<=800||sinceLoss<=45000)next=URGENCY_CONTESTED;
        else if(army>=activeArmyTarget)next=URGENCY_OPEN;
        else next=URGENCY_STABLE;
        if(!next.equals(militaryUrgency)){
            militaryUrgency=next;lastUrgencyChangeAt=time;
            Map<String,Object> data=new LinkedHashMap<String,Object>();
            data.put("state",next);data.put("nearestEnemyDistance",nearest==Double.MAX_VALUE?-1:Math.round(nearest));
            data.put("army",army);data.put("sinceLastLossMs",sinceLoss);data.put("gameTimeMs",time);
            try{event("military_urgency",json(data));}catch(Exception ignored){}
        }
    }
    /** Production may not spend money committed to an investment or a bounded capability purchase. */
    private boolean wouldBreachInvestmentReserve(Map<String,Object> state,double cost){
        return investmentReserve>0&&n(obj(state.get("player")),"credits")-cost<investmentReserve
                ||wouldBreachCapabilityReserve(state,cost);
    }
    private boolean wouldBreachCapabilityReserve(Map<String,Object> state,double cost){
        // Other reserves exclude the capability itself: adding it to strategyReserve would make
        // the director unable to spend its own commitment once the native quote is affordable.
        return strategy.wouldBreachCapabilityReserve(n(obj(state.get("player")),"credits"),cost,strategyReserve());
    }
    /**
     * One intent at a time, and only from a quiet state with an army that is not clearly weak. The reserve
     * is what makes the mine happen while the army is still below its hard cap - the v0.1 gap.
     */
    private void considerInvestmentIntent(Map<String,Object> state){
        if(investment!=null)return;
        if(time-lastInvestmentReleaseAt<investmentRetryCooldownGameMs)return;
        if(!URGENCY_STABLE.equals(militaryUrgency)&&!URGENCY_OPEN.equals(militaryUrgency))return;
        if(mainArmy(state).size()<activeArmyTarget)return;   // a transferred scout is not main-force readiness
        long cost=lastExtractorCost>0?lastExtractorCost:investmentMineCostDefault;
        if(wouldBreachCapabilityReserve(state,cost))return;
        long[] bucket=spendByCategory.get(SPEND_MINE);
        investment=new Investment("NEW_MINE",cost,time,time+investmentTimeoutGameMs);
        investmentReserve=cost;investmentIntentions++;
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("target","NEW_MINE");data.put("cost",cost);data.put("urgency",militaryUrgency);
        data.put("army",mainArmy(state).size());data.put("hardCap",mobileUnitHardCap);
        data.put("minesDone",bucket==null?0:bucket[0]);
        data.put("deadlineGameTimeMs",investment.deadline);data.put("gameTimeMs",time);
        try{event("investment_intent",json(data));}catch(Exception ignored){}
    }
    /** Every exit path must release the reserve; a reserve held at match end is the failure we guard against. */
    private void releaseInvestment(String reason)throws Exception{
        if(investment==null&&investmentReserve==0)return;
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("reason",reason);data.put("target",investment==null?"NEW_MINE":investment.target);
        data.put("cost",investmentReserve);data.put("ageMs",investment==null?0:time-investment.chosenAt);
        data.put("gameTimeMs",time);
        investment=null;investmentReserve=0;
        // Builder Utilization v0 (对话41): the retry cooldown exists to stop churn after a FAILED or
        // abandoned attempt. A COMPLETED investment has no churn to prevent, yet it used to inherit the
        // same 60 game second pause - measured at 136-171 builder-idle seconds per 900 s match, the
        // largest self-inflicted slice of idle time. Success releases immediately instead.
        if(!"COMPLETED".equals(reason))lastInvestmentReleaseAt=time;
        if("COMPLETED".equals(reason))investmentCompletions++;else investmentCancellations++;
        event("investment_released",json(data));
    }
    private void maintainInvestment(Map<String,Object> state)throws Exception{
        if(investment==null)return;
        if(URGENCY_EMERGENCY.equals(militaryUrgency)||URGENCY_CONTESTED.equals(militaryUrgency)){
            releaseInvestment("MILITARY_PRESSURE");return;
        }
        // Inclusive deadline: "release when the deadline arrives", not one decision later. A strict
        // comparison made the release depend on where the tick happened to land (found by 对话39 test).
        if(time>=investment.deadline)releaseInvestment("TIMEOUT");
    }
    /**
     * Every accepted order goes through here: gameTimeMs / category / cost / itemType / producer id.
     * `accepted` is implicit - a rejected POST returns before this is called.
     */
    private void spend(String category,double cost,String itemType,long producerId)throws Exception{
        long value=cost>0?Math.round(cost):0;
        long[] bucket=spendByCategory.get(category);
        if(bucket==null){bucket=new long[2];spendByCategory.put(category,bucket);}
        bucket[0]++;bucket[1]+=value;spendTotal+=value;
        spendLedger.add(new long[]{time,value});
        // Economy v1b: the consumption side of the throughput gate is this ledger, never a balance delta.
        if(SPEND_UNIT.equals(category))productionLedger.add(new long[]{time,value});
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("gameTimeMs",time);data.put("category",category);data.put("cost",value);
        data.put("itemType",itemType==null?"unknown":itemType);
        data.put("producerOrBuilderId",producerId);
        event("spend",json(data));
    }
    /**
     * Speed readiness (对话35): the decision GATE is game time, but the loop that reaches it is wall time,
     * so the real decision interval in game ms grows with the game speed. A hard-coded attention tolerance
     * therefore silently assumed 1x - and at 5x (0.5 s wall = 2.6 game s) it would leave 400 ms of margin
     * before resetting on every tick, which does not fail loudly, it just reports "no stalls".
     * Tolerance is derived from the measured interval instead: three decision opportunities of jitter are
     * allowed, four or more count as a real break in engagement. At 1x this reproduces 3000 ms exactly.
     */
    private void noteDecisionInterval(long intervalGameMs){
        if(intervalGameMs>0&&intervalGameMs<600000){
            recentDecisionIntervals[decisionIntervalIndex]=intervalGameMs;
            decisionIntervalIndex=(decisionIntervalIndex+1)%recentDecisionIntervals.length;
        }
    }
    private long effectiveDecisionIntervalGameMs(){
        long max=0;boolean seen=false;
        for(long value:recentDecisionIntervals)if(value>0){seen=true;if(value>max)max=value;}
        return seen?max:1000;
    }
    private long noProgressAttentionGapMs(){
        return Math.max(3000L,3*effectiveDecisionIntervalGameMs());
    }
    /** enemyId -> observation time already consumed or proven unreachable; older intel never revives. */
    private final Map<Long,Long> consumedObservations=new LinkedHashMap<Long,Long>();
    private final Map<Long,Long> unreachableObservations=new LinkedHashMap<Long,Long>();
    /**
     * P2-A/P2-B: continuous economic expansion and one extra production facility. This deliberately
     * does not touch the army caps, unit types, combat policy, repair or rebuilding.
     */
    /**
     * Economy v0 (对话32): `mineTarget` is a FLOOR, not a destination. Below it the lane expands
     * unconditionally so the opening and recovery behaviour is unchanged; at or above it the lane keeps
     * asking for sites and may take a 4th, 5th ... mine, but only when there is real surplus and the site
     * is legal and safe. Evidence for the change: in three long matches the mine count froze at 3 by
     * ~240 game seconds while 8-12 resource points were already scouted and 16k credits piled up.
     * Deliberately NOT added here: upgrades, new-factory triggers, extra builders, income-rate EMA.
     */
    private final int mineTarget=Math.max(1,Integer.getInteger("rwagent.mineTarget",3));
    private long minesBeyondFloor,expansionRefusals;
    /**
     * Economy v1a (对话37, ChatGPT+用户共识 §8): a complete spend ledger. Income can then be measured as
     * "delta credits + known spend" over a game-time window, which is what calibrates the mechanics-library
     * prior (T1 mine ~= 12.1 credits per normal game second, i.e. 700/58) against our own matches.
     * Only orders the bridge ACCEPTED are recorded, so the ledger never claims money that was not spent.
     */
    private static final String SPEND_UNIT="UNIT_PRODUCTION",SPEND_BUILDER="BUILDER_RECOVERY",
            SPEND_MINE="NEW_MINE",SPEND_FACTORY="NEW_FACTORY",SPEND_FACTORY_UPGRADE="FACTORY_UPGRADE";
    private final Map<String,long[]> spendByCategory=new LinkedHashMap<String,long[]>();
    private long spendTotal;
    private double startCredits=-1;
    /**
     * Economy v1a (对话37 共识 §7): MILITARY_URGENCY, a coarse four-state read of "is there an obvious,
     * urgent need for military funding right now". The point is NOT to predict the battle - only to answer
     * that one question from legally observable signals, so the economy may take budget in quiet moments
     * and must give it back when things get hot.
     */
    private static final String URGENCY_EMERGENCY="EMERGENCY",URGENCY_CONTESTED="CONTESTED",
            URGENCY_STABLE="STABLE",URGENCY_OPEN="OPEN_EXPANSION";
    private String militaryUrgency=URGENCY_STABLE;
    private long lastOwnLossAt=-1000000,lastUrgencyChangeAt=-1000000,lastProgressAt=-1000000;
    /** A chosen investment the production lane may not spend. Exactly one at a time in v1a. */
    private static final class Investment {
        final String target;final long cost,chosenAt,deadline;
        final boolean costLocked;
        Investment(String target,long cost,long chosenAt,long deadline){
            this(target,cost,chosenAt,deadline,false);
        }
        Investment(String target,long cost,long chosenAt,long deadline,boolean costLocked){
            this.target=target;this.cost=cost;this.chosenAt=chosenAt;this.deadline=deadline;
            this.costLocked=costLocked;
        }
    }
    private Investment investment;
    private long investmentReserve,investmentIntentions,investmentCancellations,investmentCompletions;
    private long lastInvestmentReleaseAt=-1000000;
    private final long investmentTimeoutGameMs=Math.max(30000,Integer.getInteger("rwagent.investmentTimeoutGameMs",180000));
    /**
     * After a release, wait before opening another intent. Without this the lane churns: intent -> timeout
     * -> intent -> timeout, throttling production the whole time for a mine that cannot happen yet.
     */
    private final long investmentRetryCooldownGameMs=Math.max(10000,Integer.getInteger("rwagent.investmentRetryCooldownGameMs",60000));
    /** The T1 extractor price, needed before a plan can report one; overridden by any observed price. */
    private final long investmentMineCostDefault=Math.max(1,Integer.getInteger("rwagent.mineCost",700));
    /**
     * Speed readiness: recent decision intervals in game ms. The max of a short window (rather than an
     * average) is deliberate - the user ramps speed mid-match, and the tolerance must grow as soon as the
     * speed does, not after an EMA catches up. Decaying back down within 8 decisions is fine.
     */
    private final long[] recentDecisionIntervals=new long[8];
    private int decisionIntervalIndex;
    private long maxObservedGameTimeJump,lastObservedGameTime=-1;
    /**
     * Above-floor probing gets its own rate-limit stamp. Sharing `lastExpansionAttempt` (which also gates
     * planProductionFacility) meant every above-floor probe reset the factory lane's clock, so a refused
     * mine probe could starve the second factory — a regression the above-floor tests caught.
     */
    private long lastAboveFloorProbeAt=-1000000L;
    /** The engine's own extractor price, learned from any successful /expansion/plan response. */
    private long lastExtractorCost=-1;
    private final int landFactoryTargetInitial=Math.max(1,Integer.getInteger("rwagent.landFactoryTarget",2));
    /**
     * Economy v1b (对话38 裁决 §2): no longer constant. The configured value is the STARTING target; a
     * long-term throughput bottleneck may raise it by one at a time (see considerFactoryTargetIncrease).
     */
    private long landFactoryTarget=landFactoryTargetInitial;
    private final long expansionIntervalGameMs=Math.max(2000,Integer.getInteger("rwagent.expansionIntervalGameMs",15000));
    private final double expansionBuildRangeWorld=Math.max(20,Integer.getInteger("rwagent.expansionBuildRangeWorld",100));
    private final double rearFactoryRadiusWorld=Math.max(60,Integer.getInteger("rwagent.rearFactoryRadiusWorld",400));
    private static final String JOB_EXTRACTOR="extractor",JOB_LAND_FACTORY="landFactory";
    private static final class BuildJob {
        final String kind;final double x,y;final long cost,plannedAt;final String diagnostics;
        long candidateId=-1;boolean started,moving,reserveLogged,stalled;long firstSeenGameMs;
        boolean capacityExpansion,orderAccepted;String commitmentId,lastHold;
        boolean probeAttempted;String probeState;double probeProgress;long probeAt;boolean wasStalled;double probeCredits;
        BuildJob(String kind,double x,double y,long cost,long plannedAt,String diagnostics){
            this.kind=kind;this.x=x;this.y=y;this.cost=cost;this.plannedAt=plannedAt;this.diagnostics=diagnostics;
        }
    }
    private BuildJob buildJob;
    private final Set<Long> jobKnownUnits=new LinkedHashSet<Long>();
    /** Half-built sites that cannot be resumed: kept physically blocked, never planned onto again. */
    private final Map<String,Long> blockedSites=new LinkedHashMap<String,Long>();
    private long lastExpansionAttempt=-1000000L,minesCompleted,landFactoriesCompleted,expansionBlocks,factoryBlocks,lastSurplusLog=-100000;
    private boolean expansionTargetLogged,factoryTargetLogged;
    private String expansionLastBlocked;
    private long landFactoryCost=-1,lastPreferredUnitCost=-1;
    private String lastPreferredUnit;
    private boolean lastFactoryQueueNonEmpty;
    /**
     * Economy v1b (对话38 裁决 §1-§2). Two MEASURED economy constants and the long-window instruments that
     * decide whether one more land factory is worth its price.
     *
     *   income = baseIncome + incomePerMine * (completed T1 mines)      [credits per normal game second]
     *
     * Measured on four live matches (two at 1x, two at 5x), 20 constant-mine windows, R^2 = 0.999,
     * residual sigma 0.59; per-level medians 39.26 / 51.01 / 63.02 / 74.95 / 86.45 / 100.09 for 1..6 mines.
     * Payback 700 / 12.07 = 58.0 game seconds, which matches the mechanics-library prior
     * (T1 mine +8 per 0.66 s ~= 12.1, "T1 mine pays back in 58 s"). Evidence:
     *   助手交接\evidence\two_speed_economy_builder.txt   (script _analysis/two_speed_economy_builder.py)
     * These are REPLACEABLE measured constants, not laws of the game: incomePerMine behaves like a
     * mechanism constant, baseIncome is closer to a baseline of the current configuration/mode. Both are
     * overridable at the process level and the report states where the number came from.
     */
    private final double economyBaseIncome=doubleProperty("rwagent.baseIncome",26.9);
    private final double economyIncomePerMine=doubleProperty("rwagent.incomePerMine",12.07);
    private static final String ECONOMY_MODEL_SOURCE=
            "MEASURED_4_MATCHES_2_SPEEDS_T1_R2_0.999_T2_T3_ESTIMATED_FROM_FROZEN_GENERATION_RATIOS";
    /**
     * Why a long window and never an EMA: the client books a whole production BATCH at order time while the
     * game deducts per finished unit, so a 10-30 game second balance reading is polluted by that rhythm
     * (measured live: observation pairs with no spend at all show NEGATIVE income at 1-2 mines). The balance
     * identity only equals income once a whole batch cycle has passed, hence >= 150 game seconds.
     */
    private final long economyWindowGameMs=Math.max(60000,Integer.getInteger("rwagent.economyWindowMs",150000));
    private final int factorySaturationMinPct=Math.min(100,Math.max(1,Integer.getInteger("rwagent.factorySaturationPct",80)));
    private final long landFactoryTargetMax=Math.max(2,Integer.getInteger("rwagent.landFactoryTargetMax",5));
    private final long landFactoryCostDefault=Math.max(1,Integer.getInteger("rwagent.factoryCost",700));
    /** {gameTimeMs, credits} of every ACCEPTED UNIT_PRODUCTION order - the consumption meter. */
    private final List<long[]> productionLedger=new ArrayList<long[]>();
    /** {gameTimeMs, readyFactories, busyFactories} once per poll - the time-weighted utilisation meter. */
    private final List<long[]> factoryLoadSamples=new ArrayList<long[]>();
    private final List<double[]> capacitySamples=new ArrayList<double[]>();
    private final List<long[]> spendLedger=new ArrayList<long[]>();
    private long factoryTargetIncreases,factoryTargetIncreaseBlocks;
    private long lastMilitaryCapacityDecision=-100000,lastProductionRouteLog=-100000;
    private String lastCapacityBottleneck,lastProductionRouteSignature;
    private Map<String,Object> capacityEvidence=new LinkedHashMap<String,Object>();
    private boolean capacitySustained;
    private ProductionCapacity.Bottleneck capacityBottleneck=ProductionCapacity.Bottleneck.UNKNOWN;
    private long lastCapacityLog=-100000,lastProducerCapacityIncrease=-100000,lastFactoryReady=-1;
    /**
     * True between "the long-window gate raised the target" and "that factory exists". While set, the build
     * path treats the extra factory as a commitment and only checks execution conditions (credits covering
     * the price plus both hard reserves, the builder, and a legal site) - never the instantaneous
     * queue-non-empty frame that legitimately guards the ORIGINAL second factory.
     */
    private boolean factoryTargetCommitted;
    private String factoryCommitmentId;
    private String lastReadyFactoryCommitmentId;
    private String lastFactoryIncreaseGate;
    /** Diagnostics only: last seen type/position per own unit, so own_loss can say WHAT died. */
    private final Map<Long,Object[]> lastOwnUnitInfo=new LinkedHashMap<Long,Object[]>();
    /**
     * P2-C1: one builder is the single point of failure of the whole economy tree, so replacing it
     * outranks every other purchase. While it is missing, its price is reserved from the shared pool -
     * otherwise combat production keeps spending the credits the replacement needs.
     */
    private final int builderTarget=Math.max(0,Integer.getInteger("rwagent.builderTarget",1));
    private boolean builderRecoveryActive,builderOrderPending;
    private boolean capabilityLoggedEarly,capabilityLoggedLate;
    /**
     * 杈撳嚭16 搂8: diagnostics only. The air/ground capability mapping has stayed UNRESOLVED because no
     * aircraft has ever entered a 60s/600s snapshot, so the first legally visible aircraft (seen in the
     * normal vision-gated observation) triggers one extra snapshot. It never changes a decision and it
     * never asks the scout to go anywhere.
     */
    private boolean capabilityLoggedAircraft;
    private static final Set<String> AIRCRAFT_TYPES=new LinkedHashSet<String>(java.util.Arrays.asList(
            "c_interceptor","lightGunship","c_helicopter","gunShip","gunship","heavyInterceptor"));
    private long wallStartNanos;
    private long builderReserve,builderCost=-1,builderProducerId=-1,lastBuilderRecoveryLog=-100000;
    private long builderRecoveries,builderOrders;
    private long lastDeferredLog=-100000,lastDeferredFactory=-1;
    private String lastDeferredReason;
    /**
     * 杈撳嚭18 搂D: armed by {@link #reportSecondaryInvestment} when a secondary falls back to a cheaper legal
     * action, and cleared by the producer once the bridge accepted that order - so the fallback event is
     * emitted exactly once per accepted fallback order and never for a rejected one.
     */
    private Map<String,Object> pendingFallback;
    private long pendingFallbackReserve;
    /**
     * Prospecting state. The legal expansion planner only accepts a currently visible resource inside
     * 600 world units of the builder, so from home it can only ever see the opening mine. Walking the
     * builder to a resource this agent has legally observed before is what turns memory into a site:
     * the tile enters vision on arrival and the native build command then validates it as usual.
     */
    private final double expansionProspectRangeWorld=Math.max(200,Integer.getInteger("rwagent.expansionProspectRangeWorld",3000));
    private final Set<Long> triedProspects=new LinkedHashSet<Long>();
    private long prospectTile=-1,lastProspectCommand=-100000;
    private double prospectX,prospectY;
    private Map<String,Object> awaitingOrder;
    public static void main(String[] args){System.exit(new BattleClient().run(args));}
    int run(String[] args){
        File report=null;String outcome="FAIL",reason="not started",matchOutcome="ONGOING";int exit=1;
        try{
            int seconds=BattleBudget.seconds(args);
            File dir=new File("rw-agent-reports");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create reports directory");
            try(RandomAccessFile file=new RandomAccessFile(new File(dir,"economy.lock"),"rw");FileChannel channel=file.getChannel();FileLock lock=channel.tryLock()){
                if(lock==null)throw new IllegalStateException("Another controller is running");
                report=new File(dir,"battle-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8)+".jsonl");
                output=ReportFiles.open(report);log=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));
                Map<String,Object> health=get("/health","health");
                if(!"0.07-alpha1".equals(health.get("version")))throw new IllegalStateException("Install 0.07-alpha1 and restart");
                provenance(health);
                Map<String,Object> state=observe();session=(String)state.get("sessionId");startTime=time;
                if(StrategyDirector.number(health,"strategyContractVersion",0)>=1
                        &&!"false".equalsIgnoreCase(System.getProperty("rwagent.globalStrategy","true"))
                        &&System.getProperty("rwagent.mobileUnitHardCap")==null)mobileUnitHardCap=128;
                strategy.enable(health,mobileUnitHardCap);
                Map<String,Object> home=null;for(Map<String,Object> u:units(state))if(alive(u)){seenOwn.add(id(u));if("commandCenter".equals(u.get("type")))home=u;}
                if(home==null)throw new IllegalStateException("Own commandCenter required at battle start");homeX=n(home,"x");homeY=n(home,"y");
                event("battle_config","{\"gameSeconds\":"+seconds+",\"armyCap\":"+activeArmyTarget
                        +",\"activeArmyTarget\":"+activeArmyTarget+",\"reserveTarget\":"+reserveTarget
                        +",\"mobileUnitHardCap\":"+mobileUnitHardCap
                        +",\"mineTarget\":"+mineTarget+",\"expansionIntervalGameMs\":"+expansionIntervalGameMs
                        +",\"expansionBuildRangeWorld\":"+expansionBuildRangeWorld
                        +",\"landFactoryTarget\":"+landFactoryTarget+",\"rearFactoryRadiusWorld\":"+rearFactoryRadiusWorld
                        +",\"builderTarget\":"+builderTarget
                        +",\"reachabilitySamplingEnabled\":"+reachabilitySampleEnabled
                        +",\"reachabilitySampleIntervalSeconds\":"+(reachabilitySampleIntervalMs/1000)
                        +",\"reachabilityRepresentativeTypeLimit\":"+reachabilityTypeLimit
                        +",\"noProgressWindowMs\":"+noProgressWindowMs
                        +",\"noProgressCooldownMs\":"+noProgressCooldownMs
                        +",\"noProgressEngageRange\":"+noProgressEngageRange
                        // Report the default tolerance up front so a 5x report can be read without
                        // having to infer it; the measured values land in the summary at the end.
                        +",\"noProgressAttentionGapMs\":"+noProgressAttentionGapMs()
                        +",\"decisionIntervalGameMs\":1000,\"minimumCommandGapGameMs\":1000,\"openingApmLimited\":false"
                        // Economy v1b: the model and its thresholds travel with the configuration, because
                        // they are decision inputs now, not diagnostics.
                        +",\"economyBaseIncome\":"+economyBaseIncome
                        +",\"economyIncomePerMine\":"+economyIncomePerMine
                        +",\"economyWindowGameMs\":"+economyWindowGameMs
                        +",\"factorySaturationMinPct\":"+factorySaturationMinPct
                        +",\"landFactoryTargetMax\":"+landFactoryTargetMax
                        +",\"reconEnabled\":"+reconEnabled+",\"reconFrontierEnabled\":"+reconFrontierEnabled
                        +",\"reconMinReadyArmy\":7,\"reconThreatBufferWorld\":80"
                        +",\"executionContractVersion\":1,\"executionPlayerScope\":"+Json.quote(execution.stamp().player)
                        +",\"targetEligibilityVersion\":1,\"targetSuppressionRelease\":\"NEWER_VISIBLE_COMPATIBLE_EVIDENCE\""
                        +",\"globalStrategyEnabled\":"+strategy.enabled()+",\"strategyContractVersion\":1"
                        +",\"engagementAssessmentContract\":\"COMMITTED_FORMATION_V1\""
                        +",\"pollWallTimeMs\":"+Math.max(60,Math.min(1000,Integer.getInteger("rwagent.pollMs",500)))
                        +",\"battleSafetyGameSeconds\":"+BattleBudget.gameLimit()+",\"battleSafetyWallSeconds\":"+BattleBudget.wallLimit()
                        +",\"reportCommitMemoryMiB\":8,\"reportDiskLimitMiB\":"+Math.max(64,Math.min(4096,Integer.getInteger("rwagent.reportLimitMiB",1024)))
                        +",\"reconExpendableHpMaxFraction\":0.45,\"reconFrontierMinMainForce\":6"
                        +",\"localArmyContract\":\"BOUNDED_LOCAL_COHORTS_V1\",\"localArmyMaxCohorts\":4"
                        +",\"localArmyMaxMembers\":48,\"localArmyFormationMinimum\":6,\"localArmyReleaseBelow\":3"
                        +",\"localArmyFormationRadiusWorld\":400,\"localArmyTargetRadiusWorld\":1400"
                        +",\"localArmyOrderIntervalGameMs\":8000,\"localArmyMemoryHoldGameMs\":30000"
                        +"}");
                long wallStart=System.nanoTime();wallStartNanos=wallStart;
                startCredits=n(obj(state.get("player")),"credits");
                while(true){
                    state=observe();lastState=state;Map<String,Object> match=obj(state.get("match"));matchOutcome=(String)match.get("outcome");                    if(!"ONGOING".equals(matchOutcome)){event("match_terminal",json(match));outcome="PASS";reason="Native result screen: "+matchOutcome;exit=0;break;}
                    if(time-startTime>=seconds*1000L){outcome="PARTIAL";reason="Game-time budget reached without native result";exit=2;break;}
                    if((System.nanoTime()-wallStart)/1e9>BattleBudget.wallLimit())throw new IllegalStateException("Configured wall-time safety limit");
                    account(state);
                    observeReconMotion(state);
                    if(time-lastDecision>=1000){
                        // Speed readiness (对话35): the decision GATE is game time, but the loop is wall
                        // time, so the real decision interval in game ms grows with the game speed. It is
                        // measured here instead of assumed, and it is what the no-progress attention
                        // tolerance is derived from.
                        if(lastDecision>=0)noteDecisionInterval(time-lastDecision);
                        lastDecision=time;Map<String,Object> enemies=get("/combat/observe","combat_observation");check(enemies);
                        lastEnemies=enemies;
                        String scoutPath="/scout/observe";
                        if(reconEnabled&&reconTask!=null&&reconTask.frontier())
                            scoutPath+="?tile="+reconTask.frontierTile+"&since="
                                    +reconTask.createdAt;
                        Map<String,Object> scoutState=get(scoutPath,"scout_visibility");check(scoutState);
                        lastScout=scoutState;
                        confirm(state);updatePending(state);
                        updateMilitaryUrgency(state,enemies);
                        strategy.noteUnobservedCombatSlots(artilleryLedger.unobservedSlots(state)+ordinaryUnobservedSlots(state));
                        strategy.observe(state,enemies,scoutState,mainArmy(state),startTime,seconds*1000L-(time-startTime),
                                modelledIncomePerGameSecond(state),productionConsumptionPerGameSecond(),strategyReserve(),buildJob!=null);
                        if(searchTarget!=null&&strategy.rejected(searchTarget.sourceEnemyId)){
                            event("search_target_suppressed",json(StrategyDirector.map("targetId",searchTarget.targetId,
                                "sourceEnemyId",searchTarget.sourceEnemyId,"reason","TERRAIN_APPROACH_REJECTION","gameTimeMs",time)));
                            searchTarget=null;searchNeedsOrder=false;
                        }
                        if(reconEnabled)updateRecon(state,enemies);
                        // Consume negative evidence even when Economy/Recon spends this tick's command.
                        updateTargetSuppressions(state,enemies);
                        observeLocalArmies(state);
                        observeLocalCrisis(state,enemies);
                        observeLocalArmies(state); // Newly leased responders leave main membership immediately.
                        builderRecovery(state);
                        issueCriticalMainRetreat(state);
                        issueLocalCrisis(state,enemies);
                        issueIdleLocalRecovery(state,enemies);
                        evaluateProductionCapacity(state);
                        strategy.act(strategyReserve(),factoryTargetCommitted?countType(state,"landFactory"):landFactoryTarget);
                        economyLane(state);
                        if(execution.ready(time)){
                            // A frontier scout with a refreshed own position needs its first order
                            // before its old army attack-move can carry it away. Rechecks and later
                            // scout orders keep the ordinary production grace period.
                            if(reconEnabled&&issueReconRecall(state)){}
                            else if(reconEnabled&&issueReconAcquisition(state)){}
                            else if(reconEnabled&&reconTask!=null
                                    &&(time-reconTask.createdAt>=10000
                                            ||(reconTask.frontier()&&reconTask.unitId<0
                                                    &&!reconTask.awaitFreshOwnObservation))
                                    &&issueReconOrder(state)){}
                            else if(!produce(state)){
                                if(!reconEnabled||!issueReconOrder(state))tactics(state,enemies);
                            }
                        }
                        reportLocalArmyState(state,enemies);
                    }
                    if(time-lastHeartbeat>=10000){lastHeartbeat=time;heartbeat(state);}
                    long battleSeconds=(time-startTime)/1000L;
                    if((!capabilityLoggedEarly&&battleSeconds>=60)||(!capabilityLoggedLate&&battleSeconds>=600)){
                        if(battleSeconds>=600)capabilityLoggedLate=true;else capabilityLoggedEarly=true;
                        capabilitySnapshot("SCHEDULED");
                    }
                    if(!capabilityLoggedAircraft&&firstVisibleAircraft(state)!=null){
                        capabilityLoggedAircraft=true;
                        capabilitySnapshot("FIRST_VISIBLE_AIRCRAFT");
                    }
                    // 杈撳嚭21 搂8: raw reachability sampling for semantic calibration. Rate-limited and
                    // representative-only; it never feeds a decision.
                    if(reachabilitySampleEnabled&&time-lastReachabilitySample>=reachabilitySampleIntervalMs)
                        reachabilitySample(state);
                    Thread.sleep(Math.max(60,Math.min(1000,Integer.getInteger("rwagent.pollMs",500))));
                }
            }
        }catch(Exception err){reason=err.toString();System.err.println(reason);}
        finally{
            if(log!=null)try{
                strategy.close();
                if(reconAcquisition!=null)cancelReconAcquisition("CONTROLLER_ENDED");
                if(reconTask!=null)releaseOwnership(reconTask.taskId,"CONTROLLER_ENDED");
                releaseLocalCrisis("CONTROLLER_ENDED");
                execution.clear();
                // Economy v1a guardrail. An intent still pending when the match ends is NORMAL: it was
                // opened seconds ago and its timeout has not arrived. What would be a defect is a reserve
                // that OUTLIVED its deadline, so that is what the stuck event now means, and a pending
                // intent is released here as MATCH_ENDED so the summary field stays unambiguous.
                boolean pendingAtEnd=investmentReserve>0;
                boolean outlivedDeadline=investment!=null&&time>investment.deadline;
                if(outlivedDeadline)event("investment_reserve_stuck",
                        "{\"reserve\":"+investmentReserve+",\"ageMs\":"+(time-investment.chosenAt)
                        +",\"deadlineMs\":"+investment.deadline+",\"gameTimeMs\":"+time+"}");
                else if(pendingAtEnd)releaseInvestment("MATCH_ENDED");
                event("summary","{\"outcome\":"+Json.quote(outcome)+",\"reason\":"+Json.quote(reason)+",\"phase\":\"battle\",\"matchOutcome\":"+Json.quote(matchOutcome)
                    +",\"commands\":"+commands+",\"observations\":"+observations+",\"ownLosses\":"+losses+",\"newCombatUnits\":"+recruits
                    +",\"attackOrders\":"+attacks+",\"attackOrdersConfirmed\":"+confirmed+",\"retreatOrders\":"+retreats+",\"upgradesCompleted\":"+techs
                    +",\"completedMines\":"+minesCompleted+",\"newMines\":"+minesCompleted+",\"mineTarget\":"+mineTarget
                    // Economy v0 acceptance: mines above the floor, and how often an above-floor
                    // expansion was refused (with the reason on the event).
                    +",\"minesBeyondFloor\":"+minesBeyondFloor+",\"expansionRefusals\":"+expansionRefusals
                    +",\"expansionBlocked\":"+expansionBlocks
                    +",\"completedFactories\":"+landFactoriesCompleted+",\"newFactories\":"+landFactoriesCompleted
                    +",\"landFactoryTarget\":"+landFactoryTarget+",\"factoryBlocks\":"+factoryBlocks
                    +",\"builderTarget\":"+builderTarget+",\"builderRecoveries\":"+builderRecoveries
                    +",\"builderOrders\":"+builderOrders
                    +",\"battleGameTimeMs\":"+(time-startTime)
                    +",\"gameSecondsPerWallSecond\":"+measuredSpeed()
                    // 杈撳嚭30 搂4 acceptance record: how often the army gave up on a stalled target, how
                    // often it came back, and how much target switching the match actually contained.
                    +",\"noProgressWindowMs\":"+noProgressWindowMs
                    +",\"noProgressCooldownMs\":"+noProgressCooldownMs
                    +",\"targetNoProgressTriggers\":"+noProgressTriggers
                    +",\"targetRetries\":"+noProgressRetries
                    +",\"targetSuppressionsStarted\":"+targetSuppressionsStarted
                    +",\"targetSuppressionsReleased\":"+targetSuppressionsReleased
                    +",\"targetSuppressedSelections\":"+targetSuppressedSelections
                    +",\"targetSuppressionsAtEnd\":"+targetSuppressions.size()
                    +",\"targetSwitches\":"+targetSwitches
                    // Speed readiness (对话35): all of these are measured, not assumed. A report without
                    // them cannot answer "was the client still reacting at this game speed?".
                    +",\"effectiveDecisionIntervalGameMs\":"+effectiveDecisionIntervalGameMs()
                    +",\"noProgressAttentionGapMs\":"+noProgressAttentionGapMs()
                    +",\"maxObservedGameTimeJump\":"+maxObservedGameTimeJump
                    +",\"observationCount\":"+observations
                    // Economy v1a (对话37 共识 §8): the spend ledger makes income measurable as
                    // "delta credits + known spend" over game time, which is what calibrates the
                    // mechanics-library prior (T1 mine ~= 700/58 ~= 12.1 per normal game second).
                    +",\"spendTotal\":"+spendTotal
                    +",\"spendByCategory\":"+spendJson()
                    +",\"measuredIncomePerGameSecond\":"+measuredIncome()
                    +",\"investmentIntentions\":"+investmentIntentions
                    +",\"investmentCompletions\":"+investmentCompletions
                    +",\"investmentCancellations\":"+investmentCancellations
                    // An investment pending when the match ended is not a defect; a reserve that outlived
                    // its own deadline is. The stuck event (emitted above) marks the latter.
                    +",\"investmentReserveAtEnd\":"+investmentReserve
                    +",\"investmentPendingAtEnd\":"+Boolean.toString(pendingAtEnd)
                    +",\"finalUrgency\":"+Json.quote(militaryUrgency)
                    +",\"reconEnabled\":"+reconEnabled
                    +",\"reconTasksCreated\":"+reconCreated+",\"reconOrdersQueued\":"+reconOrders
                    +",\"reconOrdersObserved\":"+reconOrdersObserved
                    +",\"reconMovesCompletedBetweenSamples\":"+reconMovesCompletedBetweenSamples
                    +",\"frontierTasksSatisfiedByTeamVision\":"+frontierTasksSatisfied
                    +",\"reconResolvedAfterObservedMove\":"+reconResolvedAfterObservedMove
                    +",\"reconReacquired\":"+reconReacquired+",\"reconSitesCleared\":"+reconCleared
                    +",\"reconBlocked\":"+reconBlocked+",\"reconTaskPendingAtEnd\":"+(reconTask!=null)
                    +",\"reconFrontierEnabled\":"+reconFrontierEnabled
                    +",\"frontierTasksCreated\":"+frontierTasksCreated
                    +",\"frontierTasksRefreshed\":"+frontierTasksRefreshed
                    +",\"frontierTasksAdvanced\":"+frontierTasksAdvanced
                    +",\"frontierTasksBlocked\":"+frontierTasksBlocked
                    +",\"frontierTasksPreempted\":"+frontierTasksPreempted
                    +",\"expendableTransfers\":"+expendableTransfers
                    +",\"expendableScoutsAtEnd\":"+expendableScouts.size()
                    // Economy v1b (对话38 裁决 §6): the acceptance record for the throughput gate - how
                    // many factories the match added on its own, and what the two long-window rates and the
                    // utilisation integral read at the end. A report that never raised the target says so
                    // here AND in factory_target_increase_blocked, so silence is never the only evidence.
                    +",\"factoryTargetIncreases\":"+factoryTargetIncreases
                    +",\"factoryTargetIncreaseBlocks\":"+factoryTargetIncreaseBlocks
                    +",\"landFactoryTargetAtEnd\":"+landFactoryTarget
                    +",\"factoryTargetCommittedAtEnd\":"+Boolean.toString(factoryTargetCommitted)
                    +",\"landFactoryTargetMax\":"+landFactoryTargetMax
                    +",\"factorySaturationPct\":"+round2(factoryLoadWindow()[0])
                    +",\"factorySaturationMinPct\":"+factorySaturationMinPct
                    +",\"economyBaseIncome\":"+economyBaseIncome
                    +",\"economyIncomePerMine\":"+economyIncomePerMine
                    +",\"economyModelSource\":"+Json.quote(ECONOMY_MODEL_SOURCE)
                    +",\"minesReadyAtEnd\":"+countReadyExtractors(lastState)
                    +",\"productionConsumptionPerGameSecond\":"+round1(productionConsumptionPerGameSecond())
                    +",\"sustainableSurplusPerGameSecond\":"+round1(sustainableSurplusPerGameSecond(lastState))
                    +",\"productionCapacity\":"+json(capacityEvidence)
                    +",\"strategy\":"+json(strategy.summary())
                    +"}");ReportFiles.finish(log,output,report);
            }catch(Exception err){exit=1;System.err.println("Report commit failed: "+err);}
        }
        System.out.println(outcome+" / "+matchOutcome+": "+reason);if(report!=null)System.out.println("Report: "+report.getAbsolutePath());return exit;
    }
    /**
     * Read-only provenance for the report itself (杈撳嚭13 搂10): the agent JAR the game actually loaded,
     * taken from the bridge rather than inferred from file names or install times.
     */
    private void provenance(Map<String,Object> health)throws Exception{
        if(health==null)return;
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        Object own=health.get("provenance");
        Map<String,Object> source=own instanceof Map?(Map<String,Object>)own:health;
        for(String key:new String[]{"agentVersion","agentJar","agentJarSha256","gameLibJarSha256","workingDirectory","javaVersion"})
            if(source.containsKey(key))data.put(key,source.get(key));
        // Economy v1b (对话38 裁决 §1): a strategy number that came from measurement must travel with its
        // provenance, otherwise the next reader cannot tell a calibrated constant from a guess.
        data.put("economyModelSource",ECONOMY_MODEL_SOURCE);
        data.put("economyBaseIncome",Double.valueOf(economyBaseIncome));
        data.put("economyIncomePerMine",Double.valueOf(economyIncomePerMine));
        data.put("economyWindowGameMs",economyWindowGameMs);
        data.put("factorySaturationMinPct",factorySaturationMinPct);
        data.put("landFactoryTargetMax",landFactoryTargetMax);
        data.put("gameTimeMs",time);
        event("report_provenance",json(data));
    }

    /**
     * 杈撳嚭21 搂8 / 杈撳嚭22 搂3: raw reachability calibration.
     *
     * The primary sample is bound to the target the Agent is actually acting on
     * (sampleSource = SELECTED_TACTICAL_TARGET): the visible enemy nearest to the last ordered attack-move
     * point, which is what ENGAGE_VISIBLE / attackMove is aimed at. Only when that probe finds nothing does
     * it fall back to a visible target with sampleSource = POSITIVE_CONTROL_VISIBLE_TARGET, so a normal
     * ground fight still yields calibration data. The two sources are never mixed in one event.
     *
     * Rate-limited by the caller; at most reachabilityTypeLimit representatives per sample; never used by
     * any decision.
     */
    private void reachabilitySample(Map<String,Object> state)throws Exception{
        if(lastEnemies==null)return;
        List<Map<String,Object>> visible=list(lastEnemies,"visibleEnemies");
        if(visible.isEmpty())return;
        Map<String,Object> target=null;
        String source=null;
        // 杈撳嚭23 搂2: prefer the enemy ID the last attack order was actually issued against.
        if(selectedTargetEnemyId!=null){
            for(Map<String,Object> enemy:visible)
                if(Long.valueOf((long)n(enemy,"id")).equals(selectedTargetEnemyId)){target=enemy;source="DIRECT_SELECTED_TARGET_ID";}
        }
        if(target==null&&lastTactic>0){
            // Fallback: the visible enemy nearest the last ordered attack-move point.
            double best=Double.MAX_VALUE;
            for(Map<String,Object> enemy:visible){
                double distance=Math.hypot(n(enemy,"x")-targetX,n(enemy,"y")-targetY);
                if(distance<=160&&distance<best){target=enemy;best=distance;source="POSITION_MATCH_FALLBACK_160";}
            }
        }
        if(target==null){
            for(Map<String,Object> enemy:visible)if(Boolean.TRUE.equals(enemy.get("building"))){target=enemy;break;}
            if(target==null)target=visible.get(0);
            source="POSITIVE_CONTROL_VISIBLE_TARGET";
        }
        String targetId=String.valueOf(target.get("id"));
        // 杈撳嚭21 搂8: finite rate limit. A new selected visible target is sampled at most once per interval,
        // so a normal match yields positive controls without path-query spam.
        if(targetId.equals(lastReachabilityTarget)&&time-lastReachabilitySample<reachabilitySampleIntervalMs)return;
        lastReachabilityTarget=targetId;lastReachabilitySample=time;
        Map<String,Long> representatives=new LinkedHashMap<String,Long>();
        for(Map<String,Object> unit:army(state)){
            String type=(String)unit.get("type");
            if(type==null||representatives.containsKey(type))continue;
            if(!"c_tank".equals(type)&&!"tank".equals(type)&&!"heavyTank".equals(type))continue;
            representatives.put(type,Long.valueOf(id(unit)));
        }
        if(representatives.isEmpty())return;
        // 杈撳嚭26 搂8: pure Agent-side metadata so a later reader can line a sample up with the record
        // without changing order, request count, parameters or any behaviour.
        long batchId=++reachabilityBatchCounter;
        int ordinal=0;
        int index=0;
        for(Map.Entry<String,Long> entry:representatives.entrySet()){
            if(index++>=reachabilityTypeLimit)break;
            ordinal++;
            Map<String,Object> raw=null;String failure=null;
            // 杈撳嚭23 搂6: one full float dump per combat type, on its first sample only.
            boolean fullDump=fullFloatDumped.add(entry.getKey());
            long requestWall=System.currentTimeMillis();
            try{
                raw=optionalGet("/combat/reachability?unitId="+entry.getValue()+"&targetId="+targetId
                                +(fullDump?"&dump=full":""),"reachability_raw");
            }catch(Exception error){
                failure=String.valueOf(error.getMessage());
            }
            Map<String,Object> data=new LinkedHashMap<String,Object>();
            data.put("sampleBatchId",Long.valueOf(batchId));
            data.put("representativeIndex",Integer.valueOf(index-1));
            data.put("requestOrdinal",Integer.valueOf(ordinal));
            data.put("requestWallTimeMs",Long.valueOf(requestWall));
            data.put("gameTimeMs",Long.valueOf(time));
            data.put("sampleSource",source);
            data.put("attackerType",entry.getKey());
            data.put("attackerId",entry.getValue());
            data.put("fullFloatDump",Boolean.valueOf(fullDump));
            data.put("targetId",target.get("id"));
            data.put("targetType",target.get("type"));
            data.put("targetBuilding",target.get("building"));
            if(failure!=null)data.put("failure",failure);
            if(raw!=null)data.put("raw",raw);
            event("report_reachability",json(data));
        }
        event("reachability_sample","{\"gameTimeMs\":"+time+",\"sampleBatchId\":"+batchId
                +",\"sampleSource\":"+Json.quote(source)
                +",\"targetId\":"+Json.quote(targetId)
                +",\"targetType\":"+Json.quote(String.valueOf(target.get("type")))
                +",\"representatives\":"+representatives.size()
                +",\"requestOrdinals\":"+ordinal
                +",\"limit\":"+reachabilityTypeLimit+"}");
    }

    /**
     * 杈撳嚭13 搂10: a read-only capability snapshot, taken at most twice per match. It exists so the
     * obfuscated air/ground flags can be mapped with two orthogonal anchors - our own c_tank (known
     * ground-only) plus whatever anti-air unit is visible - and it never changes a decision.
     */
    /**
     * 杈撳嚭16 搂8: the first aircraft that appears in a legal observation (own or visible enemy). This is a
     * read-only probe over data the Agent already received - it never issues a command and never changes
     * scout behaviour.
     */
    private String firstVisibleAircraft(Map<String,Object> state)throws Exception{
        Map<String,Object> observation=lastEnemies!=null?lastEnemies:get("/combat/observe","combat_observation");
        for(Map<String,Object> u:list(observation,"visibleEnemies"))
            if(AIRCRAFT_TYPES.contains(u.get("type")))return (String)u.get("type");
        for(Map<String,Object> u:units(state))
            if(alive(u)&&AIRCRAFT_TYPES.contains(u.get("type")))return (String)u.get("type");
        return null;
    }

    private void capabilitySnapshot(String trigger)throws Exception{
        Map<String,Object> snapshot;
        try{
            snapshot=optionalGet("/combat/capabilities","capability_snapshot");
        }catch(Exception error){
            // A diagnostic must never end the match: record the failure and carry on.
            event("capability_snapshot_failed","{\"reason\":"+Json.quote(String.valueOf(error.getMessage()))+",\"gameTimeMs\":"+time+"}");
            return;
        }
        if(snapshot==null)return;
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("gameSeconds",(time-startTime)/1000L);
        data.put("trigger",trigger);
        data.put("units",snapshot.get("units"));
        data.put("unavailable",snapshot.get("unavailable"));
        event("capability_snapshot",json(data));
    }

    /**
     * Measured game speed (杈撳嚭13 搂10): game seconds per wall-clock second for the battle phase. This is
     * how a x5 run is recognised from the report itself instead of being assumed.
     */
    /** The ledger as JSON, plus the ledger-derived income estimate. Both are game-time based. */
    private String spendJson(){
        StringBuilder s=new StringBuilder("{");
        boolean first=true;
        for(Map.Entry<String,long[]> entry:spendByCategory.entrySet()){
            if(!first)s.append(',');
            first=false;
            s.append('"').append(entry.getKey()).append("\":{\"orders\":").append(entry.getValue()[0])
             .append(",\"cost\":").append(entry.getValue()[1]).append('}');
        }
        return s.append('}').toString();
    }
    /**
     * Income over the battle phase, in credits per NORMAL GAME second: (delta credits + known spend) /
     * game-time delta. Game time, not wall time, so a 5x match yields the same number as a 1x one.
     */
    private double measuredIncome(){
        long gameMs=time-startTime;
        if(gameMs<=0||startCredits<0)return 0;
        double credits=n(obj(lastState.get("player")),"credits");
        return Math.round((credits-startCredits+spendTotal)/(gameMs/1000.0)*100.0)/100.0;
    }
    private Map<String,Object> lastState=new LinkedHashMap<String,Object>();
    /**
     * Measured game speed (杈撳嚭13 搂10): game seconds per wall-clock second for the battle phase. This is
     * how a x5 run is recognised from the report itself instead of being assumed.
     */
    private double measuredSpeed(){        double wall=(System.nanoTime()-wallStartNanos)/1e9;
        return wall>1?Math.round(((time-startTime)/1000.0/wall)*100.0)/100.0:0;
    }

    private void account(Map<String,Object> state)throws Exception{
        Set<Long> living=new HashSet<Long>();for(Map<String,Object> u:units(state))if(alive(u)){
            long id=id(u);living.add(id);
            // Diagnostics only (对话38 裁决 §5): remember what each own unit was, so own_loss can say WHAT
            // died instead of only that something did. Never read by a decision.
            lastOwnUnitInfo.put(Long.valueOf(id),new Object[]{u.get("type"),Double.valueOf(n(u,"x")),Double.valueOf(n(u,"y"))});
            if(seenOwn.add(id)&&armed(u)){recruits++;event("combat_unit_observed",json(u));}
        }
        for(long id:seenOwn)if(!living.contains(id)&&lost.add(id)){losses++;lastOwnLossAt=time;
            Object[] info=lastOwnUnitInfo.remove(Long.valueOf(id));
            event("own_loss","{\"unitId\":"+id
                +",\"type\":"+(info==null?"null":Json.quote(String.valueOf(info[0])))
                +",\"x\":"+(info==null?-1:((Double)info[1]).doubleValue())
                +",\"y\":"+(info==null?-1:((Double)info[2]).doubleValue())
                +",\"gameTimeMs\":"+time+"}");}
        observeFactoryLoad(state);
    }
    private void updatePending(Map<String,Object> state)throws Exception{
        Iterator<Map.Entry<Long,Pending>> it=pending.entrySet().iterator();while(it.hasNext()){
            Map.Entry<Long,Pending> entry=it.next();Pending p=entry.getValue();Map<String,Object> f=find(state,entry.getKey());
            if(f==null||!alive(f)){event("production_interrupted","{\"factoryId\":"+entry.getKey()+"}");it.remove();continue;}
            if(n(f,"productionQueue")>0)p.active=true;
            if(p.active&&n(f,"productionQueue")==0){
                if("upgrade".equals(p.type)){
                    if(n(f,"techLevel")<=p.tier)throw new IllegalStateException("Upgrade queue emptied without native tier increase");
                    techs++;event("upgrade_completed","{\"factoryId\":"+entry.getKey()+",\"tier\":"+n(f,"techLevel")+"}");
                }
                event("production_queue_finished","{\"factoryId\":"+entry.getKey()+",\"type\":"+Json.quote(p.type)+"}");it.remove();
            }else if(time-p.started>180000)throw new IllegalStateException("Native production did not finish within 180 game seconds");
        }
    }
    private boolean produce(Map<String,Object> state)throws Exception{
        List<Map<String,Object>> army=army(state);
        int mainCount=mainArmy(state).size();
        if(!reserveAnnounced && mainCount>=activeArmyTarget && reserveTarget>0) {
            reserveAnnounced=true;
            Map<String,Object> data=new LinkedHashMap<String,Object>();
            data.put("activeArmy",mainCount);data.put("totalArmedUnits",army.size());data.put("activeArmyTarget",activeArmyTarget);
            data.put("reserveTarget",reserveTarget);data.put("mobileUnitHardCap",mobileUnitHardCap);
            event("production_reserve_started",json(data));
        }
        Map<String,Object> menu=get("/combat/production","production_menu");check(menu);
        List<Map<String,Object>> factories=list(menu,"factories");
        observeProductionRoutes(factories);
        double ordinaryQuote=-1;
        for(Map<String,Object> factory:factories)for(Map<String,Object> action:list(factory,"actions"))
            if("heavyTank".equals(action.get("type"))&&n(action,"cost")>0)ordinaryQuote=n(action,"cost");
        if(ordinaryQuote<0)for(Map<String,Object> factory:factories)for(Map<String,Object> action:list(factory,"actions"))
            if(("c_tank".equals(action.get("type"))||"tank".equals(action.get("type")))&&n(action,"cost")>0)ordinaryQuote=n(action,"cost");
        strategy.noteOrdinaryNativePrice(ordinaryQuote);
        List<Long> producerIds=new ArrayList<Long>();for(Map<String,Object> factory:factories)producerIds.add(id(factory));
        execution.observeProductionActors(execution.stamp(),producerIds);
        // P2-B2 Global Production Budget (杈撳嚭13 搂3-搂7). The mainline unit and its primary producer are
        // derived from the native menus, never from factory ids or creation order: the mainline is the
        // highest scoring action any producer offers, and the primary is the first producer offering it.
        String mainlineType=null;long mainlineCost=-1;int mainlineScore=-1;long primaryProducerId=-1;
        for(Map<String,Object> f:factories){
            Map<String,Object> pref=preferredAction(f,state,mainCount);
            if(pref==null)continue;
            String type=(String)pref.get("type");int score=unitScore(type);
            if(score>mainlineScore){mainlineScore=score;mainlineType=type;mainlineCost=((Number)pref.get("cost")).longValue();}
        }
        for(Map<String,Object> f:factories){
            if(mainlineType!=null&&offersType(f,mainlineType)){primaryProducerId=id(f);break;}
        }
        long primaryReserve=0;
        for(Map<String,Object> f:factories){
            if(id(f)!=primaryProducerId)continue;
            boolean busy=n(f,"queue")!=0||pending.containsKey(id(f))||strategy.pending(id(f));
            Map<String,Object> pref=preferredAction(f,state,mainCount);
            boolean affordable=pref!=null&&Boolean.TRUE.equals(pref.get("affordable"));
            // The reserve is only held while the primary is idle and waiting for its mainline unit; once it
            // is producing, secondaries may spend the money instead of everyone standing still.
            if(!busy&&!affordable)primaryReserve=Math.max(0,mainlineCost);
        }
        boolean idleFactory=false;String idleReason=null;boolean anyQueueBusy=false;
        for(Map<String,Object> f:factories){
            long fid=id(f);
            // A fallback armed for an earlier factory must never attach to this factory's order.
            pendingFallback=null;
            if(n(f,"queue")!=0||pending.containsKey(fid)||strategy.pending(fid)){
                // P2-B: a busy factory is the saturation signal itself, so record which unit it would
                // build next even though no order is placed on this branch.
                anyQueueBusy=true;
                Map<String,Object> pref=preferredAction(f,state,mainCount);
                if(pref!=null){lastPreferredUnit=(String)pref.get("type");lastPreferredUnitCost=((Number)pref.get("cost")).longValue();}
                continue;
            }
            idleFactory=true;
            boolean primary=fid==primaryProducerId;
            // 杈撳嚭8 搂4: the preferred unit is what the scoring would buy if money were not a factor; the
            // chosen one is what is actually affordable. When they differ, the money decision is recorded.
            Map<String,Object> preferred=preferredAction(f,state,mainCount);
            // The price is a property of the native menu, not of having queued something, so it is learned
            // here too. Learning it only on the busy branch was a chicken-and-egg trap: an investment
            // reserve established before the first order blocked that order, so the factory never looked
            // busy, the price stayed unknown, and the surplus test could neither pass nor refuse.
            if(preferred!=null){lastPreferredUnit=(String)preferred.get("type");lastPreferredUnitCost=((Number)preferred.get("cost")).longValue();}
            Map<String,Object> chosen=null;int priority=-1;
            for(Map<String,Object> a:list(f,"actions")){
                if(!Boolean.TRUE.equals(a.get("affordable")))continue;String type=(String)a.get("type");int score=-1;
                if(("tank".equals(type)||"c_tank".equals(type))&&army.size()<productionLimit()&&!wouldBreachMineReserve(state,n(a,"cost"))&&!wouldBreachInvestmentReserve(state,n(a,"cost")))score=1;
                if("heavyTank".equals(type)&&army.size()<productionLimit()&&!wouldBreachMineReserve(state,n(a,"cost"))&&!wouldBreachInvestmentReserve(state,n(a,"cost")))score=3;
                if("upgrade".equals(type)&&mainCount>=6&&n(obj(state.get("player")),"credits")>=n(a,"cost")
                        &&!wouldBreachCapabilityReserve(state,n(a,"cost")))score=5;
                if(score>priority){priority=score;chosen=a;}
            }
            SurplusSpendingPolicy.Decision surplus=null;boolean qualitySelected=false;
            if(strategy.enabled()&&(chosen==null||!"upgrade".equals(chosen.get("type")))){
                int ordinary=0;for(Map<String,Object> unit:mainArmy(state))if(!SurplusSpendingPolicy.PRODUCT.equals(unit.get("type")))ordinary++;
                surplus=SurplusSpendingPolicy.evaluate(new SurplusSpendingPolicy.Inputs(state,lastEnemies,f,artilleryLedger,
                    strategy.armyTarget(),mobileUnitHardCap,ordinary,strategy.committedArmedForPolicy(),
                    strategyReserve()+strategy.capabilityReserve()+primaryReserve,URGENCY_EMERGENCY.equals(militaryUrgency)));
                Long last=lastSurplusEvaluation.get(fid);
                if(surplus.selected||last==null||time-last>=10000){
                    Map<String,Object> evidence=new LinkedHashMap<String,Object>(surplus.evidence);evidence.put("gameTimeMs",time);
                    event("surplus_spending_evaluated",json(evidence));lastSurplusEvaluation.put(fid,time);
                }
                if(surplus.selected){chosen=surplus.action;qualitySelected=true;}
            }
            if(chosen==null){
                Map<String,Object> blocked=reserveBlockedCandidate(f,state);
                if(blocked!=null)reportProductionDeferred(f,blocked,state,"BUILDER_RECOVERY",builderReserve);
                // Economy v1a acceptance: a normal production order held back precisely because the money
                // is committed to a chosen investment. This event is the middle link of the success chain.
                else if(investmentReserve>0&&investment!=null)
                    reportProductionDeferred(f,investmentCandidate(f),state,"INVESTMENT",investmentReserve);
                else if(strategy.capabilityReserve()>0)
                    reportProductionDeferred(f,investmentCandidate(f),state,"CAPABILITY_PURCHASE",strategy.capabilityReserve());
                if(idleReason==null)idleReason=army.size()>=productionLimit()?
                        (strategy.enabled()&&strategy.safetyCapacityAvailable()?"STRATEGY_ARMY_TARGET_REACHED":"MOBILE_UNIT_HARD_CAP_REACHED"):"NO_AFFORDABLE_ACTION";
                continue;
            }
            // 杈撳嚭15 搂2/搂3: a secondary upgrade is an opportunistic surplus investment, never a long-term
            // savings target. If it cannot be paid for without touching the primary reserve, it is
            // deferred and the factory still checks the lower-cost legal combat actions.
            // 杈撳嚭16 搂2B / 杈撳嚭18 搂D: this block and the banking block below both reach the same decision.
            // The deferral is a throttled *state* event, but the fallback is a *behaviour* event: it is
            // emitted once per fallback order that the bridge actually accepted, so
            // "successful fallback queue orders == SECONDARY_INVESTMENT_FALLBACK events" holds with no
            // reader-side dedup and no 10-second hole. The order path itself is unchanged.
            Map<String,Object> chosenUpgrade=upgradeCandidate(f);
            if(!"upgrade".equals(chosen.get("type"))&&!primary){
                long upgradeCost=chosenUpgrade!=null&&chosenUpgrade.get("cost") instanceof Number
                        ?((Number)chosenUpgrade.get("cost")).longValue():-1;
                boolean payable=upgradeCost>0&&n(obj(state.get("player")),"credits")-upgradeCost>=primaryReserve;
                if(!payable&&(mainCount>=10||upgradeCost>n(obj(state.get("player")),"credits"))){
                    // A factory with no upgrade left in its native menu has nothing to defer: it is a full
                    // combat producer now, so this branch must not record a phantom upgrade deferral.
                    if(chosenUpgrade!=null)
                        reportSecondaryInvestment(f,chosenUpgrade,chosen,state,primaryReserve,primaryReserve);
                    if(!(n(obj(state.get("player")),"credits")-n(chosen,"cost")>=primaryReserve)){
                        if(idleReason==null)idleReason="BANKING_FOR_SECONDARY_UPGRADE";
                        continue;
                    }
                }
            }
            // 杈撳嚭13 搂5: the primary producer never degrades to a cheap unit while its mainline action is
            // merely unaffordable. It waits, and the money is protected for it. Upgrades stay allowed.
            if(!qualitySelected&&primary&&preferred!=null&&mainlineType!=null&&!mainlineType.equals(chosen.get("type"))
               &&isUnitAction((String)chosen.get("type"))){
                reportProductionDeferred(f,preferred,state,"PRIMARY_PRODUCTION",Math.max(0,mainlineCost));
                if(idleReason==null)idleReason="BANKING_FOR_PRIMARY_UNIT";
                continue;
            }
            // 杈撳嚭13 搂6 / 杈撳嚭15 搂3: a secondary producer may only consume what is left above the primary
            // reserve - for units and for its upgrade alike.
            if(!primary&&primaryReserve>0&&n(obj(state.get("player")),"credits")-n(chosen,"cost")<primaryReserve){
                boolean investment="upgrade".equals(chosen.get("type"));
                reportProductionDeferred(f,chosen,state,investment?"SECONDARY_INVESTMENT":"PRIMARY_PRODUCTION",
                                         primaryReserve);
                if(idleReason==null)idleReason=investment?"BANKING_FOR_SECONDARY_UPGRADE":"RESERVING_FOR_PRIMARY";
                continue;
            }
            if(!"upgrade".equals(chosen.get("type"))&&mainCount>=10){
                boolean banking=chosenUpgrade!=null;
                if(banking){
                    // 杈撳嚭13 搂7 / 杈撳嚭15 搂4: banking for the tech that unlocks the mainline unit is primary
                    // tech enablement and keeps saving; banking on an extra factory is only an
                    // opportunistic investment, so the cheap unit is bought instead of saved for.
                    boolean enablement=mainlineScore<3||primary;
                    long banked=enablement?Math.max(0,(long)n(obj(state.get("player")),"credits")):Math.max(0,primaryReserve);
                    if(enablement){
                        reportProductionDeferred(f,chosenUpgrade,state,"PRIMARY_TECH",banked);
                        if(idleReason==null)idleReason="BANKING_FOR_UPGRADE";
                        continue;
                    }
                    // 杈撳嚭16 搂2B: the pair is reported through the single owner, so a decision already
                    // reported by the investment block above is not reported a second time here.
                    reportSecondaryInvestment(f,chosenUpgrade,chosen,state,banked,primaryReserve);
                }
            }
            if(!qualitySelected&&preferred!=null&&!preferred.get("type").equals(chosen.get("type"))){
                // 杈撳嚭9 搂8: distinguish "money could not reach the preferred unit" from "policy kept the
                // money back on purpose"; only the first is a production_decision fallback.
                if(builderReserve>0&&Boolean.TRUE.equals(preferred.get("affordable"))&&wouldBreachMineReserve(state,n(preferred,"cost")))
                    reportProductionDeferred(f,preferred,state,"BUILDER_RECOVERY",builderReserve);
                else if(primaryReserve>0&&Boolean.TRUE.equals(preferred.get("affordable")))
                    reportProductionDeferred(f,preferred,state,"PRIMARY_PRODUCTION",primaryReserve);
                else
                    event("production_decision",decisionJson(f,preferred,chosen,state));
            }
            Map<String,Object> receipt=post("/command/queue?unitId="+fid+"&actionId="+URLEncoder.encode((String)chosen.get("actionId"),"UTF-8"));
            if(receipt==null)return false;
            if(qualitySelected){
                artilleryLedger=artilleryLedger.accepted(state,fid,time);
                Map<String,Object> evidence=new LinkedHashMap<String,Object>(surplus.evidence);
                evidence.put("gameTimeMs",time);evidence.put("actionId",chosen.get("actionId"));evidence.put("receipt",receipt);
                event("surplus_role_ordered",json(evidence));
            }
            spend("upgrade".equals(chosen.get("type"))?SPEND_FACTORY_UPGRADE:SPEND_UNIT,
                  n(chosen,"cost"),String.valueOf(chosen.get("type")),fid);
            if(pendingFallback!=null){
                // 杈撳嚭18 搂D: the bridge accepted this fallback order, so exactly this one order gets an
                // event. A failed POST returns above and therefore emits nothing.
                reportSecondaryFallback(f,pendingFallback,chosen,state,pendingFallbackReserve);
                pendingFallback=null;
            }
            Pending p=new Pending();p.started=time;p.type=(String)chosen.get("type");p.tier=(int)n(f,"tier");pending.put(fid,p);
            productionIdleReason=null;return true;
        }
        lastFactoryQueueNonEmpty=anyQueueBusy;
        if(idleFactory&&idleReason!=null)reportProductionIdle(idleReason,mainCount,state);else productionIdleReason=null;
        return false;
    }

    /**
     * Diagnose the shared production loop before an order is attempted. This is deliberately a read-only
     * assessment: military capacity is released only after a persistent, legal demand signal; producer
     * expansion remains owned by the existing long-window factory gate.
     */
    private void evaluateProductionCapacity(Map<String,Object> state)throws Exception{
        if(!strategy.enabled())return;
        int committed=committedProductionArmed(state),target=strategy.armyTarget();
        Map<String,Object> menu=readStrategy("/combat/production","production_capacity_menu");
        List<Map<String,Object>> quotes=new ArrayList<Map<String,Object>>();
        int routes=0;boolean techBlocked=false;double routeCost=Double.NaN;boolean routeAffordable=false;
        String demand="NONE";boolean unknownDemand=false,unavailableDemand=false;
        for(Map<String,Object> factory:StrategyDirector.items(menu,"factories")){
            List<ProductionRoute> options=ProductionRoute.ordinaryOptions(factory);
            // An upgrade in the legal menu is evidence of a tech route; an empty/unrecognised menu
            // alone cannot prove that tech is the cause.
            if(options.isEmpty())for(Map<String,Object> action:StrategyDirector.items(factory,"actions"))
                if("upgrade".equals(action.get("type")))techBlocked=true;
            for(ProductionRoute route:options){
                quotes.add(route.evidence());String routeDemand=strategy.ordinaryDemand(route.product);
                if("KNOWN".equals(routeDemand)){
                    routes++;demand="KNOWN";
                    if(!Double.isFinite(routeCost)||(route.affordable&&!routeAffordable)
                            ||route.affordable==routeAffordable&&route.cost<routeCost){routeCost=route.cost;routeAffordable=route.affordable;}
                }else if("UNKNOWN".equals(routeDemand))unknownDemand=true;
                else if("ROUTE_UNAVAILABLE".equals(routeDemand))unavailableDemand=true;
            }
        }
        if(!"KNOWN".equals(demand))demand=unknownDemand?"UNKNOWN":unavailableDemand?"ROUTE_UNAVAILABLE":"NONE";
        if(quotes.isEmpty()){
            String ordinaryDemand=strategy.ordinaryDemand("heavyTank");
            demand="NONE".equals(ordinaryDemand)?"NONE":menu==null||"UNKNOWN".equals(ordinaryDemand)?"UNKNOWN":
                techBlocked&&"KNOWN".equals(ordinaryDemand)?"KNOWN":"ROUTE_UNAVAILABLE";
        }
        double credits=n(obj(state.get("player")),"credits"),reserved=strategyReserve()+strategy.capabilityReserve();
        double income=modelledIncomePerGameSecond(state),consumption=productionConsumptionPerGameSecond();
        boolean recovery=builderRecoveryActive||builderOrderPending||countReadyType(state,"builder")<builderTarget
            ||URGENCY_EMERGENCY.equals(militaryUrgency)||URGENCY_CONTESTED.equals(militaryUrgency);
        capacitySamples.add(new double[]{time,income-consumption,"KNOWN".equals(demand)?1:0,
            !recovery&&routeAffordable&&Double.isFinite(routeCost)&&credits-routeCost>=reserved?1:0});
        long cutoff=time-economyWindowGameMs;
        while(capacitySamples.size()>1&&capacitySamples.get(1)[0]<=cutoff)capacitySamples.remove(0);
        double covered=0,positive=0,useful=0,safe=0;
        for(int i=1;i<capacitySamples.size();i++){
            double[] prev=capacitySamples.get(i-1);double end=capacitySamples.get(i)[0];
            double dt=end-Math.max(cutoff,prev[0]);
            if(dt<=0||end-prev[0]>6000)continue;
            covered+=dt;if(prev[1]>0)positive+=dt;if(prev[2]>0)useful+=dt;if(prev[3]>0)safe+=dt;
        }
        boolean warm=time-startTime>=economyWindowGameMs&&covered>=economyWindowGameMs*.8;
        capacitySustained=warm&&positive>=covered*.8&&useful>=covered*.8&&safe>=covered*.8&&income>consumption;
        double[] load=factoryLoadWindow();
        capacityBottleneck=ProductionCapacity.classify(committed,target,mobileUnitHardCap,demand,routes,
            techBlocked,menu!=null&&Double.isFinite(routeCost)&&Double.isFinite(credits)&&Double.isFinite(reserved),
            credits,routeCost,reserved,recovery,factoryTargetCommitted,capacitySustained,load[0],factorySaturationMinPct);
        if("KNOWN".equals(demand)&&Double.isFinite(routeCost)&&credits>=routeCost&&!routeAffordable
                &&capacityBottleneck!=ProductionCapacity.Bottleneck.HARD_SAFETY_CAP)
            capacityBottleneck=ProductionCapacity.Bottleneck.UNKNOWN;
        boolean limited=capacityBottleneck==ProductionCapacity.Bottleneck.ARMY_CAPACITY_LIMIT;
        int slots=Math.min(8,mobileUnitHardCap-target);
        boolean decision=limited&&capacitySustained&&slots>0&&routeAffordable&&credits-reserved>=slots*routeCost
            &&time-lastMilitaryCapacityDecision>=economyWindowGameMs;
        Map<String,Object> evidence=StrategyDirector.map(
            "incomeEstimate",round1(income),"productionConsumptionPerGameSecond",round1(consumption),
            "recentSpendPerGameSecond",round1(recentSpendPerGameSecond()),"sustainableSurplusPerGameSecond",round1(income-consumption),
            "credits",credits,"strategyArmyTarget",target,"committedArmed",committed,"hardSafetyCap",mobileUnitHardCap,
            "readyProducerCount",countReadyType(state,"landFactory"),"producerUtilizationPct",round2(load[0]),
            "observedProducerGameMs",(long)load[1],"demand",demand,"backlogSlots",limited?slots:Math.max(0,target-committed),
            "routeCount",routes,"routes",quotes,"selectedNativeCost",Double.isFinite(routeCost)?routeCost:null,
            "selectedNativeAffordable",routeAffordable,"builderReserve",builderReserve,"investmentReserve",investmentReserve,
            "capabilityReserve",strategy.capabilityReserve(),"allReserved",reserved,"recovery",recovery,
            "factoryTarget",landFactoryTarget,"factoryTargetCommitted",factoryTargetCommitted,
            "bottleneck",capacityBottleneck.name(),"militaryCapacityDecision",decision?"INCREASE":"HOLD",
            "producerCapacityDecision",capacityBottleneck==ProductionCapacity.Bottleneck.PRODUCER_THROUGHPUT_LIMIT?"EVALUATE_EXISTING_FACTORY_GATE":"HOLD",
            "windowGameMs",economyWindowGameMs,"coveredGameMs",(long)covered,"surplusGameMs",(long)positive,
            "usefulDemandGameMs",(long)useful,"safeGameMs",(long)safe,"sustained",capacitySustained,
            "operationalCapacityStatus","UNKNOWN","gameTimeMs",time);
        capacityEvidence=evidence;
        String bottleneck=capacityBottleneck.name();
        if(!bottleneck.equals(lastCapacityBottleneck)||decision||time-lastCapacityLog>=30000){
            lastCapacityBottleneck=bottleneck;lastCapacityLog=time;
            event("production_capacity_assessment",json(evidence));
        }
        if(decision&&strategy.increaseMilitaryCapacity(committed,slots,evidence)){
            lastMilitaryCapacityDecision=time;capacitySamples.clear();
        }
        if(lastFactoryReady>=0&&time-lastFactoryReady>=economyWindowGameMs){
            Map<String,Object> after=new LinkedHashMap<String,Object>(evidence);
            after.put("factoryCommitmentId",lastReadyFactoryCommitmentId);after.put("factoryReadyAtGameMs",lastFactoryReady);
            event("production_capacity_after_expansion",json(after));lastFactoryReady=-1;
        }
    }
    private int ordinaryUnobservedSlots(Map<String,Object> state){
        int count=0;
        for(Map.Entry<Long,Pending> entry:pending.entrySet()){
            if("upgrade".equals(entry.getValue().type)||SurplusSpendingPolicy.PRODUCT.equals(entry.getValue().type))continue;
            Map<String,Object> producer=find(state,entry.getKey());
            if(producer!=null&&StrategyDirector.number(producer,"productionQueue",-1)<=0)count++;
        }
        return count;
    }
    private int committedProductionArmed(Map<String,Object> state){
        if(strategy.enabled())return strategy.committedArmedForPolicy()+artilleryLedger.unobservedSlots(state)+ordinaryUnobservedSlots(state);
        int count=0;for(Map<String,Object> u:units(state))if(alive(u)){
            if(Boolean.TRUE.equals(u.get("mobile"))&&Boolean.TRUE.equals(u.get("canAttack")))count++;
            if("landFactory".equals(u.get("type")))count+=(int)Math.max(0,StrategyDirector.number(u,"productionQueue",0));
        }
        return count+ordinaryUnobservedSlots(state);
    }
    private double recentSpendPerGameSecond(){
        long sum=0;for(long[] entry:spendLedger)if(entry[0]>time-economyWindowGameMs)sum+=entry[1];
        return sum/(economyWindowGameMs/1000.0);
    }


    private void observeProductionRoutes(List<Map<String,Object>> factories)throws Exception{
        StringBuilder signature=new StringBuilder();List<Map<String,Object>> routes=new ArrayList<Map<String,Object>>();
        for(Map<String,Object> factory:factories){ProductionRoute route=ProductionRoute.ordinary(factory);if(route!=null){
            routes.add(route.evidence());signature.append(route.producer).append(':').append(route.product).append(':').append(route.cost).append(';');
        }}
        String value=signature.toString();
        if(!value.equals(lastProductionRouteSignature)||time-lastProductionRouteLog>=30000){
            lastProductionRouteSignature=value;lastProductionRouteLog=time;
            event("production_route_observed",json(StrategyDirector.map("routeType","landFactory_to_ordinary_combat",
                "routes",routes,"routeCount",routes.size(),"gameTimeMs",time)));
        }
    }

    /** The unchanged production score (杈撳嚭13 搂8: P2-B2 changes budgets, not unit scoring). */
    private static int unitScore(String type){
        if("tank".equals(type)||"c_tank".equals(type))return 1;
        if("heavyTank".equals(type))return 3;
        if("upgrade".equals(type))return 5;
        return -1;
    }

    /** Does this producer's native menu offer that action type at all? */
    private static boolean offersType(Map<String,Object> factory,String type){
        if(type==null)return false;
        for(Map<String,Object> a:list(factory,"actions"))if(type.equals(a.get("type")))return true;
        return false;
    }

    /** A combat unit action (as opposed to a tech upgrade): only these can be a mainline unit. */
    private static boolean isUnitAction(String type){
        return unitScore(type)>=1&&!"upgrade".equals(type);
    }

    /** The upgrade action of a factory, for the deferred/ banking diagnostics. */
    private static Map<String,Object> upgradeCandidate(Map<String,Object> factory){
        for(Map<String,Object> a:list(factory,"actions"))if("upgrade".equals(a.get("type")))return a;
        return null;
    }

    /**
     * The unit this factory would queue next under the same army-cap and reserve rules used above. The
     * price is read from the menu whether or not it is affordable, because the surplus trigger has to be
     * able to compare credits against it before the order is ever placed.
     */
    private Map<String,Object> preferredAction(Map<String,Object> factory,Map<String,Object> state,int armySize){
        Map<String,Object> best=null;int priority=-1;
        for(Map<String,Object> a:list(factory,"actions")){
            if(!(a.get("cost") instanceof Number))continue;String type=(String)a.get("type");int score=-1;
            if(("tank".equals(type)||"c_tank".equals(type))&&armySize<productionLimit()&&!wouldBreachMineReserve(state,n(a,"cost")))score=1;
            if("heavyTank".equals(type)&&armySize<productionLimit()&&!wouldBreachMineReserve(state,n(a,"cost")))score=3;
            if(score>priority){priority=score;best=a;}
        }
        return best;
    }

    /** The best affordable action that the builder reserve is currently keeping out of reach. */
    /**
     * The cheapest legal combat action, used only to name the order an investment reserve held back.
     * Returns null when the menu offers none: an EMPTY map is not "no candidate" - it is a map whose
     * "cost" is null, and reportProductionDeferred would NPE on it. Live 对话39 crashed three matches
     * exactly that way, so the empty-map version is deliberately gone.
     */
    private Map<String,Object> investmentCandidate(Map<String,Object> factory){
        Map<String,Object> cheapest=null;
        for(Map<String,Object> a:list(factory,"actions")){
            if(!Boolean.TRUE.equals(a.get("affordable")))continue;
            if(!(a.get("cost") instanceof Number))continue;
            String type=String.valueOf(a.get("type"));
            if(!"c_tank".equals(type)&&!"tank".equals(type)&&!"heavyTank".equals(type))continue;
            if(cheapest==null||n(a,"cost")<n(cheapest,"cost"))cheapest=a;
        }
        return cheapest;
    }
    private Map<String,Object> reserveBlockedCandidate(Map<String,Object> factory,Map<String,Object> state){        if(builderReserve<=0)return null;
        Map<String,Object> best=null;int priority=-1;
        for(Map<String,Object> a:list(factory,"actions")){
            if(!Boolean.TRUE.equals(a.get("affordable")))continue;
            String type=(String)a.get("type");int score=-1;
            if("tank".equals(type)||"c_tank".equals(type))score=1;
            if("heavyTank".equals(type))score=3;
            if("upgrade".equals(type))score=5;
            if(score<0||!wouldBreachMineReserve(state,n(a,"cost")))continue;
            if(score>priority){priority=score;best=a;}
        }
        return best;
    }

    /** The best combat unit this factory could actually pay for right now, ignoring scoring rules. */
    private Map<String,Object> bestAffordableUnit(Map<String,Object> factory){
        Map<String,Object> best=null;int priority=-1;
        for(Map<String,Object> a:list(factory,"actions")){
            if(!Boolean.TRUE.equals(a.get("affordable")))continue;String type=(String)a.get("type");int score=-1;
            if("tank".equals(type)||"c_tank".equals(type))score=1;
            if("heavyTank".equals(type))score=3;
            if(score>priority){priority=score;best=a;}
        }
        return best;
    }

    /**
     * 杈撳嚭8 搂4: makes the money decision on an idle factory visible instead of implied. The interesting
     * case is PREFERRED_UNAFFORDABLE_FALLBACK, where the scoring wanted an expensive unit and the shared
     * credit pool could only pay for a cheap one - the mechanism behind the c_tank share rising once a
     * second factory exists. A later primary-budget version would add RESERVED_FOR_PRIMARY_PRODUCTION.
     */
    private String decisionJson(Map<String,Object> factory,Map<String,Object> preferred,Map<String,Object> chosen,Map<String,Object> state){
        boolean affordable=Boolean.TRUE.equals(preferred.get("affordable"));
        Map<String,Object> best=bestAffordableUnit(factory);
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("factoryId",Long.valueOf(id(factory)));
        data.put("factoryTier",Long.valueOf((long)n(factory,"tier")));
        data.put("credits",Double.valueOf(n(obj(state.get("player")),"credits")));
        data.put("preferredUnit",preferred.get("type"));
        data.put("preferredUnitCost",Long.valueOf((long)n(preferred,"cost")));
        data.put("preferredAffordable",Boolean.valueOf(affordable));
        data.put("bestAffordableUnit",best==null?null:best.get("type"));
        data.put("bestAffordableCost",best==null?null:Long.valueOf((long)n(best,"cost")));
        data.put("chosenUnit",chosen.get("type"));
        data.put("reason",affordable?"PREFERRED_SUPERSEDED":"PREFERRED_UNAFFORDABLE_FALLBACK");
        data.put("gameTimeMs",Long.valueOf(time));
        return json(data);
    }
    /** Makes an idle production system visible instead of silently choosing nothing. */
    private void reportProductionIdle(String reason,int activeArmy,Map<String,Object> state)throws Exception{
        if(reason.equals(productionIdleReason)&&time-lastIdleEmit<30000)return;
        productionIdleReason=reason;lastIdleEmit=time;
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("reason",reason);data.put("activeArmy",activeArmy);
        data.put("activeArmyTarget",activeArmyTarget);data.put("reserveTarget",reserveTarget);
        data.put("mobileUnitHardCap",mobileUnitHardCap);
        data.put("strategyArmyTarget",strategy.armyTarget());data.put("committedArmed",committedProductionArmed(state));
        data.put("bottleneck",capacityBottleneck.name());data.put("gameTimeMs",time);
        data.put("credits",n(obj(state.get("player")),"credits"));
        event("production_idle",json(data));
    }
    /** Low-frequency liveness record: separates "policy chose to wait" from "no goal" from "process died". */
    private void heartbeat(Map<String,Object> state)throws Exception{
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("gameSeconds",(time-startTime)/1000L);
        data.put("phase","battle");
        data.put("goal",goalLabel());
        data.put("marching",marching);
        data.put("visibleEnemies",listSize(lastEnemies,"visibleEnemies"));
        data.put("rememberedEnemies",listSize(lastEnemies,"rememberedEnemies"));
        data.put("enemyIntelVisible",numberOrNull(lastEnemies,"enemyIntelVisible"));
        data.put("enemyIntelLostContact",numberOrNull(lastEnemies,"enemyIntelLostContact"));
        data.put("enemyIntelCleared",numberOrNull(lastEnemies,"enemyIntelCleared"));
        data.put("enemyIntelEvicted",numberOrNull(lastEnemies,"enemyIntelEvicted"));
        data.put("reconTaskId",reconTask==null?null:Long.valueOf(reconTask.taskId));
        data.put("reconTaskKind",reconTask==null?null:reconTask.kind);
        data.put("reconEnemyId",reconTask==null?null:Long.valueOf(reconTask.enemyId));
        data.put("reconUnitId",reconTask==null||reconTask.unitId<0?null:Long.valueOf(reconTask.unitId));
        data.put("reconOrdersQueued",Long.valueOf(reconOrders));
        data.put("reconOrdersObserved",Long.valueOf(reconOrdersObserved));
        data.put("searchTargets",0);
        data.put("activeArmy",army(state).size());
        data.put("mainForceCount",mainArmy(state).size());
        data.put("expendableScoutCount",expendableScouts.size());
        data.put("activeArmyTarget",activeArmyTarget);
        data.put("mobileUnitHardCap",mobileUnitHardCap);
        data.put("searchTarget",searchTarget==null?null:searchTarget.status);
        data.put("searchTargetEnemy",searchTarget==null?null:Long.valueOf(searchTarget.sourceEnemyId));
        data.put("consumedObservations",Integer.valueOf(consumedObservations.size()));
        data.put("mines",Long.valueOf(countExtractors(state)));
        data.put("minesReady",Long.valueOf(countReadyExtractors(state)));
        data.put("mineTarget",Integer.valueOf(mineTarget));
        data.put("minesCompleted",Long.valueOf(minesCompleted));
        data.put("factories",Long.valueOf(countType(state,"landFactory")));
        data.put("factoriesReady",Long.valueOf(countReadyType(state,"landFactory")));
        data.put("landFactoryTarget",Long.valueOf(landFactoryTarget));
        data.put("factoriesCompleted",Long.valueOf(landFactoriesCompleted));
        data.put("buildPending",buildJob==null?null:buildJob.kind);
        data.put("buildStalled",Boolean.valueOf(buildJob!=null&&buildJob.stalled));
        data.put("expansionPending",Boolean.valueOf(buildJob!=null));
        data.put("expansionBlocked",expansionLastBlocked);
        data.put("factoryBlocks",Long.valueOf(factoryBlocks));
        data.put("factoryQueueNonEmpty",Boolean.valueOf(lastFactoryQueueNonEmpty));
        data.put("preferredUnit",lastPreferredUnit);
        data.put("preferredUnitCost",Long.valueOf(lastPreferredUnitCost));
        data.put("factoryCost",Long.valueOf(landFactoryCost));
        data.put("builderTarget",Integer.valueOf(builderTarget));
        data.put("aliveBuilders",Long.valueOf(countReadyType(state,"builder")));
        data.put("builderRecovery",Boolean.valueOf(builderRecoveryActive));
        data.put("builderOrderPending",Boolean.valueOf(builderOrderPending));
        data.put("builderReserve",Long.valueOf(builderReserve));
        data.put("builderCost",Long.valueOf(builderCost));
        data.put("builderProducerId",Long.valueOf(builderProducerId));
        data.put("exploredTiles",numberOrNull(lastScout,"exploredTiles"));
        data.put("mapMemoryRecentFogTiles",numberOrNull(lastScout,"mapMemoryRecentFogTiles"));
        data.put("mapMemoryStaleFogTiles",numberOrNull(lastScout,"mapMemoryStaleFogTiles"));
        data.put("mapMemoryDeepFogTiles",numberOrNull(lastScout,"mapMemoryDeepFogTiles"));
        data.put("mapMemoryNeverSeenTiles",numberOrNull(lastScout,"mapMemoryNeverSeenTiles"));
        data.put("reachableUnvisitedTiles",numberOrNull(lastPlan,"reachableUnvisitedTiles"));
        data.put("plannerStatus",stringOrNull(lastPlan,"status"));
        data.put("finalCause",stringOrNull(lastPlan,"finalCause"));
        data.put("fallbackLevel",numberOrNull(lastPlan,"fallbackLevel"));
        data.put("productionIdleReason",productionIdleReason);
        data.put("credits",n(obj(state.get("player")),"credits"));
        event("agent_tick_alive",json(data));
    }
    private String goalLabel(){
        if(reconTask!=null&&reconTask.frontier())return "FRONTIER_SWEEP";
        if(listSize(lastEnemies,"visibleEnemies")>0)return "ENGAGE_VISIBLE";
        if(listSize(lastEnemies,"rememberedEnemies")>0)return "PURSUE_TACTICAL_MEMORY";
        return marching?"FRONTIER_SEARCH":"REGROUP";
    }
    private static Long numberOrNull(Map<String,Object> map,String key){
        if(map==null)return null;Object value=map.get(key);return value instanceof Number?Long.valueOf(((Number)value).longValue()):null;
    }
    private static String stringOrNull(Map<String,Object> map,String key){
        if(map==null)return null;Object value=map.get(key);return value instanceof String?(String)value:null;
    }
    private static int listSize(Map<String,Object> map,String key){
        if(map==null)return 0;Object value=map.get(key);return value instanceof List<?>?((List<?>)value).size():0;
    }
    private static boolean reconPriorityType(String type){
        return "commandCenter".equals(type)||"landFactory".equals(type)||"airFactory".equals(type)
            ||"seaFactory".equals(type)||"extractorT2".equals(type);
    }
    private static int reconPriority(String type){
        if("commandCenter".equals(type))return 0;
        if("airFactory".equals(type)||"landFactory".equals(type)||"seaFactory".equals(type))return 1;
        return 2;
    }
    private static String reconOwner(long taskId){return "recon:"+taskId;}
    private void releaseOwnership(long taskId,String reason)throws Exception{
        if(!execution.release(reconOwner(taskId)))return;
        event("task_ownership_released","{\"taskId\":"+taskId+",\"reason\":"+Json.quote(reason)+",\"gameTimeMs\":"+time+"}");
    }
    private boolean claimRecon(long taskId,long unitId)throws Exception{
        if(!execution.claim(reconOwner(taskId),unitId))return false;
        event("task_ownership_acquired","{\"taskId\":"+taskId+",\"unitId\":"+unitId+",\"sessionId\":"+Json.quote(execution.stamp().session)
                +",\"player\":"+Json.quote(execution.stamp().player)+",\"frame\":"+execution.stamp().frame+",\"gameTimeMs\":"+time+"}");
        return true;
    }
    private void cancelReconAcquisition(String reason)throws Exception{
        if(reconAcquisition==null)return;
        long taskId=reconAcquisition.taskId;
        event("recon_takeover_cancelled","{\"taskId\":"+taskId+",\"unitId\":"+reconAcquisition.unitId+",\"reason\":"+Json.quote(reason)+",\"gameTimeMs\":"+time+"}");
        reconAcquisition=null;releaseOwnership(taskId,reason);lastFrontierPlanAt=time;
    }
    private boolean issueReconAcquisition(Map<String,Object> state)throws Exception{
        if(reconAcquisition==null||reconAcquisition.hold!=null)return false;
        ReconAcquisition acquisition=reconAcquisition;
        Map<String,Object> actor=find(state,acquisition.unitId);
        if(actor==null||!armed(actor)){cancelReconAcquisition("SCOUT_LOST");return false;}
        CommandArbiter.MoveIntent intent=new CommandArbiter.MoveIntent(execution.stamp(),reconOwner(acquisition.taskId),
                acquisition.unitId,n(actor,"x"),n(actor,"y"));
        acquisition.hold=submitMove(intent,actor,true);
        if(acquisition.hold==null){cancelReconAcquisition("TAKEOVER_COMMAND_REJECTED");return false;}
        event("recon_takeover_queued","{\"taskId\":"+acquisition.taskId+",\"unitId\":"+acquisition.unitId
                +",\"requestId\":"+Json.quote(acquisition.hold.requestId)+",\"acceptedFrame\":"+acquisition.hold.acceptedFrame+",\"gameTimeMs\":"+time+"}");
        return true;
    }
    private MoveExecution.Witness moveWitness(MoveExecution move,Map<String,Object> actor){
        if(move==null||actor==null||!armed(actor))return MoveExecution.Witness.NONE;
        return move.observe(execution.stamp(),id(actor),n(actor,"x"),n(actor,"y"),stringOrNull(actor,"orderType"),
                actor.get("orderX") instanceof Number?Double.valueOf(n(actor,"orderX")):null,
                actor.get("orderY") instanceof Number?Double.valueOf(n(actor,"orderY")):null);
    }
    /** Observe execution on every poll, including samples between policy decisions. */
    private void observeReconMotion(Map<String,Object> state)throws Exception{
        ReconTask task=reconTask;if(task==null||task.unitId<0||task.move==null)return;
        Map<String,Object> actor=find(state,task.unitId);
        MoveExecution.Witness witness=moveWitness(task.move,actor);
        if(witness==MoveExecution.Witness.NONE)return;
        if(!task.executionObserved){
            task.executionObserved=true;task.everExecutionObserved=true;
            Map<String,Object> data=reconData(task);data.put("requestId",task.move.requestId);
            data.put("acceptedFrame",task.move.acceptedFrame);data.put("observedFrame",execution.stamp().frame);
            data.put("witness",witness.name());
            if(witness==MoveExecution.Witness.ACTIVE_ORDER){
                task.orderObserved=true;task.everOrderObserved=true;reconOrdersObserved++;
                data.put("orderType",actor.get("orderType"));event("recon_order_observed",json(data));
            }else{
                reconMovesCompletedBetweenSamples++;event("recon_move_completed_between_samples",json(data));
            }
        }
        double d=distance(actor,task.waypointX,task.waypointY);
        double initialDistance=Math.hypot(task.move.startX-task.waypointX,task.move.startY-task.waypointY);
        double progressThreshold=Math.min(12,Math.max(.5,initialDistance*.25));
        if(d+progressThreshold<task.bestDistance){
            task.bestDistance=d;task.progressAt=time;task.progressEvents++;
            if(task.firstProgressAt<0)task.firstProgressAt=time;
            if(time-task.lastProgressEventAt>=5000){
                Map<String,Object> data=reconData(task);data.put("distanceToWaypoint",Double.valueOf(d));
                data.put("scoutX",Double.valueOf(n(actor,"x")));data.put("scoutY",Double.valueOf(n(actor,"y")));
                event("recon_progress",json(data));task.lastProgressEventAt=time;
            }
        }
        if(task.frontier()&&task.progressEvents>0&&atReconWaypoint(state,actor,task)&&task.routeCursor<task.routeWaypointIndex){
            task.routeCursor=task.routeWaypointIndex;task.waypointArrivedAt=time;
            Map<String,Object> reached=reconData(task);reached.put("routeCursor",Integer.valueOf(task.routeCursor));
            reached.put("waypointX",Double.valueOf(task.waypointX));reached.put("waypointY",Double.valueOf(task.waypointY));
            event("recon_waypoint_reached",json(reached));
        }
    }
    private static boolean atReconWaypoint(Map<String,Object> state,Map<String,Object> actor,ReconTask task){
        Map<String,Object> map=obj(state.get("map"));double tw=n(map,"tileWidth"),th=n(map,"tileHeight");
        return distance(actor,task.waypointX,task.waypointY)<12
                &&(int)Math.floor(n(actor,"x")/tw)==(int)Math.floor(task.waypointX/tw)
                &&(int)Math.floor(n(actor,"y")/th)==(int)Math.floor(task.waypointY/th);
    }
    private Map<String,Object> reconData(ReconTask task){
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("taskId",Long.valueOf(task.taskId));data.put("kind",task.kind);
        data.put("executionObserved",Boolean.valueOf(task.everExecutionObserved));
        data.put("enemyId",task.frontier()?null:Long.valueOf(task.enemyId));
        data.put("sourceType",task.sourceType);data.put("lastSeenGameTimeMs",Long.valueOf(task.lastSeen));
        data.put("lastKnownX",Double.valueOf(task.x));data.put("lastKnownY",Double.valueOf(task.y));
        data.put("unitId",task.unitId<0?null:Long.valueOf(task.unitId));data.put("gameTimeMs",Long.valueOf(time));
        if(task.frontier()){
            data.put("preferredUnitId",Long.valueOf(task.preferredUnitId));
            data.put("awaitFreshOwnObservation",Boolean.valueOf(task.awaitFreshOwnObservation));
            data.put("frontierTile",Long.valueOf(task.frontierTile));
            data.put("targetTile",Long.valueOf(task.targetTile));
            data.put("frontierMemoryClass",task.frontierMemoryClass);
            data.put("movementType",task.movementType);
            data.put("routeTerrainStatus",task.routeTerrainStatus);
            data.put("terrainSourceId",task.terrainSourceId);
            data.put("potentialInfoScore",Integer.valueOf(task.potentialInfoScore));
            data.put("potentialNeverSeenTiles",Integer.valueOf(task.potentialNeverSeenTiles));
            data.put("potentialDeepFogTiles",Integer.valueOf(task.potentialDeepFogTiles));
            data.put("potentialStaleFogTiles",Integer.valueOf(task.potentialStaleFogTiles));
        }
        return data;
    }
    private void closeRecon(String kind,String reason)throws Exception{
        ReconTask task=reconTask;if(task==null)return;
        Map<String,Object> data=reconData(task);data.put("reason",reason);
        data.put("ordersQueued",Integer.valueOf(task.orders));
        data.put("orderObserved",Boolean.valueOf(task.everOrderObserved));
        event(kind,json(data));
        if(task.frontier()){
            recheckNextAfterFrontier=true;
            if("recon_frontier_refreshed".equals(kind))frontierTasksRefreshed++;
            else if("recon_frontier_advanced".equals(kind))frontierTasksAdvanced++;
            else if("recon_frontier_satisfied".equals(kind))frontierTasksSatisfied++;
            else if("recon_frontier_preempted".equals(kind))frontierTasksPreempted++;
            else frontierTasksBlocked++;
            if("recon_frontier_blocked".equals(kind)&&!("DEFENSE_PREEMPTION".equals(reason)
                    ||"SCOUT_LOST_BEFORE_ORDER".equals(reason)
                    ||"ROUTE_ANCHOR_DRIFT_BEFORE_ASSIGNMENT".equals(reason)
                    ||"COMMAND_REJECTED".equals(reason)||"ASSIGNMENT_TIMEOUT".equals(reason))) {
                frontierBlockedUntil.put(Long.valueOf(task.frontierTile),Long.valueOf(time+120000));
                while(frontierBlockedUntil.size()>48)frontierBlockedUntil.remove(frontierBlockedUntil.keySet().iterator().next());
            }
        }else if("recon_reacquired".equals(kind))reconReacquired++;
        else if("recon_site_cleared".equals(kind))reconCleared++;
        else reconBlocked++;
        if(!task.frontier())recheckNextAfterFrontier=false;
        if(task.everOrderObserved&&("recon_reacquired".equals(kind)||"recon_site_cleared".equals(kind)))
            reconResolvedAfterObservedMove++;
        if(!task.frontier()){
            reconClosedObservations.put(Long.valueOf(task.enemyId),Long.valueOf(task.lastSeen));
            while(reconClosedObservations.size()>256)reconClosedObservations.remove(reconClosedObservations.keySet().iterator().next());
        }
        releaseOwnership(task.taskId,reason);reconTask=null;
    }
    private void deferRecon(String reason,Map<String,Object> state)throws Exception{
        if(reconTask==null||time-lastReconDeferredAt<15000)return;
        lastReconDeferredAt=time;
        Map<String,Object> data=reconData(reconTask);data.put("reason",reason);
        data.put("army",Integer.valueOf(army(state).size()));data.put("militaryUrgency",militaryUrgency);
        event("recon_deferred",json(data));
    }
    private void releaseReconActor(Map<String,Object> state,String reason,boolean failed)throws Exception{
        ReconTask task=reconTask;if(task==null||task.unitId<0)return;
        long oldId=task.unitId;
        Map<String,Object> data=reconData(task);data.put("reason",reason);
        event(failed?"recon_attempt_blocked":"recon_unassigned",json(data));
        task.firstOrderAt=-1;task.orderObserved=false;
        task.bestDistance=Double.MAX_VALUE;task.retryAt=time+(failed?15000:0);
        if(task.frontier()){
            Map<String,Object> actor=find(state,oldId);
            if("DEFENSE_PREEMPTION".equals(reason)&&expendableScouts.remove(Long.valueOf(oldId))){
                Map<String,Object> released=reconData(task);
                released.put("reason",reason);
                event("recon_expendable_released_for_defense",json(released));
            }
            if(actor!=null&&armed(actor)&&distance(actor,homeX,homeY)>140){reconRecallUnitId=oldId;reconRecallQueuedAt=-1;}
            closeRecon("DEFENSE_PREEMPTION".equals(reason)?"recon_frontier_preempted":"recon_frontier_blocked",reason);
            return;
        }
        releaseOwnership(task.taskId,reason);task.move=null;task.executionObserved=false;task.unitId=-1;
        if("SCOUT_DAMAGED".equals(reason)){
            Map<String,Object> actor=find(state,oldId);
            if(actor!=null&&armed(actor)){
                resting.put(Long.valueOf(oldId),Long.valueOf(time+45000));
                if(distance(actor,homeX,homeY)>140){reconRecallUnitId=oldId;reconRecallQueuedAt=-1;}
            }
        }
        if(failed){
            task.failedUnits.add(Long.valueOf(oldId));
            if(task.failedUnits.size()>=2)closeRecon("recon_blocked","TWO_SCOUT_ATTEMPTS_FAILED");
        }else{
            Map<String,Object> actor=find(state,oldId);
            if(actor!=null&&armed(actor)&&distance(actor,homeX,homeY)>140){reconRecallUnitId=oldId;reconRecallQueuedAt=-1;}
        }
    }
    private boolean issueReconRecall(Map<String,Object> state)throws Exception{
        if(reconRecallUnitId<0)return false;
        Map<String,Object> actor=find(state,reconRecallUnitId);
        if(actor==null||!armed(actor)||distance(actor,homeX,homeY)<=140){reconRecallUnitId=-1;reconRecallQueuedAt=-1;return false;}
        if(reconRecallQueuedAt>=0&&time-reconRecallQueuedAt<30000)return false;
        Map<String,Object> receipt=post("/command/move?unitId="+reconRecallUnitId+"&x="+homeX+"&y="+homeY);
        if(receipt==null)return false;
        Map<String,Object> data=new LinkedHashMap<String,Object>();data.put("unitId",Long.valueOf(reconRecallUnitId));
        data.put("gameTimeMs",Long.valueOf(time));data.put("reason","RECON_PREEMPTED_FOR_DEFENSE");
        data.put("receiptStatus",receipt.get("status"));event("recon_recall_queued",json(data));
        reconRecallQueuedAt=time;return true;
    }
    /** Only an earlier legal observation can start a recheck. Status and site visibility are bridge facts. */
    private void updateRecon(Map<String,Object> state,Map<String,Object> enemies)throws Exception{
        Iterator<Long> oldScouts=expendableScouts.iterator();
        while(oldScouts.hasNext()){
            Map<String,Object> unit=find(state,oldScouts.next().longValue());
            if(unit==null||!armed(unit))oldScouts.remove();
        }
        List<Map<String,Object>> intel=enemies.get("enemyIntel") instanceof List<?>
                ?list(enemies,"enemyIntel"):Collections.<Map<String,Object>>emptyList();
        if(reconTask!=null){
            ReconTask task=reconTask;Map<String,Object> current=null;
            // This call follows a new own-state observation; a stale bridge/client anchor
            // accepted when the task was created can now be checked before any move.
            task.awaitFreshOwnObservation=false;
            // Defense wins even when a legal region reveal and a task close occur in this same tick.
            int pendingMainMinimum=6; // pending ownership has already removed the actor from mainArmy
            if(task.frontier()&&task.unitId<0&&time-task.createdAt>20000){
                closeRecon("recon_frontier_blocked","ASSIGNMENT_TIMEOUT");
            }else if(task.frontier()&&task.unitId<0
                    &&(URGENCY_EMERGENCY.equals(militaryUrgency)
                            ||mainArmy(state).size()<pendingMainMinimum)){
                // A planned but unassigned scout must return to the main force immediately.
                closeRecon("recon_frontier_blocked","DEFENSE_PREEMPTION");
            }else if(task.frontier()&&task.unitId>=0
                    &&(find(state,task.unitId)==null||!armed(find(state,task.unitId)))){
                releaseReconActor(state,"SCOUT_LOST",true);
            }else if(task.frontier()&&task.unitId>=0
                    &&(URGENCY_EMERGENCY.equals(militaryUrgency)||mainArmy(state).size()<6)){
                releaseReconActor(state,"DEFENSE_PREEMPTION",false);
            }else if(task.frontier()){
                Map<String,Object> region=lastScout!=null&&lastScout.get("region") instanceof Map<?,?>
                        ?obj(lastScout.get("region")):null;
                int deepReveals=region!=null&&region.get("revealedDeepOrNeverSince") instanceof Number
                        ?((Number)region.get("revealedDeepOrNeverSince")).intValue():0;
                int staleReveals=region!=null&&region.get("revealedStaleSince") instanceof Number
                        ?((Number)region.get("revealedStaleSince")).intValue():0;
                long latestReveal=region!=null&&region.get("latestRelevantRevealGameTimeMs") instanceof Number
                        ?((Number)region.get("latestRelevantRevealGameTimeMs")).longValue():-1;
                boolean matchingRegion=region!=null&&region.get("tile") instanceof Number
                        &&((Number)region.get("tile")).longValue()==task.frontierTile;
                if(!matchingRegion){deepReveals=0;staleReveals=0;}
                if(task.everOrderObserved&&task.progressEvents>0&&task.firstProgressAt>=0
                        &&latestReveal>task.firstProgressAt&&(deepReveals>0||staleReveals>0)){
                    Map<String,Object> data=reconData(task);
                    data.put("revealedDeepOrNeverSince",Integer.valueOf(deepReveals));
                    data.put("revealedStaleSince",Integer.valueOf(staleReveals));
                    data.put("latestRelevantRevealGameTimeMs",Long.valueOf(latestReveal));
                    data.put("source","TEAM_LEGAL_VISION_STRICTLY_AFTER_OWN_PROGRESS");
                    event("recon_frontier_memory_update",json(data));
                    closeRecon("recon_frontier_refreshed","TARGET_REGION_LEGALLY_REVEALED");
                }else if(task.everExecutionObserved&&task.progressEvents>0&&latestReveal>task.createdAt
                        &&(deepReveals>0||staleReveals>0)){
                    // Coarse sampling can observe arrival AFTER the team already revealed the region.
                    // The information goal is satisfied; do not claim a strictly ordered scout refresh.
                    closeRecon("recon_frontier_satisfied","TEAM_REFRESH_WITHOUT_STRICT_ACTOR_ORDERING");
                }else if(task.firstOrderAt>=0&&time-task.firstOrderAt>120000){
                    closeRecon("recon_frontier_blocked","ACTIVE_FRONTIER_TIMEOUT");
                }
            }else{
                for(Map<String,Object> contact:intel)if(contact.get("id") instanceof Number&&id(contact)==task.enemyId){current=contact;break;}
                if(current==null){closeRecon("recon_blocked","INTEL_EVICTED");}
                else if("CLEARED".equals(current.get("status"))){
                    closeRecon("recon_site_cleared","OLD_SITE_LEGALLY_VISIBLE_EMPTY");
                }else if("VISIBLE".equals(current.get("status"))){
                    closeRecon("recon_reacquired","LEGAL_VISIBLE_CONTACT");
                }else if(current.get("lastSeenGameTimeMs") instanceof Number
                        &&((Number)current.get("lastSeenGameTimeMs")).longValue()>task.lastSeen){
                    closeRecon("recon_reacquired","NEW_LEGAL_SIGHTING_AFTER_RECHECK_STARTED");
                }else if(task.unitId>=0&&task.firstOrderAt>=0&&time-task.firstOrderAt>120000){
                    closeRecon("recon_blocked","ACTIVE_RECHECK_TIMEOUT");
                }
            }
            if(reconTask!=null&&task.unitId>=0){
                Map<String,Object> actor=find(state,task.unitId);
                if(actor==null||!armed(actor))releaseReconActor(state,"SCOUT_LOST",true);
                else if(URGENCY_EMERGENCY.equals(militaryUrgency)
                        ||(task.frontier()?mainArmy(state).size()<6:readyReconArmy(state).size()<7))
                    releaseReconActor(state,"DEFENSE_PREEMPTION",false);
                else if(!task.frontier()&&n(actor,"hp")<n(actor,"maxHp")*.35)releaseReconActor(state,"SCOUT_DAMAGED",true);
                else if(task.frontier()&&task.executionObserved&&task.orders>0
                        &&distanceFromSegment(n(actor,"x"),n(actor,"y"),task.segmentStartX,task.segmentStartY,
                                task.waypointX,task.waypointY)>80)
                    releaseReconActor(state,"NATIVE_ROUTE_DEVIATION",true);
                else if(task.frontier()&&task.orders>0
                        &&!safeFrontierSegment(actor,task.waypointX,task.waypointY))
                    releaseReconActor(state,"NEW_KNOWN_THREAT_CORRIDOR",true);
                else {
                    double d=distance(actor,task.waypointX,task.waypointY);
                    if(task.frontier()&&task.executionObserved&&task.progressEvents>0&&atReconWaypoint(state,actor,task)){
                        if(task.routeCursor<task.routeWaypointIndex){
                            task.routeCursor=task.routeWaypointIndex;
                            Map<String,Object> reached=reconData(task);
                            reached.put("routeCursor",Integer.valueOf(task.routeCursor));
                            reached.put("waypointX",Double.valueOf(task.waypointX));
                            reached.put("waypointY",Double.valueOf(task.waypointY));
                            event("recon_waypoint_reached",json(reached));
                        }
                        if(task.routeCursor>=task.routeTiles.size()-1&&task.waypointArrivedAt>=0&&time-task.waypointArrivedAt>8000)
                            closeRecon(task.targetTile==task.frontierTile?"recon_frontier_blocked":"recon_frontier_advanced",
                                    task.targetTile==task.frontierTile?"WAYPOINT_REACHED_WITHOUT_REGION_REFRESH":"SAFE_ROUTE_STEP_COMPLETED");
                    }else if(task.orders>0&&time-task.progressAt>35000)
                        releaseReconActor(state,d<50?"WAYPOINT_REACHED_WITHOUT_LEGAL_CLEARANCE":"NO_MOVEMENT_PROGRESS",true);
                }
            }
        }
        if(reconTask!=null)return;
        if(reconAcquisition!=null){tryCreateFrontier(state);return;}
        if(reconRecallUnitId>=0)return; // finish a queued recall before giving either Recon lane another actor
        if(!(enemies.get("enemyIntel") instanceof List<?>)){
            tryCreateFrontier(state);return; // old bridge/test fixtures still allow legal map memory
        }
        Map<String,Object> best=null;int priority=Integer.MAX_VALUE;long oldest=Long.MAX_VALUE;
        for(Map<String,Object> contact:intel){
            if(!"LOST_CONTACT".equals(contact.get("status"))||!Boolean.TRUE.equals(contact.get("lastKnownBuilding"))
                    ||!Boolean.FALSE.equals(contact.get("lastKnownSiteVisible"))
                    ||!(contact.get("id") instanceof Number)||!(contact.get("lastSeenGameTimeMs") instanceof Number)
                    ||!(contact.get("lastKnownX") instanceof Number)||!(contact.get("lastKnownY") instanceof Number))continue;
            String type=stringOrNull(contact,"lastKnownType");if(!reconPriorityType(type))continue;
            long seen=((Number)contact.get("lastSeenGameTimeMs")).longValue();
            Long closed=reconClosedObservations.get(Long.valueOf(id(contact)));
            if(closed!=null&&seen<=closed.longValue())continue;
            int p=reconPriority(type);if(p<priority||p==priority&&seen<oldest){best=contact;priority=p;oldest=seen;}
        }
        // One frontier and one eligible old-site recheck alternate when both remain available.
        if((best==null||!recheckNextAfterFrontier)&&tryCreateFrontier(state))return;
        if(best!=null){
            reconTask=new ReconTask(++reconTaskCounter,best,time);reconCreated++;
            event("recon_task_created",json(reconData(reconTask)));
        }
    }
    private boolean expendableEligible(Map<String,Object> unit){
        String type=stringOrNull(unit,"type");
        if(!("tank".equals(type)||"c_tank".equals(type))||!armed(unit))return false;
        double max=n(unit,"maxHp"),hp=n(unit,"hp");
        if(max<=0||hp<=0||hp/max>.45)return false;
        Long until=resting.get(Long.valueOf(id(unit)));
        return until==null||time>=until.longValue();
    }
    private boolean tryCreateFrontier(Map<String,Object> state)throws Exception{
        Map<String,Object> chosen=null;long taskId;
        if(reconAcquisition!=null){
            ReconAcquisition acquisition=reconAcquisition;chosen=find(state,acquisition.unitId);
            if(chosen==null||!armed(chosen)){cancelReconAcquisition("SCOUT_LOST");return false;}
            if(URGENCY_EMERGENCY.equals(militaryUrgency)||mainArmy(state).size()<6){cancelReconAcquisition("DEFENSE_PREEMPTION");return false;}
            if(time-acquisition.startedAt>20000){cancelReconAcquisition("TAKEOVER_TIMEOUT");return false;}
            if(acquisition.hold==null||moveWitness(acquisition.hold,chosen)==MoveExecution.Witness.NONE
                    ||distance(chosen,acquisition.hold.intent.x,acquisition.hold.intent.y)>=12)return true;
            taskId=acquisition.taskId;
            event("recon_takeover_observed","{\"taskId\":"+taskId+",\"unitId\":"+acquisition.unitId
                    +",\"frame\":"+execution.stamp().frame+",\"gameTimeMs\":"+time+"}");
        }else{
            if(!reconFrontierEnabled||reconRecallUnitId>=0||URGENCY_EMERGENCY.equals(militaryUrgency)
                    ||time-lastFrontierPlanAt<15000)return false;
            List<Map<String,Object>> main=mainArmy(state);if(main.size()<6)return false;
            if(!expendableScouts.isEmpty()){
                for(Long scoutId:expendableScouts){
                    Map<String,Object> unit=find(state,scoutId.longValue());
                    if(unit!=null&&armed(unit)&&!execution.reserved(id(unit))){chosen=unit;break;}
                }
            }else if(main.size()>=7){
                double lowest=Double.MAX_VALUE;
                for(Map<String,Object> unit:main)if(expendableEligible(unit)){
                    double ratio=n(unit,"hp")/n(unit,"maxHp");
                    if(ratio<lowest){lowest=ratio;chosen=unit;}
                }
            }
            if(chosen==null)return false;
            taskId=++reconTaskCounter;if(!claimRecon(taskId,id(chosen)))return false;
            // Exclusion from FUTURE army orders does not cancel an EXISTING native order.
            // Stop that order using the ordinary, rate-limited native move to a legal own position.
            // Do not plan until a later own observation confirms takeover at that position.
            if(chosen.get("orderType")!=null){
                reconAcquisition=new ReconAcquisition(taskId,id(chosen),time);return true;
            }
        }
        lastFrontierPlanAt=time;
        try{return createFrontierPlan(state,chosen,taskId);}
        finally{
            reconAcquisition=null;
            if(reconTask==null||reconTask.taskId!=taskId)releaseOwnership(taskId,"NO_VALID_FRONTIER_PLAN");
        }
    }
    private boolean createFrontierPlan(Map<String,Object> state,Map<String,Object> chosen,long taskId)throws Exception{
        StringBuilder avoid=new StringBuilder();
        Iterator<Map.Entry<Long,Long>> blocks=frontierBlockedUntil.entrySet().iterator();
        while(blocks.hasNext()){
            Map.Entry<Long,Long> entry=blocks.next();
            if(time>=entry.getValue().longValue()){blocks.remove();continue;}
            if(avoid.length()>0)avoid.append(',');avoid.append(entry.getKey());
        }
        Map<String,Object> plan=optionalGet("/scout/plan?role=recon&unitId="+id(chosen)
                +"&avoid="+avoid,"recon_frontier_plan");
        if(plan==null||!"planned".equals(plan.get("status")))return false;
        check(plan);
        if(!(plan.get("frontierTile") instanceof Number)||!(plan.get("targetX") instanceof Number)
                ||!(plan.get("targetY") instanceof Number)||!(plan.get("potentialInfoScore") instanceof Number)
                ||!(plan.get("potentialNeverSeenTiles") instanceof Number)
                ||!(plan.get("potentialDeepFogTiles") instanceof Number)
                ||!(plan.get("potentialStaleFogTiles") instanceof Number))return false;
        long frontier=((Number)plan.get("frontierTile")).longValue();
        Long blocked=frontierBlockedUntil.get(Long.valueOf(frontier));
        if(blocked!=null&&time<blocked.longValue()){
            event("recon_frontier_deferred","{\"reason\":\"RECENTLY_BLOCKED_FRONTIER\",\"frontierTile\":"+frontier
                    +",\"retryAt\":"+blocked+",\"gameTimeMs\":"+time+"}");
            return false;
        }
        String observedMovement=stringOrNull(plan,"movementType");
        String catalogMovement=TargetCatalog.movementType(stringOrNull(chosen,"type"));
        if(observedMovement!=null&&catalogMovement!=null&&!observedMovement.equals(catalogMovement)){
            event("recon_frontier_deferred","{\"reason\":\"MOVEMENT_TYPE_MISMATCH\",\"observedMovement\":"
                    +Json.quote(observedMovement)+",\"catalogMovement\":"+Json.quote(catalogMovement)
                    +",\"frontierTile\":"+frontier+",\"gameTimeMs\":"+time+"}");
            return false;
        }
        if(plan.get("routeTerrain") instanceof Map<?,?>){
            Map<String,Object> terrain=obj(plan.get("routeTerrain"));
            if(!"KNOWN".equals(terrain.get("status")))
                event("recon_terrain_unknown","{\"frontierTile\":"+frontier+",\"status\":"
                        +Json.quote(String.valueOf(terrain.get("status")))+",\"fallback\":\"EXISTING_KNOWN_PASSABILITY\""
                        +",\"gameTimeMs\":"+time+"}");
        }
        if(!validFrontierRoute(plan,state,chosen)){
            event("recon_frontier_deferred","{\"reason\":\"INVALID_KNOWN_ROUTE\",\"frontierTile\":"+frontier
                    +",\"gameTimeMs\":"+time+"}");
            return false;
        }
        reconTask=new ReconTask(taskId,plan,id(chosen),time);
        // The planner and this client can observe the same unit at different moments.
        // Recheck every new frontier assignment against the next own-state snapshot.
        reconTask.awaitFreshOwnObservation=true;
        reconCreated++;frontierTasksCreated++;
        event("recon_task_created",json(reconData(reconTask)));
        return true;
    }
    private static boolean validFrontierRoute(Map<String,Object> plan,Map<String,Object> state,Map<String,Object> actor){
        if(!(plan.get("routeTiles") instanceof List<?>)||!(plan.get("targetTile") instanceof Number)
                ||!(plan.get("frontierTile") instanceof Number))return false;
        Map<String,Object> map=obj(state.get("map"));
        int height=((Number)map.get("tilesHigh")).intValue(),width=((Number)map.get("tilesWide")).intValue();
        double tw=n(map,"tileWidth"),th=n(map,"tileHeight");
        if(height<=0||width<=0||tw<=0||th<=0)return false;
        long frontier=((Number)plan.get("frontierTile")).longValue();
        if(frontier<0||frontier>=(long)width*height)return false;
        List<?> raw=(List<?>)plan.get("routeTiles");
        if(raw.size()<2||raw.size()>64)return false;
        int previous=-1;
        for(Object item:raw){
            if(!(item instanceof Number))return false;
            int tile=((Number)item).intValue();if(tile<0||tile>=(long)width*height)return false;
            if(previous>=0&&Math.abs(tile/height-previous/height)+Math.abs(tile%height-previous%height)!=1)return false;
            previous=tile;
        }
        if(previous!=((Number)plan.get("targetTile")).intValue())return false;
        int first=((Number)raw.get(0)).intValue();
        int actorColumn=(int)Math.floor(n(actor,"x")/tw),actorRow=(int)Math.floor(n(actor,"y")/th);
        if(actorColumn<0||actorColumn>=width||actorRow<0||actorRow>=height)return false;
        if(actorColumn*height+actorRow==first)return true;
        // The bridge plans from a newer own-unit position than the client's last observation.
        // Accept only a one-tile stale observation with a matching bridge anchor; no command is
        // sent until issueFrontierOrder validates the actor against a fresh own-unit observation.
        if(!(plan.get("anchorUnitId") instanceof Number)||((Number)plan.get("anchorUnitId")).longValue()!=id(actor)
                ||!(plan.get("anchorX") instanceof Number)||!(plan.get("anchorY") instanceof Number))return false;
        int planColumn=(int)Math.floor(n(plan,"anchorX")/tw),planRow=(int)Math.floor(n(plan,"anchorY")/th);
        return planColumn==first/height&&planRow==first%height
                &&Math.abs(actorColumn-planColumn)+Math.abs(actorRow-planRow)==1;
    }
    /** First exact intersection of a straight segment and a known danger circle. Native path may differ. */
    private static double reconThreatEntry(double sx,double sy,double dx,double dy,double tx,double ty,double radius){
        double length2=dx*dx+dy*dy;
        if(length2<=0)return Double.POSITIVE_INFINITY;
        double ox=tx-sx,oy=ty-sy,center2=ox*ox+oy*oy;
        if(center2<=radius*radius)return 0;
        double projection=(ox*dx+oy*dy)/length2;
        double perpendicular2=center2-projection*projection*length2;
        if(perpendicular2>radius*radius)return Double.POSITIVE_INFINITY;
        double entry=projection-Math.sqrt(Math.max(0,radius*radius-perpendicular2)/length2);
        return entry>=0&&entry<=1?entry:Double.POSITIVE_INFINITY;
    }
    /** Check the proposed straight approach against legally remembered ranges plus an 80-unit margin. */
    private boolean reconWaypoint(Map<String,Object> actor,ReconTask task,double[] result){
        double sx=n(actor,"x"),sy=n(actor,"y"),dx=task.x-sx,dy=task.y-sy;
        double distance=Math.hypot(dx,dy);if(distance<40)return false;
        List<Map<String,Object>> threats=lastScout!=null&&lastScout.get("rememberedThreats") instanceof List<?>
                ?list(lastScout,"rememberedThreats"):new ArrayList<Map<String,Object>>();
        boolean foundOwnThreat=false;
        for(Map<String,Object> threat:threats)if(threat.get("id") instanceof Number&&id(threat)==task.enemyId){foundOwnThreat=true;break;}
        double earliest=Double.POSITIVE_INFINITY;
        for(Map<String,Object> threat:threats){
            if(!(threat.get("x") instanceof Number)||!(threat.get("y") instanceof Number)
                    ||!(threat.get("range") instanceof Number))continue;
            double radius=Math.max(0,n(threat,"range"))+80;
            earliest=Math.min(earliest,reconThreatEntry(sx,sy,dx,dy,n(threat,"x"),n(threat,"y"),radius));
        }
        if(!foundOwnThreat&&task.armedAtLastSighting)
            earliest=Math.min(earliest,reconThreatEntry(sx,sy,dx,dy,task.x,task.y,220));
        if(earliest!=Double.POSITIVE_INFINITY){
            double safeDistance=earliest*distance-40;
            if(safeDistance<80)return false;
            double fraction=safeDistance/distance;
            result[0]=sx+fraction*dx;result[1]=sy+fraction*dy;result[2]=1;
            return true;
        }
        result[0]=task.x;result[1]=task.y;result[2]=0;return true;
    }
    private List<Map<String,Object>> readyReconArmy(Map<String,Object> state){
        List<Map<String,Object>> ready=new ArrayList<Map<String,Object>>();
        List<Map<String,Object>> candidates=mainArmy(state);
        if(reconTask!=null&&!reconTask.frontier()&&reconTask.unitId>=0){
            Map<String,Object> actor=find(state,reconTask.unitId);if(actor!=null&&armed(actor))candidates.add(actor);
        }
        for(Map<String,Object> unit:candidates){
            Long until=resting.get(Long.valueOf(id(unit)));
            if(until==null||time>=until.longValue())ready.add(unit);
        }
        return ready;
    }
    private List<Map<String,Object>> mainArmy(Map<String,Object> state){
        List<Map<String,Object>> main=new ArrayList<Map<String,Object>>();
        for(Map<String,Object> unit:army(state))if(!expendableScouts.contains(Long.valueOf(id(unit)))
                &&id(unit)!=reconRecallUnitId&&!execution.reserved(id(unit)))main.add(unit);
        return main;
    }
    private boolean issueReconOrder(Map<String,Object> state)throws Exception{
        ReconTask task=reconTask;if(task==null)return false;
        if(task.frontier())return issueFrontierOrder(state,task);
        List<Map<String,Object>> available=readyReconArmy(state);
        if(URGENCY_EMERGENCY.equals(militaryUrgency)){deferRecon("EMERGENCY_DEFENSE",state);return false;}
        if(available.size()<7){deferRecon("ARMY_BELOW_SEVEN_READY",state);return false;}
        if(time<task.retryAt)return false;
        if(task.unitId>=0&&time-task.lastOrderAt<12000)return false;
        Map<String,Object> chosen=null;double chosenScore=Double.MAX_VALUE;double[] chosenWaypoint=null;
        if(task.unitId>=0){
            chosen=find(state,task.unitId);
            if(chosen==null)return false;
            double[] waypoint=new double[3];
            if(!reconWaypoint(chosen,task,waypoint)){
                deferRecon("KNOWN_THREAT_CORRIDOR",state);return false;
            }
            chosenWaypoint=waypoint;
        }else for(Map<String,Object> unit:available){
            if(task.failedUnits.contains(Long.valueOf(id(unit))))continue;
            Long restUntil=resting.get(Long.valueOf(id(unit)));
            if(n(unit,"hp")<n(unit,"maxHp")*.6||restUntil!=null&&time<restUntil.longValue())continue;
            double[] waypoint=new double[3];if(!reconWaypoint(unit,task,waypoint))continue;
            int score=unitScore(stringOrNull(unit,"type"));
            double rank=(score<0?5:score)*1000+distance(unit,task.x,task.y);
            if(rank<chosenScore){chosen=unit;chosenScore=rank;chosenWaypoint=waypoint;}
        }
        if(chosen==null){deferRecon("NO_SAFE_HEALTHY_SCOUT",state);return false;}
        double waypointDistance=distance(chosen,chosenWaypoint[0],chosenWaypoint[1]);
        if(waypointDistance<45){deferRecon(chosenWaypoint[2]>0?"KNOWN_THREAT_STANDOFF":"SITE_STILL_HIDDEN",state);return false;}
        if(task.unitId<0){
            if(!claimRecon(task.taskId,id(chosen)))return false;
            task.unitId=id(chosen);Map<String,Object> data=reconData(task);
            data.put("unitType",chosen.get("type"));data.put("armyBeforeAssignment",Integer.valueOf(available.size()));
            data.put("selectionScore",Double.valueOf(chosenScore));event("recon_assigned",json(data));
        }
        task.waypointX=chosenWaypoint[0];task.waypointY=chosenWaypoint[1];
        task.approach=chosenWaypoint[2]>0?"STANDOFF":"DIRECT";
        task.move=submitMove(new CommandArbiter.MoveIntent(execution.stamp(),reconOwner(task.taskId),
                task.unitId,task.waypointX,task.waypointY),chosen,false);
        if(task.move==null){releaseOwnership(task.taskId,"COMMAND_REJECTED");task.unitId=-1;deferRecon("COMMAND_REJECTED",state);return false;}
        task.orders++;task.lastOrderAt=time;if(task.firstOrderAt<0)task.firstOrderAt=time;
        task.progressAt=time;task.lastProgressEventAt=-100000;
        task.bestDistance=distance(chosen,task.waypointX,task.waypointY);task.orderObserved=false;task.executionObserved=false;task.waypointArrivedAt=-1;reconOrders++;
        Map<String,Object> data=reconData(task);data.put("approach",task.approach);
        data.put("waypointX",Double.valueOf(task.waypointX));data.put("waypointY",Double.valueOf(task.waypointY));
        data.put("receiptStatus","queued");data.put("requestId",task.move.requestId);
        data.put("acceptedFrame",task.move.acceptedFrame);event("recon_order_queued",json(data));
        return true;
    }
    private boolean issueFrontierOrder(Map<String,Object> state,ReconTask task)throws Exception{
        if(URGENCY_EMERGENCY.equals(militaryUrgency)){
            deferRecon("EMERGENCY_DEFENSE",state);return false;
        }
        if(task.awaitFreshOwnObservation){
            deferRecon("WAITING_FOR_FRESH_ROUTE_ANCHOR",state);return false;
        }
        Map<String,Object> actor=find(state,task.preferredUnitId);
        if(actor==null||!armed(actor)){
            closeRecon("recon_frontier_blocked","SCOUT_LOST_BEFORE_ORDER");return false;
        }
        boolean alreadyExpendable=expendableScouts.contains(Long.valueOf(id(actor)));
        int mainCount=mainArmy(state).size();
        if(mainCount<6){
            deferRecon("MAIN_FORCE_BELOW_SIX_AFTER_TRANSFER",state);return false;
        }
        if(!alreadyExpendable&&!expendableEligible(actor)){
            closeRecon("recon_frontier_blocked","EXPENDABLE_CANDIDATE_NO_LONGER_ELIGIBLE");return false;
        }
        if(task.routeCursor<task.routeWaypointIndex)return false; // wait for observed arrival at the last segment
        if(task.unitId>=0&&time-task.lastOrderAt<4000)return false;
        // updateRecon owns the post-arrival vision deadline; issuing cannot close it early.
        if(task.routeCursor>=task.routeTiles.size()-1)return false;
        Map<String,Object> map=obj(state.get("map"));
        int height=((Number)map.get("tilesHigh")).intValue(),width=((Number)map.get("tilesWide")).intValue();
        double tw=n(map,"tileWidth"),th=n(map,"tileHeight");
        int anchor=task.routeTiles.get(task.routeCursor).intValue();
        int end=task.routeCursor+1;
        int direction=task.routeTiles.get(end).intValue()-task.routeTiles.get(task.routeCursor).intValue();
        while(end+1<task.routeTiles.size()&&end-task.routeCursor<6
                &&task.routeTiles.get(end+1).intValue()-task.routeTiles.get(end).intValue()==direction)end++;
        int actorColumn=(int)Math.floor(n(actor,"x")/tw),actorRow=(int)Math.floor(n(actor,"y")/th);
        int actorTile=actorColumn*height+actorRow;boolean onStraightRoute=false;
        if(actorColumn>=0&&actorColumn<width&&actorRow>=0&&actorRow<height)
            for(int i=task.routeCursor;i<=end;i++)if(task.routeTiles.get(i).intValue()==actorTile){onStraightRoute=true;break;}
        if(!onStraightRoute){
            if(task.unitId<0&&time-task.createdAt<4000
                    &&Math.abs(actorColumn-anchor/height)+Math.abs(actorRow-anchor%height)==1){
                deferRecon("WAITING_FOR_FRESH_ROUTE_ANCHOR",state);return false;
            }
            if(task.unitId>=0)releaseReconActor(state,"ROUTE_ANCHOR_DRIFT",true);
            else closeRecon("recon_frontier_blocked","ROUTE_ANCHOR_DRIFT_BEFORE_ASSIGNMENT");
            return false;
        }
        int tile=task.routeTiles.get(end).intValue();
        double wx=(tile/height+.5)*tw,wy=(tile%height+.5)*th;
        if(!safeFrontierSegment(actor,wx,wy)){
            if(task.unitId>=0)releaseReconActor(state,"NEW_KNOWN_THREAT_CORRIDOR",true);
            else closeRecon("recon_frontier_blocked","KNOWN_THREAT_CORRIDOR");
            return false;
        }
        boolean firstAssignment=task.unitId<0;
        task.move=submitMove(new CommandArbiter.MoveIntent(execution.stamp(),reconOwner(task.taskId),id(actor),wx,wy),actor,false);
        if(task.move==null){
            if(firstAssignment)closeRecon("recon_frontier_blocked","COMMAND_REJECTED");
            else releaseReconActor(state,"COMMAND_REJECTED",true);
            return false;
        }
        if(firstAssignment){
            task.unitId=id(actor);
            if(!alreadyExpendable){
                expendableScouts.add(Long.valueOf(task.unitId));expendableTransfers++;
                Map<String,Object> transfer=reconData(task);
                transfer.put("unitType",actor.get("type"));transfer.put("hp",Double.valueOf(n(actor,"hp")));
                transfer.put("maxHp",Double.valueOf(n(actor,"maxHp")));
                transfer.put("hpFraction",Double.valueOf(n(actor,"hp")/n(actor,"maxHp")));
                transfer.put("mainForceAfterTransfer",Integer.valueOf(mainArmy(state).size()));
                event("recon_expendable_transfer",json(transfer));
            }
            Map<String,Object> assigned=reconData(task);
            assigned.put("actorMode","EXPENDABLE");assigned.put("unitType",actor.get("type"));
            assigned.put("mainForceAfterAssignment",Integer.valueOf(mainArmy(state).size()));
            event("recon_assigned",json(assigned));
        }
        task.waypointX=wx;task.waypointY=wy;task.routeWaypointIndex=end;task.approach="KNOWN_ROUTE_SEGMENT";
        task.segmentStartX=n(actor,"x");task.segmentStartY=n(actor,"y");
        task.orders++;task.lastOrderAt=time;if(task.firstOrderAt<0)task.firstOrderAt=time;
        task.progressAt=time;task.lastProgressEventAt=-100000;
        task.bestDistance=distance(actor,task.waypointX,task.waypointY);task.orderObserved=false;task.executionObserved=false;task.waypointArrivedAt=-1;reconOrders++;
        Map<String,Object> data=reconData(task);data.put("approach",task.approach);
        data.put("waypointX",Double.valueOf(task.waypointX));data.put("waypointY",Double.valueOf(task.waypointY));
        data.put("routeCursor",Integer.valueOf(task.routeCursor));data.put("routeWaypointIndex",Integer.valueOf(end));
        data.put("routeTilesTotal",Integer.valueOf(task.routeTiles.size()));
        data.put("receiptStatus","queued");data.put("requestId",task.move.requestId);
        data.put("acceptedFrame",task.move.acceptedFrame);event("recon_order_queued",json(data));
        return true;
    }
    private boolean safeFrontierSegment(Map<String,Object> actor,double wx,double wy){
        double sx=n(actor,"x"),sy=n(actor,"y"),dx=wx-sx,dy=wy-sy;
        if(lastScout==null||!(lastScout.get("rememberedThreats") instanceof List<?>))return true;
        for(Map<String,Object> threat:list(lastScout,"rememberedThreats")){
            if(!(threat.get("x") instanceof Number)||!(threat.get("y") instanceof Number)
                    ||!(threat.get("range") instanceof Number))continue;
            if(reconThreatEntry(sx,sy,dx,dy,n(threat,"x"),n(threat,"y"),Math.max(0,n(threat,"range"))+140)
                    !=Double.POSITIVE_INFINITY)return false;
        }
        return true;
    }
    private static double distanceFromSegment(double px,double py,double ax,double ay,double bx,double by){
        double dx=bx-ax,dy=by-ay,length2=dx*dx+dy*dy;
        if(length2<=0)return Math.hypot(px-ax,py-ay);
        double t=Math.max(0,Math.min(1,((px-ax)*dx+(py-ay)*dy)/length2));
        return Math.hypot(px-(ax+t*dx),py-(ay+t*dy));
    }
    private List<Map<String,Object>> availableMain(Map<String,Object> state){
        List<Map<String,Object>> army=mainArmy(state);
        if(reconTask!=null){
            long reservedId=reconTask.frontier()&&reconTask.unitId<0
                    ?reconTask.preferredUnitId:reconTask.unitId;
            List<Map<String,Object>> main=new ArrayList<Map<String,Object>>();
            for(Map<String,Object> unit:army)if(id(unit)!=reservedId)main.add(unit);
            army=main;
        }
        List<Map<String,Object>> force=new ArrayList<Map<String,Object>>();
        for(Map<String,Object> u:army){Long until=resting.get(id(u));if(until==null||time>=until)force.add(u);}
        return force;
    }
    private boolean issueCriticalMainRetreat(Map<String,Object> state)throws Exception{
        if(!execution.ready(time))return false;
        for(Map<String,Object> u:availableMain(state))if(n(u,"hp")<n(u,"maxHp")*.25
                &&!resting.containsKey(id(u))&&distance(u,homeX,homeY)>220){
            if(post("/command/move?unitId="+id(u)+"&x="+homeX+"&y="+homeY)!=null){
                resting.put(id(u),time+45000);retreats++;
                event("combat_retreat",json(StrategyDirector.map("unitId",id(u),"reason","CRITICAL_HP","gameTimeMs",time)));return true;}
        }
        return false;
    }
    private boolean servedByLocalCrisis(Map<String,Object> enemy){
        return localCrisis!=null&&localCrisis.returnAt<0&&(id(enemy)==localCrisis.target
                ||distance(enemy,localCrisis.goalX,localCrisis.goalY)<=LocalCrisisPolicy.CLUSTER_RADIUS);
    }
    private void observeLocalArmies(Map<String,Object> state)throws Exception{
        List<Map<String,Object>> main=availableMain(state);localArmies.observe(main,time);
        for(Map<String,Object> change:localArmies.drainChanges())event("local_army_membership",json(change));
        boolean multiple=localArmies.multiple();
        if(localArmyActive!=multiple){localArmyActive=multiple;targetReady=false;
            event("local_army_mode",json(StrategyDirector.map("active",multiple,"gameTimeMs",time)));}
        Map<Long,Map<String,Object>> own=LocalArmyDirector.index(main);
        for(LocalArmyDirector.Cohort c:localArmies.rotation())if(c.frontierActive){
            double nearest=Double.MAX_VALUE;
            for(Map<String,Object> u:c.units(own))nearest=Math.min(nearest,distance(u,c.goalX,c.goalY));
            if(nearest+12<c.bestDistance){c.bestDistance=nearest;c.progressAt=time;}
            if(nearest<65||time-c.progressAt>20000||time-c.frontierAt>75000){
                event(nearest<65?"local_army_frontier_arrived":"local_army_frontier_blocked",json(
                        StrategyDirector.map("cohortId",c.id,"targetTile",c.frontierTile,"distance",nearest,"gameTimeMs",time)));
                if(c.frontierTile>=0){c.avoided.add(c.frontierTile);
                    if(c.avoided.size()>16)c.avoided.remove(c.avoided.iterator().next());}
                c.frontierActive=false;
            }
        }
    }
    private boolean crisisAsset(Map<String,Object> unit){
        String type=stringOrNull(unit,"type");
        return alive(unit)&&n(unit,"buildProgress")>=1&&("commandCenter".equals(type)
                ||type!=null&&type.startsWith("extractor"));
    }
    private List<Map<String,Object>> crisisCluster(Map<String,Object> enemies,Map<String,Object> target){
        List<Map<String,Object>> cluster=new ArrayList<Map<String,Object>>();
        for(Map<String,Object> e:list(enemies,"visibleEnemies"))if(currentEnemy(e,enemies)
                &&Boolean.TRUE.equals(e.get("canAttack"))&&!Boolean.TRUE.equals(e.get("building"))
                &&distance(e,n(target,"x"),n(target,"y"))<=LocalCrisisPolicy.CLUSTER_RADIUS)cluster.add(e);
        return cluster;
    }
    /** Only exact compatible/known native approaches pass. UNKNOWN never becomes a defense promise. */
    private List<Map<String,Object>> crisisCompatible(List<Map<String,Object>> candidates,
            List<Map<String,Object>> contacts,Map<String,Object> enemies)throws Exception{
        List<Map<String,Object>> result=new ArrayList<Map<String,Object>>(candidates);
        for(Map<String,Object> target:contacts){
            if(deprioritized(target)||targetSuppressions.containsKey(id(target)))return Collections.emptyList();
            GuardSelection guard=targetGuard(result,target,enemies);
            if(!"COMPATIBLE".equals(guard.status))return Collections.emptyList();
            result=strategy.eligible(target,guard.assigned);
            if(result.isEmpty())return result;
            StringBuilder ids=new StringBuilder();for(Map<String,Object> u:result){if(ids.length()>0)ids.append(',');ids.append(id(u));}
            Map<String,Object> nativeEvidence=optionalGet("/combat/engagement?unitIds="+ids+"&targetId="+id(target),"local_crisis_engagement");
            if(nativeEvidence==null||!Boolean.TRUE.equals(nativeEvidence.get("targetVisible")))return Collections.emptyList();
            check(nativeEvidence);
            Long seen=numberOrNull(nativeEvidence,"targetObservedAtGameTimeMs");
            if(seen==null||seen.longValue()<(long)n(enemies,"gameTimeMs"))return Collections.emptyList();
            if(nativeEvidence.get("targetX") instanceof Number&&distance(target,n(nativeEvidence,"targetX"),n(nativeEvidence,"targetY"))>160)
                return Collections.emptyList();
            Set<Long> known=new HashSet<Long>();
            for(Map<String,Object> a:list(nativeEvidence,"actors"))if("COMPATIBLE".equals(a.get("compatibility"))
                    &&"APPROACH_PATH_KNOWN".equals(a.get("status")))known.add((long)n(a,"unitId"));
            List<Map<String,Object>> next=new ArrayList<Map<String,Object>>();
            for(Map<String,Object> u:result)if(known.contains(id(u)))next.add(u);
            result=next;if(result.isEmpty())return result;
        }
        return result;
    }
    private void beginCrisisReturn(String reason)throws Exception{
        if(localCrisis==null||localCrisis.returnAt>=0)return;
        localCrisis.returnAt=time;localCrisis.returnReason=reason;
        event("local_crisis_return",json(StrategyDirector.map("taskId",localCrisis.task,"owner",localCrisis.owner,
                "targetId",localCrisis.target,"unitIds",localCrisis.actors,"reason",reason,"gameTimeMs",time,
                "contactSemantics","LOST_VISIBILITY_IS_UNKNOWN_NOT_KILL_PROOF")));
    }
    private void releaseLocalCrisis(String reason)throws Exception{
        LocalCrisis c=localCrisis;if(c==null)return;
        execution.release(c.owner);crisisRetryAfter.put(c.target,time+LocalCrisisPolicy.RETRY_MS);
        while(crisisRetryAfter.size()>128)crisisRetryAfter.remove(crisisRetryAfter.keySet().iterator().next());
        for(Long uid:c.actors)event("task_ownership_released",json(StrategyDirector.map("taskId",c.leases.get(uid),
                "crisisTaskId",c.task,"owner",c.owner,"unitId",uid,"role","LOCAL_CRISIS","reason",reason,"gameTimeMs",time)));
        event("local_crisis_finished",json(StrategyDirector.map("taskId",c.task,"targetId",c.target,
                "reason",reason,"gameTimeMs",time)));localCrisis=null;
    }
    private void observeLocalCrisis(Map<String,Object> state,Map<String,Object> enemies)throws Exception{
        if(localCrisis!=null){
            LocalCrisis c=localCrisis;List<Map<String,Object>> responders=new ArrayList<Map<String,Object>>();
            for(Long uid:c.actors){Map<String,Object> u=find(state,uid);if(u!=null&&alive(u)&&execution.owns(c.owner,uid))responders.add(u);}
            if(responders.isEmpty()){releaseLocalCrisis("NO_SURVIVORS");return;}
            Map<String,Object> asset=find(state,c.asset),target=null;
            for(Map<String,Object> e:list(enemies,"visibleEnemies"))if(id(e)==c.target&&currentEnemy(e,enemies))target=e;
            if(c.returnAt<0){
                if(asset==null||!crisisAsset(asset))beginCrisisReturn("ASSET_NO_LONGER_READY");
                else if(time-c.created>=LocalCrisisPolicy.MAX_ACTIVE_MS)beginCrisisReturn("RESPONSE_TIME_LIMIT");
                else if(target!=null){
                    c.lastSeen=time;
                    if(distance(target,n(asset,"x"),n(asset,"y"))>LocalCrisisPolicy.ASSET_RADIUS+150)beginCrisisReturn("CONTACT_LEFT_ASSET");
                    else if(crisisCluster(enemies,target).size()>LocalCrisisPolicy.MAX_THREATS)beginCrisisReturn("RAID_GREW_BEYOND_SMALL_RESPONSE");
                    else if(deprioritized(target)||targetSuppressions.containsKey(c.target))beginCrisisReturn("TARGET_GUARD_OR_PROGRESS_REJECTION");
                    else if("COMPATIBLE".equals(targetGuard(responders,target,enemies).status)
                            &&trackTargetProgress(responders,enemies,target))beginCrisisReturn("NO_TARGET_PROGRESS");
                }else if(time-c.lastSeen>=LocalCrisisPolicy.LOST_CONTACT_MS)beginCrisisReturn("CONTACT_LOST");
                for(Map<String,Object> u:responders)if(n(u,"hp")<n(u,"maxHp")*.25){beginCrisisReturn("CRITICAL_HP");break;}
            }
            if(c.returnAt>=0){
                boolean arrived=c.returnAccepted>=0;
                for(Map<String,Object> u:responders)if(distance(u,c.returnX,c.returnY)>150)arrived=false;
                if(arrived)releaseLocalCrisis("RETURN_OBSERVED");
                else if(c.returnAccepted>=0&&time-c.returnAccepted>=8000)releaseLocalCrisis("RETURN_HANDOFF_TIMEOUT");
                else if(time-c.returnAt>=LocalCrisisPolicy.RETURN_WAIT_MS)releaseLocalCrisis("RETURN_COMMAND_WAIT_TIMEOUT");
            }
            return;
        }
        if(time-lastCrisisProbe<3000)return;lastCrisisProbe=time;
        List<Map<String,Object>> main=availableMain(state);if(main.size()<LocalCrisisPolicy.MIN_MAIN+LocalCrisisPolicy.MIN_RESPONDERS)return;
        Map<String,Object> target=null,asset=null;double nearest=Double.MAX_VALUE;
        for(Map<String,Object> e:list(enemies,"visibleEnemies")){
            Long retry=crisisRetryAfter.get(id(e));
            if(!currentEnemy(e,enemies)||!Boolean.TRUE.equals(e.get("canAttack"))||Boolean.TRUE.equals(e.get("building"))
                    ||retry!=null&&time<retry||deprioritized(e)||targetSuppressions.containsKey(id(e)))continue;
            for(Map<String,Object> a:units(state))if(crisisAsset(a)){
                double d=distance(e,n(a,"x"),n(a,"y"));
                if(d<=LocalCrisisPolicy.ASSET_RADIUS&&d<nearest){target=e;asset=a;nearest=d;}
            }
        }
        if(target==null)return;
        List<Map<String,Object>> cluster=crisisCluster(enemies,target);
        if(cluster.size()>LocalCrisisPolicy.MAX_THREATS)return;
        double hp=0;for(Map<String,Object> e:cluster){if(!(n(e,"hp")>0))return;hp+=n(e,"hp");}
        final double ax=n(asset,"x"),ay=n(asset,"y");
        List<Map<String,Object>> nearby=new ArrayList<Map<String,Object>>();
        for(Map<String,Object> u:main)if(distance(u,ax,ay)<=LocalCrisisPolicy.RESPONSE_RADIUS&&n(u,"hp")>=n(u,"maxHp")*.5)nearby.add(u);
        Collections.sort(nearby,(a,b)->Double.compare(distance(a,ax,ay),distance(b,ax,ay)));
        if(nearby.size()>48)nearby=new ArrayList<Map<String,Object>>(nearby.subList(0,48));
        List<Map<String,Object>> compatible=crisisCompatible(nearby,cluster,enemies);
        Map<Long,Long> membership=new HashMap<Long,Long>();Map<Long,Integer> sizes=new HashMap<Long,Integer>();
        for(LocalArmyDirector.Cohort c:localArmies.rotation()){sizes.put(c.id,c.memberIds().size());for(Long uid:c.memberIds())membership.put(uid,c.id);}
        List<Map<String,Object>> chosen=LocalCrisisPolicy.select(compatible,main.size(),hp,ax,ay,membership,sizes);
        if(chosen.isEmpty())return;
        List<Long> ids=new ArrayList<Long>();double rx=0,ry=0;
        for(Map<String,Object> u:chosen){ids.add(id(u));rx+=n(u,"x");ry+=n(u,"y");}
        LocalCrisis c=new LocalCrisis(++crisisSequence,id(target),id(asset),time,ids,rx/chosen.size(),ry/chosen.size());
        c.goalX=n(target,"x");c.goalY=n(target,"y");
        for(Long uid:ids)if(!execution.claim(c.owner,uid)){execution.release(c.owner);return;}
        localCrisis=c;
        for(Long uid:ids)event("task_ownership_acquired",json(StrategyDirector.map("taskId",c.leases.get(uid),"crisisTaskId",c.task,"owner",c.owner,"unitId",uid,
                "role","LOCAL_CRISIS","sessionId",session,"player",execution.stamp().player,"frame",execution.stamp().frame,"gameTimeMs",time)));
        event("local_crisis_started",json(StrategyDirector.map("taskId",c.task,"owner",c.owner,"targetId",c.target,
                "assetId",c.asset,"unitIds",ids,"visibleThreats",cluster.size(),"visibleThreatHp",hp,
                "durabilityFloorHp",hp*LocalCrisisPolicy.HP_FACTOR,"mainRemaining",main.size()-ids.size(),
                "activeDeadlineGameTimeMs",time+LocalCrisisPolicy.MAX_ACTIVE_MS,"lostContactWaitMs",LocalCrisisPolicy.LOST_CONTACT_MS,
                "returnWaitLimitMs",LocalCrisisPolicy.RETURN_WAIT_MS,"maximumResponders",LocalCrisisPolicy.MAX_RESPONDERS,
                "selectionSemantics","NEAREST_COMPATIBLE_KNOWN_APPROACH_HP_FLOOR_NOT_WIN_PREDICTION","gameTimeMs",time)));
    }
    private boolean issueLocalCrisis(Map<String,Object> state,Map<String,Object> enemies)throws Exception{
        LocalCrisis c=localCrisis;if(c==null||!execution.ready(time))return false;
        List<Map<String,Object>> actors=new ArrayList<Map<String,Object>>();
        for(Long uid:c.actors){Map<String,Object> u=find(state,uid);if(u!=null&&alive(u)&&execution.owns(c.owner,uid))actors.add(u);}
        if(actors.isEmpty())return false;
        Map<String,Object> target=null;double x=c.returnX,y=c.returnY;
        if(c.returnAt<0){
            for(Map<String,Object> e:list(enemies,"visibleEnemies"))if(id(e)==c.target&&currentEnemy(e,enemies))target=e;
            if(target==null)return false;
            x=n(target,"x");y=n(target,"y");boolean noOrder=false;
            for(Map<String,Object> u:actors)if(u.get("orderType")==null&&distance(u,x,y)>180)noOrder=true;
            if(time-c.lastAccepted<8000&&Math.hypot(x-c.goalX,y-c.goalY)<=160
                    &&!(noOrder&&time-c.lastAccepted>=2000&&time-c.lastIdleRecovery>=8000))return false;
            List<Map<String,Object>> compatible=crisisCompatible(actors,crisisCluster(enemies,target),enemies);
            if(compatible.size()!=actors.size()){beginCrisisReturn("NEW_APPROACH_OR_DOMAIN_UNCERTAINTY");return false;}
            GuardSelection guard=targetGuard(actors,target,enemies);logTargetGuard(target,guard);
            if(trackTargetProgress(actors,enemies,target)){beginCrisisReturn("NO_TARGET_PROGRESS");return false;}
        }else if(c.returnAccepted>=0)return false;
        StringBuilder ids=new StringBuilder();List<Long> actorIds=new ArrayList<Long>();
        for(Map<String,Object> u:actors){if(ids.length()>0)ids.append(',');ids.append(id(u));actorIds.add(id(u));}
        event("tactical_intent",json(StrategyDirector.map("taskId",c.task,"owner",c.owner,
                "reason",target==null?"REGROUP":"OBSERVED_ENEMY","unitIds",actorIds,"targetX",x,"targetY",y,"enemy",target,"gameTimeMs",time)));
        Map<String,Object> receipt=post(c.owner,"/command/attack-move?unitIds="+ids+"&x="+x+"&y="+y,execution.stamp());
        if(receipt==null)return false;
        if(target!=null&&time-c.lastAccepted<8000)c.lastIdleRecovery=time;
        boolean first=c.lastAccepted<0;c.lastAccepted=time;c.goalX=x;c.goalY=y;
        if(target!=null)noteTargetOrder(c.target,first?null:Long.valueOf(c.target));else c.returnAccepted=time;
        attacks++;awaitingOrder=receipt;
        event("local_crisis_order",json(StrategyDirector.map("taskId",c.task,"owner",c.owner,"unitIds",actorIds,
                "targetId",target==null?null:c.target,"phase",target==null?"RETURN":"RESPOND","requestId",receipt.get("requestId"),"gameTimeMs",time)));
        return true;
    }
    private void reportLocalArmyState(Map<String,Object> state,Map<String,Object> enemies)throws Exception{
        if(time-lastCohortDiagnostic<10000)return;lastCohortDiagnostic=time;
        Map<Long,Map<String,Object>> own=LocalArmyDirector.index(availableMain(state));
        for(LocalArmyDirector.Cohort c:localArmies.rotation()){
            List<Map<String,Object>> members=c.units(own);int noOrder=0,offGoal=0;
            for(Map<String,Object> u:members){if(u.get("orderType")==null)noOrder++;
                if(u.get("orderType")==null&&distance(u,c.goalX,c.goalY)>180)offGoal++;}
            LocalTarget target=localTarget(c,members,enemies,true);
            String legal=target==null?"NONE":target.guard.status;
            event("local_army_diagnostic",json(StrategyDirector.map("cohortId",c.id,"members",members.size(),
                    "unitsWithNoOrder",noOrder,"unitsWithNoOrderAwayFromGoal",offGoal,
                    "lastAcceptedAgeMs",c.lastAcceptedOrder<0?null:time-c.lastAcceptedOrder,
                    "legalTarget",legal,"targetId",target==null?null:id(target.enemy),
                    "frontierAvailable",c.frontierActive?"ACCEPTED_ACTIVE":c.lastPlanStatus,
                    "frontierPlanAgeMs",c.lastPlanAt<0?null:time-c.lastPlanAt,
                    "cooldownRemainingMs",Math.max(0,LocalArmyDirector.ORDER_INTERVAL_MS-(time-c.lastAcceptedOrder)),
                    "globalGateReady",execution.ready(time),"gateOwner",lastCommandAt==time?lastCommandOwner:null,
                    "gatePath",lastCommandAt==time?lastCommandPath:null,"gameTimeMs",time)));
        }
    }
    /** Bounded main recovery precedes ordinary lanes so repeated purchases cannot starve a stopped
     * cohort forever. Null native order alone is not an idle claim: near a contact it may auto-fire.
     */
    private boolean issueIdleLocalRecovery(Map<String,Object> state,Map<String,Object> enemies)throws Exception{
        if(!localArmies.multiple()||!execution.ready(time)||time-lastFairCommand<8000)return false;
        Map<Long,Map<String,Object>> own=LocalArmyDirector.index(availableMain(state));
        for(LocalArmyDirector.Cohort c:localArmies.rotation()){
            if(!c.idleRecoveryDue(time)||time-c.lastAcceptedOrder<16000)continue;
            List<Map<String,Object>> members=c.units(own);
            if(members.size()<LocalArmyDirector.RELEASE_BELOW)continue;
            List<Map<String,Object>> noOrder=new ArrayList<Map<String,Object>>();
            for(Map<String,Object> u:members)if(u.get("orderType")==null)noOrder.add(u);
            if(noOrder.size()<Math.max(2,(members.size()+1)/2))continue;
            LocalTarget choice=localTarget(c,members,enemies,true);
            if(choice!=null){
                if(!currentEnemy(choice.enemy,enemies)||!"COMPATIBLE".equals(choice.guard.status))continue;
                List<Map<String,Object>> idle=new ArrayList<Map<String,Object>>();
                for(Map<String,Object> u:choice.guard.assigned)if(u.get("orderType")==null
                        &&distance(u,n(choice.enemy,"x"),n(choice.enemy,"y"))>180)idle.add(u);
                if(idle.isEmpty())continue;
                logTargetGuard(choice.enemy,choice.guard);
                if(trackTargetProgress(choice.guard.assigned,enemies,choice.enemy))continue;
                if(attackLocal(c,idle,n(choice.enemy,"x"),n(choice.enemy,"y"),"OBSERVED_ENEMY",choice.enemy)){
                    c.frontierActive=false;c.lastIdleRecovery=lastFairCommand=time;
                    event("local_army_fairness_slot",json(StrategyDirector.map("cohortId",c.id,"reason","REAL_NO_ORDER_CURRENT_CONTACT","gameTimeMs",time)));return true;}
            }else if(c.frontierActive){
                List<Map<String,Object>> idle=new ArrayList<Map<String,Object>>();double nearest=Double.MAX_VALUE;
                for(Map<String,Object> u:members){nearest=Math.min(nearest,distance(u,c.goalX,c.goalY));
                    if(u.get("orderType")==null&&distance(u,c.goalX,c.goalY)>180)idle.add(u);}
                if(nearest<65||time-c.progressAt>20000||time-c.frontierAt>75000)continue;
                if(!idle.isEmpty()&&attackLocal(c,idle,c.goalX,c.goalY,"REINFORCE",null)){c.lastIdleRecovery=lastFairCommand=time;
                    event("local_army_fairness_slot",json(StrategyDirector.map("cohortId",c.id,"reason","REAL_NO_ORDER_ACCEPTED_FRONTIER","gameTimeMs",time)));return true;}
            }else if(time-c.lastPlanAt>=8000){
                Map<String,Object> anchor=noOrder.get(0);StringBuilder exclude=new StringBuilder();
                for(Long tile:c.avoided){if(exclude.length()>0)exclude.append(',');exclude.append(tile);}
                Map<String,Object> plan=optionalGet("/scout/plan?role=army&unitId="+id(anchor)+"&avoid="+exclude,"local_army_frontier_plan");
                c.lastPlanAt=time;c.lastPlanStatus=plan==null?"UNKNOWN":stringOrNull(plan,"status");
                if(plan!=null&&"planned".equals(plan.get("status"))&&Boolean.TRUE.equals(plan.get("pathKnown"))
                        &&plan.get("targetTile") instanceof Number&&plan.get("targetX") instanceof Number&&plan.get("targetY") instanceof Number){
                    check(plan);
                    if(attackLocal(c,members,n(plan,"targetX"),n(plan,"targetY"),"KNOWN_FRONTIER",null)){
                        c.lastIdleRecovery=lastFairCommand=time;c.frontierActive=true;c.frontierAt=c.progressAt=time;
                        c.bestDistance=distance(anchor,c.goalX,c.goalY);c.frontierTile=(long)n(plan,"targetTile");
                        event("local_army_fairness_slot",json(StrategyDirector.map("cohortId",c.id,"reason","REAL_NO_ORDER_NEW_LEGAL_FRONTIER","gameTimeMs",time)));return true;}
                }else{
                    LocalTarget distant=localTarget(c,members,enemies,false);
                    if(distant!=null&&"COMPATIBLE".equals(distant.guard.status)){
                        logTargetGuard(distant.enemy,distant.guard);
                        if(!trackTargetProgress(distant.guard.assigned,enemies,distant.enemy)
                                &&attackLocal(c,distant.guard.assigned,n(distant.enemy,"x"),n(distant.enemy,"y"),"REMOTE_VISIBLE_CONTACT",distant.enemy)){
                            c.lastIdleRecovery=lastFairCommand=time;
                            event("local_army_fairness_slot",json(StrategyDirector.map("cohortId",c.id,"reason","REAL_NO_ORDER_REMOTE_VISIBLE_CONTACT","gameTimeMs",time)));return true;}
                    }
                }
            }
        }
        return false;
    }
    private void tactics(Map<String,Object> state,Map<String,Object> enemies)throws Exception{
        List<Map<String,Object>> army=availableMain(state);
        if(army.isEmpty())return;
        if(issueCriticalMainRetreat(state))return;
        List<Map<String,Object>> force=new ArrayList<Map<String,Object>>();for(Map<String,Object> u:army){Long until=resting.get(id(u));if(until==null||time>=until)force.add(u);}
        if(force.isEmpty())return;
        observeLocalArmies(state);
        boolean multiple=localArmies.multiple();
        if(multiple){localTactics(state,enemies,force);return;}
        // P1-B: record any fresh legal observation as a search objective. This has to happen before
        // the tactical layers return, because the objective is only used once they have nothing left.
        updateSearchTargets(state,enemies);
        double cx=0,cy=0;for(Map<String,Object> u:force){cx+=n(u,"x");cy+=n(u,"y");}cx/=force.size();cy/=force.size();
        Map<String,Object> target=null;GuardSelection targetGuard=null;double score=Double.MAX_VALUE;
        boolean targetVisible=false;
        for(Map<String,Object> e:list(enemies,"rememberedEnemies")){
            if(servedByLocalCrisis(e))continue;
            TargetProgress tracked=targetProgress.get(Long.valueOf(id(e)));
            if(tracked!=null)tracked.lastSeenAt=time;
            // 杈撳嚭30 搂4: a target whose HP has not moved inside the window is not a candidate until
            // its cooldown expires. It is never removed from the world model, only benched.
            if(deprioritized(e))continue;
            GuardSelection candidate=targetGuard(force,e,enemies);
            TargetSuppression suppression=targetSuppressions.get(Long.valueOf(id(e)));
            if(suppression!=null){
                targetSuppressedSelections++;
                if(!suppression.holdReported){
                    Map<String,Object> data=new LinkedHashMap<String,Object>(suppression.evidence);
                    data.put("currentGuardStatus",candidate.status);
                    data.put("reason","PRIOR_INCOMPATIBILITY_REQUIRES_NEW_COMPATIBLE_EVIDENCE");
                    data.put("gameTimeMs",Long.valueOf(time));
                    event("target_selection_suppressed",json(data));suppression.holdReported=true;
                }
                logTargetGuard(e,candidate);continue;
            }
            if("INCOMPATIBLE".equals(candidate.status)){
                logTargetGuard(e,candidate);
                continue;
            }
            List<Map<String,Object>> eligible=strategy.eligible(e,candidate.assigned);
            if(eligible.isEmpty()){
                logTargetGuard(e,candidate);
                event("engagement_selection_blocked",json(StrategyDirector.map("targetId",id(e),
                    "reason","PRIOR_OR_CURRENT_TERRAIN_APPROACH_REJECTION","gameTimeMs",time)));
                continue;
            }
            if(eligible.size()!=candidate.assigned.size())candidate=targetGuard(eligible,e,enemies);
            double d=Math.hypot(n(e,"x")-cx,n(e,"y")-cy);
            if(n(e,"lastSeenGameTimeMs")==n(enemies,"gameTimeMs")&&Math.hypot(n(e,"x")-homeX,n(e,"y")-homeY)<450&&Boolean.TRUE.equals(e.get("canAttack")))d-=3000;
            if(Boolean.TRUE.equals(e.get("building")))d-=150;
            if("UNKNOWN".equals(candidate.status))d+=120; // retain the old legal flow without preferring uncertainty
            // A legal current contact outranks a last-seen hypothesis. Distance/building scoring
            // still chooses within each tier; UNKNOWN remains UNKNOWN and is not a hard rejection.
            boolean visible=currentEnemy(e,enemies);
            if(target==null||visible&&!targetVisible||visible==targetVisible&&d<score){
                score=d;target=e;targetGuard=candidate;targetVisible=visible;
            }
        }
        pruneTargetProgress();
        if(!marching&&force.size()>=6)marching=true;
        if(marching&&force.size()<3){marching=false;targetReady=false;event("army_regroup","{\"survivors\":"+force.size()+"}");}
        if(!marching){if(time-lastTactic>10000&&Math.hypot(cx-homeX,cy-homeY)>250)attack(force,homeX,homeY,"REGROUP",null);return;}
        if(target!=null){
            logTargetGuard(target,targetGuard);
            // 杈撳嚭30 搂4: check progress before spending an order, so the tick that gives up on a
            // target is also the tick that stops feeding it orders.
            if(trackTargetProgress(targetGuard.assigned,enemies,target))return;
            announceRetryIfAny(target);
            double x=n(target,"x"),y=n(target,"y");
            if(time-lastTactic>=8000||Math.hypot(x-targetX,y-targetY)>160){
                attack(targetGuard.assigned,x,y,"OBSERVED_ENEMY",target);targetReady=false;
            }
            return;
        }
        // P1-B: no visible enemy and no tactical memory left, so fall back to re-checking the last
        // place an enemy was legally observed. Stickiness: only a new observation, an unreachable
        // verdict or a confirmed-empty verdict may change this objective.
        if(listSize(enemies,"rememberedEnemies")==0&&searchTarget!=null){
            if(confirmSearchTarget()||marchToSearchTarget(force))return;
        }
        Map<String,Object> scout=find(state,leader);
        if(scout!=null&&((reconTask!=null&&(id(scout)==reconTask.unitId
                ||reconTask.frontier()&&id(scout)==reconTask.preferredUnitId))
                ||expendableScouts.contains(Long.valueOf(id(scout)))))scout=null;
        if(targetReady){
            if(scout==null||!alive(scout)){targetReady=false;}
            else{
                double d=distance(scout,targetX,targetY);
                for(Map<String,Object> member:force)if(distance(member,targetX,targetY)<d){scout=member;d=distance(member,targetX,targetY);}
                if(d+12<best){best=d;progressAt=time;}
                if(d<65||time-progressAt>20000||time-targetAt>75000){
                    event(d<65?"army_frontier_arrived":"army_frontier_blocked","{\"unitId\":"+id(scout)+",\"targetTile\":"+targetTile+",\"distance\":"+d+"}");
                    if(targetTile>=0){avoided.add(targetTile);if(avoided.size()>48)avoided.remove(0);}targetReady=false;
                }else{
                    List<Map<String,Object>> idle=new ArrayList<Map<String,Object>>();for(Map<String,Object> u:force)if(!"attackMove".equals(u.get("orderType"))&&distance(u,targetX,targetY)>180)idle.add(u);
                    if(!idle.isEmpty()&&time-lastTactic>6000)attack(idle,targetX,targetY,"REINFORCE",null);return;
                }
            }
        }
        scout=force.get(0);for(Map<String,Object> u:force)if(distance(u,cx,cy)<distance(scout,cx,cy))scout=u;
        leader=id(scout);StringBuilder exclude=new StringBuilder();for(long tile:avoided){if(exclude.length()>0)exclude.append(',');exclude.append(tile);}
        Map<String,Object> plan=optionalGet("/scout/plan?role=army&unitId="+leader+"&avoid="+exclude,"army_frontier_plan");
        lastPlan=plan;
        if(plan==null||!"planned".equals(plan.get("status")))return;check(plan);
        if(attack(force,n(plan,"targetX"),n(plan,"targetY"),"KNOWN_FRONTIER",null)){
            targetReady=true;targetAt=progressAt=time;best=distance(scout,targetX,targetY);targetTile=((Number)plan.get("targetTile")).longValue();
        }
    }

    /** Multiple main groups keep independent local goals. All commands still use the default main
     * owner and the single CommandArbiter; a cohort label never grants an actor a task lease.
     */
    private void localTactics(Map<String,Object> state,Map<String,Object> enemies,List<Map<String,Object>> force)throws Exception{
        updateSearchTargets(state,enemies);pruneTargetProgress();marching=true;
        Map<Long,Map<String,Object>> own=LocalArmyDirector.index(force);
        Map<Long,LocalTarget> choices=new LinkedHashMap<Long,LocalTarget>();
        Map<Long,List<Map<String,Object>>> engaging=new LinkedHashMap<Long,List<Map<String,Object>>>();
        Map<Long,Map<String,Object>> chosenEnemies=new LinkedHashMap<Long,Map<String,Object>>();
        // Observe progress for every accepted engagement before any one group consumes the command.
        // Two groups attacking the same target contribute a single own-actor union to the old guard.
        for(LocalArmyDirector.Cohort cohort:localArmies.rotation()){
            List<Map<String,Object>> members=cohort.units(own);
            LocalTarget choice=localTarget(cohort,members,enemies,true);
            if(choice==null)continue;
            choices.put(cohort.id,choice);logTargetGuard(choice.enemy,choice.guard);
            long target=id(choice.enemy);List<Map<String,Object>> combined=engaging.get(target);
            if(combined==null){combined=new ArrayList<Map<String,Object>>();engaging.put(target,combined);}
            combined.addAll(choice.guard.assigned);chosenEnemies.put(target,choice.enemy);
        }
        Set<Long> stalled=new HashSet<Long>();
        for(Map.Entry<Long,List<Map<String,Object>>> engagement:engaging.entrySet()){
            Map<String,Object> target=chosenEnemies.get(engagement.getKey());
            if(trackTargetProgress(engagement.getValue(),enemies,target))stalled.add(engagement.getKey());
            else announceRetryIfAny(target);
        }
        for(LocalArmyDirector.Cohort cohort:localArmies.rotation()){
            List<Map<String,Object>> members=cohort.units(own);
            if(members.size()<LocalArmyDirector.RELEASE_BELOW)continue;
            LocalTarget choice=choices.get(cohort.id);
            if(choice!=null){
                Map<String,Object> target=choice.enemy;GuardSelection guard=choice.guard;
                if(stalled.contains(id(target)))continue;
                boolean changed=cohort.targetId==null||cohort.targetId.longValue()!=id(target);
                boolean recover=false;
                for(Map<String,Object> u:guard.assigned)if(u.get("orderType")==null
                        &&distance(u,n(target,"x"),n(target,"y"))>180&&cohort.idleRecoveryDue(time))recover=true;
                if((cohort.due(time)||changed||recover||Math.hypot(n(target,"x")-cohort.goalX,n(target,"y")-cohort.goalY)>160)
                        &&attackLocal(cohort,guard.assigned,n(target,"x"),n(target,"y"),"OBSERVED_ENEMY",target)){
                    if(recover&&!cohort.due(time))cohort.lastIdleRecovery=time;
                    cohort.frontierActive=false;return;
                }
                continue;
            }
            if(cohort.frontierActive){
                double nearest=Double.MAX_VALUE;
                for(Map<String,Object> unit:members)nearest=Math.min(nearest,distance(unit,cohort.goalX,cohort.goalY));
                if(nearest+12<cohort.bestDistance){cohort.bestDistance=nearest;cohort.progressAt=time;}
                if(nearest<65||time-cohort.progressAt>20000||time-cohort.frontierAt>75000){
                    event(nearest<65?"local_army_frontier_arrived":"local_army_frontier_blocked",json(
                            StrategyDirector.map("cohortId",cohort.id,"targetTile",cohort.frontierTile,
                                "distance",nearest,"gameTimeMs",time)));
                    if(cohort.frontierTile>=0){cohort.avoided.add(cohort.frontierTile);
                        if(cohort.avoided.size()>16)cohort.avoided.remove(cohort.avoided.iterator().next());}
                    cohort.frontierActive=false;
                }else{
                    List<Map<String,Object>> idle=new ArrayList<Map<String,Object>>();
                    for(Map<String,Object> unit:members)if(!onAttackGoal(unit,cohort.goalX,cohort.goalY)
                            &&distance(unit,cohort.goalX,cohort.goalY)>180)idle.add(unit);
                    boolean recover=false;for(Map<String,Object> u:idle)if(u.get("orderType")==null&&cohort.idleRecoveryDue(time))recover=true;
                    if(!idle.isEmpty()&&(cohort.due(time)||recover)&&attackLocal(cohort,idle,cohort.goalX,cohort.goalY,"REINFORCE",null)){
                        if(recover)cohort.lastIdleRecovery=time;return;}
                    continue;
                }
            }
            if(time-cohort.lastPlanAt<8000)continue;
            Map<String,Object> anchor=members.get(0);
            for(Map<String,Object> member:members)if(distance(member,cohort.x,cohort.y)<distance(anchor,cohort.x,cohort.y))anchor=member;
            StringBuilder exclude=new StringBuilder();for(Long tile:cohort.avoided){if(exclude.length()>0)exclude.append(',');exclude.append(tile);}
            Map<String,Object> plan=optionalGet("/scout/plan?role=army&unitId="+id(anchor)+"&avoid="+exclude,"local_army_frontier_plan");
            cohort.lastPlanAt=time;
            cohort.lastPlanStatus=plan==null?"UNKNOWN":stringOrNull(plan,"status");
            if(plan==null||!"planned".equals(plan.get("status"))){
                // Fully explored areas may have no frontier. A real distant current contact still
                // provides a legal advance objective for this group without replacing other groups.
                LocalTarget distant=localTarget(cohort,members,enemies,false);
                if(distant!=null&&(cohort.due(time)||cohort.targetId==null
                        ||cohort.targetId.longValue()!=id(distant.enemy))){
                    logTargetGuard(distant.enemy,distant.guard);
                    if(attackLocal(cohort,distant.guard.assigned,n(distant.enemy,"x"),n(distant.enemy,"y"),
                            "REMOTE_VISIBLE_CONTACT",distant.enemy))return;
                }
                continue;
            }
            check(plan);
            if(!(plan.get("targetX") instanceof Number)||!(plan.get("targetY") instanceof Number)
                    ||!(plan.get("targetTile") instanceof Number))continue;
            if(attackLocal(cohort,members,n(plan,"targetX"),n(plan,"targetY"),"KNOWN_FRONTIER",null)){
                cohort.frontierActive=true;cohort.frontierAt=cohort.progressAt=time;
                cohort.bestDistance=distance(anchor,cohort.goalX,cohort.goalY);
                cohort.frontierTile=((Number)plan.get("targetTile")).longValue();return;
            }
        }
    }

    private static final class LocalTarget {
        final Map<String,Object> enemy;final GuardSelection guard;
        LocalTarget(Map<String,Object> enemy,GuardSelection guard){this.enemy=enemy;this.guard=guard;}
    }
    private LocalTarget localTarget(LocalArmyDirector.Cohort cohort,List<Map<String,Object>> members,
                                    Map<String,Object> enemies,boolean localOnly)throws Exception{
        Map<String,Object> target=null,held=null;GuardSelection guard=null,heldGuard=null;
        double score=Double.MAX_VALUE;boolean visibleChoice=false;
        for(Map<String,Object> enemy:list(enemies,"rememberedEnemies")){
            if(servedByLocalCrisis(enemy))continue;
            if(deprioritized(enemy)||targetSuppressions.containsKey(id(enemy)))continue;
            double d=Math.hypot(n(enemy,"x")-cohort.x,n(enemy,"y")-cohort.y);
            if(localOnly&&d>LocalArmyDirector.LOCAL_TARGET_RADIUS)continue;
            boolean visible=currentEnemy(enemy,enemies);
            if(!localOnly&&!visible)continue;
            Long seen=numberOrNull(enemy,"lastSeenGameTimeMs");
            // A local lost contact is a short-lived hypothesis, never a present threat claim.
            if(!visible&&(seen==null||time-seen.longValue()>LocalArmyDirector.MEMORY_HOLD_MS))continue;
            GuardSelection candidate=targetGuard(members,enemy,enemies);
            if("INCOMPATIBLE".equals(candidate.status)){logTargetGuard(enemy,candidate);continue;}
            List<Map<String,Object>> eligible=strategy.eligible(enemy,candidate.assigned);
            if(eligible.isEmpty())continue;
            if(eligible.size()!=candidate.assigned.size())candidate=targetGuard(eligible,enemy,enemies);
            if(cohort.targetId!=null&&cohort.targetId.longValue()==id(enemy)){held=enemy;heldGuard=candidate;}
            double rank=d-(Boolean.TRUE.equals(enemy.get("building"))?150:0)+("UNKNOWN".equals(candidate.status)?120:0);
            if(target==null||visible&&!visibleChoice||visible==visibleChoice&&rank<score){
                target=enemy;guard=candidate;score=rank;visibleChoice=visible;
            }
        }
        // Fresh contacts retain precedence over a lost-contact hypothesis. Within that tier a
        // still-local accepted target is sticky instead of reacting to every remote sighting.
        if(held!=null&&(currentEnemy(held,enemies)||!visibleChoice)){target=held;guard=heldGuard;}
        return target==null?null:new LocalTarget(target,guard);
    }

    private boolean attackLocal(LocalArmyDirector.Cohort cohort,List<Map<String,Object>> force,double x,double y,
                                String why,Map<String,Object> enemy)throws Exception{
        if(force.isEmpty()||force.size()>LocalArmyDirector.MAX_MEMBERS)throw new IllegalArgumentException("Invalid local command group");
        List<Long> actors=new ArrayList<Long>();StringBuilder ids=new StringBuilder();
        for(Map<String,Object> unit:force){
            if(!cohort.memberIds().contains(id(unit))||execution.reserved(id(unit)))throw new IllegalArgumentException("Actor outside available cohort");
            actors.add(id(unit));if(ids.length()>0)ids.append(',');ids.append(id(unit));
        }
        event("tactical_intent",json(StrategyDirector.map("cohortId",cohort.id,"reason",why,"targetX",x,"targetY",y,
                "unitIds",actors,"enemy",enemy,"gameTimeMs",time)));
        Map<String,Object> receipt=post("/command/attack-move?unitIds="+ids+"&x="+x+"&y="+y);
        if(receipt==null)return false;
        Long previous=cohort.targetId,next=enemy==null?null:Long.valueOf(id(enemy));
        if(enemy!=null)noteTargetOrder(id(enemy),previous);
        localArmies.accepted(cohort,time,x,y,next);
        // These shared fields are the last-order diagnostics and receipts, not local scheduling state.
        selectedTargetEnemyId=next;targetX=x;targetY=y;lastTactic=time;attacks++;awaitingOrder=receipt;
        event("local_army_order",json(StrategyDirector.map("cohortId",cohort.id,"unitIds",actors,"targetId",next,
                "previousTargetId",previous,"reason",why,"targetX",x,"targetY",y,"gameTimeMs",time,
                "requestId",receipt.get("requestId"),"receiptStatus",receipt.get("status"))));
        return true;
    }

    private static final class GuardSelection{
        final String status,reason,sourceId;
        final List<Map<String,Object>> assigned,candidates;
        final List<Map<String,Object>> actorDecisions;
        final long observedAt;
        final int compatible,unknown,incompatible;
        GuardSelection(String status,String reason,String sourceId,List<Map<String,Object>> assigned,
                       List<Map<String,Object>> candidates,List<Map<String,Object>> actorDecisions,
                       long observedAt,int compatible,int unknown,int incompatible){
            this.status=status;this.reason=reason;this.sourceId=sourceId;this.assigned=assigned;this.candidates=candidates;
            this.actorDecisions=actorDecisions;
            this.observedAt=observedAt;this.compatible=compatible;this.unknown=unknown;this.incompatible=incompatible;
        }
    }
    private static boolean currentEnemy(Map<String,Object> enemy,Map<String,Object> enemies){
        Long now=numberOrNull(enemies,"gameTimeMs"),seen=numberOrNull(enemy,"lastSeenGameTimeMs");
        if(now==null||seen==null||!now.equals(seen))return false;
        for(Map<String,Object> visible:list(enemies,"visibleEnemies"))
            if(id(visible)==id(enemy))return true;
        return false;
    }
    private void updateTargetSuppressions(Map<String,Object> state,Map<String,Object> enemies)throws Exception{
        List<Map<String,Object>> force=new ArrayList<Map<String,Object>>();
        for(Map<String,Object> unit:mainArmy(state)){
            Long until=resting.get(id(unit));if(until==null||time>=until)force.add(unit);
        }
        // Lack of an army is not evidence that a target is incompatible.
        if(force.isEmpty())return;
        for(Map<String,Object> enemy:list(enemies,"visibleEnemies")){
            if(!currentEnemy(enemy,enemies))continue;
            GuardSelection guard=targetGuard(force,enemy,enemies);
            Long now=numberOrNull(enemies,"gameTimeMs");
            if(now==null||guard.observedAt!=now.longValue())continue;
            Long enemyId=Long.valueOf(id(enemy));TargetSuppression previous=targetSuppressions.get(enemyId);
            if(previous!=null&&guard.observedAt<previous.observedAt)continue;
            Map<String,Object> proof=new LinkedHashMap<String,Object>();
            proof.put("targetId",enemyId);proof.put("targetType",enemy.get("type"));
            proof.put("observedTargetDomain",enemy.get("targetDomain"));
            proof.put("observedTouchingWater",enemy.get("touchingWater"));
            proof.put("observedAtGameTimeMs",Long.valueOf(guard.observedAt));
            proof.put("reason",guard.reason);proof.put("sourceId",guard.sourceId);
            proof.put("domainSourceId",enemy.get("domainSourceId"));
            proof.put("catalogSha256",TargetCatalog.catalogSha256());
            proof.put("actorDecisions",guard.actorDecisions);
            proof.put("sessionId",session);proof.put("player",execution.stamp().player);
            proof.put("gameTimeMs",Long.valueOf(time));
            if("INCOMPATIBLE".equals(guard.status)&&guard.incompatible>0){
                if(previous==null){
                    targetSuppressions.put(enemyId,new TargetSuppression(guard.observedAt,proof));
                    targetSuppressionsStarted++;event("target_suppression_started",json(proof));
                }else{previous.observedAt=guard.observedAt;previous.evidence=proof;}
                // An old search objective must not bypass the strategic exclusion after memory expiry.
                if(searchTarget!=null&&searchTarget.sourceEnemyId==enemyId.longValue()){
                    event("search_target_suppressed","{\"targetId\":"+searchTarget.targetId
                        +",\"sourceEnemyId\":"+enemyId+",\"reason\":\"CURRENT_INCOMPATIBILITY\",\"gameTimeMs\":"+time+"}");
                    searchTarget=null;searchNeedsOrder=false;
                }
            }else if(previous!=null&&"COMPATIBLE".equals(guard.status)&&guard.observedAt>previous.observedAt){
                targetSuppressions.remove(enemyId);targetSuppressionsReleased++;
                proof.put("previousRejectionObservedAtGameTimeMs",Long.valueOf(previous.observedAt));
                proof.put("reason","NEWER_VISIBLE_COMPATIBLE_EVIDENCE");
                event("target_suppression_released",json(proof));
            }
        }
    }
    private GuardSelection targetGuard(List<Map<String,Object>> force,Map<String,Object> enemy,
                                        Map<String,Object> enemies){
        boolean trusted=Boolean.TRUE.equals(enemies.get("catalogGameJarMatched"))
                &&TargetCatalog.catalogSha256()!=null
                &&TargetCatalog.catalogSha256().equals(enemies.get("catalogSha256"));
        long observedAt=enemy.get("domainObservedAtGameTimeMs") instanceof Number
                ?((Number)enemy.get("domainObservedAtGameTimeMs")).longValue():-1;
        long now=enemies.get("gameTimeMs") instanceof Number
                ?((Number)enemies.get("gameTimeMs")).longValue():-1;
        boolean fresh=currentEnemy(enemy,enemies)&&now>=0&&observedAt==now;
        String domain=stringOrNull(enemy,"targetDomain");
        Boolean water=enemy.get("touchingWater") instanceof Boolean?(Boolean)enemy.get("touchingWater"):null;
        List<Map<String,Object>> compatibleUnits=new ArrayList<Map<String,Object>>();
        List<Map<String,Object>> unknownUnits=new ArrayList<Map<String,Object>>();
        List<Map<String,Object>> actorDecisions=new ArrayList<Map<String,Object>>();
        int incompatible=0;String firstReason=null;
        for(Map<String,Object> actor:force){
            TargetCatalog.Decision d=TargetCatalog.evaluate(stringOrNull(actor,"type"),domain,water,fresh,trusted);
            if(firstReason==null)firstReason=d.reason;
            Map<String,Object> actorDecision=new LinkedHashMap<String,Object>();
            actorDecision.put("unitId",Long.valueOf(id(actor)));actorDecision.put("unitType",actor.get("type"));
            actorDecision.put("status",d.status);actorDecision.put("reason",d.reason);
            actorDecisions.add(actorDecision);
            if("COMPATIBLE".equals(d.status))compatibleUnits.add(actor);
            else if("UNKNOWN".equals(d.status))unknownUnits.add(actor);
            else incompatible++;
        }
        String status,reason;List<Map<String,Object>> assigned;
        if(!compatibleUnits.isEmpty()){
            status="COMPATIBLE";assigned=compatibleUnits;
            reason=incompatible>0||!unknownUnits.isEmpty()?"MIXED_FORCE_COMPATIBLE_SUBSET":firstReason;
        }else if(!unknownUnits.isEmpty()){
            status="UNKNOWN";assigned=unknownUnits;
            reason=incompatible>0?"MIXED_FORCE_UNKNOWN_FALLBACK":firstReason;
        }else{
            status="INCOMPATIBLE";assigned=Collections.<Map<String,Object>>emptyList();
            reason=firstReason==null?"NO_ATTACKERS":"ALL_ATTACKERS_INCOMPATIBLE_"+firstReason;
        }
        return new GuardSelection(status,reason,TargetCatalog.sourceId(),assigned,force,actorDecisions,observedAt,
                compatibleUnits.size(),unknownUnits.size(),incompatible);
    }
    private void logTargetGuard(Map<String,Object> enemy,GuardSelection guard)throws Exception{
        if(guard==null)return;
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("targetId",Long.valueOf(id(enemy)));data.put("targetType",enemy.get("type"));
        data.put("targetDomain",enemy.get("targetDomain"));
        data.put("status",guard.status);data.put("reason",guard.reason);data.put("sourceId",guard.sourceId);
        data.put("domainSourceId",enemy.get("domainSourceId"));
        data.put("observedAtGameTimeMs",guard.observedAt<0?null:Long.valueOf(guard.observedAt));
        data.put("catalogSha256",TargetCatalog.catalogSha256());
        data.put("compatibleAttackers",Integer.valueOf(guard.compatible));
        data.put("unknownAttackers",Integer.valueOf(guard.unknown));
        data.put("incompatibleAttackers",Integer.valueOf(guard.incompatible));
        List<Long> assigned=new ArrayList<Long>();
        for(Map<String,Object> actor:guard.assigned)assigned.add(Long.valueOf(id(actor)));
        List<Long> candidates=new ArrayList<Long>();
        for(Map<String,Object> actor:guard.candidates)candidates.add(Long.valueOf(id(actor)));
        data.put("candidateUnitIds",candidates);
        data.put("actorDecisions",guard.actorDecisions);
        data.put("assignedUnitIds",assigned);data.put("gameTimeMs",Long.valueOf(time));
        event("target_guard",json(data));
    }
    private TargetProgress progressOf(Map<String,Object> enemy){
        long enemyId=id(enemy);TargetProgress p=targetProgress.get(Long.valueOf(enemyId));
        if(p==null){p=new TargetProgress(enemyId,stringOrNull(enemy,"type"));targetProgress.put(Long.valueOf(enemyId),p);}
        return p;
    }
    /** A target under cooldown is simply not a candidate; the army looks elsewhere or resumes searching. */
    private boolean deprioritized(Map<String,Object> enemy){
        TargetProgress p=targetProgress.get(Long.valueOf(id(enemy)));
        return p!=null&&!p.active&&p.deprioritizedUntil>time;
    }
    /**
     * Book-keeping for an order that the bridge accepted against a specific enemy. Switching targets is
     * counted here, from the order stream rather than from intent, so "flapping" is measurable.
     */
    private void noteTargetOrder(long enemyId,Long previousTarget)throws Exception{
        TargetProgress p=targetProgress.get(Long.valueOf(enemyId));
        if(p==null){p=new TargetProgress(enemyId,"unknown");targetProgress.put(Long.valueOf(enemyId),p);}
        p.ordersIssued++;p.lastOrderedAt=time;
        if(previousTarget==null||previousTarget.longValue()!=enemyId){
            p.engagements++;p.engageStartedAt=time;p.damageObserved=false;p.retryAnnounced=true;
            targetSwitches++;
            event("target_switch","{\"from\":"+(previousTarget==null?"null":previousTarget.toString())
                +",\"to\":"+enemyId+",\"targetType\":"+Json.quote(p.type==null?"unknown":p.type)
                +",\"engagements\":"+p.engagements+",\"switchCount\":"+targetSwitches
                +",\"gameTimeMs\":"+time+"}");
        }
    }
    /**
     * Returns true when this tick deprioritised the current target, in which case the caller must not
     * spend another order on it: the next tick picks a different target or falls through to searching.
     */
    private boolean trackTargetProgress(List<Map<String,Object>> force,Map<String,Object> enemies,
                                        Map<String,Object> target)throws Exception{
        TargetProgress p=progressOf(target);p.lastSeenAt=time;
        if(p.ordersIssued==0)return false;   // never ordered against it yet: nothing to be stalled at
        // A gap in attention restarts the stretch: only continuous engagement may conclude "no progress".
        if(p.lastTrackedAt>0&&time-p.lastTrackedAt>noProgressAttentionGapMs())p.visibleSince=0;
        p.lastTrackedAt=time;
        // A field the bridge does not report must never end the match: this whole layer is optional
        // book-keeping, so a missing number simply suspends the window instead of throwing.
        Long observedTime=numberOrNull(enemies,"gameTimeMs");
        if(observedTime==null){p.visibleSince=0;return false;}
        // Only a fresh observation of the target is evidence about its HP.
        Map<String,Object> fresh=null;
        for(Map<String,Object> e:list(enemies,"visibleEnemies")){
            Long seen=numberOrNull(e,"lastSeenGameTimeMs");
            if(id(e)==p.enemyId&&seen!=null&&seen.longValue()==observedTime.longValue()){fresh=e;break;}
        }
        if(fresh==null){p.visibleSince=0;return false;}
        Long hpValue=numberOrNull(fresh,"hp"),fx=numberOrNull(fresh,"x"),fy=numberOrNull(fresh,"y");
        if(hpValue==null||fx==null||fy==null){p.visibleSince=0;return false;}
        double tx=fx.doubleValue(),ty=fy.doubleValue(),nearest=Double.MAX_VALUE;
        for(Map<String,Object> unit:force)nearest=Math.min(nearest,distance(unit,tx,ty));
        // "The main force has closed in" is part of the criterion, so distance resets the stretch too.
        if(nearest>noProgressEngageRange){p.visibleSince=0;return false;}
        double hp=hpValue.doubleValue();
        if(p.visibleSince==0){p.visibleSince=time;p.lowestHp=hp;return false;}
        if(hp<p.lowestHp-0.001){
            p.lowestHp=hp;p.visibleSince=time;
            if(!p.damageObserved){
                p.damageObserved=true;
                event("target_progress","{\"targetId\":"+p.enemyId+",\"hp\":"+hp
                    +",\"sinceEngageMs\":"+(time-p.engageStartedAt)+",\"ordersIssued\":"+p.ordersIssued
                    +",\"gameTimeMs\":"+time+"}");
            }
            return false;
        }
        long stalled=time-p.visibleSince;
        if(stalled<noProgressWindowMs)return false;
        p.deprioritizeCount++;p.deprioritizedUntil=time+noProgressCooldownMs;
        p.active=false;p.visibleSince=0;p.retryAnnounced=false;
        noProgressTriggers++;
        event("target_no_progress","{\"targetId\":"+p.enemyId+",\"targetType\":"+Json.quote(p.type==null?"unknown":p.type)
            +",\"stalledMs\":"+stalled+",\"windowMs\":"+noProgressWindowMs+",\"ordersIssued\":"+p.ordersIssued
            +",\"lowestHpSeen\":"+p.lowestHp+",\"hpNow\":"+hp+",\"nearestUnitDistance\":"+Math.round(nearest)
            +",\"deprioritizeCount\":"+p.deprioritizeCount+",\"gameTimeMs\":"+time+"}");
        event("target_deprioritized","{\"targetId\":"+p.enemyId+",\"reason\":\"NO_PROGRESS\""
            +",\"cooldownMs\":"+noProgressCooldownMs+",\"retryAtGameTimeMs\":"+p.deprioritizedUntil
            +",\"deprioritizeCount\":"+p.deprioritizeCount+",\"gameTimeMs\":"+time+"}");
        return true;
    }
    /** A target that comes back after its cooldown is a retry, with a fresh window and a fresh clock. */
    private void announceRetryIfAny(Map<String,Object> target)throws Exception{
        TargetProgress p=targetProgress.get(Long.valueOf(id(target)));
        if(p==null||p.active||p.retryAnnounced)return;
        p.active=true;p.retryAnnounced=true;p.visibleSince=0;p.lowestHp=Double.MAX_VALUE;
        p.engageStartedAt=time;p.damageObserved=false;
        noProgressRetries++;
        event("target_retry","{\"targetId\":"+p.enemyId+",\"afterCooldownMs\":"+noProgressCooldownMs
            +",\"retryAtGameTimeMs\":"+p.deprioritizedUntil+",\"deprioritizeCount\":"+p.deprioritizeCount
            +",\"retries\":"+noProgressRetries+",\"gameTimeMs\":"+time+"}");
    }
    /** Bounded memory: the oldest entry is dropped rather than letting the map grow with the match. */
    private void pruneTargetProgress(){
        if(targetProgress.size()<=64)return;
        Long oldest=null;long oldestAt=Long.MAX_VALUE;
        for(Map.Entry<Long,TargetProgress> entry:targetProgress.entrySet()){
            TargetProgress p=entry.getValue();
            if(!p.active&&p.deprioritizedUntil>time)continue;   // never forget a live cooldown
            if(p.lastSeenAt<oldestAt){oldestAt=p.lastSeenAt;oldest=entry.getKey();}
        }
        if(oldest!=null)targetProgress.remove(oldest);
    }
    /**
     * Only a fresh legal observation may create or refresh a search objective. Remembered enemies,
     * already consumed observations and previously unreachable ones may never revive an objective.
     */
    private void updateSearchTargets(Map<String,Object> state,Map<String,Object> enemies)throws Exception{        Map<String,Object> map=obj(state.get("map"));
        // A state without tile metadata cannot address an objective; never let that kill the battle.
        if(!(map.get("tilesWide") instanceof Number)||!(map.get("tilesHigh") instanceof Number)
                ||!(map.get("tileWidth") instanceof Number)||!(map.get("tileHeight") instanceof Number))return;
        long tilesWide=(long)n(map,"tilesWide"),tilesHigh=(long)n(map,"tilesHigh");
        long tileWidth=(long)n(map,"tileWidth"),tileHeight=(long)n(map,"tileHeight");
        Map<String,Object> best=null;double bestDistance=Double.MAX_VALUE;
        for(Map<String,Object> e:list(enemies,"visibleEnemies")){
            // Do not turn a fresh, clearly incompatible target into a later fog search order.
            if(targetSuppressions.containsKey(Long.valueOf(id(e))))continue;
            if(strategy.rejected(id(e)))continue;
            if("INCOMPATIBLE".equals(targetGuard(mainArmy(state),e,enemies).status))continue;
            long enemyId=id(e),seen=(long)n(e,"lastSeenGameTimeMs");
            Long consumed=consumedObservations.get(Long.valueOf(enemyId));
            Long unreachable=unreachableObservations.get(Long.valueOf(enemyId));
            if(consumed!=null&&seen<=consumed.longValue())continue;
            if(unreachable!=null&&seen<=unreachable.longValue())continue;
            if(searchTarget!=null&&searchTarget.sourceEnemyId==enemyId&&searchTarget.lastSeenGameTime>=seen)continue;
            double d=Math.hypot(n(e,"x")-homeX,n(e,"y")-homeY);
            if(d<bestDistance){bestDistance=d;best=e;}
        }
        if(best==null)return;
        long enemyId=id(best),seen=(long)n(best,"lastSeenGameTimeMs");
        double x=n(best,"x"),y=n(best,"y");
        long tile=tileFor(x,y,tilesWide,tilesHigh,tileWidth,tileHeight);
        if(searchTarget!=null&&searchTarget.sourceEnemyId==enemyId){
            // The same enemy seen again only moves the hypothesis; it never becomes a second objective.
            boolean moved=searchTarget.tile!=tile;
            searchTarget.x=x;searchTarget.y=y;searchTarget.tile=tile;
            searchTarget.lastSeenGameTime=seen;searchTarget.status="ACTIVE";
            searchBest=Double.MAX_VALUE;searchNeedsOrder=true;searchTargetAt=time;searchProgressAt=time;
            if(moved)event("search_target_refreshed","{\"targetId\":"+searchTarget.targetId+",\"sourceEnemyId\":"
                +enemyId+",\"tile\":"+tile+",\"lastSeenGameTime\":"+seen+"}");
            return;
        }
        if(searchTarget!=null){
            searchTarget.status="SUPERSEDED";
            event("search_target_superseded","{\"targetId\":"+searchTarget.targetId+",\"sourceEnemyId\":"+searchTarget.sourceEnemyId
                +",\"reason\":\"NEW_OBSERVATION\"}");
        }
        searchTarget=new SearchTarget(++searchTargetCounter,enemyId,stringOrNull(best,"type"),x,y,seen,time,tile);
        searchBest=Double.MAX_VALUE;searchNeedsOrder=true;searchTargetAt=time;searchProgressAt=time;targetReady=false;
        event("search_target_created","{\"targetId\":"+searchTarget.targetId+",\"sourceEnemyId\":"+enemyId
            +",\"sourceType\":"+Json.quote(searchTarget.sourceType==null?"unknown":searchTarget.sourceType)
            +",\"tile\":"+tile+",\"x\":"+x+",\"y\":"+y+",\"lastSeenGameTime\":"+seen+"}");
    }

    private static long tileFor(double x,double y,long tilesWide,long tilesHigh,long tileWidth,long tileHeight){
        long col=(long)Math.floor(x/tileWidth),row=(long)Math.floor(y/tileHeight);
        if(col<0)col=0;if(row<0)row=0;
        if(col>tilesWide-1)col=tilesWide-1;if(row>tilesHigh-1)row=tilesHigh-1;
        return col*tilesHigh+row;
    }

    /** Arrival means the objective tile is legally visible again, never mere proximity. */
    private boolean confirmSearchTarget()throws Exception{
        SearchTarget t=searchTarget;
        Map<String,Object> probe=optionalGet("/scout/visible?tiles="+t.tile,"scout_visible_probe");
        if(probe==null)return false;
        boolean visible=false;
        for(Map<String,Object> tile:list(probe,"tiles"))
            if(((Number)tile.get("tile")).longValue()==t.tile&&Boolean.TRUE.equals(tile.get("visible")))visible=true;
        if(!visible)return false;
        t.status="CONFIRMED_EMPTY";
        consumedObservations.put(Long.valueOf(t.sourceEnemyId),Long.valueOf(t.lastSeenGameTime));
        event("search_target_confirmed_empty","{\"targetId\":"+t.targetId+",\"sourceEnemyId\":"+t.sourceEnemyId
            +",\"tile\":"+t.tile+",\"observationGameTime\":"+t.lastSeenGameTime+",\"attempts\":"+t.attemptCount+"}");
        searchTarget=null;searchBest=Double.MAX_VALUE;searchNeedsOrder=true;
        return true;
    }

    private boolean marchToSearchTarget(List<Map<String,Object>> force)throws Exception{
        SearchTarget t=searchTarget;
        double cx=0,cy=0;for(Map<String,Object> u:force){cx+=n(u,"x");cy+=n(u,"y");}cx/=force.size();cy/=force.size();
        double d=Math.hypot(cx-t.x,cy-t.y);
        if(searchBest==Double.MAX_VALUE||d+12<searchBest){searchBest=d;searchProgressAt=time;}
        if(time-searchProgressAt>20000||time-searchTargetAt>75000){
            t.status="UNREACHABLE";t.attemptCount++;
            unreachableObservations.put(Long.valueOf(t.sourceEnemyId),Long.valueOf(t.lastSeenGameTime));
            event("search_target_unreachable","{\"targetId\":"+t.targetId+",\"sourceEnemyId\":"+t.sourceEnemyId
                +",\"distance\":"+Math.round(d)+",\"attempts\":"+t.attemptCount+"}");
            searchTarget=null;searchBest=Double.MAX_VALUE;searchNeedsOrder=true;
            return false;
        }
        if(searchNeedsOrder||time-lastTactic>=8000){
            if(attack(force,t.x,t.y,"LAST_SEEN",null)){searchNeedsOrder=false;searchTargetAt=time;return true;}
        }
        return true;
    }

    /**
     * P2-A/P2-B: keep the economy growing after the opening, then add production capacity once the
     * planned mines exist and the single factory can no longer absorb the income. Every decision is
     * logged so a match can answer why a build was attempted, which site was chosen, whether building
     * started and why it failed. One variable at a time: mines first, factories second.
     */
    private void economyLane(Map<String,Object> state)throws Exception{
        reportProductionSurplus(state);
        pruneBlockedSites(state);
        maintainInvestment(state);
        if(buildJob!=null){advanceBuildJob(state);return;}
        long mines=countReadyExtractors(state);
        if(mines<mineTarget){planResourcePoint(state,mines,false);return;}
        if(factoryTargetCommitted){planProductionFacility(state,mines);return;}
        if(!expansionTargetLogged){
            expansionTargetLogged=true;
            Map<String,Object> data=new LinkedHashMap<String,Object>();
            data.put("reason","MINE_FLOOR_REACHED");data.put("mines",mines);data.put("mineTarget",mineTarget);
            data.put("note","floor reached; above-floor expansion continues while surplus and safe sites exist");
            event("economy_expansion_finished",json(data));
        }
        // Economy v1a: above the floor the lane now works through a chosen investment, which is what lets
        // the mine happen while the army is still below its cap instead of only after the cap saturated.
        considerInvestmentIntent(state);
        // Economy v0/v0.1: above the floor the lane keeps looking. A refusal here must never stall the
        // factory lane, so it only returns when an expansion job was actually created.
        if(planResourcePoint(state,mines,true))return;
        planProductionFacility(state,mines);
    }

    /**
     * Plans one resource point. Returns true when a build job was created, so the caller knows the
     * builder is now committed and the factory lane must wait.
     *
     * `beyondFloor` marks an Economy v0 expansion above `mineTarget`. Such a site has to pay for itself
     * out of real surplus, so two gates apply, both reusing state the client already has rather than
     * introducing a new constant:
     *   SITE_UNSAFE   a remembered threat sits next to the site;
     *   NO_SURPLUS    after paying for it we could no longer afford one preferred unit, i.e. the mine
     *                 would starve the production line it is meant to feed.
     * A third candidate gate ("would breach the builder reserve") was deliberately dropped: builderReserve
     * is only non-zero while the builder is missing, and in that case findBuilder() already refuses first,
     * so the gate would be unreachable code pretending to be a protection.
     * Below the floor none of this applies: the floor is not an investment, it is the baseline economy.
     */
    private boolean planResourcePoint(Map<String,Object> state,long mines,boolean beyondFloor)throws Exception{
        // Above the floor we must not touch the shared expansion clock: the factory lane is gated on it.
        if(beyondFloor){
            if(time-lastAboveFloorProbeAt<expansionIntervalGameMs)return false;
            lastAboveFloorProbeAt=time;
        }else{
            if(time-lastExpansionAttempt<expansionIntervalGameMs)return false;
            lastExpansionAttempt=time;
        }
        Map<String,Object> builder=findBuilder(state);
        if(builder==null){reportExpansionBlocked("NO_BUILDER",mines,beyondFloor);return false;}
        long builderId=id(builder);
        Map<String,Object> plan=optionalGet("/expansion/plan?unitId="+builderId,"economy_expansion_plan");
        if(plan==null||!"planned".equals(plan.get("status"))){
            if(!beyondFloor){
                reportExpansionBlocked("NO_VISIBLE_LEGAL_SITE",mines,false);
                prospectForResource(state,builder,false);
                return false;
            }
            // Economy v0.1: /expansion/plan only searches +-600 world units around the builder
            // (EconomyBridge.openingPlan), so above the floor the only way a new mine becomes reachable
            // is to keep prospecting remembered resource points - exactly what the below-floor lane has
            // always done. Live run 对话33 showed why this matters: 45 and 49 above-floor probes were
            // refused with visibleResourceCandidates=1 because the lone resource inside the window was
            // the tile our own mine already sits on, while 5-9 free scouted points sat 1122-1902 units
            // away. Prospecting costs the only builder a long march, so it is gated first.
            String gate=aboveFloorProspectGate(state);
            if(gate!=null){reportExpansionBlocked(gate,mines,true);return false;}
            reportExpansionBlocked("NO_VISIBLE_LEGAL_SITE",mines,true);
            prospectForResource(state,builder,true);
            return false;
        }
        if(prospectTile>=0)prospectTile=-1;
        // 杈撳嚭10 搂8B: an abandoned unfinished structure is physically still there, so the planner must
        // not immediately pick the same tile again (that would loop probe -> abandon -> replan forever).
        if(blockedSites.containsKey(siteKey(n(plan,"extractorX"),n(plan,"extractorY")))){
            reportExpansionBlocked("UNUSABLE_UNFINISHED_SITE",mines,beyondFloor);return false;
        }
        // The expansion plan prices the factory alongside the resource point, so the second-factory
        // trigger can compare credits against a real number instead of a guessed constant.
        if(plan.get("factoryCost") instanceof Number)landFactoryCost=((Number)plan.get("factoryCost")).longValue();
        double x=n(plan,"extractorX"),y=n(plan,"extractorY");
        long cost=(long)n(plan,"extractorCost");
        lastExtractorCost=cost;
        // The intent may have been opened before any plan reported a price, in which case it reserved the
        // configured default. Correct it the moment the engine states the real price, so the production
        // lane is never throttled by more than the mine actually costs.
        if(investment!=null&&"NEW_MINE".equals(investment.target)&&investmentReserve!=cost&&!investment.costLocked){
            long previous=investmentReserve;
            investmentReserve=cost;investment=new Investment(investment.target,cost,investment.chosenAt,investment.deadline);
            Map<String,Object> corrected=new LinkedHashMap<String,Object>();
            corrected.put("previousReserve",previous);corrected.put("reserve",cost);
            corrected.put("source","ENGINE_EXTRACTOR_COST");corrected.put("gameTimeMs",time);
            event("investment_reserve_corrected",json(corrected));
        }
        if(beyondFloor){
            String refusal=null;
            if(nearRememberedThreat(x,y))refusal="SITE_UNSAFE";
            // "Factories before extra mines" has to be checked HERE as well as in the prospect gate: the
            // prospect gate only runs when no site is visible, so a visible site used to bypass it
            // entirely and take the builder the factory lane was about to need.
            else if(countReadyType(state,"landFactory")<landFactoryTarget)refusal="BUILDER_NEEDED_FOR_FACTORY";
            else if(investment==null&&wouldBreachCapabilityReserve(state,cost))refusal="CAPABILITY_PURCHASE_RESERVED";
            // Builder Utilization v0 (对话41): the intent already reserved exactly this price, so the mine
            // is affordable the moment that price is covered. Requiring a preferred unit on top as well
            // was the second half of the double threshold that kept the builder idle.
            else if(n(obj(state.get("player")),"credits")<cost)refusal="NO_SURPLUS";
            if(refusal!=null){reportExpansionBlocked(refusal,mines,true);return false;}
        }
        buildJob=new BuildJob(JOB_EXTRACTOR,x,y,cost,time,String.valueOf(plan.get("diagnostics")));
        snapshotKnownUnits(state,JOB_EXTRACTOR);
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("reason",beyondFloor?"ABOVE_MINE_FLOOR":"BELOW_MINE_TARGET");
        data.put("mines",mines);data.put("mineTarget",mineTarget);data.put("beyondFloor",Boolean.valueOf(beyondFloor));
        data.put("builderId",builderId);data.put("x",x);data.put("y",y);
        data.put("extractorCost",buildJob.cost);data.put("distance",Math.hypot(n(builder,"x")-x,n(builder,"y")-y));
        data.put("credits",n(obj(state.get("player")),"credits"));
        data.put("preferredUnitCost",lastPreferredUnitCost);
        data.put("diagnostics",plan.get("diagnostics"));
        event("economy_expansion_planned",json(data));
        if(beyondFloor)minesBeyondFloor++;
        return true;
    }

    /**
     * P2-B first version: build the second land factory in the rear. The trigger is economic instead of
     * a frozen time or credit threshold: the planned mines exist, the only factory is already busy, and
     * the facility plus one unit of current production is affordable without stopping that production.
     * The builder returns home before planning so the new factory is not placed on the front line.
     */
    private void planProductionFacility(Map<String,Object> state,long mines)throws Exception{
        long factories=countReadyType(state,"landFactory");
        if(factories>=landFactoryTarget){
            // The target is met, so no earlier capacity expansion is still outstanding.
            factoryTargetCommitted=false;
            if(!factoryTargetLogged){
                factoryTargetLogged=true;
                Map<String,Object> data=new LinkedHashMap<String,Object>();
                data.put("reason","FACTORY_TARGET_REACHED");data.put("factories",factories);
                data.put("landFactoryTarget",landFactoryTarget);
                event("production_facility_finished",json(data));
            }
            // Economy v1b (对话38 裁决 §2): reaching the target is no longer the end of this lane. A
            // long-term throughput bottleneck may justify exactly one more factory, so the same mechanism
            // serves the 3rd, 4th and 5th one. The gate logs why it did not fire, so a match that never
            // adds capacity is still explainable instead of merely quiet.
            String increase=considerFactoryTargetIncrease(state,factories);
            if(increase!=null){reportFactoryTargetIncreaseBlocked(state,increase,factories);return;}
            lastFactoryIncreaseGate=null;
        }
        if(time-lastExpansionAttempt<expansionIntervalGameMs)return;
        Map<String,Object> builder=findBuilder(state);
        if(builder==null){reportFactoryBlocked(state,"NO_BUILDER",mines,factories);return;}
        if(Math.hypot(n(builder,"x")-homeX,n(builder,"y")-homeY)>rearFactoryRadiusWorld){
            if(post("/command/move?unitId="+id(builder)+"&x="+homeX+"&y="+homeY)!=null){
                lastExpansionAttempt=time;
                Map<String,Object> data=new LinkedHashMap<String,Object>();
                data.put("reason","RETURNING_TO_REAR");data.put("builderId",id(builder));
                data.put("x",homeX);data.put("y",homeY);data.put("gameTimeMs",time);
                event("production_facility_move",json(data));
            }
            return;
        }
        // Economy v1b committed semantics (对话39 裁决 §2): a target that was raised by the long-window
        // bottleneck gate is a CAPACITY COMMITMENT. Re-deciding it from a single frame would let one
        // momentary `FACTORY_QUEUE_EMPTY` frame veto a decision the 150 game second window already made -
        // measured live as a 47.3 second stall between the increase and the build order (6 such frames).
        // The frozen P2-B path for the ORIGINAL second factory is untouched: it still needs a busy queue.
        if(factoryTargetCommitted){
            long committedPrice=landFactoryCost>0?landFactoryCost:landFactoryCostDefault;
            if(n(obj(state.get("player")),"credits")<committedPrice+builderReserve+investmentReserve){
                reportFactoryBlocked(state,"INSUFFICIENT_CREDITS_FOR_FACTORY",mines,factories);return;
            }
            if(wouldBreachCapabilityReserve(state,committedPrice)){
                reportFactoryBlocked(state,"CAPABILITY_PURCHASE_RESERVED",mines,factories);return;
            }
        }else{
            if(!lastFactoryQueueNonEmpty){reportFactoryBlocked(state,"FACTORY_QUEUE_EMPTY",mines,factories);return;}
            if(lastPreferredUnitCost<=0){reportFactoryBlocked(state,"UNIT_COST_UNKNOWN",mines,factories);return;}
            double credits=n(obj(state.get("player")),"credits");
            // Until a plan has reported the real factory price the native planner is the authority on
            // affordability (it refuses below factory+tank), so only the known-cost case is gated here.
            if(landFactoryCost>0&&credits<landFactoryCost+lastPreferredUnitCost){
                reportFactoryBlocked(state,"INSUFFICIENT_CREDITS_FOR_FACTORY",mines,factories);return;
            }
        }
        lastExpansionAttempt=time;
        Map<String,Object> plan=optionalGet("/economy/plan?unitId="+id(builder),"production_facility_plan");
        if(plan==null||!"planned".equals(plan.get("status"))){
            reportFactoryBlocked(state,"NO_VISIBLE_LEGAL_SITE",mines,factories);return;
        }
        double nativeCost=StrategyDirector.number(plan,"factoryCost",Double.NaN);
        if(!Double.isFinite(nativeCost)||nativeCost<=0){reportFactoryBlocked(state,"FACTORY_COST_UNKNOWN",mines,factories);return;}
        landFactoryCost=(long)nativeCost;
        if(blockedSites.containsKey(siteKey(n(plan,"targetX"),n(plan,"targetY")))){
            reportFactoryBlocked(state,"UNUSABLE_UNFINISHED_SITE",mines,factories);return;
        }
        double x=n(plan,"targetX"),y=n(plan,"targetY");
        double credits=n(obj(state.get("player")),"credits");
        if(wouldBreachCapabilityReserve(state,nativeCost)){
            reportFactoryBlocked(state,"CAPABILITY_PURCHASE_RESERVED",mines,factories);return;
        }
        if(credits-nativeCost<builderReserve+investmentReserve){
            reportFactoryBlocked(state,"RESERVE_PROTECTED",mines,factories);return;
        }
        buildJob=new BuildJob(JOB_LAND_FACTORY,x,y,(long)n(plan,"factoryCost"),time,String.valueOf(plan.get("diagnostics")));
        buildJob.capacityExpansion=factoryTargetCommitted;buildJob.commitmentId=factoryCommitmentId;
        snapshotKnownUnits(state,JOB_LAND_FACTORY);
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("reason","SURPLUS_WITH_BUSY_FACTORY");data.put("mines",mines);data.put("factories",factories);
        data.put("builderId",id(builder));data.put("x",x);data.put("y",y);
        data.put("factoryCost",buildJob.cost);data.put("preferredUnit",lastPreferredUnit);
        data.put("preferredUnitCost",lastPreferredUnitCost);data.put("credits",credits);
        data.put("distance",Math.hypot(n(builder,"x")-x,n(builder,"y")-y));
        data.put("factoryCommitmentId",buildJob.commitmentId);data.put("capacityExpansion",buildJob.capacityExpansion);
        data.put("builderReserve",builderReserve);data.put("investmentReserve",investmentReserve);
        data.put("capabilityReserve",strategy.capabilityReserve());data.put("gameTimeMs",time);
        event("production_facility_planned",json(data));
    }

    private void advanceBuildJob(Map<String,Object> state)throws Exception{
        BuildJob job=buildJob;
        // A site may still be under construction when the match ends. The analyzer treats a PASS report
        // with an unclosed extractor_started as incomplete evidence, so the paired started/completed
        // events are only written once the structure is actually finished; the real first-seen time is
        // preserved in the *_observed event and in the completed payload.
        String prefix=JOB_EXTRACTOR.equals(job.kind)?"economy_expansion":"production_facility";
        for(Map<String,Object> u:units(state)){
            if(!alive(u)||!jobMatches(job,(String)u.get("type")))continue;
            long uid=id(u);if(jobKnownUnits.contains(uid))continue;
            if(Math.hypot(n(u,"x")-job.x,n(u,"y")-job.y)>120)continue;
            jobKnownUnits.add(uid);job.candidateId=uid;job.started=true;job.firstSeenGameMs=time;
            Map<String,Object> data=new LinkedHashMap<String,Object>();
            data.put("unitId",uid);data.put("type",u.get("type"));
            data.put("x",n(u,"x"));data.put("y",n(u,"y"));
            data.put("plannedX",job.x);data.put("plannedY",job.y);data.put("gameTimeMs",time);
            data.put("factoryCommitmentId",job.commitmentId);
            event(prefix+"_observed",json(data));
        }
        if(job.candidateId>=0){
            Map<String,Object> built=find(state,job.candidateId);
            if(built==null||!alive(built)){
                event(prefix+"_lost","{\"unitId\":"+job.candidateId+",\"reason\":\"DESTROYED_BEFORE_COMPLETION\"}");
                buildJob=null;jobKnownUnits.clear();return;
            }
            if(n(built,"buildProgress")>=1.0){
                Map<String,Object> data=new LinkedHashMap<String,Object>();
                data.put("unitId",job.candidateId);data.put("type",built.get("type"));
                data.put("x",n(built,"x"));data.put("y",n(built,"y"));
                data.put("plannedAtGameMs",job.plannedAt);data.put("firstSeenGameMs",job.firstSeenGameMs);
                if(JOB_EXTRACTOR.equals(job.kind)){
                    minesCompleted++;data.put("minesCompleted",minesCompleted);
                    // Economy v1a: this is the acceptance moment - a mine finished while the army was
                    // still below its hard cap, paid for out of a reserve the production lane could not
                    // touch. Recorded before the reserve is released so the report shows both facts.
                    if(investment!=null&&"NEW_MINE".equals(investment.target)){
                        data.put("investmentReserve",investmentReserve);
                        data.put("armyAtCompletion",army(state).size());
                        data.put("hardCap",mobileUnitHardCap);
                        data.put("belowHardCap",army(state).size()<mobileUnitHardCap);
                    }
                    // 对话38 裁决 §5: firstSeenGameMs is when the SITE was first seen (the build START,
                    // ~16.7 game seconds before this moment). Every analyzer that mistook it for the
                    // completion time was off by the build duration, so state the real one explicitly.
                    data.put("completedAtGameMs",time);
                    event("extractor_started",json(data));
                    event("extractor_completed",json(data));
                    releaseInvestment("COMPLETED");
                }else{
                    landFactoriesCompleted++;data.put("factoriesCompleted",landFactoriesCompleted);
                    data.put("completedAtGameMs",time);
                    event("factory_started",json(data));
                    event("factory_completed",json(data));
                    if(job.capacityExpansion){
                        lastFactoryReady=time;lastReadyFactoryCommitmentId=job.commitmentId;factoryTargetCommitted=false;
                        data.put("factoryCommitmentId",job.commitmentId);data.put("landFactoryTarget",landFactoryTarget);
                        event("production_facility_ready",json(data));
                    }
                }
                buildJob=null;jobKnownUnits.clear();lastExpansionAttempt=time;
            }else{
                // The site exists but is not finished. Only a builder can advance it, so without one the
                // job is parked with its site and progress kept for a later builder instead of being
                // retried forever or silently forgotten. Each transition is reported exactly once.
                boolean withoutBuilder=findBuilder(state)==null;
                if(withoutBuilder&&!job.stalled){
                    job.stalled=true;job.wasStalled=true;
                    Map<String,Object> data=new LinkedHashMap<String,Object>();
                    data.put("state","STALLED_NO_BUILDER");
                    data.put("unitId",job.candidateId);data.put("type",built.get("type"));data.put("kind",job.kind);
                    data.put("x",job.x);data.put("y",job.y);data.put("buildProgress",n(built,"buildProgress"));
                    data.put("reason","BUILDER_LOST_WITH_UNFINISHED_SITE");
                    data.put("gameTimeMs",time);
                    event(prefix+"_state",json(data));
                }
                if(withoutBuilder)return;
                // A normal build in progress is not a recovery case: only a job that really lost its
                // builder is probed, exactly once (杈撳嚭10 搂2 condition 6).
                if(!job.wasStalled)return;
                // A builder is available again. 杈撳嚭10 搂3: the job is NOT active again until the same
                // candidate really gained progress, so the probe decides, not the state field.
                recoveryProbe(state,job,built,prefix);
            }
            return;
        }
        // An accepted expansion order is paid-but-not-observed. Do not submit it or book its cost twice.
        if(job.capacityExpansion&&job.orderAccepted)return;
        Map<String,Object> builder=findBuilder(state);
        if(builder==null){
            // Nothing was built yet, so there is no half-finished site to keep: the plan is abandoned and
            // the lane may plan again later (once a builder exists it will be reported as NO_BUILDER).
            Map<String,Object> data=new LinkedHashMap<String,Object>();
            data.put("state","ABANDONED_NO_BUILDER");data.put("kind",job.kind);
            data.put("x",job.x);data.put("y",job.y);data.put("reason","BUILDER_LOST_BEFORE_CONSTRUCTION");
            data.put("gameTimeMs",time);
            event(prefix+"_state",json(data));
            if(JOB_EXTRACTOR.equals(job.kind))reportExpansionBlocked("BUILDER_LOST_WITH_PENDING_SITE",countReadyExtractors(state));
            else reportFactoryBlocked(state,"BUILDER_LOST_WITH_PENDING_SITE",countReadyExtractors(state),countReadyType(state,"landFactory"));
            buildJob=null;jobKnownUnits.clear();lastExpansionAttempt=time;
            return;
        }
        double distance=Math.hypot(n(builder,"x")-job.x,n(builder,"y")-job.y);
        if(distance>expansionBuildRangeWorld){
            if(post("/command/move?unitId="+id(builder)+"&x="+job.x+"&y="+job.y)!=null){
                job.moving=true;
                Map<String,Object> data=new LinkedHashMap<String,Object>();
                data.put("builderId",id(builder));data.put("x",job.x);data.put("y",job.y);
                data.put("distance",distance);data.put("gameTimeMs",time);
                event(prefix+"_move",json(data));
            }
            return;
        }
        double credits=n(obj(state.get("player")),"credits");
        if(JOB_LAND_FACTORY.equals(job.kind)){
            String hold=null;
            double cost=job.cost;
            if(job.capacityExpansion&&strategy.enabled()){
                if(committedProductionArmed(state)>=mobileUnitHardCap)hold="HARD_SAFETY_CAP";
                else if(!"KNOWN".equals(capacityEvidence.get("demand")))hold="CURRENT_LEGAL_DEMAND_UNPROVEN";
                else if(Boolean.TRUE.equals(capacityEvidence.get("recovery")))hold="RECOVERY_PROTECTED";
                else{
                    Map<String,Object> quote=nativeFactoryQuote(builder);
                    if(quote==null)hold="NATIVE_FACTORY_ACTION_UNKNOWN";
                    else if(!Boolean.TRUE.equals(quote.get("affordable")))hold="NATIVE_FACTORY_ACTION_UNAFFORDABLE";
                    else cost=StrategyDirector.number(quote,"cost",Double.NaN);
                }
            }
            if(hold==null&&(!Double.isFinite(cost)||cost<=0||cost!=job.cost))hold="NATIVE_FACTORY_PRICE_CHANGED";
            if(hold==null&&credits-cost<builderReserve+investmentReserve+strategy.capabilityReserve())hold="RESERVE_PROTECTED";
            if(hold!=null){
                if(!hold.equals(job.lastHold)){
                    job.lastHold=hold;
                    event(prefix+"_deferred",json(StrategyDirector.map("reason",hold,"credits",credits,"cost",cost,
                        "builderReserve",builderReserve,"investmentReserve",investmentReserve,"capabilityReserve",strategy.capabilityReserve(),
                        "factoryCommitmentId",job.commitmentId,"gameTimeMs",time)));
                }
                return;
            }
            job.lastHold=null;
        }
        if(credits<job.cost){
            if(!job.reserveLogged){
                job.reserveLogged=true;
                Map<String,Object> data=new LinkedHashMap<String,Object>();
                data.put("reason","INSUFFICIENT_CREDITS");data.put("credits",credits);
                data.put("cost",job.cost);data.put("kind",job.kind);
                event(prefix+"_deferred",json(data));
            }
            return;
        }
        String path=JOB_EXTRACTOR.equals(job.kind)?"/command/build-extractor":"/command/build-factory";
        Map<String,Object> receipt=post(path+"?unitId="+id(builder)+"&x="+job.x+"&y="+job.y);
        if(receipt!=null){
            job.orderAccepted=true;
            spend(JOB_EXTRACTOR.equals(job.kind)?SPEND_MINE:SPEND_FACTORY,job.cost,job.kind,id(builder));
            Map<String,Object> data=new LinkedHashMap<String,Object>();
            data.put("builderId",id(builder));data.put("x",job.x);data.put("y",job.y);
            data.put("cost",job.cost);data.put("kind",job.kind);data.put("gameTimeMs",time);
            event(prefix+"_started",json(data));
            if(job.capacityExpansion){
                data.put("factoryCommitmentId",job.commitmentId);data.put("receipt",receipt);
                data.put("builderReserve",builderReserve);data.put("investmentReserve",investmentReserve);
                data.put("capabilityReserve",strategy.capabilityReserve());
                event("production_facility_order_accepted",json(data));
            }
        }else if(execution.ready(time)){
            // The write was allowed by the rate limit yet not accepted: record why for the report.
            Map<String,Object> data=new LinkedHashMap<String,Object>();
            data.put("reason","BUILD_COMMAND_REJECTED");data.put("builderId",id(builder));
            data.put("x",job.x);data.put("y",job.y);data.put("credits",credits);data.put("gameTimeMs",time);
            event(prefix+"_retry",json(data));
        }
    }

    private static boolean jobMatches(BuildJob job,String type){
        if(type==null)return false;
        return JOB_EXTRACTOR.equals(job.kind)?isExtractor(type):"landFactory".equals(type);
    }
    /** Read-only legal native action quote; no frozen/community factory price authorises expansion. */
    private Map<String,Object> nativeFactoryQuote(Map<String,Object> builder)throws Exception{
        if(builder==null)return null;
        Map<String,Object> menu=readStrategy("/economy/builder-actions?unitId="+id(builder),"production_facility_native_quote");
        for(Map<String,Object> action:StrategyDirector.items(menu,"actions"))
            if("landFactory".equals(action.get("type"))&&Boolean.TRUE.equals(action.get("available"))
                    &&Boolean.TRUE.equals(action.get("buildAction"))&&StrategyDirector.number(action,"cost",0)>0)return action;
        return null;
    }

    /**
     * 杈撳嚭10 搂1-搂8: exactly one recovery probe per stalled job, at the same site, with a real result
     * requirement. An accepted command is NOT recovery - only a strictly larger buildProgress on the
     * same unfinished candidate proves the original structure is being continued. A rejected probe first
     * asks the builder for its native action inventory, and only when no resume path exists is the site
     * abandoned explicitly (and remembered as physically blocked so the planner cannot pick it again).
     */
    private void recoveryProbe(Map<String,Object> state,BuildJob job,Map<String,Object> built,String prefix)throws Exception{
        Map<String,Object> builder=findBuilder(state);
        double progress=n(built,"buildProgress");
        double credits=n(obj(state.get("player")),"credits");
        if(!job.probeAttempted){
            job.probeAttempted=true;job.probeState="RECOVERY_PROBE";job.probeProgress=progress;job.probeAt=time;
            job.probeCredits=credits;
            Map<String,Object> data=new LinkedHashMap<String,Object>();
            data.put("state","RECOVERY_PROBE");data.put("unitId",job.candidateId);data.put("type",built.get("type"));
            data.put("kind",job.kind);data.put("x",job.x);data.put("y",job.y);data.put("buildProgress",progress);
            data.put("creditsBeforeProbe",credits);data.put("buildCost",job.cost);
            data.put("builderId",id(builder));data.put("gameTimeMs",time);
            event(prefix+"_recovery_probe",json(data));
            String path=JOB_EXTRACTOR.equals(job.kind)?"/command/build-extractor":"/command/build-factory";
            Map<String,Object> receipt=post(path+"?unitId="+id(builder)+"&x="+job.x+"&y="+job.y);
            Map<String,Object> result=new LinkedHashMap<String,Object>();
            result.put("unitId",job.candidateId);result.put("kind",job.kind);result.put("x",job.x);result.put("y",job.y);
            result.put("buildProgress",progress);result.put("builderId",id(builder));
            result.put("creditsBeforeProbe",credits);result.put("buildCost",job.cost);result.put("gameTimeMs",time);
            if(receipt!=null){
                job.probeState="ACCEPTED";job.probeAt=time;
                result.put("state","RECOVERY_PROBE_ACCEPTED");
                event(prefix+"_recovery_probe_accepted",json(result));
            }else{
                job.probeState="REJECTED";
                result.put("state","RECOVERY_PROBE_REJECTED");result.put("reason","BUILD_COMMAND_REJECTED");
                event(prefix+"_recovery_probe_rejected",json(result));
                reportBuilderActions(state,builder);
            }
            return;
        }
        if("ACCEPTED".equals(job.probeState)){
            // A second structure appearing next to the saved one means the command built something new
            // instead of continuing the original site, which is a failed probe, not a recovery.
            boolean secondStructure=countSameTypeNear(state,job,120)>1;
            // 杈撳嚭11 搂2/搂3: continuing a half-built site must not charge the building price a second
            // time. A full second charge inside the probe window is not an acceptable recovery.
            boolean chargedAgain=job.probeCredits-credits>=job.cost*0.9;
            Map<String,Object> data=new LinkedHashMap<String,Object>();
            data.put("unitId",job.candidateId);data.put("kind",job.kind);data.put("x",job.x);data.put("y",job.y);
            data.put("buildProgressBefore",job.probeProgress);data.put("buildProgressAfter",progress);
            data.put("creditsBeforeProbe",job.probeCredits);data.put("creditsAfterProbe",credits);
            data.put("probeCostDelta",job.probeCredits-credits);data.put("buildCost",job.cost);
            data.put("gameTimeMs",time);
            if(progress>job.probeProgress&&!secondStructure&&!chargedAgain){
                job.probeState="RESUMED";job.stalled=false;
                data.put("state","ACTIVE");data.put("resumedFrom",job.probeProgress);
                event(prefix+"_recovery_resumed",json(data));
            }else if(secondStructure||chargedAgain||time-job.probeAt>60000){
                job.probeState="REJECTED";
                data.put("state","RECOVERY_PROBE_REJECTED");
                data.put("reason",chargedAgain?"DOUBLE_CHARGED_BUILD_COST"
                    :secondStructure?"CREATED_SECOND_STRUCTURE":"NO_PROGRESS_AFTER_ACCEPT");
                event(prefix+"_recovery_probe_rejected",json(data));
                reportBuilderActions(state,builder);
            }
            return;
        }
        if("REJECTED".equals(job.probeState)){
            blockedSites.put(siteKey(job.x,job.y),Long.valueOf(job.candidateId));
            Map<String,Object> data=new LinkedHashMap<String,Object>();
            data.put("state","ABANDONED_UNRECOVERABLE");data.put("unitId",job.candidateId);data.put("kind",job.kind);
            data.put("x",job.x);data.put("y",job.y);data.put("buildProgress",progress);
            data.put("blockedSite",siteKey(job.x,job.y));data.put("gameTimeMs",time);
            event(prefix+"_abandoned_unrecoverable",json(data));
            buildJob=null;jobKnownUnits.clear();lastExpansionAttempt=time;
        }
    }

    private static String siteKey(double x,double y){return Math.round(x)+","+Math.round(y);}

    /** Same-kind own units near a site; more than the saved candidate means a second structure appeared. */
    private long countSameTypeNear(Map<String,Object> state,BuildJob job,double range){
        long count=0;
        for(Map<String,Object> u:units(state)){
            if(!alive(u)||!jobMatches(job,(String)u.get("type")))continue;
            if(Math.hypot(n(u,"x")-job.x,n(u,"y")-job.y)<=range)count++;
        }
        return count;
    }

    /** Read-only: which native actions does this builder actually have for continuing construction? */
    private void reportBuilderActions(Map<String,Object> state,Map<String,Object> builder)throws Exception{
        Map<String,Object> actions=optionalGet("/economy/builder-actions?unitId="+id(builder),"builder_actions");
        if(actions==null)return;
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("builderId",id(builder));data.put("unitType",actions.get("unitType"));
        data.put("actionCount",actions.get("actionCount"));data.put("hasRecoveryPath",actions.get("hasRecoveryPath"));
        data.put("recoveryPath",actions.get("recoveryPath"));
        data.put("actions",actions.get("actions"));data.put("gameTimeMs",time);
        event("builder_recovery_actions",json(data));
    }

    /** A physically blocked half-built site is never planned onto again until it is gone or finished. */
    private void pruneBlockedSites(Map<String,Object> state){
        Iterator<Map.Entry<String,Long>> it=blockedSites.entrySet().iterator();
        while(it.hasNext()){
            Map.Entry<String,Long> entry=it.next();Map<String,Object> u=find(state,entry.getValue().longValue());
            // 杈撳嚭11 搂6: the mark describes debris in the current world, so it is released as soon as the
            // site no longer holds an unfinished structure - gone, destroyed, or (rarely) completed.
            if(u==null||!alive(u)||n(u,"buildProgress")>=1.0)it.remove();
        }
    }

    private void snapshotKnownUnits(Map<String,Object> state,String kind){
        jobKnownUnits.clear();
        for(Map<String,Object> u:units(state)){
            if(!alive(u))continue;
            String type=(String)u.get("type");
            if(JOB_EXTRACTOR.equals(kind)?isExtractor(type):"landFactory".equals(type))jobKnownUnits.add(id(u));
        }
    }

    /**
     * Walks the builder towards a resource tile this agent has legally observed before. Only tiles the
     * scout actually saw are used, and the site is still validated natively on arrival, so this never
     * reads hidden terrain. Tiles that turn out to be unusable are remembered and not retried.
     */
    /**
     * Why an above-floor prospect may or may not start. Returns null when it may.
     *
     * The second factory and the extra mine compete for the only builder, and planProductionFacility
     * recalls a builder that is further than rearFactoryRadiusWorld from home, so prospecting while the
     * factory is still missing would make the two lanes trade move orders forever. Both prices come from
     * the engine (the plan's extractorCost and the production lane's preferred unit), never from a
     * table: 星星版铁锈机制库 marks every economy payback figure as unverified.
     */
    private String aboveFloorProspectGate(Map<String,Object> state){
        // Economy v1a: above the floor an expansion is an INVESTMENT, so it needs a chosen intent. The
        // intent is what holds the money, and it is why the mine may happen below the army cap.
        if(investment==null)return "NO_INVESTMENT_INTENT";
        if(countReadyType(state,"landFactory")<landFactoryTarget)return "BUILDER_NEEDED_FOR_FACTORY";
        // Builder Utilization v0 (对话41): with an intent held the reserve IS the mine's price, so the
        // only question left is whether that price is covered. The old extra requirement of "and one more
        // preferred unit on top" (measured 32-108 idle seconds per match) turned the soft production
        // reserve back into an absolute gate, which contradicts what the intent was opened to do.
        // The hard reserves (builder recovery) are untouched and still checked in the production lane.
        long price=lastExtractorCost>0?lastExtractorCost:investmentMineCostDefault;
        if(n(obj(state.get("player")),"credits")<price)return "NO_SURPLUS";
        return null;
    }
    private void prospectForResource(Map<String,Object> state,Map<String,Object> builder)throws Exception{
        prospectForResource(state,builder,false);
    }
    private void prospectForResource(Map<String,Object> state,Map<String,Object> builder,boolean beyondFloor)throws Exception{
        double bx=n(builder,"x"),by=n(builder,"y");
        if(prospectTile>=0&&Math.hypot(bx-prospectX,by-prospectY)<=80){
            triedProspects.add(Long.valueOf(prospectTile));
            Map<String,Object> data=new LinkedHashMap<String,Object>();
            data.put("tile",prospectTile);data.put("x",prospectX);data.put("y",prospectY);
            data.put("reason","NO_LEGAL_SITE_AT_REMEMBERED_RESOURCE");data.put("tried",triedProspects.size());
            event("economy_expansion_prospect_failed",json(data));
            prospectTile=-1;
        }
        if(prospectTile<0){
            List<Map<String,Object>> remembered=new ArrayList<Map<String,Object>>();
            if(lastScout!=null&&lastScout.get("resources") instanceof List<?>)remembered=list(lastScout,"resources");
            double bestDistance=Double.MAX_VALUE;
            for(Map<String,Object> res:remembered){
                if(!(res.get("tile") instanceof Number))continue;
                long tile=((Number)res.get("tile")).longValue();
                if(triedProspects.contains(Long.valueOf(tile)))continue;
                double x=n(res,"x"),y=n(res,"y");
                if(occupiedByOwnExtractor(state,x,y))continue;
                if(nearRememberedThreat(x,y))continue;
                double d=Math.hypot(x-bx,y-by);
                if(d>expansionProspectRangeWorld)continue;
                if(d<bestDistance){bestDistance=d;prospectTile=tile;prospectX=x;prospectY=y;}
            }
            if(prospectTile<0){reportExpansionBlocked("NO_REMEMBERED_RESOURCE",countExtractors(state),beyondFloor);return;}
            Map<String,Object> data=new LinkedHashMap<String,Object>();
            data.put("reason","PLANNER_ONLY_SEES_CURRENT_VISIBILITY");data.put("tile",prospectTile);
            data.put("x",prospectX);data.put("y",prospectY);data.put("distance",bestDistance);
            data.put("beyondFloor",Boolean.valueOf(beyondFloor));
            data.put("builderId",id(builder));data.put("tried",triedProspects.size());
            event("economy_expansion_prospect",json(data));
            lastProspectCommand=-100000;
        }
        if(time-lastProspectCommand<8000)return;
        if(post("/command/move?unitId="+id(builder)+"&x="+prospectX+"&y="+prospectY)!=null)lastProspectCommand=time;
    }

    /** A remembered resource inside a legally remembered threat area is not worth the only builder. */
    private boolean nearRememberedThreat(double x,double y){
        if(lastScout==null||!(lastScout.get("rememberedThreats") instanceof List<?>))return false;
        for(Map<String,Object> threat:list(lastScout,"rememberedThreats")){
            if(!(threat.get("x") instanceof Number)||!(threat.get("y") instanceof Number))continue;
            if(Math.hypot(n(threat,"x")-x,n(threat,"y")-y)<300)return true;
        }
        return false;
    }

    private boolean occupiedByOwnExtractor(Map<String,Object> state,double x,double y){
        for(Map<String,Object> u:units(state))
            if(alive(u)&&isExtractor((String)u.get("type"))&&Math.hypot(n(u,"x")-x,n(u,"y")-y)<60)return true;
        return false;
    }

    /**
     * Keeps a planned build affordable without stopping production altogether. Builder continuity has
     * the highest priority: while a replacement is needed its price stays out of reach of combat
     * production, otherwise the factories would keep the pool below the builder price forever.
     */
    private boolean wouldBreachMineReserve(Map<String,Object> state,double cost){
        double reserved=builderReserve;
        if(buildJob!=null&&!buildJob.started)reserved+=buildJob.cost;
        return reserved>0&&n(obj(state.get("player")),"credits")-cost<reserved;
    }

    /**
     * P2-C1: builder recovery. Without a builder nothing can be built, expanded or rebuilt, so this
     * runs before every other decision, reserves the native builder price, and orders a replacement as
     * soon as the producer is free and the money is there (杈撳嚭9 搂4-搂7). The producer is never preempted.
     */
    private void builderRecovery(Map<String,Object> state)throws Exception{
        if(builderTarget<=0)return;
        long alive=countReadyType(state,"builder");
        if(alive>=builderTarget){
            if(builderRecoveryActive||builderOrderPending){
                builderRecoveryActive=false;builderOrderPending=false;builderReserve=0;
                Map<String,Object> data=new LinkedHashMap<String,Object>();
                data.put("aliveBuilders",alive);data.put("builderTarget",builderTarget);
                data.put("builderCost",builderCost);data.put("gameTimeMs",time);
                event("builder_recovery_completed",json(data));
                builderCost=-1;
            }
            return;
        }
        if(!builderRecoveryActive){
            builderRecoveryActive=true;builderRecoveries++;
            Map<String,Object> data=new LinkedHashMap<String,Object>();
            data.put("aliveBuilders",alive);data.put("builderTarget",builderTarget);data.put("gameTimeMs",time);
            event("builder_recovery_started",json(data));
        }
        if(builderOrderPending)return;
        Map<String,Object> plan=optionalGet("/economy/builder-production","builder_production");
        double credits=n(obj(state.get("player")),"credits");
        if(plan==null||!"planned".equals(plan.get("status"))){
            reportBuilderWaiting("ACTION_UNAVAILABLE",credits,-1,alive);return;
        }
        builderProducerId=(long)n(plan,"producerId");
        builderCost=(long)n(plan,"builderCost");
        builderReserve=Math.max(0,builderCost);
        long queueCount=(long)n(plan,"queueCount");
        if(queueCount>0){reportBuilderWaiting("PRODUCER_BUSY",credits,queueCount,alive);return;}
        if(credits<builderReserve){reportBuilderWaiting("INSUFFICIENT_CREDITS",credits,queueCount,alive);return;}
        Map<String,Object> receipt=post("/command/produce-builder?unitId="+builderProducerId);
        if(receipt!=null){
            builderOrderPending=true;builderOrders++;
            spend(SPEND_BUILDER,builderCost,"builder",builderProducerId);
            Map<String,Object> data=new LinkedHashMap<String,Object>();
            data.put("producerId",builderProducerId);data.put("builderCost",builderCost);
            data.put("credits",credits);data.put("queueCount",queueCount);data.put("gameTimeMs",time);
            event("builder_recovery_ordered",json(data));
        }
    }

    /** Throttled: the recovery state may last minutes while the reserve stays held. */
    private void reportBuilderWaiting(String reason,double credits,long queueCount,long alive)throws Exception{
        if(reason.equals(lastDeferredReason==null?"":lastDeferredReason)&&time-lastBuilderRecoveryLog<30000)return;
        if(time-lastBuilderRecoveryLog<10000)return;
        lastBuilderRecoveryLog=time;
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("reason",reason);data.put("builderCost",builderCost);data.put("credits",credits);
        data.put("queueCount",queueCount);data.put("aliveBuilders",alive);data.put("gameTimeMs",time);
        event("builder_recovery_waiting",json(data));
    }

    /**
     * 杈撳嚭16 搂2B / 杈撳嚭18 搂D: the single owner of a secondary investment decision. Two blocks in the
     * factory loop can reach the same decision (the opportunistic-investment block and the army-cap
     * banking block), so both call this method instead of writing the events themselves.
     *
     * The deferral is a state event and keeps the 10 game-second window. The fallback is a behaviour
     * event: this method only arms {@code pendingFallback}, and the producer emits it after the bridge
     * accepted the order, so "successful fallback queue orders == fallback events" holds exactly.
     *
     * @param bankedReserve the reserve figure recorded on the deferral (the banked amount, not always the
     *                      primary reserve), while the emitted fallback always quotes the primary reserve.
     */
    private void reportSecondaryInvestment(Map<String,Object> factory,Map<String,Object> upgrade,
                                           Map<String,Object> unit,Map<String,Object> state,
                                           long bankedReserve,long primaryReserve)throws Exception{
        if(upgrade==null)return;
        if(mayReportSecondaryInvestment(factory))
            reportProductionDeferred(factory,upgrade,state,"SECONDARY_INVESTMENT",bankedReserve);
        pendingFallback=upgrade;pendingFallbackReserve=primaryReserve;
    }

    /**
     * 杈撳嚭16 搂2B: the same window {@code reportProductionDeferred} uses, asked before it writes. When it
     * answers true the caller owns this decision and may also write the paired fallback event exactly once;
     * a repeated decision tick inside the window reports neither.
     */
    private boolean mayReportSecondaryInvestment(Map<String,Object> factory){
        long factoryId=id(factory);
        return !(factoryId==lastDeferredFactory&&"SECONDARY_INVESTMENT".equals(lastDeferredReason)
                 &&time-lastDeferredLog<10000);
    }

    /**
     * 杈撳嚭15 搂6: what the secondary bought after its upgrade was deferred, and why that was legal.
     *
     * 杈撳嚭16 搂2B: exactly one event per real decision. The caller only reaches this method when
     * {@link #mayReportSecondaryInvestment} said the deferral is fresh, so this never writes a duplicate
     * for a decision that was already reported.
     */
    private void reportSecondaryFallback(Map<String,Object> factory,Map<String,Object> upgrade,Map<String,Object> unit,
                                         Map<String,Object> state,long reserve)throws Exception{
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("factoryId",Long.valueOf(id(factory)));
        data.put("factoryTier",Long.valueOf((long)n(factory,"tier")));
        data.put("credits",Double.valueOf(n(obj(state.get("player")),"credits")));
        data.put("deferredUpgradeCost",upgrade==null||!(upgrade.get("cost") instanceof Number)?null
                                       :Long.valueOf(((Number)upgrade.get("cost")).longValue()));
        data.put("chosenUnit",unit.get("type"));data.put("chosenCost",Long.valueOf((long)n(unit,"cost")));
        data.put("primaryReserve",Long.valueOf(reserve));
        data.put("reason","SECONDARY_INVESTMENT_FALLBACK");
        data.put("gameTimeMs",Long.valueOf(time));
        event("SECONDARY_INVESTMENT_FALLBACK",json(data));
    }

    /** 杈撳嚭13 搂9: the four money situations must be distinguishable in the report. */
    private static String deferredReason(String reservedFor){
        if("BUILDER_RECOVERY".equals(reservedFor))return "RESERVED_FOR_BUILDER_RECOVERY";
        if("PRIMARY_PRODUCTION".equals(reservedFor))return "RESERVED_FOR_PRIMARY_PRODUCTION";
        if("PRIMARY_TECH".equals(reservedFor))return "PRIMARY_TECH_BANKING";
        if("SECONDARY_INVESTMENT".equals(reservedFor))return "SECONDARY_INVESTMENT_DEFERRED";
        if("INVESTMENT".equals(reservedFor))return "RESERVED_FOR_INVESTMENT";
        return "RESERVED_FOR_"+reservedFor;
    }

    /** 杈撳嚭9 搂8: an idle factory that could buy something but deliberately does not, because of a reserve. */
    private void reportProductionDeferred(Map<String,Object> factory,Map<String,Object> candidate,Map<String,Object> state,String reservedFor,double reservedCost)throws Exception{
        // A candidate without a numeric cost is "no candidate": this is diagnostic book-keeping and may
        // never end the match (对话39: an empty candidate map NPE'd three live matches).
        if(candidate!=null&&!(candidate.get("cost") instanceof Number))candidate=null;
        long factoryId=id(factory);
        if(factoryId==lastDeferredFactory&&reservedFor.equals(lastDeferredReason)&&time-lastDeferredLog<10000)return;
        lastDeferredFactory=factoryId;lastDeferredReason=reservedFor;lastDeferredLog=time;
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("factoryId",factoryId);
        data.put("factoryTier",Long.valueOf((long)n(factory,"tier")));
        data.put("credits",Double.valueOf(n(obj(state.get("player")),"credits")));
        data.put("candidateUnit",candidate==null?null:candidate.get("type"));
        data.put("candidateCost",candidate==null?null:Long.valueOf((long)n(candidate,"cost")));
        data.put("reservedFor",reservedFor);data.put("reservedCost",Long.valueOf((long)reservedCost));
        data.put("reason",deferredReason(reservedFor));
        data.put("gameTimeMs",Long.valueOf(time));
        event("production_deferred",json(data));
    }

    private Map<String,Object> findBuilder(Map<String,Object> state){
        for(Map<String,Object> u:units(state))if(alive(u)&&"builder".equals(u.get("type"))&&!execution.reserved(id(u)))return u;
        return null;
    }

    private long countType(Map<String,Object> state,String type){
        long count=0;for(Map<String,Object> u:units(state))if(alive(u)&&type.equals(u.get("type")))count++;return count;
    }

    private void reportExpansionBlocked(String reason,long mines)throws Exception{
        reportExpansionBlocked(reason,mines,false);
    }
    /** `beyondFloor` separates "below the baseline" refusals from Economy v0 above-floor refusals. */
    private void reportExpansionBlocked(String reason,long mines,boolean beyondFloor)throws Exception{
        expansionBlocks++;expansionLastBlocked=reason;
        if(beyondFloor)expansionRefusals++;
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("reason",reason);data.put("mines",mines);data.put("mineTarget",mineTarget);
        data.put("beyondFloor",Boolean.valueOf(beyondFloor));
        data.put("attempts",expansionBlocks);data.put("gameTimeMs",time);
        event("economy_expansion_blocked",json(data));
    }

    private void reportFactoryBlocked(Map<String,Object> state,String reason,long mines,long factories)throws Exception{
        factoryBlocks++;
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("reason",reason);data.put("mines",mines);data.put("factories",factories);
        data.put("landFactoryTarget",landFactoryTarget);data.put("attempts",factoryBlocks);
        data.put("credits",n(obj(state.get("player")),"credits"));data.put("factoryCost",landFactoryCost);
        data.put("preferredUnit",lastPreferredUnit);data.put("preferredUnitCost",lastPreferredUnitCost);
        data.put("gameTimeMs",time);
        event("production_facility_blocked",json(data));
    }

    /**
     * P2-B diagnostic only: it never issues a command. It records the moments where the single factory
     * is saturated (queue non-empty) while credits are still climbing past a factory plus a unit, which
     * is the evidence the second-factory trigger is derived from rather than a guessed threshold.
     */
    private void reportProductionSurplus(Map<String,Object> state)throws Exception{
        if(!lastFactoryQueueNonEmpty)return;
        if(landFactoryCost<=0||lastPreferredUnitCost<=0)return;
        double credits=n(obj(state.get("player")),"credits");
        if(credits<landFactoryCost+lastPreferredUnitCost)return;
        if(time-lastSurplusLog<10000)return;
        lastSurplusLog=time;
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("credits",credits);data.put("completedMines",minesCompleted);
        data.put("completedFactories",landFactoriesCompleted);
        data.put("factoryQueueNonEmpty",Boolean.TRUE);
        data.put("preferredUnit",lastPreferredUnit);data.put("preferredUnitCost",lastPreferredUnitCost);
        data.put("factories",countType(state,"landFactory"));data.put("gameTimeMs",time);
        event("production_surplus",json(data));
    }

    /**
     * Economy v1b instruments. None of these decides anything on its own; the single behaviour they feed is
     * {@link #considerFactoryTargetIncrease}. Every one of them is deliberately LONG-WINDOW: a single frame
     * proves nothing here, because an army sitting at its hard cap empties every production queue by policy
     * and would otherwise look like "no demand for capacity".
     */
    private static double doubleProperty(String name,double fallback){
        try{return Double.parseDouble(System.getProperty(name,Double.toString(fallback)));}
        catch(NumberFormatException err){return fallback;}
    }
    /** Called once per poll: one utilisation sample, and the ledger pruned to the window. */
    private void observeFactoryLoad(Map<String,Object> state){
        long ready=0,busy=0;
        for(Map<String,Object> u:units(state)){
            if(!alive(u)||!"landFactory".equals(u.get("type")))continue;
            if(n(u,"buildProgress")<1.0)continue;
            ready++;
            if(n(u,"productionQueue")>0)busy++;
        }
        factoryLoadSamples.add(new long[]{time,ready,busy});
        long cutoff=time-economyWindowGameMs-6000;
        while(!factoryLoadSamples.isEmpty()&&factoryLoadSamples.get(0)[0]<cutoff)factoryLoadSamples.remove(0);
        long ledgerCutoff=time-economyWindowGameMs;
        while(!productionLedger.isEmpty()&&productionLedger.get(0)[0]<=ledgerCutoff)productionLedger.remove(0);
        while(!spendLedger.isEmpty()&&spendLedger.get(0)[0]<=ledgerCutoff)spendLedger.remove(0);
    }
    /**
     * Time-weighted share of observed ready-factory time during which that factory had something queued.
     * Returns {saturationPct, observedFactoryGameMs}: the second element is what says "the window is warm",
     * so a two-frame match can never look like a saturated one.
     */
    private double[] factoryLoadWindow(){
        long total=0,busy=0;
        for(int i=1;i<factoryLoadSamples.size();i++){
            long[] prev=factoryLoadSamples.get(i-1);
            long end=factoryLoadSamples.get(i)[0],gap=end-prev[0];
            long dt=end-Math.max(time-economyWindowGameMs,prev[0]);
            if(dt<=0||gap>6000)continue;
            total+=prev[1]*dt;busy+=prev[2]*dt;
        }
        return new double[]{total>0?100.0*busy/total:0.0,(double)total};
    }
    /** Accepted UNIT_PRODUCTION orders inside the long window, per game second. */
    private double productionConsumptionPerGameSecond(){
        long cutoff=time-economyWindowGameMs,sum=0;
        for(long[] entry:productionLedger)if(entry[0]>cutoff)sum+=entry[1];
        return sum/(economyWindowGameMs/1000.0);
    }
    /** Income capacity of the economy right now, from the calibrated model - not from a balance delta. */
    private double modelledIncomePerGameSecond(Map<String,Object> state){
        double equivalent=0;
        for(Map<String,Object> u:units(state))if(alive(u)&&n(u,"buildProgress")>=1){
            String type=String.valueOf(u.get("type"));
            if("extractorT2".equals(type))equivalent+=1.5;
            else if(type.startsWith("extractorT3"))equivalent+=2.5;
            else if(isExtractor(type))equivalent++;
        }
        return economyBaseIncome+economyIncomePerMine*equivalent;
    }
    private double sustainableSurplusPerGameSecond(Map<String,Object> state){
        return modelledIncomePerGameSecond(state)-productionConsumptionPerGameSecond();
    }
    /**
     * Economy v1b (对话38 裁决 §2): the ONE behaviour this version adds. One more land factory is justified by
     * a PRODUCTION THROUGHPUT BOTTLENECK, not by "the army is at its cap and the money has nowhere to go":
     *
     *   saturated   the existing factories had a queue for >= factorySaturationMinPct of the observed
     *               factory-time inside the window (long-term demand, not one busy frame);
     *   surplus     modelled income capacity > accepted production consumption over the same window, i.e.
     *               income is arriving faster than the existing factories can turn it into army;
     *   affordable  the price is covered AND both hard reserves (builder replacement, an active investment
     *               intent) stay untouched, so adding capacity can never break the recovery chain;
     *   warm        the match is at least one full window old, so neither rate is extrapolated from a
     *               partial history.
     *
     * Returns null when the target was raised (landFactoryTarget++), otherwise the reason it was not.
     */
    private String considerFactoryTargetIncrease(Map<String,Object> state,long factories)throws Exception{
        if(landFactoryTarget>=landFactoryTargetMax)return "TARGET_AT_MAX";
        if(strategy.enabled()){
            if(capacityBottleneck==ProductionCapacity.Bottleneck.ARMY_CAPACITY_LIMIT)return "ARMY_CAPACITY_LIMIT";
            if(capacityBottleneck==ProductionCapacity.Bottleneck.HARD_SAFETY_CAP)return "HARD_SAFETY_CAP";
            if(capacityBottleneck==ProductionCapacity.Bottleneck.NO_USEFUL_DEMAND)return "NO_USEFUL_DEMAND";
            if(capacityBottleneck==ProductionCapacity.Bottleneck.RESERVE_PROTECTED)return "RESERVE_PROTECTED";
            if(!capacitySustained)return "CAPACITY_WINDOW_UNPROVEN";
            if(capacityBottleneck!=ProductionCapacity.Bottleneck.PRODUCER_THROUGHPUT_LIMIT)return capacityBottleneck.name();
            if(time-lastProducerCapacityIncrease<economyWindowGameMs)return "CAPACITY_EXPANSION_COOLDOWN";
        }
        if(time-startTime<economyWindowGameMs)return "ECONOMY_WINDOW_WARMING";
        double[] load=factoryLoadWindow();
        if(load[1]<=0)return "FACTORY_LOAD_UNKNOWN";
        if(load[0]<factorySaturationMinPct)return "FACTORY_NOT_SATURATED";
        double income=modelledIncomePerGameSecond(state),consumption=productionConsumptionPerGameSecond();
        if(income<=consumption)return "NO_SUSTAINABLE_SURPLUS";
        long price=landFactoryCost>0?landFactoryCost:landFactoryCostDefault;
        if(strategy.enabled()){
            Map<String,Object> quote=nativeFactoryQuote(findBuilder(state));
            if(quote==null)return "NATIVE_FACTORY_ACTION_UNKNOWN";
            if(!Boolean.TRUE.equals(quote.get("affordable")))return "NATIVE_FACTORY_ACTION_UNAFFORDABLE";
            price=(long)StrategyDirector.number(quote,"cost",0);landFactoryCost=price;
        }
        double credits=n(obj(state.get("player")),"credits");
        if(credits<price+builderReserve+investmentReserve)return "INSUFFICIENT_CREDITS_FOR_FACTORY";
        if(wouldBreachCapabilityReserve(state,price))return "CAPABILITY_PURCHASE_RESERVED";
        long from=landFactoryTarget;landFactoryTarget++;
        factoryTargetIncreases++;
        // The target moved, so "target reached" is a fresh statement again once the new factory completes.
        factoryTargetLogged=false;
        // ... and the build of it is now a commitment (对话39 裁决 §2), not a fresh decision.
        factoryTargetCommitted=true;
        factoryCommitmentId="factory-capacity-"+factoryTargetIncreases+"-"+time;
        lastProducerCapacityIncrease=time;
        Map<String,Object> data=new LinkedHashMap<String,Object>(capacityEvidence);
        data.put("from",from);data.put("to",landFactoryTarget);data.put("readyFactories",factories);
        data.put("saturationPct",round2(load[0]));data.put("observedFactoryGameMs",(long)load[1]);
        data.put("windowGameMs",economyWindowGameMs);
        data.put("modelledIncomePerGameSecond",round1(income));
        data.put("productionConsumptionPerGameSecond",round1(consumption));
        data.put("sustainableSurplusPerGameSecond",round1(income-consumption));
        data.put("minesReady",countReadyExtractors(state));data.put("credits",credits);
        data.put("landFactoryCost",price);data.put("builderReserve",builderReserve);
        data.put("investmentReserve",investmentReserve);data.put("gameTimeMs",time);
        data.put("producerCapacityDecision","INCREASE");data.put("bottleneck","PRODUCER_THROUGHPUT_LIMIT");
        data.put("factoryCommitmentId",factoryCommitmentId);data.put("capabilityReserve",strategy.capabilityReserve());
        event("factory_target_increased",json(data));
        event("production_facility_committed",json(data));
        return null;
    }
    /** The gate is evaluated every decision, but only its CHANGES are logged, so a quiet match stays readable. */
    private void reportFactoryTargetIncreaseBlocked(Map<String,Object> state,String reason,long factories)throws Exception{
        if(reason.equals(lastFactoryIncreaseGate))return;
        lastFactoryIncreaseGate=reason;factoryTargetIncreaseBlocks++;
        Map<String,Object> data=new LinkedHashMap<String,Object>(capacityEvidence);
        double[] load=factoryLoadWindow();
        data.put("reason",reason);data.put("readyFactories",factories);
        data.put("landFactoryTarget",landFactoryTarget);data.put("landFactoryTargetMax",landFactoryTargetMax);
        data.put("saturationPct",round2(load[0]));data.put("saturationMinPct",factorySaturationMinPct);
        data.put("observedFactoryGameMs",(long)load[1]);data.put("windowGameMs",economyWindowGameMs);
        data.put("gameMsSinceStart",time-startTime);
        data.put("modelledIncomePerGameSecond",round1(modelledIncomePerGameSecond(state)));
        data.put("productionConsumptionPerGameSecond",round1(productionConsumptionPerGameSecond()));
        data.put("credits",n(obj(state.get("player")),"credits"));
        data.put("landFactoryCost",landFactoryCost>0?landFactoryCost:landFactoryCostDefault);
        data.put("builderReserve",builderReserve);data.put("investmentReserve",investmentReserve);
        data.put("gameTimeMs",time);
        event("factory_target_increase_blocked",json(data));
    }
    private static double round1(double value){return Math.round(value*10.0)/10.0;}
    /** Two decimals for threshold readings: a refusal at 79.98 must not print as 80.0 (对话39 裁决 §3). */
    private static double round2(double value){return Math.round(value*100.0)/100.0;}

    private static boolean isExtractor(String type){return type!=null&&type.startsWith("extractor");}
    private long countExtractors(Map<String,Object> state){
        long count=0;for(Map<String,Object> u:units(state))if(alive(u)&&isExtractor((String)u.get("type")))count++;return count;
    }
    /** A site still under construction is not a building yet: only finished ones satisfy a build target. */
    private long countReadyExtractors(Map<String,Object> state){
        long count=0;for(Map<String,Object> u:units(state))
            if(alive(u)&&isExtractor((String)u.get("type"))&&n(u,"buildProgress")>=1.0)count++;return count;
    }
    private long countReadyType(Map<String,Object> state,String type){
        long count=0;for(Map<String,Object> u:units(state))
            if(alive(u)&&type.equals(u.get("type"))&&n(u,"buildProgress")>=1.0)count++;return count;
    }

    private boolean attack(List<Map<String,Object>> force,double x,double y,String why,Map<String,Object> enemy)throws Exception{
        if(force.size()>48){
            // Native group commands remain bounded. Units not already on this order are served first;
            // the following tactic opportunity reinforces the rest through the same command gate.
            force=new ArrayList<Map<String,Object>>(force);
            Collections.sort(force,(a,b)->Boolean.compare(onAttackGoal(a,x,y),onAttackGoal(b,x,y)));
            force=new ArrayList<Map<String,Object>>(force.subList(0,48));
        }
        StringBuilder ids=new StringBuilder();for(Map<String,Object> u:force){if(ids.length()>0)ids.append(',');ids.append(id(u));}
        event("tactical_intent","{\"reason\":"+Json.quote(why)+",\"targetX\":"+x+",\"targetY\":"+y+",\"enemy\":"+(enemy==null?"null":json(enemy))+"}");
        Map<String,Object> receipt=post("/command/attack-move?unitIds="+ids+"&x="+x+"&y="+y);if(receipt==null)return false;
        // 杈撳嚭23 搂2: remember the target ID this order was aimed at (null for REGROUP / KNOWN_FRONTIER).
        Long previousTarget=selectedTargetEnemyId;
        selectedTargetEnemyId=enemy==null?null:Long.valueOf((long)n(enemy,"id"));
        if(enemy!=null)noteTargetOrder((long)n(enemy,"id"),previousTarget);
        attacks++;awaitingOrder=receipt;targetX=x;targetY=y;lastTactic=time;return true;
    }
    private void confirm(Map<String,Object> state)throws Exception{
        if(awaitingOrder==null)return;
        // A receipt without the fields this book-keeping needs is not worth ending the match over: drop it
        // and carry on. (Found by the v1a fixture, whose generic POST reply had no unitIds.)
        if(!(awaitingOrder.get("unitIds") instanceof List<?>)||!(awaitingOrder.get("targetX") instanceof Number)
           ||!(awaitingOrder.get("targetY") instanceof Number)){awaitingOrder=null;return;}
        List<Long> observed=new ArrayList<Long>();for(Object v:(List<?>)awaitingOrder.get("unitIds")){
            long id=((Number)v).longValue();Map<String,Object> u=find(state,id);
            if(u!=null&&alive(u)&&"attackMove".equals(u.get("orderType"))&&u.containsKey("orderX")
                &&Math.hypot(n(u,"orderX")-n(awaitingOrder,"targetX"),n(u,"orderY")-n(awaitingOrder,"targetY"))<1)observed.add(id);
        }
        if(!observed.isEmpty()){confirmed++;event("attack_order_confirmed","{\"requestId\":"+Json.quote((String)awaitingOrder.get("requestId"))+",\"unitIds\":"+observed+"}");awaitingOrder=null;}
    }
    private Map<String,Object> observe()throws Exception{
        Map<String,Object> s=get("/state","observation");observations++;
        if(!"running".equals(s.get("status"))||s.get("player")==null||Boolean.TRUE.equals(s.get("networked"))||Boolean.TRUE.equals(s.get("replay")))throw new IllegalStateException("Local active match required");
        if(session!=null)check(s);
        long frame=((Number)s.get("frame")).longValue();if(frame<lastFrame)throw new IllegalStateException("Frame went backwards");
        if(frame!=lastFrame){lastFrame=frame;frameAt=System.nanoTime();}else if((System.nanoTime()-frameAt)/1e9>30)throw new IllegalStateException("No advancing frames for 30 seconds");
        time=((Number)s.get("gameTimeMs")).longValue();
        // Speed readiness (对话35): the largest game-time step between two observations is the honest
        // measure of how coarse the client's view of the world became as the game speed rose.
        if(lastObservedGameTime>=0){
            long jump=time-lastObservedGameTime;
            if(jump>maxObservedGameTimeJump)maxObservedGameTimeJump=jump;
        }
        lastObservedGameTime=time;
        Map<String,Object> player=obj(s.get("player"));
        String playerKey=player.get("teamId") instanceof Number?"team:"+((Number)player.get("teamId")).longValue():"legacy-local";
        List<Long> ownIds=new ArrayList<Long>();for(Map<String,Object> unit:units(s))if(alive(unit))ownIds.add(id(unit));
        execution.observe(new CommandArbiter.Stamp((String)s.get("sessionId"),playerKey,frame,time),ownIds);
        artilleryLedger=artilleryLedger.observe(s,time);
        for(Map<String,Object> transition:artilleryLedger.transitions){
            Map<String,Object> data=new LinkedHashMap<String,Object>(transition);data.put("gameTimeMs",time);
            event("surplus_role_commitment_update",json(data));
        }
        return s;
    }
    private static boolean onAttackGoal(Map<String,Object> unit,double x,double y){
        return "attackMove".equals(unit.get("orderType"))&&unit.get("orderX") instanceof Number&&unit.get("orderY") instanceof Number
                &&Math.hypot(n(unit,"orderX")-x,n(unit,"orderY")-y)<1;
    }
    private int productionLimit(){
        if(strategy.enabled()&&lastState!=null&&committedProductionArmed(lastState)>=Math.min(mobileUnitHardCap,strategy.armyTarget()))return 0;
        return strategy.enabled()?(strategy.safetyCapacityAvailable()?Math.min(mobileUnitHardCap,strategy.armyTarget()):0):mobileUnitHardCap;
    }
    private double strategyReserve(){return builderReserve+investmentReserve+(buildJob!=null&&!buildJob.started?buildJob.cost:0);}
    public Map<String,Object> readStrategy(String path,String kind)throws Exception{
        Map<String,Object> answer=optionalGet(path,kind);if(answer!=null)check(answer);return answer;
    }
    public Map<String,Object> orderStrategy(String owner,String path)throws Exception{return post(owner,path,execution.stamp());}
    public void emitStrategy(String kind,Map<String,Object> data)throws Exception{event(kind,json(data));}
    public void spendStrategy(String category,long cost,String type,long actor)throws Exception{
        spend(category,cost,type,actor);
        if("heavyTank".equals(type)||"combatEngineer".equals(type)||"amphibiousJet".equals(type))productionLedger.add(new long[]{time,cost});
    }
    public void strategicAttack(Map<String,Object> receipt){attacks++;awaitingOrder=receipt;}
    private void check(Map<String,Object> s){if(!session.equals(s.get("sessionId")))throw new IllegalStateException("Session changed");}
    private Map<String,Object> post(String path)throws Exception{
        return post(CommandArbiter.DEFAULT_OWNER,path,execution.stamp());
    }
    private Map<String,Object> post(String owner,String path,CommandArbiter.Stamp stamp)throws Exception{
        List<Long> actors=new ArrayList<Long>();
        for(String field:path.substring(path.indexOf('?')+1).split("&")){
            if(field.startsWith("unitId=")||field.startsWith("unitIds="))
                for(String value:field.substring(field.indexOf('=')+1).split(","))actors.add(Long.valueOf(value));
        }
        String denied=execution.admit(stamp,owner,actors);
        if(denied!=null){
            if(!"COMMAND_GAME_TIME_BUDGET".equals(denied))event("command_arbitration_denied",
                    "{\"owner\":"+Json.quote(owner)+",\"reason\":"+Json.quote(denied)+",\"unitIds\":"+actors+",\"gameTimeMs\":"+time+"}");
            return null;
        }
        lastCommandAt=time;lastCommandOwner=owner;lastCommandPath=path.substring(0,path.indexOf('?'));
        path+="&sessionId="+session+"&requestId="+UUID.randomUUID();event("action","{\"path\":"+Json.quote(path)+",\"gameTimeMs\":"+time
                +",\"owner\":"+Json.quote(owner)+",\"observationFrame\":"+stamp.frame+"}");
        AgentClient.Response r=AgentClient.request("POST","http://127.0.0.1:"+port+path);
        if(r.status==409){event("command_rejected","{\"status\":409,\"body\":"+Json.quote(r.body)+"}");return null;}
        if(r.status!=200)throw new IllegalStateException("Command HTTP "+r.status+": "+r.body);
        Map<String,Object> result=obj(Json.parse(r.body));event("command_result",r.body);
        if(!"queued".equals(result.get("status")))throw new IllegalStateException("Invalid command receipt");check(result);commands++;return result;
    }
    /** The Recon policy emits a typed intent; the same ownership/time gate serves legacy commands. */
    private MoveExecution submitMove(CommandArbiter.MoveIntent intent,Map<String,Object> actor,boolean takeover)throws Exception{
        Map<String,Object> receipt=post(intent.owner,"/command/move?unitId="+intent.unitId+"&x="+intent.x+"&y="+intent.y,intent.observation);
        if(receipt==null)return null;
        if(!(receipt.get("frame") instanceof Number)||!(receipt.get("unitId") instanceof Number)
                ||((Number)receipt.get("unitId")).longValue()!=intent.unitId||!(receipt.get("requestId") instanceof String))
            throw new IllegalStateException("Move receipt lacks execution identity");
        return new MoveExecution(intent,(String)receipt.get("requestId"),((Number)receipt.get("frame")).longValue(),
                n(actor,"x"),n(actor,"y"),takeover);
    }
    private Map<String,Object> optionalGet(String path,String kind)throws Exception{
        AgentClient.Response r=AgentClient.request("GET","http://127.0.0.1:"+port+path);
        if(r.status==409){event("plan_unavailable","{\"path\":"+Json.quote(path)+",\"body\":"+Json.quote(r.body)+"}");return null;}
        if(r.status!=200)throw new IllegalStateException("GET HTTP "+r.status+": "+r.body);event(kind,r.body);return obj(Json.parse(r.body));
    }
    private Map<String,Object> get(String path,String kind)throws Exception{Map<String,Object> r=optionalGet(path,kind);if(r==null)throw new IllegalStateException("Unavailable "+path);return r;}
    private void event(String kind,String data)throws Exception{log.write("{\"wallTimeMs\":"+System.currentTimeMillis()+",\"event\":"+Json.quote(kind)+",\"data\":"+data+"}\n");log.flush();}
    static String json(Object v){
        if(v==null)return "null";if(v instanceof String)return Json.quote((String)v);if(v instanceof Number||v instanceof Boolean)return v.toString();
        StringBuilder s=new StringBuilder();if(v instanceof Map){s.append('{');for(Object entry:((Map<?,?>)v).entrySet()){Map.Entry<?,?> e=(Map.Entry<?,?>)entry;if(s.length()>1)s.append(',');s.append(Json.quote((String)e.getKey())).append(':').append(json(e.getValue()));}return s.append('}').toString();}
        s.append('[');for(Object item:(Iterable<?>)v){if(s.length()>1)s.append(',');s.append(json(item));}return s.append(']').toString();
    }
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object v){return (Map<String,Object>)v;}
    static List<Map<String,Object>> list(Map<String,Object> s,String key){List<Map<String,Object>> out=new ArrayList<Map<String,Object>>();for(Object u:(List<?>)s.get(key))out.add(obj(u));return out;}
    static List<Map<String,Object>> units(Map<String,Object> s){return list(s,"ownUnits");}
    static Map<String,Object> find(Map<String,Object> s,long id){for(Map<String,Object> u:units(s))if(id(u)==id)return u;return null;}
    static double n(Map<String,Object> u,String key){return ((Number)u.get(key)).doubleValue();}
    static long id(Map<String,Object> u){return ((Number)u.get("id")).longValue();}
    static boolean alive(Map<String,Object> u){return !Boolean.TRUE.equals(u.get("dead"))&&n(u,"hp")>0;}
    static boolean armed(Map<String,Object> u){return alive(u)&&Boolean.TRUE.equals(u.get("mobile"))&&Boolean.TRUE.equals(u.get("canAttack"))&&n(u,"buildProgress")>=1;}
    static List<Map<String,Object>> army(Map<String,Object> s){List<Map<String,Object>> out=new ArrayList<Map<String,Object>>();for(Map<String,Object> u:units(s))if(armed(u))out.add(u);return out;}
    static double distance(Map<String,Object> u,double x,double y){return Math.hypot(n(u,"x")-x,n(u,"y")-y);}
    static final class Pending{long started;boolean active;String type;int tier;}
}
