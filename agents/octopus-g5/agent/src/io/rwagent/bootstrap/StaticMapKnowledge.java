package io.rwagent.bootstrap;

import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ao;
import com.corrodinggames.rts.game.units.y;
import com.sun.net.httpserver.HttpServer;
import io.rwagent.client.TargetCatalog;
import java.util.*;
import static io.rwagent.bootstrap.RuntimeBridge.*;

/** Authorized, session-local map prior. The kernel reads ONLY loaded Ground/Items/PathingOverride.
 * No fog, live actors, buildings, native path grid or obstruction arrays enter the snapshot.
 * Static connectivity is deliberately independent of legal live observations and native move guards.
 */
public final class StaticMapKnowledge {
    private final RuntimeBridge bridge;
    private Snapshot snapshot;
    private long totalScans;
    private static final ao[] MOVEMENTS={ao.b,ao.c,ao.e,ao.f,ao.g,ao.h,ao.d,ao.a};
    private static final String BOUNDARY=",\"staticOnly\":true,\"dynamicReachability\":\"UNKNOWN\",\"occupied\":\"UNKNOWN\",\"buildable\":\"UNKNOWN\",\"safe\":\"UNKNOWN\"";

    StaticMapKnowledge(RuntimeBridge bridge){this.bridge=bridge;}
    void install(HttpServer server){
        for(final String path:new String[]{"/static-map/observe","/static-map/approach"})server.createContext(path,exchange->{
            if(!path.equals(exchange.getRequestURI().getPath())){respond(exchange,404,jsonError("unknown endpoint"));return;}
            if(!"GET".equals(exchange.getRequestMethod())){respond(exchange,405,jsonError("GET required"));return;}
            if(exchange.getRequestHeaders().getFirst("Origin")!=null){respond(exchange,403,jsonError("browser-origin requests disabled"));return;}
            try{
                final Map<String,String> query=EconomyBridge.query(exchange.getRequestURI().getRawQuery());
                CommandResult result=bridge.onGameThread(()->{try{return dispatch(path,query);}catch(IllegalArgumentException e){return CommandResult.error(400,e.getMessage());}});
                respond(exchange,result.httpStatus,result.json);
            }catch(IllegalArgumentException e){respond(exchange,400,jsonError(e.getMessage()));}
            catch(Exception e){RwAgent.log("Static map unavailable",e);respond(exchange,503,jsonError("static map unavailable"));}
        });
    }
    private CommandResult dispatch(String path,Map<String,String> query){
        bridge.refreshSession();CommandResult guard=bridge.commandGuard();if(guard!=null)return guard;
        if(bridge.engine.bL==null||bridge.engine.bL.u==null)return CommandResult.error(409,"loaded Ground layer required");
        int width=bridge.engine.bL.C,height=bridge.engine.bL.D;
        if(width<=0||height<=0||(long)width*height>262144)return CommandResult.error(409,"static map limit is 262144 tiles");
        boolean approach=path.equals("/static-map/approach");
        if(approach&&!query.keySet().equals(new HashSet<String>(Arrays.asList("unitId","tile"))))throw new IllegalArgumentException("required: unitId, tile");
        if(!approach&&!Collections.singleton("tile").containsAll(query.keySet()))throw new IllegalArgumentException("optional: tile");
        Integer tile=query.containsKey("tile")?Integer.valueOf(query.get("tile")):null;
        if(tile!=null&&(tile<0||tile>=(long)width*height))throw new IllegalArgumentException("invalid tile");
        if(!TargetCatalog.GAME_SHA256.equals(bridge.gameJarSha256())){
            snapshot=null;return CommandResult.ok(header("UNKNOWN")+",\"reason\":\"GAME_SHA_MISMATCH\",\"knowledgeId\":null,\"resourceTiles\":[],\"staticPathKnown\":false"+BOUNDARY+"}");
        }
        Snapshot data=current();
        if(!approach)return CommandResult.ok(observe(data,tile));
        long id=Long.parseLong(query.get("unitId"));
        // Live actor validation is outside the static kernel; no enemy attributes are sampled.
        y actor=null;
        for(Object raw:am.bE){am unit=(am)raw;if(unit.eh==id&&unit instanceof y&&unit.bX==bridge.engine.bs&&!unit.ej&&!unit.bV&&!unit.cW()&&unit.cm>=1&&((y)unit).I()){actor=(y)unit;break;}}
        if(actor==null)return CommandResult.error(409,"completed own mobile required");
        if(actor.h()==ao.a||actor.h()==ao.c)return CommandResult.error(409,"mobile movement type required");
        return CommandResult.ok(approach(data,actor,tile));
    }
    private String header(String status){return "{\"status\":\""+status+"\",\"sessionId\":\""+bridge.sessionId+"\",\"frame\":"+bridge.engine.bx+",\"gameTimeMs\":"+bridge.engine.by;}
    private Snapshot current(){
        String sha=bridge.gameJarSha256();
        if(snapshot==null||!snapshot.matches(bridge,sha)){
            snapshot=new Snapshot(bridge,sha);totalScans++;
        }
        return snapshot;
    }
    private String common(Snapshot data){
        return ",\"knowledgeId\":\""+data.id+"\",\"mapPath\":\""+escape(String.valueOf(bridge.engine.dl))+"\",\"gameJarSha256\":\""+data.sha+"\",\"scanCount\":1,\"totalScanCount\":"+totalScans+",\"routeFieldBuildCount\":"+data.routeBuilds+",\"routeFieldCacheSize\":"+data.routes.size()
                +",\"sources\":[\"NATIVE_LOADED_GROUND\",\"NATIVE_LOADED_ITEMS\",\"NATIVE_LOADED_PATHING_OVERRIDE\"],\"sourceId\":\"FROZEN_NATIVE_K_I_D_STATIC_MIRROR\",\"costScope\":\"STATIC_TERRAIN_ONLY\",\"bounds\":{\"widthTiles\":"+data.width+",\"heightTiles\":"+data.height+",\"tileWidth\":"+data.tw+",\"tileHeight\":"+data.th+",\"width\":"+(data.width*data.tw)+",\"height\":"+(data.height*data.th)+"}"+BOUNDARY;
    }
    private String observe(Snapshot data,Integer tile){
        StringBuilder json=new StringBuilder(header("KNOWN")).append(common(data)).append(",\"resourceTiles\":[");
        boolean first=true;for(int at:data.resources){if(!first)json.append(',');first=false;json.append(point(data,at));}
        json.append(']');
        if(tile!=null){int at=tile;json.append(",\"tileEvidence\":{").append("\"tile\":").append(at)
                .append(",\"ground\":").append(TerrainSemantics.json(data.ground[at])).append(",\"items\":").append(TerrainSemantics.json(data.items[at]))
                .append(",\"overridePresent\":").append(data.overridePresent.get(at)).append(",\"override\":").append(data.overridePresent.get(at)?TerrainSemantics.json(data.override[at]):"null")
                .append(",\"terrainCost\":{");
            first=true;for(ao movement:MOVEMENTS){if(!first)json.append(',');first=false;json.append('"').append(TerrainSemantics.movementName(movement)).append("\":").append(data.costs.get(movement)[at]);}json.append("}}");
        }
        return json.append('}').toString();
    }
    private static String point(Snapshot data,int at){return "{\"tile\":"+at+",\"x\":"+format((at/data.height+.5)*data.tw)+",\"y\":"+format((at%data.height+.5)*data.th)+"}";}
    private String approach(Snapshot data,y actor,int target){
        String prefix=header("UNKNOWN")+common(data)+",\"unitId\":"+actor.eh+",\"targetTile\":"+target+",\"movementType\":\""+TerrainSemantics.movementName(actor.h())+"\"";
        if(!Float.isFinite(actor.eo)||!Float.isFinite(actor.ep)||actor.eo<0||actor.ep<0||actor.eo>=data.width*data.tw||actor.ep>=data.height*data.th)return prefix+",\"staticPathKnown\":false,\"reason\":\"ACTOR_OUTSIDE_MAP\"}";
        int start=(int)(actor.eo/data.tw)*data.height+(int)(actor.ep/data.th);
        byte[] costs=data.costs.get(actor.h());
        if(costs==null||costs[start]<0)return prefix+",\"staticPathKnown\":false,\"reason\":\"START_STATIC_TERRAIN_BLOCKED\"}";
        // One reverse multi-source field serves every legal own actor with this target/domain.
        // New actor positions traverse at most one short segment; no per-observation full-grid BFS.
        RouteField route=data.route(actor.h(),target);int goal=route.goal[start];boolean resource=data.resources.contains(target);
        if(goal<0)return prefix+",\"staticPathKnown\":false,\"reason\":\"NO_STATIC_CONNECTED_APPROACH\"}";
        int waypoint=start,limit=Math.max(1,1200/Math.max(data.tw,data.th));for(int step=0;step<limit&&route.distance[waypoint]>0;step++)waypoint=route.next[waypoint];
        return header("KNOWN")+common(data)+",\"unitId\":"+actor.eh+",\"targetTile\":"+target+",\"movementType\":\""+TerrainSemantics.movementName(actor.h())+"\",\"approachTile\":"+goal
                +",\"x\":"+format((goal/data.height+.5)*data.tw)+",\"y\":"+format((goal%data.height+.5)*data.th)+",\"waypointTile\":"+waypoint
                +",\"waypointX\":"+format((waypoint/data.height+.5)*data.tw)+",\"waypointY\":"+format((waypoint%data.height+.5)*data.th)
                +",\"distanceTiles\":"+route.distance[start]+",\"staticPathKnown\":true,\"connectivity\":\"FOUR_NEIGHBOR_STATIC_TERRAIN\",\"resourceRally\":"+resource+"}";
    }
    private static final class RouteField {
        final int[] distance,next,goal;
        RouteField(Snapshot data,ao movement,int target){
            int size=data.width*data.height;distance=new int[size];next=new int[size];goal=new int[size];Arrays.fill(distance,-1);Arrays.fill(next,-1);Arrays.fill(goal,-1);
            byte[] cost=data.costs.get(movement);int[] queue=new int[size];int end=0;boolean resource=data.resources.contains(target);
            if(resource){int tx=target/data.height,ty=target%data.height,rx=120/data.tw+1,ry=120/data.th+1;
                for(int x=Math.max(0,tx-rx);x<=Math.min(data.width-1,tx+rx);x++)for(int y=Math.max(0,ty-ry);y<=Math.min(data.height-1,ty+ry);y++){
                    int at=x*data.height+y;double d=Math.hypot((x-tx)*data.tw,(y-ty)*data.th);
                    if(d>=60&&d<=120&&cost[at]>=0&&data.costs.get(ao.b)[at]>=0){distance[at]=0;next[at]=goal[at]=at;queue[end++]=at;}
                }
            }else if(cost[target]>=0){distance[target]=0;next[target]=goal[target]=target;queue[end++]=target;}
            for(int head=0;head<end;head++){int at=queue[head],x=at/data.height,y=at%data.height;
                if(x>0)end=visit(at,at-data.height,cost,queue,end);
                if(x+1<data.width)end=visit(at,at+data.height,cost,queue,end);
                if(y>0)end=visit(at,at-1,cost,queue,end);
                if(y+1<data.height)end=visit(at,at+1,cost,queue,end);
            }
        }
        private int visit(int from,int at,byte[] cost,int[] queue,int end){if(distance[at]<0&&cost[at]>=0){next[at]=from;goal[at]=goal[from];distance[at]=distance[from]+1;queue[end++]=at;}return end;}
    }
    private static final class Tile {final int flags;final byte cost;Tile(int flags,byte cost){this.flags=flags;this.cost=cost;}}
    private static Tile ground(RuntimeBridge bridge,int x,int y){
        if(bridge.engine.bL.u.a(x,y)==null)return new Tile(TerrainSemantics.ABSENT,(byte)0);
        return new Tile(TerrainSemantics.flags(bridge.engine.bL.u.a(x,y).e,bridge.engine.bL.u.a(x,y).f,bridge.engine.bL.u.a(x,y).g,bridge.engine.bL.u.a(x,y).h,bridge.engine.bL.u.a(x,y).k,bridge.engine.bL.u.a(x,y).i,bridge.engine.bL.u.a(x,y).j,bridge.engine.bL.u.a(x,y).l),bridge.engine.bL.u.a(x,y).j);
    }
    private static Tile items(RuntimeBridge bridge,int x,int y){
        if(bridge.engine.bL.e(x,y)==null)return new Tile(TerrainSemantics.ABSENT,(byte)0);
        return new Tile(TerrainSemantics.flags(bridge.engine.bL.e(x,y).e,bridge.engine.bL.e(x,y).f,bridge.engine.bL.e(x,y).g,bridge.engine.bL.e(x,y).h,bridge.engine.bL.e(x,y).k,bridge.engine.bL.e(x,y).i,bridge.engine.bL.e(x,y).j,bridge.engine.bL.e(x,y).l),bridge.engine.bL.e(x,y).j);
    }
    private static Tile override(RuntimeBridge bridge,int x,int y){
        if(bridge.engine.bL.x==null||bridge.engine.bL.x.a(x,y)==null)return new Tile(TerrainSemantics.ABSENT,(byte)0);
        return new Tile(TerrainSemantics.flags(bridge.engine.bL.x.a(x,y).e,bridge.engine.bL.x.a(x,y).f,bridge.engine.bL.x.a(x,y).g,bridge.engine.bL.x.a(x,y).h,bridge.engine.bL.x.a(x,y).k,bridge.engine.bL.x.a(x,y).i,bridge.engine.bL.x.a(x,y).j,bridge.engine.bL.x.a(x,y).l),bridge.engine.bL.x.a(x,y).j);
    }
    /** Immutable scalar copy of authorized static layers. No native path grids or actor references. */
    private static final class Snapshot {
        final String session,sha,id;final Object mapObject;final int width,height,tw,th;
        final int[] ground,items,override;final BitSet overridePresent;
        final Map<ao,byte[]> costs=new HashMap<ao,byte[]>();
        final SortedSet<Integer> resources=new TreeSet<Integer>();
        long routeBuilds;
        final Map<String,RouteField> routes=new LinkedHashMap<String,RouteField>(8,.75f,true){
            protected boolean removeEldestEntry(Map.Entry<String,RouteField> entry){return size()>8;}
        };
        RouteField route(ao movement,int target){String key=TerrainSemantics.movementName(movement)+":"+target;RouteField field=routes.get(key);
            if(field==null){field=new RouteField(this,movement,target);routes.put(key,field);routeBuilds++;}return field;}
        Snapshot(RuntimeBridge bridge,String sha){
            this.session=bridge.sessionId;this.mapObject=bridge.engine.bL;this.sha=sha;id=session+":static-map:"+UUID.randomUUID();width=bridge.engine.bL.C;height=bridge.engine.bL.D;tw=bridge.engine.bL.n;th=bridge.engine.bL.o;
            int size=width*height;ground=new int[size];items=new int[size];override=new int[size];overridePresent=new BitSet(size);
            for(ao movement:MOVEMENTS)costs.put(movement,new byte[size]);
            for(int x=0;x<width;x++)for(int y=0;y<height;y++){
                int at=x*height+y;Tile g=ground(bridge,x,y),i=items(bridge,x,y),o=override(bridge,x,y);boolean present=(o.flags&TerrainSemantics.ABSENT)==0;
                ground[at]=g.flags;items[at]=i.flags;override[at]=o.flags;if(present)overridePresent.set(at);
                if((i.flags&TerrainSemantics.RESOURCE)!=0)resources.add(at);
                for(ao movement:MOVEMENTS)costs.get(movement)[at]=TerrainSemantics.staticCost(movement,g.flags,g.cost,i.flags,i.cost,present,o.flags,o.cost);
            }
        }
        boolean matches(RuntimeBridge bridge,String sha){return this.session.equals(bridge.sessionId)&&mapObject==bridge.engine.bL&&this.sha.equals(sha)&&width==bridge.engine.bL.C&&height==bridge.engine.bL.D&&tw==bridge.engine.bL.n&&th==bridge.engine.bL.o;}
    }
}
