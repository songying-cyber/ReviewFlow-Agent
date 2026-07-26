package com.paicli.runtime.task;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public final class TaskQueueFactory {
    private static final String QUEUE_PROPERTY = "paicli.task.queue";
    private static final String QUEUE_ENV = "PAICLI_TASK_QUEUE";
    private static final String REDIS_URL_PROPERTY = "paicli.task.redis.url";
    private static final String REDIS_URL_ENV = "PAICLI_TASK_REDIS_URL";
    private static final String REDIS_KEY_PROPERTY = "paicli.task.redis.key";
    private static final String REDIS_KEY_ENV = "PAICLI_TASK_REDIS_KEY";

    private TaskQueueFactory() {
    }

    public static TaskQueue createDefault() {
        String mode = configValue(QUEUE_PROPERTY, QUEUE_ENV);
        if (mode == null || mode.isBlank() || mode.equalsIgnoreCase("local")) {
            return new LocalTaskQueue();
        }
        if (mode.equalsIgnoreCase("redis")) {
            String url = configValue(REDIS_URL_PROPERTY, REDIS_URL_ENV);
            String key = configValue(REDIS_KEY_PROPERTY, REDIS_KEY_ENV);
            return new RedisTaskQueue(url, key);
        }
        throw new IllegalArgumentException("未知后台任务队列类型: " + mode + "（支持 local / redis）");
    }

    private static String configValue(String property, String envKey) {
        String fromProperty = System.getProperty(property);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty.trim();
        }
        String fromEnv = System.getenv(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromDotEnv = readFromDotEnv(envKey);
        if (fromDotEnv != null && !fromDotEnv.isBlank()) {
            return fromDotEnv.trim();
        }
        return null;
    }

    private static String readFromDotEnv(String key) {
        File[] envFiles = { new File(".env"), new File(System.getProperty("user.home"), ".env") };
        for (File envFile : envFiles) {
            if (!envFile.exists()) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#") || !line.startsWith(key + "=")) {
                        continue;
                    }
                    return line.substring((key + "=").length()).trim();
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }
}
