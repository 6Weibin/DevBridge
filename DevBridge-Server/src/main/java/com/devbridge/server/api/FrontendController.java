package com.devbridge.server.api;

import java.time.Duration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * H5 前端资源控制器，显式返回打包进 jar 的静态资源。
 *
 * <p>by AI.Coding</p>
 */
@Controller
public class FrontendController {

    /**
     * 返回前端首页，避免默认静态资源链异常时影响页面加载。
     *
     * @return index.html 静态资源响应
     */
    @GetMapping({"/", "/index.html"})
    public ResponseEntity<Resource> index() {
        return resourceResponse("static/index.html", MediaType.TEXT_HTML, CacheControl.noCache());
    }

    /**
     * 返回 Vite 构建产物，文件名由构建 hash 生成，只允许访问 assets 下的单文件。
     *
     * @param filename 资源文件名
     * @return 静态资源响应
     */
    @GetMapping("/assets/{filename:.+}")
    public ResponseEntity<Resource> asset(@PathVariable String filename) {
        MediaType mediaType = assetMediaType(filename);
        return resourceResponse("static/assets/" + filename, mediaType, CacheControl.maxAge(Duration.ofDays(365)));
    }

    /**
     * 统一构造 classpath 静态资源响应；资源缺失时返回 404，不再回填任何演示内容。
     *
     * @param classpathLocation classpath 下的资源路径
     * @param mediaType 响应媒体类型
     * @param cacheControl 缓存策略
     * @return 静态资源响应
     */
    private ResponseEntity<Resource> resourceResponse(
            String classpathLocation,
            MediaType mediaType,
            CacheControl cacheControl) {
        Resource resource = new ClassPathResource(classpathLocation);
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(cacheControl)
                .body(resource);
    }

    /**
     * 根据构建产物扩展名给出浏览器可识别的媒体类型。
     *
     * @param filename 资源文件名
     * @return 媒体类型
     */
    private MediaType assetMediaType(String filename) {
        if (filename.endsWith(".css")) {
            return MediaType.valueOf("text/css");
        }
        if (filename.endsWith(".js")) {
            return MediaType.valueOf("application/javascript");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
