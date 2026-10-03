package io.rwagent.client;

/** Product duration and experiment safety bound are separate, audited values. */
final class BattleBudget {
    static int gameLimit(){return Math.max(1800,Math.min(21600,Integer.getInteger("rwagent.battleSafetyGameSeconds",7200)));}
    static int wallLimit(){return Math.max(120,Math.min(3600,Integer.getInteger("rwagent.battleSafetyWallSeconds",2400)));}
    static int seconds(String[] args){
        int value=args.length==0?900:Integer.parseInt(args[0]);
        if(args.length>1||value<120||value>gameLimit())throw new IllegalArgumentException("Battle duration must be 120.."+gameLimit()+" game seconds (configured safety limit)");
        return value;
    }
}
