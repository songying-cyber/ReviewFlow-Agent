# 第 14 期开发任务：CDP 会话复用 + 登录态访问

> **当前状态**：已落地 `/browser` 命令组、Agent 内部 `browser_connect` / `browser_disconnect` / `browser_status` 工具、`chrome-devtools` shared/isolated 运行时切换、敏感页面单步 HITL、shared 模式 `close_page` 保护、浏览器审计 metadata 与对应单测。登录态复用默认走 `chrome-devtools-mcp --autoConnect`，也保留 `/browser connect <port>` 的旧式 `--browser-url` 端口兼容路径。

> 这份文档是给执行 Agent 的开发任务说明书，自包含、可直接照着推进。
>
> **开工前必读**：
> 1. 仓库根 `AGENTS.md`（仓库规则、文档联动硬规则）
> 2. `docs/phase-10-mcp-core.md` / `docs/phase-11-mcp-advanced.md` / `docs/phase-13-chrome-devtools-mcp.md`（已完成，本期在它们之上增量）
> 3. `src/main/java/com/paicli/mcp/McpServerManager.java`（restart / disable / enable 已就绪，本期要补「运行时改 args 后重启」）
> 4. `src/main/java/com/paicli/hitl/`（`approvedAllByTool` / `approvedAllByServer` 已就绪，本期要补「敏感页面强制单步审批」）
> 5. `src/main/java/com/paicli/policy/`（`PathGuard` / `CommandGuard` / `AuditLog` 已就绪，本期新增 `BrowserGuard`、`SensitivePagePolicy`，`AuditEntry` 加可选 `metadata` 字段）
>
> **核心原则**：第 13 期已经把 `chrome-devtools-mcp` 接进来跑通；本期**不写 CDP 协议**——chrome-devtools-mcp 原生支持 `--autoConnect` 连接 Chrome 144+ 的 `chrome://inspect/#remote-debugging` 实例，也支持旧式 `--browser-url=http://127.0.0.1:9222`，PaiCLI 这边只做「运行时切换 server 启动参数 + 登录态安全约束 + UX 引导」。

---

## 1. 目标与产出物

让 Agent 能复用带登录态的调试 Chrome 实例，直接访问 GitHub 私仓、内部系统、邮箱等需要登录态的页面；同时保证「真实登录态」不会被 Agent 滥用：敏感页面强制单步审批、关闭页面强制保护、审计字段记录浏览器模式。

最终交付：

- `chrome-devtools` server 支持两种运行模式 `isolated`（默认）/ `shared`（复用带登录态的调试 Chrome），运行时切换并自动重启
- 新增 CLI 命令组 `/browser`：`status` / `connect` / `disconnect` / `tabs`
- 默认 `--autoConnect` 连接已允许远程调试的本机 Chrome；显式 `/browser connect <port>` 保留 9222 等旧式 CDP 端口探活 + 三平台 Chrome 启动命令引导（macOS / Linux / Windows）
- 敏感页面识别（默认规则 + 用户级 `~/.paicli/sensitive_patterns.txt`）+ 命中后强制单步审批（绕过 `approvedAllByServer`）
- `close_page` 工具硬保护：只允许关闭 PaiCLI 当次会话 `new_page` 出来的 tab，否则抛 `PolicyException`
- `AuditLog.AuditEntry` 新增可选 `metadata` 字段，记录 `browser_mode` / `sensitive` / `target_url`
- `Agent` / `PlanExecuteAgent` / `SubAgent` 三处提示词加「shared 模式 = 真实登录态，按用户视角操作」段落
- 文档联动（AGENTS.md / README.md / ROADMAP.md / .env.example）
- Banner 升 `v14.0.0`，标语 `Session-Aware Browser Agent CLI`

**明确不做**（拆给后续期次）：
- 自动登录（不替用户输账号密码、不集成密码管理器）
- Cookie / localStorage 直接读写工具（chrome-devtools-mcp 自身没暴露，不自补）
- 跨设备 / 跨 PaiCLI 实例的 session 同步
- Cookie 持久化到 PaiCLI 自己的存储
- 多 Chrome profile 并行（同时连两个 Chrome 实例）
- TLS 客户端证书 / mTLS 配置
- 真 multimodal LLM 输入（仍走第 21 期）
- Playwright / Firefox / WebKit 跨浏览器（仍走 chrome-devtools-mcp 专精）

---

## 2. chrome-devtools-mcp 复用模式概况

| 项 | 值 |
|---|---|
| chrome-devtools-mcp 版本 | v0.23.0+（同第 13 期） |
| 默认复用参数 | `--autoConnect` |
| 默认用户侧前置 | Chrome 144+ 打开 `chrome://inspect/#remote-debugging`，勾选 `Allow remote debugging for this browser instance` |
| 旧式兼容参数 | `--browser-url=http://127.0.0.1:9222` |
| 旧式用户侧前置 | 用户先用 `--remote-debugging-port=9222` 启动 Chrome |
| 旧式探活端点 | `GET http://127.0.0.1:9222/json/version`（返回 JSON `{Browser, webSocketDebuggerUrl, ...}`） |
| 连通性表现 | `--autoConnect` 由 chrome-devtools-mcp 自己连接并报错；旧式 `--browser-url` 指向的端口连不上时会启动失败，stderr 报错 |
| 多 Tab 行为 | server 接管已开 Chrome 的所有 tab；`list_pages` 会列出包括用户原有 tab 在内的全部页面 |

**旧式端口路径三平台 Chrome 启动命令**（写进 README）：

```bash
# macOS
open -a "Google Chrome" --args --remote-debugging-port=9222

# Linux
google-chrome --remote-debugging-port=9222

# Windows (PowerShell)
& "C:\Program Files\Google\Chrome\Application\chrome.exe" --remote-debugging-port=9222
```

---

## 3. 关键设计决策（务必遵守）

### 3.1 双轨切换：单 server，args 切换 + 重启

**不**注册两个 server（不要 `chrome-devtools-isolated` + `chrome-devtools-shared` 并存），原因：
- 工具数翻倍（28 → 56），system prompt token 暴涨
- 提示词、HITL 全放行的 server 维度都得重写
- 用户心智复杂

**采用**：`chrome-devtools` server 名不变，args 在 `[--isolated=true]`、`[--autoConnect]` 与旧式 `[--browser-url=<url>]` 间切换，切换时重启该 server。LLM 看到的工具集（`mcp__chrome-devtools__*`）始终一致。

`McpServerManager` 已有 `restart(name)`，但只能用 **当前 config** 重启。本期需新增：

```java
public synchronized String restartWithArgs(String name, List<String> newArgs)
```

实现：把 `McpServer.config().setArgs(newArgs)` 改完后走 `restart(name)` 现有路径。`McpServerConfig` 当前 args 字段如果是不可变 list，需要补一个 setter（保持包内可见，避免外部滥用）。

**默认行为不变**：`~/.paicli/mcp.json` 不存在时仍生成 `--isolated=true`（向后兼容第 13 期用户）。`browser_connect` / `/browser connect` 是运行期动作，不主动改用户 mcp.json 文件，**只在内存中切换 args**；用户重启 PaiCLI 后回到 isolated。这样：
- 默认安全（isolated 仍是默认）
- 运行时按需 connect
- 用户的 mcp.json 不被 PaiCLI 偷偷改动

如果用户希望默认就走 shared，文档里明示：自己改 `~/.paicli/mcp.json` 把 args 改成 `["-y", "chrome-devtools-mcp@latest", "--autoConnect"]`。旧式端口可用 `["-y", "chrome-devtools-mcp@latest", "--browser-url=http://127.0.0.1:9222"]`。

### 3.2 `/browser` CLI 命令组

| 命令 | 行为 |
|---|---|
| `/browser` | 等价 `/browser status` |
| `/browser status` | 显示当前模式（isolated / shared）、target URL（shared 模式下）、autoConnect 引导、旧式 9222 探活结果、`chrome-devtools` server 状态 |
| `/browser connect` | 切换到 shared 模式，默认 `restartWithArgs(chrome-devtools, ["-y", "chrome-devtools-mcp@latest", "--autoConnect"])` |
| `/browser connect <port>` | 旧式 CDP 端口兼容路径。先探活 → 失败给三平台命令 + 退出；成功则 `restartWithArgs(chrome-devtools, ["-y", "chrome-devtools-mcp@latest", "--browser-url=http://127.0.0.1:<port>"])` |
| `/browser disconnect` | 切回 isolated 模式，`restartWithArgs(chrome-devtools, ["-y", "chrome-devtools-mcp@latest", "--isolated=true"])` |
| `/browser tabs` | shared 模式下：调 `list_pages` 列 tab；isolated 模式下：提示「当前为 isolated 模式，没有真实 Chrome tab，可用 `/browser connect`」 |

**重要约束**：
- `connect` / `disconnect` 期间禁止其他 Agent 调用（命令本身 `synchronized` 即可）
- 切换会清空 `chrome-devtools` 的 `approvedAllByServer` 记录（重启后是新一轮信任，旧的全放行不应跨模式延续）—— 在 `BrowserSession.switchMode()` 里调 `hitlHandler.clearApprovedAllForServer("chrome-devtools")`
- 切换会清空 `BrowserSession.agentOpenedTabs`（见 §3.4），新模式下重新累计

**端口验证**：
- 默认 9222
- 用户传入的 `port` 必须是 `1024..65535` 整数，否则报错「端口超出范围」
- 不接受非数字、不接受 host 部分（一句话约定：本期只允许 `127.0.0.1`，不暴露公网/远程 host，避免 SSRF 类风险）

### 3.3 敏感页面识别 + 强制单步审批

**判定时机**：所有 `mcp__chrome-devtools__*` 工具调用前。`BrowserGuard.check(toolName, args, currentMode, currentUrl, sensitivePolicy)`。

**判定输入**：
- `args` 中显式带 `url` 字段（`navigate_page`、`new_page`）
- 否则取 `BrowserSession.lastNavigatedUrl`（最近一次 `navigate_page` / `new_page` 后由 `BrowserGuard` 缓存）
- 拿不到 URL（如刚启动还没 navigate）→ 不视为敏感

**判定规则**（`SensitivePagePolicy`）：
- 默认内置 glob 列表：
  - `*://*/settings*`
  - `*://*/admin*`
  - `*://*/billing*`
  - `*://*/account*`
  - `*://*/security*`
  - `*://*/oauth*`
  - `*://*/auth*`
  - `*://*/2fa*`
  - `*://*/password*`
- 用户级追加：`~/.paicli/sensitive_patterns.txt`，每行一个 glob（`#` 开头注释、空行忽略）。文件不存在视为空
- 匹配走 `java.nio.file.FileSystems.getDefault().getPathMatcher("glob:...")` 不合适（路径匹配语义不对），改用简单 `*` / `?` glob → regex 转换：`*` → `.*`，`?` → `.`，其他元字符 quote
- 大小写不敏感
- 只匹配 URL 本身（含 scheme + host + path + query），不解析 host 域名归属

**命中后效果**（**改写型工具**才升级，**读型工具**不升级以保持 90% 操作体验）：

改写型（命中后强制单步审批，绕过 `approvedAllByTool` / `approvedAllByServer`）：
- `click`
- `drag`
- `fill`
- `fill_form`
- `hover`
- `press_key`
- `type_text`
- `upload_file`
- `handle_dialog`
- `evaluate_script`
- `navigate_page`（包含 url 输入本身就是高敏，敏感 URL 跳转一律审批）
- `new_page`
- `install_extension` / `uninstall_extension` / `reload_extension` / `trigger_extension_action`

读型（不升级，仍受 `approvedAllByServer` 影响）：
- `take_snapshot`
- `take_screenshot`
- `list_pages` / `select_page` / `wait_for`
- `list_console_messages` / `get_console_message`
- `list_network_requests` / `get_network_request`
- `lighthouse_audit`
- `performance_*`
- `emulate` / `resize_page`
- `take_memory_snapshot`
- `list_extensions`
- `close_page`（已经被 §3.4 单独管，不重复）

`HitlToolRegistry` 改造：进入审批前先查 `BrowserGuard.requiresPerCallApproval(...)`，命中则跳过 `isApprovedAllByTool` / `isApprovedAllByServer` 直接弹窗。弹窗信息加一行：
```
⚠️ 检测到敏感页面，本次操作需单独确认（不接受全部放行）
当前 URL: https://github.com/settings/profile
匹配规则: */settings*
```

**isolated 模式下的行为**：判敏感继续生效（防御性），但因为 isolated 没有用户登录态，命中概率低、风险也低。不做模式区分，规则简单一致更可读。

### 3.4 `close_page` 硬保护

`close_page` 在 shared 模式下能关掉用户原有的 Gmail / Slack / 工作 tab，损失大。

**约束**：`BrowserSession` 维护 `agentOpenedTabs: Set<String>`（pageId 或 page index 形式，依 chrome-devtools-mcp 实际返回）。
- `new_page` 工具调用成功后，从返回结果里解析新 page 的标识，记入 `agentOpenedTabs`
- `close_page` 调用前 `BrowserGuard.check()` 校验：目标 pageId 不在 `agentOpenedTabs` 里 → 抛 `PolicyException("close_page 拒绝：该 tab 不是 PaiCLI 本次会话开启的，不允许关闭。如需关闭请用户手动操作。")`
- HITL 全放行无法绕过（PolicyException 在 ToolRegistry 层硬阻断，写 `denyByPolicy` 审计）
- 如果 chrome-devtools-mcp 返回结构里拿不到稳定的 page 标识（解析失败），保守：一律拒绝 `close_page`，提示用户手动操作

**isolated 模式下的行为**：tabs 都是 PaiCLI 自己开的，正常允许 close。判定：
- isolated 模式 → 跳过该硬保护
- shared 模式 → 启用

`BrowserSession` 单例在 §5.1 详细约定。

### 3.5 启动连通性探测 + 三平台 Chrome 启动引导

**启动期**：PaiCLI 启动时，如果 `mcp.json` 里 `chrome-devtools` 配置已经是 `--browser-url=...`（用户自己改的），不在启动期探活——`chrome-devtools-mcp` 自己会失败并写 stderr，`/mcp logs chrome-devtools` 可见。启动期间不阻塞别的 server。

**`/browser connect` 期**：不再用 `/json/version` 探活。新版 Chrome 的 `chrome://inspect/#remote-debugging` 开关面向 `chrome-devtools-mcp --autoConnect`，并不保证旧式 `http://127.0.0.1:9222/json/version` 返回 200。默认路径直接重启 server 为 `--autoConnect`，失败则回滚 args，并提示用户确认 Chrome 144+ 已勾选 remote debugging。

**`/browser connect <port>` 期**：旧式 CDP 端口路径，必须探活。

`BrowserConnectivityCheck.probe(host, port)`：
- 实现：OkHttp `GET http://<host>:<port>/json/version`，timeout 2 秒
- 成功 → 返回 `Probe(connected=true, browserVersion="Chrome/130.0.0.0", webSocketDebuggerUrl="...")`
- 失败 → 返回 `Probe(connected=false, errorMessage="Connection refused")`

`/browser connect <port>` 失败时打印：

```
❌ 无法连接到 Chrome 调试端口 127.0.0.1:9222
   原因: Connection refused

请先用调试端口启动 Chrome：

  macOS:
    open -a "Google Chrome" --args --remote-debugging-port=9222

  Linux:
    google-chrome --remote-debugging-port=9222

  Windows (PowerShell):
    & "C:\Program Files\Google\Chrome\Application\chrome.exe" --remote-debugging-port=9222

启动后再执行 /browser connect 9222 重试。
```

`/browser status` 总是显示当前旧式 9222 探活结果（不阻塞、独立线程或 2s 内同步），但这个结果只代表 `--browser-url` 路径，不代表 `--autoConnect` 不可用。

### 3.6 数据脱敏 / 审计字段升级

**DOM snapshot 不脱敏**：脱敏会破坏内容理解（用户名、订单号本身就是 LLM 要回答的事实），且工作量极大。接受这个边界。代价由 §3.3 敏感页面强制审批兜住——用户能看到将要发什么参数到模型。

**审计字段升级**：

`AuditLog.AuditEntry` 当前是 7 字段 record（`timestamp / tool / args / outcome / reason / approver / durationMs`）。本期新增**可选**第 8 字段：

```java
public record AuditEntry(
        String timestamp,
        String tool,
        String args,
        String outcome,
        String reason,
        String approver,
        long durationMs,
        String metadata   // 可空，JSON 字符串
) { ... }
```

`metadata` 内容是一个 JSON 对象字符串：
```json
{"browser_mode":"shared","sensitive":true,"target_url":"https://github.com/settings/profile","matched_pattern":"*/settings*"}
```

字段约定（按需写入，缺省字段省略）：
- `browser_mode`: `"isolated"` / `"shared"`（仅 chrome-devtools 工具写）
- `sensitive`: `true` / `false`（仅 chrome-devtools 工具写）
- `target_url`: 字符串（仅 chrome-devtools 工具写，且工具 args 中能解析出 URL 时）
- `matched_pattern`: 字符串（仅 sensitive=true 时写）

**兼容性**：
- 所有现有 `AuditEntry.allow/deny/error` 静态工厂保持原签名，内部传 `null` 给 `metadata`
- 新增重载 `allow(tool, args, durationMs, metadata)` 等
- 反序列化老文件（缺 `metadata` 字段）：在 `AuditLog` 类静态块或构造器里给 `mapper` 注册 `DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES = false`、并对 record 用 `@JsonInclude(JsonInclude.Include.NON_NULL)`。Jackson 2.12+ 对 record 缺字段反序列化的容忍度依赖配置，必须**先写一个 `AuditLogTest` 用例验证「读老格式 7 字段 JSONL」不抛异常**，跑通再继续

**`/audit` 命令展示**：metadata 非空时在条目末尾加一行 `↳ metadata: {...}`（截断到 200 字符）。

### 3.7 不破坏第 13 期 server 维度全放行

第 13 期的核心 UX 是「连续浏览器操作选 `a → server` 一次审批走到底」。本期的敏感页面强制单步审批是**例外**，不是替代。

正确语义：
- 用户对 chrome-devtools 选了 `a → server` → 普通浏览器操作（非敏感页面）继续静默放行
- 一旦 navigate 到敏感 URL → 之后的改写型工具（click / fill / ...）每次都弹窗
- 离开敏感 URL（再次 navigate 到非敏感 URL） → 恢复静默放行

实现：`BrowserGuard.requiresPerCallApproval(toolName, args)` 内部判断当前最近 URL 是否敏感 + 工具是否在改写型清单里，两者都满足才返回 true。`HitlToolRegistry.executeTool` 把这个调用插在 `isApprovedAllByTool` / `isApprovedAllByServer` 检查**之前**：

```java
if (BrowserGuard.requiresPerCallApproval(toolName, argsJson)) {
    // 强制弹窗，跳过全放行检查
} else if (handler.isApprovedAllByTool(toolName) || handler.isApprovedAllByServer(serverName)) {
    // 静默放行
} else {
    // 普通审批
}
```

---

## 4. 配置文件改动

### 4.1 `mcp.json` 默认模板

**保持第 13 期不变**：`~/.paicli/mcp.json` 不存在时仍生成 `--isolated=true`。本期不修改默认模板。理由见 §3.1。

### 4.2 `.env.example` 新增

```bash
# ========== 第 14 期：CDP 会话复用 ==========
# Chrome 144+ 推荐在 chrome://inspect/#remote-debugging 勾选 Allow remote debugging，
# 然后让 Agent 自动调用 browser_connect，或手动执行 /browser connect。
# 旧式 CDP HTTP JSON 端口可用 /browser connect 9222；启动命令：
#   macOS:   open -a "Google Chrome" --args --remote-debugging-port=9222
#   Linux:   google-chrome --remote-debugging-port=9222
#   Windows: chrome.exe --remote-debugging-port=9222
# 用户级敏感页面规则：~/.paicli/sensitive_patterns.txt（每行一个 glob，# 开头注释）
# 默认敏感规则：*/settings* */admin* */billing* */account* */security* */oauth* */auth* */2fa* */password*
```

### 4.3 用户级敏感规则文件

`~/.paicli/sensitive_patterns.txt`（**PaiCLI 不主动创建**，只读取）：

```
# 用户自定义敏感页面规则（每行一个 glob）
# 默认内置规则已包含 settings / admin / billing / account / security / oauth / auth / 2fa / password
# 自定义示例：
# *://*.example-bank.com/*
# *://localhost:*/admin*
```

读取约定：
- 不存在 → 视为空
- 存在但读取失败 → 启动期 stderr 警告一行，视为空，不阻塞主流程

---

## 5. 与现有架构的集成点（要修改 / 新增的文件）

### 5.1 新增类

| 文件 | 职责 |
|---|---|
| `src/main/java/com/paicli/browser/BrowserMode.java` | 枚举 `ISOLATED` / `SHARED` |
| `src/main/java/com/paicli/browser/BrowserSession.java` | 单例。当前 `mode` / `targetHost` / `targetPort` / `lastNavigatedUrl` / `agentOpenedTabs`。提供 `switchMode(...)`、`recordNavigated(url)`、`recordOpenedTab(tabId)`、`isAgentOpened(tabId)` |
| `src/main/java/com/paicli/browser/BrowserConnectivityCheck.java` | `probe(host, port)` → `Probe(connected, browserVersion, errorMessage)` |
| `src/main/java/com/paicli/policy/BrowserGuard.java` | `check(toolName, argsJson)` → `BrowserCheckResult(blocked, reason, requiresPerCallApproval, metadata)`；从 `BrowserSession` + `SensitivePagePolicy` 取依赖 |
| `src/main/java/com/paicli/policy/SensitivePagePolicy.java` | 加载默认 + 用户级 glob，`isSensitive(url)` 返回 `(matched: bool, pattern: String)` |

### 5.2 修改类

| 文件 | 改动 |
|---|---|
| `src/main/java/com/paicli/mcp/McpServerManager.java` | 新增 `restartWithArgs(name, newArgs)` 方法 |
| `src/main/java/com/paicli/mcp/config/McpServerConfig.java` | `args` 增加 setter（包可见或 public，文档注释「运行时切换专用」） |
| `src/main/java/com/paicli/policy/AuditLog.java` | `AuditEntry` 加可选 `metadata` 字段，新增带 metadata 的静态工厂；反序列化老格式容错 |
| `src/main/java/com/paicli/tool/ToolRegistry.java` | `executeTool` 入口对 `mcp__chrome-devtools__*` 工具加一道 `BrowserGuard.check`；`blocked=true` 抛 `PolicyException`；非 blocked 把 metadata 透传给 audit |
| `src/main/java/com/paicli/hitl/HitlToolRegistry.java` | 进入审批前先查 `BrowserGuard.requiresPerCallApproval`，命中则强制单步审批 |
| `src/main/java/com/paicli/hitl/TerminalHitlHandler.java` | 审批弹窗在敏感页面命中时多打印一行 `⚠️ 敏感页面，需单独确认` + matched pattern + URL；不展示「[a] 全部放行」 |
| `src/main/java/com/paicli/hitl/ApprovalRequest.java` | 新增可选字段 `String sensitiveNotice`（敏感命中时由调用方填，弹窗显示）；保持兼容老构造 |
| `src/main/java/com/paicli/cli/Main.java` | 新增 `/browser` 命令分发（参考 `/mcp` 实现） |
| `src/main/java/com/paicli/cli/CliCommandParser.java` | 增加 `BROWSER` 命令解析及子命令 `status` / `connect [port]` / `disconnect` / `tabs` |
| `src/main/java/com/paicli/agent/Agent.java` 系统提示词 | §5.4 段落 |
| `src/main/java/com/paicli/agent/PlanExecuteAgent.java` 同上 | 同 |
| `src/main/java/com/paicli/agent/SubAgent.java` 同上 | 同 |
| `src/main/java/com/paicli/cli/Main.java` Banner | `VERSION = "14.0.0"`；标语 `Session-Aware Browser Agent CLI` |

### 5.3 联动文档（按 AGENTS.md 5.x 硬规则）

- `AGENTS.md`：项目快照里把第 14 期标已完成；第 12 段「Chrome DevTools MCP」末尾「当前边界」改写——拆出 12.1（isolated 默认）+ 12.2（shared 模式 + 敏感页面策略 + close_page 保护）；CLI 命令章节加 `/browser`
- `README.md`：新增「第十四期 CDP 会话复用」段，含三平台 Chrome 启动命令、`/browser` 命令表、敏感规则文件示例
- `ROADMAP.md`：第 14 期 ✅；末尾状态行更新为「下一步进入第 15 期 Skill 系统」
- `.env.example`：见 §4.2

### 5.4 提示词加段（三处一致）

```
浏览器登录态（第 14 期）：
- 默认 isolated 模式：临时 user-data-dir，无 cookie / 登录态
- 用户执行 /browser connect 后进入 shared 模式：复用带登录态的调试 Chrome
- shared 模式下你看到的页面是用户的真实账户视图（GitHub、邮箱、内部系统等）
- 在 shared 模式下，你按用户视角操作，不要做用户没明确要求的写入：
  * 不要点关注 / 取消关注 / 删除 / 退出登录 / 修改设置等改写按钮
  * 不要在表单里填用户没给你的数据
  * 不要执行 evaluate_script 跑用户没要求的脚本
- PaiCLI 已对敏感 URL（settings / admin / billing / account / security / oauth 等）启用强制单步审批，每个改写型工具调用都会弹窗
- close_page 只能关你自己 new_page 出来的 tab，不要尝试关用户原有的 tab
- 如果不确定某个操作是否会影响用户登录态或账户数据，先问用户确认
```

---

## 6. 用户体验

### 6.1 `/browser status`（默认 isolated）

```
🌐 Browser Session
  当前模式: isolated（临时 user-data-dir，无登录态）
  chrome-devtools server: ● ready (28 tools)
  旧式 /json/version 探活: ⚠️ 未检测到 Chrome 调试端口（这是正常的，isolated 模式不需要）
  自动连接: Chrome 144+ 可在 chrome://inspect/#remote-debugging 勾选 Allow remote debugging 后使用 /browser connect
  提示: /browser connect 切到 shared 模式复用带登录态的调试 Chrome
```

### 6.2 `/browser connect`（autoConnect 失败）

```
> /browser connect

❌ autoConnect 连接失败，已回滚 chrome-devtools 启动参数：
...

请确认 Chrome 144+ 已打开 chrome://inspect/#remote-debugging，并勾选 Allow remote debugging for this browser instance。
```

### 6.3 `/browser connect <port>`（旧式探活失败）

```
> /browser connect 9222

❌ 无法连接到 Chrome 调试端口 127.0.0.1:9222
   原因: Connection refused

请先用调试端口启动 Chrome：

  macOS:
    open -a "Google Chrome" --args --remote-debugging-port=9222

  Linux:
    google-chrome --remote-debugging-port=9222

  Windows (PowerShell):
    & "C:\Program Files\Google\Chrome\Application\chrome.exe" --remote-debugging-port=9222

启动后再执行 /browser connect 9222 重试。
```

### 6.4 `/browser connect`（autoConnect 成功）

```
> /browser connect

🔄 已用 --autoConnect 连接 Chrome（需已在 chrome://inspect/#remote-debugging 允许远程调试）
   重启中... 4.2s
   ✓ chrome-devtools 已就绪 (28 tools)
```

### 6.5 `/browser connect <port>`（旧式探活成功）

```
> /browser connect 9222

🔍 探测 127.0.0.1:9222 ... ✓ Chrome/130.0.6723.91
🔄 切换 chrome-devtools server 到 shared 模式 (--browser-url=http://127.0.0.1:9222)
   重启中... 4.2s
   ✓ chrome-devtools 已就绪 (28 tools)
🌐 已连接到调试 Chrome（共 7 个 tab）
   ⚠️ shared 模式下 Agent 将以你的真实账户视角操作；敏感页面将强制单步审批

提示: /browser tabs 查看当前 tab 列表
```

### 6.4 敏感页面命中

```
─────── ⚠️  HITL 审批请求 ───────
工具: mcp__chrome-devtools__click
来源: chrome-devtools (Chrome DevTools 浏览器自动化)
风险: 在敏感页面执行点击操作

⚠️ 检测到敏感页面，本次操作需单独确认（不接受全部放行）
   当前 URL: https://github.com/settings/profile
   匹配规则: */settings*

参数:
  selector: "button.btn-danger"
  pageId: "page-2"

请选择操作：[y/Enter] 批准  [n] 拒绝  [s] 跳过  [m] 修改参数
> 
```

注意：选项里**不展示** `[a] 全部放行`，从源头杜绝用户「下意识 a」。

### 6.5 `close_page` 硬保护

```
> 帮我关掉 GitHub 那个 tab

[Agent 调 mcp__chrome-devtools__close_page，pageId=page-1]

🛡️ 策略拒绝: close_page 拒绝：该 tab 不是 PaiCLI 本次会话开启的，
   不允许关闭。如需关闭请用户手动操作。

[Agent 转告用户]
我无法关闭你原本打开的 GitHub tab，PaiCLI 只允许关闭它自己开的 tab。
你可以在 Chrome 里直接关掉，或者告诉我关 PaiCLI 自己开过的哪个 tab。
```

---

## 7. 测试场景（端到端实测必跑）

### 7.1 GitHub 私有仓 README（shared 模式核心场景）

前置：用户已在 Chrome 登录 GitHub，并打开了 `--remote-debugging-port=9222`。

```
> /browser connect
> 帮我看看 https://github.com/itwanger/<某个私有仓> 的 README 写了啥
```

**期望**：
1. `chrome-devtools` 切到 shared 模式
2. Agent 调 `navigate_page` → `take_snapshot`
3. 拿到私有仓 README 内容（isolated 模式下会被 GitHub 重定向到登录页）
4. Agent 输出总结

**验收**：内容确实是该私有仓的，不是登录页文案。

### 7.2 敏感页面强制审批

```
> /browser connect
> 打开 https://github.com/settings/profile，把昵称改成"测试"
```

**期望**：
1. navigate_page 命中 `*/settings*` → 单步审批弹窗
2. 用户批准 → 进入 settings 页
3. Agent 调 `fill` 改昵称 → 再次单步审批弹窗（敏感页面 + 改写型）
4. **没有** 「[a] 全部放行」选项

**验收**：连续 fill / click 期间每次都弹窗；用户不主动确认就改不了。

### 7.3 close_page 硬保护

```
> /browser connect
[/browser tabs 显示用户已开 7 个 tab，PaiCLI 自开 0 个]
> 帮我关掉第一个 tab
```

**期望**：Agent 调 `close_page` → `🛡️ 策略拒绝`。再让 Agent `new_page` 开一个新 tab，然后关——这个能成。

**验收**：误关用户原有 tab 100% 拦截；自开 tab 能正常关。

### 7.4 9222 未监听的友好提示

```
[未启动 Chrome 调试端口]
> /browser connect
```

**期望**：§6.2 的输出（含三平台命令）。

### 7.5 disconnect 后登录态消失

```
> /browser connect
> [访问 GitHub 私仓成功]
> /browser disconnect
> 再试一次
```

**期望**：disconnect 后回到 isolated；同样的私仓访问应失败或拿到登录页。`approvedAllByServer("chrome-devtools")` 被清空。

### 7.6 用户级敏感规则文件

写入 `~/.paicli/sensitive_patterns.txt`：

```
*://*.example-bank.com/*
```

```
> /browser connect
> 打开 https://www.example-bank.com/transfer
```

**期望**：navigate_page 弹敏感页面单步审批，匹配规则 `*://*.example-bank.com/*`。

### 7.7 server 全放行 + 敏感页面交叉

```
> /browser connect
> [操作普通页面，HITL 选 a → server，本会话 chrome-devtools 全放行]
> 现在去 https://github.com/settings/profile 改个东西
```

**期望**：进入 settings 后，下一个改写型工具（click / fill）**仍弹窗**，不被全放行覆盖。离开 settings 后回到非敏感页 → 静默放行恢复。

### 7.8 切换模式期间无并发问题

故意在 `/browser connect` 重启 chrome-devtools 期间通过另一个 ReAct turn 调用浏览器工具：

**期望**：要么阻塞等重启完成，要么 server 状态 `STARTING` 时该工具调用返回友好错误「chrome-devtools 正在重启」，不卡死、不抛 NPE。

---

## 8. 风险点

### 已知必踩的坑

1. **Chrome remote debugging UI 不等于旧式 `/json/version`**：`chrome://inspect/#remote-debugging` 勾选后，截图里会显示 `127.0.0.1:9222`，但 `curl http://127.0.0.1:9222/json/version` 仍可能是 404；默认路径必须走 `chrome-devtools-mcp --autoConnect`。
2. **旧式 `--browser-url` 需要 Chrome 已启用真实 CDP JSON 端口**：用户没启动 / 启动了但绑了别的端口 / 或调试端口被另一个工具占用 → 探活失败。文档 + `/browser connect <port>` 错误提示必须清楚。
3. **旧式端口未启动情况下还是改了 args**：`restartWithArgs` 后 chrome-devtools-mcp 自己启动失败、状态 `ERROR`。`/browser connect <port>` 必须**先探活，再改 args**——这个顺序硬规定。
3. **`close_page` 的 pageId 解析**：chrome-devtools-mcp 工具返回结构 PaiCLI 这边没强约定，需要在 Day 1 实际跑一次 `new_page` 看看 result 长什么样，再决定怎么解析 pageId。如果发现完全没法解析，**保守**：shared 模式下一律拒绝 `close_page`，让用户手动关。
4. **敏感规则的 URL 来源**：`navigate_page` 的 args 显式带 url，好办；但 `click` / `fill` 这些 args 里没 url，靠 `BrowserSession.lastNavigatedUrl` 缓存。如果 Agent 用 `evaluate_script` 改 location 跳转 → PaiCLI 抓不到。**接受这个边界**，文档明示「敏感判定基于最近一次 navigate_page；evaluate_script 跳转无法识别」。
5. **`AuditEntry` 加字段的反序列化兼容**：必须先写测试验证旧 7 字段 JSONL 能读，再 commit。Jackson 对 record 的容忍取决于 mapper 配置，宁可保守加 `@JsonCreator` 注解或显式 builder。
6. **`/browser` 命令撞上 `/browser` 自然语言**：如果用户输入「browser 怎么用」走的是 Agent，没事；只在 `/` 开头才走命令解析。这是项目既有约定（AGENTS.md §2 输入解析），保持。
8. **shared 模式启动慢**：`--autoConnect` / `--browser-url` 模式因为不用启 Chromium，通常比 isolated 快。但首次仍可能 npx 拉包慢，沿用第 13 期的进度打印逻辑即可。
9. **跨平台路径**：`~/.paicli/sensitive_patterns.txt` 在 Windows 是 `%USERPROFILE%\.paicli\sensitive_patterns.txt`。沿用项目既有约定 `Path.of(System.getProperty("user.home"), ".paicli", ...)` 不要硬编码 `/`。
9. **测试隔离**：`BrowserSession` 单例容易污染测试。**必须**在 `BrowserSessionTest` 等用例里 `@BeforeEach` reset。或者干脆把 BrowserSession 做成**非全局单例**，由 `Main` 持有引用并注入 ToolRegistry / HitlToolRegistry——更可测，推荐这条路。
10. **HITL 弹窗在敏感页面隐藏 `[a]`**：`TerminalHitlHandler` 的 `promptUntilDecision` 当前对所有请求展示同一组选项。要么按 `request.sensitiveNotice != null` 条件渲染不同的 prompt 文本，要么把「全放行」选项变成可选 flag。**采用前者**，无侵入。

### 已决策（不要再讨论）

- **单 server 切换 args + 重启**（不搞双 server 并存）
- **默认 mcp.json 模板不变**（仍是 `--isolated=true`，向后兼容）
- **`/browser connect` 是显式动作**，不主动改用户 mcp.json 文件
- **敏感页面命中只升级改写型工具**，读型工具仍受全放行影响
- **`close_page` 硬保护是策略层 PolicyException**，HITL 无法绕过
- **`AuditEntry` 加可选 `metadata` 字段**，不破坏现有调用
- **`BrowserSession` 由 Main 注入**，不做全局单例
- **不做 cookie 持久化 / 跨设备同步 / 自动登录**
- **本期只允许 host=127.0.0.1**，端口范围 1024-65535
- **敏感规则用户级文件 PaiCLI 不主动创建**，只读取

---

## 9. 开发顺序（5 天工作量）

每天结束前 `mvn test` 全绿才进入下一天。

### Day 1：BrowserSession + 连通性探测 + `/browser` 命令骨架（不含切换实现）

**产出**：
- `BrowserMode` 枚举
- `BrowserSession` 类（mode / lastNavigatedUrl / agentOpenedTabs / switchMode 占位）
- `BrowserConnectivityCheck.probe(host, port)` 用 OkHttp，timeout 2s
- `CliCommandParser` 加 `BROWSER` 命令 + 子命令解析
- `Main.java` `/browser` dispatch（status / connect / disconnect / tabs 占位实现，先返回固定文案）
- 端口 / host 校验

**测试**：
- `BrowserSessionTest`（5+ 用例：初始 isolated、switchMode 重置 lastNavigatedUrl、recordOpenedTab、isAgentOpened、agentOpenedTabs 隔离）
- `BrowserConnectivityCheckTest`（mock OkHttp：成功 / connection refused / timeout）
- `CliCommandParserTest` 追加：`BROWSER` 4 个子命令解析（5+ 用例）

### Day 2：McpServerManager.restartWithArgs + `/browser connect/disconnect` 真实切换

**产出**：
- `McpServerConfig.setArgs(...)`
- `McpServerManager.restartWithArgs(name, newArgs)` 内部走 `restart` 现有路径
- `Main.java` `/browser connect` 真实实现：默认 setArgs 为 `--autoConnect` → restartWithArgs → BrowserSession.switchMode；失败回滚 args、保持 isolated。`/browser connect <port>` 走旧式探活 → `--browser-url` → restartWithArgs。
- `/browser disconnect` 同理反向
- `/browser tabs`：shared 模式下调 `mcp__chrome-devtools__list_pages`（通过 ToolRegistry 调）；isolated 模式提示
- 切换时清 `approvedAllByServer("chrome-devtools")` 与 `agentOpenedTabs`

**测试**：
- `McpServerManagerTest` 追加：`restartWithArgs` 后 `server.config().getArgs()` 已变（mock McpClient，1+ 用例）
- `MainBrowserCommandTest` 新建：`/browser connect <port>` 探活失败时不调 restart，默认 `/browser connect` 走 autoConnect（4+ 用例）

### Day 3：SensitivePagePolicy + BrowserGuard + close_page 硬保护

**产出**：
- `SensitivePagePolicy`：默认规则 + 用户级文件加载；`isSensitive(url)` 返回 `(matched, pattern)`
- `BrowserGuard.check(toolName, argsJson)`：返回 `BrowserCheckResult(blocked, reason, requiresPerCallApproval, metadataJson)`
  - 解析 args 拿 url（navigate_page / new_page），更新 `BrowserSession.lastNavigatedUrl`
  - 解析 args 拿 pageId（new_page 成功后），更新 `agentOpenedTabs`
  - close_page：shared 模式 + 不在 agentOpenedTabs → blocked=true
  - 改写型工具 + 当前 URL 敏感 → requiresPerCallApproval=true
- `ToolRegistry.executeTool` 对 `mcp__chrome-devtools__*` 调 `BrowserGuard.check`；`blocked=true` 抛 `PolicyException`；`metadata` 透传给 audit

**测试**：
- `SensitivePagePolicyTest`（10+ 用例：默认规则匹配、自定义规则、空文件、缺失文件、glob 元字符）
- `BrowserGuardTest`（10+ 用例：close_page 自开 / 非自开、navigate_page 敏感 / 非敏感、改写型 / 读型工具、isolated 模式跳过 close 保护）

### Day 4：HITL 联动 + AuditEntry metadata 字段 + 提示词

**产出**：
- `ApprovalRequest` 加 `sensitiveNotice` 可选字段
- `TerminalHitlHandler` 在 `sensitiveNotice != null` 时 prompt 文本不展示 `[a]`，多打印 ⚠️ 行
- `HitlToolRegistry.executeTool` 把 `BrowserGuard.requiresPerCallApproval` 检查插在全放行检查之前
- `AuditLog.AuditEntry` 加可选 `metadata` 字段；新增带 metadata 的工厂方法；老格式 JSONL 反序列化不抛
- `Agent` / `PlanExecuteAgent` / `SubAgent` 三处提示词加 §5.4 段落
- `/audit` 输出在 metadata 非空时多一行展示

**测试**：
- `AuditLogTest` 追加：metadata 字段写入 + 读取（2 用例）；老格式 JSONL 反序列化兼容（1 用例）
- `TerminalHitlHandlerTest` 追加：sensitiveNotice 非空时不展示 `[a]`（2 用例）
- `HitlToolRegistryTest` 追加：requiresPerCallApproval 命中时绕过全放行（2 用例）

### Day 5：端到端实测 + 文档联动 + Banner

**产出**：
- §7 全部 8 个端到端用例手测，结果记录到 commit description
- `AGENTS.md` / `README.md` / `ROADMAP.md` / `.env.example` 联动
- Banner v14.0.0 + 标语
- 启动 hint 加一行「输入 `/browser connect` 复用带登录态的调试 Chrome」

**手测清单**（必跑，结果写到 commit description）：

1. ✅ `/browser status` 默认显示 isolated
2. ✅ `/browser connect` 在 Chrome remote debugging 已允许时走 `--autoConnect` → server 重启 → READY
3. ✅ `/browser connect 9222` 在旧式 9222 未启动 → 三平台命令提示；Chrome 启动旧式 9222 后成功 → server 重启 → READY
4. ✅ §7.1 GitHub 私仓访问成功
5. ✅ §7.2 敏感页面强制单步审批（连续 click / fill 都弹窗）
6. ✅ §7.3 close_page 硬保护（误关用户 tab 100% 拦截）
7. ✅ §7.5 disconnect 后登录态丢失
8. ✅ §7.6 用户级 sensitive_patterns.txt 生效
9. ✅ §7.7 server 全放行 + 敏感页面交叉
10. ✅ §7.8 切换期间并发调用不死锁
11. ✅ Ctrl+D 退出 → `ps aux | grep chrome-devtools-mcp` 无残留进程
12. ✅ 老格式 audit JSONL 文件能用 `/audit` 读出来不报错

任何一项 fail 回 Day 1-4 修。手测过完才能 commit。

---

## 10. 测试策略

### 单测覆盖下限

- `BrowserSessionTest` ≥ 5
- `BrowserConnectivityCheckTest` ≥ 3
- `SensitivePagePolicyTest` ≥ 10
- `BrowserGuardTest` ≥ 10
- `MainBrowserCommandTest` ≥ 4
- `CliCommandParserTest` 追加 ≥ 5
- `McpServerManagerTest` 追加 ≥ 1
- `AuditLogTest` 追加 ≥ 3（含老格式兼容）
- `TerminalHitlHandlerTest` 追加 ≥ 2
- `HitlToolRegistryTest` 追加 ≥ 2

### 集成测（可选）

- 标记 `@EnabledIfEnvironmentVariable("CDP_INTEGRATION_TEST", "true")`
- CI 默认不跑，本地手测覆盖

### 端到端（Day 5 手测清单 12 条）

必跑，结果写到 commit description 或 PR body。

---

## 11. 明确不做（留给后续期次）

- 自动登录 / 密码管理器集成
- Cookie / localStorage 直接读写工具（chrome-devtools-mcp 自身不暴露，不自补）
- 跨设备 / 跨 PaiCLI 实例的 session 同步
- Cookie 持久化到 PaiCLI
- 多 Chrome profile 并行
- TLS 客户端证书 / mTLS
- DOM snapshot 内的敏感数据自动脱敏（接受边界，由敏感页面强制审批兜底）
- 真 multimodal LLM 输入（第 21 期）
- Playwright / Firefox / WebKit 跨浏览器
- 远程 host 连接（仅 127.0.0.1，避免 SSRF 风险）
- `evaluate_script` 跳转后的敏感判定（无法可靠拦截，文档明示边界）

实现过程中如果发现某条不做的功能其实绕不过去，**先停下来回上游确认**，不要擅自扩展范围。

---

## 12. Banner 版本

完成第 14 期后：
- `Main.java` `VERSION` = `"14.0.0"`
- 类注释：第 14 期新增 CDP 会话复用、`/browser` 命令组、敏感页面强制单步审批、close_page 硬保护
- Banner 标语：`Session-Aware Browser Agent CLI`

---

## 13. 完成判定（DoD）

- [ ] §5.1 所有新增类落地，§5.2 所有现有文件改动落地
- [ ] `/browser status` / `connect [port]` / `disconnect` / `tabs` 四个子命令实现
- [ ] `9222` 探活失败给三平台命令提示
- [ ] 敏感页面命中改写型工具时强制单步审批，弹窗不展示 `[a]`，提示词含敏感 URL 与 matched pattern
- [ ] `close_page` 在 shared 模式下硬保护，非自开 tab 抛 PolicyException
- [ ] `AuditEntry.metadata` 写入 `browser_mode` / `sensitive` / `target_url` / `matched_pattern`
- [ ] 老格式 7 字段 JSONL 反序列化兼容
- [ ] 三处 Agent 系统提示词都加了 §5.4 浏览器登录态段落
- [ ] `/browser connect` 切换会清空 `approvedAllByServer("chrome-devtools")` 和 `agentOpenedTabs`
- [ ] `mvn test` 全绿（含原有用例 + 新增）
- [ ] §9 Day 5 手测 12 条全部跑过，结果写入 commit message
- [ ] §5.3 所有文档联动完成
- [ ] Banner 升 v14.0.0
- [ ] commit message 格式 + Co-Authored-By

---

## 14. 提交规约

- **不要**自行 `git push`
- 一次性 commit，message 用 heredoc
- commit 前 `git status` 确认无误改动 / 无敏感信息
- 末尾加：

```
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

---

## 15. Pre-flight 决策（已替你决定，不要再讨论）

| 问题 | 决策 |
|---|---|
| 双 server 还是单 server 切换 | **单 server，args 切换 + 重启** |
| 默认 mcp.json 模板要不要改 | **不改**，仍 `--isolated=true`；向后兼容 |
| `/browser connect` 是否改用户 mcp.json 文件 | **不改**，只在内存切；重启 PaiCLI 回 isolated |
| 敏感页面识别在哪一层 | 策略层 `BrowserGuard`，与 `PathGuard` / `CommandGuard` 同级 |
| 敏感命中升级哪些工具 | **只升级改写型工具**，读型仍受全放行 |
| 敏感命中是否绕过 server 全放行 | **绕过**，强制单步审批，弹窗不展示 `[a]` |
| 敏感规则数据源 | **默认内置 9 条 + 用户级 `~/.paicli/sensitive_patterns.txt`** |
| `close_page` 保护 | shared 模式硬保护，PolicyException；isolated 模式跳过 |
| `close_page` pageId 无法解析时 | **保守拒绝**，让用户手动 |
| AuditEntry 加字段方式 | 加可选 `metadata` 字段（JSON 字符串），保留所有现有工厂签名 |
| `BrowserSession` 是否全局单例 | **不**，由 Main 注入，便于测试 |
| host 范围 | 只允许 `127.0.0.1` |
| 端口范围 | `1024..65535` |
| evaluate_script 跳转的敏感判定 | **不拦**，文档明示边界 |
| DOM snapshot 是否脱敏 | **不脱敏**，由敏感页面单步审批兜底 |
| 跨平台 Chrome 启动命令 | macOS / Linux / Windows 三平台都给 |
| `/browser connect` 探活与 setArgs 顺序 | 默认 `--autoConnect` 不探活，失败回滚；旧式 `/browser connect <port>` **先探活，再 setArgs**，失败不动 args |
| 切换模式时 `approvedAllByServer` | **清空**，新模式新一轮信任 |
| 第 21 期多模态、第 15 期 Skill 范围 | **保持原计划**，本期不动 |

---

## 16. 必跑端到端用例：GitHub 私仓 + Settings 改写

这是**第 14 期最关键的端到端用例**，因为它同时验证：
- shared 模式真能复用登录态（私仓内容拿到）
- 敏感页面识别真触发了（settings 弹窗）
- 改写型工具在敏感页面强制单步审批（连续 fill 都弹）
- 全放行不能跨敏感边界（事先选了 server 全放行也没用）

**前置**：用户已在 Chrome 登录 GitHub，并以 `--remote-debugging-port=9222` 启动。

**测试 URL**：
- 私仓 README：用户提供自己的私仓
- Settings：`https://github.com/settings/profile`

**提示词序列**：
```
> /browser connect
> 帮我看看 https://github.com/<owner>/<private-repo> 的 README
> 然后打开 https://github.com/settings/profile 把昵称改成"测试昵称"
```

**期望行为序列**：
1. `/browser connect` 探活成功 → chrome-devtools 切 shared
2. Agent 调 `navigate_page` 私仓 URL → HITL 弹窗（首次浏览器调用），用户选 `a → server`
3. `take_snapshot` 拿私仓 README → 输出总结
4. Agent 调 `navigate_page` settings URL → **再次弹窗**（敏感页面），不走全放行
5. 用户批准
6. Agent 调 `fill` 改昵称 → **再次弹窗**（敏感页面 + 改写型）
7. 用户批准
8. 操作完成

**失败模式**（任一出现都算 Day 5 不通过，回 Day 3-4 重调）：
- 私仓 README 拿到的是登录页内容（说明 shared 模式没生效）
- settings 跳转没弹窗（说明敏感识别没接上）
- fill 操作没弹窗（说明改写型工具升级没生效）
- HITL 弹窗里仍展示 `[a] 全部放行`（说明 sensitiveNotice 路径没走对）
- close 用户原本 tab 没被拦（如果用例顺手测的话）

---

如果有任何疑问，回到上游问，不要自行推断。祝顺利。
