package com.devbridge.server.model;

import java.time.Instant;

/**
 * 实时日志会话状态，供停止接口和前端诊断使用。
 *
 * <p>by AI.Coding</p>
 *
 * @param sessionId 会话 ID
 * @param platform 平台
 * @param serial 设备序列号
 * @param status 会话状态
 * @param startedAt 启动时间
 */
public record LogSessionInfo(String sessionId, Platform platform, String serial, String status, Instant startedAt) {
}
