package com.paicli.runtime.task;

public enum TaskStatus {
    ENQUEUED("enqueued"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELED("canceled");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static TaskStatus from(String value) {
        if (value == null) {
            return ENQUEUED;
        }
        for (TaskStatus status : values()) {
            if (status.value.equalsIgnoreCase(value) || status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        return ENQUEUED;
    }
}
