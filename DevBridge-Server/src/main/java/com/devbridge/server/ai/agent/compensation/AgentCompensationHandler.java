package com.devbridge.server.ai.agent.compensation;

/**
 * Agent 补偿处理器边界，由具体领域实现实际清理或回滚。
 *
 * <p>by AI.Coding</p>
 */
public interface AgentCompensationHandler {

    /**
     * 获取处理器类型。
     *
     * @return 处理器类型
     */
    String type();

    /**
     * 执行补偿动作。
     *
     * @param action 补偿动作
     * @param context 补偿上下文
     */
    void compensate(AgentCompensationAction action, AgentCompensationContext context);
}
