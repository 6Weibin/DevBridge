# 工程初始化脚本技术设计（SDD）

> 来源: 用户原始需求（未单独生成 spec.md）
> 生成时间: 2026-07-22

## 技术设计

### 背景与现状

项目根目录包含 `DevBridge-Front`、`DevBridge-Server`、`DevBridge-Electron` 三个工程，分别使用 pnpm/Vite、Maven/JDK 17、npm/Electron Builder。当前缺少统一的环境检查、依赖恢复和全量构建入口。

### 架构决策

- 根目录提供 `init.sh` 和 `init.cmd`，避免引入额外脚本运行时。
- macOS 使用 Homebrew，Windows 优先使用 winget、兼容 Chocolatey；只安装实际缺失或版本不足的工具。
- 构建顺序固定为前端、后端、Electron，保证 Electron 资源准备时上游产物已存在。
- 默认执行后端测试和 Electron 目录包构建；`--check-only` 仅完成环境与工程结构检查。
- 安装、依赖恢复或构建任一步失败即退出，避免产生看似成功的不完整产物。

### 接口定义

- `./init.sh [--check-only]` — macOS 环境初始化及全工程构建。
- `init.cmd [--check-only]` — Windows 环境初始化及全工程构建。

### 数据模型

无。

## 阶段任务

- [x] 【Unix 初始化脚本】实现 macOS 工具检查、自动安装、依赖恢复和构建编排
  - 目标: 一条命令完成 macOS 环境准备和三个工程构建
  - 涉及文件: `init.sh`
  - 验收: 语法检查通过，工具缺失可安装，构建命令与工程配置一致
- [x] 【Windows 初始化脚本】实现 Windows 工具检查、自动安装、依赖恢复和构建编排
  - 目标: 一条命令完成 Windows 环境准备和三个工程构建
  - 涉及文件: `init.cmd`
  - 验收: 批处理流程具备错误传播、PATH 刷新和包管理器回退
- [x] 【联调验证】验证两个入口的静态语法、环境检查及 macOS 实际构建
  - 目标: 确认脚本可以从项目根目录可靠执行
  - 涉及文件: `init.sh`, `init.cmd`
  - 验收: 检查模式通过，macOS 全量构建通过，构建产物仍由 `.gitignore` 排除

## 完成状态

> 进度: 3/3 已完成
