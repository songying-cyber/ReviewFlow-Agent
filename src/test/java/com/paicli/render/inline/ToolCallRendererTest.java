package com.paicli.render.inline;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallRendererTest {

    @Test
    void singleGroupCollapsedHeaderUsesToolLabel() {
        var grouped = ToolCallRenderer.group(List.of(
                tc("read_file", "{\"path\":\"a.md\"}"),
                tc("read_file", "{\"path\":\"b.md\"}")));
        String header = ToolCallRenderer.collapsedHeader(grouped);
        assertTrue(header.contains("⏵"), header);
        assertTrue(header.contains("读取 2 个文件"), header);
        assertTrue(header.contains("ctrl+o"), header);
    }

    @Test
    void multipleGroupsCollapsedShowsTotalCount() {
        var grouped = ToolCallRenderer.group(List.of(
                tc("read_file", "{}"),
                tc("write_file", "{}")));
        String header = ToolCallRenderer.collapsedHeader(grouped);
        assertTrue(header.contains("2 组工具调用"), header);
        assertTrue(header.contains("2 次"), header);
    }

    @Test
    void expandedLinesIncludeToolLabelAndPaths() {
        var grouped = ToolCallRenderer.group(List.of(
                tc("read_file", "{\"path\":\"README.md\"}")));
        List<String> lines = ToolCallRenderer.expandedLines(grouped);
        assertTrue(lines.stream().anyMatch(l -> l.contains("📖 读取 1 个文件")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("README.md")), lines.toString());
    }

    @Test
    void rendererCreatesAndRegistersFoldableBlock() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BlockRegistry registry = new BlockRegistry();
        ToolCallRenderer r = new ToolCallRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8), registry);
        r.render(List.of(tc("read_file", "{\"path\":\"a.md\"}")));
        assertEquals(1, registry.size());
        FoldableBlock b = registry.peekLast();
        assertFalse(b.isExpanded());
        String emitted = sink.toString(StandardCharsets.UTF_8);
        assertTrue(emitted.contains("⏵"), emitted);
    }

    @Test
    void emptyToolCallsListIsNoOp() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BlockRegistry registry = new BlockRegistry();
        ToolCallRenderer r = new ToolCallRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8), registry);
        r.render(List.of());
        assertEquals(0, registry.size());
        assertEquals("", sink.toString(StandardCharsets.UTF_8));
    }

    @Test
    void toolLabelRendersBuiltinTools() {
        assertEquals("📖 读取 1 个文件", ToolCallRenderer.toolLabel("read_file", 1));
        assertEquals("✏️ 写入 2 个文件", ToolCallRenderer.toolLabel("write_file", 2));
        assertEquals("⚡ 执行 1 条命令", ToolCallRenderer.toolLabel("execute_command", 1));
    }

    @Test
    void toolLabelRendersMcpTool() {
        assertEquals("🔌 调用 MCP 工具 chrome-devtools.click",
                ToolCallRenderer.toolLabel("mcp__chrome-devtools__click", 1));
    }

    @Test
    void extractKeyParamPullsOutPath() {
        assertEquals("README.md",
                ToolCallRenderer.extractKeyParam("read_file", "{\"path\":\"README.md\"}"));
    }

    @Test
    void extractKeyParamReturnsEmptyForNullArgs() {
        assertEquals("", ToolCallRenderer.extractKeyParam("read_file", null));
    }

    private static LlmClient.ToolCall tc(String name, String args) {
        return new LlmClient.ToolCall(name + "-id", new LlmClient.ToolCall.Function(name, args));
    }
}
