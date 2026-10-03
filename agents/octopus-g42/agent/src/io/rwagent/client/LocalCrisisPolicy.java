package io.rwagent.client;

import java.util.*;

/** A small detachment envelope, not a combat simulator. Current HP is a durability floor;
 * compatibility and a known approach must be supplied by the legal/native adapter first.
 * Prices, hidden contacts and hypothetical damage never enter this policy.
 */
public final class LocalCrisisPolicy {
    public static final int MAX_THREATS=3,MIN_RESPONDERS=2,MAX_RESPONDERS=6,MIN_MAIN=6;
    public static final double ASSET_RADIUS=450,CLUSTER_RADIUS=250,RESPONSE_RADIUS=1500,HP_FACTOR=1.5;
    public static final long MAX_ACTIVE_MS=45000,LOST_CONTACT_MS=5000,RETURN_WAIT_MS=15000,RETRY_MS=12000;

    public static List<Map<String,Object>> select(List<Map<String,Object>> compatible,
            int availableMain,double enemyHp,double x,double y,Map<Long,Long> memberships,
            Map<Long,Integer> cohortSizes){
        if(availableMain<MIN_MAIN+MIN_RESPONDERS||!(enemyHp>0)||!Double.isFinite(enemyHp))return Collections.emptyList();
        List<Map<String,Object>> candidates=new ArrayList<Map<String,Object>>();
        for(Map<String,Object> unit:compatible){
            double hp=number(unit,"hp"),max=number(unit,"maxHp");
            if(hp>0&&max>0&&hp>=max*.5&&distance(unit,x,y)<=RESPONSE_RADIUS)candidates.add(unit);
        }
        Collections.sort(candidates,(a,b)->{int d=Double.compare(distance(a,x,y),distance(b,x,y));
            return d!=0?d:Long.compare(id(a),id(b));});
        Map<Long,Integer> left=new HashMap<Long,Integer>(cohortSizes);
        List<Map<String,Object>> selected=new ArrayList<Map<String,Object>>();double hp=0;
        for(Map<String,Object> unit:candidates){
            if(selected.size()>=MAX_RESPONDERS||availableMain-selected.size()<=MIN_MAIN)break;
            Long cohort=memberships.get(id(unit));
            if(cohort!=null&&left.get(cohort)<=LocalArmyDirector.RELEASE_BELOW)continue;
            selected.add(unit);hp+=number(unit,"hp");
            if(cohort!=null)left.put(cohort,left.get(cohort)-1);
            if(selected.size()>=MIN_RESPONDERS&&hp>=enemyHp*HP_FACTOR)return selected;
        }
        return Collections.emptyList();
    }
    private static double number(Map<String,Object> u,String key){Object v=u.get(key);return v instanceof Number?((Number)v).doubleValue():0;}
    private static long id(Map<String,Object> u){return ((Number)u.get("id")).longValue();}
    private static double distance(Map<String,Object> u,double x,double y){return Math.hypot(number(u,"x")-x,number(u,"y")-y);}
}
