package com.devbridge.server.ai.tool.artifact;

import com.devbridge.server.ai.storage.StorageManager;
import com.devbridge.server.ai.storage.AiDataMaintenanceLock;
import com.devbridge.server.ai.storage.StorageManager.StorageCategory;
import com.devbridge.server.ai.storage.StorageManager.WritePermit;
import com.devbridge.server.ai.tool.gateway.ToolContract.ArtifactIntegrity;
import com.devbridge.server.ai.tool.gateway.ToolContract.ArtifactReference;
import com.devbridge.server.ai.tool.gateway.ToolContract.ArtifactRetention;
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 工具 Artifact 分段文件存储，使用有界缓冲流处理大输出并提供有界范围读取。
 *
 * <p>每段独立压缩，范围读取只解压命中的分段；模型和前端不会获取本机真实路径。</p>
 *
 * <p>by AI.Coding</p>
 */
@Service
public class ToolArtifactStore {

    static final int SEGMENT_BYTES = 4 * 1024 * 1024;
    static final int MAX_RANGE_BYTES = 1024 * 1024;
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final String METADATA_FILE = "metadata.json";

    private final Path root;
    private final ObjectMapper objectMapper;
    private final StorageManager storageManager;
    private final AiDataMaintenanceLock maintenanceLock;

    /**
     * 从集中配置初始化 Artifact 根目录。
     *
     * @param properties DevBridge 配置
     * @param objectMapper JSON 工具
     * @param storageManager 存储管理器
     */
    @Autowired
    public ToolArtifactStore(
            DevBridgeProperties properties,
            ObjectMapper objectMapper,
            StorageManager storageManager,
            AiDataMaintenanceLock maintenanceLock) {
        this(Path.of(properties.getToolArtifactRoot()), objectMapper, storageManager, maintenanceLock);
    }

    /** 创建兼容现有测试和显式装配的 Artifact Store。 */
    public ToolArtifactStore(
            DevBridgeProperties properties, ObjectMapper objectMapper, StorageManager storageManager) {
        this(Path.of(properties.getToolArtifactRoot()), objectMapper, storageManager, new AiDataMaintenanceLock());
    }

    /**
     * 创建兼容测试和手工装配的 Artifact Store。
     *
     * @param properties DevBridge 配置
     * @param objectMapper JSON 工具
     */
    public ToolArtifactStore(DevBridgeProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new StorageManager(properties, objectMapper));
    }

    /**
     * 创建测试或嵌入式 Artifact Store。
     *
     * @param root 存储根目录
     * @param objectMapper JSON 工具
     */
    ToolArtifactStore(Path root, ObjectMapper objectMapper) {
        this(root, objectMapper, new StorageManager(
                Map.of(StorageCategory.ARTIFACTS, root), Long.MAX_VALUE, objectMapper),
                new AiDataMaintenanceLock());
    }

    /**
     * 创建指定 Storage Manager 的内部 Store。
     *
     * @param root 存储根目录
     * @param objectMapper JSON 工具
     * @param storageManager 存储管理器
     */
    private ToolArtifactStore(
            Path root,
            ObjectMapper objectMapper,
            StorageManager storageManager,
            AiDataMaintenanceLock maintenanceLock) {
        this.root = root.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.storageManager = storageManager;
        this.maintenanceLock = maintenanceLock;
        createDirectories(this.root);
    }

    /**
     * 从输入流分段写入 Artifact，过程中不保留完整内容副本。
     *
     * @param request 写入元数据
     * @param input 原始输入流
     * @return Artifact 元数据
     */
    public ArtifactMetadata write(ArtifactWriteRequest request, InputStream input) {
        return maintenanceLock.read(() -> writeUnlocked(request, input));
    }

    /** 在业务维护读锁内执行 Artifact 原子写入。 */
    private ArtifactMetadata writeUnlocked(ArtifactWriteRequest request, InputStream input) {
        validateRequest(request, input);
        String artifactId = UUID.randomUUID().toString();
        Path temporary = root.resolve(".tmp-" + artifactId);
        Path target = root.resolve(artifactId);
        createDirectories(temporary);
        try (WritePermit permit = storageManager.openWrite(StorageCategory.ARTIFACTS)) {
            WriteSummary summary = writeSegments(temporary, request.compression(), input, permit);
            ArtifactMetadata metadata = new ArtifactMetadata(
                    new ArtifactIdentity(artifactId, request.kind(), request.mediaType()),
                    new ArtifactStorage(
                            summary.sizeBytes(), summary.sha256(), request.compression(),
                            summary.segmentCount(), SEGMENT_BYTES),
                    new ArtifactPolicy(
                            request.sensitivity(), request.retentionUntil(), true, request.redacted()),
                    Instant.now());
            objectMapper.writeValue(temporary.resolve(METADATA_FILE).toFile(), metadata);
            moveAtomically(temporary, target);
            permit.commit();
            return metadata;
        } catch (IOException | RuntimeException ex) {
            deleteTree(temporary);
            throw new IllegalStateException("Artifact 写入失败", ex);
        }
    }

    /**
     * 从本机文件流式写入 Artifact。
     *
     * @param request 写入元数据
     * @param source 源文件
     * @return Artifact 元数据
     */
    public ArtifactMetadata writeFile(ArtifactWriteRequest request, Path source) {
        try (InputStream input = Files.newInputStream(source)) {
            return write(request, input);
        } catch (IOException ex) {
            throw new IllegalStateException("Artifact 源文件读取失败", ex);
        }
    }

    /**
     * 按 Artifact ID 读取元数据。
     *
     * @param artifactId Artifact ID
     * @return 元数据
     */
    public Optional<ArtifactMetadata> find(String artifactId) {
        return maintenanceLock.read(() -> findUnlocked(artifactId));
    }

    /** 读取 Artifact 元数据，调用方已持有维护读锁。 */
    private Optional<ArtifactMetadata> findUnlocked(String artifactId) {
        if (!validId(artifactId)) {
            return Optional.empty();
        }
        Path metadata = root.resolve(artifactId).resolve(METADATA_FILE);
        if (!Files.isRegularFile(metadata)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(metadata.toFile(), ArtifactMetadata.class));
        } catch (IOException ex) {
            throw new IllegalStateException("Artifact 元数据读取失败", ex);
        }
    }

    /**
     * 有界读取指定字节范围，最大单次返回 1 MiB。
     *
     * @param artifactId Artifact ID
     * @param offset 起始偏移
     * @param length 请求长度
     * @return 范围内容
     */
    public ArtifactRange readRange(String artifactId, long offset, int length) {
        return maintenanceLock.read(() -> readRangeUnlocked(artifactId, offset, length));
    }

    /** 在业务维护读锁内读取 Artifact 范围。 */
    private ArtifactRange readRangeUnlocked(String artifactId, long offset, int length) {
        ArtifactMetadata metadata = find(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact 不存在: " + artifactId));
        if (offset < 0 || offset > metadata.storage().sizeBytes() || length <= 0 || length > MAX_RANGE_BYTES) {
            throw new IllegalArgumentException("Artifact 范围参数无效");
        }
        int actualLength = (int) Math.min(length, metadata.storage().sizeBytes() - offset);
        byte[] result = new byte[actualLength];
        int copied = copyRange(metadata, offset, result);
        return new ArtifactRange(
                metadata.identity().artifactId(), offset, offset + copied, metadata.storage().sizeBytes(),
                metadata.identity().mediaType(), copied == result.length ? result : java.util.Arrays.copyOf(result, copied));
    }

    /**
     * 将 Artifact 原始内容流式写入受控目标文件，供安装和文件传输等领域服务使用。
     *
     * @param artifactId Artifact ID
     * @param target 目标文件
     */
    public void copyTo(String artifactId, Path target) {
        maintenanceLock.read(() -> copyToUnlocked(artifactId, target));
    }

    /** 在业务维护读锁内复制 Artifact，避免恢复覆盖读取中的分段。 */
    private void copyToUnlocked(String artifactId, Path target) {
        ArtifactMetadata metadata = find(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact 不存在: " + artifactId));
        try {
            Path parent = target.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(target), BUFFER_BYTES)) {
                for (int segment = 0; segment < metadata.storage().segmentCount(); segment++) {
                    try (InputStream input = segmentInput(metadata, segment)) {
                        input.transferTo(output);
                    }
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Artifact 内容复制失败", ex);
        }
    }

    /**
     * 删除 Artifact 全部分段和元数据。
     *
     * @param artifactId Artifact ID
     * @return 存在并删除返回 true
     */
    public boolean delete(String artifactId) {
        return maintenanceLock.read(() -> deleteUnlocked(artifactId));
    }

    /** 在业务维护读锁内删除 Artifact。 */
    private boolean deleteUnlocked(String artifactId) {
        if (!validId(artifactId)) {
            return false;
        }
        Path directory = root.resolve(artifactId);
        if (!Files.exists(directory)) {
            return false;
        }
        deleteTree(directory);
        return true;
    }

    /**
     * 将存储元数据转换为中立工具结果引用。
     *
     * @param metadata Artifact 元数据
     * @return 中立引用
     */
    public ArtifactReference reference(ArtifactMetadata metadata) {
        return new ArtifactReference(
                metadata.identity().artifactId(),
                metadata.identity().kind(),
                metadata.identity().mediaType(),
                new ArtifactIntegrity(
                        metadata.storage().sizeBytes(), metadata.storage().sha256(), metadata.storage().compression()),
                new ArtifactRetention(metadata.policy().sensitivity(), metadata.policy().retentionUntil()),
                metadata.policy().rangeReadable(),
                metadata.policy().redacted());
    }

    /**
     * 使用固定大小分段写入并计算原始内容摘要。
     *
     * @param directory 临时目录
     * @param compression 压缩方式
     * @param input 输入流
     * @return 写入摘要
     * @throws IOException IO 失败
     */
    private WriteSummary writeSegments(
            Path directory, String compression, InputStream input, WritePermit permit) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0;
        int segment = 0;
        int segmentBytes = 0;
        OutputStream output = null;
        try (InputStream source = new BufferedInputStream(input, BUFFER_BYTES)) {
            int read;
            while ((read = source.read(buffer)) != -1) {
                int cursor = 0;
                while (cursor < read) {
                    if (output == null || segmentBytes == SEGMENT_BYTES) {
                        close(output);
                        output = segmentOutput(directory, segment++, compression);
                        segmentBytes = 0;
                    }
                    int chunk = Math.min(read - cursor, SEGMENT_BYTES - segmentBytes);
                    permit.reserve(chunk);
                    output.write(buffer, cursor, chunk);
                    digest.update(buffer, cursor, chunk);
                    cursor += chunk;
                    segmentBytes += chunk;
                    total += chunk;
                }
            }
        } finally {
            close(output);
        }
        return new WriteSummary(total, HexFormat.of().formatHex(digest.digest()), segment);
    }

    /**
     * 从命中分段读取目标范围。
     *
     * @param metadata Artifact 元数据
     * @param offset 起始偏移
     * @param target 目标缓冲区
     * @return 实际复制字节数
     */
    private int copyRange(ArtifactMetadata metadata, long offset, byte[] target) {
        int firstSegment = (int) (offset / metadata.storage().segmentBytes());
        int segmentOffset = (int) (offset % metadata.storage().segmentBytes());
        int copied = 0;
        for (int segment = firstSegment; segment < metadata.storage().segmentCount() && copied < target.length; segment++) {
            try (InputStream input = segmentInput(metadata, segment)) {
                input.skipNBytes(segment == firstSegment ? segmentOffset : 0);
                copied += readAtMost(input, target, copied, target.length - copied);
            } catch (IOException ex) {
                throw new IllegalStateException("Artifact 范围读取失败", ex);
            }
        }
        return copied;
    }

    /**
     * 打开分段输出流；gzip 为逐段压缩，保持范围读取边界。
     *
     * @param directory Artifact 目录
     * @param segment 分段编号
     * @param compression 压缩方式
     * @return 输出流
     * @throws IOException IO 失败
     */
    private OutputStream segmentOutput(Path directory, int segment, String compression) throws IOException {
        OutputStream file = new BufferedOutputStream(Files.newOutputStream(segmentPath(directory, segment, compression)));
        return "gzip".equals(compression) ? new GZIPOutputStream(file, BUFFER_BYTES) : file;
    }

    /**
     * 打开分段输入流并按元数据解压。
     *
     * @param metadata Artifact 元数据
     * @param segment 分段编号
     * @return 原始字节输入流
     * @throws IOException IO 失败
     */
    private InputStream segmentInput(ArtifactMetadata metadata, int segment) throws IOException {
        Path path = segmentPath(
                root.resolve(metadata.identity().artifactId()), segment, metadata.storage().compression());
        InputStream file = new BufferedInputStream(Files.newInputStream(path), BUFFER_BYTES);
        return "gzip".equals(metadata.storage().compression()) ? new GZIPInputStream(file, BUFFER_BYTES) : file;
    }

    /**
     * 生成分段文件路径。
     *
     * @param directory Artifact 目录
     * @param segment 分段编号
     * @param compression 压缩方式
     * @return 分段路径
     */
    private Path segmentPath(Path directory, int segment, String compression) {
        String extension = "gzip".equals(compression) ? ".part.gz" : ".part";
        return directory.resolve(String.format("%06d%s", segment, extension));
    }

    /**
     * 从输入流读取不超过指定长度的字节。
     *
     * @param input 输入流
     * @param target 目标数组
     * @param offset 目标偏移
     * @param length 最大长度
     * @return 实际长度
     * @throws IOException IO 失败
     */
    private int readAtMost(InputStream input, byte[] target, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = input.read(target, offset + total, length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    /**
     * 校验元数据、压缩方式和输入流。
     *
     * @param request 写入请求
     * @param input 输入流
     */
    private void validateRequest(ArtifactWriteRequest request, InputStream input) {
        if (request == null || input == null || !StringUtils.hasText(request.kind())
                || !StringUtils.hasText(request.mediaType()) || !StringUtils.hasText(request.sensitivity())
                || request.retentionUntil() == null || !List.of("none", "gzip").contains(request.compression())) {
            throw new IllegalArgumentException("Artifact 写入参数无效");
        }
    }

    /**
     * 创建 SHA-256 摘要器。
     *
     * @return 摘要器
     */
    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    /**
     * 原子发布已完成 Artifact 目录。
     *
     * @param source 临时目录
     * @param target 最终目录
     * @throws IOException 移动失败
     */
    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        }
    }

    /**
     * 创建目录，失败时转换为受控存储错误。
     *
     * @param directory 目录
     */
    private void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            throw new IllegalStateException("Artifact 目录创建失败", ex);
        }
    }

    /**
     * 关闭输出流，保留原始写入异常。
     *
     * @param output 输出流
     */
    private void close(OutputStream output) {
        if (output == null) {
            return;
        }
        try {
            output.close();
        } catch (IOException ex) {
            throw new IllegalStateException("Artifact 分段关闭失败", ex);
        }
    }

    /**
     * 递归删除受控 Artifact 目录。
     *
     * @param directory 目录
     */
    private void deleteTree(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败不覆盖原始写入异常，Storage Manager 后续可再次回收临时目录。
                }
            });
        } catch (IOException ignored) {
            // 目录遍历失败时保留现场，避免误删根目录之外的文件。
        }
    }

    /**
     * 校验 Artifact ID 只能是本服务生成的 UUID。
     *
     * @param artifactId Artifact ID
     * @return 合法返回 true
     */
    private boolean validId(String artifactId) {
        try {
            return artifactId != null && UUID.fromString(artifactId).toString().equals(artifactId);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public record ArtifactWriteRequest(
            String kind,
            String mediaType,
            String sensitivity,
            Instant retentionUntil,
            boolean redacted,
            String compression) {
    }

    public record ArtifactIdentity(String artifactId, String kind, String mediaType) {
    }

    public record ArtifactStorage(
            long sizeBytes,
            String sha256,
            String compression,
            int segmentCount,
            int segmentBytes) {
    }

    public record ArtifactPolicy(
            String sensitivity,
            Instant retentionUntil,
            boolean rangeReadable,
            boolean redacted) {
    }

    public record ArtifactMetadata(
            ArtifactIdentity identity,
            ArtifactStorage storage,
            ArtifactPolicy policy,
            Instant createdAt) {
    }

    public record ArtifactRange(
            String artifactId,
            long start,
            long endExclusive,
            long totalBytes,
            String mediaType,
            byte[] bytes) {

        /**
         * 防御性复制范围字节。
         */
        public ArtifactRange {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        /**
         * 返回范围字节副本。
         *
         * @return 字节副本
         */
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record WriteSummary(long sizeBytes, String sha256, int segmentCount) {
    }
}
