package io.rwagent.client;

import java.util.*;

/** Shared execution admission, bounded game-time budget and resource commitment ledger.
 * Immediate callers traverse their fixed lane order: the first actual native attempt
 * claims its actors. Sorted batches decide all order before transport, never revoke receipts.
 */
public final class ExecutionScheduler {
    public interface Dispatcher {Map<String,Object> dispatch(Intent intent)throws Exception;}
    public static final class Result {
        public final Intent intent;
        public final Map<String,Object> receipt;
        public final String cancelReason;
        public final boolean nativeAttempted,accepted,commitmentApplied,transportUnknown;
        public final Exception failure;
        private Result(Intent intent,Map<String,Object> receipt,String reason,boolean attempted,boolean accepted,boolean committed,boolean unknown,Exception failure){
            this.intent=intent;this.receipt=receipt;cancelReason=reason;nativeAttempted=attempted;this.accepted=accepted;commitmentApplied=committed;transportUnknown=unknown;this.failure=failure;
        }
        public Map<String,Object> metadata(){Map<String,Object> m=new LinkedHashMap<String,Object>(intent.metadata());m.put("cancelReason",cancelReason);m.put("nativeAttempted",nativeAttempted);
            m.put("nativeAccepted",accepted);m.put("commitmentApplied",commitmentApplied);m.put("transportUnknown",transportUnknown);m.put("receipt",receipt);m.put("executionWitness",null);return m;}
    }
    private final CommandArbiter arbiter;
    private CommandArbiter.Stamp batchStamp;
    private String observationId;
    private Long credits;
    private Integer militarySlots;
    private final Map<Long,Integer> producerSlots=new LinkedHashMap<Long,Integer>();
    private final Set<Long> attemptedActors=new HashSet<Long>();
    private final LinkedHashSet<String> attemptedIntents=new LinkedHashSet<String>();
    // queued receipts can precede native command.k()/payment. New source clocks do not
    // settle them. Never evict a commitment merely to bound a diagnostic/replay cache.
    private final Map<String,Long> unsettledCredits=new LinkedHashMap<String,Long>();
    private boolean dispatching;
    public ExecutionScheduler(CommandArbiter arbiter){this(arbiter,1000,4);}
    public ExecutionScheduler(CommandArbiter arbiter,long intervalMs,int burst){
        if(arbiter==null)throw new IllegalArgumentException("Arbiter required");this.arbiter=arbiter;arbiter.configureElapsedBudget(intervalMs,burst);
    }
    public void beginObservation(CommandArbiter.Stamp stamp,String id,Long nativeCredits,Map<Long,Integer> slots,Integer nativeMilitarySlots){
        if(dispatching)throw new IllegalStateException("Cannot reset batch while dispatching");
        if(stamp==null||id==null||id.isEmpty()||slots==null||nativeCredits!=null&&nativeCredits<0||nativeMilitarySlots!=null&&nativeMilitarySlots<0)
            throw new IllegalArgumentException("Invalid batch evidence");
        CommandArbiter.Stamp current=arbiter.stamp();
        if(!current.samePlayer(stamp)||current.frame!=stamp.frame||current.gameTimeMs!=stamp.gameTimeMs)throw new IllegalStateException("Batch differs from legal state observation");
        for(Map.Entry<Long,Integer> e:slots.entrySet())if(e.getKey()==null||e.getKey()<0||e.getValue()==null||e.getValue()<0)throw new IllegalArgumentException("Invalid slot evidence");
        boolean newTime=batchStamp==null||!batchStamp.samePlayer(stamp)||stamp.gameTimeMs>batchStamp.gameTimeMs;
        Long availableCredits=nativeCredits==null?null:Math.max(0L,nativeCredits-unsettledCreditTotal());
        if(newTime){credits=availableCredits;militarySlots=nativeMilitarySlots;producerSlots.clear();producerSlots.putAll(slots);attemptedActors.clear();}
        else{
            // A repeated native time cannot refund local accepted commitments, even if frame/id changes.
            if(credits!=null&&availableCredits!=null)credits=Math.min(credits,availableCredits);
            if(militarySlots!=null&&nativeMilitarySlots!=null)militarySlots=Math.min(militarySlots,nativeMilitarySlots);
            for(Map.Entry<Long,Integer> e:slots.entrySet()){Integer old=producerSlots.get(e.getKey());if(old!=null)producerSlots.put(e.getKey(),Math.min(old,e.getValue()));}
        }
        batchStamp=stamp;observationId=id;
    }
    public Long effectiveCredits(){return credits;}
    public int unsettledCreditCount(){return unsettledCredits.size();}
    /** Saturates without wrapping or dropping the individual obligations. */
    public long unsettledCreditTotal(){
        long total=0;for(Long value:unsettledCredits.values()){
            if(value>Long.MAX_VALUE-total)return Long.MAX_VALUE;total+=value;
        }return total;
    }
    /** Trusted witness adapter only: a validated later native queue/tier/construction
     * effect must already be linked to this exact intent. Receipt, timer and wallet
     * change are not settlement evidence. This removes only the credit obligation;
     * the current batch receives no refund, token or slot. A later begin refreshes it.
     */
    public boolean confirmNativeEffect(String intentId,String observationId,String witnessKind){
        if(dispatching)throw new IllegalStateException("Cannot settle credits during native dispatch");
        if(intentId==null||intentId.trim().isEmpty()||observationId==null||observationId.trim().isEmpty()
                ||witnessKind==null||witnessKind.trim().isEmpty())throw new IllegalArgumentException("Native effect witness identity required");
        return unsettledCredits.remove(intentId)!=null;
    }
    public Integer availableMilitarySlots(){return militarySlots;}
    public Integer availableProducerSlots(long actor){return producerSlots.get(actor);}
    public int availableTokens(){return arbiter.availableTokens();}
    public String observationId(){return observationId;}
    public boolean actorAvailable(long actor){return !attemptedActors.contains(actor);}
    public boolean canUseActors(Collection<Long> actors){for(Long actor:actors)if(!actorAvailable(actor))return false;return true;}
    public void observeProducerSlots(Map<Long,Integer> slots){
        if(batchStamp==null||dispatching)throw new IllegalStateException("No mutable current batch");
        for(Map.Entry<Long,Integer> e:slots.entrySet()){
            if(e.getKey()==null||e.getKey()<0||e.getValue()==null||e.getValue()<0)throw new IllegalArgumentException("Invalid slot evidence");
            Integer old=producerSlots.get(e.getKey());producerSlots.put(e.getKey(),old==null?e.getValue():Math.min(old,e.getValue()));
        }
    }
    private String validate(Intent intent){
        if(batchStamp==null)return "NO_EXECUTION_OBSERVATION";
        CommandArbiter.Stamp current=arbiter.stamp();
        if(!batchStamp.samePlayer(current)||batchStamp.frame!=current.frame||batchStamp.gameTimeMs!=current.gameTimeMs)
            return "STALE_EXECUTION_BATCH";
        if(!batchStamp.samePlayer(intent.observation)||batchStamp.frame!=intent.observation.frame||batchStamp.gameTimeMs!=intent.observation.gameTimeMs)
            return "STALE_OR_FOREIGN_OBSERVATION";
        String denied=arbiter.validateGenerations(intent.actorIds,intent.ownerGenerations);if(denied!=null)return denied;
        denied=arbiter.validate(intent.observation,intent.owner,intent.actorIds);if(denied!=null)return denied;
        if(attemptedIntents.contains(intent.intentId)||unsettledCredits.containsKey(intent.intentId))return "INTENT_ALREADY_ATTEMPTED";
        if(!canUseActors(intent.actorIds))return "ACTOR_CONFLICT_IN_OBSERVATION";
        Intent.Commitment c=intent.commitment;
        if(c.spending){if(c.credits==null)return "UNKNOWN_PRICE_COMMITMENT";if(credits==null)return "UNKNOWN_EFFECTIVE_CREDITS";if(c.credits>credits)return "INSUFFICIENT_EFFECTIVE_CREDITS";}
        for(Map.Entry<Long,Integer> e:c.producerSlots.entrySet()){
            if(!intent.actorIds.contains(e.getKey()))return "SLOT_PRODUCER_NOT_ACTOR";
            Integer remaining=producerSlots.get(e.getKey());if(remaining==null)return "UNKNOWN_PRODUCER_SLOTS";if(e.getValue()>remaining)return "PRODUCER_SLOT_BUDGET";
        }
        if(c.militarySlots>0){if(militarySlots==null)return "UNKNOWN_MILITARY_SLOTS";if(c.militarySlots>militarySlots)return "MILITARY_SLOT_BUDGET";}
        return null;
    }
    public Result dispatch(Intent intent,Dispatcher dispatcher){
        if(intent==null||dispatcher==null)throw new IllegalArgumentException("Intent and native dispatcher required");
        if(dispatching)throw new IllegalStateException("Recursive native dispatch forbidden");
        String denied=validate(intent);if(denied==null)denied=arbiter.consumeAttempt(intent.observation);
        if(denied!=null)return new Result(intent,null,denied,false,false,false,false,null);
        attemptedActors.addAll(intent.actorIds);attemptedIntents.add(intent.intentId);if(attemptedIntents.size()>512)attemptedIntents.remove(attemptedIntents.iterator().next());
        Map<String,Object> receipt=null;Exception failure=null;dispatching=true;
        try{receipt=dispatcher.dispatch(intent);}catch(Exception exception){failure=exception;}finally{dispatching=false;}
        boolean accepted=receipt!=null&&("queued".equals(receipt.get("status"))||Boolean.TRUE.equals(receipt.get("accepted")));
        boolean rejected=receipt!=null&&("rejected".equals(receipt.get("status"))||Boolean.FALSE.equals(receipt.get("accepted")));
        // Contradictory transport metadata is unknown, never a reason to refund resources.
        if(accepted&&rejected){accepted=false;rejected=false;}
        boolean unknown=!accepted&&!rejected;boolean committed=accepted||unknown;
        if(committed){Intent.Commitment c=intent.commitment;if(c.spending){credits-=c.credits;unsettledCredits.put(intent.intentId,c.credits);}
            for(Map.Entry<Long,Integer> e:c.producerSlots.entrySet())producerSlots.put(e.getKey(),producerSlots.get(e.getKey())-e.getValue());
            if(c.militarySlots>0)militarySlots-=c.militarySlots;}
        return new Result(intent,receipt,rejected?"NATIVE_REJECTED":unknown?"NATIVE_OUTCOME_UNKNOWN":null,true,accepted,committed,unknown,failure);
    }
    public List<Result> dispatchBatch(Collection<Intent> intents,Dispatcher dispatcher){
        List<Intent> sorted=new ArrayList<Intent>(intents);Collections.sort(sorted,new Comparator<Intent>(){public int compare(Intent a,Intent b){
            int c=Integer.compare(b.priority,a.priority);if(c!=0)return c;c=a.lane.compareTo(b.lane);return c!=0?c:a.intentId.compareTo(b.intentId);}});
        List<Result> results=new ArrayList<Result>();for(Intent intent:sorted)results.add(dispatch(intent,dispatcher));return Collections.unmodifiableList(results);
    }
}
