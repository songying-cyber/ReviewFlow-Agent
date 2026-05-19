# Inline TUI v16.1 端到端手测清单

> 配套 [`docs/inline-tui-pivot.md`](inline-tui-pivot.md) 的 §8 手测清单具体化。
> 每条用例自包含——给出启动命令 + 输入提示词 + 预期表现 + 通过判定。
> 失败现象优先说"看到了什么"而不是"猜原因"。

---

## 0. 启动前置

```bash
cd /Users/itwanger/Documents/GitHub/paicli
mvn -q clean package -DskipTests
```

产物：`target/paicli-1.0-SNAPSHOT.jar`，所有用例都基于这个 jar 启动。

清理副作用：跑完后用 `rm hello*.txt a.txt b.txt c.txt renamed.txt hello-test.txt 2>/dev/null` 清掉测试文件。

---

## 1. 默认 inline 模式启动（用例 1）

**启动**：
```bash
java -jar target/paicli-1.0-SNAPSHOT.jar
```

**预期**：
- Banner 显示 `Terminal-First Agent IDE v16.1.0`
- 进入 JLine REPL，提示 `👤 你: `
- **不进 alternate screen**（退出后 banner、对话历史还留在 terminal scrollback 里）
- 底部最后一行显示反色状态栏（用例 15 详细验证）

**失败迹象**：
- 整屏被清空 → 误进了 alt screen
- 输出乱码 / 没颜色 → 终端不支持 ANSI，应该看到 stderr `⚠️ 终端不支持 ANSI`

---

## 2. 折叠块 — 单工具组（用例 2）

**输入**：
```
帮我读一下 README.md 的前 50 行
```

**预期**：Agent 调 `read_file` 时输出**单行折叠态**：
```
⏵ 读取 1 个文件 (ctrl+o to expand)
```

按 **Ctrl+O**（不需要回车）：
- 上方一行被覆盖
- 展开为多行：`📖 读取 1 个文件` + `└ README.md` + `⏷ collapse (ctrl+o)`

再按 **Ctrl+O**：折回单行。

**通过判定**：
- 默认就是折叠态（不是直接展开整段）
- Ctrl+O 切换不破坏后续输出
- 切换无颜色撕裂

---

## 3. 折叠块 — 多工具组（用例 3）

**输入**：
```
同时读取 pom.xml、AGENTS.md、ROADMAP.md 这三个文件
```

**预期**：单行折叠态：
```
⏵ 读取 3 个文件 (ctrl+o to expand)
```

Ctrl+O 展开后看到三个文件路径都在 `└` 缩进列表里。

**注意**：Agent 必须在**同一轮**返回 3 个 tool call 才会聚合成一个折叠块。如果 Agent 拆成 3 轮（每轮 1 个），会出现 3 个独立折叠块——这是 LLM 的决策，不是 bug。

---

## 3.5 LLM 代码块自动折叠

**输入**：
```
帮我看一下 README.md，把内容用 markdown 代码块包起来给我
```

**预期**：LLM 流式输出过程中，`┌─ code: markdown` 出现后，body 部分**不会持续刷屏**；
等代码块结束时，原 header 被覆盖为：
```
⏵ code: markdown (52 行, ctrl+o to expand)
```

按 **Ctrl+O** 展开成完整 `┌─ code: markdown / ... body ... / └─ end / ⏷ collapse (ctrl+o)`。再按 Ctrl+O 折回。

**通过判定**：
- 流式期间 body 行不在终端可见（避免大段刷屏）
- 折叠头 N 行计数和真实 body 行数一致
- 展开 / 收起切换不破坏后续 LLM 输出

**已知瑕疵**：代码块流式期间按 Ctrl+O 折叠"上一个"块可能会让本代码块视觉错位；下次输入或 `/clear` 后恢复。

---

## 4. 折叠块 frozen 行为

**步骤**：
1. 跑完用例 2 / 3 任一个，记录最近的折叠块
2. 等 LLM 流式输出后续回复（"📖 我看到 README 内容如下…"）
3. 在 LLM 输出**之后**按 Ctrl+O

**预期**：Ctrl+O 不会动到上面那个旧折叠块（已 frozen），只会作用于**最近一个**新生成的折叠块。如果当前没有新的活跃块，按了无反应。

**通过判定**：旧折叠块保持原样，没有错位 / 重影。

---

## 5. HITL 单字符审批 — 批准（用例 4）

**前置**：
```
/hitl on
```

**输入**：
```
帮我在项目根目录创建一个 hello.txt，内容写 "hi"
```

**预期**：
```
⚠️  HITL 审批
┌─...┐
│ ⚠️  需要审批
│ 工具: write_file
│ 参数: ...
└─...┘
> [y] approve  [a] all  [n] reject  [s] skip  [m] modify
```

按 **`y`**（不要回车），立刻看到 `y` 回显 + 下一行继续执行。

**通过判定**：单字符响应，不需要回车；HITL 框紧凑、一屏内可见。

---

## 6. HITL 单字符审批 — 拒绝带原因（用例 5）

**输入**：
```
帮我在项目根目录创建一个 dangerous.sh，内容写 "rm -rf /"
```

按 **`n`**，立刻提示：
```
拒绝原因（可直接回车跳过）:
```
输入 `太危险了` 回车。

**通过判定**：
- 拒绝原因输入是 readLine（多字符），不是单字符
- Agent 收到 REJECTED 信号后回复"已拒绝"或重新规划

---

## 7. HITL 单字符审批 — 全部放行（用例 6）

**输入**：
```
帮我连续创建三个文件 a.txt b.txt c.txt，内容分别写 1 2 3
```

第一次提示按 **`a`**，应看到：`已批准 tool 范围`。

**预期**：后续两次 `write_file` 自动跳过 HITL 提示（`[HITL] write_file 已在本次会话中全部放行，自动通过`）。

**通过判定**：a.txt / b.txt / c.txt 都在项目根创建成功；只第一次 HITL 拦了。

---

## 8. HITL 单字符审批 — 修改参数（用例 7）

**输入**：
```
帮我创建一个 wrongname.txt 写入 "test"
```

按 **`m`**，提示：
```
当前参数: {"path":"wrongname.txt","content":"test"}
修改后的 JSON（空行 = 保留原参数）:
```
输入 `{"path":"renamed.txt","content":"modified"}` 回车。

**通过判定**：实际创建的是 `renamed.txt`，内容是 `modified`；`wrongname.txt` 不存在。

---

## 9. HITL 单字符审批 — 跳过（用例 8）

**前置**：`/hitl on`

**输入**：
```
帮我执行 echo hello
```

按 **`s`**。**预期**：Agent 收到 SKIPPED 信号，命令不执行。

---

## 10. 形态切换 — Lanterna 全屏（用例 9）

```bash
PAICLI_RENDERER=lanterna java -jar target/paicli-1.0-SNAPSHOT.jar
```

**预期**：进 alternate screen，三栏 Lanterna 窗口（文件树 + 对话流 + 状态栏 + 底部输入栏）。

- 在输入栏问问题，输出在中间对话流面板
- HITL 弹 Lanterna 模态框（Yes / No / Cancel 按钮）
- 退出后 scrollback 干净（alt screen 特性）

**通过判定**：UI 完全替换为窗口形态，不是 inline 滚动。

---

## 11. 形态切换 — 兼容旧 PAICLI_TUI（用例 10）

```bash
PAICLI_TUI=true java -jar target/paicli-1.0-SNAPSHOT.jar
```

**预期**：
- stderr 出现 `⚠️ PAICLI_TUI=true 已废弃，请改用 PAICLI_RENDERER=lanterna`
- 行为等同用例 10（Lanterna 全屏）

---

## 12. 形态切换 — Plain 兜底（用例 11）

```bash
PAICLI_RENDERER=plain java -jar target/paicli-1.0-SNAPSHOT.jar
```

**输入**：
```
读取 ROADMAP.md
```

**预期**：
- 工具调用输出**直接展开**（`📖 读取 1 个文件 / └ ROADMAP.md`），**没有 ⏵ 折叠提示**
- HITL 提示是多行 readLine 风格（`> ` 后回车确认），不是单字符
- 没有底部状态栏

**通过判定**：等价 v15 行为。

---

## 13. 禁色（用例 12）

```bash
NO_COLOR=1 java -jar target/paicli-1.0-SNAPSHOT.jar
```

**预期**：
- 所有 ANSI 颜色消失（"思考过程"标题、subtle 灰、emphasis 粗体都退化为纯文本）
- 但布局结构（折叠头、`⏵`/`⏷` 符号、`@@` hunk 头）保留
- 状态栏没了反色背景但仍占最后一行

---

## 14. 未知 RENDERER 值的回退（用例 13）

```bash
PAICLI_RENDERER=weird java -jar target/paicli-1.0-SNAPSHOT.jar
```

**预期**：stderr `⚠️ 未识别的 PAICLI_RENDERER='weird'，回退到 inline`，正常进 inline 模式。

---

## 15. dumb 终端自动降级（用例 14）

```bash
TERM=dumb java -jar target/paicli-1.0-SNAPSHOT.jar
```

**预期**：stderr `⚠️ 终端不支持 ANSI，inline 模式回退到 plain`，行为等同用例 12。

---

## 16. 底部状态栏数据可见（用例 15）

**启动**：默认 inline 模式

**输入**：
```
帮我读 README.md
```

**预期**：发送后，**底部最后一行实时显示**反色状态栏：
```
 glm-5.1 │ 0/200.0k │ HITL OFF │ 12ms 
```

- 模型名跟当前 `/model` 一致
- token 计数在 LLM 响应回来后跳动（如 `1.3k/200.0k`）
- elapsed 持续增长（200ms 节流刷新）
- HITL 列反映 `/hitl on/off` 状态

**通过判定**：状态栏不被对话内容滚走（DECSTBM 滚动区域生效）；输入文字时不与状态栏冲突。

**已知小瑕疵**：JLine 输入瞬间状态栏可能闪一下，是 200ms 节流和光标移动的竞态，不影响功能。

---

## 17. HITL 状态联动状态栏（用例 16）

**步骤**：
1. 启动后状态栏显示 `HITL OFF`
2. 输入 `/hitl on`
3. 再问任何问题（如 `列一下 src/main 目录`）

**预期**：下一次 LLM 调用后状态栏立刻刷新为 `HITL ON`。

---

## 18. 行内 diff — 修改已有文件（用例 17）

**前置**：`/hitl on`

**输入**：
```
把 ROADMAP.md 第一行改成 "# PaiCLI 路线图（v16.1 测试）"
```

HITL 按 `y` 通过。**预期**对话流里出现：

```
📝 ROADMAP.md
@@ -1,N +1,N @@
- # PaiCLI ROADMAP（原标题）
+ # PaiCLI 路线图（v16.1 测试）
  ...（上下文行）
```

- `-` 行红色背景
- `+` 行绿色背景
- `@@` 行号青色

**通过判定**：diff 出现在工具结果之后；改回原文也能正确 diff。

**清理**：
```
git checkout ROADMAP.md
```

---

## 19. 行内 diff — 新建文件（用例 18）

**输入**：
```
帮我创建一个 hello-test.txt，内容写 "line 1\nline 2"
```

HITL 通过后**预期**：
```
📝 hello-test.txt
@@ -0,0 +1,N @@
+ line 1
+ line 2
```

**清理**：`rm hello-test.txt`

---

## 20. 行内 diff — 二进制 / 大文件 fallback

**步骤**：
1. 让 Agent 用 `write_file` 写入超大内容（比如 10000 行的列表）
2. 不会触发，因为 5MB 上限会先拒绝

**改测**：让 Agent 覆盖一个二进制文件（不太可能由 LLM 自然触发）。

**预期**：如果 before 内容读不出（二进制 / 编码错），diff 会按"新建文件"形态展示——这是 ToolRegistry.write_file lambda 里 `before == null` 的兜底分支。

---

## 21. /config palette — inline 模式（用例 19）

**输入**：
```
/config
```

**预期**：浮起选择列表：
```
┌─ 配置 / config ─
│ ▶ [1] 模型: glm-5.1 / zhipu
│   [2] 默认 Provider: glm
│   [3] HITL: OFF
│   [4] Skill 启用数: 3
│   [5] 渲染器: InlineRenderer
│   [6] 配置文件: ~/.paicli/config.json (只读视图，编辑请用编辑器)
└─ ↑↓ 切换  Enter 确认  Esc 取消  数字键直选
```

测试交互：
- **↑↓** 移动选中（`▶` 跟随）
- **j / k** 同上
- **数字 `3`** 直选第 3 项（HITL）
- **Enter** 确认当前选中
- **Esc** / **q** 取消

每种确认后：
- palette 应**完全消失**（用 `[<n>A[J` 清屏到末尾）
- 对话流出现一行提示：`💡 切换 HITL: /hitl on / /hitl off` 等
- 取消则出现 `(已关闭)`

**通过判定**：palette 不留痕迹；后续输入照常。

---

## 22. /config palette — Lanterna 模式（用例 20）

```bash
PAICLI_RENDERER=lanterna java -jar target/paicli-1.0-SNAPSHOT.jar
```

TUI 输入框输入 `/config`。

**预期**：弹出 Lanterna 模态选择列表（不是 inline 浮起 palette），选项内容相同。选中后 inline 模式下的提示文案应该出现在 CenterPane（对话流）中。

---

## 23. PAICLI_NO_STATUSBAR 禁用底部状态栏

```bash
PAICLI_NO_STATUSBAR=true java -jar target/paicli-1.0-SNAPSHOT.jar
```

**预期**：
- 折叠块、HITL 单字符、`/config` palette 等其它 inline 特性照常
- 但底部最后一行**没有反色状态栏**（因为 DECSTBM 滚动区域没设置）
- 输入提示 / Agent 输出可以一直占满整屏，不会被状态栏占位压缩

---

## 24. 终端尺寸过小自动降级

**手动操作**：把终端窗口拖到 `< 5 行` 或 `< 20 列`。

```bash
java -jar target/paicli-1.0-SNAPSHOT.jar
```

**预期**：`TerminalCapabilities.supportsScrollRegion` 返回 false，inline 模式禁用状态栏，但其它特性不变。

---

## 25. Ctrl+C 中断与状态栏

**步骤**：
1. 默认 inline 模式启动
2. 输入一个长任务：`帮我把 ROADMAP.md 全文逐行翻译成英文`
3. LLM 流式输出中按 **Ctrl+C**

**预期**：
- 任务取消，对话流出现 `⏹️ 已请求取消当前任务。`
- 状态栏 `elapsed` 停止刷新
- 提示符回到 `👤 你: `
- 整体不卡死

---

## 26. /clear 清屏不影响状态栏

**输入**：
```
/clear
```

**预期**：
- 对话历史清空，但状态栏仍在最后一行
- 之前注册的折叠块（如果还活跃）也应清干净

---

## 27. 退出清理

按 Ctrl+D 或输入 `/exit`。

**预期**：
- 状态栏被清掉（DECSTBM 还原）
- 输出 `👋 再见!`
- 进程正常退出
- terminal scrollback 里完整保留对话历史（inline 模式特性）
- Lanterna 模式则没有这个保留（alt screen 特性）

---

## 通过总判据

每条用例都满足"预期"且没有"失败迹象"，则 v16.1 inline TUI 改造视为通过。

**已知不在测试范围**：
- 流式输出与状态栏并发刷新的撕裂（极小概率，肉眼几乎不可见，不阻断任务）
- 终端 resize 后状态栏不会自动重新设置滚动区域（再次 `/clear` 或重启可恢复）
- 旧 Windows cmd / PuTTY 等不支持 DECSTBM 的终端：用 `PAICLI_NO_STATUSBAR=true` 即可

---

## 测试运行记录模板

跑完一遍后填到 commit message 或 PR body：

```
v16.1 inline TUI 端到端测试结果（YYYY-MM-DD 终端：iTerm2 / macOS Terminal / xxx）：

✅ 1 默认 inline 启动
✅ 2 折叠块单工具组
✅ 3 折叠块多工具组
✅ 4 折叠块 frozen 行为
✅ 5 HITL y
... （列举每条）
❌ N 行内 diff 修改已有文件 — 看到 X，期望 Y
```

任何 ❌ 项需要 issue 跟进，不能直接 ship。
