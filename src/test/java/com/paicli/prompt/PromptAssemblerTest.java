package com.paicli.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptAssemblerTest {

    @TempDir
    Path tempDir;

    @Test
    void assemblesBuiltinPromptWithDynamicSections() {
        PromptAssembler assembler = PromptAssembler.createDefault();

        String prompt = assembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .memoryContext("## 相关记忆\n用户偏好中文。")
                .externalContext("## MCP Resources\n- demo://resource")
                .skillIndex("## 可用 Skills\n- web-access")
                .build());

        assertTrue(prompt.contains("## Language"));
        assertTrue(prompt.contains("## Mode: ReAct Agent"));
        assertTrue(prompt.contains("用户偏好中文"));
        assertTrue(prompt.contains("demo://resource"));
        assertTrue(prompt.contains("web-access"));
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
