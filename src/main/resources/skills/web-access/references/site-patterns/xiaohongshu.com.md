---
domain: xiaohongshu.com
aliases: [小红书, RedNote, XHS]
updated: 2026-05-07
---

## 平台特征

- **架构**：Vue SPA，重度依赖 token / signature 校验
- **反爬强度**：高（请求带 x-s / x-t / x-s-common 签名头，算法定期更换）
- **登录态**：网页端允许未登录浏览 explore，但搜索和详情页强度检查高
- **关键技术事实**：
  - 直接 URL 访问 `/explore/{noteId}` 经常 404 + "当前笔记暂时无法浏览，请打开小红书 App 扫码查看"（error_code=300031）
  - explore 首页默认不加载 feed（需要滚动 + 一段活跃时间），从空白 tab 打开会只剩 footer
- **结论**：**只用搜索页的列表数据**，不尝试拿详情正文

## 有效模式

### 搜索（最可靠的入口）

```
URL 格式：
https://www.xiaohongshu.com/search_result?keyword={URL_ENCODED}&type=51
# type=51 综合 / type=55 图文 / type=56 视频
```

```
mcp__chrome-devtools__navigate_page("https://www.xiaohongshu.com/search_result?keyword=...")
  → wait_for(selector="section.note-item, [class*=note-item]")
  → take_snapshot()
```

提取列表 JS：

```javascript
const items = [...document.querySelectorAll('[class*=note-item]')];
const notes = items.map(s => {
  const text = s.innerText.trim().replace(/\s+/g, ' ');
  const link = s.querySelector('a[href*="/explore/"]')?.href || '';
  return {
    text: text.slice(0, 200),  // "标题 作者 日期 点赞数"
    link,
    noteId: link.match(/\/explore\/([a-f0-9]+)/)?.[1] || ''
  };
}).filter(n => n.link);
```

### 文本结构解析

笔记卡片的 innerText 是连贯文本，用正则拆解：

```javascript
function parseNote(text) {
  const m = text.match(/^(.+?)\s+([^\s]+?)\s+(\d{4}-\d{2}-\d{2}|\d{2}-\d{2}|\d+天前|昨天\s+\d+:\d+|今天\s+\d+:\d+|\d+小时前)\s+(\d+)$/);
  if (!m) return null;
  const [, title, author, date, likes] = m;
  return { title, author, date, likes: parseInt(likes) };
}
```

## 已知陷阱

1. **`/explore/{noteId}` 直接 navigate 经常 404**：即使 link 是从合法搜索页拿的。404 页含 `error_code=300031` 和"请打开小红书 App 扫码查看"。**不要尝试绕过**，直接放弃详情页，用列表数据。
2. **explore 首页从空白加载失败**：tab 是 `about:blank` 直接 navigate 到 explore，body 只有 footer。解决方案：先导航到搜索页（更稳定）。
3. **note-item 选择器有两种 class**：`section.note-item` 和 `[class*=note-item]`，建议用后者更宽容。
4. **相关搜索词混在 note-item 里**：最后几项可能是 `div.query-note-item` 而不是真笔记，filter 时要检查是否有 `a[href*=/explore/]`。
5. **"热度/日期"字段变体多**：`02-14`、`2025-04-28`、`昨天 20:25`、`5 天前`、`03-31` 都会出现，解析时要宽容。
6. **type=51 是综合搜索**，混合图文 / 视频 / 直播。如果只要图文用 `type=55`。

## 高价值字段

只能从搜索结果列表拿到：
- 标题（前 30 字）
- 作者昵称
- 发布日期
- 点赞数
- noteId（用于追踪，但不能再 navigate 详情）

## 适用场景

作为**信号发现层**，只收集标题 + 作者 + 点赞数，做话题热度分析。**不适合**抓小红书笔记详情正文（基本拿不到）。
