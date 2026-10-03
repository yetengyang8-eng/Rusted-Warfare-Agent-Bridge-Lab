package io.rwagent.client;

import java.util.*;

/** G5 independent General combat decisions; only the host admits proposals and native receipts. */
public final class GeneralCombatDirector {
    public static final double PRESSURE_RADIUS=600,OVERMATCH_RATIO=1.8,LOSING_DAMAGE_FRACTION=.25,RECOVERY_RATIO=.55;
    public static final long DAMAGE_WINDOW_MS=5000,REGROUP_HOLD_MS=4000,NORMAL_RETRY_MS=3000,RETREAT_RETRY_MS=1500;
    private final GeneralRegistry registry;private final ForceController.Host host;
    private final Map<GeneralRegistry.GeneralId,CombatLedger> ledgers=new LinkedHashMap<GeneralRegistry.GeneralId,CombatLedger>();
    private final List<Map<String,Object>> changes=new ArrayList<Map<String,Object>>();
    private Map<GeneralRegistry.GeneralId,Long> preferred=Collections.emptyMap();
    public GeneralCombatDirector(GeneralRegistry registry,ForceController.Host host){if(registry==null||host==null)throw new IllegalArgumentException("Registry and host required");this.registry=registry;this.host=host;}
    public void setPreferredThreats(Map<GeneralRegistry.GeneralId,Long> targets){preferred=targets==null?Collections.<GeneralRegistry.GeneralId,Long>emptyMap():new HashMap<GeneralRegistry.GeneralId,Long>(targets);}
    public Map<String,Object> view(GeneralRegistry.GeneralId id){CombatLedger ledger=ledgers.get(id);return ledger==null?Collections.<String,Object>emptyMap():ledger.view();}
    public boolean retreating(GeneralRegistry.GeneralId id){CombatLedger ledger=ledgers.get(id);return ledger!=null&&ledger.retreating;}
    public List<Map<String,Object>> drainChanges(){List<Map<String,Object>> result=new ArrayList<Map<String,Object>>(changes);changes.clear();return result;}
    /** Observe before Commander allocation. Duplicate frame calls cannot create extra trend samples. */
    public void observe(Map<String,Object> state,Map<String,Object> enemies,double homeX,double homeY,long now){
        Long frame=GameClock.number(state==null?null:state.get("frame")),ownTime=GameClock.number(state==null?null:state.get("gameTimeMs"));
        if(frame==null||ownTime==null||ownTime!=now||!(state.get("sessionId") instanceof String)||!(state.get("ownUnits") instanceof List)||!registry.currentOwnState(state))return;
        Set<GeneralRegistry.GeneralId> alive=new HashSet<GeneralRegistry.GeneralId>();
        for(GeneralRegistry.GeneralView general:registry.generals()){
            alive.add(general.id);CombatLedger ledger=ledgers.get(general.id);
            if(ledger==null||!Objects.equals(ledger.session,state.get("sessionId"))||!Objects.equals(ledger.player,registry.observation().player)){ledger=new CombatLedger(general.id);ledger.session=(String)state.get("sessionId");ledger.player=registry.observation().player;ledgers.put(general.id,ledger);}
            if(frame<=ledger.frame||now<ledger.time)continue;
            Map<String,Object> before=ledger.view();long oldRevision=ledger.revision;
            ledger.lastObservationGapMs=ledger.time<0?-1:now-ledger.time;
            ledger.damageWindowKnown=ledger.lastObservationGapMs>=0&&ledger.lastObservationGapMs<=DAMAGE_WINDOW_MS;
            ledger.frame=frame;ledger.time=now;ledger.ownCurrent=true;ledger.currentVisibleKnown=combatCurrent(state,enemies,now);
            ledger.ownHp=ledger.ownMaxHp=0;ledger.currentMembers=ledger.currentHealthy=0;
            Map<Long,CombatLedger.OwnSample> current=new LinkedHashMap<Long,CombatLedger.OwnSample>();double damage=0,enemyDelta=0;int deaths=0;
            // Include explicitly observed dead samples for loss evidence; absence is never a death.
            for(Map.Entry<Long,CombatLedger.OwnSample> previous:ledger.own.entrySet()){
                Map<String,Object> unit=find(state,previous.getKey());GeneralRegistry.UnitView owner=registry.unit(previous.getKey());
                if(unit!=null&&(Boolean.TRUE.equals(unit.get("dead"))||number(unit,"hp")==0)
                        &&(owner==null||owner.ownerGeneration==previous.getValue().generation)){deaths++;damage+=previous.getValue().hp;}
            }
            for(Long id:general.members){GeneralRegistry.UnitView owner=registry.unit(id);Map<String,Object> unit=find(state,id);
                if(!owned(general,owner)||!ready(unit))continue;
                double hp=number(unit,"hp"),max=number(unit,"maxHp");boolean maxKnown=Double.isFinite(max)&&max>0;if(!maxKnown)max=hp;
                ledger.ownHp+=hp;ledger.ownMaxHp+=max;ledger.currentMembers++;
                if(owner.healthRole==GeneralRegistry.HealthRole.NORMAL&&maxKnown&&hp>=max*.5)ledger.currentHealthy++;
                CombatLedger.OwnSample previous=ledger.own.get(id);if(previous!=null&&previous.generation==owner.ownerGeneration)damage+=Math.max(0,previous.hp-hp);
                else if(previous!=null){ledger.acceptedAt.remove(id);ledger.acceptedRevision.remove(id);ledger.retreatReceiptFrame.remove(id);ledger.retreatReceiptGeneration.remove(id);ledger.event("OWNER_GENERATION_CHANGED",now);}
                current.put(id,new CombatLedger.OwnSample(owner.ownerGeneration,hp));
            }
            ledger.own.clear();ledger.own.putAll(current);ledger.visiblePressure=0;
            if(ledger.currentVisibleKnown){Map<Long,Double> currentHp=new HashMap<Long,Double>();
                for(Map<String,Object> enemy:visible(enemies)){
                    boolean local=general.centroidKnown&&general.centroidFrame==frame&&distance(enemy,general.x,general.y)<=PRESSURE_RADIUS;
                    if(local||Objects.equals(ledger.lastTarget,id(enemy))){Double previous=ledger.visibleHp.get(id(enemy));if(previous!=null)enemyDelta+=Math.max(0,previous-number(enemy,"hp"));currentHp.put(id(enemy),number(enemy,"hp"));}
                    if(Boolean.TRUE.equals(enemy.get("canAttack"))&&general.centroidKnown&&general.centroidFrame==frame
                            &&distance(enemy,general.x,general.y)<=PRESSURE_RADIUS)ledger.visiblePressure+=number(enemy,"hp");
                }
                ledger.visibleHp.clear();ledger.visibleHp.putAll(currentHp);
            }else ledger.visibleHp.clear(); // A later reappearance cannot be called continuous damage.
            ledger.pressureRatio=ledger.ownHp>0?ledger.visiblePressure/ledger.ownHp:ledger.visiblePressure>0?Double.POSITIVE_INFINITY:0;
            ledger.unknownWindowOwnHpDecrease=ledger.damageWindowKnown?0:damage;ledger.unknownWindowWeakHpDecrease=ledger.damageWindowKnown?0:enemyDelta;
            if(damage>0||enemyDelta>0||deaths>0){if(ledger.damageWindowKnown)ledger.recent.add(new CombatLedger.Damage(now,damage,enemyDelta,deaths));ledger.event(!ledger.damageWindowKnown?"HP_DELTA_DETECTED_WINDOW_UNKNOWN":deaths>0?"EXPLICIT_OWN_DEATH_OBSERVED":damage>0?"CURRENT_OWN_HP_DECREASE":"WEAK_VISIBLE_HP_DECREASE",now);}
            while(!ledger.recent.isEmpty()&&now-ledger.recent.peek().time>DAMAGE_WINDOW_MS)ledger.recent.remove();
            ledger.recentOwnDamage=ledger.weakVisibleHpDecrease=0;int recentLosses=0;
            for(CombatLedger.Damage trend:ledger.recent){ledger.recentOwnDamage+=trend.own;ledger.weakVisibleHpDecrease+=trend.enemy;recentLosses+=trend.losses;}
            ledger.observedOwnDeaths+=deaths;
            boolean overmatch=ledger.currentVisibleKnown&&ledger.visiblePressure>0&&ledger.pressureRatio>=OVERMATCH_RATIO;
            boolean losing=ledger.currentMembers>0&&(ledger.recentOwnDamage>=Math.max(1,ledger.ownMaxHp)*LOSING_DAMAGE_FRACTION
                    ||recentLosses>=2);
            boolean calm=ledger.currentVisibleKnown&&ledger.damageWindowKnown&&ledger.pressureRatio<=RECOVERY_RATIO&&ledger.recentOwnDamage<=Math.max(1,ledger.ownMaxHp)*.02;
            if(!ledger.retreating&&(overmatch||losing)){
                ledger.retreating=true;ledger.trigger=overmatch?"CURRENT_VISIBLE_HP_OVERMATCH":"CURRENT_OWN_LOSS_EXCHANGE";
                ledger.commandRevision++;
                ledger.change(overmatch?CombatLedger.Crisis.OVERMATCHED:CombatLedger.Crisis.LOSING_EXCHANGE,now);
                establishRally(ledger,general,state,enemies,homeX,homeY);ledger.regroupSince=-1;
            }else if(ledger.retreating){
                if(ledger.crisis==CombatLedger.Crisis.REGROUPING){
                    if(!calm){ledger.regroupSince=-1;ledger.change(CombatLedger.Crisis.RETREATING,now);}
                    else if(ledger.currentHealthy>=Math.min(LocalArmyDirector.FORM_MINIMUM,general.desiredStrength)
                            &&ledger.ownHp>=ledger.ownMaxHp*.6&&now-ledger.regroupSince>=REGROUP_HOLD_MS){
                        ledger.retreating=false;ledger.commandRevision++;ledger.trigger="RECOVERY_CURRENT_OWN_RALLY_WITNESSED";ledger.change(CombatLedger.Crisis.NORMAL,now);
                    }
                }else if(calm&&rallyWitness(ledger,general,state,frame)){
                    ledger.regroupSince=now;ledger.change(CombatLedger.Crisis.REGROUPING,now);
                }else ledger.change(CombatLedger.Crisis.RETREATING,now);
            }else if(ledger.currentVisibleKnown)ledger.change(ledger.pressureRatio>=.75?CombatLedger.Crisis.PRESSURED:CombatLedger.Crisis.NORMAL,now);
            if(ledger.revision!=oldRevision)changes.add(fields("reason","GENERAL_COMBAT_OBSERVED","generalId",general.id.value,"before",before,"after",ledger.view(),"sourceFrame",frame,"gameTimeMs",now));
        }
        ledgers.keySet().retainAll(alive);
    }
    private void establishRally(CombatLedger ledger,GeneralRegistry.GeneralView general,Map<String,Object> state,Map<String,Object> enemies,double homeX,double homeY){
        if(!Double.isFinite(homeX)||!Double.isFinite(homeY)||!general.centroidKnown)return;
        double x=homeX,y=homeY;
        if(Math.hypot(general.x-homeX,general.y-homeY)<140&&ledger.currentVisibleKnown){
            double dx=0,dy=0;for(Map<String,Object> enemy:visible(enemies))if(Boolean.TRUE.equals(enemy.get("canAttack"))&&distance(enemy,general.x,general.y)<=PRESSURE_RADIUS){dx+=general.x-number(enemy,"x");dy+=general.y-number(enemy,"y");}
            double length=Math.hypot(dx,dy);if(length>0){x=Math.max(0,general.x+dx/length*360);y=Math.max(0,general.y+dy/length*360);}
        }
        Object rawMap=state.get("map");Map<?,?> map=rawMap instanceof Map?(Map<?,?>)rawMap:Collections.emptyMap();
        double width=numeric(map.get("width")),height=numeric(map.get("height"));
        ledger.rallyBoundsKnown=Double.isFinite(width)&&Double.isFinite(height)&&width>0&&height>0;
        // Native coordinates use float and strict <width/height. A world-unit margin avoids float rounding onto the boundary.
        if(ledger.rallyBoundsKnown){x=Math.max(0,Math.min(x,Math.max(0,width-1)));y=Math.max(0,Math.min(y,Math.max(0,height-1)));}
        else{x=Math.max(0,x);y=Math.max(0,y);}
        ledger.rallyX=x;ledger.rallyY=y;ledger.rallyKnown=true;
    }
    private boolean rallyWitness(CombatLedger ledger,GeneralRegistry.GeneralView general,Map<String,Object> state,long frame){
        if(!ledger.rallyKnown||ledger.currentHealthy==0)return false;int witnesses=0;
        for(Long id:general.members){GeneralRegistry.UnitView owner=registry.unit(id);Map<String,Object> actor=find(state,id);Long receipt=ledger.retreatReceiptFrame.get(id);
            if(owned(general,owner)&&healthy(actor,owner)&&receipt!=null&&Objects.equals(ledger.retreatReceiptGeneration.get(id),owner.ownerGeneration)&&frame>receipt&&distance(actor,ledger.rallyX,ledger.rallyY)<=140)witnesses++;
        }
        return witnesses>=Math.max(1,(int)Math.ceil(ledger.currentHealthy*.75));
    }
    public void collect(Map<String,Object> state,Map<String,Object> enemies,long now)throws Exception{
        Long frame=GameClock.number(state==null?null:state.get("frame"));if(frame==null||!registry.currentOwnState(state))return;
        for(final GeneralRegistry.GeneralView general:registry.generals()){
            final CombatLedger ledger=ledgers.get(general.id);if(ledger==null||ledger.frame!=frame||ledger.time!=now||!Objects.equals(ledger.session,state.get("sessionId")))continue;
            if(!general.centroidKnown||general.centroidFrame!=frame)continue;
            List<Map<String,Object>> actors=new ArrayList<Map<String,Object>>();
            for(Long id:general.members){GeneralRegistry.UnitView owner=registry.unit(id);Map<String,Object> actor=find(state,id);
                if(owned(general,owner)&&owner.healthRole==GeneralRegistry.HealthRole.NORMAL&&ready(actor))actors.add(actor);}
            if(ledger.retreating){
                if(!ledger.rallyKnown||ledger.crisis==CombatLedger.Crisis.REGROUPING)continue;
                for(Map<String,Object> actor:actors){final long id=id(actor);if(!due(ledger,id,now,RETREAT_RETRY_MS))continue;
                    if("move".equals(actor.get("orderType"))&&distanceToOrder(actor,ledger.rallyX,ledger.rallyY)<1&&ledger.retreatReceiptFrame.containsKey(id))continue;
                    final long generation=registry.unit(id).ownerGeneration,revision=ledger.commandRevision;
                    host.collect(new ForceController.Proposal(general.owner,Collections.singletonList(id),"/command/move?unitId="+id+"&x="+ledger.rallyX+"&y="+ledger.rallyY,"GENERAL_RETREAT",85,
                        ledger.trigger+":RALLY_GEOMETRY_SAFETY_UNKNOWN",receipt->{Long receiptFrame=GameClock.number(receipt.get("frame"));if(receiptFrame==null||receiptFrame<frame||!stillOwned(general,id,generation))return;
                            accepted(ledger,id,receipt,now,revision);ledger.retreatReceiptFrame.put(id,receiptFrame);ledger.retreatReceiptGeneration.put(id,generation);recordAccepted(ledger,general,receipt,"RETREAT_ACCEPTED_NOT_ARRIVED");}));
                }continue;
            }
            if(general.phase!=GeneralRegistry.Phase.ACTIVE||!combatCurrent(state,enemies,now))continue; // UNKNOWN keeps existing orders only.
            List<Map<String,Object>> targets=new ArrayList<Map<String,Object>>(visible(enemies));final Long priority=preferred.get(general.id);
            Collections.sort(targets,(a,b)->{boolean ap=Objects.equals(priority,id(a)),bp=Objects.equals(priority,id(b));if(ap!=bp)return ap?-1:1;int d=Double.compare(distance(a,general.x,general.y),distance(b,general.x,general.y));return d!=0?d:Long.compare(id(a),id(b));});
            boolean proposed=false;
            for(Map<String,Object> target:targets){List<Map<String,Object>> eligible=host.eligible(actors,target,enemies);if(eligible==null)continue;
                List<Long> ids=new ArrayList<Long>();for(Map<String,Object> actor:eligible)if(contains(actors,id(actor))&&!ids.contains(id(actor)))ids.add(id(actor));if(ids.isEmpty())continue;
                if(!Objects.equals(ledger.lastTarget,id(target))){ledger.lastTarget=id(target);ledger.commandRevision++;ledger.event("CURRENT_VISIBLE_TASK_CHANGED",now);}
                ids.removeIf(id->!due(ledger,id,now,NORMAL_RETRY_MS));if(ids.isEmpty()){proposed=true;break;}
                attackChunks(general,ledger,ids,number(target,"x"),number(target,"y"),id(target),"CURRENT_VISIBLE_TARGET",frame,now);proposed=true;break;
            }
            if(proposed||actors.isEmpty())continue;
            Map<String,Object> anchor=actors.get(0);Map<String,Object> plan=host.read("/scout/plan?role=army&unitId="+id(anchor)+"&avoid=","g5_general_frontier_plan");
            if(plan==null||!"planned".equals(plan.get("status"))||!Boolean.TRUE.equals(plan.get("pathKnown"))||GameClock.number(plan.get("targetTile"))==null||!finite(plan.get("targetX"))||!finite(plan.get("targetY")))continue;
            List<Long> ids=new ArrayList<Long>();for(Map<String,Object> actor:actors)if(Objects.equals(actor.get("type"),anchor.get("type"))&&due(ledger,id(actor),now,NORMAL_RETRY_MS))ids.add(id(actor));
            attackChunks(general,ledger,ids,number(plan,"targetX"),number(plan,"targetY"),null,"NATIVE_KNOWN_ANCHOR_FRONTIER",frame,now);
        }
    }
    private void attackChunks(final GeneralRegistry.GeneralView general,final CombatLedger ledger,List<Long> actors,final double x,final double y,final Long target,String reason,final long frame,final long now)throws Exception{
        for(int start=0;start<actors.size();start+=48){final List<Long> chunk=new ArrayList<Long>(actors.subList(start,Math.min(start+48,actors.size())));final Map<Long,Long> generations=new HashMap<Long,Long>();
            for(Long id:chunk)generations.put(id,registry.unit(id).ownerGeneration);final long revision=ledger.commandRevision;
            StringBuilder ids=new StringBuilder();for(Long id:chunk){if(ids.length()>0)ids.append(',');ids.append(id);}
            host.collect(new ForceController.Proposal(general.owner,chunk,"/command/attack-move?unitIds="+ids+"&x="+x+"&y="+y,"GENERAL",30,reason,
                receipt->{Long receiptFrame=GameClock.number(receipt.get("frame"));if(receiptFrame==null||receiptFrame<frame)return;for(Long id:chunk)if(!stillOwned(general,id,generations.get(id)))return;
                    for(Long id:chunk)accepted(ledger,id,receipt,now,revision);registry.updateGeneralGoal(general.id,x,y,target);recordAccepted(ledger,general,receipt,"ATTACK_ACCEPTED_NOT_EXECUTED");}));
        }
    }
    private void recordAccepted(CombatLedger ledger,GeneralRegistry.GeneralView general,Map<String,Object> receipt,String reason){changes.add(fields("reason",reason,"generalId",general.id.value,"owner",general.owner,"receiptFrame",receipt.get("frame"),"receiptGameTimeMs",receipt.get("gameTimeMs"),"after",ledger.view()));}
    private static void accepted(CombatLedger ledger,long id,Map<String,Object> receipt,long now,long revision){Long time=GameClock.number(receipt.get("gameTimeMs"));ledger.acceptedAt.put(id,time!=null&&time>=now?time:now);ledger.acceptedRevision.put(id,revision);}
    private static boolean due(CombatLedger ledger,long id,long now,long interval){Long accepted=ledger.acceptedAt.get(id),revision=ledger.acceptedRevision.get(id);return accepted==null||revision==null||revision!=ledger.commandRevision||now-accepted>=interval;}
    private boolean stillOwned(GeneralRegistry.GeneralView general,long id,long generation){GeneralRegistry.UnitView unit=registry.unit(id);return owned(general,unit)&&unit.ownerGeneration==generation;}
    private static boolean owned(GeneralRegistry.GeneralView general,GeneralRegistry.UnitView unit){return unit!=null&&unit.membership==GeneralRegistry.Membership.ATTACHED&&general.id.equals(unit.generalId)&&unit.temporaryTask==GeneralRegistry.TemporaryTask.NONE&&unit.externalOwner==null&&general.owner.equals(unit.owner);}
    private static double distanceToOrder(Map<String,Object> unit,double x,double y){return Math.hypot(number(unit,"orderX")-x,number(unit,"orderY")-y);}
    boolean combatCurrent(Map<String,Object> state,Map<String,Object> enemies,long now){Long source=GameClock.number(enemies==null?null:enemies.get("gameTimeMs"));Long frame=GameClock.number(state==null?null:state.get("frame")),enemyFrame=GameClock.number(enemies==null?null:enemies.get("frame"));
        if(!(state!=null&&enemies!=null&&state.get("sessionId") instanceof String&&state.get("sessionId").equals(enemies.get("sessionId"))&&source!=null&&source>=now&&(enemyFrame==null||frame!=null&&enemyFrame>=frame)&&enemies.get("visibleEnemies") instanceof List))return false;
        String ownPlayer=registry.observation().player;
        if(enemies.containsKey("playerKey")&&!Objects.equals(ownPlayer,enemies.get("playerKey")))return false;
        Object player=enemies.get("player");if(player instanceof Map&&((Map<?,?>)player).containsKey("teamId")){
            Object team=((Map<?,?>)player).get("teamId");if(!(team instanceof Number)||!ownPlayer.equals("team:"+((Number)team).longValue()))return false;
        }
        // A malformed/stale visible row cannot turn a partial packet into evidence of calm.
        for(Map<String,Object> enemy:items(enemies,"visibleEnemies"))if(!Objects.equals(source,GameClock.number(enemy.get("lastSeenGameTimeMs")))||id(enemy)<0||!finite(enemy.get("x"))||!finite(enemy.get("y"))||!(number(enemy,"hp")>0))return false;
        return true;}
    static List<Map<String,Object>> visible(Map<String,Object> enemies){List<Map<String,Object>> current=new ArrayList<Map<String,Object>>();Long source=GameClock.number(enemies==null?null:enemies.get("gameTimeMs"));if(source==null)return current;
        for(Map<String,Object> enemy:items(enemies,"visibleEnemies")){Long seen=GameClock.number(enemy.get("lastSeenGameTimeMs"));if(seen!=null&&seen.equals(source)&&id(enemy)>=0&&finite(enemy.get("x"))&&finite(enemy.get("y"))&&number(enemy,"hp")>0&&!Boolean.TRUE.equals(enemy.get("dead")))current.add(enemy);}return current;}
    private static boolean contains(List<Map<String,Object>> actors,long id){for(Map<String,Object> actor:actors)if(id(actor)==id)return true;return false;}
    private static boolean ready(Map<String,Object> unit){return unit!=null&&finite(unit.get("x"))&&finite(unit.get("y"))&&number(unit,"hp")>0&&!Boolean.TRUE.equals(unit.get("dead"))&&number(unit,"buildProgress")>=1;}
    private static boolean healthy(Map<String,Object> unit,GeneralRegistry.UnitView owner){return ready(unit)&&owner!=null&&owner.healthRole==GeneralRegistry.HealthRole.NORMAL&&number(unit,"maxHp")>0&&number(unit,"hp")>=number(unit,"maxHp")*.5;}
    private static double distance(Map<String,Object> unit,double x,double y){return Math.hypot(number(unit,"x")-x,number(unit,"y")-y);}
    private static double number(Map<String,Object> map,String key){Object value=map==null?null:map.get(key);return value instanceof Number?((Number)value).doubleValue():Double.NaN;}
    private static boolean finite(Object value){return value instanceof Number&&Double.isFinite(((Number)value).doubleValue());}
    private static double numeric(Object value){return value instanceof Number?((Number)value).doubleValue():Double.NaN;}
    private static long id(Map<String,Object> unit){Long value=GameClock.number(unit.get("id"));return value==null?-1:value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> map,String key){return map!=null&&map.get(key) instanceof List?(List<Map<String,Object>>)map.get(key):Collections.<Map<String,Object>>emptyList();}
    private static Map<String,Object> find(Map<String,Object> state,long id){for(Map<String,Object> actor:items(state,"ownUnits"))if(id(actor)==id)return actor;return null;}
    private static Map<String,Object> fields(Object... pairs){Map<String,Object> result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
}
