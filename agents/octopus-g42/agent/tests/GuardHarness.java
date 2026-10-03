import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;
import com.corrodinggames.rts.game.units.y;
import java.util.*;

public final class GuardHarness {
    static void require(boolean b,String why){BridgeHarness.require(b,why);}
    public static void main(String[] args)throws Exception {
        try {
            OpeningHarness.test();com.corrodinggames.rts.game.i e=EconomyHarness.engine;e.cf.b.clear();
            EconomyHarness.set(Class.forName("com.corrodinggames.rts.game.units.e.n"),"b",BridgeHarness.construct("com.corrodinggames.rts.gameFramework.m.e"));
            am tank=ar.valueOf("tank").a(true);tank.eh=91;tank.bX=e.bs;tank.eo=920;tank.ep=1780;am.bE.add(tank);
            String session=(String)EconomyHarness.obj(BridgeHarness.check("GET","/state",200,"running")).get("sessionId");
            String command="/command/guard?unitId=91&targetId=4&sessionId="+session+"&requestId=escort-one";
            BridgeHarness.check("POST",command,200,"guard");BridgeHarness.check("POST",command,200,"queued");
            require(e.cf.b.size()==1,"guard retry queues only once");
            com.corrodinggames.rts.gameFramework.e order=(com.corrodinggames.rts.gameFramework.e)e.cf.b.get(0);
            require(order.j.d().name().equals("guard") && order.j.i()==EconomyHarness.builder,"native guard waypoint references the own builder");
            e.bV=new com.corrodinggames.rts.gameFramework.aa();
            order.k();require(((y)tank).ar()!=null && "guard".equals(((y)tank).ar().d().name()),"native command execution applies guard order");
            BridgeHarness.check("GET","/state",200,"\"guardTargetId\":4");
            BridgeHarness.check("POST",command.replace("targetId=4","targetId=91"),409,"reused");
            BridgeHarness.check("POST",command.replace("escort-one","foreign").replace("targetId=4","targetId=99"),409,"own");
            BridgeHarness.check("POST",command.replace("escort-one","unarmed").replace("unitId=91&targetId=4","unitId=4&targetId=91"),409,"armed");
            BridgeHarness.check("POST",command.replace("escort-one","self").replace("targetId=4","targetId=91"),409,"distinct");
            BridgeHarness.check("POST",command.replace("escort-one","stale").replace(session,"old"),409,"session");
            BridgeHarness.check("POST",command.replace("escort-one","nan").replace("unitId=91","unitId=NaN"),400,"For input");
            tank.cm=.8f;BridgeHarness.check("POST",command.replace("escort-one","partial"),409,"completed");tank.cm=1;
            e.bX.B=true;BridgeHarness.check("POST",command.replace("escort-one","online"),409,"network");e.bX.B=false;
            require(e.cf.b.size()==1,"invalid guard commands never queue");
            am enemy=ar.valueOf("tank").a(true);enemy.eh=99;enemy.bX=new com.corrodinggames.rts.game.e(1,false);enemy.bX.r=1;e.bs.r=0;
            enemy.eo=110;enemy.ep=110;am.bE.add(enemy);e.bs.N[5][5]=10;
            Map<?,?> observed=EconomyHarness.obj(BridgeHarness.check("GET","/scout/observe",200,"observed"));
            require(((List<?>)observed.get("visibleThreats")).isEmpty(),"hidden enemy is never exposed");
            e.bs.N[5][5]=0;observed=EconomyHarness.obj(BridgeHarness.check("GET","/scout/observe",200,"observed"));
            require(((List<?>)observed.get("visibleThreats")).size()==1,"visible armed enemy is reported");
            e.bs.N[5][5]=10;observed=EconomyHarness.obj(BridgeHarness.check("GET","/scout/observe",200,"observed"));
            require(((List<?>)observed.get("visibleThreats")).isEmpty() && ((List<?>)observed.get("rememberedThreats")).size()==1,"loss of visibility yields only last-seen memory");
            System.out.println("GUARD_NATIVE_TEST_OK checks="+BridgeHarness.checks);System.exit(0);
        }catch(Throwable error){error.printStackTrace();System.exit(1);}
    }
}
