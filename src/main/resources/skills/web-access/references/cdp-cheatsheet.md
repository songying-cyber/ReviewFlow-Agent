# Chrome DevTools MCP / CDP 速查

PaiCLI 第 13 期接入 Google 官方 `chrome-devtools-mcp@latest`（28 个工具），第 14 期加 `/browser connect` 切 shared 模式。

## 模式切换

| 模式 | 启动参数 | 登录态 | 用途 |
|---|---|---|---|
| isolated（默认） | `--isolated=true` | 无（临时 user-data-dir） | 公开页面：博客、文档、新闻、SPA 应用 |
| shared | `--browser-url=http://127.0.0.1:9222` | 复用用户 Chrome 全部登录态 | 私有仓、邮箱、内部系统、需要表单交互 |

切换：

```
/browser connect       # isolated → shared（先要求用户用 --remote-debugging-port=9222 启 Chrome）
/browser disconnect    # shared → isolated
/browser status        # 查当前模式 + 9222 探活
/browser tabs          # shared 模式下列 tab
```

用户启 Chrome 调试端口（写在引导里）：

```bash
# macOS
open -a "Google Chrome" --args --remote-debugging-port=9222

# Linux
google-chrome --remote-debugging-port=9222

# Windows (PowerShell)
& "C:\Program Files\Google\Chrome\Application\chrome.exe" --remote-debugging-port=9222
```

## 工具速查（28 个）

### 导航（6）
- `navigate_page` — 在已开 tab 中跳转新 URL
- `new_page` — 开新 tab（PaiCLI 记录为 agent-opened，可被 close_page 关）
- `select_page` — 切换激活 tab
- `close_page` — 关 tab（**只能关 PaiCLI 自己 new_page 出来的**）
- `list_pages` — 列出所有 tab
- `wait_for` — 等待选择器或文本出现（最常用：等 SPA 关键容器加载）

### 输入（9，shared 模式敏感页面强制单步审批）
- `click` — 点击元素（按 uid 或 selector）
- `drag` — 拖拽
- `fill` / `fill_form` — 填表单（fill_form 一次填多个字段，效率高）
- `hover` — 悬停（触发 tooltip / 菜单）
- `press_key` — 按键
- `type_text` — 文本输入
- `upload_file` — 上传文件
- `handle_dialog` — 处理 alert / confirm / prompt 弹窗

### 调试（6）
- `take_snapshot` — **首选** 结构化 DOM 文本快照，含元素 uid。LLM 直接读
- `take_screenshot` — 截图 base64。LLM 看不到图，要向用户口述
- `evaluate_script` — 跑 JS。强力工具，shared 模式属敏感
- `list_console_messages` / `get_console_message` — 控制台日志
- `lighthouse_audit` — Lighthouse 审计

### 网络（2）
- `list_network_requests` — 列出网络请求
- `get_network_request` — 单条请求详情（含响应体）

### 性能（3）
- `performance_start_trace` / `performance_stop_trace` / `performance_analyze_insight`

### 模拟（2）
- `emulate` — 模拟设备 / 网络 / 地理位置
- `resize_page` — 改窗口尺寸

### 扩展（5，shared 模式敏感页面强制单步审批）
- `install_extension` / `uninstall_extension` / `reload_extension`
- `list_extensions` / `trigger_extension_action`

### 内存（1）
- `take_memory_snapshot`

## 典型流

### 读 SPA 文章
```
navigate_page(url)
  → wait_for(selector="article, main, [role=main]")
  → take_snapshot()
  → 用 snapshot 文本抽取
```

### 填表单
```
navigate_page(url)
  → wait_for(form selector)
  → fill_form([{uid: e1, value: "..."}, {uid: e2, value: "..."}])
  → click(submit uid)
  → wait_for(success indicator)
```

### 调试空白页
```
navigate_page(url)
  → take_snapshot()  # 看 DOM
  → list_console_messages()  # 看 JS 报错
  → list_network_requests()  # 看接口失败
```

## 常见陷阱

1. **截图无意义**：`take_screenshot` 的 image content LLM 看不到，PaiCLI 现版本不直传给模型。一定要看页面布局时用，并且自己向用户描述。
2. **selector 经常失效**：SPA 站点 class 名是 hash 化的（`._3f9k_aBcD2`），优先用 snapshot 拿 uid，再 click(uid) / fill(uid)。
3. **wait_for 比 sleep 可靠**：等异步加载用 `wait_for`，不要用 `setTimeout` / `sleep`。
4. **跨 tab 操作**：`new_page` 后默认不切换激活 tab，需要 `select_page` 或在 navigate 时显式指定 pageId。
5. **shared 模式 close_page 限制**：只能关自己开的 tab。要关用户原有 tab 让用户手动。
