package com.paicli.sandbox;

import java.util.List;
import java.util.Locale;

public class SandboxPolicy {
    private final SandboxConfig config;

    public SandboxPolicy(SandboxConfig config) {
        this.config = config == null ? new SandboxConfig() : config;
    }

    public SandboxDecision decide(String command, boolean dangerouslyDisableSandbox) {
        if (!config.isEnabled()) {
            return SandboxDecision.disabled();
        }
        if (!isMacOs()) {
            if (config.isRequired()) {
                return SandboxDecision.denied("当前 sandbox.required=true，但 PaiCLI 沙箱仅支持 macOS");
            }
            return new SandboxDecision(true, false, false, false, "",
                    "unsupported_platform", "macos-seatbelt");
        }
        if (dangerouslyDisableSandbox && config.isAllowUnsandboxedCommands()) {
            return new SandboxDecision(true, false, false, false, "",
                    "dangerously_disable_sandbox", "macos-seatbelt");
        }
        if (isExcluded(command, config.getExcludedCommands())) {
            return new SandboxDecision(true, false, false, false, "",
                    "excluded_command", "macos-seatbelt");
        }
        return new SandboxDecision(true, true, false, config.isAutoAllowCommandIfSandboxed(),
                "", "", "macos-seatbelt");
    }

    public SandboxConfig config() {
        return config;
    }

    public static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    static boolean isExcluded(String command, List<String> patterns) {
        if (command == null || command.isBlank() || patterns == null || patterns.isEmpty()) {
            return false;
        }
        String normalized = command.trim();
        for (String raw : patterns) {
            String pattern = raw == null ? "" : raw.trim();
            if (pattern.isBlank()) {
                continue;
            }
            if (matchesPattern(normalized, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPattern(String command, String pattern) {
        if (pattern.endsWith(":*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return command.equals(prefix) || command.startsWith(prefix + " ");
        }
        if (pattern.contains("*")) {
            String regex = "\\Q" + pattern.replace("*", "\\E.*\\Q") + "\\E";
            return command.matches(regex);
        }
        return command.equals(pattern) || command.startsWith(pattern + " ");
    }
}
