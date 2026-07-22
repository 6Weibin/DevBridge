package com.devbridge.server.ai.observation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AI 调用观测记录器，为后续多模型观测看板保留统一入口。
 *
 * <p>by AI.Coding</p>
 */
@Component
public class AiObservationRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiObservationRecorder.class);

    /**
     * 记录 AI 调用摘要；不记录 API Key、Prompt 和完整日志正文。
     *
     * @param event AI 调用观测事件
     */
    public void record(AiObservationEvent event) {
        if (event.success()) {
            LOGGER.info("AI 调用成功 provider={} model={} elapsed={}ms", event.provider(), event.model(), event.elapsedMillis());
            return;
        }
        LOGGER.warn("AI 调用失败 provider={} model={} elapsed={}ms error={}",
                event.provider(),
                event.model(),
                event.elapsedMillis(),
                event.error());
    }
}
