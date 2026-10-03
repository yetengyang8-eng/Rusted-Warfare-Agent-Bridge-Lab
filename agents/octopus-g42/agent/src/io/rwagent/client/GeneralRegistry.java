package io.rwagent.client;

import java.util.*;

/** Own-force allocation and membership, independent of enemy policy and economic receipts.
 * Every control transition changes the real arbiter owner. A queued join move is not arrival.
 * Cohorts seed bootstrap once. An explicitly requested empty formation uses a legal own
 * rally anchor; even its first recruit needs a later own-position arrival witness. */
public final class GeneralRegistry {
    public static final String FREE_OWNER="force:free";
    public enum Allocation {FREE,PENDING_JOIN,ASSIGNED}
    public enum Membership {UNATTACHED,JOINING,ATTACHED}
    public enum TemporaryTask {NONE,LOCAL_RESPONSE}
    public enum HealthRole {NORMAL,RECOVERING}
    public enum Phase {FORMING,ACTIVE}
    public static final class GeneralId {
        public final long value;
        public GeneralId(long value){if(value<=0)throw new IllegalArgumentException("Positive General ID required");this.value=value;}
        public String owner(){return "general:"+value;}
        public String joinOwner(){return "join:"+value;}
        public boolean equals(Object other){return other instanceof GeneralId&&((GeneralId)other).value==value;}
        public int hashCode(){return Long.valueOf(value).hashCode();}
        public String toString(){return Long.toString(value);}
    }
    public static final class UnitView {
        public final long unitId,freeSinceFrame,joinAcceptedFrame,ownerGeneration,healthObservedFrame;
        public final String owner,externalOwner;
        public final Allocation allocation;
        public final Membership membership;
        public final TemporaryTask temporaryTask;
        public final HealthRole healthRole;
        public final GeneralId generalId,reservedGeneralId;
        public final boolean localResponseDetachedFromGeneral;
        private UnitView(UnitState u,long generation){
            unitId=u.id;owner=u.owner;externalOwner=u.externalOwner;allocation=u.allocation;membership=u.membership;
            temporaryTask=u.temporaryTask;healthRole=u.healthRole;generalId=u.general;reservedGeneralId=u.reservation;
            freeSinceFrame=u.freeSinceFrame;joinAcceptedFrame=u.joinAcceptedFrame;ownerGeneration=generation;
            localResponseDetachedFromGeneral=u.localResponseDetachedFromGeneral;
            healthObservedFrame=u.healthObservedFrame;
        }
        public Map<String,Object> metadata(){return fields("unitId",unitId,"owner",owner,"ownerGeneration",ownerGeneration,
                "allocation",allocation.name(),"membership",membership.name(),"temporaryTask",temporaryTask.name(),"healthRole",healthRole.name(),
                "generalId",generalId==null?null:generalId.value,"reservedGeneralId",reservedGeneralId==null?null:reservedGeneralId.value,
                "externalOwner",externalOwner,"freeSinceFrame",freeSinceFrame,"joinAcceptedFrame",joinAcceptedFrame,
                "localResponseDetachedFromGeneral",localResponseDetachedFromGeneral,"healthObservedFrame",healthObservedFrame);}
    }
    public static final class GeneralView {
        public final GeneralId id;
        public final String owner;
        public final Set<Long> members,reservations;
        public final int desiredStrength;
        public final double x,y;
        public final boolean centroidKnown;
        public final boolean goalKnown;
        public final double goalX,goalY;
        public final Long targetEnemyId;
        public final long centroidFrame;
        public final Phase phase;
        public final boolean anchorKnown,joinTargetKnown;
        public final double anchorX,anchorY,joinTargetX,joinTargetY;
        public final long anchorFrame,joinTargetFrame;
        public final String joinTargetSource;
        public final int healthyAttachedStrength;
        private GeneralView(GeneralState g,int healthyAttached,long currentFrame){id=g.id;owner=g.id.owner();members=immutableSet(g.members);reservations=immutableSet(g.reservations);
            desiredStrength=g.desiredStrength;x=g.x;y=g.y;centroidKnown=g.centroidKnown;centroidFrame=g.centroidFrame;
            goalKnown=g.goalKnown;goalX=g.goalX;goalY=g.goalY;targetEnemyId=g.targetEnemyId;phase=g.phase;
            anchorKnown=g.anchorKnown;anchorX=g.anchorX;anchorY=g.anchorY;anchorFrame=g.anchorFrame;healthyAttachedStrength=healthyAttached;
            boolean rally=phase==Phase.FORMING&&anchorKnown;
            joinTargetKnown=rally||!g.members.isEmpty()&&centroidKnown&&centroidFrame==currentFrame;
            joinTargetX=rally?anchorX:x;joinTargetY=rally?anchorY:y;joinTargetFrame=rally?anchorFrame:centroidFrame;
            joinTargetSource=!joinTargetKnown?"UNKNOWN":rally?"LEGAL_OWN_RALLY_ANCHOR":"CURRENT_ATTACHED_CENTROID";}
        public Map<String,Object> metadata(){return fields("generalId",id.value,"owner",owner,"members",new ArrayList<Long>(members),
                "reservations",new ArrayList<Long>(reservations),"desiredStrength",desiredStrength,"centroidKnown",centroidKnown,"centroidFrame",centroidFrame,
                "centroidX",centroidKnown?x:null,"centroidY",centroidKnown?y:null,"goalKnown",goalKnown,
                "goalX",goalKnown?goalX:null,"goalY",goalKnown?goalY:null,"targetEnemyId",targetEnemyId,
                "phase",phase.name(),"healthyAttachedStrength",healthyAttachedStrength,"anchorKnown",anchorKnown,"anchorX",anchorKnown?anchorX:null,"anchorY",anchorKnown?anchorY:null,"anchorFrame",anchorFrame,
                "joinTargetKnown",joinTargetKnown,"joinTargetX",joinTargetKnown?joinTargetX:null,"joinTargetY",joinTargetKnown?joinTargetY:null,"joinTargetFrame",joinTargetFrame,"joinTargetSource",joinTargetSource);}
    }
    private static final class UnitState {
        final long id;String owner=FREE_OWNER,externalOwner;
        Allocation allocation=Allocation.FREE;Membership membership=Membership.UNATTACHED;
        TemporaryTask temporaryTask=TemporaryTask.NONE;HealthRole healthRole;
        GeneralId general,reservation;long freeSinceFrame,joinStartedFrame=-1,joinAcceptedFrame=-1;
        boolean localResponseDetachedFromGeneral;
        long healthObservedFrame;
        UnitState(long id,HealthRole health,long frame){this.id=id;healthRole=health;freeSinceFrame=healthObservedFrame=frame;}
    }
    private static final class GeneralState {
        final GeneralId id;final int desiredStrength;
        final Set<Long> members=new LinkedHashSet<Long>(),reservations=new LinkedHashSet<Long>();
        double x,y;boolean centroidKnown;long centroidFrame=-1;
        double goalX,goalY;boolean goalKnown;Long targetEnemyId;
        Phase phase;boolean anchorKnown;double anchorX,anchorY;long anchorFrame=-1;
        GeneralState(GeneralId id,Collection<Long> initial,int desired,Phase phase){this.id=id;members.addAll(initial);desiredStrength=desired;this.phase=phase;}
    }
    private final CommandArbiter arbiter;
    private final Map<Long,UnitState> units=new LinkedHashMap<Long,UnitState>();
    private final Map<GeneralId,GeneralState> generals=new LinkedHashMap<GeneralId,GeneralState>();
    private final List<Map<String,Object>> changes=new ArrayList<Map<String,Object>>();
    private boolean bootstrapped;private long nextGeneral;
    public GeneralRegistry(CommandArbiter arbiter){if(arbiter==null)throw new IllegalArgumentException("Arbiter required");this.arbiter=arbiter;}

    public void bootstrap(Collection<? extends Collection<Long>> groups,Collection<Long> ordinaryIds){
        bootstrapObserved(groups,ordinaryIds,LocalArmyDirector.FORM_MINIMUM,null,null);
    }
    /** Entry seeds migrate existing ready ordinary members; small seeds remain FORMING. */
    public void bootstrap(Collection<? extends Collection<Long>> groups,Collection<Long> ordinaryIds,int formingDesiredStrength,double anchorX,double anchorY){
        validateFormation(formingDesiredStrength,anchorX,anchorY);bootstrapObserved(groups,ordinaryIds,formingDesiredStrength,anchorX,anchorY);
    }
    private void bootstrapObserved(Collection<? extends Collection<Long>> groups,Collection<Long> ordinaryIds,int formingDesiredStrength,Double anchorX,Double anchorY){
        if(bootstrapped||!units.isEmpty())throw new IllegalStateException("General bootstrap occurs once");
        if(groups==null||ordinaryIds==null)throw new IllegalArgumentException("Bootstrap observations required");
        Set<Long> observed=validIds(ordinaryIds),assigned=new HashSet<Long>();List<Set<Long>> seeds=new ArrayList<Set<Long>>();
        for(Collection<Long> group:groups){Set<Long> ids=validIds(group);if(ids.isEmpty())throw new IllegalArgumentException("Empty General seed");
            for(Long id:ids)if(!observed.contains(id)||!assigned.add(id))throw new IllegalArgumentException("General seeds must be distinct observed ordinary actors");seeds.add(ids);}
        if(!observed.isEmpty()&&arbiter.validate(arbiter.stamp(),CommandArbiter.DEFAULT_OWNER,observed)!=null)
            throw new IllegalStateException("Bootstrap actors must be unleased own observations");
        for(Long id:observed)if(!admitFree(id,HealthRole.NORMAL))throw new IllegalStateException("Bootstrap FREE claim failed");
        for(Set<Long> seed:seeds){boolean mature=seed.size()>=LocalArmyDirector.FORM_MINIMUM;
            GeneralState g=new GeneralState(new GeneralId(++nextGeneral),seed,mature?seed.size():Math.max(formingDesiredStrength,LocalArmyDirector.FORM_MINIMUM),mature?Phase.ACTIVE:Phase.FORMING);
            if(anchorX!=null)setAnchor(g,anchorX,anchorY);generals.put(g.id,g);
            for(Long id:seed){UnitState u=units.get(id);UnitView before=view(u);if(!moveOwner(u,g.id.owner()))throw new IllegalStateException("Bootstrap General transfer failed");
                u.general=g.id;u.allocation=Allocation.ASSIGNED;u.membership=Membership.ATTACHED;change("BOOTSTRAP_GENERAL_MEMBER",before,u);}
            changes.add(fields("reason","GENERAL_CREATED_FROM_SEED","gameTimeMs",arbiter.stamp().gameTimeMs,"sourceFrame",arbiter.stamp().frame,
                    "before",null,"after",generalView(g).metadata()));}
        bootstrapped=true;
    }
    /** Caller supplies an observed own rally site. Creation alone leases no member or command. */
    public GeneralId createForming(int desiredStrength,double anchorX,double anchorY){
        validateFormation(desiredStrength,anchorX,anchorY);
        if(hasValidGeneral())return null;
        boolean readyFree=false;for(UnitState u:units.values())if(freeEligible(u)&&u.healthObservedFrame==arbiter.stamp().frame
                &&arbiter.validate(arbiter.stamp(),FREE_OWNER,Collections.singletonList(u.id))==null){readyFree=true;break;}
        if(!readyFree)return null;
        GeneralState g=new GeneralState(new GeneralId(++nextGeneral),Collections.<Long>emptySet(),desiredStrength,Phase.FORMING);setAnchor(g,anchorX,anchorY);generals.put(g.id,g);
        changes.add(fields("reason","GENERAL_FORMATION_CREATED","gameTimeMs",arbiter.stamp().gameTimeMs,"sourceFrame",arbiter.stamp().frame,
                "before",null,"after",generalView(g).metadata()));return g.id;
    }
    /** A retained empty FORMING General is valid until its own-state adapter explicitly invalidates it. */
    public boolean hasValidGeneral(){return !generals.isEmpty();}
    public boolean hasActiveGeneral(){for(GeneralState g:generals.values())if(g.phase==Phase.ACTIVE)return true;return false;}
    /** Current health facts cannot create readiness, membership or a native arrival witness. */
    public boolean refreshFormation(){
        boolean changed=false;for(GeneralState g:generals.values())if(g.phase==Phase.FORMING&&healthyAttached(g)>=LocalArmyDirector.FORM_MINIMUM){
            Map<String,Object> before=generalView(g).metadata();g.phase=Phase.ACTIVE;changed=true;
            changes.add(fields("reason","GENERAL_FORMATION_ACTIVE","gameTimeMs",arbiter.stamp().gameTimeMs,"sourceFrame",arbiter.stamp().frame,
                    "before",before,"after",generalView(g).metadata()));}return changed;
    }
    public boolean admitFree(long id,HealthRole health){
        if(id<0||health==null)throw new IllegalArgumentException("Observed ordinary actor and health required");
        if(units.containsKey(id)||arbiter.reserved(id)||!arbiter.claim(FREE_OWNER,id))return false;
        UnitState u=new UnitState(id,health,arbiter.stamp().frame);units.put(id,u);change("ORDINARY_ADMITTED_FREE",null,u);return true;
    }
    public boolean updateHealthRole(long id,HealthRole health){
        if(health==null)throw new IllegalArgumentException("Health role required");UnitState u=units.get(id);if(u==null)return false;
        UnitView before=view(u);u.healthObservedFrame=arbiter.stamp().frame;if(u.healthRole==health)return false;
        u.healthRole=health;change("HEALTH_ROLE_UPDATED",before,u);return true;
    }
    public boolean updateGeneralCentroid(GeneralId id,double x,double y){
        if(!Double.isFinite(x)||!Double.isFinite(y))throw new IllegalArgumentException("Legal own centroid required");
        GeneralState g=generals.get(id);if(g==null||g.members.isEmpty())return false;g.x=x;g.y=y;g.centroidKnown=true;g.centroidFrame=arbiter.stamp().frame;return true;
    }
    /** Caller invokes only after an actual native General command was accepted. */
    public boolean updateGeneralGoal(GeneralId id,double x,double y,Long targetEnemyId){
        if(!Double.isFinite(x)||!Double.isFinite(y)||targetEnemyId!=null&&targetEnemyId<0)throw new IllegalArgumentException("Accepted General goal required");
        GeneralState g=generals.get(id);if(g==null)return false;Map<String,Object> before=generalView(g).metadata();g.goalX=x;g.goalY=y;g.targetEnemyId=targetEnemyId;g.goalKnown=true;
        changes.add(fields("reason","GENERAL_GOAL_NATIVE_ACCEPTED","sourceFrame",arbiter.stamp().frame,"gameTimeMs",arbiter.stamp().gameTimeMs,
                "before",before,"after",generalView(g).metadata()));return true;
    }
    public UnitView unit(long id){UnitState u=units.get(id);return u==null?null:view(u);}
    public GeneralView general(GeneralId id){GeneralState g=generals.get(id);return g==null?null:generalView(g);}
    public List<UnitView> units(){List<UnitView> out=new ArrayList<UnitView>();for(UnitState u:units.values())out.add(view(u));return Collections.unmodifiableList(out);}
    public List<GeneralView> generals(){List<GeneralView> out=new ArrayList<GeneralView>();for(GeneralState g:generals.values())out.add(generalView(g));return Collections.unmodifiableList(out);}
    public Set<Long> ordinaryActorIds(){Set<Long> out=new LinkedHashSet<Long>();for(UnitState u:units.values())if(u.externalOwner==null)out.add(u.id);return immutableSet(out);}
    public Set<Long> attachedActorIds(GeneralId id){GeneralState g=generals.get(id);return g==null?Collections.<Long>emptySet():immutableSet(g.members);}
    public Set<Long> freeCandidateIds(){Set<Long> out=new LinkedHashSet<Long>();for(UnitState u:units.values())if(freeEligible(u))out.add(u.id);return immutableSet(out);}
    public int reservedJoinCount(GeneralId id){GeneralState g=generals.get(id);return g==null?0:g.reservations.size();}
    public List<Map<String,Object>> drainChanges(){List<Map<String,Object>> out=new ArrayList<Map<String,Object>>(changes);changes.clear();return out;}

    public boolean requestJoin(long id,GeneralId target){
        UnitState u=units.get(id);GeneralState g=generals.get(target);
        if(u==null||g==null||u.externalOwner!=null||u.membership!=Membership.UNATTACHED||u.reservation!=null||u.healthRole!=HealthRole.NORMAL
                ||u.localResponseDetachedFromGeneral||arbiter.stamp().frame<=u.freeSinceFrame
                ||arbiter.validate(arbiter.stamp(),u.owner,Collections.singletonList(id))!=null)return false;
        UnitView before=view(u);u.reservation=target;u.allocation=Allocation.PENDING_JOIN;g.reservations.add(id);change("JOIN_RESERVED",before,u);return true;
    }
    public boolean beginJoining(long id){
        UnitState u=units.get(id);if(u==null||u.reservation==null||!generals.containsKey(u.reservation)||u.membership!=Membership.UNATTACHED
                ||u.temporaryTask!=TemporaryTask.NONE||u.externalOwner!=null)return false;
        UnitView before=view(u);if(!moveOwner(u,u.reservation.joinOwner()))return false;
        u.membership=Membership.JOINING;u.joinStartedFrame=arbiter.stamp().frame;u.joinAcceptedFrame=-1;change("JOINING_STARTED",before,u);return true;
    }
    public boolean joinAccepted(long id,long receiptFrame){
        UnitState u=units.get(id);if(u==null||u.membership!=Membership.JOINING||receiptFrame<u.joinStartedFrame||receiptFrame<=u.joinAcceptedFrame||!arbiter.owns(u.owner,id))return false;
        UnitView before=view(u);u.joinAcceptedFrame=receiptFrame;change("JOIN_MOVE_ACCEPTED_NOT_ARRIVED",before,u);return true;
    }
    /** Trusted own-observation adapter only. Receipt and elapsed time cannot attach membership. */
    public boolean observeJoinPosition(long id,CommandArbiter.Stamp observation,double x,double y,double radius){
        if(observation==null||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(radius)||radius<=0)return false;
        UnitState u=units.get(id);GeneralState g=u==null?null:generals.get(u.reservation);CommandArbiter.Stamp current=arbiter.stamp();
        if(u==null||g==null||u.membership!=Membership.JOINING||u.joinAcceptedFrame<0||!current.samePlayer(observation)
                ||current.frame!=observation.frame||current.gameTimeMs!=observation.gameTimeMs||observation.frame<=u.joinAcceptedFrame)return false;
        GeneralView target=generalView(g);if(!target.joinTargetKnown||Math.hypot(x-target.joinTargetX,y-target.joinTargetY)>radius)return false;
        UnitView before=view(u);if(!moveOwner(u,g.id.owner()))return false;
        g.reservations.remove(id);g.members.add(id);u.general=g.id;u.reservation=null;u.allocation=Allocation.ASSIGNED;u.membership=Membership.ATTACHED;
        u.joinStartedFrame=u.joinAcceptedFrame=-1;change("JOIN_ARRIVAL_OWN_POSITION_WITNESSED",before,u);return true;
    }
    public boolean beginLocalResponse(long id,String responseOwner){
        if(!externalTaskOwner(responseOwner))return false;UnitState u=units.get(id);
        if(u==null||u.membership==Membership.JOINING||u.externalOwner!=null||u.temporaryTask!=TemporaryTask.NONE)return false;
        UnitView before=view(u);if(!moveOwner(u,responseOwner))return false;
        if(u.general!=null){u.localResponseDetachedFromGeneral=true;GeneralState g=generals.get(u.general);if(g!=null)g.members.remove(id);u.general=null;clearReservation(u);
            u.membership=Membership.UNATTACHED;u.allocation=Allocation.FREE;u.freeSinceFrame=arbiter.stamp().frame;}
        u.temporaryTask=TemporaryTask.LOCAL_RESPONSE;change("LOCAL_RESPONSE_STARTED",before,u);return true;
    }
    public boolean clearLocalResponse(long id){
        UnitState u=units.get(id);if(u==null||u.temporaryTask!=TemporaryTask.LOCAL_RESPONSE)return false;
        UnitView before=view(u);boolean joining=u.reservation!=null&&generals.containsKey(u.reservation);
        if(!moveOwner(u,joining?u.reservation.joinOwner():FREE_OWNER))return false;
        u.temporaryTask=TemporaryTask.NONE;
        u.localResponseDetachedFromGeneral=false;
        if(joining){u.membership=Membership.JOINING;u.joinStartedFrame=arbiter.stamp().frame;u.joinAcceptedFrame=-1;}
        else{clearReservation(u);makeFree(u);}
        change(joining?"LOCAL_RESPONSE_CLEARED_JOINING":"LOCAL_RESPONSE_CLEARED_FREE",before,u);return true;
    }
    public boolean cancelJoin(long id){
        UnitState u=units.get(id);if(u==null||u.reservation==null||!arbiter.owns(u.owner,id))return false;UnitView before=view(u);
        if(u.membership==Membership.JOINING&&!moveOwner(u,FREE_OWNER))return false;
        clearReservation(u);u.allocation=Allocation.FREE;u.membership=Membership.UNATTACHED;u.joinStartedFrame=u.joinAcceptedFrame=-1;
        if(u.temporaryTask==TemporaryTask.NONE)u.freeSinceFrame=arbiter.stamp().frame;
        change("JOIN_RESERVATION_CANCELLED",before,u);return true;
    }
    public boolean invalidateGeneral(GeneralId id){
        GeneralState g=generals.get(id);if(g==null)return false;Map<String,Object> beforeGeneral=generalView(g).metadata();
        Set<Long> affected=new LinkedHashSet<Long>(g.members);affected.addAll(g.reservations);
        // Do not mutate a roster around an ownership mismatch owned by another controller.
        for(Long actor:affected){UnitState u=units.get(actor);if(u==null||!arbiter.owns(u.owner,actor))return false;}
        for(Long actor:affected){UnitState u=units.get(actor);UnitView before=view(u);
            if(u.temporaryTask==TemporaryTask.NONE){
                if(!moveOwner(u,FREE_OWNER)){removeUnit(actor);continue;}
                clearReservation(u);makeFree(u);
            }else{clearReservation(u);u.general=null;u.allocation=Allocation.FREE;u.membership=Membership.UNATTACHED;}
            change("GENERAL_INVALIDATED_RELEASE",before,u);
        }
        generals.remove(id);changes.add(fields("reason","GENERAL_INVALIDATED","generalId",id.value,"sourceFrame",arbiter.stamp().frame,"gameTimeMs",arbiter.stamp().gameTimeMs,
                "before",beforeGeneral,"after",null));return true;
    }
    public boolean removeUnit(long id){
        UnitState u=units.get(id);if(u==null)return false;UnitView before=view(u);if(!arbiter.releaseActor(u.owner,id))return false;
        if(u.general!=null){GeneralState g=generals.get(u.general);if(g!=null)g.members.remove(id);}clearReservation(u);units.remove(id);
        changes.add(fields("reason","ORDINARY_REMOVED","unitId",id,"before",before.metadata(),"after",null,"ownerGeneration",arbiter.ownerGeneration(id),
                "sourceFrame",arbiter.stamp().frame,"gameTimeMs",arbiter.stamp().gameTimeMs));return true;
    }
    public boolean borrowExternal(long id,String owner){
        if(!externalTaskOwner(owner))return false;UnitState u=units.get(id);if(u==null||!freeEligible(u))return false;
        UnitView before=view(u);if(!moveOwner(u,owner))return false;u.externalOwner=owner;change("EXTERNAL_BORROWED_FREE",before,u);return true;
    }
    public boolean releaseExternal(long id,String owner){
        UnitState u=units.get(id);if(u==null||u.externalOwner==null||!u.externalOwner.equals(owner))return false;
        UnitView before=view(u);if(!moveOwner(u,FREE_OWNER))return false;u.externalOwner=null;makeFree(u);change("EXTERNAL_RELEASED_FREE",before,u);return true;
    }
    private boolean freeEligible(UnitState u){return u.allocation==Allocation.FREE&&u.membership==Membership.UNATTACHED&&u.temporaryTask==TemporaryTask.NONE
            &&u.healthRole==HealthRole.NORMAL&&u.externalOwner==null&&arbiter.owns(FREE_OWNER,u.id);}
    private boolean moveOwner(UnitState u,String owner){if(!arbiter.transfer(u.owner,owner,u.id))return false;u.owner=owner;return true;}
    private void clearReservation(UnitState u){if(u.reservation!=null){GeneralState g=generals.get(u.reservation);if(g!=null)g.reservations.remove(u.id);}u.reservation=null;}
    private void makeFree(UnitState u){u.general=null;u.allocation=Allocation.FREE;u.membership=Membership.UNATTACHED;u.joinStartedFrame=u.joinAcceptedFrame=-1;u.freeSinceFrame=arbiter.stamp().frame;}
    private UnitView view(UnitState u){return new UnitView(u,arbiter.ownerGeneration(u.id));}
    private GeneralView generalView(GeneralState g){return new GeneralView(g,healthyAttached(g),arbiter.stamp().frame);}
    private int healthyAttached(GeneralState g){
        int count=0;for(Long id:g.members){UnitState u=units.get(id);
            if(u!=null&&g.id.equals(u.general)&&u.membership==Membership.ATTACHED&&u.temporaryTask==TemporaryTask.NONE&&u.externalOwner==null
                    &&u.healthRole==HealthRole.NORMAL&&u.healthObservedFrame==arbiter.stamp().frame&&g.id.owner().equals(u.owner)
                    &&arbiter.validate(arbiter.stamp(),u.owner,Collections.singletonList(id))==null)count++;}return count;
    }
    private void setAnchor(GeneralState g,double x,double y){g.anchorKnown=true;g.anchorX=x;g.anchorY=y;g.anchorFrame=arbiter.stamp().frame;}
    private static void validateFormation(int desired,double x,double y){if(desired<LocalArmyDirector.FORM_MINIMUM||!Double.isFinite(x)||!Double.isFinite(y))
        throw new IllegalArgumentException("Formation requires explicit strength >= six and a legal finite own anchor");}
    private void change(String reason,UnitView before,UnitState after){changes.add(fields("reason",reason,"unitId",after.id,"before",before==null?null:before.metadata(),"after",view(after).metadata(),
            "sourceFrame",arbiter.stamp().frame,"gameTimeMs",arbiter.stamp().gameTimeMs));}
    private static boolean externalTaskOwner(String owner){return owner!=null&&!owner.isEmpty()&&!CommandArbiter.DEFAULT_OWNER.equals(owner)&&!FREE_OWNER.equals(owner)&&!owner.startsWith("general:")&&!owner.startsWith("join:");}
    private static Set<Long> validIds(Collection<Long> input){if(input==null)throw new IllegalArgumentException("Observed actors required");Set<Long> ids=new LinkedHashSet<Long>();
        for(Long id:input)if(id==null||id<0||!ids.add(id))throw new IllegalArgumentException("Distinct nonnegative actors required");return ids;}
    private static Set<Long> immutableSet(Collection<Long> values){return Collections.unmodifiableSet(new LinkedHashSet<Long>(values));}
    private static Map<String,Object> fields(Object... pairs){Map<String,Object> result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
}
