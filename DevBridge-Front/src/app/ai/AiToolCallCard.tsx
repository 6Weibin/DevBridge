/**
 * AI 工具调用状态卡片，展示 MCP 工具结果摘要。
 *
 * by AI.Coding
 */
import React from "react";
import { CheckCircle2, Terminal, XCircle } from "lucide-react";
import { AdbMcpToolResult } from "./aiTypes";

interface AiToolCallCardProps {
  toolName: string;
  result: AdbMcpToolResult | null;
  running: boolean;
}

/**
 * 渲染工具调用摘要，避免大段 stdout/stderr 直接撑开侧边栏。
 */
export function AiToolCallCard({ toolName, result, running }: AiToolCallCardProps) {
  const failed = result?.status === "FAILED";
  const success = result?.status === "SUCCESS";
  const displayName = cleanToolDisplayName(result?.toolTitle || toolName);
  return (
    <div className="min-w-0 max-w-full overflow-hidden rounded-lg border border-border bg-background px-3 py-2 text-[12px]">
      <div className="flex min-w-0 items-center gap-2">
        {running ? <RunningToolIcon/> : statusIcon(success, failed)}
        <span className="min-w-0 flex-1 truncate font-medium text-foreground">{displayName}</span>
        {result?.riskLevel && <span className="text-[10px] text-muted-foreground">{result.riskLevel}</span>}
      </div>
      {result && (
        <div className="mt-2 min-w-0 max-w-full space-y-1 overflow-hidden text-muted-foreground">
          <p className="line-clamp-2 break-words">{result.message || result.errorCode || "工具调用完成"}</p>
          {result.commandSummary && (
            <div className="min-w-0 max-w-full overflow-hidden rounded-md border border-border bg-muted px-2 py-1">
              <div className="mb-0.5 text-[10px] text-muted-foreground">{commandSummaryLabel(displayName)}</div>
              <code className="block max-h-20 max-w-full overflow-auto whitespace-pre-wrap break-all font-mono text-[11px] text-foreground/85 [overflow-wrap:anywhere]">{result.commandSummary}</code>
            </div>
          )}
          {result.exitCode !== null && <p>exitCode: {result.exitCode} / {result.durationMillis}ms</p>}
          {result.truncated && <p>输出已截断</p>}
          {summary(result) && <pre className="max-h-28 max-w-full overflow-auto whitespace-pre-wrap break-all rounded-md bg-muted p-2 font-mono text-[11px] [overflow-wrap:anywhere]">{summary(result)}</pre>}
        </div>
      )}
    </div>
  );
}

/** 网络工具展示业务语义，其余执行工具继续显示实际命令。 */
function commandSummaryLabel(displayName: string) {
  if (displayName === "网络搜索") return "检索内容";
  if (displayName === "网页读取") return "目标地址";
  return "执行命令";
}

/**
 * 工具卡片面向用户展示实际工具类别，不暴露 MCP 协议实现词。
 */
function cleanToolDisplayName(name: string) {
  return name.replace(/\s*MCP\b/gi, "").replace(/^Local Shell$/i, "本机终端").replace(/^ADB$/i, "ADB 工具").trim();
}

/**
 * 渲染工具运行态；使用静态状态点避免旋转动画在半透明 AI 面板中触发合成闪烁。
 */
function RunningToolIcon() {
  return <span className="inline-flex h-3 w-3 rounded-full bg-primary shadow-[0_0_0_3px_rgba(37,99,235,0.10)]"/>;
}

/**
 * 根据工具状态选择图标。
 */
function statusIcon(success: boolean, failed: boolean) {
  if (success) {
    return <CheckCircle2 size={13} className="text-emerald-500"/>;
  }
  if (failed) {
    return <XCircle size={13} className="text-red-500"/>;
  }
  return <Terminal size={13} className="text-muted-foreground"/>;
}

/**
 * 提取工具输出摘要，优先展示 stdout。
 */
function summary(result: AdbMcpToolResult) {
  return (result.stdout || result.stderr || "").slice(0, 1200);
}
