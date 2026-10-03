package io.rwagent.client;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Controlled single-builder experiment: new landFactory completed, then new tank observed. */
public final class EconomyClient {
    private final int port=Integer.getInteger("rwagent.port",47653);
    private BufferedWriter log;
    private FileOutputStream reportOutput;
    private String session;
    private int commands,observations;
    private long lastFrame=-1,frameChangedAt;
    private String phase="startup";
    private final Map<Long,String> requiredUnits=new LinkedHashMap<Long,String>();
    private int completedTanks,completedBuildings;
    private long startedAt;
    public static void main(String[] args) {System.exit(new EconomyClient().run(args,false));}
    int run(String[] args,boolean opening) {
        startedAt=System.nanoTime();
        String outcome="FAIL",reason="not started";int exit=1;
        File report=null;FileChannel lockChannel=null;FileLock lock=null;
        try {
            if(args.length>1)throw new IllegalArgumentException("Usage: EconomyClient / OpeningClient [builderId]");
            if(args.length==1)Long.parseLong(args[0]);
            File directory=new File("rw-agent-reports");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create report directory");
            lockChannel=new RandomAccessFile(new File(directory,"economy.lock"),"rw").getChannel();lock=lockChannel.tryLock();
            if(lock==null)throw new IllegalStateException("An economy test is already running in this game directory");
            report=new File(directory,(opening?"opening-":"economy-")+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8)+".jsonl");
            reportOutput=ReportFiles.open(report);
                log=new BufferedWriter(new OutputStreamWriter(reportOutput,StandardCharsets.UTF_8));
            System.out.println("Report: "+report.getAbsolutePath());
            System.out.println("Keep game running. Do not issue other build/production orders during this test.");
            Map<String,Object> health=get("/health","health");
            if(!"0.07-alpha1".equals(health.get("version")))throw new IllegalStateException("Install 0.07-alpha1 and restart the game");
            Map<String,Object> state=observe();
            session=(String)state.get("sessionId");
            phase="plan";
            Map<String,Object> plan=get((opening?"/opening/plan":"/economy/plan")+(args.length==1?"?unitId="+args[0]:""),"plan");
            if(opening) {
                if(!session.equals(plan.get("sessionId")))throw new IllegalStateException("Session changed while planning opening");
                String extractorType=typeName(plan,"extractorType");
                long worker=id(plan,"builderId");requiredUnits.put(worker,"builder");
                phase="extractor_budget";waitForCredits(num(plan,"extractorCost"));
                state=observe();Set<Long> oldExtractors=ids(state,extractorType);
                phase="extractor_build";
                Map<String,Object> mineOrder=post("/command/build-extractor?unitId="+worker+"&x="+num(plan,"extractorX")+"&y="+num(plan,"extractorY"));
                if(!extractorType.equals(mineOrder.get("type")))throw new IllegalStateException("Extractor type changed since planning");
                System.out.println("Building extractor, then landFactory, then 3 tanks.");
                Map<String,Object> mine=waitForBuilding(worker,num(mineOrder,"targetX"),num(mineOrder,"targetY"),oldExtractors,extractorType,"extractor");
                requiredUnits.put(id(mine,"id"),"extractor");
                phase="factory_budget";waitForCredits(num(plan,"factoryCost")+num(plan,"tankCost"));
                plan=get("/economy/plan?unitId="+worker,"factory_plan");
            }
            if(!session.equals(plan.get("sessionId")))throw new IllegalStateException("Session changed while planning");
            String productType=(String)plan.get("productType");
            if(productType==null || productType.isEmpty())throw new IllegalStateException("Plan missing resolved productType");
            String factoryType=typeName(plan,"factoryType");
            long builder=id(plan,"builderId");double x=num(plan,"targetX"),y=num(plan,"targetY");
            System.out.println("Builder "+builder+": landFactory at ("+x+", "+y+"), then "+(opening?3:1)+" tank(s) ("+productType+").");
            state=observe();Set<Long> oldFactories=ids(state,factoryType);
            phase="build";
            Map<String,Object> build=post("/command/build-factory?unitId="+builder+"&x="+x+"&y="+y);
            if(!factoryType.equals(build.get("type")))throw new IllegalStateException("Factory type changed since planning");
            x=num(build,"targetX");y=num(build,"targetY");
            Map<String,Object> factory=waitForBuilding(builder,x,y,oldFactories,factoryType,"factory");
            long factoryId=id(factory,"id");
            System.out.println("Factory "+factoryId+" completed. Starting tank production.");
            requiredUnits.put(factoryId,"factory");
            for(int number=1;number<=(opening?3:1);number++) {
                phase="tank_"+number+"_budget";
                if(opening)waitForCredits(num(plan,"tankCost"));
                state=observe();factory=find(state,factoryId);
                if(factory==null || dead(factory) || num(factory,"buildProgress")<1)throw new IllegalStateException("Factory unavailable before production");
                Set<Long> oldTanks=ids(state,productType);
                phase="tank_"+number+"_produce";
                Map<String,Object> produced=post("/command/produce-tank?unitId="+factoryId);
                if(!productType.equals(produced.get("type")))throw new IllegalStateException("Production type changed since planning; observe game before restarting");
                Map<String,Object> tank=waitForTank(factoryId,num(factory,"x"),num(factory,"y"),oldTanks,productType);
                completedTanks++;requiredUnits.put(id(tank,"id"),"tank "+number);
                System.out.println("Tank "+number+" completed: "+id(tank,"id"));
            }
            if(opening)observe();
            outcome="PASS";reason=opening?"New extractor and landFactory completed; 3 distinct new tanks confirmed through 3 queue cycles; all task units still alive":"New landFactory completed; its queue was observed active then empty; one new own tank appeared nearby";exit=0;
        } catch(Exception error) {reason=error.toString();System.err.println(reason);}
        finally {
            try {if(log!=null) {event("summary","{\"outcome\":"+Json.quote(outcome)+",\"reason\":"+Json.quote(reason)+",\"phase\":"+Json.quote(phase)+",\"commands\":"+commands+",\"observations\":"+observations+",\"completedBuildings\":"+completedBuildings+",\"completedTanks\":"+completedTanks+"}");ReportFiles.finish(log,reportOutput,report);}}
            catch(Exception error){exit=1;System.err.println("Report write failed: "+error);}
            try {if(lock!=null)lock.release();if(lockChannel!=null)lockChannel.close();}catch(Exception ignored){}
        }
        System.out.println(outcome+": "+reason);
        if(report!=null)System.out.println("Send back: "+report.getAbsolutePath());
        return exit;
    }
    private Map<String,Object> waitForBuilding(long builder,double x,double y,Set<Long> old,String buildingType,String label) throws Exception {
        long start=System.nanoTime();Long seen=null;int lastProgress=-1;
        while(seconds(start)<240) {
            Thread.sleep(500);Map<String,Object> state=observe();
            List<Map<String,Object>> matches=new ArrayList<Map<String,Object>>();
            for(Map<String,Object> u:units(state))if(buildingType.equals(u.get("type")) && !old.contains(id(u,"id")) && Math.hypot(num(u,"x")-x,num(u,"y")-y)<=35 && !dead(u))matches.add(u);
            if(matches.size()>1)throw new IllegalStateException("Ambiguous new "+label+" near target; stop other construction during test");
            Map<String,Object> factory=matches.isEmpty()?null:matches.get(0);
            if(seen!=null && (factory==null || id(factory,"id")!=seen.longValue()))throw new IllegalStateException("Observed "+label+" was destroyed or disappeared");
            if(factory!=null) {
                if(seen==null){seen=id(factory,"id");event(label+"_started",unitJson(factory));}
                int progress=(int)(num(factory,"buildProgress")*10);
                if(progress!=lastProgress){lastProgress=progress;System.out.println(label+" progress: "+Math.min(100,progress*10)+"%");}
                if(num(factory,"buildProgress")>=1){event(label+"_completed",unitJson(factory));completedBuildings++;return factory;}
            }
            Map<String,Object> worker=find(state,builder);
            if(worker==null || dead(worker))throw new IllegalStateException("Builder died or disappeared before "+label+" completion");
        }
        throw new IllegalStateException(label+" completion timeout (240s): inspect site, builder path, credits, or interference");
    }
    private Map<String,Object> waitForTank(long factoryId,double x,double y,Set<Long> old,String productType) throws Exception {
        long start=System.nanoTime(),emptyAt=0;boolean queueSeen=false;
        while(seconds(start)<120) {
            Thread.sleep(500);Map<String,Object> state=observe();Map<String,Object> factory=find(state,factoryId);
            if(factory==null || dead(factory))throw new IllegalStateException("Factory died or disappeared during production");
            int queue=(int)num(factory,"productionQueue");
            if(queue<0)throw new IllegalStateException("Factory queue unavailable");
            if(queue>1)throw new IllegalStateException("Factory queue contains other orders; experiment interrupted");
            if(queue>0 && !queueSeen){queueSeen=true;event("production_started","{\"factoryId\":"+factoryId+",\"queue\":"+queue+"}");}
            List<Map<String,Object>> matches=new ArrayList<Map<String,Object>>();
            for(Map<String,Object> u:units(state))if(productType.equals(u.get("type")) && !old.contains(id(u,"id")) && !dead(u) && num(u,"buildProgress")>=1 && Math.hypot(num(u,"x")-x,num(u,"y")-y)<=200)matches.add(u);
            if(queueSeen && queue==0) {
                if(matches.size()>1)throw new IllegalStateException("Ambiguous new tanks near factory");
                if(matches.size()==1){event("tank_completed",unitJson(matches.get(0)));return matches.get(0);}
                if(emptyAt==0)emptyAt=System.nanoTime();
                if(seconds(emptyAt)>5)throw new IllegalStateException("Queue ended without a new tank nearby; order may have been cancelled or tank lost");
            } else emptyAt=0;
        }
        throw new IllegalStateException("Tank production timeout (120s); queue cycle and new tank were not both confirmed");
    }
    private void waitForCredits(double cost) throws Exception {
        long start=System.nanoTime();boolean announced=false;
        while(seconds(start)<120) {
            Map<String,Object> state=observe();double credits=num(object(state.get("player")),"credits");
            if(credits>=cost)return;
            if(!announced){announced=true;event("budget_wait","{\"required\":"+cost+",\"credits\":"+credits+"}");System.out.println("Waiting for credits: need "+cost+", have "+credits);}
            Thread.sleep(500);
        }
        throw new IllegalStateException("Insufficient credits for 120s; no order submitted for this stage");
    }
    private Map<String,Object> observe() throws Exception {
        if(seconds(startedAt)>600)throw new IllegalStateException("Overall test timeout (600s)");
        Map<String,Object> s=get("/state","observation");observations++;
        if(!"running".equals(s.get("status")) || s.get("player")==null)throw new IllegalStateException("No active local match");
        if(Boolean.TRUE.equals(s.get("networked")) || Boolean.TRUE.equals(s.get("replay")))throw new IllegalStateException("Network/replay tests are disabled");
        if(session!=null&&!session.equals(s.get("sessionId")))throw new IllegalStateException("Session changed");
        long frame=id(s,"frame");if(frame<lastFrame)throw new IllegalStateException("Simulation frame went backwards");
        if(frame!=lastFrame){lastFrame=frame;frameChangedAt=System.nanoTime();}else if(seconds(frameChangedAt)>10)throw new IllegalStateException("Game paused or simulation stalled for 10s");
        for(Map.Entry<Long,String> entry:requiredUnits.entrySet()) {
            Map<String,Object> u=find(s,entry.getKey());
            if(u==null || dead(u))throw new IllegalStateException("Required "+entry.getValue()+" died or disappeared");
        }
        return s;
    }
    private Map<String,Object> post(String path) throws Exception {
        String full=path+"&sessionId="+session+"&requestId="+UUID.randomUUID();
        event("action","{\"path\":"+Json.quote(full)+"}");
        Map<String,Object> result=request("POST",full,"command_result");
        if(!"queued".equals(result.get("status")))throw new IllegalStateException("Command not queued");commands++;return result;
    }
    private Map<String,Object> get(String path,String kind) throws Exception {return request("GET",path,kind);}
    private Map<String,Object> request(String verb,String path,String kind) throws Exception {
        AgentClient.Response r=AgentClient.request(verb,"http://127.0.0.1:"+port+path);
        if(r.status!=200){event("http_error","{\"status\":"+r.status+",\"body\":"+Json.quote(r.body)+"}");throw new IllegalStateException("HTTP "+r.status+": "+r.body);}
        Map<String,Object> result=object(Json.parse(r.body));event(kind,r.body);return result;
    }
    private void event(String type,String data) throws Exception {log.write("{\"wallTimeMs\":"+System.currentTimeMillis()+",\"event\":"+Json.quote(type)+",\"data\":"+data+"}");log.newLine();log.flush();}
    private static String unitJson(Map<String,Object> u){return "{\"unitId\":"+id(u,"id")+",\"type\":"+Json.quote((String)u.get("type"))+",\"x\":"+num(u,"x")+",\"y\":"+num(u,"y")+",\"buildProgress\":"+num(u,"buildProgress")+"}";}
    private static String typeName(Map<String,Object> plan,String key){Object value=plan.get(key);if(!(value instanceof String)||((String)value).isEmpty())throw new IllegalStateException("Missing resolved "+key);return (String)value;}
    private static boolean dead(Map<String,Object> u){return Boolean.TRUE.equals(u.get("dead"));}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object v){if(!(v instanceof Map))throw new IllegalArgumentException("Expected object");return (Map<String,Object>)v;}
    private static List<Map<String,Object>> units(Map<String,Object> s){List<Map<String,Object>> result=new ArrayList<Map<String,Object>>();for(Object v:(List<?>)s.get("ownUnits"))result.add(object(v));return result;}
    private static Map<String,Object> find(Map<String,Object> s,long id){for(Map<String,Object> u:units(s))if(id(u,"id")==id)return u;return null;}
    private static Set<Long> ids(Map<String,Object> s,String type){Set<Long> result=new HashSet<Long>();for(Map<String,Object> u:units(s))if(type.equals(u.get("type")))result.add(id(u,"id"));return result;}
    private static long id(Map<String,Object> o,String key){return ((Number)o.get(key)).longValue();}
    private static double num(Map<String,Object> o,String key){Object v=o.get(key);if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue()))throw new IllegalStateException("Missing numeric "+key);return ((Number)v).doubleValue();}
    private static double seconds(long since){return (System.nanoTime()-since)/1e9;}
}
