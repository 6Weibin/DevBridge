package com.devbridge.server.ai.storage;

import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 统一存储管理器，统计分类占用、执行阈值策略并保护活跃任务数据。
 *
 * <p>80% 只预警，95% 仅清理过期或临时文件，100% 拒绝新写入；Agent Task 不自动删除。</p>
 *
 * <p>by AI.Coding</p>
 */
@Service
public class StorageManager {

    private static final double WARNING_THRESHOLD = 0.80D;
    private static final double CRITICAL_THRESHOLD = 0.95D;
    private static final Duration TEMP_RETENTION = Duration.ofHours(24);
    private static final Duration LOG_RETENTION = Duration.ofDays(30);

    private final Map<StorageCategory, Path> roots;
    private final long quotaBytes;
    private final ObjectMapper objectMapper;
    private final AiDataMaintenanceLock maintenanceLock;
    private long reservedBytes;
    private long observedBytes;

    /**
     * 从集中配置构建分类根目录。
     *
     * @param properties DevBridge 配置
     * @param objectMapper JSON 工具
     */
    @Autowired
    public StorageManager(
            DevBridgeProperties properties,
            ObjectMapper objectMapper,
            AiDataMaintenanceLock maintenanceLock) {
        this(roots(properties), properties.getStorageQuotaBytes(), objectMapper, maintenanceLock);
    }

    /** 创建兼容测试和显式装配的存储管理器。 */
    public StorageManager(DevBridgeProperties properties, ObjectMapper objectMapper) {
        this(roots(properties), properties.getStorageQuotaBytes(), objectMapper, new AiDataMaintenanceLock());
    }

    /**
     * 创建测试或嵌入式 Storage Manager。
     *
     * @param roots 分类根目录
     * @param quotaBytes 总配额
     * @param objectMapper JSON 工具
     */
    public StorageManager(Map<StorageCategory, Path> roots, long quotaBytes, ObjectMapper objectMapper) {
        this(roots, quotaBytes, objectMapper, new AiDataMaintenanceLock());
    }

    /** 创建使用指定维护锁的存储管理器。 */
    private StorageManager(
            Map<StorageCategory, Path> roots,
            long quotaBytes,
            ObjectMapper objectMapper,
            AiDataMaintenanceLock maintenanceLock) {
        if (quotaBytes <= 0) {
            throw new IllegalArgumentException("存储配额必须大于 0");
        }
        EnumMap<StorageCategory, Path> normalized = new EnumMap<>(StorageCategory.class);
        roots.forEach((category, path) -> normalized.put(category, path.toAbsolutePath().normalize()));
        this.roots = Map.copyOf(normalized);
        this.quotaBytes = quotaBytes;
        this.objectMapper = objectMapper;
        this.maintenanceLock = maintenanceLock;
    }

    /**
     * 计算当前分类和总占用快照。
     *
     * @return 存储快照
     */
    public StorageSnapshot snapshot() {
        return maintenanceLock.read(this::snapshotUnlocked);
    }

    /** 在维护读锁内计算磁盘占用快照。 */
    private StorageSnapshot snapshotUnlocked() {
        List<CategoryUsage> categories = new ArrayList<>();
        long totalBytes = 0;
        long totalFiles = 0;
        for (StorageCategory category : StorageCategory.values()) {
            Usage usage = usage(category, roots.get(category));
            categories.add(new CategoryUsage(category, usage.bytes(), usage.files(), roots.get(category) != null));
            totalBytes += usage.bytes();
            totalFiles += usage.files();
        }
        double ratio = quotaBytes == 0 ? 1D : (double) totalBytes / quotaBytes;
        return new StorageSnapshot(totalBytes, totalFiles, quotaBytes, level(ratio), categories, Instant.now());
    }

    /**
     * 为流式写入创建配额许可，写入方必须按实际输入持续预留并最终提交。
     *
     * @param category 存储分类
     * @return 写入许可
     */
    public synchronized WritePermit openWrite(StorageCategory category) {
        StorageSnapshot current = snapshot();
        observedBytes = current.totalBytes();
        if (current.level() == StorageLevel.FULL || current.totalBytes() + reservedBytes >= quotaBytes) {
            throw new IllegalStateException("存储配额已用尽，拒绝新写入");
        }
        return new WritePermit(category);
    }

    /**
     * 达到 95% 后清理过期 Artifact、下载临时文件和历史日志，不删除 Agent Task。
     *
     * @param protectedPaths 当前任务或活跃会话保护路径
     * @return 清理结果
     */
    public CleanupResult cleanup(Set<Path> protectedPaths) {
        return maintenanceLock.read(() -> cleanupUnlocked(protectedPaths));
    }

    /** 在维护读锁内执行过期文件清理。 */
    private CleanupResult cleanupUnlocked(Set<Path> protectedPaths) {
        StorageSnapshot before = snapshot();
        if (before.level().ordinal() < StorageLevel.CRITICAL.ordinal()) {
            return new CleanupResult(before, before, 0, 0);
        }
        Set<Path> protectedValues = normalizeProtected(protectedPaths);
        CleanupCounter counter = new CleanupCounter();
        cleanupArtifacts(protectedValues, counter);
        cleanupByAge(StorageCategory.DOWNLOADS, TEMP_RETENTION, protectedValues, counter);
        cleanupByAge(StorageCategory.LOGS, LOG_RETENTION, protectedValues, counter);
        StorageSnapshot after = snapshot();
        return new CleanupResult(before, after, counter.deletedFiles, counter.deletedBytes);
    }

    /**
     * 预留流式写入字节，考虑并发许可，避免多个大写入共同突破配额。
     *
     * @param permit 写入许可
     * @param bytes 新增字节
     */
    private synchronized void reserve(WritePermit permit, long bytes) {
        if (permit.closed || bytes < 0) {
            throw new IllegalStateException("存储写入许可无效");
        }
        if (observedBytes + reservedBytes + bytes > quotaBytes) {
            throw new IllegalStateException("写入将超过存储配额");
        }
        reservedBytes += bytes;
        permit.reserved += bytes;
    }

    /**
     * 释放许可预留；提交和回滚都会重新以磁盘事实为准。
     *
     * @param permit 写入许可
     */
    private synchronized void release(WritePermit permit, boolean committed) {
        if (permit.closed) {
            return;
        }
        reservedBytes = Math.max(0, reservedBytes - permit.reserved);
        if (committed) {
            observedBytes += permit.reserved;
        }
        permit.closed = true;
    }

    /**
     * 清理已过保留期的 Artifact 和遗留临时目录。
     *
     * @param protectedPaths 保护路径
     * @param counter 清理计数
     */
    private void cleanupArtifacts(Set<Path> protectedPaths, CleanupCounter counter) {
        Path root = roots.get(StorageCategory.ARTIFACTS);
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (var children = Files.list(root)) {
            children.forEach(path -> {
                if (isProtected(path, protectedPaths)) {
                    return;
                }
                if (path.getFileName().toString().startsWith(".tmp-") && olderThan(path, TEMP_RETENTION)) {
                    deleteTree(path, counter);
                } else if (Files.isDirectory(path) && artifactExpired(path)) {
                    deleteTree(path, counter);
                }
            });
        } catch (IOException ex) {
            throw new IllegalStateException("Artifact 清理扫描失败", ex);
        }
    }

    /**
     * 按文件最后修改时间清理指定分类，Agent 和配置分类不会调用此方法。
     *
     * @param category 分类
     * @param retention 保留时间
     * @param protectedPaths 保护路径
     * @param counter 清理计数
     */
    private void cleanupByAge(
            StorageCategory category,
            Duration retention,
            Set<Path> protectedPaths,
            CleanupCounter counter) {
        Path root = roots.get(category);
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !isProtected(path, protectedPaths))
                    .filter(path -> olderThan(path, retention))
                    .forEach(path -> deleteFile(path, counter));
        } catch (IOException ex) {
            throw new IllegalStateException("存储分类清理失败: " + category, ex);
        }
    }

    /**
     * 读取 Artifact 元数据中的保留截止时间。
     *
     * @param directory Artifact 目录
     * @return 已过期返回 true
     */
    private boolean artifactExpired(Path directory) {
        Path metadata = directory.resolve("metadata.json");
        if (!Files.isRegularFile(metadata)) {
            return olderThan(directory, TEMP_RETENTION);
        }
        try {
            JsonNode root = objectMapper.readTree(metadata.toFile());
            String value = root.path("policy").path("retentionUntil").asText("");
            return !value.isBlank() && Instant.now().isAfter(Instant.parse(value));
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 统计目录普通文件数和实际磁盘字节。
     *
     * @param root 根目录
     * @return 使用量
     */
    private Usage usage(StorageCategory category, Path root) {
        if (root == null || !Files.exists(root)) {
            return new Usage(0, 0);
        }
        long bytes = 0;
        long files = 0;
        try (var paths = category == StorageCategory.CONFIG ? Files.list(root) : Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                bytes += Files.size(path);
                files++;
            }
            return new Usage(bytes, files);
        } catch (IOException ex) {
            throw new IllegalStateException("存储占用统计失败: " + root, ex);
        }
    }

    /**
     * 删除目录树并累计普通文件占用。
     *
     * @param root 删除根
     * @param counter 计数器
     */
    private void deleteTree(Path root, CleanupCounter counter) {
        try (var paths = Files.walk(root)) {
            List<Path> values = paths.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path path : values) {
                if (Files.isRegularFile(path)) {
                    counter.deletedBytes += Files.size(path);
                    counter.deletedFiles++;
                }
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("存储目录清理失败: " + root, ex);
        }
    }

    /**
     * 删除单个文件并累计占用。
     *
     * @param path 文件
     * @param counter 计数器
     */
    private void deleteFile(Path path, CleanupCounter counter) {
        try {
            long size = Files.size(path);
            if (Files.deleteIfExists(path)) {
                counter.deletedBytes += size;
                counter.deletedFiles++;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("存储文件清理失败: " + path, ex);
        }
    }

    /**
     * 判断路径是否超过保留时间。
     *
     * @param path 路径
     * @param retention 保留时间
     * @return 超期返回 true
     */
    private boolean olderThan(Path path, Duration retention) {
        try {
            FileTime modified = Files.getLastModifiedTime(path);
            return modified.toInstant().isBefore(Instant.now().minus(retention));
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * 规范化受保护路径。
     *
     * @param paths 原始路径
     * @return 规范路径
     */
    private Set<Path> normalizeProtected(Set<Path> paths) {
        Set<Path> normalized = new HashSet<>();
        if (paths != null) {
            paths.forEach(path -> normalized.add(path.toAbsolutePath().normalize()));
        }
        return Set.copyOf(normalized);
    }

    /**
     * 判断目标路径本身或其父目录是否被保护。
     *
     * @param path 目标路径
     * @param protectedPaths 保护路径
     * @return 受保护返回 true
     */
    private boolean isProtected(Path path, Set<Path> protectedPaths) {
        Path normalized = path.toAbsolutePath().normalize();
        return protectedPaths.stream().anyMatch(value -> normalized.startsWith(value) || value.startsWith(normalized));
    }

    /**
     * 根据配额比例计算阈值等级。
     *
     * @param ratio 占用比例
     * @return 阈值等级
     */
    private StorageLevel level(double ratio) {
        if (ratio >= 1D) {
            return StorageLevel.FULL;
        }
        if (ratio >= CRITICAL_THRESHOLD) {
            return StorageLevel.CRITICAL;
        }
        return ratio >= WARNING_THRESHOLD ? StorageLevel.WARNING : StorageLevel.NORMAL;
    }

    /**
     * 从配置生成分类根目录。
     *
     * @param properties DevBridge 配置
     * @return 分类根目录
     */
    private static Map<StorageCategory, Path> roots(DevBridgeProperties properties) {
        Path aiRoot = Path.of(properties.getAiConfigRoot());
        EnumMap<StorageCategory, Path> values = new EnumMap<>(StorageCategory.class);
        values.put(StorageCategory.AGENT, Path.of(properties.getAiAgentDataRoot()));
        values.put(StorageCategory.ARTIFACTS, Path.of(properties.getToolArtifactRoot()));
        values.put(StorageCategory.AUDIT, Path.of(properties.getToolAuditRoot()));
        values.put(StorageCategory.LOGS, Path.of(properties.getLogCaptureRoot()));
        values.put(StorageCategory.DOWNLOADS, Path.of(properties.getDownloadTempRoot()));
        values.put(StorageCategory.CONFIG, aiRoot);
        // 历史聊天独立计入配额，配置根目录统计仍只读取直属文件，避免重复计算。
        values.put(StorageCategory.CONVERSATION, aiRoot.resolve("conversations"));
        values.put(StorageCategory.MEMORY, aiRoot.resolve("memory"));
        values.put(StorageCategory.RAG, aiRoot.resolve("rag"));
        return values;
    }

    public enum StorageCategory {
        AGENT, ARTIFACTS, AUDIT, LOGS, DOWNLOADS, CONFIG, CONVERSATION, MEMORY, RAG
    }

    public enum StorageLevel {
        NORMAL, WARNING, CRITICAL, FULL
    }

    public record CategoryUsage(StorageCategory category, long bytes, long files, boolean configured) {
    }

    public record StorageSnapshot(
            long totalBytes,
            long totalFiles,
            long quotaBytes,
            StorageLevel level,
            List<CategoryUsage> categories,
            Instant measuredAt) {

        /**
         * 固化分类快照。
         */
        public StorageSnapshot {
            categories = List.copyOf(categories);
        }
    }

    public record CleanupResult(
            StorageSnapshot before,
            StorageSnapshot after,
            long deletedFiles,
            long deletedBytes) {
    }

    /**
     * 流式写入配额许可。
     *
     * <p>by AI.Coding</p>
     */
    public final class WritePermit implements AutoCloseable {

        private final StorageCategory category;
        private long reserved;
        private boolean closed;

        /**
         * 保存写入分类。
         *
         * @param category 存储分类
         */
        private WritePermit(StorageCategory category) {
            this.category = category;
        }

        /**
         * 为下一批流式内容预留配额。
         *
         * @param bytes 字节数
         */
        public void reserve(long bytes) {
            StorageManager.this.reserve(this, bytes);
        }

        /**
         * 提交写入并释放内存预留，磁盘事实由下一次快照重新统计。
         */
        public void commit() {
            StorageManager.this.release(this, true);
        }

        /**
         * 未提交退出时回滚内存预留，不删除调用方临时文件。
         */
        @Override
        public void close() {
            StorageManager.this.release(this, false);
        }

        /**
         * 返回写入分类，用于诊断。
         *
         * @return 分类
         */
        public StorageCategory category() {
            return category;
        }
    }

    private record Usage(long bytes, long files) {
    }

    private static final class CleanupCounter {
        private long deletedFiles;
        private long deletedBytes;
    }
}
