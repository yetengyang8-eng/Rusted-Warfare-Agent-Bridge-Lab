package io.rwagent.headless;

import android.content.ServerContext;
import com.corrodinggames.rts.game.i;
import com.corrodinggames.rts.gameFramework.l;
import io.rwagent.bootstrap.RuntimeBridge;
import io.rwagent.client.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.locks.LockSupport;

/** Experimental native 1.15 process runner. Original engine simulation; no fabricated units or game state. */
public final class HeadlessRunner {
    static final String HASH="8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9";
    static final class NoInputView extends com.corrodinggames.rts.java.d {
        private final com.corrodinggames.rts.appFramework.m input=new com.corrodinggames.rts.appFramework.m();
        NoInputView(){a=1280;b=720;}
        @Override public com.corrodinggames.rts.appFramework.m k(){return input;}
    }
    public static void main(String[] args) {
        try {run(args);System.exit(0);}catch(Throwable error){error.printStackTrace();System.exit(1);}
    }
    static void run(String[] args)throws Exception {
        Map<String,String> options=new HashMap<String,String>();
        Set<String> valid=new HashSet<String>(Arrays.asList("map","port","speed","max-wall-seconds","frames","difficulty"));
        for(int j=0;j<args.length;j+=2) {
            if(j+1==args.length || !args[j].startsWith("--") || !valid.contains(args[j].substring(2)) || options.put(args[j].substring(2),args[j+1])!=null)
                throw new IllegalArgumentException("Options: --map PATH --port N --speed 0..8 --max-wall-seconds 1..3600 --frames N --difficulty -2..3");
        }
        String map=options.containsKey("map")?options.get("map"):"maps/skirmish/[p2]Small_Island (2p).tmx";
        if(!map.startsWith("maps/") || map.contains("..") || map.contains("\\") || !map.endsWith(".tmx") || !new File("assets",map).isFile())
            throw new IllegalArgumentException("Map must exist under assets/maps");
        int port=Integer.parseInt(options.containsKey("port")?options.get("port"):"47653");
        int frames=Integer.parseInt(options.containsKey("frames")?options.get("frames"):"0");
        int maxWall=Integer.parseInt(options.containsKey("max-wall-seconds")?options.get("max-wall-seconds"):"300");
        int difficulty=Integer.parseInt(options.containsKey("difficulty")?options.get("difficulty"):"0");
        double speed=Double.parseDouble(options.containsKey("speed")?options.get("speed"):"1");
        if(port<1024 || port>65535 || frames<0 || maxWall<1 || maxWall>3600 || !Double.isFinite(speed) || speed<0 || speed>8 || difficulty< -2 || difficulty>3)
            throw new IllegalArgumentException("Out-of-range runtime option");
        if(speed==0 && frames==0)throw new IllegalArgumentException("Unthrottled benchmark requires --frames");
        if(!HASH.equals(sha(Paths.get("game-lib.jar"))))throw new IllegalArgumentException("Unsupported game-lib.jar fingerprint");
        android.os.Looper.a();
        l.aU=true; // Native server/null graphics path.
        l.bb=true;
        l.aB=true; // Skip image resource loads.
        l.aJ=true; // Disable external mods; bundled original unit definitions still load.
        l.ck=new android.graphics.Point(1280,720);
        Class.forName("com.corrodinggames.rts.gameFramework.a.e").getField("c").set(null,Class.forName("com.corrodinggames.rts.gameFramework.a.f").newInstance());
        Class.forName("com.corrodinggames.rts.gameFramework.am").getField("a").set(null,Class.forName("com.corrodinggames.rts.gameFramework.av").newInstance());
        i engine=(i)l.a(new ServerContext(),null);
        l.aW=true; // Set PC map/fog defaults after null-image unit loading.
        engine.bQ.aiDifficulty=difficulty;
        engine.bQ.enableSounds=false;
        engine.bQ.musicVolume=0;
        engine.bX.ay.d=2; // Native LOS fog configuration before loading the match.
        engine.dl=map;
        engine.a(true,com.corrodinggames.rts.gameFramework.s.b);
        engine.ap=new NoInputView();
        if(!engine.bG || engine.bs==null || !engine.bL.E || !engine.bL.F)throw new IllegalStateException("Native match/fog initialization failed: loaded="+engine.bG+" player="+engine.bs+" E="+engine.bL.E+" F="+engine.bL.F+" config="+engine.bX.ay.d);
        if(engine.bX.B || engine.cb.j())throw new IllegalStateException("Only local matches are supported");
        // Do not manipulate fog arrays, positions, funds, construction or production.
        RuntimeBridge.start(engine,port,true);
        long started=System.nanoTime();
        long step=speed==0?0:(long)(1e9/(60*speed));
        long next=started;
        int ticks=0;
        String status="RUNNING";
        save(engine,map,port,speed,difficulty,status,0,0);
        System.out.println("RW_HEADLESS_READY map="+map+" port="+port+" fog=los seed="+engine.bJ);
        while(true) {
            if(Files.exists(Paths.get("stop.request"))){status="STOP_REQUESTED";break;}
            if(frames>0 && ticks>=frames){status="FRAME_LIMIT";break;}
            if((System.nanoTime()-started)/1e9>=maxWall){status="WALL_LIMIT";break;}
            engine.b(1f,16);ticks++;
            if(step>0){next+=step;long delay=next-System.nanoTime();if(delay>0)LockSupport.parkNanos(delay);else if(delay< -1000000000L)next=System.nanoTime();}
        }
        double wall=(System.nanoTime()-started)/1e9;
        save(engine,map,port,speed,difficulty,status,ticks,wall);
        System.out.println("RW_HEADLESS_FINISHED status="+status+" ticks="+ticks+" frame="+engine.bx+" gameTimeMs="+engine.by+" wallSeconds="+wall);
    }
    static void save(i e,String map,int port,double speed,int difficulty,String status,int ticks,double wall)throws IOException {
        String data="{\"runnerVersion\":\"0.07-alpha1\",\"gameVersion\":"+Json.quote(e.u())+",\"gameJarSha256\":"+Json.quote(HASH)
            +",\"map\":"+Json.quote(map)+",\"port\":"+port+",\"requestedSpeed\":"+speed+",\"aiDifficulty\":"+difficulty
            +",\"seed\":"+e.bJ+",\"seedReproducibilityVerified\":false,\"fogEnabled\":"+e.bL.E+",\"lineOfSightFog\":"+e.bL.F
            +",\"status\":"+Json.quote(status)+",\"ticks\":"+ticks+",\"frame\":"+e.bx+",\"gameTimeMs\":"+e.by
            +",\"wallSeconds\":"+wall+",\"simulatedSecondsPerWallSecond\":"+(wall>0?(e.by/1000.0)/wall:0)+"}\n";
        Path temp=Paths.get("runtime.json.tmp");Files.write(temp,data.getBytes(StandardCharsets.UTF_8));
        Files.move(temp,Paths.get("runtime.json"),StandardCopyOption.REPLACE_EXISTING);
    }
    static String sha(Path path)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[65536];for(int n;(n=in.read(b))>=0;)digest.update(b,0,n);}
        StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
}
