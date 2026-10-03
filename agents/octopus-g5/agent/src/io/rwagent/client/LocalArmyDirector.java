package io.rwagent.client;

import java.util.*;

/** Bounded local groups inside the default main owner. Inputs must already exclude all task leases.
 * This stores own-unit membership and accepted goals only; enemy legality stays in BattleClient.
 */
public final class LocalArmyDirector {
    public static final int MAX_COHORTS=4,MAX_MEMBERS=48,FORM_MINIMUM=6,RELEASE_BELOW=3;
    public static final double FORM_RADIUS=400,SEPARATE_DISTANCE=1000,LOCAL_TARGET_RADIUS=1400;
    public static final long ORDER_INTERVAL_MS=8000,MEMORY_HOLD_MS=30000;
    public static final class Cohort {
        public final long id;
        private final LinkedHashSet<Long> members=new LinkedHashSet<Long>();
        public double x,y,goalX,goalY,bestDistance=Double.MAX_VALUE;
        public Long targetId;
        public long lastAcceptedOrder=-100000,frontierAt,progressAt,lastPlanAt=-100000,frontierTile=-1,lastIdleRecovery=-100000;
        public String lastPlanStatus="NOT_QUERIED";
        public boolean frontierActive;
        public final LinkedHashSet<Long> avoided=new LinkedHashSet<Long>();
        private Cohort(long id){this.id=id;}
        public Set<Long> memberIds(){return Collections.unmodifiableSet(members);}
        public List<Map<String,Object>> units(Map<Long,Map<String,Object>> own){
            List<Map<String,Object>> out=new ArrayList<Map<String,Object>>();
            for(Long id:members){Map<String,Object> unit=own.get(id);if(unit!=null)out.add(unit);}
            return out;
        }
        public boolean due(long now){return now-lastAcceptedOrder>=ORDER_INTERVAL_MS;}
        /** A real missing native order can recover once before the normal cadence, not every tick. */
        public boolean idleRecoveryDue(long now){return now-lastAcceptedOrder>=2000&&now-lastIdleRecovery>=ORDER_INTERVAL_MS;}
    }
    private final LinkedHashMap<Long,Cohort> cohorts=new LinkedHashMap<Long,Cohort>();
    private final List<Map<String,Object>> changes=new ArrayList<Map<String,Object>>();
    private long counter,cursor=-1;

    public void observe(List<Map<String,Object>> main,long now){
        Map<Long,Map<String,Object>> own=index(main);
        Iterator<Cohort> old=cohorts.values().iterator();
        while(old.hasNext()){
            Cohort cohort=old.next();boolean changed=cohort.members.retainAll(own.keySet());
            if(cohort.members.size()<RELEASE_BELOW){
                change("RELEASED_BELOW_THREE",cohort,now);old.remove();
            }else{
                recenter(cohort,own);
                if(changed)change("MEMBERS_NO_LONGER_AVAILABLE",cohort,now);
            }
        }
        // A genuinely separated formation can become independent once six members gather there.
        // Existing groups never merge merely because their routes cross, which avoids relabel churn.
        for(Cohort cohort:new ArrayList<Cohort>(cohorts.values())){
            if(cohorts.size()>=MAX_COHORTS)break;
            if(cohort.members.size()<FORM_MINIMUM+RELEASE_BELOW)continue;
            Map<String,Object> anchor=own.get(cohort.members.iterator().next());
            List<Map<String,Object>> remote=new ArrayList<Map<String,Object>>();
            for(Long id:cohort.members){Map<String,Object> unit=own.get(id);
                if(distance(unit,number(anchor,"x"),number(anchor,"y"))>=SEPARATE_DISTANCE)remote.add(unit);}
            List<Map<String,Object>> formation=formation(remote);
            if(formation.size()>=FORM_MINIMUM&&cohort.members.size()-formation.size()>=RELEASE_BELOW){
                Cohort split=create(formation,own,now,"SEPARATED_FORMATION");
                cohort.members.removeAll(split.members);recenter(cohort,own);change("MEMBERS_TRANSFERRED",cohort,now);
            }
        }
        Set<Long> held=new HashSet<Long>();for(Cohort cohort:cohorts.values())held.addAll(cohort.members);
        List<Map<String,Object>> remaining=new ArrayList<Map<String,Object>>();
        for(Map<String,Object> unit:main)if(!held.contains(id(unit)))remaining.add(unit);
        while(cohorts.size()<MAX_COHORTS){
            List<Map<String,Object>> formation=formation(remaining);
            if(formation.size()<FORM_MINIMUM)break;
            Cohort cohort=create(formation,own,now,"LOCAL_FORMATION");
            Iterator<Map<String,Object>> it=remaining.iterator();
            while(it.hasNext())if(cohort.members.contains(id(it.next())))it.remove();
        }
        // Sub-six reinforcements keep moving with the nearest group; no standing reserve is created.
        Set<Cohort> enlarged=new LinkedHashSet<Cohort>();
        for(Map<String,Object> unit:remaining){
            Cohort nearest=null;double best=Double.MAX_VALUE;
            for(Cohort cohort:cohorts.values())if(cohort.members.size()<MAX_MEMBERS){
                double d=distance(unit,cohort.x,cohort.y);
                if(d<best){best=d;nearest=cohort;}
            }
            if(nearest!=null){nearest.members.add(id(unit));enlarged.add(nearest);}
        }
        for(Cohort cohort:enlarged){recenter(cohort,own);change("REINFORCEMENTS_JOINED",cohort,now);}
    }
    public boolean multiple(){return cohorts.size()>=2;}
    public List<Cohort> rotation(){
        List<Cohort> out=new ArrayList<Cohort>(cohorts.values());
        int split=0;while(split<out.size()&&out.get(split).id<=cursor)split++;
        if(split>0&&split<out.size())Collections.rotate(out,-split);
        return out;
    }
    /** Call only after the shared arbiter and native endpoint accepted this command. */
    public void accepted(Cohort cohort,long now,double x,double y,Long target){
        if(cohorts.get(cohort.id)!=cohort)throw new IllegalArgumentException("Released cohort");
        cohort.lastAcceptedOrder=now;cohort.goalX=x;cohort.goalY=y;cohort.targetId=target;cursor=cohort.id;
    }
    public List<Map<String,Object>> drainChanges(){
        List<Map<String,Object>> out=new ArrayList<Map<String,Object>>(changes);changes.clear();return out;
    }
    public static Map<Long,Map<String,Object>> index(List<Map<String,Object>> units){
        Map<Long,Map<String,Object>> own=new LinkedHashMap<Long,Map<String,Object>>();
        for(Map<String,Object> unit:units)if(own.put(id(unit),unit)!=null)throw new IllegalArgumentException("Duplicate own actor");
        return own;
    }
    private Cohort create(List<Map<String,Object>> formation,Map<Long,Map<String,Object>> own,long now,String reason){
        Cohort cohort=new Cohort(++counter);for(Map<String,Object> unit:formation)cohort.members.add(id(unit));
        recenter(cohort,own);cohorts.put(cohort.id,cohort);change(reason,cohort,now);return cohort;
    }
    private static List<Map<String,Object>> formation(List<Map<String,Object>> units){
        List<Map<String,Object>> best=Collections.emptyList();
        for(final Map<String,Object> seed:units){
            List<Map<String,Object>> local=new ArrayList<Map<String,Object>>();
            for(Map<String,Object> unit:units)if(distance(unit,number(seed,"x"),number(seed,"y"))<=FORM_RADIUS)local.add(unit);
            if(local.size()>best.size()){
                Collections.sort(local,(a,b)->Double.compare(distance(a,number(seed,"x"),number(seed,"y")),distance(b,number(seed,"x"),number(seed,"y"))));
                best=new ArrayList<Map<String,Object>>(local.subList(0,Math.min(local.size(),MAX_MEMBERS)));
            }
        }
        return best;
    }
    private static void recenter(Cohort cohort,Map<Long,Map<String,Object>> own){
        cohort.x=cohort.y=0;
        for(Long id:cohort.members){Map<String,Object> unit=own.get(id);cohort.x+=number(unit,"x");cohort.y+=number(unit,"y");}
        cohort.x/=cohort.members.size();cohort.y/=cohort.members.size();
    }
    private void change(String reason,Cohort cohort,long now){
        Map<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("cohortId",cohort.id);data.put("unitIds",new ArrayList<Long>(cohort.members));data.put("reason",reason);
        data.put("centerX",cohort.x);data.put("centerY",cohort.y);data.put("gameTimeMs",now);changes.add(data);
    }
    private static double number(Map<String,Object> unit,String key){return ((Number)unit.get(key)).doubleValue();}
    private static long id(Map<String,Object> unit){return ((Number)unit.get("id")).longValue();}
    private static double distance(Map<String,Object> unit,double x,double y){return Math.hypot(number(unit,"x")-x,number(unit,"y")-y);}
}
