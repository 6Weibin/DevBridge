package com.devbridge.server.ai.tool.audit;

import com.devbridge.server.ai.security.SensitiveDataMasker;
import com.devbridge.server.ai.storage.AiDataMaintenanceLock;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 结构化工具审计存储，按日追加 JSONL 且每行带独立 SHA-256 校验。
 *
 * <p>审计只保存脱敏摘要和摘要 Hash，不保存完整参数、工具输出、密钥或堆栈。</p>
 *
 * <p>by AI.Coding</p>
 */
@Service
public class ToolAuditStore {

    private static final String SCHEMA_VERSION = "1.0.0";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int MAX_QUERY_LIMIT = 1000;
    private static final int MAX_SUMMARY_LENGTH = 2048;
    private static final int RETENTION_DAYS = 90;

    private final Path root;
    private final ObjectMapper objectMapper;
    private final SensitiveDataMasker masker;
    private final AiDataMaintenanceLock maintenanceLock;
    private final Object appendLock = new Object();

    /**
     * 从集中配置初始化审计根目录。
     *
     * @param properties DevBridge 配置
     * @param objectMapper JSON 工具
     * @param masker 敏感数据脱敏器
     */
    @Autowired
    public ToolAuditStore(
            DevBridgeProperties properties,
            ObjectMapper objectMapper,
            SensitiveDataMasker masker,
            AiDataMaintenanceLock maintenanceLock) {
        this(Path.of(properties.getToolAuditRoot()), objectMapper, masker, maintenanceLock);
    }

    /** 创建兼容显式装配的审计 Store。 */
    public ToolAuditStore(
            DevBridgeProperties properties, ObjectMapper objectMapper, SensitiveDataMasker masker) {
        this(properties, objectMapper, masker, new AiDataMaintenanceLock());
    }

    /**
     * 创建测试或嵌入式审计 Store。
     *
     * @param root 审计根目录
     * @param objectMapper JSON 工具
     * @param masker 脱敏器
     */
    ToolAuditStore(Path root, ObjectMapper objectMapper, SensitiveDataMasker masker) {
        this(root, objectMapper, masker, new AiDataMaintenanceLock());
    }

    /** 初始化指定根目录和共享维护锁。 */
    private ToolAuditStore(
            Path root,
            ObjectMapper objectMapper,
            SensitiveDataMasker masker,
            AiDataMaintenanceLock maintenanceLock) {
        this.root = root.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.masker = masker;
        this.maintenanceLock = maintenanceLock;
        createRoot();
    }

    /**
     * 在策略评估前持久化请求摘要，审计不可写时阻止工具执行。
     *
     * @param request 工具请求
     */
    public void recordRequested(CallRequest request) {
        append(event(request, null, "REQUESTED", "REQUESTED", "工具调用已请求", "", 0));
    }

    /**
     * 持久化工具最终结果摘要。
     *
     * @param request 工具请求
     * @param result 工具结果
     */
    public void recordResult(CallRequest request, CallResult result) {
        String errorCode = result.diagnostics().error() == null ? "" : result.diagnostics().error().code();
        append(event(
                request, result, "COMPLETED", result.status().name(),
                result.payload().summary(), errorCode, result.timing().durationMs()));
    }

    /**
     * 持久化 Schema、平台或策略阶段拒绝摘要。
     *
     * @param request 工具请求
     * @param error 拒绝异常
     */
    public void recordRejected(CallRequest request, RuntimeException error) {
        append(event(request, null, "REJECTED", "REJECTED", error.getMessage(), error.getClass().getSimpleName(), 0));
    }

    /**
     * 按任务、工具、风险和时间查询审计记录。
     *
     * @param query 查询条件
     * @return 审计记录
     */
    public List<AuditEvent> query(AuditQuery query) {
        return maintenanceLock.read(() -> queryUnlocked(query));
    }

    /** 在维护读锁内查询审计记录。 */
    private List<AuditEvent> queryUnlocked(AuditQuery query) {
        AuditQuery effective = query == null
                ? new AuditQuery("", "", "", null, null, 100)
                : query;
        int limit = Math.min(MAX_QUERY_LIMIT, Math.max(1, effective.limit()));
        List<AuditEvent> results = new ArrayList<>();
        for (Path file : auditFiles()) {
            List<AuditEvent> daily = readFile(file, effective, limit);
            for (int index = daily.size() - 1; index >= 0 && results.size() < limit; index--) {
                results.add(daily.get(index));
            }
            if (results.size() >= limit) {
                break;
            }
        }
        return List.copyOf(results);
    }

    /**
     * 删除超过 90 天的整日审计文件。
     *
     * @return 删除文件数
     */
    public int purgeExpired() {
        return maintenanceLock.read(this::purgeExpiredUnlocked);
    }

    /** 在维护读锁内清理过期审计文件。 */
    private int purgeExpiredUnlocked() {
        int deleted = 0;
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(RETENTION_DAYS);
        for (Path file : auditFiles()) {
            LocalDate date = auditDate(file);
            try {
                if (date != null && date.isBefore(cutoff) && Files.deleteIfExists(file)) {
                    deleted++;
                }
            } catch (IOException ex) {
                throw new IllegalStateException("审计保留清理失败", ex);
            }
        }
        return deleted;
    }

    /**
     * 构造不含正文的审计事件。
     *
     * @param request 工具请求
     * @param result 工具结果，可空
     * @param phase 审计阶段
     * @param status 状态
     * @param summary 脱敏摘要
     * @param errorCode 错误码
     * @param durationMs 耗时
     * @return 审计事件
     */
    private AuditEvent event(
            CallRequest request,
            CallResult result,
            String phase,
            String status,
            String summary,
            String errorCode,
            long durationMs) {
        AuditIdentity identity = new AuditIdentity(
                request == null || request.identity() == null ? "" : safe(request.identity().taskId()),
                request == null || request.identity() == null ? "" : safe(request.identity().stepId()),
                request == null || request.identity() == null ? "" : safe(request.identity().toolCallId()),
                request == null || request.tool() == null ? "" : safe(request.tool().toolId()),
                request == null ? "" : safe(request.argumentDigest()));
        AuditDecision decision = result == null || result.riskDecision() == null
                ? new AuditDecision("", "", "")
                : new AuditDecision(
                        result.riskDecision().level().name(), result.riskDecision().action().name(),
                        safe(result.riskDecision().policyRuleId()));
        AuditOutcome outcome = new AuditOutcome(
                phase, status, limited(masker.maskText(safe(summary))), safe(errorCode), Math.max(0, durationMs));
        return new AuditEvent(identity, decision, outcome, Instant.now());
    }

    /**
     * 追加单条带校验和 JSONL 记录并强制刷新文件。
     *
     * @param event 审计事件
     */
    private void append(AuditEvent event) {
        maintenanceLock.read(() -> appendUnlocked(event));
    }

    /** 在维护读锁内追加审计记录。 */
    private void appendUnlocked(AuditEvent event) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(event);
            StoredAudit stored = new StoredAudit(SCHEMA_VERSION, event, digest(payload));
            byte[] line = (objectMapper.writeValueAsString(stored) + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (appendLock) {
                try (FileChannel channel = FileChannel.open(
                        fileFor(event.timestamp()),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    channel.write(ByteBuffer.wrap(line));
                    channel.force(false);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("结构化审计写入失败", ex);
        }
    }

    /**
     * 读取单个审计文件，损坏行跳过并继续处理后续记录。
     *
     * @param file 审计文件
     * @param query 查询条件
     * @param limit 结果上限
     * @return 当日按时间正序保留的最新匹配记录
     */
    private List<AuditEvent> readFile(Path file, AuditQuery query, int limit) {
        ArrayDeque<AuditEvent> results = new ArrayDeque<>(limit);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                AuditEvent event = parse(line);
                if (event != null && matches(event, query)) {
                    if (results.size() == limit) {
                        results.removeFirst();
                    }
                    results.addLast(event);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("审计文件读取失败", ex);
        }
        return List.copyOf(results);
    }

    /**
     * 解析并校验单行审计记录。
     *
     * @param line JSONL 行
     * @return 有效事件，损坏返回 null
     */
    private AuditEvent parse(String line) {
        try {
            StoredAudit stored = objectMapper.readValue(line, StoredAudit.class);
            byte[] payload = objectMapper.writeValueAsBytes(stored.event());
            if (!SCHEMA_VERSION.equals(stored.schemaVersion()) || !digest(payload).equals(stored.sha256())) {
                return null;
            }
            return stored.event();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 判断事件是否符合查询条件。
     *
     * @param event 审计事件
     * @param query 查询条件
     * @return 匹配返回 true
     */
    private boolean matches(AuditEvent event, AuditQuery query) {
        return matchText(query.taskId(), event.identity().taskId())
                && matchText(query.toolId(), event.identity().toolId())
                && matchText(query.riskLevel(), event.decision().riskLevel())
                && (query.start() == null || !event.timestamp().isBefore(query.start()))
                && (query.end() == null || !event.timestamp().isAfter(query.end()));
    }

    /**
     * 空查询值表示不限制。
     *
     * @param expected 查询值
     * @param actual 实际值
     * @return 匹配返回 true
     */
    private boolean matchText(String expected, String actual) {
        return expected == null || expected.isBlank() || expected.equals(actual);
    }

    /**
     * 返回按日期倒序排列的审计文件。
     *
     * @return 文件列表
     */
    private List<Path> auditFiles() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var files = Files.list(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("audit-\\d{8}\\.jsonl"))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("审计目录扫描失败", ex);
        }
    }

    /**
     * 获取事件所属按日文件。
     *
     * @param timestamp 事件时间
     * @return 文件路径
     */
    private Path fileFor(Instant timestamp) {
        LocalDate date = timestamp.atZone(ZoneOffset.UTC).toLocalDate();
        return root.resolve("audit-" + date.format(FILE_DATE) + ".jsonl");
    }

    /**
     * 从文件名解析审计日期。
     *
     * @param file 审计文件
     * @return 日期，非法返回 null
     */
    private LocalDate auditDate(Path file) {
        String name = file.getFileName().toString();
        try {
            return LocalDate.parse(name.substring(6, 14), FILE_DATE);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 计算字节摘要。
     *
     * @param bytes 字节
     * @return SHA-256
     */
    private String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    /**
     * 创建审计根目录。
     */
    private void createRoot() {
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("审计目录创建失败", ex);
        }
    }

    /**
     * 限制摘要长度。
     *
     * @param value 摘要
     * @return 有界摘要
     */
    private String limited(String value) {
        return value.length() <= MAX_SUMMARY_LENGTH ? value : value.substring(0, MAX_SUMMARY_LENGTH);
    }

    /**
     * 将空值规范为空字符串。
     *
     * @param value 文本
     * @return 非空文本
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record AuditIdentity(
            String taskId,
            String stepId,
            String toolCallId,
            String toolId,
            String argumentDigest) {
    }

    public record AuditDecision(String riskLevel, String action, String policyRuleId) {
    }

    public record AuditOutcome(
            String phase,
            String status,
            String summary,
            String errorCode,
            long durationMs) {
    }

    public record AuditEvent(
            AuditIdentity identity,
            AuditDecision decision,
            AuditOutcome outcome,
            Instant timestamp) {
    }

    public record AuditQuery(
            String taskId,
            String toolId,
            String riskLevel,
            Instant start,
            Instant end,
            int limit) {
    }

    private record StoredAudit(String schemaVersion, AuditEvent event, String sha256) {
    }
}
