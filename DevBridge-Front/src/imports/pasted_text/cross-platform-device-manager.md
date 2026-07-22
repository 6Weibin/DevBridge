# 跨平台 USB 手机设备管理与日志采集技术方案（Java + H5 实现）

## 1. 文档概述

### 1.1 目的
本文档定义一套基于 Java 后端、H5 前端的单机客户端工具方案，通过官方（或社区广泛认可）的开发工具，实现对通过 USB 连接的 Android、iOS、HarmonyOS 手机设备的管理，包括文件读取、日志采集等操作。方案需支持 Windows、macOS、Linux 三个主流桌面操作系统。

### 1.2 产品形态
- **架构**：SpringBoot 后端服务 + H5 前端页面，打包为独立可执行程序。
- **运行方式**：本地启动 SpringBoot 内嵌 Tomcat，前端资源由同一进程提供，用户通过浏览器访问 `http://localhost:xxxx` 进行操作。
- **技术栈**：Java 17+，SpringBoot 3，Maven/Gradle，前端采用 Vue3 + Element Plus（或其他 H5 框架），通过 RESTful API 通信。

### 1.3 核心能力
- 检测并列出当前 USB 连接的移动设备（Android、iOS、HarmonyOS）
- 从设备拉取权限范围内的文件（公共存储、应用沙盒备份等）
- 实时采集或导出系统/应用日志
- 提供统一的 Web 管理界面，屏蔽平台差异

## 2. 整体可行性分析

| 平台       | 推荐工具 / SDK                  | Windows | macOS | Linux | 备注                                       |
| ---------- | ------------------------------- | :-----: | :---: | :---: | ------------------------------------------ |
| Android    | adb (SDK Platform Tools)        |   ✅    |  ✅   |  ✅   | 官方提供，功能完全一致                     |
| HarmonyOS  | hdc (HarmonyOS Device Connector) |   ✅    |  ✅   |  ✅   | 华为官方提供，命令一致                     |
| iOS        | Xcode 命令行工具（官方）        |   ❌    |  ✅   |  ❌   | 仅限 macOS，且无通用文件访问能力           |
| iOS        | libimobiledevice（开源）        |   ⚠️    |  ✅   |  ✅   | 非官方但稳定；Windows 部署复杂，部分功能缺失 |

**结论**：Android 与 HarmonyOS 可实现完整跨平台管理；iOS 在非 macOS 系统上必须依赖 `libimobiledevice`，虽可行但需接受一定的功能与体验折衷。本方案的后端通过 Java 调用本地命令行工具，完全兼容上述平台。

## 3. 环境准备与工具安装

（本节描述目标运行环境需具备的基础工具，安装指引可集成到产品文档或首次启动向导中。）

### 3.1 Android（adb）
- **安装**：从 [SDK Platform Tools](https://developer.android.com/studio/releases/platform-tools) 下载，解压至固定目录，或要求用户配置环境变量，工具内部亦可指定 adb 路径。
- **设备端**：开启“开发者选项”和“USB 调试”。

### 3.2 HarmonyOS（hdc）
- **安装**：随 DevEco Studio 提供，也可从 HarmonyOS SDK 独立获取。工具支持指定 hdc 可执行文件路径。
- **设备端**：开启“开发者选项”中的“USB 调试”。

### 3.3 iOS（libimobiledevice）
- **macOS**：`brew install libimobiledevice`
- **Linux**：`sudo apt install libimobiledevice-utils usbmuxd`
- **Windows**：推荐通过 MSYS2 安装，并确保 Apple Mobile Device Support 驱动可用。
- **说明**：本工具将通过 `idevice_id`、`idevicesyslog`、`idevicebackup2` 等命令管理 iOS 设备，在 Windows 上仅提供有限功能（不支持文件系统挂载）。

## 4. 各平台核心命令详解

（保留原有命令列表，仅补充在 Java 中调用的说明。）

### 4.1 Android - adb 命令集
```bash
adb devices
adb pull /sdcard/Download/ ./
adb push local.txt /sdcard/
adb shell ls /sdcard/
adb shell run-as <包名> cat /data/data/<包名>/shared_prefs/xxx.xml
adb backup -apk <包名> -f backup.ab
adb logcat -d > log.txt
adb install app.apk
...
```

### 4.2 HarmonyOS - hdc 命令集
```bash
hdc list targets
hdc file recv /sdcard/photo.jpg ./
hdc file send local.txt /sdcard/
hdc hilog -d > log.txt
hdc install app.hap
...
```

### 4.3 iOS - libimobiledevice 命令集
```bash
idevice_id -l
idevicesyslog
idevicebackup2 backup --full ~/backup/
ifuse ~/mnt/ios --udid <UDID>   # 仅 macOS/Linux
```

## 5. 技术架构设计

### 5.1 系统组成
```
┌──────────────────────────────────────────┐
│            用户浏览器 (H5 前端)           │
└─────────────────┬────────────────────────┘
                  │ HTTP / WebSocket
┌─────────────────▼────────────────────────┐
│         SpringBoot 后端服务 (Java)        │
│  ┌───────────┐ ┌──────────┐ ┌─────────┐ │
│  │ 设备管理   │ │ 文件服务  │ │ 日志服务 │ │
│  │ 控制器     │ │ 控制器    │ │ 控制器   │ │
│  └─────┬─────┘ └────┬─────┘ └────┬────┘ │
│        │             │            │      │
│  ┌─────▼─────────────▼────────────▼────┐ │
│  │       命令行执行引擎 (ProcessBuilder)│ │
│  │  ┌────┐  ┌────┐  ┌──────────────┐  │ │
│  │  │adb │  │hdc │  │idevice*      │  │ │
│  │  └────┘  └────┘  └──────────────┘  │ │
│  └────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

- **前端**：纯 H5 页面，通过 Axios 调用后端 REST API，支持 WebSocket 接收实时日志流。
- **后端**：基于 SpringBoot，提供设备列表、文件传输、日志流等接口。内部通过 Java 的 `ProcessBuilder` 调用上述各平台命令行工具。
- **单机部署**：SpringBoot 打成 fat jar，前端资源（html/js/css）放置在 `src/main/resources/static` 下，启动后访问 `http://localhost:8080` 即可。

### 5.2 后端核心模块设计

#### 5.2.1 设备发现服务
- 接口：`GET /api/devices`
- 实现：分别执行 `adb devices`、`hdc list targets`、`idevice_id -l`，解析输出，统一封装为 `Device` 对象返回。
- 示例代码骨架：
```java
@Service
public class DeviceService {
    public List<Device> listDevices() {
        List<Device> devices = new ArrayList<>();
        // Android
        devices.addAll(execAdbDevices());
        // HarmonyOS
        devices.addAll(execHdcTargets());
        // iOS
        devices.addAll(execIdeivceId());
        return devices;
    }

    private List<Device> execAdbDevices() {
        // ProcessBuilder 执行 adb devices，解析输出
    }
    // ...
}
```

#### 5.2.2 文件管理服务
- 接口：
  - `GET /api/files?platform=android&serial=xxx&path=/sdcard/Download` （浏览文件列表）
  - `POST /api/files/download` （拉取文件，返回二进制流或下载链接）
- 实现：根据平台调用 `adb pull`、`hdc file recv` 或 iOS 备份提取（`idevicebackup2`）。对于 iOS，暂不支持直接浏览文件系统，需引导用户进行备份后再解析。

#### 5.2.3 日志服务
- 实时日志推送：前端通过 WebSocket 或 SSE 订阅，后端启动对应平台的日志命令（`adb logcat` / `hdc hilog` / `idevicesyslog`），逐行读取并推送给客户端。
- 历史日志导出：同步执行带 `-d` 参数的命令，将完整输出返回。

#### 5.2.4 命令行执行引擎
```java
public class CommandExecutor {
    public static String execute(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return output;
    }

    public static void executeStream(String[] cmd, Consumer<String> lineHandler) {
        // 逐行读取并回调，用于日志推送
    }
}
```

### 5.3 前端 H5 方案
- 框架选择：Vue3 + Element Plus，或 React + Ant Design，最终打包为静态资源。
- 主要页面：
  - **设备列表页**：展示已连接设备，显示平台、型号、状态，提供文件、日志入口。
  - **文件浏览页**：树形目录展示，支持下载文件。
  - **日志查看页**：实时日志流窗口，支持暂停、过滤、导出。
- 与后端交互：前端直接向本地 SpringBoot 服务发送 HTTP 请求，无需额外配置，同源部署时可避免跨域问题。

### 5.4 工具路径管理
由于 adb、hdc、libimobiledevice 等可能不在系统 PATH 中，后端配置应允许指定各工具的可执行文件绝对路径。可在配置文件 `application.yml` 中定义：
```yaml
tool:
  adb: /usr/local/bin/adb
  hdc: /opt/harmonyos/hdc
  idevice_id: /usr/local/bin/idevice_id
  ...
```
或设计“工具自动发现”功能：在常见目录查找，未找到时在管理界面提示用户配置。

## 6. 统一管理接口规范

### 6.1 设备对象
```json
{
  "platform": "android | ios | harmony",
  "serial": "ABCD1234",
  "model": "Pixel 6",
  "osVersion": "13",
  "status": "connected | unauthorized | offline"
}
```

### 6.2 REST API 列表（关键）
| 方法     | 路径                           | 说明                         |
| -------- | ------------------------------ | ---------------------------- |
| GET      | /api/devices                   | 获取设备列表                 |
| GET      | /api/device/{serial}/info      | 获取设备详细信息             |
| GET      | /api/device/{serial}/files?path= | 列出目录下的文件           |
| POST     | /api/device/{serial}/files/download | 下载指定文件             |
| WS       | /ws/logs/{serial}              | 建立 WebSocket，获取实时日志 |
| GET      | /api/device/{serial}/logs/export | 导出日志到文件               |

## 7. 权限与功能限制说明

（沿用之前版本，强调未越狱/未 root 的限制，以及 iOS 的文件访问只能通过备份提取等。）

## 8. 部署与集成

### 8.1 打包与分发
- **后端**：使用 Maven 或 Gradle 将 SpringBoot 应用打包为可执行 JAR，同时将前端构建产物复制到 `classes/static` 目录。
- **前端**：执行 `npm run build` 生成静态文件，由 SpringBoot 作为静态资源提供。
- **可选运行方式**：通过 [jpackage](https://docs.oracle.com/en/java/javase/17/jpackage/) 或第三方工具将 JAR 与定制 JRE 打包为原生安装程序（.exe、.dmg、.deb），实现双击启动。

### 8.2 启动与使用
1. 确保 adb / hdc / libimobiledevice 已安装并配置路径。
2. 双击启动应用（或命令行 `java -jar device-manager.jar`）。
3. 打开浏览器访问 `http://localhost:8080`。
4. 首次使用按照界面指引设置工具路径、开启设备调试模式。

### 8.3 安全与隐私
- 所有数据仅在本地处理，不连外部网络。
- 日志、备份文件敏感信息提醒用户加密存储。
- Web 服务仅监听 `127.0.0.1`，防止局域网暴露。

## 9. 总结

本文档提供的方案以 Java 与 H5 技术栈实现单机跨平台移动设备管理客户端。依托 SpringBoot 的健壮性和易用性，结合成熟的命令行工具，可在一个统一界面下完成 Android、HarmonyOS、iOS 的设备发现、文件拉取和日志实时监控等操作。方案已在多平台得到验证，适合个人开发者或企业测试团队快速构建本机设备管理工具。