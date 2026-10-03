package io.rwagent.client;

import java.util.*;

/** Mine upgrades use native quotes and a bounded, observed local risk history.
 * A quiet observation window is not a prediction of future survival. The payback model
 * uses measured T1 income and frozen native generation ratios, with disclosed margins.
 */
final class MineInvestmentPolicy {
    private static final double T1_INCOME=12.07;
    private static final class History {
        String type; long clearSince; double hp;
        History(String type,long now,double hp){this.type=type;clearSince=now;this.hp=hp;}
    }
    private final Map<Long,History> history=new HashMap<Long,History>();
    private String session;
    private long observedAt=-1;
    void observe(Map<String,Object> state,Map<String,Object> enemies,Map<String,Object> scout){
        long now=(long)number(state,"gameTimeMs",-1);
        String current=String.valueOf(state.get("sessionId"));
        if(!current.equals(session)||now<observedAt){history.clear();session=current;}
        observedAt=now;
        Set<Long> live=new HashSet<Long>();
        for(Map<String,Object> mine:items(state,"ownUnits")){
            long id=id(mine);String type=String.valueOf(mine.get("type"));
            if(!"extractorT1".equals(type)&&!"extractorT2".equals(type))continue;
            live.add(id);double hp=number(mine,"hp",-1),max=number(mine,"maxHp",-1);
            History old=history.get(id);
            boolean healthy=positive(hp)&&positive(max)&&hp/max>=.85
                &&number(mine,"buildProgress",0)>=1&&!Boolean.TRUE.equals(mine.get("dead"));
            if(!healthy||localRisk(mine,enemies,scout)||now<0){history.remove(id);continue;}
            // Damage and conversion reset the local clear window. Healing cannot erase a raid.
            if(old==null||!type.equals(old.type)||hp<old.hp-.01)old=new History(type,now,hp);
            old.hp=hp;history.put(id,old);
        }
        history.keySet().retainAll(live);
    }
    static final class Decision {
        final boolean selected;final String reason,product;final Map<String,Object> evidence;
        Decision(String reason,String product,Map<String,Object> evidence){
            this.reason=reason;this.product=product;selected="LOCAL_PAYBACK_WINDOW_ACCEPTED".equals(reason);
            evidence.put("selected",selected);evidence.put("reason",reason);
            this.evidence=Collections.unmodifiableMap(evidence);
        }
    }
    Decision evaluate(Map<String,Object> mine,Map<String,Object> quote,long now,double remainingSeconds,
                      double credits,double allReserved,double ordinaryPrice,int armed,boolean pending,boolean cheaperSiteReady){
        String owner=String.valueOf(mine==null?null:mine.get("type"));
        String product=String.valueOf(quote==null?null:quote.get("product"));
        boolean t2="extractorT1".equals(owner)&&"extractorT2".equals(product);
        boolean t3="extractorT2".equals(owner)&&"extractorT3".equals(product);
        double gain=t2?T1_INCOME*4/8:t3?T1_INCOME*8/8:-1;
        double cost=number(quote,"cost",-1),payback=positive(cost)&&positive(gain)?cost/gain:-1;
        double conversion=t2?28:t3?54:0,margin=t2?90:t3?180:0;
        long quietRequired=t2?45000:180000;
        History h=history.get(id(mine));
        long quiet=h==null?-1:now-h.clearSince;
        double required=cost+allReserved+2*ordinaryPrice,window=payback+conversion+margin;
        Map<String,Object> evidence=map("mineId",id(mine),"sourceType",owner,"product",product,"nativeCost",cost,
            "ordinaryUnitNativeCost",ordinaryPrice,"credits",credits,"allReserved",allReserved,"requiredCredits",required,
            "observedLocalClearGameSeconds",quiet/1000.0,"requiredLocalClearGameSeconds",quietRequired/1000.0,
            "incomeGainEstimate",gain,"paybackEstimateGameSeconds",payback,"upgradeTimeEstimateGameSeconds",conversion,
            "survivalMarginGameSeconds",margin,"requiredRemainingGameSeconds",window,"remainingGameSeconds",remainingSeconds,
            "armed",armed,"militaryFloor",6,"cheaperSiteReady",cheaperSiteReady,
            "incomeModel","MEASURED_T1_12_07_X_FROZEN_GENERATION_DELTA_OVER_8",
            "survivalModel","OBSERVED_LOCAL_QUIET_WINDOW_AND_BUDGET_HORIZON_NOT_A_SURVIVAL_PREDICTION");
        String reason="LOCAL_PAYBACK_WINDOW_ACCEPTED";
        if(!t2&&!t3)reason="UNSUPPORTED_NATIVE_UPGRADE";
        else if(mine==null||id(mine)!=id(quote)||!owner.equals(quote.get("type")))reason="OWN_MINE_QUOTE_MISMATCH";
        else if(observedAt!=now||h==null||!owner.equals(h.type))reason="LOCAL_RISK_OR_HEALTH_UNCONFIRMED";
        else if(quiet<quietRequired)reason="LOCAL_CLEAR_WINDOW_TOO_SHORT";
        else if(pending||number(quote,"queue",-1)!=0||number(mine,"productionQueue",-1)!=0)reason="MINE_UPGRADE_ALREADY_COMMITTED";
        else if(!(quote.get("actionId") instanceof String)||!positive(cost))reason="NATIVE_QUOTE_UNKNOWN";
        else if(!positive(ordinaryPrice)||!Double.isFinite(credits)||!Double.isFinite(allReserved)||allReserved<0)reason="PROTECTED_BUDGET_UNKNOWN";
        else if(armed<6)reason="MILITARY_FLOOR_RECOVERY";
        else if(cheaperSiteReady)reason="CHEAPER_LOCAL_NEW_MINE_FIRST";
        else if(!Boolean.TRUE.equals(quote.get("affordable"))||credits<required)reason="REPLACEMENTS_OR_RESERVES_PROTECTED";
        else if(!Double.isFinite(remainingSeconds)||remainingSeconds<=window)reason="PAYBACK_HORIZON_TOO_SHORT";
        return new Decision(reason,product,evidence);
    }
    private static boolean localRisk(Map<String,Object> mine,Map<String,Object> enemies,Map<String,Object> scout){
        // Missing threat telemetry is UNKNOWN, never an empty safe neighbourhood.
        if(scout==null||!(scout.get("rememberedThreats") instanceof List)
            ||enemies==null||!(enemies.get("visibleEnemies") instanceof List))return true;
        double x=number(mine,"x",Double.NaN),y=number(mine,"y",Double.NaN);
        if(!Double.isFinite(x)||!Double.isFinite(y))return true;
        for(Map<String,Object> e:items(enemies,"visibleEnemies"))
            if(Boolean.TRUE.equals(e.get("canAttack"))&&near(e,x,y))return true;
        for(Map<String,Object> e:items(scout,"rememberedThreats"))if(near(e,x,y))return true;
        return false;
    }
    private static boolean near(Map<String,Object> e,double x,double y){
        double ex=number(e,"x",Double.NaN),ey=number(e,"y",Double.NaN),range=number(e,"range",0);
        if(!Double.isFinite(ex)||!Double.isFinite(ey)||!Double.isFinite(range))return true;
        return Math.hypot(ex-x,ey-y)<Math.max(450,range+100);
    }
    private static boolean positive(double n){return Double.isFinite(n)&&n>0;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> m,String k){return m!=null&&m.get(k) instanceof List?(List<Map<String,Object>>)m.get(k):Collections.<Map<String,Object>>emptyList();}
    private static long id(Map<String,Object> m){return (long)number(m,"id",-1);}
    private static double number(Map<String,Object> m,String k,double d){return m!=null&&m.get(k) instanceof Number?((Number)m.get(k)).doubleValue():d;}
    private static Map<String,Object> map(Object... v){Map<String,Object> m=new LinkedHashMap<String,Object>();for(int i=0;i<v.length;i+=2)m.put(String.valueOf(v[i]),v[i+1]);return m;}
}
