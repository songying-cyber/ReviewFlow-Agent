# PaiCLI 迭代路线图（13期教程）

从零开始，逐步构建生产级 Java Agent CLI

---

## 第1期：基础ReAct + Tool Call ✅

**已完成**

- ReAct循环（思考-行动-观察）
- GLM-5.1 API集成
- 5个基础工具（文件、Shell、项目创建）
- 交互式CLI
- 约400行代码

**核心知识点**：ReAct模式、Function Calling、Agent基础架构

---

## 第2期：Plan-and-Execute + 多轮规划 ✅

**目标**：让Agent能处理复杂多步任务

**功能迭代**：
- Plan-and-Execute模式（先规划后执行）
- 任务分解（Task Decomposition）
- 子任务依赖管理
- 执行计划可视化
- 计划失败时的重规划

**核心知识点**：
- Plan-and-Solve模式
- 任务DAG管理
- 规划-执行分离架构

**教程标题候选**：《Agent只会一步一步执行？教它先规划后行动，复杂任务也能搞定》

---

## 第3期：Memory系统 + 上下文工程 ✅

**目标**：让Agent有记忆，能处理长对话

**功能迭代**：
- 短期记忆（对话历史管理）
- 长期记忆（关键信息持久化）
- 上下文压缩（摘要生成）
- Token预算管理
- 记忆检索（相似度匹配）

**核心知识点**：
- Context Window管理
- 记忆分层架构
- 摘要算法（Map-Reduce）

**教程标题候选**：《Agent记性太差？给它装上记忆系统，长对话也不忘事》

---

## 第4期：RAG检索 + 代码库理解 ✅

**已完成**

**目标**：让Agent能理解整个代码库

**功能迭代**：
- 代码向量化（Embedding），支持本地 Ollama 和远程 API
- 向量数据库（SQLite + 内存余弦检索）
- 代码分块与索引（文件/类/方法粒度）
- 语义检索（自然语言搜代码）
- 代码关系图谱（类、方法依赖）

**核心知识点**：
- RAG架构
- Code Embedding
- 向量检索
- AST 分析

**教程标题候选**：《Agent看不懂你的代码库？接入RAG，让它秒懂项目结构》

---

## 第5期：Multi-Agent协作 + 角色分工 ✅

**已完成**

**目标**：多个Agent协作完成复杂任务

**功能迭代**：
- Agent角色定义（规划者、执行者、检查者）
- Agent间通信机制
- 任务分配与协调
- 冲突解决策略
- 主从Agent架构

**核心知识点**：
- Multi-Agent系统
- 角色扮演（Role Playing）
- 分布式任务协调

**教程标题候选**：《一个Agent忙不过来？搞个团队，规划、执行、检查分工干》

---

## 第6期：Human-in-the-Loop + 审批流 ✅

**已完成**

**目标**：关键操作人工确认，安全可控

**功能迭代**：
- 危险操作静态规则识别（`write_file`、`execute_command`、`create_project`）
- 三级危险等级（高危 / 中危 / 安全）
- 审批决策：批准 / 全部放行 / 拒绝 / 跳过 / 修改参数后执行
- HITL 默认关闭，`/hitl on|off` 运行时切换
- `HitlToolRegistry` 透明拦截层，HITL 关闭时与普通 `ToolRegistry` 行为完全相同

**核心知识点**：
- HITL（人机协同）
- 中断处理
- 安全策略

**教程标题候选**：《Agent权限太大怕搞砸？加上人工审批，安全又放心》

---

## 第7期：异步执行 + 并行工具调用 ✅

**已完成**

**目标**：提升执行效率，支持长时间任务

**功能迭代**：
- 同一轮 LLM 返回多个 `tool_calls` 时并行执行
- ReAct、Plan-and-Execute、Multi-Agent Worker 复用统一批量工具执行入口
- Plan-and-Execute 按 DAG 依赖批次并行执行独立任务
- Multi-Agent 按依赖批次并行调度多个 Worker
- 工具批次统一超时，超时工具会被取消并返回可回灌结果

**核心知识点**：
- 异步编程模型
- 并发控制
- 任务调度

**教程标题候选**：《Agent执行太慢？上异步+并行，编译测试一起跑》

---

## 第8期：多模型适配 + 运行时切换（GLM / DeepSeek）✅

**已完成**

**目标**：支持多模型运行时切换，GLM-5.1 和 DeepSeek V4 双模型

**功能迭代**：
- `LlmClient` 接口抽象：将 GLMClient 的内部类型（Message、ToolCall、Tool 等）提升为接口级公共类型
- `AbstractOpenAiCompatibleClient` 基类：共享 SSE 流式解析、请求构建、工具调用增量合并逻辑
- `GLMClient` / `DeepSeekClient` 瘦子类：各约 20 行，仅提供 API URL、模型名、API Key
- 运行时模型切换：`/model glm` `/model deepseek` 命令实时切换当前对话模型
- 配置持久化：`~/.paicli/config.json` 存储默认模型，支持 `.env` 回退读取 API Key
- `LlmClientFactory` 工厂：根据 provider 名称和配置创建对应客户端

**核心知识点**：
- 策略模式 + Provider 抽象
- OpenAI 兼容协议
- 模板方法模式（AbstractOpenAiCompatibleClient）
- 运行时配置管理

**教程标题候选**：《只能用一个模型？策略模式 + 模板方法，GLM 和 DeepSeek 随时切换》

---

## 第9期：联网能力 + Web工具

**目标**：让 Agent 能访问互联网，获取实时信息，并能操控浏览器

**功能迭代**：
- `web_search` 工具：接入搜索引擎 API（Google/Bing/SerpAPI），返回摘要结果
- `web_fetch` 工具：抓取指定 URL 页面内容，自动提取正文（去除 HTML 标签/广告/导航）
- Chrome DevTools MCP：通过 MCP 协议接入 Chrome DevTools Server，Agent 可打开浏览器页面、截图、读取 DOM 内容
- CDP 会话复用：通过 Chrome DevTools Protocol 连接用户已打开的 Chrome 实例（`--remote-debugging-port`），复用登录态访问需要认证的页面（如 GitHub、内部系统）
- 搜索结果摘要：LLM 对检索结果二次提炼，只保留与用户问题相关的信息
- 网络访问安全：URL 白名单/黑名单、请求频率限制、响应体大小限制
- Agent 提示词升级：让 Agent 知道何时该用联网工具（如"最新版本是什么"、"官方文档怎么说"），以及何时该用浏览器（如需要登录态、JS 渲染页面）

**核心知识点**：
- 搜索引擎 API 集成
- HTML 正文提取（Jsoup / Readability 算法）
- Chrome DevTools Protocol（CDP）
- MCP 协议客户端实现
- 浏览器会话复用与登录态管理
- Agent 工具选择策略

**教程标题候选**：《Agent 与世隔绝？搜索、抓取、操控浏览器，联网能力一步到位》

---

## 第10期：Spring AI + 框架升级

**目标**：引入 Spring AI 框架，用框架替代手写实现

**功能迭代**：
- Spring Boot 化：项目整体迁移到 Spring Boot，CLI 入口改为 CommandLineRunner
- Spring AI 接入：引入 spring-ai-openai-spring-boot-starter，用 ChatModel 适配
- SpringAiLlmClient：作为第三个 LlmClient 实现，内部使用 ChatModel / StreamingChatModel
- 依赖注入：用 Spring DI 替代手动 new，统一管理 Bean 生命周期

**核心知识点**：
- Spring AI 框架（ChatModel / StreamingChatModel / ToolCallback）
- Spring Boot 自动配置
- 依赖注入与 Bean 管理

**教程标题候选**：《手写够了？上 Spring AI，框架帮你管模型、管工具、管配置》

---

## 第11期：沙箱安全 + 权限控制

**目标**：生产环境安全运行

**功能迭代**：
- Docker沙箱执行
- 文件系统隔离
- 网络访问控制
- 资源限额（CPU/内存/时间）
- 操作审计日志

**核心知识点**：
- 容器化隔离
- 安全沙箱
- 权限模型

**教程标题候选**：《Agent乱执行命令很危险？关进Docker沙箱，想搞破坏也没门》

---

## 第12期：MCP协议 + 生态接入

**目标**：接入丰富的外部工具生态

**功能迭代**：
- MCP（Model Context Protocol）支持
- MCP Server发现与连接
- 工具动态加载
- 第三方服务集成（GitHub、Slack、数据库）
- 插件市场机制

**核心知识点**：
- MCP协议
- 插件架构
- 生态集成

**教程标题候选**：《工具不够用了？接入MCP生态，GitHub、Slack、数据库随便调》

---

## 第13期：TUI界面 + 产品化

**目标**：从CLI到完整产品体验

**功能迭代**：
- 终端TUI界面（Lanterna/JLine）
- 文件树浏览
- 代码高亮显示
- 对话历史可视化
- 配置文件管理
- 安装包分发

**核心知识点**：
- TUI开发
- 终端渲染
- 产品工程化

**教程标题候选**：《CLI太简陋？做个漂亮的TUI界面，体验不输Claude Code》

---

## 技术栈演进图

```
第1期 ──► 第2期 ──► 第3期 ──► 第4期 ──► 第5期 ──► 第6期 ──► 第7期
基础      规划      记忆      RAG      多Agent   人机      异步
ReAct    执行     上下文    检索      协作      协同      并行

第8期 ──► 第9期 ──► 第10期 ──► 第11期 ──► 第12期 ──► 第13期
多模型    联网     Spring AI   安全      MCP       产品
切换      能力     框架升级     沙箱      生态       化
```

## 每期代码增量预估

| 期数 | 新增代码 | 累计代码 | 核心难度 |
|-----|---------|---------|---------|
| 1 | 400 | 400 | ⭐⭐ |
| 2 | 300 | 700 | ⭐⭐⭐ |
| 3 | 400 | 1100 | ⭐⭐⭐ |
| 4 | 600 | 1700 | ⭐⭐⭐⭐ |
| 5 | 600 | 2200 | ⭐⭐⭐⭐ |
| 6 | 400 | 2600 | ⭐⭐⭐ |
| 7 | 500 | 3100 | ⭐⭐⭐⭐ |
| 8 | 400 | 3500 | ⭐⭐⭐ |
| 9 | 500 | 4000 | ⭐⭐⭐ |
| 10 | 600 | 4600 | ⭐⭐⭐⭐ |
| 11 | 600 | 5200 | ⭐⭐⭐⭐ |
| 12 | 500 | 5700 | ⭐⭐⭐ |
| 13 | 800 | 6500 | ⭐⭐⭐ |

## 学习路径建议

**初学者**：按顺序1→2→3→6→13，掌握核心即可
**进阶者**：1→2→3→4→7→8→9→10，深入技术细节
**全面掌握**：全部13期，完整技术栈

## 参考项目

- **Claude Code**：人机协同、TUI界面
- **OpenClaw**：多Agent、MCP集成
- **PaiAgent**：工作流编排、可视化
- **LangGraph**：状态管理、循环控制
- **Spring AI**：多模型适配、工具回调

---

*已完成第8期 多模型适配 + 运行时切换，下一步进入第9期 联网能力 + Web工具。*
