# 第 21 期：图片复制粘贴手动验证用例

> 配合 `docs/phase-21-image-input.md` 使用。这里只列手动验证流程，不重复实现细节。

## 前置准备

1. 准备测试图片，建议放在 `~/paicli-image-cases/` 下：
   - `shot.png`：正常 PNG，<100KB
   - `large.png`：体积 >5MB（可用 `dd if=/dev/urandom of=large.png bs=1M count=6`，再手动覆盖前几个字节让 MIME 探测出 `image/png`，或直接拿一张高分辨率截图）
   - `not-image.txt`：纯文本文件
   - `path with spaces.png`：复制 `shot.png` 改名而来
   - `中文截图.png`：复制 `shot.png` 改名而来
2. CLI 输入层不要求先切换模型；是否接受 image block，以 provider API 返回为准。

---

## 1. 基础链路

### Case 1 — 普通模型也应附加图片块

`/model deepseek`：

```
帮我分析下这张截图 @image:./shot.png
```

预期：CLI 不出现“未抓取剪贴板图片 / 请切换模型”一类拦截提示；请求仍按 `text + image_url/base64` 图片输入结构发送。若 provider 拒绝当前请求，应显示 provider 返回的真实错误。

### Case 2 — 图片应完整附加

```
描述这张图里有什么 @image:./shot.png
```

预期：note 行 `[已附加图片: ./shot.png, mimeType=image/png, bytes=…]`；模型回复内容明显是基于图像描述。

---

## 2. 边界保护

### Case 3 — 5MB 上限拦截

```
看一下这张大图 @image:./large.png
```

预期：`[图片引用无效: ./large.png，原因: 图片超过 5MB 上限]`。

### Case 4 — 非图片 MIME 拦截

```
看一下 @image:./not-image.txt
```

预期：`[图片引用无效: …，原因: 不是受支持的图片 MIME 类型: text/plain]`。

### Case 5 — 文件不存在

```
@image:./不存在.png 看看这是什么
```

预期：`[图片引用无效: ./不存在.png，原因: 文件不存在]`，不抛栈。

---

## 3. 路径解析

### Case 6 — file:// 含空格（修复后）

```
看下 @image:<file:///Users/.../path with spaces.png>
```

预期：成功附加。修复前会报 `Illegal character in path at index 12`。

### Case 7 — file:// 含非 ASCII（修复后）

```
看下 @image:<file:///Users/.../中文截图.png>
```

预期：成功附加。修复前会报 `Bad escape`。

### Case 8 — 裸路径包尖括号

```
看下 @image:</Users/.../path with spaces.png>
```

预期：成功附加（裸路径不走 URI.create，本就支持空格）。

### Case 9 — 贪婪匹配吞中文标点

```
帮我看 @image:./shot.png。这个里面是什么？
```

预期：报"文件不存在"，因为 `shot.png。` 被当成完整路径。改用 `<…>` 显式包裹后能避免：

```
帮我看 @image:<./shot.png>。这个里面是什么？
```

---

## 4. MCP 浏览器截图

### Case 10 — take_screenshot 走图片附件回灌

```
打开 https://www.apple.com 然后用 take_screenshot 截图，告诉我首页主视觉里有什么
```

预期：
- tool 文本里出现 `[此工具返回了 image: …]` fallback
- 紧接其后有一条 user message 带真图（log 可见）
- 模型回复显然依赖图像

### Case 11 — MCP 图片大小上限（修复后）

人工触发一张 >5MB 截图（高分辨率全屏 + emulate 大尺寸视口）：

```
emulate 视口为 7680x4320，打开 https://www.apple.com，take_screenshot
```

预期：fallback 文本里出现"图片超过 5MB 上限，未作为图片输入附加"或类似提示，下一轮 LLM 请求 body 不会塞这张图。

### Case 12 — 普通模型对 MCP 截图退化为文本

`/model deepseek`：

```
打开 https://example.com，take_screenshot
```

预期：tool 返回 `[此工具返回了 image: …]` fallback，模型不会拿到真图。

---

## 5. 多 Agent 路径

### Case 13 — Plan 模式

```
/plan 把 @image:./shot.png 里看到的 UI 元素整理成一份 Markdown 文档放到 docs/ui-spec.md
```

预期：plan 第一个 task 拿到图（task 输入也走 ImageReferenceParser），生成的 Markdown 内容来自图。

### Case 14 — Team 模式

```
/team 帮我分析 @image:./shot.png 这个设计稿，由 planner 拆任务，coder 落代码骨架
```

预期：SubAgent.execute 也能附图（同 Agent 走 ImageReferenceParser.userMessage）。

---

## 6. 剪贴板图片

### Case 17 — `@clipboard` token

先用系统截图（macOS `Cmd+Shift+Ctrl+4` / Windows `Win+Shift+S` / Linux `gnome-screenshot --clipboard`）把图复制到剪贴板，然后：

```
帮我看看这张图 @clipboard
```

预期：
- 自动写入 `~/.paicli/cache/clip-<ts>.png`
- note 行 `[已附加图片: 剪贴板 (clip-<ts>.png), mimeType=image/png, bytes=…]`
- LLM 回复明显基于图像内容

### Case 18 — Ctrl+V 注入

光标停在 PaiCLI 输入框，先复制好图（同 Case 17），然后按 **Ctrl+V**（不是 Cmd+V，Cmd+V 会被终端拦截成本地粘贴文本）。

预期：
- 输入行末尾自动追加 `@image:</Users/.../clip-<ts>.png> `
- 继续敲文字（如"分析下"）后回车，等同于 Case 17 的效果

### Case 19 — 剪贴板里没图时的提示

清空剪贴板（复制一段文字），按 Ctrl+V：

预期：终端打印 `⚠️ Ctrl+V 抓图失败: 剪贴板里没有图片，请先截图后再触发…`，输入行不被破坏。

打字 `@clipboard` 也应得到对应的 `[图片引用无效: 剪贴板，原因: …]` 提示。

### Case 20 — headless 环境（如 ssh / docker）

通过 `ssh user@host` 进入远程主机，启动 PaiCLI，然后：

```
@clipboard
```

预期：`[图片引用无效: 剪贴板，原因: 当前环境无 GUI（headless），无法读取系统剪贴板]`。Ctrl+V 同理弹相同提示。

---

## 7. 复合输入

### Case 15 — 多张图同条消息

```
对比一下这两张截图差异 @image:./before.png @image:./after.png
```

预期：两张都被附加，note 区分别列两条 `[已附加图片…]`。

### Case 16 — ReAct 中工具 + 图同时存在（验证 tool-role 兼容性）

```
打开 https://news.ycombinator.com，截图后告诉我现在排在第一的标题
```

观察点：
- 服务端是否对 `assistant(tool_calls) → tool(text) → user(text+image)` 顺序报错
- 模型是否在第二轮基于图片给出正确标题
- token 统计是否合理

---

## 8. 回归

```
mvn test -Dtest=AbstractOpenAiCompatibleClientImageInputTest,ImageReferenceParserTest,ClipboardImageTest,McpCallToolResultTest,LlmClientFactoryTest,TokenBudgetTest,McpClientTest,McpToolRegistrationTest
mvn test -Pquick
```
