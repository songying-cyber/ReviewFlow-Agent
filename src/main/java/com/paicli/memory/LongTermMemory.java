package com.paicli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 长期记忆 - 跨对话持久化的关键信息
 *
 * 职责：
 * 1. 持久化用户偏好、项目事实、关键决策等
 * 2. 支持关键词检索
 * 3. 自动去重（基于内容相似度）
 * 4. 定期持久化到磁盘
 */
public class LongTermMemory implements Memory {
    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);
    private static final String STORAGE_DIR_PROPERTY = "paicli.memory.dir";
    private static final String STORAGE_DIR_ENV = "PAICLI_MEMORY_DIR";
    private static final String STORAGE_FILE = "long_term_memory.json";
    private final Map<String, MemoryEntry> entries;
    private final AtomicInteger tokenCounter;
    private final ObjectMapper mapper;
    private final File storageFile;
    private final File topicDir;
    private final File memoryIndexFile;

    public LongTermMemory() {
        this(resolveStorageDir());
    }

    public LongTermMemory(File storageDir) {
        this.entries = new ConcurrentHashMap<>();
        this.tokenCounter = new AtomicInteger(0);
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);

        // 确保存储目录存在
        File dir = storageDir;
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.storageFile = new File(dir, STORAGE_FILE);
        this.topicDir = new File(dir, "topics");
        this.memoryIndexFile = new File(dir, "MEMORY.md");

        // 启动时加载已有记忆
        loadFromDisk();
        loadTopicsFromDisk();
        syncTopicFiles();
    }

    @Override
    public void store(MemoryEntry entry) {
        // 去重检查：如果已存在内容完全相同的条目，跳过
        boolean duplicate = entries.values().stream()
                .anyMatch(e -> e.getContent().equals(entry.getContent()));
        if (duplicate) {
            return;
        }

        entries.put(entry.getId(), entry);
        tokenCounter.addAndGet(entry.getTokenCount());
        saveToDisk();
        writeTopicFile(entry);
        writeMemoryIndex();
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        return search(query, limit, null);
    }

    public List<MemoryEntry> search(String query, int limit, String projectKey) {
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);

        return entries.values().stream()
                .filter(entry -> isVisibleInProject(entry, projectKey))
                .filter(entry -> {
                    if (MemoryQueryTokenizer.matches(entry.getContent(), queryTokens)) {
                        return true;
                    }
                    return entry.getMetadata().values().stream()
                            .anyMatch(value -> MemoryQueryTokenizer.matches(value, queryTokens));
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    public List<MemoryEntry> getAll(String projectKey) {
        return entries.values().stream()
                .filter(entry -> isVisibleInProject(entry, projectKey))
                .collect(Collectors.toList());
    }

    @Override
    public boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            tokenCounter.addAndGet(-removed.getTokenCount());
            saveToDisk();
            deleteTopicFile(removed);
            writeMemoryIndex();
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        entries.clear();
        tokenCounter.set(0);
        saveToDisk();
        deleteTopicFiles();
        writeMemoryIndex();
    }

    @Override
    public int getTokenCount() {
        return tokenCounter.get();
    }

    @Override
    public int size() {
        return entries.size();
    }

    /**
     * 按类型筛选记忆
     */
    public List<MemoryEntry> getByType(MemoryEntry.MemoryType type) {
        return entries.values().stream()
                .filter(entry -> entry.getType() == type)
                .collect(Collectors.toList());
    }

    public static boolean isVisibleInProject(MemoryEntry entry, String projectKey) {
        String scope = scopeOf(entry);
        if ("global".equals(scope)) {
            return true;
        }
        String entryProject = entry.getMetadata().get("project");
        return projectKey != null && !projectKey.isBlank() && Objects.equals(entryProject, projectKey);
    }

    public static String scopeOf(MemoryEntry entry) {
        String scope = entry.getMetadata().get("scope");
        if ("project".equalsIgnoreCase(scope)) {
            return "project";
        }
        return "global";
    }

    /**
     * 持久化到磁盘
     */
    private void saveToDisk() {
        try {
            List<Map<String, Object>> dataList = entries.values().stream()
                    .map(this::entryToMap)
                    .collect(Collectors.toList());
            mapper.writeValue(storageFile, dataList);
        } catch (IOException e) {
            log.warn("长期记忆持久化失败: {}", e.getMessage(), e);
        }
    }

    private void syncTopicFiles() {
        if (entries.isEmpty()) {
            writeMemoryIndex();
            return;
        }
        for (MemoryEntry entry : entries.values()) {
            if (entry.getMetadata().containsKey("memoryFile")) {
                continue;
            }
            writeTopicFile(entry);
        }
        writeMemoryIndex();
    }

    private static File resolveStorageDir() {
        String configuredDir = System.getProperty(STORAGE_DIR_PROPERTY);
        if (configuredDir == null || configuredDir.isBlank()) {
            configuredDir = System.getenv(STORAGE_DIR_ENV);
        }
        if (configuredDir != null && !configuredDir.isBlank()) {
            return new File(configuredDir);
        }
        return new File(new File(System.getProperty("user.home"), ".paicli"), "memory");
    }

    /**
     * 从磁盘加载
     */
    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (!storageFile.exists()) return;

        try {
            List<Map<String, Object>> dataList = mapper.readValue(storageFile, List.class);
            for (Map<String, Object> data : dataList) {
                MemoryEntry entry = mapToEntry(data);
                if (entry != null) {
                    entries.put(entry.getId(), entry);
                    tokenCounter.addAndGet(entry.getTokenCount());
                }
            }
            log.info("加载了 {} 条长期记忆", entries.size());
        } catch (IOException e) {
            log.warn("加载长期记忆失败: {}", e.getMessage(), e);
        }
    }

    private void loadTopicsFromDisk() {
        if (!topicDir.isDirectory()) {
            return;
        }
        File[] files = topicDir.listFiles((dir, name) -> name.endsWith(".md"));
        if (files == null || files.length == 0) {
            return;
        }
        for (File file : files) {
            MemoryEntry entry = readTopicFile(file.toPath());
            if (entry == null || entries.containsKey(entry.getId())) {
                continue;
            }
            entries.put(entry.getId(), entry);
            tokenCounter.addAndGet(entry.getTokenCount());
        }
    }

    private Map<String, Object> entryToMap(MemoryEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.getId());
        map.put("content", entry.getContent());
        map.put("type", entry.getType().name());
        map.put("timestamp", entry.getTimestamp().toString());
        map.put("metadata", entry.getMetadata());
        map.put("tokenCount", entry.getTokenCount());
        return map;
    }

    @SuppressWarnings("unchecked")
    private MemoryEntry mapToEntry(Map<String, Object> map) {
        try {
            String id = (String) map.get("id");
            String content = (String) map.get("content");
            MemoryEntry.MemoryType type = MemoryEntry.MemoryType.valueOf((String) map.get("type"));
            Instant timestamp = null;
            Object timestampObj = map.get("timestamp");
            if (timestampObj instanceof String timestampValue && !timestampValue.isBlank()) {
                timestamp = Instant.parse(timestampValue);
            }
            Map<String, String> metadata = new HashMap<>();
            Object metaObj = map.get("metadata");
            if (metaObj instanceof Map) {
                ((Map<String, Object>) metaObj).forEach((k, v) -> metadata.put(k, String.valueOf(v)));
            }
            int tokenCount = map.get("tokenCount") instanceof Number n ? n.intValue() : MemoryEntry.estimateTokens(content);
            return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeTopicFile(MemoryEntry entry) {
        try {
            Files.createDirectories(topicDir.toPath());
            Path path = topicPath(entry);
            Files.writeString(path, topicMarkdown(entry, path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("长期记忆 topic 文件写入失败: {}", e.getMessage(), e);
        }
    }

    private void deleteTopicFile(MemoryEntry entry) {
        try {
            Files.deleteIfExists(topicPath(entry));
        } catch (IOException e) {
            log.warn("长期记忆 topic 文件删除失败: {}", e.getMessage(), e);
        }
    }

    private void deleteTopicFiles() {
        if (!topicDir.isDirectory()) {
            return;
        }
        File[] files = topicDir.listFiles((dir, name) -> name.endsWith(".md"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try {
                Files.deleteIfExists(file.toPath());
            } catch (IOException e) {
                log.warn("长期记忆 topic 文件删除失败: {}", e.getMessage(), e);
            }
        }
    }

    private void writeMemoryIndex() {
        try {
            Files.createDirectories(memoryIndexFile.toPath().getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("# MEMORY.md\n\n");
            sb.append("PaiCLI 长期记忆索引。详细内容在 `topics/*.md`；JSON 兼容文件仍保留。\n\n");
            entries.values().stream()
                    .sorted(Comparator.comparing(MemoryEntry::getTimestamp).reversed())
                    .forEach(entry -> sb.append("- [")
                            .append(topicName(entry))
                            .append("](")
                            .append("topics/").append(safeTopicFileName(entry.getId())).append(".md")
                            .append(") — ")
                            .append(indexDescription(entry))
                            .append(" [").append(scopeOf(entry)).append("]")
                            .append("\n"));
            Files.writeString(memoryIndexFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("长期记忆 MEMORY.md 写入失败: {}", e.getMessage(), e);
        }
    }

    private MemoryEntry readTopicFile(Path path) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            TopicDocument doc = parseTopicDocument(text);
            if (doc.id == null || doc.id.isBlank() || doc.content.isBlank()) {
                return null;
            }
            MemoryEntry.MemoryType type = parseMemoryType(doc.frontmatter.get("type"));
            Instant timestamp = parseInstant(doc.frontmatter.get("modified"));
            Map<String, String> metadata = new LinkedHashMap<>();
            doc.frontmatter.forEach((key, value) -> {
                if (!Set.of("id", "type", "modified").contains(key)) {
                    metadata.put(key, value);
                }
            });
            metadata.put("memoryFile", path.toAbsolutePath().normalize().toString());
            int tokenCount = MemoryEntry.estimateTokens(doc.content);
            return new MemoryEntry(doc.id, doc.content, type, timestamp, metadata, tokenCount);
        } catch (IOException e) {
            log.warn("长期记忆 topic 文件读取失败: {}", path, e);
            return null;
        }
    }

    private Path topicPath(MemoryEntry entry) {
        String file = entry.getMetadata().get("memoryFile");
        if (file != null && !file.isBlank()) {
            try {
                Path candidate = Path.of(file).toAbsolutePath().normalize();
                if (candidate.startsWith(topicDir.toPath().toAbsolutePath().normalize())) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // Fall through to generated path.
            }
        }
        return topicDir.toPath().resolve(safeTopicFileName(entry.getId()) + ".md");
    }

    private static String topicMarkdown(MemoryEntry entry, Path path) {
        Map<String, String> metadata = entry.getMetadata();
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("id: ").append(entry.getId()).append("\n");
        sb.append("name: ").append(escapeFrontmatter(topicName(entry))).append("\n");
        sb.append("description: ").append(escapeFrontmatter(indexDescription(entry))).append("\n");
        sb.append("type: ").append(entry.getType().name()).append("\n");
        sb.append("scope: ").append(scopeOf(entry)).append("\n");
        if (metadata.containsKey("project")) {
            sb.append("project: ").append(escapeFrontmatter(metadata.get("project"))).append("\n");
        }
        sb.append("modified: ").append(entry.getTimestamp()).append("\n");
        sb.append("memoryFile: ").append(escapeFrontmatter(path.toAbsolutePath().normalize().toString())).append("\n");
        sb.append("---\n\n");
        sb.append(entry.getContent()).append("\n");
        return sb.toString();
    }

    private static TopicDocument parseTopicDocument(String text) {
        Map<String, String> frontmatter = new LinkedHashMap<>();
        String body = text == null ? "" : text;
        if (body.startsWith("---\n")) {
            int end = body.indexOf("\n---", 4);
            if (end > 0) {
                String header = body.substring(4, end);
                for (String line : header.split("\n")) {
                    int idx = line.indexOf(':');
                    if (idx <= 0) {
                        continue;
                    }
                    frontmatter.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                }
                body = body.substring(Math.min(body.length(), end + 4)).stripLeading();
            }
        }
        return new TopicDocument(frontmatter.get("id"), frontmatter, body.strip());
    }

    private static MemoryEntry.MemoryType parseMemoryType(String value) {
        if (value == null || value.isBlank()) {
            return MemoryEntry.MemoryType.FACT;
        }
        try {
            return MemoryEntry.MemoryType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MemoryEntry.MemoryType.FACT;
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(value.trim());
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private static String topicName(MemoryEntry entry) {
        String name = entry.getMetadata().get("name");
        if (name != null && !name.isBlank()) {
            return name;
        }
        String content = entry.getContent() == null ? "" : entry.getContent().strip();
        return content.length() > 28 ? content.substring(0, 28) + "..." : content;
    }

    private static String indexDescription(MemoryEntry entry) {
        String description = entry.getMetadata().get("description");
        if (description != null && !description.isBlank()) {
            return description;
        }
        String content = entry.getContent() == null ? "" : entry.getContent().replaceAll("\\s+", " ").strip();
        return content.length() > 80 ? content.substring(0, 80) + "..." : content;
    }

    private static String safeTopicFileName(String id) {
        String normalized = id == null ? "memory" : id.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9._-]+", "-");
        return normalized.isBlank() ? "memory" : normalized;
    }

    private static String escapeFrontmatter(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').strip();
    }

    public File getMemoryIndexFile() {
        return memoryIndexFile;
    }

    public File getTopicDir() {
        return topicDir;
    }

    private record TopicDocument(String id, Map<String, String> frontmatter, String content) {
    }

    /**
     * 生成记忆状态摘要
     */
    public String getStatusSummary() {
        Map<MemoryEntry.MemoryType, Long> typeCounts = entries.values().stream()
                .collect(Collectors.groupingBy(MemoryEntry::getType, Collectors.counting()));

        return String.format("长期记忆: %d条 / %d tokens (事实: %d, 摘要: %d, 工具结果: %d)",
                entries.size(), tokenCounter.get(),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.FACT, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.SUMMARY, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.TOOL_RESULT, 0L));
    }
}
