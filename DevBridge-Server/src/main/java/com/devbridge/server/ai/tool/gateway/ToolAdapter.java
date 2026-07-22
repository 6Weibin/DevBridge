package com.devbridge.server.ai.tool.gateway;

import com.devbridge.server.ai.agent.runtime.AgentResourceRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.Definition;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskDecision;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 中立工具适配器端口，底层 ADB、Local Shell、领域服务和标准 MCP 均通过该端口接入。
 *
 * <p>by AI.Coding</p>
 */
public interface ToolAdapter {

    /**
     * 返回当前 Adapter 提供的工具定义。
     *
     * @return 工具定义
     */
    List<Definition> definitions();

    /**
     * 将旧版工具参数迁移到当前定义版本。默认拒绝未声明的迁移，防止静默误用旧参数。
     *
     * @param sourceSchemaVersion 调用方记录的旧版本
     * @param definition 当前工具定义
     * @param arguments 旧版参数
     * @return 当前版本参数
     */
    default JsonNode migrateArguments(
            String sourceSchemaVersion, Definition definition, JsonNode arguments) {
        throw new IllegalArgumentException(
                "工具未配置 Schema 迁移: " + definition.identity().toolId()
                        + " " + sourceSchemaVersion + " -> " + definition.schemaVersion());
    }

    /**
     * 根据本地规则评估最终风险，不能信任模型提供的风险字段。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @return 风险决策
     */
    RiskDecision assess(CallRequest request, Definition definition);

    /**
     * 声明本次调用需要持有的资源锁。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @return 资源锁请求
     */
    List<AgentResourceRequest> resources(CallRequest request, Definition definition);

    /**
     * 执行已经通过统一策略流水线的工具请求。
     *
     * @param request 工具请求
     * @param definition 工具定义
     * @param decision 已确定风险决策
     * @return 工具结果
     */
    CallResult execute(CallRequest request, Definition definition, RiskDecision decision);

    /**
     * 取消指定运行中工具调用；不支持取消的短任务保持默认空实现。
     *
     * @param request 原工具请求
     * @param definition 工具定义
     */
    default void cancel(CallRequest request, Definition definition) {
        // 只有声明支持取消的 Adapter 才需要覆盖，避免为同步短任务增加无意义实现。
    }
}
