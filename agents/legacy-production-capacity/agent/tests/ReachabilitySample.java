import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;
import com.corrodinggames.rts.game.units.y;
public final class ReachabilitySample {
    public static void main(String[] a) throws Exception {
        EconomyHarness.test();
        com.corrodinggames.rts.game.i e = EconomyHarness.engine;
        EconomyHarness.set(Class.forName("com.corrodinggames.rts.game.units.e.n"), "b",
            BridgeHarness.construct("com.corrodinggames.rts.gameFramework.m.e"));
        ar c = ar.valueOf("commandCenter"); c.h(); am enemy = c.a(true);
        am.bF.put(c, enemy); enemy.eh = 771; enemy.bX = new com.corrodinggames.rts.game.e(1, false);
        enemy.cm = 1; enemy.eo = 1200; enemy.ep = 1700; am.bE.add(enemy);
        ar t = ar.valueOf("tank"); t.h(); y tank = (y) t.a(true);
        am.bF.put(t, tank); tank.eh = 772; tank.bX = e.bs; tank.cm = 1; tank.eo = 941; tank.ep = 1781;
        am.bE.add(tank);
        String body = BridgeHarness.check("GET", "/combat/reachability?unitId=772&targetId=771", 200, "status");
        System.out.println("RAW_PAYLOAD_BEGIN");
        System.out.println(body);
        System.out.println("RAW_PAYLOAD_END");
        System.exit(0);
    }
}
