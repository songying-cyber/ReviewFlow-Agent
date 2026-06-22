package com.paicli.tool;

import com.paicli.browser.BrowserConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    @Test
    void shouldRunCommandInProjectDirectory(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("execute_command", "{\"command\":\"pwd\"}");

        assertTrue(result.contains(tempDir.toString()));
    }

    @Test
    void shouldRejectBroadFilesystemScan() {
        ToolRegistry registry = new ToolRegistry();

        String result = registry.executeTool("execute_command", "{\"command\":\"find / -name \\\"pom.xml\\\" -type f | head -20\"}");

        assertTrue(result.contains("策略拒绝"));
    }

    @Test
    void shouldReadRequestedLineRange(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, String.join("\n",
                "class Sample {",
                "  void first() {}",
                "  void second() {}",
                "}"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("read_file", "{\"path\":\"Sample.java\",\"offset\":2,\"limit\":2}");

        assertTrue(result.contains("lines 2-3 of 4"));
        assertTrue(result.contains("2 |   void first() {}"));
        assertTrue(result.contains("3 |   void second() {}"));
        assertTrue(!result.contains("class Sample {"));
    }

    @Test
    void shouldGlobFilesInsideProject(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/UserService.java"), "class UserService {}\n");
        Files.writeString(tempDir.resolve("README.md"), "# demo\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("glob_files", "{\"pattern\":\"**/*Service.java\"}");

        assertTrue(result.contains("src/main/java/com/example/UserService.java"));
        assertTrue(!result.contains("README.md"));
    }

    @Test
    void shouldGlobRootFileByName(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# demo\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("glob_files", "{\"pattern\":\"README.md\"}");

        assertTrue(result.contains("README.md"));
    }

    @Test
    void shouldGrepCodeWithLineNumbersAndContext(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/UserService.java"), String.join("\n",
                "class UserService {",
                "  User getUserById(String id) {",
                "    return repository.findById(id);",
                "  }",
                "}"));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("grep_code",
                "{\"pattern\":\"getUserById\",\"glob\":\"**/*.java\",\"context_lines\":1}");

        assertTrue(result.contains("src/main/java/com/example/UserService.java:2"));
        assertTrue(result.contains(">    2 |   User getUserById(String id) {"));
        assertTrue(result.contains("     3 |     return repository.findById(id);"));
    }

    @Test
    void shouldSkipCommonDependencyDirectoriesWhenGrepping(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve("node_modules/pkg"));
        Files.writeString(tempDir.resolve("src/App.java"), "class App { String marker = \"targetSymbol\"; }\n");
        Files.writeString(tempDir.resolve("node_modules/pkg/Generated.java"), "class Generated { String marker = \"targetSymbol\"; }\n");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("grep_code", "{\"pattern\":\"targetSymbol\",\"max_results\":10}");

        assertTrue(result.contains("src/App.java:1"));
        assertTrue(!result.contains("node_modules"));
    }

    @Test
    void shouldTimeoutLongRunningCommandWithoutHanging(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry(1);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("execute_command", "{\"command\":\"sleep 2\"}");

        assertTrue(result.contains("命令执行超时"));
    }

    @Test
    void shouldExecuteMultipleToolInvocationsInParallelAndKeepResultOrder() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                int now = current.incrementAndGet();
                peak.updateAndGet(prev -> Math.max(prev, now));
                bothStarted.countDown();
                try {
                    assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "两个工具调用应同时进入执行区");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    current.decrementAndGet();
                }
                return "result-" + name;
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "first", "{}"),
                new ToolRegistry.ToolInvocation("call_2", "second", "{}")
        ));

        assertEquals(2, peak.get(), "两个工具调用应并行执行");
        assertEquals("call_1", results.get(0).id());
        assertEquals("result-first", results.get(0).result());
        assertEquals("call_2", results.get(1).id());
        assertEquals("result-second", results.get(1).result());
    }

    @Test
    void shouldCancelToolInvocationWhenBatchTimeoutIsReached() {
        ToolRegistry registry = new ToolRegistry(1, 1) {
            @Override
            public String executeTool(String name, String argumentsJson) {
                if ("slow".equals(name)) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return "result-" + name;
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "slow", "{}"),
                new ToolRegistry.ToolInvocation("call_2", "fast", "{}")
        ));

        assertTrue(results.get(0).timedOut());
        assertTrue(results.get(0).result().contains("工具执行超时"));
        assertEquals("result-fast", results.get(1).result());
    }

    @Test
    void browserConnectToolUsesInjectedConnector() {
        ToolRegistry registry = new ToolRegistry();
        registry.setBrowserConnector(new BrowserConnector() {
            @Override
            public String status() {
                return "status-ok";
            }

            @Override
            public String connectDefault() {
                return "connected";
            }

            @Override
            public String disconnect() {
                return "disconnected";
            }
        });

        assertEquals("connected", registry.executeTool("browser_connect", "{}"));
        assertEquals("status-ok", registry.executeTool("browser_status", "{}"));
        assertEquals("disconnected", registry.executeTool("browser_disconnect", "{}"));
    }

    @Test
    void saveMemoryToolUsesInjectedMemorySaver() {
        ToolRegistry registry = new ToolRegistry();
        List<String> saved = new ArrayList<>();
        registry.setMemorySaver(saved::add);

        String result = registry.executeTool("save_memory", "{\"fact\":\"访问 yuque.com 时复用登录态\"}");

        assertEquals(List.of("访问 yuque.com 时复用登录态"), saved);
        assertTrue(result.contains("已保存到长期记忆"));
    }

    @Test
    void saveMemoryToolPassesScopeToScopedSaver() {
        ToolRegistry registry = new ToolRegistry();
        List<String> saved = new ArrayList<>();
        registry.setScopedMemorySaver((fact, scope) -> saved.add(scope + ":" + fact));

        String result = registry.executeTool("save_memory", "{\"fact\":\"默认用中文回答\",\"scope\":\"global\"}");

        assertEquals(List.of("global:默认用中文回答"), saved);
        assertTrue(result.contains("长期记忆(global)"));
    }
}
