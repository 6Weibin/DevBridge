package com.devbridge.server.ai.agent.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Agent Event Sequencer 测试，覆盖并发序号、重启高水位和游标补发。
 *
 * <p>by AI.Coding</p>
 */
class AgentEventSequencerTest {

    /**
     * 验证同一任务并发发布事件时序号连续且不重复。
     *
     * @param tempDir 临时存储目录
     * @throws Exception 并发执行失败时抛出
     */
    @Test
    void sequencerShouldAssignMonotonicSequenceUnderConcurrency(@TempDir Path tempDir) throws Exception {
        AgentEventSequencer sequencer = runtime(tempDir).sequencer();
        List<Callable<AgentEvent>> calls = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            calls.add(() -> sequencer.publish("task-concurrent", request(AgentEventType.STEP_PROGRESS)));
        }

        List<AgentEvent> events;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            events = executor.invokeAll(calls).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }).toList();
        } finally {
            executor.shutdownNow();
        }

        assertThat(events).extracting(AgentEvent::eventSequence)
                .containsExactlyInAnyOrderElementsOf(java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList());
        assertThat(runtime(tempDir).store().readAfter("task-concurrent", 0L, 100))
                .extracting(AgentEvent::eventSequence)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList());
    }

    /**
     * 验证后端重启后从磁盘高水位继续分配序号。
     *
     * @param tempDir 临时存储目录
     */
    @Test
    void sequencerShouldContinueAfterRestart(@TempDir Path tempDir) {
        TestRuntime first = runtime(tempDir);
        first.sequencer().publish("task-restart", request(AgentEventType.TASK_CREATED));
        first.sequencer().publish("task-restart", request(AgentEventType.TASK_STATE_CHANGED));

        AgentEvent next = runtime(tempDir).sequencer().publish(
                "task-restart", request(AgentEventType.STEP_STARTED));

        assertThat(next.eventSequence()).isEqualTo(3L);
    }

    /**
     * 验证客户端可以从最后已处理游标后补发事件。
     *
     * @param tempDir 临时存储目录
     */
    @Test
    void storeShouldReplayAfterCursor(@TempDir Path tempDir) {
        TestRuntime runtime = runtime(tempDir);
        for (int index = 0; index < 5; index++) {
            runtime.sequencer().publish("task-replay", request(AgentEventType.OUTPUT_DELTA));
        }

        List<AgentEvent> replay = runtime.store().readAfter("task-replay", 2L, 10);

        assertThat(replay).extracting(AgentEvent::eventSequence).containsExactly(3L, 4L, 5L);
    }

    /** 已持久化事件类型计数可供确认、输入和暂停续跑恢复任务级预算。 */
    @Test
    void sequencerShouldCountPersistedBudgetEvents(@TempDir Path tempDir) {
        AgentEventSequencer sequencer = runtime(tempDir).sequencer();
        sequencer.publish("task-budget", request(AgentEventType.TOOL_STARTED));
        sequencer.publish("task-budget", request(AgentEventType.MODEL_CALL_STARTED));
        sequencer.publish("task-budget", request(AgentEventType.TOOL_STARTED));

        assertThat(runtime(tempDir).sequencer().count("task-budget", AgentEventType.TOOL_STARTED)).isEqualTo(2);
        assertThat(runtime(tempDir).sequencer().count("task-budget", AgentEventType.MODEL_CALL_STARTED)).isEqualTo(1);
    }

    /** 确认续跑应选择同步骤最近完成的工具调用。 */
    @Test
    void sequencerShouldFindLatestCompletedToolCall(@TempDir Path tempDir) {
        AgentEventSequencer sequencer = runtime(tempDir).sequencer();
        sequencer.publish("task-tool-replay", toolCompletedRequest("tool-call-old"));
        sequencer.publish("task-tool-replay", toolCompletedRequest("tool-call-latest"));

        assertThat(sequencer.latestCompletedToolCallId("task-tool-replay", "step"))
                .isEqualTo("tool-call-latest");
    }

    /**
     * 验证不同任务分别从序号一开始，不共享全局序号。
     *
     * @param tempDir 临时存储目录
     */
    @Test
    void sequencerShouldKeepIndependentTaskSequences(@TempDir Path tempDir) {
        AgentEventSequencer sequencer = runtime(tempDir).sequencer();

        AgentEvent first = sequencer.publish("task-a", request(AgentEventType.TASK_CREATED));
        AgentEvent second = sequencer.publish("task-b", request(AgentEventType.TASK_CREATED));

        assertThat(first.eventSequence()).isEqualTo(1L);
        assertThat(second.eventSequence()).isEqualTo(1L);
    }

    /**
     * 验证工具事件必须同时关联步骤和独立工具调用标识。
     *
     * @param tempDir 临时存储目录
     */
    @Test
    void sequencerShouldRejectToolEventWithoutToolCallId(@TempDir Path tempDir) {
        AgentEventContext context = new AgentEventContext(
                "conversation", "turn", "step", null, null, null, 1L);
        AgentEventRequest request = new AgentEventRequest(
                AgentEventType.TOOL_STARTED, AgentEventScope.TOOL_CALL,
                context, Map.of(), Instant.now(), "tool-gateway");

        assertThatThrownBy(() -> runtime(tempDir).sequencer().publish("task-tool", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolCallId");
    }

    /**
     * 创建默认事件请求。
     *
     * @param type 事件类型
     * @return 事件请求
     */
    private AgentEventRequest request(AgentEventType type) {
        AgentEventContext context = new AgentEventContext(
                "conversation", "turn", "step", "tool-call", "confirmation", "model-call", 1L);
        return new AgentEventRequest(type, scope(type), context, Map.of("message", "测试"), Instant.now(), "test");
    }

    /** 创建指定工具调用的完成事件。 */
    private AgentEventRequest toolCompletedRequest(String toolCallId) {
        AgentEventContext context = new AgentEventContext(
                "conversation", "turn", "step", toolCallId, null, null, 1L);
        return new AgentEventRequest(
                AgentEventType.TOOL_COMPLETED, AgentEventScope.TOOL_CALL,
                context, Map.of(), Instant.now(), "tool-gateway");
    }

    /**
     * 根据测试事件类型选择作用域。
     *
     * @param type 事件类型
     * @return 事件作用域
     */
    private AgentEventScope scope(AgentEventType type) {
        if (type.name().startsWith("TOOL_")) {
            return AgentEventScope.TOOL_CALL;
        }
        if (type.name().startsWith("OUTPUT_")) {
            return AgentEventScope.OUTPUT;
        }
        if (type.name().startsWith("STEP_")) {
            return AgentEventScope.STEP;
        }
        return AgentEventScope.TASK;
    }

    /**
     * 创建文件事件运行环境。
     *
     * @param root 临时存储目录
     * @return 测试运行环境
     */
    private TestRuntime runtime(Path root) {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setAiAgentDataRoot(root.toString());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileAgentEventStore store = new FileAgentEventStore(properties, new AgentEventFileCodec(mapper));
        return new TestRuntime(store, new AgentEventSequencer(store, event -> { }));
    }

    /**
     * 测试事件依赖集合。
     *
     * @param store 事件 Store
     * @param sequencer 事件序列器
     */
    private record TestRuntime(FileAgentEventStore store, AgentEventSequencer sequencer) {
    }
}
