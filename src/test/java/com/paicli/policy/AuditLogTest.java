package com.paicli.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogTest {

    @Test
    void writesEntryAsJsonLineToTodayFile(@TempDir Path tempDir) throws Exception {
        AuditLog log = new AuditLog(tempDir);
        log.record(AuditLog.AuditEntry.allow("write_file", "{\"path\":\"a.txt\"}", 12));

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Path file = tempDir.resolve("audit-" + today + ".jsonl");
        assertTrue(Files.exists(file));

        List<String> lines = Files.readAllLines(file);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"tool\":\"write_file\""));
        assertTrue(lines.get(0).contains("\"outcome\":\"allow\""));
    }

    @Test
    void recordsAllOutcomeKinds(@TempDir Path tempDir) {
        AuditLog log = new AuditLog(tempDir);
        log.record(AuditLog.AuditEntry.allow("write_file", "{}", 1));
        log.record(AuditLog.AuditEntry.denyByHitl("execute_command", "{}", "user reject", 2));
        log.record(AuditLog.AuditEntry.denyByPolicy("execute_command", "{}", "rm -rf /", 3));
        log.record(AuditLog.AuditEntry.error("write_file", "{}", "io fail", 4));

        List<AuditLog.AuditEntry> recent = log.readRecent(10);
        assertEquals(4, recent.size());
        assertEquals("allow", recent.get(0).outcome());
        assertEquals("hitl", recent.get(1).approver());
        assertEquals("policy", recent.get(2).approver());
        assertEquals("error", recent.get(3).outcome());
    }

    @Test
    void readRecentReturnsLastN(@TempDir Path tempDir) {
        AuditLog log = new AuditLog(tempDir);
        for (int i = 0; i < 10; i++) {
            log.record(AuditLog.AuditEntry.allow("write_file", "{\"i\":" + i + "}", i));
        }

        List<AuditLog.AuditEntry> recent = log.readRecent(3);
        assertEquals(3, recent.size());
        assertTrue(recent.get(0).args().contains("\"i\":7"));
        assertTrue(recent.get(2).args().contains("\"i\":9"));
    }

    @Test
    void readRecentReturnsEmptyWhenFileMissing(@TempDir Path tempDir) {
        AuditLog log = new AuditLog(tempDir);
        assertTrue(log.readRecent(5).isEmpty());
    }

    @Test
    void truncatesOversizedArgs(@TempDir Path tempDir) {
        AuditLog log = new AuditLog(tempDir);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append('x');

        log.record(AuditLog.AuditEntry.allow("write_file", big.toString(), 0));

        AuditLog.AuditEntry entry = log.readRecent(1).get(0);
        assertTrue(entry.args().endsWith("...(truncated)"));
        assertTrue(entry.args().length() < big.length());
    }

    @Test
    void concurrentWritesDoNotInterleave(@TempDir Path tempDir) throws Exception {
        AuditLog log = new AuditLog(tempDir);
        int threads = 10;
        int perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        log.record(AuditLog.AuditEntry.allow(
                                "write_file", "{\"t\":" + tid + ",\"i\":" + i + "}", 0));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        List<AuditLog.AuditEntry> all = log.readRecent(threads * perThread + 10);
        assertEquals(threads * perThread, all.size(), "并发写入不应丢条或损坏 JSON");
        for (AuditLog.AuditEntry entry : all) {
            assertEquals("write_file", entry.tool());
            assertNotNull(entry.args());
        }
    }

    @Test
    void ignoresNullEntry(@TempDir Path tempDir) {
        AuditLog log = new AuditLog(tempDir);
        log.record(null);
        assertTrue(log.readRecent(5).isEmpty());
    }
}
