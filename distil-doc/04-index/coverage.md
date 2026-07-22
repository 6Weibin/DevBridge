# 覆盖率说明

## 扫描覆盖

- 静态骨架文件数：198
- 手动统计源码文件数：204
- 模块数：3（`DevBridge-Server`、`DevBridge-Front`、`DevBridge-Electron`）
- 语言：Java、TypeScript/TSX、JavaScript、CSS/HTML/YAML

## 已深入阅读区域

- 后端入口、配置、REST/SSE Controller、异常处理。
- 设备枚举、Android 文件/应用/截图、iOS 详情、实时日志、日志落盘导出。
- AI 配置、Provider endpoint 解析、Spring AI Provider Gateway、对话、日志分析、ADB MCP REST/Spring 适配和风险确认。
- 前端主业务容器、AI API 封装、AI 配置弹窗、AI 类型定义。
- Electron main/preload/splash/resource prepare/package 配置。

## 置信度

- **Confirmed**：项目定位、工程结构、核心接口、AI 配置链路、模型列表现状、Electron 端口与启动编排。
- **Partial**：部分 UI 细节、部分 Android 设备属性解析、部分 MCP 工具参数细节未逐行展开。
- **Inferred**：产品意图中“跨平台统一设备管理”结合需求文档、README 和源码共同推断，但核心功能路径已由源码确认。

## 尚未深入区域

- 所有测试文件未逐个展开分析。
- 全量 UI 组件库文件未逐个说明，因多为通用 shadcn/Radix 包装组件。
- `target`、`dist`、`release`、`node_modules` 未纳入源码分析。

