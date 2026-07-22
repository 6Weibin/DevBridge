package com.devbridge.server.service;

import java.util.List;

/**
 * PoC 支持的工具清单，集中声明可执行命令白名单。
 *
 * <p>by AI.Coding</p>
 */
public final class ToolCatalog {

    public static final ToolDefinition ADB = new ToolDefinition("adb", List.of("adb"), List.of("version"));
    public static final ToolDefinition HDC = new ToolDefinition("hdc", List.of("hdc", "hdc_std"), List.of("-v"));
    public static final ToolDefinition IDEVICE_ID = new ToolDefinition("idevice_id", List.of("idevice_id"), List.of("-h"));
    public static final ToolDefinition IDEVICEINFO = new ToolDefinition("ideviceinfo", List.of("ideviceinfo"), List.of("-h"));
    public static final ToolDefinition IDEVICESYSLOG = new ToolDefinition("idevicesyslog", List.of("idevicesyslog"), List.of("-h"));
    public static final ToolDefinition IDEVICEBACKUP2 = new ToolDefinition("idevicebackup2", List.of("idevicebackup2"), List.of("-h"));

    private static final List<ToolDefinition> ALL = List.of(ADB, HDC, IDEVICE_ID, IDEVICEINFO, IDEVICESYSLOG, IDEVICEBACKUP2);

    private ToolCatalog() {
    }

    /**
     * 返回全部工具定义，服务层据此做状态探测。
     *
     * @return 工具定义列表
     */
    public static List<ToolDefinition> all() {
        return ALL;
    }
}
