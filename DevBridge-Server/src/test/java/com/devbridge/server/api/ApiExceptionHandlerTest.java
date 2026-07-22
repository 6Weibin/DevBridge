package com.devbridge.server.api;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * API 异常处理器测试，覆盖流式响应断连场景。
 *
 * <p>by AI.Coding</p>
 */
class ApiExceptionHandlerTest {

    /**
     * 验证客户端主动断开 SSE 时只做服务端清理，不再尝试写 JSON 错误响应。
     */
    @Test
    void handleIOExceptionShouldSwallowBrokenPipe() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        assertThatCode(() -> handler.handleIOException(new IOException("Broken pipe")))
                .doesNotThrowAnyException();
    }
}
