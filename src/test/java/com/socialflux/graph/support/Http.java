package com.socialflux.graph.support;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Cliente HTTP mínimo para os testes (JSON e multipart). */
public class Http {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public record Response(int status, byte[] bytes, HttpResponse<byte[]> raw) {

        public String body() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> json() {
            try {
                return MAPPER.readValue(body(), Map.class);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Resposta não é um objeto JSON: " + body(), e);
            }
        }

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> jsonList() {
            return MAPPER.readValue(body(), List.class);
        }

        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    private final HttpClient client = HttpClient.newHttpClient();
    private final String baseUrl;

    public Http(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public Response get(String path, String token) {
        return send("GET", path, null, token);
    }

    public Response send(String method, String path, String json, String token) {
        return send(method, path, json, token, Map.of());
    }

    public Response send(String method, String path, String json, String token, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path));
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        headers.forEach(builder::header);
        HttpRequest.BodyPublisher body = json == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json);
        return execute(builder.method(method, body).build());
    }

    private Response execute(HttpRequest request) {
        try {
            HttpResponse<byte[]> raw = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new Response(raw.statusCode(), raw.body(), raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
