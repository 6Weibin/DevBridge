package com.devbridge.server.ai.agent.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.ai.agent.model.AgentTask;
import com.devbridge.server.ai.agent.model.AgentTaskState;
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地 Agent Task Store 测试，覆盖原子持久化、分页和损坏隔离。
 *
 * <p>by AI.Coding</p>
 */
class FileAgentTaskStoreTest {

    /**
     * 验证重新创建 Store 后仍能读取最后完整任务快照。
     *
     * @param tempDir 临时存储目录
     */
    @Test
    void storeShouldRecoverTaskAfterRestart(@TempDir Path tempDir) {
        FileAgentTaskStore first = store(tempDir);
        AgentTask created = task("task-restart", 1L, AgentTaskState.CREATED, "任务已创建");
        first.save(created);
        AgentTask running = task("task-restart", 2L, AgentTaskState.PLANNING, "开始规划");
        first.update(running, 1L);

        FileAgentTaskStore restarted = store(tempDir);

        assertThat(restarted.findById("task-restart")).contains(running);
    }

    /**
     * 验证状态更新会保留追加历史，便于恢复和审计。
     *
     * @param tempDir 临时存储目录
     * @throws Exception 文件读取失败时抛出
     */
    @Test
    void storeShouldAppendTaskHistory(@TempDir Path tempDir) throws Exception {
        FileAgentTaskStore store = store(tempDir);
        store.save(task("task-history", 1L, AgentTaskState.CREATED, "任务已创建"));
        store.update(task("task-history", 2L, AgentTaskState.PLANNING, "开始规划"), 1L);

        Path history = findFile(tempDir, "history.ndjson");

        assertThat(Files.readAllLines(history)).hasSize(2);
    }

    /**
     * 验证旧版本不能覆盖较新的任务快照。
     *
     * @param tempDir 临时存储目录
     */
    @Test
    void storeShouldRejectVersionConflict(@TempDir Path tempDir) {
        FileAgentTaskStore store = store(tempDir);
        store.save(task("task-version", 1L, AgentTaskState.CREATED, "任务已创建"));

        assertThatThrownBy(() -> store.update(
                task("task-version", 3L, AgentTaskState.RUNNING, "错误版本"), 2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("版本冲突");
    }

    /**
     * 验证至少 1000 个任务可以稳定分页，不需要一次返回全部数据。
     *
     * @param tempDir 临时存储目录
     */
    @Test
    void storeShouldPageMoreThanOneThousandTasks(@TempDir Path tempDir) {
        FileAgentTaskStore store = store(tempDir);
        for (int index = 0; index < 1_005; index++) {
            store.save(task("task-page-" + index, 1L, AgentTaskState.CREATED, "任务已创建"));
        }

        AgentTaskPage first = store.list(0, 100);
        AgentTaskPage last = store.list(10, 100);

        assertThat(first.items()).hasSize(100);
        assertThat(first.total()).isEqualTo(1_005L);
        assertThat(last.items()).hasSize(5);
    }

    /**
     * 验证残留临时文件不会覆盖最后完整快照。
     *
     * @param tempDir 临时存储目录
     * @throws Exception 文件写入失败时抛出
     */
    @Test
    void storeShouldIgnoreIncompleteTemporaryFile(@TempDir Path tempDir) throws Exception {
        FileAgentTaskStore store = store(tempDir);
        AgentTask task = task("task-temp", 1L, AgentTaskState.CREATED, "任务已创建");
        store.save(task);
        Path taskFile = findFile(tempDir, "task.json");
        Files.writeString(taskFile.resolveSibling("task.json.tmp-crash"), "{broken");

        FileAgentTaskStore restarted = store(tempDir);

        assertThat(restarted.findById("task-temp")).contains(task);
    }

    /**
     * 验证单个任务损坏后，其余任务仍可在重启时加载。
     *
     * @param tempDir 临时存储目录
     * @throws Exception 文件写入失败时抛出
     */
    @Test
    void storeShouldIsolateCorruptTask(@TempDir Path tempDir) throws Exception {
        FileAgentTaskStore store = store(tempDir);
        store.save(task("task-good", 1L, AgentTaskState.CREATED, "任务已创建"));
        store.save(task("task-broken", 1L, AgentTaskState.CREATED, "任务已创建"));
        Path broken = findTaskFile(tempDir, "task-broken");
        Files.writeString(broken, "{broken");

        FileAgentTaskStore restarted = store(tempDir);

        assertThat(restarted.findById("task-good")).isPresent();
        assertThat(restarted.list(0, 100).total()).isEqualTo(1L);
        try (var markers = Files.list(tempDir.resolve("quarantine"))) {
            assertThat(markers).isNotEmpty();
        }
    }

    /**
     * 创建文件 Store。
     *
     * @param root 存储根目录
     * @return 文件 Store
     */
    private FileAgentTaskStore store(Path root) {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setAiAgentDataRoot(root.toString());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new FileAgentTaskStore(properties, new AgentTaskFileCodec(mapper));
    }

    /**
     * 创建固定任务快照。
     *
     * @param taskId 任务标识
     * @param version 任务版本
     * @param state 任务状态
     * @param reason 状态原因
     * @return 任务快照
     */
    private AgentTask task(String taskId, long version, AgentTaskState state, String reason) {
        Instant now = Instant.parse("2026-07-14T00:00:00Z").plusSeconds(version);
        return new AgentTask(taskId, "conversation", "测试任务", state, reason, version, now, now);
    }

    /**
     * 查找指定文件名的第一个文件。
     *
     * @param root 搜索根目录
     * @param fileName 文件名
     * @return 匹配文件
     * @throws Exception 文件搜索失败时抛出
     */
    private Path findFile(Path root, String fileName) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().equals(fileName)).findFirst().orElseThrow();
        }
    }

    /**
     * 按任务标识查找对应快照文件。
     *
     * @param root 搜索根目录
     * @param taskId 任务标识
     * @return 任务快照文件
     * @throws Exception 文件搜索失败时抛出
     */
    private Path findTaskFile(Path root, String taskId) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().equals("task.json"))
                    .filter(path -> path.toString().contains(taskId))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
