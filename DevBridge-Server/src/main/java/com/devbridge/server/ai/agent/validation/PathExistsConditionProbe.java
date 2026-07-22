package com.devbridge.server.ai.agent.validation;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * 本机路径存在条件探针。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class PathExistsConditionProbe implements AgentConditionProbe {

    /**
     * 返回路径存在条件类型。
     *
     * @return 条件类型
     */
    @Override
    public AgentStepConditionType type() {
        return AgentStepConditionType.PATH_EXISTS;
    }

    /**
     * 规范化路径并检查是否存在，不读取文件正文。
     *
     * @param condition 条件定义
     * @param context 校验上下文
     * @return 路径检查结果
     */
    @Override
    public AgentConditionCheck evaluate(
            AgentStepCondition condition, AgentStepValidationContext context) {
        try {
            Path path = Path.of(condition.target()).toAbsolutePath().normalize();
            boolean exists = Files.exists(path);
            return new AgentConditionCheck(
                    condition.conditionId(), exists, Boolean.toString(exists),
                    exists ? "路径存在" : "路径不存在");
        } catch (InvalidPathException ex) {
            return new AgentConditionCheck(
                    condition.conditionId(), false, "invalid", "路径格式不合法");
        }
    }
}
