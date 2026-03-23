package com.paicli.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRoleTest {

    @Test
    void shouldHaveThreeRoles() {
        AgentRole[] roles = AgentRole.values();
        assertEquals(3, roles.length);
    }

    @Test
    void shouldHaveCorrectDisplayNames() {
        assertEquals("规划者", AgentRole.PLANNER.getDisplayName());
        assertEquals("执行者", AgentRole.WORKER.getDisplayName());
        assertEquals("检查者", AgentRole.REVIEWER.getDisplayName());
    }

    @Test
    void shouldHaveNonEmptyDescriptions() {
        for (AgentRole role : AgentRole.values()) {
            assertFalse(role.getDescription().isEmpty(),
                    role.name() + " should have a non-empty description");
        }
    }

    @Test
    void shouldValueOfByName() {
        assertSame(AgentRole.PLANNER, AgentRole.valueOf("PLANNER"));
        assertSame(AgentRole.WORKER, AgentRole.valueOf("WORKER"));
        assertSame(AgentRole.REVIEWER, AgentRole.valueOf("REVIEWER"));
    }
}
