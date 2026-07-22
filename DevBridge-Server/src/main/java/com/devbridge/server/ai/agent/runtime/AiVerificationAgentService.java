package com.devbridge.server.ai.agent.runtime;

import com.devbridge.server.ai.tool.gateway.ToolContract;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallIdentity;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.Caller;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionContext;
import com.devbridge.server.ai.tool.gateway.ToolContract.ToolReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * Verification Agent，只使用专业 Agent 的只读 Operation 检查关键诊断证据。
 *
 * <p>检查规则是确定性的：检查成功且返回非空 evidence 才算通过；引用缺失、工具失败或
 * 证据为空都会标记为证据不足。</p>
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AiVerificationAgentService {

    private static final int MAX_CHECKS = 8;
    private static final Map<String, Set<String>> READ_OPERATIONS = Map.of(
            AiAgentRegistry.DEVICE_AGENT, Set.of("LIST", "DETAIL", "HEALTH", "DIAGNOSE"),
            AiAgentRegistry.DOMAIN_LOG_AGENT, Set.of("READ", "STATUS"),
            AiAgentRegistry.APP_AGENT, Set.of("LIST", "DETAIL", "PERMISSIONS"));

    private final AiSpecialistAgentService specialistService;
    private final ObjectMapper objectMapper;

    /** 注入专业 Agent 服务和 JSON 工具。 */
    public AiVerificationAgentService(
            AiSpecialistAgentService specialistService,
            ObjectMapper objectMapper) {
        this.specialistService = specialistService;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行有界只读检查并验证结论引用。
     *
     * @param parent Verification Agent 父请求
     * @return 验证状态、检查证据和证据不足原因
     */
    public JsonNode verify(CallRequest parent) {
        JsonNode checks = requireArray(parent.arguments(), "checks", 1, MAX_CHECKS);
        JsonNode claims = requireArray(parent.arguments(), "claims", 1, MAX_CHECKS);
        ArrayNode results = objectMapper.createArrayNode();
        Set<String> passed = new HashSet<>();
        Set<String> known = new HashSet<>();
        for (int index = 0; index < checks.size(); index++) {
            ObjectNode result = executeCheck(parent, checks.get(index), index);
            results.add(result);
            known.add(result.path("checkId").asText());
            if (result.path("passed").asBoolean(false)) {
                passed.add(result.path("checkId").asText());
            }
        }
        ArrayNode claimResults = verifyClaims(claims, known, passed);
        String status = verificationStatus(claimResults);
        ObjectNode output = objectMapper.createObjectNode()
                .put("agentId", AiAgentRegistry.VERIFICATION_AGENT)
                .put("status", status)
                .put("summary", summary(status, claimResults.size()));
        output.set("checks", results);
        output.set("claims", claimResults);
        return output;
    }

    /** 执行单个只读专业 Agent 检查。 */
    private ObjectNode executeCheck(CallRequest parent, JsonNode check, int index) {
        String checkId = text(check, "checkId", "check-" + (index + 1));
        String agentId = text(check, "agentId", "");
        String operation = text(check, "operation", "").toUpperCase(Locale.ROOT);
        JsonNode arguments = check.path("arguments");
        if (!READ_OPERATIONS.getOrDefault(agentId, Set.of()).contains(operation)
                || !arguments.isObject()) {
            throw new IllegalArgumentException("Verification Agent 只允许已声明的只读检查: " + checkId);
        }
        ObjectNode specialistArguments = objectMapper.createObjectNode().put("operation", operation);
        specialistArguments.set("arguments", arguments.deepCopy());
        try {
            JsonNode output = specialistService.execute(childRequest(parent, checkId, agentId, specialistArguments), agentId);
            boolean passed = "SUCCEEDED".equals(output.path("status").asText())
                    && output.path("evidence").size() > 0;
            return objectMapper.createObjectNode()
                    .put("checkId", checkId).put("agentId", agentId).put("operation", operation)
                    .put("passed", passed).put("reason", passed ? "只读证据检查通过" : "工具成功但未返回有效证据")
                    .set("evidence", output.path("evidence").deepCopy());
        } catch (RuntimeException ex) {
            return objectMapper.createObjectNode()
                    .put("checkId", checkId).put("agentId", agentId).put("operation", operation)
                    .put("passed", false).put("reason", "只读证据检查失败: " + ex.getClass().getSimpleName())
                    .set("evidence", objectMapper.createObjectNode());
        }
    }

    /** 按 claim 引用的检查 ID 计算每个结论状态。 */
    private ArrayNode verifyClaims(JsonNode claims, Set<String> known, Set<String> passed) {
        ArrayNode output = objectMapper.createArrayNode();
        for (int index = 0; index < claims.size(); index++) {
            JsonNode claim = claims.get(index);
            String claimId = text(claim, "claimId", "claim-" + (index + 1));
            JsonNode refs = claim.path("evidenceRefs");
            boolean hasRefs = refs.isArray() && !refs.isEmpty();
            boolean allKnown = hasRefs;
            boolean allPassed = hasRefs;
            if (hasRefs) {
                for (JsonNode ref : refs) {
                    allKnown &= known.contains(ref.asText());
                    allPassed &= passed.contains(ref.asText());
                }
            }
            String status = allKnown && allPassed ? "VERIFIED" : allKnown ? "INSUFFICIENT" : "MISSING_EVIDENCE";
            output.add(objectMapper.createObjectNode()
                    .put("claimId", claimId).put("claim", text(claim, "claim", ""))
                    .put("status", status).put("reason", "VERIFIED".equals(status)
                            ? "全部引用证据已通过只读检查" : "引用证据缺失或检查未通过"));
        }
        return output;
    }

    /** 根据结论检查结果计算整体状态。 */
    private String verificationStatus(ArrayNode claims) {
        int verified = 0;
        for (JsonNode claim : claims) {
            verified += "VERIFIED".equals(claim.path("status").asText()) ? 1 : 0;
        }
        return verified == claims.size() ? "VERIFIED" : verified > 0 ? "PARTIAL" : "INSUFFICIENT";
    }

    /** 生成不夸大验证结果的摘要。 */
    private String summary(String status, int claimCount) {
        return switch (status) {
            case "VERIFIED" -> "关键结论已通过 " + claimCount + " 项证据验证";
            case "PARTIAL" -> "部分关键结论已验证，其余证据不足";
            default -> "关键结论证据不足，不能视为已验证事实";
        };
    }

    /** 构造继承父任务身份和确认根绑定的专业 Agent 请求。 */
    private CallRequest childRequest(
            CallRequest parent, String checkId, String agentId, JsonNode arguments) {
        String suffix = digest(parent.identity().toolCallId() + ":" + checkId).substring(0, 12);
        ExecutionContext context = parent.executionContext();
        return new CallRequest(
                ToolContract.SCHEMA_VERSION,
                new CallIdentity(
                        parent.identity().conversationId(), parent.identity().taskId(), parent.identity().turnId(),
                        "verification-step-" + suffix, "verification-call-" + suffix, Instant.now()),
                new ToolReference("agent." + agentId.replace("-agent", "") + ".execute", ToolContract.SCHEMA_VERSION),
                arguments, digest(arguments.toString()), "verification:" + parent.identity().toolCallId() + ":" + checkId,
                Caller.WORKFLOW,
                new ExecutionContext(
                        context.platform(), context.deviceId(), context.workspace(), context.confirmationId(),
                        List.of("agent:" + AiAgentRegistry.VERIFICATION_AGENT), context.workflowAuthorization()));
    }

    /** 读取并校验有界数组字段。 */
    private JsonNode requireArray(JsonNode parent, String field, int minimum, int maximum) {
        JsonNode value = parent == null ? null : parent.path(field);
        if (value == null || !value.isArray() || value.size() < minimum || value.size() > maximum) {
            throw new IllegalArgumentException(field + " 数量必须在 " + minimum + " 到 " + maximum + " 之间");
        }
        return value;
    }

    /** 读取文本字段并提供默认值。 */
    private String text(JsonNode value, String field, String fallback) {
        String text = value == null ? "" : value.path(field).asText("").trim();
        return text.isEmpty() ? fallback : text;
    }

    /** 计算稳定参数摘要。 */
    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }
}
