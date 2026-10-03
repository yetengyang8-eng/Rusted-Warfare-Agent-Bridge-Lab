package io.rwagent.client;

import java.util.*;

/** Client-side task ownership and one shared game-time command gate.
 * Native permissions remain authoritative. One instance belongs to ONE player/session;
 * this does not turn the single-player bridge into a multi-player bridge.
 */
public final class CommandArbiter {
    public static final String DEFAULT_OWNER="rule-main";
    public static final class Stamp {
        public final String session,player;
        public final long frame,gameTimeMs;
        public Stamp(String session,String player,long frame,long gameTimeMs){
            if(session==null||player==null||frame<0||gameTimeMs<0)throw new IllegalArgumentException("Invalid observation identity");
            this.session=session;this.player=player;this.frame=frame;this.gameTimeMs=gameTimeMs;
        }
        boolean samePlayer(Stamp other){return session.equals(other.session)&&player.equals(other.player);}
    }
    public static final class MoveIntent {
        public final Stamp observation;
        public final String owner;
        public final long unitId;
        public final double x,y;
        public MoveIntent(Stamp observation,String owner,long unitId,double x,double y){
            if(observation==null||owner==null||owner.isEmpty()||unitId<0||!Double.isFinite(x)||!Double.isFinite(y))
                throw new IllegalArgumentException("Invalid move intent");
            this.observation=observation;this.owner=owner;this.unitId=unitId;this.x=x;this.y=y;
        }
    }
    private final Map<Long,String> owners=new LinkedHashMap<Long,String>();
    // Retained after release: an A -> main -> A cycle must never revive an old token.
    private final Map<Long,Long> generations=new LinkedHashMap<Long,Long>();
    private final Set<Long> ownUnits=new HashSet<Long>();
    private Stamp latest;
    private long nextCommand;
    private boolean elapsedBudget;
    private long budgetInterval=1000,budgetAnchor;
    private int maxBurst=4,tokens=1;
    public void configureElapsedBudget(long intervalMs,int burst){
        if(intervalMs<=0||burst<1)throw new IllegalArgumentException("Invalid execution budget");
        if(elapsedBudget)throw new IllegalStateException("Execution budget already configured");
        elapsedBudget=true;budgetInterval=intervalMs;maxBurst=burst;tokens=1;
        budgetAnchor=latest==null?-1:latest.gameTimeMs;
    }
    private void accrue(long now){
        if(!elapsedBudget)return;
        if(budgetAnchor<0){budgetAnchor=now;return;}
        long intervals=(now-budgetAnchor)/budgetInterval;
        if(intervals>0){tokens=(int)Math.min((long)maxBurst,tokens+intervals);budgetAnchor+=intervals*budgetInterval;}
    }
    public void observe(Stamp stamp,Collection<Long> units){
        if(latest!=null&&(!latest.samePlayer(stamp)||stamp.frame<latest.frame||stamp.gameTimeMs<latest.gameTimeMs))
            throw new IllegalStateException("Execution observation identity changed or went backwards");
        accrue(stamp.gameTimeMs);latest=stamp;ownUnits.clear();ownUnits.addAll(units);
    }
    public Stamp stamp(){if(latest==null)throw new IllegalStateException("Observe before executing");return latest;}
    /** Trusted observation adapter only: the native production menu is another own-actor
     * observation, potentially newer than /state (e.g. a factory just completed).
     * Never call this with actor IDs proposed by a policy. Next /state replaces this set.
     */
    public void observeProductionActors(Stamp stamp,Collection<Long> units){
        if(latest==null||!latest.samePlayer(stamp)||stamp.frame!=latest.frame||stamp.gameTimeMs!=latest.gameTimeMs)
            throw new IllegalStateException("Production observation identity differs");
        ownUnits.addAll(units);
    }
    public boolean claim(String owner,long unitId){
        if(owner==null||owner.isEmpty()||DEFAULT_OWNER.equals(owner)||!ownUnits.contains(unitId))return false;
        String previous=owners.get(unitId);
        if(previous!=null&&!owner.equals(previous))return false;
        if(previous==null){advance(unitId);owners.put(unitId,owner);}return true;
    }
    public boolean reserved(long unitId){return owners.containsKey(unitId);}
    public boolean owns(String owner,long unitId){return owner.equals(owners.get(unitId));}
    /** Release one actor, including one no longer present in the latest own observation.
     * Shared owners such as the FREE pool must not release their unrelated actors. */
    public boolean releaseActor(String owner,long unitId){
        if(owner==null||!owns(owner,unitId))return false;
        advance(unitId);owners.remove(unitId);return true;
    }
    public boolean release(String owner){
        boolean released=false;
        Iterator<Map.Entry<Long,String>> it=owners.entrySet().iterator();
        while(it.hasNext()){Map.Entry<Long,String> entry=it.next();if(owner.equals(entry.getValue())){advance(entry.getKey());it.remove();released=true;}}
        return released;
    }
    public void clear(){for(Long id:owners.keySet())advance(id);owners.clear();}
    private void advance(long actor){long old=ownerGeneration(actor);if(old==Long.MAX_VALUE)throw new IllegalStateException("Owner generation exhausted");generations.put(actor,old+1);}
    public long ownerGeneration(long actor){Long value=generations.get(actor);return value==null?0:value;}
    public boolean transfer(String from,String to,long actor){
        if(to==null||to.isEmpty()||DEFAULT_OWNER.equals(to)||!ownUnits.contains(actor)||!owns(from,actor))return false;
        if(!from.equals(to)){advance(actor);owners.put(actor,to);}return true;
    }
    public Map<Long,Long> snapshotGenerations(String owner,Collection<Long> actors){
        Map<Long,Long> result=new LinkedHashMap<Long,Long>();for(Long actor:actors){
            if(actor==null||!ownUnits.contains(actor))throw new IllegalArgumentException("Generation snapshot actor not own");result.put(actor,ownerGeneration(actor));}
        return Collections.unmodifiableMap(result);
    }
    public int availableTokens(){return elapsedBudget?tokens:(latest!=null&&latest.gameTimeMs>=nextCommand?1:0);}
    public boolean ready(long now){return latest!=null&&(elapsedBudget?tokens>0:now>=nextCommand);}
    /** Legality only; no token or resource is consumed until native dispatch. */
    public String validate(Stamp stamp,String owner,Collection<Long> units){
        if(latest==null||!latest.samePlayer(stamp)||stamp.frame!=latest.frame||stamp.gameTimeMs!=latest.gameTimeMs)
            return "STALE_OR_FOREIGN_OBSERVATION";
        if(owner==null||owner.isEmpty())return "NO_OWNER";
        if(units.isEmpty())return "NO_ACTOR";
        for(Long id:units){
            if(!ownUnits.contains(id))return "ACTOR_NOT_OWN";
            String held=owners.get(id);
            if(held!=null&&!held.equals(owner))return "ACTOR_OWNED_BY_OTHER_TASK";
            if(!DEFAULT_OWNER.equals(owner)&&!owner.equals(held))return "TASK_HAS_NO_OWNERSHIP";
        }
        return null;
    }
    public String validateGenerations(Collection<Long> units,Map<Long,Long> expected){
        for(Long actor:units){Long value=expected.get(actor);if(value==null||value.longValue()!=ownerGeneration(actor))return "STALE_OWNER_GENERATION";}return null;
    }
    /** Validate the entire group before consuming a slot; rejected native attempts still cost a slot. */
    public String admit(Stamp stamp,String owner,Collection<Long> units){
        String denied=validate(stamp,owner,units);if(denied!=null)return denied;
        return consumeAttempt(stamp);
    }
    String consumeAttempt(Stamp stamp){
        if(!ready(stamp.gameTimeMs))return "COMMAND_GAME_TIME_BUDGET";
        if(elapsedBudget)tokens--;else nextCommand=stamp.gameTimeMs+1000;return null;
    }
}
