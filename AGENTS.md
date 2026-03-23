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
- 定位：一个教学导向的 Java Agent CLI，目标是从简单 Agent CLI 逐步演进到更完整的 Agent 产品
- 当前主线：已完成第 1 期 `ReAct`、第 2 期 `Plan-and-Execute + DAG`、第 3 期 `Memory + 上下文工程`、第 4 期 `RAG 检索 + 代码库理解`、第 5 期 `Multi-Agent 协作 + 角色分工`
- 当前用户可感知版本：CLI Banner 显示 `v5.0.0`
- 当前 Maven 产物版本：`pom.xml` 仍是 `1.0-SNAPSHOT`
- 结论：如果你看到运行界面是 `v5.0.0`，但 Jar 名仍是 `paicli-1.0-SNAPSHOT.jar`，这是当前仓库的真实状态，不是你看错

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

Embedding 配置读取顺序（以代码实际行为为准）：

1. 环境变量：`EMBEDDING_PROVIDER`、`EMBEDDING_MODEL`、`EMBEDDING_BASE_URL`、`EMBEDDING_API_KEY`
2. 系统属性（同上）
3. 默认值：`ollama` / `nomic-embed-text:latest` / `http://localhost:11434`

日志配置读取顺序（以代码实际行为为准）：

1. 系统属性：`paicli.log.dir`、`paicli.log.level`、`paicli.log.maxHistory`、`paicli.log.maxFileSize`、`paicli.log.totalSizeCap`
2. 环境变量或 `.env`：`PAICLI_LOG_DIR`、`PAICLI_LOG_LEVEL`、`PAICLI_LOG_MAX_HISTORY`、`PAICLI_LOG_MAX_FILE_SIZE`、`PAICLI_LOG_TOTAL_SIZE_CAP`
3. 默认值：`~/.paicli/logs` / `INFO` / `7` / `10MB` / `100MB`

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
- 最多迭代 10 轮
- 支持工具调用后继续思考
- 用户默认看到的是流式输出的模型 `reasoning_content`（如果接口返回）和最终结果；ReAct 与用户可见的 Plan 阶段都走流式展示；终端会先渲染常见 Markdown 再输出；工具参数、工具返回片段、Token 使用量不再作为默认用户输出
- 会写入短期记忆，并在清空时提取关键事实到长期记忆

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
- CLI 命令：
  - `/memory` 或 `/mem`：查看当前记忆状态
  - `/save <事实>`：手动保存关键事实
- `ReAct` 和 `Plan-and-Execute` 两条主路径都应写回记忆；改动其中一条时，另一条也要检查

### 5. RAG 系统

- 主模块在 `src/main/java/com/paicli/rag/`
- 默认包含：EmbeddingClient、VectorStore（SQLite）、CodeChunker、CodeAnalyzer、CodeIndex、CodeRetriever
- CLI 命令：
  - `/index [路径]`：索引代码库
  - `/search <查询>`：语义检索代码
  - `/graph <类名>`：查看代码关系图谱
- Agent 工具：`search_code`（语义检索代码库）
- 在 ReAct 和 Plan 模式下，Agent 会自动检索代码上下文辅助回答

### 6. Multi-Agent 协作模式

- 通过 `/team` 或 `/team <任务>` 进入
- 主入口在 `src/main/java/com/paicli/agent/AgentOrchestrator.java`
- 采用主从架构：编排器（Orchestrator）为"主"，子代理（SubAgent）为"从"
- 三个角色：
  - 规划者（Planner）：拆解任务为执行步骤
  - 执行者（Worker）：调用工具执行具体操作（默认 2 个 Worker 轮询分配）
  - 检查者（Reviewer）：审查执行结果质量
- 协作流程：规划 -> 按依赖顺序分配给 Worker -> Reviewer 审查 -> 通过则完成，未通过则带反馈重试
- 同一个依赖批次内部当前仍按步骤串行执行（Worker 仅做轮询分担对话历史，没有真正并发），以保证流式输出不交错、方便教学阅读
- 冲突解决：每步最多重试 2 次，超过次数保留当前结果
- Reviewer 审查结果解析不出来（空内容、缺 approved 字段、既无肯定也无否定关键词）时，采取保守策略判为未通过
- 如果某步失败导致其依赖步骤无法执行，Orchestrator 会显式提示 `⏭️ 步骤 [step_x] 因前置步骤失败被跳过`
- SubAgent 在执行阶段遭遇 `IOException`（LLM 调用失败、超时等）时返回 `AgentMessage.Type.ERROR`，调用方需要独立于 RESULT 处理
- 所有子代理共享同一个 ToolRegistry 与 MemoryManager（与 ReAct 模式共享项目路径与记忆上下文，避免重复加载长期记忆）
- 任务执行完后回到默认 `ReAct`
- `ReAct`、`Plan-and-Execute` 和 `Multi-Agent` 三条路径都应写回记忆

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
├── llm/
│   └── GLMClient.java
├── memory/
│   ├── ConversationMemory.java
│   ├── LongTermMemory.java
│   ├── MemoryManager.java
│   ├── MemoryRetriever.java
│   ├── ContextCompressor.java
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
└── tool/
    └── ToolRegistry.java
```

测试目前主要覆盖：

- `CliCommandParserTest`
- `PlanReviewInputParserTest`
- `MainInputNormalizationTest`
- `ExecutionPlanTest`
- `MemoryEntryTest`
- `ConversationMemoryTest`
- `LongTermMemoryTest`
- `MemoryRetrieverTest`
- `MemoryManagerTest`
- `PlanExecuteAgentTest`
- `AgentRoleTest`
- `AgentMessageTest`
- `AgentOrchestratorTest`
- `EmbeddingClientTest`
- `VectorStoreTest`
- `CodeChunkerTest`
- `CodeAnalyzerTest`
- `CodeIndexTest`

这意味着当前自动化测试更偏解析、计划结构、RAG 核心模块、Multi-Agent 编排逻辑，不覆盖真实 LLM 联调、真实 Embedding API 联调，也不覆盖终端交互的完整手工体验。

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

当前内置工具只有 6 个：

- `read_file`
- `write_file`
- `list_dir`
- `execute_command`（在当前项目目录执行短时命令，默认 60 秒超时，不允许扫描 `/`、`~` 或整个文件系统）
- `create_project`
- `search_code`

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

- HITL 审批流
- 异步长任务调度
- 沙箱与权限控制
- MCP 接入
- TUI 产品化界面

不要把 `ROADMAP.md` 中“将来要做”误读成“现在已经有”。

## 修改时的硬规则

### 1. 改行为，不只改代码

如果你改的是用户可见行为，需要同步检查这些文档是否要更新：

- `AGENTS.md`
- `README.md`
- `ROADMAP.md`（仅在阶段目标或完成状态变化时）

### 2. 改命令入口，要联动这几处

如果修改 `/plan`、`/team`、`/clear`、`/memory`、`/save`、`/index`、`/search`、`/graph`、`/exit` 或输入解析：

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

### 5.2 改 Memory 持久化或预算策略，要联动这几处

- `MemoryManager.java`
- `LongTermMemory.java`
- `TokenBudget.java`
- 至少一个 memory 测试
- `README.md`
- `AGENTS.md`

### 6. 不要提交敏感信息或产物垃圾

- 不提交 `.env`
- 不把真实 API Key 写进代码或文档示例
- 不手改 `target/` 里的生成产物

### 7. 保持“教学型代码”可读性

这个仓库不是纯业务系统，也承担教程示例作用。

所以改动时优先：

- 结构清晰
- 逻辑可读
- 文件职责明确
- 少量但必要的注释

不要为了“工程炫技”把简单逻辑过度抽象到难以教学理解。

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
