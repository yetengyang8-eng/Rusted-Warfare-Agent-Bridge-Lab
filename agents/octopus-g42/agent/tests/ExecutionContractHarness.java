package io.rwagent.client;

import java.util.*;

/** Engine-free adversarial checks for the shared ownership/intent/evidence boundary. */
public final class ExecutionContractHarness {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static CommandArbiter.Stamp stamp(String player,long frame,long time){return new CommandArbiter.Stamp("session",player,frame,time);}
    private static List<Long> ids(Long... ids){return Arrays.asList(ids);}
    public static void main(String[] args){
        CommandArbiter a=new CommandArbiter(),b=new CommandArbiter();
        CommandArbiter.Stamp a0=stamp("team:0",100,2000),b0=stamp("team:1",100,2000);
        a.observe(a0,ids(1L,2L));b.observe(b0,ids(3L,4L));
        check(a.claim("recon:1",1),"claim own actor");
        check(!a.claim("learned:2",1),"second policy cannot steal a lease");
        check(!a.claim("recon:1",3),"foreign actor cannot be claimed");
        check("STALE_OR_FOREIGN_OBSERVATION".equals(b.admit(a0,"recon:1",ids(1L))),"cross-player intent rejected");
        check("ACTOR_OWNED_BY_OTHER_TASK".equals(a.admit(a0,CommandArbiter.DEFAULT_OWNER,ids(2L,1L))),"whole group rejected before transport");
        check(a.ready(2000),"ownership rejection did not consume command budget");
        check("TASK_HAS_NO_OWNERSHIP".equals(a.admit(a0,"learned:2",ids(2L))),"task must acquire first");
        check(a.admit(a0,"recon:1",ids(1L))==null,"owner can issue");
        check("COMMAND_GAME_TIME_BUDGET".equals(a.admit(a0,CommandArbiter.DEFAULT_OWNER,ids(2L))),"native rejection cannot refund used slot");
        check(b.admit(b0,CommandArbiter.DEFAULT_OWNER,ids(3L))==null,"independent player has independent budget");
        CommandArbiter.Stamp a1=stamp("team:0",101,2500);a.observe(a1,ids(1L,2L));
        check("STALE_OR_FOREIGN_OBSERVATION".equals(a.admit(a0,"recon:1",ids(1L))),"stale policy result rejected");
        check(!a.ready(2500),"wall-clock passage cannot buy game-time commands");
        CommandArbiter.Stamp a2=stamp("team:0",102,3000);a.observe(a2,ids(1L,2L));
        check(!a.release("other-task"),"unheld lease cannot emit a release");check(a.reserved(1),"release is owner-scoped");
        check(a.release("recon:1"),"real lease released once");
        check(!a.release("recon:1"),"idempotent second release reports no transition");
        check(a.admit(a2,CommandArbiter.DEFAULT_OWNER,ids(1L,2L))==null,"explicit release returns actors to main force");
        a.observe(stamp("team:0",103,4000),ids(2L));
        check("ACTOR_NOT_OWN".equals(a.admit(a.stamp(),CommandArbiter.DEFAULT_OWNER,ids(1L))),"lost actor rejected");
        boolean changed=false;try{a.observe(stamp("team:1",104,5000),ids(2L));}catch(IllegalStateException expected){changed=true;}
        check(changed,"silent player switch forbidden even within same session");
        boolean backwards=false;try{a.observe(stamp("team:0",102,3000),ids(2L));}catch(IllegalStateException expected){backwards=true;}
        check(backwards,"observation clock cannot roll back");
        a.observeProductionActors(a.stamp(),ids(5L));
        check(a.admit(a.stamp(),CommandArbiter.DEFAULT_OWNER,ids(5L))==null,"fresh legal production menu can identify a newly completed own factory");
        a.observe(stamp("team:0",104,5000),ids(2L));
        check("ACTOR_NOT_OWN".equals(a.admit(a.stamp(),CommandArbiter.DEFAULT_OWNER,ids(5L))),"menu evidence expires at next state observation");
        boolean foreignMenu=false;try{a.observeProductionActors(b0,ids(3L));}catch(IllegalStateException expected){foreignMenu=true;}
        check(foreignMenu,"foreign production evidence rejected");

        CommandArbiter.MoveIntent intent=new CommandArbiter.MoveIntent(a0,"recon:1",1,230,110);
        MoveExecution move=new MoveExecution(intent,"request-1",101,100,100,false);
        check(move.observe(stamp("team:0",101,2500),1,230,110,null,null,null)==MoveExecution.Witness.NONE,"queued receipt is not execution");
        check(move.observe(stamp("team:1",102,3000),1,230,110,null,null,null)==MoveExecution.Witness.NONE,"foreign observation cannot confirm move");
        check(move.observe(a2,2,230,110,null,null,null)==MoveExecution.Witness.NONE,"other unit cannot confirm move");
        check(move.observe(a2,1,150,104,"move",230.0,110.0)==MoveExecution.Witness.ACTIVE_ORDER,"active matching native order observed");
        check(move.observe(a2,1,230,110,null,null,null)==MoveExecution.Witness.ARRIVED_AFTER_ACCEPTANCE,"fast completed move survives coarse sampling");
        check(move.observe(a2,1,230,110,"attackMove",230.0,110.0)==MoveExecution.Witness.NONE,"old attack order at destination is not move evidence");
        check(move.observe(a2,1,100,100,null,null,null)==MoveExecution.Witness.NONE,"accepted but dropped command has no witness");
        CommandArbiter.MoveIntent holdIntent=new CommandArbiter.MoveIntent(a0,"recon:1",1,100,100);
        MoveExecution hold=new MoveExecution(holdIntent,"hold",101,100,100,true);
        check(hold.observe(a2,1,100,100,"attackMove",1500.0,100.0)==MoveExecution.Witness.NONE,"old order must end before takeover");
        check(hold.observe(a2,1,100,100,null,null,null)==MoveExecution.Witness.ARRIVED_AFTER_ACCEPTANCE,"stationary takeover can complete");
        MoveExecution zeroRoute=new MoveExecution(holdIntent,"route",101,100,100,false);
        check(zeroRoute.observe(a2,1,100,100,null,null,null)==MoveExecution.Witness.NONE,"zero motion cannot invent route progress");
        MoveExecution shortMove=new MoveExecution(new CommandArbiter.MoveIntent(a0,"recon:1",1,130,110),"short",101,119.5,110,false);
        check(shortMove.observe(a2,1,130,110,null,null,null)==MoveExecution.Witness.ARRIVED_AFTER_ACCEPTANCE,"10.5-unit move can complete");
        check(shortMove.observe(a2,1,120,110,null,null,null)==MoveExecution.Witness.NONE,"sub-unit drift near a short waypoint is insufficient");
        System.out.println("ExecutionContractHarness PASS checks="+checks+" evidence=E2_NO_ENGINE");
    }
}
