package com.vct.tts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The files of one sherpa-onnx TTS voice, found in an unpacked model folder such as
 * {@code vits-piper-zh_CN-huayan-medium} or {@code kokoro-multi-lang-v1_1}.
 *
 * @param lexicons  word-to-phoneme tables (MeloTTS, Kokoro); empty for Piper
 * @param dataDir   espeak-ng data (Piper, Kokoro), or null
 * @param dictDir   jieba dictionary for Chinese word splitting (MeloTTS), or null
 * @param ruleFsts  text normalization rules, e.g. Chinese numbers and dates, applied in this order
 */
public record TtsVoice(Kind kind, Path dir, Path model, Path tokens, Path voices, List<Path> lexicons,
                       Path dataDir, Path dictDir, List<Path> ruleFsts) {

    public enum Kind {
        /** Piper, MeloTTS and other VITS models. */
        VITS,
        /** Kokoro (has a voices.bin with many speakers). */
        KOKORO
    }

    // sherpa-onnx's documented order for its Chinese rules; other .fst files follow alphabetically.
    private static final List<String> FST_ORDER = List.of("phone-zh.fst", "date-zh.fst", "number-zh.fst");

    public TtsVoice {
        lexicons = List.copyOf(lexicons);
        ruleFsts = List.copyOf(ruleFsts);
    }

    /**
     * Inspects an unpacked voice folder. Checking here matters: sherpa-onnx ends the whole process
     * on some invalid models instead of reporting an error, so the obvious mistakes are caught first.
     */
    public static TtsVoice detect(Path dir) throws TtsException {
        if (!Files.isDirectory(dir)) throw new TtsException("Voice folder not found: " + dir.toAbsolutePath());
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.sorted().toList();
        } catch (IOException e) {
            throw new TtsException("Cannot read voice folder " + dir.toAbsolutePath() + ": " + e.getMessage());
        }
        List<Path> onnx = files.stream().filter(p -> name(p).endsWith(".onnx")).toList();
        Path tokens = dir.resolve("tokens.txt");
        Path voices = dir.resolve("voices.bin");
        if (!Files.isRegularFile(tokens)) {
            throw new TtsException("No tokens.txt in " + dir.toAbsolutePath() + ". Is this a sherpa-onnx TTS voice folder?");
        }
        if (onnx.isEmpty()) throw new TtsException("No .onnx model in " + dir.toAbsolutePath());
        // Some releases ship model.onnx and model.int8.onnx: prefer the full-precision file.
        Path model = onnx.stream().filter(p -> !name(p).contains(".int8.")).findFirst().orElse(onnx.getFirst());

        List<Path> lexicons = files.stream()
                .filter(p -> name(p).startsWith("lexicon") && name(p).endsWith(".txt")).toList();
        List<Path> fsts = new ArrayList<>(files.stream().filter(p -> name(p).endsWith(".fst")).toList());
        fsts.sort(Comparator.comparingInt((Path p) -> {
            int i = FST_ORDER.indexOf(name(p));
            return i < 0 ? FST_ORDER.size() : i;
        }).thenComparing(TtsVoice::name));
        Path data = Files.isDirectory(dir.resolve("espeak-ng-data")) ? dir.resolve("espeak-ng-data") : null;
        Path dict = Files.isDirectory(dir.resolve("dict")) ? dir.resolve("dict") : null;

        Kind kind = Files.isRegularFile(voices) ? Kind.KOKORO : Kind.VITS;
        if (data == null && lexicons.isEmpty()) {
            throw new TtsException("Voice folder " + dir.toAbsolutePath()
                    + " has neither espeak-ng-data nor a lexicon, so text cannot be turned into sounds.");
        }
        return new TtsVoice(kind, dir, model, tokens, kind == Kind.KOKORO ? voices : null, lexicons, data, dict, fsts);
    }

    private static String name(Path p) {
        return p.getFileName().toString();
    }
}
