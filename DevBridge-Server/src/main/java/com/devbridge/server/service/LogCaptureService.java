package com.devbridge.server.service;

import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.BusinessException;
import com.devbridge.server.model.Platform;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 实时日志采集文件服务，负责本地落盘、滚动文件和导出压缩包。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class LogCaptureService {

    static final long DEFAULT_MAX_LOG_FILE_BYTES = 10L * 1024L * 1024L;
    static final int DEFAULT_MAX_LOG_FILES = 20;
    private static final DateTimeFormatter DATE_FOLDER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter ZIP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern UNSAFE_PATH_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\s]+");

    private final DevBridgeProperties properties;
    private final long maxFileBytes;
    private final int maxFiles;

    /**
     * 注入日志采集配置。
     *
     * @param properties DevBridge 配置
     */
    @Autowired
    public LogCaptureService(DevBridgeProperties properties) {
        this(properties, DEFAULT_MAX_LOG_FILE_BYTES, DEFAULT_MAX_LOG_FILES);
    }

    /**
     * 创建可配置滚动参数的采集服务，供单元测试用小文件快速验证滚动逻辑。
     *
     * @param properties DevBridge 配置
     * @param maxFileBytes 单文件最大字节数
     * @param maxFiles 最大滚动文件数
     */
    LogCaptureService(DevBridgeProperties properties, long maxFileBytes, int maxFiles) {
        this.properties = properties;
        this.maxFileBytes = maxFileBytes;
        this.maxFiles = maxFiles;
    }

    /**
     * 创建一次实时日志采集会话，并按日期、平台、设备型号建立本地目录。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @param model 设备型号
     * @return 采集会话
     */
    public CaptureSession createSession(Platform platform, String serial, String model) {
        try {
            String date = LocalDate.now().format(DATE_FOLDER);
            Path root = Path.of(properties.getLogCaptureRoot());
            Path directory = root.resolve(date).resolve(platformDirectory(platform)).resolve(safePathName(model));
            Files.createDirectories(directory);
            return new CaptureSession(root, directory, date, safePathName(serial), maxFileBytes, maxFiles);
        } catch (IOException ex) {
            throw new BusinessException("LOG_CAPTURE_CREATE_FAILED", "创建日志采集目录失败", HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    /**
     * 将采集会话已保留的日志文件压缩成 zip，调用方负责下载完成后删除 zip 临时文件。
     *
     * @param session 采集会话
     * @return zip 文件路径
     */
    public Path zipSession(CaptureSession session) {
        session.close();
        try {
            Path zipPath = zipPath(session);
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipPath))) {
                for (Path file : session.files()) {
                    addZipEntry(session.root(), file, zip);
                }
            }
            return zipPath;
        } catch (IOException ex) {
            throw new BusinessException("LOG_EXPORT_FAILED", "日志压缩导出失败", HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    /**
     * 生成 zip 临时文件路径，放在下载临时目录下，避免污染采集目录。
     *
     * @param session 采集会话
     * @return zip 路径
     * @throws IOException 创建目录失败时抛出
     */
    private Path zipPath(CaptureSession session) throws IOException {
        Path tempRoot = Path.of(properties.getDownloadTempRoot());
        Files.createDirectories(tempRoot);
        String filename = "logs-" + session.serial() + "-" + LocalDateTime.now().format(ZIP_TIMESTAMP) + ".zip";
        return tempRoot.resolve(filename);
    }

    /**
     * 向 zip 中写入一个日志文件，保留日期/平台/型号/文件名的相对目录结构。
     *
     * @param root 采集根目录
     * @param file 日志文件
     * @param zip zip 输出流
     * @throws IOException 写入失败时抛出
     */
    private void addZipEntry(Path root, Path file, ZipOutputStream zip) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        String entryName = root.relativize(file).toString().replace('\\', '/');
        zip.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    /**
     * 转换平台目录名，满足 Android/iOS/Harmony 的输出路径要求。
     *
     * @param platform 平台
     * @return 平台目录名
     */
    private String platformDirectory(Platform platform) {
        return switch (platform) {
            case ANDROID -> "Android";
            case IOS -> "iOS";
            case HARMONY -> "Harmony";
        };
    }

    /**
     * 清理路径片段中的空白和非法字符，避免设备型号影响本机目录结构。
     *
     * @param value 原始路径片段
     * @return 安全路径片段
     */
    private String safePathName(String value) {
        String source = StringUtils.hasText(value) ? value.trim() : "Unknown";
        String safe = UNSAFE_PATH_CHARS.matcher(source).replaceAll("_");
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    /**
     * 单次日志采集会话，内部同步写入以兼容 stdout/stderr 两个读取线程。
     *
     * <p>by AI.Coding</p>
     */
    public static final class CaptureSession {

        private final Path root;
        private final Path directory;
        private final String date;
        private final String serial;
        private final long maxFileBytes;
        private final int maxFiles;
        private final LinkedList<Path> files = new LinkedList<>();
        private OutputStream output;
        private int fileNumber = 1;
        private long currentBytes;
        private boolean closed;

        /**
         * 创建采集会话并打开第一个日志文件。
         *
         * @param root 采集根目录
         * @param directory 当前设备目录
         * @param date 日期文本
         * @param serial 设备序列号
         * @param maxFileBytes 单文件最大字节数
         * @param maxFiles 最大滚动文件数
         * @throws IOException 打开文件失败时抛出
         */
        CaptureSession(Path root, Path directory, String date, String serial, long maxFileBytes, int maxFiles) throws IOException {
            this.root = root;
            this.directory = directory;
            this.date = date;
            this.serial = serial;
            this.maxFileBytes = maxFileBytes;
            this.maxFiles = maxFiles;
            openFile(1);
        }

        /**
         * 写入一行原始日志，单文件达到上限时切换到下一个滚动文件。
         *
         * @param line 原始日志
         * @return 写入成功返回 true
         */
        public synchronized boolean writeLine(String line) {
            if (closed) {
                return false;
            }
            try {
                byte[] bytes = logBytes(line);
                if (currentBytes > 0 && currentBytes + bytes.length > maxFileBytes) {
                    openFile(nextFileNumber());
                }
                output.write(bytes);
                output.flush();
                currentBytes += bytes.length;
                return true;
            } catch (IOException ex) {
                close();
                return false;
            }
        }

        /**
         * 关闭当前输出流，允许后续安全压缩文件。
         */
        public synchronized void close() {
            closed = true;
            if (output == null) {
                return;
            }
            try {
                output.close();
            } catch (IOException ignored) {
                // 关闭失败不影响后续会话清理，导出阶段会按实际文件内容压缩。
            } finally {
                output = null;
            }
        }

        /**
         * 返回当前会话保留的日志文件快照。
         *
         * @return 日志文件列表
         */
        public synchronized List<Path> files() {
            return new ArrayList<>(files);
        }

        /**
         * 获取采集根目录。
         *
         * @return 采集根目录
         */
        Path root() {
            return root;
        }

        /**
         * 获取安全序列号，用于 zip 文件命名。
         *
         * @return 安全序列号
         */
        String serial() {
            return serial;
        }

        /**
         * 打开指定编号的日志文件；再次滚动到同一编号时会截断旧内容，保证最多 20 个文件。
         *
         * @param number 文件编号
         * @throws IOException 打开文件失败时抛出
         */
        private void openFile(int number) throws IOException {
            closeOutputOnly();
            fileNumber = number;
            Path file = directory.resolve("log_" + date + "_" + number + ".log");
            files.remove(file);
            files.add(file);
            trimFiles();
            output = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            currentBytes = 0;
        }

        /**
         * 计算下一个滚动文件编号，达到上限后回到 1。
         *
         * @return 下一个文件编号
         */
        private int nextFileNumber() {
            return fileNumber >= maxFiles ? 1 : fileNumber + 1;
        }

        /**
         * 控制文件列表长度不超过滚动上限。
         */
        private void trimFiles() {
            while (files.size() > maxFiles) {
                files.removeFirst();
            }
        }

        /**
         * 将日志行编码成 UTF-8 字节；超长单行会截断，保证单文件不会超过配置上限。
         *
         * @param line 原始日志
         * @return 日志字节
         */
        private byte[] logBytes(String line) {
            byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            if (bytes.length <= maxFileBytes) {
                return bytes;
            }
            byte[] marker = "...[truncated]".getBytes(StandardCharsets.UTF_8);
            if (maxFileBytes <= marker.length) {
                return Arrays.copyOf(bytes, (int) maxFileBytes);
            }
            byte[] truncated = new byte[(int) maxFileBytes];
            int payloadLength = Math.max(0, truncated.length - marker.length);
            System.arraycopy(bytes, 0, truncated, 0, payloadLength);
            System.arraycopy(marker, 0, truncated, payloadLength, marker.length);
            return truncated;
        }

        /**
         * 仅关闭当前输出流，不改变会话关闭状态，供滚动切换文件使用。
         *
         * @throws IOException 关闭失败时抛出
         */
        private void closeOutputOnly() throws IOException {
            if (output != null) {
                output.close();
            }
            output = null;
        }
    }
}
