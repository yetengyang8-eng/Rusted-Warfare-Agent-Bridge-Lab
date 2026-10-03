package io.rwagent.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Collective existing capability objective. Execution mode belongs exclusively to each member. */
public final class CapabilityTask {
    public final long taskId,targetId;
    public final String reason;
    private final Map<Long,CapabilityUnitMode> members=new LinkedHashMap<Long,CapabilityUnitMode>();
    private boolean legallyResolved;
    public CapabilityTask(long taskId,long targetId,String reason){this.taskId=taskId;this.targetId=targetId;this.reason=reason;}
    void attach(long unit,CapabilityUnitMode mode){if(unit!=mode.unitId)throw new IllegalArgumentException("member identity");members.put(unit,mode);}
    void detach(long unit){members.remove(unit);}
    public Map<Long,CapabilityUnitMode> members(){return Collections.unmodifiableMap(members);}
    void legalSiteCleared(){legallyResolved=true;}
    public Map<String,Object> snapshot(){Map<String,Object> modes=new LinkedHashMap<String,Object>();
        for(Map.Entry<Long,CapabilityUnitMode> e:members.entrySet())modes.put(String.valueOf(e.getKey()),e.getValue().snapshot());
        return StrategyDirector.map("capabilityTaskId",taskId,"targetId",targetId,"reason",reason,"legallyResolved",legallyResolved,
                "unitModes",modes,"modeScope","PER_UNIT","producerLineageStatus","NEEDS_EVIDENCE");}
}
