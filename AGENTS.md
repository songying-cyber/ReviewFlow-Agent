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
- 当前主线：已完成第 1 期 `ReAct`，已落地第 2 期 `Plan-and-Execute + DAG`
- 当前用户可感知版本：CLI Banner 显示 `v2.0.0`
- 当前 Maven 产物版本：`pom.xml` 仍是 `1.0-SNAPSHOT`
- 结论：如果你看到运行界面是 `v2.0.0`，但 Jar 名仍是 `paicli-1.0-SNAPSHOT.jar`，这是当前仓库的真实状态，不是你看错

## 运行前提

- Java 17+
- Maven
- 可用的 `GLM_API_KEY`

API Key 当前读取顺序以代码为准：

1. 仓库当前目录下的 `.env`
2. 用户主目录下的 `.env`
3. 环境变量 `GLM_API_KEY`

`.env.example` 只定义了：

```bash
GLM_API_KEY=your_api_key_here
```

## 常用命令

```bash
cp .env.example .env
mvn clean package
java -jar target/paicli-1.0-SNAPSHOT.jar
mvn clean compile exec:java -Dexec.mainClass="com.paicli.cli.Main"
mvn test
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

### 2. Plan-and-Execute 模式

- 通过 `/plan` 或 `/plan <任务>` 进入
- 主入口在 `src/main/java/com/paicli/agent/PlanExecuteAgent.java`
- 流程是：规划 -> 用户审阅 -> 执行 DAG -> 汇总结果
- 计划执行完后会回到默认 `ReAct`

### 3. Plan 审阅交互

以 `Main.java` 当前实现为准：

- `Enter`：执行当前计划
- `Ctrl+O`：展开完整计划
- `ESC`：如果当前在展开视图则先折叠，否则取消本次计划
- `I`：输入补充要求并重新规划

注意：

- 这里不是 README 里旧描述的“只有 Enter / ESC / I”
- 原始按键处理依赖 JLine raw mode
- 涉及这块的改动，不能只看字符串，要连输入模式和回退路径一起看

## 仓库结构

```text
src/main/java/com/paicli
├── agent/
│   ├── Agent.java
│   └── PlanExecuteAgent.java
├── cli/
│   ├── Main.java
│   ├── CliCommandParser.java
│   └── PlanReviewInputParser.java
├── llm/
│   └── GLMClient.java
├── plan/
│   ├── ExecutionPlan.java
│   ├── Planner.java
│   └── Task.java
└── tool/
    └── ToolRegistry.java
```

测试目前主要覆盖：

- `CliCommandParserTest`
- `PlanReviewInputParserTest`
- `MainInputNormalizationTest`
- `ExecutionPlanTest`

这意味着当前自动化测试更偏解析和计划结构，不覆盖真实 LLM 联调，也不覆盖终端交互的完整手工体验。

## 核心文件说明

### `src/main/java/com/paicli/cli/Main.java`

- CLI 入口
- Banner 输出
- `.env` / 环境变量读取
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

### `src/main/java/com/paicli/plan/Planner.java`

- 调用 LLM 生成计划 JSON
- 解析任务列表
- 重新编号为 `task_1`、`task_2`...
- 计算依赖关系和执行顺序

### `src/main/java/com/paicli/plan/ExecutionPlan.java`

- DAG 拓扑排序
- 可执行任务判定
- 进度、状态、可视化与摘要

### `src/main/java/com/paicli/tool/ToolRegistry.java`

当前内置工具只有 5 个：

- `read_file`
- `write_file`
- `list_dir`
- `execute_command`
- `create_project`

### `src/main/java/com/paicli/llm/GLMClient.java`

- 当前固定模型：`glm-5.1`
- 当前固定接口：`https://open.bigmodel.cn/api/coding/paas/v4/chat/completions`
- 负责消息、tools、tool_calls、usage 解析

## 当前已知边界

下面这些内容在路线图里出现了，但当前仓库还没有真正交付：

- Memory 系统
- RAG / 代码库检索
- Multi-Agent
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

如果修改 `/plan`、`/clear`、`/exit` 或输入解析：

- `Main.java`
- `CliCommandParser.java`
- 对应测试
- `README.md`
- `AGENTS.md`

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

## 持续维护约定

以后凡是形成了稳定协作规则，例如：

- 哪些文件改动必须联动哪些测试
- 哪些行为以代码为准而 README 常滞后
- 哪些版本号或运行方式存在容易误判的地方
- 哪些模块是后续线程最容易踩坑的地方

都直接补进 `AGENTS.md`，不要只留在聊天记录里。
