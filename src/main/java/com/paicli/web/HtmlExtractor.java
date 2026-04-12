package com.paicli.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 极简版 readability：HTML → 主正文 Markdown。
 *
 * 思路（按优先级）：
 * <ol>
 *   <li>清理噪声标签：script、style、nav、aside、footer、header、form、iframe、广告 class</li>
 *   <li>找主语义容器：&lt;article&gt;、&lt;main&gt;、role="main"</li>
 *   <li>都没有则给所有 block 元素打分（文本长度 - 链接占比惩罚），选最高分</li>
 *   <li>再把选中容器递归转成 Markdown</li>
 * </ol>
 *
 * 不追求与 Mozilla Readability 完全对齐，目标是覆盖博客 / 文档 / 官网这类
 * SSR 页面的常见结构。SPA 渲染后的空 HTML 会得到空字符串，由调用方提示边界。
 */
public class HtmlExtractor {

    private static final Set<String> NOISE_TAGS = Set.of(
            "script", "style", "noscript", "iframe", "nav", "aside",
            "header", "footer", "form", "svg", "canvas", "button"
    );

    private static final Set<String> NOISE_CLASS_KEYWORDS = Set.of(
            "ads", "advert", "banner", "popup", "modal", "subscribe", "newsletter",
            "related", "recommend", "comment", "share", "social", "breadcrumb",
            "sidebar", "promo", "cookie", "footer", "navigation"
    );

    public Extracted extract(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl == null ? "" : baseUrl, Parser.htmlParser());
        String title = pickTitle(doc);

        cleanNoise(doc);
        Element main = pickMainElement(doc);

        if (main == null) {
            return new Extracted(title, "");
        }

        StringBuilder out = new StringBuilder();
        renderChildren(main, out, false);
        String markdown = collapseBlankLines(out.toString()).trim();
        return new Extracted(title, markdown);
    }

    private String pickTitle(Document doc) {
        String t = doc.title();
        if (t != null && !t.isBlank()) return t.trim();
        Element h1 = doc.selectFirst("h1");
        return h1 == null ? "" : h1.text().trim();
    }

    private void cleanNoise(Document doc) {
        for (String tag : NOISE_TAGS) {
            doc.select(tag).remove();
        }
        // 按 class/id 关键词清理常见广告 / 导航壳
        Elements all = doc.select("[class],[id]");
        for (Element el : all) {
            String marker = (el.className() + " " + el.id()).toLowerCase(Locale.ROOT);
            for (String kw : NOISE_CLASS_KEYWORDS) {
                if (marker.contains(kw)) {
                    el.remove();
                    break;
                }
            }
        }
    }

    private Element pickMainElement(Document doc) {
        Element semantic = doc.selectFirst("article, main, [role=main]");
        if (semantic != null && semantic.text().length() > 80) {
            return semantic;
        }
        // 给候选 block 元素打分
        Elements candidates = doc.select("div, section, article, main");
        Element best = doc.body();
        double bestScore = best == null ? 0 : score(best);
        for (Element el : candidates) {
            double s = score(el);
            if (s > bestScore) {
                best = el;
                bestScore = s;
            }
        }
        return best;
    }

    private double score(Element el) {
        String text = el.text();
        int textLen = text.length();
        if (textLen < 80) return 0;
        int linkLen = 0;
        for (Element a : el.select("a")) {
            linkLen += a.text().length();
        }
        double linkRatio = (double) linkLen / textLen;
        // 链接密度高 → 大概率是导航 / 列表页
        double penalty = Math.min(linkRatio * 2.0, 1.0);
        return textLen * (1.0 - penalty);
    }

    private void renderChildren(Element parent, StringBuilder out, boolean inListContext) {
        for (Node child : parent.childNodes()) {
            if (child instanceof TextNode tn) {
                String txt = tn.text();
                if (!txt.isBlank()) {
                    out.append(txt);
                }
            } else if (child instanceof Element el) {
                renderElement(el, out, inListContext);
            } else if (child instanceof Comment) {
                // 忽略
            }
        }
    }

    private void renderElement(Element el, StringBuilder out, boolean inListContext) {
        String tag = el.tagName().toLowerCase(Locale.ROOT);
        switch (tag) {
            case "h1" -> heading(el, out, "# ");
            case "h2" -> heading(el, out, "## ");
            case "h3" -> heading(el, out, "### ");
            case "h4" -> heading(el, out, "#### ");
            case "h5" -> heading(el, out, "##### ");
            case "h6" -> heading(el, out, "###### ");
            case "p" -> {
                out.append("\n\n");
                renderChildren(el, out, false);
                out.append("\n\n");
            }
            case "br" -> out.append("\n");
            case "hr" -> out.append("\n\n---\n\n");
            case "strong", "b" -> {
                out.append("**");
                renderChildren(el, out, inListContext);
                out.append("**");
            }
            case "em", "i" -> {
                out.append("*");
                renderChildren(el, out, inListContext);
                out.append("*");
            }
            case "code" -> {
                if (el.parent() != null && "pre".equalsIgnoreCase(el.parent().tagName())) {
                    renderChildren(el, out, inListContext);
                } else {
                    out.append("`").append(el.text()).append("`");
                }
            }
            case "pre" -> {
                out.append("\n\n```\n");
                out.append(el.wholeText().stripTrailing());
                out.append("\n```\n\n");
            }
            case "blockquote" -> {
                StringBuilder inner = new StringBuilder();
                renderChildren(el, inner, false);
                String[] lines = inner.toString().trim().split("\n");
                out.append("\n\n");
                for (String line : lines) {
                    out.append("> ").append(line).append("\n");
                }
                out.append("\n");
            }
            case "ul" -> renderList(el, out, false);
            case "ol" -> renderList(el, out, true);
            case "li" -> {
                // 没有外层 ul/ol（罕见）就当无序列表项处理
                out.append("\n- ");
                renderChildren(el, out, true);
            }
            case "a" -> {
                String href = el.attr("abs:href");
                String text = el.text();
                if (text.isBlank()) {
                    return;
                }
                if (href.isBlank()) {
                    out.append(text);
                } else {
                    out.append("[").append(text).append("](").append(href).append(")");
                }
            }
            case "img" -> {
                // 默认不渲染图片：教学项目里会让 markdown 体积爆涨。如需要可在调用方扩展
                String alt = el.attr("alt");
                if (!alt.isBlank()) {
                    out.append(alt);
                }
            }
            case "table" -> renderTable(el, out);
            default -> renderChildren(el, out, inListContext);
        }
    }

    private void heading(Element el, StringBuilder out, String prefix) {
        String text = el.text().trim();
        if (text.isEmpty()) return;
        out.append("\n\n").append(prefix).append(text).append("\n\n");
    }

    private void renderList(Element list, StringBuilder out, boolean ordered) {
        out.append("\n");
        int idx = 1;
        for (Element li : list.children()) {
            if (!"li".equalsIgnoreCase(li.tagName())) continue;
            out.append(ordered ? (idx++ + ". ") : "- ");
            StringBuilder inner = new StringBuilder();
            renderChildren(li, inner, true);
            out.append(inner.toString().trim().replace("\n", " "));
            out.append("\n");
        }
        out.append("\n");
    }

    private void renderTable(Element table, StringBuilder out) {
        Elements rows = table.select("tr");
        if (rows.isEmpty()) return;
        out.append("\n\n");
        boolean headerWritten = false;
        for (Element row : rows) {
            Elements cells = row.select("th, td");
            if (cells.isEmpty()) continue;
            List<String> texts = new ArrayList<>();
            for (Element cell : cells) {
                texts.add(cell.text().replace("|", "\\|").trim());
            }
            out.append("| ").append(String.join(" | ", texts)).append(" |\n");
            if (!headerWritten) {
                out.append("|");
                for (int i = 0; i < texts.size(); i++) out.append(" --- |");
                out.append("\n");
                headerWritten = true;
            }
        }
        out.append("\n");
    }

    private String collapseBlankLines(String text) {
        return text.replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n");
    }

    public record Extracted(String title, String markdown) {}
}
