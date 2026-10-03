package io.rwagent.client;

import java.util.*;

public final class MineInvestmentHarness {
    static int checks;
    static void require(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static Map<String,Object> m(Object... v){Map<String,Object> m=new LinkedHashMap<String,Object>();for(int i=0;i<v.length;i+=2)m.put(String.valueOf(v[i]),v[i+1]);return m;}
    static Map<String,Object> mine(String type){return m("id",3L,"type",type,"x",1000.,"y",1000.,"hp",1000.,"maxHp",1000.,"buildProgress",1.,"productionQueue",0);}
    static Map<String,Object> quote(String owner,String product,double cost){return m("id",3L,"type",owner,"product",product,"cost",cost,"actionId","live", "queue",0,"affordable",true);}
    static Map<String,Object> emptyEnemies(){return m("visibleEnemies",Collections.emptyList());}
    static Map<String,Object> emptyScout(){return m("rememberedThreats",Collections.emptyList());}
    static void observe(MineInvestmentPolicy p,Map<String,Object> mine,long now,Map<String,Object> enemies,Map<String,Object> scout){p.observe(m("sessionId","s","gameTimeMs",now,"ownUnits",Arrays.asList(mine)),enemies,scout);}
    static MineInvestmentPolicy.Decision evaluate(MineInvestmentPolicy p,Map<String,Object> mine,Map<String,Object> q,long now){return p.evaluate(mine,q,now,1800,103017,1200,800,39,false,false);}
    public static void main(String[] args){
        MineInvestmentPolicy p=new MineInvestmentPolicy();Map<String,Object> mine=mine("extractorT1"),q=quote("extractorT1","extractorT2",1400);
        observe(p,mine,0,emptyEnemies(),emptyScout());observe(p,mine,44000,emptyEnemies(),emptyScout());
        require(!evaluate(p,mine,q,44000).selected,"quiet window prevents immediate unverified upgrade");
        observe(p,mine,45000,emptyEnemies(),emptyScout());MineInvestmentPolicy.Decision longGame=evaluate(p,mine,q,45000);
        require(longGame.selected,"rich near-target force can invest in a locally stable long game");
        require(Math.abs(((Number)longGame.evidence.get("paybackEstimateGameSeconds")).doubleValue()-1400/6.035)<.001,"disclosed calibrated delta drives payback");
        require(!p.evaluate(mine,q,45000,300,103017,1200,800,39,false,false).selected,"short game cannot pay back plus conversion and risk margin");
        require(!p.evaluate(mine,q,45000,1800,4000,1200,800,39,false,false).selected,"two military replacements and all reserves protected");
        require(p.evaluate(mine,q,45000,1800,4200,1200,800,39,false,false).selected,"exact protected cash threshold is sufficient");
        require(!p.evaluate(mine,q,45000,1800,103017,1200,-1,39,false,false).selected,"unknown ordinary quote is conservative");
        require(!p.evaluate(mine,q,45000,1800,103017,-1,800,39,false,false).selected,"unknown reserve budget is conservative");
        require(!p.evaluate(mine,q,45000,1800,103017,1200,800,5,false,false).selected,"military recovery floor protects a collapsed force");
        require(!p.evaluate(mine,q,45000,1800,103017,1200,800,39,true,false).selected,"paid upgrade never duplicates");
        require(!p.evaluate(mine,q,45000,1800,103017,1200,800,39,false,true).selected,"nearby idle builder and cheaper new site win priority");
        q.put("queue",-1);require(!evaluate(p,mine,q,45000).selected,"unknown native queue is not empty");q.put("queue",0);
        q.put("affordable",false);require(!evaluate(p,mine,q,45000).selected,"native affordability remains required");q.put("affordable",true);
        q.put("cost",Double.NaN);require(!evaluate(p,mine,q,45000).selected,"unknown native cost remains required");q.put("cost",1400.);
        q.put("id",4L);require(!evaluate(p,mine,q,45000).selected,"quote belongs to exact owned mine");q.put("id",3L);
        require(!evaluate(p,mine,q,46000).selected,"stale local snapshot cannot authorize spending");
        Map<String,Object> threat=m("x",1010.,"y",1010.,"canAttack",true,"range",100.);
        observe(p,mine,46000,m("visibleEnemies",Arrays.asList(threat)),emptyScout());require(!evaluate(p,mine,q,46000).selected,"visible armed raid resets local risk window");
        observe(p,mine,90000,emptyEnemies(),m("rememberedThreats",Arrays.asList(threat)));require(!evaluate(p,mine,q,90000).selected,"fogged remembered risk still blocks");
        observe(p,mine,91000,emptyEnemies(),emptyScout());observe(p,mine,136000,emptyEnemies(),emptyScout());require(evaluate(p,mine,q,136000).selected,"a new full quiet window recovers");
        mine.put("hp",950.);observe(p,mine,137000,emptyEnemies(),emptyScout());require(!evaluate(p,mine,q,137000).selected,"actual damage resets even healthy mine window");
        mine.put("hp",840.);observe(p,mine,200000,emptyEnemies(),emptyScout());require(!evaluate(p,mine,q,200000).selected,"damaged mine below85percent never spends");
        mine.put("hp",1000.);observe(p,mine,210000,emptyEnemies(),emptyScout());observe(p,mine,255000,emptyEnemies(),emptyScout());require(evaluate(p,mine,q,255000).selected,"healed mine waits a fresh quiet window");
        // A home raid 1,200 units away does not veto this stable mine.
        observe(p,mine,256000,m("visibleEnemies",Arrays.asList(m("x",0.,"y",0.,"canAttack",true))),emptyScout());require(evaluate(p,mine,q,256000).selected,"distant home crisis is not a global upgrade veto");
        mine.put("type","extractorT2");q=quote("extractorT2","extractorT3",5300);observe(p,mine,257000,emptyEnemies(),emptyScout());
        observe(p,mine,436000,emptyEnemies(),emptyScout());require(!evaluate(p,mine,q,436000).selected,"T3 requires its own longer stable window after conversion");
        observe(p,mine,437000,emptyEnemies(),emptyScout());MineInvestmentPolicy.Decision t3=evaluate(p,mine,q,437000);
        require(t3.selected,"native T2 to T3 long-game quote accepted");
        require(Math.abs(((Number)t3.evidence.get("paybackEstimateGameSeconds")).doubleValue()-5300/12.07)<.001,"T3 uses actual changed native price, not4000constant");
        require(!p.evaluate(mine,q,437000,650,103017,1200,800,39,false,false).selected,"T3 shorter than actual quote payback protected");
        q.put("product","extractorT3_overclocked");require(!evaluate(p,mine,q,437000).selected,"fragile overclock not accidentally whitelisted");
        q.put("product","extractorT3");observe(p,mine,438000,emptyEnemies(),null);require(!evaluate(p,mine,q,438000).selected,"missing threat telemetry stays unknown");
        observe(p,mine,1,emptyEnemies(),emptyScout());require(!evaluate(p,mine,q,1).selected,"time rewind resets old lifetime");
        System.out.println("MineInvestmentHarness checks="+checks+" PASS");
    }
}
