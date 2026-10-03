package io.rwagent.client;

import java.util.*;

/** G4 ordinary force proposals. Transport and deterministic batch admission belong to the host.
 * Only actual accepted native receipts advance command cooldowns or joining commitments. */
public final class ForceController {
    /** A lawful static resource objective; this does not assert occupancy or combat safety. */
    public static final class ExpansionSupport {
        public final int targetTile;
        public final String sourceKnowledgeId;
        public ExpansionSupport(int targetTile,String sourceKnowledgeId){
            if(targetTile<0||sourceKnowledgeId==null||sourceKnowledgeId.isEmpty())throw new IllegalArgumentException("Static resource tile and knowledge identity required");
            this.targetTile=targetTile;this.sourceKnowledgeId=sourceKnowledgeId;
        }
    }
    public interface Host {
        Map<String,Object> read(String path,String event)throws Exception;
        List<Map<String,Object>> eligible(List<Map<String,Object>> actors,Map<String,Object> target,Map<String,Object> enemies)throws Exception;
        void collect(Proposal proposal)throws Exception;
    }
    public interface ReceiptCallback {void accepted(Map<String,Object> receipt)throws Exception;}
    public static final class Proposal {
        public final String owner,path,lane,reason;
        public final List<Long> actors;
        public final int priority;
        private final ReceiptCallback callback;
        public Proposal(String owner,List<Long> actors,String path,String lane,int priority,String reason,ReceiptCallback callback){
            if(owner==null||owner.isEmpty()||actors==null||actors.isEmpty()||path==null||lane==null||reason==null)throw new IllegalArgumentException("Force proposal identity");
            this.owner=owner;this.actors=Collections.unmodifiableList(new ArrayList<Long>(actors));this.path=path;this.lane=lane;
            this.priority=priority;this.reason=reason;this.callback=callback;
        }
        /** The execution host calls this only after an accepted real native receipt. */
        public void accepted(Map<String,Object> receipt)throws Exception{if(receipt==null||!("queued".equals(receipt.get("status"))||"accepted".equals(receipt.get("status"))||Boolean.TRUE.equals(receipt.get("accepted"))))throw new IllegalArgumentException("Real accepted receipt required");if(callback!=null)callback.accepted(receipt);}
    }
    private final GeneralRegistry registry;
    private final Host host;
    private static final long ORDER_INTERVAL_MS=8000;
    private final Map<Long,Long> generalAccepted=new HashMap<Long,Long>(),joinAccepted=new HashMap<Long,Long>(),responseClearedFrame=new HashMap<Long,Long>();
    private final Map<Long,JoinReceiptTarget> joinReceiptTargets=new HashMap<Long,JoinReceiptTarget>();
    private static final class JoinReceiptTarget {
        final GeneralRegistry.GeneralId general;final long generation;final double x,y;final String source;
        JoinReceiptTarget(GeneralRegistry.UnitView unit,double x,double y,String source){general=unit.reservedGeneralId;generation=unit.ownerGeneration;this.x=x;this.y=y;this.source=source;}
    }
    private final Map<Long,Long> screenAccepted=new HashMap<Long,Long>();
    private ExpansionSupport expansionSupport;
    private GeneralCombatDirector generalCombatDirector;
    private Response response;
    private long responseSequence;
    private static final class Response {
        final long targetId,assetId,createdAt;final String owner;final List<Long> actors;
        long acceptedAt=-100000;
        Response(long target,long asset,long now,long sequence,List<Long> actors){targetId=target;assetId=asset;createdAt=now;
            owner="local-response:"+sequence;this.actors=actors;}
    }
    public ForceController(GeneralRegistry registry,Host host){if(registry==null||host==null)throw new IllegalArgumentException("Registry and host required");this.registry=registry;this.host=host;}
    public void setExpansionSupport(ExpansionSupport support){expansionSupport=support;}
    /** G5 replaces ordinary General decisions only; null retains the validated G4 lane. */
    public void setGeneralCombatDirector(GeneralCombatDirector director){generalCombatDirector=director;}
    public void clearExpansionSupport(){expansionSupport=null;}
    /** Root may call this before its formal replenishment allocator; collect also reconciles idempotently. */
    public void reconcile(Map<String,Object> state,Map<String,Object> enemies,long now){
        if(response==null)return;
        // Only a complete, fresh same-session native visible sample can end this simple task.
        // Missing/stale/foreign coverage is UNKNOWN; elapsed time and geometry are not visibility.
        if(!combatCurrent(state,enemies,now))return;
        if(currentTarget(enemies,response.targetId)==null)clearResponse();
    }
    public void collect(Map<String,Object> state,Map<String,Object> enemies,Map<String,Object> scout,double homeX,double homeY,long now)throws Exception{
        if(state==null||enemies==null||now<0)return;
        reconcile(state,enemies,now);
        collectLocalResponse(state,enemies,now);
        collectJoining(state,now);
        if(generalCombatDirector==null)collectGenerals(state,enemies,now);else generalCombatDirector.collect(state,enemies,now);
        collectFormingScreens(state,now);
        // FREE movement is deliberately absent without a lawful, useful home-resource route.
    }
    private void clearResponse(){if(response==null)return;for(Long actor:response.actors){GeneralRegistry.UnitView unit=registry.unit(actor);
        if(unit!=null&&response.owner.equals(unit.owner)){registry.clearLocalResponse(actor);GeneralRegistry.UnitView cleared=registry.unit(actor);if(cleared!=null)responseClearedFrame.put(actor,cleared.freeSinceFrame);}}response=null;}
    private void collectLocalResponse(Map<String,Object> state,Map<String,Object> enemies,long now)throws Exception{
        if(!combatCurrent(state,enemies,now))return;
        if(response==null){
            Map<String,Object> target=null,asset=null;double nearest=Double.MAX_VALUE;
            for(Map<String,Object> enemy:visible(enemies))if(Boolean.TRUE.equals(enemy.get("canAttack"))&&!Boolean.TRUE.equals(enemy.get("building"))){
                for(Map<String,Object> candidate:items(state,"ownUnits"))if(asset(candidate)){double d=distance(enemy,number(candidate,"x"),number(candidate,"y"));
                    if(d<=LocalCrisisPolicy.ASSET_RADIUS&&(d<nearest||d==nearest&&target!=null&&id(enemy)<id(target))){nearest=d;target=enemy;asset=candidate;}}}
            if(target==null)return;
            List<Map<String,Object>> threats=cluster(enemies,target);if(threats.size()>LocalCrisisPolicy.MAX_THREATS)return;
            double enemyHp=0;for(Map<String,Object> threat:threats){double hp=number(threat,"hp");if(!(hp>0)||!Double.isFinite(hp))return;enemyHp+=hp;}
            List<Map<String,Object>> available=new ArrayList<Map<String,Object>>();
            for(GeneralRegistry.UnitView unit:registry.units())if(unit.membership!=GeneralRegistry.Membership.JOINING
                    &&unit.temporaryTask==GeneralRegistry.TemporaryTask.NONE&&unit.healthRole==GeneralRegistry.HealthRole.NORMAL
                    &&(generalCombatDirector==null||unit.generalId==null||!generalCombatDirector.retreating(unit.generalId))
                    &&unit.externalOwner==null&&!Objects.equals(responseClearedFrame.get(unit.unitId),GameClock.number(state.get("frame")))){Map<String,Object> own=find(state,unit.unitId);if(ready(own)&&number(own,"hp")>=number(own,"maxHp")*.5)available.add(own);}
            if(available.isEmpty())return;
            List<Map<String,Object>> compatible=available;
            for(Map<String,Object> threat:threats){compatible=host.eligible(compatible,threat,enemies);if(compatible==null||compatible.isEmpty())return;}
            final double x=number(asset,"x"),y=number(asset,"y");
            List<Map<String,Object>> candidates=new ArrayList<Map<String,Object>>();
            for(Map<String,Object> actor:compatible)if(contains(available,id(actor))&&distance(actor,x,y)<=LocalCrisisPolicy.RESPONSE_RADIUS)candidates.add(actor);
            Collections.sort(candidates,(a,c)->{int rank=Integer.compare(localRank(id(a)),localRank(id(c)));if(rank!=0)return rank;
                int d=Double.compare(distance(a,x,y),distance(c,x,y));return d!=0?d:Long.compare(id(a),id(c));});
            List<Long> selected=new ArrayList<Long>();Map<GeneralRegistry.GeneralId,Integer> sizes=new HashMap<GeneralRegistry.GeneralId,Integer>();
            for(GeneralRegistry.GeneralView general:registry.generals())sizes.put(general.id,general.members.size());
            double hp=0;
            for(Map<String,Object> actor:candidates){
                if(selected.size()>=LocalCrisisPolicy.MAX_RESPONDERS)break;
                GeneralRegistry.UnitView unit=registry.unit(id(actor));
                if(unit.generalId!=null){Integer left=sizes.get(unit.generalId);GeneralRegistry.GeneralView general=registry.general(unit.generalId);
                    if(left==null||general==null||general.phase==GeneralRegistry.Phase.ACTIVE&&left<=LocalArmyDirector.RELEASE_BELOW)continue;sizes.put(unit.generalId,left-1);}
                selected.add(id(actor));hp+=number(actor,"hp");
                if(hp>=enemyHp*LocalCrisisPolicy.HP_FACTOR)break;
            }
            if(selected.isEmpty()||hp<enemyHp*LocalCrisisPolicy.HP_FACTOR)return;
            Response candidate=new Response(id(target),id(asset),now,++responseSequence,selected);
            for(Long actor:selected)if(!registry.beginLocalResponse(actor,candidate.owner)){
                for(Long acquired:selected){GeneralRegistry.UnitView held=registry.unit(acquired);if(held!=null&&candidate.owner.equals(held.owner))registry.clearLocalResponse(acquired);}return;}
            response=candidate;
        }
        final Response active=response;if(now-active.acceptedAt<LocalCrisisPolicy.RETRY_MS)return;
        Map<String,Object> target=currentTarget(enemies,active.targetId);if(target==null)return;
        List<Map<String,Object>> actors=new ArrayList<Map<String,Object>>();for(Long id:active.actors){GeneralRegistry.UnitView unit=registry.unit(id);Map<String,Object> own=find(state,id);
            if(unit!=null&&active.owner.equals(unit.owner)&&unit.temporaryTask==GeneralRegistry.TemporaryTask.LOCAL_RESPONSE&&ready(own))actors.add(own);}
        List<Map<String,Object>> eligible=host.eligible(actors,target,enemies);if(eligible==null||eligible.isEmpty())return;
        List<Long> ids=validatedIds(actors,eligible);if(ids.isEmpty())return;
        host.collect(new Proposal(active.owner,ids,attack(ids,number(target,"x"),number(target,"y")),"LOCAL_RESPONSE",70,"CURRENT_VISIBLE_HOME_RAID",
            receipt->{if(response==active)active.acceptedAt=now;}));
    }
    private int localRank(long actor){GeneralRegistry.UnitView unit=registry.unit(actor);return unit!=null&&unit.membership!=GeneralRegistry.Membership.ATTACHED?0:1;}
    private void collectJoining(Map<String,Object> state,long now)throws Exception{
        final Long frame=GameClock.number(state.get("frame"));if(frame==null)return;
        for(GeneralRegistry.UnitView initial:registry.units()){
            if(initial.temporaryTask!=GeneralRegistry.TemporaryTask.NONE||initial.externalOwner!=null)continue;
            if(initial.allocation==GeneralRegistry.Allocation.PENDING_JOIN&&initial.membership==GeneralRegistry.Membership.UNATTACHED&&initial.healthRole==GeneralRegistry.HealthRole.NORMAL)registry.beginJoining(initial.unitId);
            final GeneralRegistry.UnitView unit=registry.unit(initial.unitId);
            if(unit==null||unit.membership!=GeneralRegistry.Membership.JOINING||unit.reservedGeneralId==null)continue;
            GeneralRegistry.GeneralView general=registry.general(unit.reservedGeneralId);Map<String,Object> own=find(state,unit.unitId);
            Map<String,Object> target=registry.joiningTarget(unit.reservedGeneralId,state);
            if(general==null||!Boolean.TRUE.equals(target.get("known"))||!ready(own))continue;
            final double targetX=number(target,"x"),targetY=number(target,"y");
            final String targetSource=(String)target.get("source");final JoinReceiptTarget prior=joinReceiptTargets.get(unit.unitId);
            boolean urgentRedirect="CURRENT_HOME_SIDE_RALLY_DYNAMIC_UNKNOWN".equals(targetSource)
                    &&(prior==null||prior.generation!=unit.ownerGeneration||!unit.reservedGeneralId.equals(prior.general)
                    ||!targetSource.equals(prior.source)||Math.hypot(prior.x-targetX,prior.y-targetY)>=1);
            if(!urgentRedirect&&now-last(joinAccepted,unit.unitId)<ORDER_INTERVAL_MS)continue;
            if(unit.joinAcceptedFrame>=0&&"move".equals(own.get("orderType"))&&Math.hypot(number(own,"orderX")-targetX,number(own,"orderY")-targetY)<1)continue;
            List<Long> ids=Collections.singletonList(unit.unitId);
            host.collect(new Proposal(unit.owner,ids,"/command/move?unitId="+unit.unitId+"&x="+targetX+"&y="+targetY,"JOINING",50,
                "CURRENT_HOME_SIDE_RALLY_DYNAMIC_UNKNOWN".equals(target.get("source"))?"MOVE_TO_HOME_SIDE_RALLY_DYNAMIC_UNKNOWN":general.phase==GeneralRegistry.Phase.FORMING?"MOVE_TO_FORMATION_OWN_ANCHOR":"MOVE_TO_CURRENT_GENERAL_CENTROID",
                receipt->{Long actualFrame=GameClock.number(receipt.get("frame"));GeneralRegistry.UnitView current=registry.unit(unit.unitId);
                    if(actualFrame!=null&&actualFrame>=frame&&current!=null&&current.ownerGeneration==unit.ownerGeneration&&current.owner.equals(unit.owner)
                            &&Objects.equals(current.reservedGeneralId,unit.reservedGeneralId)&&registry.joinAccepted(unit.unitId,actualFrame)){
                        Long actualTime=GameClock.number(receipt.get("gameTimeMs"));joinAccepted.put(unit.unitId,actualTime!=null&&actualTime>=now?actualTime:now);
                        joinReceiptTargets.put(unit.unitId,new JoinReceiptTarget(unit,targetX,targetY,targetSource));}}));
        }
    }
    private void collectGenerals(Map<String,Object> state,Map<String,Object> enemies,long now)throws Exception{
        for(final GeneralRegistry.GeneralView general:registry.generals()){
            if(general.phase!=GeneralRegistry.Phase.ACTIVE)continue;
            if(!general.centroidKnown||!Objects.equals(Long.valueOf(general.centroidFrame),GameClock.number(state.get("frame")))||now-last(generalAccepted,general.id.value)<ORDER_INTERVAL_MS)continue;
            List<Map<String,Object>> actors=new ArrayList<Map<String,Object>>();for(Long id:general.members){GeneralRegistry.UnitView unit=registry.unit(id);Map<String,Object> own=find(state,id);
                if(unit!=null&&unit.membership==GeneralRegistry.Membership.ATTACHED&&unit.temporaryTask==GeneralRegistry.TemporaryTask.NONE
                        &&unit.healthRole==GeneralRegistry.HealthRole.NORMAL&&unit.externalOwner==null&&general.owner.equals(unit.owner)&&ready(own))actors.add(own);}
            if(actors.isEmpty())continue;
            List<Map<String,Object>> targets=new ArrayList<Map<String,Object>>(combatCurrent(state,enemies,now)?visible(enemies):Collections.<Map<String,Object>>emptyList());Collections.sort(targets,(a,b)->{
                int d=Double.compare(distance(a,general.x,general.y),distance(b,general.x,general.y));return d!=0?d:Long.compare(id(a),id(b));});
            boolean proposed=false;
            for(Map<String,Object> target:targets){List<Map<String,Object>> eligible=host.eligible(actors,target,enemies);if(eligible==null||eligible.isEmpty())continue;
                List<Long> ids=validatedIds(actors,eligible);if(ids.isEmpty())continue;
                host.collect(new Proposal(general.owner,ids,attack(ids,number(target,"x"),number(target,"y")),"GENERAL",30,"CURRENT_VISIBLE_TARGET",
                    receipt->{generalAccepted.put(general.id.value,now);registry.updateGeneralGoal(general.id,number(target,"x"),number(target,"y"),id(target));}));proposed=true;break;}
            if(proposed)continue;
            Map<String,Object> anchor=actors.get(0);for(Map<String,Object> actor:actors)if(distance(actor,general.x,general.y)<distance(anchor,general.x,general.y))anchor=actor;
            Map<String,Object> plan=host.read("/scout/plan?role=army&unitId="+id(anchor)+"&avoid=","g4_general_frontier_plan");
            if(plan==null||!"planned".equals(plan.get("status"))||!Boolean.TRUE.equals(plan.get("pathKnown"))
                    ||GameClock.number(plan.get("targetTile"))==null||!finite(plan.get("targetX"))||!finite(plan.get("targetY")))continue;
            // As in the existing local-army lane, this is anchor path evidence, not a claim that
            // every member can reach it. Homogeneous members keep native final command guards.
            List<Long> ids=new ArrayList<Long>();Object type=anchor.get("type");for(Map<String,Object> actor:actors)if(Objects.equals(type,actor.get("type")))ids.add(id(actor));
            if(ids.isEmpty())continue;
            host.collect(new Proposal(general.owner,ids,attack(ids,number(plan,"targetX"),number(plan,"targetY")),"GENERAL",30,"NATIVE_KNOWN_ANCHOR_FRONTIER",
                receipt->{generalAccepted.put(general.id.value,now);registry.updateGeneralGoal(general.id,number(plan,"targetX"),number(plan,"targetY"),null);}));
        }
    }
    private void collectFormingScreens(Map<String,Object> state,long now)throws Exception{
        final ExpansionSupport support=expansionSupport;if(support==null)return;
        for(final GeneralRegistry.GeneralView general:registry.generals()){
            if(general.phase!=GeneralRegistry.Phase.FORMING)continue;
            if(generalCombatDirector!=null&&generalCombatDirector.retreating(general.id))continue;
            for(final Long actor:general.members){GeneralRegistry.UnitView unit=registry.unit(actor);Map<String,Object> own=find(state,actor);
                if(unit!=null&&unit.membership==GeneralRegistry.Membership.ATTACHED&&unit.temporaryTask==GeneralRegistry.TemporaryTask.NONE
                        &&unit.healthRole==GeneralRegistry.HealthRole.NORMAL&&unit.externalOwner==null&&general.owner.equals(unit.owner)
                        &&ready(own)&&number(own,"hp")>=number(own,"maxHp")*.5&&now-last(screenAccepted,actor)>=ORDER_INTERVAL_MS){
                    Map<String,Object> plan=host.read("/static-map/approach?unitId="+actor+"&tile="+support.targetTile,"g41_forming_expansion_approach");
                    if(!staticApproachCurrent(plan,state,now,actor,support))continue;
                    // Each actor is its own path anchor. A static short segment does not prove
                    // hidden occupancy, dynamic safety, final arrival or another member's route.
                    host.collect(new Proposal(general.owner,Collections.singletonList(actor),
                        "/command/move?unitId="+actor+"&x="+number(plan,"waypointX")+"&y="+number(plan,"waypointY"),"FORMING_SCREEN",20,
                        "STATIC_ANCHOR_APPROACH_DYNAMIC_UNKNOWN:knowledge="+support.sourceKnowledgeId+":tile="+support.targetTile+":anchor="+actor,
                        receipt->{screenAccepted.put(actor,now);}));
                }
            }
        }
    }
    private static boolean staticApproachCurrent(Map<String,Object> plan,Map<String,Object> state,long now,long actor,ExpansionSupport support){
        if(plan==null||!"KNOWN".equals(plan.get("status"))||!Boolean.TRUE.equals(plan.get("staticOnly"))||!Boolean.TRUE.equals(plan.get("staticPathKnown")))return false;
        Long unit=GameClock.number(plan.get("unitId")),tile=GameClock.number(plan.get("targetTile")),sourceFrame=GameClock.number(plan.get("frame")),frame=GameClock.number(state.get("frame")),sourceTime=GameClock.number(plan.get("gameTimeMs"));
        Object session=state.get("sessionId");
        return unit!=null&&unit==actor&&tile!=null&&tile==support.targetTile&&support.sourceKnowledgeId.equals(plan.get("knowledgeId"))
                &&session instanceof String&&session.equals(plan.get("sessionId"))&&frame!=null&&sourceFrame!=null&&sourceFrame>=frame&&sourceTime!=null&&sourceTime>=now
                &&finite(plan.get("waypointX"))&&finite(plan.get("waypointY"));
    }
    private static List<Long> validatedIds(List<Map<String,Object>> candidates,List<Map<String,Object>> eligible){List<Long> ids=new ArrayList<Long>();
        for(Map<String,Object> actor:eligible){long id=id(actor);if(contains(candidates,id)&&!ids.contains(id))ids.add(id);}return ids;}
    private static boolean contains(List<Map<String,Object>> actors,long id){for(Map<String,Object> actor:actors)if(id(actor)==id)return true;return false;}
    private static String attack(List<Long> ids,double x,double y){StringBuilder actors=new StringBuilder();for(Long id:ids){if(actors.length()>0)actors.append(',');actors.append(id);}
        return "/command/attack-move?unitIds="+actors+"&x="+x+"&y="+y;}
    private static long last(Map<Long,Long> values,long id){Long value=values.get(id);return value==null?-100000:value;}
    private static List<Map<String,Object>> cluster(Map<String,Object> enemies,Map<String,Object> target){List<Map<String,Object>> result=new ArrayList<Map<String,Object>>();
        for(Map<String,Object> enemy:visible(enemies))if(Boolean.TRUE.equals(enemy.get("canAttack"))&&!Boolean.TRUE.equals(enemy.get("building"))
                &&distance(enemy,number(target,"x"),number(target,"y"))<=LocalCrisisPolicy.CLUSTER_RADIUS)result.add(enemy);return result;}
    private static Map<String,Object> currentTarget(Map<String,Object> enemies,long target){for(Map<String,Object> enemy:visible(enemies))if(id(enemy)==target)return enemy;return null;}
    private static boolean combatCurrent(Map<String,Object> state,Map<String,Object> enemies,long now){
        if(state==null||enemies==null||!(enemies.get("visibleEnemies") instanceof List))return false;
        Object session=state.get("sessionId");Long source=GameClock.number(enemies.get("gameTimeMs"));
        return session instanceof String&&session.equals(enemies.get("sessionId"))&&source!=null&&source>=now;
    }
    private static List<Map<String,Object>> visible(Map<String,Object> enemies){List<Map<String,Object>> current=new ArrayList<Map<String,Object>>();Long source=GameClock.number(enemies.get("gameTimeMs"));if(source==null)return current;
        for(Map<String,Object> enemy:items(enemies,"visibleEnemies")){Long seen=GameClock.number(enemy.get("lastSeenGameTimeMs"));if(seen!=null&&seen.equals(source)&&readyEnemy(enemy))current.add(enemy);}return current;}
    private static boolean readyEnemy(Map<String,Object> enemy){return GameClock.number(enemy.get("id"))!=null&&finite(enemy.get("x"))&&finite(enemy.get("y"))&&number(enemy,"hp")>0;}
    private static boolean asset(Map<String,Object> own){if(!ready(own))return false;Object type=own.get("type");return "commandCenter".equals(type)||type instanceof String&&((String)type).startsWith("extractor");}
    private static boolean ready(Map<String,Object> own){return own!=null&&finite(own.get("x"))&&finite(own.get("y"))&&number(own,"hp")>0&&!Boolean.TRUE.equals(own.get("dead"))&&number(own,"buildProgress")>=1;}
    private static double distance(Map<String,Object> unit,double x,double y){return Math.hypot(number(unit,"x")-x,number(unit,"y")-y);}
    private static double number(Map<String,Object> map,String key){Object value=map==null?null:map.get(key);return value instanceof Number?((Number)value).doubleValue():Double.NaN;}
    private static boolean finite(Object value){return value instanceof Number&&Double.isFinite(((Number)value).doubleValue());}
    private static long id(Map<String,Object> unit){Long value=GameClock.number(unit.get("id"));return value==null?-1:value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> map,String key){return map!=null&&map.get(key) instanceof List?(List<Map<String,Object>>)map.get(key):Collections.<Map<String,Object>>emptyList();}
    private static Map<String,Object> find(Map<String,Object> state,long id){for(Map<String,Object> own:items(state,"ownUnits"))if(id(own)==id)return own;return null;}
}

