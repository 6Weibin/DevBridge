package com.devbridge.server.api;

import com.devbridge.server.model.ApiError;
import com.devbridge.server.model.BusinessException;
import com.devbridge.server.model.LogSessionInfo;
import com.devbridge.server.model.LogStreamQuery;
import com.devbridge.server.service.LogStreamService;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 设备日志接口，提供 Android/iOS 实时日志和 Android 日志导出。
 *
 * <p>by AI.Coding</p>
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class LogController {

    private final LogStreamService logStreamService;

    /**
     * 注入日志能力服务。
     *
     * @param logStreamService 实时日志服务
     */
    public LogController(LogStreamService logStreamService) {
        this.logStreamService = logStreamService;
    }

    /**
     * 明确拒绝旧演示日志接口，确保删除后不会再返回任何假数据。
     *
     * @return 统一 404 错误响应
     */
    @GetMapping("/logs/demo")
    public ResponseEntity<ApiError> removedDemoLogs() {
        ApiError error = new ApiError("NOT_FOUND", "资源不存在", "/api/logs/demo", Instant.now());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * 建立实时日志 SSE 流；Android 使用 logcat，iOS 使用 idevicesyslog。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @param level 日志级别
     * @param filter 文本过滤
     * @return SSE emitter
     */
    @GetMapping("/devices/{platform}/{serial}/logs/stream")
    public SseEmitter streamLogs(
            @PathVariable String platform,
            @PathVariable String serial,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String filter) {
        if ("android".equalsIgnoreCase(platform)) {
            return logStreamService.streamAndroidLogs(serial, new LogStreamQuery(level, filter));
        }
        if ("ios".equalsIgnoreCase(platform)) {
            return logStreamService.streamIosLogs(serial, new LogStreamQuery(level, filter));
        }
        throw new BusinessException("PLATFORM_UNSUPPORTED", "当前平台暂不支持实时日志", HttpStatus.BAD_REQUEST, platform);
    }

    /**
     * 停止实时日志会话。
     *
     * @param sessionId 会话 ID
     * @return 会话状态
     */
    @PostMapping("/logs/sessions/{sessionId}/stop")
    public LogSessionInfo stopSession(@PathVariable String sessionId) {
        return logStreamService.stop(sessionId);
    }

    /**
     * 停止当前采集会话并导出本次采集日志 zip。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @return 日志下载响应
     */
    @GetMapping("/devices/{platform}/{serial}/logs/export")
    public ResponseEntity<StreamingResponseBody> exportLogs(@PathVariable String platform, @PathVariable String serial) {
        Path zipFile = logStreamService.exportCapturedLogs(platform, serial);
        StreamingResponseBody body = outputStream -> {
            try (InputStream inputStream = Files.newInputStream(zipFile)) {
                inputStream.transferTo(outputStream);
            } finally {
                Files.deleteIfExists(zipFile);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(serial, zipFile))
                .body(body);
    }

    /**
     * 构造日志下载响应头。
     *
     * @param serial 设备序列号
     * @param zipFile zip 文件路径
     * @return Content-Disposition 响应头
     */
    private String contentDisposition(String serial, Path zipFile) {
        String filename = zipFile.getFileName() == null ? "device-logs-" + serial + ".zip" : zipFile.getFileName().toString();
        return ContentDisposition.attachment().filename(filename).build().toString();
    }
}
