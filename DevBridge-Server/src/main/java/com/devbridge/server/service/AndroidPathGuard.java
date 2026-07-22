package com.devbridge.server.service;

import com.devbridge.server.model.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Android 远端路径安全校验器，允许浏览整机可见目录，同时阻断相对路径和控制字符。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AndroidPathGuard {

    /**
     * 校验并规范化 Android 远端路径；根目录浏览需要允许 `/`，但必须拒绝相对路径和 `..`。
     *
     * @param path 用户请求路径
     * @return 规范化后的远端路径
     */
    public String validateRemotePath(String path) {
        if (!StringUtils.hasText(path)) {
            throw forbidden("远端路径不能为空", "empty path");
        }
        String normalized = normalize(path);
        if (containsUnsafeSegment(normalized) || containsControlCharacter(normalized)) {
            throw forbidden("远端路径不允许访问上级目录或包含控制字符", normalized);
        }
        if (!normalized.startsWith("/")) {
            throw forbidden("远端路径必须是绝对路径", normalized);
        }
        return normalized;
    }

    /**
     * 提取远端路径中的文件名，用于浏览器下载响应。
     *
     * @param path 远端路径
     * @return 文件名
     */
    public String fileName(String path) {
        String normalized = validateRemotePath(path);
        if ("/".equals(normalized)) {
            return "android-root";
        }
        int index = normalized.lastIndexOf('/');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    /**
     * 校验重命名目标文件名；目标名只能是单级名称，避免通过重命名接口移动到其它目录。
     *
     * @param name 用户输入的新文件名
     * @return 规范化后的文件名
     */
    public String validateFileName(String name) {
        if (!StringUtils.hasText(name)) {
            throw forbidden("文件名不能为空", "empty file name");
        }
        String normalized = name.trim();
        if (".".equals(normalized) || "..".equals(normalized)) {
            throw forbidden("文件名不能是当前目录或上级目录", normalized);
        }
        if (normalized.contains("/") || normalized.contains("\\") || containsControlCharacter(normalized)) {
            throw forbidden("文件名不能包含路径分隔符或控制字符", normalized);
        }
        return normalized;
    }

    /**
     * 基于源路径和目标文件名构造同级目标路径；重命名只允许发生在原目录内。
     *
     * @param sourcePath 源远端路径
     * @param newName 新文件名
     * @return 同级目标路径
     */
    public String siblingPath(String sourcePath, String newName) {
        String safeSource = validateMutableFilePath(sourcePath);
        String safeName = validateFileName(newName);
        int index = safeSource.lastIndexOf('/');
        String parentPath = index <= 0 ? "/" : safeSource.substring(0, index);
        return "/".equals(parentPath) ? "/" + safeName : parentPath + "/" + safeName;
    }

    /**
     * 校验会修改远端文件系统的文件路径，根目录不能被删除或重命名。
     *
     * @param path 用户请求路径
     * @return 规范化后的远端路径
     */
    public String validateMutableFilePath(String path) {
        String normalized = validateRemotePath(path);
        if ("/".equals(normalized)) {
            throw forbidden("根目录不允许执行文件变更操作", normalized);
        }
        return normalized;
    }

    /**
     * 规范化重复斜杠和末尾斜杠，避免同一路径出现多种表示。
     *
     * @param path 原始路径
     * @return 规范化路径
     */
    private String normalize(String path) {
        String normalized = path.trim().replace('\\', '/').replaceAll("/{2,}", "/");
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 判断路径是否包含上级目录片段。
     *
     * @param path 规范化路径
     * @return 包含不安全片段时返回 true
     */
    private boolean containsUnsafeSegment(String path) {
        return path.equals("..") || path.contains("/../") || path.startsWith("../") || path.endsWith("/..");
    }

    /**
     * 判断路径是否包含控制字符，避免传入不可见命令参数。
     *
     * @param path 规范化路径
     * @return 包含控制字符时返回 true
     */
    private boolean containsControlCharacter(String path) {
        return path.chars().anyMatch(ch -> ch < 32 || ch == 127);
    }

    /**
     * 创建路径拒绝异常。
     *
     * @param message 错误提示
     * @param detail 诊断摘要
     * @return 业务异常
     */
    private BusinessException forbidden(String message, String detail) {
        return new BusinessException("REMOTE_PATH_FORBIDDEN", message, HttpStatus.BAD_REQUEST, detail);
    }
}
