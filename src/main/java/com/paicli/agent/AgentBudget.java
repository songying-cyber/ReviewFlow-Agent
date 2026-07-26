package com.paicli.agent;

import com.paicli.llm.LlmClient;

/**
 * Backward-compatible name for the Agent loop controller.
 *
 * New code should treat this as loop control rather than a pure token budget:
 * it also detects repeated tools, no-progress loops, invalid reflection, and
 * consecutive tool failures.
 */
public class AgentBudget extends AgentLoopController {
    public AgentBudget(int tokenBudget, int stagnationWindow, int hardMaxIterations) {
        super(tokenBudget, stagnationWindow, hardMaxIterations);
    }

    public AgentBudget(int tokenBudget, int stagnationWindow, int hardMaxIterations,
                       int noProgressWindow, int invalidReflectionWindow, int toolFailureWindow) {
        super(tokenBudget, stagnationWindow, hardMaxIterations,
                noProgressWindow, invalidReflectionWindow, toolFailureWindow);
    }

    public static AgentBudget fromSystemProperties() {
        return fromLlmClient(null);
    }

    public static AgentBudget fromLlmClient(LlmClient llmClient) {
        AgentLoopController controller = AgentLoopController.fromLlmClient(llmClient);
        return new AgentBudget(
                controller.tokenBudget(),
                controller.stagnationWindow(),
                controller.hardMaxIterations(),
                controller.noProgressWindow(),
                controller.invalidReflectionWindow(),
                controller.toolFailureWindow());
    }
}
