package com.devbridge.server.ai.conversation;

import com.devbridge.server.ai.config.AiConfigCrypto;
import com.devbridge.server.ai.storage.StorageManager;
import com.devbridge.server.ai.storage.AiDataMaintenanceLock;
import com.devbridge.server.ai.storage.StorageManager.StorageCategory;
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

/**
 * 设备与故障长期记忆，使用独立加密小文件保存设备快照、故障证据和验证结果。
 *
 * <p>内存索引只保存有界结构化记录，不保存日志全文和工具原始输出。</p>
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AiDeviceIncidentMemory {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiDeviceIncidentMemory.class);
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final int MAX_RESULTS = 200;
    private static final int MAX_EVIDENCE_ITEMS = 20;
    private static final int MAX_TEXT_LENGTH = 2_000;

    private final Path cryptoRoot;
    private final Path memoryRoot;
    private final ObjectMapper objectMapper;
    private final AiConfigCrypto crypto;
    private final StorageManager storageManager;
    private final AiDataMaintenanceLock maintenanceLock;
    private final Map<String, DeviceSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, IncidentRecord> incidents = new ConcurrentHashMap<>();

    /**
     * 初始化本地 Memory 目录并恢复可读记录。
     *
     * @param properties 应用配置
     * @param objectMapper JSON 工具
     * @param crypto 本地加密工具
     * @param storageManager 统一磁盘配额
     */
    @Autowired
    public AiDeviceIncidentMemory(
            DevBridgeProperties properties,
            ObjectMapper objectMapper,
            AiConfigCrypto crypto,
            StorageManager storageManager,
            AiDataMaintenanceLock maintenanceLock) {
        this.cryptoRoot = Path.of(properties.getAiConfigRoot()).toAbsolutePath().normalize();
        this.memoryRoot = cryptoRoot.resolve("memory");
        this.objectMapper = objectMapper;
        this.crypto = crypto;
        this.storageManager = storageManager;
        this.maintenanceLock = maintenanceLock;
        initialize();
    }

    /** 创建兼容测试和显式装配的 Memory。 */
    public AiDeviceIncidentMemory(
            DevBridgeProperties properties,
            ObjectMapper objectMapper,
            AiConfigCrypto crypto,
            StorageManager storageManager) {
        this(properties, objectMapper, crypto, storageManager, new AiDataMaintenanceLock());
    }

    /**
     * 保存设备结构化快照。
     *
     * @param request 快照请求
     * @return 已保存快照
     */
    public DeviceSnapshot recordSnapshot(DeviceSnapshotRequest request) {
        return maintenanceLock.read(() -> recordSnapshotUnlocked(request));
    }

    /** 在维护读锁内保存设备快照。 */
    private DeviceSnapshot recordSnapshotUnlocked(DeviceSnapshotRequest request) {
        requireDevice(request == null ? "" : request.deviceId());
        Instant capturedAt = request.capturedAt() == null ? Instant.now() : request.capturedAt();
        DeviceSnapshot snapshot = new DeviceSnapshot(
                "device-" + UUID.randomUUID(), request.deviceId().trim(), safe(request.platform()),
                safe(request.model()), safe(request.osVersion()), safeNode(request.metrics()), capturedAt);
        write("device", snapshot.id(), snapshot);
        snapshots.put(snapshot.id(), snapshot);
        return snapshot;
    }

    /**
     * 保存有界故障案例和验证结果。
     *
     * @param request 故障请求
     * @return 已保存故障
     */
    public IncidentRecord recordIncident(IncidentRequest request) {
        return maintenanceLock.read(() -> recordIncidentUnlocked(request));
    }

    /** 在维护读锁内保存故障记录。 */
    private IncidentRecord recordIncidentUnlocked(IncidentRequest request) {
        requireDevice(request == null ? "" : request.deviceId());
        IncidentDetails requested = request.details() == null
                ? new IncidentDetails("", "", List.of(), "", "UNKNOWN", List.of())
                : request.details();
        IncidentDetails details = new IncidentDetails(
                limit(requested.signature()), limit(requested.summary()), bounded(requested.evidence()),
                limit(requested.resolution()), normalizeStatus(requested.verificationStatus()), bounded(requested.tags()));
        IncidentRecord incident = new IncidentRecord(
                "incident-" + UUID.randomUUID(), request.deviceId().trim(), safe(request.platform()),
                safe(request.osVersion()), details, Instant.now(), request.verifiedAt());
        write("incident", incident.id(), incident);
        incidents.put(incident.id(), incident);
        return incident;
    }

    /**
     * 按设备、系统版本和故障特征查询历史案例。
     *
     * @param query 查询条件
     * @return 稳定倒序故障列表
     */
    public List<IncidentRecord> searchIncidents(MemoryQuery query) {
        MemoryQuery effective = query == null ? new MemoryQuery("", "", "", 20) : query;
        int limit = Math.min(MAX_RESULTS, Math.max(1, effective.limit()));
        String signature = safe(effective.signature()).toLowerCase(Locale.ROOT);
        return incidents.values().stream()
                .filter(value -> matches(value.deviceId(), effective.deviceId()))
                .filter(value -> matches(value.osVersion(), effective.osVersion()))
                .filter(value -> signature.isBlank()
                        || searchable(value).toLowerCase(Locale.ROOT).contains(signature))
                .sorted(Comparator.comparing(IncidentRecord::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * 按设备查询最新结构化快照。
     *
     * @param deviceId 设备标识，可空
     * @param limit 返回数量
     * @return 快照列表
     */
    public List<DeviceSnapshot> snapshots(String deviceId, int limit) {
        int actual = Math.min(MAX_RESULTS, Math.max(1, limit));
        return snapshots.values().stream()
                .filter(value -> matches(value.deviceId(), deviceId))
                .sorted(Comparator.comparing(DeviceSnapshot::capturedAt).reversed())
                .limit(actual)
                .toList();
    }

    /**
     * 删除指定 Memory 记录。
     *
     * @param id 记录标识
     * @return 已删除返回 true
     */
    public boolean delete(String id) {
        return maintenanceLock.read(() -> deleteUnlocked(id));
    }

    /** 在维护读锁内删除 Memory 文件和索引。 */
    private boolean deleteUnlocked(String id) {
        if (!StringUtils.hasText(id) || !id.matches("[A-Za-z0-9-]{1,128}")) {
            return false;
        }
        String type = snapshots.containsKey(id) ? "device" : incidents.containsKey(id) ? "incident" : "";
        if (type.isBlank()) {
            return false;
        }
        try {
            Files.deleteIfExists(path(type, id));
            if ("device".equals(type)) {
                snapshots.remove(id);
            } else {
                incidents.remove(id);
            }
            return true;
        } catch (IOException ex) {
            throw new IllegalStateException("Memory 记录删除失败", ex);
        }
    }

    /**
     * 初始化目录并恢复两类 Memory 文件。
     */
    private void initialize() {
        try {
            Files.createDirectories(memoryRoot.resolve("device"));
            Files.createDirectories(memoryRoot.resolve("incident"));
            loadDirectory("device", DeviceSnapshot.class, snapshots);
            loadDirectory("incident", IncidentRecord.class, incidents);
        } catch (IOException ex) {
            throw new IllegalStateException("AI Memory 初始化失败", ex);
        }
    }

    /** 从恢复后的本地文件重建 Memory 内存索引。 */
    public void recoverIndex() {
        maintenanceLock.read(() -> {
            snapshots.clear();
            incidents.clear();
            initialize();
        });
    }

    /**
     * 扫描单类记录；损坏文件隔离为警告，不阻塞其他记忆加载。
     */
    private <T> void loadDirectory(String type, Class<T> targetType, Map<String, T> target) throws IOException {
        try (var files = Files.list(memoryRoot.resolve(type))) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".enc")).toList()) {
                try {
                    T value = read(file, type, targetType);
                    String id = type.equals("device")
                            ? ((DeviceSnapshot) value).id()
                            : ((IncidentRecord) value).id();
                    target.put(id, value);
                } catch (RuntimeException ex) {
                    LOGGER.warn("AI Memory 文件读取失败，已跳过: {}", file.getFileName());
                }
            }
        }
    }

    /**
     * 在统一配额许可内加密压缩并原子写入记录。
     */
    private void write(String type, String id, Object payload) {
        byte[] bytes = encode(new StoredMemory(SCHEMA_VERSION, type, objectMapper.valueToTree(payload)));
        try (StorageManager.WritePermit permit = storageManager.openWrite(StorageCategory.MEMORY)) {
            permit.reserve(bytes.length);
            writeAtomic(path(type, id), bytes);
            permit.commit();
        }
    }

    /**
     * 解密单个记录并校验类型载荷。
     */
    private <T> T read(Path file, String expectedType, Class<T> targetType) {
        try {
            String encrypted = Files.readString(file, StandardCharsets.UTF_8);
            byte[] compressed = Base64.getDecoder().decode(crypto.decrypt(cryptoRoot, encrypted));
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                StoredMemory stored = objectMapper.readValue(gzip, StoredMemory.class);
                if (!SCHEMA_VERSION.equals(stored.schemaVersion()) || !expectedType.equals(stored.type())) {
                    throw new IllegalStateException("AI Memory Schema 或类型不兼容");
                }
                return objectMapper.treeToValue(stored.payload(), targetType);
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("AI Memory 读取失败", ex);
        }
    }

    /**
     * 把记录编码为 AES-GCM 保护内容。
     */
    private byte[] encode(StoredMemory stored) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
                gzip.write(objectMapper.writeValueAsBytes(stored));
            }
            String compressed = Base64.getEncoder().encodeToString(output.toByteArray());
            return crypto.encrypt(cryptoRoot, compressed).getBytes(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("AI Memory 编码失败", ex);
        }
    }

    /**
     * 使用同目录临时文件强制落盘后原子替换。
     */
    private void writeAtomic(Path target, byte[] bytes) {
        Path temporary = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            moveAtomic(temporary, target);
        } catch (IOException ex) {
            throw new IllegalStateException("AI Memory 写入失败", ex);
        } finally {
            tryDelete(temporary);
        }
    }

    /** 原子移动不受支持时回退为同文件系统替换。 */
    private void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 安静清理失败写入的临时文件。 */
    private void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件不会进入索引，后续磁盘治理可安全清理。
        }
    }

    /** 生成白名单记录路径。 */
    private Path path(String type, String id) {
        return memoryRoot.resolve(type).resolve(id + ".enc");
    }

    /** 校验设备标识。 */
    private void requireDevice(String deviceId) {
        if (!StringUtils.hasText(deviceId) || deviceId.length() > 256) {
            throw new IllegalArgumentException("设备标识不能为空且不能超过 256 个字符");
        }
    }

    /** 判断可选查询字段。 */
    private boolean matches(String value, String expected) {
        return !StringUtils.hasText(expected) || safe(value).equalsIgnoreCase(expected.trim());
    }

    /** 聚合故障可搜索字段。 */
    private String searchable(IncidentRecord incident) {
        return incident.details().signature() + " " + incident.details().summary()
                + " " + String.join(" ", incident.details().tags());
    }

    /** 限制列表数量和单项长度。 */
    private List<String> bounded(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                result.add(limit(value));
            }
            if (result.size() >= MAX_EVIDENCE_ITEMS) {
                break;
            }
        }
        return List.copyOf(result);
    }

    /** 限制单条 Memory 文本长度。 */
    private String limit(String value) {
        String text = safe(value).trim();
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH);
    }

    /** 规范化验证状态。 */
    private String normalizeStatus(String value) {
        String status = safe(value).trim().toUpperCase(Locale.ROOT);
        return List.of("VERIFIED", "UNVERIFIED", "FAILED", "UNKNOWN").contains(status)
                ? status : "UNKNOWN";
    }

    /** 返回非空文本。 */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    /** 返回不可变 JSON 副本。 */
    private JsonNode safeNode(JsonNode value) {
        return value == null ? objectMapper.createObjectNode() : value.deepCopy();
    }

    /** 磁盘 Memory 包装。by AI.Coding */
    private record StoredMemory(String schemaVersion, String type, JsonNode payload) {
    }

    /** 设备快照写入请求。by AI.Coding */
    public record DeviceSnapshotRequest(
            String deviceId,
            String platform,
            String model,
            String osVersion,
            JsonNode metrics,
            Instant capturedAt) {
    }

    /** 设备结构化快照。by AI.Coding */
    public record DeviceSnapshot(
            String id,
            String deviceId,
            String platform,
            String model,
            String osVersion,
            JsonNode metrics,
            Instant capturedAt) {

        /** 固化指标 JSON。 */
        public DeviceSnapshot {
            metrics = metrics == null ? com.fasterxml.jackson.databind.node.NullNode.instance : metrics.deepCopy();
        }
    }

    /** 故障记录写入请求。by AI.Coding */
    public record IncidentRequest(
            String deviceId,
            String platform,
            String osVersion,
            IncidentDetails details,
            Instant verifiedAt) {
    }

    /** 故障详情。by AI.Coding */
    public record IncidentDetails(
            String signature,
            String summary,
            List<String> evidence,
            String resolution,
            String verificationStatus,
            List<String> tags) {

        /** 固化证据和标签。 */
        public IncidentDetails {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    /** 可检索故障案例。by AI.Coding */
    public record IncidentRecord(
            String id,
            String deviceId,
            String platform,
            String osVersion,
            IncidentDetails details,
            Instant createdAt,
            Instant verifiedAt) {
    }

    /** Memory 查询条件。by AI.Coding */
    public record MemoryQuery(String deviceId, String osVersion, String signature, int limit) {
    }
}
