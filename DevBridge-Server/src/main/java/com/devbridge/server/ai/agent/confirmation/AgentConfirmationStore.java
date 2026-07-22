package com.devbridge.server.ai.agent.confirmation;

import java.util.Optional;

/**
 * Agent 确认记录存储端口。
 *
 * <p>by AI.Coding</p>
 */
public interface AgentConfirmationStore {

    /**
     * 保存新的确认记录。
     *
     * @param confirmation 确认记录
     * @return 已保存记录
     */
    AgentConfirmation save(AgentConfirmation confirmation);

    /**
     * 条件更新确认状态，防止重复决策。
     *
     * @param confirmation 新确认记录
     * @param expectedStatus 预期旧状态
     * @return 已更新记录
     */
    AgentConfirmation update(AgentConfirmation confirmation, AgentConfirmationStatus expectedStatus);

    /**
     * 查询任务下的确认记录。
     *
     * @param taskId 任务标识
     * @param confirmationId 确认标识
     * @return 确认记录
     */
    Optional<AgentConfirmation> find(String taskId, String confirmationId);
}
