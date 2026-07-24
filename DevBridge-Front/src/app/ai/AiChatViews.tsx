/**
 * AI 聊天纯展示组件，保持会话控制、确认和恢复逻辑位于 AiChatPanel。
 *
 * by AI.Coding
 */
import React from "react";
import { Activity, Bot, Check, ChevronDown, ChevronRight, Copy, MessageSquare, Plus, Sparkles, Terminal } from "lucide-react";
import { AiConfirmationCard } from "./AiConfirmationCard";
import { AiToolCallCard } from "./AiToolCallCard";
import { materializeContent } from "./aiStreamSegments";
import { MarkdownContent } from "./MarkdownContent";
import { AgentTrace, AiMessage, AiProcessEntry, AiProcessState } from "./aiTypes";

export interface AiConversationListItem {
  id: string;
  title: string;
  updatedAt: number;
}

interface AiHomePanelProps {
  title: string;
  prompts: string[];
  disabled: boolean;
  onPrompt: (prompt: string) => void;
}

/** 渲染空会话主页，不把欢迎文案写入历史。 */
export function AiHomePanel({ title, prompts, disabled, onPrompt }: AiHomePanelProps) {
  return (
    <div className="flex min-h-0 flex-1 items-center justify-center px-6 py-10">
      <div className="mx-auto flex w-full max-w-[760px] flex-col items-center text-center">
        <h2 className="text-[20px] font-semibold leading-7 text-foreground">{title}</h2>
        <p className="mt-2 text-[12px] text-muted-foreground">选择一个常用手机诊断任务，或直接在下方输入你的问题。</p>
        <div className="mt-6 flex max-w-full flex-wrap items-center justify-center gap-2">
          {prompts.map(prompt => (
            <button
              key={prompt}
              type="button"
              disabled={disabled}
              onClick={() => onPrompt(prompt)}
              className="inline-flex w-auto max-w-none items-center whitespace-nowrap rounded-full bg-slate-100 px-3 py-1.5 text-left text-[12px] font-normal leading-5 text-slate-900 transition-colors hover:bg-slate-200 disabled:opacity-45 dark:bg-[#2a2b2e] dark:text-zinc-100 dark:hover:bg-[#303236]"
            >
              {prompt}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

interface AiConversationHistoryProps {
  conversations: AiConversationListItem[];
  activeConversationId: string;
  assistantName: string;
  deviceConnected: boolean;
  loading: boolean;
  hasMore: boolean;
  formatTime: (value: number) => string;
  onNewConversation: () => void;
  onSelectConversation: (conversationId: string) => void;
  onOpenMenu: (conversationId: string, event: React.MouseEvent) => void;
  onLoadMore: () => void;
}

/** 渲染左侧历史聊天列表。 */
export function AiConversationHistory(props: AiConversationHistoryProps) {
  const { conversations, activeConversationId, loading, hasMore, formatTime } = props;
  return (
    <div className="relative z-10 flex w-[188px] shrink-0 flex-col border-r border-slate-200 bg-slate-50 shadow-[8px_0_18px_rgba(15,23,42,0.035)] dark:border-[#2f3033] dark:bg-[#1f2023] dark:shadow-[8px_0_18px_rgba(0,0,0,0.14)]">
      <div className="px-3 pb-2 pt-3">
        <div className="flex min-w-0 items-center gap-2.5">
          <div className="relative flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-primary/20 bg-primary/10 text-primary">
            <Bot size={16}/>
            <span className={`absolute -right-0.5 -top-0.5 h-2 w-2 rounded-full ring-2 ring-slate-50 dark:ring-[#1f2023] ${props.deviceConnected ? "bg-emerald-500" : "bg-slate-400"}`}/>
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate text-[12px] font-semibold text-foreground">{props.assistantName}</p>
          </div>
        </div>
      </div>
      <div className="px-2.5 pb-1 pt-1">
        <button
          type="button"
          onClick={props.onNewConversation}
          disabled={loading}
          className="flex h-8 w-full items-center justify-center gap-1.5 rounded-lg border border-slate-200 bg-white text-[12px] font-medium text-foreground shadow-sm transition-colors hover:border-blue-300 hover:bg-slate-100 disabled:opacity-45 dark:border-[#2f3033] dark:bg-[#25262a] dark:hover:border-blue-700 dark:hover:bg-[#2b2c30]"
        >
          <Plus size={13}/>
          新对话
        </button>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-2 pt-4">
        <div className="mb-2 px-1 text-[12px] font-medium text-muted-foreground">历史聊天</div>
        <div className="space-y-1">
          {conversations.map(conversation => (
            <ConversationListButton
              key={conversation.id}
              conversation={conversation}
              active={conversation.id === activeConversationId}
              loading={loading}
              formattedTime={formatTime(conversation.updatedAt)}
              onSelect={props.onSelectConversation}
              onOpenMenu={props.onOpenMenu}
            />
          ))}
          {hasMore && (
            <button
              type="button"
              onClick={props.onLoadMore}
              disabled={loading}
              className="w-full rounded-md px-2 py-2 text-center text-[11px] text-muted-foreground hover:bg-slate-100 hover:text-foreground disabled:opacity-45 dark:hover:bg-[#25262a]"
            >
              {loading ? "加载中..." : "加载更多"}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

/** 单独记忆历史项，流式回复不会让整列历史重复渲染。 */
const ConversationListButton = React.memo(function ConversationListButton({
  conversation, active, loading, formattedTime, onSelect, onOpenMenu,
}: {
  conversation: AiConversationListItem;
  active: boolean;
  loading: boolean;
  formattedTime: string;
  onSelect: (conversationId: string) => void;
  onOpenMenu: (conversationId: string, event: React.MouseEvent) => void;
}) {
  return (
    <button
      type="button"
      onClick={() => onSelect(conversation.id)}
      onContextMenu={event => onOpenMenu(conversation.id, event)}
      disabled={loading && !active}
      className={`flex w-full min-w-0 items-start gap-2 rounded-lg border px-2 py-2 text-left transition-colors disabled:opacity-45 ${
        active
          ? "border-blue-200 bg-blue-50 text-foreground shadow-[inset_2px_0_0_rgba(0,122,255,0.72)] dark:border-blue-900 dark:bg-[#172033]"
          : "border-transparent bg-slate-50 text-muted-foreground hover:border-slate-200 hover:bg-slate-100 hover:text-foreground dark:bg-[#1f2023] dark:hover:border-[#2f3033] dark:hover:bg-[#25262a]"
      }`}
    >
      <MessageSquare size={13} className={active ? "mt-0.5 shrink-0 text-primary" : "mt-0.5 shrink-0"}/>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[12px] font-medium">{conversation.title}</span>
        <span className="mt-0.5 block truncate text-[10px] text-muted-foreground">{formattedTime}</span>
      </span>
    </button>
  );
});

/** 渲染普通聊天消息，流式正文使用稳定分段 Markdown。 */
export const AiMessageBubble = React.memo(function AiMessageBubble({
  message, streaming,
}: {
  message: AiMessage;
  streaming: boolean;
}) {
  if (message.role === "assistant" || message.role === "system") {
    if (message.error) {
      return (
        <div className="flex flex-col items-start">
          <div className="mr-8 max-w-full rounded-xl border border-red-200 bg-red-50 px-3 py-2 text-[12px] leading-relaxed text-red-600 shadow-sm dark:border-red-900 dark:bg-red-950 dark:text-red-400">
            <pre className="whitespace-pre-wrap break-words font-sans">{message.content}</pre>
          </div>
          {!streaming && <MessageCopyButton message={message}/>}
        </div>
      );
    }
    return (
      <div className="flex flex-col items-start">
        <div className="max-w-full rounded-xl border border-transparent px-1 text-[12px] leading-relaxed text-foreground">
          <MarkdownContent content={message.content} segments={message.contentSegments} streaming={streaming}/>
        </div>
        {!streaming && <MessageCopyButton message={message}/>}
      </div>
    );
  }
  return (
    <div className="flex flex-col items-end">
      <div className="max-w-[84%] rounded-md border border-border bg-muted px-3 py-2 text-[12px] leading-relaxed text-foreground">
        <pre className="whitespace-pre-wrap break-words font-sans">{message.content}</pre>
      </div>
      <MessageCopyButton message={message}/>
    </div>
  );
});

/** 一键复制消息原始正文；长回复只在用户点击时合并稳定分段。 */
const MessageCopyButton = React.memo(function MessageCopyButton({ message }: { message: AiMessage }) {
  const [copied, setCopied] = React.useState(false);
  const resetTimerRef = React.useRef<number | null>(null);

  /** 复制原始 Markdown，并短暂展示成功状态。 */
  const copyMessage = async () => {
    const content = materializeContent(message.content || "", message.contentSegments);
    if (!content) return;
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      if (resetTimerRef.current !== null) window.clearTimeout(resetTimerRef.current);
      resetTimerRef.current = window.setTimeout(() => setCopied(false), 1600);
    } catch {
      setCopied(false);
    }
  };

  /** 卸载消息时清理状态恢复计时器。 */
  React.useEffect(() => () => {
    if (resetTimerRef.current !== null) window.clearTimeout(resetTimerRef.current);
  }, []);

  return (
    <div className="mt-1 inline-flex h-6 items-center gap-1.5 text-[10px] text-muted-foreground/60">
      <button
        type="button"
        onClick={() => void copyMessage()}
        title={copied ? "已复制" : "复制原始内容"}
        aria-label={copied ? "已复制" : "复制原始内容"}
        className="flex h-6 w-6 items-center justify-center transition-colors hover:text-foreground"
      >
        {copied ? <Check size={13}/> : <Copy size={13}/>}
      </button>
      {message.timestamp ? <span>{formatMessageTime(message.timestamp)}</span> : null}
    </div>
  );
});

/** 当天消息只显示时分，历史消息显示完整年月日和时分。 */
function formatMessageTime(timestamp: number) {
  const value = new Date(timestamp);
  const today = new Date();
  const time = `${padTime(value.getHours())}:${padTime(value.getMinutes())}`;
  const sameDay = value.getFullYear() === today.getFullYear()
    && value.getMonth() === today.getMonth()
    && value.getDate() === today.getDate();
  if (sameDay) return time;
  return `${value.getFullYear()}/${padTime(value.getMonth() + 1)}/${padTime(value.getDate())} ${time}`;
}

/** 把日期时间数字补齐为两位。 */
function padTime(value: number) {
  return String(value).padStart(2, "0");
}

/** 渲染可折叠的 Agent 过程时间线。 */
export function AiProcessCard({
  messageId, process, confirming, onToggle, onApprove, onCancel,
}: {
  messageId: number;
  process: AiProcessState;
  confirming: boolean;
  onToggle: (messageId: number) => void;
  onApprove: (token: string) => void;
  onCancel: (token: string) => void;
}) {
  const toolCount = process.entries.filter(entry => entry.type === "tool").length;
  const waitingConfirmation = process.entries.some(entry => entry.toolResult?.confirmationRequired);
  const title = waitingConfirmation ? "等待用户确认" : process.active ? "正在思考" : "已处理";
  const summary = toolCount > 0 ? `${process.entries.length} 个步骤 / ${toolCount} 次工具调用` : `${process.entries.length} 个步骤`;
  const latest = latestProcessSummary(process);
  const duration = processDurationLabel(process);
  return (
    <div className="mr-8 min-w-0 max-w-full overflow-hidden text-[12px]">
      <button
        type="button"
        onClick={() => onToggle(messageId)}
        className="mb-2 flex w-full min-w-0 items-center gap-2 rounded-md px-1 py-1 text-left text-[12px] text-muted-foreground hover:bg-muted hover:text-foreground"
      >
        {process.expanded ? <ChevronDown size={13}/> : <ChevronRight size={13}/>} 
        {process.active ? <AiThinkingIcon/> : <Sparkles size={13} className={waitingConfirmation ? "text-amber-500" : "text-primary"}/>} 
        <span className="shrink-0 text-[12px] font-medium">{title}</span>
        {process.active ? (
          <span className="min-w-0 flex-1 truncate text-[11px] font-normal text-muted-foreground">{latest}</span>
        ) : (
          <>
            {duration && <span className="text-[11px] font-normal text-muted-foreground">{duration}</span>}
            <span className="min-w-0 truncate text-[11px] font-normal text-muted-foreground">{summary}</span>
          </>
        )}
      </button>
      {process.expanded && (
        <div className="min-w-0 max-w-full space-y-0 overflow-hidden">
          {process.entries.map((entry, index) => (
            <ProcessEntry
              key={entry.id}
              entry={entry}
              last={index === process.entries.length - 1}
              confirming={confirming}
              onApprove={onApprove}
              onCancel={onCancel}
            />
          ))}
        </div>
      )}
    </div>
  );
}

/** 折叠状态展示最新可观察步骤，不暴露模型内部推理链。 */
function latestProcessSummary(process: AiProcessState) {
  const latest = process.entries[process.entries.length - 1];
  if (!latest) return "正在理解问题并选择处理方式";
  if (latest.toolResult) {
    if (latest.status === "failed") return `${latest.label}执行失败，正在分析原因`;
    if (latest.status === "running") return `正在执行${latest.label}`;
    return `${latest.label}执行完成，正在分析结果`;
  }
  return latest.detail || latest.label || "正在分析当前任务";
}

/** 渲染单个过程步骤。 */
function ProcessEntry({ entry, last, confirming, onApprove, onCancel }: {
  entry: AiProcessEntry;
  last: boolean;
  confirming: boolean;
  onApprove: (token: string) => void;
  onCancel: (token: string) => void;
}) {
  const marker = entry.status === "running" ? <AiThinkingIcon/> : entry.type === "tool" ? <Terminal size={12}/> : <Activity size={12}/>;
  let content: React.ReactNode;
  if (entry.toolResult?.confirmationRequired) {
    content = <AiConfirmationCard result={entry.toolResult} busy={confirming} onApprove={onApprove} onCancel={onCancel}/>;
  } else if (entry.toolResult) {
    content = <AiToolCallCard toolName={entry.label} result={entry.toolResult} running={entry.status === "running"}/>;
  } else {
    content = (
      <div className="min-w-0 max-w-full overflow-hidden rounded-md border border-border bg-card px-2.5 py-2 text-[12px] text-muted-foreground">
        <div className="mb-1 font-medium text-foreground/80">{entry.label}</div>
        <p className="whitespace-pre-wrap break-all leading-relaxed [overflow-wrap:anywhere]">{entry.detail || "等待模型输出可观察步骤。"}</p>
      </div>
    );
  }
  return (
    <div className="grid min-w-0 max-w-full grid-cols-[20px_minmax(0,1fr)] gap-2 overflow-hidden">
      <div className="relative flex justify-center">
        <span className="relative z-10 mt-2 flex h-5 w-5 items-center justify-center rounded-full border border-primary/20 bg-background text-primary shadow-sm dark:bg-[#202124]">{marker}</span>
        {!last && <span className="absolute bottom-0 top-7 w-px bg-border"/>}
      </div>
      <div className="min-w-0 max-w-full overflow-hidden pb-2">{content}</div>
    </div>
  );
}

/** 渲染轻量动态思考图标。 */
export function AiThinkingIcon() {
  return (
    <span aria-hidden="true" className="relative inline-flex h-3 w-3 shrink-0 items-center justify-center">
      <span className="absolute inset-0 rounded-full bg-cyan-400/20 motion-safe:animate-ping"/>
      <Sparkles size={11} className="relative text-cyan-500 motion-safe:animate-pulse"/>
    </span>
  );
}

/** 格式化过程总耗时。 */
function processDurationLabel(process: AiProcessState) {
  if (!process.startedAt || !process.finishedAt || process.finishedAt < process.startedAt) return "";
  const totalSeconds = Math.max(0, Math.round((process.finishedAt - process.startedAt) / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return [hours > 0 ? `${hours}h` : "", hours > 0 || minutes > 0 ? `${minutes}m` : "", `${seconds}s`]
    .filter(Boolean).join(" ");
}

/** 渲染当前任务的轻量观测抽屉，不展示 Prompt、密钥和完整工具正文。 */
export function AiTracePanel({ trace, loading, error, onClose }: {
  trace: AgentTrace | null;
  loading: boolean;
  error: string;
  onClose: () => void;
}) {
  const events = trace?.events.slice(-100).reverse() || [];
  return (
    <div className="absolute inset-y-0 right-0 z-40 flex w-[360px] max-w-full flex-col border-l border-border bg-background shadow-xl">
      <div className="flex h-12 items-center justify-between border-b border-border px-3">
        <div>
          <p className="text-[12px] font-semibold text-foreground">Agent Trace</p>
          <p className="text-[10px] text-muted-foreground">模型、RAG、工具、重试与错误</p>
        </div>
        <button type="button" onClick={onClose} className="rounded-md px-2 py-1 text-[12px] text-muted-foreground hover:bg-muted">关闭</button>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto p-3 text-[11px]">
        {loading && <p className="text-muted-foreground">正在加载 Trace...</p>}
        {error && <p className="whitespace-pre-wrap text-red-500">{error}</p>}
        {trace && (
          <div className="space-y-4">
            <div className="grid grid-cols-3 gap-2">
              <TraceMetric label="模型" value={trace.metrics.modelCalls}/>
              <TraceMetric label="工具" value={trace.metrics.toolCalls}/>
              <TraceMetric label="错误" value={trace.metrics.errors}/>
            </div>
            <TraceObject title="模型路由" value={trace.model}/>
            <TraceObject title="上下文与 RAG" value={trace.rag}/>
            <div>
              <p className="mb-2 font-medium text-foreground">事件时间线</p>
              <div className="space-y-1.5">
                {events.map(event => (
                  <div key={event.eventId} className="rounded-md border border-border bg-muted/40 px-2 py-1.5">
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate font-medium text-foreground">{event.eventType}</span>
                      <span className="shrink-0 text-muted-foreground">#{event.eventSequence}</span>
                    </div>
                    <p className="mt-0.5 truncate text-muted-foreground">{compactTracePayload(event.payload)}</p>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/** 渲染 Trace 计数。 */
function TraceMetric({ label, value }: { label: string; value: number }) {
  return <div className="rounded-md bg-muted px-2 py-2 text-center"><p className="text-[14px] font-semibold text-foreground">{value}</p><p className="text-muted-foreground">{label}</p></div>;
}

/** 渲染有界结构化 Trace 摘要。 */
function TraceObject({ title, value }: { title: string; value: Record<string, unknown> }) {
  return (
    <div>
      <p className="mb-1 font-medium text-foreground">{title}</p>
      <pre className="max-h-40 overflow-auto whitespace-pre-wrap break-all rounded-md bg-muted p-2 font-mono text-[10px] text-muted-foreground">{JSON.stringify(value, null, 2)}</pre>
    </div>
  );
}

/** 事件列表只展示短摘要，防止大 payload 拉宽或撑高观测抽屉。 */
function compactTracePayload(payload: Record<string, unknown>) {
  const text = JSON.stringify(payload);
  return text.length <= 240 ? text : `${text.slice(0, 240)}...`;
}
