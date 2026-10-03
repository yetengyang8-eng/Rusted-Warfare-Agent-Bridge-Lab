package io.rwagent.client;

/** Existing factory -> parallel production and bounded exploration/expansion. */
public final class FrontierClient {
    public static void main(String[] args) {System.exit(new DevelopmentClient(true).run(args));}
}
