package com.vct.stt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HallucinationFilterTest {

    private final HallucinationFilter filter = HallucinationFilter.defaults();

    private static SttResult result(String text, double confidence) {
        return new SttResult(text, null, Double.NaN, confidence, Double.NaN);
    }

    @Test
    void dropsJapaneseAndChineseStockPhrasesWithTheirPunctuation() {
        assertTrue(filter.reject(result("ご視聴ありがとうございました。", 0.7)));
        assertTrue(filter.reject(result("チャンネル登録よろしくお願いします！", 0.7)));
        assertTrue(filter.reject(result("请不吝点赞 订阅 转发 打赏支持明镜与点点栏目", 0.7)));
        assertTrue(filter.reject(result("字幕由Amara.org社区提供", 0.7)));
        assertTrue(filter.reject(result("谢谢观看。", 0.7)));
    }

    @Test
    void keepsConfidentStockPhrasesAndRealSentences() {
        assertFalse(filter.reject(result("ご視聴ありがとうございました。", 0.95)), "said for real, and clearly");
        assertFalse(filter.reject(result("左に行こう。", 0.7)));
        assertFalse(filter.reject(result("我们走左边。", 0.7)));
    }

    @Test
    void normalizesUnicodePunctuationAndWidth() {
        assertEquals("谢谢观看", HallucinationFilter.normalize("「谢谢观看。」"));
        assertEquals("thank you", HallucinationFilter.normalize("Ｔｈａｎｋ　ｙｏｕ！"));
        assertEquals("it's ok", HallucinationFilter.normalize("It's OK..."));
    }
}
