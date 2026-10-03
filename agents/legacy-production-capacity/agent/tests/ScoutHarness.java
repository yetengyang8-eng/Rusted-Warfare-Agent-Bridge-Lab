import com.corrodinggames.rts.game.units.am;
import java.util.*;
import java.lang.reflect.Array;

/** Native fog/cost/action fixtures, deliberately poisoned hidden terrain and hidden costs. */
public final class ScoutHarness {
    static Map<?,?> get(String path)throws Exception {return EconomyHarness.obj(BridgeHarness.check("GET",path,200,"sessionId"));}
    static void require(boolean value,String why){BridgeHarness.require(value,why);}
    static int count(Map<?,?> observation,String key){return ((Number)observation.get(key)).intValue();}
    static String attemptCause(Map<?,?> plan,int level){
        List<?> attempts=(List<?>)plan.get("attempts");
        return (String)((Map<?,?>)attempts.get(level)).get("cause");
    }
    static int attemptNumber(Map<?,?> plan,int level,String key){
        List<?> attempts=(List<?>)plan.get("attempts");
        return ((Number)((Map<?,?>)attempts.get(level)).get(key)).intValue();
    }
    public static void main(String[] args)throws Exception {
        try {
            OpeningHarness.test();com.corrodinggames.rts.game.i e=EconomyHarness.engine;
            Object costs=Array.newInstance(Class.forName("com.corrodinggames.rts.gameFramework.k.i"),1);Array.set(costs,0,EconomyHarness.costs);
            EconomyHarness.set(e.bU,"v",costs);e.cf.b.clear();int units=am.bE.size();double credits=e.bs.o;
            for(byte[] column:e.bs.N)Arrays.fill(column,(byte)10);
            for(int c=35;c<=60;c++)for(int r=75;r<=103;r++)e.bs.N[c][r]=0;
            java.lang.reflect.Field field=e.bL.u.getClass().getDeclaredField("q");field.setAccessible(true);short[] cells=(short[])field.get(e.bL.u);
            cells[0]=32767; // Native terrain lookup would fail if the hidden tile were read.
            Map<?,?> observed=get("/scout/observe");require(((List<?>)observed.get("resources")).size()==2,"only visible resource sites observed");
            require(((Number)observed.get("exploredTiles")).intValue()==26*29,"visibility baseline exact");
            require(count(observed,"mapMemoryCurrentVisibleTiles")==26*29
                    && count(observed,"mapMemoryNeverSeenTiles")==12100-26*29
                    && count(observed,"mapMemoryRecentFogTiles")==0
                    && count(observed,"mapMemoryStaleFogTiles")==0
                    && count(observed,"mapMemoryDeepFogTiles")==0,
                    "Map Memory begins with only legally visible and never-seen tiles");
            // Keep one previously observed cell hidden while game time crosses both age thresholds.
            // Every other tile remains visible, so the five categories must partition the whole map.
            e.bs.N[40][80]=10;e.by+=44000;observed=get("/scout/observe");
            require(count(observed,"mapMemoryCurrentVisibleTiles")==26*29-1
                    && count(observed,"mapMemoryRecentFogTiles")==1
                    && count(observed,"mapMemoryStaleFogTiles")==0
                    && count(observed,"mapMemoryDeepFogTiles")==0
                    && count(observed,"mapMemoryNeverSeenTiles")==12100-26*29,
                    "a legally seen tile hidden for less than 45 seconds is recent fog");
            e.by+=1000;observed=get("/scout/observe");
            require(count(observed,"mapMemoryRecentFogTiles")==0
                    && count(observed,"mapMemoryStaleFogTiles")==1
                    && count(observed,"mapMemoryDeepFogTiles")==0,
                    "45-second old fog becomes stale at the exact boundary");
            e.by+=75000;observed=get("/scout/observe");
            require(count(observed,"mapMemoryStaleFogTiles")==0
                    && count(observed,"mapMemoryDeepFogTiles")==1,
                    "120-second old fog becomes deep at the exact boundary");
            e.bs.N[40][80]=0;observed=get("/scout/observe");
            require(count(observed,"mapMemoryCurrentVisibleTiles")==26*29
                    && count(observed,"mapMemoryDeepFogTiles")==0
                    && count(observed,"mapMemoryNeverSeenTiles")==12100-26*29,
                    "legal re-observation refreshes a deep-fog tile without inventing new terrain");
            e.bs.N[40][80]=10;e.by+=1000;observed=get("/scout/observe");
            require(count(observed,"mapMemoryRecentFogTiles")==1
                    && count(observed,"mapMemoryDeepFogTiles")==0,
                    "a refreshed tile returns to recent fog when hidden again");
            e.bs.N[40][80]=0;
            Map<?,?> plan=get("/scout/plan?unitId=4");require("planned".equals(plan.get("status")),"frontier exists");
            for(Object t:(List<?>)plan.get("routeTiles")){int tile=((Number)t).intValue();require(e.bs.N[tile/110][tile%110]<5,"every planned route cell is visible");}
            EconomyHarness.set(Class.forName("com.corrodinggames.rts.game.units.e.n"),"b",
                    BridgeHarness.construct("com.corrodinggames.rts.gameFramework.m.e"));
            com.corrodinggames.rts.game.units.ar tankType=com.corrodinggames.rts.game.units.ar.valueOf("tank");
            tankType.h();
            am reconTank=tankType.a(true);
            reconTank.eh=191;reconTank.bX=e.bs;reconTank.eo=940;reconTank.ep=1780;reconTank.cm=1;am.bE.add(reconTank);
            Map<?,?> reconPlan=get("/scout/plan?role=recon&unitId=191");
            require("planned".equals(reconPlan.get("status")),"own armed mobile unit receives a Recon frontier plan");
            require("NEVER_SEEN_EDGE".equals(reconPlan.get("frontierMemoryClass"))
                    && count(reconPlan,"potentialNeverSeenTiles")>0
                    && count(reconPlan,"potentialDeepFogTiles")==0
                    && count(reconPlan,"potentialStaleFogTiles")==0,
                    "Recon prefers a legal boundary with never-seen information, not nearby recent fog");
            require(Boolean.TRUE.equals(reconPlan.get("pathKnown")),"Recon route uses only legally learned passability");
            for(Object t:(List<?>)reconPlan.get("routeTiles")){
                int tile=((Number)t).intValue();
                require(e.bs.N[tile/110][tile%110]<5,"initial Recon route stays on visible native tiles");
            }
            // Hidden costs cannot change the frontier selected from the visible connected component.
            byte[] blocked=new byte[12100];for(int c=0;c<110;c++)for(int r=0;r<110;r++)if(e.bs.N[c][r]>=5)blocked[c*110+r]=-1;
            EconomyHarness.set(EconomyHarness.costs,"d",blocked);
            Map<?,?> same=get("/scout/plan?unitId=4");require(plan.get("targetTile").equals(same.get("targetTile")),"hidden passability does not alter target");
            Map<?,?> reconSame=get("/scout/plan?role=recon&unitId=191");
            require("planned".equals(reconSame.get("status"))
                    && reconPlan.get("frontierTile").equals(reconSame.get("frontierTile"))
                    && reconPlan.get("targetTile").equals(reconSame.get("targetTile")),
                    "poisoning all hidden costs cannot alter the Recon frontier or waypoint");
            // A legally visible armed enemy becomes a remembered threat when its old tile goes dark.
            // A Recon plan may route around that memory or report an exhausted safe frontier.
            List<?> originalRoute=(List<?>)reconPlan.get("routeTiles");
            int threatTile=((Number)originalRoute.get(originalRoute.size()-1)).intValue();
            int threatCol=threatTile/110,threatRow=threatTile%110;
            am enemyTank=tankType.a(true);
            enemyTank.eh=192;enemyTank.bX=new com.corrodinggames.rts.game.e(1,false);
            enemyTank.bX.r=1;e.bs.r=0;enemyTank.eo=(threatCol+.5f)*20;enemyTank.ep=(threatRow+.5f)*20;
            am.bE.add(enemyTank);
            Map<?,?> threatVisible=get("/scout/observe");
            require(!((List<?>)threatVisible.get("visibleThreats")).isEmpty(),"native visibility admits the armed enemy as a legal threat");
            e.bs.N[threatCol][threatRow]=10;am.bE.remove(enemyTank);
            Map<?,?> threatHidden=get("/scout/observe");
            require(!((List<?>)threatHidden.get("rememberedThreats")).isEmpty(),"hidden former enemy location retains only legal threat memory");
            Map<?,?> rememberedThreat=(Map<?,?>)((List<?>)threatHidden.get("rememberedThreats")).get(0);
            Map<?,?> saferRecon=get("/scout/plan?role=recon&unitId=191");
            if("planned".equals(saferRecon.get("status"))){
                double tx=((Number)rememberedThreat.get("x")).doubleValue();
                double ty=((Number)rememberedThreat.get("y")).doubleValue();
                double dangerRadius=((Number)rememberedThreat.get("range")).doubleValue()+80;
                for(Object t:(List<?>)saferRecon.get("routeTiles")){
                    int tile=((Number)t).intValue();double x=(tile/110+.5)*20,y=(tile%110+.5)*20;
                    require(Math.hypot(x-tx,y-ty)>=dangerRadius,
                            "Recon known route stays outside a legally remembered attack circle");
                }
            }else require("no_frontier".equals(saferRecon.get("status"))
                    && count(saferRecon,"dangerBlockedTiles")>0,
                    "a remembered threat can exhaust the safe Recon frontier without reading hidden truth");
            e.bs.N[threatCol][threatRow]=0;get("/scout/observe");am.bE.remove(reconTank);
            for(byte[] column:e.bs.N)Arrays.fill(column,(byte)10);e.bs.N[47][89]=0;
            Map<?,?> remembered=get("/scout/plan?unitId=4");
            require(Boolean.TRUE.equals(remembered.get("pathKnown")) && Boolean.FALSE.equals(remembered.get("pathVisible")),"previously observed route remains usable after fog returns");
            for(int c=35;c<=60;c++)for(int r=75;r<=103;r++)e.bs.N[c][r]=0;
            for(int r=75;r<=103;r++)blocked[52*110+r]=-1;
            Map<?,?> mine=get("/expansion/plan?unitId=4");require(((Number)mine.get("extractorX")).intValue()==990,"reachable mine retained");
            Map<?,?> diag=(Map<?,?>)mine.get("diagnostics");require(((Number)diag.get("notReachable")).intValue()==1,"other side of visible barrier is rejected");
            blocked[52*110+80]=0;mine=get("/expansion/plan?unitId=4");diag=(Map<?,?>)mine.get("diagnostics");
            require(((Number)diag.get("legalCandidates")).intValue()==2,"visible detour makes second mine reachable");
            // Resource first seen after natural-visibility fixture update is marked separately.
            cells[20*110+20]=1;e.bs.N[20][20]=0;e.bx=10;
            observed=get("/scout/observe");require(((List<?>)observed.get("resources")).size()==3,"newly visible mine is remembered");
            require(((Number)observed.get("newlyObservedTiles")).intValue()==1,"one newly observed tile");
            e.bs.N[20][20]=10;observed=get("/scout/observe");require(((List<?>)observed.get("resources")).size()==3,"known resource memory survives loss of sight");
            e.bx=0;observed=get("/scout/observe");require(((List<?>)observed.get("resources")).size()==2,"new session clears old memory");
            // ---- Search ladder: soft heuristics may choose a target, never prove exhaustion. ----
            // Observed tiles stay observed for the session, so the unseen set only ever shrinks.
            Arrays.fill(blocked,(byte)0);
            for(byte[] column:e.bs.N)Arrays.fill(column,(byte)10);
            for(int c=0;c<110;c++)for(int r=21;r<110;r++)e.bs.N[c][r]=0;
            Map<?,?> farPlan=get("/scout/plan?unitId=4");
            require("planned".equals(farPlan.get("status")),"a frontier beyond the fast radius is still planned");
            require(((Number)farPlan.get("fallbackLevel")).intValue()==4,"far frontier escalates to the exhaustive level");
            require("GLOBAL_EXHAUSTIVE".equals(farPlan.get("fallbackName")),"the exhaustive level is named in the plan");
            require("POTENTIAL_FILTER".equals(attemptCause(farPlan,0)),"the fast level genuinely had no candidate");
            // One unseen tile is left, so its window has potential 1 and only the last local level wins.
            // Observed tiles can never become unknown again, so rotate the session with the same frame
            // trick used above and hide the tile from the very first observation of the new session.
            // The earlier hidden-terrain poison covered tile 0; it already proved its point, so restore
            // that cell before this scenario legitimately makes the whole map visible.
            e.bx=-1;cells[0]=0;
            for(byte[] column:e.bs.N)Arrays.fill(column,(byte)0);
            e.bs.N[47][79]=10;
            Map<?,?> thinPlan=get("/scout/plan?unitId=4");
            require("planned".equals(thinPlan.get("status")),"a single-tile frontier is still planned");
            require(((Number)thinPlan.get("fallbackLevel")).intValue()==3,"potential fallback must relax to 1");
            require("POTENTIAL_FILTER".equals(attemptCause(thinPlan,0)),"the quality threshold blocked the fast level");
            // The only frontier sits inside the soft avoid window: only the exhaustive level overrides it.
            StringBuilder avoid=new StringBuilder();
            for(int c=40;c<=54;c+=7)for(int r=72;r<=86;r+=7){if(avoid.length()>0)avoid.append(',');avoid.append(c*110+r);}
            Map<?,?> avoidedPlan=get("/scout/plan?unitId=4&avoid="+avoid);
            require("planned".equals(avoidedPlan.get("status")),"soft avoid may not make the map look empty");
            require(((Number)avoidedPlan.get("fallbackLevel")).intValue()==4,"only the exhaustive level overrides soft avoid");
            require(attemptNumber(avoidedPlan,3,"candidates")==0,"soft avoid vetoed the same target one level earlier");
            require(attemptNumber(avoidedPlan,4,"candidates")>0,"dropping soft avoid in the exhaustive level exposes the target");
            // A frontier whose approach is genuinely impassable must never be chosen.
            for(int c=39;c<=55;c++)for(int r=71;r<=87;r++)blocked[c*110+r]=-1;
            Map<?,?> forbiddenPlan=get("/scout/plan?unitId=4");
            require("no_frontier".equals(forbiddenPlan.get("status")),"hard forbidden terrain cannot be bypassed");
            require("KNOWN_REACHABLE_FRONTIER_EXHAUSTED".equals(forbiddenPlan.get("finalCause")),"impassable approach is a true exhausted state");
            for(int c=39;c<=55;c++)for(int r=71;r<=87;r++)blocked[c*110+r]=0;
            // Nothing unseen is left anywhere, so the terminal state replaces repeating no_frontier.
            for(byte[] column:e.bs.N)Arrays.fill(column,(byte)0);
            Map<?,?> exhaustedPlan=get("/scout/plan?unitId=4");
            require("no_frontier".equals(exhaustedPlan.get("status")),"fully explored reachable space has no frontier");
            require("KNOWN_REACHABLE_FRONTIER_EXHAUSTED".equals(exhaustedPlan.get("finalCause")),"terminal cause is the exhaustive state");
            require("NO_REACHABLE_UNVISITED_FRONTIER".equals(exhaustedPlan.get("reason")),"legacy reason string is preserved");
            require(((Number)exhaustedPlan.get("fallbackLevel")).intValue()==4,"every level was tried before giving up");
            BridgeHarness.check("GET","/scout/plan?unitId=99",409,"own mobile");
            BridgeHarness.check("GET","/scout/plan?unitId=4&unitId=4",400,"duplicate");
            BridgeHarness.check("GET","/scout/plan?avoid=-1",400,"invalid");
            BridgeHarness.check("POST","/scout/plan",405,"GET required");
            e.bX.B=true;BridgeHarness.check("GET","/scout/observe",409,"network");e.bX.B=false;
            require(e.cf.b.isEmpty() && am.bE.size()==units && e.bs.o==credits,"all scouting reads leave game objects, credits and commands unchanged");
            System.out.println("SCOUT_NATIVE_TEST_OK checks="+BridgeHarness.checks);System.exit(0);
        }catch(Throwable error){error.printStackTrace();System.exit(1);}
    }
}
