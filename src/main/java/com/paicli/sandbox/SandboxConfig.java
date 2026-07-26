package com.paicli.sandbox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SandboxConfig {
    private boolean enabled = false;
    private boolean required = false;
    private boolean autoAllowCommandIfSandboxed = true;
    private boolean allowUnsandboxedCommands = true;
    private List<String> excludedCommands = new ArrayList<>(List.of("docker:*", "podman:*", "colima:*"));
    private Filesystem filesystem = new Filesystem();
    private Network network = new Network();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public boolean isAutoAllowCommandIfSandboxed() {
        return autoAllowCommandIfSandboxed;
    }

    public void setAutoAllowCommandIfSandboxed(boolean autoAllowCommandIfSandboxed) {
        this.autoAllowCommandIfSandboxed = autoAllowCommandIfSandboxed;
    }

    public boolean isAllowUnsandboxedCommands() {
        return allowUnsandboxedCommands;
    }

    public void setAllowUnsandboxedCommands(boolean allowUnsandboxedCommands) {
        this.allowUnsandboxedCommands = allowUnsandboxedCommands;
    }

    public List<String> getExcludedCommands() {
        return excludedCommands;
    }

    public void setExcludedCommands(List<String> excludedCommands) {
        this.excludedCommands = excludedCommands == null ? new ArrayList<>() : new ArrayList<>(excludedCommands);
    }

    public Filesystem getFilesystem() {
        return filesystem;
    }

    public void setFilesystem(Filesystem filesystem) {
        this.filesystem = filesystem == null ? new Filesystem() : filesystem;
    }

    public Network getNetwork() {
        return network;
    }

    public void setNetwork(Network network) {
        this.network = network == null ? new Network() : network;
    }

    public SandboxConfig copy() {
        SandboxConfig copy = new SandboxConfig();
        copy.enabled = enabled;
        copy.required = required;
        copy.autoAllowCommandIfSandboxed = autoAllowCommandIfSandboxed;
        copy.allowUnsandboxedCommands = allowUnsandboxedCommands;
        copy.excludedCommands = new ArrayList<>(excludedCommands);
        copy.filesystem = filesystem.copy();
        copy.network = network.copy();
        return copy;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Filesystem {
        private List<String> allowRead = new ArrayList<>(List.of("."));
        private List<String> allowWrite = new ArrayList<>(List.of("."));
        private List<String> denyRead = new ArrayList<>(List.of(
                ".env", "**/*.pem", "**/id_rsa", "**/*_key", "**/*token*", "**/*secret*", "**/*credential*"));
        private List<String> denyWrite = new ArrayList<>(List.of(
                "~/.paicli/**", ".paicli/**", ".env", "PAI.md", "AGENTS.md",
                ".git/hooks/**", "HEAD", "objects", "refs", "hooks", "config"));

        public List<String> getAllowRead() {
            return allowRead;
        }

        public void setAllowRead(List<String> allowRead) {
            this.allowRead = allowRead == null ? new ArrayList<>() : new ArrayList<>(allowRead);
        }

        public List<String> getAllowWrite() {
            return allowWrite;
        }

        public void setAllowWrite(List<String> allowWrite) {
            this.allowWrite = allowWrite == null ? new ArrayList<>() : new ArrayList<>(allowWrite);
        }

        public List<String> getDenyRead() {
            return denyRead;
        }

        public void setDenyRead(List<String> denyRead) {
            this.denyRead = denyRead == null ? new ArrayList<>() : new ArrayList<>(denyRead);
        }

        public List<String> getDenyWrite() {
            return denyWrite;
        }

        public void setDenyWrite(List<String> denyWrite) {
            this.denyWrite = denyWrite == null ? new ArrayList<>() : new ArrayList<>(denyWrite);
        }

        Filesystem copy() {
            Filesystem copy = new Filesystem();
            copy.allowRead = new ArrayList<>(allowRead);
            copy.allowWrite = new ArrayList<>(allowWrite);
            copy.denyRead = new ArrayList<>(denyRead);
            copy.denyWrite = new ArrayList<>(denyWrite);
            return copy;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Network {
        private boolean enabled = false;
        private List<String> allowedDomains = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getAllowedDomains() {
            return allowedDomains;
        }

        public void setAllowedDomains(List<String> allowedDomains) {
            this.allowedDomains = allowedDomains == null ? new ArrayList<>() : new ArrayList<>(allowedDomains);
        }

        Network copy() {
            Network copy = new Network();
            copy.enabled = enabled;
            copy.allowedDomains = new ArrayList<>(allowedDomains);
            return copy;
        }
    }
}
