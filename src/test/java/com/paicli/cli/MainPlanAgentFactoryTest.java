package com.paicli.cli;

import com.paicli.agent.Agent;
import com.paicli.agent.PlanExecuteAgent;
import com.paicli.memory.MemoryManager;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertSame;

class MainPlanAgentFactoryTest {

    @Test
    void planModeReusesReactToolRegistryAndMemoryManager() throws Exception {
        ToolRegistry sharedToolRegistry = new ToolRegistry();
        Agent reactAgent = new Agent("test-key", sharedToolRegistry);
        MemoryManager sharedMemoryManager = reactAgent.getMemoryManager();

        PlanExecuteAgent planAgent = Main.createPlanAgent(
                "test-key",
                reactAgent,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.cancel()
        );

        assertSame(sharedToolRegistry, readField(planAgent, "toolRegistry"));
        assertSame(sharedMemoryManager, readField(planAgent, "memoryManager"));
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
