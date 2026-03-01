package com.paicli.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPlanTest {

    @Test
    void computeExecutionOrderRespectsDependencies() {
        ExecutionPlan plan = new ExecutionPlan("plan_1", "demo");
        Task task1 = new Task("task_1", "create project", Task.TaskType.COMMAND);
        Task task2 = new Task("task_2", "read pom", Task.TaskType.FILE_READ, List.of("task_1"));
        Task task3 = new Task("task_3", "verify structure", Task.TaskType.VERIFICATION, List.of("task_2"));

        plan.addTask(task1);
        plan.addTask(task2);
        plan.addTask(task3);

        assertEquals(List.of("task_1", "task_2", "task_3"), plan.getExecutionOrder());
    }

    @Test
    void executableTasksWaitUntilDependenciesComplete() {
        ExecutionPlan plan = new ExecutionPlan("plan_2", "demo");
        Task task1 = new Task("task_1", "create project", Task.TaskType.COMMAND);
        Task task2 = new Task("task_2", "read pom", Task.TaskType.FILE_READ, List.of("task_1"));

        plan.addTask(task1);
        plan.addTask(task2);

        assertEquals(List.of(task1), plan.getExecutableTasks());

        task1.markCompleted("done");

        assertEquals(List.of(task2), plan.getExecutableTasks());
    }

    @Test
    void addDependencyMutatesTaskState() {
        Task task = new Task("task_1", "read pom", Task.TaskType.FILE_READ);

        task.addDependency("task_0");

        assertEquals(List.of("task_0"), task.getDependencies());
    }

    @Test
    void addTaskBuildsDependentRelationship() {
        ExecutionPlan plan = new ExecutionPlan("plan_3", "demo");
        Task task1 = new Task("task_1", "create project", Task.TaskType.COMMAND);
        Task task2 = new Task("task_2", "read pom", Task.TaskType.FILE_READ, List.of("task_1"));

        plan.addTask(task1);
        plan.addTask(task2);

        assertTrue(plan.getTask("task_1").getDependents().contains("task_2"));
    }

    @Test
    void executableTasksCanExposeParallelBatch() {
        ExecutionPlan plan = new ExecutionPlan("plan_4", "demo");
        Task task1 = new Task("task_1", "read pom", Task.TaskType.FILE_READ);
        Task task2 = new Task("task_2", "list dir", Task.TaskType.COMMAND);
        Task task3 = new Task("task_3", "verify", Task.TaskType.VERIFICATION, List.of("task_1", "task_2"));

        plan.addTask(task1);
        plan.addTask(task2);
        plan.addTask(task3);

        assertEquals(List.of(task1, task2), plan.getExecutableTasks());

        task1.markCompleted("done");
        assertEquals(List.of(task2), plan.getExecutableTasks());

        task2.markCompleted("done");
        assertEquals(List.of(task3), plan.getExecutableTasks());
    }

    @Test
    void summarizeKeepsPlanPreviewCompact() {
        ExecutionPlan plan = new ExecutionPlan("plan_5",
                "请把任务拆成可并行的 DAG:\n1. 读取 pom.xml\n2. 列出 src/main/java");
        Task task1 = new Task("task_1", "read pom", Task.TaskType.FILE_READ);
        Task task2 = new Task("task_2", "list src main java", Task.TaskType.COMMAND);
        Task task3 = new Task("task_3", "summarize project", Task.TaskType.ANALYSIS, List.of("task_1", "task_2"));

        plan.addTask(task1);
        plan.addTask(task2);
        plan.addTask(task3);

        String summary = plan.summarize();

        assertTrue(summary.contains("任务数: 3 | 并行批次: 2 | 当前可执行: 2"));
        assertTrue(summary.contains("首批执行: task_1, task_2"));
        assertTrue(summary.contains("最终收敛: task_3"));
        assertTrue(!summary.contains("╔════════"));
    }

    @Test
    void executionBatchesFollowDagLayers() {
        ExecutionPlan plan = new ExecutionPlan("plan_6", "demo");
        Task task1 = new Task("task_1", "read pom", Task.TaskType.FILE_READ);
        Task task2 = new Task("task_2", "list main", Task.TaskType.COMMAND);
        Task task3 = new Task("task_3", "list test", Task.TaskType.COMMAND);
        Task task4 = new Task("task_4", "read readme", Task.TaskType.FILE_READ);
        Task task5 = new Task("task_5", "summarize", Task.TaskType.ANALYSIS, List.of("task_1", "task_2", "task_3", "task_4"));

        plan.addTask(task1);
        plan.addTask(task2);
        plan.addTask(task3);
        plan.addTask(task4);
        plan.addTask(task5);

        List<List<Task>> batches = plan.getExecutionBatches();

        assertEquals(List.of(task1, task2, task3, task4), batches.get(0));
        assertEquals(List.of(task5), batches.get(1));
    }
}
