package io.rwagent.client;

import java.util.*;

/** Terrain evidence, not a native path/fire/kill oracle. Unknown cells never prove a blockade. */
public final class EngagementGeometry {
    public static final byte UNKNOWN=Byte.MIN_VALUE;
    public static final class Result {
        public final String status,reason;
        public final double x,y;
        public final int distanceTiles,visited;
        Result(String status,String reason,double x,double y,int distance,int visited){
            this.status=status;this.reason=reason;this.x=x;this.y=y;distanceTiles=distance;this.visited=visited;
        }
    }
    private EngagementGeometry(){}
    /** A target/range/movement field serves the whole formation in O(map + actors), not O(map*actors). */
    public static final class Field {
        private final int w,h,tw,th;private final byte[] terrain;
        private final int[] known,goal,optimistic;
        Field(int w,int h,int tw,int th,byte[] terrain,boolean[] water,double tx,double ty,double range,double radius,boolean wet){
            this.w=w;this.h=h;this.tw=tw;this.th=th;this.terrain=terrain;
            if(w<=0||h<=0||tw<=0||th<=0||(long)w*h>262144||terrain==null||terrain.length!=w*h
                    ||!Double.isFinite(tx)||!Double.isFinite(ty)||!Double.isFinite(range)||range<=0){known=goal=optimistic=null;return;}
            // Positive: known terrain, four-neighbor path and a point safely inside range.
            // Negative: unknown is free, eight-neighbor corner cutting is allowed, and the goal
            // radius is expanded by a full tile diagonal. This graph over-approximates native paths.
            known=new int[w*h];goal=new int[w*h];optimistic=new int[w*h];
            reverse(terrain,water,tx,ty,Math.max(1,range-20),wet,false,known,goal);
            reverse(terrain,water,tx,ty,range+Math.hypot(tw,th)+Math.max(0,radius),wet,true,optimistic,null);
        }
        private void reverse(byte[] cells,boolean[] water,double tx,double ty,double range,boolean wet,boolean allowUnknown,int[] distance,int[] goal){
            Arrays.fill(distance,-1);if(goal!=null)Arrays.fill(goal,-1);
            int[] queue=new int[w*h];int head=0,tail=0;
            for(int x=Math.max(0,(int)((tx-range)/tw));x<w&&(x+.5)*tw<=tx+range;x++)
                for(int y=Math.max(0,(int)((ty-range)/th));y<h&&(y+.5)*th<=ty+range;y++){
                    int at=x*h+y;
                    if(cells[at]==-1||!allowUnknown&&cells[at]==UNKNOWN)continue;
                    if(wet&&!(water!=null&&water[at])&&!(allowUnknown&&cells[at]==UNKNOWN))continue;
                    if(Math.hypot((x+.5)*tw-tx,(y+.5)*th-ty)>range)continue;
                    distance[at]=0;if(goal!=null)goal[at]=at;queue[tail++]=at;
                }
            while(head<tail){int at=queue[head++],x=at/h,y=at%h;
                for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){
                    if(dx==0&&dy==0||!allowUnknown&&dx!=0&&dy!=0)continue;
                    int nx=x+dx,ny=y+dy;if(nx<0||ny<0||nx>=w||ny>=h)continue;
                    int next=nx*h+ny;
                    if(distance[next]>=0||cells[next]==-1||!allowUnknown&&cells[next]==UNKNOWN)continue;
                    distance[next]=distance[at]+1;if(goal!=null)goal[next]=goal[at];queue[tail++]=next;
                }
            }
        }
        public Result from(double sx,double sy){
            if(known==null||!Double.isFinite(sx)||!Double.isFinite(sy))return unknown("MISSING_GEOMETRY",sx,sy,0);
            int x=(int)Math.floor(sx/tw),y=(int)Math.floor(sy/th);
            if(x<0||y<0||x>=w||y>=h)return unknown("ACTOR_OUTSIDE_MAP",sx,sy,0);
            int root=x*h+y,near=root;
            // The actor's occupied start cell may itself be marked blocked. Only its first step
            // may leave that cell; do not turn it into a traversable corridor for other actors.
            if(known[root]<0&&terrain[root]==-1){
                for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)if(Math.abs(dx)+Math.abs(dy)==1){
                    int nx=x+dx,ny=y+dy;if(nx<0||ny<0||nx>=w||ny>=h)continue;
                    int at=nx*h+ny;if(known[at]>=0&&(known[near]<0||known[at]<known[near]))near=at;
                }
            }
            if(known[near]>=0){int at=goal[near];return new Result("APPROACH_PATH_KNOWN","LEGAL_TERRAIN_ROUTE_NOT_FIRE_PROOF",
                (at/h+.5)*tw,(at%h+.5)*th,known[near]+(near==root?0:1),w*h);}
            boolean possible=optimistic[root]>=0;
            if(!possible&&terrain[root]==-1)for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){
                int nx=x+dx,ny=y+dy;if(nx>=0&&ny>=0&&nx<w&&ny<h&&optimistic[nx*h+ny]>=0)possible=true;
            }
            return possible?unknown("UNOBSERVED_OR_MARGINAL_APPROACH",sx,sy,w*h):
                new Result("BLOCKED_TERRAIN","NO_APPROACH_EVEN_WITH_UNKNOWN_TERRAIN_PASSABLE",sx,sy,-1,w*h);
        }
    }
    public static Field prepare(int w,int h,int tw,int th,byte[] terrain,boolean[] water,double tx,double ty,double range,double radius,boolean wet){
        return new Field(w,h,tw,th,terrain,water,tx,ty,range,radius,wet);
    }
    public static Result solve(int w,int h,int tw,int th,byte[] terrain,boolean[] water,
                               double sx,double sy,double tx,double ty,double range,
                               double targetRadius,boolean requireWater){
        return prepare(w,h,tw,th,terrain,water,tx,ty,range,targetRadius,requireWater).from(sx,sy);
    }
    private static Result unknown(String reason,double x,double y,int visited){return new Result("UNKNOWN",reason,x,y,-1,visited);}
}
