package com.devbridge.server.ai.agent.runtime;

import java.util.List;

/**
 * Agent 任务取消传播结果。
 *
 * <p>by AI.Coding</p>
 *
 * @param newlyCanceled 本次是否首次发出取消
 * @param registeredHandleCount 首次取消时已注册句柄数
 * @param canceledHandleCount 成功取消句柄数
 * @param failures 失败句柄的有界诊断摘要
 */
public record AgentCancellationResult(
        boolean newlyCanceled,
        int registeredHandleCount,
        int canceledHandleCount,
        List<String> failures) {

    /**
     * 固化不可变失败摘要。
     */
    public AgentCancellationResult {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /**
     * 返回未找到活动作用域时的幂等结果。
     *
     * @return 空取消结果
     */
    public static AgentCancellationResult notActive() {
        return new AgentCancellationResult(false, 0, 0, List.of());
    }
}
