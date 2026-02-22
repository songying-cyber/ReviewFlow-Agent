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
}
