package io.rwagent.bootstrap;

import android.graphics.Rect;
import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.as;
import com.corrodinggames.rts.game.units.ar;
import com.corrodinggames.rts.game.units.y;
import com.corrodinggames.rts.gameFramework.e;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.Callable;
import static io.rwagent.bootstrap.RuntimeBridge.*;

/** Fixed native landFactory -> tank proof; reflection only handles obfuscated package/class collisions. */
final class EconomyBridge {
    private final RuntimeBridge bridge;
    private final Map<String, Receipt> receipts = new LinkedHashMap<String, Receipt>();
    private String receiptSession;
    static final Class<?> ACTION = cls("com.corrodinggames.rts.game.units.a.s");
    private static final Class<?> BUILD = cls("com.corrodinggames.rts.game.units.a.v");
    static final Class<?> PRODUCE = cls("com.corrodinggames.rts.game.units.a.w");
    private static final Class<?> FACTORY = cls("com.corrodinggames.rts.game.units.d.l");
    static final Method TYPE = method(ACTION,"i"), ACTION_ID = method(ACTION,"N"), TIER = method(ACTION,"t");
    static final Method AVAILABLE = method(ACTION,"b",am.class), AFFORDABLE = method(ACTION,"a",am.class,boolean.class);
    static final Method COST = method(ACTION,"c"), QUEUE = method(FACTORY,"f",boolean.class);
    private static final Class<?> CUSTOM = cls("com.corrodinggames.rts.game.units.custom.l");
    static final Method REPLACEMENT = method(CUSTOM,"c",as.class), CUSTOM_PREVIEW=method(CUSTOM,"a",boolean.class);
    static final Method SET_ACTION = method(e.class,"a",cls("com.corrodinggames.rts.game.units.a.c"));

    EconomyBridge(RuntimeBridge bridge) { this.bridge=bridge; }
    void install(HttpServer server) {
        install(server,"/economy/preflight","GET");
        install(server,"/economy/plan","GET");
        install(server,"/economy/production-plan","GET");
        install(server,"/opening/plan","GET");
        install(server,"/expansion/plan","GET");
        install(server,"/economy/builder-production","GET");
        install(server,"/economy/builder-actions","GET");
        install(server,"/economy/investments","GET");
        install(server,"/economy/construction-plan","GET");
        install(server,"/command/invest","POST");
        install(server,"/command/construct","POST");
        install(server,"/command/build-extractor","POST");
        install(server,"/command/build-factory","POST");
        install(server,"/command/produce-builder","POST");
        install(server,"/command/produce-tank","POST");
    }
    private void install(HttpServer server, final String path, final String verb) {
        server.createContext(path, exchange -> {
            if (!path.equals(exchange.getRequestURI().getPath())) { respond(exchange,404,jsonError("unknown endpoint"));return; }
            if (!verb.equals(exchange.getRequestMethod())) { respond(exchange,405,jsonError(verb+" required"));return; }
            if (exchange.getRequestHeaders().getFirst("Origin")!=null) { respond(exchange,403,jsonError("browser-origin requests disabled"));return; }
            try {
                final Map<String,String> q=query(exchange.getRequestURI().getRawQuery());
                CommandResult result=bridge.onGameThread(new Callable<CommandResult>() {
                    public CommandResult call() { return dispatch(path,q); }
                });
                respond(exchange,result.httpStatus,result.json);
            } catch (IllegalArgumentException ex) { respond(exchange,400,jsonError(ex.getMessage())); }
            catch (Exception ex) { RwAgent.log("Economy request failed",ex);respond(exchange,503,jsonError("economy result unknown; observe before issuing another command")); }
        });
    }
    private CommandResult dispatch(String path, Map<String,String> q) {
        bridge.refreshSession();
        if(path.equals("/economy/preflight")) {
            if(!q.isEmpty())return CommandResult.error(400,"preflight accepts no query fields");
            return preflight();
        }
        CommandResult guard=bridge.commandGuard();if(guard!=null)return guard;
        try {
            if(path.equals("/economy/investments")){
                requireKeys(q,new String[0]);return investments();
            }
            if(path.equals("/economy/construction-plan")){
                requireKeys(q,new String[]{"unitId","type"});return constructionPlan(Long.parseLong(q.get("unitId")),q.get("type"));
            }
            if(path.equals("/command/invest")||path.equals("/command/construct"))return strategicCommand(path,q);
            if(path.equals("/economy/builder-production")) {
                if(!q.isEmpty())throw new IllegalArgumentException("builder-production accepts no query fields");
                return builderProduction();
            }
            if(path.equals("/economy/builder-actions")) {
                requireKeys(q,new String[]{"unitId"});
                return builderActions(Long.parseLong(q.get("unitId")));
            }
            if (path.equals("/economy/plan") || path.equals("/opening/plan") || path.equals("/expansion/plan") || path.equals("/economy/production-plan")) {
                if (!q.isEmpty() && !(q.size()==1 && q.containsKey("unitId"))) throw new IllegalArgumentException("optional field: unitId");
                Long id=q.isEmpty()?null:Long.valueOf(q.get("unitId"));
                if(path.equals("/economy/production-plan"))return productionPlan(id);
                return path.equals("/economy/plan")?plan(id):openingPlan(id,path.equals("/expansion/plan"));
            }
            boolean extractor=path.equals("/command/build-extractor");
            boolean build=extractor || path.equals("/command/build-factory");
            boolean builderOrder=path.equals("/command/produce-builder");
            requireKeys(q,build?new String[]{"unitId","x","y","sessionId","requestId"}:new String[]{"unitId","sessionId","requestId"});
            long id=Long.parseLong(q.get("unitId"));
            String request=q.get("requestId");
            if(!request.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("invalid requestId");
            if(!bridge.sessionId.equals(q.get("sessionId")))return CommandResult.error(409,"session changed; read state again");
            if(!bridge.sessionId.equals(receiptSession)) { receipts.clear();receiptSession=bridge.sessionId; }
            String fingerprint=path+new TreeMap<String,String>(q).toString();
            Receipt old=receipts.get(request);
            if(old!=null)return old.fingerprint.equals(fingerprint)?old.result:CommandResult.error(409,"requestId reused with different economy command");
            y unit=ownUnit(id);
            if(unit==null)return CommandResult.error(409,"completed controllable own unit not found");
            String extra;
            if(builderOrder) {
                Object action=builderAction(unit);
                if(action==null)return CommandResult.error(409,"unit has no native builder production action");
                if(!usable(action,unit))return CommandResult.error(409,"builder action unavailable, locked, or insufficient credits");
                if(!FACTORY.isInstance(unit))return CommandResult.error(409,"builder production requires a native factory queue");
                if(allBuildersQueued()>0)return CommandResult.error(409,"a builder is already in production");
                if(queueCount(unit)!=0)return CommandResult.error(409,"producer queue must be empty before builder production");
                if(countBuilders()>0)return CommandResult.error(409,"a builder already exists");
                as type=(as)invoke(TYPE,action);
                e command=bridge.engine.cf.b(bridge.engine.bs);command.a(unit);invoke(SET_ACTION,command,invoke(ACTION_ID,action));
                extra=",\"type\":\""+escape(type.i())+"\",\"actionClass\":\""+escape(action.getClass().getSimpleName())
                    +"\",\"builderCost\":"+invoke(COST,action);
            } else {
            Object action=extractor?buildAction(unit,ar.valueOf("extractor")):action(unit,build);
            if(action==null)return CommandResult.error(409,build?"unit has no requested native building action":"unit has no native tank production action");
            if(!usable(action,unit))return CommandResult.error(409,"action unavailable, locked, or insufficient credits");
            as type=(as)invoke(TYPE,action);
            if(build) {
                float x=coordinate(q.get("x")),yy=coordinate(q.get("y"));
                Site site=site(unit,type,x,yy,extractor?600:240);
                if(site==null)return CommandResult.error(409,"no visible legal building footprint at target or target beyond allowed builder range");
                e command=bridge.engine.cf.b(bridge.engine.bs);
                command.a(unit);command.a(site.x,site.y,type,((Number)invoke(TIER,action)).intValue());
                extra=",\"targetX\":"+format(site.x)+",\"targetY\":"+format(site.y)+",\"type\":\""+escape(type.i())+"\"";
            } else {
                if(unit.r()!=resolved(ar.b))return CommandResult.error(409,"this proof requires the current landFactory type");
                if(queueCount(unit)!=0)return CommandResult.error(409,"factory production queue must be empty");
                e command=bridge.engine.cf.b(bridge.engine.bs);command.a(unit);invoke(SET_ACTION,command,invoke(ACTION_ID,action));
                extra=",\"type\":\""+escape(type.i())+"\"";
            }
            }
            CommandResult result=CommandResult.ok("{\"status\":\"queued\",\"requestId\":\""+request+"\",\"sessionId\":\""+bridge.sessionId+"\",\"unitId\":"+id+",\"frame\":"+bridge.engine.bx+extra+"}");
            receipts.put(request,new Receipt(fingerprint,result));if(receipts.size()>256)receipts.remove(receipts.keySet().iterator().next());
            RwAgent.log("Queued economy: "+path+" unit="+id+" request="+request+extra);
            return result;
        } catch(IllegalArgumentException ex) {return CommandResult.error(400,ex.getMessage());}
    }
    private CommandResult plan(Long id) {
        y selected=null;Object buildAction=null;
        am[] live=am.bE.a();
        for(int index=0;index<am.bE.size();index++) {
            am unit=live[index];
            if(unit==null || (id!=null && unit.eh!=id.longValue()))continue;
            y candidate=ownUnit(unit.eh);if(candidate==null)continue;
            Object a=action(candidate,true);
            if(a!=null && usable(a,candidate)) { selected=candidate;buildAction=a;break; }
        }
        if(selected==null)return CommandResult.error(409,"no completed own builder with affordable native landFactory action; prepare a standard builder and sufficient credits");
        as type=(as)invoke(TYPE,buildAction);
        // Preflight that this factory will expose tank production, before spending on construction.
        am preview=preview(type);preview.bX=bridge.engine.bs;
        Object produceAction=action(preview,false);
        if(produceAction==null)return CommandResult.error(409,"landFactory has no resolved tank production action; expected="+tankType().i()+"; menu="+menu(preview));
        int factoryCost=((Number)invoke(COST,buildAction)).intValue();
        int tankCost=((Number)invoke(COST,produceAction)).intValue();
        if(bridge.engine.bs.o < factoryCost+tankCost)return CommandResult.error(409,"insufficient credits for factory plus one tank: need "+(factoryCost+tankCost));
        Site chosen=null;
        for(int radius:new int[]{100,140,180,220}) {
            for(int d=0;d<8;d++) {
                double angle=d*Math.PI/4;
                chosen=site(selected,type,(float)(selected.eo+radius*Math.cos(angle)),(float)(selected.ep+radius*Math.sin(angle)));
                if(chosen!=null)break;
            }
            if(chosen!=null)break;
        }
        if(chosen==null)return CommandResult.error(409,"no nearby visible legal landFactory site; move builder to open land");
        return CommandResult.ok("{\"status\":\"planned\",\"sessionId\":\""+bridge.sessionId+"\",\"builderId\":"+selected.eh
                +",\"factoryType\":\""+escape(type.i())+"\",\"productType\":\""+escape(((as)invoke(TYPE,produceAction)).i())+"\",\"targetX\":"+format(chosen.x)+",\"targetY\":"+format(chosen.y)
                +",\"factoryCost\":"+factoryCost+",\"tankCost\":"+tankCost+",\"credits\":"+format(bridge.engine.bs.o)+"}");
    }
    private CommandResult productionPlan(Long id) {
        am[] units=am.bE.a();
        for(int i=0;i<am.bE.size();i++) {
            am u=units[i];if(u==null || (id!=null && u.eh!=id.longValue()))continue;
            y factory=ownUnit(u.eh);
            if(factory==null || factory.r()!=resolved(ar.b) || queueCount(factory)!=0)continue;
            Object production=action(factory,false);
            if(production==null || !Boolean.TRUE.equals(invoke(AVAILABLE,production,factory)))continue;
            as type=(as)invoke(TYPE,production);
            return CommandResult.ok("{\"status\":\"planned\",\"sessionId\":\""+bridge.sessionId+"\",\"factoryId\":"+factory.eh
                +",\"factoryType\":\""+escape(factory.r().i())+"\",\"productType\":\""+escape(type.i())+"\",\"tankCost\":"+invoke(COST,production)+"}");
        }
        return CommandResult.error(409,"no completed own idle landFactory with available tank action; finish Opening first and clear its queue");
    }
    private CommandResult openingPlan(Long id,boolean requireReachable) {
        if(bridge.engine.bL==null)return CommandResult.error(409,"map unavailable");
        as extractor=resolved(ar.valueOf("extractor"));
        y selected=null;Object extractorAction=null,factoryAction=null;Site chosen=null;
        int builders=0,visibleResources=0;
        SiteDiagnostics diagnostics=new SiteDiagnostics();
        double best=Double.POSITIVE_INFINITY;int candidates=0;
        int tw=bridge.engine.bL.n,th=bridge.engine.bL.o;
        if(tw<=0 || th<=0)return CommandResult.error(409,"invalid tile dimensions");
        am[] live=am.bE.a();
        for(int index=0;index<am.bE.size();index++) {
            am u=live[index];if(u==null)continue;if(id!=null && u.eh!=id.longValue())continue;
            y worker=ownUnit(u.eh);if(worker==null)continue;
            Object mine=buildAction(worker,ar.valueOf("extractor")),factory=action(worker,true);
            if(mine==null || factory==null || !Boolean.TRUE.equals(invoke(AVAILABLE,mine,worker)) || !Boolean.TRUE.equals(invoke(AVAILABLE,factory,worker)))continue;
            builders++;
            ScoutBridge.VisibleGrid reachable=requireReachable?new ScoutBridge.VisibleGrid(bridge,worker):null;
            int minC=Math.max(0,(int)Math.floor((worker.eo-600)/tw)),maxC=Math.min(bridge.engine.bL.C-1,(int)Math.floor((worker.eo+600)/tw));
            int minR=Math.max(0,(int)Math.floor((worker.ep-600)/th)),maxR=Math.min(bridge.engine.bL.D-1,(int)Math.floor((worker.ep+600)/th));
            for(int c=minC;c<=maxC;c++)for(int r=minR;r<=maxR;r++) {
                float x=(c+.5f)*tw,yy=(r+.5f)*th;
                // Read terrain only after current-player visibility passes.
                if(!bridge.engine.bL.a(x,yy,bridge.engine.bs))continue;
                if(bridge.engine.bL.e(c,r)==null || !bridge.engine.bL.e(c,r).i)continue;
                visibleResources++;
                Site possible=site(worker,extractor,x,yy,600,diagnostics);if(possible==null)continue;
                if(reachable!=null && !reachable.reaches(possible.x,possible.y,60)) {
                    diagnostics.legalCandidates--;diagnostics.notReachable++;continue;
                }
                candidates++;double distance=Math.hypot(possible.x-worker.eo,possible.y-worker.ep);
                if(distance<best){best=distance;selected=worker;extractorAction=mine;factoryAction=factory;chosen=possible;}
            }
        }
        String diagnosticJson=diagnostics.json(builders,visibleResources);
        if(selected==null)return new CommandResult(409,"{\"status\":\"error\",\"message\":\""+escape("no legal opening site; extractorType="+extractor.i()+"; eligibleBuilders="+builders+"; visibleResourceCandidates="+visibleResources+"; searchRange=600")+"\",\"diagnostics\":"+diagnosticJson+"}");
        am preview=preview(resolved(ar.b));preview.bX=bridge.engine.bs;Object production=action(preview,false);
        if(production==null)return CommandResult.error(409,"no resolved tank production action; expected="+tankType().i()+"; menu="+menu(preview));
        int mineCost=((Number)invoke(COST,extractorAction)).intValue(),factoryCost=((Number)invoke(COST,factoryAction)).intValue(),tankCost=((Number)invoke(COST,production)).intValue();
        return CommandResult.ok("{\"status\":\"planned\",\"sessionId\":\""+bridge.sessionId+"\",\"builderId\":"+selected.eh
            +",\"extractorType\":\""+escape(extractor.i())+"\",\"extractorX\":"+format(chosen.x)+",\"extractorY\":"+format(chosen.y)
            +",\"extractorCost\":"+mineCost+",\"factoryCost\":"+factoryCost+",\"tankCost\":"+tankCost
            +",\"productType\":\""+escape(((as)invoke(TYPE,production)).i())+"\",\"targetTanks\":3,\"totalCost\":"+(mineCost+factoryCost+3*tankCost)
            +",\"resourceCandidates\":"+candidates+",\"diagnostics\":"+diagnosticJson+",\"distance\":"+format(best)+",\"credits\":"+format(bridge.engine.bs.o)+"}");
    }
    private static Object buildAction(am unit,ar requested) {
        for(Object a:unit.N())if(BUILD.isInstance(a) && invoke(TYPE,a)==resolved(requested))return a;
        return null;
    }
    private static String actionText(Object a){
        Object id=invoke(ACTION_ID,a);return (String)invoke(method(ACTION_ID.getReturnType(),"a"),id);
    }
    private static boolean strategicAction(y unit,Object action,boolean construct){
        as product=(as)invoke(TYPE,action);if(product==null)return false;
        String type=product.i(),owner=unit.r().i();
        if(!construct)return !BUILD.isInstance(action)&&
            (("extractorT1".equals(owner)&&"extractorT2".equals(type))
             ||("extractorT2".equals(owner)&&"extractorT3".equals(type)));
        if(!BUILD.isInstance(action))return false;
        if("builder".equals(owner))return "landFactory".equals(type);
        return "combatEngineer".equals(owner)&&Arrays.asList("heavyTank","amphibiousJet","repairbay","landFactory").contains(type);
    }
    private CommandResult investments(){
        StringBuilder out=new StringBuilder("{\"status\":\"observed\",\"sessionId\":\"").append(bridge.sessionId)
            .append("\",\"gameTimeMs\":").append(bridge.engine.by).append(",\"units\":[");
        boolean first=true;am[] live=am.bE.a();
        for(int i=0;i<am.bE.size();i++){
            y unit=ownUnit(live[i].eh);if(unit==null||!("extractorT1".equals(unit.r().i())||"extractorT2".equals(unit.r().i())))continue;
            for(Object a:unit.N())if(strategicAction(unit,a,false)&&Boolean.TRUE.equals(invoke(AVAILABLE,a,unit))){
                if(!first)out.append(',');first=false;
                out.append("{\"id\":").append(unit.eh).append(",\"type\":\"").append(escape(unit.r().i())).append("\",\"queue\":").append(queueCount(unit))
                    .append(",\"actionId\":\"").append(escape(actionText(a))).append("\",\"product\":\"").append(escape(((as)invoke(TYPE,a)).i())).append("\",\"cost\":")
                    .append(invoke(COST,a)).append(",\"affordable\":").append(invoke(AFFORDABLE,a,unit,true)).append('}');
            }
        }
        return CommandResult.ok(out.append("]}").toString());
    }
    private CommandResult constructionPlan(long id,String wanted){
        y unit=ownUnit(id);if(unit==null)return CommandResult.error(409,"completed own constructor required");
        Object chosen=null;
        for(Object a:unit.N())if(strategicAction(unit,a,true)&&wanted.equals(((as)invoke(TYPE,a)).i())
                &&Boolean.TRUE.equals(invoke(AVAILABLE,a,unit))){chosen=a;break;}
        if(chosen==null)return CommandResult.error(409,"native construction action unavailable");
        as product=(as)invoke(TYPE,chosen);Site location=null;
        // Only a currently visible native-legal footprint near this owned constructor is returned.
        for(int radius=60;radius<=180&&location==null;radius+=40)for(int direction=0;direction<16&&location==null;direction++){
            double angle=direction*Math.PI/8;
            location=site(unit,product,(float)(unit.eo+Math.cos(angle)*radius),(float)(unit.ep+Math.sin(angle)*radius),200);
        }
        if(location==null)return CommandResult.error(409,"no visible legal local construction position");
        return CommandResult.ok("{\"status\":\"planned\",\"sessionId\":\""+bridge.sessionId+"\",\"unitId\":"+id
            +",\"type\":\""+escape(product.i())+"\",\"actionId\":\""+escape(actionText(chosen))+"\",\"cost\":"+invoke(COST,chosen)
            +",\"affordable\":"+invoke(AFFORDABLE,chosen,unit,true)+",\"x\":"+format(location.x)+",\"y\":"+format(location.y)+"}");
    }
    private CommandResult strategicCommand(String path,Map<String,String> q){
        boolean construct=path.endsWith("construct");
        requireKeys(q,construct?new String[]{"unitId","actionId","x","y","sessionId","requestId"}
                :new String[]{"unitId","actionId","sessionId","requestId"});
        if(!bridge.sessionId.equals(q.get("sessionId")))return CommandResult.error(409,"session changed");
        if(bridge.engine.dq||bridge.engine.dt)return CommandResult.error(409,"match already ended");
        String request=q.get("requestId"),fingerprint=path+new TreeMap<String,String>(q);
        if(!request.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("invalid requestId");
        if(!bridge.sessionId.equals(receiptSession)){receipts.clear();receiptSession=bridge.sessionId;}
        Receipt old=receipts.get(request);if(old!=null)return old.fingerprint.equals(fingerprint)?old.result:CommandResult.error(409,"requestId reused");
        long id=Long.parseLong(q.get("unitId"));y unit=ownUnit(id);
        if(unit==null)return CommandResult.error(409,"completed own constructor required");
        Object chosen=null;
        for(Object a:unit.N())if(strategicAction(unit,a,construct)&&actionText(a).equals(q.get("actionId"))){chosen=a;break;}
        if(chosen==null||!usable(chosen,unit))return CommandResult.error(409,"native action unavailable or insufficient credits");
        if(!construct&&queueCount(unit)!=0)return CommandResult.error(409,"investment queue must be empty");
        as product=(as)invoke(TYPE,chosen);Site location=null;
        if(construct){
            location=site(unit,product,coordinate(q.get("x")),coordinate(q.get("y")),200);
            if(location==null)return CommandResult.error(409,"no visible legal local footprint");
        }
        e command=bridge.engine.cf.b(bridge.engine.bs);command.a(unit);
        if(construct)command.a(location.x,location.y,product,((Number)invoke(TIER,chosen)).intValue());
        else invoke(SET_ACTION,command,invoke(ACTION_ID,chosen));
        CommandResult result=CommandResult.ok("{\"status\":\"queued\",\"sessionId\":\""+bridge.sessionId+"\",\"requestId\":\""+request
            +"\",\"unitId\":"+id+",\"type\":\""+escape(product.i())+"\",\"actionId\":\""+escape(actionText(chosen))+"\",\"frame\":"+bridge.engine.bx+"}");
        receipts.put(request,new Receipt(fingerprint,result));if(receipts.size()>256)receipts.remove(receipts.keySet().iterator().next());return result;
    }
    /**
     * P2-C1: the native "produce a builder" action. It is looked up by resolved type rather than by
     * action class, because the builder is a unit while the action may be exposed through either the
     * production or the building action family depending on the unit definition.
     */
    private static Object builderAction(am unit) {
        as builder=resolved(ar.valueOf("builder"));
        for(Object a:unit.N())if(invoke(TYPE,a)==builder)return a;
        return null;
    }
    /** Alive, completed builders of ours; a builder still in production does not count. */
    private int countBuilders() {
        as builder=resolved(ar.valueOf("builder"));
        int count=0;am[] live=am.bE.a();
        for(int i=0;i<am.bE.size();i++) {
            am u=live[i];if(u==null)continue;
            if(u.r()==builder && ownUnit(u.eh)!=null)count++;
        }
        return count;
    }
    /** How many units of this specific action are already queued in this producer. */
    private static int queuedCount(am unit,Object action) {
        if(!FACTORY.isInstance(unit))return -1;
        try {return ((Number)invoke(method(FACTORY,"a",ACTION_ID.getReturnType(),boolean.class),unit,invoke(ACTION_ID,action),false)).intValue();}
        catch(Exception error){throw new IllegalStateException("Native queued unit mapping failed",error);}
    }
    /** The recovery/bootstrap contract is global: another producer's builder also prevents an order. */
    private int allBuildersQueued() {
        int queued=0;am[] live=am.bE.a();
        for(int i=0;i<am.bE.size();i++) {
            am u=live[i];if(u==null || !FACTORY.isInstance(u) || ownUnit(u.eh)==null)continue;
            Object a=builderAction(u);if(a!=null)queued+=Math.max(0,queuedCount(u,a));
        }
        return queued;
    }
    /**
     * Read-only inventory of one own unit's native actions (输出10 §6). It answers the recovery
     * question honestly: if a builder has no repair/assist/continue-construction action at all, then a
     * half-built site really cannot be resumed through the native command system, and the client is
     * allowed to abandon it explicitly instead of pretending the job became active again.
     */
    private CommandResult builderActions(long id) {
        y unit=ownUnit(id);
        if(unit==null)return CommandResult.error(409,"completed controllable own unit not found");
        StringBuilder j=new StringBuilder("{\"status\":\"planned\",\"sessionId\":\"").append(bridge.sessionId)
            .append("\",\"unitId\":").append(id).append(",\"unitType\":\"").append(escape(unit.r().i())).append("\",\"actions\":[");
        boolean first=true;int count=0;boolean construction=false;
        for(Object a:unit.N()) {
            if(count>=32)break;
            as type=(as)invoke(TYPE,a);
            String kind=type==null?"upgrade":type.i();
            String idText=String.valueOf(invoke(ACTION_ID,a));
            String lower=(kind+" "+idText+" "+a.getClass().getSimpleName()).toLowerCase();
            boolean resume=lower.contains("repair")||lower.contains("assist")||lower.contains("reclaim")
                ||lower.contains("continue")||lower.contains("resume");
            boolean build=BUILD.isInstance(a);
            if(resume)construction=true;
            if(!first)j.append(',');first=false;count++;
            j.append("{\"class\":\"").append(escape(a.getClass().getSimpleName())).append("\",\"type\":\"").append(escape(kind))
             .append("\",\"actionId\":\"").append(escape(idText)).append("\",\"cost\":").append(invoke(COST,a))
             .append(",\"available\":").append(Boolean.TRUE.equals(invoke(AVAILABLE,a,unit)))
             .append(",\"affordable\":").append(Boolean.TRUE.equals(invoke(AFFORDABLE,a,unit,true)))
             .append(",\"buildAction\":").append(build)
             .append(",\"recoveryCandidate\":").append(resume||build).append('}');
        }
        return CommandResult.ok(j.append("],\"actionCount\":").append(count)
            .append(",\"hasRecoveryPath\":").append(construction)
            .append(",\"recoveryPath\":\"").append(construction?"SAME_BUILD_COMMAND":"NONE").append('"')
            .append('}').toString());
    }
    /**
     * Read-only builder production status (输出9 §2). One producer is selected - the command centre when
     * it can produce builders, otherwise any own building that can - and its native action, price and
     * queue are reported so the client never has to guess or hardcode the cost.
     */
    private CommandResult builderProduction() {
        as builder=resolved(ar.valueOf("builder"));
        am selected=null;Object selectedAction=null;int builders=0,totalQueued=0,producers=0,rank=-1;
        am[] live=am.bE.a();
        for(int i=0;i<am.bE.size();i++) {
            am u=live[i];if(u==null)continue;
            y own=ownUnit(u.eh);if(own==null)continue;
            if(u.r()==builder)builders++;
            if(!FACTORY.isInstance(own))continue;
            Object action=builderAction(own);
            if(action==null)continue;
            producers++;
            int queued=queuedCount(own,action);totalQueued+=Math.max(0,queued);
            boolean available=Boolean.TRUE.equals(invoke(AVAILABLE,action,own));
            if(queued<=0 && !available)continue;
            boolean centre="commandCenter".equals(u.r().i());
            // Observe an existing builder queue first; otherwise prefer an idle native producer.
            int candidateRank=queued>0?8:(queueCount(own)==0?4:0);if(centre)candidateRank++;
            if(selected==null || candidateRank>rank) { selected=u;selectedAction=action;rank=candidateRank; }
        }
        if(selected==null)return CommandResult.error(409,"no completed own building exposes an available native builder action");
        as type=(as)invoke(TYPE,selectedAction);
        int queued=queuedCount(selected,selectedAction);
        return CommandResult.ok("{\"status\":\"planned\",\"sessionId\":\""+bridge.sessionId+"\",\"producerId\":"+selected.eh
            +",\"producerType\":\""+escape(selected.r().i())+"\",\"queueCount\":"+queueCount(selected)
            +",\"buildersQueued\":"+queued+",\"builderActionId\":\""+escape(String.valueOf(invoke(ACTION_ID,selectedAction)))+"\""
            +",\"builderActionClass\":\""+escape(selectedAction.getClass().getSimpleName())+"\""
            +",\"builderActionAvailable\":"+Boolean.TRUE.equals(invoke(AVAILABLE,selectedAction,selected))
            +",\"builderActionAffordable\":"+Boolean.TRUE.equals(invoke(AFFORDABLE,selectedAction,selected,true))
            +",\"builderCost\":"+invoke(COST,selectedAction)
            +",\"builderType\":\""+escape(type.i())+"\",\"availableCredits\":"+format(bridge.engine.bs.o)
            +",\"existingBuilders\":"+builders+",\"totalBuildersQueued\":"+totalQueued
            +",\"builderProducers\":"+producers+",\"builderOrderPending\":"+(totalQueued>0)+"}");
    }
    private Site site(y builder,as type,float x,float yy) { return site(builder,type,x,yy,240); }
    private Site site(y builder,as type,float x,float yy,int range) {
        return site(builder,type,x,yy,range,null);
    }
    private Site site(y builder,as type,float x,float yy,int range,SiteDiagnostics diagnostics) {
        if(bridge.engine.bL==null || !Float.isFinite(x) || !Float.isFinite(yy))return null;
        y ghost=(y)preview(type);ghost.bX=bridge.engine.bs;
        int tileWidth=bridge.engine.bL.n,tileHeight=bridge.engine.bL.o;
        if(tileWidth<=0 || tileHeight<=0)return null;
        ghost.eo=(float)Math.floor(x/tileWidth)*tileWidth+ghost.cZ();
        ghost.ep=(float)Math.floor(yy/tileHeight)*tileHeight+ghost.da();
        if(Math.hypot(ghost.eo-builder.eo,ghost.ep-builder.ep)>range) { if(diagnostics!=null)diagnostics.outsideRange++;return null; }
        Rect footprint=ghost.cd();
        int col=(int)Math.floor((ghost.eo-ghost.cZ()+1)/tileWidth),row=(int)Math.floor((ghost.ep-ghost.da()+1)/tileHeight);
        for(int c=col+footprint.a;c<=col+footprint.c;c++)for(int r=row+footprint.b;r<=row+footprint.d;r++) {
            if(!bridge.engine.bL.c(c,r)) { if(diagnostics!=null)diagnostics.footprintOutsideMap++;return null; }
            if(!bridge.engine.bL.a((c+0.5f)*tileWidth,(r+0.5f)*tileHeight,bridge.engine.bs)) { if(diagnostics!=null)diagnostics.footprintNotVisible++;return null; }
        }
        // Native footprint/terrain/building collision rules; no unit creation in the world.
        String rejection=ghost.b(false,bridge.engine.bs);
        if(rejection!=null) { if(diagnostics!=null)diagnostics.reject(rejection);return null; }
        if(diagnostics!=null)diagnostics.legalCandidates++;
        return new Site(ghost.eo,ghost.ep);
    }
    /** Counts builder/site pairs; a visible tile may be counted for multiple builders. */
    private static final class SiteDiagnostics {
        int outsideRange,footprintOutsideMap,footprintNotVisible,nativeRejected,legalCandidates,notReachable;
        final Map<String,Integer> nativeReasons=new TreeMap<String,Integer>();
        void reject(String reason) {
            nativeRejected++;
            // Native placement codes are retained; do not infer terrain/occupancy causes.
            String key=reason.length()>160?reason.substring(0,160):reason;
            Integer old=nativeReasons.get(key);nativeReasons.put(key,old==null?1:old+1);
        }
        String json(int builders,int visible) {
            StringBuilder j=new StringBuilder("{\"countingUnit\":\"builder_site_pair\",\"searchRange\":600,\"eligibleBuilders\":").append(builders)
                .append(",\"visibleResourceCandidates\":").append(visible).append(",\"outsideRange\":").append(outsideRange)
                .append(",\"footprintOutsideMap\":").append(footprintOutsideMap).append(",\"footprintNotVisible\":").append(footprintNotVisible)
                .append(",\"nativeRejected\":").append(nativeRejected).append(",\"legalCandidates\":").append(legalCandidates)
                .append(",\"notReachable\":").append(notReachable)
                .append(",\"nativeRejectionReasons\":{");
            boolean first=true;for(Map.Entry<String,Integer> reason:nativeReasons.entrySet()) {
                if(!first)j.append(',');first=false;j.append('"').append(escape(reason.getKey())).append("\":").append(reason.getValue());
            }
            return j.append("}}").toString();
        }
    }
    private CommandResult preflight() {
        CommandResult guard=bridge.commandGuard();
        int builders=0,idle=0,busy=0,constructing=0,unavailable=0;
        Long selected=null;
        if(bridge.engine.bG && bridge.engine.bs!=null) {
            am[] live=am.bE.a();
            for(int i=0;i<am.bE.size();i++) {
                am unit=live[i];if(unit==null || unit.bX!=bridge.engine.bs || unit.ej || unit.bV)continue;
                boolean factory=unit.r()==resolved(ar.b);
                if(unit.cm<1) { if(factory)constructing++;continue; }
                y controllable=ownUnit(unit.eh);if(controllable==null)continue;
                Object build=action(unit,true);
                if(build!=null && Boolean.TRUE.equals(invoke(AVAILABLE,build,unit)))builders++;
                if(factory) {
                    int queue=queueCount(unit);Object produce=action(unit,false);
                    if(queue>0)busy++;
                    else if(queue==0 && produce!=null && Boolean.TRUE.equals(invoke(AVAILABLE,produce,unit))) {idle++;if(selected==null)selected=unit.eh;}
                    else unavailable++;
                }
            }
        }
        String recommendation;
        if(guard!=null)recommendation="RESOLVE_GAME_STATE";
        else if(idle>0)recommendation="RUN_DEVELOP";
        else if(busy>0)recommendation="WAIT_FACTORY_QUEUE";
        else if(constructing>0)recommendation="WAIT_FACTORY_CONSTRUCTION";
        else if(unavailable>0)recommendation="CHECK_FACTORY_ACTION";
        else if(builders>0)recommendation="RUN_ECONOMY_OR_OPENING";
        else recommendation="PREPARE_BUILDER";
        return CommandResult.ok("{\"status\":\"preflight\",\"sessionId\":\""+bridge.sessionId+"\",\"commandsAllowed\":"+(guard==null)
            +",\"commandGuard\":"+(guard==null?"null":guard.json)+",\"eligibleBuilders\":"+builders+",\"idleFactories\":"+idle
            +",\"busyFactories\":"+busy+",\"factoriesUnderConstruction\":"+constructing+",\"unavailableFactories\":"+unavailable
            +",\"factoryId\":"+(selected==null?"null":selected.toString())+",\"recommendation\":\""+recommendation+"\"}");
    }
    private y ownUnit(long id) {
        am[] units=am.bE.a();
        for(int i=0;i<am.bE.size();i++) {
            am u=units[i];
            if(u instanceof y && u.eh==id && u.bX==bridge.engine.bs && !u.ej && !u.bV && !u.cW() && u.cm>=1)return (y)u;
        }
        return null;
    }
    static int queueCount(am unit) {
        if(!FACTORY.isInstance(unit))return -1;
        try {return ((Number)invoke(method(FACTORY,"a",ACTION_ID.getReturnType(),boolean.class),unit,ACTION.getField("i").get(null),false)).intValue();}
        catch(ReflectiveOperationException error){throw new IllegalStateException("Native complete queue mapping failed",error);}
    }
    static boolean expansionBuilder(am unit) {
        Object a=buildAction(unit,ar.valueOf("extractor"));
        return a!=null && Boolean.TRUE.equals(invoke(AVAILABLE,a,unit));
    }
    static Object action(am unit,boolean build) {
        for(Object a:unit.N()) {
            if(!(build?BUILD:PRODUCE).isInstance(a))continue;
            as type=(as)invoke(TYPE,a);
            if(build ? type==resolved(ar.b) : type==tankType())return a;
        }
        return null;
    }
    private static as tankType() { return resolved(ar.valueOf("tank")); }
    private static as resolved(ar original) {
        as replaced=(as)invoke(REPLACEMENT,null,original);
        return replaced==null?original:replaced;
    }
    private static am preview(as type) {
        if(type instanceof ar)return ((ar)type).a(true);
        if(CUSTOM.isInstance(type))return (am)invoke(CUSTOM_PREVIEW,type,true);
        throw new IllegalArgumentException("unsupported unit metadata: "+type.i());
    }
    private static String menu(am unit) {
        StringBuilder result=new StringBuilder();
        for(Object a:unit.N()) {
            if(result.length()>1200){result.append("...");break;}
            as type=(as)invoke(TYPE,a);
            result.append(a.getClass().getSimpleName()).append(":").append(type==null?"none":type.i()).append(";");
        }
        return result.toString();
    }
    private static boolean usable(Object action,am unit) { return Boolean.TRUE.equals(invoke(AVAILABLE,action,unit)) && Boolean.TRUE.equals(invoke(AFFORDABLE,action,unit,true)); }
    private static float coordinate(String value) { float v=Float.parseFloat(value);if(!Float.isFinite(v))throw new IllegalArgumentException("coordinates must be finite");return v; }
    static Map<String,String> query(String raw) {
        Map<String,String> q=new LinkedHashMap<String,String>();if(raw==null || raw.isEmpty())return q;
        if(raw.length()>1024)throw new IllegalArgumentException("query too long");
        try { for(String part:raw.split("&")) {String[] pair=part.split("=",2);if(pair.length!=2)throw new IllegalArgumentException("invalid query");
            String key=URLDecoder.decode(pair[0],"UTF-8"),value=URLDecoder.decode(pair[1],"UTF-8");if(q.put(key,value)!=null)throw new IllegalArgumentException("duplicate field");}
        }catch(java.io.UnsupportedEncodingException ex){throw new AssertionError(ex);}return q;
    }
    private static void requireKeys(Map<String,String> q,String[] keys) {if(q.size()!=keys.length || !q.keySet().containsAll(Arrays.asList(keys)))throw new IllegalArgumentException("required fields: "+Arrays.toString(keys));}
    private static Class<?> cls(String name) {try{return Class.forName(name);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static Method method(Class<?> type,String name,Class<?>... params) {try{return type.getMethod(name,params);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static Object invoke(Method method,Object target,Object... args) {try{return method.invoke(target,args);}catch(Exception e){throw new IllegalStateException("Native economy mapping failed: "+method,e);}}
    private static final class Site {final float x,y;Site(float x,float y){this.x=x;this.y=y;}}
    private static final class Receipt {final String fingerprint;final CommandResult result;Receipt(String f,CommandResult r){fingerprint=f;result=r;}}
}
