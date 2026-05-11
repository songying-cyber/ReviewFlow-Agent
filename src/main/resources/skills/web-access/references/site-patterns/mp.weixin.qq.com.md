---
domain: mp.weixin.qq.com
aliases: [微信公众号, weixin, 公众号文章]
updated: 2026-05-07
---

## 平台特征

- **架构**：服务端渲染但带反爬墙；普通 HTTP fetch 拿到的是空 body 或验证码页
- **反爬强度**：中高（user-agent 检查 + IP 频次 + Referer 校验）
- **登录态**：阅读不需要登录，浏览器 MCP isolated 模式即可
- **关键技术事实**：
  - 文章主体在 `#js_content` 内，全部静态写死（不需要 JS 渲染）
  - 但反爬墙拦在 HTTP 层，必须用真实浏览器 User-Agent + 完整 cookies
  - 移动端 URL（`mp.weixin.qq.com/s/<token>`）和 PC 分享版 URL 内容一致

## 有效模式

### 直接走 chrome-devtools MCP（不要先 web_fetch）

```
mcp__chrome-devtools__navigate_page(url)
  → wait_for(selector="#js_content")
  → take_snapshot()
```

抽正文 JS 片段：

```javascript
const article = document.querySelector('#js_content');
const title = document.querySelector('#activity-name')?.textContent.trim();
const author = document.querySelector('#js_name')?.textContent.trim();
const publishTime = document.querySelector('#publish_time')?.textContent.trim();
return { title, author, publishTime, body: article?.innerText.slice(0, 5000) };
```

### r.jina.ai 也能用

```bash
curl -s 'https://r.jina.ai/https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg'
```

返回 markdown 化的文章正文，免登录、免浏览器。文章不长时优于浏览器（省 30-50s）。

## 已知陷阱

1. **web_fetch 几乎必失败**：HTML 拿回来 `#js_content` 为空或被替换为反爬墙页面。第一次失败就换浏览器 / Jina，不要重试。
2. **图文消息中的图片是懒加载**：`<img data-src="..."` 直接抓 `src` 会拿到占位符。要用 `data-src`：
   ```js
   document.querySelectorAll('img').forEach(img => {
     if (img.dataset.src) img.src = img.dataset.src;
   });
   ```
3. **过期文章 / 删除文章**：服务端返回"该内容已被发布者删除"或者整页 reload 不动。snapshot 出现 `已被发布者删除` 关键词就告诉用户文章已不可访问，不要重试。
4. **作者名字段不稳定**：原创文章在 `#js_name`；转载在 `#meta_content > a.rich_media_meta`；老文章可能在 `#js_profile_qrcode + a`。三者都试一遍。
5. **回链 / 阅读原文**：文章末尾 `#js_view_source` 是原文链接（多用于转载文章），需要继续追的话拿这个。
6. **Jina 偶尔限流**：返回 `Rate limit exceeded`，等 1-2 分钟再试，或回到浏览器路径。

## 高价值字段

| 字段 | 选择器 |
|---|---|
| 标题 | `#activity-name` |
| 作者 | `#js_name` |
| 发布时间 | `#publish_time` 或 `em#publish_time` |
| 公众号名 | `#js_profile_qrcode + a` 或 `.profile_meta strong` |
| 正文 | `#js_content` |
| 阅读原文链接 | `#js_view_source` |

## 适用场景

读单篇微信文章，做摘要、提取观点、转译。**不适合**批量抓公众号历史文章列表（接口被严格限制）。
