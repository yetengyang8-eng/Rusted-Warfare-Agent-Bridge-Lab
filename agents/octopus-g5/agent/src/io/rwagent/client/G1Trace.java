package io.rwagent.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** G1 metadata sidecar. IDs describe existing direct proposals, never queued Intents.
 * No field is used for native admission, ownership, timing, budget or policy decisions.
 */
public final class G1Trace {
    public static final String SCHEMA="rw-g1-trace-v1";
    private final String runId;
    private final GameClock clock;
    private long eventSequence,intentSequence,commandSequence;
    private final Map<String,CommandSpan> byRequest=new LinkedHashMap<String,CommandSpan>();
    private static final int MAX_RECEIPTS=256;

    public G1Trace(GameClock clock,String runId){
        if(clock==null||runId==null||runId.length()==0)throw new IllegalArgumentException("clock/runId required");
        this.clock=clock;this.runId=runId;
    }

    public static final class CommandSpan {
        public final String intentId,commandId,owner,endpoint;
        public final List<Long> actors;
        public final List<String> inputObservationIds;
        public final String stateObservationId;
        public final Long proposedAtGameTimeMs;
        public String requestId;
        private String denial,receiptStatus,receiptSessionId,receiptRequestIdFromBody;
        private Long receiptFrame,receiptSourceGameTimeMs;
        private boolean admissionRecorded,receiptRecorded;
        private Intent executionIntent;
        private CommandSpan(String intentId,String commandId,String owner,List<Long> actors,String endpoint,
                            List<String> inputs,GameClock.Observation state){
            this.intentId=intentId;this.commandId=commandId;this.owner=owner;this.endpoint=endpoint;
            this.actors=Collections.unmodifiableList(new ArrayList<Long>(actors));
            inputObservationIds=Collections.unmodifiableList(new ArrayList<String>(inputs));
            stateObservationId=state==null?null:state.observationId;
            proposedAtGameTimeMs=state==null?null:state.sourceGameTimeMs;
        }
        public void admission(String denial){admissionRecorded=true;this.denial=denial;}
        /** G3 attaches the real proposal; retain its generation snapshot for later witnesses. */
        public void executionIntent(Intent intent){
            if(intent==null||!owner.equals(intent.owner)||!actors.equals(intent.actorIds))throw new IllegalArgumentException("Execution intent differs from trace command");
            if(executionIntent!=null)throw new IllegalStateException("Execution intent already attached");
            executionIntent=intent;
        }
        public void nativeReceipt(String requestId,Map<String,Object> receipt){
            this.requestId=requestId;receiptRecorded=true;
            receiptStatus=receipt==null?null:GameClock.string(receipt.get("status"));
            receiptRequestIdFromBody=receipt==null?null:GameClock.string(receipt.get("requestId"));
            receiptSessionId=receipt==null?null:GameClock.string(receipt.get("sessionId"));
            receiptFrame=receipt==null?null:GameClock.number(receipt.get("frame"));
            receiptSourceGameTimeMs=receipt==null?null:GameClock.number(receipt.get("gameTimeMs"));
        }
        public Map<String,Object> metadata(){
            Map<String,Object> m=new LinkedHashMap<String,Object>();
            m.put("intentId",intentId);m.put("intentSemantics","TRACE_ONLY_DIRECT_PROPOSAL");m.put("commandId",commandId);
            m.put("owner",owner);m.put("ownerGeneration",null);m.put("ownerGenerationStatus","NOT_IMPLEMENTED_G3");
            if(executionIntent!=null){
                m.put("intentSemantics","G3_EXPLICIT_EXECUTION_PROPOSAL");m.put("executionIntentId",executionIntent.intentId);
                m.put("ownerGenerations",executionIntent.ownerGenerations);m.put("ownerGenerationStatus","PER_ACTOR_MONOTONIC_AT_PROPOSAL");
                m.put("ownerGeneration",actors.size()==1?executionIntent.ownerGenerations.get(actors.get(0)):null);
                m.put("commitment",executionIntent.commitment.metadata());
                m.put("costSourceObservationId",executionIntent.costSourceObservationId);
                m.put("costSourceRequestPath",executionIntent.costSourceRequestPath);
            }
            m.put("actors",actors);m.put("endpoint",endpoint);m.put("inputObservationIds",inputObservationIds);
            m.put("inputObservationSemantics","AVAILABLE_READ_CONTEXT_NOT_EXCLUSIVE_CAUSAL_PROOF");
            m.put("stateObservationId",stateObservationId);m.put("proposedAtGameTimeMs",proposedAtGameTimeMs);
            m.put("admission",!admissionRecorded?"NOT_RECORDED":denial==null?"ADMITTED":"DENIED");m.put("admissionDenial",denial);
            m.put("nativeRequestId",requestId);m.put("receiptRecorded",receiptRecorded);m.put("nativeReceiptStatus",receiptStatus);
            m.put("nativeRequestIdSemantics","WIRE_REQUEST_CORRELATION_NOT_ASSUMED_NATIVE_ECHO");
            m.put("nativeReceiptRequestIdFromBody",receiptRequestIdFromBody);
            m.put("receiptRequestIdMatches",receiptRequestIdFromBody==null||requestId==null?null:Boolean.valueOf(receiptRequestIdFromBody.equals(requestId)));
            m.put("nativeReceiptSessionId",receiptSessionId);m.put("nativeReceiptFrame",receiptFrame);
            m.put("nativeReceiptSourceGameTimeMs",receiptSourceGameTimeMs);
            m.put("receiptProvesExecution",false);return m;
        }
    }

    public synchronized CommandSpan beginCommand(String owner,List<Long> actors,String endpoint,List<String> inputObservationIds){
        return new CommandSpan(runId+":i:"+(++intentSequence),runId+":c:"+(++commandSequence),owner,
                actors==null?Collections.<Long>emptyList():actors,endpoint,
                inputObservationIds==null?Collections.<String>emptyList():inputObservationIds,clock.latestState());
    }
    public synchronized void rememberReceipt(CommandSpan span,String requestId,Map<String,Object> receipt){
        span.nativeReceipt(requestId,receipt);
        if(requestId!=null){byRequest.put(requestId,span);if(byRequest.size()>MAX_RECEIPTS)byRequest.remove(byRequest.keySet().iterator().next());}
    }
    public synchronized CommandSpan commandForRequest(String requestId){return byRequest.get(requestId);}
    public synchronized int retainedReceiptCount(){return byRequest.size();}

    /** extra is explicit context (lane/task/state/witness), copied so legacy payloads stay untouched. */
    public synchronized Map<String,Object> event(String kind,long wallTimeMs,GameClock.Observation observation,
                                                 CommandSpan command,String phase,Map<String,Object> extra){
        Map<String,Object> m=new LinkedHashMap<String,Object>();
        m.put("schema",SCHEMA);m.put("runId",runId);m.put("eventId",runId+":e:"+(++eventSequence));
        m.put("eventKind",kind);m.put("phase",phase);m.put("loggedAtWallTimeMs",wallTimeMs);
        m.put("evidenceLevel","OBSERVATION".equals(phase)?"OBSERVED":"NATIVE_RECEIPT".equals(phase)?"NATIVE_RECEIPT_ONLY":
                "LATER_WITNESS".equals(phase)||phase.contains("STATE")?"DERIVED":"LEGACY_UNCLASSIFIED");
        if("strategy_responder_mode_observed".equals(kind))m.put("legacyLabelBoundary","LEGACY_DIVE_LABEL_NOT_ACTUAL_MODE_PROOF");
        m.put("occurredAtGameTimeMs",null);m.put("occurredAtStatus","NOT_PROVEN_BY_G1");
        GameClock.Observation state=clock.latestState();
        m.put("detectedAtGameTimeMs",state==null?null:state.sourceGameTimeMs);
        m.put("detectedAtGameTimeBasis","LATEST_STATE_SAMPLE_NOT_NATIVE_RECEIVE_TIME");
        m.put("observation",observation==null?null:observation.metadata());
        m.put("stateObservationId",state==null?null:state.observationId);
        m.put("command",command==null?null:command.metadata());
        m.put("context",extra==null?Collections.emptyMap():new LinkedHashMap<String,Object>(extra));
        return m;
    }
    public Map<String,Object> event(String kind,long wallTimeMs,Map<String,Object> extra){
        return event(kind,wallTimeMs,clock.latestState(),null,"EXISTING_EVENT",extra);
    }
    public Map<String,Object> link(CommandSpan span,String phase,GameClock.Observation evidence){
        return event("command_trace",System.currentTimeMillis(),evidence,span,phase,null);
    }
}
