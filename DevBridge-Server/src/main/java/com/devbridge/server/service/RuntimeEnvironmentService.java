package com.devbridge.server.service;

import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.RuntimeEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.springframework.stereotype.Service;

/**
 * 运行环境服务，集中返回后端进程看到的操作系统和工具目录信息。
 *
 * <p>by AI.Coding</p>
 */
@Service
public class RuntimeEnvironmentService {

    private final DevBridgeProperties properties;
    private final ExecutableLocator executableLocator;
    private static final String VERSION_RESOURCE = "devbridge-version.properties";

    /**
     * 注入工具配置和定位器，用于生成当前平台诊断信息。
     *
     * @param properties DevBridge 配置
     * @param executableLocator 工具路径定位器
     */
    public RuntimeEnvironmentService(DevBridgeProperties properties, ExecutableLocator executableLocator) {
        this.properties = properties;
        this.executableLocator = executableLocator;
    }

    /**
     * 获取当前运行环境信息，帮助用户判断 Mac/Windows 工具目录是否匹配。
     *
     * @return 运行环境信息
     */
    public RuntimeEnvironment currentEnvironment() {
        Properties version = loadVersionProperties();
        return new RuntimeEnvironment(
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""),
                executableLocator.currentToolDirectoryName(),
                properties.getBundledToolRoot(),
                System.getProperty("java.version", ""),
                version.getProperty("app.name", "Ai DevBridge"),
                version.getProperty("app.version", ""));
    }

    /**
     * 读取构建脚本生成的版本文件；缺失时返回默认值，避免诊断接口影响主页可用性。
     *
     * @return 版本属性
     */
    private Properties loadVersionProperties() {
        Properties version = new Properties();
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(VERSION_RESOURCE)) {
            if (input != null) {
                version.load(input);
            }
        } catch (IOException ex) {
            // 版本信息只用于展示，读取失败时回退默认值，不能影响设备管理主流程。
            version.setProperty("app.name", "Ai DevBridge");
        }
        return version;
    }
}
