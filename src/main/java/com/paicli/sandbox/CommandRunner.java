package com.paicli.sandbox;

import com.paicli.sandbox.mac.MacSeatbeltSandbox;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class CommandRunner {
    private final Path projectRoot;
    private final SandboxConfig config;
    private final long timeoutSeconds;
    private final int maxOutputChars;

    public CommandRunner(Path projectRoot, SandboxConfig config, long timeoutSeconds, int maxOutputChars) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.config = config == null ? new SandboxConfig() : config;
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputChars = maxOutputChars;
    }

    public CommandResult run(String command, SandboxDecision decision) {
        SandboxDecision effective = decision == null ? SandboxDecision.disabled() : decision;
        if (effective.denied()) {
            return new CommandResult("执行命令失败: " + effective.denyReason(),
                    metadata(effective, false, effective.unsandboxedReason()));
        }
        List<String> argv;
        SandboxAuditMetadata metadata;
        try {
            if (effective.useSandbox()) {
                MacSeatbeltSandbox sandbox = new MacSeatbeltSandbox(config, projectRoot);
                if (!sandbox.available()) {
                    if (config.isRequired()) {
                        return new CommandResult("执行命令失败: sandbox-exec 不可用，且 sandbox.required=true",
                                metadata(effective, false, "sandbox_unavailable"));
                    }
                    argv = List.of("/bin/bash", "-lc", command);
                    metadata = metadata(effective, false, "sandbox_unavailable");
                } else {
                    MacSeatbeltSandbox.WrappedCommand wrapped = sandbox.wrap(command);
                    argv = wrapped.argv();
                    metadata = wrapped.metadata();
                }
            } else {
                argv = List.of("/bin/bash", "-lc", command);
                metadata = metadata(effective, false, effective.unsandboxedReason());
            }
            return runProcess(argv, metadata);
        } catch (Exception e) {
            return new CommandResult("执行命令失败: " + e.getMessage(),
                    metadata(effective, effective.useSandbox(), effective.unsandboxedReason()));
        }
    }

    private CommandResult runProcess(List<String> argv, SandboxAuditMetadata metadata) {
        ExecutorService outputReaderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "paicli-command-output");
            thread.setDaemon(true);
            return thread;
        });

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(new File(projectRoot.toString()));
            pb.redirectErrorStream(true);
            process = pb.start();

            Process runningProcess = process;
            Future<String> outputFuture = outputReaderExecutor.submit(() -> readProcessOutput(runningProcess));

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                outputFuture.cancel(true);
                return new CommandResult("命令执行超时（" + timeoutSeconds + "秒），已强制终止", metadata);
            }

            String output = getCommandOutput(outputFuture);
            int exitCode = process.exitValue();
            return new CommandResult(String.format("命令执行完成 (exit code: %d)%n%s", exitCode, output), metadata);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new CommandResult("用户取消了此次工具调用", metadata);
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return new CommandResult("执行命令失败: " + e.getMessage(), metadata);
        } finally {
            outputReaderExecutor.shutdownNow();
        }
    }

    private String readProcessOutput(Process process) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < maxOutputChars) {
                    int remaining = maxOutputChars - output.length();
                    if (line.length() > remaining) {
                        output.append(line, 0, remaining);
                    } else {
                        output.append(line);
                    }
                    output.append("\n");
                }
            }
        }
        if (output.length() >= maxOutputChars) {
            return output.substring(0, maxOutputChars) + "\n...(输出已截断)";
        }
        return output.toString();
    }

    private String getCommandOutput(Future<String> outputFuture) throws Exception {
        try {
            return outputFuture.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            outputFuture.cancel(true);
            return "(命令已结束，但输出读取超时)";
        }
    }

    private SandboxAuditMetadata metadata(SandboxDecision decision, boolean used, String unsandboxedReason) {
        boolean enabled = decision != null && decision.sandboxEnabled();
        return new SandboxAuditMetadata(
                enabled,
                used,
                config.isRequired(),
                enabled ? "macos-seatbelt" : "",
                used && config.isAutoAllowCommandIfSandboxed(),
                unsandboxedReason,
                config.getNetwork().isEnabled() ? "allow" : "deny",
                "",
                List.of());
    }
}
