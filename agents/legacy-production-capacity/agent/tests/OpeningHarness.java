import com.corrodinggames.rts.game.units.am;
import com.corrodinggames.rts.game.units.ar;
import java.util.*;
import java.lang.reflect.*;

/** Native extractor placement on actual map layer + original tank replacement regression. */
public final class OpeningHarness {
    static boolean customExtractor;
    static com.corrodinggames.rts.game.units.as extractorType;
    static am preview() throws Exception {return (am)extractorType.getClass().getMethod("a",boolean.class).invoke(extractorType,true);}
    static void require(boolean b,String s){BridgeHarness.require(b,s);}
    static String check(String m,String p,int s,String t)throws Exception{return BridgeHarness.check(m,p,s,t);}
    public static void main(String[] args)throws Exception {
        try { customExtractor=args.length>0;test();System.out.println("OPENING_NATIVE_TEST_OK totalChecks="+BridgeHarness.checks);System.exit(0); }
        catch(Throwable t){t.printStackTrace();System.exit(1);}
    }
    static void test()throws Exception {
        EconomyHarness.replacement=true;EconomyHarness.test();
        com.corrodinggames.rts.game.i engine=EconomyHarness.engine;
        am.bE.remove(EconomyHarness.factory);engine.cf.b.clear();engine.bs.o=10000;
        Object images=Array.newInstance(Class.forName("com.corrodinggames.rts.gameFramework.m.e"),10);
        EconomyHarness.set(Class.forName("com.corrodinggames.rts.game.units.d.g"),"e",images);
        ar extractor=ar.valueOf("extractor");extractor.h();
        am.bF.put(extractor,extractor.a(true));extractorType=extractor;
        if(customExtractor) {
            Class<?> meta=Class.forName("com.corrodinggames.rts.game.units.custom.l");Object value=meta.newInstance();
            EconomyHarness.set(value,"M","extractorT1");EconomyHarness.set(value,"ch",extractor.u());EconomyHarness.set(value,"ck",.001f);
            EconomyHarness.set(value,"cl",1);meta.getMethod("h").invoke(value);
            EconomyHarness.set(value,"aH",true);EconomyHarness.set(value,"aJ",true);
            EconomyHarness.set(value,"da",10f);EconomyHarness.set(value,"db",10f);
            EconomyHarness.set(value,"fg",com.corrodinggames.rts.game.units.ao.a);
            EconomyHarness.set(value,"fQ",Array.newInstance(Class.forName("com.corrodinggames.rts.game.units.custom.bn"),0));
            ((Map)meta.getField("f").get(null)).put(extractor,value);
            ((ar)EconomyHarness.builder.r()).h();extractorType=(com.corrodinggames.rts.game.units.as)value;
            // Invoke the real custom metadata preview constructor; no fallback to the legacy extractor.
            int before=am.bE.size();am ghost=preview();
            require(ghost.r()==extractorType && am.bE.size()==before,"real custom preview keeps type and stays outside world");
        }
        EconomyHarness.set(engine.bL,"y",engine.bL.u);
        Constructor<?> ctor=Class.forName("com.corrodinggames.rts.game.b.g").getDeclaredConstructor();ctor.setAccessible(true);Object tile=ctor.newInstance();
        EconomyHarness.set(tile,"i",true);Object tiles=Array.newInstance(tile.getClass(),2);Array.set(tiles,1,tile);EconomyHarness.set(engine.bL,"B",tiles);
        short[] cells=new short[12100];cells[49*110+94]=1;cells[55*110+94]=1;
        EconomyHarness.set(engine.bL.u,"q",cells);
        String session=(String)EconomyHarness.obj(check("GET","/state",200,"running")).get("sessionId");
        Map<?,?> plan=EconomyHarness.obj(check("GET","/opening/plan?unitId=4",200,"planned"));
        require(((Number)plan.get("extractorX")).intValue()==990 && ((Number)plan.get("extractorY")).intValue()==1890,"nearest visible mine selected at native tile center");
        require(((Number)plan.get("totalCost")).intValue()==2450 && ((Number)plan.get("targetTanks")).intValue()==3,"opening budget is 700 + 700 + 3*350");
        require(extractorType.i().equals(plan.get("extractorType")),"plan preserves resolved extractor type");
        require("c_tank".equals(plan.get("productType")),"opening keeps original loaded tank replacement");
        engine.bs.o=0;check("GET","/opening/plan",200,"planned");require(engine.cf.b.isEmpty(),"read-only plan works without credits and never orders");engine.bs.o=10000;
        engine.bs.N[49][94]=10;
        Map<?,?> other=EconomyHarness.obj(check("GET","/opening/plan",200,"planned"));require(((Number)other.get("extractorX")).intValue()==1110,"hidden nearest mine is not selected");
        engine.bs.N[55][94]=10;check("GET","/opening/plan",409,"no legal opening site");engine.bs.N[49][94]=0;engine.bs.N[55][94]=0;
        am occupied=customExtractor?preview():extractor.a(true);occupied.bX=engine.bs;occupied.eh=80;occupied.eo=990;occupied.ep=1890;am.bE.add(occupied);
        other=EconomyHarness.obj(check("GET","/opening/plan",200,"planned"));require(((Number)other.get("extractorX")).intValue()==1110,"occupied nearest mine is skipped");am.bE.remove(occupied);
        String order="/command/build-extractor?unitId=4&x=990&y=1890&sessionId="+session+"&requestId=mine";
        check("POST",order,200,"extractor");check("POST",order,200,"queued");require(engine.cf.b.size()==1,"extractor retry has one native command");
        com.corrodinggames.rts.gameFramework.e cmd=(com.corrodinggames.rts.gameFramework.e)engine.cf.b.get(0);
        require(cmd.j.a()==extractorType && cmd.j.g()==990 && cmd.j.h()==1890,"actual extractor waypoint type and coordinates");
        require(am.bE.size()==1 && engine.bs.o==10000,"planning and queue submission did not spawn or directly charge units");
        check("POST",order.replace("requestId=mine","requestId=ground").replace("x=990","x=970"),409,"footprint");
        engine.bs.N[49][94]=10;check("POST",order.replace("requestId=mine","requestId=fog"),409,"footprint");engine.bs.N[49][94]=0;
        engine.bs.N[49][customExtractor?94:93]=10;check("POST",order.replace("requestId=mine","requestId=footprint-fog"),409,"footprint");engine.bs.N[49][customExtractor?94:93]=0;
        cells[49*110+94]=0;check("POST",order.replace("requestId=mine","requestId=removed-site"),409,"footprint");cells[49*110+94]=1;
        check("POST",order.replace("requestId=mine","requestId=old-session").replace(session,"old"),409,"session changed");
        check("POST",order.replace("requestId=mine","requestId=foreign").replace("unitId=4","unitId=99"),409,"not found");
        engine.bs.o=0;check("POST",order.replace("requestId=mine","requestId=poor"),409,"credits");engine.bs.o=10000;
        engine.bX.B=true;check("GET","/opening/plan",409,"network");engine.bX.B=false;
        require(engine.cf.b.size()==1,"invalid extractor requests queued nothing");
    }
}
