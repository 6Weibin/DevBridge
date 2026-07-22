package com.devbridge.server.ai.localshell.adapter.rest;

import com.devbridge.server.ai.localshell.execution.LocalShellMcpToolService;
import com.devbridge.server.ai.localshell.model.LocalShellMcpToolDefinition;
import com.devbridge.server.ai.localshell.model.LocalShellMcpToolRequest;
import com.devbridge.server.ai.mcp.model.AdbConfirmationDecisionRequest;
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
 * AI MCP Local Shell 接口，提供工具目录、工具调用、确认和取消能力。
 *
 * <p>by AI.Coding</p>
 */
@RestController
@RequestMapping("/api/ai/mcp/local-shell")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class LocalShellMcpController {

    private final LocalShellMcpToolService toolService;
    private final LegacyToolGatewayFacade gatewayFacade;

    /**
     * 注入 Local Shell MCP 工具服务。
     *
     * @param toolService 工具服务
     */
    public LocalShellMcpController(
            LocalShellMcpToolService toolService,
            LegacyToolGatewayFacade gatewayFacade) {
        this.toolService = toolService;
        this.gatewayFacade = gatewayFacade;
    }

    /**
     * 获取 Local Shell MCP 工具目录。
     *
     * @return 工具定义列表
     */
    @GetMapping("/tools")
    public List<LocalShellMcpToolDefinition> tools() {
        return toolService.listTools();
    }

    /**
     * 调用 Local Shell MCP 工具。
     *
     * @param request 工具请求
     * @return 工具结果
     */
    @PostMapping("/tools/call")
    public AdbMcpToolResult call(@RequestBody LocalShellMcpToolRequest request) {
        return gatewayFacade.callLocal(request);
    }

    /**
     * 流式调用 Local Shell MCP 工具。
     *
     * @param request 工具请求
     * @return SSE 流
     */
    @PostMapping("/tools/call/stream")
    public SseEmitter streamCall(@RequestBody LocalShellMcpToolRequest request) {
        return gatewayFacade.streamLocal(request);
    }

    /**
     * 确认敏感本机命令并执行令牌绑定命令。
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
     * 取消敏感本机命令确认令牌。
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
     * 取消运行中的 Local Shell MCP 工具调用。
     *
     * @param requestId 请求 ID
     * @return 取消结果
     */
    @PostMapping("/tools/running/{requestId}/cancel")
    public AdbMcpToolResult cancelRunning(@PathVariable String requestId) {
        return gatewayFacade.cancel(requestId);
    }
}
