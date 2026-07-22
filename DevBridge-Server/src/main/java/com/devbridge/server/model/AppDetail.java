package com.devbridge.server.model;

import java.util.List;

/**
 * Android 应用详情模型，字段读取失败时允许为空字符串或空列表。
 *
 * <p>by AI.Coding</p>
 *
 * @param name 应用显示名称，无法读取时回退为包名
 * @param packageName 应用包名
 * @param versionName 版本名称
 * @param versionCode 版本号
 * @param uid 应用 UID
 * @param minSdk 最低 SDK
 * @param targetSdk 目标 SDK
 * @param firstInstallTime 首次安装时间
 * @param lastUpdateTime 最后更新时间
 * @param installerPackageName 安装来源包名
 * @param codePath 代码路径
 * @param resourcePath 资源路径
 * @param dataDir 数据目录
 * @param systemApp 是否系统应用
 * @param enabledState 启用状态
 * @param installed 当前用户是否安装
 * @param hidden 当前用户是否隐藏
 * @param stopped 当前用户是否停止
 * @param suspended 当前用户是否挂起
 * @param requestedPermissions 申请权限列表
 * @param grantedPermissions 已授权权限列表
 */
public record AppDetail(
        String name,
        String packageName,
        String versionName,
        String versionCode,
        String uid,
        String minSdk,
        String targetSdk,
        String firstInstallTime,
        String lastUpdateTime,
        String installerPackageName,
        String codePath,
        String resourcePath,
        String dataDir,
        boolean systemApp,
        String enabledState,
        boolean installed,
        boolean hidden,
        boolean stopped,
        boolean suspended,
        List<String> requestedPermissions,
        List<String> grantedPermissions) {
}
