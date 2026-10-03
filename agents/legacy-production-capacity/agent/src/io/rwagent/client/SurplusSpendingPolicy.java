package io.rwagent.client;

import java.util.*;

/** Bounded surplus use. All input is already observed own state or a native menu.
 * Quotas and the thirty-second cash buffer are engineering limits, not optimality claims.
 * Accepted artillery orders remain committed through an empty-queue/observation gap.
 */
final class SurplusSpendingPolicy {
    static final String PRODUCT="heavyArtillery";
    static final int MIN_ORDINARY_FORCE=24, MAX_ARTILLERY=6;
    static final long PRODUCT_TIMEOUT_MS=180000;

    static final class Commitment {
        final long producer,acceptedAt;
        final Set<Long> before;
        Commitment(long producer,long acceptedAt,Set<Long> before){
            this.producer=producer;this.acceptedAt=acceptedAt;
            this.before=Collections.unmodifiableSet(new HashSet<Long>(before));
        }
    }

    /** Immutable ledger: matching an observed available product is not factory provenance. */
    static final class Ledger {
        final List<Commitment> pending;
        final Set<Long> matched;
        final List<Map<String,Object>> transitions;
        Ledger(List<Commitment> pending,Set<Long> matched,List<Map<String,Object>> transitions){
            this.pending=Collections.unmodifiableList(new ArrayList<Commitment>(pending));
            this.matched=Collections.unmodifiableSet(new HashSet<Long>(matched));
            this.transitions=Collections.unmodifiableList(new ArrayList<Map<String,Object>>(transitions));
        }
        static Ledger empty(){return new Ledger(Collections.<Commitment>emptyList(),Collections.<Long>emptySet(),Collections.<Map<String,Object>>emptyList());}
        Ledger accepted(Map<String,Object> state,long producer,long now){
            List<Commitment> next=new ArrayList<Commitment>(pending);
            Set<Long> before=new HashSet<Long>();for(Map<String,Object> unit:units(state))before.add(id(unit));
            next.add(new Commitment(producer,now,before));
            return new Ledger(next,matched,Collections.<Map<String,Object>>emptyList());
        }
        Ledger observe(Map<String,Object> state,long now){
            List<Commitment> next=new ArrayList<Commitment>();
            Set<Long> used=new HashSet<Long>(matched);
            List<Map<String,Object>> events=new ArrayList<Map<String,Object>>();
            List<Map<String,Object>> available=new ArrayList<Map<String,Object>>();
            for(Map<String,Object> unit:units(state))if(alive(unit)&&PRODUCT.equals(unit.get("type"))&&number(unit,"buildProgress",0)>=1)available.add(unit);
            Collections.sort(available,(a,b)->Long.compare(id(a),id(b)));
            for(Commitment order:pending){
                Map<String,Object> product=null;
                Map<String,Object> producer=find(state,order.producer);
                if(now>=order.acceptedAt)for(Map<String,Object> unit:available)
                    if(!order.before.contains(id(unit))&&!used.contains(id(unit))){product=unit;break;}
                if(product!=null){
                    used.add(id(product));events.add(map("producerId",order.producer,"unitId",id(product),"reason","OBSERVED_AVAILABLE_PRODUCT",
                        "assignmentSemantics","OBSERVED_AVAILABLE_ROLE_NOT_FACTORY_PROVENANCE"));
                }else if(producer==null||!alive(producer)){
                    events.add(map("producerId",order.producer,"reason","PRODUCER_LOST"));
                }else if(now-order.acceptedAt>=PRODUCT_TIMEOUT_MS){
                    events.add(map("producerId",order.producer,"reason","PRODUCT_OBSERVATION_TIMEOUT"));
                }else next.add(order);
            }
            return new Ledger(next,used,events);
        }
        int unobservedSlots(Map<String,Object> state){
            int count=0;
            for(Commitment order:pending){Map<String,Object> producer=find(state,order.producer);
                // A positive native queue is already counted in the global commitment bound.
                if(producer!=null&&number(producer,"productionQueue",0)<=0)count++;
            }
            return count;
        }
    }

    static final class Inputs {
        final Map<String,Object> state,enemies,factory;
        final Ledger ledger;
        final int armyTarget,hardCap,ordinaryForce,globalCommitted;
        final double allReserved;
        final boolean homeEmergency;
        Inputs(Map<String,Object> state,Map<String,Object> enemies,Map<String,Object> factory,Ledger ledger,
               int armyTarget,int hardCap,int ordinaryForce,int globalCommitted,double allReserved,boolean homeEmergency){
            this.state=state;this.enemies=enemies;this.factory=factory;this.ledger=ledger;
            this.armyTarget=armyTarget;this.hardCap=hardCap;this.ordinaryForce=ordinaryForce;this.globalCommitted=globalCommitted;
            this.allReserved=allReserved;this.homeEmergency=homeEmergency;
        }
    }
    static final class Decision {
        final boolean selected;
        final String reason;
        final Map<String,Object> action,evidence;
        Decision(boolean selected,String reason,Map<String,Object> action,Map<String,Object> evidence){
            this.selected=selected;this.reason=reason;this.action=action;
            Map<String,Object> copy=new LinkedHashMap<String,Object>(evidence);copy.put("selected",selected);copy.put("reason",reason);
            this.evidence=Collections.unmodifiableMap(copy);
        }
    }

    static Decision evaluate(Inputs in){
        Map<String,Object> artillery=null;double ordinaryPrice=-1;
        for(Map<String,Object> action:items(in.factory,"actions")){
            String type=String.valueOf(action.get("type"));
            if(PRODUCT.equals(type))artillery=action;
            if("heavyTank".equals(type)&&positive(number(action,"cost",-1)))ordinaryPrice=number(action,"cost",-1);
        }
        if(ordinaryPrice<0)for(Map<String,Object> action:items(in.factory,"actions"))
            if(("c_tank".equals(action.get("type"))||"tank".equals(action.get("type")))&&positive(number(action,"cost",-1))){ordinaryPrice=number(action,"cost",-1);break;}
        double cost=number(artillery,"cost",-1),credits=number(object(in.state.get("player")),"credits",-1);
        int observed=0,nativeCommitted=0;
        for(Map<String,Object> unit:units(in.state))if(alive(unit)){
            if(PRODUCT.equals(unit.get("type"))&&number(unit,"buildProgress",0)>=1)observed++;
            if(Boolean.TRUE.equals(unit.get("mobile"))&&Boolean.TRUE.equals(unit.get("canAttack")))nativeCommitted++;
            if("landFactory".equals(unit.get("type")))nativeCommitted+=(int)Math.max(0,number(unit,"productionQueue",0));
        }
        int quota=Math.min(MAX_ARTILLERY,Math.max(0,in.armyTarget/12));
        int committed=Math.max(in.globalCommitted,nativeCommitted)+in.ledger.unobservedSlots(in.state);
        double required=cost+2*ordinaryPrice+in.allReserved;
        Map<String,Object> evidence=map("producerId",id(in.factory),"product",PRODUCT,"nativeCost",cost,"ordinaryUnitNativeCost",ordinaryPrice,
            "credits",credits,"allReserved",in.allReserved,"requiredCredits",required,"ordinaryForce",in.ordinaryForce,
            "artilleryObserved",observed,"artilleryPending",in.ledger.pending.size(),"artilleryQuota",quota,
            "committedArmed",committed,"armyTarget",in.armyTarget,"hardSafetyCap",in.hardCap,"homeEmergency",in.homeEmergency);
        String reason=null;
        if(!positive(cost)||artillery==null||!(artillery.get("actionId") instanceof String))reason="NO_NATIVE_ARTILLERY_ACTION";
        else if(!positive(ordinaryPrice)||!Double.isFinite(credits)||!Double.isFinite(in.allReserved)||in.allReserved<0)reason="BUDGET_INPUT_UNKNOWN";
        else if(number(in.factory,"queue",-1)!=0)reason="PRODUCER_BUSY";
        else if(in.homeEmergency)reason="HOME_EMERGENCY";
        else if(in.ordinaryForce<MIN_ORDINARY_FORCE)reason="ORDINARY_FORCE_BELOW_MINIMUM";
        else if(committed>=Math.min(in.armyTarget,in.hardCap))reason="NO_ARMY_SLOT";
        else if(observed+in.ledger.pending.size()>=quota)reason="ARTILLERY_QUOTA_REACHED";
        else if(!surfaceBuildingVisible(in.enemies))reason="NO_VISIBLE_SURFACE_BUILDING";
        else if(!Boolean.TRUE.equals(artillery.get("affordable")))reason="NATIVE_ACTION_UNAFFORDABLE";
        else if(credits<required)reason="ORDINARY_REPLACEMENTS_OR_RESERVES_PROTECTED";
        return new Decision(reason==null,reason==null?"SURPLUS_SIEGE_ROLE":reason,reason==null?artillery:null,evidence);
    }

    private static boolean surfaceBuildingVisible(Map<String,Object> enemies){
        for(Map<String,Object> enemy:items(enemies,"visibleEnemies"))if(Boolean.TRUE.equals(enemy.get("building"))&&"SURFACE".equals(enemy.get("targetDomain")))return true;
        return false;
    }
    private static boolean positive(double value){return Double.isFinite(value)&&value>0;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return value instanceof Map?(Map<String,Object>)value:Collections.<String,Object>emptyMap();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> value,String key){return value!=null&&value.get(key) instanceof List?(List<Map<String,Object>>)value.get(key):Collections.<Map<String,Object>>emptyList();}
    private static List<Map<String,Object>> units(Map<String,Object> state){return items(state,"ownUnits");}
    private static double number(Map<String,Object> value,String key,double fallback){return value!=null&&value.get(key) instanceof Number?((Number)value.get(key)).doubleValue():fallback;}
    private static long id(Map<String,Object> value){return (long)number(value,"id",-1);}
    private static boolean alive(Map<String,Object> unit){return unit!=null&&!Boolean.TRUE.equals(unit.get("dead"))&&number(unit,"hp",0)>0;}
    private static Map<String,Object> find(Map<String,Object> state,long id){for(Map<String,Object> unit:units(state))if(id(unit)==id)return unit;return null;}
    private static Map<String,Object> map(Object... values){Map<String,Object> result=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)result.put(String.valueOf(values[i]),values[i+1]);return result;}
}
