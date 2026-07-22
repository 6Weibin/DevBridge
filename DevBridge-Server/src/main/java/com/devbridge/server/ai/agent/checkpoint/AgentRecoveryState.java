package com.devbridge.server.ai.agent.checkpoint;

import java.util.List;
import java.util.Map;

/**
 * Agent Task 恢复所需的最小控制状态，不复制大型正文和工具输出。
 *
 * <p>by AI.Coding</p>
 *
 * @param currentStepId 当前步骤标识，可空
 * @param completedStepIds 已完成步骤
 * @param toolCalls 工具调用恢复状态
 * @param pendingConfirmationId 等待确认或确认续跑关联标识，可空
 * @param pendingInputKey 待补充输入键，可空
 * @param continuationContext 后端恢复模型调用所需的有界对话上下文，可空
 * @param continuationState 确认续跑状态，可空
 * @param protectedInputValue 加密后的用户补充输入，可空
 */
public record AgentRecoveryState(
        String currentStepId,
        List<String> completedStepIds,
        Map<String, AgentToolCallCheckpoint> toolCalls,
        String pendingConfirmationId,
        String pendingInputKey,
        AgentContinuationContext continuationContext,
        String continuationState,
        String protectedInputValue) {

    /**
     * 固化集合副本，防止保存后被调用方修改。
     */
    public AgentRecoveryState {
        completedStepIds = completedStepIds == null ? List.of() : List.copyOf(completedStepIds);
        toolCalls = toolCalls == null ? Map.of() : Map.copyOf(toolCalls);
    }

    /**
     * 兼容既有 Checkpoint 构造点；尚未接入聊天主链路的任务没有续跑上下文。
     *
     * @param currentStepId 当前步骤标识
     * @param completedStepIds 已完成步骤
     * @param toolCalls 工具调用状态
     * @param pendingConfirmationId 待确认标识
     * @param pendingInputKey 待输入标识
     */
    public AgentRecoveryState(
            String currentStepId,
            List<String> completedStepIds,
            Map<String, AgentToolCallCheckpoint> toolCalls,
            String pendingConfirmationId,
            String pendingInputKey) {
        this(currentStepId, completedStepIds, toolCalls, pendingConfirmationId, pendingInputKey, null, null, null);
    }

    /**
     * 兼容已接入对话恢复上下文的既有构造点。
     *
     * @param currentStepId 当前步骤标识
     * @param completedStepIds 已完成步骤
     * @param toolCalls 工具调用状态
     * @param pendingConfirmationId 待确认标识
     * @param pendingInputKey 待输入标识
     * @param continuationContext 对话恢复上下文
     */
    public AgentRecoveryState(
            String currentStepId,
            List<String> completedStepIds,
            Map<String, AgentToolCallCheckpoint> toolCalls,
            String pendingConfirmationId,
            String pendingInputKey,
            AgentContinuationContext continuationContext) {
        this(currentStepId, completedStepIds, toolCalls, pendingConfirmationId, pendingInputKey,
                continuationContext, null, null);
    }

    /**
     * 兼容确认续跑状态构造点；旧 Checkpoint 尚未保存补充输入。
     */
    public AgentRecoveryState(
            String currentStepId,
            List<String> completedStepIds,
            Map<String, AgentToolCallCheckpoint> toolCalls,
            String pendingConfirmationId,
            String pendingInputKey,
            AgentContinuationContext continuationContext,
            String continuationState) {
        this(currentStepId, completedStepIds, toolCalls, pendingConfirmationId, pendingInputKey,
                continuationContext, continuationState, null);
    }

    /**
     * 判断指定工具调用是否已成功并应在恢复时跳过。
     *
     * @param toolCallId 工具调用标识
     * @return 已成功时返回 true
     */
    public boolean shouldSkipToolCall(String toolCallId) {
        AgentToolCallCheckpoint checkpoint = toolCalls.get(toolCallId);
        return checkpoint != null && checkpoint.status() == AgentToolCallCheckpointStatus.SUCCEEDED;
    }

    /**
     * 后端续跑所需的最小对话快照，不包含密钥、完整工具输出和模型正文流。
     *
     * <p>by AI.Coding</p>
     *
     * @param message 原始用户问题
     * @param conversationId 对话标识
     * @param device 设备摘要，可空
     * @param history 最近普通文本历史
     * @param summary 较早历史摘要
     * @param rag 本地知识引用快照
     */
    public record AgentContinuationContext(
            String message,
            String conversationId,
            AgentDeviceSnapshot device,
            List<AgentHistorySnapshot> history,
            AgentSummarySnapshot summary,
            AgentRagSnapshot rag) {

        /**
         * 固化历史集合，防止保存后被调用方修改。
         */
        public AgentContinuationContext {
            history = history == null ? List.of() : List.copyOf(history);
            summary = summary == null ? AgentSummarySnapshot.empty() : summary;
            rag = rag == null ? AgentRagSnapshot.empty() : rag;
        }

        /**
         * 兼容 M3-03 前未保存摘要的 Checkpoint。
         *
         * @param message 原始问题
         * @param conversationId 会话标识
         * @param device 设备摘要
         * @param history 最近历史
         */
        public AgentContinuationContext(
                String message,
                String conversationId,
                AgentDeviceSnapshot device,
                List<AgentHistorySnapshot> history) {
            this(message, conversationId, device, history, AgentSummarySnapshot.empty());
        }

        /**
         * 兼容 M3-09 前仅保存摘要的 Checkpoint。
         *
         * @param message 原始问题
         * @param conversationId 会话标识
         * @param device 设备摘要
         * @param history 最近历史
         * @param summary 较早摘要
         */
        public AgentContinuationContext(
                String message,
                String conversationId,
                AgentDeviceSnapshot device,
                List<AgentHistorySnapshot> history,
                AgentSummarySnapshot summary) {
            this(message, conversationId, device, history, summary, AgentRagSnapshot.empty());
        }
    }

    /**
     * 确认恢复所需的较早历史摘要元数据。
     *
     * <p>by AI.Coding</p>
     *
     * @param content 摘要正文
     * @param version 摘要版本
     * @param sourceMessageCount 来源消息数量
     */
    public record AgentSummarySnapshot(String content, long version, int sourceMessageCount) {

        /** 兼容旧 Checkpoint 缺失摘要字段。 */
        public AgentSummarySnapshot {
            content = content == null ? "" : content;
        }

        /** 创建空摘要。 */
        public static AgentSummarySnapshot empty() {
            return new AgentSummarySnapshot("", 0, 0);
        }
    }

    /**
     * 确认恢复使用的有界本地知识证据。
     *
     * <p>by AI.Coding</p>
     *
     * @param content 不可信知识正文
     * @param citations 引用标识
     */
    public record AgentRagSnapshot(String content, List<String> citations) {

        /** 固化引用并兼容旧 Checkpoint。 */
        public AgentRagSnapshot {
            content = content == null ? "" : content;
            citations = citations == null ? List.of() : List.copyOf(citations);
        }

        /** 创建空 RAG 快照。 */
        public static AgentRagSnapshot empty() {
            return new AgentRagSnapshot("", List.of());
        }
    }

    /**
     * 与前端无关的设备恢复摘要。
     *
     * <p>by AI.Coding</p>
     *
     * @param platform 设备平台
     * @param serial 设备序列号
     * @param model 设备型号
     * @param osVersion 系统版本
     * @param status 连接状态
     */
    public record AgentDeviceSnapshot(
            String platform,
            String serial,
            String model,
            String osVersion,
            String status) {
    }

    /**
     * 最近普通文本历史快照。
     *
     * <p>by AI.Coding</p>
     *
     * @param role 消息角色
     * @param content 有界文本内容
     */
    public record AgentHistorySnapshot(String role, String content) {
    }
}
