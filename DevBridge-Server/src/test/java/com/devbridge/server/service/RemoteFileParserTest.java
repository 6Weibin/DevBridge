package com.devbridge.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.model.RemoteFileType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 远端文件列表解析测试，覆盖 Android `ls -la` 常见输出。
 *
 * <p>by AI.Coding</p>
 */
class RemoteFileParserTest {

    private final RemoteFileParser parser = new RemoteFileParser();

    /**
     * 验证目录和文件行能转换为前端文件树节点。
     */
    @Test
    void parseAndroidLsShouldCreateFileNodes() {
        var nodes = parser.parseAndroidLs(List.of(
                "total 16",
                "drwxrwx--x 2 root sdcard_rw 4096 2026-06-30 10:11 Download",
                "-rw-rw---- 1 u0_a88 sdcard_rw 12345 2026-06-30 10:12 report.txt"), "/sdcard");

        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0).type()).isEqualTo(RemoteFileType.DIRECTORY);
        assertThat(nodes.get(0).path()).isEqualTo("/sdcard/Download");
        assertThat(nodes.get(1).type()).isEqualTo(RemoteFileType.FILE);
        assertThat(nodes.get(1).sizeBytes()).isEqualTo(12345L);
    }

    /**
     * 验证绝对路径形式的符号链接名称会被清理成可点击节点名，避免根目录路径拼接错误。
     */
    @Test
    void parseAndroidLsShouldCleanAbsoluteSymlinkName() {
        var nodes = parser.parseAndroidLs(List.of(
                "lrw-r--r-- 1 root root 21 2018-08-08 00:01 /sdcard -> /storage/self/primary"), "/");

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).name()).isEqualTo("sdcard");
        assertThat(nodes.get(0).path()).isEqualTo("/sdcard");
        assertThat(nodes.get(0).type()).isEqualTo(RemoteFileType.DIRECTORY);
    }

    /**
     * 验证解析结果会按目录优先、同类型名称升序返回，避免前端文件树跟随设备输出顺序抖动。
     */
    @Test
    void parseAndroidLsShouldSortDirectoriesBeforeFilesByName() {
        var nodes = parser.parseAndroidLs(List.of(
                "-rw-rw---- 1 u0_a88 sdcard_rw 12345 2026-06-30 10:12 z-report.txt",
                "drwxrwx--x 2 root sdcard_rw 4096 2026-06-30 10:11 Videos",
                "-rw-rw---- 1 u0_a88 sdcard_rw 1024 2026-06-30 10:12 a-report.txt",
                "drwxrwx--x 2 root sdcard_rw 4096 2026-06-30 10:11 Download"), "/sdcard");

        assertThat(nodes).extracting("name")
                .containsExactly("Download", "Videos", "a-report.txt", "z-report.txt");
    }
}
