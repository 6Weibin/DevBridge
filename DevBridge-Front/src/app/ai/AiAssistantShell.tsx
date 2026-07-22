/**
 * AI 助手浮动入口总控，保持与主业务页面解耦。
 *
 * by AI.Coding
 */
import React, { useCallback, useEffect, useRef, useState } from "react";
import { Bot, Loader2 } from "lucide-react";
import { getConfigStatus } from "./aiApi";
import { AiConfigDialog } from "./AiConfigDialog";
import { AiChatPanel } from "./AiChatPanel";
import { AiConfigStatus, AiDeviceContext, AiLogLine } from "./aiTypes";

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
  // 首帧直接覆盖主界面，避免等待异步配置检查时先显示主屏再跳转到 AI 助手。
  const [panelOpen, setPanelOpen] = useState(true);
  const [configStatus, setConfigStatus] = useState<AiConfigStatus | null>(null);
  const [hint, setHint] = useState("");
  const initialOpenAttemptedRef = useRef(false);

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
        setPanelOpen(true);
      } else {
        setConfigOpen(true);
      }
    } catch (error) {
      setHint(error instanceof Error ? error.message : "AI 配置状态读取失败");
      window.setTimeout(() => setHint(""), 2400);
    } finally {
      setChecking(false);
    }
  }, [backendOnline]);

  // 后端首次就绪时只检查配置；AI 面板已经在首帧全屏展示。
  useEffect(() => {
    if (!backendOnline || initialOpenAttemptedRef.current) return;
    initialOpenAttemptedRef.current = true;
    void openAssistant();
  }, [backendOnline, openAssistant]);

  /**
   * 配置完成后直接打开侧边栏，减少用户再次点击。
   */
  const handleConfigured = (status: AiConfigStatus) => {
    setConfigStatus(status);
    setPanelOpen(true);
  };

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
        configStatus={configStatus}
        device={device}
        streaming={streaming}
        getRecentLogs={getRecentLogs}
        onStartLogCapture={onStartLogCapture}
        onOpenConfig={() => setConfigOpen(true)}
        onClose={() => setPanelOpen(false)}
      />
      <AiConfigDialog
        open={configOpen}
        initialStatus={configStatus}
        onOpenChange={setConfigOpen}
        onConfigured={handleConfigured}
      />
    </>
  );
}
