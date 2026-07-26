package com.paicli.runtime.task;

public final class DurableRunContext {
    private static final ThreadLocal<Controller> CURRENT = new InheritableThreadLocal<>();

    private DurableRunContext() {
    }

    public static void bind(Controller controller) {
        if (controller == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(controller);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static String workflowId() {
        Controller controller = CURRENT.get();
        return controller == null ? null : controller.workflowId();
    }

    public static String nodeId() {
        Controller controller = CURRENT.get();
        return controller == null ? "react-loop" : controller.nodeId();
    }

    public static void checkpoint(String kind, String stateJson) {
        Controller controller = CURRENT.get();
        if (controller != null) {
            controller.checkpoint(kind, stateJson);
        }
    }

    public static void checkpointAndPauseIfRequested(String kind, String stateJson) {
        Controller controller = CURRENT.get();
        if (controller == null) {
            return;
        }
        controller.checkpoint(kind, stateJson);
        if (controller.pauseRequested()) {
            throw new TaskPausedException("任务已在安全边界暂停");
        }
    }

    public interface Controller {
        String workflowId();

        String nodeId();

        void checkpoint(String kind, String stateJson);

        boolean pauseRequested();
    }
}
