package com.paicli.render;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;

import java.io.PrintStream;
import java.util.List;

/**
 * 终端渲染器抽象。
 *
 * <p>把对话流核心交互（流式输出、工具调用、HITL、状态栏、行内 diff、palette）
 * 收口到一个接口，方便 inline 流式 / Lanterna 全屏 / plain 三种形态切换。
 *
 * <p>启动期一次性输出（banner、MCP/Skill 摘要等）仍可走原有 stdout；
 * 对话期的流、工具块、状态栏、输入区应尽量经过 Renderer，避免多个组件
 * 直接争抢终端光标。
 *
 * <p>线程模型：所有方法应在调用方线程同步返回；
 * 涉及异步（如 Lanterna GUI 线程）的实现负责自己做线程封送。
 */
public interface Renderer extends AutoCloseable {

    /** 启动渲染器（例如设置滚动区域、启动 GUI 主循环）。Main 必须先调用一次。 */
    void start();

    /** 开始一次用户任务输出。默认 no-op；inline renderer 用它重置本轮可重绘 transcript。 */
    default void beginTurn() {
    }

    /** 进入用户输入前。inline renderer 用它刷新输入周边状态。 */
    default void beforeInput() {
    }

    /** 用户输入结束后。inline renderer 用它恢复输入周边状态。 */
    default void afterInput() {
    }

    /** 当前渲染器是否支持独立的模型思考面板。 */
    default boolean supportsThinkingPanel() {
        return false;
    }

    /** 当前渲染器是否应该把 reasoning/thinking 内容展示给用户。 */
    default boolean rendersReasoning() {
        return true;
    }

    /** 开始显示模型思考面板。plain renderer 保持 no-op，继续用正文流式输出。 */
    default void beginThinking(String label) {
    }

    /** 追加模型 reasoning delta 到思考面板。 */
    default void appendThinking(String delta) {
    }

    /** 结束并清理模型思考面板。 */
    default void endThinking() {
    }

    /** 追加 assistant 正文 delta。普通终端渲染器忽略；远程通道可用它做正文分片推送。 */
    default void appendAssistantContentDelta(String delta) {
    }

    /** assistant 正文流结束。普通终端渲染器忽略；远程通道可用它 flush 剩余正文。 */
    default void finishAssistantContent() {
    }

    /** 当前渲染器是否支持通用临时活动面板。 */
    default boolean supportsActivityPanel() {
        return supportsThinkingPanel();
    }

    /** 开始显示通用临时活动面板，用于非 Agent 思考的阻塞操作。 */
    default void beginActivity(String label, String detail) {
        beginThinking(label);
        if (detail != null && !detail.isBlank()) {
            appendThinking(detail);
        }
    }

    /** 结束并清理通用临时活动面板。 */
    default void endActivity() {
        endThinking();
    }

    /** 当前渲染器希望 LineReader 使用的左侧输入提示。 */
    default String inputPrompt() {
        return "> ";
    }

    /** 当前渲染器希望 LineReader 使用的右侧提示；返回 null 表示不显示。 */
    default String inputRightPrompt() {
        return null;
    }

    @Override
    void close();

    /**
     * 流式输出的目标 PrintStream。
     *
     * <p>Agent.StreamRenderer / PlanExecuteAgent.TaskStreamRenderer / SubAgent.SubAgentStreamRenderer
     * 把流式 reasoning / content 写到这里。
     *
     * <p>对 InlineRenderer / PlainRenderer 而言这就是 {@code System.out}；
     * LanternaRenderer 返回一个把字节写入 CenterPane 的 PrintStream。
     */
    PrintStream stream();

    /** 当前输出区域可用列数。Markdown 表格等宽布局按这个值做收缩和换行。 */
    default int terminalColumns() {
        return 120;
    }

    /**
     * 渲染一组工具调用的标签和关键参数。
     *
     * <p>InlineRenderer 把每条调用包装成可折叠块（Day 3）；
     * LanternaRenderer 写入 CenterPane.appendToolCall；
     * PlainRenderer 直接 println 当前 Agent 内已有的标签格式。
     */
    void appendToolCalls(List<LlmClient.ToolCall> toolCalls);

    /**
     * 渲染一个文件 diff 块。
     *
     * @param filePath 文件路径
     * @param before   修改前内容（null 表示新建）
     * @param after    修改后内容（null 表示删除）
     */
    void appendDiff(String filePath, String before, String after);

    /** 更新底部状态栏 / StatusPane。允许频繁调用，渲染器内部自行节流。 */
    void updateStatus(StatusInfo status);

    /**
     * 同步阻塞地展示 HITL 审批请求并收集决策。
     *
     * <p>实现需要保证在 GUI 线程之外调用时不死锁；如果实现是 Lanterna，
     * 内部用 CountDownLatch 把 GUI 线程结果回写主线程。
     */
    ApprovalResult promptApproval(ApprovalRequest request);

    /**
     * 显示一个临时浮起的选择列表，等待用户选定一项或取消。
     *
     * @return 选中项的下标；用户取消（Esc）返回 -1
     */
    int openPalette(String title, List<String> items);
}
