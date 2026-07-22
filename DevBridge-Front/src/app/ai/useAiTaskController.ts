/**
 * AI 任务控制 Hook，集中管理请求生命周期、取消和 Trace 查询。
 *
 * by AI.Coding
 */
import { useRef, useState } from "react";
import { cancelAgentTask, getAgentTrace } from "./aiApi";
import { AgentTrace, AiMessage } from "./aiTypes";

interface AiTaskControllerOptions {
  appendMessage: (role: AiMessage["role"], content: string) => number;
  appendError: (content: string) => number;
  flushStreaming: () => void;
  clearStreaming: () => void;
  setHint: (hint: string) => void;
}

/** 管理单个面板内的活动任务，不接管消息、历史或展示状态。 */
export function useAiTaskController(options: AiTaskControllerOptions) {
  const [loading, setLoading] = useState(false);
  const [currentTaskId, setCurrentTaskId] = useState("");
  const abortRef = useRef<AbortController | null>(null);
  const activeTaskIdRef = useRef("");
  const traceController = useTaskTrace(currentTaskId, () => setCurrentTaskId(""));

  /** 记录后端返回的任务标识，取消使用 ref 避免读取旧闭包。 */
  const registerTask = (taskId: string) => {
    activeTaskIdRef.current = taskId;
    setCurrentTaskId(taskId);
  };

  /** 上下文落盘期间提前锁定输入，避免用户重复提交同一轮。 */
  const beginPreparation = () => setLoading(true);

  /** 上下文落盘失败时解除输入锁定。 */
  const endPreparation = () => setLoading(false);

  /** 执行统一可取消请求，并把异常详情写入聊天内容。 */
  const runRequest = async (executor: (signal: AbortSignal) => Promise<void>) => {
    setLoading(true);
    options.setHint("");
    traceController.resetTaskView();
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      await executor(controller.signal);
    } catch (error) {
      if (controller.signal.aborted) {
        options.appendMessage("assistant", "已停止当前任务。");
      } else {
        options.appendError(formatRequestError(error));
        options.setHint("AI 执行失败，详情已显示在对话中。");
      }
    } finally {
      options.flushStreaming();
      options.clearStreaming();
      abortRef.current = null;
      activeTaskIdRef.current = "";
      setLoading(false);
    }
  };

  /** 立即停止当前 SSE，再向后端传播任务取消，避免网络或清理延迟阻塞按钮反馈。 */
  const cancelCurrent = async () => {
    const taskId = activeTaskIdRef.current;
    abortRef.current?.abort();
    if (taskId) await cancelBackendTask(taskId, options);
  };

  return {
    loading,
    currentTaskId,
    ...traceController,
    registerTask,
    beginPreparation,
    endPreparation,
    runRequest,
    cancelCurrent,
  };
}

/** 管理当前任务 Trace 抽屉，避免请求 Hook 同时承担展示状态。 */
function useTaskTrace(currentTaskId: string, clearCurrentTask: () => void) {
  const [traceOpen, setTraceOpen] = useState(false);
  const [traceLoading, setTraceLoading] = useState(false);
  const [traceError, setTraceError] = useState("");
  const [trace, setTrace] = useState<AgentTrace | null>(null);

  /** 查询后端脱敏事件和工具审计。 */
  const openTrace = async () => {
    if (!currentTaskId || traceLoading) return;
    setTraceOpen(true);
    setTraceLoading(true);
    setTraceError("");
    try {
      setTrace(await getAgentTrace(currentTaskId));
    } catch (error) {
      setTraceError(formatRequestError(error));
    } finally {
      setTraceLoading(false);
    }
  };

  /** 切换会话或开始新请求时清理旧任务观测视图。 */
  const resetTaskView = () => {
    clearCurrentTask();
    setTraceOpen(false);
    setTrace(null);
    setTraceError("");
  };
  return { traceOpen, traceLoading, traceError, trace, setTraceOpen, openTrace, resetTaskView };
}

/** 调用后端取消接口并保留明确失败原因。 */
async function cancelBackendTask(taskId: string, options: AiTaskControllerOptions) {
  try {
    const canceled = await cancelAgentTask(taskId);
    if (["CANCELED", "COMPLETED", "FAILED"].includes(canceled.state)) return;
    options.appendError(`取消未生效：任务当前状态为 ${canceled.state}，后台任务没有被标记为已取消。`);
    options.setHint("AI 任务取消未生效，详情已显示在对话中。");
  } catch (error) {
    options.appendError(`当前对话已停止接收，但后台任务停止失败\n\n${formatRequestError(error)}`);
    options.setHint("AI 任务取消失败，详情已显示在对话中。");
  }
}

/** 把任意请求异常转换为用户可定位的红色错误正文。 */
export function formatRequestError(error: unknown) {
  const detail = error instanceof Error ? error.message : "AI 请求失败";
  return `AI 执行失败\n\n${detail}\n\n排查建议：\n- 检查 AI Provider、API URL、API Key 和模型配置\n- 检查网络连接及后端日志\n- 如果包含工具调用，检查对应设备或本地命令执行结果`;
}
