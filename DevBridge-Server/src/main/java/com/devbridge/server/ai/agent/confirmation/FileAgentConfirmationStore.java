package com.devbridge.server.ai.agent.confirmation;

import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.ai.storage.AiDataMaintenanceLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Agent 确认本地文件 Store，每个确认记录独立原子更新。
 *
 * <p>by AI.Coding</p>
 */
@Repository
public class FileAgentConfirmationStore implements AgentConfirmationStore {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,128}");
    private static final int LOCK_STRIPES = 64;

    private final Path root;
    private final AgentConfirmationFileCodec codec;
    private final AiDataMaintenanceLock maintenanceLock;
    private final Object[] locks = createLocks();

    /**
     * 初始化确认 Store。
     *
     * @param properties 应用配置
     * @param codec 确认编解码器
     */
    @Autowired
    public FileAgentConfirmationStore(
            DevBridgeProperties properties,
            AgentConfirmationFileCodec codec,
            AiDataMaintenanceLock maintenanceLock) {
        this.root = Path.of(properties.getAiAgentDataRoot()).toAbsolutePath().normalize();
        this.codec = codec;
        this.maintenanceLock = maintenanceLock;
    }

    /** 创建兼容测试的确认 Store。 */
    public FileAgentConfirmationStore(
            DevBridgeProperties properties, AgentConfirmationFileCodec codec) {
        this(properties, codec, new AiDataMaintenanceLock());
    }

    /**
     * 保存新的确认记录，禁止覆盖相同确认标识。
     *
     * @param confirmation 确认记录
     * @return 已保存记录
     */
    @Override
    public AgentConfirmation save(AgentConfirmation confirmation) {
        return maintenanceLock.read(() -> saveUnlocked(confirmation));
    }

    /** 在维护读锁内保存确认记录。 */
    private AgentConfirmation saveUnlocked(AgentConfirmation confirmation) {
        validateIds(confirmation.taskId(), confirmation.confirmationId());
        synchronized (lockFor(confirmation.confirmationId())) {
            Path file = confirmationFile(confirmation.taskId(), confirmation.confirmationId());
            if (Files.exists(file)) {
                throw new IllegalStateException("确认记录已经存在: " + confirmation.confirmationId());
            }
            codec.write(file, confirmation);
            return confirmation;
        }
    }

    /**
     * 按预期状态条件更新确认记录，防止重复决策覆盖。
     *
     * @param confirmation 新确认记录
     * @param expectedStatus 预期旧状态
     * @return 已更新记录
     */
    @Override
    public AgentConfirmation update(
            AgentConfirmation confirmation, AgentConfirmationStatus expectedStatus) {
        return maintenanceLock.read(() -> updateUnlocked(confirmation, expectedStatus));
    }

    /** 在维护读锁内条件更新确认记录。 */
    private AgentConfirmation updateUnlocked(
            AgentConfirmation confirmation, AgentConfirmationStatus expectedStatus) {
        validateIds(confirmation.taskId(), confirmation.confirmationId());
        synchronized (lockFor(confirmation.confirmationId())) {
            Path file = confirmationFile(confirmation.taskId(), confirmation.confirmationId());
            if (!Files.exists(file)) {
                throw new IllegalArgumentException("确认记录不存在: " + confirmation.confirmationId());
            }
            AgentConfirmation current = codec.read(file);
            if (current.status() != expectedStatus) {
                throw new IllegalStateException("确认记录状态冲突: " + current.status());
            }
            codec.write(file, confirmation);
            return confirmation;
        }
    }

    /**
     * 查询确认记录。
     *
     * @param taskId 任务标识
     * @param confirmationId 确认标识
     * @return 确认记录
     */
    @Override
    public Optional<AgentConfirmation> find(String taskId, String confirmationId) {
        return maintenanceLock.read(() -> findUnlocked(taskId, confirmationId));
    }

    /** 在维护读锁内读取确认记录。 */
    private Optional<AgentConfirmation> findUnlocked(String taskId, String confirmationId) {
        validateIds(taskId, confirmationId);
        Path file = confirmationFile(taskId, confirmationId);
        return Files.exists(file) ? Optional.of(codec.read(file)) : Optional.empty();
    }

    /**
     * 获取确认记录文件。
     *
     * @param taskId 任务标识
     * @param confirmationId 确认标识
     * @return 确认文件
     */
    private Path confirmationFile(String taskId, String confirmationId) {
        String compact = taskId.replace("-", "");
        String first = compact.substring(0, Math.min(2, compact.length()));
        String second = compact.length() > 2 ? compact.substring(2, Math.min(4, compact.length())) : "00";
        return root.resolve("tasks").resolve(first).resolve(second).resolve(taskId)
                .resolve("confirmations").resolve(confirmationId + ".json");
    }

    /**
     * 校验任务和确认标识，阻断路径穿越。
     *
     * @param taskId 任务标识
     * @param confirmationId 确认标识
     */
    private void validateIds(String taskId, String confirmationId) {
        if (taskId == null || !SAFE_ID.matcher(taskId).matches()
                || confirmationId == null || !SAFE_ID.matcher(confirmationId).matches()) {
            throw new IllegalArgumentException("任务或确认标识不合法");
        }
    }

    /**
     * 获取固定锁分片。
     *
     * @param confirmationId 确认标识
     * @return 锁对象
     */
    private Object lockFor(String confirmationId) {
        return locks[Math.floorMod(confirmationId.hashCode(), locks.length)];
    }

    /**
     * 初始化固定锁分片。
     *
     * @return 锁分片
     */
    private static Object[] createLocks() {
        Object[] values = new Object[LOCK_STRIPES];
        for (int index = 0; index < values.length; index++) {
            values[index] = new Object();
        }
        return values;
    }
}
