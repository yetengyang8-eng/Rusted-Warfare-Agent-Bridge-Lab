package io.rwagent.client;

import java.util.*;
import static io.rwagent.client.StrategyDirector.map;

/** G4.1 collected proposals and actual registry transitions in synthetic own observations.
 * Static fixtures are path contracts, not native route completion or combat safety evidence. */
public final class ForceFormationHarness {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static final class Fixture extends ForceControllerHarness.Fixture {
        GeneralRegistry.GeneralId forming;
        Map<String,Object> staticOverrides=new LinkedHashMap<String,Object>();
        final Map<Long,Map<String,Object>> actorStaticOverrides=new HashMap<Long,Map<String,Object>>();
        final List<String> paths=new ArrayList<String>();
        Fixture(int count,int attached)throws Exception{
            super(count);registry.createForming(6,100,100);
            check(registry.generals().size()==1,"one General lifecycle is created from lawful FREE capacity");forming=registry.generals().get(0).id;
            observe(1000);
            for(long actor=30;actor<30+attached;actor++)check(registry.requestJoin(actor,forming),"observed FREE actor gets formal reservation");
            collect();
            for(ForceController.Proposal p:new ArrayList<ForceController.Proposal>(proposals))if("JOINING".equals(p.lane))p.accepted(map("status","queued","frame",frame));
            for(long actor=30;actor<30+attached;actor++){find(actor).put("x",100.);find(actor).put("y",100.);}
            observe(2000);
            for(long actor=30;actor<30+attached;actor++)check(registry.observeJoinPosition(actor,gate.stamp(),100,100,180),"later legal own position witnesses attachment at formation anchor");
            centroids();registry.refreshFormation();proposals.clear();paths.clear();
        }
        void observe(long time){super.observe(time);for(GeneralRegistry.UnitView unit:registry.units())registry.updateHealthRole(unit.unitId,unit.healthRole);}
        public Map<String,Object> read(String path,String event){
            paths.add(path);reads++;
            if(path.startsWith("/static-map/approach?")){
                String actor=path.substring(path.indexOf("unitId=")+7,path.indexOf("&tile="));
                Map<String,Object> plan=map("status","KNOWN","knowledgeId","static:k","unitId",Long.valueOf(actor),"movementType","LAND",
                    "targetTile",99,"approachTile",98,"x",800.,"y",500.,"waypointX",200.,"waypointY",150.,
                    "staticPathKnown",true,"staticOnly",true,"sessionId","s","frame",frame,"gameTimeMs",now,
                    "dynamicReachability","UNKNOWN","occupied","UNKNOWN","buildable","UNKNOWN","safe","UNKNOWN");
                plan.putAll(staticOverrides);Map<String,Object> actorOverride=actorStaticOverrides.get(Long.valueOf(actor));if(actorOverride!=null)plan.putAll(actorOverride);return plan;
            }
            return map("status","planned","pathKnown",frontierKnown,"targetTile",99,"targetX",1000,"targetY",300);
        }
        void support(){controller.setExpansionSupport(new ForceController.ExpansionSupport(99,"static:k"));}
    }
    private static void formingBlocksOffenseUntilActualStrength()throws Exception{
        for(int count=1;count<6;count++){
            Fixture f=new Fixture(count,count);f.frontierKnown=true;f.enemy(500,2000,100);f.collect();
            check(f.registry.general(f.forming).phase==GeneralRegistry.Phase.FORMING,"one through five attached actors retain FORMING");
            check(f.lane("GENERAL")==null&&f.paths.isEmpty(),"FORMING does not attack distant visible targets or ask frontier planner");
            f.enemyRows.clear();f.collect();check(f.lane("GENERAL")==null&&f.paths.isEmpty(),"FORMING remains without frontier even with a known native plan");
        }
        Fixture active=new Fixture(6,6);active.frontierKnown=true;active.collect();
        check(active.registry.general(active.forming).phase==GeneralRegistry.Phase.ACTIVE,"six healthy actual attachments activate same General ID");
        ForceController.Proposal frontier=active.lane("GENERAL");
        check(frontier!=null&&frontier.priority==30&&frontier.actors.size()==6&&frontier.path.startsWith("/command/attack-move"),"ACTIVE retains mature group frontier command");
        active.proposals.clear();active.enemy(501,2000,100);active.collect();
        check(active.lane("GENERAL")!=null&&"CURRENT_VISIBLE_TARGET".equals(active.lane("GENERAL").reason),"ACTIVE retains mature current-visible target command");
    }
    private static void smallFormationsCanRespondAndClear()throws Exception{
        for(int count=1;count<=3;count++){
            Fixture f=new Fixture(count,count);f.enemy(600,150,100);f.support();f.collect();
            ForceController.Proposal response=f.lane("LOCAL_RESPONSE");
            check(response!=null&&response.actors.size()==1&&response.priority==70,"small FORMING General can detach least sufficient home responder without mature floor");
            long actor=response.actors.get(0);check(f.registry.unit(actor).localResponseDetachedFromGeneral,"FORMING response uses actual General detach lifecycle");
            ForceController.Proposal screen=f.lane("FORMING_SCREEN");check(screen==null||!screen.actors.contains(actor),"local response actor cannot also screen under stale General ownership");
            f.enemyRows.clear();f.observe(3000);f.collect();
            check(f.registry.unit(actor).allocation==GeneralRegistry.Allocation.FREE&&f.registry.unit(actor).generalId==null,"current visibility clear returns detached member to FREE");
            check(!f.registry.requestJoin(actor,f.forming),"cleared member cannot rejoin in same frame");
            f.observe(4000);check(f.registry.requestJoin(actor,f.forming),"later frame can replenish surviving same FORMING lifecycle");
        }
        Fixture active=new Fixture(6,6);active.enemy(601,150,2001);active.collect();
        check(active.lane("LOCAL_RESPONSE")==null&&active.registry.general(active.forming).members.size()==6,"ACTIVE preserves mature three-member release floor");
    }
    private static void lawfulLowPriorityStaticScreen()throws Exception{
        Fixture f=new Fixture(3,3);f.support();f.find(32).put("type","hoverTank");f.find(32).put("movementType","HOVER");f.collect();
        ForceController.Proposal screen=f.lane("FORMING_SCREEN");
        check(screen!=null&&screen.owner.equals(f.forming.owner())&&screen.priority==20&&screen.actors.equals(Collections.singletonList(30L)),"static screen keeps General owner with independent actor proposal at low priority");
        check(f.proposals.size()==3,"every healthy attached actor gets its own static route proof, including a different movement type");
        for(ForceController.Proposal proposal:f.proposals)check(proposal.actors.size()==1&&proposal.path.contains("unitId="+proposal.actors.get(0))&&!proposal.path.contains("unitIds="),"each screen uses actual native single-actor move protocol");
        check(screen.path.startsWith("/command/move?")&&screen.path.contains("x=200.0&y=150.0"),"screen moves short static waypoint rather than attack or distant resource coordinates");
        check(screen.reason.contains("STATIC_ANCHOR_APPROACH_DYNAMIC_UNKNOWN")&&screen.reason.contains("knowledge=static:k")&&screen.reason.contains("anchor=30"),"screen reason preserves anchor-only static evidence and UNKNOWN dynamic safety");
        check(!f.registry.general(f.forming).goalKnown&&f.dispatched.isEmpty(),"collect does not dispatch or invent accepted General goal");
        f.flush();check(f.dispatched.size()==3&&f.dispatched.get(0).lane.equals("FORMING_SCREEN")&&!f.registry.general(f.forming).goalKnown,"actual single-actor receipts do not invent collective General goal progress");
        f.observe(3000);f.collect();check(f.lane("FORMING_SCREEN")==null,"accepted screen has lightweight existing eight-second command cooldown");
        f.observe(10000);f.controller.clearExpansionSupport();f.collect();check(f.lane("FORMING_SCREEN")==null,"no lawful expansion objective gives no optional screen");
        Fixture active=new Fixture(6,6);active.support();active.collect();check(active.lane("FORMING_SCREEN")==null,"ACTIVE follows mature General behavior without optional formation screen");
    }
    private static void staticSourceMismatchStaysUnknown()throws Exception{
        Object[][] cases={{"status","UNKNOWN"},{"knowledgeId","old:k"},{"unitId",999L},{"targetTile",100},{"sessionId","foreign"},
            {"frame",1L},{"gameTimeMs",1999L},{"staticOnly",false},{"staticPathKnown",false},{"waypointX",Double.NaN},{"waypointY",null}};
        for(Object[] mismatch:cases){Fixture f=new Fixture(1,1);f.support();f.staticOverrides.put((String)mismatch[0],mismatch[1]);f.collect();
            check(f.lane("FORMING_SCREEN")==null,"invalid static source/coordinate remains UNKNOWN: "+mismatch[0]);}
        Fixture recovery=new Fixture(1,1);recovery.registry.updateHealthRole(30,GeneralRegistry.HealthRole.RECOVERING);recovery.support();recovery.collect();
        check(recovery.paths.isEmpty()&&recovery.lane("FORMING_SCREEN")==null,"recovering attached actor does not take optional screen");
    }
    private static void emptyFormationCanReceiveActualJoin()throws Exception{
        Fixture f=new Fixture(1,0);check(f.registry.general(f.forming).members.isEmpty(),"forming roster can remain truly empty before witness");
        check(f.registry.requestJoin(30,f.forming),"empty FORMING General can reserve observed FREE actor");f.collect();ForceController.Proposal joining=f.lane("JOINING");
        check(joining!=null&&joining.path.contains("x=100.0&y=100.0")&&"MOVE_TO_FORMATION_OWN_ANCHOR".equals(joining.reason),"empty FORMING join heads toward lawful fixed own anchor");
        joining.accepted(map("status","queued","frame",f.frame+2));
        check(f.registry.unit(30).membership==GeneralRegistry.Membership.JOINING&&f.registry.unit(30).joinAcceptedFrame==f.frame+2,"actual later receipt remains JOINING and retains exact frame");
        check(!f.registry.observeJoinPosition(30,f.gate.stamp(),100,100,180),"early own position does not override later actual receipt frame");
    }
    private static void joiningPrecedesOptionalScreen()throws Exception{
        Fixture f=new Fixture(3,2);f.support();check(f.registry.requestJoin(32,f.forming),"remaining FREE actor reserves same formation");f.collect();
        check(f.lane("JOINING")!=null&&f.lane("FORMING_SCREEN")!=null&&f.dispatched.isEmpty(),"actual Host captures independent JOINING and screen before transport");
        while(f.scheduler.availableTokens()>1)check(f.gate.admit(f.gate.stamp(),f.forming.owner(),Collections.singletonList(30L))==null,"outside candidate batch consumes spare budget");
        f.flush();check(f.dispatched.size()==1&&f.dispatched.get(0).lane.equals("JOINING"),"last force-batch token goes to JOINING50 before optional screen20");
        check(f.registry.unit(32).joinAcceptedFrame==f.frame&&!f.registry.general(f.forming).goalKnown,"only actual dispatched join receipt updates commitment; rejected screen has no accepted goal");
    }
    private static void partialAcceptanceDoesNotWaitForGroup()throws Exception{
        Fixture f=new Fixture(3,3);f.support();f.actorStaticOverrides.put(31L,map("staticPathKnown",false));f.collect();
        check(f.proposals.size()==2&&f.proposals.get(0).actors.equals(Collections.singletonList(30L))&&f.proposals.get(1).actors.equals(Collections.singletonList(32L)),"one unknown static route does not suppress other lawful actor proposals");
        ForceController.Proposal accepted=f.proposals.get(0);accepted.accepted(map("status","queued","frame",f.frame));
        f.actorStaticOverrides.clear();f.observe(3000);f.collect();
        check(f.proposals.size()==2&&f.proposals.get(0).actors.equals(Collections.singletonList(31L))&&f.proposals.get(1).actors.equals(Collections.singletonList(32L)),"only actually accepted actor enters cooldown; unaccepted and newly lawful actors independently propose");
    }
    public static void main(String[] args)throws Exception{
        formingBlocksOffenseUntilActualStrength();smallFormationsCanRespondAndClear();lawfulLowPriorityStaticScreen();staticSourceMismatchStaysUnknown();emptyFormationCanReceiveActualJoin();joiningPrecedesOptionalScreen();partialAcceptanceDoesNotWaitForGroup();
        System.out.println("ForceFormationHarness checks="+checks+" PASS");
    }
}
