package io.rwagent.client;

/** Future search demand backed by explicit legal terrain/exploration coverage, never by hidden enemies.
 * This contract does not activate a search policy or introduce native facts. */
public final class SearchAreaNeed {
    public enum Domain { WATER, ISLAND }
    public final String areaId,coverageObservationId;
    public final Domain domain;
    public final double minX,minY,maxX,maxY,coverage,confidence;
    public final long observedAtGameTimeMs;
    public final Long lastSearchedGameTimeMs;
    private SearchAreaNeed(String id,Domain domain,double x,double y,double maxX,double maxY,double coverage,
                           Long searched,double confidence,long at,String observation){
        areaId=id;this.domain=domain;minX=x;minY=y;this.maxX=maxX;this.maxY=maxY;this.coverage=coverage;
        lastSearchedGameTimeMs=searched;this.confidence=confidence;observedAtGameTimeMs=at;coverageObservationId=observation;
    }
    /** All three trigger facts must be explicit; battle ongoing alone cannot locate a search area. */
    public static SearchAreaNeed lawful(boolean battleOngoing,boolean landHasNoLegalReachableTarget,boolean legalCoverage,
            String id,Domain domain,double x,double y,double maxX,double maxY,double coverage,Long searched,
            double confidence,long at,String observation){
        if(!battleOngoing||!landHasNoLegalReachableTarget||!legalCoverage)return null;
        if(id==null||id.isEmpty()||domain==null||observation==null||observation.isEmpty()||at<0
                ||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(maxX)||!Double.isFinite(maxY)||x>=maxX||y>=maxY
                ||!Double.isFinite(coverage)||coverage<0||coverage>1||!Double.isFinite(confidence)||confidence<0||confidence>1
                ||searched!=null&&(searched<0||searched>at))throw new IllegalArgumentException("lawful coverage evidence required");
        if(coverage>=1)return null;
        return new SearchAreaNeed(id,domain,x,y,maxX,maxY,coverage,searched,confidence,at,observation);
    }
    /** Caller supplies an evidence aging horizon, not a new strategy threshold. */
    public double confidenceAt(long now,long horizon){if(now<observedAtGameTimeMs||horizon<=0)throw new IllegalArgumentException("aging clock");
        return confidence*Math.max(0,1-(now-observedAtGameTimeMs)/(double)horizon);}
    public Long lastSearchedAgeAt(long now){if(now<observedAtGameTimeMs)throw new IllegalArgumentException("aging clock");return lastSearchedGameTimeMs==null?null:now-lastSearchedGameTimeMs;}
    public double confidenceWeightedCoverageAt(long now,long horizon){return coverage*confidenceAt(now,horizon);}
    public String underwaterDiscoveryRequirement(){return "NEEDS_EVIDENCE";}
}
