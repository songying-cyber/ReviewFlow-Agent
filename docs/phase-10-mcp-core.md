# 第 10 期开发任务：MCP 协议核心（stdio + Streamable HTTP，默认开启）

> 这份文档是给执行 Agent 的开发任务说明书，自包含、可直接照着推进。
>
> **开工前必读**：
> 1. 仓库根的 `AGENTS.md`（仓库规则、文档联动硬规则、运行前提）
> 2. 仓库根的 `ROADMAP.md`（确认第 10 期目标与边界）
> 3. 现有的 `src/main/java/com/paicli/tool/ToolRegistry.java`、`src/main/java/com/paicli/hitl/HitlToolRegistry.java`、`src/main/java/com/paicli/policy/AuditLog.java` —— 你要集成进这三处
>
> **执行原则**：
> - 严格按 Day 1 → Day 6 顺序推进，每日 `mvn test` 全绿才进入下一日
> - 范围严格守住「明确不做」清单，越界请求务必回到上游确认而不是擅自扩展
> - 提交按 AGENTS.md 的硬规则联动文档；commit 前**不要**擅自 push
> - 改动行为时同步更新 `AGENTS.md` / `README.md` / `ROADMAP.md` / `.env.example`

---

## 1. 目标与产出物

让 PaiCLI 接入 MCP 生态：stdio 子进程 server 与 Streamable HTTP 远程 server 都能用，工具自动注册到 `ToolRegistry`，与 HITL / AuditLog 协同。**默认开启**。

最终交付：

- `com.paicli.mcp` 包（4 个子包，约 15 个类、~1500 行代码）
- 5 个新 CLI 命令：`/mcp` / `/mcp restart <name>` / `/mcp logs <name>` / `/mcp disable <name>` / `/mcp enable <name>`
- 配置文件格式（与 Claude Code `claude_desktop_config.json` 兼容）
- 默认配置 4 个 demo server（3 个 stdio + 1 个远程）
- 单测覆盖核心组件，零外部网络 / 零真实 npm 依赖
- 文档全部联动（AGENTS.md / README.md / ROADMAP.md / .env.example）

---

## 2. 模块拆分

```
src/main/java/com/paicli/mcp/
├── McpClient.java              门面，对外暴露 list/call
├── McpServerManager.java       多 server 生命周期 + JVM shutdown hook
├── McpServer.java              单 server 运行态（status/tools/transport）
├── McpToolBridge.java          MCP 工具 ↔ ToolRegistry 适配
├── McpServerStatus.java        枚举：STARTING / READY / DISABLED / ERROR
├── jsonrpc/
│   ├── JsonRpcClient.java      请求/响应配对、通知路由、超时、错误码
│   ├── JsonRpcMessage.java     records: Request / Response / Notification / Error
│   └── JsonRpcException.java
├── transport/
│   ├── McpTransport.java       接口：send / onReceive / close
│   ├── StdioTransport.java     ProcessBuilder + 三流管理
│   └── StreamableHttpTransport.java   OkHttp + SSE upgrade + session ID
├── protocol/
│   ├── McpInitializeRequest.java / McpInitializeResult.java
│   ├── McpCapabilities.java
│   ├── McpToolDescriptor.java
│   ├── McpCallToolRequest.java / McpCallToolResult.java
│   └── McpContent.java         content 数组 union 类型
└── config/
    ├── McpConfigLoader.java    读两层配置 + 环境变量替换
    ├── McpConfigFile.java      整个配置文件 record
    └── McpServerConfig.java    单 server 配置 record（command / url 二选一）
```

测试 mirror 这个结构放到 `src/test/java/com/paicli/mcp/`。

---

## 3. 关键设计决策（务必遵守）

### 3.1 工具命名空间：`mcp__{server}__{tool}`

跟 Claude Code 对齐，完整 `mcp__` 前缀让 LLM 一眼区分本地工具 vs MCP 工具。同 server 内同名工具直接报错，要求用户在配置里 alias。

### 3.2 JSON-RPC 实现

- 复用 Jackson（已在 `pom.xml`）
- 请求 ID 用 `AtomicLong` 自增，**不支持** string ID（规范允许，简化）
- 配对用 `ConcurrentHashMap<Long, CompletableFuture<JsonNode>>`
- 通知（无 id 的请求）单独路由到 `Consumer<JsonNode>` listener 列表
- 每请求独立超时（默认 60s，与 `execute_command` 对齐）
- 标准错误码：`-32700/parse` `-32600/invalid_request` `-32601/method_not_found` `-32602/invalid_params` `-32603/internal_error`

### 3.3 stdio transport 进程管理（最容易踩坑的地方）

- ProcessBuilder + 三流：
  - **stdin**：业务线程同步写（`BufferedWriter` + `flush()`）
  - **stdout**：单独 daemon thread 读，newline-delimited JSON
  - **stderr**：必须单独 drain！不 drain 会让子进程 OS 缓冲填满后**阻塞死锁**
- 协议格式：每行一条 JSON-RPC 消息，UTF-8
- stderr 输出存环形 buffer（最近 200 行），`/mcp logs` 命令查看
- 关闭顺序：发 `shutdown` 通知 → 等 1s → `destroy()` → 等 2s → `destroyForcibly()`
- `Runtime.addShutdownHook` 兜底，避免僵尸进程

### 3.4 Streamable HTTP transport（2025 年 3 月新规范）

- 单 endpoint：客户端 POST → 服务端要么直接返回 JSON、要么 SSE 升级流式响应
- session ID 通过响应 header `Mcp-Session-Id` 拿到，后续请求带上
- 协议版本 `MCP-Protocol-Version` header 协商
- 复用 OkHttp（已在 `pom.xml`）
- 关闭：DELETE 请求释放 session

### 3.5 工具 schema 清洗

MCP server 返回的 `inputSchema` 是 JSON Schema draft-07 子集，但 GLM-5.1 / DeepSeek V4 不一定全支持。需要清洗：

- 删 `$schema` `$id` `$ref`
- 嵌套 `anyOf` / `oneOf` 降级为 `type: object` + 描述拼接
- 描述字段超过 1000 字符截断 + `…`

### 3.6 tools/call 结果处理

```json
{"content": [{"type": "text", "text": "..."}], "isError": false}
```

- 第一版只处理 `text` 类型，多个 text item 用 `\n\n` 拼接
- `image` / `resource` 类型 → fallback：`"[此工具返回了 {type}，请向用户描述结果]"`
- `isError: true` → 把 content 当成错误消息返回给 LLM，由 LLM 决定下一步

### 3.7 配置文件格式（与 Claude Code 兼容）

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "${PROJECT_DIR}"],
      "env": {"NODE_ENV": "production"}
    },
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
    }
  }
}
```

- 有 `command` 字段 → stdio
- 有 `url` 字段 → Streamable HTTP
- 用户级 `~/.paicli/mcp.json` + 项目级 `.paicli/mcp.json`，**项目级覆盖用户级**（按 server 名 merge）
- `${VAR}` 在 args / env / headers 里展开（环境变量 + 内置变量 `${PROJECT_DIR}` `${HOME}`）
- 缺失的 env 变量**直接报错**，让用户知道哪里没配好（不要静默保留 `${VAR}`）

### 3.8 server 生命周期

- 启动：`McpConfigLoader.load()` → `McpServerManager.startAll()` 用 `CompletableFuture.allOf` 并行 start
- 单 server 启动失败不阻塞其他 server，只标 `status: ERROR`
- initialize 握手超时 30s
- 工具注册：每个 server initialize OK → 调 `tools/list` → 转 `LlmClient.Tool` → 注入 `ToolRegistry`
- server 崩溃（stdout 流断开）：第一版**不自动重启**，标 `status: ERROR`，工具从 ToolRegistry 移除；用户 `/mcp restart <name>` 手动恢复
- HTTP server 连续 3 次请求失败：标 ERROR

### 3.9 HITL 默认审批

`com.paicli.hitl.ApprovalPolicy.requiresApproval` 改为：

```java
return DANGEROUS_TOOLS.contains(toolName) || toolName.startsWith("mcp__");
```

所有 MCP 工具默认走 HITL，不区分 server。审批弹窗里显示 `(MCP server: filesystem)` 这种额外信息，让用户知道工具来源。

### 3.10 AuditLog 集成

`com.paicli.tool.ToolRegistry.AUDIT_TOOLS` 扩展为「危险工具集 + 任何 `mcp__` 前缀工具」。审计字段保持不变；`tool` 字段就是 `mcp__filesystem__read_file`，过滤时按前缀。

### 3.11 args 脱敏（必须做）

audit 写入前对 args 做脱敏，避免 token / 凭证泄漏：

- 匹配 `Bearer .+` → `Bearer ***`
- 匹配 `(token|key|password|secret|authorization)["\s:=]+["']?[\w\-]+` → 字段值替换 `***`
- 这条逻辑放 `AuditLog.AuditEntry.truncate` 附近，新增 `sanitize` 方法

### 3.12 启动 / 用户体验输出

```
✅ 已加载模型: glm-5.1 (zhipu)
🔌 启动 MCP server（4 个）...
   ✓ filesystem    stdio   11 工具    1.2s
   ✓ fetch         stdio    1 工具    0.8s
   ✓ git           stdio   14 工具    1.5s
   ✗ remote-demo   http     —         启动失败: 401 Unauthorized
   3/4 就绪，共 26 个 MCP 工具

🔄 使用 ReAct 模式
```

`/mcp` 输出：

```
🔌 MCP Servers
  filesystem    ● ready    stdio   11 tools   uptime 5m   pid 12345
  fetch         ● ready    stdio    1 tool    uptime 5m   pid 12346
  git           ● ready    stdio   14 tools   uptime 5m   pid 12347
  remote-demo   ✗ error    http     —         401 Unauthorized
```

---

## 4. 与现有架构的集成（要修改的文件清单）

**新增**：
- `src/main/java/com/paicli/mcp/` 整个包
- `src/test/java/com/paicli/mcp/` 测试

**修改**：
- `src/main/java/com/paicli/tool/ToolRegistry.java`：新增 `registerMcpTool(McpToolDescriptor)` / `unregisterMcpTool(String toolName)`，`executeTool` 路由到 `McpToolBridge`；`AUDIT_TOOLS` 判断扩展为含 `mcp__` 前缀
- `src/main/java/com/paicli/hitl/ApprovalPolicy.java`：`requiresApproval` 加 `mcp__` 前缀判断
- `src/main/java/com/paicli/hitl/TerminalHitlHandler.java`：弹窗加 server 信息行
- `src/main/java/com/paicli/policy/AuditLog.java`：新增 args 脱敏（regex 替换 token / Bearer / authorization）
- `src/main/java/com/paicli/cli/Main.java`：启动时 `McpServerManager.startAll`；shutdown hook；CLI 命令分支；启动 banner 后打印 MCP server 状态
- `src/main/java/com/paicli/cli/CliCommandParser.java`：5 个新 `MCP_*` 命令枚举与 parse 分支
- `src/main/java/com/paicli/agent/Agent.java`：系统提示词加 MCP 工具说明
- `src/main/java/com/paicli/agent/PlanExecuteAgent.java`：同上
- `src/main/java/com/paicli/agent/SubAgent.java`：同上
- `pom.xml`：评估下来不需要新依赖（Jackson + OkHttp 已有），如果中途发现需要请回到上游确认

**联动文档**（按 AGENTS.md 5.x 硬规则）：
- `AGENTS.md`：项目快照里把第 10 期标为已完成；新增「11. MCP 协议接入」段；硬规则加一条「改 MCP 要联动」；版本号根据是否升 v10 决定（见 §10）
- `README.md`：新增「第十期」段、命令列表加 5 个 MCP 命令、工具描述更新
- `ROADMAP.md`：第 10 期标 ✅；末尾状态行更新为「下一步进入第 11 期 MCP 高级能力」
- `.env.example`：新增 MCP 相关的环境变量示例（如 `REMOTE_TOKEN`）

---

## 5. CLI 命令规约

| 命令 | 行为 | payload |
|---|---|---|
| `/mcp` | 列出所有 server：name / status / transport / tools / uptime / pid | 无 |
| `/mcp restart <name>` | 重启单个 server | name |
| `/mcp logs <name>` | 显示 stderr 环形 buffer 最近 200 行 | name |
| `/mcp disable <name>` | 运行时禁用，从 ToolRegistry 移除工具 | name |
| `/mcp enable <name>` | 运行时启用 | name |

**不**实现 `/mcp add` / `/mcp remove`：配置编辑通过文件 + 重启 PaiCLI。

`CliCommandParser.CommandType` 新增：`MCP_LIST` / `MCP_RESTART` / `MCP_LOGS` / `MCP_DISABLE` / `MCP_ENABLE`。

---

## 6. 风险点

### 6.1 已知必踩的坑

1. **stdio buffer 死锁**：stderr 不 drain 会让子进程阻塞。必须用单独 daemon thread 持续读。
2. **GLM/DeepSeek 对 JSON Schema 的容忍度差**：MCP server 返回的 schema 可能用了 `$ref` / 深度 `anyOf` / `additionalProperties` 等，模型直接报错。schema 清洗（§3.5）必须做，且要写测试覆盖至少 3 种坏 schema。
3. **冷启动慢**：`npx` 第一次拉包要 5–30s。第一次启动慢是预期的，但要给进度提示（`✓` 前打印 `…` 和 server 名）。
4. **环境变量泄漏到 audit**：`Authorization: Bearer xxx` 这种 header 不能进 audit args。脱敏（§3.11）必须做。
5. **Streamable HTTP 规范分歧**：2025 年 3 月规范，server 实现不一定都遵守。要做兼容降级：先尝试 SSE，失败回退到普通 POST。
6. **Windows 路径 / 命令**：`npx.cmd` vs `npx`、`/` vs `\`。第一版只测 macOS / Linux，Windows 留 follow-up。在 README 里明示。

### 6.2 已决策（不要再讨论）

- 环境变量缺失 → **报错**，不静默保留
- server 启动失败 → **不阻塞** PaiCLI 启动，标 ERROR 跳过
- 工具描述截断长度：1000 字符
- MCP 工具调用日志：写到 `~/.paicli/logs/mcp/<server>.log`（按天滚动，复用 logback 风格但用单独 logger）

---

## 7. 开发顺序（5–6 天工作量）

每天结束前 `mvn test` 必须全绿才进入下一天。

### Day 1：JSON-RPC 基础（约 250 行）

**产出**：
- `jsonrpc/JsonRpcMessage.java`：4 个 record（Request / Response / Notification / Error）
- `jsonrpc/JsonRpcClient.java`：发送 / 接收配对 / 通知路由 / 超时
- `jsonrpc/JsonRpcException.java`

**测试**：`JsonRpcClientTest`，用 in-memory loopback transport 至少 8 个用例：
- 单请求 / 响应配对
- 多请求并发不串号
- 错误响应正确抛 JsonRpcException
- 通知不阻塞响应配对
- 超时取消 future
- close 后请求立即失败
- 接收 parse error 不崩溃
- 服务端发来未知 method 通知（应忽略，不抛）

### Day 2：stdio transport（约 200 行）

**产出**：
- `transport/McpTransport.java`：接口
- `transport/StdioTransport.java`：ProcessBuilder + 三流管理 + 环形 stderr buffer + 优雅关闭
- shutdown hook 注册

**测试**：用 `cat` / `echo` 子进程做 mock：
- 启动 / 关闭 OK
- stderr 持续输出不阻塞 stdout
- 进程崩溃后 onClose 回调被调用
- destroy 强制关闭超时进程
- stderr 环形 buffer 截断（写入 300 行只保留最后 200 行）

### Day 3：MCP 协议层（约 300 行）

**产出**：
- `protocol/` 下 records
- `McpClient.java`：connect / initialize / listTools / callTool
- `McpServer.java`：单 server 运行态
- `McpServerStatus.java` 枚举
- 工具 schema 清洗逻辑（删 `$ref` / 降级 `anyOf` / 截断 description）

**测试**：用 mock JsonRpcClient 验证消息序列：
- initialize 握手发出正确的 capabilities
- tools/list 返回多工具
- tools/call 成功返回 text content
- tools/call 返回 isError 时正确包装
- schema 清洗：删 `$ref`、降级 `anyOf`、截断长 description

### Day 4：Streamable HTTP transport（约 250 行）

**产出**：
- `transport/StreamableHttpTransport.java`：OkHttp + SSE 升级 + session ID 管理 + DELETE 释放

**测试**：MockWebServer：
- 普通 JSON 响应
- SSE 流式响应
- session ID 注入后续请求
- DELETE 释放 session
- 连续 3 次失败标 ERROR
- 401 Unauthorized 立即标 ERROR（不重试）

### Day 5：配置 + 多 server 管理 + 集成（约 400 行）

**产出**：
- `config/McpConfigLoader.java`：两层配置合并 + `${VAR}` 展开 + 缺失变量报错
- `config/McpConfigFile.java` / `config/McpServerConfig.java` records
- `McpServerManager.java`：并行启动 + 状态追踪 + 工具注册 + JVM shutdown hook
- `McpToolBridge.java`：ToolRegistry 集成 + HITL 审批 + AuditLog 写入
- `cli/CliCommandParser.java` 加 5 个新命令
- `cli/Main.java` 启动调用 + 命令分支
- 系统提示词更新（Agent / PlanExecuteAgent / SubAgent）
- `ApprovalPolicy.requiresApproval` 加 `mcp__` 前缀判断
- `AuditLog` args 脱敏 regex

**测试**：
- `McpConfigLoaderTest`：临时文件测两层 merge / `${VAR}` 展开 / 缺失变量报错
- `McpServerManagerTest`：mock McpClient，测并行启动 / 单个失败不阻塞 / 工具注册 / shutdown hook
- `McpToolBridgeTest`：测工具调用路由 / 审批弹窗带 server 名 / audit 字段含 `mcp__` 前缀
- `ApprovalPolicyTest` 追加：`mcp__xxx` 前缀的工具应 requireApproval
- `AuditLogTest` 追加：脱敏测试（Bearer / token / password 都被替换）
- `CliCommandParserTest` 追加：5 个 MCP 命令的解析

### Day 6：联调 + 边界 + 文档

**产出**：
- 三个 stdio demo server 真实启动验证：filesystem / fetch / git
- 一个 Streamable HTTP demo（在 README 给出公开 server URL，或自起一个 minimal Python MCP server）
- 错误路径手测清单（见 §9）
- 文档：
  - `AGENTS.md`：新增「11. MCP 协议接入」段、5.5 硬规则、命令入口、仓库结构图、测试列表
  - `README.md`：新增第十期段、Banner（如果升 v10）、命令列表、工具描述
  - `ROADMAP.md`：第 10 期标 ✅、末尾状态行
  - `.env.example`：MCP 相关变量示例

---

## 8. 测试策略

- **单测**：每个组件独立、零外部依赖（用 in-memory transport / cat 子进程 / MockWebServer）
- **集成测**（可选，CI 默认不跑）：标记 `@EnabledIfEnvironmentVariable("MCP_INTEGRATION_TEST", "true")`，跑真实 filesystem / fetch server
- **手测清单**（Day 6 必跑，结果写到 commit description 或 PR body）：
  1. 启动 4 个 server，3 stdio + 1 远程，远程认证失败的也要跑通
  2. `/mcp` 命令显示正确状态
  3. `/mcp logs filesystem` 看 stderr
  4. 让 LLM 调用一个 mcp 工具，HITL 弹窗显示 server 名
  5. `/mcp disable filesystem`，再让 LLM 调用同一工具，应找不到
  6. `/mcp enable filesystem` + `/mcp restart filesystem`
  7. 配置故意写错 `${MISSING_VAR}`，启动应清晰报错
  8. 配置故意缺 `command` 和 `url`，应清晰报错
  9. 让 LLM 调用一个慢工具（>60s），应正确超时
  10. Ctrl+C 退出，所有子进程应被 destroy（`ps aux | grep mcp-server` 看不到残留）

---

## 9. 明确不做（留给第 11 期）

第一版**不实现**这些，但 `McpClient` 接口设计要为它们留好扩展位（capabilities 协商已经包含这些 flag，只是不实现 handler）：

- `resources/list` / `resources/read` / `resources/subscribe`
- `prompts/list` / `prompts/get`
- `sampling/createMessage`（server 反向调用 LLM）
- `notifications/tools/list_changed` / `notifications/cancelled`
- OAuth 2.0 鉴权流程（第一版只支持 Bearer + 自定义 header）
- 自动重启 + 退避

如果实现过程中发现某条不做的功能其实绕不过去，**先停下来回到上游确认**，不要擅自扩展范围。

---

## 10. Banner 版本号

完成第 10 期后，把 `Main.java` 的 `VERSION` 升到 `10.0.0`，banner 标语改成 `MCP-Enabled Agent CLI`（或其他你认为更好的，但要在 commit 中说明理由）。

---

## 11. 完成判定（DoD）

只有以下全部满足才算第 10 期完成：

- [ ] 所有 §2 列出的类都已创建并有完整实现
- [ ] §4 列出的所有现有文件都按要求修改
- [ ] §3.11 args 脱敏已实现并有测试
- [ ] 单测覆盖：JsonRpcClient ≥ 8 用例、StdioTransport ≥ 5 用例、StreamableHttpTransport ≥ 6 用例、McpClient ≥ 5 用例、McpConfigLoader ≥ 4 用例、McpServerManager ≥ 4 用例、McpToolBridge ≥ 3 用例、ApprovalPolicyTest 与 AuditLogTest 各追加 ≥ 1 用例
- [ ] `mvn test` 全绿（包含原有 261 用例）
- [ ] §8 手测清单 10 条全部跑过且 OK
- [ ] §4 所有文档联动完成
- [ ] Banner 升 v10.0.0
- [ ] commit message 清楚交代「这是第 10 期 MCP 协议核心」+ 「测试统计」+ Co-Authored-By

---

## 12. 提交规约

- **不要**自行 `git push`
- 一次性 commit，message 用 heredoc 格式（参考 AGENTS.md 与最近的 commit `f90d9f5`）
- commit 前 `git status` 确认无误改动 / 无敏感信息
- commit message 标题 ≤ 70 字符，正文按层次列改动 + 测试统计
- 末尾加：

```
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

---

## 13. Pre-flight 决策（已替你决定，不要再讨论）

| 问题 | 决策 |
|---|---|
| Streamable HTTP demo server 用哪个 | Day 6 自己找一个公开免费的；找不到就自起 Python minimal MCP server，写到 README |
| Windows 支持 | 第一版只测 macOS + Linux，README 明示 Windows 留 follow-up |
| 配置文件位置 | `~/.paicli/mcp.json`（用户级）+ `.paicli/mcp.json`（项目级，可入 git） |
| 是否引入新依赖 | 不引入。如果中途发现需要，停下来回上游确认 |
| Banner 升级 | 升 v10.0.0，标语改 `MCP-Enabled Agent CLI` |
| 工具命名前缀 | `mcp__{server}__{tool}` 完整前缀，跟 Claude Code 对齐 |
| HITL 默认对 MCP 工具开 | 开 |
| MCP 工具是否一律审计 | 是 |
| 自动重启 | 不做 |
| sampling | 不做（第 11 期） |

---

如果有任何疑问，回到上游问，不要自行推断。祝顺利。
