package com.paicli.sandbox.mac;

import com.paicli.sandbox.SandboxAuditMetadata;
import com.paicli.sandbox.SandboxConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MacSeatbeltSandbox {
    private final SandboxConfig config;
    private final Path projectRoot;

    public MacSeatbeltSandbox(SandboxConfig config, Path projectRoot) {
        this.config = config == null ? new SandboxConfig() : config;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    public WrappedCommand wrap(String command) throws IOException {
        Path tempDir = Files.createTempDirectory("paicli-sandbox-");
        Path profile = tempDir.resolve("profile.sb");
        Files.writeString(profile, new SeatbeltProfileBuilder(projectRoot, tempDir, config).build());
        List<String> argv = new ArrayList<>();
        argv.add("sandbox-exec");
        argv.add("-f");
        argv.add(profile.toString());
        argv.add("/bin/bash");
        argv.add("-lc");
        argv.add(command);
        SandboxAuditMetadata metadata = new SandboxAuditMetadata(
                true,
                true,
                config.isRequired(),
                "macos-seatbelt",
                config.isAutoAllowCommandIfSandboxed(),
                "",
                config.getNetwork().isEnabled() ? "allow" : "deny",
                profile.toString(),
                List.of());
        return new WrappedCommand(argv, tempDir, metadata);
    }

    public boolean available() {
        return Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))
                || Files.isExecutable(Path.of("/bin/sandbox-exec"));
    }

    public record WrappedCommand(List<String> argv, Path tempDir, SandboxAuditMetadata metadata) {
        public WrappedCommand {
            argv = argv == null ? List.of() : List.copyOf(argv);
        }
    }
}
