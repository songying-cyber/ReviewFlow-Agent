package com.paicli.plan;

import com.paicli.llm.GLMClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

    @Test
    void createsMinimalPlanForSimpleGoalWithoutCallingLlm() throws Exception {
        Planner planner = new Planner(new FailingGLMClient());

        ExecutionPlan plan = planner.createPlan("列出当前目录的文件");

        assertEquals("直接执行简单任务：列出当前目录的文件", plan.getSummary());
        assertEquals(List.of("task_1"), plan.getExecutionOrder());
        Task task = plan.getTask("task_1");
        assertEquals(Task.TaskType.COMMAND, task.getType());
        assertEquals("列出当前目录的文件", task.getDescription());
    }

    @Test
    void delegatesComplexGoalToLlmPlannerPath() throws Exception {
        Planner planner = new Planner(new StubGLMClient("""
                {
                  "summary": "复杂任务",
                  "tasks": [
                    {
                      "id": "task_a",
                      "description": "先读取 pom.xml",
                      "type": "FILE_READ",
                      "dependencies": []
                    },
                    {
                      "id": "task_b",
                      "description": "再验证项目结构",
                      "type": "VERIFICATION",
                      "dependencies": ["task_a"]
                    }
                  ]
                }
                """));

        ExecutionPlan plan = planner.createPlan("先读取 pom.xml 然后验证项目结构");

        assertEquals("复杂任务", plan.getSummary());
        assertEquals(2, plan.getAllTasks().size());
        assertTrue(plan.getTask("task_2").getDependencies().contains("task_1"));
    }

    private static final class FailingGLMClient extends GLMClient {
        private FailingGLMClient() {
            super("test-key");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            throw new IOException("simple goal should not call llm");
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final String content;

        private StubGLMClient(String content) {
            super("test-key");
            this.content = content;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return new ChatResponse("assistant", content, null, 100, 20);
        }
    }
}
