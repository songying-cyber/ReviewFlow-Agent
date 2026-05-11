# AGENTS.md

这份文档是 `paicli` 仓库给各类 Agent / 新线程使用的首读入口。

目标只有两个：

1. 让首次进入仓库的线程，能在几分钟内建立对项目的正确认识。
2. 把后续协作规则沉淀到一个稳定入口，避免规则只存在于历史对话里。

如果本仓库的行为、目录结构、约定或协作方式发生了稳定变化，请在同一次改动里同步更新本文件。

## 信息优先级

当不同文档描述不一致时，按下面的优先级理解：

1. 代码实际行为
2. `AGENTS.md`
3. `README.md`
4. `ROADMAP.md`
5. `CLAUDE.md`

`ROADMAP.md` 代表演进方向，不代表已经交付。

## 项目快照

- 项目名：`PaiCLI`
- 定位：一个面向商业使用的 Java Agent CLI 产品，对标 Claude Code，从最初的 ReAct 循环持续演进到完整 Agent 产品形态
- 当前主线：主线规划 17 期。已完成第 1 期 `ReAct`、第 2 期 `Plan-and-Execute + DAG`、第 3 期 `Memory + 上下文工程`、第 4 期 `RAG 检索 + 代码库理解`、第 5 期 `Multi-Agent 协作 + 角色分工`、第 6 期 `HITL 人工审批 + 危险操作拦截`（含 HITL 增强：路径围栏 / 命令快速拒绝 / 操作审计）、第 7 期 `异步执行 + 并行工具调用`、第 8 期 `多模型适配 + 运行时切换`、第 9 期 `联网能力 + Web 工具`、第 10 期 `MCP 协议核心（stdio + Streamable HTTP）`、第 11 期 `MCP 高级能力首批（resources 双轨 + prompts 查看 + 被动通知）`、第 12 期 `长上下文工程`、第 13 期 `Chrome DevTools MCP`、第 14 期 `CDP 会话复用 + 登录态访问`
- 下一步：第 15 期 `Skill 系统`、第 16 期 `TUI 产品化`、第 17 期 `多模态 LLM 输入`；OAuth / sampling / recovery 作为后续 MCP 增强，不属于当前第 14 期已交付范围
- 当前用户可感知版本：CLI Banner 显示 `v14.0.0`
- 当前 Maven 产物版本：`pom.xml` 仍是 `1.0-SNAPSHOT`
- 结论：如果你看到运行界面是 `v14.0.0`，但 Jar 名仍是 `paicli-1.0-SNAPSHOT.jar`，这是当前仓库的真实状态，不是你看错

## 运行前提

- Java 17+
- Maven
- 可用的 `GLM_API_KEY`

API Key 当前读取顺序以代码为准：

1. 仓库当前目录下的 `.env`
2. 用户主目录下的 `.env`
3. 环境变量 `GLM_API_KEY`

`.env.example` 当前包含：

```bash
GLM_API_KEY=your_api_key_here
EMBEDDING_PROVIDER=ollama
EMBEDDING_MODEL=nomic-embed-text:latest
EMBEDDING_BASE_URL=http://localhost:11434
# EMBEDDING_API_KEY=your_api_key_here
# PAICLI_LOG_LEVEL=INFO
# PAICLI_LOG_DIR=/Users/yourname/.paicli/logs
# PAICLI_LOG_MAX_HISTORY=7
# PAICLI_LOG_MAX_FILE_SIZE=10MB
# PAICLI_LOG_TOTAL_SIZE_CAP=100MB
```

长期记忆默认持久化位置：

1. `~/.paicli/memory/long_term_memory.json`
2. 如果传入 `-Dpaicli.memory.dir=/path/to/dir`，则优先使用该目录

代码索引（RAG）默认持久化位置：

1. `~/.paicli/rag/codebase.db`
2. 如果传入 `-Dpaicli.rag.dir=/path/to/dir`，则优先使用该目录

操作审计日志默认持久化位置（以代码实际行为为准）：

1. 系统属性：`-Dpaicli.audit.dir=/path/to/dir`
2. 环境变量：`PAICLI_AUDIT_DIR`
3. 默认值：`~/.paicli/audit/audit-YYYY-MM-DD.jsonl`（按天分文件，JSONL 格式）

Embedding 配置读取顺序（以代码实际行为为准）：

1. 环境变量：`EMBEDDING_PROVIDER`、`EMBEDDING_MODEL`、`EMBEDDING_BASE_URL`、`EMBEDDING_API_KEY`
2. 系统属性（同上）
3. 默认值：`ollama` / `nomic-embed-text:latest` / `http://localhost:11434`

日志配置读取顺序（以代码实际行为为准）：

1. 系统属性：`paicli.log.dir`、`paicli.log.level`、`paicli.log.maxHistory`、`paicli.log.maxFileSize`、`paicli.log.totalSizeCap`
2. 环境变量或 `.env`：`PAICLI_LOG_DIR`、`PAICLI_LOG_LEVEL`、`PAICLI_LOG_MAX_HISTORY`、`PAICLI_LOG_MAX_FILE_SIZE`、`PAICLI_LOG_TOTAL_SIZE_CAP`
3. 默认值：`~/.paicli/logs` / `INFO` / `7` / `10MB` / `100MB`

ReAct / SubAgent 预算配置读取顺序（以代码实际行为为准）：

1. 系统属性：`paicli.react.token.budget`、`paicli.react.stagnation.window`、`paicli.react.hard.max.iterations`
2. 默认值：`Integer.MAX_VALUE`（实质不限，仅显式配置时启用 token 硬预算）/ `3` / `50`

设计取舍：长上下文模型（GLM-5.1 200k / DeepSeek V4 1M）配合套餐用户的"无限 token"诉求，默认不再以 80% × window 为硬限——让 LLM 自然停在 content 不再调用工具的位置。死循环防护由 stagnation 检测（连续 3 轮相同工具调用）和 hardMaxIterations（50 轮）两道兜底承担。需要严格成本控制的 CI / 自动化批跑场景通过系统属性显式开启硬预算。

注意：Token 显示行 `📊 Token: 已用 X / Y` 中的 Y 仍是 `ContextProfile.agentTokenBudget()`（80% × window），作为软提示显示占用比，**不再代表强制限制**。

LLM HTTP 超时配置读取顺序（以代码实际行为为准）：

1. 系统属性：`paicli.llm.connect.timeout.seconds`、`paicli.llm.read.timeout.seconds`、`paicli.llm.write.timeout.seconds`、`paicli.llm.call.timeout.seconds`
2. 默认值：`60` / `300` / `60` / `600`（单位：秒）

注意：SSE 流式接口下，OkHttp 的 `readTimeout` 是"两次 read 之间最大间隔"而非请求总时长；GLM-5.1 在生成大段 reasoning_content 时服务端可能长时间静默，所以默认值放宽到 300 秒，再用 `callTimeout` 兜底整个请求。

Web 搜索 provider 配置读取顺序（以代码实际行为为准）：

1. 环境变量 / 系统属性 / `.env` 中的 `SEARCH_PROVIDER`：显式指定 `zhipu` / `serpapi` / `searxng`
2. 未指定时按 Key/URL 自动判断（优先级从高到低）：
   - `GLM_API_KEY` 存在 → `zhipu`（智谱 Web Search，与 GLM 推理共用 Key，国内首选）
   - `SERPAPI_KEY` 存在 → `serpapi`
   - `SEARXNG_URL` 存在 → `searxng`
3. 都没有时返回 `zhipu` 占位 provider，`web_search` 工具会提示用户配置

各 provider 配置读取顺序（环境变量 / 系统属性 / `.env`）：
- `zhipu`：`GLM_API_KEY`（必填，与 LLM 推理共用）+ `ZHIPU_SEARCH_ENGINE`（可选，默认 `search_std`，可选 `search_pro` / `search_pro_sogou` / `search_pro_quark`）
- `serpapi`：`SERPAPI_KEY`
- `searxng`：`SEARXNG_URL`（推荐本地 `docker run --rm -p 8888:8888 searxng/searxng`）

Web 抓取（`web_fetch`）安全策略（实现位于 `src/main/java/com/paicli/web/NetworkPolicy.java`）：

- scheme 白名单：仅允许 `http` / `https`
- 主机黑名单：屏蔽 `localhost`、`0.0.0.0`、loopback / link-local / site-local 地址（基础 SSRF 围栏，不防 DNS rebinding）
- 响应体上限：5MB（流式截断，避免 OOM）
- 整体超时：30 秒（OkHttp `callTimeout`）
- 限流：默认每 60 秒最多 30 次请求

MCP 配置读取顺序（以代码实际行为为准）：

1. 用户级：`~/.paicli/mcp.json`
2. 项目级：`.paicli/mcp.json`
3. 按 server 名 merge，项目级覆盖用户级

配置格式兼容 Claude Code 的 `claude_desktop_config.json`：`command` + `args` 表示 stdio server，`url` + `headers` 表示 Streamable HTTP server。`${PROJECT_DIR}` 和 `${HOME}` 是内置变量；其他 `${VAR}` 从环境变量读取，缺失会直接报错。

第 13 期后，`~/.paicli/mcp.json` 不存在时启动会自动创建默认 chrome-devtools 配置：

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

如果已有用户级配置但缺少 `chrome-devtools`，启动只打印提示，不自动修改用户文件。默认 isolated 模式不复用登录态；需要登录态时默认由 Agent 调用 `browser_connect` 或用户手动执行 `/browser connect`，在当前进程内切到 `--autoConnect`，复用已在 `chrome://inspect/#remote-debugging` 允许远程调试的登录态 Chrome。`/browser connect <port>` 保留旧式 `--browser-url=http://127.0.0.1:<port>` 兼容路径。

## 常用命令

```bash
cp .env.example .env
mvn clean package
java -jar target/paicli-1.0-SNAPSHOT.jar
mvn clean compile exec:java -Dexec.mainClass="com.paicli.cli.Main"
mvn test
```

验证 RAG 相关测试：

```bash
mvn test -Dtest=CodeChunkerTest,CodeAnalyzerTest,VectorStoreTest,CodeIndexTest
```

如果只是验证一个测试类：

```bash
mvn test -Dtest=ExecutionPlanTest
```

## 当前产品行为

### 1. ReAct 模式

- 默认模式
- 主入口在 `src/main/java/com/paicli/agent/Agent.java`
- 维护对话历史
- 退出条件由 LLM 自决：只要它不再返回 `tool_calls`、直接给出 `content`，循环就结束
- `AgentBudget`（`src/main/java/com/paicli/agent/AgentBudget.java`）只承担保险阀职责，三种兜底任一命中即收尾：
  - 累计 `inputTokens + outputTokens` 超过 token 预算（默认 `80% * 当前模型 maxContextWindow`）
  - 连续 N 轮（默认 3）出现完全相同的工具名 + 参数，判定为死循环
  - 累计轮数超过硬上限（默认 50），最终防御
- 不再使用"固定最多 10 轮"的策略；新代码改动前阅读 `AgentBudget` 的注释比读老 README 更可靠
- 支持工具调用后继续思考
- 用户默认看到的是流式输出的模型 `reasoning_content`（如果接口返回）和回复内容；ReAct 同一次用户输入只打印一次 `🧠 思考过程` 标题，工具调用前后的后续推理继续归在同一块下；ReAct 流式头标签使用 `🤖 回复`（而非 `最终结果`，避免在模型调用工具前先 narrate 时误导用户）；Plan 阶段同样走流式展示；终端会先渲染常见 Markdown 再输出；工具参数、工具返回片段、Token 使用量不再作为默认用户输出
- 会写入短期记忆

### 1.1 长上下文工程

主模块在 `src/main/java/com/paicli/context/`：

- `ContextProfile`：根据 `LlmClient.maxContextWindow()` 计算 short / balanced / long 模式、Agent token 预算、RAG 默认 topK、Memory 压缩开关、MCP resource 索引开关
- `ContextMode`：`short` / `balanced` / `long`
- `TokenUsageFormatter`：统一输出已用 token、动态预算、window、cached input tokens 与估算成本
- `LlmClient` 新增能力声明：`maxContextWindow()`、`supportsPromptCaching()`、`promptCacheMode()`
- GLM-5.1 当前声明 `200000` window；DeepSeek V4 当前声明 `1000000` window
- `AgentBudget.fromLlmClient()` 默认使用 `80% * maxContextWindow`，但 `paicli.react.token.budget` 仍可覆盖
- long 模式（`>= 100000` window）默认跳过 Memory 自动摘要压缩，`search_code` 默认 topK 提升到 20
- MCP resources 自动索引只在 long 模式启用，只注入 URI / 名称 / 描述 / mimeType，不注入正文；ReAct / Plan / Team 都接入同一索引供应器
- prompt caching 当前交付能力声明、cached usage 解析与可见化；不新增 Anthropic provider，也不向 GLM / DeepSeek 请求体注入未确认兼容的私有 cache 字段
- `/context` 会展示上下文策略、window、动态预算、RAG topK、prompt cache 模式、MCP resource 自动索引状态

### 2. Plan-and-Execute 模式

- 通过 `/plan` 或 `/plan <任务>` 进入
- 主入口在 `src/main/java/com/paicli/agent/PlanExecuteAgent.java`
- 流程是：规划 -> 用户审阅 -> 执行 DAG -> 汇总结果
- 计划执行完后会回到默认 `ReAct`
- 简单任务应优先生成最小计划；不要为了凑步数引入无关读写文件或中间落盘步骤

### 3. Plan 审阅交互

以 `Main.java` 当前实现为准：

- `Enter`：执行当前计划
- `Ctrl+O`：展开完整计划
- `ESC`：如果当前在展开视图则先折叠，否则取消本次计划
- `I`：输入补充要求并重新规划

注意：

- 这里不是 README 里旧描述的“只有 Enter / ESC / I”
- 原始按键处理依赖 JLine raw mode
- 方向键属于终端控制序列，不应被误判成 `ESC` 取消
- 涉及这块的改动，不能只看字符串，要连输入模式和回退路径一起看

### 4. Memory 系统

- 主模块在 `src/main/java/com/paicli/memory/`
- 默认包含：短期记忆、长期记忆、摘要压缩、事实提取、Token 预算、相关记忆检索
- 注入到 system prompt 的”相关记忆”应只来自长期记忆；当前轮用户输入和短期对话已经在消息历史里，不应再被当成”历史记忆”重复注入
- 上下文有**两道压缩**，不要混淆：
  1. `ContextCompressor` 压缩 `MemoryManager.shortTermMemory`（PaiCLI 自己的短期记忆条目），由 `addUserMessage` / `addAssistantMessage` / `addToolResult` 内嵌触发，阈值看 `ConversationMemory.tokenCount` vs `ContextProfile.compressionTriggerRatio`
  2. `ConversationHistoryCompactor` 压缩 `Agent.conversationHistory`（真正发给 LLM 的消息列表），在 `Agent.run` 主循环每轮 LLM 调用前评估，阈值看 `TokenBudget.estimateMessagesTokens(conversationHistory)` vs `ContextProfile.compressionTriggerTokens()`
- 第二道压缩是 ReAct 主循环防上下文超 window 的关键。未做之前，shortTermMemory 压缩看似触发了但 conversationHistory 仍在膨胀，下一轮 input token 不会变小
- 第二道压缩切割点必然落在 user message 边界，避免破坏 tool_call / tool_result 配对协议；保留最近 3 个 user 起算的尾部，前面全部摘要为单条 user(`[已压缩的历史对话摘要]\n...`) + assistant 确认
- 三条主路径都接入了第二道压缩：`Agent.run`（ReAct）、`PlanExecuteAgent.executeTask`（每个 task 内多轮工具调用）、`SubAgent.execute`（Multi-Agent 三角色）。每轮调 LLM 之前评估当前 messages 列表的 token，超阈值即压
- Token 显示行 `📊 Token: 已用 X / Y` 的 Y 显示**模型窗口大小**（`maxContextWindow()`），不再是 80% × window 软预算。这条信息只用于让用户感知占用比；硬限制由 `ConversationHistoryCompactor` 自动压缩兜底，不再有"撞墙强制收尾"
- 长期记忆只通过显式保存意图写入：`/save <事实>`，或用户在自然语言里明确说“记一下 / 记住 / 以后记得”时由 Agent 调用 `save_memory`；不要在每轮对话结束或 `/clear` 时自动提取事实
- 长期记忆只应保存跨会话仍成立的稳定事实；一次性任务请求、临时文件名/目录名、模型猜测或“用户想要你做什么”这类指令，不应落入长期记忆
- CLI 命令：
  - `/memory` 或 `/mem`：查看当前记忆状态
  - `/memory clear`：清空长期记忆
  - `/save <事实>`：手动保存关键事实
- 内置工具 `save_memory`：只在用户明确要求保存长期偏好 / 稳定事实时调用；第 14 期还对“复用已登录 Chrome，记一下”这类浏览器登录态偏好做确定性兜底，写入长期记忆供新 Agent 检索
- `ReAct` 和 `Plan-and-Execute` 两条主路径都应写回记忆；改动其中一条时，另一条也要检查

### 5. RAG 系统

- 主模块在 `src/main/java/com/paicli/rag/`
- 默认包含：EmbeddingClient、VectorStore（SQLite）、CodeChunker、CodeAnalyzer、CodeIndex、CodeRetriever
- CLI 命令：
  - `/index [路径]`：索引代码库
  - `/search <查询>`：语义检索代码
  - `/graph <类名>`：查看代码关系图谱
- Agent 工具：`search_code`（语义检索代码库）、`web_search`（联网搜索）、`web_fetch`（抓取已知 URL）
- 在 ReAct 和 Plan 模式下，Agent 会自动检索代码上下文辅助回答
- `web_search` 通过 `SearchProvider` 抽象接入，当前内置三个实现：
  - `zhipu`（默认，与 GLM 推理共用 `GLM_API_KEY`，0.01–0.05 元/次，中文搜索质量高，国内首选）
  - `serpapi`（国际通用，需 `SERPAPI_KEY`，付费即开即用）
  - `searxng`（开源自托管，需 `SEARXNG_URL`，免费但需本地 docker 实例）
  - Provider 不可用时工具返回引导提示，不会让整轮 Agent 失败
- `web_fetch` 走「OkHttp + Jsoup + 简易 readability」本地链路，对静态/SSR 页面有效；遇到 SPA / 防爬墙会返回空正文 + `已知边界` 提示，不会反复重试。JS 渲染可 fallback 到 Chrome DevTools MCP；登录态访问通过第 14 期 `/browser connect` 复用带登录态的调试 Chrome

### 6. Multi-Agent 协作模式

- 通过 `/team` 或 `/team <任务>` 进入
- 主入口在 `src/main/java/com/paicli/agent/AgentOrchestrator.java`
- 采用主从架构：编排器（Orchestrator）为"主"，子代理（SubAgent）为"从"
- 三个角色：
  - 规划者（Planner）：拆解任务为执行步骤
  - 执行者（Worker）：调用工具执行具体操作（默认 2 个 Worker 轮询分配）
  - 检查者（Reviewer）：审查执行结果质量
- 协作流程：规划 -> 按依赖顺序分配给 Worker -> Reviewer 审查 -> 通过则完成，未通过则带反馈重试
- 同一个依赖批次内部当前仍按步骤串行执行（Worker 仅做轮询分担对话历史，没有真正并发），以保证流式输出不交错；Worker 真正并发执行留作后续优化
- 冲突解决：每步最多重试 2 次，超过次数保留当前结果
- Reviewer 审查结果解析不出来（空内容、缺 approved 字段、既无肯定也无否定关键词）时，采取保守策略判为未通过
- 如果某步失败导致其依赖步骤无法执行，Orchestrator 会显式提示 `⏭️ 步骤 [step_x] 因前置步骤失败被跳过`
- SubAgent 在执行阶段遭遇 `IOException`（LLM 调用失败、超时等）时返回 `AgentMessage.Type.ERROR`，调用方需要独立于 RESULT 处理
- 所有子代理共享同一个 ToolRegistry 与 MemoryManager（与 ReAct 模式共享项目路径与记忆上下文，避免重复加载长期记忆）
- 任务执行完后回到默认 `ReAct`
- `ReAct`、`Plan-and-Execute` 和 `Multi-Agent` 三条路径都应写回记忆

### 7. HITL 审批系统

- 主模块在 `src/main/java/com/paicli/hitl/`
- 通过 `/hitl on` 启用、`/hitl off` 关闭，默认关闭
- 通过 `/hitl` 查看当前状态
- 危险工具：`write_file`（中危）、`execute_command`（高危）、`create_project`（中危）
- 非危险工具（`read_file`、`list_dir`、`search_code`）不受影响，直接执行
- 审批决策选项：
  - `y` / Enter：批准本次操作
  - `a`：本次会话全部放行同类操作（`APPROVED_ALL`，省去重复确认）
  - `n`：拒绝，可附拒绝原因
  - `s`：跳过本步骤
  - `m`：修改参数后执行
- 关键设计：`HitlToolRegistry` 继承 `ToolRegistry`，通过覆写 `executeTool()` 实现透明拦截；HITL 关闭时与普通 `ToolRegistry` 行为完全一致
- `/clear` 命令同时清除本次会话中积累的"全部放行"记录
- fail-safe：无法识别的输入会重新提示，**不会**默认批准；连续 5 次无效输入则保守判为 REJECTED
- 修改参数（`m`）输入的 JSON 会先用 Jackson 校验语法，非法则提示并回到主菜单重选
- 并发安全：`TerminalHitlHandler.requestApproval` 整体 `synchronized`，多 Agent 并行场景下审批提示会串行展示、避免 stdout / stdin 互相打架；`approvedAllTools` 使用 `ConcurrentHashMap.newKeySet()`
- 审批框展示采用"显示列宽"算法（CJK / 全角 / emoji 按 2 列计算），保证中文和表情符号下边框仍然对齐
- 参数展示按 JSON 结构解析逐字段展示；长字符串（> 120 字符）显示前 120 字符预览 + 总长度，换行替换为 `⏎` 以便肉眼可读
- 审批框上方会打印 `────────── ⚠️ HITL 审批请求 ──────────` 作为视觉分隔符，与上游 `🤖 回复` / `执行输出` 区视觉分离
- 流式渲染器在进入 tool-call 迭代前会调用 `resetBetweenIterations()`：`TerminalMarkdownRenderer` 按换行才 flush，没做这一步 HITL 提示会"跨过"还在 pending 缓冲区里的 reasoning/content 文本，造成标题与内容错位。Agent / SubAgent / PlanExecuteAgent 三条路径都做了相同处理；其中 ReAct 会重建渲染器但不会重复打印同一次用户输入的 `🧠 思考过程` 标题

#### 7.1 HITL 增强：路径围栏 / 命令快速拒绝 / 操作审计

HITL 是"用户在场时确认"，本子段是 HITL 之外的辅助层，不是沙箱、不提供进程隔离。主模块在 `src/main/java/com/paicli/policy/`：

- `PathGuard`：`read_file` / `write_file` / `list_dir` / `create_project` 在执行前必须经过它，强制把路径限定在项目根之内。处理三类越界——绝对路径外逃、`..` 穿越、符号链接逃逸（向上找最近存在祖先做 `Files.toRealPath`，再把剩余段接回）
- `CommandGuard`：`execute_command` 进入 HITL 之前的 fast-fail 黑名单（sudo / rm -rf 全盘 / mkfs / dd of=/dev / fork bomb / curl|sh / find / / chmod 777 / / shutdown）。**定位是辅助 HITL，不是主防线**——黑名单永远列不全（base64 解码后执行、`eval`、写 `~/.bashrc` 持久化等都漏），它只是减少 HITL 弹窗骚扰。真正的安全责任在 HITL 审批
- `ResourceLimit` 类约束：`write_file` 单文件 5MB；`execute_command` 60 秒超时 + 8KB 输出截断（与第 7 期共用）
- `AuditLog`：危险工具（`write_file` / `execute_command` / `create_project`）调用一律落一行 JSONL 到 `~/.paicli/audit/audit-YYYY-MM-DD.jsonl`，字段：`timestamp / tool / args / outcome (allow|deny|error) / reason / approver (hitl|policy|none) / durationMs`。审计写入失败仅 stderr 提示，不影响主流程
- 拦截出口：`PathGuard` / `CommandGuard` / 文件大小限制 都抛 `PolicyException`（`RuntimeException` 子类），由 `ToolRegistry.executeTool` 统一 catch、写 deny 审计、返回 `🛡️ 策略拒绝: ...`
- 与 HITL 协同顺序：`HitlToolRegistry` → `ToolRegistry` → 策略层。HITL 拒绝/跳过写 `approver=hitl` 审计；HITL 通过后策略层仍校验，**用户无法批准策略拒绝的请求**
- CLI 命令：
  - `/policy`：查看安全策略状态（项目根 / 危险工具 / 黑名单 / 审计目录）
  - `/audit [N]`：看今日最近 N 条审计（默认 10，最大 100）
- 提示词联动：`Agent` / `PlanExecuteAgent` / `SubAgent` 三处都告知 LLM 安全策略硬规则与 `🛡️ 策略拒绝` 输出格式，避免 LLM 原样重试同一条违规请求
- **不做沙箱的取舍**：本地 Agent CLI（参考 Claude Code / Cursor / Aider）默认都不做容器/VM 沙箱——沙箱削弱 Agent 能力（不能装依赖、不能跑全局命令）、给虚假安全感（容器逃逸真实存在）、体验更差。生产级 Agent 沙箱实际是 microVM-level（Devin / Modal / Anthropic Computer Use 用 Firecracker / gVisor），不是 Docker-level。想做隔离请参考 ROADMAP 末尾「Pro 升级版本」

### 8. 异步执行与并行工具调用

- 主入口在 `src/main/java/com/paicli/tool/ToolRegistry.java`
- `ToolRegistry.executeTools()` 负责批量执行同一轮 LLM 返回的多个工具调用
- 批量工具调用内部使用固定上限线程池并行执行，默认最多 4 个工具并发
- 返回结果保持原始 `tool_call` 顺序，调用方按这个顺序回灌 `tool` 消息，避免破坏 LLM 消息协议
- 批量工具调用有统一超时兜底，超时工具会返回 `工具执行超时（xx秒），已取消`
- `execute_command` 仍有独立的命令级超时，默认 60 秒
- `Agent`、`PlanExecuteAgent`、`SubAgent` 三条工具调用路径都应走 `executeTools()`，不要再各自手写逐个同步执行工具的 for-loop
- 系统提示词明确告知模型：同一轮多个工具调用会并行执行；如果工具之间有依赖关系，应分多轮调用
- `PlanExecuteAgent` 已支持同一 DAG 依赖批次内并行执行可执行任务
- `AgentOrchestrator` 已支持 Multi-Agent 同一依赖批次内部并行执行，默认最多 2 个 Worker 并发
- HITL 场景下危险工具仍会通过 `HitlToolRegistry.executeTool()` 透明拦截；终端审批由 `TerminalHitlHandler.requestApproval` 串行化，避免多线程同时抢 stdin/stdout

### 9. 联网能力（web_search + web_fetch）

- 主模块在 `src/main/java/com/paicli/web/`
- `SearchProvider` 接口 + 工厂：默认 `ZhipuSearchProvider`（与 GLM 推理共用 Key，国内首选），可切 `SerpApiSearchProvider` 或 `SearxngSearchProvider`，未来加 Brave / Tavily 只需实现接口
- `web_search` 工具不再返回拼接字符串，而是 provider 返回 `SearchResult` 列表（带 position / title / url / snippet / source），由 ToolRegistry 统一格式化
- `web_fetch` 工具链路：`NetworkPolicy.checkUrl()` → `acquire()`（限流）→ `WebFetcher.fetch()` → `HtmlExtractor.extract()`，全部本地，无第三方服务依赖
- `HtmlExtractor` 是简化版 readability：先按 `<article>` / `<main>` / `[role=main]` 选语义容器，否则按文本长度 - 链接占比惩罚 给 div/section 打分；最后递归转 Markdown（保留 h1-h6、列表、链接、代码块、表格）
- 边界明确：SPA / 防爬墙会返回 `body_empty: true` + `已知边界`提示，调用方不重试。JS 渲染可 fallback 到 Chrome DevTools MCP；登录态页面可先 `/browser connect` 切到 shared 模式
- 设计取舍：不做 LLM 二次摘要工具（混淆"工具 = 副作用"边界）；不引入 Jina（路线重叠，留到第 15 期 Skill 章节里作为 fallback）

### 10. MCP 协议接入

- 主模块在 `src/main/java/com/paicli/mcp/`
- 支持 stdio 子进程 server 与 Streamable HTTP 远程 server
- `McpConfigLoader` 读取 `~/.paicli/mcp.json` 和 `.paicli/mcp.json`，项目级按 server 名覆盖用户级
- `JsonRpcClient` 手写 JSON-RPC 2.0，请求 id 使用 `AtomicLong` 数字自增，请求响应配对用 `ConcurrentHashMap`
- `StdioTransport` 用 `ProcessBuilder` 启动子进程，stdout 读 newline-delimited JSON，stderr 单独 drain 到最近 200 行环形 buffer，避免 OS 缓冲填满造成死锁
- `StreamableHttpTransport` 复用 OkHttp，单 endpoint POST，支持 JSON 响应和 `text/event-stream` 的 SSE `data:` 消息，保存 `Mcp-Session-Id`
- `tools/list` 返回的工具会注册为 `mcp__{server}__{tool}`，并进入 LLM tool definitions
- `McpSchemaSanitizer` 会删 `$schema` / `$id` / `$ref`，把 `anyOf` / `oneOf` 降级为 object description，并截断超长 description
- `tools/call` 第一版只扁平化 text content；image / resource 返回 fallback 文本
- 所有 `mcp__` 前缀工具默认走 HITL 审批，并写入 AuditLog；审计参数会脱敏 Bearer、token、key、password、secret、authorization
- CLI 命令：
  - `/mcp`：查看所有 server 状态
  - `/mcp restart <name>`：重启单个 server
  - `/mcp logs <name>`：查看 stderr 环形 buffer
  - `/mcp disable <name>`：运行时禁用 server 并移除工具
  - `/mcp enable <name>`：运行时启用 server
- 当前不实现 `/mcp add` / `/mcp remove`，配置编辑通过文件 + 重启 PaiCLI

### 11. MCP 高级能力首批

本期交付 resources / prompts / 被动通知 / 运行中取消，不包含 OAuth 与 sampling。

- resources 双轨：
  - 工具层：支持 resources capability 的 server 会自动注册 `mcp__{server}__list_resources` / `mcp__{server}__read_resource` 两个虚拟工具
  - 用户输入层：普通输入支持 `@server:protocol://path`，提交给 Agent 前由 `AtMentionExpander` 展开为 `<resource>` 内联块
- JLine 自动补全只接普通输入态；Plan / Team 审阅里的 raw-mode 单键路径不接 completer，避免干扰 `ESC` / `Ctrl+O`
- `McpResourceCache` 缓存 server 启动时的 resources list；收到 `notifications/resources/list_changed` 或 `notifications/resources/updated` 后只做失效标记，下次 list/read 重拉
- `notifications/tools/list_changed` 会触发该 server 工具列表全量替换，入口是 `ToolRegistry.replaceMcpToolsForServer(...)`
- `/mcp resources <name>`：查看 server 暴露的 resources
- `/mcp prompts <name>`：查看 server 暴露的 prompts；只查看，不执行 `prompts/get`，不注入对话流
- @-mention 读取 resource 记录 `approver=mention` 审计；通过虚拟工具读取仍走普通 `mcp__` 工具审计与 HITL 规则
- `/cancel`：任务运行期间输入 `/cancel` 并回车，请求取消当前 Agent run；ReAct、Plan、Team、工具批次与 `execute_command` 会在边界处检查 `CancellationToken`
- 当前明确不做：OAuth、`sampling/createMessage`、MCP server 自动重启、prompts 注入对话、health ping / heartbeat、progress notification UI

### 12. Chrome DevTools MCP

第 13 期交付浏览器能力，第 14 期在其上新增 CDP 会话复用和登录态访问；仍不自研浏览器自动化协议，直接接 Google 官方 `chrome-devtools-mcp@latest`。

- 默认 server 名：`chrome-devtools`
- 默认启动命令：`npx -y chrome-devtools-mcp@latest --isolated=true`
- 默认配置文件：`~/.paicli/mcp.json` 不存在时由 `Main.ensureDefaultMcpConfig()` 自动创建；已有文件缺少 `chrome-devtools` 时只提示，不覆盖用户配置
- 工具注册名：`mcp__chrome-devtools__navigate_page`、`mcp__chrome-devtools__take_snapshot`、`mcp__chrome-devtools__click` 等
- `McpClient.initialize()` 默认超时 60 秒，可用系统属性 `paicli.mcp.initialize.timeout.seconds` 或环境变量 `PAICLI_MCP_INITIALIZE_TIMEOUT_SECONDS` 覆盖
- `McpServerManager.startAll(System.out)` 在长启动期间每 5 秒打印仍在 STARTING 的 server 等待时长，避免 npx 拉包或 Chrome 冷启动时看起来卡死
- 系统提示词要求：已知 URL 先 `web_fetch`，如果返回空正文 / SPA / 防爬墙边界，自动 fallback 到浏览器 MCP；浏览器读取优先 `take_snapshot`，不要默认 `take_screenshot`
- 微信公众号文章、知乎专栏、推特、小红书、React/Vue SPA、需要表单交互或登录态页面，优先按浏览器 MCP 路线处理
- `image` 类型 content 当前仍不进入多模态 LLM 输入，`McpCallToolResult` 只返回 fallback 文案，引导改用 `take_snapshot` 获取 DOM 文本
- HITL 的全部放行支持两个维度：普通工具按 tool name，MCP 工具可选按 server name。对 `chrome-devtools` 选 server 维度后，本会话内 `mcp__chrome-devtools__*` 不再重复弹审批
- `/browser status`：查看当前 isolated / shared 模式、chrome-devtools 状态、autoConnect 引导和旧式 9222 探活结果
- `/browser connect`：默认把 `chrome-devtools` 运行时切到 `--autoConnect` 并重启该 server，复用已在 `chrome://inspect/#remote-debugging` 允许远程调试的 Chrome；失败会回滚 args
- `/browser connect <port>`：旧式 CDP 端口路径，先探活 `127.0.0.1:<port>/json/version`，成功后切到 `--browser-url=http://127.0.0.1:<port>`；失败不修改 args
- `/browser disconnect`：切回 `--isolated=true`；切换 shared / isolated 都会清空 `chrome-devtools` 的 server 维度全部放行
- `/browser tabs`：shared 模式下调用 `mcp__chrome-devtools__list_pages` 查看真实 Chrome tab；isolated 模式下只提示使用 `/browser connect`
- Agent 可调用内置工具 `browser_connect` / `browser_disconnect` / `browser_status`。模型遇到登录页、权限不足或明确需要登录态时，应自己调用 `browser_connect` 后重试原 URL；公开页面（如微信公众号文章）不要提前切 shared
- shared 模式下 `close_page` 只允许关闭 PaiCLI 自己创建的 tab；无法证明是 PaiCLI 创建的 tab 时策略层拒绝，HITL 无法批准绕过
- 敏感页面策略在 `src/main/java/com/paicli/browser/SensitivePagePolicy.java`，内置 GitHub settings、支付、云控制台、飞书/Lark admin 等规则；用户可用 `~/.paicli/sensitive_patterns.txt` 追加 glob 规则
- 命中敏感页面后，`click` / `fill_form` / `evaluate_script` 等改写型浏览器工具必须单步 HITL 审批，不复用全部放行；`take_snapshot` 等读型工具不升级
- `AuditLog` 对 chrome-devtools 工具追加可选 metadata：`browser_mode` / `sensitive` / `target_url`；旧 7 字段 JSONL 仍可读取

### 13. Skill 系统

第 15 期把"Agent 该怎么思考"从硬编码 system prompt 抽出，沉淀成可复用的 Skill 单元。Skill = `SKILL.md`（决策手册）+ `references/`（按需读取的资料库）+ 可选 `scripts/`（可执行依赖）。

- 主模块：`src/main/java/com/paicli/skill/`
- 三层加载位置（按优先级，后者整体覆盖同名 skill）：
  1. jar 内置 `resources/skills/<name>/`
  2. 用户级 `~/.paicli/skills/<name>/SKILL.md`
  3. 项目级 `<project>/.paicli/skills/<name>/SKILL.md`
- 启动期 `SkillBuiltinExtractor` 把 jar 内置 skill 解压到 `~/.paicli/skills-cache/<name>/`，按 `.version` 文件控制重建（避免每次启动 IO）
- 启用状态持久化：`~/.paicli/skills.json` 的 `disabled` 列表，默认全启用
- frontmatter 解析手写 YAML 子集（`SkillFrontmatterParser`），不依赖 SnakeYAML；只支持单行 string / 多行 `|` block / 行内数组；嵌套对象会 stderr 警告并跳过该字段
- 必填字段：`name`（kebab-case，与目录名一致）、`description`（≤ 500 codepoint）；选填：`version`、`author`、`tags`
- system prompt 索引段：`SkillIndexFormatter.format()` 把启用 skill 渲染为 `## 可用 Skills` 段，注入到 `Agent` / `PlanExecuteAgent` / `SubAgent` 三处系统提示词末尾；启用上限 20 个，索引段总大小 ≤ 4KB
- 内置工具 `load_skill(name)`：LLM 在 system prompt 索引段里看到匹配 description 时主动调用，PaiCLI 把 SKILL.md 正文（去 frontmatter，5KB 截断）写入 `SkillContextBuffer`
- `SkillContextBuffer` 注入路径：下一轮 user message 构造时由 `Agent` / `PlanExecuteAgent` / `SubAgent` 调 `drain()` 取出，前置到原 user 内容前，形成 `## 已加载 Skill：<name>` 段
- buffer 一次性消费、最多保留 3 个 skill body、`/clear` 命令 reset；当前实现是 per-session 单实例（不做角色级隔离）
- CLI 命令：`/skill` / `/skill list` / `/skill show <name>` / `/skill on <name>` / `/skill off <name>` / `/skill reload`
- 内置 web-access skill：决策手册（浏览哲学四步法 + 工具选择表 + 浏览器优先级 + Jina 兜底说明）+ 6 个站点经验（mp.weixin / zhuanlan.zhihu / x.com / xiaohongshu / github / juejin）+ cdp-cheatsheet
- 站点经验三段式标准：`platform-features / valid-patterns / known-pitfalls`，frontmatter `domain / aliases / updated`；自我增强机制由 SKILL.md 里的提示驱动（"操作中发现新模式时主动写到 `~/.paicli/skills/web-access/references/site-patterns/<domain>.md`"）
- Jina Reader 集成位置：**只**在 web-access SKILL.md 写入提示，**不**改 `web_fetch` 工具内部链路（保持第 9 期纯本地约定）
- Skill 与 HITL 协同：Skill 内调用 `execute_command`、浏览器 MCP 等危险工具仍走 HITL 既有审批流；不给 Skill 单独审批维度

## 仓库结构

```text
src/main/java/com/paicli
├── agent/
│   ├── Agent.java
│   ├── PlanExecuteAgent.java
│   ├── AgentRole.java
│   ├── AgentMessage.java
│   ├── SubAgent.java
│   └── AgentOrchestrator.java
├── cli/
│   ├── Main.java
│   ├── CliCommandParser.java
│   └── PlanReviewInputParser.java
├── browser/
│   ├── BrowserSession.java
│   ├── BrowserConnectivityCheck.java
│   ├── BrowserGuard.java
│   └── SensitivePagePolicy.java
├── llm/
│   └── GLMClient.java
├── context/
│   ├── ContextMode.java
│   ├── ContextProfile.java
│   └── TokenUsageFormatter.java
├── memory/
│   ├── ConversationMemory.java
│   ├── LongTermMemory.java
│   ├── MemoryManager.java
│   ├── MemoryRetriever.java
│   ├── ContextCompressor.java
│   ├── ConversationHistoryCompactor.java
│   ├── MemoryEntry.java
│   └── TokenBudget.java
├── plan/
│   ├── ExecutionPlan.java
│   ├── Planner.java
│   └── Task.java
├── rag/
│   ├── EmbeddingClient.java
│   ├── VectorStore.java
│   ├── CodeChunk.java
│   ├── CodeChunker.java
│   ├── CodeAnalyzer.java
│   ├── CodeRelation.java
│   ├── CodeIndex.java
│   └── CodeRetriever.java
├── tool/
│   └── ToolRegistry.java
├── mcp/
│   ├── McpClient.java
│   ├── McpServerManager.java
│   ├── McpServer.java
│   ├── McpToolBridge.java
│   ├── config/
│   ├── jsonrpc/
│   ├── protocol/
│   ├── resources/
│   ├── mention/
│   ├── notifications/
│   └── transport/
├── hitl/
│   ├── ApprovalPolicy.java
│   ├── ApprovalRequest.java
│   ├── ApprovalResult.java
│   ├── HitlHandler.java
│   ├── TerminalHitlHandler.java
│   └── HitlToolRegistry.java
├── web/
│   ├── SearchProvider.java
│   ├── ZhipuSearchProvider.java
│   ├── SerpApiSearchProvider.java
│   ├── SearxngSearchProvider.java
│   ├── SearchProviderFactory.java
│   ├── SearchResult.java
│   ├── WebFetcher.java
│   ├── HtmlExtractor.java
│   ├── NetworkPolicy.java
│   └── FetchResult.java
├── policy/
│   ├── PolicyException.java
│   ├── PathGuard.java
│   ├── CommandGuard.java
│   └── AuditLog.java
└── skill/
    ├── Skill.java
    ├── SkillFrontmatterParser.java
    ├── SkillRegistry.java
    ├── SkillStateStore.java
    ├── SkillBuiltinExtractor.java
    ├── SkillContextBuffer.java
    └── SkillIndexFormatter.java
```

测试目前主要覆盖：

- `CliCommandParserTest`
- `MainBrowserCommandTest`
- `PlanReviewInputParserTest`
- `MainInputNormalizationTest`
- `ExecutionPlanTest`
- `MemoryEntryTest`
- `ConversationMemoryTest`
- `LongTermMemoryTest`
- `MemoryRetrieverTest`
- `MemoryManagerTest`
- `ExplicitMemoryHintsTest`
- `ContextProfileTest`
- `PlanExecuteAgentTest`
- `AgentMemoryHintTest`
- `AgentRoleTest`
- `AgentMessageTest`
- `AgentOrchestratorTest`
- `EmbeddingClientTest`
- `SearchResultTest`、`NetworkPolicyTest`、`HtmlExtractorTest`、`WebFetcherTest`、`SearchProviderFactoryTest`、`ZhipuSearchProviderTest`
- `VectorStoreTest`
- `CodeChunkerTest`
- `CodeAnalyzerTest`
- `CodeIndexTest`
- `ApprovalPolicyTest`
- `ApprovalResultTest`
- `HitlToolRegistryTest`
- `TerminalHitlHandlerTest`
- `ToolRegistryTest`
- `BrowserSessionTest`、`BrowserConnectivityCheckTest`、`SensitivePagePolicyTest`、`BrowserGuardTest`
- `McpSchemaSanitizerTest`、`McpConfigLoaderTest`、`JsonRpcClientTest`、`McpToolBridgeTest`、`McpResourceCacheTest`、`AtMentionParserTest`、`AtMentionExpanderTest`、`AtMentionCompleterTest`、`NotificationRouterTest`
- `PathGuardTest`、`CommandGuardTest`、`AuditLogTest`
- `SkillFrontmatterParserTest`、`SkillRegistryTest`、`SkillStateStoreTest`、`SkillBuiltinExtractorTest`、`SkillContextBufferTest`、`SkillIndexFormatterTest`、`LoadSkillToolTest`、`SkillCommandHandlerTest`

这意味着当前自动化测试更偏解析、计划结构、RAG 核心模块、Multi-Agent 编排逻辑、HITL 审批策略、策略层拦截规则、MCP 协议核心组件、MCP resources 输入层、长上下文策略计算与 Skill 加载机制，不覆盖真实 LLM 联调、真实 Embedding API 联调、真实 npm/uvx MCP server 联调，也不覆盖终端交互的完整手工体验。

## 核心文件说明

### `src/main/java/com/paicli/cli/Main.java`

- CLI 入口
- Banner 输出
- `.env` / 环境变量读取
- 日志目录初始化与 logback 配置系统属性注入
- ReAct 与 Plan 模式切换
- JLine 单键交互、raw mode、bracketed paste 处理

### `src/main/java/com/paicli/agent/Agent.java`

- ReAct 主循环
- 对话历史维护
- 工具调用执行与结果回灌

### `src/main/java/com/paicli/agent/PlanExecuteAgent.java`

- 规划后执行主流程
- 计划审阅
- DAG 任务执行
- 并行批次执行
- 失败后重规划

### `src/main/java/com/paicli/agent/AgentOrchestrator.java`

- Multi-Agent 编排器（主从架构中的"主"）
- 管理规划者、执行者、检查者三个角色
- 按依赖顺序分配步骤给 Worker
- 检查者审查结果，未通过则带反馈重试（最多 2 次）
- 解析规划者输出的 JSON 执行计划
- 解析检查者输出的审批结果

### `src/main/java/com/paicli/agent/SubAgent.java`

- 可配置角色的轻量子代理
- 三个角色对应三套系统提示词（规划者/执行者/检查者）
- 维护独立对话历史
- 执行者可使用工具调用，规划者和检查者不使用工具
- 支持流式输出（按角色显示不同标签）

### `src/main/java/com/paicli/agent/AgentRole.java`

- Agent 角色枚举：PLANNER、WORKER、REVIEWER
- 每个角色有显示名和描述

### `src/main/java/com/paicli/agent/AgentMessage.java`

- Agent 间通信消息类型
- 五种消息类型：TASK、RESULT、FEEDBACK、APPROVAL、REJECTION

### `src/main/java/com/paicli/plan/Planner.java`

- 调用 LLM 生成计划 JSON
- 对明显简单的任务走最小计划快捷路径，避免过度规划
- 解析任务列表
- 重新编号为 `task_1`、`task_2`...
- 计算依赖关系和执行顺序

### `src/main/java/com/paicli/plan/ExecutionPlan.java`

- DAG 拓扑排序
- 可执行任务判定
- 进度、状态、可视化与摘要

### `src/main/java/com/paicli/tool/ToolRegistry.java`

当前内置工具有 8 个：

- `read_file`
- `write_file`
- `list_dir`
- `execute_command`（在当前项目目录执行短时命令，默认 60 秒超时，不允许扫描 `/`、`~` 或整个文件系统）
- `create_project`
- `search_code`
- `web_search`（通过 `SearchProvider` 抽象，支持 SerpAPI 与 SearXNG 两种实现；provider 未就绪时返回引导提示而非抛错）
- `web_fetch`（抓取 URL → 提取正文 → Markdown，本地实现；遇 SPA/防爬墙返回空正文 + 边界提示）

另外会动态注册 MCP 工具：

- `mcp__{server}__{tool}`：由 MCP server 的 `tools/list` 返回，`ToolRegistry.registerMcpTool()` 注入，执行时经 `McpToolBridge` 路由到 `tools/call`
- MCP 工具不是内置 8 个之一，但会进入同一套 LLM tool definitions、并行执行、HITL 和 AuditLog 流程
- 支持 resources capability 的 MCP server 还会注册两个虚拟工具：`mcp__{server}__list_resources` / `mcp__{server}__read_resource`

第七期新增的并行执行入口也在这里：

- `ToolInvocation`：封装一次工具调用的 id、工具名与 JSON 参数
- `ToolExecutionResult`：封装工具结果、耗时与是否超时
- `executeTools(List<ToolInvocation>)`：并行执行同一批工具调用，并按输入顺序返回结果

### `src/main/java/com/paicli/mcp/`

- `McpServerManager.java`：读取配置、并行启动 server、注册/移除 MCP 工具、处理 `/mcp` 系列命令
- `McpClient.java`：封装 `initialize`、`tools/list`、`tools/call`
- `jsonrpc/JsonRpcClient.java`：JSON-RPC 请求响应配对、通知路由和超时
- `transport/StdioTransport.java`：stdio 子进程三流管理，stderr 环形日志
- `transport/StreamableHttpTransport.java`：Streamable HTTP POST / SSE / session ID
- `protocol/McpSchemaSanitizer.java`：清洗 MCP inputSchema，避免模型不兼容 JSON Schema 子集
- `resources/`：resources/list + resources/read 的描述、缓存和虚拟工具封装
- `mention/`：解析 `@server:protocol://path`、JLine 候选补全、提交前展开 `<resource>` 块
- `notifications/NotificationRouter.java`：被动路由 MCP notification，当前处理工具列表变化和 resource cache 失效

### `src/main/java/com/paicli/llm/GLMClient.java`

- 当前固定模型：`glm-5.1`
- 当前固定接口：`https://open.bigmodel.cn/api/coding/paas/v4/chat/completions`
- 底层默认通过流式接口获取响应，并负责消息、`reasoning_content`、tools、tool_calls、usage 解析

### `src/main/resources/logback.xml`

- 默认日志落盘配置
- 按天 + 按文件大小滚动
- 支持保留天数和总容量上限

## 当前已知边界

下面这些内容在路线图里出现了，但当前仓库还没有真正交付：

- 持久化后台任务队列 / 跨会话异步长任务调度
- 容器 / VM 沙箱：本地 Agent CLI 默认不做容器隔离（参考 Claude Code / Cursor / Aider）；想做隔离请参考 ROADMAP 末尾「Pro 升级版本」或自行实现 `SandboxDriver` 接口
- MCP 后续高级能力：OAuth、`sampling/createMessage`、server 自动重启、prompts 注入对话
- TUI 产品化界面

不要把 `ROADMAP.md` 中“将来要做”误读成“现在已经有”。

## 修改时的硬规则

### 1. 改行为，不只改代码

如果你改的是用户可见行为，需要同步检查这些文档是否要更新：

- `AGENTS.md`
- `README.md`
- `ROADMAP.md`（仅在阶段目标或完成状态变化时）

### 2. 改命令入口，要联动这几处

如果修改 `/plan`、`/team`、`/cancel`、`/hitl`、`/mcp`、`/policy`、`/audit`、`/clear`、`/memory`、`/save`、`/index`、`/search`、`/graph`、`/exit` 或输入解析：

- `Main.java`
- `CliCommandParser.java`
- 对应测试
- `README.md`
- `AGENTS.md`

当前输入解析约定补充：

- 任何未识别的 `/xxx` 都应在 CLI 层直接报“未知命令”
- 不要把未知 slash 命令回退给 Agent 当普通自然语言处理

### 3. 改 Plan 审阅交互，要联动这几处

如果修改 `Enter / ESC / I / Ctrl+O` 的行为：

- `Main.java`
- `PlanReviewInputParser.java`
- 相关测试
- `README.md`
- `AGENTS.md`

这块尤其要做真实手工验证，因为 raw mode 和行输入回退共存。

### 4. 改工具集，要联动这几处

如果新增、删除或修改工具：

- `ToolRegistry.java`
- `Agent.java` 的系统提示词
- `PlanExecuteAgent.java` 的执行提示词
- `SubAgent.java` 的 WORKER_PROMPT
- 如有必要，`Planner.java` 的规划提示词
- `README.md`
- `AGENTS.md`

不要只改工具注册，不改提示词，否则模型不会稳定学会使用。

### 5. 改模型或接口，要联动这几处

如果修改 GLM 模型名、接口地址、认证方式或配置项：

- `GLMClient.java`
- `Main.java`（如果配置读取方式变化）
- `.env.example`
- `README.md`
- `AGENTS.md`

### 5.1 改 Embedding 配置或向量存储，要联动这几处

如果修改 Embedding provider、模型、接口或向量存储结构：

- `EmbeddingClient.java`
- `VectorStore.java`
- `.env.example`
- `README.md`
- `AGENTS.md`

### 5.2 改搜索 / 抓取或网络策略，要联动这几处

如果新增 SearchProvider 实现，或调整 NetworkPolicy / WebFetcher 行为：

- `src/main/java/com/paicli/web/` 下相关文件
- `ToolRegistry.java` 的 `webSearch` / `webFetch` 实现
- `SearchProviderFactory.pickProvider` 的环境变量优先级
- `.env.example`：补充新的环境变量示例
- `README.md` 与 `AGENTS.md`：工具列表 / 安全策略段落
- 至少补一个相应的单元测试

### 5.3 改 Memory 持久化或预算策略，要联动这几处

- `MemoryManager.java`
- `LongTermMemory.java`
- `TokenBudget.java`
- 至少一个 memory 测试
- `README.md`
- `AGENTS.md`

### 5.4 改 HITL 增强（路径围栏 / 命令黑名单 / 审计 / 资源上限），要联动这几处

如果新增黑名单规则、调整 PathGuard 行为、改 AuditLog 字段、或加新的资源上限：

- `src/main/java/com/paicli/policy/` 下相关文件
- `ToolRegistry.java`：执行入口与拦截路径
- `HitlToolRegistry.java`：HITL 审批与策略层审计的协同
- `Agent.java` / `PlanExecuteAgent.java` / `SubAgent.java` 的系统提示词：让 LLM 知道新增规则与 `🛡️ 策略拒绝` 输出格式
- `Main.java`：如果新增 `/policy` 子命令或 `/audit` 行为变化
- `.env.example`：新增的环境变量示例（如 `PAICLI_AUDIT_DIR`）
- `README.md` 与 `AGENTS.md`：HITL 增强子段 + 命令列表
- 至少补一个对应的单元测试（`PathGuardTest` / `CommandGuardTest` / `AuditLogTest`）

### 5.5 改 MCP 协议或 server 管理，要联动这几处

如果新增 MCP transport、调整配置格式、改变工具命名、改 `tools/list` / `tools/call` / `resources/list` / `resources/read` 行为、调整 `@server:protocol://path` 输入层、或新增 `/mcp` 子命令：

- `src/main/java/com/paicli/mcp/` 下相关文件
- `ToolRegistry.java`：MCP 工具注册、卸载、执行路由、审计判断
- `ApprovalPolicy.java` / `ApprovalRequest.java` / `HitlToolRegistry.java`：MCP 默认审批和展示信息
- `AuditLog.java`：确保 args 脱敏仍覆盖 token / key / password / authorization / Bearer
- `Main.java` / `CliCommandParser.java`：启动加载、命令解析和命令输出
- `Agent.java` / `PlanExecuteAgent.java` / `SubAgent.java`：提示词中的 MCP 工具说明和策略拒绝规则
- `.env.example`、`README.md`、`ROADMAP.md`、`AGENTS.md`
- 至少补一个对应的 MCP 单元测试（配置、schema、JSON-RPC、resources、mention、notification、工具注册或 CLI 解析）

### 6. 不要提交敏感信息或产物垃圾

- 不提交 `.env`
- 不把真实 API Key 写进代码或文档示例
- 不手改 `target/` 里的生成产物

### 7. 保持代码可读性

这个仓库强调结构清晰、逻辑可读，是要长期维护演进的商业产品。

所以改动时优先：

- 结构清晰
- 逻辑可读
- 文件职责明确
- 少量但必要的注释

不要为了"工程炫技"把简单逻辑过度抽象到难以理解和维护。

## 建议验证路径

### 文档或轻微重构

- 至少跑 `mvn test`

### 命令解析相关

```bash
mvn test -Dtest=CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest
```

### 计划执行/DAG 相关

```bash
mvn test -Dtest=ExecutionPlanTest
```

### Multi-Agent 相关

```bash
mvn test -Dtest=AgentRoleTest,AgentMessageTest,AgentOrchestratorTest
```

### 改了交互或终端输入

除了测试，还应手工 smoke test：

1. 启动 CLI
2. 输入 `/plan`
3. 验证 `ESC` 取消待执行 plan
4. 验证 `Ctrl+O` 展开计划
5. 验证 `I` 可以补充要求后重规划
6. 验证执行完成后自动回到默认 `ReAct`

## 给新线程的工作建议

进入仓库后，建议按这个顺序建立上下文：

1. 先看本文件
2. 再看 `README.md`
3. 再看 `Main.java`
4. 然后根据任务进入对应模块

如果用户提的是：

- CLI 命令问题：先看 `Main.java` + `CliCommandParser.java`
- 规划/DAG 问题：先看 `PlanExecuteAgent.java` + `Planner.java` + `ExecutionPlan.java`
- 工具调用问题：先看 `ToolRegistry.java` + `Agent.java`
- API/模型问题：先看 `GLMClient.java`
- RAG/代码检索问题：先看 `CodeRetriever.java` + `CodeIndex.java` + `VectorStore.java`
- 代码分块/AST 问题：先看 `CodeChunker.java` + `CodeAnalyzer.java`
- Multi-Agent 协作问题：先看 `AgentOrchestrator.java` + `SubAgent.java` + `AgentRole.java` + `AgentMessage.java`

## 持续维护约定

以后凡是形成了稳定协作规则，例如：

- 哪些文件改动必须联动哪些测试
- 哪些行为以代码为准而 README 常滞后
- 哪些版本号或运行方式存在容易误判的地方
- 哪些模块是后续线程最容易踩坑的地方

都直接补进 `AGENTS.md`，不要只留在聊天记录里。
