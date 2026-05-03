# PaiCLI 迭代路线图（17 期）

从零开始，逐步构建生产级 Java Agent CLI

---

## 第1期：基础ReAct + Tool Call ✅

**已完成**

- ReAct循环（思考-行动-观察）
- GLM-5.1 API集成
- 5个基础工具（文件、Shell、项目创建）
- 交互式CLI
- 约400行代码

**核心知识点**：ReAct模式、Function Calling、Agent基础架构

---

## 第2期：Plan-and-Execute + 多轮规划 ✅

**目标**：让Agent能处理复杂多步任务

**功能迭代**：
- Plan-and-Execute模式（先规划后执行）
- 任务分解（Task Decomposition）
- 子任务依赖管理
- 执行计划可视化
- 计划失败时的重规划

**核心知识点**：
- Plan-and-Solve模式
- 任务DAG管理
- 规划-执行分离架构

**教程标题候选**：《Agent只会一步一步执行？教它先规划后行动，复杂任务也能搞定》

---

## 第3期：Memory系统 + 上下文工程 ✅

**目标**：让Agent有记忆，能处理长对话

**功能迭代**：
- 短期记忆（对话历史管理）
- 长期记忆（关键信息持久化）
- 上下文压缩（摘要生成）
- Token预算管理
- 记忆检索（相似度匹配）

**核心知识点**：
- Context Window管理
- 记忆分层架构
- 摘要算法（Map-Reduce）

**教程标题候选**：《Agent记性太差？给它装上记忆系统，长对话也不忘事》

---

## 第4期：RAG检索 + 代码库理解 ✅

**已完成**

**目标**：让Agent能理解整个代码库

**功能迭代**：
- 代码向量化（Embedding），支持本地 Ollama 和远程 API
- 向量数据库（SQLite + 内存余弦检索）
- 代码分块与索引（文件/类/方法粒度）
- 语义检索（自然语言搜代码）
- 代码关系图谱（类、方法依赖）

**核心知识点**：
- RAG架构
- Code Embedding
- 向量检索
- AST 分析

**教程标题候选**：《Agent看不懂你的代码库？接入RAG，让它秒懂项目结构》

---

## 第5期：Multi-Agent协作 + 角色分工 ✅

**已完成**

**目标**：多个Agent协作完成复杂任务

**功能迭代**：
- Agent角色定义（规划者、执行者、检查者）
- Agent间通信机制
- 任务分配与协调
- 冲突解决策略
- 主从Agent架构

**核心知识点**：
- Multi-Agent系统
- 角色扮演（Role Playing）
- 分布式任务协调

**教程标题候选**：《一个Agent忙不过来？搞个团队，规划、执行、检查分工干》

---

## 第6期：Human-in-the-Loop + 审批流 ✅

**已完成**

**目标**：关键操作人工确认，安全可控

**功能迭代**：
- 危险操作静态规则识别（`write_file`、`execute_command`、`create_project`）
- 三级危险等级（高危 / 中危 / 安全）
- 审批决策：批准 / 全部放行 / 拒绝 / 跳过 / 修改参数后执行
- HITL 默认关闭，`/hitl on|off` 运行时切换
- `HitlToolRegistry` 透明拦截层，HITL 关闭时与普通 `ToolRegistry` 行为完全相同

**HITL 增强（后续补丁，归在本期叙事下）**：
- `PathGuard` 路径围栏：`read_file` / `write_file` / `list_dir` / `create_project` 强制限定在项目根之内，拦截绝对路径越界、`..` 穿越、符号链接逃逸
- `CommandGuard` 命令快速拒绝：HITL 之前的 fast-fail 黑名单（sudo / rm -rf 全盘 / mkfs / dd 写裸设备 / fork bomb / curl|sh / find / / chmod 777 / / shutdown），减少 HITL 弹窗骚扰
- `AuditLog` 操作审计链：危险工具调用按天写 JSONL 到 `~/.paicli/audit/`，含 `outcome (allow|deny|error)` 与 `approver (hitl|policy|none)`
- `write_file` 单文件 5MB 上限
- CLI 命令：`/policy` 看安全策略状态、`/audit [N]` 看最近审计

**为什么不叫沙箱**：
- 真正的沙箱是隔离的执行环境（Docker / microVM / chroot），本地 Agent CLI（参考 Claude Code / Cursor / Aider）默认都不做沙箱——沙箱削弱 Agent 能力、给虚假安全感、体验更差
- PaiCLI 的安全模型是 **HITL + 路径校验 + 命令快速拒绝 + 审计**，不是隔离
- 想做容器隔离的请参考 Pro 升级版本章节，或自行实现 `SandboxDriver` 接口

**核心知识点**：
- HITL（人机协同）
- 中断处理
- 安全策略
- 路径解析与符号链接安全（`Files.toRealPath` 防逃逸）
- 结构化审计（JSONL、按天分文件、并发安全）

**教程标题候选**：《Agent权限太大怕搞砸？加上人工审批，安全又放心》

---

## 第7期：异步执行 + 并行工具调用 ✅

**已完成**

**目标**：提升执行效率，支持长时间任务

**功能迭代**：
- 同一轮 LLM 返回多个 `tool_calls` 时并行执行
- ReAct、Plan-and-Execute、Multi-Agent Worker 复用统一批量工具执行入口
- Plan-and-Execute 按 DAG 依赖批次并行执行独立任务
- Multi-Agent 按依赖批次并行调度多个 Worker
- 工具批次统一超时，超时工具会被取消并返回可回灌结果

**核心知识点**：
- 异步编程模型
- 并发控制
- 任务调度

**教程标题候选**：《Agent执行太慢？上异步+并行，编译测试一起跑》

---

## 第8期：多模型适配 + 运行时切换（GLM / DeepSeek）✅

**已完成**

**目标**：支持多模型运行时切换，GLM-5.1 和 DeepSeek V4 双模型

**功能迭代**：
- `LlmClient` 接口抽象：将 GLMClient 的内部类型（Message、ToolCall、Tool 等）提升为接口级公共类型
- `AbstractOpenAiCompatibleClient` 基类：共享 SSE 流式解析、请求构建、工具调用增量合并逻辑
- `GLMClient` / `DeepSeekClient` 瘦子类：各约 20 行，仅提供 API URL、模型名、API Key
- 运行时模型切换：`/model glm` `/model deepseek` 命令实时切换当前对话模型
- 配置持久化：`~/.paicli/config.json` 存储默认模型，支持 `.env` 回退读取 API Key
- `LlmClientFactory` 工厂：根据 provider 名称和配置创建对应客户端

**核心知识点**：
- 策略模式 + Provider 抽象
- OpenAI 兼容协议
- 模板方法模式（AbstractOpenAiCompatibleClient）
- 运行时配置管理

**教程标题候选**：《只能用一个模型？策略模式 + 模板方法，GLM 和 DeepSeek 随时切换》

---

## 第9期：联网能力 + Web工具

**目标**：让 Agent 能访问互联网，获取实时信息（不涉及浏览器操控，那部分见第13/14期）

**功能迭代**：
- `web_search` 工具升级：在第7期 SerpAPI 最小落地的基础上，把搜索结果结构化、字段稳定化
- `web_fetch` 工具：抓取指定 URL 页面内容，自动提取正文（去除 HTML 标签 / 广告 / 导航）
- 搜索结果摘要：LLM 对检索结果二次提炼，只保留与用户问题相关的信息
- 网络访问安全：URL 白名单 / 黑名单、请求频率限制、响应体大小限制
- Agent 提示词升级：让 Agent 知道何时该用联网工具（如"最新版本是什么"、"官方文档怎么说"），以及和本地工具的边界

**核心知识点**：
- 搜索引擎 API 集成
- HTML 正文提取（Jsoup / Readability 算法）
- 网络访问安全策略
- Agent 工具选择 prompt 设计

**教程标题候选**：《Agent 与世隔绝？让它学会搜索和抓取，实时信息一手到位》

---

## 第10期：MCP 协议核心（stdio + Streamable HTTP，默认开启） ✅

**已完成**

**目标**：把 PaiCLI 接入 MCP 生态。stdio 子进程 server 与 Streamable HTTP 远程 server 都能用，工具自动注册到 ToolRegistry，与 HITL / AuditLog 协同。

**功能迭代**：
- 手写 `JsonRpcClient`：JSON-RPC 2.0 客户端，请求-响应配对、通知、错误码、超时
- `McpTransport` 抽象 + 两个实现：
  - `StdioTransport`：ProcessBuilder + newline-delimited JSON-RPC，stderr 单独 drain，JVM 退出 hook 清理子进程
  - `StreamableHttpTransport`：OkHttp + 单 POST + 服务端 SSE 流式响应，支持 session ID
- `initialize` 握手 + capabilities 协商 + protocol version negotiation
- `tools/list` + `tools/call`：工具按 `mcp__{server}__{tool}` 前缀注册到 `ToolRegistry`
- MCP 返回 `content` 数组扁平化（text 拼接，image / resource 给 fallback 提示）
- 配置文件：`~/.paicli/mcp.json`（用户级）+ `.paicli/mcp.json`（项目级，可入 git），格式与 Claude Code `claude_desktop_config.json` 兼容
- 启动时 eager 并行启动所有 server（复用第 7 期并行调度）
- **默认开启**，`/mcp disable <name>` 关单个
- HITL + AuditLog 集成：MCP 工具默认走 HITL，audit `tool` 字段带 `mcp__` 前缀
- CLI：`/mcp` / `/mcp restart <name>` / `/mcp logs <name>` / `/mcp disable <name>` / `/mcp enable <name>`
- MCP 子系统默认启动；未配置 `mcp.json` 时不启动外部 server，避免首次运行被 `npx` / `uvx` 冷启动阻塞

**核心知识点**：
- JSON-RPC 2.0 协议实现
- 长 running 子进程生命周期管理（NIO + 流分离）
- Streamable HTTP（2025 年 3 月新规范，替代已废弃的 SSE）
- 第三方工具源进入安全模型的纳管方式（HITL + Audit + 命名空间隔离）

**估算**：5–6 天

---

## 第11期：MCP 高级能力（resources 双轨 + prompts 查看 + 被动通知） ✅

**前置依赖**：第 10 期 MCP 协议核心

**目标**：优先补齐 MCP resources 体验，对齐 Claude Code 的资源引用方式，并提供 prompts 查看、被动通知处理与运行中取消。OAuth 与 sampling 已确认延后，不计入本期交付。

**功能迭代**（详细开发任务见 `docs/phase-11-mcp-advanced.md`）：

- **resources 双轨**（参考 Claude Code）：
  - 工具层：每个支持 resources 的 server 注册 `mcp__{server}__list_resources` / `mcp__{server}__read_resource` 虚拟工具，让 LLM 自决
  - 用户 @-mention 层：`@server:protocol://path` 语法 + jline 自动补全，输入预处理时 fetch 内容并替换为 `<resource>` 内联块
  - `resources/list_changed` / `resources/updated` 到达后只做缓存失效，下次 read/list 重拉
- **prompts 查看**：`/mcp prompts <server>` 展示 server 暴露的 prompt 模板；不加载到对话流
- **双向通知（被动）**：
  - `tools/list_changed` → 重拉工具列表 → `replaceMcpToolsForServer` 全量替换
  - `resources/list_changed` / `resources/updated` → cache 失效
  - **不做 health ping**，不主动探活，避免对按量或按月计费 server 造成额外负担
- **新增 CLI**：`/mcp resources <server>`、`/mcp prompts <server>`
- **运行中取消**：任务执行期间输入 `/cancel` 并回车，请求取消当前 Agent run；ReAct、Plan、Team、工具批次与 `execute_command` 在边界处协同检查取消信号

**不做（明确边界）**：
- OAuth 2.0 Authorization Code + PKCE
- `sampling/createMessage`
- MCP server 自动重启
- prompts 加载到对话流（仅保留 `/mcp prompts` 查看 server 暴露的模板）
- resources 自动注入 system prompt（第 12 期长上下文模式已接入 URI / 描述索引）
- server health ping / heartbeat
- progress / logging notification 的 UI 展示
- OAuth Device Flow / Client Credentials

**核心知识点**：
- MCP resources/list + resources/read 的工具化封装
- 用户显式 `@server:protocol://path` resource 引用与上下文注入
- jline `Completer` 与 raw mode 的协同（@-mention autocomplete 不能干扰 plan/team raw mode 路径）
- 被动通知响应模式 vs 主动 ping 的取舍（按月计费的 server 必须不主动 ping）

**验证**：`mvn test` 336 tests 通过

---

## 第12期：长上下文工程（适配 200k–1M 模型 + prompt caching） ✅

**目标**：适配 GLM-5.1（200k）/ DeepSeek V4（1M）/ Claude Sonnet 4.6（1M）等长上下文模型。第 3 期 Memory 是基于"短上下文兜底"假设设计的，长窗口下要切换策略。

**功能迭代**（详细开发任务见 `docs/phase-12-long-context.md`）：
- `LlmClient` 接口扩展能力声明：`maxContextWindow()` / `supportsPromptCaching()` / `promptCacheMode()`
- `ContextProfile` 统一管理 short / balanced / long 三种上下文模式
- `AgentBudget` token 预算从写死 300K 改为按当前模型动态计算（默认 80% × maxContextWindow，仍支持系统属性覆盖）
- 长 / 短上下文双模式：
  - 短 / balanced：保留第 3 期 Memory 摘要压缩策略
  - long（≥ 100k window）：跳过摘要压缩，提高 RAG 默认 topK（20），扩大短期记忆预算
- prompt caching 接入：
  - 能力声明与 `/context` 可见化
  - OpenAI-compatible usage 中解析 cached input tokens
  - DeepSeek V4 走 automatic prefix cache；当前不注入未确认兼容的 provider 私有字段
- 上下文成本可见化：每轮输出 `已用 X / Y token (window W, cached: Z, 估算 ¥A)`
- 检索策略自适应：`search_code` 未传 `top_k` 时按上下文模式选择 5 / 10 / 20
- **MCP resources 自动注入**（与第 11 期联动）：长模式下，把所有 server 已知 resources 的 URI + 描述（不含 body）作为索引注入 system prompt；ReAct / Plan / Team 都接入
- `/context` 命令扩展：显示当前 window、动态预算、模式、prompt cache、RAG topK、resources 是否已自动注入

**核心知识点**：
- 长上下文模型的成本模型（input vs cached input 价差通常 5–10 倍）
- prompt caching 的缓存边界设计
- RAG 在长上下文时代的角色变化（从"压缩选择"到"加速 + 精排"）
- 资源索引（MCP resources URI + 描述）作为长上下文的有效填充

**验证**：`mvn test` 347 tests 通过；`mvn clean package` 通过

---

## 第13期：Chrome DevTools MCP ✅

**前置依赖**：第 10 / 11 期 MCP 框架（已完成）

**目标**：让 Agent 能操控浏览器，处理需要 JS 渲染、防爬墙、表单交互、登录态的页面（如微信公众号文章、知乎专栏、SPA 应用等）。

**功能迭代**（详细开发任务见 `docs/phase-13-chrome-devtools-mcp.md`）：

- 接入 Google 官方 `chrome-devtools-mcp@latest`（28 个工具：导航 / 输入 / 调试 / 网络 / 性能 / 模拟 / 扩展 / 内存）
- **默认 enabled**：`~/.paicli/mcp.json` 不存在时启动自动创建模板，含 chrome-devtools 条目
- `image` content 处理走**路线 B**：fallback 文案引导 LLM 优先用 `take_snapshot`（DOM 文本快照）而非 `take_screenshot`；不做真 multimodal LLM 输入（拆到第 17 期）
- HITL「全部放行」改为 **server 维度**：用户对 chrome-devtools 选 `a → server` 后，连续浏览器操作只需确认一次（`approvedAllByServer` 集合 + 子菜单）
- `Agent` / `PlanExecuteAgent` / `SubAgent` 系统提示词加「web_fetch vs 浏览器 MCP」决策表，明示微信公众号 / 知乎 / 推特等典型 web_fetch 失败站点直接走浏览器
- `McpClient.initialize` 超时 30s → 60s（chrome-devtools 首次启动需 npx 拉包 + Chrome 冷启 ≈ 20s+），可被 `paicli.mcp.initialize.timeout.seconds` 覆盖
- `McpServerManager.startAll` 启动期间另起 status printer 线程，每 5s 打印未就绪 server 等待时长
- 必跑端到端测试：微信公众号文章（`https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg`），验证 web_fetch 失败 → LLM 自动 fallback 到浏览器 → take_snapshot 拿正文

**不做（明确边界）**：
- 真 multimodal LLM 输入（拆到第 17 期「多模态 LLM 输入」）
- CDP 会话复用 / 登录态识别（第 14 期）
- Playwright / Firefox / WebKit 跨浏览器
- 浏览器执行隔离（默认 `--isolated=true` 临时 user-data-dir，第 14 期改 `--browser-url` 复用已开 Chrome）

**核心知识点**：
- 第三方 MCP server 接入实战（直接用 Google 官方 server，不再造轮子）
- HITL 全放行的多维度设计（tool 维度 vs server 维度）
- LLM 自动决策 fallback 路径（web_fetch 拿不到 → 提示词引导走浏览器）
- 长启动 server 的 UX 工程（进度提示 + 超时调整）

**教程标题候选**：《静态抓取不够看？接 Chrome DevTools MCP，让 Agent 自己开浏览器》

**验证**：单元测试覆盖默认 MCP 配置创建、HITL server 维度全放行、MCP image fallback 与初始化超时；真实浏览器端到端需本机 Chrome + API Key 环境执行。

---

## 第14期：CDP 会话复用 + 登录态访问 ✅

**前置依赖**：第13期 Chrome DevTools MCP 已能驱动浏览器

**目标**：让 Agent 复用带登录态的调试 Chrome 实例，访问需要认证的页面

**功能迭代**：
- 通过 `/browser connect [port]` 探活 `127.0.0.1:<port>/json/version`，运行时把 `chrome-devtools` 切到 `--browser-url=http://127.0.0.1:<port>`
- 复用调试 Chrome 登录态访问 GitHub、内部系统等需认证页面；默认 `mcp.json` 仍保持 `--isolated=true`
- `/browser status` / `/browser tabs` / `/browser disconnect` 提供会话状态、tab 查看和回到 isolated 的入口
- 登录态访问安全约束已落地：敏感页面识别、改写型工具单步 HITL、shared 模式 `close_page` 硬保护
- 审计日志为 chrome-devtools 工具追加浏览器 metadata，同时兼容旧 JSONL

**核心知识点**：
- Chrome 远程调试端口工作机制
- 登录态复用与隔离
- 认证页面的安全策略

**教程标题候选**：《要登录才能看？让 Agent 连上你的调试 Chrome，省掉重复打开页面的麻烦》

---

## 第15期：Skill 系统 + web-access Skill

**前置依赖**：第 9 期 web 工具、第 13 期 Chrome DevTools MCP、第 14 期 CDP 会话复用全部就绪

**目标**：做出 PaiCLI 自己的 Skill 加载机制，把零散的工具与决策指引打包成可复用单元，并以 web-access 作为首个落地 Skill

**功能迭代**：
- Skill 加载机制：扫描目录，解析 `SKILL.md`（frontmatter + 触发词 + 指令体）
- Skill 注册：启动时把每个 Skill 的 metadata 注入 system prompt，触发词命中时再展开完整指令
- Skill 目录约定：`<root>/SKILL.md`、可选 `scripts/`、可选 `references/`
- 内置 web-access Skill：把第 9–14 期的联网能力打包成「何时搜索 / 何时抓取 / 何时开浏览器 / 何时复用登录态」的决策手册，附带站点经验目录
- 集成 Jina Reader 作为 fallback：`web_fetch` 拿不到正文（SPA / 防爬墙）时，可选降级到 `r.jina.ai/<url>` 拿干净 Markdown，与本地 readability 形成「先本地、再第三方、最后浏览器」的三档兜底
- CLI 命令：`/skill list`、`/skill on <name>`、`/skill off <name>`、`/skill reload`
- Skill 与 HITL 协同：Skill 内调用危险工具仍走 HITL 审批，不绕过

**核心知识点**：
- 提示词工程的工程化封装
- 触发词路由与按需加载
- 经验沉淀目录（按域名/场景累积可复用知识）
- 设计意图：从「写工具」演进到「打包专家手册」

**教程标题候选**：《工具堆成山，Agent 还是不会用？给它写本「专家手册」，按场景自动展开》

---

## 第16期：TUI界面 + 产品化

**目标**：从CLI到完整产品体验

**功能迭代**：
- 终端TUI界面（Lanterna/JLine）
- 文件树浏览
- 代码高亮显示
- 对话历史可视化
- 配置文件管理
- 安装包分发

**核心知识点**：
- TUI开发
- 终端渲染
- 产品工程化

**教程标题候选**：《CLI太简陋？做个漂亮的TUI界面，体验不输Claude Code》

---

## 第17期：多模态 LLM 输入（vision）

**前置依赖**：第 13 期 Chrome DevTools MCP 已能产出截图等 image content；第 12 期长上下文工程已就绪。

**目标**：让 PaiCLI 真正"看见"图片——浏览器截图、用户粘贴的图片、文档中的图表，都能直接喂给 LLM 让它理解，而不是 fallback 成"[此工具返回了 image]"占位。

**功能迭代**：

- `LlmClient.Message.content` 协议升级：从 `String` 扩展为 `List<ContentPart>`（含 `text` / `image_base64` / `image_url`）
- 各 `LlmClient` 实现适配 OpenAI 兼容的 multimodal API：
  - GLM-5.1V（智谱多模态变体）
  - DeepSeek V4（如声明 vision 能力）
  - Claude Sonnet / Opus 系列
- `LlmClient` 接口加 `supportsVision()` 能力声明
- 第 13 期的 `take_screenshot` image fallback 升级为真 multimodal 输入（仅在当前模型 `supportsVision()` 时生效）
- 用户输入层：终端粘贴 base64 图片或 `@image:file://path/to/img.png` 显式引用
- HITL 弹窗展示图片元数据（mimeType / size），不展示原图
- 按 token 成本审计：image input 单独计费维度（多数 vision API 按 image tile 数计 token）

**不做**：
- 视频 / 音频输入（再独立期）
- 图像生成（PaiCLI 是 Agent 不是绘图工具）
- TUI sixel 协议显示截图（依赖第 16 期 TUI 是否实现，留作扩展）

**核心知识点**：
- OpenAI 兼容协议的 multimodal 扩展（content array vs string）
- 各模型 vision 输入定价模型差异
- base64 图片在 JSON-RPC / HTTP / 流式响应里的传输与缓存
- Agent 何时该截图、何时该读 DOM、何时该问用户（决策权 vs 成本）

**教程标题候选**：《Agent 不能只看文字？接通视觉能力，截图 / 图表 / 设计稿都能"看"》

**估算**：5–6 天

---

## 技术栈演进图

```
第1期 ──► 第2期 ──► 第3期 ──► 第4期 ──► 第5期 ──► 第6期 ──► 第7期 ──► 第8期
基础      规划      记忆      RAG       多Agent   人机      异步      多模型
ReAct    执行     上下文    检索       协作      协同      并行      切换

第9期 ──► 第10期 ──► 第11期 ──► 第12期 ──► 第13期 ──► 第14期 ──► 第15期 ──► 第16期 ──► 第17期
联网     MCP核心    MCP高级     长上下文    Chrome     CDP        Skill      TUI       多模态
能力     stdio+HTTP rsc/sample  200k-1M    DevTools   会话复用    系统       产品化     vision
```

## 学习路径建议

**入门**：按顺序 1 → 2 → 3 → 6 → 16，掌握核心即可
**进阶**：1 → 2 → 3 → 4 → 7 → 8 → 9 → 10 → 12 → 13 → 15，深入技术细节
**全套**：全部 17 期

## 参考项目

- **Claude Code**：人机协同、TUI界面
- **OpenClaw**：多Agent、MCP集成
- **PaiAgent**：工作流编排、可视化
- **LangGraph**：状态管理、循环控制
- **Spring AI**：多模型适配、工具回调

---

## Pro 升级版本（独立分支）

主线 17 期完成后，将开启独立分支做框架重构，作为「手写版 → 框架版」的对照实现。不并入主分支，主线手写版保持稳定基线。

**触发时机**：主线 1–17 期全部交付后启动

**候选实现**：

- **Spring AI 版本**：用 `ChatModel` / `StreamingChatModel` / `ToolCallback` / Spring Boot DI 重写主流程；`Agent` / `PlanExecuteAgent` / `AgentOrchestrator` / `ToolRegistry` / `MemoryManager` 全面 Bean 化；HITL 通过 AOP 拦截
- **LangGraph4J 版本**：用图状态机模型重构 Agent 流程，把 ReAct / Plan-and-Execute / Multi-Agent 三种模式统一到 graph 抽象下，节点 = 角色/工具调用，边 = 状态转移条件

**设计价值**：完整呈现「自己造轮子 → 用社区轮子」的取舍——什么场景手写更清晰、什么场景框架更省心，让用户既能看懂底层、又能切换主流框架。

---

*已完成第 14 期 CDP 会话复用 + 登录态访问。下一步进入第 15 期 Skill 系统，OAuth / sampling / recovery 留给后续 MCP 增强期。*
