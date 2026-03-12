package com.paicli.rag;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 代码检索器：语义检索 + 图谱检索的统一入口
 */
public class CodeRetriever implements AutoCloseable {
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;

    public CodeRetriever(String projectPath) throws SQLException {
        this.embeddingClient = new EmbeddingClient();
        this.vectorStore = new VectorStore(Paths.get(projectPath).toAbsolutePath().normalize().toString());
    }

    public CodeRetriever(String projectPath, EmbeddingClient embeddingClient) throws SQLException {
        this.embeddingClient = embeddingClient;
        this.vectorStore = new VectorStore(Paths.get(projectPath).toAbsolutePath().normalize().toString());
    }

    /**
     * 语义检索：用自然语言查询最相关的代码块
     */
    public List<VectorStore.SearchResult> semanticSearch(String query, int topK) throws Exception {
        float[] queryEmbedding = embeddingClient.embed(query);
        return vectorStore.search(queryEmbedding, topK);
    }

    /**
     * 关键词检索：按类名/方法名/内容精确匹配
     */
    public List<VectorStore.SearchResult> keywordSearch(String keyword) throws SQLException {
        return vectorStore.searchByKeyword(keyword);
    }

    /**
     * 混合检索：同时进行语义检索和关键词检索，合并去重
     */
    public List<VectorStore.SearchResult> hybridSearch(String query, int topK) throws Exception {
        Map<String, VectorStore.SearchResult> merged = new LinkedHashMap<>();
        Set<String> dualMatchBonused = new HashSet<>();

        // 1. 语义检索
        int semanticLimit = Math.max(topK * 2, 10);
        for (VectorStore.SearchResult result : semanticSearch(query, semanticLimit)) {
            mergeResult(merged, result, dualMatchBonused);
        }

        // 2. 关键词检索
        Set<String> keywords = RagQueryTokenizer.tokenize(query);
        for (String keyword : keywords) {
            for (VectorStore.SearchResult result : keywordSearch(keyword)) {
                mergeResult(merged, boostKeywordMatch(result, keyword), dualMatchBonused);
            }
        }

        // 3. 代码类型加分：method/class 比 file 更直接回答"怎么实现"
        List<VectorStore.SearchResult> ranked = new ArrayList<>();
        for (VectorStore.SearchResult r : merged.values()) {
            double typeBoost = switch (r.chunkType()) {
                case "method" -> 0.15;
                case "class" -> 0.10;
                default -> 0.0;
            };
            ranked.add(typeBoost == 0.0 ? r : new VectorStore.SearchResult(
                    r.filePath(), r.chunkType(), r.name(), r.content(), r.similarity() + typeBoost));
        }

        ranked.sort(Comparator.comparingDouble(VectorStore.SearchResult::similarity).reversed());
        return limitPerFile(ranked, topK, 2);
    }

    private void mergeResult(Map<String, VectorStore.SearchResult> merged, VectorStore.SearchResult candidate,
                             Set<String> dualMatchBonused) {
        String key = candidate.filePath() + "#" + candidate.name();
        VectorStore.SearchResult existing = merged.get(key);
        if (existing == null) {
            merged.put(key, candidate);
        } else {
            double best = Math.max(existing.similarity(), candidate.similarity());
            // 双重命中奖励只给一次，不重复叠加
            if (!dualMatchBonused.contains(key)) {
                best += 0.1;
                dualMatchBonused.add(key);
            }
            merged.put(key, new VectorStore.SearchResult(
                    candidate.filePath(), candidate.chunkType(), candidate.name(),
                    candidate.content(), best));
        }
    }

    private VectorStore.SearchResult boostKeywordMatch(VectorStore.SearchResult result, String keyword) {
        String nameLower = result.name().toLowerCase();
        String fileLower = result.filePath().toLowerCase();
        String contentLower = result.content().toLowerCase();
        String keywordLower = keyword.toLowerCase();

        // 加分幅度控制在 0.1~0.5，确保关键词结果（base 0.3）最高到 ~0.8，不会压过语义结果（max 1.0）
        double bonus = 0.0;
        if (nameLower.contains(keywordLower)) {
            bonus += 0.3;  // 类名/方法名精确命中是最强信号
        }
        if (fileLower.contains(keywordLower)) {
            bonus += 0.1;
        }
        if (contentLower.contains(keywordLower)) {
            bonus += 0.1;
        }

        return new VectorStore.SearchResult(
                result.filePath(),
                result.chunkType(),
                result.name(),
                result.content(),
                result.similarity() + bonus
        );
    }

    /**
     * 同一文件最多保留 maxPerFile 个结果，总数不超过 topK
     */
    private List<VectorStore.SearchResult> limitPerFile(List<VectorStore.SearchResult> sorted, int topK, int maxPerFile) {
        List<VectorStore.SearchResult> result = new ArrayList<>();
        Map<String, Integer> fileCount = new HashMap<>();
        for (VectorStore.SearchResult r : sorted) {
            int count = fileCount.getOrDefault(r.filePath(), 0);
            if (count < maxPerFile) {
                result.add(r);
                fileCount.put(r.filePath(), count + 1);
                if (result.size() >= topK) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 图谱检索：查询指定类/方法的关系图谱
     */
    public List<CodeRelation> getRelationGraph(String name) throws SQLException {
        return vectorStore.getRelations(name);
    }

    /**
     * 获取当前索引统计
     */
    public VectorStore.IndexStats getStats() throws SQLException {
        return vectorStore.getStats();
    }

    @Override
    public void close() throws Exception {
        vectorStore.close();
    }
}
