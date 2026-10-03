package io.rwagent.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** G1 observation provenance only. This clock never advances a policy or rejects a read.
 * Endpoint snapshots are independent; missing native timestamps remain unknown.
 */
public final class GameClock {
    private final String runId;
    private long sequence;
    private final Map<String,Observation> latest=new LinkedHashMap<String,Observation>(16,.75f,true);
    private static final int MAX_ENDPOINTS=32;

    public GameClock(String runId){
        if(runId==null||runId.length()==0)throw new IllegalArgumentException("runId required");
        this.runId=runId;
    }

    public static final class Observation {
        public final String id,observationId,endpoint,requestPath,sourceSessionId,sourcePlayerId;
        public final Long sourceGameTimeMs,sourceFrame,detectedAtGameTimeMs;
        public final long requestedWallTimeMs,receivedWallTimeMs;
        public final String previousObservationId;
        public final Long previousSourceGameTimeMs,previousSourceFrame;
        public final List<String> discontinuities;

        private Observation(String id,String endpoint,String requestPath,Map<String,Object> payload,long requested,long received,
                            Long detected,Observation previous){
            this.id=id;observationId=id;this.endpoint=endpoint;this.requestPath=requestPath;requestedWallTimeMs=requested;receivedWallTimeMs=received;
            sourceGameTimeMs=number(payload.get("gameTimeMs"));sourceFrame=number(payload.get("frame"));
            sourceSessionId=string(payload.get("sessionId"));sourcePlayerId=player(payload);
            detectedAtGameTimeMs=detected;
            previousObservationId=previous==null?null:previous.observationId;
            List<String> flags=new ArrayList<String>();
            boolean sameSession=previous!=null&&sourceSessionId!=null&&sourceSessionId.equals(previous.sourceSessionId);
            if(previous!=null&&sourceSessionId!=null&&previous.sourceSessionId!=null&&!sameSession)flags.add("SESSION_CHANGED");
            if(sameSession&&sourcePlayerId!=null&&previous.sourcePlayerId!=null&&!sourcePlayerId.equals(previous.sourcePlayerId))flags.add("PLAYER_CHANGED");
            if(sameSession&&less(sourceGameTimeMs,previous.sourceGameTimeMs))flags.add("SOURCE_GAME_TIME_ROLLBACK");
            if(sameSession&&less(sourceFrame,previous.sourceFrame))flags.add("SOURCE_FRAME_ROLLBACK");
            if(sameSession&&sourceGameTimeMs!=null&&sourceGameTimeMs.equals(previous.sourceGameTimeMs)
                    &&sourceFrame!=null&&sourceFrame.equals(previous.sourceFrame))flags.add("REPEATED_SOURCE_STAMP");
            if(received<requested)flags.add("WALL_CLOCK_ROLLBACK_DURING_READ");
            if(previous!=null&&received<previous.receivedWallTimeMs)flags.add("WALL_CLOCK_ROLLBACK_BETWEEN_READS");
            // A prior sample across a session switch is not a source-time interval.
            previousSourceGameTimeMs=sameSession?previous.sourceGameTimeMs:null;
            previousSourceFrame=sameSession?previous.sourceFrame:null;
            discontinuities=Collections.unmodifiableList(flags);
        }

        public Map<String,Object> metadata(){
            Map<String,Object> m=new LinkedHashMap<String,Object>();
            m.put("observationId",observationId);m.put("endpoint",endpoint);m.put("requestPath",requestPath);
            m.put("sourceSessionId",sourceSessionId);m.put("sourcePlayerId",sourcePlayerId);
            m.put("sourceGameTimeMs",sourceGameTimeMs);m.put("sourceFrame",sourceFrame);
            m.put("requestedWallTimeMs",requestedWallTimeMs);m.put("receivedWallTimeMs",receivedWallTimeMs);
            m.put("detectedAtGameTimeMs",detectedAtGameTimeMs);m.put("previousObservationId",previousObservationId);
            m.put("detectedAtGameTimeBasis","LATEST_STATE_SAMPLE_NOT_NATIVE_RECEIVE_TIME");
            m.put("gameTimeInterpolation",false);
            m.put("sourceRangeStartGameTimeMs",previousSourceGameTimeMs);m.put("sourceRangeEndGameTimeMs",sourceGameTimeMs);
            m.put("sourceRangeSemantics","PREVIOUS_SAME_ENDPOINT_READ_NOT_EVENT_OCCURRENCE_INTERVAL");
            m.put("sourceRangeStatus",discontinuities.contains("SOURCE_GAME_TIME_ROLLBACK")?"ROLLBACK_NOT_INTERVAL":
                    previousSourceGameTimeMs==null||sourceGameTimeMs==null?"NO_COMPLETE_SOURCE_INTERVAL":"SAME_ENDPOINT_SAMPLE_RANGE");
            m.put("previousSourceFrame",previousSourceFrame);m.put("discontinuities",discontinuities);
            m.put("atomicWithOtherEndpoints",false);return m;
        }
        public Map<String,Object> toMap(){return metadata();}
    }

    public synchronized Observation observe(String endpoint,Map<String,Object> payload,long requestedWallTimeMs,
                                            long receivedWallTimeMs,Long detectedAtGameTimeMs){
        if(endpoint==null||payload==null)throw new IllegalArgumentException("endpoint and payload required");
        String requestPath=endpoint;endpoint=canonicalEndpoint(endpoint);
        Observation o=new Observation(runId+":o:"+(++sequence),endpoint,requestPath,payload,requestedWallTimeMs,
                receivedWallTimeMs,detectedAtGameTimeMs,latest.get(endpoint));
        latest.put(endpoint,o);
        if(latest.size()>MAX_ENDPOINTS)latest.remove(latest.keySet().iterator().next());
        return o;
    }
    public synchronized Observation latest(String endpoint){return endpoint==null?null:latest.get(canonicalEndpoint(endpoint));}
    public synchronized Observation latestState(){return latest.get("/state");}
    public synchronized List<String> latestObservationIds(){
        List<String> result=new ArrayList<String>();for(Observation o:latest.values())result.add(o.observationId);
        return Collections.unmodifiableList(result);
    }
    public synchronized Map<String,Object> latestInputs(){
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        for(Map.Entry<String,Observation> e:latest.entrySet())result.put(e.getKey(),e.getValue().metadata());
        return result;
    }
    public synchronized int retainedEndpointCount(){return latest.size();}
    private static String canonicalEndpoint(String endpoint){int q=endpoint.indexOf('?');return q<0?endpoint:endpoint.substring(0,q);}
    static Long number(Object value){return value instanceof Number?Long.valueOf(((Number)value).longValue()):null;}
    static String string(Object value){return value instanceof String?(String)value:null;}
    private static boolean less(Long a,Long b){return a!=null&&b!=null&&a.longValue()<b.longValue();}
    private static String player(Map<String,Object> payload){
        Object p=payload.get("player");
        if(p instanceof Map){Object team=((Map<?,?>)p).get("teamId");if(team!=null)return "team:"+team;
            Object id=((Map<?,?>)p).get("id");return id==null?null:String.valueOf(id);}
        if(p instanceof Number)return "team:"+p;
        return p instanceof String?(String)p:string(payload.get("playerId"));
    }
}
