# 第 16 期开发任务：TUI 界面 + 产品化

> ⚠️ **形态修正提示（2026-05-08）**：本文的"Lanterna 三栏全屏"形态选型已被
> [`docs/inline-tui-pivot.md`](inline-tui-pivot.md) 修正。
> 默认渲染器切换为 **inline 流式 TUI**（Claude Code 风格）；
> Lanterna 三栏 TUI 作为可切换形态保留，通过 `PAICLI_RENDERER=lanterna` 启用。
> phase-16 实现的 widget 代码（CenterPane / StatusPane / FileTreePane）继续可用，只是默认不再启动。


> 这份文档是给执行 Agent 的开发任务说明书，自包含、可直接照着推进。
>
> **开工前必读**：
> 1. 仓库根 `AGENTS.md`（仓库规则、文档联动硬规则）
> 2. `docs/phase-15-skill-system.md`（第 15 期 Skill 系统，已完成，本期 TUI 需兼容 Skill 状态展示）
> 3. `docs/phase-13-chrome-devtools-mcp.md`（第 13 期 Chrome DevTools MCP，已完成，TUI 需适配流式输出 + HITL 弹窗）
> 4. `src/main/java/com/paicli/cli/Main.java`（当前 CLI 入口，TUI 将在此之上做可视化层）
> 5. `src/main/java/com/paicli/util/TerminalMarkdownRenderer.java`（现有 Markdown 渲染器，TUI 需复用或增强）
> 6. `src/main/java/com/paicli/agent/Agent.java`、`PlanExecuteAgent.java`、`SubAgent.java`（系统提示词构造路径）
>
> **核心原则**：本期**不写新工具、不修改 Agent 核心逻辑**，只做**可视化层**。TUI 是 Main.java 的"皮肤"，ReAct / Plan / Team 三条执行路径完全不动，只是把原来 JLine 行编辑器的输入输出升级成 Lanterna 面板布局。页面布局、样式定制、键盘交互是本期重点，LLM 协议和工具层保持稳定。

---

## 1. 目标与产出物

让 PaiCLI 从"纯 CLI"升级为"终端 GUI"（TUI），具备文件树浏览、代码高亮、对话历史可视化、配置管理等产品级体验，**不牺牲任何现有 Agent 能力**。

**为什么做 TUI 而不是继续纯 CLI**：

- PaiCLI 当前已内置 9 个工具 + MCP 60+ 个工具，用户记忆成本高
- 文件树 / 代码高亮 / 对话历史回滚这些"展示型"需求，纯 CLI 手工渲染成本高且体验差
- Claude Code、Cursor、Aider 等竞品都有 TUI，PaiCLI 需要产品化竞争力
- Skill 系统（第 15 期）的 TUI 可视化是天然配套

最终交付：

- **TUI 主窗口**：替换 JLine 行编辑器，用 Lanterna 实现三栏布局（文件树 / 对话流 / 底部状态栏）
- **文件树浏览器**：侧边栏展示项目文件树，支持展开 / 收起、文件 / 目录图标区分、实时过滤搜索
- **代码高亮显示**：对话中代码块自动语法高亮（支持 Java / Python / TypeScript / Bash / JSON / Markdown），`read_file` 结果在 TUI 内内联展示
- **对话历史可视化**：上下键切换历史对话（不依赖 JLine 历史），支持 Markdown 渲染、代码块折叠、Assistant / Tool / User 消息分色
- **配置管理面板**：`/config` 命令打开配置编辑器，可查看 / 编辑 API Key、模型选择、MCP server 列表、Skill 启用状态
- **产品化打磨**：版本号显示、快捷键提示栏、滚动优化、颜色主题（深色 / 浅色）、窗口大小自适应
- **文档联动**（AGENTS.md / README.md / ROADMAP.md / .env.example）
- Banner 升 `v16.0.0`，标语 `Terminal-First Agent IDE`

**明确不做**（拆给后续期次或永远不做）：
- TUI 内嵌代码编辑器（只能看，不能改；改文件走 `write_file` 工具）
- TUI 内嵌终端仿真器（命令输出在对话流里渲染，不搞独立 terminal widget）
- 文件树内嵌 Git 状态（`git status` 集成留后续期）
- 多窗口 / 分屏（本期单窗口）
- 插件系统 / TUI 主题市场
- 安装包分发（CI/CD 范畴，不在本期范围）

---

## 2. 关键技术决策（务必遵守）

### 2.1 TUI 框架选型：Lanterna 3

**为什么选 Lanterna**：

| 维度 | Lanterna | JLine 原生 TUI | Textual (Python) |
|---|---|---|---|
| Java 生态 | ✅ 纯 Java | ✅ 当前在用 | ❌ Python |
| 成熟度 | ✅ v3 稳定 | ⚠️ 简单场景够用 | ✅ 但跨语言 |
| 布局能力 | ✅ 面板 / 滚动 / 分屏 | ❌ 行编辑为主 | ✅ 强 |
| 颜色 / 样式 | ✅ 16M RGB + TrueColor | ⚠️ 基础 ANSI | ✅ 强 |
| 学习成本 | ⚠️ 中等（需熟悉 widget 体系） | ✅ 低 | ✅ 低 |
| 对现有代码侵入 | ✅ 替换 Main 输入输出层，不碰 Agent | — | ❌ 需重写整个 CLI |

**Lanterna v3 核心 widget 使用计划**：

- `Window` / `Panel`：主窗口 + 三栏布局（侧边栏 + 对话流 + 状态栏）
- `TextArea`：对话流渲染（Markdown + 语法高亮）
- `ListWidget`：历史对话列表（上键切换）
- `FileTree` 或自定义 `TreeWidget`：文件树浏览
- `TextField`：底部输入框（替代 JLine LineReader）
- `Border` / `BackgroundColor`：面板边框和主题

**当前版本**：Lanterna `3.1.3`（需要 Java 17+ 支持）。

**JLine 保留用途**：
- `Terminal` 底层（Lanterna 依赖 JLine Terminal 做 ANSI 和键盘输入）
- `History` 接口（TUI 实现自己的历史对话管理，不依赖 JLine History）

### 2.2 TUI 与现有 CLI 的兼容策略

**不破坏现有命令行行为**：默认仍使用纯 CLI（JLine 行编辑器模式）。Lanterna 全屏 TUI 只在显式设置 `PAICLI_TUI=true` 或 `-Dpaicli.tui=true` 时启用；如果 `NO_TUI=true`、终端不可用或终端尺寸过小（`< 80×24`），仍降级为 CLI。

实现：
```java
if (shouldUseTui()) {
    launchTui();  // Lanterna TUI
} else {
    launchCli();  // 现有 JLine 行编辑器
}
```

`shouldUseTui()` 判断：
1. 未设置 `PAICLI_TUI=true` 且未设置 `-Dpaicli.tui=true` → 保持默认 CLI
2. `NO_TUI=true` → 降级
3. 终端不可用、尺寸为 `0×0`、rows < 24 或 cols < 80 → 降级
4. 显式启用且终端满足条件 → 启动 TUI

默认 CLI 不打印降级提示；只有用户显式启用 TUI 但环境不满足时，才打印具体原因。

### 2.3 三栏布局设计

```
┌──────────┬─────────────────────────────────────┬──────────┐
│ 文件树   │  对话流（Markdown + 代码高亮）      │ 状态栏   │
│          │                                     │          │
│ 📁 src   │  👤 你: 帮我看下项目结构            │ 🟢 Ready │
│ 📁 main  │                                     │ 💡 2k/200k│
│ 📄 Main  │  🤖 思考过程:                       │ ⏱ 12.3s │
│ 📄 Agent │  [RAG 检索中...]                    │          │
│          │                                     │          │
│          │  🤖 回复:                           │          │
│          │  项目结构如下...                    │          │
│          │                                     │          │
│          │  🛠️ 工具调用: read_file             │          │
│          │                                     │          │
├──────────┴─────────────────────────────────────┴──────────┤
│ > 输入你的问题...                                              │
└─────────────────────────────────────────────────────────────┘
```

**三栏宽度比例**（可配置，默认值）：
- 文件树：25%（最小 15 列，最大 35 列）
- 对话流：65%（自适应填充）
- 状态栏：10%（固定 20 列）

**响应式调整**：窗口宽度 < 120 列时，文件树缩到 15 列，状态栏隐藏部分信息；< 90 列时，文件树自动隐藏（按 `Ctrl+\` 切换显示）。

### 2.4 文件树浏览设计

**数据源**：`File` 对象 + `Files.walk` 懒加载（不用预扫描整个项目，只在展开节点时加载子目录）

**视觉规范**：
- 📁 目录（可展开）
- 📄 文件
- ☑️ `.git` / `node_modules` / `target` 等忽略目录（灰色 / 半透明）
- 文件名超长截断（尾部 `…`）

**交互**：
- `↑` / `↓`：上下移动选中项
- `→`：展开当前目录
- `←`：收起当前目录 / 返回上级
- `Enter`：在右侧对话流插入 `@file://path` 引用（相当于 `read_file` 语义快捷方式）
- `Ctrl+F`：文件树内搜索（按名称过滤，支持 `*.java` 后缀过滤）

**不做的功能**（留后续期）：
- 文件树内 Git 状态标记（绿勾 / 红叉 / 蓝点）
- 文件树右键菜单（文件重命名 / 删除等）
- 多文件选择（只支持单选 Enter 插入引用）

### 2.5 代码高亮设计

**高亮引擎**：**不使用第三方高亮库**（如 Pygments / highlight.js 的 Java 移植版），而是用 PaiCLI 自己第 4 期 `CodeChunker` 和 `CodeAnalyzer` 的能力做**轻量级语法着色**。

实现路径：
1. 从 `CodeChunker` 拿代码块的 `language` 字段
2. 内置 **正则表达式词法着色器**（支持 Java / Python / TypeScript / Bash / JSON / Markdown / XML / YAML），基于关键字 / 字符串 / 注释三类 token
3. 输出 ANSI 256 色字符串，直接交给 Lanterna `TextArea` 渲染

**为什么不用第三方库**：
- PaiCLI 已有 AST 解析能力（第 4 期 JavaParser），Java 代码着色可以复用
- 其他语言用正则表达式着色足够（`CodeChunker` 已能识别语言）
- 避免引入 highlight.js / Pygments 等 1MB+ 的依赖

**对话流代码块样式**：
```
┌─────────────────────────────────────────────────┐
│ ```java                                          │
│ public class Main {  // 注释: 绿色               │
│     public static void  // 关键字: 蓝色           │
│         void main(String[] args) {  // 字符串: 橙 │
│         }                                         │
│     }                                             │
│ }                                                 │
└─────────────────────────────────────────────────┘
```

### 2.6 对话历史可视化设计

**历史对话数据结构**（新增 `ConversationSnapshot`）：
```java
public record ConversationSnapshot(
    String id,              // UUID
    Instant timestamp,      // 时间戳
    String summary,         // LLM 自动摘要（≤ 50 字）
    int messageCount,       // 消息数
    String model,           // 当时使用的模型
    long tokenUsage         // token 使用量
) {}
```

**历史对话列表**（侧边栏或下拉列表）：
- 按时间倒序排列
- 显示摘要 + 时间 + 模型
- 按 `↑` / `↓` 切换，`Enter` 恢复该对话上下文

**消息分色**：
- 👤 用户消息：浅蓝背景
- 🤖 Assistant 回复：白色背景
- 🛠️ 工具调用：灰色斜体
- ⚠️ HITL 审批：橙色背景
- 🛡️ 策略拒绝：红色背景

**代码块折叠**：
- 超过 20 行的代码块自动折叠，显示 `... (展开)` 提示
- 按 `Enter` 或 `→` 展开，`←` 收起

### 2.7 配置管理面板设计

**触发方式**：`/config` 命令打开配置编辑器

**配置编辑器布局**：
```
┌─ 配置管理 ─────────────────────────────────────┐
│ [✓] API Key        •••••••••••••••••••••••••••│
│ [✓] 模型           GLM-5.1 (zhipu)              │
│ [✓] 上下文模式      Balanced (128k)             │
│ [✓] MCP Server     5 个已启用                   │
│ [✓] Skill          3 个已启用                   │
│ [✓] HITL           OFF                          │
└───────────────────────────────────────────────┘
```

**交互**：
- `↑` / `↓`：移动选中项
- `Enter`：编辑该项（弹出模态框或行内编辑）
- `Esc`：关闭配置面板

**配置读写**：
- 读：`PaiCliConfig.load()` 已有
- 写：`PaiCliConfig.save(config)` 新增，写入 `~/.paicli/config.json`
- 不修改 `.env`（`.env` 只读，配置持久化走 `config.json`）

### 2.8 主题系统

**两种主题**（可配置）：
- **深色主题（默认）**：背景 `#1e1e2e`（Catppuccin Mocha 基调），文字 `#cdd6f4`，代码关键字 `#cba6f7`（紫），字符串 `#a6e3a1`（绿），注释 `#6c7086`（灰）
- **浅色主题**：背景 `#eff1f5`（Catppuccin Latte），文字 `#4c4f69`，代码关键字 `#8839ef`，字符串 `#40a02b`，注释 `#9ca0b0`

**主题持久化**：`~/.paicli/config.json` 的 `theme` 字段（`"dark"` / `"light"`）

**Lanterna 颜色映射**：用 `TextColor.ANSI.foreground(颜色编号)` 或 `TextColor.fromRGB(r, g, b)`，深色主题用 256 色或 TrueColor，浅色主题同样适配。

### 2.9 快捷键体系

**全局快捷键**（TUI 内任何位置生效）：

| 快捷键 | 行为 |
|---|---|
| `Ctrl+C` | 取消当前任务（同 `/cancel`） |
| `Ctrl+D` | 退出程序（确认对话框） |
| `Ctrl+O` | 展开 / 折叠当前选中的代码块 |
| `Ctrl+\` | 显示 / 隐藏文件树侧边栏 |
| `Ctrl+P` | 打开历史对话列表 |
| `Ctrl+R` | 刷新文件树 |
| `Esc` | 关闭当前模态框 / 退出编辑模式 |

**输入框内快捷键**：

| 快捷键 | 行为 |
|---|---|
| `Enter` | 提交输入 |
| `↑` / `↓` | 输入历史（当前对话内的命令历史） |
| `Tab` | 自动补全（`/` 命令补全 + MCP resource `@` 补全，复用既有 `AtMentionCompleter`） |
| `Ctrl+K` | 清空输入框 |

---

## 3. 配置文件改动

### 3.1 `~/.paicli/config.json` 新增字段

```json
{
  "defaultModel": "glm",
  "theme": "dark",
  "tui": {
    "fileTree": {
      "width": "25%",
      "showHidden": false,
      "ignorePatterns": [".git", "node_modules", "target", "dist", ".idea"]
    },
    "editor": {
      "codeFolding": true,
      "maxLines": 500,
      "fontSize": 1
    }
  }
}
```

`tui` 字段是可选的，不存在时使用默认值。

### 3.2 `~/.paicli/filetree-ignore.txt`（用户级忽略规则）

文件树忽略规则，每行一个 glob：

```
# 默认忽略
.git
node_modules
target
dist
.idea
*.class
*.jar

# 用户自定义
*.log
*.tmp
```

不存在时使用内置默认列表（`.git` / `node_modules` / `target` / `dist` / `.idea` / `*.class` / `*.jar`）。

### 3.3 `.env.example` 新增

```bash
# ========== 第 16 期：TUI 产品化 ==========
# TUI 开关
# PAICLI_TUI=true       # 显式启用 Lanterna 全屏 TUI；默认保持 CLI
# NO_TUI=true           # 强制 CLI，覆盖 PAICLI_TUI=true
# PAICLI_THEME=dark     # TUI 主题: dark / light（默认 dark）

# TUI 窗口尺寸（Lanterna 自适应，无需手动设置）
# PAICLI_TUI_FILE_TREE_WIDTH=25%  # 文件树宽度（百分比或绝对列数）
# PAICLI_TUI_FONT_SIZE=1          # 字体缩放（0=小, 1=中, 2=大）
```

---

## 4. 与现有架构的集成点（要修改 / 新增的文件）

### 4.1 新增类（`com.paicli.tui` 包）

| 文件 | 职责 |
|---|---|
| `src/main/java/com/paicli/tui/TuiBootstrap.java` | TUI 入口，判断 `shouldUseTui()`，创建 `LanternaWindow` 和布局 |
| `src/main/java/com/paicli/tui/LanternaWindow.java` | 封装 Lanterna `Window`，统一处理主题 / 大小变化 / 输入分发 |
| `src/main/java/com/paicli/tui/pane/LeftPane.java` | 左侧文件树面板（`FileTreePane` 类，基于 Lanterna `TreeWidget` 或自定义 `ListWidget`） |
| `src/main/java/com/paicli/tui/pane/CenterPane.java` | 中央对话流面板（`ChatPane`，基于 Lanterna `TextArea`，支持 Markdown 渲染 + 代码高亮 + 消息分色） |
| `src/main/java/com/paicli/tui/pane/RightPane.java` | 右侧状态栏面板（`StatusPane`，显示模型 / Token / 耗时 / 命令提示） |
| `src/main/java/com/paicli/tui/pane/InputBar.java` | 底部输入栏（`InputBar`，基于 Lanterna `TextField`，处理 Enter / Tab / ↑↓ / Ctrl 快捷键） |
| `src/main/java/com/paicli/tui/highlight/CodeHighlighter.java` | 轻量级语法高亮器（基于 `CodeChunker` 语言识别 + 正则词法着色，输出 ANSI 256 色字符串） |
| `src/main/java/com/paicli/tui/highlight/SyntaxTheme.java` | 主题定义（深色 / 浅色），映射语言关键字 / 字符串 / 注释到 Lanterna `TextColor` |
| `src/main/java/com/paicli/tui/history/ConversationHistoryManager.java` | 对话历史管理（按 `ConversationSnapshot` 组织，支持保存 / 恢复 / 删除） |
| `src/main/java/com/paicli/tui/history/ConversationStore.java` | 对话历史持久化（JSONL 格式，按天分文件 `~/.paicli/history/conversations-YYYY-MM-DD.jsonl`） |
| `src/main/java/com/paicli/tui/config/ConfigEditor.java` | 配置管理面板（`/config` 命令触发，支持 API Key / 模型 / 上下文模式 / HITL / Skill 启用状态编辑） |
| `src/main/java/com/paicli/tui/theme/ThemeManager.java` | 主题管理器（深色 / 浅色切换，持久化到 `config.json`） |
| `src/main/java/com/paicli/tui/theme/ColorPalette.java` | Catppuccin Mocha / Latte 调色板（背景 / 前景 / 关键字 / 字符串 / 注释等） |

### 4.2 修改类

| 文件 | 改动 |
|---|---|
| `src/main/java/com/paicli/cli/Main.java` | TUI 入口判断（`shouldUseTui()`）；降级时保留现有 CLI；TUI 模式下用 `TuiBootstrap.launch()` 替换 JLine `LineReader` 主循环；Banner v16.0.0 |
| `src/main/java/com/paicli/cli/CliCommandParser.java` | 新增 `CONFIG` 命令类型 + 子命令（`/config` 开配置面板）；TUI 模式下 `/clear` 清空对话流；`/exit` / `/quit` 关闭 TUI 窗口 |
| `src/main/java/com/paicli/util/TerminalMarkdownRenderer.java` | 增强 Markdown 渲染器：支持代码块折叠 + 语法高亮输出（调用 `CodeHighlighter`）；保持 CLI 模式向后兼容 |
| `src/main/java/com/paicli/config/PaiCliConfig.java` | 新增 `tui` 配置段（`fileTree` / `editor`）；新增 `ThemeManager` 读取 / 写入 `theme` 字段 |
| `src/main/java/com/paicli/agent/Agent.java` | 流式输出时 TUI 模式改调用 `ChatPane.appendStreamingMessage()`（直接向面板写流式文本，不经过 `System.out`）；TUI 下的 HITL 弹窗调用 `TuiHitlDialog.show()` 替代 `TerminalHitlHandler` |
| `src/main/java/com/paicli/hitl/TerminalHitlHandler.java` | 新增 `TuiHitlDialog` 内部类（Lanterna 模态框弹窗），保留 CLI 模式兼容；`requestApproval` 根据 `LanternaWindow.isTuiMode()` 分发到 TUI 或 CLI |
| `src/main/java/com/paicli/agent/PlanExecuteAgent.java` | 同上：流式输出和 HITL 弹窗根据模式分发 |
| `src/main/java/com/paicli/agent/SubAgent.java` | 同上：Multi-Agent 流式输出分发 |

### 4.3 联动文档

- `AGENTS.md`：项目快照里把第 16 期标已完成；新增「14. TUI 界面」段说明三栏布局、快捷键、主题系统
- `README.md`：新增「第十六期 TUI 产品化」段，含截图示例、快捷键表、配置字段说明
- `ROADMAP.md`：第 16 期标 ✅；末尾状态行更新为「下一步进入第 17 期 LSP 诊断注入」
- `.env.example`：见 §3.3

---

## 5. 核心实现细节

### 5.1 TUI 主循环（替换 JLine LineReader 主循环）

**当前 CLI 主循环**（`Main.java` 已有）：
```java
while (running) {
    String line = lineReader.readLine("👤 你: ");
    // ... 处理命令 / 交给 Agent
}
```

**TUI 主循环**：
```java
if (shouldUseTui()) {
    TuiBootstrap.launch(terminal, config, llmClient, ...);
} else {
    // 现有 CLI 主循环
}
```

`TuiBootstrap.launch()` 内：
1. 创建 `LanternaWindow`（`Screen` + `Window`）
2. 创建三栏布局：`FileTreePane` / `ChatPane` / `StatusPane` + `InputBar`
3. 注册 `InputBar` 的 `Listener`：Enter 提交 → 调用 Agent 执行 → 流式结果写入 `ChatPane`
4. 注册窗口大小变化监听器：自适应调整三栏宽度
5. 主线程阻塞在 `Screen` 的 `pollEvent()` 循环

### 5.2 流式输出适配（TUI vs CLI）

**CLI 模式**（现有实现）：
```java
// AgentStreamRenderer 调用 System.out.println / print
reasoningContent.append(chunk);
System.out.print("🧠 思考过程:\n" + reasoningContent);
```

**TUI 模式**：
```java
// ChatPane 提供同步 append 方法（Lanterna 内部会处理线程安全）
chatPane.appendReasoningChunk(chunk);  // 流式追加思考过程
chatPane.appendContentChunk(chunk);    // 流式追加回复内容
chatPane.appendToolCall(toolCall);      // 工具调用（灰色缩进）
chatPane.appendToolResult(result);      // 工具结果（浅灰背景块）
```

**关键约束**：`ChatPane.append*` 方法必须是**线程安全**的，因为 Agent 流式输出和工具执行结果是并行到达的。Lanterna `TextArea` 本身不是线程安全的，需要用 `screen.getEventThread().postRunnable()` 或 `AtomicReference` 保证串行化。

### 5.3 HITL 弹窗适配（TUI vs CLI）

**CLI 模式**：`TerminalHitlHandler.requestApproval()` 用 `lineReader.readLine()` 阻塞等输入。

**TUI 模式**：`TuiHitlDialog.show()` 显示 Lanterna 模态对话框：
```
┌─ ⚠️ HITL 审批请求 ─────────────────────────┐
│ 工具: execute_command                       │
│ 危险等级: 🔴 高危                           │
│                                           │
│ 参数:                                      │
│   command: rm -rf /tmp/test                │
│                                           │
│ [y] 批准  [a] 全部放行  [n] 拒绝  [s] 跳过  │
└───────────────────────────────────────────┘
```

模态框阻塞 `InputBar` 输入，用户按键后关闭弹窗并返回 `ApprovalResult`。

### 5.4 文件树懒加载

**数据结构**：
```java
class FileTreeNode {
    Path path;
    String name;
    boolean isDirectory;
    boolean expanded;
    List<FileTreeNode> children; // 懒加载，expanded 时才填充
    FileTreeNode parent;
}
```

**懒加载触发**：`TreeWidget` 的 `expand(int index)` 回调里 `Files.list(path)` 加载子节点，过滤掉 `filetree-ignore.txt` 规则匹配的条目。

**文件树更新**：`/clear` 命令不刷新文件树（项目结构不变）；需要手动 `Ctrl+R` 或 `ToolRegistry.executeTools(["read_file", "list_dir"])` 后触发刷新。

### 5.5 代码高亮实现（轻量级正则着色）

**架构**：
```java
// CodeHighlighter.java
public String highlight(String code, String language) {
    SyntaxTheme theme = ThemeManager.getCurrentTheme();
    List<Token> tokens = lex(code, language);  // 正则词法分析
    return tokens.stream()
        .map(t -> switch (t.type()) {
            case KEYWORD -> theme.keywordStyle().toAnsi(t.text());
            case STRING  -> theme.stringStyle().toAnsi(t.text());
            case COMMENT -> theme.commentStyle().toAnsi(t.text());
            default      -> t.text();  // 默认颜色
        })
        .collect(Collectors.joining());
}
```

**正则词法规则**（每种语言单独实现 `Lexer` 接口）：
- `JavaLexer`：`\b(public|private|class|static|void|new|return|if|else|for|while)\b`（关键字）+ `"([^"\\]|\\.)*"`（字符串）+ `//.*$`（行注释）+ `/\*[\s\S]*?\*/`（块注释）
- `PythonLexer`：`\b(def|class|import|from|if|elif|else|for|while|return|True|False|None)\b` + `'''[\s\S]*?'''` / `"""[\s\S]*?"""` + `#.*$`
- `BashLexer`：`\b(if|then|else|fi|for|while|do|done|echo|export|source)\b` + `#.*$` + `"([^"\\]|\\.)*"` / `'[^']*'`

**集成到 `CodeChunker`**：`CodeChunker.split()` 已返回 `CodeChunk` list 含 `language` 字段，`CodeHighlighter` 直接复用。

### 5.6 主题持久化

**配置路径**：
1. `PaiCliConfig` 初始化时读 `config.json` 的 `tui.theme` 字段
2. `ThemeManager` 维护当前主题（`"dark"` 默认）
3. `/config` 编辑主题后调用 `PaiCliConfig.save(config)` 写回
4. 下次启动时读取

**Lanterna 主题适配**：
- 深色主题：`Screen` 的背景色设为 `#1e1e2e`，`TextArea` 背景色 `#181825`，边框色 `#313244`
- 浅色主题：`Screen` 背景色 `#eff1f5`，`TextArea` 背景色 `#e6e9ef`，边框色 `#dce0e8`

**颜色实现**：用 `TextColor.fromRGB(r, g, b)` 指定 RGB，不依赖系统终端配色。

---

## 6. 用户体验

### 6.1 TUI 启动输出

```
╔══════════════════════════════════════════════════════════╗
║                                                          ║
║   ██████╗  █████╗ ██╗ ██████╗██╗     ██╗                ║
║   ██╔══██╗██╔══██╗██║██╔════╝██║     ██║                ║
║   ██████╔╝███████║██║██║     ██║     ██║                ║
║   ██╔═══╝ ██╔══██║██║██║     ██║     ██║                ║
║   ██║     ██║  ██║██║╚██████╗███████╗██║                ║
║   ╚═╝     ╚═╝  ╚═╝╚═╝ ╚═════╝╚══════╝╚═╝                ║
║                                                          ║
║      Terminal-First Agent IDE v16.0.0                 ║
║                                                          ║
║  快捷键: Ctrl+O=折叠代码  Ctrl+\=切文件树  Ctrl+P=历史对话  ║
╚══════════════════════════════════════════════════════════╝

✅ 已加载模型: glm-5.1 (zhipu)
🔌 MCP server 就绪（5 个，60 个工具）
📚 Skills（3 个）
🔄 使用 ReAct 模式

┌───项目结构───┬───────────────────────────────┬──状态──┐
│ 📁 paicli    │  对话开始...                   │ 🟢 Ready│
│ 📁 src       │                               │ 💡 2k/200k│
│ 📁 main      │                               │ ⏱ --  │
│ 📄 pom.xml   │                               │        │
└──────────────┴───────────────────────────────┴────────┘
```

### 6.2 对话流渲染

**用户消息**（蓝色左竖线）：
```
│ > 帮我看下项目结构                              │
```

**Assistant 流式输出**（白底）：
```
│ 🤖 思考过程:                                     │
│   用户想了解项目结构，我先列出文件...             │
│                                                  │
│ 🤖 回复:                                         │
│  当前项目包含以下文件:                           │
│  - pom.xml: Maven 构建配置                      │
│  - README.md: 项目说明                          │
│  - src/: 源码目录                               │
│                                                  │
│ 🛠️ 工具调用: read_file                           │
│   path: README.md                               │
```

**工具结果**（灰底块）：
```
│ 📄 README.md（前 50 行）                          │
│ ┌─────────────────────────────────────────────┐  │
│ │ # PaiCLI                                     │  │
│ │ 一个 Java Agent CLI...                       │  │
│ └─────────────────────────────────────────────┘  │
```

**代码块高亮**：
```
│ ```java                                           │
│ ┌─────────────────────────────────────────────┐  │
│ │ public class Main {                         │  │
│ │     public static void main(                │  │
│ │         String[] args) {                    │  │
│ │         }                                    │  │
│ │ }                                            │  │
│ └─────────────────────────────────────────────┘  │
```

### 6.3 HITL 弹窗（TUI 模态框）

```
┌─ ⚠️ HITL 审批请求 ───────────────────────────────┐
│                                                    │
│ 工具: execute_command                              │
│ 危险等级: 🔴 高危                                   │
│ 来源: execute_command（执行 Shell 命令）             │
│ 风险: 可能修改系统文件或安装软件                     │
│                                                    │
│ 参数:                                              │
│   command: rm -rf /tmp/test                        │
│   timeout: 60                                      │
│                                                    │
│ [y] 批准  [a] 全部放行  [n] 拒绝  [s] 跳过  [m] 修改 │
│                                                    │
└────────────────────────────────────────────────────┘
```

### 6.4 配置管理面板

```
┌─ 配置管理 ─────────────────────────────────────────┐
│                                                    │
│  [✓] API Key            •••••••••••••••••••••••  │
│  值: sk-xxxxxxxxxxxxxxxx（已隐藏）                   │
│                                                    │
│  [✓] 默认模型            GLM-5.1 (zhipu)          │
│                                                    │
│  [✓] 上下文模式          Balanced (128k)           │
│                                                    │
│  [✓] HITL                OFF                      │
│   (按 Enter 切换 ON / OFF)                         │
│                                                    │
│  [✓] 主题                Dark  ▸                  │
│                                                    │
│  [✓] 文件树宽度           25%  ▸                   │
│                                                    │
│ 按 Esc 关闭                                        │
└────────────────────────────────────────────────────┘
```

### 6.5 文件树交互

```
┌───项目结构───┐
│ 📁 .git       │ ← ↑↓ 移动
│ 📁 src        │ ← → 展开
│ 📁 main       │ ← ← 收起
│ 📁 java       │
│ 📁 com        │
│ 📁 paicli     │
│ 📁 agent      │
│ 📄 Agent.java │ ← Enter 插入 @file:// 引用
│ 📄 Main.java  │
│ 📁 plan       │
│ 📄 pom.xml    │
└──────────────┘
```

---

## 7. 测试场景（端到端实测必跑）

### 7.1 TUI 启动（降级检测）

**场景 A：正常 TUI 启动**
```bash
# 终端尺寸 ≥ 80×24，显式启用 TUI
PAICLI_TUI=true java -jar target/paicli-1.0-SNAPSHOT.jar
```
**期望**：Lanterna 三栏窗口正常渲染，输入框可交互，可以提交任务。

**场景 B：默认 CLI**
```bash
java -jar target/paicli-1.0-SNAPSHOT.jar
```
**期望**：进入 JLine 行编辑器，不弹 Lanterna 全屏窗口。

**场景 C：CLI 强制降级**
```bash
PAICLI_TUI=true NO_TUI=true java -jar target/paicli-1.0-SNAPSHOT.jar
```
**期望**：降级到 JLine 行编辑器，所有现有 CLI 功能正常。

### 7.2 文件树浏览

```
> /config
[确认 fileTree.showHidden = false]

> /clear
[对话流清空]

> 帮我看下 src/main/java 下有哪些 Java 文件
```
**期望**：
1. Agent 调 `list_dir("src/main/java")`
2. TUI 文件树自动展开到 `src/main/java`，显示 `.java` 文件列表
3. 用 `↑` / `↓` 移动选中 `Agent.java`，按 `Enter` 插入 `@file://src/main/java/com/paicli/agent/Agent.java`
4. Agent 收到引用后调 `read_file` 并展示内容

### 7.3 代码高亮

```
> 帮我看下 src/main/java/com/paicli/agent/Agent.java 里的 run() 方法做了什么
```
**期望**：
1. Agent 调 `read_file` 拿 `Agent.java`
2. `read_file` 结果在对话流内渲染时，Java 代码块有语法高亮（关键字蓝色 / 字符串绿色 / 注释灰色）
3. Markdown 渲染正确（标题 / 列表 / 表格）

### 7.4 对话历史可视化

```
> 帮我创建一个 demo 项目
[Agent 执行完成]

> /clear
[清空当前对话]

> Ctrl+P
[弹出历史对话列表]
> ↑ 选择"帮我创建一个 demo 项目"
> Enter
```
**期望**：
1. `Ctrl+P` 打开历史对话列表（显示摘要 + 时间 + 模型）
2. `↑` / `↓` 移动选中该历史对话
3. `Enter` 恢复该对话上下文（`ConversationStore.load(id)`），对话流重新渲染
4. Agent 可以继续基于恢复的上下文执行

### 7.5 流式输出适配（TUI 模式）

```
> 帮我看下 ROADMAP.md 第 16 期讲了什么
```
**期望**：
1. 流式输出的 `🤖 思考过程` 实时追加到 `ChatPane`（不整块刷新）
2. `🛠️ 工具调用` 和 `📄 工具结果` 用不同背景色区分
3. 长回复（超过窗口高度）`ChatPane` 自动滚动到底部
4. 流式输出期间 `InputBar` 仍然可交互（可以 `Ctrl+C` 取消）

### 7.6 配置管理面板

```
> /config
[打开配置面板]

> ↓ 移动到"HITL"行
> Enter
[弹出确认框: 当前 OFF，切换为 ON？]
> y
[HITL 切换为 ON，写入 config.json]

> Esc
[关闭配置面板]
```
**期望**：
1. `/config` 打开配置管理面板
2. `Enter` 编辑 HITL 状态，弹出确认框
3. 保存后 `config.json` 更新
4. `Esc` 关闭面板回到对话流

### 7.7 TUI 快捷键

```
> Ctrl+\  [隐藏文件树]
> Ctrl+\  [重新显示文件树]

> Ctrl+P  [打开历史对话列表]
> Esc    [关闭历史对话列表]

> Ctrl+R  [文件树刷新]
[文件树重新扫描 src/ 目录]
```

### 7.8 主题切换

```
> /config
> ↓ 移动到"主题"行
> Enter
> → 切换到"Light"
> Enter
```
**期望**：
1. 整个 TUI 立即切换到浅色主题（背景变白，文字变深）
2. 对话流 / 文件树 / 弹窗统一变浅色
3. `config.json` 写入 `"theme": "light"`
4. 重启后仍保持浅色

### 7.9 HITL 弹窗（TUI 模态框）

```
> /hitl on
> 执行一个危险命令: execute_command "rm -rf /tmp/test"
```
**期望**：
1. TUI 模式显示 Lanterna 模态框弹窗（见 §6.3 样式）
2. 弹窗期间 `InputBar` 不可交互
3. `y` 批准 → 弹窗关闭 → 命令执行
4. `n` 拒绝 → 弹窗关闭 → 策略拒绝文案写入对话流

### 7.10 降级边界

```
> # 终端尺寸 79×23
> java -jar target/paicli-1.0-SNAPSHOT.jar
```
**期望**：检测到 cols < 80 或 rows < 24 → 降级 CLI 模式，Banner 加降级提示。

```
> NO_TUI=true java -jar ...
```
**期望**：强制降级 CLI 模式。

---

## 8. 风险点

### 已知必踩的坑

1. **Lanterna 的线程安全模型**：Lanterna 所有 GUI 操作必须在事件线程（`screen.getEventThread()`）里执行。Agent 流式输出在后台线程，需要 `postRunnable()` 封送到事件线程，否则会抛 `IllegalStateException` 或渲染撕裂。
2. **`TextArea` 大文本性能**：对话积累到 1000+ 条消息时，`TextArea` 会卡顿。解决方案：`TextArea` 只保留最近 200 条消息的渲染，历史对话按 `ConversationSnapshot` 归档到 `ConversationStore`，`Ctrl+P` 恢复。
3. **代码高亮性能**：大文件（> 500 行）正则着色可能阻塞事件线程。解决：在后台线程做 `CodeHighlighter.highlight()`，完成后再 `postRunnable` 更新 UI。
4. **JLine History 迁移**：现有 CLI 的 JLine `History` 是输入命令历史（`/plan`、`/search` 等），TUI 的 `ConversationHistoryManager` 是**对话快照历史**（用户和 Agent 的完整对话），两者不能混用。TUI 模式下 JLine History 仍可保留（按 `↑` 在输入框内展示命令历史）。
5. **Lanterna 在 Windows ConEmu / Cmder 的兼容性**：部分 Windows 虚拟终端对 ANSI TrueColor 支持不完整，可能导致颜色显示异常。需要在 Windows 上做 fallback 到 16 色方案。
6. **文件树的 `Files.walk` 性能**：大仓库（如 `paicli` 自己，src/ 下 100+ 文件）`Files.walk` 可能 100ms+。解决：只懒加载一层（展开目录时 `Files.list`），不做预扫描。
7. **`CodeChunker` 的语言识别精度**：`CodeChunker` 靠文件名后缀识别语言（`.java` → `java`），没有后缀的文件（如 `Makefile`、`Dockerfile`）拿不到语言。解决：内置 `Makefile` / `Dockerfile` / `Jenkinsfile` 等无后缀文件的扩展识别表。
8. **Lanterna Screen 重绘频率**：流式输出每来一个 chunk 就 `postRunnable` 刷新 `TextArea`，事件线程会密集刷新屏幕。解决：做 50ms 节流（`ScheduledExecutorService.scheduleAtFixedRate`），每 50ms 批量合并多个 chunk 一次性刷新。
9. **TUI 下的多行输入**：Lanterna `TextField` 默认是单行的。`Shift+Enter` 插入 `\n` 让输入框支持多行，提交时合并为一段。
10. **降级 CLI 模式的 TUI 专属功能降级行为**：`/config` 在 CLI 模式下应该降级为打印配置值（`PaiCliConfig.toString()`），不弹出配置面板（因为 CLI 没有面板能力）。

### 已决策（不要再讨论）

| 问题 | 决策 |
|---|---|
| TUI 框架 | **Lanterna 3**（不引入 Textual / Bubble Tea 等跨语言方案） |
| 代码高亮引擎 | **自研正则词法着色**（不引入第三方高亮库，复用 `CodeChunker` 语言识别） |
| TUI vs CLI 关系 | **CLI 为主，TUI opt-in**（`PAICLI_TUI=true` 或 `-Dpaicli.tui=true` 才启动全屏界面） |
| 文件树 Git 状态 | **不做**（留后续期） |
| TUI 内嵌代码编辑器 | **不做**（只展示，改文件走 `write_file` 工具） |
| 多窗口 / 分屏 | **本期不做**（单窗口） |
| 主题数量 | **两种**（深色 / 浅色），不搞主题市场 |
| 对话历史 | **新设计 `ConversationSnapshot`**，不依赖 JLine History |
| `CodeHighlighter` 依赖 | **零第三方依赖**（纯正则 + 已有 `CodeChunker`） |
| 主题持久化路径 | `~/.paicli/config.json` 的 `theme` 字段 |
| 对话历史持久化 | `~/.paicli/history/conversations-YYYY-MM-DD.jsonl`（按天，JSONL） |
| 降级提示文案 | 默认 CLI 不提示；显式启用 TUI 但环境不满足时才提示具体原因 |
| TUI 启动失败 | 降级 CLI 模式，不直接退出 |
| 快捷键冲突 | `Ctrl+C` / `Ctrl+D` / `Esc` / `Enter` / `↑↓` 保留终端默认语义，不做自定义冲突映射 |

---

## 9. 开发顺序（6 天工作量）

每天结束前 `mvn test` 全绿才进入下一天。

### Day 1：项目骨架 + `TuiBootstrap` + `LanternaWindow` + 降级检测

**产出**：
- `com.paicli.tui` 包结构
- `TuiBootstrap`：`shouldUseTui()` 判断 + `launch()` 入口
- `LanternaWindow`：`Screen` / `Window` / 主题初始化 / 大小监听
- 三栏布局骨架（空 `Panel` 占位）
- `Main.java`：`shouldUseTui()` 分支判断 + 降级 CLI 路径

**测试**：
- `TuiBootstrapTest`：未显式设置 `PAICLI_TUI=true` → `shouldUseTui()` 返回 `false`
- `TuiBootstrapTest`：`PAICLI_TUI=true` 且 `NO_TUI=true` → `shouldUseTui()` 返回 `false`
- `TuiBootstrapTest`：小终端尺寸模拟 → `shouldUseTui()` 返回 `false`（1 用例）
- `LanternaWindowTest`：创建窗口 + 设置主题 + 调整大小（2 用例）

### Day 2：三栏布局 + 文件树 + 输入栏

**产出**：
- `FileTreePane`：基于 Lanterna `ListWidget` 或 `TreeWidget`，懒加载 `Files.list`，支持 `↑↓→←Enter` 快捷键
- `InputBar`：基于 Lanterna `TextField`，处理 `Enter` 提交 / `↑↓` 输入历史 / `Tab` 补全（接 `AtMentionCompleter`）
- `ChatPane` 骨架：`TextArea` 占位，只显示消息骨架（不渲染 Markdown）
- `StatusPane` 骨架：显示固定占位符

**测试**：
- `FileTreePaneTest`：懒加载子目录、展开 / 收起、忽略规则过滤（5+ 用例）
- `InputBarTest`：Enter 提交、空输入不提交、Esc 清空（3+ 用例）

### Day 3：代码高亮 + ChatPane 渲染 + 流式输出适配

**产出**：
- `CodeHighlighter` + `SyntaxTheme`（深色 + 浅色调色板 + 正则词法着色器）
- `ChatPane` 完整渲染：Markdown 标题 / 列表 / 表格 / 代码块 + 消息分色（用户 / Assistant / Tool / HITL）
- `Agent.java` / `PlanExecuteAgent.java` / `SubAgent.java`：流式输出时根据 `LanternaWindow.isTuiMode()` 分流到 `ChatPane.append*()` 或 `System.out.print`
- 流式输出 50ms 节流（`ScheduledExecutorService`）

**测试**：
- `CodeHighlighterTest`：Java / Python / Bash / JSON 代码高亮输出含 ANSI 颜色（4+ 用例）
- `ChatPaneRenderTest`：用户消息 / Assistant 回复 / 工具调用 / HITL 弹窗渲染（5+ 用例）
- `SyntaxThemeTest`：深色 / 浅色主题颜色值正确（2+ 用例）

### Day 4：HITL 弹窗 + 配置面板 + 快捷键

**产出**：
- `TuiHitlDialog`：Lanterna 模态框（替代 `TerminalHitlHandler` CLI 模式）
- `ConfigEditor`：`/config` 命令打开配置面板，支持 API Key / 模型 / HITL / 主题编辑
- `ThemeManager`：主题切换（`Ctrl+?` 或其他快捷键），`config.json` 持久化
- 全局快捷键注册（`Ctrl+C` / `Ctrl+D` / `Ctrl+O` / `Ctrl+\` / `Ctrl+P` / `Ctrl+R`）

**测试**：
- `TuiHitlDialogTest`：显示弹窗 + 按键响应（3+ 用例）
- `ConfigEditorTest`：编辑 HITL 开关 + 主题切换（3+ 用例）
- `ThemeManagerTest`：深色 / 浅色切换 + 持久化（2+ 用例）
- `ShortcutTest`：`Ctrl+C` 取消任务 / `Ctrl+D` 退出确认（2+ 用例）

### Day 5：对话历史 + 代码块折叠 + 响应式布局 + 端到端实测

**产出**：
- `ConversationHistoryManager` + `ConversationStore`：历史对话快照保存 / 恢复
- `ChatPane` 代码块折叠（> 20 行自动折叠，`Ctrl+O` 展开）
- 窗口大小变化自适应（< 120 列文件树缩窄，< 90 列文件树隐藏）
- `StatusPane` 完整实现（模型 / Token / 耗时 / 快捷键提示）

**测试**：
- `ConversationHistoryManagerTest`：保存快照 / 恢复 / 删除（4+ 用例）
- `ConversationStoreTest`：JSONL 写入 / 读取 / 按天分文件（3+ 用例）
- `ChatPaneFoldTest`：长代码块折叠 / 展开（2+ 用例）
- `ResponsiveLayoutTest`：窗口尺寸 < 120 / < 90 的布局变化（2+ 用例）

### Day 6：文档联动 + Banner + 端到端全量实测 + 安装包分发

**产出**：
- §7 全部 10 个端到端用例手测
- `AGENTS.md` / `README.md` / `ROADMAP.md` / `.env.example` 联动
- Banner v16.0.0 + 标语 + 快捷键提示
- `pom.xml` 添加 Lanterna 依赖
- **安装包分发**（参见 §6.7）：
  - `mvn clean package` 产出 `target/paicli-1.0-SNAPSHOT.jar`（pom.xml 仍保持 `1.0-SNAPSHOT`，Banner 显示 `16.0.0`）
  - 配置 `maven-assembly-plugin` 或 `maven-shade-plugin` 做**可执行 fat jar**（包含 Lanterna 依赖，用户 `java -jar` 即可运行）
  - 编写 `INSTALL.md`（安装说明：JDK 17 + `java -jar` 两步）
  - GitHub Actions Release workflow 留给后续分发增强

**手测清单**（必跑，结果写到 commit description）：

1. ✅ §7.1 TUI 启动（正常 TUI + CLI 降级）
2. ✅ §7.2 文件树浏览（展开 / 收起 / Enter 插入引用）
3. ✅ §7.3 代码高亮（Java / Python / Bash）
4. ✅ §7.4 对话历史可视化（`Ctrl+P` 恢复历史对话）
5. ✅ §7.5 流式输出适配（实时追加到 ChatPane，50ms 节流有效）
6. ✅ §7.6 配置管理面板（HITL 切换 + 主题切换）
7. ✅ §7.7 TUI 快捷键（`Ctrl+\` / `Ctrl+R` / `Ctrl+O`）
8. ✅ §7.8 主题切换（深色 / 浅色即时生效）
9. ✅ §7.9 HITL 弹窗（TUI 模态框，`y/n/s/m` 响应）
10. ✅ §7.10 降级边界（`NO_TUI=true` / 小终端）
11. ✅ `mvn clean package` 产出 fat jar，本地 `java -jar` 直接运行
12. ⏭️ GitHub Actions Release workflow 留给后续分发增强

任何一项 fail 回 Day 1-5 修。手测过完才能 commit。

---

## 10. 测试策略

### 单测覆盖下限

- `TuiBootstrapTest` ≥ 3（`shouldUseTui()` 分支覆盖）
- `LanternaWindowTest` ≥ 2（主题 / 大小监听）
- `FileTreePaneTest` ≥ 5（懒加载 / 展开 / 收起 / 忽略规则 / 过滤搜索）
- `InputBarTest` ≥ 3（Enter 提交 / 空输入 / Esc 清空）
- `CodeHighlighterTest` ≥ 4（Java / Python / Bash / JSON）
- `ChatPaneRenderTest` ≥ 5（消息分色 / Markdown 渲染 / 工具结果 / 代码块）
- `SyntaxThemeTest` ≥ 2（深色 / 浅色）
- `TuiHitlDialogTest` ≥ 3（显示 / 按键 / 关闭）
- `ConfigEditorTest` ≥ 3（编辑 HITL / 主题 / API Key 隐藏）
- `ThemeManagerTest` ≥ 2（切换 / 持久化）
- `ConversationHistoryManagerTest` ≥ 4（保存 / 恢复 / 删除 / 列表）
- `ConversationStoreTest` ≥ 3（JSONL 写入 / 读取 / 按天分文件）
- `ResponsiveLayoutTest` ≥ 2（窗口尺寸 < 120 / < 90）

### 集成测（可选）

- 标记 `@EnabledIfEnvironmentVariable("TUI_INTEGRATION_TEST", "true")`
- CI 默认不跑（Lanterna 需要真实终端）

### 端到端（Day 6 手测清单 12 条）

必跑，结果写到 commit description 或 PR body。

---

## 11. 明确不做（留给后续期次）

- TUI 内嵌代码编辑器（只能看，不能写；改文件走 `write_file`）
- TUI 内嵌终端仿真器（命令输出渲染在对话流，不搞独立 widget）
- 文件树 Git 状态标记（绿勾 / 红叉 / 蓝点）
- 文件树右键菜单（重命名 / 删除 / 新建文件）
- 多文件选择（只支持单选 Enter）
- 多窗口 / 分屏
- 插件系统 / TUI 主题市场
- 安装包分发（CI/CD 范畴，第 16 期只做可执行 fat jar，Release workflow 留作后续增强）
- TUI 内嵌 RAG 检索结果高亮（`search_code` 结果在对话流内正常渲染即可）
- TUI 内嵌 Skill 浏览器（`/skill list` 在 CLI 模式仍可用）
- 真正的代码补全（Tab 只补 `/` 命令和 `@` resource，不做代码补全）

实现过程中如果发现某条不做的功能其实绕不过去，**先停下来回上游确认**，不要擅自扩展范围。

---

## 12. Banner 版本

完成第 16 期后：
- `Main.java` `VERSION` = `"16.0.0"`
- 类注释：第 16 期新增 TUI 界面（Lanterna 3）、文件树浏览、代码高亮、对话历史可视化、配置管理面板
- Banner 标语：`Terminal-First Agent IDE`

---

## 13. 完成判定（DoD）

- [x] `com.paicli.tui` 包落地，`Main.java` 接入 TUI / CLI 分支
- [x] `shouldUseTui()` 默认返回 CLI；显式 `PAICLI_TUI=true` / `-Dpaicli.tui=true` 才检查 TUI 条件；`NO_TUI=true` 和小终端降级
- [x] 三栏布局渲染：文件树 + 对话流 + 状态栏 + 底部输入栏
- [x] TUI 输入桥接真实 Agent runtime：普通输入走 ReAct，`/plan <任务>` 走 Plan-and-Execute，`/team <任务>` 走 Multi-Agent
- [x] TUI 支持核心命令：`/clear`、`/context`、`/memory`、`/memory clear`、`/save <事实>`、`/hitl`、`/hitl on`、`/hitl off`、`/config`、`/cancel`、`/exit`
- [x] `CodeHighlighter` 支持 Java / Python / TypeScript / Bash / JSON / Markdown 等常见语言，`CenterPane` 可渲染高亮代码块
- [x] HITL 弹窗 TUI 模态框（Lanterna），CLI 模式保留原有 `TerminalHitlHandler`；TUI 不默认批准危险操作
- [x] 对话历史写入 `~/.paicli/history/session_*.jsonl`
- [x] `pom.xml` 添加 Lanterna 依赖，`mvn clean package` 产出可执行 fat jar（含 Lanterna 依赖）
- [x] Banner 升 v16.0.0，标语 `Terminal-First Agent IDE`
- [x] `mvn test` 全绿（482 tests）
- [x] 日常验证入口：`mvn test -Pphase16-smoke`（只跑第 16 期终端 / TUI / inline renderer 冒烟）
- [x] `AGENTS.md` / `README.md` / `ROADMAP.md` 联动完成

当前取舍：第 16 期先交付可运行 TUI 主链路与安全审批闭环；主题市场、完整历史恢复 UI、代码块折叠状态机、安装包 Release workflow 等保留为后续产品化增强，不作为本期阻塞项。

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
| TUI 框架 | **Lanterna 3**（当前 v3.1.3） |
| 布局 | **三栏**（文件树 25% + 对话流 65% + 状态栏 10%） |
| 代码高亮引擎 | **自研正则词法着色**（不引入第三方高亮库） |
| TUI vs CLI | **CLI 为主，TUI opt-in**（`PAICLI_TUI=true` 或 `-Dpaicli.tui=true`） |
| 文件树 Git 状态 | **不做**（留后续期） |
| TUI 内嵌编辑器 | **不做** |
| 多窗口 / 分屏 | **本期不做** |
| 主题 | **深色 / 浅色两种**，Catppuccin 调色板，`config.json` 持久化 |
| 对话历史 | **新设计 `ConversationSnapshot`**，`~/.paicli/history/` JSONL 持久化 |
| HITL 弹窗 | **TUI 模态框**（Lanterna）+ CLI 弹窗（原有 `TerminalHitlHandler`）双路 |
| 流式输出节流 | **50ms**（`ScheduledExecutorService.scheduleAtFixedRate`） |
| 代码块折叠阈值 | **20 行** |
| 对话流保留消息数 | **最近 200 条**（历史归档到 `ConversationStore`） |
| 文件树忽略规则 | `~/.paicli/filetree-ignore.txt`（每行一个 glob），不存在用内置默认 |
| 安装包分发 | **fat jar**（`maven-shade-plugin`），GitHub Actions Release workflow 留作后续增强 |
| `CodeChunker` 无后缀文件识别 | **内置扩展表**（`Makefile` / `Dockerfile` / `Jenkinsfile` 等） |
| 第 17 期范围 | **按 ROADMAP 更新为 LSP 诊断注入**，TUI 不碰；图片复制粘贴输入后移第 21 期 |
| Lanterna 线程安全 | **所有 GUI 操作走 `screen.getEventThread().postRunnable()`** |
| Windows ConEmu / Cmder 颜色 fallback | **检测到非 TrueColor 终端降级为 16 色** |

---

## 16. 必跑端到端用例：文件树 + 代码高亮 + 对话历史

这是**第 16 期最关键的端到端用例**，因为它同时验证：
- TUI 三栏布局完整渲染
- 文件树懒加载 + `Enter` 插入引用工作流
- 代码高亮与对话流渲染正确
- 对话历史保存 / 恢复工作流
- HITL 弹窗 TUI 模态框正确

**前置**：
- Lanterna 3.1.3 依赖已引入
- `~/.paicli/config.json` 已存在（第 15 期已有）
- `PAICLI_TUI=true`
- 终端尺寸 ≥ 80×24

**测试场景序列**：

### 场景 A：文件树 + 代码高亮 + 对话流渲染

```
> 帮我看下 src/main/java/com/paicli/agent/Agent.java 里的 Agent 类主要做了什么
```

**期望行为序列**：
1. Agent 收到问题，调 `search_code` 或直接 `read_file` 读取 `Agent.java`
2. `read_file` 结果返回（约 500 行）
3. `ChatPane` 渲染 `read_file` 结果时，`CodeHighlighter` 识别 Java 语法，代码块有颜色（关键字蓝色 / 字符串绿色 / 注释灰色）
4. 长代码块（> 20 行）自动折叠，显示 `... (按 Enter 展开)` 提示
5. `Enter` 展开代码块，显示完整内容

**失败模式**：
- 代码块无颜色（说明 `CodeHighlighter` 没接上或 `SyntaxTheme` 配置错）
- 代码块不折叠（阈值判断错）
- 折叠后 `Enter` 不展开（折叠状态机错）

### 场景 B：对话历史保存 / 恢复

```
> 帮我创建一个 demo 项目
[Agent 执行完成，对话流显示完整对话]

> Ctrl+P
[弹出历史对话列表，显示"帮我创建一个 demo 项目"摘要]
> Enter
[恢复该对话，对话流重新渲染]

> 帮它加个 README
[Agent 基于恢复的上下文继续执行]
```

**期望行为序列**：
1. 第一次对话执行完后，`ConversationHistoryManager` 自动保存快照
2. `Ctrl+P` 打开历史列表，显示该对话摘要 + 时间 + 模型
3. `Enter` 恢复，`ChatPane` 重新渲染对话流
4. Agent 收到恢复的上下文后继续执行

**失败模式**：
- 历史列表为空（`ConversationStore.save()` 没调用）
- 恢复后对话流渲染错乱（快照序列化 / 反序列化错）
- Agent 拿到恢复上下文后行为异常（消息历史配对错）

### 场景 C：TUI 降级 + HITL 弹窗

```
> java -jar target/paicli-1.0-SNAPSHOT.jar
[默认 CLI 模式]

> /hitl on
> 执行: execute_command "echo test"
[终端内 HITL 审批提示（CLI 模式）]

> y
[命令执行]

> Ctrl+C
[退出程序]
```

**期望行为序列**：
1. 未显式设置 `PAICLI_TUI=true` → `shouldUseTui()` 返回 `false`
2. 不弹 Lanterna 全屏窗口
3. HITL 弹窗走 `TerminalHitlHandler` 原有路径，行为不变
4. `Ctrl+C` 正常退出

---

如果有任何疑问，回到上游问，不要自行推断。祝顺利。
