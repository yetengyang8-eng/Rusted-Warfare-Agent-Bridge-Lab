package io.rwagent.bootstrap;

/** Bridge-owned logging; no dependency on either frozen agent's bootstrap or strategy. */
final class BridgeLog {
    private BridgeLog() {}
    static void log(String message) { System.out.println("[PlayerBridge] " + message); }
    static void log(String message, Throwable error) { log(message + ": " + error); }
}
