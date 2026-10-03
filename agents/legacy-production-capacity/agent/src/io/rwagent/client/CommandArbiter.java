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
    private final Set<Long> ownUnits=new HashSet<Long>();
    private Stamp latest;
    private long nextCommand;
    public void observe(Stamp stamp,Collection<Long> units){
        if(latest!=null&&(!latest.samePlayer(stamp)||stamp.frame<latest.frame||stamp.gameTimeMs<latest.gameTimeMs))
            throw new IllegalStateException("Execution observation identity changed or went backwards");
        latest=stamp;ownUnits.clear();ownUnits.addAll(units);
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
        if(owner==null||DEFAULT_OWNER.equals(owner)||!ownUnits.contains(unitId))return false;
        String previous=owners.get(unitId);
        if(previous!=null&&!owner.equals(previous))return false;
        owners.put(unitId,owner);return true;
    }
    public boolean reserved(long unitId){return owners.containsKey(unitId);}
    public boolean owns(String owner,long unitId){return owner.equals(owners.get(unitId));}
    public boolean release(String owner){
        boolean released=false;
        Iterator<Map.Entry<Long,String>> it=owners.entrySet().iterator();
        while(it.hasNext())if(owner.equals(it.next().getValue())){it.remove();released=true;}
        return released;
    }
    public void clear(){owners.clear();}
    public boolean ready(long now){return latest!=null&&now>=nextCommand;}
    /** Validate the entire group before consuming a slot; rejected native attempts still cost a slot. */
    public String admit(Stamp stamp,String owner,Collection<Long> units){
        if(latest==null||!latest.samePlayer(stamp)||stamp.frame!=latest.frame||stamp.gameTimeMs!=latest.gameTimeMs)
            return "STALE_OR_FOREIGN_OBSERVATION";
        if(units.isEmpty())return "NO_ACTOR";
        for(Long id:units){
            if(!ownUnits.contains(id))return "ACTOR_NOT_OWN";
            String held=owners.get(id);
            if(held!=null&&!held.equals(owner))return "ACTOR_OWNED_BY_OTHER_TASK";
            if(!DEFAULT_OWNER.equals(owner)&&!owner.equals(held))return "TASK_HAS_NO_OWNERSHIP";
        }
        if(!ready(stamp.gameTimeMs))return "COMMAND_GAME_TIME_BUDGET";
        nextCommand=stamp.gameTimeMs+1000;return null;
    }
}
