package com.vct.tts;

import com.vct.audio.AudioClip;
import com.vct.translate.OpenAiProvider;
import com.vct.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link TtsEngine} over OpenAI's speech API ({@code POST /v1/audio/speech}). Natural voices in
 * English, Japanese and many other languages, for the translation the other participants hear.
 * Asks for raw PCM (24 kHz, 16-bit, mono), so there is nothing to decode.
 *
 * <p>The {@code language} argument is not needed: the voices speak the language of the text.
 * {@code voiceId} overrides the configured voice.
 */
public final class OpenAiTts implements TtsEngine {

    /** The API's "pcm" format: 24 kHz, signed 16-bit little-endian, mono. */
    static final int PCM_RATE = 24_000;

    private final HttpClient http;
    private final URI baseUrl;
    private final Supplier<String> apiKey;
    private final String model;
    private final String voice;
    private final Duration timeout;

    /**
     * @param model e.g. "gpt-4o-mini-tts"
     * @param voice e.g. "alloy"
     */
    public OpenAiTts(HttpClient http, URI baseUrl, Supplier<String> apiKey, String model, String voice, Duration timeout) {
        this.http = http;
        this.baseUrl = baseUrl.toString().endsWith("/") ? baseUrl : URI.create(baseUrl + "/");
        this.apiKey = apiKey;
        this.model = model;
        this.voice = voice;
        this.timeout = timeout;
    }

    @Override
    public AudioClip synthesize(String text, String language, String voiceId, float speed) throws TtsException {
        if (text == null || text.isBlank()) return new AudioClip(new float[0], PCM_RATE);
        String key = apiKey.get();
        if (key == null || key.isBlank()) throw new TtsException("Add your OpenAI API key in Settings.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", text);
        body.put("voice", voiceId == null || voiceId.isBlank() ? voice : voiceId);
        body.put("response_format", "pcm");
        if (speed != 1.0f) body.put("speed", Math.clamp(speed, 0.25, 4.0));
        HttpRequest req = HttpRequest.newBuilder(baseUrl.resolve("audio/speech"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new TtsException("OpenAI speech timed out after " + timeout.toSeconds() + " s.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TtsException("Speech request cancelled.");
        } catch (IOException e) {
            throw new TtsException("Could not reach OpenAI for speech: " + e.getMessage());
        }
        if (resp.statusCode() / 100 != 2) {
            String detail = OpenAiProvider.errorMessage(new String(resp.body(), StandardCharsets.UTF_8));
            throw new TtsException("OpenAI speech failed (HTTP " + resp.statusCode() + (detail == null ? "" : ": " + detail) + ").");
        }
        return new AudioClip(pcm16ToFloat(resp.body()), PCM_RATE);
    }

    static float[] pcm16ToFloat(byte[] pcm) {
        ByteBuffer b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[pcm.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = b.getShort(2 * i) / 32768f;
        return out;
    }
}
