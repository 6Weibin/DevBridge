package com.devbridge.server.model;

/**
 * 前端日志表格使用的日志行模型。
 *
 * <p>by AI.Coding</p>
 *
 * @param id 行 ID
 * @param timestamp 日志时间
 * @param level 日志级别
 * @param tag 日志标签
 * @param pid 进程号
 * @param message 日志内容
 */
public record LogLine(
        long id,
        String timestamp,
        String level,
        String tag,
        String pid,
        String message) {
}
