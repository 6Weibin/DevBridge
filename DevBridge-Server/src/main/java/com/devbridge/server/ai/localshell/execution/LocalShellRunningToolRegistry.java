package com.devbridge.server.ai.localshell.execution;

import com.devbridge.server.ai.localshell.model.LocalShellRunningToolCall;
import com.devbridge.server.ai.mcp.model.AdbMcpToolResult;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Local Shell 运行中工具注册表，用于取消长时间运行的本机命令。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class LocalShellRunningToolRegistry {

    private final Map<String, LocalShellRunningToolCall> running = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingCancellations = new ConcurrentHashMap<>();

    /**
     * 注册运行中工具调用。
     *
     * @param call 工具调用
     */
    public void register(LocalShellRunningToolCall call) {
        if (pendingCancellations.remove(call.requestId()) != null) {
            call.cancelHandle().run();
            return;
        }
        running.put(call.requestId(), call);
    }

    /**
     * 移除运行中工具调用。
     *
     * @param requestId 请求 ID
     */
    public void remove(String requestId) {
        running.remove(requestId);
        pendingCancellations.remove(requestId);
    }

    /**
     * 取消运行中工具调用。
     *
     * @param requestId 请求 ID
     * @return 取消结果
     */
    public AdbMcpToolResult cancel(String requestId) {
        LocalShellRunningToolCall call = running.remove(requestId);
        if (call == null) {
            // 取消可能早于命令进程注册，保留短期待取消标识以关闭该竞态窗口。
            pendingCancellations.put(requestId, System.currentTimeMillis());
            discardExpiredCancellations();
            return AdbMcpToolResult.canceled("本机命令尚未启动或已结束，已记录取消请求。");
        }
        call.cancelHandle().run();
        return AdbMcpToolResult.canceled("已取消运行中的本机命令工具调用。");
    }

    /**
     * 清理超过五分钟的待取消标识，防止异常请求长期占用内存。
     */
    private void discardExpiredCancellations() {
        if (pendingCancellations.size() < 1000) {
            return;
        }
        long threshold = System.currentTimeMillis() - 300_000L;
        pendingCancellations.entrySet().removeIf(entry -> entry.getValue() < threshold);
    }

    /**
     * 返回运行中工具调用快照，避免 AI 直接扫描本机全部进程。
     *
     * @return 运行中工具调用列表
     */
    public List<LocalShellRunningToolCall> snapshot() {
        return running.values().stream()
                .sorted(Comparator.comparing(LocalShellRunningToolCall::requestId))
                .toList();
    }
}
