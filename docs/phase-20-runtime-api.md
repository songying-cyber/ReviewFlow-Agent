# 第 20 期：异步后台任务 + Runtime API

> 当前状态：MVP 已落地。第 20 期补齐无头与后台执行入口；第 21 期 图片输入 已独立完成，不依赖本期 API。

## 已交付

### 后台任务

- `DurableTaskManager`：SQLite 持久化任务队列 + durable workflow state
- 默认数据库：`~/.paicli/tasks/tasks.db`
- 生命周期：
  - `enqueued`
  - `running`
  - `pause_requested`
  - `paused`
  - `cancel_requested`
  - `completed`
  - `failed`
  - `canceled`
  - `compensating`
  - `compensated`
- Worker Pool：默认 2 个后台 worker，可用 `PAICLI_TASK_WORKERS` 或 `-Dpaicli.task.workers` 覆盖
- 任务 State 保存在 `runtime_tasks`；Agent checkpoint 保存在 `runtime_checkpoints`；写工具副作用保存在 `runtime_tool_executions`
- ReAct 后台任务会在 LLM 前、LLM 后工具前、工具后和完成边界写 checkpoint
- `TaskQueue` 是可替换的 MQ 唤醒层，默认 `LocalTaskQueue` 使用进程内阻塞队列；`PAICLI_TASK_QUEUE=redis` 时使用 Redis list（`RPUSH` / `BLPOP`），默认 URL `redis://localhost:6379/0`、key `paicli:tasks:ready`；消息只携带 taskId，允许重复或过期，worker 收到消息后必须再次 DB claim 成功才执行
- 内置 scheduler 周期性扫描 `enqueued` 和过期 lease 恢复后的任务并投递 taskId；`enqueue` / `resume` / `retry` 也会立即投递，降低长任务启动延迟
- Worker claim 会写入 `owner_id` / `lease_until`，运行中由 `paicli-task-heartbeat` 定期续租；SQLite 连接启用 `busy_timeout` / WAL 以降低多实例写锁冲突；checkpoint 与 worker 终态写回都带 owner/lease fencing，owner 丢失后当前 worker 不能继续推进或写终态
- 进程启动时只把过期或缺失 lease 的 `running` / `compensating` 任务恢复为 `enqueued`，活跃 lease 不会被新实例抢走；`pause_requested` 恢复为 `paused`，`cancel_requested` 恢复为 `canceled`
- 后台 ReAct 的写工具通过 `DurableToolRegistry` 记录幂等键；`write_file` / `create_project` 保存 Side-Git 快照和文件 hash，恢复或 retry 时相同工具调用回放旧结果，避免重复写入
- `write_file` / `create_project` 成功后会记录 `restore_snapshot` 补偿信息；`execute_command` 默认只记录副作用，不自动补偿
- CLI 命令：
  - `/task` 或 `/task list [N]`
  - `/task add <任务内容>`
  - `/task cancel <task_id>`
  - `/task pause <task_id>`
  - `/task resume <task_id>`
  - `/task retry <task_id>`
  - `/task compensate <task_id>`
  - `/task log <task_id>`

### Runtime API

实现位于 `src/main/java/com/paicli/runtime/api/`，使用 JDK 内置 `HttpServer`，不引入 Spring / Javalin。

启动：

```bash
PAICLI_RUNTIME_API_KEY=your_local_api_key \
java -jar target/paicli-1.0-SNAPSHOT.jar serve --http --port 8080
```

安全策略：

- 仅监听 `127.0.0.1`
- 必须配置 `PAICLI_RUNTIME_API_KEY` 或 `-Dpaicli.runtime.api.key`
- 请求头支持：
  - `Authorization: Bearer <key>`
  - `X-PaiCLI-API-Key: <key>`

端点：

- `POST /v1/threads`：创建 thread
- `POST /v1/threads/{id}/turns`：提交一轮 Agent 输入，异步执行
- `GET /v1/threads/{id}/events`：以 SSE 格式回放事件

事件类型：

- `thread.created`
- `turn.started`
- `message.delta`
- `turn.completed`
- `turn.failed`

## 当前边界

- Runtime API MVP 是事件回放式 SSE，不做长连接持续阻塞推送
- 后台任务 runner 使用 headless ReAct Agent，不复用交互式 TUI 的 HITL 输入
- 后台任务取消通过持久 `cancel_requested`、`CancellationToken` 和线程中断协作实现；正在进行的远端 LLM HTTP 调用能否立即停止取决于底层 client 边界
- pause 是安全边界暂停：如果任务正卡在远端 LLM 或长命令里，需要等下一次 checkpoint 边界才会进入 `paused`
- 当前 durable workflow MVP 先覆盖 `/task` 后台 ReAct 路径；交互式 ReAct、Plan DAG 和 Runtime API thread/turn 仍保留原执行语义
- Runtime API 当前不模拟完整 OpenAI Assistants API schema，只保留兼容方向的 threads / turns / events 主路径

## 验证

```bash
mvn test -Dtest=RedisTaskQueueIntegrationTest,TaskQueueFactoryTest,DurableTaskManagerTest,RuntimeApiServerTest,CliCommandParserTest
```

建议回归：

```bash
mvn test -Pquick
mvn test
mvn -q clean package -DskipTests
PAICLI_RUNTIME_API_KEY=test java -jar target/paicli-1.0-SNAPSHOT.jar serve --http --port 0
```
