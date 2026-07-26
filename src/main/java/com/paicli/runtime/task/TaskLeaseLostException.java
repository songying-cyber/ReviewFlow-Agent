package com.paicli.runtime.task;

public class TaskLeaseLostException extends RuntimeException {
    public TaskLeaseLostException(String message) {
        super(message);
    }
}
