# PaiCLI

一个简单的 Java Agent CLI，类似 Claude Code / Qoder CLI。

## 功能

- 🤖 基于 GLM-5.1 的智能对话
- 🔄 ReAct Agent 循环（思考-行动-观察）
- 🛠️ 工具调用（文件操作、Shell命令、项目创建）
- 💬 交互式命令行界面

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

## 使用示例

```
👤 你: 创建一个Java项目叫myapp

🤔 思考中...

🔧 执行工具: create_project
   参数: {"name":"myapp","type":"java"}
   结果: 项目已创建: myapp (类型: java)

📊 Token使用: 输入=156, 输出=89

🤖 Agent: 已成功创建 Java 项目 "myapp"，包含基本的 Maven 结构。

👤 你: 在myapp/src/main/java/com/example下创建一个Hello.java，输出Hello World

...
```

## 可用工具

- `read_file` - 读取文件内容
- `write_file` - 写入文件内容
- `list_dir` - 列出目录内容
- `execute_command` - 执行 Shell 命令
- `create_project` - 创建项目结构（java/python/node）

## 命令

- `clear` - 清空对话历史
- `exit` / `quit` - 退出程序

## 运行效果

```
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

✅ API Key 已加载

💡 提示:
   - 输入你的问题或任务
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

🤖 Agent: 当前目录包含以下文件和文件夹：

**目录（文件夹）：**
| 名称 | 说明 |
|------|------|
| 📁 `demo` | demo 目录 |
| 📁 `.qoder` | qoder 配置目录 |
| 📁 `target` | 构建输出目录 |
| 📁 `src` | 源代码目录 |
| 📁 `.git` | Git 版本控制目录 |

**文件：**
| 名称 | 说明 |
|------|------|
| 📄 `pom.xml` | Maven 项目配置文件 |
| 📄 `README.md` | 项目说明文档 |
| 📄 `.gitignore` | Git 忽略规则文件 |
| 📄 `.env` | 环境变量配置文件 |
| 📄 `.env.example` | 环境变量示例文件 |

从文件结构来看，这是一个 **Java Maven 项目**。
请问您需要我做什么操作呢？😊

👤 你: exit

👋 再见!
```

## 技术栈

- Java 17
- Maven
- GLM-5.1 API
- OkHttp
- Jackson
