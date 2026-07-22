package com.devbridge.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.config.DevBridgeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 外部工具定位器测试，覆盖跨平台内置工具目录解析。
 *
 * <p>by AI.Coding</p>
 */
class ExecutableLocatorTest {

    @TempDir
    private Path tempDir;

    /**
     * 验证 Windows 环境会优先解析项目内置的 adb.exe。
     *
     * @throws Exception 文件创建或系统属性恢复失败时抛出
     */
    @Test
    void locateShouldResolveBundledWindowsAdbExecutable() throws Exception {
        Path adb = tempDir.resolve("windows-x64").resolve("platform-tools").resolve("adb.exe");
        Files.createDirectories(adb.getParent());
        Files.writeString(adb, "mock adb");
        adb.toFile().setExecutable(true);

        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setBundledToolRoot(tempDir.toString());
        ExecutableLocator locator = new ExecutableLocator(properties);

        String originalOsName = System.getProperty("os.name");
        String originalOsArch = System.getProperty("os.arch");
        try {
            // Windows 分发包中的 adb 依赖 exe 后缀，必须通过系统架构目录优先命中。
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "amd64");

            assertThat(locator.locate(ToolCatalog.ADB)).isEqualTo(adb.toAbsolutePath().toString());
        } finally {
            System.setProperty("os.name", originalOsName);
            System.setProperty("os.arch", originalOsArch);
        }
    }
}
