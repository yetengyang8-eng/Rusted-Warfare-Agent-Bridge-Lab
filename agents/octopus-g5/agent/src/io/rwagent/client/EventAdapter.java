package io.rwagent.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Deterministic G2 snapshot adapter. It observes; existing policies keep their original inputs.
 * Snapshot differences are detection evidence, never exact births/deaths or product delivery.
 */
public final class EventAdapter {
    public static final long DEFAULT_GAME_GAP_MS=30000,DEFAULT_WALL_GAP_MS=15000;
    private static final int MAX_SCOPES=128,MAX_HISTORY=512,MAX_RETIRED_SESSIONS=64;
    private final String runId;
    private final long gameLimit,wallLimit;
    private String session,player;
    private long epoch,revision,eventSequence,referenceWall;
    private Long referenceGame;
    private final Map<String,WorldState.SourceView> views=new LinkedHashMap<String,WorldState.SourceView>();
    private final Map<Long,WorldState.UnitFact> own=new TreeMap<Long,WorldState.UnitFact>();
    private final Map<Long,WorldState.EnemyFact> enemies=new TreeMap<Long,WorldState.EnemyFact>();
    private final Set<String> retiredSessions=new LinkedHashSet<String>();
    private final Map<String,String> acceptedObservationIds=new LinkedHashMap<String,String>();
    private GameClock.Observation stateObservation;

    public EventAdapter(String runId){this(runId,DEFAULT_GAME_GAP_MS,DEFAULT_WALL_GAP_MS);}
    public EventAdapter(String runId,long coverageGapGameMs,long coverageGapWallMs){
        if(runId==null||runId.length()==0||coverageGapGameMs<=0||coverageGapWallMs<=0)throw new IllegalArgumentException("G2 run/coverage limits required");
        this.runId=runId;gameLimit=coverageGapGameMs;wallLimit=coverageGapWallMs;
    }
    public synchronized WorldState snapshot(){return new WorldState(session,player,epoch,revision,referenceGame,referenceWall,views,own,enemies,gameLimit,wallLimit);}
    public static final class Update {
        public final boolean accepted;
        public final String disposition,observationId,scope;
        public final WorldState snapshot;
        public final List<DerivedEvent> events;
        private Update(boolean accepted,String disposition,GameClock.Observation o,String scope,WorldState snapshot,List<DerivedEvent> events){
            this.accepted=accepted;this.disposition=disposition;observationId=o==null?null:o.id;this.scope=scope;this.snapshot=snapshot;
            this.events=Collections.unmodifiableList(new ArrayList<DerivedEvent>(events));
        }
        /** Compact log projection: full packets and events are deliberately not repeated. */
        public Map<String,Object> toMap(){
            WorldState.SourceView source=snapshot.views().get(scope);
            return WorldState.map("schema","rw-g2-world-update-v1","accepted",accepted,"disposition",disposition,
                    "observationId",observationId,"scope",scope,"epoch",snapshot.epoch,"revision",snapshot.revision,
                    "sessionId",snapshot.sessionId,"playerId",snapshot.playerId,"sourceCount",snapshot.views().size(),
                    "ownFactCount",snapshot.ownUnits().size(),"enemyFactCount",snapshot.enemies().size(),"eventCount",events.size(),
                    "coverage",source==null?"NO_ACCEPTED_SOURCE":source.coverage,"sourceFreshness",source==null?null:source.metadata(),
                    "atomicAcrossSources",false,"commitmentsReconciled",false);
        }
    }
    public static final class DerivedEvent {
        public final String id,kind,evidenceLevel;
        public final GameClock.Observation observation,previous;
        public final Map<String,Object> data;
        private DerivedEvent(String id,String kind,String evidence,GameClock.Observation current,GameClock.Observation previous,Map<String,Object> data){
            this.id=id;this.kind=kind;evidenceLevel=evidence;observation=current;this.previous=previous;this.data=WorldState.immutableMap(data);
        }
        public Map<String,Object> toMap(){return WorldState.map("schema","rw-g2-derived-event-v1","eventId",id,"kind",kind,
                "evidenceLevel",evidenceLevel,"observationId",observation.id,"sourceEndpoint",observation.endpoint,
                "requestPath",observation.requestPath,"sourceGameTimeMs",observation.sourceGameTimeMs,"sourceFrame",observation.sourceFrame,
                "observedAtGameTimeMs",observation.detectedAtGameTimeMs,"receivedAtWallTimeMs",observation.receivedWallTimeMs,
                "occurredAtGameTimeMs",null,"occurredAtStatus","NOT_PROVEN_BY_SNAPSHOT_DIFF",
                "previousObservationId",previous==null?null:previous.id,
                "sourceRangeStartGameTimeMs",previous==null?null:previous.sourceGameTimeMs,
                "sourceRangeEndGameTimeMs",observation.sourceGameTimeMs,
                "sourceRangeSemantics","SAME_REQUEST_SCOPE_SAMPLE_RANGE_NOT_EXACT_OCCURRENCE",
                "atomicAcrossSources",false,"data",data);}
    }
    public synchronized Update accept(GameClock.Observation o,Map<String,Object> payload){
        List<DerivedEvent> events=new ArrayList<DerivedEvent>();
        if(o==null||payload==null)return result(false,"MISSING_INPUT",o,null,events);
        String scope=o.endpoint+"|"+o.requestPath;
        if(!validObservationId(o.id))return result(false,"INVALID_OR_FOREIGN_RUN_OBSERVATION_ID",o,scope,events);
        if(!supported(o.endpoint))return result(false,"UNSUPPORTED_SOURCE",o,scope,events);
        String signature=fingerprint(payload),already=acceptedObservationIds.get(o.id);
        if(already!=null)return already.equals(signature)?result(false,"REPLAYED_OBSERVATION_ID",o,scope,events):
                invalidate(o,scope,payload,"CONFLICTING_OBSERVATION_ID",events);
        if(!validStamp(o,payload))return invalidate(o,scope,payload,"INVALID_SOURCE_STAMP",events);
        boolean isState="/state".equals(o.endpoint),reset=false;
        if(isState){
            Object nativePlayer=payload.get("player");
            if(o.sourceSessionId==null||o.sourcePlayerId==null||o.sourceGameTimeMs==null||o.sourceFrame==null
                    ||!(nativePlayer instanceof Map)||integer(((Map<?,?>)nativePlayer).get("teamId"))==null)
                return invalidateContext(o,scope,"STATE_IDENTITY_UNKNOWN",events);
            if(stateObservation!=null&&(!o.sourceSessionId.equals(session)||!o.sourcePlayerId.equals(player))){
                if(retiredSessions.contains(o.sourceSessionId)||o.requestedWallTimeMs<stateObservation.requestedWallTimeMs)
                    return result(false,"DELAYED_OR_RETIRED_STATE_CONTEXT",o,scope,events);
                if(!o.sourceSessionId.equals(session)){retiredSessions.add(session);trimSessions();}
                reset(o,events,"SESSION_OR_PLAYER_CHANGED");reset=true;
            }else if(stateObservation==null){reset(o,events,"INITIAL_CONTEXT");reset=true;}
        }else{
            if(session==null||player==null)return result(false,"NO_VALIDATED_STATE_CONTEXT",o,scope,events);
            if(o.sourceSessionId==null||!session.equals(o.sourceSessionId))return result(false,"FOREIGN_OR_UNKNOWN_SESSION",o,scope,events);
            if(o.sourcePlayerId!=null&&!player.equals(o.sourcePlayerId))return result(false,"FOREIGN_PLAYER",o,scope,events);
            if(o.sourceGameTimeMs!=null&&referenceGame!=null&&referenceGame-o.sourceGameTimeMs>gameLimit)
                return result(false,"STALE_SOURCE_RELATIVE_TO_VALIDATED_STATE",o,scope,events);
        }
        WorldState.SourceView previous=views.get(scope);
        if(previous!=null&&olderObservationOrdinal(o,previous.observation))return result(false,"OUT_OF_ORDER_OBSERVATION_ID",o,scope,events);
        if(previous!=null&&o.requestedWallTimeMs<previous.observation.requestedWallTimeMs
                &&(o.sourceGameTimeMs==null&&o.sourceFrame==null||sameStamp(o,previous.observation)))
            return result(false,"OUT_OF_ORDER_READ_WITHOUT_NEWER_NATIVE_STAMP",o,scope,events);
        boolean continuous=previous!=null&&!previous.invalid&&!reset;
        if(previous!=null&&older(o,previous.observation)){
            if(o.requestedWallTimeMs<previous.observation.requestedWallTimeMs)
                return result(false,"OUT_OF_ORDER_SOURCE_READ",o,scope,events);
            if(isState){reset(o,events,"STATE_SOURCE_CLOCK_ROLLBACK");reset=true;previous=null;continuous=false;}
            else return invalidate(o,scope,payload,"SOURCE_CLOCK_ROLLBACK_REBASE_REQUIRED",events);
        }
        if(previous!=null&&sameStamp(o,previous.observation)&&!previous.invalid){
            if(signature.equals(fingerprint(previous.payload))){
                String validation=validateRows(o,payload);if(validation!=null)return invalidate(o,scope,payload,validation,events);
                boolean duplicateGap=gap(o,previous.observation);
                if(duplicateGap)emit(events,"SOURCE_COVERAGE_GAP","DERIVED",o,previous.observation,
                        WorldState.map("scope",scope,"gameGapLimitMs",gameLimit,"wallGapLimitMs",wallLimit,"gameGapBasis",o.sourceGameTimeMs!=null&&previous.observation.sourceGameTimeMs!=null?"NATIVE_SOURCE_TIME":"STATE_DETECTION_ANCHOR_NOT_SOURCE_TIME","negativeDeltasSuppressed",true,"newReadSameNativeStamp",true));
                WorldState.SourceView refreshed=new WorldState.SourceView(scope,o,payload,authority(o.endpoint),player,duplicateGap?"DUPLICATE_NATIVE_STAMP_FRESH_READ_AFTER_GAP":"DUPLICATE_NATIVE_STAMP_FRESH_READ",false);
                views.put(scope,refreshed);revision++;referenceWall=Math.max(referenceWall,o.receivedWallTimeMs);
                if(isState){stateObservation=o;referenceGame=o.sourceGameTimeMs;}
                refreshFacts(o,refreshed);remember(o,signature);
                return result(true,"DUPLICATE_SOURCE_SNAPSHOT_FRESH_READ",o,scope,events);
            }
            return invalidate(o,scope,payload,"CONFLICTING_SAME_SOURCE_STAMP",events);
        }
        boolean gap=previous!=null&&gap(o,previous.observation);
        if(gap){continuous=false;emit(events,"SOURCE_COVERAGE_GAP","DERIVED",o,previous.observation,
                WorldState.map("scope",scope,"gameGapLimitMs",gameLimit,"wallGapLimitMs",wallLimit,
                        "gameGapBasis",o.sourceGameTimeMs!=null&&previous.observation.sourceGameTimeMs!=null?"NATIVE_SOURCE_TIME":"STATE_DETECTION_ANCHOR_NOT_SOURCE_TIME","negativeDeltasSuppressed",true));}
        String coverage=gap?"REBASE_AFTER_COVERAGE_GAP":previous!=null&&previous.invalid?"REBASE_AFTER_INVALID_SOURCE":
                o.sourceGameTimeMs==null&&o.sourceFrame==null?"READ_SEQUENCE_ONLY_SOURCE_TIME_UNKNOWN":"COMPLETE_SOURCE_SAMPLE";
        String validation=validateRows(o,payload);
        if(validation!=null)return invalidate(o,scope,payload,validation,events);
        WorldState.SourceView source=new WorldState.SourceView(scope,o,payload,authority(o.endpoint),player,coverage,false);
        views.put(scope,source);trimScopes(o,events);revision++;remember(o,signature);
        referenceWall=Math.max(referenceWall,o.receivedWallTimeMs);
        if(isState){stateObservation=o;referenceGame=o.sourceGameTimeMs;diffOwn(o,source,previous,continuous,events);}
        else if("/combat/observe".equals(o.endpoint))diffEnemies(o,source,previous,continuous,events);
        else if("/scout/observe".equals(o.endpoint))diffThreats(o,source,previous,continuous,events);
        trimHistory(o,events);
        return result(true,reset?"ACCEPTED_AFTER_RESET":gap?"ACCEPTED_AFTER_GAP":
                o.sourceGameTimeMs==null&&o.sourceFrame==null?"ACCEPTED_UNSTAMPED_READ":"ACCEPTED",o,scope,events);
    }
    private void reset(GameClock.Observation o,List<DerivedEvent> events,String reason){
        String oldSession=session,oldPlayer=player;session=o.sourceSessionId;player=o.sourcePlayerId;epoch++;
        views.clear();own.clear();enemies.clear();stateObservation=null;referenceGame=null;referenceWall=o.receivedWallTimeMs;
        emit(events,"WORLD_CONTEXT_RESET","DERIVED",o,null,WorldState.map("reason",reason,"previousSessionId",oldSession,"previousPlayerId",oldPlayer,"newSessionId",session,"newPlayerId",player,"entityDisappearanceEventsEmitted",false));
    }
    private Update invalidateContext(GameClock.Observation o,String scope,String reason,List<DerivedEvent> events){
        if(session!=null){epoch++;revision++;session=null;player=null;stateObservation=null;views.clear();own.clear();enemies.clear();referenceGame=null;
            emit(events,"WORLD_CONTEXT_INVALIDATED","DERIVED",o,null,WorldState.map("reason",reason,"entityDisappearanceEventsEmitted",false));}
        return result(false,reason,o,scope,events);
    }
    private Update invalidate(GameClock.Observation o,String scope,Map<String,Object> payload,String reason,List<DerivedEvent> events){
        WorldState.SourceView old=views.get(scope);
        if(session!=null){views.put(scope,new WorldState.SourceView(scope,o,payload,authority(o.endpoint),player,reason,true));trimScopes(o,events);revision++;}
        emit(events,"SOURCE_COVERAGE_INVALID","DERIVED",o,old==null?null:old.observation,
                WorldState.map("reason",reason,"scope",scope,"domainDeltasSuppressed",true));
        return result(false,reason,o,scope,events);
    }
    private Update result(boolean accepted,String reason,GameClock.Observation o,String scope,List<DerivedEvent> events){return new Update(accepted,reason,o,scope,snapshot(),events);}
    private void emit(List<DerivedEvent> events,String kind,String level,GameClock.Observation o,GameClock.Observation previous,Map<String,Object> data){
        events.add(new DerivedEvent(runId+":g2:"+epoch+":e:"+(++eventSequence),kind,level,o,previous,data));
    }
    private void diffOwn(GameClock.Observation o,WorldState.SourceView source,WorldState.SourceView previous,boolean continuous,List<DerivedEvent> events){
        Map<Long,Map<String,Object>> now=rows(source.payload,"ownUnits"),before=previous==null||previous.invalid?Collections.<Long,Map<String,Object>>emptyMap():rows(previous.payload,"ownUnits");
        for(Map.Entry<Long,Map<String,Object>> e:now.entrySet()){
            long id=e.getKey();Map<String,Object> row=e.getValue(),old=before.get(id);boolean alive=alive(row),oldAlive=continuous&&old!=null&&alive(old);
            WorldState.UnitFact prior=own.get(id);WorldState.LastObservation last=new WorldState.LastObservation(o,row);
            own.put(id,new WorldState.UnitFact(id,alive?"AVAILABLE":"EXPLICIT_DEAD_OR_NONPOSITIVE_HP",source.scope,row,last));
            if(old==null){emit(events,prior==null?"UNIT_FIRST_OBSERVED":"UNIT_REOBSERVED","DERIVED",o,null,WorldState.map("unitId",id,"state",row,"readyObserved",ready(row),"nativeCreationProven",false));
                if(ready(row))emit(events,"UNIT_READY_OBSERVED","DERIVED",o,null,WorldState.map("unitId",id,"state",row,"isSpecialistType",specialist(row),"readinessEvidence","READY_AT_FIRST_OR_REOBSERVATION","producerLineageConfirmed",false));}
            else if(continuous){
                if(oldAlive&&!alive)emit(events,"UNIT_BECAME_UNAVAILABLE","DERIVED",o,previous.observation,WorldState.map("unitId",id,"reasonClass",Boolean.TRUE.equals(row.get("dead"))?"EXPLICIT_DEAD_FLAG":"EXPLICIT_NONPOSITIVE_HP","lastSeenState",old,"observedState",row,"exactDeathTimeProven",false));
                if(!ready(old)&&ready(row))emit(events,"UNIT_READY_OBSERVED","DERIVED",o,previous.observation,WorldState.map("unitId",id,"state",row,"isSpecialistType",specialist(row),"producerLineageConfirmed",false));
                Integer oldBand=hpBand(old),newBand=hpBand(row);
                if(oldBand!=null&&newBand!=null&&!oldBand.equals(newBand))emit(events,"UNIT_HP_BAND_CHANGED","DERIVED",o,previous.observation,
                        WorldState.map("unitId",id,"oldBand",oldBand,"newBand",newBand,"bandDefinition","FLOOR_HP_FRACTION_TIMES_10_CLAMPED_0_TO_10","policyThreshold",false));
                Long q0=integer(old.get("productionQueue")),q1=integer(row.get("productionQueue"));
                if(q0!=null&&q1!=null&&q0>=0&&q1>=0&&!q0.equals(q1)){
                    emit(events,"QUEUE_CHANGED","DERIVED",o,previous.observation,WorldState.map("factoryId",id,"oldQueueCount",q0,"newQueueCount",q1,"productReadyProven",false,"commitmentsReconciled",false));
                    if(q0>0&&q1==0)emit(events,"QUEUE_BECAME_EMPTY","DERIVED",o,previous.observation,WorldState.map("factoryId",id,"productReadyProven",false,"producerProductLineageConfirmed",false,"acceptedUnobservedOccupancyReleased",false));
                }
            }else if(prior!=null)emit(events,"UNIT_REOBSERVED_AFTER_COVERAGE_GAP","OBSERVED",o,null,WorldState.map("unitId",id,"state",row));
        }
        for(Long id:new TreeSet<Long>(own.keySet()))if(!now.containsKey(id)){
            WorldState.UnitFact prior=own.get(id);Map<String,Object> lastRow=before.get(id);
            own.put(id,new WorldState.UnitFact(id,continuous?"ABSENT_FROM_COMPLETE_OWN_SAMPLE":"UNKNOWN_AFTER_COVERAGE_GAP",source.scope,Collections.<String,Object>emptyMap(),prior.lastObservation));
            if(continuous&&lastRow!=null&&alive(lastRow))emit(events,"UNIT_BECAME_UNAVAILABLE","DERIVED",o,previous.observation,
                    WorldState.map("unitId",id,"reasonClass","ABSENT_FROM_COMPLETE_OWN_SAMPLE","lastSeenState",lastRow,"confirmedDead",null,"exactDeathTimeProven",false));
        }
    }
    private void diffEnemies(GameClock.Observation o,WorldState.SourceView source,WorldState.SourceView previous,boolean continuous,List<DerivedEvent> events){
        Map<Long,Map<String,Object>> visible=rows(source.payload,"visibleEnemies");
        Map<Long,Map<String,Object>> before=previous==null||previous.invalid?Collections.<Long,Map<String,Object>>emptyMap():rows(previous.payload,"visibleEnemies");
        for(Map.Entry<Long,Map<String,Object>> e:visible.entrySet()){
            long id=e.getKey();Map<String,Object> row=e.getValue(),old=before.get(id);
            Map<String,Object> current=enemyCurrent(row,o.sourceGameTimeMs);
            WorldState.LastObservation last=new WorldState.LastObservation(o,current);
            enemies.put(id,new WorldState.EnemyFact(id,"VISIBLE_AT_SOURCE_SAMPLE",source.scope,current,last,"NOT_CLEARED"));
            // A fresh legal read advances provenance even when only sampling stamps changed.
            // Compare calibrated facts so stale raw domain values never become transitions.
            if(old==null||!continuous||!enemyFacts(current).equals(enemyFacts(enemyCurrent(old,previous.observation.sourceGameTimeMs))))
                emit(events,old==null||!continuous?"ENEMY_OBSERVED":"ENEMY_UPDATED","OBSERVED",o,continuous&&previous!=null?previous.observation:null,
                    WorldState.map("enemyId",id,"current",current,"attribution","UNKNOWN","maxHp",null,"confirmedDestroyed",null));
        }
        for(Long id:new TreeSet<Long>(enemies.keySet()))if(!visible.containsKey(id)){
            WorldState.EnemyFact prior=enemies.get(id);boolean wasVisible=before.containsKey(id);
            enemies.put(id,new WorldState.EnemyFact(id,continuous?"NOT_VISIBLE_AT_SOURCE_SAMPLE":"UNKNOWN_AFTER_COVERAGE_GAP",source.scope,
                    Collections.<String,Object>emptyMap(),prior.lastObservation,prior.siteStatus));
            if(continuous&&wasVisible)emit(events,"ENEMY_LOST_VISIBILITY","DERIVED",o,previous.observation,
                    WorldState.map("enemyId",id,"lastObservation",prior.lastObservation==null?null:prior.lastObservation.toMap(),"confirmedDestroyed",null));
        }
        Object intel=source.payload.get("enemyIntel");
        if(intel instanceof List)for(Map<String,Object> contact:sortedRows((List<?>)intel)){
            Long id=integer(contact.get("id"));if(id==null||id<0)continue;
            if(!visible.containsKey(id)&&!enemies.containsKey(id)&&("LOST_CONTACT".equals(contact.get("status"))||"CLEARED".equals(contact.get("status")))&&finite(contact.get("lastKnownHp"))&&finite(contact.get("lastKnownX"))&&finite(contact.get("lastKnownY"))){
                WorldState.LastObservation last=new WorldState.LastObservation(o,WorldState.map("id",id,"type",contact.get("lastKnownType"),"hp",contact.get("lastKnownHp"),"x",contact.get("lastKnownX"),"y",contact.get("lastKnownY"),"lastSeenGameTimeMs",contact.get("lastSeenGameTimeMs")),"NATIVE_LAST_KNOWN_HISTORY_ONLY");
                enemies.put(id,new WorldState.EnemyFact(id,"HISTORICAL_CONTACT_CURRENT_UNKNOWN",source.scope,Collections.<String,Object>emptyMap(),last,"NOT_CLEARED"));
            }
            if(!"CLEARED".equals(contact.get("status"))
                    ||!Boolean.TRUE.equals(contact.get("lastKnownBuilding"))||!Boolean.TRUE.equals(contact.get("lastKnownSiteVisible"))
                    ||visible.containsKey(id)||integer(contact.get("clearedGameTimeMs"))==null)continue;
            WorldState.EnemyFact prior=enemies.get(id);if(prior==null)continue;
            if(!"SITE_CLEARED".equals(prior.siteStatus))emit(events,"ENEMY_SITE_CLEARED","DERIVED",o,continuous&&previous!=null?previous.observation:null,
                    WorldState.map("enemyId",id,"siteEvidence",contact,"confirmedDestroyed",null,"confirmedKillProven",false));
            enemies.put(id,new WorldState.EnemyFact(id,prior.visibility,source.scope,prior.current,prior.lastObservation,"SITE_CLEARED"));
        }
    }
    private static Map<String,Object> enemyCurrent(Map<String,Object> row,Long sourceTime){
        Map<String,Object> current=new LinkedHashMap<String,Object>(row);
        if(!equalLong(row.get("domainObservedAtGameTimeMs"),sourceTime)){
            current.put("targetDomain","UNKNOWN");current.put("touchingWater",null);current.put("domainEvidenceStatus","UNKNOWN_OR_STALE_DOMAIN_SOURCE");
        }
        return current;
    }
    /** All observed fact fields participate; only explicit read/sample provenance is excluded. */
    private static String enemyFacts(Map<String,Object> current){
        Map<String,Object> facts=new LinkedHashMap<String,Object>(current);
        for(String key:new String[]{"lastSeenGameTimeMs","domainObservedAtGameTimeMs","observedAtGameTimeMs",
                "sampleGameTimeMs","sourceGameTimeMs","gameTimeMs","frame","sourceFrame","sampleFrame",
                "requestedWallTimeMs","receivedWallTimeMs","sampleWallTimeMs"})facts.remove(key);
        return fingerprint(facts,"");
    }
    private void diffThreats(GameClock.Observation o,WorldState.SourceView source,WorldState.SourceView previous,boolean continuous,List<DerivedEvent> events){
        Map<Long,Map<String,Object>> now=rows(source.payload,"visibleThreats"),old=previous==null?Collections.<Long,Map<String,Object>>emptyMap():rows(previous.payload,"visibleThreats");
        for(Map.Entry<Long,Map<String,Object>> e:now.entrySet())if(!old.containsKey(e.getKey()))emit(events,"LOCAL_THREAT_OBSERVED","OBSERVED",o,null,WorldState.map("threatId",e.getKey(),"current",e.getValue(),"authority","SCOUT_CURRENT_VISIBLE_ARMED_SUBSET"));
        if(continuous)for(Map.Entry<Long,Map<String,Object>> e:old.entrySet())if(!now.containsKey(e.getKey()))emit(events,"LOCAL_THREAT_BECAME_NOT_VISIBLE","DERIVED",o,previous.observation,WorldState.map("threatId",e.getKey(),"lastObservation",e.getValue(),"confirmedDestroyed",null,"taskClearAuthorized",false));
    }
    private String validateRows(GameClock.Observation o,Map<String,Object> payload){
        String key="/state".equals(o.endpoint)?"ownUnits":"/combat/observe".equals(o.endpoint)?"visibleEnemies":"/scout/observe".equals(o.endpoint)?"visibleThreats":"/combat/production".equals(o.endpoint)?"factories":null;
        if(key==null)return null;
        if("visibleEnemies".equals(key)&&o.sourceGameTimeMs==null)return "MISSING_COMBAT_SOURCE_TIME_COVERAGE";
        if("visibleThreats".equals(key)&&o.sourceGameTimeMs==null&&o.sourceFrame==null)return "MISSING_SCOUT_NATIVE_STAMP_COVERAGE";
        if(!(payload.get(key) instanceof List))return "MISSING_OR_INVALID_ENTITY_LIST";
        Set<Long> seen=new LinkedHashSet<Long>();
        for(Object value:(List<?>)payload.get(key)){
            if(!(value instanceof Map))return "INVALID_ENTITY_ROW";
            Map<?,?> row=(Map<?,?>)value;Long id=integer(row.get("id"));
            if(id==null||id<0||!seen.add(id))return "INVALID_OR_DUPLICATE_ENTITY_ID";
            if("ownUnits".equals(key)&&(!finite(row.get("hp"))||!finite(row.get("maxHp"))||!finite(row.get("buildProgress"))||!(row.get("dead") instanceof Boolean)))return "INVALID_OWN_UNIT_FIELDS";
            if("visibleEnemies".equals(key)&&(!finite(row.get("hp"))||!finite(row.get("x"))||!finite(row.get("y"))||!equalLong(row.get("lastSeenGameTimeMs"),o.sourceGameTimeMs)))return "VISIBLE_ENEMY_ROW_EVIDENCE_GAP";
        }
        return null;
    }
    private static boolean validStamp(GameClock.Observation o,Map<String,Object> payload){
        for(String key:new String[]{"gameTimeMs","frame"})if(payload.containsKey(key)&&(integer(payload.get(key))==null||integer(payload.get(key))<0))return false;
        return sameNullable(o.sourceGameTimeMs,integer(payload.get("gameTimeMs")))&&sameNullable(o.sourceFrame,integer(payload.get("frame")))
                &&sameNullable(o.sourceSessionId,payload.get("sessionId"))&&sameNullable(o.sourcePlayerId,payloadPlayer(payload));
    }
    private static boolean supported(String endpoint){return "/state".equals(endpoint)||endpoint.startsWith("/combat/")||endpoint.startsWith("/scout/")||endpoint.startsWith("/economy/");}
    private boolean validObservationId(String id){String prefix=runId+":o:";if(!id.startsWith(prefix))return false;try{return Long.parseLong(id.substring(prefix.length()))>0;}catch(NumberFormatException invalid){return false;}}
    private static String authority(String endpoint){return "/state".equals(endpoint)?"STATE_OWN_UNITS_PLAYER_MATCH":
            "/combat/observe".equals(endpoint)?"COMBAT_CURRENT_VISIBLE_ENEMIES_PLUS_SEPARATE_HISTORY":
            "/scout/observe".equals(endpoint)?"SCOUT_VISIBLE_THREATS_AND_HISTORICAL_RESOURCE_TERRAIN":
            "/combat/production".equals(endpoint)?"NATIVE_AVAILABLE_LAND_FACTORY_MENU_SUBSET_NO_NATIVE_TIME":"SCOPED_NATIVE_QUERY_ONLY";}
    private static boolean older(GameClock.Observation a,GameClock.Observation b){return lower(a.sourceGameTimeMs,b.sourceGameTimeMs)||lower(a.sourceFrame,b.sourceFrame);}
    private static boolean sameStamp(GameClock.Observation a,GameClock.Observation b){return (a.sourceGameTimeMs!=null||a.sourceFrame!=null)&&sameNullable(a.sourceGameTimeMs,b.sourceGameTimeMs)&&sameNullable(a.sourceFrame,b.sourceFrame);}
    private boolean gap(GameClock.Observation a,GameClock.Observation b){Long t=a.sourceGameTimeMs!=null&&b.sourceGameTimeMs!=null?a.sourceGameTimeMs:a.detectedAtGameTimeMs;
        Long previous=a.sourceGameTimeMs!=null&&b.sourceGameTimeMs!=null?b.sourceGameTimeMs:b.detectedAtGameTimeMs;
        return t!=null&&previous!=null&&t-previous>gameLimit||a.receivedWallTimeMs-b.receivedWallTimeMs>wallLimit;}
    private static String payloadPlayer(Map<String,Object> payload){Object p=payload.get("player");if(p instanceof Map){Object team=((Map<?,?>)p).get("teamId");if(team!=null)return "team:"+team;Object id=((Map<?,?>)p).get("id");return id==null?null:String.valueOf(id);}return p instanceof Number?"team:"+p:p instanceof String?(String)p:GameClock.string(payload.get("playerId"));}
    private static boolean olderObservationOrdinal(GameClock.Observation a,GameClock.Observation b){int ai=a.id.lastIndexOf(":o:"),bi=b.id.lastIndexOf(":o:");if(ai<0||bi<0||!a.id.substring(0,ai).equals(b.id.substring(0,bi)))return false;try{return Long.parseLong(a.id.substring(ai+3))<Long.parseLong(b.id.substring(bi+3));}catch(NumberFormatException unknown){return false;}}
    private static boolean lower(Long a,Long b){return a!=null&&b!=null&&a<b;}
    private static boolean sameNullable(Object a,Object b){return a==null?b==null:a.equals(b);}
    private static boolean equalLong(Object a,Long b){Long n=integer(a);return n!=null&&b!=null&&n.equals(b);}
    static Long integer(Object value){if(value instanceof Long||value instanceof Integer||value instanceof Short||value instanceof Byte)return Long.valueOf(((Number)value).longValue());if(!(value instanceof Double||value instanceof Float))return null;double n=((Number)value).doubleValue();if(Double.isNaN(n)||Double.isInfinite(n)||n!=Math.rint(n)||n>=0x1.0p63||n< -0x1.0p63)return null;return Long.valueOf(((Number)value).longValue());}
    private static boolean finite(Object v){return v instanceof Number&&!Double.isNaN(((Number)v).doubleValue())&&!Double.isInfinite(((Number)v).doubleValue());}
    private static boolean alive(Map<String,Object> row){return !Boolean.TRUE.equals(row.get("dead"))&&((Number)row.get("hp")).doubleValue()>0;}
    private static boolean ready(Map<String,Object> row){return alive(row)&&((Number)row.get("buildProgress")).doubleValue()>=1;}
    private static boolean specialist(Map<String,Object> row){return "amphibiousJet".equals(row.get("type"))||"combatEngineer".equals(row.get("type"));}
    private static Integer hpBand(Map<String,Object> row){double hp=((Number)row.get("hp")).doubleValue(),max=((Number)row.get("maxHp")).doubleValue();return max<=0?null:Integer.valueOf((int)Math.floor(Math.max(0,Math.min(1,hp/max))*10));}
    @SuppressWarnings("unchecked") private static Map<Long,Map<String,Object>> rows(Map<String,Object> payload,String key){
        Map<Long,Map<String,Object>> result=new TreeMap<Long,Map<String,Object>>();Object value=payload.get(key);if(!(value instanceof List))return result;
        for(Object row:(List<?>)value)if(row instanceof Map){Long id=integer(((Map<?,?>)row).get("id"));if(id!=null)result.put(id,(Map<String,Object>)row);}return result;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> sortedRows(List<?> rows){List<Map<String,Object>> out=new ArrayList<Map<String,Object>>();for(Object row:rows)if(row instanceof Map&&integer(((Map<?,?>)row).get("id"))!=null)out.add((Map<String,Object>)row);Collections.sort(out,new Comparator<Map<String,Object>>(){public int compare(Map<String,Object>a,Map<String,Object>b){return Long.compare(integer(a.get("id")),integer(b.get("id")));}});return out;}
    private static String fingerprint(Object value){try{byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(fingerprint(value,"").getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static String fingerprint(Object value,String key){
        if(value instanceof Map){StringBuilder b=new StringBuilder("{");Map<String,Object> sorted=new TreeMap<String,Object>();for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet())sorted.put((String)e.getKey(),e.getValue());for(Map.Entry<String,Object> e:sorted.entrySet())b.append(Json.quote(e.getKey())).append(':').append(fingerprint(e.getValue(),e.getKey())).append(',');return b.append('}').toString();}
        if(value instanceof List){List<String> parts=new ArrayList<String>();for(Object item:(List<?>)value)parts.add(fingerprint(item,""));if(key.matches("ownUnits|visibleEnemies|enemyIntel|rememberedEnemies|visibleThreats|rememberedThreats|factories|units|actors"))Collections.sort(parts);return parts.toString();}
        return value==null?"null":value instanceof String?Json.quote((String)value):String.valueOf(value);
    }
    private void trimScopes(GameClock.Observation o,List<DerivedEvent> events){while(views.size()>MAX_SCOPES){String victim=null;for(Map.Entry<String,WorldState.SourceView> e:views.entrySet())if(!e.getValue().observation.endpoint.matches("/state|/combat/observe|/scout/observe|/combat/production")){victim=e.getKey();break;}if(victim==null)victim=views.keySet().iterator().next();views.remove(victim);emit(events,"SOURCE_VIEW_EVICTED","DERIVED",o,null,WorldState.map("scope",victim,"reason","BOUNDED_REQUEST_SCOPE_HISTORY","entityLossProven",false));}}
    private void remember(GameClock.Observation o,String signature){acceptedObservationIds.put(o.id,signature);while(acceptedObservationIds.size()>512)acceptedObservationIds.remove(acceptedObservationIds.keySet().iterator().next());}
    private void refreshFacts(GameClock.Observation o,WorldState.SourceView source){
        if("/state".equals(o.endpoint))for(Map.Entry<Long,Map<String,Object>> e:rows(source.payload,"ownUnits").entrySet())own.put(e.getKey(),new WorldState.UnitFact(e.getKey(),alive(e.getValue())?"AVAILABLE":"EXPLICIT_DEAD_OR_NONPOSITIVE_HP",source.scope,e.getValue(),new WorldState.LastObservation(o,e.getValue())));
        if("/combat/observe".equals(o.endpoint))for(Map.Entry<Long,Map<String,Object>> e:rows(source.payload,"visibleEnemies").entrySet()){
            WorldState.EnemyFact prior=enemies.get(e.getKey());Map<String,Object> fields=enemyCurrent(e.getValue(),o.sourceGameTimeMs);
            enemies.put(e.getKey(),new WorldState.EnemyFact(e.getKey(),"VISIBLE_AT_SOURCE_SAMPLE",source.scope,fields,new WorldState.LastObservation(o,fields),prior==null?"NOT_CLEARED":prior.siteStatus));}
    }
    private void trimSessions(){while(retiredSessions.size()>MAX_RETIRED_SESSIONS)retiredSessions.remove(retiredSessions.iterator().next());}
    private void trimHistory(GameClock.Observation o,List<DerivedEvent> events){
        while(own.size()>MAX_HISTORY){Long victim=null;for(Map.Entry<Long,WorldState.UnitFact> e:own.entrySet())if(!"AVAILABLE".equals(e.getValue().availability)){victim=e.getKey();break;}if(victim==null)break;own.remove(victim);emit(events,"HISTORICAL_RECORD_EVICTED","DERIVED",o,null,WorldState.map("unitId",victim,"reason","BOUNDED_OWN_HISTORY","deathProven",false));}
        while(enemies.size()>MAX_HISTORY){Long victim=null;for(Map.Entry<Long,WorldState.EnemyFact> e:enemies.entrySet())if(!"VISIBLE_AT_SOURCE_SAMPLE".equals(e.getValue().visibility)){victim=e.getKey();break;}if(victim==null)break;enemies.remove(victim);emit(events,"HISTORICAL_RECORD_EVICTED","DERIVED",o,null,WorldState.map("enemyId",victim,"reason","BOUNDED_ENEMY_HISTORY","deathProven",false));}
    }
}
