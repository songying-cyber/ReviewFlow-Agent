package com.paicli.runtime.task;

import java.util.List;

public final class TaskCommandFormatter {
    private TaskCommandFormatter() {}

    public static String handle(DurableTaskManager manager, String payload) {
        String normalized = payload == null || payload.isBlank() ? "list" : payload.trim();
        if (normalized.equalsIgnoreCase("list")) {
            return formatList(manager.list(20));
        }
        if (normalized.regionMatches(true, 0, "list ", 0, 5)) {
            return formatList(manager.list(parseLimit(normalized.substring(5).trim(), 20)));
        }
        if (normalized.regionMatches(true, 0, "add ", 0, 4)) {
            DurableTask task = manager.enqueue(normalized.substring(4).trim());
            return "✅ 后台任务已提交: " + task.id() + "\n   /task log " + task.id();
        }
        if (normalized.regionMatches(true, 0, "cancel ", 0, 7)) {
            String id = normalized.substring(7).trim();
            return manager.cancel(id)
                    ? "⏹️ 已请求取消后台任务: " + id
                    : "❌ 未找到可取消的后台任务: " + id;
        }
        if (normalized.regionMatches(true, 0, "pause ", 0, 6)) {
            String id = normalized.substring(6).trim();
            return manager.pause(id)
                    ? "⏸️ 已请求暂停后台任务: " + id
                    : "❌ 未找到可暂停的后台任务: " + id;
        }
        if (normalized.regionMatches(true, 0, "resume ", 0, 7)) {
            String id = normalized.substring(7).trim();
            return manager.resume(id)
                    ? "▶️ 已恢复后台任务: " + id
                    : "❌ 未找到可恢复的后台任务: " + id;
        }
        if (normalized.regionMatches(true, 0, "retry ", 0, 6)) {
            String id = normalized.substring(6).trim();
            return manager.retry(id)
                    ? "🔁 已重新排队后台任务: " + id
                    : "❌ 未找到可重试的后台任务: " + id;
        }
        if (normalized.regionMatches(true, 0, "compensate ", 0, 11)) {
            return manager.compensate(normalized.substring(11).trim());
        }
        if (normalized.regionMatches(true, 0, "log ", 0, 4)) {
            return manager.find(normalized.substring(4).trim())
                    .map(TaskCommandFormatter::formatLog)
                    .orElse("❌ 未找到后台任务: " + normalized.substring(4).trim());
        }
        return """
                ❌ 未知 /task 子命令: %s
                可用命令：
                  /task
                  /task list [N]
                  /task add <任务内容>
                  /task cancel <task_id>
                  /task pause <task_id>
                  /task resume <task_id>
                  /task retry <task_id>
                  /task compensate <task_id>
                  /task log <task_id>
                """.formatted(payload).trim();
    }

    public static String formatList(List<DurableTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return "📭 暂无后台任务";
        }
        StringBuilder sb = new StringBuilder("📋 最近 ").append(tasks.size()).append(" 个后台任务：\n");
        for (DurableTask task : tasks) {
            sb.append("   ")
                    .append(task.id())
                    .append("  ")
                    .append(task.status().value())
                    .append("  ")
                    .append(task.durationMs())
                    .append("ms  ")
                    .append(task.shortPrompt())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    public static String formatLog(DurableTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 后台任务 ").append(task.id()).append('\n');
        sb.append("状态: ").append(task.status().value()).append('\n');
        sb.append("节点: ").append(task.currentNodeId()).append('\n');
        sb.append("尝试: ").append(task.attempt()).append('\n');
        if (task.projectPath() != null && !task.projectPath().isBlank()) {
            sb.append("工作区: ").append(task.projectPath()).append('\n');
        }
        sb.append("创建: ").append(task.createdAt()).append('\n');
        if (task.updatedAt() != null) {
            sb.append("更新: ").append(task.updatedAt()).append('\n');
        }
        if (task.startedAt() != null) {
            sb.append("开始: ").append(task.startedAt()).append('\n');
        }
        if (task.finishedAt() != null) {
            sb.append("结束: ").append(task.finishedAt()).append(" (").append(task.durationMs()).append("ms)\n");
        }
        sb.append("\n任务:\n").append(task.prompt()).append('\n');
        if (task.error() != null && !task.error().isBlank()) {
            sb.append("\n错误:\n").append(task.error()).append('\n');
        }
        if (task.result() != null && !task.result().isBlank()) {
            sb.append("\n结果:\n").append(task.result()).append('\n');
        }
        return sb.toString().trim();
    }

    private static int parseLimit(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
