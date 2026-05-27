# Inline 流式 TUI 改造方案（双形态可切换）

> 本文是给执行 Agent 的方案说明，不是新一期需求。
> 它修正 `docs/phase-16-tui-productization.md` 里"Lanterna 三栏"的形态选型：
> 抽出 `Renderer` 接口、两个实现并存——**inline 流式为默认、Lanterna 为可切换形态**。
> 不延伸到 phase-17（当前按 ROADMAP 为 LSP 诊断注入；图片复制粘贴输入后移到 phase-21）。

---

## 1. 为什么要改

phase-16 选了 Lanterna 全屏窗口型 TUI（文件树 + 对话流 + 状态栏 + 输入栏），
对照真正的目标体验——Claude Code、Qoder CLI、Codex CLI、Gemini CLI、Aider——是**走错了流派**。

| 流派 | 代表 | 终端形态 |
|---|---|---|
| 全屏窗口型 TUI | lazygit、k9s、htop、Lanterna 应用 | 进 alternate screen，接管整屏，退出后内容消失 |
| **Inline 流式 TUI** | **Claude Code、Qoder CLI、Codex CLI、Aider** | **不接管整屏**，正常滚屏，scrollback 保留 |

`Read 3 files (ctrl+o to expand)` 这种行内可折叠工具块、行内 git diff、单字符 HITL 提示——
Lanterna 的 widget 体系做不出来（一旦 `startScreen()` 进 alternate buffer，所有内容都活在它的 buffer 里；widget 是固定盒子，没有"折叠展开导致下方消息向下推"的滚屏语义）。

**调整方向**：抽 `Renderer` 接口，inline 流式作为默认实现，Lanterna 作为另一个可切换的实现。
两套并存的代价是接口抽象层 + 双倍维护，但好处是 phase-16 的 Lanterna 工作量不浪费、给用户保留全屏窗口选择、教学价值更高。

---

## 2. 什么是 Inline 流式 TUI（精确定义）

不进 alternate screen buffer（不发 `\e[?1049h`），主屏直接输出，
靠 ANSI 控制序列做**局部重绘**和**底部固定区域**：

| ANSI 序列 | 作用 | 用途 |
|---|---|---|
| `\e[s` / `\e7` | 保存光标位置 | 折叠块就地展开/收起 |
| `\e[u` / `\e8` | 恢复光标位置 | 同上 |
| `\e[K` | 清除从光标到行尾 | 单行刷新（spinner、状态） |
| `\e[2K` | 清除整行 | 多行刷新 |
| `\e[<n>A` / `\e[<n>B` | 上移/下移 n 行 | 折叠块覆盖原来的展开内容 |
| `\e[<n>;<m>r` | 设置滚动区域为第 n..m 行 | **底部常驻状态栏**核心 |
| `\e[r` | 重置滚动区域 | 退出时清理 |
| `\e[?25l` / `\e[?25h` | 隐藏/显示光标 | 重绘期间防闪烁 |
| `\r` | 回到行首 | 进度条 |
| `\e[7m` / `\e[27m` | 反色 / 取消反色 | 状态栏视觉 |

**底部常驻状态栏的实现核心**：DECSTBM（Top/Bottom Margins）。
1. 启动时发 `\e[1;<rows-1>r`，把可滚动区域限制在前 N-1 行
2. 任何 `println` 都会自动滚到第 N-1 行，第 N 行始终静止
3. 主线程定时 `\e[s` → `\e[<rows>;1H` → 重写状态栏 → `\e[u`
4. 退出前 `\e[r` 还原滚动区域

跨终端兼容性：macOS Terminal、iTerm2、Alacritty、Kitty、Windows Terminal 都支持；老 Windows cmd / 部分 SSH 客户端不支持，需要降级判断。

---

## 3. 目标 UI 元素清单（inline 形态）

| 元素 | 形态 | 关键技术 |
|---|---|---|
| 底部常驻状态栏 | 最后一行：`glm-5.1 │ 12.3k/200k │ HITL OFF │ 12.3s` | DECSTBM + 反色 |
| 行内可折叠工具块 | 折叠：`⏵ Read 3 files (ctrl+o to expand)`<br>展开：原内容 + `⏷ collapse` | 记录块起止行号 + `\e[<n>A\e[J` 覆盖重绘 |
| 行内 diff 块 | `+ added line`（绿底）/ `- removed line`（红底）/ `@@ ... @@`（青色） | `\e[42;30m` / `\e[41;30m` |
| HITL 行内提示 | `⚠️ execute_command "rm -rf /tmp" [y/n/a/s/m]` | 单字符读取，不阻塞滚屏 |
| Slash command palette | 临时浮起的浮动选择列表（最后 N 行） | 同折叠块技术，Esc 收回 |
| `/config` 配置面板 | palette 形态，不是模态窗 | 列表 + Enter 进入子面板 |
| 输入框 | 多行 + bracketed paste + 可选 Vim 模式 | JLine `LineReader` 已支持 |

Lanterna 形态保持 phase-16 现有设计：三栏布局、文件树、配置面板模态框、HITL 模态框。

---

## 4. 实现路径：抽 Renderer 接口 + 双实现

### 4.1 `Renderer` 接口（核心抽象层）

新建 `com.paicli.render` 包，把 Agent / HITL / Main 对终端的全部输出收口到一个接口：

```java
package com.paicli.render;

public interface Renderer extends AutoCloseable {
    /** 启动渲染器（设置滚动区域、初始化 widget 等）。 */
    void start();

    /** 关闭渲染器，还原终端状态。 */
    @Override void close();

    // ---- 普通消息 ----
    void appendUserMessage(String text);
    void appendSystemMessage(String text);
    void appendError(String text);

    // ---- 流式 LLM 输出 ----
    void appendReasoningChunk(String chunk);
    void appendContentChunk(String chunk);
    void finishStream();

    // ---- 工具调用（返回 handle，调用方稍后填结果） ----
    ToolBlock startToolCall(String toolName, String argsJson);

    // ---- 文件 diff ----
    void appendDiff(String filePath, String beforeText, String afterText);

    // ---- 状态栏 ----
    void updateStatus(StatusInfo status);  // model, tokens, elapsed, hitl

    // ---- HITL ----
    ApprovalDecision promptApproval(ApprovalRequest request);

    // ---- 配置面板 / 命令选择 ----
    void openConfigPanel(PaiCliConfig config);
    PaletteResult openPalette(String title, List<PaletteItem> items);
}

public interface ToolBlock {
    void appendResult(String result);
    void complete();  // 标记完成，允许 Ctrl+O 折叠
}
```

设计原则：
- 所有方法返回值小、无回调（避免 Lanterna 事件线程和 inline stdout 写入的并发模型分歧）
- `promptApproval` 同步阻塞（两个实现都能做到：inline 走 raw stdin 单字符、Lanterna 走 GUI 线程同步弹窗）
- `ToolBlock` 是 handle 模式：`startToolCall` 返回后 `appendResult` 异步填充——inline 实现里它对应 `FoldableBlock`，Lanterna 实现里对应 `CenterPane.appendToolResult` 的延迟调用

### 4.2 包结构

```
com.paicli.render/                       ← 新增（接口层）
├── Renderer.java                        核心接口
├── ToolBlock.java                       工具调用 handle
├── StatusInfo.java                      状态栏数据载体（record）
├── ApprovalRequest.java、ApprovalDecision.java
├── PaletteItem.java、PaletteResult.java
└── RendererFactory.java                 根据 PAICLI_RENDERER 创建实例

com.paicli.render.inline/                ← 新增（inline 实现）
├── InlineRenderer.java                  实现 Renderer
├── AnsiSeq.java                         ANSI 序列常量 + 工具
├── TerminalCapabilities.java            探测 DECSTBM、TrueColor、bracketed paste
├── BottomStatusBar.java                 DECSTBM 常驻状态栏
├── KeyDispatcher.java                   Ctrl+O / Esc 单按键分发
├── render/
│   ├── FoldableBlock.java               可折叠块
│   ├── BlockRegistry.java               活动块注册表
│   ├── InlineDiffRenderer.java          行内 diff
│   ├── ToolCallRenderer.java            工具调用块（折叠态/展开态）
│   └── MarkdownStreamRenderer.java      复用现有 TerminalMarkdownRenderer
└── palette/
    ├── SlashPalette.java                临时浮起命令选择列表
    └── ConfigPalette.java               /config palette 形态

com.paicli.render.plain/                 ← 新增（纯 println 降级）
└── PlainRenderer.java                   等价 phase-15 行为，无折叠、无状态栏

com.paicli.tui/                          ← 保留（Lanterna 实现）
├── LanternaRenderer.java                NEW：实现 Renderer，桥接到现有 LanternaWindow
├── LanternaWindow.java                  保留
├── RootPane.java、TuiBootstrap.java     保留
├── pane/CenterPane.java 等              保留
├── highlight/CodeHighlighter.java       保留（inline 也复用）
├── theme/、config/、history/、hitl/     保留
└── ...                                  整体加 @SinceDeprecated 注解：可继续维护、不强制
```

`com.paicli.tui.highlight.CodeHighlighter` 两个实现都用，不迁移。

### 4.3 `RendererFactory` 选型

```java
public final class RendererFactory {
    public static Renderer create(Terminal terminal, PaiCliConfig config) {
        String mode = resolveMode();  // env: PAICLI_RENDERER
        return switch (mode) {
            case "lanterna" -> new LanternaRenderer(terminal, config);
            case "plain"    -> new PlainRenderer(System.out);
            default         -> {
                if (TerminalCapabilities.supportsInline(terminal)) {
                    yield new InlineRenderer(terminal, config);
                }
                System.err.println("⚠️ 终端不支持 inline 模式，降级为 plain");
                yield new PlainRenderer(System.out);
            }
        };
    }

    private static String resolveMode() {
        String prop = System.getProperty("paicli.renderer");
        if (prop != null && !prop.isBlank()) return prop.toLowerCase();
        String env = System.getenv("PAICLI_RENDERER");
        if (env != null && !env.isBlank()) return env.toLowerCase();
        return "inline";  // 默认
    }
}
```

环境变量：

| 变量 | 含义 |
|---|---|
| 默认 | `inline`（Claude Code 风格） |
| `PAICLI_RENDERER=lanterna` | Lanterna 全屏 TUI |
| `PAICLI_RENDERER=plain` | 纯 println，无折叠、无状态栏 |
| `PAICLI_RENDERER=inline` | 显式声明 inline |
| `NO_COLOR=1` | 已有：禁用 ANSI 颜色，对三种模式都生效 |
| `PAICLI_TUI=true` | 兼容旧版：等价于 `PAICLI_RENDERER=lanterna`，打 deprecation 提示 |

### 4.4 修改的现有文件

| 文件 | 改动 |
|---|---|
| `com.paicli.cli.Main` | 启动时 `Renderer renderer = RendererFactory.create(...)`；删除 `TuiBootstrap.shouldUseTui` 二分支判断；CLI 主循环全部经 `renderer` 调用，不再直接 `System.out.println` |
| `com.paicli.agent.Agent$StreamRenderer` | 持有 `Renderer`；`onReasoningDelta`/`onContentDelta` 走 `renderer.appendReasoningChunk/appendContentChunk`；`appendToolCall` 走 `renderer.startToolCall(...).appendResult(...)` |
| `com.paicli.agent.PlanExecuteAgent`、`SubAgent` | 同上 |
| `com.paicli.hitl.TerminalHitlHandler` | 改名 `RendererHitlHandler`，持有 `Renderer`；`requestApproval` 直接调 `renderer.promptApproval(req)`，不区分 CLI / TUI |
| `com.paicli.hitl.SwitchableHitlHandler` | 简化：只剩开关逻辑（enable/disable），delegate 永远是同一个 `RendererHitlHandler` |
| `com.paicli.hitl.TuiHitlHandler` | 删除（被 `LanternaRenderer.promptApproval` 取代） |
| `com.paicli.tui.TuiBootstrap` | 删除 `shouldUseTui()` / `launch()`；保留为空类或直接删除（`LanternaRenderer` 自己负责启动 Lanterna 主循环） |
| `com.paicli.tui.TuiSessionController` | 删除（职责由 `Main` + `LanternaRenderer` 承担） |
| `com.paicli.util.AnsiStyle` | 扩充 cursor save/restore、line clear、scroll region、reverse 等序列；底层常量挪到 `com.paicli.render.inline.AnsiSeq` |
| `pom.xml` | 保留 Lanterna 依赖 |

### 4.5 关键模块代码草图（inline 实现部分）

#### 4.5.1 `BottomStatusBar`

```java
public final class BottomStatusBar implements AutoCloseable {
    private final PrintStream out;
    private final int rows, cols;
    private final ScheduledExecutorService scheduler;
    private volatile StatusInfo current;

    public BottomStatusBar(Terminal terminal) {
        this.rows = terminal.getHeight();
        this.cols = terminal.getWidth();
        this.out = System.out;
        out.print("[1;" + (rows - 1) + "r");  // 滚动区域 = 前 N-1 行
        out.print("[" + rows + ";1H");         // 光标到底
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::redraw, 0, 200, TimeUnit.MILLISECONDS);
    }

    public void update(StatusInfo info) { this.current = info; }

    private void redraw() {
        if (current == null) return;
        synchronized (out) {
            out.print("7");                          // save cursor
            out.print("[" + rows + ";1H");           // goto bottom
            out.print("[2K");                        // clear line
            out.print("[7m " + current.format(cols) + " [27m");
            out.print("8");                          // restore cursor
            out.flush();
        }
    }

    @Override public void close() {
        scheduler.shutdownNow();
        out.print("[r");
        out.print("[" + rows + ";1H[2K");
        out.flush();
    }
}
```

#### 4.5.2 `FoldableBlock` + `BlockRegistry`

```java
public final class FoldableBlock implements ToolBlock {
    private final String id;
    private final String collapsedHeader;   // "⏵ Read 3 files (ctrl+o to expand)"
    private final List<String> expandedLines = new ArrayList<>();
    private boolean isExpanded;
    private int renderedLineCount;
    private final PrintStream out;

    public void renderInitial() {
        synchronized (out) { out.println(collapsedHeader); renderedLineCount = 1; }
        BlockRegistry.register(this);
    }

    @Override public void appendResult(String result) {
        for (String line : result.split("\n")) expandedLines.add(line);
    }

    @Override public void complete() { /* 默认折叠态，等用户 Ctrl+O 展开 */ }

    public void toggle() {
        synchronized (out) {
            out.print("7");
            out.print("[" + renderedLineCount + "A");
            out.print("[J");
            if (isExpanded) {
                out.println(collapsedHeader);
                renderedLineCount = 1;
            } else {
                expandedLines.forEach(out::println);
                out.println("⏷ collapse");
                renderedLineCount = expandedLines.size() + 1;
            }
            isExpanded = !isExpanded;
            out.print("8");
            out.flush();
        }
    }
}
```

> 约束：`toggle` 只能在块之后没有滚屏的情况下生效。`BlockRegistry` 维护 `Deque<FoldableBlock>`，
> Ctrl+O 永远 toggle 队尾，且每次有新 println 时把队尾标记为 frozen（不可再展开）。
> Claude Code 也只允许折叠最近一个块，这是 inline 流式的固有约束。

#### 4.5.3 `InlineRenderer.promptApproval`（HITL 行内提示）

```java
@Override public ApprovalDecision promptApproval(ApprovalRequest req) {
    out.println("\n⚠️  HITL 审批: " + req.toolName());
    out.println("   风险: " + req.riskLevel());
    out.println("   参数: " + req.argsPreview());
    out.print("[y] 批准  [a] 全部  [n] 拒绝  [s] 跳过  [m] 修改 > ");
    out.flush();
    char ch = readSingleChar();  // raw mode 单字符
    out.println();
    return switch (Character.toLowerCase(ch)) {
        case 'y' -> ApprovalDecision.approve();
        case 'a' -> ApprovalDecision.approveAll();
        case 'n' -> ApprovalDecision.reject(promptForReason());
        case 's' -> ApprovalDecision.skip();
        case 'm' -> ApprovalDecision.modify(promptForNewParams(req));
        default  -> ApprovalDecision.reject("invalid input");
    };
}
```

### 4.6 `LanternaRenderer` 适配草图

```java
public final class LanternaRenderer implements Renderer {
    private LanternaWindow window;
    private CenterPane center;
    private StatusPane status;

    @Override public void start() {
        window = new LanternaWindow(...);
        center = window.getRootPane().getCenterPane();
        status = window.getRootPane().getStatusPane();
        // 在独立线程跑 window.start()（阻塞式 GUI 主循环）
    }

    @Override public void appendReasoningChunk(String chunk) {
        runOnGui(() -> center.appendReasoningChunk(chunk));
    }

    @Override public ToolBlock startToolCall(String name, String args) {
        runOnGui(() -> center.appendToolCall(name, args));
        return new LanternaToolBlock(center);  // appendResult 转发到 center
    }

    @Override public ApprovalDecision promptApproval(ApprovalRequest req) {
        // 复用现有 TuiHitlHandler 的模态框逻辑
        return ApprovalDecisionConverter.from(showDialog(req));
    }

    @Override public void close() {
        window.close();
    }
}
```

`LanternaRenderer` 是适配器，不重写 Lanterna 已有的 widget 逻辑——保留 `CenterPane` / `StatusPane` / `TuiHitlHandler`，只是 `Renderer` 方法到 widget 方法的薄封装。

---

## 5. phase-16 处置方案

### 5.1 已完成的代码怎么办

**全部保留**：`com.paicli.tui.*` 在新架构下作为 Lanterna 实现存在，不删代码。
- 现有 `CenterPane.appendAssistantChunk` / `appendToolCall` / `appendToolResult` 等方法被 `LanternaRenderer` 包装
- `TuiHitlHandler` 删除，能力合并到 `LanternaRenderer.promptApproval`
- `TuiBootstrap` / `TuiSessionController` 删除，启动逻辑由 `Main` + `RendererFactory` + `LanternaRenderer.start()` 接管

### 5.2 文档联动

- `docs/phase-16-tui-productization.md`：在文档顶部加注 "本文档形态选型已被 inline-tui-pivot.md 修正：phase-16 工作以 Lanterna 实现的形式保留，但默认形态切换为 inline 流式"
- `AGENTS.md`：第 16 期描述更新为"双形态可切换：默认 inline 流式，可切换 Lanterna 全屏"
- `README.md`：新增"渲染器形态切换"段，说明 `PAICLI_RENDERER` 三档
- `ROADMAP.md`：第 16 期标 ✅，下一步 phase-17 为 LSP 诊断注入，图片输入后移 phase-21
- `.env.example`：把 `PAICLI_TUI=true` 标 deprecated，新增 `PAICLI_RENDERER=inline|lanterna|plain`

---

## 6. 风险点

| 风险 | 缓解 |
|---|---|
| `Renderer` 接口稳定性 | 接口先收紧（≤16 方法），新功能优先扩展 `ToolBlock` / `StatusInfo` 等 record，不轻易加方法 |
| 双实现行为漂移 | 端到端手测必须在 inline / lanterna 双形态各跑一遍；接口契约写在 Javadoc，每个方法明确"哪些副作用、阻塞还是异步" |
| `promptApproval` 阻塞模型 | 两个实现都同步阻塞调用线程；Lanterna 内部用 `CountDownLatch` 让 GUI 线程回写主线程 |
| 部分终端不支持 DECSTBM | `TerminalCapabilities` 探测，不支持时 `InlineRenderer` 内部禁用状态栏，其他特性照常 |
| JLine `LineReader` 输入期间状态栏重绘把光标推走 | 重绘前后 `7` / `8` 严格配对；200ms 节流；`LineReader.readLine` 期间允许跳过一帧重绘 |
| 折叠块 `renderedLineCount` 在 resize 后失效 | resize 监听器把所有已折叠块标 frozen，显示 `⏵ ... (resized, scroll up to view)` |
| `Ctrl+O` 在 vi-mode 下被吃 | 用 `KeyMap.ctrl('O')` 显式绑定到 widget，优先级高于 vi-mode |
| Lanterna 启动失败时 fallback | `LanternaRenderer.start()` 抛异常时 `RendererFactory` 自动回退到 `InlineRenderer`，打日志提示 |
| 维护成本翻倍 | 接受。新功能默认只在 inline 实现；如要在 Lanterna 也展现，需要显式在 `LanternaRenderer` 适配 |

---

## 7. 开发顺序（6 天工作量）

每天结束 `mvn test` 全绿才进入下一天。

### Day 1：Renderer 接口 + RendererFactory + PlainRenderer

- 新建 `com.paicli.render` 包：`Renderer` / `ToolBlock` / `StatusInfo` / `ApprovalRequest` / `ApprovalDecision` / `PaletteItem` / `PaletteResult`
- `PlainRenderer`：纯 `System.out.println`，状态栏 / 折叠 / palette 都退化为简单打印
- `RendererFactory.create()` 实现，环境变量解析
- `Main.java` 接入：删除 `TuiBootstrap.shouldUseTui` 分支，改 `Renderer renderer = RendererFactory.create(...)`，主循环对话输出全部经 `renderer`
- `Agent$StreamRenderer` 持有 `Renderer` 引用，`System.out.print` 全部换成 `renderer.appendXxx`
- `RendererHitlHandler`（`TerminalHitlHandler` 改名）：调 `renderer.promptApproval`
- 测试：`PlainRendererTest`、`RendererFactoryTest`、`AgentStreamRendererTest`（用 `MockRenderer` 验证调用链）

### Day 2：InlineRenderer 骨架 + AnsiSeq + TerminalCapabilities + BottomStatusBar

- `com.paicli.render.inline` 包落地
- `AnsiSeq`：cursor save/restore、line clear、scroll region、show/hide cursor、reverse 等序列常量
- `TerminalCapabilities`：探测 DECSTBM、TrueColor、bracketed paste；不支持时各特性独立降级
- `BottomStatusBar` 完整实现（DECSTBM、200ms 节流、close 还原）
- `InlineRenderer.start()` / `close()` / `appendUserMessage` / `appendSystemMessage` / `updateStatus` 实现
- 测试：`AnsiSeqTest`、`TerminalCapabilitiesTest`、`BottomStatusBarTest`、`InlineRendererBasicTest`

### Day 3：FoldableBlock + 工具调用渲染 + 流式输出

- `FoldableBlock` + `BlockRegistry` 完整实现
- `ToolCallRenderer`：`startToolCall` 创建 `FoldableBlock`，`appendResult` 填内容，`complete` 标完成
- JLine 绑 `Ctrl+O` → `BlockRegistry.toggleLast()`
- `InlineRenderer.appendReasoningChunk` / `appendContentChunk` / `finishStream` 实现（直写 stdout，复用 `TerminalMarkdownRenderer`）
- 测试：`FoldableBlockTest`、`ToolCallRendererTest`

### Day 4：InlineDiff + HITL 行内提示 + Slash palette

- `InlineDiffRenderer`：行内 diff 渲染
- `InlineRenderer.appendDiff` 实现
- `InlineRenderer.promptApproval`：单行提示 + raw 单字符读取
- `SlashPalette` + `ConfigPalette`：`/config` palette 形态
- `InlineRenderer.openPalette` / `openConfigPanel` 实现
- 测试：`InlineDiffRendererTest`、`SlashPaletteTest`

### Day 5：LanternaRenderer 适配器

- `LanternaRenderer` 实现 `Renderer` 接口
- 桥接 `CenterPane.append*` / `StatusPane.update` / 现有模态框
- `promptApproval`：复用 `TuiHitlHandler` 内部逻辑（合并到 `LanternaRenderer`）
- 删除 `TuiBootstrap` / `TuiSessionController` / 独立 `TuiHitlHandler`
- `RendererFactory` 接 `lanterna` 分支
- 测试：`LanternaRendererTest`（用 mock Lanterna terminal）

### Day 6：端到端手测 + 文档联动

- §8 全部手测在 inline / lanterna 双形态各跑一遍
- 文档联动：phase-16 注废弃说明、AGENTS / README / ROADMAP / .env.example 更新
- Lanterna 启动失败 → 自动回退 inline 验证
- `mvn clean package` 验证 fat jar 仍可执行
- Banner 微调："PaiCLI v16.1.0"（小版本号区分形态修正）

---

## 8. 端到端手测清单（Day 6 必跑，inline / lanterna 双形态）

每条用例在 `PAICLI_RENDERER=inline`（默认）和 `PAICLI_RENDERER=lanterna` 各跑一遍：

1. ✅ 启动 PaiCLI，状态栏/状态面板显示模型 + token
2. ✅ `read_file ROADMAP.md`：inline 看到折叠块 + Ctrl+O 展开；lanterna 看到 `CenterPane` 内联工具结果
3. ✅ `write_file` 修改文件：inline 行内 diff；lanterna 文本对话流显示 diff（不要求颜色一致）
4. ✅ `/hitl on` + 危险命令：inline 单行提示 `[y/n/a/s/m]`；lanterna 模态框
5. ✅ `/config`：inline palette 浮起；lanterna 模态框
6. ✅ 长任务跑动期间 Ctrl+C 取消、状态显示停止刷新
7. ✅ `NO_COLOR=1`：无颜色，其它正常
8. ✅ `PAICLI_RENDERER=plain`：纯 println，无折叠、无状态栏
9. ✅ 终端 resize：inline 状态栏重新定位 + 已折叠块标 frozen；lanterna widget 自适应
10. ✅ Vim 模式 `PAICLI_VI=true`：输入框支持 hjkl 移动
11. ✅ 200 行代码粘贴：格式保留，不触发提交
12. ✅ Lanterna 启动失败模拟：自动回退 inline，打日志提示
13. ✅ `PAICLI_TUI=true`（deprecated 兼容）：等价 lanterna，提示 deprecation

---

## 9. 已决策（不再讨论）

| 问题 | 决策 |
|---|---|
| 双形态架构 | **抽 `Renderer` 接口 + 三个实现**（inline / lanterna / plain） |
| 默认形态 | **inline** |
| 切换机制 | **启动时**通过 `PAICLI_RENDERER` 选择，不支持运行时热切换 |
| 接口位置 | `com.paicli.render` 包，与 inline / lanterna 实现解耦 |
| Lanterna 代码 | **保留**，作为 `LanternaRenderer` 桥接 |
| 文件树（lanterna 形态） | 保留 phase-16 设计 |
| 文件树（inline 形态） | **不要**，用 `@` mention 自动补全替代 |
| 折叠快捷键 | **Ctrl+O**（Claude Code 同步），只折叠最近一个块 |
| 折叠块持久性 | 仅最近一个可折叠（resize / 后续滚屏后 frozen） |
| HITL（inline） | 行内单行 `[y/n/a/s/m]` |
| HITL（lanterna） | 保留模态框 |
| `/config`（inline） | palette |
| `/config`（lanterna） | 保留模态框 |
| 输入框 | JLine `LineReader`（双形态共用），可选 vi-mode |
| 旧 `PAICLI_TUI=true` | 兼容映射到 `PAICLI_RENDERER=lanterna`，打 deprecation |
| 启动失败 fallback | Lanterna 启动失败自动回退 inline |
| phase-17 计划 | **按 ROADMAP 更新为 LSP 诊断注入**；图片复制粘贴输入后移 phase-21 |

---

## 10. 完成判定（DoD）

- [ ] `com.paicli.render` 接口包落地（`Renderer` + 全部数据载体）
- [ ] `com.paicli.render.inline` 落地，`InlineRenderer` 通过所有单测
- [ ] `com.paicli.render.plain.PlainRenderer` 落地
- [ ] `com.paicli.tui.LanternaRenderer` 落地，`TuiBootstrap` / `TuiSessionController` / `TuiHitlHandler` 删除
- [ ] `RendererFactory` 三档切换工作正常，`PAICLI_TUI=true` 兼容映射
- [ ] `Main.java` / `Agent.StreamRenderer` / `RendererHitlHandler` 全部经 `Renderer` 接口
- [ ] §8 全部手测在 inline / lanterna 双形态过
- [ ] 文档联动完成（phase-16 / AGENTS / README / ROADMAP / .env.example）
- [ ] Banner 升 v16.1.0
- [ ] `mvn test` 全绿，`mvn clean package` 产出可执行 fat jar
