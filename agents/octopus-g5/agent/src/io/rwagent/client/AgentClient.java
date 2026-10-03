package io.rwagent.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class AgentClient {
    private AgentClient() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            usage();
            System.exit(2);
        }
        String action = args[0].toLowerCase();
        String method;
        String path;
        if ("health".equals(action)) {
            method = "GET";
            path = "/health";
        } else if ("state".equals(action)) {
            method = "GET";
            path = "/state";
        } else if ("move-first".equals(action)) {
            method = "POST";
            path = "/test/move-first";
        } else {
            usage();
            System.exit(2);
            return;
        }

        int port = Integer.getInteger("rwagent.port", 47653);
        try {
            Response response = request(method, "http://127.0.0.1:" + port + path);
            System.out.println("HTTP " + response.status);
            System.out.println(response.body);
            if (response.status < 200 || response.status >= 300) {
                System.exit(1);
            }
        } catch (Throwable error) {
            System.err.println("Cannot reach RW Agent API on 127.0.0.1:" + port);
            System.err.println("Start the game with RW-Agent-Start.bat first.");
            error.printStackTrace();
            System.exit(1);
        }
    }

    static Response request(String method, String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(7000);
        connection.setUseCaches(false);
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
            connection.getOutputStream().close();
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = input == null ? "" : readAll(input);
        connection.disconnect();
        return new Response(status, body);
    }

    private static String readAll(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    private static void usage() {
        System.out.println("Usage: AgentClient health|state|move-first");
    }

    static final class Response {
        final int status;
        final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
