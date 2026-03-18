package com.paicli.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalMarkdownRendererTest {
    static {
        System.setProperty("paicli.render.color", "false");
    }

    @Test
    void rendersHeadingListTableAndCodeBlockToTerminalFriendlyText() {
        String markdown = """
                # 规划思考
                                
                1. **分析请求**
                - 列出当前目录
                                
                | 名称 | 说明 |
                | --- | --- |
                | src | 源码 |
                | pom.xml | Maven 配置 |
                                
                ```java
                System.out.println("hello");
                ```
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown);

        assertTrue(rendered.contains("规划思考"));
        assertTrue(rendered.contains("1. 分析请求"));
        assertTrue(rendered.contains("- 列出当前目录"));
        assertTrue(rendered.contains("| 名称"));
        assertTrue(rendered.contains("| src"));
        assertTrue(rendered.contains("源码"));
        assertTrue(rendered.contains("┌─ code: java"));
        assertTrue(rendered.contains("└─ end"));
        assertTrue(rendered.contains("    System.out.println(\"hello\");"));
    }

    @Test
    void supportsIncrementalStreamingAppend() {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.io.PrintStream stream = new java.io.PrintStream(output);
        TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(stream);

        renderer.append("## 标题\n- 第一");
        renderer.append("项\n- 第二项\n");
        renderer.finish();

        String rendered = output.toString();
        assertTrue(rendered.contains("标题"));
        assertTrue(rendered.contains("- 第一项"));
        assertTrue(rendered.contains("- 第二项"));
    }

    @Test
    void preservesNestedListIndentation() {
        String markdown = """
                1. 总体分析
                  - 第一层补充
                    - 第二层补充
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown);

        assertTrue(rendered.contains("1. 总体分析"));
        assertTrue(rendered.contains("  - 第一层补充"));
        assertTrue(rendered.contains("    - 第二层补充"));
    }

    @Test
    void fallsBackToKeyValueLayoutForLongTwoColumnTable() {
        String markdown = """
                | 目录名 | 说明 |
                | --- | --- |
                | src/main/java/com/paicli | 这里存放 PaiCLI 的主要 Java 源码实现与相关模块 |
                """;

        String rendered = TerminalMarkdownRenderer.render(markdown);

        assertTrue(rendered.contains("目录名 / 说明"));
        assertTrue(rendered.contains("- src/main/java/com/paicli"));
        assertTrue(rendered.contains("这里存放 PaiCLI 的主要 Java 源码实现与相关模块"));
    }
}
