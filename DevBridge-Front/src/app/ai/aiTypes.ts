/**
 * AI 助手前端类型定义，保持与后端 /api/ai 契约一致。
 *
 * by AI.Coding
 */

export type AiProvider = "openai" | "deepseek" | "qwen" | "glm" | "ernie" | "custom-openai-compatible";

export interface AiConfigStatus {
  configured: boolean;
  provider: string;
  model: string;
  apiUrlHost: string;
  updatedAt: string | null;
}

export interface AiConfigRequest {
  provider: AiProvider;
  apiUrl: string;
  apiKey: string;
  model: string;
  systemPrompt: string;
  localShellAuthorizations: AiCommandAuthorizationRule[];
}

export interface AiConfigDetail {
  configured: boolean;
  provider: string;
  apiUrl: string;
  apiKey: string;
  model: string;
  systemPrompt: string;
  localShellAuthorizations: AiCommandAuthorizationRule[];
  updatedAt: string | null;
}

export type AiCommandAuthorizationLevel = "LOW" | "MEDIUM" | "HIGH";

export interface AiCommandAuthorizationRule {
  command: string;
  level: AiCommandAuthorizationLevel;
}

export interface AiConnectionTestResult {
  available: boolean;
  message: string;
  provider: string;
  model: string;
}

export interface AiModelListRequest {
  provider: AiProvider;
  apiUrl: string;
  apiKey: string;
}

export interface AiModelListResponse {
  provider: string;
  models: string[];
}

export interface AiDeviceContext {
  platform: string;
  serial: string;
  model: string;
  osVersion: string;
  status: string;
}

export interface AiLogLine {
  timestamp: string;
  level: string;
  pid: string;
  tag: string;
  message: string;
}

export interface AiChatRequest {
  message: string;
  deviceContext: AiDeviceContext | null;
  conversationId: string;
  /** 普通请求固定为空；确认恢复由后端处理，历史正文不再由前端回传。 */
  history: AiChatHistoryMessage[];
}

export interface AiChatHistoryMessage {
  role: "user" | "assistant";
  content: string;
}

export interface AiChatResponse {
  answer: string;
  provider: string;
  model: string;
  elapsedMillis: number;
}

export type AgentTaskState = "CREATED" | "PLANNING" | "RUNNING" | "WAITING_CONFIRMATION"
  | "WAITING_INPUT" | "PAUSED" | "RETRYING" | "COMPLETED" | "FAILED" | "CANCELED";

export interface AgentTaskResult {
  taskId: string;
  state: AgentTaskState;
  answer: string;
  failure?: {
    errorCode: string;
    source: string;
    stage: string;
    summary: string;
    retryable: boolean;
    completedSteps: string[];
    possibleImpact: string;
    suggestedAction: string;
  };
}

/** Agent Task 的脱敏 Trace 聚合。 */
export interface AgentTrace {
  taskId: string;
  conversationId: string;
  state: string;
  createdAt: string;
  updatedAt: string;
  metrics: {
    modelCalls: number;
    toolCalls: number;
    errors: number;
    events: number;
    auditRecords: number;
  };
  model: Record<string, unknown>;
  rag: Record<string, unknown>;
  events: Array<{
    eventId: string;
    eventSequence: string;
    eventType: string;
    scope: string;
    payload: Record<string, unknown>;
  }>;
  toolAudits: Array<Record<string, unknown>>;
}

export interface AgentTaskStatus {
  taskId: string;
  state: AgentTaskState;
}

export interface AiLogAnalysisRequest {
  question: string;
  deviceContext: AiDeviceContext | null;
  logs: AiLogLine[];
  limits: {
    maxLines: number;
    maxCharacters: number;
  };
}

export interface AiLogAnalysisResponse {
  summary: string;
  evidence: string[];
  cause: string;
  actions: string[];
  confidence: string;
  context: {
    platform: string;
    device: string;
    logRange: string;
    logCount: number;
    truncated: boolean;
  };
}

export type AiMessageRole = "user" | "assistant" | "system";

export type AiProcessEntryStatus = "running" | "success" | "failed";

export interface AiProcessEntry {
  id: number;
  type: "thought" | "tool";
  label: string;
  detail: string;
  status: AiProcessEntryStatus;
  toolResult?: AdbMcpToolResult;
}

export interface AiProcessState {
  active: boolean;
  expanded: boolean;
  startedAt?: number;
  finishedAt?: number;
  entries: AiProcessEntry[];
}

export interface AiMessage {
  id: number;
  role: AiMessageRole;
  content: string;
  /** 用户消息发送时间或 AI 最终回复完成时间。 */
  timestamp?: number;
  /** 流式长回复的稳定分段；页面状态和本地历史均保持分段，避免复制完整正文。 */
  contentSegments?: string[];
  kind?: "text" | "tool" | "process";
  error?: boolean;
  toolResult?: AdbMcpToolResult;
  process?: AiProcessState;
}

/** 历史聊天列表摘要；消息正文只在详情接口中返回。 */
export interface AiConversationSummary {
  id: string;
  title: string;
  titleEdited: boolean;
  createdAt: number;
  updatedAt: number;
  messageCount: number;
}

/** 历史聊天分页响应。 */
export interface AiConversationPage {
  items: AiConversationSummary[];
  page: number;
  size: number;
  total: number;
  activeConversationId: string;
}

/** 历史聊天详情只返回最近消息，完整旧消息继续保存在后端文件中。 */
export interface AiConversationDetail extends AiConversationSummary {
  messages: AiMessage[];
  hasMoreMessages: boolean;
}

/** 创建、更新、重命名或激活历史聊天的请求。 */
export interface AiConversationWriteRequest {
  title: string;
  titleEdited: boolean;
  messages: AiMessage[];
  createdAt: number;
  updatedAt: number;
  active: boolean;
}

/** 旧浏览器历史迁移项。 */
export interface AiConversationMigrationItem extends AiConversationWriteRequest {
  id: string;
}

/** 旧浏览器历史迁移请求。 */
export interface AiConversationMigrationRequest {
  conversations: Omit<AiConversationMigrationItem, "active">[];
  activeConversationId: string;
}

/** 旧浏览器历史迁移结果。 */
export interface AiConversationMigrationResult {
  migratedCount: number;
  activeConversationId: string;
}

export type AdbRiskLevel = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
export type AdbToolStatus = "SUCCESS" | "FAILED" | "CONFIRMATION_REQUIRED" | "CANCELED";

export interface AdbMcpToolDefinition {
  name: string;
  description: string;
  defaultRiskLevel: AdbRiskLevel;
  timeout: string;
  requiresDevice: boolean;
}

export interface AdbMcpToolRequest {
  toolName: string;
  conversationId: string;
  deviceSerial: string;
  arguments: Record<string, unknown>;
  confirmationToken: string;
  requestId: string;
}

export interface AdbMcpToolResult {
  status: AdbToolStatus;
  stdout: string;
  stderr: string;
  exitCode: number | null;
  timedOut: boolean;
  durationMillis: number;
  truncated: boolean;
  riskLevel: AdbRiskLevel;
  confirmationRequired: boolean;
  confirmationToken: string;
  message: string;
  errorCode: string;
  toolTitle?: string;
  commandSummary?: string;
}

export interface AdbConfirmationDecisionRequest {
  conversationId: string;
  requestId: string;
}

export type AiToolStreamEventName = "tool-start" | "tool-output" | "tool-confirmation" | "tool-result" | "tool-error";

export interface AiToolStreamEvent {
  eventName: AiToolStreamEventName;
  payload: AdbMcpToolResult | string;
}
