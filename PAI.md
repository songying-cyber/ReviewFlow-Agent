# PAI.md

## Commands

- 构建：`mvn clean package` 默认跳过测试，优先产出可手工验收 jar。
- 常规回归：`mvn test -Pquick`；TUI 相关跑 `mvn test -Pphase16-smoke`。
- 针对性测试：`mvn test -Dtest=XxxTest -DskipTests=false`。

## What This Is

PaiCLI 是面向商业使用的 Java Agent CLI 产品，对标 Claude Code；当前主路径是 ReAct、Plan-and-Execute、Multi-Agent 三套执行模式。

## Architecture

- 三条执行路径共享 `ToolRegistry` / `MemoryManager` / `SnapshotService`，不要为某个模式创建孤立能力。
- 精确代码定位优先 `glob_files` / `grep_code` / `read_file`，`search_code` 只做 RAG 语义辅助。
- system prompt 由 `PromptAssembler` 分层组装，内置 prompt 在 `src/main/resources/prompts/`，支持 `~/.paicli/prompts/` 和 `.paicli/prompts/` 覆盖。

## Things That Will Bite You

- 改行为要同步 `AGENTS.md` / `README.md` / `ROADMAP.md`；路线图只在状态变化时更新。
- 改命令入口要联动 `Main.java`、`CliCommandParser.java`、测试、`README.md`、`AGENTS.md`。
- 改工具集要联动 `ToolRegistry.java`、Agent/Plan/SubAgent 提示词和文档。
- 长期记忆只通过 `/save` 或用户明确要求保存；不要自动提取临时事实。
- `ctx` 表示下一轮仍会带入请求的上下文估算；`in/out/cache` 表示最近任务 LLM 调用统计，不要混用。

## Don't

- 不提交 `.env`、真实 API Key、`target/` 产物。
- 不把 `ROADMAP.md` 的未来计划写成已交付能力。
- 不在交互主路径新增裸 `System.out.println`；优先走 `Renderer.stream()`。
