package com.paicli.memory;

import com.huaban.analysis.jieba.JiebaSegmenter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 基于 jieba 的检索分词器。
 *
 * 使用 jieba 进行中文分词，英文保留完整单词。
 * 过滤掉单字和纯标点，保留有意义的词语用于关键词匹配。
 */
final class MemoryQueryTokenizer {
    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();

    private MemoryQueryTokenizer() {
    }

    /**
     * 对查询文本进行分词，返回用于检索匹配的 token 集合。
     */
    static Set<String> tokenize(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return tokens;
        }

        List<String> words = SEGMENTER.sentenceProcess(query.toLowerCase(Locale.ROOT).trim());
        for (String word : words) {
            String trimmed = word.trim();
            // 过滤单字符和纯标点
            if (trimmed.length() >= 2 && !isPunctuation(trimmed)) {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }

    /**
     * 检查文本中是否包含任意一个 query token（子串匹配）。
     */
    static boolean matches(String text, Set<String> queryTokens) {
        if (text == null || text.isBlank() || queryTokens.isEmpty()) {
            return false;
        }

        String normalizedText = text.toLowerCase(Locale.ROOT);
        for (String token : queryTokens) {
            if (normalizedText.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPunctuation(String s) {
        return s.codePoints().allMatch(cp ->
                !Character.isLetterOrDigit(cp) && Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN);
    }
}
