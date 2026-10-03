package io.rwagent.bootstrap;

import io.rwagent.client.TargetCatalog;
import io.rwagent.client.EngagementGeometry;

import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.y;
import com.corrodinggames.rts.gameFramework.e;
import com.sun.net.httpserver.HttpServer;
import java.util.*;
import static io.rwagent.bootstrap.RuntimeBridge.*;
import static io.rwagent.bootstrap.EconomyBridge.*;
import com.corrodinggames.rts.game.units.as;
import java.lang.reflect.Method;

/** Own native menus, currently visible hostiles, last-seen memory and group attack-move. */
final class CombatBridge {
    private final RuntimeBridge b;
    private static final Method UPGRADE=method(am.class,"cm"), ID_TEXT=method(ACTION_ID.getReturnType(),"a");
    private static String actionId(Object a){return (String)invoke(ID_TEXT,invoke(ACTION_ID,a));}
    private static String product(Object a){as t=(as)invoke(TYPE,a);return t==null?"upgrade":t.i();}
    private String session;
    private final Map<Long,Enemy> memory=new LinkedHashMap<Long,Enemy>();
    /** Read-only war memory; only fresh legal visible samples can create or update a contact. */
    private final Map<Long,EnemyIntel> enemyIntel=new LinkedHashMap<Long,EnemyIntel>();
    private int enemyIntelEvicted;
    private static final int MAX_ENEMY_INTEL=256;
    private final Map<String,Receipt> receipts=new LinkedHashMap<String,Receipt>();
    CombatBridge(RuntimeBridge b){this.b=b;}
    void install(HttpServer server){
        for(final String path:new String[]{"/combat/observe","/combat/production","/combat/capabilities","/combat/reachability","/combat/engagement","/combat/unit-modes","/command/attack-move","/command/queue","/command/unit-mode"})
            server.createContext(path,x -> {
                if(!path.equals(x.getRequestURI().getPath())){respond(x,404,jsonError("unknown endpoint"));return;}
                String method=path.startsWith("/command/")?"POST":"GET";
                if(!method.equals(x.getRequestMethod())){respond(x,405,jsonError(method+" required"));return;}
                if(x.getRequestHeaders().getFirst("Origin")!=null){respond(x,403,jsonError("browser-origin requests disabled"));return;}
                try{
                    final Map<String,String> q=EconomyBridge.query(x.getRequestURI().getRawQuery());
                    CommandResult r=b.onGameThread(() -> {
                        try{return dispatch(path,q);}catch(IllegalArgumentException err){return CommandResult.error(400,err.getMessage());}
                    });respond(x,r.httpStatus,r.json);
                }catch(IllegalArgumentException err){respond(x,400,jsonError(err.getMessage()));}
                catch(Exception err){RwAgent.log("Combat request failed",err);respond(x,503,jsonError("result unknown; observe before retrying"));}
            });
    }
    static String matchJson(RuntimeBridge b){
        // These are the native result-screen flags and OWN player flags, never an enemy census.
        boolean lost=b.engine.dt,won=b.engine.dq;
        String outcome=lost?"DEFEAT":won?"VICTORY":"ONGOING";
        return "{\"outcome\":\""+outcome+"\",\"nativeVictory\":"+won+",\"nativeDefeat\":"+lost
            +",\"ownTeamVictory\":"+b.engine.bs.H+",\"ownTeamDefeated\":"+b.engine.bs.F
            +",\"ownTeamWipedOut\":"+b.engine.bs.G+",\"source\":\"native_result_screen\"}";
    }
    private CommandResult dispatch(String path,Map<String,String> q){
        b.refreshSession();CommandResult guard=b.commandGuard();if(guard!=null)return guard;
        if(!b.sessionId.equals(session)){session=b.sessionId;memory.clear();enemyIntel.clear();enemyIntelEvicted=0;receipts.clear();}
        if(path.startsWith("/combat/")){
            if(path.endsWith("unit-modes"))return unitModes(q);
            if(path.endsWith("engagement"))return engagement(q);
            if(path.endsWith("capabilities"))return CommandResult.ok(capabilities(q));
            if(path.endsWith("reachability")){
                // 输出21 §2: read-only raw diagnostic; it never returns a reachable/engageable verdict.
                Set<String> allowed=new HashSet<String>(Arrays.asList("unitId","targetId","dump","groups","incident","stages"));
                if(!q.keySet().containsAll(Arrays.asList("unitId","targetId"))||!allowed.containsAll(q.keySet()))
                    throw new IllegalArgumentException("required: unitId,targetId[,dump][,groups][,incident][,stages]");
                return CommandResult.ok(reachability(q));
            }
            if(!q.isEmpty())throw new IllegalArgumentException("observation accepts no query fields");
            return CommandResult.ok(path.endsWith("production")?production():observe());
        }
        boolean attack=path.endsWith("attack-move"),mode=path.endsWith("unit-mode");
        List<String> keys=attack?Arrays.asList("unitIds","x","y","sessionId","requestId"):Arrays.asList("unitId","actionId","sessionId","requestId");
        if(q.size()!=keys.size() || !q.keySet().containsAll(keys))throw new IllegalArgumentException("required: "+keys);
        if(!session.equals(q.get("sessionId")))return CommandResult.error(409,"session changed; observe again");
        String request=q.get("requestId"),fingerprint=path+new TreeMap<String,String>(q);
        if(!request.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("invalid requestId");
        Receipt old=receipts.get(request);if(old!=null)return old.fingerprint.equals(fingerprint)?old.result:CommandResult.error(409,"requestId reused with different command");
        if(b.engine.dq || b.engine.dt)return CommandResult.error(409,"match already ended");
        String extra;
        if(attack){
            float x=Float.parseFloat(q.get("x")),yy=Float.parseFloat(q.get("y"));
            if(!Float.isFinite(x)||!Float.isFinite(yy)||x<0||yy<0||x>=b.engine.bL.i()||yy>=b.engine.bL.j())throw new IllegalArgumentException("finite in-map target required");
            String[] ids=q.get("unitIds").split(",",-1);
            if(ids.length<1||ids.length>48)throw new IllegalArgumentException("1..48 units required");
            Set<Long> unique=new LinkedHashSet<Long>();List<y> actors=new ArrayList<y>();
            for(String id:ids){long uid=Long.parseLong(id);y u=own(uid);
                if(!unique.add(uid))throw new IllegalArgumentException("duplicate unit ID");
                if(u==null||!u.I()||!u.l())return CommandResult.error(409,"completed own armed mobile required");actors.add(u);
            }
            e command=b.engine.cf.b(b.engine.bs);for(y u:actors)command.a(u);command.b(x,yy);
            extra=",\"unitIds\":"+unique+",\"targetX\":"+format(x)+",\"targetY\":"+format(yy)+",\"orderType\":\"attackMove\"";
        }else if(mode){
            y actor=own(Long.parseLong(q.get("unitId")));
            if(actor==null||!"amphibiousJet".equals(actor.r().i()))return CommandResult.error(409,"completed own amphibiousJet required");
            Object action=modeAction(actor,q.get("actionId"));
            if(action==null||!Boolean.TRUE.equals(invoke(AVAILABLE,action,actor))||!Boolean.TRUE.equals(invoke(AFFORDABLE,action,actor,true)))
                return CommandResult.error(409,"native amphibious mode unavailable at own current position");
            e command=b.engine.cf.b(b.engine.bs);command.a(actor);invoke(SET_ACTION,command,invoke(ACTION_ID,action));
            extra=",\"unitId\":"+actor.eh+",\"actionId\":\""+escape(actionId(action))+"\",\"type\":\"amphibiousJet\",\"mode\":\""+("152".equals(q.get("actionId"))?"DIVE":"FLY")+"\"";
        }else{
            long id=Long.parseLong(q.get("unitId"));y factory=own(id);
            if(factory==null||!"landFactory".equals(factory.r().i()))return CommandResult.error(409,"completed own landFactory required");
            if(EconomyBridge.queueCount(factory)!=0)return CommandResult.error(409,"factory queue must be empty");
            Object action=null;for(Object item:factory.N()){
                Object candidate=item;if(allowed(factory,candidate)&&actionId(candidate).equals(q.get("actionId")))action=candidate;
            }
            if(action==null||!((Boolean)invoke(AVAILABLE,action,factory))||!((Boolean)invoke(AFFORDABLE,action,factory,true)))return CommandResult.error(409,"native action unavailable or insufficient credits");
            e command=b.engine.cf.b(b.engine.bs);command.a(factory);invoke(SET_ACTION,command,invoke(ACTION_ID,action));
            extra=",\"unitId\":"+id+",\"actionId\":\""+escape(actionId(action))+"\",\"type\":\""+escape(product(action))+"\"";
        }
        CommandResult result=CommandResult.ok("{\"status\":\"queued\",\"sessionId\":\""+session+"\",\"requestId\":\""+request+"\",\"frame\":"+b.engine.bx+extra+"}");
        receipts.put(request,new Receipt(fingerprint,result));if(receipts.size()>256)receipts.remove(receipts.keySet().iterator().next());return result;
    }
    private y own(long id){
        am[] units=am.bE.a();for(int i=0;i<am.bE.size();i++){am u=units[i];
            if(u instanceof y&&u.eh==id&&u.bX==b.engine.bs&&!u.ej&&!u.bV&&!u.cW()&&u.cm>=1)return (y)u;
        }return null;
    }
    /** Only own actors and an already legally observed contact. No hidden target lookup. */
    private CommandResult engagement(Map<String,String> q){
        if(!q.keySet().equals(new HashSet<String>(Arrays.asList("unitIds","targetId"))))
            throw new IllegalArgumentException("required: unitIds,targetId");
        if(b.engine.bL==null||b.engine.bU==null||(long)b.engine.bL.C*b.engine.bL.D>262144)
            return CommandResult.error(409,"engagement terrain unavailable");
        Enemy target=memory.get(Long.valueOf(q.get("targetId")));
        if(target==null){
            EnemyIntel historical=enemyIntel.get(Long.valueOf(q.get("targetId")));
            if(historical!=null&&historical.clearedAt<0)target=historical.last;
        }
        if(target==null)return CommandResult.error(409,"target absent from legal observation memory");
        String[] ids=q.get("unitIds").split(",",-1);
        if(ids.length<1||ids.length>48)throw new IllegalArgumentException("1..48 actors required");
        List<y> actors=new ArrayList<y>();Set<Long> unique=new HashSet<Long>();
        for(String value:ids){long id=Long.parseLong(value);y actor=own(id);
            if(actor==null||!actor.I()||!actor.l())return CommandResult.error(409,"completed own armed mobile required");
            if(!unique.add(id))throw new IllegalArgumentException("duplicate actor");actors.add(actor);
        }
        b.scout.refreshEngagementTerrain(actors);
        boolean current=target.time==b.engine.by;
        // HTTP reads may straddle ticks. Re-observe using the same fog-gated adapter, never anyUnit().
        observe();Enemy latest=memory.get(target.id);
        if(latest!=null){target=latest;current=target.time==b.engine.by;}
        StringBuilder out=new StringBuilder("{\"status\":\"observed\",\"sessionId\":\"").append(session)
            .append("\",\"player\":").append(b.engine.bs.k).append(",\"frame\":").append(b.engine.bx)
            .append(",\"gameTimeMs\":").append(b.engine.by).append(",\"targetId\":").append(target.id)
            .append(",\"targetX\":").append(format(target.x)).append(",\"targetY\":").append(format(target.y))
            .append(",\"targetObservedAtGameTimeMs\":").append(target.time).append(",\"targetVisible\":").append(current)
            .append(",\"source\":\"LEGAL_TERRAIN_MEMORY_AND_OWN_NATIVE_CAPABILITY\",\"scope\":\"APPROACH_ONLY_NOT_FIRE_OR_KILL_PROOF\",\"actors\":[");
        boolean first=true;
        Map<String,EngagementGeometry.Field> fields=new HashMap<String,EngagementGeometry.Field>();
        for(y actor:actors){
            if(!first)out.append(',');first=false;
            // Frozen y.o(am) adds collision radii only when aV() (melee range) is true.
            // aV is a constant false in y, or the custom type's eF field; audited passive reads.
            double range=actor.m()+(actor.aV()?actor.cj+target.radius:0);boolean submerged="SUBMERGED".equals(target.domain);
            // These established native capability accessors are also used by the ordinary engine
            // domain guard. No reflective method discovery or quarantined reachability probe.
            String compatibility=nativeDomainCompatibility(target.domain,target.touchingWater,current,
                    actor.af(),actor.ae(),actor.ag(),actor.ah());
            boolean waterRequired="combatEngineer".equals(actor.r().i())&&submerged;
            String key=actor.h().name()+":"+range+":"+waterRequired;
            EngagementGeometry.Field field=fields.get(key);
            if(field==null){field=b.scout.engagementField(actor,target.x,target.y,range,0,waterRequired);fields.put(key,field);}
            EngagementGeometry.Result result=field.from(actor.eo,actor.ep);
            String status=current?result.status:"UNKNOWN";
            out.append("{\"unitId\":").append(actor.eh).append(",\"unitType\":\"").append(escape(actor.r().i()))
                .append("\",\"movementType\":\"").append(TerrainSemantics.movementName(actor.h()))
                .append("\",\"weaponRange\":").append(format(range)).append(",\"compatibility\":\"")
                .append(compatibility)
                .append("\",\"status\":\"").append(status).append("\",\"reason\":\"")
                .append(current?result.reason:"TARGET_NOT_CURRENTLY_VISIBLE").append("\",\"requiresWaterPosition\":").append(waterRequired)
                .append(",\"lastKnownPositionApproachStatus\":\"").append(result.status).append('"')
                .append(",\"approachX\":").append(format(result.x)).append(",\"approachY\":").append(format(result.y))
                .append(",\"distanceTiles\":").append(result.distanceTiles);
            if(current&&submerged&&"amphibiousJet".equals(actor.r().i())&&!actor.ae()){
                // Frozen native b.c Dive=152, Fly=151. Current flight compatibility stays
                // INCOMPATIBLE. Only the proposed future mode uses its frozen 100-unit range;
                // wet goal cells come from legally seen terrain, never hidden map queries.
                Object dive=modeAction(actor,"152");
                EngagementGeometry.Result wet=b.scout.engagementField(actor,target.x,target.y,100,0,true).from(actor.eo,actor.ep);
                out.append(",\"requiredMode\":\"DIVE\",\"modeApproachStatus\":\"").append(dive==null?"UNKNOWN":wet.status)
                    .append("\",\"modeApproachX\":").append(format(wet.x)).append(",\"modeApproachY\":").append(format(wet.y))
                    .append(",\"modeActionId\":").append(dive==null?"null":"\"152\"")
                    .append(",\"modeActionReady\":").append(dive!=null&&Boolean.TRUE.equals(invoke(AVAILABLE,dive,actor))&&Boolean.TRUE.equals(invoke(AFFORDABLE,dive,actor,true)))
                    .append(",\"modePlanSemantics\":\"LEGAL_SEEN_WATER_POSITION_NOT_CURRENT_WEAPON_COMPATIBILITY\"");
            }
            out.append('}');
        }
        return CommandResult.ok(out.append("]}").toString());
    }
    /** No new reflective accessors: same audited own-action reads and native dispatcher as queue. */
    private static Object modeAction(y actor,String id){
        if(!"amphibiousJet".equals(actor.r().i())||!("151".equals(id)||"152".equals(id)))return null;
        for(Object action:actor.N())if(id.equals(actionId(action)))return action;return null;
    }
    private CommandResult unitModes(Map<String,String> q){
        if(!q.keySet().equals(Collections.singleton("unitId")))throw new IllegalArgumentException("required: unitId");
        y actor=own(Long.parseLong(q.get("unitId")));
        if(actor==null||!"amphibiousJet".equals(actor.r().i()))return CommandResult.error(409,"completed own amphibiousJet required");
        StringBuilder out=new StringBuilder("{\"status\":\"observed\",\"sessionId\":\"").append(session)
            .append("\",\"gameTimeMs\":").append(b.engine.by).append(",\"unitId\":").append(actor.eh)
            .append(",\"submergedWeaponAvailable\":").append(actor.ae()).append(",\"actions\":[");boolean first=true;
        for(String id:new String[]{"151","152"}){Object action=modeAction(actor,id);if(action==null)continue;
            if(!first)out.append(',');first=false;
            out.append("{\"actionId\":\"").append(id).append("\",\"mode\":\"").append("152".equals(id)?"DIVE":"FLY")
                .append("\",\"cost\":").append(invoke(COST,action)).append(",\"available\":").append(invoke(AVAILABLE,action,actor))
                .append(",\"affordable\":").append(invoke(AFFORDABLE,action,actor,true)).append('}');}
        return CommandResult.ok(out.append("]}").toString());
    }
    /** A failed domain observation is never silently treated as SURFACE. */
    private static String nativeDomainCompatibility(String domain,Boolean water,boolean current,
                                                    boolean air,boolean sub,boolean surface,boolean land){
        if(!current)return "UNKNOWN";
        if("AIR".equals(domain))return air?"COMPATIBLE":"INCOMPATIBLE";
        if("SUBMERGED".equals(domain))return sub?"COMPATIBLE":"INCOMPATIBLE";
        if(!"SURFACE".equals(domain))return "UNKNOWN";
        if(!surface)return "INCOMPATIBLE";
        if(land)return "COMPATIBLE";
        return water==null?"UNKNOWN":water?"COMPATIBLE":"INCOMPATIBLE";
    }
    private am anyUnit(long id){
        am[] units=am.bE.a();for(int i=0;i<am.bE.size();i++){am u=units[i];
            if(u!=null&&u.eh==id&&!u.ej&&!u.bV&&!u.cW())return u;
        }return null;
    }

    // ---------------------------------------------------------------------------------------------
    // 输出21 §2: diagnostics-only reachability probe. v0 deliberately returns RAW engine results and
    // never a single "reachable/engageable" verdict: the three concepts of 输出21 §3 (targetable /
    // path-to-coordinate / reachable engagement position) must stay separate until the live calibration
    // of §4 has run. Nothing here is used by a decision.
    // ---------------------------------------------------------------------------------------------
    private static final Class<?> MOVE_CLASS=classOrNull("com.corrodinggames.rts.game.units.ao");
    private static final Class<?> GRID_CLASS=classOrNull("com.corrodinggames.rts.gameFramework.k.i");
    private static final Class<?> PATHFINDER_CLASS=classOrNull("com.corrodinggames.rts.gameFramework.k.l");
    private static final Class<?> AQ_CLASS=classOrNull("com.corrodinggames.rts.game.units.aq");
    private static final String[] OUTER_A_GUESS_NAMES={"fromX","fromY","toX","toY","a","b","budget"};
    /**
     * 输出24 §P0: the first live calibration destroyed every unit it probed, so the non-passive groups are
     * opt-in and additionally require an explicitly armed bridge. The arm switch exists so a sandbox session
     * can bisect the side effect; a normal match never sets it. {@code rwagent.reachabilityDiagnostics} is a
     * process-level JVM flag, so no HTTP caller can turn it on.
     */
    private static final boolean diagnosticsArmed=Boolean.parseBoolean(
            System.getProperty("rwagent.reachabilityDiagnostics","false"));

    /** Empty set = the default passive group (A only: reflection field reads, no engine call at all). */
    private static Set<String> requestedGroups(Map<String,String> q){
        Set<String> out=new LinkedHashSet<String>();
        String raw=q.get("groups");
        if(raw==null||raw.trim().isEmpty())return out;
        for(String part:raw.split(",")){
            String name=part.trim().toUpperCase(Locale.ROOT);
            if(name.isEmpty())continue;
            if(name.startsWith("A"))continue; // the passive read group is always on
            if(name.startsWith("B")){out.add("B");continue;}
            if(name.startsWith("C")){out.add("C");continue;}
            if(name.startsWith("D")){out.add("D");continue;}
            if(name.startsWith("E")){out.add("E");continue;}
            throw new IllegalArgumentException("unknown reachability group: "+part.trim());
        }
        return out;
    }
    private static String[] enabledGroupNames(Set<String> groups){
        List<String> names=new ArrayList<String>();
        names.add("A_FIELD_READS");
        if(groups.contains("B"))names.add("B_MOVEMENT_CLASS_AND_PASSABILITY");
        if(groups.contains("C"))names.add("C_OUTER_QUERY");
        if(groups.contains("D"))names.add("D_AQ_A_Y_AM");
        if(groups.contains("E"))names.add("E_AQ_B_Y_AM");
        return names.toArray(new String[0]);
    }

    private static Class<?> classOrNull(String name){try{return Class.forName(name);}catch(Throwable missing){return null;}}
    private static Method rawMethod(Class<?> type,String name,Class<?>... params){
        if(type==null)return null;
        try{return type.getMethod(name,params);}catch(Exception missing){return null;}
    }
    /**
     * 输出28 §七 (+Astra review): live diagnostics may only invoke methods on an EXPLICIT allowlist keyed by
     * owner + name and matched against a REAL parametric descriptor (parameter types + return type) computed
     * from the Method itself. Storing a descriptor as prose, matching on name only, or walking the
     * superclass chain would all leave same-name overloads and subclass overrides over-permitted.
     * Rejecting void/factory stays as the second line of defence.
     */
    private static final Map<String,Map<String,String>> ALLOWED_ENGINE_CALLS=
            new LinkedHashMap<String,Map<String,String>>();
    /** The exact descriptor of the live unit/type accessors, captured by ?trace=allowlist (never guessed). */
    private static final Map<String,String> OBSERVED_DESCRIPTORS=new LinkedHashMap<String,String>();
    static{
        String unit="com.corrodinggames.rts.game.units.am";
        String target="com.corrodinggames.rts.game.units.y";
        String moveClass="com.corrodinggames.rts.game.units.ao";
        String unitType="com.corrodinggames.rts.game.units.as";
        allow(unit,"h","()->"+moveClass,"movement class of the live unit (engine accessor)");
        allow(unit,"r","()->"+unitType,"unit type object (name source)");
        allow(unit,"bI","()->boolean","building flag");
        allow(unit,"cW","()->boolean","construction-complete check");
        allow(target,"r","()->"+unitType,"unit type object (name source, armed units)");
        allow(target,"h","()->"+moveClass,"movement class accessor on the armed-unit class");
    }
    /** owner + name + descriptor -> purpose. Adding an entry is the ONLY way to permit an engine call. */
    private static void allow(String owner,String name,String descriptor,String purpose){
        Map<String,String> entry=ALLOWED_ENGINE_CALLS.get(owner+'#'+name);
        if(entry==null){entry=new LinkedHashMap<String,String>();ALLOWED_ENGINE_CALLS.put(owner+'#'+name,entry);}
        entry.put(descriptor,purpose);
    }
    /** The real parametric descriptor of a method, in the same notation the allowlist uses. */
    private static String parametricDescriptor(Method m){
        StringBuilder s=new StringBuilder("(");
        Class<?>[] params=m.getParameterTypes();
        for(int i=0;i<params.length;i++)s.append(i>0?",":"").append(params[i].getName());
        return s.append(")->").append(m.getReturnType().getName()).toString();
    }
    private static int notAllowedSkipped;
    private static String allowlistKey(Method m){
        return m.getDeclaringClass().getName()+'#'+m.getName();
    }
    /**
     * Astra review (对话29) found a real regression: {@code getMethod("h")} on a concrete unit resolves to the
     * SUBCLASS declaration (e.g. {@code e.j#h}), so an entry keyed only on {@code am#h} never matched and the
     * whole movement-class path silently returned null. Matching therefore walks the concrete class's
     * inheritance chain and accepts the entry of an OWNER THAT WAS AUDITED, while still comparing the full
     * parametric descriptor against the observed one. Superclass walking is restricted to owners already in
     * the allowlist, so it cannot widen the reach to un-audited classes.
     */
    private static boolean isAllowlisted(Method m){
        if(java.lang.reflect.Modifier.isPublic(m.getModifiers())&&!m.isSynthetic()){
            String descriptor=parametricDescriptor(m);
            for(Class<?> owner=m.getDeclaringClass();owner!=null&&owner!=Object.class;owner=owner.getSuperclass()){
                Map<String,String> entry=ALLOWED_ENGINE_CALLS.get(owner.getName()+'#'+m.getName());
                if(entry!=null&&entry.containsKey(descriptor))return true;
            }
        }
        return false;
    }
    /** The allowlist entry that permitted a call, for the payload's provenance fields. */
    private static String allowlistOwner(Method m){
        String descriptor=parametricDescriptor(m);
        for(Class<?> owner=m.getDeclaringClass();owner!=null&&owner!=Object.class;owner=owner.getSuperclass()){
            Map<String,String> entry=ALLOWED_ENGINE_CALLS.get(owner.getName()+'#'+m.getName());
            if(entry!=null&&entry.containsKey(descriptor))return owner.getName();
        }
        return null;
    }
    private static Object rawInvoke(Method m,Object target,Object... args){
        if(m==null)return null;
        // 输出28 §七: allowlist first (owner+name+exact descriptor), then the structural guards.
        if(!isAllowlisted(m)){
            notAllowedSkipped++;
            OBSERVED_DESCRIPTORS.put(allowlistKey(m),parametricDescriptor(m));
            return "SKIPPED_NOT_ALLOWLISTED:"+allowlistKey(m);
        }
        if(!isPureQuery(m)){guardSkip(m);return "SKIPPED_IMPURE_METHOD";}
        try{return m.invoke(target,args);}catch(Throwable error){return new Failed(error);}
    }
    private static int voidSkipped,factorySkipped;
    private static void guardSkip(Method m){
        if(m.getReturnType()==void.class)voidSkipped++;else factorySkipped++;
    }
    /**
     * A pure query returns a value (not void) and is not the known factory accessor. The unit-type factory
     * {@code ar.a()} creates and registers a unit, and {@code am.cj()} is a void mutator whose body is
     * {@code cu = -1}; both are excluded by structure, not by trusting the name.
     */
    private static boolean isPureQuery(Method m){
        if(m.getReturnType()==void.class)return false;
        if(m.getParameterTypes().length==0&&FACTORY_ACCESSOR_NAMES.contains(m.getName()))return false;
        return true;
    }
    /** Field read by name; used where the engine also has a same-named mutating method (输出27 Q1). */
    private static Object fieldValue(Object target,String name){
        for(Class<?> c=target.getClass();c!=null&&c!=Object.class;c=c.getSuperclass()){
            try{
                java.lang.reflect.Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(target);
            }catch(NoSuchFieldException missing){
            }catch(Throwable error){return new Failed(error);}
        }
        return null;
    }
    private static String rootCause(Throwable error){
        Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
        return cause.getClass().getSimpleName()+":"+cause.getMessage();
    }
    /** Call aq.a(y,am) or aq.b(y,am) directly, for the incident-order replay only. */
    private boolean callTwoArg(String name,y attacker,am target){
        for(Method m:twoArgAqCandidates())
            if(name.equals(m.getName()))return Boolean.TRUE.equals(rawInvoke(m,null,attacker,target));
        return false;
    }
    /** Call the outer aq query directly, for the incident-order replay only. */
    private boolean callOuter(Object movement,float ax,float ay,float tx,float ty){
        for(Method m:outerAqCandidates())
            if("a".equals(m.getName()))
                return Boolean.TRUE.equals(rawInvoke(m,null,movement,Float.valueOf(ax),Float.valueOf(ay),
                        Float.valueOf(tx),Float.valueOf(ty),Integer.valueOf(0),Integer.valueOf(0),Integer.valueOf(0)));
        return false;
    }
    /** Marker so a thrown reflection call is reported as a raw outcome instead of aborting the probe. */
    private static final class Failed{final Throwable error;Failed(Throwable error){this.error=error;}
        public String toString(){Throwable c=error;while(c.getCause()!=null)c=c.getCause();
            return "THREW:"+c.getClass().getSimpleName()+":"+c.getMessage();}}

    /**
     * 输出23 §1: the engine's own movement-class accessor. The engine calls {@code y.h()} to obtain the
     * movement class it feeds to aq.a(...), so that method is the primary source; the field scan is only a
     * fallback and is reported separately so the two can never be confused.
     */
    /**
     * 输出26: the fallback scan must NEVER contain a factory-like accessor. `ar.a()` creates and registers a
     * new unit (verified: each call increments the global world registry by 1), so a "read-only" lookup that
     * probed it was inserting a phantom unit into a live match on every sample. Only value accessors remain;
     * if none matches, the movement class stays null and the payload says so.
     */
    private static final String[] MOVEMENT_ACCESSOR_NAMES={"u","k","n","m","e","d","c","b"};
    /** 输出27 Q4: the known factory accessor that must never be called by a diagnostic. */
    private static final Set<String> FACTORY_ACCESSOR_NAMES=new HashSet<String>(Arrays.asList("a"));

    private Object movementObject(am unit){
        Object direct=rawInvoke(rawMethod(unit.getClass(),"h"),unit);
        if(direct!=null&&MOVE_CLASS!=null&&MOVE_CLASS.isInstance(direct))return direct;
        Object type=rawInvoke(rawMethod(am.class,"r"),unit);
        if(type==null)return null;
        // 输出27 Q4: the fallback scan is retained but is NOT enabled by default -- the accessors it would
        // call are not on the allowlist, so every one of them returns SKIPPED_NOT_ALLOWLISTED and the
        // movement class stays null. That is the intended safe behaviour; ?trace=allowlist records the
        // descriptors so a future, deliberately audited extension can allowlist them one by one.
        for(String name:MOVEMENT_ACCESSOR_NAMES){
            Object value=rawInvoke(rawMethod(type.getClass(),name),type);
            if(value!=null&&MOVE_CLASS!=null&&MOVE_CLASS.isInstance(value))return value;
        }
        return null;
    }
    /**
     * 输出26 §2: INCIDENT_EQUIVALENT_PATH replay of the quarantined 0beaf504 candidate. That build had no
     * group switch, resolved the movement class WITHOUT trying y.h() first, and ran every engine call. This
     * mode restores exactly that order so a sandbox arm can compare it against CURRENT_SAFE_PATH. It requires
     * the arm switch, so no live match can reach it.
     */
    private Object incidentMovementObject(am unit){
        Object type=rawInvoke(rawMethod(am.class,"r"),unit);
        if(type==null)return null;
        // the incident build's list ended with the factory accessor "a"; kept here ONLY so the sandbox can
        // reproduce what that path did, never on a live match (requires the diagnostics switch)
        for(String name:new String[]{"u","k","n","m","e","d","c","b","a"}){
            Object value=rawInvoke(rawMethod(type.getClass(),name),type);
            if(value!=null&&MOVE_CLASS!=null&&MOVE_CLASS.isInstance(value))return value;
        }
        return null;
    }
    private static boolean movementFromEngineMethod(am unit){
        Object direct=rawInvoke(rawMethod(unit.getClass(),"h"),unit);
        return direct!=null&&MOVE_CLASS!=null&&MOVE_CLASS.isInstance(direct);
    }
    /** Provenance of the movement class: which audited owner permitted the h() call, or why it failed. */
    private static String movementSource(am unit){
        Method m=rawMethod(unit.getClass(),"h");
        if(m==null)return "NO_H_METHOD";
        String owner=allowlistOwner(m);
        if(owner==null)return "H_NOT_ALLOWLISTED:"+m.getDeclaringClass().getName()+"#h";
        Object value=rawInvoke(m,unit);
        if(value==null||MOVE_CLASS==null||!MOVE_CLASS.isInstance(value))return "H_RETURNED_NON_MOVEMENT_CLASS";
        return "y.h() via "+owner.split("\\.")[owner.split("\\.").length-1]+"#h";
    }
    /**
     * The cold/warm lazy lookup of 输出26 §4, exposed so a sandbox arm can record what each step returns
     * instead of hiding it inside the group result: unit.h() first, then the field scan on the unit type.
     */
    private String movementResolutionTrace(am unit){
        StringBuilder trace=new StringBuilder();
        Object direct=rawInvoke(rawMethod(unit.getClass(),"h"),unit);
        trace.append("h()=").append(rawJson(direct));
        Object type=rawInvoke(rawMethod(am.class,"r"),unit);
        trace.append(",r()=").append(type==null?"null":"\""+escape(String.valueOf(type))+"\"");
        if(type!=null){
            for(String name:MOVEMENT_ACCESSOR_NAMES){
                Object value=rawInvoke(rawMethod(type.getClass(),name),type);
                trace.append(',').append(name).append("()=").append(rawJson(value));
            }
            trace.append(",a()=NEVER_CALLED_FACTORY_ACCESSOR");
        }
        return trace.toString();
    }
    /** Every public method whose parameters are exactly movementClass + four floats + three ints. */
    private static List<Method> outerAqCandidates(){
        List<Method> out=new ArrayList<Method>();
        if(AQ_CLASS==null)return out;
        for(Method m:AQ_CLASS.getMethods()){
            Class<?>[] p=m.getParameterTypes();
            if(p.length!=8||MOVE_CLASS==null||!p[0].equals(MOVE_CLASS))continue;
            if(p[1].getTypeName().equals("float")&&p[2].getTypeName().equals("float")
               &&p[3].getTypeName().equals("float")&&p[4].getTypeName().equals("float")
               &&p[5].getTypeName().equals("int")&&p[6].getTypeName().equals("int")
               &&p[7].getTypeName().equals("int"))out.add(m);
        }
        return out;
    }
    /** The two-argument engine primitives aq.a(y,am) / aq.b(y,am). */
    private static List<Method> twoArgAqCandidates(){
        List<Method> out=new ArrayList<Method>();
        if(AQ_CLASS==null)return out;
        for(Method m:AQ_CLASS.getMethods()){
            Class<?>[] p=m.getParameterTypes();
            if(p.length==2&&!"a".equals(m.getName())&&!"b".equals(m.getName()))continue;
            if(p.length==2&&y.class.isAssignableFrom(p[0]))out.add(m);
        }
        return out;
    }
    private static String descriptor(Method m){
        StringBuilder s=new StringBuilder(m.getName()).append('(');
        Class<?>[] p=m.getParameterTypes();
        for(int i=0;i<p.length;i++)s.append(i>0?",":"").append(p[i].getSimpleName());
        return s.append(")->").append(m.getReturnType().getSimpleName()).toString();
    }

    private String reachability(Map<String,String> q){
        long attackerId, targetId;
        try{attackerId=Long.parseLong(q.get("unitId"));targetId=Long.parseLong(q.get("targetId"));}
        catch(Exception bad){throw new IllegalArgumentException("unitId and targetId must be numeric");}
        y attacker=own(attackerId);
        if(attacker==null)throw new IllegalArgumentException("completed own armed mobile required");
        am target=anyUnit(targetId);
        if(target==null)throw new IllegalArgumentException("target unit not found");
        // 输出24 §P0: group A only by default - reflection field reads, no engine call. Group B calls
        // y.h() and the pathfinder's read accessors (k.l.a(grid,true) was bytecode-verified to WRITE the
        // grid's internal index field), and groups C/D/E call the aq primitives. Every group past A is
        // opt-in and additionally requires an explicitly armed bridge, never a real match.
        Set<String> groups=requestedGroups(q);
        if(!groups.isEmpty()&&!diagnosticsArmed)
            throw new IllegalArgumentException("non-passive reachability groups are disabled during a live match");
        boolean extended=groups.contains("B")||groups.contains("C")||groups.contains("D")||groups.contains("E");
        // 输出27 §3: staged bisect of the request path, so the sandbox can find which stage mutates state
        // instead of guessing. Requires the arm switch; a live match cannot request a partial path.
        String stages=q.get("stages");
        if(stages!=null&&!diagnosticsArmed)
            throw new IllegalArgumentException("staged path requires the diagnostics switch");
        if("resolve".equals(stages)){
            StringBuilder early=new StringBuilder("{\"status\":\"raw\",\"stage\":\"resolve\"");
            early.append(",\"attackerResolved\":").append(attacker!=null);
            early.append(",\"targetResolved\":").append(target!=null);
            early.append(",\"registrySize\":").append(am.bE.size());
            early.append('}');
            return early.toString();
        }
        if("incident".equals(stages)){
            // positions are read here because the incident block runs before the common payload section
            float ax=attacker.eo, ay=attacker.ep, tx=target.eo, ty=target.ep;
            // 输出27 Q4: the true INCIDENT_EQUIVALENT_ENDPOINT_PATH. It runs the quarantined build's steps in
            // the quarantined build's order - resolve, old movementObject(r() then the scan ending in the
            // factory accessor), D, E, C, field payload, B passability - and reports per-step accounting
            // instead of claiming equivalence from a mode name.
            StringBuilder steps=new StringBuilder();
            StringBuilder early=new StringBuilder("{\"status\":\"raw\",\"stage\":\"incident\"");
            List<String> executed=new ArrayList<String>(),skipped=new ArrayList<String>(),threw=new ArrayList<String>();
            executed.add("resolve.own");executed.add("resolve.anyUnit");
            Object oldMovement;
            try{
                oldMovement=incidentMovementObject(attacker);
                executed.add("movementObject.r()");
                executed.add("movementObject.scan(u,k,n,m,e,d,c,b,a)");
            }catch(Throwable error){
                oldMovement=null;threw.add("movementObject:"+rootCause(error));
            }
            steps.append("movement=").append(oldMovement);
            boolean dOk=false,eOk=false,cOk=false,bOk=false;
            try{
                dOk=callTwoArg("a",attacker,target);
                executed.add("D.aq.a(y,am)");
            }catch(Throwable error){threw.add("D:"+rootCause(error));}
            try{
                eOk=callTwoArg("b",attacker,target);
                executed.add("E.aq.b(y,am)");
            }catch(Throwable error){threw.add("E:"+rootCause(error));}
            if(!dOk&&!eOk)skipped.add("D/E returned false or threw");
            try{
                cOk=callOuter(oldMovement,ax,ay,tx,ty);
                executed.add("C.outerQuery");
            }catch(Throwable error){threw.add("C:"+rootCause(error));}
            try{
                hintedFields(attacker);hintedFields(target);
                executed.add("fieldPayload.hintedFields");
            }catch(Throwable error){threw.add("fieldPayload:"+rootCause(error));}
            try{
                Object pf=null;try{pf=b.engine.bU;}catch(Throwable ignored){}
                queryPassable(pf,oldMovement,(int)(ax/20),(int)(ay/20));
                executed.add("B.passability");
            }catch(Throwable error){threw.add("B:"+rootCause(error));}
            early.append(",\"pathMode\":\"INCIDENT_EQUIVALENT_ENDPOINT_PATH\"");
            early.append(",\"executedSteps\":").append(jsonArray(executed.toArray(new String[0])));
            early.append(",\"skippedSteps\":").append(jsonArray(skipped.toArray(new String[0])));
            early.append(",\"threwSteps\":").append(jsonArray(threw.toArray(new String[0])));
            early.append(",\"detail\":\"").append(escape(steps.toString())).append('"');
            early.append(",\"registrySize\":").append(am.bE.size());
            early.append('}');
            return early.toString();
        }
        if("allowlist".equals(stages)){
            // Astra review: capture the REAL parametric descriptors of every method the diagnostic could
            // reach, so the allowlist can be pinned to observed signatures instead of prose. Also runs the
            // movement-class fallback scan explicitly, because h() normally succeeds and skips it.
            StringBuilder early=new StringBuilder("{\"status\":\"raw\",\"stage\":\"allowlist\"");
            early.append(",\"allowlist\":[");
            boolean one=true;
            for(Map.Entry<String,Map<String,String>> entry:ALLOWED_ENGINE_CALLS.entrySet())
                for(Map.Entry<String,String> variant:entry.getValue().entrySet()){
                    if(!one)early.append(',');one=false;
                    early.append("{\"ownerName\":\"").append(escape(entry.getKey()))
                         .append("\",\"descriptor\":\"").append(escape(variant.getKey()))
                         .append("\",\"purpose\":\"").append(escape(variant.getValue())).append('"').append('}');
                }
            early.append(']');
            Object type=rawInvoke(rawMethod(am.class,"r"),attacker);
            early.append(",\"fallbackScan\":[");
            one=true;
            if(type!=null){
                for(String name:MOVEMENT_ACCESSOR_NAMES){
                    Method m=rawMethod(type.getClass(),name);
                    if(!one)early.append(',');one=false;
                    early.append("{\"name\":\"").append(escape(name)).append('"');
                    early.append(",\"declaringClass\":\"").append(m==null?"null":escape(m.getDeclaringClass().getName())).append('"');
                    early.append(",\"descriptor\":\"").append(m==null?"null":escape(parametricDescriptor(m))).append('"');
                    early.append(",\"onAllowlist\":").append(m!=null&&isAllowlisted(m));
                    early.append(",\"invokedAs\":\"").append(m==null?"null":escape(String.valueOf(rawInvoke(m,type)))).append('"');
                    early.append('}');
                }
            }
            early.append(']');
            early.append(",\"typeAccessorDescriptors\":[");
            one=true;
            for(Map.Entry<String,String> observed:OBSERVED_DESCRIPTORS.entrySet()){
                if(!one)early.append(',');one=false;
                early.append("{\"ownerName\":\"").append(escape(observed.getKey()))
                     .append("\",\"descriptor\":\"").append(escape(observed.getValue())).append('"').append('}');
            }
            early.append(']');
            early.append(",\"notAllowedSkipped\":").append(Integer.valueOf(notAllowedSkipped));
            early.append('}');
            return early.toString();
        }
        if("type".equals(stages)){
            StringBuilder early=new StringBuilder("{\"status\":\"raw\",\"stage\":\"type\"");
            early.append(",\"attackerType\":\"").append(escape(attacker.r()==null?"unknown":attacker.r().i())).append('"');
            early.append(",\"targetType\":\"").append(escape(target.r()==null?"unknown":target.r().i())).append('"');
            early.append('}');
            return early.toString();
        }
        if("building".equals(stages)){
            StringBuilder early=new StringBuilder("{\"status\":\"raw\",\"stage\":\"building\"");
            early.append(",\"targetBuilding\":").append(target.bI());
            early.append('}');
            return early.toString();
        }
        if("floats".equals(stages)){
            StringBuilder early=new StringBuilder("{\"status\":\"raw\",\"stage\":\"floats\"");
            early.append(",\"weaponRangeRaw\":").append(rawJson(matchingFields(hintedFields(attacker),"range")));
            early.append('}');
            return early.toString();
        }
        if("radius".equals(stages)){
            StringBuilder early=new StringBuilder("{\"status\":\"raw\",\"stage\":\"radius\"");
            early.append(",\"collisionRadiusRaw\":").append(rawJson(fieldValue(attacker,"cj")));
            early.append('}');
            return early.toString();
        }
        if("dump".equals(stages)){
            StringBuilder early=new StringBuilder("{\"status\":\"raw\",\"stage\":\"dump\"");
            early.append(",\"fullFloatDump\":").append(floatDump(attacker));
            early.append('}');
            return early.toString();
        }

        // Movement class comes from the engine accessor, which is an engine call, so it belongs to group B
        // (输出24 §P0). Without that group the fields stay null rather than being guessed.
        boolean incident="1".equals(q.get("incident"));
        if(incident&&!diagnosticsArmed)
            throw new IllegalArgumentException("incident replay mode requires the diagnostics switch");
        Object movement=groups.contains("B")?(incident?incidentMovementObject(attacker):movementObject(attacker)):null;
        Object targetMovement=groups.contains("B")?(incident?incidentMovementObject(target):movementObject(target)):null;
        float ax=attacker.eo, ay=attacker.ep, tx=target.eo, ty=target.ep;
        int height=b.engine.bL.D;
        int attackerTile=(int)(ay/20)*height+(int)(ax/20);
        int targetTile=(int)(ty/20)*height+(int)(tx/20);

        Object pathfinder=null;
        try{pathfinder=b.engine.bU;}catch(Throwable ignored){}
        if(pathfinder==null)pathfinder=rawInvoke(rawMethod(b.engine.getClass(),"pathfinder"),b.engine);

        StringBuilder j=new StringBuilder("{\"status\":\"raw\",\"sessionId\":\"").append(session).append('"');
        j.append(",\"concept\":\"output21-raw-reachability-v0\"");
        j.append(",\"attackerId\":").append(attackerId);
        j.append(",\"attackerType\":\"").append(escape(attacker.r()==null?"unknown":attacker.r().i())).append('"');
        j.append(",\"attackerMovementClass\":").append(movement==null?"null":"\""+escape(movement.toString())+"\"");
        j.append(",\"attackerMovementClassSource\":\"")
         .append(!groups.contains("B")?"GROUP_B_DISABLED":escape(movementSource(attacker))).append('"');
        j.append(",\"attackerPosition\":[").append(format(ax)).append(',').append(format(ay)).append(']');
        j.append(",\"attackerTile\":").append(attackerTile);
        j.append(",\"targetId\":").append(targetId);
        j.append(",\"targetType\":\"").append(escape(target.r()==null?"unknown":target.r().i())).append('"');
        j.append(",\"targetMovementClass\":").append(targetMovement==null?"null":"\""+escape(targetMovement.toString())+"\"");
        j.append(",\"targetMovementClassSource\":\"")
         .append(!groups.contains("B")?"GROUP_B_DISABLED":escape(movementSource(target))).append('"');
        j.append(",\"targetBuilding\":").append(target.bI());
        j.append(",\"targetPosition\":[").append(format(tx)).append(',').append(format(ty)).append(']');
        j.append(",\"targetTile\":").append(targetTile);
        j.append(",\"tileWidth\":20,\"mapTilesWide\":").append(b.engine.bL.C).append(",\"mapTilesHigh\":").append(height);

        // 输出24 §P0: groups A and B only by default. Groups C (outer query), D (aq.a(y,am)) and E
        // (aq.b(y,am)) are BANNED on live units: the first live calibration showed every probed unit
        // vanishing from the world at full HP ~0.5 game-seconds after the call, so they may only run when
        // the bridge is explicitly armed for a sandbox session (never a real match).
        j.append(",\"groupsEnabled\":").append(jsonArray(enabledGroupNames(groups)));
        j.append(",\"bannedGroups\":").append(jsonArray(new String[]{"C_OUTER_QUERY","D_AQ_A_Y_AM","E_AQ_B_Y_AM"}));
        j.append(",\"liveMatchSafe\":").append(!diagnosticsArmed);

        // engine primitives: only reported when their group is enabled
        j.append(",\"twoArgCandidates\":[");
        List<Method> twoArg=twoArgAqCandidates();
        boolean first=true;
        if(groups.contains("D")||groups.contains("E")){
            for(Method m:twoArg){
                if(!groups.contains("D")&&"a".equals(m.getName()))continue;
                if(!groups.contains("E")&&"b".equals(m.getName()))continue;
                if(!first)j.append(',');first=false;
                j.append("{\"signature\":\"").append(escape(descriptor(m))).append("\",\"rawResult\":")
                 .append(rawJson(rawInvoke(m,null,attacker,target))).append('}');
            }
        }
        j.append(']');
        j.append(",\"engineAqAResult\":").append(groups.contains("D")
                ?rawJson(rawResult(twoArg,"a",attacker,target)):"\"BANNED_GROUP_D_DISABLED\"");
        j.append(",\"engineAqBResult\":").append(groups.contains("E")
                ?rawJson(rawResult(twoArg,"b",attacker,target)):"\"BANNED_GROUP_E_DISABLED\"");

        j.append(",\"outerCandidates\":[");
        List<Method> outer=outerAqCandidates();
        first=true;
        if(groups.contains("C")){
            for(Method m:outer){
                if(!first)j.append(',');first=false;
                Object legacy=rawInvoke(m,null,movement,Float.valueOf(ax),Float.valueOf(ay),
                                        Float.valueOf(tx),Float.valueOf(ty),Integer.valueOf(0),Integer.valueOf(0),Integer.valueOf(0));
                // 输出23 §3: the engine has two live call patterns - mode 0 and mode 3 - and the meaning of
                // the boolean that selects them is not settled, so both are reported as raw results.
                Object mode0=rawInvoke(m,null,movement,Float.valueOf(ax),Float.valueOf(ay),
                                       Float.valueOf(tx),Float.valueOf(ty),Integer.valueOf(80),Integer.valueOf(0),Integer.valueOf(1));
                Object mode3=rawInvoke(m,null,movement,Float.valueOf(ax),Float.valueOf(ay),
                                       Float.valueOf(tx),Float.valueOf(ty),Integer.valueOf(80),Integer.valueOf(3),Integer.valueOf(1));
                j.append("{\"signature\":\"").append(escape(descriptor(m))).append('"')
                 .append(",\"argSemantics\":\"ARG_SEMANTICS_OLD_GUESS\"")
                 .append(",\"legacyGuessArgs\":[0,0,0],\"legacyGuessRawResult\":").append(rawJson(legacy))
                 .append(",\"enginePatternMode0Args\":[80,0,1],\"enginePatternMode0RawResult\":").append(rawJson(mode0))
                 .append(",\"enginePatternMode3Args\":[80,3,1],\"enginePatternMode3RawResult\":").append(rawJson(mode3))
                 .append('}');
            }
        }
        j.append(']');
        Object exactPath=null;
        if(groups.contains("C")){
            for(Method m:outer)if("a".equals(m.getName())){exactPath=rawInvoke(m,null,movement,Float.valueOf(ax),Float.valueOf(ay),
                    Float.valueOf(tx),Float.valueOf(ty),Integer.valueOf(0),Integer.valueOf(0),Integer.valueOf(0));break;}
        }
        j.append(",\"exactTargetPathRawResult\":").append(groups.contains("C")
                ?rawJson(exactPath):"\"BANNED_GROUP_C_DISABLED\"");
        j.append(",\"outerArgGuessNames\":").append(jsonArray(OUTER_A_GUESS_NAMES));

        // raw weapon / geometry fields, no interpretation (输出21 §6, 输出23 §4-§5)
        List<Object[]> attackerFields=hintedFields(attacker);
        List<Object[]> targetFields=hintedFields(target);
        j.append(",\"weaponRangeRaw\":").append(rawJson(matchingFields(attackerFields,"range")));
        // 输出27 Q1: `am.cj` is a FLOAT FIELD, but am also declares `public void cj()` whose entire body is
        // `cu = -1` (verified bytecode). A name-based rawMethod() lookup therefore resolved to that mutator and
        // the diagnostic was killing the very units it measured. The value is now read as a FIELD, never a
        // method, and the payload records which route produced it.
        j.append(",\"collisionRadiusRaw\":{\"field\":\"cj\",\"readVia\":\"Field.get\"")
         .append(",\"attacker\":").append(rawJson(fieldValue(attacker,"cj")))
         .append(",\"target\":").append(rawJson(fieldValue(target,"cj"))).append('}');
        j.append(",\"targetRadiusRaw\":").append(rawJson(matchingFields(targetFields,"radius")));
        j.append(",\"targetFootprintRaw\":").append(rawJson(matchingFields(targetFields,"footprint")));
        if("full".equals(q.get("dump")))
            j.append(",\"fullFloatDump\":{\"attacker\":").append(floatDump(attacker))
             .append(",\"target\":").append(floatDump(target)).append('}');

        // passability samples around both positions (group B: they read the pathfinder, and the accessor
        // k.l.a(grid,true) was bytecode-verified to write the grid's internal index field)
        j.append(",\"movementGridClass\":").append(GRID_CLASS==null?"null":"\""+escape(GRID_CLASS.getName())+"\"");
        j.append(",\"rawPassabilitySamples\":[");
        first=true;
        int[][] offsets={{0,0},{1,0},{-1,0},{0,1},{0,-1},{2,0},{-2,0},{0,2},{0,-2}};
        if(groups.contains("B")){
            for(String where:new String[]{"attacker","target"}){
                int baseX=where.equals("attacker")?(int)(ax/20):(int)(tx/20);
                int baseY=where.equals("attacker")?(int)(ay/20):(int)(ty/20);
                for(int[] offset:offsets){
                    int tileX=baseX+offset[0], tileY=baseY+offset[1];
                    if(!first)j.append(',');first=false;
                    j.append("{\"where\":\"").append(where).append("\",\"tileX\":").append(tileX).append(",\"tileY\":").append(tileY)
                     .append(",\"globalPassable\":").append(rawJson(queryPassable(pathfinder,null,tileX,tileY)))
                     .append(",\"classPassable\":").append(rawJson(queryPassable(pathfinder,movement,tileX,tileY)))
                     .append(",\"gridMoveCost\":").append(rawJson(queryMoveCost(pathfinder,movement,tileX,tileY)))
                     .append('}');
                }
            }
        }
        // Astra contract gap (对话29 §5): notAllowedSkipped existed only on the stages=allowlist response, so a
        // reader of the default payload could not tell how many reflection calls the allowlist rejected on the
        // very path being sampled. It is now part of the main payload, together with the rejected signatures,
        // and it is serialized LAST: an earlier position reported the counts as they stood before the
        // passability samples below had been refused, i.e. the summary contradicted its own response.
        j.append(']');
        j.append(",\"reflectionGuards\":{\"voidMethodsSkipped\":").append(Integer.valueOf(voidSkipped))
         .append(",\"factoryAccessorsSkipped\":").append(Integer.valueOf(factorySkipped))
         .append(",\"notAllowedSkipped\":").append(Integer.valueOf(notAllowedSkipped))
         // counters are process-lifetime (never reset per request); the label says so rather than implying
         // "rejected during this one sample". Full owner#name -> descriptor map is on stages=allowlist.
         .append(",\"counterScope\":\"session_cumulative_process_lifetime\"")
         .append(",\"notAllowedSignatures\":")
         .append(jsonArray(OBSERVED_DESCRIPTORS.values().toArray(new String[0])))
         .append('}');
        j.append(",\"diagnosticStatus\":\"RAW_ONLY_NOT_A_VERDICT\"");
        if(incident)j.append(",\"pathMode\":\"INCIDENT_EQUIVALENT_PATH\"");
        else if(extended)j.append(",\"pathMode\":\"EXTENDED_OPT_IN_PATH\"");
        else j.append(",\"pathMode\":\"CURRENT_SAFE_PATH\"");
        j.append(",\"movementResolutionTrace\":\"").append(escape(groups.contains("B")
                ?movementResolutionTrace(attacker):"GROUP_B_DISABLED")).append('"');
        j.append('}');
        return j.toString();
    }
    private static Object rawResult(List<Method> candidates,String name,Object... args){
        for(Method m:candidates)if(m.getName().equals(name))return rawInvoke(m,null,args);
        return null;
    }
    private Object queryPassable(Object pathfinder,Object movement,int tileX,int tileY){
        if(pathfinder==null)return null;
        try{
            Method byClass=rawMethod(PATHFINDER_CLASS,"a",MOVE_CLASS,int.class,int.class);
            if(byClass!=null&&movement!=null)return rawInvoke(byClass,pathfinder,movement,tileX,tileY);
            Method global=rawMethod(PATHFINDER_CLASS,"a",int.class,int.class);
            if(global!=null)return rawInvoke(global,pathfinder,tileX,tileY);
        }catch(Throwable error){return new Failed(error);}
        return null;
    }
    private Object queryMoveCost(Object pathfinder,Object movement,int tileX,int tileY){
        if(pathfinder==null||GRID_CLASS==null)return null;
        try{
            Object grid=null;
            Method gridOf=rawMethod(PATHFINDER_CLASS,"a",MOVE_CLASS);
            if(gridOf!=null&&movement!=null)grid=rawInvoke(gridOf,pathfinder,movement);
            if(grid==null){
                Method unitGrid=rawMethod(PATHFINDER_CLASS,"a",am.class);
                if(unitGrid!=null)grid=null;
            }
            if(grid==null)return null;
            Method cost=rawMethod(PATHFINDER_CLASS,"b",GRID_CLASS,int.class,int.class);
            return cost==null?null:rawInvoke(cost,pathfinder,grid,tileX,tileY);
        }catch(Throwable error){return new Failed(error);}
    }
    /** Numeric instance fields whose name hints at the requested concept; raw only, never interpreted. */
    private static List<Object[]> hintedFields(Object unit){
        List<Object[]> out=new ArrayList<Object[]>();
        for(Class<?> c=unit.getClass();c!=null&&c!=Object.class&&!c.getName().startsWith("java.");c=c.getSuperclass()){
            for(java.lang.reflect.Field f:c.getDeclaredFields()){
                String type=f.getType().getName();
                if(!(type.equals("float")||type.equals("int")||type.equals("short")))continue;
                try{
                    f.setAccessible(true);
                    out.add(new Object[]{c.getSimpleName()+"."+f.getName(),f.get(unit)});
                }catch(Throwable ignored){}
            }
        }
        return out;
    }
    private static List<Object[]> matchingFields(List<Object[]> fields,String hint){
        List<Object[]> out=new ArrayList<Object[]>();
        for(Object[] entry:fields)if(((String)entry[0]).toLowerCase().contains(hint))out.add(entry);
        return out;
    }
    /**
     * 输出23 §6: every float field of the live unit plus its unit-type metadata, dumped once per combat
     * type on its first calibration sample. Values only - no field is named a range here.
     */
    private static String floatDump(Object unit){
        StringBuilder j=new StringBuilder("{\"instance\":{");
        List<Object[]> all=hintedFields(unit);
        boolean first=true;
        for(Object[] entry:all){
            Object value=entry[1];
            if(!(value instanceof Float)&&!(value instanceof Double))continue;
            if(!first)j.append(',');first=false;
            j.append('"').append(escape((String)entry[0])).append("\":").append(format(((Number)value).doubleValue()));
        }
        Object type=rawInvoke(rawMethod(am.class,"r"),unit);
        j.append("},\"metadata\":{");
        first=true;
        if(type!=null){
            List<Object[]> meta=hintedFields(type);
            for(Object[] entry:meta){
                Object value=entry[1];
                if(!(value instanceof Float)&&!(value instanceof Double))continue;
                if(!first)j.append(',');first=false;
                j.append('"').append(escape((String)entry[0])).append("\":").append(format(((Number)value).doubleValue()));
            }
        }
        return j.append("}}").toString();
    }
    private static String rawJson(Object value){
        if(value==null)return "null";
        if(value instanceof Failed)return "\""+escape(value.toString())+"\"";
        if(value instanceof Number||value instanceof Boolean)return String.valueOf(value);
        return "\""+escape(String.valueOf(value))+"\"";
    }
    private static String jsonArray(String[] values){
        StringBuilder s=new StringBuilder("[");
        for(int i=0;i<values.length;i++){
            if(i>0)s.append(',');
            s.append('"').append(escape(values[i])).append('"');
        }
        return s.append(']').toString();
    }
    private boolean allowed(y u,Object a){return PRODUCE.isInstance(a) || invoke(ACTION_ID,a).equals(invoke(UPGRADE,u));}
    private String production(){
        StringBuilder j=new StringBuilder("{\"status\":\"observed\",\"sessionId\":\"").append(session).append("\",\"factories\":[");boolean first=true;
        am[] units=am.bE.a();for(int i=0;i<am.bE.size();i++){am unit=units[i];
            if(unit==null||unit.bX!=b.engine.bs||unit.ej||unit.bV||unit.cm<1||!(unit instanceof y)||!"landFactory".equals(unit.r().i()))continue;
            y u=(y)unit;if(!first)j.append(',');first=false;
            j.append("{\"id\":").append(u.eh).append(",\"tier\":").append(u.V()).append(",\"queue\":").append(EconomyBridge.queueCount(u)).append(",\"actions\":[");boolean one=true;
            for(Object item:u.N()){Object a=item;if(!allowed(u,a)||!((Boolean)invoke(AVAILABLE,a,u)))continue;
                if(!one)j.append(',');one=false;
                j.append("{\"actionId\":\"").append(escape(actionId(a))).append("\",\"type\":\"").append(escape(product(a)))
                    .append("\",\"cost\":").append(invoke(COST,a)).append(",\"affordable\":").append(invoke(AFFORDABLE,a,u,true)).append('}');
            }j.append("]}");
        }return j.append("]}").toString();
    }
    /**
     * Read-only capability snapshot (输出9 Q1 / 输出10 §13): the air/ground flags of a representative
     * instance per unit type. Custom units carry their flags in an ini file, but built-ins such as
     * heavyTank have no ini at all, so the only honest source is the fully initialised live object.
     * Enemy types are only listed when they are currently visible, exactly like the observation path.
     */
    private String capabilities(Map<String,String> q){
        Set<String> wanted=new LinkedHashSet<String>();
        String types=q.get("types");
        if(types!=null&&!types.isEmpty()){for(String t:types.split(","))if(!t.trim().isEmpty())wanted.add(t.trim());}
        if(wanted.size()>16)throw new IllegalArgumentException("at most 16 types");
        Map<String,am> found=new LinkedHashMap<String,am>();
        Map<String,String> side=new LinkedHashMap<String,String>();
        am[] units=am.bE.a();
        for(int i=0;i<am.bE.size();i++){
            am u=units[i];if(u==null||u.bX==null||u.ej||u.cW())continue;
            boolean own=u.bX==b.engine.bs;
            if(!own){
                // No enemy attribute is exported before both native visibility checks pass.
                if(!b.engine.bs.c(u.bX)||!u.d(b.engine.bs)||!b.engine.bL.a(u.eo,u.ep,b.engine.bs))continue;
            }
            String name=u.r()==null?"unknown":u.r().i();
            if(!wanted.isEmpty()&&!wanted.contains(name))continue;
            if(found.containsKey(name))continue;
            if(!own&&found.size()>=16)continue;
            found.put(name,u);side.put(name,own?"own":"visibleEnemy");
        }
        StringBuilder j=new StringBuilder("{\"status\":\"observed\",\"sessionId\":\"").append(session).append("\",\"units\":[");
        boolean first=true;
        for(Map.Entry<String,am> entry:found.entrySet()){
            am u=entry.getValue();
            if(!first)j.append(',');first=false;
            j.append("{\"type\":\"").append(escape(entry.getKey())).append("\",\"side\":\"").append(side.get(entry.getKey()))
                .append("\",\"price\":").append(price(u.r())).append(",\"booleans\":{");
            boolean one=true;
            for(Map.Entry<String,Boolean> f:booleans(u).entrySet()){
                if(!one)j.append(',');one=false;
                j.append('"').append(f.getKey()).append("\":").append(f.getValue());
            }
            j.append("}}");
        }
        j.append("],\"unavailable\":[");
        boolean none=true;
        for(String name:wanted)if(!found.containsKey(name)){if(!none)j.append(',');none=false;j.append('"').append(escape(name)).append('"');}
        return j.append("]}").toString();
    }
    private static long price(as type){
        if(type==null)return -1;
        try{
            Object shared=invoke(method(type.getClass(),"u"),type);
            if(shared==null)return -1;
            java.lang.reflect.Field f=shared.getClass().getDeclaredField("b");f.setAccessible(true);
            Object v=f.get(shared);return v instanceof Number?((Number)v).longValue():-1;
        }catch(Exception error){return -1;}
    }
    /** Boolean fields of the live instance, including inherited ones but never JDK internals. */
    private static Map<String,Boolean> booleans(Object target){
        Map<String,Boolean> out=new LinkedHashMap<String,Boolean>();
        for(Class<?> c=target.getClass();c!=null&&c!=Object.class;c=c.getSuperclass()){
            if(c.getName().startsWith("java."))break;
            for(java.lang.reflect.Field f:c.getDeclaredFields()){
                if(f.getType()!=boolean.class&&f.getType()!=Boolean.class)continue;
                if(java.lang.reflect.Modifier.isStatic(f.getModifiers()))continue;
                if(out.size()>=80)return out;
                try{f.setAccessible(true);out.put(c.getSimpleName()+"."+f.getName(),(Boolean)f.get(target));}
                catch(Throwable ignored){}
            }
        }
        return out;
    }
    private String observe(){
        List<Enemy> visible=new ArrayList<Enemy>();Set<Long> ids=new HashSet<Long>();am[] units=am.bE.a();
        for(int i=0;i<am.bE.size();i++){am u=units[i];
            if(u==null||u.bX==null||u.bX==b.engine.bs||!b.engine.bs.c(u.bX))continue;
            // No enemy attributes are exported until both native visibility checks pass.
            if(!u.d(b.engine.bs)||!b.engine.bL.a(u.eo,u.ep,b.engine.bs))continue;
            if(u.ej||u.bV||u.cW())continue;
            Enemy t=new Enemy(u,b.engine.by);visible.add(t);memory.put(t.id,t);ids.add(t.id);
        }
        Iterator<Enemy> it=memory.values().iterator();while(it.hasNext()){
            Enemy t=it.next();if(!ids.contains(t.id)&&(b.engine.bL.a(t.x,t.y,b.engine.bs)||(!t.building&&b.engine.by-t.time>20000)))it.remove();
        }
        for(Enemy t:visible){
            EnemyIntel contact=enemyIntel.get(t.id);
            if(contact==null){contact=new EnemyIntel(t);enemyIntel.put(t.id,contact);}
            else {contact.last=t;contact.clearedAt=-1;}
        }
        for(EnemyIntel contact:enemyIntel.values()){
            contact.visible=ids.contains(contact.last.id);
            contact.lastKnownSiteVisible=contact.visible;
            if(!contact.visible&&contact.last.building){
                // The complete old-site neighborhood must be visible to clear it. Export the same
                // predicate to the policy so a partly visible site remains eligible for recheck.
                contact.lastKnownSiteVisible=formerSiteVisible(contact.last,1);
                if(contact.clearedAt<0&&contact.lastKnownSiteVisible)
                    contact.clearedAt=b.engine.by;
            }
        }
        // Retain every currently visible contact. Bound only lost contacts; otherwise a crowded
        // visible frame would repeatedly evict and re-add the same IDs on every observation.
        while(enemyIntel.size()>MAX_ENEMY_INTEL){
            Long oldest=null;int oldestTime=Integer.MAX_VALUE;
            for(Map.Entry<Long,EnemyIntel> entry:enemyIntel.entrySet()){
                EnemyIntel contact=entry.getValue();
                if(contact.visible)continue;
                if(contact.last.time<oldestTime){oldest=entry.getKey();oldestTime=contact.last.time;}
            }
            if(oldest==null)break;
            enemyIntel.remove(oldest);enemyIntelEvicted++;
        }
        StringBuilder j=new StringBuilder("{\"status\":\"observed\",\"sessionId\":\"").append(session).append("\",\"frame\":").append(b.engine.bx)
            .append(",\"gameTimeMs\":").append(b.engine.by).append(",\"match\":").append(matchJson(b))
            .append(",\"catalogSha256\":").append(TargetCatalog.catalogSha256()==null?"null":"\""+TargetCatalog.catalogSha256()+"\"")
            .append(",\"catalogGameJarMatched\":").append(TargetCatalog.GAME_SHA256.equals(b.gameJarSha256()))
            .append(",\"visibleEnemies\":[");boolean first=true;
        for(Enemy t:visible){if(!first)j.append(',');first=false;j.append(t.json());}j.append("],\"rememberedEnemies\":[");first=true;
        for(Enemy t:memory.values()){if(!first)j.append(',');first=false;j.append(t.json());}
        j.append("],\"enemyIntel\":[");first=true;int intelVisible=0,intelLost=0,intelCleared=0;
        for(EnemyIntel contact:enemyIntel.values()){
            if(!first)j.append(',');first=false;j.append(contact.json());
            if(contact.visible)intelVisible++;else if(contact.clearedAt>=0)intelCleared++;else intelLost++;
        }
        return j.append("],\"enemyIntelVisible\":").append(intelVisible)
            .append(",\"enemyIntelLostContact\":").append(intelLost)
            .append(",\"enemyIntelCleared\":").append(intelCleared)
            .append(",\"enemyIntelEvicted\":").append(enemyIntelEvicted).append('}').toString();
    }
    private boolean formerSiteVisible(Enemy last,int radius){
        int width=b.engine.bL.C,height=b.engine.bL.D,tw=b.engine.bL.n,th=b.engine.bL.o;
        if(width<=0||height<=0||tw<=0||th<=0)return false;
        int col=(int)Math.floor(last.x/tw),row=(int)Math.floor(last.y/th);
        if(col<0||col>=width||row<0||row>=height)return false;
        for(int dx=-radius;dx<=radius;dx++)for(int dy=-radius;dy<=radius;dy++){
            int x=col+dx,y=row+dy;
            if(x<0||x>=width||y<0||y>=height)continue;
            if(!b.engine.bL.a((x+.5f)*tw,(y+.5f)*th,b.engine.bs))return false;
        }
        return true;
    }
    private static final class Receipt{final String fingerprint;final CommandResult result;Receipt(String f,CommandResult r){fingerprint=f;result=r;}}
    private static final class Enemy{
        final long id;final float x,y,hp,radius;final boolean building,armed;final String type,domain;
        final Boolean touchingWater;final int time;
        Enemy(am u,int time){
            id=u.eh;x=u.eo;y=u.ep;hp=u.cu;radius=u.cj;building=u.bI();armed=u.l();type=u.r().i();this.time=time;
            String observedDomain="UNKNOWN";Boolean water=null;
            // This constructor is called only after both legal native visibility checks in observe().
            // Never call these dynamic accessors again when the contact is lost in fog.
            try {
                observedDomain=u.i()?"AIR":u.Q()?"SUBMERGED":"SURFACE";
                if("SURFACE".equals(observedDomain))water=Boolean.valueOf(u.cH());
            } catch (Throwable unavailable) { observedDomain="UNKNOWN";water=null; }
            domain=observedDomain;touchingWater=water;
        }
        String json(){return "{\"id\":"+id+",\"x\":"+format(x)+",\"y\":"+format(y)+",\"hp\":"+format(hp)+",\"building\":"+building
            +",\"canAttack\":"+armed+",\"type\":\""+escape(type)+"\",\"lastSeenGameTimeMs\":"+time
            +",\"targetDomain\":\""+domain+"\",\"touchingWater\":"+(touchingWater==null?"null":touchingWater.toString())
            +",\"domainObservedAtGameTimeMs\":"+time+",\"domainSourceId\":\"native:am.i/Q/cH_after_legal_visibility\"}";}
    }
    private static final class EnemyIntel{
        final int firstSeenTime;
        Enemy last;
        boolean visible,lastKnownSiteVisible;
        int clearedAt=-1;
        EnemyIntel(Enemy first){firstSeenTime=first.time;last=first;}
        String json(){return "{\"id\":"+last.id+",\"lastKnownType\":\""+escape(last.type)
            +"\",\"status\":\""+(visible?"VISIBLE":clearedAt>=0?"CLEARED":"LOST_CONTACT")
            +"\",\"firstSeenGameTimeMs\":"+firstSeenTime+",\"lastSeenGameTimeMs\":"+last.time
            +",\"lastKnownX\":"+format(last.x)+",\"lastKnownY\":"+format(last.y)
            +",\"lastKnownHp\":"+format(last.hp)+",\"lastKnownBuilding\":"+last.building
            +",\"lastKnownCanAttack\":"+last.armed+",\"lastKnownSiteVisible\":"+lastKnownSiteVisible
            +",\"clearedGameTimeMs\":"+(clearedAt>=0?Integer.toString(clearedAt):"null")+'}';}
    }
}
