import com.corrodinggames.rts.game.units.*;
import io.rwagent.bootstrap.RuntimeBridge;
import io.rwagent.client.Json;
import java.lang.reflect.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** E2 fixture on fully loaded frozen 1.15 assets, NOT an autonomous match or desktop E4.
 * Fixture positions/funds/fog are changed explicitly to test endpoint contracts.
 */
public final class StrategyNativeHarness {
    static int checks;
    static final int PORT=47659;
    static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    @SuppressWarnings("unchecked") static Map<String,Object> call(String method,String path,int expected)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+PORT+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(5000);c.setReadTimeout(7000);
        int status=c.getResponseCode();ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream in=status>=400?c.getErrorStream():c.getInputStream()){byte[] block=new byte[4096];for(int n;(n=in.read(block))!=-1;)out.write(block,0,n);}
        String body=new String(out.toByteArray(),StandardCharsets.UTF_8);
        require(status==expected,path+" HTTP="+status+" "+body);
        System.out.println("NATIVE_STRATEGY_E2 "+path+" "+body);return (Map<String,Object>)Json.parse(body);
    }
    static am unit(String name,long id,com.corrodinggames.rts.game.n team,float x,float y)throws Exception{
        as type=ar.a(name);require(type!=null,"native type "+name);
        am u=type instanceof ar?((ar)type).a(true):(am)type.getClass().getMethod("a",boolean.class).invoke(type,true);
        u.eh=id;u.bX=team;u.eo=x;u.ep=y;u.cm=1;am.bE.add(u);return u;
    }
    @SuppressWarnings("unchecked") static Map<String,Object> actor(Map<String,Object> answer,int index){return (Map<String,Object>)((List<?>)answer.get("actors")).get(index);}
    public static void main(String[] args)throws Exception{
        try{
            Method init=TerrainNativeCostHarness.class.getDeclaredMethod("initialize",String.class);init.setAccessible(true);
            com.corrodinggames.rts.game.i engine=(com.corrodinggames.rts.game.i)init.invoke(null,"maps/skirmish/[p2]Big Island (2p).tmx");
            // Pure adapter-boundary checks, including a native observation accessor failure.
            Method domain=Class.forName("io.rwagent.bootstrap.CombatBridge").getDeclaredMethod("nativeDomainCompatibility",
                String.class,Boolean.class,boolean.class,boolean.class,boolean.class,boolean.class,boolean.class);domain.setAccessible(true);
            require("UNKNOWN".equals(domain.invoke(null,"UNKNOWN",null,true,true,true,true,true)),"unknown observed domain cannot become surface-compatible");
            require("UNKNOWN".equals(domain.invoke(null,"SURFACE",null,true,false,false,true,false)),"water-conditional weapon cannot infer missing target water state");
            require("UNKNOWN".equals(domain.invoke(null,"AIR",null,false,true,true,true,true)),"lost-contact native compatibility remains unknown");
            require("INCOMPATIBLE".equals(domain.invoke(null,"SURFACE",false,true,false,false,true,false)),"observed dry target rejects water-only surface weapon");
            // No simulation ticks: only process explicitly submitted bridge read/command tasks.
            Thread game=new Thread(()->{while(true){Runnable r=(Runnable)engine.k.poll();if(r!=null)r.run();try{Thread.sleep(1);}catch(InterruptedException e){return;}}});game.setDaemon(true);game.start();
            RuntimeBridge.start(engine,PORT,true);am.bE.clear();engine.bs.o=20000;
            for(byte[] col:engine.bs.N)Arrays.fill(col,(byte)10);
            for(int x=0;x<40;x++)for(int y=0;y<40;y++)engine.bs.N[x][y]=0;
            am heavy=unit("heavyTank",9001,engine.bs,450,350),engineer=unit("combatEngineer",9002,engine.bs,450,350);
            com.corrodinggames.rts.game.n opponent=new com.corrodinggames.rts.game.e(1,false);opponent.r=1;engine.bs.r=0;
            am factory=unit("seaFactory",9230,opponent,290,70);
            am mine=unit("extractorT1",9003,engine.bs,410,790);
            call("GET","/health",200);Map<String,Object> state=call("GET","/state",200);String session=(String)state.get("sessionId");
            call("GET","/combat/observe",200);int before=am.bE.size();float hp=heavy.cu;double credits=engine.bs.o;
            Map<String,Object> geometry=call("GET","/combat/engagement?unitIds=9001,9002&targetId=9230",200);
            require("COMPATIBLE".equals(actor(geometry,0).get("compatibility")),"heavy weapon surface compatibility preserved");
            require("BLOCKED_TERRAIN".equals(actor(geometry,0).get("status")),"desktop #230 geometry blocks LAND using native legal terrain");
            require("APPROACH_PATH_KNOWN".equals(actor(geometry,1).get("status")),"HOVER engineer has known approach");
            require(before==am.bE.size()&&hp==heavy.cu&&credits==engine.bs.o,"engagement reads neither mutate actors nor spend credits");
            // A naval factory near the coast must remain reachable: no per-type ban.
            factory.eo=410;factory.ep=170;engine.by+=1000;call("GET","/combat/observe",200);
            require("APPROACH_PATH_KNOWN".equals(actor(call("GET","/combat/engagement?unitIds=9001&targetId=9230",200),0).get("status")),"near-coast seaFactory remains targetable by LAND");
            engine.bs.N[20][8]=10;engine.bs.N[25][5]=10;factory.eo=510;factory.ep=110;factory.cu=10;engine.by+=1000;
            call("GET","/combat/observe",200);Map<String,Object> hidden=call("GET","/combat/engagement?unitIds=9001&targetId=9230",200);
            require(Boolean.FALSE.equals(hidden.get("targetVisible"))&&((Number)hidden.get("targetX")).intValue()==410,"hidden target movement not read through feasibility endpoint");
            require("UNKNOWN".equals(actor(hidden,0).get("status")),"fog does not create fresh feasibility proof");
            call("GET","/combat/engagement?unitIds=9230&targetId=9230",409);
            Map<String,Object> investments=call("GET","/economy/investments",200);
            call("GET","/economy/builder-actions?unitId=9002",200);
            Map<?,?> upgrade=(Map<?,?>)((List<?>)investments.get("units")).get(0);
            require(((Number)upgrade.get("cost")).intValue()==1400&&"extractorT2_0".equals(upgrade.get("actionId")),"upgrade price/action from real loaded menu");
            am tier2=unit("extractorT2",9004,engine.bs,450,790);
            Map<String,Object> tier3Menu=call("GET","/economy/investments",200);Map<?,?> tier3=null;
            for(Object item:(List<?>)tier3Menu.get("units"))if(((Number)((Map<?,?>)item).get("id")).longValue()==9004)tier3=(Map<?,?>)item;
            require(tier3!=null&&"extractorT2".equals(tier3.get("type"))&&"extractorT3".equals(tier3.get("product"))
                &&((Number)tier3.get("cost")).intValue()==4000,"T2 menu exports only native T3 upgrade and live4000quote");
            String suffix="&sessionId="+session+"&requestId=";
            engine.cf.b.clear();String order="/command/invest?unitId=9003&actionId=extractorT2_0"+suffix+"upgrade";
            call("POST",order,200);call("POST",order,200);require(engine.cf.b.size()==1,"upgrade idempotency queues once");
            call("POST",order.replace("unitId=9003","unitId=9230").replace("requestId=upgrade","requestId=foreign"),409);
            call("POST",order.replace(session,"foreign-session").replace("requestId=upgrade","requestId=stale"),409);
            call("POST","/command/invest?unitId=9004&actionId="+URLEncoder.encode((String)tier3.get("actionId"),"UTF-8")+suffix+"upgrade3",200);
            require(engine.cf.b.size()==2,"T3 submits ordinary native action");
            engine.cf.b.remove(1);am.bE.remove(tier2);
            Map<String,Object> plan=call("GET","/economy/construction-plan?unitId=9002&type=heavyTank",200);
            String construction="/command/construct?unitId=9002&actionId="+URLEncoder.encode((String)plan.get("actionId"),"UTF-8")+"&x="+plan.get("x")+"&y="+plan.get("y")+suffix+"build";
            call("POST",construction,200);call("POST",construction,200);require(engine.cf.b.size()==2,"engineer construction idempotency queues once");
            com.corrodinggames.rts.gameFramework.e build=(com.corrodinggames.rts.gameFramework.e)engine.cf.b.get(1);
            require("heavyTank".equals(build.j.a().i()),"engineer emits native heavyTank construction waypoint");
            call("GET","/economy/construction-plan?unitId=9002&type=amphibiousJet",200);
            Map<String,Object> scout=call("GET","/scout/observe",200);
            List<?> resources=(List<?>)scout.get("resources");require(!resources.isEmpty(),"fixture exposes native legal resource tiles");
            Map<?,?> resource=(Map<?,?>)resources.get(0);String tile=resource.get("tile").toString();
            Map<String,Object> route=call("GET","/scout/resource-approach?unitId=9002&tile="+tile,200);
            require(Boolean.TRUE.equals(route.get("pathKnown")),"constructor resource route uses known safe native movement grid");
            call("GET","/scout/resource-approach?unitId=9230&tile="+tile,409);
            call("GET","/scout/resource-approach?unitId=9002&tile=32399",409);
            int x=(int)(((Number)plan.get("x")).doubleValue()/20),y=(int)(((Number)plan.get("y")).doubleValue()/20);engine.bs.N[x][y]=10;
            call("POST",construction.replace("requestId=build","requestId=fog"),409);
            require(engine.cf.b.size()==2&&am.bE.size()==before&&credits==engine.bs.o,"planning/submission never fabricates units or deducts funds directly");
            System.out.println("STRATEGY_NATIVE_E2_OK checks="+checks+" noSimulationTicks=true fixtureOnly=true");System.exit(0);
        }catch(Throwable e){e.printStackTrace();System.exit(1);}
    }
}
