package com.paicli.web;

/**
 * web_fetch 的结构化结果。
 *
 * <ul>
 *   <li>{@link #markdown} 为空 / {@link #bodyEmpty} = true 时，意味着抓到了 HTML 但提取不出正文，
 *       常见原因是 SPA / 防爬墙。LLM 应该意识到这是已知边界，不要反复重试。</li>
 *   <li>{@link #truncated} = true 表示 markdown 已被截断到调用方指定的最大字符数。</li>
 * </ul>
 */
public record FetchResult(
        String url,
        String title,
        String markdown,
        int contentLength,
        boolean truncated,
        boolean bodyEmpty,
        String hint
) {

    public static FetchResult ok(String url, String title, String markdown, int originalLength, boolean truncated) {
        boolean empty = markdown == null || markdown.isBlank();
        String hint = empty
                ? "未提取到正文。可能是 JS 渲染或防爬墙；本期范围内不再重试。"
                : "";
        return new FetchResult(url, title == null ? "" : title, markdown == null ? "" : markdown,
                originalLength, truncated, empty, hint);
    }
}
