package io.rwagent.client;

import java.util.*;

/** The caller supplies legally/native compatible actors. This tests the bounded HP/ownership envelope. */
public final class LocalCrisisContractHarness {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static Map<String,Object> unit(long id,double x,double hp,double max){
        Map<String,Object> u=new LinkedHashMap<String,Object>();u.put("id",id);u.put("x",x);u.put("y",0.0);
        u.put("hp",hp);u.put("maxHp",max);u.put("cost",999999);return u;
    }
    private static List<Map<String,Object>> select(List<Map<String,Object>> own,int main,double enemy,
            Map<Long,Long> memberships,Map<Long,Integer> sizes){return LocalCrisisPolicy.select(own,main,enemy,0,0,memberships,sizes);}
    public static void main(String[] args){
        List<Map<String,Object>> own=new ArrayList<Map<String,Object>>();
        for(long i=1;i<=12;i++)own.add(unit(i,i*10,600,600));
        Map<Long,Long> membership=new HashMap<Long,Long>();Map<Long,Integer> sizes=new HashMap<Long,Integer>();
        List<Map<String,Object>> two=select(own,12,50,membership,sizes);
        check(two.size()==2,"minimum two responders even for a weak visible contact");
        check(two.get(0).get("id").equals(1L)&&two.get(1).get("id").equals(2L),"nearest deterministic selection");
        check(select(own,7,50,membership,sizes).isEmpty(),"six main actors remain");
        check(select(own,8,1000,membership,sizes).isEmpty(),"do not breach reserve to meet HP floor");
        check(select(own,12,1000,membership,sizes).size()==3,"current HP envelope, not cost");
        check(select(own,12,2500,membership,sizes).isEmpty(),"six cap prevents risky partial response");
        check(select(own,12,Double.NaN,membership,sizes).isEmpty(),"unknown HP is not capacity");
        check(select(own,12,0,membership,sizes).isEmpty(),"absent HP is not capacity");
        own.set(0,unit(1,10,200,600));
        check(select(own,12,50,membership,sizes).get(0).get("id").equals(2L),"damaged actor stays out");
        own.set(0,unit(1,1600,600,600));
        check(select(own,12,50,membership,sizes).get(0).get("id").equals(2L),"far army is not pulled back");
        for(long i=2;i<=5;i++)membership.put(i,1L);sizes.put(1L,4);
        List<Map<String,Object>> split=select(own,12,50,membership,sizes);
        check(split.size()==2,"another nearby cohort can provide remaining response");
        int borrowed=0;for(Map<String,Object> u:split)if(membership.containsKey(u.get("id")))borrowed++;
        check(borrowed==1,"each stable cohort retains three actors");
        LocalArmyDirector d=new LocalArmyDirector();List<Map<String,Object>> group=new ArrayList<Map<String,Object>>();
        for(long i=20;i<28;i++)group.add(unit(i,1000+i,600,600));d.observe(group,0);
        LocalArmyDirector.Cohort c=d.rotation().get(0);d.accepted(c,1000,2000,0,null);
        check(!c.idleRecoveryDue(2000),"one observation does not bypass cadence");
        check(c.idleRecoveryDue(3000),"missing native order can recover after two game seconds");
        c.lastIdleRecovery=3000;check(!c.idleRecoveryDue(4000),"recovery cannot repeat every tick");
        check(c.idleRecoveryDue(11000),"recovery shares bounded eight-second cadence");
        System.out.println("LocalCrisisContractHarness: "+checks+" checks passed");
    }
}
