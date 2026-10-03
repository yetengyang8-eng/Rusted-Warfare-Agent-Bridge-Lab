package io.rwagent.client;

import java.util.*;
import static io.rwagent.client.StrategyDirector.map;

/** Focused legal own-state evidence for the cross-observation credit ledger. */
public final class NativeCreditWitnessHarness {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static Map<String,Object> unit(long id,String type,int queue,int tier,double progress,double x,double y){
        return map("id",id,"type",type,"productionQueue",queue,"techLevel",tier,"buildProgress",progress,"x",x,"y",y,"dead",false);
    }
    private static final class Fixture {
        final CommandArbiter arbiter=new CommandArbiter();
        final ExecutionScheduler scheduler=new ExecutionScheduler(arbiter);
        final NativeCreditWitness witness=new NativeCreditWitness();
        final List<Map<String,Object>> units=new ArrayList<Map<String,Object>>();
        final Map<String,Object> state=map("sessionId","s","status","running","frame",10L,"gameTimeMs",2500L,"player",map("teamId",0,"credits",1600L),"ownUnits",units);
        Fixture(){units.add(unit(1,"landFactory",0,1,1,100,200));units.add(unit(2,"builder",-1,1,1,100,200));
            arbiter.observe(new CommandArbiter.Stamp("s","team:0",0,0),Arrays.asList(1L,2L));
            arbiter.observe(stamp(),Arrays.asList(1L,2L));scheduler.beginObservation(stamp(),"o:10",1600L,Collections.<Long,Integer>emptyMap(),10);}
        CommandArbiter.Stamp stamp(){return new CommandArbiter.Stamp("s","team:0",((Number)state.get("frame")).longValue(),((Number)state.get("gameTimeMs")).longValue());}
        void hold(String id,long actor,String kind,String path,String product,boolean unknown){
            Intent intent=new Intent(id,CommandArbiter.DEFAULT_OWNER,arbiter.snapshotGenerations(CommandArbiter.DEFAULT_OWNER,Arrays.asList(actor)),Arrays.asList(actor),kind,"TEST",1,stamp(),"quote:o","/combat/production",path,Intent.Commitment.spending(800L,Collections.<Long,Integer>emptyMap(),0),"quote:o","/combat/production");
            Map<String,Object> receipt=unknown?null:map("status","queued","frame",20L);
            ExecutionScheduler.Result result=scheduler.dispatch(intent,i->receipt);
            check(result.commitmentApplied,"accepted or transport unknown retains credit obligation");witness.accepted(intent,result.receipt,map("product",product),state);
        }
        void queue(boolean unknown){hold("q",1,"/command/queue","/command/queue?unitId=1&actionId=tank","tank",unknown);}
        void construction(String path){hold("c",2,"/command/construct",path,"landFactory",false);}
        List<Map<String,Object>> observe(long frame,long time){state.put("frame",frame);state.put("gameTimeMs",time);return witness.observe(stamp(),"o:"+frame,state,scheduler);}
        void noEffect(String why){check(observe(21,3500).isEmpty(),why);check(scheduler.unsettledCreditTotal()==800,"unproven effect retains exact obligation");}
        void build(){units.get(1).put("orderType","build");units.get(1).put("orderX",100);units.get(1).put("orderY",200);}
        void candidate(long id,double progress){units.add(unit(id,"landFactory",-1,1,progress,100,200));}
        void refresh(){arbiter.observe(stamp(),Arrays.asList(1L,2L));scheduler.beginObservation(stamp(),"o:refresh",1600L,Collections.<Long,Integer>emptyMap(),10);}
    }
    private static void queuesAndTime(){
        Fixture f=new Fixture();f.queue(false);check(f.scheduler.effectiveCredits()==800,"receipt debits local ledger before native cash");
        f.units.get(0).put("productionQueue",1);check(f.observe(20,3500).isEmpty(),"receipt frame is not a later native effect");
        check(f.observe(21,2000).isEmpty(),"earlier game clock cannot settle");
        List<Map<String,Object>> rows=f.observe(21,3500);check(rows.size()==1,"later empty-to-nonempty queue settles exact intent");
        check("NATIVE_QUEUE_NONEMPTY_NOT_PRODUCT_READY".equals(rows.get(0).get("witnessKind")),"queue witness is not ready product");
        check(Boolean.FALSE.equals(rows.get(0).get("productReady")),"settlement explicitly rejects readiness claim");
        check(f.scheduler.unsettledCreditCount()==0&&f.scheduler.effectiveCredits()==800,"witness removes hold without current-batch refund");
        check(f.observe(22,3500).isEmpty(),"repeated native effect does not settle twice");f.refresh();check(f.scheduler.effectiveCredits()==1600,"next legal begin refreshes settled funds");
        Fixture sameTime=new Fixture();sameTime.queue(false);sameTime.units.get(0).put("productionQueue",1);
        check(sameTime.observe(21,2500).size()==1,"later frame at same game time is legal native evidence");sameTime.refresh();check(sameTime.scheduler.effectiveCredits()==800,"same-time begin cannot refill settled current batch");
        Fixture unknown=new Fixture();unknown.queue(true);unknown.noEffect("transport unknown receipt and elapsed clock do not settle");
        unknown.units.get(0).put("productionQueue",1);check(unknown.observe(22,4000).size()==1,"unknown transport may settle only by later native queue effect");
        Fixture wallet=new Fixture();wallet.queue(false);BattleClient.obj(wallet.state.get("player")).put("credits",0L);wallet.noEffect("wallet drop is not attributable native effect");
        Fixture missingQueue=new Fixture();missingQueue.units.get(0).remove("productionQueue");missingQueue.queue(false);missingQueue.units.get(0).put("productionQueue",1);missingQueue.noEffect("unknown baseline queue is not empty baseline");
        Fixture busy=new Fixture();busy.units.get(0).put("productionQueue",1);busy.queue(false);busy.units.get(0).put("productionQueue",2);busy.noEffect("already nonempty queue increase has no unique lineage");
        Fixture vanished=new Fixture();vanished.queue(false);vanished.units.remove(0);vanished.noEffect("missing actor retains hold");
        Fixture dead=new Fixture();dead.queue(false);dead.units.get(0).put("productionQueue",1);dead.units.get(0).put("dead",true);dead.noEffect("dead actor is not live queue witness");
    }
    private static void identity(){
        for(String field:Arrays.asList("sessionId","player","frame","gameTimeMs","status","networked","replay")){
            Fixture f=new Fixture();f.queue(false);f.units.get(0).put("productionQueue",1);f.state.put("frame",21L);f.state.put("gameTimeMs",3500L);
            CommandArbiter.Stamp legal=f.stamp();
            f.state.put(field,field.equals("sessionId")?"foreign":field.equals("player")?map("teamId",1):field.equals("status")?"ended":field.equals("frame")?999L:field.equals("gameTimeMs")?9999L:true);
            check(f.witness.observe(legal,"o:legal",f.state,f.scheduler).isEmpty(),"foreign or illegal own-state field cannot settle: "+field);
            check(f.scheduler.unsettledCreditCount()==1,"illegal packet retains obligation: "+field);
        }
        Fixture f=new Fixture();f.queue(false);f.units.get(0).put("productionQueue",1);f.state.put("frame",21L);
        check(f.witness.observe(new CommandArbiter.Stamp("s","team:1",21,2500),"o:foreign",f.state,f.scheduler).isEmpty(),"foreign player stamp cannot settle");
        check(f.witness.observe(new CommandArbiter.Stamp("foreign","team:0",21,2500),"o:foreign",f.state,f.scheduler).isEmpty(),"foreign session stamp cannot settle");
        check(f.witness.observe(f.stamp()," ",f.state,f.scheduler).isEmpty(),"missing witness observation identity retains hold");
    }
    private static void investments(){
        Fixture f=new Fixture();f.hold("i",1,"/command/invest","/command/invest?unitId=1&actionId=tier2","landFactory",false);f.units.get(0).put("techLevel",2);
        check("NATIVE_TIER_ADVANCED".equals(f.observe(21,3500).get(0).get("witnessKind")),"native later tier increase settles investment");
        Fixture unknown=new Fixture();unknown.units.get(0).remove("techLevel");unknown.hold("i",1,"/command/invest","/command/invest?unitId=1&actionId=tier2","landFactory",false);unknown.units.get(0).put("techLevel",2);unknown.noEffect("unknown baseline tier cannot prove increase");
        Fixture missing=new Fixture();missing.units.remove(0);missing.hold("i",1,"/command/invest","/command/invest?unitId=1&actionId=tier2","landFactory",false);missing.units.add(unit(1,"landFactory",1,2,1,100,200));missing.noEffect("actor absent in baseline cannot prove native effect");
        Fixture lower=new Fixture();lower.hold("i",1,"/command/invest","/command/invest?unitId=1&actionId=tier2","landFactory",false);lower.units.get(0).put("techLevel",0);lower.noEffect("tier decrease is not investment settlement");
    }
    private static void constructions(){
        String path="/command/construct?unitId=2&actionId=factory&x=100&y=200";
        Fixture f=new Fixture();f.construction(path);f.build();f.candidate(3,.1);List<Map<String,Object>> rows=f.observe(21,3500);
        check(rows.size()==1&&"NATIVE_CONSTRUCTION_SITE_EFFECT_NOT_PRODUCER_LINEAGE".equals(rows.get(0).get("witnessKind")),"unique new product in site envelope with builder build order settles without lineage claim");
        Fixture progress=new Fixture();progress.candidate(3,.1);progress.construction(path);progress.build();progress.units.get(2).put("buildProgress",.2);check(progress.observe(21,3500).size()==1,"unique baseline candidate progress increase settles construction");
        Fixture unchanged=new Fixture();unchanged.candidate(3,.1);unchanged.construction(path);unchanged.build();unchanged.noEffect("unchanged candidate supplies no effect");
        Fixture noOrder=new Fixture();noOrder.construction(path);noOrder.candidate(3,.1);noOrder.noEffect("new candidate without builder build order cannot settle");
        Fixture originalBridgeShape=new Fixture();originalBridgeShape.construction(path);originalBridgeShape.units.get(1).put("orderType","build");originalBridgeShape.candidate(3,.1);
        originalBridgeShape.noEffect("original native build packet without order coordinates retains obligation");
        originalBridgeShape.build();check(originalBridgeShape.observe(22,4000).size()==1,"fresh native-shaped build coordinates settle later unique site effect");
        Fixture wrongSite=new Fixture();wrongSite.construction(path);wrongSite.build();wrongSite.units.get(1).put("orderX",1000);wrongSite.candidate(3,.1);wrongSite.noEffect("builder working another site cannot prove held construction");
        Fixture ambiguous=new Fixture();ambiguous.construction(path);ambiguous.build();ambiguous.candidate(3,.1);ambiguous.candidate(4,.1);ambiguous.noEffect("multiple new product candidates retain hold");
        Fixture twoBuilders=new Fixture();twoBuilders.construction(path);twoBuilders.hold("c2",1,"/command/construct","/command/construct?unitId=1&actionId=factory&x=100&y=200","landFactory",false);twoBuilders.build();twoBuilders.units.get(0).putAll(map("orderType","build","orderX",100,"orderY",200));twoBuilders.candidate(3,.1);
        check(twoBuilders.observe(21,3500).isEmpty()&&twoBuilders.scheduler.unsettledCreditCount()==2,"one site effect cannot settle overlapping intents from different actors");
        Fixture twoActorHolds=new Fixture();twoActorHolds.queue(false);twoActorHolds.state.put("frame",11L);twoActorHolds.state.put("gameTimeMs",3500L);twoActorHolds.refresh();twoActorHolds.hold("q2",1,"/command/queue","/command/queue?unitId=1&actionId=tank","tank",false);twoActorHolds.units.get(0).put("productionQueue",1);
        check(twoActorHolds.observe(21,4000).isEmpty()&&twoActorHolds.scheduler.unsettledCreditCount()==2,"two unresolved actor queue orders remain ambiguous independent of iteration order");
        for(String bad:Arrays.asList("&x=100","&x=NaN&y=200","&x=100&y=Infinity","&x=garbage&y=200")){
            Fixture badSite=new Fixture();badSite.construction("/command/construct?unitId=2&actionId=factory"+bad);badSite.build();badSite.candidate(3,.1);badSite.noEffect("incomplete or invalid site remains unknown: "+bad);}
        Fixture unknownProgress=new Fixture();unknownProgress.construction(path);unknownProgress.build();unknownProgress.candidate(3,-1);unknownProgress.noEffect("new candidate with missing progress does not fabricate construction effect");
    }
    public static void main(String[] args){queuesAndTime();identity();investments();constructions();System.out.println("NativeCreditWitnessHarness checks="+checks+" PASS");}
}
