package com.vct.tts;

import com.sun.net.httpserver.HttpServer;
import com.vct.audio.AudioClip;
import com.vct.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Against a local stand-in for the speech endpoint, which answers with binary PCM. */
class OpenAiTtsTest {

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> auth = new CopyOnWriteArrayList<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<byte[]> reply = new AtomicReference<>(new byte[0]);
    private final AtomicReference<String> key = new AtomicReference<>("sk-test");
    private OpenAiTts tts;
    private final java.util.concurrent.CountDownLatch restSent = new java.util.concurrent.CountDownLatch(1);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/speech", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = reply.get();
            ex.sendResponseHeaders(status.get(), b.length == 0 ? -1 : b.length);
            if (b.length > 0) ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/v1/slow/audio/speech", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, 0); // chunked, like OpenAI
            ex.getResponseBody().write(new byte[]{1, 0, 2, 0});
            ex.getResponseBody().flush();
            try {
                restSent.await(); // the rest only after the test has read the start
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.getResponseBody().write(new byte[]{3, 0});
            ex.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        tts = new OpenAiTts(HttpClient.newHttpClient(), base, key::get, "gpt-4o-mini-tts", "alloy", Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsTextAndDecodesPcm() throws Exception {
        reply.set(new byte[]{0, 0, (byte) 0xFF, 0x7F, 0x00, (byte) 0x80}); // 0, max, min
        AudioClip clip = tts.synthesize("右の建物に敵がいます。", "ja", null, 1.0f);
        assertEquals(24_000, clip.sampleRate());
        assertEquals(3, clip.samples().length);
        assertEquals(0f, clip.samples()[0]);
        assertEquals(32767 / 32768f, clip.samples()[1]);
        assertEquals(-1f, clip.samples()[2]);

        Map<String, Object> sent = Json.parseObject(bodies.getFirst());
        assertEquals("gpt-4o-mini-tts", sent.get("model"));
        assertEquals("右の建物に敵がいます。", sent.get("input"));
        assertEquals("alloy", sent.get("voice"));
        assertEquals("pcm", sent.get("response_format"));
        assertTrue(!sent.containsKey("speed"), "normal speed is not sent");
        assertEquals("Bearer sk-test", auth.getFirst());
    }

    @Test
    void streamsTheVoiceBeforeItIsComplete() throws Exception {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/slow");
        OpenAiTts slow = new OpenAiTts(HttpClient.newHttpClient(), base, key::get, "gpt-4o-mini-tts", "alloy", Duration.ofSeconds(5));
        try (SpeechStream s = slow.stream("Go left.", "en", null, 1f)) {
            assertEquals(24_000, s.sampleRate());
            byte[] start = s.pcm16().readNBytes(4);
            assertEquals(List.of((byte) 1, (byte) 0, (byte) 2, (byte) 0), List.of(start[0], start[1], start[2], start[3]),
                    "the first audio is readable while the server still holds back the rest");
            restSent.countDown();
            assertEquals(2, s.pcm16().readAllBytes().length);
        } finally {
            restSent.countDown();
        }
    }

    @Test
    void voiceIdAndSpeedOverrideTheDefaults() throws Exception {
        reply.set(new byte[2]);
        tts.synthesize("Go left.", "en", "nova", 1.25f);
        Map<String, Object> sent = Json.parseObject(bodies.getFirst());
        assertEquals("nova", sent.get("voice"));
        assertEquals(1.25, ((Number) sent.get("speed")).doubleValue(), 1e-9);
    }

    @Test
    void errorsAreExplainedAndNothingIsSentWithoutAKey() throws Exception {
        status.set(401);
        reply.set("{\"error\":{\"message\":\"Incorrect API key provided.\"}}".getBytes(StandardCharsets.UTF_8));
        TtsException e = assertThrows(TtsException.class, () -> tts.synthesize("Hi", "en", null, 1f));
        assertEquals("OpenAI speech failed (HTTP 401: Incorrect API key provided.).", e.getMessage());

        key.set(null);
        int before = bodies.size();
        assertTrue(assertThrows(TtsException.class, () -> tts.synthesize("Hi", "en", null, 1f))
                .getMessage().startsWith("Add your OpenAI API key"));
        assertEquals(before, bodies.size());
        assertEquals(0, tts.synthesize(" ", "en", null, 1f).samples().length);
    }
}
