package io.rwagent.bootstrap;
import com.corrodinggames.rts.game.i;
import com.corrodinggames.rts.game.units.*;
import java.lang.reflect.*;
/** Referee-only comparison, never a public native-global placement endpoint. */
public final class NativePlacementHarness {
 public static void main(String[] args) throws Exception {
  Method init=Class.forName("TerrainNativeCostHarness").getDeclaredMethod("initialize",String.class);init.setAccessible(true);
  i engine=(i)init.invoke(null,"maps/skirmish/[p2]Small_Island (2p).tmx");
  y builder=null;for(Object raw:am.bE){am u=(am)raw;if(u.bX==engine.bs){System.out.println("OWN "+u.r().i()+" "+u.eo+","+u.ep);if("builder".equals(u.r().i()))builder=(y)u;}}
  RuntimeBridge b=PlayerScopeHarness.bridge(engine,null);b.refreshSession();EconomyBridge economy=new EconomyBridge(b);
  y ghost=(y)ar.b.a(true);ghost.bX=engine.bs;ghost.eo=1090;ghost.ep=1730;
  System.out.println("TYPE movement="+ar.b.o()+" ghostMovement="+ghost.h()+" offset="+ghost.cZ()+","+ghost.da()+" native="+ghost.b(false,engine.bs));
  android.graphics.Rect rect=ghost.cd();int col=(int)Math.floor((ghost.eo-ghost.cZ()+1)/20),row=(int)Math.floor((ghost.ep-ghost.da()+1)/20);
  for(int c=col+rect.a;c<=col+rect.c;c++)for(int r=row+rect.b;r<=row+rect.d;r++){
   int tile=c*110+r;com.corrodinggames.rts.gameFramework.k.i costs=engine.bU.a(ar.b.o()),land=engine.bU.a(ao.b);
   System.out.println("TILE "+c+","+r+" move d/e/f="+costs.d[tile]+"/"+costs.e[tile]+"/"+costs.f[tile]+" land d/e/f="+land.d[tile]+"/"+land.e[tile]+"/"+land.f[tile]+" resource="+(engine.bL.e(c,r)!=null&&engine.bL.e(c,r).i));
  }
  Object site=PlayerScopeHarness.invoke(economy,"site",new Class<?>[]{y.class,as.class,float.class,float.class},builder,ar.b,1090f,1730f);
  System.out.println("OVERLAY_CANDIDATE="+(site!=null));
  if(site!=null)throw new AssertionError("resource-overlapping factory must be rejected");
  RuntimeBridge.CommandResult plan=(RuntimeBridge.CommandResult)PlayerScopeHarness.invoke(economy,"dispatch",new Class<?>[]{String.class,java.util.Map.class},"/economy/plan",java.util.Collections.singletonMap("unitId",Long.toString(builder.eh)));
  if(plan.httpStatus!=200)throw new AssertionError(plan.json);
  java.util.Map<?,?> data=(java.util.Map<?,?>)Json.parse(plan.json);ghost.eo=((Number)data.get("targetX")).floatValue();ghost.ep=((Number)data.get("targetY")).floatValue();
  System.out.println("FIXED_PLAN="+plan.json+" NATIVE_REJECTION="+ghost.b(false,engine.bs));
  if(ghost.b(false,engine.bs)!=null)throw new AssertionError("replacement factory candidate must satisfy original native placement");
  System.out.println("NATIVE_PLACEMENT_OK resource_overlap_rejected=true replacement_native_legal=true");System.exit(0);
 }
}
