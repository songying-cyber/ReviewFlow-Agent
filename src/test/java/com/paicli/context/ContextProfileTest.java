package com.paicli.context;

import com.paicli.llm.DeepSeekClient;
import com.paicli.llm.GLMClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ContextProfile 的派生公式。
 *
 * 设计原则：没有"长 / 短模式"分档，所有参数都是 maxContextWindow 的简单函数；
 * 全模型走同一阈值（90% 触发压缩）。
 */
class ContextProfileTest {

    @Test
    void glmDerivesParamsFrom200kWindow() {
        ContextProfile profile = ContextProfile.from(new GLMClient("test-key"));

        assertEquals(200_000, profile.maxContextWindow());
        assertEquals(160_000, profile.agentTokenBudget());                  // 200k × 0.8
        assertEquals(0.90, profile.compressionTriggerRatio(), 0.001);
        assertEquals(180_000, profile.compressionTriggerTokens());          // 200k × 0.9
        assertEquals(90_000, profile.shortTermMemoryBudget());              // 200k × 0.45
        assertTrue(profile.mcpResourceIndexEnabled());                      // window ≥ 32k
        assertTrue(profile.promptCachingSupported());
    }

    @Test
    void deepSeekDerivesParamsFromMillionWindow() {
        ContextProfile profile = ContextProfile.from(new DeepSeekClient("test-key"));

        assertEquals(1_000_000, profile.maxContextWindow());
        assertEquals(800_000, profile.agentTokenBudget());                  // 1M × 0.8
        assertEquals(900_000, profile.compressionTriggerTokens());          // 1M × 0.9
        assertEquals(450_000, profile.shortTermMemoryBudget());             // 1M × 0.45
        assertEquals("automatic-prefix-cache", profile.promptCacheMode());
        assertTrue(profile.mcpResourceIndexEnabled());
    }

    @Test
    void compressionTriggerIsAlwaysOnRegardlessOfWindowSize() {
        // 关键：长 window 也必须可触发压缩，没有"长模式不压缩"的硬开关
        for (int window : new int[]{8_000, 32_000, 128_000, 200_000, 1_000_000}) {
            ContextProfile profile = ContextProfile.custom(window, 1_000);
            assertTrue(profile.compressionTriggerRatio() > 0,
                    "window=" + window + " 必须有正的触发率");
            assertTrue(profile.compressionTriggerTokens() > 0,
                    "window=" + window + " 必须有正的触发 token 数");
        }
    }

    @Test
    void smallWindowDisablesMcpResourceIndexInjection() {
        // window < 32k 时索引注入不值当，关闭
        ContextProfile profile = ContextProfile.custom(16_000, 4_000);
        assertFalse(profile.mcpResourceIndexEnabled());
    }

    @Test
    void customProfileRespectsExplicitShortTermBudget() {
        ContextProfile profile = ContextProfile.custom(128_000, 40);

        assertEquals(40, profile.shortTermMemoryBudget());
        // 即使是 custom 也走同一压缩阈值
        assertEquals(0.90, profile.compressionTriggerRatio(), 0.001);
    }

    @Test
    void nullClientFallsBackToReasonableDefault() {
        ContextProfile profile = ContextProfile.from(null);
        assertEquals(128_000, profile.maxContextWindow());
        assertEquals(0.90, profile.compressionTriggerRatio(), 0.001);
        assertFalse(profile.promptCachingSupported());
    }
}
