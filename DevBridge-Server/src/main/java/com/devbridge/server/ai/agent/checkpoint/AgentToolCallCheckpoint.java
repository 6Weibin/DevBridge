package com.devbridge.server.ai.agent.checkpoint;

/**
 * 单次工具调用的恢复快照，不保存完整工具输出。
 *
 * <p>by AI.Coding</p>
 *
 * @param toolCallId 工具调用标识
 * @param stepId 所属步骤标识
 * @param status 调用状态
 * @param idempotencyKey 幂等键，可空
 * @param requestDigest 规范化请求摘要，可空
 * @param resultReference 结果或 Artifact 引用，可空
 * @param sideEffectVerified 是否已验证副作用结果
 * @param protectedRequest 加密后的原始中立工具请求，可空
 */
public record AgentToolCallCheckpoint(
        String toolCallId,
        String stepId,
        AgentToolCallCheckpointStatus status,
        String idempotencyKey,
        String requestDigest,
        String resultReference,
        boolean sideEffectVerified,
        String protectedRequest) {

    /**
     * 兼容旧 Checkpoint 构造点；旧记录没有可恢复的原始工具请求。
     *
     * @param toolCallId 工具调用标识
     * @param stepId 步骤标识
     * @param status 调用状态
     * @param idempotencyKey 幂等键
     * @param requestDigest 请求摘要
     * @param resultReference 结果引用
     * @param sideEffectVerified 副作用验证结果
     */
    public AgentToolCallCheckpoint(
            String toolCallId,
            String stepId,
            AgentToolCallCheckpointStatus status,
            String idempotencyKey,
            String requestDigest,
            String resultReference,
            boolean sideEffectVerified) {
        this(toolCallId, stepId, status, idempotencyKey, requestDigest,
                resultReference, sideEffectVerified, null);
    }
}
