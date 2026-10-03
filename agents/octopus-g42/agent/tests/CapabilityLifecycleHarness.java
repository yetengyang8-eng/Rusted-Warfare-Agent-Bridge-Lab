package io.rwagent.client;

import java.util.*;
import static io.rwagent.client.StrategyDirector.*;

/** Focused G3.5 vertical fixture: legal WorldState -> task/actor ownership -> Intent -> scheduler.
 * Native mode fields are supplied explicitly. This does not claim production lineage or game victory. */
public final class CapabilityLifecycleHarness {
    static int checks;
    static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    static final class Fixture implements StrategyDirector.Host {
        final EngineerProviderHarness.Fixture legacy=new EngineerProviderHarness.Fixture();
        final CommandArbiter gate=new CommandArbiter();final ExecutionScheduler scheduler=new ExecutionScheduler(gate);
        final StrategyDirector strategy=new StrategyDirector(this,gate);
        final GameClock clock=new GameClock("capability");final EventAdapter adapter=new EventAdapter("capability");
        final Set<Long> submerged=new HashSet<Long>(),desiredDive=new HashSet<Long>();final List<String> orders=new ArrayList<String>();
        final List<Map<String,Object>> contexts=new ArrayList<Map<String,Object>>();long sequence;
        boolean probeTrue,probeStale,probeForeign,probeWrongActor;int modeReads;
        Fixture()throws Exception{BattleClient.obj(legacy.world.get("player")).put("teamId",0);legacy.world.put("sessionId","s");
            strategy.enable(map("strategyContractVersion",1),128);observe(120000);}
        void observe(long now)throws Exception{
            legacy.now=now;legacy.world.put("gameTimeMs",now);legacy.world.put("frame",now);
            List<Long> actors=new ArrayList<Long>();Map<Long,Integer> slots=new LinkedHashMap<Long,Integer>();
            for(Map<String,Object> u:legacy.own){actors.add(BattleClient.id(u));slots.put(BattleClient.id(u),1);}
            CommandArbiter.Stamp stamp=new CommandArbiter.Stamp("s","team:0",now,now);gate.observe(stamp,actors);
            GameClock.Observation observation=clock.observe("/state",legacy.world,now,now,now);adapter.accept(observation,legacy.world);
            scheduler.beginObservation(stamp,observation.id,(long)number(BattleClient.obj(legacy.world.get("player")),"credits",0),slots,100);
            strategy.observe(legacy.world,legacy.enemies,legacy.scout,legacy.force,0,900000,100,60,500,false);
        }
        public WorldState worldStrategy(){return adapter.snapshot();}
        public boolean parallelStrategy(){return true;}
        public double effectiveStrategyCredits(double credits){return scheduler.effectiveCredits()==null?credits:scheduler.effectiveCredits();}
        public Map<String,Object> readStrategy(String path,String event){
            if(path.startsWith("/combat/engagement")){
                String actors=path.split("unitIds=")[1].split("&")[0];long first=Long.parseLong(actors.split(",")[0]);legacy.submerged=submerged.contains(first);
                Map<String,Object> packet=legacy.readStrategy(path,event);
                for(Map<String,Object> a:items(packet,"actors")){Map<String,Object> own=BattleClient.find(legacy.world,(long)BattleClient.n(a,"unitId"));
                    a.put("movementType",own!=null&&"amphibiousJet".equals(own.get("type"))?(desiredDive.contains((long)BattleClient.n(a,"unitId"))?"WATER":"AIR"):"LAND");}
                return packet;
            }
            if(path.startsWith("/combat/unit-modes")){long actor=Long.parseLong(path.split("unitId=")[1]);modeReads++;
                return map("sessionId",probeForeign?"foreign":"s","gameTimeMs",probeStale?legacy.now-1:legacy.now,"unitId",probeWrongActor?actor+100:actor,"submergedWeaponAvailable",probeTrue||submerged.contains(actor),
                    "actions",Arrays.asList(map("mode","FLY","actionId","151","cost",0,"available",true,"affordable",true)));}
            return legacy.readStrategy(path,event);
        }
        public Map<String,Object> orderStrategy(String owner,String path)throws Exception{return orderStrategy(owner,path,Collections.<String,Object>emptyMap());}
        public Map<String,Object> orderStrategy(String owner,String path,Map<String,Object> context)throws Exception{
            long actor=Long.parseLong(path.split(path.contains("unitIds=")?"unitIds=":"unitId=")[1].split("&")[0]);
            List<Long> actors=Arrays.asList(actor);boolean spend=context.get("cost") instanceof Number;
            Map<Long,Integer> slots=new LinkedHashMap<Long,Integer>();if(spend)slots.put(actor,((Number)context.get("producerSlots")).intValue());
            Intent.Commitment commitment=spend?Intent.Commitment.spending(((Number)context.get("cost")).longValue(),slots,(int)number(context,"militarySlots",0)):Intent.Commitment.none();
            Intent intent=Intent.create("capability-intent:"+(++sequence),owner,gate.snapshotGenerations(owner,actors),actors,"CAPABILITY","Strategy",40,
                gate.stamp(),scheduler.observationId(),"/state",path,commitment);
            ExecutionScheduler.Result result=scheduler.dispatch(intent,i->{orders.add(i.path);return map("status","queued","requestId",i.intentId);});
            if(result.accepted){contexts.add(new LinkedHashMap<String,Object>(context));
                if(path.startsWith("/command/unit-mode")&&path.contains("actionId=152"))desiredDive.add(actor);
                if(path.startsWith("/command/unit-mode")&&path.contains("actionId=151"))desiredDive.remove(actor);}
            return result.receipt;
        }
        public void emitStrategy(String event,Map<String,Object> data){legacy.emitStrategy(event,data);}
        public void spendStrategy(String category,long cost,String type,long actor){}
        public void strategicAttack(Map<String,Object> receipt){}
        boolean ordered(String prefix){return orders.stream().anyMatch(p->p.startsWith(prefix));}
    }
    static void independentVertical()throws Exception{
        Fixture f=new Fixture();check(f.strategy.act(500,1),"provider constructs through scheduler");
        check(f.ordered("/command/construct?unitId=80")&&f.scheduler.effectiveCredits()==8000,"native paid construction debits same batch");
        check(f.contexts.get(0).containsKey("commitmentId")&&f.contexts.get(0).containsKey("worldActorSourceObservationId"),"world source and paid commitment accompany Intent");
        f.observe(121000);f.strategy.act(500,1);
        check(f.orders.stream().filter(p->p.startsWith("/command/construct?unitId=80")).count()==1,"accepted paid construction remains occupied before product witness");
        Map<String,Object> wet=f.legacy.addJet(81,1),dry=f.legacy.addJet(82,1);wet.put("x",1200);
        f.observe(124000);check(f.strategy.act(500,1),"ready first match hands over and independent specialists advance");
        check(f.ordered("/command/unit-mode?unitId=81&actionId=152")&&f.ordered("/command/move?unitId=82"),"wet actor dives while dry actor approaches in same observation");
        check("DIVE_REQUESTED".equals(f.strategy.unitModes().get(81L).get("phase"))&&"MOVE_TO_WATER".equals(f.strategy.unitModes().get(82L).get("phase")),"collective task has independent actor phases");
        Map<String,Object> collective=BattleClient.obj(f.strategy.capabilitySnapshot().get("230"));
        check(BattleClient.obj(collective.get("unitModes")).size()==2&&!collective.containsKey("mode"),"one task holds two specialists and no group mode");
        check(f.legacy.events("strategy_support_transferred")==1,"first ready construction match retains existing handover semantics");
        f.submerged.add(81L);f.observe(125000);f.strategy.act(500,1);
        check(f.ordered("/command/attack-move?unitIds=81")&&!f.ordered("/command/attack-move?unitIds=82"),"native submerged actor engages without waiting for dry partner");
        check("SUBMERGED_READY".equals(f.strategy.unitModes().get(81L).get("phase")),"fresh own unit-modes Boolean advances own mode independently of desired movement");
        dry.put("x",1200);f.observe(128000);f.strategy.act(500,1);
        check(f.ordered("/command/unit-mode?unitId=82&actionId=152"),"second specialist independently reaches water and dives");
        f.submerged.add(82L);f.observe(132000);f.strategy.act(500,1);
        check(f.ordered("/command/attack-move?unitIds=82"),"second specialist independently joins response");
        wet.put("hp",200);f.observe(144000);f.strategy.act(500,1);
        check(f.ordered("/command/unit-mode?unitId=81&actionId=151")&&"FLY_REQUESTED".equals(f.strategy.unitModes().get(81L).get("phase")),"critical actor requests native Fly for own return");
        check("SUBMERGED_READY".equals(f.strategy.unitModes().get(82L).get("phase")),"partner return does not change other actor's mode");
        f.submerged.remove(81L);f.observe(158000);f.strategy.act(500,1);
        check("AIR_READY".equals(f.strategy.unitModes().get(81L).get("phase"))&&f.ordered("/command/move?unitId=81"),"native flight witness precedes independent return movement");
        check(number(f.strategy.summary(),"needsResolvedByLegalEvidence",-1)==0,"mode and attack receipts never resolve target need");
        f.strategy.close();check(!f.gate.reserved(80)&&!f.gate.reserved(81)&&!f.gate.reserved(82),"all specialist ownership releases");
    }
    static void noInferredModes(){CapabilityUnitMode mode=new CapabilityUnitMode(1);mode.diveAccepted();
        check(!mode.witness(map("gameTimeMs",10),map("unitId",1,"compatibility","COMPATIBLE","status","APPROACH_PATH_KNOWN"),10),"legacy compatible response is not actual Dive proof");
        check(mode.phase()==CapabilityUnitMode.Phase.DIVE_REQUESTED&&"UNKNOWN".equals(mode.nativeMode()),"receipt preserves unknown native mode");
        check(!mode.witness(map("gameTimeMs",9,"unitId",1,"submergedWeaponAvailable",true),null,10),"old native witness rejected");
        check(!mode.witness(map("gameTimeMs",10,"unitId",2,"submergedWeaponAvailable",true),null,10),"other actor native witness rejected");
        check(!mode.witness(map("gameTimeMs",10),map("unitId",1,"movementType","WATER"),10),"desired Dive movement cannot prove submerged native height");
        check(mode.phase()==CapabilityUnitMode.Phase.DIVE_REQUESTED&&"UNKNOWN".equals(mode.nativeMode())&&"WATER".equals(mode.snapshot().get("desiredMovementType")),"desired water is recorded without changing requested phase or physical mode");
        check(mode.witness(map("gameTimeMs",10,"unitId",1,"submergedWeaponAvailable",false),null,10)&&mode.phase()==CapabilityUnitMode.Phase.DIVE_REQUESTED&&"AIR".equals(mode.nativeMode()),"native false after desired Dive proves transition remains pending");
        check(mode.witness(map("gameTimeMs",11,"unitId",1,"submergedWeaponAvailable",true),null,11)&&mode.phase()==CapabilityUnitMode.Phase.SUBMERGED_READY,"later own native weapon witness confirms submerged threshold");
        mode.flyAccepted();
        check(!mode.witness(map("gameTimeMs",12),map("unitId",1,"movementType","AIR"),12)&&mode.phase()==CapabilityUnitMode.Phase.FLY_REQUESTED&&"SUBMERGED".equals(mode.nativeMode()),"desired Fly while native height remains submerged cannot prove flight");
        check(mode.witness(map("gameTimeMs",12,"unitId",1,"submergedWeaponAvailable",true),null,12)&&mode.phase()==CapabilityUnitMode.Phase.FLY_REQUESTED,"actual submerged witness preserves pending Fly transition");
        check(mode.witness(map("gameTimeMs",13,"unitId",1,"submergedWeaponAvailable",false),null,13)&&mode.phase()==CapabilityUnitMode.Phase.AIR_READY,"later native non-submerged witness confirms return readiness");
    }
    static void actualProbeValidation()throws Exception{
        Fixture f=new Fixture();f.strategy.act(500,1);Map<String,Object> jet=f.legacy.addJet(81,1);
        f.observe(124000);f.strategy.act(500,1);jet.put("x",1200);
        f.observe(125000);f.strategy.act(500,1);int reads=f.modeReads;
        f.observe(126000);f.strategy.act(500,1);
        check(f.modeReads>reads&&"DIVE_REQUESTED".equals(f.strategy.unitModes().get(81L).get("phase")),"desired-water engagement requests a separate actual own mode sample without premature ready: reads="+f.modeReads+" before="+reads+" mode="+f.strategy.unitModes().get(81L));
        f.probeTrue=true;f.probeStale=true;f.observe(127000);f.strategy.act(500,1);
        check("DIVE_REQUESTED".equals(f.strategy.unitModes().get(81L).get("phase")),"stale true probe cannot confirm actual readiness");
        f.probeStale=false;f.probeForeign=true;f.observe(128000);f.strategy.act(500,1);
        check("DIVE_REQUESTED".equals(f.strategy.unitModes().get(81L).get("phase")),"foreign-session true probe cannot confirm actual readiness");
        f.probeForeign=false;f.probeWrongActor=true;f.observe(129000);f.strategy.act(500,1);
        check("DIVE_REQUESTED".equals(f.strategy.unitModes().get(81L).get("phase")),"other-actor true probe cannot confirm actual readiness");
        f.probeWrongActor=false;f.observe(130000);f.strategy.act(500,1);
        check("SUBMERGED_READY".equals(f.strategy.unitModes().get(81L).get("phase")),"only matching fresh same-session true probe confirms own submerged threshold");
        check(!f.ordered("/command/attack-move?unitIds=81"),"mode readiness alone cannot replace separate legal engagement and terrain compatibility");
    }
    static void lawfulSearch(){
        check(SearchAreaNeed.lawful(true,true,false,"a",SearchAreaNeed.Domain.WATER,0,0,100,100,.2,null,.9,100,"coverage:o:1")==null,"ongoing alone cannot invent search region");
        check(SearchAreaNeed.lawful(false,true,true,"a",SearchAreaNeed.Domain.WATER,0,0,100,100,.2,null,.9,100,"coverage:o:1")==null,"ended battle creates no search task");
        check(SearchAreaNeed.lawful(true,false,true,"a",SearchAreaNeed.Domain.WATER,0,0,100,100,.2,null,.9,100,"coverage:o:1")==null,"ordinary reachable target suppresses terminal search trigger");
        SearchAreaNeed need=SearchAreaNeed.lawful(true,true,true,"a",SearchAreaNeed.Domain.WATER,0,0,100,100,.2,50L,.9,100,"coverage:o:1");
        check(need.confidenceAt(600,1000)==.45&&Math.abs(need.confidenceWeightedCoverageAt(600,1000)-.09)<1e-9&&need.lastSearchedAgeAt(600)==550,"coverage confidence and search age remain explicit");
        check("NEEDS_EVIDENCE".equals(need.underwaterDiscoveryRequirement()),"underwater discovery Dive requirement remains unknown");
        SearchTask task=new SearchTask(need,Arrays.asList(9L,8L));check(task.regions().get(8L).contains(49,50)&&!task.regions().get(8L).contains(50,50)&&task.regions().get(9L).contains(50,50),"stable specialist partition has no duplicate boundary");
        GameClock clock=new GameClock("search");EventAdapter adapter=new EventAdapter("search");
        Map<String,Object> state=map("sessionId","s","gameTimeMs",100L,"frame",1,"player",map("teamId",0),"ownUnits",Collections.emptyList());
        adapter.accept(clock.observe("/state",state,100,100,100L),state);
        Map<String,Object> enemy=map("id",99L,"type","submarine","hp",100,"x",20,"y",50,"lastSeenGameTimeMs",100L,"targetDomain","SUBMERGED","domainObservedAtGameTimeMs",100L);
        Map<String,Object> combat=map("sessionId","s","gameTimeMs",100L,"frame",1,"visibleEnemies",Arrays.asList(enemy),"enemyIntel",Collections.emptyList());
        adapter.accept(clock.observe("/combat/observe",combat,100,100,100L),combat);
        check(task.discover(adapter.snapshot(),9,99)==null,"other specialist region cannot claim duplicate discovery");
        SearchTask.TargetTask target=task.discover(adapter.snapshot(),8,99);
        check(target!=null&&target.targetId==99&&target.sourceObservationId!=null&&task.state()==SearchTask.State.TARGET_DISCOVERED,"lawful current discovery converts to target contract");
        combat.put("gameTimeMs",200L);combat.put("frame",2);combat.put("visibleEnemies",Collections.emptyList());adapter.accept(clock.observe("/combat/observe",combat,200,200,200L),combat);
        check(new SearchTask(need,Arrays.asList(8L)).discover(adapter.snapshot(),8,99)==null,"lost visibility cannot become new target from hidden coordinates");
    }
    public static void main(String[] args)throws Exception{noInferredModes();lawfulSearch();independentVertical();actualProbeValidation();System.out.println("CapabilityLifecycleHarness checks="+checks+" PASS");}
}
