# AGENTS Reference: Detailed Feature Behavior

This document contains detailed feature behavior descriptions, configuration reading orders, and implementation notes that were previously in `AGENTS.md`. Consult this when working on specific modules.

For the primary entry point, see `/AGENTS.md`.

---

## Configuration Reading Orders

### API Key

1. `~/.paicli/config.json` 中对应 provider 的 `apiKey`
2. 环境变量：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY` / `XFYUN_MAAS_API_KEY` / `AGNES_API_KEY`（Kimi 兼容 `MOONSHOT_API_KEY`，讯飞 MaaS 兼容 `XFYUN_API_KEY`）
3. 仓库当前目录下的 `.env`
4. 用户主目录下的 `.env`

### GitHub PR Client Config

`GitHubConfig.fromEnvironment()` 读取顺序：

- Token：系统属性 `paicli.github.token` → 环境变量 `PAICLI_GITHUB_TOKEN` / `GITHUB_TOKEN` / `GH_TOKEN` → 项目 `.env` → 用户 `~/.env`
- REST API base URL：系统属性 `paicli.github.api.baseUrl` → `PAICLI_GITHUB_API_BASE_URL` → `.env` → 默认 `https://api.github.com`
- GraphQL URL：系统属性 `paicli.github.graphql.url` → `PAICLI_GITHUB_GRAPHQL_URL` → `.env` → 由 REST base URL 派生，默认 `https://api.github.com/graphql`

### Persistence Locations

| 数据 | 默认路径 | 覆盖方式 |
|------|----------|----------|
| 长期记忆 | `~/.paicli/memory/long_term_memory.json` | `-Dpaicli.memory.dir` |
| 项目级记忆 | `PAI.md` / `.paicli/PAI.md` / `PAI.local.md` | 用户级稳定偏好：`~/.paicli/PAI.md` |
| RAG 索引 | `~/.paicli/rag/codebase.db` | `-Dpaicli.rag.dir` |
| 审计日志 | `~/.paicli/audit/audit-YYYY-MM-DD.jsonl` | `PAICLI_AUDIT_DIR` / `-Dpaicli.audit.dir` |
| Side-Git 快照 | `~/.paicli/snapshots/<project_hash>/<worktree_hash>/.git` | `PAICLI_SNAPSHOT_DIR` / `-Dpaicli.snapshot.dir` |
| 后台任务 | `~/.paicli/tasks/tasks.db` | — |

### Snapshot Config

系统属性 > 环境变量 > 默认值：`paicli.snapshot.enabled`(true) / `paicli.snapshot.max`(50) / `paicli.snapshot.excludes`(.git,.paicli/snapshots,target,node_modules,dist,.idea,*.class,*.jar) / `paicli.snapshot.dir`(~/.paicli/snapshots)

### Embedding Config

环境变量 > 系统属性 > 默认值：`EMBEDDING_PROVIDER`(ollama) / `EMBEDDING_MODEL`(nomic-embed-text:latest) / `EMBEDDING_BASE_URL`(http://localhost:11434)

### Log Config

系统属性 > 环境变量/.env > 默认值：`PAICLI_LOG_DIR`(~/.paicli/logs) / `PAICLI_LOG_LEVEL`(INFO) / `PAICLI_LOG_MAX_HISTORY`(7) / `PAICLI_LOG_MAX_FILE_SIZE`(10MB) / `PAICLI_LOG_TOTAL_SIZE_CAP`(100MB)

### Command Sandbox Config

`~/.paicli/config.json` 的 `sandbox` 字段控制 macOS-only command sandbox。它只包裹 `execute_command` 启动的 `/bin/bash -lc` 及其子进程，不沙箱整个 PaiCLI 进程。

- `enabled`：开启后在 macOS 上通过 Seatbelt (`sandbox-exec`) 包裹命令；默认 false。
- `required`：严格模式；如果当前平台不是 macOS 或 `sandbox-exec` 不可用，`execute_command` 直接策略拒绝。
- `autoAllowCommandIfSandboxed`：命令确认会进入沙箱时，HITL 开启状态下可自动放行该高风险工具。
- `allowUnsandboxedCommands`：允许模型通过 `dangerously_disable_sandbox=true` 请求非沙箱执行；非沙箱仍走高风险 HITL。
- `excludedCommands`：不适合沙箱的命令模式，如 `docker:*` / `podman:*` / `colima:*`；这是体验配置，不是安全边界。
- `filesystem.allowWrite` / `denyRead` / `denyWrite`：转换为 Seatbelt profile；默认允许项目目录写入，拒绝 `.paicli/**`、`.env`、`PAI.md`、`AGENTS.md`、`.git/hooks/**` 等敏感写入。
- `network.enabled`：false 时 profile 写入 `(deny network*)`；true 时允许命令联网。

CLI：`/sandbox status`、`/sandbox on`、`/sandbox off`、`/sandbox strict on|off`、`/sandbox excluded add|remove <pattern>`、`/sandbox doctor`。

### ReAct/SubAgent Budget Config

系统属性 > 默认值：`paicli.react.token.budget`(Integer.MAX_VALUE) / `paicli.react.stagnation.window`(3) / `paicli.react.hard.max.iterations`(50) / `paicli.react.no.progress.window`(4) / `paicli.react.invalid.reflection.window`(1) / `paicli.react.tool.failure.window`(5)；Plan 单节点可用 `paicli.plan.task.max.iterations` / `PAICLI_PLAN_TASK_MAX_ITERATIONS` 覆盖，默认 8。

设计取舍：长上下文模型默认不再以 80% x window 为硬限。`AgentLoopController` 负责多类空转防护：连续相同工具调用、连续工具失败、连续无新工具观察、无工具却输出“我需要继续检查/调用工具”的无效反思、硬最大轮数。Token 显示行 `📊 Token: 已用 X / Y` 的 Y 是软提示，不代表强制限制。

### LLM HTTP Timeout Config

系统属性 > 默认值：`paicli.llm.connect.timeout.seconds`(60) / `paicli.llm.read.timeout.seconds`(300) / `paicli.llm.write.timeout.seconds`(60) / `paicli.llm.call.timeout.seconds`(600)

SSE 流式下 readTimeout 是两次 read 间最大间隔，GLM-5.1 生成大段 reasoning 时可能长时间静默，所以放宽到 300 秒。
DeepSeek 流式调用默认使用 HTTP/1.1，避免部分 HTTP/2 网关在长 SSE 响应中重置 stream，表现为 `stream was reset: INTERNAL_ERROR`。
DeepSeek 当前不发送图片输入：`supportsImageInput()` 返回 false，含图片的 `ContentPart` 会在 OpenAI-compatible 请求序列化时替换成文本提示，避免不支持多模态的 DeepSeek API 收到 `image_url` block。

### Web Search Provider Config

1. `SEARCH_PROVIDER` 显式指定 `zhipu` / `serpapi` / `searxng`
2. 未指定时按 Key 自动判断：`GLM_API_KEY` → zhipu / `SERPAPI_KEY` → serpapi / `SEARXNG_URL` → searxng
3. 都没有 → zhipu 占位

各 provider：zhipu(`GLM_API_KEY` + 可选 `ZHIPU_SEARCH_ENGINE`) / serpapi(`SERPAPI_KEY`) / searxng(`SEARXNG_URL`)

### Web Fetch Security (NetworkPolicy)

scheme 白名单(http/https) / 主机黑名单(localhost/loopback/link-local/site-local) / 响应体上限 5MB / 超时 30s / 限流 30次/60s

### MCP Config

1. 用户级：`~/.paicli/mcp.json`
2. 项目级：`.paicli/mcp.json`
3. 按 server 名 merge，项目级覆盖用户级

格式兼容 Claude Code：`command` + `args` = stdio，`url` + `headers` = Streamable HTTP。内置变量：`${PROJECT_DIR}`、`${HOME}`；其他 `${VAR}` 从系统环境变量、系统属性、项目 `.env`、用户 `~/.env` 读取。
检测到 `STEP_API_KEY` 时自动内置 `step_search` 远程 MCP（显式同名配置优先），用于 Step 3.7 Flash 的 `web_search` / `web_fetch` 优先代理。

---

## Detailed Feature Behavior

### ReAct Mode

- 主入口：`Agent.java`
- 退出条件由 LLM 自决（不返回 tool_calls 即结束）
- `AgentLoopController` 兜底：token 超预算 / 连续 3 轮相同调用 / 连续无进展 / 无效反思 / 连续工具失败 / 50 轮硬上限
- 流式输出 reasoning_content + content；inline ReAct 用固定高度 live thinking 区动态预览 reasoning，同一次输入只把完整 reasoning 引用块落到 transcript 一次；live 区只允许清理自己占用的行，避免覆盖旧输出
- inline 流式回答用低调 `▪` 标记起始，不再输出强标题；plain / 非流式兜底仍可使用传统 reasoning + answer 文本
- `TerminalMarkdownRenderer` 渲染 Markdown 表格时按终端列宽分配列宽，长内容在单元格内部换行；CJK 字符按显示宽度计算，避免表格行被终端自动折断后错位

### Long Context Engineering

- `ContextProfile` 计算 short/balanced/long 模式
- GLM-5.1: 200k / DeepSeek V4: 1M / Agnes: 1M / StepFun: 256k / Kimi K2.6: 256k / FreeLLMAPI: 128k
- long 模式(>=100k)：跳过 Memory 自动摘要，search_code 语义辅助 topK=20，MCP resources 自动索引；精确代码定位仍优先实时 glob/grep/read
- prompt caching：能力声明 + cached usage 解析
- 自动压缩阈值按 Claude Code 风格预留空间：`maxContextWindow - min(20k, window/4) - min(13k, window/8)`；200k 窗口约 167k 触发，1M 窗口约 967k 触发，小窗口会按比例缩小预留。

### Memory System

- 两道压缩：
  1. `ContextCompressor` 压缩 shortTermMemory
  2. `ConversationHistoryCompactor` 压缩 conversationHistory（真正发给 LLM 的消息）
- 完整 conversationHistory 压缩前会先运行 `ConversationMicroCompactor`：保留最近 2 条 tool result，把较早且超过阈值的大型工具结果替换为短标记，避免 read/grep/command/web 输出撑爆上下文。
- 第二道压缩切割在 user message 边界，保留最近 3 个 user 起算的尾部
- 三条路径(ReAct/Plan/SubAgent)都接入第二道压缩
- `/compact` 可手动压缩当前 ReAct conversationHistory，不等待 token 阈值触发；手动路径也会先 microcompact 旧工具结果，再保留最近 1 个 user 轮次做结构化摘要；Plan/SubAgent 仍只走调 LLM 前的自动压缩
- `ConversationHistoryCompactor` 会在摘要消息写入 `[compact_boundary]` 锚点；当前内存 history 已经只保留摘要和尾部，后续 transcript/resume 加载应优先恢复该边界之后的有效链。
- 长期记忆只通过 `/save` 或用户明确要求保存；可选自动归纳需显式启用 `PAICLI_AUTO_MEMORY=true` 或 `-Dpaicli.auto.memory=true`
- 长期记忆存储同时维护 JSON 兼容文件和 Markdown topic 体系：`MEMORY.md` 为索引，`topics/*.md` 为详情；召回会综合内容、topic frontmatter 和 metadata 评分并记录原因。
- 长期记忆只保存跨会话稳定事实，不保存临时指令；默认项目级作用域，跨项目通用偏好才用 global
- 长期记忆管理命令：`/memory list`、`/memory search <关键词>`、`/memory delete <id>`、`/memory clear`
- `PAI.md` 不是 `/save` 长期记忆：它是启动时注入 system prompt 的项目指令文件，适合团队共享、长期稳定、可进 git 的规则
- 加载顺序：`~/.paicli/PAI.md` → `PAI.md` → `.paicli/PAI.md` → `PAI.local.md` → `.paicli/PAI.local.md`
- `.paicli/rules/*.md` 参与项目记忆；无 `paths:` frontmatter 的规则启动即加载，有 `paths:` 的规则在 `read_file` 读到匹配路径后加载。`read_file` 还会触发 nested memory，下一轮模型调用前加载被读文件所在子目录链路上的 PAI/local/rules。
- `PAI.md` 中独占一行的 `@relative/path.md` 会被展开；导入路径必须留在用户配置目录或项目根内，总注入内容按预算截断
- `/init` 生成精简 `PAI.md`，只写 commands / project positioning / architecture / pitfalls / don'ts；已有文件默认不覆盖，`/init --force` 重写

### Multi-Agent

- 三角色：Planner / Worker(默认 2 个) / Reviewer
- 流程：规划 → 按依赖分配 Worker → Reviewer 审查 → 未通过重试(最多 2 次)
- SubAgent IOException 返回 ERROR 类型
- 所有子代理共享 ToolRegistry 和 MemoryManager

### HITL System

- 危险工具：write_file(中) / execute_command(高) / create_project(中) / revert_turn(高)
- 审批选项：y(批准) / a(全部放行) / n(拒绝) / s(跳过) / m(修改参数)
- fail-safe：连续 5 次无效输入判为 REJECTED
- 并发：requestApproval 整体 synchronized

### HITL Enhancement (Policy Layer)

- `PathGuard`：路径限定在项目根内（绝对路径外逃 / `..` 穿越 / 符号链接逃逸）
- `CommandGuard`：fast-fail 黑名单（sudo/rm -rf/mkfs/dd/fork bomb/curl|sh 等）
- `ResourceLimit`：write_file 5MB / execute_command 60s + 8KB 输出
- `AuditLog`：JSONL 字段 timestamp/tool/args/outcome/reason/approver/durationMs
- 拦截顺序：HitlToolRegistry → ToolRegistry → 策略层。用户无法批准策略拒绝的请求

### Parallel Tool Execution

- `executeTools()` 固定线程池并行，默认最多 4 个并发
- 返回结果保持原始顺序
- Agent/PlanExecuteAgent/SubAgent 三条路径都走 executeTools()

### Web Capabilities

- `web_search`：SearchProvider 接口，返回 SearchResult 列表
- `web_fetch`：NetworkPolicy → WebFetcher → HtmlExtractor，SPA/防爬墙返回空正文 + 边界提示
- 联网决策由模型通过原生 tool call 自主发起；Prompt 不包含 Freshness Policy，不强制 `web_search`。本地“当前项目/当前 README/当前文件/当前代码”仍作为代码库任务交给模型在工具 schema 中选择本地工具。
- StepSearch 优先级：当前模型 provider=`step` 且 model 以 `step-3.7-flash` 开头，并且自动/显式 `mcp__step_search__web_search` / `mcp__step_search__web_fetch` 已注册时，内置 `web_search` / `web_fetch` 会先代理到 StepSearch MCP；MCP 未就绪或返回不可用结果时回退原实现。
- JS 渲染 fallback 到 Chrome DevTools MCP

### GitHub PR Review Client

- `com.paicli.github.GitHubPrClient` 是内置 PR review 底座，使用 OkHttp + Jackson 访问 GitHub REST / GraphQL。
- `fetchSnapshot(ref)` 聚合 `fetchPullRequest`、`fetchDiff`、`fetchChangedFiles`、`fetchReviewComments`、`fetchCiStatus`，返回 `GitHubPrSnapshot`，包含 PR metadata、diff、文件列表、已有 review comments、commit statuses 与 check runs。
- `fetchPullRequestViaGraphql(ref)` 用 GraphQL 拉取 PR metadata；GraphQL 与发布 review 都要求 token。
- `publishReview(ref, GitHubReviewRequest)` 调用 REST `POST /repos/{owner}/{repo}/pulls/{number}/reviews`，支持 summary 和 inline comments（默认 side=`RIGHT`）。
- `GitHubDiffLineMap` 解析 changed file `patch` 的 hunk header，把新文件 `RIGHT` 行、旧文件 `LEFT` 行和 context 行映射成可评论位置；rename 会把 `previous_filename` 作为别名解析到当前 filename。
- `GitHubReviewPreparer.prepare(snapshot, event, body, findings)` 会用 `GitHubDiffLineMap` 过滤 missing path、无效行号、不在当前 diff 的 outdated 定位，只把仍可发布的位置放入 `GitHubPreparedReview.request()`，跳过项放入 `skippedFindings()`。
- `GitHubInlineCommentFormatter` 负责 inline comment Markdown：支持 title、severity 和 GitHub `suggestion` fenced block，并在发布前限制超长评论。
- `GitHubPrSnapshot.outdatedReviewComments()` 会结合 GitHub 返回的 line/position/original_position/commit_id 与当前 diff line map 找出已有 outdated comments。
- `GitHubPrReference.parse` 支持 `https://github.com/owner/repo/pull/123` 与 `owner/repo#123`。
- CLI 入口 `/review pr <url|owner/repo#number|number>` 在 `Main` 中解析；只传 number 时从当前 git `remote.origin.url` 推断 owner/repo，然后调用 `GitHubPrClient.fetchSnapshot`，把 PR metadata / CI / changed files / existing comments / diff 构造成 ReAct 审查 prompt。当前入口不会自动发布 review；发布能力保留在 `GitHubPrReviewService.publishPreparedReview`。
- 非交互入口 `review pr <url|owner/repo#number|number> --dry-run --format json` 在初始化 LLM 前执行，只拉取 PR snapshot、构造同一份 review prompt，并输出 changed files / comments / outdated comments / CI / diff chars / prompt chars 等机器可读摘要；它用于 Code Review Bench、CI 或本地 smoke，不代表完整模型评审结果。
- Code Review Bench 入口 `benchmark code-review-bench <offline-dir> --mode smoke|review` 读取官方 `offline/results/benchmark_data.json`，逐条按 golden URL 拉 GitHub PR snapshot。默认会把目标仓库 sparse checkout 到 `offline/results/paicli-worktrees`，fetch PR head，并采集 changed file 的 head 内容补进 prompt；`--no-checkout` 可退回纯 diff 模式，`--only-url <PR_URL>` 可只重跑指定 PR。`--parallel N` 沿用 PaiCLI `executeTools` / Plan batch 的有界并发风格并行处理 PR：worker 内独立 fetch/checkout/LLM review，主线程按 benchmark 输入顺序合并 `benchmark_data`、`candidates` 和 run summary；同仓库 checkout 目录按 PR 号隔离，避免 sparse-checkout 互相覆盖；当前最大 4。benchmark prompt 使用高召回策略，明确要求覆盖 doc/style/test_gap/translation/locale、命名拼写、常量修饰符等低严重度但有证据的问题，并会对 CLI exit 行为、`picocli.exit`/`System.exit`、exit code 变更缺 release/migration note 等 Code Review Bench 常见 golden 做 targeted audit hints。diff 不再走 60k 整体截断，改为按文件分块和总预算写入。`smoke` 验证拉取、prompt 构造和 checkout；`review` 调 PaiCLI 当前配置的 LLM 生成 JSON findings，并写出 benchmark 兼容的 review comments 与 `results/<MARTIAN_MODEL>/candidates.json`。默认输出到 `benchmark_data.<tool>.json`，只有 `--in-place` 会更新官方 step3 默认读取的 `benchmark_data.json`。`--timeout-seconds` 控制单 PR 模型审查上限，超时记为 failed 并继续。正式跑 50 条需要配置 `PAICLI_GITHUB_TOKEN` / `GITHUB_TOKEN`，否则匿名 GitHub API 很容易 403 rate limit。当前 runner 尚未自动运行目标项目测试。
- `/review pr` prompt 参考 `code-review-standards`：要求基于真实 PR base/head diff，按安全鉴权、数据迁移、配置环境、部署网关、用户可见行为、测试和文档分层检查；输出 findings first，使用 P0-P3 严重级别，并记录已通过验证、失败原因、未验证范围和剩余风险。

### MCP Protocol

- stdio + Streamable HTTP 双 transport
- 工具注册为 `mcp__{server}__{tool}`
- McpSchemaSanitizer 清洗 inputSchema
- 所有 mcp__ 工具默认走 HITL + AuditLog
- resources 双轨：虚拟工具 + @-mention 输入层
- CLI 首屏默认只等待 MCP 启动 8 秒，慢 server 后台继续初始化并保持 `starting`，用 `/mcp` / `/mcp logs <name>` 追踪
- notifications 路由：tools/list_changed → 工具全量替换，resources 变化 → cache 失效

### Chrome DevTools MCP

- 默认 server：chrome-devtools，`npx -y chrome-devtools-mcp@latest --isolated=true`
- `/browser connect`：切到 --autoConnect 复用登录态 Chrome
- `/browser connect <port>`：旧式 CDP 端口路径
- `/browser disconnect`：切回 isolated
- 敏感页面策略：改写型工具必须单步 HITL，不复用全部放行
- shared 模式 close_page 只允许关闭 PaiCLI 创建的 tab

### Skill System

- 三层加载：jar 内置 < 用户级 ~/.paicli/skills/ < 项目级 .paicli/skills/
- frontmatter：name(必填) / description(必填,<=500) / version / author / tags
- system prompt 索引段注入到三处提示词末尾，上限 20 个 / 4KB
- load_skill 工具把 SKILL.md 正文(5KB 截断)写入 SkillContextBuffer
- buffer 一次性消费，最多 3 个 skill body

### TUI (v16.1 Renderer Architecture)

- 三个实现：InlineRenderer(默认) / LanternaRenderer / PlainRenderer
- 环境变量：`PAICLI_RENDERER=inline|lanterna|plain`
- `PAICLI_TUI=true`(旧) → lanterna + deprecation 提示
- `PAICLI_NO_STATUSBAR=true`：禁用底部状态栏
- `NO_COLOR=1`：禁用 ANSI 颜色
- 当前开屏 Banner 是无右侧盒线边框的简洁布局，避免 ANSI/CJK 字宽导致竖线错位
- InlineRenderer 复用 JLine 4 的编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`
- BottomStatusBar 是 JLine `Status` 托管的底部 dock：由 JLine 负责滚动区域和状态行位置，不再手写 `\n`、`moveUp`、`CLEAR_TO_EOS` 或绝对光标行号；dock 上层展示 YOLO/HITL 与 MCP/Skill 摘要，下层展示 model、phase、ctx、token、cost、elapsed 与 cwd。关键字段可用 JLine `AttributedString` 做克制彩色高亮，但纯文本格式和列宽裁剪仍要稳定。`ctx` 只表示当前仍会带入下一轮请求的上下文估算，`in/out/cache` 表示最近任务调用统计。
- `/clear` 清空当前 ReAct conversationHistory、shortTermMemory 和待注入 SkillContextBuffer，并重建不含上一轮检索记忆的 system prompt；长期记忆条目保留，后续只会按新查询重新检索注入。
- `/compact` 手动压缩当前 ReAct conversationHistory，压缩期间显示动态 activity 面板，成功后刷新底部 ctx；不会清空 shortTermMemory、长期记忆或待注入 SkillContextBuffer。
- `/context` 显示下一轮请求的大致 API view，而不是只看 UI 历史；需要拆分 system/tools/conversation/user/assistant/tool result，并列出 PAI.md/rules 来源、当前项目可见长期记忆数量、上一轮实际注入的长期记忆及召回原因、工具结果 microcompact 状态。
- `/export` 导出当前 ReAct conversationHistory 为 Markdown 到 `~/.paicli/exports/session-*.md`；包含完整 system prompt，便于检查 LLM 实际接收前的指令，命令不接受路径参数。
- 普通任务和斜杠命令提交后都会以 `>` 暗色整行块回写原始输入，避免 JLine accept 后清掉编辑行导致结果区看不到刚执行的命令
- InlineRenderer 不使用独立 JLine `Display.update()` 维护 thinking 临时区；真实终端验证发现独立 Display 会在 transcript/status 输出后从错误位置向上清屏。当前实现用固定高度 live 区重写自身行，content/tool 边界先清理 live 区再追加 transcript。
- 交互期输出优先走 `Renderer.stream()`；`Main`、`PlanExecuteAgent`、`Planner`、`AgentOrchestrator` 都可接收同一个 renderer 输出流，避免绕过 inline renderer 直接写 stdout
- `CodeIndex` 通过 `ProgressListener` 上报索引开始 / 文件数量 / 进度 / 完成或失败，`/index` 绑定当前 renderer 输出流；内部异常细节写 logger

### LSP Diagnostics (Phase 17)

- write_file 成功后对 Java 文件做 JavaParser 语法诊断
- 诊断作为合成 user message 注入下一轮 LLM 请求
- `PAICLI_LSP_ENABLED=false` 关闭

### Git Side-History Snapshot (Phase 18)

- side-git 在 ~/.paicli/snapshots/ 维护独立仓库（JGit，不依赖系统 git）
- pre-turn 同步，post-turn 异步
- revert_turn 纳入 HITL/AuditLog，恢复前先创建 pre-restore 快照

### Prompt Layering (Phase 19)

- 组装顺序：base → personality → mode → approval → runtime_context → project_context → skills → context_mgmt → handoff
- runtime_context 每轮注入当前日期和系统时区，供相对日期理解使用
- project_context 顺序：`PAI.md` 项目记忆 → 相关长期记忆 → MCP resources 索引
- 覆盖优先级：jar 内置 < 用户级 ~/.paicli/prompts/ < 项目级 .paicli/prompts/
- 必要校验：base.md 和最终 prompt 必须包含 `## Language`

### Async Tasks + Runtime API (Phase 20)

- DurableTaskManager(SQLite) / CLI: /task, /task list, /task add, /task cancel, /task log
- Runtime API: `serve --http --port 8080`，仅 127.0.0.1，需 API Key
- 端点：POST /v1/threads / POST /v1/threads/{id}/turns / GET /v1/threads/{id}/events

### Image Input (Phase 21)

- ContentPart 支持图片 block（base64 + mimeType）
- ImageProcessor：铺白底/缩放 2000x2000/压缩 5MB
- 输入：`@image:file:///path.png` / `@image:/path.png` / `@image:relative.png`
- GLM-5V-Turbo 通过 `/model glm-5v-turbo` 切换
- Provider 通过 `supportsImageInput()` 声明是否接收图片；不支持时保留文字上下文并省略图片 payload
- 历史 image payload 替换为文本占位，避免旧截图消耗上下文

---

## Core File Descriptions

### Main.java
CLI 入口 / Banner / .env 读取 / 日志初始化 / 模式切换 / JLine raw mode

### Agent.java
ReAct 主循环 / 对话历史 / 工具调用与结果回灌

### PlanExecuteAgent.java
规划后执行 / 计划审阅 / DAG 任务执行 / 并行批次 / 失败重规划

### AgentOrchestrator.java
Multi-Agent 编排器 / 三角色管理 / 按依赖分配 / 审查重试

### SubAgent.java
可配置角色子代理 / 独立对话历史 / Worker 用工具、Planner/Reviewer 不用

### Planner.java
LLM 生成计划 JSON / 简单任务最小计划 / 重编号 task_1..N / 依赖计算

### ExecutionPlan.java
DAG 拓扑排序 / 可执行任务判定 / 进度可视化

### ToolRegistry.java
13 个核心内置工具 + MCP 动态工具 / executeTools() 并行入口 / ToolInvocation / ToolExecutionResult。代码理解默认路径是 `glob_files` / `grep_code` / `read_file` 现用现查，`grep_code` 优先走 ripgrep 并按 `max_results` / `head_limit` / `max_chars` 渐进返回，`search_code` 保留为 RAG 语义辅助。后台 durable 写工具会返回 `operation_id`，`tool_status` 查询单次操作状态，`tool_compensate` 按单次可逆操作恢复写入前快照。确定性搜索链路的回归样例见 `docs/code-search-golden-set.md`。

### GitHub Package
GitHubConfig / GitHubPrReference / GitHubPrClient / GitHubPrReviewService / GitHubPrSnapshot / GitHubPullRequest / GitHubChangedFile / GitHubReviewComment / GitHubCiStatus / GitHubDiffLineMap / GitHubReviewPreparer / GitHubPreparedReview / GitHubReviewRequest / GitHubReviewResult。当前提供 PR review agent 所需的 GitHub 结构化 IO：PR diff、changed files、已有 comments、CI 状态读取、inline comment 行号映射、outdated 定位过滤，以及 review 发布。

### MCP Package
McpServerManager / McpClient / JsonRpcClient / StdioTransport / StreamableHttpTransport / McpSchemaSanitizer / resources/ / mention/ / notifications/

### TUI Package
TuiBootstrap / LanternaWindow / TuiSessionController / pane/ / hitl/ / history/ / highlight/

### LLM Clients
- GLMClient：glm-5.1，glm-5v 开头切多模态接口
- DeepSeekClient：deepseek-v4-flash，thinking + tool calls 带回 reasoning_content
- StepClient：step-3.5-flash，可通过 STEP_BASE_URL 切通道
- KimiClient：kimi-k2.6，thinking + tool calls 带回 reasoning_content
- FreeLlmApiClient：auto，默认 http://localhost:5173/v1，OpenAI-compatible 本地网关；可用 `/config provider freellmapi ...` 写入配置后 `/model freellmapi` 切换
- XfyunMaaSClient：Qwen3.6-35B-A3B，默认 https://maas-api.cn-huabei-1.xf-yun.com/v2，OpenAI-compatible 讯飞星辰 MaaS；可用 `/config provider xfyun ...` 写入配置后 `/model xfyun` 切换。`model` 必须使用 MaaS 服务管控页展示的 modelId；微调模型可配置 `--lora-id <resourceId>`，作为 HTTP header `lora_id` 发出；该 provider 不发送 PaiCLI 内置 tools。
- AgnesClient：agnes-2.0-flash，默认 https://apihub.agnes-ai.com/v1，OpenAI-compatible Agnes AI，默认 1M context window；可用 `/config provider agnes ...` 写入配置后 `/model agnes` 切换，支持流式输出和 tools。

---

## .env.example Reference

```bash
GLM_API_KEY=your_api_key_here
# GLM_MODEL=glm-5.1
# GLM_MODEL=glm-5v-turbo
# DEEPSEEK_API_KEY=your_deepseek_api_key_here
# DEEPSEEK_MODEL=deepseek-v4-flash
# STEP_API_KEY=your_step_api_key_here
# STEP_MODEL=step-3.5-flash
# STEP_BASE_URL=https://api.stepfun.com/v1
# KIMI_API_KEY=your_kimi_api_key_here
# MOONSHOT_API_KEY=your_moonshot_api_key_here
# KIMI_MODEL=kimi-k2.6
# KIMI_BASE_URL=https://api.moonshot.ai/v1
# FREELLMAPI_API_KEY=your_freellmapi_unified_key_here
# FREELLMAPI_MODEL=auto
# FREELLMAPI_BASE_URL=http://localhost:5173/v1
# AGNES_API_KEY=your_agnes_api_key_here
# AGNES_MODEL=agnes-2.0-flash
# AGNES_BASE_URL=https://apihub.agnes-ai.com/v1
# PAICLI_GITHUB_TOKEN=github_pat_or_gh_token_here
# PAICLI_GITHUB_API_BASE_URL=https://api.github.com
# PAICLI_GITHUB_GRAPHQL_URL=https://api.github.com/graphql
# XFYUN_MAAS_API_KEY=your_xfyun_maas_api_key_here
# XFYUN_MAAS_MODEL=Qwen3.6-35B-A3B
# XFYUN_MAAS_BASE_URL=https://maas-api.cn-huabei-1.xf-yun.com/v2
# XFYUN_MAAS_LORA_ID=0
EMBEDDING_PROVIDER=ollama
EMBEDDING_MODEL=nomic-embed-text:latest
EMBEDDING_BASE_URL=http://localhost:11434
# EMBEDDING_API_KEY=your_api_key_here
# PAICLI_LOG_LEVEL=INFO
# PAICLI_LOG_DIR=/Users/yourname/.paicli/logs
# PAICLI_LOG_MAX_HISTORY=7
# PAICLI_LOG_MAX_FILE_SIZE=10MB
# PAICLI_LOG_TOTAL_SIZE_CAP=100MB
# PAICLI_SNAPSHOT_ENABLED=true
# PAICLI_SNAPSHOT_MAX=50
# PAICLI_SNAPSHOT_EXCLUDES=.git,.paicli/snapshots,target,node_modules,dist,.idea,*.class,*.jar
# PAICLI_SNAPSHOT_DIR=/Users/yourname/.paicli/snapshots
# PAICLI_TUI=true
# NO_TUI=true
```

---

## Test Coverage Summary

测试覆盖偏向：解析、计划结构、RAG 核心、Multi-Agent 编排、HITL 策略、策略层拦截、MCP 协议、资源输入层、长上下文策略与 Skill 加载。

不覆盖：真实 LLM 联调、真实 Embedding API、真实 MCP server 联调、终端完整手工体验。

完整测试类列表：CliCommandParserTest / MainBrowserCommandTest / PlanReviewInputParserTest / MainInputNormalizationTest / ExecutionPlanTest / MemoryEntryTest / ConversationMemoryTest / LongTermMemoryTest / MemoryRetrieverTest / MemoryManagerTest / ExplicitMemoryHintsTest / ContextProfileTest / PlanExecuteAgentTest / AgentMemoryHintTest / AgentRoleTest / AgentMessageTest / AgentOrchestratorTest / EmbeddingClientTest / SearchResultTest / NetworkPolicyTest / HtmlExtractorTest / WebFetcherTest / SearchProviderFactoryTest / ZhipuSearchProviderTest / VectorStoreTest / CodeChunkerTest / CodeAnalyzerTest / CodeIndexTest / ApprovalPolicyTest / ApprovalResultTest / HitlToolRegistryTest / TerminalHitlHandlerTest / ToolRegistryTest / BrowserSessionTest / BrowserConnectivityCheckTest / SensitivePagePolicyTest / BrowserGuardTest / McpSchemaSanitizerTest / McpConfigLoaderTest / JsonRpcClientTest / McpToolBridgeTest / McpResourceCacheTest / AtMentionParserTest / AtMentionExpanderTest / AtMentionCompleterTest / NotificationRouterTest / PathGuardTest / CommandGuardTest / AuditLogTest / SkillFrontmatterParserTest / SkillRegistryTest / SkillStateStoreTest / SkillBuiltinExtractorTest / SkillContextBufferTest / SkillIndexFormatterTest / LoadSkillToolTest / SkillCommandHandlerTest
