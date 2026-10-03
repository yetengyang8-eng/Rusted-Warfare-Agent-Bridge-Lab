import com.corrodinggames.rts.game.units.*;
import io.rwagent.bootstrap.RuntimeBridge;
import io.rwagent.client.CommandArbiter;
import io.rwagent.client.GeneralRegistry;
import io.rwagent.client.Intent;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Focused G4 runtime fixture with the original engine and real bridge HTTP.
 * Explicit native clock/positions and producer progress are recorded as fixture
 * controls; they do not constitute a natural match, route or tactical outcome. */
public final class G4ForceNativeHarness {
    static int checks;static G3NativeAcceptanceHarness.Client client;
    static com.corrodinggames.rts.game.i engine;static Path out;
    static void require(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static Map<String,Object> map(Object... pairs){return G3NativeAcceptanceHarness.map(pairs);}
    static void log(String event,Object data)throws Exception{G3NativeAcceptanceHarness.record(event,data);}
    static Object value(Object target,String name)throws Exception{return G3NativeAcceptanceHarness.value(target,name);}
    static void set(Object target,String name,Object value)throws Exception{G3NativeAcceptanceHarness.set(target,name,value);}
    static Object call(Object target,String name,Class<?>[] types,Object... args)throws Exception{return G3NativeAcceptanceHarness.invoke(target,name,types,args);}
    static am nativeUnit(long id){for(Object raw:am.bE){am unit=(am)raw;if(unit.eh==id)return unit;}throw new AssertionError("missing native actor "+id);}
    static List<Map<String,Object>> own(){return G3NativeAcceptanceHarness.items(client.state,"ownUnits");}
    static void observeForces()throws Exception{call(client.battle,"observeG4Forces",new Class<?>[]{Map.class,Map.class},client.state,client.enemies);}
    static void collectForces()throws Exception{call(client.battle,"collectG4Forces",new Class<?>[]{Map.class,Map.class,Map.class},client.state,client.enemies,client.scout);}
    static void flushForces()throws Exception{call(client.battle,"flushG4Forces",new Class<?>[0]);}
    static void advance(long gameMs)throws Exception{client.advance(gameMs,false);observeForces();}
    static void bootstrap()throws Exception{
        System.setProperty("rwagent.port",String.valueOf(G3NativeAcceptanceHarness.PORT));System.setProperty("rwagent.g3Execution","true");System.setProperty("rwagent.g4Forces","true");
        System.setProperty("rwagent.executionBurst","4");System.setProperty("rwagent.g1Trace","true");System.setProperty("rwagent.g2WorldState","true");System.setProperty("rwagent.additionalDiagnostics","true");
        Method init=TerrainNativeCostHarness.class.getDeclaredMethod("initialize",String.class);init.setAccessible(true);engine=(com.corrodinggames.rts.game.i)init.invoke(null,"maps/skirmish/[p2]Big Island (2p).tmx");
        G3NativeAcceptanceHarness.engine=engine;G3NativeAcceptanceHarness.out=out;G3NativeAcceptanceHarness.fixture=Files.newBufferedWriter(out.resolve("g4-force-fixture.jsonl"),StandardCharsets.UTF_8);
        for(byte[] column:engine.bs.N)Arrays.fill(column,(byte)0);
        Thread pump=new Thread(()->{while(true){Runnable r=(Runnable)engine.k.poll();if(r!=null)r.run();try{Thread.sleep(1);}catch(InterruptedException e){return;}}},"g4-native-task-pump");pump.setDaemon(true);pump.start();RuntimeBridge.start(engine,G3NativeAcceptanceHarness.PORT,true);
        log("scope",map("evidence","E2_NATIVE_FIXTURE_WITH_REAL_HTTP","naturalMatch","NO_NATURAL_MATCH","desktopTouched",false,"g4Forces",true,"policyEntry","ACTUAL_BATTLECLIENT_OBSERVE_COLLECT_FLUSH_ADAPTERS","positionAndClockControls","EXPLICIT_FIXTURE_NOT_NATURAL_PROGRESS"));
    }
    static float[] distantDry(float x,float y){
        for(int tx=8;tx<engine.bL.C-8;tx++)for(int ty=8;ty<engine.bL.D-8;ty++){
            float px=tx*engine.bL.n+10,py=ty*engine.bL.o+10;if(Math.hypot(px-x,py-y)<1050)continue;
            boolean dry=true;for(int dx=-5;dx<=5&&dry;dx++)for(int dy=-5;dy<=5;dy++)if(engine.bU.a(ao.b).d[(tx+dx)*engine.bL.D+ty+dy]<0){dry=false;break;}
            if(dry)return new float[]{px,py};
        }throw new AssertionError("second separated real dry native patch");
    }
    static void fixturePosition(long actor,double x,double y)throws Exception{am nativeActor=nativeUnit(actor);nativeActor.eo=(float)x;nativeActor.ep=(float)y;log("fixture_position",map("actor",actor,"x",x,"y",y,"basis","EXPLICIT_NATIVE_POSITION_NOT_NATURAL_ARRIVAL"));}
    static List<Map<String,Object>> rows(String event)throws Exception{
        List<Map<String,Object>> result=new ArrayList<Map<String,Object>>();for(String line:Files.readAllLines(out.resolve("g4-forces-battle.jsonl"),StandardCharsets.UTF_8)){
            Map<String,Object> row=G3NativeAcceptanceHarness.packet(io.rwagent.client.Json.parse(line));if(event.equals(row.get("event")))result.add(G3NativeAcceptanceHarness.packet(row.get("data")));
        }return result;
    }
    static GeneralRegistry registry()throws Exception{return (GeneralRegistry)value(client.battle,"generals");}
    static Map<String,Object> forceSnapshot()throws Exception{
        List<Object> generals=new ArrayList<Object>(),units=new ArrayList<Object>();for(GeneralRegistry.GeneralView g:registry().generals())generals.add(g.metadata());for(GeneralRegistry.UnitView u:registry().units())units.add(u.metadata());
        return map("generals",generals,"units",units,"ledger",client.ledger());
    }
    static List<Map<String,Object>> proposalMetadata(List<?> proposals)throws Exception{
        List<Map<String,Object>> result=new ArrayList<Map<String,Object>>();for(Object proposal:proposals)result.add(((Intent)value(proposal,"intent")).metadata());return result;
    }
    static com.corrodinggames.rts.game.n enemyTeam;
    static am enemy(String type,long id,double x,double y)throws Exception{return G3NativeAcceptanceHarness.unit(type,id,enemyTeam,(float)x,(float)y);}
    static void run()throws Exception{
        G3NativeAcceptanceHarness.base(12,1,40000);float[] first=G3NativeAcceptanceHarness.dry(),second=distantDry(first[0],first[1]);
        for(int i=0;i<6;i++)fixturePosition(100+i,first[0]+i*12,first[1]+150);
        for(int i=0;i<6;i++)fixturePosition(106+i,second[0]+i*12,second[1]);
        enemyTeam=new com.corrodinggames.rts.game.e(1,false);enemyTeam.r=1;
        am target1=enemy("commandCenter",230,first[0]+150,first[1]+150),target2=enemy("commandCenter",231,second[0]+150,second[1]);
        client=new G3NativeAcceptanceHarness.Client("g4-forces",40,false);observeForces();
        GeneralRegistry registry=registry();require(registry.generals().size()==2,"two observed separated native formations seed two Generals");
        GeneralRegistry.GeneralView a=registry.generals().get(0),b=registry.generals().get(1);
        require(!a.owner.equals(b.owner)&&a.owner.startsWith("general:")&&b.owner.startsWith("general:"),"Generals have distinct real canonical owners");
        require(a.desiredStrength==6&&b.desiredStrength==6,"fixed desiredStrength equals initial native seed strength");
        for(Long id:a.members)require(client.arbiter.owns(a.owner,id),"first General owns actual arbiter member");
        for(Long id:b.members)require(client.arbiter.owns(b.owner,id),"second General owns actual arbiter member");
        log("two_generals_bootstrap",forceSnapshot());advance(2500);collectForces();int before=engine.cf.b.size();require(before==0,"force collection does not dispatch before flush");flushForces();
        List<Map<String,Object>> commands=G3NativeAcceptanceHarness.commands();require(commands.size()==2,"two Generals submit independent actual native commands");
        for(Map<String,Object> command:commands){Set<Long> actors=new LinkedHashSet<Long>();for(Object id:(List<?>)command.get("actors"))actors.add(((Number)id).longValue());
            require(a.members.containsAll(actors)||b.members.containsAll(actors),"one native order never mixes distinct General rosters");require(actors.size()==6,"all six homogeneous actual members follow their own General");}
        log("two_generals_native_commands",map("commands",commands,"forces",forceSnapshot()));G3NativeAcceptanceHarness.process();advance(1000);
        require("attackMove".equals(client.actor(100).get("orderType"))&&"attackMove".equals(client.actor(106).get("orderType")),"later actual state witnesses both General native orders");
        target1.bV=true;target2.bV=true;log("fixture_targets_removed",map("ids",Arrays.asList(230,231),"basis","EXPLICIT_NATIVE_DEAD_FLAGS"));
        client.produce();require(engine.cf.b.size()==1,"actual producer emits one funded native heavyTank order");G3NativeAcceptanceHarness.process();advance(1000);
        Set<Long> existing=new HashSet<Long>();for(Object raw:am.bE)existing.add(((am)raw).eh);
        ((y)nativeUnit(50)).a(100000f);Long born=null;for(Object raw:am.bE){am u=(am)raw;if(!existing.contains(u.eh)&&"heavyTank".equals(u.r().i())&&u.bX==engine.bs)born=u.eh;}
        require(born!=null,"original factory update actually registers a produced native heavyTank");
        log("fixture_producer_progress",map("producer",50,"method","ORIGINAL_FACTORY_A_FLOAT","explicitDelta",100000,"nativeBornId",born,"naturalProductionTimingProven",false));advance(1000);
        final long newborn=born;GeneralRegistry.UnitView free=registry.unit(newborn);
        require(free!=null&&free.allocation==GeneralRegistry.Allocation.FREE&&free.membership==GeneralRegistry.Membership.UNATTACHED,"later native product birth is FREE and UNATTACHED");
        require(GeneralRegistry.FREE_OWNER.equals(free.owner)&&free.freeSinceFrame==client.arbiter.stamp().frame,"birth has real FREE owner and current freeSinceFrame");
        require(!registry.requestJoin(newborn,a.id),"birth frame cannot allocate a same-frame newborn");
        require(registry.general(a.id).members.size()==6&&registry.general(b.id).members.size()==6,"production birth does not autoattach to either General");log("native_product_birth_free",forceSnapshot());
        am raider=enemy("heavyTank",240,first[0]+80,first[1]+30);advance(8000);
        require(client.scheduler.availableTokens()==4,"long sample replenishes default bounded burst four");
        client.battle.orderStrategy(CommandArbiter.DEFAULT_OWNER,"/command/move?unitId=1&x="+first[0]+"&y="+first[1]);
        require(client.scheduler.availableTokens()==3,"real native CC move rejection still spends attempted token");
        require(client.battle.orderStrategy(CommandArbiter.DEFAULT_OWNER,"/command/move?unitId=2&x="+(first[0]+60)+"&y="+first[1])!=null,"real builder move prepares remaining-token case");client.produce();
        require(client.scheduler.availableTokens()==1,"actual nonforce native attempts leave exactly one shared token");before=engine.cf.b.size();collectForces();
        List<?> collected=(List<?>)value(client.battle,"forceProposals");require(collected.size()>=2,"actual force collection contains competing LocalResponse and General candidates");
        List<Map<String,Object>> originalProposals=proposalMetadata(collected);Collections.reverse(collected);
        List<Map<String,Object>> reversedProposals=proposalMetadata(collected);
        require(((Number)reversedProposals.get(0).get("priority")).intValue()<((Number)reversedProposals.get(reversedProposals.size()-1).get("priority")).intValue(),"fixture supplies actual captured low-priority candidate before high-priority candidate");
        log("fixture_reverse_collected_candidate_order",map("original",originalProposals,"submittedToFlush",reversedProposals,"basis","REORDER_ACTUAL_CAPTURED_PROPOSALS_ONLY_NO_SYNTHETIC_RECEIPTS"));
        GeneralRegistry.UnitView lr=registry.unit(newborn);require(lr.temporaryTask==GeneralRegistry.TemporaryTask.LOCAL_RESPONSE,"actual ForceController recruits available newborn into LocalResponse");
        require(registry.requestJoin(newborn,a.id),"formal allocator reserves FREE active-LR unit in a later frame");
        require(registry.unit(newborn).allocation==GeneralRegistry.Allocation.PENDING_JOIN&&registry.unit(newborn).temporaryTask==GeneralRegistry.TemporaryTask.LOCAL_RESPONSE,"PENDING_JOIN and LocalResponse coexist without beginning movement");
        flushForces();require(engine.cf.b.size()==before+1&&client.scheduler.availableTokens()==0,"highest force candidate spends last token once");
        Map<String,Object> last=G3NativeAcceptanceHarness.commands().get(before);require(((List<?>)last.get("actors")).contains(newborn),"last native command belongs to actual LocalResponse newborn");
        require(((List<?>)last.get("actors")).size()==2,"actual native HP factor requires exactly two responders rather than a fixed FREE floor");
        double selectedHp=0;for(Object actor:(List<?>)last.get("actors"))selectedHp+=nativeUnit(((Number)actor).longValue()).cu;
        require(nativeUnit(newborn).cu<raider.cu*1.5&&selectedHp>=raider.cu*1.5,"single actual heavyTank HP cannot cover threat factor but selected two can");
        require("attackMove".equals(last.get("waypoint")),"LocalResponse wins last token with native attackMove");
        log("last_token_local_response_priority",map("commands",G3NativeAcceptanceHarness.commands(),"forces",forceSnapshot(),"reservedPendingGeneral",a.id.value,"ordering","COLLECT_ALL_FORCE_CANDIDATES_THEN_FLUSH_PRIORITY"));G3NativeAcceptanceHarness.process();
        raider.bV=true;log("fixture_target_removed",map("id",240,"basis","EXPLICIT_NATIVE_DEAD_FLAG"));advance(1000);collectForces();
        GeneralRegistry.UnitView joining=registry.unit(newborn);require(joining.temporaryTask==GeneralRegistry.TemporaryTask.NONE&&joining.membership==GeneralRegistry.Membership.JOINING&&a.id.joinOwner().equals(joining.owner),"actual response reconciliation clears LR into pending JOINING owner");
        long sourceFrame=client.arbiter.stamp().frame;engine.bx++;
        log("fixture_native_frame_between_get_and_post",map("legalStateFrame",sourceFrame,"actualNativePostFrame",engine.bx,"gameTimeMs",engine.by,"basis","EXPLICIT_FRAME_ADVANCE_MODELS_NATIVE_POST_AFTER_GET"));
        flushForces();require(engine.cf.b.size()>=1,"real joining candidate obtains native move receipt");joining=registry.unit(newborn);
        require(joining.joinAcceptedFrame==engine.bx&&joining.joinAcceptedFrame>sourceFrame&&joining.membership==GeneralRegistry.Membership.JOINING,"accepted actual native receipt frame, not older GET frame, guards attachment");
        long joinGeneration=joining.ownerGeneration;log("joining_actual_native_receipt",map("commands",G3NativeAcceptanceHarness.commands(),"forces",forceSnapshot()));G3NativeAcceptanceHarness.process();
        am nativeNewborn=nativeUnit(newborn);double originalX=nativeNewborn.eo,originalY=nativeNewborn.ep;
        GeneralRegistry.GeneralView currentGeneral=registry.general(a.id);fixturePosition(newborn,currentGeneral.x,currentGeneral.y);client.sample(false);client.begin();observeForces();
        require(client.arbiter.stamp().frame==joining.joinAcceptedFrame&&registry.unit(newborn).membership==GeneralRegistry.Membership.JOINING,"same actual receipt frame own-position cannot attach even at centroid");
        log("same_receipt_frame_position_not_arrival",forceSnapshot());fixturePosition(newborn,originalX,originalY);advance(1000);
        require(registry.unit(newborn).membership==GeneralRegistry.Membership.JOINING,"later unchanged native position cannot fabricate arrival");
        GeneralRegistry.GeneralView destination=registry.general(a.id);fixturePosition(newborn,destination.x,destination.y);advance(1000);
        GeneralRegistry.UnitView attached=registry.unit(newborn);require(attached.membership==GeneralRegistry.Membership.ATTACHED&&attached.allocation==GeneralRegistry.Allocation.ASSIGNED&&a.owner.equals(attached.owner),"later real native own-position at current centroid witnesses attachment");
        require(attached.ownerGeneration>joinGeneration&&attached.reservedGeneralId==null,"arrival transfers generation and clears unique reservation");log("join_later_native_position_witness",forceSnapshot());
        enemy("commandCenter",250,first[0]+150,first[1]+150);enemy("commandCenter",251,second[0]+150,second[1]);advance(8000);collectForces();
        long staleActor=registry.general(a.id).members.iterator().next(),generation=client.arbiter.ownerGeneration(staleActor);
        require(client.arbiter.transfer(a.owner,"fixture:aba",staleActor)&&client.arbiter.transfer("fixture:aba",a.owner,staleActor),"explicit owner ABA fixture uses real monotonic arbiter transfer");
        require(client.arbiter.ownerGeneration(staleActor)>generation,"ABA changes generation even when owner name returns");flushForces();commands=G3NativeAcceptanceHarness.commands();
        for(Map<String,Object> command:commands)for(Object actor:(List<?>)command.get("actors"))require(!registry.general(a.id).members.contains(((Number)actor).longValue()),"stale collected General proposal cannot reach native bridge after owner ABA");
        require(commands.size()==1&&((List<?>)commands.get(0).get("actors")).containsAll(registry.general(b.id).members),"unrelated valid General still dispatches after stale other General cancellation");
        require(client.scheduler.availableTokens()==3,"stale collected generation cancels locally without wasting a native token");
        log("collected_generation_stale_rejected",map("staleActor",staleActor,"collectedGeneration",generation,"currentGeneration",client.arbiter.ownerGeneration(staleActor),"commands",commands,"forces",forceSnapshot()));G3NativeAcceptanceHarness.process();advance(1000);client.close();
        require(!rows("g4_force_state").isEmpty()&&!rows("g4_force_transition").isEmpty(),"actual BC report records force state and lifecycle transitions");
    }
    public static void main(String[] args){try{
        if(args.length!=1)throw new IllegalArgumentException("OUTPUT_DIRECTORY");out=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(out);bootstrap();run();
        Map<String,Object> summary=map("status","PASS","checks",checks,"evidence","E2_NATIVE_FIXTURE_WITH_REAL_HTTP","naturalMatch","NO_NATURAL_MATCH","fullAutonomousMainLoop",false,"actualNativeProducerUpdate",true,"naturalRouteArrivalProven",false,"output",out.toString());
        Files.write(out.resolve("g4-force-summary.json"),G3NativeAcceptanceHarness.json(summary).getBytes(StandardCharsets.UTF_8));log("summary",summary);G3NativeAcceptanceHarness.fixture.close();System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();try{if(out!=null)Files.write(out.resolve("g4-force-summary.json"),G3NativeAcceptanceHarness.json(map("status","FAIL","checks",checks,"failure",failure.toString(),"evidence","E2_NATIVE_FIXTURE_WITH_REAL_HTTP")).getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}System.exit(1);}}
}
