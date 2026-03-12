package com.paicli.rag;

import com.huaban.analysis.jieba.JiebaSegmenter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 查询分词器。
 *
 * 目标不是做复杂 NLP，而是把自然语言问题里的“代码关键词”尽量保留下来，
 * 例如类名、方法名、ReAct、Agent、index、memory 等，用于混合检索加权。
 */
final class RagQueryTokenizer {
    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();
    private static final Pattern ASCII_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9_.$-]{1,}");

    private RagQueryTokenizer() {
    }

    static Set<String> tokenize(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return tokens;
        }

        String normalized = query.trim();
        List<String> words = SEGMENTER.sentenceProcess(normalized);
        for (String word : words) {
            String token = word.trim();
            if (isUsefulToken(token)) {
                tokens.add(token);
            }
        }

        Matcher matcher = ASCII_TOKEN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (isUsefulToken(token)) {
                tokens.add(token);
            }
        }

        return tokens;
    }

    private static boolean isUsefulToken(String token) {
        if (token == null) {
            return false;
        }

        String normalized = token.trim();
        if (normalized.length() < 2) {
            return false;
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        boolean stopword = switch (lower) {
            case "怎么", "如何", "什么", "哪些", "一下", "实现", "的是", "一个", "可以", "这里", "那里" -> true;
            default -> false;
        };
        return !stopword && isMeaningful(normalized);
    }

    private static boolean isMeaningful(String token) {
        boolean hasHan = token.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
        boolean hasAsciiWord = token.codePoints().anyMatch(Character::isLetterOrDigit);
        return hasHan || hasAsciiWord;
    }
}
