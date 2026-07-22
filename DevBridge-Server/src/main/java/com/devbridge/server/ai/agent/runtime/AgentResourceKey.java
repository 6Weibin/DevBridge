package com.devbridge.server.ai.agent.runtime;

/**
 * Agent 资源唯一键。
 *
 * <p>by AI.Coding</p>
 *
 * @param type 资源类型
 * @param resourceId 资源标识
 */
public record AgentResourceKey(AgentResourceType type, String resourceId)
        implements Comparable<AgentResourceKey> {

    private static final int MAX_RESOURCE_ID_LENGTH = 500;

    /**
     * 校验并规范化资源标识。
     */
    public AgentResourceKey {
        if (type == null || resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("资源类型和标识不能为空");
        }
        resourceId = resourceId.trim();
        if (resourceId.length() > MAX_RESOURCE_ID_LENGTH) {
            throw new IllegalArgumentException("资源标识过长");
        }
    }

    /**
     * 按类型和标识稳定排序，保证多资源申请顺序一致。
     *
     * @param other 其他资源键
     * @return 比较结果
     */
    @Override
    public int compareTo(AgentResourceKey other) {
        int typeComparison = type.name().compareTo(other.type.name());
        return typeComparison != 0 ? typeComparison : resourceId.compareTo(other.resourceId);
    }

    /**
     * 返回可诊断资源名称。
     *
     * @return 资源名称
     */
    public String displayName() {
        return type.name() + ":" + resourceId;
    }
}
