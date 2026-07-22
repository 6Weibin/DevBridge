package com.devbridge.server.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DevBridge PoC 配置项，集中管理命令超时和工具路径。
 *
 * <p>by AI.Coding</p>
 */
@ConfigurationProperties(prefix = "devbridge")
public class DevBridgeProperties {

    private Duration commandTimeout = Duration.ofSeconds(3);
    private Duration logStreamTimeout = Duration.ofMinutes(30);
    private String bundledToolRoot = "tools";
    private String downloadTempRoot = "target/devbridge-downloads";
    private String logCaptureRoot = "target/devbridge-logs";
    private String aiConfigRoot = "target/devbridge-ai";
    private String aiAgentDataRoot = "target/devbridge-ai/agent-data";
    private String toolArtifactRoot = "target/devbridge-ai/artifacts";
    private String toolAuditRoot = "target/devbridge-ai/audit";
    private long storageQuotaBytes = 20L * 1024L * 1024L * 1024L;
    private int aiContextWindowTokens = 32_768;
    private int aiContextReservedTokens = 10_000;
    private Duration aiAgentInputTimeout = Duration.ofMinutes(30);
    private AiFeatures aiFeatures = new AiFeatures();
    private AiMcpAdb aiMcpAdb = new AiMcpAdb();
    private AiMcpLocalShell aiMcpLocalShell = new AiMcpLocalShell();
    private long maxDownloadBytes = 524_288_000L;
    private Map<String, String> tools = new HashMap<>();

    /**
     * 获取外部命令的统一超时时间，避免日志或设备命令长期挂起。
     *
     * @return 命令超时时间
     */
    public Duration getCommandTimeout() {
        return commandTimeout;
    }

    /**
     * 设置外部命令的统一超时时间。
     *
     * @param commandTimeout 命令超时时间
     */
    public void setCommandTimeout(Duration commandTimeout) {
        this.commandTimeout = commandTimeout;
    }

    /**
     * 获取实时日志会话超时时间，避免前端断开后长时间占用 adb 进程。
     *
     * @return 日志会话超时时间
     */
    public Duration getLogStreamTimeout() {
        return logStreamTimeout;
    }

    /**
     * 设置实时日志会话超时时间。
     *
     * @param logStreamTimeout 日志会话超时时间
     */
    public void setLogStreamTimeout(Duration logStreamTimeout) {
        this.logStreamTimeout = logStreamTimeout;
    }

    /**
     * 获取项目内置工具根目录，默认相对后端工程或运行目录下的 tools。
     *
     * @return 内置工具根目录
     */
    public String getBundledToolRoot() {
        return bundledToolRoot;
    }

    /**
     * 设置项目内置工具根目录。
     *
     * @param bundledToolRoot 内置工具根目录
     */
    public void setBundledToolRoot(String bundledToolRoot) {
        this.bundledToolRoot = bundledToolRoot;
    }

    /**
     * 获取下载临时目录，文件传输完成或失败后会清理该目录下的任务文件。
     *
     * @return 下载临时目录
     */
    public String getDownloadTempRoot() {
        return downloadTempRoot;
    }

    /**
     * 设置下载临时目录。
     *
     * @param downloadTempRoot 下载临时目录
     */
    public void setDownloadTempRoot(String downloadTempRoot) {
        this.downloadTempRoot = downloadTempRoot;
    }

    /**
     * 获取实时日志采集落盘根目录；目录下按日期、平台和设备型号继续分层。
     *
     * @return 实时日志采集根目录
     */
    public String getLogCaptureRoot() {
        return logCaptureRoot;
    }

    /**
     * 设置实时日志采集落盘根目录。
     *
     * @param logCaptureRoot 实时日志采集根目录
     */
    public void setLogCaptureRoot(String logCaptureRoot) {
        this.logCaptureRoot = logCaptureRoot;
    }

    /**
     * 获取 AI 配置根目录；该目录存放模型配置和本机密钥材料，避免写入前端或应用资源目录。
     *
     * @return AI 配置根目录
     */
    public String getAiConfigRoot() {
        return aiConfigRoot;
    }

    /**
     * 设置 AI 配置根目录。
     *
     * @param aiConfigRoot AI 配置根目录
     */
    public void setAiConfigRoot(String aiConfigRoot) {
        this.aiConfigRoot = aiConfigRoot;
    }

    /**
     * 获取 Agent Task、Checkpoint 和事件数据根目录。
     *
     * @return Agent 数据根目录
     */
    public String getAiAgentDataRoot() {
        return aiAgentDataRoot;
    }

    /**
     * 设置 Agent Task、Checkpoint 和事件数据根目录。
     *
     * @param aiAgentDataRoot Agent 数据根目录
     */
    public void setAiAgentDataRoot(String aiAgentDataRoot) {
        this.aiAgentDataRoot = aiAgentDataRoot;
    }

    /**
     * 获取工具 Artifact 分段文件根目录。
     *
     * @return Artifact 根目录
     */
    public String getToolArtifactRoot() {
        return toolArtifactRoot;
    }

    /**
     * 设置工具 Artifact 分段文件根目录。
     *
     * @param toolArtifactRoot Artifact 根目录
     */
    public void setToolArtifactRoot(String toolArtifactRoot) {
        this.toolArtifactRoot = toolArtifactRoot;
    }

    /**
     * 获取结构化工具审计根目录。
     *
     * @return 审计根目录
     */
    public String getToolAuditRoot() {
        return toolAuditRoot;
    }

    /**
     * 设置结构化工具审计根目录。
     *
     * @param toolAuditRoot 审计根目录
     */
    public void setToolAuditRoot(String toolAuditRoot) {
        this.toolAuditRoot = toolAuditRoot;
    }

    /**
     * 获取 AI 与工具数据统一磁盘配额。
     *
     * @return 配额字节数
     */
    public long getStorageQuotaBytes() {
        return storageQuotaBytes;
    }

    /**
     * 设置 AI 与工具数据统一磁盘配额。
     *
     * @param storageQuotaBytes 配额字节数
     */
    public void setStorageQuotaBytes(long storageQuotaBytes) {
        this.storageQuotaBytes = storageQuotaBytes;
    }

    /**
     * 获取当前模型上下文窗口；M3-05 接入模型能力注册前允许部署配置覆盖默认值。
     *
     * @return 上下文窗口 Token 数
     */
    public int getAiContextWindowTokens() {
        return aiContextWindowTokens;
    }

    /**
     * 设置当前模型上下文窗口。
     *
     * @param aiContextWindowTokens 上下文窗口 Token 数
     */
    public void setAiContextWindowTokens(int aiContextWindowTokens) {
        this.aiContextWindowTokens = aiContextWindowTokens;
    }

    /**
     * 获取系统提示词、工具 Schema 和运行控制信息预留 Token。
     *
     * @return 预留 Token 数
     */
    public int getAiContextReservedTokens() {
        return aiContextReservedTokens;
    }

    /**
     * 设置系统与工具上下文预留 Token。
     *
     * @param aiContextReservedTokens 预留 Token 数
     */
    public void setAiContextReservedTokens(int aiContextReservedTokens) {
        this.aiContextReservedTokens = aiContextReservedTokens;
    }

    /** 获取 Agent 等待用户输入的最大时长。 */
    public Duration getAiAgentInputTimeout() {
        return aiAgentInputTimeout;
    }

    /** 设置 Agent 等待用户输入的最大时长。 */
    public void setAiAgentInputTimeout(Duration aiAgentInputTimeout) {
        this.aiAgentInputTimeout = aiAgentInputTimeout;
    }

    /**
     * 获取 AI 产品化能力开关。
     *
     * @return AI 功能开关
     */
    public AiFeatures getAiFeatures() {
        return aiFeatures;
    }

    /**
     * 设置 AI 产品化能力开关。
     *
     * @param aiFeatures AI 功能开关
     */
    public void setAiFeatures(AiFeatures aiFeatures) {
        this.aiFeatures = aiFeatures == null ? new AiFeatures() : aiFeatures;
    }

    /**
     * 获取 AI ADB MCP 配置。
     *
     * @return AI ADB MCP 配置
     */
    public AiMcpAdb getAiMcpAdb() {
        return aiMcpAdb;
    }

    /**
     * 设置 AI ADB MCP 配置。
     *
     * @param aiMcpAdb AI ADB MCP 配置
     */
    public void setAiMcpAdb(AiMcpAdb aiMcpAdb) {
        this.aiMcpAdb = aiMcpAdb == null ? new AiMcpAdb() : aiMcpAdb;
    }

    /**
     * 获取 AI 本机 Shell MCP 配置。
     *
     * @return AI 本机 Shell MCP 配置
     */
    public AiMcpLocalShell getAiMcpLocalShell() {
        return aiMcpLocalShell;
    }

    /**
     * 设置 AI 本机 Shell MCP 配置。
     *
     * @param aiMcpLocalShell AI 本机 Shell MCP 配置
     */
    public void setAiMcpLocalShell(AiMcpLocalShell aiMcpLocalShell) {
        this.aiMcpLocalShell = aiMcpLocalShell == null ? new AiMcpLocalShell() : aiMcpLocalShell;
    }

    /**
     * 获取单文件下载大小上限。
     *
     * @return 单文件下载大小上限
     */
    public long getMaxDownloadBytes() {
        return maxDownloadBytes;
    }

    /**
     * 设置单文件下载大小上限。
     *
     * @param maxDownloadBytes 单文件下载大小上限
     */
    public void setMaxDownloadBytes(long maxDownloadBytes) {
        this.maxDownloadBytes = maxDownloadBytes;
    }

    /**
     * 获取用户配置的工具路径映射。
     *
     * @return 工具路径映射
     */
    public Map<String, String> getTools() {
        return tools;
    }

    /**
     * 设置用户配置的工具路径映射。
     *
     * @param tools 工具路径映射
     */
    public void setTools(Map<String, String> tools) {
        this.tools = tools == null ? new HashMap<>() : tools;
    }

    /**
     * AI ADB MCP 配置项，控制工具开关、确认有效期和默认输出限制。
     *
     * <p>by AI.Coding</p>
     */
    public static class AiMcpAdb {

        private boolean enabled = true;
        private Duration confirmationTtl = Duration.ofMinutes(2);

        /**
         * 判断 ADB MCP 是否启用。
         *
         * @return 启用返回 true
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置 ADB MCP 是否启用。
         *
         * @param enabled 是否启用
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 获取敏感操作确认令牌有效期。
         *
         * @return 确认有效期
         */
        public Duration getConfirmationTtl() {
            return confirmationTtl;
        }

        /**
         * 设置敏感操作确认令牌有效期。
         *
         * @param confirmationTtl 确认有效期
         */
        public void setConfirmationTtl(Duration confirmationTtl) {
            this.confirmationTtl = confirmationTtl;
        }
    }

    /**
     * AI 本机 Shell MCP 配置项，控制工具开关、工作目录、确认有效期和输出限制。
     *
     * <p>by AI.Coding</p>
     */
    public static class AiMcpLocalShell {

        private boolean enabled = true;
        private Duration confirmationTtl = Duration.ofMinutes(2);
        private Duration defaultTimeout = Duration.ofSeconds(10);
        private Duration maxTimeout = Duration.ofSeconds(120);
        private int maxOutputChars = 60_000;
        private int maxOutputLines = 1_200;
        private boolean requireConfirmationForShellMode = true;
        private java.util.List<String> allowedWorkingDirectories = new java.util.ArrayList<>();
        private java.util.List<String> deniedWorkingDirectories = new java.util.ArrayList<>();
        private java.util.List<String> allowedEnvironmentKeys = new java.util.ArrayList<>(java.util.List.of(
                "PATH",
                "JAVA_HOME",
                "ANDROID_HOME",
                "ANDROID_SDK_ROOT",
                "LANG",
                "LC_ALL"));

        /**
         * 判断 Local Shell MCP 是否启用。
         *
         * @return 启用返回 true
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置 Local Shell MCP 是否启用。
         *
         * @param enabled 是否启用
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 获取敏感本机命令确认令牌有效期。
         *
         * @return 确认有效期
         */
        public Duration getConfirmationTtl() {
            return confirmationTtl;
        }

        /**
         * 设置敏感本机命令确认令牌有效期。
         *
         * @param confirmationTtl 确认有效期
         */
        public void setConfirmationTtl(Duration confirmationTtl) {
            this.confirmationTtl = confirmationTtl;
        }

        /**
         * 获取默认命令超时时间。
         *
         * @return 默认超时时间
         */
        public Duration getDefaultTimeout() {
            return defaultTimeout;
        }

        /**
         * 设置默认命令超时时间。
         *
         * @param defaultTimeout 默认超时时间
         */
        public void setDefaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
        }

        /**
         * 获取最大命令超时时间。
         *
         * @return 最大超时时间
         */
        public Duration getMaxTimeout() {
            return maxTimeout;
        }

        /**
         * 设置最大命令超时时间。
         *
         * @param maxTimeout 最大超时时间
         */
        public void setMaxTimeout(Duration maxTimeout) {
            this.maxTimeout = maxTimeout;
        }

        /**
         * 获取最大输出字符数。
         *
         * @return 最大输出字符数
         */
        public int getMaxOutputChars() {
            return maxOutputChars;
        }

        /**
         * 设置最大输出字符数。
         *
         * @param maxOutputChars 最大输出字符数
         */
        public void setMaxOutputChars(int maxOutputChars) {
            this.maxOutputChars = maxOutputChars;
        }

        /**
         * 获取最大输出行数。
         *
         * @return 最大输出行数
         */
        public int getMaxOutputLines() {
            return maxOutputLines;
        }

        /**
         * 设置最大输出行数。
         *
         * @param maxOutputLines 最大输出行数
         */
        public void setMaxOutputLines(int maxOutputLines) {
            this.maxOutputLines = maxOutputLines;
        }

        /**
         * 判断 Shell 模式是否默认需要确认。
         *
         * @return 需要确认返回 true
         */
        public boolean isRequireConfirmationForShellMode() {
            return requireConfirmationForShellMode;
        }

        /**
         * 设置 Shell 模式是否默认需要确认。
         *
         * @param requireConfirmationForShellMode 是否需要确认
         */
        public void setRequireConfirmationForShellMode(boolean requireConfirmationForShellMode) {
            this.requireConfirmationForShellMode = requireConfirmationForShellMode;
        }

        /**
         * 获取允许工作目录列表；为空时默认使用用户主目录。
         *
         * @return 允许工作目录列表
         */
        public java.util.List<String> getAllowedWorkingDirectories() {
            return allowedWorkingDirectories;
        }

        /**
         * 设置允许工作目录列表。
         *
         * @param allowedWorkingDirectories 允许工作目录列表
         */
        public void setAllowedWorkingDirectories(java.util.List<String> allowedWorkingDirectories) {
            this.allowedWorkingDirectories = allowedWorkingDirectories == null ? new java.util.ArrayList<>() : allowedWorkingDirectories;
        }

        /**
         * 获取拒绝工作目录列表。
         *
         * @return 拒绝工作目录列表
         */
        public java.util.List<String> getDeniedWorkingDirectories() {
            return deniedWorkingDirectories;
        }

        /**
         * 设置拒绝工作目录列表。
         *
         * @param deniedWorkingDirectories 拒绝工作目录列表
         */
        public void setDeniedWorkingDirectories(java.util.List<String> deniedWorkingDirectories) {
            this.deniedWorkingDirectories = deniedWorkingDirectories == null ? new java.util.ArrayList<>() : deniedWorkingDirectories;
        }

        /**
         * 获取允许传入命令的环境变量 Key。
         *
         * @return 环境变量 Key 列表
         */
        public java.util.List<String> getAllowedEnvironmentKeys() {
            return allowedEnvironmentKeys;
        }

        /**
         * 设置允许传入命令的环境变量 Key。
         *
         * @param allowedEnvironmentKeys 环境变量 Key 列表
         */
        public void setAllowedEnvironmentKeys(java.util.List<String> allowedEnvironmentKeys) {
            this.allowedEnvironmentKeys = allowedEnvironmentKeys == null ? new java.util.ArrayList<>() : allowedEnvironmentKeys;
        }
    }

    /**
     * M4 产品化能力灰度开关，关闭后保留普通对话和既有设备管理链路。
     *
     * <p>by AI.Coding</p>
     */
    public static class AiFeatures {

        private boolean agentRuntimeEnabled = true;
        private boolean multiAgentEnabled = true;
        private boolean modelFallbackEnabled = true;
        private boolean traceEnabled = true;
        private boolean localDataMaintenanceEnabled = true;

        /** 判断后端 Agent Runtime 主链路是否启用。 */
        public boolean isAgentRuntimeEnabled() {
            return agentRuntimeEnabled;
        }

        /** 设置后端 Agent Runtime 主链路开关。 */
        public void setAgentRuntimeEnabled(boolean agentRuntimeEnabled) {
            this.agentRuntimeEnabled = agentRuntimeEnabled;
        }

        /** 判断多 Agent 工具是否启用。 */
        public boolean isMultiAgentEnabled() {
            return multiAgentEnabled;
        }

        /** 设置多 Agent 工具开关。 */
        public void setMultiAgentEnabled(boolean multiAgentEnabled) {
            this.multiAgentEnabled = multiAgentEnabled;
        }

        /** 判断模型安全降级是否启用。 */
        public boolean isModelFallbackEnabled() {
            return modelFallbackEnabled;
        }

        /** 设置模型安全降级开关。 */
        public void setModelFallbackEnabled(boolean modelFallbackEnabled) {
            this.modelFallbackEnabled = modelFallbackEnabled;
        }

        /** 判断 Agent Trace 查询是否启用。 */
        public boolean isTraceEnabled() {
            return traceEnabled;
        }

        /** 设置 Agent Trace 查询开关。 */
        public void setTraceEnabled(boolean traceEnabled) {
            this.traceEnabled = traceEnabled;
        }

        /** 判断本地数据维护接口是否启用。 */
        public boolean isLocalDataMaintenanceEnabled() {
            return localDataMaintenanceEnabled;
        }

        /** 设置本地数据维护接口开关。 */
        public void setLocalDataMaintenanceEnabled(boolean localDataMaintenanceEnabled) {
            this.localDataMaintenanceEnabled = localDataMaintenanceEnabled;
        }
    }
}
