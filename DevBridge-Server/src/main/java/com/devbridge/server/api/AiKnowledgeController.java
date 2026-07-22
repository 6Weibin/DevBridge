package com.devbridge.server.api;

import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory;
import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory.DeviceSnapshot;
import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory.DeviceSnapshotRequest;
import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory.IncidentRecord;
import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory.IncidentRequest;
import com.devbridge.server.ai.conversation.AiDeviceIncidentMemory.MemoryQuery;
import com.devbridge.server.ai.rag.AiRagBoundary;
import com.devbridge.server.ai.rag.AiRagBoundary.DocumentMetadata;
import com.devbridge.server.ai.rag.AiRagBoundary.ImportRequest;
import com.devbridge.server.ai.rag.AiRagBoundary.SearchRequest;
import com.devbridge.server.ai.rag.AiRagBoundary.SearchResult;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 本地 Memory 与 RAG 管理接口，供本机产品页面和诊断流程维护知识数据。
 *
 * <p>by AI.Coding</p>
 */
@RestController
@RequestMapping("/api/ai/knowledge")
public class AiKnowledgeController {

    private final AiDeviceIncidentMemory memory;
    private final AiRagBoundary rag;

    /**
     * 注入本地 Memory 与 RAG 边界。
     *
     * @param memory 设备与故障 Memory
     * @param rag 本地 RAG
     */
    public AiKnowledgeController(AiDeviceIncidentMemory memory, AiRagBoundary rag) {
        this.memory = memory;
        this.rag = rag;
    }

    /** 保存设备快照。 */
    @PostMapping("/memory/devices")
    public DeviceSnapshot recordSnapshot(@RequestBody DeviceSnapshotRequest request) {
        return memory.recordSnapshot(request);
    }

    /** 查询设备快照。 */
    @GetMapping("/memory/devices")
    public List<DeviceSnapshot> snapshots(
            @RequestParam(defaultValue = "") String deviceId,
            @RequestParam(defaultValue = "20") int limit) {
        return memory.snapshots(deviceId, limit);
    }

    /** 保存故障案例。 */
    @PostMapping("/memory/incidents")
    public IncidentRecord recordIncident(@RequestBody IncidentRequest request) {
        return memory.recordIncident(request);
    }

    /** 按设备、版本和特征查询故障案例。 */
    @GetMapping("/memory/incidents")
    public List<IncidentRecord> incidents(
            @RequestParam(defaultValue = "") String deviceId,
            @RequestParam(defaultValue = "") String osVersion,
            @RequestParam(defaultValue = "") String signature,
            @RequestParam(defaultValue = "20") int limit) {
        return memory.searchIncidents(new MemoryQuery(deviceId, osVersion, signature, limit));
    }

    /** 删除 Memory 记录。 */
    @DeleteMapping("/memory/{id}")
    public Map<String, Boolean> deleteMemory(@PathVariable String id) {
        return Map.of("deleted", memory.delete(id));
    }

    /** 导入或更新本地 RAG 文档。 */
    @PostMapping("/rag/documents")
    public DocumentMetadata importDocument(@RequestBody ImportRequest request) {
        return rag.importDocument(request);
    }

    /** 返回本地 RAG 文档元数据。 */
    @GetMapping("/rag/documents")
    public List<DocumentMetadata> documents() {
        return rag.listDocuments();
    }

    /** 检索本地知识并返回引用。 */
    @PostMapping("/rag/search")
    public SearchResult search(@RequestBody SearchRequest request) {
        return rag.search(request);
    }

    /** 删除本地 RAG 文档。 */
    @DeleteMapping("/rag/documents/{id}")
    public Map<String, Boolean> deleteDocument(@PathVariable String id) {
        return Map.of("deleted", rag.delete(id));
    }

    /** 重建全部本地 RAG Chunk。 */
    @PostMapping("/rag/rebuild")
    public Map<String, Integer> rebuild() {
        return Map.of("rebuiltDocuments", rag.rebuild());
    }
}
