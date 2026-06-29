# Code Search Golden Set

PaiCLI 的代码理解默认走 `glob_files -> grep_code -> read_file`，`search_code` 只作为语义辅助。这个 golden set 用来固定确定性搜索链路的最低质量线：给定一个真实代码问题，`grep_code` 必须在预算内定位到预期文件和行号，随后 `read_file offset/limit` 必须能读取到目标上下文。

## 运行命令

```bash
mvn test -Dtest=CodeSearchGoldenSetTest -DskipTests=false
```

`mvn test -Pquick` 也会覆盖该测试。

## 当前评测内容

用例文件：`src/test/resources/code-search/golden-set.json`

每个 case 包含：

- `question`：用户可能提出的自然语言问题，用作评测语义说明
- `pattern`：本轮确定性 grep 关键词
- `glob`：限定候选文件范围
- `expectedPath`：应命中的目标文件
- `expectedText`：命中后 `read_file` 应读到的关键代码片段

测试逻辑：

1. 强制 `grep_code` 使用 Java fallback，避免 CI 依赖本机是否安装 `ripgrep`
2. 限制 `max_chars=6000`，模拟单轮工具结果预算
3. 断言 `grep_code` 返回 `expectedPath:line`
4. 断言结果包含 `suggested_reads`
5. 用 `read_file offset/limit` 读取命中附近 80 行并确认包含 `expectedText`

## 扩展规则

新增 case 时优先选择真实用户会问的问题，例如：

- 命令入口在哪里处理？
- 某个安全策略在哪里拦截？
- 某个多模态或 MCP 行为由哪个类负责？
- 某个渲染或 TUI 行为在哪里落地？

`pattern` 应该能代表 Agent 在第一轮会提取出的明确符号、字符串或文案。若问题只能靠模糊语义定位，先不要放进这个 deterministic golden set，应单独进入 `search_code` / RAG fallback 评测。

## 后续指标

这个测试先保证 correctness。后续可以在独立 benchmark 中补充：

- P50 / P95 搜索耗时
- 命中文件排名
- 三轮内是否读到正确代码段
- grep/read 总字符量或 token 估算
- 是否错误优先调用 `search_code`
