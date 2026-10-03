package io.rwagent.client;

import java.util.*;
import static io.rwagent.client.StrategyDirector.*;

/** A native menu quote, independent of the army-slot and money gates being diagnosed.
 * Duration and operational throughput are UNKNOWN until an adapter can observe them.
 * This first adapter covers ordinary land-factory production only.
 */
final class ProductionRoute {
    final long producer;
    final String producerType,product,action;
    final int tech,queue;
    final double cost;
    final boolean affordable;
    ProductionRoute(long producer,String producerType,String product,String action,int tech,int queue,double cost,boolean affordable){
        this.producer=producer;this.producerType=producerType;this.product=product;this.action=action;
        this.tech=tech;this.queue=queue;this.cost=cost;this.affordable=affordable;
    }
    static ProductionRoute ordinary(Map<String,Object> factory){
        List<ProductionRoute> routes=ordinaryOptions(factory);
        return routes.isEmpty()?null:routes.get(0);
    }
    static List<ProductionRoute> ordinaryOptions(Map<String,Object> factory){
        List<ProductionRoute> routes=new ArrayList<ProductionRoute>();
        for(Map<String,Object> option:items(factory,"actions")){
            String type=String.valueOf(option.get("type"));
            int priority="heavyTank".equals(type)?3:("tank".equals(type)||"c_tank".equals(type))?1:0;
            double cost=number(option,"cost",Double.NaN);
            if(priority>0&&option.get("actionId") instanceof String&&Double.isFinite(cost)&&cost>0)
                routes.add(new ProductionRoute(BattleClient.id(factory),"landFactory",type,(String)option.get("actionId"),
                    (int)number(factory,"tier",-1),(int)number(factory,"queue",-1),cost,Boolean.TRUE.equals(option.get("affordable"))));
        }
        Collections.sort(routes,(a,b)->Integer.compare("heavyTank".equals(b.product)?3:1,"heavyTank".equals(a.product)?3:1));
        return routes;
    }
    Map<String,Object> evidence(){return map("producerId",producer,"producerType",producerType,"product",product,
        "nativeActionId",action,"nativeCost",cost,"observedTech",tech,"nativeQueue",queue,"nativeAffordable",affordable,
        "quoteSource","LEGAL_NATIVE_MENU","expectedDurationGameMs",null,"expectedThroughput",null,
        "durationStatus","UNKNOWN","fallbackScope","EXISTING_ORDINARY_PRODUCTION_POLICY");}
}
