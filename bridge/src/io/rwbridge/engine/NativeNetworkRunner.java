package io.rwbridge.engine;

import android.content.ServerContext;
import com.corrodinggames.rts.game.i;
import com.corrodinggames.rts.game.n;
import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.y;
import com.corrodinggames.rts.gameFramework.l;
import io.rwagent.bootstrap.RuntimeBridge;
import io.rwagent.client.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.locks.LockSupport;

/** Native PC 1.15 multiplayer process. Each process has one native local player.
 * No game-library patch, synthetic state, global-player switching, or local command execution. */
public final class NativeNetworkRunner {
    public static final String HASH="8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9";
    static final class NoInputView extends com.corrodinggames.rts.java.d {
        private final com.corrodinggames.rts.appFramework.m input=new com.corrodinggames.rts.appFramework.m();
        NoInputView(){a=1280;b=720;}
        @Override public com.corrodinggames.rts.appFramework.m k(){return input;}
    }
    private static i engine;
    private static String role,map,matchId,status="INITIALIZING";
    private static int port,networkPort,ticks;
    private static long started;
    private static double speed;
    private static y probeUnit;
    private static float probeX,probeY,probeTargetX,probeTargetY;
    private static boolean probeQueued;
    public static void main(String[] args) {
        try {run(args);System.exit(0);}catch(Throwable error){
            error.printStackTrace();status="ERROR";
            try{if(engine!=null)save();}catch(Throwable ignored){}
            System.exit(1);
        }
    }
    static void run(String[] args)throws Exception {
        Map<String,String> o=new HashMap<String,String>();
        Set<String> valid=new HashSet<String>(Arrays.asList("role","connect","network-port","native-port","port","api-port","map","match-id","speed","max-wall-seconds","auto-start","fog","credits","probe","player-name","player-slot","frames"));
        for(int j=0;j<args.length;j+=2){
            if(j+1==args.length || !args[j].startsWith("--") || !valid.contains(args[j].substring(2)) || o.put(args[j].substring(2),args[j+1])!=null)
                throw new IllegalArgumentException("Expected named --role host|join --network-port N --port API --connect HOST:PORT --map maps/skirmish/FILE.tmx --match-id ID --speed .1..8 --max-wall-seconds 1..3600 --auto-start true|false --fog 0..2 --credits N --probe true|false");
        }
        role=get(o,"role","host");
        if(!role.equals("host")&&!role.equals("join"))throw new IllegalArgumentException("role must be host or join");
        map=get(o,"map","maps/skirmish/[p2]Small_Island (2p).tmx");
        if(!map.startsWith("maps/skirmish/")||map.contains("..")||map.contains("\\")||!map.endsWith(".tmx")||!new File("assets",map).isFile())throw new IllegalArgumentException("Use an original bundled assets/maps/skirmish map");
        port=Integer.parseInt(get(o,"port",get(o,"api-port","47653")));
        networkPort=Integer.parseInt(get(o,"network-port",get(o,"native-port","5123")));
        int maxWall=Integer.parseInt(get(o,"max-wall-seconds","300"));
        int frames=Integer.parseInt(get(o,"frames","0"));
        int expectedSlot=Integer.parseInt(get(o,"player-slot",role.equals("host")?"0":"1"));
        int fog=Integer.parseInt(get(o,"fog","2"));
        int credits=Integer.parseInt(get(o,"credits","4000"));
        speed=Double.parseDouble(get(o,"speed","1"));
        boolean autoStart=flag(o,"auto-start",true),probe=flag(o,"probe",false);
        matchId=get(o,"match-id",null);
        if(port<1024||port>65535||networkPort<1024||networkPort>65535||port==networkPort||maxWall<1||maxWall>3600||frames<0||fog<0||fog>2||expectedSlot<0||expectedSlot>=10||!Double.isFinite(speed)||speed<=0||speed>8)throw new IllegalArgumentException("Out of range option");
        if(matchId!=null&&(matchId.length()>128||!matchId.matches("[A-Za-z0-9_-]+")))throw new IllegalArgumentException("Invalid match-id");
        if(!HASH.equals(sha(Paths.get("game-lib.jar"))))throw new IllegalArgumentException("Unsupported original game-lib.jar fingerprint");
        android.os.Looper.a();
        l.aU=true;l.bb=true;l.aB=true;l.aJ=true;l.ck=new android.graphics.Point(1280,720);
        Class.forName("com.corrodinggames.rts.gameFramework.a.e").getField("c").set(null,Class.forName("com.corrodinggames.rts.gameFramework.a.f").newInstance());
        Class.forName("com.corrodinggames.rts.gameFramework.am").getField("a").set(null,Class.forName("com.corrodinggames.rts.gameFramework.av").newInstance());
        engine=(i)l.a(new ServerContext(),null);
        l.aW=true;
        engine.ap=new NoInputView();
        engine.bQ.enableSounds=false;engine.bQ.musicVolume=0;engine.bQ.networkPort=networkPort;engine.bQ.udpInMultiplayer=false;
        engine.bX.y=get(o,"player-name","bridge-"+role);
        engine.bX.q=false; // Private native lobby, no master-server publication.
        engine.bX.getClass().getField("r").setBoolean(null,false);
        started=System.nanoTime();
        if(role.equals("host")){
            if(!engine.bX.b(false))throw new IllegalStateException("Native listen failed");
            engine.bX.ay.a=engine.bX.ay.a.values()[0];engine.bX.ay.b=map.substring("maps/skirmish/".length());engine.bX.ay.d=fog;
            int creditIndex=-1;for(int j=0;j<9;j++)if(engine.bX.e(j)==credits){creditIndex=j;break;}
            if(creditIndex<0)throw new IllegalArgumentException("Unsupported native starting credits");
            engine.bX.ay.c=creditIndex;engine.bX.az=map;engine.bX.getClass().getMethod("a",engine.bX.ay.getClass()).invoke(engine.bX,engine.bX.ay);
        }else{
            if(!((Boolean)engine.bX.getClass().getMethod("a",java.net.Socket.class).invoke(engine.bX,(java.net.Socket)engine.bX.getClass().getMethod("b",String.class,boolean.class).invoke(null,get(o,"connect","127.0.0.1:"+networkPort),false))))throw new IllegalStateException("Native join failed");
        }
        status="LOBBY";save();
        System.out.println("RW_NATIVE_LOBBY role="+role+" networkPort="+networkPort+" apiPort="+port);
        long next=System.nanoTime(),lastReport=0;
        boolean loaded=false,surrenderSent=false,readySent=false;
        while(true){
            if(Files.exists(Paths.get("stop.request"))){status="STOP_REQUESTED";break;}
            if((System.nanoTime()-started)/1e9>=maxWall){status="WALL_LIMIT";break;}
            if(loaded&&frames>0&&engine.bx>=frames){status="FRAME_LIMIT";break;}
            if(!loaded){
                engine.bX.b(1f);engine.bX.getClass().getMethod("a",float.class).invoke(engine.bX,1f); // Native lobby pump; full engine tick requires a loaded map.
                if(role.equals("host")&&!engine.bX.aW&&engine.bX.C()>0&&(autoStart||Files.exists(Paths.get("start.request")))){
                    if(!engine.bX.ae())throw new IllegalStateException("Native start-game packet failed");
                    System.out.println("RW_NATIVE_START_SENT server="+engine.bX.ac()+" seed="+engine.bX.ay.q);
                }
                if(engine.bX.aW){
                    Class.forName("com.corrodinggames.rts.appFramework.n").getMethod("r").invoke(null); // Original multiplayer UI's map loader, also binds engine.bs=engine.bX.z.
                    if(!engine.bG||engine.bs==null||engine.bs!=engine.bX.z||!engine.bX.B||engine.bs.k!=expectedSlot)throw new IllegalStateException("Native map/player binding failed: expectedSlot="+expectedSlot+" actual="+(engine.bs==null?-1:engine.bs.k));
                    map=engine.dl;
                    if(matchId==null)matchId="native_"+engine.bX.ac().replaceAll("[^A-Za-z0-9_-]","_")+"_"+engine.bX.ay.q;
                    RuntimeBridge.class.getMethod("startNativeNetwork",i.class,int.class,boolean.class,String.class,int.class).invoke(null,engine,port,true,matchId,expectedSlot);
                    loaded=true;status="RUNNING";save();
                    System.out.println("RW_NATIVE_READY role="+role+" matchId="+matchId+" nativeServerId="+engine.bX.ac()+" playerId="+engine.bs.k+" teamId="+engine.bs.r+" seed="+engine.bJ+" port="+port);
                }
            }else{
                engine.b(1f,16);ticks++;
                if(!readySent&&role.equals("join")){engine.bX.ad();readySent=true;System.out.println("RW_NATIVE_LOADED_ACK player="+engine.bs.k);}
                if(!surrenderSent&&Files.exists(Paths.get("surrender.request"))){engine.bX.m("-surrender");surrenderSent=true;System.out.println("RW_NATIVE_SURRENDER_SENT player="+engine.bs.k+" frame="+engine.bx);}
                if(probe&&!probeQueued&&engine.bx>=60)queueProbe();
                if(!engine.bX.B){status="DISCONNECTED";break;}
                if(engine.bX.bc){status="START_FAILED";break;}
            }
            long now=System.nanoTime();if(now-lastReport>=500000000L){save();lastReport=now;}
            long step=(long)(1e9/(60*(loaded?speed:1)));
            next+=step;long delay=next-System.nanoTime();if(delay>0)LockSupport.parkNanos(delay);else if(delay< -1000000000L)next=System.nanoTime();
        }
        save();engine.bX.b("bridge runner "+status);
        System.out.println("RW_NATIVE_FINISHED role="+role+" status="+status+" frame="+engine.bx);
    }
    static void queueProbe(){
        for(am unit:am.bE.a())if(unit instanceof y&&unit.bX==engine.bs&&!unit.bV&&!unit.ej&&!unit.cW()&&((y)unit).I()){
            probeUnit=(y)unit;probeX=unit.eo;probeY=unit.ep;
            probeTargetX=Math.max(20,Math.min(engine.bL.i()-20,probeX+(probeX<engine.bL.i()/2?100:-100)));
            probeTargetY=Math.max(20,Math.min(engine.bL.j()-20,probeY+(probeY<engine.bL.j()/2?60:-60)));
            com.corrodinggames.rts.gameFramework.e command=engine.cf.b(engine.bs);command.a(probeUnit);command.a(probeTargetX,probeTargetY);
            probeQueued=true;System.out.println("RW_NATIVE_PROBE_QUEUED player="+engine.bs.k+" unit="+unit.eh+" frame="+engine.bx);return;
        }
    }
    static void save()throws IOException {
        n player=engine.bs;
        String result=engine.dt?"DEFEAT":engine.dq?"VICTORY":player!=null&&player.G?"DEFEAT":player!=null&&player.H?"VICTORY":"ONGOING";
        String resultSource=engine.dt||engine.dq?"native_result_screen":player!=null&&(player.G||player.H)?"native_player_flags":"none";
        String data="{\"runnerVersion\":\"native-network-m0\",\"gameVersion\":"+q(engine.u())+",\"gameJarSha256\":"+q(HASH)
            +",\"role\":"+q(role)+",\"matchId\":"+q(matchId)+",\"status\":"+q(status)+",\"map\":"+q(map)
            +",\"port\":"+port+",\"networkPort\":"+networkPort+",\"requestedSpeed\":"+speed+",\"playerId\":"+(player==null?-1:player.k)+",\"teamId\":"+(player==null?-1:player.r)
            +",\"nativeServerId\":"+q(engine.bX.ac())+",\"seed\":"+engine.bJ+",\"setupSeed\":"+engine.bX.ay.q+",\"networked\":"+engine.bX.B+",\"networkStarted\":"+engine.bX.aW
            +",\"localPlayerBound\":"+(player!=null&&player==engine.bX.z)+",\"nativeConnections\":"+engine.bX.C()+",\"fog\":"+engine.bX.ay.d+",\"fogEnabled\":"+(engine.bL!=null&&engine.bL.E)+",\"lineOfSightFog\":"+(engine.bL!=null&&engine.bL.F)
            +",\"ticks\":"+ticks+",\"frame\":"+engine.bx+",\"gameTimeMs\":"+engine.by+",\"wallSeconds\":"+((System.nanoTime()-started)/1e9)
            +",\"desyncErrors\":"+engine.bX.ap+",\"resyncCount\":"+engine.bX.ar+",\"nativeChecksum\":"+engine.bX.am.a+",\"checksumFrame\":"+engine.bX.ah+",\"nativeResult\":"+q(result)
            +",\"nativeVictory\":"+engine.dq+",\"nativeDefeat\":"+engine.dt+",\"nativeResultSource\":"+q(resultSource)
            +",\"nativePlayerFlags\":"+(player==null?"null":"{\"F\":"+player.F+",\"G\":"+player.G+",\"H\":"+player.H+",\"I\":"+player.I+",\"J\":"+player.J+"}")
            +",\"probeQueued\":"+probeQueued+",\"probeUnitId\":"+(probeUnit==null?-1:probeUnit.eh)+",\"probeDistance\":"+(probeUnit==null?0:Math.hypot(probeUnit.eo-probeX,probeUnit.ep-probeY))
            +",\"probeTargetX\":"+probeTargetX+",\"probeTargetY\":"+probeTargetY+"}\n";
        Path temp=Paths.get("runtime.json.tmp");Files.write(temp,data.getBytes(StandardCharsets.UTF_8));Files.move(temp,Paths.get("runtime.json"),StandardCopyOption.REPLACE_EXISTING);
    }
    static String q(String value){return value==null?"null":Json.quote(value);}
    static String get(Map<String,String> o,String key,String def){return o.containsKey(key)?o.get(key):def;}
    static boolean flag(Map<String,String> o,String key,boolean def){String value=get(o,key,Boolean.toString(def));if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException("--"+key+" requires true or false");return Boolean.parseBoolean(value);}
    static String sha(Path path)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[65536];for(int count;(count=in.read(b))>=0;)digest.update(b,0,count);}
        StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
}
