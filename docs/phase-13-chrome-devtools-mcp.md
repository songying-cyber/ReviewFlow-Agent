# 第 13 期开发任务：Chrome DevTools MCP（接入 + 适配 + UX 优化）

> 这份文档是给执行 Agent 的开发任务说明书，自包含、可直接照着推进。
>
> **开工前必读**：
> 1. 仓库根 `AGENTS.md`（仓库规则、文档联动硬规则）
> 2. `docs/phase-10-mcp-core.md`（MCP 协议核心，已完成）
> 3. `docs/phase-11-mcp-advanced.md`（MCP 高级能力，已完成）
> 4. `src/main/java/com/paicli/mcp/` 整套现有代码 —— 你只是在这套基础上增量配置 + 适配
> 5. `src/main/java/com/paicli/hitl/HitlToolRegistry.java`、`TerminalHitlHandler.java` —— HITL「server 维度全部放行」要改这两处
>
> **核心原则**：第 10/11 期已经做完 MCP 框架，本期**不写浏览器自动化逻辑**——直接接 Google 官方 `chrome-devtools-mcp` server，PaiCLI 这边只做"接入配置 + 适配层 + UX 优化"。

---

## 1. 目标与产出物

让 PaiCLI 能操控浏览器：处理 SPA / JS 渲染 / 防爬墙 / 表单交互 / 登录后页面。**直接接 Google 官方 `chrome-devtools-mcp@latest`**（v0.23.0+，2026 年 4 月仍在活跃维护，37k stars，28 个工具）。

最终交付：

- `~/.paicli/mcp.json` 默认包含 `chrome-devtools` server，**默认 enabled**
- `image` 类型 content 的 fallback 体验升级（路线 B：引导 LLM 优先用 `take_snapshot` 而非 `take_screenshot`）
- HITL「全部放行」改为 **server 维度**而非 tool 维度（避免连续浏览器操作触发 5+ 次审批）
- 系统提示词加「web_fetch vs 浏览器 MCP」决策表
- 启动延迟超时从 30s 提到 60s + 长启动 server 友好状态显示
- 端到端测试用例：微信公众号文章（web_fetch 拿不到、必须走浏览器）
- 文档联动（AGENTS.md / README.md / ROADMAP.md / .env.example）
- Banner 升到 `v13.0.0`，标语 `Browser-Capable Agent CLI`

---

## 2. chrome-devtools-mcp 概况

| 项 | 值 |
|---|---|
| 维护方 | **Google ChromeDevTools 官方** |
| 最新版本 | v0.23.0（2026-04） |
| 启动方式 | `npx -y chrome-devtools-mcp@latest` |
| 工具数 | **28**（导航 6 / 输入 9 / 调试 6 / 网络 2 / 性能 3 / 模拟 2 / 扩展 5 / 内存 1） |
| capabilities | 仅 tools，不暴露 resources / prompts |
| 浏览器 | 需本地装 Chrome（自动启 Chromium 实例，可隔离 user-data-dir） |
| 启动延迟 | npx 拉包 + Chrome 冷启 ≈ **15–30s** |
| remote-debugging | 原生支持 `--browser-url=http://127.0.0.1:9222`（**第 14 期 CDP 复用直接基于此**，不必重写 CDP 协议） |

**完整工具清单（28 个）**：

```
导航(6)：navigate_page  new_page  select_page  close_page  list_pages  wait_for
输入(9)：click  drag  fill  fill_form  handle_dialog  hover
        press_key  type_text  upload_file
调试(6)：take_screenshot  take_snapshot  evaluate_script
        list_console_messages  get_console_message  lighthouse_audit
网络(2)：list_network_requests  get_network_request
性能(3)：performance_start_trace  performance_stop_trace  performance_analyze_insight
模拟(2)：emulate  resize_page
扩展(5)：install_extension  uninstall_extension  reload_extension
        list_extensions  trigger_extension_action
内存(1)：take_memory_snapshot
```

注册到 PaiCLI 后命名为 `mcp__chrome-devtools__navigate_page` 等。

---

## 3. 关键设计决策（务必遵守）

### 3.1 默认 enabled

`chrome-devtools` server 默认开启。理由：
- 工具能力差异大，用户主要诉求就是「让 Agent 能上浏览器」，关闭等于把核心场景藏起来
- 用户可以 `/mcp disable chrome-devtools` 临时关掉
- 工具列表膨胀（28 + 32 ≈ 60 个）的代价 < 用户首次使用的体验阻力

### 3.2 image content fallback 路线 B（不做多模态）

第 13 期**不实现真 multimodal LLM 输入**。`image` 类型 content 处理保持当前 fallback：

```
[此工具返回了 image，请向用户描述结果]
```

但提示词改为**主动引导 LLM 优先用 `take_snapshot`**（DOM 文本快照）而非 `take_screenshot`（图片）。理由：
- take_snapshot 返回结构化 DOM 文本，LLM 直接能理解
- 90% 浏览器自动化场景（操作 SPA、读动态内容、提交表单）take_snapshot 就够
- screenshot 只在用户明确要看页面时才用，结果由 LLM 向用户口头描述
- 真 multimodal 涉及 LlmClient.Message 协议升级（content 从 String → List<ContentPart>），各 LlmClient 实现适配，工作量大，**留作独立期次（暂定第 17 期"多模态 LLM 输入"）**

### 3.3 HITL「全部放行」改为 server 维度

当前 `TerminalHitlHandler.approvedAllTools` 是按 tool name 维度的 Set，每个 MCP 工具都要单独放行一次。chrome-devtools 一次浏览器操作连续触发 4-5 个工具调用（navigate → wait_for → click → fill → click），HITL 弹 5 次太烦。

**改造**：
- `approvedAllTools` 变成两个维度：
  - `Set<String> approvedAllByTool`（保留兼容）
  - `Set<String> approvedAllByServer`（新加，按 mcp__{server}__ 前缀匹配）
- HITL 弹窗的 `a` 选项变成两级菜单或加一行说明：
  ```
  [a] 本次会话全部放行同类操作
      → 工具 mcp__chrome-devtools__click
      → server: chrome-devtools (推荐，连续浏览器操作只需确认一次)
  ```
- 用户按 `a` 进入子菜单选「按工具」还是「按 server」
- **Agent / SubAgent / PlanExecuteAgent 路径里 HITL 通过审批后** 在判断 isApprovedAll 时同时查两个 Set

接口大致：

```java
// ApprovalResult
public boolean isApprovedAllForTool() { ... }
public boolean isApprovedAllForServer() { ... }

// HitlToolRegistry.executeTool 内
if (handler.isApprovedAllByTool(toolName) || handler.isApprovedAllByServer(serverName(toolName))) {
    // 跳过弹窗
}
```

### 3.4 启动延迟与长启动 server 体验

chrome-devtools server 首次启动可能 30s+（npx 拉包 + Chrome 冷启）。当前问题：
- `McpServerManager` 的 startup summary 是同步等所有 server ready 才打印 → 用户看到长时间黑屏
- initialize 握手默认 30s 超时太紧

**改造**：
- `McpClient.initialize()` 超时从 30s 提到 60s（chrome-devtools 文档建议 Windows 20s+）
- `McpServerManager.startAll` 在并行启动期间打印 inline 进度：

```
🔌 启动 MCP server（4 个）...
   ✓ filesystem      stdio   14 工具    1.2s
   ⏳ chrome-devtools stdio   启动中...（首次需拉包 + 启动 Chrome，约 20s）
   ✓ zread           http     3 工具    0.8s
   ✓ everything      stdio   15 工具    2.4s
   ⏳ chrome-devtools stdio   启动中...（已等待 12s）
   ✓ chrome-devtools stdio   28 工具   18.5s
   4/4 就绪，共 60 个 MCP 工具
```

实现细节：startup 期间另起一个 status printer 线程，每 5s 检查并打印未就绪 server 的等待时长。

### 3.5 工具列表膨胀的处理

加上 chrome-devtools 后总工具数 ≈ 60，system prompt 里 tools schema 占用 8-10k token。第 13 期**不做特殊处理**（默认 enabled），但要在文档里告知用户：
- 用 `/mcp disable chrome-devtools` 临时关闭可瘦身
- 长上下文模型（GLM-5.1 200k / DeepSeek V4 1M）下完全无压力
- 短窗口模型（< 32k）下用户可自己决定

### 3.6 系统提示词升级

`Agent.SYSTEM_PROMPT` / `PlanExecuteAgent.EXECUTION_PROMPT` / `SubAgent.WORKER_PROMPT` 三处都加一段「web_fetch vs 浏览器 MCP」决策表：

```
工具选择 — 网页内容获取：
- 静态 / SSR 页面（博客、官方文档、wiki、GitHub README）→ web_fetch
- SPA / React / Vue / 客户端渲染、需要 JS 才有内容 → 浏览器 MCP（mcp__chrome-devtools__navigate_page + take_snapshot）
- 防爬墙、需要登录态、需要表单交互（点击/输入/提交）→ 浏览器 MCP
- 微信公众号文章 (mp.weixin.qq.com)、知乎专栏、推特、小红书等 → 浏览器 MCP（这些站点 web_fetch 拿不到正文）
- web_fetch 返回空正文（提示 SPA / 防爬墙）→ **自动 fallback 到浏览器 MCP**，不要重复 web_fetch
- 已知 URL → 直接 web_fetch 试一次，失败再上浏览器
- 用户要看页面长什么样、UI 验收 → take_screenshot（截图无法直接给 LLM 看，由 PaiCLI 附给用户）

工具选择 — 浏览器操作：
- 优先 mcp__chrome-devtools__take_snapshot（结构化 DOM 文本，LLM 能直接理解）
- 不要默认上 take_screenshot，除非用户明确要图
- 表单填写：mcp__chrome-devtools__fill_form 一次性填多字段，比逐个 fill 高效
- 等待异步加载：mcp__chrome-devtools__wait_for（指定文本或选择器出现）
- 控制台错误排查：list_console_messages
- 网络请求查看：list_network_requests + get_network_request
```

---

## 4. 配置文件改动

### 4.1 默认 `mcp.json` 模板

PaiCLI 启动时如果 `~/.paicli/mcp.json` 不存在，**自动创建**含 chrome-devtools 的最小模板。如果存在但缺 chrome-devtools 条目，启动时打印一行提示「检测到未配置 chrome-devtools，建议参考 README 添加」（不自动改用户文件）。

模板：

```json
{
  "mcpServers": {
    "chrome-devtools": {
      "command": "npx",
      "args": ["-y", "chrome-devtools-mcp@latest", "--isolated=true"]
    }
  }
}
```

`--isolated=true` 用临时 user-data-dir，避免污染日常 Chrome profile。第 14 期会改为 `--browser-url` 复用已开 Chrome。

### 4.2 `.env.example` 新增

```bash
# ========== Chrome DevTools MCP ==========
# 默认 isolated 模式（临时 user-data-dir）；想复用已开 Chrome 用第 14 期 /browser connect 命令
# 如果首次启动 npx 拉包慢，可调长 MCP initialize 超时：
# PAICLI_MCP_INITIALIZE_TIMEOUT_SECONDS=60
```

---

## 5. 与现有架构的集成点（要修改的文件）

**新增**：无新增类（直接接现成 server）

**修改**：

| 文件 | 改动 |
|---|---|
| `~/.paicli/mcp.json`（用户文件，启动时检测） | `Main.java` 启动时检测：文件不存在则创建默认含 chrome-devtools 的模板 |
| `src/main/java/com/paicli/mcp/McpClient.java` | initialize 超时 30s → 60s（或读 `paicli.mcp.initialize.timeout.seconds` 系统属性） |
| `src/main/java/com/paicli/mcp/McpServerManager.java` | startAll 期间另起 status printer 线程，每 5s 打印未就绪 server 的等待时长 |
| `src/main/java/com/paicli/hitl/TerminalHitlHandler.java` | `approvedAllTools` 拆成 `approvedAllByTool` + `approvedAllByServer` 两个 Set；`a` 选项弹子菜单 |
| `src/main/java/com/paicli/hitl/ApprovalResult.java` | 新增 `APPROVED_ALL_BY_SERVER` 枚举值 + `isApprovedAllForServer()` 判断 |
| `src/main/java/com/paicli/hitl/HitlToolRegistry.java` | `executeTool` 进入审批前先查两个 approvedAll 集合 |
| `src/main/java/com/paicli/mcp/protocol/McpCallToolResult.java` | image fallback 文案微调：`"[此工具返回了 image。如果用户没明确要看图，优先用 take_snapshot 工具拿 DOM 文本快照]"` |
| `src/main/java/com/paicli/agent/Agent.java` 系统提示词 | 加「web_fetch vs 浏览器 MCP」决策表 + take_snapshot 优先指引 |
| `src/main/java/com/paicli/agent/PlanExecuteAgent.java` 同上 | 同 |
| `src/main/java/com/paicli/agent/SubAgent.java` 同上 | 同 |
| `src/main/java/com/paicli/cli/Main.java` | Banner v12 → v13；标语 → `Browser-Capable Agent CLI`；启动 hint 加一条「输入 '/mcp restart chrome-devtools' 重启浏览器 server」 |

**联动文档**（按 AGENTS.md 5.x 硬规则）：
- `AGENTS.md`：项目快照里把第 13 期标已完成；新增「12. Chrome DevTools MCP」段说明默认 enabled、image fallback 策略、HITL 维度变更
- `README.md`：第十三期段落、配置示例、微信文章测试场景
- `ROADMAP.md`：第 13 期标 ✅；第 14 期范围保持不变；末尾状态行 → 进入第 14 期 CDP 会话复用
- `.env.example`：新增 `PAICLI_MCP_INITIALIZE_TIMEOUT_SECONDS` 示例

---

## 6. 用户体验

### 启动输出

```
✅ 已加载模型: glm-5.1 (zhipu)
🔌 启动 MCP server（5 个）...
   ✓ filesystem      stdio   14 工具    1.2s
   ✓ zread           http     3 工具    0.8s
   ✓ everything      stdio   15 工具    2.4s
   ⏳ chrome-devtools stdio   启动中...（首次需拉包 + 启动 Chrome）
   ⏳ chrome-devtools stdio   启动中...（已等待 8s）
   ⏳ chrome-devtools stdio   启动中...（已等待 16s）
   ✓ chrome-devtools stdio   28 工具   22.1s
   5/5 就绪，共 60 个 MCP 工具

🔄 使用 ReAct 模式
```

### 浏览器 HITL 体验

第一次浏览器操作弹审批：

```
─────── ⚠️ HITL 审批请求 ───────
工具: mcp__chrome-devtools__navigate_page
危险等级: 🟡 MCP
来源: chrome-devtools (Chrome DevTools 浏览器自动化)
风险: 将打开浏览器并访问外部网页

参数:
  url: https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg

[y / Enter] 批准本次操作
[a]        本次会话全部放行
           ├─ 仅本工具 (navigate_page)
           └─ 整个 chrome-devtools server（推荐，连续浏览器操作只需确认一次）
[n]        拒绝
[s]        跳过
[m]        修改参数
> a
> server      ← 用户选 server 维度
✅ 本次会话内 chrome-devtools 所有工具调用将自动放行
```

之后该会话内所有 `mcp__chrome-devtools__*` 不再弹窗。

---

## 7. 测试场景（端到端实测必跑）

### 7.1 微信公众号文章（web_fetch 必失败、浏览器必成功）

```
帮我看下 https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg 这篇文章讲了什么
```

**期望流程**：
1. LLM 先尝试 `web_fetch`（按提示词「已知 URL → 先 fetch 试」）
2. web_fetch 返回空正文 + 已知边界提示
3. LLM **自动** fallback 到 `mcp__chrome-devtools__navigate_page`（按提示词「web_fetch 拿到空正文 → 自动 fallback」）
4. HITL 弹窗审批，用户选 `a → server` 全放行
5. LLM 调 `take_snapshot` 拿 DOM 文本
6. 拿到正文后总结输出

**验收点**：
- web_fetch 路径走过且确实失败
- 浏览器路径接管，take_snapshot（**不是** screenshot）
- 用户能看到文章总结，不是「[image]」字样

### 7.2 SPA 页面读取

```
看下 https://react.dev 首页讲了什么
```

react.dev 是 React 官网，重 JS 渲染，web_fetch 拿到的内容稀少。期望走浏览器 MCP。

### 7.3 表单交互

```
用 chrome-devtools 打开 https://example.com，把 form 里的 name 字段填成"test"
```

验证 `fill` / `fill_form` 工具调用正确。

### 7.4 截图 fallback 行为

```
帮我用 chrome-devtools 截一张 https://www.google.com 的图
```

LLM 应该调 take_screenshot，但 PaiCLI 把 image fallback 给 LLM 后，LLM 应该输出"已为您截图，但当前模型无法直接查看图片，请向我描述要从图中找什么信息"或类似友好提示。

### 7.5 启动流程稳健性

- 故意改 mcp.json 把 chrome-devtools `command` 设成不存在的命令 → 看启动期间的进度输出 + 标 ERROR 但不阻塞其他 server
- 启动期间断网 → npx 拉包失败的 stderr 通过 `/mcp logs chrome-devtools` 能看到

### 7.6 HITL server 维度全放行

跑一个连续浏览器操作的提示：
```
打开 react.dev，等首页加载完，看一下控制台有没有错误，再截一张图
```

验证：选 `a → server` 后，后续 4-5 个工具调用都不再弹窗。

### 7.7 disable / enable

```
/mcp disable chrome-devtools
```

之后让 LLM 操作浏览器，应该明确告知用户「chrome-devtools 已禁用」。

```
/mcp enable chrome-devtools
```

重启该 server，工具重新可用。

---

## 8. 风险点

### 已知必踩的坑

1. **首次启动慢**：npx 第一次拉 chrome-devtools-mcp + 启 Chrome 可能 30s+。改 60s 超时 + 进度提示是必须的，否则用户以为卡死。
2. **HITL 维度改动影响既有测试**：`HitlToolRegistryTest` / `TerminalHitlHandlerTest` 现有的 approvedAll 用例需要更新 / 补全。
3. **image fallback 文案影响 LLM 决策**：当前 fallback 是中性提示，改成主动引导后要在端到端测试里观察 LLM 是不是真的更少调 screenshot 了。
4. **chrome-devtools 暴露的工具描述质量**：server 自报的 description 可能太长（GLM-5.1/DeepSeek 容忍但占 token）。`McpSchemaSanitizer.truncateDescription` 1000 字符上限对它够用，但要观察实际 token 占用。
5. **macOS 第一次跑 Chrome 弹权限**：用户首次可能看到「允许 chrome-devtools-mcp 访问辅助功能 / 屏幕录制」之类的系统弹窗，必须点「允许」否则失败。文档要明示。
6. **没有 Chrome 的环境**：如果用户机器没装 Chrome，启动会失败。文档明示「需要本地装 Chrome」。
7. **isolated 模式下 cookie 不持久**：`--isolated=true` 每次清空 user-data-dir，登录态不保留——这正是第 14 期要解决的，第 13 期接受这个边界。

### 已决策（不要再讨论）

- **chrome-devtools 默认 enabled**
- **不做真 multimodal**（拆到独立期次）
- **HITL 全放行 = server 维度优先**（保留 tool 维度兼容）
- **image fallback 走路线 B**（提示词引导 + take_snapshot 优先）
- **第 14 期范围保持原计划**（不缩减为"配置工程"）
- **不做 Playwright / Firefox 等跨浏览器**（chrome-devtools-mcp 已专精 Chrome）

---

## 9. 开发顺序（5 天工作量）

每天结束前 `mvn test` 全绿才进入下一天。

### Day 1：mcp.json 模板 + 启动期 UX

**产出**：
- `Main.java`：启动时检测 `~/.paicli/mcp.json`，不存在则用模板创建（含 chrome-devtools）
- `McpServerManager.startAll`：另起 status printer 线程；每 5s 打印未就绪 server 等待时长
- `McpClient.initialize`：超时从 30s 提到 60s，可被 `paicli.mcp.initialize.timeout.seconds` 系统属性覆盖
- 启动 banner 升 v13.0.0 + 标语

**测试**：
- `MainConfigBootstrapTest`：临时 home 目录无 mcp.json → 启动后文件被创建（2 用例）
- `McpServerManagerTest` 追加：长启动 server 期间 status printer 不阻塞（mock client，1 用例）

### Day 2：HITL server 维度全放行（最大改动）

**产出**：
- `ApprovalResult` 加 `APPROVED_ALL_BY_SERVER` 枚举 + `isApprovedAllForServer()`
- `TerminalHitlHandler.approvedAllTools` 拆成两个 Set；`a` 选项进子菜单选 tool / server
- `HitlToolRegistry.executeTool` 入口先查两个 approvedAll 集合
- 子菜单输入：`tool`（默认）/ `server` / 直接 Enter（默认 tool 维度）

**测试**：
- `ApprovalResultTest` 追加：APPROVED_ALL_BY_SERVER 路径（2 用例）
- `TerminalHitlHandlerTest` 追加：`a → server` 子菜单解析（2 用例）
- `HitlToolRegistryTest` 追加：approvedAllByServer 命中后 mcp__server__* 工具不再弹窗（2 用例）

### Day 3：image fallback + 系统提示词

**产出**：
- `McpCallToolResult.formatForLlm` 修改 image fallback 文案：「优先用 take_snapshot 拿 DOM 文本，截图用户无法直接看」
- `Agent.SYSTEM_PROMPT` / `PlanExecuteAgent.EXECUTION_PROMPT` / `SubAgent.WORKER_PROMPT` 三处加「web_fetch vs 浏览器 MCP」决策表（§3.6）
- 提示词里明示微信公众号 / 知乎 / 推特 / 小红书等典型 web_fetch 失败站点直接走浏览器

**测试**：
- 提示词改动不写单测（无法机械验证 LLM 行为），但端到端测试（Day 5）必须覆盖

### Day 4：默认配置 + .env + 文档

**产出**：
- 默认 `mcp.json` 模板包含 chrome-devtools（§4.1）
- `.env.example` 加 `PAICLI_MCP_INITIALIZE_TIMEOUT_SECONDS` 示例
- `AGENTS.md` 新增「12. Chrome DevTools MCP」段
- `README.md` 加第十三期段落 + 配置示例 + 微信文章测试场景描述
- `ROADMAP.md` 第 13 期标 ✅，末尾状态行更新为「下一步进入第 14 期 CDP 会话复用」

### Day 5：端到端实测 + 边界用例

**手测清单**（必跑，结果写到 commit description）：

1. ✅ 启动 PaiCLI，看到 chrome-devtools 启动进度提示，最终 ready
2. ✅ 跑 §7.1 微信文章场景：web_fetch 失败 → fallback 浏览器 → take_snapshot 拿正文 → 总结
3. ✅ 跑 §7.2 SPA 页面（react.dev）
4. ✅ 跑 §7.3 表单填充（fill_form）
5. ✅ 跑 §7.4 截图 fallback 文案是否生效
6. ✅ 跑 §7.5 故障路径：错误 command / 缺 Chrome / 断网
7. ✅ 跑 §7.6 HITL server 全放行
8. ✅ 跑 §7.7 disable / enable
9. ✅ 启动总耗时记录：首次冷启 vs 第二次（npx 缓存命中）
10. ✅ Ctrl+D 退出后 `ps aux | grep chrome` 确认无残留 Chromium 进程

任何一项 fail 就回 Day 1-4 修。手测过完才能 commit。

---

## 10. 测试策略

### 单测覆盖下限

- `ApprovalResultTest` 追加 ≥ 2 用例（APPROVED_ALL_BY_SERVER）
- `TerminalHitlHandlerTest` 追加 ≥ 2 用例（子菜单解析）
- `HitlToolRegistryTest` 追加 ≥ 2 用例（approvedAllByServer 命中）
- `MainConfigBootstrapTest` 新建 ≥ 2 用例（mcp.json 模板创建）
- `McpServerManagerTest` 追加 ≥ 1 用例（status printer 不阻塞）

### 集成测（可选）

- 标记 `@EnabledIfEnvironmentVariable("CHROME_DEVTOOLS_INTEGRATION_TEST", "true")`
- CI 默认不跑，本地手测覆盖

### 端到端（Day 5 手测清单 10 条）

必跑，结果写到 commit description 或 PR body。

---

## 11. 明确不做（留给后续期次）

- **真 multimodal LLM 输入**（Message.content 升级为 List<ContentPart>，含 image_base64 / image_url；各 LlmClient 适配 vision API）→ **拆到独立第 17 期「多模态 LLM 输入」**
- **CDP 会话复用 / 登录态访问**（chrome-devtools-mcp 已原生支持 `--browser-url`，但接入 + 登录态识别 + 敏感页面 HITL 留给第 14 期）
- **Playwright / Firefox / WebKit 跨浏览器** —— chrome-devtools-mcp 专精 Chrome，不并行
- **浏览器自动化 DSL / 工作流**（连续操作打包成可复用 Skill 留第 15 期）

实现过程中如果发现某条不做的功能其实绕不过去，**先停下来回上游确认**，不要擅自扩展范围。

---

## 12. Banner 版本

完成第 13 期后：
- `Main.java` `VERSION` = `"13.0.0"`
- 类注释：第 13 期新增 chrome-devtools MCP server 接入、HITL server 维度全放行、image fallback 引导
- Banner 标语：`Browser-Capable Agent CLI`

---

## 13. 完成判定（DoD）

- [ ] §5 列出的所有现有文件都按要求修改
- [ ] `~/.paicli/mcp.json` 不存在时启动自动创建（含 chrome-devtools）
- [ ] HITL `a` 选项支持子菜单选 tool / server 两种粒度
- [ ] image content fallback 文案改为引导 take_snapshot
- [ ] 三处 Agent 系统提示词都加了 web_fetch vs 浏览器 MCP 决策表
- [ ] `mvn test` 全绿（含原 351 用例 + 新增）
- [ ] §9 Day 5 手测 10 条全部跑过，结果写入 commit message
- [ ] §5 所有文档联动完成
- [ ] Banner 升 v13.0.0
- [ ] commit message 格式 + Co-Authored-By

---

## 14. 提交规约

- **不要**自行 `git push`
- 一次性 commit，message 用 heredoc
- commit 前 `git status` 确认无误改动 / 无敏感信息
- 末尾加：

```
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

---

## 15. Pre-flight 决策（已替你决定，不要再讨论）

| 问题 | 决策 |
|---|---|
| Chrome DevTools MCP server 选哪个 | Google 官方 `chrome-devtools-mcp@latest` |
| 默认 enabled / disabled | **enabled** |
| image content 处理 | 路线 B：fallback 文案引导 take_snapshot 优先；不做 multimodal |
| 多模态 LLM 输入 | **拆到独立第 17 期**，本期不做 |
| HITL「全部放行」维度 | 改 server 维度，但保留 tool 维度兼容；用户选 `a` 时进子菜单 |
| 第 14 期范围 | **保持原计划**（CDP 会话复用 + 登录态识别），不缩减 |
| 启动延迟容忍 | initialize 超时 60s + 进度打印 |
| 工具数膨胀（28 + 32 = 60）| 接受，不做特殊瘦身；用户可 `/mcp disable` |
| 跨浏览器（Firefox / WebKit） | 不做 |
| 浏览器执行隔离 | 默认 `--isolated=true`，第 14 期改 `--browser-url` |

---

## 16. 必跑测试用例：微信公众号文章

这是**第 13 期最关键的端到端用例**，因为它同时验证：
- web_fetch 真的拿不到（不是猜的，是实测）
- chrome-devtools 真能拿到
- LLM 自动 fallback 决策走通
- HITL server 维度全放行体验

**测试 URL**：
```
https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg
```

**提示词**：
```
帮我看下 https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg 这篇文章讲了什么
```

**期望行为序列**：
1. LLM 先调 web_fetch（按提示词「已知 URL → 先 fetch」）
2. web_fetch 返回 `body_empty: true` + 已知边界提示
3. LLM 看到提示后立即调 `mcp__chrome-devtools__navigate_page` 给微信 URL
4. HITL 弹窗，用户选 `a → server`
5. LLM 调 `mcp__chrome-devtools__wait_for`（等 article 容器加载）
6. LLM 调 `mcp__chrome-devtools__take_snapshot` 拿正文 DOM
7. LLM 输出文章总结给用户

**失败模式**（任一出现都算 Day 5 不通过，回 Day 3 重调提示词）：
- LLM 在 web_fetch 失败后告知用户「无法读取」就放弃，**没自动 fallback**
- LLM 调用了 `take_screenshot` 而不是 `take_snapshot`
- HITL 连续弹 5 次而不是一次

---

如果有任何疑问，回到上游问，不要自行推断。祝顺利。
