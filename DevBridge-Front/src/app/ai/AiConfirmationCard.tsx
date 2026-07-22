/**
 * AI 敏感操作确认卡片，用户确认后才会执行绑定的工具命令。
 *
 * by AI.Coding
 */
import React from "react";
import { ShieldAlert, X } from "lucide-react";
import { AdbMcpToolResult } from "./aiTypes";

interface AiConfirmationCardProps {
  result: AdbMcpToolResult;
  busy: boolean;
  onApprove: (token: string) => void;
  onCancel: (token: string) => void;
}

/**
 * 渲染敏感操作确认卡片。
 */
export function AiConfirmationCard({ result, busy, onApprove, onCancel }: AiConfirmationCardProps) {
  return (
    <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-3 text-[12px] dark:border-amber-900 dark:bg-amber-950">
      <div className="flex items-start gap-2">
        <ShieldAlert size={15} className="mt-0.5 text-amber-600"/>
        <div className="min-w-0 flex-1">
          <p className="font-medium text-foreground">敏感操作确认</p>
          <p className="mt-1 whitespace-pre-wrap break-words text-muted-foreground">{result.message}</p>
          <p className="mt-2 text-[11px] text-amber-700">风险级别：{result.riskLevel}</p>
        </div>
      </div>
      <div className="mt-3 flex justify-end gap-2">
        <button
          type="button"
          disabled={busy}
          onClick={() => onCancel(result.confirmationToken)}
          className="inline-flex h-8 items-center gap-1.5 rounded-md border border-border px-2.5 text-[12px] text-foreground hover:bg-muted disabled:opacity-45"
        >
          <X size={13}/>
          取消
        </button>
        <button
          type="button"
          disabled={busy}
          onClick={() => onApprove(result.confirmationToken)}
          className="h-8 rounded-md bg-amber-600 px-3 text-[12px] text-white disabled:opacity-45"
        >
          确认执行
        </button>
      </div>
    </div>
  );
}
