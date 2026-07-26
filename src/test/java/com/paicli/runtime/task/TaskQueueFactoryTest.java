package com.paicli.runtime.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskQueueFactoryTest {

    @Test
    void explicitLocalQueue() {
        withQueueProperty("local", () ->
                assertInstanceOf(LocalTaskQueue.class, TaskQueueFactory.createDefault()));
    }

    @Test
    void rejectsUnknownQueueMode() {
        withQueueProperty("bogus", () ->
                assertThrows(IllegalArgumentException.class, TaskQueueFactory::createDefault));
    }

    private void withQueueProperty(String value, Runnable assertion) {
        String old = System.getProperty("paicli.task.queue");
        try {
            if (value == null) {
                System.clearProperty("paicli.task.queue");
            } else {
                System.setProperty("paicli.task.queue", value);
            }
            assertion.run();
        } finally {
            if (old == null) {
                System.clearProperty("paicli.task.queue");
            } else {
                System.setProperty("paicli.task.queue", old);
            }
        }
    }
}
