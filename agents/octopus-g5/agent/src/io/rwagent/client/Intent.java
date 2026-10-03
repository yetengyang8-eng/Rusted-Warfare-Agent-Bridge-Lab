package io.rwagent.client;

import java.util.*;

/** Immutable policy proposal. A native receipt remains separate from later execution witnesses. */
public final class Intent {
    public final String intentId,owner,kind,lane,sourceObservationId,sourceRequestPath,path;
    public final String costSourceObservationId,costSourceRequestPath;
    public final Map<Long,Long> ownerGenerations;
    public final List<Long> actorIds;
    public final int priority;
    public final CommandArbiter.Stamp observation;
    public final Commitment commitment;
    public Intent(String id,String owner,Map<Long,Long> generations,Collection<Long> actors,String kind,String lane,int priority,
                  CommandArbiter.Stamp observation,String sourceObservationId,String sourceRequestPath,String path,Commitment commitment){
        this(id,owner,generations,actors,kind,lane,priority,observation,sourceObservationId,sourceRequestPath,path,commitment,null,null);
    }
    public Intent(String id,String owner,Map<Long,Long> generations,Collection<Long> actors,String kind,String lane,int priority,
                  CommandArbiter.Stamp observation,String sourceObservationId,String sourceRequestPath,String path,Commitment commitment,
                  String costSourceObservationId,String costSourceRequestPath){
        if(empty(id)||empty(owner)||empty(kind)||empty(lane)||observation==null||empty(sourceObservationId)||empty(sourceRequestPath)||empty(path)||commitment==null)
            throw new IllegalArgumentException("Intent identity, provenance and commitment required");
        this.intentId=id;this.owner=owner;this.kind=kind;this.lane=lane;this.priority=priority;this.observation=observation;
        this.sourceObservationId=sourceObservationId;this.sourceRequestPath=sourceRequestPath;this.path=path;this.commitment=commitment;
        if((costSourceObservationId==null)!=(costSourceRequestPath==null))throw new IllegalArgumentException("Cost source identity must be a pair");
        if(costSourceRequestPath!=null&&("/state".equals(costSourceRequestPath)||costSourceRequestPath.startsWith("/state?")))
            throw new IllegalArgumentException("State is not a native price quote");
        this.costSourceObservationId=costSourceObservationId;this.costSourceRequestPath=costSourceRequestPath;
        if(actors==null||generations==null)throw new IllegalArgumentException("Actor generation snapshots required");
        List<Long> ids=new ArrayList<Long>();for(Long actor:actors){if(actor==null||actor<0||ids.contains(actor))throw new IllegalArgumentException("Invalid or duplicate actor");ids.add(actor);}
        actorIds=Collections.unmodifiableList(ids);ownerGenerations=Collections.unmodifiableMap(new LinkedHashMap<Long,Long>(generations));
    }
    public static Intent create(String id,String owner,Map<Long,Long> generations,Collection<Long> actors,String kind,String lane,int priority,
                                CommandArbiter.Stamp observation,String sourceObservationId,String sourceRequestPath,String path,Commitment commitment){
        return new Intent(id,owner,generations,actors,kind,lane,priority,observation,sourceObservationId,sourceRequestPath,path,commitment);
    }
    public Map<String,Object> metadata(){
        Map<String,Object> m=new LinkedHashMap<String,Object>();m.put("intentId",intentId);m.put("owner",owner);m.put("ownerGenerations",ownerGenerations);
        m.put("actorIds",actorIds);m.put("kind",kind);m.put("lane",lane);m.put("priority",priority);m.put("sourceObservationId",sourceObservationId);
        m.put("sourceRequestPath",sourceRequestPath);m.put("commandPath",path);m.put("commitment",commitment.metadata());m.put("receiptIsExecution",false);
        m.put("costSourceObservationId",costSourceObservationId);m.put("costSourceRequestPath",costSourceRequestPath);
        m.put("costSourceStatus",!commitment.spending?"NOT_SPENDING":costSourceObservationId==null?"UNKNOWN":"LEGAL_NATIVE_QUOTE");return m;
    }
    private static boolean empty(String value){return value==null||value.isEmpty();}
    public static final class Commitment {
        public final boolean spending;
        public final Long credits;
        public final Map<Long,Integer> producerSlots;
        public final int militarySlots;
        public Commitment(boolean spending,Long credits,Map<Long,Integer> slots,int militarySlots){
            if(credits!=null&&credits<0||militarySlots<0||slots==null)throw new IllegalArgumentException("Invalid commitment");
            Map<Long,Integer> copy=new LinkedHashMap<Long,Integer>();for(Map.Entry<Long,Integer> e:slots.entrySet()){
                if(e.getKey()==null||e.getKey()<0||e.getValue()==null||e.getValue()<0)throw new IllegalArgumentException("Invalid producer slots");copy.put(e.getKey(),e.getValue());}
            if(!spending&&(credits==null||credits!=0||!copy.isEmpty()||militarySlots!=0))throw new IllegalArgumentException("Non-spending commitment must be empty");
            this.spending=spending;this.credits=credits;this.producerSlots=Collections.unmodifiableMap(copy);this.militarySlots=militarySlots;
        }
        public static Commitment none(){return new Commitment(false,0L,Collections.<Long,Integer>emptyMap(),0);}
        public static Commitment spending(Long credits,Map<Long,Integer> producerSlots,int militarySlots){return new Commitment(true,credits,producerSlots,militarySlots);}
        public Map<String,Object> metadata(){Map<String,Object> m=new LinkedHashMap<String,Object>();m.put("spending",spending);m.put("credits",credits);m.put("producerSlots",producerSlots);m.put("militarySlots",militarySlots);return m;}
    }
}
