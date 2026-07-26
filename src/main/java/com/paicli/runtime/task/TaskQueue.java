package com.paicli.runtime.task;

import java.io.Closeable;
import java.time.Duration;
import java.util.Optional;

/**
 * Wake-up queue for durable tasks.
 *
 * The queue is intentionally not the source of truth: messages may be duplicated
 * or stale, and workers must still claim the task from the durable store before
 * executing it.
 */
public interface TaskQueue extends Closeable {
    void publish(String taskId);

    Optional<String> poll(Duration timeout) throws InterruptedException;

    @Override
    default void close() {
    }
}
