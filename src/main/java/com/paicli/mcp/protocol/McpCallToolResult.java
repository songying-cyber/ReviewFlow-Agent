package com.paicli.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpCallToolResult(List<McpContent> content, boolean isError) {
    public String formatForLlm() {
        if (content == null || content.isEmpty()) {
            return isError ? "MCP 工具返回错误，但没有错误正文" : "";
        }
        return content.stream()
                .map(item -> {
                    String type = item.type() == null || item.type().isBlank() ? "text" : item.type();
                    if ("text".equals(type)) {
                        return item.text() == null ? "" : item.text();
                    }
                    return "[此工具返回了 " + type + "，请向用户描述结果]";
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));
    }
}
