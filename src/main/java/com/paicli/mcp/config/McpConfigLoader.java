package com.paicli.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class McpConfigLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*|PROJECT_DIR|HOME)}");
    private static final String STEP_SEARCH_SERVER = "step_search";
    private static final String STEP_SEARCH_URL = "https://api.stepfun.com/step_plan/v1/mcp/web_search/mcp";

    private final Path userConfig;
    private final Path projectConfig;
    private final Path projectDir;

    public McpConfigLoader(Path projectDir) {
        this(
                Path.of(System.getProperty("user.home"), ".paicli", "mcp.json"),
                projectDir.resolve(".paicli").resolve("mcp.json"),
                projectDir
        );
    }

    public McpConfigLoader(Path userConfig, Path projectConfig, Path projectDir) {
        this.userConfig = userConfig;
        this.projectConfig = projectConfig;
        this.projectDir = projectDir.toAbsolutePath().normalize();
    }

    /**
     * 只读取并合并配置，不做 {@code ${VAR}} 展开。展开 / 校验由 {@link com.paicli.mcp.McpServerManager}
     * 在启动单个 server 时进行，确保单个 server 配置错误（如缺失环境变量）不会阻塞其他 server。
     */
    public Map<String, McpServerConfig> load() throws IOException {
        Map<String, McpServerConfig> merged = new LinkedHashMap<>();
        if (Files.exists(userConfig)) {
            merged.putAll(read(userConfig));
        }
        if (Files.exists(projectConfig)) {
            merged.putAll(read(projectConfig));
        }
        addBuiltInStepSearchIfAvailable(merged);
        return merged;
    }

    /**
     * 对单个 server 配置展开 {@code ${VAR}} 并校验 transport 选择。
     * 失败抛 {@link IllegalArgumentException}，调用方负责把单个失败转成该 server 的 ERROR 状态。
     */
    public void prepare(McpServerConfig config) {
        expand(config);
        validate(config);
    }

    private Map<String, McpServerConfig> read(Path file) throws IOException {
        McpConfigFile configFile = MAPPER.readValue(file.toFile(), McpConfigFile.class);
        return configFile.getMcpServers();
    }

    private void addBuiltInStepSearchIfAvailable(Map<String, McpServerConfig> merged) {
        if (merged.containsKey(STEP_SEARCH_SERVER)) {
            return;
        }
        String apiKey = readConfiguredValue("STEP_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        McpServerConfig config = new McpServerConfig();
        config.setUrl(STEP_SEARCH_URL);
        config.setHeaders(Map.of("Authorization", "Bearer " + apiKey.trim()));
        merged.put(STEP_SEARCH_SERVER, config);
    }

    private void expand(McpServerConfig config) {
        if (config.getCommand() != null) {
            config.setCommand(expandString(config.getCommand()));
        }
        List<String> expandedArgs = new ArrayList<>();
        for (String arg : config.getArgs()) {
            expandedArgs.add(expandString(arg));
        }
        config.setArgs(expandedArgs);
        config.setEnv(expandMap(config.getEnv()));
        if (config.getUrl() != null) {
            config.setUrl(expandString(config.getUrl()));
        }
        config.setHeaders(expandMap(config.getHeaders()));
    }

    private Map<String, String> expandMap(Map<String, String> raw) {
        Map<String, String> expanded = new LinkedHashMap<>();
        if (raw == null) {
            return expanded;
        }
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            expanded.put(entry.getKey(), expandString(entry.getValue()));
        }
        return expanded;
    }

    private String expandString(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = VAR_PATTERN.matcher(raw);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = switch (name) {
                case "PROJECT_DIR" -> projectDir.toString();
                case "HOME" -> System.getProperty("user.home");
                default -> readConfiguredValue(name);
            };
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("MCP 配置引用了未设置的环境变量: " + name);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private void validate(McpServerConfig config) {
        if (config.isStdio() == config.isHttp()) {
            throw new IllegalArgumentException("MCP server 必须且只能配置 command 或 url");
        }
    }

    private String readConfiguredValue(String key) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromProp = System.getProperty(key);
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.trim();
        }
        String fromProjectEnv = readFromDotEnv(projectDir.resolve(".env"), key);
        if (fromProjectEnv != null && !fromProjectEnv.isBlank()) {
            return fromProjectEnv.trim();
        }
        String fromHomeEnv = readFromDotEnv(Path.of(System.getProperty("user.home"), ".env"), key);
        if (fromHomeEnv != null && !fromHomeEnv.isBlank()) {
            return fromHomeEnv.trim();
        }
        return null;
    }

    private static String readFromDotEnv(Path file, String key) {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith(key + "=")) {
                    return stripOptionalQuotes(line.substring((key + "=").length()).trim());
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static String stripOptionalQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
