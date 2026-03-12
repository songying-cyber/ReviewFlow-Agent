package com.paicli.rag;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索结果展示格式化。
 *
 * 保持实现简单：不额外调用 LLM，只根据查询和 Top 结果生成简短摘要，
 * 让 /search 更像“可读搜索结果”，而不是只打印原始代码片段。
 */
public final class SearchResultFormatter {

    private SearchResultFormatter() {
    }

    public static String formatForCli(String query, List<VectorStore.SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 找到 ").append(results.size()).append(" 个相关代码块:\n\n");
        sb.append(buildSummary(query, results)).append("\n\n");

        for (int i = 0; i < results.size(); i++) {
            VectorStore.SearchResult result = results.get(i);
            sb.append(String.format("%d. [%s:%s] (相似度: %.3f) %s%n",
                    i + 1,
                    result.chunkType(),
                    result.name(),
                    result.similarity(),
                    result.filePath()));
            sb.append("   ").append(buildSnippet(result.content(), 120).replace("\n", "\n   "));
            sb.append("\n\n");
        }

        return sb.toString().trim();
    }

    public static String formatForTool(String query, List<VectorStore.SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("检索摘要:\n");
        sb.append(buildSummary(query, results)).append("\n\n");
        sb.append("检索结果:\n");

        for (int i = 0; i < results.size(); i++) {
            VectorStore.SearchResult result = results.get(i);
            sb.append(String.format("%d. [%s:%s] (相似度: %.3f) %s\n",
                    i + 1,
                    result.chunkType(),
                    result.name(),
                    result.similarity(),
                    result.filePath()));
            sb.append("   ").append(buildSnippet(result.content(), 180).replace("\n", "\n   ")).append("\n\n");
        }

        return sb.toString().trim();
    }

    static String buildSummary(String query, List<VectorStore.SearchResult> results) {
        if (results.isEmpty()) {
            return "搜索摘要:\n- 没有命中可用代码块。";
        }

        VectorStore.SearchResult top = results.get(0);
        Set<String> fileNames = results.stream()
                .map(result -> Paths.get(result.filePath()).getFileName().toString())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> queryTokens = RagQueryTokenizer.tokenize(query).stream()
                .limit(3)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        String topFile = shortenPath(top.filePath());
        String relatedFiles = fileNames.stream().limit(3).collect(Collectors.joining("、"));
        String tokenText = queryTokens.isEmpty() ? "自然语言语义" : String.join("、", queryTokens);

        StringBuilder sb = new StringBuilder("搜索摘要:\n");
        sb.append("- 最相关的入口是 [")
                .append(top.chunkType())
                .append(":")
                .append(top.name())
                .append("]，位于 ")
                .append(topFile)
                .append("。\n");
        sb.append("- 当前结果主要集中在 ")
                .append(relatedFiles)
                .append(fileNames.size() > 3 ? " 等文件" : " 这些文件")
                .append("。\n");
        sb.append("- 这次排序综合参考了 ")
                .append(tokenText)
                .append(" 等关键词与语义相似度；先看第 1 条，再按文件继续展开最稳妥。");
        return sb.toString();
    }

    private static String buildSnippet(String content, int maxChars) {
        String normalized = content == null ? "" : content.trim().replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isEmpty()) {
            return "(无内容片段)";
        }
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...";
    }

    private static String shortenPath(String filePath) {
        Path path = Paths.get(filePath);
        int count = path.getNameCount();
        if (count <= 3) {
            return filePath;
        }
        return path.subpath(count - 3, count).toString();
    }
}
