package io.rwagent.client;

import java.util.Map;

/** Start from a standard builder or reuse an idle factory. Each child commits its own report. */
public final class AutopilotClient {
    public static void main(String[] args) {System.exit(run(args));}
    static int run(String[] args) {
        try {
            // Validate all arguments before a bootstrap can spend any credits.
            if(args.length>3)throw new IllegalArgumentException("Usage: AutopilotClient [tanks 1..30 [mines 0..10 [scoutMoves 1..48]]]");
            int[] bounds={30,10,48};for(int i=0;i<args.length;i++){int n=Integer.parseInt(args[i]);if(n<(i==1?0:1) || n>bounds[i])throw new IllegalArgumentException("argument out of bounds");}
            int port=Integer.getInteger("rwagent.port",47653);
            AgentClient.Response response=AgentClient.request("GET","http://127.0.0.1:"+port+"/economy/preflight");
            if(response.status!=200)throw new IllegalStateException("Preflight HTTP "+response.status+": "+response.body);
            Map<?,?> preflight=(Map<?,?>)Json.parse(response.body);
            if(!Boolean.TRUE.equals(preflight.get("commandsAllowed")))throw new IllegalStateException("Preflight: "+response.body);
            String recommendation=(String)preflight.get("recommendation");
            if("RUN_ECONOMY_OR_OPENING".equals(recommendation)) {
                System.out.println("Bootstrap: building one factory and verifying one tank.");
                int bootstrap=new EconomyClient().run(new String[0],false);if(bootstrap!=0)return bootstrap;
            } else if(!"RUN_DEVELOP".equals(recommendation))throw new IllegalStateException("Preflight: "+recommendation+"; finish pending work before restarting");
            return new DevelopmentClient(true).run(args);
        }catch(Exception error){System.err.println(error);return 1;}
    }
}
