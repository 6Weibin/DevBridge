package com.devbridge.server.ai.agent.runtime;

/**
 * Agent 单个资源锁请求。
 *
 * <p>by AI.Coding</p>
 *
 * @param resource 资源键
 * @param mode 访问模式
 */
public record AgentResourceRequest(AgentResourceKey resource, AgentResourceMode mode) {

    /**
     * 校验资源请求完整性。
     */
    public AgentResourceRequest {
        if (resource == null || mode == null) {
            throw new IllegalArgumentException("资源和访问模式不能为空");
        }
    }
}
