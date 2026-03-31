package com.paicli.agent;

import com.paicli.llm.GLMClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStreamRendererTest {

    @Test
    void shouldNotPrintEmptyReasoningHeadingBeforeTextIsFlushable() throws Exception {
        GLMClient.StreamListener renderer = newStreamRenderer();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));

            renderer.onReasoningDelta("继续查看 src 目录下的包结构");
            String afterDelta = output.toString(StandardCharsets.UTF_8);
            assertFalse(afterDelta.contains("思考过程"),
                    "没有完整行时不应先打印空的思考标题: " + afterDelta);

            invokeNoArg(renderer, "resetBetweenIterations");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("思考过程"));
        assertTrue(rendered.contains("继续查看 src 目录下的包结构"));
    }

    @Test
    void shouldIgnoreWhitespaceOnlyReasoningAcrossIterationReset() throws Exception {
        GLMClient.StreamListener renderer = newStreamRenderer();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            renderer.onReasoningDelta("  \n");
            invokeNoArg(renderer, "resetBetweenIterations");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertFalse(rendered.contains("思考过程"),
                "空白 reasoning 不应打印空的思考标题: " + rendered);
    }

    @Test
    void shouldPrintReasoningHeadingOnlyOnceAcrossToolIterations() throws Exception {
        GLMClient.StreamListener renderer = newStreamRenderer();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            renderer.onReasoningDelta("我先查找 ROADMAP.md。\n");
            invokeNoArg(renderer, "resetBetweenIterations");
            renderer.onReasoningDelta("已经读到内容，接下来总结给用户。\n");
            invokeNoArg(renderer, "finish");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("我先查找 ROADMAP.md。"));
        assertTrue(rendered.contains("已经读到内容，接下来总结给用户。"));
        assertTrue(rendered.contains("思考过程"));
        assertTrue(countOccurrences(rendered, "思考过程") == 1,
                "同一次 ReAct 运行中工具调用前后的 reasoning 应归到同一个思考区: " + rendered);
    }

    private GLMClient.StreamListener newStreamRenderer() throws Exception {
        Class<?> rendererClass = Class.forName("com.paicli.agent.Agent$StreamRenderer");
        Constructor<?> constructor = rendererClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (GLMClient.StreamListener) constructor.newInstance();
    }

    private void invokeNoArg(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
