package com.devbridge.server.command;

/**
 * 进程树终止结果，提供最小可观测信息用于日志和测试。
 *
 * <p>by AI.Coding</p>
 *
 * @param rootPid 根进程 PID
 * @param descendantCount 已发现的后代进程数
 * @param forceKillCount 执行强制终止的进程数
 * @param terminated 根进程和已发现后代是否全部终止
 */
public record ProcessTerminationResult(
        long rootPid,
        int descendantCount,
        int forceKillCount,
        boolean terminated) {
}
