# 第 23 期：可选微信通道

> 期号说明：第 22 期已分配给「JLine-first 交互升级」（见 `docs/phase-22-jline-interaction-upgrade.md`），微信通道顺延为第 23 期。

## Summary

- 新增 PaiCLI 内置 WeChat Channel，默认关闭；只有显式执行 `paicli wechat ...` 才启动 iLink。
- 不做 Skill、不走 Runtime API；微信接收用 iLink `getupdates` long-poll，回复流式体验靠 `sendmessage` 分块。
- iLink 协议层出站回复仍是 `text_item.text` 文本消息，没有显式 Markdown parse mode；PaiCLI 保留 ClawBot 稳定支持的 Markdown 子集，并对标题、表格和非代码 fenced block 做移动端友好的归一化。
- 微信通道**不是关闭 HITL，而是用「非交互式默认拒绝策略」替代交互式审批**：远程入口不能弹窗等人，所以危险操作默认拒绝而非默认放行；PathGuard / CommandGuard / 绑定用户校验 / 审计日志 / 工作区边界全部保留并强化。
- v1 支持私聊、单绑定用户、单并发 turn、文字/图片/文件输入、文本回复和显式文件推送。

## 安全模型（本期核心，先于功能定义）

远程聊天入口 = 把一个能执行 shell、写文件、调 MCP 的 Agent 暴露给「任何能给绑定微信号发消息的人」。因此安全模型必须在功能之前定义清楚。

### 1. 非交互式策略，替代 HITL（不是关闭 HITL）

终端 HITL 靠「弹窗 + 等用户按键」实现，远程通道无法弹窗，所以引入 `WechatPolicyDecider`，对危险工具做**非交互式裁决**，默认从严：

| 工具类别 | 终端默认 | 微信通道默认 | 放开方式 |
|---|---|---|---|
| `execute_command` | HITL 审批 | **拒绝** | setup 配置只读命令白名单（如 `ls/cat/git status/mvn -q test`），命中才放行 |
| `write_file` / `create_project` | HITL 审批 | 允许但**强制 PathGuard 限定在 workspace 内** | 无需额外配置，越界即拒 |
| `revert_turn` | HITL 审批 | **拒绝** | 不在 v1 放开 |
| `mcp__*` 全部 MCP 工具 | 默认 HITL | **拒绝** | setup 配置 server/tool 维度允许清单 |
| 只读工具（`read_file`/`glob_files`/`grep_code`/`list_dir`/`search_code`/`web_search`/`web_fetch`） | 直接执行 | 直接执行 | — |

- 被拒绝的工具调用：把「策略拒绝 + 原因」作为工具结果回灌给模型（让它换路），并写一行审计 JSONL，approver 记 `policy`、outcome 记 `deny`。
- **`/help` 必须显式声明当前通道的策略级别**，让用户知道自己开了多大口子。
- 删除原计划里「固定 no-HITL / 最大权限」表述——HITL 是策略层核心环节，不存在「绕过 HITL 但保留策略层」。

### 2. 绑定与鉴权（解决「谁能敲门」）

iLink 扫码登录的是一个微信账号，`getupdates` 会收到**所有**给该号发消息的人，`boundUserId` 必须是硬门禁：

- **setup 扫码确认**：iLink 二维码由目标微信扫码确认后，直接使用扫码返回的 `ilink_user_id` 作为 `boundUserId`；不再额外要求验证码，避免 setup 完成前出现一次临时对话又退出的困惑。
- **非绑定用户消息**：一律 drop，**但必须写审计日志**（记录 senderId 脱敏值 + 时间 + "unbound_drop"），不能静默——静默等于不知道有人在试探。
- **换绑/多设备**：boundUserId 变更必须重新走双向确认；不提供「本地直接改文件即生效」路径。
- 账号文件（token / session / boundUserId）权限收紧到 `600`，敏感字段脱敏后才入日志。

### 3. 消息分发分层（解决控制命令竞态）

控制命令必须在分发层旁路，不进 turn 队列，否则 `/stop` 会排在它要取消的消息后面、永远来不及：

```
getupdates → [鉴权: boundUserId?] → [分类]
                                      ├─ 控制命令 (/stop /pause /resume /status) → 立即执行，旁路队列
                                      └─ 普通消息 / 业务命令 → 单并发 turn 队列
```

- `/stop` / `/pause` / `/resume` 直接作用于当前 turn 的取消句柄，不入队。
- 取消语义**复用现有 ReAct `/cancel` 机制**（cancellation token + 协作式检查点），不新造一套 `Thread.interrupt()`——OkHttp 阻塞读 LLM 流时 interrupt 不保证即时生效。
- `/pause` 暂停后，队列里的新消息继续积压；`/resume` 恢复消费。

### 4. 资源与成本围栏

远程入口 + 自动跑 turn = API Key 暴露给消息发送方，即使已绑定也要有上限：

- 每日 turn 数上限、单 turn token 预算上限（setup 可配，给保守默认值），超限拒绝并回执。
- long-poll 退避、限流重试、登录过期重连沿用 iLink 既有错误码处理。

### 5. 合规与隐私边界（文档必须显式声明）

- **封号风险**：iLink 走协议号 / hook 形态，违反微信使用条款，存在封号先例。文档显著位置声明，**建议用户使用小号**，不要绑定主力微信。
- **第三方可见**：分块发送的文本会经过 iLink / 微信链路，等于明文经过第三方。文档必须写明这条隐私边界，敏感内容不要走微信通道。
- **iLink 客户端供应链**：依赖的安装器是「运行时拉取本体」的 installer，Assumptions 里**锁定具体版本号，禁止 `@latest`**，并在文档说明「插件本体运行时下载」这一事实。

## Key Changes

- 新增 top-level 入口，必须在 LLM/API Key/JLine/MCP 初始化前分发：
  - `/wechat`：在 PaiCLI 主交互内扫码确认绑定并在当前进程后台启动 long-poll；已绑定时直接启动。
  - `/wechat setup`：重新扫码绑定并启动。
  - `/wechat status` / `/wechat stop`：查看或关闭当前进程内通道。
  - `paicli wechat setup`：进程级扫码确认绑定，保存 token/baseUrl/botId/boundUserId/workspace/策略配置，不启动轮询。
  - `paicli wechat start`：进程级前台启动 long-poll，适合脚本 / daemon。
  - `paicli wechat daemon start|stop|restart|status|logs`：后台服务管理。
- 新增 `com.paicli.wechat` 结构：
  - `IlinkClient`：QR、`getupdates`、`sendmessage`、`sendtyping`、`getuploadurl`、`notifystart/notifystop`。
  - `WechatAccountStore`：`~/.paicli/wechat/` 下保存账号、session、sync buf、media、logs；敏感字段脱敏，文件权限 `600`。
  - `WechatMessageLoop`：long-poll、cursor、去重、退避、登录过期、限流重试、启动停止通知；**鉴权 + 控制命令旁路在此层完成**。
  - `WechatPolicyDecider`：非交互式危险工具裁决（见安全模型 §1），接入现有 AuditLog。
  - `WechatAgentSession`：无 JLine 初始化，复用 PaiCLI config、LLM、MCP、Skill、Memory、ToolRegistry；注入 `WechatPolicyDecider` 替代交互式 HITL handler。
- 新增微信专用交互层：
  - `WechatCommandParser` 只支持 `/help /clear /compact /model /cwd /status /send /pause /resume /stop`；控制命令子集（`/stop /pause /resume /status`）标记为旁路队列。
  - `WechatRenderer` 分块发送正文，保留 ClawBot 稳定支持的 Markdown 子集（列表、引用、粗体、行内代码、真实代码块），把标题转成粗体标题、表格转成键值/列表，并清理 ANSI / 终端专用标记、图片 Markdown、H5-H6、中文斜体等兼容性差的标记；reasoning、完整工具参数和 diff 细节默认只写日志。
  - `WechatTextFormatter` 对非代码类 fenced block（流程说明、长中文箭头链）解包并换行，避免微信侧出现横向滚动代码块和“复制”按钮。
  - `WechatTerminalRenderer` 对微信侧分片做 Markdown 结构感知：未闭合代码块、表格中间不提前 flush，避免客户端收到半个块后渲染失败。
  - turn 取消复用 ReAct `/cancel` 的 cancellation token；普通消息运行中排队。
- 媒体规则：
  - 图片下载解密后保存本地，再用现有 `@image:<path>` 链路进入 `ImageReferenceParser`。
  - 非图片文件保存本地，并把文件名和路径注入提示词；是否读取由 Agent 工具决定。
  - **文件推送只走显式 `/send <path>` 或「本 turn 工具产物登记表」**；不再「检测到回复里出现的本地路径就自动上传」——避免把 `~/.ssh/id_rsa`、`~/.paicli/config.json` 等路径无意外发。推送路径同样受 workspace / 白名单约束。

## Test Plan

- 单元测试：
  - top-level `wechat` 命令不要求 API Key、不初始化 Terminal/MCP、不进入 REPL。
  - iLink headers、`context_token`、`get_updates_buf`、`longpolling_timeout_ms`、`ret:-2`、`errcode:-14`。
  - **鉴权**：非 boundUserId 消息被 drop 且写审计；setup 以扫码返回的 `ilink_user_id` 绑定；换绑需重新扫码确认。
  - **策略**：`execute_command` 默认拒绝、命中白名单放行；MCP 工具默认拒绝；`write_file` 越界被 PathGuard 拒；拒绝结果回灌模型 + 审计 approver=policy/outcome=deny。
  - 微信命令白名单、`/cwd` workspace 限制。
  - **控制命令旁路**：`/stop` 排在普通消息之后仍能立即取消当前 turn；`/pause /resume` 正确暂停/恢复队列消费。
  - 单并发 turn：A/B 消息排队、`/stop` 取消后队列继续。
  - **成本围栏**：每日 turn 上限、单 turn token 预算超限拒绝。
  - 媒体下载/解密/上传、图片转 `@image`、文件路径注入。
  - **文件推送**：仅 `/send` 与工具产物登记表触发；回复中出现的任意本地路径**不**自动上传。
  - `WechatRenderer`：正文分块、Markdown 保真、reasoning 不外发、长任务 typing/安抚。
  - `WechatTerminalRenderer`：表格 / 代码块未结束时不提前推送；完整段落可提前发送。
- 回归：
  - `mvn test -Dtest=Wechat*Test,CliCommandParserTest,ImageReferenceParserTest`
  - `mvn test -Pquick`
- 手工验收：
  - 默认启动 PaiCLI 不触发任何微信请求。
  - `wechat setup` 只扫码确认并保存，不轮询。
  - 非绑定用户发消息 → 被 drop + 审计可见。
  - `wechat start` 后文字/图片/文件进入 Agent；`execute_command` 默认被策略拒绝并回执。
  - `/stop` 能中断运行中的 turn。
  - `daemon stop` 后调用 `notifystop` 并停止处理新消息。

## Assumptions

- v1 不做群聊、语音、视频。
- 微信通道**不启用交互式 HITL，改用非交互式默认拒绝策略**；不代表绕过 PaiCLI 策略层（PathGuard / CommandGuard / 审计 / 工作区边界全部保留）。
- `/cwd` 只允许切到 setup workspace root 内的目录。
- 文件推送只来自显式 `/send` 或工具产物登记表；不做「路径出现即上传」。
- iLink 客户端依赖**锁定具体版本号**（非 `@latest`），且知悉「本体运行时下载」；绑定建议使用微信小号，知悉封号风险。
- 微信 / iLink 链路可见分块发送的文本内容，敏感内容不应走微信通道。
