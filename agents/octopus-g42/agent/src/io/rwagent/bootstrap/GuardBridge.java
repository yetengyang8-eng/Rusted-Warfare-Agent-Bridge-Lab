package io.rwagent.bootstrap;

import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.y;
import com.corrodinggames.rts.gameFramework.e;
import com.sun.net.httpserver.HttpServer;
import java.util.*;
import static io.rwagent.bootstrap.RuntimeBridge.*;

/** One native guard waypoint, restricted to a completed own armed mobile and own target. */
final class GuardBridge {
    private final RuntimeBridge bridge;
    private final Map<String,Receipt> receipts=new LinkedHashMap<String,Receipt>();
    private String receiptSession;
    GuardBridge(RuntimeBridge bridge){this.bridge=bridge;}
    void install(HttpServer server) {
        server.createContext("/command/guard",exchange -> {
            if(!"/command/guard".equals(exchange.getRequestURI().getPath())){respond(exchange,404,jsonError("unknown endpoint"));return;}
            if(!"POST".equals(exchange.getRequestMethod())){respond(exchange,405,jsonError("POST required"));return;}
            if(exchange.getRequestHeaders().getFirst("Origin")!=null){respond(exchange,403,jsonError("browser-origin commands disabled"));return;}
            try {
                final Map<String,String> q=EconomyBridge.query(exchange.getRequestURI().getRawQuery());
                CommandResult r=bridge.onGameThread(() -> {
                    try{return guard(q);}catch(IllegalArgumentException error){return CommandResult.error(400,error.getMessage());}
                });respond(exchange,r.httpStatus,r.json);
            }catch(IllegalArgumentException error){respond(exchange,400,jsonError(error.getMessage()));}
            catch(Exception error){RwAgent.log("Guard request failed",error);respond(exchange,503,jsonError("guard result unknown; observe before retrying"));}
        });
    }
    private CommandResult guard(Map<String,String> q) {
        bridge.refreshSession();CommandResult guard=bridge.commandGuard();if(guard!=null)return guard;
        if(q.size()!=4 || !q.keySet().containsAll(Arrays.asList("unitId","targetId","sessionId","requestId")))throw new IllegalArgumentException("required: unitId, targetId, sessionId, requestId");
        long id=Long.parseLong(q.get("unitId")),target=Long.parseLong(q.get("targetId"));String request=q.get("requestId");
        if(!request.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("invalid requestId");
        if(!bridge.sessionId.equals(q.get("sessionId")))return CommandResult.error(409,"session changed; observe again");
        if(!bridge.sessionId.equals(receiptSession)){receipts.clear();receiptSession=bridge.sessionId;}
        Receipt old=receipts.get(request);
        if(old!=null)return old.unit==id && old.target==target?old.result:CommandResult.error(409,"requestId reused with different guard command");
        y actor=own(id),protectedUnit=own(target);
        if(actor==null || protectedUnit==null || actor==protectedUnit || !actor.I() || !actor.l())return CommandResult.error(409,"completed own armed mobile and distinct own target required");
        e command=bridge.engine.cf.b(bridge.engine.bs);command.a(actor);command.c((am)protectedUnit);
        CommandResult result=CommandResult.ok("{\"status\":\"queued\",\"sessionId\":\""+bridge.sessionId+"\",\"requestId\":\""+request
            +"\",\"frame\":"+bridge.engine.bx+",\"unitId\":"+id+",\"targetId\":"+target+",\"orderType\":\"guard\"}");
        receipts.put(request,new Receipt(id,target,result));if(receipts.size()>256)receipts.remove(receipts.keySet().iterator().next());
        return result;
    }
    private y own(long id) {
        am[] units=am.bE.a();for(int index=0;index<am.bE.size();index++) {
            am u=units[index];if(u instanceof y && u.eh==id && u.bX==bridge.engine.bs && !u.ej && !u.bV && !u.cW() && u.cm>=1)return (y)u;
        }return null;
    }
    private static final class Receipt {
        final long unit,target;final CommandResult result;
        Receipt(long unit,long target,CommandResult result){this.unit=unit;this.target=target;this.result=result;}
    }
}
