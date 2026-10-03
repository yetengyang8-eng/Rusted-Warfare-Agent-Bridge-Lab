package io.rwagent.client;

import java.util.*;
import static io.rwagent.client.BattleClient.*;

/** Conservative linkage of accepted spending to a later legal own-state effect.
 * A receipt, elapsed time, wallet delta and an empty queue never settle a hold.
 * Construction association uses the existing product/site envelope; ambiguity
 * retains the commitment and does not assert producer lineage or completion.
 */
public final class NativeCreditWitness {
    private static final class Hold {
        Intent intent; long frame, actor; String product; double tier=-1,x,y;
        final Map<Long,Double> before=new LinkedHashMap<Long,Double>();
        boolean queueEmpty,site,actorObserved;
    }
    private final Map<String,Hold> holds=new LinkedHashMap<String,Hold>();
    public void accepted(Intent intent,Map<String,Object> receipt,Map<String,Object> context,Map<String,Object> state){
        if(!intent.commitment.spending||intent.commitment.credits==null||intent.commitment.credits==0||intent.actorIds.size()!=1)return;
        if(holds.containsKey(intent.intentId))return;
        Hold h=new Hold();h.intent=intent;h.actor=intent.actorIds.get(0);h.frame=intent.observation.frame;
        if(receipt!=null&&receipt.get("frame") instanceof Number)h.frame=Math.max(h.frame,((Number)receipt.get("frame")).longValue());
        h.product=context.get("product") instanceof String?(String)context.get("product"):null;
        for(Map<String,Object> u:units(state)){
            h.before.put(id(u),StrategyDirector.number(u,"buildProgress",-1));
            if(id(u)==h.actor&&!Boolean.TRUE.equals(u.get("dead"))){h.actorObserved=true;h.tier=StrategyDirector.number(u,"techLevel",-1);h.queueEmpty=StrategyDirector.number(u,"productionQueue",-1)==0;}
        }
        boolean hasX=false,hasY=false;
        for(String field:intent.path.substring(intent.path.indexOf('?')+1).split("&")){
            try{
                if(field.startsWith("x=")){h.x=Double.parseDouble(field.substring(2));hasX=true;}
                if(field.startsWith("y=")){h.y=Double.parseDouble(field.substring(2));hasY=true;}
            }catch(NumberFormatException invalid){h.x=h.y=Double.NaN;break;}
        }
        h.site=hasX&&hasY&&Double.isFinite(h.x)&&Double.isFinite(h.y)&&construction(h.intent.kind);
        holds.put(intent.intentId,h);
    }
    public List<Map<String,Object>> observe(CommandArbiter.Stamp stamp,String observationId,Map<String,Object> state,ExecutionScheduler scheduler){
        List<Map<String,Object>> result=new ArrayList<Map<String,Object>>();
        if(stamp==null||observationId==null||observationId.trim().isEmpty()||!legalState(stamp,state))return result;
        Map<Hold,String> settled=new LinkedHashMap<Hold,String>();
        for(Hold h:holds.values()){
            if(!h.actorObserved||!h.intent.observation.samePlayer(stamp)||stamp.frame<=h.frame||stamp.gameTimeMs<h.intent.observation.gameTimeMs)continue;
            // Multiple unresolved orders for one actor do not have unique queue lineage.
            int actorHolds=0;for(Hold other:holds.values())if(other.actor==h.actor)actorHolds++;
            if(actorHolds!=1)continue;
            Map<String,Object> actor=null;for(Map<String,Object> u:units(state))if(id(u)==h.actor)actor=u;
            if(actor==null||Boolean.TRUE.equals(actor.get("dead")))continue;
            String kind=null;
            if(("/command/queue".equals(h.intent.kind)||"/command/produce-builder".equals(h.intent.kind)||"/command/invest".equals(h.intent.kind))
                    &&h.queueEmpty&&StrategyDirector.number(actor,"productionQueue",-1)>0)kind="NATIVE_QUEUE_NONEMPTY_NOT_PRODUCT_READY";
            if("/command/invest".equals(h.intent.kind)&&h.tier>=0&&StrategyDirector.number(actor,"techLevel",-1)>h.tier)kind="NATIVE_TIER_ADVANCED";
            if(h.site&&h.product!=null&&"build".equals(actor.get("orderType"))
                    &&Math.hypot(StrategyDirector.number(actor,"orderX",Double.NaN)-h.x,StrategyDirector.number(actor,"orderY",Double.NaN)-h.y)<120){
                int candidates=0;
                for(Map<String,Object> u:units(state))if(!Boolean.TRUE.equals(u.get("dead"))&&h.product.equals(u.get("type"))
                        &&Math.hypot(StrategyDirector.number(u,"x",Double.NaN)-h.x,StrategyDirector.number(u,"y",Double.NaN)-h.y)<120){
                    Double old=h.before.get(id(u));double progress=StrategyDirector.number(u,"buildProgress",-1);
                    if(old==null&&progress>=0||old!=null&&old>=0&&progress>old){
                        int possibleHolds=0;
                        for(Hold other:holds.values())if(other.site&&other.intent.observation.samePlayer(stamp)&&h.product.equals(other.product)
                                &&Math.hypot(StrategyDirector.number(u,"x",Double.NaN)-other.x,StrategyDirector.number(u,"y",Double.NaN)-other.y)<120)possibleHolds++;
                        candidates+=possibleHolds==1?1:2;
                    }
                }
                if(candidates==1)kind="NATIVE_CONSTRUCTION_SITE_EFFECT_NOT_PRODUCER_LINEAGE";
            }
            if(kind!=null)settled.put(h,kind);
        }
        // Determine all ambiguity before removing anything; iteration order cannot create lineage.
        for(Map.Entry<Hold,String> entry:settled.entrySet()){
            Hold h=entry.getKey();String kind=entry.getValue();
            if(kind!=null&&scheduler.confirmNativeEffect(h.intent.intentId,observationId,kind)){
                result.add(StrategyDirector.map("intentId",h.intent.intentId,"observationId",observationId,"requestPath","/state","witnessKind",kind,
                        "sourceFrame",stamp.frame,"sourceGameTimeMs",stamp.gameTimeMs,"credits",h.intent.commitment.credits,"productReady",false));holds.remove(h.intent.intentId);
            }
        }
        return result;
    }
    private static boolean construction(String kind){return "/command/construct".equals(kind)||"/command/build-extractor".equals(kind)||"/command/build-factory".equals(kind);}
    private static boolean legalState(CommandArbiter.Stamp stamp,Map<String,Object> state){
        if(state==null||!stamp.session.equals(state.get("sessionId"))||!"running".equals(state.get("status"))
                ||Boolean.TRUE.equals(state.get("networked"))||Boolean.TRUE.equals(state.get("replay"))
                ||!(state.get("frame") instanceof Number)||!(state.get("gameTimeMs") instanceof Number)||!(state.get("player") instanceof Map))return false;
        if(((Number)state.get("frame")).longValue()!=stamp.frame||((Number)state.get("gameTimeMs")).longValue()!=stamp.gameTimeMs)return false;
        Object team=obj(state.get("player")).get("teamId");String player=team instanceof Number?"team:"+((Number)team).longValue():"legacy-local";
        return stamp.player.equals(player);
    }
}
