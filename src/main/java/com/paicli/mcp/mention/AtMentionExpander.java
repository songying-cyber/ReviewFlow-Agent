package com.paicli.mcp.mention;

import com.paicli.mcp.McpServerManager;

import java.util.List;

public class AtMentionExpander {
    private static final int MAX_INLINE_RESOURCE_CHARS = 200_000;

    private final McpServerManager serverManager;

    public AtMentionExpander(McpServerManager serverManager) {
        this.serverManager = serverManager;
    }

    public String expand(String input) {
        List<AtMentionParser.MentionToken> tokens = AtMentionParser.parse(input);
        if (tokens.isEmpty()) {
            return input;
        }

        StringBuilder expanded = new StringBuilder(input);
        for (int i = tokens.size() - 1; i >= 0; i--) {
            AtMentionParser.MentionToken token = tokens.get(i);
            String replacement = expandToken(token);
            expanded.replace(token.start(), token.end(), replacement);
        }
        return expanded.toString();
    }

    private String expandToken(AtMentionParser.MentionToken token) {
        try {
            McpServerManager.ResourceReadResult result =
                    serverManager.readResourceForMention(token.serverName(), token.uri());
            String content = result.content();
            boolean truncated = false;
            if (content.length() > MAX_INLINE_RESOURCE_CHARS) {
                content = content.substring(0, MAX_INLINE_RESOURCE_CHARS);
                truncated = true;
            }
            String mimeType = result.mimeType() == null || result.mimeType().isBlank()
                    ? "text/plain"
                    : result.mimeType();
            String suffix = truncated
                    ? "\n[resource truncated by PaiCLI at " + MAX_INLINE_RESOURCE_CHARS + " chars]"
                    : "";
            return "<resource server=\"" + escapeXml(token.serverName()) +
                    "\" uri=\"" + escapeXml(token.uri()) +
                    "\" mimeType=\"" + escapeXml(mimeType) + "\">\n" +
                    content + suffix + "\n</resource>";
        } catch (Exception e) {
            return token.raw() + "\n<resource_error server=\"" + escapeXml(token.serverName()) +
                    "\" uri=\"" + escapeXml(token.uri()) + "\">" +
                    escapeXml(e.getMessage()) + "</resource_error>";
        }
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
