# PaiCLI

一个成熟的 Java Agent CLI 产品，对标 Claude Code 作者为沉默王二，从第一期的 `ReAct` 单代理循环逐步演进到第十六期的 `TUI 产品化`。

当前进度：已完成第 16.1 期 inline 流式 TUI 形态修正、第 17 期 `LSP 诊断注入` MVP，并开始落地第 18 期 `Git Side-History 快照与回滚`。第 19–20 期依次推进 Prompt 分层架构、异步后台任务 + Runtime API，第 21 期再做 `多模态 LLM 输入（vision）`。

## 测试策略

日常开发不需要每次都跑全量测试。推荐按改动范围选择：

```bash
# 第 16 期终端 / TUI / inline renderer 冒烟
mvn test -Pphase16-smoke

# 常规快速回归，跳过外部进程 / 网络超时 / 命令超时类慢测试
mvn test -Pquick

# 发版或大范围重构前再跑全量
mvn test
```

## 演进历程

### 第一期：ReAct Agent CLI

- 单轮对话驱动的 `ReAct` 循环
- 支持工具调用：读文件、写文件、列目录、执行命令、创建项目、代码语义检索、联网搜索、MCP 动态工具
- 更适合简单任务或单步操作

### 第二期：Plan-and-Execute + DAG

- 在保留 `ReAct` 模式的基础上新增复杂任务规划能力
- 支持先拆解任务，再按照依赖顺序执行
- 新增 `/plan` 入口，以一次性计划执行方式增强默认的 `ReAct`
- 计划生成后，会先与用户确认再执行
- 更适合多步骤、带依赖关系的复杂任务

### 第三期：Memory + 上下文工程

- 短期记忆管理当前对话与工具结果
- 长期记忆通过 `/save <事实>` 或用户明确说“记一下 / 记住”时的 `save_memory` 保存关键事实，跨会话复用
- 注入给模型的相关记忆只使用长期稳定事实，不把当前轮短期对话误当成“历史记忆”
- 对话接近预算时自动做摘要压缩
- 新增 `/memory` 查看状态、`/memory clear` 清空长期记忆、`/save` 手动保存事实；Agent 在用户明确说“记一下 / 记住”时可调用 `save_memory`

### 第四期：RAG 检索 + 代码库理解

- 代码向量化（Embedding），支持本地 Ollama 和远程 API
- SQLite 持久化 + 余弦相似度语义检索
- 代码分块（文件/类/方法粒度）与 AST 解析
- 代码关系图谱（extends/implements/imports/calls/contains）
- 新增 `/index`、`/search`、`/graph` CLI 命令
- Agent 自动调用 `search_code` 工具理解代码库

### 第五期：Multi-Agent 协作 + 角色分工

- 三个角色：规划者（Planner）、执行者（Worker）、检查者（Reviewer）
- 主从架构：编排器（Orchestrator）协调子代理（SubAgent）
- 规划者拆解任务 -> 执行者执行 -> 检查者审查质量
- 审查未通过时带反馈重试（最多 2 次），冲突自动解决
- 新增 `/team` CLI 命令，进入多 Agent 协作模式

### 第六期：Human-in-the-Loop + 审批流

- 危险操作静态规则识别：`write_file`、`execute_command`、`create_project`、`revert_turn`
- 三级危险等级：高危（`execute_command`）、中危（`write_file` / `create_project`）
- 审批决策：批准 / 全部放行 / 拒绝 / 跳过 / 修改参数后执行
- HITL 默认关闭，通过 `/hitl on` 启用
- 新增 `/hitl` CLI 命令，支持 `/hitl on`、`/hitl off`、`/hitl`（查看状态）

### 第七期：异步执行 + 并行工具调用

- 同一轮 LLM 返回多个 `tool_calls` 时，工具层会并行执行
- ReAct、Plan-and-Execute、Multi-Agent Worker 都复用统一的批量工具执行入口
- 工具结果仍按原始 `tool_call` 顺序回灌，保证消息历史协议稳定
- 批量工具调用有统一超时与取消兜底，单个 `execute_command` 仍保留 60 秒命令级超时
- Plan-and-Execute 与 Multi-Agent 已支持按依赖批次并行执行独立任务

### 第八期：多模型适配 + 运行时切换

- `LlmClient` 接口抽象 + `AbstractOpenAiCompatibleClient` 模板基类
- 内置 `GLMClient`、`DeepSeekClient`、`StepClient` 三个瘦实现
- `/model glm` / `/model deepseek` / `/model step` 运行时切换当前对话模型
- 配置持久化到 `~/.paicli/config.json`，API Key 可从配置、环境变量或 `.env` 读取

### 第九期：联网能力 + Web 工具

- `web_search` 抽象成 `SearchProvider` 接口，内置三个实现：智谱 Web Search（默认，与 GLM 共用 Key，0.01–0.05 元/次）、SerpAPI（国际通用付费）、SearXNG（开源自托管免费）
- `web_fetch` 新工具：URL → OkHttp 抓取 → Jsoup 解析 → 简易 readability → Markdown 正文
- 默认安全策略：屏蔽 `file://` / 内网 / loopback；30 秒超时；5MB 响应上限；每分钟 30 次限流
- 边界明确：SPA / 防爬墙站点会返回空正文 + 已知边界提示，Agent 会 fallback 到浏览器 MCP 路线

### 第十期：MCP 协议核心

- 新增 `com.paicli.mcp` 模块，支持 stdio 子进程 server 与 Streamable HTTP 远程 server
- 启动时读取 `~/.paicli/mcp.json` 与 `.paicli/mcp.json`，项目级配置按 server 名覆盖用户级配置
- MCP 工具自动注册为 `mcp__{server}__{tool}`，参数 schema 会清洗 `$ref` / `anyOf` / 超长 description，降低模型调用失败率
- 所有 MCP 工具默认走 HITL 审批和审计，审计参数会脱敏 token / key / password / Authorization / Bearer 凭证
- 支持 MCP resources：server 声明 `resources` capability 后，自动注册 `mcp__{server}__list_resources` / `mcp__{server}__read_resource` 虚拟工具
- 普通输入支持 `@server:protocol://path` 显式引用 resource，提交给 Agent 前展开为 `<resource>` 内联块
- 被动处理 `notifications/tools/list_changed`、`notifications/resources/list_changed`、`notifications/resources/updated`
- 运行中输入 `/cancel` 并回车可请求取消当前 Agent run
- CLI 命令：`/mcp`、`/mcp restart <name>`、`/mcp logs <name>`、`/mcp disable <name>`、`/mcp enable <name>`、`/mcp resources <name>`、`/mcp prompts <name>`
- `~/.paicli/mcp.json` 不存在时会自动创建默认 chrome-devtools 配置；项目级 `.paicli/mcp.json` 仍可按 server 名覆盖

### 第十二期：长上下文工程

- `LlmClient` 声明模型能力：`maxContextWindow()`、`supportsPromptCaching()`、`promptCacheMode()`
- GLM-5.1 默认 200k window，DeepSeek V4 默认 1M window，StepFun 默认 256k window
- `AgentBudget` 按当前模型动态计算预算，默认 `80% * maxContextWindow`，仍可用系统属性覆盖
- short / balanced / long 三种上下文模式：长上下文模式跳过摘要压缩，RAG 默认 topK 提升到 20
- `search_code` 未显式传 `top_k` 时按上下文模式自适应
- 长上下文模式下自动把 MCP resources 的 URI / 描述索引注入 system prompt，不自动注入正文
- Token 输出显示 window、动态预算、cached input tokens 和估算成本
- `/context` 会显示当前上下文模式、prompt cache 模式、RAG topK、resources 自动索引状态

### 第十三期：Chrome DevTools MCP

- 默认接入 Google 官方 `chrome-devtools-mcp@latest`，注册为 `mcp__chrome-devtools__navigate_page`、`take_snapshot`、`click`、`fill_form` 等浏览器工具
- `~/.paicli/mcp.json` 不存在时启动自动创建模板，默认使用 `--isolated=true` 临时浏览器 profile
- 用于处理 SPA / JS 渲染 / 防爬墙 / 表单交互页面；微信公众号文章、知乎专栏、推特、小红书等 `web_fetch` 失败站点会引导走浏览器 MCP
- HITL 的“全部放行”支持 MCP server 维度，连续浏览器操作可对 `chrome-devtools` 一次确认
- `image` 类型结果仍不进入多模态模型输入（已后移到第 21 期 vision），fallback 文案会引导优先使用 `take_snapshot` 获取 DOM 文本
- MCP initialize 默认超时提升到 60 秒，并在长启动期间打印等待进度

### 第十四期：CDP 会话复用 + 登录态访问

- 新增 `/browser status`、`/browser connect [port]`、`/browser disconnect`、`/browser tabs` 命令组，并给 Agent 暴露内部 `browser_connect` / `browser_disconnect` / `browser_status` 工具
- 默认仍使用 `--isolated=true` 临时浏览器 profile；执行 `/browser connect` 后，运行时把 `chrome-devtools` 切到 `--autoConnect`，复用已在 `chrome://inspect/#remote-debugging` 允许远程调试的登录态 Chrome
- Agent 遇到登录页、权限不足或明确需要登录态页面时，会先调用 `browser_connect` 自动切到 shared；公开页面如微信公众号文章不提前切换
- `/browser connect <port>` 保留旧式 CDP 端口兼容路径：先探活 `127.0.0.1:<port>/json/version`，成功后切到 `--browser-url=http://127.0.0.1:<port>`；失败时不会改 MCP 启动参数，并输出 macOS / Windows / Linux 的 Chrome 启动命令
- 切换 shared / isolated 模式都会清空 `chrome-devtools` 的 server 维度全部放行，避免旧信任跨模式延续
- shared 模式下 `close_page` 只能关闭 PaiCLI 自己创建的 tab；无法证明是 PaiCLI 创建的 tab 会被策略层拒绝
- 敏感页面命中规则后，`click` / `fill_form` / `evaluate_script` 等改写型浏览器工具必须单步 HITL 审批，不复用全部放行；读型工具如 `take_snapshot` 仍可继续使用
- 审计日志为 chrome-devtools 工具追加可选浏览器 metadata：`browser_mode`、`sensitive`、`target_url`，旧格式 JSONL 仍可读取

### 第十五期：Skill 系统 + 内置 web-access skill

把"Agent 该怎么思考"从硬编码 system prompt 抽出，沉淀成可复用单元。每个 Skill 是一个目录：`SKILL.md`（决策手册）+ `references/`（按需读取）+ 可选 `scripts/`（可执行依赖）。

- 三层加载位置（按优先级，后者整体覆盖同名 skill）：jar 内置 < 用户级 `~/.paicli/skills/<name>/` < 项目级 `<project>/.paicli/skills/<name>/`
- 启动期把启用 skill 的 `name` + `description` 注入三处 Agent 系统提示词索引段（启用上限 20 个，索引段 ≤ 4KB）
- 内置工具 `load_skill(name)`：LLM 在 system prompt 看到匹配 description 时主动调用，PaiCLI 把 SKILL.md 正文（5KB 截断）写入 `SkillContextBuffer`，下一轮 user message 自动前置注入
- 内置 web-access skill：决策手册（浏览哲学四步法 + 工具选择表 + 浏览器优先级 + Jina 兜底说明）+ 6 个站点经验文件（mp.weixin / zhuanlan.zhihu / x.com / xiaohongshu / github / juejin）+ cdp-cheatsheet
- frontmatter 走手写 YAML 子集解析，不引 SnakeYAML；解析失败 stderr 警告但不阻塞启动
- CLI 命令：`/skill list` / `/skill show <name>` / `/skill on <name>` / `/skill off <name>` / `/skill reload`
- 启用状态持久化：`~/.paicli/skills.json` 的 `disabled` 列表，默认全启用
- 与 HITL 协同：Skill 内调用 `execute_command` 等危险工具仍走既有 HITL 审批，沿用 `execute_command` 工具维度全放行；不给 Skill 单独审批维度

设计意图：从「写工具」演进到「打包专家手册」。当工具堆成山（PaiCLI 当前内置 9 个 + MCP 60+ 工具），用 Skill 给 LLM 一份按场景展开的"专家手册"，比往 system prompt 里塞更多规则更可扩展。

### 第十六期：TUI 产品化（v16.1 形态修正后：双形态可切换）

v16.1 抽出 `Renderer` 接口 + 三个实现：

| 形态 | 启用方式 | 视觉风格 |
|---|---|---|
| **inline 流式 TUI**（默认） | 直接运行 / `PAICLI_RENDERER=inline` | Claude Code 风格：主屏直出、底部 DECSTBM 状态栏、行内可折叠工具块（`Read 3 files (ctrl+o to expand)`）、行内 git diff、HITL 单字符 `[y/n/a/s/m]` 提示 |
| **lanterna 全屏 TUI** | `PAICLI_RENDERER=lanterna`（或兼容旧 `PAICLI_TUI=true`） | v16 三栏全屏：文件树 + 对话流 + 状态栏 + 底部输入栏，HITL 模态弹窗 |
| **plain 兜底** | `PAICLI_RENDERER=plain` | 纯 println，无折叠 / 状态栏，等价 v15 行为 |

- 三种形态共享同一套 `Agent` / `ToolRegistry` / `MemoryManager` / MCP server / SkillRegistry / HITL handler，不创建孤立空会话
- 普通输入走 ReAct；`/plan <任务>` 走 Plan-and-Execute；`/team <任务>` 走 Multi-Agent；`/cancel` 可取消运行中任务
- 通用命令：`/clear`、`/context`、`/memory`、`/memory clear`、`/save <事实>`、`/hitl`、`/hitl on`、`/hitl off`、`/config`、`/exit`
- 对话历史保存到 `~/.paicli/history/session_*.jsonl`
- 兼容旧设置：`PAICLI_TUI=true` 自动映射为 `PAICLI_RENDERER=lanterna`（已 deprecated）
- `PAICLI_NO_STATUSBAR=true` 在 inline 模式下禁用底部状态栏（不支持 DECSTBM 的终端）
- `NO_COLOR=1` 禁用所有 ANSI 颜色，保留布局

### 第十七期：LSP 诊断注入（MVP）

- `write_file` 成功后触发 post-edit 诊断，诊断结果不会阻塞工具主流程
- 当前 MVP 对 Java 文件使用 JavaParser 做轻量语法诊断，不依赖本机安装 JDT LS
- ReAct、Plan-and-Execute、Multi-Agent 三条路径都会在下一轮 LLM 请求前注入 pending 诊断
- 诊断按 error / warning / info、文件、行列号、message 格式化，默认最多注入 20 条
- 配置：`PAICLI_LSP_ENABLED=false` 可关闭，`PAICLI_LSP_MAX_DIAGNOSTICS=20` 可调整注入上限
- 后续增强：接入 JDT LS / rust-analyzer / pyright / gopls 的 stdio JSON-RPC transport

### 第十八期：Git Side-History 快照与回滚（MVP）

- 每个 ReAct / Plan / Team turn 开始前创建 `pre-turn` 快照，结束后异步创建 `post-turn` 快照
- 快照仓库使用 JGit 纯 Java 实现，默认位于 `~/.paicli/snapshots/<project_hash>/<worktree_hash>/.git`，不写用户项目 `.git`
- `/snapshot` 查看最近快照，`/snapshot status` 查看配置与 side-git 目录，`/snapshot clean` 清理当前项目快照目录
- `/restore <N>` 恢复到最近第 N 个 `pre-turn` 快照；恢复前会先创建 `pre-restore` 快照
- Agent 内置 `revert_turn` 工具，纳入 HITL 与 AuditLog 危险工具链
- 配置：`PAICLI_SNAPSHOT_ENABLED=false` 可关闭，`PAICLI_SNAPSHOT_MAX=50`、`PAICLI_SNAPSHOT_EXCLUDES=...`、`PAICLI_SNAPSHOT_DIR=...` 可调整策略

### 第六期 HITL 增强（路径围栏 / 命令快速拒绝 / 操作审计）

`com.paicli.policy` 包，作为 HITL 之外的辅助层（不是沙箱、不提供进程隔离）：

- `PathGuard` 路径围栏：文件类工具强制限定在项目根之内，拦截绝对路径外逃 / `..` 穿越 / 符号链接逃逸
- `CommandGuard` 命令快速拒绝：HITL 之前的 fast-fail 黑名单（`sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh` / `find /` / `chmod 777 /` / `shutdown`），减少 HITL 弹窗骚扰
- `AuditLog` 结构化审计：危险工具调用按天写 JSONL 到 `~/.paicli/audit/`，含 `outcome (allow|deny|error)` 与 `approver (hitl|policy|none)`；`revert_turn` 也纳入危险工具链
- `write_file` 单文件 5MB 上限
- CLI 命令：`/policy` 查看安全策略状态、`/audit [N]` 看最近 N 条审计

**为什么不叫沙箱**：本地 Agent CLI（参考 Claude Code / Cursor / Aider）默认都不做容器/VM 沙箱——沙箱削弱 Agent 能力、给虚假安全感、体验更差。生产级 Agent 沙箱实际是 microVM-level（Devin / Modal / Anthropic Computer Use 用 Firecracker / gVisor）。PaiCLI 的安全模型是 **HITL + 路径校验 + 命令快速拒绝 + 审计**，不是隔离。

## 启动界面

### 当前启动界面

当前启动输出以命令行实际产物为准：

```text
╔══════════════════════════════════════════════════════════╗
║                                                          ║
║   ██████╗  █████╗ ██╗ ██████╗██╗     ██╗                ║
║   ██╔══██╗██╔══██╗██║██╔════╝██║     ██║                ║
║   ██████╔╝███████║██║██║     ██║     ██║                ║
║   ██╔═══╝ ██╔══██║██║██║     ██║     ██║                ║
║   ██║     ██║  ██║██║╚██████╗███████╗██║                ║
║   ╚═╝     ╚═╝  ╚═╝╚═╝ ╚═════╝╚══════╝╚═╝                ║
║                                                          ║
║      Terminal-First Agent IDE v16.1.0                ║
║                                                          ║
╚══════════════════════════════════════════════════════════╝

🔄 使用 ReAct 模式
```

## 功能

### 第一期

- 🤖 基于 GLM-5.1 的智能对话
- 🔄 ReAct Agent 循环（思考-行动-观察）
- 🛠️ 工具调用（文件操作、Shell命令、项目创建、代码语义检索、联网搜索、MCP 动态工具）
- 💬 交互式命令行界面
- 🧠 默认通过流式接口获取模型输出；ReAct 与用户可见的 Plan 阶段都会按流式展示思考过程与最终回复；ReAct 同一次用户输入只打印一次 `🧠 思考过程` 标题，工具调用前后的后续推理继续归在同一块下
- 🖥️ 终端会对常见 Markdown（标题、列表、表格、代码块）做渲染后再显示，避免直接暴露原始标记符号

### 第二期

- 📋 Plan-and-Execute + DAG 任务拆解与顺序执行
- ⌨️ `/plan` 一次性进入计划执行
- 🧭 更清晰的复杂任务执行顺序与依赖展示
- ⚖️ 简单任务会自动生成最小计划，不再为了凑步数扩展无关步骤

### 第三期

- 🧠 短期记忆、长期记忆与相关记忆检索
- 📦 长对话摘要压缩与 Token 预算管理
- 🧮 长上下文动态预算、prompt cache 可见化与成本估算
- 💾 `/memory` 与 `/save` 记忆管理入口

### 第四期

- 🔍 代码库语义检索（自然语言搜代码）
- 🕸️ 代码关系图谱（类继承、接口实现、方法调用）
- 📡 本地 Ollama Embedding + 远程 API 可配置
- 🗃️ SQLite 向量存储与持久化

### 第五期

- 👥 多 Agent 协作（规划者 + 执行者 + 检查者）
- 🎯 主从架构编排器自动分配任务
- 🔍 检查者审查质量，未通过自动重试
- 🛠️ 执行者共享工具集，支持文件操作与代码检索

### 第六期

- 🔒 危险操作静态规则识别（`write_file` / `execute_command` / `create_project` / `revert_turn`）
- ⚠️ 三级危险等级展示（高危 / 中危 / 安全）
- ✅ 审批决策：批准、全部放行、拒绝、跳过、修改参数后执行
- 🔓 HITL 默认关闭，`/hitl on` 启用、`/hitl off` 关闭

### 第七期

- ⚡ 同一轮多个工具调用会并行执行，适合同时读取多个文件、同时列目录、同时跑独立检查
- 🧵 ReAct、Plan-and-Execute、Multi-Agent Worker 共用同一套并行工具执行机制
- ⏱️ 工具批次有统一超时，超时工具会被取消并把超时结果回灌给模型
- 📋 Plan-and-Execute 与 Multi-Agent 会按 DAG 依赖批次并行推进独立任务

### 第八期

- 🔄 GLM-5.1、DeepSeek V4 与阶跃星辰 StepFun 多模型，`/model glm` / `/model deepseek` / `/model step` 运行时切换
- 🧱 `LlmClient` 接口 + 模板方法基类，新增 provider 只需 ~20 行
- 💾 默认模型持久化到 `~/.paicli/config.json`

### 第九期

- 🌐 `web_search` 工具支持三条路：智谱 Web Search（与 GLM 共用 Key 默认推荐）、SerpAPI（国际通用付费）、SearXNG（开源自托管免费）
- 📰 `web_fetch` 工具：抓 URL → readability 提取 → 返回 Markdown 正文
- 🛡️ 内置网络访问策略：屏蔽内网、loopback、`file://`；5MB 响应上限；每分钟 30 次限流
- 🚧 边界明确：SPA / 防爬墙返回空正文 + 已知边界提示，不重试

### 第六期 HITL 增强

- 🛡️ 路径围栏：文件类工具强制限定在项目根之内，绝对路径外逃 / `..` 穿越 / 符号链接逃逸全部拦截
- 🧯 命令快速拒绝：HITL 之前的 fast-fail 黑名单（`sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh` / `find /` / `chmod 777 /` / `shutdown`），减少 HITL 弹窗骚扰
- 📦 资源上限：`write_file` 5MB；`execute_command` 60 秒超时 + 8KB 输出截断
- 📋 结构化审计：危险工具调用按天写一行 JSONL 到 `~/.paicli/audit/`，可通过 `/audit [N]` 查看
- 🧱 定位：HITL 之外的辅助层，不是沙箱、不提供进程隔离

## 快速开始

### 1. 配置 API Key

复制 `.env.example` 为 `.env`，并填入你的 GLM、DeepSeek 或 StepFun API Key：

```bash
cp .env.example .env
# 编辑 .env 文件，填入你的 API Key
```

或者在环境变量中设置：

```bash
export GLM_API_KEY=your_api_key_here
# 或
export STEP_API_KEY=your_step_api_key_here
export STEP_MODEL=step-3.5-flash
```

长期记忆默认保存在用户目录下的 `~/.paicli/memory/long_term_memory.json`。
长期记忆只保存显式保存意图下的稳定事实：`/save <事实>`，或用户在自然语言里明确说“记一下 / 记住 / 以后记得”时由 Agent 调用 `save_memory`。它不应包含一次性任务请求或临时文件名/目录名。
代码索引默认保存在 `~/.paicli/rag/codebase.db`。
调试日志默认滚动写入 `~/.paicli/logs/paicli.log`，旧日志会按保留天数和总容量自动清理。

如果你想为某次运行指定单独目录，可以额外传入：

```bash
# 指定记忆目录
java -Dpaicli.memory.dir=/tmp/paicli-memory -jar target/paicli-1.0-SNAPSHOT.jar

# 指定 RAG 索引目录
java -Dpaicli.rag.dir=/tmp/paicli-rag -jar target/paicli-1.0-SNAPSHOT.jar

# 指定日志目录与保留策略
java -Dpaicli.log.dir=/tmp/paicli-logs \
     -Dpaicli.log.level=DEBUG \
     -Dpaicli.log.maxHistory=3 \
     -Dpaicli.log.maxFileSize=5MB \
     -Dpaicli.log.totalSizeCap=20MB \
     -jar target/paicli-1.0-SNAPSHOT.jar
```

也可以放到 `.env` 或环境变量中：

```bash
PAICLI_LOG_LEVEL=DEBUG
PAICLI_LOG_DIR=/Users/yourname/.paicli/logs
PAICLI_LOG_MAX_HISTORY=7
PAICLI_LOG_MAX_FILE_SIZE=10MB
PAICLI_LOG_TOTAL_SIZE_CAP=100MB
```

### 2. 可选：配置 MCP server

MCP 子系统默认开启。`~/.paicli/mcp.json` 不存在时，PaiCLI 会自动创建默认 chrome-devtools 配置：

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

需要继续接入其他 server 时，可编辑 `~/.paicli/mcp.json` 或项目内 `.paicli/mcp.json`：

```json
{
  "mcpServers": {
    "fetch": {
      "command": "uvx",
      "args": ["mcp-server-fetch"]
    },
    "git": {
      "command": "uvx",
      "args": ["mcp-server-git", "--repository", "${PROJECT_DIR}"]
    },
    "remote-demo": {
      "url": "https://mcp.example.com/v1",
      "headers": {"Authorization": "Bearer ${REMOTE_TOKEN}"}
    }
  }
}
```

`command` 表示 stdio server，`url` 表示 Streamable HTTP server。`${PROJECT_DIR}` / `${HOME}` 是内置变量，其他 `${VAR}` 从环境变量读取；缺失会在启动时直接提示。

需要复用当前登录态时，Chrome 144+ 推荐打开 `chrome://inspect/#remote-debugging` 并勾选 `Allow remote debugging for this browser instance`。旧版本或需要显式 CDP 端口时，可以启动带远程调试端口和独立 user-data-dir 的 Chrome，并在这个调试 Chrome 中完成登录：

```bash
# macOS
open -na "Google Chrome" --args --remote-debugging-port=9222 --user-data-dir=/tmp/paicli-chrome-profile

# Windows
start chrome.exe --remote-debugging-port=9222 --user-data-dir=%TEMP%\paicli-chrome-profile

# Linux
google-chrome --remote-debugging-port=9222 --user-data-dir=/tmp/paicli-chrome-profile
```

通常不需要用户预先切换；Agent 如果遇到登录页会自己调用 `browser_connect`。手工调试时也可以在 PaiCLI 内执行：

```text
/browser status
/browser connect
/browser tabs
/browser disconnect
```

`/browser connect` 只在当前进程内把 `chrome-devtools` 切到 shared 模式，不会改写 `~/.paicli/mcp.json`。如果希望启动后默认 shared，可手动把 args 改为：

```json
["-y", "chrome-devtools-mcp@latest", "--autoConnect"]
```

旧式 CDP HTTP JSON 端口也可使用：

```json
["-y", "chrome-devtools-mcp@latest", "--browser-url=http://127.0.0.1:9222"]
```

浏览器测试可直接让 Agent 读取动态页面，例如：

```text
帮我看下 https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg 这篇文章讲了什么
```

期望路径是 `web_fetch` 尝试失败后，fallback 到 `mcp__chrome-devtools__navigate_page` 与 `take_snapshot`。

如果 server 支持 resources，可以直接查看或引用：

```text
/mcp resources filesystem
/mcp prompts filesystem
帮我看下 @filesystem:file://README.md 这份文档
```

OAuth 和 `sampling/createMessage` 当前未实现；远程 server 需要鉴权时仍使用 `headers` + 环境变量注入 Bearer token。

### 3. 编译运行

```bash
# 编译
mvn clean package

# 运行（需要本地 Ollama 已启动且拉取了 nomic-embed-text）
java -jar target/paicli-1.0-SNAPSHOT.jar
```

或者直接运行：

```bash
mvn clean compile exec:java -Dexec.mainClass="com.paicli.cli.Main"
```

### 4. 如何进入 Plan 模式

当前默认模式是 `ReAct`。进入 `Plan-and-Execute` 的方式只有 `/plan`：

1. 输入 `/plan`
2. 下一条任务会用计划模式执行
3. 执行完成后自动回到默认 `ReAct`

如果想一条命令切模式并执行任务，可以直接输入：

```text
/plan 创建一个 demo 项目，然后读取 pom.xml，最后验证项目结构
```

这条命令执行完成后，会自动回到默认的 `ReAct` 模式。

计划生成后，CLI 会先停下来等待确认：

- 按 `Enter`：按当前计划执行
- 按 `Ctrl+O`：展开完整计划
- 按 `ESC`：折叠完整计划或取消本次计划
- 按 `I`：输入补充要求并重新规划
- 按方向键不会触发取消；只有单独按下 `ESC` 才会取消待执行 plan

## 使用示例

### 第一期：ReAct 示例

```text
👤 你: 创建一个Java项目叫myapp

🧠 思考过程:
用户要创建一个 Java 项目。我先调用 create_project 工具生成基础结构，再根据工具返回结果确认是否创建成功。

🤖 最终结果:
已成功创建 Java 项目 "myapp"，包含基本的 Maven 结构。
```

### 第二期：Plan-and-Execute 示例

```text
💡 提示:
   - 输入你的问题或任务
   - 输入 '/' 查看命令
   - 输入 '@server:protocol://path' 可显式引用 MCP resource
   - 任务运行中按 ESC 取消当前任务
   - 默认模式是 ReAct
   - 未识别的 `/xxx` 命令会直接提示“未知命令”，不会再交给 Agent 当普通对话处理

👤 你: /plan 创建一个名为 demoapp 的 java 项目，然后读取 pom.xml，最后验证项目结构

📋 使用 Plan-and-Execute 模式

📋 正在规划任务: 创建一个名为 demoapp 的 java 项目，然后读取 pom.xml，最后验证项目结构

╔══════════════════════════════════════════════════════════╗
║  执行计划: 创建一个名为 demoapp 的 java 项目，然后读取... ║
╠══════════════════════════════════════════════════════════╣
║  1. ⏳ task_1               [COMMAND   ] 依赖: 无        ║
║     创建 demoapp 项目结构                              ║
║  2. ⏳ task_2               [FILE_READ ] 依赖: task_1    ║
║     读取 demoapp/pom.xml 内容                          ║
║  3. ⏳ task_3               [VERIFICATION] 依赖: task_2  ║
║     验证项目结构与 Maven 配置                          ║
╚══════════════════════════════════════════════════════════╝

📝 计划已生成。
   - 回车：按当前计划执行
   - ESC：取消本次计划
   - I：输入补充要求后重新规划

I
补充> 请在执行前先检查 README

📝 已收到补充要求，正在重新规划...

🚀 开始执行计划...
```

## 可用工具

- `read_file` - 读取文件内容
- `write_file` - 写入文件内容
- `list_dir` - 列出目录内容
- `execute_command` - 在当前项目目录执行短时 Shell 命令（默认 60 秒超时，黑名单拦截破坏性命令）
- `create_project` - 创建项目结构（java/python/node）
- `search_code` - 语义检索代码库（自然语言查询）
- `web_search` - 搜索互联网获取实时信息
- `web_fetch` - 抓取已知 URL 并提取正文 Markdown
- `revert_turn` - 恢复到最近第 N 个 pre-turn 快照（走 HITL 与审计）
- `mcp__{server}__{tool}` - MCP server 动态提供的外部工具
- `mcp__{server}__list_resources` / `mcp__{server}__read_resource` - 支持 resources 的 MCP server 自动注册的虚拟工具

同一轮模型返回多个工具调用时，PaiCLI 会并行执行这些工具；如果工具之间有依赖关系，模型应分多轮调用。

文件类工具（`read_file` / `write_file` / `list_dir` / `create_project`）路径强制限定在项目根之内，越界请求会被策略层拒绝；`execute_command` 通过命令黑名单拦截 `sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh` 等。`revert_turn` 会批量回写工作区，默认触发 HITL 和审计。所有 `mcp__` 前缀工具默认触发 HITL 和审计。详见 `/policy`。

## 命令

- `/plan` - 下一条任务使用 Plan-and-Execute 模式
- `/plan <任务>` - 直接用 Plan-and-Execute 模式执行这条任务
- `/team` - 下一条任务使用 Multi-Agent 协作模式
- `/team <任务>` - 直接用 Multi-Agent 协作模式执行这条任务
- `/cancel` - 运行中请求取消当前任务；空闲时会提示当前没有正在运行的任务
- `/hitl on` - 启用危险操作人工审批（HITL）
- `/hitl off` - 关闭 HITL 审批
- `/hitl` - 查看 HITL 当前状态
- `/mcp` - 查看所有 MCP server 状态
- `/mcp restart <name>` - 重启单个 MCP server
- `/mcp logs <name>` - 查看 MCP server 最近 200 行 stderr 日志
- `/mcp disable <name>` - 运行时禁用 MCP server 并移除其工具
- `/mcp enable <name>` - 运行时启用 MCP server
- `/mcp resources <name>` - 查看 MCP server 暴露的 resources
- `/mcp prompts <name>` - 查看 MCP server 暴露的 prompts（只查看，不注入对话）
- `/policy` - 查看安全策略状态（路径围栏 / 命令黑名单 / 资源上限 / 审计目录）
- `/audit [N]` - 查看今日最近 N 条危险工具审计记录（默认 10）
- `/snapshot` - 查看最近 Side-Git 快照
- `/snapshot status` - 查看 Side-Git 快照状态
- `/snapshot clean` - 清理当前项目 Side-Git 快照目录
- `/restore <N>` - 恢复到最近第 N 个 pre-turn 快照
- `/memory` / `/mem` - 查看记忆系统状态
- `/memory clear` - 清空长期记忆
- `/save <事实>` - 手动保存关键事实到长期记忆
- `save_memory` - Agent 内置工具，仅在用户明确要求保存长期偏好或稳定事实时调用；“复用已登录 Chrome，记一下”这类浏览器登录态偏好会写入长期记忆，供新会话检索
- `/index [路径]` - 索引代码库（默认当前目录）
- `/search <查询>` - 语义检索代码
- `/graph <类名>` - 查看代码关系图谱
- `/clear` - 清空对话历史
- `/exit` / `/quit` - 退出程序

## 运行效果

### 第一期：旧版启动效果

```text
╔══════════════════════════════════════════════════════════╗
║                                                          ║
║   ██████╗  █████╗ ██╗      ██████╗██╗     ██╗            ║
║   ██╔══██╗██╔══██╗██║     ██╔════╝██║     ██║            ║
║   ██████╔╝███████║██║     ██║     ██║     ██║            ║
║   ██╔═══╝ ██╔══██║██║     ██║     ██║     ██║            ║
║   ██║     ██║  ██║███████╗╚██████╗███████╗██║            ║
║   ╚═╝     ╚═╝  ╚═╝╚══════╝ ╚═════╝╚══════╝╚═╝            ║
║                                                          ║
║              简单的 Java Agent CLI v1.0.0                ║
║                                                          ║
╚══════════════════════════════════════════════════════════╝
```

### 第三期：当前运行效果

```text
╔══════════════════════════════════════════════════════════╗
║                                                          ║
║   ██████╗  █████╗ ██╗ ██████╗██╗     ██╗                ║
║   ██╔══██╗██╔══██╗██║██╔════╝██║     ██║                ║
║   ██████╔╝███████║██║██║     ██║     ██║                ║
║   ██╔═══╝ ██╔══██║██║██║     ██║     ██║                ║
║   ██║     ██║  ██║██║╚██████╗███████╗██║                ║
║   ╚═╝     ╚═╝  ╚═╝╚═╝ ╚═════╝╚══════╝╚═╝                ║
║                                                          ║
║      Memory-Enhanced Agent CLI v3.0.0                 ║
║                                                          ║
╚══════════════════════════════════════════════════════════╝

✅ API Key 已加载

🔄 使用 ReAct 模式

💡 提示:
   - 输入你的问题或任务
   - 输入 '/' 查看命令
   - 输入 '@server:protocol://path' 可显式引用 MCP resource
   - 任务运行中按 ESC 取消当前任务
   - 默认模式是 ReAct

👤 你: 你好，请列出当前目录的文件

🧠 思考过程:
用户想了解当前目录结构。我先读取目录，再基于结果做归类说明，而不是只回原始文件列表。

🤖 最终结果:
当前目录包含 `src`、`target`、`pom.xml`、`README.md` 等文件，
这是一个标准的 Java Maven 项目。

👤 你: /exit

👋 再见!
```

## 技术栈

- Java 17
- Maven
- GLM-5.1 API
- OkHttp
- Jackson
- JLine3（终端交互）
- SQLite（向量与图谱持久化）
- JavaParser（AST 分析）
- Ollama（本地 Embedding）

## 项目结构

```
src/main/java/com/paicli
├── agent/
│   ├── Agent.java              # ReAct Agent
│   ├── PlanExecuteAgent.java   # Plan-and-Execute Agent
│   ├── AgentRole.java          # Agent 角色枚举
│   ├── AgentMessage.java       # Agent 间通信消息
│   ├── SubAgent.java           # 可配置子代理
│   └── AgentOrchestrator.java  # Multi-Agent 编排器
├── cli/
│   ├── Main.java               # CLI 入口
│   ├── CliCommandParser.java   # 命令解析
│   └── PlanReviewInputParser.java  # 计划审核输入
├── llm/
│   ├── GLMClient.java          # GLM-5.1 API 客户端
│   ├── DeepSeekClient.java     # DeepSeek API 客户端
│   └── StepClient.java         # 阶跃星辰 StepFun API 客户端
├── context/
│   ├── ContextMode.java        # short / balanced / long 模式
│   ├── ContextProfile.java     # 模型窗口与上下文策略
│   └── TokenUsageFormatter.java # Token / cache / 成本展示
├── memory/
│   ├── MemoryEntry.java        # 记忆条目
│   ├── ConversationMemory.java # 短期记忆
│   ├── LongTermMemory.java     # 长期记忆
│   ├── ContextCompressor.java  # 上下文压缩
│   ├── TokenBudget.java        # Token 预算管理
│   ├── MemoryRetriever.java    # 记忆检索
│   └── MemoryManager.java      # 记忆门面类
├── plan/
│   ├── Task.java               # 任务定义
│   ├── ExecutionPlan.java      # 执行计划
│   └── Planner.java            # 规划器
├── rag/
│   ├── EmbeddingClient.java    # Embedding API 客户端
│   ├── VectorStore.java        # SQLite 向量存储
│   ├── CodeChunk.java          # 代码块模型
│   ├── CodeChunker.java        # 代码分块器
│   ├── CodeAnalyzer.java       # AST 关系分析
│   ├── CodeRelation.java       # 代码关系模型
│   ├── CodeIndex.java          # 索引管理器
│   └── CodeRetriever.java      # 检索入口
└── tool/
    └── ToolRegistry.java       # 工具注册表
```
