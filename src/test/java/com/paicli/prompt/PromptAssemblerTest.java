package com.paicli.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptAssemblerTest {

    @TempDir
    Path tempDir;

    @Test
    void assemblesBuiltinPromptWithDynamicSections() {
        PromptAssembler assembler = PromptAssembler.createDefault();

        String prompt = assembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .projectMemoryContext("## PAI.md 项目记忆\n- 项目规则")
                .memoryContext("## 相关记忆\n用户偏好中文。")
                .externalContext("## MCP Resources\n- demo://resource")
                .skillIndex("## 可用 Skills\n- web-access")
                .build());

        assertTrue(prompt.contains("## Language"));
        assertTrue(prompt.contains("## Runtime Context"));
        assertTrue(prompt.contains("当前日期"));
        assertFalse(prompt.contains("## Freshness Policy（强制规则）"));
        assertFalse(prompt.contains("禁止**直接基于训练知识回答"));
        assertTrue(prompt.contains("## Mode: ReAct Agent"));
        assertTrue(prompt.contains("项目规则"));
        assertTrue(prompt.contains("用户偏好中文"));
        assertTrue(prompt.contains("demo://resource"));
        assertTrue(prompt.contains("web-access"));
        assertTrue(prompt.indexOf("项目规则") < prompt.indexOf("用户偏好中文"));
    }

    @Test
    void projectOverrideReplacesBuiltinModePrompt() throws Exception {
        Path projectPrompts = tempDir.resolve("project");
        Files.createDirectories(projectPrompts.resolve("modes"));
        Files.writeString(projectPrompts.resolve("modes/agent.md"), "## Mode: Override\n\n项目覆盖 prompt");

        PromptAssembler assembler = new PromptAssembler(new PromptRepository(
                tempDir.resolve("user"),
                projectPrompts
        ));

        String prompt = assembler.assemble(PromptMode.AGENT, PromptContext.empty());

        assertTrue(prompt.contains("项目覆盖 prompt"));
        assertTrue(prompt.contains("## Language"));
    }

    @Test
    void omitsToolInstructionsWhenToolsAreDisabled() {
        PromptAssembler assembler = PromptAssembler.createDefault();

        String prompt = assembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .toolsEnabled(false)
                .build());

        assertTrue(prompt.contains("## Language"));
        assertTrue(prompt.contains("## Tool Availability"));
        assertTrue(prompt.contains("当前模型不支持 PaiCLI 原生工具调用"));
        assertTrue(prompt.contains("绝对不要输出伪造的工具标签"));
        assertTrue(prompt.contains("<toolcall>"));
        assertFalse(prompt.contains("## Tools"));
        assertFalse(prompt.contains("## Tool Policy"));
        assertFalse(prompt.contains("`read_file` - 读取文件内容"));
        assertFalse(prompt.contains("当需要操作文件、执行命令或创建项目时，请使用工具调用"));
    }

    @Test
    void baseOverrideMustKeepLanguageSection() throws Exception {
        Path projectPrompts = tempDir.resolve("project");
        Files.createDirectories(projectPrompts);
        Files.writeString(projectPrompts.resolve("base.md"), "## Identity\n\nmissing language");

        PromptAssembler assembler = new PromptAssembler(new PromptRepository(
                tempDir.resolve("user"),
                projectPrompts
        ));

        assertThrows(IllegalStateException.class,
                () -> assembler.assemble(PromptMode.AGENT, PromptContext.empty()));
    }
}
