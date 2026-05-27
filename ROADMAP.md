# PaiCLI 迭代路线图（21 期）

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

## 第8期：多模型适配 + 运行时切换（GLM / DeepSeek / StepFun / Kimi）✅

**已完成**

**目标**：支持多模型运行时切换，当前包含 GLM-5.1、DeepSeek V4、StepFun 和 Kimi K2.6

**功能迭代**：
- `LlmClient` 接口抽象：将 GLMClient 的内部类型（Message、ToolCall、Tool 等）提升为接口级公共类型
- `AbstractOpenAiCompatibleClient` 基类：共享 SSE 流式解析、请求构建、工具调用增量合并逻辑
- `GLMClient` / `DeepSeekClient` / `StepClient` / `KimiClient` 瘦子类：仅提供 API URL、模型名、API Key 与 provider 差异
- 运行时模型切换：`/model glm` `/model deepseek` `/model step` `/model kimi` 命令实时切换当前对话模型
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

**目标**：适配 GLM-5.1（200k）/ DeepSeek V4（1M）/ StepFun（256k）/ Kimi K2.6（256k）/ Claude Sonnet 4.6（1M）等长上下文模型。第 3 期 Memory 是基于"短上下文兜底"假设设计的，长窗口下要切换策略。

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
- `image` content 处理走**路线 B**：fallback 文案引导 LLM 优先用 `take_snapshot`（DOM 文本快照）而非 `take_screenshot`；不做真 图片复制粘贴输入（拆到第 21 期）
- HITL「全部放行」改为 **server 维度**：用户对 chrome-devtools 选 `a → server` 后，连续浏览器操作只需确认一次（`approvedAllByServer` 集合 + 子菜单）
- `Agent` / `PlanExecuteAgent` / `SubAgent` 系统提示词加「web_fetch vs 浏览器 MCP」决策表，明示微信公众号 / 知乎 / 推特等典型 web_fetch 失败站点直接走浏览器
- `McpClient.initialize` 超时 30s → 60s（chrome-devtools 首次启动需 npx 拉包 + Chrome 冷启 ≈ 20s+），可被 `paicli.mcp.initialize.timeout.seconds` 覆盖
- `McpServerManager.startAll` 启动期间另起 status printer 线程，每 5s 打印未就绪 server 等待时长
- 必跑端到端测试：微信公众号文章（`https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg`），验证 web_fetch 失败 → LLM 自动 fallback 到浏览器 → take_snapshot 拿正文

**不做（明确边界）**：
- 真 图片复制粘贴输入（拆到第 21 期「图片复制粘贴输入」）
- CDP 会话复用 / 登录态识别（第 14 期）
- Playwright / Firefox / WebKit 跨浏览器
- 浏览器执行隔离（默认 `--isolated=true` 临时 user-data-dir，第 14 期通过 `--autoConnect` 或旧式 `--browser-url` 复用已开 Chrome）

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
- 通过 Agent 内部 `browser_connect` 或 `/browser connect` 按需切到 `--autoConnect`，复用已在 `chrome://inspect/#remote-debugging` 允许远程调试的 Chrome；`/browser connect <port>` 保留旧式 `--browser-url=http://127.0.0.1:<port>` 兼容路径
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

## 第15期：Skill 系统 + web-access Skill ✅

**已完成**

**前置依赖**：第 9 期 web 工具、第 13 期 Chrome DevTools MCP、第 14 期 CDP 会话复用全部就绪

**目标**：做出 PaiCLI 自己的 Skill 加载机制，把零散的工具与决策指引打包成可复用单元，并以 web-access 作为首个落地 Skill

**功能迭代**（详细开发任务见 `docs/phase-15-skill-system.md`）：
- Skill 加载机制：三层目录扫描（jar 内置 / 用户级 `~/.paicli/skills/` / 项目级 `<project>/.paicli/skills/`），按 name 整体覆盖，frontmatter 走手写 YAML 子集解析（不引 SnakeYAML）
- 启动期把启用 skill 的 `name` + `description` 注入 system prompt 索引段（单 description ≤ 500 codepoint，启用上限 20 个，索引段 ≤ 4KB）
- 内置工具 `load_skill(name)`：LLM 主动调用以把 SKILL.md 正文写入 `SkillContextBuffer`，下一轮 user message 自动前置注入（lazy 展开，节省 token）
- `SkillContextBuffer`：一次性消费、最多保留 3 个 skill body、`/clear` 可 reset
- 内置 web-access Skill：决策手册（浏览哲学四步法 + 工具选择表 + 浏览器优先级 + Jina 兜底说明）+ 6 个站点经验文件（mp.weixin / zhuanlan.zhihu / x.com / xiaohongshu / github / juejin）+ cdp-cheatsheet
- 启动期 `SkillBuiltinExtractor` 把 jar 内置 skill 解压到 `~/.paicli/skills-cache/`，按 `.version` 文件控制重建
- CLI 命令：`/skill` / `/skill list` / `/skill show <name>` / `/skill on <name>` / `/skill off <name>` / `/skill reload`
- Jina Reader 集成：**只**在 web-access SKILL.md 写入「web_fetch 失败可让 execute_command 调 r.jina.ai」的提示，**不**改 `web_fetch` 工具内部链路（保持第 9 期纯本地约定）
- Skill 与 HITL 协同：Skill 内调用 `execute_command` / 浏览器 MCP 等危险工具仍走 HITL 审批，沿用 `execute_command` 工具维度全放行；不给 Skill 单独审批维度

**核心知识点**：
- 提示词工程的工程化封装
- 触发词路由与按需加载
- 经验沉淀目录（按域名/场景累积可复用知识）
- 设计意图：从「写工具」演进到「打包专家手册」

**教程标题候选**：《工具堆成山，Agent 还是不会用？给它写本「专家手册」，按场景自动展开》

**验证**：`mvn test` 457 tests 通过；`mvn clean package` 通过

---

## 第16期：TUI界面 + 产品化 ✅

**目标**：从CLI到完整产品体验

**功能迭代**：
- 终端TUI界面（Lanterna/JLine）
- 文件树浏览
- 代码高亮显示
- 对话历史可视化（`~/.paicli/history/session_*.jsonl`）
- 配置文件管理（TUI `/config` 面板）
- TUI 输入桥接真实 ReAct / Plan / Team 执行链
- TUI HITL 模态审批（批准 / 拒绝 / 跳过）
- 安装包分发

**第 16.1 期形态修正（v16.1.0）**：
- 抽出 `Renderer` 接口 + 三个实现：inline 流式（默认）/ lanterna 全屏（保留）/ plain 兜底
- 默认形态切换为 **inline 流式 TUI**（Claude Code 风格），主屏直出 + 底部 DECSTBM 状态栏 + 行内可折叠工具块（`ctrl+o`）+ 行内 diff
- HITL 改为单字符 `[y/n/a/s/m]` 提示；`/config` 改为浮起 palette
- 切换：`PAICLI_RENDERER=inline|lanterna|plain`，旧 `PAICLI_TUI=true` 兼容映射到 lanterna

**核心知识点**：
- TUI开发
- 终端渲染（DECSTBM、ANSI 局部重绘、JLine widget 绑定）
- 产品工程化（接口抽象 + 多形态切换）

**教程标题候选**：《CLI太简陋？做个漂亮的TUI界面，体验不输Claude Code》

---

## 第17期：LSP 诊断注入（开发体验安全网）

**前置依赖**：第 6 期 HITL 审批流、第 16 期 TUI 产品化

**目标**：Agent 改完代码后，立即注入编译诊断，而不是等用户手跑 `mvn compile` 再告诉 Agent。对标 Claude Code / DeepSeek TUI 的招牌功能。

**功能迭代**：

- `LspManager`：按语言惰性启动 LSP server（JDT LS for Java、rust-analyzer for Rust、pyright for Python、gopls for Go），通过 stdio JSON-RPC 通信，per-language transport pool 复用连接
- 最小 LSP 协议子集：`initialize` → `textDocument/didOpen` → `textDocument/didChange` → 收集 `textDocument/publishDiagnostics`
- `LspHooks`：挂接在 `ToolRegistry.executeTool()` 的执行后路径上——`edit_file` / `apply_patch` / `write_file` 成功后，对编辑的文件调 `runPostEditLspHook()`
- `flushPendingLspDiagnostics()`：在每轮 LLM 请求前，把收集到的诊断作为合成 user message 注入——模型在下一轮推理前就能看到编译错误
- 诊断格式化：按 severity（error / warning / info）+ 文件路径 + 行号 + message 渲染为内联诊断块，限制单次注入条数（默认最多 20 条 diagnosis）
- TUI 展示：inline 模式下诊断块以红色/黄色 ANSI 渲染，用户可以直观看到 Agent 引入的编译问题
- 优雅降级：LSP server 启动失败或超时时只打 trace 日志，不阻塞 Agent 主流程；没有对应 LSP server 的语言跳过

**设计参考**：DeepSeek TUI `crates/tui/src/lsp/`——`LspManager`（惰性 transport pool）+ `lsp_hooks.rs`（post-edit 挂钩）+ `diagnostics.rs`（诊断类型与渲染）。PaiCLI 的 Java 生态可以用 Eclipse JDT LS（`org.eclipse.jdt.ls`）或直接复用已有的 `CodeAnalyzer` 做轻量版。

**核心知识点**：
- LSP（Language Server Protocol）的 JSON-RPC 子集
- 外部进程生命周期管理（ProcessBuilder + stdio 流分离）
- Post-edit hook 注入模式（Agent 执行链的扩展点）
- 合成消息注入（在 tool_result 之后、下一轮 LLM 请求之前）

**教程标题候选**：《Agent 改完代码就报错？给它接上 LSP，编译错误当场发现》

---

---

## 第18期：Git Side-History 快照与回滚（文件安全网）

**前置依赖**：第 7 期异步执行、第 16 期 TUI 产品化

**目标**：Agent 每次 turn 前后自动做 workspace 快照，用户可以一键回滚到任意 turn 之前的状态，不污染用户的 `.git` 历史。对标 DeepSeek TUI 的 `snapshot/` 系统。

**功能迭代**：
- `SideGitManager`：在 `~/.paicli/snapshots/<project_hash>/<worktree_hash>/.git` 维护独立 side-git 仓库，通过 JGit 纯 Java 实现，与用户的工作区 `.git` 完全隔离
- `preTurnSnapshot()`：每个 turn 开始前，对 workspace 执行 JGit add/commit 并标记 `"pre-turn <turn_id>"`；MVP 采用同步 pre 快照，确保 Agent 改文件前已经保存基线
- `postTurnSnapshot()`：turn 结束后异步执行第二次快照，commit message 标记 `"post-turn <turn_id>"`
- `/restore <N>` 命令：从最近 N 个 turn 的 pre-turn 快照中恢复文件到工作区，不改变用户 `.git` 和对话历史
- `revert_turn` 工具：LLM 可调用的回滚工具，让 Agent 自己能判断"改坏了需要撤销"
- 快照策略可配：`max_snapshots`（默认保留最近 50 个 turn）、`snapshot_excludes`（默认排除 `.git/`、`node_modules/`、`target/`）

**设计参考**：DeepSeek TUI `crates/tui/src/core/engine.rs` 的 `pre_turn_snapshot()` / `post_turn_snapshot()` + `crates/tui/src/core/turn.rs` 的 `pre_tool_snapshot()`。

**核心知识点**：
- Git 内部对象模型（tree / commit / blob）与 JGit API
- Side-git 仓库隔离技术（独立 `.git` 目录 + `--work-tree` 等价操作）
- Turn 粒度的自动快照策略
- 异步快照不阻塞主流程的 fire-and-forget 模式

**教程标题候选**：《Agent 改坏文件怎么办？自动 Git 快照 + 一键回滚，放心让它改》

---

## 第19期：Prompt 分层架构（可维护性重构）

**前置依赖**：第 1–16 期全链路（所有 system prompt 的累积）

**当前状态**：MVP 已落地。ReAct、Plan task executor、Multi-Agent 三角色、Planner 已接入 `PromptAssembler`，内置资源位于 `src/main/resources/prompts/`，覆盖路径支持 `~/.paicli/prompts/...` 与 `.paicli/prompts/...`。

**目标**：把分散在 `Agent.java` / `PlanExecuteAgent.java` / `SubAgent.java` 三处的硬编码 system prompt 重构为编译时嵌入的 Markdown 分层，支持用户级覆盖，让 prompt 调优从"改 Java 源码 + 重编译"变成"改 Markdown 文件"。

**功能迭代**：
- 分层 prompt 文件（`src/main/resources/prompts/`）：
  - `base.md`：核心规则（工具使用、输出格式、子 Agent 协议、上下文管理）
  - `modes/agent.md` / `modes/plan.md` / `modes/planner.md` / `modes/team-planner.md` / `modes/team-worker.md` / `modes/team-reviewer.md`：各模式的工作流预期和权限
  - `approvals/suggest.md` / `approvals/auto.md` / `approvals/never.md`：审批策略
  - `personalities/calm.md`：语调（保留现有 `AGENTS.md` 中的 Personality 规范）
- `PromptAssembler`：按固定顺序组装（base → personality → mode → approval → project_context → skills → context_mgmt → handoff），遵循"volatile content last"原则以最大化 KV prefix cache 命中率
- 用户级覆盖：`~/.paicli/prompts/base.md` 可整体替换内置 base.md；`~/.paicli/prompts/modes/agent.md` 可覆盖特定模式；项目级 `.paicli/prompts/...` 优先级更高
- 启动时校验：必含 `## Language` section（保证 reasoning_content 语言跟随）
- 兼容旧有 API：`Agent.java` / `PlanExecuteAgent.java` / `SubAgent.java` / `Planner.java` 不再手写运行模式 prompt，改为调 `PromptAssembler.assemble(mode, context)`
- 自带 prompt 质量审计模板（参考 DeepSeek TUI `PROMPT_ANALYSIS.md`）：每次改 prompt 都应该写 Gap 分析

**设计参考**：DeepSeek TUI `crates/tui/src/prompts.rs` + `crates/tui/src/prompts/*.md` 的分层架构，以及 `PROMPT_ANALYSIS.md` 的自我批判方法论。

**核心知识点**：
- Prompt Engineering 的工程化管理
- 编译时资源嵌入（Java `Class.getResourceAsStream` + 缓存）
- KV prefix cache 友好的 prompt 布局（稳定内容在前，volatile 在后）
- 用户可覆盖的配置层次（jar 内置 → 用户级 → 项目级）

**教程标题候选**：《System prompt 写得像意大利面？用分层架构，一个 Markdown 文件管一种职责》

---

## 第20期：异步后台任务 + Runtime API（异步 & 无头场景） ✅ MVP

**前置依赖**：第 13 期 Chrome DevTools MCP 已能产出截图等 image content；第 12 期长上下文工程已就绪。

**目标**：用户可以在 TUI 里提交后台任务（如"重构整个模块"），关掉终端走人，回来查看结果。同时暴露 HTTP/SSE Runtime API，让 PaiCLI 可以嵌入 CI/CD、IDE 插件、Web 面板。

**功能迭代**：

**后台任务（Task Manager）**：
- `DurableTaskManager`：SQLite 持久化的任务队列，复用已有的 `VectorStore` SQLite 基础设施
- 任务生命周期：`enqueued` → `running` → `completed` / `failed` / `canceled`
- Worker Pool：可配并发数（默认 2），每个 Worker 启动独立 Agent 线程执行任务
- `/task add <prompt>`：提交后台任务，返回 task_id
- `/task list`：列出所有任务（含状态、耗时、进度）
- `/task cancel <id>`：取消运行中任务
- `/task log <id>`：查看任务执行摘要
- 持久化恢复：进程重启后未完成的任务自动重入队

**Runtime API**：
- `RuntimeApiServer`：嵌入式 HTTP/SSE 服务端（`paicli serve --http --port 8080`），基于已有的 OkHttp / Javalin 或 Spring Boot 内嵌
- 兼容 OpenAI Assistants API 的端点：
  - `POST /v1/threads`：创建对话线程
  - `POST /v1/threads/{id}/turns`：发起一轮 Agent 交互
  - `GET /v1/threads/{id}/events`：SSE 流式事件（MessageDelta / ToolCall / TurnComplete）
- `RuntimeThreadStore`：thread/turn 的持久化记录 + 可重放事件时间线
- 安全：仅监听 localhost，API key header 校验

**设计参考**：DeepSeek TUI `crates/tui/src/task_manager.rs`（SQLite 任务队列）+ `crates/tui/src/runtime_api.rs`（HTTP/SSE）+ `crates/tui/src/runtime_threads.rs`（thread 持久化）。

**核心知识点**：
- 持久化任务队列（SQLite schema + 状态机）
- Worker Pool 并发模型
- HTTP/SSE 服务端嵌入（Javalin / Spring Boot 内嵌 + SSE emitter）
- OpenAI Assistants API 兼容层设计

**当前 MVP 已落地**：
- `DurableTaskManager`：SQLite 后台任务队列，默认 `~/.paicli/tasks/tasks.db`
- `/task`、`/task add`、`/task cancel`、`/task log` CLI 闭环
- 进程启动时将残留 `running` 任务恢复为 `enqueued`
- Worker Pool 默认 2，可用 `PAICLI_TASK_WORKERS` / `-Dpaicli.task.workers` 覆盖
- `RuntimeApiServer`：基于 JDK `HttpServer`，仅监听 `127.0.0.1`
- `RuntimeThreadStore`：SQLite 保存 thread 与 event 时间线
- Runtime API 强制 `PAICLI_RUNTIME_API_KEY` / `-Dpaicli.runtime.api.key`
- 详细实现文档：`docs/phase-20-runtime-api.md`

**教程标题候选**：《不想守在终端前？后台任务 + HTTP API，Agent 可以在后台跑》

---

## 第21期：图片复制粘贴输入 ✅ MVP

**前置依赖**：第 13 期 Chrome DevTools MCP 已能产出截图等 image content；第 12 期长上下文工程已就绪；第 17–20 期安全网与架构已就绪。

**目标**：让 PaiCLI 真正"看见"图片——浏览器截图、用户粘贴的图片、文档中的图表，都能直接喂给 LLM 让它理解，而不是 fallback 成"[此工具返回了 image]"占位。此期排在安全网（LSP + 快照）和架构重构（Prompt + Task）之后，确保模型生态成熟时再做。

**功能迭代**：

- `LlmClient.Message.content` 协议升级：从 `String` 扩展为 `List<ContentPart>`（含 `text` / `image_base64` / `image_url`）
- 各 `LlmClient` 实现适配图片输入请求体；公共接口不声明图片能力，含图片时统一上传，provider API 负责最终接收或返回错误
- 第 13 期的 `take_screenshot` image fallback 升级为图片附件回灌；输入层不按模型名拦截图片
- 用户输入层：终端粘贴 base64 图片或 `@image:file://path/to/img.png` 显式引用
- HITL 弹窗展示图片元数据（mimeType / size），不展示原图
- 按 token 成本审计：image input 单独计费维度（多数 图片输入 API 按 image tile 数计 token）

**当前 MVP 已落地**：
- `LlmClient.Message` 新增 `ContentPart`，旧字符串构造器保持兼容
- `AbstractOpenAiCompatibleClient` 在含图片时输出带图片块的 content array，纯文本仍输出 string content
- 公共 `LlmClient` 接口不做图片能力声明
- MCP image content 的 `data` / `mimeType` 被保留为 `ToolOutput.imageParts`
- ReAct / Plan task executor / SubAgent 在工具结果后追加图片 user message，不在 CLI 输入层按模型名拦截
- 用户输入支持 `@image:file:///abs/path.png`、`@image:/abs/path.png`、`@image:relative/path.png`
- 图片处理对齐 Claude Code：不 OCR 成文本；统一压缩 / 缩放后以图片块发送，并只补充来源、尺寸、坐标映射元信息
- 详细实现文档：`docs/phase-21-image-input.md`

**不做**：
- 视频 / 音频输入（再独立期）
- 图像生成（PaiCLI 是 Agent 不是绘图工具）
- TUI sixel 协议显示截图（依赖第 16 期 TUI 是否实现，留作扩展）

**核心知识点**：
- OpenAI 兼容协议的 图片输入 扩展（content array vs string）
- 各模型 图片输入定价模型差异
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
联网     MCP核心    MCP高级     长上下文    Chrome     CDP        Skill      TUI       LSP
能力     stdio+HTTP rsc/sample  200k-1M    DevTools   会话复用    系统       产品化     诊断注入

第18期 ──► 第19期 ──► 第20期 ──► 第21期
Git       Prompt    异步后台    图片
快照回滚   分层架构    Runtime API  图片输入
```

## 学习路径建议

**入门**：按顺序 1 → 2 → 3 → 6 → 16，掌握核心即可
**进阶**：1 → 2 → 3 → 4 → 7 → 8 → 9 → 10 → 12 → 13 → 15，深入技术细节
**全套**：全部 21 期
**安全优先**：6（HITL）→ 17（LSP）→ 18（Git快照）→ 其他按需
**架构优先**：19（Prompt重构）→ 20（Task Manager）→ 其他按需

## 参考项目

- **Claude Code**：人机协同、TUI界面
- **OpenClaw**：多Agent、MCP集成
- **PaiAgent**：工作流编排、可视化
- **LangGraph**：状态管理、循环控制
- **Spring AI**：多模型适配、工具回调

---

## Pro 升级版本（独立分支）

主线 21 期完成后，将开启独立分支做框架重构，作为「手写版 → 框架版」的对照实现。不并入主分支，主线手写版保持稳定基线。

**触发时机**：主线 1–21 期全部交付后启动

**候选实现**：

- **Spring AI 版本**：用 `ChatModel` / `StreamingChatModel` / `ToolCallback` / Spring Boot DI 重写主流程；`Agent` / `PlanExecuteAgent` / `AgentOrchestrator` / `ToolRegistry` / `MemoryManager` 全面 Bean 化；HITL 通过 AOP 拦截
- **LangGraph4J 版本**：用图状态机模型重构 Agent 流程，把 ReAct / Plan-and-Execute / Multi-Agent 三种模式统一到 graph 抽象下，节点 = 角色/工具调用，边 = 状态转移条件

**设计价值**：完整呈现「自己造轮子 → 用社区轮子」的取舍——什么场景手写更清晰、什么场景框架更省心，让用户既能看懂底层、又能切换主流框架。

---

*已完成第 16 期 TUI 产品化（含 16.1 形态修正：默认切换为 inline 流式 TUI，Lanterna 全屏 TUI 通过 `PAICLI_RENDERER=lanterna` 保留）、第 17 期 LSP 诊断注入 MVP、第 18 期 Git Side-History 快照与回滚 MVP、第 19 期 Prompt 分层架构 MVP、第 20 期后台任务 + Runtime API MVP、第 21 期图片复制粘贴输入 MVP。*
