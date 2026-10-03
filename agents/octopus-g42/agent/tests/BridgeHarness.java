import android.content.ServerContext;
import io.rwagent.bootstrap.RuntimeBridge;
import io.rwagent.client.Json;
import com.corrodinggames.rts.game.units.am;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.FutureTask;

/** Real 1.15 engine objects, with a simulated queue consumer, no graphics/pathfinding. */
public final class BridgeHarness {
    static com.corrodinggames.rts.game.i engine;
    static volatile boolean consume = true;
    static int checks;
    static final String base = "http://127.0.0.1:47656";
    public static void main(String[] args) throws Exception {
        try { test(args.length == 0); System.out.println("BRIDGE_TEST_OK checks=" + checks); System.exit(0); }
        catch (Throwable t) { t.printStackTrace(); System.exit(1); }
    }
    static void test(boolean allow) throws Exception {
        android.os.Looper.a(); engine = new com.corrodinggames.rts.game.i(new ServerContext());
        Thread game = new Thread(() -> {
            while (true) {
                if (consume) { Runnable r = (Runnable)engine.k.poll(); if (r != null) r.run(); }
                try { Thread.sleep(1); } catch (InterruptedException e) { return; }
            }
        }); game.setDaemon(true); game.start();
        RuntimeBridge.start(engine, 47656, allow);
        check("GET", "/health", 200, "0.07-alpha1");
        check("GET", "/command/move", 405, "POST");
        check("POST", "/command/move", 400, "invalid query");
        check("POST", "/command/move?unitId=4&x=NaN&y=1&sessionId=a&requestId=b", 400, "finite");
        check("POST", "/command/move?unitId=4&x=1&x=2&y=1&sessionId=a&requestId=b", 400, "duplicate");
        check("POST", move("menu", "a", 4, "100", "100"), allow ? 409 : 403, allow ? "no level" : "disabled");
        if (!allow) return;
        onGame(() -> {
            engine.bG = true;
            engine.bs = new com.corrodinggames.rts.game.e(0, false);
            engine.bs.v = "Tester\"\\\n中文";
            engine.bL = construct("com.corrodinggames.rts.game.b.b");
            // Real map dimensions are C/D; p/q remain the constructor's half-tile offsets.
            engine.bL.C = 110; engine.bL.D = 110;
            engine.cf = new com.corrodinggames.rts.gameFramework.c();
            engine.bX = construct("com.corrodinggames.rts.gameFramework.j.ad");
            am.bE.clear();
            am u = builder();
            u.eh = 9007199254740993L; u.eo = 100; u.ep = 100; u.bX = engine.bs;
            am.bE.add(u);
            am enemy = builder();
            enemy.eh = 99; enemy.bX = new com.corrodinggames.rts.game.e(1, false); am.bE.add(enemy);
        });
        String state = check("GET", "/state", 200, "9007199254740993");
        Map<?,?> parsed = (Map<?,?>)Json.parse(state);
        Map<?,?> map = (Map<?,?>)parsed.get("map");
        require(((Number)map.get("width")).doubleValue()==2200
                && ((Number)map.get("height")).doubleValue()==2200,
                "Small_Island 110x110 tiles must report 2200x2200 world units, not 200x200");
        require(engine.bL.p==10 && engine.bL.q==10,"native half-tile offsets were not altered by the fixture");
        onGame(() -> { engine.bL.D = 80; });
        String rectangular = check("GET", "/state", 200, "\"height\":1600.000");
        require(rectangular.contains("\"width\":2200.000"),"rectangular map width and height are independent");
        onGame(() -> { engine.bL.D = 110; });
        String session = (String)parsed.get("sessionId");
        require(!state.contains("\"id\":99"), "enemy not exposed");
        check("POST", move(session,"bounds",9007199254740993L,"2200","20"),400,"outside");
        check("POST", move(session,"foreign",99,"200","200"),409,"not found");
        check("POST", move("old-session","stale",9007199254740993L,"200","200"),409,"session changed");
        String path = move(session,"once",9007199254740993L,"220","160");
        check("POST",path,200,"queued");
        check("POST",path,200,"queued");
        require(engine.cf.b.size()==1,"duplicate command queued only once");
        com.corrodinggames.rts.gameFramework.e command = (com.corrodinggames.rts.gameFramework.e)engine.cf.b.get(0);
        require(command.j.g()==220 && command.j.h()==160,"actual command target");
        require(am.bE.a()[0].eo==100,"command did not teleport unit");
        check("POST",move(session,"once",9007199254740993L,"221","160"),409,"reused");
        // These are the exact three starting positions in the user's failed alpha1 reports.
        String[][] origins = {{"990", "1730"}, {"988.95", "1862.397"}, {"940", "1780"}};
        for (int i=0; i<origins.length; i++) {
            check("POST",move(session,"return-regression-"+i,9007199254740993L,origins[i][0],origins[i][1]),200,"queued");
        }
        require(engine.cf.b.size()==4,"all three real return coordinates accepted inside 2200x2200 map");
        onGame(() -> { engine.cf.b.subList(1,engine.cf.b.size()).clear(); am.bE.a()[0].eo=940; am.bE.a()[0].ep=1780; });
        check("POST","/test/move-first",200,"\"targetX\":1060.000,\"targetY\":1720.000");
        onGame(() -> { engine.cf.b.subList(1,engine.cf.b.size()).clear(); am.bE.a()[0].eo=2500; });
        check("POST","/test/move-first",409,"inconsistent with map");
        require(engine.cf.b.size()==1,"invalid origin sent no legacy test command");
        onGame(() -> { am.bE.a()[0].eo=100; am.bE.a()[0].ep=100; });
        onGame(() -> { engine.bX.B = true; });
        check("POST",move(session,"net",9007199254740993L,"220","160"),409,"network");
        onGame(() -> {
            engine.bX.B = false;
            engine.cb = new com.corrodinggames.rts.gameFramework.ba();
            try {
                for (String field : new String[]{"P", "u"}) {
                    java.lang.reflect.Field f = engine.cb.getClass().getDeclaredField(field);
                    f.setAccessible(true); f.setBoolean(engine.cb, true);
                }
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        check("POST",move(session,"replay",9007199254740993L,"220","160"),409,"replay");
        onGame(() -> { engine.cb = null; am.bE.a()[0].bV=true; });
        check("POST",move(session,"dead",9007199254740993L,"220","160"),409,"not found");
        onGame(() -> { am.bE.a()[0].bV=false; engine.bx=100; });
        check("GET","/state",200,"\"frame\":100");
        onGame(() -> { engine.bx=0; });
        check("POST",move(session,"reset",9007199254740993L,"220","160"),409,"session changed");
        String newSession=(String)((Map<?,?>)Json.parse(check("GET","/state",200,"running"))).get("sessionId");
        require(!session.equals(newSession),"session rotates after frame reset");
        consume=false;
        check("POST",move(newSession,"timeout",9007199254740993L,"220","160"),503,"unknown");
        require(engine.k.isEmpty(),"timed-out task removed from queue");
        consume=true;
        onGame(() -> {});
        require(engine.cf.b.size()==1,"timeout did not execute later");
        onGame(() -> { engine.bG=false; });
        check("GET","/state",200,"\"ownUnits\":[]");
    }
    @SuppressWarnings("unchecked") static <T> T construct(String name) { try { return (T)Class.forName(name).newInstance(); } catch(Exception e) { throw new RuntimeException(e); } }
    static am builder() { try { return (am)Class.forName("com.corrodinggames.rts.game.units.e.b").getConstructor(boolean.class).newInstance(true); } catch(Exception e) { throw new RuntimeException(e); } }
    static void onGame(Runnable r) throws Exception { FutureTask<Void> t=new FutureTask<Void>(r,null); engine.k.add(t); t.get(); }
    static String move(String s,String r,long id,String x,String y) { return "/command/move?unitId="+id+"&x="+x+"&y="+y+"&sessionId="+s+"&requestId="+r; }
    static void require(boolean result,String name) { if(!result)throw new AssertionError(name); checks++; System.out.println("PASS "+name); }
    static String check(String method,String path,int expected,String text) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection(); c.setRequestMethod(method);c.setReadTimeout(8000);
        if(method.equals("POST")){c.setDoOutput(true);c.setFixedLengthStreamingMode(0);c.getOutputStream().close();}
        int status=c.getResponseCode(); InputStream in=status>=400?c.getErrorStream():c.getInputStream();
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
        while((n=in.read(b))!=-1)out.write(b,0,n);in.close();c.disconnect();
        String body=new String(out.toByteArray(),StandardCharsets.UTF_8);
        require(status==expected && body.contains(text),method+" "+path+" -> "+status+" "+body);
        Json.parse(body);return body;
    }
}
