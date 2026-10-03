package io.rwagent.client;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One bounded native builder queue, completed own-unit observation, then a fresh Match preflight. */
public final class BootstrapClient {
    private final int port=Integer.getInteger("rwagent.port",47653);
    private final int gameLimit=Math.max(1,Math.min(300,Integer.getInteger("rwagent.bootstrapGameSeconds",180)));
    private final int wallLimit=Math.max(1,Math.min(300,Integer.getInteger("rwagent.bootstrapWallSeconds",120)));
    private final int poll=Math.max(20,Math.min(2000,Integer.getInteger("rwagent.bootstrapPollMs",500)));
    private BufferedWriter log;private FileOutputStream output;
    private String session,mode="UNDETERMINED",phase="startup",builderType="builder";
    private long started,firstGame,lastFrame=-1,lastGame=-1,frameChangedAt,producer=-1,builder=-1;
    private Number team;private int commands,observations;private boolean queueSeen;
    private String completionEvidence="UNCONFIRMED";
    private Map<String,Object> ready;
    public static void main(String[] args){if(args.length!=0){System.err.println("Usage: BootstrapClient");System.exit(2);}System.exit(new BootstrapClient().run());}
    Map<String,Object> preflight(){return ready;}
    int run(){
        started=System.nanoTime();String outcome="FAIL",reason="not started";int exit=1;
        File report=null;FileChannel lockChannel=null;FileLock lock=null;
        try {
            File dir=new File("rw-agent-reports");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create report directory");
            lockChannel=new RandomAccessFile(new File(dir,"bootstrap.lock"),"rw").getChannel();lock=lockChannel.tryLock();
            if(lock==null)throw new IllegalStateException("Another builder bootstrap is running in this game directory");
            report=new File(dir,"bootstrap-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8)+".jsonl");
            output=ReportFiles.open(report);log=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));
            System.out.println("Bootstrap report: "+report.getAbsolutePath());
            Map<String,Object> health=get("/health","health");
            if(!"0.07-alpha1".equals(health.get("version")))throw new IllegalStateException("Unsupported bridge version for builder bootstrap");
            if(Boolean.FALSE.equals(health.get("allowCommands")))throw new IllegalStateException("Local bootstrap commands are disabled");
            Map<String,Object> state=observe();firstGame=integer(state,"gameTimeMs");
            ready=readPreflight();
            if(completed(state,"builder").size()>0){mode="EXISTING_BUILDER";completionEvidence="EXISTING_READY_BUILDER";builder=completed(state,"builder").iterator().next();event("bootstrap_existing_builder","{\"count\":"+completed(state,"builder").size()+",\"builderId\":"+builder+"}");}
            else prepare(state);
            // This is intentionally re-read after unit/queue confirmation; the old opening state is not reused.
            phase="ready_preflight";
            Map<String,Object> fresh=observe();Set<Long> usable=completed(fresh,builderType);
            if(!usable.contains(builder)){
                if(usable.isEmpty())throw new IllegalStateException("No completed own builder remains at final bootstrap verification");
                long previous=builder;builder=usable.iterator().next();completionEvidence="RESELECTED_READY_OWN_BUILDER";
                event("bootstrap_builder_reselected","{\"previousBuilderId\":"+previous+",\"builderId\":"+builder+",\"reason\":\"ORIGINAL_BUILDER_UNAVAILABLE\",\"assignmentSemantics\":\"OBSERVED_AVAILABLE_ROLE\"}");
            }
            ready=readPreflight();
            String next=String.valueOf(ready.get("recommendation"));
            if(!"RUN_ECONOMY_OR_OPENING".equals(next)&&!"RUN_DEVELOP".equals(next))throw new IllegalStateException("Bootstrap completed, but preflight requires "+next);
            event("bootstrap_ready","{\"mode\":"+Json.quote(mode)+",\"recommendation\":"+Json.quote(next)+",\"producerId\":"+producer+",\"builderId\":"+builder+",\"completionEvidence\":"+Json.quote(completionEvidence)+"}");
            outcome="PASS";reason="Completed own builder available; fresh preflight permits Match";exit=0;
        }catch(Exception e){reason=e.toString();System.err.println(reason);}
        finally {
            try{if(log!=null){event("summary","{\"outcome\":"+Json.quote(outcome)+",\"reason\":"+Json.quote(reason)+",\"phase\":"+Json.quote(phase)+",\"bootstrapMode\":"+Json.quote(mode)+",\"commands\":"+commands+",\"automaticProductionCommands\":"+commands+",\"observations\":"+observations+",\"queueObserved\":"+queueSeen+",\"producerId\":"+producer+",\"builderId\":"+builder+",\"resolvedBuilderType\":"+Json.quote(builderType)+",\"completionEvidence\":"+Json.quote(completionEvidence)+",\"gameBudgetSeconds\":"+gameLimit+",\"wallBudgetSeconds\":"+wallLimit+"}");ReportFiles.finish(log,output,report);}}
            catch(Exception e){exit=1;System.err.println("Bootstrap report write failed: "+e);}
            try{if(lock!=null)lock.release();if(lockChannel!=null)lockChannel.close();}catch(Exception ignored){}
        }
        return exit;
    }
    private void prepare(Map<String,Object> initial)throws Exception{
        phase="builder_plan";Map<String,Object> plan=get("/economy/builder-production","builder_plan");checkSession(plan);
        String type=type(plan);builderType=type;Set<Long> old=completed(initial,type);
        if(!old.isEmpty() || integer(plan,"existingBuilders")>0){
            Map<String,Object> fresh=observe();if(completed(fresh,type).isEmpty())throw new IllegalStateException("Builder count was not confirmed in own state");
            mode="EXISTING_BUILDER";completionEvidence="EXISTING_READY_BUILDER";builder=completed(fresh,type).iterator().next();event("bootstrap_existing_builder","{\"count\":"+completed(fresh,type).size()+",\"builderId\":"+builder+"}");return;
        }
        boolean pending=queued(plan)>0;mode=pending?"EXISTING_BUILDER_QUEUE":"AUTOMATIC_NATIVE_PRODUCTION";
        producer=integer(plan,"producerId");
        if(pending){queueSeen=true;event("bootstrap_queue_observed","{\"producerId\":"+producer+",\"buildersQueued\":"+queued(plan)+",\"source\":\"EXISTING_QUEUE\"}");}
        else {
            phase="builder_budget";String waiting=null;
            while(true){
                Map<String,Object> state=observe();
                if(!completed(state,type).isEmpty()){mode="EXISTING_BUILDER";completionEvidence="EXISTING_READY_BUILDER";builder=completed(state,type).iterator().next();event("bootstrap_existing_builder","{\"source\":\"OBSERVED_WHILE_WAITING\",\"builderId\":"+builder+"}");return;}
                plan=get("/economy/builder-production","builder_plan");checkSession(plan);checkType(plan,type);
                if(queued(plan)>0){mode="EXISTING_BUILDER_QUEUE";producer=integer(plan,"producerId");queueSeen=true;event("bootstrap_queue_observed","{\"producerId\":"+producer+",\"buildersQueued\":"+queued(plan)+",\"source\":\"EXISTING_QUEUE\"}");break;}
                double cost=number(plan,"builderCost"),credits=number(object(state.get("player")),"credits");
                if(cost<0)throw new IllegalStateException("Invalid native builder price");
                if(integer(plan,"queueCount")<0)throw new IllegalStateException("Native builder queue unavailable");
                String cause=integer(plan,"queueCount")!=0?"PRODUCER_BUSY":credits<cost?"INSUFFICIENT_CREDITS":!Boolean.TRUE.equals(plan.get("builderActionAvailable"))?"ACTION_UNAVAILABLE":plan.containsKey("builderActionAffordable")&&!Boolean.TRUE.equals(plan.get("builderActionAffordable"))?"ACTION_UNAFFORDABLE":null;
                if(cause==null){
                    producer=integer(plan,"producerId");Map<String,Object> own=find(state,producer);
                    if(own==null || dead(own) || number(own,"buildProgress")<1)throw new IllegalStateException("Native builder producer unavailable in own state");
                    phase="builder_order";String path="/command/produce-builder?unitId="+producer+"&sessionId="+session+"&requestId="+UUID.randomUUID();
                    event("action","{\"path\":"+Json.quote(path)+"}");
                    Map<String,Object> receipt=post(path,"command_result");
                    if(!"queued".equals(receipt.get("status")))throw new IllegalStateException("Builder command not queued");
                    commands++;
                    checkSession(receipt);if(!type.equals(receipt.get("type")))throw new IllegalStateException("Builder production type changed");
                    break;
                }
                if(!cause.equals(waiting)){waiting=cause;event("bootstrap_wait","{\"reason\":"+Json.quote(cause)+",\"credits\":"+credits+",\"required\":"+cost+"}");}
                pause();
            }
        }
        phase="builder_observe";
        long emptyAt=-1;
        while(true){
            pause();Map<String,Object> state=observe();Map<String,Object> own=find(state,producer);
            if(own==null || dead(own) || number(own,"buildProgress")<1)throw new IllegalStateException("Builder producer lost during bootstrap");
            plan=get("/economy/builder-production","builder_plan");checkSession(plan);checkType(plan,type);
            if(queued(plan)>0 && integer(plan,"producerId")!=producer)throw new IllegalStateException("Builder queue moved to a different producer during bootstrap");
            long active=integer(plan,"buildersQueued");
            if(active>0&&!queueSeen){queueSeen=true;event("bootstrap_queue_observed","{\"producerId\":"+producer+",\"buildersQueued\":"+active+",\"source\":\"NATIVE_QUEUE\"}");}
            Set<Long> done=completed(state,type);done.removeAll(old);
            // Fast native production can finish between polls. A new ready own worker suffices to
            // continue, but this weaker observation never claims an unobserved queue cycle.
            if(active==0 && (queueSeen || commands==1&&!done.isEmpty())){
                if(done.size()>1)throw new IllegalStateException("Ambiguous new completed builders during bootstrap");
                if(done.size()==1){builder=done.iterator().next();completionEvidence=queueSeen?"NEW_OWN_UNIT_AND_NATIVE_QUEUE_CYCLE":"NEW_READY_BUILDER_AFTER_ACCEPTED_ORDER";event("bootstrap_builder_completed","{\"unitId\":"+builder+",\"producerId\":"+producer+",\"type\":"+Json.quote(type)+",\"assignmentSemantics\":"+Json.quote(completionEvidence)+"}");return;}
                if(emptyAt<0)emptyAt=integer(state,"gameTimeMs");
                if(integer(state,"gameTimeMs")-emptyAt>=5000)throw new IllegalStateException("Builder queue ended without a new completed own builder; cancelled or lost");
            }else emptyAt=-1;
        }
    }
    private Map<String,Object> observe()throws Exception{
        if((System.nanoTime()-started)/1e9>=wallLimit)throw new IllegalStateException("Builder bootstrap wall timeout ("+wallLimit+"s)");
        Map<String,Object> state=get("/state","observation");observations++;
        if(!"running".equals(state.get("status"))||!(state.get("player") instanceof Map))throw new IllegalStateException("No active local match for builder bootstrap");
        if(Boolean.TRUE.equals(state.get("networked"))||Boolean.TRUE.equals(state.get("replay")))throw new IllegalStateException("Network/replay bootstrap is disabled");
        String current=String.valueOf(state.get("sessionId"));if(session==null)session=current;else if(!session.equals(current))throw new IllegalStateException("Session changed during bootstrap");
        Object identity=object(state.get("player")).get("teamId");if(!(identity instanceof Number))throw new IllegalStateException("Missing local player teamId");
        if(team==null)team=(Number)identity;else if(team.longValue()!=((Number)identity).longValue())throw new IllegalStateException("Local player changed during bootstrap");
        long frame=integer(state,"frame"),time=integer(state,"gameTimeMs");
        if(frame<lastFrame||time<lastGame)throw new IllegalStateException("Simulation clock went backwards during bootstrap");
        if(frame!=lastFrame){frameChangedAt=System.nanoTime();}else if((System.nanoTime()-frameChangedAt)/1e9>10)throw new IllegalStateException("Game paused or stalled during bootstrap");
        if(lastGame>=0&&(time-firstGame)/1000.0>=gameLimit)throw new IllegalStateException("Builder bootstrap game timeout ("+gameLimit+"s)");
        lastFrame=frame;lastGame=time;return state;
    }
    private Map<String,Object> readPreflight()throws Exception{Map<String,Object> p=get("/economy/preflight","preflight");checkSession(p);if(!Boolean.TRUE.equals(p.get("commandsAllowed")))throw new IllegalStateException("Preflight command guard: "+p);return p;}
    private Map<String,Object> get(String path,String kind)throws Exception{return request("GET",path,kind);}
    private Map<String,Object> post(String path,String kind)throws Exception{return request("POST",path,kind);}
    private Map<String,Object> request(String method,String path,String kind)throws Exception{
        AgentClient.Response r=AgentClient.request(method,"http://127.0.0.1:"+port+path);
        if(r.status!=200){event("http_error","{\"status\":"+r.status+",\"body\":"+Json.quote(r.body)+"}");throw new IllegalStateException("Bootstrap HTTP "+r.status+": "+r.body);}
        Map<String,Object> result=object(Json.parse(r.body));event(kind,r.body);return result;
    }
    private void checkSession(Map<String,Object> p){if(!session.equals(p.get("sessionId")))throw new IllegalStateException("Session changed during bootstrap planning/command");}
    private static String type(Map<String,Object> p){Object t=p.get("builderType");if(!(t instanceof String)||((String)t).isEmpty())throw new IllegalStateException("Missing resolved builderType");return(String)t;}
    private static void checkType(Map<String,Object> p,String expected){if(!expected.equals(type(p)))throw new IllegalStateException("Resolved builder type changed during bootstrap");}
    private static long queued(Map<String,Object> p){return integer(p,p.containsKey("totalBuildersQueued")?"totalBuildersQueued":"buildersQueued");}
    private void pause()throws InterruptedException{Thread.sleep(poll);}
    private void event(String name,String data)throws IOException{log.write("{\"wallTimeMs\":"+System.currentTimeMillis()+",\"event\":"+Json.quote(name)+",\"data\":"+data+"}");log.newLine();log.flush();}
    @SuppressWarnings("unchecked")private static Map<String,Object> object(Object v){if(!(v instanceof Map))throw new IllegalStateException("Expected JSON object");return(Map<String,Object>)v;}
    private static List<Map<String,Object>> units(Map<String,Object> state){List<Map<String,Object>> out=new ArrayList<Map<String,Object>>();Object raw=state.get("ownUnits");if(!(raw instanceof List))throw new IllegalStateException("Missing ownUnits");for(Object v:(List<?>)raw)out.add(object(v));return out;}
    private static Map<String,Object> find(Map<String,Object> state,long id){for(Map<String,Object> u:units(state))if(integer(u,"id")==id)return u;return null;}
    private static Set<Long> completed(Map<String,Object> state,String type){Set<Long> ids=new LinkedHashSet<Long>();for(Map<String,Object> u:units(state))if(type.equals(u.get("type"))&&!dead(u)&&number(u,"buildProgress")>=1)ids.add(integer(u,"id"));return ids;}
    private static boolean dead(Map<String,Object> u){return Boolean.TRUE.equals(u.get("dead"));}
    private static long integer(Map<String,Object> p,String key){return(long)number(p,key);}
    private static double number(Map<String,Object> p,String key){Object v=p.get(key);if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue()))throw new IllegalStateException("Missing numeric "+key);return((Number)v).doubleValue();}
}
