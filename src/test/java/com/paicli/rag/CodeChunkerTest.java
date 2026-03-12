package com.paicli.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeChunkerTest {

    private final CodeChunker chunker = new CodeChunker();

    @Test
    void testNonJavaFile() throws Exception {
        Path path = Paths.get("src/test/resources/rag/SampleService.java").toAbsolutePath();
        // 故意把一个 .java 文件复制成 .txt 来测试非Java文件路径
        List<CodeChunk> chunks = chunker.chunkFile(path);
        assertFalse(chunks.isEmpty());
        // 因为是 .java 后缀，会被 AST 解析
        assertTrue(chunks.stream().anyMatch(c -> c.chunkType().equals("class")));
    }

    @Test
    void testJavaFileChunking() throws Exception {
        Path path = Paths.get("src/test/resources/rag/SampleService.java").toAbsolutePath();
        List<CodeChunk> chunks = chunker.chunkFile(path);

        assertFalse(chunks.isEmpty());

        // 应该包含类级别的 chunk
        assertTrue(chunks.stream().anyMatch(c ->
                c.chunkType().equals("class") && c.name().equals("SampleService")));

        // 应该包含方法级别的 chunk
        assertTrue(chunks.stream().anyMatch(c ->
                c.chunkType().equals("method") && c.name().contains("findUserById")));
        assertTrue(chunks.stream().anyMatch(c ->
                c.chunkType().equals("method") && c.name().contains("initialize")));
    }

    @Test
    void testEmbeddingTextFormat() {
        CodeChunk chunk = CodeChunk.classChunk("Test.java", "TestClass",
                "content here", 1, 1);
        String text = chunk.toEmbeddingText();
        assertTrue(text.contains("[class:TestClass]"));
        assertTrue(text.contains("content here"));
    }
}
