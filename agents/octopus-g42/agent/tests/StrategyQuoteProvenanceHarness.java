package io.rwagent.client;

import java.util.*;
import static io.rwagent.client.StrategyDirector.*;

/** Native quote provenance through real StrategyDirector, including interleaved unrelated reads.
 * Uses the existing legacy gate to prove metadata adds no new policy or funding behavior. */
public final class StrategyQuoteProvenanceHarness {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static final class Fixture implements StrategyDirector.Host {
        final StrategyContractHarness.Fake fake=new StrategyContractHarness.Fake();
        final StrategyDirector strategy=new StrategyDirector(this,fake.gate);
        final IdentityHashMap<Map<String,Object>,Map<String,Object>> sources=new IdentityHashMap<Map<String,Object>,Map<String,Object>>();
        final List<Map<String,Object>> contexts=new ArrayList<Map<String,Object>>();
        final List<Map<String,Object>> quoteInputs=new ArrayList<Map<String,Object>>();
        final List<Map<String,Object>> own=new ArrayList<Map<String,Object>>(),force=new ArrayList<Map<String,Object>>();
        final Map<String,Object> world,enemies,scout;
        Map<String,Object> latestSource,lastLookupSource;long readSequence;
        boolean sourceKnown=true,bogusStateSource,interleave=true,engineerScene,constructionScene;
        Fixture(String scene)throws Exception{
            own.add(StrategyContractHarness.unit(1,"commandCenter",2990,3070));own.add(StrategyContractHarness.unit(2,"builder",2990,3000));
            own.add(StrategyContractHarness.unit(90,"landFactory",3050,3100));own.add(StrategyContractHarness.unit(91,"extractorT1",3090,3010));
            for(long id=10;id<18;id++){Map<String,Object> tank=StrategyContractHarness.unit(id,"heavyTank",450,350);force.add(tank);own.add(tank);}
            engineerScene=scene.startsWith("engineer");constructionScene="construction".equals(scene);
            if(engineerScene){own.add(StrategyContractHarness.unit(80,"combatEngineer",2990,3070));fake.supportPlan=true;}
            if("expansion".equals(scene)||constructionScene){own.add(StrategyContractHarness.unit(3,"builder",2990,3070));fake.minePlan="expansion".equals(scene);}
            world=map("sessionId","session","gameTimeMs",120000L,"map",map("tilesWide",400,"tilesHigh",370),"player",map("credits",100000),"ownUnits",own);
            boolean target=scene.startsWith("provider")||engineerScene;
            enemies=map("visibleEnemies",target?Arrays.asList(map("id",230L,"type","seaFactory","building",true,"canAttack",false,"x",510,"y",70,"hp",1000)):Collections.emptyList(),"enemyIntel",Collections.emptyList());
            scout=map("resources",Collections.emptyList(),"rememberedThreats",Collections.emptyList());
            strategy.enable(map("strategyContractVersion",1),128);strategy.noteOrdinaryNativePrice(800);observe(120000);
        }
        void observe(long now)throws Exception{world.put("gameTimeMs",now);fake.stamp(world);strategy.observe(world,enemies,scout,force,0,1800000,100,60,500,false);}
        void credits(long amount){BattleClient.obj(world.get("player")).put("credits",amount);}
        public Map<String,Object> readStrategy(String path,String event){
            Map<String,Object> packet;
            if(path.equals("/economy/investments"))packet=map("units",Arrays.asList(map("id",91L,"type","extractorT1","product","extractorT2","queue",0,"actionId","extractorT2_0","cost",1400,"affordable",true)));
            else if(constructionScene&&path.startsWith("/economy/construction-plan"))packet=map("actionId","landFactory","cost",1100,"affordable",true,"x",3050,"y",3000);
            else packet=fake.readStrategy(path,event);
            if(engineerScene&&packet!=null&&path.startsWith("/combat/engagement"))for(Map<String,Object> actor:items(packet,"actors"))
                if(number(actor,"unitId",-1)==80)actor.put("compatibility","INCOMPATIBLE");
            if(packet!=null){latestSource=map("costSourceObservationId","quote:o:"+(++readSequence),"costSourceRequestPath",path);sources.put(packet,latestSource);}
            return packet;
        }
        public Map<String,Object> costSourceStrategy(Map<String,Object> packet){
            quoteInputs.add(packet);lastLookupSource=sources.get(packet);
            // The source registry must use this exact packet even when another GET becomes latest.
            if(interleave)readStrategy("/combat/production","interleaved_unrelated_native_menu");
            if(!sourceKnown)return Collections.emptyMap();
            if(bogusStateSource)return map("costSourceObservationId","state:o:1","costSourceRequestPath","/state?own=true");
            return lastLookupSource;
        }
        public Map<String,Object> orderStrategy(String owner,String path)throws Exception{return fake.orderStrategy(owner,path);}
        public Map<String,Object> orderStrategy(String owner,String path,Map<String,Object> context)throws Exception{
            if(context.containsKey("cost")){contexts.add(new LinkedHashMap<String,Object>(context));
                if(sourceKnown&&!bogusStateSource){check(lastLookupSource!=null,"quote source lookup uses registered top-level packet identity");
                    check(context.get("costSourceObservationId").equals(lastLookupSource.get("costSourceObservationId")),"unrelated later GET cannot overwrite cost source identity");
                    check(!context.get("costSourceObservationId").equals(latestSource.get("costSourceObservationId")),"cost source differs from newest unrelated menu sample");}}
            return fake.orderStrategy(owner,path);
        }
        public void emitStrategy(String event,Map<String,Object> data){fake.emitStrategy(event,data);}
        public void spendStrategy(String category,long cost,String product,long actor){}
        public void strategicAttack(Map<String,Object> receipt){}
        Map<String,Object> spending(){check(contexts.size()==1,"one legacy spending action admitted");return contexts.get(0);}
        void sourcePath(String expected){Map<String,Object> c=spending();check(expected.equals(c.get("costSourceRequestPath")),"spending preserves complete original quote request path: "+expected);
            check("VALIDATED_NATIVE_QUOTE_PACKET".equals(c.get("costSourceStatus")),"known native packet source marked valid");}
    }
    private static void directAndFundedProviders()throws Exception{
        Fixture direct=new Fixture("provider");check(direct.strategy.act(500,1),"existing direct provider purchase remains admitted");direct.sourcePath("/combat/production");
        check(number(direct.spending(),"cost",0)==3500&&direct.strategy.pending(90),"provider native price and paid pending contract retained");
        Fixture funding=new Fixture("provider-funding");funding.credits(1000);funding.observe(121000);
        check(!funding.strategy.act(500,1)&&funding.strategy.capabilityReserve()==3500,"funding uses original native price without issuing a command");
        String prior=(String)funding.latestSource.get("costSourceObservationId");funding.credits(4000);funding.observe(125000);
        check(funding.strategy.act(500,1),"refreshed funding menu admits original retained price");funding.sourcePath("/combat/production");
        check(!prior.equals(funding.spending().get("costSourceObservationId")),"paid funded purchase cites refreshed menu rather than old reserve quote");
    }
    private static void constructions()throws Exception{
        Fixture expansion=new Fixture("expansion");check(expansion.strategy.act(500,2),"existing extra builder expansion admitted");expansion.sourcePath("/expansion/plan?unitId=3");
        check(number(expansion.spending(),"cost",0)==700,"expansion quote keeps extractorCost native price");
        Fixture construction=new Fixture("construction");check(construction.strategy.act(500,2),"existing extra builder factory construction admitted");construction.sourcePath("/economy/construction-plan?unitId=3&type=landFactory");
        check(number(construction.spending(),"cost",0)==1100,"factory construction quote keeps native cost");
        Fixture engineer=new Fixture("engineer");check(engineer.strategy.act(500,1),"mature engineer support construction admitted");engineer.sourcePath("/economy/construction-plan?unitId=80&type=amphibiousJet");
        check(number(engineer.spending(),"cost",0)==2000,"support construction keeps native price");
        Fixture funded=new Fixture("engineer-funding");funded.credits(1000);funded.observe(121000);
        check(!funded.strategy.act(500,1)&&funded.strategy.capabilityReserve()==2000,"existing support funding reserve retained");
        funded.credits(2500);funded.observe(130000);check(funded.strategy.act(500,1),"support funding refreshes original plan before paid construction");
        funded.sourcePath("/economy/construction-plan?unitId=80&type=amphibiousJet");
        check(funded.quoteInputs.get(0).containsKey("actionId"),"support source is actual refreshed top-level plan, not copied worker supportQuote");
    }
    private static void investmentAndUnknown()throws Exception{
        Fixture mine=new Fixture("mine");mine.observe(165000);check(mine.strategy.act(500,1),"quiet mine legacy investment remains admitted");mine.sourcePath("/economy/investments");
        check(number(mine.spending(),"cost",0)==1400&&mine.strategy.pending(91),"investment native price and paid commitment remain unchanged");
        Fixture unknown=new Fixture("provider");unknown.sourceKnown=false;check(unknown.strategy.act(500,1),"unknown provenance metadata does not change legacy host gate");
        Map<String,Object> c=unknown.spending();check(c.get("costSourceObservationId")==null&&c.get("costSourceRequestPath")==null,"missing provenance is explicit unknown with no state fallback");
        check("UNKNOWN_PRICE_SOURCE".equals(c.get("costSourceStatus")),"missing native source cannot be represented as validated price evidence");
        Fixture state=new Fixture("provider");state.bogusStateSource=true;check(state.strategy.act(500,1),"source hardening preserves legacy native gate behavior");
        check(state.spending().get("costSourceObservationId")==null&&state.spending().get("costSourceRequestPath")==null,"state packet rejected as price quote provenance");
    }
    public static void main(String[] args)throws Exception{directAndFundedProviders();constructions();investmentAndUnknown();System.out.println("StrategyQuoteProvenanceHarness checks="+checks+" PASS");}
}
