# 第 11 期开发任务：MCP 高级能力（resources 双轨 + prompts 查看 + 被动通知）

> 本期范围已按当前实现决策收窄：**OAuth 先不做，sampling 先不做**。
> 运行中 `/cancel` 已纳入本期交付；MCP server 自动重启、OAuth、sampling 统一留到后续 MCP 增强期，不算本期 DoD。

## 1. 目标与产出物

在第 10 期 MCP 协议核心（stdio + Streamable HTTP + tools/list + tools/call）的基础上，补齐最先能产生用户价值、且不依赖鉴权与反向 LLM 调用的高级能力：

- resources 双轨：
  - 工具层：支持 resources 的 server 自动注册 `mcp__{server}__list_resources` / `mcp__{server}__read_resource`
  - 用户输入层：支持 `@server:protocol://path`，提交给 Agent 前展开为 `<resource>` 内联块
- prompts 查看：`/mcp prompts <server>` 只展示 server 暴露的 prompt 模板，不注入对话流
- 被动通知：
  - `notifications/tools/list_changed` 触发工具列表全量替换
  - `notifications/resources/list_changed` / `notifications/resources/updated` 触发 resource cache 失效
- 运行中取消：任务执行期间输入 `/cancel` 并回车，请求取消当前 Agent run
- CLI：
  - `/mcp resources <server>`
  - `/mcp prompts <server>`
  - `/cancel`
- Banner 升级到 `v11.0.0`，标语为 `MCP-Native Agent CLI`

## 2. 明确不做

- OAuth 2.0 / PKCE / token 持久化 / 401 refresh
- `sampling/createMessage` 反向 LLM 调用
- tool-enabled sampling
- MCP server crash 自动重启
- prompts 加载到 `/plan` / `/team` / 普通对话
- resources 自动注入 system prompt（留给第 12 期长上下文工程）
- health ping / heartbeat
- progress notification UI 展示

## 3. 模块拆分

```text
src/main/java/com/paicli/mcp/
├── resources/
│   ├── McpResourceDescriptor.java
│   ├── McpResourceContent.java
│   ├── McpResourceCache.java
│   └── McpResourceTool.java
├── mention/
│   ├── AtMentionParser.java
│   ├── AtMentionExpander.java
│   └── AtMentionCompleter.java
└── notifications/
    └── NotificationRouter.java
```

集成点：

- `McpClient`：新增 `listResources()` / `readResource(uri)` / `subscribeResource(uri)` / `listPrompts()` / capability 判断
- `McpServerManager`：启动时根据 server capabilities 注册 resources 虚拟工具；处理 resources/prompts CLI；注册通知路由
- `ToolRegistry`：新增 `replaceMcpToolsForServer(...)`，用于 `tools/list_changed` 原子替换
- `Main`：普通输入启用 `AtMentionCompleter`；Agent 执行前通过 `AtMentionExpander` 展开用户显式 resource 引用
- `Main`：Agent run 放入后台 runner，前台监听 `/cancel`；取消时设置 `CancellationToken` 并 interrupt runner
- `CliCommandParser`：新增 `MCP_RESOURCES` / `MCP_PROMPTS` / `CANCEL`
- `AuditLog`：新增 `approver=mention`，记录用户显式 @-mention 读取 resource
- `CancellationContext`：运行级取消上下文，供 ReAct / Plan / Team / 工具批次协同检查

## 4. 用户行为

### resources 工具层

如果 MCP server 在 `initialize` 返回的 capabilities 中声明 `resources`，PaiCLI 会：

1. 调一次 `resources/list` 建立候选缓存
2. 注册两个虚拟工具：
   - `mcp__{server}__list_resources`
   - `mcp__{server}__read_resource`
3. 这两个虚拟工具与普通 MCP 工具一样进入 ToolRegistry，受 HITL 和 AuditLog 管理

### @-mention 输入层

用户可以在普通任务中显式引用 resource：

```text
帮我看下 @filesystem:file://README.md 这份文档
```

提交给 Agent 前会展开为：

```xml
<resource server="filesystem" uri="file://README.md" mimeType="text/markdown">
...
</resource>
```

`@-mention` 只在用户输入里识别，不识别模型输出。语法为：

```text
@([a-zA-Z][\w-]*):([a-z]+)://([^\s@]+)
```

Plan / Team 的 raw-mode 单键交互不接 autocomplete，避免干扰 `ESC` / `Ctrl+O` 等输入路径。

### prompts 查看

```text
/mcp prompts filesystem
```

只展示 `prompts/list` 返回的 prompt 名称、标题和描述，不执行 `prompts/get`，也不把 prompt 注入对话。

### 运行中取消

任务运行期间，终端会提示：

```text
运行中可输入 /cancel 并回车取消当前任务。
```

用户输入 `/cancel` 后，PaiCLI 会设置当前运行的 `CancellationToken`，并中断后台 runner。ReAct、Plan-and-Execute、Multi-Agent 编排、工具批量执行以及 `execute_command` 会在边界处检查取消信号；如果底层 LLM HTTP 流式调用无法立即响应 Java interrupt，取消属于 best-effort，但后续工具执行不会继续推进。

## 5. 测试证据

本期新增或扩展的测试覆盖：

- `McpResourceCacheTest`
- `AtMentionParserTest`
- `AtMentionExpanderTest`
- `AtMentionCompleterTest`
- `NotificationRouterTest`
- `McpClientTest`
- `McpToolRegistrationTest`
- `CliCommandParserTest`
- `MainInputNormalizationTest`

当前验证命令：

```bash
mvn test
```

通过结果：`336 tests, 0 failures, 0 errors, 0 skipped`。

## 6. 后续增强入口

后续如果继续补完整 MCP 高级能力，建议拆成独立期次：

1. OAuth：Authorization Code + PKCE、loopback callback、token store chmod 600、401 refresh
2. Sampling：`JsonRpcClient.onRequest`、强制 HITL、预算控制、递归深度限制
3. Cancel 增强：pending MCP request cancelled notification、不可中断 HTTP 调用的更细粒度协作取消
4. Recovery：stdio EOF / HTTP 连接失败触发指数退避重启
