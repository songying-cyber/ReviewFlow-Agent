# PaiCLI

一个简单的 Java Agent CLI，从第一期的 `ReAct` 单代理循环，演进到第二期的 `Plan-and-Execute + DAG` 执行模式。

## 演进历程

### 第一期：ReAct Agent CLI

- 单轮对话驱动的 `ReAct` 循环
- 支持工具调用：读文件、写文件、列目录、执行命令、创建项目
- 更适合简单任务或单步操作

### 第二期：Plan-and-Execute + DAG

- 在保留 `ReAct` 模式的基础上新增复杂任务规划能力
- 支持先拆解任务，再按照依赖顺序执行
- 新增 `mode` 和 `/plan` 切换，可在 `ReAct` 与 `Plan-and-Execute` 之间切换
- 更适合多步骤、带依赖关系的复杂任务

## 启动界面

### 第二期当前启动界面

当前启动输出以命令行实际产物为准：

```text
========================================
           PaiCLI v2.0.0
      Plan-and-Execute Agent CLI
========================================

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
- 🔀 `mode` 模式切换
- 🧭 更清晰的复杂任务执行顺序与依赖展示

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

### 2. 编译运行

```bash
# 编译
mvn clean package

# 运行
java -jar target/paicli-1.0-SNAPSHOT.jar
```

或者直接运行：

```bash
mvn clean compile exec:java -Dexec.mainClass="com.paicli.cli.Main"
```

### 3. 如何进入 Plan 模式

当前默认模式是 `ReAct`。进入 `Plan-and-Execute` 模式有 2 种方式：

1. 进入交互界面后输入 `mode`，再选择 `2`
2. 直接输入 `/plan`

如果想一条命令切模式并执行任务，可以直接输入：

```text
/plan 创建一个 demo 项目，然后读取 pom.xml，最后验证项目结构
```

这条命令执行完成后，会自动回到默认的 `ReAct` 模式。

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
   - 输入 'mode' 切换执行模式
   - 输入 '/plan' 进入 Plan-and-Execute 模式
   - 输入 '/plan 任务内容' 直接用计划模式执行任务
   - 输入 '/react' 切回 ReAct 模式
   - 输入 'clear' 清空对话历史
   - 输入 'exit' 或 'quit' 退出

👤 你: /plan 创建一个名为 demoapp 的 java 项目，然后读取 pom.xml，最后验证项目结构

📋 已切换到 Plan-and-Execute 模式

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

🚀 开始执行计划...

🔄 已切换到 ReAct 模式
```

## 可用工具

- `read_file` - 读取文件内容
- `write_file` - 写入文件内容
- `list_dir` - 列出目录内容
- `execute_command` - 执行 Shell 命令
- `create_project` - 创建项目结构（java/python/node）

## 命令

- `mode` - 切换 ReAct / Plan-and-Execute 模式
- `/plan` - 切换到 Plan-and-Execute 模式
- `/plan <任务>` - 切换到 Plan-and-Execute 模式并立即执行任务
- `/react` - 切换回 ReAct 模式
- `clear` - 清空对话历史
- `exit` / `quit` - 退出程序

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

### 第二期：当前运行效果

```text
========================================
           PaiCLI v2.0.0
      Plan-and-Execute Agent CLI
========================================

✅ API Key 已加载

🔄 使用 ReAct 模式

💡 提示:
   - 输入你的问题或任务
   - 输入 'mode' 切换执行模式
   - 输入 '/plan' 进入 Plan-and-Execute 模式
   - 输入 '/plan 任务内容' 直接用计划模式执行任务
   - 输入 '/react' 切回 ReAct 模式
   - 默认模式是 ReAct
   - 输入 'clear' 清空对话历史
   - 输入 'exit' 或 'quit' 退出

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

👤 你: exit

👋 再见!
```

## 技术栈

- Java 17
- Maven
- GLM-5.1 API
- OkHttp
- Jackson
