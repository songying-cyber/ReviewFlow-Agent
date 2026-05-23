# 第 18 期：Git Side-History 快照与回滚

> 当前状态：MVP 开发中。第 18 期目标是给 Agent 改文件加一层文件安全网：每个 turn 前后自动快照，用户可以恢复到某个 turn 开始前，而且不污染用户项目自己的 `.git` 历史。

## 目标

Agent 能放心改代码的前提不是“永远不犯错”，而是“改坏了能退回”。第 18 期要做到：

1. 每个用户 turn 开始前自动保存 workspace 快照。
2. turn 结束后再保存一次快照，用于对比和审计。
3. 用户可以恢复到最近某个 turn 的 pre-turn 状态。
4. 快照仓库与用户项目 `.git` 完全隔离，不写用户提交历史。
5. 快照与恢复链路同时覆盖 ReAct、Plan、Team、TUI。

## MVP 范围

### 1. 模块与数据结构

新增包：`src/main/java/com/paicli/snapshot/`

- `SideGitManager`
  - 初始化 side-git 仓库
  - 执行 add / commit / list / restore
  - 负责 project root 与 snapshot root 的路径计算
- `SnapshotConfig`
  - `enabled`
  - `maxSnapshots`
  - `snapshotRoot`
  - `excludes`
- `TurnSnapshot`
  - `turnId`
  - `phase`：`pre` / `post` / `pre-restore`
  - `commitId`
  - `createdAt`
  - `summary`
- `RestoreResult`
  - `success`
  - `restoredCommit`
  - `changedFiles`
  - `message`

### 2. Side-git 仓库位置

默认目录：

```text
~/.paicli/snapshots/<project_hash>/<worktree_hash>/.git
```

约束：

- 不能写用户项目 `.git`
- 不能依赖系统 `git` 命令
- 优先使用 JGit 纯 Java 实现
- project hash 与 worktree hash 必须稳定，路径移动后生成新 side history

### 3. 依赖

`pom.xml` 新增：

```xml
<dependency>
    <groupId>org.eclipse.jgit</groupId>
    <artifactId>org.eclipse.jgit</artifactId>
    <version>7.6.0.202603022253-r</version>
</dependency>
```

已按 Maven Central 当前发布版落地 `7.6.0.202603022253-r`。

### 4. 快照时机

#### ReAct

在普通用户输入进入 `Agent.run()` 前后包一层：

```text
preTurnSnapshot(turnId)
Agent.run(userInput)
postTurnSnapshot(turnId)
```

#### Plan-and-Execute

以整个 `/plan <任务>` 为一个 turn：

- 计划生成前或用户确认执行前做 pre-turn，需要落地时二选一并写清理由
- 所有 DAG task 完成后做 post-turn
- 不要每个 task 都自动快照，避免快照噪声过大

#### Multi-Agent

以整个 `/team <任务>` 为一个 turn：

- Orchestrator 执行前做 pre-turn
- 所有步骤完成后做 post-turn
- Worker 内部不要额外快照

#### TUI

`TuiSessionController` 必须复用同一个 turn wrapper，避免 CLI 有快照、TUI 没快照。

### 5. 异步策略

快照写入策略：

- pre-turn 同步完成后再进入 Agent，避免任务已改文件时 pre 快照才开始扫描
- post-turn 异步写入
- pre-turn 失败：stderr / log 记录，任务继续
- post-turn 失败：stderr / log 记录，不影响最终回复
- 同一项目同一时刻只允许一个 snapshot 写入 side repo
- 如果上一个 snapshot 还没完成，新 snapshot 可以排队或合并，MVP 推荐单线程 executor 串行

### 6. 排除规则

默认排除：

```text
.git/
.paicli/snapshots/
target/
node_modules/
dist/
.idea/
*.class
*.jar
```

可配置：

```bash
PAICLI_SNAPSHOT_ENABLED=true
PAICLI_SNAPSHOT_MAX=50
PAICLI_SNAPSHOT_EXCLUDES=.git,.paicli/snapshots,target,node_modules,dist,.idea,*.class,*.jar
```

系统属性同样支持：

```bash
-Dpaicli.snapshot.enabled=false
-Dpaicli.snapshot.max=50
-Dpaicli.snapshot.excludes=...
```

### 7. CLI 命令

新增命令：

```text
/snapshot
/snapshot status
/snapshot clean
/restore <N>
```

语义：

- `/snapshot`：列最近快照，默认显示最近 10 个 turn
- `/snapshot status`：显示 side-git 目录、enabled、max、排除规则
- `/snapshot clean`：清理当前项目的 side-git 快照目录
- `/restore <N>`：恢复到最近第 N 个 pre-turn 快照，`N=1` 表示恢复到最近一个 turn 开始前

恢复前必须先做 `pre-restore` 快照，避免用户恢复错了没有退路。

### 8. Agent 工具

新增工具：

```text
revert_turn
```

参数：

```json
{
  "turn_offset": 1,
  "reason": "刚才修改后测试失败，需要回到修改前"
}
```

要求：

- 纳入危险工具，走 HITL 审批
- 写 AuditLog
- LLM 系统提示词说明：只有在明确判断当前 turn 改坏了，且用户目标仍需要继续推进时才调用
- 默认只允许恢复 pre-turn 快照，不恢复 post-turn 快照

## 不做

- 不做 per-tool 自动快照。第 18 期先做 turn 级别，避免并行工具调用时快照交错。
- 不改变用户项目 `.git` 的 branch、index、HEAD。
- 不自动提交用户项目 Git。
- 不做远程备份。
- 不做冲突交互式 merge。恢复就是把目标快照内容写回工作区。

## 开发拆分

### Day 1：设计文档 + 依赖 + 核心模型

- [x] 新增 JGit 依赖
- [x] 新增 `snapshot` 包和基础 record / config 类
- [x] 实现 snapshot root 计算
- [ ] 写 `SnapshotConfigTest`

### Day 2：SideGitManager 初始化与提交

- [x] 初始化独立 side-git 仓库
- [x] 配置 worktree 指向项目根
- [x] 实现 `preTurnSnapshot(turnId)` / `postTurnSnapshot(turnId)`
- [x] 实现默认 excludes
- [x] 写 `SideGitManagerTest`

### Day 3：快照列表与清理

- [x] 实现最近快照列表
- [x] 实现按 turn offset 定位 pre-turn 快照
- [ ] 实现 `maxSnapshots` 历史压缩策略
- [ ] 写 `SnapshotStoreTest`

### Day 4：恢复

- [x] 实现 `restorePreTurn(turnOffset)`
- [x] 恢复前自动创建 `pre-restore` 快照
- [x] 确认恢复不会修改用户 `.git`
- [x] 写 `RestoreSnapshotTest`（当前合并在 `SideGitManagerTest`）

### Day 5：运行时接入

- [x] ReAct 普通输入接入 pre/post snapshot
- [x] `/plan <任务>` 接入 turn 级快照
- [x] `/team <任务>` 接入 turn 级快照
- [x] TUI `TuiSessionController` 接入同一 wrapper
- [x] 快照失败不影响 Agent 主流程

### Day 6：命令与工具

- [x] `/snapshot`
- [x] `/snapshot status`
- [x] `/snapshot clean`
- [x] `/restore <N>`
- [x] `revert_turn` 工具
- [x] `revert_turn` 进入 HITL / AuditLog 危险工具集

### Day 7：文档与验收

- [x] 更新 `AGENTS.md`
- [x] 更新 `README.md`
- [x] 更新 `.env.example`
- [x] 更新 `ROADMAP.md` 进度说明
- [x] 写手工验收记录

## 测试计划

必跑测试：

```bash
mvn test -Dtest=SnapshotConfigTest,SideGitManagerTest,SnapshotStoreTest,RestoreSnapshotTest
mvn test -Dtest=MainInputNormalizationTest,CliCommandParserTest,ToolRegistryTest
mvn test -Pquick
mvn test
```

本轮已执行：

```bash
mvn test -Dtest=SideGitManagerTest,CliCommandParserTest,ApprovalPolicyTest
mvn test -Dtest=SideGitManagerTest,CliCommandParserTest,ApprovalPolicyTest,ToolRegistryTest
mvn test -Dtest=SideGitManagerTest,ToolRegistryTest
mvn test -Pquick
mvn test
mvn -q clean package -DskipTests
```

结果：以上命令均通过；全量 `mvn test` 为 596 个测试，0 failures / 0 errors。

手工验收：

1. 新建临时文件，执行一轮 Agent 修改，确认 pre/post 快照出现。
2. 修改已有文件，执行 `/restore 1`，确认文件恢复到 turn 开始前。
3. 确认项目 `.git` 的 branch / HEAD / index 没变化。
4. `target/`、`.git/`、`node_modules/` 不进入 side history。
5. `revert_turn` 在 HITL 开启时会弹审批。
6. TUI 模式下执行一轮普通任务，也会生成快照。

## 验收标准

- 自动快照默认开启，但失败不阻塞 Agent。
- `/restore 1` 能恢复最近 turn 开始前的工作区文件。
- 用户项目 `.git` 不被写入、不被 reset、不被 checkout。
- `maxSnapshots` 对列表/定位上限生效；历史压缩留作后续增强。
- ReAct / Plan / Team / TUI 四条入口行为一致。
