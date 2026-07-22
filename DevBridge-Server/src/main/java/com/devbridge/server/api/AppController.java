package com.devbridge.server.api;

import com.devbridge.server.model.AppDetail;
import com.devbridge.server.model.BusinessException;
import com.devbridge.server.model.InstalledApp;
import com.devbridge.server.service.AndroidDeviceService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备应用管理接口，提供 Android 已安装应用列表和详情。
 *
 * <p>by AI.Coding</p>
 */
@RestController
@RequestMapping("/api/devices/{platform}/{serial}/apps")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class AppController {

    private final AndroidDeviceService androidDeviceService;

    /**
     * 注入 Android 应用能力服务。
     *
     * @param androidDeviceService Android 设备能力服务
     */
    public AppController(AndroidDeviceService androidDeviceService) {
        this.androidDeviceService = androidDeviceService;
    }

    /**
     * 查询当前设备已安装应用。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @return 已安装应用列表
     */
    @GetMapping
    public List<InstalledApp> listApps(@PathVariable String platform, @PathVariable String serial) {
        if (!"android".equalsIgnoreCase(platform)) {
            throw new BusinessException("PLATFORM_UNSUPPORTED", "当前平台暂不支持应用管理", HttpStatus.BAD_REQUEST, platform);
        }
        return androidDeviceService.listInstalledApps(serial);
    }

    /**
     * 查询当前设备指定应用详情。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @param packageName 应用包名
     * @return 应用详情
     */
    @GetMapping("/{packageName}/detail")
    public AppDetail getAppDetail(
            @PathVariable String platform,
            @PathVariable String serial,
            @PathVariable String packageName) {
        if (!"android".equalsIgnoreCase(platform)) {
            throw new BusinessException("PLATFORM_UNSUPPORTED", "当前平台暂不支持应用管理", HttpStatus.BAD_REQUEST, platform);
        }
        return androidDeviceService.getAppDetail(serial, packageName);
    }

    /**
     * 卸载当前设备指定应用。
     *
     * @param platform 平台
     * @param serial 设备序列号
     * @param packageName 应用包名
     */
    @DeleteMapping("/{packageName}")
    public void uninstallApp(
            @PathVariable String platform,
            @PathVariable String serial,
            @PathVariable String packageName) {
        if (!"android".equalsIgnoreCase(platform)) {
            throw new BusinessException("PLATFORM_UNSUPPORTED", "当前平台暂不支持应用管理", HttpStatus.BAD_REQUEST, platform);
        }
        androidDeviceService.uninstallApp(serial, packageName);
    }
}
