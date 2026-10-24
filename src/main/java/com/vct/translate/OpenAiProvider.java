package com.vct.translate;

import com.vct.translate.TranslationException.Kind;
import com.vct.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * OpenAI adapter using the Responses API ({@code POST /v1/responses}) over plain HTTP.
 * Requests set {@code store:false} so translations are not retained for later retrieval.
 * Pick a non-reasoning, low-latency model: reasoning models spend output tokens before answering.
 */
public final class OpenAiProvider implements TranslationProvider {

    public static final URI DEFAULT_BASE_URL = URI.create("https://api.openai.com/v1/");

    private final HttpClient http;
    private final URI baseUrl;
    private final Supplier<String> apiKey;
    private final String model;
    private final Duration requestTimeout;
    private final int maxOutputTokens;

    public OpenAiProvider(HttpClient http, URI baseUrl, Supplier<String> apiKey, String model,
                          Duration requestTimeout, int maxOutputTokens) {
        this.http = http;
        this.baseUrl = baseUrl.toString().endsWith("/") ? baseUrl : URI.create(baseUrl + "/");
        this.apiKey = apiKey;
        this.model = model;
        this.requestTimeout = requestTimeout;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public String id() {
        return "openai";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public TranslationResult translate(TranslationRequest r) throws TranslationException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("instructions", PromptBuilder.instructions(r));
        body.put("input", PromptBuilder.input(r));
        body.put("max_output_tokens", maxOutputTokens);
        body.put("store", false);

        long t0 = System.nanoTime();
        HttpRequest req = authorized(HttpRequest.newBuilder(baseUrl.resolve("responses")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        Map<String, Object> json = send(req);
        String text = extractOutputText(json);
        return new TranslationResult(text, id(), model, Duration.ofNanos(System.nanoTime() - t0), false);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> listModels() throws TranslationException {
        HttpRequest req = authorized(HttpRequest.newBuilder(baseUrl.resolve("models"))).GET().build();
        Map<String, Object> json = send(req);
        List<String> ids = new ArrayList<>();
        if (json.get("data") instanceof List<?> data) {
            for (Object o : data) {
                if (o instanceof Map<?, ?> m && m.get("id") instanceof String id) ids.add(id);
            }
        }
        ids.sort(null);
        return ids;
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder b) throws TranslationException {
        String key = apiKey.get();
        if (key == null || key.isBlank()) throw new TranslationException(Kind.NOT_CONFIGURED, null);
        return b.timeout(requestTimeout).header("Authorization", "Bearer " + key);
    }

    private Map<String, Object> send(HttpRequest req) throws TranslationException {
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new TranslationException(Kind.TIMEOUT, null, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TranslationException(Kind.CANCELLED, null, null, e);
        } catch (IOException e) {
            throw new TranslationException(Kind.NETWORK, e.getMessage(), null, e);
        }
        int status = resp.statusCode();
        if (status / 100 == 2) {
            try {
                return Json.parseObject(resp.body());
            } catch (Json.JsonException e) {
                throw new TranslationException(Kind.BAD_RESPONSE, "invalid JSON", null, e);
            }
        }
        String detail = errorMessage(resp.body());
        Kind kind = switch (status) {
            case 401, 403 -> Kind.AUTH;
            case 408 -> Kind.TIMEOUT;
            case 429 -> Kind.RATE_LIMIT;
            default -> status >= 500 ? Kind.SERVER : Kind.BAD_REQUEST;
        };
        throw new TranslationException(kind, "HTTP " + status + (detail == null ? "" : ": " + detail),
                retryAfter(resp), null);
    }

    @SuppressWarnings("unchecked")
    static String extractOutputText(Map<String, Object> json) throws TranslationException {
        StringBuilder text = new StringBuilder();
        String refusal = null;
        if (json.get("output") instanceof List<?> output) {
            for (Object item : output) {
                if (!(item instanceof Map<?, ?> m) || !"message".equals(m.get("type"))) continue;
                if (!(m.get("content") instanceof List<?> content)) continue;
                for (Object c : content) {
                    if (!(c instanceof Map<?, ?> part)) continue;
                    if ("output_text".equals(part.get("type")) && part.get("text") instanceof String s) {
                        text.append(s);
                    } else if ("refusal".equals(part.get("type")) && part.get("refusal") instanceof String s) {
                        refusal = s;
                    }
                }
            }
        }
        String result = text.toString().strip();
        if (!result.isEmpty()) return result;
        if (refusal != null) throw new TranslationException(Kind.BAD_RESPONSE, "model refused: " + refusal);
        if ("incomplete".equals(json.get("status"))) {
            Object reason = json.get("incomplete_details") instanceof Map<?, ?> d ? d.get("reason") : null;
            throw new TranslationException(Kind.BAD_RESPONSE, "incomplete response" + (reason == null ? "" : ": " + reason));
        }
        throw new TranslationException(Kind.BAD_RESPONSE, "no output text");
    }

    /** The "error.message" of an OpenAI error response, or null. Also used by the speech API client. */
    public static String errorMessage(String body) {
        try {
            if (Json.parseObject(body).get("error") instanceof Map<?, ?> err && err.get("message") instanceof String m) {
                return m.length() > 300 ? m.substring(0, 300) + "…" : m;
            }
        } catch (RuntimeException ignored) {
            // Non-JSON error body (e.g. proxy HTML); the status code is enough.
        }
        return null;
    }

    private static Duration retryAfter(HttpResponse<?> resp) {
        return resp.headers().firstValue("retry-after").map(v -> {
            try {
                return Duration.ofMillis((long) (Double.parseDouble(v.trim()) * 1000));
            } catch (NumberFormatException e) {
                return null;
            }
        }).orElse(null);
    }
}
