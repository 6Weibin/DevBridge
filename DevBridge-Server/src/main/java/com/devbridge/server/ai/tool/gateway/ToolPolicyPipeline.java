package com.devbridge.server.ai.tool.gateway;

import com.devbridge.server.ai.agent.confirmation.AgentConfirmation;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.Definition;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskAction;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskDecision;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskLevel;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 统一工具策略流水线，固定执行契约、Schema、平台和风险校验顺序。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class ToolPolicyPipeline {

    private final ToolSchemaValidator schemaValidator;

    /**
     * 注入 Schema 校验器。
     *
     * @param schemaValidator Schema 校验器
     */
    public ToolPolicyPipeline(ToolSchemaValidator schemaValidator) {
        this.schemaValidator = schemaValidator;
    }

    /**
     * 评估单次工具请求；返回 ALLOW 才能进入执行流水线。
     *
     * @param registration 工具注册信息
     * @param request 工具请求
     * @return 策略结果
     */
    public PolicyOutcome evaluate(ToolRegistry.Registration registration, CallRequest request) {
        Definition definition = registration.definition();
        validateContract(definition, request);
        schemaValidator.validate(definition.inputSchema(), request.arguments());
        validatePlatform(definition, request.executionContext().platform());
        RiskDecision decision = registration.adapter().assess(request, definition);
        validateRiskFloor(definition, decision);
        // 当前产品由明确知道操作范围的本机用户使用，风险仅用于审计，不再阻断执行。
        return new PolicyOutcome(directExecution(decision), null);
    }

    /**
     * 校验工具和请求契约版本、启用及弃用状态。
     *
     * @param definition 工具定义
     * @param request 工具请求
     */
    private void validateContract(Definition definition, CallRequest request) {
        if (request == null || request.identity() == null || request.tool() == null
                || request.executionContext() == null || request.requestedBy() == null) {
            throw new IllegalArgumentException("工具请求字段不完整");
        }
        if (!major(ToolContract.SCHEMA_VERSION).equals(major(request.schemaVersion()))) {
            throw new IllegalArgumentException("工具调用契约主版本不兼容: " + request.schemaVersion());
        }
        if (!major(definition.schemaVersion()).equals(major(request.tool().schemaVersion()))) {
            throw new IllegalArgumentException("工具 Schema 主版本不兼容: " + request.tool().schemaVersion());
        }
        if (!definition.identity().toolId().equals(request.tool().toolId())) {
            throw new IllegalArgumentException("工具引用与注册定义不一致");
        }
        if (!definition.enabled() || definition.deprecation().deprecated()) {
            throw new IllegalStateException("工具已禁用或弃用: " + definition.identity().toolId());
        }
    }

    /**
     * 校验目标平台是否属于工具声明范围。
     *
     * @param definition 工具定义
     * @param platform 目标平台
     */
    private void validatePlatform(Definition definition, Platform platform) {
        boolean supported = definition.metadata().platforms().contains(Platform.PLATFORM_INDEPENDENT)
                || definition.metadata().platforms().contains(platform);
        if (!supported) {
            throw new IllegalStateException("工具不支持目标平台: " + platform);
        }
    }

    /**
     * 防止 Adapter 把风险降低到工具静态基线以下。
     *
     * @param definition 工具定义
     * @param decision 动态风险决策
     */
    private void validateRiskFloor(Definition definition, RiskDecision decision) {
        if (decision == null || decision.level() == null || decision.action() == null) {
            throw new IllegalStateException("工具风险决策不完整");
        }
        RiskLevel baseline = definition.metadata().riskProfile().minimumLevel();
        if (riskOrder(decision.level()) < riskOrder(baseline)) {
            throw new IllegalStateException("工具动态风险不能低于静态基线");
        }
    }

    /**
     * 将风险结果收敛为直接执行，保留风险等级供审计和展示。
     *
     * @param decision 原决策
     * @return 直接执行决策
     */
    private RiskDecision directExecution(RiskDecision decision) {
        return new RiskDecision(
                decision.level(), RiskAction.ALLOW, "trusted-local-user",
                "DIRECT_EXECUTION_ENABLED", "AI 工具二次确认已关闭，直接执行",
                "", decision.evaluatedAt());
    }

    /**
     * 提取语义化版本主版本。
     *
     * @param version 版本号
     * @return 主版本
     */
    private String major(String version) {
        if (!StringUtils.hasText(version)) {
            return "";
        }
        return version.trim().split("\\.", 2)[0];
    }

    /**
     * 计算风险严格程度顺序。
     *
     * @param level 风险等级
     * @return 严格程度
     */
    private int riskOrder(RiskLevel level) {
        return switch (level) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case UNCLASSIFIED -> 3;
        };
    }

    /**
     * 策略流水线结果。
     *
     * @param decision 风险决策
     * @param confirmation 新创建确认，可空
     */
    public record PolicyOutcome(RiskDecision decision, AgentConfirmation confirmation) {
    }
}
