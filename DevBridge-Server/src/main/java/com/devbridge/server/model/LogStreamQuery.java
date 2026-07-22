package com.devbridge.server.model;

/**
 * 实时日志查询条件，当前用于前端传递级别和文本过滤意图。
 *
 * <p>by AI.Coding</p>
 *
 * @param level 日志级别过滤
 * @param filter 文本过滤
 */
public record LogStreamQuery(String level, String filter) {
}
