package io.rwagent.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Inactive future search contract. Regions are half-open on x to avoid duplicate assignments. */
public final class SearchTask {
    public enum State { SEARCHING, TARGET_DISCOVERED }
    public final SearchAreaNeed need;
    private State state=State.SEARCHING;
    private final Map<Long,Region> regions=new LinkedHashMap<Long,Region>();
    private TargetTask discovered;
    public static final class Region {
        public final double minX,minY,maxX,maxY;public final boolean includeMaxX;
        Region(double x,double y,double mx,double my,boolean last){minX=x;minY=y;maxX=mx;maxY=my;includeMaxX=last;}
        public boolean contains(double x,double y){return x>=minX&&(x<maxX||includeMaxX&&x==maxX)&&y>=minY&&y<=maxY;}
    }
    /** A target handover contains only the current lawful WorldState contact and its source. */
    public static final class TargetTask {
        public final long targetId;public final String sourceObservationId;public final Map<String,Object> currentContact;
        TargetTask(WorldState.EnemyFact fact,WorldState.SourceView source){targetId=fact.id;sourceObservationId=source.observation.id;currentContact=fact.current;}
    }
    public SearchTask(SearchAreaNeed need,Collection<Long> specialists){
        if(need==null||specialists==null||specialists.isEmpty())throw new IllegalArgumentException("need and specialists");this.need=need;
        List<Long> ids=new ArrayList<Long>(new TreeSet<Long>(specialists));double width=(need.maxX-need.minX)/ids.size();
        for(int i=0;i<ids.size();i++){long id=ids.get(i);if(id<0)throw new IllegalArgumentException("specialist id");
            regions.put(id,new Region(need.minX+i*width,need.minY,i==ids.size()-1?need.maxX:need.minX+(i+1)*width,need.maxY,i==ids.size()-1));}
    }
    public Map<Long,Region> regions(){return Collections.unmodifiableMap(regions);}
    public State state(){return state;}
    public TargetTask discovered(){return discovered;}
    public TargetTask discover(WorldState world,long specialist,long enemyId){
        Region region=regions.get(specialist);if(world==null||region==null)return null;
        WorldState.EnemyFact fact=world.enemies().get(enemyId);if(fact==null||!"VISIBLE_AT_SOURCE_SAMPLE".equals(fact.visibility))return null;
        WorldState.SourceView source=world.views().get(fact.sourceScope);
        if(source==null||source.invalid||source.expired||!"/combat/observe".equals(source.observation.endpoint))return null;
        Object x=fact.current.get("x"),y=fact.current.get("y");
        if(!(x instanceof Number)||!(y instanceof Number)||!region.contains(((Number)x).doubleValue(),((Number)y).doubleValue()))return null;
        discovered=new TargetTask(fact,source);state=State.TARGET_DISCOVERED;return discovered;
    }
}
