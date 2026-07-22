package com.devbridge.server.service;

import java.util.List;

/**
 * 外部设备工具定义，描述候选命令名和版本探测参数。
 *
 * <p>by AI.Coding</p>
 *
 * @param name 工具逻辑名称
 * @param commands 候选命令名
 * @param versionArgs 版本探测参数
 */
public record ToolDefinition(String name, List<String> commands, List<String> versionArgs) {
}
