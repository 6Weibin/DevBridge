package com.devbridge.server.ai.agent.runtime;

import java.time.Instant;

/**
 * 活动资源锁快照。
 *
 * <p>by AI.Coding</p>
 *
 * @param leaseId 租约标识
 * @param taskId 任务标识
 * @param resource 资源键
 * @param mode 访问模式
 * @param acquiredAt 获取时间
 * @param expiresAt 过期时间
 */
public record AgentResourceLockSnapshot(
        String leaseId,
        String taskId,
        AgentResourceKey resource,
        AgentResourceMode mode,
        Instant acquiredAt,
        Instant expiresAt) {
}
