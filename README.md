# ReviewFlow Agent

一个成熟的 Java Agent CLI 产品，对标 Claude Code 作者为沉默王二，从第一期的 `ReAct` 单代理循环逐步演进到第十六期的 `TUI 产品化`。

当前进度：已完成第 16.1 期 inline 流式 TUI 形态修正、第 17 期 `LSP 诊断注入` MVP、第 18 期 `Git Side-History 快照与回滚` MVP、第 19 期 `Prompt 分层架构` MVP、第 20 期 `异步后台任务 + Runtime API` MVP、第 21 期 `图片复制粘贴输入` MVP、第 23 期 `微信 iLink 通道` 文本 MVP；已新增 GitHub PR review 结构化 client 基础模块和 `/review pr` CLI 审查入口。

## 测试策略

日常开发不需要每次都跑全量测试。`mvn clean package` 默认跳过测试，优先产出可手工验收的 jar；需要回归时按改动范围选择：

```bash
# 第 16 期终端 / TUI / inline renderer 冒烟
mvn test -Pphase16-smoke

# 常规快速回归，跳过外部进程 / 网络超时 / 命令超时类慢测试
mvn test -Pquick

# 代码搜索 deterministic golden set
mvn test -Dtest=CodeSearchGoldenSetTest -DskipTests=false

# 发版或大范围重构前再跑全量
mvn test -DskipTests=false
```

## 演进历程

### 第一期：ReAct Agent CLI

- 单轮对话驱动的 `ReAct` 循环
- 支持工具调用：读文件、写文件、列目录、文件 glob、代码 grep、执行命令、创建项目、RAG 语义辅助检索、联网搜索、写工具状态查询/补偿、MCP 动态工具
- 更适合简单任务或单步操作

### 第二期：Plan-and-Execute + DAG

- 在保留 `ReAct` 模式的基础上新增复杂任务规划能力
- 支持先拆解任务，再按照依赖顺序执行
- 新增 `/plan` 入口，以一次性计划执行方式增强默认的 `ReAct`
- 计划生成后，会先与用户确认再执行
- 更适合多步骤、带依赖关系的复杂任务

### 第三期：Memory + 上下文工程

- 短期记忆管理当前对话与工具结果
- 长期记忆通过 `/save <事实>` 或用户明确说“记一下 / 记住”时的 `save_memory` 保存关键事实，默认项目级作用域，跨会话复用
- 项目级记忆通过 `PAI.md` / `.paicli/PAI.md` 启动自动注入，适合提交到仓库的团队共享规则；`PAI.local.md` / `.paicli/PAI.local.md` 只做本地覆盖
- 注入给模型的相关记忆只使用长期稳定事实，不把当前轮短期对话误当成“历史记忆”
- 对话接近预算时自动做摘要压缩
- 新增 `/memory` 查看状态、`/memory list/search/delete/clear` 管理长期记忆、`/save` 手动保存事实；Agent 在用户明确说“记一下 / 记住”时可调用 `save_memory`

### 第四期：RAG 检索 + 代码库理解

- 代码向量化（Embedding），支持本地 Ollama 和远程 API
- SQLite 持久化 + 余弦相似度语义检索
- 代码分块（文件/类/方法粒度）与 AST 解析
- 代码关系图谱（extends/implements/imports/calls/contains）
- 新增 `/index`、`/search`、`/graph` CLI 命令
- `search_code` 作为语义辅助检索工具；精确代码定位默认走 `glob_files` / `grep_code` / `read_file` 现用现查

### 第五期：Multi-Agent 协作 + 角色分工

- 三个角色：规划者（Planner）、执行者（Worker）、检查者（Reviewer）
- 主从架构：编排器（Orchestrator）协调子代理（SubAgent）
- 规划者拆解任务 -> 执行者执行 -> 检查者审查质量
- 审查未通过时带反馈重试（最多 2 次），冲突自动解决
- 新增 `/team` CLI 命令，进入多 Agent 协作模式

### 第六期：Human-in-the-Loop + 审批流

- 工具风险等级元数据：`READ_ONLY` / `LOW_WRITE` / `MEDIUM_WRITE` / `HIGH_RISK`
- 审批策略按风险等级推导：只读默认放行，低风险写默认放行并审计，中/高风险写触发 HITL
- 审批请求带稳定 fingerprint（如 `approval: 9f31c2ab`），同一风险动作按规范化参数生成 hash；`write_file` 审批会展示新建文件大小或简短 diff 预览
- 审批决策：批准 / 全部放行 / 拒绝 / 跳过 / 修改参数后执行；高风险操作不支持全部放行，只按最终参数逐次批准
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
- 内置 `GLMClient`、`DeepSeekClient`、`StepClient`、`KimiClient`、`FreeLlmApiClient`、`AgnesClient` 六个瘦实现
- `/model glm-5.1` / `/model glm-5v-turbo` 明确切 GLM 模型；`/model deepseek` / `/model step` / `/model kimi` / `/model freellmapi` / `/model agnes` 切 provider 并读取配置里的具体模型
- 配置持久化到 `~/.paicli/config.json`，API Key 可从配置、环境变量或 `.env` 读取

### 第九期：联网能力 + Web 工具

- `web_search` 抽象成 `SearchProvider` 接口，内置三个实现：智谱 Web Search（默认，与 GLM 共用 Key，0.01–0.05 元/次）、SerpAPI（国际通用付费）、SearXNG（开源自托管免费）
- `web_fetch` 新工具：URL → OkHttp 抓取 → Jsoup 解析 → 简易 readability → Markdown 正文
- 当当前模型是 `step-3.7-flash*` 且自动/显式 `step_search` 远程 server 已就绪时，内置 `web_search` / `web_fetch` 会优先走 StepSearch MCP；未就绪或调用失败时自动回退到原 provider。
- ReAct 对“最新/当前/今天/今年/2026/趋势/新闻/版本”等时效性问题会先做一次 `web_search` 预检并注入本轮上下文，避免模型在工具可用时误说无法实时搜索；用户明确不要联网时跳过。
- 默认安全策略：屏蔽 `file://` / 内网 / loopback；30 秒超时；5MB 响应上限；每分钟 30 次限流
- 边界明确：SPA / 防爬墙站点会返回空正文 + 已知边界提示，Agent 会 fallback 到浏览器 MCP 路线

### 第十期：MCP 协议核心

- 新增 `com.paicli.mcp` 模块，支持 stdio 子进程 server 与 Streamable HTTP 远程 server
- 启动时读取 `~/.paicli/mcp.json` 与 `.paicli/mcp.json`，项目级配置按 server 名覆盖用户级配置
- MCP `${VAR}` 支持系统环境变量、系统属性、项目 `.env`、用户 `~/.env`；检测到 `STEP_API_KEY` 时自动内置 `step_search` 远程 MCP，显式同名配置优先
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
- GLM-5.1 默认 200k window，DeepSeek V4 默认 1M window，Agnes 2.0 Flash 默认 1M window，StepFun 默认 256k window，Kimi K2.6 默认 256k window，FreeLLMAPI 默认按 128k 保守预算
- `AgentLoopController` 统一控制 ReAct/SubAgent 循环：默认 token 硬预算不限，仍可用系统属性覆盖；同时检测重复工具、连续无进展、无效反思、连续工具失败和硬最大轮数
- short / balanced / long 三种上下文模式：长上下文模式跳过摘要压缩，语义检索 topK 可提升到 20
- `search_code` 未显式传 `top_k` 时按上下文模式自适应；默认代码定位仍优先实时 grep/read
- 长上下文模式下自动把 MCP resources 的 URI / 描述索引注入 system prompt，不自动注入正文
- inline 模式下 Token / cached input tokens / 估算成本 / 耗时进入底部状态栏，避免占用正文输出区
- `/context` 会显示模型下一轮大致会携带的上下文：system/tools/conversation/user/assistant/tool result token 拆分、PAI.md 来源、当前项目可见长期记忆数量、工具结果 microcompact 状态、prompt cache 与 MCP resource 自动索引状态

### 第十三期：Chrome DevTools MCP

- 默认接入 Google 官方 `chrome-devtools-mcp@latest`，注册为 `mcp__chrome-devtools__navigate_page`、`take_snapshot`、`click`、`fill_form` 等浏览器工具
- `~/.paicli/mcp.json` 不存在时启动自动创建模板，默认使用 `--isolated=true` 临时浏览器 profile
- 用于处理 SPA / JS 渲染 / 防爬墙 / 表单交互页面；微信公众号文章、知乎专栏、推特、小红书等 `web_fetch` 失败站点会引导走浏览器 MCP
- HITL 的“全部放行”支持 MCP server 维度，连续浏览器操作可对 `chrome-devtools` 一次确认
- `image` 类型结果会作为图片输入附加到下一轮；文本 fallback 仍保留，用于日志、人类可读摘要，以及 DeepSeek 等不接受图片块的 provider 自动降级上下文
- MCP initialize 默认超时为 60 秒；CLI 首屏默认最多等待 8 秒，超时后先进入交互，未完成的 server 保持 `starting` 并在后台继续启动，可用 `/mcp` 和 `/mcp logs <name>` 追踪

### 第十四期：CDP 会话复用 + 登录态访问

- 新增 `/browser status`、`/browser connect [port]`、`/browser disconnect`、`/browser tabs` 命令组，并给 Agent 暴露内部 `browser_connect` / `browser_disconnect` / `browser_status` 工具
- 默认仍使用 `--isolated=true` 临时浏览器 profile；执行 `/browser connect` 后，运行时把 `chrome-devtools` 切到 `--autoConnect`，复用已在 `chrome://inspect/#remote-debugging` 允许远程调试的登录态 Chrome
- Agent 遇到登录页、权限不足或明确需要登录态页面时，会先调用 `browser_connect` 自动切到 shared；公开页面如微信公众号文章不提前切换
- `/browser connect <port>` 保留旧式 CDP 端口兼容路径：先探活 `127.0.0.1:<port>/json/version`，成功后切到 `--browser-url=http://127.0.0.1:<port>`；失败时不会改 MCP 启动参数，并输出 macOS / Windows / Linux 的 Chrome 启动命令
- 切换 shared / isolated 模式都会清空 `chrome-devtools` 的 server 维度全部放行，避免旧信任跨模式延续
- shared 模式下 `close_page` 只能关闭 ReviewFlow Agent 自己创建的 tab；无法证明是 ReviewFlow Agent 创建的 tab 会被策略层拒绝
- 敏感页面命中规则后，`click` / `fill_form` / `evaluate_script` 等改写型浏览器工具必须单步 HITL 审批，不复用全部放行；读型工具如 `take_snapshot` 仍可继续使用
- 审计日志为 chrome-devtools 工具追加可选浏览器 metadata：`browser_mode`、`sensitive`、`target_url`，旧格式 JSONL 仍可读取

### 第十五期：Skill 系统 + 内置 web-access skill

把"Agent 该怎么思考"从硬编码 system prompt 抽出，沉淀成可复用单元。每个 Skill 是一个目录：`SKILL.md`（决策手册）+ `references/`（按需读取）+ 可选 `scripts/`（可执行依赖）。

- 三层加载位置（按优先级，后者整体覆盖同名 skill）：jar 内置 < 用户级 `~/.paicli/skills/<name>/` < 项目级 `<project>/.paicli/skills/<name>/`
- 启动期把启用 skill 的 `name` + `description` 注入三处 Agent 系统提示词索引段（启用上限 20 个，索引段 ≤ 4KB）
- 内置工具 `load_skill(name)`：LLM 在 system prompt 看到匹配 description 时主动调用，ReviewFlow Agent 把 SKILL.md 正文（5KB 截断）写入 `SkillContextBuffer`，下一轮 user message 自动前置注入
- 内置 web-access skill：决策手册（浏览哲学四步法 + 工具选择表 + 浏览器优先级 + Jina 兜底说明）+ 6 个站点经验文件（mp.weixin / zhuanlan.zhihu / x.com / xiaohongshu / github / juejin）+ cdp-cheatsheet
- frontmatter 走手写 YAML 子集解析，不引 SnakeYAML；解析失败 stderr 警告但不阻塞启动
- CLI 命令：`/skill list` / `/skill show <name>` / `/skill on <name>` / `/skill off <name>` / `/skill reload`
- 启用状态持久化：`~/.paicli/skills.json` 的 `disabled` 列表，默认全启用
- 与 HITL 协同：Skill 内调用 `execute_command` 等危险工具仍走既有 HITL 审批，沿用 `execute_command` 工具维度全放行；不给 Skill 单独审批维度

设计意图：从「写工具」演进到「打包专家手册」。当工具堆成山（ReviewFlow Agent 当前内置 9 个 + MCP 60+ 工具），用 Skill 给 LLM 一份按场景展开的"专家手册"，比往 system prompt 里塞更多规则更可扩展。

### 第十六期：TUI 产品化（v16.1 形态修正后：双形态可切换）

v16.1 抽出 `Renderer` 接口 + 三个实现：

| 形态 | 启用方式 | 视觉风格 |
|---|---|---|
| **inline 流式 TUI**（默认） | 直接运行 / `PAICLI_RENDERER=inline` | Claude Code / Qoder 风格：π 主题彩色开屏、主屏直出、transcript 当前位置的 `* ` 输入提示、JLine `Status` 托管的底部 dock（YOLO/HITL、MCP、Skill、model、ctx、token、cwd 等关键字段带克制彩色高亮；ctx 是当前上下文估算，in/out/cache 是调用统计）、右侧输入提示、行内可折叠工具块（`Read 3 files (ctrl+o to expand)`）、行内 git diff、HITL 单字符 `[y/n/a/s/m]` 提示 |
| **lanterna 全屏 TUI** | `PAICLI_RENDERER=lanterna`（或兼容旧 `PAICLI_TUI=true`） | v16 三栏全屏：文件树 + 对话流 + 状态栏 + 底部输入栏，HITL 模态弹窗 |
| **plain 兜底** | `PAICLI_RENDERER=plain` | 纯 println，无折叠 / 状态栏，等价 v15 行为 |

- 三种形态共享同一套 `Agent` / `ToolRegistry` / `MemoryManager` / MCP server / SkillRegistry / HITL handler，不创建孤立空会话
- 普通输入走 ReAct；`/plan <任务>` 走 Plan-and-Execute；`/team <任务>` 走 Multi-Agent；`/cancel` 可取消运行中任务
- 通用命令：`/clear`、`/context`、`/memory`、`/memory clear`、`/save <事实>`、`/export`、`/hitl`、`/hitl on`、`/hitl off`、`/config`、`/exit`
- 对话历史保存到 `~/.paicli/history/session_*.jsonl`
- 兼容旧设置：`PAICLI_TUI=true` 自动映射为 `PAICLI_RENDERER=lanterna`（已 deprecated）
- `PAICLI_NO_STATUSBAR=true` 在 inline 模式下禁用 JLine 底部 dock（不适合 ANSI 光标控制的终端）
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

### 第十九期：Prompt 分层架构（MVP）

- ReAct、Plan task executor、Multi-Agent 三角色、Planner 的 system prompt 已从 Java 硬编码抽离到 `src/main/resources/prompts/`
- `PromptAssembler` 按 `base -> personality -> mode -> approval -> runtime_context -> project_context -> skills -> context_mgmt -> handoff` 组装；`runtime_context` 注入当前日期/时区，动态项目上下文靠后注入
- `project_context` 会先注入 `PAI.md` 项目记忆，再注入 `/save` 检索到的相关长期记忆和 MCP resource 索引
- 支持用户级覆盖 `~/.paicli/prompts/...`，支持项目级覆盖 `.paicli/prompts/...`，项目级优先级最高
- 覆盖是整文件替换；`base.md` 和最终 prompt 必须包含 `## Language`
- Prompt 改动审计模板见 `docs/prompt-analysis-template.md`

### 第二十期：异步后台任务 + Runtime API（MVP）

- `DurableTaskManager` 使用 SQLite 持久化后台任务队列，默认位置 `~/.paicli/tasks/tasks.db`
- 后台任务已升级为 durable workflow MVP：任务 State、checkpoint、写工具幂等记录、`operation_id` 状态、owner/lease、pause/resume/retry/cancel 和文件类补偿共用同一套持久化表
- 任务生命周期：`enqueued -> running -> pause_requested -> paused -> running -> completed / failed / canceled`，补偿路径为 `failed/canceled -> compensating -> compensated`
- 后台执行已抽出 `TaskQueue` 唤醒层：默认 `LocalTaskQueue` 作为进程内 MQ；`PAICLI_TASK_QUEUE=redis` 时使用 Redis list（默认 `redis://localhost:6379/0`，key 为 `paicli:tasks:ready`）；scheduler 定期扫描 DB 中可运行任务并投递 taskId，worker 消费消息后仍必须通过 DB 原子 claim，重复/过期消息不会重复执行任务
- Agent 循环会在 LLM 前、LLM 后工具前、工具后和完成边界写 checkpoint；worker claim 会写入 `owner_id` / `lease_until`，后台 heartbeat 会持续续租；checkpoint 和 worker 终态写回都会校验 owner/lease，服务重启后只回收过期或缺失 lease 的 `running` / `compensating` 任务，活跃 lease 不会被新实例抢走
- 后台 ReAct 写工具通过 `DurableToolRegistry` 记录幂等键、`operation_id` 与执行结果；`write_file` / `create_project` 会保存 Side-Git 快照与文件 hash，恢复或重试时同一成功工具调用会回放旧结果，避免重复写入。`tool_status` 可按 `operation_id` 查询状态，`tool_compensate` 可按单次可逆操作恢复写入前快照
- `/task`、`/task add <任务内容>`、`/task cancel <task_id>`、`/task pause <task_id>`、`/task resume <task_id>`、`/task retry <task_id>`、`/task compensate <task_id>`、`/task log <task_id>` 提供 CLI 闭环
- Worker Pool 默认 2 个后台 worker，可通过 `PAICLI_TASK_WORKERS` 调整；Redis 队列可通过 `PAICLI_TASK_REDIS_URL` / `PAICLI_TASK_REDIS_KEY` 配置
- `java -jar target/paicli-1.0-SNAPSHOT.jar serve --http --port 8080` 启动 localhost Runtime API
- Runtime API 端点：`POST /v1/threads`、`POST /v1/threads/{id}/turns`、`GET /v1/threads/{id}/events`
- Runtime API 强制要求 `PAICLI_RUNTIME_API_KEY` 或 `-Dpaicli.runtime.api.key`
- 详细文档见 `docs/phase-20-runtime-api.md`

### 第二十一期：图片复制粘贴输入（MVP）

- `LlmClient.Message` 支持 `ContentPart`，包括 `text`、`image_base64`、`image_url`
- 请求体在含图片且 provider 支持图片输入时输出带图片块的 content array，纯文本仍保持 string content
- `LlmClient` 公共接口用 `supportsImageInput()` 声明图片能力；DeepSeek 等文本 provider 会把图片块替换成文本提示，避免 `image_url` 进入不支持多模态的 API 请求体
- GLM 套餐用户可通过 `/model glm-5v-turbo` 切换到 GLM-5V-Turbo 多模态模型，再用 Ctrl+V 或 `@image:` 输入图片；本地 base64 图片会按智谱格式写入 `image_url.url`
- MCP `image` content 会保留 base64 与 `mimeType`，在 ReAct / Plan / SubAgent 工具结果后作为图片 user message 回灌；当前 provider 不支持图片输入时，请求序列化层会自动省略图片 payload 并保留文本提示
- 用户可通过 `@image:file:///abs/path.png`、`@image:/abs/path.png` 或 `@image:relative/path.png` 引用本地图片
- 本地图片和 MCP 图片都会按 Claude Code 同类策略预处理：不是 OCR 成文本，而是压缩 / 缩放后作为图片块发送；带 alpha 的 PNG 会铺白底重编码；额外注入来源、尺寸和坐标映射元信息
- 本地 `@image:` 消息会要求模型优先分析本轮图片；除非用户明确要求结合历史，历史对话和历史工具结果不能替代当前图片内容
- 新一轮 ReAct / SubAgent 任务开始前会省略历史 image payload，仅保留文本元信息，避免旧截图反复进入上下文；模型 `reasoning_content` 默认只写日志 / 展示，DeepSeek V4 / Kimi thinking tool-call 续轮会按 provider 协议带回上一轮 assistant reasoning
- DeepSeek 流式调用默认使用 HTTP/1.1，规避部分 HTTP/2 网关长 SSE 响应被重置导致的 `stream was reset: INTERNAL_ERROR`
- 当前边界：不做视频 / 音频、图像生成、TUI sixel 图片预览

### 第二十三期：微信 iLink 通道（文本 MVP）

- 新增进程级入口：`paicli wechat setup`、`paicli wechat start`、`paicli wechat status`、`paicli wechat daemon start|stop|restart|status|logs`
- 新增交互式入口：在 ReviewFlow Agent 主界面输入 `/wechat` 可扫码绑定并在当前进程后台启动微信通道；`/wechat setup` 重新扫码绑定，`/wechat status` 查看状态，`/wechat stop` 停止通道
- 默认不开启微信通道；用户必须主动执行 `setup` 并扫码确认完成绑定
- 支持在 Warp / iTerm2 / WezTerm 等兼容终端内直接显示 260px PNG 二维码；不支持终端图片协议时回退为字符二维码和链接
- 微信侧使用 iLink `getupdates` 长轮询收消息、`sendmessage` 分片回消息，不依赖 SSE；这是独立通道，不是 Skill，也不是 Runtime API
- 运行时只接受绑定用户私聊；普通消息单并发排队，`/help`、`/status`、`/pause`、`/resume`、`/stop` 走队列外控制路径
- 微信侧用户消息会回显到 ReviewFlow Agent 终端 transcript；ReviewFlow Agent 终端继续显示 thinking / 工具调用过程，微信侧只接收 assistant 正文。iLink 协议层仍是 `text_item.text` 文本消息，没有显式 Markdown parse mode；ReviewFlow Agent 会保留 ClawBot 稳定支持的 Markdown 子集（列表、引用、粗体、行内代码、真实代码块），把标题转成粗体标题、把表格转成移动端更稳的键值/列表，并过滤图片 Markdown / H5-H6 / 中文斜体等兼容性差的标记；非代码类 fenced block（流程说明、长中文箭头链）会解包并换行，避免微信侧出现横向滚动代码块。iLink 不提供真正 SSE 或改单条消息能力。
- 微信通道使用非交互式默认拒绝策略：只读工具默认允许，`write_file` / `create_project` 继续受 workspace PathGuard 限制，`execute_command` 必须精确命中命令白名单，`mcp__*` 必须命中 MCP 白名单，`revert_turn` 和浏览器会话切换默认拒绝
- 当前文本 MVP 会保留图片 / 文件消息的媒体元数据提示，但 CDN 下载解密、图片块输入和 `/send` 文件推送仍待后续媒体链路补齐

### 第六期 HITL 增强（路径围栏 / 命令快速拒绝 / 操作审计 / macOS 命令沙箱）

`com.paicli.policy` 与 `com.paicli.sandbox` 包，作为 HITL 之外的辅助层：

- `PathGuard` 路径围栏：文件类工具强制限定在项目根之内，拦截绝对路径外逃 / `..` 穿越 / 符号链接逃逸
- `CommandGuard` 命令快速拒绝：HITL 之前的 fast-fail 黑名单（`sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh` / `find /` / `chmod 777 /` / `shutdown`），减少 HITL 弹窗骚扰
- macOS command sandbox：开启 `/sandbox on` 后，`execute_command` 会通过 macOS Seatbelt (`sandbox-exec`) 包裹执行；只沙箱命令及其子进程，不沙箱整个 ReviewFlow Agent 进程。默认禁网络、限制写入到项目目录和 ReviewFlow Agent 临时目录，并拒绝 `.paicli/**`、`.env`、`PAI.md`、`AGENTS.md`、`.git/hooks/**` 等敏感写入
- `AuditLog` 结构化审计：低风险写及以上工具调用按天写 JSONL 到 `~/.paicli/audit/`，含 `outcome (allow|deny|error)`、`approver (hitl|policy|none)`、审批 fingerprint 与 sandbox metadata；`revert_turn` 也纳入高风险工具链
- `write_file` 单文件 5MB 上限
- CLI 命令：`/policy` 查看安全策略状态、`/audit [N]` 看最近 N 条审计，`/sandbox status|on|off|doctor` 管理 macOS 命令沙箱

边界说明：ReviewFlow Agent 内置沙箱是 **command sandbox**。它只保护 `execute_command` 启动的 shell 及其子进程；`read_file` / `write_file` 继续由 `PathGuard` 和 HITL 管理，`web_fetch` 继续由 `NetworkPolicy` 管理，MCP server / 浏览器 shared session / 微信 daemon 不在该沙箱内。

## 启动界面

### 当前启动界面

当前启动输出以命令行实际产物为准：

```text
    ██████   ██████  ███████   ███████     ReviewFlow Agent
   ██       ██    ██ ██    ██  ██          Model step-3.5-flash-2603 (step)
   ██       ██    ██ ██     ██ ██          MCP 4/4 · 61 tools · 2/2 skills · ReAct
   ██       ██    ██ ██     ██ █████       ReAct · Plan · MCP · Browser · Image · Tools · Memory · RAG
   ██       ██    ██ ██    ██  ██
    ██████   ██████  ███████   ███████

Tips for getting started:
1. Type / for commands and Tab completion
2. Ask coding questions, edit code or run commands
3. Attach context with @path or @image:
```

## 功能

### 第一期

- 🤖 基于 GLM-5.1 的智能对话
- 🔄 ReAct Agent 循环（思考-行动-观察）
- 🛠️ 工具调用（文件操作、确定性代码搜索、Shell命令、项目创建、RAG 语义检索、联网搜索、MCP 动态工具）
- 💬 交互式命令行界面
- 📝 普通任务和斜杠命令提交后会先把本轮原始输入以 `>` 暗色整行块写回 transcript；输入态仍显示 `* `，单行提交只占一行，不额外追加空白行。普通任务随后再进入 Thinking / 工具调用，避免 dock 刷新或 activity 重绘后用户输入从可见历史里消失
- 🧠 默认通过流式接口获取模型输出；inline ReAct 用固定高度 live thinking 区动态预览 reasoning，content / tool call 开始前清掉 live 区并把完整 reasoning 引用块落到 transcript，回答正文用低调标记起始；web_search / web_fetch 会在折叠头展示 query / URL，并在执行后输出一行结果摘要
- 🖥️ 终端会对常见 Markdown（标题、列表、表格、代码块）做渲染后再显示；表格会按当前窗口宽度分配列宽，并在单元格内部换行，避免长 URL / 中文内容把列打散

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

- 🔍 代码库实时搜索 + RAG 语义辅助（精确定位优先 glob/grep/read，自然语言模糊查询再 search_code）
- 🕸️ 代码关系图谱（类继承、接口实现、方法调用）
- 📡 本地 Ollama Embedding + 远程 API 可配置
- 🗃️ SQLite 向量存储与持久化

### 第五期

- 👥 多 Agent 协作（规划者 + 执行者 + 检查者）
- 🎯 主从架构编排器自动分配任务
- 🔍 检查者审查质量，未通过自动重试
- 🛠️ 执行者共享工具集，支持文件操作与代码检索

### 第六期

- 🔒 工具风险等级元数据（只读 / 低风险写 / 中风险写 / 高风险）
- ⚠️ 审批策略按风险等级推导（中风险写和高风险默认触发 HITL）
- ✅ 审批决策：批准、全部放行、拒绝、跳过、修改参数后执行；高风险只按最终参数 fingerprint 单次放行
- 🔓 HITL 默认关闭，`/hitl on` 启用、`/hitl off` 关闭

### 第七期

- ⚡ 同一轮多个工具调用会并行执行，适合同时读取多个文件、同时列目录、同时跑独立检查
- 🧵 ReAct、Plan-and-Execute、Multi-Agent Worker 共用同一套并行工具执行机制
- ⏱️ 工具批次有统一超时，超时工具会被取消并把超时结果回灌给模型
- 📋 Plan-and-Execute 与 Multi-Agent 会按 DAG 依赖批次并行推进独立任务

### 第八期

- 🔄 GLM-5.1、GLM-5V-Turbo、DeepSeek V4、阶跃星辰 StepFun、Kimi K2.6、FreeLLMAPI 与 Agnes 2.0 Flash 多模型，`/model glm-5.1` / `/model glm-5v-turbo` 明确切 GLM 模型，`/model deepseek` / `/model step` / `/model kimi` / `/model freellmapi` / `/model agnes` 读取配置模型
- 🧱 `LlmClient` 接口 + 模板方法基类，新增 provider 只需 ~20 行
- 💾 默认模型持久化到 `~/.paicli/config.json`

### 第九期

- 🌐 `web_search` 工具支持四条路：Step 3.7 Flash + StepSearch MCP 优先、智谱 Web Search（与 GLM 共用 Key默认推荐）、SerpAPI（国际通用付费）、SearXNG（开源自托管免费）
- 📰 `web_fetch` 工具：抓 URL → readability 提取 → 返回 Markdown 正文
- 🛡️ 内置网络访问策略：屏蔽内网、loopback、`file://`；5MB 响应上限；每分钟 30 次限流
- 🚧 边界明确：SPA / 防爬墙返回空正文 + 已知边界提示，不重试

### GitHub PR Review Client（基础模块）

- `com.paicli.github.GitHubPrClient` 内置 GitHub REST / GraphQL 访问封装，可结构化拉取 PR metadata、diff、changed files、review comments、commit status 与 check runs
- `GitHubPrReviewService` 提供 review agent 后续可复用的加载上下文、prepare review 与发布 review 薄封装
- `GitHubDiffLineMap` 根据 changed file patch 映射 `RIGHT` / `LEFT` 行号；`GitHubReviewPreparer` 会把 findings 转成 GitHub inline comments，并跳过不在当前 diff 中的 outdated 定位
- inline comment 支持 title / severity / suggestion Markdown 格式化；发布前会校验 path、line、body，避免无效评论打到 GitHub 后返回 422
- 支持向 GitHub 发布 PR review summary 与 inline comments；需要 `PAICLI_GITHUB_TOKEN` / `GITHUB_TOKEN` / `GH_TOKEN`
- 支持 GitHub Enterprise：`PAICLI_GITHUB_API_BASE_URL`、`PAICLI_GITHUB_GRAPHQL_URL`
- CLI 入口：`/review pr <url|owner/repo#number|number>` 会拉取 PR 上下文并启动当前 Agent 审查；只传 number 时从当前 git `remote.origin.url` 推断仓库；当前不会自动发布 review
- 非交互烟测入口：`java -jar target/paicli-1.0-SNAPSHOT.jar review pr <url|owner/repo#number|number> --dry-run --format json` 会在不初始化 LLM 的情况下拉取 PR snapshot、构造 review prompt，并输出机器可读摘要，适合接入 Code Review Bench 等离线评测流水线做链路健康检查
- Code Review Bench 适配入口：`benchmark code-review-bench <offline-dir> --mode smoke|review` 会读取 `offline/results/benchmark_data.json`；`smoke` 模式验证 PR 拉取、prompt 构造和目标仓库 sparse checkout，`review` 模式调用 ReviewFlow Agent 配置的 LLM 生成 findings，并写出 benchmark 兼容的 `reviews` 和 `results/<MARTIAN_MODEL>/candidates.json`；benchmark prompt 默认高召回，覆盖低严重度但有证据的 doc/style/test_gap/translation/locale 问题，并会针对 CLI exit 行为、`picocli.exit`/`System.exit`、exit code 变更缺 release/migration note 等 benchmark 常见 golden 做专项提示；diff 按文件分块写入，并补充 checked-out head changed file 内容，不再走 60k 整体 diff 截断；`--only-url <PR_URL>` 可只重跑指定 PR，便于 prompt 调试；`--parallel N` 沿用 ReviewFlow Agent 有界并发风格并行处理 PR，结果仍按 benchmark 输入顺序串行合并，当前最大 4；加 `--in-place` 后会更新官方 step3 默认读取的 `results/benchmark_data.json`；`--timeout-seconds` 控制单个 PR 的模型审查上限；`--no-checkout` 可退回纯 diff 模式；正式跑 50 条建议配置 `PAICLI_GITHUB_TOKEN` / `GITHUB_TOKEN` 避免 GitHub 匿名 API rate limit

### 第六期 HITL 增强

- 🛡️ 路径围栏：文件类工具强制限定在项目根之内，绝对路径外逃 / `..` 穿越 / 符号链接逃逸全部拦截
- 🧯 命令快速拒绝：HITL 之前的 fast-fail 黑名单（`sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh` / `find /` / `chmod 777 /` / `shutdown`），减少 HITL 弹窗骚扰
- 📦 资源上限：`write_file` 5MB；`execute_command` 60 秒超时 + 8KB 输出截断
- 📋 结构化审计：低风险写及以上工具调用按天写一行 JSONL 到 `~/.paicli/audit/`，可通过 `/audit [N]` 查看
- 🧱 定位：HITL 之外的辅助层，不是沙箱、不提供进程隔离

## 快速开始

### 1. 配置 API Key

复制 `.env.example` 为 `.env`，并填入你的 GLM、DeepSeek、StepFun、Kimi、FreeLLMAPI 或 Agnes API Key：

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
# 或
export KIMI_API_KEY=your_kimi_api_key_here
export KIMI_MODEL=kimi-k2.6
# 或
export FREELLMAPI_API_KEY=your_freellmapi_unified_key_here
export FREELLMAPI_BASE_URL=http://localhost:5173/v1
export FREELLMAPI_MODEL=auto
# 或
export AGNES_API_KEY=your_agnes_api_key_here
# GitHub PR review client（后续 review agent 使用）
export PAICLI_GITHUB_TOKEN=your_github_token_here
export AGNES_MODEL=agnes-2.0-flash
export AGNES_BASE_URL=https://apihub.agnes-ai.com/v1
```

也可以在 ReviewFlow Agent 内用命令写入 `~/.paicli/config.json`，不会覆盖 Kimi 配置：

```text
/config provider freellmapi --base-url http://localhost:5173/v1 --api-key <key> --model auto
/model freellmapi
/config provider agnes --api-key <key> --model agnes-2.0-flash --default
/model agnes
```

长期记忆默认保存在用户目录下的 `~/.paicli/memory/long_term_memory.json`，同时会维护 Markdown 形态的 `~/.paicli/memory/MEMORY.md` 索引和 `~/.paicli/memory/topics/*.md` topic 文件；JSON 用于兼容旧数据，Markdown 便于审计、编辑和后续 topic 召回。
长期记忆只保存显式保存意图下的稳定事实：`/save <事实>`，或用户在自然语言里明确说“记一下 / 记住 / 以后记得”时由 Agent 调用 `save_memory`。默认保存为当前项目作用域；跨项目通用偏好可用 `/save --global <事实>` 或 `save_memory(scope=global)`。它不应包含一次性任务请求或临时文件名/目录名。
可选自动记忆归纳默认关闭；设置 `PAICLI_AUTO_MEMORY=true` 或 `-Dpaicli.auto.memory=true` 后，ReAct/Plan 完成后会从短期记忆提取稳定事实并按当前项目 scope 保存。
可用 `/memory list` 查看长期记忆，`/memory search <关键词>` 搜索当前项目可见记忆，`/memory delete <id>` 删除单条记忆。
长期记忆召回会综合内容、topic frontmatter 的 `name/description`、metadata 和查询关键词评分，`/context` 会展示上一轮实际注入的长期记忆及召回原因。
上下文压缩分三层：`ConversationMicroCompactor` 先清理旧的大型 tool result；`ConversationHistoryCompactor` 再把真实发给 LLM 的 `conversationHistory` 结构化摘要，并在摘要消息写入 `[compact_boundary]` 锚点；`ContextCompressor` 只压缩 MemoryManager 内部的 shortTermMemory。`/compact` 手动路径也会先 microcompact，再保留最近 1 个 user 轮次做结构化摘要。

项目级记忆使用 Markdown 文件维护，和 `/save` 的长期记忆分工不同：

- `~/.paicli/PAI.md`：用户级稳定偏好，所有项目可见。
- `PAI.md` / `.paicli/PAI.md`：项目级团队规则，建议提交到 git。
- `PAI.local.md` / `.paicli/PAI.local.md`：本地覆盖，适合个人调试约定，建议加入 `.gitignore`。
- `.paicli/rules/*.md`：项目规则文件；无 `paths:` frontmatter 时启动即加载，有 `paths:` 时只在读到匹配文件后加载。
- nested memory：当 `read_file` 读取子目录文件后，ReviewFlow Agent 会在下一轮模型调用前加载该子目录链路上的 `PAI.md` / `.paicli/PAI.md` / local 文件和匹配 rules。
- `@relative/path.md`：在 `PAI.md` 中导入项目根内的相对文件；越靠后的文件越接近本地覆盖，优先级越高。

可用 `/init` 为当前项目生成一份短 `PAI.md`。该命令默认不覆盖已有文件；确认需要重建时使用 `/init --force`。
代码索引默认保存在 `~/.paicli/rag/codebase.db`。
调试日志默认滚动写入 `~/.paicli/logs/paicli.log`，旧日志会按保留天数和总容量自动清理。
ReAct / Plan task / SubAgent / Planner 的模型 `reasoning_content` 会以 `LLM reasoning [...]` 形式写入该日志，便于排查模型为什么选择某个工具或路径。

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

MCP 子系统默认开启。`~/.paicli/mcp.json` 不存在时，ReviewFlow Agent 会自动创建默认 chrome-devtools 配置：

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
    },
    "step_search": {
      "url": "https://api.stepfun.com/step_plan/v1/mcp/web_search/mcp",
      "headers": {"Authorization": "Bearer ${STEP_API_KEY}"}
    }
  }
}
```

`command` 表示 stdio server，`url` 表示 Streamable HTTP server。`${PROJECT_DIR}` / `${HOME}` 是内置变量，其他 `${VAR}` 从环境变量读取；缺失会在启动时直接提示。

`step_search` 是约定名称：如果项目 `.env`、用户 `~/.env` 或系统环境变量里存在 `STEP_API_KEY`，ReviewFlow Agent 会自动内置这个远程 MCP；上面的手写配置只用于覆盖默认地址或自定义鉴权。当前模型为 `step-3.7-flash*` 时，内置 `web_search` / `web_fetch` 会优先代理到该 MCP server。

需要复用当前登录态时，Chrome 144+ 推荐打开 `chrome://inspect/#remote-debugging` 并勾选 `Allow remote debugging for this browser instance`。旧版本或需要显式 CDP 端口时，可以启动带远程调试端口和独立 user-data-dir 的 Chrome，并在这个调试 Chrome 中完成登录：

```bash
# macOS
open -na "Google Chrome" --args --remote-debugging-port=9222 --user-data-dir=/tmp/paicli-chrome-profile

# Windows
start chrome.exe --remote-debugging-port=9222 --user-data-dir=%TEMP%\paicli-chrome-profile

# Linux
google-chrome --remote-debugging-port=9222 --user-data-dir=/tmp/paicli-chrome-profile
```

通常不需要用户预先切换；Agent 如果遇到登录页会自己调用 `browser_connect`。手工调试时也可以在 ReviewFlow Agent 内执行：

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
# 编译（默认跳过测试）
mvn clean package

# 运行（需要本地 Ollama 已启动且拉取了 nomic-embed-text；grep_code 会优先使用本机 ripgrep，未安装时自动回退）
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
* 创建一个Java项目叫myapp

🧠 思考过程:
用户要创建一个 Java 项目。我先调用 create_project 工具生成基础结构，再根据工具返回结果确认是否创建成功。

🤖 最终结果:
已成功创建 Java 项目 "myapp"，包含基本的 Maven 结构。
```

### 第二期：Plan-and-Execute 示例

```text
💡 提示:
   - 输入你的问题或任务
   - 输入 '/' 后按 Tab 补全命令
   - 输入 '@server:protocol://path' 可显式引用 MCP resource
   - 任务运行中按 ESC 取消当前任务
   - 默认模式是 ReAct
   - 未识别的 `/xxx` 命令会直接提示“未知命令”，不会再交给 Agent 当普通对话处理

* /plan 创建一个名为 demoapp 的 java 项目，然后读取 pom.xml，最后验证项目结构

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

- `read_file` - 读取已知路径的项目文件内容（大文件用 offset/limit）
- `write_file` - 整文件覆盖写入项目文件（不支持 patch/diff/追加）
- `list_dir` - 列出目录内容
- `glob_files` - 按文件名 glob 实时查找项目内文件（只读，自动跳过常见构建/依赖目录）
- `grep_code` - 按关键字或正则实时搜索项目内代码，优先使用 ripgrep，返回文件、行号、可选上下文、partial 状态与 suggested_reads
- `execute_command` - 在当前项目目录执行短时 Shell 命令（默认 60 秒超时，黑名单拦截破坏性命令）
- `create_project` - 创建项目结构（java/python/node）
- `search_code` - 语义检索代码库（自然语言查询，适合作为模糊语义或常规搜索无果时的辅助）
- `web_search` - 搜索互联网获取实时信息
- `web_fetch` - 抓取已知 URL 并提取正文 Markdown
- `revert_turn` - 恢复到最近第 N 个 pre-turn 快照（走 HITL 与审计）
- `tool_status` - 按 `operation_id` 查询有副作用工具调用的状态
- `tool_compensate` - 按 `operation_id` 补偿已成功且可逆的写工具副作用（高风险，走 HITL 与审计）
- `mcp__{server}__{tool}` - MCP server 动态提供的外部工具
- `mcp__{server}__list_resources` / `mcp__{server}__read_resource` - 支持 resources 的 MCP server 自动注册的虚拟工具

同一轮模型返回多个工具调用时，ReviewFlow Agent 会并行执行这些工具；如果工具之间有依赖关系，模型应分多轮调用。

内置工具 schema 会通过 `additionalProperties: false`、枚举、数值范围、默认值和更明确的“何时使用/何时不要使用”描述约束模型选工具；`create_project.type`、`save_memory.scope` 等选项参数使用 enum，`grep_code` / `web_fetch` 等预算参数带上下限。

文件类与代码检索工具（`read_file` / `write_file` / `list_dir` / `glob_files` / `grep_code` / `create_project`）路径强制限定在项目根之内，越界请求会被策略层拒绝；`execute_command` 通过命令黑名单拦截 `sudo` / `rm -rf 全盘` / `mkfs` / `dd of=/dev` / fork bomb / `curl|sh` 等。工具注册时声明风险等级：`READ_ONLY` 默认放行，`LOW_WRITE` 默认放行并审计，`MEDIUM_WRITE` / `HIGH_RISK` 默认触发 HITL；所有 `mcp__` 前缀工具默认按 `HIGH_RISK` 处理。审批请求会展示 fingerprint，参数 JSON 会 canonical normalize 后参与 hash；用户修改参数后按最终参数重新计算 fingerprint；`write_file` 会展示新建文件大小或简短 diff；高风险操作不支持 approve-all。后台 durable 写工具会把幂等键、`operation_id`、前后文件 hash 和可选补偿快照写入 `runtime_tool_executions`；遇到 `UNKNOWN/PENDING` 时 Agent 应先用 `tool_status` 查状态，用户明确要求回滚某个可逆操作时再用 `tool_compensate`。工具结果回灌模型前会包装为 `<paicli_tool_result trust="untrusted">`，原始内容逐行加前缀，并带 `SUCCESS` / `FAILED` / `PARTIAL` / `UNKNOWN` / `PENDING` 统一状态，防止网页、文件、命令输出或 MCP 返回内容反向提示注入，也避免 Agent 从纯文本猜执行结果。设置 `PAICLI_TOOL_INTENT_VALIDATION=true` 后，ReviewFlow Agent 会在工具执行前用 LLM 校验工具调用是否符合用户业务意图；明确不匹配时返回结构化 `INTENT_TOOL_MISMATCH`，不执行真实工具。详见 `/policy`。

## 命令

进程级入口：

- `paicli wechat setup` - 绑定微信 iLink 通道，选择 workspace 并完成扫码确认
- `paicli wechat start` - 前台启动微信通道
- `paicli wechat status` - 查看绑定状态和 daemon pid
- `paicli wechat daemon start|stop|restart|status|logs` - 管理本机微信通道后台进程

交互式斜杠命令：

- `/wechat` - 扫码绑定并启动微信 iLink 通道；已绑定时直接启动
- `/wechat setup` - 重新扫码绑定并启动微信通道
- `/wechat status` - 查看当前 ReviewFlow Agent 进程内微信通道状态
- `/wechat stop` - 停止当前 ReviewFlow Agent 进程内微信通道
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
- `/review pr <url|owner/repo#number|number>` - 拉取 GitHub PR diff / changed files / comments / CI，并启动代码审查
- `review pr <url|owner/repo#number|number> --dry-run --format json` - 非交互拉取 PR 上下文并输出 benchmark/CI 烟测摘要
- `benchmark code-review-bench <offline-dir> --mode smoke|review --limit N --parallel N --only-url PR_URL --timeout-seconds N [--no-checkout]` - 运行 Code Review Bench ReviewFlow Agent 适配层
- `/policy` - 查看安全策略状态（路径围栏 / 命令黑名单 / 资源上限 / 审计目录）
- `/audit [N]` - 查看今日最近 N 条工具审计记录（默认 10）
- `/sandbox status` - 查看 macOS `execute_command` Seatbelt 沙箱状态
- `/sandbox on` / `/sandbox off` - 开启或关闭 macOS 命令沙箱
- `/sandbox strict on` / `/sandbox strict off` - 控制沙箱不可用时是否拒绝命令
- `/sandbox doctor` - 检查当前 macOS 沙箱依赖
- `/snapshot` - 查看最近 Side-Git 快照
- `/snapshot status` - 查看 Side-Git 快照状态
- `/snapshot clean` - 清理当前项目 Side-Git 快照目录
- `/restore <N>` - 恢复到最近第 N 个 pre-turn 快照
- `/memory` / `/mem` - 查看记忆系统状态
- `/memory list` - 查看长期记忆列表
- `/memory search <关键词>` - 搜索当前项目可见长期记忆
- `/memory delete <id>` - 删除单条长期记忆
- `/memory clear` - 清空长期记忆
- `/save <事实>` - 手动保存项目级关键事实到长期记忆；`/save --global <事实>` 保存跨项目通用偏好
- `save_memory` - Agent 内置工具，仅在用户明确要求保存长期偏好或稳定事实时调用；默认 `scope=project`，跨项目通用偏好才用 `scope=global`
- `/init` - 生成精简项目级记忆 `PAI.md`；已存在时不覆盖，`/init --force` 可重写
- `/export` - 导出当前 ReAct 会话对话记录为 Markdown（包含完整 system prompt），写入 `~/.paicli/exports/session-*.md`
- `/index [路径]` - 索引代码库（默认当前目录）
- `/search <查询>` - 语义检索代码（RAG 辅助路径）
- `/graph <类名>` - 查看代码关系图谱
- `/clear` - 清空当前对话历史、短期记忆、待注入 Skill 上下文和上一轮检索记忆注入；长期记忆保留
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
    ██████   ██████  ███████   ███████     ReviewFlow Agent
   ██       ██    ██ ██    ██  ██          Model glm-5.1 (glm)
   ██       ██    ██ ██     ██ ██          MCP 4/4 · 61 tools · 2/2 skills · ReAct
   ██       ██    ██ ██     ██ █████       ReAct · Plan · MCP · Browser · Image · Tools · Memory · RAG
   ██       ██    ██ ██    ██  ██
    ██████   ██████  ███████   ███████

Tips for getting started:
1. Type / for commands and Tab completion
2. Ask coding questions, edit code or run commands
3. Attach context with @path or @image:

* 你好，请列出当前目录的文件

🧠 思考过程:
用户想了解当前目录结构。我先读取目录，再基于结果做归类说明，而不是只回原始文件列表。

🤖 最终结果:
当前目录包含 `src`、`target`、`pom.xml`、`README.md` 等文件，
这是一个标准的 Java Maven 项目。

* /exit

👋 再见!
```

## 技术栈

- Java 17
- Maven
- GLM-5.1 API
- OkHttp
- Jackson
- JLine 4（终端交互、Status、输入 widgets）
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
│   ├── GLMClient.java          # GLM API 客户端；glm-5.1 走 Coding endpoint，glm-5v-turbo 走多模态 endpoint
│   ├── DeepSeekClient.java     # DeepSeek API 客户端
│   ├── StepClient.java         # 阶跃星辰 StepFun API 客户端
│   ├── KimiClient.java         # Kimi / Moonshot API 客户端
│   ├── FreeLlmApiClient.java   # 本地 FreeLLMAPI OpenAI-compatible 网关客户端
│   └── AgnesClient.java        # Agnes AI OpenAI-compatible 客户端
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
