---
domain: x.com
aliases: [Twitter, twitter.com, X, 推特]
updated: 2026-05-07
---

## 平台特征

- **架构**：React SPA，所有内容由 JS 渲染；几乎不存在 SSR HTML
- **反爬强度**：高（GraphQL 接口需要 bearer token + cookies + ct0 csrf token + transaction id 签名）
- **登录态**：未登录可看部分推文，但有强烈的"登录墙"弹窗；批量浏览必须登录
- **关键技术事实**：
  - 所有数据通过 `client-event` / GraphQL endpoint（如 `TweetDetail`、`UserTweets`）拉取
  - DOM 选择器极易变化（class 名都是 hash 化的 `r-1d09ksm` 类似）
  - `data-testid` 属性比 class 稳定得多

## 有效模式

### 必须走 chrome-devtools MCP

```
mcp__chrome-devtools__navigate_page(url)
  → wait_for(selector="article[data-testid=tweet]")
  → take_snapshot()
```

未登录读单条推文：

```javascript
const tweet = document.querySelector('article[data-testid=tweet]');
const text = tweet?.querySelector('div[data-testid=tweetText]')?.innerText;
const author = tweet?.querySelector('div[data-testid=User-Name]')?.innerText;
return { text, author };
```

### 需要个人时间线 / 私信 / 列表 / 关注关系

必须 `/browser connect` 切 shared 模式（用户已登录）。**注意 X 是敏感页面**：
- `/i/communities/*` / `/messages` / `/settings/*` 命中 PaiCLI 默认敏感规则 → 操作要单步审批
- 即使用户全放行也无法批量操作敏感页面

## 已知陷阱

1. **未登录时 timeline 是空的**：`/<user>` 个人主页未登录看不到推文列表，只看到 "Try again or sign up"。要拿用户推文必须登录。
2. **`data-testid=tweet` 一篇页面有多条**：详情页除了主推文还有回复树，同 testid 多个。区分：主推文是第一个或带 `tabindex=-1` 属性的那个。
3. **Show more 折叠**：超过 280 字的推文会折叠，需要点 `Show more` 链接展开（`a[role=link]:contains("Show more")`）。
4. **图片 / 视频 URL 易变**：`pbs.twimg.com/media/<id>?format=jpg&name=large` 是高清版；`small` 是缩略图。视频是 m3u8 流，直接抓 mp4 需要解析 manifest。
5. **限流 / 反爬**：连续多次 navigate 同一域名会触发临时锁，`status_code 429` 或被强制登录。**降速**：每两次 navigate 之间 2-3 秒 wait。
6. **/x.com 和 twitter.com 都是同一个站**：`twitter.com` 现在 301 到 `x.com`，但接口和 OG 标签里仍可见 `twitter.com`。

## 高价值字段

| 字段 | 选择器（用 data-testid，比 class 稳定） |
|---|---|
| 推文容器 | `article[data-testid=tweet]` |
| 正文 | `div[data-testid=tweetText]` |
| 作者展示名+handle | `div[data-testid=User-Name]` |
| 时间 | `time[datetime]` 的 datetime |
| 点赞数 | `button[data-testid=like]` 的 aria-label |
| 转发数 | `button[data-testid=retweet]` 的 aria-label |
| 回复数 | `button[data-testid=reply]` 的 aria-label |

## 适用场景

读单条推文 / 浅层调研话题。**不适合**做大规模数据采集，会被 IP 级 ban。
