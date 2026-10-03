package io.rwagent.client;

import java.util.*;

/** Allocation/task coordinator. Independent Generals retain every member, command and owner. */
public final class CommanderDirector {
    public interface CombatProvider {Map<String,Object> view(GeneralRegistry.GeneralId id);}
    private final GeneralRegistry registry;
    private CombatProvider combat;
    private final Map<Long,ThreatTask> tasks=new LinkedHashMap<Long,ThreatTask>();
    private final Map<GeneralRegistry.GeneralId,Long> assignments=new LinkedHashMap<GeneralRegistry.GeneralId,Long>();
    private final List<Map<String,Object>> changes=new ArrayList<Map<String,Object>>();
    private long nextTask,lastBirthFrame=-1;
    public CommanderDirector(GeneralRegistry registry){if(registry==null)throw new IllegalArgumentException("Registry required");this.registry=registry;}
    public void setCombatProvider(CombatProvider provider){combat=provider;}
    public void reconcile(Map<String,Object> state,Map<String,Object> enemies,double homeX,double homeY,long now,int desiredStrength){
        if(!registry.currentOwnState(state)||now!=registry.observation().gameTimeMs)return;
        cleanup();reconcileTasks(state,enemies,now);refreshJoinTargets();allocate(state);
        // One explicit birth per observation; its full desired strength prevents six-unit fragmentation.
        if(lastBirthFrame!=registry.observation().frame&&legalHome(state,homeX,homeY)){
            GeneralRegistry.GeneralId born=registry.createAdditionalForming(state,Math.max(LocalArmyDirector.FORM_MINIMUM,desiredStrength),homeX,homeY);
            if(born!=null){lastBirthFrame=registry.observation().frame;record("COMMANDER_OVERFLOW_FORMATION",fields("generalId",born.value));allocate(state);}
        }
        assignTasks();
    }
    private void cleanup(){Set<GeneralRegistry.GeneralId> live=new HashSet<GeneralRegistry.GeneralId>();for(GeneralRegistry.GeneralView g:registry.generals())live.add(g.id);
        Iterator<Map.Entry<GeneralRegistry.GeneralId,Long>> it=assignments.entrySet().iterator();while(it.hasNext()){
            Map.Entry<GeneralRegistry.GeneralId,Long> entry=it.next();if(!live.contains(entry.getKey())){record("COMMANDER_GENERAL_INVALIDATED",fields("generalId",entry.getKey().value,"taskId",entry.getValue()));it.remove();}}
    }
    private void refreshJoinTargets(){for(GeneralRegistry.GeneralView general:registry.generals()){
        Map<String,Object> view=rawCombatView(general.id);boolean retreat=flag(view,"retreating")||"OVERMATCHED".equals(view.get("crisis"))||"RETREAT".equals(view.get("crisis"));
        if(view.isEmpty()||!combatCurrent(view)){if(retreat)registry.requireJoinTargetOverride(general.id);continue;}
        if(retreat){registry.requireJoinTargetOverride(general.id);
            if(flag(view,"rallyKnown")&&finite(view.get("rallyX"))&&finite(view.get("rallyY")))registry.setJoinTargetOverride(general.id,registry.observation(),number(view,"rallyX"),number(view,"rallyY"));
        }else registry.clearJoinTargetOverride(general.id);
    }}
    private void allocate(Map<String,Object> state){
        List<GeneralRegistry.UnitView> candidates=new ArrayList<GeneralRegistry.UnitView>();for(GeneralRegistry.UnitView u:registry.units()){
            Map<String,Object> own=find(state,u.unitId);
            if(u.allocation==GeneralRegistry.Allocation.FREE&&u.membership==GeneralRegistry.Membership.UNATTACHED&&u.reservedGeneralId==null
                    &&u.externalOwner==null&&!u.localResponseDetachedFromGeneral&&u.healthRole==GeneralRegistry.HealthRole.NORMAL
                    &&u.healthObservedFrame==registry.observation().frame&&u.freeSinceFrame<registry.observation().frame&&healthy(own))candidates.add(u);
        }
        Collections.sort(candidates,(a,b)->Long.compare(a.unitId,b.unitId));
        for(GeneralRegistry.UnitView unit:candidates){GeneralRegistry.GeneralView best=null;double bestScore=-Double.MAX_VALUE;
            Map<String,Object> own=find(state,unit.unitId);
            for(GeneralRegistry.GeneralView g:registry.generals()){
                int vacancies=g.desiredStrength-g.members.size()-g.reservations.size();if(vacancies<=0||!g.joinTargetKnown)continue;
                Map<String,Object> v=combatView(g.id);int currentHealthy=v.get("currentHealthy") instanceof Number?Math.max(0,((Number)v.get("currentHealthy")).intValue()):g.healthyAttachedStrength;
                int deficit=Math.max(0,g.desiredStrength-currentHealthy-g.reservations.size());
                boolean retreat=flag(v,"retreating")||"OVERMATCHED".equals(v.get("crisis"));
                boolean crisis=flag(v,"crisis")||v.get("crisis") instanceof String&&!"NONE".equals(v.get("crisis"))&&!"NORMAL".equals(v.get("crisis"));
                double pressure=finite(v.get("visiblePressure"))?Math.max(0,number(v,"visiblePressure")):0;
                double urgency=finite(v.get("urgency"))?Math.max(0,number(v,"urgency")):pressure;
                double distance=Math.hypot(number(own,"x")-g.joinTargetX,number(own,"y")-g.joinTargetY);
                double score=(crisis?200000:0)+(retreat?100000:0)+1000.0*deficit+100*Math.min(100,urgency)-distance;
                if(best==null||score>bestScore||score==bestScore&&g.id.value<best.id.value){best=g;bestScore=score;}
            }
            if(best!=null&&registry.requestJoin(unit.unitId,best.id))record("COMMANDER_REINFORCEMENT_RESERVED",fields("unitId",unit.unitId,"generalId",best.id.value,"score",bestScore,"joinTargetSource",best.joinTargetSource));
        }
    }
    private void reconcileTasks(Map<String,Object> state,Map<String,Object> enemies,long now){
        if(!enemyCurrent(state,enemies,now)){for(Map.Entry<Long,ThreatTask> entry:tasks.entrySet())if(entry.getValue().status!=ThreatTask.Status.CLEAR)
            update(entry.getValue().withStatus(ThreatTask.Status.UNKNOWN,"VISIBLE_SAMPLE_UNKNOWN"));return;}
        List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>();Long source=GameClock.number(enemies.get("gameTimeMs"));
        for(Map<String,Object> row:items(enemies,"visibleEnemies"))if(GameClock.number(row.get("id"))!=null&&finite(row.get("x"))&&finite(row.get("y"))
                &&number(row,"hp")>0&&!flag(row,"dead")&&Objects.equals(source,GameClock.number(row.get("lastSeenGameTimeMs"))))rows.add(row);
        Collections.sort(rows,(a,b)->Long.compare(id(a),id(b)));
        Set<Long> usedTasks=new HashSet<Long>(),seen=new HashSet<Long>();
        for(Map<String,Object> seed:rows){if(!seen.add(id(seed)))continue;List<Map<String,Object>> cluster=new ArrayList<Map<String,Object>>();cluster.add(seed);
            for(int i=0;i<cluster.size();i++)for(Map<String,Object> row:rows)if(!seen.contains(id(row))&&distance(cluster.get(i),row)<=LocalCrisisPolicy.CLUSTER_RADIUS){seen.add(id(row));cluster.add(row);}
            Set<Long> ids=new LinkedHashSet<Long>();double x=0,y=0;for(Map<String,Object> row:cluster){ids.add(id(row));x+=number(row,"x");y+=number(row,"y");}
            ThreatTask old=null;int overlap=0;for(ThreatTask candidate:tasks.values())if(candidate.status!=ThreatTask.Status.CLEAR&&!usedTasks.contains(candidate.id)){
                int common=0;for(Long enemy:ids)if(candidate.enemyIds.contains(enemy))common++;if(common>overlap){old=candidate;overlap=common;}}
            Long enemyFrame=GameClock.number(enemies.get("frame"));long taskId=old==null?++nextTask:old.id;usedTasks.add(taskId);
            update(new ThreatTask(taskId,ids,x/cluster.size(),y/cluster.size(),enemyFrame==null?-1:enemyFrame,source,ThreatTask.Status.CURRENT,"CURRENT_LEGAL_VISIBLE_CLUSTER"));
        }
        for(Map.Entry<Long,ThreatTask> entry:tasks.entrySet())if(!usedTasks.contains(entry.getKey())&&entry.getValue().status!=ThreatTask.Status.CLEAR)
            update(entry.getValue().withStatus(ThreatTask.Status.CLEAR,"CURRENT_VISIBILITY_DISAPPEARED_NOT_KILL"));
    }
    private void assignTasks(){for(GeneralRegistry.GeneralView g:registry.generals()){
        ThreatTask previous=taskForGeneral(g.id);if(previous!=null&&previous.status==ThreatTask.Status.UNKNOWN)continue;
        ThreatTask best=null;double nearest=Double.MAX_VALUE;for(ThreatTask task:tasks.values())if(task.status==ThreatTask.Status.CURRENT){
            boolean currentCentroid=g.centroidKnown&&g.centroidFrame==registry.observation().frame;
            if(!currentCentroid&&!g.anchorKnown)continue;
            double x=currentCentroid?g.x:g.anchorX,y=currentCentroid?g.y:g.anchorY;double d=Math.hypot(x-task.x,y-task.y);
            if(best==null||d<nearest||d==nearest&&task.id<best.id){best=task;nearest=d;}}
        Long next=best==null?null:best.id;if(!Objects.equals(assignments.get(g.id),next)){
            Long before=assignments.get(g.id);if(next==null)assignments.remove(g.id);else assignments.put(g.id,next);
            record("COMMANDER_THREAT_ATTENTION",fields("generalId",g.id.value,"beforeTaskId",before,"taskId",next));}
    }}
    public ThreatTask taskForGeneral(GeneralRegistry.GeneralId general){Long id=assignments.get(general);return id==null?null:tasks.get(id);}
    public Map<GeneralRegistry.GeneralId,Long> preferredThreats(){Map<GeneralRegistry.GeneralId,Long> result=new LinkedHashMap<GeneralRegistry.GeneralId,Long>();
        for(GeneralRegistry.GeneralId id:assignments.keySet()){ThreatTask task=taskForGeneral(id);if(task!=null&&task.primaryEnemyId()!=null)result.put(id,task.primaryEnemyId());}return Collections.unmodifiableMap(result);}
    public List<Map<String,Object>> drainChanges(){List<Map<String,Object>> out=new ArrayList<Map<String,Object>>(changes);changes.clear();return out;}
    public Map<String,Object> snapshot(){List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>();for(ThreatTask task:tasks.values())rows.add(task.metadata());
        List<Map<String,Object>> attention=new ArrayList<Map<String,Object>>();for(Map.Entry<GeneralRegistry.GeneralId,Long> entry:assignments.entrySet())attention.add(fields("generalId",entry.getKey().value,"taskId",entry.getValue()));
        return fields("tasks",rows,"generalAttention",attention,"superGeneral",false);}
    private void update(ThreatTask task){ThreatTask old=tasks.put(task.id,task);if(old==null||old.status!=task.status||!old.enemyIds.equals(task.enemyIds))
        record("COMMANDER_THREAT_TASK",fields("before",old==null?null:old.metadata(),"after",task.metadata()));}
    private Map<String,Object> rawCombatView(GeneralRegistry.GeneralId id){Map<String,Object> view=combat==null?null:combat.view(id);return view==null?Collections.<String,Object>emptyMap():view;}
    private Map<String,Object> combatView(GeneralRegistry.GeneralId id){Map<String,Object> view=rawCombatView(id);return combatCurrent(view)?view:Collections.<String,Object>emptyMap();}
    private boolean combatCurrent(Map<String,Object> view){CommandArbiter.Stamp own=registry.observation();return flag(view,"ownCurrent")
            &&own.session.equals(view.get("sourceSessionId"))&&Objects.equals(Long.valueOf(own.frame),GameClock.number(view.get("sourceFrame")))
            &&Objects.equals(Long.valueOf(own.gameTimeMs),GameClock.number(view.get("gameTimeMs")));}
    private void record(String reason,Map<String,Object> details){Map<String,Object> row=fields("reason",reason,"sourceFrame",registry.observation().frame,"gameTimeMs",registry.observation().gameTimeMs);row.putAll(details);changes.add(row);}
    private static boolean legalHome(Map<String,Object> state,double x,double y){if(!Double.isFinite(x)||!Double.isFinite(y))return false;
        for(Map<String,Object> unit:items(state,"ownUnits"))if(ready(unit)&&("commandCenter".equals(unit.get("type"))||flag(unit,"building"))&&Math.hypot(number(unit,"x")-x,number(unit,"y")-y)<=1)return true;return false;}
    private boolean enemyCurrent(Map<String,Object> state,Map<String,Object> enemies,long now){if(enemies==null||!(enemies.get("visibleEnemies") instanceof List))return false;
        Long source=GameClock.number(enemies.get("gameTimeMs")),frame=GameClock.number(enemies.get("frame"));
        if(!(state.get("sessionId") instanceof String)||!state.get("sessionId").equals(enemies.get("sessionId"))||source==null||source<now
                ||frame!=null&&frame<GameClock.number(state.get("frame"))
                ||enemies.get("playerKey") instanceof String&&!Objects.equals(registry.observation().player,enemies.get("playerKey")))return false;
        Object player=enemies.get("player"),ownPlayer=state.get("player");Object team=player instanceof Map?((Map<?,?>)player).get("teamId"):null;
        Object ownTeam=ownPlayer instanceof Map?((Map<?,?>)ownPlayer).get("teamId"):null;
        if(team instanceof Number&&!registry.observation().player.equals("team:"+((Number)team).longValue()))return false;
        for(Map<String,Object> row:items(enemies,"visibleEnemies"))if(GameClock.number(row.get("id"))==null||!finite(row.get("x"))||!finite(row.get("y"))
                ||!finite(row.get("hp"))||!Objects.equals(source,GameClock.number(row.get("lastSeenGameTimeMs"))))return false;return true;}
    private static boolean ready(Map<String,Object> unit){return unit!=null&&finite(unit.get("x"))&&finite(unit.get("y"))&&number(unit,"hp")>0&&!flag(unit,"dead")&&number(unit,"buildProgress")>=1;}
    private static boolean healthy(Map<String,Object> unit){return ready(unit)&&number(unit,"maxHp")>0&&number(unit,"hp")>=number(unit,"maxHp")*.5;}
    private static boolean flag(Map<String,Object> map,String key){return Boolean.TRUE.equals(map.get(key));}
    private static boolean finite(Object value){return value instanceof Number&&Double.isFinite(((Number)value).doubleValue());}
    private static double number(Map<String,Object> map,String key){Object value=map.get(key);return value instanceof Number?((Number)value).doubleValue():Double.NaN;}
    private static long id(Map<String,Object> row){return ((Number)row.get("id")).longValue();}
    private static double distance(Map<String,Object> a,Map<String,Object> b){return Math.hypot(number(a,"x")-number(b,"x"),number(a,"y")-number(b,"y"));}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> map,String key){return map.get(key) instanceof List?(List<Map<String,Object>>)map.get(key):Collections.<Map<String,Object>>emptyList();}
    private static Map<String,Object> find(Map<String,Object> state,long id){for(Map<String,Object> unit:items(state,"ownUnits"))if(id(unit)==id)return unit;return null;}
    private static Map<String,Object> fields(Object... pairs){Map<String,Object> result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
}
