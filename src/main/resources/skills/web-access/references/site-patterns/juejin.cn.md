---
domain: juejin.cn
aliases: [掘金, JueJin]
updated: 2026-05-07
---

## 平台特征

- **架构**：Next.js + 部分 client-side fetch；文章页 SSR HTML 含完整正文
- **反爬强度**：低-中（弹"完成验证"对话框较少；列表页有限流）
- **登录态**：阅读不需要；点赞 / 收藏 / 关注 / 看个人主页详情需要
- **关键技术事实**：
  - 文章 URL：`https://juejin.cn/post/<numeric_id>`
  - 正文容器：`#article-root` 或 `.article-content` 或 `article.markdown-body`
  - SSR 后正文已就绪，web_fetch 通常能拿到

## 有效模式

### web_fetch 一般直接成功

```
web_fetch("https://juejin.cn/post/7281234567890123456")
```

HtmlExtractor 能识别 `<article>` + `.markdown-body`，提取干净。

### web_fetch 失败时上浏览器

```
mcp__chrome-devtools__navigate_page(url)
  → wait_for(selector="#article-root, article")
  → take_snapshot()
```

JS 提取：

```javascript
const title = document.querySelector('.article-title, h1.title')?.textContent;
const author = document.querySelector('.author-name, .username')?.textContent;
const publishTime = document.querySelector('time, .meta-box time')?.getAttribute('datetime');
const body = document.querySelector('#article-root, .article-content')?.innerText;
const tags = [...document.querySelectorAll('.tag-list .tag')].map(t => t.textContent);
return { title, author, publishTime, body, tags };
```

### 搜索文章

```
https://juejin.cn/search?query=<keyword>&type=article
```

注意搜索结果页是 SPA，要走浏览器 wait_for + take_snapshot。

## 已知陷阱

1. **文章详情 SSR 但搜索结果 SPA**：详情页 web_fetch 能拿；搜索 / 列表必须浏览器。
2. **markdown 代码块**：`<pre>` 内 `<code>` 包含完整代码，take_snapshot 后要保留 indentation。如果输出乱了，用 `pre code` 元素的 innerText（保留 \n）而不是 textContent。
3. **作者主页 user/<id>**：是 SPA，未登录看到的内容比登录少（订阅/动态等）。
4. **图片懒加载**：用 `data-src` 属性而非 `src`：
   ```js
   document.querySelectorAll('img[data-src]').forEach(img => img.src = img.dataset.src);
   ```
5. **分类页限流**：连续访问 `/recommend` `/frontend` 等分类列表页超过 10 次会触发图形验证码，浏览器内手动通过即可。
6. **小册（付费课程）**：`/book/<id>` 路径需要购买后登录才能看正文，未购买只能看大纲。

## 高价值字段

| 字段 | 选择器 |
|---|---|
| 标题 | `.article-title` 或 `h1.title` |
| 作者 | `.author-name` / `.username` |
| 发布时间 | `time[datetime]` |
| 阅读量 | `.views-count` |
| 点赞数 | `.like-btn .count` |
| 标签 | `.tag-list .tag` |
| 正文 | `#article-root` / `.article-content` |

## 适用场景

抓掘金技术文章正文做摘要 / 提取代码片段。SSR 友好，是国内技术博客里最容易抓的之一。
