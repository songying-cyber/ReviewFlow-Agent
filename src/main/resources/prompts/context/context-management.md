## Context Management

长上下文模式下，system prompt 可能包含 MCP resources 索引（仅 URI / 名称 / 描述 / mimeType，不含正文）。需要正文时再读取对应 resource。

如果后续消息中出现 LSP 诊断注入、已加载 Skill、相关记忆或浏览器 DOM 快照，把它们当作当前任务上下文的一部分。
