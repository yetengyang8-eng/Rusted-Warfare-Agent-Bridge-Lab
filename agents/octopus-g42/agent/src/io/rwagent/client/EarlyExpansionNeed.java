package io.rwagent.client;

import java.util.*;

/** One logistics target from a fixed map prior. Never an occupancy/safety/buildability oracle.
 * Native current-site plans and own product witnesses are separate transitions. */
public final class EarlyExpansionNeed {
    public enum State { REQUESTED, APPROACHING, LEGAL_SITE_OBSERVED, CONSTRUCTION_OBSERVED, COMPLETE, CANCELLED }
    public final String knowledgeId,sourceObservationId;
    public final int tile;
    public final long builderId;
    public final double x,y;
    private State state=State.REQUESTED;
    private long acceptedFrame=-1;
    private final List<Map<String,Object>> changes=new ArrayList<Map<String,Object>>();
    public EarlyExpansionNeed(String knowledge,String observation,int tile,long builder,double x,double y){
        if(knowledge==null||knowledge.isEmpty()||observation==null||observation.isEmpty()||tile<0||builder<0||!Double.isFinite(x)||!Double.isFinite(y))
            throw new IllegalArgumentException("Static resource identity and own logistics actor required");
        this.knowledgeId=knowledge;sourceObservationId=observation;this.tile=tile;builderId=builder;this.x=x;this.y=y;
        change(null,"STATIC_RESOURCE_CANDIDATE");
    }
    public State state(){return state;}
    public boolean live(){return state!=State.COMPLETE&&state!=State.CANCELLED;}
    public long acceptedFrame(){return acceptedFrame;}
    public void commandAccepted(long frame){if(!live()||frame<0)return;acceptedFrame=frame;
        if(state==State.REQUESTED)transition(State.APPROACHING,"NATIVE_MOVE_ACCEPTED_NOT_ARRIVED");}
    public void nativeSiteObserved(){if(live()&&state!=State.LEGAL_SITE_OBSERVED&&state!=State.CONSTRUCTION_OBSERVED)
        transition(State.LEGAL_SITE_OBSERVED,"CURRENT_NATIVE_SITE_PLAN_NOT_CONSTRUCTION");}
    public void ownExtractorObserved(boolean ready){if(!live())return;
        if(ready)transition(State.COMPLETE,"CURRENT_OWN_READY_EXTRACTOR_WITNESS");
        else if(state!=State.CONSTRUCTION_OBSERVED)transition(State.CONSTRUCTION_OBSERVED,"CURRENT_OWN_UNFINISHED_EXTRACTOR_WITNESS");}
    public void cancel(String reason){if(live())transition(State.CANCELLED,reason);}
    private void transition(State next,String reason){Map<String,Object> before=metadata();state=next;change(before,reason);}
    private void change(Map<String,Object> before,String reason){changes.add(StrategyDirector.map("reason",reason,"before",before,"after",metadata()));}
    public List<Map<String,Object>> drainChanges(){List<Map<String,Object>> out=new ArrayList<Map<String,Object>>(changes);changes.clear();return out;}
    public Map<String,Object> metadata(){return StrategyDirector.map("knowledgeId",knowledgeId,"sourceObservationId",sourceObservationId,
        "tile",tile,"builderId",builderId,"x",x,"y",y,"state",state.name(),"acceptedFrame",acceptedFrame,
        "safe","UNKNOWN","enemyOccupancy","UNKNOWN","dynamicReachability","UNKNOWN",
        "buildable",state==State.LEGAL_SITE_OBSERVED?"CURRENT_NATIVE_PLAN_ONLY":"UNKNOWN");}
}
