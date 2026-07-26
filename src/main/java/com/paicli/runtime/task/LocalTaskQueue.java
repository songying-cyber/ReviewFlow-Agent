package com.paicli.runtime.task;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-process MQ implementation used by the default CLI runtime.
 */
public class LocalTaskQueue implements TaskQueue {
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();

    @Override
    public void publish(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        queue.offer(taskId);
    }

    @Override
    public Optional<String> poll(Duration timeout) throws InterruptedException {
        long millis = timeout == null ? 0 : Math.max(0, timeout.toMillis());
        String taskId = queue.poll(millis, TimeUnit.MILLISECONDS);
        return taskId == null || taskId.isBlank() ? Optional.empty() : Optional.of(taskId);
    }
}
