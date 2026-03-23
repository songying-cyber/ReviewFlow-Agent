package com.paicli.agent;

/**
 * Agent 角色定义 - Multi-Agent 系统中的角色分工
 */
public enum AgentRole {
    PLANNER("规划者", "负责分析用户任务，制定执行计划，将复杂任务拆解为可执行的子任务"),
    WORKER("执行者", "负责执行具体任务步骤，调用工具完成文件操作、命令执行等操作"),
    REVIEWER("检查者", "负责检查执行结果的质量和正确性，提供改进建议");

    private final String displayName;
    private final String description;

    AgentRole(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
