package com.devbridge.server.ai.tool.audit;

import com.devbridge.server.ai.tool.audit.ToolAuditStore.AuditEvent;
import com.devbridge.server.ai.tool.audit.ToolAuditStore.AuditQuery;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结构化工具审计查询和保留清理接口。
 *
 * <p>by AI.Coding</p>
 */
@RestController
@RequestMapping("/api/ai/audit/tools")
public class ToolAuditController {

    private final ToolAuditStore auditStore;

    /**
     * 注入审计 Store。
     *
     * @param auditStore 审计 Store
     */
    public ToolAuditController(ToolAuditStore auditStore) {
        this.auditStore = auditStore;
    }

    /**
     * 按任务、工具、风险和时间查询审计记录。
     *
     * @param taskId 任务 ID
     * @param toolId 工具 ID
     * @param riskLevel 风险等级
     * @param start 开始时间
     * @param end 结束时间
     * @param limit 最大数量
     * @return 审计记录
     */
    @GetMapping
    public List<AuditEvent> query(
            @RequestParam(defaultValue = "") String taskId,
            @RequestParam(defaultValue = "") String toolId,
            @RequestParam(defaultValue = "") String riskLevel,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
            @RequestParam(defaultValue = "100") int limit) {
        return auditStore.query(new AuditQuery(taskId, toolId, riskLevel, start, end, limit));
    }

    /**
     * 清理超过保留期的整日审计文件。
     *
     * @return 删除数量
     */
    @DeleteMapping("/expired")
    public Map<String, Integer> purgeExpired() {
        return Map.of("deletedFiles", auditStore.purgeExpired());
    }
}
