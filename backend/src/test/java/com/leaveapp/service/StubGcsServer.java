package com.leaveapp.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

// In-memory stand-in for the metadata token endpoint and the GCS JSON API, just enough for
// MediaStorageService: media upload, media download, delete.
class StubGcsServer implements AutoCloseable {

    /* Variables */

    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final AtomicInteger tokenCalls = new AtomicInteger();
    private final HttpServer server;

    /* Public Methods */

    StubGcsServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", this::token);
        server.createContext("/upload/storage/v1/b/test-bucket/o", this::upload);
        server.createContext("/storage/v1/b/test-bucket/o/", this::object);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /* Private Helper Methods */

    private void token(HttpExchange ex) throws IOException {
        tokenCalls.incrementAndGet();
        reply(ex, 200, "{\"access_token\":\"tok\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8));
    }

    private void upload(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getRawQuery();
        String name = null;
        for (String kv : query.split("&")) {
            if (kv.startsWith("name=")) {
                name = URLDecoder.decode(kv.substring(5), StandardCharsets.UTF_8);
            }
        }
        if (!"Bearer tok".equals(ex.getRequestHeaders().getFirst("Authorization")) || name == null) {
            reply(ex, 401, new byte[0]);
            return;
        }
        objects.put(name, ex.getRequestBody().readAllBytes());
        reply(ex, 200, "{}".getBytes(StandardCharsets.UTF_8));
    }

    private void object(HttpExchange ex) throws IOException {
        String raw = ex.getRequestURI().getRawPath().substring("/storage/v1/b/test-bucket/o/".length());
        String key = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        if ("DELETE".equals(ex.getRequestMethod())) {
            reply(ex, objects.remove(key) == null ? 404 : 204, new byte[0]);
            return;
        }
        byte[] body = objects.get(key);
        reply(ex, body == null ? 404 : 200, body == null ? new byte[0] : body);
    }

    private static void reply(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, status == 204 ? -1 : body.length);
        if (status != 204) {
            ex.getResponseBody().write(body);
        }
        ex.close();
    }
}
