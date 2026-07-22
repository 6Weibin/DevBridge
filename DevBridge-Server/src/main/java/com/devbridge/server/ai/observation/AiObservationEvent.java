package com.devbridge.server.ai.observation;

/**
 * AI 调用观测事件，只记录摘要，不包含 Prompt 和日志正文。
 *
 * <p>by AI.Coding</p>
 *
 * @param provider Provider 类型
 * @param model 模型名称
 * @param success 是否成功
 * @param elapsedMillis 耗时毫秒
 * @param error 错误摘要
 */
public record AiObservationEvent(String provider, String model, boolean success, long elapsedMillis, String error) {
}
