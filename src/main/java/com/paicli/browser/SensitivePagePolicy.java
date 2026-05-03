package com.paicli.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class SensitivePagePolicy {
    private static final List<String> DEFAULT_PATTERNS = List.of(
            "*://*.bank.*/*",
            "*://*.alipay.com/*",
            "*://*.paypal.com/*",
            "*://*.stripe.com/*",
            "*://github.com/settings/*",
            "*://*.github.com/settings/*",
            "*://github.com/*/settings/*",
            "*://*.github.com/*/settings/*",
            "*://paypal.com/*",
            "*://*.feishu.cn/admin/*",
            "*://*.larksuite.com/admin/*",
            "*://*.console.cloud.google.com/*",
            "*://*.console.aws.amazon.com/*",
            "*://*.portal.azure.com/*"
    );

    private final List<Rule> rules;

    public SensitivePagePolicy() {
        this(Path.of(System.getProperty("user.home"), ".paicli", "sensitive_patterns.txt"));
    }

    public SensitivePagePolicy(Path userRulesFile) {
        this.rules = compile(loadPatterns(userRulesFile));
    }

    public MatchResult match(String url) {
        if (url == null || url.isBlank()) {
            return MatchResult.notMatched();
        }
        String normalized = url.toLowerCase(Locale.ROOT);
        for (Rule rule : rules) {
            if (rule.regex().matcher(normalized).matches()) {
                return MatchResult.matched(rule.pattern());
            }
        }
        return MatchResult.notMatched();
    }

    public boolean isSensitive(String url) {
        return match(url).matched();
    }

    private static List<String> loadPatterns(Path userRulesFile) {
        List<String> patterns = new ArrayList<>(DEFAULT_PATTERNS);
        if (userRulesFile == null || !Files.exists(userRulesFile)) {
            return patterns;
        }
        try {
            for (String line : Files.readAllLines(userRulesFile)) {
                String trimmed = line.trim();
                if (!trimmed.isBlank() && !trimmed.startsWith("#")) {
                    patterns.add(trimmed);
                }
            }
        } catch (IOException ignored) {
            // 策略文件读取失败时保留默认规则，不能阻塞主流程。
        }
        return patterns;
    }

    private static List<Rule> compile(List<String> patterns) {
        return patterns.stream()
                .map(pattern -> new Rule(pattern, Pattern.compile(globToRegex(pattern.toLowerCase(Locale.ROOT)))))
                .toList();
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> {
                    regex.append('\\').append(c);
                }
                default -> regex.append(c);
            }
        }
        regex.append('$');
        return regex.toString();
    }

    private record Rule(String pattern, Pattern regex) {}

    public record MatchResult(boolean matched, String pattern) {
        static MatchResult matched(String pattern) {
            return new MatchResult(true, pattern);
        }

        static MatchResult notMatched() {
            return new MatchResult(false, null);
        }
    }
}
