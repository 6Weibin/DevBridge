package com.devbridge.server.model;

import java.time.Instant;

/**
 * API 统一错误响应，前端通过 code 映射稳定文案。
 *
 * <p>by AI.Coding</p>
 *
 * @param code 稳定错误码
 * @param message 用户可读错误信息
 * @param detail 诊断摘要，不包含完整敏感命令输出
 * @param timestamp 错误发生时间
 */
public record ApiError(String code, String message, String detail, Instant timestamp) {
}
