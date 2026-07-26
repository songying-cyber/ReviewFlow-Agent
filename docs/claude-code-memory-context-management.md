# Claude Code 记忆机制与上下文管理复现说明

生成日期：2026-08-12

本文基于本仓库 `claude code代码/` 目录源码和 Claude Code 官方文档整理。目标是让一个全新的 Codex 对话读完后，能够最大程度复现 Claude Code 的上下文管理与记忆功能。

## 资料来源

本地源码入口：

- `claude code代码/context.ts`
- `claude code代码/constants/prompts.ts`
- `claude code代码/utils/queryContext.ts`
- `claude code代码/utils/claudemd.ts`
- `claude code代码/memdir/*.ts`
- `claude code代码/utils/attachments.ts`
- `claude code代码/query.ts`
- `claude code代码/services/compact/*.ts`
- `claude code代码/services/extractMemories/*.ts`
- `claude code代码/utils/sessionStorage.ts`
- `claude code代码/commands/compact/compact.ts`
- `claude code代码/commands/context/context-noninteractive.ts`

官方文档：

- Claude Code Memory: https://code.claude.com/docs/en/memory
- Claude Code Context Window: https://code.claude.com/docs/en/context-window
- Claude Code Model Config: https://code.claude.com/docs/en/model-config
- Claude Code Settings: https://code.claude.com/docs/en/settings
- Claude Code Commands: https://code.claude.com/docs/en/commands
- Claude Code Headless: https://code.claude.com/docs/en/headless
- Claude Code Features Overview: https://code.claude.com/docs/en/features-overview

## 总体模型

Claude Code 把“记忆”和“上下文”拆成几个层次：

1. System prompt：工具规则、运行环境、模型行为、auto memory 使用说明等。
2. User context：以用户消息形式注入的项目说明、`CLAUDE.md`、`MEMORY.md` 索引、当前日期等。
3. System context：会话启动时的动态系统上下文，例如 git status 快照。
4. Conversation messages：用户输入、助手输出、tool use、tool result、attachment、compact boundary。
5. File-based memory：用户维护的 `CLAUDE.md` 与 Claude 自动维护的 auto memory。
6. Session transcript：JSONL 追加日志，用于 `/resume`、分支链、compact 后恢复。
7. Compaction：在上下文接近窗口上限时，用结构化摘要替换旧消息，并重新注入必要的启动上下文和少量附件。
8. Subagent/fork：大范围搜索或记忆提取用独立上下文执行，只把摘要或记忆文件写回主会话。

官方文档也明确把跨会话知识分成两类：`CLAUDE.md` 是人写的持久指令；auto memory 是 Claude 自己写的跨会话笔记。每个新会话仍然从一个新上下文窗口开始，持久信息靠这些文件重新加载。

源码对应：

- `context.ts:113-188` 构建并缓存 `systemContext` 与 `userContext`。
- `utils/queryContext.ts:30-74` 同时取 `systemPrompt`、`userContext`、`systemContext`，作为 API cache-key prefix。
- `QueryEngine.ts:288-325` 组装最终 `systemPrompt`，再把 `userContext` 和 `systemContext` 传入 `query()`。
- `query.ts:449-467` 每轮调用前追加 `systemContext`，并在调用模型前执行 microcompact 与 autocompact。

## 启动上下文组装

### System prompt

`getSystemPrompt()` 返回一个字符串数组，每个元素是一段 prompt section。

重要行为：

- 简单模式或 bare 模式下，`CLAUDE_CODE_SIMPLE` 使 system prompt 简化，只包含身份、CWD 和日期。
- 正常模式下，system prompt 会包含 session guidance、memory mechanics、环境信息、语言、output style、MCP 指令、工具结果总结规则等。
- auto memory 的“如何保存/读取记忆”说明位于 system prompt 的 `memory` section。

源码：

- `constants/prompts.ts:444-454`：`CLAUDE_CODE_SIMPLE` 分支。
- `constants/prompts.ts:491-496`：`systemPromptSection('memory', () => loadMemoryPrompt())`。
- `memdir/memdir.ts:419-507`：`loadMemoryPrompt()` 按 auto memory / team memory / assistant daily log 模式生成记忆使用说明。

复现建议：

```text
system_prompt = [
  base_identity_and_behavior,
  tool_usage_rules,
  memory_mechanics_prompt_if_auto_memory_enabled,
  environment_info,
  language_or_output_style,
  mcp_or_dynamic_tool_instructions,
  cache_boundary_marker_if_needed
]
```

### User context

`getUserContext()` 注入：

- `claudeMd`：聚合后的 `CLAUDE.md`、`.claude/rules/*.md`、auto memory entrypoint 等。
- `currentDate`：今天日期。

源码：

- `context.ts:155-188`：读取 memory files，过滤后变成 `claudeMd`，并注入 `currentDate`。
- `utils/claudemd.ts:1153-1195`：`getClaudeMds()` 把多个文件包成一个文本块，前缀为“Codebase and user instructions are shown below...”。

官方文档强调 `CLAUDE.md` 内容不是 system prompt，而是在 system prompt 后作为用户消息交给模型；因此它影响行为，但不是硬性策略。见 Memory 文档 `Claude isn’t following my CLAUDE.md` 小节。

复现建议：

```text
user_context = {
  claudeMd: rendered_memory_files_or_empty,
  currentDate: "Today's date is YYYY-MM-DD."
}
```

### System context

`getSystemContext()` 主要包含启动时 git 状态快照：

- 当前分支。
- main/default 分支。
- git user。
- `git status --short`，最多 2000 字符。
- 最近 5 条 commit。

源码：

- `context.ts:36-111`：读取 git 状态。
- `context.ts:116-149`：组装 `systemContext`。

注意：官方上下文可视化文档说明，启动前会加载 `CLAUDE.md`、auto memory、MCP tool names、skill descriptions 等。源码里这些分别来自 user context、system prompt sections、tool schema 与附件系统，不一定在同一个函数中完成。

## `CLAUDE.md` 与规则文件

### 文件类型和加载顺序

源码注释给出明确顺序：

1. Managed memory：组织级全局指令。
2. User memory：`~/.claude/CLAUDE.md`。
3. Project memory：项目里的 `CLAUDE.md`、`.claude/CLAUDE.md`、`.claude/rules/*.md`。
4. Local memory：`CLAUDE.local.md`。

这些文件按“低优先级先加载，高优先级后加载”的原则拼接，越靠后模型越容易采纳。

源码：

- `utils/claudemd.ts:1-26`：加载顺序和 include 规则总注释。
- `utils/claudemd.ts:790-847`：Managed 和 User 文件。
- `utils/claudemd.ts:849-934`：从当前目录向上收集 Project 和 Local 文件，再 root -> cwd 顺序加载。
- `utils/claudemd.ts:936-977`：额外目录的 `CLAUDE.md` 加载。
- `utils/claudemd.ts:979-1007`：auto memory / team memory 的 entrypoint 加入 memory files。

官方文档对应：

- Memory 文档说明 `CLAUDE.md` 从当前工作目录向上查找，root 到 cwd 顺序注入，`CLAUDE.local.md` 在同层 `CLAUDE.md` 后追加。
- Memory 文档说明 `CLAUDE.md`、`.claude/CLAUDE.md`、`.claude/rules/*.md` 都可参与加载。
- Settings 文档说明 `claudeMdExcludes` 可排除部分 `CLAUDE.md` 或规则文件，但不能排除 managed policy 文件。

复现规格：

```text
discover_memory_files(cwd):
  result = []
  processed = set()

  result += managed_CLAUDE_md
  result += managed_rules_without_paths

  if user_settings_enabled:
    result += ~/.claude/CLAUDE.md
    result += ~/.claude/rules/**/*.md without paths

  dirs = ancestors(cwd) ordered root_to_cwd
  for dir in dirs:
    if project_settings_enabled:
      result += dir/CLAUDE.md
      result += dir/.claude/CLAUDE.md
      result += dir/.claude/rules/**/*.md without paths
    if local_settings_enabled:
      result += dir/CLAUDE.local.md

  if CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD:
    for dir in additional_dirs:
      result += dir/CLAUDE.md
      result += dir/.claude/CLAUDE.md
      result += dir/.claude/rules/**/*.md without paths

  if auto_memory_enabled and MEMORY.md exists:
    result += auto_memory/MEMORY.md as AutoMem

  return result
```

### `@path` imports

`CLAUDE.md` 可以用 `@path` 引入其他文件。官方文档说相对路径相对于包含该 import 的文件，非工作目录；支持递归 import，最大四跳；会跳过 Markdown code span 和 fenced code block。

源码实现：

- `utils/claudemd.ts:448-535`：从 Markdown token 中提取 `@path`，跳过 code/codespan，处理 `@./`、`@~/`、`@/` 和裸相对路径。
- `utils/claudemd.ts:537-685`：`MAX_INCLUDE_DEPTH = 5`，从 depth 0 开始，实际相当于主文件加最多 4 层 include；用 `processedPaths` 防止循环。
- `utils/claudemd.ts:94-227`：只允许文本类扩展名，避免二进制进入上下文。
- `utils/claudemd.ts:666-681`：include 文件会被追加到结果中，并带 parent 信息。

复现要点：

- 不要用正则直接扫整份 Markdown；至少要跳过 code fence 和 inline code。
- include 文件需要去重，路径比较要考虑 symlink / realpath / Windows 大小写。
- 对项目文件引用到工作区外的外部路径，需要信任/审批机制；源码通过 `hasClaudeMdExternalIncludesApproved` 控制，User scope 默认可信。

### Frontmatter 和 HTML 注释

规则文件可以有 YAML frontmatter：

- `paths:` 表示路径作用域规则。
- 没有 `paths:` 的规则启动即加载。
- 有 `paths:` 的规则只在匹配文件被读取时加载。

源码：

- `utils/claudemd.ts:249-279`：解析 frontmatter 的 `paths`。
- `utils/claudemd.ts:697-779`：递归读取 `.claude/rules/**/*.md`，按是否 conditionalRule 筛选。
- `utils/claudemd.ts:1354-1396`：判断 `paths` 是否匹配目标文件。
- `utils/claudemd.ts:281-334`：剥离 block-level HTML comments，保留代码块内注释。

官方文档对应：

- Memory 文档说明 `.claude/rules/` 可用 `paths` frontmatter 做 path-scoped rules。
- Context Window 文档说明 path-scoped rules 和 nested `CLAUDE.md` 在 compact 后会丢失，直到再次读到匹配文件才重新加载。

### Nested memory lazy load

当 Claude 读取某个子目录文件时，会触发 nested memory：

- FileReadTool 把被读取文件加入 `nestedMemoryAttachmentTriggers`。
- 附件系统根据该文件路径加载子目录中的 `CLAUDE.md`、`.claude/CLAUDE.md`、`CLAUDE.local.md`、unconditional rules、conditional rules。
- 已加载 nested memory 由 `loadedNestedMemoryPaths` 去重。

源码：

- `tools/FileReadTool/FileReadTool.ts:848`、`870`、`1038`：读取文件后触发 nested memory。
- `utils/attachments.ts:2165-2193`：消费 `nestedMemoryAttachmentTriggers`。
- `utils/claudemd.ts:1205-1396`：加载 managed/user conditional rules、nested directory memory、conditional project rules。

复现建议：

```text
on_file_read(path):
  pending_nested_memory_triggers.add(path)

before_next_model_call:
  for path in pending_nested_memory_triggers:
    attachments += memory_files_for_nested_dirs_and_matching_rules(path)
  pending_nested_memory_triggers.clear()
```

## Auto Memory

### 目录位置

官方文档：

- auto memory 默认开启。
- 每个项目有一个目录：`~/.claude/projects/<project>/memory/`。
- `<project>` 来源于 git repository；同一个 repo 的所有 worktree 和子目录共享同一 auto memory 目录。
- 可用 `autoMemoryDirectory` 覆盖。
- `MEMORY.md` 是索引，topic files 存详细内容。
- `MEMORY.md` 启动时只加载前 200 行或 25KB，取较小者；topic files 不在启动时加载，按需读取。

源码：

- `memdir/paths.ts:21-55`：auto memory 默认开启；`CLAUDE_CODE_DISABLE_AUTO_MEMORY`、`CLAUDE_CODE_SIMPLE`、remote 无持久存储、settings 都可关闭。
- `memdir/paths.ts:198-205`：优先 canonical git root，否则 project root。
- `memdir/paths.ts:207-235`：目录解析顺序：env override、settings override、`<base>/projects/<sanitized-git-root>/memory/`。
- `memdir/paths.ts:253-259`：entrypoint 是 `MEMORY.md`。
- `memdir/memdir.ts:34-39`：`ENTRYPOINT_NAME = MEMORY.md`，200 行，25KB。
- `memdir/memdir.ts:57-103`：对 `MEMORY.md` 做行数和字节截断，并追加 warning。

复现规格：

```text
is_auto_memory_enabled():
  if CLAUDE_CODE_DISABLE_AUTO_MEMORY truthy: return false
  if CLAUDE_CODE_DISABLE_AUTO_MEMORY explicitly false: return true
  if bare/simple mode: return false
  if remote mode and no persistent memory dir: return false
  if settings.autoMemoryEnabled is defined: return settings.autoMemoryEnabled
  return true

get_auto_memory_dir():
  if CLAUDE_COWORK_MEMORY_PATH_OVERRIDE valid: return it
  if settings.autoMemoryDirectory valid: return it
  base = CLAUDE_CODE_REMOTE_MEMORY_DIR or ~/.claude
  project_key = sanitize(canonical_git_root or project_root)
  return base/projects/project_key/memory/
```

### Auto memory prompt

`loadMemoryPrompt()` 不把所有 memory 内容塞进 system prompt。它生成的是“如何使用 memory 系统”的行为说明：

- 这个目录已经存在，直接用 Write/Edit 写，不要先 mkdir。
- 需要长期建立记忆系统。
- 用户明确要求 remember 时立即保存；要求 forget 时删除相关 entry。
- 记忆类型限定为 user / feedback / project / reference。
- 不保存可从代码、git、`CLAUDE.md` 推导的内容。
- 保存通常分两步：写 topic file，再在 `MEMORY.md` 加一行索引。
- `MEMORY.md` 是索引，不直接塞详细记忆。
- 搜索过去上下文时，优先搜 memory topic files，最后才搜 session transcript。

源码：

- `memdir/memdir.ts:199-266`：`buildMemoryLines()`。
- `memdir/memoryTypes.ts`：四类记忆、何时保存、不应保存、陈旧性校验。
- `memdir/memdir.ts:475-489`：auto memory 开启时确保目录存在并返回 prompt。

复现建议：把 memory prompt 放进 system prompt，而把 `MEMORY.md` 索引放进 user context。这样模型知道怎么写，也能看到索引。

### `MEMORY.md` 和 topic files 的关系

Claude Code 设计上让 `MEMORY.md` 成为非常短的索引：

```markdown
- [Testing feedback](feedback_testing.md) — integration tests use real DB
- [User profile](user_role.md) — user is backend-heavy, learning frontend
```

topic file 则带 frontmatter：

```markdown
---
name: Testing feedback
description: Integration testing policy and rationale
type: feedback
modified: 2026-08-12T10:00:00.000Z
---

Integration tests should hit a real database, not mocks.

Why: prior mock/prod divergence masked a migration bug.
How to apply: prefer testcontainers or local DB setup in future test plans.
```

源码支持：

- `memdir/memdir.ts:219-234`：保存记忆的两步过程。
- `memdir/memoryScan.ts:35-77`：扫描 topic files，不含 `MEMORY.md`，读取前 30 行 frontmatter。
- `memdir/memoryScan.ts:84-94`：把 headers 格式化为 manifest。

### 相关记忆召回

在新 turn 开始时，Claude Code 会启动一个不阻塞主循环的 memory prefetch：

1. 找最后一个真实用户消息。
2. 如果 auto memory 开启，且用户消息不是单词，启动 side query。
3. 扫描 memory 目录里最多 200 个 `.md` topic files，排除 `MEMORY.md`。
4. 读取每个文件 frontmatter 的 description / type / mtime。
5. 用 Sonnet 选择最多 5 个明确相关文件。
6. 读取这些文件前若干行/字节，作为 `relevant_memories` attachment 注入。
7. 如果 side query 没及时完成，本轮不等待，下个 loop iteration 再消费。

源码：

- `query.ts:297-304`：每个用户 turn 启动 `startRelevantMemoryPrefetch()`。
- `utils/attachments.ts:2196-2242`：选择 auto memory 或 agent memory 目录，调用 `findRelevantMemories()`，最多注入 5 个。
- `utils/attachments.ts:2268-2321`：读取被选中的 memory 文件，超限则截断并提示可用 Read 完整查看。
- `utils/attachments.ts:2334-2415`：prefetch 是 disposable，跟随 abort signal。
- `query.ts:1592-1614`：只在 prefetch 已 settled 时消费，不阻塞。
- `memdir/findRelevantMemories.ts:18-24`：选择 prompt 要求“只选确定有帮助的，最多 5 个”。
- `memdir/findRelevantMemories.ts:39-75`：返回 path + mtime，过滤已 surfaced 文件。
- `memdir/memoryScan.ts:21-27`：最多扫描 200 个文件，按 mtime 新到旧。

复现伪代码：

```text
on_user_turn_start(messages):
  handle = maybe_start_memory_prefetch(last_user_message)

maybe_start_memory_prefetch(input):
  if !auto_memory_enabled: return none
  if input is empty or one word: return none
  if already_surfaced_memory_bytes >= session_limit: return none
  async:
    headers = scan_memory_files(auto_memory_dir, max_files=200)
    selected = side_model_select(query=input, headers=headers, max=5)
    return read_selected_memory_files(selected)

after_tools_before_next_model_call:
  if handle.settled:
    inject relevant_memories attachments
  else:
    skip without waiting
```

### 自动提取并保存记忆

Claude Code 还有一个后台提取器，在主 turn 完成后从当前 transcript 中提取值得保存的长期记忆：

- 通过 forked agent 运行，继承主会话 prompt cache。
- 不写 transcript，避免和主线程竞争。
- 如果主 agent 已经在 auto memory 目录写过记忆，则跳过后台提取，避免重复。
- 工具权限限制：Read/Grep/Glob 允许；Bash 只允许只读命令；Edit/Write 只能写 auto memory 目录；REPL 可用但内部工具仍受同样限制。
- 运行最多 5 个 assistant turn。
- 保存成功后追加系统消息，例如 “Saved N memories”。

源码：

- `services/extractMemories/extractMemories.ts:1-10`：文件顶部说明它在完整 query loop 结束时运行。
- `services/extractMemories/extractMemories.ts:121-148`：检测自上次 cursor 后是否已有 memory 写入。
- `services/extractMemories/extractMemories.ts:166-220`：自动记忆提取 agent 的工具权限。
- `services/extractMemories/extractMemories.ts:329-360`：主 agent 已写 memory 时跳过。
- `services/extractMemories/extractMemories.ts:395-427`：预注入 memory manifest，forked agent 运行，`skipTranscript: true`，`maxTurns: 5`。
- `services/extractMemories/extractMemories.ts:463-496`：过滤掉 `MEMORY.md` 索引，只把 topic files 计为用户可见 memory。

复现伪代码：

```text
after_assistant_final_response(messages):
  if !auto_memory_enabled: return
  if main_agent_wrote_memory_since(last_cursor):
    advance_cursor()
    return
  if extraction_throttle_not_due: return

  manifest = scan_memory_files(auto_memory_dir)
  prompt = build_extract_memories_prompt(new_message_count, manifest)
  result = run_forked_agent(
    prompt,
    can_use_tool = auto_mem_permissions,
    skip_transcript = true,
    max_turns = 5
  )
  written_topic_files = written_paths excluding MEMORY.md
  if written_topic_files:
    append_system_memory_saved_message(written_topic_files)
```

## 消息循环中的上下文裁剪

Claude Code 每次真正调用模型前，不是直接把全量 messages 发给 API，而是构造一个 API view。

顺序大致是：

1. 从最近 compact boundary 之后取消息。
2. 应用 tool result budget。
3. 可选 history snip。
4. microcompact。
5. 可选 context collapse。
6. autocompact。
7. 追加 system context。
8. 调模型。

源码：

- `query.ts:365`：`getMessagesAfterCompactBoundary(messages)`。
- `query.ts:369-394`：tool result budget。
- `query.ts:396-410`：history snip。
- `query.ts:412-426`：microcompact。
- `query.ts:428-447`：context collapse。
- `query.ts:449-467`：autocompact。
- `commands/context/context-noninteractive.ts:16-21`：`/context` 也镜像 query 前的 transform，以显示模型实际看到的上下文。

复现关键点：UI 里可以保留完整滚动历史，但 API 请求必须只发“compact/snip/collapse 后的视图”。

## Microcompact

microcompact 是 auto-compact 前的轻量清理，优先减少旧 tool results。

源码中的 compactable tools：

- Read
- shell tools
- Grep
- Glob
- WebSearch
- WebFetch
- Edit
- Write

源码：

- `services/compact/microCompact.ts:40-50`：可 microcompact 的工具集合。
- `services/compact/microCompact.ts:253-292`：入口。

### Cached microcompact

如果支持 cache editing：

- 不改本地 message 内容。
- 记录要删除的 tool result id。
- 通过 API 层插入 cache edits。
- 保持 prompt cache 的 prefix 命中。
- 只在 main thread 路径跑，避免 fork/subagent 污染全局状态。

源码：

- `services/compact/microCompact.ts:295-304`：cached microcompact 设计说明。
- `services/compact/microCompact.ts:305-399`：注册 tool result、计算待删除项、生成 pending cache edits。

### Time-based microcompact

当距离上一次主线程 assistant message 的时间超过阈值，说明服务端 prompt cache 可能已冷：

- 直接把旧 tool results 的内容替换为 `[Old tool result content cleared]`。
- 至少保留最近 1 个 tool result。
- 重置 cached microcompact 状态。

源码：

- `services/compact/microCompact.ts:401-411`：time-based microcompact 说明。
- `services/compact/microCompact.ts:422-444`：判断触发条件。
- `services/compact/microCompact.ts:456-529`：清空旧 tool result 内容。

复现伪代码：

```text
microcompact(messages):
  if time_gap_since_last_assistant > threshold:
    clear all compactable tool_result contents except last keepRecent
    return mutated_messages

  if cache_editing_supported and main_thread:
    select old compactable tool_result ids
    queue cache_edits
    return messages_unchanged

  return messages
```

## Auto-compact 与 `/compact`

### 阈值

官方文档：

- 可通过 `/autocompact`、`--autocompact`、`CLAUDE_CODE_AUTO_COMPACT_WINDOW` 设置 auto-compact window。
- 如果未设置，Claude Code 根据模型使用默认阈值。
- `CLAUDE_CODE_MAX_CONTEXT_TOKENS` 可修正未知 gateway / custom model 的窗口。

源码：

- `services/compact/autoCompact.ts:28-49`：effective context window = model context window - reserved output tokens；最多预留 20,000 output tokens 给 summary。
- `services/compact/autoCompact.ts:62-65`：auto compact buffer 13,000，warning/error buffer 20,000，manual compact buffer 3,000。
- `services/compact/autoCompact.ts:72-91`：auto compact threshold = effective window - 13,000。
- `services/compact/autoCompact.ts:93-145`：计算 warning/error/autocompact/blocking 状态。
- `services/compact/autoCompact.ts:147-158`：`DISABLE_COMPACT` 关闭全部 compact，`DISABLE_AUTO_COMPACT` 只关自动 compact，settings 控制 `autoCompactEnabled`。
- `settings` 官方文档说明 `autoCompactEnabled` 默认 true，`autoCompactWindow` 可设 100000 到 1000000。

复现伪代码：

```text
effective_window = min(model_context_window, CLAUDE_CODE_AUTO_COMPACT_WINDOW if set) - min(model_max_output, 20000)
auto_threshold = effective_window - 13000
blocking_limit = effective_window - 3000

if auto_compact_enabled and token_count >= auto_threshold:
  compact()

if no automatic compaction path is allowed and token_count >= blocking_limit:
  return prompt_too_long_error
```

### Auto-compact 流程

源码：

- `services/compact/autoCompact.ts:160-239`：判断是否应该 compact，排除 `session_memory` 和 `compact` 自身，避免递归死锁。
- `services/compact/autoCompact.ts:241-351`：auto compact 主流程。
- `services/compact/autoCompact.ts:257-265`：连续失败超过 3 次熔断。
- `services/compact/autoCompact.ts:287-310`：优先尝试 session memory compaction。
- `services/compact/autoCompact.ts:312-333`：否则调用传统 `compactConversation()`。

### `/compact` 手动流程

手动 `/compact` 与自动 compact 共用核心逻辑，但支持用户自定义 compact instructions。

源码：

- `commands/compact/compact.ts:40-53`：取最近 compact boundary 后的 messages，读取用户自定义 instructions。
- `commands/compact/compact.ts:55-83`：无自定义 instructions 时先试 session memory compaction。
- `commands/compact/compact.ts:96-108`：传统路径先 microcompact，再 `compactConversation()`。
- `commands/compact/compact.ts:250-287`：为 compact fork 构建 cache sharing params，包括 system prompt、user context、system context。

官方文档：

- Context Window 文档说明 `/compact` 会用结构化摘要替换会话历史，且自动 compact 与 `/compact` 的机制相同。
- Context Window 文档建议 `/compact focus on ...` 可让摘要偏向用户指定主题。

## `compactConversation()` 细节

### Compact 摘要 prompt

摘要 prompt 要求模型输出：

- `<analysis>`：思考草稿，后续会剥离，不进入新上下文。
- `<summary>`：真正进入新上下文的摘要。
- 摘要包含 9 部分：
  1. Primary Request and Intent
  2. Key Technical Concepts
  3. Files and Code Sections
  4. Errors and fixes
  5. Problem Solving
  6. All user messages
  7. Pending Tasks
  8. Current Work
  9. Optional Next Step

源码：

- `services/compact/prompt.ts:19-26`：强制 compact agent 不调用工具，只输出文本。
- `services/compact/prompt.ts:61-143`：完整 compact prompt。
- `services/compact/prompt.ts:145-204`：partial compact prompt。
- `services/compact/prompt.ts:206-260`：prefix-preserving partial compact prompt。

复现建议：摘要 prompt 必须强调“所有用户消息”和“当前工作/下一步”，否则 compact 后容易漂移。

### Compact 执行流程

源码：

- `services/compact/compact.ts:387-395`：入口。
- `services/compact/compact.ts:401-424`：记录 pre-compact token count，执行 PreCompact hooks，合并 hook instructions。
- `services/compact/compact.ts:440-459`：构造 summary request，运行 compact summary。
- `services/compact/compact.ts:460-491`：如果 compact 请求本身 prompt too long，按 API round 从头部丢弃旧组并重试，最多 3 次。
- `services/compact/compact.ts:517-523`：保存 read file state，清理 read cache 和 nested memory loaded set。
- `services/compact/compact.ts:531-585`：并行生成 post-compact attachments：文件、异步 agent、plan、plan mode、invoked skill、deferred tools、agent listing、MCP instruction delta。
- `services/compact/compact.ts:591-594`：compact 后执行 SessionStart hooks。
- `services/compact/compact.ts:598-624`：创建 compact boundary 和 compact summary user message。
- `services/compact/compact.ts:626-645`：估算 compact 后真实 token 数，并读取 compaction API usage。
- `services/compact/compact.ts:697-711`：通知 prompt cache break detector，标记 post-compaction，重写 session metadata 到 transcript 尾部。
- `services/compact/compact.ts:723-729`：执行 PostCompact hooks。
- `services/compact/compact.ts:738-748`：返回 `CompactionResult`。

### Compact 后消息顺序

`buildPostCompactMessages()` 固定顺序：

1. boundary marker
2. summary messages
3. messagesToKeep
4. attachments
5. hook results

源码：

- `services/compact/compact.ts:325-338`。

复现规格：

```text
post_compact_messages = [
  compact_boundary,
  compact_summary_user_message,
  ...preserved_recent_or_partial_messages,
  ...post_compact_attachments,
  ...session_start_hook_results
]
```

### Compact 后重新注入什么

官方 Context Window 文档的“what survives compaction”说明：

- System prompt 和 output style 不变。
- Project-root `CLAUDE.md` 和 unscoped rules 会从磁盘重新注入。
- Auto memory 会从磁盘重新注入。
- `paths:` rules 和 nested `CLAUDE.md` 会丢失，直到匹配文件再次被读取。
- Invoked skill bodies 会重新注入，但每个 skill 上限 5,000 tokens，总上限 25,000 tokens，旧的先丢。

源码对应：

- `services/compact/compact.ts:122-130`：post compact 文件和 skill token budget。
- `services/compact/compact.ts:517-523`：清理 nested memory loaded set，意味着 nested memory 需要重新触发。
- `commands/compact/compact.ts:117-118`：手动 compact 后清 user context cache 和 post compact cleanup。
- `utils/claudemd.ts:1088-1130`：compact 导致 memory files cache 重置时，InstructionsLoaded hook reason 可标记为 `compact`。

## Session transcript 与 resume

Claude Code 的会话日志是 append-only JSONL。compact 不会物理删除旧 JSONL 行，而是在加载时通过 compact boundary / parentUuid chain 只恢复有效链。

### 为什么需要 compact boundary

compact 后，如果 resume 仍加载旧消息，摘要就没有意义，而且会立刻再次触发 auto-compact。源码做了两个关键处理：

1. in-memory query loop 只用最近 compact boundary 后的 messages。
2. transcript load 时跳过/剪掉 compact boundary 前的旧内容，并处理 preserved segment。

源码：

- `query.ts:365`：每轮 API view 从 `getMessagesAfterCompactBoundary(messages)` 开始。
- `QueryEngine.ts:687-731`：assistant/user/compact boundary 消息都会记录 transcript。
- `QueryEngine.ts:693-714`：写 compact boundary 前，如果有 preservedSegment tail，需要先 flush 到 transcript，否则 resume relink 会失败。

### Preserved segment relink

部分 compact 会保留一段原始消息。由于 JSONL 是 append-only，保留消息在磁盘上仍有 compact 前的 parentUuid，因此加载时要修补：

- preserved head 的 parent 指向 anchor。
- anchor 的其他 child 指向 preserved tail。
- preserved assistant messages 的 usage 清零，避免 resume 后用旧 usage 立即触发 auto-compact。
- 删除最后一个 compact boundary 之前、且不属于 preserved segment 的消息。

源码：

- `services/compact/compact.ts:340-367`：在 boundary 上写 preservedSegment metadata。
- `utils/sessionStorage.ts:1823-1956`：`applyPreservedSegmentRelinks()`。
- `utils/sessionStorage.ts:1920-1939`：清零 preserved assistant usage。
- `utils/sessionStorage.ts:1942-1955`：删除 pre-boundary stale messages。

### 大 transcript 加载优化

源码会在加载大 transcript 时避免把大量 stale 内容读入内存：

- 如果文件大于阈值，会先扫描 compact boundary，读取 post-boundary buffer。
- 如果跳过了 pre-boundary bytes，还会扫描 pre-boundary metadata，保留 session title、tag、mode、PR link 等。
- compact 时会把 session metadata 重新追加到尾部，避免 `/resume` 展示信息被挤出 tail read window。

源码：

- `utils/sessionStorage.ts:693-721`：re-append session metadata 的用途。
- `utils/sessionStorage.ts:764-824`：写 last prompt、title、tag、agent 信息、mode、worktree。
- `utils/sessionStorage.ts:3468-3579`：`loadTranscriptFile()` 大文件 pre-boundary skip。
- `utils/sessionStorage.ts:3581-3611`：读取 pre-boundary metadata。
- `utils/sessionStorage.ts:3704-3705`：加载后应用 preserved segment relink 和 snip removals。

复现建议：

```text
append_transcript(entry):
  write JSONL with uuid, parentUuid, sessionId, timestamp, message payload

on_compact_success:
  append metadata entries near EOF
  append compact_boundary with compactMetadata
  append compact_summary
  append post_compact_attachments

load_transcript(file):
  parse messages and metadata
  if compact_boundary exists:
    prune stale messages before latest boundary
    if boundary has preservedSegment:
      relink preserved segment into post-compact chain
      zero stale assistant usage in preserved segment
  compute leaf uuids from parent graph
  return latest chain for resume
```

## `/context`

`/context` 的目标不是简单估算当前 UI messages，而是展示“模型下一次 API 调用大约会看到什么”。

源码：

- `commands/context/context-noninteractive.ts:16-21`：说明它镜像 query 前 transform。
- `commands/context/context-noninteractive.ts:49-58`：从 compact boundary 后取消息，应用 context collapse 和 microcompact。
- `commands/context/context-noninteractive.ts:61-76`：调用 `analyzeContextUsage()`。
- `utils/contextAnalysis.ts:27-97`：按 human messages、assistant messages、tool requests、tool results、attachments、重复 Read 等做 token 统计。

复现建议：

- `/context` 不应该只显示 token 总数。
- 至少显示：模型、总 tokens / max window、system prompt、tools、MCP tools、memory files、skills、messages、tool results、attachments、free space、autocompact buffer。
- 对调试记忆加载，必须列出实际进入上下文的 memory files。

官方文档也建议用 `/context` 检查哪些 `CLAUDE.md` 和 auto memory 文件已加载；用 `/memory` 打开和编辑这些文件。

## 设置和环境变量

关键设置：

- `autoMemoryEnabled`：默认 true；关闭后不读写 auto memory。
- `autoMemoryDirectory`：自定义 auto memory 目录。
- `autoCompactEnabled`：默认 true。
- `autoCompactWindow`：自动 compact token 窗口，100000 到 1000000。
- `claudeMdExcludes`：跳过用户/项目/本地 memory 文件，managed policy 不能跳过。
- `cleanupPeriodDays`：启动时清理旧 session / app data。

关键环境变量：

- `CLAUDE_CODE_DISABLE_AUTO_MEMORY=1`：关闭 auto memory。
- `CLAUDE_CODE_SIMPLE` / `--bare`：跳过自动发现，关闭 auto memory 相关功能。
- `CLAUDE_CODE_REMOTE_MEMORY_DIR`：远程模式 memory base。
- `CLAUDE_COWORK_MEMORY_PATH_OVERRIDE`：直接覆盖 auto memory 目录。
- `CLAUDE_CODE_DISABLE_CLAUDE_MDS`：关闭 `CLAUDE.md` 读取。
- `CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD=1`：让 `--add-dir` 目录也加载 `CLAUDE.md` / rules。
- `DISABLE_COMPACT`：关闭全部 compact。
- `DISABLE_AUTO_COMPACT`：只关闭自动 compact，保留手动 `/compact`。
- `CLAUDE_CODE_AUTO_COMPACT_WINDOW`：设置自动 compact window。
- `CLAUDE_CODE_MAX_CONTEXT_TOKENS`：修正未知/custom model 的窗口推断。

来源：

- `memdir/paths.ts:21-55`。
- `context.ts:162-172`。
- `services/compact/autoCompact.ts:40-49`、`147-158`。
- Settings 官方文档。
- Model Config 官方文档。

## Subagent 与上下文隔离

官方 Features Overview 文档说明：

- Skills 是加载到主上下文的可复用内容。
- Subagents 是隔离 worker，有自己的上下文窗口。
- 当某个 side task 会读大量文件但主会话不需要中间过程时，应交给 subagent，只把摘要带回主上下文。

源码体现：

- memory extraction 通过 `runForkedAgent()` 执行，`skipTranscript: true`，避免污染主 transcript。
- relevant memory recall 如果用户 `@agent-xxx`，会只搜索该 agent 的 memory dir，实现 agent memory 隔离。

源码：

- `services/extractMemories/extractMemories.ts:415-427`。
- `utils/attachments.ts:2204-2214`。

复现建议：

```text
run_subagent(task):
  child_context = {
    system_prompt: inherited_or_agent_specific,
    user_context: inherited_if_fork_else agent_memory_only,
    messages: task_prompt_only_or_forked_messages,
    tools: selected_tools,
  }
  result = child_loop(child_context)
  main_context.append(summary(result))
```

不要把 subagent 的全部 tool results 回灌主会话；这会破坏上下文隔离。

## Prompt cache 相关设计

Claude Code 很重视 prompt cache：

- `fetchSystemPromptParts()` 将 system prompt、user context、system context 作为 API cache-key prefix。
- compact summary 尽量复用主会话 prompt cache。
- cached microcompact 尽量通过 cache edits 删除旧 tool results，而不修改本地消息内容。
- system prompt 有动态边界，边界前可用 global cache scope，边界后是用户/会话动态内容。

源码：

- `utils/queryContext.ts:1-9`：该文件专门构建 API cache-key prefix。
- `utils/queryContext.ts:30-43`：说明三块上下文构成 prefix。
- `constants/prompts.ts:105-115`：`SYSTEM_PROMPT_DYNAMIC_BOUNDARY`。
- `services/compact/compact.ts:431-438`：compact fork 默认启用 prompt cache sharing。
- `services/compact/microCompact.ts:295-304`：cached microcompact 不改本地 messages，cache edits 在 API 层添加。

复现建议：

- 固定 system prompt section 顺序。
- 动态 section 单独缓存，变化时清 cache。
- 让 compact/forked agent 共享父会话 cache-safe params。
- 不要在每轮把时间戳、随机数等动态内容塞进 system prompt prefix；日期可放 user context 并缓存一个 session。

## 最小可复现实现清单

一个尽量贴近 Claude Code 的实现，至少需要这些模块。

### 1. Memory file discovery

- 支持 managed/user/project/local 四级。
- 支持 `CLAUDE.md`、`.claude/CLAUDE.md`、`CLAUDE.local.md`、`.claude/rules/**/*.md`。
- root -> cwd 顺序加载。
- 同层 `CLAUDE.local.md` 后于 `CLAUDE.md`。
- `@path` imports，跳过代码块，限制深度，防循环。
- block-level HTML comments 剥离。
- `paths` frontmatter 规则启动时不加载，只在匹配文件读取时加载。
- `claudeMdExcludes` 排除非 managed 文件。

### 2. Auto memory

- 默认开启，可按 env/settings/bare/remote 状态关闭。
- 目录按 canonical git root 归一，同 repo worktree 共享。
- `MEMORY.md` 是索引，启动只加载 200 行或 25KB。
- topic files 不启动加载，按需召回。
- system prompt 注入“如何保存/读取 memory”的 mechanics。
- 保存记忆采用 topic file + `MEMORY.md` index 两步。
- topic file frontmatter 至少包含 `name`、`description`、`type`、`modified`。
- background extractor 用 forked agent 提取长期记忆，权限限制到 auto memory 目录。

### 3. Relevant memory recall

- 每 turn 非阻塞 prefetch。
- 扫描最多 200 个 `.md` topic files。
- 读取前 30 行 frontmatter。
- side model 选最多 5 个明确相关文件。
- 注入为 attachment，带 path、mtime、freshness。
- 限制每文件读取行数/字节，超限提示可用 Read。
- 防止同一 session 重复注入同一 memory。

### 4. Query API view

- API 请求前先取 compact boundary 后 messages。
- 应用 tool result budget。
- microcompact 旧 tool results。
- 如果启用 context collapse，在 auto compact 前先尝试局部 collapse。
- 达到 auto threshold 执行 compact。
- 若自动 compact 不可用且接近硬限制，返回 prompt-too-long 错误，保留手动 compact 空间。

### 5. Compact

- 自动和手动 compact 共用 `compactConversation()`。
- 手动 compact 支持自定义 focus instructions。
- compact summary prompt 必须结构化，并记录所有用户消息、文件、错误、当前工作、下一步。
- compact agent 禁止工具调用。
- compact 请求本身 prompt-too-long 时，按 API round 丢弃最旧组并重试。
- compact 后生成 boundary + summary + preserved messages + post-compact attachments + hooks。
- compact 后重新注入 project-root `CLAUDE.md`、unscoped rules、auto memory、invoked skills、tools/MCP delta、plan mode 等必要上下文。
- path-scoped rules 和 nested `CLAUDE.md` 不自动保留，等下次读匹配文件再触发。

### 6. Transcript / resume

- JSONL append-only，消息有 uuid / parentUuid。
- compact boundary 写入 compact metadata。
- resume 时只恢复最新 compact boundary 之后的有效链。
- preserved segment 需要 relink。
- preserved assistant usage 清零，避免恢复后立即再次 compact。
- metadata 在 compact 后 re-append 到文件尾部，保证 resume UI 可读到 title/tag/mode。

### 7. `/context` 和 `/memory`

- `/context` 显示模型实际将看到的 API view，而不是 UI 全历史。
- `/context` 列出 memory files、token 分类、free space、autocompact buffer。
- `/memory` 可列出/edit `CLAUDE.md`、`CLAUDE.local.md`、auto memory folder，可切换 auto memory。

## 推荐数据结构

```typescript
type Message =
  | UserMessage
  | AssistantMessage
  | AttachmentMessage
  | SystemCompactBoundaryMessage
  | SystemMessage

type MemoryFileInfo = {
  path: string
  type: 'Managed' | 'User' | 'Project' | 'Local' | 'AutoMem' | 'TeamMem'
  content: string
  parent?: string
  globs?: string[]
  contentDiffersFromDisk?: boolean
  rawContent?: string
}

type AutoMemoryTopic = {
  filename: string
  path: string
  mtimeMs: number
  description: string | null
  type?: 'user' | 'feedback' | 'project' | 'reference'
}

type CompactionResult = {
  boundaryMarker: SystemCompactBoundaryMessage
  summaryMessages: UserMessage[]
  messagesToKeep?: Message[]
  attachments: AttachmentMessage[]
  hookResults: SystemMessage[]
  preCompactTokenCount?: number
  postCompactTokenCount?: number
  truePostCompactTokenCount?: number
}

type CompactMetadata = {
  trigger: 'auto' | 'manual'
  preCompactTokenCount: number
  lastMessageUuid?: string
  preservedSegment?: {
    headUuid: string
    anchorUuid: string
    tailUuid: string
  }
  preCompactDiscoveredTools?: string[]
}
```

## 关键实现陷阱

1. 不要把 `CLAUDE.md` 当成硬性 system policy。官方文档和源码都把它作为用户上下文/用户消息；硬性阻止行为应该用 hooks/settings/permissions。
2. 不要启动时加载所有 auto memory topic files。只加载 `MEMORY.md` 索引，topic files 走相关性召回。
3. 不要让 `MEMORY.md` 变长文档。它是索引，200 行/25KB 外会被截断。
4. 不要在 compact 后保留全部 old messages。UI 可以显示，API view 和 resume 必须从 compact boundary 后恢复。
5. 不要在 compact 后丢掉 root `CLAUDE.md` 和 auto memory。官方文档明确它们会重新注入。
6. 不要指望 path-scoped rules compact 后仍在上下文。它们属于 message history，会被 summary 替代，直到再次匹配文件。
7. 不要在 memory extraction fork 里开放任意写工具。只能写 auto memory 目录。
8. 不要同步等待 memory recall side query。源码是 settled 才消费，没完成就跳过，避免拖慢主循环。
9. 不要用原始 transcript token usage 做 compact 后阈值判断。preserved segment 的旧 usage 要清零，否则 resume 后会立即 auto-compact。
10. 不要把同一 memory file 重复注入。用 readFileState、surfaced paths、session byte limit 去重和限流。

## 官方文档与源码差异/补充

官方文档给出产品级保证和用户可见行为；源码补充了工程细节：

- 官方说 `MEMORY.md` 只加载 200 行/25KB；源码实现同时在 `memdir/memdir.ts` 和 `utils/claudemd.ts` 里截断 AutoMem/TeamMem entrypoint。
- 官方说 topic files 按需读取；源码新增了 Sonnet side query 相关性选择，最多 5 个，并且是非阻塞 prefetch。
- 官方说 compact 会重新注入 root `CLAUDE.md`、auto memory、skill bodies；源码显示 compact 后还会恢复 plan mode、工具/MCP delta、agent listing、部分 file attachments、SessionStart hook 输出。
- 官方说 subagent 隔离上下文；源码的 auto memory extraction 就是 forked agent 模式，并且不写 transcript。
- 官方说 auto compact 接近窗口时触发；源码具体为 effective window 减 13,000 token buffer，并预留最多 20,000 output tokens 给 summary。

## 源码索引

启动与上下文：

- `claude code代码/context.ts:36-111`：git status 快照。
- `claude code代码/context.ts:116-149`：system context。
- `claude code代码/context.ts:155-188`：user context，加载 `CLAUDE.md` 和 currentDate。
- `claude code代码/utils/queryContext.ts:30-74`：构建 API cache-key prefix。
- `claude code代码/QueryEngine.ts:288-325`：最终 system prompt 组装。
- `claude code代码/query.ts:365-467`：API view transform 与 auto-compact。

`CLAUDE.md` / rules：

- `claude code代码/utils/claudemd.ts:1-26`：加载顺序总说明。
- `claude code代码/utils/claudemd.ts:249-334`：frontmatter 和 HTML comments。
- `claude code代码/utils/claudemd.ts:448-535`：`@path` imports。
- `claude code代码/utils/claudemd.ts:537-685`：递归处理 memory file。
- `claude code代码/utils/claudemd.ts:790-1075`：启动 discovery。
- `claude code代码/utils/claudemd.ts:1153-1195`：渲染为 `claudeMd`。
- `claude code代码/utils/claudemd.ts:1205-1396`：path-scoped rules 与 nested memory。

Auto memory：

- `claude code代码/memdir/paths.ts:21-55`：auto memory enable/disable。
- `claude code代码/memdir/paths.ts:198-235`：auto memory 目录。
- `claude code代码/memdir/memdir.ts:34-103`：`MEMORY.md` 限制与截断。
- `claude code代码/memdir/memdir.ts:199-266`：memory mechanics prompt。
- `claude code代码/memdir/memdir.ts:419-507`：加载 memory prompt。
- `claude code代码/memdir/memoryScan.ts:35-94`：扫描 topic files。
- `claude code代码/memdir/findRelevantMemories.ts:18-75`：相关记忆选择。
- `claude code代码/utils/attachments.ts:2196-2415`：相关记忆 prefetch 和 attachment。
- `claude code代码/services/extractMemories/extractMemories.ts:1-10`：后台提取说明。
- `claude code代码/services/extractMemories/extractMemories.ts:166-220`：提取器工具权限。
- `claude code代码/services/extractMemories/extractMemories.ts:329-496`：提取器运行与保存消息。

Compact：

- `claude code代码/services/compact/autoCompact.ts:28-91`：窗口、buffer、阈值。
- `claude code代码/services/compact/autoCompact.ts:93-158`：warning/blocking/启停。
- `claude code代码/services/compact/autoCompact.ts:160-351`：auto compact。
- `claude code代码/services/compact/microCompact.ts:40-50`：compactable tools。
- `claude code代码/services/compact/microCompact.ts:253-399`：cached microcompact。
- `claude code代码/services/compact/microCompact.ts:401-529`：time-based microcompact。
- `claude code代码/services/compact/prompt.ts:19-143`：compact summary prompt。
- `claude code代码/services/compact/compact.ts:325-367`：post compact order 和 preserved segment metadata。
- `claude code代码/services/compact/compact.ts:387-748`：compactConversation 核心。
- `claude code代码/commands/compact/compact.ts:40-137`：手动 `/compact`。

Transcript / resume：

- `claude code代码/QueryEngine.ts:436-463`：用户消息先写 transcript，保证 kill 后可 resume。
- `claude code代码/QueryEngine.ts:687-731`：assistant/user/compact boundary 写 transcript。
- `claude code代码/utils/sessionStorage.ts:693-824`：compact 后重写 session metadata。
- `claude code代码/utils/sessionStorage.ts:1823-1956`：preserved segment relink 和 prune。
- `claude code代码/utils/sessionStorage.ts:3468-3810`：加载 transcript。

诊断：

- `claude code代码/commands/context/context-noninteractive.ts:16-21`：`/context` 镜像 query transform。
- `claude code代码/commands/context/context-noninteractive.ts:49-76`：收集 context data。
- `claude code代码/utils/contextAnalysis.ts:27-97`：token 分类统计。

