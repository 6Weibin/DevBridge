package com.devbridge.server.ai.agent.runtime;

/**
 * 进入工具执行前的幂等预留请求。
 *
 * <p>by AI.Coding</p>
 *
 * @param taskId 任务标识
 * @param stepId 步骤标识
 * @param toolCallId 工具调用标识
 * @param idempotencyKey 幂等键
 * @param requestDigest 规范化参数摘要
 * @param sideEffectExpected 是否可能产生副作用
 * @param protectedRequest 加密后的原始工具请求
 */
public record AgentToolExecutionRequest(
        String taskId,
        String stepId,
        String toolCallId,
        String idempotencyKey,
        String requestDigest,
        boolean sideEffectExpected,
        String protectedRequest) {

    /**
     * 兼容旧调用点；不携带恢复请求时由后续首次请求补齐。
     *
     * @param taskId 任务标识
     * @param stepId 步骤标识
     * @param toolCallId 工具调用标识
     * @param idempotencyKey 幂等键
     * @param requestDigest 请求摘要
     * @param sideEffectExpected 是否可能产生副作用
     */
    public AgentToolExecutionRequest(
            String taskId,
            String stepId,
            String toolCallId,
            String idempotencyKey,
            String requestDigest,
            boolean sideEffectExpected) {
        this(taskId, stepId, toolCallId, idempotencyKey, requestDigest, sideEffectExpected, null);
    }
}
