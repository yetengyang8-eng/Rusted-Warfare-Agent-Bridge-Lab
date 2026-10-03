package io.rwagent.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Engine-free G1 provenance contract: IDs and metadata have no execution authority. */
public final class G1TraceHarness {
    private static int checks;
    private static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    private static Map<String,Object> payload(String session,Long time,Long frame){
        Map<String,Object> m=new LinkedHashMap<String,Object>();
        m.put("sessionId",session);if(time!=null)m.put("gameTimeMs",time);if(frame!=null)m.put("frame",frame);
        return m;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object m){return (Map<String,Object>)m;}

    public static void main(String[] args){
        GameClock clock=new GameClock("run-a");
        Map<String,Object> state=payload("session-a",1000L,10L);
        Map<String,Object> original=new LinkedHashMap<String,Object>(state);
        GameClock.Observation s1=clock.observe("/state",state,5000,5010,1000L);
        GameClock.Observation combat=clock.observe("/combat/observe",payload("session-a",1050L,11L),5011,5020,1000L);
        GameClock.Observation scout=clock.observe("/scout/observe",payload("session-a",1100L,12L),5021,5030,1000L);
        GameClock.Observation menu=clock.observe("/combat/production",payload("session-a",null,null),5031,5040,1000L);
        check(state.equals(original),"source input never mutated");
        check(combat.sourceGameTimeMs==1050&&scout.sourceGameTimeMs==1100,"endpoint clocks remain distinct");
        check(combat.detectedAtGameTimeMs==1000,"detected time is supplied state context, not combat source");
        check(menu.sourceGameTimeMs==null&&menu.sourceFrame==null,"menu missing native source time stays UNKNOWN");
        check(menu.metadata().get("sourceRangeEndGameTimeMs")==null,"range cannot synthesize absent source time");
        check(!s1.id.equals(combat.id)&&!combat.id.equals(menu.id),"reads receive independent identities");
        check(Boolean.FALSE.equals(combat.metadata().get("atomicWithOtherEndpoints")),"no atomic cross-endpoint claim");

        GameClock.Observation s2=clock.observe("/state",payload("session-a",1000L,10L),5050,5060,1000L);
        check(!s1.id.equals(s2.id),"equal native timestamps still have distinct ObservationId");
        check(s2.discontinuities.contains("REPEATED_SOURCE_STAMP"),"repeat stamp marked without deduplicating reads");
        check(s2.previousObservationId.equals(s1.id)&&s2.previousSourceGameTimeMs==1000,"range follows same endpoint only");
        GameClock.Observation c2=clock.observe("/combat/observe?targetId=9",payload("session-a",1250L,15L),5061,5070,1000L);
        check(c2.previousObservationId.equals(combat.id)&&c2.previousSourceGameTimeMs==1050,"query does not invent endpoint family or borrow state range");
        check(clock.latest("/combat/observe?targetId=3")==c2,"canonical endpoint latest read");
        check("/combat/observe?targetId=9".equals(c2.requestPath),"query context preserved separately from endpoint family");
        Map<String,Object> ownPlayer=new LinkedHashMap<String,Object>();ownPlayer.put("teamId",2L);
        Map<String,Object> ownPayload=payload("s",1L,1L);ownPayload.put("player",ownPlayer);
        Map<String,Object> combatPlayer=payload("s",1L,1L);combatPlayer.put("player",2L);
        check(clock.observe("/player-own",ownPayload,0,0,null).sourcePlayerId.equals(
                clock.observe("/player-combat",combatPlayer,0,0,null).sourcePlayerId),"legal numeric and structured team identity canonicalized consistently");

        GameClock.Observation rollback=clock.observe("/state",payload("session-a",900L,9L),5080,5075,900L);
        check(rollback.discontinuities.contains("SOURCE_FRAME_ROLLBACK")&&rollback.discontinuities.contains("SOURCE_GAME_TIME_ROLLBACK"),"rollback trace only, no guard changes");
        check("ROLLBACK_NOT_INTERVAL".equals(rollback.metadata().get("sourceRangeStatus")),"backward range not asserted as event interval");
        check(rollback.discontinuities.contains("WALL_CLOCK_ROLLBACK_DURING_READ"),"wall clock adjustment not game clock advance");
        GameClock.Observation changed=clock.observe("/state",payload("session-b",10L,1L),5081,5090,10L);
        check(changed.discontinuities.contains("SESSION_CHANGED"),"session switch observationally marked");
        check(changed.previousSourceGameTimeMs==null,"different session never supplies interval start");
        GameClock.Observation unknown=clock.observe("/legacy",payload(null,null,null),5091,5095,null);
        check(unknown.detectedAtGameTimeMs==null&&unknown.sourceSessionId==null,"caller missing context remains null");

        G1Trace trace=new G1Trace(clock,"run-a");
        List<Long> actors=new ArrayList<Long>(Arrays.asList(7L));
        List<String> inputs=new ArrayList<String>(clock.latestObservationIds());
        G1Trace.CommandSpan span=trace.beginCommand("recon:7",actors,"/command/move",inputs);
        actors.clear();inputs.clear();
        check(span.actors.size()==1&&!span.inputObservationIds.isEmpty(),"command copies input lists");
        check(span.stateObservationId.equals(changed.id),"proposal records state context");
        Map<String,Object> before=span.metadata();span.admission(null);
        Map<String,Object> receipt=new LinkedHashMap<String,Object>();receipt.put("status","queued");receipt.put("frame",2L);
        trace.rememberReceipt(span,"native-request-1",receipt);
        check("NOT_RECORDED".equals(before.get("admission")),"emitted metadata remains stable after span advancement");
        check(trace.commandForRequest("native-request-1")==span,"native receipt lookup joins later witness");
        check(span.metadata().get("nativeReceiptRequestIdFromBody")==null&&span.metadata().get("receiptRequestIdMatches")==null,"missing native request echo UNKNOWN");
        Map<String,Object> r=trace.event("command_result",5100,changed,span,"NATIVE_RECEIPT",null);
        check(Boolean.FALSE.equals(map(r.get("command")).get("receiptProvesExecution")),"queued receipt never proves execution or readiness");
        check(Long.valueOf(2L).equals(map(r.get("command")).get("nativeReceiptFrame"))
                &&map(r.get("command")).get("nativeReceiptSourceGameTimeMs")==null,"receipt frame retained but absent receipt game time never inherited");
        check(r.get("occurredAtGameTimeMs")==null,"no difference detection promoted to true occurrence time");
        check(map(r.get("command")).get("ownerGeneration")==null&&"NOT_IMPLEMENTED_G3".equals(map(r.get("command")).get("ownerGenerationStatus")),"generation is honestly missing");
        GameClock.Observation later=clock.observe("/state",payload("session-b",20L,3L),5110,5120,20L);
        Map<String,Object> context=new LinkedHashMap<String,Object>();context.put("witnessKind","ACTIVE_ORDER");
        Map<String,Object> witness=trace.event("recon_move_witness",5120,later,span,"LATER_WITNESS",context);
        check(map(witness.get("command")).get("commandId").equals(map(r.get("command")).get("commandId")),"receipt and explicit witness share command identity");
        check(!map(witness.get("observation")).get("observationId").equals(map(r.get("observation")).get("observationId")),"later witness has new observation identity");
        check(!witness.get("eventId").equals(r.get("eventId")),"equal or close wall timestamps do not merge EventId");
        map(witness.get("context")).put("witnessKind","changed");
        check("ACTIVE_ORDER".equals(context.get("witnessKind")),"trace decoration cannot mutate legacy context");
        check(receipt.size()==2&&"queued".equals(receipt.get("status")),"receipt input preserved");
        G1Trace.CommandSpan denied=trace.beginCommand("strategy:engineer-1",Arrays.asList(2L),"/economy/construct",null);
        denied.admission("COMMAND_GAME_TIME_BUDGET");
        check("DENIED".equals(denied.metadata().get("admission"))&&!Boolean.TRUE.equals(denied.metadata().get("receiptRecorded")),"admission denial has no fabricated native receipt");
        check(!denied.commandId.equals(span.commandId)&&!denied.intentId.equals(span.intentId),"every direct proposal receives distinct monotonic IDs");
        Map<String,Object> mismatch=new LinkedHashMap<String,Object>();mismatch.put("requestId","different-native-id");
        trace.rememberReceipt(denied,"wire-id",mismatch);
        check(Boolean.FALSE.equals(denied.metadata().get("receiptRequestIdMatches")),"echo mismatch only marked as evidence, never modifies admission");
        check("DENIED".equals(denied.metadata().get("admission")),"receipt diagnostics never replace gate result");

        CommandArbiter arbiter=new CommandArbiter();
        CommandArbiter.Stamp stamp=new CommandArbiter.Stamp("session-b","2",3,20);
        arbiter.observe(stamp,Arrays.asList(7L));
        arbiter.claim("recon:7",7L);
        Intent executionIntent=Intent.create("g3-test-1","recon:7",arbiter.snapshotGenerations("recon:7",Arrays.asList(7L)),
                Arrays.asList(7L),"MOVE","recon",40,stamp,later.id,"/state","/command/move",Intent.Commitment.none());
        G1Trace.CommandSpan attached=trace.beginCommand("recon:7",Arrays.asList(7L),"/command/move",null);
        attached.executionIntent(executionIntent);
        Long proposedGeneration=(Long)attached.metadata().get("ownerGeneration");
        check("g3-test-1".equals(attached.metadata().get("executionIntentId")),"G1 command joins explicit G3 intent");
        check(proposedGeneration.equals(arbiter.ownerGeneration(7L)),"trace records actual per actor generation at proposal");
        arbiter.release("recon:7");
        arbiter.claim("recon:7",7L);
        check(!proposedGeneration.equals(arbiter.ownerGeneration(7L)),"release and reacquire advances ABA generation");
        check(proposedGeneration.equals(attached.metadata().get("ownerGeneration")),"later witness preserves proposal generation after transfer");
        check(Boolean.FALSE.equals(attached.metadata().get("receiptProvesExecution")),"explicit Intent does not turn receipt into execution witness");

        for(int i=0;i<400;i++){
            G1Trace.CommandSpan x=trace.beginCommand("rule-main",Arrays.asList(1L),"/produce",null);
            trace.rememberReceipt(x,"bounded-"+i,receipt);
            clock.observe("/endpoint-"+i,payload("s",1L,1L),0,0,null);
        }
        check(trace.retainedReceiptCount()==256,"receipt metadata bounded");
        check(trace.commandForRequest("native-request-1")==null,"evicted metadata remains unknown rather than recomputed");
        check(clock.retainedEndpointCount()==32,"endpoint provenance bounded");
        System.out.println("G1TraceHarness PASS checks="+checks+" evidence=E2_NO_ENGINE");
    }
}
