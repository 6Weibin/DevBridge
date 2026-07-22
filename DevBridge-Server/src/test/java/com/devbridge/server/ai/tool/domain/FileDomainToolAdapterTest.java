package com.devbridge.server.ai.tool.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.devbridge.server.ai.tool.artifact.ToolArtifactStore;
import com.devbridge.server.ai.tool.artifact.ToolArtifactStore.ArtifactWriteRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallIdentity;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallRequest;
import com.devbridge.server.ai.tool.gateway.ToolContract.CallResult;
import com.devbridge.server.ai.tool.gateway.ToolContract.Caller;
import com.devbridge.server.ai.tool.gateway.ToolContract.ExecutionContext;
import com.devbridge.server.ai.tool.gateway.ToolContract.Platform;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskAction;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskDecision;
import com.devbridge.server.ai.tool.gateway.ToolContract.RiskLevel;
import com.devbridge.server.ai.tool.gateway.ToolContract.ToolReference;
import com.devbridge.server.config.DevBridgeProperties;
import com.devbridge.server.model.RemoteFileNode;
import com.devbridge.server.model.RemoteFileType;
import com.devbridge.server.service.AndroidDeviceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * File 领域工具测试，覆盖完整定义、拉取 Artifact 和上传临时文件清理。
 *
 * <p>by AI.Coding</p>
 */
class FileDomainToolAdapterTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * 验证文件工具覆盖浏览、搜索、预览、传输和变更操作。
     */
    @Test
    void definitionsShouldExposeCompleteFileCapabilities() {
        FileDomainToolAdapter adapter = adapter(new FakeAndroidDeviceService());
        var ids = adapter.definitions().stream().map(value -> value.identity().toolId()).toList();
        var delete = adapter.definitions().stream()
                .filter(value -> value.identity().toolId().equals("file.delete")).findFirst().orElseThrow();

        assertThat(ids).contains(
                "file.list", "file.detail.read", "file.search", "file.preview",
                "file.pull", "file.push", "file.delete", "file.rename", "file.copy");
        assertThat(adapter.assess(request("file.delete", pathArguments()), delete).action())
                .isEqualTo(RiskAction.CONFIRM);
    }

    /**
     * 验证远端拉取文件转为 Artifact，结果不暴露本机路径。
     */
    @Test
    void executeShouldPullRemoteFileIntoArtifact() throws Exception {
        FakeAndroidDeviceService service = new FakeAndroidDeviceService();
        FileDomainToolAdapter adapter = adapter(service);
        CallRequest request = request("file.pull", pathArguments());

        CallResult result = adapter.execute(request, definition(adapter, request), allow());

        assertThat(result.payload().artifacts()).hasSize(1);
        assertThat(result.payload().output().has("path")).isFalse();
        assertThat(Files.exists(service.pulledFile)).isFalse();
    }

    /**
     * 验证上传只从 Artifact 物化临时文件，完成后立即清理。
     */
    @Test
    void executeShouldPushArtifactAndDeleteTemporaryFile() {
        FakeAndroidDeviceService service = new FakeAndroidDeviceService();
        ToolArtifactStore store = artifactStore();
        var metadata = store.write(new ArtifactWriteRequest(
                "file", "application/octet-stream", "INTERNAL",
                Instant.now().plusSeconds(3600), false, "gzip"),
                new ByteArrayInputStream(new byte[] {8, 9, 10}));
        FileDomainToolAdapter adapter = adapter(service, store);
        ObjectNode arguments = objectMapper.createObjectNode()
                .put("serial", "device-1")
                .put("artifactId", metadata.identity().artifactId())
                .put("remoteDirectory", "/sdcard")
                .put("targetName", "demo.bin");
        CallRequest request = request("file.push", arguments);

        CallResult result = adapter.execute(request, definition(adapter, request), allow());

        assertThat(result.payload().output().path("path").asText()).isEqualTo("/sdcard/demo.bin");
        assertThat(service.sourceExistedDuringPush).isTrue();
        assertThat(Files.exists(service.pushedSource)).isFalse();
    }

    /**
     * 创建默认 Adapter。
     *
     * @param service Android 服务替身
     * @return Adapter
     */
    private FileDomainToolAdapter adapter(FakeAndroidDeviceService service) {
        return adapter(service, artifactStore());
    }

    /**
     * 创建指定 Artifact Store 的 Adapter。
     *
     * @param service Android 服务替身
     * @param store Artifact Store
     * @return Adapter
     */
    private FileDomainToolAdapter adapter(FakeAndroidDeviceService service, ToolArtifactStore store) {
        return new FileDomainToolAdapter(service, store, properties(), objectMapper);
    }

    /**
     * 创建 Artifact Store。
     *
     * @return Store
     */
    private ToolArtifactStore artifactStore() {
        return new ToolArtifactStore(properties(), objectMapper);
    }

    /**
     * 创建隔离目录配置。
     *
     * @return 配置
     */
    private DevBridgeProperties properties() {
        DevBridgeProperties properties = new DevBridgeProperties();
        properties.setToolArtifactRoot(tempDir.resolve("artifacts").toString());
        properties.setDownloadTempRoot(tempDir.resolve("downloads").toString());
        return properties;
    }

    /**
     * 查找请求对应定义。
     *
     * @param adapter Adapter
     * @param request 请求
     * @return 定义
     */
    private ToolContract.Definition definition(FileDomainToolAdapter adapter, CallRequest request) {
        return adapter.definitions().stream()
                .filter(value -> value.identity().toolId().equals(request.tool().toolId()))
                .findFirst().orElseThrow();
    }

    /**
     * 创建远端路径参数。
     *
     * @return 参数
     */
    private ObjectNode pathArguments() {
        return objectMapper.createObjectNode().put("serial", "device-1").put("path", "/sdcard/demo.txt");
    }

    /**
     * 创建工具请求。
     *
     * @param toolId 工具 ID
     * @param arguments 参数
     * @return 请求
     */
    private CallRequest request(String toolId, ObjectNode arguments) {
        return new CallRequest(
                ToolContract.SCHEMA_VERSION,
                new CallIdentity("conversation", "", "turn", "step", "call", Instant.now()),
                new ToolReference(toolId, ToolContract.SCHEMA_VERSION),
                arguments,
                "digest",
                "key",
                Caller.AGENT,
                new ExecutionContext(Platform.ANDROID, "device-1", "", "", List.of()));
    }

    /**
     * 创建允许执行的风险决策。
     *
     * @return 风险决策
     */
    private RiskDecision allow() {
        return new RiskDecision(RiskLevel.MEDIUM, RiskAction.ALLOW, "test", "TEST", "测试", "", Instant.now());
    }

    /**
     * 不执行真实 ADB 文件操作的服务替身。
     *
     * <p>by AI.Coding</p>
     */
    private class FakeAndroidDeviceService extends AndroidDeviceService {

        private Path pulledFile;
        private Path pushedSource;
        private boolean sourceExistedDuringPush;

        /**
         * 创建空依赖替身。
         */
        FakeAndroidDeviceService() {
            super(null, null, new DevBridgeProperties(), null, null);
        }

        /**
         * 返回固定目录列表。
         *
         * @param serial 序列号
         * @param path 路径
         * @return 节点列表
         */
        @Override
        public List<RemoteFileNode> listFiles(String serial, String path) {
            return List.of(node(path + "/demo.txt"));
        }

        /**
         * 返回固定文件详情。
         *
         * @param serial 序列号
         * @param remotePath 路径
         * @return 节点
         */
        @Override
        public RemoteFileNode getFileDetail(String serial, String remotePath) {
            return node(remotePath);
        }

        /**
         * 返回固定搜索结果。
         *
         * @param serial 序列号
         * @param rootPath 根目录
         * @param query 查询
         * @param maxResults 上限
         * @return 路径
         */
        @Override
        public List<String> searchFiles(String serial, String rootPath, String query, int maxResults) {
            return List.of(rootPath + "/demo.txt");
        }

        /**
         * 返回固定文本预览。
         *
         * @param serial 序列号
         * @param remotePath 路径
         * @return 文本
         */
        @Override
        public String previewTextFile(String serial, String remotePath) {
            return "preview";
        }

        /**
         * 创建拉取临时文件。
         *
         * @param serial 序列号
         * @param remotePath 路径
         * @return 临时文件
         */
        @Override
        public Path pullFile(String serial, String remotePath) {
            try {
                pulledFile = tempDir.resolve("pulled.txt");
                Files.writeString(pulledFile, "content");
                return pulledFile;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        /**
         * 记录上传临时文件并返回目标详情。
         *
         * @param serial 序列号
         * @param localPath 本机路径
         * @param remoteDirectory 远端目录
         * @param targetName 文件名
         * @return 节点
         */
        @Override
        public RemoteFileNode pushFile(
                String serial, Path localPath, String remoteDirectory, String targetName) {
            pushedSource = localPath;
            sourceExistedDuringPush = Files.isRegularFile(localPath);
            return node(remoteDirectory + "/" + targetName);
        }

        /**
         * 删除测试不执行命令。
         *
         * @param serial 序列号
         * @param remotePath 路径
         */
        @Override
        public void deleteFile(String serial, String remotePath) {
        }

        /**
         * 返回重命名节点。
         *
         * @param serial 序列号
         * @param remotePath 路径
         * @param newName 新名称
         * @return 节点
         */
        @Override
        public RemoteFileNode renameFile(String serial, String remotePath, String newName) {
            return node("/sdcard/" + newName);
        }

        /**
         * 返回复制节点。
         *
         * @param serial 序列号
         * @param remotePath 路径
         * @return 节点
         */
        @Override
        public RemoteFileNode copyFile(String serial, String remotePath) {
            return node("/sdcard/demo-copy.txt");
        }

        /**
         * 创建文件节点。
         *
         * @param path 路径
         * @return 节点
         */
        private RemoteFileNode node(String path) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            return new RemoteFileNode(name, path, RemoteFileType.FILE, 7L, "", "rw", "shell", "shell");
        }
    }
}
