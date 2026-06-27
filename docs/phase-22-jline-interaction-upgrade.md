# Phase 22: JLine-first 交互升级

本阶段目标：把 PaiCLI 从“使用 JLine 的 CLI”升级为“以 JLine 4 为核心 UI runtime 的 Agent 终端”，交互体验对齐 Claude Code / Qoder CLI 一类产品。

参考文档：

- JLine 4 Intro: https://jline.org/versions/4.0/docs/intro/
- JLine 4 Architecture: https://jline.org/versions/4.0/docs/architecture/
- JLine 4 Line Reader: https://jline.org/versions/4.0/docs/line-reader/
- JLine 4 Interactive Features: https://jline.org/versions/4.0/docs/advanced/interactive-features/
- JLine 4 Print Above: https://jline.org/versions/4.0/docs/examples/print-above/
- JLine 4 Tab Completion: https://jline.org/versions/4.0/docs/tab-completion/
- JLine 4 History: https://jline.org/versions/4.0/docs/history/
- JLine 4 Key Bindings: https://jline.org/versions/4.0/docs/advanced/key-bindings/

## 架构目标

目标结构：

```text
Terminal
  -> LineReader
      -> prompt / right prompt / history / completer / highlighter / widgets
  -> InlineRenderer
      -> printAbove output bridge
      -> JLine Status bottom dock
      -> foldable blocks / tool calls / diff / HITL / plan review
```

原则：

- 交互期所有用户可见输出优先走 `Renderer.stream()`。
- inline 模式下 `Renderer.stream()` 优先通过 `LineReader#printAbove` 输出到当前输入行上方。
- inline 启动顺序必须先 `Renderer.start()` / 初始化 Status dock，再通过 `InlineRenderer.installStartupScreen(...)` 把完整 Banner + tips 挂到 `LineReader.CALLBACK_INIT`；首次进入 `readLine` 时由 `printAbove` 一次性渲染首屏，保证 logo、tips、输入行和底部 dock 在同一个 JLine 生命周期里协调。
- 普通任务和斜杠命令提交后先把本轮原始输入以 `>` 暗色整行块回写到 transcript；输入态仍使用 `* `，单行输入只回显一行，不额外追加空白行。普通任务随后再进入 mention 展开、Thinking 面板和工具调用，避免输入提交行被 dock 刷新或 activity 重绘吞掉。
- ReAct inline 模式下 LLM 请求期间显示固定高度 live thinking 区；reasoning delta 以灰色引用行出现在 live 区，content / tool call 开始前只清理 live 区自己占用的行，再进入正常 transcript。
- 底部 dock 使用 JLine `Status` 托管，JLine 负责滚动区域和 dock 重绘；业务代码不再手写 `\n`、`moveUp`、`CLEAR_TO_EOS` 去拼状态区。输入期把 LineReader 光标定位到 dock 上方一行，让输入行和 Status 同处底部区域。
- 非交互、测试、plain renderer 和降级路径继续使用普通 `PrintStream`。
- 输出写入必须同步，避免并行工具、后台任务、MCP 通知抢终端。

## 22.1 输出通道升级

目标：先做稳“transcript 自然流式输出 + 底部稳定状态”。

- 默认 CLI 路径尽早创建 JLine `Terminal`、`LineReader` 和 `Renderer`，启动期输出不再先裸写 `System.out`。
- Banner、模型加载、MCP 启动、Skill summary、ReAct 提示、退出提示走 `Renderer.stream()`。
- 开屏改为 π 主题彩色 logo + Qoder 风格首屏；MCP/Skill 初始化完成后一次性展示模型、MCP、Skill、ReAct 状态和三条 getting-started tips。
- `InlineRenderer` 增加 `bindLineReader(LineReader)`。
- `InlineRenderer.stream()` 在 `LineReader.isReading()` 时使用 `LineReader#printAbove`。
- 未绑定 LineReader、非读取态、plain/test 路径继续走原 `PrintStream`。
- 保留 transcript 与折叠块数据结构，避免 Agent / Plan / Team 调用方改动。
- `/index`、ReAct、Plan、Team 已经收口到 renderer stream，应自然受益。

验收：

- 用户输入过程中，异步通知可以显示在输入行上方。
- 普通输出、代码块折叠、tool block 在无 LineReader 绑定时保持原行为。
- `mvn test -Pphase16-smoke` 通过。

## 22.2 底部状态栏升级

目标：底部不只是提示，而是 JLine 原生托管的运行控制面板。

- dock 上层显示 YOLO/HITL 与 MCP/Skill 摘要。
- dock 下层显示 Auto Model / model / phase / ctx 百分比与 token / cost / elapsed / cwd。
- ReAct token/cost/elapsed 默认进入强状态行，不再作为 `📊 Token: ...` 正文行输出。
- ReAct 显示 thinking / tools / streaming / idle；thinking 阶段由固定高度 live 区承载，tick 只重写自己占用的几行，不使用独立 JLine `Display.update()` 或 `CLEAR_TO_EOS` 清屏。
- Plan 显示当前 task、完成数、失败数。
- Team 显示 worker/reviewer 状态。
- MCP 显示 starting/ready/error 数量。
- resize 后重新计算宽度，中文、emoji、ANSI 不破坏布局。

## 22.3 输入体验升级

目标：输入框成为真正的 Agent command line。

- 增加 `PaiCliHighlighter`：高亮 `/command`、`@path`、`@image:`、`@clipboard`。
- 对危险命令关键词做视觉提示。
- 对未闭合括号/引号做轻量提示。
- 保持右提示 `message / @path / @image`。
- 验证多行输入方案，优先评估 Alt+Enter / Shift+Enter 的终端兼容性。
- 保留 ESC 清空、Ctrl+V 图片、Ctrl+O 折叠。

## 22.4 补全系统升级

目标：补全从命令列表升级到上下文感知。

- 已扩展 `PaiCliCompleter` 的上下文补全入口，避免多个输入态各自维护补全逻辑。
- `/model` 已补 provider：`glm-5.1`、`glm-5v-turbo`、`deepseek`、`step`、`kimi`。
- `/mcp` 已补 `restart/logs/disable/enable/resources/prompts` 子命令，并从 resource cache 补 server name。
- `/skill on/off/show` 已补 skill name，candidate description 使用 skill 描述。
- `/task`、`/browser`、`/snapshot` 已补常用子命令。
- `@` 继续补 MCP resource；本地 `@path` 已补文件/目录路径。
- `@image:` 后已补本地路径，目录候选保留尾部分隔符。
- 本地 `@path` 已通过 `LocalPathMentionExpander` 展开为 `<file>` / `<directory>` 上下文块，路径逃逸项目根时保持原文。
- 普通裸路径补全接 JLine files completer。
- candidate 带 description、group、style。
- 大候选集做 topN 与懒加载，避免卡输入。

## 22.5 History 升级

目标：历史好用，但不泄露敏感内容。

- 已接入 `PaiCliHistory`，基于 JLine `DefaultHistory` 做 PaiCLI 过滤策略。
- 默认配置 `~/.paicli/history/input.history` 持久化，可用 `paicli.history.file` / `PAICLI_HISTORY_FILE` 覆盖；如果配置值指向目录，则使用该目录下的 `input.history`。
- 已设置 history size / file size，默认 `2000` / `10000`，可用 `paicli.history.size`、`paicli.history.fileSize` 或对应环境变量覆盖。
- 已忽略空白、重复、超长输入。
- 已过滤 API key、Bearer、Authorization、password、secret、token、private key、base64 图片等敏感内容。
- 已禁用 event expansion，避免 `!` 类历史展开产生意外行为。
- 已增加 `/history clear`。

## 22.6 Plan Review / HITL 升级

目标：计划审阅和人工审批走 JLine 原生交互。

Plan review：

- Enter 执行。
- Ctrl+O 展开完整计划。
- ESC 取消或折叠。
- `i` 输入补充要求。
- 使用 printAbove 渲染计划卡片。
- 底部 status 显示审阅模式。

HITL：

- 审批框走 printAbove。
- 单键 y/a/n/s/m 保持。
- 修改 JSON 参数时使用 LineReader 输入，支持编辑、历史、括号配对。
- 拒绝原因使用 LineReader。
- 审批期间 status 显示 `HITL waiting`。

## 22.7 异步任务和后台通知升级

目标：后台任务不会打断输入，但能持续反馈。

- MCP server 启动进度异步显示；CLI 首屏只做有界等待，默认 8 秒后进入 prompt，未完成 server 留在 `starting` 并后台继续初始化。
- `/task` 后台任务完成后通知。
- RAG 索引进度持续显示。
- LSP 诊断注入提示。
- Browser connect 状态变化提示。
- 后台失败使用醒目的 printAbove 消息，但不抢输入。

## 22.8 样式系统统一

目标：减少散落 ANSI，统一宽度和颜色策略。

- 用户输入高亮使用 `AttributedString` / `AttributedStringBuilder`。
- Status 使用 `AttributedString`。
- Completion candidate 使用 style。
- 逐步替换散落的 `AnsiStyle` 字符串拼接。
- 所有宽度计算统一显示列宽算法。
- 暗色 / 亮色终端都要可读。

## 22.9 Shell / Builtins / Console UI 评估

目标：借鉴 JLine 能力，但不冒进替换主命令系统。

- 暂不使用 `jline-shell` 替换 PaiCLI 现有命令解析。
- 优先借鉴 `SystemCompleter` 思路强化 `/` 命令补全。
- `console-ui` 可小实验用于 Plan review / HITL。
- `less/table/nano` 可用于后续长审计、MCP logs、搜索结果查看。

## 22.10 验收矩阵

自动测试：

- `InlineRenderer` printAbove fallback。
- printAbove 多行与并发写。
- `BottomStatusBar` 宽度 / 截断。
- `PaiCliCompleter` 上下文补全。
- 输入 highlighter。
- History 过滤。
- Plan review / HITL key binding。
- 扩展 `phase16-smoke` 或新增 `phase22-jline-smoke`。

手工 / PTY 测试：

- 输入一半时，异步输出不破坏输入行。
- 底部 status 稳定。
- 中文、emoji、窄窗口不乱。
- Ctrl+O、ESC、Ctrl+V、Tab、上下键正常。
- Plan review 方向键不误判 ESC。
- HITL 审批期间不吞输入。
- `java -jar target/paicli-1.0-SNAPSHOT.jar` 真机验证。
