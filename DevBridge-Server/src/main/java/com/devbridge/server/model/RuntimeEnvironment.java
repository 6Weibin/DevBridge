package com.devbridge.server.model;

/**
 * 后端运行环境信息，用于前端确认当前服务会加载哪套内置工具。
 *
 * <p>by AI.Coding</p>
 *
 * @param osName Java 识别到的操作系统名称
 * @param osArch Java 识别到的 CPU 架构
 * @param toolDirectoryName 当前平台对应的内置工具目录名
 * @param bundledToolRoot 内置工具根目录配置
 * @param javaVersion 当前 Java 运行时版本
 * @param appName 应用名称
 * @param appVersion 应用构建版本
 */
public record RuntimeEnvironment(
        String osName,
        String osArch,
        String toolDirectoryName,
        String bundledToolRoot,
        String javaVersion,
        String appName,
        String appVersion) {
}
