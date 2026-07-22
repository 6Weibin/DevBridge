package com.devbridge.server.ai.mcp.adapter.rest;

import com.devbridge.server.ai.mcp.execution.AdbMcpToolService;
import com.devbridge.server.ai.mcp.model.AdbConfirmationDecisionRequest;
import com.devbridge.server.ai.mcp.model.AdbMcpToolDefinition;
import com.devbridge.server.ai.mcp.model.AdbMcpToolRequest;
import com.devbridge.server.ai.mcp.model.AdbMcpToolResult;
import com.devbridge.server.ai.tool.gateway.LegacyToolGatewayFacade;
import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI MCP ADB 接口，提供工具目录、工具调用、确认和取消能力。
 *
 * <p>by AI.Coding</p>
 */
@RestController
@RequestMapping("/api/ai/mcp/adb")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class AiMcpController {

    private final AdbMcpToolService toolService;
    private final LegacyToolGatewayFacade gatewayFacade;

    /**
     * 注入 ADB MCP 工具服务。
     *
     * @param toolService 工具服务
     */
    public AiMcpController(
            AdbMcpToolService toolService,
            LegacyToolGatewayFacade gatewayFacade) {
        this.toolService = toolService;
        this.gatewayFacade = gatewayFacade;
    }

    /**
     * 获取 ADB MCP 工具目录。
     *
     * @return 工具定义列表
     */
    @GetMapping("/tools")
    public List<AdbMcpToolDefinition> tools() {
        return toolService.listTools();
    }

    /**
     * 调用 ADB MCP 工具。
     *
     * @param request 工具请求
     * @return 工具结果
     */
    @PostMapping("/tools/call")
    public AdbMcpToolResult call(@RequestBody AdbMcpToolRequest request) {
        return gatewayFacade.callAdb(request);
    }

    /**
     * 流式调用 ADB MCP 工具。
     *
     * @param request 工具请求
     * @return SSE 流
     */
    @PostMapping("/tools/call/stream")
    public SseEmitter streamCall(@RequestBody AdbMcpToolRequest request) {
        return gatewayFacade.streamAdb(request);
    }

    /**
     * 确认敏感操作并执行令牌绑定的 ADB 命令。
     *
     * @param token 确认令牌
     * @param request 确认请求
     * @return 工具结果
     */
    @PostMapping("/confirmations/{token}/approve")
    public AdbMcpToolResult approve(@PathVariable String token, @RequestBody AdbConfirmationDecisionRequest request) {
        return gatewayFacade.approve(token, request.conversationId());
    }

    /**
     * 取消敏感操作确认令牌。
     *
     * @param token 确认令牌
     * @param request 取消请求
     * @return 取消结果
     */
    @PostMapping("/confirmations/{token}/cancel")
    public AdbMcpToolResult cancelConfirmation(@PathVariable String token, @RequestBody AdbConfirmationDecisionRequest request) {
        return gatewayFacade.reject(token, request.conversationId());
    }

    /**
     * 取消运行中的 ADB MCP 工具调用。
     *
     * @param requestId 请求 ID
     * @return 取消结果
     */
    @PostMapping("/tools/running/{requestId}/cancel")
    public AdbMcpToolResult cancelRunning(@PathVariable String requestId) {
        return gatewayFacade.cancel(requestId);
    }
}
