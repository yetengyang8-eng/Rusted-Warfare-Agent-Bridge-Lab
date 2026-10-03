package io.rwagent.client;

import java.net.URLEncoder;
import java.util.*;
import static io.rwagent.client.BattleClient.*;

/** Session-local capability demand, investment allocation and leased specialist tasks.
 * It consumes legal observations only. Geometry is evidence about an approach, never a kill promise.
 */
final class StrategyDirector {
    interface Host {
        Map<String,Object> readStrategy(String path,String event)throws Exception;
        Map<String,Object> orderStrategy(String owner,String path)throws Exception;
        default Map<String,Object> orderStrategy(String owner,String path,Map<String,Object> context)throws Exception{return orderStrategy(owner,path);}
        default WorldState worldStrategy(){return null;}
        default boolean parallelStrategy(){return false;}
        default double effectiveStrategyCredits(double nativeCredits){return nativeCredits;}
        /** Identity of the actual validated native menu/plan packet; absent metadata is UNKNOWN.
         * A later unrelated GET and the state observation are never this quote's price source. */
        default Map<String,Object> costSourceStrategy(Map<String,Object> nativeResponse){return Collections.emptyMap();}
        void emitStrategy(String event,Map<String,Object> data)throws Exception;
        void spendStrategy(String category,long cost,String type,long actor)throws Exception;
        void strategicAttack(Map<String,Object> receipt);
        /** Optional G1 sidecar. Its data must never be read by strategy policy. */
        default void traceStrategy(String event,Map<String,Object> data){}
    }
    private final Host host;
    private final CommandArbiter arbiter;
    private boolean enabled;
    private long now,started,remaining,lastCapacity=-100000,lastAllocation=-100000,taskSequence=1000000;
    private int hardCap=40,armyTarget=40,activeTarget=24,reserveTarget=8,builderTarget=1;
    private int militaryCapacityFloor,militaryCapacityIncreases;
    private double income,consumption,protectedFunds,homeX,homeY;
    private boolean homeEmergency;
    private Map<String,Object> state,enemies,scout;
    private final Map<Long,Need> needs=new LinkedHashMap<Long,Need>();
    private final Map<Long,Assessment> assessments=new LinkedHashMap<Long,Assessment>();
    private final Map<Long,Worker> workers=new LinkedHashMap<Long,Worker>();
    private final Map<Long,CapabilityTask> capabilityTasks=new LinkedHashMap<Long,CapabilityTask>();
    private final Map<Long,Purchase> purchases=new LinkedHashMap<Long,Purchase>();
    private final Set<Long> matchedConstructionProducts=new HashSet<Long>();
    private final Set<Long> matchedPurchasedWorkers=new HashSet<Long>();
    private final MineInvestmentPolicy mineInvestment=new MineInvestmentPolicy();
    private int investments,upgrades,completedJobs,resolvedNeeds;
    private CapabilityFunding capabilityFunding;
    private long capabilityFundingRetryAt;
    private int capabilityReservesStarted,capabilityReservesReleased;
    private static final class CapabilityFunding {
        final long need,producer,started,deadline;
        final String action;
        final double cost;
        CapabilityFunding(long need,long producer,String action,double cost,long now){
            this.need=need;this.producer=producer;this.action=action;this.cost=cost;started=now;deadline=now+90000;
        }
    }
    private static final class Assessment {
        long checked=-100000;String signature="";double x,y;
        final Set<Long> blocked=new HashSet<Long>();
        final Set<String> usefulProducts=new HashSet<String>();
        final Set<String> checkedProducts=new HashSet<String>(),unknownProducts=new HashSet<String>();
        boolean complete;
    }
    private static final class Need {
        final long id;String type,reason;double x,y;long seen,awaitVisibleAfter=-1;int failures;boolean building;
        Need(long id){this.id=id;}
    }
    private static final class Worker {
        final long unit,task;final String owner;
        String job="IDLE",product;long target=-1,jobAt,lastOrder=-100000,lastProgress,retryAt,lastAssessment=-100000,investigateAt,investigateSeen;
        double x,y,buildX,buildY,best=Double.MAX_VALUE,hp=Double.MAX_VALUE;
        Set<Long> before=new HashSet<Long>();
        Set<Long> prospects=new HashSet<Long>();
        Map<String,Object> supportQuote;
        double supportReserve;
        long supportDeadline;
        String modeAction;
        final CapabilityUnitMode mode;
        boolean paidConstruction;
        Worker(long unit,long task){this.unit=unit;this.task=task;owner="strategy:"+task;mode=new CapabilityUnitMode(unit);}
    }
    private static final class Purchase {
        final String product;final long at,selectedNeed;long need;boolean active,queueFinished,fulfilled;
        final Set<Long> before=new HashSet<Long>();
        Purchase(String product,long at){this(product,at,-1);}
        Purchase(String product,long at,long need){this.product=product;this.at=at;this.need=need;selectedNeed=need;}
    }
    StrategyDirector(Host host,CommandArbiter arbiter){this.host=host;this.arbiter=arbiter;}
    void enable(Map<String,Object> health,int hardCap){
        enabled=number(health,"strategyContractVersion",0)>=1
                &&!"false".equalsIgnoreCase(System.getProperty("rwagent.globalStrategy","true"));
        this.hardCap=hardCap;armyTarget=Math.min(40,hardCap);
    }
    boolean enabled(){return enabled;}
    int armyTarget(){return enabled?armyTarget:hardCap;}
    int activeTarget(){return activeTarget;}
    int builderTarget(){return builderTarget;}
    double capabilityReserve(){double value=capabilityFunding==null?0:capabilityFunding.cost;
        for(Worker worker:workers.values())value+=worker.supportReserve;return value;}
    boolean wouldBreachCapabilityReserve(double credits,double cost,double otherReserved){
        return capabilityReserve()>0&&credits-cost<capabilityReserve()+Math.max(0,otherReserved);
    }
    boolean pending(long actor){return purchases.containsKey(actor);}
    private double ordinaryNativePrice=-1;
    private int unobservedCombatSlots;
    void noteUnobservedCombatSlots(int slots){unobservedCombatSlots=Math.max(0,slots);}
    private long lastIncomeSurplusHold=-100000;
    void noteOrdinaryNativePrice(double price){if(Double.isFinite(price)&&price>0)ordinaryNativePrice=price;}
    int committedArmedForPolicy(){return state==null?0:committedArmed();}
    boolean safetyCapacityAvailable(){return !enabled||state==null||committedArmed()+unobservedCombatSlots<hardCap;}
    private int committedArmed(){
        int count=0;
        for(Map<String,Object> u:units(state))if(alive(u)){
            if(Boolean.TRUE.equals(u.get("mobile"))&&Boolean.TRUE.equals(u.get("canAttack")))count++;
            // Queue contents are not exported per item. Counting every factory slot as armed is a
            // conservative bound, including upgrades/builders rather than undercounting paid units.
            if("landFactory".equals(u.get("type")))count+=(int)Math.max(0,number(u,"productionQueue",0));
        }
        for(Purchase purchase:purchases.values())if(!purchase.active&&"combatEngineer".equals(purchase.product))count++;
        for(Worker worker:workers.values())if(worker.paidConstruction
                &&("heavyTank".equals(worker.product)||"amphibiousJet".equals(worker.product)))count++;
        return count;
    }
    void observe(Map<String,Object> state,Map<String,Object> enemies,Map<String,Object> scout,
                 List<Map<String,Object>> main,long start,long remainingMs,double income,double consumption,
                 double reserved,boolean buildBacklog)throws Exception{
        Map<String,Map<String,Object>> before=traceStates();
        try{observePolicy(state,enemies,scout,main,start,remainingMs,income,consumption,reserved,buildBacklog);}
        finally{traceStateChanges(before,"OBSERVE");}
    }
    private void observePolicy(Map<String,Object> state,Map<String,Object> enemies,Map<String,Object> scout,
                 List<Map<String,Object>> main,long start,long remainingMs,double income,double consumption,
                 double reserved,boolean buildBacklog)throws Exception{
        if(!enabled)return;
        this.state=state;this.enemies=enemies;this.scout=scout;now=(long)n(state,"gameTimeMs");started=start;
        mineInvestment.observe(state,enemies,scout);
        remaining=remainingMs;this.income=income;this.consumption=consumption;protectedFunds=reserved;
        for(Map<String,Object> unit:units(state))if("commandCenter".equals(unit.get("type"))){homeX=n(unit,"x");homeY=n(unit,"y");break;}
        homeEmergency=false;
        for(Map<String,Object> enemy:items(enemies,"visibleEnemies"))if(Boolean.TRUE.equals(enemy.get("canAttack"))
                &&distance(enemy,homeX,homeY)<500)homeEmergency=true;
        claimWorkers();updatePurchases();refreshAssessments(main);clearNeeds();updateWorkers();
        validateCapabilityFunding();
        if(now-lastCapacity>=30000){
            lastCapacity=now;
            int backlog=resourceBacklog(),armed=army(state).size();
            Map<String,Object> map=obj(state.get("map"));
            double area=number(map,"tilesWide",110)*number(map,"tilesHigh",110);
            int threats=0;for(Map<String,Object> enemy:items(enemies,"visibleEnemies"))if(Boolean.TRUE.equals(enemy.get("canAttack")))threats++;
            Capacity value=capacity(area,income,threats,needs.size(),backlog,buildBacklog,armed,
                    now-started,hardCap,System.getProperty("rwagent.mobileUnitHardCap")!=null);
            // Grow at most eight slots per evaluation. Never cancel already paid units when income falls.
            armyTarget=Math.min(hardCap,Math.min(Math.max(value.total,militaryCapacityFloor),armyTarget+8));activeTarget=Math.min(value.active,armyTarget);
            reserveTarget=Math.max(0,armyTarget-activeTarget);builderTarget=value.builders;
            emit("strategy_capacity",map("armyTarget",armyTarget,"activeArmyTarget",activeTarget,"reserveTarget",reserveTarget,
                "hardSafetyCap",hardCap,"builderTarget",builderTarget,"mapTiles",area,"incomeEstimate",income,
                "productionConsumption",consumption,"knownCapabilityNeeds",needs.size(),"resourceBacklog",backlog,
                "buildBacklog",buildBacklog,"fixedCapOverride",System.getProperty("rwagent.mobileUnitHardCap")!=null));
        }
    }
    /** Only current-visible, native-compatible ordinary actors with a known approach create
     * ordinary route demand. Capability needs and hidden contacts alone cannot authorize tanks.
     */
    String ordinaryDemand(String product){
        boolean visible=false,unknown=false;
        for(Map<String,Object> enemy:items(enemies,"visibleEnemies")){
            visible=true;Assessment a=assessments.get(id(enemy));
            if(a==null||!a.complete||now-a.checked>15000||Math.hypot(n(enemy,"x")-a.x,n(enemy,"y")-a.y)>20){unknown=true;continue;}
            if(a.usefulProducts.contains(product))return "KNOWN";
            if(!a.checkedProducts.contains(product)||a.unknownProducts.contains(product))unknown=true;
        }
        return !visible?"NONE":unknown?"UNKNOWN":"ROUTE_UNAVAILABLE";
    }
    boolean increaseMilitaryCapacity(int committed,int slots,Map<String,Object> evidence)throws Exception{
        if(!enabled||homeEmergency||committed>=hardCap||committed<armyTarget||armyTarget>=hardCap||slots<=0)return false;
        int old=armyTarget;armyTarget=Math.min(hardCap,armyTarget+Math.min(8,slots));
        militaryCapacityFloor=armyTarget;militaryCapacityIncreases++;
        activeTarget=Math.min(armyTarget-8,activeTarget+(armyTarget-old));reserveTarget=Math.max(0,armyTarget-activeTarget);
        Map<String,Object> data=new LinkedHashMap<String,Object>(evidence);
        data.put("oldTarget",old);data.put("newTarget",armyTarget);data.put("slotsAdded",armyTarget-old);
        data.put("reason","ARMY_CAPACITY_LIMIT");data.put("decision","BOUNDED_MILITARY_CAPACITY_INCREASE");
        emit("military_capacity_increased",data);return true;
    }
    /** Bounded policy targets. Area/observed threats/tasks create demand; income supports capacity. */
    static final class Capacity {
        final int active,total,builders;
        Capacity(int active,int total,int builders){this.active=active;this.total=total;this.builders=builders;}
    }
    static Capacity capacity(double area,double income,int threats,int needs,int backlog,boolean buildBacklog,
                             int army,long age,int hard,boolean fixed){
        double scale=Math.max(1,Math.min(3,Math.sqrt(area/12100.0)));
        int demand=24+(int)Math.ceil((scale-1)*24)+Math.min(24,threats*2)+(needs>0?8:0);
        int supported=32+(int)Math.max(0,(income-45)*.65);
        int total=fixed?hard:Math.min(hard,age<90000?40:Math.max(32,Math.min(96,Math.min(demand+12,supported))));
        int builders=age>=90000&&army>=12&&income>=70&&backlog>=2&&(buildBacklog||area>=20000)?2:1;
        return new Capacity(Math.min(demand,Math.max(1,total-8)),total,builders);
    }
    private void refreshAssessments(List<Map<String,Object>> force)throws Exception{
        if(force.isEmpty())return;
        List<Map<String,Object>> actors=new ArrayList<Map<String,Object>>();
        for(Map<String,Object> actor:force)if(!arbiter.reserved(id(actor)))actors.add(actor);
        if(actors.isEmpty())return;
        String ids=ids(actors);int queried=0;
        List<Map<String,Object>> contacts=new ArrayList<Map<String,Object>>(items(enemies,"visibleEnemies"));
        Collections.sort(contacts,(a,b)->Boolean.compare(Boolean.TRUE.equals(b.get("building")),Boolean.TRUE.equals(a.get("building"))));
        for(Map<String,Object> enemy:contacts){
            long eid=id(enemy);Assessment assessment=assessments.get(eid);
            if(assessment==null){assessment=new Assessment();assessments.put(eid,assessment);}
            if(now-assessment.checked<10000&&ids.equals(assessment.signature)
                    &&Math.hypot(n(enemy,"x")-assessment.x,n(enemy,"y")-assessment.y)<20)continue;
            if(queried++>=4)break;
            Map<String,Object> answer=null;List<Map<String,Object>> combined=new ArrayList<Map<String,Object>>();boolean complete=true;
            for(int offset=0;offset<actors.size();offset+=48){
                Map<String,Object> part=host.readStrategy("/combat/engagement?unitIds="+ids(actors.subList(offset,Math.min(actors.size(),offset+48)))+"&targetId="+eid,"engagement_observation");
                if(part==null||!Boolean.TRUE.equals(part.get("targetVisible"))){complete=false;break;}
                if(answer!=null&&Math.hypot(number(part,"targetX",0)-number(answer,"targetX",0),number(part,"targetY",0)-number(answer,"targetY",0))>20){complete=false;break;}
                answer=new LinkedHashMap<String,Object>(part);combined.addAll(items(part,"actors"));
            }
            assessment.checked=now;assessment.signature=ids;
            if(!complete||answer==null){
                assessment.complete=false;assessment.usefulProducts.clear();
                emit("engagement_assessment_deferred",map("targetId",eid,"reason","CONTACT_LOST_OR_MOVED_BETWEEN_FORMATION_BATCHES"));
                continue;
            }
            answer.put("actors",combined);
            host.emitStrategy("engagement_assessment",answer);
            if(Math.hypot(number(answer,"targetX",0)-assessment.x,number(answer,"targetY",0)-assessment.y)>20)
                assessment.blocked.clear();
            assessment.x=number(answer,"targetX",0);assessment.y=number(answer,"targetY",0);
            int blocked=0,incompatible=0,total=0;boolean possible=false;assessment.complete=true;
            assessment.usefulProducts.clear();assessment.checkedProducts.clear();assessment.unknownProducts.clear();
            for(Map<String,Object> actor:items(answer,"actors")){
                long uid=(long)n(actor,"unitId");total++;
                Map<String,Object> own=find(state,uid);
                if(own!=null){
                    String product=String.valueOf(own.get("type"));assessment.checkedProducts.add(product);
                    if(!"INCOMPATIBLE".equals(actor.get("compatibility"))&&!"BLOCKED_TERRAIN".equals(actor.get("status"))
                            &&!("COMPATIBLE".equals(actor.get("compatibility"))&&"APPROACH_PATH_KNOWN".equals(actor.get("status"))))
                        assessment.unknownProducts.add(product);
                }
                if("BLOCKED_TERRAIN".equals(actor.get("status"))){assessment.blocked.add(uid);blocked++;}
                else if("APPROACH_PATH_KNOWN".equals(actor.get("status")))assessment.blocked.remove(uid);
                // UNKNOWN cannot release a prior terrain rejection for the same last-known objective.
                if("INCOMPATIBLE".equals(actor.get("compatibility")))incompatible++;
                if("APPROACH_PATH_KNOWN".equals(actor.get("status"))&&"COMPATIBLE".equals(actor.get("compatibility"))){
                    possible=true;
                    if(own!=null&&("heavyTank".equals(own.get("type"))||"c_tank".equals(own.get("type"))||"tank".equals(own.get("type"))))
                        assessment.usefulProducts.add(String.valueOf(own.get("type")));
                }
            }
            if(total>0&&(blocked==total||incompatible==total)){
                Need need=needs.get(eid);boolean fresh=need==null;
                if(fresh){need=new Need(eid);needs.put(eid,need);}
                need.type=String.valueOf(enemy.get("type"));need.x=assessment.x;need.y=assessment.y;
                need.building=Boolean.TRUE.equals(enemy.get("building"));
                need.seen=(long)number(answer,"targetObservedAtGameTimeMs",now);
                need.reason=incompatible==total?"WEAPON_DOMAIN_GAP":"MOVEMENT_APPROACH_GAP";
                if(fresh)emit("capability_need_created",map("targetId",eid,"targetType",need.type,"reason",need.reason,
                    "x",need.x,"y",need.y,"observedAtGameTimeMs",need.seen,"evidence",answer));
            }else if(possible&&needs.containsKey(eid)&&!assigned(eid)){
                needs.remove(eid);capabilityTasks.remove(eid);emit("capability_need_released",map("targetId",eid,"reason","CURRENT_FORCE_HAS_NEW_APPROACH_EVIDENCE"));
            }
        }
        // Bound historical geometry without forgetting an unresolved need.
        if(assessments.size()>256){Iterator<Long> it=assessments.keySet().iterator();while(it.hasNext()&&assessments.size()>256){Long id=it.next();if(!needs.containsKey(id))it.remove();}}
    }
    List<Map<String,Object>> eligible(Map<String,Object> enemy,List<Map<String,Object>> force){
        if(!enabled)return force;
        Assessment a=assessments.get(id(enemy));if(a==null||a.blocked.isEmpty())return force;
        List<Map<String,Object>> result=new ArrayList<Map<String,Object>>();
        for(Map<String,Object> actor:force)if(!a.blocked.contains(id(actor)))result.add(actor);
        return result;
    }
    boolean rejected(long enemyId){Assessment a=assessments.get(enemyId);return enabled&&a!=null&&!a.blocked.isEmpty()&&needs.containsKey(enemyId);}
    private void clearNeeds()throws Exception{
        for(Map<String,Object> intel:items(enemies,"enemyIntel"))if("CLEARED".equals(intel.get("status"))){
            long id=id(intel);CapabilityTask task=capabilityTasks.remove(id);if(task!=null)task.legalSiteCleared();if(needs.remove(id)!=null){resolvedNeeds++;emit("capability_need_resolved",map("targetId",id,"reason","LEGAL_SITE_CLEARED","evidence",intel));}
            assessments.remove(id);
        }
    }
    private boolean assigned(long target){
        for(Worker w:workers.values())if(w.target==target)return true;
        for(Purchase p:purchases.values())if(p.need==target)return true;
        return false;
    }
    private boolean available(Need need){return need!=null&&need.failures<3&&need.seen>need.awaitVisibleAfter;}
    private Need eligibleNeed(){for(Need need:needs.values())if(available(need)&&!assigned(need.id))return need;return null;}
    private void bind(Worker worker,Need need,String reason,long producer)throws Exception{
        detachCapability(worker);worker.target=need.id;
        CapabilityTask collective=capabilityTasks.get(need.id);
        if(collective==null){collective=new CapabilityTask(need.id,need.id,need.reason);capabilityTasks.put(need.id,collective);}
        collective.attach(worker.unit,worker.mode);
        emit("strategy_worker_committed",map("taskId",worker.task,"unitId",worker.unit,"needId",need.id,
            "reason",reason,"producerId",producer,"assignmentSemantics","OBSERVED_AVAILABLE_ROLE_NOT_FACTORY_PROVENANCE"));
    }
    private void detachCapability(Worker worker){CapabilityTask task=capabilityTasks.get(worker.target);if(task!=null)task.detach(worker.unit);}
    private double effectiveCredits(){return host.effectiveStrategyCredits(n(obj(state.get("player")),"credits"));}
    private boolean joinableResponse(Need need){
        if(!host.parallelStrategy())return false;
        CapabilityTask task=capabilityTasks.get(need.id);if(task==null)return false;
        for(Long unit:task.members().keySet()){Map<String,Object> actor=find(state,unit);if(actor!=null&&"amphibiousJet".equals(actor.get("type")))return true;}
        return false;
    }
    private void claimWorkers()throws Exception{
        long baselineBuilder=Long.MAX_VALUE;
        for(Map<String,Object> unit:units(state))if(ready(unit)&&"builder".equals(unit.get("type")))baselineBuilder=Math.min(baselineBuilder,id(unit));
        for(Map<String,Object> unit:units(state)){
            long uid=id(unit);String type=String.valueOf(unit.get("type"));
            if(!ready(unit)||!("combatEngineer".equals(type)||"amphibiousJet".equals(type)||"builder".equals(type)&&uid!=baselineBuilder))continue;
            if(workers.containsKey(uid)||arbiter.reserved(uid))continue;
            Worker w=new Worker(uid,++taskSequence);
            if(arbiter.claim(w.owner,uid)){
                workers.put(uid,w);emit("task_ownership_acquired",map("taskId",w.task,"owner",w.owner,"unitId",uid,"role","STRATEGIC_SPECIALIST"));
            }
        }
        Worker baseline=workers.get(baselineBuilder);
        if(baseline!=null&&"IDLE".equals(baseline.job)){release(baseline,"BASELINE_BUILDER_HANDOVER");workers.remove(baselineBuilder);}
        for(Map.Entry<Long,Purchase> entry:purchases.entrySet()){
            Purchase purchase=entry.getValue();Need need=needs.get(purchase.need);
            if(!"combatEngineer".equals(purchase.product)||need==null||purchase.fulfilled)continue;
            for(Worker worker:workers.values())if(worker.target<0&&"IDLE".equals(worker.job)){
                Map<String,Object> actor=find(state,worker.unit);
                if(actor!=null&&ready(actor)&&"combatEngineer".equals(actor.get("type"))
                        &&!purchase.before.contains(worker.unit)&&!matchedPurchasedWorkers.contains(worker.unit)){
                    tracePurchaseMatch(entry.getKey(),purchase,worker);
                    matchedPurchasedWorkers.add(worker.unit);
                    purchase.need=-1;purchase.fulfilled=true;bind(worker,need,"PURCHASE_NEED_FULFILMENT",entry.getKey());break;
                }
            }
        }
    }
    private void updatePurchases()throws Exception{
        Iterator<Map.Entry<Long,Purchase>> it=purchases.entrySet().iterator();
        while(it.hasNext()){
            Map.Entry<Long,Purchase> entry=it.next();Purchase p=entry.getValue();Map<String,Object> unit=find(state,entry.getKey());
            if(p.need>=0&&!needs.containsKey(p.need))p.need=-1;
            if(unit==null||!alive(unit)){Need need=needs.get(p.need);if(need!=null)need.failures++;
                emit("strategy_purchase_lost",map("actorId",entry.getKey(),"product",p.product,"needId",p.need));it.remove();continue;}
            if(number(unit,"productionQueue",0)>0)p.active=true;
            boolean mineUpgrade="extractorT2".equals(p.product)||"extractorT3".equals(p.product);
            if(mineUpgrade&&p.product.equals(unit.get("type"))){
                upgrades++;emit("mine_upgrade_observed",map("unitId",id(unit),"product",p.product,"unit",unit));it.remove();
            }else if(!mineUpgrade&&(p.active||p.fulfilled)&&number(unit,"productionQueue",0)==0){
                if(!p.queueFinished){p.queueFinished=true;emit("strategy_production_queue_finished",map("producerId",id(unit),"product",p.product,
                    "needId",p.selectedNeed,"evidence",p.active?"QUEUE_BECAME_EMPTY":"AVAILABLE_WORKER_ASSIGNED"));}
                if(!"combatEngineer".equals(p.product)||p.fulfilled||p.need<0)it.remove();
                else if(now-p.at>180000){Need need=needs.get(p.need);if(need!=null)need.failures++;
                    emit("strategy_purchase_unconfirmed",map("actorId",entry.getKey(),"product",p.product,"needId",p.need,"reason","OBSERVATION_TIMEOUT"));it.remove();}
            }else if(now-p.at>180000){Need need=needs.get(p.need);if(need!=null)need.failures++;
                emit("strategy_purchase_unconfirmed",map("actorId",entry.getKey(),"product",p.product,"needId",p.need,"reason","OBSERVATION_TIMEOUT"));it.remove();}
        }
    }
    private void updateWorkers()throws Exception{
        Iterator<Worker> it=workers.values().iterator();
        while(it.hasNext()){
            Worker w=it.next();Map<String,Object> actor=find(state,w.unit);
            if(actor==null||!alive(actor)){
                Need need=needs.get(w.target);if(need!=null)need.failures++;
                releaseSupportReserve(w,"PROVIDER_LOST");
                emit("strategy_task_lost",map("taskId",w.task,"unitId",w.unit,"job",w.job));release(w,"ACTOR_LOST");it.remove();continue;
            }
            if(w.target>=0&&!needs.containsKey(w.target)){
                emit("strategy_task_completed",map("taskId",w.task,"targetId",w.target,"reason","NEED_RELEASED_BY_LEGAL_EVIDENCE"));
                releaseSupportReserve(w,"NEED_RELEASED");detachCapability(w);w.target=-1;if(!"BUILD".equals(w.job))w.job="IDLE";
            }
            Need commitment=needs.get(w.target);
            if(commitment!=null&&commitment.failures>=3){
                emit("strategy_worker_commitment_released",map("taskId",w.task,"unitId",w.unit,"needId",w.target,"reason","NEED_ATTEMPT_LIMIT"));
                detachCapability(w);w.target=-1;if(!"RETURN".equals(w.job)&&!"BUILD".equals(w.job))returnHome(w,15000);
            }
            if(w.paidConstruction){
                for(Map<String,Object> unit:units(state))if(!w.before.contains(id(unit))&&!matchedConstructionProducts.contains(id(unit))&&ready(unit)
                        &&w.product.equals(unit.get("type"))&&distance(unit,w.buildX,w.buildY)<120){
                    traceReadyMatch(w,unit);
                    matchedConstructionProducts.add(id(unit));
                    completedJobs++;emit("strategy_construction_observed",map("taskId",w.task,"builderId",w.unit,"product",w.product,"unit",unit));
                    Worker responder=workers.get(id(unit));Need supported=needs.get(w.target);
                    if("amphibiousJet".equals(w.product)&&responder!=null&&responder.target<0&&supported!=null){
                        detachCapability(w);w.target=-1;emit("strategy_worker_commitment_released",map("taskId",w.task,"unitId",w.unit,
                            "needId",supported.id,"reason","SUPPORT_RESPONDER_HANDOVER"));
                        bind(responder,supported,"OBSERVED_SUPPORT_RESPONDER",-1);
                        emit("strategy_support_transferred",map("taskId",w.task,"unitId",w.unit,"responderId",responder.unit,"needId",supported.id));
                    }
                    w.paidConstruction=false;if(!"RETURN".equals(w.job))w.job="IDLE";w.retryAt=now+5000;break;
                }
                if(w.paidConstruction&&now-w.jobAt>150000){
                    emit("strategy_task_blocked",map("taskId",w.task,"job",w.job,"reason","CONSTRUCTION_TIMEOUT"));
                    Need failed=needs.get(w.target);if(failed!=null)failed.failures++;
                    if(w.target>=0)emit("strategy_worker_commitment_released",map("taskId",w.task,"unitId",w.unit,"needId",w.target,"reason","CONSTRUCTION_TIMEOUT"));
                    detachCapability(w);w.target=-1;w.paidConstruction=false;if(!"RETURN".equals(w.job))w.job="IDLE";w.retryAt=now+30000;
                }
            }else if("MODE_APPROACH".equals(w.job)||"MODE_WAIT".equals(w.job)){
                double d=distance(actor,w.x,w.y);if(d+20<w.best){w.best=d;w.lastProgress=now;}
                if(now-w.jobAt>90000||now-w.lastProgress>45000){
                    Need failed=needs.get(w.target);if(failed!=null)failed.failures++;
                    emit("strategy_task_blocked",map("taskId",w.task,"unitId",w.unit,"targetId",w.target,"reason","MODE_TRANSITION_NO_PROGRESS"));
                    returnHome(w,30000);
                }
            }else if("PROSPECT".equals(w.job)){
                double d=distance(actor,w.x,w.y);
                if(d+20<w.best){w.best=d;w.lastProgress=now;}
                if(d<75){emit("strategy_prospect_observed",map("taskId",w.task,"unitId",w.unit,"unit",actor));w.job="IDLE";w.retryAt=now;}
                else if(now-w.lastProgress>45000||now-w.jobAt>120000){
                    emit("strategy_task_blocked",map("taskId",w.task,"job",w.job,"reason","PROSPECT_NO_PROGRESS"));w.job="IDLE";w.retryAt=now+15000;
                }
            }else if("RESPONSE".equals(w.job)||"INVESTIGATE".equals(w.job)){
                if(!needs.containsKey(w.target)){emit("strategy_task_completed",map("taskId",w.task,"targetId",w.target,"reason","NEED_RESOLVED"));w.job="IDLE";w.target=-1;continue;}
                assessResponse(w,actor);
                if("INVESTIGATE".equals(w.job)){
                    if(now-w.investigateAt>=15000){
                        Need need=needs.get(w.target);need.awaitVisibleAfter=Math.max(need.awaitVisibleAfter,w.investigateSeen);
                        emit("strategy_investigation_exhausted",map("taskId",w.task,"unitId",w.unit,"targetId",w.target,
                            "lastSeenGameTimeMs",w.investigateSeen,"reason","LAST_KNOWN_APPROACH_REACHED_WITHOUT_FRESH_CONTACT"));
                        returnHome(w,15000);
                    }
                    continue;
                }
                if(!"RESPONSE".equals(w.job))continue;
                double d=distance(actor,w.x,w.y);
                if(d+20<w.best){w.best=d;w.lastProgress=now;emit("strategy_response_progress",map("taskId",w.task,"unitId",w.unit,"targetId",w.target,"distance",d,"unit",actor));}
                for(Map<String,Object> enemy:items(enemies,"visibleEnemies"))if(id(enemy)==w.target){
                    if(n(enemy,"hp")<w.hp-0.01){
                        if(w.hp<Double.MAX_VALUE)emit("strategy_target_damage_observed",map("taskId",w.task,"targetId",w.target,"hpBefore",w.hp,"hpNow",n(enemy,"hp"),"attribution","TEAM_DAMAGE_NOT_EXCLUSIVE_TO_RESPONDER"));
                        w.hp=n(enemy,"hp");w.lastProgress=now;
                    }
                }
                if(now-w.lastProgress>60000||now-w.jobAt>240000){
                    Need need=needs.get(w.target);need.failures++;
                    emit("strategy_task_blocked",map("taskId",w.task,"targetId",w.target,"reason","NO_OBSERVED_PROGRESS","attempts",need.failures));
                    returnHome(w,45000);
                }
            }else if("RETURN".equals(w.job)&&distance(actor,homeX,homeY)<180&&now>=w.retryAt){
                Need waiting=needs.get(w.target);
                if(waiting!=null&&waiting.seen<=waiting.awaitVisibleAfter){
                    emit("strategy_worker_commitment_released",map("taskId",w.task,"unitId",w.unit,"needId",w.target,
                        "reason","STALE_SITE_INVESTIGATION_RETURNED_HOME"));detachCapability(w);w.target=-1;
                }
                if(n(actor,"hp")>=n(actor,"maxHp")*.3)w.job="IDLE";
            }
            if(w.supportReserve>0&&(now>=w.supportDeadline||homeEmergency||!available(needs.get(w.target))||!safetyCapacityAvailable()))
                releaseSupportReserve(w,now>=w.supportDeadline?"FUNDING_TIMEOUT":homeEmergency?"HOME_EMERGENCY":"NEED_OR_CAPACITY_UNAVAILABLE");
        }
    }
    /** Legacy hosts retain one slot; scheduler hosts admit independent workers in one observation. */
    boolean act(double reserved,long factoryTarget)throws Exception{
        Map<String,Map<String,Object>> before=traceStates();
        try{return actPolicy(reserved,factoryTarget);}
        finally{traceStateChanges(before,"ACT");}
    }
    private boolean actPolicy(double reserved,long factoryTarget)throws Exception{
        if(!enabled||!arbiter.ready(now))return false;
        protectedFunds=reserved;
        boolean acted=false;
        if(capabilityFunding!=null&&fulfilCapabilityFunding()){acted=true;if(!host.parallelStrategy())return true;}
        for(Worker worker:workers.values()){if(!arbiter.ready(now))break;if(workerAction(worker)){acted=true;if(!host.parallelStrategy())return true;}}
        if(!arbiter.ready(now))return acted;
        if(now-lastAllocation<8000)return acted;
        lastAllocation=now;
        double credits=effectiveCredits(),free=credits-protectedFunds-capabilityReserve();
        int force=army(state).size(),engineers=count("combatEngineer"),builders=count("builder");
        int backlog=resourceBacklog();
        Need selectedNeed=eligibleNeed();boolean unserved=selectedNeed!=null;
        // Explicit alternatives: a task capability gap outranks throughput, then safe growth.
        String product=null,reason=null;
        if(capabilityFunding==null&&now>=capabilityFundingRetryAt&&unserved&&engineers+pendingCount("combatEngineer")<Math.min(2,needs.size())&&force>=6&&!homeEmergency&&safetyCapacityAvailable()){product="combatEngineer";reason="UNSERVED_CAPABILITY_NEED";}
        else if(builders+pendingCount("builder")<builderTarget&&!homeEmergency){product="builder";reason="PARALLEL_CONSTRUCTION_BACKLOG";}
        Map<String,Object> alternatives=map("militaryDeficit",Math.max(0,armyTarget-force),"unservedCapabilityNeed",unserved,
            "constructionBacklog",backlog,"builders",builders,"builderTarget",builderTarget,"engineers",engineers,
            "freeCredits",free,"protectedFunds",protectedFunds,"remainingGameSeconds",remaining/1000);
        if(product!=null){
            Map<String,Object> menu=host.readStrategy("/combat/production","strategy_production_menu");
            providerAllocation: if(menu!=null)for(Map<String,Object> factory:items(menu,"factories"))if(number(factory,"queue",-1)==0&&!pending(id(factory))){
                for(Map<String,Object> action:items(factory,"actions"))if(product.equals(action.get("type"))
                        &&Boolean.TRUE.equals(action.get("affordable"))&&n(action,"cost")<=free){
                    alternatives.put("selected",reason);alternatives.put("action",action);emit("strategy_allocation",alternatives);
                    Map<String,Object> receipt=traceOrder(CommandArbiter.DEFAULT_OWNER,"/command/queue?unitId="+id(factory)+"&actionId="+encode(action.get("actionId")),costContext(menu,map("needId","combatEngineer".equals(product)?selectedNeed.id:-1,"commitmentId",purchaseTraceId(id(factory),now),"product",product,"cost",n(action,"cost"),"producerSlots",1,"militarySlots","combatEngineer".equals(product)?1:0)));
                    if(receipt!=null){long needId="combatEngineer".equals(product)?selectedNeed.id:-1;
                        purchases.put(id(factory),purchase(product,needId));
                        if(needId>=0)emit("strategy_purchase_committed",map("needId",needId,"producerId",id(factory),"product",product));
                        investments++;host.spendStrategy("STRATEGIC_CAPABILITY",(long)n(action,"cost"),product,id(factory));acted=true;if(!host.parallelStrategy())return true;break providerAllocation;}
                }
            }
            credits=effectiveCredits();free=credits-protectedFunds-capabilityReserve();
            if("combatEngineer".equals(product)&&startCapabilityFunding(menu,free))free-=capabilityReserve();
        }
        // Local risk and the disclosed payback window replace the blanket cash-surplus veto.
        // A distant crisis does not erase a quiet mine; shared task funds and replacements stay held.
        if(force>=6&&remaining>0){
            if(ordinaryNativePrice<=0){
                Map<String,Object> nativeProduction=host.readStrategy("/combat/production","strategy_price_menu");
                double quote=-1;
                if(nativeProduction!=null)for(Map<String,Object> factory:items(nativeProduction,"factories"))for(Map<String,Object> action:items(factory,"actions"))
                    if("heavyTank".equals(action.get("type"))&&n(action,"cost")>0)quote=n(action,"cost");
                if(quote<0&&nativeProduction!=null)for(Map<String,Object> factory:items(nativeProduction,"factories"))for(Map<String,Object> action:items(factory,"actions"))
                    if(("tank".equals(action.get("type"))||"c_tank".equals(action.get("type")))&&n(action,"cost")>0)quote=n(action,"cost");
                noteOrdinaryNativePrice(quote);
            }
            Map<String,Object> menu=host.readStrategy("/economy/investments","strategy_investment_menu");
            if(menu!=null)for(Map<String,Object> candidate:items(menu,"units")){
                Map<String,Object> mine=find(state,id(candidate));
                double cost=number(candidate,"cost",-1);
                boolean cheaperMineReady=backlog>0&&idleWorkerNearResource();
                MineInvestmentPolicy.Decision growth=mineInvestment.evaluate(mine,candidate,now,remaining/1000.0,
                    credits,protectedFunds+capabilityReserve(),ordinaryNativePrice,force,pending(id(candidate)),cheaperMineReady);
                emit("mine_income_investment_evaluated",new LinkedHashMap<String,Object>(growth.evidence));
                if(!growth.selected){
                    if(now-lastIncomeSurplusHold>=10000){
                        lastIncomeSurplusHold=now;Map<String,Object> hold=new LinkedHashMap<String,Object>(growth.evidence);
                        hold.put("mineId",id(candidate));emit("mine_income_investment_deferred",hold);
                    }
                    continue;
                }
                alternatives.putAll(growth.evidence);alternatives.put("selected","extractorT3".equals(growth.product)?"MINE_T3_INCOME_INVESTMENT":"MINE_T2_INCOME_INVESTMENT");
                alternatives.put("action",candidate);emit("strategy_allocation",alternatives);
                Map<String,Object> receipt=traceOrder(CommandArbiter.DEFAULT_OWNER,"/command/invest?unitId="+id(candidate)+"&actionId="+encode(candidate.get("actionId")),costContext(menu,map("cost",cost,"product",growth.product,"producerSlots",1,"quotedProducerQueue",candidate.get("queue"),"investmentSlotAvailable",pending(id(candidate))?0:1,"slotEvidenceSource","EXISTING_PURCHASE_COMMITMENT","commitmentId",purchaseTraceId(id(candidate),now))));
                if(receipt!=null){purchases.put(id(candidate),new Purchase(growth.product,now));investments++;host.spendStrategy("MINE_UPGRADE",(long)cost,growth.product,id(candidate));acted=true;if(!host.parallelStrategy())return true;credits=effectiveCredits();free=credits-protectedFunds-capabilityReserve();}
            }
        }
        // Combat engineers remain capability specialists. Only additional builders enter the
        // ordinary economic lane; a specialist may construct a responder for its explicit need.
        workerAllocation: for(Worker worker:workers.values())if("IDLE".equals(worker.job)&&now>=worker.retryAt){
            credits=effectiveCredits();free=credits-protectedFunds-capabilityReserve();
            Map<String,Object> actor=find(state,worker.unit);if(actor==null)continue;
            if("combatEngineer".equals(actor.get("type"))){
                if(startSupportBuild(worker,actor,free+worker.supportReserve)){acted=true;if(!host.parallelStrategy())return true;}
                continue;
            }
            if(!"builder".equals(actor.get("type")))continue;
            if(!nearThreat(n(actor,"x"),n(actor,"y"),400)){
                Map<String,Object> plan=host.readStrategy("/expansion/plan?unitId="+worker.unit,"strategy_expansion_plan");
                if(plan!=null&&number(plan,"extractorCost",Double.MAX_VALUE)<=free
                        &&!nearThreat(n(plan,"extractorX"),n(plan,"extractorY"),350)){
                    if(startBuild(worker,plan,"/command/build-extractor?unitId="+worker.unit+"&x="+n(plan,"extractorX")+"&y="+n(plan,"extractorY"),
                        "extractorT1",n(plan,"extractorX"),n(plan,"extractorY"),(long)n(plan,"extractorCost"),"MINE")){acted=true;if(!host.parallelStrategy())return true;continue workerAllocation;}
                }
                String build=count("landFactory")<factoryTarget?"landFactory":null;
                if(build!=null){
                    Map<String,Object> plan2=host.readStrategy("/economy/construction-plan?unitId="+worker.unit+"&type="+build,"strategy_construction_plan");
                    if(plan2!=null&&Boolean.TRUE.equals(plan2.get("affordable"))&&n(plan2,"cost")<=free){
                        if(startBuild(worker,plan2,"/command/construct?unitId="+worker.unit+"&actionId="+encode(plan2.get("actionId"))+"&x="+n(plan2,"x")+"&y="+n(plan2,"y"),
                            build,n(plan2,"x"),n(plan2,"y"),(long)n(plan2,"cost"),"BUILD")){acted=true;if(!host.parallelStrategy())return true;continue workerAllocation;}
                    }
                }
                // Additional construction capacity must actually reach its backlog, not stand at
                // home once all sites within the native 600-unit building search are occupied.
                List<Map<String,Object>> resources=new ArrayList<Map<String,Object>>(items(scout,"resources"));
                Collections.sort(resources,(a,b)->Double.compare(distance(actor,n(a,"x"),n(a,"y")),distance(actor,n(b,"x"),n(b,"y"))));
                int queriedResources=0;
                for(Map<String,Object> resource:resources){
                    long tile=(long)n(resource,"tile");
                    if(worker.prospects.contains(tile)||occupiedResource(resource)||nearThreat(n(resource,"x"),n(resource,"y"),350))continue;
                    if(queriedResources++>=4)break;
                    worker.prospects.add(tile);
                    Map<String,Object> approach=host.readStrategy("/scout/resource-approach?unitId="+worker.unit+"&tile="+tile,"strategy_resource_approach");
                    if(approach==null||!Boolean.TRUE.equals(approach.get("pathKnown")))continue;
                    if(traceOrder(worker.owner,"/command/move?unitId="+worker.unit+"&x="+n(approach,"x")+"&y="+n(approach,"y"))!=null){
                        worker.job="PROSPECT";worker.x=n(approach,"x");worker.y=n(approach,"y");worker.jobAt=worker.lastProgress=now;worker.best=distance(actor,worker.x,worker.y);
                        emit("strategy_prospect_ordered",map("taskId",worker.task,"unitId",worker.unit,"tile",tile,"evidence",approach));acted=true;if(!host.parallelStrategy())return true;continue workerAllocation;
                    }
                    break;
                }
            }
            worker.retryAt=now+15000;
        }
        return acted;
    }
    private void validateCapabilityFunding()throws Exception{
        if(capabilityFunding==null)return;
        Need need=needs.get(capabilityFunding.need);
        Map<String,Object> producer=find(state,capabilityFunding.producer);
        String reason=null;
        if(now>=capabilityFunding.deadline)reason="TIMEOUT";
        else if(!available(need))reason="NEED_UNAVAILABLE";
        else if(assigned(need.id))reason="NEED_ALREADY_SERVED";
        else if(homeEmergency)reason="HOME_EMERGENCY";
        else if(army(state).size()<6)reason="FORCE_BELOW_MINIMUM";
        else if(producer==null||!alive(producer))reason="PRODUCER_LOST";
        else if(!safetyCapacityAvailable())reason="CAPACITY_UNAVAILABLE";
        else if(count("combatEngineer")+pendingCount("combatEngineer")>=Math.min(2,needs.size()))reason="ENGINEER_CAPACITY_SATISFIED";
        if(reason!=null)releaseCapabilityFunding(reason);
    }
    private boolean startCapabilityFunding(Map<String,Object> menu,double free)throws Exception{
        if(capabilityFunding!=null||now<capabilityFundingRetryAt||!(income>0)||!Double.isFinite(income))return false;
        Need selected=eligibleNeed();
        if(selected==null)return false;
        for(Map<String,Object> factory:items(menu,"factories")){
            Map<String,Object> producer=find(state,id(factory));
            if(producer==null||!alive(producer)||pending(id(factory)))continue;
            for(Map<String,Object> action:items(factory,"actions")){
                double cost=number(action,"cost",0);
                if(!"combatEngineer".equals(action.get("type"))||!(action.get("actionId") instanceof String)
                        ||String.valueOf(action.get("actionId")).isEmpty()||!Double.isFinite(cost)||cost<=0||free>=cost)continue;
                double fundingSeconds=(cost-free)/income;
                if(fundingSeconds>60)continue;
                capabilityFunding=new CapabilityFunding(selected.id,id(factory),(String)action.get("actionId"),cost,now);
                capabilityReservesStarted++;
                Map<String,Object> data=capabilityFundingData("BOUNDED_CAPABILITY_FUNDING");
                data.put("freeCredits",free);data.put("protectedFunds",protectedFunds);data.put("incomeEstimate",income);
                data.put("estimatedFundingGameSeconds",fundingSeconds);data.put("estimateSemantics","BUDGET_FEASIBILITY_NOT_OUTCOME_PREDICTION");
                emit("strategy_capability_reserve_started",data);return true;
            }
        }
        return false;
    }
    /** A funded purchase keeps its original hard-reserve deduction, never its own reserve. */
    private boolean fulfilCapabilityFunding()throws Exception{
        CapabilityFunding funding=capabilityFunding;
        Map<String,Object> menu=host.readStrategy("/combat/production","strategy_production_menu");
        if(menu==null){releaseCapabilityFunding("MENU_UNAVAILABLE");return false;}
        Map<String,Object> producer=null,chosen=null;
        for(Map<String,Object> factory:items(menu,"factories"))if(id(factory)==funding.producer){producer=factory;break;}
        if(producer!=null)for(Map<String,Object> action:items(producer,"actions"))
            if("combatEngineer".equals(action.get("type"))&&funding.action.equals(action.get("actionId"))){chosen=action;break;}
        if(chosen==null){releaseCapabilityFunding("ACTION_UNAVAILABLE");return false;}
        if(number(chosen,"cost",Double.NaN)!=funding.cost){releaseCapabilityFunding("PRICE_CHANGED");return false;}
        double free=effectiveCredits()-protectedFunds-(capabilityReserve()-funding.cost);
        if(number(producer,"queue",-1)!=0||pending(funding.producer)||!Boolean.TRUE.equals(chosen.get("affordable"))||free<funding.cost)return false;
        Map<String,Object> data=capabilityFundingData("CAPABILITY_COMMITMENT_READY");
        data.put("selected","UNSERVED_CAPABILITY_NEED");data.put("action",chosen);data.put("freeCredits",free);data.put("protectedFunds",protectedFunds);
        emit("strategy_allocation",data);
        Map<String,Object> receipt=traceOrder(CommandArbiter.DEFAULT_OWNER,"/command/queue?unitId="+funding.producer+"&actionId="+encode(funding.action),costContext(menu,map("needId",funding.need,"commitmentId",purchaseTraceId(funding.producer,now),"product","combatEngineer","cost",funding.cost,"producerSlots",1,"militarySlots",1)));
        if(receipt==null){releaseCapabilityFunding("ORDER_REJECTED");return false;}
        purchases.put(funding.producer,purchase("combatEngineer",funding.need));investments++;
        emit("strategy_purchase_committed",map("needId",funding.need,"producerId",funding.producer,"product","combatEngineer"));
        host.spendStrategy("STRATEGIC_CAPABILITY",(long)funding.cost,"combatEngineer",funding.producer);
        releaseCapabilityFunding("PURCHASE_ACCEPTED");return true;
    }
    private Map<String,Object> capabilityFundingData(String reason){
        CapabilityFunding f=capabilityFunding;
        return map("needId",f.need,"producerId",f.producer,"product","combatEngineer","actionId",f.action,"cost",f.cost,
            "startedAtGameTimeMs",f.started,"deadlineGameTimeMs",f.deadline,"ageGameMs",now-f.started,"reason",reason);
    }
    private void releaseCapabilityFunding(String reason)throws Exception{
        if(capabilityFunding==null)return;
        Map<String,Object> data=capabilityFundingData(reason);capabilityFunding=null;capabilityReservesReleased++;
        if(!"PURCHASE_ACCEPTED".equals(reason))capabilityFundingRetryAt=now+60000;
        emit("strategy_capability_reserve_released",data);
    }
    private boolean workerAction(Worker w)throws Exception{
        Map<String,Object> actor=find(state,w.unit);if(actor==null||!ready(actor))return false;
        if(n(actor,"hp")<n(actor,"maxHp")*.3&&!"RETURN".equals(w.job)){
            Need interrupted=needs.get(w.target);
            if("RESPONSE".equals(w.job)&&interrupted!=null)interrupted.failures++;
            emit("strategy_task_preempted",map("taskId",w.task,"previousJob",w.job,"reason","CRITICAL_HP"));
            returnHome(w,45000);
        }
        if("RETURN".equals(w.job)){
            if("amphibiousJet".equals(actor.get("type"))&&now-w.lastOrder>=10000){
                Map<String,Object> modes=host.readStrategy("/combat/unit-modes?unitId="+w.unit,"strategy_unit_modes");
                if(modes!=null)traceMode(w,modes,null,"UNIT_MODES_NATIVE_FIELDS");
                if(modes!=null&&Boolean.TRUE.equals(modes.get("submergedWeaponAvailable")))for(Map<String,Object> action:items(modes,"actions"))
                    if("FLY".equals(action.get("mode"))&&Boolean.TRUE.equals(action.get("available"))&&Boolean.TRUE.equals(action.get("affordable"))){
                        if(traceOrder(w.owner,"/command/unit-mode?unitId="+w.unit+"&actionId="+encode(action.get("actionId")))!=null){
                            w.mode.flyAccepted();w.lastOrder=now;emit("strategy_responder_mode_ordered",map("taskId",w.task,"unitId",w.unit,"mode","FLY","reason","RETURN_TO_OWN_REAR","evidence",modes));return true;}
                        return false;
                    }
            }
            if(distance(actor,homeX,homeY)<180)return false;
            if("move".equals(actor.get("orderType"))&&actor.get("orderX") instanceof Number&&actor.get("orderY") instanceof Number
                    &&Math.hypot(n(actor,"orderX")-homeX,n(actor,"orderY")-homeY)<1)return false;
            if(now-w.lastOrder<10000)return false;
            if(traceOrder(w.owner,"/command/move?unitId="+w.unit+"&x="+homeX+"&y="+homeY)!=null){w.lastOrder=now;return true;}return false;
        }
        boolean engineer="combatEngineer".equals(actor.get("type")),jet="amphibiousJet".equals(actor.get("type"));
        if(jet&&("MODE_APPROACH".equals(w.job)||"MODE_WAIT".equals(w.job)))return modeResponse(w,actor);
        // A weapon-domain gap needs a production provider. Its own torpedo is conditional on
        // water position; sending the constructor to satisfy it sacrifices the rear production node.
        if(engineer&&"IDLE".equals(w.job)&&now>=w.retryAt){
            Need support=needs.get(w.target);
            if(support==null)for(Need candidate:needs.values())if(available(candidate)&&!assigned(candidate.id)
                    &&"WEAPON_DOMAIN_GAP".equals(candidate.reason)){support=candidate;break;}
            if(available(support)&&"WEAPON_DOMAIN_GAP".equals(support.reason)){
                if(w.target<0&&readyResponderAvailable(support))return false;
                if(w.target<0)bind(w,support,"REAR_CAPABILITY_PROVIDER",-1);
                if(distance(actor,homeX,homeY)>650||nearThreat(n(actor,"x"),n(actor,"y"),400)){
                    emit("strategy_provider_repositioned",map("taskId",w.task,"unitId",w.unit,"needId",support.id,
                        "reason","RETURN_TO_OWN_REAR_BEFORE_PRODUCTION","x",homeX,"y",homeY));
                    returnHome(w,0);return workerAction(w);
                }
                return startSupportBuild(w,actor,effectiveCredits()-protectedFunds-capabilityReserve()+w.supportReserve);
            }
        }
        if("IDLE".equals(w.job)&&now>=w.retryAt&&(engineer||jet)){
            List<Need> ordered=new ArrayList<Need>();
            if(w.target>=0){Need bound=needs.get(w.target);if(bound!=null)ordered.add(bound);}
            else ordered.addAll(needs.values());
            Collections.sort(ordered,(a,b)->Boolean.compare(b.building,a.building));
            for(Need need:ordered)if(available(need)&&(w.target==need.id||!assigned(need.id)||jet&&joinableResponse(need))){
                if(engineer&&"WEAPON_DOMAIN_GAP".equals(need.reason))continue;
                if(engineer&&threatCount(need.x,need.y,450)>1)continue;
                Map<String,Object> response=host.readStrategy("/combat/engagement?unitIds="+w.unit+"&targetId="+need.id,"response_engagement_observation");
                if(response==null)continue;
                List<Map<String,Object>> options=items(response,"actors");if(options.isEmpty())continue;
                Map<String,Object> choice=options.get(0);
                if(jet)traceMode(w,response,choice,"ENGAGEMENT_NATIVE_FIELDS");
                boolean current=Boolean.TRUE.equals(response.get("targetVisible"));
                if(jet&&"WEAPON_DOMAIN_GAP".equals(need.reason)&&!current)continue;
                if(jet&&current&&"DIVE".equals(choice.get("requiredMode"))
                        &&"APPROACH_PATH_KNOWN".equals(choice.get("modeApproachStatus"))&&choice.get("modeActionId") instanceof String){
                    if(w.target<0)bind(w,need,"AVAILABLE_MODE_CAPABLE_RESPONDER",-1);
                    w.job="MODE_APPROACH";w.mode.approachingWater();w.modeAction=String.valueOf(choice.get("modeActionId"));
                    w.x=n(choice,"modeApproachX");w.y=n(choice,"modeApproachY");w.jobAt=w.lastProgress=now;w.best=distance(actor,w.x,w.y);w.lastOrder=-100000;
                    emit("strategy_responder_mode_planned",map("taskId",w.task,"unitId",w.unit,"targetId",w.target,"mode","DIVE",
                        "x",w.x,"y",w.y,"evidence",response));return modeResponse(w,actor);
                }
                boolean investigate=!current&&(need.building||now-need.seen<180000)
                        &&"APPROACH_PATH_KNOWN".equals(choice.get("lastKnownPositionApproachStatus"));
                if(!investigate&&(!"COMPATIBLE".equals(choice.get("compatibility"))||!"APPROACH_PATH_KNOWN".equals(choice.get("status"))))continue;
                if(w.target<0)bind(w,need,"AVAILABLE_SPECIALIST",-1);
                w.job="RESPONSE";w.x=n(choice,"approachX");w.y=n(choice,"approachY");
                w.jobAt=w.lastProgress=now;w.lastOrder=-100000;w.best=Double.MAX_VALUE;w.hp=Double.MAX_VALUE;
                w.lastAssessment=now;
                emit("strategy_task_assigned",map("taskId",w.task,"unitId",w.unit,"role","CROSS_DOMAIN_RESPONSE","targetId",w.target,
                    "approachX",w.x,"approachY",w.y,"objectiveSemantics",investigate?"LAST_KNOWN_SITE_INVESTIGATION":"CURRENT_CONTACT_APPROACH","evidence",response));break;
            }
        }
        if("RESPONSE".equals(w.job)&&now-w.lastOrder>=12000){
            // Hold one objective. Other visible targets cannot steal this specialist through the main army.
            if("attackMove".equals(actor.get("orderType"))&&number(actor,"orderX",-1)==w.x&&number(actor,"orderY",-1)==w.y)return false;
            if(jet){
                Map<String,Object> fresh=host.readStrategy("/combat/engagement?unitIds="+w.unit+"&targetId="+w.target,"response_engagement_observation");
                if(fresh==null||!arbiter.stamp().session.equals(fresh.get("sessionId"))||number(fresh,"targetId",-1)!=w.target
                        ||!Boolean.TRUE.equals(fresh.get("targetVisible"))||number(fresh,"gameTimeMs",-1)<now
                        ||number(fresh,"targetObservedAtGameTimeMs",-1)!=number(fresh,"gameTimeMs",-2)||items(fresh,"actors").isEmpty())return false;
                Map<String,Object> chosen=items(fresh,"actors").get(0);
                if(number(chosen,"unitId",-1)!=w.unit||!"COMPATIBLE".equals(chosen.get("compatibility"))
                        ||!"APPROACH_PATH_KNOWN".equals(chosen.get("status")))return false;
                traceMode(w,fresh,chosen,"ENGAGEMENT_NATIVE_FIELDS");
                double x=n(chosen,"approachX"),y=n(chosen,"approachY");
                if(x!=w.x||y!=w.y)emit("strategy_response_replanned",map("taskId",w.task,"unitId",w.unit,"targetId",w.target,"x",x,"y",y,"evidence",fresh));
                w.x=x;w.y=y;w.lastAssessment=now;
            }
            emit("tactical_intent",map("reason","STRATEGIC_RESPONSE","targetX",w.x,"targetY",w.y,"targetId",w.target,"taskId",w.task));
            Map<String,Object> receipt=traceOrder(w.owner,"/command/attack-move?unitIds="+w.unit+"&x="+w.x+"&y="+w.y);
            if(receipt!=null){host.strategicAttack(receipt);w.lastOrder=now;emit("strategy_response_ordered",map("taskId",w.task,"unitId",w.unit,"targetId",w.target,"receipt",receipt));return true;}
        }
        return false;
    }
    private boolean modeResponse(Worker worker,Map<String,Object> actor)throws Exception{
        Need need=needs.get(worker.target);if(!available(need))return false;
        Map<String,Object> response=host.readStrategy("/combat/engagement?unitIds="+worker.unit+"&targetId="+worker.target,"response_engagement_observation");
        if(response==null||!Boolean.TRUE.equals(response.get("targetVisible"))||items(response,"actors").isEmpty())return false;
        Map<String,Object> choice=items(response,"actors").get(0);
        traceMode(worker,response,choice,"ENGAGEMENT_NATIVE_FIELDS");
        if("COMPATIBLE".equals(choice.get("compatibility"))&&"APPROACH_PATH_KNOWN".equals(choice.get("status"))){
            worker.job="RESPONSE";worker.x=n(choice,"approachX");worker.y=n(choice,"approachY");worker.lastOrder=-100000;
            worker.jobAt=worker.lastProgress=now;worker.best=Double.MAX_VALUE;worker.hp=Double.MAX_VALUE;
            Map<String,Object> observed=map("taskId",worker.task,"unitId",worker.unit,"targetId",worker.target,"mode","DIVE","evidence",response);
            if(host.parallelStrategy()){observed.put("actualModeProof",false);observed.put("unitMode",worker.mode.snapshot());}
            emit("strategy_responder_mode_observed",observed);
            emit("strategy_task_assigned",map("taskId",worker.task,"unitId",worker.unit,"targetId",worker.target,
                "approachX",worker.x,"approachY",worker.y,"role","CROSS_DOMAIN_RESPONSE","objectiveSemantics","CURRENT_CONTACT_APPROACH","evidence",response));return workerAction(worker);
        }
        if("MODE_WAIT".equals(worker.job))return false;
        if(!"DIVE".equals(choice.get("requiredMode"))||!"APPROACH_PATH_KNOWN".equals(choice.get("modeApproachStatus")))return false;
        if(Boolean.TRUE.equals(choice.get("modeActionReady"))){
            if(traceOrder(worker.owner,"/command/unit-mode?unitId="+worker.unit+"&actionId="+encode(choice.get("modeActionId")))!=null){
                worker.job="MODE_WAIT";worker.mode.diveAccepted();worker.lastOrder=worker.lastProgress=now;
                emit("strategy_responder_mode_ordered",map("taskId",worker.task,"unitId",worker.unit,"targetId",worker.target,"mode","DIVE","evidence",response));return true;}
            return false;
        }
        double x=n(choice,"modeApproachX"),y=n(choice,"modeApproachY");
        if(Math.hypot(x-worker.x,y-worker.y)>60){worker.x=x;worker.y=y;worker.best=distance(actor,x,y);worker.lastProgress=now;}
        if(now-worker.lastOrder<10000)return false;
        if("move".equals(actor.get("orderType"))&&number(actor,"orderX",-1)==worker.x&&number(actor,"orderY",-1)==worker.y)return false;
        if(traceOrder(worker.owner,"/command/move?unitId="+worker.unit+"&x="+worker.x+"&y="+worker.y)!=null){
            worker.lastOrder=now;emit("strategy_responder_water_approach_ordered",map("taskId",worker.task,"unitId",worker.unit,"targetId",worker.target,
                "x",worker.x,"y",worker.y,"evidence",response));return true;}
        return false;
    }
    private void returnHome(Worker w,long cooldown){w.mode.repositioning();w.job="RETURN";w.x=homeX;w.y=homeY;w.lastOrder=-100000;w.retryAt=now+cooldown;}
    private void assessResponse(Worker w,Map<String,Object> actor)throws Exception{
        // Reads are independent of the actuator slot, so an arrival/contact transition cannot
        // starve behind another task's command. Investigation polls retain the normal decision rate.
        if(!"INVESTIGATE".equals(w.job)&&now-w.lastAssessment<10000)return;
        w.lastAssessment=now;
        Map<String,Object> response=host.readStrategy("/combat/engagement?unitIds="+w.unit+"&targetId="+w.target,"response_engagement_observation");
        if(response==null)return;
        Need need=needs.get(w.target);if(need==null)return;
        if(Boolean.FALSE.equals(response.get("targetVisible"))){
            if("RESPONSE".equals(w.job)&&distance(actor,w.x,w.y)<=75){
                w.job="INVESTIGATE";w.investigateAt=now;w.investigateSeen=need.seen;
                emit("strategy_investigation_started",map("taskId",w.task,"unitId",w.unit,"targetId",w.target,
                    "lastSeenGameTimeMs",w.investigateSeen,"deadlineGameTimeMs",now+15000,"distance",distance(actor,w.x,w.y)));
            }
            return;
        }
        if(!Boolean.TRUE.equals(response.get("targetVisible"))||items(response,"actors").isEmpty())return;
        long seen=(long)number(response,"targetObservedAtGameTimeMs",-1);
        if(seen>=0)need.seen=Math.max(need.seen,seen);
        Map<String,Object> choice=items(response,"actors").get(0);
        if("INCOMPATIBLE".equals(choice.get("compatibility"))||"BLOCKED_TERRAIN".equals(choice.get("status"))){
            emit("strategy_task_preempted",map("taskId",w.task,"targetId",w.target,"reason","NEW_VISIBLE_NEGATIVE_EVIDENCE","evidence",response));
            returnHome(w,30000);return;
        }
        if("COMPATIBLE".equals(choice.get("compatibility"))&&"APPROACH_PATH_KNOWN".equals(choice.get("status"))){
            boolean resumed="INVESTIGATE".equals(w.job);
            if(resumed){w.job="RESPONSE";w.lastProgress=now;w.lastOrder=-100000;
                emit("strategy_investigation_contact_restored",map("taskId",w.task,"unitId",w.unit,"targetId",w.target,"observedAtGameTimeMs",seen));}
            if(resumed||Math.hypot(n(choice,"approachX")-w.x,n(choice,"approachY")-w.y)>60){
                w.x=n(choice,"approachX");w.y=n(choice,"approachY");w.best=distance(actor,w.x,w.y);
                emit("strategy_response_replanned",map("taskId",w.task,"targetId",w.target,"x",w.x,"y",w.y,"reason","SAME_TARGET_NEW_VISIBLE_APPROACH","evidence",response));
            }
        }
    }
    private boolean startSupportBuild(Worker worker,Map<String,Object> actor,double free)throws Exception{
        if(homeEmergency||nearThreat(n(actor,"x"),n(actor,"y"),400)||!safetyCapacityAvailable())return false;
        Need need=needs.get(worker.target);
        if(worker.target<0)for(Need candidate:needs.values())if(available(candidate)&&!assigned(candidate.id)
                &&"MOVEMENT_APPROACH_GAP".equals(candidate.reason)){need=candidate;break;}
        if(!available(need)||!("MOVEMENT_APPROACH_GAP".equals(need.reason)||"WEAPON_DOMAIN_GAP".equals(need.reason)))return false;
        int jets=count("amphibiousJet");for(Worker other:workers.values())if("amphibiousJet".equals(other.product)&&"BUILD".equals(other.job))jets++;
        if(jets>=4)return false;
        Map<String,Object> plan=host.readStrategy("/economy/construction-plan?unitId="+worker.unit+"&type=amphibiousJet","strategy_construction_plan");
        if(plan==null||!(plan.get("actionId") instanceof String)||String.valueOf(plan.get("actionId")).isEmpty()
                ||!Double.isFinite(number(plan,"cost",Double.NaN))||n(plan,"cost")<=0
                ||nearThreat(n(plan,"x"),n(plan,"y"),400)){
            releaseSupportReserve(worker,"PLAN_UNAVAILABLE_OR_UNSAFE");return false;
        }
        double cost=n(plan,"cost");
        if(worker.supportReserve>0&&(cost!=worker.supportReserve||!Objects.equals(plan.get("actionId"),worker.supportQuote.get("actionId")))){
            releaseSupportReserve(worker,"PRICE_OR_ACTION_CHANGED");worker.retryAt=now+15000;return false;
        }
        if(!Boolean.TRUE.equals(plan.get("affordable"))||cost>free){
            if(worker.supportReserve==0&&income>0&&Double.isFinite(income)&&(cost-free)/income<=60){
                if(worker.target<0)bind(worker,need,"REAR_CAPABILITY_PROVIDER",-1);
                worker.supportReserve=cost;worker.supportQuote=new LinkedHashMap<String,Object>(plan);worker.supportDeadline=now+90000;
                emit("strategy_support_reserve_started",map("taskId",worker.task,"unitId",worker.unit,"needId",need.id,
                    "product","amphibiousJet","cost",cost,"deadlineGameTimeMs",worker.supportDeadline,"evidence",plan));
            }
            return false;
        }
        if(startBuild(worker,plan,"/command/construct?unitId="+worker.unit+"&actionId="+encode(plan.get("actionId"))+"&x="+n(plan,"x")+"&y="+n(plan,"y"),
                "amphibiousJet",n(plan,"x"),n(plan,"y"),(long)n(plan,"cost"),"BUILD")){
            if(worker.target<0)bind(worker,need,"CAPABILITY_SUPPORT_CONSTRUCTION",-1);
            releaseSupportReserve(worker,"CONSTRUCTION_ACCEPTED");
            emit("strategy_support_construction",map("taskId",worker.task,"unitId",worker.unit,"needId",need.id,"product","amphibiousJet"));return true;
        }
        releaseSupportReserve(worker,"CONSTRUCTION_REJECTED");worker.retryAt=now+15000;
        return false;
    }
    private boolean readyResponderAvailable(Need need)throws Exception{
        for(Worker candidate:workers.values())if("IDLE".equals(candidate.job)&&candidate.target<0&&now>=candidate.retryAt){
            Map<String,Object> unit=find(state,candidate.unit);if(unit==null||!"amphibiousJet".equals(unit.get("type")))continue;
            Map<String,Object> observation=host.readStrategy("/combat/engagement?unitIds="+candidate.unit+"&targetId="+need.id,"response_engagement_observation");
            if(observation==null||!Boolean.TRUE.equals(observation.get("targetVisible"))||items(observation,"actors").isEmpty())continue;
            Map<String,Object> choice=items(observation,"actors").get(0);
            if("COMPATIBLE".equals(choice.get("compatibility"))&&"APPROACH_PATH_KNOWN".equals(choice.get("status"))
                    ||"DIVE".equals(choice.get("requiredMode"))&&"APPROACH_PATH_KNOWN".equals(choice.get("modeApproachStatus")))return true;
        }return false;
    }
    private Purchase purchase(String product,long need){Purchase purchase=new Purchase(product,now,need);
        for(Map<String,Object> unit:units(state))purchase.before.add(id(unit));return purchase;}
    private void releaseSupportReserve(Worker worker,String reason)throws Exception{
        if(worker.supportReserve<=0)return;
        emit("strategy_support_reserve_released",map("taskId",worker.task,"unitId",worker.unit,"needId",worker.target,
            "product","amphibiousJet","cost",worker.supportReserve,"reason",reason));
        worker.supportReserve=0;worker.supportQuote=null;
    }
    private boolean startBuild(Worker w,Map<String,Object> evidence,String path,String product,double x,double y,long cost,String job)throws Exception{
        if(traceOrder(w.owner,path,costContext(evidence,map("commitmentId",constructionTraceId(w,now),"product",product,"cost",cost,"producerSlots",1,"constructionSlotAvailable",w.paidConstruction?0:1,"slotEvidenceSource","EXISTING_WORKER_CONSTRUCTION_COMMITMENT","militarySlots",("heavyTank".equals(product)||"amphibiousJet".equals(product))?1:0)))==null)return false;
        w.job=job;w.product=product;w.paidConstruction=true;w.x=w.buildX=x;w.y=w.buildY=y;w.jobAt=now;w.before.clear();for(Map<String,Object> u:units(state))w.before.add(id(u));
        investments++;host.spendStrategy("STRATEGIC_CONSTRUCTION",cost,product,w.unit);
        emit("strategy_construction_ordered",map("taskId",w.task,"unitId",w.unit,"product",product,"x",x,"y",y,"evidence",evidence));return true;
    }
    private int count(String type){int count=0;for(Map<String,Object> u:units(state))if(ready(u)&&type.equals(u.get("type")))count++;return count;}
    private int pendingCount(String type){int count=0;for(Purchase p:purchases.values())if(type.equals(p.product))count++;return count;}
    private boolean nearThreat(double x,double y,double radius){
        for(Map<String,Object> e:items(scout,"rememberedThreats"))if(distance(e,x,y)<Math.max(radius,number(e,"range",0)+100))return true;return false;
    }
    private int threatCount(double x,double y,double radius){int count=0;for(Map<String,Object> e:items(scout,"rememberedThreats"))if(distance(e,x,y)<radius)count++;return count;}
    private int resourceBacklog(){
        int count=0;for(Map<String,Object> resource:items(scout,"resources")){
            if(nearThreat(n(resource,"x"),n(resource,"y"),350))continue;
            boolean occupied=false;for(Map<String,Object> unit:units(state))if(alive(unit)&&String.valueOf(unit.get("type")).startsWith("extractor")
                    &&distance(unit,n(resource,"x"),n(resource,"y"))<60){occupied=true;break;}
            if(!occupied)count++;
        }return count;
    }
    private boolean occupiedResource(Map<String,Object> resource){
        for(Map<String,Object> unit:units(state))if(alive(unit)&&String.valueOf(unit.get("type")).startsWith("extractor")
                &&distance(unit,n(resource,"x"),n(resource,"y"))<60)return true;
        for(Map<String,Object> unit:items(enemies,"visibleEnemies"))if(String.valueOf(unit.get("type")).startsWith("extractor")
                &&distance(unit,n(resource,"x"),n(resource,"y"))<60)return true;
        return false;
    }
    private boolean idleWorkerNearResource(){
        for(Worker w:workers.values())if("IDLE".equals(w.job)){
            Map<String,Object> actor=find(state,w.unit);if(actor==null)continue;
            if(!"builder".equals(actor.get("type")))continue;
            for(Map<String,Object> res:items(scout,"resources"))if(Boolean.TRUE.equals(res.get("currentlyVisible"))
                    &&distance(actor,n(res,"x"),n(res,"y"))<600&&!nearThreat(n(res,"x"),n(res,"y"),350)){
                boolean occupied=false;for(Map<String,Object> unit:units(state))if(alive(unit)&&String.valueOf(unit.get("type")).startsWith("extractor")&&distance(unit,n(res,"x"),n(res,"y"))<60)occupied=true;
                if(!occupied)return true;
            }
        }return false;
    }
    private void release(Worker w,String reason)throws Exception{
        detachCapability(w);
        if(arbiter.release(w.owner))emit("task_ownership_released",map("taskId",w.task,"owner",w.owner,"reason",reason));
    }
    void close()throws Exception{releaseCapabilityFunding("CONTROLLER_ENDED");for(Worker w:workers.values()){
        releaseSupportReserve(w,"CONTROLLER_ENDED");release(w,"CONTROLLER_ENDED");}workers.clear();capabilityTasks.clear();}
    Map<String,Object> summary(){Map<String,Object> result=map("enabled",enabled,"armyTarget",armyTarget,"activeTarget",activeTarget,"reserveTarget",reserveTarget,
        "hardSafetyCap",hardCap,"builderTarget",builderTarget,"militaryCapacityIncreases",militaryCapacityIncreases,
        "militaryCapacityFloor",militaryCapacityFloor,"capabilityNeedsAtEnd",needs.size(),"needsResolvedByLegalEvidence",resolvedNeeds,
        "investmentOrders",investments,"mineUpgradesObserved",upgrades,"constructionCompletionsObserved",completedJobs,
        "capabilityReservesStarted",capabilityReservesStarted,"capabilityReservesReleased",capabilityReservesReleased,"capabilityReserveAtEnd",capabilityReserve());
        if(host.parallelStrategy()){result.put("capabilityTasksAtEnd",capabilityTasks.size());result.put("capabilityModeScope","PER_UNIT");
            result.put("searchAreaPolicy","CONTRACT_ONLY_NEEDS_LEGAL_COVERAGE_INPUT");}return result;}
    private void emit(String event,Map<String,Object> data)throws Exception{
        data.put("gameTimeMs",now);data.put("sessionId",arbiter.stamp().session);data.put("player",arbiter.stamp().player);host.emitStrategy(event,data);
    }
    // G1 sidecars below never feed policy state, timing, matching, funding or command admission.
    private void trace(String event,Map<String,Object> data){
        try{data.put("detectedAtGameTimeMs",now);data.put("eventOccurredAtGameTimeMs",null);
            data.put("timeSemantics","DETECTED_BY_EXISTING_POLICY_NOT_EXACT_EVENT_TIME");
            host.traceStrategy(event,data);
        }catch(RuntimeException ignored){/* Optional diagnostics cannot alter existing actuation. */}
    }
    private Map<String,Object> costContext(Map<String,Object> nativeResponse,Map<String,Object> extra){
        Map<String,Object> context=new LinkedHashMap<String,Object>(extra);
        Map<String,Object> source=nativeResponse==null?null:host.costSourceStrategy(nativeResponse);
        Object id=source==null?null:source.get("costSourceObservationId"),path=source==null?null:source.get("costSourceRequestPath");
        boolean known=id instanceof String&&!((String)id).isEmpty()&&path instanceof String&&!((String)path).isEmpty()
                &&!"/state".equals(path)&&!((String)path).startsWith("/state?");
        context.put("costSourceObservationId",known?id:null);context.put("costSourceRequestPath",known?path:null);
        context.put("costSourceStatus",known?"VALIDATED_NATIVE_QUOTE_PACKET":"UNKNOWN_PRICE_SOURCE");
        return context;
    }
    private Map<String,Object> traceOrder(String owner,String path)throws Exception{
        return traceOrder(owner,path,Collections.<String,Object>emptyMap());
    }
    private Map<String,Object> traceOrder(String owner,String path,Map<String,Object> extra)throws Exception{
        Map<String,Object> context=map("lane","Strategy","owner",owner,"path",path);
        for(Worker worker:workers.values())if(worker.owner.equals(owner)){
            context.putAll(workerTrace(worker));break;
        }
        context.putAll(extra);
        WorldState world=host.worldStrategy();
        if(world!=null){
            context.put("worldRevision",world.revision);context.put("worldEpoch",world.epoch);
            context.put("worldEpochIsOwnerGeneration",false);context.put("worldSessionId",world.sessionId);
            Object unit=context.get("unitId");
            if(unit instanceof Number){WorldState.UnitFact fact=world.ownUnits().get(((Number)unit).longValue());
                context.put("worldActorAvailability",fact==null?"UNKNOWN":fact.availability);
                if(fact!=null){WorldState.SourceView source=world.views().get(fact.sourceScope);
                    context.put("worldActorSourceObservationId",source==null?null:source.observation.id);}}
            Object need=context.get("needId");
            if(need instanceof Number){WorldState.EnemyFact fact=world.enemies().get(((Number)need).longValue());
                context.put("worldTargetVisibility",fact==null?"UNKNOWN":fact.visibility);
                context.put("worldTargetCurrentKnown",fact!=null&&!fact.current.isEmpty());}
            context.put("worldUnknownSemantics","NO_HIDDEN_COORDINATES_OR_PRODUCTION_LINEAGE");
        }
        trace("command_context",context);
        return host.orderStrategy(owner,path,context);
    }
    private static String constructionTraceId(Worker worker,long at){return "strategy-construction:"+worker.task+":"+at;}
    private static String purchaseTraceId(long producer,long at){return "strategy-purchase:"+producer+":"+at;}
    private Map<String,Object> workerTrace(Worker worker){
        return map("taskId",worker.task,"unitId",worker.unit,"owner",worker.owner,"needId",worker.target,
            "job",worker.job,"capabilityTaskId",worker.target<0?null:worker.target,"unitMode",worker.mode.snapshot(),"paidConstruction",worker.paidConstruction,"product",worker.product,
            "commitmentId",worker.paidConstruction?constructionTraceId(worker,worker.jobAt):null);
    }
    private Map<String,Map<String,Object>> traceStates(){
        Map<String,Map<String,Object>> snapshots=new LinkedHashMap<String,Map<String,Object>>();
        for(Worker worker:workers.values())snapshots.put("worker:"+worker.unit,workerTrace(worker));
        for(Need need:needs.values())snapshots.put("need:"+need.id,map("needId",need.id,"targetType",need.type,
            "reason",need.reason,"failures",need.failures,"awaitVisibleAfterGameTimeMs",need.awaitVisibleAfter));
        for(Map.Entry<Long,Purchase> entry:purchases.entrySet()){
            Purchase purchase=entry.getValue();snapshots.put("purchase:"+entry.getKey(),map("producerId",entry.getKey(),
                "needId",purchase.selectedNeed,"boundNeedId",purchase.need,"product",purchase.product,
                "active",purchase.active,"queueFinished",purchase.queueFinished,"fulfilled",purchase.fulfilled,
                "commitmentId",purchaseTraceId(entry.getKey(),purchase.at)));
        }
        return snapshots;
    }
    private void traceStateChanges(Map<String,Map<String,Object>> before,String phase){
        Map<String,Map<String,Object>> after=traceStates();Set<String> keys=new LinkedHashSet<String>(before.keySet());keys.addAll(after.keySet());
        for(String key:keys)if(!Objects.equals(before.get(key),after.get(key))){
            Map<String,Object> identity=after.containsKey(key)?after.get(key):before.get(key);
            Map<String,Object> data=new LinkedHashMap<String,Object>(identity);
            data.put("objectKey",key);data.put("phase",phase);data.put("oldState",before.get(key));data.put("newState",after.get(key));
            data.put("transitionSemantics","NET_CHANGE_DURING_EXISTING_POLICY_CALL");trace("state_transition",data);
        }
    }
    Map<String,Object> capabilitySnapshot(){Map<String,Object> result=new LinkedHashMap<String,Object>();
        for(CapabilityTask task:capabilityTasks.values())result.put(String.valueOf(task.taskId),task.snapshot());return result;}
    Map<Long,Map<String,Object>> unitModes(){Map<Long,Map<String,Object>> result=new LinkedHashMap<Long,Map<String,Object>>();
        for(Worker worker:workers.values()){Map<String,Object> actor=find(state,worker.unit);if(actor!=null&&"amphibiousJet".equals(actor.get("type")))result.put(worker.unit,worker.mode.snapshot());}return result;}
    private void traceReadyMatch(Worker worker,Map<String,Object> selected){
        List<Long> candidates=new ArrayList<Long>();
        for(Map<String,Object> unit:units(state))if(!worker.before.contains(id(unit))&&!matchedConstructionProducts.contains(id(unit))&&ready(unit)
                &&worker.product.equals(unit.get("type"))&&distance(unit,worker.buildX,worker.buildY)<120)candidates.add(id(unit));
        Map<String,Object> data=workerTrace(worker);data.put("selectedUnitId",id(selected));data.put("candidateUnitIds",candidates);
        data.put("candidateCount",candidates.size());data.put("matchAmbiguous",candidates.size()>1);data.put("observedUnit",selected);
        data.put("matchSemantics","EXISTING_FIRST_MATCH_NEW_READY_TYPE_WITHIN_120");data.put("producerLineageConfirmed",false);
        data.put("sourceGameTimeMs",state.get("gameTimeMs"));data.put("sourceFrame",state.get("frame"));trace("ready_match_witness",data);
    }
    private void tracePurchaseMatch(long producer,Purchase purchase,Worker selected){
        List<Long> candidates=new ArrayList<Long>();
        for(Worker worker:workers.values())if(worker.target<0&&"IDLE".equals(worker.job)){
            Map<String,Object> actor=find(state,worker.unit);
            if(actor!=null&&ready(actor)&&"combatEngineer".equals(actor.get("type"))
                    &&!purchase.before.contains(worker.unit)&&!matchedPurchasedWorkers.contains(worker.unit))candidates.add(worker.unit);
        }
        trace("purchase_ready_match_witness",map("producerId",producer,"needId",purchase.selectedNeed,
            "commitmentId",purchaseTraceId(producer,purchase.at),"taskId",selected.task,"unitId",selected.unit,"owner",selected.owner,
            "candidateUnitIds",candidates,"candidateCount",candidates.size(),"matchAmbiguous",candidates.size()>1,
            "matchSemantics","EXISTING_FIRST_AVAILABLE_NEW_READY_WORKER","producerLineageConfirmed",false,
            "sourceGameTimeMs",state.get("gameTimeMs"),"sourceFrame",state.get("frame")));
    }
    private void traceMode(Worker worker,Map<String,Object> response,Map<String,Object> choice,String source)throws Exception{
        boolean responseWitnessAccepted=arbiter.stamp().session.equals(response.get("sessionId"))&&worker.mode.witness(response,choice,now);
        // Engagement movement is a desired flag, not native height completion. Only G3 obtains
        // the independent own-mode witness; the legacy toggle retains its exact GET sequence.
        if(host.parallelStrategy()&&choice!=null&&number(choice,"unitId",-1)==worker.unit){
            Map<String,Object> modes=host.readStrategy("/combat/unit-modes?unitId="+worker.unit,"strategy_unit_modes");
            if(modes!=null&&arbiter.stamp().session.equals(modes.get("sessionId"))){
                boolean actualAccepted=worker.mode.witness(modes,null,now);
                Map<String,Object> actual=workerTrace(worker);actual.put("nativeModeWitnessAccepted",actualAccepted);actual.put("sourceKind","UNIT_MODES_NATIVE_FIELDS");
                actual.put("sourceGameTimeMs",modes.get("gameTimeMs"));actual.put("sourceFrame",modes.get("frame"));
                actual.put("nativeSubmergedWeaponAvailable",modes.get("submergedWeaponAvailable"));
                actual.put("actualModeEvidence","NATIVE_OWN_SUBMERGED_THRESHOLD_NOT_EXACT_HEIGHT_OR_TERRAIN_PERMISSION");
                actual.put("legacyDiveObservedIsActualModeProof",false);trace("mode_evidence_witness",actual);
            }
        }
        Map<String,Object> data=workerTrace(worker);data.put("nativeModeWitnessAccepted",responseWitnessAccepted);data.put("sourceKind",source);data.put("sourceGameTimeMs",response.get("gameTimeMs"));
        data.put("sourceFrame",response.get("frame"));data.put("nativeSubmergedWeaponAvailable",response.get("submergedWeaponAvailable"));
        data.put("nativeMovementType",choice==null?null:choice.get("movementType"));
        data.put("nativeWeaponRange",choice==null?null:choice.get("weaponRange"));
        data.put("targetVisible",response.get("targetVisible"));data.put("targetDomain",response.get("targetDomain"));
        data.put("compatibility",choice==null?null:choice.get("compatibility"));data.put("approachStatus",choice==null?null:choice.get("status"));
        data.put("actualModeEvidence",choice==null?"OWN_UNIT_MODES_BOOLEAN_ONLY":"DESIRED_MOVEMENT_ONLY_PHYSICAL_MODE_NEEDS_EVIDENCE");
        data.put("legacyDiveObservedIsActualModeProof",false);trace("mode_evidence_witness",data);
    }
    static Map<String,Object> map(Object... kv){Map<String,Object> m=new LinkedHashMap<String,Object>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;}
    static double number(Map<String,Object> value,String key,double fallback){return value!=null&&value.get(key) instanceof Number?((Number)value.get(key)).doubleValue():fallback;}
    static boolean ready(Map<String,Object> u){return alive(u)&&number(u,"buildProgress",0)>=1;}
    static List<Map<String,Object>> items(Map<String,Object> m,String key){return m!=null&&m.get(key) instanceof List<?>?list(m,key):Collections.<Map<String,Object>>emptyList();}
    private static String ids(List<Map<String,Object>> units){StringBuilder s=new StringBuilder();for(Map<String,Object> u:units){if(s.length()>0)s.append(',');s.append(id(u));}return s.toString();}
    private static String encode(Object value)throws Exception{return URLEncoder.encode(String.valueOf(value),"UTF-8");}
}
