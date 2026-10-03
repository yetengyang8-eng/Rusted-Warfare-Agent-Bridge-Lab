import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;
import java.util.Map;

public final class ProductionPlanHarness {
    public static void main(String[] args)throws Exception {
        try {
            EconomyHarness.replacement=true;EconomyHarness.test();
            com.corrodinggames.rts.game.i engine=EconomyHarness.engine;
            BridgeHarness.check("GET","/economy/production-plan",409,"idle landFactory");
            am.bE.remove(EconomyHarness.factory);engine.cf.b.clear();
            am factory=ar.b.a(true);factory.bX=engine.bs;factory.eh=51;am.bE.add(factory);
            engine.bs.o=0;
            Map<?,?> plan=EconomyHarness.obj(BridgeHarness.check("GET","/economy/production-plan",200,"planned"));
            BridgeHarness.require("c_tank".equals(plan.get("productType")) && ((Number)plan.get("factoryId")).longValue()==51 && ((Number)plan.get("tankCost")).intValue()==350,"existing factory actual product, id and price with zero funds");
            BridgeHarness.check("GET","/economy/production-plan?unitId=4",409,"idle landFactory");
            BridgeHarness.check("GET","/economy/production-plan?unitId=51",200,"planned");
            factory.cm=.99999f;BridgeHarness.check("GET","/economy/production-plan",409,"idle landFactory");factory.cm=1;
            factory.bX=null;BridgeHarness.check("GET","/economy/production-plan",409,"idle landFactory");factory.bX=engine.bs;
            engine.bX.B=true;BridgeHarness.check("GET","/economy/production-plan",409,"network");engine.bX.B=false;
            BridgeHarness.check("GET","/economy/production-plan?foo=1",400,"optional field");
            BridgeHarness.require(engine.cf.b.isEmpty() && engine.bs.o==0 && am.bE.size()==2,"production planning submits nothing, spends nothing and spawns nothing");
            System.out.println("PRODUCTION_PLAN_TEST_OK totalChecks="+BridgeHarness.checks);System.exit(0);
        }catch(Throwable t){t.printStackTrace();System.exit(1);}
    }
}
