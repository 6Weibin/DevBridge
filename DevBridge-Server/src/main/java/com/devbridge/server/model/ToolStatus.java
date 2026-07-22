package com.devbridge.server.model;

/**
 * 本机外部工具探测结果。
 *
 * <p>by AI.Coding</p>
 *
 * @param name 工具逻辑名称
 * @param command 可执行命令名
 * @param available 是否可用
 * @param path 实际发现或配置的路径
 * @param version 版本输出摘要
 * @param message 错误或提示信息
 */
public record ToolStatus(
        String name,
        String command,
        boolean available,
        String path,
        String version,
        String message) {
}
