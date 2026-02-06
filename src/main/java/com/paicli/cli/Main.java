package com.paicli.cli;

import com.paicli.agent.Agent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Scanner;

/**
 * PaiCLI - 简单的 Java Agent CLI
 * 类似 Claude Code / Qoder CLI
 */
public class Main {
    private static final String VERSION = "1.0.0";
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

        // 创建 Agent
        Agent agent = new Agent(apiKey);

        // 交互式循环
        Scanner scanner = new Scanner(System.in);

        System.out.println("💡 提示:");
        System.out.println("   - 输入你的问题或任务");
        System.out.println("   - 输入 'clear' 清空对话历史");
        System.out.println("   - 输入 'exit' 或 'quit' 退出\n");

        while (true) {
            System.out.print("👤 你: ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            // 处理特殊命令
            if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                System.out.println("\n👋 再见!");
                break;
            }

            if (input.equalsIgnoreCase("clear")) {
                agent.clearHistory();
                System.out.println("🗑️ 对话历史已清空\n");
                continue;
            }

            // 运行 Agent
            System.out.println();
            String response = agent.run(input);
            System.out.println("🤖 Agent: " + response);
            System.out.println();
        }

        scanner.close();
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
        System.out.println("""
                ╔══════════════════════════════════════════════════════════╗
                ║                                                          ║
                ║   ██████╗  █████╗ ██╗      ██████╗██╗     ██╗            ║
                ║   ██╔══██╗██╔══██╗██║     ██╔════╝██║     ██║            ║
                ║   ██████╔╝███████║██║     ██║     ██║     ██║            ║
                ║   ██╔═══╝ ██╔══██║██║     ██║     ██║     ██║            ║
                ║   ██║     ██║  ██║███████╗╚██████╗███████╗██║            ║
                ║   ╚═╝     ╚═╝  ╚═╝╚══════╝ ╚═════╝╚══════╝╚═╝            ║
                ║                                                          ║
                ║              简单的 Java Agent CLI v""" + VERSION + """
                ║                                                          ║
                ╚══════════════════════════════════════════════════════════╝
                """);
    }
}
