---
name: web-access
description: |
  所有联网与浏览器操作的决策手册：搜索、网页抓取、读 SPA / 防爬墙站点、访问需要登录的页面、抓取社交媒体内容（微信公众号、知乎专栏、Twitter、小红书等）。
  触发场景：用户要求搜索信息、阅读网页、调研话题、看公众号 / 知乎文章、读 GitHub 仓库内容、查看登录后的页面、抓取动态渲染站点。先调 load_skill 再决定用哪条工具链。
version: "1.0.0"
author: PaiCLI
tags: [web, browser, fetch]
---

# web-access Skill

## 浏览哲学

**像人一样思考，带着目标进入，边看边判断。**

1. **明确目标**：用户到底要什么？是要事实结论、要正文摘要、要决策依据，还是要操作完成某个动作？把"成功标准"写下来再动手。
2. **选择起点**：根据任务性质和已有线索（URL / 关键词 / 平台），选最可能直达的一个工具试一次。先不堆方案。
3. **过程校验**：每一步的结果都是证据。失败有失败的信息（404、登录页、空正文、防爬提示），用证据更新判断，**不要在同一条工具上反复重试**。
4. **完成判断**：拿到目标内容就停，不为"完整"而过度操作。一个高质量摘要 > 抓十个不相关页面。

## 工具选择表

| 场景 | 首选 | 备选 / fallback |
|---|---|---|
| 搜索关键词、找入口 | `web_search` | — |
| URL 已知，目标是正文（博客 / 官方文档 / GitHub README） | `web_fetch` | `r.jina.ai/<url>` 见 §Jina 兜底 |
| URL 已知但 web_fetch 返回空正文 / SPA 提示 | `mcp__chrome-devtools__navigate_page` + `take_snapshot` | — |
| 微信公众号 / 知乎专栏 / Twitter / 小红书 | 直接走 chrome-devtools MCP | 不要先 web_fetch（90% 失败） |
| 需要登录态（GitHub 私仓、内部系统、邮箱） | `/browser connect` 切 shared 后再操作 | — |
| 表单交互（点击 / 填写 / 提交） | `mcp__chrome-devtools__click` / `fill` / `fill_form` | — |
| 用户明确要看页面截图 | `take_screenshot` | vision 模型可看图；非 vision 模型只能拿到 fallback 文案 |

## 浏览器优先级

```
1. web_fetch（先试一次）
       ↓ 失败 / 空正文 / 防爬墙提示
2. mcp__chrome-devtools__navigate_page（isolated 模式，无登录态）
       ↓ 拿到登录页 / 内容仍缺
3. /browser connect 切 shared 模式（用户已登录的 Chrome）
       ↓ 仍拿不到
4. 最后兜底：r.jina.ai/<url> 走 execute_command + curl
```

**关键约束**：
- shared 模式下敏感页面（settings / admin / billing / oauth / 2fa 等）的改写型工具会强制单步审批，不能批量放行。
- `close_page` 只能关 PaiCLI 自己 `new_page` 出来的 tab，不要尝试关用户原有的 Gmail / Slack。
- 浏览器读页面优先 `take_snapshot`（结构化 DOM 文本）而非 `take_screenshot`。截图只在用户明确要看视觉布局、颜色、遮挡、截图验收时使用；vision 模型会收到图片，非 vision 模型仍只能走 fallback 文案。

## Jina Reader 兜底

`web_fetch` 拿不到正文（SPA / 防爬墙 / 反爬虫）时，可让 Agent 调 `execute_command` 跑：

```bash
curl -s 'https://r.jina.ai/https://example.com/article'
```

返回干净 markdown，免登录。**约束**：免费配额 20 RPM，不要批量刷；命中防爬严重的站点（小红书 / 微信文章详情）也会失败，最后还是回到浏览器 MCP。

## 登录态判断

**核心问题只有一个：目标内容拿到了吗？**

- 先打开页面尝试拿目标（不要先猜要不要登录）
- 拿到 → 完事
- 拿到的是登录提示 / 空内容 / 跳转登录页 → 才告知用户需要 `/browser connect`
- 不要在用户没要求时主动切 shared 模式，shared 模式有真实账户操作风险

## 站点经验目录

`references/site-patterns/<domain>.md` 按域名累积已知陷阱与有效模式。

**用法**：确定目标站点后先列目录看有没有命中：

```bash
ls ~/.paicli/skills-cache/web-access/references/site-patterns/
```

命中的话先 `read_file` 读对应文件，把"已知陷阱"和"有效模式"作为先验。已有：

- `mp.weixin.qq.com.md`（微信公众号文章）
- `zhuanlan.zhihu.com.md`（知乎专栏）
- `x.com.md`（Twitter / X）
- `xiaohongshu.com.md`（小红书）
- `github.com.md`（GitHub）
- `juejin.cn.md`（掘金）

**写回机制**：操作中如果发现新站点的陷阱或有效模式，主动写到 `~/.paicli/skills/web-access/references/site-patterns/<domain>.md`（用户级目录，不要写 jar 内置缓存），格式参考已有文件。

## CDP 工具速查

完整 28 个 chrome-devtools MCP 工具速查见 `references/cdp-cheatsheet.md`。常用流：

```
navigate_page → wait_for（等关键元素出现）→ take_snapshot → 抽取
                                            ↘ click / fill / fill_form（操作）
```

## 并行调研

任务包含多个**独立**调研目标时（如同时调研 3 个公众号），可以拆给 sub-agent 并行：让 PlanExecuteAgent 把任务拆成 DAG 平行批次，或用 Multi-Agent 模式（`/team`）分给多个 worker。**注意**：浏览器实例只有一个，浏览器操作仍要串行；只有 `web_fetch` / `web_search` 这种纯 HTTP 调用才能真并行。

## 不要做的事

- 不要在 SPA 站点反复 `web_fetch`：第一次空了就换浏览器，别浪费配额
- 不要默认 `take_screenshot`：截图比 DOM 文本更贵，先用 snapshot；只有视觉问题再截图
- 不要为了"全面"在敏感页面批量操作：每个改写型操作都强制审批，会卡住流程
- 不要替用户输用户名密码：PaiCLI 不做自动登录
- 不要把 references/ 写回 jar 缓存目录：写到 `~/.paicli/skills/web-access/references/site-patterns/` 用户级目录
