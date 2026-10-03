package io.rwagent.client;

import java.util.*;

/** G3 synthetic native-callback contract tests; no game or desktop acceptance claim. */
public final class ExecutionSchedulerHarness {
    private static int checks,sequence;
    private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static List<Long> ids(Long... values){return Arrays.asList(values);}
    private static Map<String,Object> receipt(String status){Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("status",status);return result;}
    private static Map<Long,Integer> slots(long actor,int count){Map<Long,Integer> result=new LinkedHashMap<Long,Integer>();result.put(actor,count);return result;}
    private static final ExecutionScheduler.Dispatcher ACCEPT=new ExecutionScheduler.Dispatcher(){public Map<String,Object> dispatch(Intent i){return receipt("queued");}};
    private static final class Context {
        final CommandArbiter arbiter=new CommandArbiter();final ExecutionScheduler scheduler=new ExecutionScheduler(arbiter);String observation;
        void begin(long frame,long time,long credits,Map<Long,Integer> slots,Integer military){
            CommandArbiter.Stamp stamp=new CommandArbiter.Stamp("session","team:0",frame,time);arbiter.observe(stamp,ids(1L,2L,3L,4L,5L,6L,7L,8L));
            observation="run:o:"+(++sequence);scheduler.beginObservation(stamp,observation,credits,slots,military);
        }
        Intent intent(String owner,Collection<Long> actors,String lane,int priority,Intent.Commitment c){return new Intent("run:i:"+(++sequence),owner,
                arbiter.snapshotGenerations(owner,actors),actors,"COMMAND",lane,priority,arbiter.stamp(),observation,"/state","/command/move?unitId="+actors.iterator().next(),c);}
        Intent move(long actor){return intent(CommandArbiter.DEFAULT_OWNER,ids(actor),"MAIN",0,Intent.Commitment.none());}
    }
    private static void denied(Context c,Intent i,String reason){int before=c.scheduler.availableTokens();ExecutionScheduler.Result r=c.scheduler.dispatch(i,ACCEPT);
        check(reason.equals(r.cancelReason),reason+" got "+r.cancelReason);check(!r.nativeAttempted,"local denial has no native call");check(before==c.scheduler.availableTokens(),"local denial preserves token");}
    public static void main(String[] args){
        Context c=new Context();c.begin(1,0,1000,slots(1,3),3);
        check(c.scheduler.availableTokens()==1,"initial one token");check(c.arbiter.claim("task:a",1),"claim");
        long g=c.arbiter.ownerGeneration(1);check(g==1,"claim increments generation");check(c.arbiter.claim("task:a",1)&&c.arbiter.ownerGeneration(1)==g,"same-owner re-claim is idempotent");
        Intent old=c.intent("task:a",ids(1L),"CAPABILITY",1,Intent.Commitment.none());
        check(c.arbiter.transfer("task:a","task:b",1),"transfer");check(c.arbiter.ownerGeneration(1)==g+1,"transfer generation increment");
        denied(c,old,"STALE_OWNER_GENERATION");check(c.arbiter.release("task:b"),"release");check(c.arbiter.ownerGeneration(1)==g+2,"release generation increment");
        check(c.arbiter.claim("task:a",1),"ABA re-claim");denied(c,old,"STALE_OWNER_GENERATION");
        c.arbiter.clear();check(c.arbiter.ownerGeneration(1)==g+4,"clear advances active ownership generation");check(c.arbiter.snapshotGenerations(CommandArbiter.DEFAULT_OWNER,ids(1L)).get(1L)==g+4,"default snapshot tracks retained generation");
        boolean foreign=false;try{c.arbiter.snapshotGenerations(CommandArbiter.DEFAULT_OWNER,ids(99L));}catch(IllegalArgumentException expected){foreign=true;}check(foreign,"snapshot rejects foreign actor");
        Intent first=c.move(1);ExecutionScheduler.Result accepted=c.scheduler.dispatch(first,ACCEPT);
        check(accepted.accepted&&accepted.nativeAttempted&&accepted.receipt!=null,"real synchronous queued receipt");check(accepted.metadata().get("executionWitness")==null,"receipt does not fabricate execution witness");
        denied(c,first,"INTENT_ALREADY_ATTEMPTED");denied(c,c.move(1),"ACTOR_CONFLICT_IN_OBSERVATION");check(!c.scheduler.actorAvailable(1)&&c.scheduler.actorAvailable(2),"upper callers see attempted actor");
        denied(c,c.move(2),"COMMAND_GAME_TIME_BUDGET");
        c.begin(2,2500,1000,slots(1,3),3);check(c.scheduler.availableTokens()==2,"2500ms accrues two consumed-budget tokens");
        check(c.scheduler.dispatch(c.move(1),ACCEPT).accepted&&c.scheduler.dispatch(c.move(2),ACCEPT).accepted,"independent actors advance same observation");
        c.begin(3,3500,1000,slots(1,3),3);check(c.scheduler.availableTokens()==1,"3500ms adds one with remainder");
        c.begin(4,100000,1000,slots(1,3),3);check(c.scheduler.availableTokens()==4,"long gap burst bounded four");
        c.begin(5,100000,1000,slots(1,3),3);check(c.scheduler.availableTokens()==4,"same native time never refills token");

        Context money=new Context();money.begin(1,0,1000,slots(1,1),1);money.begin(2,3500,1000,slots(1,1),1);
        Intent buy=money.intent(CommandArbiter.DEFAULT_OWNER,ids(1L),"PRODUCTION",1,Intent.Commitment.spending(700L,slots(1,1),1));
        check(money.scheduler.dispatch(buy,ACCEPT).accepted,"first purchase accepted");check(money.scheduler.effectiveCredits()==300,"accepted purchase immediately debits credits");
        check(money.scheduler.availableProducerSlots(1)==0&&money.scheduler.availableMilitarySlots()==0,"accepted purchase debits producer and military slot");
        denied(money,money.intent(CommandArbiter.DEFAULT_OWNER,ids(2L),"PRODUCTION",1,Intent.Commitment.spending(400L,Collections.<Long,Integer>emptyMap(),0)),"INSUFFICIENT_EFFECTIVE_CREDITS");
        String batch=money.observation;money.scheduler.beginObservation(money.arbiter.stamp(),batch,1000L,slots(1,1),1);check(money.scheduler.effectiveCredits()==300&&money.scheduler.availableMilitarySlots()==0,"same stamp does not refund accepted credits/slots");
        money.begin(3,3500,1000,slots(1,1),1);check(money.scheduler.effectiveCredits()==300&&!money.scheduler.actorAvailable(1),"new frame same time preserves ledger/conflict");
        denied(money,money.intent(CommandArbiter.DEFAULT_OWNER,ids(2L),"PRODUCTION",1,Intent.Commitment.spending(null,Collections.<Long,Integer>emptyMap(),0)),"UNKNOWN_PRICE_COMMITMENT");
        money.scheduler.observeProducerSlots(slots(2,0));denied(money,money.intent(CommandArbiter.DEFAULT_OWNER,ids(2L),"PRODUCTION",1,Intent.Commitment.spending(0L,slots(2,1),0)),"PRODUCER_SLOT_BUDGET");
        denied(money,money.intent(CommandArbiter.DEFAULT_OWNER,ids(2L),"PRODUCTION",1,Intent.Commitment.spending(0L,Collections.<Long,Integer>emptyMap(),1)),"MILITARY_SLOT_BUDGET");
        money.scheduler.observeProducerSlots(slots(1,5));check(money.scheduler.availableProducerSlots(1)==0,"fresh menu cannot refund spent slot");

        Context nativeOut=new Context();nativeOut.begin(1,0,1000,slots(1,2),2);nativeOut.begin(2,3500,1000,slots(1,2),2);
        ExecutionScheduler.Result rejected=nativeOut.scheduler.dispatch(nativeOut.intent(CommandArbiter.DEFAULT_OWNER,ids(1L),"PRODUCTION",1,Intent.Commitment.spending(200L,slots(1,1),1)),new ExecutionScheduler.Dispatcher(){public Map<String,Object> dispatch(Intent i){return receipt("rejected");}});
        check(rejected.nativeAttempted&&!rejected.accepted&&!rejected.commitmentApplied,"explicit native rejection");check(nativeOut.scheduler.availableTokens()==3&&nativeOut.scheduler.effectiveCredits()==1000,"reject costs native attempt token but no credits");
        denied(nativeOut,nativeOut.move(1),"ACTOR_CONFLICT_IN_OBSERVATION");
        ExecutionScheduler.Result lost=nativeOut.scheduler.dispatch(nativeOut.intent(CommandArbiter.DEFAULT_OWNER,ids(2L),"INVESTMENT",1,Intent.Commitment.spending(300L,Collections.<Long,Integer>emptyMap(),1)),new ExecutionScheduler.Dispatcher(){public Map<String,Object> dispatch(Intent i)throws Exception{throw new Exception("lost after send");}});
        check(lost.transportUnknown&&lost.failure!=null&&lost.commitmentApplied&&!lost.accepted,"transport exception retained without forged receipt");
        check(nativeOut.scheduler.effectiveCredits()==700&&nativeOut.scheduler.availableMilitarySlots()==1&&nativeOut.scheduler.availableTokens()==2,"unknown outcome conservatively debits resources and attempt");
        ExecutionScheduler.Result missing=nativeOut.scheduler.dispatch(nativeOut.intent(CommandArbiter.DEFAULT_OWNER,ids(3L),"INVESTMENT",1,Intent.Commitment.spending(100L,Collections.<Long,Integer>emptyMap(),0)),new ExecutionScheduler.Dispatcher(){public Map<String,Object> dispatch(Intent i){return null;}});
        check(missing.transportUnknown&&nativeOut.scheduler.effectiveCredits()==600,"null transport outcome not guessed rejected");

        Context batchContext=new Context();batchContext.begin(1,0,1000,Collections.<Long,Integer>emptyMap(),0);batchContext.begin(2,3500,1000,Collections.<Long,Integer>emptyMap(),0);
        Intent low=batchContext.intent(CommandArbiter.DEFAULT_OWNER,ids(1L),"Z",1,Intent.Commitment.none()),high=batchContext.intent(CommandArbiter.DEFAULT_OWNER,ids(1L),"A",9,Intent.Commitment.none()),other=batchContext.move(2);
        final List<String> nativeIds=new ArrayList<String>();List<ExecutionScheduler.Result> results=batchContext.scheduler.dispatchBatch(Arrays.asList(low,other,high),new ExecutionScheduler.Dispatcher(){public Map<String,Object> dispatch(Intent i){nativeIds.add(i.intentId);return receipt("queued");}});
        check(nativeIds.size()==2&&nativeIds.get(0).equals(high.intentId),"sorted batch chooses priority before irreversible dispatch");check(results.get(1).cancelReason.equals("ACTOR_CONFLICT_IN_OBSERVATION"),"batch loser explicitly cancelled");
        Intent invalidOwner=batchContext.intent("task:unclaimed",ids(4L),"A",1,Intent.Commitment.none());denied(batchContext,invalidOwner,"TASK_HAS_NO_OWNERSHIP");
        check(batchContext.scheduler.canUseActors(ids(3L,4L))&&!batchContext.scheduler.canUseActors(ids(1L,4L)),"upper caller group availability");
        batchContext.arbiter.observe(new CommandArbiter.Stamp("session","team:0",3,4500),ids(1L,2L,3L,4L));
        denied(batchContext,batchContext.move(3),"STALE_EXECUTION_BATCH");
        Context unknown=new Context();unknown.begin(1,0,1000,Collections.<Long,Integer>emptyMap(),0);unknown.begin(2,3500,1000,Collections.<Long,Integer>emptyMap(),0);
        denied(unknown,unknown.intent(CommandArbiter.DEFAULT_OWNER,ids(1L),"PRODUCTION",1,Intent.Commitment.spending(1L,slots(1,1),0)),"UNKNOWN_PRODUCER_SLOTS");
        unknown.scheduler.beginObservation(unknown.arbiter.stamp(),unknown.observation,900L,Collections.<Long,Integer>emptyMap(),0);check(unknown.scheduler.effectiveCredits()==900,"same time can tighten credits");
        ExecutionScheduler.Result contradiction=unknown.scheduler.dispatch(unknown.intent(CommandArbiter.DEFAULT_OWNER,ids(2L),"INVESTMENT",1,Intent.Commitment.spending(100L,Collections.<Long,Integer>emptyMap(),0)),new ExecutionScheduler.Dispatcher(){public Map<String,Object> dispatch(Intent i){Map<String,Object> value=receipt("queued");value.put("accepted",false);return value;}});
        check(contradiction.transportUnknown&&contradiction.commitmentApplied&&unknown.scheduler.effectiveCredits()==800,"contradictory receipt conservatively commits");
        check(unknown.scheduler.confirmNativeEffect(contradiction.intent.intentId,"run:o:native-effect","QUEUE_NONEMPTY_OBSERVED"),"later exact native effect settles contradictory transport obligation");
        check(unknown.scheduler.effectiveCredits()==800,"settlement never replenishes current batch");
        unknown.begin(3,4500,800,Collections.<Long,Integer>emptyMap(),0);check(unknown.scheduler.effectiveCredits()==800&&unknown.scheduler.actorAvailable(2),"advanced observed time refreshes explicitly settled batch");
        Context factories=new Context();factories.begin(1,0,500,slots(1,1),2);factories.begin(2,3500,500,slots(1,1),2);factories.scheduler.observeProducerSlots(slots(2,1));
        Intent p1=factories.intent(CommandArbiter.DEFAULT_OWNER,ids(1L),"PRODUCTION",1,Intent.Commitment.spending(200L,slots(1,1),1));
        Intent p2=factories.intent(CommandArbiter.DEFAULT_OWNER,ids(2L),"PRODUCTION",1,Intent.Commitment.spending(200L,slots(2,1),1));
        List<ExecutionScheduler.Result> products=factories.scheduler.dispatchBatch(Arrays.asList(p1,p2),ACCEPT);
        check(products.get(0).accepted&&products.get(1).accepted,"two factories receive actual native receipts in same batch");
        check(factories.scheduler.effectiveCredits()==100&&factories.scheduler.availableMilitarySlots()==0&&factories.scheduler.availableProducerSlots(1)==0&&factories.scheduler.availableProducerSlots(2)==0,"multi-factory ledger has no double spend");
        denied(factories,factories.intent(CommandArbiter.DEFAULT_OWNER,ids(3L),"PRODUCTION",1,Intent.Commitment.spending(200L,Collections.<Long,Integer>emptyMap(),0)),"INSUFFICIENT_EFFECTIVE_CREDITS");
        Context missingBudget=new Context();missingBudget.arbiter.observe(new CommandArbiter.Stamp("session","team:0",1,0),ids(1L));missingBudget.observation="run:o:unknown";
        missingBudget.scheduler.beginObservation(missingBudget.arbiter.stamp(),missingBudget.observation,null,Collections.<Long,Integer>emptyMap(),null);
        denied(missingBudget,missingBudget.intent(CommandArbiter.DEFAULT_OWNER,ids(1L),"PRODUCTION",1,Intent.Commitment.spending(0L,Collections.<Long,Integer>emptyMap(),0)),"UNKNOWN_EFFECTIVE_CREDITS");
        denied(missingBudget,missingBudget.intent(CommandArbiter.DEFAULT_OWNER,ids(1L),"PRODUCTION",1,Intent.Commitment.spending(0L,Collections.<Long,Integer>emptyMap(),1)),"UNKNOWN_EFFECTIVE_CREDITS");
        delayedNativePayment();unknownAndRejectedCommitments();commitmentIdentityAndBounds();
        System.out.println("ExecutionSchedulerHarness PASS checks="+checks+" evidence=E2_SYNTHETIC_NATIVE_CALLBACK receiptIsExecution=false");
    }
    private static Intent cost(Context c,long actor,long amount){return c.intent(CommandArbiter.DEFAULT_OWNER,ids(actor),"PRODUCTION",1,
            Intent.Commitment.spending(amount,Collections.<Long,Integer>emptyMap(),0));}
    private static void delayedNativePayment(){
        Context c=new Context();c.begin(1,0,1600,slots(1,1),3);c.begin(2,2500,1600,slots(1,1),3);
        Intent first=cost(c,1,800);check(c.scheduler.dispatch(first,ACCEPT).accepted,"first factory accepted before native k/payment");
        check(c.scheduler.unsettledCreditCount()==1&&c.scheduler.unsettledCreditTotal()==800,"queued receipt retains exact intent credit obligation");
        c.begin(3,3500,1600,slots(2,1),3);
        check(c.scheduler.effectiveCredits()==800,"new native clock with unchanged cash cannot refund first paid commitment");
        Intent second=cost(c,2,800);check(c.scheduler.dispatch(second,ACCEPT).accepted,"second independent factory uses remaining1600 budget");
        c.begin(4,7000,1600,slots(3,1),3);
        check(c.scheduler.effectiveCredits()==0&&c.scheduler.unsettledCreditTotal()==1600,"both accepted costs persist across delayed native k");
        denied(c,cost(c,3,800),"INSUFFICIENT_EFFECTIVE_CREDITS");
        int tokens=c.scheduler.availableTokens(),military=c.scheduler.availableMilitarySlots(),producer=c.scheduler.availableProducerSlots(3);
        check(c.scheduler.confirmNativeEffect(first.intentId,"run:o:queue-first","QUEUE_NONEMPTY_OBSERVED"),"exact first native queue settles only first credits");
        check(c.scheduler.unsettledCreditCount()==1&&c.scheduler.unsettledCreditTotal()==800,"other factory still awaiting native effect");
        check(c.scheduler.effectiveCredits()==0&&c.scheduler.availableTokens()==tokens&&c.scheduler.availableMilitarySlots()==military
                &&c.scheduler.availableProducerSlots(3)==producer,"native effect does not refund batch money, tokens or slots");
        c.scheduler.beginObservation(c.arbiter.stamp(),"run:o:same-clock-after-effect",1600L,slots(3,1),3);
        check(c.scheduler.effectiveCredits()==0,"even confirmed witness does not recharge same-time batch");
        // After first k, wallet is800 and second command has not applied yet.
        c.begin(5,8000,800,slots(3,1),3);check(c.scheduler.effectiveCredits()==0,"native debit plus other outstanding cost remain conservative");
        check(c.scheduler.confirmNativeEffect(second.intentId,"run:o:queue-second","QUEUE_NONEMPTY_OBSERVED"),"second queue effect settles second exact intent");
        check(c.scheduler.unsettledCreditTotal()==0&&c.scheduler.effectiveCredits()==0,"settling all effects still does not refund current batch");
        // Both k paid1600; a later observed800 of native income is now actually free.
        c.begin(6,9000,800,slots(3,1),3);check(c.scheduler.effectiveCredits()==800,"next legal observation makes truly available native income spendable");
        check(c.scheduler.dispatch(cost(c,3,800),ACCEPT).accepted,"new funds can fund third actor after explicit settlement");
        check(!c.scheduler.confirmNativeEffect(first.intentId,"run:o:duplicate-effect","QUEUE_NONEMPTY_OBSERVED"),"duplicate native confirmation is idempotent");
        check(!c.scheduler.confirmNativeEffect("run:i:never-attempted","run:o:other","CONSTRUCTION_OBSERVED"),"unlinked witness cannot remove any outstanding obligation");
        boolean invalid=false;try{c.scheduler.confirmNativeEffect(second.intentId,"","QUEUE_NONEMPTY_OBSERVED");}catch(IllegalArgumentException expected){invalid=true;}
        check(invalid,"settlement requires native observation identity");
    }
    private static void unknownAndRejectedCommitments(){
        Context c=new Context();c.begin(1,0,1600,Collections.<Long,Integer>emptyMap(),0);c.begin(2,3500,1600,Collections.<Long,Integer>emptyMap(),0);
        Intent lost=cost(c,1,800);ExecutionScheduler.Result unknown=c.scheduler.dispatch(lost,new ExecutionScheduler.Dispatcher(){public Map<String,Object> dispatch(Intent i)throws Exception{throw new Exception("sent but native effect unavailable");}});
        check(unknown.transportUnknown&&c.scheduler.unsettledCreditTotal()==800,"unknown transport retains unsettled credits");
        c.begin(3,7000,1600,Collections.<Long,Integer>emptyMap(),0);check(c.scheduler.effectiveCredits()==800,"unknown receipt never refunds at next source clock");
        Intent refused=cost(c,2,800);ExecutionScheduler.Result rejected=c.scheduler.dispatch(refused,new ExecutionScheduler.Dispatcher(){public Map<String,Object> dispatch(Intent i){return receipt("rejected");}});
        check(!rejected.commitmentApplied&&c.scheduler.unsettledCreditCount()==1&&c.scheduler.effectiveCredits()==800,"explicit native rejection adds no unsettled obligation");
        check(!c.scheduler.confirmNativeEffect(refused.intentId,"run:o:refused","QUEUE_NONEMPTY_OBSERVED"),"rejected intent has no credit record to settle");
        c.begin(4,1000000,1600,Collections.<Long,Integer>emptyMap(),0);check(c.scheduler.unsettledCreditCount()==1&&c.scheduler.effectiveCredits()==800,"long gap and elapsed burst cannot erase unknown payment");
        c.begin(5,1001000,800,Collections.<Long,Integer>emptyMap(),0);check(c.scheduler.unsettledCreditTotal()==800&&c.scheduler.effectiveCredits()==0,"wallet decrease alone does not identify native payment");
        check(c.scheduler.confirmNativeEffect(lost.intentId,"run:o:actual-construction","CONSTRUCTION_OBSERVED"),"later actual construction may settle unknown transport");
        check(c.scheduler.effectiveCredits()==0,"unknown transport witness does not retroactively refund batch");
        c.begin(6,1002000,800,Collections.<Long,Integer>emptyMap(),0);check(c.scheduler.effectiveCredits()==800,"explicit effect allows next observed native balance");
    }
    private static void commitmentIdentityAndBounds(){
        Context c=new Context();c.begin(1,0,Long.MAX_VALUE,Collections.<Long,Integer>emptyMap(),0);Intent first=null;
        for(int i=1;i<=600;i++){
            c.begin(i+1,i*1000L,Long.MAX_VALUE,Collections.<Long,Integer>emptyMap(),0);Intent purchase=cost(c,1,1);
            if(first==null)first=purchase;if(!c.scheduler.dispatch(purchase,ACCEPT).accepted)throw new AssertionError("commitment sample"+i);
        }
        check(c.scheduler.unsettledCreditCount()==600&&c.scheduler.unsettledCreditTotal()==600,"unsettled credits cannot be discarded at512 replay-cache capacity");
        c.begin(602,601000,Long.MAX_VALUE,Collections.<Long,Integer>emptyMap(),0);
        Intent repeated=new Intent(first.intentId,CommandArbiter.DEFAULT_OWNER,c.arbiter.snapshotGenerations(CommandArbiter.DEFAULT_OWNER,ids(1L)),ids(1L),
                "COMMAND","PRODUCTION",1,c.arbiter.stamp(),c.observation,"/state","/command/move?unitId=1",Intent.Commitment.spending(1L,Collections.<Long,Integer>emptyMap(),0));
        denied(c,repeated,"INTENT_ALREADY_ATTEMPTED");check(c.scheduler.unsettledCreditTotal()==600,"attempt-ID churn cannot overwrite prior unsettled commitment");
        Context large=new Context();large.begin(1,0,Long.MAX_VALUE,Collections.<Long,Integer>emptyMap(),0);
        Intent maximum=cost(large,1,Long.MAX_VALUE);check(large.scheduler.dispatch(maximum,ACCEPT).accepted,"full64-bit native balance can be conservatively committed");
        large.begin(2,3500,100,Collections.<Long,Integer>emptyMap(),0);
        check(large.scheduler.unsettledCreditTotal()==Long.MAX_VALUE&&large.scheduler.effectiveCredits()==0,"large outstanding amount cannot wrap into spendable funds");
        denied(large,cost(large,2,1),"INSUFFICIENT_EFFECTIVE_CREDITS");
        check(large.scheduler.confirmNativeEffect(maximum.intentId,"run:o:native-max","FACTORY_TIER_INCREASE_OBSERVED"),"large exact intent can be settled without overflow");
        large.begin(3,4500,100,Collections.<Long,Integer>emptyMap(),0);check(large.scheduler.effectiveCredits()==100,"post-settlement normal wallet remains precise");
    }
}
