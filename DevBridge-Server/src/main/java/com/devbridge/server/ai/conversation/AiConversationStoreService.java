package com.devbridge.server.ai.conversation;

import com.devbridge.server.ai.config.AiConfigCrypto;
import com.devbridge.server.ai.conversation.AiConversationSummaryService.ConversationSummarySnapshot;
import com.devbridge.server.ai.storage.StorageManager;
import com.devbridge.server.ai.storage.AiDataMaintenanceLock;
import com.devbridge.server.ai.storage.StorageManager.StorageCategory;
import com.devbridge.server.ai.storage.StorageManager.WritePermit;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.BusinessException;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 历史聊天本地文件服务，负责加密压缩、分页索引和旧浏览器数据迁移。
 *
 * <p>一个会话对应一个独立文件，避免单个损坏会话影响其它历史；前端只读写有界消息尾部，
 * 后端按稳定消息 ID 合并并保留未加载的旧消息。</p>
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AiConversationStoreService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiConversationStoreService.class);
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_MESSAGE_LIMIT = 200;
    private static final int MAX_WRITE_MESSAGES = 500;
    private static final int MAX_MIGRATION_CONVERSATIONS = 5000;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int LOCK_STRIPES = 64;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,128}");

    private final Path cryptoRoot;
    private final Path dataRoot;
    private final Path indexFile;
    private final Path quarantineRoot;
    private final ObjectMapper objectMapper;
    private final AiConfigCrypto crypto;
    private final StorageManager storageManager;
    private final AiConversationSummaryService summaryService;
    private final AiDataMaintenanceLock maintenanceLock;
    private final Map<String, ConversationSummary> index = new ConcurrentHashMap<>();
    private final Object[] locks = createLocks();
    private final Object indexLock = new Object();
    private volatile String activeConversationId = "";

    /**
     * 初始化历史聊天目录和可重建索引。
     *
     * @param properties 应用配置
     * @param objectMapper JSON 工具
     * @param crypto AI 本地数据加密工具
     * @param storageManager 统一存储配额管理器
     * @param summaryService 对话与任务摘要服务
     */
    @Autowired
    public AiConversationStoreService(
            DevBridgeProperties properties,
            ObjectMapper objectMapper,
            AiConfigCrypto crypto,
            StorageManager storageManager,
            AiConversationSummaryService summaryService,
            AiDataMaintenanceLock maintenanceLock) {
        this.cryptoRoot = Path.of(properties.getAiConfigRoot()).toAbsolutePath().normalize();
        Path root = cryptoRoot.resolve("conversations");
        this.dataRoot = root.resolve("data");
        this.indexFile = root.resolve("index.enc");
        this.quarantineRoot = root.resolve("quarantine");
        this.objectMapper = objectMapper;
        this.crypto = crypto;
        this.storageManager = storageManager;
        this.summaryService = summaryService;
        this.maintenanceLock = maintenanceLock;
        initialize();
    }

    /** 创建兼容测试的会话 Store。 */
    public AiConversationStoreService(
            DevBridgeProperties properties,
            ObjectMapper objectMapper,
            AiConfigCrypto crypto,
            StorageManager storageManager,
            AiConversationSummaryService summaryService) {
        this(properties, objectMapper, crypto, storageManager, summaryService, new AiDataMaintenanceLock());
    }

    /**
     * 分页读取会话摘要，不加载会话消息正文。
     *
     * @param page 从零开始的页码
     * @param size 每页数量
     * @return 会话分页
     */
    public ConversationPage list(int page, int size) {
        return maintenanceLock.read(() -> listUnlocked(page, size));
    }

    /** 在维护锁保护下读取会话分页。 */
    private ConversationPage listUnlocked(int page, int size) {
        validatePage(page, size);
        List<ConversationSummary> sorted = sortedSummaries();
        long fromValue = (long) page * size;
        int from = (int) Math.min(fromValue, sorted.size());
        int to = Math.min(from + size, sorted.size());
        return new ConversationPage(
                sorted.subList(from, to), page, size, sorted.size(), activeConversationId);
    }

    /**
     * 读取指定会话的最近消息，完整旧消息继续保留在后端文件中。
     *
     * @param conversationId 会话标识
     * @param messageLimit 最近消息上限
     * @return 会话详情
     */
    public ConversationDetail get(String conversationId, int messageLimit) {
        return maintenanceLock.read(() -> getUnlocked(conversationId, messageLimit));
    }

    /** 在维护锁保护下读取会话详情。 */
    private ConversationDetail getUnlocked(String conversationId, int messageLimit) {
        validateId(conversationId);
        if (messageLimit < 1 || messageLimit > MAX_MESSAGE_LIMIT) {
            throw new IllegalArgumentException("会话消息数量必须在 1 到 200 之间");
        }
        StoredConversation stored = readConversation(conversationId);
        int from = Math.max(0, stored.messages().size() - messageLimit);
        List<JsonNode> tail = stored.messages().subList(from, stored.messages().size());
        return detail(stored, tail);
    }

    /**
     * 读取 Context Builder 所需的摘要和最近消息，不向前端暴露摘要存储结构。
     *
     * @param conversationId 会话标识
     * @param messageLimit 最近消息上限
     * @return 上下文快照
     */
    public ConversationContextSnapshot context(String conversationId, int messageLimit) {
        return maintenanceLock.read(() -> contextUnlocked(conversationId, messageLimit));
    }

    /** 在维护锁保护下读取上下文。 */
    private ConversationContextSnapshot contextUnlocked(String conversationId, int messageLimit) {
        validateId(conversationId);
        if (messageLimit < 1 || messageLimit > MAX_MESSAGE_LIMIT) {
            throw new IllegalArgumentException("会话消息数量必须在 1 到 200 之间");
        }
        StoredConversation stored = readConversation(conversationId);
        int messageCount = stored.messages().size();
        int summarizedCount = Math.min(
                messageCount, Math.max(0, stored.summary().sourceMessageCount()));
        // 摘要与原始消息必须保持不重叠，否则会重复消耗 Token 并放大历史信息。
        int from = Math.max(summarizedCount, messageCount - messageLimit);
        List<JsonNode> messages = List.copyOf(stored.messages().subList(from, messageCount));
        return new ConversationContextSnapshot(
                messages, messageCount, from > 0, stored.summary());
    }

    /**
     * 创建或更新会话；消息按稳定 ID 合并，避免有界前端尾部覆盖更早历史。
     *
     * @param conversationId 路径中的会话标识
     * @param request 会话写入请求
     * @return 保存后的有界会话详情
     */
    public ConversationDetail upsert(String conversationId, ConversationWriteRequest request) {
        return maintenanceLock.read(() -> upsertUnlocked(conversationId, request));
    }

    /** 在维护锁保护下写入会话。 */
    private ConversationDetail upsertUnlocked(String conversationId, ConversationWriteRequest request) {
        validateId(conversationId);
        validateWriteRequest(request);
        StoredConversation stored;
        synchronized (lockFor(conversationId)) {
            StoredConversation current = readConversationIfPresent(conversationId);
            if (isActivationOnly(current, request)) {
                // 仅点击历史聊天只更新活动索引，不重写可能很大的会话正文文件。
                stored = current;
            } else {
                stored = mergeConversation(conversationId, current, request);
                writeProtected(conversationFile(conversationId), stored);
            }
        }
        index.put(conversationId, summary(stored));
        if (request.active()) {
            activeConversationId = conversationId;
        }
        writeIndex();
        return detail(stored, recentMessages(stored.messages(), MAX_MESSAGE_LIMIT));
    }

    /**
     * 删除一个历史会话；单会话文件删除不会影响其它会话。
     *
     * @param conversationId 会话标识
     */
    public void delete(String conversationId) {
        maintenanceLock.read(() -> deleteUnlocked(conversationId));
    }

    /** 在维护锁保护下删除会话。 */
    private void deleteUnlocked(String conversationId) {
        validateId(conversationId);
        synchronized (lockFor(conversationId)) {
            try {
                Files.deleteIfExists(conversationFile(conversationId));
            } catch (IOException ex) {
                throw storageError("AI_CONVERSATION_DELETE_FAILED", "历史聊天删除失败", ex);
            }
        }
        index.remove(conversationId);
        if (conversationId.equals(activeConversationId)) {
            activeConversationId = sortedSummaries().stream()
                    .findFirst().map(ConversationSummary::id).orElse("");
        }
        writeIndex();
    }

    /**
     * 幂等迁移旧浏览器会话；任一文件失败时整体返回失败，前端因此不会删除旧数据。
     *
     * @param request 旧会话批量数据
     * @return 迁移结果
     */
    public ConversationMigrationResult migrate(ConversationMigrationRequest request) {
        return maintenanceLock.read(() -> migrateUnlocked(request));
    }

    /** 在维护锁保护下迁移历史会话。 */
    private ConversationMigrationResult migrateUnlocked(ConversationMigrationRequest request) {
        List<ConversationMigrationItem> items = request == null || request.conversations() == null
                ? List.of() : request.conversations();
        if (items.size() > MAX_MIGRATION_CONVERSATIONS) {
            throw new IllegalArgumentException("单次迁移会话数量不能超过 5000");
        }
        try (WritePermit permit = storageManager.openWrite(StorageCategory.CONVERSATION)) {
            for (ConversationMigrationItem item : items) {
                validateMigrationItem(item);
                ConversationWriteRequest write = new ConversationWriteRequest(
                        item.title(), item.titleEdited(), item.messages(),
                        item.createdAt(), item.updatedAt(), false);
                persistWithoutIndexWrite(item.id(), write, permit);
            }
            applyMigratedActiveConversation(request == null ? "" : request.activeConversationId());
            writeIndex(permit);
            permit.commit();
        }
        return new ConversationMigrationResult(items.size(), activeConversationId);
    }

    /**
     * 从独立会话文件重建索引，损坏单文件继续进入隔离目录。
     *
     * @return 恢复后的会话和隔离文件数量
     */
    public ConversationRecoveryResult recoverIndex() {
        return maintenanceLock.read(this::recoverIndexUnlocked);
    }

    /** 在维护锁保护下重建会话索引。 */
    private ConversationRecoveryResult recoverIndexUnlocked() {
        synchronized (indexLock) {
            rebuildIndex();
            return new ConversationRecoveryResult(index.size(), countQuarantinedFiles());
        }
    }

    /** 统计隔离目录中的损坏文件数量。 */
    private long countQuarantinedFiles() {
        if (!Files.isDirectory(quarantineRoot)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(quarantineRoot)) {
            return files.filter(Files::isRegularFile).count();
        } catch (IOException ex) {
            throw storageError("AI_CONVERSATION_QUARANTINE_READ_FAILED", "历史聊天隔离目录读取失败", ex);
        }
    }

    /**
     * 保存迁移中的单个会话，但把索引写入合并到批次末尾以避免 O(n²) IO。
     *
     * @param conversationId 会话标识
     * @param request 会话数据
     * @param permit 批量迁移共享配额许可
     */
    private void persistWithoutIndexWrite(
            String conversationId,
            ConversationWriteRequest request,
            WritePermit permit) {
        StoredConversation stored;
        synchronized (lockFor(conversationId)) {
            StoredConversation current = readConversationIfPresent(conversationId);
            stored = mergeConversation(conversationId, current, request);
            writeProtected(conversationFile(conversationId), stored, permit);
        }
        index.put(conversationId, summary(stored));
    }

    /**
     * 初始化目录，优先读取索引；索引损坏时从独立会话文件重建。
     */
    private void initialize() {
        try {
            Files.createDirectories(dataRoot);
            Files.createDirectories(quarantineRoot);
            if (Files.isRegularFile(indexFile)) {
                loadIndex();
            }
            reconcileIndex();
        } catch (RuntimeException | IOException ex) {
            // 索引可重建，日志只记录异常类型，避免正常恢复路径输出大段堆栈。
            LOGGER.warn("历史聊天索引不可用，正在从会话文件重建, reason={}",
                    ex.getClass().getSimpleName());
            rebuildIndex();
        }
    }

    /**
     * 读取加密索引并恢复活动会话。
     */
    private void loadIndex() {
        StoredIndex stored = readProtected(indexFile, StoredIndex.class);
        if (stored.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalStateException("历史聊天索引版本不支持");
        }
        index.clear();
        if (stored.conversations() != null) {
            stored.conversations().forEach(item -> index.put(item.id(), item));
        }
        activeConversationId = stored.activeConversationId() == null ? "" : stored.activeConversationId();
    }

    /**
     * 对照会话文件修正索引中的新增和已删除项，避免异常退出留下孤儿记录。
     *
     * @throws IOException 文件扫描失败
     */
    private void reconcileIndex() throws IOException {
        Map<String, Path> files = conversationFiles();
        boolean changed = index.keySet().removeIf(id -> !files.containsKey(id));
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            if (!index.containsKey(entry.getKey())) {
                loadIndexEntry(entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        if ((activeConversationId.isBlank() && !index.isEmpty())
                || (!activeConversationId.isBlank() && !index.containsKey(activeConversationId))) {
            activeConversationId = sortedSummaries().stream()
                    .findFirst().map(ConversationSummary::id).orElse("");
            changed = true;
        }
        if (changed) {
            writeIndex();
        }
    }

    /**
     * 清空内存索引并从全部独立会话文件恢复，单文件损坏只隔离自身。
     */
    private void rebuildIndex() {
        index.clear();
        activeConversationId = "";
        try {
            Files.createDirectories(dataRoot);
            Files.createDirectories(quarantineRoot);
            conversationFiles().forEach(this::loadIndexEntry);
            activeConversationId = sortedSummaries().stream()
                    .findFirst().map(ConversationSummary::id).orElse("");
            writeIndex();
        } catch (IOException ex) {
            throw storageError("AI_CONVERSATION_INIT_FAILED", "历史聊天存储初始化失败", ex);
        }
    }

    /**
     * 从一个会话文件恢复摘要，损坏文件仅写入隔离标记。
     *
     * @param conversationId 会话标识
     * @param path 会话文件
     */
    private void loadIndexEntry(String conversationId, Path path) {
        try {
            StoredConversation stored = readProtected(path, StoredConversation.class);
            validateStoredConversation(conversationId, stored);
            index.put(conversationId, summary(stored));
        } catch (RuntimeException ex) {
            quarantine(conversationId, ex);
        }
    }

    /**
     * 合并会话元数据和消息；较旧请求不得覆盖较新的同 ID 消息。
     */
    private StoredConversation mergeConversation(
            String id,
            StoredConversation current,
            ConversationWriteRequest request) {
        long now = Instant.now().toEpochMilli();
        long createdAt = current == null ? positiveTime(request.createdAt(), now) : current.createdAt();
        long requestedUpdatedAt = positiveTime(request.updatedAt(), now);
        boolean replaceExisting = current == null || requestedUpdatedAt >= current.updatedAt();
        List<JsonNode> existingMessages = current == null ? List.of() : current.messages();
        List<JsonNode> incomingMessages = request.messages() == null ? List.of() : request.messages();
        List<JsonNode> messages = mergeMessages(existingMessages, incomingMessages, replaceExisting);
        String title = replaceExisting ? normalizedTitle(request.title()) : current.title();
        boolean titleEdited = replaceExisting ? request.titleEdited() : current.titleEdited();
        long version = current == null ? 1 : current.version() + 1;
        long updatedAt = current == null ? requestedUpdatedAt : Math.max(current.updatedAt(), requestedUpdatedAt);
        ConversationSummarySnapshot existingSummary = current == null
                ? ConversationSummarySnapshot.empty() : current.summary();
        ConversationSummarySnapshot summary = summaryService.summarize(messages, existingSummary);
        return new StoredConversation(
                SCHEMA_VERSION, version, id, title, titleEdited,
                messages, summary, createdAt, updatedAt);
    }

    /**
     * 判断请求是否只激活现有会话，避免选择历史聊天时产生无业务价值的大文件 IO。
     */
    private boolean isActivationOnly(
            StoredConversation current,
            ConversationWriteRequest request) {
        if (current == null || request.messages() == null || !request.messages().isEmpty()) {
            return false;
        }
        return current.title().equals(normalizedTitle(request.title()))
                && current.titleEdited() == request.titleEdited()
                && current.createdAt() == request.createdAt()
                && current.updatedAt() == request.updatedAt();
    }

    /**
     * 按消息 ID 合并有界尾部，保留已有消息顺序和未加载的旧消息。
     */
    private List<JsonNode> mergeMessages(
            List<JsonNode> existing,
            List<JsonNode> incoming,
            boolean replaceExisting) {
        LinkedHashMap<String, JsonNode> merged = new LinkedHashMap<>();
        existing.forEach(message -> merged.put(messageId(message), message));
        for (JsonNode message : incoming) {
            String id = messageId(message);
            if (replaceExisting || !merged.containsKey(id)) {
                merged.put(id, message);
            }
        }
        return List.copyOf(merged.values());
    }

    /**
     * 读取存在的会话；损坏数据从索引隔离并返回明确错误。
     */
    private StoredConversation readConversation(String conversationId) {
        StoredConversation stored = readConversationIfPresent(conversationId);
        if (stored == null) {
            throw new BusinessException(
                    "AI_CONVERSATION_NOT_FOUND", "历史聊天不存在", HttpStatus.NOT_FOUND, conversationId);
        }
        return stored;
    }

    /**
     * 读取可选会话文件。
     */
    private StoredConversation readConversationIfPresent(String conversationId) {
        Path file = conversationFile(conversationId);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            StoredConversation stored = readProtected(file, StoredConversation.class);
            validateStoredConversation(conversationId, stored);
            return stored;
        } catch (RuntimeException ex) {
            quarantine(conversationId, ex);
            throw new BusinessException(
                    "AI_CONVERSATION_CORRUPTED", "历史聊天文件已损坏", HttpStatus.CONFLICT, conversationId);
        }
    }

    /**
     * 使用 JSON、GZIP 和 AES-GCM 生成受保护文件内容。
     */
    private String encode(Object value) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(value);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
                gzip.write(json);
            }
            String compressed = Base64.getEncoder().encodeToString(output.toByteArray());
            return crypto.encrypt(cryptoRoot, compressed);
        } catch (IOException ex) {
            throw storageError("AI_CONVERSATION_ENCODE_FAILED", "历史聊天编码失败", ex);
        }
    }

    /**
     * 解密并解压受保护文件。
     */
    private <T> T readProtected(Path path, Class<T> type) {
        try {
            String encrypted = Files.readString(path, StandardCharsets.UTF_8);
            byte[] compressed = Base64.getDecoder().decode(crypto.decrypt(cryptoRoot, encrypted));
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                return objectMapper.readValue(gzip, type);
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw storageError("AI_CONVERSATION_READ_FAILED", "历史聊天读取失败", ex);
        }
    }

    /**
     * 在统一配额许可内原子写入加密文件。
     */
    private void writeProtected(Path target, Object value) {
        try (StorageManager.WritePermit permit = storageManager.openWrite(StorageCategory.CONVERSATION)) {
            writeProtected(target, value, permit);
            permit.commit();
        }
    }

    /**
     * 使用已有配额许可写入一个受保护文件，供批量迁移避免重复全盘统计。
     */
    private void writeProtected(Path target, Object value, WritePermit permit) {
        byte[] bytes = encode(value).getBytes(StandardCharsets.UTF_8);
        permit.reserve(bytes.length);
        writeAtomic(target, bytes);
    }

    /**
     * 强制刷新临时文件后执行原子替换，避免异常退出留下半个 JSON 文件。
     */
    private void writeAtomic(Path target, byte[] bytes) {
        Path temp = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (FileChannel channel = FileChannel.open(
                    temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            moveAtomic(temp, target);
        } catch (IOException ex) {
            throw storageError("AI_CONVERSATION_WRITE_FAILED", "历史聊天写入失败", ex);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 临时文件清理由统一存储治理后续兜底，不覆盖原始写入异常。
            }
        }
    }

    /**
     * 写入当前内存索引；索引只包含摘要，不复制消息正文。
     */
    private void writeIndex() {
        synchronized (indexLock) {
            StoredIndex stored = new StoredIndex(
                    SCHEMA_VERSION, activeConversationId, sortedSummaries());
            writeProtected(indexFile, stored);
        }
    }

    /**
     * 使用批量写入许可保存索引，迁移过程只在开始时统计一次磁盘占用。
     */
    private void writeIndex(WritePermit permit) {
        synchronized (indexLock) {
            StoredIndex stored = new StoredIndex(
                    SCHEMA_VERSION, activeConversationId, sortedSummaries());
            writeProtected(indexFile, stored, permit);
        }
    }

    /**
     * 扫描全部会话文件路径，文件名和父目录共同提供安全会话 ID。
     */
    private Map<String, Path> conversationFiles() throws IOException {
        Map<String, Path> files = new LinkedHashMap<>();
        if (!Files.isDirectory(dataRoot)) {
            return files;
        }
        try (Stream<Path> paths = Files.walk(dataRoot)) {
            paths.filter(path -> path.getFileName().toString().equals("conversation.enc"))
                    .forEach(path -> files.put(path.getParent().getFileName().toString(), path));
        }
        return files;
    }

    /**
     * 损坏会话写入隔离标记并从可见索引移除，原文件保留用于人工恢复。
     */
    private void quarantine(String conversationId, RuntimeException cause) {
        index.remove(conversationId);
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        Path marker = quarantineRoot.resolve(conversationId + "-" + System.nanoTime() + ".error");
        try {
            Files.createDirectories(quarantineRoot);
            Files.writeString(marker, message, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException ex) {
            LOGGER.warn("历史聊天损坏隔离标记写入失败, conversationId={}", conversationId);
        }
        LOGGER.warn("历史聊天已隔离, conversationId={}, reason={}", conversationId, message);
    }

    /**
     * 校验写入请求大小和消息稳定标识。
     */
    private void validateWriteRequest(ConversationWriteRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("历史聊天写入内容不能为空");
        }
        List<JsonNode> messages = request.messages() == null ? List.of() : request.messages();
        if (messages.size() > MAX_WRITE_MESSAGES) {
            throw new IllegalArgumentException("单次写入消息数量不能超过 500");
        }
        messages.forEach(this::messageId);
        if (request.title() != null && request.title().length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("历史聊天标题不能超过 200 个字符");
        }
    }

    /**
     * 校验单个迁移会话并复用普通写入规则。
     */
    private void validateMigrationItem(ConversationMigrationItem item) {
        if (item == null) {
            throw new IllegalArgumentException("迁移会话不能为空");
        }
        validateId(item.id());
        validateWriteRequest(new ConversationWriteRequest(
                item.title(), item.titleEdited(), item.messages(),
                item.createdAt(), item.updatedAt(), false));
    }

    /**
     * 校验磁盘中的会话版本和标识，防止路径与正文身份不一致。
     */
    private void validateStoredConversation(String expectedId, StoredConversation stored) {
        if (stored == null || stored.schemaVersion() != SCHEMA_VERSION || !expectedId.equals(stored.id())) {
            throw new IllegalStateException("历史聊天文件结构无效");
        }
        stored.messages().forEach(this::messageId);
    }

    /**
     * 校验会话标识，阻止路径穿越和异常文件名。
     */
    private void validateId(String conversationId) {
        if (conversationId == null || !SAFE_ID.matcher(conversationId).matches()) {
            throw new IllegalArgumentException("历史聊天标识不合法");
        }
    }

    /**
     * 校验分页参数，避免一次返回全部会话。
     */
    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("会话分页参数不合法");
        }
    }

    /**
     * 提取消息稳定 ID；没有 ID 的消息不能参与幂等合并。
     */
    private String messageId(JsonNode message) {
        if (message == null || !message.hasNonNull("id") || !message.get("id").isValueNode()) {
            throw new IllegalArgumentException("历史聊天消息缺少稳定 ID");
        }
        String id = message.get("id").asText();
        if (id.isBlank() || id.length() > 128) {
            throw new IllegalArgumentException("历史聊天消息 ID 不合法");
        }
        return id;
    }

    /**
     * 应用旧数据中的活动会话，仅接受已成功迁移的会话标识。
     */
    private void applyMigratedActiveConversation(String requestedId) {
        if (requestedId != null && index.containsKey(requestedId)) {
            activeConversationId = requestedId;
        } else if (!index.containsKey(activeConversationId)) {
            activeConversationId = sortedSummaries().stream()
                    .findFirst().map(ConversationSummary::id).orElse("");
        }
    }

    /**
     * 返回按更新时间倒序的不可变摘要列表。
     */
    private List<ConversationSummary> sortedSummaries() {
        return index.values().stream()
                .sorted(Comparator.comparingLong(ConversationSummary::updatedAt).reversed()
                        .thenComparing(ConversationSummary::id))
                .toList();
    }

    /**
     * 将磁盘会话转换为列表摘要。
     */
    private ConversationSummary summary(StoredConversation stored) {
        return new ConversationSummary(
                stored.id(), stored.title(), stored.titleEdited(),
                stored.createdAt(), stored.updatedAt(), stored.messages().size());
    }

    /**
     * 将磁盘会话转换为有界详情。
     */
    private ConversationDetail detail(StoredConversation stored, List<JsonNode> messages) {
        return new ConversationDetail(
                stored.id(), stored.title(), stored.titleEdited(), List.copyOf(messages),
                stored.createdAt(), stored.updatedAt(), stored.messages().size(),
                messages.size() < stored.messages().size());
    }

    /**
     * 返回最近消息尾部。
     */
    private List<JsonNode> recentMessages(List<JsonNode> messages, int limit) {
        return messages.subList(Math.max(0, messages.size() - limit), messages.size());
    }

    /**
     * 生成会话分片路径，防止 1000 个以上会话堆积在单一目录。
     */
    private Path conversationFile(String conversationId) {
        String compact = conversationId.replace("-", "");
        String first = compact.substring(0, Math.min(2, compact.length()));
        String second = compact.length() > 2 ? compact.substring(2, Math.min(4, compact.length())) : "00";
        return dataRoot.resolve(first).resolve(second).resolve(conversationId).resolve("conversation.enc");
    }

    /**
     * 获取固定锁分片，避免长期为每个历史会话保留锁对象。
     */
    private Object lockFor(String conversationId) {
        return locks[Math.floorMod(conversationId.hashCode(), locks.length)];
    }

    /**
     * 初始化固定数量锁分片。
     */
    private static Object[] createLocks() {
        Object[] values = new Object[LOCK_STRIPES];
        for (int index = 0; index < values.length; index++) {
            values[index] = new Object();
        }
        return values;
    }

    /**
     * 标题为空时使用产品默认名称。
     */
    private String normalizedTitle(String title) {
        return title == null || title.isBlank() ? "新对话" : title.trim();
    }

    /**
     * 时间值无效时使用当前时间，兼容旧浏览器异常数据。
     */
    private long positiveTime(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    /**
     * 优先使用原子移动，不支持时退化为同文件系统替换。
     */
    private void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 构造不泄露会话正文的存储异常。
     */
    private BusinessException storageError(String code, String message, Exception cause) {
        return new BusinessException(code, message, HttpStatus.INTERNAL_SERVER_ERROR, cause.getClass().getSimpleName());
    }

    /** 会话写入请求。by AI.Coding */
    public record ConversationWriteRequest(
            String title,
            boolean titleEdited,
            List<JsonNode> messages,
            long createdAt,
            long updatedAt,
            boolean active) {
    }

    /** 旧浏览器会话迁移项。by AI.Coding */
    public record ConversationMigrationItem(
            String id,
            String title,
            boolean titleEdited,
            List<JsonNode> messages,
            long createdAt,
            long updatedAt) {
    }

    /** 旧浏览器会话批量迁移请求。by AI.Coding */
    public record ConversationMigrationRequest(
            List<ConversationMigrationItem> conversations,
            String activeConversationId) {
    }

    /** 会话列表摘要。by AI.Coding */
    public record ConversationSummary(
            String id,
            String title,
            boolean titleEdited,
            long createdAt,
            long updatedAt,
            int messageCount) {
    }

    /** 会话分页响应。by AI.Coding */
    public record ConversationPage(
            List<ConversationSummary> items,
            int page,
            int size,
            long total,
            String activeConversationId) {

        /** 固化分页列表，避免调用方修改服务内数据。 */
        public ConversationPage {
            items = List.copyOf(items);
        }
    }

    /** 会话详情响应。by AI.Coding */
    public record ConversationDetail(
            String id,
            String title,
            boolean titleEdited,
            List<JsonNode> messages,
            long createdAt,
            long updatedAt,
            int messageCount,
            boolean hasMoreMessages) {

        /** 固化消息尾部，避免调用方修改持久化对象。 */
        public ConversationDetail {
            messages = List.copyOf(messages);
        }
    }

    /**
     * Context Builder 内部读取快照，包含有界最近消息和持久摘要。
     *
     * <p>by AI.Coding</p>
     */
    public record ConversationContextSnapshot(
            List<JsonNode> messages,
            int messageCount,
            boolean hasMoreMessages,
            ConversationSummarySnapshot summary) {

        /** 固化消息并兼容旧会话缺失摘要。 */
        public ConversationContextSnapshot {
            messages = List.copyOf(messages);
            summary = summary == null ? ConversationSummarySnapshot.empty() : summary;
        }
    }

    /** 迁移结果。by AI.Coding */
    public record ConversationMigrationResult(int migratedCount, String activeConversationId) {
    }

    /** 历史会话索引恢复结果。by AI.Coding */
    public record ConversationRecoveryResult(int recoveredConversations, long quarantinedFiles) {
    }

    /** 磁盘会话正文。by AI.Coding */
    private record StoredConversation(
            int schemaVersion,
            long version,
            String id,
            String title,
            boolean titleEdited,
            List<JsonNode> messages,
            ConversationSummarySnapshot summary,
            long createdAt,
            long updatedAt) {

        /** 固化磁盘消息，避免保存期间被修改。 */
        private StoredConversation {
            messages = messages == null ? List.of() : List.copyOf(messages);
            summary = summary == null ? ConversationSummarySnapshot.empty() : summary;
        }
    }

    /** 磁盘索引。by AI.Coding */
    private record StoredIndex(
            int schemaVersion,
            String activeConversationId,
            List<ConversationSummary> conversations) {
    }
}
