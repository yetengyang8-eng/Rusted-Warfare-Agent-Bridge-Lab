package io.rwagent.client;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded two-lane economic controller. Each lane has at most one in-flight order. */
public final class DevelopmentClient {
    private final int port=Integer.getInteger("rwagent.port",47653);
    private final Map<Long,String> required=new LinkedHashMap<Long,String>();
    private BufferedWriter log;
    private FileOutputStream reportOutput;
    private String session,phase="setup",expansion="SEARCHING";
    private long started=System.nanoTime(),lastFrame=-1,frameChanged=started;
    private int commands,observations,mines,tanks;
    private Job mineJob,tankJob;
    private Map<String,Object> minePlan;
    private Long builder;
    private boolean mineClosed;
    private long mineBudgetAt,tankBudgetAt;
    private double pendingMineCost;
    private final boolean explore;
    private ScoutJob scoutJob;
    private int scoutMoves,scoutArrivals,scoutBlocked,scoutedMines,maxScoutMoves=24;
    private long initialExplored=-1,exploredTiles;
    private final Set<Long> initialResources=new HashSet<Long>(),scoutDiscoveries=new HashSet<Long>();
    private final List<Long> avoided=new ArrayList<Long>();
    private Map<String,Object> visibility;
    private int scoutRetreats;
    private double refugeX,refugeY;
    private boolean retreating,refugeReady;
    private final List<Long> tankIds=new ArrayList<Long>();
    private final Set<Long> escorts=new LinkedHashSet<Long>(),confirmedEscorts=new LinkedHashSet<Long>();
    private long regroupAt=-1;
    private int escortTarget;

    DevelopmentClient(){this(false);}
    DevelopmentClient(boolean explore){this.explore=explore;}

    public static void main(String[] args){System.exit(new DevelopmentClient().run(args));}

    int run(String[] args) {
        String outcome="FAIL",reason="not started";int exit=1;
        File report=null;
        try {
            if(args.length>(explore?3:2))throw new IllegalArgumentException("Usage: tanks 1..30, mines 0..10, optional exploration moves 1..48");
            int target=args.length>0?Integer.parseInt(args[0]):8,limit=args.length>1?Integer.parseInt(args[1]):3;
            if(target<1 || target>30 || limit<0 || limit>10)throw new IllegalArgumentException("tanks must be 1..30; mines 0..10");
            escortTarget=Math.min(3,target);
            if(args.length>2)maxScoutMoves=Integer.parseInt(args[2]);
            if(maxScoutMoves<1 || maxScoutMoves>48)throw new IllegalArgumentException("scout moves must be 1..48");
            File directory=new File("rw-agent-reports");
            if(!directory.isDirectory() && !directory.mkdirs())throw new IOException("Cannot create reports directory");
            try(RandomAccessFile lockFile=new RandomAccessFile(new File(directory,"economy.lock"),"rw");
                FileChannel channel=lockFile.getChannel();FileLock lock=channel.tryLock()) {
                if(lock==null)throw new IllegalStateException("Another economy controller is already running");
                report=new File(directory,(explore?"frontier-":"development-")+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8)+".jsonl");
                reportOutput=ReportFiles.open(report);
                log=new BufferedWriter(new OutputStreamWriter(reportOutput,StandardCharsets.UTF_8));
                System.out.println("Report: "+report.getAbsolutePath());
                if(!"0.07-alpha1".equals(get("/health","health").get("version")))throw new IllegalStateException("Install 0.07-alpha1 and restart the game");
                Map<String,Object> state=observe();session=text(state,"sessionId");
                if(explore)updateVisibility();
                Map<String,Object> plan=get("/economy/production-plan","production_plan");checkSession(plan);
                long factoryId=id(plan,"factoryId");String factoryType=text(plan,"factoryType"),product=text(plan,"productType");
                double cost=positive(plan,"tankCost");required.put(factoryId,"factory");
                event("targets","{\"newTanks\":"+target+",\"maxNewMines\":"+limit+",\"requireMineTarget\":"+explore+",\"maxScoutMoves\":"+maxScoutMoves+"}");
                System.out.println("Reusing factory "+factoryId+". New tanks: "+target+"; new visible mines: up to "+limit);
                while(true) {
                    phase="observe";state=observe();
                    if(explore)updateVisibility();
                    Map<String,Object> factory=find(state,factoryId);
                    if(!factoryType.equals(factory.get("type")) || num(factory,"buildProgress")<1)throw new IllegalStateException("Factory type or completion changed");
                    int queue=(int)num(factory,"productionQueue");
                    if(queue<0 || queue>1)throw new IllegalStateException("Factory queue unavailable or contains other orders");
                    if(tankJob!=null) {
                        phase="tank_observe";Map<String,Object> done=completeTank(state,queue);
                        if(done!=null){tanks++;required.put(id(done,"id"),"tank "+tanks);tankIds.add(id(done,"id"));event("tank_completed",unit(done));tankJob=null;System.out.println("Tanks completed: "+tanks+"/"+target);}
                    } else if(queue!=0)throw new IllegalStateException("Unexpected external factory order");
                    if(mineJob!=null) {
                        phase="mine_observe";Map<String,Object> done=completeMine(state);
                        if(done!=null){mines++;required.put(id(done,"id"),"extractor "+mines);
                            if(mineJob.scouted)scoutedMines++;
                            event("extractor_completed",unit(done).replace("}",",\"discoveredByScouting\":"+mineJob.scouted+",\"resourceTile\":"+mineJob.resourceTile+"}"));
                            mineJob=null;minePlan=null;System.out.println("Mines completed: "+mines+"/"+limit);}
                    }
                    if(explore && builder!=null && !mineClosed && !retreating && scoutJob==null && threatened(find(state,builder))) {
                        if(mineJob!=null) {
                            event("mine_interrupted","{\"reason\":\"VISIBLE_THREAT_OR_DAMAGE\",\"unitId\":"+(mineJob.seen==null?"null":mineJob.seen)+"}");
                            mineJob=null;finishExpansion("THREAT_INTERRUPTED_BUILD");
                        }
                        minePlan=null;beginRetreat(state,find(state,builder));
                    }
                    if(scoutJob!=null)observeScout(state);
                    if(explore && builder!=null && !mineClosed)assignEscorts(state);
                    if(!mineClosed && mineJob==null && minePlan==null && scoutJob==null) {
                        if(mines>=limit){mineClosed=true;expansion="LIMIT_REACHED";event("expansion_finished","{\"reason\":\"LIMIT_REACHED\"}");}
                        else {
                            phase="mine_plan";minePlan=nextMine();
                            if(minePlan!=null) {
                                checkSession(minePlan);builder=id(minePlan,"builderId");required.put(builder,"builder");
                                rememberRefuge(find(state,builder));
                                positive(minePlan,"extractorCost");text(minePlan,"extractorType");
                            } else if(explore && !mineClosed)startScout(state);
                        }
                    }
                    if(tanks==target && tankJob==null && mineClosed && mineJob==null && scoutJob==null) {
                        observe();outcome=explore && mines<limit?"PARTIAL":"PASS";
                        reason="Tank target completed; mines "+mines+"/"+limit+"; expansion "+expansion+"; all task units alive";
                        exit=outcome.equals("PASS")?0:2;break;
                    }
                    double credits=num(object(state.get("player")),"credits");
                    // A submitted build may not have reached the site or charged its cost yet.
                    if(mineJob!=null && mineJob.seen==null)credits-=pendingMineCost;
                    if(tankJob!=null && !tankJob.queueSeen)credits-=cost;
                    // Reserve an unsubmitted mine's full cost so continuous production cannot starve expansion.
                    if(minePlan!=null && mineJob==null) {
                        double mineCost=positive(minePlan,"extractorCost");
                        if(credits>=mineCost) {
                            phase="mine_order";String type=text(minePlan,"extractorType");
                            pendingMineCost=mineCost;mineJob=new Job(type,ids(state,type),num(minePlan,"extractorX"),num(minePlan,"extractorY"));
                            if(explore)tagDiscovery(mineJob);
                            Map<String,Object> receipt=post("/command/build-extractor?unitId="+builder+"&x="+mineJob.x+"&y="+mineJob.y);
                            requireType(receipt,type);mineJob.x=num(receipt,"targetX");mineJob.y=num(receipt,"targetY");credits-=mineCost;mineBudgetAt=0;
                        } else {mineBudgetAt=budget(mineBudgetAt,"mine",mineCost,credits);}
                    }
                    if(tanks<target && tankJob==null) {
                        double reserve=minePlan!=null && mineJob==null?positive(minePlan,"extractorCost"):0;
                        if(credits>=cost+reserve) {
                            phase="tank_order";tankJob=new Job(product,ids(state,product),num(factory,"x"),num(factory,"y"));
                            requireType(post("/command/produce-tank?unitId="+factoryId),product);tankBudgetAt=0;
                        } else {tankBudgetAt=budget(tankBudgetAt,"tank",cost+reserve,credits);}
                    }
                    Thread.sleep(explore?Math.max(100,Math.min(1000,Integer.getInteger("rwagent.pollMs",500))):500);
                }
            }
        } catch(Exception error){reason=error.toString();System.err.println(reason);}
        finally {
            if(log!=null)try {
                event("summary","{\"outcome\":"+Json.quote(outcome)+",\"reason\":"+Json.quote(reason)+",\"phase\":"+Json.quote(phase)
                    +",\"commands\":"+commands+",\"observations\":"+observations+",\"completedMines\":"+mines+",\"completedTanks\":"+tanks+",\"expansionStatus\":"+Json.quote(expansion)
                    +",\"scoutMoves\":"+scoutMoves+",\"scoutArrivals\":"+scoutArrivals+",\"scoutBlocked\":"+scoutBlocked+",\"scoutedMines\":"+scoutedMines
                    +",\"scoutRetreats\":"+scoutRetreats
                    +",\"escortsAssigned\":"+escorts.size()+",\"escortsConfirmed\":"+confirmedEscorts.size()
                    +",\"newlyExploredTiles\":"+(initialExplored<0?0:exploredTiles-initialExplored)+"}");ReportFiles.finish(log,reportOutput,report);
            }catch(Exception error){exit=1;System.err.println("Report write failed: "+error);}
        }
        System.out.println(outcome+": "+reason);
        if(report!=null)System.out.println("Send back: "+report.getAbsolutePath());
        return exit;
    }

    private Map<String,Object> nextMine() throws Exception {
        String path=(explore?"/expansion/plan":"/opening/plan")+(builder==null?"":"?unitId="+builder);
        AgentClient.Response r=AgentClient.request("GET",address(path));
        Map<String,Object> result=object(Json.parse(r.body));
        // Only the explicit no-site response ends optional expansion; all other failures stop the controller.
        if(r.status==409 && result.get("message") instanceof String && ((String)result.get("message")).startsWith("no legal opening site;")) {
            if(explore){expansion="SCOUTING";event("no_visible_mine",r.body);}
            else {mineClosed=true;expansion="NO_VISIBLE_LEGAL_SITE";event("expansion_finished",r.body);}return null;
        }
        if(r.status!=200){event("http_error","{\"status\":"+r.status+",\"body\":"+Json.quote(r.body)+"}");throw new IOException("HTTP "+r.status+": "+r.body);}
        event("mine_plan",r.body);return result;
    }
    private void updateVisibility() throws Exception {
        visibility=get("/scout/observe","scout_visibility");checkSession(visibility);
        long count=id(visibility,"exploredTiles");
        if(initialExplored<0) {
            initialExplored=count;
            for(Object value:(List<?>)visibility.get("resources"))initialResources.add(id(object(value),"tile"));
        } else if(count<exploredTiles)throw new IllegalStateException("Exploration memory reset");
        exploredTiles=count;
        if(scoutJob!=null && !retreating)for(Object value:(List<?>)visibility.get("resources")) {
            Map<String,Object> site=object(value);long tile=id(site,"tile");
            if(!initialResources.contains(tile) && id(site,"firstSeenFrame")>=scoutJob.frame
                    && Boolean.TRUE.equals(site.get("currentlyVisible")) && scoutDiscoveries.add(tile)) {
                event("scout_discovery","{\"resourceTile\":"+tile+",\"x\":"+num(site,"x")+",\"y\":"+num(site,"y")
                    +",\"scoutMove\":"+scoutMoves+",\"firstSeenFrame\":"+id(site,"firstSeenFrame")+"}");
            }
        }
    }
    private void tagDiscovery(Job job) {
        for(Object value:(List<?>)visibility.get("resources")) {
            Map<String,Object> site=object(value);
            if(Math.hypot(num(site,"x")-job.x,num(site,"y")-job.y)<20) {
                job.resourceTile=id(site,"tile");job.scouted=scoutDiscoveries.contains(job.resourceTile);return;
            }
        }
    }
    private void finishExpansion(String reason) throws Exception {
        mineClosed=true;expansion=reason;event("expansion_finished","{\"reason\":"+Json.quote(reason)+"}");
    }
    private void startScout(Map<String,Object> state) throws Exception {
        if(scoutMoves>=maxScoutMoves){finishExpansion("SCOUT_MOVE_LIMIT");return;}
        if(scoutBlocked>=3){finishExpansion("SCOUT_BLOCKED_LIMIT");return;}
        StringBuilder query=new StringBuilder();if(builder!=null)query.append("unitId=").append(builder);
        if(!avoided.isEmpty()) {
            if(query.length()>0)query.append('&');query.append("avoid=");
            for(int index=0;index<avoided.size();index++){if(index>0)query.append(',');query.append(avoided.get(index));}
        }
        Map<String,Object> plan=get("/scout/plan"+(query.length()==0?"":"?"+query),"scout_plan");checkSession(plan);
        if("no_frontier".equals(plan.get("status"))){finishExpansion("NO_REACHABLE_FRONTIER");return;}
        if(!"planned".equals(plan.get("status")) || !Boolean.TRUE.equals(plan.get("pathKnown")))throw new IllegalStateException("Scout path is not confirmed observed");
        builder=id(plan,"builderId");required.put(builder,"builder");Map<String,Object> worker=find(state,builder);
        if(worker==null || dead(worker))throw new IllegalStateException("Scout builder unavailable");
        if(num(worker,"hp")<num(worker,"maxHp")*.85){finishExpansion("BUILDER_DAMAGED");return;}
        if(scoutRetreats>=3){finishExpansion("SCOUT_THREAT_LIMIT");return;}
        int close=0;for(long escort:confirmedEscorts) {
            Map<String,Object> tank=find(state,escort);
            if(tank!=null && Math.hypot(num(tank,"x")-num(worker,"x"),num(tank,"y")-num(worker,"y"))<=220)close++;
        }
        if(close<Math.min(2,escortTarget)) {
            if(regroupAt<0){regroupAt=id(state,"gameTimeMs");event("escort_regroup","{\"nearby\":"+close+"}");}
            if(id(state,"gameTimeMs")-regroupAt>60000)finishExpansion("ESCORT_REGROUP_TIMEOUT");
            return;
        }
        regroupAt=-1;
        rememberRefuge(worker);
        ScoutJob job=new ScoutJob(plan,worker,id(state,"gameTimeMs"),id(state,"frame"));
        phase="scout_order";post("/command/move?unitId="+builder+"&x="+job.x+"&y="+job.y);
        scoutMoves++;scoutJob=job;
        event("scout_started","{\"move\":"+scoutMoves+",\"unitId\":"+builder+",\"targetTile\":"+job.tile
                +",\"startX\":"+job.startX+",\"startY\":"+job.startY+",\"targetX\":"+job.x+",\"targetY\":"+job.y+",\"frame\":"+job.frame+"}");
        System.out.println("Scout move "+scoutMoves+"/"+maxScoutMoves+" to ("+job.x+", "+job.y+")");
    }
    private void assignEscorts(Map<String,Object> state)throws Exception {
        for(long tank:tankIds) {
            if(escorts.size()>=escortTarget)break;
            if(!escorts.contains(tank)) {
                post("/command/guard?unitId="+tank+"&targetId="+builder);escorts.add(tank);
                event("escort_assigned","{\"unitId\":"+tank+",\"targetId\":"+builder+"}");
            }
        }
        for(long tank:escorts) {
            Map<String,Object> unit=find(state,tank);
            if(!confirmedEscorts.contains(tank) && unit!=null && "guard".equals(unit.get("orderType"))
                    && unit.get("guardTargetId") instanceof Number && id(unit,"guardTargetId")==builder) {
                confirmedEscorts.add(tank);event("escort_confirmed","{\"unitId\":"+tank+",\"targetId\":"+builder+"}");
            }
        }
    }
    private void observeScout(Map<String,Object> state) throws Exception {
        ScoutJob job=scoutJob;Map<String,Object> worker=find(state,builder);long time=id(state,"gameTimeMs");
        double x=num(worker,"x"),y=num(worker,"y"),distance=Math.hypot(x-job.x,y-job.y);
        if(!retreating && threatened(worker)){beginRetreat(state,worker);return;}
        if(distance+8<job.best){job.best=distance;job.progressAt=time;}
        if(distance<=30) {
            if(retreating) {
                retreating=false;scoutJob=null;event("scout_retreat_arrived","{\"unitId\":"+builder+",\"distance\":"+distance+"}");
                if(num(worker,"hp")<num(worker,"maxHp")*.85)finishExpansion("BUILDER_DAMAGED");
                else if(threatened(worker))finishExpansion("THREAT_AT_REFUGE");
                return;
            }
            double displacement=Math.hypot(x-job.startX,y-job.startY);
            if(displacement<60)throw new IllegalStateException("Scout arrival without meaningful displacement");
            scoutArrivals++;avoided.add(job.tile);
            event("scout_arrived","{\"move\":"+scoutMoves+",\"unitId\":"+builder+",\"targetTile\":"+job.tile
                    +",\"distance\":"+distance+",\"displacement\":"+displacement+",\"exploredTiles\":"+exploredTiles+"}");
            scoutJob=null;
        } else if(time-job.progressAt>12000 || time-job.gameStart>60000 || seconds(job.wallStart)>90) {
            scoutBlocked++;if(job.tile>=0)avoided.add(job.tile);scoutJob=null;
            // Replace the outstanding move with a bounded hold at the observed position.
            post("/command/move?unitId="+builder+"&x="+x+"&y="+y);
            event("scout_blocked","{\"move\":"+scoutMoves+",\"targetTile\":"+job.tile+",\"distance\":"+distance+"}");
            if(retreating){retreating=false;finishExpansion("RETREAT_BLOCKED");}
        }
    }
    private void rememberRefuge(Map<String,Object> worker) {
        if(!refugeReady && worker!=null){refugeX=num(worker,"x");refugeY=num(worker,"y");refugeReady=true;}
    }
    private boolean threatened(Map<String,Object> worker) {
        if(worker==null)throw new IllegalStateException("Builder disappeared");
        if(num(worker,"hp")<num(worker,"maxHp")*.85)return true;
        for(Object value:(List<?>)visibility.get("visibleThreats")) {
            Map<String,Object> threat=object(value);
            if(Math.hypot(num(worker,"x")-num(threat,"x"),num(worker,"y")-num(threat,"y"))<num(threat,"range")+160)return true;
        }
        return false;
    }
    private void beginRetreat(Map<String,Object> state,Map<String,Object> worker)throws Exception {
        rememberRefuge(worker);long oldTile=scoutJob==null?-1:scoutJob.tile;
        if(oldTile>=0)avoided.add(oldTile);scoutRetreats++;retreating=true;
        post("/command/move?unitId="+builder+"&x="+refugeX+"&y="+refugeY);
        Map<String,Object> refuge=new HashMap<String,Object>();refuge.put("targetX",refugeX);refuge.put("targetY",refugeY);refuge.put("targetTile",oldTile);
        scoutJob=new ScoutJob(refuge,worker,id(state,"gameTimeMs"),id(state,"frame"));
        event("scout_retreat","{\"move\":"+scoutMoves+",\"unitId\":"+builder+",\"targetX\":"+refugeX+",\"targetY\":"+refugeY+",\"hp\":"+num(worker,"hp")+"}");
    }
    private Map<String,Object> completeMine(Map<String,Object> state) throws Exception {
        Job j=mineJob;if(seconds(j.started)>240)throw new IllegalStateException("Mine completion timeout (240s)");
        List<Map<String,Object>> found=matches(state,j,35,false);
        if(found.size()>1)throw new IllegalStateException("Ambiguous new extractors");
        Map<String,Object> u=found.isEmpty()?null:found.get(0);
        if(j.seen!=null && (u==null || id(u,"id")!=j.seen))throw new IllegalStateException("Observed extractor disappeared");
        if(u!=null) {
            if(j.seen==null){j.seen=id(u,"id");event("extractor_started",unit(u));}
            if(num(u,"buildProgress")>=1)return u;
        }
        return null;
    }
    private Map<String,Object> completeTank(Map<String,Object> state,int queue) throws Exception {
        Job j=tankJob;if(seconds(j.started)>120)throw new IllegalStateException("Tank completion timeout (120s)");
        if(queue>0 && !j.queueSeen){j.queueSeen=true;event("production_started","{\"queue\":"+queue+"}");}
        if(j.queueSeen && queue==0) {
            List<Map<String,Object>> found=matches(state,j,200,true);
            if(found.size()>1)throw new IllegalStateException("Ambiguous new tanks");
            if(found.size()==1)return found.get(0);
            if(j.emptyAt==0)j.emptyAt=System.nanoTime();
            if(seconds(j.emptyAt)>5)throw new IllegalStateException("Queue ended without a new tank nearby");
        } else j.emptyAt=0;
        return null;
    }
    private List<Map<String,Object>> matches(Map<String,Object> state,Job j,double radius,boolean completed) {
        List<Map<String,Object>> result=new ArrayList<Map<String,Object>>();
        for(Map<String,Object> u:units(state))if(j.type.equals(u.get("type")) && !j.old.contains(id(u,"id")) && !dead(u)
            && (!completed || num(u,"buildProgress")>=1) && Math.hypot(num(u,"x")-j.x,num(u,"y")-j.y)<=radius)result.add(u);
        return result;
    }
    private long budget(long since,String lane,double need,double have)throws Exception {
        if(since==0){event("budget_wait","{\"lane\":"+Json.quote(lane)+",\"required\":"+need+",\"credits\":"+have+"}");return System.nanoTime();}
        if(seconds(since)>120)throw new IllegalStateException(lane+" budget timeout (120s)");return since;
    }
    private Map<String,Object> observe()throws Exception {
        if(seconds(started)>600)throw new IllegalStateException("Overall timeout (600s)");
        Map<String,Object> s=get("/state","observation");observations++;
        if(!"running".equals(s.get("status")) || s.get("player")==null)throw new IllegalStateException("No active local match");
        if(Boolean.TRUE.equals(s.get("networked")) || Boolean.TRUE.equals(s.get("replay")))throw new IllegalStateException("Network/replay disabled");
        if(session!=null)checkSession(s);
        long frame=id(s,"frame");if(frame<lastFrame)throw new IllegalStateException("Frame went backwards");
        if(frame!=lastFrame){lastFrame=frame;frameChanged=System.nanoTime();}else if(seconds(frameChanged)>10)throw new IllegalStateException("Game paused for 10s");
        for(Map.Entry<Long,String> entry:required.entrySet()){Map<String,Object> u=find(s,entry.getKey());if(u==null || dead(u))throw new IllegalStateException("Required "+entry.getValue()+" died or disappeared");}
        return s;
    }
    private void checkSession(Map<String,Object> v){if(!session.equals(v.get("sessionId")))throw new IllegalStateException("Session changed");}
    private Map<String,Object> post(String path)throws Exception {
        path+="&sessionId="+session+"&requestId="+UUID.randomUUID();event("action","{\"path\":"+Json.quote(path)+"}");
        Map<String,Object> r=request("POST",path,"command_result");
        if(!"queued".equals(r.get("status")))throw new IllegalStateException("Command not queued");commands++;return r;
    }
    private String address(String path){return "http://127.0.0.1:"+port+path;}
    private Map<String,Object> get(String path,String kind)throws Exception{return request("GET",path,kind);}
    private Map<String,Object> request(String verb,String path,String kind)throws Exception {
        AgentClient.Response r=AgentClient.request(verb,address(path));
        if(r.status!=200){event("http_error","{\"status\":"+r.status+",\"body\":"+Json.quote(r.body)+"}");throw new IOException("HTTP "+r.status+": "+r.body);}
        event(kind,r.body);return object(Json.parse(r.body));
    }
    private void event(String kind,String data)throws Exception {log.write("{\"wallTimeMs\":"+System.currentTimeMillis()+",\"event\":"+Json.quote(kind)+",\"data\":"+data+"}");log.newLine();log.flush();}
    private static void requireType(Map<String,Object> r,String type){if(!type.equals(r.get("type")))throw new IllegalStateException("Command type changed since planning");}
    private static String unit(Map<String,Object> u){return "{\"unitId\":"+id(u,"id")+",\"type\":"+Json.quote(text(u,"type"))+"}";}
    private static boolean dead(Map<String,Object> u){return Boolean.TRUE.equals(u.get("dead"));}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object v){if(!(v instanceof Map))throw new IllegalArgumentException("Expected object");return (Map<String,Object>)v;}
    private static List<Map<String,Object>> units(Map<String,Object> s){List<Map<String,Object>> r=new ArrayList<Map<String,Object>>();for(Object u:(List<?>)s.get("ownUnits"))r.add(object(u));return r;}
    private static Map<String,Object> find(Map<String,Object> s,long id){for(Map<String,Object> u:units(s))if(id(u,"id")==id)return u;return null;}
    private static Set<Long> ids(Map<String,Object> s,String type){Set<Long> r=new HashSet<Long>();for(Map<String,Object> u:units(s))if(type.equals(u.get("type")))r.add(id(u,"id"));return r;}
    private static long id(Map<String,Object> v,String key){return ((Number)v.get(key)).longValue();}
    private static String text(Map<String,Object> v,String key){Object x=v.get(key);if(!(x instanceof String) || ((String)x).isEmpty())throw new IllegalStateException("Missing "+key);return (String)x;}
    private static double num(Map<String,Object> v,String key){Object x=v.get(key);if(!(x instanceof Number) || !Double.isFinite(((Number)x).doubleValue()))throw new IllegalStateException("Missing numeric "+key);return ((Number)x).doubleValue();}
    private static double positive(Map<String,Object> v,String key){double x=num(v,key);if(x<=0)throw new IllegalStateException("Nonpositive "+key);return x;}
    private static double seconds(long since){return (System.nanoTime()-since)/1e9;}
    private static final class Job {
        final String type;final Set<Long> old;final long started=System.nanoTime();double x,y;Long seen;boolean queueSeen,scouted;long emptyAt,resourceTile=-1;
        Job(String t,Set<Long> ids,double xx,double yy){type=t;old=ids;x=xx;y=yy;}
    }
    private static final class ScoutJob {
        final double x,y,startX,startY;final long tile,gameStart,frame,wallStart=System.nanoTime();long progressAt;double best;
        ScoutJob(Map<String,Object> plan,Map<String,Object> worker,long time,long frame) {
            x=num(plan,"targetX");y=num(plan,"targetY");tile=id(plan,"targetTile");startX=num(worker,"x");startY=num(worker,"y");
            gameStart=time;progressAt=time;this.frame=frame;best=Math.hypot(x-startX,y-startY);
        }
    }
}
