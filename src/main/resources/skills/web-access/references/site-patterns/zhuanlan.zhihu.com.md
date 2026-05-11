---
domain: zhuanlan.zhihu.com
aliases: [知乎专栏, zhihu zhuanlan]
updated: 2026-05-07
---

## 平台特征

- **架构**：Next.js SSR + 客户端 hydration；HTML 里有完整正文（SSR 已渲染），但 JS 接管后会把部分内容懒加载
- **反爬强度**：中（带 X-Zse 签名头校验，但 SSR 路径对 fetch 友好）
- **登录态**：阅读不需要登录；评论 / 点赞 / 关注需要
- **关键技术事实**：
  - 专栏文章 URL：`https://zhuanlan.zhihu.com/p/<numeric_id>`
  - 问答 URL（结构不同）：`https://www.zhihu.com/question/<id>/answer/<id>`
  - 专栏正文在 `<div class="Post-RichTextContainer">` 或 `<article class="Post-Main">`

## 有效模式

### web_fetch 一般够用

```
web_fetch(url="https://zhuanlan.zhihu.com/p/12345")
```

如果返回正文则直接用。HtmlExtractor 的 readability 能识别 `<article>`。

### web_fetch 失败时上浏览器

```
mcp__chrome-devtools__navigate_page(url)
  → wait_for(selector=".Post-RichTextContainer, article")
  → take_snapshot()
```

JS 抽取片段：

```javascript
const title = document.querySelector('.Post-Title')?.textContent;
const author = document.querySelector('.AuthorInfo-name a')?.textContent;
const publishTime = document.querySelector('time')?.getAttribute('datetime');
const body = document.querySelector('.Post-RichTextContainer')?.innerText;
return { title, author, publishTime, body };
```

### 问答页（不是专栏）

问答页比专栏页麻烦：
- 答案列表是无限滚动加载，需要滚到底（`scrollTo(0, document.body.scrollHeight)` + `wait_for`）
- 单答案需要点"展开"按钮才有完整内容

问答类任务先用 `web_search` 把目标答案 URL 找到，再走浏览器交互。

## 已知陷阱

1. **被风控时返回登录墙**：`web_fetch` 返回的 HTML 包含 `<title>知乎，让每一次点击都充满意义</title>` + 没有正文 → 被风控。换浏览器 isolated 也只是延缓；真要稳定抓建议走 `r.jina.ai`。
2. **图片站外防盗链**：知乎图片域名 `pic1.zhimg.com` 等加了 Referer 校验。要把图保存下来必须带 `Referer: https://zhuanlan.zhihu.com/`。
3. **正文中嵌入的视频是腾讯云 / 七牛 CDN**：抓不到原 mp4，只能抓播放器 iframe URL。
4. **作者头像选择器易变**：`.AuthorInfo-avatar img` / `.UserAvatar img` 都见过。
5. **评论区是分页 ajax**：`api/v4/comments/<id>` 返回 JSON，但带签名头，浏览器内执行没问题，纯 curl 容易 401。

## 高价值字段

| 字段 | 选择器 |
|---|---|
| 标题 | `.Post-Title` |
| 作者 | `.AuthorInfo-name a` |
| 作者签名 | `.AuthorInfo-detail` |
| 发布时间 | `time[datetime]` 的 datetime 属性 |
| 点赞数 | `.VoteButton .Voters` |
| 正文 | `.Post-RichTextContainer` |

## 适用场景

读知乎专栏单篇文章。问答页要谨慎，建议先告知用户"知乎答案需要展开+滚动加载，可能拿到不全"。
