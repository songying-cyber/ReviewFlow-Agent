package com.paicli.wechat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

public final class WechatCommandMain {
    private WechatCommandMain() {
    }

    public static boolean isWechatCommand(String[] args) {
        return args != null && args.length > 0 && "wechat".equalsIgnoreCase(args[0]);
    }

    public static int run(String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "help";
        try {
            return switch (sub) {
                case "setup" -> setup();
                case "start" -> start();
                case "status" -> status();
                case "daemon" -> daemon(args);
                case "help", "-h", "--help" -> {
                    printHelp();
                    yield 0;
                }
                default -> {
                    System.err.println("未知 wechat 子命令: " + sub);
                    printHelp();
                    yield 2;
                }
            };
        } catch (Exception e) {
            System.err.println("wechat 命令失败: " + e.getMessage());
            return 1;
        }
    }

    private static int setup() throws Exception {
        IlinkClient client = new IlinkClient();
        WechatAccountStore store = WechatAccountStore.createDefault();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.print("请输入微信通道工作区 [" + Path.of(".").toAbsolutePath().normalize() + "]: ");
        String workspace = in.readLine();
        if (workspace == null || workspace.isBlank()) {
            workspace = Path.of(".").toAbsolutePath().normalize().toString();
        }

        WechatQrLogin qr = client.startQrLogin("3");
        System.out.println("请用目标微信扫描二维码：");
        TerminalQrRenderer.print(System.out, qr.qrcodeUrl());
        System.out.println("扫码失败时可打开链接：" + qr.qrcodeUrl());
        System.out.println("等待扫码确认...");

        WechatLoginResult login = waitLogin(client, qr.qrcodeId(), Duration.ofMinutes(5));
        if (!login.connected()) {
            throw new IllegalStateException("扫码绑定未完成: " + login.message());
        }
        WechatAccount account = store.createAccount(
                login.token(),
                login.accountId(),
                login.baseUrl(),
                login.userId(),
                workspace);
        store.save(account);
        System.out.println("微信通道绑定完成");
        System.out.println("账号: " + login.accountId());
        System.out.println("工作区: " + workspace);
        System.out.println("setup 已退出；启动通道后才会保持在线。");
        printStartHint();
        return 0;
    }

    private static int start() {
        WechatAccountStore store = WechatAccountStore.createDefault();
        WechatAccount account = store.loadLatest()
                .orElseThrow(() -> new IllegalStateException("未找到微信账号，请先执行 paicli wechat setup"));
        System.out.println("PaiCLI 微信通道启动中，账号: " + account.accountId());
        new WechatMessageLoop(new IlinkClient(), store, account).run();
        return 0;
    }

    private static int status() {
        WechatAccountStore store = WechatAccountStore.createDefault();
        Optional<WechatAccount> account = store.loadLatest();
        if (account.isEmpty()) {
            System.out.println("微信通道未绑定。执行 `paicli wechat setup` 开始绑定。");
            return 0;
        }
        System.out.println("微信通道已绑定");
        System.out.println("账号: " + account.get().accountId());
        System.out.println("绑定用户: " + mask(account.get().boundUserId()));
        System.out.println("工作区: " + account.get().workspace());
        Path pid = WechatPaths.pidFile();
        if (Files.exists(pid)) {
            try {
                System.out.println("daemon pid: " + Files.readString(pid).trim());
            } catch (IOException ignored) {
            }
        }
        return 0;
    }

    private static int daemon(String[] args) throws IOException {
        String action = args.length >= 3 ? args[2].toLowerCase() : "status";
        return switch (action) {
            case "status" -> daemonStatus();
            case "start" -> daemonStart();
            case "stop" -> daemonStop();
            case "restart" -> {
                daemonStop();
                yield daemonStart();
            }
            case "logs" -> daemonLogs();
            default -> {
                System.err.println("未知 wechat daemon 子命令: " + action);
                yield 2;
            }
        };
    }

    private static int daemonStart() throws IOException {
        Files.createDirectories(WechatPaths.logsDir());
        Path stdout = WechatPaths.logsDir().resolve("stdout.log");
        Path stderr = WechatPaths.logsDir().resolve("stderr.log");
        ProcessBuilder pb = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "com.paicli.cli.Main",
                "wechat",
                "start");
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(stdout.toFile()));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(stderr.toFile()));
        Process process = pb.start();
        Files.createDirectories(WechatPaths.root());
        Files.writeString(WechatPaths.pidFile(), Long.toString(process.pid()));
        System.out.println("微信 daemon 已启动，PID: " + process.pid());
        System.out.println("日志: " + stdout);
        return 0;
    }

    private static int daemonStop() throws IOException {
        Path pidFile = WechatPaths.pidFile();
        if (!Files.exists(pidFile)) {
            System.out.println("微信 daemon 未运行。");
            return 0;
        }
        String raw = Files.readString(pidFile).trim();
        try {
            long pid = Long.parseLong(raw);
            ProcessHandle.of(pid).ifPresent(handle -> {
                handle.destroy();
                try {
                    handle.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    handle.destroyForcibly();
                }
            });
        } catch (NumberFormatException ignored) {
        }
        Files.deleteIfExists(pidFile);
        System.out.println("微信 daemon 已停止。");
        return 0;
    }

    private static int daemonStatus() throws IOException {
        Path pidFile = WechatPaths.pidFile();
        if (!Files.exists(pidFile)) {
            System.out.println("微信 daemon 未运行。");
            return 0;
        }
        String pid = Files.readString(pidFile).trim();
        boolean alive = false;
        try {
            alive = ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false);
        } catch (NumberFormatException ignored) {
        }
        System.out.println(alive ? "微信 daemon 运行中 (PID: " + pid + ")" : "微信 daemon pid 文件存在但进程不在运行");
        return 0;
    }

    private static int daemonLogs() throws IOException {
        Path file = WechatPaths.logsDir().resolve("stdout.log");
        if (!Files.exists(file)) {
            System.out.println("暂无微信 daemon 日志。");
            return 0;
        }
        java.util.List<String> lines = Files.readAllLines(file);
        int from = Math.max(0, lines.size() - 100);
        for (int i = from; i < lines.size(); i++) {
            System.out.println(lines.get(i));
        }
        return 0;
    }

    private static WechatLoginResult waitLogin(IlinkClient client, String qrcodeId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            WechatLoginResult result = client.pollQrStatus(qrcodeId);
            if (result.connected() || result.expired()) {
                return result;
            }
            Thread.sleep(3_000);
        }
        throw new IllegalStateException("等待扫码超时");
    }

    private static void printHelp() {
        System.out.println("""
                PaiCLI 微信通道：
                  paicli wechat setup
                  paicli wechat start
                  paicli wechat status
                  paicli wechat daemon start|stop|restart|status|logs
                """.trim());
    }

    private static void printStartHint() {
        System.out.println("启动: java -jar target/paicli-1.0-SNAPSHOT.jar wechat start");
        System.out.println("如果已安装全局命令，也可以执行: paicli wechat start");
    }

    private static String mask(String value) {
        if (value == null || value.length() < 8) {
            return "***";
        }
        return value.substring(0, 4) + "***" + value.substring(value.length() - 4);
    }
}
