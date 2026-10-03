import android.content.ServerContext;
import io.rwagent.bootstrap.RuntimeBridge;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SmokeHarness {
    public static void main(String[] args) throws Exception {
        android.os.Looper.a();
        final com.corrodinggames.rts.game.i engine =
                new com.corrodinggames.rts.game.i(new ServerContext());
        final AtomicBoolean running = new AtomicBoolean(true);
        Thread simulatedGameThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running.get()) {
                    Runnable runnable = (Runnable) engine.k.poll();
                    if (runnable != null) {
                        runnable.run();
                    } else {
                        try {
                            Thread.sleep(2L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        }, "simulated-game-thread");
        simulatedGameThread.setDaemon(true);
        simulatedGameThread.start();

        int port = 47655;
        RuntimeBridge.start(engine, port, true);
        check("GET", "http://127.0.0.1:" + port + "/health", 200, "\"status\":\"ok\"");
        check("GET", "http://127.0.0.1:" + port + "/state", 200, "\"status\":\"menu\"");
        check("POST", "http://127.0.0.1:" + port + "/test/move-first", 409,
                "no level is currently loaded");
        running.set(false);
        System.out.println("SMOKE_TEST_OK");
        System.exit(0);
    }

    private static void check(String method, String address, int expectedStatus, String expectedText)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod(method);
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
            connection.getOutputStream().close();
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        input.close();
        connection.disconnect();
        String body = new String(output.toByteArray(), StandardCharsets.UTF_8);
        if (status != expectedStatus || !body.contains(expectedText)) {
            throw new AssertionError("Unexpected response: " + status + " " + body);
        }
        System.out.println(method + " " + address + " -> " + status + " " + body);
    }
}
