# AGENTS.md

仓库给 Agent / 新线程使用的首读入口。详细行为描述见 `docs/agents-reference.md`。

## 信息优先级

1. 代码实际行为 > 2. `AGENTS.md` > 3. `README.md` > 4. `ROADMAP.md` > 5. `CLAUDE.md`

`ROADMAP.md` 代表演进方向，不代表已交付。

## 项目快照

- 项目名：`PaiCLI`
- 定位：面向商业使用的 Java Agent CLI 产品，对标 Claude Code
- 已交付 21 期（ReAct → Plan+DAG → Memory → RAG → Multi-Agent → HITL → 并行工具 → 多模型 → 联网 → MCP 核心 → MCP 高级 → 长上下文 → Chrome DevTools → CDP 会话复用 → Skill → TUI → LSP 诊断 → Side-Git 快照 → Prompt 分层 → Runtime API → 图片输入）
- 下一步：OAuth / sampling / recovery 作为后续 MCP 增强
- Banner 版本：`v16.1.0`，Maven 产物：`paicli-1.0-SNAPSHOT.jar`（两者不一致是正常状态）

## 运行前提

- Java 17+ / Maven
- 可选：`ripgrep`（`grep_code` 会优先使用；未安装时自动回退 Java 扫描）
- 至少一个 API Key：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY`

## 常用命令

```bash
cp .env.example .env
mvn clean package        # 默认跳过测试，优先产出可手工验收 jar
java -jar target/paicli-1.0-SNAPSHOT.jar
mvn test -Pquick          # 常规回归
mvn test -Pphase16-smoke  # TUI 相关
mvn test -Dtest=XxxTest -DskipTests=false   # 针对性
mvn test -DskipTests=false                  # 全量回归
```

## 架构概览

三条主执行路径，共享 ToolRegistry / MemoryManager / SnapshotService：

| 路径 | 入口 | 触发 |
|------|------|------|
| ReAct | `Agent.java` | 默认模式 |
| Plan-and-Execute | `PlanExecuteAgent.java` | `/plan` |
| Multi-Agent | `AgentOrchestrator.java` | `/team` |

核心内置工具 11 个：`read_file` / `write_file` / `list_dir` / `glob_files` / `grep_code` / `execute_command` / `create_project` / `search_code` / `web_search` / `web_fetch` / `revert_turn`

代码库理解默认走 Claude Code 式实时探索：`glob_files` 找候选文件、`grep_code` 精确定位符号或字符串、`read_file` 按需读取具体行段。`grep_code` 优先使用本机 `ripgrep`，不可用时回退到 Java 扫描；结果受 `max_results` / `head_limit` / `max_chars` 预算约束，返回 `partial: true` 或 `suggested_reads` 时应继续缩小搜索范围或按建议读取行段。`search_code` 是 RAG 语义辅助，适合模糊自然语言、关键词不明确、常规搜索无果、巨型/跨知识检索场景，不作为精确代码定位的首选。

MCP 动态工具：`mcp__{server}__{tool}`（+ resources 虚拟工具）

MCP 配置会合并用户级 `~/.paicli/mcp.json` 与项目级 `.paicli/mcp.json`；`${VAR}` 支持系统环境变量、系统属性、项目 `.env`、用户 `~/.env`。检测到 `STEP_API_KEY` 时会自动内置 `step_search` 远程 MCP（显式同名配置优先）。

DeepSeek V4 / Kimi thinking 模式下，assistant tool-call 消息的 `reasoning_content` 必须随下一轮请求历史带回；其他 provider 默认只把 reasoning 写日志 / 展示。

## 仓库结构

```
src/main/java/com/paicli/
├── agent/       Agent.java, PlanExecuteAgent.java, SubAgent.java, AgentOrchestrator.java
├── cli/         Main.java, CliCommandParser.java, PlanReviewInputParser.java
├── browser/     BrowserSession, BrowserGuard, SensitivePagePolicy
├── llm/         GLMClient, DeepSeekClient, StepClient, KimiClient, FreeLlmApiClient
├── context/     ContextProfile, ContextMode, TokenUsageFormatter
├── memory/      MemoryManager, ConversationHistoryCompactor, LongTermMemory
├── plan/        Planner, ExecutionPlan, Task
├── rag/         CodeIndex, CodeRetriever, VectorStore, CodeChunker
├── lsp/         LspManager, LspDiagnosticFormatter
├── prompt/      PromptAssembler, PromptContext, PromptRepository
├── image/       ImageReferenceParser
├── runtime/     api/ (RuntimeApiServer) + task/ (DurableTaskManager)
├── snapshot/    SideGitManager, SnapshotService
├── tool/        ToolRegistry
├── mcp/         McpClient, McpServerManager, transport/, resources/, mention/
├── hitl/        HitlToolRegistry, ApprovalPolicy, TerminalHitlHandler
├── web/         SearchProvider, WebFetcher, HtmlExtractor, NetworkPolicy
├── policy/      PathGuard, CommandGuard, AuditLog
├── skill/       SkillRegistry, SkillContextBuffer, SkillIndexFormatter
└── render/      Renderer, InlineRenderer, PlainRenderer, RendererFactory
```

启动与 inline 渲染当前约定：

- 开屏 Banner 使用无右边框的简洁布局，避免 CJK/ANSI 字宽导致右侧竖线错位；Phase 22 后默认是 π 主题彩色 logo + Qoder 风格首屏，只展示模型、MCP、Skill、ReAct 状态和三条 getting-started tips，不再把 MCP server 明细刷成启动日志。
- inline 模式使用 JLine 4 的 LineReader 编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`。
- 默认 CLI 启动路径应先 `Renderer.start()` 并初始化底部 dock；inline 首屏不要在 `readLine` 前裸写 stdout，而是通过 `InlineRenderer.installStartupScreen(...)` 挂到 `LineReader.CALLBACK_INIT`，首次进入输入时用 `printAbove` 一次性显示完整 Banner + tips，避免 logo 被 LineReader 首次重绘滚出可视区域。
- `BottomStatusBar` 现在是 JLine `Status` 托管的底部 dock：由 JLine 维护滚动区域和状态行位置，不再手写 `\n` / `moveUp` / `CLEAR_TO_EOS` 清屏。输入期会把 LineReader 光标定位到 dock 上方一行，让 `*` 输入行和 Status 同处底部区域；dock 保留两类信息：上层模式 + MCP/Skill 摘要，下层 Auto Model / model / phase / ctx 百分比与 token / cost / elapsed / cwd。`ctx` 表示当前仍会带入下一轮请求的上下文估算；`in/out/cache` 表示最近任务的 LLM 调用统计，二者不要混用。
- 普通任务和斜杠命令提交后，`Main` 会把本轮原始输入以暗色整行块写回 transcript：输入态左提示仍是 `* `，提交回显左提示改为 `>`；单行输入只占一行，不额外追加空白行。普通任务随后再展开 MCP resource / 本地 `@path` 并进入 Agent；不要只依赖 JLine 提交行残留，否则 activity 重绘或 dock 刷新可能让用户输入从可见历史里消失。`/clear` 清空 conversationHistory、shortTermMemory、待注入 Skill buffer，并重建不含上一轮检索记忆的 system prompt；长期记忆保留。
- ReAct LLM 调用期间，inline renderer 使用固定高度 live thinking 区动态显示 `Thinking...` 和灰色竖线 reasoning 预览；该区域只能清理自己刚打印的几行，不能用独立 JLine `Display.update()` / `CLEAR_TO_EOS` 向上覆盖 transcript。content 或 tool call 开始前先清掉 live 区，再把完整 reasoning 引用块落到正文区，正文回答用低调标记起始，不再刷强标题。
- 交互期输出应优先走 `Renderer.stream()`；`Main`、`PlanExecuteAgent`、`Planner`、`AgentOrchestrator` 都支持把输出流接到 inline renderer，避免直接争抢 stdout。`CodeIndex` 的索引进度通过 `ProgressListener` 注入，`/index` 应绑定到当前 renderer 输出流。
- Phase 22 开始，`InlineRenderer` 可绑定当前 `LineReader`；当 `LineReader.isReading()` 为 true 时，`Renderer.stream()` 的完整行输出优先通过 `LineReader#printAbove` 显示在输入行上方，未绑定 / 非读取态 / 测试路径回退到原 `PrintStream`。
- Markdown 表格渲染要按当前终端列宽分配列宽；长内容在单元格内部换行，不能依赖终端自动折行把整行表格打散。
- ReAct 正常结束后不再把 `📊 Token: ...` 打进正文区；token/cost/elapsed 会保留在底部强状态行，phase 回到 `idle`。
- 默认 CLI 启动路径应尽早建立 `Terminal -> LineReader -> Renderer`，启动 Banner、模型加载、MCP 启动、Skill summary、ReAct 提示和退出提示都应走 `Renderer.stream()`；除 fatal bootstrap / runtime API / legacy TUI 降级外，不要在交互主路径新增裸 `System.out.println`。
- 启动期 MCP 不得阻塞首屏：CLI 默认最多等待 8 秒（`PAICLI_MCP_STARTUP_WAIT_SECONDS` / `-Dpaicli.mcp.startup.wait.seconds` 可调），超时后保留未完成 server 为 `STARTING` 并后台继续初始化；`/mcp` 查看最新状态。
- `LineReader` 使用 `PaiCliHighlighter` 做输入实时高亮：slash 命令、`@` 引用、`@image:`、`@clipboard`、敏感词和明显危险 shell 片段会在编辑阶段被标记；不要把这类视觉提示混入最终提交文本。
- `LineReader` 使用 `PaiCliCompleter` 做上下文补全：`/model` provider、`/mcp` 子命令与 server、`/skill` 子命令与 skill name、`/task` / `/browser` / `/snapshot` 子命令、`@image:` 本地路径、本地 `@path` 和 MCP resource `@server:uri` 引用都应从同一个 completer 出口维护。
- 普通用户输入进入 Agent 前会先展开 MCP resource mention，再由 `LocalPathMentionExpander` 展开本地 `@path`：文件会内联为 `<file>` 块，目录会内联为 `<directory>` 列表；绝对路径或符号链接逃逸项目根时保持原文不展开。
- `LineReader` 使用 `PaiCliHistory` 持久化输入历史到 `~/.paicli/history/input.history`；如果 `paicli.history.file` / `PAICLI_HISTORY_FILE` 指向目录，也会自动使用该目录下的 `input.history`，避免把目录当文件读；默认忽略空白、重复、明显密钥/Bearer、base64 图片和超长输入，用户可用 `/history clear` 清空本机输入历史。
- JLine 交互升级计划记录在 `docs/phase-22-jline-interaction-upgrade.md`。

## 关键行为约束（Agent 必读）

### Memory

- 长期记忆只通过 `/save` 或用户明确要求保存；不要自动提取事实
- 长期记忆只保存跨会话稳定事实，不保存临时指令；默认项目级作用域，跨项目通用偏好才用 global
- 长期记忆必须可审计和可删除：`/memory list` / `/memory search <关键词>` / `/memory delete <id>` / `/memory clear`
- 两道压缩不要混淆：shortTermMemory 压缩 vs conversationHistory 压缩（后者是防 window 超限的关键）

### HITL + 策略层

- 拦截顺序：HitlToolRegistry → ToolRegistry → PathGuard/CommandGuard
- 用户无法批准策略拒绝的请求
- PathGuard 强制路径限定在项目根内
- CommandGuard 是辅助黑名单，不是主防线

### Plan 审阅交互

- `Enter` 执行 / `Ctrl+O` 展开 / `ESC` 取消 / `I` 补充重规划
- 方向键不应被误判为 ESC
- 涉及改动要连 raw mode 和回退路径一起看

### 并行工具

- 三条路径都走 `executeTools()`，不手写 for-loop
- 默认最多 4 个并发，结果保持原始顺序

### Web + Browser

- 每轮 system prompt 会注入当前日期/时区；包含“最新/当前/今天/今年/2026/趋势/新闻/版本”等时效性问题时，模型应先调用 `web_search`，不得在 `web_search` 可用时声称无法实时搜索。
- ReAct 会对上述时效性问题做确定性联网预检：进入第一轮 LLM 前先执行一次内置 `web_search` 并把结果注入本轮上下文；用户明确“不要联网/不用搜索”时跳过。
- 当前模型为 `step-3.7-flash*` 且自动/显式 `step_search` MCP 的 `web_search` / `web_fetch` 已就绪时，内置 `web_search` / `web_fetch` 会优先转调 StepSearch MCP；未就绪或调用失败时回退到原 SearchProvider / WebFetcher。
- 已知 URL 先 `web_fetch`，SPA/防爬墙 fallback 到 Chrome DevTools MCP
- 浏览器读取优先 `take_snapshot`，不默认 `take_screenshot`
- 公开页面不要提前切 shared 模式

### Skill

- system prompt 索引段注入三处提示词，上限 20 个 / 4KB
- `load_skill` → SkillContextBuffer → 下一轮 user message 前置注入

## 修改时的硬规则

### 1. 改行为 → 同步文档

`AGENTS.md` / `README.md` / `ROADMAP.md`（仅状态变化时）

### 2. 改命令入口 → 联动

`Main.java` + `CliCommandParser.java` + 测试 + `README.md` + `AGENTS.md`

未识别的 `/xxx` 在 CLI 层直接报"未知命令"，不回退给 Agent。

### 3. 改 Plan 审阅交互 → 联动

`Main.java` + `PlanReviewInputParser.java` + 测试 + 手工验证

### 4. 改工具集 → 联动

`ToolRegistry.java` + Agent/PlanExecuteAgent/SubAgent 提示词 + 可能 Planner 提示词 + 文档

### 5. 改模型/接口 → 联动

对应 Client + `LlmClientFactory.java` + `.env.example` + 文档

### 5.1 改 Embedding → `EmbeddingClient` + `VectorStore` + `.env.example` + 文档

### 5.2 改 Web/搜索 → `web/` 相关 + ToolRegistry + `.env.example` + 文档 + 测试

### 5.3 改 Memory → `MemoryManager` + `LongTermMemory` + `TokenBudget` + 测试 + 文档

### 5.4 改 HITL/策略 → `policy/` + ToolRegistry + HitlToolRegistry + 提示词 + `.env.example` + 文档 + 测试

### 5.5 改 MCP → `mcp/` + ToolRegistry + HITL + AuditLog + 提示词 + 文档 + 测试

### 6. 不提交 `.env` / 真实 API Key / `target/` 产物

### 7. 保持代码可读性，不过度抽象

## 验证路径

| 场景 | 命令 |
|------|------|
| 代码搜索工具 | `mvn test -Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest` |
| 命令解析 | `mvn test -Dtest=CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest` |
| DAG/Plan | `mvn test -Dtest=ExecutionPlanTest` |
| Multi-Agent | `mvn test -Dtest=AgentRoleTest,AgentMessageTest,AgentOrchestratorTest` |
| TUI/终端 | `mvn test -Pphase16-smoke` |
| RAG | `mvn test -Dtest=CodeChunkerTest,CodeAnalyzerTest,VectorStoreTest,CodeIndexTest` |
| 常规回归 | `mvn test -Pquick` |

## 给新线程的导航

1. 先看本文件 → 2. `README.md` → 3. `Main.java` → 4. 按任务进入对应模块

| 任务类型 | 先看 |
|----------|------|
| CLI 命令 | Main.java + CliCommandParser.java |
| 规划/DAG | PlanExecuteAgent.java + Planner.java + ExecutionPlan.java |
| 工具调用 | ToolRegistry.java + Agent.java |
| 代码搜索 | ToolRegistry.java (`glob_files` / `grep_code` / `read_file`) |
| 模型/API | llm/*Client.java + LlmClientFactory.java |
| RAG 语义辅助 | CodeRetriever.java + CodeIndex.java + VectorStore.java |
| Multi-Agent | AgentOrchestrator.java + SubAgent.java |
| MCP | McpServerManager.java + McpClient.java |
| TUI/渲染 | render/Renderer.java + RendererFactory.java |

## 当前已知边界

以下在路线图但未交付：容器/VM 沙箱 / MCP OAuth + sampling + server 自动重启

不要把 `ROADMAP.md` 中"将来要做"误读成"现在已有"。

## 持续维护约定

形成稳定协作规则时直接补进本文件，不要只留在聊天记录里。详细实现细节补到 `docs/agents-reference.md`。
