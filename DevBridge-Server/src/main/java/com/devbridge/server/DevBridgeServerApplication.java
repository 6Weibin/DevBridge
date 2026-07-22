package com.devbridge.server;

import com.devbridge.server.config.DevBridgeExecutorProperties;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.config.ControlPlaneSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DevBridge 本机服务启动入口。
 *
 * <p>by AI.Coding</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        DevBridgeProperties.class,
        DevBridgeExecutorProperties.class,
        ControlPlaneSecurityProperties.class
})
public class DevBridgeServerApplication {

    /**
     * 启动 Spring Boot 应用，默认只监听 application.yml 中配置的本机地址。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(DevBridgeServerApplication.class, args);
    }
}
