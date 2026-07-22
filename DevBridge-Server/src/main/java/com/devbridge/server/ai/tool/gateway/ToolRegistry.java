package com.devbridge.server.ai.tool.gateway;

import com.devbridge.server.ai.tool.gateway.ToolContract.Definition;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CapabilityMetadata;
import com.devbridge.server.ai.tool.gateway.ToolContract.CapabilityQuery;
import com.devbridge.server.ai.tool.gateway.ToolContract.ToolReference;
import com.devbridge.server.ai.tool.gateway.ToolContract.VersionCompatibility;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 统一工具注册表，负责工具发现、全局 ID 去重和 Adapter 定位。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class ToolRegistry {

    private final Map<String, Registration> registrations;

    /**
     * 从全部 Spring Adapter 构建不可变注册表。
     *
     * @param adapters 工具适配器
     */
    public ToolRegistry(List<ToolAdapter> adapters) {
        Map<String, Registration> values = new LinkedHashMap<>();
        for (ToolAdapter adapter : adapters) {
            for (Definition definition : adapter.definitions()) {
                validateDefinition(definition);
                String toolId = definition.identity().toolId();
                if (values.putIfAbsent(toolId, new Registration(definition, adapter)) != null) {
                    throw new IllegalStateException("工具 ID 重复: " + toolId);
                }
            }
        }
        this.registrations = Map.copyOf(values);
    }

    /**
     * 返回全部已启用工具定义。
     *
     * @return 工具定义列表
     */
    public List<Definition> definitions() {
        return registrations.values().stream()
                .map(Registration::definition)
                .filter(value -> value.enabled() && !value.deprecation().deprecated())
                .toList();
    }

    /**
     * 按平台、能力和访问模式查询可路由的工具元数据。
     *
     * @param query 能力查询条件
     * @return 稳定排序的能力元数据
     */
    public List<CapabilityMetadata> capabilities(CapabilityQuery query) {
        CapabilityQuery effective = query == null
                ? new CapabilityQuery(null, List.of(), null, false)
                : query;
        return registrations.values().stream()
                .map(Registration::definition)
                .filter(Definition::enabled)
                .filter(value -> effective.includeDeprecated() || !value.deprecation().deprecated())
                .filter(value -> supportsPlatform(value, effective.platform()))
                .filter(value -> effective.accessMode() == null
                        || value.metadata().accessMode() == effective.accessMode())
                .filter(value -> value.metadata().capabilities().containsAll(effective.requiredCapabilities()))
                .map(this::capabilityMetadata)
                .toList();
    }

    /**
     * 将调用请求升级到注册定义版本；未知主版本和未声明迁移必须在策略执行前失败。
     *
     * @param request 原始工具请求
     * @return 当前定义版本的请求
     */
    public CallRequest prepare(CallRequest request) {
        if (request == null || request.tool() == null) {
            throw new IllegalArgumentException("工具请求或工具引用不能为空");
        }
        Registration registration = require(request.tool().toolId());
        Definition definition = registration.definition();
        VersionCompatibility compatibility = compatibility(
                request.tool().schemaVersion(), definition.schemaVersion());
        if (compatibility == VersionCompatibility.INCOMPATIBLE) {
            throw new IllegalArgumentException("工具 Schema 版本不兼容: "
                    + request.tool().schemaVersion() + " -> " + definition.schemaVersion());
        }
        if (compatibility == VersionCompatibility.EXACT) {
            return request;
        }
        JsonNode migrated = registration.adapter().migrateArguments(
                request.tool().schemaVersion(), definition, request.arguments());
        if (migrated == null) {
            throw new IllegalStateException("工具 Schema 迁移结果不能为空: " + request.tool().toolId());
        }
        return new CallRequest(
                ToolContract.SCHEMA_VERSION,
                request.identity(),
                new ToolReference(request.tool().toolId(), definition.schemaVersion()),
                migrated,
                digest(migrated),
                request.idempotencyKey(),
                request.requestedBy(),
                request.executionContext());
    }

    /**
     * 判断两个语义化工具版本是否可直接执行、需要迁移或不兼容。
     *
     * @param sourceVersion 调用版本
     * @param targetVersion 当前定义版本
     * @return 兼容性结论
     */
    public VersionCompatibility compatibility(String sourceVersion, String targetVersion) {
        SemanticVersion source = SemanticVersion.parse(sourceVersion);
        SemanticVersion target = SemanticVersion.parse(targetVersion);
        if (source.major() != target.major() || source.compareTo(target) > 0) {
            return VersionCompatibility.INCOMPATIBLE;
        }
        return source.equals(target) ? VersionCompatibility.EXACT : VersionCompatibility.MIGRATION_REQUIRED;
    }

    /**
     * 按稳定工具 ID 获取注册信息。
     *
     * @param toolId 工具 ID
     * @return 注册信息
     */
    public Registration require(String toolId) {
        Registration registration = registrations.get(toolId);
        if (registration == null) {
            throw new IllegalArgumentException("工具不存在: " + toolId);
        }
        return registration;
    }

    /**
     * 校验注册定义完整性和当前契约主版本，防止无效工具进入发现结果。
     *
     * @param definition 工具定义
     */
    private void validateDefinition(Definition definition) {
        if (definition == null || definition.identity() == null || definition.metadata() == null
                || definition.deprecation() == null || definition.inputSchema() == null
                || definition.outputSchema() == null) {
            throw new IllegalArgumentException("工具定义字段不完整");
        }
        SemanticVersion contract = SemanticVersion.parse(ToolContract.SCHEMA_VERSION);
        SemanticVersion tool = SemanticVersion.parse(definition.schemaVersion());
        if (contract.major() != tool.major()) {
            throw new IllegalArgumentException("工具定义主版本不受支持: " + definition.schemaVersion());
        }
    }

    /**
     * 判断工具定义是否覆盖查询平台；平台为空表示不限制。
     *
     * @param definition 工具定义
     * @param platform 查询平台
     * @return 支持返回 true
     */
    private boolean supportsPlatform(Definition definition, ToolContract.Platform platform) {
        return platform == null
                || definition.metadata().platforms().contains(ToolContract.Platform.PLATFORM_INDEPENDENT)
                || definition.metadata().platforms().contains(platform);
    }

    /**
     * 从工具定义生成供 Router 和协议 Adapter 使用的只读能力元数据。
     *
     * @param definition 工具定义
     * @return 能力元数据
     */
    private CapabilityMetadata capabilityMetadata(Definition definition) {
        return new CapabilityMetadata(
                definition.identity().toolId(),
                definition.identity().displayName(),
                definition.schemaVersion(),
                definition.metadata(),
                definition.deprecation());
    }

    /**
     * 计算迁移后参数摘要，使确认和幂等绑定到实际执行参数。
     *
     * @param arguments 迁移后的参数
     * @return SHA-256 十六进制摘要
     */
    private String digest(JsonNode arguments) {
        try {
            byte[] bytes = arguments.toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    /**
     * 工具定义与执行 Adapter 的内部绑定。
     *
     * @param definition 工具定义
     * @param adapter 工具 Adapter
     */
    public record Registration(Definition definition, ToolAdapter adapter) {
    }

    /**
     * 严格的三段语义化版本，仅用于工具 Schema 兼容判断。
     *
     * @param major 主版本
     * @param minor 次版本
     * @param patch 修订版本
     */
    private record SemanticVersion(int major, int minor, int patch) implements Comparable<SemanticVersion> {

        /**
         * 解析严格的 major.minor.patch 版本。
         *
         * @param value 版本文本
         * @return 语义化版本
         */
        private static SemanticVersion parse(String value) {
            if (value == null || !value.matches("\\d+\\.\\d+\\.\\d+")) {
                throw new IllegalArgumentException("工具 Schema 版本格式无效: " + value);
            }
            String[] parts = value.split("\\.");
            try {
                return new SemanticVersion(
                        Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("工具 Schema 版本数值无效: " + value, ex);
            }
        }

        /**
         * 按主、次、修订版本依次比较。
         *
         * @param other 目标版本
         * @return 比较结果
         */
        @Override
        public int compareTo(SemanticVersion other) {
            int majorResult = Integer.compare(major, other.major);
            if (majorResult != 0) {
                return majorResult;
            }
            int minorResult = Integer.compare(minor, other.minor);
            return minorResult != 0 ? minorResult : Integer.compare(patch, other.patch);
        }
    }
}
