# 第 19 期：Prompt 分层架构

> 当前状态：MVP 已落地。目标是把 ReAct / Plan / Team / Planner 的 system prompt 从 Java 源码中抽离为 Markdown 资源，并支持用户级、项目级覆盖。

## 目标

第 19 期解决的是 prompt 可维护性问题：

1. 调 prompt 不再需要改 Java 源码。
2. 不同职责的 prompt 分层存放，避免一个超长字符串承担所有职责。
3. 稳定内容在前，动态上下文在后，尽量提高 prompt cache 命中。
4. Agent / Plan / Team / Planner 四条路径共用同一个组装器。
5. 用户可以覆盖内置 prompt，项目也可以覆盖 prompt。

## 已落地范围

主模块：

```text
src/main/java/com/paicli/prompt/
├── PromptAssembler.java
├── PromptContext.java
├── PromptMode.java
└── PromptRepository.java
```

内置 prompt 资源：

```text
src/main/resources/prompts/
├── base.md
├── handoff.md
├── approvals/
│   ├── auto.md
│   ├── never.md
│   └── suggest.md
├── context/
│   └── context-management.md
├── modes/
│   ├── agent.md
│   ├── plan.md
│   ├── planner.md
│   ├── team-planner.md
│   ├── team-reviewer.md
│   └── team-worker.md
└── personalities/
    └── calm.md
```

接入点：

- `Agent`：默认 ReAct system prompt 由 `PromptAssembler` 组装。
- `PlanExecuteAgent`：每个 task 的执行 system prompt 由 `PromptAssembler` 组装，并注入 `taskType` / `taskDescription`。
- `SubAgent`：Planner / Worker / Reviewer 三角色按 `PromptMode` 组装。
- `Planner`：Plan-and-Execute 的规划 prompt 由 `PromptAssembler` 组装。

## 组装顺序

`PromptAssembler` 固定按下面顺序组装：

```text
base
personality
mode
approval
project_context
skills
context_mgmt
handoff
```

其中 `project_context` 和 `skills` 是运行期动态段：

- `memoryContext`
- `externalContext`（例如 MCP resource index）
- `skillIndex`

## 覆盖规则

同一路径按下面优先级读取：

1. jar 内置：`src/main/resources/prompts/...`
2. 用户级覆盖：`~/.paicli/prompts/...`
3. 项目级覆盖：`.paicli/prompts/...`

例如：

```text
~/.paicli/prompts/base.md
~/.paicli/prompts/modes/agent.md
.paicli/prompts/modes/team-worker.md
```

覆盖是“整文件替换”，不是局部 merge。

## 校验规则

`base.md` 或最终组装结果必须包含：

```markdown
## Language
```

这个 section 用来保证模型默认跟随中文输出。用户覆盖 `base.md` 时如果删掉该 section，启动或调用 prompt 组装会失败。

## 开发拆分

- [x] 新增 `PromptMode` / `PromptContext` / `PromptRepository` / `PromptAssembler`
- [x] 新增内置 Markdown prompt 资源
- [x] 支持用户级覆盖 `~/.paicli/prompts/...`
- [x] 支持项目级覆盖 `.paicli/prompts/...`
- [x] 校验 `## Language`
- [x] ReAct 接入
- [x] Plan task executor 接入
- [x] Multi-Agent 三角色接入
- [x] Planner 接入
- [x] 写 `PromptAssemblerTest`
- [x] 更新 `AGENTS.md`
- [x] 更新 `README.md`
- [x] 更新 `ROADMAP.md`
- [x] 写 prompt 审计模板

## 测试记录

已执行：

```bash
mvn test -Dtest=PromptAssemblerTest,PlannerTest,PlanExecuteAgentTest,SubAgentTest,AgentOrchestratorTest,AgentMemoryHintTest
```

结果：通过，28 个测试，0 failures / 0 errors。

## 当前边界

- Memory 压缩和事实抽取 prompt 仍在 `memory` 包内，后续可单独拆到 `prompts/memory/`。
- 覆盖策略是整文件替换，不做 YAML frontmatter、include 或局部 patch。
- 当前未提供 CLI 查看 prompt 内容，先通过文件约定和测试覆盖。
