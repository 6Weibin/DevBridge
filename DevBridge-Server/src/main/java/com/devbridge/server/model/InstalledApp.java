package com.devbridge.server.model;

/**
 * Android 已安装应用信息，供前端应用管理页签展示。
 *
 * <p>by AI.Coding</p>
 *
 * @param name 应用名称，无法读取 label 时回退为包名
 * @param packageName 应用包名
 * @param versionName 版本名称
 * @param versionCode 版本号
 * @param systemApp 是否系统应用
 */
public record InstalledApp(
        String name,
        String packageName,
        String versionName,
        String versionCode,
        boolean systemApp) {
}
