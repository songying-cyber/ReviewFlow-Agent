# PaiCLI

一个教学导向的 Java Agent CLI，已经从第一期的 `ReAct` 单代理循环，演进到第四期的 `RAG 检索 + 代码库理解`。

## 演进历程

### 第一期：ReAct Agent CLI

- 单轮对话驱动的 `ReAct` 循环
- 支持工具调用：读文件、写文件、列目录、执行命令、创建项目
- 更适合简单任务或单步操作

### 第二期：Plan-and-Execute + DAG

- 在保留 `ReAct` 模式的基础上新增复杂任务规划能力
- 支持先拆解任务，再按照依赖顺序执行
- 新增 `/plan` 入口，以一次性计划执行方式增强默认的 `ReAct`
- 计划生成后，会先与用户确认再执行
- 更适合多步骤、带依赖关系的复杂任务

### 第三期：Memory + 上下文工程

- 短期记忆管理当前对话与工具结果
- 长期记忆持久化关键事实，跨会话复用
- 对话接近预算时自动做摘要压缩
- 新增 `/memory` 查看状态、`/save` 手动保存事实

### 第四期：RAG 检索 + 代码库理解

- 代码向量化（Embedding），支持本地 Ollama 和远程 API
- SQLite 持久化 + 余弦相似度语义检索
- 代码分块（文件/类/方法粒度）与 AST 解析
- 代码关系图谱（extends/implements/imports/calls/contains）
- 新增 `/index`、`/search`、`/graph` CLI 命令
- Agent 自动调用 `search_code` 工具理解代码库

## 启动界面

### 第三期当前启动界面

当前启动输出以命令行实际产物为准：

```text
╔══════════════════════════════════════════════════════════╗
║                                                          ║
║   ██████╗  █████╗ ██╗ ██████╗██╗     ██╗                ║
║   ██╔══██╗██╔══██╗██║██╔════╝██║     ██║                ║
║   ██████╔╝███████║██║██║     ██║     ██║                ║
║   ██╔═══╝ ██╔══██║██║██║     ██║     ██║                ║
║   ██║     ██║  ██║██║╚██████╗███████╗██║                ║
║   ╚═╝     ╚═╝  ╚═╝╚═╝ ╚═════╝╚══════╝╚═╝                ║
║                                                          ║
║      RAG-Enhanced Agent CLI v4.0.0                  ║
║                                                          ║
╚══════════════════════════════════════════════════════════╝

🔄 使用 ReAct 模式
```

## 功能

### 第一期

- 🤖 基于 GLM-5.1 的智能对话
- 🔄 ReAct Agent 循环（思考-行动-观察）
- 🛠️ 工具调用（文件操作、Shell命令、项目创建）
- 💬 交互式命令行界面

### 第二期

- 📋 Plan-and-Execute + DAG 任务拆解与顺序执行
- ⌨️ `/plan` 一次性进入计划执行
- 🧭 更清晰的复杂任务执行顺序与依赖展示

### 第三期

- 🧠 短期记忆、长期记忆与相关记忆检索
- 📦 长对话摘要压缩与 Token 预算管理
- 💾 `/memory` 与 `/save` 记忆管理入口

### 第四期

- 🔍 代码库语义检索（自然语言搜代码）
- 🕸️ 代码关系图谱（类继承、接口实现、方法调用）
- 📡 本地 Ollama Embedding + 远程 API 可配置
- 🗃️ SQLite 向量存储与持久化

## 快速开始

### 1. 配置 API Key

复制 `.env.example` 为 `.env`，并填入你的 GLM API Key：

```bash
cp .env.example .env
# 编辑 .env 文件，填入你的 API Key
```

或者在环境变量中设置：

```bash
export GLM_API_KEY=your_api_key_here
```

长期记忆默认保存在用户目录下的 `~/.paicli/memory/long_term_memory.json`。
代码索引默认保存在 `~/.paicli/rag/codebase.db`。

如果你想为某次运行指定单独目录，可以额外传入：

```bash
# 指定记忆目录
java -Dpaicli.memory.dir=/tmp/paicli-memory -jar target/paicli-1.0-SNAPSHOT.jar

# 指定 RAG 索引目录
java -Dpaicli.rag.dir=/tmp/paicli-rag -jar target/paicli-1.0-SNAPSHOT.jar
```

### 2. 编译运行

```bash
# 编译
mvn clean package

# 运行（需要本地 Ollama 已启动且拉取了 nomic-embed-text）
java -jar target/paicli-1.0-SNAPSHOT.jar
```

或者直接运行：

```bash
mvn clean compile exec:java -Dexec.mainClass="com.paicli.cli.Main"
```

### 3. 如何进入 Plan 模式

当前默认模式是 `ReAct`。进入 `Plan-and-Execute` 的方式只有 `/plan`：

1. 输入 `/plan`
2. 下一条任务会用计划模式执行
3. 执行完成后自动回到默认 `ReAct`

如果想一条命令切模式并执行任务，可以直接输入：

```text
/plan 创建一个 demo 项目，然后读取 pom.xml，最后验证项目结构
```

这条命令执行完成后，会自动回到默认的 `ReAct` 模式。

计划生成后，CLI 会先停下来等待确认：

- 按 `Enter`：按当前计划执行
- 按 `Ctrl+O`：展开完整计划
- 按 `ESC`：折叠完整计划或取消本次计划
- 按 `I`：输入补充要求并重新规划

## 使用示例

### 第一期：ReAct 示例

```text
👤 你: 创建一个Java项目叫myapp

🤔 思考中...

🔧 执行工具: create_project
   参数: {"name":"myapp","type":"java"}
   结果: 项目已创建: myapp (类型: java)

📊 Token使用: 输入=156, 输出=89

🤖 Agent: 已成功创建 Java 项目 "myapp"，包含基本的 Maven 结构。
```

### 第二期：Plan-and-Execute 示例

```text
💡 提示:
   - 输入你的问题或任务
   - 输入 '/plan' 后，下一条任务使用 Plan-and-Execute 模式
   - 输入 '/plan 任务内容' 直接用计划模式执行这条任务
   - 计划生成后可直接执行、补充要求重规划，或取消
   - 输入 '/index [路径]' 为代码库建立向量索引
   - 输入 '/search <查询>' 语义检索代码
   - 输入 '/graph <类名>' 查看代码关系图谱
   - 输入 '/memory' 查看记忆状态
   - 输入 '/save 事实内容' 手动保存关键事实
   - 输入 '/clear' 清空对话历史
   - 输入 '/exit' 或 '/quit' 退出

👤 你: /plan 创建一个名为 demoapp 的 java 项目，然后读取 pom.xml，最后验证项目结构

📋 使用 Plan-and-Execute 模式

📋 正在规划任务: 创建一个名为 demoapp 的 java 项目，然后读取 pom.xml，最后验证项目结构

╔══════════════════════════════════════════════════════════╗
║  执行计划: 创建一个名为 demoapp 的 java 项目，然后读取... ║
╠══════════════════════════════════════════════════════════╣
║  1. ⏳ task_1               [COMMAND   ] 依赖: 无        ║
║     创建 demoapp 项目结构                              ║
║  2. ⏳ task_2               [FILE_READ ] 依赖: task_1    ║
║     读取 demoapp/pom.xml 内容                          ║
║  3. ⏳ task_3               [VERIFICATION] 依赖: task_2  ║
║     验证项目结构与 Maven 配置                          ║
╚══════════════════════════════════════════════════════════╝

📝 计划已生成。
   - 回车：按当前计划执行
   - ESC：取消本次计划
   - I：输入补充要求后重新规划

I
补充> 请在执行前先检查 README

📝 已收到补充要求，正在重新规划...

🚀 开始执行计划...
```

## 可用工具

- `read_file` - 读取文件内容
- `write_file` - 写入文件内容
- `list_dir` - 列出目录内容
- `execute_command` - 执行 Shell 命令
- `create_project` - 创建项目结构（java/python/node）
- `search_code` - 语义检索代码库（自然语言查询）

## 命令

- `/plan` - 下一条任务使用 Plan-and-Execute 模式
- `/plan <任务>` - 直接用 Plan-and-Execute 模式执行这条任务
- `/memory` / `/mem` - 查看记忆系统状态
- `/save <事实>` - 手动保存关键事实到长期记忆
- `/index [路径]` - 索引代码库（默认当前目录）
- `/search <查询>` - 语义检索代码
- `/graph <类名>` - 查看代码关系图谱
- `/clear` - 清空对话历史
- `/exit` / `/quit` - 退出程序

## 运行效果

### 第一期：旧版启动效果

```text
╔══════════════════════════════════════════════════════════╗
║                                                          ║
║   ██████╗  █████╗ ██╗      ██████╗██╗     ██╗            ║
║   ██╔══██╗██╔══██╗██║     ██╔════╝██║     ██║            ║
║   ██████╔╝███████║██║     ██║     ██║     ██║            ║
║   ██╔═══╝ ██╔══██║██║     ██║     ██║     ██║            ║
║   ██║     ██║  ██║███████╗╚██████╗███████╗██║            ║
║   ╚═╝     ╚═╝  ╚═╝╚══════╝ ╚═════╝╚══════╝╚═╝            ║
║                                                          ║
║              简单的 Java Agent CLI v1.0.0                ║
║                                                          ║
╚══════════════════════════════════════════════════════════╝
```

### 第三期：当前运行效果

```text
╔══════════════════════════════════════════════════════════╗
║                                                          ║
║   ██████╗  █████╗ ██╗ ██████╗██╗     ██╗                ║
║   ██╔══██╗██╔══██╗██║██╔════╝██║     ██║                ║
║   ██████╔╝███████║██║██║     ██║     ██║                ║
║   ██╔═══╝ ██╔══██║██║██║     ██║     ██║                ║
║   ██║     ██║  ██║██║╚██████╗███████╗██║                ║
║   ╚═╝     ╚═╝  ╚═╝╚═╝ ╚═════╝╚══════╝╚═╝                ║
║                                                          ║
║      Memory-Enhanced Agent CLI v3.0.0                 ║
║                                                          ║
╚══════════════════════════════════════════════════════════╝

✅ API Key 已加载

🔄 使用 ReAct 模式

💡 提示:
   - 输入你的问题或任务
   - 输入 '/plan' 后，下一条任务使用 Plan-and-Execute 模式
   - 输入 '/plan 任务内容' 直接用计划模式执行这条任务
   - 计划生成后可直接执行、补充要求重规划，或取消
   - 输入 '/index [路径]' 为代码库建立向量索引
   - 输入 '/search <查询>' 语义检索代码
   - 输入 '/graph <类名>' 查看代码关系图谱
   - 默认模式是 ReAct
   - 输入 '/clear' 清空对话历史
   - 输入 '/memory' 查看记忆状态
   - 输入 '/save 事实内容' 手动保存关键事实
   - 输入 '/exit' 或 '/quit' 退出

👤 你: 你好，请列出当前目录的文件

🤔 思考中...

🔧 执行工具: list_dir
   参数: {"path":"."}
   结果: 目录内容:
[D] demo
[D] .qoder
[D] target
[F] pom.xml
[F] README.md
[F] .gitignore
[F] .env
[F] .env.example
[D] .git
[D] src


📊 Token使用: 输入=596, 输出=205

🤖 Agent: 当前目录包含 `src`、`target`、`pom.xml`、`README.md` 等文件，
这是一个标准的 Java Maven 项目。

👤 你: /exit

👋 再见!
```

## 技术栈

- Java 17
- Maven
- GLM-5.1 API
- OkHttp
- Jackson
- JLine3（终端交互）
- SQLite（向量与图谱持久化）
- JavaParser（AST 分析）
- Ollama（本地 Embedding）

## 项目结构

```
src/main/java/com/paicli
├── agent/
│   ├── Agent.java              # ReAct Agent
│   └── PlanExecuteAgent.java   # Plan-and-Execute Agent
├── cli/
│   ├── Main.java               # CLI 入口
│   ├── CliCommandParser.java   # 命令解析
│   └── PlanReviewInputParser.java  # 计划审核输入
├── llm/
│   └── GLMClient.java          # GLM-5.1 API 客户端
├── memory/
│   ├── MemoryEntry.java        # 记忆条目
│   ├── ConversationMemory.java # 短期记忆
│   ├── LongTermMemory.java     # 长期记忆
│   ├── ContextCompressor.java  # 上下文压缩
│   ├── TokenBudget.java        # Token 预算管理
│   ├── MemoryRetriever.java    # 记忆检索
│   └── MemoryManager.java      # 记忆门面类
├── plan/
│   ├── Task.java               # 任务定义
│   ├── ExecutionPlan.java      # 执行计划
│   └── Planner.java            # 规划器
├── rag/
│   ├── EmbeddingClient.java    # Embedding API 客户端
│   ├── VectorStore.java        # SQLite 向量存储
│   ├── CodeChunk.java          # 代码块模型
│   ├── CodeChunker.java        # 代码分块器
│   ├── CodeAnalyzer.java       # AST 关系分析
│   ├── CodeRelation.java       # 代码关系模型
│   ├── CodeIndex.java          # 索引管理器
│   └── CodeRetriever.java      # 检索入口
└── tool/
    └── ToolRegistry.java       # 工具注册表
```
