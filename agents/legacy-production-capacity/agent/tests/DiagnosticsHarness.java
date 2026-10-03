import com.corrodinggames.rts.game.units.am;
import java.util.*;
public final class DiagnosticsHarness {
    static Map<?,?> diag(String text){return (Map<?,?>)EconomyHarness.obj(text).get("diagnostics");}
    static void count(Map<?,?> d,String name,int value){BridgeHarness.require(((Number)d.get(name)).intValue()==value,name+"="+value);}
    static String plan(int status)throws Exception{return BridgeHarness.check("GET","/opening/plan",status,status==200?"planned":"no legal opening site;");}
    public static void main(String[] args)throws Exception {
        try {
            OpeningHarness.test();
            com.corrodinggames.rts.game.i e=EconomyHarness.engine;
            e.cf.b.clear();int before=am.bE.size();double credits=e.bs.o;
            Map<?,?> d=diag(plan(200));count(d,"visibleResourceCandidates",2);count(d,"legalCandidates",2);count(d,"nativeRejected",0);
            java.lang.reflect.Field f=e.bL.u.getClass().getDeclaredField("q");f.setAccessible(true);short[] cells=(short[])f.get(e.bL.u);
            cells[76*110+109]=1;
            d=diag(plan(200));count(d,"visibleResourceCandidates",3);count(d,"outsideRange",1);count(d,"legalCandidates",2);
            cells[76*110+109]=0;
            e.bs.N[49][93]=10;
            d=diag(plan(200));count(d,"visibleResourceCandidates",2);count(d,"footprintNotVisible",1);count(d,"legalCandidates",1);
            e.bs.N[49][93]=0;
            am occupied=com.corrodinggames.rts.game.units.ar.valueOf("extractor").a(true);occupied.bX=e.bs;occupied.eh=90;occupied.eo=990;occupied.ep=1890;am.bE.add(occupied);
            d=diag(plan(200));count(d,"nativeRejected",1);count(d,"legalCandidates",1);
            BridgeHarness.require(!((Map<?,?>)d.get("nativeRejectionReasons")).isEmpty(),"native reason retained without inferred occupancy label");
            am.bE.remove(occupied);
            e.bs.N[49][94]=10;e.bs.N[55][94]=10;
            d=diag(plan(409));count(d,"visibleResourceCandidates",0);count(d,"legalCandidates",0);count(d,"nativeRejected",0);
            e.bs.N[49][94]=0;e.bs.N[55][94]=0;
            d=diag(plan(200));
            int sum=0;for(String k:new String[]{"outsideRange","footprintOutsideMap","footprintNotVisible","nativeRejected","legalCandidates"})sum+=((Number)d.get(k)).intValue();
            count(d,"visibleResourceCandidates",sum);
            BridgeHarness.require(before==am.bE.size() && credits==e.bs.o && e.cf.b.isEmpty(),"diagnostics never register, charge or command");
            System.out.println("DIAGNOSTICS_TEST_OK checks="+BridgeHarness.checks);System.exit(0);
        }catch(Throwable t){t.printStackTrace();System.exit(1);}
    }
}
