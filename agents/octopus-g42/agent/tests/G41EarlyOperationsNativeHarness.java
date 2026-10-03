import com.corrodinggames.rts.game.units.*;
import io.rwagent.bootstrap.RuntimeBridge;
import io.rwagent.client.GeneralRegistry;
import io.rwagent.client.EarlyExpansionNeed;
import io.rwagent.client.Json;
import java.lang.reflect.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** E2_NATIVE_FIXTURE_WITH_REAL_HTTP / NO_NATURAL_MATCH.
 * Actual BC observe/collect/primary-flush/economy/production/optional-flush adapters are invoked.
 * Clock, original factory progress and arrival positions are explicit recorded fixture controls.
 * No desktop control, autonomous full main-loop, natural timing, safe route or victory claim.
 */
public final class G41EarlyOperationsNativeHarness {
    static int checks;static Path out;static com.corrodinggames.rts.game.i engine;
    static G3NativeAcceptanceHarness.Client client;
    static void require(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static Map<String,Object> map(Object... pairs){return G3NativeAcceptanceHarness.map(pairs);}
    static Object value(Object object,String field)throws Exception{return G3NativeAcceptanceHarness.value(object,field);}
    static void set(Object object,String field,Object value)throws Exception{G3NativeAcceptanceHarness.set(object,field,value);}
    static Object call(String method,Class<?>[] types,Object... arguments)throws Exception{return G3NativeAcceptanceHarness.invoke(client.battle,method,types,arguments);}
    static void log(String event,Object data)throws Exception{G3NativeAcceptanceHarness.record(event,data);}
    @SuppressWarnings("unchecked") static Map<String,Object> nativeProbe(String method,String path,int expected)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+G3NativeAcceptanceHarness.PORT+path).openConnection();connection.setRequestMethod(method);connection.setConnectTimeout(5000);connection.setReadTimeout(15000);if("POST".equals(method))connection.setDoOutput(true);
        int status=connection.getResponseCode();InputStream input=status<400?connection.getInputStream():connection.getErrorStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];for(int count;(count=input.read(buffer))>=0;)bytes.write(buffer,0,count);input.close();connection.disconnect();Map<String,Object> packet=(Map<String,Object>)Json.parse(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        log("native_guard_probe",map("method",method,"path",path,"httpStatus",status,"response",packet,"scope","TEST_NATIVE_GUARD_PROBE_NOT_POLICY"));require(status==expected,"actual native guard "+method+" "+path+" expected "+expected+" got "+status);return packet;
    }
    static GeneralRegistry registry()throws Exception{return (GeneralRegistry)value(client.battle,"generals");}
    static EarlyExpansionNeed need()throws Exception{return (EarlyExpansionNeed)value(client.battle,"earlyExpansion");}
    static am actor(long id){for(Object raw:am.bE){am unit=(am)raw;if(unit.eh==id)return unit;}throw new AssertionError("native own actor missing "+id);}
    static void observe()throws Exception{call("observeG4Forces",new Class<?>[]{Map.class,Map.class},client.state,client.enemies);}
    static void collect()throws Exception{call("collectG4Forces",new Class<?>[]{Map.class,Map.class,Map.class},client.state,client.enemies,client.scout);}
    static void flushPrimary()throws Exception{call("flushG4PrimaryForces",new Class<?>[0]);}
    static void flushOptional()throws Exception{call("collectEarlyApproach",new Class<?>[0]);call("flushG4Forces",new Class<?>[0]);}
    static void advance(long gameMs)throws Exception{client.advance(gameMs,false);observe();}
    @SuppressWarnings("unchecked") static void economy()throws Exception{set(client.battle,"executingLane","ECONOMY");Map<String,Object> state=(Map<String,Object>)call("executionState",new Class<?>[]{Map.class},client.state);call("economyLane",new Class<?>[]{Map.class},state);}
    static List<Map<String,Object>> commands()throws Exception{return G3NativeAcceptanceHarness.commands();}
    static void process()throws Exception{G3NativeAcceptanceHarness.process();am producer=actor(50);log("native_producer_after_dispatch",map("id",producer.eh,"dead",producer.bV,"removed",producer.ej,"hp",producer.cu,"ready",producer.cm,"ownerTeam",producer.bX.k,"nativeTeamUnitCount",producer.bX.w(),"nativeTeamUnitCap",producer.bX.x(),"legalProductionMenu",client.read("/combat/production")));}
    static Map<String,Object> forces()throws Exception{List<Map<String,Object>> units=new ArrayList<Map<String,Object>>(),generals=new ArrayList<Map<String,Object>>();for(GeneralRegistry.UnitView unit:registry().units())units.add(unit.metadata());for(GeneralRegistry.GeneralView general:registry().generals())generals.add(general.metadata());return map("units",units,"generals",generals,"ledger",client.ledger());}
    static List<Map<String,Object>> proposals()throws Exception{List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>();for(Object dispatch:(List<?>)value(client.battle,"forceProposals")){Object proposal=value(dispatch,"proposal");rows.add(map("owner",value(proposal,"owner"),"actors",value(proposal,"actors"),"lane",value(proposal,"lane"),"reason",value(proposal,"reason"),"path",value(proposal,"path"),"priority",value(proposal,"priority")));}return rows;}
    static void position(long id,double x,double y)throws Exception{am unit=actor(id);unit.eo=(float)x;unit.ep=(float)y;log("fixture_position",map("actor",id,"x",x,"y",y,"basis","EXPLICIT_NATIVE_OWN_POSITION_NOT_NATURAL_ROUTE"));}
    static void bootstrap()throws Exception{
        System.setProperty("rwagent.port",String.valueOf(G3NativeAcceptanceHarness.PORT));System.setProperty("rwagent.g3Execution","true");System.setProperty("rwagent.g4Forces","true");System.setProperty("rwagent.earlyOperations","true");System.setProperty("rwagent.executionBurst","4");System.setProperty("rwagent.activeArmyTarget","24");System.setProperty("rwagent.globalStrategy","false");System.setProperty("rwagent.g1Trace","true");System.setProperty("rwagent.g2WorldState","true");System.setProperty("rwagent.additionalDiagnostics","true");
        Method initialize=TerrainNativeCostHarness.class.getDeclaredMethod("initialize",String.class);initialize.setAccessible(true);engine=(com.corrodinggames.rts.game.i)initialize.invoke(null,"maps/skirmish/[p2]Big Island (2p).tmx");G3NativeAcceptanceHarness.engine=engine;G3NativeAcceptanceHarness.out=out;G3NativeAcceptanceHarness.fixture=Files.newBufferedWriter(out.resolve("g41-fixture.jsonl"),StandardCharsets.UTF_8);
        for(byte[] column:engine.bs.N)Arrays.fill(column,(byte)10);
        Thread pump=new Thread(()->{while(true){Runnable task=(Runnable)engine.k.poll();if(task!=null)task.run();try{Thread.sleep(1);}catch(InterruptedException e){return;}}},"g41-native-isolated-task-pump");pump.setDaemon(true);pump.start();RuntimeBridge.start(engine,G3NativeAcceptanceHarness.PORT,true);
        log("scope",map("evidence","E2_NATIVE_FIXTURE_WITH_REAL_HTTP","naturalMatch","NO_NATURAL_MATCH","desktopTouched",false,"entry","ACTUAL_BC_OBSERVE_COLLECT_PRIMARY_ECONOMY_PRODUCE_OPTIONAL","fogFixture","ALL_HIDDEN","activeArmyTarget",24));
    }
    static long completeProduct(long desiredId)throws Exception{
        Set<Long> existing=new HashSet<Long>();for(Object raw:am.bE)existing.add(((am)raw).eh);
        ((y)actor(50)).a(100000f);am born=null;int count=0;for(Object raw:am.bE){am unit=(am)raw;if(!existing.contains(unit.eh)&&unit.bX==engine.bs&&"heavyTank".equals(unit.r().i())){born=unit;count++;}}
        require(count==1&&born!=null,"original paid native producer update registers exactly one new heavyTank");long nativeId=born.eh;born.eh=desiredId;
        log("fixture_native_producer_update",map("producer",50,"method","ORIGINAL_FACTORY_A_FLOAT","explicitDelta",100000,"nativeBornId",nativeId,"testIdentity",desiredId,"naturalProductionTimingProven",false,"productType",born.r().i()));
        // Keep receipt and later position evidence distinct even when producer is near the rally.
        position(desiredId,actor(50).eo+400,actor(50).ep);advance(1000);return desiredId;
    }
    static void assertNoGeneralActions()throws Exception{for(Map<String,Object> proposal:proposals())require(!"GENERAL".equals(proposal.get("lane")),"FORMING one-to-five cannot collect General attack/frontier");}
    static void joinAndAttach(long id,int strength)throws Exception{
        GeneralRegistry registry=registry();GeneralRegistry.GeneralView general=registry.generals().get(0);GeneralRegistry.UnitView newborn=registry.unit(id);
        require(newborn!=null&&newborn.allocation==GeneralRegistry.Allocation.FREE&&newborn.membership==GeneralRegistry.Membership.UNATTACHED,"real native birth remains visible FREE breakpoint before formal allocation");
        require(general.phase==GeneralRegistry.Phase.FORMING&&general.desiredStrength==24,"production creates/retains FORMING desired24");require(!registry.requestJoin(id,general.id),"same native birth frame cannot acquire join reservation");
        log("birth_visible_free_and_forming",forces());advance(1000);collect();assertNoGeneralActions();
        GeneralRegistry.UnitView joining=registry.unit(id);require(joining.membership==GeneralRegistry.Membership.JOINING&&joining.reservedGeneralId.equals(general.id),"next actual own observation automatically allocates first/newborn to existing FORMING");
        int before=engine.cf.b.size();flushPrimary();require(engine.cf.b.size()>before,"JOINING priority passes actual BC primary flush");
        boolean nativeJoin=false;for(Map<String,Object> command:commands())if(((List<?>)command.get("actors")).equals(Collections.singletonList(id))&&"move".equals(command.get("waypoint")))nativeJoin=true;require(nativeJoin,"single-actor native join move reaches real bridge");
        joining=registry.unit(id);require(joining.joinAcceptedFrame>=client.arbiter.stamp().frame&&joining.membership==GeneralRegistry.Membership.JOINING,"actual move receipt retains JOINING and actual native frame");
        long accepted=joining.joinAcceptedFrame;process();require("move".equals(((y)actor(id)).ar().d().name()),"native command.k applies original join move order");
        position(id,general.joinTargetX,general.joinTargetY);client.sample(false);client.begin();observe();require(registry.unit(id).membership==GeneralRegistry.Membership.JOINING&&client.arbiter.stamp().frame<=accepted,"same receipt-frame own position cannot become ATTACHED");
        advance(1000);require(registry.unit(id).membership==GeneralRegistry.Membership.ATTACHED&&registry.unit(id).reservedGeneralId==null,"later actual native own position attaches and clears reservation");
        require(registry.general(general.id).members.size()==strength,"native later-position attached strength grows exactly once");require(client.arbiter.owns(general.owner,id),"later arrival performs real General owner transfer");
        require(registry.general(general.id).phase==(strength<6?GeneralRegistry.Phase.FORMING:GeneralRegistry.Phase.ACTIVE),"phase changes only after sixth healthy actual arrival");log("later_native_arrival_phase",forces());
    }
    static void run()throws Exception{
        G3NativeAcceptanceHarness.base(0,1,100000);client=new G3NativeAcceptanceHarness.Client("g41-early",40,false);set(client.battle,"initialG4Actors",Collections.<Long>emptySet());observe();require(registry().generals().isEmpty(),"standard zero ordinary military has no proxy General");
        int oldCap=engine.bs.x();com.corrodinggames.rts.game.n.X();log("fixture_native_team_cap_refresh",map("method","ORIGINAL_N_X_CONFIGURED_CAP_REFRESH","oldCachedCap",oldCap,"engineConfiguredCap",engine.bB,"refreshedCap",engine.bs.x(),"basis","EXPLICIT_NORMAL_NATIVE_STATS_REFRESH_WITHOUT_SIMULATION_TICKS"));require(engine.bs.x()>=24,"native configured production cap supports formation target");
        require(((List<?>)client.scout.get("resources")).isEmpty(),"old Scout memory contains no unseen resource");advance(2500);collect();flushPrimary();economy();
        EarlyExpansionNeed first=need();require(first!=null&&first.live()&&first.state()==EarlyExpansionNeed.State.REQUESTED,"hidden resource static prior creates first-class EarlyExpansionNeed");
        require("UNKNOWN".equals(first.metadata().get("safe"))&&"UNKNOWN".equals(first.metadata().get("enemyOccupancy")),"need leaves hidden dynamic safety/occupancy UNKNOWN");require(!engine.bL.a((float)first.x,(float)first.y,engine.bs),"resource need really refers to currently hidden native tile");
        require(value(client.battle,"buildJob")==null,"no hidden native site plan means no construction job");client.produce();int productionIndex=engine.cf.b.size();require(productionIndex==1,"empty army executes funded native factory production before optional logistics");flushOptional();
        int guardedCount=engine.cf.b.size();nativeProbe("GET","/expansion/plan?unitId=2",409);nativeProbe("POST","/command/build-extractor?unitId=2&x="+first.x+"&y="+first.y+"&sessionId="+client.state.get("sessionId")+"&requestId=g41-hidden-build",409);require(engine.cf.b.size()==guardedCount,"hidden resource native build refusal creates no command");
        require(engine.cf.b.size()==2&&need().state()==EarlyExpansionNeed.State.APPROACHING,"remaining actual token dispatches builder static approach and receipt advances Need only to APPROACHING");
        List<Map<String,Object>> initialCommands=commands();require(initialCommands.get(0).get("nativeAction")!=null&&((List<?>)initialCommands.get(0).get("actors")).equals(Collections.singletonList(50L)),"real production command precedes optional builder move");require(((List<?>)initialCommands.get(1).get("actors")).equals(Collections.singletonList(2L)),"static logistics command has single own builder anchor");
        log("zero_army_production_before_static_logistics",map("commands",initialCommands,"need",need().metadata(),"forces",forces()));process();advance(1000);require(((Number)client.actor(50).get("productionQueue")).intValue()==1,"later legal native state proves actual factory queue");
        require("move".equals(client.actor(2).get("orderType")),"later native builder state witnesses applied static approach order");long firstBorn=completeProduct(201);joinAndAttach(firstBorn,1);
        collect();assertNoGeneralActions();List<Map<String,Object>> screens=new ArrayList<Map<String,Object>>();for(Map<String,Object> proposal:proposals())if("FORMING_SCREEN".equals(proposal.get("lane")))screens.add(proposal);
        require(screens.size()==1&&((List<?>)screens.get(0).get("actors")).equals(Collections.singletonList(201L)),"one attached FORMING member collects independent single-actor resource screen");
        int beforePrimary=engine.cf.b.size();flushPrimary();require(engine.cf.b.size()==beforePrimary,"optional FORMING screen is deferred before economy/production");client.produce();require(engine.cf.b.size()==beforePrimary+1,"ongoing production retains native command opportunity before screen");economy();flushOptional();
        boolean screen=false;for(Map<String,Object> command:commands())if(((List<?>)command.get("actors")).equals(Collections.singletonList(201L))&&"move".equals(command.get("waypoint")))screen=true;require(screen,"FORMING screen actually dispatches one native own move after production");
        log("forming_screen_after_production",map("collected",screens,"commands",commands(),"forces",forces(),"need",need().metadata()));process();advance(1000);
        for(int strength=2;strength<=6;strength++){
            if(((Number)client.actor(50).get("productionQueue")).intValue()==0){advance(3000);collect();flushPrimary();economy();client.produce();flushOptional();require(engine.cf.b.size()>0,"standard continued production submits native command");process();advance(1000);}
            require(((Number)client.actor(50).get("productionQueue")).intValue()==1,"next actual military production remains queued while General forms");long born=completeProduct(200+strength);joinAndAttach(born,strength);
            if(strength<6){collect();assertNoGeneralActions();flushPrimary();client.produce();flushOptional();process();advance(1000);}
        }
        GeneralRegistry.GeneralView active=registry().generals().get(0);require(active.phase==GeneralRegistry.Phase.ACTIVE&&active.members.size()==6&&active.desiredStrength==24,"six native arrivals activate same General and retain fixed desired24 target");
        // Unarmed visible target uses original mature General targeting without becoming a LocalResponse raid.
        com.corrodinggames.rts.game.n enemyTeam=new com.corrodinggames.rts.game.e(1,false);enemyTeam.r=1;G3NativeAcceptanceHarness.unit("builder",999,enemyTeam,(float)active.x+150,(float)active.y+150);
        for(byte[] column:engine.bs.N)Arrays.fill(column,(byte)0);log("fixture_fog_reveal",map("visibility","ALL_VISIBLE","target",999,"basis","EXPLICIT_NATIVE_FOG_FIXTURE"));advance(8000);collect();boolean general=false;for(Map<String,Object> proposal:proposals())if("GENERAL".equals(proposal.get("lane")))general=true;require(general,"ACTIVE resumes existing General visible-target actions");flushPrimary();boolean attack=false;for(Map<String,Object> command:commands())if("attackMove".equals(command.get("waypoint"))&&((List<?>)command.get("actors")).size()==6)attack=true;require(attack,"six attached actors reach actual native General attackMove");process();advance(1000);log("active_native_general_action",forces());
        constructionLegality();client.close();
    }
    static void constructionLegality()throws Exception{
        EarlyExpansionNeed target=need();require(target!=null&&target.live(),"original hidden resource need persists until actual site/product witness");
        actor(999).bV=true;log("fixture_target_removed",map("actor",999,"basis","EXPLICIT_NATIVE_DEAD_FLAG_NOT_INFERRED_DEATH"));
        Map<String,Object> route=client.read("/static-map/approach?unitId=2&tile="+target.tile);require(Boolean.TRUE.equals(route.get("staticPathKnown")),"current own builder retains static approach after visibility reveal");position(2,((Number)route.get("x")).doubleValue(),((Number)route.get("y")).doubleValue());advance(8000);
        Map<String,Object> plan=client.read("/expansion/plan?unitId=2");require("planned".equals(plan.get("status")),"current visibility and actual own rally position allow original native site plan");
        require(Math.hypot(((Number)plan.get("extractorX")).doubleValue()-target.x,((Number)plan.get("extractorY")).doubleValue()-target.y)<60,"current native plan identifies the same prior resource site");
        economy();require(value(client.battle,"buildJob")!=null&&need().state()==EarlyExpansionNeed.State.LEGAL_SITE_OBSERVED,"BC creates priced BuildJob only after current native legality witness");economy();
        // The unchanged economic lane first approaches within its existing build-range threshold.
        if(!commands().isEmpty()&&"move".equals(commands().get(0).get("waypoint"))){process();int near=-1;byte[] land=engine.bU.a(ao.b).d;
            for(int at=0;at<land.length;at++){double distance=Math.hypot((at/engine.bL.D+.5)*engine.bL.n-target.x,(at%engine.bL.D+.5)*engine.bL.o-target.y);if(land[at]>=0&&distance>=60&&distance<=80){near=at;break;}}
            require(near>=0,"legal resource has native LAND approach inside unchanged economy build range");position(2,(near/engine.bL.D+.5)*engine.bL.n,(near%engine.bL.D+.5)*engine.bL.o);advance(1000);economy();}
        require(need().state()!=EarlyExpansionNeed.State.COMPLETE&&engine.cf.b.size()==1,"actual accepted build order cannot mark expansion complete");process();y builder=(y)actor(2);au order=builder.ar();List<Map<String,Object>> nativeOrders=new ArrayList<Map<String,Object>>();for(int i=0;i<builder.av();i++){au queued=builder.k(i);nativeOrders.add(map("index",i,"type",queued.d().name(),"x",queued.g(),"y",queued.h()));}log("native_builder_orders_after_dispatch",map("actor",2,"orders",nativeOrders));require(order!=null&&"build".equals(order.d().name()),"native command.k applies actual original builder build order");
        double before=engine.bs.o;Object nativeResult=builder.a(order,order.a(),1,order.g(),order.h());am unfinished=(am)value(nativeResult,"a");
        require(unfinished!=null&&unfinished.r().i().startsWith("extractor")&&unfinished.cm<1,"original guarded build helper creates registered unfinished extractor at legal site");
        require(engine.bs.o<before,"original guarded construction helper really deducts native cost");log("fixture_native_extractor_initialization",map("method","ORIGINAL_Y_A_AU_AS_INT_FLOAT_FLOAT","nativeProductId",unfinished.eh,"x",unfinished.eo,"y",unfinished.ep,"progress",unfinished.cm,"creditsBefore",before,"creditsAfter",engine.bs.o,"naturalConstructionProven",false));
        advance(1000);collect();require(need().state()==EarlyExpansionNeed.State.CONSTRUCTION_OBSERVED,"later actual own unfinished extractor enters CONSTRUCTION_OBSERVED");require(need().state()!=EarlyExpansionNeed.State.COMPLETE,"unfinished native product never proxies readiness");
        float prior=unfinished.cm;builder.a(100000f);log("fixture_original_builder_update",map("method","ORIGINAL_BUILDER_A_FLOAT","explicitDelta",100000,"nativeProductId",unfinished.eh,"progressBefore",prior,"progressAfter",unfinished.cm,"naturalTimingProven",false));advance(1000);collect();
        if(unfinished.cm>=1){require(need().state()==EarlyExpansionNeed.State.COMPLETE,"only later actual ready own extractor marks COMPLETE");log("later_native_ready_extractor_witness",map("need",need().metadata(),"ownProduct",client.actor(unfinished.eh),"naturalConstructionTimingProven",false));}
        else log("construction_ready_boundary",map("status","NEEDS_EVIDENCE","actualProgress",unfinished.cm,"need",need().metadata(),"reason","ORIGINAL_CONTROLLED_BUILDER_UPDATE_DID_NOT_FINISH_NATIVE_CONSTRUCTION"));
    }
    public static void main(String[] args){try{if(args.length!=1)throw new IllegalArgumentException("OUTPUT_DIRECTORY");out=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(out);bootstrap();run();Map<String,Object> summary=map("status","PASS","checks",checks,"evidence","E2_NATIVE_FIXTURE_WITH_REAL_HTTP","naturalMatch","NO_NATURAL_MATCH","fullAutonomousMainLoop",false,"actualOriginalFactoryUpdate",true,"naturalProductionTimingProven",false,"naturalArrivalProven",false,"output",out.toString());Files.write(out.resolve("g41-summary.json"),G3NativeAcceptanceHarness.json(summary).getBytes(StandardCharsets.UTF_8));log("summary",summary);G3NativeAcceptanceHarness.fixture.close();System.exit(0);}catch(Throwable failure){failure.printStackTrace();try{if(out!=null)Files.write(out.resolve("g41-summary.json"),G3NativeAcceptanceHarness.json(map("status","FAIL","checks",checks,"failure",failure.toString())).getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}System.exit(1);}}
}
