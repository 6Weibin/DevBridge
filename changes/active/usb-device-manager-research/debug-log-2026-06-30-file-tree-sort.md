# 文件管理树形菜单排序实现记录

> 日期：2026-06-30  
> 类型：feature  
> by AI.Coding

## 需求目标

- 文件管理树形菜单展示时，文件夹排在文件前面。
- 文件夹内部和文件内部都按名称升序排序。
- 后端真实数据和前端演示数据保持一致排序规则。

## 实现方案

- 后端 `RemoteFileParser` 在解析 Android `ls -la` 输出后统一排序。
- 排序规则为目录优先，同类型按名称忽略大小写升序，大小写差异作为兜底顺序。
- 前端新增文件树排序函数，对接口数据、演示数据和递归 children 都做同一规则排序。
- 保留现有文件树状态保持逻辑，不改变目录切换、文件选中和预览行为。

## 验证结果

- `mvn test`：通过，18 个测试全部成功。
- `./node_modules/.bin/vite build`：通过，生成 `/assets/index-LQKGRT8F.js` 和 `/assets/index-KcCNF6ul.css`。
- `mvn package -DskipTests`：通过，后端 jar 已包含最新前端静态资源。
- 8080 服务已重启，首页引用新资源 `/assets/index-LQKGRT8F.js`。
- 真实 Android 设备 `EMH0223511000196` 目录 `/sdcard/DCIM` 验证通过：
  - 返回顺序：`.android`、`.tmfs`、`Camera`、`DJI Album`、`DJI Export`、`tassistant`、`WeixinWork`、`isSimple.txt`
  - 校验结果：`sorted=true`
