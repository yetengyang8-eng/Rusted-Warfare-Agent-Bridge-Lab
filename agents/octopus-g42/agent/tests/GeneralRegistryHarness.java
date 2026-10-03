package io.rwagent.client;

import java.util.*;
import static io.rwagent.client.GeneralRegistry.*;

/** Focused own-force allocation cases; no enemy inference, receipt fabrication or native engine claim. */
public final class GeneralRegistryHarness {
    private static int checks;
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    private static void throwsExpected(Runnable action,String reason){boolean thrown=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException|UnsupportedOperationException expected){thrown=true;}check(thrown,reason);}
    private static final class Fixture {
        final CommandArbiter arbiter=new CommandArbiter();final GeneralRegistry registry=new GeneralRegistry(arbiter);
        final Set<Long> own=new LinkedHashSet<Long>(Arrays.asList(1L,2L,3L,4L,5L,6L,7L,8L));
        Fixture(){frame(10);registry.bootstrap(Arrays.asList(Arrays.asList(1L,2L),Arrays.asList(3L,4L)),own);centroids();}
        void frame(long frame){arbiter.observe(new CommandArbiter.Stamp("s","team:0",frame,frame*100),own);}
        GeneralId a(){return registry.generals().get(0).id;}GeneralId b(){return registry.generals().get(1).id;}
        void centroids(){registry.updateGeneralCentroid(a(),100,100);registry.updateGeneralCentroid(b(),900,900);}
        void owner(long id,String owner){check(owner.equals(registry.unit(id).owner)&&arbiter.owns(owner,id),"registry and real arbiter owner agree for "+id+"="+owner);}
        void invariant(){
            Set<Long> attached=new HashSet<Long>(),reserved=new HashSet<Long>();
            for(GeneralView g:registry.generals()){
                for(Long id:g.members){check(attached.add(id),"membership belongs to exactly one General");UnitView u=registry.unit(id);
                    check(u!=null&&g.id.equals(u.generalId)&&u.membership==Membership.ATTACHED&&u.allocation==Allocation.ASSIGNED&&g.owner.equals(u.owner),"General member and unit snapshot match");}
                for(Long id:g.reservations){check(reserved.add(id),"reservation belongs to exactly one General");UnitView u=registry.unit(id);
                    check(u!=null&&g.id.equals(u.reservedGeneralId)&&u.allocation==Allocation.PENDING_JOIN&&u.membership!=Membership.ATTACHED,"reservation and orthogonal state agree");}
            }
            for(UnitView u:registry.units()){
                check(arbiter.owns(u.owner,u.unitId),"snapshot always has actual control owner");
                check((u.generalId!=null)==(u.membership==Membership.ATTACHED),"only attached member has memberGeneral");
                check((u.reservedGeneralId!=null)==(u.allocation==Allocation.PENDING_JOIN),"pending allocation precisely reflects reservation");
                if(u.membership==Membership.JOINING)check(u.temporaryTask==TemporaryTask.NONE&&u.reservedGeneralId.joinOwner().equals(u.owner),"joining cannot simultaneously respond to harassment");
            }
        }
    }
    private static void bootstrapAndBirth(){
        CommandArbiter a=new CommandArbiter();Set<Long> ids=new LinkedHashSet<Long>(Arrays.asList(1L,2L,3L,4L,5L,6L));a.observe(new CommandArbiter.Stamp("s","team:0",1,0),ids);
        GeneralRegistry r=new GeneralRegistry(a);r.bootstrap(Arrays.asList(Arrays.asList(1L),Arrays.asList(2L),Arrays.asList(3L),Arrays.asList(4L),Arrays.asList(5L)),ids);
        check(r.generals().size()==5,"bootstrap is not limited by old four-cohort cap");Set<String> owners=new HashSet<String>();
        for(GeneralView g:r.generals()){check(owners.add(g.owner)&&g.desiredStrength==LocalArmyDirector.FORM_MINIMUM&&g.phase==Phase.FORMING&&g.members.size()==1,"each small seed creates independent FORMING General with explicit minimum strength");
            long id=g.members.iterator().next();check(a.owns(g.owner,id)&&a.ownerGeneration(id)==2,"bootstrap genuinely claims then transfers General owner");}
        check(r.unit(6).allocation==Allocation.FREE&&r.freeCandidateIds().contains(6L),"unseeded initial unit remains FREE");
        ids.add(7L);a.observe(new CommandArbiter.Stamp("s","team:0",2,100),ids);check(r.admitFree(7,HealthRole.NORMAL),"new observed ordinary recruit admits FREE");
        check(r.generals().size()==5&&r.unit(7).membership==Membership.UNATTACHED&&r.unit(7).freeSinceFrame==2,"birth never automatically attaches or creates General");
        check(!r.requestJoin(7,r.generals().get(0).id),"newborn stays visibly FREE at admission frame");
        throwsExpected(()->r.bootstrap(Collections.<Collection<Long>>emptyList(),Collections.<Long>emptyList()),"bootstrap cannot repeat");
        GeneralView snapshot=r.generals().get(0);throwsExpected(()->snapshot.members.add(99L),"General member snapshot cannot be externally mutated");
    }
    private static void invalidBootstrapAndEmpty(){
        CommandArbiter a=new CommandArbiter();a.observe(new CommandArbiter.Stamp("s","team:0",1,0),Arrays.asList(1L,2L));GeneralRegistry r=new GeneralRegistry(a);
        throwsExpected(()->r.bootstrap(Arrays.asList(Arrays.asList(1L),Arrays.asList(1L)),Arrays.asList(1L,2L)),"duplicate seed actor rejects before ownership mutation");
        check(!a.reserved(1)&&r.units().isEmpty(),"invalid bootstrap has no partial owners");
        throwsExpected(()->r.bootstrap(Arrays.asList(Arrays.asList(3L)),Arrays.asList(1L,2L)),"unobserved seed is rejected");
        r.bootstrap(Collections.<Collection<Long>>emptyList(),Collections.<Long>emptyList());check(r.generals().isEmpty(),"empty entry army explicitly has zero Generals");
        check(r.admitFree(1,HealthRole.NORMAL)&&r.generals().isEmpty(),"first product after empty entry remains FREE with no automatic birth");
        check(!r.admitFree(1,HealthRole.RECOVERING)&&r.unit(1).healthRole==HealthRole.NORMAL,"repeat admission does not reset state");
    }
    private static void joiningWitness(){
        Fixture f=new Fixture();GeneralId a=f.a();long original=f.arbiter.ownerGeneration(5);check(!f.registry.requestJoin(5,a),"FREE admission frame cannot formal allocate");f.frame(11);f.centroids();
        check(f.registry.requestJoin(5,a)&&f.registry.reservedJoinCount(a)==1,"later allocator creates counted pending reservation");f.owner(5,FREE_OWNER);
        check(f.arbiter.ownerGeneration(5)==original&&f.registry.unit(5).membership==Membership.UNATTACHED,"reservation alone does not fake owner transfer or membership");
        check(!f.registry.requestJoin(5,f.b()),"same actor cannot reserve two Generals");check(f.registry.beginJoining(5),"pending join starts actual independent join owner");f.owner(5,a.joinOwner());
        check(f.arbiter.ownerGeneration(5)==original+1&&!f.registry.freeCandidateIds().contains(5L),"joining advances generation and excludes free candidates");
        check(!f.registry.beginLocalResponse(5,"local-crisis:1"),"joining actor does not answer harassment");
        check(!f.registry.observeJoinPosition(5,f.arbiter.stamp(),100,100,80),"near position before accepted move cannot attach");
        check(!f.registry.joinAccepted(5,10)&&f.registry.joinAccepted(5,12),"only non-stale accepted receipt frame records join movement");
        check(f.registry.unit(5).membership==Membership.JOINING&&!f.registry.attachedActorIds(a).contains(5L),"queued join receipt does not attach member");
        f.frame(12);f.centroids();check(!f.registry.observeJoinPosition(5,f.arbiter.stamp(),100,100,80),"receipt frame itself is not later arrival witness");
        f.frame(13);check(!f.registry.observeJoinPosition(5,f.arbiter.stamp(),100,100,80),"stale centroid cannot prove current formation arrival");f.centroids();
        check(!f.registry.observeJoinPosition(5,new CommandArbiter.Stamp("other","team:0",13,1300),100,100,80),"foreign session position cannot attach");
        check(!f.registry.observeJoinPosition(5,new CommandArbiter.Stamp("s","team:1",13,1300),100,100,80),"foreign player position cannot attach");
        check(!f.registry.observeJoinPosition(5,new CommandArbiter.Stamp("s","team:0",12,1200),100,100,80),"stale source frame cannot attach");
        check(!f.registry.observeJoinPosition(5,f.arbiter.stamp(),900,900,80),"elapsed time with distant own position does not attach");
        check(!f.registry.observeJoinPosition(5,f.arbiter.stamp(),Double.NaN,100,80),"unknown own position cannot attach");
        check(f.registry.observeJoinPosition(5,f.arbiter.stamp(),105,100,80),"later legal own position near current destination centroid attaches");f.owner(5,a.owner());
        check(f.arbiter.ownerGeneration(5)==original+2&&f.registry.reservedJoinCount(a)==0&&f.registry.attachedActorIds(a).contains(5L),"arrival transfers owner, moves reservation into member roster");
        check(f.registry.unit(5).allocation==Allocation.ASSIGNED&&f.registry.unit(5).reservedGeneralId==null,"allocation and membership finish cleanly");
        check(!f.registry.observeJoinPosition(5,f.arbiter.stamp(),105,100,80)&&!f.registry.joinAccepted(5,14),"arrival and receipts cannot be replayed after attach");
        f.invariant();
        Fixture emptyCentroid=new Fixture();emptyCentroid.frame(11);emptyCentroid.centroids();check(emptyCentroid.registry.requestJoin(5,emptyCentroid.a())&&emptyCentroid.registry.beginJoining(5)&&emptyCentroid.registry.joinAccepted(5,11),"empty formation witness case has accepted join");
        emptyCentroid.frame(12);emptyCentroid.centroids();emptyCentroid.registry.beginLocalResponse(1,"local-crisis:1");emptyCentroid.registry.beginLocalResponse(2,"local-crisis:1");
        check(!emptyCentroid.registry.updateGeneralCentroid(emptyCentroid.a(),100,100)&&!emptyCentroid.registry.observeJoinPosition(5,emptyCentroid.arbiter.stamp(),100,100,80),"formation without attached members cannot provide a current centroid or arrival witness");
    }
    private static void freeResponseAndPending(){
        Fixture f=new Fixture();f.frame(11);f.centroids();long gen=f.arbiter.ownerGeneration(5);
        check(f.registry.beginLocalResponse(5,"local-crisis:1"),"FREE ordinary can answer local harassment");f.owner(5,"local-crisis:1");
        check(f.registry.requestJoin(5,f.a()),"active FREE-origin local response may coexist with pending join");
        UnitView u=f.registry.unit(5);check(u.allocation==Allocation.PENDING_JOIN&&u.temporaryTask==TemporaryTask.LOCAL_RESPONSE&&u.membership==Membership.UNATTACHED,"LR task and pending allocation are orthogonal");
        check(f.registry.reservedJoinCount(f.a())==1&&!f.registry.beginJoining(5),"LR reservation counts but cannot begin movement before clear");
        check(f.registry.clearLocalResponse(5),"caller current visibility loss clears immediately without timeout");f.owner(5,f.a().joinOwner());
        check(f.registry.unit(5).temporaryTask==TemporaryTask.NONE&&f.registry.unit(5).membership==Membership.JOINING&&f.arbiter.ownerGeneration(5)==gen+2,"cleared pending LR transfers directly into real JOINING");
        check(!f.registry.clearLocalResponse(5)&&!f.registry.beginLocalResponse(5,"local-crisis:2"),"JOINING response cannot be replayed or recruited for harassment");f.invariant();
        Fixture noPending=new Fixture();noPending.frame(11);check(noPending.registry.beginLocalResponse(5,"local-crisis:2")&&noPending.registry.clearLocalResponse(5),"unreserved response clears into FREE");noPending.owner(5,FREE_OWNER);
        check(noPending.registry.unit(5).freeSinceFrame==11&&!noPending.registry.requestJoin(5,noPending.a()),"response-clear same frame preserves visible FREE breakpoint");
        noPending.frame(12);check(noPending.registry.requestJoin(5,noPending.b()),"later frame may explicitly assign formerly responding free actor");
    }
    private static void attachedDetachAndGeneration(){
        Fixture f=new Fixture();Map<Long,Long> old=f.arbiter.snapshotGenerations(f.a().owner(),Arrays.asList(1L));int desired=f.registry.general(f.a()).desiredStrength;
        check(f.registry.beginLocalResponse(1,"local-crisis:3"),"attached General member truly detaches to local response");f.owner(1,"local-crisis:3");
        check(f.registry.unit(1).localResponseDetachedFromGeneral&&f.registry.unit(1).generalId==null&&!f.registry.attachedActorIds(f.a()).contains(1L),"detach removes old membership and records response provenance");
        check(f.registry.general(f.a()).desiredStrength==desired,"detach creates refill deficit without changing formation target");
        check("STALE_OWNER_GENERATION".equals(f.arbiter.validateGenerations(Arrays.asList(1L),old)),"old General intent loses actual ownership generation");
        f.frame(20);check(!f.registry.requestJoin(1,f.a())&&!f.registry.requestJoin(1,f.b()),"detached LR cannot be silently reallocated during its task");
        check(f.registry.clearLocalResponse(1),"detached General response ends FREE without automatic original-General return");f.owner(1,FREE_OWNER);
        check(!f.registry.unit(1).localResponseDetachedFromGeneral&&f.registry.unit(1).membership==Membership.UNATTACHED&&f.registry.unit(1).freeSinceFrame==20,"response-end exposes fresh FREE state");
        check(!f.registry.requestJoin(1,f.a()),"detached clear frame cannot immediately join former General");f.frame(21);
        check(f.registry.requestJoin(1,f.b()),"later explicit allocation may choose a different General");
        check("STALE_OWNER_GENERATION".equals(f.arbiter.validateGenerations(Arrays.asList(1L),old)),"FREE cycle never revives stale General token");f.invariant();
    }
    private static void invalidationAndOrphans(){
        Fixture f=new Fixture();f.frame(11);check(f.registry.requestJoin(5,f.a())&&f.registry.beginJoining(5),"orphan case starts join");check(f.registry.requestJoin(6,f.a())&&f.registry.beginLocalResponse(6,"local-crisis:1"),"LR pending reservation shares General");
        check(f.registry.invalidateGeneral(f.a()),"General invalidation clears members and every reservation");
        check(f.registry.general(new GeneralId(1))==null&&f.registry.unit(5).reservedGeneralId==null&&f.registry.unit(5).membership==Membership.UNATTACHED,"joining orphan releases FREE");f.owner(5,FREE_OWNER);f.owner(1,FREE_OWNER);f.owner(2,FREE_OWNER);
        check(f.registry.unit(6).temporaryTask==TemporaryTask.LOCAL_RESPONSE&&f.registry.unit(6).reservedGeneralId==null,"invalid General cancels LR pending without interrupting response");f.owner(6,"local-crisis:1");
        check(f.registry.clearLocalResponse(6),"orphan LR clear returns FREE");f.owner(6,FREE_OWNER);f.invariant();
        Fixture dead=new Fixture();dead.frame(11);check(dead.registry.requestJoin(5,dead.a())&&dead.registry.beginJoining(5),"dead join actor registered");long gen=dead.arbiter.ownerGeneration(5);dead.own.remove(5L);dead.frame(12);
        check(dead.registry.removeUnit(5)&&dead.registry.unit(5)==null&&dead.registry.reservedJoinCount(dead.a())==0,"missing own actor removes orphan reservation without fake arrival");
        check(!dead.arbiter.reserved(5)&&dead.arbiter.ownerGeneration(5)==gen+1,"dead actor release invalidates generation despite absent own observation");check(!dead.registry.removeUnit(5),"removed orphan is idempotent");
        Fixture cancel=new Fixture();cancel.frame(11);check(cancel.registry.requestJoin(5,cancel.a())&&cancel.registry.beginJoining(5)&&cancel.registry.cancelJoin(5),"cancel actual joining transfers back FREE");cancel.owner(5,FREE_OWNER);
        check(cancel.registry.reservedJoinCount(cancel.a())==0&&!cancel.registry.requestJoin(5,cancel.a()),"cancel releases slot and exposes fresh FREE frame");
        Fixture ownerMismatch=new Fixture();ownerMismatch.arbiter.transfer(ownerMismatch.a().owner(),"external:other",1);
        check(!ownerMismatch.registry.invalidateGeneral(ownerMismatch.a())&&ownerMismatch.registry.attachedActorIds(ownerMismatch.a()).size()==2,"ownership mismatch cannot partly invalidate roster");
    }
    private static void externalHealthAndGoals(){
        Fixture f=new Fixture();long gen=f.arbiter.ownerGeneration(5);check(f.registry.borrowExternal(5,"recon:1"),"Recon borrows actual FREE owner");f.owner(5,"recon:1");
        check(!f.registry.ordinaryActorIds().contains(5L)&&!f.registry.freeCandidateIds().contains(5L),"external Recon is excluded from ordinary stats and free pool");
        check(!f.registry.requestJoin(5,f.a())&&!f.registry.beginLocalResponse(5,"local-crisis:1"),"external actor cannot be double leased");
        check(!f.registry.releaseExternal(5,"recon:other")&&f.registry.releaseExternal(5,"recon:1"),"only actual borrowing owner releases its actor");f.owner(5,FREE_OWNER);
        check(f.registry.ordinaryActorIds().contains(5L)&&f.arbiter.ownerGeneration(5)==gen+2,"external return restores ordinary accounting with fresh generation");
        check(!f.registry.borrowExternal(1,"recon:2"),"Recon cannot borrow attached General actor");check(f.registry.updateHealthRole(6,HealthRole.RECOVERING)&&!f.registry.freeCandidateIds().contains(6L),"recovery role excludes formal candidates independently");
        long memberGeneration=f.arbiter.ownerGeneration(1);check(f.registry.updateHealthRole(1,HealthRole.RECOVERING)&&f.registry.unit(1).membership==Membership.ATTACHED&&f.arbiter.ownerGeneration(1)==memberGeneration,"health role is orthogonal and does not silently detach or migrate General ownership");
        check(!f.registry.borrowExternal(6,"recon:2"),"recovering FREE actor cannot be borrowed");f.frame(11);check(!f.registry.requestJoin(6,f.a()),"recovering FREE actor cannot be allocated");
        check(f.registry.updateHealthRole(6,HealthRole.NORMAL)&&f.registry.requestJoin(6,f.a()),"health recovery does not change owner and later permits allocation");
        check(f.registry.ordinaryActorIds().size()==8,"ordinary accounting includes attached, joining, pending and LR roles");
        GeneralView before=f.registry.general(f.a());check(!before.goalKnown&&f.registry.updateGeneralGoal(f.a(),300,400,99L),"actual caller receipt updates independent General goal");
        check(f.registry.general(f.a()).goalKnown&&f.registry.general(f.a()).targetEnemyId==99L&&!f.registry.general(f.b()).goalKnown,"one General accepted target does not overwrite another");
        check(!before.goalKnown&&before.members.size()==2,"published General snapshots remain immutable across changes");
        throwsExpected(()->f.registry.updateGeneralGoal(f.a(),Double.NaN,1,null),"unknown goal cannot become runtime goal");
        List<Map<String,Object>> changes=f.registry.drainChanges();check(!changes.isEmpty()&&f.registry.drainChanges().isEmpty(),"changes drain once for root trace without policy side effects");f.invariant();
    }
    private static void perActorRelease(){
        CommandArbiter a=new CommandArbiter();a.observe(new CommandArbiter.Stamp("s","team:0",1,0),Arrays.asList(1L,2L));a.claim(FREE_OWNER,1);a.claim(FREE_OWNER,2);long generation=a.ownerGeneration(1);
        check(!a.releaseActor("recon:wrong",1)&&a.ownerGeneration(1)==generation,"foreign release cannot change generation");
        check(a.releaseActor(FREE_OWNER,1)&&!a.reserved(1)&&a.owns(FREE_OWNER,2),"per actor FREE release preserves unrelated actor");
        check(a.ownerGeneration(1)==generation+1&&!a.releaseActor(FREE_OWNER,1),"release invalidates once and cannot replay");
    }
    @SuppressWarnings("unchecked") private static void generalTraceSnapshots(){
        Fixture f=new Fixture();int created=0;
        for(Map<String,Object> change:f.registry.drainChanges())if("GENERAL_CREATED_FROM_SEED".equals(change.get("reason"))){
            created++;check(change.containsKey("before")&&change.get("before")==null&&change.get("after") instanceof Map,"General creation exports explicit old absence and new state");}
        check(created==2,"independent General creation changes recorded");f.frame(11);f.centroids();
        check(f.registry.drainChanges().isEmpty(),"centroid fact refresh produces no repetitive control transitions");
        f.registry.updateGeneralGoal(f.a(),300,400,99L);Map<String,Object> goal=f.registry.drainChanges().get(0);
        Map<String,Object> before=(Map<String,Object>)goal.get("before"),after=(Map<String,Object>)goal.get("after");
        check("GENERAL_GOAL_NATIVE_ACCEPTED".equals(goal.get("reason"))&&Boolean.FALSE.equals(before.get("goalKnown"))&&Boolean.TRUE.equals(after.get("goalKnown")),"accepted General goal trace carries before and after goal state");
        check(after.get("targetEnemyId").equals(99L)&&before.get("targetEnemyId")==null,"old target remains unknown in retained snapshot");
        f.registry.requestJoin(5,f.a());f.registry.beginJoining(5);f.registry.drainChanges();f.registry.invalidateGeneral(f.a());Map<String,Object> invalid=null;
        for(Map<String,Object> change:f.registry.drainChanges())if("GENERAL_INVALIDATED".equals(change.get("reason")))invalid=change;
        check(invalid!=null&&invalid.get("before") instanceof Map&&invalid.containsKey("after")&&invalid.get("after")==null,"General invalidation exports old state and explicit new absence");
        Map<String,Object> old=(Map<String,Object>)invalid.get("before");check(((List<?>)old.get("members")).size()==2&&((List<?>)old.get("reservations")).size()==1,"General old-state trace retains membership and reservation before release");
        check(((List<?>)after.get("members")).size()==2,"later invalidation cannot rewrite an earlier goal trace snapshot");
    }
    public static void main(String[] args){bootstrapAndBirth();invalidBootstrapAndEmpty();joiningWitness();freeResponseAndPending();attachedDetachAndGeneration();invalidationAndOrphans();externalHealthAndGoals();perActorRelease();generalTraceSnapshots();System.out.println("GeneralRegistryHarness checks="+checks+" PASS");}
}
