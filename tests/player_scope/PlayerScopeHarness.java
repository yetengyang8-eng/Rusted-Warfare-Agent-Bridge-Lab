package io.rwagent.bootstrap;

import android.content.ServerContext;
import com.corrodinggames.rts.game.i;
import com.corrodinggames.rts.game.n;
import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ao;
import com.corrodinggames.rts.game.units.y;
import java.lang.reflect.*;
import java.util.*;

/** Real 1.15 data objects, deliberately no simulation: authority and fog invariants only. */
public final class PlayerScopeHarness {
    static int checks;
    static void require(boolean ok,String message) { if(!ok)throw new AssertionError(message); checks++; System.out.println("PASS "+message); }
    @SuppressWarnings("unchecked") static <T> T make(String name) throws Exception { return (T)Class.forName(name).getConstructor().newInstance(); }
    static Object empty(String name) throws Exception { Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); return ((sun.misc.Unsafe)f.get(null)).allocateInstance(Class.forName(name)); }
    static void set(Object obj,String name,Object value) throws Exception { Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);f.set(obj,value); }
    static RuntimeBridge bridge(i engine,PlayerBinding binding) throws Exception {
        Constructor<RuntimeBridge> c=RuntimeBridge.class.getDeclaredConstructor(i.class,int.class,boolean.class,PlayerBinding.class);
        c.setAccessible(true); return c.newInstance(engine,0,true,binding);
    }
    static Object invoke(Object obj,String name,Class<?>[] types,Object... args) throws Exception { Method m=obj.getClass().getDeclaredMethod(name,types); m.setAccessible(true); return m.invoke(obj,args); }
    static Map<?,?> state(RuntimeBridge bridge) throws Exception { return (Map<?,?>)Json.parse((String)invoke(bridge,"snapshotJson",new Class<?>[0])); }
    static RuntimeBridge.CommandResult move(RuntimeBridge bridge,String session,long id,String request) throws Exception {
        Class<?> type=Class.forName("io.rwagent.bootstrap.RuntimeBridge$MoveRequest");
        Method parse=type.getDeclaredMethod("parse",String.class);parse.setAccessible(true);
        Object action=parse.invoke(null,"unitId="+id+"&x=150&y=150&sessionId="+session+"&requestId="+request);
        return (RuntimeBridge.CommandResult)invoke(bridge,"move",new Class<?>[]{type},action);
    }
    static y builder(n player,long id,float x,float yy) throws Exception {
        y u=(y)Class.forName("com.corrodinggames.rts.game.units.e.b").getConstructor(boolean.class).newInstance(true);
        u.bX=player;u.eh=id;u.eo=x;u.ep=yy;u.cm=1;return u;
    }
    public static void main(String[] args) throws Exception {
        try { run(); System.out.println("PLAYER_SCOPE_OK checks="+checks); System.exit(0); }
        catch(Throwable t) { t.printStackTrace(); System.exit(1); }
    }
    static void run() throws Exception {
        android.os.Looper.a();i engine=new i(new ServerContext());
        engine.bG=true;engine.bs=new com.corrodinggames.rts.game.e(0,false);engine.bs.r=7;
        engine.bL=make("com.corrodinggames.rts.game.b.b");
        engine.bL.C=engine.bL.D=20;engine.bL.E=true;engine.bs.N=new byte[20][20];
        for(byte[] c:engine.bs.N)Arrays.fill(c,(byte)10);
        for(int c=3;c<=8;c++)for(int r=3;r<=8;r++)engine.bs.N[c][r]=0;
        Object ground=Class.forName("com.corrodinggames.rts.game.b.e").getConstructor(engine.bL.getClass(),String.class,int.class,int.class).newInstance(engine.bL,"ground",20,20);
        set(engine.bL,"u",ground);set(engine.bL,"y",ground);set(ground,"q",new short[400]);
        engine.cf=new com.corrodinggames.rts.gameFramework.c();
        engine.bX=make("com.corrodinggames.rts.gameFramework.j.ad");
        engine.bX.B=true;engine.bX.z=engine.bs;
        engine.bU=(com.corrodinggames.rts.gameFramework.k.l)empty("com.corrodinggames.rts.gameFramework.k.l");
        set(engine.bU,"q",engine.bL);set(engine.bU,"s",(short)20);set(engine.bU,"t",(short)20);
        com.corrodinggames.rts.gameFramework.k.i costs=(com.corrodinggames.rts.gameFramework.k.i)empty("com.corrodinggames.rts.gameFramework.k.i");
        set(costs,"a",ao.b);costs.d=new byte[400];costs.e=new byte[400];costs.f=new byte[400];
        set(engine.bU,"v",new com.corrodinggames.rts.gameFramework.k.i[]{costs});set(engine.bU,"z",costs);
        am.bE.clear();y own=builder(engine.bs,101,110,110);am.bE.add(own);
        n enemy=new com.corrodinggames.rts.game.e(1,false);enemy.r=8;
        y hidden=builder(enemy,999,350,350);am.bE.add(hidden);
        RuntimeBridge defaultBridge=bridge(engine,null);
        require(defaultBridge.player()==null,"default bridge refuses network observation identity");
        require(defaultBridge.commandGuard().httpStatus==409,"default bridge retains network command guard");
        require(((List<?>)state(defaultBridge).get("ownUnits")).isEmpty(),"unbound network state exposes no live units");
        boolean rejected=false;try{PlayerBinding.nativeNetwork(engine,"fixture",1);}catch(IllegalStateException expected){rejected=true;}
        require(rejected,"native binding rejects wrong expected local slot");
        PlayerBinding binding=PlayerBinding.nativeNetwork(engine,"fixture",0);RuntimeBridge b=bridge(engine,binding);
        Map<?,?> s=state(b);String session=(String)s.get("sessionId");
        require(Boolean.TRUE.equals(s.get("networked"))&&Boolean.TRUE.equals(s.get("nativeNetworkPlayerV1")),"native network state preserves true network transport");
        require(((Number)s.get("playerId")).intValue()==0&&((Number)s.get("teamId")).intValue()==7,"slot and actual ally group stay distinct");
        require(((List<?>)s.get("ownUnits")).size()==1&&!s.toString().contains("999"),"state exports only owned units");
        require(!LegalVisibility.observed(b,hidden),"hidden foreign unit fails legal fog gate");
        RuntimeBridge.CommandResult foreign=move(b,session,999,"foreign");
        RuntimeBridge.CommandResult absent=move(b,session,777,"absent");
        require(foreign.httpStatus==409&&foreign.json.equals(absent.json),"hidden foreign and nonexistent actor IDs have identical rejection");
        require(engine.cf.d.isEmpty(),"rejected actions never enter native network queue");
        RuntimeBridge.CommandResult accepted=move(b,session,101,"own");
        require(accepted.httpStatus==200&&accepted.json.contains("queued")&&engine.cf.d.size()==1,"owned move enters original native network preprocessing queue");
        move(b,session,101,"own");require(engine.cf.d.size()==1,"request retry is idempotent");
        require(own.eo==110&&own.ep==110,"queued receipt does not claim execution or teleport");
        ScoutBridge.VisibleGrid before=new ScoutBridge.VisibleGrid(b,own,Collections.<ScoutBridge.Threat>emptyList(),null);
        Arrays.fill(costs.e,(byte)-1);Arrays.fill(costs.f,(byte)-1);hidden.eo=330;hidden.ep=330;
        ScoutBridge.VisibleGrid after=new ScoutBridge.VisibleGrid(b,own,Collections.<ScoutBridge.Threat>emptyList(),null);
        require(Arrays.equals(before.distance,after.distance),"poisoned global dynamic blockers cannot change legal scout routes");
        Class<?> factoryType=Class.forName("com.corrodinggames.rts.game.units.d.m");
        Field sprite=factoryType.getDeclaredField("a");sprite.setAccessible(true);sprite.set(null,make("com.corrodinggames.rts.gameFramework.m.e"));
        y hiddenFactory=(y)factoryType.getConstructor(boolean.class).newInstance(true);
        hiddenFactory.eh=998;hiddenFactory.eo=190;hiddenFactory.ep=110;hiddenFactory.bX=enemy;hiddenFactory.cm=1;am.bE.add(hiddenFactory);
        require(LegalVisibility.observedBuildings(b).isEmpty(),"hidden building center cannot reveal its footprint on neighboring visible tiles");
        ScoutBridge.VisibleGrid withHiddenFactory=new ScoutBridge.VisibleGrid(b,own,Collections.<ScoutBridge.Threat>emptyList(),null);
        require(Arrays.equals(after.distance,withHiddenFactory.distance),"hidden enemy building overhang cannot affect a legal route");
        engine.bs.N[9][5]=0;
        require(LegalVisibility.observedBuildings(b).size()==1,"revealed native building becomes a legal blocker");
        ScoutBridge.VisibleGrid withVisibleFactory=new ScoutBridge.VisibleGrid(b,own,Collections.<ScoutBridge.Threat>emptyList(),null);
        require(!Arrays.equals(after.distance,withVisibleFactory.distance),"legally visible building footprint affects route planning");
        engine.bs.N[9][5]=10;am.bE.remove(hiddenFactory);
        CombatBridge combat=new CombatBridge(b);
        engine.bs.N[16][16]=0;hidden.cu=75;
        RuntimeBridge.CommandResult visible=(RuntimeBridge.CommandResult)invoke(combat,"dispatch",new Class<?>[]{String.class,Map.class},"/combat/observe",Collections.<String,String>emptyMap());
        Map<?,?> observed=(Map<?,?>)Json.parse(visible.json);
        require(((List<?>)observed.get("visibleEnemies")).size()==1,"combat observer samples a currently visible enemy");
        engine.bs.N[16][16]=10;hidden.eo=350;hidden.ep=350;hidden.cu=-1;hidden.bV=true;engine.by=1000;
        RuntimeBridge.CommandResult lost=(RuntimeBridge.CommandResult)invoke(combat,"dispatch",new Class<?>[]{String.class,Map.class},"/combat/observe",Collections.<String,String>emptyMap());
        Map<?,?> remembered=(Map<?,?>)Json.parse(lost.json);
        require(((List<?>)remembered.get("visibleEnemies")).isEmpty(),"lost visibility removes current enemy state");
        Map<?,?> old=(Map<?,?>)((List<?>)remembered.get("rememberedEnemies")).get(0);
        require(((Number)old.get("hp")).doubleValue()==75&&((Number)old.get("x")).doubleValue()==330,"hidden HP and position changes cannot refresh last-seen memory");
        require(lost.json.contains("LOST_CONTACT")&&!lost.json.contains("DEAD"),"lost contact never asserts hidden enemy death");
        RuntimeBridge.CommandResult raw=(RuntimeBridge.CommandResult)invoke(combat,"dispatch",new Class<?>[]{String.class,Map.class},"/combat/reachability",Collections.<String,String>emptyMap());
        require(raw.httpStatus==403,"live native network disables global-grid diagnostics");
        engine.bX.z=enemy;require(b.player()==null&&b.commandGuard().httpStatus==409,"native local-player substitution revokes authority");
        engine.bX.z=engine.bs;require(b.player()==null,"restoring globals cannot resurrect a revoked match binding");
        require(((List<?>)state(b).get("ownUnits")).isEmpty(),"revoked binding clears current live observation output");
    }
}
