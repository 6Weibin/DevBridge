package com.devbridge.server.ai.agent.runtime;

/**
 * 创建 Agent Task 的应用层命令，只表达业务输入，不复用前端请求模型。
 *
 * <p>by AI.Coding</p>
 *
 * @param conversationId 所属历史会话标识
 * @param goal 用户业务目标
 * @param idempotencyKey 客户端创建幂等键，可空
 */
public record CreateAgentTaskCommand(String conversationId, String goal, String idempotencyKey) {

    /**
     * 兼容未提供幂等键的内部调用；生产 Chat 和 Task API 应传入稳定键。
     *
     * @param conversationId 会话标识
     * @param goal 任务目标
     */
    public CreateAgentTaskCommand(String conversationId, String goal) {
        this(conversationId, goal, "");
    }
}
