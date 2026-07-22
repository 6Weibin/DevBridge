# Task/Checkpoint 本地文件格式

## 1. 文档信息

| 项目 | 内容 |
|------|------|
| 任务 | M0-04：定义 Task/Checkpoint 文件格式 |
| 格式版本 | 1.0.0 |
| 存储方式 | 本地文件；不引入数据库 |
| 目标 | 高效追加、原子恢复、损坏隔离、分页读取、压缩和版本迁移 |

## 2. 设计原则

1. 单个 Task 是独立故障域，一个任务损坏不能阻塞其他任务或应用启动。
2. 运行事实使用追加写，当前快照使用原子替换，禁止频繁重写完整历史。
3. 热数据保持可快速追加和范围读取，封存数据分段压缩。
4. 所有持久格式包含主版本、校验和和创建时间。
5. 恢复只接受最后一个完整、校验通过且状态一致的 Checkpoint。
6. 完整工具输出、日志和报告不写入 Task JSON，使用 Artifact 引用。
7. 1M Token 是可管理的数据规模，不代表一次加载到内存、前端或模型上下文。

## 3. 存储目录

存储根目录由应用数据目录决定，不允许使用工程目录或浏览器 `localStorage`。逻辑布局：

```text
agent-data/
  manifest.json
  indexes/
    tasks-000001.ndjson
    tasks-current.json
  tasks/
    01/
      9a/
        <taskId>/
          task.json
          plan.json
          events/
            events-000001.ndjson
            events-000002.ndjson.gz
          checkpoints/
            checkpoint-0000000000000042.json
            checkpoint-current.json
          confirmations/
            <confirmationId>.json
          artifacts/
            manifest.ndjson
          recovery.json
  quarantine/
  tmp/
  migrations/
```

任务目录按 `taskId` 前四个十六进制字符两级分片，避免单目录包含数千文件。文件中不得保存依赖该绝对目录的路径。

## 4. 根清单与索引

### 4.1 manifest.json

```json
{
  "format": "ai-devbridge-agent-store",
  "schemaVersion": "1.0.0",
  "storeId": "019abcde-0000-7000-8000-000000000001",
  "createdAt": "2026-07-14T08:00:00Z",
  "lastOpenedByVersion": "1.0.0",
  "migrationState": "READY",
  "checksumAlgorithm": "SHA-256"
}
```

### 4.2 任务索引

- 索引是可重建的查询加速数据，不是任务真相来源。
- 活跃索引使用 NDJSON 追加记录任务摘要变更；达到大小阈值后封存并压缩。
- `tasks-current.json` 原子指向当前段、高水位和分页游标。
- 列表查询只读取索引，不扫描每个任务目录。
- 索引记录至少包含：`taskId`、`conversationId`、标题、状态、更新时间、最后事件序号、风险摘要、归档状态和目录分片。
- 索引损坏时允许后台从 `task.json` 重建，不影响单任务恢复。

该布局必须支持至少 1000 个任务的分页列表，不要求一次把全部任务或历史正文加载到内存。

## 5. 文件通用 Envelope

除 NDJSON 单条记录外，每个 JSON 文件使用统一 Envelope：

```json
{
  "format": "agent-task",
  "schemaVersion": "1.0.0",
  "recordVersion": 17,
  "createdAt": "2026-07-14T08:00:00Z",
  "updatedAt": "2026-07-14T08:05:00Z",
  "payload": {},
  "checksum": {
    "algorithm": "SHA-256",
    "canonicalization": "JCS",
    "value": "hex-encoded-digest"
  }
}
```

校验和只覆盖 Envelope 中除 `checksum` 外的规范化 JSON。敏感文件需要加密时，校验和覆盖密文 Envelope，解密后还需验证认证标签。

## 6. Task 文件

### 6.1 task.json

`task.json` 是任务当前元数据快照，通过原子替换更新。

| 字段 | 类型 | 说明 |
|------|------|------|
| `taskId` | string | 任务标识 |
| `conversationId` | string | 会话标识 |
| `activeTurnId` | string | 当前 Turn，可空 |
| `title` | string | 有界标题 |
| `goalSummary` | string | 脱敏且有界的目标摘要 |
| `state` | AgentTaskState | 当前状态 |
| `stateReason` | object | 原因码和摘要 |
| `taskVersion` | integer | 乐观锁版本 |
| `planVersion` | integer | 当前计划版本 |
| `lastEventSequence` | string | 已持久化事件高水位 |
| `currentStepId` | string | 当前步骤，可空 |
| `pendingConfirmationId` | string | 等待确认时必填 |
| `pendingInput` | object | 等待输入时的结构化描述 |
| `checkpointId` | string | 当前有效 Checkpoint |
| `budget` | object | 配额和实际使用摘要 |
| `result` | object | 终态输出/Artifact 引用 |
| `error` | object | 终态错误摘要 |
| `retention` | object | 保留、归档和删除策略 |

任务元数据不得嵌入完整对话、模型全文、stdout/stderr、日志或文件正文。

### 6.2 plan.json

`plan.json` 保存当前计划和历史步骤事实：

```json
{
  "planId": "019abcde-aaaa-7000-8000-000000000001",
  "planVersion": 3,
  "taskId": "019abcde-bbbb-7000-8000-000000000001",
  "status": "ACTIVE",
  "steps": [
    {
      "stepId": "019abcde-cccc-7000-8000-000000000001",
      "ordinal": 10,
      "type": "TOOL",
      "title": "查询设备状态",
      "status": "COMPLETED",
      "dependsOn": [],
      "toolCallIds": ["019abcde-dddd-7000-8000-000000000001"],
      "preconditions": [],
      "postconditions": [],
      "idempotencyKey": "sha256:...",
      "retryPolicy": { "maxAttempts": 1 },
      "compensation": null,
      "resultReference": "event:42"
    }
  ]
}
```

计划修订不能删除已执行步骤。废弃步骤标记为 `SUPERSEDED`，并记录替代步骤和修订事件。

## 7. Event 文件

### 7.1 记录格式

事件使用 UTF-8 NDJSON，一行一个 Agent Event。每行外层增加完整性字段：

```json
{"sequence":"42","length":1240,"crc32c":"8af13c20","event":{}}
```

`length` 是规范化 `event` 的 UTF-8 字节数，`crc32c` 用于快速检测尾部撕裂；封存段另有 SHA-256 段清单。

### 7.2 分段与压缩

- 活跃段保持未压缩以支持追加，默认达到 16 MiB 或 10000 条事件后滚动。
- 封存段关闭后生成段清单和 SHA-256，再使用 GZIP 压缩为 `.ndjson.gz`。
- GZIP 是 JDK 可直接支持的基线；后续可引入 Zstandard，但必须通过格式能力标识，不能静默改变。
- 事件分段按首序号命名，段之间不得重叠或缺号。
- 高频工具正文不写成大量事件，使用分块 Artifact 和少量进度事件。

### 7.3 持久化顺序

1. 在任务级写锁内确定下一序号和新任务版本。
2. 追加事件记录并按持久级别执行 flush；关键事件执行文件同步。
3. 原子更新 `task.json`/Checkpoint 指针。
4. 更新可重建索引。
5. 提交后再向订阅者发布。

关键事件包括确认要求/决策、工具执行意图、工具最终结果、Checkpoint、状态变更和终态。进度事件可批量同步，但不能越过相关关键事件。

## 8. Checkpoint 文件

### 8.1 Checkpoint 内容

Checkpoint 是恢复任务的完整控制面快照，不复制大型事件正文：

| 字段 | 说明 |
|------|------|
| `checkpointId` | 稳定 ID，建议包含对应事件序号 |
| `taskId`、`taskVersion` | 所属任务和版本 |
| `eventSequence` | 快照包含的最后事件 |
| `taskState` | 当前任务状态 |
| `activeTurnId` | 当前 Turn |
| `planVersion`、`currentStepId` | 计划和推进位置 |
| `completedStepIds` | 已完成步骤集合 |
| `toolCallStates` | 调用 ID、状态、幂等键、结果引用和副作用状态 |
| `pendingConfirmation` | 等待确认的完整绑定信息 |
| `pendingInput` | 等待输入字段和校验规则 |
| `resourceClaims` | 恢复时需要重新获取的资源键，不恢复旧租约 |
| `budgetUsage` | 已消耗模型、工具、时间和字节预算 |
| `modelContinuation` | 可恢复阶段和模型输出引用，不保存 Provider 隐藏状态 |
| `artifactReferences` | 任务依赖的 Artifact |
| `recoveryPolicy` | 自动恢复、等待用户或失败的策略 |

### 8.2 保存时机

必须在以下边界保存 Checkpoint：

1. 计划生成或修订后。
2. 每个有副作用工具执行意图持久化后、实际执行前。
3. 工具结果和后置验证持久化后。
4. 进入等待确认、等待输入或暂停前。
5. Provider 工具请求确定后和最终总结前。
6. 受控停机前。
7. 任务进入终态前的最后一致状态。

### 8.3 current 指针

`checkpoint-current.json` 只保存当前 Checkpoint 文件名、任务版本、事件序号和 SHA-256，通过原子替换更新。恢复时不能只信任文件名，必须再次校验目标文件。

## 9. Confirmation 文件

每个确认记录独立保存，至少包含：

- `confirmationId`、`taskId`、`turnId`、`stepId`、`toolCallId`。
- 工具 ID、规范化参数摘要、风险等级、影响摘要。
- 请求时间、过期时间、状态和一次性随机数摘要。
- 会话实例、决策主体、决策时间和拒绝原因。
- 数据外发类型与 Provider 摘要，可选。
- 关联的创建、决策和失效事件序号。

不保存可直接重放的明文确认令牌，只保存安全摘要。确认被接受、拒绝、过期或失效后不可改回待决状态。

## 10. Artifact 元数据

任务目录只保存 Artifact 清单引用，实际内容由统一 Artifact Store 管理。清单记录：

- `artifactId`
- 所属任务、步骤和工具调用
- 类型、媒体类型、压缩算法和大小
- SHA-256、分块信息和可范围读取能力
- 数据分类、脱敏状态、加密状态
- 创建时间、保留期和删除状态

完整输出不得同时复制进 Event、Checkpoint 和 Task 文件。模型和 UI 按需读取片段，读取行为受权限、预算和审计控制。

## 11. 原子写入与并发

### 11.1 快照原子替换

1. 在目标同目录创建唯一临时文件。
2. 写入完整 Envelope 和校验和。
3. flush 并同步文件内容。
4. 使用原子 rename/replace 替换目标文件。
5. 平台支持时同步父目录元数据。
6. 启动时清理超时临时文件，不把临时文件当作有效快照。

### 11.2 单写者模型

- 一个 Task 同时只能有一个持久化写者。
- 进程内使用任务级锁和乐观版本；跨进程通过 Store 实例锁防止两个后端同时打开同一写目录。
- 读取使用不可变封存段和原子快照，不阻塞长时间工具执行。
- 不在持有文件写锁时调用模型、工具、网络或 UI。

## 12. 崩溃恢复

启动恢复顺序：

1. 校验根 `manifest.json` 和格式主版本。
2. 检测 Store 锁和上次非正常关闭标记。
3. 加载任务索引；损坏时标记后台重建。
4. 对非终态任务读取 `task.json` 和 `checkpoint-current.json`。
5. 校验 Checkpoint 校验和、任务版本和事件高水位。
6. 扫描最后活跃事件段，逐行验证长度、CRC 和序号。
7. 仅当损坏位于文件尾部时截断到最后完整换行；保留恢复报告。
8. 重建任务投影并验证状态约束。
9. 根据状态恢复等待、重新入队或标记失败。
10. 写入 `recovery.json` 和恢复事件。

恢复过程中禁止自动执行写工具。必须先完成幂等结果检查和外部状态重新校验。

## 13. 损坏隔离

| 损坏情况 | 处理 |
|----------|------|
| 活跃段尾部不完整 | 截断至最后完整记录，记录恢复报告 |
| 封存段校验失败 | 隔离该段和所属任务，禁止静默跳过事件继续执行 |
| `task.json` 损坏但 Checkpoint 完整 | 从 Checkpoint 和事件重建快照 |
| Checkpoint 损坏 | 回退到上一个完整 Checkpoint并回放事件 |
| 确认记录损坏 | 使确认失效，任务停止自动恢复并要求重新决策 |
| Artifact 损坏 | 标记证据不可用；写操作结果不因此自动重放 |
| 无法建立一致状态 | 将任务移动/标记到 `quarantine`，应用其余任务继续启动 |

所有隔离操作保留原文件，禁止未经用户同意直接删除损坏证据。

## 14. 版本迁移

1. 启动先读取主版本，不支持的高版本必须只读打开或拒绝启动 Agent Store。
2. 迁移前创建清单和元数据备份，检查可用磁盘空间。
3. 迁移以任务为单位写入新目录，校验成功后原子切换 Store 清单。
4. 单任务迁移失败不覆盖旧任务，记录失败并允许其他任务继续。
5. 迁移过程可中断并从 `migrations/` 状态文件恢复。
6. 旧格式只在全部校验完成并达到可配置保留期后清理。
7. Schema 新增可选字段使用读取默认值；语义变化必须提升主版本或提供显式转换。

## 15. 安全与隐私边界

- API Key、Authorization Header、明文确认令牌、系统密钥不得写入 Task Store。
- 用户消息和工具摘要按数据分类脱敏；必要原文由 Conversation/Artifact Store 独立保存和授权。
- 高敏 Checkpoint、确认详情和 Artifact 支持 AES-GCM 等认证加密；密钥不与数据文件同目录管理，优先使用操作系统密钥环。
- 文件和目录使用当前用户最小权限；启动时检查权限过宽并告警或拒绝加载敏感数据。
- 路径通过 `artifactId`、`taskId` 等逻辑标识访问，API 不接收任意本地存储相对路径。
- 删除任务采用标记、引用检查和受控清理，审计保留规则优先于普通历史清理。

## 16. 容量与性能约束

1. 任务列表支持至少 1000 个任务分页，单页默认不超过 100 条。
2. 单任务事件和 Artifact 可承载相当于 1M Token 以上的历史证据，但上下文按预算、摘要和范围读取组装。
3. 打开历史任务时只加载元数据、可见窗口和所需事件段，禁止读取全部 Artifact。
4. 活跃写入使用顺序追加，不对每个流式 Delta 重写 `task.json` 或 Checkpoint。
5. Checkpoint 仅保存控制状态和引用，目标压缩前大小应保持有界。
6. 封存段后台压缩，不阻塞当前任务关键路径。
7. Storage Manager 后续统一管理 80%、95%、100% 磁盘阈值；当前任务不得被静默清理。

## 17. 验收场景

1. 强制终止发生在事件行写入一半时，重启能截断尾部并恢复最后完整状态。
2. 强制终止发生在工具成功后，重启不重放工具，继续后续总结。
3. 等待确认时重启，确认记录、参数摘要和等待状态保持一致。
4. 单个任务的封存事件段损坏时，其余 999 个以上任务仍可分页查询和运行。
5. 1M Token 等量历史和大型日志不会一次载入 Java 堆或 Electron Renderer。
6. 更新 `task.json` 期间崩溃后只会看到旧完整版本或新完整版本，不会看到半文件。
7. 索引删除或损坏后可从任务元数据重建，不丢失任务真相。
8. API Key、明文确认令牌和 Artifact 真实路径无法从 Task 文件中检索到。
9. 不支持的主版本不会被旧程序写坏，迁移失败可保留旧数据恢复。
