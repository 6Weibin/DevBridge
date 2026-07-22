
# project 项目骨架

## 基本信息
- 路径: .
- 主语言: java
- 文件数: 198
- 模块数: 3
- 类数: 85
- 函数数: 2662
- 外部依赖: 67
- 测试文件: 22
- 热点文件: 50

## 语言分布
- javascript: 8 文件
- typescript: 61 文件
- java: 129 文件

## 角色分布
- api: 1 文件
- config: 15 文件
- controller: 9 文件
- handler: 3 文件
- model: 35 文件
- other: 104 文件
- repository: 2 文件
- service: 29 文件

## 入口文件
- DevBridge-Electron/src/main.js

## 模块结构
- **DevBridge-Electron** (7 文件) - 待进一步判断
  - 角色: other:7
  - 热点文件: DevBridge-Electron/src/main.js
- **DevBridge-Front** (62 文件) - 待进一步判断
  - 角色: other:59, api:1, config:1, model:1
  - 热点文件: DevBridge-Front/src/app/ai/aiApi.ts, DevBridge-Front/vite.config.ts
- **devbridge.server** (129 文件) - 待进一步判断
  - 角色: other:38, model:34, service:29, config:14
  - 热点文件: DevBridge-Server/src/main/java/com/devbridge/server/api/FileController.java, DevBridge-Server/src/main/java/com/devbridge/server/api/AiController.java, DevBridge-Server/src/main/java/com/devbridge/server/service/LogStreamService.java

## 热点文件 Top 10
- DevBridge-Electron/src/main.js | role=other | score=6 | inbound=0 | outbound=0 | reasons=entrypoint, many-symbols
- DevBridge-Server/src/main/java/com/devbridge/server/api/FileController.java | role=controller | score=5 | inbound=0 | outbound=0 | reasons=role:controller, many-symbols
- DevBridge-Server/src/main/java/com/devbridge/server/api/AiController.java | role=controller | score=5 | inbound=0 | outbound=0 | reasons=role:controller, many-symbols
- DevBridge-Server/src/main/java/com/devbridge/server/service/LogStreamService.java | role=service | score=4 | inbound=0 | outbound=0 | reasons=role:service, many-symbols
- DevBridge-Server/src/main/java/com/devbridge/server/service/LogCaptureService.java | role=service | score=4 | inbound=0 | outbound=0 | reasons=role:service, many-symbols
- DevBridge-Server/src/main/java/com/devbridge/server/service/IosDeviceService.java | role=service | score=4 | inbound=0 | outbound=0 | reasons=role:service, many-symbols
- DevBridge-Server/src/main/java/com/devbridge/server/service/DeviceOutputParser.java | role=service | score=4 | inbound=0 | outbound=0 | reasons=role:service, many-symbols
- DevBridge-Server/src/main/java/com/devbridge/server/service/AndroidPathGuard.java | role=service | score=4 | inbound=0 | outbound=0 | reasons=role:service, many-symbols
- DevBridge-Server/src/main/java/com/devbridge/server/service/AndroidDeviceService.java | role=service | score=4 | inbound=0 | outbound=0 | reasons=role:service, many-symbols
- DevBridge-Server/src/main/java/com/devbridge/server/api/LogController.java | role=controller | score=4 | inbound=0 | outbound=0 | reasons=role:controller

## 外部依赖
- @emotion/react, @emotion/styled, @mui/icons-material, @mui/material, @popperjs/core, @radix-ui/react-accordion, @radix-ui/react-alert-dialog, @radix-ui/react-aspect-ratio, @radix-ui/react-avatar, @radix-ui/react-checkbox
