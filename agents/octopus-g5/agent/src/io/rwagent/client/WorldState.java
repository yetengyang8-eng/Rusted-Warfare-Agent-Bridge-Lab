package io.rwagent.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable G2 observational projection. Independent sources never form an atomic frame.
 * No control, lease, commitment, production occupancy or scheduling state lives here.
 */
public final class WorldState {
    public final String sessionId,playerId;
    public final long epoch,revision;
    public final Long referenceGameTimeMs;
    public final long referenceWallTimeMs;
    private final Map<String,SourceView> sourceViews;
    private final Map<Long,UnitFact> ownUnits;
    private final Map<Long,EnemyFact> enemies;

    WorldState(String session,String player,long epoch,long revision,Long referenceGame,long referenceWall,
               Map<String,SourceView> views,Map<Long,UnitFact> own,Map<Long,EnemyFact> enemy,long gameLimit,long wallLimit){
        sessionId=session;playerId=player;this.epoch=epoch;this.revision=revision;
        referenceGameTimeMs=referenceGame;referenceWallTimeMs=referenceWall;
        Map<String,SourceView> v=new TreeMap<String,SourceView>();
        for(Map.Entry<String,SourceView> e:views.entrySet())v.put(e.getKey(),e.getValue().at(referenceGame,referenceWall,gameLimit,wallLimit));
        sourceViews=Collections.unmodifiableMap(v);
        Map<Long,UnitFact> u=new TreeMap<Long,UnitFact>();
        for(Map.Entry<Long,UnitFact> e:own.entrySet()){
            UnitFact f=e.getValue();SourceView source=v.get(f.sourceScope);
            u.put(e.getKey(),source==null||source.expired||source.invalid?f.unknown("SOURCE_COVERAGE_UNKNOWN"):f);
        }
        ownUnits=Collections.unmodifiableMap(u);
        Map<Long,EnemyFact> en=new TreeMap<Long,EnemyFact>();
        for(Map.Entry<Long,EnemyFact> e:enemy.entrySet()){
            EnemyFact f=e.getValue();SourceView source=v.get(f.sourceScope);
            en.put(e.getKey(),source==null||source.expired||source.invalid?f.unknown("SOURCE_COVERAGE_UNKNOWN"):f);
        }
        enemies=Collections.unmodifiableMap(en);
    }
    public Map<String,SourceView> views(){return sourceViews;}
    public Map<Long,UnitFact> ownUnits(){return ownUnits;}
    public Map<Long,EnemyFact> enemies(){return enemies;}
    public Map<String,Object> toMap(){
        Map<String,Object> out=map("sessionId",sessionId,"playerId",playerId,"epoch",epoch,"revision",revision,
                "referenceGameTimeMs",referenceGameTimeMs,"referenceWallTimeMs",referenceWallTimeMs,
                "atomicAcrossSources",false,"confirmedEnemyLoss",null,"confirmedEnemyLossStatus","NEEDS_EVIDENCE",
                "commitmentAuthority","NOT_OWNED_BY_G2");
        Map<String,Object> v=new LinkedHashMap<String,Object>();for(Map.Entry<String,SourceView> e:sourceViews.entrySet())v.put(e.getKey(),e.getValue().toMap());
        Map<String,Object> u=new LinkedHashMap<String,Object>();for(Map.Entry<Long,UnitFact> e:ownUnits.entrySet())u.put(e.getKey().toString(),e.getValue().toMap());
        Map<String,Object> en=new LinkedHashMap<String,Object>();for(Map.Entry<Long,EnemyFact> e:enemies.entrySet())en.put(e.getKey().toString(),e.getValue().toMap());
        out.put("sources",v);out.put("ownUnits",u);out.put("enemies",en);return out;
    }

    public static final class SourceView {
        public final String scope,authority,contextPlayerId,coverage;
        public final GameClock.Observation observation;
        /** Raw legal packet, never a promise that every row is a current reconciled fact. */
        public final Map<String,Object> payload;
        public final boolean invalid,expired;
        public final Long ageGameTimeMs;
        public final Long ageWallTimeMs;
        public final Long contextAgeGameTimeMs;
        public final String gameFreshness,wallFreshness,contextGameFreshness;
        SourceView(String scope,GameClock.Observation o,Map<String,Object> payload,String authority,String player,
                   String coverage,boolean invalid){
            this(scope,o,payload,authority,player,coverage,invalid,false,null,Long.valueOf(0),
                    o.sourceGameTimeMs==null?"UNKNOWN_SOURCE_GAME_TIME":"AT_SOURCE_SAMPLE","AT_RECEIVE_SAMPLE",null,"NOT_EVALUATED",false);
        }
        private SourceView(String scope,GameClock.Observation o,Map<String,Object> payload,String authority,String player,
                           String coverage,boolean invalid,boolean expired,Long ageGame,Long ageWall,String game,String wall,Long contextAge,String contextFreshness,boolean frozen){
            this.scope=scope;observation=o;this.payload=frozen?payload:immutableMap(payload);this.authority=authority;
            contextPlayerId=player;this.coverage=coverage;this.invalid=invalid;this.expired=expired;
            ageGameTimeMs=ageGame;ageWallTimeMs=ageWall;gameFreshness=game;wallFreshness=wall;
            contextAgeGameTimeMs=contextAge;contextGameFreshness=contextFreshness;
        }
        SourceView at(Long referenceGame,long referenceWall,long gameLimit,long wallLimit){
            boolean ahead=referenceGame!=null&&observation.sourceGameTimeMs!=null&&referenceGame<observation.sourceGameTimeMs;
            Long game=referenceGame==null||observation.sourceGameTimeMs==null||ahead?null:Long.valueOf(referenceGame-observation.sourceGameTimeMs);
            boolean wallUnknown=referenceWall<observation.receivedWallTimeMs||observation.discontinuities.contains("WALL_CLOCK_ROLLBACK_DURING_READ")
                    ||observation.discontinuities.contains("WALL_CLOCK_ROLLBACK_BETWEEN_READS");
            Long wall=wallUnknown?null:Long.valueOf(referenceWall-observation.receivedWallTimeMs);
            Long contextAge=referenceGame==null||observation.detectedAtGameTimeMs==null||referenceGame<observation.detectedAtGameTimeMs?null:referenceGame-observation.detectedAtGameTimeMs;
            boolean staleGame=game!=null&&game>gameLimit,staleContext=contextAge!=null&&contextAge>gameLimit,staleWall=wall!=null&&wall>wallLimit;
            return new SourceView(scope,observation,payload,authority,contextPlayerId,coverage,invalid,
                    invalid||staleGame||staleContext||staleWall,game,wall,ahead?"SOURCE_AHEAD_OF_STATE_REFERENCE":game==null?"UNKNOWN_SOURCE_GAME_TIME":
                    staleGame?"EXPIRED":"WITHIN_OBSERVATIONAL_LIMIT",wallUnknown?"UNKNOWN_WALL_ORDER":staleWall?"EXPIRED":"WITHIN_OBSERVATIONAL_LIMIT",
                    contextAge,contextAge==null?"UNKNOWN_DETECTION_ANCHOR_AGE":staleContext?"EXPIRED":"WITHIN_OBSERVATIONAL_LIMIT",true);
        }
        public Map<String,Object> metadata(){return map("scope",scope,"authority",authority,
                "observation",observation.toMap(),"nativePlayerId",observation.sourcePlayerId,"contextPlayerId",contextPlayerId,
                "playerBinding",observation.sourcePlayerId==null?"VALIDATED_STATE_CONTEXT_ONLY":"NATIVE_SOURCE_FIELD",
                "coverage",coverage,"invalid",invalid,"expired",expired,"ageGameTimeMs",ageGameTimeMs,
                "ageWallTimeMs",ageWallTimeMs,"gameFreshness",gameFreshness,"wallFreshness",wallFreshness,
                "contextAgeGameTimeMs",contextAgeGameTimeMs,"contextGameFreshness",contextGameFreshness,
                "contextGameAgeBasis","STATE_DETECTION_ANCHOR_NOT_NATIVE_SOURCE_TIME",
                "atomicWithOtherSources",false,"payloadSemantics","RAW_SOURCE_PACKET_NOT_RECONCILED_CURRENT_TRUTH");}
        public Map<String,Object> toMap(){Map<String,Object> m=metadata();m.put("payload",payload);return m;}
    }
    public static final class LastObservation {
        public final GameClock.Observation observation;
        public final Map<String,Object> fields;
        public final String origin;
        LastObservation(GameClock.Observation o,Map<String,Object> fields){this(o,fields,"DIRECT_LEGAL_SOURCE_SAMPLE");}
        LastObservation(GameClock.Observation o,Map<String,Object> fields,String origin){observation=o;this.fields=immutableMap(fields);this.origin=origin;}
        public Map<String,Object> toMap(){return map("fields",fields,"readObservation",observation.toMap(),"origin",origin,
                "originalObservationId","DIRECT_LEGAL_SOURCE_SAMPLE".equals(origin)?observation.id:null,"semantics","HISTORICAL_NOT_CURRENT");}
    }
    public static final class UnitFact {
        public final long id;
        public final String availability,sourceScope;
        public final Map<String,Object> current;
        public final LastObservation lastObservation;
        UnitFact(long id,String status,String scope,Map<String,Object> current,LastObservation last){
            this.id=id;availability=status;sourceScope=scope;this.current=immutableMap(current);lastObservation=last;
        }
        UnitFact unknown(String reason){return new UnitFact(id,reason,sourceScope,Collections.<String,Object>emptyMap(),lastObservation);}
        public Map<String,Object> toMap(){return map("id",id,"availability",availability,"current",current,
                "lastObservation",lastObservation==null?null:lastObservation.toMap(),"sourceScope",sourceScope,"authority","STATE_OWN_UNIT_SAMPLE");}
    }
    public static final class EnemyFact {
        public final long id;
        public final String visibility,sourceScope,siteStatus;
        public final Map<String,Object> current;
        public final LastObservation lastObservation;
        EnemyFact(long id,String visibility,String scope,Map<String,Object> current,LastObservation last,String site){
            this.id=id;this.visibility=visibility;sourceScope=scope;this.current=immutableMap(current);lastObservation=last;siteStatus=site;
        }
        EnemyFact unknown(String reason){return new EnemyFact(id,reason,sourceScope,Collections.<String,Object>emptyMap(),lastObservation,siteStatus);}
        public Object currentField(String field){return current.get(field);}
        public Map<String,Object> toMap(){return map("id",id,"visibility",visibility,"current",current,
                "lastObservation",lastObservation==null?null:lastObservation.toMap(),"sourceScope",sourceScope,
                "siteStatus",siteStatus,"confirmedDestroyed",null,"confirmedDestroyedStatus","NEEDS_EVIDENCE",
                "authority","CURRENT_LEGAL_COMBAT_VISIBLE_ONLY");}
    }
    static Map<String,Object> map(Object... args){Map<String,Object> m=new LinkedHashMap<String,Object>();for(int i=0;i<args.length;i+=2)m.put((String)args[i],args[i+1]);return m;}
    @SuppressWarnings("unchecked") static Map<String,Object> immutableMap(Map<String,Object> input){return (Map<String,Object>)freeze(input);}
    private static Object freeze(Object value){
        if(value instanceof Map){Map<String,Object> copy=new LinkedHashMap<String,Object>();for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet())copy.put((String)e.getKey(),freeze(e.getValue()));return Collections.unmodifiableMap(copy);}
        if(value instanceof List){List<Object> copy=new ArrayList<Object>();for(Object v:(List<?>)value)copy.add(freeze(v));return Collections.unmodifiableList(copy);}
        return value;
    }
}
