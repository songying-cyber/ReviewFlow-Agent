package com.paicli.runtime.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.snapshot.RestoreResult;
import com.paicli.snapshot.TurnSnapshot;
import com.paicli.tool.ToolError;
import com.paicli.tool.ToolErrorType;
import com.paicli.tool.ToolExecutionStatus;
import com.paicli.tool.ToolIntentContext;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResultMeta;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public class DurableToolRegistry extends ToolRegistry {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> DURABLE_TOOLS = Set.of(
            "write_file",
            "create_project",
            "execute_command",
            "revert_turn"
    );

    private final DurableToolExecutionStore store;
    private final String workflowId;
    private final String nodeId;

    public DurableToolRegistry(DurableToolExecutionStore store, String workflowId, String nodeId) {
        this.store = store;
        this.workflowId = workflowId;
        this.nodeId = nodeId == null || nodeId.isBlank() ? "react-loop" : nodeId;
    }

    @Override
    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
        return executeTools(invocations, ToolIntentContext.empty());
    }

    @Override
    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations, ToolIntentContext intentContext) {
        if (invocations == null || invocations.isEmpty() || store == null || workflowId == null || workflowId.isBlank()) {
            return super.executeTools(invocations, intentContext);
        }
        boolean hasDurableTool = invocations.stream().anyMatch(invocation -> DURABLE_TOOLS.contains(invocation.name()));
        if (!hasDurableTool) {
            return super.executeTools(invocations, intentContext);
        }

        List<ToolExecutionResult> results = new ArrayList<>();
        for (ToolInvocation invocation : invocations) {
            if (!DURABLE_TOOLS.contains(invocation.name())) {
                results.addAll(super.executeTools(List.of(invocation), intentContext));
                continue;
            }
            results.add(executeDurably(invocation, intentContext));
        }
        return results;
    }

    @Override
    protected ToolOutput doExecuteTool(String name, String argumentsJson) {
        if ("tool_status".equals(name)) {
            return statusTool(argumentsJson);
        }
        if ("tool_compensate".equals(name)) {
            return compensateTool(argumentsJson);
        }
        return super.doExecuteTool(name, argumentsJson);
    }

    private ToolExecutionResult executeDurably(ToolInvocation invocation, ToolIntentContext intentContext) {
        String normalizedArgs = normalizeJson(invocation.argumentsJson());
        String key = sha256(workflowId + "|" + nodeId + "|" + invocation.name() + "|" + normalizedArgs);
        Optional<DurableToolExecution> existing = store.findToolExecutionByKey(key);
        if (existing.isPresent()) {
            return existingResult(invocation, existing.get());
        }

        String beforeHash = fileHashJson(invocation.name(), invocation.argumentsJson());
        String beforeSnapshot = snapshot("pre-tool", invocation);
        DurableToolExecution planned = new DurableToolExecution(
                "tool_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                workflowId,
                nodeId,
                invocation.id(),
                key,
                invocation.name(),
                invocation.argumentsJson(),
                "running",
                "",
                null,
                sideEffectLevel(invocation.name()),
                beforeSnapshot,
                null,
                beforeHash,
                null,
                null,
                Instant.now(),
                Instant.now());
        store.recordToolRunning(planned);

        ToolExecutionResult result = super.executeTools(List.of(invocation), intentContext).get(0);
        String afterHash = fileHashJson(invocation.name(), invocation.argumentsJson());
        String afterSnapshot = snapshot("post-tool", invocation);
        ToolExecutionStatus status = durableStatus(result);
        boolean succeeded = status == ToolExecutionStatus.SUCCESS || status == ToolExecutionStatus.PARTIAL;
        String compensation = succeeded ? compensationJson(invocation.name(), beforeSnapshot, beforeHash, afterHash) : null;
        store.recordToolFinished(
                key,
                persistedStatus(status),
                result.result(),
                succeeded ? null : result.result(),
                beforeSnapshot,
                afterSnapshot,
                beforeHash,
                afterHash,
                compensation);
        return withOperationId(result, planned.id());
    }

    private ToolExecutionResult existingResult(ToolInvocation invocation, DurableToolExecution execution) {
        ToolResultMeta meta = new ToolResultMeta(false, "idempotency_replay", execution.id(),
                "如需确认状态，请调用 tool_status 查询该 operation_id。");
        String status = execution.status() == null ? "" : execution.status().toLowerCase();
        if ("running".equals(status) || "pending".equals(status)) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    "工具操作仍在执行或上次执行结果尚未确认: " + execution.id(),
                    0,
                    false,
                    List.of(),
                    null,
                    ToolExecutionStatus.PENDING,
                    new ToolResultMeta(false, "existing_operation_pending", execution.id(),
                            "先调用 tool_status 查询状态，不要重复执行写工具。"));
        }
        if ("succeeded".equals(status)) {
            if (reconcile(execution)) {
                return new ToolExecutionResult(
                        invocation.id(),
                        invocation.name(),
                        invocation.argumentsJson(),
                        execution.result(),
                        0,
                        false,
                        List.of(),
                        null,
                        ToolExecutionStatus.SUCCESS,
                        meta);
            }
            ToolError error = new ToolError(
                    ToolErrorType.EXECUTION_ERROR,
                    true,
                    "工具操作状态不确定: 已记录成功，但当前文件状态与记录的 after_hash 不一致: " + execution.id(),
                    "先调用 tool_status 查看详情，必要时向用户确认是否补偿或按当前文件重新规划。");
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    error.message(),
                    0,
                    false,
                    List.of(),
                    error,
                    ToolExecutionStatus.UNKNOWN,
                    new ToolResultMeta(false, "state_reconcile_failed", execution.id(), error.suggestion()));
        }
        if ("compensated".equals(status)) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    "工具操作已补偿，不会按幂等键重复执行: " + execution.id(),
                    0,
                    false,
                    List.of(),
                    null,
                    ToolExecutionStatus.SUCCESS,
                    meta);
        }
        ToolError error = new ToolError(
                ToolErrorType.EXECUTION_ERROR,
                true,
                execution.error() == null || execution.error().isBlank()
                        ? "工具操作已失败或状态未知: " + execution.id()
                        : execution.error(),
                "不要直接重复执行有副作用工具；先调用 tool_status 查询，再根据用户意图决定是否重试。");
        return new ToolExecutionResult(
                invocation.id(),
                invocation.name(),
                invocation.argumentsJson(),
                error.message(),
                0,
                false,
                List.of(),
                error,
                "unknown".equals(status) ? ToolExecutionStatus.UNKNOWN : ToolExecutionStatus.FAILED,
                new ToolResultMeta(false, "existing_operation_" + (status.isBlank() ? "unknown" : status),
                        execution.id(), error.suggestion()));
    }

    private ToolExecutionResult withOperationId(ToolExecutionResult result, String operationId) {
        ToolResultMeta old = result.meta();
        ToolResultMeta meta = new ToolResultMeta(
                old.partial(),
                old.reason(),
                operationId,
                old.nextAction().isBlank()
                        ? "如需确认写工具最终状态，可调用 tool_status 查询 operation_id。"
                        : old.nextAction());
        return new ToolExecutionResult(
                result.id(),
                result.name(),
                result.argumentsJson(),
                result.result(),
                result.elapsedMillis(),
                result.timedOut(),
                result.imageParts(),
                result.error(),
                result.status(),
                meta);
    }

    private ToolOutput statusTool(String argumentsJson) {
        Optional<String> operationId = operationIdFromArgs(argumentsJson);
        if (operationId.isEmpty()) {
            return ToolOutput.error(ToolErrorType.INVALID_ARGUMENT, true,
                    "tool_status 需要 operation_id",
                    "请传入工具结果中返回的 operation_id。");
        }
        Optional<DurableToolExecution> execution = store.findToolExecutionById(operationId.get());
        if (execution.isEmpty()) {
            return ToolOutput.error(ToolErrorType.NOT_FOUND, true,
                    "未找到工具操作: " + operationId.get(),
                    "请确认 operation_id 来自当前任务的工具结果。");
        }
        DurableToolExecution item = execution.get();
        ToolExecutionStatus status = executionStatus(item.status());
        ToolResultMeta meta = new ToolResultMeta(false, "durable_operation_status", item.id(),
                nextActionForStatus(item));
        String text = formatStatus(item);
        if (status == ToolExecutionStatus.FAILED) {
            return new ToolOutput(false, status, text,
                    new ToolError(ToolErrorType.EXECUTION_ERROR, true, text, meta.nextAction()),
                    meta,
                    List.of());
        }
        return ToolOutput.status(status, text, meta);
    }

    private ToolOutput compensateTool(String argumentsJson) {
        Optional<String> operationId = operationIdFromArgs(argumentsJson);
        if (operationId.isEmpty()) {
            return ToolOutput.error(ToolErrorType.INVALID_ARGUMENT, true,
                    "tool_compensate 需要 operation_id",
                    "请传入要补偿的写工具 operation_id。");
        }
        Optional<DurableToolExecution> execution = store.findToolExecutionById(operationId.get());
        if (execution.isEmpty()) {
            return ToolOutput.error(ToolErrorType.NOT_FOUND, true,
                    "未找到工具操作: " + operationId.get(),
                    "请确认 operation_id 来自当前任务的工具结果。");
        }
        DurableToolExecution item = execution.get();
        String status = item.status() == null ? "" : item.status().toLowerCase();
        ToolResultMeta meta = new ToolResultMeta(false, "durable_operation_compensation", item.id(),
                "补偿后再次调用 tool_status 可确认状态。");
        if ("compensated".equals(status)) {
            return ToolOutput.status(ToolExecutionStatus.SUCCESS,
                    "工具操作已补偿: " + item.id(),
                    meta);
        }
        if ("running".equals(status) || "pending".equals(status)) {
            return ToolOutput.status(ToolExecutionStatus.PENDING,
                    "工具操作仍在执行，不能补偿: " + item.id(),
                    new ToolResultMeta(false, "operation_still_running", item.id(),
                            "稍后调用 tool_status 确认终态，再决定是否补偿。"));
        }
        if (!item.succeeded()) {
            return ToolOutput.error(ToolErrorType.SEMANTIC_ERROR, true,
                    "只有已成功且可逆的工具操作可以补偿: " + item.id() + " 当前状态=" + item.status(),
                    "先调用 tool_status 查看状态；失败或未知操作需要人工判断是否已有副作用。");
        }
        String snapshot = beforeSnapshotFromCompensation(item);
        if (snapshot == null || snapshot.isBlank()) {
            return ToolOutput.error(ToolErrorType.SEMANTIC_ERROR, false,
                    "该工具操作没有可用补偿快照: " + item.id(),
                    "只能人工检查并恢复相关文件。");
        }
        try {
            RestoreResult result = getSnapshotService().manager().restoreCommit(snapshot);
            if (!result.success()) {
                return ToolOutput.error(ToolErrorType.EXECUTION_ERROR, true,
                        "补偿失败: " + result.message(),
                        "请查看 Side-Git 状态或人工恢复文件。");
            }
            store.markToolCompensated(item.id());
            return ToolOutput.status(ToolExecutionStatus.SUCCESS,
                    "已补偿工具操作: " + item.id() + "\nrestored_snapshot: "
                            + snapshot.substring(0, Math.min(8, snapshot.length())),
                    meta);
        } catch (Exception e) {
            return ToolOutput.error(ToolErrorType.EXECUTION_ERROR, true,
                    "补偿失败: " + e.getMessage(),
                    "请查看 Side-Git 状态或人工恢复文件。");
        }
    }

    private boolean reconcile(DurableToolExecution execution) {
        if (execution.afterHashJson() == null || execution.afterHashJson().isBlank()) {
            return true;
        }
        String currentHash = fileHashJson(execution.toolName(), execution.argsJson());
        return currentHash != null && currentHash.equals(execution.afterHashJson());
    }

    private String snapshot(String phase, ToolInvocation invocation) {
        try {
            TurnSnapshot snapshot = getSnapshotService().manager().createSnapshot(
                    "pre-tool".equals(phase)
                            ? com.paicli.snapshot.SnapshotPhase.PRE_TURN
                            : com.paicli.snapshot.SnapshotPhase.POST_TURN,
                    workflowId + "-" + nodeId + "-" + invocation.name(),
                    "durable tool " + phase + "\nkey=" + invocation.name());
            return snapshot == null ? null : snapshot.commitId();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Optional<String> operationIdFromArgs(String argumentsJson) {
        try {
            JsonNode args = MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
            String id = args.path("operation_id").asText("").trim();
            return id.isBlank() ? Optional.empty() : Optional.of(id);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static ToolExecutionStatus durableStatus(ToolExecutionResult result) {
        if (result == null) {
            return ToolExecutionStatus.UNKNOWN;
        }
        if (result.timedOut()) {
            return ToolExecutionStatus.UNKNOWN;
        }
        if (result.status() == ToolExecutionStatus.FAILED
                || result.status() == ToolExecutionStatus.UNKNOWN
                || result.status() == ToolExecutionStatus.PENDING
                || result.status() == ToolExecutionStatus.PARTIAL) {
            return result.status();
        }
        return looksFailed(result.result()) ? ToolExecutionStatus.FAILED : ToolExecutionStatus.SUCCESS;
    }

    private static String persistedStatus(ToolExecutionStatus status) {
        return switch (status == null ? ToolExecutionStatus.UNKNOWN : status) {
            case SUCCESS, PARTIAL -> "succeeded";
            case FAILED -> "failed";
            case PENDING -> "pending";
            case UNKNOWN -> "unknown";
        };
    }

    private static ToolExecutionStatus executionStatus(String status) {
        String normalized = status == null ? "" : status.toLowerCase();
        return switch (normalized) {
            case "succeeded", "compensated" -> ToolExecutionStatus.SUCCESS;
            case "running", "pending" -> ToolExecutionStatus.PENDING;
            case "failed" -> ToolExecutionStatus.FAILED;
            default -> ToolExecutionStatus.UNKNOWN;
        };
    }

    private static String nextActionForStatus(DurableToolExecution execution) {
        String status = execution.status() == null ? "" : execution.status().toLowerCase();
        if ("running".equals(status) || "pending".equals(status)) {
            return "稍后再次调用 tool_status；不要重复执行同一个写工具。";
        }
        if ("succeeded".equals(status) && execution.reversible()) {
            return "如用户明确要求回滚该次副作用，可调用 tool_compensate。";
        }
        if ("failed".equals(status) || "unknown".equals(status)) {
            return "先检查文件/日志确认副作用，再决定是否重试或人工恢复。";
        }
        return "";
    }

    private static String formatStatus(DurableToolExecution execution) {
        return """
                operation_id: %s
                status: %s
                tool: %s
                workflow_id: %s
                node_id: %s
                reversible: %s
                created_at: %s
                updated_at: %s
                result: %s
                error: %s
                """.formatted(
                execution.id(),
                execution.status(),
                execution.toolName(),
                execution.workflowId(),
                execution.nodeId(),
                execution.reversible(),
                execution.createdAt(),
                execution.updatedAt(),
                truncate(execution.result(), 1000),
                truncate(execution.error(), 1000)).trim();
    }

    private static String beforeSnapshotFromCompensation(DurableToolExecution execution) {
        if (execution.compensationJson() == null || execution.compensationJson().isBlank()) {
            return execution.beforeSnapshotId();
        }
        try {
            JsonNode node = MAPPER.readTree(execution.compensationJson());
            String snapshot = node.path("beforeSnapshotId").asText("");
            return snapshot.isBlank() ? execution.beforeSnapshotId() : snapshot;
        } catch (Exception e) {
            return execution.beforeSnapshotId();
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars)) + "...(truncated)";
    }

    private String compensationJson(String toolName, String beforeSnapshot, String beforeHash, String afterHash) {
        if (beforeSnapshot == null || beforeSnapshot.isBlank()) {
            return null;
        }
        if (!"write_file".equals(toolName) && !"create_project".equals(toolName)) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "type", "restore_snapshot",
                    "beforeSnapshotId", beforeSnapshot,
                    "beforeHash", beforeHash == null ? "" : beforeHash,
                    "afterHash", afterHash == null ? "" : afterHash
            ));
        } catch (Exception e) {
            return null;
        }
    }

    private String fileHashJson(String toolName, String argsJson) {
        try {
            JsonNode args = MAPPER.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            if ("write_file".equals(toolName)) {
                String path = args.path("path").asText("");
                return hashForPath(path);
            }
            if ("create_project".equals(toolName)) {
                String name = args.path("name").asText("");
                return hashForPath(name);
            }
            if ("execute_command".equals(toolName)) {
                return hashForPath(".");
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String hashForPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        try {
            Path root = Path.of(getProjectPath()).toAbsolutePath().normalize();
            Path path = root.resolve(rawPath).normalize();
            if (!path.startsWith(root) || !Files.exists(path)) {
                return "{\"exists\":false,\"path\":\"" + escape(rawPath) + "\"}";
            }
            if (Files.isDirectory(path)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (var stream = Files.walk(path)) {
                    for (Path child : stream
                            .filter(Files::isRegularFile)
                            .filter(child -> !isHashExcluded(root, child))
                            .sorted()
                            .toList()) {
                        digest.update(root.relativize(child).toString().getBytes(StandardCharsets.UTF_8));
                        digest.update(Files.readAllBytes(child));
                    }
                }
                return "{\"exists\":true,\"directory\":true,\"path\":\"" + escape(rawPath) + "\",\"sha256\":\""
                        + HexFormat.of().formatHex(digest.digest()) + "\"}";
            }
            return "{\"exists\":true,\"directory\":false,\"path\":\"" + escape(rawPath) + "\",\"sha256\":\""
                    + sha256Bytes(Files.readAllBytes(path)) + "\"}";
        } catch (Exception e) {
            return "{\"unknown\":true,\"path\":\"" + escape(rawPath) + "\"}";
        }
    }

    private static boolean isHashExcluded(Path root, Path child) {
        String rel = root.relativize(child).toString().replace('\\', '/');
        return rel.startsWith(".git/")
                || rel.startsWith("target/")
                || rel.startsWith("node_modules/")
                || rel.startsWith(".gradle/")
                || rel.startsWith(".mvn/")
                || rel.startsWith(".paicli/");
    }

    private static String normalizeJson(String json) {
        try {
            JsonNode node = MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
            return MAPPER.writeValueAsString(canonical(node));
        } catch (Exception e) {
            return json == null ? "" : json.trim();
        }
    }

    private static Object canonical(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                sorted.put(field.getKey(), canonical(field.getValue()));
            }
            return sorted;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonNode child : node) {
                values.add(canonical(child));
            }
            return values;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    private static boolean looksFailed(String result) {
        if (result == null) {
            return false;
        }
        String normalized = result.toLowerCase();
        return normalized.contains("工具执行失败")
                || normalized.contains("策略拒绝")
                || normalized.contains("失败:")
                || normalized.contains("写入文件失败")
                || normalized.contains("创建项目失败");
    }

    private static String sideEffectLevel(String toolName) {
        return switch (toolName) {
            case "write_file" -> "idempotent_write";
            case "create_project" -> "reversible_write";
            case "execute_command" -> "unknown_write";
            default -> "unknown";
        };
    }

    private static String sha256(String text) {
        return sha256Bytes((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Bytes(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes == null ? new byte[0] : bytes));
        } catch (Exception e) {
            return "";
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
