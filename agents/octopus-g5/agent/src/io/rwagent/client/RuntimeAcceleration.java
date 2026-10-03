package io.rwagent.client;

import java.util.*;
import java.util.concurrent.*;

/** Transport workers never mutate clocks, world state, ownership or controllers. Each response
 * retains its own native clock and wall interval; parallel reads are not an atomic world. */
public final class RuntimeAcceleration implements AutoCloseable {
    public static final class Read {
        public final AgentClient.Response response;
        public final long requestedWallMs,receivedWallMs,elapsedNanos;
        Read(AgentClient.Response response,long requested,long received,long elapsed){this.response=response;requestedWallMs=requested;receivedWallMs=received;elapsedNanos=elapsed;}
    }
    public static final class CachedRead {
        public final Map<String,Object> payload;public final GameClock.Observation observation;
        CachedRead(Map<String,Object> payload,GameClock.Observation observation){this.payload=payload;this.observation=observation;}
    }
    private final ExecutorService workers=Executors.newFixedThreadPool(2,new ThreadFactory(){
        public Thread newThread(Runnable action){Thread thread=new Thread(action,"rw-observation-transport");thread.setDaemon(true);return thread;}});
    private final Map<String,Future<Read>> pending=new HashMap<String,Future<Read>>();
    private final Map<String,CachedRead> cache=new HashMap<String,CachedRead>();
    private final int port;
    private long observationReads,observationNanos,dispatches,dispatchNanos,cacheHits,parallelFetches,decisions,decisionIntervals,decisionTotal;
    private long previousDecision=-1,lastSampleGame=-1,lastSampleNano=-1,cycleCommands,maxCommands;
    private double speed=1;
    public RuntimeAcceleration(int port){this.port=port;}
    public void beginCycle(){cache.clear();cycleCommands=0;}
    public void prefetch(String... paths){for(final String path:paths)if(!pending.containsKey(path)){
        pending.put(path,workers.submit(new Callable<Read>(){public Read call()throws Exception{return request(path);}}));parallelFetches++;}}
    private Read request(String path)throws Exception{long wall=System.currentTimeMillis(),nano=System.nanoTime();
        AgentClient.Response response=AgentClient.request("GET","http://127.0.0.1:"+port+path);
        return new Read(response,wall,System.currentTimeMillis(),System.nanoTime()-nano);}
    public Read fetch(String path)throws Exception{Future<Read> future=pending.remove(path);Read read;
        try{read=future==null?request(path):future.get();}catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;throw failure;}
        observationReads++;observationNanos+=read.elapsedNanos;return read;}
    public CachedRead cached(String path){CachedRead hit=cache.get(path);if(hit!=null)cacheHits++;return hit;}
    public void remember(String path,Map<String,Object> payload,GameClock.Observation observation){
        if(payload!=null&&observation!=null&&cacheable(path))cache.put(path,new CachedRead(payload,observation));}
    private static boolean cacheable(String path){return path.equals("/combat/production")||path.startsWith("/combat/engagement?")
        ||path.equals("/economy/investments")||path.startsWith("/combat/unit-modes?")
        ||path.startsWith("/economy/builder-actions?")||path.equals("/economy/builder-production");}
    public void sample(long gameTime){long nano=System.nanoTime();if(lastSampleGame>=0&&gameTime>lastSampleGame&&nano>lastSampleNano){
        double measured=(gameTime-lastSampleGame)/((nano-lastSampleNano)/1000000.0);if(Double.isFinite(measured)&&measured>0)speed=Math.max(.1,Math.min(30,measured));}
        lastSampleGame=gameTime;lastSampleNano=nano;}
    public void decision(long gameTime){if(previousDecision>=0&&gameTime>previousDecision){decisionTotal+=gameTime-previousDecision;decisionIntervals++;}previousDecision=gameTime;decisions++;}
    public long sleepMs(long desiredGameInterval,long loopStartedNano){double elapsed=(System.nanoTime()-loopStartedNano)/1000000.0;
        return Math.max(10,Math.min(100,(long)Math.ceil(desiredGameInterval/speed-elapsed)));}
    public void dispatch(long nanos,boolean accepted){dispatchNanos+=Math.max(0,nanos);dispatches++;if(accepted){cycleCommands++;maxCommands=Math.max(maxCommands,cycleCommands);}}
    public Map<String,Object> metrics(long commands,long elapsedGameMs){return StrategyDirector.map("effectiveDecisionIntervalGameMs",decisionIntervals==0?null:decisionTotal/(double)decisionIntervals,
        "observationLatencyWallMs",observationReads==0?null:observationNanos/1000000.0/observationReads,
        "dispatchLatencyWallMs",dispatches==0?null:dispatchNanos/1000000.0/dispatches,"commandsPerGameMinute",elapsedGameMs>0?commands*60000.0/elapsedGameMs:null,
        "maxCommandsPerObservation",maxCommands,"commandsThisObservation",cycleCommands,"observationReads",observationReads,
        "parallelFetches",parallelFetches,"cacheHits",cacheHits,"decisions",decisions,"measuredGameSpeed",speed,
        "atomicSnapshot",false,"ordering","SERIAL_CONTROLLER_STATE_PARALLEL_INDEPENDENT_TRANSPORT");}
    public void close(){for(Future<Read> future:pending.values())future.cancel(true);pending.clear();workers.shutdownNow();}
}
