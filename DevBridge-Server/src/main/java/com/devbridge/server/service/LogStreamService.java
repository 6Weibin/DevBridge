package com.devbridge.server.service;

import com.devbridge.server.command.StreamingCommandRunner;
import com.devbridge.server.command.StreamingProcess;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.BusinessException;
import com.devbridge.server.model.DeviceDetail;
import com.devbridge.server.model.LogEvent;
import com.devbridge.server.model.LogSessionInfo;
import com.devbridge.server.model.LogStreamQuery;
import com.devbridge.server.model.Platform;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 实时日志会话服务，负责创建 SSE、管理 logcat 进程和断开清理。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class LogStreamService {

    private static final Pattern THREADTIME = Pattern.compile(
            "^(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\d+\\s+(\\d+)\\s+([VDIWEF])\\s+([^:]+):\\s?(.*)$");
    private static final Pattern IOS_SYSLOG = Pattern.compile(
            "^([A-Z][a-z]{2}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3,6})?)\\s+(?:(\\S+)\\s+)?([^\\s\\[(]+)(?:\\(([^)]*)\\))?\\[(\\d+)]\\s+<([^>]+)>:\\s?(.*)$");
    static final String LOG_EVENT_NAME = "log";
    static final String TOOL_ERROR_EVENT_NAME = "tool-error";
    static final String ANDROID_INITIAL_REPLAY_LINES = "1000";

    private final StreamingCommandRunner streamingCommandRunner;
    private final AndroidDeviceService androidDeviceService;
    private final IosDeviceService iosDeviceService;
    private final LogCaptureService logCaptureService;
    private final DevBridgeProperties properties;
    private final Map<String, LogSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> serialSessions = new ConcurrentHashMap<>();

    /**
     * 注入日志流依赖。
     *
     * @param streamingCommandRunner 长进程执行器
     * @param androidDeviceService Android 设备能力服务
     * @param iosDeviceService iOS 设备能力服务
     * @param logCaptureService 日志采集文件服务
     * @param properties DevBridge 配置
     */
    public LogStreamService(
            StreamingCommandRunner streamingCommandRunner,
            AndroidDeviceService androidDeviceService,
            IosDeviceService iosDeviceService,
            LogCaptureService logCaptureService,
            DevBridgeProperties properties) {
        this.streamingCommandRunner = streamingCommandRunner;
        this.androidDeviceService = androidDeviceService;
        this.iosDeviceService = iosDeviceService;
        this.logCaptureService = logCaptureService;
        this.properties = properties;
    }

    /**
     * 创建 Android logcat SSE 会话。
     *
     * @param serial 设备序列号
     * @param query 查询条件
     * @return SSE emitter
     */
    public SseEmitter streamAndroidLogs(String serial, LogStreamQuery query) {
        String sessionKey = reserveSession(Platform.ANDROID, serial);
        DeviceDetail detail = androidDeviceService.getDetail(serial);
        LogCaptureService.CaptureSession capture = logCaptureService.createSession(Platform.ANDROID, serial, detail.model());
        SseEmitter emitter = new SseEmitter(properties.getLogStreamTimeout().toMillis());
        AtomicLong counter = new AtomicLong();
        AtomicReference<StreamingProcess> processRef = new AtomicReference<>();
        AtomicReference<LogSession> sessionRef = new AtomicReference<>();
        List<String> command = List.of(
                androidDeviceService.adbExecutable(),
                "-s",
                serial,
                "logcat",
                "-v",
                "threadtime",
                "-T",
                ANDROID_INITIAL_REPLAY_LINES);
        StreamingProcess process = streamingCommandRunner.start(
                command,
                properties.getLogStreamTimeout(),
                line -> {
                    if (!capture.writeLine(line)) {
                        stopBrokenStream(sessionRef, processRef);
                        return;
                    }
                    if (!sendLogLine(emitter, counter, line, query)) {
                        stopBrokenStream(sessionRef, processRef);
                    }
                },
                line -> {
                    if (!capture.writeLine("[stderr] " + line)) {
                        stopBrokenStream(sessionRef, processRef);
                        return;
                    }
                    if (!sendErrorLine(emitter, counter, line)) {
                        stopBrokenStream(sessionRef, processRef);
                    }
                });
        processRef.set(process);
        LogSession session = new LogSession(process.id(), Platform.ANDROID, serial, process, emitter, capture, Instant.now());
        sessionRef.set(session);
        sessions.put(process.id(), session);
        serialSessions.put(sessionKey, process.id());
        registerCleanup(session);
        return emitter;
    }

    /**
     * 创建 iOS idevicesyslog SSE 会话。
     *
     * @param udid iOS 设备 UDID
     * @param query 查询条件
     * @return SSE emitter
     */
    public SseEmitter streamIosLogs(String udid, LogStreamQuery query) {
        String sessionKey = reserveSession(Platform.IOS, udid);
        DeviceDetail detail = iosDeviceService.getDetail(udid);
        LogCaptureService.CaptureSession capture = logCaptureService.createSession(Platform.IOS, udid, detail.model());
        SseEmitter emitter = new SseEmitter(properties.getLogStreamTimeout().toMillis());
        AtomicLong counter = new AtomicLong();
        AtomicReference<LogEvent> previousEventRef = new AtomicReference<>();
        AtomicReference<StreamingProcess> processRef = new AtomicReference<>();
        AtomicReference<LogSession> sessionRef = new AtomicReference<>();
        List<String> command = List.of(iosDeviceService.idevicesyslogExecutable(), "-u", udid);
        StreamingProcess process = streamingCommandRunner.start(
                command,
                properties.getLogStreamTimeout(),
                line -> {
                    if (!capture.writeLine(line)) {
                        stopBrokenStream(sessionRef, processRef);
                        return;
                    }
                    if (!sendIosLogLine(emitter, counter, previousEventRef, line, query)) {
                        stopBrokenStream(sessionRef, processRef);
                    }
                },
                line -> {
                    if (!capture.writeLine("[stderr] " + line)) {
                        stopBrokenStream(sessionRef, processRef);
                        return;
                    }
                    if (!sendIosErrorLine(emitter, counter, line)) {
                        stopBrokenStream(sessionRef, processRef);
                    }
                });
        processRef.set(process);
        LogSession session = new LogSession(process.id(), Platform.IOS, udid, process, emitter, capture, Instant.now());
        sessionRef.set(session);
        sessions.put(process.id(), session);
        serialSessions.put(sessionKey, process.id());
        registerCleanup(session);
        return emitter;
    }

    /**
     * 为 Agent 启动无 SSE 的实时日志采集，复用同一进程、滚动文件和单设备会话约束。
     *
     * @param platformValue 平台字符串
     * @param serial 设备序列号
     * @return 日志会话信息
     */
    public LogSessionInfo startCapture(String platformValue, String serial) {
        Platform platform = platformFrom(platformValue);
        if (platform == Platform.HARMONY) {
            throw new BusinessException(
                    "PLATFORM_UNSUPPORTED", "当前平台暂不支持实时日志采集", HttpStatus.BAD_REQUEST, platformValue);
        }
        String sessionKey = reserveSession(platform, serial);
        DeviceDetail detail = platform == Platform.ANDROID
                ? androidDeviceService.getDetail(serial)
                : iosDeviceService.getDetail(serial);
        LogCaptureService.CaptureSession capture = logCaptureService.createSession(platform, serial, detail.model());
        AtomicReference<StreamingProcess> processRef = new AtomicReference<>();
        AtomicReference<LogSession> sessionRef = new AtomicReference<>();
        List<String> command = captureCommand(platform, serial);
        StreamingProcess process = streamingCommandRunner.start(
                command,
                properties.getLogStreamTimeout(),
                line -> writeAgentCapture(capture, line, sessionRef, processRef),
                line -> writeAgentCapture(capture, "[stderr] " + line, sessionRef, processRef));
        processRef.set(process);
        LogSession session = new LogSession(process.id(), platform, serial, process, null, capture, Instant.now());
        sessionRef.set(session);
        sessions.put(process.id(), session);
        serialSessions.put(sessionKey, process.id());
        return new LogSessionInfo(session.id(), platform, serial, "running", session.startedAt());
    }

    /**
     * 查询日志会话和底层进程状态。
     *
     * @param sessionId 会话 ID
     * @return 会话状态
     */
    public LogSessionInfo status(String sessionId) {
        LogSession session = requireSession(sessionId);
        String status = session.process().isAlive() ? "running" : "completed";
        return new LogSessionInfo(session.id(), session.platform(), session.serial(), status, session.startedAt());
    }

    /**
     * 返回日志会话当前滚动文件快照，调用方只能读取文件内容，不能向外暴露路径。
     *
     * @param sessionId 会话 ID
     * @return 日志文件快照
     */
    public List<Path> captureFiles(String sessionId) {
        return requireSession(sessionId).capture().files();
    }

    /**
     * 停止指定会话并压缩其已采集日志。
     *
     * @param sessionId 会话 ID
     * @return zip 临时文件
     */
    public Path exportSession(String sessionId) {
        LogSession session = requireSession(sessionId);
        cleanup(session, true);
        return logCaptureService.zipSession(session.capture());
    }

    /**
     * 停止指定日志会话。
     *
     * @param sessionId 会话 ID
     * @return 会话状态
     */
    public LogSessionInfo stop(String sessionId) {
        LogSession session = sessions.get(sessionId);
        if (session == null) {
            throw new BusinessException("LOG_SESSION_NOT_FOUND", "日志会话不存在", HttpStatus.NOT_FOUND, sessionId);
        }
        cleanup(session, true);
        return new LogSessionInfo(session.id(), session.platform(), session.serial(), "stopped", session.startedAt());
    }

    /**
     * 停止指定设备当前采集会话，并将本次采集保留的日志文件压缩为 zip。
     *
     * @param platformValue 平台值
     * @param serial 设备序列号
     * @return zip 文件路径
     */
    public Path exportCapturedLogs(String platformValue, String serial) {
        Platform platform = platformFrom(platformValue);
        if (platform == Platform.HARMONY) {
            throw new BusinessException("PLATFORM_UNSUPPORTED", "当前平台暂不支持实时日志导出", HttpStatus.BAD_REQUEST, platformValue);
        }
        LogSession session = activeSession(platform, serial);
        cleanup(session, true);
        return logCaptureService.zipSession(session.capture());
    }

    /**
     * 注册 SSE 生命周期回调，确保任意断开路径都会停止进程。
     *
     * @param session 日志会话
     */
    private void registerCleanup(LogSession session) {
        session.emitter().onCompletion(() -> cleanup(session));
        session.emitter().onTimeout(() -> cleanup(session));
        session.emitter().onError(error -> cleanup(session));
    }

    /**
     * 清理日志会话和底层进程。
     *
     * @param session 日志会话
     */
    private void cleanup(LogSession session) {
        cleanup(session, false);
    }

    /**
     * 清理日志会话，可在用户主动停止或导出时完成 SSE 响应。
     *
     * @param session 日志会话
     * @param completeEmitter 是否主动完成 SSE 响应
     */
    private void cleanup(LogSession session, boolean completeEmitter) {
        session.process().stop();
        session.capture().close();
        sessions.remove(session.id());
        serialSessions.remove(sessionKey(session.platform(), session.serial()));
        if (completeEmitter && session.emitter() != null) {
            completeQuietly(session.emitter());
        }
    }

    /**
     * 构造 Agent 日志采集命令。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @return 命令参数
     */
    private List<String> captureCommand(Platform platform, String serial) {
        if (platform == Platform.ANDROID) {
            return List.of(
                    androidDeviceService.adbExecutable(), "-s", serial,
                    "logcat", "-v", "threadtime", "-T", ANDROID_INITIAL_REPLAY_LINES);
        }
        if (platform == Platform.IOS) {
            iosDeviceService.ensureLoggable(serial);
            return List.of(iosDeviceService.idevicesyslogExecutable(), "-u", serial);
        }
        throw new BusinessException(
                "PLATFORM_UNSUPPORTED", "当前平台暂不支持实时日志采集", HttpStatus.BAD_REQUEST, platform.getValue());
    }

    /**
     * 写入 Agent 采集文件，写入失败时停止底层进程并清理会话。
     *
     * @param capture 采集文件会话
     * @param line 日志行
     * @param sessionRef 会话引用
     * @param processRef 进程引用
     */
    private void writeAgentCapture(
            LogCaptureService.CaptureSession capture,
            String line,
            AtomicReference<LogSession> sessionRef,
            AtomicReference<StreamingProcess> processRef) {
        if (!capture.writeLine(line)) {
            stopBrokenStream(sessionRef, processRef);
        }
    }

    /**
     * 用户主动停止时完成 SSE；若浏览器已经断开，忽略完成异常。
     *
     * @param emitter SSE emitter
     */
    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // 浏览器可能已经先断开，受控停止不需要再触发错误响应。
        }
    }

    /**
     * SSE 响应已断开时停止底层日志进程；此时不能再操作 HTTP 响应，否则会触发 Spring 异常分发。
     *
     * @param sessionRef 日志会话引用
     * @param processRef 日志进程引用
     */
    private void stopBrokenStream(AtomicReference<LogSession> sessionRef, AtomicReference<StreamingProcess> processRef) {
        LogSession session = sessionRef.get();
        if (session != null) {
            cleanup(session);
            return;
        }
        StreamingProcess process = processRef.get();
        if (process != null) {
            process.stop();
        }
    }

    /**
     * 为设备预留日志会话；浏览器断开或 EventSource 重连可能留下短暂旧会话，新会话接管可避免用户点击无反应。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @return 会话 key
     */
    private String reserveSession(Platform platform, String serial) {
        String sessionKey = sessionKey(platform, serial);
        String oldSessionId = serialSessions.get(sessionKey);
        if (oldSessionId != null) {
            LogSession oldSession = sessions.get(oldSessionId);
            if (oldSession != null) {
                cleanup(oldSession, true);
            } else {
                serialSessions.remove(sessionKey);
            }
        }
        return sessionKey;
    }

    /**
     * 发送 logcat 正常日志行。
     *
     * @param emitter SSE emitter
     * @param counter 事件计数器
     * @param line 原始日志行
     * @param query 查询条件
     */
    private boolean sendLogLine(SseEmitter emitter, AtomicLong counter, String line, LogStreamQuery query) {
        LogEvent event = parseLogLine(counter.incrementAndGet(), line);
        if (!matches(event, query)) {
            return true;
        }
        return safeSend(emitter, LOG_EVENT_NAME, event);
    }

    /**
     * 发送 logcat 错误输出。
     *
     * @param emitter SSE emitter
     * @param counter 事件计数器
     * @param line 错误行
     */
    private boolean sendErrorLine(SseEmitter emitter, AtomicLong counter, String line) {
        LogEvent event = new LogEvent(counter.incrementAndGet(), "", "E", "", "logcat", line, "error");
        // 工具 stderr 是业务提示，不使用 SSE 保留语义的 error，避免前端误判为连接失败。
        return safeSend(emitter, TOOL_ERROR_EVENT_NAME, event);
    }

    /**
     * 发送 iOS 系统日志行；idevicesyslog 输出量很大，过滤在服务端先执行以减少 SSE 压力。
     *
     * @param emitter SSE emitter
     * @param counter 事件计数器
     * @param previousEventRef 上一条可解析 iOS 日志
     * @param line 原始日志行
     * @param query 查询条件
     */
    private boolean sendIosLogLine(
            SseEmitter emitter,
            AtomicLong counter,
            AtomicReference<LogEvent> previousEventRef,
            String line,
            LogStreamQuery query) {
        LogEvent event = parseIosLogLine(counter.incrementAndGet(), line, previousEventRef.get());
        if (!event.timestamp().isBlank()) {
            previousEventRef.set(event);
        }
        if (!matches(event, query)) {
            return true;
        }
        return safeSend(emitter, LOG_EVENT_NAME, event);
    }

    /**
     * 发送 iOS 日志工具错误输出。
     *
     * @param emitter SSE emitter
     * @param counter 事件计数器
     * @param line 错误行
     */
    private boolean sendIosErrorLine(SseEmitter emitter, AtomicLong counter, String line) {
        LogEvent event = new LogEvent(counter.incrementAndGet(), "", "E", "", "idevicesyslog", line, "error");
        // 工具 stderr 是业务提示，不使用 SSE 保留语义的 error，避免前端误判为连接失败。
        return safeSend(emitter, TOOL_ERROR_EVENT_NAME, event);
    }

    /**
     * 解析 threadtime 日志行。
     *
     * @param id 事件 ID
     * @param line 原始行
     * @return 日志事件
     */
    private LogEvent parseLogLine(long id, String line) {
        Matcher matcher = THREADTIME.matcher(line);
        if (!matcher.matches()) {
            return new LogEvent(id, "", "I", "", "logcat", line, "log");
        }
        return new LogEvent(id, matcher.group(1), matcher.group(3), matcher.group(2), matcher.group(4).trim(), matcher.group(5), "log");
    }

    /**
     * 解析 idevicesyslog 日志行，兼容带 subsystem 和不带 subsystem 的 iOS 系统日志。
     *
     * @param id 事件 ID
     * @param line 原始行
     * @return 日志事件
     */
    static LogEvent parseIosLogLine(long id, String line) {
        return parseIosLogLine(id, line, null);
    }

    /**
     * 解析 idevicesyslog 日志行；无前缀的多行堆栈继承上一条日志上下文，避免前端丢失时间和 PID。
     *
     * @param id 事件 ID
     * @param line 原始行
     * @param previousEvent 上一条可解析日志
     * @return 日志事件
     */
    static LogEvent parseIosLogLine(long id, String line, LogEvent previousEvent) {
        Matcher matcher = IOS_SYSLOG.matcher(line);
        if (!matcher.matches()) {
            if (shouldInheritIosContext(line, previousEvent)) {
                // idevicesyslog 的 Callstack 等续行没有头部字段，沿用上一条日志上下文才能保持表格字段完整。
                return new LogEvent(
                        id,
                        previousEvent.timestamp(),
                        previousEvent.level(),
                        previousEvent.pid(),
                        previousEvent.tag(),
                        line,
                        "log");
            }
            return new LogEvent(id, "", "I", "", "idevicesyslog", line, "log");
        }
        String process = matcher.group(3);
        String subsystem = matcher.group(4);
        // idevicesyslog 不同版本会在时间后插入设备主机名；解析时忽略该字段，保留真正的进程、PID 和级别。
        String tag = subsystem == null || subsystem.isBlank() ? process : subsystem;
        return new LogEvent(
                id,
                matcher.group(1),
                iosLevel(matcher.group(6)),
                matcher.group(5),
                tag,
                matcher.group(7),
                "log");
    }

    /**
     * 判断 iOS 无前缀日志是否应继承上一条日志上下文。
     *
     * @param line 原始行
     * @param previousEvent 上一条日志
     * @return 需要继承时返回 true
     */
    private static boolean shouldInheritIosContext(String line, LogEvent previousEvent) {
        return previousEvent != null
                && !previousEvent.timestamp().isBlank()
                && line != null
                && !line.isBlank()
                && !line.startsWith("[");
    }

    /**
     * 将 iOS 文本级别映射到前端统一的 V/D/I/W/E/F 级别。
     *
     * @param level idevicesyslog 级别
     * @return 前端日志级别
     */
    private static String iosLevel(String level) {
        String normalized = level == null ? "" : level.toLowerCase();
        if (normalized.contains("debug")) {
            return "D";
        }
        if (normalized.contains("error")) {
            return "E";
        }
        if (normalized.contains("fault")) {
            return "F";
        }
        if (normalized.contains("warn")) {
            return "W";
        }
        return "I";
    }

    /**
     * 判断日志事件是否匹配前端过滤条件。
     *
     * @param event 日志事件
     * @param query 查询条件
     * @return 匹配返回 true
     */
    private boolean matches(LogEvent event, LogStreamQuery query) {
        boolean levelMatched = query == null || query.level() == null || query.level().isBlank()
                || "ALL".equalsIgnoreCase(query.level()) || event.level().equalsIgnoreCase(query.level());
        boolean textMatched = query == null || query.filter() == null || query.filter().isBlank()
                || event.tag().toLowerCase().contains(query.filter().toLowerCase())
                || event.message().toLowerCase().contains(query.filter().toLowerCase());
        return levelMatched && textMatched;
    }

    /**
     * 安全发送 SSE 事件，发送失败时关闭连接。
     *
     * @param emitter SSE emitter
     * @param eventName 事件名
     * @param event 日志事件
     * @return 发送成功返回 true，连接已不可用返回 false
     */
    private boolean safeSend(SseEmitter emitter, String eventName, LogEvent event) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(event));
            return true;
        } catch (IOException | IllegalStateException ex) {
            // 响应已经断开时不能再 complete/completeWithError，否则 Spring 会尝试用 text/event-stream 写 JSON 错误。
            return false;
        }
    }

    /**
     * 生成单设备单会话 key，避免不同平台相同序列号互相占用。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @return 会话 key
     */
    private String sessionKey(Platform platform, String serial) {
        return platform.getValue() + ":" + serial;
    }

    /**
     * 查找设备当前活跃日志会话，导出按钮以设备为入口时不需要前端持有 sessionId。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @return 活跃会话
     */
    private LogSession activeSession(Platform platform, String serial) {
        String sessionKey = sessionKey(platform, serial);
        String sessionId = serialSessions.get(sessionKey);
        LogSession session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null) {
            serialSessions.remove(sessionKey);
            throw new BusinessException("LOG_SESSION_NOT_FOUND", "没有正在采集的日志会话", HttpStatus.NOT_FOUND, sessionKey);
        }
        return session;
    }

    /**
     * 按 ID 查找日志会话。
     *
     * @param sessionId 会话 ID
     * @return 日志会话
     */
    private LogSession requireSession(String sessionId) {
        LogSession session = sessions.get(sessionId);
        if (session == null) {
            throw new BusinessException("LOG_SESSION_NOT_FOUND", "日志会话不存在", HttpStatus.NOT_FOUND, sessionId);
        }
        return session;
    }

    /**
     * 将接口平台字符串转换成内部枚举，保持 Controller 逻辑简单。
     *
     * @param platformValue 平台字符串
     * @return 平台枚举
     */
    private Platform platformFrom(String platformValue) {
        for (Platform platform : Platform.values()) {
            if (platform.getValue().equalsIgnoreCase(platformValue)) {
                return platform;
            }
        }
        throw new BusinessException("PLATFORM_UNSUPPORTED", "当前平台暂不支持实时日志导出", HttpStatus.BAD_REQUEST, platformValue);
    }

    /**
     * 内部日志会话对象。
     *
     * <p>by AI.Coding</p>
     *
     * @param id 会话 ID
     * @param platform 平台
     * @param serial 设备序列号
     * @param process 底层进程
     * @param emitter SSE emitter
     * @param capture 采集文件会话
     * @param startedAt 开始时间
     */
    private record LogSession(
            String id,
            Platform platform,
            String serial,
            StreamingProcess process,
            SseEmitter emitter,
            LogCaptureService.CaptureSession capture,
            Instant startedAt) {
    }
}
