package com.devbridge.server.ai.rag;

import com.devbridge.server.ai.config.AiConfigCrypto;
import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory;
import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory.MemoryQuery;
import com.devbridge.server.ai.storage.StorageManager;
import com.devbridge.server.ai.storage.AiDataMaintenanceLock;
import com.devbridge.server.ai.storage.StorageManager.StorageCategory;
import com.devbridge.server.config.DevBridgeProperties;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

/**
 * 本地 RAG 边界，提供加密文档、确定性切分、轻量词法检索、引用和索引重建。
 *
 * <p>当前产品规模不引入向量数据库和额外 embedding 模型；检索内容始终作为不可信证据使用。</p>
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AiRagBoundary {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiRagBoundary.class);
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final int CHUNK_CHARACTERS = 1_200;
    private static final int CHUNK_OVERLAP = 200;
    private static final int MAX_DOCUMENT_CHARACTERS = 5_000_000;
    private static final int MAX_RESULTS = 8;
    private static final int EXCERPT_CHARACTERS = 600;

    private final Path cryptoRoot;
    private final Path documentRoot;
    private final ObjectMapper objectMapper;
    private final AiConfigCrypto crypto;
    private final StorageManager storageManager;
    private final AiDeviceIncidentMemory memory;
    private final AiDataMaintenanceLock maintenanceLock;
    private final Map<String, DocumentMetadata> documents = new ConcurrentHashMap<>();

    /**
     * 初始化本地 RAG 文档目录并恢复索引。
     *
     * @param properties 应用配置
     * @param objectMapper JSON 工具
     * @param crypto 本地加密工具
     * @param storageManager 统一磁盘配额
     * @param memory 设备与故障 Memory
     */
    @Autowired
    public AiRagBoundary(
            DevBridgeProperties properties,
            ObjectMapper objectMapper,
            AiConfigCrypto crypto,
            StorageManager storageManager,
            AiDeviceIncidentMemory memory,
            AiDataMaintenanceLock maintenanceLock) {
        this.cryptoRoot = Path.of(properties.getAiConfigRoot()).toAbsolutePath().normalize();
        this.documentRoot = cryptoRoot.resolve("rag").resolve("documents");
        this.objectMapper = objectMapper;
        this.crypto = crypto;
        this.storageManager = storageManager;
        this.memory = memory;
        this.maintenanceLock = maintenanceLock;
        initialize();
    }

    /** 创建兼容测试和显式装配的本地 RAG 边界。 */
    public AiRagBoundary(
            DevBridgeProperties properties,
            ObjectMapper objectMapper,
            AiConfigCrypto crypto,
            StorageManager storageManager,
            AiDeviceIncidentMemory memory) {
        this(properties, objectMapper, crypto, storageManager, memory, new AiDataMaintenanceLock());
    }

    /**
     * 判断当前是否有可检索知识。
     *
     * @return 存在本地文档或故障案例时返回 true
     */
    public boolean enabled() {
        return !documents.isEmpty() || !memory.searchIncidents(new MemoryQuery("", "", "", 1)).isEmpty();
    }

    /**
     * 导入或按来源更新一个文本文档。
     *
     * @param request 文档导入请求
     * @return 文档元数据
     */
    public DocumentMetadata importDocument(ImportRequest request) {
        return maintenanceLock.read(() -> importDocumentUnlocked(request));
    }

    /** 在维护读锁内导入或更新文档。 */
    private DocumentMetadata importDocumentUnlocked(ImportRequest request) {
        validate(request);
        DocumentMetadata existing = documents.values().stream()
                .filter(value -> value.source().equals(request.source().trim()))
                .findFirst().orElse(null);
        String id = existing == null ? "rag-" + UUID.randomUUID() : existing.id();
        int version = existing == null ? 1 : existing.version() + 1;
        Instant createdAt = existing == null ? Instant.now() : existing.createdAt();
        List<DocumentChunk> chunks = chunks(id, request.content());
        DocumentMetadata metadata = new DocumentMetadata(
                id, request.title().trim(), request.source().trim(), version,
                chunks.size(), createdAt, Instant.now());
        StoredDocument document = new StoredDocument(metadata, request.content(), chunks);
        write(document);
        // 内存只保留元数据；正文、Chunk 和词集合按查询逐文档加载，避免知识库规模直接占满堆。
        documents.put(id, metadata);
        return metadata;
    }

    /**
     * 检索本地文档和历史故障，返回带来源的有界引用。
     *
     * @param request 检索请求
     * @return 检索结果
     */
    public SearchResult search(SearchRequest request) {
        if (request == null || !StringUtils.hasText(request.query())) {
            return new SearchResult("", List.of(), false);
        }
        int limit = Math.min(MAX_RESULTS, Math.max(1, request.limit()));
        Set<String> queryTerms = terms(request.query());
        List<RagCitation> citations = new ArrayList<>();
        for (DocumentMetadata metadata : documents.values()) {
            scoreDocument(read(path(metadata.id())), queryTerms, citations);
        }
        scoreIncidents(request, queryTerms, citations);
        List<RagCitation> selected = citations.stream()
                .filter(value -> value.score() > 0)
                .sorted(Comparator.comparingDouble(RagCitation::score).reversed()
                        .thenComparing(RagCitation::source))
                .limit(limit)
                .toList();
        return new SearchResult(request.query().trim(), selected, !selected.isEmpty());
    }

    /**
     * 返回文档元数据，不加载正文。
     *
     * @return 文档列表
     */
    public List<DocumentMetadata> listDocuments() {
        return documents.values().stream()
                .sorted(Comparator.comparing(DocumentMetadata::updatedAt).reversed())
                .toList();
    }

    /**
     * 删除文档及内存索引。
     *
     * @param documentId 文档标识
     * @return 已删除返回 true
     */
    public boolean delete(String documentId) {
        return maintenanceLock.read(() -> deleteUnlocked(documentId));
    }

    /** 在维护读锁内删除文档文件和索引。 */
    private boolean deleteUnlocked(String documentId) {
        if (!validId(documentId) || !documents.containsKey(documentId)) {
            return false;
        }
        try {
            Files.deleteIfExists(path(documentId));
            documents.remove(documentId);
            return true;
        } catch (IOException ex) {
            throw new IllegalStateException("RAG 文档删除失败", ex);
        }
    }

    /**
     * 使用持久正文重新切分全部文档并递增索引版本。
     *
     * @return 已重建文档数量
     */
    public int rebuild() {
        return maintenanceLock.read(this::rebuildUnlocked);
    }

    /** 在维护读锁内重新切分现有文档。 */
    private int rebuildUnlocked() {
        List<DocumentMetadata> current = new ArrayList<>(documents.values());
        for (DocumentMetadata metadata : current) {
            StoredDocument document = read(path(metadata.id()));
            ImportRequest request = new ImportRequest(
                    document.metadata().title(), document.metadata().source(), document.content());
            importDocument(request);
        }
        return current.size();
    }

    /** 从恢复后的本地文档重建 RAG 内存索引。 */
    public void recoverIndex() {
        maintenanceLock.read(() -> {
            documents.clear();
            initialize();
        });
    }

    /**
     * 对单个文档的每个 Chunk 计算查询词覆盖率和短语加权。
     */
    private void scoreDocument(
            StoredDocument document,
            Set<String> queryTerms,
            List<RagCitation> citations) {
        for (DocumentChunk chunk : document.chunks()) {
            double score = score(queryTerms, chunk.terms(), chunk.text());
            if (score <= 0) {
                continue;
            }
            citations.add(new RagCitation(
                    document.metadata().id(), document.metadata().title(), document.metadata().source(),
                    chunk.id(), excerpt(chunk.text()), score));
        }
    }

    /**
     * 把历史故障作为可引用的本地知识来源。
     */
    private void scoreIncidents(
            SearchRequest request,
            Set<String> queryTerms,
            List<RagCitation> citations) {
        var records = memory.searchIncidents(new MemoryQuery(
                request.deviceId(), request.osVersion(), "", MAX_RESULTS * 4));
        for (var incident : records) {
            String text = incident.details().signature() + "\n" + incident.details().summary()
                    + "\n" + String.join("\n", incident.details().evidence())
                    + "\n" + incident.details().resolution();
            double score = score(queryTerms, terms(text), text);
            if (score > 0) {
                citations.add(new RagCitation(
                        incident.id(), "历史故障案例", "incident://" + incident.id(),
                        incident.id(), excerpt(text), score + 0.25D));
            }
        }
    }

    /**
     * 计算简单可解释的词法相关性，避免引入外部模型和不可控成本。
     */
    private double score(Set<String> queryTerms, Set<String> documentTerms, String text) {
        if (queryTerms.isEmpty() || documentTerms.isEmpty()) {
            return 0D;
        }
        long matches = queryTerms.stream().filter(documentTerms::contains).count();
        double coverage = (double) matches / queryTerms.size();
        String normalized = text.toLowerCase(Locale.ROOT);
        double phrase = queryTerms.stream().anyMatch(term -> term.length() > 2 && normalized.contains(term))
                ? 0.25D : 0D;
        return matches == 0 ? 0D : coverage + phrase;
    }

    /**
     * 按固定大小和重叠窗口切分文本。
     */
    private List<DocumentChunk> chunks(String documentId, String content) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int index = 0;
        for (int start = 0; start < content.length(); start += CHUNK_CHARACTERS - CHUNK_OVERLAP) {
            int end = Math.min(content.length(), start + CHUNK_CHARACTERS);
            String text = content.substring(start, end).trim();
            if (!text.isBlank()) {
                chunks.add(new DocumentChunk(
                        documentId + "-chunk-" + index, index, text, terms(text)));
                index++;
            }
            if (end == content.length()) {
                break;
            }
        }
        return List.copyOf(chunks);
    }

    /**
     * 生成英文单词、数字和中文双字词集合。
     */
    private Set<String> terms(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        Set<String> values = new HashSet<>();
        for (String part : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (!part.isBlank()) {
                values.add(part);
            }
        }
        String chinese = normalized.replaceAll("[^\\p{IsHan}]", "");
        for (int index = 0; index + 1 < chinese.length(); index++) {
            values.add(chinese.substring(index, index + 2));
        }
        return Set.copyOf(values);
    }

    /**
     * 初始化目录并恢复全部可读文档。
     */
    private void initialize() {
        try {
            Files.createDirectories(documentRoot);
            try (var files = Files.list(documentRoot)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".enc")).toList()) {
                    try {
                        StoredDocument document = read(file);
                        documents.put(document.metadata().id(), document.metadata());
                    } catch (RuntimeException ex) {
                        LOGGER.warn("RAG 文档读取失败，已跳过: {}", file.getFileName());
                    }
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("RAG 初始化失败", ex);
        }
    }

    /**
     * 加密压缩并原子保存单个文档。
     */
    private void write(StoredDocument document) {
        byte[] bytes = encode(document);
        try (StorageManager.WritePermit permit = storageManager.openWrite(StorageCategory.RAG)) {
            permit.reserve(bytes.length);
            writeAtomic(path(document.metadata().id()), bytes);
            permit.commit();
        }
    }

    /**
     * 解密并读取单个文档。
     */
    private StoredDocument read(Path file) {
        try {
            String encrypted = Files.readString(file, StandardCharsets.UTF_8);
            byte[] compressed = Base64.getDecoder().decode(crypto.decrypt(cryptoRoot, encrypted));
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                StoredDocument document = objectMapper.readValue(gzip, StoredDocument.class);
                if (!SCHEMA_VERSION.equals(document.schemaVersion())) {
                    throw new IllegalStateException("RAG Schema 不支持: " + document.schemaVersion());
                }
                return document;
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("RAG 文档读取失败", ex);
        }
    }

    /**
     * 编码文档内容。
     */
    private byte[] encode(StoredDocument document) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
                gzip.write(objectMapper.writeValueAsBytes(document));
            }
            String compressed = Base64.getEncoder().encodeToString(output.toByteArray());
            return crypto.encrypt(cryptoRoot, compressed).getBytes(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("RAG 文档编码失败", ex);
        }
    }

    /**
     * 原子写入文档文件。
     */
    private void writeAtomic(Path target, byte[] bytes) {
        Path temporary = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
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
            throw new IllegalStateException("RAG 文档写入失败", ex);
        } finally {
            tryDelete(temporary);
        }
    }

    /** 原子移动不支持时回退到同文件系统替换。 */
    private void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 清理临时文件。 */
    private void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件没有索引引用，后续磁盘清理可以处理。
        }
    }

    /** 校验导入请求和正文大小。 */
    private void validate(ImportRequest request) {
        if (request == null || !StringUtils.hasText(request.title())
                || !StringUtils.hasText(request.source()) || !StringUtils.hasText(request.content())) {
            throw new IllegalArgumentException("RAG 文档标题、来源和正文不能为空");
        }
        if (request.title().length() > 256 || request.source().length() > 1_024) {
            throw new IllegalArgumentException("RAG 文档标题或来源过长");
        }
        if (request.content().length() > MAX_DOCUMENT_CHARACTERS) {
            throw new IllegalArgumentException("RAG 单文档不能超过 500 万字符");
        }
    }

    /** 返回有界引用摘要。 */
    private String excerpt(String text) {
        String value = text == null ? "" : text.trim();
        return value.length() <= EXCERPT_CHARACTERS
                ? value : value.substring(0, EXCERPT_CHARACTERS) + "...";
    }

    /** 校验文档标识。 */
    private boolean validId(String value) {
        return StringUtils.hasText(value) && value.matches("[A-Za-z0-9-]{1,128}");
    }

    /** 返回文档文件路径。 */
    private Path path(String documentId) {
        return documentRoot.resolve(documentId + ".enc");
    }

    /** 文档导入请求。by AI.Coding */
    public record ImportRequest(String title, String source, String content) {
    }

    /** 检索请求。by AI.Coding */
    public record SearchRequest(String query, String deviceId, String osVersion, int limit) {
    }

    /** RAG 文档元数据。by AI.Coding */
    public record DocumentMetadata(
            String id,
            String title,
            String source,
            int version,
            int chunkCount,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** RAG Chunk。by AI.Coding */
    public record DocumentChunk(String id, int index, String text, Set<String> terms) {

        /** 固化词项集合。 */
        public DocumentChunk {
            terms = terms == null ? Set.of() : Set.copyOf(terms);
        }
    }

    /** 加密文档内容。by AI.Coding */
    private record StoredDocument(
            String schemaVersion,
            DocumentMetadata metadata,
            String content,
            List<DocumentChunk> chunks) {

        /** 创建当前 Schema 文档。 */
        private StoredDocument(DocumentMetadata metadata, String content, List<DocumentChunk> chunks) {
            this(SCHEMA_VERSION, metadata, content, List.copyOf(chunks));
        }

        /** 固化 Chunk 集合。 */
        private StoredDocument {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }

    /** 可追溯 RAG 引用。by AI.Coding */
    public record RagCitation(
            String documentId,
            String title,
            String source,
            String chunkId,
            String excerpt,
            double score) {
    }

    /** RAG 检索结果。by AI.Coding */
    public record SearchResult(String query, List<RagCitation> citations, boolean hasMatches) {

        /** 固化引用列表。 */
        public SearchResult {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }
}
