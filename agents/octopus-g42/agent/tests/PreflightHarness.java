import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;
public final class PreflightHarness {
    static void check(String text)throws Exception{BridgeHarness.check("GET","/economy/preflight",200,text);}
    public static void main(String[] args)throws Exception {
        try {
            EconomyHarness.replacement=true;EconomyHarness.test();
            com.corrodinggames.rts.game.i e=EconomyHarness.engine;
            e.cf.b.clear();double money=e.bs.o;
            check("WAIT_FACTORY_QUEUE");
            EconomyHarness.factory.cm=.5f;check("WAIT_FACTORY_CONSTRUCTION");
            am.bE.remove(EconomyHarness.factory);check("RUN_ECONOMY_OR_OPENING");
            am factory=ar.b.a(true);factory.bX=e.bs;factory.eh=57;am.bE.add(factory);
            e.bs.o=0;check("RUN_DEVELOP");check("\"factoryId\":57");
            factory.bX=new com.corrodinggames.rts.game.e(1,false);check("\"idleFactories\":0");check("RUN_ECONOMY_OR_OPENING");
            am.bE.remove(factory);am.bE.remove(EconomyHarness.builder);check("PREPARE_BUILDER");
            e.bX.B=true;check("RESOLVE_GAME_STATE");e.bX.B=false;
            e.bG=false;check("RESOLVE_GAME_STATE");
            BridgeHarness.check("POST","/economy/preflight",405,"GET");
            BridgeHarness.check("GET","/economy/preflight?unitId=1",400,"no query");
            BridgeHarness.check("GET","/economy/preflight/extra",404,"unknown");
            BridgeHarness.require(e.bs.o==0 && e.cf.b.isEmpty() && am.bE.isEmpty(),"preflight never changes money, commands or units");
            System.out.println("PREFLIGHT_TEST_OK checks="+BridgeHarness.checks);System.exit(0);
        }catch(Throwable t){t.printStackTrace();System.exit(1);}
    }
}
