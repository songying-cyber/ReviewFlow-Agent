package com.paicli.memory;

import java.util.List;
import java.util.Optional;

/**
 * Memory 接口 - 记忆系统的统一抽象
 *
 * 分为短期记忆（ShortTermMemory）和长期记忆（LongTermMemory）：
 * - 短期记忆：当前对话的上下文，包括消息历史和工具结果
 * - 长期记忆：跨对话持久化的关键信息，如用户偏好、项目事实
 */
public interface Memory {
    /**
     * 存储一条记忆
     */
    void store(MemoryEntry entry);

    /**
     * 根据ID检索记忆
     */
    Optional<MemoryEntry> retrieve(String id);

    /**
     * 搜索相关记忆
     */
    List<MemoryEntry> search(String query, int limit);

    /**
     * 获取所有记忆
     */
    List<MemoryEntry> getAll();

    /**
     * 删除指定记忆
     */
    boolean delete(String id);

    /**
     * 清空所有记忆
     */
    void clear();

    /**
     * 获取当前记忆的 token 总数
     */
    int getTokenCount();

    /**
     * 获取记忆条数
     */
    int size();
}
