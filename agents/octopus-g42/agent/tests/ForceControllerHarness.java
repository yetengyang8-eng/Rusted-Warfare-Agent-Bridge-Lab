package io.rwagent.client;

import java.util.*;
import static io.rwagent.client.StrategyDirector.map;

/** G4 deterministic proposals through the real scheduler. Fixture receipts are synthetic,
 * ownership/generation/budget are production classes; this is not native or win evidence. */
public final class ForceControllerHarness {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static Map<String,Object> unit(long id,String type,double x,double y){return map("id",id,"type",type,"hp",1000,"maxHp",1000,"buildProgress",1,"dead",false,"x",x,"y",y,"orderType",null);}
    static class Fixture implements ForceController.Host {
        final CommandArbiter gate=new CommandArbiter();final ExecutionScheduler scheduler=new ExecutionScheduler(gate);
        final GeneralRegistry registry=new GeneralRegistry(gate);final ForceController controller=new ForceController(registry,this);
        final List<Map<String,Object>> own=new ArrayList<Map<String,Object>>(),enemyRows=new ArrayList<Map<String,Object>>();
        final List<ForceController.Proposal> proposals=new ArrayList<ForceController.Proposal>();
        final List<Intent> dispatched=new ArrayList<Intent>();final Map<String,Object> state,enemies,scout;
        final GeneralRegistry.GeneralId a=new GeneralRegistry.GeneralId(1),b=new GeneralRegistry.GeneralId(2);
        boolean blocked,frontierKnown;int reads;long frame,now,intentSequence;
        Fixture()throws Exception{this(-1);}
        Fixture(int freeOnlyCount)throws Exception{
            own.add(unit(1,"commandCenter",100,100));List<Long> first=new ArrayList<Long>(),second=new ArrayList<Long>(),ordinary=new ArrayList<Long>();
            if(freeOnlyCount<0)for(long id=10;id<16;id++){own.add(unit(id,"heavyTank",130+(id-10)*3,100));first.add(id);ordinary.add(id);}
            if(freeOnlyCount<0)for(long id=20;id<26;id++){own.add(unit(id,"heavyTank",2000+(id-20)*3,100));second.add(id);ordinary.add(id);}
            for(long id=30;id<30+(freeOnlyCount<0?4:freeOnlyCount);id++){own.add(unit(id,"heavyTank",300+(id-30)*3,100));ordinary.add(id);}
            state=map("sessionId","s","frame",0L,"gameTimeMs",0L,"ownUnits",own,"player",map("credits",0));
            enemies=map("sessionId","s","gameTimeMs",0L,"visibleEnemies",enemyRows,"enemyIntel",Collections.emptyList());
            scout=map("resources",Collections.emptyList());observe(0);registry.bootstrap(freeOnlyCount<0?Arrays.asList(first,second):Collections.<Collection<Long>>emptyList(),ordinary);centroids();
        }
        void observe(long time){now=time;frame++;state.put("frame",frame);state.put("gameTimeMs",time);enemies.put("gameTimeMs",time);
            for(Map<String,Object> enemy:enemyRows)enemy.put("lastSeenGameTimeMs",time);
            List<Long> ids=new ArrayList<Long>();for(Map<String,Object> unit:own)ids.add(((Number)unit.get("id")).longValue());
            CommandArbiter.Stamp stamp=new CommandArbiter.Stamp("s","team:0",frame,time);gate.observe(stamp,ids);
            scheduler.beginObservation(stamp,"force:o:"+frame,0L,Collections.<Long,Integer>emptyMap(),0);centroids();proposals.clear();
        }
        void centroids(){for(GeneralRegistry.GeneralView general:registry.generals()){double x=0,y=0;int count=0;
            for(Long id:general.members){Map<String,Object> unit=find(id);if(unit!=null){x+=((Number)unit.get("x")).doubleValue();y+=((Number)unit.get("y")).doubleValue();count++;}}
            if(count>0)registry.updateGeneralCentroid(general.id,x/count,y/count);}}
        Map<String,Object> find(long id){for(Map<String,Object> unit:own)if(((Number)unit.get("id")).longValue()==id)return unit;return null;}
        void enemy(long id,double x,double hp){enemyRows.add(map("id",id,"type","tank","hp",hp,"x",x,"y",100,"canAttack",true,"building",false,"lastSeenGameTimeMs",now));}
        void collect()throws Exception{controller.collect(state,enemies,scout,100,100,now);}
        public Map<String,Object> read(String path,String event){reads++;return map("status","planned","pathKnown",frontierKnown,"targetTile",99,"targetX",1000,"targetY",300);}
        public List<Map<String,Object>> eligible(List<Map<String,Object>> actors,Map<String,Object> target,Map<String,Object> enemies){return blocked?Collections.<Map<String,Object>>emptyList():new ArrayList<Map<String,Object>>(actors);}
        public void collect(ForceController.Proposal proposal){proposals.add(proposal);}
        ForceController.Proposal lane(String lane){for(ForceController.Proposal proposal:proposals)if(lane.equals(proposal.lane))return proposal;return null;}
        List<ExecutionScheduler.Result> flush()throws Exception{
            final Map<String,ForceController.Proposal> callbacks=new HashMap<String,ForceController.Proposal>();List<Intent> intents=new ArrayList<Intent>();
            for(ForceController.Proposal p:proposals){String id="force:i:"+(++intentSequence);callbacks.put(id,p);
                intents.add(Intent.create(id,p.owner,gate.snapshotGenerations(p.owner,p.actors),p.actors,"MOVE",p.lane,p.priority,gate.stamp(),"force:o:"+frame,"/state",p.path,Intent.Commitment.none()));}
            List<ExecutionScheduler.Result> results=scheduler.dispatchBatch(intents,i->{
                for(Long actor:i.actorIds)check(gate.owns(i.owner,actor),"native dispatcher sees exact production registry owner");
                dispatched.add(i);return map("status","queued","requestId",i.intentId,"frame",frame);});
            for(ExecutionScheduler.Result r:results)if(r.accepted)callbacks.get(r.intent.intentId).accepted(r.receipt);return results;
        }
    }
    static void independentGeneralsAndReceipts()throws Exception{
        Fixture f=new Fixture();f.enemy(500,900,1000);f.enemy(501,2300,1000);f.collect();
        check(f.proposals.size()==2&&f.dispatched.isEmpty()&&f.scheduler.availableTokens()==1,"collect computes two independent General proposals without sending or consuming budget");
        check(f.proposals.get(0).owner.equals("general:1")&&f.proposals.get(1).owner.equals("general:2"),"General commands have separate real owners");
        check(f.proposals.get(0).actors.size()==6&&f.proposals.get(1).actors.size()==6,"attached roster members are each General's ordinary force");
        f.observe(3000);f.collect();check(f.proposals.size()==2,"unaccepted proposals do not start cooldown");
        f.flush();check(f.dispatched.size()==2,"accumulated budget dispatches both independent Generals");
        check(f.registry.general(f.a).goalKnown&&f.registry.general(f.a).targetEnemyId==500L,"General visible goal changes only on actual accepted receipt");
        f.observe(4000);f.collect();check(f.proposals.isEmpty(),"actual accepted receipt starts existing eight-second General cooldown");
        f.observe(11000);f.collect();check(f.proposals.size()==2,"each General independently becomes due after cooldown");
    }
    static void urgentPriorityAndJoining()throws Exception{
        Fixture f=new Fixture();f.observe(1000);check(f.registry.requestJoin(33,f.a)&&f.registry.beginJoining(33),"formal pending reservation starts distinct JOINING ownership");
        f.enemy(600,150,900);f.collect();ForceController.Proposal response=f.lane("LOCAL_RESPONSE"),joining=f.lane("JOINING");
        check(response!=null&&response.priority==70&&response.actors.equals(Arrays.asList(30L,31L)),"minimal FREE responders take precedence over nearer attached General members");
        check(!response.actors.contains(33L)&&joining!=null&&joining.priority==50,"JOINING actor is never recruited into LocalResponse");
        check(joining.path.contains("x="+f.registry.general(f.a).x)&&joining.owner.equals("join:1"),"joining heads to current General centroid under join owner");
        // Consume one spare token outside the candidate batch, leaving exactly one urgent choice.
        check(f.gate.admit(f.gate.stamp(),GeneralRegistry.FREE_OWNER,Collections.singletonList(32L))==null,"fixture leaves one token for actual force batch");
        f.flush();check(f.dispatched.size()==1&&f.dispatched.get(0).lane.equals("LOCAL_RESPONSE"),"last token goes to high-priority LocalResponse instead of low-priority General/JOINING");
        check(f.registry.unit(33).joinAcceptedFrame<0,"unexecuted JOINING candidate is never treated as accepted native movement");
        f.enemyRows.clear();f.observe(4000);f.collect();
        check(f.registry.unit(30).temporaryTask==GeneralRegistry.TemporaryTask.NONE&&f.registry.unit(30).allocation==GeneralRegistry.Allocation.FREE,"lost visible raid clears LocalResponse immediately to FREE");
        check(f.registry.unit(31).freeSinceFrame==f.frame,"response release carries current native frame to prevent same-frame formal recruitment");
        check(f.lane("LOCAL_RESPONSE")==null&&f.lane("JOINING")!=null,"cleared raid does not retain hidden target task while independent joining continues");
        f.flush();check(f.registry.unit(33).joinAcceptedFrame==f.frame&&f.registry.unit(33).membership==GeneralRegistry.Membership.JOINING,"accepted join move remains JOINING, never immediate attachment");
        GeneralRegistry.GeneralView general=f.registry.general(f.a);Map<String,Object> join=f.find(33);join.put("x",general.x);join.put("y",general.y);
        check(!f.registry.observeJoinPosition(33,f.gate.stamp(),general.x,general.y,180),"receipt-frame own position cannot prove later arrival");
        f.observe(5000);general=f.registry.general(f.a);check(f.registry.observeJoinPosition(33,f.gate.stamp(),general.x,general.y,180),"later legal own arrival attaches JOINING unit to General");
    }
    static void detachedResponseAndPending()throws Exception{
        Fixture f=new Fixture();for(long id=30;id<34;id++)f.registry.updateHealthRole(id,GeneralRegistry.HealthRole.RECOVERING);
        f.enemy(700,150,900);f.collect();ForceController.Proposal response=f.lane("LOCAL_RESPONSE");
        check(response!=null&&response.actors.size()==2&&f.registry.general(f.a).members.size()==4,"only necessary two General members temporarily detach when FREE pool is unavailable");
        for(Long id:response.actors)check(f.registry.unit(id).localResponseDetachedFromGeneral,"temporary detached source is explicit");
        List<Long> detached=response.actors;f.enemyRows.clear();f.observe(1000);f.collect();
        for(Long id:detached)check(f.registry.unit(id).allocation==GeneralRegistry.Allocation.FREE&&f.registry.unit(id).generalId==null,"finished temporary response does not return automatically to its original General");
        Fixture pending=new Fixture();pending.observe(1000);check(pending.registry.requestJoin(30,pending.a),"formal FREE reservation can remain pending before movement");
        pending.enemy(701,150,500);pending.collect();check(pending.lane("LOCAL_RESPONSE").actors.contains(30L),"PENDING_JOIN without JOINING remains eligible for urgent visible response");
        check(pending.registry.unit(30).allocation==GeneralRegistry.Allocation.PENDING_JOIN&&pending.registry.unit(30).temporaryTask==GeneralRegistry.TemporaryTask.LOCAL_RESPONSE,"temporary response and formal allocation remain independent dimensions");
    }
    static void lawfulFrontierAndUnknown()throws Exception{
        Fixture f=new Fixture();f.collect();check(f.proposals.isEmpty()&&f.reads==2,"no known target/path leaves ordinary Generals without invented hidden coordinates");
        f.frontierKnown=true;f.collect();check(f.proposals.size()==2&&f.proposals.get(0).reason.equals("NATIVE_KNOWN_ANCHOR_FRONTIER"),"legally known native army plan gives each General a frontier candidate");
        check(f.proposals.get(0).actors.size()==6&&f.proposals.get(1).actors.size()==6,"homogeneous General formation retains existing group frontier behavior");
        Fixture hidden=new Fixture();hidden.enemy(800,150,500);hidden.enemyRows.get(0).put("lastSeenGameTimeMs",-1L);hidden.collect();
        check(hidden.lane("LOCAL_RESPONSE")==null&&hidden.proposals.isEmpty(),"stale visible-array row cannot become current response or attack");
        Fixture blocked=new Fixture();blocked.enemy(801,150,500);blocked.blocked=true;blocked.collect();
        check(blocked.lane("LOCAL_RESPONSE")==null,"incompatible or unknown native engagement never creates response ownership");
        check(blocked.registry.unit(30).temporaryTask==GeneralRegistry.TemporaryTask.NONE,"negative target evidence preserves FREE lifecycle");
        Fixture large=new Fixture();for(int i=0;i<4;i++)large.enemy(900+i,150+i,500);large.collect();
        check(large.lane("LOCAL_RESPONSE")==null,"raid beyond existing small-response envelope does not expand into G5 scoring");
    }
    static void evidenceAndActualReceiptBoundaries()throws Exception{
        Fixture visible=new Fixture();visible.enemy(950,150,900);visible.collect();List<Long> responders=new ArrayList<Long>(visible.lane("LOCAL_RESPONSE").actors);
        visible.observe(50000);visible.enemyRows.get(0).put("x",3000.);for(int i=1;i<5;i++)visible.enemy(950+i,3000+i,500);visible.collect();
        check(visible.lane("LOCAL_RESPONSE")!=null,"still-current visible target is not cleared by old timer, range, or enlarged raid envelope");
        for(Long actor:responders)check(visible.registry.unit(actor).temporaryTask==GeneralRegistry.TemporaryTask.LOCAL_RESPONSE,"initial response actors stay assigned while current target remains visible");
        visible.enemyRows.clear();visible.enemies.remove("gameTimeMs");visible.proposals.clear();visible.collect();
        check(visible.registry.unit(responders.get(0)).temporaryTask==GeneralRegistry.TemporaryTask.LOCAL_RESPONSE,"missing combat time is UNKNOWN and cannot end visible response");
        visible.enemies.put("gameTimeMs",49999L);visible.proposals.clear();visible.collect();
        check(visible.registry.unit(responders.get(0)).temporaryTask==GeneralRegistry.TemporaryTask.LOCAL_RESPONSE,"stale combat packet cannot end response");
        visible.enemies.put("gameTimeMs",50000L);visible.enemies.put("sessionId","foreign");visible.proposals.clear();visible.collect();
        check(visible.registry.unit(responders.get(0)).temporaryTask==GeneralRegistry.TemporaryTask.LOCAL_RESPONSE,"foreign combat packet cannot end response");
        visible.enemies.put("sessionId","s");visible.proposals.clear();visible.collect();
        check(visible.registry.unit(responders.get(0)).temporaryTask==GeneralRegistry.TemporaryTask.NONE,"fresh same-session empty visible sample immediately ends response");
        Fixture join=new Fixture();join.observe(1000);check(join.registry.requestJoin(33,join.a)&&join.registry.beginJoining(33),"receipt-boundary actor begins JOINING");join.collect();ForceController.Proposal proposal=join.lane("JOINING");
        proposal.accepted(map("status","queued"));check(join.registry.unit(33).joinAcceptedFrame<0,"missing actual receipt frame cannot be replaced with state frame");
        proposal.accepted(map("status","queued","frame",join.frame-1));check(join.registry.unit(33).joinAcceptedFrame<0,"older actual receipt frame cannot confirm joining command");
        long lateFrame=join.frame+3;proposal.accepted(map("status","queued","frame",lateFrame));
        check(join.registry.unit(33).joinAcceptedFrame==lateFrame,"later native receipt frame is retained exactly for arrival witness");
        join.registry.updateHealthRole(33,GeneralRegistry.HealthRole.RECOVERING);join.observe(10000);join.collect();
        check(join.lane("JOINING")!=null,"already JOINING actor keeps autonomous movement even when health role changes");
        final int[] accepted={0};ForceController.Proposal guarded=new ForceController.Proposal("force:free",Arrays.asList(32L),"/command/move?unitId=32&x=100&y=100","FREE",10,"TEST",r->accepted[0]++);
        boolean refused=false;try{guarded.accepted(map("status","observed","frame",1));}catch(IllegalArgumentException expected){refused=true;}
        check(refused&&accepted[0]==0,"arbitrary non-receipt Map cannot invoke accepted callback");
        guarded.accepted(map("status","queued","frame",1));check(accepted[0]==1,"accepted native receipt status invokes callback once per host delivery");
    }
    static void freePoolHasNoFixedReserveFloor()throws Exception{
        Fixture one=new Fixture(1);one.enemy(980,150,100);one.collect();ForceController.Proposal response=one.lane("LOCAL_RESPONSE");
        check(one.registry.generals().isEmpty()&&response!=null&&response.actors.equals(Collections.singletonList(30L)),"one strong FREE actor can answer one small visible enemy without a General or fixed reserve floor");
        Fixture two=new Fixture(2);two.enemy(981,150,100);two.collect();
        check(two.lane("LOCAL_RESPONSE")!=null&&two.lane("LOCAL_RESPONSE").actors.size()==1,"two FREE actors recruit only the one necessary to cover native HP envelope");
        check(two.registry.unit(31).temporaryTask==GeneralRegistry.TemporaryTask.NONE,"unneeded FREE actor remains unassigned reserve without a configured minimum");
        Fixture weak=new Fixture(1);weak.enemy(982,150,900);weak.collect();
        check(weak.lane("LOCAL_RESPONSE")==null,"removing reserve floor does not relax existing visible HP coverage requirement");
    }
    public static void main(String[] args)throws Exception{independentGeneralsAndReceipts();urgentPriorityAndJoining();detachedResponseAndPending();lawfulFrontierAndUnknown();evidenceAndActualReceiptBoundaries();freePoolHasNoFixedReserveFloor();System.out.println("ForceControllerHarness checks="+checks+" PASS");}
}
