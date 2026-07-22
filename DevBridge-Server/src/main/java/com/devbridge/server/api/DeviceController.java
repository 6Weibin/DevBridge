package com.devbridge.server.api;

import com.devbridge.server.model.DeviceInfo;
import com.devbridge.server.model.CommandDiagnostic;
import com.devbridge.server.model.DeviceDetail;
import com.devbridge.server.model.RuntimeEnvironment;
import com.devbridge.server.model.ToolStatus;
import com.devbridge.server.model.BusinessException;
import com.devbridge.server.service.AndroidDeviceService;
import com.devbridge.server.service.DeviceService;
import com.devbridge.server.service.IosDeviceService;
import com.devbridge.server.service.RuntimeEnvironmentService;
import com.devbridge.server.service.ToolStatusService;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 设备管理 PoC 接口，提供前端可直接消费的最小 API。
 *
 * <p>by AI.Coding</p>
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class DeviceController {

    private final ToolStatusService toolStatusService;
    private final DeviceService deviceService;
    private final RuntimeEnvironmentService runtimeEnvironmentService;
    private final AndroidDeviceService androidDeviceService;
    private final IosDeviceService iosDeviceService;

    /**
     * 注入设备、工具和日志服务，保持 Controller 只负责接口编排。
     *
     * @param toolStatusService 工具状态服务
     * @param deviceService 设备枚举服务
     * @param runtimeEnvironmentService 运行环境服务
     * @param androidDeviceService Android 设备能力服务
     * @param iosDeviceService iOS 设备能力服务
     */
    public DeviceController(
            ToolStatusService toolStatusService,
            DeviceService deviceService,
            RuntimeEnvironmentService runtimeEnvironmentService,
            AndroidDeviceService androidDeviceService,
            IosDeviceService iosDeviceService) {
        this.toolStatusService = toolStatusService;
        this.deviceService = deviceService;
        this.runtimeEnvironmentService = runtimeEnvironmentService;
        this.androidDeviceService = androidDeviceService;
        this.iosDeviceService = iosDeviceService;
    }

    /**
     * 查询本机移动设备工具状态。
     *
     * @return 工具状态列表
     */
    @GetMapping("/tools/status")
    public List<ToolStatus> listToolStatus() {
        return toolStatusService.listToolStatus();
    }

    /**
     * 查询后端运行环境，便于确认当前会加载 macOS、Windows 还是 Linux 工具目录。
     *
     * @return 运行环境信息
     */
    @GetMapping("/runtime/environment")
    public RuntimeEnvironment runtimeEnvironment() {
        return runtimeEnvironmentService.currentEnvironment();
    }

    /**
     * 枚举当前 USB 连接设备；工具缺失时返回空列表而不是抛出异常。
     *
     * @return 设备信息列表
     */
    @GetMapping("/devices")
    public List<DeviceInfo> listDevices() {
        return deviceService.listDevices();
    }

    /**
     * 查询设备详情；Android 和 iOS 走各自工具链，其他平台保持降级。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @return 设备详情
     */
    @GetMapping("/devices/{platform}/{serial}/detail")
    public DeviceDetail deviceDetail(@PathVariable String platform, @PathVariable String serial) {
        if ("android".equalsIgnoreCase(platform)) {
            return androidDeviceService.getDetail(serial);
        }
        if ("ios".equalsIgnoreCase(platform)) {
            return iosDeviceService.getDetail(serial);
        }
        throw new BusinessException("PLATFORM_UNSUPPORTED", "当前平台暂不支持设备详情", HttpStatus.BAD_REQUEST, platform);
    }

    /**
     * 截取当前设备屏幕并以内联 PNG 返回，响应结束后清理服务端临时文件。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @return PNG 截图响应
     */
    @GetMapping("/devices/{platform}/{serial}/screenshot")
    public ResponseEntity<StreamingResponseBody> screenshot(@PathVariable String platform, @PathVariable String serial) {
        if (!"android".equalsIgnoreCase(platform)) {
            throw new BusinessException("PLATFORM_UNSUPPORTED", "当前平台暂不支持屏幕截图", HttpStatus.BAD_REQUEST, platform);
        }
        Path localFile = androidDeviceService.captureScreenshot(serial);
        StreamingResponseBody body = outputStream -> {
            try (InputStream inputStream = Files.newInputStream(localFile)) {
                inputStream.transferTo(outputStream);
            } finally {
                Files.deleteIfExists(localFile);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, screenshotDisposition(serial))
                .body(body);
    }

    /**
     * 诊断后端进程中的 adb 设备枚举输出，帮助定位真实设备未显示的问题。
     *
     * @return adb devices -l 诊断结果
     */
    @GetMapping("/diagnostics/adb-devices")
    public CommandDiagnostic diagnoseAdbDevices() {
        return deviceService.diagnoseAdbDevices();
    }

    /**
     * 构造截图响应头，内联展示同时保留浏览器另存为文件名。
     *
     * @param serial 设备序列号
     * @return Content-Disposition 响应头
     */
    private String screenshotDisposition(String serial) {
        String safeSerial = serial.replaceAll("[\\\\/:*?\"<>|]", "_");
        return ContentDisposition.inline().filename("screenshot-" + safeSerial + ".png").build().toString();
    }
}
