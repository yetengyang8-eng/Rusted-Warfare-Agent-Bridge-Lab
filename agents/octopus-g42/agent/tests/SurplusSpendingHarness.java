package io.rwagent.client;

import java.util.*;

/** Deterministic budgets and paid-product observation gaps, without a game process. */
public final class SurplusSpendingHarness {
    static int checks;
    static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static Map<String,Object> map(Object... pairs){Map<String,Object> result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put(String.valueOf(pairs[i]),pairs[i+1]);return result;}
    static Map<String,Object> unit(long id,String type){return map("id",id,"type",type,"hp",600,"maxHp",600,"dead",false,"buildProgress",1,
        "mobile",!"landFactory".equals(type),"canAttack",!"landFactory".equals(type),"productionQueue",0);}
    static Map<String,Object> state(double credits,int artillery){
        List<Map<String,Object>> units=new ArrayList<Map<String,Object>>();units.add(unit(5,"landFactory"));units.add(unit(6,"landFactory"));
        for(int i=0;i<24;i++)units.add(unit(10+i,"heavyTank"));for(int i=0;i<artillery;i++)units.add(unit(100+i,"heavyArtillery"));
        return map("ownUnits",units,"player",map("credits",credits));
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> units(Map<String,Object> state){return (List<Map<String,Object>>)state.get("ownUnits");}
    static Map<String,Object> factory(double cost){return map("id",5L,"queue",0,"actions",Arrays.asList(
        map("actionId","u_heavyTank","type","heavyTank","cost",800,"affordable",true),
        map("actionId","u_heavyArtillery","type","heavyArtillery","cost",cost,"affordable",true)));}
    static Map<String,Object> enemies(String domain){return map("visibleEnemies",Arrays.asList(map("id",90L,"building",true,"targetDomain",domain)));}
    static SurplusSpendingPolicy.Decision evaluate(Map<String,Object> state,Map<String,Object> enemies,Map<String,Object> factory,
        SurplusSpendingPolicy.Ledger ledger,int target,int cap,int normal,int committed,double reserved,boolean emergency){
        return SurplusSpendingPolicy.evaluate(new SurplusSpendingPolicy.Inputs(state,enemies,factory,ledger,target,cap,normal,committed,reserved,emergency));
    }
    static void policy(){
        SurplusSpendingPolicy.Ledger empty=SurplusSpendingPolicy.Ledger.empty();Map<String,Object> nativeMenu=factory(4700);
        require(evaluate(state(6300,0),enemies("SURFACE"),nativeMenu,empty,40,128,24,24,0,false).selected,"native 4700 price plus two native 800 replacements may buy");
        SurplusSpendingPolicy.Decision low=evaluate(state(6299,0),enemies("SURFACE"),nativeMenu,empty,40,128,24,24,0,false);
        require(!low.selected&&"ORDINARY_REPLACEMENTS_OR_RESERVES_PROTECTED".equals(low.reason),"one credit below exact budget remains protected");
        require(!evaluate(state(9000,0),enemies("SURFACE"),nativeMenu,empty,40,128,24,24,3000,false).selected,"all shared reserves count before artillery");
        require(evaluate(state(9300,0),enemies("SURFACE"),nativeMenu,empty,40,128,24,24,3000,false).selected,"exact cost plus reserves and two replacements is accepted");
        require(!evaluate(state(20000,0),enemies("AIR"),nativeMenu,empty,40,128,24,24,0,false).selected,"air-only visible target cannot motivate ground artillery");
        require(!evaluate(state(20000,0),map("visibleEnemies",Collections.emptyList(),"rememberedEnemies",enemies("SURFACE").get("visibleEnemies")),nativeMenu,empty,40,128,24,24,0,false).selected,"remembered buildings do not create visible evidence");
        require(!evaluate(state(20000,0),enemies("SURFACE"),nativeMenu,empty,40,128,23,24,0,false).selected,"twenty-three normal troops keep ordinary recovery first");
        require(!evaluate(state(20000,0),enemies("SURFACE"),nativeMenu,empty,40,128,24,24,0,true).selected,"home emergency blocks optional quality spend");
        require(!evaluate(state(20000,0),enemies("SURFACE"),nativeMenu,empty,40,128,24,40,0,false).selected,"paid global commitments cannot exceed current target");
        require(!evaluate(state(20000,0),enemies("SURFACE"),nativeMenu,empty,80,24,24,24,0,false).selected,"hard cap bounds a larger strategy target");
        require(!evaluate(state(20000,3),enemies("SURFACE"),nativeMenu,empty,40,128,24,27,0,false).selected,"three is the quota for target forty");
        require(!evaluate(state(20000,6),enemies("SURFACE"),nativeMenu,empty,128,128,24,30,0,false).selected,"quota stays at six even with large target");
        nativeMenu.put("queue",1);require(!evaluate(state(20000,0),enemies("SURFACE"),nativeMenu,empty,40,128,24,24,0,false).selected,"busy producer cannot be selected");
        nativeMenu=factory(Double.NaN);require(!evaluate(state(20000,0),enemies("SURFACE"),nativeMenu,empty,40,128,24,24,0,false).selected,"unknown price cannot spend");
        nativeMenu=factory(4700);nativeMenu.put("actions",Arrays.asList(map("actionId","u_heavyArtillery","type","heavyArtillery","cost",4700,"affordable",true)));
        require(!evaluate(state(20000,0),enemies("SURFACE"),nativeMenu,empty,40,128,24,24,0,false).selected,"missing ordinary native quote leaves budget unknown");
    }
    static void paidProducts(){
        Map<String,Object> initial=state(20000,2);SurplusSpendingPolicy.Ledger empty=SurplusSpendingPolicy.Ledger.empty();
        SurplusSpendingPolicy.Ledger paid=empty.accepted(initial,5,1000);
        require(empty.pending.isEmpty()&&paid.pending.size()==1,"accept creates a new immutable ledger");
        require(!evaluate(initial,enemies("SURFACE"),factory(3100),paid,40,128,24,26,0,false).selected,"paid product already fills the last artillery quota slot");
        units(initial).get(0).put("productionQueue",1);paid=paid.observe(initial,5000);
        require(paid.pending.size()==1&&paid.unobservedSlots(initial)==0,"visible native queue is counted once by global native bound");
        units(initial).get(0).put("productionQueue",0);paid=paid.observe(initial,20000);
        require(paid.pending.size()==1&&paid.unobservedSlots(initial)==1,"empty queue without new product retains paid ghost slot");
        units(initial).get(0).put("productionQueue",-1);require(paid.unobservedSlots(initial)==1,"unknown native queue cannot hide paid commitment");
        units(initial).get(0).put("productionQueue",0);
        require(!evaluate(initial,enemies("SURFACE"),factory(3100),paid,40,128,24,26,0,false).selected,"observation gap cannot buy duplicate artillery");
        units(initial).add(unit(500,"heavyArtillery"));paid=paid.observe(initial,24000);
        require(paid.pending.isEmpty()&&paid.matched.contains(500L),"new observed product replaces pending slot");
        require(!evaluate(initial,enemies("SURFACE"),factory(3100),paid,40,128,24,27,0,false).selected,"observed product keeps the same quota without double counting");
        Map<String,Object> unfinished=state(20000,2);paid=empty.accepted(unfinished,5,1000);
        Map<String,Object> growing=unit(550,"heavyArtillery");growing.put("buildProgress",.5);units(unfinished).add(growing);
        paid=paid.observe(unfinished,5000);require(paid.pending.size()==1&&!paid.matched.contains(550L),"unfinished product cannot fulfill a ready role");
        SurplusSpendingPolicy.Decision waiting=evaluate(unfinished,enemies("SURFACE"),factory(3100),paid,40,128,24,27,0,false);
        require(!waiting.selected&&((Number)waiting.evidence.get("artilleryObserved")).intValue()==2,"unfinished paid product counts once through pending quota");
        growing.put("buildProgress",1);units(unfinished).get(0).put("productionQueue",1);paid=paid.observe(unfinished,7000);
        require(paid.pending.isEmpty()&&paid.matched.contains(550L),"ready available product fulfills role while producer has another busy queue");
        Map<String,Object> parallel=state(20000,0);paid=empty.accepted(parallel,5,1000).accepted(parallel,6,2000);
        units(parallel).add(unit(600,"heavyArtillery"));paid=paid.observe(parallel,10000);
        require(paid.pending.size()==1&&paid.matched.size()==1,"one unit fulfills only one of two paid orders");
        paid=paid.observe(parallel,12000);require(paid.pending.size()==1,"same unit is not recycled to fulfill second order");
        units(parallel).add(unit(601,"heavyArtillery"));paid=paid.observe(parallel,14000);
        require(paid.pending.isEmpty()&&paid.matched.size()==2,"second distinct unit settles second order");
        paid=empty.accepted(state(20000,0),5,1000).observe(state(20000,0),180999);require(paid.pending.size()==1,"observation timeout is not premature");
        paid=paid.observe(state(20000,0),181000);require(paid.pending.isEmpty()&&"PRODUCT_OBSERVATION_TIMEOUT".equals(paid.transitions.get(0).get("reason")),"observation timeout releases with reason");
        Map<String,Object> lost=state(20000,0);paid=empty.accepted(lost,5,1000);units(lost).remove(0);paid=paid.observe(lost,2000);
        require(paid.pending.isEmpty()&&"PRODUCER_LOST".equals(paid.transitions.get(0).get("reason")),"producer loss releases paid commitment");
        Map<String,Object> almostFull=state(20000,0);paid=empty.accepted(almostFull,5,1000);
        SurplusSpendingPolicy.Decision capped=evaluate(almostFull,enemies("SURFACE"),factory(3100),paid,25,128,24,24,0,false);
        require(!capped.selected&&"NO_ARMY_SLOT".equals(capped.reason),"empty-queue paid slot counts toward global target as well as role quota");
    }
    public static void main(String[] args){policy();paidProducts();System.out.println("SurplusSpendingHarness checks="+checks+" PASS");}
}
