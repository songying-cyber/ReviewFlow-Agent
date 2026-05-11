# 第 15 期开发任务：Skill 系统 + 内置 web-access Skill

> 这份文档是给执行 Agent 的开发任务说明书，自包含、可直接照着推进。
>
> **开工前必读**：
> 1. 仓库根 `AGENTS.md`（仓库规则、文档联动硬规则）
> 2. `docs/phase-13-chrome-devtools-mcp.md` / `docs/phase-14-cdp-session-reuse.md`（已完成，本期用浏览器能力）
> 3. `src/main/java/com/paicli/agent/Agent.java`、`PlanExecuteAgent.java`、`SubAgent.java`（系统提示词构造路径）
> 4. `src/main/java/com/paicli/tool/ToolRegistry.java`（本期新增内置工具 `load_skill` 的注册位置）
> 5. `src/main/java/com/paicli/cli/Main.java` / `CliCommandParser.java`（本期新增 `/skill` 命令组）
> 6. 参考品：Anthropic 公开的 Claude Code skills（`~/.claude/skills/web-access/` 是用户本机已有的优秀范例，开发 Agent 可读取参考结构与文风，但**不能直接搬代码**——许可与工程化口径都不一样）
>
> **核心原则**：本期不是写工具，是把「Agent 该怎么思考」沉淀成可复用单元。Skill 是**决策手册** + **按需取用的资料库** + **可执行依赖**三件套，不是 prompt 片段。
>
> **必读判断准则**：实现过程中若 Skill 内容看起来像「LLM 系统提示词的另一种表达」，方向就错了。Skill 的核心价值在 references/ 的可累积、scripts/ 的可执行、SKILL.md 的决策框架——三者不可分开。

---

## 1. 目标与产出物

让 PaiCLI 拥有自己的 Skill 体系。Skill 是一个目录（含 `SKILL.md` + 可选 `references/` + 可选 `scripts/`），其中：

- `SKILL.md` 是 Agent 的决策手册（哲学 + 工具选择表 + 优先级 + 兜底策略）
- `references/` 是按需读取的资料库（典型用法：站点经验目录 `references/site-patterns/<domain>.md`）
- `scripts/` 是可执行依赖（通过 `execute_command` 调用，沿用既有 HITL 流程）

最终交付：

- 通用 Skill 加载器：扫描三层目录（jar 内置 / 用户级 / 项目级），解析 frontmatter 与正文
- Skill 启用状态持久化：`~/.paicli/skills.json` 存 disabled 列表（默认全启用）
- 启动期把所有启用 Skill 的 `name` + `description` 注入三处 Agent 系统提示词
- 新增内置工具 `load_skill(name)`：LLM 主动调用以把 SKILL.md 正文注入下一轮上下文（lazy 展开）
- CLI 命令组 `/skill`：`list` / `show <name>` / `on <name>` / `off <name>` / `reload`
- 内置一个 Skill：`web-access`（决策手册 + 5–8 个 site-patterns + 0 个 scripts）
- 文档联动（AGENTS.md / README.md / ROADMAP.md / .env.example）
- Banner 升 `v15.0.0`，标语 `Skill-Driven Agent CLI`

**明确不做**（拆给后续期次或永远不做）：
- 改 `web_fetch` 工具内部链路（**不**做"本地 → Jina → 浏览器"三档级联，违背第 9 期纯本地约定）
- Skill 间依赖声明（`requires` 字段，YAGNI）
- Skill marketplace / 远程下载 / 自动更新
- 把 Skill body 全部一次性注入 system prompt（token 成本失控）
- 把 scripts/ 注册成虚拟工具（变相绕开 HITL）
- 跨 Agent 角色的 Skill 路由差异化（ReAct / Plan / Team 共享同一套启用列表）
- 给 Skill 单独的"全部放行"维度（沿用 `execute_command` / MCP 既有 HITL 维度即可）
- Skill 内嵌 LLM 调用 / sub-agent 编排
- Markdown 之外的格式（YAML / JSON / TOML 写 SKILL.md，本期只支持 Markdown frontmatter）

---

## 2. 关键概念定义

### 2.1 Skill 是什么

| 维度 | 定义 |
|---|---|
| 形态 | 一个目录，根文件必须是 `SKILL.md` |
| 加载顺序 | jar 内置 < 用户级 `~/.paicli/skills/` < 项目级 `<project>/.paicli/skills/`（同名后者覆盖前者） |
| 启用控制 | 默认启用；`~/.paicli/skills.json` 的 `disabled` 列表关掉 |
| 系统提示词占位 | 启用后只把 `name` + `description` 进 system prompt（"轻量索引"） |
| 完整指令展开 | LLM 调 `load_skill("name")` 后，PaiCLI 把 SKILL.md 正文（去 frontmatter）拼到 next user message 的开头 |

### 2.2 SKILL.md 格式

```markdown
---
name: web-access
description: |
  所有联网操作必须通过此 skill 处理，包括搜索、网页抓取、登录后操作。
  触发场景：用户要求搜索信息、查看网页、访问需登录站点、抓取社交媒体内容、读取动态渲染页面。
version: "1.0.0"
author: PaiCLI
tags: [web, browser, fetch]
---

# 决策手册正文（任意 markdown）
...
```

**字段约定**：
- 必填：`name`（kebab-case，与目录名一致），`description`（≤ 500 字符，超出截断并 stderr 警告）
- 选填：`version`、`author`、`tags`（数组）
- 不允许：`license`、`github`、`metadata.*` 嵌套、其他未声明字段（**忽略而非报错**，向前兼容）

### 2.3 Skill 命中机制

**默认路径**：description 列表注入 system prompt，LLM 自决判断；判定要用时调 `load_skill(name)`。

**显式路径**：用户输入 `/skill use <name>`（不在 §3.4 的命令清单里——见 §3.4）→ 实际改用更直观的 **「下一轮自动注入」机制**：用户调用 `/skill show <name>` 后下一轮 user message 末尾自动追加该 skill 的 body 提示「请参考以下指引」。本期**不做**专门的 `use` 子命令，需要强制时让用户在 prompt 里写「请按 web-access skill 的方法...」即可，LLM 会主动调 `load_skill`。

### 2.4 与 system prompt 的关系（关键架构）

```
┌──────────────────────────────────────────────┐
│ system prompt（每轮固定部分）                 │
│ ├─ Agent 角色定义                            │
│ ├─ 工具集说明                                │
│ ├─ 安全策略                                  │
│ ├─ MCP 工具列表                              │
│ └─ 【新】Skill 索引（name + description × N）│
└──────────────────────────────────────────────┘

┌──────────────────────────────────────────────┐
│ user message（每轮变化）                     │
│ ├─ 【新】上一轮 load_skill 注入的 body       │
│ │  > 你曾加载 skill: web-access              │
│ │  > <SKILL.md 正文>                         │
│ └─ 用户实际输入                              │
└──────────────────────────────────────────────┘
```

注入位置选 user message 而非 system，理由：
- system prompt 在多轮对话里只发一次（节省 token），但本期 LLM 自决加载是**每轮可能不同**的
- user message 是每轮重发，注入 body 会随轮次自然衰减（PaiCLI 控制只保留最近 1 轮的注入，避免堆积）
- system prompt 不变 → prompt cache 命中率高（第 12 期能力依赖）

---

## 3. 关键设计决策（务必遵守）

### 3.1 加载位置 + 优先级覆盖

三层目录扫描顺序（后者覆盖前者同名 skill）：

1. **内置**：jar 内 `src/main/resources/skills/<name>/SKILL.md`
2. **用户级**：`~/.paicli/skills/<name>/SKILL.md`
3. **项目级**：`<project>/.paicli/skills/<name>/SKILL.md`

实现要点：
- jar 内置用 `getResourceAsStream`，把 `SKILL.md` 拷到内存即可（不解压到磁盘）
- 用户级 / 项目级用 `Files.walk(maxDepth=2)`
- 同名按"完整 skill 替换"覆盖（不是字段级 merge），便于用户整体重写
- 启动期日志：`📚 Skill: web-access (project)`、`📚 Skill: web-access (user, override builtin)`

### 3.2 SKILL.md 解析

frontmatter 用 SnakeYAML（项目已经依赖 Jackson + 不依赖 SnakeYAML，**为这一项新增依赖不划算**）。改用极简手写 frontmatter 解析：

- 文件以 `---\n` 开头 → 读到下一个 `---\n` 之间作为 YAML
- frontmatter 体内只支持以下语法（对齐 95% 实际写法）：
  - `key: value` 单行字符串
  - `key: |\n  value\n  value`（多行字符串，用首行缩进推断）
  - `key: [a, b, c]` 单行数组
  - 不支持：嵌套对象、引用、anchor、复杂 YAML 类型
- 解析失败 → stderr 警告 + 跳过该 skill，不阻塞启动
- 多行 description 的换行折叠为空格（避免 system prompt 里出现尴尬换行）

为什么不上 SnakeYAML：本期 frontmatter 字段就 5 个，全是 string / list-of-string，手写 80 行解析够用；引依赖会带 90KB jar 体积。

### 3.3 启用状态持久化

`~/.paicli/skills.json`：

```json
{
  "disabled": ["some-skill-name"]
}
```

- 默认全启用
- 启用列表不持久化（避免新加 skill 时被遗漏）
- `/skill off <name>` 写入 disabled 列表
- `/skill on <name>` 从 disabled 列表移除
- 文件不存在 → 视为空 disabled
- 解析失败 → stderr 警告 + 视为空 disabled，不阻塞启动

### 3.4 CLI 命令组 `/skill`

| 命令 | 行为 |
|---|---|
| `/skill` | 等价 `/skill list` |
| `/skill list` | 列出所有发现的 skill：`name / 来源(builtin/user/project) / 启用状态 / description 摘要前 80 字` |
| `/skill show <name>` | 打印 SKILL.md 全文（含 frontmatter） |
| `/skill on <name>` | 启用 skill，更新 `skills.json` |
| `/skill off <name>` | 禁用 skill，更新 `skills.json` |
| `/skill reload` | 重新扫描三层目录，重建 skill 表，刷新三处 system prompt 的 skill 索引 |

**约束**：
- `/skill on` 对未发现的 name 报错，不创建空记录
- `/skill reload` 不影响当前轮次：当前轮 LLM 已发出的请求继续用旧 system；下一轮才用新表
- `/skill list` 输出按 name 字典序排列，不按发现顺序

### 3.5 `load_skill` 内置工具（lazy 展开机制）

新增内置工具，与 `read_file` / `web_search` 等同级：

```json
{
  "name": "load_skill",
  "description": "Load full SKILL.md instructions for a skill the system has indexed. Use when a skill's description in your system prompt looks relevant to the current task. Pass the exact skill name (kebab-case).",
  "parameters": {
    "name": "string (required) — exact kebab-case skill name"
  }
}
```

工具行为：
- 校验 name 在已启用 skill 表里 → 不在 → 返回 `Skill 'xxx' 未找到或已禁用，可用 /skill list 查看`
- 读取 SKILL.md 正文（去 frontmatter）
- body 长度限 5KB，超出截断并附 `(skill body truncated, full content via /skill show <name>)`
- **不直接返回正文给 LLM 当工具结果**，而是：
  - 工具结果只返回简短确认：`已加载 skill 'web-access' 的完整指引（X bytes），将在下一轮上下文中体现`
  - 把 body 写入 `SkillContextBuffer`（per-session，per-agent-role），下一轮 user message 末尾被 PaiCLI 拼接

为什么这样设计：
- 直接返回正文 → LLM 在当前轮就吃下，但工具结果通常被 LLM 当"事实输入"，不当"指令"
- 走 user message 注入 → 模型把它当"用户附加要求"理解，决策权重更高
- 同时 SkillContextBuffer 只保留**最近 1 轮**注入（防止累积）

### 3.6 SkillContextBuffer 生命周期

```
LLM 调 load_skill("web-access") 
  → ToolRegistry 写入 buffer
  → 下一轮 Agent 构造 user message 时取出并前置：
      "## 已加载 Skill：web-access\n<body>\n\n---\n用户输入：<原内容>"
  → 取出后 buffer 清空（一次性）
  → 如果当轮 LLM 又调 load_skill，新 body 进 buffer，下一轮再注入
```

边界处理：
- 同一轮 LLM 连续调多个 `load_skill` → buffer 累积（按调用顺序拼接），最多保留 3 个 skill body
- ReAct / Plan / Team 各自独立 buffer，不共享
- `/clear` 命令 reset buffer
- buffer 只走内存，不持久化

### 3.7 内置 web-access Skill 的范围（必须严控）

**做**：
- `SKILL.md`（决策手册）：浏览哲学（明确目标 → 选起点 → 过程校验 → 完成判断）；工具选择表（web_search / web_fetch / chrome-devtools MCP / Jina 的取舍）；浏览器优先级（先 web_fetch 试一次 → 失败上 chrome-devtools → 登录态用 `/browser connect` 切 shared 模式）；登录判断准则；并行调研提示（@subagent 拆分任务）
- `references/site-patterns/`：预置 6 个站点经验文件（推荐：`mp.weixin.qq.com.md` / `zhuanlan.zhihu.com.md` / `x.com.md` / `xiaohongshu.com.md` / `github.com.md` / `juejin.cn.md`），每个文件按 `domain / aliases / updated` frontmatter + 「平台特征 / 有效模式 / 已知陷阱」三段式
- `references/cdp-cheatsheet.md`：第 13 期 chrome-devtools-mcp 的 28 工具速查 + 第 14 期 `/browser connect` 用法
- Jina 集成：**只**在 `SKILL.md` 写一段「web_fetch 拿不到正文时，可让 `execute_command` 调 `curl https://r.jina.ai/<url>` 取干净 markdown，限 20 RPM」——**不**改 web_fetch 工具

**不做**：
- **不**复制 `~/.claude/skills/web-access/scripts/cdp-proxy.mjs`（那是 568 行的 HTTP 代理，PaiCLI 第 13 / 14 期已经有 chrome-devtools MCP + shared 模式，能力等价，重写一个反而引入维护负担）
- **不**写 `scripts/` 任何脚本（本期内置版 scripts/ 留空目录或干脆不建）
- **不**预置 5KB 以上的超长 SKILL.md（决策手册要精炼，超长内容拆到 references/）
- **不**抄用户本机 `~/.claude/skills/web-access/SKILL.md` 的具体文风（许可与立场不同，参考结构即可）

**站点经验文件标准模板**（强制三段式）：

```markdown
---
domain: example.com
aliases: [示例, Example]
updated: 2026-05-07
---

## 平台特征
- 架构（SPA / SSR / 静态）
- 反爬强度（低 / 中 / 高）
- 登录态：是否必需 / 是否影响内容质量
- 关键技术事实（懒加载、签名头、token 校验等）

## 有效模式
- 已验证的 URL 模式
- 已验证的选择器或 JS 提取片段（带可执行 snippet）
- 推荐的进入路径

## 已知陷阱
- 失败模式 1 + 原因 + 应对
- 失败模式 2 + 原因 + 应对
- ...
```

### 3.8 system prompt 注入方式（三处一致）

`Agent` / `PlanExecuteAgent` / `SubAgent` 三处构造 system prompt 时，加新段：

```
## 可用 Skills（按需调用 load_skill 加载完整指引）

- **web-access**: 所有联网操作必须通过此 skill 处理，包括搜索、网页抓取、登录后操作...
- **<other-skill>**: ...

判断准则：当任务描述匹配某个 skill 的触发场景时，调用 load_skill(name) 加载完整指引，
然后按指引执行。已加载的 skill 会在下一轮以 "## 已加载 Skill" 段落出现在你的 user message 中。
不要重复加载同一 skill；同一会话内一次足够。
```

**token 预算**：
- 单 skill description ≤ 500 字符（解析时强制截断）
- 启用 skill 数 ≤ 20 个（超出按字典序前 20 注入，stderr 警告）
- 索引段总大小硬上限 4KB，超出截断并 stderr 警告

### 3.9 与 HITL 的协同

Skill 内 SKILL.md 提示 LLM 调用危险工具时（如 `execute_command "curl r.jina.ai/..."`），仍走 `HitlToolRegistry` 既有审批流。本期**不**给 Skill 单独的 HITL 维度，理由：
- skill 维度全放行 = 间接放行 skill 内推荐的所有命令 = 安全模型崩塌
- 用户对 `execute_command` 选 `a → tool` 即可一次性放行所有该工具调用，已经够用
- 沿用既有维度避免审批 UX 的复杂度爆炸

`AuditLog.AuditEntry.metadata` 在第 14 期已加为可选字段。本期**不**新增字段，但建议在 metadata 里补一个 `skill_loaded` 字段（可空，为 user message 当时已注入的 skill name），帮助审计回放——**第二优先级，不影响 DoD**。

### 3.10 启动期与重载期表现

启动期：

```
📚 Skills 加载（3 个）...
   ✓ web-access      builtin   description 88 字符
   ✓ paicli-internal user      description 124 字符
   ✓ project-tweaks  project   description 67 字符
   3/3 启用，索引段共 0.6KB
```

`/skill reload` 后：

```
> /skill reload
🔄 重新扫描 skill 目录...
📚 Skills 加载（4 个）...
   ✓ web-access      builtin   description 88 字符
   ✓ paicli-internal user      description 124 字符
   ✓ project-tweaks  project   description 67 字符
   ✓ new-one         user      description 102 字符
   4/4 启用，索引段共 0.8KB
✅ 已重新加载，下一轮 LLM 调用生效
```

---

## 4. 配置文件改动

### 4.1 `~/.paicli/skills.json`

启动期检测：不存在则视为空，**不**主动创建（与 sensitive_patterns.txt 一致策略）。

格式：

```json
{
  "disabled": ["foo-skill", "bar-skill"]
}
```

`/skill off` / `/skill on` 写入或更新此文件。写入失败 stderr 警告，不阻塞主流程。

### 4.2 `.env.example` 新增

```bash
# ========== 第 15 期：Skill 系统 ==========
# Skill 加载位置（按优先级，后者覆盖前者）：
#   1. jar 内置（resources/skills/）
#   2. 用户级：~/.paicli/skills/<name>/SKILL.md
#   3. 项目级：<project>/.paicli/skills/<name>/SKILL.md
# 禁用列表持久化：~/.paicli/skills.json（{"disabled": ["name1", ...]}）
# 内置 web-access skill 包含 6 个 site-patterns 示例，可在 references/site-patterns/ 下补充自己的
```

### 4.3 内置 skill 资源位置

```
src/main/resources/skills/
└── web-access/
    ├── SKILL.md
    └── references/
        ├── cdp-cheatsheet.md
        └── site-patterns/
            ├── github.com.md
            ├── juejin.cn.md
            ├── mp.weixin.qq.com.md
            ├── x.com.md
            ├── xiaohongshu.com.md
            └── zhuanlan.zhihu.com.md
```

注意：`references/` 内的文件在 jar 内是只读 resources，LLM 通过 `read_file` 读取时需要走特殊路径——本期约定 **`references/` 文件由用户级或项目级 skill 提供**，jar 内置 skill 的 references 在启动期解压到 `~/.paicli/skills-cache/<name>/references/`，后续 LLM 用绝对路径读取。

解压策略：
- 启动期检测 `~/.paicli/skills-cache/<name>/.version` 与 jar 内置版本号是否一致
- 不一致或不存在 → 重写整个 cache 目录
- 一致 → 跳过（避免每次启动 IO）
- SKILL.md 在 `Agent` 启动注入索引时由 jar 内 `getResourceAsStream` 读，不依赖解压

---

## 5. 与现有架构的集成点（要修改 / 新增的文件）

### 5.1 新增类

| 文件 | 职责 |
|---|---|
| `src/main/java/com/paicli/skill/Skill.java` | record，含 name / description / version / author / tags / source(builtin/user/project) / bodyPath / cachedReferencesDir |
| `src/main/java/com/paicli/skill/SkillFrontmatterParser.java` | 极简 YAML 子集解析（§3.2） |
| `src/main/java/com/paicli/skill/SkillRegistry.java` | 单例（由 Main 注入），扫描三层目录、合并、过滤 disabled、对外提供 `enabledSkills()` / `findSkill(name)` / `reload()` |
| `src/main/java/com/paicli/skill/SkillIndexFormatter.java` | 把 enabled skills 渲染成 system prompt 索引段（§3.8 模板，含 token 预算硬上限） |
| `src/main/java/com/paicli/skill/SkillContextBuffer.java` | per-session 内存 buffer（§3.6），提供 push(name, body) / drain() |
| `src/main/java/com/paicli/skill/SkillBuiltinExtractor.java` | 启动期把 jar 内 `resources/skills/<name>/references/` 解压到 `~/.paicli/skills-cache/<name>/`（§4.3） |
| `src/main/java/com/paicli/skill/SkillStateStore.java` | 读写 `~/.paicli/skills.json` |
| `src/main/java/com/paicli/skill/LoadSkillTool.java` | 内置工具实现，调用时往 SkillContextBuffer 写入 |

### 5.2 修改类

| 文件 | 改动 |
|---|---|
| `src/main/java/com/paicli/cli/Main.java` | 启动期初始化 SkillBuiltinExtractor + SkillRegistry + SkillContextBuffer；Banner v15.0.0；启动 hint 加 `/skill list` 提示；`/skill` 命令分发 |
| `src/main/java/com/paicli/cli/CliCommandParser.java` | 新增 `SKILL` 命令类型 + 子命令 `list` / `show <name>` / `on <name>` / `off <name>` / `reload` |
| `src/main/java/com/paicli/tool/ToolRegistry.java` | 注册 `load_skill` 工具；构造时接收 SkillRegistry + SkillContextBuffer 引用 |
| `src/main/java/com/paicli/agent/Agent.java` | system prompt 加 §3.8 skill 索引段；构造 user message 时调 `SkillContextBuffer.drain()` 拼接到原内容前 |
| `src/main/java/com/paicli/agent/PlanExecuteAgent.java` | 同上 |
| `src/main/java/com/paicli/agent/SubAgent.java` | 同上（三种角色都共享同一索引；buffer 在主 Agent 与子 Agent 间不共享，子 Agent 有自己的 buffer 实例） |
| `src/main/java/com/paicli/agent/AgentOrchestrator.java` | 在创建 SubAgent 时为每个 SubAgent 构造独立 SkillContextBuffer |

### 5.3 联动文档

- `AGENTS.md`：项目快照里把第 15 期标已完成；新增「13. Skill 系统」段说明加载顺序、命中机制、`load_skill` 工具、HITL 协同
- `README.md`：新增「第十五期 Skill 系统」段，含 SKILL.md 字段表、`/skill` 命令表、内置 web-access 速览
- `ROADMAP.md`：第 15 期标 ✅；末尾状态行更新为「下一步进入第 16 期 TUI 产品化」
- `.env.example`：见 §4.2

---

## 6. 用户体验

### 6.1 `/skill list`

```
> /skill list

📚 Skills（3 个）
  ● web-access       builtin   v1.0.0  所有联网操作必须通过此 skill 处理...
  ● paicli-internal  user      v0.2.0  PaiCLI 项目内部约定与文档导航...
  ○ verbose-debug    project   v0.1.0  在每个工具调用前打印决策原因...

提示：
  /skill show <name> 看完整 SKILL.md
  /skill on/off <name> 切换启用状态
  /skill reload 重新扫描
```

### 6.2 `/skill show web-access`

```
> /skill show web-access

📖 Skill: web-access (builtin, v1.0.0)
  路径: jar:resources/skills/web-access/SKILL.md
  references/: ~/.paicli/skills-cache/web-access/references/ (6 个文件)

---
name: web-access
description: |
  所有联网操作必须通过此 skill 处理...
version: "1.0.0"
---

# web-access Skill
...（完整正文）
```

### 6.3 LLM 自决加载

```
> 帮我看下 https://mp.weixin.qq.com/s/xxx 这篇文章讲了啥

[LLM 思考：这是微信公众号文章 → 看 system prompt 里的 skill 索引 → web-access description 命中]

🤖 调用工具: load_skill(name="web-access")

[ToolRegistry 把 web-access 的 SKILL.md body 写入 SkillContextBuffer]
[工具结果]: 已加载 skill 'web-access' 的完整指引（3.2KB），将在下一轮上下文中体现

[下一轮 user message 自动前置]:
## 已加载 Skill：web-access
<SKILL.md body>
---
用户输入：（PaiCLI 不重复用户原话，但 LLM 已有上下文）

[LLM 按 web-access 指引：先试 web_fetch，失败 → 上 chrome-devtools MCP，take_snapshot 拿正文]
```

### 6.4 `/skill off`

```
> /skill off verbose-debug

⏸️ 已禁用 skill: verbose-debug
   下一轮 LLM 调用生效；已写入 ~/.paicli/skills.json
```

---

## 7. 测试场景（端到端实测必跑）

### 7.1 三层覆盖优先级

前置：
- jar 内置 `web-access`（v1.0.0）
- 用户级 `~/.paicli/skills/web-access/SKILL.md`（v9.9.9，description 改成"用户版"）
- 不放项目级

```
> /skill list
```

**期望**：只显示一个 `web-access`，version `v9.9.9`，来源 `user`。

再加项目级 `<project>/.paicli/skills/web-access/SKILL.md`（v0.0.1）：

```
> /skill reload
> /skill list
```

**期望**：仍只一个 `web-access`，version `v0.0.1`，来源 `project`。

### 7.2 LLM 自决加载 web-access

清空所有自定义 skills，只留 builtin web-access：

```
> 帮我看下 https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg 这篇文章讲了啥
```

**期望**：
1. LLM 在第一轮调用 `load_skill("web-access")`
2. PaiCLI 输出 `已加载 skill 'web-access'...`
3. 下一轮按 web-access 指引：先 `web_fetch` 试 → 失败 → 上 `mcp__chrome-devtools__navigate_page` → `take_snapshot` 拿正文

**验收点**：
- LLM 真的调了 `load_skill`，不是直接上 chrome-devtools
- 下一轮 user message 里能看到 `## 已加载 Skill：web-access` 段（开 debug 日志验证）

### 7.3 SkillContextBuffer 一次性消费

让 LLM 连续两轮提问，但只第一轮调 load_skill：

```
> 帮我看 https://mp.weixin.qq.com/s/xxx
[LLM 调 load_skill, 走完]

> 再看一篇 https://zhuanlan.zhihu.com/p/yyy
[LLM 看 system prompt 里 skill 索引仍在，第二轮**不**应再次调 load_skill]
```

**期望**：第二轮 LLM 不重复加载（提示词里说"同一会话内一次足够"）。第三方观察：buffer 在第一轮 drain 后清空，第二轮 user message 没有 `## 已加载 Skill` 段。

### 7.4 Skill 间不影响

启用两个 skill：web-access 和 paicli-internal。让 LLM 完成跨域任务：

```
> 看下 PaiCLI 项目的 ROADMAP.md，再去网上搜一下 LangGraph4J 最新版本
```

**期望**：LLM 加载 paicli-internal 处理 ROADMAP，加载 web-access 处理搜索；两个 skill body 都进 user message（按调用顺序拼接）。

### 7.5 `/skill off` 立即生效

```
> /skill list
[显示 web-access enabled]
> /skill off web-access
> 帮我看 https://mp.weixin.qq.com/s/xxx
```

**期望**：LLM 看到 system prompt 里没有 web-access description → 不调 load_skill；尝试调 load_skill("web-access") 也会被 LoadSkillTool 拒绝。

### 7.6 frontmatter 解析容错

放一个**坏的** SKILL.md：

```markdown
---
name: broken
description: |
  multiline
  test
unsupported_field: { nested: object }
---
body
```

**期望**：启动期 stderr 警告 `Skill 'broken' frontmatter 解析失败：不支持嵌套对象` 但不阻塞，其他 skill 正常加载。

### 7.7 token 预算上限

放 25 个 skill（用脚本批量生成），每个 description 600 字符：

**期望**：
- description 自动截断到 500 字符（每个）
- 启用列表只前 20 个进 system prompt
- 索引段总大小不超过 4KB
- stderr 警告 `已检测到 25 个 skill，仅前 20 个进入 system prompt 索引`

### 7.8 启动期 builtin extractor

清空 `~/.paicli/skills-cache/`，启动 PaiCLI：

**期望**：自动重建 `~/.paicli/skills-cache/web-access/references/`，6 个 site-patterns 文件齐全，`.version` 文件存在。

### 7.9 Jina Reader 走 execute_command 路径

```
> 帮我看 https://github.com/anthropics/anthropic-cookbook 的 README
[LLM 调 load_skill("web-access")]
[下一轮 LLM 看到 web-access 提示「web_fetch 失败可走 r.jina.ai」]
[LLM 第一步先 web_fetch → 假设失败]
[LLM 第二步 execute_command "curl -s https://r.jina.ai/https://github.com/anthropics/anthropic-cookbook"]
[execute_command 触发 HITL（既有流程）]
```

**期望**：HITL 弹窗正常，用户批准后 curl 走通；如果用户对 execute_command 选 `a → tool`，本会话内后续 curl 都自动放行。

### 7.10 `/clear` 清空 buffer

```
> 帮我看 xxx
[LLM 调 load_skill, buffer 写入]
> /clear
[buffer 应被清空]
> 再看 yyy
[此时 user message 不应有上一次残留的 skill body]
```

---

## 8. 风险点

### 已知必踩的坑

1. **frontmatter 手写解析的边界**：用户写 SKILL.md 时可能用各种 YAML 怪招（quoted string with colon、`>` 折叠、`---` 在 body 里）。Day 1 测试用例必须覆盖至少 6 种合法 + 3 种非法格式。
2. **jar 内置 references 解压**：Windows 路径分隔符 / Linux mode bits / macOS 隐藏 `.DS_Store` 都可能搞砸 `Files.walk`。建议只解压 `*.md` 文件，其他忽略。
3. **`load_skill` 描述写不好 LLM 不主动调**：description 字段必须明确写「当 system prompt 里某个 skill 的 description 看起来匹配时调用我」。Day 5 端到端必测 LLM 真的会主动调。如果 GLM-5.1 / DeepSeek V4 不主动调，回 Day 3 重写工具 description。
4. **SkillContextBuffer 与流式输出竞态**：LLM 流式返回 tool_calls 时，PaiCLI 还没 flush 完上一轮 reasoning，就收到新一轮 user message——buffer drain 时机要在**新 user message 构造时**而不是上一轮 LLM 完成时，避免提前清空。
5. **三层目录覆盖语义**：用户级 SKILL.md 必须**完整覆盖**内置版（不是字段级 merge），避免出现"用户改了 description 但 references 还指向 builtin 缓存路径"的诡异状态。
6. **builtin extractor 与多用户**：如果系统多用户共用 `~/.paicli`，extractor 写入要带文件锁。本期接受单用户假设，文档明示。
7. **AgentOrchestrator 多个 SubAgent 同时 load_skill**：Worker × 2 同时各自调 load_skill 写入各自 buffer，无冲突；但 Reviewer 角色不应继承 Worker 的 buffer——三个角色各自独立 buffer。
8. **`/skill reload` 期间正在跑的 turn**：当前轮 system prompt 已发出，reload 不影响；测试要明确这一点而不是死锁等当前轮结束。
9. **description 在多语言下截断的字符边界**：用 `String.length()` 截断会切坏中文/emoji。改用 codepoint 计数或字符位置 `Character.isHighSurrogate` 检查。
10. **SubAgent 索引段重复**：三个角色（Planner/Worker/Reviewer）的系统提示词都拼 skill 索引，token 浪费。**接受**这个浪费——每个角色都可能调用 load_skill，索引必须在场。
11. **prompt cache 失效**：每次 reload 都改 system prompt（skill 索引段变化）→ prompt cache 全失效。文档明示：reload 是 cache-breaking 操作，建议只在配置稳定后偶尔做。
12. **Skill 加载顺序非确定 → 启动日志不稳定**：用 `Files.walk` 默认顺序非保证。强制按 name 字典序处理，便于复现。

### 已决策（不要再讨论）

- **不**做 SKILL.md 字段级 merge（用户级整体覆盖内置）
- **不**改 web_fetch 工具内部链路（Jina 只在 web-access 提示词里）
- **不**给 Skill 单独 HITL 维度
- **不**做 `requires` / 依赖解析
- **不**实现 `/skill use <name>` 强制路径（用提示词触发即可）
- **不**实现 SKILL.md 之外的格式
- **不**新增 SnakeYAML 依赖（手写解析）
- **不**把 SKILL.md body 一次性进 system prompt（永远只走 user message 注入）
- **不**做 Skill marketplace / 远程下载
- **不**抄用户本机 web-access 的具体内容（只参考结构与文风）

---

## 9. 开发顺序（5 天工作量）

每天结束前 `mvn test` 全绿才进入下一天。

### Day 1：Skill 模型 + frontmatter 解析 + Registry

**产出**：
- `Skill` record
- `SkillFrontmatterParser`（手写解析）
- `SkillRegistry`：扫三层目录 + 合并 + 过滤 disabled + reload
- `SkillStateStore`：读写 `skills.json`
- `SkillBuiltinExtractor`：jar 内 `resources/skills/` 解压到 cache（先支持 `web-access` 单 skill）
- 占位的 builtin SKILL.md（一行 description 即可，Day 4 写完整内容）

**测试**：
- `SkillFrontmatterParserTest`（6+ 用例：单行 / 多行 string / 数组 / 空 frontmatter / 缺 description / 嵌套对象报错）
- `SkillRegistryTest`（5+ 用例：三层覆盖、disabled 过滤、reload、name 字典序、坏文件跳过）
- `SkillStateStoreTest`（3+ 用例）
- `SkillBuiltinExtractorTest`（2+ 用例：首次解压、版本一致跳过）

### Day 2：load_skill 工具 + SkillContextBuffer

**产出**：
- `SkillContextBuffer`：push / drain / clear
- `LoadSkillTool`：注册到 ToolRegistry；调用写入 buffer + 返回简短确认
- ToolRegistry 构造接收 SkillRegistry + SkillContextBuffer
- 工具 description 反复打磨（Day 5 端到端验）

**测试**：
- `SkillContextBufferTest`（5+ 用例：drain 一次性、最多 3 个 skill、`/clear` reset、并发 push）
- `LoadSkillToolTest`（4+ 用例：合法 name、未启用 name、不存在 name、body 截断）

### Day 3：Agent 系统提示词 + user message 注入

**产出**：
- `SkillIndexFormatter`：渲染索引段（含 token 预算）
- 三处 Agent（`Agent` / `PlanExecuteAgent` / `SubAgent`）系统提示词构造路径加 skill 索引段
- 三处构造 user message 时 drain buffer 并前置（注意 SubAgent 三个角色各自独立 buffer 实例）
- AgentOrchestrator 给每个 SubAgent 分配独立 buffer

**测试**：
- `SkillIndexFormatterTest`（5+ 用例：空、1 个、20 个上限、4KB 上限、中文截断）
- `AgentSkillInjectionTest` 等三处集成测试（mock LLM，断言 user message 含/不含 buffer 内容）

### Day 4：CLI 命令组 + 内置 web-access 实质内容

**产出**：
- `/skill list / show / on / off / reload` 在 `Main.java` + `CliCommandParser.java` 落地
- 启动期 banner v15.0.0、标语、startup hint、skill 加载日志
- **写真实的 web-access SKILL.md**（参考 `~/.claude/skills/web-access/SKILL.md` 结构与精神，**不抄具体内容**）：
  - 浏览哲学四步法（自己组织语言）
  - 工具选择表（基于 PaiCLI 第 9 / 13 / 14 期能力）
  - 浏览器优先级（先 web_fetch → chrome-devtools isolated → /browser connect 切 shared）
  - Jina Reader 兜底说明（execute_command + curl r.jina.ai/）
  - 站点经验目录使用说明
- **写 6 个 site-patterns**（`mp.weixin.qq.com.md` / `zhuanlan.zhihu.com.md` / `x.com.md` / `xiaohongshu.com.md` / `github.com.md` / `juejin.cn.md`），每个按 §3.7 三段式模板，控制在 60-100 行
- `references/cdp-cheatsheet.md`：第 13 期 28 工具速查 + 第 14 期 `/browser connect` 用法

**测试**：
- `MainSkillCommandTest`（5+ 用例：list / show / on / off / reload 路径）
- `CliCommandParserTest` 追加（3+ 用例）

### Day 5：端到端实测 + 文档联动

**产出**：
- §7 全部 10 个端到端用例手测，结果记录到 commit description
- `AGENTS.md` / `README.md` / `ROADMAP.md` / `.env.example` 联动
- 启动 hint 加 `/skill list` 提示

**手测清单**（必跑，结果写到 commit description）：

1. ✅ §7.1 三层覆盖
2. ✅ §7.2 LLM 自决加载 web-access（关键，验证 description 写法 + load_skill 描述写法）
3. ✅ §7.3 buffer 一次性消费
4. ✅ §7.5 `/skill off` 立即生效
5. ✅ §7.6 坏 frontmatter 启动不阻塞
6. ✅ §7.7 token 预算
7. ✅ §7.8 builtin extractor 重建
8. ✅ §7.9 Jina 走 execute_command 路径（HITL 正常）
9. ✅ §7.10 `/clear` 清空 buffer
10. ✅ AgentOrchestrator 三角色独立 buffer

任何一项 fail 回 Day 1-4 修。手测过完才能 commit。

---

## 10. 测试策略

### 单测覆盖下限

- `SkillFrontmatterParserTest` ≥ 6
- `SkillRegistryTest` ≥ 5
- `SkillStateStoreTest` ≥ 3
- `SkillBuiltinExtractorTest` ≥ 2
- `SkillContextBufferTest` ≥ 5
- `LoadSkillToolTest` ≥ 4
- `SkillIndexFormatterTest` ≥ 5
- `MainSkillCommandTest` ≥ 5
- `CliCommandParserTest` 追加 ≥ 3
- 三个 Agent 角色的 skill 注入集成测试 ≥ 3

### 集成测（可选）

- 标记 `@EnabledIfEnvironmentVariable("SKILL_INTEGRATION_TEST", "true")`
- CI 默认不跑，本地手测覆盖

### 端到端（Day 5 手测清单 10 条）

必跑，结果写到 commit description 或 PR body。

---

## 11. 明确不做（留给后续期次）

- Skill 间依赖解析 / `requires` 字段
- Skill marketplace / 远程下载 / 自动更新
- Skill 内嵌 LLM 调用 / sub-agent 编排
- Skill scripts/ 自动批准 / 单独 HITL 维度
- SKILL.md 之外的格式（YAML / JSON / TOML）
- web_fetch 工具内部三档级联（Jina 只在 SKILL.md 提示词里）
- Skill 跨 Agent 角色差异化路由
- Skill body 全量进 system prompt
- Skill marketplace UI / TUI 集成（第 16 期再考虑）
- 实时校验 SKILL.md 的"质量分"（命中率 / 加载失败率监控）

实现过程中如果发现某条不做的功能其实绕不过去，**先停下来回上游确认**，不要擅自扩展范围。

---

## 12. Banner 版本

完成第 15 期后：
- `Main.java` `VERSION` = `"15.0.0"`
- 类注释：第 15 期新增 Skill 系统、`load_skill` 内置工具、内置 web-access skill
- Banner 标语：`Skill-Driven Agent CLI`

---

## 13. 完成判定（DoD）

- [ ] §5.1 所有新增类落地，§5.2 所有现有文件改动落地
- [ ] `/skill list` / `show <name>` / `on <name>` / `off <name>` / `reload` 五个子命令实现
- [ ] frontmatter 解析容错（未知字段忽略、嵌套对象报错但不阻塞）
- [ ] 三层目录扫描 + 优先级覆盖 + name 字典序处理
- [ ] `~/.paicli/skills.json` 读写持久化
- [ ] `load_skill` 内置工具注册，写入 SkillContextBuffer
- [ ] 三处 Agent system prompt 加 skill 索引段（含 token 预算）
- [ ] 三处 Agent user message 构造时 drain buffer 并前置
- [ ] AgentOrchestrator 给每个 SubAgent 分配独立 buffer
- [ ] jar 内置 web-access skill：SKILL.md（决策手册）+ 6 个 site-patterns + cdp-cheatsheet.md
- [ ] SkillBuiltinExtractor 启动期解压 references/ 到 `~/.paicli/skills-cache/`
- [ ] description 截断（codepoint 边界）+ enabled 上限 20 + 索引段 4KB 上限
- [ ] `mvn test` 全绿（含原有用例 + 新增）
- [ ] §9 Day 5 手测 10 条全部跑过，结果写入 commit message
- [ ] §5.3 所有文档联动完成
- [ ] Banner 升 v15.0.0
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
| Skill 触发机制 | description + LLM 自决；`load_skill(name)` 工具显式加载；不做关键词硬匹配 |
| Skill body 注入位置 | next user message 前置（不是 system prompt） |
| Skill body 上限 | 5KB 截断 |
| 索引段位置 | system prompt 末尾，独立段 |
| 索引段上限 | 单 description 500 codepoint，启用 20 个，总 4KB |
| 加载位置 | jar 内置 < 用户级 < 项目级，整体覆盖（非字段 merge） |
| 启用状态持久化 | `~/.paicli/skills.json` 的 disabled 列表 |
| frontmatter 格式 | YAML 子集（手写解析），不依赖 SnakeYAML |
| 必填字段 | `name`、`description` |
| 选填字段 | `version`、`author`、`tags` |
| 未知字段 | 忽略（不报错），保前向兼容 |
| Jina Reader 集成位置 | **只**在 web-access SKILL.md 的指引里，**不**改 web_fetch 工具 |
| scripts/ 执行通道 | 沿用 `execute_command`，HITL 既有流程；不给 skill 单独审批维度 |
| `load_skill` 工具结果 | 简短确认（不返回 body） |
| SkillContextBuffer 生命周期 | 一次性消费、最多 3 个 skill body、`/clear` reset |
| Agent 三角色 buffer | 各自独立实例（Planner / Worker / Reviewer / 主 Agent） |
| `/skill reload` 影响范围 | 只影响下一轮，当前轮不变 |
| 内置 web-access 的 scripts/ | 留空（不复制 cdp-proxy.mjs） |
| 内置 site-patterns 数量 | 6 个（mp.weixin.qq.com / zhuanlan.zhihu.com / x.com / xiaohongshu.com / github.com / juejin.cn） |
| SKILL.md 字数预算 | 决策手册控制在 4-5KB，超长内容拆 references/ |
| prompt cache 失效 | 接受（reload 必然失效），文档明示 |
| 多用户文件锁 | 不做（接受单用户假设） |
| `/skill use <name>` | **不实现**（用提示词或 LLM 自决即可） |
| Skill marketplace | 不做 |
| Skill 间依赖 | 不做 |
| 第 16 期范围 | 不动（TUI 仍按原计划） |

---

## 16. 必跑端到端用例：微信公众号文章 + Jina 兜底

这是**第 15 期最关键的端到端用例**，因为它同时验证：
- LLM 真的会主动调 `load_skill`（验 description 写法）
- web-access 指引真的影响后续工具选择（验 buffer 注入路径）
- Jina 兜底真的走 execute_command + HITL（验不绕过安全边界）
- 三层覆盖 + reload 真的能用（验生命周期）

**前置**：
- jar 内置 web-access（builtin）
- 用户级 / 项目级 不放 web-access
- 第 13 期 chrome-devtools MCP 已就绪
- API Key 配置正常

**测试 URL**：
```
https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg
```

**提示词序列**：
```
> /skill list
[期望显示 web-access (builtin)]
> 帮我看下 https://mp.weixin.qq.com/s/RB7kF_BbsJZ5_Hmu9PxWdg 这篇文章讲了什么
```

**期望行为序列**：
1. LLM 看到 system prompt 里 web-access description，主动调 `load_skill("web-access")`
2. 工具结果：`已加载 skill 'web-access'...`
3. 下一轮 LLM 收到 user message 前置的 web-access body
4. LLM 按 web-access 指引：先 `web_fetch` → 失败（微信文章 SPA） → 上 `mcp__chrome-devtools__navigate_page` → `take_snapshot` 拿 DOM 文本
5. 输出文章总结

**失败模式**（任一出现都算 Day 5 不通过，回 Day 2-4 重调）：
- LLM 在第一轮**没**调 `load_skill`，直接上 chrome-devtools（说明索引段没被 LLM 当回事 → 检查 description 写法）
- LLM 调了 `load_skill` 但下一轮没看到 web-access body（说明 buffer drain 时机或注入路径错了）
- web-access body 注入到了 system prompt 而不是 user message（违反 §3.5 关键决策）
- 同一会话第二个 URL 时 LLM 又调一次 `load_skill`（违反"一次足够"提示，buffer 提示词写得不够清楚）
- `/skill off web-access` 后 LLM 仍能调 `load_skill("web-access")`（说明 LoadSkillTool 没查 enabled 状态）

---

如果有任何疑问，回到上游问，不要自行推断。祝顺利。
