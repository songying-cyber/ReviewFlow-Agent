package com.paicli.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BrowserGuard {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SERVER_PREFIX = "mcp__chrome-devtools__";
    private static final Set<String> WRITE_TOOLS = Set.of(
            "click",
            "drag",
            "fill",
            "fill_form",
            "handle_dialog",
            "hover",
            "press_key",
            "resize_page",
            "upload_file",
            "evaluate_script"
    );
    private static final Pattern PAGE_ID_PATTERN = Pattern.compile("(page[-_][A-Za-z0-9_-]+)");

    private final BrowserSession session;
    private final SensitivePagePolicy sensitivePagePolicy;

    public BrowserGuard(BrowserSession session, SensitivePagePolicy sensitivePagePolicy) {
        this.session = session;
        this.sensitivePagePolicy = sensitivePagePolicy;
    }

    public BrowserCheckResult check(String toolName, String argsJson, boolean mutateSession) {
        if (!isChromeTool(toolName)) {
            return BrowserCheckResult.allow(null);
        }
        String localTool = localToolName(toolName);
        JsonNode args = parseArgs(argsJson);
        String targetUrl = targetUrl(localTool, args);
        String effectiveUrl = targetUrl == null ? session.lastNavigatedUrl() : targetUrl;
        SensitivePagePolicy.MatchResult match = sensitivePagePolicy.match(effectiveUrl);
        BrowserAuditMetadata metadata = BrowserAuditMetadata.of(session.mode(), match.matched(), effectiveUrl);

        if ("close_page".equals(localTool)
                && session.mode() == BrowserMode.SHARED
                && !session.isAgentOpenedTab(pageId(args))) {
            return BrowserCheckResult.block(
                    "shared 浏览器模式下拒绝关闭非 PaiCLI 创建的标签页，请手动关闭该 Chrome 标签页",
                    metadata);
        }

        if (match.matched() && WRITE_TOOLS.contains(localTool)) {
            return BrowserCheckResult.requireApproval(
                    "敏感页面命中规则 " + match.pattern() + "，本次浏览器改写操作必须单步审批，不能复用全部放行。",
                    metadata);
        }

        if (mutateSession) {
            applyMutation(localTool, args, targetUrl);
        }
        return BrowserCheckResult.allow(metadata);
    }

    public void applyAfterExecution(String toolName, String argsJson, String result) {
        if (!isChromeTool(toolName)) {
            return;
        }
        String localTool = localToolName(toolName);
        JsonNode args = parseArgs(argsJson);
        applyMutation(localTool, args, targetUrl(localTool, args));
        if ("new_page".equals(localTool)) {
            String pageId = pageId(args);
            if (pageId == null || pageId.isBlank()) {
                pageId = extractPageId(result);
            }
            session.recordOpenedTab(pageId);
        }
    }

    public static boolean isChromeTool(String toolName) {
        return toolName != null && toolName.startsWith(SERVER_PREFIX);
    }

    private static String localToolName(String toolName) {
        return toolName.substring(SERVER_PREFIX.length());
    }

    private static JsonNode parseArgs(String argsJson) {
        try {
            return MAPPER.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static String targetUrl(String localTool, JsonNode args) {
        if (!"navigate_page".equals(localTool) && !"new_page".equals(localTool)) {
            return null;
        }
        String url = text(args, "url");
        return url == null || url.isBlank() ? null : url;
    }

    private static String pageId(JsonNode args) {
        String pageIdx = text(args, "pageIdx");
        if (pageIdx != null && !pageIdx.isBlank()) {
            return pageIdx;
        }
        String pageId = text(args, "pageId");
        if (pageId != null && !pageId.isBlank()) {
            return pageId;
        }
        String uid = text(args, "uid");
        return uid == null || uid.isBlank() ? null : uid;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private void applyMutation(String localTool, JsonNode args, String targetUrl) {
        if (("navigate_page".equals(localTool) || "new_page".equals(localTool)) && targetUrl != null) {
            session.rememberNavigation(targetUrl);
        }
    }

    private static String extractPageId(String result) {
        if (result == null) {
            return null;
        }
        Matcher matcher = PAGE_ID_PATTERN.matcher(result);
        return matcher.find() ? matcher.group(1) : null;
    }
}
