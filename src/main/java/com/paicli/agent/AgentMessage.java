package com.paicli.agent;

/**
 * Agent 间通信消息 - Multi-Agent 协作的基本通信单元
 *
 * 消息类型说明：
 * - TASK:      主控分配给子代理的任务
 * - RESULT:    子代理返回的执行结果
 * - FEEDBACK:  检查者对结果的反馈（可能包含改进建议）
 * - APPROVAL:  检查者认可结果
 * - REJECTION: 检查者拒绝结果，需要重新执行
 * - ERROR:     子代理在执行过程中遭遇系统级错误（例如 LLM 调用失败），调用方需识别并优雅处理
 */
public record AgentMessage(
        String fromAgent,
        AgentRole fromRole,
        String content,
        Type type
) {
    public enum Type {
        TASK,
        RESULT,
        FEEDBACK,
        APPROVAL,
        REJECTION,
        ERROR
    }

    /**
     * 创建任务消息（主控 -> 子代理）
     */
    public static AgentMessage task(String fromAgent, String content) {
        return new AgentMessage(fromAgent, null, content, Type.TASK);
    }

    /**
     * 创建结果消息（子代理 -> 主控）
     */
    public static AgentMessage result(String fromAgent, AgentRole role, String content) {
        return new AgentMessage(fromAgent, role, content, Type.RESULT);
    }

    /**
     * 创建反馈消息（检查者 -> 主控）
     */
    public static AgentMessage feedback(String fromAgent, String content) {
        return new AgentMessage(fromAgent, AgentRole.REVIEWER, content, Type.FEEDBACK);
    }

    /**
     * 创建审批通过消息
     */
    public static AgentMessage approval(String fromAgent, String content) {
        return new AgentMessage(fromAgent, AgentRole.REVIEWER, content, Type.APPROVAL);
    }

    /**
     * 创建拒绝消息（检查者认为结果不合格）
     */
    public static AgentMessage rejection(String fromAgent, String content) {
        return new AgentMessage(fromAgent, AgentRole.REVIEWER, content, Type.REJECTION);
    }

    /**
     * 创建错误消息（子代理在执行过程中遇到系统级错误）
     */
    public static AgentMessage error(String fromAgent, AgentRole role, String content) {
        return new AgentMessage(fromAgent, role, content, Type.ERROR);
    }
}
