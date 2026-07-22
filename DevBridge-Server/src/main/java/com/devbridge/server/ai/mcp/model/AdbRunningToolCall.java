package com.devbridge.server.ai.mcp.model;

/**
 * 运行中的 ADB 工具调用，用于侧边栏关闭或用户取消时停止后端进程。
 *
 * <p>by AI.Coding</p>
 *
 * @param requestId 请求 ID
 * @param processId 长进程 ID
 * @param cancelHandle 取消回调
 */
public record AdbRunningToolCall(String requestId, String processId, Runnable cancelHandle) {
}
