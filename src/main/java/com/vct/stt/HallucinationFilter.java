package com.vct.stt;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * Rejects recognition output that is almost certainly not real speech. Whisper in particular
 * "hears" stock phrases in noise and silence.
 */
public final class HallucinationFilter {

    private static final Set<String> KNOWN_PHANTOMS = Set.of(
            "thank you", "thanks for watching", "thank you for watching",
            "please subscribe", "subscribe to my channel", "you",
            "untertitel im auftrag des zdf", "sous-titres réalisés par la communauté d'amara.org",
            "amara.org",
            // Japanese
            "ご視聴ありがとうございました", "ご視聴ありがとうございます", "最後までご視聴いただきありがとうございました",
            "チャンネル登録よろしくお願いします", "チャンネル登録をお願いします",
            // Chinese
            "请不吝点赞 订阅 转发 打赏支持明镜与点点栏目", "字幕由amara.org社区提供", "谢谢观看", "感谢观看",
            "谢谢大家", "请订阅我的频道");

    private final double maxNoSpeechProbability;
    private final double minConfidence;

    public HallucinationFilter(double maxNoSpeechProbability, double minConfidence) {
        this.maxNoSpeechProbability = maxNoSpeechProbability;
        this.minConfidence = minConfidence;
    }

    public static HallucinationFilter defaults() {
        return new HallucinationFilter(0.6, 0.35);
    }

    public boolean reject(SttResult r) {
        String norm = normalize(r.text());
        if (norm.isEmpty()) return true;
        if (!Double.isNaN(r.noSpeechProbability()) && r.noSpeechProbability() > maxNoSpeechProbability
                && (Double.isNaN(r.confidence()) || r.confidence() < 0.6)) {
            return true;
        }
        if (!Double.isNaN(r.confidence()) && r.confidence() < minConfidence) return true;
        // Stock phrases are only dropped when the recognizer is not confident about them.
        return KNOWN_PHANTOMS.contains(norm) && (Double.isNaN(r.confidence()) || r.confidence() < 0.8);
    }

    static String normalize(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
        // Unicode punctuation, not just ASCII \p{Punct}: Japanese and Chinese use 。、「」 etc.
        s = s.replaceAll("[\\p{IsPunctuation}&&[^'.-]]", " ").replaceAll("\\s+", " ").trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }
}
