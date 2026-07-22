package com.devbridge.server.ai.tool.artifact;

import com.devbridge.server.ai.tool.artifact.ToolArtifactStore.ArtifactMetadata;
import com.devbridge.server.ai.tool.artifact.ToolArtifactStore.ArtifactRange;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具 Artifact 查询接口，只暴露元数据和有界范围内容，不返回本机文件路径。
 *
 * <p>by AI.Coding</p>
 */
@RestController
@RequestMapping("/api/ai/artifacts")
public class ToolArtifactController {

    private final ToolArtifactStore artifactStore;

    /**
     * 注入 Artifact Store。
     *
     * @param artifactStore Artifact Store
     */
    public ToolArtifactController(ToolArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    /**
     * 查询 Artifact 元数据。
     *
     * @param artifactId Artifact ID
     * @return 元数据
     */
    @GetMapping("/{artifactId}")
    public ArtifactMetadata metadata(@PathVariable String artifactId) {
        return artifactStore.find(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact 不存在: " + artifactId));
    }

    /**
     * 读取 Artifact 有界字节范围。
     *
     * @param artifactId Artifact ID
     * @param offset 起始偏移
     * @param length 最大读取长度
     * @return 范围响应
     */
    @GetMapping("/{artifactId}/content")
    public ResponseEntity<byte[]> content(
            @PathVariable String artifactId,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "262144") int length) {
        ArtifactRange range = artifactStore.readRange(artifactId, offset, length);
        MediaType mediaType = MediaType.parseMediaType(range.mediaType());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE,
                        "bytes " + range.start() + "-" + Math.max(range.start(), range.endExclusive() - 1)
                                + "/" + range.totalBytes())
                .headers(headers -> headers.set("X-Artifact-Id", range.artifactId()))
                .body(range.bytes());
    }

    /**
     * 返回范围读取限制，便于客户端分块拉取。
     *
     * @return 限制信息
     */
    @GetMapping("/limits")
    public Map<String, Integer> limits() {
        return Map.of("maxRangeBytes", ToolArtifactStore.MAX_RANGE_BYTES);
    }
}
