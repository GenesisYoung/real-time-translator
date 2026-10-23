package com.vct.translate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** Local stand-in for the OpenAI API. Responses are scripted per request, in order. */
final class MockOpenAiServer implements AutoCloseable {

    record Reply(int status, String body, Map<String, String> headers, long delayMs) {
        static Reply ok(String body) {
            return new Reply(200, body, Map.of(), 0);
        }
    }

    record Recorded(String method, String path, String authorization, String body) {}

    private final HttpServer server;
    private final ConcurrentLinkedQueue<Reply> script = new ConcurrentLinkedQueue<>();
    final List<Recorded> requests = new CopyOnWriteArrayList<>();

    MockOpenAiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
    }

    MockOpenAiServer enqueue(Reply r) {
        script.add(r);
        return this;
    }

    static String responseWithText(String text) {
        return """
                {"id":"resp_1","object":"response","status":"completed",
                 "output":[{"type":"message","role":"assistant",
                   "content":[{"type":"output_text","text":%s,"annotations":[]}]}]}
                """.formatted(com.vct.util.Json.write(text));
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Recorded(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                ex.getRequestHeaders().getFirst("Authorization"), body));
        Reply r = script.poll();
        if (r == null) r = new Reply(500, "{\"error\":{\"message\":\"no scripted reply\"}}", Map.of(), 0);
        if (r.delayMs() > 0) {
            try {
                Thread.sleep(r.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
        r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.getResponseHeaders().add("Content-Type", "application/json");
        try {
            ex.sendResponseHeaders(r.status(), bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // client gave up (timeout test)
        } finally {
            ex.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
