package com.devbridge.server.ai.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 对话摘要测试，覆盖约束与工具保留、来源追踪和版本重建。
 *
 * <p>by AI.Coding</p>
 */
class AiConversationSummaryServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AiConversationSummaryService service = new AiConversationSummaryService();

    /**
     * 验证摘要保留明确用户约束和工具执行结果，并记录来源范围。
     */
    @Test
    void shouldPreserveConstraintsAndToolResults() {
        List<JsonNode> messages = messages(60);
        messages.set(2, text(3, "user", "必须只输出 Markdown 表格，不要省略失败项"));
        messages.set(8, tool(9, "ADB 工具", "adb shell pm list packages", "SUCCESS", "查询完成"));

        var summary = service.summarize(messages, null);

        assertThat(summary.version()).isEqualTo(1);
        assertThat(summary.sourceMessageCount()).isEqualTo(20);
        assertThat(summary.sourceFirstMessageId()).isEqualTo("1");
        assertThat(summary.sourceLastMessageId()).isEqualTo("20");
        assertThat(summary.conversationSummary())
                .contains("长期用户约束")
                .contains("必须只输出 Markdown 表格");
        assertThat(summary.taskSummary())
                .contains("ADB 工具")
                .contains("adb shell pm list packages")
                .contains("SUCCESS");
    }

    /**
     * 验证来源不变时复用版本，已摘要来源发生变化时递增版本并更新校验值。
     */
    @Test
    void shouldReuseOrRebuildSummaryBySourceDigest() {
        List<JsonNode> messages = messages(60);
        var first = service.summarize(messages, null);

        var reused = service.summarize(messages, first);
        messages.set(0, text(1, "user", "修改后的历史问题"));
        var rebuilt = service.summarize(messages, reused);

        assertThat(reused).isEqualTo(first);
        assertThat(rebuilt.version()).isEqualTo(first.version() + 1);
        assertThat(rebuilt.sourceDigest()).isNotEqualTo(first.sourceDigest());
        assertThat(rebuilt.conversationSummary()).contains("修改后的历史问题");
    }

    /**
     * 构造交替用户和 AI 文本的会话。
     */
    private List<JsonNode> messages(int count) {
        List<JsonNode> messages = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            messages.add(text(index, index % 2 == 0 ? "assistant" : "user", "历史消息 " + index));
        }
        return messages;
    }

    /**
     * 构造普通文本消息。
     */
    private JsonNode text(int id, String role, String content) {
        return mapper.createObjectNode()
                .put("id", id)
                .put("role", role)
                .put("kind", "text")
                .put("content", content);
    }

    /**
     * 构造独立工具结果消息。
     */
    private JsonNode tool(int id, String title, String command, String status, String outcome) {
        var message = mapper.createObjectNode()
                .put("id", id)
                .put("role", "assistant")
                .put("kind", "tool")
                .put("content", "");
        message.putObject("toolResult")
                .put("toolTitle", title)
                .put("commandSummary", command)
                .put("status", status)
                .put("message", outcome);
        return message;
    }
}
