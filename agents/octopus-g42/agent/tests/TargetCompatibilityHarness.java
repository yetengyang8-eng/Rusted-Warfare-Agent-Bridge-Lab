import io.rwagent.client.TargetCatalog;

/** Contract-level E2 checks against the catalog shipped in the agent JAR. */
public final class TargetCompatibilityHarness {
    private static void expect(String attacker, String domain, Boolean water,
                               boolean fresh, boolean trusted, String expected) {
        TargetCatalog.Decision d=TargetCatalog.evaluate(attacker,domain,water,fresh,trusted);
        if(!expected.equals(d.status))throw new AssertionError(attacker+" -> "+domain
                +" fresh="+fresh+" trusted="+trusted+" expected "+expected+" got "+d.status
                +" reason="+d.reason);
        if(d.reason==null||d.reason.isEmpty()||d.sourceId==null||d.sourceId.isEmpty())
            throw new AssertionError("decision lacks reason/sourceId: "+attacker+" -> "+domain);
    }
    public static void main(String[] args) {
        if(!"263cdaaa3e923e8307c37f059e50bee529b8ecd7c3e215937ce2adf615a0e236"
                .equals(TargetCatalog.catalogSha256()))
            throw new AssertionError("packaged catalog identity differs from accepted knowledge packet");
        expect("tank","AIR",null,true,true,"INCOMPATIBLE");
        expect("c_tank","AIR",null,true,true,"INCOMPATIBLE");
        expect("heavyTank","AIR",null,true,true,"COMPATIBLE");
        expect("scout","AIR",null,true,true,"COMPATIBLE");
        expect("tank","SURFACE",Boolean.TRUE,true,true,"COMPATIBLE");
        expect("tank","SUBMERGED",null,true,true,"INCOMPATIBLE");
        for(String noncombat:new String[]{"builder","landFactory","airFactory","extractorT1","extractorT2"})
            expect(noncombat,"SURFACE",Boolean.FALSE,true,true,"INCOMPATIBLE");
        expect("tank","UNKNOWN",null,true,true,"UNKNOWN");
        expect("tank","AIR",null,false,true,"UNKNOWN");
        expect("tank","AIR",null,true,false,"UNKNOWN");
        expect("unknownBuiltInType","AIR",null,true,true,"UNKNOWN");
        System.out.println("TargetCompatibilityHarness PASS");
    }
}
