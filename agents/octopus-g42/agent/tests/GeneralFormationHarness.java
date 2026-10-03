package io.rwagent.client;

import java.util.*;
import static io.rwagent.client.GeneralRegistry.*;

/** Focused formation state/evidence contracts. Synthetic legal own observations, no native engine claim. */
public final class GeneralFormationHarness {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void invalid(Runnable action,String why){boolean thrown=false;try{action.run();}catch(IllegalArgumentException expected){thrown=true;}check(thrown,why);}
    private static final class Fixture {
        final CommandArbiter arbiter=new CommandArbiter();final GeneralRegistry registry=new GeneralRegistry(arbiter);
        final Set<Long> own=new LinkedHashSet<Long>();long frame;
        Fixture(){observe(0);registry.bootstrap(Collections.<Collection<Long>>emptyList(),Collections.<Long>emptyList());}
        void observe(long next){frame=next;arbiter.observe(new CommandArbiter.Stamp("s","team:0",frame,frame*100),own);}
        void later(){observe(frame+1);health();}
        void health(){for(UnitView unit:registry.units())if(own.contains(unit.unitId))registry.updateHealthRole(unit.unitId,unit.healthRole);}
        void admit(long id){own.add(id);observe(frame);check(registry.admitFree(id,HealthRole.NORMAL),"ready ordinary own actor enters FREE");}
        GeneralId birth(int desired){GeneralId id=registry.createForming(desired,100,200);check(id!=null,"healthy ready FREE allows explicit empty FORMING birth");return id;}
        void reserve(long actor,GeneralId general){later();check(registry.requestJoin(actor,general),"later frame formally reserves newborn for formation");}
        void accept(long actor){check(registry.beginJoining(actor),"pending moves to real JOINING owner");check(registry.joinAccepted(actor,frame+1),"actual receipt frame retained without membership");}
        void arrive(long actor,GeneralId general){later();check(!registry.observeJoinPosition(actor,arbiter.stamp(),100,200,180),"receipt frame cannot attach even at rally anchor");later();
            check(registry.observeJoinPosition(actor,arbiter.stamp(),100,200,180),"later own position at legal anchor attaches to same General");
            check(arbiter.owns(general.owner(),actor),"arrival transfers actual General owner");}
        void addAttached(long actor,GeneralId general){admit(actor);reserve(actor,general);accept(actor);arrive(actor,general);}
    }
    private static void emptyBirthAndFirstArrival(){
        Fixture f=new Fixture();check(!f.registry.hasValidGeneral()&&!f.registry.hasActiveGeneral(),"initial empty army has explicit absence");
        check(f.registry.createForming(12,100,200)==null,"empty actor set cannot create a proxy General");f.admit(1);
        f.registry.updateHealthRole(1,HealthRole.RECOVERING);check(f.registry.createForming(12,100,200)==null,"recovering FREE actor cannot create a formation");f.registry.updateHealthRole(1,HealthRole.NORMAL);
        long generation=f.arbiter.ownerGeneration(1);GeneralId g=f.birth(12);GeneralView initial=f.registry.general(g);
        check(initial.phase==Phase.FORMING&&initial.desiredStrength==12&&initial.members.isEmpty()&&initial.reservations.isEmpty(),"new General is empty FORMING with explicit desired strength");
        check(initial.anchorKnown&&initial.joinTargetKnown&&initial.joinTargetX==100&&initial.joinTargetY==200&&!initial.centroidKnown,"lawful base anchor enables empty roster joining without invented centroid");
        check("LEGAL_OWN_RALLY_ANCHOR".equals(initial.joinTargetSource)&&f.registry.hasValidGeneral()&&!f.registry.hasActiveGeneral(),"formation anchor remains distinct from attached centroid");
        check(f.registry.unit(1).allocation==Allocation.FREE&&f.arbiter.owns(FREE_OWNER,1)&&f.arbiter.ownerGeneration(1)==generation,"birth alone neither reserves nor claims first recruit");
        check(!f.registry.requestJoin(1,g),"birth frame retains visible FREE breakpoint");check(f.registry.createForming(12,500,600)==null,"retained empty FORMING prevents duplicate birth");
        f.reserve(1,g);check(f.registry.reservedJoinCount(g)==1&&f.registry.general(g).members.isEmpty()&&f.arbiter.ownerGeneration(1)==generation,"pending is counted but is not first member");
        f.accept(1);check(f.registry.general(g).members.isEmpty()&&f.registry.unit(1).membership==Membership.JOINING&&f.arbiter.owns(g.joinOwner(),1),"native acceptance leaves first actor JOINING");
        f.later();check(!f.registry.observeJoinPosition(1,f.arbiter.stamp(),100,200,180),"same receipt frame at anchor cannot attach");f.later();
        check(!f.registry.observeJoinPosition(1,new CommandArbiter.Stamp("other","team:0",f.frame,f.frame*100),100,200,180),"foreign session cannot witness formation arrival");
        check(!f.registry.observeJoinPosition(1,new CommandArbiter.Stamp("s","team:1",f.frame,f.frame*100),100,200,180),"foreign player cannot witness formation arrival");
        check(!f.registry.observeJoinPosition(1,new CommandArbiter.Stamp("s","team:0",f.frame-1,(f.frame-1)*100),100,200,180),"stale own packet cannot witness formation arrival");
        check(!f.registry.observeJoinPosition(1,f.arbiter.stamp(),1000,200,180),"elapsed time at distant location cannot attach to empty formation");
        check(f.registry.observeJoinPosition(1,f.arbiter.stamp(),100,200,180)&&f.arbiter.ownerGeneration(1)==generation+2,"first actual later arrival performs join-to-General generation migration");
        check(f.registry.general(g).members.equals(Collections.singleton(1L))&&f.registry.general(g).phase==Phase.FORMING&&!f.registry.refreshFormation(),"one attached member does not activate General");
        check(initial.members.isEmpty()&&initial.phase==Phase.FORMING,"creation snapshot cannot be rewritten by later arrival");
        invalid(()->f.registry.createForming(5,100,200),"desired formation strength below six is rejected");invalid(()->f.registry.createForming(12,Double.NaN,200),"unknown anchor cannot be invented");
    }
    private static void sixHealthyArrivalsAndNoDowngrade(){
        Fixture f=new Fixture();f.admit(1);GeneralId g=f.birth(12);f.reserve(1,g);f.accept(1);f.arrive(1,g);
        for(long id=2;id<=5;id++){f.addAttached(id,g);check(!f.registry.refreshFormation()&&f.registry.general(g).phase==Phase.FORMING,"one to five real attached members remain FORMING");}
        f.admit(6);f.reserve(6,g);check(!f.registry.refreshFormation()&&f.registry.general(g).healthyAttachedStrength==5,"healthy pending sixth actor cannot activate");f.accept(6);
        check(!f.registry.refreshFormation()&&f.registry.general(g).members.size()==5,"sixth accepted receipt cannot activate");f.arrive(6,g);
        f.registry.updateHealthRole(6,HealthRole.RECOVERING);check(!f.registry.refreshFormation()&&f.registry.general(g).healthyAttachedStrength==5,"six attached with recovering sixth is still FORMING");
        f.registry.updateHealthRole(6,HealthRole.NORMAL);f.observe(f.frame+1);
        check(!f.registry.refreshFormation()&&f.registry.general(g).healthyAttachedStrength==0,"old NORMAL facts cannot substitute for current health sampling");f.health();
        Map<Long,Long> before=f.arbiter.snapshotGenerations(g.owner(),f.registry.attachedActorIds(g));f.registry.drainChanges();
        check(f.registry.refreshFormation()&&f.registry.general(g).phase==Phase.ACTIVE&&f.registry.hasActiveGeneral(),"six healthy actual current attached members activate same General");
        check(g.equals(f.registry.general(g).id)&&f.registry.general(g).desiredStrength==12&&f.arbiter.validateGenerations(new ArrayList<Long>(before.keySet()),before)==null,"activation does not replace ID, target strength or owner generation");
        List<Map<String,Object>> changes=f.registry.drainChanges();check(changes.size()==1&&"GENERAL_FORMATION_ACTIVE".equals(changes.get(0).get("reason")),"activation is one explicit phase transition");
        Map<?,?> old=(Map<?,?>)changes.get(0).get("before"),next=(Map<?,?>)changes.get(0).get("after");check("FORMING".equals(old.get("phase"))&&"ACTIVE".equals(next.get("phase")),"phase trace contains old and new state");
        check(!f.registry.refreshFormation()&&f.registry.drainChanges().isEmpty(),"repeated refresh does not flood ACTIVE transitions");
        check(!f.registry.general(g).joinTargetKnown,"ACTIVE cannot keep using old rally anchor as a current centroid");
        f.registry.updateGeneralCentroid(g,300,400);check(f.registry.general(g).joinTargetKnown&&"CURRENT_ATTACHED_CENTROID".equals(f.registry.general(g).joinTargetSource)&&f.registry.general(g).joinTargetX==300,"ACTIVE target uses actual current attached centroid");
        f.registry.updateHealthRole(1,HealthRole.RECOVERING);f.registry.beginLocalResponse(2,"local-response:1");
        check(!f.registry.refreshFormation()&&f.registry.general(g).phase==Phase.ACTIVE&&f.registry.general(g).healthyAttachedStrength==4,"injury and detachment never downgrade ACTIVE");
    }
    private static void ownershipAndLiveHealthAuthority(){
        Fixture f=new Fixture();f.admit(1);GeneralId g=f.birth(6);f.reserve(1,g);f.accept(1);f.arrive(1,g);for(long id=2;id<=6;id++)f.addAttached(id,g);
        check(!f.registry.updateHealthRole(999,HealthRole.NORMAL)&&f.registry.unit(999)==null,"health updates cannot register unknown actor or fabricate readiness");
        f.arbiter.transfer(g.owner(),"foreign-owner",1);check(!f.registry.refreshFormation()&&f.registry.general(g).healthyAttachedStrength==5,"six roster entries with one non-General real owner do not activate");
        f.arbiter.transfer("foreign-owner",g.owner(),1);f.own.remove(2L);f.observe(f.frame+1);f.health();
        check(!f.registry.refreshFormation()&&f.registry.general(g).healthyAttachedStrength==5,"actor absent from current own-live observation does not count as healthy ATTACHED");
        f.registry.updateHealthRole(2,HealthRole.NORMAL);check(!f.registry.refreshFormation(),"updating health cannot revive absent current-own actor");f.own.add(2L);f.later();check(f.registry.refreshFormation(),"later actual own observation restores sixth eligible actor");
        Fixture staleFree=new Fixture();staleFree.admit(1);staleFree.observe(1);check(staleFree.registry.createForming(6,100,200)==null,"birth needs current health facts rather than stale FREE NORMAL");staleFree.health();check(staleFree.registry.createForming(6,100,200)!=null,"fresh legal health fact enables retained ready FREE birth");
        Fixture external=new Fixture();external.admit(1);external.registry.borrowExternal(1,"recon:1");check(external.registry.createForming(6,100,200)==null,"borrowed external actor cannot supply force birth");
    }
    private static void localResponseAndRetainedFormation(){
        Fixture pending=new Fixture();pending.admit(1);GeneralId g=pending.birth(6);pending.later();check(pending.registry.beginLocalResponse(1,"local-response:1")&&pending.registry.requestJoin(1,g),"FREE response may coexist with pending empty formation reservation");
        check(!pending.registry.beginJoining(1)&&pending.registry.reservedJoinCount(g)==1,"response retains control until current visibility caller clears");check(pending.registry.clearLocalResponse(1)&&pending.arbiter.owns(g.joinOwner(),1),"pending response clear continues real formation joining");
        check(pending.registry.joinAccepted(1,pending.frame+1),"continued joining needs its actual accepted frame");pending.arrive(1,g);check(pending.registry.general(g).phase==Phase.FORMING,"pending response continuation does not proxy ACTIVE");
        check(pending.registry.beginLocalResponse(1,"local-response:2"),"attached forming member may detach for ordinary response");
        check(pending.registry.general(g).members.isEmpty()&&pending.registry.hasValidGeneral()&&pending.registry.general(g).joinTargetKnown,"temporarily empty FORMING retains legal anchor and same ID");
        check(pending.registry.createForming(6,500,600)==null&&!pending.registry.requestJoin(1,g),"retained detached response cannot trigger duplicate birth or automatic original-General return");
        check(pending.registry.clearLocalResponse(1)&&pending.arbiter.owns(FREE_OWNER,1)&&!pending.registry.requestJoin(1,g),"detached response ends at visible FREE breakpoint");pending.reserve(1,g);pending.accept(1);pending.arrive(1,g);
        check(g.equals(pending.registry.general(g).id)&&pending.registry.general(g).members.size()==1,"later explicit reallocation may join retained FORMING again");
    }
    private static void deathInvalidationRebirthAndABA(){
        Fixture f=new Fixture();f.admit(1);GeneralId original=f.birth(6);f.reserve(1,original);f.accept(1);f.arrive(1,original);
        Map<Long,Long> oldGenerations=f.arbiter.snapshotGenerations(original.owner(),Arrays.asList(1L));f.own.remove(1L);f.observe(f.frame+1);
        check(f.registry.removeUnit(1)&&f.registry.invalidateGeneral(original)&&!f.registry.hasValidGeneral(),"caller all-own-force loss invalidates forming General");
        check(f.registry.createForming(6,100,200)==null,"death cannot birth a replacement without a new healthy ready FREE");f.admit(1);GeneralId replacement=f.birth(6);
        check(!replacement.equals(original)&&replacement.value>original.value&&f.registry.general(original)==null,"rebirth never reuses old General identity");
        check(!f.registry.requestJoin(1,original),"old General ID cannot acquire a new reservation");f.reserve(1,replacement);f.accept(1);f.arrive(1,replacement);
        check("STALE_OWNER_GENERATION".equals(f.arbiter.validateGenerations(Arrays.asList(1L),oldGenerations)),"recycled unit ID and owner migration do not revive old intent generation");
        Fixture reservations=new Fixture();reservations.admit(1);reservations.admit(2);GeneralId first=reservations.birth(6);reservations.later();
        check(reservations.registry.requestJoin(1,first)&&reservations.registry.beginJoining(1),"forming join reservation established");
        check(reservations.registry.beginLocalResponse(2,"local-response:1")&&reservations.registry.requestJoin(2,first),"forming LR pending reservation established");
        check(reservations.registry.invalidateGeneral(first)&&reservations.registry.reservedJoinCount(first)==0&&reservations.registry.unit(1).reservedGeneralId==null&&reservations.arbiter.owns(FREE_OWNER,1),"invalid formation clears real joining ownership and all reservations");
        check(reservations.registry.unit(2).temporaryTask==TemporaryTask.LOCAL_RESPONSE&&reservations.registry.unit(2).reservedGeneralId==null&&reservations.registry.clearLocalResponse(2),"unrelated response survives invalidation then clears FREE");
        GeneralId second=reservations.birth(6);check(!second.equals(first)&&!reservations.registry.joinAccepted(1,reservations.frame+1),"new forming ID cannot consume prior join receipt or orphan state");
    }
    private static void bootstrapAndTraceCompatibility(){
        CommandArbiter a=new CommandArbiter();List<Long> own=Arrays.asList(1L,2L,3L,4L,5L,6L,7L);a.observe(new CommandArbiter.Stamp("s","team:0",1,100),own);GeneralRegistry r=new GeneralRegistry(a);
        r.bootstrap(Arrays.asList(Arrays.asList(1L,2L,3L,4L,5L,6L),Arrays.asList(7L)),own,12,100,200);
        GeneralView mature=r.generals().get(0),small=r.generals().get(1);
        check(mature.phase==Phase.ACTIVE&&mature.desiredStrength==6&&mature.members.size()==6,"mature bootstrap preserves old ACTIVE roster/initial desired strength");
        check(small.phase==Phase.FORMING&&small.desiredStrength==12&&small.members.equals(Collections.singleton(7L))&&small.anchorKnown,"small entry seed is FORMING with explicit passed target and own anchor");
        check(a.owns(small.owner,7)&&a.ownerGeneration(7)==2,"small entry seed retains actual bootstrap owner migration");
        Fixture f=new Fixture();f.admit(1);f.registry.drainChanges();GeneralId g=f.birth(6);Map<String,Object> birth=f.registry.drainChanges().get(0);
        check("GENERAL_FORMATION_CREATED".equals(birth.get("reason"))&&birth.containsKey("before")&&birth.get("before")==null&&birth.get("after") instanceof Map,"birth trace exports reason and old/new General state");
        Map<?,?> after=(Map<?,?>)birth.get("after");check("FORMING".equals(after.get("phase"))&&after.get("desiredStrength").equals(6)&&((List<?>)after.get("members")).isEmpty()&&after.get("anchorKnown").equals(true),"trace explicitly distinguishes empty formation and its legal anchor");
        f.registry.updateHealthRole(1,HealthRole.NORMAL);check(f.registry.drainChanges().isEmpty(),"same-role health fact refresh has no duplicate control transition");
        f.registry.invalidateGeneral(g);Map<String,Object> gone=f.registry.drainChanges().get(0);check(gone.get("before") instanceof Map&&gone.containsKey("after")&&gone.get("after")==null,"formation invalidation retains old-state trace");
    }
    public static void main(String[] args){emptyBirthAndFirstArrival();sixHealthyArrivalsAndNoDowngrade();ownershipAndLiveHealthAuthority();localResponseAndRetainedFormation();deathInvalidationRebirthAndABA();bootstrapAndTraceCompatibility();System.out.println("GeneralFormationHarness checks="+checks+" PASS");}
}
