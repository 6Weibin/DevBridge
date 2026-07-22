package com.devbridge.server.ai.localshell.model;

/**
 * Local Shell 运行中工具调用，保存取消句柄。
 *
 * <p>by AI.Coding</p>
 *
 * @param requestId 请求 ID
 * @param processId 进程 ID 摘要
 * @param cancelHandle 取消回调
 */
public record LocalShellRunningToolCall(String requestId, String processId, Runnable cancelHandle) {
}
