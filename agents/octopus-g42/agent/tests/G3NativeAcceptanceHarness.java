import com.corrodinggames.rts.game.units.*;
import io.rwagent.bootstrap.RuntimeBridge;
import io.rwagent.client.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Controlled E2 fixture: fully initialized original objects, real bridge HTTP and real
 * BattleClient caller/scheduler. Reflection enters private mature policy methods; it does
 * not replace their responses, admission, command transport or native final guards.
 * No natural match, desktop control, production completion or natural mode timing claim.
 */
public final class G3NativeAcceptanceHarness {
    static int checks;static com.corrodinggames.rts.game.i engine;static Path out;
    static BufferedWriter fixture;static final int PORT=47669;
    static void require(boolean b,String reason){checks++;if(!b)throw new AssertionError(reason);}
    static Field field(Class<?> type,String name)throws Exception{for(Class<?> c=type;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    static Object value(Object target,String name)throws Exception{return field(target.getClass(),name).get(target);}
    static void set(Object target,String name,Object v)throws Exception{field(target.getClass(),name).set(target,v);}
    static Object invoke(Object target,String name,Class<?>[] types,Object... args)throws Exception{
        Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(target,args);}catch(InvocationTargetException e){Throwable t=e.getCause();if(t instanceof Exception)throw (Exception)t;throw new RuntimeException(t);}
    }
    static Map<String,Object> map(Object... pairs){Map<String,Object> m=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put(String.valueOf(pairs[i]),pairs[i+1]);return m;}
    static String json(Object o)throws Exception{Method m=BattleClient.class.getDeclaredMethod("json",Object.class);m.setAccessible(true);return (String)m.invoke(null,o);}
    static void record(String event,Object data)throws Exception{fixture.write(json(map("event",event,"data",data))+"\n");fixture.flush();System.out.println("G3_NATIVE "+event+" "+json(data));}
    @SuppressWarnings("unchecked") static Map<String,Object> packet(Object o){return (Map<String,Object>)o;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> items(Map<String,Object> m,String key){return (List<Map<String,Object>>)m.get(key);}
    static am unit(String type,long id,com.corrodinggames.rts.game.n team,float x,float y)throws Exception{
        as nativeType=ar.a(type);require(nativeType!=null,"loaded native type "+type);
        am u=nativeType instanceof ar?((ar)nativeType).a(true):(am)nativeType.getClass().getMethod("a",boolean.class).invoke(nativeType,true);
        u.eh=id;u.bX=team;u.eo=x;u.ep=y;u.cm=1;am.bE.add(u);return u;
    }
    static float[] dry(){for(int x=15;x<engine.bL.C-15;x++)for(int y=15;y<engine.bL.D-15;y++){
        boolean good=true;for(int a=x-8;a<=x+8&&good;a++)for(int b=y-8;b<=y+8;b++)if(engine.bU.a(ao.b).d[a*engine.bL.D+b]<0){good=false;break;}
        if(good)return new float[]{x*engine.bL.n+10,y*engine.bL.o+10};}throw new AssertionError("native dry fixture patch");}
    static float[] coast(){for(int x=10;x<engine.bL.C-10;x++)for(int y=10;y<engine.bL.D-10;y++){
        if(engine.bU.a(ao.e).d[x*engine.bL.D+y]<0||!engine.bL.u.a(x,y).e)continue;
        for(int dx=-5;dx<=5;dx++)for(int dy=-5;dy<=5;dy++)if(engine.bU.a(ao.b).d[(x+dx)*engine.bL.D+y+dy]>=0)
            return new float[]{x*engine.bL.n+10,y*engine.bL.o+10,(x+dx)*engine.bL.n+10,(y+dy)*engine.bL.o+10};
    }throw new AssertionError("native coast fixture");}
    static void clock(long delta)throws Exception{engine.by+=(int)delta;engine.bx+=Math.max(1,(int)(delta/20));record("fixture_clock",map("deltaGameMs",delta,"gameTimeMs",engine.by,"frame",engine.bx,"equivalent5xSamplingWallMs",delta/5.0,"actualWallSpeedMeasured",false,"basis","EXPLICIT_MONOTONIC_ENGINE_CLOCK_NO_NATURAL_TICKS"));}
    static final class Client {
        final BattleClient battle=new BattleClient();final BufferedWriter log;final Object strategy;final CommandArbiter arbiter;final ExecutionScheduler scheduler;final String label;
        Map<String,Object> state,enemies,scout;
        Client(String label,int cap,boolean enableStrategy)throws Exception{
            this.label=label;
            log=Files.newBufferedWriter(out.resolve(label+"-battle.jsonl"),StandardCharsets.UTF_8);set(battle,"log",log);set(battle,"mobileUnitHardCap",cap);
            strategy=value(battle,"strategy");arbiter=(CommandArbiter)value(battle,"execution");scheduler=(ExecutionScheduler)value(battle,"scheduler");
            System.setProperty("rwagent.globalStrategy",String.valueOf(enableStrategy));
            Map<String,Object> health=packet(invoke(battle,"get",new Class<?>[]{String.class,String.class},"/health","native_acceptance_health"));invoke(strategy,"enable",new Class<?>[]{Map.class,int.class},health,cap);
            sample(false);set(battle,"session",state.get("sessionId"));set(battle,"startTime",0L);
            for(Map<String,Object> u:items(state,"ownUnits"))if("commandCenter".equals(u.get("type"))){set(battle,"homeX",((Number)u.get("x")).doubleValue());set(battle,"homeY",((Number)u.get("y")).doubleValue());}
            begin();
        }
        Map<String,Object> read(String path)throws Exception{return battle.readStrategy(path,"native_acceptance_read");}
        void sample(boolean policy)throws Exception{
            state=packet(invoke(battle,"observe",new Class<?>[0]));set(battle,"lastState",state);
            if(value(battle,"session")==null)set(battle,"session",state.get("sessionId"));
            invoke(battle,"updatePending",new Class<?>[]{Map.class},state);
            enemies=read("/combat/observe");scout=read("/scout/observe");set(battle,"lastEnemies",enemies);set(battle,"lastScout",scout);
            if(policy){List<Map<String,Object>> main=packetList(invoke(battle,"mainArmy",new Class<?>[]{Map.class},state));
                invoke(strategy,"observe",new Class<?>[]{Map.class,Map.class,Map.class,List.class,long.class,long.class,double.class,double.class,double.class,boolean.class},state,enemies,scout,main,0L,900000L,100.0,60.0,0.0,false);}
        }
        void begin()throws Exception{invoke(battle,"beginExecutionObservation",new Class<?>[]{Map.class},state);}
        void advance(long delta,boolean policy)throws Exception{clock(delta);sample(policy);begin();}
        void produce()throws Exception{set(battle,"executingLane","ORDINARY_PRODUCTION");Map<String,Object> effective=packet(invoke(battle,"executionState",new Class<?>[]{Map.class},state));invoke(battle,"produce",new Class<?>[]{Map.class},effective);}
        void act()throws Exception{set(battle,"executingLane","STRATEGY");invoke(strategy,"act",new Class<?>[]{double.class,long.class},0.0,1L);}
        Map<String,Object> ledger(){return map("gameTimeMs",arbiter.stamp().gameTimeMs,"tokens",scheduler.availableTokens(),"credits",scheduler.effectiveCredits(),"militarySlots",scheduler.availableMilitarySlots(),"nativeCredits",engine.bs.o,"nativeBufferedCommands",engine.cf.b.size());}
        Map<String,Object> actor(long id){for(Map<String,Object> u:items(state,"ownUnits"))if(((Number)u.get("id")).longValue()==id)return u;throw new AssertionError("missing actor "+id);}
        void close()throws Exception{
            log.close();Path logPath=out.resolve(logName());List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>();Map<String,Map<String,Object>> reads=new LinkedHashMap<String,Map<String,Object>>();
            for(String line:Files.readAllLines(logPath,StandardCharsets.UTF_8)){Map<String,Object> row=packet(Json.parse(line));rows.add(row);Map<String,Object> trace=packet(row.get("trace"));
                if(trace!=null&&"OBSERVATION".equals(trace.get("phase"))&&trace.get("observation") instanceof Map){Map<String,Object> o=packet(trace.get("observation"));reads.put(String.valueOf(o.get("observationId")),o);}}
            int priced=0;for(Map<String,Object> row:rows)if("g3_execution".equals(row.get("event"))){Map<String,Object> e=packet(row.get("data"));
                if(Boolean.TRUE.equals(e.get("nativeAttempted"))&&Boolean.TRUE.equals(packet(e.get("commitment")).get("spending"))){priced++;
                    String source=String.valueOf(e.get("costSourceObservationId")),path=String.valueOf(e.get("costSourceRequestPath"));require(!"/state".equals(path)&&reads.containsKey(source),"spending source identifies actual earlier native GET");require(path.equals(reads.get(source).get("requestPath")),"price source full path matches native GET provenance");}
            }record("price_provenance_checked",map("battleLog",logPath.toString(),"actualAttemptSpendingIntents",priced,"assertion","MATCH_ACTUAL_NATIVE_GET_OBSERVATION_ID_AND_REQUEST_PATH_NEVER_STATE"));
        }
        String logName(){return label+"-battle.jsonl";}
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> packetList(Object o){return (List<Map<String,Object>>)o;}
    static List<Map<String,Object>> commands()throws Exception{
        List<Map<String,Object>> result=new ArrayList<Map<String,Object>>();for(Object raw:engine.cf.b){com.corrodinggames.rts.gameFramework.e c=(com.corrodinggames.rts.gameFramework.e)raw;
            List<Long> actors=new ArrayList<Long>();for(Object a:(Iterable<?>)value(c,"v"))actors.add(((am)a).eh);
            result.add(map("actors",actors,"nativeAction",c.k==null?null:c.k.a(),"waypoint",c.j==null?null:c.j.d().name(),"nativeProduct",c.j==null||c.j.a()==null?null:c.j.a().i()));}
        return result;
    }
    static void process()throws Exception{
        record("native_dispatch_before",map("credits",engine.bs.o,"commands",commands()));engine.bV=new com.corrodinggames.rts.gameFramework.aa();
        List<Object> submitted=new ArrayList<Object>();for(Object o:engine.cf.b)submitted.add(o);engine.cf.b.clear();
        for(Object raw:submitted)((com.corrodinggames.rts.gameFramework.e)raw).k();
        record("native_dispatch_after",map("credits",engine.bs.o,"processed",submitted.size(),"method","REAL_NATIVE_COMMAND_K_NO_UNIT_SIMULATION_TICKS"));
    }
    static void base(int army,int factories,double credits)throws Exception{
        am.bE.clear();engine.cf.b.clear();engine.bs.o=credits;engine.bs.r=0;float[] p=dry();
        unit("commandCenter",1,engine.bs,p[0],p[1]);unit("builder",2,engine.bs,p[0]+40,p[1]);
        for(int i=0;i<army;i++)unit("heavyTank",100+i,engine.bs,p[0]+i*12,p[1]+150);
        for(int i=0;i<factories;i++){am factory=unit("landFactory",50+i,engine.bs,p[0]-110+(i%4)*65,p[1]-120+(i/4)*65);factory.getClass().getMethod("a",int.class).invoke(factory,2);require(factory.V()==2,"actual native T2 producer");}
        record("fixture_units",map("army",army,"factories",factories,"credits",credits,"dryPatch",Arrays.asList(p[0],p[1]),"unitsConstructedFromLoadedNativeTypes",true));
    }
    static void production()throws Exception{
        base(8,12,100000);Client c=new Client("production-abundant",40,false);require(c.scheduler.availableTokens()==1,"initial token");
        c.advance(2500,false);c.produce();require(engine.cf.b.size()==3,"2500ms supports three independent factories with initial token");record("production_2500",c.ledger());
        int ghosts=((Number)invoke(c.battle,"ordinaryUnobservedSlots",new Class<?>[]{Map.class},c.state)).intValue();require(ghosts==3,"accepted invisible native queues retain pending military slots");
        for(Map<String,Object> command:commands())require(((List<?>)command.get("actors")).size()==1,"native produce has distinct single actor");
        c.advance(3500,false);require(c.scheduler.availableMilitarySlots()==29,"next sample counts three invisible pending queue ghosts");c.produce();require(engine.cf.b.size()==7,"3500ms refills bounded four commands");record("production_3500",c.ledger());
        c.advance(60000,false);c.produce();require(engine.cf.b.size()==11,"long gap admits burst four only");require(c.scheduler.availableTokens()==0,"burst consumed exactly four");record("production_long_gap",c.ledger());
        Set<Long> actors=new HashSet<Long>();for(Map<String,Object> command:commands())for(Object id:(List<?>)command.get("actors"))require(actors.add(((Number)id).longValue()),"invisible accepted queue actor cannot be purchased twice");
        double before=engine.bs.o;process();require(engine.bs.o<before,"real native command.k deducts production money");c.advance(1000,false);int queued=0;for(Map<String,Object> u:items(c.state,"ownUnits"))if("landFactory".equals(u.get("type"))&&((Number)u.get("productionQueue")).intValue()>0)queued++;
        require(queued==11,"later state witnesses eleven actual native queues");record("production_native_queue_witness",map("queuedFactories",queued,"ledger",c.ledger(),"productReadyProven",false));c.close();
        base(8,5,1600);Client money=new Client("production-credits",40,false);money.advance(2500,false);money.produce();require(engine.cf.b.size()==2,"native800 price admits two funded producers only");require(money.scheduler.effectiveCredits()==0,"batch credits immediately debited before native queue processing");
        engine.bx++;money.sample(false);money.begin();money.produce();require(engine.cf.b.size()==2&&money.scheduler.effectiveCredits()==0,"repeated native game time does not refund batch credits");record("production_credit_bound",money.ledger());
        money.advance(3500,false);money.produce();record("production_delayed_execution_credit_bound",map("ledger",money.ledger(),"commands",commands(),"nativeCommandsStillUnprocessed",true,"nativePrice",800,"availableOriginalCash",1600,"desiredMaximumAcceptedOrders",2));
        require(engine.cf.b.size()==2&&money.scheduler.effectiveCredits()==0,"accepted unresolved native orders retain credits across advanced game-time observation");
        require(engine.bs.o==1600,"delayed native commands are not mislabeled as native payment");process();require(engine.bs.o==0,"native payment reconciles exact budget");money.advance(2500,false);money.produce();require(engine.cf.b.isEmpty(),"paid native credits prevent further orders");money.close();
        base(22,5,100000);Client slots=new Client("production-slots",24,false);slots.advance(2500,false);slots.produce();require(engine.cf.b.size()==2&&slots.scheduler.availableMilitarySlots()==0,"two remaining military slots bound abundant multi-factory batch");
        slots.advance(3500,false);slots.produce();require(engine.cf.b.size()==2&&slots.scheduler.availableMilitarySlots()==0,"accepted-before-visible pending preserves hard-cap slots");record("production_slot_bound",slots.ledger());process();slots.advance(1000,false);require(slots.scheduler.availableMilitarySlots()==0,"actual native queue remains counted");slots.close();
        construction();
    }
    static void construction()throws Exception{
        base(0,0,10000);Client c=new Client("production-construction",24,false);
        Map<String,Object> plan=c.read("/economy/construction-plan?unitId=2&type=landFactory");
        require(plan!=null&&"planned".equals(plan.get("status")),"native construction plan returns a legal actual builder site");
        String owner="strategy:construction-probe";require(c.arbiter.claim(owner,2),"real builder owner generation claimed");
        Map<String,Object> context=map("nativeCost",plan.get("cost"),"product",plan.get("type"),"constructionSlotAvailable",1,"producerSlots",1);context.putAll(c.battle.costSourceStrategy(plan));
        String path="/command/construct?unitId=2&actionId="+java.net.URLEncoder.encode(String.valueOf(plan.get("actionId")),"UTF-8")+"&x="+plan.get("x")+"&y="+plan.get("y");
        long cost=((Number)plan.get("cost")).longValue();require(c.battle.orderStrategy(owner,path,context)!=null,"actual priced BC scheduler construction sends native HTTP");
        require(c.scheduler.effectiveCredits()==10000-cost,"construction accepted receipt reserves exact native quote");process();c.advance(1000,false);
        Map<String,Object> builderPacket=c.actor(2);require("build".equals(builderPacket.get("orderType")),"native command.k starts actual builder build waypoint");
        require(builderPacket.get("orderX") instanceof Number&&builderPacket.get("orderY") instanceof Number,"real state exports native build order coordinates");
        require(Math.hypot(((Number)builderPacket.get("orderX")).doubleValue()-((Number)plan.get("x")).doubleValue(),((Number)builderPacket.get("orderY")).doubleValue()-((Number)plan.get("y")).doubleValue())<1,"state build site equals native quoted order site");
        require(c.scheduler.unsettledCreditTotal()==cost,"build order coordinates alone cannot settle a price obligation");
        y builder=null;for(Object raw:am.bE){am u=(am)raw;if(u.eh==2)builder=(y)u;}require(builder!=null,"actual native builder remains present");
        au order=builder.ar();double cashBefore=engine.bs.o;
        // The same initialized native helper used by the original build simulation.
        // It performs native menu/placement/payment guards and native unit registration.
        // This explicit helper invocation is fixture execution, not a natural builder tick.
        Object nativeResult=builder.a(order,order.a(),1,order.g(),order.h());am candidate=(am)value(nativeResult,"a");
        require(candidate!=null&&"landFactory".equals(candidate.r().i())&&candidate.cm<1,"native guarded build helper initializes a real unfinished factory");
        require(engine.bs.o==cashBefore-cost,"native helper actually pays the quoted factory cost");
        record("fixture_native_construction_initialization",map("method","ORIGINAL_Y_A_AU_AS_INT_FLOAT_FLOAT","siteX",order.g(),"siteY",order.h(),"nativeProductId",candidate.eh,"buildProgress",candidate.cm,"creditsBefore",cashBefore,"creditsAfter",engine.bs.o,"simulationTicks",0,"naturalConstructionProven",false));
        c.advance(1000,false);require(c.scheduler.unsettledCreditCount()==0,"later legal native site+order witness settles exact construction hold");
        require(c.scheduler.effectiveCredits()==(long)engine.bs.o,"observed native payment reconciles without double debit");
        record("construction_later_effect_witness",map("builder",c.actor(2),"product",c.actor(candidate.eh),"ledger",c.ledger(),"witnessScope","NATIVE_CONSTRUCTION_SITE_EFFECT_NOT_PRODUCER_LINEAGE","completionProven",false));c.close();
    }
    @SuppressWarnings("unchecked") static Object worker(Client c,long actor)throws Exception{return ((Map<Long,Object>)value(c.strategy,"workers")).get(actor);}
    static CapabilityUnitMode mode(Client c,long actor)throws Exception{return (CapabilityUnitMode)value(worker(c,actor),"mode");}
    static void modes()throws Exception{
        base(8,0,20000);float[] coast=coast();y wet=(y)unit("amphibiousJet",81,engine.bs,coast[0],coast[1]),dry=(y)unit("amphibiousJet",82,engine.bs,coast[2],coast[3]);
        com.corrodinggames.rts.game.n opponent=new com.corrodinggames.rts.game.e(1,false);opponent.r=1;
        am enemy=unit("amphibiousJet",230,opponent,coast[0],coast[1]);enemy.eq=-5;
        require(wet.cJ()&&!dry.cJ(),"actual native wet/dry position split");record("fixture_coast",map("wetActor",81,"dryActor",82,"target",230,"coordinates",Arrays.asList(coast[0],coast[1],coast[2],coast[3]),"enemyHeightFixture",-5));
        Client c=new Client("modes",40,true);c.advance(120000,true);c.act();record("mode_first_dispatch",map("commands",commands(),"wet",mode(c,81).snapshot(),"dry",mode(c,82).snapshot(),"ledger",c.ledger()));
        require(mode(c,81).phase()==CapabilityUnitMode.Phase.DIVE_REQUESTED,"wet member independently requests Dive");require(mode(c,82).phase()==CapabilityUnitMode.Phase.MOVE_TO_WATER,"dry member independently approaches known water");require(engine.cf.b.size()==2,"wet Dive and dry move share real state batch");
        require(!wet.ae(),"queued receipt does not prove native underwater readiness");process();require(!wet.ae()&&wet.m()==100,"native command.k sets desired Dive but not submerged height");
        Map<String,Object> before=c.read("/combat/unit-modes?unitId=81");require(Boolean.FALSE.equals(before.get("submergedWeaponAvailable")),"independent native modes read still air before height witness");record("mode_desired_dive_without_height",before);
        wet.eq=-5;record("fixture_height",map("actor",81,"eq",-5,"basis","EXPLICIT_FIXTURE_HEIGHT_NOT_NATURAL_DIVE_COMPLETION"));c.advance(2500,true);c.act();
        Map<String,Object> witnessed=c.read("/combat/unit-modes?unitId=81");require(Boolean.TRUE.equals(witnessed.get("submergedWeaponAvailable")),"later independent native packet witnesses wet submerged readiness");require(mode(c,81).nativeMode().equals("SUBMERGED"),"actual Strategy per-unit witness follows native movement field");require(!mode(c,82).nativeMode().equals("SUBMERGED"),"dry member never inherits collective submerged mode");record("mode_later_submerged_witness",map("packet",witnessed,"wet",mode(c,81).snapshot(),"dry",mode(c,82).snapshot()));process();
        dry.eo=coast[0];dry.ep=coast[1];record("fixture_position",map("actor",82,"x",dry.eo,"y",dry.ep,"basis","EXPLICIT_FIXTURE_ARRIVAL_NOT_NATURAL_ROUTE_COMPLETION"));c.advance(2500,true);c.act();require(mode(c,82).phase()==CapabilityUnitMode.Phase.DIVE_REQUESTED,"late dry member requests own Dive after actual water position");require(mode(c,81).nativeMode().equals("SUBMERGED"),"other member preserves own witness while late member requests");record("mode_late_member_dive",map("commands",commands(),"wet",mode(c,81).snapshot(),"dry",mode(c,82).snapshot()));process();
        Object w=worker(c,81);invoke(c.strategy,"returnHome",new Class<?>[]{w.getClass(),long.class},w,0L);record("fixture_return_trigger",map("actor",81,"method","EXISTING_STRATEGY_RETURN_HOME","naturalTargetCompletionProven",false));c.advance(12000,true);c.act();require(mode(c,81).phase()==CapabilityUnitMode.Phase.FLY_REQUESTED,"returning wet actor independently requests Fly");
        Map<String,Object> dryDesired=c.read("/combat/unit-modes?unitId=82");record("mode_dry_desired_only_boundary",map("nativePacket",dryDesired,"nativeHeight",dry.eq,"nativeUnderwaterWeapon",dry.ae(),"dryPhase",mode(c,82).snapshot()));
        require(!dry.ae()&&Boolean.FALSE.equals(dryDesired.get("submergedWeaponAvailable")),"native desired Dive at unchanged height still has no underwater weapon");
        require(mode(c,82).phase()!=CapabilityUnitMode.Phase.SUBMERGED_READY&&!mode(c,82).nativeMode().equals("SUBMERGED"),"movement WATER desired mode must not fabricate physical submerged readiness");
        record("mode_fly_request",map("commands",commands(),"wet",mode(c,81).snapshot(),"dry",mode(c,82).snapshot()));process();
        require(Boolean.TRUE.equals(c.read("/combat/unit-modes?unitId=81").get("submergedWeaponAvailable")),"Fly receipt/native desired mode not surfaced-height proof");wet.eq=20;record("fixture_height",map("actor",81,"eq",20,"basis","EXPLICIT_FIXTURE_HEIGHT_NOT_NATURAL_FLY_COMPLETION"));c.advance(12000,true);c.act();Map<String,Object> air=c.read("/combat/unit-modes?unitId=81");require(Boolean.FALSE.equals(air.get("submergedWeaponAvailable"))&&mode(c,81).nativeMode().equals("AIR"),"later native modes read and Strategy witness prove this actor air");record("mode_later_air_witness",map("packet",air,"wet",mode(c,81).snapshot(),"dry",mode(c,82).snapshot()));c.close();
    }
    static void competition()throws Exception{
        base(10,4,20000);float[] p=dry();com.corrodinggames.rts.game.n opponent=new com.corrodinggames.rts.game.e(1,false);opponent.r=1;
        am enemy=unit("tank",230,opponent,p[0]+80,p[1]+120);enemy.cu=50;
        Client c=new Client("competition",40,false);c.advance(2500,false);
        require(Boolean.TRUE.equals(invoke(c.battle,"claimRecon",new Class<?>[]{long.class,long.class},42L,109L)),"actual Recon helper claims separate actor");
        invoke(c.battle,"observeLocalArmies",new Class<?>[]{Map.class},c.state);invoke(c.battle,"observeLocalCrisis",new Class<?>[]{Map.class,Map.class},c.state,c.enemies);
        require(value(c.battle,"localCrisis")!=null,"native visible contact produces existing LocalCrisis");set(c.battle,"executingLane","LOCAL_CRISIS");require(Boolean.TRUE.equals(invoke(c.battle,"issueLocalCrisis",new Class<?>[]{Map.class,Map.class},c.state,c.enemies)),"actual LocalCrisis caller sends native group first");
        set(c.battle,"executingLane","RECON");CommandArbiter.MoveIntent move=new CommandArbiter.MoveIntent(c.arbiter.stamp(),"recon:42",109,p[0]+170,p[1]+150);
        Object execution=invoke(c.battle,"submitMove",new Class<?>[]{CommandArbiter.MoveIntent.class,Map.class,boolean.class},move,c.actor(109),false);require(execution!=null,"actual typed Recon submitMove sends second native request");
        int remaining=c.scheduler.availableTokens();Object conflict=invoke(c.battle,"submitMove",new Class<?>[]{CommandArbiter.MoveIntent.class,Map.class,boolean.class},move,c.actor(109),false);require(conflict==null&&c.scheduler.availableTokens()==remaining,"same actor contender cancelled without native token");
        c.produce();require(engine.cf.b.size()==3&&c.scheduler.availableTokens()==0,"LocalCrisis Recon production share observation and budget");List<Map<String,Object>> orders=commands();require("attackMove".equals(orders.get(0).get("waypoint"))&&"move".equals(orders.get(1).get("waypoint"))&&orders.get(2).get("nativeAction")!=null,"native command buffer retains actual immediate caller order");
        record("competition_immediate_order",map("callers",Arrays.asList("LOCAL_CRISIS","RECON","RECON_CONFLICT_LOCAL_CANCEL","ORDINARY_PRODUCTION"),"nativeCommands",orders,"ledger",c.ledger(),"semantics","FIXED_CALLER_TRAVERSAL_NO_RETROACTIVE_GLOBAL_PRIORITY_REORDER"));process();
        c.advance(1000,false);invoke(c.battle,"observeLocalCrisis",new Class<?>[]{Map.class,Map.class},c.state,c.enemies);require("move".equals(c.actor(109).get("orderType")),"later real native state witnesses Recon active order");record("competition_later_order_witness",map("reconActor",c.actor(109),"receiptIsExecution",false,"nativeCommandsProcessed",3));c.close();
    }
    public static void main(String[] args){try{
        if(args.length!=2)throw new IllegalArgumentException("G3NativeAcceptanceHarness production|modes|competition OUTPUT_DIRECTORY");out=Paths.get(args[1]).toAbsolutePath();Files.createDirectories(out);
        fixture=Files.newBufferedWriter(out.resolve(args[0]+"-fixture.jsonl"),StandardCharsets.UTF_8);System.setProperty("rwagent.port",String.valueOf(PORT));System.setProperty("rwagent.g3Execution","true");System.setProperty("rwagent.executionBurst","4");System.setProperty("rwagent.g1Trace","true");System.setProperty("rwagent.g2WorldState","true");System.setProperty("rwagent.additionalDiagnostics","true");
        Method init=TerrainNativeCostHarness.class.getDeclaredMethod("initialize",String.class);init.setAccessible(true);engine=(com.corrodinggames.rts.game.i)init.invoke(null,"maps/skirmish/[p2]Big Island (2p).tmx");
        for(byte[] column:engine.bs.N)Arrays.fill(column,(byte)0);
        Thread tasks=new Thread(()->{while(true){Runnable r=(Runnable)engine.k.poll();if(r!=null)r.run();try{Thread.sleep(1);}catch(InterruptedException e){return;}}},"g3-native-isolated-task-pump");tasks.setDaemon(true);tasks.start();RuntimeBridge.start(engine,PORT,true);
        record("scope",map("evidence","E2_NATIVE_FIXTURE_WITH_REAL_HTTP","naturalMatch","NO_NATURAL_MATCH","originalMapLoaded",true,"fogFixture","ALL_NATIVE_MAP_TILES_VISIBLE_FOR_CONTROLLED_TEST","desktopTouched",false,"policyEntry","REAL_BATTLECLIENT_PRIVATE_CALLERS_NOT_FULL_AUTONOMOUS_LOOP"));
        if(args[0].equals("production"))production();else if(args[0].equals("modes"))modes();else if(args[0].equals("competition"))competition();else throw new IllegalArgumentException("scenario");
        Map<String,Object> summary=map("scenario",args[0],"status","PASS","checks",checks,"evidence","E2_NATIVE_FIXTURE_WITH_REAL_HTTP","naturalMatch","NO_NATURAL_MATCH","simulationTicks",0,"output",out.toString());Files.write(out.resolve(args[0]+"-summary.json"),json(summary).getBytes(StandardCharsets.UTF_8));record("summary",summary);fixture.close();System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();try{if(out!=null&&args.length>0)Files.write(out.resolve(args[0]+"-summary.json"),json(map("scenario",args[0],"status","FAIL","checks",checks,"failureType",failure.getClass().getName(),"failure",failure.getMessage(),"evidence","E2_NATIVE_FIXTURE_WITH_REAL_HTTP","naturalMatch","NO_NATURAL_MATCH")).getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}System.exit(1);}}
}
