package com.devbridge.server.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 远端文件或目录节点，供前端文件树展示。
 *
 * <p>by AI.Coding</p>
 *
 * @param name 文件名
 * @param path 远端完整路径
 * @param type 节点类型
 * @param sizeBytes 文件大小，目录可为空
 * @param modified 修改时间摘要
 * @param permissions 权限摘要
 * @param owner 所有者
 * @param group 所属组
 */
public record RemoteFileNode(
        String name,
        String path,
        @JsonProperty("type") RemoteFileType type,
        Long sizeBytes,
        String modified,
        String permissions,
        String owner,
        String group) {
}
