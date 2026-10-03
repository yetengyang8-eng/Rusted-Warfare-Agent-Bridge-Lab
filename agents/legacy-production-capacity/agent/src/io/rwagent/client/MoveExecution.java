package io.rwagent.client;

/** Evidence for one accepted move, evaluated at EVERY own-state sample.
 * Arrival after acceptance is distinct from seeing the active native order.
 * Neither is evidence of fog refresh or of exclusive causal attribution.
 */
public final class MoveExecution {
    public enum Witness { NONE, ACTIVE_ORDER, ARRIVED_AFTER_ACCEPTANCE }
    public final CommandArbiter.MoveIntent intent;
    public final String requestId;
    public final long acceptedFrame;
    public final double startX,startY;
    public final boolean takeover;
    public MoveExecution(CommandArbiter.MoveIntent intent,String requestId,long acceptedFrame,
                         double startX,double startY,boolean takeover){
        if(requestId==null||acceptedFrame<intent.observation.frame)throw new IllegalArgumentException("Invalid move receipt");
        this.intent=intent;this.requestId=requestId;this.acceptedFrame=acceptedFrame;
        this.startX=startX;this.startY=startY;this.takeover=takeover;
    }
    public Witness observe(CommandArbiter.Stamp stamp,long unitId,double x,double y,
                           String orderType,Double orderX,Double orderY){
        if(!intent.observation.samePlayer(stamp)||unitId!=intent.unitId||stamp.frame<=acceptedFrame
                ||stamp.gameTimeMs<intent.observation.gameTimeMs)return Witness.NONE;
        if("move".equals(orderType)&&orderX!=null&&orderY!=null
                &&Math.hypot(orderX-intent.x,orderY-intent.y)<1)return Witness.ACTIVE_ORDER;
        // A foreign order must never be mistaken for our completed move. A route move
        // also needs real own displacement; a takeover deliberately stops at the old position.
        double initialDistance=Math.hypot(startX-intent.x,startY-intent.y);
        double arrivalTolerance=takeover?12:Math.min(12,Math.max(1,initialDistance/2));
        if(orderType==null&&Math.hypot(x-intent.x,y-intent.y)<arrivalTolerance
                &&(takeover||Math.hypot(x-startX,y-startY)>.5))return Witness.ARRIVED_AFTER_ACCEPTANCE;
        return Witness.NONE;
    }
}
