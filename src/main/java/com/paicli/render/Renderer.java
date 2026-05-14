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
 * <p>非对话流的输出（banner、slash 命令结果、错误提示等）不经过此接口，
 * 仍走原有 {@code System.out.println}，避免大改 {@code Main.java}。
 *
 * <p>线程模型：所有方法应在调用方线程同步返回；
 * 涉及异步（如 Lanterna GUI 线程）的实现负责自己做线程封送。
 */
public interface Renderer extends AutoCloseable {

    /** 启动渲染器（例如设置滚动区域、启动 GUI 主循环）。Main 必须先调用一次。 */
    void start();

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
