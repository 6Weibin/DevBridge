/**
 * AI 侧边栏主体，负责普通对话和当前设备日志分析。
 *
 * by AI.Coding
 */
import React, { useEffect, useRef, useState } from "react";
import { Activity, ArrowDown, ArrowUp, Globe2, Loader2, Maximize2, Minimize2, Play, Settings, Square, Terminal, X } from "lucide-react";
import {
  analyzeLogs,
  approveAdbConfirmation,
  approveAgentConfirmation,
  approveLocalShellConfirmation,
  cancelAdbConfirmation,
  cancelLocalShellConfirmation,
  chatStream,
  deleteStoredConversation,
  getConversation,
  getAgentTaskResult,
  getWebSearchConfig,
  isAgentConfirmationToken,
  listConversations,
  migrateConversations,
  rejectAgentConfirmation,
  saveConversation,
  saveWebSearchConfig,
  submitAgentInput,
} from "./aiApi";
import { AiConversationHistory, AiHomePanel, AiMessageBubble, AiProcessCard, AiThinkingIcon, AiTracePanel } from "./AiChatViews";
import { AiConfirmationCard } from "./AiConfirmationCard";
import { AiToolCallCard } from "./AiToolCallCard";
import { materializeContent } from "./aiStreamSegments";
import { useAiChatStore } from "./useAiChatStore";
import { useAiChatLayout } from "./useAiChatLayout";
import { useAiTaskController } from "./useAiTaskController";
import { conversationTitle, createConversationId, deriveConversationTitle, formatAnalysisResult, formatConversationTime } from "./aiFormatters";
import { AdbMcpToolResult, AiChatHistoryMessage, AiConfigStatus, AiConversationDetail, AiConversationPage, AiConversationSummary, AiDeviceContext, AiLogLine, AiMessage, AiProcessEntry, AiProcessState, AiToolStreamEvent } from "./aiTypes";

interface AiChatPanelProps {
  open: boolean;
  visible: boolean;
  configStatus: AiConfigStatus | null;
  device: AiDeviceContext | null;
  streaming: boolean;
  getRecentLogs: () => AiLogLine[];
  onStartLogCapture: () => void;
  onOpenConfig: () => void;
  onOpenNetworkConfig: () => void;
  webSearchEnabled: boolean;
  onWebSearchEnabledChange: (enabled: boolean) => void;
  onClose: () => void;
}

let nextMessageId = 1;
// 约 12 FPS 刷新流式尾段，保持连续输出并为 Markdown 和自动滚动预留主线程预算。
const STREAM_FLUSH_INTERVAL_MS = 80;
const FINAL_RESPONSE_MARKER = "<DEVBRIDGE_FINAL>";
const ASSISTANT_DISPLAY_NAME = "Bridge Copilot";
const ASSISTANT_HOME_TITLE = `您好，我是${ASSISTANT_DISPLAY_NAME}你的手机智能助手，有什么我能帮你的吗？`;
const CONVERSATION_STORAGE_KEY = "devbridge.ai.conversations.v1";
const CONVERSATION_DEFAULT_TITLE = "新对话";
const CONVERSATION_PAGE_SIZE = 100;
const CONVERSATION_MESSAGE_LOAD_LIMIT = 100;
const CONVERSATION_SAVE_DEBOUNCE_MS = 300;
const CONVERSATION_WRITE_MAX_MESSAGES = 200;
const TOOL_OUTPUT_MAX_CONTENT_LENGTH = 24_000;
const TOOL_OUTPUT_TRUNCATED_NOTICE = "\n[工具输出过长，前端已截断显示。]";
const PROCESS_DETAIL_MAX_CONTENT_LENGTH = 8_000;
const PROCESS_MAX_ENTRIES = 80;
const CONVERSATION_PERSISTED_TOOL_OUTPUT_MAX_LENGTH = 2_500;

const QUICK_PROMPTS = [
  "帮我检查手机网络状况",
  "当前手机定位不准确出现位置漂移，帮我检查定位",
  "帮我实时拉取手机日志，并完成日志分析",
  "帮我进行手机健康检查",
  "帮我打开电脑上的Chrome或Edge浏览器，并访问远程调试管理面板（chrome://inspect）",
];

interface AiConversationSession {
  id: string;
  title: string;
  titleEdited?: boolean;
  messages: AiMessage[];
  createdAt: number;
  updatedAt: number;
}

interface LegacyConversationStore {
  conversations: AiConversationSession[];
  activeConversationId: string;
}

type AiStreamStarter = (
  signal: AbortSignal,
  onChunk: (content: string) => void,
  onToolEvent: (event: AiToolStreamEvent) => void,
  onTask: (taskId: string) => void,
) => Promise<void>;

/**
 * 在 SSE 意外断开后等待后端任务进入终态，优先恢复已经持久化的完整回复。
 */
async function waitForPersistedTaskResult(taskId: string, signal: AbortSignal) {
  for (let attempt = 0; attempt < 300 && !signal.aborted; attempt += 1) {
    let result: Awaited<ReturnType<typeof getAgentTaskResult>>;
    try {
      result = await getAgentTaskResult(taskId);
    } catch {
      // 短暂网络波动时保留原始流错误，结果查询只做恢复兜底。
      await new Promise(resolve => window.setTimeout(resolve, 1000));
      continue;
    }
    if (result.state === "COMPLETED" && result.answer) return result.answer;
    if (result.state === "FAILED" && result.failure) {
      throw new Error([
        result.failure.summary,
        `错误码：${result.failure.errorCode}`,
        `失败阶段：${result.failure.source} / ${result.failure.stage}`,
        `可能影响：${result.failure.possibleImpact}`,
        `建议动作：${result.failure.suggestedAction}`,
      ].join("\n"));
    }
    if (result.state === "FAILED" || result.state === "CANCELED") return "";
    await new Promise(resolve => window.setTimeout(resolve, 1000));
  }
  return "";
}

/**
 * 渲染 AI 对话与日志分析侧边栏。
 */
export function AiChatPanel({ open, visible, configStatus, device, streaming, getRecentLogs, onStartLogCapture, onOpenConfig, onOpenNetworkConfig, webSearchEnabled, onWebSearchEnabledChange, onClose }: AiChatPanelProps) {
  const [initialConversationStore] = useState(createInitialConversationStore);
  const [conversations, setConversations] = useState<AiConversationSession[]>(initialConversationStore.conversations);
  const [activeConversationId, setActiveConversationId] = useState(initialConversationStore.activeConversationId);
  const { messages, setMessages, messagesRef, queueStreamChunk, flushStreamChunk } = useAiChatStore(
    initialConversationStore.messages,
    STREAM_FLUSH_INTERVAL_MS,
  );
  const [conversationStoreReady, setConversationStoreReady] = useState(false);
  const [conversationPage, setConversationPage] = useState(0);
  const [conversationTotal, setConversationTotal] = useState(0);
  const [conversationListLoading, setConversationListLoading] = useState(false);
  const [input, setInput] = useState("");
  const [waitingInput, setWaitingInput] = useState<{ taskId: string; inputKey: string } | null>(null);
  const [hint, setHint] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [checkingWebSearch, setCheckingWebSearch] = useState(false);
  const [webSearchPromptOpen, setWebSearchPromptOpen] = useState(false);
  const [streamingMessageId, setStreamingMessageId] = useState<number | null>(null);
  const [historyMenu, setHistoryMenu] = useState<{ conversationId: string; x: number; y: number } | null>(null);
  const conversationIdRef = useRef(initialConversationStore.activeConversationId);
  const streamingMessageIdRef = useRef<number | null>(null);
  const conversationsRef = useRef(initialConversationStore.conversations);
  const conversationSaveQueueRef = useRef<Promise<void>>(Promise.resolve());
  const submittingRef = useRef(false);

  /** 切换智能联网搜索；开启前确认 Tavily URL 和 Key 均已配置。 */
  const toggleWebSearch = async () => {
    setCheckingWebSearch(true);
    try {
      const detail = await getWebSearchConfig();
      if (!detail.configured || !detail.apiUrl.trim() || !detail.apiKey.trim()) {
        setWebSearchPromptOpen(true);
        return;
      }
      const saved = await saveWebSearchConfig({
        enabled: !webSearchEnabled,
        apiUrl: detail.apiUrl,
        apiKey: detail.apiKey,
        defaultResultCount: detail.defaultResultCount,
      });
      onWebSearchEnabledChange(saved.enabled);
    } catch (error) {
      setHint(error instanceof Error ? error.message : "Tavily 配置读取失败");
    } finally {
      setCheckingWebSearch(false);
    }
  };

  /** 追加一条消息并集中维护消息 ID。 */
  const append = (role: AiMessage["role"], content: string) => {
    const id = nextId();
    const timestamp = role === "user" || (role === "assistant" && !!content) ? Date.now() : undefined;
    setMessages(current => [...visibleConversationMessages(current), sanitizeMessageForState({
      id, role, content, timestamp, kind: "text",
    })]);
    return id;
  };

  /** 追加红色错误消息，保证顶部提示消失后仍可定位异常。 */
  const appendError = (content: string) => {
    const id = nextId();
    setMessages(current => [...current, sanitizeMessageForState({
      id, role: "assistant", content, timestamp: Date.now(), kind: "text", error: true,
    })]);
    return id;
  };

  const {
    loading,
    currentTaskId,
    traceOpen,
    traceLoading,
    traceError,
    trace,
    setTraceOpen,
    registerTask,
    beginPreparation,
    endPreparation,
    runRequest,
    cancelCurrent,
    openTrace,
    resetTaskView,
  } = useAiTaskController({
    appendMessage: append,
    appendError,
    flushStreaming: () => flushStreamChunk(streamingMessageIdRef.current),
    clearStreaming: () => {
      streamingMessageIdRef.current = null;
      setStreamingMessageId(null);
    },
    setHint,
  });
  const {
    fullscreen,
    setFullscreen,
    atScrollBottom,
    inputRef,
    inputPanelRef,
    inputPanelHeight,
    scrollContainerRef,
    endRef,
    suppressNextAutoScrollRef,
    updateScrollBottomState,
    resetAutoScroll,
    returnToChatBottom,
  } = useAiChatLayout({ open, input, hint, loading, messages, onClose });
  // 助手身份、模型和设备信息统一显示在历史聊天栏顶部。
  // 工具区统一使用“厂商/模型”格式，英文转为大写以便快速识别当前模型。
  const modelLabel = configStatus?.configured
    ? `${configStatus.provider}/${configStatus.model}`.toUpperCase()
    : "未配置模型";
  // 快捷操作入口先保留代码但默认隐藏，后续明确交互场景后再开放。
  const showQuickActions = false;
  // 设备上下文已经移到输入框下方，旧顶部区域保留但默认隐藏。
  const showHeaderDeviceContext = false;
  const deviceTags = device ? [
    { label: "平台", value: device.platform },
    { label: "状态", value: device.status },
    { label: "型号", value: device.model },
    { label: "系统", value: device.osVersion },
  ] : [];
  const visibleMessages = visibleConversationMessages(messages);
  const hasActiveProcess = visibleMessages.some(message => message.kind === "process" && message.process?.active);
  const showHome = visibleMessages.length === 0 && !loading && !conversationListLoading;

  /**
   * 当前会话 ID 也作为 ADB MCP 确认令牌绑定 ID，切换历史会话后工具确认仍能归属到当前对话。
   */
  useEffect(() => {
    conversationIdRef.current = activeConversationId;
  }, [activeConversationId]);

  /**
   * 会话摘要引用供异步保存和切换使用，避免事件处理函数读到旧 React 闭包。
   */
  useEffect(() => {
    conversationsRef.current = conversations;
  }, [conversations]);

  /**
   * 首次进入时迁移旧 localStorage，再从后端分页索引恢复活动会话。
   */
  useEffect(() => {
    let canceled = false;
    const initialize = async () => {
      setConversationListLoading(true);
      let migrationWarning = "";
      try {
        try {
          await migrateLegacyConversationStore();
        } catch (error) {
          // 迁移失败时保留 localStorage，同时继续读取已存在的后端历史。
          migrationWarning = `旧历史迁移失败：${error instanceof Error ? error.message : "原数据已保留"}`;
        }
        const page = await listConversations(0, CONVERSATION_PAGE_SIZE);
        const loaded = await loadInitialConversationPage(page);
        if (canceled) return;
        applyLoadedConversationStore(loaded.page, loaded.active);
        if (migrationWarning) {
          setHint(migrationWarning);
        }
      } catch (error) {
        if (!canceled) {
          setHint(`历史聊天加载失败：${error instanceof Error ? error.message : "本地文件不可用"}`);
        }
      } finally {
        if (!canceled) {
          setConversationStoreReady(true);
          setConversationListLoading(false);
        }
      }
    };
    void initialize();
    return () => {
      canceled = true;
    };
  }, []);

  /**
   * 一轮对话结束后低频保存当前消息尾部；流式阶段不做 JSON 序列化和文件 IO。
   */
  useEffect(() => {
    if (loading || !conversationStoreReady) return;
    const timer = window.setTimeout(() => {
      const current = conversationsRef.current.find(item => item.id === activeConversationId);
      if (!current || conversationMessageSignature(current.messages) === conversationMessageSignature(messages)) {
        return;
      }
      const synced = syncActiveConversation(
        conversationsRef.current, activeConversationId, messages, true,
      );
      conversationsRef.current = synced;
      setConversations(synced);
      const active = synced.find(item => item.id === activeConversationId);
      if (active) {
        void queueConversationSave(active, true).catch(error => {
          setHint(`历史聊天保存失败：${error instanceof Error ? error.message : "本地文件不可写"}`);
        });
      }
    }, CONVERSATION_SAVE_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [activeConversationId, conversationStoreReady, loading, messages]);

  /**
   * 历史对话右键菜单打开后，点击其它区域自动关闭。
   */
  useEffect(() => {
    if (!historyMenu) return;
    const closeMenu = () => setHistoryMenu(null);
    window.addEventListener("click", closeMenu);
    window.addEventListener("keydown", closeMenu);
    return () => {
      window.removeEventListener("click", closeMenu);
      window.removeEventListener("keydown", closeMenu);
    };
  }, [historyMenu]);

  /** 组件卸载时清理当前流式消息引用，缓冲和滚动帧由各自 Hook 负责。 */
  useEffect(() => () => {
    streamingMessageIdRef.current = null;
  }, []);

  /**
   * 顶部提示只做短暂状态反馈，详细错误已经进入对话区，避免黄色提示长期占用顶部空间。
   */
  useEffect(() => {
    if (!hint) return;
    const timer = window.setTimeout(() => setHint(""), 5000);
    return () => window.clearTimeout(timer);
  }, [hint]);

  /**
   * 把后端分页摘要和活动会话详情一次性应用到页面状态。
   */
  const applyLoadedConversationStore = (page: AiConversationPage, active: AiConversationDetail) => {
    const sessions = page.items.map(conversationSummaryToSession);
    const activeSession = conversationDetailToSession(active);
    const nextSessions = replaceConversationSession(sessions, activeSession);
    conversationsRef.current = nextSessions;
    conversationIdRef.current = activeSession.id;
    messagesRef.current = activeSession.messages;
    setConversations(nextSessions);
    setActiveConversationId(activeSession.id);
    setMessages(activeSession.messages);
    setConversationPage(page.page);
    setConversationTotal(Math.max(page.total, nextSessions.length));
  };

  /**
   * 串行提交会话写入，避免重命名、切换和消息保存请求乱序覆盖。
   */
  const queueConversationSave = (
    conversation: AiConversationSession,
    active: boolean,
    includeMessages = true,
  ) => {
    const operation = conversationSaveQueueRef.current
      .catch(() => undefined)
      .then(async () => {
        await saveConversation(conversation.id, {
          title: conversationTitle(conversation),
          titleEdited: !!conversation.titleEdited,
          messages: includeMessages ? messagesForConversationStorage(conversation.messages) : [],
          createdAt: conversation.createdAt,
          updatedAt: conversation.updatedAt,
          active,
        });
      });
    conversationSaveQueueRef.current = operation;
    return operation;
  };

  /**
   * 发送下一轮前把当前已完成消息写入后端，确保 Context Builder 能读取连续上下文。
   */
  const ensureCurrentConversationPersisted = async () => {
    await conversationSaveQueueRef.current.catch(() => undefined);
    const synced = syncActiveConversation(
      conversationsRef.current, activeConversationId, messagesRef.current, true,
    );
    const active = synced.find(item => item.id === activeConversationId);
    if (!active) {
      throw new Error("当前历史聊天不存在");
    }
    conversationsRef.current = synced;
    setConversations(synced);
    await queueConversationSave(active, true);
  };

  if (!open) {
    return null;
  }

  /**
   * 把工具调用结果放入同一条消息时间线，避免工具卡片跑到用户消息前面。
   */
  const appendToolResult = (result: AdbMcpToolResult) => {
    const id = nextId();
    setMessages(current => [...current, sanitizeMessageForState({ id, role: "assistant", content: "", kind: "tool", toolResult: result })]);
    return id;
  };

  /**
   * 新建空白对话；加载中不切换会话，避免正在进行的 SSE 和工具确认写入错误会话。
   */
  const startNewConversation = () => {
    if (loading || conversationListLoading) {
      setHint("当前请求处理中，完成或取消后再新建对话。");
      return;
    }
    // 新会话默认进入底部跟随模式，避免继承上一个会话的历史查看滚动状态。
    resetAutoScroll();
    const synced = syncActiveConversation(
      conversationsRef.current, activeConversationId, messages, true,
    );
    const previous = synced.find(item => item.id === activeConversationId);
    if (previous && conversationMessageSignature(previous.messages) !== conversationMessageSignature(
      conversationsRef.current.find(item => item.id === activeConversationId)?.messages || [],
    )) {
      void queueConversationSave(previous, false).catch(error => {
        setHint(`当前历史聊天保存失败：${error instanceof Error ? error.message : "本地文件不可写"}`);
      });
    }
    const session = createConversationSession();
    const next = [session, ...synced.filter(item => item.id !== session.id)];
    conversationsRef.current = next;
    conversationIdRef.current = session.id;
    messagesRef.current = session.messages;
    setConversations(next);
    setActiveConversationId(session.id);
    setMessages(session.messages);
    resetTaskView();
    setWaitingInput(null);
    setConversationTotal(current => current + 1);
    setInput("");
    setHint("");
    void queueConversationSave(session, true).catch(error => {
      setHint(`新对话保存失败：${error instanceof Error ? error.message : "本地文件不可写"}`);
    });
  };

  /**
   * 加载历史对话内容；加载后继续发送问题会沿用该会话消息作为上下文。
   */
  const selectConversation = async (conversationId: string) => {
    if (conversationId === activeConversationId) {
      return;
    }
    if (loading || conversationListLoading) {
      setHint("当前请求处理中，完成或取消后再切换历史对话。");
      return;
    }
    setConversationListLoading(true);
    try {
      const synced = syncActiveConversation(
        conversationsRef.current, activeConversationId, messages, true,
      );
      const previous = synced.find(item => item.id === activeConversationId);
      const previousStored = conversationsRef.current.find(item => item.id === activeConversationId);
      if (previous && previousStored
        && conversationMessageSignature(previous.messages) !== conversationMessageSignature(previousStored.messages)) {
        await queueConversationSave(previous, false);
      }
      const detail = await getConversation(conversationId, CONVERSATION_MESSAGE_LOAD_LIMIT);
      const target = conversationDetailToSession(detail);
      const next = replaceConversationSession(synced, target);
      // 切换会话只激活索引，不修改更新时间，因此不会把点击项移到列表顶部。
      await queueConversationSave(target, true, false);
      resetAutoScroll();
      conversationsRef.current = next;
      conversationIdRef.current = target.id;
      messagesRef.current = target.messages;
      setConversations(next);
      setActiveConversationId(target.id);
      setMessages(target.messages);
      resetTaskView();
      setWaitingInput(null);
      setInput("");
      setHint("");
    } catch (error) {
      setHint(`历史对话加载失败：${error instanceof Error ? error.message : "会话不存在"}`);
    } finally {
      setConversationListLoading(false);
    }
  };

  /**
   * 打开历史对话右键菜单，提供重命名和删除操作。
   */
  const openHistoryMenu = (conversationId: string, event: React.MouseEvent) => {
    event.preventDefault();
    setHistoryMenu({ conversationId, x: event.clientX, y: event.clientY });
  };

  /**
   * 重命名历史对话标题；空标题会回退为自动标题。
   */
  const renameConversation = async (conversationId: string) => {
    const current = conversations.find(item => item.id === conversationId);
    if (!current) return;
    const nextTitle = window.prompt("重命名对话标题", conversationTitle(current));
    setHistoryMenu(null);
    if (nextTitle === null) {
      return;
    }
    const title = nextTitle.trim();
    try {
      const sourceMessages = !title && current.messages.length === 0
        ? (await getConversation(conversationId, CONVERSATION_MESSAGE_LOAD_LIMIT)).messages
        : current.messages;
      const renamed = {
        ...current,
        title: title || deriveConversationTitle(sourceMessages),
        titleEdited: !!title,
      };
      await queueConversationSave(renamed, conversationId === activeConversationId, false);
      const next = conversationsRef.current.map(item => item.id === conversationId ? renamed : item);
      conversationsRef.current = next;
      setConversations(next);
    } catch (error) {
      setHint(`历史聊天重命名失败：${error instanceof Error ? error.message : "本地文件不可写"}`);
    }
  };

  /**
   * 删除历史对话；删除当前会话时自动切换到剩余最近会话，保持右侧内容可用。
   */
  const deleteConversation = async (conversationId: string) => {
    if (loading || conversationListLoading) {
      setHint("当前请求处理中，完成或取消后再删除历史对话。");
      setHistoryMenu(null);
      return;
    }
    setHistoryMenu(null);
    setConversationListLoading(true);
    try {
      if (conversationId !== activeConversationId) {
        const synced = syncActiveConversation(
          conversationsRef.current, activeConversationId, messages, true,
        );
        const active = synced.find(item => item.id === activeConversationId);
        const stored = conversationsRef.current.find(item => item.id === activeConversationId);
        if (active && stored
          && conversationMessageSignature(active.messages) !== conversationMessageSignature(stored.messages)) {
          await queueConversationSave(active, true);
        }
      }
      await deleteStoredConversation(conversationId);
      const page = await listConversations(0, CONVERSATION_PAGE_SIZE);
      const loaded = await loadInitialConversationPage(page);
      resetAutoScroll();
      applyLoadedConversationStore(loaded.page, loaded.active);
      setInput("");
      setHint("");
    } catch (error) {
      setHint(`历史聊天删除失败：${error instanceof Error ? error.message : "本地文件不可写"}`);
    } finally {
      setConversationListLoading(false);
    }
  };

  /**
   * 分页加载更多历史聊天摘要，不加载这些会话的消息正文。
   */
  const loadMoreConversationHistory = async () => {
    if (conversationListLoading || conversationsRef.current.length >= conversationTotal) return;
    setConversationListLoading(true);
    try {
      const nextPage = conversationPage + 1;
      const page = await listConversations(nextPage, CONVERSATION_PAGE_SIZE);
      const next = mergeConversationSummaries(conversationsRef.current, page.items);
      conversationsRef.current = next;
      setConversations(next);
      setConversationPage(page.page);
      setConversationTotal(page.total);
    } catch (error) {
      setHint(`历史聊天加载失败：${error instanceof Error ? error.message : "分页读取失败"}`);
    } finally {
      setConversationListLoading(false);
    }
  };

  /**
   * 把工具调用前的模型前置文本转换为可折叠过程，避免把过程当作最终回复。
   */
  const convertAssistantToProcess = (messageId: number, taskDetail: string) => {
    setMessages(current => current.map(message => {
      if (message.id !== messageId) {
        return message;
      }
      const entries = [thoughtEntry(processInitialDetail(taskDetail))];
      const visibleContent = materializeContent(message.content, message.contentSegments);
      if (visibleContent.trim()) {
        entries.push(thoughtEntry(visibleContent));
      }
      return sanitizeMessageForState({
        ...message,
        content: "",
        contentSegments: undefined,
        kind: "process",
        process: {
          active: true,
          expanded: false,
          startedAt: Date.now(),
          entries,
        },
      });
    }));
    return messageId;
  };

  /**
   * 把后续工具调用前的模型说明并入既有过程，避免过程文本被误当成独立最终回复。
   */
  const mergeAssistantIntoProcess = (processId: number, assistantMessageId: number) => {
    setMessages(current => {
      const assistant = current.find(message => message.id === assistantMessageId);
      const processMessage = current.find(message => message.id === processId && message.process);
      if (!assistant || !processMessage?.process) return current;
      const visibleContent = materializeContent(assistant.content, assistant.contentSegments).trim();
      return current
        .filter(message => message.id !== assistantMessageId)
        .map(message => {
          if (message.id !== processId || !message.process || !visibleContent) return message;
          return sanitizeMessageForState({
            ...message,
            process: {
              ...message.process,
              entries: message.process.entries.concat(thoughtEntry(visibleContent)),
            },
          });
        });
    });
  };

  /**
   * 把模型最终回复标记前的可观察文本追加到思考过程，不进入普通回复区域。
   */
  const appendProcessThought = (processId: number, content: string) => {
    const detail = content.trim();
    if (!detail) return;
    setMessages(current => current.map(message => (
      message.id === processId && message.process
        ? sanitizeMessageForState({
            ...message,
            process: {
              ...message.process,
              entries: message.process.entries.concat(thoughtEntry(detail)),
            },
          })
        : message
    )));
  };

  /**
   * 创建空过程消息，用于模型没有前置文本但直接开始调用工具的场景。
   */
  const appendProcess = (taskDetail: string) => {
    const id = nextId();
    setMessages(current => [...current, sanitizeMessageForState({
      id,
      role: "assistant",
      content: "",
      kind: "process",
      process: { active: true, expanded: false, startedAt: Date.now(), entries: [thoughtEntry(processInitialDetail(taskDetail))] },
    })]);
    return id;
  };

  /**
   * 把工具执行结果追加到对应过程消息中，完成后由过程卡片展示。
   */
  const appendProcessTool = (processId: number, result: AdbMcpToolResult) => {
    setMessages(current => current.map(message => {
      if (message.id !== processId || !message.process) {
        return message;
      }
      const entries = message.process.entries.map(entry => (
        entry.status === "running" ? { ...entry, status: "success" as const } : entry
      ));
      return sanitizeMessageForState({
        ...message,
        process: {
          ...message.process,
          // 普通过程保持折叠；只有必须由用户处理的等待状态才自动展开操作入口。
          expanded: message.process.expanded
            || result.confirmationRequired
            || result.errorCode === "AI_INPUT_REQUIRED",
          entries: entries.concat(toolDecisionEntry(result), toolEntry(result)),
        },
      });
    }));
  };

  /**
   * 完成过程后默认折叠，保留展开入口供用户查看执行过程。
   */
  const completeProcess = (processId: number | null, waitingConfirmation = false) => {
    if (processId === null) return;
    setMessages(current => current.map(message => {
      if (message.id !== processId || !message.process) {
        return message;
      }
      return sanitizeMessageForState({
        ...message,
        process: {
          ...message.process,
          active: false,
          expanded: waitingConfirmation,
          finishedAt: waitingConfirmation ? message.process.finishedAt : Date.now(),
          entries: message.process.entries.map(entry => (
            entry.status === "running" ? { ...entry, status: "success" as const } : entry
          )),
        },
      });
    }));
  };

  /**
   * 展开或收起指定过程消息。
   */
  const toggleProcess = (messageId: number) => {
    // 展开/折叠只是用户查看历史过程，不属于新消息到达，必须跳过本次自动滚动。
    suppressNextAutoScrollRef.current = true;
    setMessages(current => current.map(message => (
      message.id === messageId && message.process
        ? { ...message, process: { ...message.process, expanded: !message.process.expanded } }
        : message
    )));
  };

  /**
   * 更新指定消息内容，流式响应按增量持续追加到同一条 AI 消息。
   */
  const updateMessage = (id: number, updater: (content: string) => string) => {
    setMessages(current => current.map(message => (
      message.id === id ? sanitizeMessageForState({
        ...message,
        content: updater(materializeContent(message.content, message.contentSegments)),
        contentSegments: undefined,
      }) : message
    )));
  };

  /** AI 流式正文全部接收后记录最终完成时间，避免把首个 chunk 时间误当成回复时间。 */
  const completeAssistantMessage = (id: number | null) => {
    if (id === null) return;
    const completedAt = Date.now();
    setMessages(current => current.map(message => (
      message.id === id ? { ...message, timestamp: completedAt } : message
    )));
  };

  /**
   * 执行一轮 AI 流式请求；普通对话和后端确认续跑共用同一套事件消费逻辑。
   */
  const streamAssistantTurn = async (
    text: string,
    history: AiChatHistoryMessage[],
    processDetail = text,
    streamStarter?: AiStreamStarter,
  ) => {
    await runRequest(async signal => {
      // 同一用户轮次在网络重试时复用稳定键，后端不会创建第二个任务或重放工具。
      const idempotencyKey = createConversationId();
      let assistantId: number | null = null;
      let processId: number | null = null;
      let toolSeen = false;
      let confirmationSeen = false;
      let waitingInputKey = "";
      let finalPhase = false;
      let phaseBuffer = "";
      /** 把已确认的最终正文送入现有平滑流式渲染管线。 */
      const queueFinalContent = (content: string) => {
        if (!content) return;
        if (assistantId === null) {
          assistantId = append("assistant", "");
          streamingMessageIdRef.current = assistantId;
          setStreamingMessageId(assistantId);
        }
        queueStreamChunk(assistantId, content);
      };
      /** 解析跨 chunk 的最终回复边界，标记前文本只保留为过程候选。 */
      const consumeModelChunk = (chunk: string) => {
        if (finalPhase) {
          queueFinalContent(chunk);
          return;
        }
        phaseBuffer += chunk;
        const markerIndex = phaseBuffer.indexOf(FINAL_RESPONSE_MARKER);
        if (markerIndex < 0) return;
        const processText = phaseBuffer.slice(0, markerIndex);
        if (processId !== null) appendProcessThought(processId, processText);
        const finalContent = phaseBuffer.slice(markerIndex + FINAL_RESPONSE_MARKER.length);
        phaseBuffer = "";
        finalPhase = true;
        queueFinalContent(finalContent);
      };
      const startStream = streamStarter || ((
        requestSignal: AbortSignal,
        onChunk: (content: string) => void,
        onToolEvent: (event: AiToolStreamEvent) => void,
        onTask: (taskId: string) => void,
      ) => chatStream({
        message: text,
        deviceContext: device,
        conversationId: conversationIdRef.current,
        webSearchEnabled,
        history,
      }, requestSignal, onChunk, onToolEvent, onTask, idempotencyKey));
      let taskId = "";
      try {
        await startStream(signal, consumeModelChunk, event => {
          toolSeen = true;
          if (typeof event.payload !== "string" && event.payload.confirmationRequired) {
            confirmationSeen = true;
          }
          if (typeof event.payload !== "string" && event.payload.errorCode === "AI_INPUT_REQUIRED") {
            waitingInputKey = inputKeyFromToolResult(event.payload);
          }
          // 工具事件确认当前模型轮次仍属于过程；尚未出现最终标记的文本直接并入过程。
          flushStreamChunk(assistantId);
          if (processId === null) {
            processId = assistantId === null ? appendProcess(processDetail) : convertAssistantToProcess(assistantId, processDetail);
          } else if (assistantId !== null) {
            mergeAssistantIntoProcess(processId, assistantId);
          }
          appendProcessThought(processId, phaseBuffer);
          handleToolEvent(event, processId);
          // 新一轮模型输出必须重新等待最终回复标记，禁止沿用上一轮阶段。
          phaseBuffer = "";
          finalPhase = false;
          assistantId = null;
          streamingMessageIdRef.current = null;
          setStreamingMessageId(null);
        }, value => {
          taskId = value;
          registerTask(value);
        });
      } catch (error) {
        flushStreamChunk(assistantId);
        const recovered = !signal.aborted && taskId
          ? await waitForPersistedTaskResult(taskId, signal)
          : "";
        if (!recovered) {
          // 工具流程中的未完成过渡文本必须归入过程，异常时也不能残留为隐藏消息。
          if (processId !== null && assistantId !== null) {
            mergeAssistantIntoProcess(processId, assistantId);
            assistantId = null;
          }
          throw error;
        }
        phaseBuffer = "";
        finalPhase = true;
        if (assistantId === null) {
          assistantId = append("assistant", recovered);
        } else {
          updateMessage(assistantId, () => recovered);
        }
      }
      // 兼容未实现内部标记的 Provider：只在流完成后把剩余正文作为最终回复，不丢失业务结果。
      if (!finalPhase && phaseBuffer) {
        queueFinalContent(phaseBuffer);
        phaseBuffer = "";
      }
      flushStreamChunk(assistantId);
      completeAssistantMessage(assistantId);
      if (waitingInputKey && taskId) {
        setWaitingInput({ taskId, inputKey: waitingInputKey });
      }
      completeProcess(processId, confirmationSeen || !!waitingInputKey);
      if (assistantId === null && !confirmationSeen && !waitingInputKey) {
        appendError(toolSeen
          ? "AI 执行已中断：工具已经执行，但模型没有返回最终回复。\n\n建议检查：\n- Provider 是否在工具调用后继续输出最终回答\n- 后端是否出现 AI_STREAM_TIMEOUT 或 Provider 断流错误\n- 当前模型是否支持工具调用后的流式续写"
          : "AI 执行已中断：模型没有返回任何内容。\n\n建议检查：\n- Provider 连接是否正常\n- 模型名称、API URL 和 API Key 是否匹配\n- 后端 AI 调用日志是否存在超时或鉴权失败");
      }
    });
  };

  /**
   * 发送普通对话请求。
   */
  const sendMessage = async () => {
    const text = input.trim();
    if (!text || loading || conversationListLoading) return;
    setInput("");
    if (waitingInput) {
      await submitWaitingInput(text, waitingInput);
    } else {
      await submitPrompt(text);
    }
  };

  /** 将用户补充字段提交回原等待任务，并消费后端自动续跑流。 */
  const submitWaitingInput = async (
    value: string,
    pending: { taskId: string; inputKey: string },
  ) => {
    setWaitingInput(null);
    append("user", value);
    await streamAssistantTurn(
      value, [], "提交补充信息并继续原任务。",
      (signal, onChunk, onToolEvent, onTask) => submitAgentInput(
        pending.taskId, conversationIdRef.current, pending.inputKey, value,
        signal, onChunk, onToolEvent, onTask,
      ),
    );
  };

  /**
   * 统一处理输入框和主页快捷操作发起的对话。
   */
  const submitPrompt = async (text: string) => {
    if (!text.trim() || loading || conversationListLoading || submittingRef.current) return;
    submittingRef.current = true;
    beginPreparation();
    try {
      await ensureCurrentConversationPersisted();
    } catch (error) {
      const message = `连续对话上下文保存失败：${error instanceof Error ? error.message : "本地文件不可写"}`;
      appendError(message);
      setHint("历史聊天保存失败，本次问题未发送。");
      submittingRef.current = false;
      endPreparation();
      return;
    }
    try {
      append("user", text);
      // 普通请求不再上传历史正文，后端从 Conversation Store 按 Token 预算构造 Working Memory。
      await streamAssistantTurn(text, []);
    } finally {
      submittingRef.current = false;
    }
  };

  /**
   * 通过按钮分析当前设备最近日志。
   */
  const analyzeCurrentLogs = async () => {
    await requestLogAnalysis("请分析当前设备日志中的异常。", "分析当前设备日志");
  };

  /**
   * 分析当前设备最近日志；无前端快照时继续请求后端，复用服务端 ADB 取数兜底能力。
   */
  const requestLogAnalysis = async (question: string, displayText: string) => {
    if (!device || device.status !== "connected") {
      setHint("当前没有可分析的已连接设备");
      return;
    }
    const logs = getRecentLogs();
    if (logs.length === 0) {
      setHint("当前没有日志快照，正在尝试通过设备工具读取日志。");
    }
    append("user", displayText);
    await runRequest(async signal => {
      const response = await analyzeLogs({
        question,
        deviceContext: device,
        logs,
        limits: { maxLines: 500, maxCharacters: 60000 },
      }, signal);
      append("assistant", formatAnalysisResult(response));
    });
  };

  /**
   * 接收后端工具事件，敏感确认和执行结果都独立展示为卡片。
   */
  const handleToolEvent = (event: AiToolStreamEvent, processId?: number) => {
    if (typeof event.payload === "string") {
      return;
    }
    if (processId !== undefined) {
      appendProcessTool(processId, event.payload);
      return;
    }
    appendToolResult(event.payload);
  };

  /**
   * 确认敏感工具操作并展示执行结果。
   */
  const approveConfirmation = async (token: string) => {
    await decideConfirmation(token, true);
  };

  /**
   * 取消敏感工具操作并展示取消状态。
   */
  const cancelConfirmation = async (token: string) => {
    await decideConfirmation(token, false);
  };

  /**
   * 执行确认或取消动作；失败只影响 AI 面板，不影响主业务状态。
   */
  const decideConfirmation = async (token: string, approve: boolean) => {
    setConfirming(true);
    const controller = new AbortController();
    try {
      if (isAgentConfirmationToken(token)) {
        if (approve) {
          setConfirming(false);
          await continueApprovedAgentConfirmation(token);
        } else {
          await rejectAgentConfirmation(token, conversationIdRef.current, controller.signal);
          const result = agentConfirmationResult(
            findConfirmationToolResult(messagesRef.current, token), token, false,
          );
          replaceConfirmationMessages(token, result);
        }
        return;
      }
      const request = { conversationId: conversationIdRef.current, requestId: createConversationId() };
      const result = approve
        ? await approveToolConfirmation(token, request, controller.signal)
        : await cancelToolConfirmation(token, request, controller.signal);
      const updatedMessages = replaceConfirmationResult(messagesRef.current, token, result);
      messagesRef.current = updatedMessages;
      setMessages(updatedMessages);
    } catch (error) {
      setHint(error instanceof Error ? error.message : "工具确认操作失败");
    } finally {
      setConfirming(false);
    }
  };

  /**
   * 用户批准 Agent 敏感操作后直接消费后端续跑流，前端不再构造内部 Prompt。
   */
  const continueApprovedAgentConfirmation = async (token: string) => {
    const source = findConfirmationToolResult(messagesRef.current, token);
    const markApproved = () => replaceConfirmationMessages(
      token, agentConfirmationResult(source, token, true),
    );
    await streamAssistantTurn(
      "继续执行已批准的敏感操作",
      [],
      "用户已确认敏感操作，后端正在继续原任务。",
      (signal, onChunk, onToolEvent, onTask) => approveAgentConfirmation(
        token, conversationIdRef.current, signal, onChunk, onToolEvent, onTask, markApproved,
      ),
    );
  };

  /**
   * 更新确认卡片和过程时间线，并同步 React 状态与 ref。
   */
  const replaceConfirmationMessages = (token: string, result: AdbMcpToolResult) => {
    const updatedMessages = replaceConfirmationResult(messagesRef.current, token, result);
    messagesRef.current = updatedMessages;
    setMessages(updatedMessages);
  };

  /**
   * 根据确认令牌类型选择对应 MCP 确认接口。
   */
  const approveToolConfirmation = (token: string, request: { conversationId: string; requestId: string }, signal: AbortSignal) => (
    isLocalShellConfirmation(token)
        ? approveLocalShellConfirmation(token, request, signal)
        : approveAdbConfirmation(token, request, signal)
  );

  /**
   * 根据确认令牌类型选择对应 MCP 取消接口。
   */
  const cancelToolConfirmation = (token: string, request: { conversationId: string; requestId: string }, signal: AbortSignal) => (
    isLocalShellConfirmation(token)
        ? cancelLocalShellConfirmation(token, request, signal)
        : cancelAdbConfirmation(token, request, signal)
  );

  /**
   * Local Shell 确认令牌以后端前缀区分，避免前端猜测工具消息文本。
   */
  const isLocalShellConfirmation = (token: string) => token.startsWith("local-");

  // 全屏只改变 AI 容器占位，不改变历史会话、消息流和配置服务的业务逻辑。
  const panelClassName = [
    "fixed inset-y-0 right-0 z-[46] isolate flex overflow-hidden bg-white shadow-2xl",
    "transition-[width,max-width,transform,opacity,border-color,box-shadow] duration-300",
    "ease-[cubic-bezier(0.22,1,0.36,1)] motion-reduce:transition-none dark:bg-[#18181b]",
    fullscreen ? "max-w-none border-0" : "max-w-[calc(100vw-24px)] border-l border-slate-200 dark:border-[#2f3033]",
    visible
      ? "pointer-events-auto translate-x-0 scale-100 opacity-100"
      : fullscreen ? "pointer-events-none translate-x-0 scale-[0.985] opacity-0" : "pointer-events-none translate-x-5 scale-[0.99] opacity-0",
  ].join(" ");
  const panelStyle = { width: fullscreen ? "100vw" : "760px" };
  // 全屏时消息内容与输入区共用同一宽度，保证阅读视线和输入位置一致。
  const conversationContentClassName = fullscreen
    ? "mx-auto w-[60%] transition-[width] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] motion-reduce:transition-none"
    : "mx-auto w-full transition-[width] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] motion-reduce:transition-none";

  return (
    // 只移除透底效果，不改变 AI 助手原本的分区结构和视觉层次。
    <aside className={panelClassName} style={panelStyle} aria-hidden={!visible}>
      <div className="pointer-events-none absolute inset-0 -z-10 bg-white dark:bg-[#18181b]"/>
      <AiConversationHistory
        conversations={conversations}
        activeConversationId={activeConversationId}
        assistantName={ASSISTANT_DISPLAY_NAME}
        deviceConnected={device?.status === "connected"}
        loading={loading || conversationListLoading}
        hasMore={conversations.length < conversationTotal}
        onNewConversation={startNewConversation}
        onSelectConversation={selectConversation}
        onOpenMenu={openHistoryMenu}
        onLoadMore={loadMoreConversationHistory}
        formatTime={formatConversationTime}
      />
      {historyMenu && (
        <div
          className="fixed z-[70] w-28 overflow-hidden rounded-lg border border-border bg-popover py-1 text-[12px] shadow-xl"
          style={{ left: historyMenu.x, top: historyMenu.y }}
          onClick={event => event.stopPropagation()}
        >
          <button
            type="button"
            onClick={() => renameConversation(historyMenu.conversationId)}
            className="block w-full px-3 py-1.5 text-left text-foreground hover:bg-muted"
          >
            重命名
          </button>
          <button
            type="button"
            onClick={() => deleteConversation(historyMenu.conversationId)}
            className="block w-full px-3 py-1.5 text-left text-red-500 hover:bg-red-50 dark:hover:bg-red-950"
          >
            删除
          </button>
        </div>
      )}
      <div className="relative z-10 flex min-w-0 flex-1 flex-col">
      {traceOpen && (
        <AiTracePanel
          trace={trace}
          loading={traceLoading}
          error={traceError}
          onClose={() => setTraceOpen(false)}
        />
      )}
      {/* 内容区工具按钮独立悬浮，不再使用横向顶部栏占用聊天高度。 */}
      <div className="absolute right-3 top-3 z-30 flex items-center gap-0.5 rounded-md border border-slate-200 bg-white p-1 shadow-[0_6px_18px_rgba(15,23,42,0.10)] dark:border-[#343539] dark:bg-[#222327] dark:shadow-[0_6px_18px_rgba(0,0,0,0.28)]">
        <button
          type="button"
          onClick={() => void openTrace()}
          disabled={!currentTaskId || loading}
          title="查看 Agent Trace"
          className="flex h-7 w-7 items-center justify-center rounded text-muted-foreground hover:bg-slate-100 hover:text-foreground disabled:opacity-35 dark:hover:bg-[#2d2e32]"
        >
          <Activity size={14}/>
        </button>
        <button
          type="button"
          onClick={() => setFullscreen(current => !current)}
          title={fullscreen ? "退出全屏" : "全屏"}
          className="flex h-7 w-7 items-center justify-center rounded text-muted-foreground hover:bg-slate-100 hover:text-foreground dark:hover:bg-[#2d2e32]"
        >
          {fullscreen ? <Minimize2 size={14}/> : <Maximize2 size={14}/>}
        </button>
        <button
          type="button"
          onClick={onOpenConfig}
          disabled={loading}
          title="切换模型设置"
          className="flex h-7 w-7 items-center justify-center rounded text-muted-foreground hover:bg-slate-100 hover:text-foreground disabled:opacity-45 dark:hover:bg-[#2d2e32]"
        >
          <Settings size={14}/>
        </button>
        <button
          type="button"
          onClick={onClose}
          title="关闭"
          className="flex h-7 w-7 items-center justify-center rounded text-muted-foreground hover:bg-slate-100 hover:text-foreground dark:hover:bg-[#2d2e32]"
        >
          <X size={15}/>
        </button>
      </div>

      {showQuickActions && (
      <div className="flex items-center gap-2 border-b border-border bg-card px-3 py-2">
        <button
          type="button"
          onClick={analyzeCurrentLogs}
          disabled={loading || !device || device.status !== "connected"}
          className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-border bg-background px-2.5 text-[12px] text-foreground shadow-sm hover:bg-muted disabled:opacity-45"
        >
          <Terminal size={13}/>
          分析日志
        </button>
        <button
          type="button"
          onClick={onStartLogCapture}
          disabled={loading || streaming || !device || device.status !== "connected"}
          className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-border bg-background px-2.5 text-[12px] text-foreground shadow-sm hover:bg-muted disabled:opacity-45"
        >
          <Play size={13}/>
          {streaming ? "采集中" : "开始采集"}
        </button>
        {loading && (
          <button
            type="button"
            onClick={cancelCurrent}
            className="ml-auto inline-flex h-8 items-center gap-1.5 rounded-lg border border-red-200 px-2 text-[12px] text-red-500 hover:bg-red-50 dark:border-red-900 dark:hover:bg-red-950"
          >
            <Square size={12}/>
            取消
          </button>
        )}
      </div>
      )}

      {showHeaderDeviceContext && (
      <div className="border-b border-border bg-card px-3 py-2 text-[11px] text-muted-foreground">
        {device ? (
          <div className="grid grid-cols-2 gap-1.5">
            <span className="truncate rounded-md bg-background px-2 py-1 ring-1 ring-border">平台：{device.platform}</span>
            <span className="truncate rounded-md bg-background px-2 py-1 ring-1 ring-border">状态：{device.status}</span>
            <span className="truncate rounded-md bg-background px-2 py-1 ring-1 ring-border">型号：{device.model}</span>
            <span className="truncate rounded-md bg-background px-2 py-1 ring-1 ring-border">系统：{device.osVersion}</span>
          </div>
        ) : (
          <span>当前没有可分析的已连接设备</span>
        )}
      </div>
      )}

      {hint && (
        <div className="mx-3 mt-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-[12px] text-amber-700 shadow-sm dark:border-amber-900 dark:bg-amber-950 dark:text-amber-500">
          {hint}
        </div>
      )}

      <div className="relative flex-1 overflow-hidden">
        {/* 聊天内容区需要覆盖主应用的全局 select-none，支持复制 AI 回复、用户消息和工具结果。 */}
        <div
          ref={scrollContainerRef}
          onScroll={updateScrollBottomState}
          className="h-full space-y-3 overflow-y-auto bg-white px-3 py-4 pb-2 select-text will-change-transform dark:bg-[#18181b]"
          style={{ backfaceVisibility: "hidden", contain: "paint", transform: "translateZ(0)" }}
        >
          {/* 空会话占满聊天视口，欢迎区域在悬浮输入框上方的剩余空间内双向居中。 */}
          <div className={`${conversationContentClassName} ${showHome ? "flex min-h-full flex-col" : "space-y-3"}`}>
          {showHome && (
            <AiHomePanel
              title={ASSISTANT_HOME_TITLE}
              prompts={QUICK_PROMPTS}
              disabled={loading || conversationListLoading}
              onPrompt={prompt => void submitPrompt(prompt)}
            />
          )}
          {visibleMessages.map(message => (
            message.kind === "process" && message.process ? (
              <AiProcessCard
                key={message.id}
                messageId={message.id}
                process={message.process}
                confirming={confirming}
                onToggle={toggleProcess}
                onApprove={approveConfirmation}
                onCancel={cancelConfirmation}
              />
            ) : message.kind === "tool" && message.toolResult ? (
              message.toolResult.confirmationRequired ? (
                <AiConfirmationCard
                  key={message.id}
                  result={message.toolResult}
                  busy={confirming}
                  onApprove={approveConfirmation}
                  onCancel={cancelConfirmation}
                />
              ) : (
                <AiToolCallCard
                  key={message.id}
                  toolName={toolDisplayName(message.toolResult)}
                  result={message.toolResult}
                  running={false}
                />
              )
            ) : (
              <AiMessageBubble key={message.id} message={message} streaming={message.id === streamingMessageId}/>
            )
          ))}
          {loading && !hasActiveProcess && (
            <div className="mr-8 flex min-w-0 items-center gap-2 px-1 py-1 text-[12px] text-muted-foreground">
              <AiThinkingIcon/>
              <span className="shrink-0 font-medium">正在思考</span>
              <span className="min-w-0 truncate text-[11px]">正在理解问题并选择处理方式</span>
            </div>
          )}
          {/* 底部 spacer 只负责避让悬浮输入框，不再依赖额外色块承托，避免底部出现灰色区域。 */}
          <div aria-hidden="true" style={{ height: inputPanelHeight }}/>
          <div ref={endRef}/>
          </div>
        </div>

        {!atScrollBottom && (
          <button
            type="button"
            onClick={returnToChatBottom}
            title="回到底部"
            className={`absolute z-20 flex h-9 w-9 items-center justify-center rounded-full border border-border bg-card text-muted-foreground shadow-[0_10px_24px_rgba(15,23,42,0.16)] transition-colors hover:border-primary hover:text-primary dark:shadow-[0_10px_24px_rgba(0,0,0,0.32)] ${fullscreen ? "left-1/2" : "right-4"}`}
            style={fullscreen ? { bottom: inputPanelHeight + 18, marginLeft: 238 } : { bottom: inputPanelHeight + 18 }}
          >
            <ArrowDown size={16}/>
          </button>
        )}

        <div ref={inputPanelRef} className="absolute inset-x-0 bottom-0 z-10 p-3">
          <div className={conversationContentClassName}>
          <div className="flex flex-col rounded-xl border border-border bg-card p-2.5 shadow-[0_16px_34px_rgba(15,23,42,0.16)] transition-shadow focus-within:border-primary focus-within:shadow-[0_18px_40px_rgba(37,99,235,0.16)] dark:shadow-[0_16px_34px_rgba(0,0,0,0.34)]">
            <textarea
              ref={inputRef}
              value={input}
              onChange={event => setInput(event.target.value)}
              onKeyDown={event => {
                if (event.key === "Enter" && !event.shiftKey) {
                  event.preventDefault();
                  void sendMessage();
                }
              }}
              disabled={loading || conversationListLoading}
              placeholder={waitingInput ? `请补充 ${waitingInput.inputKey}` : "输入问题，按 Enter 发送"}
              rows={3}
              className="max-h-40 min-h-[60px] w-full resize-none bg-transparent text-[13px] leading-5 outline-none placeholder:text-muted-foreground"
            />
            {/* 输入框与工具区共用同一卡片，正文可使用完整宽度，模型和操作按钮在下方横向排列。 */}
            <div className="mt-1.5 flex h-8 min-w-0 items-center justify-between gap-2">
              <button
                type="button"
                onClick={() => void toggleWebSearch()}
                disabled={loading || checkingWebSearch}
                aria-pressed={webSearchEnabled}
                className={`flex h-7 shrink-0 items-center gap-1.5 rounded-md border px-2 text-[11px] transition-colors disabled:opacity-50 ${webSearchEnabled
                  ? "border-blue-300 bg-blue-50 text-blue-600 dark:border-blue-700 dark:bg-blue-950/50 dark:text-blue-400"
                  : "border-slate-200 bg-transparent text-muted-foreground hover:bg-slate-50 dark:border-[#3a3b3f] dark:hover:bg-[#292a2e]"}`}
              >
                {checkingWebSearch ? <Loader2 size={13} className="animate-spin"/> : <Globe2 size={13}/>}智能联网搜索
              </button>
              <div className="flex min-w-0 items-center gap-2">
                <span title={modelLabel} className="max-w-[180px] truncate text-[10px] text-muted-foreground/70">
                  {modelLabel}
                </span>
                {loading ? (
                  <button
                    type="button"
                    onClick={() => void cancelCurrent()}
                    title="停止任务"
                    className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full border border-red-200 bg-card text-red-500 hover:bg-red-50 dark:border-red-900 dark:hover:bg-red-950"
                  >
                    <Square size={13}/>
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={sendMessage}
                    disabled={conversationListLoading || !input.trim()}
                    className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary text-white shadow-sm transition-transform hover:scale-[1.03] disabled:scale-100 disabled:opacity-45"
                  >
                    <ArrowUp size={15}/>
                  </button>
                )}
              </div>
            </div>
          </div>
          <div className="mt-2 flex h-5 max-w-full items-center gap-1.5 overflow-hidden text-[10px] text-muted-foreground">
            {deviceTags.length > 0 ? deviceTags.map(tag => (
              <span
                key={tag.label}
                title={`${tag.label}：${tag.value}`}
                className="inline-flex min-w-0 max-w-[118px] shrink items-center gap-1 rounded-full border border-border bg-muted px-2 py-0.5"
              >
                <span className="shrink-0 text-foreground/60">{tag.label}</span>
                <span className="truncate">{tag.value}</span>
              </span>
            )) : (
              <span className="inline-flex items-center rounded-full border border-border bg-muted px-2 py-0.5">未选择设备</span>
            )}
          </div>
          </div>
        </div>
        {webSearchPromptOpen && (
          <div className="absolute inset-0 z-50 flex items-center justify-center bg-black/20 p-4">
            <div role="dialog" aria-modal="true" aria-labelledby="web-search-config-title" className="w-full max-w-sm rounded-lg border border-border bg-background p-4 shadow-xl">
              <h3 id="web-search-config-title" className="text-sm font-semibold text-foreground">需要配置 Tavily</h3>
              <p className="mt-2 text-[12px] leading-5 text-muted-foreground">启用智能联网搜索前，请先配置 Tavily API URL 和 API Key。</p>
              <div className="mt-4 flex justify-end gap-2">
                <button type="button" onClick={() => setWebSearchPromptOpen(false)} className="h-8 rounded-md px-3 text-[12px] text-muted-foreground hover:bg-muted">取消</button>
                <button
                  type="button"
                  onClick={() => {
                    setWebSearchPromptOpen(false);
                    onOpenNetworkConfig();
                  }}
                  className="h-8 rounded-md bg-primary px-3 text-[12px] text-white hover:bg-primary/90"
                >
                  前往设置
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
      </div>
    </aside>
  );
}

/**
 * 创建模型前置文本过程项，记录可观察到的执行计划。
 */
function thoughtEntry(detail: string): AiProcessEntry {
  return {
    id: nextId(),
    type: "thought",
    label: "模型前置说明",
    detail: detail.trim() || "准备处理当前问题。",
    status: "running",
  };
}

/**
 * 构造任务理解步骤；只展示用户可见任务摘要，不展示模型不可见的内部推理链。
 */
function processInitialDetail(detail: string) {
  const normalized = detail.trim().replace(/\s+/g, " ");
  return normalized
    ? `理解任务：${limitText(normalized, 260, "…")}`
    : "理解任务：准备根据当前上下文选择下一步操作。";
}

/**
 * 创建工具选择过程项，让工具调用前的意图和命令摘要按时间线可见。
 */
function toolDecisionEntry(result: AdbMcpToolResult): AiProcessEntry {
  return {
    id: nextId(),
    type: "thought",
    label: result.confirmationRequired ? "等待确认" : "选择工具",
    detail: toolDecisionDetail(result),
    status: result.confirmationRequired ? "running" : "success",
  };
}

/** 从等待输入工具的结构化输出读取输入项标识，格式异常时使用稳定兜底。 */
function inputKeyFromToolResult(result: AdbMcpToolResult) {
  try {
    const output = JSON.parse(result.stdout || "{}") as { inputKey?: unknown };
    return typeof output.inputKey === "string" && output.inputKey.trim()
      ? output.inputKey.trim()
      : "必要信息";
  } catch {
    return "必要信息";
  }
}

/**
 * 根据工具结果生成可观察过程说明；这是操作摘要，不是模型内部推理。
 */
function toolDecisionDetail(result: AdbMcpToolResult) {
  const command = result.commandSummary ? `：${result.commandSummary}` : "";
  if (result.confirmationRequired) {
    return `${toolDisplayName(result)} 请求执行敏感操作${command}，等待用户确认后继续。`;
  }
  if (result.status === "FAILED") {
    return `${toolDisplayName(result)} 执行失败${command}，需要结合错误输出判断下一步。`;
  }
  if (result.status === "CANCELED") {
    return `${toolDisplayName(result)} 操作已取消${command}。`;
  }
  return `${toolDisplayName(result)} 已执行${command}，继续基于结果分析。`;
}

/**
 * 创建工具执行过程项，统一标记执行状态。
 */
function toolEntry(result: AdbMcpToolResult): AiProcessEntry {
  return {
    id: nextId(),
    type: "tool",
    label: toolDisplayName(result),
    detail: result.message || result.errorCode || "工具调用完成",
    status: result.status === "FAILED" ? "failed" : "success",
    toolResult: result,
  };
}

/**
 * 解析工具展示标题；旧历史数据没有 toolTitle 时按错误码和确认令牌做兼容兜底。
 */
function toolDisplayName(result: AdbMcpToolResult | undefined) {
  if (result?.toolTitle) {
    return cleanToolDisplayName(result.toolTitle);
  }
  if (result?.confirmationToken?.startsWith("local-") || result?.errorCode?.startsWith("LOCAL_SHELL")) {
    return "本机终端";
  }
  return "ADB 工具";
}

/**
 * 去掉工具族展示名中的协议实现词，用户只需要看到实际工具类别。
 */
function cleanToolDisplayName(name: string) {
  return name.replace(/\s*MCP\b/gi, "").replace(/^Local Shell$/i, "本机终端").replace(/^ADB$/i, "ADB 工具").trim();
}

/**
 * 用户确认后的真实执行发生在确认动作之后，必须按当前时间线追加结果，不能替换原确认卡片。
 */
function replaceConfirmationResult(messages: AiMessage[], token: string, result: AdbMcpToolResult) {
  let appendedToProcess = false;
  const updated = messages.map(message => {
    if (message.toolResult?.confirmationToken === token) {
      return sanitizeMessageForState({
        ...message,
        toolResult: resolvedConfirmationResult(message.toolResult, result),
      });
    }
    if (!message.process || !message.process.entries.some(entry => entry.toolResult?.confirmationToken === token)) {
      return message;
    }
    appendedToProcess = true;
    return sanitizeMessageForState({
      ...message,
      process: {
        ...message.process,
        active: false,
        expanded: false,
        finishedAt: Date.now(),
        entries: message.process.entries.map(entry => (
          entry.toolResult?.confirmationToken === token ? resolvedConfirmationEntry(entry, result) : entry
        )).concat(toolEntry(result)),
      },
    });
  });
  if (appendedToProcess) {
    return updated;
  }
  return updated.concat(sanitizeMessageForState({ id: nextId(), role: "assistant", content: "", kind: "tool", toolResult: result }));
}

/**
 * 确认卡片被处理后转为历史步骤，避免按钮继续显示并避免过程标题一直停留在等待确认。
 */
function resolvedConfirmationEntry(entry: AiProcessEntry, result: AdbMcpToolResult): AiProcessEntry {
  if (!entry.toolResult) {
    return entry;
  }
  const approved = result.status !== "CANCELED";
  return {
    ...entry,
    status: approved ? "success" : "failed",
    detail: approved ? "用户已确认敏感操作，继续执行绑定命令。" : "用户已取消敏感操作。",
    toolResult: resolvedConfirmationResult(entry.toolResult, result),
  };
}

/**
 * 将待确认结果转为已处理结果，保留命令摘要但移除确认按钮。
 */
function resolvedConfirmationResult(pending: AdbMcpToolResult, result: AdbMcpToolResult): AdbMcpToolResult {
  const approved = result.status !== "CANCELED";
  return {
    ...pending,
    status: approved ? "SUCCESS" : "CANCELED",
    confirmationRequired: false,
    confirmationToken: "",
    message: approved ? "用户已确认敏感操作，继续执行绑定命令。" : "用户已取消敏感操作。",
  };
}

/**
 * 裁剪非最终回复正文的辅助文本，例如工具输出、过程详情和历史持久化副本。
 */
function limitText(content: string, maxLength: number, notice: string) {
  if (!content || content.length <= maxLength) {
    return content || "";
  }
  const safeLength = Math.max(0, maxLength - notice.length);
  return `${content.slice(0, safeLength)}${notice}`;
}

/**
 * 当前会话和后端历史都完整保留 AI 正文；可选长度只供非正文展示场景复用。
 */
function limitMessageContent(content: string, maxLength: number | null) {
  return maxLength === null ? content || "" : limitText(content, maxLength, "\n[消息过长，显示副本已压缩]");
}

/**
 * 清洗单条消息供当前页面状态使用；AI 正文完整保留，工具和过程输出继续压缩。
 */
function sanitizeMessageForState(message: AiMessage) {
  return sanitizeMessage(message, {
    contentLength: null,
    preserveContentSegments: true,
    toolOutputLength: TOOL_OUTPUT_MAX_CONTENT_LENGTH,
    processDetailLength: PROCESS_DETAIL_MAX_CONTENT_LENGTH,
    maxProcessEntries: PROCESS_MAX_ENTRIES,
  });
}

/**
 * 清洗单条消息供后端历史文件持久化使用；最终回复完整保留，工具大输出继续使用摘要。
 */
function sanitizeMessageForConversationStorage(message: AiMessage) {
  return sanitizeMessage(message, {
    contentLength: null,
    preserveContentSegments: true,
    toolOutputLength: CONVERSATION_PERSISTED_TOOL_OUTPUT_MAX_LENGTH,
    processDetailLength: CONVERSATION_PERSISTED_TOOL_OUTPUT_MAX_LENGTH,
    maxProcessEntries: 20,
  });
}

interface MessageSanitizeOptions {
  contentLength: number | null;
  preserveContentSegments: boolean;
  toolOutputLength: number;
  processDetailLength: number;
  maxProcessEntries: number;
}

/**
 * 统一清洗消息；当前会话保留 AI 正文完整性，非正文大字段按场景压缩。
 */
function sanitizeMessage(message: AiMessage, options: MessageSanitizeOptions): AiMessage {
  const processEntries = Array.isArray(message.process?.entries) ? message.process.entries : [];
  const process = message.process ? {
    ...message.process,
    entries: processEntries.slice(-options.maxProcessEntries).map(entry => ({
      ...entry,
      detail: limitText(entry.detail || "", options.processDetailLength, TOOL_OUTPUT_TRUNCATED_NOTICE),
      toolResult: entry.toolResult ? sanitizeToolResult(entry.toolResult, options.toolOutputLength) : undefined,
    })),
  } : undefined;
  const preserveSegments = options.preserveContentSegments
    && Array.isArray(message.contentSegments)
    && message.contentSegments.length > 0;
  // 长回复保持稳定分段，避免页面同步和历史保存前反复生成完整正文副本。
  const content = preserveSegments
    ? limitMessageContent(message.content || "", options.contentLength)
    : limitMessageContent(materializeContent(message.content || "", message.contentSegments), options.contentLength);
  return {
    ...message,
    content,
    contentSegments: preserveSegments ? message.contentSegments : undefined,
    toolResult: message.toolResult ? sanitizeToolResult(message.toolResult, options.toolOutputLength) : undefined,
    process,
  };
}

/**
 * 裁剪 ADB MCP 输出；前端卡片只展示摘要，完整大输出不应长期驻留在 React 状态和历史存储中。
 */
function sanitizeToolResult(result: AdbMcpToolResult, maxOutputLength: number): AdbMcpToolResult {
  const stdout = limitText(result.stdout || "", maxOutputLength, TOOL_OUTPUT_TRUNCATED_NOTICE);
  const stderr = limitText(result.stderr || "", maxOutputLength, TOOL_OUTPUT_TRUNCATED_NOTICE);
  const commandSummary = limitText(result.commandSummary || "", 2000, TOOL_OUTPUT_TRUNCATED_NOTICE);
  return {
    ...result,
    stdout,
    stderr,
    commandSummary,
    truncated: result.truncated || stdout !== (result.stdout || "") || stderr !== (result.stderr || "") || commandSummary !== (result.commandSummary || ""),
  };
}

/**
 * 从消息和过程时间线中查找指定待确认工具结果。
 */
function findConfirmationToolResult(messages: AiMessage[], token: string): AdbMcpToolResult | undefined {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (message.toolResult?.confirmationToken === token) {
      return message.toolResult;
    }
    const entries = message.process?.entries || [];
    for (let entryIndex = entries.length - 1; entryIndex >= 0; entryIndex -= 1) {
      if (entries[entryIndex].toolResult?.confirmationToken === token) {
        return entries[entryIndex].toolResult;
      }
    }
  }
  return undefined;
}

/**
 * 将 Agent 确认决策映射为既有工具结果；批准只代表授权，不伪造工具已执行输出。
 */
function agentConfirmationResult(
  source: AdbMcpToolResult | undefined,
  token: string,
  approved: boolean,
): AdbMcpToolResult {
  return {
    status: approved ? "SUCCESS" : "CANCELED",
    stdout: "",
    stderr: "",
    exitCode: null,
    timedOut: false,
    durationMillis: 0,
    truncated: false,
    riskLevel: source?.riskLevel || "HIGH",
    confirmationRequired: false,
    confirmationToken: token,
    message: approved ? "用户已授权，等待 AI 执行原工具调用" : "用户已取消敏感操作",
    errorCode: "",
    toolTitle: source?.toolTitle || "工具",
    commandSummary: source?.commandSummary || source?.message || "",
  };
}

/**
 * 创建页面启动占位会话；真实历史会在组件挂载后从后端文件分页加载。
 */
function createInitialConversationStore() {
  const fallback = createConversationSession();
  return { conversations: [fallback], activeConversationId: fallback.id, messages: fallback.messages };
}

/**
 * 读取旧浏览器会话，仅作为一次性迁移来源，解析失败时保留原键供人工恢复。
 */
function readLegacyConversationStore(): LegacyConversationStore | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.localStorage.getItem(CONVERSATION_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { conversations?: AiConversationSession[]; activeConversationId?: string };
    const conversations = normalizeConversations(parsed.conversations);
    if (conversations.length === 0) return null;
    const activeConversationId = conversations.some(item => item.id === parsed.activeConversationId)
      ? String(parsed.activeConversationId)
      : conversations[0].id;
    return { conversations, activeConversationId };
  } catch {
    return null;
  }
}

/**
 * 幂等迁移旧浏览器历史；只有后端确认全部写入成功后才删除 localStorage 键。
 */
async function migrateLegacyConversationStore() {
  const legacy = readLegacyConversationStore();
  if (!legacy) return;
  await migrateConversations({
    conversations: legacy.conversations.map(conversation => ({
      id: conversation.id,
      title: conversationTitle(conversation),
      titleEdited: !!conversation.titleEdited,
      messages: messagesForConversationStorage(conversation.messages),
      createdAt: conversation.createdAt,
      updatedAt: conversation.updatedAt,
    })),
    activeConversationId: legacy.activeConversationId,
  });
  window.localStorage.removeItem(CONVERSATION_STORAGE_KEY);
}

/**
 * 确保至少存在一个后端会话，并读取后端记录的活动会话详情。
 */
async function loadInitialConversationPage(page: AiConversationPage) {
  let effectivePage = page;
  if (effectivePage.items.length === 0) {
    const session = createConversationSession();
    await saveConversation(session.id, {
      title: session.title,
      titleEdited: false,
      messages: [],
      createdAt: session.createdAt,
      updatedAt: session.updatedAt,
      active: true,
    });
    effectivePage = await listConversations(0, CONVERSATION_PAGE_SIZE);
  }
  const activeId = effectivePage.items.some(item => item.id === effectivePage.activeConversationId)
    ? effectivePage.activeConversationId
    : effectivePage.items[0].id;
  const active = await getConversation(activeId, CONVERSATION_MESSAGE_LOAD_LIMIT);
  return { page: effectivePage, active };
}

/**
 * 把后端摘要转换为列表会话，非活动项不持有消息正文。
 */
function conversationSummaryToSession(summary: AiConversationSummary): AiConversationSession {
  return {
    id: summary.id,
    title: summary.title || CONVERSATION_DEFAULT_TITLE,
    titleEdited: summary.titleEdited,
    messages: [],
    createdAt: summary.createdAt,
    updatedAt: summary.updatedAt,
  };
}

/**
 * 把后端详情转换为当前可展示会话并恢复稳定消息 ID。
 */
function conversationDetailToSession(detail: AiConversationDetail): AiConversationSession {
  return {
    ...conversationSummaryToSession(detail),
    messages: restoreMessages(detail.messages),
  };
}

/**
 * 在原列表位置替换会话详情，选择历史聊天时不会改变排序。
 */
function replaceConversationSession(
  conversations: AiConversationSession[],
  replacement: AiConversationSession,
) {
  const found = conversations.some(item => item.id === replacement.id);
  return found
    ? conversations.map(item => item.id === replacement.id ? replacement : item)
    : conversations.concat(replacement);
}

/**
 * 合并后续分页摘要，已加载会话继续保留其消息尾部。
 */
function mergeConversationSummaries(
  conversations: AiConversationSession[],
  summaries: AiConversationSummary[],
) {
  const existing = new Map(conversations.map(item => [item.id, item]));
  const next = [...conversations];
  for (const summary of summaries) {
    const current = existing.get(summary.id);
    if (current) {
      const index = next.findIndex(item => item.id === summary.id);
      next[index] = { ...conversationSummaryToSession(summary), messages: current.messages };
    } else {
      next.push(conversationSummaryToSession(summary));
    }
  }
  return next;
}

/**
 * 将当前消息同步回活动会话，同时自动更新默认标题和更新时间。
 */
function syncActiveConversation(conversations: AiConversationSession[], activeConversationId: string, messages: AiMessage[], updateTimestamp: boolean) {
  const restoredMessages = messagesForState(messages);
  const synced = conversations.map(conversation => {
    if (conversation.id !== activeConversationId) {
      return conversation;
    }
    const changed = conversationMessageSignature(conversation.messages) !== conversationMessageSignature(restoredMessages);
    return {
      ...conversation,
      title: conversation.titleEdited ? conversation.title : deriveConversationTitle(restoredMessages),
      messages: restoredMessages,
      // 点击历史对话只加载内容，不改变排序；只有消息真正变化时才刷新更新时间。
      updatedAt: updateTimestamp && changed ? Date.now() : conversation.updatedAt,
    };
  });
  return synced
    .sort((left, right) => right.updatedAt - left.updatedAt);
}

/**
 * 生成会话消息签名，用于判断是否真的发生内容变化。
 */
function conversationMessageSignature(messages: AiMessage[]) {
  return messages.map(message => {
    const process = message.process;
    const processEntryCount = Array.isArray(process?.entries) ? process.entries.length : 0;
    const processState = process
      ? `${process.active}:${process.expanded}:${process.startedAt || 0}:${process.finishedAt || 0}:${processEntryCount}`
      : "";
    const segments = message.contentSegments || [];
    const content = message.content || "";
    const contentLength = segments.length > 0
      ? segments.reduce((total, segment) => total + segment.length, 0)
      : content.length;
    const contentStart = segments.length > 0 ? segments[0].slice(0, 64) : content.slice(0, 64);
    const contentEnd = segments.length > 0 ? segments[segments.length - 1].slice(-64) : content.slice(-64);
    // 签名只采样头尾和长度，不拼接完整大文本，避免长回复同步时制造额外大字符串副本。
    return [
      message.id,
      message.role,
      message.kind || "text",
      message.error ? "error" : "ok",
      message.timestamp || 0,
      contentLength,
      contentStart,
      contentEnd,
      message.toolResult?.status || "",
      processState,
    ].join(":");
  }).join("|");
}

/**
 * 规范化已存储的会话列表，过滤异常项并恢复消息 ID 计数器。
 */
function normalizeConversations(value: AiConversationSession[] | undefined) {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .filter(item => typeof item?.id === "string")
    .map(item => ({
      id: item.id,
      title: item.title || CONVERSATION_DEFAULT_TITLE,
      titleEdited: !!item.titleEdited,
      messages: restoreMessages(item.messages),
      createdAt: typeof item.createdAt === "number" ? item.createdAt : Date.now(),
      updatedAt: typeof item.updatedAt === "number" ? item.updatedAt : Date.now(),
    }))
    .sort((left, right) => right.updatedAt - left.updatedAt);
}

/**
 * 创建新对话会话，默认只包含 Bridge Copilot 欢迎语。
 */
function createConversationSession(): AiConversationSession {
  const now = Date.now();
  const messages = welcomeMessages();
  return {
    id: createConversationId(),
    title: CONVERSATION_DEFAULT_TITLE,
    titleEdited: false,
    messages,
    createdAt: now,
    updatedAt: now,
  };
}

/**
 * 创建欢迎消息；每个会话独立生成 ID，避免 React key 冲突。
 */
function welcomeMessages(): AiMessage[] {
  // 新会话首页由 AiHomePanel 渲染，不再写入普通系统消息，避免污染上下文和历史聊天。
  return [];
}

/**
 * 恢复消息列表；未完成的过程在重开应用后标记为完成，避免历史会话长期显示运行态。
 */
function restoreMessages(messages: AiMessage[] | undefined) {
  const restored = messagesForState(messages);
  syncNextMessageId(restored);
  return restored;
}

/**
 * 恢复当前页面可展示的消息；历史中的运行态过程统一收束为完成态。
 */
function messagesForState(messages: AiMessage[] | undefined) {
  return normalizeMessages(messages, sanitizeMessageForState);
}

/**
 * 生成发往后端文件存储的消息尾部；旧消息由后端按 ID 合并保留。
 */
function messagesForConversationStorage(messages: AiMessage[] | undefined) {
  return normalizeMessages(messages, sanitizeMessageForConversationStorage)
    .slice(-CONVERSATION_WRITE_MAX_MESSAGES);
}

/**
 * 规范化消息结构，兼容旧版本历史数据并清理未完成过程状态。
 */
function normalizeMessages(messages: AiMessage[] | undefined, sanitizer: (message: AiMessage) => AiMessage) {
  if (!Array.isArray(messages) || messages.length === 0) {
    return welcomeMessages();
  }
  return messages.filter(message => !isLegacyHomeMessage(message)).map(message => sanitizer({
    ...message,
    kind: message.kind || "text",
    process: message.process ? {
      ...message.process,
      active: false,
      expanded: false,
      entries: (Array.isArray(message.process.entries) ? message.process.entries : []).map(entry => ({
        ...entry,
        status: entry.status === "running" ? "success" as const : entry.status,
      })),
    } : undefined,
  }));
}

/**
 * 过滤旧版本写入历史的欢迎系统消息，空会话主页不应该作为聊天内容参与渲染和上下文。
 */
function visibleConversationMessages(messages: AiMessage[]) {
  return messages.filter(message => !isLegacyHomeMessage(message));
}

/**
 * 判断旧欢迎语消息；兼容改名前后的欢迎文案。
 */
function isLegacyHomeMessage(message: AiMessage) {
  return message.role === "system"
    && message.kind !== "process"
    && (
      message.content === `${ASSISTANT_DISPLAY_NAME} 已就绪，可进行普通对话或分析当前设备日志。`
      || message.content === "AI 助手已就绪，可进行普通对话或分析当前设备日志。"
    );
}

/**
 * 根据已有消息推进全局消息 ID，防止加载历史后新增消息 key 重复。
 */
function syncNextMessageId(messages: AiMessage[]) {
  const maxId = messages.reduce((value, message) => Math.max(value, message.id), nextMessageId);
  nextMessageId = Math.max(nextMessageId, maxId);
}


/**
 * 生成消息 ID，保持消息列表 key 稳定。
 */
function nextId() {
  nextMessageId += 1;
  return nextMessageId;
}
