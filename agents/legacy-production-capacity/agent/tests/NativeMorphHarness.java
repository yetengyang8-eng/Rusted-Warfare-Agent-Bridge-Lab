import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;
import com.corrodinggames.rts.game.units.y;
import java.lang.reflect.*;
import java.util.*;

/** Frozen native own-action fixture, no natural game or simulation result claim. */
public final class NativeMorphHarness {
    static void require(boolean value,String reason){BridgeHarness.require(value,reason);}
    static String call(String method,String path,int status,String contains)throws Exception{
        return BridgeHarness.check(method,path,status,contains);
    }
    static Map<?,?> get(String path)throws Exception{return EconomyHarness.obj(call("GET",path,200,"sessionId"));}
    public static void main(String[] args){try{
        OpeningHarness.test();com.corrodinggames.rts.game.i engine=EconomyHarness.engine;engine.cf.b.clear();
        com.corrodinggames.rts.gameFramework.k.i land=(com.corrodinggames.rts.gameFramework.k.i)EconomyHarness.costs;
        com.corrodinggames.rts.gameFramework.k.i hover=(com.corrodinggames.rts.gameFramework.k.i)EconomyHarness.empty("com.corrodinggames.rts.gameFramework.k.i");
        hover.d=new byte[12100];EconomyHarness.set(engine.bU,"D",land);EconomyHarness.set(engine.bU,"A",hover);
        Class<?> jetClass=Class.forName("com.corrodinggames.rts.game.units.b.c");
        EconomyHarness.set(jetClass,"b",BridgeHarness.construct("com.corrodinggames.rts.gameFramework.m.e"));
        y jet=(y)ar.valueOf("amphibiousJet").a(true);jet.eh=9001;jet.bX=engine.bs;jet.cm=1;jet.eo=810;jet.ep=1610;am.bE.add(jet);
        require(!jet.ae()&&jet.m()==170,"frozen flight has no underwater attack and native range 170");
        require(jet.r().i().equals("amphibiousJet")&&jet.r().c()==2000,"real native jet price 2000, not unused c_amphibiousJet INI");
        String session=(String)get("/state").get("sessionId"),suffix="&sessionId="+session+"&requestId=";
        Map<?,?> observed=get("/combat/unit-modes?unitId=9001");
        require(Boolean.FALSE.equals(observed.get("submergedWeaponAvailable")),"own mode observation preserves actual compatibility");
        require(((List<?>)observed.get("actions")).size()==2,"exact frozen Fly/Dive actions exported");
        call("GET","/combat/unit-modes",400,"required");call("GET","/combat/unit-modes?unitId=4",409,"amphibiousJet");
        String dive="/command/unit-mode?unitId=9001&actionId=152"+suffix+"dive";
        call("POST",dive,409,"unavailable");require(engine.cf.b.isEmpty(),"dry ground cannot accept native Dive");
        Constructor<?> tileConstructor=Class.forName("com.corrodinggames.rts.game.b.g").getDeclaredConstructor();tileConstructor.setAccessible(true);
        Object water=tileConstructor.newInstance();EconomyHarness.set(water,"e",true);
        Object palette=Array.newInstance(water.getClass(),3);Array.set(palette,2,water);EconomyHarness.set(engine.bL,"B",palette);
        short[] cells=new short[12100];cells[40*110+80]=2;EconomyHarness.set(engine.bL.u,"q",cells);
        land.d[40*110+80]=-1;
        require(jet.cJ(),"fixture owns a native water position");
        // The future-mode plan uses real native domain state and only legally observed water.
        y enemy=(y)ar.valueOf("amphibiousJet").a(true);enemy.eh=9002;enemy.bX=new com.corrodinggames.rts.game.e(1,false);
        enemy.bX.r=1;engine.bs.r=0;enemy.cm=1;enemy.eo=810;enemy.ep=1610;enemy.eq=-5;am.bE.add(enemy);
        com.corrodinggames.rts.gameFramework.k.i sea=(com.corrodinggames.rts.gameFramework.k.i)EconomyHarness.empty("com.corrodinggames.rts.gameFramework.k.i");
        EconomyHarness.set(sea,"a",com.corrodinggames.rts.game.units.ao.e);sea.d=new byte[12100];Arrays.fill(sea.d,(byte)-1);sea.d[40*110+80]=0;
        sea.e=new byte[12100];sea.f=new byte[12100];
        EconomyHarness.set(engine.bU,"v",new com.corrodinggames.rts.gameFramework.k.i[]{land,sea});
        get("/combat/observe");
        Map<?,?> engagement=get("/combat/engagement?unitIds=9001&targetId=9002");
        Map<?,?> approach=(Map<?,?>)((List<?>)engagement.get("actors")).get(0);
        require(Boolean.TRUE.equals(engagement.get("targetVisible"))&&"INCOMPATIBLE".equals(approach.get("compatibility")),"future-mode planning never changes current flight weapon incompatibility");
        require("DIVE".equals(approach.get("requiredMode"))&&"APPROACH_PATH_KNOWN".equals(approach.get("modeApproachStatus")),"frozen live adapter plans Dive only at known wet firing position");
        require(Boolean.TRUE.equals(approach.get("modeActionReady"))&&"152".equals(approach.get("modeActionId")),"plan preserves audited own native mode action");
        engine.bs.N[40][80]=10;engine.by+=1000;get("/combat/observe");
        Map<?,?> hidden=get("/combat/engagement?unitIds=9001&targetId=9002");
        require(Boolean.FALSE.equals(hidden.get("targetVisible"))&&!((Map<?,?>)((List<?>)hidden.get("actors")).get(0)).containsKey("requiredMode"),"hidden target cannot authorize a new native mode plan");
        engine.bs.N[40][80]=0;engine.by+=1000;get("/combat/observe");
        int unitsBefore=am.bE.size();double credits=engine.bs.o;float hp=jet.cu;
        call("POST",dive,200,"DIVE");call("POST",dive,200,"DIVE");require(engine.cf.b.size()==1,"native mode receipt idempotency queues once");
        require(!jet.ae(),"accepted Dive alone is not a ready underwater weapon");
        call("POST",dive.replace("requestId=dive","requestId=forbidden").replace("actionId=152","actionId=999"),409,"unavailable");
        call("POST",dive.replace("actionId=152","actionId=151"),409,"reused");
        call("POST",dive.replace("requestId=dive","requestId=stale").replace(session,"old"),409,"session");
        call("POST",dive.replace("requestId=dive","requestId=builder").replace("unitId=9001","unitId=4"),409,"amphibiousJet");
        jet.cm=.5f;call("POST",dive.replace("requestId=dive","requestId=unfinished"),409,"completed");jet.cm=1;
        jet.bX=new com.corrodinggames.rts.game.e(1,false);call("POST",dive.replace("requestId=dive","requestId=foreign"),409,"own");jet.bX=engine.bs;
        jet.bV=true;call("POST",dive.replace("requestId=dive","requestId=dead"),409,"completed");jet.bV=false;
        engine.bX.B=true;call("POST",dive.replace("requestId=dive","requestId=network"),409,"network");engine.bX.B=false;
        engine.bV=new com.corrodinggames.rts.gameFramework.aa();((com.corrodinggames.rts.gameFramework.e)engine.cf.b.get(0)).k();
        require(!jet.ae()&&jet.m()==100,"dispatch changes desired mode before height transition, still no underwater weapon");
        // An explicit fixture height transition isolates native capability accessors; no battle tick.
        jet.eq=-5;
        require(jet.ae(),"only observed native submerged height exposes underwater capability");
        require(Boolean.TRUE.equals(get("/combat/unit-modes?unitId=9001").get("submergedWeaponAvailable")),"mode observation reports actual native readiness");
        Map<?,?> actual=(Map<?,?>)((List<?>)get("/combat/engagement?unitIds=9001&targetId=9002").get("actors")).get(0);
        require("COMPATIBLE".equals(actual.get("compatibility"))&&!actual.containsKey("requiredMode"),"observed native submerged mode replaces future plan with actual compatibility");
        call("POST","/command/unit-mode?unitId=9001&actionId=151"+suffix+"fly",200,"FLY");
        require(am.bE.size()==unitsBefore&&engine.bs.o==credits&&jet.cu==hp,"bridge mode reads/orders do not spawn units, charge directly or mutate HP");
        require(engine.cf.b.size()==2,"only accepted Dive/Fly commands enter native dispatcher");
        System.out.println("NATIVE_MORPH_TEST_OK totalChecks="+BridgeHarness.checks+" fixtureOnly=true");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
