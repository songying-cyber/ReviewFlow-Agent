package com.paicli.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码索引管理器：负责将代码库分块、向量化并持久化到 VectorStore
 */
public class CodeIndex {
    private static final Logger log = LoggerFactory.getLogger(CodeIndex.class);
    private final EmbeddingClient embeddingClient;
    private final CodeChunker chunker;
    private final CodeAnalyzer analyzer;
    private final ProgressListener progressListener;

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String message);

        static ProgressListener noop() {
            return message -> {
            };
        }
    }

    public CodeIndex() {
        this(new EmbeddingClient(), ProgressListener.noop());
    }

    public CodeIndex(EmbeddingClient embeddingClient) {
        this(embeddingClient, ProgressListener.noop());
    }

    public CodeIndex(ProgressListener progressListener) {
        this(new EmbeddingClient(), progressListener);
    }

    public CodeIndex(EmbeddingClient embeddingClient, ProgressListener progressListener) {
        this.embeddingClient = embeddingClient;
        this.chunker = new CodeChunker();
        this.analyzer = new CodeAnalyzer();
        this.progressListener = progressListener == null ? ProgressListener.noop() : progressListener;
    }

    /**
     * 索引指定路径的代码库
     *
     * @param projectPath 项目根目录
     * @return 索引统计信息
     */
    public IndexResult index(String projectPath) {
        Path root = Paths.get(projectPath).toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            String message = "路径不存在: " + projectPath;
            emit("❌ " + message);
            return new IndexResult(0, 0, message);
        }

        emit("🔍 开始索引: " + root);

        List<Path> filesToIndex = new ArrayList<>();
        collectFiles(root, filesToIndex);
        emit("📁 发现 " + filesToIndex.size() + " 个文件待索引");

        List<VectorStore.CodeChunkEntry> entries = new ArrayList<>();
        List<CodeRelation> allRelations = new ArrayList<>();

        int processed = 0;
        int total = filesToIndex.size();

        for (Path file : filesToIndex) {
            processed++;
            if (processed % 10 == 0 || processed == total) {
                emit(String.format("   进度: %d/%d (%s)", processed, total, file.getFileName()));
            }

            try {
                // 1. 分块
                List<CodeChunk> chunks = chunker.chunkFile(file);

                // 2. 生成 Embedding 并组装条目
                for (CodeChunk chunk : chunks) {
                    float[] embedding = embeddingClient.embed(chunk.toEmbeddingText());
                    entries.add(new VectorStore.CodeChunkEntry(chunk, embedding));
                }

                // 3. 分析关系（仅 Java 文件）
                if (file.toString().endsWith(".java")) {
                    allRelations.addAll(analyzer.analyzeFile(file));
                }
            } catch (Exception e) {
                String message = "   ⚠️ 索引失败: " + file + " - " + e.getMessage();
                emit(message);
                log.warn("code index failed for file {}", file, e);
            }
        }

        // 4. 持久化到 SQLite
        try (VectorStore store = new VectorStore(root.toString())) {
            store.clearProject();
            store.insertChunks(entries);
            store.insertRelations(allRelations);

            VectorStore.IndexStats stats = store.getStats();
            String msg = String.format("索引完成：%d 个代码块，%d 条关系", stats.chunkCount(), stats.relationCount());
            emit("✅ " + msg);
            return new IndexResult(stats.chunkCount(), stats.relationCount(), msg);
        } catch (Exception e) {
            String error = "持久化失败: " + e.getMessage();
            emit("❌ " + error);
            log.warn("code index persistence failed for root {}", root, e);
            return new IndexResult(0, 0, error);
        }
    }

    private void emit(String message) {
        progressListener.onProgress(message);
    }

    /**
     * 收集需要索引的文件（排除常见非代码目录）
     */
    private void collectFiles(Path root, List<Path> files) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    // 跳过常见非代码目录
                    if (dirName.equals("node_modules") || dirName.equals("target")
                            || dirName.equals("build") || dirName.equals(".git")
                            || dirName.equals(".idea") || dirName.equals(".vscode")
                            || dirName.equals("dist") || dirName.equals("out")
                            || dirName.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    // 只索引文本代码文件
                    if (name.endsWith(".java") || name.endsWith(".py")
                            || name.endsWith(".js") || name.endsWith(".ts")
                            || name.endsWith(".go") || name.endsWith(".rs")
                            || name.endsWith(".c") || name.endsWith(".cpp")
                            || name.endsWith(".h") || name.endsWith(".md")
                            || name.endsWith(".xml") || name.endsWith(".properties")
                            || name.endsWith(".yaml") || name.endsWith(".yml")
                            || name.endsWith(".json") || name.endsWith(".sh")
                            || name.endsWith(".gradle") || name.endsWith(".kt")) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            String message = "遍历文件失败: " + e.getMessage();
            emit("❌ " + message);
            log.warn("code index file traversal failed for root {}", root, e);
        }
    }

    public record IndexResult(int chunkCount, int relationCount, String message) {}
}
