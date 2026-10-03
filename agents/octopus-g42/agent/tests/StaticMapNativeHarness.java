import com.corrodinggames.rts.game.units.*;
import io.rwagent.bootstrap.RuntimeBridge;
import io.rwagent.client.Json;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** E2_NATIVE_FIXTURE_WITH_REAL_HTTP / NO_NATURAL_MATCH.
 * Runtime kernel uses loaded static layers only. Native pure terrain d[] is read ONLY by this test.
 * Fog, actors and clock are explicit fixture controls, never desktop operations.
 */
public final class StaticMapNativeHarness {
    static int checks;static final int PORT=47679;static BufferedWriter evidence;
    static com.corrodinggames.rts.game.i engine;
    static void require(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static Field field(Class<?> type,String name)throws Exception{for(Class<?> c=type;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    static Object value(Object o,String name)throws Exception{return field(o.getClass(),name).get(o);}
    static void set(Object o,String name,Object v)throws Exception{field(o.getClass(),name).set(o,v);}
    @SuppressWarnings("unchecked") static Map<String,Object> get(String path,int expected)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+PORT+path).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(15000);
        int status=connection.getResponseCode();InputStream input=status<400?connection.getInputStream():connection.getErrorStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];for(int count;(count=input.read(buffer))>=0;)bytes.write(buffer,0,count);input.close();connection.disconnect();
        String body=new String(bytes.toByteArray(),StandardCharsets.UTF_8);evidence.write("{\"requestPath\":\""+path+"\",\"httpStatus\":"+status+",\"response\":"+body+"}\n");evidence.flush();
        require(status==expected,"HTTP "+path+" expected="+expected+" actual="+status+" "+body);return (Map<String,Object>)Json.parse(body);
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> resources(Map<String,Object> packet){return (List<Map<String,Object>>)packet.get("resourceTiles");}
    static int number(Object o){return ((Number)o).intValue();}
    static Set<Integer> resourceIds(Map<String,Object> packet){Set<Integer> ids=new TreeSet<Integer>();for(Map<String,Object> tile:resources(packet))ids.add(number(tile.get("tile")));return ids;}
    static void boundary(Map<String,Object> packet){require(Boolean.TRUE.equals(packet.get("staticOnly")),"static only");for(String key:new String[]{"dynamicReachability","occupied","buildable","safe"})require("UNKNOWN".equals(packet.get(key)),key+" UNKNOWN");}
    @SuppressWarnings("unchecked") static void compareNative(Object kernel)throws Exception{
        Object snapshot=value(kernel,"snapshot");Map<ao,byte[]> costs=(Map<ao,byte[]>)value(snapshot,"costs");long compared=0;
        for(ao movement:new ao[]{ao.b,ao.c,ao.e,ao.f,ao.g,ao.h}){
            byte[] want=engine.bU.a(movement).d,got=costs.get(movement);require(want.length==got.length,"native cost length "+movement);
            int mismatches=0;for(int at=0;at<want.length;at++)if(want[at]!=got[at])mismatches++;
            require(mismatches==0,"static mirror exactly matches native pure terrain "+movement+" mismatches="+mismatches);compared+=want.length;
        }
        System.out.println("STATIC_MAP_GRID_PASS comparedCells="+compared+" nativeGridReadScope=TEST_ONLY");
    }
    static void bootstrap(String map)throws Exception{
        Method initialize=TerrainNativeCostHarness.class.getDeclaredMethod("initialize",String.class);initialize.setAccessible(true);engine=(com.corrodinggames.rts.game.i)initialize.invoke(null,map);
        Thread pump=new Thread(()->{while(true){Runnable task=(Runnable)engine.k.poll();if(task!=null)task.run();try{Thread.sleep(1);}catch(InterruptedException e){return;}}},"static-map-native-isolated-task-pump");pump.setDaemon(true);pump.start();RuntimeBridge.start(engine,PORT,true);
    }
    static am unit(String type,long id,com.corrodinggames.rts.game.n team,float x,float y){am actor=((ar)ar.a(type)).a(true);actor.eh=id;actor.bX=team;actor.eo=x;actor.ep=y;actor.cm=1;am.bE.add(actor);return actor;}
    static int farthestConnected(byte[] land,int start){int h=engine.bL.D,w=engine.bL.C;int[] queue=new int[land.length];BitSet seen=new BitSet(land.length);int end=1;queue[0]=start;seen.set(start);
        for(int head=0;head<end;head++){int at=queue[head],x=at/h,y=at%h;for(int next:new int[]{x>0?at-h:-1,x+1<w?at+h:-1,y>0?at-1:-1,y+1<h?at+1:-1})if(next>=0&&!seen.get(next)&&land[next]>=0){seen.set(next);queue[end++]=next;}}
        return queue[end-1];}
    static void run(String[] args)throws Exception{
        require(args.length==2,"usage: StaticMapNativeHarness baseline|override OUT");String mode=args[0];Path out=Paths.get(args[1]);Files.createDirectories(out);evidence=Files.newBufferedWriter(out.resolve("static-map-http.jsonl"),StandardCharsets.UTF_8);
        bootstrap("maps/skirmish/"+("override".equals(mode)?"E2_PathingOverride_two_cells.tmx":"[p2]Big Island (2p).tmx"));
        am.bE.clear();engine.cf.b.clear();engine.bs.r=0;engine.bs.o=10000;
        for(byte[] column:engine.bs.N)Arrays.fill(column,(byte)10);
        Map<String,Object> hidden=get("/static-map/observe",200);boundary(hidden);require("KNOWN".equals(hidden.get("status")),"frozen loaded map known");
        Object bridge=field(RuntimeBridge.class,"instance").get(null),kernel=value(bridge,"staticMap");compareNative(kernel);
        Set<Integer> oracle=new TreeSet<Integer>();for(Object p:engine.bL.A)oracle.add(number(value(p,"a"))*engine.bL.D+number(value(p,"b")));
        require(resourceIds(hidden).equals(oracle),"loaded static resource pools match native registered map.A oracle");
        require(resources(hidden).size()==("override".equals(mode)?4:14),"map resource count frozen oracle");
        for(Map<String,Object> tile:resources(hidden)){int at=number(tile.get("tile"));require(((Number)tile.get("x")).doubleValue()==(at/engine.bL.D+.5)*engine.bL.n,"column-major resource x");require(((Number)tile.get("y")).doubleValue()==(at%engine.bL.D+.5)*engine.bL.o,"column-major resource y");}
        require(number(hidden.get("scanCount"))==1&&number(hidden.get("totalScanCount"))==1,"first scan once");
        int target=resourceIds(hidden).iterator().next(),start=-1;byte[] land=engine.bU.a(ao.b).d;
        for(int at=0;at<land.length;at++)if(land[at]>=0&&Math.hypot((at/engine.bL.D-target/engine.bL.D)*engine.bL.n,(at%engine.bL.D-target%engine.bL.D)*engine.bL.o)>=60&&Math.hypot((at/engine.bL.D-target/engine.bL.D)*engine.bL.n,(at%engine.bL.D-target%engine.bL.D)*engine.bL.o)<=120){start=at;break;}
        require(start>=0,"native resource has adjacent LAND rally");float x=(start/engine.bL.D+.5f)*engine.bL.n,y=(start%engine.bL.D+.5f)*engine.bL.o;
        am builder=unit("builder",2,engine.bs,x,y);com.corrodinggames.rts.game.n opponent=new com.corrodinggames.rts.game.e(1,false);opponent.r=1;unit("heavyTank",999,opponent,x,y);
        require(((List<?>)get("/combat/observe",200).get("visibleEnemies")).isEmpty(),"all hidden enemy excluded from combat");
        Map<String,Object> route=get("/static-map/approach?unitId=2&tile="+target,200);boundary(route);require(Boolean.TRUE.equals(route.get("staticPathKnown")),"own builder static connected approach");require(route.get("knowledgeId").equals(hidden.get("knowledgeId")),"route references source knowledge identity");
        require(number(route.get("routeFieldBuildCount"))==1,"first target/domain builds one reverse static route field");
        double rally=Math.hypot(((Number)route.get("x")).doubleValue()-(target/engine.bL.D+.5)*engine.bL.n,((Number)route.get("y")).doubleValue()-(target%engine.bL.D+.5)*engine.bL.o);require(rally>=60&&rally<=120,"resource rally 60..120 world");
        require(land[number(route.get("approachTile"))]>=0,"resource rally pure terrain LAND passable");require(route.get("waypointX") instanceof Number&&route.get("waypointY") instanceof Number,"bounded waypoint fields exist");
        int far=farthestConnected(land,start);builder.eo=(far/engine.bL.D+.5f)*engine.bL.n;builder.ep=(far%engine.bL.D+.5f)*engine.bL.o;
        Map<String,Object> distant=get("/static-map/approach?unitId=2&tile="+target,200);require(Boolean.TRUE.equals(distant.get("staticPathKnown")),"far own actor follows static component through hidden map");
        require(number(distant.get("routeFieldBuildCount"))==1,"changed actor position reuses target/domain field without full BFS");
        require(Math.hypot(((Number)distant.get("waypointX")).doubleValue()-builder.eo,((Number)distant.get("waypointY")).doubleValue()-builder.ep)<=1200.001,"short waypoint bounded by 1200 world static path length");
        if("baseline".equals(mode))require(number(distant.get("distanceTiles"))>60&&!distant.get("approachTile").equals(distant.get("waypointTile")),"long static route returns intermediate waypoint before final rally");builder.eo=x;builder.ep=y;
        unit("heavyTank",3,engine.bs,x,y);Map<String,Object> second=get("/static-map/approach?unitId=3&tile="+target,200);require(number(second.get("routeFieldBuildCount"))==1&&Boolean.TRUE.equals(second.get("staticPathKnown")),"independent second own LAND actor reuses field");
        for(byte[] column:engine.bs.N)Arrays.fill(column,(byte)0);engine.bx++;engine.by+=1000;
        Map<String,Object> visible=get("/static-map/observe?tile="+target,200);require(resourceIds(hidden).equals(resourceIds(visible)),"fog on/off static resources identical");require(hidden.get("knowledgeId").equals(visible.get("knowledgeId")),"fog changes preserve cached knowledge");require(number(visible.get("totalScanCount"))==1,"fog change does not scan map twice");
        require(((List<?>)get("/combat/observe",200).get("visibleEnemies")).size()==1,"visible enemy now exported independently");
        require(engine.cf.b.isEmpty(),"static reads never issue native command");
        get("/static-map/approach?unitId=999&tile="+target,409);builder.cm=.5f;get("/static-map/approach?unitId=2&tile="+target,409);builder.cm=1;
        get("/static-map/observe?tile=-1",400);get("/static-map/observe?unitId=2",400);
        // Existing obstruction arrays are poisoned only in fixture: runtime answers cannot consume them.
        com.corrodinggames.rts.gameFramework.k.i grid=engine.bU.a(ao.b);byte[] oldBuildings=grid.e,oldUnits=grid.f,oldTerrain=grid.d;
        grid.e=new byte[land.length];grid.f=new byte[land.length];grid.d=new byte[land.length];Arrays.fill(grid.e,(byte)-1);Arrays.fill(grid.f,(byte)-1);Arrays.fill(grid.d,(byte)-1);
        Map<String,Object> isolated=get("/static-map/approach?unitId=2&tile="+target,200);require(Boolean.TRUE.equals(isolated.get("staticPathKnown")),"dynamic/native grid poison cannot affect static route");
        // A fresh session must still use only loaded tile layers rather than copying native d[].
        set(bridge,"sessionId",UUID.randomUUID().toString());Map<String,Object> newSession=get("/static-map/observe",200);require(!hidden.get("knowledgeId").equals(newSession.get("knowledgeId")),"new session invalidates identity");require(number(newSession.get("totalScanCount"))==2,"new session one additional scan");
        Map<String,Object> freshRoute=get("/static-map/approach?unitId=2&tile="+target,200);require(Boolean.TRUE.equals(freshRoute.get("staticPathKnown")),"fresh static scan independent of native grids");require(number(freshRoute.get("routeFieldBuildCount"))==1,"new session starts a new target/domain route field");grid.e=oldBuildings;grid.f=oldUnits;grid.d=oldTerrain;compareNative(kernel);
        int queried=0;for(int extra=0;extra<land.length&&queried<9;extra++)if(land[extra]>=0&&extra!=target){get("/static-map/approach?unitId=2&tile="+extra,200);queried++;}
        Map<String,Object> bounded=get("/static-map/observe",200);require(number(bounded.get("routeFieldCacheSize"))==8,"reverse static fields have bounded LRU8 memory");
        if("override".equals(mode)){
            @SuppressWarnings("unchecked") Map<ao,byte[]> costs=(Map<ao,byte[]>)value(value(kernel,"snapshot"),"costs");
            require(engine.bL.x!=null,"actual loaded override fixture");require(costs.get(ao.b)[10*engine.bL.D+10]==0,"override clears Ground water blocking");require(costs.get(ao.b)[55*engine.bL.D+55]==-1,"override introduces hard blocking");
        }
        set(bridge,"gameJarSha256Cache","mismatch-fixture");Map<String,Object> mismatch=get("/static-map/observe",200);require("UNKNOWN".equals(mismatch.get("status"))&&resources(mismatch).isEmpty(),"game SHA mismatch cannot guess static map");Map<String,Object> unknown=get("/static-map/approach?unitId=2&tile="+target,200);require("UNKNOWN".equals(unknown.get("status"))&&unknown.get("knowledgeId")==null,"mismatch cannot return route");
        evidence.close();String summary="{\"status\":\"PASS\",\"checks\":"+checks+",\"case\":\""+mode+"\",\"evidenceClass\":\"E2_NATIVE_FIXTURE_WITH_REAL_HTTP\",\"naturalMatch\":false,\"nativeGridReadScope\":\"TEST_ONLY\",\"resources\":"+oracle.size()+",\"comparedCellsPerPass\":"+(engine.bL.C*engine.bL.D*6)+",\"comparisonPasses\":2,\"desktopOperated\":false}";
        Files.write(out.resolve("static-map-summary.json"),summary.getBytes(StandardCharsets.UTF_8));System.out.println("STATIC_MAP_NATIVE_PASS "+summary);
    }
    public static void main(String[] args){try{run(args);System.exit(0);}catch(Throwable e){e.printStackTrace();System.exit(1);}}
}
