package com.devbridge.server.ai.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.config.AiConfigCrypto;
import com.devbridge.server.ai.conversation.AiConversationStoreService.ConversationMigrationItem;
import com.devbridge.server.ai.conversation.AiConversationStoreService.ConversationMigrationRequest;
import com.devbridge.server.ai.conversation.AiConversationStoreService.ConversationWriteRequest;
import com.devbridge.server.ai.storage.StorageManager;
import com.devbridge.server.config.DevBridgeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 历史聊天文件服务测试，只覆盖消息保留、千会话分页和索引恢复三个业务闭环。
 *
 * <p>by AI.Coding</p>
 */
class AiConversationStoreServiceTest {

    /**
     * 验证有界消息尾部更新不会覆盖旧消息，并支持删除会话。
     */
    @Test
    void shouldMergeMessageTailAndDeleteConversation(@TempDir Path tempDir) {
        TestStore testStore = store(tempDir);
        AiConversationStoreService service = testStore.service();
        service.upsert("conversation-1", write(
                List.of(message(testStore.mapper(), 1, "一"), message(testStore.mapper(), 2, "二"),
                        message(testStore.mapper(), 3, "三")), 100L));

        service.upsert("conversation-1", write(
                List.of(message(testStore.mapper(), 3, "三-更新"), message(testStore.mapper(), 4, "四")), 200L));

        var detail = service.get("conversation-1", 2);
        assertThat(detail.messageCount()).isEqualTo(4);
        assertThat(detail.hasMoreMessages()).isTrue();
        assertThat(detail.messages()).extracting(node -> node.path("content").asText())
                .containsExactly("三-更新", "四");

        service.delete("conversation-1");
        assertThat(service.list(0, 100).total()).isZero();
    }

    /**
     * 验证一次迁移 1000 个以上会话后仍可按摘要分页，不加载正文。
     */
    @Test
    void shouldPageMoreThanOneThousandConversations(@TempDir Path tempDir) {
        TestStore testStore = store(tempDir);
        List<ConversationMigrationItem> items = new ArrayList<>();
        for (int index = 0; index < 1_005; index++) {
            items.add(new ConversationMigrationItem(
                    "conversation-" + index, "会话 " + index, false,
                    List.of(message(testStore.mapper(), index + 1, "消息")),
                    index + 1L, index + 1L));
        }

        testStore.service().migrate(new ConversationMigrationRequest(items, "conversation-1004"));

        assertThat(testStore.service().list(0, 100).items()).hasSize(100);
        assertThat(testStore.service().list(10, 100).items()).hasSize(5);
        assertThat(testStore.service().list(10, 100).total()).isEqualTo(1_005L);
    }

    /**
     * 验证加密索引损坏后可从独立会话文件重建，不丢失历史正文。
     */
    @Test
    void shouldRebuildCorruptIndex(@TempDir Path tempDir) throws Exception {
        TestStore first = store(tempDir);
        first.service().upsert("conversation-rebuild", write(
                List.of(message(first.mapper(), 1, "可恢复消息")), 100L));
        Files.writeString(tempDir.resolve("ai/conversations/index.enc"), "broken-index");

        TestStore restarted = store(tempDir);

        assertThat(restarted.service().list(0, 100).total()).isEqualTo(1L);
        assertThat(restarted.service().get("conversation-rebuild", 100).messages())
                .extracting(node -> node.path("content").asText())
                .containsExactly("可恢复消息");
    }

    /**
     * 创建隔离临时目录中的文件服务。
     */
    private TestStore store(Path root) {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setAiConfigRoot(root.resolve("ai").toString());
        properties.setAiAgentDataRoot(root.resolve("agent").toString());
        properties.setToolArtifactRoot(root.resolve("artifacts").toString());
        properties.setToolAuditRoot(root.resolve("audit").toString());
        properties.setLogCaptureRoot(root.resolve("logs").toString());
        properties.setDownloadTempRoot(root.resolve("downloads").toString());
        properties.setStorageQuotaBytes(1024L * 1024L * 1024L);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StorageManager storage = new StorageManager(properties, mapper);
        return new TestStore(
                new AiConversationStoreService(
                        properties, mapper, new AiConfigCrypto(), storage,
                        new AiConversationSummaryService()),
                mapper);
    }

    /**
     * 构造固定会话写入请求。
     */
    private ConversationWriteRequest write(List<JsonNode> messages, long updatedAt) {
        return new ConversationWriteRequest("测试会话", false, messages, 1L, updatedAt, true);
    }

    /**
     * 构造带稳定 ID 的前端消息 JSON。
     */
    private JsonNode message(ObjectMapper mapper, int id, String content) {
        return mapper.createObjectNode()
                .put("id", id)
                .put("role", "user")
                .put("kind", "text")
                .put("content", content);
    }

    /** 测试服务和 JSON 工具。by AI.Coding */
    private record TestStore(AiConversationStoreService service, ObjectMapper mapper) {
    }
}
