package com.devbridge.server.model;

/**
 * 实时日志事件模型，既可承载日志行，也可承载错误或结束事件。
 *
 * <p>by AI.Coding</p>
 *
 * @param id 事件序号
 * @param timestamp 日志时间
 * @param level 日志级别
 * @param pid 进程号
 * @param tag 日志标签
 * @param message 日志内容
 * @param eventType 事件类型
 */
public record LogEvent(
        long id,
        String timestamp,
        String level,
        String pid,
        String tag,
        String message,
        String eventType) {
}
