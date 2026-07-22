/**
 * AI 助手 API 封装，集中处理后端统一错误响应。
 *
 * by AI.Coding
 */
import {
  AdbConfirmationDecisionRequest,
  AdbMcpToolDefinition,
  AdbMcpToolRequest,
  AdbMcpToolResult,
  AiToolStreamEvent,
  AiChatRequest,
  AiChatResponse,
  AiConfigDetail,
  AiConfigRequest,
  AiConfigStatus,
  AiConnectionTestResult,
  AiConversationDetail,
  AiConversationMigrationRequest,
  AiConversationMigrationResult,
  AiConversationPage,
  AiConversationWriteRequest,
  AiModelListRequest,
  AiModelListResponse,
  AiLogAnalysisRequest,
  AiLogAnalysisResponse,
  AgentTaskResult,
  AgentTaskStatus,
  AgentTrace,
  WebSearchConfigDetail,
  WebSearchConfigRequest,
  WebSearchConnectionTestResult,
} from "./aiTypes";

const API_BASE = import.meta.env.VITE_API_BASE || "http://127.0.0.1:8080";
const SSE_BUFFER_MAX_CHARACTERS = 1_000_000;
const SSE_EVENT_MAX_CHARACTERS = 500_000;

interface ApiError {
  code: string;
  message: string;
  detail: string;
  timestamp: string;
}

interface AiStreamEvent {
  type: "task" | "chunk" | "done" | "error";
  content: string;
  code: string;
  detail?: string;
}

interface SseReadState {
  doneReceived: boolean;
  eventCount: number;
}

/**
 * GET 请求 JSON 数据。
 */
export async function getConfigStatus(): Promise<AiConfigStatus> {
  return requestJson<AiConfigStatus>("/api/ai/config/status", { method: "GET" });
}

/**
 * 获取 AI 配置详情，用于配置弹窗按 Provider 回填。
 */
export async function getConfigDetail(provider?: string): Promise<AiConfigDetail> {
  const query = provider
    ? `?provider=${encodeURIComponent(provider)}&revealApiKey=true`
    : "?revealApiKey=true";
  return requestJson<AiConfigDetail>(`/api/ai/config${query}`, { method: "GET" });
}

/**
 * 保存 AI 配置。
 */
export async function saveConfig(request: AiConfigRequest): Promise<AiConfigStatus> {
  return requestJson<AiConfigStatus>("/api/ai/config", {
    method: "PUT",
    body: JSON.stringify(request),
  });
}

/**
 * 测试 AI 配置连接。
 */
export async function testConfig(request: AiConfigRequest): Promise<AiConnectionTestResult> {
  return requestJson<AiConnectionTestResult>("/api/ai/config/test", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

/**
 * 使用当前 API URL 和 API Key 拉取 Provider 模型列表，不要求模型字段已填写。
 */
export async function fetchModelList(request: AiModelListRequest): Promise<AiModelListResponse> {
  return requestJson<AiModelListResponse>("/api/ai/config/models", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

/** 获取全局网络检索配置。 */
export async function getWebSearchConfig(): Promise<WebSearchConfigDetail> {
  return requestJson<WebSearchConfigDetail>("/api/ai/config/web-search?revealApiKey=true", { method: "GET" });
}

/** 保存全局网络检索配置。 */
export async function saveWebSearchConfig(request: WebSearchConfigRequest): Promise<WebSearchConfigDetail> {
  return requestJson<WebSearchConfigDetail>("/api/ai/config/web-search", {
    method: "PUT",
    body: JSON.stringify(request),
  });
}

/** 使用临时 Tavily 配置测试网络搜索。 */
export async function testWebSearchConfig(request: WebSearchConfigRequest): Promise<WebSearchConnectionTestResult> {
  return requestJson<WebSearchConnectionTestResult>("/api/ai/config/web-search/test", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

/**
 * 发起普通 AI 对话。
 */
export async function chat(
  request: AiChatRequest,
  signal: AbortSignal,
  idempotencyKey = "",
): Promise<AiChatResponse> {
  return requestJson<AiChatResponse>("/api/ai/chat", {
    method: "POST",
    headers: idempotencyKey ? { "Idempotency-Key": idempotencyKey } : undefined,
    body: JSON.stringify(request),
    signal,
  });
}

/**
 * 发起普通 AI 流式对话，逐段回调模型返回内容。
 */
export async function chatStream(
  request: AiChatRequest,
  signal: AbortSignal,
  onChunk: (content: string) => void,
  onToolEvent?: (event: AiToolStreamEvent) => void,
  onTask?: (taskId: string) => void,
  idempotencyKey = "",
): Promise<void> {
  const response = await controlPlaneFetch(`${API_BASE}/api/ai/chat/stream`, {
    method: "POST",
    headers: {
      Accept: "text/event-stream",
      "Content-Type": "application/json",
      ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {}),
    },
    body: JSON.stringify(request),
    signal,
  });
  if (!response.ok) {
    throw await apiError(response);
  }
  await readEventStream(response, signal, onChunk, onToolEvent, onTask);
}

/**
 * 取消当前 Agent Task，并向模型和可取消工具传播信号。
 */
export async function cancelAgentTask(taskId: string): Promise<AgentTaskStatus> {
  return requestJson<AgentTaskStatus>(`/api/ai/agent/tasks/${encodeURIComponent(taskId)}/cancel`, {
    method: "POST",
  });
}

/** 提交结构化补充输入并消费后端自动续跑流。 */
export async function submitAgentInput(
  taskId: string,
  conversationId: string,
  inputKey: string,
  value: string,
  signal: AbortSignal,
  onChunk: (content: string) => void,
  onToolEvent?: (event: AiToolStreamEvent) => void,
  onTask?: (taskId: string) => void,
): Promise<void> {
  const response = await controlPlaneFetch(`${API_BASE}/api/ai/agent/tasks/${encodeURIComponent(taskId)}/input`, {
    method: "POST",
    headers: { Accept: "text/event-stream", "Content-Type": "application/json" },
    body: JSON.stringify({ conversationId, inputKey, value }),
    signal,
  });
  if (!response.ok) throw await apiError(response);
  await readEventStream(response, signal, onChunk, onToolEvent, onTask);
}

/**
 * 查询持久化任务结果，供 SSE 意外断开后恢复最终回复。
 */
export async function getAgentTaskResult(taskId: string): Promise<AgentTaskResult> {
  return requestJson<AgentTaskResult>(
    `/api/ai/agent/tasks/${encodeURIComponent(taskId)}/result`,
    { method: "GET" },
  );
}

/** 查询当前 Agent Task 的脱敏模型、RAG、工具和错误 Trace。 */
export async function getAgentTrace(taskId: string): Promise<AgentTrace> {
  return requestJson<AgentTrace>(
    `/api/ai/agent/tasks/${encodeURIComponent(taskId)}/trace`,
    { method: "GET" },
  );
}

/**
 * 分页读取历史聊天摘要，避免一次加载全部会话正文。
 */
export async function listConversations(page = 0, size = 100): Promise<AiConversationPage> {
  return requestJson<AiConversationPage>(
    `/api/ai/conversations?page=${page}&size=${size}`,
    { method: "GET" },
  );
}

/**
 * 读取一个历史聊天的最近消息。
 */
export async function getConversation(conversationId: string, messageLimit = 100): Promise<AiConversationDetail> {
  return requestJson<AiConversationDetail>(
    `/api/ai/conversations/${encodeURIComponent(conversationId)}?messageLimit=${messageLimit}`,
    { method: "GET" },
  );
}

/**
 * 创建、更新、重命名或激活一个历史聊天。
 */
export async function saveConversation(
  conversationId: string,
  request: AiConversationWriteRequest,
): Promise<AiConversationDetail> {
  return requestJson<AiConversationDetail>(
    `/api/ai/conversations/${encodeURIComponent(conversationId)}`,
    { method: "PUT", body: JSON.stringify(request) },
  );
}

/**
 * 删除一个历史聊天文件。
 */
export async function deleteStoredConversation(conversationId: string): Promise<void> {
  await requestEmpty(`/api/ai/conversations/${encodeURIComponent(conversationId)}`, { method: "DELETE" });
}

/**
 * 幂等迁移旧 localStorage 历史；调用成功后前端才可以删除旧数据。
 */
export async function migrateConversations(
  request: AiConversationMigrationRequest,
): Promise<AiConversationMigrationResult> {
  return requestJson<AiConversationMigrationResult>("/api/ai/conversations/migrate", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

/**
 * 发起日志分析。
 */
export async function analyzeLogs(request: AiLogAnalysisRequest, signal: AbortSignal): Promise<AiLogAnalysisResponse> {
  return requestJson<AiLogAnalysisResponse>("/api/ai/analyze/logs", {
    method: "POST",
    body: JSON.stringify(request),
    signal,
  });
}

/**
 * 获取 ADB MCP 工具目录。
 */
export async function listAdbMcpTools(): Promise<AdbMcpToolDefinition[]> {
  return requestJson<AdbMcpToolDefinition[]>("/api/ai/mcp/adb/tools", { method: "GET" });
}

/**
 * 调用 ADB MCP 工具。
 */
export async function callAdbMcpTool(request: AdbMcpToolRequest, signal: AbortSignal): Promise<AdbMcpToolResult> {
  return requestJson<AdbMcpToolResult>("/api/ai/mcp/adb/tools/call", {
    method: "POST",
    body: JSON.stringify(request),
    signal,
  });
}

/**
 * 确认敏感 ADB MCP 操作。
 */
export async function approveAdbConfirmation(token: string, request: AdbConfirmationDecisionRequest, signal: AbortSignal): Promise<AdbMcpToolResult> {
  return requestJson<AdbMcpToolResult>(`/api/ai/mcp/adb/confirmations/${encodeURIComponent(token)}/approve`, {
    method: "POST",
    body: JSON.stringify(request),
    signal,
  });
}

/**
 * 取消敏感 ADB MCP 操作。
 */
export async function cancelAdbConfirmation(token: string, request: AdbConfirmationDecisionRequest, signal: AbortSignal): Promise<AdbMcpToolResult> {
  return requestJson<AdbMcpToolResult>(`/api/ai/mcp/adb/confirmations/${encodeURIComponent(token)}/cancel`, {
    method: "POST",
    body: JSON.stringify(request),
    signal,
  });
}

/**
 * 取消运行中的 ADB MCP 工具调用。
 */
export async function cancelAdbRunningTool(requestId: string): Promise<AdbMcpToolResult> {
  return requestJson<AdbMcpToolResult>(`/api/ai/mcp/adb/tools/running/${encodeURIComponent(requestId)}/cancel`, { method: "POST" });
}

/**
 * 确认敏感 Local Shell MCP 操作。
 */
export async function approveLocalShellConfirmation(token: string, request: AdbConfirmationDecisionRequest, signal: AbortSignal): Promise<AdbMcpToolResult> {
  return requestJson<AdbMcpToolResult>(`/api/ai/mcp/local-shell/confirmations/${encodeURIComponent(token)}/approve`, {
    method: "POST",
    body: JSON.stringify(request),
    signal,
  });
}

/**
 * 取消敏感 Local Shell MCP 操作。
 */
export async function cancelLocalShellConfirmation(token: string, request: AdbConfirmationDecisionRequest, signal: AbortSignal): Promise<AdbMcpToolResult> {
  return requestJson<AdbMcpToolResult>(`/api/ai/mcp/local-shell/confirmations/${encodeURIComponent(token)}/cancel`, {
    method: "POST",
    body: JSON.stringify(request),
    signal,
  });
}

/**
 * 判断确认令牌是否来自统一 Agent Tool Gateway。
 */
export function isAgentConfirmationToken(token: string): boolean {
  return token.startsWith("agent-confirmation:");
}

/**
 * 批准统一 Agent 工具确认，并消费后端自动续跑的模型和工具事件流。
 */
export async function approveAgentConfirmation(
  token: string,
  conversationId: string,
  signal: AbortSignal,
  onChunk: (content: string) => void,
  onToolEvent?: (event: AiToolStreamEvent) => void,
  onTask?: (taskId: string) => void,
  onConnected?: () => void,
): Promise<void> {
  const identity = agentConfirmationIdentity(token);
  // 确认令牌已经绑定 taskId，先通知 UI，工具执行尚未返回时也可以取消整个任务。
  onTask?.(identity.taskId);
  const response = await controlPlaneFetch(
    `${API_BASE}/api/ai/agent/tasks/${encodeURIComponent(identity.taskId)}/confirmations/${encodeURIComponent(identity.confirmationId)}/approve`,
    {
      method: "POST",
      headers: {
        Accept: "text/event-stream",
        "X-Agent-Conversation-Id": conversationId,
        "X-Agent-Confirmation-Token": identity.approvalToken,
      },
      signal,
    },
  );
  if (!response.ok) {
    throw await apiError(response);
  }
  onConnected?.();
  await readEventStream(response, signal, onChunk, onToolEvent, onTask);
}

/**
 * 拒绝统一 Agent 工具确认并结束依赖该操作的任务路径。
 */
export async function rejectAgentConfirmation(
  token: string,
  conversationId: string,
  signal: AbortSignal,
): Promise<void> {
  const identity = agentConfirmationIdentity(token);
  await requestJson<unknown>(
    `/api/ai/agent/tasks/${encodeURIComponent(identity.taskId)}/confirmations/${encodeURIComponent(identity.confirmationId)}/reject`,
    {
      method: "POST",
      headers: {
        "X-Agent-Conversation-Id": conversationId,
        "X-Agent-Confirmation-Token": identity.approvalToken,
      },
      body: JSON.stringify({ reason: "用户取消敏感操作" }),
      signal,
    },
  );
}

/**
 * 从兼容令牌提取服务端确认路径标识，格式异常时在请求前失败。
 */
function agentConfirmationIdentity(token: string): {
  taskId: string;
  confirmationId: string;
  approvalToken: string;
} {
  const parts = token.split(":");
  if (parts.length !== 4 || parts[0] !== "agent-confirmation"
    || !parts[1] || !parts[2] || !parts[3]) {
    throw new Error("Agent 确认令牌格式无效");
  }
  return { taskId: parts[1], confirmationId: parts[2], approvalToken: parts[3] };
}

/**
 * 统一请求 JSON；后端错误转换为用户可读 Error。
 */
async function requestJson<T>(path: string, init: RequestInit): Promise<T> {
  const response = await controlPlaneFetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...(init.headers || {}),
    },
  });
  if (!response.ok) {
    throw await apiError(response);
  }
  return response.json() as Promise<T>;
}

/**
 * 统一请求无响应正文接口，避免对 DELETE 的空响应执行 JSON 解析。
 */
async function requestEmpty(path: string, init: RequestInit): Promise<void> {
  const response = await controlPlaneFetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...(init.headers || {}),
    },
  });
  if (!response.ok) {
    throw await apiError(response);
  }
}

/**
 * 执行控制面请求；同源浏览器在后端重启导致 Cookie 失效时重新引导并安全重试一次。
 */
async function controlPlaneFetch(url: string, init: RequestInit): Promise<Response> {
  const response = await fetch(url, init);
  if (!await shouldRefreshControlPlaneSession(response)) {
    return response;
  }
  // 401 在过滤器阶段产生，业务 Controller 尚未执行，因此原请求只重试一次不会重复副作用。
  const bootstrap = await fetch(`${API_BASE}/`, {
    method: "GET",
    cache: "no-store",
    credentials: "include",
    signal: init.signal,
  });
  if (!bootstrap.ok) {
    return response;
  }
  await response.body?.cancel();
  return fetch(url, { ...init, credentials: "include" });
}

/**
 * 只允许后端同源页面恢复控制面 Cookie，Electron 和跨域开发页继续使用显式令牌。
 */
async function shouldRefreshControlPlaneSession(response: Response) {
  if (response.status !== 401 || typeof window === "undefined") {
    return false;
  }
  const backendOrigin = new URL(API_BASE, window.location.href).origin;
  if (window.location.origin !== backendOrigin) {
    return false;
  }
  try {
    const payload = await response.clone().json() as Partial<ApiError>;
    return payload.code === "CONTROL_PLANE_UNAUTHORIZED";
  } catch {
    return false;
  }
}

/**
 * 读取后端 SSE 文本流；POST 场景不能用 EventSource，因此使用 fetch reader。
 */
async function readEventStream(
  response: Response,
  signal: AbortSignal,
  onChunk: (content: string) => void,
  onToolEvent?: (event: AiToolStreamEvent) => void,
  onTask?: (taskId: string) => void,
): Promise<void> {
  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error("当前浏览器不支持流式响应");
  }
  const decoder = new TextDecoder();
  let buffer = "";
  const state: SseReadState = { doneReceived: false, eventCount: 0 };
  while (!signal.aborted) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    if (buffer.length > SSE_BUFFER_MAX_CHARACTERS) {
      // SSE 长时间没有事件分隔符时必须主动中断，避免未完成 buffer 在 Electron 渲染进程中无限增长。
      throw new Error("AI 流式响应异常：单个未完成 SSE 缓冲区过大，已中断以避免客户端内存溢出。");
    }
    const result = consumeSseBuffer(buffer, onChunk, onToolEvent, onTask, state);
    buffer = result.remaining;
  }
  // TextDecoder 在 stream 模式下可能缓存最后几个字节；结束时必须 flush，否则尾部 done 事件会被误判为断流。
  buffer += decoder.decode();
  if (buffer.trim()) {
    consumeSseBuffer(`${buffer}\n\n`, onChunk, onToolEvent, onTask, state);
  }
  if (!signal.aborted && !state.doneReceived) {
    // 后端或 Provider 静默断开时不能当成正常完成，否则前端会误显示“未返回最终回复”。
    throw new Error(`AI 流式响应中断：连接已关闭但未收到完成事件。已接收 ${state.eventCount} 个 SSE 事件，请检查 Provider 连接、模型工具调用结果和后端日志。`);
  }
}

/**
 * 消费完整 SSE 事件块，保留未接收完整的尾部内容。
 */
function consumeSseBuffer(
  buffer: string,
  onChunk: (content: string) => void,
  onToolEvent: ((event: AiToolStreamEvent) => void) | undefined,
  onTask: ((taskId: string) => void) | undefined,
  state: SseReadState,
) {
  // 兼容不同容器输出的 LF/CRLF SSE 分隔符，避免正常完成事件因分隔符差异滞留在 buffer 中。
  const parts = buffer.split(/\r?\n\r?\n/);
  const remaining = parts.pop() || "";
  for (const part of parts) {
    if (part.length > SSE_EVENT_MAX_CHARACTERS) {
      // 单个事件过大时 JSON.parse 会瞬间复制大字符串，必须在解析前阻断。
      throw new Error("AI 流式响应异常：单个 SSE 事件过大，已中断以避免客户端内存溢出。");
    }
    handleSseBlock(part, onChunk, onToolEvent, onTask, state);
  }
  return { remaining };
}

/**
 * 处理单个 SSE 事件块，后端 data 使用 JSON 以安全承载换行和 Markdown 字符。
 */
function handleSseBlock(
  block: string,
  onChunk: (content: string) => void,
  onToolEvent: ((event: AiToolStreamEvent) => void) | undefined,
  onTask: ((taskId: string) => void) | undefined,
  state: SseReadState,
) {
  const lines = block.split(/\r?\n/);
  const eventName = lines.find(line => line.startsWith("event:"))?.slice(6).trim() || "message";
  const data = lines
    .filter(line => line.startsWith("data:"))
    .map(line => line.slice(5).trimStart())
    .join("\n");
  if (!data) return;
  const payload = JSON.parse(data) as AiStreamEvent;
  state.eventCount += 1;
  if (eventName === "error" || payload.type === "error") {
    throw new Error(formatStreamError(payload));
  }
  if (payload.type === "task" && payload.content && onTask) {
    onTask(payload.content);
  }
  if (payload.type === "chunk") {
    // AI 正文不能按客户端内存保护截断；内存风险由渲染降级、低频刷新和历史存储压缩处理。
    onChunk(payload.content || "");
  }
  if (payload.type === "done") {
    state.doneReceived = true;
  }
  if (eventName.startsWith("tool-") && onToolEvent) {
    onToolEvent({ eventName: eventName as AiToolStreamEvent["eventName"], payload: payload as unknown as AdbMcpToolResult });
  }
}

/**
 * 拼接流式错误详情；错误码和诊断详情必须进入 Error，供聊天区红色消息展示。
 */
function formatStreamError(payload: AiStreamEvent) {
  const message = payload.content || "AI 请求失败";
  const code = payload.code ? `错误码：${payload.code}` : "";
  const detail = payload.detail ? `诊断详情：${payload.detail}` : "";
  return [message, code, detail].filter(Boolean).join("\n");
}

/**
 * 将后端 ApiError 转换为前端展示摘要。
 */
async function apiError(response: Response): Promise<Error> {
  try {
    const payload = await response.json() as ApiError;
    const summary = payload.message || payload.code || `HTTP ${response.status}`;
    const detail = payload.detail && payload.detail !== summary ? `：${payload.detail}` : "";
    return new Error(`${summary}${detail}`);
  } catch {
    return new Error(`HTTP ${response.status}`);
  }
}
