import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;
import com.corrodinggames.rts.game.units.y;
import java.util.*;
public final class CombatHarness {
    static void check(boolean v,String s){BridgeHarness.require(v,s);}
    static String call(String method,String path,int status,String part)throws Exception{return BridgeHarness.check(method,path,status,part);}
    static Map<?,?> get(String path)throws Exception{return EconomyHarness.obj(call("GET",path,200,"sessionId"));}
    static Map<?,?> intel(Map<?,?> observation,long enemyId){
        for(Object item:(List<?>)observation.get("enemyIntel")){
            Map<?,?> contact=(Map<?,?>)item;
            if(((Number)contact.get("id")).longValue()==enemyId)return contact;
        }
        throw new AssertionError("missing enemyIntel id="+enemyId);
    }
    public static void main(String[] args){
        try{
            OpeningHarness.test();com.corrodinggames.rts.game.i e=EconomyHarness.engine;e.cf.b.clear();
            EconomyHarness.set(Class.forName("com.corrodinggames.rts.game.units.e.n"),"b",BridgeHarness.construct("com.corrodinggames.rts.gameFramework.m.e"));
            for(int i=91;i<=92;i++){am t=ar.valueOf("tank").a(true);t.eh=i;t.bX=e.bs;t.eo=900+i;t.ep=1750;am.bE.add(t);}
            String session=(String)get("/state").get("sessionId");
            String suffix="&sessionId="+session+"&requestId=";
            String path="/command/attack-move?unitIds=91,92&x=1010&y=1750"+suffix+"attack1";
            call("POST",path,200,"attackMove");call("POST",path,200,"attackMove");check(e.cf.b.size()==1,"duplicate group receipt queues once");
            com.corrodinggames.rts.gameFramework.e order=(com.corrodinggames.rts.gameFramework.e)e.cf.b.get(0);
            check("attackMove".equals(order.j.d().name()),"native attackMove enum");
            e.bV=new com.corrodinggames.rts.gameFramework.aa();order.k();
            call("GET","/state",200,"\"orderX\":1010.000");
            call("POST",path.replace("attack1","badactor").replace("91,92","91,4"),409,"armed");
            call("POST",path.replace("attack1","duplicate").replace("91,92","91,91"),400,"duplicate");
            call("POST",path.replace("x=1010","x=NaN").replace("attack1","nan"),400,"finite");
            call("POST",path.replace("x=1010","x=2200").replace("attack1","outside"),400,"in-map");
            call("POST",path.replace("x=1010","x=1050"),409,"reused");
            call("POST",path.replace(session,"stale").replace("attack1","stale"),409,"session");
            check(e.cf.b.size()==1,"all invalid group requests leave native queue unchanged");
            am enemy=ar.valueOf("builder").a(true);enemy.eh=99;enemy.bX=new com.corrodinggames.rts.game.e(1,false);enemy.bX.r=1;e.bs.r=0;enemy.eo=110;enemy.ep=110;am.bE.add(enemy);
            e.bs.N[5][5]=10;
            Map<?,?> neverSeen=get("/combat/observe");
            check(((List<?>)neverSeen.get("visibleEnemies")).isEmpty(),"hidden unarmed enemies are not exposed");
            check(((List<?>)neverSeen.get("enemyIntel")).isEmpty(),"unseen enemy has no war-memory contact");
            e.bs.N[5][5]=0;Map<?,?> seen=get("/combat/observe");
            check(((List<?>)seen.get("visibleEnemies")).size()==1,"visible unarmed builder is a valid combat observation");
            Map<?,?> saved=(Map<?,?>)((List<?>)seen.get("visibleEnemies")).get(0);
            Map<?,?> seenIntel=intel(seen,99);
            check("VISIBLE".equals(seenIntel.get("status"))&&saved.get("x").equals(seenIntel.get("lastKnownX")),"visible enemy becomes a legal historical contact");
            e.bs.N[5][5]=10;e.bs.N[10][10]=10;enemy.eo=210;enemy.ep=210;enemy.cu=1;e.by+=1000;
            Map<?,?> hidden=get("/combat/observe");Map<?,?> remembered=(Map<?,?>)((List<?>)hidden.get("rememberedEnemies")).get(0);
            Map<?,?> lost=intel(hidden,99);
            check(((List<?>)hidden.get("visibleEnemies")).isEmpty(),"hidden moving enemy not exported");
            check(saved.get("x").equals(remembered.get("x"))&&saved.get("hp").equals(remembered.get("hp")),"memory never refreshes hidden position or HP");
            check("LOST_CONTACT".equals(lost.get("status"))&&saved.get("x").equals(lost.get("lastKnownX"))
                &&saved.get("y").equals(lost.get("lastKnownY"))&&saved.get("hp").equals(lost.get("lastKnownHp"))
                &&saved.get("lastSeenGameTimeMs").equals(lost.get("lastSeenGameTimeMs")),"war memory never refreshes hidden attributes or sighting time");
            check(Boolean.FALSE.equals(lost.get("lastKnownSiteVisible"))&&lost.get("clearedGameTimeMs")==null,"hidden former site has no clearance evidence");
            check(((Number)hidden.get("enemyIntelVisible")).intValue()==0&&((Number)hidden.get("enemyIntelLostContact")).intValue()==1,"war-memory counts distinguish lost contact");
            e.bs.N[5][5]=0;Map<?,?> oldSpotEmpty=get("/combat/observe");
            check(((List<?>)oldSpotEmpty.get("rememberedEnemies")).isEmpty(),"visible empty last-seen location clears stale tactical memory");
            Map<?,?> mobileOldSpot=intel(oldSpotEmpty,99);
            check("LOST_CONTACT".equals(mobileOldSpot.get("status"))&&Boolean.FALSE.equals(mobileOldSpot.get("lastKnownSiteVisible"))
                &&mobileOldSpot.get("clearedGameTimeMs")==null,"a mobile builder remains lost contact even after its old site is visible");
            check(((Number)oldSpotEmpty.get("enemyIntelLostContact")).intValue()==1
                &&((Number)oldSpotEmpty.get("enemyIntelCleared")).intValue()==0,"mobile old-site visibility cannot increment cleared building count");
            e.bs.N[10][10]=0;e.by+=1000;Map<?,?> reacquired=get("/combat/observe");
            Map<?,?> reacquiredIntel=intel(reacquired,99);
            check("VISIBLE".equals(reacquiredIntel.get("status"))&&((Number)reacquiredIntel.get("lastKnownX")).intValue()==210
                &&((Number)reacquiredIntel.get("lastKnownHp")).intValue()==1
                &&reacquired.get("gameTimeMs").equals(reacquiredIntel.get("lastSeenGameTimeMs"))
                &&reacquiredIntel.get("clearedGameTimeMs")==null,"new legal sighting refreshes the same mobile contact and time");
            // A separate enemy building exercises the legal 3x3 former-site clearance rule.
            am enemyFactory=ar.b.a(true);enemyFactory.eh=199;enemyFactory.bX=enemy.bX;
            enemyFactory.eo=610;enemyFactory.ep=610;enemyFactory.cm=1;am.bE.add(enemyFactory);
            check(enemyFactory.bI()&&"landFactory".equals(enemyFactory.r().i()),"native enemy landFactory is a building contact");
            int oldCol=(int)Math.floor(enemyFactory.eo/e.bL.n),oldRow=(int)Math.floor(enemyFactory.ep/e.bL.o);
            for(int cx=oldCol-1;cx<=oldCol+1;cx++)for(int cy=oldRow-1;cy<=oldRow+1;cy++)e.bs.N[cx][cy]=0;
            e.by+=1000;Map<?,?> buildingSeen=get("/combat/observe");Map<?,?> buildingIntel=intel(buildingSeen,199);
            check("VISIBLE".equals(buildingIntel.get("status"))&&Boolean.TRUE.equals(buildingIntel.get("lastKnownBuilding"))
                &&"landFactory".equals(buildingIntel.get("lastKnownType")),"legally seen factory enters building war memory");
            check(((Number)buildingSeen.get("enemyIntelVisible")).intValue()==2,"builder and factory are both visible before hiding the factory");
            e.bs.N[oldCol][oldRow]=10;e.by+=1000;Map<?,?> buildingHidden=get("/combat/observe");
            Map<?,?> buildingLost=intel(buildingHidden,199);
            check("LOST_CONTACT".equals(buildingLost.get("status"))&&Boolean.FALSE.equals(buildingLost.get("lastKnownSiteVisible"))
                &&buildingLost.get("clearedGameTimeMs")==null,"hidden factory remains a historical contact");
            check(((Number)buildingHidden.get("enemyIntelVisible")).intValue()==1
                &&((Number)buildingHidden.get("enemyIntelLostContact")).intValue()==1,"hidden factory is counted as lost contact");
            am.bE.remove(enemyFactory);
            e.bs.N[oldCol][oldRow]=0;e.bs.N[oldCol+1][oldRow]=10;
            e.by+=1000;Map<?,?> buildingPartlyVisible=get("/combat/observe");
            Map<?,?> buildingPartial=intel(buildingPartlyVisible,199);
            check("LOST_CONTACT".equals(buildingPartial.get("status"))
                &&Boolean.FALSE.equals(buildingPartial.get("lastKnownSiteVisible")),
                "center-only visibility does not strand the lost factory outside Recon eligibility");
            for(int cx=oldCol-1;cx<=oldCol+1;cx++)for(int cy=oldRow-1;cy<=oldRow+1;cy++)e.bs.N[cx][cy]=0;
            e.by+=1000;Map<?,?> buildingOldSite=get("/combat/observe");Map<?,?> buildingCleared=intel(buildingOldSite,199);
            check("CLEARED".equals(buildingCleared.get("status"))&&Boolean.TRUE.equals(buildingCleared.get("lastKnownSiteVisible"))
                &&buildingCleared.get("clearedGameTimeMs")!=null,"visible empty factory site and its 3x3 neighborhood clear only that building contact");
            check(((Number)buildingOldSite.get("enemyIntelVisible")).intValue()==1
                &&((Number)buildingOldSite.get("enemyIntelLostContact")).intValue()==0
                &&((Number)buildingOldSite.get("enemyIntelCleared")).intValue()==1,"cleared building is counted separately from visible and lost contacts");
            am.bE.add(enemyFactory);e.by+=1000;Map<?,?> buildingReappeared=get("/combat/observe");
            Map<?,?> buildingReacquired=intel(buildingReappeared,199);
            check("VISIBLE".equals(buildingReacquired.get("status"))&&buildingReacquired.get("clearedGameTimeMs")==null
                &&buildingReappeared.get("gameTimeMs").equals(buildingReacquired.get("lastSeenGameTimeMs")),"same-ID legal factory sighting resets prior clearance time");
            check(((Number)buildingReappeared.get("enemyIntelVisible")).intValue()==2
                &&((Number)buildingReappeared.get("enemyIntelCleared")).intValue()==0,"reacquired factory returns to visible count");
            am.bE.remove(enemyFactory);e.bs.N[10][10]=10;
            am factory=ar.b.a(true);factory.eh=100;factory.bX=e.bs;factory.eo=1000;factory.ep=1900;am.bE.add(factory);
            Map<?,?> menu=get("/combat/production");Map<?,?> f=(Map<?,?>)((List<?>)menu.get("factories")).get(0);
            Map<?,?> upgrade=null;for(Object item:(List<?>)f.get("actions"))if("upgrade".equals(((Map<?,?>)item).get("type")))upgrade=(Map<?,?>)item;
            check(upgrade!=null,"upgrade comes from native factory menu");
            String q="/command/queue?unitId=100&actionId="+upgrade.get("actionId")+suffix+"upgrade";
            call("POST",q,200,"upgrade");call("POST",q,200,"queued");check(e.cf.b.size()==2,"upgrade retry queues once");
            ((com.corrodinggames.rts.gameFramework.e)e.cf.b.get(1)).k();
            Map<?,?> active=get("/combat/production");check(((Number)((Map<?,?>)((List<?>)active.get("factories")).get(0)).get("queue")).intValue()==1,"all-orders queue includes native upgrade");
            call("POST",q.replace("requestId=upgrade","requestId=busy"),409,"empty");
            e.dq=true;call("GET","/state",200,"\"outcome\":\"VICTORY\"");call("POST",path.replace("attack1","ended"),409,"ended");e.dq=false;
            e.dt=true;call("GET","/state",200,"\"outcome\":\"DEFEAT\"");e.dt=false;
            e.bs.F=true;call("GET","/state",200,"\"outcome\":\"ONGOING\"");e.bs.F=false;
            e.bx--;Map<?,?> nextSession=get("/combat/observe");e.bx++;
            check(!seen.get("sessionId").equals(nextSession.get("sessionId"))&&((List<?>)nextSession.get("enemyIntel")).isEmpty(),"session change clears war memory");
            e.bX.B=true;call("GET","/combat/observe",409,"network");
            System.out.println("COMBAT_NATIVE_TEST_OK checks="+BridgeHarness.checks);System.exit(0);
        }catch(Throwable t){t.printStackTrace();System.exit(1);}
    }
}
