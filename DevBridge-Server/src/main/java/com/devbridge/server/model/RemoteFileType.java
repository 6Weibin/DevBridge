package com.devbridge.server.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 远端文件节点类型。
 *
 * <p>by AI.Coding</p>
 */
public enum RemoteFileType {
    DIRECTORY("dir"),
    FILE("file");

    private final String value;

    /**
     * 保存前端约定的类型值。
     *
     * @param value 前端类型值
     */
    RemoteFileType(String value) {
        this.value = value;
    }

    /**
     * 获取前端使用的类型值。
     *
     * @return 类型值
     */
    @JsonValue
    public String getValue() {
        return value;
    }
}
