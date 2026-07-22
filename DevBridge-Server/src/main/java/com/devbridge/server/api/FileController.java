package com.devbridge.server.api;

import com.devbridge.server.model.BusinessException;
import com.devbridge.server.model.RemoteFileNode;
import com.devbridge.server.service.AndroidDeviceService;
import com.devbridge.server.service.AndroidPathGuard;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 远端文件管理接口，提供 Android 可见目录浏览、下载和媒体预览能力。
 *
 * <p>by AI.Coding</p>
 */
@RestController
@RequestMapping("/api/devices/{platform}/{serial}/files")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class FileController {

    private final AndroidDeviceService androidDeviceService;
    private final AndroidPathGuard pathGuard;

    /**
     * 注入 Android 文件能力服务。
     *
     * @param androidDeviceService Android 设备能力服务
     * @param pathGuard 路径校验器
     */
    public FileController(AndroidDeviceService androidDeviceService, AndroidPathGuard pathGuard) {
        this.androidDeviceService = androidDeviceService;
        this.pathGuard = pathGuard;
    }

    /**
     * 查询远端目录列表。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @param path 远端目录路径
     * @return 文件节点列表
     */
    @GetMapping
    public List<RemoteFileNode> listFiles(
            @PathVariable String platform,
            @PathVariable String serial,
            @RequestParam String path) {
        requireAndroid(platform);
        return androidDeviceService.listFiles(serial, path);
    }

    /**
     * 查询远端单个文件详情。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @param path 远端文件路径
     * @return 文件节点详情
     */
    @GetMapping("/detail")
    public RemoteFileNode fileDetail(
            @PathVariable String platform,
            @PathVariable String serial,
            @RequestParam String path) {
        requireAndroid(platform);
        return androidDeviceService.getFileDetail(serial, path);
    }

    /**
     * 删除远端单个文件；前端负责二次确认，后端仍会限制为文件类型。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @param path 远端文件路径
     * @return 空响应
     */
    @DeleteMapping
    public ResponseEntity<Void> deleteFile(
            @PathVariable String platform,
            @PathVariable String serial,
            @RequestParam String path) {
        requireAndroid(platform);
        androidDeviceService.deleteFile(serial, path);
        return ResponseEntity.noContent().build();
    }

    /**
     * 重命名远端单个文件；目标名只接收单级文件名，不允许携带路径。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @param path 源远端文件路径
     * @param newName 新文件名
     * @return 重命名后的文件节点
     */
    @PostMapping("/rename")
    public RemoteFileNode renameFile(
            @PathVariable String platform,
            @PathVariable String serial,
            @RequestParam String path,
            @RequestParam String newName) {
        requireAndroid(platform);
        return androidDeviceService.renameFile(serial, path, newName);
    }

    /**
     * 在当前目录创建远端文件副本；副本名由后端按现有文件自动递增，避免前端竞态覆盖。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @param path 源远端文件路径
     * @return 新副本文件节点
     */
    @PostMapping("/copy")
    public RemoteFileNode copyFile(
            @PathVariable String platform,
            @PathVariable String serial,
            @RequestParam String path) {
        requireAndroid(platform);
        return androidDeviceService.copyFile(serial, path);
    }

    /**
     * 下载远端单个文件，响应完成后清理服务端临时文件。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @param path 远端文件路径
     * @return 下载响应
     */
    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> downloadFile(
            @PathVariable String platform,
            @PathVariable String serial,
            @RequestParam String path) {
        requireAndroid(platform);
        Path localFile = androidDeviceService.pullFile(serial, path);
        StreamingResponseBody body = outputStream -> {
            try (InputStream inputStream = Files.newInputStream(localFile)) {
                inputStream.transferTo(outputStream);
            } finally {
                Files.deleteIfExists(localFile);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(path))
                .body(body);
    }

    /**
     * 内联预览远端媒体文件；视频由前端播放器控制加载，后端只在请求到达时拉取临时文件。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @param path 远端文件路径
     * @return 预览响应
     */
    @GetMapping("/preview")
    public ResponseEntity<StreamingResponseBody> previewFile(
            @PathVariable String platform,
            @PathVariable String serial,
            @RequestParam String path) {
        requireAndroid(platform);
        Path localFile = androidDeviceService.pullFile(serial, path);
        StreamingResponseBody body = outputStream -> {
            try (InputStream inputStream = Files.newInputStream(localFile)) {
                inputStream.transferTo(outputStream);
            } finally {
                Files.deleteIfExists(localFile);
            }
        };
        return ResponseEntity.ok()
                .contentType(previewMediaType(path))
                .header(HttpHeaders.CONTENT_DISPOSITION, inlineContentDisposition(path))
                .body(body);
    }

    /**
     * 校验平台是否为 Android。
     *
     * @param platform 平台
     */
    private void requireAndroid(String platform) {
        if (!"android".equalsIgnoreCase(platform)) {
            throw new BusinessException("PLATFORM_UNSUPPORTED", "当前平台暂不支持文件管理", HttpStatus.BAD_REQUEST, platform);
        }
    }

    /**
     * 构造下载文件名响应头。
     *
     * @param remotePath 远端路径
     * @return Content-Disposition 响应头
     */
    private String contentDisposition(String remotePath) {
        return ContentDisposition.attachment().filename(pathGuard.fileName(remotePath)).build().toString();
    }

    /**
     * 构造内联预览文件名响应头，避免浏览器按附件下载图片或视频。
     *
     * @param remotePath 远端路径
     * @return Content-Disposition 响应头
     */
    private String inlineContentDisposition(String remotePath) {
        return ContentDisposition.inline().filename(pathGuard.fileName(remotePath)).build().toString();
    }

    /**
     * 根据文件扩展名推断预览类型；未知类型按二进制流返回，前端不会主动预览。
     *
     * @param remotePath 远端路径
     * @return 媒体类型
     */
    private MediaType previewMediaType(String remotePath) {
        String extension = extension(remotePath);
        String contentType = Map.ofEntries(
                Map.entry("jpg", "image/jpeg"),
                Map.entry("jpeg", "image/jpeg"),
                Map.entry("png", "image/png"),
                Map.entry("gif", "image/gif"),
                Map.entry("webp", "image/webp"),
                Map.entry("bmp", "image/bmp"),
                Map.entry("mp4", "video/mp4"),
                Map.entry("webm", "video/webm"),
                Map.entry("mov", "video/quicktime"),
                Map.entry("m4v", "video/x-m4v"),
                Map.entry("3gp", "video/3gpp")).getOrDefault(extension, MediaType.APPLICATION_OCTET_STREAM_VALUE);
        return MediaType.parseMediaType(contentType);
    }

    /**
     * 提取小写扩展名，避免文件名大小写影响预览 Content-Type。
     *
     * @param remotePath 远端路径
     * @return 小写扩展名
     */
    private String extension(String remotePath) {
        String fileName = pathGuard.fileName(remotePath);
        int index = fileName.lastIndexOf('.');
        return index >= 0 ? fileName.substring(index + 1).toLowerCase(Locale.ROOT) : "";
    }
}
