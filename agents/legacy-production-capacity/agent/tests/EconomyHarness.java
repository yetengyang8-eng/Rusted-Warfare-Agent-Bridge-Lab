import android.content.ServerContext;
import io.rwagent.bootstrap.RuntimeBridge;
import io.rwagent.client.Json;
import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;
import java.lang.reflect.*;
import java.util.*;

/** Native 1.15 actions/placement/commands; headless terrain fixture, no full simulation. */
public final class EconomyHarness {
    static com.corrodinggames.rts.game.i engine;
    static am builder, factory;
    static Object costs;
    static int checks;
    static boolean replacement;
    static com.corrodinggames.rts.game.units.as tankType;
    public static void main(String[] args) throws Exception {
        try { replacement=args.length>0;test();System.out.println("ECONOMY_NATIVE_TEST_OK checks="+BridgeHarness.checks);System.exit(0); }
        catch(Throwable t){t.printStackTrace();System.exit(1);}
    }
    static void set(Object o,String n,Object v)throws Exception {Class<?> c=o instanceof Class?(Class<?>)o:o.getClass();Field f=c.getDeclaredField(n);f.setAccessible(true);f.set(o instanceof Class?null:o,v);}
    static Object empty(String name)throws Exception {Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return ((sun.misc.Unsafe)f.get(null)).allocateInstance(Class.forName(name));}
    static void test()throws Exception {
        android.os.Looper.a();engine=new com.corrodinggames.rts.game.i(new ServerContext());BridgeHarness.engine=engine;
        Thread consumer=new Thread(()->{while(true){Runnable r=(Runnable)engine.k.poll();if(r!=null)r.run();try{Thread.sleep(1);}catch(Exception e){return;}}});consumer.setDaemon(true);consumer.start();
        engine.bG=true;engine.bs=new com.corrodinggames.rts.game.e(0,false);engine.bs.o=10000;
        engine.bL=BridgeHarness.construct("com.corrodinggames.rts.game.b.b");engine.bL.C=110;engine.bL.D=110;engine.bL.E=true;engine.bs.N=new byte[110][110];
        set(engine.bL,"u",Class.forName("com.corrodinggames.rts.game.b.e").getConstructor(engine.bL.getClass(),String.class,int.class,int.class).newInstance(engine.bL,"ground",110,110));
        engine.cf=new com.corrodinggames.rts.gameFramework.c();engine.bX=BridgeHarness.construct("com.corrodinggames.rts.gameFramework.j.ad");
        engine.bU=(com.corrodinggames.rts.gameFramework.k.l)empty("com.corrodinggames.rts.gameFramework.k.l");
        set(engine.bU,"q",engine.bL);set(engine.bU,"s",(short)110);set(engine.bU,"t",(short)110);
        costs=empty("com.corrodinggames.rts.gameFramework.k.i");set(costs,"a",com.corrodinggames.rts.game.units.ao.b);
        for(String f:new String[]{"d","e","f"})set(costs,f,new byte[12100]);set(engine.bU,"z",costs);
        set(Class.forName("com.corrodinggames.rts.game.units.d.m"),"a",BridgeHarness.construct("com.corrodinggames.rts.gameFramework.m.e"));
        tankType=ar.valueOf("tank");
        if(replacement) {
            // Original assets/units/tanks/tank.ini: name=c_tank, overrideAndReplace=tank, price=350.
            Class<?> custom=Class.forName("com.corrodinggames.rts.game.units.custom.l");
            Object metadata=custom.newInstance();set(metadata,"M","c_tank");set(metadata,"ch",tankType.u());set(metadata,"ck",.002f);
            ((Map)custom.getField("f").get(null)).put(tankType,metadata);
            tankType=(com.corrodinggames.rts.game.units.as)metadata;
        }
        set(ar.class,"ae",new ArrayList<ar>());ar.b.h();am.bF.put(ar.b,ar.b.a(true));
        am.bE.clear();builder=BridgeHarness.builder();((ar)builder.r()).h();builder.eh=4;builder.eo=940;builder.ep=1780;builder.bX=engine.bs;am.bE.add(builder);
        RuntimeBridge.start(engine,47656,true);
        String session=(String)obj(check("GET","/state",200,"buildProgress")).get("sessionId");
        Map<?,?> plan=obj(check("GET","/economy/plan?unitId=4",200,"planned"));
        require(tankType.i().equals(plan.get("productType")),"plan reports actual resolved product type");
        require(am.bE.size()==1 && engine.bs.o==10000,"planning does not register previews or spend credits");
        require(((Number)plan.get("factoryCost")).intValue()==700,"native factory cost 700");
        System.out.println("NATIVE_TANK_COST="+plan.get("tankCost"));
        String target="&x="+plan.get("targetX")+"&y="+plan.get("targetY");
        String build="/command/build-factory?unitId=4"+target+"&sessionId="+session+"&requestId=build-1";
        check("POST",build,200,"queued");check("POST",build,200,"queued");require(engine.cf.b.size()==1,"one native build command after duplicate receipt");
        com.corrodinggames.rts.gameFramework.e command=(com.corrodinggames.rts.gameFramework.e)engine.cf.b.get(0);
        require(command.j.a()==ar.b,"build waypoint uses native landFactory type");
        require(command.j.g()==((Number)plan.get("targetX")).floatValue() && command.j.h()==((Number)plan.get("targetY")).floatValue(),"build command retains aligned placement");
        check("POST",build.replace("unitId=4","unitId=99"),409,"reused");
        check("POST",build.replace("build-1","foreign").replace("unitId=4","unitId=99"),409,"not found");
        check("POST",build.replace("build-1","stale").replace(session,"old"),409,"session changed");
        check("POST",build.replace("build-1","bad-coordinate").replace(target,"&x=NaN&y=10"),400,"finite");
        check("POST",build.replace("build-1","far").replace(target,"&x=10&y=10"),409,"footprint");
        engine.bs.o=0;check("GET","/economy/plan",409,"affordable");check("POST",build.replace("build-1","poor"),409,"credits");engine.bs.o=10000;
        for(byte[] row:engine.bs.N)Arrays.fill(row,(byte)10);
        check("GET","/economy/plan",409,"no nearby");check("POST",build.replace("build-1","fog"),409,"footprint");
        for(byte[] row:engine.bs.N)Arrays.fill(row,(byte)0);
        byte[] blocked=new byte[12100];Arrays.fill(blocked,(byte)-1);set(costs,"d",blocked);
        check("GET","/economy/plan",409,"no nearby");check("POST",build.replace("build-1","terrain"),409,"footprint");set(costs,"d",new byte[12100]);
        factory=ar.b.a(true);factory.bX=engine.bs;factory.eh=50;factory.eo=((Number)plan.get("targetX")).floatValue();factory.ep=((Number)plan.get("targetY")).floatValue();factory.cm=.5f;am.bE.add(factory);
        String produce="/command/produce-tank?unitId=50&sessionId="+session+"&requestId=tank-1";
        check("POST",produce,409,"completed");factory.cm=.99999f;
        Map<?,?> snapshot=obj(check("GET","/state",200,"0.99999"));
        require(((Number)((Map<?,?>)((java.util.List<?>)snapshot.get("ownUnits")).get(1)).get("buildProgress")).doubleValue()<1,"almost complete is not rounded to complete");
        factory.cm=1;
        check("POST",produce,200,"\"type\":\""+tankType.i()+"\"");check("POST",produce,200,"queued");require(engine.cf.b.size()==2,"only one production command");
        Class<?> actionClass=Class.forName("com.corrodinggames.rts.game.units.a.s");Object tankAction=null;
        for(Object a:factory.N()){Object t=actionClass.getMethod("i").invoke(a);if(t==tankType)tankAction=a;}
        require(tankAction!=null,"native landFactory exposes tank action");
        Object actionId=actionClass.getMethod("N").invoke(tankAction);
        Object actualId=engine.cf.b.get(1).getClass().getField("k").get(engine.cf.b.get(1));require(actionId.equals(actualId),"production command carries actual native action identifier");
        // Execute the actual queued native production command, without a full world simulation.
        engine.cb=new com.corrodinggames.rts.gameFramework.ba();
        ((com.corrodinggames.rts.gameFramework.e)engine.cf.b.get(1)).k();
        require(engine.bs.o==9650,"native command debits the 350 tank cost");
        check("GET","/state",200,"\"productionQueue\":1");
        check("POST",produce.replace("tank-1","busy"),409,"empty");
        engine.bX.B=true;check("POST",produce.replace("tank-1","network"),409,"network");engine.bX.B=false;
        require(am.bE.size()==2,"bridge inserted no factory or tank into world");
    }
    static Map<?,?> obj(String s){return (Map<?,?>)Json.parse(s);}
    static String check(String m,String p,int s,String t)throws Exception{return BridgeHarness.check(m,p,s,t);}
    static void require(boolean b,String s){BridgeHarness.require(b,s);}
}
