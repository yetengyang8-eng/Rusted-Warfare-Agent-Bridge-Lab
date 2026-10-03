package io.rwagent.client;

import java.util.*;
import static io.rwagent.client.StrategyDirector.*;

/** Deterministic E2 contracts: unknown terrain, capability debt, allocation and exclusive workers. */
public final class StrategyContractHarness {
    static int checks;
    static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static Map<String,Object> unit(long id,String type,double x,double y){return map("id",id,"type",type,"x",x,"y",y,"hp",1000,"maxHp",1000,
        "buildProgress",1,"productionQueue",0,"mobile",!type.equals("commandCenter"),"canAttack",type.equals("heavyTank")||type.equals("combatEngineer"),"dead",false,"orderType",null);}
    static void geometry(){
        int w=30,h=20;byte[] land=new byte[w*h];boolean[] water=new boolean[w*h];
        for(int x=15;x<w;x++)for(int y=0;y<h;y++){land[x*h+y]=-1;water[x*h+y]=true;}
        EngagementGeometry.Result blocked=EngagementGeometry.solve(w,h,20,20,land,water,110,210,510,210,160,0,false);
        require("BLOCKED_TERRAIN".equals(blocked.status),"deep-water surface building has no LAND firing position");
        require("APPROACH_PATH_KNOWN".equals(EngagementGeometry.solve(w,h,20,20,land,water,110,210,370,210,160,0,false).status),"coastal naval building inside range remains eligible");
        byte[] unknown=land.clone();Arrays.fill(unknown,EngagementGeometry.UNKNOWN);
        require("UNKNOWN".equals(EngagementGeometry.solve(w,h,20,20,unknown,water,110,210,510,210,160,0,false).status),"unseen terrain cannot prove blockage");
        // A never-seen corridor through a known barrier must invalidate a negative path proof.
        for(int x=15;x<w;x++)land[x*h+5]=EngagementGeometry.UNKNOWN;
        require("UNKNOWN".equals(EngagementGeometry.solve(w,h,20,20,land,water,110,210,510,210,160,0,false).status),"optimistic unknown corridor is preserved");
        byte[] hover=new byte[w*h];
        EngagementGeometry.Result submarine=EngagementGeometry.solve(w,h,20,20,hover,water,110,210,510,210,170,0,true);
        require("APPROACH_PATH_KNOWN".equals(submarine.status)&&water[(int)(submarine.x/20)*h+(int)(submarine.y/20)],"conditional torpedo plans a water position");
        require("BLOCKED_TERRAIN".equals(EngagementGeometry.solve(w,h,20,20,hover,new boolean[w*h],110,210,510,210,170,0,true).status),"dry firing position cannot satisfy conditional torpedo");
        require("UNKNOWN".equals(EngagementGeometry.solve(w,h,20,20,null,water,110,210,510,210,170,0,false).status),"missing rules remain unknown");
        EngagementGeometry.Field shared=EngagementGeometry.prepare(w,h,20,20,hover,water,510,210,170,0,false);
        for(int x=0;x<15;x++)require("APPROACH_PATH_KNOWN".equals(shared.from(x*20+10,210).status),"shared field serves distinct formation origins");
    }
    static final class Fake implements StrategyDirector.Host {
        final CommandArbiter gate=new CommandArbiter();
        final List<Map<String,Object>> events=new ArrayList<Map<String,Object>>();
        final List<String> orders=new ArrayList<String>();
        Map<String,Object> world;String approach="BLOCKED_TERRAIN",specialistApproach="APPROACH_PATH_KNOWN";
        boolean visible=true;int upgradeOrders;
        double engineerCost=3500;
        boolean engineerOffered=true,productionMenuAvailable=true,rejectOrders,minePlan,supportPlan;
        public Map<String,Object> readStrategy(String path,String event){
            if(path.startsWith("/combat/engagement")){
                String ids=path.split("unitIds=")[1].split("&")[0];List<Map<String,Object>> actors=new ArrayList<Map<String,Object>>();
                for(String value:ids.split(",")){
                    long uid=Long.parseLong(value);Map<String,Object> unit=BattleClient.find(world,uid);
                    boolean specialist=unit!=null&&("combatEngineer".equals(unit.get("type"))||"amphibiousJet".equals(unit.get("type")));
                    String status=specialist?specialistApproach:approach;
                    actors.add(map("unitId",uid,"status",visible?status:"UNKNOWN","lastKnownPositionApproachStatus",status,
                        "compatibility",visible?"COMPATIBLE":"UNKNOWN","approachX",490,"approachY",90));
                }
                return map("sessionId","session","gameTimeMs",world.get("gameTimeMs"),"targetId",Long.parseLong(path.split("targetId=")[1].split("&")[0]),
                    "targetVisible",visible,"targetX",510,"targetY",70,"targetObservedAtGameTimeMs",world.get("gameTimeMs"),"actors",actors);
            }
            if(path.equals("/combat/production")){
                if(!productionMenuAvailable)return null;
                List<Map<String,Object>> actions=new ArrayList<Map<String,Object>>();
                if(engineerOffered)actions.add(map("actionId","u_combatEngineer","type","combatEngineer","cost",engineerCost,
                    "affordable",number(BattleClient.obj(world.get("player")),"credits",0)>=engineerCost));
                actions.add(map("actionId","u_builder","type","builder","cost",500,"affordable",true));
                Map<String,Object> factory=BattleClient.find(world,90);
                return map("factories",Arrays.asList(map("id",90L,"queue",number(factory,"productionQueue",0),"actions",actions)));
            }
            if(path.startsWith("/expansion/plan")&&minePlan)return map("extractorCost",700,"extractorX",3090,"extractorY",3100);
            if(path.contains("type=amphibiousJet")&&supportPlan)return map("actionId","jet","affordable",true,"cost",2000,"x",3100,"y",3100);
            if(path.equals("/economy/investments"))return map("units",Arrays.asList(map("id",91L,"queue",0,"actionId","extractorT2_0","cost",1400,"affordable",true)));
            if(path.startsWith("/scout/resource-approach"))return map("pathKnown",true,"x",1890,"y",1890,"tile",17000);
            return null;
        }
        public Map<String,Object> orderStrategy(String owner,String path){
            List<Long> ids=new ArrayList<Long>();String value=path.split(path.contains("unitIds=")?"unitIds=":"unitId=")[1].split("&")[0];
            for(String id:value.split(","))ids.add(Long.valueOf(id));
            require(gate.admit(gate.stamp(),owner,ids)==null,"strategy observes actor ownership and shared command gap");
            orders.add(path);if(path.startsWith("/command/invest"))upgradeOrders++;
            if(rejectOrders)return null;
            return map("status","queued","unitIds",ids,"requestId","r"+orders.size(),"targetX",490,"targetY",90);
        }
        public void emitStrategy(String kind,Map<String,Object> data){events.add(map("event",kind,"data",new LinkedHashMap<String,Object>(data)));}
        public void spendStrategy(String category,long cost,String type,long actor){}
        public void strategicAttack(Map<String,Object> receipt){}
        long events(String type){return events.stream().filter(e->type.equals(e.get("event"))).count();}
        void stamp(Map<String,Object> world){this.world=world;List<Long> ids=new ArrayList<Long>();for(Map<String,Object> u:BattleClient.units(world))ids.add(BattleClient.id(u));
            long now=(long)number(world,"gameTimeMs",0);gate.observe(new CommandArbiter.Stamp("session","team:0",now,now),ids);}
    }
    static void policy()throws Exception{
        Fake f=new Fake();StrategyDirector strategy=new StrategyDirector(f,f.gate);strategy.enable(map("strategyContractVersion",1),128);
        List<Map<String,Object>> all=new ArrayList<Map<String,Object>>(),force=new ArrayList<Map<String,Object>>();
        all.add(unit(1,"commandCenter",2990,3070));all.add(unit(2,"builder",2990,3000));all.add(unit(90,"landFactory",3050,3100));all.add(unit(91,"extractorT1",3090,3010));
        for(long id=10;id<50;id++){Map<String,Object> u=unit(id,"heavyTank",450,350);force.add(u);all.add(u);}
        Map<String,Object> world=map("sessionId","session","gameTimeMs",120000L,"map",map("tilesWide",180,"tilesHigh",180),
            "player",map("credits",15000),"ownUnits",all);
        Map<String,Object> enemy=map("id",230L,"type","seaFactory","building",true,"canAttack",false,"x",510,"y",70,"hp",1000);
        Map<String,Object> enemies=map("visibleEnemies",Arrays.asList(enemy),"enemyIntel",Collections.emptyList());
        Map<String,Object> scout=map("resources",Collections.emptyList(),"rememberedThreats",Collections.emptyList());
        f.stamp(world);strategy.observe(world,enemies,scout,force,0,900000,150,80,0,false);
        require(strategy.eligible(enemy,force).isEmpty(),"terrain-incompatible main force excluded before tactical selection");
        require(f.events("capability_need_created")==1,"excluded objective becomes capability demand");
        require(strategy.armyTarget()>40&&strategy.armyTarget()<=128,"supported map/task demand grows army target below hard safety cap");
        require(strategy.act(0,1)&&f.orders.get(0).contains("u_combatEngineer"),"capability demand buys native engineer before income upgrades");
        require(strategy.pending(90),"accepted capability production owns pending producer accounting");
        world.put("gameTimeMs",130000L);f.visible=false;f.approach="UNKNOWN";
        enemies.put("visibleEnemies",Collections.emptyList());f.stamp(world);strategy.observe(world,enemies,scout,force,0,800000,150,80,0,false);
        require(strategy.eligible(enemy,force).isEmpty(),"fog/time does not release prior terrain rejection");
        world.put("gameTimeMs",141000L);f.visible=true;enemies.put("visibleEnemies",Arrays.asList(enemy));
        Map<String,Object> reinforcement=unit(79,"heavyTank",450,350);all.add(reinforcement);force.add(reinforcement);f.stamp(world);
        strategy.observe(world,enemies,scout,force,0,795000,150,80,0,false);
        require(strategy.rejected(230)&&f.events("capability_need_released")==0,"new actor with UNKNOWN approach cannot discharge negative capability evidence");
        Map<String,Object> engineer=unit(80,"combatEngineer",410,350);all.add(engineer);world.put("gameTimeMs",145000L);
        enemies.put("visibleEnemies",Arrays.asList(enemy));f.visible=true;f.approach="BLOCKED_TERRAIN";f.stamp(world);
        strategy.observe(world,enemies,scout,force,0,790000,150,80,0,false);strategy.act(0,1);
        require(f.gate.reserved(80),"engineer leased before main/recon assignment");
        require(f.events("strategy_task_assigned")==1&&f.orders.get(f.orders.size()-1).contains("unitIds=80"),"engineer alone follows a known reachable firing position");
        require("ACTOR_OWNED_BY_OTHER_TASK".equals(f.gate.admit(f.gate.stamp(),CommandArbiter.DEFAULT_OWNER,Arrays.asList(80L))),"main army cannot steal a committed engineer");
        world.put("gameTimeMs",158000L);engineer.put("x",490);engineer.put("y",90);engineer.put("orderType","attackMove");engineer.put("orderX",490);engineer.put("orderY",90);
        enemy.put("hp",800);f.stamp(world);strategy.observe(world,enemies,scout,force,0,780000,150,80,0,false);
        require(f.events("strategy_response_progress")>0,"own observation records execution progress independently of receipt");
        world.put("gameTimeMs",170000L);enemies.put("enemyIntel",Arrays.asList(map("id",230L,"status","CLEARED")));enemies.put("visibleEnemies",Collections.emptyList());f.stamp(world);
        strategy.observe(world,enemies,scout,force,0,760000,150,80,0,false);strategy.act(0,1);
        require(f.events("capability_need_resolved")==1,"legal site clearance resolves capability debt");
        strategy.close();require(!f.gate.reserved(80)&&f.events("task_ownership_acquired")==f.events("task_ownership_released"),"lease release is balanced at controller end");
        Capacity high=capacity(148000,200,5,1,5,true,40,300000,128,false);
        require(high.total>40&&high.total<=96&&high.builders==2,"capacity needs income, task/map demand and construction backlog");
        require(capacity(32400,40,0,0,0,false,40,300000,128,false).builders==1,"cash is absent from builder target decision");
        require(capacity(148000,200,5,1,5,true,40,300000,32,true).total==32,"explicit fixed cap experiment stays fixed");
    }
    static void constructionCapacity()throws Exception{
        Fake f=new Fake();StrategyDirector strategy=new StrategyDirector(f,f.gate);strategy.enable(map("strategyContractVersion",1),3);
        Map<String,Object> factory=unit(90,"landFactory",3050,3100);factory.put("productionQueue",1);
        Map<String,Object> halfBuilt=unit(10,"heavyTank",2990,3100);halfBuilt.put("buildProgress",.5);
        Map<String,Object> builder=unit(3,"builder",2990,3000);
        List<Map<String,Object>> all=new ArrayList<Map<String,Object>>(Arrays.asList(unit(1,"commandCenter",2990,3070),unit(2,"builder",2990,3000),builder,
            factory,halfBuilt,unit(11,"heavyTank",2990,3100)));
        Map<String,Object> world=map("gameTimeMs",120000L,"map",map("tilesWide",180,"tilesHigh",180),"player",map("credits",100000),"ownUnits",all);
        Map<String,Object> empty=map("visibleEnemies",Collections.emptyList(),"enemyIntel",Collections.emptyList());
        Map<String,Object> scout=map("resources",Arrays.asList(map("tile",17000,"x",1890,"y",1890)),"rememberedThreats",Collections.emptyList());
        f.stamp(world);strategy.observe(world,empty,scout,Collections.emptyList(),0,900000,60,0,0,false);
        require(!strategy.safetyCapacityAvailable(),"unfinished armed units and paid queue consume hard safety capacity");
        require(strategy.act(0,1)&&f.orders.get(0).startsWith("/command/move?unitId=3"),"extra builder receives owned move toward known remote resource backlog");
        require(f.events("strategy_prospect_ordered")==1&&f.gate.reserved(3),"prospecting keeps the exclusive constructor lease");
        builder.put("x",1890);builder.put("y",1890);factory.put("productionQueue",0);world.put("gameTimeMs",130000L);f.stamp(world);
        strategy.observe(world,empty,scout,Collections.emptyList(),0,890000,60,0,0,false);
        require(strategy.safetyCapacityAvailable()&&f.events("strategy_prospect_observed")==1,"only fresh owned arrival and freed capacity complete prospect accounting");
        int existingCommitment=strategy.committedArmedForPolicy();
        strategy.noteUnobservedCombatSlots(3-existingCommitment);
        require(!strategy.safetyCapacityAvailable(),"paid product observation gap reserves capacity for capability and support paths");
        require(strategy.committedArmedForPolicy()==existingCommitment,"external paid slots stay separate from native bound to avoid duplicate counting");
        strategy.noteUnobservedCombatSlots(0);
        require(strategy.safetyCapacityAvailable(),"fulfilled product observation releases external capacity accounting");
        strategy.close();require(!f.gate.reserved(3),"remote construction worker releases at end");
    }
    static final class FundingFixture {
        final Fake f=new Fake();
        final StrategyDirector strategy=new StrategyDirector(f,f.gate);
        final List<Map<String,Object>> all=new ArrayList<Map<String,Object>>(),force=new ArrayList<Map<String,Object>>();
        final Map<String,Object> world,enemies,scout;
        double income=63.11,reserved=500;
        FundingFixture()throws Exception{
            strategy.enable(map("strategyContractVersion",1),128);
            all.add(unit(1,"commandCenter",2990,3070));all.add(unit(2,"builder",2990,3000));all.add(unit(90,"landFactory",3050,3100));
            for(long id=10;id<18;id++){Map<String,Object> tank=unit(id,"heavyTank",450,350);force.add(tank);all.add(tank);}
            world=map("sessionId","session","gameTimeMs",120000L,"map",map("tilesWide",400,"tilesHigh",370),"player",map("credits",1000),"ownUnits",all);
            enemies=map("visibleEnemies",Arrays.asList(map("id",230L,"type","seaFactory","building",true,"canAttack",false,"x",510,"y",70,"hp",1000)),
                "enemyIntel",Collections.emptyList());
            scout=map("resources",Collections.emptyList(),"rememberedThreats",Collections.emptyList());
            observe(120000);
        }
        void observe(long now)throws Exception{
            world.put("gameTimeMs",now);f.stamp(world);
            strategy.observe(world,enemies,scout,force,0,900000,income,60,reserved,false);
        }
        void credits(double value){BattleClient.obj(world.get("player")).put("credits",value);}
        void start()throws Exception{
            require(!strategy.act(reserved,1),"starting a funding commitment is not a native command");
            require(strategy.capabilityReserve()==3500&&f.events("strategy_capability_reserve_started")==1,"reserve uses full native engineer menu price");
        }
        String releaseReason(){
            String reason=null;for(Map<String,Object> event:f.events)if("strategy_capability_reserve_released".equals(event.get("event")))
                reason=String.valueOf(BattleClient.obj(event.get("data")).get("reason"));
            return reason;
        }
        void released(String expected){require(strategy.capabilityReserve()==0&&expected.equals(releaseReason()),"commitment released: "+expected);}
    }
    static void capabilityFunding()throws Exception{
        FundingFixture bought=new FundingFixture();bought.start();
        require(bought.strategy.wouldBreachCapabilityReserve(4000,1,500),"ordinary spending protects capability plus independent hard reserves");
        require(!bought.strategy.wouldBreachCapabilityReserve(4600,600,500),"spending above both reserves remains legal");
        Map<String,Object> wounded=unit(3,"builder",3100,3300);wounded.put("hp",100);bought.all.add(wounded);
        bought.credits(4000);bought.observe(121000);
        require(bought.strategy.act(500,1),"held purchase gets a legal opportunity before ordinary eight-second allocation");
        require(bought.f.orders.get(0).contains("u_combatEngineer"),"funded engineer purchase precedes an available worker return command");
        bought.released("PURCHASE_ACCEPTED");require(bought.strategy.pending(90),"accepted engineer retains normal pending accounting");
        require(!bought.strategy.wouldBreachCapabilityReserve(100,100,500),"released commitment no longer changes ordinary production");

        FundingFixture fog=new FundingFixture();fog.start();fog.f.visible=false;fog.enemies.put("visibleEnemies",Collections.emptyList());fog.observe(125000);
        require(fog.strategy.capabilityReserve()==3500,"future UNKNOWN contact preserves legal unresolved capability memory");
        fog.observe(209999);require(fog.strategy.capabilityReserve()==3500,"reserve remains one millisecond before deadline");
        fog.observe(210000);fog.released("TIMEOUT");
        fog.observe(269000);fog.strategy.act(500,1);require(fog.strategy.capabilityReserve()==0,"failed commitment cannot restart before sixty-second cooldown");
        fog.observe(278000);fog.strategy.act(500,1);require(fog.strategy.capabilityReserve()==3500,"unresolved legal memory may start a new bounded attempt after cooldown");
        fog.strategy.close();fog.released("CONTROLLER_ENDED");
        FundingFixture fundedTimeout=new FundingFixture();fundedTimeout.start();fundedTimeout.credits(10000);fundedTimeout.observe(210000);
        fundedTimeout.released("TIMEOUT");fundedTimeout.strategy.act(500,1);
        require(fundedTimeout.f.orders.isEmpty(),"timeout with sufficient funds cannot bypass cooldown through a direct purchase");
        fundedTimeout.observe(262000);fundedTimeout.strategy.act(500,1);
        require(fundedTimeout.f.orders.isEmpty(),"direct engineer purchase remains blocked within sixty seconds of timeout");
        fundedTimeout.observe(270000);
        require(fundedTimeout.strategy.act(500,1)&&fundedTimeout.f.orders.get(0).contains("u_combatEngineer"),"direct purchase resumes at cooldown boundary when ordinary allocation is ready");

        FundingFixture emergency=new FundingFixture();emergency.start();
        emergency.enemies.put("visibleEnemies",Arrays.asList(map("id",999L,"type","heavyTank","canAttack",true,"x",2990,"y",3070,"hp",1000)));
        emergency.observe(121000);emergency.released("HOME_EMERGENCY");
        FundingFixture cleared=new FundingFixture();cleared.start();cleared.enemies.put("visibleEnemies",Collections.emptyList());
        cleared.enemies.put("enemyIntel",Arrays.asList(map("id",230L,"status","CLEARED")));cleared.observe(121000);cleared.released("NEED_UNAVAILABLE");
        FundingFixture lost=new FundingFixture();lost.start();lost.all.remove(BattleClient.find(lost.world,90));lost.observe(121000);lost.released("PRODUCER_LOST");
        FundingFixture weak=new FundingFixture();weak.start();
        for(int i=0;i<3;i++){Map<String,Object> tank=weak.force.remove(0);weak.all.remove(tank);}
        weak.observe(121000);weak.released("FORCE_BELOW_MINIMUM");
        FundingFixture arrived=new FundingFixture();arrived.start();arrived.all.add(unit(80,"combatEngineer",410,350));arrived.observe(121000);arrived.released("ENGINEER_CAPACITY_SATISFIED");
        FundingFixture full=new FundingFixture();full.start();full.strategy.enable(map("strategyContractVersion",1),8);full.observe(121000);full.released("CAPACITY_UNAVAILABLE");
        FundingFixture served=new FundingFixture();
        List<Map<String,Object>> contacts=new ArrayList<Map<String,Object>>(BattleClient.list(served.enemies,"visibleEnemies"));
        Map<String,Object> second=new LinkedHashMap<String,Object>(contacts.get(0));second.put("id",231L);contacts.add(second);served.enemies.put("visibleEnemies",contacts);
        served.observe(120000);served.start();served.all.add(unit(80,"combatEngineer",410,350));served.observe(121000);served.strategy.act(500,1);
        served.observe(122000);served.released("NEED_ALREADY_SERVED");

        FundingFixture price=new FundingFixture();price.start();price.f.engineerCost=3600;price.credits(10000);price.observe(130000);price.strategy.act(500,1);price.released("PRICE_CHANGED");
        require(price.f.orders.isEmpty(),"price-change cancellation cannot directly buy the newly affordable action in the same allocation");
        FundingFixture missing=new FundingFixture();missing.start();missing.f.engineerOffered=false;missing.observe(121000);missing.strategy.act(500,1);missing.released("ACTION_UNAVAILABLE");
        FundingFixture menu=new FundingFixture();menu.start();menu.f.productionMenuAvailable=false;menu.observe(121000);menu.strategy.act(500,1);menu.released("MENU_UNAVAILABLE");
        FundingFixture rejected=new FundingFixture();rejected.start();rejected.f.rejectOrders=true;rejected.credits(4000);rejected.observe(121000);
        rejected.strategy.act(500,1);rejected.released("ORDER_REJECTED");require(!rejected.strategy.pending(90),"rejected order creates no pending purchase");

        FundingFixture hardReserve=new FundingFixture();hardReserve.start();hardReserve.credits(3999);hardReserve.observe(130000);
        require(!hardReserve.strategy.act(500,1)&&hardReserve.strategy.capabilityReserve()==3500,"funded order must still protect original hard reserve");
        hardReserve.all.add(unit(3,"builder",2990,3000));hardReserve.f.minePlan=true;hardReserve.observe(140000);hardReserve.strategy.act(500,1);
        require(hardReserve.f.orders.isEmpty(),"lower-priority strategic construction cannot spend committed capability funds");

        FundingFixture slow=new FundingFixture();slow.income=49;slow.observe(121000);slow.strategy.act(500,1);
        require(slow.strategy.capabilityReserve()==0,"a funding gap over sixty modeled game seconds cannot start a commitment");
        FundingFixture noIncome=new FundingFixture();noIncome.income=0;noIncome.observe(121000);noIncome.strategy.act(500,1);
        require(noIncome.strategy.capabilityReserve()==0,"zero income cannot justify funding");
        FundingFixture invalidCost=new FundingFixture();invalidCost.f.engineerCost=Double.NaN;invalidCost.strategy.act(500,1);
        require(invalidCost.strategy.capabilityReserve()==0,"invalid native menu price cannot justify funding");
    }
    static Map<String,Object> specialist(FundingFixture fixture,long id,String type,double x,double y)throws Exception{
        Map<String,Object> unit=unit(id,type,x,y);fixture.all.add(unit);fixture.observe(121000);return unit;
    }
    static long attackCount(Fake fake){return fake.orders.stream().filter(p->p.startsWith("/command/attack-move")).count();}
    static void specialistLifecycle()throws Exception{
        FundingFixture investigate=new FundingFixture();Map<String,Object> engineer=specialist(investigate,80,"combatEngineer",490,90);
        investigate.strategy.act(500,1);long orders=attackCount(investigate.f);
        investigate.f.visible=false;investigate.enemies.put("visibleEnemies",Collections.emptyList());
        investigate.observe(132000);investigate.strategy.act(500,1);
        require(investigate.f.events("strategy_investigation_started")==1,"arrival plus fresh lost contact starts a bounded site investigation");
        investigate.observe(146999);investigate.strategy.act(500,1);
        require(investigate.f.events("strategy_investigation_exhausted")==0&&attackCount(investigate.f)==orders,"waiting at last approach never repeats attack-move before deadline");
        investigate.observe(147000);investigate.strategy.act(500,1);
        require(investigate.f.events("strategy_investigation_exhausted")==1,"investigation expires exactly after fifteen game seconds");
        require(investigate.f.events("strategy_task_blocked")==0&&investigate.f.events("capability_need_resolved")==0,"lost contact investigation is neither failure nor clearance");
        engineer.put("x",2990);engineer.put("y",3070);investigate.observe(163000);investigate.strategy.act(500,1);
        require(attackCount(investigate.f)==orders&&investigate.strategy.capabilityReserve()==0,"same stale need cannot redispatch or buy a replacement while specialist remains committed");
        require(investigate.f.events("strategy_worker_commitment_released")==1,"safe home releases stale-site binding so the specialist can serve another need");
        investigate.f.visible=true;investigate.enemies.put("visibleEnemies",Arrays.asList(map("id",230L,"type","seaFactory","building",true,"canAttack",false,"x",510,"y",70,"hp",1000)));
        investigate.observe(175000);investigate.strategy.act(500,1);
        require(attackCount(investigate.f)>orders,"new legal visible evidence re-enables the bound specialist");
        investigate.enemies.put("enemyIntel",Arrays.asList(map("id",230L,"status","CLEARED")));investigate.enemies.put("visibleEnemies",Collections.emptyList());
        investigate.observe(177000);
        require(investigate.f.events("capability_need_resolved")==1,"legal clearance still resolves and releases the bound need");

        FundingFixture restored=new FundingFixture();specialist(restored,80,"combatEngineer",490,90);restored.strategy.act(500,1);
        restored.f.visible=false;restored.enemies.put("visibleEnemies",Collections.emptyList());restored.observe(132000);
        restored.f.visible=true;restored.observe(140000);restored.strategy.act(500,1);
        require(restored.f.events("strategy_investigation_contact_restored")==1&&restored.f.events("strategy_investigation_exhausted")==0,"fresh compatible contact during waiting resumes response without exhausting investigation");

        FundingFixture anotherNeed=new FundingFixture();Map<String,Object> reusable=specialist(anotherNeed,80,"combatEngineer",490,90);anotherNeed.strategy.act(500,1);
        anotherNeed.f.visible=false;anotherNeed.enemies.put("visibleEnemies",Collections.emptyList());anotherNeed.observe(132000);anotherNeed.observe(147000);anotherNeed.strategy.act(500,1);
        reusable.put("x",2990);reusable.put("y",3070);anotherNeed.f.visible=true;
        anotherNeed.enemies.put("visibleEnemies",Arrays.asList(map("id",231L,"type","seaFactory","building",true,"canAttack",false,"x",510,"y",70,"hp",1000)));
        anotherNeed.observe(163000);anotherNeed.strategy.act(500,1);
        require(anotherNeed.f.events.stream().anyMatch(e->"strategy_task_assigned".equals(e.get("event"))&&number(BattleClient.obj(e.get("data")),"targetId",-1)==231),
            "specialist safely returned from an exhausted stale site can dispatch to a different new need");

        FundingFixture recovery=new FundingFixture();Map<String,Object> worker=unit(3,"builder",2990,3070);worker.put("hp",100);recovery.all.add(worker);recovery.observe(121000);
        recovery.strategy.act(500,1);recovery.observe(180000);recovery.strategy.act(500,1);
        require(recovery.f.orders.stream().noneMatch(p->p.contains("unitId=3")),"low-health worker at home holds without repeated return commands");
        worker.put("x",1000);worker.put("y",1000);worker.put("orderType","move");worker.put("orderX",2990);worker.put("orderY",3070);
        recovery.observe(190000);recovery.strategy.act(500,1);
        require(recovery.f.orders.stream().noneMatch(p->p.contains("unitId=3")),"native move toward home is preserved without replacement");
        worker.put("orderType",null);recovery.observe(201000);recovery.strategy.act(500,1);
        require(recovery.f.orders.stream().anyMatch(p->p.startsWith("/command/move?unitId=3")),"far-away interrupted return receives a recovery command");

        FundingFixture role=new FundingFixture();role.credits(2000);role.enemies.put("visibleEnemies",Collections.emptyList());role.f.specialistApproach="UNKNOWN";role.f.minePlan=true;
        specialist(role,80,"combatEngineer",2990,3070);role.strategy.act(500,2);
        require(role.f.orders.stream().noneMatch(p->p.contains("unitId=80")),"idle capability engineer cannot be stolen for mine, prospect, factory or heavy-tank work");
        Map<String,Object> builder=unit(3,"builder",2990,3070);role.all.add(builder);role.observe(132000);role.strategy.act(500,2);
        require(role.f.orders.stream().anyMatch(p->p.startsWith("/command/build-extractor?unitId=3")),"additional ordinary builder retains the economic lane");

        FundingFixture purchase=new FundingFixture();purchase.credits(10000);purchase.strategy.act(500,1);
        require(purchase.f.events("strategy_purchase_committed")==1,"direct purchase records its exact selected capability need");
        Map<String,Object> second=new LinkedHashMap<String,Object>(BattleClient.list(purchase.enemies,"visibleEnemies").get(0));second.put("id",231L);
        purchase.enemies.put("visibleEnemies",Arrays.asList(second,BattleClient.list(purchase.enemies,"visibleEnemies").get(0)));
        Map<String,Object> bought=specialist(purchase,80,"combatEngineer",490,90);purchase.strategy.act(500,1);
        Map<String,Object> assigned=null;for(Map<String,Object> event:purchase.f.events)if("strategy_task_assigned".equals(event.get("event")))assigned=BattleClient.obj(event.get("data"));
        require(assigned!=null&&number(assigned,"targetId",-1)==230,"observed available engineer fulfils purchase need before another newly listed objective");
        bought.put("hp",100);purchase.observe(133000);purchase.strategy.act(500,1);bought.put("x",2990);bought.put("y",3070);purchase.observe(190000);purchase.strategy.act(500,1);
        long targetPurchases=purchase.f.events.stream().filter(e->"strategy_purchase_committed".equals(e.get("event"))&&number(BattleClient.obj(e.get("data")),"needId",-1)==230).count();
        require(targetPurchases==1,"returning bound specialist keeps the same need from ordering another engineer");

        FundingFixture losses=new FundingFixture();losses.credits(10000);losses.strategy.act(500,1);
        for(int attempt=0;attempt<3;attempt++){
            Map<String,Object> responder=unit(80+attempt,"combatEngineer",490,90);losses.all.add(responder);
            losses.observe(121000+attempt*24000);losses.strategy.act(500,1);
            losses.all.remove(responder);losses.observe(133000+attempt*24000);losses.strategy.act(500,1);
        }
        require(losses.f.events("strategy_task_lost")==3&&losses.f.events("strategy_purchase_committed")==3,
            "confirmed bound losses release commitments but respect the three-attempt need budget");
        FundingFixture missingUnit=new FundingFixture();missingUnit.credits(10000);missingUnit.strategy.act(500,1);
        Map<String,Object> producer=BattleClient.find(missingUnit.world,90);producer.put("productionQueue",1);missingUnit.observe(130000);
        producer.put("productionQueue",0);missingUnit.observe(140000);missingUnit.strategy.act(500,1);
        require(missingUnit.f.events("strategy_purchase_committed")==1&&missingUnit.strategy.pending(90),"empty queue alone does not release an unfulfilled unit commitment");
        missingUnit.observe(301001);missingUnit.strategy.act(500,1);
        require(missingUnit.f.events("strategy_purchase_unconfirmed")==1&&missingUnit.f.events("strategy_purchase_committed")==2,
            "missing-unit timeout releases its bounded commitment and permits a budgeted retry");

        FundingFixture support=new FundingFixture();support.credits(10000);support.f.specialistApproach="BLOCKED_TERRAIN";support.f.supportPlan=true;
        specialist(support,80,"combatEngineer",2990,3070);support.strategy.act(500,1);
        require(support.f.events("strategy_support_construction")==1,"an otherwise unable engineer retains explicit movement-gap jet construction");
        Map<String,Object> jet=unit(81,"amphibiousJet",3100,3100);jet.put("canAttack",true);support.all.add(jet);support.observe(133000);
        require(support.f.events("strategy_support_transferred")==1,"observed completed support responder inherits the bound need");
        require(support.f.events("strategy_worker_commitment_released")==1,"support handover explicitly frees the constructing engineer's need binding");
        support.f.specialistApproach="APPROACH_PATH_KNOWN";support.observe(141000);support.strategy.act(500,1);
        require(support.f.orders.stream().anyMatch(p->p.startsWith("/command/attack-move?unitIds=81")),"dedicated support responder executes its inherited movement-gap response");
    }
    public static void main(String[] args)throws Exception{
        geometry();policy();constructionCapacity();capabilityFunding();specialistLifecycle();
        require(BattleBudget.seconds(new String[]{"3600"})==3600,"long product window is independent of old 1800 limit");
        boolean refused=false;try{BattleBudget.seconds(new String[]{"21601"});}catch(IllegalArgumentException e){refused=true;}
        require(refused,"long experiments retain a bounded safety ceiling");
        System.out.println("STRATEGY_CONTRACT_TEST_OK checks="+checks);
    }
}
