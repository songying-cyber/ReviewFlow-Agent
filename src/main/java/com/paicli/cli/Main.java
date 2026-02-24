package com.paicli.cli;

import com.paicli.agent.Agent;
import com.paicli.agent.PlanExecuteAgent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Scanner;

/**
 * PaiCLI v2.0 - Plan-and-Execute Agent CLI
 * 支持 ReAct 和 Plan-and-Execute 两种模式
 */
public class Main {
    private static final String VERSION = "2.0.0";
    private static final String ENV_FILE = ".env";

    public static void main(String[] args) {
        printBanner();

        // 加载 API Key
        String apiKey = loadApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("❌ 错误: 未找到 GLM_API_KEY");
            System.err.println("请在 .env 文件中添加: GLM_API_KEY=your_api_key_here");
            System.exit(1);
        }

        System.out.println("✅ API Key 已加载\n");

        Scanner scanner = new Scanner(System.in);

        // 默认使用 ReAct，Plan 模式通过 /plan 进入
        AgentMode mode = AgentMode.REACT;
        Object agent = createAgent(apiKey, mode, false);

        System.out.println("💡 提示:");
        System.out.println("   - 输入你的问题或任务");
        System.out.println("   - 输入 'mode' 切换执行模式");
        System.out.println("   - 输入 '/plan' 进入 Plan-and-Execute 模式");
        System.out.println("   - 输入 '/plan 任务内容' 直接用计划模式执行任务");
        System.out.println("   - 默认模式是 ReAct");
        System.out.println("   - 输入 'clear' 清空对话历史");
        System.out.println("   - 输入 'exit' 或 'quit' 退出\n");

        while (true) {
            System.out.print("👤 你: ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            CliCommandParser.ParsedCommand command = CliCommandParser.parse(input);
            boolean revertToReactAfterRun = false;
            switch (command.type()) {
                case EXIT -> {
                    System.out.println("\n👋 再见!");
                    scanner.close();
                    return;
                }
                case SELECT_MODE -> {
                    mode = selectMode(scanner);
                    agent = createAgent(apiKey, mode, true);
                    continue;
                }
                case CLEAR -> {
                    if (agent instanceof Agent) {
                        ((Agent) agent).clearHistory();
                    }
                    System.out.println("🗑️ 对话历史已清空\n");
                    continue;
                }
                case SWITCH_PLAN -> {
                    mode = AgentMode.PLAN_EXECUTE;
                    agent = createAgent(apiKey, mode, true);
                    if (command.payload() == null || command.payload().isEmpty()) {
                        continue;
                    }
                    input = command.payload();
                    revertToReactAfterRun = true;
                }
                case NONE -> {
                }
            }

            // 运行 Agent
            System.out.println();
            String response;
            if (agent instanceof Agent) {
                response = ((Agent) agent).run(input);
            } else {
                response = ((PlanExecuteAgent) agent).run(input);
            }
            System.out.println("🤖 Agent: " + response);
            System.out.println();

            if (revertToReactAfterRun && mode == AgentMode.PLAN_EXECUTE) {
                mode = AgentMode.REACT;
                agent = createAgent(apiKey, mode, true);
            }
        }

    }

    /**
     * 选择执行模式
     */
    private static AgentMode selectMode(Scanner scanner) {
        System.out.println("请选择执行模式:");
        System.out.println("  1. ReAct - 边思考边执行（适合简单任务）");
        System.out.println("  2. Plan-and-Execute - 先规划后执行（适合复杂任务）");
        System.out.print("> ");

        String choice = scanner.nextLine().trim();
        if (choice.equals("2")) {
            return AgentMode.PLAN_EXECUTE;
        }
        return AgentMode.REACT;
    }

    private static Object createAgent(String apiKey, AgentMode mode, boolean switched) {
        if (mode == AgentMode.REACT) {
            Agent agent = new Agent(apiKey);
            System.out.println(switched ? "🔄 已切换到 ReAct 模式\n" : "🔄 使用 ReAct 模式\n");
            return agent;
        }

        PlanExecuteAgent agent = new PlanExecuteAgent(apiKey);
        System.out.println(switched ? "📋 已切换到 Plan-and-Execute 模式\n" : "📋 使用 Plan-and-Execute 模式\n");
        return agent;
    }

    private enum AgentMode {
        REACT,
        PLAN_EXECUTE
    }

    /**
     * 从 .env 文件加载 API Key
     */
    private static String loadApiKey() {
        File envFile = new File(ENV_FILE);

        // 先尝试从当前目录读取
        if (envFile.exists()) {
            return readApiKeyFromFile(envFile);
        }

        // 再尝试从用户主目录读取
        envFile = new File(System.getProperty("user.home"), ENV_FILE);
        if (envFile.exists()) {
            return readApiKeyFromFile(envFile);
        }

        // 最后尝试从环境变量读取
        String envKey = System.getenv("GLM_API_KEY");
        if (envKey != null && !envKey.isEmpty()) {
            return envKey;
        }

        return null;
    }

    private static String readApiKeyFromFile(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("GLM_API_KEY=")) {
                    return line.substring("GLM_API_KEY=".length()).trim();
                }
            }
        } catch (IOException e) {
            System.err.println("读取 .env 文件失败: " + e.getMessage());
        }
        return null;
    }

    private static void printBanner() {
        System.out.println("========================================");
        System.out.println("           PaiCLI v" + VERSION);
        System.out.println("      Plan-and-Execute Agent CLI");
        System.out.println("========================================");
        System.out.println();
    }
}
