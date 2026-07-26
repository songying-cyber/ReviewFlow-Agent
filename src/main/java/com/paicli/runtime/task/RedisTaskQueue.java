package com.paicli.runtime.task;

import redis.clients.jedis.JedisPooled;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis-backed wake-up queue for durable tasks.
 *
 * Redis is deliberately only used for delivery hints. A worker must still claim
 * the task row in SQLite before executing it, so duplicate Redis messages are
 * harmless.
 */
public class RedisTaskQueue implements TaskQueue {
    public static final String DEFAULT_URL = "redis://localhost:6379/0";
    public static final String DEFAULT_KEY = "paicli:tasks:ready";

    private final JedisPooled jedis;
    private final String key;

    public RedisTaskQueue(String redisUrl, String key) {
        this(new JedisPooled(normalizeUrl(redisUrl)), key);
    }

    RedisTaskQueue(JedisPooled jedis, String key) {
        this.jedis = jedis;
        this.key = key == null || key.isBlank() ? DEFAULT_KEY : key.trim();
        this.jedis.ping();
    }

    @Override
    public void publish(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        jedis.rpush(key, taskId.trim());
    }

    @Override
    public Optional<String> poll(Duration timeout) {
        int seconds = timeout == null ? 0 : Math.max(1, (int) Math.ceil(timeout.toMillis() / 1000.0));
        List<String> result = jedis.blpop(seconds, key);
        if (result == null || result.size() < 2 || result.get(1) == null || result.get(1).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(result.get(1));
    }

    @Override
    public void close() {
        jedis.close();
    }

    private static String normalizeUrl(String redisUrl) {
        return redisUrl == null || redisUrl.isBlank() ? DEFAULT_URL : redisUrl.trim();
    }
}
