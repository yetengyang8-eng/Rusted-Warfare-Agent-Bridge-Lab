package io.rwagent.client;
import java.util.Map;
/** Fresh opening or existing idle factory -> bounded battle. Child reports remain independent. */
public final class MatchClient {
    public static void main(String[] args){System.exit(run(args));}
    static int run(String[] args){
        try{
            int seconds=BattleBudget.seconds(args);
            BootstrapClient bootstrap=new BootstrapClient();int boot=bootstrap.run();if(boot!=0)return boot;
            Map<?,?> p=bootstrap.preflight();String recommendation=(String)p.get("recommendation");
            if("RUN_ECONOMY_OR_OPENING".equals(recommendation)){
                int code=new EconomyClient().run(new String[0],false);if(code!=0)return code;
                code=new DevelopmentClient(false).run(new String[]{"6","1"});if(code!=0)return code;
            }else if(!"RUN_DEVELOP".equals(recommendation))throw new IllegalStateException("Preflight: "+recommendation);
            return new BattleClient().run(new String[]{String.valueOf(seconds)});
        }catch(Exception e){System.err.println(e);return 1;}
    }
}
