# iOS 实时日志时间、PID 和级别丢失

## 排查步骤

- 检查前端日志表字段映射，确认时间、PID、级别直接来自后端 SSE 数据。
- 检查后端 `LogStreamService.parseIosLogLine`，确认解析失败时会降级为 `timestamp=""`、`pid=""`、`level="I"`。
- 对照真实 `idevicesyslog` 输出，发现实际日志可包含设备主机名，且时间可能没有小数秒。
- 修复后短时间拉取真实 iOS SSE，验证日志已包含时间、PID 和 `D` 级别。

## 根因

原 iOS 日志正则只兼容：

```text
Jun 30 12:25:24.754168 backboardd(...)[64321] <Debug>: ...
```

真实输出中还会出现：

```text
Jul  1 15:58:06 iPhone SpringBoard[456] <Warning>: ...
```

由于时间后多了设备主机名，且时间可能没有小数秒，正则匹配失败。匹配失败后的兜底逻辑会把日志作为普通文本输出，导致前端看到空时间、空 PID，并且级别固定为 `I`。

iOS 的 Callstack 等多行日志续行没有 syslog 头部字段，原逻辑也会降级为空字段。

## 修复

- 扩展 iOS syslog 正则，兼容可选设备主机名和无小数秒时间。
- 调整解析分组，确保进程、PID、级别和消息字段正确提取。
- 对无前缀的 iOS 多行续行，继承上一条可解析日志的时间、PID、级别和 tag。
- 补充单元测试覆盖主机名格式和续行继承。

## 验证

- `mvn test`：通过，26 个测试全部成功。
- `mvn clean package -DskipTests`：通过。
- 已重启 `devbridge-server`，当前 PID：`76107`。
- `curl /api/devices`：返回 Android 和 iOS 真实设备。
- 真实 iOS SSE 抽样中，日志包含：
  - `timestamp`: `Jul  1 16:14:37.327848`
  - `pid`: `52`
  - `level`: `D`
  - `tag`: `wifid`
- 服务日志未发现 `ERROR`、`Exception`、`INTERNAL_ERROR`。
