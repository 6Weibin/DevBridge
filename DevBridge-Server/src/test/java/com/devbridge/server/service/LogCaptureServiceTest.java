package com.devbridge.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 实时日志采集文件服务测试，覆盖路径结构、滚动限制和 zip 导出。
 *
 * <p>by AI.Coding</p>
 */
class LogCaptureServiceTest {

    /**
     * 验证日志文件按 日期/设备类型/设备型号/log_日期_编号.log 结构落盘，并在 zip 中保留相对路径。
     */
    @Test
    void createSessionShouldUseRequiredDirectoryStructure(@TempDir Path tempDir) throws IOException {
        LogCaptureService service = new LogCaptureService(testProperties(tempDir), 1024, 20);
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);

        LogCaptureService.CaptureSession session = service.createSession(Platform.ANDROID, "SERIAL-1", "Pixel 7 Pro");
        session.writeLine("hello");
        Path zip = service.zipSession(session);

        assertThat(session.files().get(0))
                .endsWith(Path.of(today, "Android", "Pixel_7_Pro", "log_" + today + "_1.log"));
        assertThat(firstZipEntryName(zip))
                .isEqualTo(today + "/Android/Pixel_7_Pro/log_" + today + "_1.log");
    }

    /**
     * 验证滚动写入不会超过配置的文件数量，且每个文件大小受单文件上限约束。
     */
    @Test
    void writeLineShouldRollFilesWithinConfiguredLimits(@TempDir Path tempDir) {
        LogCaptureService service = new LogCaptureService(testProperties(tempDir), 20, 3);
        LogCaptureService.CaptureSession session = service.createSession(Platform.IOS, "UDID-1", "iPhone 15");

        for (int index = 0; index < 8; index++) {
            assertThat(session.writeLine("123456789012345")).isTrue();
        }

        assertThat(session.files()).hasSizeLessThanOrEqualTo(3);
        assertThat(session.files()).allSatisfy(file -> {
            try {
                assertThat(Files.size(file)).isLessThanOrEqualTo(20);
            } catch (IOException ex) {
                throw new AssertionError(ex);
            }
        });
    }

    /**
     * 创建测试配置，隔离日志目录和下载临时目录。
     *
     * @param tempDir 临时目录
     * @return 配置
     */
    private DevBridgeProperties testProperties(Path tempDir) {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setLogCaptureRoot(tempDir.resolve("logs").toString());
        properties.setDownloadTempRoot(tempDir.resolve("downloads").toString());
        return properties;
    }

    /**
     * 读取 zip 中第一个 entry 名称。
     *
     * @param zip zip 路径
     * @return 第一个 entry 名称
     * @throws IOException 读取失败时抛出
     */
    private String firstZipEntryName(Path zip) throws IOException {
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            return input.getNextEntry().getName();
        }
    }
}
