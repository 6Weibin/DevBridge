# 文件管理目录单击进入问题排查记录

## 排查步骤

- [x] 检查文件树节点 `TreeNode` 的点击事件。
- [x] 检查目录选择回调 `selectFileNode` 的行为。
- [x] 确认问题边界只在前端文件树交互，不涉及后端文件接口。
- [x] 将目录“选中查看信息”和“进入目录”拆成两个动作。
- [x] 执行前端构建、后端打包、服务重启和接口验证。

## 假设和验证结果

### 假设 1：目录详情区域没有渲染能力

验证：右侧详情区已支持 `file.type === "dir"` 时展示目录标题、完整路径、类型、时间、权限等信息。

结论：详情展示能力存在，不是根因。

### 假设 2：单击目录同时触发进入目录

验证：`TreeNode` 的 `onClick` 会调用 `onSelect(node)`；`selectFileNode` 内部对目录立即调用 `loadFiles(node.path)`，因此单击目录会马上切换目录并清空当前选中项。

结论：该假设成立。

## 根因定位链

症状：用户单击目录后直接进入目录，无法选中目录查看右侧信息。

代码路径：`TreeNode.onClick` → `selectFileNode(node)` → `loadFiles(node.path)`。

根因：目录选择和目录进入复用了同一个单击事件，交互职责混在一起。

## 修复方案

- `selectFileNode` 只负责设置当前选中文件/目录。
- 新增 `openFileDirectory`，只负责进入目录。
- `TreeNode` 单击节点主体只选中。
- `TreeNode` 双击目录主体进入目录。
- 点击目录最前面的箭头进入目录，并保留本地 mock 树展开状态。

## 验证结果

- `DevBridge-Front` 执行 `npx -y yarn@1.22.22 build` 通过。
- `DevBridge-Server` 执行 `mvn package -DskipTests` 通过。
- 后端服务已重启到 `http://127.0.0.1:8080/`。
- 首页返回 `200`，加载新资源 `/assets/index-BHFaCfaN.js`。
- `/api/runtime/environment` 返回 `200`。
