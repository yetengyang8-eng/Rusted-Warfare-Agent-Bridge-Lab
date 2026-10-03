import com.corrodinggames.rts.game.units.*;
import io.rwagent.bootstrap.RuntimeBridge;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** User hypothesis probe only. Loaded unchanged supplied Spain TMX, real native
 * objects and real HTTP; no desktop, natural route, simulation or fire result. */
public final class G3SpainTerrainNativeHarness {
    static final String MAP="mods/maps/[10p] 10p 西班牙混战_by_MP97.tmx";
    static com.corrodinggames.rts.game.i engine;
    static void log(String event,Object data)throws Exception{G3NativeAcceptanceHarness.record(event,data);}
    static Map<String,Object> map(Object... pairs){return G3NativeAcceptanceHarness.map(pairs);}
    static Map<String,Object> tile(int x,int y)throws Exception{
        Object t=engine.bL.u.a(x,y),tileset=t==null?null:G3NativeAcceptanceHarness.value(t,"a");
        int index=x*engine.bL.D+y;
        return map("tileX",x,"tileY",y,"x",x*engine.bL.n+10,"y",y*engine.bL.o+10,
            "tileset",tileset==null?null:G3NativeAcceptanceHarness.value(tileset,"a"),"bitmap",tileset==null?null:G3NativeAcceptanceHarness.value(tileset,"c"),"tileId",t==null?null:G3NativeAcceptanceHarness.value(t,"b"),
            "groundWaterFlag",t!=null&&Boolean.TRUE.equals(G3NativeAcceptanceHarness.value(t,"e")),"waterBridgeFlag",t!=null&&Boolean.TRUE.equals(G3NativeAcceptanceHarness.value(t,"f")),
            "groundLavaFlag",t!=null&&Boolean.TRUE.equals(G3NativeAcceptanceHarness.value(t,"g")),"overWaterNative",engine.bU.a(x,y),
            "LANDCost",(int)engine.bU.a(ao.b).d[index],"WATERCost",(int)engine.bU.a(ao.e).d[index],
            "HOVERCost",(int)engine.bU.a(ao.f).d[index],"OVER_CLIFF_WATERCost",(int)engine.bU.a(ao.g).d[index]);
    }
    static Map<String,Object> witness(G3NativeAcceptanceHarness.Client c,y actor,am submerged,am land)throws Exception{
        Map<String,Object> modes=c.read("/combat/unit-modes?unitId="+actor.eh);
        Map<String,Object> submergedEngagement=c.read("/combat/engagement?unitIds="+actor.eh+"&targetId="+submerged.eh);
        Map<String,Object> groundEngagement=c.read("/combat/engagement?unitIds="+actor.eh+"&targetId="+land.eh);
        return map("actor",actor.eh,"terrain",tile((int)(actor.eo/engine.bL.n),(int)(actor.ep/engine.bL.o)),
            "nativeHeight",actor.eq,"desiredMovementType",actor.h().name(),"physicalSubmerged",actor.Q(),
            "submergedWeaponAvailable",actor.ae(),"nativeOverWater",actor.cJ(),"independentUnitModes",modes,
            "legalBridgeEngagementSubmergedTarget",submergedEngagement,"legalBridgeEngagementGroundTarget",groundEngagement,
            "submergedTarget",map("id",submerged.eh,"physicalSubmerged",submerged.Q(),"targetOverWater",submerged.cJ(),
                "nativeDomainCompatible",actor.k(submerged),"nativeAttackSelectionInRange",actor.a(submerged,false),"nativeWeaponEligible",actor.i(submerged)),
            "groundTarget",map("id",land.eh,"physicalSubmerged",land.Q(),"targetOverWater",land.cJ(),
                "nativeDomainCompatible",actor.k(land),"nativeAttackSelectionInRange",actor.a(land,false),"nativeWeaponEligible",actor.i(land)),
            "fireOrDamageProven",false,"accessorBasis","PASSIVE_NATIVE_Y_K_DOMAIN_Y_A_TARGET_TEAM_RANGE_Y_I_WEAPON_ELIGIBILITY");
    }
    static void position(y actor,Map<String,Object> cell)throws Exception{
        actor.eo=((Number)cell.get("x")).floatValue();actor.ep=((Number)cell.get("y")).floatValue();
        log("fixture_position",map("actor",actor.eh,"x",actor.eo,"y",actor.ep,"basis","EXPLICIT_POSITION_NOT_NATURAL_ROUTE"));
    }
    static void targetPosition(am a,y actor){a.eo=actor.eo+10;a.ep=actor.ep;}
    public static void main(String[] args){try{
        if(args.length!=1)throw new IllegalArgumentException("OUTPUT_DIRECTORY");
        G3NativeAcceptanceHarness.out=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(G3NativeAcceptanceHarness.out);
        G3NativeAcceptanceHarness.fixture=Files.newBufferedWriter(G3NativeAcceptanceHarness.out.resolve("spain-fixture.jsonl"),StandardCharsets.UTF_8);
        System.setProperty("rwagent.port",String.valueOf(G3NativeAcceptanceHarness.PORT));System.setProperty("rwagent.g3Execution","true");
        System.setProperty("rwagent.g1Trace","true");System.setProperty("rwagent.g2WorldState","true");System.setProperty("rwagent.additionalDiagnostics","true");
        Method initialize=TerrainNativeCostHarness.class.getDeclaredMethod("initialize",String.class);initialize.setAccessible(true);
        engine=(com.corrodinggames.rts.game.i)initialize.invoke(null,MAP);G3NativeAcceptanceHarness.engine=engine;
        for(byte[] column:engine.bs.N)Arrays.fill(column,(byte)0);
        Thread pump=new Thread(()->{while(true){Runnable r=(Runnable)engine.k.poll();if(r!=null)r.run();try{Thread.sleep(1);}catch(InterruptedException e){return;}}},"spain-native-task-pump");pump.setDaemon(true);pump.start();RuntimeBridge.start(engine,G3NativeAcceptanceHarness.PORT,true);
        log("scope",map("evidence","E2_NATIVE_FIXTURE_WITH_REAL_HTTP","naturalMatch","NO_NATURAL_MATCH","map",MAP,"mapProvenance","SUPPLIED_MODS_MAP_NOT_BUILT_IN_ORIGINAL_MAP","mapUnchanged",true,"desktopTouched",false,"simulationTicks",0));
        Map<String,Object> water=null,mixed=null,shallow=null,dry=null;Map<String,Integer> categories=new TreeMap<String,Integer>();Map<String,Map<String,Object>> examples=new TreeMap<String,Map<String,Object>>();
        for(int x=2;x<engine.bL.C-2;x++)for(int y=2;y<engine.bL.D-2;y++){
            Map<String,Object> t=tile(x,y);String key="wet="+t.get("overWaterNative")+",water="+t.get("WATERCost")+",land="+t.get("LANDCost")+",flag="+t.get("groundWaterFlag")+",bridge="+t.get("waterBridgeFlag");
            categories.put(key,categories.containsKey(key)?categories.get(key)+1:1);if(!examples.containsKey(key))examples.put(key,t);
            int sea=((Number)t.get("WATERCost")).intValue(),ground=((Number)t.get("LANDCost")).intValue();boolean wet=Boolean.TRUE.equals(t.get("overWaterNative"));
            if(water==null&&wet&&sea>=0)water=t;
            if(mixed==null&&!wet&&sea>=0)mixed=t;
            if(dry==null&&!wet&&ground>=0)dry=t;
            String bitmap=String.valueOf(t.get("bitmap"));if(shallow==null&&bitmap.toLowerCase(Locale.ROOT).contains("shallow")&&!wet)shallow=t;
        }
        log("map_native_terrain_categories",map("counts",categories,"examples",examples,"mixedWaterPassableButDiveUnavailable",mixed,"namedShallowNotWater",shallow));
        G3NativeAcceptanceHarness.require(water!=null&&dry!=null,"loaded map contains native wet and dry examples");
        Map<String,Object> third=mixed!=null?mixed:shallow!=null?shallow:dry;
        am.bE.clear();engine.cf.b.clear();engine.bs.o=20000;engine.bs.r=0;
        y actor=(y)G3NativeAcceptanceHarness.unit("amphibiousJet",81,engine.bs,((Number)water.get("x")).floatValue(),((Number)water.get("y")).floatValue());
        com.corrodinggames.rts.game.n other=new com.corrodinggames.rts.game.e(1,false);other.r=1;
        am sub=G3NativeAcceptanceHarness.unit("amphibiousJet",230,other,actor.eo+10,actor.ep);sub.eq=-5;
        am ground=G3NativeAcceptanceHarness.unit("tank",231,other,actor.eo+10,actor.ep);
        G3NativeAcceptanceHarness.Client c=new G3NativeAcceptanceHarness.Client("spain",40,false);String owner="strategy:terrain-probe";
        G3NativeAcceptanceHarness.require(c.arbiter.claim(owner,81),"actor ownership uses real arbiter");
        log("normal_water_air",witness(c,actor,sub,ground));
        G3NativeAcceptanceHarness.require(c.battle.orderStrategy(owner,"/command/unit-mode?unitId=81&actionId=152")!=null,"normal water actual BC scheduler sends Dive");G3NativeAcceptanceHarness.process();
        log("normal_water_desired_dive",witness(c,actor,sub,ground));actor.eq=-5;
        // s=false is the native stable-submerged phase after M(), distinct from a pending Dive.
        G3NativeAcceptanceHarness.set(actor,"s",false);
        log("fixture_height",map("actor",81,"eq",-5,"nativeSurfaceTransitionFlag_s",false,"basis","EXPLICIT_STABLE_SUBMERGED_FIXTURE_NOT_NATURAL_DIVE"));c.advance(1000,false);
        log("normal_water_physical_submerged",witness(c,actor,sub,ground));
        position(actor,third);targetPosition(sub,actor);targetPosition(ground,actor);c.advance(1000,false);
        log("third_terrain_arrival_submerged",witness(c,actor,sub,ground));
        G3NativeAcceptanceHarness.require(actor.Q()&&actor.ae(),"physical fixture remains underwater despite current Dive eligibility");
        G3NativeAcceptanceHarness.require(c.battle.orderStrategy(owner,"/command/unit-mode?unitId=81&actionId=151")!=null,"actual BC sends Fly on third terrain");G3NativeAcceptanceHarness.process();actor.eq=20;c.advance(1000,false);
        Map<String,Object> dryWitness=witness(c,actor,sub,ground);log("third_terrain_air",dryWitness);
        Map<String,Object> rejected=c.battle.orderStrategy(owner,"/command/unit-mode?unitId=81&actionId=152");
        G3NativeAcceptanceHarness.require(rejected==null&&!actor.cJ()&&engine.cf.b.isEmpty(),"actual native HTTP rejects direct Dive at nonwater third terrain");
        log("third_terrain_direct_dive_rejected",map("ledger",c.ledger(),"physicalSubmerged",actor.Q(),"submergedWeaponAvailable",actor.ae(),"nativeOverWater",actor.cJ()));c.close();
        Map<String,Object> summary=map("status",mixed==null?"NEEDS_EVIDENCE":"PASS_BOUNDED_ACCESSORS","checks",G3NativeAcceptanceHarness.checks,"thirdTerrain",third,"nativeWaterPassableDiveUnavailableFound",mixed!=null,"submergedEntryNaturalRouteProven",false,"attackFireProven",false,"conclusion","DIRECT_DIVE_GUARD_AND_PHYSICAL_WEAPON_DOMAIN_SEPARATED; NATURAL_TRAVERSAL_AND_NO_ATTACK_HYPOTHESIS_NEED_EVIDENCE","evidence","E2_NATIVE_FIXTURE_WITH_REAL_HTTP");
        Files.write(G3NativeAcceptanceHarness.out.resolve("spain-summary.json"),G3NativeAcceptanceHarness.json(summary).getBytes(StandardCharsets.UTF_8));log("summary",summary);G3NativeAcceptanceHarness.fixture.close();System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
