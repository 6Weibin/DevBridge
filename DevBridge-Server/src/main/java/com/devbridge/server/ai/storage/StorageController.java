package com.devbridge.server.ai.storage;

import com.devbridge.server.ai.storage.StorageManager.CleanupResult;
import com.devbridge.server.ai.storage.StorageManager.StorageSnapshot;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存储占用和显式清理接口，受本地控制面认证保护。
 *
 * <p>by AI.Coding</p>
 */
@RestController
@RequestMapping("/api/ai/storage")
public class StorageController {

    private final StorageManager storageManager;
    private final AiLocalDataMaintenanceService maintenanceService;

    /**
     * 注入 Storage Manager。
     *
     * @param storageManager 存储管理器
     * @param maintenanceService 本地数据维护服务
     */
    public StorageController(
            StorageManager storageManager,
            AiLocalDataMaintenanceService maintenanceService) {
        this.storageManager = storageManager;
        this.maintenanceService = maintenanceService;
    }

    /**
     * 查询分类占用和阈值状态。
     *
     * @return 存储快照
     */
    @GetMapping
    public StorageSnapshot snapshot() {
        return storageManager.snapshot();
    }

    /**
     * 显式触发阈值清理，受保护路径不会删除。
     *
     * @param request 清理请求
     * @return 清理结果
     */
    @PostMapping("/cleanup")
    public CleanupResult cleanup(@RequestBody(required = false) CleanupRequest request) {
        Set<Path> protectedPaths = request == null || request.protectedPaths() == null
                ? Set.of()
                : request.protectedPaths().stream().map(Path::of).collect(Collectors.toUnmodifiableSet());
        return storageManager.cleanup(protectedPaths);
    }

    /** 查询本地文件格式、占用、迁移策略和备份列表。 */
    @GetMapping("/maintenance")
    public AiLocalDataMaintenanceService.MaintenanceStatus maintenance() {
        return maintenanceService.status();
    }

    /** 显式创建流式本地备份。 */
    @PostMapping("/maintenance/backups")
    public AiLocalDataMaintenanceService.BackupResult backup() {
        return maintenanceService.backup();
    }

    /** 从指定受控备份恢复文件；完成后建议重启服务。 */
    @PostMapping("/maintenance/backups/restore")
    public AiLocalDataMaintenanceService.RestoreResult restore(@RequestBody RestoreRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("备份文件名不能为空");
        }
        return maintenanceService.restore(request.fileName());
    }

    /** 从独立会话文件重建历史索引并隔离损坏文件。 */
    @PostMapping("/maintenance/conversations/recover")
    public com.devbridge.server.ai.conversation.AiConversationStoreService.ConversationRecoveryResult recoverConversations() {
        return maintenanceService.recoverConversations();
    }

    public record CleanupRequest(List<String> protectedPaths) {
    }

    /** 本地备份恢复请求。by AI.Coding */
    public record RestoreRequest(String fileName) {
    }
}
