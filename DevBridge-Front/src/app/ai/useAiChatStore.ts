/**
 * AI 聊天消息 Store Hook，集中维护同步消息引用和长回复分段刷新。
 *
 * by AI.Coding
 */
import { Dispatch, SetStateAction, useCallback, useEffect, useRef, useState } from "react";
import { AiMessage } from "./aiTypes";
import { appendContentSegment } from "./aiStreamSegments";

/**
 * 管理消息状态和流式尾段；控制组件只决定何时追加，不接触分段实现。
 */
export function useAiChatStore(initialMessages: AiMessage[], flushIntervalMs: number) {
  const [messages, rawSetMessages] = useState<AiMessage[]>(initialMessages);
  const messagesRef = useRef(initialMessages);
  const bufferRef = useRef<string[]>([]);
  const bufferIndexRef = useRef(0);
  const bufferedCharactersRef = useRef(0);
  const timerRef = useRef<number | null>(null);

  /** 同步更新 React 状态和异步流程使用的最新引用。 */
  const setMessages: Dispatch<SetStateAction<AiMessage[]>> = useCallback(action => {
    rawSetMessages(current => {
      const next = typeof action === "function"
        ? (action as (value: AiMessage[]) => AiMessage[])(current)
        : action;
      messagesRef.current = next;
      return next;
    });
  }, []);

  /** 把指定文本写入消息的可变尾段，已稳定分段保持引用不变。 */
  const appendStreamContent = useCallback((messageId: number, content: string) => {
    if (!content) return;
    setMessages(current => current.map(message => message.id === messageId
      ? { ...message, contentSegments: appendContentSegment(message.contentSegments, content) }
      : message));
  }, [setMessages]);

  /** 计算本轮释放字符数；小 chunk 一次显示，只有较大积压才拆分以避免整段跳变。 */
  const nextFlushSize = useCallback((length: number) => {
    if (length <= 192) return length;
    const adaptive = Math.ceil(length / 2);
    return Math.min(length, Math.max(96, Math.min(4_096, adaptive)));
  }, []);

  /** 从 chunk 队列按引用消费文本，避免长任务反复拼接和切片形成 V8 Rope 内存滞留。 */
  const takeBufferedContent = useCallback((flushAll: boolean) => {
    const chunks = bufferRef.current;
    const target = flushAll ? Number.MAX_SAFE_INTEGER : nextFlushSize(bufferedCharactersRef.current);
    const parts: string[] = [];
    let consumed = 0;
    while (bufferIndexRef.current < chunks.length && (flushAll || consumed < target)) {
      const chunk = chunks[bufferIndexRef.current];
      bufferIndexRef.current += 1;
      parts.push(chunk);
      consumed += chunk.length;
    }
    bufferedCharactersRef.current = Math.max(0, bufferedCharactersRef.current - consumed);
    if (bufferIndexRef.current >= chunks.length) {
      bufferRef.current = [];
      bufferIndexRef.current = 0;
    } else if (bufferIndexRef.current >= 128) {
      // 只复制剩余 chunk 引用，及时释放已消费数组槽位，不复制正文字符串。
      bufferRef.current = chunks.slice(bufferIndexRef.current);
      bufferIndexRef.current = 0;
    }
    return parts.join("");
  }, [nextFlushSize]);

  /** 调度下一帧流式刷新，避免网络大 chunk 直接造成整段文字跳变。 */
  const scheduleStreamFlush = useCallback((messageId: number) => {
    if (timerRef.current !== null) return;
    timerRef.current = window.setTimeout(() => {
      timerRef.current = null;
      if (bufferedCharactersRef.current === 0) return;
      const content = takeBufferedContent(false);
      appendStreamContent(messageId, content);
      if (bufferedCharactersRef.current > 0) scheduleStreamFlush(messageId);
    }, flushIntervalMs);
  }, [appendStreamContent, flushIntervalMs, takeBufferedContent]);

  /** 立即把全部缓存写入指定消息，用于完成、异常和工具切换边界。 */
  const flushStreamChunk = useCallback((messageId: number | null) => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    if (messageId === null || bufferedCharactersRef.current === 0) return;
    const content = takeBufferedContent(true);
    appendStreamContent(messageId, content);
  }, [appendStreamContent, takeBufferedContent]);

  /** 合并 Provider chunk，并按接近绘制帧的节奏平滑释放到 UI。 */
  const queueStreamChunk = useCallback((messageId: number, chunk: string) => {
    if (!chunk) return;
    bufferRef.current.push(chunk);
    bufferedCharactersRef.current += chunk.length;
    scheduleStreamFlush(messageId);
  }, [scheduleStreamFlush]);

  /** 卸载时清理待执行刷新，防止关闭 AI 面板后继续更新状态。 */
  useEffect(() => () => {
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    bufferRef.current = [];
    bufferIndexRef.current = 0;
    bufferedCharactersRef.current = 0;
  }, []);

  return { messages, setMessages, messagesRef, queueStreamChunk, flushStreamChunk };
}
