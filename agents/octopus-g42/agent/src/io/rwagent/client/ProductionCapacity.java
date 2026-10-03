package io.rwagent.client;

/** Small diagnosis shared by the existing military-target and factory-target controllers.
 * Persistent income/demand evidence and utilisation are supplied by BattleClient's windows.
 * UNKNOWN idle time is not evidence of an operational or producer bottleneck.
 */
final class ProductionCapacity {
    enum Bottleneck { MONEY_LIMITED, RESERVE_PROTECTED, NO_USEFUL_DEMAND, ARMY_CAPACITY_LIMIT,
        HARD_SAFETY_CAP, PRODUCER_THROUGHPUT_LIMIT, PRODUCER_TECH_LIMIT, ROUTE_UNAVAILABLE,
        CAPACITY_EXPANSION_COMMITTED, UNKNOWN }
    static Bottleneck classify(int committed,int target,int hard,String demand,int routes,
            boolean techBlocked,boolean budgetKnown,double credits,double cost,double reserved,
            boolean recovery,boolean expansion,boolean sustained,double utilization,int saturation){
        if(committed>=hard)return Bottleneck.HARD_SAFETY_CAP;
        if("NONE".equals(demand))return Bottleneck.NO_USEFUL_DEMAND;
        if("ROUTE_UNAVAILABLE".equals(demand))return Bottleneck.ROUTE_UNAVAILABLE;
        if(!"KNOWN".equals(demand))return Bottleneck.UNKNOWN;
        if(routes==0)return techBlocked?Bottleneck.PRODUCER_TECH_LIMIT:Bottleneck.ROUTE_UNAVAILABLE;
        if(!budgetKnown)return Bottleneck.UNKNOWN;
        if(credits<cost)return Bottleneck.MONEY_LIMITED;
        if(recovery||credits-cost<reserved)return Bottleneck.RESERVE_PROTECTED;
        if(expansion)return Bottleneck.CAPACITY_EXPANSION_COMMITTED;
        if(committed>=target)return Bottleneck.ARMY_CAPACITY_LIMIT;
        if(sustained&&utilization>=saturation)return Bottleneck.PRODUCER_THROUGHPUT_LIMIT;
        return Bottleneck.UNKNOWN;
    }
}
