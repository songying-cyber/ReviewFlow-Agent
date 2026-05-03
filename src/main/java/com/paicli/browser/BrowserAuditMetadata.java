package com.paicli.browser;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BrowserAuditMetadata(
        @JsonProperty("browser_mode") String browserMode,
        Boolean sensitive,
        @JsonProperty("target_url") String targetUrl
) {
    public static BrowserAuditMetadata of(BrowserMode mode, boolean sensitive, String targetUrl) {
        return new BrowserAuditMetadata(mode == null ? null : mode.name().toLowerCase(), sensitive, targetUrl);
    }
}
