package io.rwagent.bootstrap;

import com.corrodinggames.rts.gameFramework.l;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class RwAgent {
    private static final String LOG_FILE = "rw-agent-bootstrap.log";

    private RwAgent() {
    }

    /**
     * Appends one UTF-8 log line. The file writer must pin UTF-8 explicitly: this log records the
     * provenance line, which contains absolute paths that are not ASCII on many machines.
     */
    private static PrintWriter openLog() throws java.io.IOException {
        return new PrintWriter(new OutputStreamWriter(new FileOutputStream(LOG_FILE, true), StandardCharsets.UTF_8));
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        final int port = Integer.getInteger("rwagent.port", 47653);
        final boolean allowCommands = Boolean.getBoolean("rwagent.allowCommands");
        log("RW Agent 0.07-alpha1 bootstrap loaded; port=" + port
                + ", allowCommands=" + allowCommands);

        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                waitForGame(port, allowCommands);
            }
        }, "rw-agent-game-waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    private static void waitForGame(int port, boolean allowCommands) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                l engine = l.B();
                if (engine instanceof com.corrodinggames.rts.game.i) {
                    RuntimeBridge.start(
                            (com.corrodinggames.rts.game.i) engine,
                            port,
                            allowCommands);
                    return;
                }
                Thread.sleep(250L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable error) {
                log("Waiting for the game failed", error);
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public static synchronized void log(String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
        String line = timestamp + " [RW-Agent] " + message;
        System.out.println(line);
        try {
            PrintWriter writer = openLog();
            try {
                writer.write(line);
                writer.write(System.lineSeparator());
            } finally {
                writer.close();
            }
        } catch (Throwable ignored) {
            // Logging must never stop the game from starting.
        }
    }

    public static synchronized void log(String message, Throwable error) {
        log(message + ": " + error);
        try {
            PrintWriter writer = openLog();
            try {
                error.printStackTrace(writer);
            } finally {
                writer.close();
            }
        } catch (Throwable ignored) {
            // Logging must never stop the game from starting.
        }
    }
}
