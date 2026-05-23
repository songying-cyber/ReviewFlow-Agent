# 第 17 期：LSP 诊断注入

> 当前状态：MVP 已接入。先用 JavaParser 做 Java 语法诊断，打通 post-edit hook 和下一轮 LLM 注入链路；真实 LSP server 进程池留给后续增强。

## 目标

Agent 写完代码后，不等用户手动执行 `mvn compile`，就能在下一轮推理前看到刚引入的明显语法错误。第 17 期的关键不是一次性做满所有语言服务器，而是先把稳定的扩展点打通：

1. 文件写入成功后触发 post-edit 诊断。
2. 诊断不会阻塞工具主流程。
3. 诊断在下一轮 LLM 请求前以合成 user message 注入。
4. ReAct、Plan-and-Execute、Multi-Agent 三条路径共享同一机制。

## 已实现范围

- `src/main/java/com/paicli/lsp/LspManager.java`
  - 持有项目根路径
  - 对 `*.java` 文件运行 JavaParser 语法诊断
  - 按文件维护 pending diagnostics
  - `flushPendingDiagnostics()` 一次性返回并清空诊断
- `ToolRegistry.write_file`
  - 成功写入后触发 `runPostEditLspHook`
  - hook 失败只记录 trace，不影响 `write_file` 工具结果
- `Agent` / `PlanExecuteAgent` / `SubAgent`
  - 每轮 LLM 请求前调用 `flushPendingLspDiagnostics()`
  - 有诊断时渲染给用户，并追加 `[LSP 诊断注入]` 合成 user message
- `LspDiagnosticFormatter`
  - 按 severity、文件、行列号、message 输出
  - 默认最多注入 20 条

## 配置

```bash
# 默认开启
PAICLI_LSP_ENABLED=true

# 默认 20
PAICLI_LSP_MAX_DIAGNOSTICS=20
```

系统属性同样支持：

```bash
-Dpaicli.lsp.enabled=false
-Dpaicli.lsp.max.diagnostics=20
```

## 当前边界

- 只做 Java 语法级诊断，不做完整 Maven 编译。
- 不启动 JDT LS / rust-analyzer / pyright / gopls。
- 不解析 `execute_command` 里的手写补丁，也不支持 `edit_file` / `apply_patch` 工具，因为当前 PaiCLI 内置工具里还没有这两个工具。
- 多 Agent 并发时 pending diagnostics 挂在共享 `ToolRegistry` 上，下一条进入 LLM 请求的执行链会消费诊断。

## 后续增强

- 抽出 `LanguageDiagnosticProvider` 接口。
- 接入 JDT LS stdio JSON-RPC：`initialize`、`textDocument/didOpen`、`didChange`、`publishDiagnostics`。
- 为 Rust / Python / Go 增加可选 server 探测和 graceful fallback。
- 把诊断展示接入 Renderer 专用方法，而不是当前的文本块渲染。

## 验证

```bash
mvn test -Dtest=LspManagerTest,AgentLspDiagnosticsTest
```
