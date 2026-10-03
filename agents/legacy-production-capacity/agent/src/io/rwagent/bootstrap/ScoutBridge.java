package io.rwagent.bootstrap;

import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.y;
import com.corrodinggames.rts.game.units.ao;
import com.corrodinggames.rts.gameFramework.k.i;
import io.rwagent.client.TargetCatalog;
import io.rwagent.client.EngagementGeometry;
import com.sun.net.httpserver.HttpServer;
import java.util.*;
import static io.rwagent.bootstrap.RuntimeBridge.*;

/** Read-only frontier planning. Hidden terrain and hidden resource tiles are never read. */
final class ScoutBridge {
    private final RuntimeBridge bridge;
    private String memorySession;
    private BitSet seen,initialVisible,visibleNow;
    /** Session-local own vision history only. No hidden terrain or enemy state is sampled here. */
    private long[] lastVisibleGameMs,lastRevealGameMs;
    private byte[] lastRevealFrom;
    private static final long STALE_FOG_MS=45000,DEEP_FOG_MS=120000;
    private final Map<Integer,Integer> resources=new TreeMap<Integer,Integer>();
    private final Map<Long,Threat> rememberedThreats=new LinkedHashMap<Long,Threat>();
    private List<Threat> visibleThreats=new ArrayList<Threat>();
    private final Map<ao,byte[]> knownPassability=new HashMap<ao,byte[]>();
    /** Terrain is separate from remembered combined native obstruction and route reachability. */
    private final Map<ao,byte[]> knownTerrainCost=new HashMap<ao,byte[]>();
    private int[] groundFlags,itemFlags,overrideFlags;
    private boolean[] hasOverride;
    private long[] terrainObservedAt;
    private boolean terrainRulesTrusted;
    private int visibleCount,newCount;

    /** Fast-path radius, also used by EconomyBridge site reachability. The ladder may exceed it. */
    static final int DEFAULT_RADIUS_WORLD=1200;
    private static final String CAUSE_EXHAUSTED="KNOWN_REACHABLE_FRONTIER_EXHAUSTED";
    private static final String CAUSE_BLOCKED_BY_THREATS="SEARCH_BLOCKED_BY_KNOWN_THREATS";
    /**
     * {radiusWorld (0 = unlimited), potentialThreshold, useSoftAvoid}. Escalated inside one call.
     * There is deliberately no intermediate 2400 step: measured on a 110x110 fixture the whole
     * ladder including the exhaustive pass costs 2.7-7.6 ms, so a map-sized radius is not worth a
     * map-dependent magic number to skip.
     */
    private static final int[][] SEARCH_LADDER={
        {DEFAULT_RADIUS_WORLD,12,1},
        {DEFAULT_RADIUS_WORLD,6,1},
        {DEFAULT_RADIUS_WORLD,3,1},
        {DEFAULT_RADIUS_WORLD,1,1},
        {0,1,0}
    };
    private static final String[] LADDER_NAMES={
        "FAST_LOCAL","RELAXED_LOCAL_6","RELAXED_LOCAL_3","RELAXED_LOCAL_1","GLOBAL_EXHAUSTIVE"
    };

    /**
     * Per-level reason. Only the terminal level turns this into a final verdict. A frontier here is
     * a reachable tile of known passability whose window still covers at least one unseen tile, so
     * the number of reachable-and-unseen tiles is structurally zero and is deliberately not a cause.
     */
    private static String levelCause(int radius,int reachableTiles,int inDistanceBand,int afterAvoid,int afterPotential) {
        if(reachableTiles==0)return "NO_REACHABLE_TILES";
        if(inDistanceBand==0)return radius>0?"DISTANCE_LIMIT":"NO_TILE_BEYOND_NEAR_RADIUS";
        if(afterAvoid==0)return "AVOID_FILTER";
        if(afterPotential==0)return "POTENTIAL_FILTER";
        return "NO_CANDIDATE_SELECTED";
    }

    ScoutBridge(RuntimeBridge bridge) { this.bridge=bridge; }
    void refreshEngagementTerrain(Collection<y> actors){
        observe();Set<ao> movements=new HashSet<ao>();
        for(y actor:actors)movements.add(actor.h());
        for(ao movement:movements)if(movement!=ao.d&&movement!=ao.a)recordPassability(movement);
    }
    EngagementGeometry.Field engagementField(y actor,double x,double y,double range,double radius,boolean water){
        int w=bridge.engine.bL.C,h=bridge.engine.bL.D;
        byte[] costs=terrainRulesTrusted?knownTerrainCost.get(actor.h()):null;
        // AIR terrain is an explicit movement rule, not permission to observe hidden terrain.
        if(terrainRulesTrusted&&actor.h()==ao.d)costs=new byte[w*h];
        boolean[] wet=new boolean[w*h];
        for(int at=0;at<wet.length;at++)wet[at]=seen.get(at)&&(groundFlags[at]&TerrainSemantics.WATER)!=0;
        return EngagementGeometry.prepare(w,h,bridge.engine.bL.n,bridge.engine.bL.o,costs,wet,x,y,range,radius,water);
    }
    void install(HttpServer server) {
        for(final String path:new String[]{"/scout/observe","/scout/plan","/scout/visible","/scout/resource-approach"})server.createContext(path,exchange -> {
            if(!path.equals(exchange.getRequestURI().getPath())) {respond(exchange,404,jsonError("unknown endpoint"));return;}
            if(!"GET".equals(exchange.getRequestMethod())) {respond(exchange,405,jsonError("GET required"));return;}
            if(exchange.getRequestHeaders().getFirst("Origin")!=null) {respond(exchange,403,jsonError("browser-origin requests disabled"));return;}
            try {
                final Map<String,String> q=EconomyBridge.query(exchange.getRequestURI().getRawQuery());
                CommandResult r=bridge.onGameThread(() -> {
                    try{return dispatch(path,q);}catch(IllegalArgumentException error){return CommandResult.error(400,error.getMessage());}
                });respond(exchange,r.httpStatus,r.json);
            }catch(IllegalArgumentException error) {respond(exchange,400,jsonError(error.getMessage()));}
            catch(Exception error) {RwAgent.log("Scout observation failed",error);respond(exchange,503,jsonError("scout observation unavailable"));}
        });
    }
    private CommandResult dispatch(String path,Map<String,String> q) {
        bridge.refreshSession();CommandResult guard=bridge.commandGuard();if(guard!=null)return guard;
        if(bridge.engine.bL==null || bridge.engine.bU==null)return CommandResult.error(409,"map or path engine unavailable");
        if((long)bridge.engine.bL.C*bridge.engine.bL.D>262144)return CommandResult.error(409,"scout map limit is 262144 tiles");
        if(path.equals("/scout/resource-approach"))return resourceApproach(q);
        if(path.equals("/scout/observe")) {
            if(!new HashSet<String>(Arrays.asList("tile","since")).containsAll(q.keySet())
                    ||q.containsKey("since")&&!q.containsKey("tile"))
                throw new IllegalArgumentException("optional fields: tile, since (requires tile)");
            Integer tile=q.containsKey("tile")?Integer.valueOf(q.get("tile")):null;
            Long since=q.containsKey("since")?Long.valueOf(q.get("since")):null;
            if(tile!=null&&(tile.intValue()<0||tile.intValue()>=(long)bridge.engine.bL.C*bridge.engine.bL.D))
                throw new IllegalArgumentException("invalid tile");
            if(since!=null&&since.longValue()<0)throw new IllegalArgumentException("invalid since");
            observe();return CommandResult.ok(observation(tile,since));
        }
        if(path.equals("/scout/visible")) {
            // Read-only fog answer for specific tiles: a search objective may only be consumed once
            // its tile is legally visible again. No terrain, unit or hidden-tile data is exposed.
            if(!q.keySet().equals(Collections.singleton("tiles")))throw new IllegalArgumentException("required: tiles");
            String[] parts=q.get("tiles").split(",",-1);
            if(parts.length<1 || parts.length>32)throw new IllegalArgumentException("1..32 tiles required");
            int width=bridge.engine.bL.C,height=bridge.engine.bL.D;
            StringBuilder json=new StringBuilder("{\"status\":\"observed\",\"sessionId\":\"").append(bridge.sessionId)
                    .append("\",\"frame\":").append(bridge.engine.bx).append(",\"gameTimeMs\":").append(bridge.engine.by).append(",\"tiles\":[");
            for(int index=0;index<parts.length;index++) {
                int tile=Integer.parseInt(parts[index]);
                if(tile<0 || tile>=(long)width*height)throw new IllegalArgumentException("invalid tile");
                float x=(tile/height+.5f)*bridge.engine.bL.n,y=(tile%height+.5f)*bridge.engine.bL.o;
                if(index>0)json.append(',');
                json.append("{\"tile\":").append(tile).append(",\"x\":").append(format(x)).append(",\"y\":").append(format(y))
                        .append(",\"visible\":").append(bridge.engine.bL.a(x,y,bridge.engine.bs)).append('}');
            }
            return CommandResult.ok(json.append("]}").toString());
        }
        if(!new HashSet<String>(Arrays.asList("unitId","avoid","role")).containsAll(q.keySet()))throw new IllegalArgumentException("optional fields: unitId, avoid, role");
        boolean army="army".equals(q.get("role"));
        boolean recon="recon".equals(q.get("role"));
        if(q.containsKey("role") && !army && !recon)throw new IllegalArgumentException("role must be army or recon");
        Long wanted=q.containsKey("unitId")?Long.valueOf(q.get("unitId")):null;
        Set<Integer> avoid=new HashSet<Integer>();
        if(q.containsKey("avoid") && !q.get("avoid").isEmpty()) {
            String[] parts=q.get("avoid").split(",",-1);
            if(parts.length>48)throw new IllegalArgumentException("at most 48 avoided tiles");
            for(String part:parts) {
                int tile=Integer.parseInt(part);if(tile<0 || tile>=bridge.engine.bL.C*bridge.engine.bL.D)throw new IllegalArgumentException("invalid avoided tile");
                avoid.add(tile);
            }
        }
        y worker=null;am[] units=am.bE.a();
        for(int index=0;index<am.bE.size();index++) {
            am u=units[index];
            if(u instanceof y && u.bX==bridge.engine.bs && !u.ej && !u.bV && !u.cW() && u.cm>=1
                    && ((y)u).I() && (wanted==null || wanted==u.eh) && ((army||recon)?u.l():EconomyBridge.expansionBuilder(u))) {worker=(y)u;break;}
        }
        if(worker==null)return CommandResult.error(409,(army||recon)?"no completed own armed mobile":"no completed own mobile extractor builder");
        observe();recordPassability(worker.h());
        List<Threat> threatList=army?Collections.<Threat>emptyList():new ArrayList<Threat>(rememberedThreats.values());
        byte[] known=knownPassability.get(worker.h());
        byte[] terrainCost=knownTerrainCost.get(worker.h());
        double homeX=worker.eo,homeY=worker.ep;
        for(int index=0;index<am.bE.size();index++) {
            am u=units[index];if(u!=null && u.bX==bridge.engine.bs && !u.ej && !u.bV && "commandCenter".equals(u.r().i())) {
                homeX=u.eo;homeY=u.ep;break;
            }
        }
        int mobileUnits=0;
        for(int index=0;index<am.bE.size();index++) {
            am u=units[index];
            if(u==null || u.bX!=bridge.engine.bs || u.ej || u.bV || u.cW() || u.cm<1 || !(u instanceof y) || !((y)u).I())continue;
            if((army||recon) && !u.l())continue;
            mobileUnits++;
        }
        // Soft heuristics decide which frontier is preferred. They may never decide that no frontier
        // exists, so every level below is retried inside THIS call before the search may give up.
        // radiusWorld 0 means "no distance limit"; useSoftAvoid false drops the soft avoid veto.
        // Hard constraints (map bounds, known-impassable tiles, movement type, known threats and the
        // legal fog rule that hidden tiles are only entered when already known passable) stay on.
        final int[][] ladder=SEARCH_LADDER;
        long planStartNanos=System.nanoTime();
        int mapWidth=bridge.engine.bL.C,mapHeight=bridge.engine.bL.D,mapStride=mapHeight+1;
        // A summed-area table contains only observed/unobserved flags, never unknown terrain.
        int[] unknown=new int[(mapWidth+1)*mapStride];
        int[] info=recon?new int[unknown.length]:null;
        int[] never=recon?new int[unknown.length]:null;
        int[] deep=recon?new int[unknown.length]:null;
        int[] stale=recon?new int[unknown.length]:null;
        for(int c=0;c<mapWidth;c++)for(int r=0;r<mapHeight;r++) {
            int at=(c+1)*mapStride+r+1;
            unknown[at]=(seen.get(c*mapHeight+r)?0:1)+unknown[at-1]+unknown[at-mapStride]-unknown[at-mapStride-1];
            if(recon){
                int category=memoryCategory(c*mapHeight+r,bridge.engine.by);
                int newTile=category==4?1:0,deepTile=category==3?1:0,staleTile=category==2?1:0;
                info[at]=newTile*4+deepTile*3+staleTile+info[at-1]+info[at-mapStride]-info[at-mapStride-1];
                never[at]=newTile+never[at-1]+never[at-mapStride]-never[at-mapStride-1];
                deep[at]=deepTile+deep[at-1]+deep[at-mapStride]-deep[at-mapStride-1];
                stale[at]=staleTile+stale[at-1]+stale[at-mapStride]-stale[at-mapStride-1];
            }
        }
        VisibleGrid grid=null;int gridRadius=Integer.MIN_VALUE,bfsRuns=0,dangerBlockedTiles=0;
        int best=-1,gain=0,bestInfo=0,bestNever=0,bestDeep=0,bestStale=0;double score=-1;
        int unknownTilesTotal=mapWidth*mapHeight-seen.cardinality();
        int reachableTiles=0,inDistanceBand=0,afterAvoid=0,afterPotential=0,maxPotential=0;
        int fallbackLevel=0;String finalCause=CAUSE_EXHAUSTED;
        StringBuilder attempts=new StringBuilder("[");
        for(int level=0;level<ladder.length;level++) {
            int radius=ladder[level][0],threshold=ladder[level][1];boolean softAvoid=ladder[level][2]!=0;
            if(grid==null || gridRadius!=radius) {
                grid=new VisibleGrid(bridge,worker,threatList,known,radius,terrainCost,effectiveTerrainFlags());
                gridRadius=radius;bfsRuns++;dangerBlockedTiles=grid.dangerBlockedTiles;
            }
            reachableTiles=grid.reachable.size();inDistanceBand=0;afterAvoid=0;afterPotential=0;maxPotential=0;
            best=-1;score=-1;gain=0;bestInfo=bestNever=bestDeep=bestStale=0;
            for(int at:grid.reachable) {
                int c=at/grid.height,r=at%grid.height;
                double distance=grid.distance[at]*Math.max(grid.tw,grid.th);
                if(distance<120 || (radius>0 && distance>radius))continue;
                inDistanceBand++;
                if(softAvoid) {
                    boolean excluded=false;
                    for(int old:avoid)if(Math.abs(c-old/grid.height)<=3 && Math.abs(r-old%grid.height)<=3){excluded=true;break;}
                    if(excluded)continue;
                }
                afterAvoid++;
                int a=Math.max(0,c-8),b=Math.max(0,r-8),cc=Math.min(grid.width,c+9),dd=Math.min(grid.height,r+9);
                int potential=recon?rectangle(info,mapStride,a,b,cc,dd)
                    :rectangle(unknown,mapStride,a,b,cc,dd);
                if(potential>maxPotential)maxPotential=potential;
                if(potential<threshold)continue;
                afterPotential++;
                // Expand around the known home base before sending an economic worker far away.
                double homeDistance=Math.hypot((c+.5)*grid.tw-homeX,(r+.5)*grid.th-homeY);
                // A known positive terrain cost is preference evidence, never a reachability proof.
                double terrainPenalty=grid.terrainPenalty[at]*2.0;
                double value=recon?potential*(1+Math.min(homeDistance,2000)/4000.0)/(120.0+distance+terrainPenalty)
                    :potential/(120.0+distance+terrainPenalty+(army?0:homeDistance*homeDistance/160.0));
                if(value>score){
                    score=value;best=at;
                    gain=rectangle(unknown,mapStride,a,b,cc,dd);
                    if(recon){bestInfo=potential;bestNever=rectangle(never,mapStride,a,b,cc,dd);
                        bestDeep=rectangle(deep,mapStride,a,b,cc,dd);bestStale=rectangle(stale,mapStride,a,b,cc,dd);}
                }
            }
            String levelCause=best>=0?"CANDIDATE_SELECTED":levelCause(radius,reachableTiles,inDistanceBand,afterAvoid,afterPotential);
            if(attempts.length()>1)attempts.append(',');
            attempts.append("{\"level\":").append(level).append(",\"name\":\"").append(LADDER_NAMES[level])
                .append("\",\"radiusWorld\":").append(radius).append(",\"potentialThreshold\":").append(threshold)
                .append(",\"softAvoid\":").append(softAvoid)
                .append(",\"reachableTiles\":").append(reachableTiles)
                .append(",\"distanceFiltered\":").append(reachableTiles-inDistanceBand)
                .append(",\"avoidFiltered\":").append(inDistanceBand-afterAvoid)
                .append(",\"potentialFiltered\":").append(afterAvoid-afterPotential)
                .append(",\"candidates\":").append(afterPotential)
                .append(",\"maxPotentialSeen\":").append(maxPotential)
                .append(",\"cause\":\"").append(levelCause).append("\"}");
            fallbackLevel=level;
            if(best>=0)break;
        }
        attempts.append(']');
        // finalCause describes this call's outcome, so a successful plan must not claim exhaustion.
        if(best>=0)finalCause="CANDIDATE_SELECTED";
        else finalCause=dangerBlockedTiles>0?CAUSE_BLOCKED_BY_THREATS:CAUSE_EXHAUSTED;
        long planMicros=(System.nanoTime()-planStartNanos)/1000L;
        String diagnostics=",\"anchorUnitId\":"+worker.eh+",\"anchorUnitType\":\""+escape(worker.r().i())+"\""
                +",\"anchorX\":"+format(worker.eo)+",\"anchorY\":"+format(worker.ep)
                +",\"mobileUnitCount\":"+mobileUnits
                +",\"fallbackLevel\":"+fallbackLevel+",\"fallbackName\":\""+LADDER_NAMES[fallbackLevel]+"\""
                +",\"finalCause\":\""+finalCause+"\""+",\"attempts\":"+attempts
                +",\"bfsRuns\":"+bfsRuns+",\"bfsVisitedTiles\":"+reachableTiles+",\"dangerBlockedTiles\":"+dangerBlockedTiles
                +",\"terrainBlockedTiles\":"+grid.terrainBlockedTiles
                +",\"terrainBlockedReasons\":"+grid.terrainBlockedReasonsJson()
                +",\"dynamicOrNativeBlockedTiles\":"+grid.dynamicOrNativeBlockedTiles
                +",\"unknownTerrainTilesSkipped\":"+grid.unknownTerrainTilesSkipped
                +",\"movementType\":\""+TerrainSemantics.movementName(worker.h())+"\""
                +",\"terrainSemanticStatus\":\""+(terrainRulesTrusted?"VERIFIED_FROZEN_GAME":"UNKNOWN_GAME_SHA_MISMATCH")+"\""
                +",\"terrainSourceId\":\""+TerrainSemantics.SOURCE_ID+"\""
                +",\"frontierPlanDurationMicros\":"+planMicros
                +",\"reachableTiles\":"+reachableTiles+",\"unknownTilesTotal\":"+unknownTilesTotal
                +",\"maxPotentialSeen\":"+maxPotential+",\"potentialWindowRadiusTiles\":8";
        if(best<0) {
            return CommandResult.ok("{\"status\":\"no_frontier\",\"sessionId\":\""+bridge.sessionId
                    +"\",\"builderId\":"+worker.eh+",\"reason\":\""+(recon?"NO_REACHABLE_DEEP_OR_STALE_FRONTIER":"NO_REACHABLE_UNVISITED_FRONTIER")+"\""
                    +diagnostics+"}");
        }
        List<Integer> pathTiles=new ArrayList<Integer>();for(int at=best;at!=-1;at=grid.parent[at])pathTiles.add(at);
        Collections.reverse(pathTiles);int frontier=best;
        int maxStepWorld=recon?260:640;
        if(grid.distance[best]*Math.max(grid.tw,grid.th)>maxStepWorld) {
            int length=maxStepWorld/Math.max(grid.tw,grid.th)+1;
            pathTiles=new ArrayList<Integer>(pathTiles.subList(0,length));best=pathTiles.get(pathTiles.size()-1);
        }
        StringBuilder route=new StringBuilder("[");
        boolean currentlyVisible=true;
        for(int tile:pathTiles)if(!bridge.engine.bL.a((tile/grid.height+.5f)*grid.tw,(tile%grid.height+.5f)*grid.th,bridge.engine.bs))currentlyVisible=false;
        for(int j=0;j<pathTiles.size();j++){if(j>0)route.append(',');route.append(pathTiles.get(j));}route.append(']');
        return CommandResult.ok("{\"status\":\"planned\",\"sessionId\":\""+bridge.sessionId+"\",\"builderId\":"+worker.eh
            +",\"targetX\":"+format((best/grid.height+.5)*grid.tw)+",\"targetY\":"+format((best%grid.height+.5)*grid.th)
            +",\"targetTile\":"+best+",\"frontierTile\":"+frontier+",\"pathLength\":"+grid.distance[best]*Math.max(grid.tw,grid.th)
            +",\"pathKnown\":true,\"pathVisible\":"+currentlyVisible+",\"routeTiles\":"+route
            +",\"routeTerrain\":"+routeTerrainJson(pathTiles,worker.h())
            +",\"potentialUnknownTiles\":"+gain
            +",\"exploredTiles\":"+seen.cardinality()
            +(recon?",\"frontierMemoryClass\":\""+(bestNever>0?"NEVER_SEEN_EDGE":bestDeep>0?"DEEP_FOG":"STALE_FOG")+"\""
                    +",\"potentialInfoScore\":"+bestInfo+",\"potentialNeverSeenTiles\":"+bestNever
                    +",\"potentialDeepFogTiles\":"+bestDeep+",\"potentialStaleFogTiles\":"+bestStale:"")
            +diagnostics+"}");
    }
    private static int rectangle(int[] prefix,int stride,int a,int b,int c,int d){
        return prefix[c*stride+d]-prefix[a*stride+d]-prefix[c*stride+b]+prefix[a*stride+b];
    }
    private CommandResult resourceApproach(Map<String,String> q){
        if(!q.keySet().equals(new HashSet<String>(Arrays.asList("unitId","tile"))))throw new IllegalArgumentException("required: unitId,tile");
        long id=Long.parseLong(q.get("unitId"));int tile=Integer.parseInt(q.get("tile"));
        if(tile<0||tile>=(long)bridge.engine.bL.C*bridge.engine.bL.D)throw new IllegalArgumentException("invalid tile");
        y worker=null;am[] live=am.bE.a();
        for(int index=0;index<am.bE.size();index++){
            am u=live[index];if(u instanceof y&&u.eh==id&&u.bX==bridge.engine.bs&&!u.ej&&!u.bV&&!u.cW()&&u.cm>=1
                    &&((y)u).I()&&EconomyBridge.expansionBuilder(u)){worker=(y)u;break;}
        }
        if(worker==null)return CommandResult.error(409,"completed own mobile extractor constructor required");
        observe();if(!resources.containsKey(tile))return CommandResult.error(409,"resource was never legally observed");
        recordPassability(worker.h());
        VisibleGrid grid=new VisibleGrid(bridge,worker,new ArrayList<Threat>(rememberedThreats.values()),
                knownPassability.get(worker.h()),0,knownTerrainCost.get(worker.h()),effectiveTerrainFlags());
        double x=(tile/grid.height+.5)*grid.tw,y=(tile%grid.height+.5)*grid.th;
        int best=-1;
        for(int at:grid.reachable)if(Math.hypot((at/grid.height+.5)*grid.tw-x,(at%grid.height+.5)*grid.th-y)<=60
                &&(best<0||grid.distance[at]<grid.distance[best]))best=at;
        if(best<0)return CommandResult.error(409,"no known safe approach to remembered resource");
        return CommandResult.ok("{\"status\":\"planned\",\"sessionId\":\""+bridge.sessionId+"\",\"unitId\":"+id
                +",\"tile\":"+tile+",\"x\":"+format((best/grid.height+.5)*grid.tw)+",\"y\":"+format((best%grid.height+.5)*grid.th)
                +",\"pathKnown\":true,\"movementType\":\""+TerrainSemantics.movementName(worker.h())
                +"\",\"distanceTiles\":"+grid.distance[best]+",\"gameTimeMs\":"+bridge.engine.by+"}");
    }
    private void observe() {
        int h=bridge.engine.bL.D,w=bridge.engine.bL.C;boolean first=!bridge.sessionId.equals(memorySession);
        terrainRulesTrusted=TargetCatalog.GAME_SHA256.equals(bridge.gameJarSha256());
        if(first){
            memorySession=bridge.sessionId;seen=new BitSet(w*h);visibleNow=new BitSet(w*h);
            lastVisibleGameMs=new long[w*h];lastRevealGameMs=new long[w*h];lastRevealFrom=new byte[w*h];
            groundFlags=new int[w*h];itemFlags=new int[w*h];overrideFlags=new int[w*h];
            hasOverride=new boolean[w*h];terrainObservedAt=new long[w*h];Arrays.fill(terrainObservedAt,-1);
            Arrays.fill(lastVisibleGameMs,-1);Arrays.fill(lastRevealGameMs,-1);
            resources.clear();rememberedThreats.clear();knownPassability.clear();knownTerrainCost.clear();
        }
        BitSet previouslyVisible=(BitSet)visibleNow.clone();visibleNow.clear();
        visibleCount=0;newCount=0;
        for(int c=0;c<w;c++)for(int r=0;r<h;r++) {
            float x=(c+.5f)*bridge.engine.bL.n,yy=(r+.5f)*bridge.engine.bL.o;
            // This visibility check must precede both resource and path-cost reads.
            if(!bridge.engine.bL.a(x,yy,bridge.engine.bs))continue;
            int tile=c*h+r;visibleCount++;visibleNow.set(tile);
            if(!previouslyVisible.get(tile)){
                lastRevealFrom[tile]=(byte)memoryCategoryWithoutCurrent(tile,bridge.engine.by);
                lastRevealGameMs[tile]=bridge.engine.by;
            }
            lastVisibleGameMs[tile]=bridge.engine.by;
            if(!seen.get(tile)){seen.set(tile);newCount++;}
            if(bridge.engine.bL.e(c,r)!=null && bridge.engine.bL.e(c,r).i
                    && !resources.containsKey(tile))resources.put(tile,bridge.engine.bx);
            if(terrainRulesTrusted){
                groundFlags[tile]=readGroundFlags(c,r);
                itemFlags[tile]=readItemFlags(c,r);
                hasOverride[tile]=bridge.engine.bL.x!=null&&bridge.engine.bL.x.a(c,r)!=null;
                overrideFlags[tile]=hasOverride[tile]?readOverrideFlags(c,r):0;
            }
            terrainObservedAt[tile]=bridge.engine.by;
        }
        if(first)initialVisible=(BitSet)seen.clone();
        Set<ao> movements=new HashSet<ao>(knownPassability.keySet());
        for(ao movement:new ao[]{ao.b,ao.f}){
            try { if(bridge.engine.bU.a(movement)!=null)movements.add(movement); }
            catch(RuntimeException unavailable) { /* observation still has legal tile flags */ }
        }
        // Terrain cost is optional for an observation. Some loaded maps/fixtures have no path grid
        // yet; leave that movement's cost UNKNOWN instead of breaking the fog observation endpoint.
        for(ao movement:movements)
            try { recordPassability(movement); }
            catch(RuntimeException unavailable) { /* the planning path still requires its grid */ }
        visibleThreats=threats(bridge);Set<Long> visibleIds=new HashSet<Long>();
        for(Threat threat:visibleThreats){rememberedThreats.put(threat.id,threat);visibleIds.add(threat.id);}
        Iterator<Threat> old=rememberedThreats.values().iterator();
        while(old.hasNext()) {
            Threat t=old.next();if(visibleIds.contains(t.id))continue;
            if(bridge.engine.bL.a(t.x,t.y,bridge.engine.bs) || (!t.building && bridge.engine.by-t.time>20000))old.remove();
        }
    }
    private int readGroundFlags(int c,int r){
        if(bridge.engine.bL.u==null||bridge.engine.bL.u.a(c,r)==null)return 0;
        return TerrainSemantics.flags(
                bridge.engine.bL.u.a(c,r).e,bridge.engine.bL.u.a(c,r).f,
                bridge.engine.bL.u.a(c,r).g,bridge.engine.bL.u.a(c,r).h,
                bridge.engine.bL.u.a(c,r).k,bridge.engine.bL.u.a(c,r).i,
                bridge.engine.bL.u.a(c,r).j,bridge.engine.bL.u.a(c,r).l);
    }
    private int readItemFlags(int c,int r){
        if(bridge.engine.bL.e(c,r)==null)return 0;
        return TerrainSemantics.flags(
                bridge.engine.bL.e(c,r).e,bridge.engine.bL.e(c,r).f,
                bridge.engine.bL.e(c,r).g,bridge.engine.bL.e(c,r).h,
                bridge.engine.bL.e(c,r).k,bridge.engine.bL.e(c,r).i,
                bridge.engine.bL.e(c,r).j,bridge.engine.bL.e(c,r).l);
    }
    private int readOverrideFlags(int c,int r){
        return TerrainSemantics.flags(
                bridge.engine.bL.x.a(c,r).e,bridge.engine.bL.x.a(c,r).f,
                bridge.engine.bL.x.a(c,r).g,bridge.engine.bL.x.a(c,r).h,
                bridge.engine.bL.x.a(c,r).k,bridge.engine.bL.x.a(c,r).i,
                bridge.engine.bL.x.a(c,r).j,bridge.engine.bL.x.a(c,r).l);
    }
    private void recordPassability(ao movement) {
        int w=bridge.engine.bL.C,h=bridge.engine.bL.D;
        byte[] known=knownPassability.get(movement);if(known==null){known=new byte[w*h];knownPassability.put(movement,known);}
        i costs=bridge.engine.bU.a(movement);if(costs==null)throw new IllegalStateException("native movement costs unavailable");
        byte[] terrain=knownTerrainCost.get(movement);
        if(terrain==null){terrain=new byte[w*h];Arrays.fill(terrain,Byte.MIN_VALUE);knownTerrainCost.put(movement,terrain);}
        for(int c=0;c<w;c++)for(int r=0;r<h;r++) {
            if(!bridge.engine.bL.a((c+.5f)*bridge.engine.bL.n,(r+.5f)*bridge.engine.bL.o,bridge.engine.bs))continue;
            int tile=c*h+r;
            known[tile]=(byte)(bridge.engine.bU.a(costs,c,r)?1:2);
            if(costs.d!=null&&tile<costs.d.length)terrain[tile]=costs.d[tile];
        }
    }
    private int[] effectiveTerrainFlags(){
        int[] result=new int[groundFlags.length];
        for(int tile=0;tile<result.length;tile++)
            if(seen.get(tile))result[tile]=hasOverride[tile]?overrideFlags[tile]:(groundFlags[tile]|itemFlags[tile]);
        return result;
    }
    private String terrainTileJson(int tile){
        if(!seen.get(tile))return "{\"tile\":"+tile+",\"status\":\"UNKNOWN\"}";
        if(!terrainRulesTrusted)return "{\"tile\":"+tile+",\"status\":\"UNKNOWN\",\"reason\":\"GAME_SHA_MISMATCH\"}";
        return "{\"tile\":"+tile+",\"status\":\"KNOWN\",\"currentlyVisible\":"+visibleNow.get(tile)
                +",\"observedAtGameTimeMs\":"+terrainObservedAt[tile]
                +",\"ground\":"+TerrainSemantics.json(groundFlags[tile])
                +",\"items\":"+TerrainSemantics.json(itemFlags[tile])
                +",\"overridePresent\":"+hasOverride[tile]
                +",\"override\":"+(hasOverride[tile]?TerrainSemantics.json(overrideFlags[tile]):"null")
                +",\"costInputFlags\":"+TerrainSemantics.json(hasOverride[tile]?overrideFlags[tile]:(groundFlags[tile]|itemFlags[tile]))
                +",\"terrainCost\":{\"LAND\":"+terrainCostJson(ao.b,tile)+",\"HOVER\":"+terrainCostJson(ao.f,tile)+"}"
                +",\"costScope\":\"NATIVE_TERRAIN_D_ONLY_NOT_REACHABILITY\""
                +",\"sourceId\":\""+TerrainSemantics.SOURCE_ID+"\"}";
    }
    private String terrainCostJson(ao movement,int tile){
        byte[] costs=knownTerrainCost.get(movement);
        return costs==null||costs[tile]==Byte.MIN_VALUE?"null":Byte.toString(costs[tile]);
    }
    private String routeTerrainJson(List<Integer> path,ao movement){
        byte[] costs=knownTerrainCost.get(movement);
        int[] flags=effectiveTerrainFlags();int water=0,cliff=0,large=0,lava=0,smallRock=0,costSum=0,unknown=0;
        long newest=-1;
        for(int tile:path){
            int f=flags[tile];
            if((f&TerrainSemantics.WATER)!=0)water++;
            if((f&TerrainSemantics.CLIFF)!=0)cliff++;
            if((f&TerrainSemantics.LARGE_OBSTACLE)!=0)large++;
            if((f&TerrainSemantics.LAVA)!=0)lava++;
            if((f&TerrainSemantics.SMALL_ROCK)!=0)smallRock++;
            newest=Math.max(newest,terrainObservedAt[tile]);
            if(!seen.get(tile)||costs==null||costs[tile]==Byte.MIN_VALUE)unknown++;
            else if(costs[tile]>0)costSum+=costs[tile];
        }
        return "{\"status\":\""+(!terrainRulesTrusted?"UNKNOWN_RULES":unknown==0?"KNOWN":"PARTIAL_UNKNOWN")
                +"\",\"scope\":\"NATIVE_TERRAIN_D_ONLY_NOT_REACHABILITY\",\"movementType\":\""
                +TerrainSemantics.movementName(movement)+"\",\"waterTiles\":"+water+",\"cliffTiles\":"+cliff
                +",\"largeCliffOrTreesTiles\":"+large+",\"lavaTiles\":"+lava+",\"smallRockTiles\":"+smallRock
                +",\"positiveTerrainCostSum\":"+costSum+",\"unknownCostTiles\":"+unknown
                +",\"lastLegalObservationGameTimeMs\":"+newest+",\"sourceId\":\""+TerrainSemantics.SOURCE_ID+"\"}";
    }
    /** 0 visible, 1 recent fog, 2 stale fog, 3 deep fog, 4 never seen. */
    private int memoryCategory(int tile,long now){
        return visibleNow.get(tile)?0:memoryCategoryWithoutCurrent(tile,now);
    }
    private int memoryCategoryWithoutCurrent(int tile,long now){
        if(!seen.get(tile))return 4;
        long age=Math.max(0,now-lastVisibleGameMs[tile]);
        return age<STALE_FOG_MS?1:age<DEEP_FOG_MS?2:3;
    }
    private String observation(Integer regionTile,Long since) {
        int[] categories=new int[5];
        for(int tile=0;tile<lastVisibleGameMs.length;tile++)categories[memoryCategory(tile,bridge.engine.by)]++;
        int h=bridge.engine.bL.D;StringBuilder j=new StringBuilder("{\"status\":\"observed\",\"sessionId\":\"").append(bridge.sessionId)
            .append("\",\"frame\":").append(bridge.engine.bx).append(",\"fogEnabled\":").append(bridge.engine.bL.E)
            .append(",\"lineOfSightFog\":").append(bridge.engine.bL.F).append(",\"visibleTiles\":").append(visibleCount)
            .append(",\"exploredTiles\":").append(seen.cardinality()).append(",\"initialVisibleTiles\":").append(initialVisible.cardinality())
            .append(",\"newlyObservedTiles\":").append(newCount)
            .append(",\"mapMemoryCurrentVisibleTiles\":").append(categories[0])
            .append(",\"mapMemoryRecentFogTiles\":").append(categories[1])
            .append(",\"mapMemoryStaleFogTiles\":").append(categories[2])
            .append(",\"mapMemoryDeepFogTiles\":").append(categories[3])
            .append(",\"mapMemoryNeverSeenTiles\":").append(categories[4])
            .append(",\"mapMemoryStaleAfterMs\":").append(STALE_FOG_MS)
            .append(",\"mapMemoryDeepAfterMs\":").append(DEEP_FOG_MS)
            .append(",\"resources\":[");
        boolean first=true;for(Map.Entry<Integer,Integer> resource:resources.entrySet()) {
            int tile=resource.getKey();float x=(tile/h+.5f)*bridge.engine.bL.n,yy=(tile%h+.5f)*bridge.engine.bL.o;
            if(!first)j.append(',');first=false;
            j.append("{\"tile\":").append(tile).append(",\"x\":").append(format(x)).append(",\"y\":").append(format(yy))
             .append(",\"firstSeenFrame\":").append(resource.getValue()).append(",\"initiallyVisible\":").append(initialVisible.get(tile))
             .append(",\"currentlyVisible\":").append(bridge.engine.bL.a(x,yy,bridge.engine.bs)).append('}');
        }
        j.append("],\"visibleThreats\":[");first=true;
        for(Threat threat:visibleThreats){if(!first)j.append(',');first=false;j.append(threat.json());}
        j.append("],\"rememberedThreats\":[");first=true;
        for(Threat threat:rememberedThreats.values()){if(!first)j.append(',');first=false;j.append(threat.json());}
        j.append(']');
        if(regionTile!=null){
            j.append(",\"region\":").append(regionJson(regionTile.intValue(),since));
            j.append(",\"terrainTile\":").append(terrainTileJson(regionTile.intValue()));
        }
        return j.append('}').toString();
    }
    private String regionJson(int tile,Long since){
        int height=bridge.engine.bL.D,width=bridge.engine.bL.C;
        int c=tile/height,r=tile%height;int[] counts=new int[5];int revealDeep=0,revealStale=0;long latestReveal=-1;
        for(int x=Math.max(0,c-8);x<Math.min(width,c+9);x++)
            for(int y=Math.max(0,r-8);y<Math.min(height,r+9);y++){
                int at=x*height+y;counts[memoryCategory(at,bridge.engine.by)]++;
                if(since!=null&&lastRevealGameMs[at]>since.longValue()){
                    if(lastRevealFrom[at]==4||lastRevealFrom[at]==3){revealDeep++;latestReveal=Math.max(latestReveal,lastRevealGameMs[at]);}
                    else if(lastRevealFrom[at]==2){revealStale++;latestReveal=Math.max(latestReveal,lastRevealGameMs[at]);}
                }
            }
        return "{\"tile\":"+tile+",\"sampleGameTimeMs\":"+bridge.engine.by
            +",\"currentVisibleTiles\":"+counts[0]+",\"recentFogTiles\":"+counts[1]
            +",\"staleFogTiles\":"+counts[2]+",\"deepFogTiles\":"+counts[3]
            +",\"neverSeenTiles\":"+counts[4]
            +",\"revealedDeepOrNeverSince\":"+revealDeep+",\"revealedStaleSince\":"+revealStale
            +",\"latestRelevantRevealGameTimeMs\":"+latestReveal+"}";
    }

    // Native team.c(other) is hostile alliance; unit.d(player) applies current fog visibility.
    static List<Threat> threats(RuntimeBridge bridge) {
        List<Threat> result=new ArrayList<Threat>();am[] units=am.bE.a();
        for(int index=0;index<am.bE.size();index++) {
            am u=units[index];if(u==null || u.bX==null || u.bX==bridge.engine.bs || !bridge.engine.bs.c(u.bX))continue;
            if(!u.d(bridge.engine.bs) || !bridge.engine.bL.a(u.eo,u.ep,bridge.engine.bs))continue;
            if(u.ej || u.bV || u.cW() || !(u instanceof y) || !u.l())continue;
            result.add(new Threat(u.eh,u.eo,u.ep,((y)u).m(),u.bI(),u.r().i(),bridge.engine.by));
        }
        return result;
    }
    static final class Threat {
        final long id;final float x,y,range;final boolean building;final String type;final int time;
        Threat(long id,float x,float y,float range,boolean building,String type,int time) {
            this.id=id;this.x=x;this.y=y;this.range=range;this.building=building;this.type=type;this.time=time;
        }
        String json(){return "{\"id\":"+id+",\"x\":"+format(x)+",\"y\":"+format(y)+",\"range\":"+format(range)
            +",\"building\":"+building+",\"type\":\""+escape(type)+"\",\"lastSeenGameTimeMs\":"+time+"}";}
    }

    /** Four-neighbour flood fill; optional memory was populated ONLY while each cell was visible. */
    static final class VisibleGrid {
        final int width,height,tw,th;final int[] distance,parent,terrainPenalty;final List<Integer> reachable=new ArrayList<Integer>();
        /** Tiles a remembered threat kept out of the grid, so a later level can explain a stop. */
        int dangerBlockedTiles,terrainBlockedTiles,dynamicOrNativeBlockedTiles,unknownTerrainTilesSkipped;
        final Map<String,Integer> terrainBlockedReasons=new TreeMap<String,Integer>();
        VisibleGrid(RuntimeBridge bridge,y worker) {
            this(bridge,worker,threats(bridge),null,DEFAULT_RADIUS_WORLD,null,null);
        }
        VisibleGrid(RuntimeBridge bridge,y worker,List<Threat> threats,byte[] known) {
            this(bridge,worker,threats,known,DEFAULT_RADIUS_WORLD,null,null);
        }
        VisibleGrid(RuntimeBridge bridge,y worker,List<Threat> threats,byte[] known,int radiusWorld) {
            this(bridge,worker,threats,known,radiusWorld,null,null);
        }
        VisibleGrid(RuntimeBridge bridge,y worker,List<Threat> threats,byte[] known,int radiusWorld,
                    byte[] terrainCost,int[] terrainFlags) {
            width=bridge.engine.bL.C;height=bridge.engine.bL.D;tw=bridge.engine.bL.n;th=bridge.engine.bL.o;
            if(width<=0 || height<=0 || tw<=0 || th<=0 || (long)width*height>262144)throw new IllegalArgumentException("unsupported scout grid");
            distance=new int[width*height];parent=new int[width*height];terrainPenalty=new int[width*height];
            Arrays.fill(distance,-1);Arrays.fill(parent,-1);
            int sc=(int)(worker.eo/tw),sr=(int)(worker.ep/th);
            if(sc<0 || sr<0 || sc>=width || sr>=height)return;
            i costs=bridge.engine.bU.a(worker.h());if(costs==null)throw new IllegalStateException("native movement costs unavailable");
            int root=sc*height+sr;distance[root]=0;reachable.add(root);
            for(int cursor=0;cursor<reachable.size();cursor++) {
                int at=reachable.get(cursor),c=at/height,r=at%height;
                if(radiusWorld>0 && distance[at]*Math.max(tw,th)>=radiusWorld)continue;
                for(int direction=0;direction<4;direction++) {
                    int nc=c+(direction==0?1:direction==1?-1:0),nr=r+(direction==2?1:direction==3?-1:0);
                    if(nc<0 || nr<0 || nc>=width || nr>=height)continue;
                    int next=nc*height+nr;if(distance[next]>=0)continue;
                    boolean visible=bridge.engine.bL.a((nc+.5f)*tw,(nr+.5f)*th,bridge.engine.bs);
                    if(!visible && (known==null || known[next]!=2)){unknownTerrainTilesSkipped++;continue;}
                    boolean danger=false;
                    for(Threat threat:threats)if(Math.hypot((nc+.5f)*tw-threat.x,(nr+.5f)*th-threat.y)<threat.range+140){danger=true;break;}
                    if(danger){dangerBlockedTiles++;continue;}
                    byte nativeTerrain=terrainCost==null?Byte.MIN_VALUE:terrainCost[next];
                    if(nativeTerrain==-1){
                        terrainBlockedTiles++;
                        String reason=TerrainSemantics.blockReason(terrainFlags==null?0:terrainFlags[next],worker.h());
                        Integer count=terrainBlockedReasons.get(reason);
                        terrainBlockedReasons.put(reason,Integer.valueOf(count==null?1:count.intValue()+1));
                        continue;
                    }
                    if(visible && bridge.engine.bU.a(costs,nc,nr)){dynamicOrNativeBlockedTiles++;continue;}
                    distance[next]=distance[at]+1;parent[next]=at;
                    terrainPenalty[next]=terrainPenalty[at]+Math.max(0,nativeTerrain);
                    reachable.add(next);
                }
            }
        }
        String terrainBlockedReasonsJson(){
            StringBuilder json=new StringBuilder("{");boolean first=true;
            for(Map.Entry<String,Integer> e:terrainBlockedReasons.entrySet()){
                if(!first)json.append(',');first=false;
                json.append('"').append(e.getKey()).append("\":").append(e.getValue());
            }
            return json.append('}').toString();
        }
        boolean reaches(float x,float y,int range) {
            for(int tile:reachable)if(Math.hypot((tile/height+.5f)*tw-x,(tile%height+.5f)*th-y)<=range)return true;
            return false;
        }
    }
}
