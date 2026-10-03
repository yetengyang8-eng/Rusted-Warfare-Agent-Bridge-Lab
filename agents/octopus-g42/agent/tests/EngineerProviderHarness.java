package io.rwagent.client;

import java.util.*;
import static io.rwagent.client.StrategyDirector.*;

/** Provider funding, paid-slot and actual mode observations through the real StrategyDirector. */
public final class EngineerProviderHarness {
    static int checks;
    static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static Map<String,Object> unit(long id,String type,double x,double y){return map("id",id,"type",type,"x",x,"y",y,
        "hp",1000,"maxHp",1000,"buildProgress",1,"productionQueue",0,"mobile",!"commandCenter".equals(type),
        "canAttack",!"builder".equals(type)&&!"commandCenter".equals(type),"dead",false,"orderType",null);}
    static final class Fixture implements StrategyDirector.Host {
        final CommandArbiter gate=new CommandArbiter();final StrategyDirector strategy=new StrategyDirector(this,gate);
        final List<Map<String,Object>> own=new ArrayList<Map<String,Object>>(),force=new ArrayList<Map<String,Object>>(),events=new ArrayList<Map<String,Object>>();
        final List<String> orders=new ArrayList<String>();
        final List<Map<String,Object>> traces=new ArrayList<Map<String,Object>>();
        final Map<String,Object> world,enemies,scout;Map<String,Object> engineer;
        boolean visible=true,submerged,waterKnown=true,reject,planAvailable=true,jetOffered,traceFailure;
        double jetCost=2000,reserved=500,income=100;long now;
        Fixture()throws Exception{
            own.add(unit(1,"commandCenter",100,100));own.add(unit(2,"builder",120,100));
            for(long id=10;id<18;id++){Map<String,Object> tank=unit(id,"heavyTank",400,200);force.add(tank);own.add(tank);}
            engineer=unit(80,"combatEngineer",100,100);own.add(engineer);
            world=map("gameTimeMs",120000L,"map",map("tilesWide",400,"tilesHigh",370),"player",map("credits",10000),"ownUnits",own);
            enemies=map("visibleEnemies",Arrays.asList(map("id",230L,"type","attackSubmarine","targetDomain","SUBMERGED", "building",false,"x",1250,"y",100,"hp",1000)),"enemyIntel",Collections.emptyList());
            scout=map("resources",Collections.emptyList(),"rememberedThreats",Collections.emptyList());
            strategy.enable(map("strategyContractVersion",1),128);observe(120000);
        }
        void observe(long value)throws Exception{now=value;world.put("gameTimeMs",value);List<Long> ids=new ArrayList<Long>();for(Map<String,Object> u:own)ids.add(BattleClient.id(u));
            gate.observe(new CommandArbiter.Stamp("s","team:0",value,value),ids);strategy.observe(world,enemies,scout,force,0,900000,income,60,reserved,false);}
        void credits(double value){BattleClient.obj(world.get("player")).put("credits",value);}
        long events(String event){return events.stream().filter(e->event.equals(e.get("event"))).count();}
        long orders(String prefix){return orders.stream().filter(p->p.startsWith(prefix)).count();}
        Map<String,Object> addJet(long id,double progress){Map<String,Object> jet=unit(id,"amphibiousJet",160,100);jet.put("buildProgress",progress);own.add(jet);return jet;}
        public Map<String,Object> readStrategy(String path,String event){
            if(path.startsWith("/combat/engagement")){
                List<Map<String,Object>> actors=new ArrayList<Map<String,Object>>();String values=path.split("unitIds=")[1].split("&")[0];
                for(String value:values.split(",")){long id=Long.parseLong(value);Map<String,Object> unit=BattleClient.find(world,id);boolean jet=unit!=null&&"amphibiousJet".equals(unit.get("type"));
                    Map<String,Object> actor=map("unitId",id,"compatibility",visible?(jet&&submerged?"COMPATIBLE":"INCOMPATIBLE"):"UNKNOWN",
                        "status",visible?"APPROACH_PATH_KNOWN":"UNKNOWN","lastKnownPositionApproachStatus","APPROACH_PATH_KNOWN","approachX",1200,"approachY",100);
                    if(jet&&!submerged&&visible)actor.putAll(map("requiredMode","DIVE","modeActionId","152","modeApproachStatus",waterKnown?"APPROACH_PATH_KNOWN":"UNKNOWN",
                        "modeApproachX",1200,"modeApproachY",100,"modeActionReady",BattleClient.distance(unit,1200,100)<50));actors.add(actor);}
                return map("sessionId","s","gameTimeMs",now,"targetId",230L,"targetVisible",visible,"targetObservedAtGameTimeMs",now,"targetX",1250,"targetY",100,"actors",actors);
            }
            if(path.contains("type=amphibiousJet")&&planAvailable)return map("status","planned","type","amphibiousJet","actionId","u_amphibiousJet","cost",jetCost,
                "affordable",number(BattleClient.obj(world.get("player")),"credits",0)>=jetCost,"x",number(engineer,"x",100)+60,"y",number(engineer,"y",100));
            if(path.startsWith("/combat/unit-modes"))return map("submergedWeaponAvailable",submerged,"actions",Arrays.asList(map("mode","FLY","actionId","151","available",true,"affordable",true)));
            if(path.equals("/combat/production"))return map("factories",Collections.emptyList());
            return null;
        }
        public Map<String,Object> orderStrategy(String owner,String path){
            String value=path.split(path.contains("unitIds=")?"unitIds=":"unitId=")[1].split("&")[0];List<Long> ids=new ArrayList<Long>();for(String id:value.split(","))ids.add(Long.valueOf(id));
            require(gate.admit(gate.stamp(),owner,ids)==null,"all provider/responder actions preserve owner and global game-time budget");orders.add(path);if(reject)return null;
            return map("status","queued","unitIds",ids,"requestId","r"+orders.size());
        }
        public void emitStrategy(String event,Map<String,Object> data){events.add(map("event",event,"data",new LinkedHashMap<String,Object>(data)));}
        public void traceStrategy(String event,Map<String,Object> data){
            if(traceFailure)throw new IllegalStateException("sidecar unavailable");
            traces.add(map("event",event,"data",new LinkedHashMap<String,Object>(data)));
        }
        public void spendStrategy(String category,long cost,String type,long actor){require(cost>0&&"amphibiousJet".equals(type),"construction spends native quoted product price");}
        public void strategicAttack(Map<String,Object> receipt){}
    }
    static void completeChain()throws Exception{
        Fixture fixture=new Fixture();require(fixture.strategy.act(500,1),"weapon domain gap constructs a rear responder");
        require(fixture.orders("/command/construct")==1&&fixture.orders("/command/attack-move?unitIds=80")==0,"provider never attacks the weapon-gap target itself");
        require(!fixture.gate.reserved(2)&&fixture.gate.reserved(80),"baseline builder stays available and provider retains its lease");
        Map<String,Object> jet=fixture.addJet(81,.5);fixture.observe(124000);fixture.strategy.act(500,1);
        require(fixture.events("strategy_support_transferred")==0,"unfinished new jet cannot fulfill paid support construction");
        jet.put("buildProgress",1);fixture.observe(128000);fixture.strategy.act(500,1);
        require(fixture.events("strategy_support_transferred")==1&&fixture.gate.reserved(81),"new ready jet uniquely inherits need and exclusive responder ownership");
        require(fixture.orders("/command/move?unitId=81")==1&&fixture.orders("/command/attack-move?unitIds=81")==0,"flight first approaches only known wet firing position");
        jet.put("x",1200);fixture.observe(132000);fixture.strategy.act(500,1);
        require(fixture.orders("/command/unit-mode?unitId=81&actionId=152")==1,"native Dive is ordered when own water position permits it");
        fixture.observe(134000);fixture.strategy.act(500,1);
        require(fixture.orders("/command/attack-move?unitIds=81")==0,"accepted Dive is not underwater weapon readiness");
        fixture.submerged=true;fixture.observe(136000);fixture.strategy.act(500,1);
        require(fixture.events("strategy_responder_mode_observed")==1&&fixture.orders("/command/attack-move?unitIds=81")==1,"actual fresh compatibility plus known approach permits the inherited underwater response");
        fixture.observe(144000);require(fixture.events("strategy_support_transferred")==1,"ready product cannot fulfill another construction twice");
        require(number(fixture.strategy.summary(),"needsResolvedByLegalEvidence",-1)==0,"production, move, Dive and attack receipt are not legal target-clearance proof");
        fixture.strategy.close();require(!fixture.gate.reserved(80)&&!fixture.gate.reserved(81),"provider and responder leases release at controller end");
    }
    static void budgets()throws Exception{
        Fixture funded=new Fixture();funded.credits(1000);funded.observe(121000);funded.strategy.act(500,1);
        require(funded.strategy.capabilityReserve()==2000&&funded.events("strategy_support_reserve_started")==1,"native responder quote gets bounded funding while preserving original reserves");
        require(funded.strategy.wouldBreachCapabilityReserve(2500,1,500),"ordinary purchases cannot consume responder's held funds");
        funded.credits(2500);funded.observe(125000);funded.strategy.act(500,1);
        require(funded.orders("/command/construct")==1&&funded.strategy.capabilityReserve()==0,"funded support excludes its own reserve but retains the independent 500");
        Fixture poor=new Fixture();poor.credits(2499);poor.reserved=500;poor.observe(121000);poor.strategy.act(500,1);
        require(poor.orders("/command/construct")==0,"native affordable flag cannot override independent funds");
        Fixture changed=new Fixture();changed.credits(1000);changed.observe(121000);changed.strategy.act(500,1);changed.jetCost=2100;changed.credits(3000);changed.observe(125000);changed.strategy.act(500,1);
        require(changed.strategy.capabilityReserve()==0&&changed.orders("/command/construct")==0,"price change releases commitment without immediate overspend");
        Fixture timeout=new Fixture();timeout.credits(1000);timeout.observe(121000);timeout.strategy.act(500,1);timeout.observe(211000);
        require(timeout.strategy.capabilityReserve()==0&&timeout.events("strategy_support_reserve_released")==1,"support funding expires after bounded game-time budget");
        Fixture lost=new Fixture();lost.credits(1000);lost.observe(121000);lost.strategy.act(500,1);lost.own.remove(lost.engineer);lost.observe(125000);
        require(lost.strategy.capabilityReserve()==0&&lost.events("strategy_task_lost")==1,"provider loss releases funds and need attempt");
        Fixture full=new Fixture();full.strategy.enable(map("strategyContractVersion",1),9);full.observe(121000);full.strategy.act(500,1);
        require(full.orders("/command/construct")==0,"real armed provider plus main force consumes hard safety cap before a new responder");
        Fixture emptyGap=new Fixture();emptyGap.strategy.act(500,1);int before=emptyGap.strategy.committedArmedForPolicy();emptyGap.observe(150000);
        require(emptyGap.strategy.committedArmedForPolicy()==before,"paid unobserved product retains combat slot");emptyGap.engineer.put("hp",100);emptyGap.engineer.put("x",900);emptyGap.observe(154000);emptyGap.strategy.act(500,1);
        require(emptyGap.strategy.committedArmedForPolicy()==before,"preempting wounded provider retains paid product capacity until ready or timeout");
        emptyGap.observe(274001);require(emptyGap.strategy.committedArmedForPolicy()==before-1,"unconfirmed construction timeout releases its ghost combat slot");
    }
    static void visibilityAndRear()throws Exception{
        Fixture rear=new Fixture();rear.engineer.put("x",1800);rear.observe(121000);rear.strategy.act(500,1);
        require(rear.orders("/command/move?unitId=80")==1&&rear.orders("/command/construct")==0,"forward-born provider first returns to own rear with normal move");
        rear.engineer.put("x",100);rear.observe(125000);rear.strategy.act(500,1);require(rear.orders("/command/construct")==1,"safe return enables normal native construction");
        Fixture unknown=new Fixture();unknown.strategy.act(500,1);unknown.addJet(81,1);unknown.observe(125000);unknown.waterKnown=false;unknown.strategy.act(500,1);
        require(unknown.orders("/command/move?unitId=81")==0&&unknown.orders("/command/unit-mode")==0,"unknown water approach cannot enable movement or Dive");
        Fixture hidden=new Fixture();hidden.strategy.act(500,1);Map<String,Object> jet=hidden.addJet(81,1);hidden.observe(125000);hidden.strategy.act(500,1);jet.put("x",1200);
        hidden.visible=false;hidden.enemies.put("visibleEnemies",Collections.emptyList());hidden.observe(129000);hidden.strategy.act(500,1);
        require(hidden.orders("/command/unit-mode")==0,"lost submarine contact cannot authorize fresh Dive");
        Fixture existing=new Fixture();existing.addJet(81,1);existing.observe(121000);existing.strategy.act(500,1);
        require(existing.orders("/command/construct")==0&&existing.orders("/command/move?unitId=81")==1,"usable existing jet responds before buying another product");
    }
    static Map<String,Object> trace(Fixture fixture,String event){
        for(Map<String,Object> item:fixture.traces)if(event.equals(item.get("event")))return BattleClient.obj(item.get("data"));
        throw new AssertionError("missing trace "+event);
    }
    static void traceEvidenceAndEquivalence()throws Exception{
        Fixture fixture=new Fixture();fixture.strategy.act(500,1);
        Map<String,Object> context=trace(fixture,"command_context");
        require(context.get("taskId") instanceof Number&&number(context,"needId",-1)==230&&context.get("commitmentId") instanceof String,
            "construction command context binds existing owner task need and paid commitment identity");
        require(context.get("eventOccurredAtGameTimeMs")==null&&number(context,"detectedAtGameTimeMs",-1)==120000,
            "policy detection time is never asserted as exact event occurrence time");
        fixture.addJet(81,1);fixture.addJet(82,1);fixture.observe(124000);
        Map<String,Object> matched=trace(fixture,"ready_match_witness");
        require(number(matched,"candidateCount",0)==2&&Boolean.TRUE.equals(matched.get("matchAmbiguous"))&&number(matched,"selectedUnitId",0)==81,
            "ambiguous ready candidates are recorded while the existing first match remains selected");
        require(Boolean.FALSE.equals(matched.get("producerLineageConfirmed"))&&context.get("commitmentId").equals(matched.get("commitmentId")),
            "paid commitment links witness without inventing strict native producer ancestry");
        fixture.strategy.act(500,1);
        Map<String,Object> mode=trace(fixture,"mode_evidence_witness");
        require(mode.get("nativeSubmergedWeaponAvailable")==null&&mode.get("nativeMovementType")==null
                &&Boolean.FALSE.equals(mode.get("legacyDiveObservedIsActualModeProof")),
            "missing native mode facts remain unknown despite mode plan or compatibility evidence");
        Fixture healthy=new Fixture(),broken=new Fixture();broken.traceFailure=true;
        healthy.strategy.act(500,1);broken.strategy.act(500,1);
        require(healthy.orders.equals(broken.orders)&&healthy.strategy.summary().equals(broken.strategy.summary()),
            "optional failing trace sink does not change command sequence or strategy outcome");
        require(trace(fixture,"state_transition").containsKey("oldState"),"net state transition includes before and after policy snapshot");
    }
    public static void main(String[] args)throws Exception{completeChain();budgets();visibilityAndRear();traceEvidenceAndEquivalence();System.out.println("ENGINEER_PROVIDER_TEST_OK checks="+checks);}
}
