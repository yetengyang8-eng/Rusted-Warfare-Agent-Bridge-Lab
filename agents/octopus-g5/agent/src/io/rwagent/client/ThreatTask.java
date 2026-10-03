package io.rwagent.client;

import java.util.*;

/** Commander attention only. CLEAR means visibility disappeared, never a proven kill. */
public final class ThreatTask {
    public enum Status {CURRENT,UNKNOWN,CLEAR}
    public final long id;
    public final Set<Long> enemyIds;
    public final double x,y;
    public final long sourceFrame,sourceGameTimeMs;
    public final Status status;
    public final String reason;
    ThreatTask(long id,Collection<Long> enemies,double x,double y,long frame,long time,Status status,String reason){
        this.id=id;enemyIds=Collections.unmodifiableSet(new LinkedHashSet<Long>(enemies));this.x=x;this.y=y;
        sourceFrame=frame;sourceGameTimeMs=time;this.status=status;this.reason=reason;
    }
    ThreatTask withStatus(Status value,String why){return new ThreatTask(id,enemyIds,x,y,sourceFrame,sourceGameTimeMs,value,why);}
    public Long primaryEnemyId(){return status==Status.CURRENT&&!enemyIds.isEmpty()?enemyIds.iterator().next():null;}
    public Map<String,Object> metadata(){Map<String,Object> result=new LinkedHashMap<String,Object>();
        result.put("taskId",id);result.put("enemyIds",new ArrayList<Long>(enemyIds));result.put("status",status.name());result.put("reason",reason);
        result.put("x",status==Status.CURRENT?x:null);result.put("y",status==Status.CURRENT?y:null);
        result.put("sourceFrame",sourceFrame);result.put("sourceGameTimeMs",sourceGameTimeMs);result.put("killClaim",false);return result;}
}
