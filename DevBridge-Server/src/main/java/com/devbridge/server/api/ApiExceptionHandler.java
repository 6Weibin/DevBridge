package com.devbridge.server.api;

import com.devbridge.server.model.ApiError;
import com.devbridge.server.model.BusinessException;
import com.devbridge.server.ai.agent.runtime.AgentTaskTransitionException;
import com.devbridge.server.ai.agent.runtime.AgentTaskIdempotencyException;
import java.io.IOException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * API 异常处理器，统一输出前端可识别的错误码和摘要。
 *
 * <p>by AI.Coding</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 处理业务异常，保留稳定错误码供前端展示。
     *
     * @param ex 业务异常
     * @return 统一错误响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusinessException(BusinessException ex) {
        ApiError error = new ApiError(ex.getErrorCode(), ex.getMessage(), ex.getDetail(), Instant.now());
        return ResponseEntity.status(ex.getHttpStatus()).body(error);
    }

    /**
     * 处理浏览器主动关闭 SSE/下载连接导致的写失败；此时响应已断开，不能再写 JSON 错误体。
     *
     * @param ex IO 异常
     */
    @ExceptionHandler(IOException.class)
    public void handleIOException(IOException ex) {
        if (isClientDisconnect(ex)) {
            LOGGER.debug("客户端已断开流式响应", ex);
            return;
        }
        LOGGER.warn("API IO 异常，响应可能已不可写", ex);
    }

    /**
     * 处理已删除或不存在的接口/静态资源；演示接口删除后应返回 404，而不是误报服务内部错误。
     *
     * @param ex 资源不存在异常
     * @return 统一 404 响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResourceFound(NoResourceFoundException ex) {
        ApiError error = new ApiError("NOT_FOUND", "资源不存在", ex.getResourcePath(), Instant.now());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * 处理非法 API 参数，返回稳定 400 而不是内部错误。
     *
     * @param ex 参数异常
     * @return 统一 400 响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        ApiError error = new ApiError("INVALID_ARGUMENT", ex.getMessage(), "", Instant.now());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * 处理 Agent 非法状态转换，返回状态冲突。
     *
     * @param ex 状态转换异常
     * @return 统一 409 响应
     */
    @ExceptionHandler(AgentTaskTransitionException.class)
    public ResponseEntity<ApiError> handleAgentTransition(AgentTaskTransitionException ex) {
        ApiError error = new ApiError("AGENT_STATE_CONFLICT", ex.getMessage(), "", Instant.now());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * 处理任务创建幂等键复用冲突，避免重复副作用请求被当成服务异常。
     *
     * @param ex 幂等冲突
     * @return 统一 409 响应
     */
    @ExceptionHandler(AgentTaskIdempotencyException.class)
    public ResponseEntity<ApiError> handleAgentIdempotency(AgentTaskIdempotencyException ex) {
        ApiError error = new ApiError("AGENT_IDEMPOTENCY_CONFLICT", ex.getMessage(), "", Instant.now());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * 处理未预期异常；日志记录服务端详情，响应只返回摘要，避免泄露命令输出。
     *
     * @param ex 未预期异常
     * @return 统一错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedException(Exception ex) {
        LOGGER.error("未处理的 API 异常", ex);
        ApiError error = new ApiError("INTERNAL_ERROR", "服务内部错误", ex.getClass().getSimpleName(), Instant.now());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * 判断异常是否来自客户端断开连接，避免 SSE 断连进入全局 JSON 异常响应链。
     *
     * @param ex IO 异常
     * @return 客户端断开返回 true
     */
    private boolean isClientDisconnect(IOException ex) {
        String message = ex.getMessage();
        return message != null
                && (message.contains("Broken pipe")
                || message.contains("Connection reset")
                || message.contains("An established connection was aborted"));
    }
}
