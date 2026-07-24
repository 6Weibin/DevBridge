/**
 * AI 助手浮动入口总控，保持与主业务页面解耦。
 *
 * by AI.Coding
 */
import React, { useCallback, useEffect, useRef, useState } from "react";
import { Bot, Loader2 } from "lucide-react";
import { getConfigStatus, getWebSearchConfig } from "./aiApi";
import { AiConfigDialog, ConfigSection } from "./AiConfigDialog";
import { AiChatPanel } from "./AiChatPanel";
import { AiConfigStatus, AiDeviceContext, AiLogLine } from "./aiTypes";

const PANEL_TRANSITION_MILLIS = 300;

interface AiAssistantShellProps {
  backendOnline: boolean;
  device: AiDeviceContext | null;
  streaming: boolean;
  getRecentLogs: () => AiLogLine[];
  onStartLogCapture: () => void;
}

/**
 * 渲染 AI 浮动按钮、配置弹窗和右侧侧边栏。
 */
export function AiAssistantShell({ backendOnline, device, streaming, getRecentLogs, onStartLogCapture }: AiAssistantShellProps) {
  const [checking, setChecking] = useState(false);
  const [configOpen, setConfigOpen] = useState(false);
  const [configSection, setConfigSection] = useState<ConfigSection>("prompt");
  // 首帧直接覆盖主界面，避免等待异步配置检查时先显示主屏再跳转到 AI 助手。
  const [panelOpen, setPanelOpen] = useState(true);
  const [panelVisible, setPanelVisible] = useState(true);
  const [configStatus, setConfigStatus] = useState<AiConfigStatus | null>(null);
  const [webSearchEnabled, setWebSearchEnabled] = useState(false);
  const [hint, setHint] = useState("");
  const initialOpenAttemptedRef = useRef(false);
  const closeTimerRef = useRef<number | null>(null);
  const openFrameRef = useRef<number | null>(null);

  /**
   * 挂载面板后再进入可见态，保证浏览器能够绘制打开进场动画。
   */
  const showAssistant = useCallback(() => {
    // 首次启动已经直接显示全屏面板，不重复播放进场以免主页面出现闪烁。
    if (panelOpen && panelVisible) return;
    if (closeTimerRef.current !== null) window.clearTimeout(closeTimerRef.current);
    if (openFrameRef.current !== null) window.cancelAnimationFrame(openFrameRef.current);
    setPanelVisible(false);
    setPanelOpen(true);
    openFrameRef.current = window.requestAnimationFrame(() => {
      openFrameRef.current = window.requestAnimationFrame(() => {
        setPanelVisible(true);
        openFrameRef.current = null;
      });
    });
  }, [panelOpen, panelVisible]);

  /**
   * 先播放退场动画，结束后再卸载面板，避免关闭动作瞬间消失。
   */
  const hideAssistant = useCallback(() => {
    setPanelVisible(false);
    closeTimerRef.current = window.setTimeout(() => {
      setPanelOpen(false);
      closeTimerRef.current = null;
    }, PANEL_TRANSITION_MILLIS);
  }, []);

  /**
   * 点击入口时先检查后端和 AI 配置状态，避免展示空白侧边栏。
   */
  const openAssistant = useCallback(async () => {
    if (!backendOnline) {
      setHint("本机后端未连接，无法打开 Bridge Copilot");
      window.setTimeout(() => setHint(""), 2400);
      return;
    }
    setChecking(true);
    setHint("");
    try {
      const status = await getConfigStatus();
      setConfigStatus(status);
      if (status.configured) {
        showAssistant();
      } else {
        setConfigOpen(true);
      }
    } catch (error) {
      setHint(error instanceof Error ? error.message : "AI 配置状态读取失败");
      window.setTimeout(() => setHint(""), 2400);
    } finally {
      setChecking(false);
    }
  }, [backendOnline, showAssistant]);

  // 后端首次就绪时只检查配置；AI 面板已经在首帧全屏展示。
  useEffect(() => {
    if (!backendOnline || initialOpenAttemptedRef.current) return;
    initialOpenAttemptedRef.current = true;
    void openAssistant();
  }, [backendOnline, openAssistant]);

  /** 后端就绪后读取持久化联网状态，作为输入区和设置页的共享状态源。 */
  useEffect(() => {
    if (!backendOnline) {
      setWebSearchEnabled(false);
      return;
    }
    getWebSearchConfig()
      .then(detail => setWebSearchEnabled(detail.enabled))
      .catch(() => setWebSearchEnabled(false));
  }, [backendOnline]);

  /**
   * 配置完成后直接打开侧边栏，减少用户再次点击。
   */
  const handleConfigured = (status: AiConfigStatus) => {
    setConfigStatus(status);
    showAssistant();
  };

  /** 清理尚未执行的动画帧和关闭计时器。 */
  useEffect(() => () => {
    if (closeTimerRef.current !== null) window.clearTimeout(closeTimerRef.current);
    if (openFrameRef.current !== null) window.cancelAnimationFrame(openFrameRef.current);
  }, []);

  return (
    <>
      {hint && (
        <div className="fixed bottom-24 right-5 z-[45] max-w-[260px] rounded-lg border border-border bg-background px-3 py-2 text-[12px] text-foreground shadow-xl">
          {hint}
        </div>
      )}
      <button
        type="button"
        onClick={openAssistant}
        disabled={checking}
        className="fixed bottom-5 right-5 z-[45] flex h-12 w-12 items-center justify-center rounded-full bg-primary text-white shadow-[0_12px_32px_rgba(0,0,0,0.28)] transition-transform hover:scale-105 disabled:opacity-70"
        title="Bridge Copilot"
      >
        {checking ? <Loader2 size={21} className="animate-spin"/> : <Bot size={22}/>}
      </button>
      <AiChatPanel
        open={panelOpen}
        visible={panelVisible}
        configStatus={configStatus}
        device={device}
        streaming={streaming}
        getRecentLogs={getRecentLogs}
        onStartLogCapture={onStartLogCapture}
        onOpenConfig={() => {
          setConfigSection("prompt");
          setConfigOpen(true);
        }}
        onOpenNetworkConfig={() => {
          setConfigSection("network");
          setConfigOpen(true);
        }}
        webSearchEnabled={webSearchEnabled}
        onWebSearchEnabledChange={setWebSearchEnabled}
        onClose={hideAssistant}
      />
      <AiConfigDialog
        open={configOpen}
        initialStatus={configStatus}
        initialSection={configSection}
        onOpenChange={setConfigOpen}
        onConfigured={handleConfigured}
        onWebSearchConfigured={setWebSearchEnabled}
      />
    </>
  );
}
