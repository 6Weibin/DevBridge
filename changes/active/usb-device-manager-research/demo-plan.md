# Demo 计划：跨平台 USB 手机设备管理 PoC

## 1. Demo 目标

- 验证假设：Spring Boot 后端可以安全调用本机 adb/hdc/libimobiledevice 工具，并向 H5 Demo 提供可消费的设备、工具状态和日志流接口。
- 成功标准：无设备工具时接口仍稳定返回可解释状态；有工具时可通过同一 API 扩展到真实设备；前端能从后端加载数据并在无工具环境下正常展示空态/错误态。

## 2. Demo 范围

- 包含：
  - 在 `DevBridge-Server` 新建最小 Spring Boot 3 工程。
  - 实现 `/api/tools/status`、`/api/devices`、`/api/logs/demo` 三个 PoC 接口。
  - 增加命令执行封装，使用参数数组和超时，不拼接 shell。
  - 增加命令输出解析测试，覆盖 adb/hdc/iOS 典型输出。
  - 改造 `DevBridge-Front/src/app/App.tsx`，从后端加载工具状态、设备列表和演示日志。
- 不包含：
  - 真实文件下载。
  - iOS 备份解析。
  - 驱动安装器。
  - 完整打包、jpackage、自动更新。
  - root/越狱能力。

## 3. 实现方案

| 文件/模块 | 操作 | 说明 |
|-----------|------|------|
| `changes/active/usb-device-manager-research/tasks.md` | 新增 | 记录 PoC 实施任务和进度 |
| `DevBridge-Server/pom.xml` | 新增 | Spring Boot 3 + Java 17 + 测试依赖 |
| `DevBridge-Server/src/main/java/...` | 新增 | 应用入口、Controller、Service、命令执行和解析模型 |
| `DevBridge-Server/src/test/java/...` | 新增 | 命令输出解析与安全边界测试 |
| `DevBridge-Front/src/app/App.tsx` | 修改 | 将静态 mock 接入本机后端 API，失败时降级到 mock |

## 4. 验证命令

| 命令 | 预期结果 |
|------|----------|
| `mvn test` | 后端单元测试通过 |
| `mvn spring-boot:run` | 后端服务可启动在 `127.0.0.1:8080` |
| `curl http://127.0.0.1:8080/api/tools/status` | 返回工具状态 JSON |
| `curl http://127.0.0.1:8080/api/devices` | 返回设备数组；无工具时为空数组且不报 500 |
| `pnpm build` | 前端构建通过 |

## 5. 风险控制

- 隔离方式：PoC 只新增最小接口，不实现高风险文件写入；真实命令调用限定在工具状态和只读设备枚举。
- 清理方式：如需回退，可删除 `DevBridge-Server` 新增工程与前端 API 接入改动。
- 安全措施：命令执行使用 `ProcessBuilder(List<String>)`，禁止 shell 字符串拼接；命令超时强制销毁；后端默认绑定 `127.0.0.1`。
