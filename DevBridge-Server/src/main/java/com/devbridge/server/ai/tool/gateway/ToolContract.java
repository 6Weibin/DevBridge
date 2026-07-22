package com.devbridge.server.ai.tool.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * 中立工具契约，集中定义工具元数据、调用请求、策略决策和执行结果。
 *
 * <p>领域模型不依赖 ADB、Local Shell、Spring AI、MCP SDK 或前端消息类型。</p>
 *
 * <p>by AI.Coding</p>
 */
public final class ToolContract {

    public static final String SCHEMA_VERSION = "1.0.0";

    private ToolContract() {
    }

    public enum Platform {
        ANDROID, IOS, HARMONY_OS, MACOS, WINDOWS, LINUX, WEB, PLATFORM_INDEPENDENT
    }

    public enum SourceKind {
        DOMAIN_SERVICE, LOCAL_ADAPTER, STANDARD_MCP, REMOTE_API, BUILT_IN
    }

    public enum AccessMode {
        READ, WRITE, CONTROL, MIXED
    }

    public enum IdempotencyMode {
        NATURAL, KEYED, VERIFY_REQUIRED, NON_IDEMPOTENT
    }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH, UNCLASSIFIED
    }

    public enum RiskAction {
        ALLOW, CONFIRM, BLOCK
    }

    public enum CallStatus {
        REQUESTED, WAITING_CONFIRMATION, RUNNING, SUCCEEDED, FAILED, CANCELED, BLOCKED, TIMED_OUT, UNKNOWN
    }

    public enum Caller {
        AGENT, WORKFLOW, USER, SYSTEM
    }

    public enum ErrorCategory {
        VALIDATION, POLICY, RESOURCE, EXECUTION, TIMEOUT, CANCELED, PROTOCOL, UNKNOWN
    }

    public enum VersionCompatibility {
        EXACT, MIGRATION_REQUIRED, INCOMPATIBLE
    }

    public record Source(SourceKind kind, String provider, String adapterVersion, String serverId, String transport) {
    }

    public record Idempotency(IdempotencyMode mode, boolean keyRequired, String verificationCapability) {
    }

    public record RiskProfile(RiskLevel minimumLevel, boolean canEscalate) {
    }

    public record ExecutionProfile(
            long defaultTimeoutMs,
            long maxTimeoutMs,
            long maxInlineOutputBytes,
            boolean supportsCancellation,
            boolean supportsStreaming,
            List<String> resourceScopes) {

        /**
         * 固化资源范围集合。
         */
        public ExecutionProfile {
            resourceScopes = resourceScopes == null ? List.of() : List.copyOf(resourceScopes);
        }
    }

    public record Metadata(
            Source source,
            List<String> capabilities,
            List<Platform> platforms,
            AccessMode accessMode,
            Idempotency idempotency,
            RiskProfile riskProfile,
            ExecutionProfile executionProfile) {

        /**
         * 固化能力和平台集合。
         */
        public Metadata {
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            platforms = platforms == null ? List.of() : List.copyOf(platforms);
        }
    }

    public record Identity(String toolId, String displayName, String description) {
    }

    public record Deprecation(boolean deprecated, String replacementToolId) {
    }

    public record Definition(
            String schemaVersion,
            Identity identity,
            Metadata metadata,
            JsonNode inputSchema,
            JsonNode outputSchema,
            boolean enabled,
            Deprecation deprecation) {
    }

    public record CapabilityQuery(
            Platform platform,
            List<String> requiredCapabilities,
            AccessMode accessMode,
            boolean includeDeprecated) {

        /**
         * 固化查询能力集合，避免调用方修改注册表查询条件。
         */
        public CapabilityQuery {
            requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
        }
    }

    public record CapabilityMetadata(
            String toolId,
            String displayName,
            String schemaVersion,
            Metadata metadata,
            Deprecation deprecation) {
    }

    public record CallIdentity(
            String conversationId,
            String taskId,
            String turnId,
            String stepId,
            String toolCallId,
            Instant createdAt) {
    }

    public record ToolReference(String toolId, String schemaVersion) {
    }

    public record ExecutionContext(
            Platform platform,
            String deviceId,
            String workspace,
            String confirmationId,
            List<String> resourceHints,
            WorkflowAuthorization workflowAuthorization) {

        /**
         * 固化资源提示集合。
         */
        public ExecutionContext {
            resourceHints = resourceHints == null ? List.of() : List.copyOf(resourceHints);
        }

        /**
         * 兼容没有父工作流授权的工具调用。
         *
         * @param platform 执行平台
         * @param deviceId 设备标识
         * @param workspace 工作目录
         * @param confirmationId 确认标识
         * @param resourceHints 资源提示
         */
        public ExecutionContext(
                Platform platform,
                String deviceId,
                String workspace,
                String confirmationId,
                List<String> resourceHints) {
            this(platform, deviceId, workspace, confirmationId, resourceHints, null);
        }
    }

    /**
     * 服务端固定工作流对子步骤的父确认绑定，外部模型不能构造 Caller.WORKFLOW。
     *
     * <p>by AI.Coding</p>
     */
    public record WorkflowAuthorization(
            String parentToolId,
            String parentStepId,
            String parentToolCallId,
            String parentArgumentDigest) {
    }

    public record CallRequest(
            String schemaVersion,
            CallIdentity identity,
            ToolReference tool,
            JsonNode arguments,
            String argumentDigest,
            String idempotencyKey,
            Caller requestedBy,
            ExecutionContext executionContext) {
    }

    public record RiskDecision(
            RiskLevel level,
            RiskAction action,
            String policyRuleId,
            String reasonCode,
            String reasonSummary,
            String confirmationId,
            Instant evaluatedAt) {
    }

    public record Timing(Instant startedAt, Instant finishedAt, long durationMs) {
    }

    public record Error(
            String code,
            ErrorCategory category,
            String message,
            String detail,
            boolean retryable,
            boolean resultUncertain) {
    }

    public record Exit(Integer code, boolean timedOut) {
    }

    public record Metrics(long inputBytes, long outputBytes, long discardedBytes, int retryCount) {
    }

    public record SideEffect(boolean produced, boolean verified, boolean compensatable) {
    }

    public record ArtifactIntegrity(long sizeBytes, String sha256, String compression) {
    }

    public record ArtifactRetention(String sensitivity, Instant retentionUntil) {
    }

    public record ArtifactReference(
            String artifactId,
            String kind,
            String mediaType,
            ArtifactIntegrity integrity,
            ArtifactRetention retention,
            boolean rangeReadable,
            boolean redacted) {
    }

    public record ResultPayload(JsonNode output, String summary, List<ArtifactReference> artifacts) {

        /**
         * 固化 Artifact 引用集合。
         */
        public ResultPayload {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }
    }

    public record Diagnostics(Error error, Exit exit, Metrics metrics, SideEffect sideEffect) {
    }

    public record CallResult(
            String schemaVersion,
            ToolReference tool,
            String toolCallId,
            CallStatus status,
            RiskDecision riskDecision,
            Timing timing,
            ResultPayload payload,
            Diagnostics diagnostics) {
    }
}
