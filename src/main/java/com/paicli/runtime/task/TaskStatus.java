package com.paicli.runtime.task;

public enum TaskStatus {
    ENQUEUED("enqueued"),
    PAUSE_REQUESTED("pause_requested"),
    PAUSED("paused"),
    CANCEL_REQUESTED("cancel_requested"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELED("canceled"),
    COMPENSATING("compensating"),
    COMPENSATED("compensated");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static TaskStatus from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("任务状态不能为空");
        }
        for (TaskStatus status : values()) {
            if (status.value.equalsIgnoreCase(value) || status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知任务状态: " + value);
    }
}
