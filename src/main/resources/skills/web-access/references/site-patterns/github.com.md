---
domain: github.com
aliases: [GitHub, GH]
updated: 2026-05-07
---

## 平台特征

- **架构**：传统 Rails SSR + 局部 React enhancement，绝大部分页面是 SSR HTML
- **反爬强度**：低（公开仓库 fetch 友好），但 60 RPM 未授权 API 限流
- **登录态**：公开仓库不需要；私仓 / Issues / PR / Settings / Actions 必须登录
- **关键技术事实**：
  - README 渲染后嵌在 `<article class="markdown-body">`
  - Raw README markdown：`https://raw.githubusercontent.com/<owner>/<repo>/<branch>/README.md`
  - GitHub API：`https://api.github.com/repos/<owner>/<repo>` 不需要鉴权能拿元数据，但有 60 RPM 限制

## 有效模式

### 公开仓库 README

直接 web_fetch：

```
web_fetch("https://github.com/anthropics/anthropic-cookbook")
```

或者更省 token：

```
web_fetch("https://raw.githubusercontent.com/anthropics/anthropic-cookbook/main/README.md")
```

raw 路径不带 GitHub 页面外壳（导航栏、文件树、统计），纯 markdown 内容，是 LLM 的最爱。

### 仓库元数据（star / fork / lang / 最新 release）

```bash
curl -s 'https://api.github.com/repos/anthropics/anthropic-cookbook' | jq '{
  stars: .stargazers_count,
  forks: .forks_count,
  lang: .language,
  description,
  default_branch
}'
```

最新 release：

```bash
curl -s 'https://api.github.com/repos/<owner>/<repo>/releases/latest' | jq '{tag_name, name, body}'
```

### 私有仓 / 需要登录的页面

必须 `/browser connect` 切 shared 模式。然后：

```
mcp__chrome-devtools__navigate_page("https://github.com/<your-org>/<private-repo>")
  → take_snapshot()
```

**敏感页面提醒**：
- `/settings/*` 路径命中 PaiCLI 默认敏感规则 → 改写型操作单步审批
- `/security/*` / `/billing/*` / `/admin/*` 同上

### 搜索代码

GitHub 代码搜索（公开）：

```
web_fetch("https://github.com/search?q=<keyword>&type=code")
```

需要登录看完整结果，未登录有 hit 数限制。

## 已知陷阱

1. **大文件 README 截断**：默认 GitHub 页面 README 渲染上限是 1MB（实际很少超），但 web_fetch 自身有 8000 字符上限（`max_chars`）。要看完整 README 用 raw 路径 + 调大 `max_chars`。
2. **私仓 API 401**：`https://api.github.com/repos/owner/private-repo` 不带 token 返回 404（注意是 404 不是 403，混淆）。需要 token 或走浏览器 shared 模式。
3. **目录树 SPA 化**：仓库根 / 子目录浏览（`/tree/<branch>/<path>`）现在用 React 渲染，web_fetch 拿不到完整文件列表。要列目录用 GitHub Trees API：`https://api.github.com/repos/<owner>/<repo>/git/trees/<sha>?recursive=1`。
4. **Issues / Discussions / PR comments 是动态加载**：长讨论列表分页 ajax，要用浏览器 + 滚动加载。
5. **默认分支不一定是 main**：老仓库还在 `master`。先调 API 拿 `default_branch` 字段。
6. **GitHub Pages 有自己的域名**：`<owner>.github.io/<repo>` 是 Pages 部署，不是仓库本身。

## 高价值字段（API）

| 字段 | API 路径 |
|---|---|
| 仓库基础信息 | `/repos/<owner>/<repo>` |
| README | `/repos/<owner>/<repo>/readme`（base64 encoded） |
| 文件树 | `/repos/<owner>/<repo>/git/trees/<sha>?recursive=1` |
| 最新 release | `/repos/<owner>/<repo>/releases/latest` |
| 贡献者 | `/repos/<owner>/<repo>/contributors` |
| 最近 commits | `/repos/<owner>/<repo>/commits?per_page=10` |

## 适用场景

读 README、查看仓库元数据、调研开源项目活跃度。私仓 / Settings / Issues 详情走浏览器 shared 模式。
