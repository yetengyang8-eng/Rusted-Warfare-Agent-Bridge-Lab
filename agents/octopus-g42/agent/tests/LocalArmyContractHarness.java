package io.rwagent.client;

import java.util.*;

/** Own-observation membership, regrouping and accepted-order scheduling without an engine. */
public final class LocalArmyContractHarness {
    private static int checks;
    private static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    private static Map<String,Object> unit(long id,double x,double y){
        Map<String,Object> u=new LinkedHashMap<String,Object>();u.put("id",id);u.put("x",x);u.put("y",y);return u;
    }
    private static List<Map<String,Object>> group(int first,int count,double x,double y){
        List<Map<String,Object>> out=new ArrayList<Map<String,Object>>();
        for(int i=0;i<count;i++)out.add(unit(first+i,x+i*4,y));return out;
    }
    private static Set<Long> members(LocalArmyDirector d){
        Set<Long> ids=new HashSet<Long>();
        for(LocalArmyDirector.Cohort c:d.rotation()){
            check(c.memberIds().size()<=48,"native group remains bounded");
            for(Long id:c.memberIds())check(ids.add(id),"one main unit has one cohort");
        }
        check(d.rotation().size()<=4,"cohort count is bounded");return ids;
    }
    public static void main(String[] args){
        LocalArmyDirector d=new LocalArmyDirector();List<Map<String,Object>> main=group(10,8,1000,1000);
        d.observe(main,1000);check(!d.multiple(),"one local group leaves legacy tactics available");
        main.addAll(group(100,8,4000,1000));d.observe(main,2000);
        check(d.multiple(),"two independent nearby formations are recognized");
        check(members(d).size()==16,"all eligible main units have membership");
        LocalArmyDirector.Cohort first=d.rotation().get(0),second=d.rotation().get(1);
        check(first.memberIds().contains(10L)&&!first.memberIds().contains(100L),"distant front has independent actors");
        List<Map<String,Object>> crossing=new ArrayList<Map<String,Object>>();
        for(Map<String,Object> u:main)crossing.add(unit(((Number)u.get("id")).longValue(),2000,1000));
        d.observe(crossing,3000);
        check(first.memberIds().contains(10L)&&second.memberIds().contains(100L),"crossing fronts keep accepted identity");
        check(first.due(3000)&&second.due(3000),"unaccepted intentions consume no local cooldown");
        d.accepted(first,3000,1500,1200,700L);
        check(d.rotation().get(0)==second,"only accepted order advances rotation");
        check(!first.due(10999)&&first.due(11000),"accepted game-time interval boundary");
        check(second.lastAcceptedOrder<0,"another cohort does not share last-order time");
        d.accepted(second,4000,4500,1200,800L);
        check(first.targetId==700L&&second.targetId==800L,"each cohort stores its own accepted target");
        List<Map<String,Object>> damaged=new ArrayList<Map<String,Object>>();
        damaged.add(unit(10,1000,1000));damaged.add(unit(11,1000,1000));damaged.addAll(group(100,8,4000,1000));
        d.observe(damaged,5000);check(d.rotation().size()==1,"a group below three releases");
        check(members(d).size()==10,"survivors reinforce an available nearby group");
        check(!members(d).contains(12L),"removed or leased actors leave all cohorts");
        d.observe(Collections.<Map<String,Object>>emptyList(),6000);
        check(d.rotation().isEmpty(),"empty own observation releases every group");

        LocalArmyDirector many=new LocalArmyDirector();List<Map<String,Object>> mass=new ArrayList<Map<String,Object>>();
        for(int i=0;i<5;i++)mass.addAll(group(i*100,30,1000+i*2000,1000));
        many.observe(mass,1000);check(many.rotation().size()==4,"five regions cannot create unlimited controllers");
        check(members(many).size()==150,"excess region reinforces the closest non-full group");
        LocalArmyDirector packed=new LocalArmyDirector();packed.observe(group(1,128,1000,1000),1000);
        check(members(packed).size()==128,"large formation never leaves cap-excess main units stranded");
        for(LocalArmyDirector.Cohort c:packed.rotation())check(c.memberIds().size()>=6,"new independent group needs six");
        LocalArmyDirector spread=new LocalArmyDirector();spread.observe(group(1,16,1000,1000),1000);
        List<Map<String,Object>> split=group(1,8,1000,1000);split.addAll(group(9,8,4000,1000));
        spread.observe(split,2000);check(spread.multiple(),"six already-held remote members can form their own front");
        check(members(spread).size()==16,"formation splitting preserves every actor exactly once");
        LocalArmyDirector small=new LocalArmyDirector();small.observe(group(1,5,1000,1000),1000);
        check(small.rotation().isEmpty(),"five alone cannot fabricate a new main formation");
        System.out.println("LocalArmyContractHarness PASS checks="+checks+" evidence=E2_NO_ENGINE");
    }
}
