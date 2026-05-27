# Prompt 质量审计模板

每次修改 `src/main/resources/prompts/` 或覆盖 `~/.paicli/prompts/` / `.paicli/prompts/` 后，建议按下面模板做一次审计。

## Change

- 修改的 prompt 文件：
- 修改目的：
- 影响模式：ReAct / Plan / Team Planner / Team Worker / Team Reviewer / Planner

## Gap Analysis

- 原 prompt 的问题：
- 新 prompt 如何解决：
- 是否引入重复规则：
- 是否引入互相冲突的规则：
- 是否把动态上下文放在稳定内容之前：

## Safety Check

- 是否保留 `## Language`：
- 是否保留路径围栏 / 命令黑名单 / HITL / AuditLog / `revert_turn` 规则：
- 是否会诱导模型绕过策略拒绝：
- 是否会鼓励不必要的工具调用：

## Verification

- 跑过的测试：
- 用到的手工任务：
- 观察到的模型行为变化：

## Decision

- 接受 / 回滚 / 继续调整：
- 后续跟踪项：
