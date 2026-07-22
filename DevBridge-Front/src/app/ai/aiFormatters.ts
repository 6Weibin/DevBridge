/**
 * AI 聊天纯格式化函数，避免控制组件承担标题、时间和日志报告拼装。
 *
 * by AI.Coding
 */
import { AiLogAnalysisResponse, AiMessage } from "./aiTypes";

const DEFAULT_TITLE = "新对话";

/** 根据首条用户消息生成有界会话标题。 */
export function deriveConversationTitle(messages: AiMessage[]) {
  const firstUserMessage = messages.find(message => message.role === "user" && message.content.trim());
  if (!firstUserMessage) return DEFAULT_TITLE;
  const normalized = firstUserMessage.content.trim().replace(/\s+/g, " ");
  return normalized.length > 24 ? `${normalized.slice(0, 24)}...` : normalized;
}

/** 返回会话展示标题。 */
export function conversationTitle(conversation: { title: string; messages: AiMessage[] }) {
  return conversation.title && conversation.title !== DEFAULT_TITLE
    ? conversation.title
    : deriveConversationTitle(conversation.messages);
}

/** 格式化历史聊天更新时间。 */
export function formatConversationTime(value: number) {
  if (!Number.isFinite(value)) return "";
  return new Date(value).toLocaleString("zh-CN", {
    month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit",
  });
}

/** 创建不依赖浏览器持久化的会话标识。 */
export function createConversationId() {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/** 把结构化日志分析结果整理成 Markdown。 */
export function formatAnalysisResult(response: AiLogAnalysisResponse) {
  const truncated = response.context.truncated ? "，已截断" : "";
  const evidence = response.evidence.length > 0 ? response.evidence.map(item => `- ${item}`).join("\n") : "- 无明确证据日志";
  const actions = response.actions.length > 0 ? response.actions.map(item => `- ${item}`).join("\n") : "- 继续补充日志后重新分析";
  return [
    `问题摘要：${response.summary}`,
    `关键证据日志：\n${evidence}`,
    `原因判断：${response.cause}`,
    `建议操作：\n${actions}`,
    `置信度：${response.confidence}`,
    `上下文：${response.context.device}，${response.context.platform}，${response.context.logRange}，${response.context.logCount} 行${truncated}`,
    "限制：最多 500 行 / 60000 字符",
  ].join("\n\n");
}
