package io.rwagent.client;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import static io.rwagent.client.StrategyDirector.*;

/** Policy/real delayed-build execution contracts. Reflection supplies private state, not a game. */
public final class ProductionCapacityHarness {
    static int checks;
    static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static Field field(Class<?> c,String name)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
    static void set(Object value,String name,Object data)throws Exception{field(value.getClass(),name).set(value,data);}
    static Object get(Object value,String name)throws Exception{return field(value.getClass(),name).get(value);}
    static ProductionCapacity.Bottleneck classify(int army,int target,int hard,String demand,double credits,double reserve,boolean recovery,boolean committed,boolean sustained,double busy){
        return ProductionCapacity.classify(army,target,hard,demand,1,false,true,credits,800,reserve,recovery,committed,sustained,busy,80);
    }
    static void diagnoses(){
        require(classify(96,96,128,"KNOWN",100000,0,false,false,true,0)==ProductionCapacity.Bottleneck.ARMY_CAPACITY_LIMIT,"strategy cap with idle producer is army limit");
        require(classify(82,104,128,"KNOWN",100000,0,false,false,true,100)==ProductionCapacity.Bottleneck.PRODUCER_THROUGHPUT_LIMIT,"free military slots and sustained busy producer are throughput limit");
        require(classify(128,128,128,"KNOWN",100000,0,false,false,true,100)==ProductionCapacity.Bottleneck.HARD_SAFETY_CAP,"busy hard cap cannot expand");
        require(classify(20,40,128,"KNOWN",799,0,false,false,true,100)==ProductionCapacity.Bottleneck.MONEY_LIMITED,"native quote not covered");
        require(classify(40,40,128,"KNOWN",1000,500,false,false,true,0)==ProductionCapacity.Bottleneck.RESERVE_PROTECTED,"reserve cannot fund military increase");
        require(classify(40,40,128,"KNOWN",100000,0,true,false,true,0)==ProductionCapacity.Bottleneck.RESERVE_PROTECTED,"recovery cannot fund military increase");
        require(classify(20,40,128,"NONE",100000,0,false,false,true,100)==ProductionCapacity.Bottleneck.NO_USEFUL_DEMAND,"cash without demand cannot expand");
        require(classify(20,40,128,"UNKNOWN",100000,0,false,false,true,100)==ProductionCapacity.Bottleneck.UNKNOWN,"unknown remains unknown");
        require(classify(20,40,128,"KNOWN",100000,0,false,true,true,100)==ProductionCapacity.Bottleneck.CAPACITY_EXPANSION_COMMITTED,"one commitment at a time");
        require(classify(20,40,128,"KNOWN",100000,0,false,false,false,100)==ProductionCapacity.Bottleneck.UNKNOWN,"one busy observation is insufficient");
    }
    static void militaryFloor()throws Exception{
        BattleClient host=new BattleClient();set(host,"log",new BufferedWriter(new StringWriter()));
        CommandArbiter execution=(CommandArbiter)get(host,"execution");
        execution.observe(new CommandArbiter.Stamp("capacity-fixture","0",1,200000),Collections.<Long>emptyList());
        StrategyDirector strategy=(StrategyDirector)get(host,"strategy");strategy.enable(map("strategyContractVersion",1),128);
        set(strategy,"armyTarget",96);set(strategy,"activeTarget",86);
        require(strategy.increaseMilitaryCapacity(96,8,map("gameTimeMs",200000)),"96 policy target may grow while hard cap128 has space");
        require(strategy.armyTarget()==104&&(Integer)get(strategy,"militaryCapacityFloor")==104,"capacity floor is retained beyond old96 formula");
        require(!strategy.increaseMilitaryCapacity(104,0,map()),"no zero-slot increase");
        set(strategy,"armyTarget",128);require(!strategy.increaseMilitaryCapacity(128,8,map()),"hard safety cap cannot grow");
    }
    static void delayedReserve(String reserve)throws Exception{
        BattleClient client=new BattleClient();StringWriter output=new StringWriter();set(client,"log",new BufferedWriter(output));
        Map<String,Object> builder=map("id",4L,"type","builder","hp",1000,"dead",false,"x",100,"y",100,"buildProgress",1);
        Map<String,Object> state=map("sessionId","s","gameTimeMs",10000,"ownUnits",Arrays.asList(builder),"player",map("credits",1000));
        Class<?> jobClass=Class.forName("io.rwagent.client.BattleClient$BuildJob");
        Constructor<?> constructor=jobClass.getDeclaredConstructor(String.class,double.class,double.class,long.class,long.class,String.class);constructor.setAccessible(true);
        Object job=constructor.newInstance("landFactory",100d,100d,700L,8000L,"native plan");set(client,"buildJob",job);set(client,"time",10000L);
        if(!"capability".equals(reserve))set(client,reserve+"Reserve",500L);
        else{
            StrategyDirector strategy=(StrategyDirector)get(client,"strategy");
            Class<?> funding=Class.forName("io.rwagent.client.StrategyDirector$CapabilityFunding");
            Constructor<?> fc=funding.getDeclaredConstructor(long.class,long.class,String.class,double.class,long.class);fc.setAccessible(true);
            set(strategy,"capabilityFunding",fc.newInstance(74L,5L,"engineer",500d,8000L));
        }
        Method advance=BattleClient.class.getDeclaredMethod("advanceBuildJob",Map.class);advance.setAccessible(true);advance.invoke(client,state);
        ((BufferedWriter)get(client,"log")).flush();
        require(output.toString().contains("RESERVE_PROTECTED"),reserve+" reserve is rechecked after plan and before delayed native order");
        require(!(Boolean)get(job,"orderAccepted"),reserve+" hold cannot accept factory order");
    }
    static void paidGap()throws Exception{
        BattleClient client=new BattleClient();BattleClient.Pending pending=new BattleClient.Pending();pending.type="heavyTank";pending.started=1000;pending.active=true;
        Map<Long,BattleClient.Pending> jobs=(Map<Long,BattleClient.Pending>)get(client,"pending");jobs.put(5L,pending);
        Map<String,Object> factory=map("id",5L,"type","landFactory","hp",1000,"dead",false,"productionQueue",0);
        Map<String,Object> state=map("ownUnits",Arrays.asList(factory));
        Method count=BattleClient.class.getDeclaredMethod("ordinaryUnobservedSlots",Map.class);count.setAccessible(true);
        require((Integer)count.invoke(client,state)==1,"ordinary paid empty-queue gap holds a safety slot");
        pending.type="heavyArtillery";require((Integer)count.invoke(client,state)==0,"artillery stays exclusively on its existing commitment ledger");
        pending.type="upgrade";require((Integer)count.invoke(client,state)==0,"upgrade is not an extra ordinary paid slot");
    }
    static void delayedHardCap()throws Exception{
        BattleClient client=new BattleClient();StringWriter output=new StringWriter();set(client,"log",new BufferedWriter(output));
        StrategyDirector strategy=(StrategyDirector)get(client,"strategy");strategy.enable(map("strategyContractVersion",1),128);
        List<Map<String,Object>> own=new ArrayList<Map<String,Object>>();
        own.add(map("id",4L,"type","builder","hp",1000,"dead",false,"x",100,"y",100,"buildProgress",1));
        for(long i=10;i<138;i++)own.add(map("id",i,"type","heavyTank","hp",1000,"dead",false,"mobile",true,"canAttack",true));
        Map<String,Object> state=map("sessionId","s","ownUnits",own,"player",map("credits",100000));set(strategy,"state",state);set(client,"mobileUnitHardCap",128);
        Class<?> jobClass=Class.forName("io.rwagent.client.BattleClient$BuildJob");Constructor<?> constructor=jobClass.getDeclaredConstructor(String.class,double.class,double.class,long.class,long.class,String.class);constructor.setAccessible(true);
        Object job=constructor.newInstance("landFactory",100d,100d,700L,8000L,"native plan");set(job,"capacityExpansion",true);set(client,"buildJob",job);set(client,"time",10000L);
        Method advance=BattleClient.class.getDeclaredMethod("advanceBuildJob",Map.class);advance.setAccessible(true);advance.invoke(client,state);((BufferedWriter)get(client,"log")).flush();
        require(output.toString().contains("HARD_SAFETY_CAP"),"unpaid expansion is held when hard cap becomes full after planning");
        require(!(Boolean)get(job,"orderAccepted"),"cash cannot execute delayed expansion at hard cap");
        while(own.size()>2)own.remove(own.size()-1);
        set(client,"capacityEvidence",map("demand","UNKNOWN"));advance.invoke(client,state);((BufferedWriter)get(client,"log")).flush();
        require(output.toString().contains("CURRENT_LEGAL_DEMAND_UNPROVEN"),"planned unpaid factory waits when fresh demand becomes unknown");
        set(client,"capacityEvidence",map("demand","KNOWN","recovery",true));advance.invoke(client,state);((BufferedWriter)get(client,"log")).flush();
        require(output.toString().contains("RECOVERY_PROTECTED"),"planned unpaid factory waits during dangerous recovery");
    }
    public static void main(String[] args)throws Exception{
        diagnoses();militaryFloor();delayedReserve("builder");delayedReserve("investment");delayedReserve("capability");paidGap();delayedHardCap();
        System.out.println("PRODUCTION_CAPACITY_CONTRACT_OK checks="+checks+" fixtureOnly=true");
    }
}
