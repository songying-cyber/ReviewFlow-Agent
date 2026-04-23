package com.paicli.mcp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigLoaderTest {

    @Test
    void projectConfigOverridesUserConfig(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Path project = tempDir.resolve("project-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"git":{"command":"uvx","args":["old"]}}}
                """);
        Files.writeString(project, """
                {"mcpServers":{"git":{"command":"uvx","args":["mcp-server-git","--repository","${PROJECT_DIR}"]}}}
                """);

        McpConfigLoader loader = new McpConfigLoader(user, project, tempDir);
        Map<String, McpServerConfig> configs = loader.load();

        assertEquals(1, configs.size());
        // 项目级配置覆盖用户级
        assertEquals("mcp-server-git", configs.get("git").getArgs().get(0));
        // load 不展开 ${VAR}：展开延迟到 prepare()，单 server 错误才能被隔离
        assertEquals("${PROJECT_DIR}", configs.get("git").getArgs().get(2));
    }

    @Test
    void prepareExpandsProjectDirAndHomeBuiltins(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"fs":{"command":"npx","args":["-y","server","${PROJECT_DIR}","${HOME}"]}}}
                """);

        McpConfigLoader loader = new McpConfigLoader(user, tempDir.resolve("missing.json"), tempDir);
        Map<String, McpServerConfig> configs = loader.load();
        McpServerConfig fs = configs.get("fs");
        loader.prepare(fs);

        assertEquals(tempDir.toAbsolutePath().normalize().toString(), fs.getArgs().get(2));
        assertEquals(System.getProperty("user.home"), fs.getArgs().get(3));
    }

    @Test
    void prepareRejectsMissingEnvironmentVariable(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"remote":{"url":"https://example.com","headers":{"Authorization":"Bearer ${NO_SUCH_PAICLI_TOKEN}"}}}}
                """);

        McpConfigLoader loader = new McpConfigLoader(user, tempDir.resolve("missing.json"), tempDir);
        Map<String, McpServerConfig> configs = loader.load();

        // load 不报错（推迟到 prepare）；prepare 时缺失变量才抛
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> loader.prepare(configs.get("remote")));
        assertTrue(ex.getMessage().contains("NO_SUCH_PAICLI_TOKEN"));
    }

    @Test
    void prepareRejectsConfigMissingBothCommandAndUrl(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user-mcp.json");
        Files.writeString(user, """
                {"mcpServers":{"broken":{}}}
                """);

        McpConfigLoader loader = new McpConfigLoader(user, tempDir.resolve("missing.json"), tempDir);
        Map<String, McpServerConfig> configs = loader.load();

        assertThrows(IllegalArgumentException.class, () -> loader.prepare(configs.get("broken")));
    }
}
