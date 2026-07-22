# 文件夹展开箭头状态排查记录

## 排查步骤

- [x] 复查文件树节点 `TreeNode` 的展开状态初始化逻辑。
- [x] 确认当前顶层目录默认 `open=true`，导致未展开目录显示向下箭头。
- [x] 修改箭头状态为仅在本地树节点存在子节点且已展开时显示向下。
- [x] 执行前端构建、后端打包和服务重启验证。

## 根因

`TreeNode` 使用 `useState(depth===0)` 初始化展开状态，顶层目录默认被认为已展开。远端文件管理的目录进入会替换当前列表，并不代表原节点已展开，因此顶层目录不应默认显示向下箭头。

## 修复方案

- `TreeNode` 默认 `open=false`。
- 新增 `hasNestedChildren` 和 `expanded` 状态计算。
- 只有 `open && hasNestedChildren` 时显示向下箭头和打开文件夹图标。
- 没有本地子节点或未展开时显示向右箭头。

## 验证结果

- `DevBridge-Front` 执行 `npx -y yarn@1.22.22 build` 通过。
- `DevBridge-Server` 执行 `mvn package -DskipTests` 通过。
- 后端服务已重启到 `http://127.0.0.1:8080/`。
- 首页返回 `200`，加载新资源 `/assets/index-CISHMKTw.js`。
- `/api/runtime/environment` 返回 `200`。
