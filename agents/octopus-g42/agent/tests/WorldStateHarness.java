package io.rwagent.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** G2 adversarial snapshot tests, engine-free. No policy reads the projection. */
public final class WorldStateHarness {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static Map<String,Object> m(Object... args){return WorldState.map(args);}
    private static List<Map<String,Object>> list(Map<String,Object>... rows){return new ArrayList<Map<String,Object>>(Arrays.asList(rows));}
    private static Map<String,Object> unit(long id,double hp,double progress,int queue){return m("id",id,"type","amphibiousJet","hp",hp,"maxHp",100.0,"buildProgress",progress,"productionQueue",queue,"dead",false,"x",10.0,"y",20.0);}
    private static Map<String,Object> state(String session,int player,long time,long frame,List<Map<String,Object>> units){return m("status","running","sessionId",session,"player",m("teamId",player,"credits",1000),"gameTimeMs",time,"frame",frame,"ownUnits",units);}
    private static Map<String,Object> enemy(long id,long time){return m("id",id,"type","submarine","hp",50.0,"x",120.0,"y",130.0,"building",false,"lastSeenGameTimeMs",time,"targetDomain","SUBMERGED","domainObservedAtGameTimeMs",time,"touchingWater",null);}
    private static Map<String,Object> combat(String session,long time,long frame,List<Map<String,Object>> enemies){return m("status","observed","sessionId",session,"gameTimeMs",time,"frame",frame,"visibleEnemies",enemies,"enemyIntel",new ArrayList<Map<String,Object>>());}
    private static final class Fixture {
        final GameClock clock;
        final EventAdapter adapter;
        long wall=100;
        Long detected;
        Fixture(String run){clock=new GameClock(run);adapter=new EventAdapter(run,1000,1000);}
        GameClock.Observation observation(String endpoint,Map<String,Object> payload){if("/state".equals(endpoint)&&payload.get("gameTimeMs") instanceof Number)detected=((Number)payload.get("gameTimeMs")).longValue();return clock.observe(endpoint,payload,++wall,++wall,detected);}
        EventAdapter.Update accept(String endpoint,Map<String,Object> payload){return adapter.accept(observation(endpoint,payload),payload);}
    }
    private static int count(EventAdapter.Update u,String kind){int n=0;for(EventAdapter.DerivedEvent e:u.events)if(kind.equals(e.kind))n++;return n;}
    private static EventAdapter.DerivedEvent event(EventAdapter.Update u,String kind){for(EventAdapter.DerivedEvent e:u.events)if(kind.equals(e.kind))return e;throw new AssertionError("missing "+kind);}
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object o){return (Map<String,Object>)o;}
    public static void main(String[] args){
        ownAndImmutability();enemyEvidence();enemyMeaningfulChanges();resetOrderingAndCoverage();scopedSourcesAndFreshness();determinismAndBounds();
        System.out.println("WorldStateHarness PASS checks="+checks+" evidence=E2_NO_ENGINE");
    }
    private static void ownAndImmutability(){
        Fixture f=new Fixture("own");Map<String,Object> row=unit(11,100,.4,1),packet=state("a",0,100,1,list(row));
        EventAdapter.Update initial=f.accept("/state",packet);WorldState frozen=initial.snapshot;
        check(initial.accepted&&frozen.ownUnits().get(11L).availability.equals("AVAILABLE"),"initial own point sample accepted");
        check(count(initial,"UNIT_FIRST_OBSERVED")==1&&count(initial,"UNIT_READY_OBSERVED")==0,"first seen construction is not ready");
        row.put("hp",1.0);packet.put("sessionId","mutation");
        check(frozen.ownUnits().get(11L).current.get("hp").equals(100.0),"nested caller mutation does not alter world");
        boolean immutable=false;try{frozen.views().values().iterator().next().payload.put("new",1);}catch(UnsupportedOperationException expected){immutable=true;}
        check(immutable,"source payload immutable");
        immutable=false;try{obj(((List<?>)frozen.views().values().iterator().next().payload.get("ownUnits")).get(0)).put("hp",99);}catch(UnsupportedOperationException expected){immutable=true;}
        check(immutable,"nested source row immutable");
        EventAdapter.Update next=f.accept("/state",state("a",0,200,2,list(unit(11,60,1,0))));
        check(count(next,"UNIT_HP_BAND_CHANGED")==1&&event(next,"UNIT_HP_BAND_CHANGED").data.get("newBand").equals(6),"defined numeric observational HP deciles");
        check(count(next,"UNIT_READY_OBSERVED")==1&&Boolean.FALSE.equals(event(next,"UNIT_READY_OBSERVED").data.get("producerLineageConfirmed")),"readiness observation no producer lineage");
        check(count(next,"QUEUE_BECAME_EMPTY")==1&&Boolean.FALSE.equals(event(next,"QUEUE_BECAME_EMPTY").data.get("acceptedUnobservedOccupancyReleased")),"queue empty cannot release commitment");
        check(next.toMap().get("commitmentsReconciled").equals(false)&&!next.toMap().containsKey("payload"),"compact update has no transaction or large packet");
        check(frozen.ownUnits().get(11L).current.get("hp").equals(100.0),"previous snapshot stable after later input");
        EventAdapter.Update missing=f.accept("/state",state("a",0,300,3,list()));
        check(count(missing,"UNIT_BECAME_UNAVAILABLE")==1&&event(missing,"UNIT_BECAME_UNAVAILABLE").data.get("reasonClass").equals("ABSENT_FROM_COMPLETE_OWN_SAMPLE"),"absence distinct from explicit death");
        check(missing.snapshot.ownUnits().get(11L).current.isEmpty()&&missing.snapshot.ownUnits().get(11L).lastObservation.fields.get("hp").equals(60.0),"absent current unknown and last evidence retained");
        EventAdapter.Update reappear=f.accept("/state",state("a",0,400,4,list(unit(11,55,1,0))));
        check(count(reappear,"UNIT_FIRST_OBSERVED")==0&&count(reappear,"UNIT_REOBSERVED")==1,"same epoch ID reappearance is not first observed");
        Map<String,Object> dead=unit(11,0,1,0);dead.put("dead",true);
        EventAdapter.Update died=f.accept("/state",state("a",0,500,5,list(dead)));
        check(event(died,"UNIT_BECAME_UNAVAILABLE").data.get("reasonClass").equals("EXPLICIT_DEAD_FLAG"),"explicit dead evidence distinct from missing");
        check(event(died,"UNIT_BECAME_UNAVAILABLE").toMap().get("occurredAtGameTimeMs")==null,"detected death is not exact occurrence time");
        Map<String,Object> bad=unit(11,40,1,1);bad.put("hp","invalid");
        EventAdapter.Update invalid=f.accept("/state",state("a",0,600,6,list(bad)));
        check(!invalid.accepted&&invalid.snapshot.ownUnits().get(11L).current.isEmpty(),"invalid rows invalidate coverage");
        EventAdapter.Update repaired=f.accept("/state",state("a",0,700,7,list(unit(11,35,1,0))));
        check(repaired.accepted&&count(repaired,"QUEUE_BECAME_EMPTY")==0&&count(repaired,"UNIT_HP_BAND_CHANGED")==0,"no diff values across invalid row baseline");
        Map<String,Object> noList=state("a",0,800,8,list());noList.remove("ownUnits");
        f.accept("/state",noList);EventAdapter.Update empty=f.accept("/state",state("a",0,900,9,list()));
        check(empty.snapshot.ownUnits().get(11L).current.isEmpty()&&count(empty,"UNIT_BECAME_UNAVAILABLE")==0,"invalid-list to empty never resurrects retained own HP");
        Map<String,Object> duplicate=state("a",0,950,10,list(unit(12,100,1,0),unit(12,100,1,0)));
        check(!f.accept("/state",duplicate).accepted,"duplicate IDs reject full-list coverage");
        Map<String,Object> fractional=unit(1,100,1,0);fractional.put("id",1.5);
        check(!f.accept("/state",state("a",0,960,11,list(fractional))).accepted,"noninteger IDs not truncated");
        fractional.put("id",Double.NaN);check(!f.accept("/state",state("a",0,970,12,list(fractional))).accepted,"NaN ID rejected");
        fractional.put("id",1e30);check(!f.accept("/state",state("a",0,980,13,list(fractional))).accepted,"overflow ID rejected");
        check(EventAdapter.integer(9007199254740993L).equals(9007199254740993L),"64 bit native identity precise");
    }
    private static void enemyEvidence(){
        Fixture f=new Fixture("enemy");f.accept("/state",state("a",0,100,1,list()));
        Map<String,Object> visible=combat("a",110,2,list(enemy(21,110)));EventAdapter.Update seen=f.accept("/combat/observe",visible);
        check(seen.accepted&&seen.snapshot.enemies().get(21L).currentField("hp").equals(50.0),"current enemy only legal visible sample");
        check(count(seen,"UNIT_HP_BAND_CHANGED")==0,"enemy without maxHP never gets relative band");
        check(seen.snapshot.views().get("/combat/observe|/combat/observe").observation.sourcePlayerId==null
                &&seen.snapshot.views().get("/combat/observe|/combat/observe").contextPlayerId.equals("team:0"),"native missing player separate from validated context");
        Map<String,Object> absent=combat("a",120,3,list());
        absent.put("enemyIntel",list(m("id",21L,"status","LOST_CONTACT","lastKnownHp",999.0,"lastKnownX",999.0,"lastKnownY",999.0,"lastKnownType","submarine")));
        EventAdapter.Update lost=f.accept("/combat/observe",absent);WorldState.EnemyFact ghost=lost.snapshot.enemies().get(21L);
        check(count(lost,"ENEMY_LOST_VISIBILITY")==1&&ghost.current.isEmpty(),"lost visibility clears current dynamic fields");
        check(ghost.lastObservation.fields.get("hp").equals(50.0)&&ghost.lastObservation.fields.get("x").equals(120.0),"hidden intel cannot refresh prior last fields");
        check(ghost.toMap().get("confirmedDestroyed")==null,"missing enemy is not confirmed destroyed");
        Map<String,Object> stale=combat("a",130,4,list(enemy(21,129)));
        EventAdapter.Update bad=f.accept("/combat/observe",stale);
        check(!bad.accepted&&count(bad,"ENEMY_LOST_VISIBILITY")==0&&count(bad,"ENEMY_UPDATED")==0,"stale visible row causes evidence gap, not false visibility loss");
        Map<String,Object> noCombatTime=combat("a",135,5,list());noCombatTime.remove("gameTimeMs");
        EventAdapter.Update unknownTime=f.accept("/combat/observe",noCombatTime);
        check(!unknownTime.accepted&&count(unknownTime,"ENEMY_LOST_VISIBILITY")==0,"empty combat list without source time is unknown coverage");
        Map<String,Object> staleDomain=enemy(21,140);staleDomain.put("domainObservedAtGameTimeMs",130L);
        EventAdapter.Update domain=f.accept("/combat/observe",combat("a",140,5,list(staleDomain)));
        check(domain.snapshot.enemies().get(21L).currentField("targetDomain").equals("UNKNOWN"),"stale domain cannot be borrowed from fresh HP");
        Map<String,Object> cleared=combat("a",150,6,list());
        cleared.put("enemyIntel",list(m("id",21L,"status","CLEARED","lastKnownBuilding",false,"lastKnownSiteVisible",true,"clearedGameTimeMs",150L)));
        check(count(f.accept("/combat/observe",cleared),"ENEMY_SITE_CLEARED")==0,"mobile old empty site never classified cleared building");
        Map<String,Object> building=enemy(22,160);building.put("building",true);f.accept("/combat/observe",combat("a",160,7,list(building)));
        Map<String,Object> site=combat("a",170,8,list());site.put("enemyIntel",list(m("id",22L,"status","CLEARED","lastKnownBuilding",true,"lastKnownSiteVisible",true,"clearedGameTimeMs",170L)));
        EventAdapter.Update clear=f.accept("/combat/observe",site);
        check(count(clear,"ENEMY_SITE_CLEARED")==1&&event(clear,"ENEMY_SITE_CLEARED").data.get("confirmedDestroyed")==null,"site cleared narrow evidence not kill");
        long events=clear.events.size();check(count(f.accept("/combat/observe",combat("b",200,9,list(enemy(33,200)))),"ENEMY_OBSERVED")==0,"foreign combat cannot reseed world");
        check(f.adapter.snapshot().sessionId.equals("a")&&!f.adapter.snapshot().enemies().containsKey(33L),"foreign packet no facts added");
        Map<String,Object> history=combat("a",180,9,list());history.put("enemyIntel",list(m("id",23L,"status","LOST_CONTACT","lastKnownType","tank","lastKnownHp",20.0,"lastKnownX",1.0,"lastKnownY",2.0,"lastSeenGameTimeMs",30L)));
        WorldState.EnemyFact hist=f.accept("/combat/observe",history).snapshot.enemies().get(23L);
        check(hist.current.isEmpty()&&hist.lastObservation.origin.equals("NATIVE_LAST_KNOWN_HISTORY_ONLY"),"first historical contact remains historical with original ID unknown");
    }
    private static void enemyMeaningfulChanges(){
        Fixture f=new Fixture("enemy-facts");f.accept("/state",state("s",0,100,1,list()));
        EventAdapter.Update first=f.accept("/combat/observe",combat("s",100,1,list(enemy(1,100))));
        check(count(first,"ENEMY_OBSERVED")==1,"first legal sample is observed");
        for(int i=1;i<=40;i++){
            long time=100+i;EventAdapter.Update repeat=f.accept("/combat/observe",combat("s",time,1+i,list(enemy(1,time))));
            check(repeat.events.isEmpty(),"unchanged visible enemy does not create transition flood at sample "+i);
        }
        WorldState.EnemyFact repeated=f.adapter.snapshot().enemies().get(1L);
        check(repeated.lastObservation.fields.get("lastSeenGameTimeMs").equals(140L)
                &&repeated.lastObservation.observation.id.equals(f.adapter.snapshot().views().get("/combat/observe|/combat/observe").observation.id),"silent sample refreshes both source and last legal observation");
        Map<String,Object> previous=enemy(1,140);
        String[] fields={"hp","x","y","type","targetDomain","touchingWater","building","canAttack","domainSourceId"};
        Object[] values={40.0,121.0,131.0,"amphibiousJet","SURFACE",Boolean.TRUE,Boolean.TRUE,Boolean.TRUE,"new-native-proof"};
        for(int i=0;i<fields.length;i++){
            long time=150+i;Map<String,Object> changed=new LinkedHashMap<String,Object>(previous);
            changed.put("lastSeenGameTimeMs",time);changed.put("domainObservedAtGameTimeMs",time);changed.put(fields[i],values[i]);
            EventAdapter.Update update=f.accept("/combat/observe",combat("s",time,50+i,list(changed)));
            check(count(update,"ENEMY_UPDATED")==1&&count(update,"ENEMY_OBSERVED")==0,"meaningful "+fields[i]+" change creates exactly one update");
            check(event(update,"ENEMY_UPDATED").previous!=null&&event(update,"ENEMY_UPDATED").toMap().get("occurredAtGameTimeMs")==null,"update retains detection-only provenance for "+fields[i]);
            previous=changed;
        }
        // Metadata attachment is first established, then its changing stamps stay silent.
        Map<String,Object> stamped=new LinkedHashMap<String,Object>(previous);
        stamped.put("lastSeenGameTimeMs",170L);stamped.put("domainObservedAtGameTimeMs",170L);
        stamped.put("sampleGameTimeMs",170L);stamped.put("observedAtGameTimeMs",170L);stamped.put("frame",70L);
        EventAdapter.Update sample=f.accept("/combat/observe",combat("s",170,70,list(stamped)));
        check(sample.events.isEmpty(),"adding sample/frame provenance does not create factual update");
        stamped.put("lastSeenGameTimeMs",180L);stamped.put("domainObservedAtGameTimeMs",180L);
        stamped.put("sampleGameTimeMs",180L);stamped.put("observedAtGameTimeMs",180L);stamped.put("frame",80L);
        check(f.accept("/combat/observe",combat("s",180,80,list(stamped))).events.isEmpty(),"sample/frame-only advance stays silent");
        Map<String,Object> stale=new LinkedHashMap<String,Object>(stamped);stale.put("lastSeenGameTimeMs",189L);
        EventAdapter.Update invalid=f.accept("/combat/observe",combat("s",190,81,list(stale)));
        check(!invalid.accepted&&count(invalid,"ENEMY_UPDATED")==0&&count(invalid,"ENEMY_LOST_VISIBILITY")==0,"stale enemy row invalidates without update or loss");
        EventAdapter.Update rebase=f.accept("/combat/observe",combat("s",200,82,list(enemy(1,200))));
        check(count(rebase,"ENEMY_OBSERVED")==1&&count(rebase,"ENEMY_UPDATED")==0,"repair after invalid source establishes observation baseline");
        EventAdapter.Update fog=f.accept("/combat/observe",combat("s",210,83,list()));
        check(count(fog,"ENEMY_LOST_VISIBILITY")==1&&fog.snapshot.enemies().get(1L).current.isEmpty(),"fog retains unknown current and one narrow visibility loss");
        EventAdapter.Update visibleAgain=f.accept("/combat/observe",combat("s",220,84,list(enemy(1,220))));
        check(count(visibleAgain,"ENEMY_OBSERVED")==1&&count(visibleAgain,"ENEMY_UPDATED")==0,"return from fog is a fresh observation");
        EventAdapter.Update gap=f.accept("/combat/observe",combat("s",1500,85,list(enemy(1,1500))));
        check(count(gap,"SOURCE_COVERAGE_GAP")==1&&count(gap,"ENEMY_OBSERVED")==1&&count(gap,"ENEMY_UPDATED")==0,"long gap does not synthesize an enemy transition");
        EventAdapter.Update gapEmpty=f.accept("/combat/observe",combat("s",2800,86,list()));
        check(count(gapEmpty,"ENEMY_LOST_VISIBILITY")==0,"cross-gap empty sample does not infer loss");
        f.accept("/state",state("next",1,10,1,list()));
        EventAdapter.Update reset=f.accept("/combat/observe",combat("next",10,1,list(enemy(1,10))));
        check(count(reset,"ENEMY_OBSERVED")==1&&count(reset,"ENEMY_UPDATED")==0,"new world context does not borrow old enemy baseline");
        Fixture domain=new Fixture("enemy-domain");domain.accept("/state",state("s",0,100,1,list()));
        Map<String,Object> unknown=enemy(1,100);unknown.put("domainObservedAtGameTimeMs",90L);
        domain.accept("/combat/observe",combat("s",100,1,list(unknown)));
        unknown=enemy(1,110);unknown.put("domainObservedAtGameTimeMs",90L);unknown.put("targetDomain","AIR");unknown.put("touchingWater",true);
        EventAdapter.Update stillUnknown=domain.accept("/combat/observe",combat("s",110,2,list(unknown)));
        check(stillUnknown.events.isEmpty()&&stillUnknown.snapshot.enemies().get(1L).currentField("targetDomain").equals("UNKNOWN"),"stale raw domain churn stays unknown without factual transition");
        check(count(domain.accept("/combat/observe",combat("s",120,3,list(enemy(1,120)))),"ENEMY_UPDATED")==1,"fresh calibrated domain replacing unknown is a meaningful update");
    }
    private static void resetOrderingAndCoverage(){
        Fixture f=new Fixture("ordering");Map<String,Object> p=state("a",0,100,1,list(unit(1,100,1,0)));GameClock.Observation first=f.observation("/state",p);f.adapter.accept(first,p);
        check(!f.adapter.accept(first,p).accepted&&f.adapter.snapshot().revision==1,"same Observation ID replay never advances revision");
        EventAdapter.Update duplicate=f.accept("/state",state("a",0,100,1,list(unit(1,100,1,0))));
        check(duplicate.accepted&&duplicate.events.isEmpty()&&duplicate.disposition.equals("DUPLICATE_SOURCE_SNAPSHOT_FRESH_READ"),"new read of same native snapshot refreshes provenance without domain events");
        Map<String,Object> changed=state("a",0,100,1,list(unit(1,20,1,0)));
        EventAdapter.Update conflict=f.accept("/state",changed);
        check(!conflict.accepted&&conflict.snapshot.ownUnits().get(1L).current.isEmpty(),"conflicting same source stamp invalidates current coverage");
        check(count(conflict,"UNIT_HP_BAND_CHANGED")==0,"same stamp conflict no invented damage delta");
        EventAdapter.Update rebase=f.accept("/state",state("a",0,200,2,list(unit(1,30,1,0))));
        check(rebase.accepted&&count(rebase,"UNIT_HP_BAND_CHANGED")==0,"conflict recovery establishes new baseline");
        EventAdapter.Update rollback=f.accept("/state",state("a",0,50,0,list()));
        check(count(rollback,"WORLD_CONTEXT_RESET")==1&&count(rollback,"UNIT_BECAME_UNAVAILABLE")==0&&rollback.snapshot.epoch==2,"new authoritative read rollback resets without disappearance cascade");
        EventAdapter.Update switched=f.accept("/state",state("b",1,100,1,list(unit(2,100,1,0))));
        check(switched.snapshot.sessionId.equals("b")&&switched.snapshot.playerId.equals("team:1"),"state switches session/player explicitly");
        check(!f.adapter.accept(first,p).accepted&&f.adapter.snapshot().sessionId.equals("b"),"delayed retired state ID cannot switch back");
        EventAdapter.Update oldSession=f.accept("/state",state("a",0,300,3,list()));
        check(!oldSession.accepted&&f.adapter.snapshot().sessionId.equals("b"),"new old-session read cannot resurrect retired world");
        Map<String,Object> gapState=state("b",1,3000,3,list());EventAdapter.Update gap=f.accept("/state",gapState);
        check(count(gap,"SOURCE_COVERAGE_GAP")==1&&count(gap,"UNIT_BECAME_UNAVAILABLE")==0,"long gap suppresses disappearance inference");
        check(gap.snapshot.ownUnits().get(2L).current.isEmpty(),"gap does not resurrect unknown retained own facts");
        Map<String,Object> mismatch=state("b",1,3100,4,list());GameClock.Observation o=f.observation("/state",mismatch);mismatch.put("player",m("teamId",7));
        check(!f.adapter.accept(o,mismatch).accepted,"payload/player provenance mismatch rejected");
        Map<String,Object> unknown=state("b",1,3200,5,list());unknown.remove("player");EventAdapter.Update invalid=f.accept("/state",unknown);
        check(!invalid.accepted&&invalid.snapshot.sessionId==null&&count(invalid,"UNIT_BECAME_UNAVAILABLE")==0,"unknown state identity invalidates context without fabricated player");
        Map<String,Object> fakePlayer=state("c",0,1,1,list());fakePlayer.put("player",m("id","made-up-player"));
        check(!f.accept("/state",fakePlayer).accepted,"generic player ID cannot replace missing native teamId");
        GameClock other=new GameClock("other");Map<String,Object> foreignRun=state("c",0,1,1,list());
        check(!f.adapter.accept(other.observe("/state",foreignRun,1,2,1L),foreignRun).accepted,"mixed run Observation ID rejected");
    }
    private static void scopedSourcesAndFreshness(){
        Fixture f=new Fixture("scopes");f.accept("/state",state("a",0,100,1,list(unit(5,100,1,1))));
        Map<String,Object> menu=m("sessionId","a","factories",list(m("id",5L,"queue",0,"actions",list(m("actionId","tank","cost",350)))));
        GameClock.Observation mo=f.observation("/combat/production",menu);EventAdapter.Update m1=f.adapter.accept(mo,menu);
        check(m1.accepted&&m1.events.isEmpty()&&m1.snapshot.ownUnits().get(5L).current.get("productionQueue").equals(1),"unstamped menu cannot override state queue or release paid occupancy");
        WorldState.SourceView mv=m1.snapshot.views().get("/combat/production|/combat/production");
        check(mv.observation.sourceGameTimeMs==null&&mv.ageGameTimeMs==null&&mv.gameFreshness.equals("UNKNOWN_SOURCE_GAME_TIME"),"missing menu source time remains unknown");
        check(!f.adapter.accept(mo,menu).accepted,"unstamped same observation replay ignored");
        Map<String,Object> later=m("sessionId","a","factories",list(m("id",5L,"queue",2,"actions",list())));f.accept("/combat/production",later);
        check(!f.adapter.accept(mo,menu).accepted,"old unstamped menu cannot overwrite newer quote");
        f.accept("/combat/engagement?unitIds=1&targetId=10",m("sessionId","a","gameTimeMs",110L,"targetId",10L,"actors",list(m("id",1L,"status","COMPATIBLE"))));
        EventAdapter.Update q=f.accept("/combat/engagement?unitIds=2&targetId=11",m("sessionId","a","gameTimeMs",111L,"targetId",11L,"actors",list(m("id",2L,"status","COMPATIBLE"))));
        check(q.snapshot.views().containsKey("/combat/engagement|/combat/engagement?unitIds=1&targetId=10")&&q.snapshot.views().containsKey("/combat/engagement|/combat/engagement?unitIds=2&targetId=11"),"request scopes retain independent query evidence");
        check(count(q,"UNIT_BECAME_UNAVAILABLE")==0,"query subset absence is not own unavailable");
        f.accept("/combat/observe",combat("a",120,2,list(enemy(21,120))));
        EventAdapter.Update expired=f.accept("/state",state("a",0,2000,3,list(unit(5,100,1,1))));
        check(expired.snapshot.enemies().get(21L).current.isEmpty()&&expired.snapshot.enemies().get(21L).lastObservation.fields.get("hp").equals(50.0),"freshness expiry masks current enemy but preserves last observation");
        check(count(expired,"ENEMY_LOST_VISIBILITY")==0,"source expiry is not actual visibility loss");
        check(!f.accept("/combat/observe",combat("a",130,3,list(enemy(21,130)))).accepted,"stale cross-source packet cannot refresh historical enemy into current");
        EventAdapter.Update ahead=f.accept("/combat/observe",combat("a",2100,4,list(enemy(21,2100))));
        WorldState.SourceView view=ahead.snapshot.views().get("/combat/observe|/combat/observe");
        check(view.ageGameTimeMs==null&&view.gameFreshness.equals("SOURCE_AHEAD_OF_STATE_REFERENCE"),"source ahead of state not falsely zero-aged");
        Map<String,Object> scout=m("sessionId","a","frame",4L,"visibleThreats",list(m("id",22L,"type","tank","x",1.0,"y",2.0)));
        EventAdapter.Update so=f.accept("/scout/observe",scout);
        check(so.snapshot.views().get("/scout/observe|/scout/observe").observation.sourceGameTimeMs==null,"scout native game time never fabricated");
        f.accept("/state",state("a",0,4000,5,list(unit(5,100,1,1))));scout.put("frame",6L);scout.put("visibleThreats",list());
        EventAdapter.Update sg=f.accept("/scout/observe",scout);
        check(count(sg,"SOURCE_COVERAGE_GAP")==1&&event(sg,"SOURCE_COVERAGE_GAP").data.get("gameGapBasis").equals("STATE_DETECTION_ANCHOR_NOT_SOURCE_TIME"),"frame-only source game coverage uses explicitly separate detection anchor");
        check(count(sg,"LOCAL_THREAT_BECAME_NOT_VISIBLE")==0,"no source continuity fabricated across anchor gap");
        scout.remove("frame");EventAdapter.Update noScoutStamp=f.accept("/scout/observe",scout);
        check(!noScoutStamp.accepted&&count(noScoutStamp,"LOCAL_THREAT_BECAME_NOT_VISIBLE")==0,"unstamped scout list cannot establish current visibility coverage");
        Fixture paused=new Fixture("paused");paused.accept("/state",state("s",0,1,1,list(unit(1,100,1,0))));paused.accept("/combat/observe",combat("s",1,1,list(enemy(2,1))));
        paused.wall+=1200;EventAdapter.Update refreshed=paused.accept("/combat/observe",combat("s",1,1,list(enemy(2,1))));
        check(count(refreshed,"ENEMY_UPDATED")==0&&count(refreshed,"ENEMY_LOST_VISIBILITY")==0&&!refreshed.snapshot.enemies().get(2L).current.isEmpty(),"fresh reads during pause do not expire solely from old receipt wall time");
        check(count(refreshed,"SOURCE_COVERAGE_GAP")==1,"long wall gap remains explicit even if native stamp repeats");
        check(refreshed.snapshot.enemies().get(2L).lastObservation.observation.id.equals(refreshed.observationId),"duplicate point sample updates read provenance");
        Map<String,Object> ordered=m("sessionId","s","gameTimeMs",2L,"routeTiles",Arrays.asList(1,2,3));paused.accept("/scout/route?unitId=1",ordered);ordered.put("routeTiles",Arrays.asList(3,2,1));
        check(!paused.accept("/scout/route?unitId=1",ordered).accepted,"ordered route list change cannot normalize into duplicate");
    }
    private static void determinismAndBounds(){
        List<String> first=replay("replay"),second=replay("replay");check(first.equals(second),"identical packet sequence gives byte-equivalent deterministic events");
        Fixture f=new Fixture("bounded");f.accept("/state",state("s",0,100,1,list(unit(1,100,1,0))));int evictions=0;
        for(int i=0;i<150;i++)evictions+=count(f.accept("/combat/engagement?targetId="+i,m("sessionId","s","gameTimeMs",101L+i,"actors",list())),"SOURCE_VIEW_EVICTED");
        check(f.adapter.snapshot().views().size()==128&&evictions==23,"query source history bounded with explicit eviction metadata");
        check(f.adapter.snapshot().ownUnits().get(1L).current.get("hp").equals(100.0),"query churn preserves authoritative state source");
    }
    private static List<String> replay(String run){
        Fixture f=new Fixture(run);List<String> result=new ArrayList<String>();
        for(int i=1;i<=3;i++)for(EventAdapter.DerivedEvent e:f.accept("/state",state("s",0,i*100,i,list(unit(2,100-i*10,1,i==1?1:0),unit(1,100,1,0)))).events)result.add(canonical(e.toMap()));
        return result;
    }
    private static String canonical(Object o){if(o instanceof Map){StringBuilder b=new StringBuilder("{");for(Map.Entry<String,Object> e:new java.util.TreeMap<String,Object>(obj(o)).entrySet())b.append(e.getKey()).append('=').append(canonical(e.getValue())).append(';');return b.append('}').toString();}if(o instanceof List){StringBuilder b=new StringBuilder("[");for(Object v:(List<?>)o)b.append(canonical(v)).append(';');return b.append(']').toString();}return String.valueOf(o);}
}
