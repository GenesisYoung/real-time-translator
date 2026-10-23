package com.vct.transcript;

import com.vct.model.Utterance;
import com.vct.translate.TranslationResult;
import com.vct.util.Json;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One UTF-8 JSON Lines file per session.
 *
 * <p>While the session runs the file is an append-only log: a {@code session} header, one
 * {@code utt} line per utterance, and {@code tr} lines for translations that arrive later. Every
 * line is written and forced to disk immediately, so a crash loses at most the line being written.
 * On clean close, and on recovery after a crash, the log is compacted so each utterance occupies
 * exactly one line with its translations embedded.
 */
public final class JsonlTranscriptWriter implements TranscriptSink {

    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final Path file;
    private final FileChannel channel;
    private final Clock clock;
    private boolean closed;

    private JsonlTranscriptWriter(Path file, FileChannel channel, Clock clock) {
        this.file = file;
        this.channel = channel;
        this.clock = clock;
    }

    /** Creates a new session file in {@code dir}. Never overwrites an existing file. */
    public static JsonlTranscriptWriter create(Path dir, String sessionId, Map<String, Object> header, Clock clock)
            throws IOException {
        Files.createDirectories(dir);
        Instant now = clock.instant();
        Path file = dir.resolve("session-" + FILE_TS.format(now) + "-" + sessionId + ".jsonl");
        FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        JsonlTranscriptWriter w = new JsonlTranscriptWriter(file, ch, clock);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "session");
        line.put("id", sessionId);
        line.put("started", now.toString());
        line.put("format", 1);
        line.putAll(header);
        w.append(line);
        return w;
    }

    public Path file() {
        return file;
    }

    @Override
    public synchronized void writeUtterance(Utterance u) throws IOException {
        append(utteranceLine(u));
    }

    @Override
    public synchronized void writeTranslation(String utteranceId, String targetLanguage, TranslationResult r)
            throws IOException {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "tr");
        line.put("ref", utteranceId);
        line.putAll(translationFields(targetLanguage, r, clock.instant()));
        append(line);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("type", "session_end");
        end.put("ended", clock.instant().toString());
        append(end);
        closed = true;
        channel.close();
        compact(file);
    }

    /** Closes the file handle without ending the session, as a crash would. Tests only. */
    synchronized void abandonForCrashTest() throws IOException {
        closed = true;
        channel.close();
    }

    private void append(Map<String, Object> line) throws IOException {
        if (closed) throw new IOException("transcript closed");
        ByteBuffer bytes = ByteBuffer.wrap((Json.write(line) + "\n").getBytes(StandardCharsets.UTF_8));
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(false);
    }

    static Map<String, Object> utteranceLine(Utterance u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "utt");
        m.put("id", u.id());
        m.put("t", u.start().toString());
        m.put("end", u.end().toString());
        m.put("src", u.source().label());
        m.put("speaker", null); // speaker identity is not determined reliably from mixed loopback audio
        m.put("lang", u.language());
        m.put("lang_conf", round(u.languageConfidence()));
        m.put("text", u.text());
        m.put("conf", round(u.confidence()));
        if (u.lowConfidence()) m.put("low_conf", true);
        return m;
    }

    static Map<String, Object> translationFields(String targetLanguage, TranslationResult r, Instant at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lang", targetLanguage);
        m.put("text", r.text());
        m.put("provider", r.provider());
        m.put("model", r.model());
        m.put("at", at.toString());
        return m;
    }

    private static Double round(double v) {
        return Double.isNaN(v) ? null : Math.round(v * 1000) / 1000.0;
    }

    /**
     * Rewrites an append log as one line per utterance. Tolerates a truncated final line (crash
     * mid-write) and unknown line types. The original is replaced atomically.
     */
    @SuppressWarnings("unchecked")
    public static void compact(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Map<String, Object> header = null;
        Map<String, Object> end = null;
        Map<String, Map<String, Object>> utts = new LinkedHashMap<>();
        int skipped = 0;
        for (String raw : lines) {
            if (raw.isBlank()) continue;
            Map<String, Object> m;
            try {
                m = Json.parseObject(raw);
            } catch (RuntimeException e) {
                skipped++;
                continue;
            }
            switch (String.valueOf(m.get("type"))) {
                case "session" -> header = m;
                case "session_end" -> end = m;
                case "utt" -> utts.put(String.valueOf(m.get("id")), m);
                case "tr" -> {
                    Map<String, Object> u = utts.get(String.valueOf(m.get("ref")));
                    if (u == null) {
                        skipped++;
                        continue;
                    }
                    Map<String, Object> tr = new LinkedHashMap<>(m);
                    tr.remove("type");
                    tr.remove("ref");
                    ((List<Object>) u.computeIfAbsent("translations", k -> new ArrayList<>())).add(tr);
                }
                default -> skipped++;
            }
        }
        if (end == null) {
            end = new LinkedHashMap<>();
            end.put("type", "session_end");
            end.put("recovered", true);
        }
        end.put("compacted", true);
        if (skipped > 0) end.put("skipped_lines", skipped);

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        StringBuilder sb = new StringBuilder();
        if (header != null) sb.append(Json.write(header)).append('\n');
        for (Map<String, Object> u : utts.values()) sb.append(Json.write(u)).append('\n');
        sb.append(Json.write(end)).append('\n');
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer bytes = ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) ch.write(bytes);
            ch.force(true);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Compacts session files left un-compacted by a crash. Returns the files it repaired. */
    public static List<Path> recover(Path dir) throws IOException {
        List<Path> repaired = new ArrayList<>();
        if (!Files.isDirectory(dir)) return repaired;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "session-*.jsonl")) {
            for (Path p : ds) {
                if (!isCompacted(p)) {
                    compact(p);
                    repaired.add(p);
                }
            }
        }
        return repaired;
    }

    private static boolean isCompacted(Path p) throws IOException {
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).isBlank()) continue;
            try {
                Map<String, Object> m = Json.parseObject(lines.get(i));
                return "session_end".equals(m.get("type")) && Boolean.TRUE.equals(m.get("compacted"));
            } catch (RuntimeException e) {
                return false;
            }
        }
        return false;
    }
}
