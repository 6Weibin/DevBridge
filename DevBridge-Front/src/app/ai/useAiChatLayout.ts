/**
 * AI 聊天面板布局 Hook，隔离全屏、输入区测量和粘底滚动状态。
 *
 * by AI.Coding
 */
import { useEffect, useRef, useState } from "react";
import { AiMessage } from "./aiTypes";

const BOTTOM_THRESHOLD = 32;

/** 组合三个独立布局 Hook，控制组件只消费稳定引用和命令。 */
export function useAiChatLayout(options: {
  open: boolean;
  input: string;
  hint: string;
  loading: boolean;
  messages: AiMessage[];
  onClose: () => void;
}) {
  const fullscreen = useFullscreenLifecycle(options.open, options.onClose);
  const inputPanel = useInputPanelSize(options.open, options.input);
  const scroll = useChatAutoScroll(
    options.open,
    options.hint,
    options.loading,
    options.messages,
    inputPanel.inputPanelHeight,
  );
  return { ...fullscreen, ...inputPanel, ...scroll };
}

/** 管理 Esc 和关闭后的全屏复位。 */
function useFullscreenLifecycle(open: boolean, onClose: () => void) {
  const [fullscreen, setFullscreen] = useState(true);
  const hasOpenedRef = useRef(false);
  useEffect(() => {
    if (!open) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      if (fullscreen) setFullscreen(false);
      else onClose();
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [fullscreen, onClose, open]);
  useEffect(() => {
    // 首次展示直接全屏；关闭后复位，后续仍由用户手动切换全屏状态。
    if (open) {
      hasOpenedRef.current = true;
      return;
    }
    if (hasOpenedRef.current && fullscreen) setFullscreen(false);
  }, [fullscreen, open]);
  return { fullscreen, setFullscreen };
}

/** 测量自动增高文本框和底部浮层真实高度。 */
function useInputPanelSize(open: boolean, input: string) {
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const inputPanelRef = useRef<HTMLDivElement | null>(null);
  const [height, setHeight] = useState(144);
  useEffect(() => {
    const textarea = inputRef.current;
    if (!textarea) return;
    textarea.style.height = "auto";
    textarea.style.height = `${Math.min(textarea.scrollHeight, 160)}px`;
  }, [input]);
  useEffect(() => {
    const panel = inputPanelRef.current;
    if (!open || !panel) return;
    const update = () => {
      const next = Math.max(120, Math.ceil(panel.getBoundingClientRect().height));
      setHeight(current => current === next ? current : next);
    };
    update();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(update);
    observer.observe(panel);
    return () => observer.disconnect();
  }, [open]);
  return { inputRef, inputPanelRef, inputPanelHeight: height };
}

/** 只在用户仍位于底部时跟随流式内容，并提供显式回到底部命令。 */
function useChatAutoScroll(
  open: boolean,
  hint: string,
  loading: boolean,
  messages: AiMessage[],
  inputPanelHeight: number,
) {
  const [atScrollBottom, setAtScrollBottom] = useState(true);
  const scrollContainerRef = useRef<HTMLDivElement | null>(null);
  const endRef = useRef<HTMLDivElement | null>(null);
  const autoScrollRef = useRef(true);
  const suppressNextAutoScrollRef = useRef(false);
  const frameRef = useRef<number | null>(null);
  const programmaticRef = useRef(false);
  const scrollBottom = (behavior: ScrollBehavior) => {
    if (frameRef.current !== null) window.cancelAnimationFrame(frameRef.current);
    frameRef.current = window.requestAnimationFrame(() => {
      const container = scrollContainerRef.current;
      if (container) {
        programmaticRef.current = true;
        if (behavior === "smooth") container.scrollTo({ top: container.scrollHeight, behavior });
        else container.scrollTop = container.scrollHeight;
        window.setTimeout(() => { programmaticRef.current = false; }, 0);
      }
      autoScrollRef.current = true;
      setAtScrollBottom(true);
      frameRef.current = null;
    });
  };
  const updateScrollBottomState = () => {
    const container = scrollContainerRef.current;
    if (!container || programmaticRef.current) return;
    const atBottom = container.scrollHeight - container.scrollTop - container.clientHeight <= BOTTOM_THRESHOLD;
    autoScrollRef.current = atBottom;
    setAtScrollBottom(current => current === atBottom ? current : atBottom);
  };
  useEffect(() => {
    if (!open) return;
    if (suppressNextAutoScrollRef.current) suppressNextAutoScrollRef.current = false;
    else if (autoScrollRef.current) scrollBottom("auto");
    else window.requestAnimationFrame(updateScrollBottomState);
  }, [hint, inputPanelHeight, loading, messages, open]);
  useEffect(() => () => {
    if (frameRef.current !== null) window.cancelAnimationFrame(frameRef.current);
  }, []);
  return {
    atScrollBottom,
    scrollContainerRef,
    endRef,
    suppressNextAutoScrollRef,
    updateScrollBottomState,
    resetAutoScroll: () => scrollBottom("auto"),
    returnToChatBottom: () => scrollBottom("smooth"),
  };
}
