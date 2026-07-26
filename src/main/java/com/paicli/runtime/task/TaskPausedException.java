package com.paicli.runtime.task;

public class TaskPausedException extends RuntimeException {
    public TaskPausedException(String message) {
        super(message);
    }
}
