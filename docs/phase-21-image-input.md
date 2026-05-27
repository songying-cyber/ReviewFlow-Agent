# 第 21 期：图片复制粘贴输入

> 当前状态：MVP 已落地。第 20 期后台任务 + Runtime API 仍未纳入本次实现；本期只打通图片输入协议与 Agent 回灌链路。

## 目标

让 PaiCLI 能把图片作为真正的 LLM 输入，而不是只把 MCP 工具返回的 image content 格式化成占位文字。

## 已交付

- `LlmClient.Message` 支持 `ContentPart`：
  - `text`
  - `image_base64`
  - `image_url`
- 请求体适配 content array：
  - 纯文本消息仍输出旧的 string content
  - 图片消息输出 `[{type:"text"}, {type:"image_url"}]`
  - base64 图片转为 `data:<mime>;base64,<payload>`
- 公共 `LlmClient` 接口不做图片能力声明：
  - 含图片时统一序列化为 image block 上传
  - provider API 负责最终接收或返回错误
- MCP image content 结构化保留：
  - `McpContent` 读取 `data` / `mimeType`
  - `McpCallToolResult.toToolOutput()` 生成文本 fallback + 图片附件
  - `ToolRegistry.executeTools()` 返回的 `ToolExecutionResult` 保留 image parts
- 图片预处理采用 Claude Code 同类策略：
  - 图片不是 OCR 成文本，而是以 `base64 + mimeType` 作为图片块发送
  - 本地 `@image:` 与 MCP `image` content 都先经过统一处理器
  - 带 alpha 通道的小 PNG 会先铺白底重编码，避免不同 provider 对透明背景处理不一致
  - 大图先等比缩放到 2000x2000 范围内，并压缩到 5MB base64 API 上限内
  - 额外注入图片来源、原始尺寸、缩放后尺寸和坐标映射提示，不注入图片内容描述
  - Agent 回灌链路：
  - ReAct / Plan task executor / SubAgent 在工具结果后追加图片 user message
  - 本地 `@image:` 消息按「文本说明 / source 元信息 / 图片 block」顺序发送，图片 block 保持最后，避免模型优先盯着路径文本走工具兜底
  - 本地 `@image:` 文本会明确要求优先分析本轮图片；除非用户要求结合历史，历史对话、历史工具结果、网页 / 仓库信息只能作为背景，不能替代当前图片内容
  - 新一轮 ReAct / SubAgent 任务开始前会把历史里的 image payload 替换为文本占位，保留 `Image source` 元信息，避免旧截图在同一会话里反复消耗上下文并污染新图分析
  - CLI 输入层不按模型名拦截图片，统一附加为图片块
  - tool message 仍保留文本 fallback，保证工具调用协议不被破坏
- 用户输入层：
  - 支持 `@image:file:///abs/path.png`
  - 支持 `@image:/abs/path.png` / `@image:relative/path.png`
  - 支持 `@image:<file:///path with spaces.png>`
  - 接受 `image/*` MIME；处理后的单图 base64 必须不超过 5MB

## 当前边界

- 不做视频 / 音频输入
- 不做图像生成
- 不做 TUI sixel 图片预览
- 不把图片 OCR 成文本；图片统一以 image block 附加，provider API 负责最终能力校验
- 对图片输入 token 成本只做粗略估算，真实计费仍以 provider usage 为准
- 不按模型名在 PaiCLI 输入层拦截图片；如果 provider 不接受某个模型 / endpoint 的图片输入，应暴露 provider 的真实错误或走 provider 内部图片请求路由

## 验证

```bash
mvn test -Dtest=AbstractOpenAiCompatibleClientImageInputTest,ImageReferenceParserTest,McpClientTest,McpToolRegistrationTest,LlmClientFactoryTest,TokenBudgetTest
```

覆盖点：

- OpenAI 兼容 content array 序列化
- 本地 `@image:` 引用解析
- 图片等比缩放、JPEG 压缩和元信息生成
- MCP image content 转为 image parts
- ToolRegistry 批量工具结果保留图片附件
- TokenBudget 对图片输入做估算
