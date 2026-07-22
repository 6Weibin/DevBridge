package com.devbridge.server.ai.agent.runtime;

/**
 * Agent Task 状态转换异常，表示请求违反已固化的生命周期规则。
 *
 * <p>by AI.Coding</p>
 */
public class AgentTaskTransitionException extends RuntimeException {

    /**
     * 创建状态转换异常。
     *
     * @param message 可诊断错误信息
     */
    public AgentTaskTransitionException(String message) {
        super(message);
    }
}
