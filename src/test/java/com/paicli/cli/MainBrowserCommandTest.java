package com.paicli.cli;

import com.paicli.browser.BrowserConnectivityCheck;
import com.paicli.browser.BrowserMode;
import com.paicli.browser.BrowserSession;
import com.paicli.hitl.HitlToolRegistry;
import com.paicli.hitl.TerminalHitlHandler;
import com.paicli.mcp.McpServerManager;
import com.paicli.mcp.config.McpConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MainBrowserCommandTest {

    @Test
    void browserStatusShowsCurrentMode(@TempDir Path tempDir) throws IOException {
        Harness h = new Harness(tempDir);

        String result = Main.handleBrowserCommand("status", h.session, h.connectivity, h.manager, h.registry, h.handler);

        assertTrue(result.contains("当前模式"));
        assertTrue(result.contains("isolated"));
    }

    @Test
    void browserConnectRejectsInvalidPort(@TempDir Path tempDir) throws IOException {
        Harness h = new Harness(tempDir);

        String result = Main.handleBrowserCommand("connect 80", h.session, h.connectivity, h.manager, h.registry, h.handler);

        assertTrue(result.contains("1024-65535"));
        assertEquals(BrowserMode.ISOLATED, h.session.mode());
    }

    @Test
    void browserDisconnectWithoutServerClearsSession(@TempDir Path tempDir) throws IOException {
        Harness h = new Harness(tempDir);
        h.session.switchToShared("http://127.0.0.1:9222");

        String result = Main.handleBrowserCommand("disconnect", h.session, h.connectivity, h.manager, h.registry, h.handler);

        assertTrue(result.contains("未配置"));
        assertEquals(BrowserMode.ISOLATED, h.session.mode());
    }

    @Test
    void browserTabsInIsolatedModeGivesConnectHint(@TempDir Path tempDir) throws IOException {
        Harness h = new Harness(tempDir);

        String result = Main.handleBrowserCommand("tabs", h.session, h.connectivity, h.manager, h.registry, h.handler);

        assertTrue(result.contains("isolated"));
        assertTrue(result.contains("/browser connect"));
    }

    @Test
    void unknownBrowserSubCommandShowsHelp(@TempDir Path tempDir) throws IOException {
        Harness h = new Harness(tempDir);

        String result = Main.handleBrowserCommand("wat", h.session, h.connectivity, h.manager, h.registry, h.handler);

        assertTrue(result.contains("未知 /browser 子命令"));
        assertTrue(result.contains("/browser connect"));
    }

    private static final class Harness {
        private final BrowserSession session = new BrowserSession();
        private final BrowserConnectivityCheck connectivity = new BrowserConnectivityCheck();
        private final TerminalHitlHandler handler = new TerminalHitlHandler(false);
        private final HitlToolRegistry registry = new HitlToolRegistry(handler);
        private final McpServerManager manager;

        private Harness(Path tempDir) throws IOException {
            manager = new McpServerManager(
                    registry,
                    tempDir,
                    new McpConfigLoader(tempDir.resolve("user.json"), tempDir.resolve("project.json"), tempDir));
            manager.loadConfiguredServers();
        }
    }
}
