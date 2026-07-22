package com.devbridge.server.ai.storage;

import com.devbridge.server.ai.conversation.AiConversationStoreService;
import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory;
import com.devbridge.server.ai.rag.AiRagBoundary;
import com.devbridge.server.ai.conversation.AiConversationStoreService.ConversationRecoveryResult;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.ai.agent.runtime.AgentTaskService;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * AI 本地文件数据维护服务，提供流式备份、受控恢复和会话索引重建。
 *
 * <p>备份不解密受保护文件；恢复校验 ZIP 路径和总大小，防止目录穿越和压缩炸弹。</p>
 *
 * <p>by AI.Coding</p>
 */
@Service
public class AiLocalDataMaintenanceService {

    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern SAFE_BACKUP = Pattern.compile("ai-backup-\\d{8}-\\d{6}\\.zip");
    private final DevBridgeProperties properties;
    private final AiConversationStoreService conversationStore;
    private final Path backupRoot;
    private final AgentTaskService taskService;
    private final AiDataMaintenanceLock maintenanceLock;
    private final AiDeviceIncidentMemory memory;
    private final AiRagBoundary rag;

    /** 注入本地数据配置和会话 Store。 */
    @Autowired
    public AiLocalDataMaintenanceService(
            DevBridgeProperties properties,
            AiConversationStoreService conversationStore,
            AgentTaskService taskService,
            AiDataMaintenanceLock maintenanceLock,
            AiDeviceIncidentMemory memory,
            AiRagBoundary rag) {
        this.properties = properties;
        this.conversationStore = conversationStore;
        this.taskService = taskService;
        this.maintenanceLock = maintenanceLock;
        this.memory = memory;
        this.rag = rag;
        this.backupRoot = Path.of(properties.getAiConfigRoot()).toAbsolutePath().normalize().resolve("backups");
        createDirectory(backupRoot);
    }

    /** 创建兼容测试和显式装配的维护服务。 */
    public AiLocalDataMaintenanceService(
            DevBridgeProperties properties,
            AiConversationStoreService conversationStore,
            AgentTaskService taskService,
            AiDataMaintenanceLock maintenanceLock) {
        this(properties, conversationStore, taskService, maintenanceLock, null, null);
    }

    /** 创建兼容测试的维护服务。 */
    public AiLocalDataMaintenanceService(
            DevBridgeProperties properties,
            AiConversationStoreService conversationStore) {
        this(properties, conversationStore, null, new AiDataMaintenanceLock());
    }

    /** 查询当前格式、根目录和维护开关状态。 */
    public MaintenanceStatus status() {
        return maintenanceLock.read(this::statusUnlocked);
    }

    /** 在维护读锁内读取目录和备份状态，避免与恢复覆盖交叉扫描。 */
    private MaintenanceStatus statusUnlocked() {
        List<RootStatus> roots = new ArrayList<>();
        List<Path> values = dataRoots();
        for (int index = 0; index < values.size(); index++) {
            Path root = values.get(index);
            roots.add(new RootStatus(index, "root-" + index, Files.isDirectory(root), directorySize(root)));
        }
        return new MaintenanceStatus(
                properties.getAiFeatures().isLocalDataMaintenanceEnabled(), "1.0.0",
                "各 Store 启动时自动迁移；损坏单文件隔离", roots, listBackups());
    }

    /** 流式创建一次本地数据备份。 */
    public BackupResult backup() {
        return maintenanceLock.write(this::backupLocked);
    }

    /** 在阻塞业务文件写入期间创建一致备份。 */
    private BackupResult backupLocked() {
        requireEnabled();
        String fileName = "ai-backup-" + BACKUP_TIME.format(LocalDateTime.now()) + ".zip";
        Path target = backupRoot.resolve(fileName);
        Path temporary = backupRoot.resolve(fileName + ".tmp-" + UUID.randomUUID());
        long entries = 0;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
            List<Path> roots = dataRoots();
            for (int index = 0; index < roots.size(); index++) {
                entries += writeRoot(zip, roots.get(index), index);
            }
        } catch (IOException ex) {
            deleteQuietly(temporary);
            throw new IllegalStateException("AI 本地数据备份失败", ex);
        }
        moveAtomic(temporary, target);
        restrictPermissions(target);
        return new BackupResult(fileName, entries, fileSize(target));
    }

    /** 从指定备份恢复到当前配置的数据根目录。 */
    public RestoreResult restore(String fileName) {
        return maintenanceLock.write(() -> restoreLocked(fileName));
    }

    /** 在维护写锁内恢复文件并同步重建活动索引。 */
    private RestoreResult restoreLocked(String fileName) {
        requireEnabled();
        requireNoActiveTasks();
        Path backup = requireBackup(fileName);
        Path workRoot = temporaryRestoreRoot();
        Path staging = workRoot.resolve("staging");
        Path rollback = workRoot.resolve("rollback");
        createDirectory(staging);
        RestoreResult result;
        try {
            result = extractBackup(backup, staging);
            applyStaging(staging, rollback);
            if (conversationStore != null) {
                conversationStore.recoverIndex();
            }
            if (taskService != null) {
                taskService.recoverIndex();
            }
            if (memory != null) {
                memory.recoverIndex();
            }
            if (rag != null) {
                rag.recoverIndex();
            }
        } finally {
            deleteTree(workRoot);
        }
        return result;
    }

    /** 重建会话索引，单个损坏会话不会阻塞其它历史。 */
    public ConversationRecoveryResult recoverConversations() {
        return maintenanceLock.write(() -> {
            requireEnabled();
            return conversationStore.recoverIndex();
        });
    }

    /** 恢复前拒绝仍有活动 Agent Task，避免覆盖正在执行的状态和 Checkpoint。 */
    private void requireNoActiveTasks() {
        if (taskService == null) {
            return;
        }
        for (int page = 0; ; page++) {
            var tasks = taskService.listTasks(page, 100);
            boolean active = tasks.items().stream().anyMatch(task ->
                    task.state() != AgentTaskState.COMPLETED
                            && task.state() != AgentTaskState.FAILED
                            && task.state() != AgentTaskState.CANCELED);
            if (active) {
                throw new IllegalStateException("存在运行中 Agent Task，不能执行在线恢复");
            }
            if (tasks.items().size() < 100) {
                return;
            }
        }
    }

    /** 把一个数据根目录写入 ZIP，保持流式复制。 */
    private long writeRoot(ZipOutputStream zip, Path root, int rootIndex) throws IOException {
        if (!Files.isDirectory(root)) return 0;
        long count = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).filter(path -> !path.startsWith(backupRoot)).toList()) {
                if (Files.isSymbolicLink(file)) continue;
                String entryName = "root-" + rootIndex + "/" + root.relativize(file).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(file, zip);
                zip.closeEntry();
                count++;
            }
        }
        return count;
    }

    /** 解压到隔离暂存目录并校验总大小。 */
    private RestoreResult extractBackup(Path backup, Path staging) {
        long total = 0;
        long entries = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(backup))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Path target = safeStagingTarget(staging, entry.getName());
                Files.createDirectories(target.getParent());
                total += copyBounded(zip, target, properties.getStorageQuotaBytes() - total);
                entries++;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("AI 本地数据备份恢复失败", ex);
        }
        return new RestoreResult(backup.getFileName().toString(), entries, total, true);
    }

    /** 将暂存快照替换到当前数据根目录，失败时恢复替换前快照。 */
    private void applyStaging(Path staging, Path rollback) {
        List<Path> roots = dataRoots();
        snapshotCurrentData(roots, rollback);
        try {
            for (int index = 0; index < roots.size(); index++) {
                replaceRootSnapshot(staging.resolve("root-" + index), roots.get(index));
            }
        } catch (RuntimeException ex) {
            restoreRollback(roots, rollback);
            throw ex;
        }
    }

    /** 把当前受管理文件复制到工作目录，作为跨根目录恢复失败时的回滚基线。 */
    private void snapshotCurrentData(List<Path> roots, Path rollback) {
        for (int index = 0; index < roots.size(); index++) {
            copyManagedFiles(roots.get(index), rollback.resolve("root-" + index));
        }
    }

    /** 清理当前受管理文件并写入单个备份根目录的完整快照。 */
    private void replaceRootSnapshot(Path source, Path targetRoot) {
        clearManagedFiles(targetRoot);
        if (Files.isDirectory(source)) {
            copyManagedFiles(source, targetRoot);
        }
    }

    /** 清理部分恢复结果并把所有根目录恢复到替换前状态。 */
    private void restoreRollback(List<Path> roots, Path rollback) {
        try {
            for (int index = 0; index < roots.size(); index++) {
                clearManagedFiles(roots.get(index));
                Path source = rollback.resolve("root-" + index);
                if (Files.isDirectory(source)) {
                    copyManagedFiles(source, roots.get(index));
                }
            }
        } catch (RuntimeException ex) {
            throw new IllegalStateException("AI 本地数据恢复失败且回滚未完成", ex);
        }
    }

    /** 复制目录中的受管理普通文件；备份目录不属于恢复快照。 */
    private void copyManagedFiles(Path source, Path targetRoot) {
        if (!Files.isDirectory(source)) return;
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).filter(this::managedFile).toList()) {
                Path target = targetRoot.resolve(source.relativize(file)).normalize();
                if (!target.startsWith(targetRoot)) throw new IllegalArgumentException("恢复路径越界");
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("AI 本地数据快照复制失败", ex);
        }
    }

    /** 删除当前根目录内的受管理文件，确保备份后新增数据不会残留。 */
    private void clearManagedFiles(Path root) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).filter(this::managedFile).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("AI 本地数据快照清理失败", ex);
        }
    }

    /** 判断文件是否属于可备份和恢复的数据，维护备份文件始终排除。 */
    private boolean managedFile(Path file) {
        return !file.toAbsolutePath().normalize().startsWith(backupRoot);
    }

    /** 在系统临时目录创建恢复工作区，避免工作文件落入被替换的数据根。 */
    private Path temporaryRestoreRoot() {
        try {
            return Files.createTempDirectory("devbridge-ai-restore-");
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建 AI 数据恢复工作目录", ex);
        }
    }

    /** 复制单个 ZIP 条目并执行全局配额限制。 */
    private long copyBounded(InputStream input, Path target, long remaining) throws IOException {
        if (remaining <= 0) throw new IllegalArgumentException("备份解压大小超过存储配额");
        long written = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                written += read;
                if (written > remaining) throw new IllegalArgumentException("备份解压大小超过存储配额");
                output.write(buffer, 0, read);
            }
        }
        return written;
    }

    /** 返回去重后的数据根目录，父目录已包含子目录时不重复备份。 */
    private List<Path> dataRoots() {
        List<Path> roots = Stream.of(
                        properties.getAiConfigRoot(), properties.getAiAgentDataRoot(),
                        properties.getToolArtifactRoot(), properties.getToolAuditRoot())
                .map(value -> Path.of(value).toAbsolutePath().normalize())
                .distinct().sorted(Comparator.comparingInt(Path::getNameCount)).toList();
        List<Path> result = new ArrayList<>();
        for (Path root : roots) {
            if (result.stream().noneMatch(root::startsWith)) result.add(root);
        }
        return List.copyOf(result);
    }

    /** 校验 ZIP 条目只能落在暂存目录和已知 root-N 前缀内。 */
    private Path safeStagingTarget(Path staging, String entryName) {
        if (entryName == null || entryName.indexOf('\0') >= 0 || !entryName.matches("root-\\d+/.+")) {
            throw new IllegalArgumentException("备份条目格式无效");
        }
        int rootIndex = Integer.parseInt(entryName.substring(5, entryName.indexOf('/')));
        if (rootIndex < 0 || rootIndex >= dataRoots().size()) throw new IllegalArgumentException("备份根目录不匹配");
        Path target = staging.resolve(entryName).normalize();
        if (!target.startsWith(staging)) throw new IllegalArgumentException("备份条目路径越界");
        return target;
    }

    /** 查询现有备份文件名。 */
    private List<String> listBackups() {
        try (Stream<Path> files = Files.list(backupRoot)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString()).filter(name -> SAFE_BACKUP.matcher(name).matches())
                    .sorted(Comparator.reverseOrder()).limit(20).toList();
        } catch (IOException ex) {
            throw new IllegalStateException("AI 备份目录读取失败", ex);
        }
    }

    /** 校验备份文件名和存在性。 */
    private Path requireBackup(String fileName) {
        if (fileName == null || !SAFE_BACKUP.matcher(fileName).matches()) {
            throw new IllegalArgumentException("备份文件名无效");
        }
        Path file = backupRoot.resolve(fileName).normalize();
        if (!file.startsWith(backupRoot) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("备份文件不存在");
        }
        return file;
    }

    /** 计算目录占用，状态查询失败时返回零而不影响应用启动。 */
    private long directorySize(Path root) {
        if (!Files.isDirectory(root)) return 0;
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).mapToLong(this::fileSize).sum();
        } catch (IOException ex) {
            return 0;
        }
    }

    /** 原子移动文件，不支持原子移动的文件系统回退为替换移动。 */
    private void moveAtomic(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("AI 本地数据文件替换失败", ex);
        }
    }

    /** 创建目录。 */
    private void createDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException ex) {
            throw new IllegalStateException("AI 本地数据目录创建失败", ex);
        }
    }

    /** 限制备份文件为当前用户读写。 */
    private void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 等不支持 POSIX 权限的平台继续依赖用户目录 ACL。
        }
    }

    /** 删除暂存目录。 */
    private void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException ignored) {
            // 恢复主结果优先，残留暂存目录可由下次维护清理。
        }
    }

    /** 尽力删除文件。 */
    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不能覆盖原始备份或恢复异常。
        }
    }

    /** 返回文件大小，读取失败时返回零。 */
    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return 0;
        }
    }

    /** 校验维护能力开关。 */
    private void requireEnabled() {
        if (!properties.getAiFeatures().isLocalDataMaintenanceEnabled()) {
            throw new IllegalStateException("AI 本地数据维护能力已关闭");
        }
    }

    /** 本地维护状态。by AI.Coding */
    public record MaintenanceStatus(boolean enabled, String schemaVersion, String migrationPolicy,
                                    List<RootStatus> roots, List<String> backups) {
    }

    /** 数据根目录状态。by AI.Coding */
    public record RootStatus(int index, String label, boolean available, long sizeBytes) {
    }

    /** 备份结果。by AI.Coding */
    public record BackupResult(String fileName, long entries, long sizeBytes) {
    }

    /** 恢复结果；完成后建议重启以重新加载所有内存索引。by AI.Coding */
    public record RestoreResult(String fileName, long entries, long sizeBytes, boolean restartRecommended) {
    }
}
