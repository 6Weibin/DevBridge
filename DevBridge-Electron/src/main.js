/**
 * DevBridge Electron 主进程入口。
 *
 * by AI.Coding
 */
const { app, BrowserWindow, dialog, session } = require('electron');
const childProcess = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
const path = require('path');

const FRONTEND_PORT = 15173;
const BACKEND_PORT = 18180;
const LEGACY_FRONTEND_API_PORT = 8080;
const LOCAL_HOST = '127.0.0.1';
const HEALTH_PATH = '/api/runtime/environment';
const ADB_DIAGNOSTIC_PATH = '/api/diagnostics/adb-devices';
const FRONTEND_ORIGIN = `http://${LOCAL_HOST}:${FRONTEND_PORT}`;
const LEGACY_FRONTEND_API_FILTER = { urls: [`http://${LOCAL_HOST}:${LEGACY_FRONTEND_API_PORT}/api/*`] };
const BACKEND_API_FILTER = { urls: [`http://${LOCAL_HOST}:${BACKEND_PORT}/api/*`] };
const CORS_API_FILTER = { urls: [...LEGACY_FRONTEND_API_FILTER.urls, ...BACKEND_API_FILTER.urls] };
const API_CORS_METHODS = 'GET,POST,PUT,DELETE,OPTIONS';
const CONTROL_PLANE_TOKEN_HEADER = 'X-Ai-DevBridge-Token';
const API_CORS_HEADERS = [
  'Content-Type',
  'Accept',
  CONTROL_PLANE_TOKEN_HEADER,
  'Idempotency-Key',
  'X-Agent-Conversation-Id',
  'X-Agent-Confirmation-Token',
  'Last-Event-ID',
].join(',');
const REUSE_BACKEND = process.env.DEVBRIDGE_ELECTRON_REUSE_BACKEND === '1';
const APP_NAME = 'Ai DevBridge';
const ADB_STARTUP_MAX_ATTEMPTS = 3;
const ADB_STARTUP_RETRY_DELAY_MS = 1200;
// Electron 窗口图标随 src 一起进入 asar，避免打包后依赖 build 目录。
const APP_ICON_PATH = path.join(__dirname, 'assets', 'DevBridge_logo.png');
// 令牌只保存在 Electron 主进程和后端子进程环境中，不写入前端资源或磁盘。
const controlPlaneToken = process.env.DEVBRIDGE_CONTROL_PLANE_TOKEN || crypto.randomBytes(32).toString('base64url');

// 开发态和打包态都显式设置应用名，避免原生菜单或系统任务栏继续显示旧名称。
app.setName(APP_NAME);

let frontendServer;
let backendProcess;
let backendStartedByApp = false;
let appQuitting = false;
let mainWindow;

/**
 * 启动桌面客户端；先展示 Electron 自有启动页，再逐步启动本地服务。
 */
async function startApplication() {
  try {
    await createMainWindow();
    registerBackendApiCorsBridge();
    updateStartupProgress(8, '初始化客户端窗口', `正在载入 ${APP_NAME} 启动环境`);
    const paths = resolveResourcePaths();
    updateStartupProgress(18, '准备运行目录', '正在检查客户端资源和用户数据目录');
    await ensureDirectory(paths.userDataRoot);
    updateStartupProgress(32, '启动页面服务', '正在启动本地前端静态服务');
    await startFrontendServer(paths.frontendRoot);
    updateStartupProgress(58, '启动后端服务', '正在检查或启动本机设备管理服务');
    await ensureBackend(paths);
    updateStartupProgress(82, '检查 ADB 服务', '正在确认 Android Debug Bridge 后台服务状态');
    await ensureAdbServiceReady();
    updateStartupProgress(90, '服务已就绪', '正在切换到设备管理主页');
    await delay(420);
    await loadMainPage();
  } catch (error) {
    updateStartupProgress(100, '启动失败', error.message || '客户端启动失败');
    await showStartupError(error);
    app.quit();
  }
}

/**
 * 为 Electron 本地页面桥接后端 API 跨域访问。
 *
 * 后端当前在 CORS 处理链路会触发 Tomcat 类缺失；这里限定只处理本机 API，
 * 避免修改 Front/Server 的同时让桌面客户端可以读取设备接口。
 */
function registerBackendApiCorsBridge() {
  const webRequest = session.defaultSession.webRequest;
  webRequest.onBeforeRequest(LEGACY_FRONTEND_API_FILTER, (details, callback) => {
    // Front 构建产物暂时硬编码 8080，这里在 Electron 内重定向到客户端专用后端端口。
    callback({ redirectURL: rewriteLegacyApiUrl(details.url) });
  });
  webRequest.onBeforeSendHeaders(CORS_API_FILTER, (details, callback) => {
    const headers = { ...details.requestHeaders };
    removeHeader(headers, 'Origin');
    setHeader(headers, CONTROL_PLANE_TOKEN_HEADER, controlPlaneToken);
    callback({ requestHeaders: headers });
  });
  webRequest.onHeadersReceived(CORS_API_FILTER, (details, callback) => {
    const headers = { ...details.responseHeaders };
    headers['Access-Control-Allow-Origin'] = [FRONTEND_ORIGIN];
    // 文件删除、应用卸载和 AI 配置保存会触发预检；漏放对应方法会让 Electron 前端得到 Failed to fetch。
    headers['Access-Control-Allow-Methods'] = [API_CORS_METHODS];
    // AI 任务幂等、确认和事件恢复都使用自定义头；遗漏任一项会被 Chromium 预检拦截为 Failed to fetch。
    headers['Access-Control-Allow-Headers'] = [API_CORS_HEADERS];
    callback({ responseHeaders: headers });
  });
}

/**
 * 将前端硬编码的 8080 API 地址改写为 Electron 后端专用端口。
 *
 * @param {string} rawUrl 前端发起的原始 API 地址
 * @returns {string} Electron 实际访问的后端地址
 */
function rewriteLegacyApiUrl(rawUrl) {
  const url = new URL(rawUrl);
  url.port = String(BACKEND_PORT);
  return url.toString();
}

/**
 * 按大小写无关方式删除请求头；Chromium 传入的 Header 名大小写不固定。
 *
 * @param {Record<string, string|string[]>} headers 请求头集合
 * @param {string} targetName 目标 Header 名
 */
function removeHeader(headers, targetName) {
  for (const name of Object.keys(headers)) {
    if (name.toLowerCase() === targetName.toLowerCase()) {
      delete headers[name];
    }
  }
}

/**
 * 按大小写无关方式设置唯一请求头，避免 Chromium 原有 Header 与控制面令牌重复。
 *
 * @param {Record<string, string|string[]>} headers 请求头集合
 * @param {string} name 请求头名称
 * @param {string} value 请求头值
 */
function setHeader(headers, name, value) {
  removeHeader(headers, name);
  headers[name] = value;
}

/**
 * 向启动页发送进度；主页面尚未加载时只更新启动页，不依赖业务前端。
 *
 * @param {number} percent 进度百分比
 * @param {string} title 当前步骤标题
 * @param {string} detail 当前步骤详情
 */
function updateStartupProgress(percent, title, detail) {
  if (!mainWindow || mainWindow.isDestroyed()) {
    return;
  }
  mainWindow.webContents.send('startup-progress', { percent, title, detail });
}

/**
 * 解析开发态和打包态的资源目录，确保 jar、前端和工具目录都来自 Electron 工程产物。
 *
 * @returns {object} 资源路径集合
 */
function resolveResourcePaths() {
  const resourceRoot = app.isPackaged
    ? path.join(process.resourcesPath, 'devbridge')
    : path.join(app.getAppPath(), 'resources');
  return {
    resourceRoot,
    frontendRoot: path.join(resourceRoot, 'frontend'),
    backendJar: path.join(resourceRoot, 'backend', 'devbridge-server.jar'),
    bundledToolRoot: path.join(resourceRoot, 'tools'),
    runtimeJava: path.join(resourceRoot, 'jre', 'bin', process.platform === 'win32' ? 'java.exe' : 'java'),
    userDataRoot: path.join(app.getPath('userData'), 'runtime'),
    logsRoot: path.join(app.getPath('logs'), 'backend'),
  };
}

/**
 * 创建目录；桌面客户端的临时下载和日志目录不能依赖工程源码路径。
 *
 * @param {string} directory 目标目录
 */
async function ensureDirectory(directory) {
  await fs.promises.mkdir(directory, { recursive: true });
}

/**
 * 等待 HTTP 服务监听成功；Electron 启动链路需要 Promise 化，避免服务未就绪就继续加载窗口。
 *
 * @param {import('http').Server} server HTTP 服务
 * @param {number} port 监听端口
 * @param {string} serviceName 服务名称
 */
function listen(server, port, serviceName) {
  return new Promise((resolve, reject) => {
    const onError = (error) => {
      server.off('listening', onListening);
      reject(new Error(`${serviceName}启动失败：${formatListenError(error, port)}`));
    };
    const onListening = () => {
      server.off('error', onError);
      resolve();
    };

    // 只监听本机地址，避免 PoC 客户端把前端页面暴露到局域网。
    server.once('error', onError);
    server.once('listening', onListening);
    server.listen(port, LOCAL_HOST);
  });
}

/**
 * 转换端口监听错误，给用户可执行的排查信息。
 *
 * @param {NodeJS.ErrnoException} error 原始错误
 * @param {number} port 监听端口
 * @returns {string} 用户可读错误
 */
function formatListenError(error, port) {
  if (error.code === 'EADDRINUSE') {
    return `127.0.0.1:${port} 已被占用，请关闭占用该端口的服务后重试。`;
  }
  return error.message || String(error);
}

/**
 * 启动前端静态服务。桌面端使用 15173，避免与 Vite 默认端口冲突。
 *
 * @param {string} frontendRoot 前端 dist 目录
 */
async function startFrontendServer(frontendRoot) {
  await assertReadableFile(path.join(frontendRoot, 'index.html'), '前端构建产物缺少 index.html');
  frontendServer = http.createServer((request, response) => {
    serveStaticFile(frontendRoot, request, response);
  });
  await listen(frontendServer, FRONTEND_PORT, '前端静态服务');
}

/**
 * 输出静态文件；路径会做归一化校验，避免请求逃逸出前端资源目录。
 *
 * @param {string} root 静态资源根目录
 * @param {import('http').IncomingMessage} request HTTP 请求
 * @param {import('http').ServerResponse} response HTTP 响应
 */
function serveStaticFile(root, request, response) {
  const requestPath = safeRequestPath(request.url || '/');
  const filePath = requestPath === '/' ? path.join(root, 'index.html') : path.join(root, requestPath);
  if (!filePath.startsWith(root)) {
    writeResponse(response, 403, 'text/plain; charset=utf-8', 'Forbidden');
    return;
  }
  fs.promises.readFile(filePath)
    .then((content) => writeResponse(response, 200, contentType(filePath), content))
    .catch(() => writeResponse(response, 404, 'text/plain; charset=utf-8', 'Not Found'));
}

/**
 * 将 URL 转换为本地安全路径；前端 Vite 产物使用绝对 `/assets` 路径，需要保留该路径结构。
 *
 * @param {string} rawUrl 原始 URL
 * @returns {string} 安全路径
 */
function safeRequestPath(rawUrl) {
  const parsed = new URL(rawUrl, `http://${LOCAL_HOST}:${FRONTEND_PORT}`);
  return path.normalize(decodeURIComponent(parsed.pathname)).replace(/^(\.\.[/\\])+/, '');
}

/**
 * 写入 HTTP 响应。
 *
 * @param {import('http').ServerResponse} response HTTP 响应
 * @param {number} statusCode 状态码
 * @param {string} type Content-Type
 * @param {Buffer|string} body 响应体
 */
function writeResponse(response, statusCode, type, body) {
  response.writeHead(statusCode, { 'Content-Type': type });
  response.end(body);
}

/**
 * 根据扩展名推断 Content-Type，满足前端 JS/CSS/图片资源加载。
 *
 * @param {string} filePath 文件路径
 * @returns {string} Content-Type
 */
function contentType(filePath) {
  const extension = path.extname(filePath).toLowerCase();
  const types = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.webp': 'image/webp',
  };
  return types[extension] || 'application/octet-stream';
}

/**
 * 确保后端服务可用；默认要求 Electron 自己启动后端，避免 PoC 测试误依赖外部服务。
 *
 * @param {object} paths 资源路径集合
 */
async function ensureBackend(paths) {
  const existing = await probeBackend();
  if (existing.ok) {
    if (!REUSE_BACKEND) {
      // 默认不复用已有服务，是为了验证桌面客户端能独立拉起内置后端。
      throw new Error(`127.0.0.1:${BACKEND_PORT} 已有 ${APP_NAME} 后端在运行。请先关闭占用该端口的服务后再启动客户端；如需临时复用，请设置 DEVBRIDGE_ELECTRON_REUSE_BACKEND=1。`);
    }
    updateStartupProgress(74, '复用已有后端服务', `检测到 127.0.0.1:${BACKEND_PORT} 已有 ${APP_NAME} 后端，已跳过重复启动`);
    return;
  }
  if (existing.authFailed) {
    throw new Error(`127.0.0.1:${BACKEND_PORT} 已有启用控制面认证的后端，但当前 Electron 令牌不匹配。复用后端时请设置一致的 DEVBRIDGE_CONTROL_PLANE_TOKEN。`);
  }
  if (existing.occupied) {
    throw new Error(`127.0.0.1:${BACKEND_PORT} 已被其他服务占用，请释放端口后再启动客户端。`);
  }
  await assertReadableFile(paths.backendJar, '后端 jar 不存在');
  await ensureDirectory(paths.logsRoot);
  updateStartupProgress(70, '启动内置后端服务', '正在拉起客户端内置 Spring Boot 服务');
  startBackendProcess(paths);
  updateStartupProgress(78, '等待后端就绪', '正在等待本机设备管理接口完成健康检查');
  await waitForBackend();
}

/**
 * 探测后端健康状态，区分端口未监听和被其他服务占用两种场景。
 *
 * @returns {Promise<{ok: boolean, occupied: boolean, authFailed?: boolean}>} 探测结果
 */
async function probeBackend() {
  try {
    const response = await httpGet(HEALTH_PATH, 1200);
    if (response.statusCode === 401 || response.statusCode === 403) {
      return { ok: false, occupied: true, authFailed: true };
    }
    const ok = response.statusCode === 200 && response.body.includes('toolDirectoryName');
    return { ok, occupied: !ok };
  } catch (error) {
    return { ok: false, occupied: error.code !== 'ECONNREFUSED' };
  }
}

/**
 * 启动 Spring Boot jar。通过命令行参数传入绝对工具目录，避免 Electron 打包后工作目录变化。
 *
 * @param {object} paths 资源路径集合
 */
function startBackendProcess(paths) {
  const javaExecutable = fs.existsSync(paths.runtimeJava) ? paths.runtimeJava : 'java';
  const downloadRoot = path.join(paths.userDataRoot, 'downloads');
  const captureRoot = path.join(paths.userDataRoot, 'logs');
  const aiConfigRoot = path.join(paths.userDataRoot, 'ai');
  const aiAgentDataRoot = path.join(aiConfigRoot, 'agent-data');
  const toolArtifactRoot = path.join(aiConfigRoot, 'artifacts');
  const toolAuditRoot = path.join(aiConfigRoot, 'audit');
  const logFile = path.join(paths.logsRoot, 'devbridge-server.log');
  const runtimeBackendJar = prepareRuntimeBackendJar(paths);
  const output = fs.openSync(logFile, 'a');
  backendProcess = childProcess.spawn(javaExecutable, [
    '-jar',
    runtimeBackendJar,
    `--server.address=${LOCAL_HOST}`,
    `--server.port=${BACKEND_PORT}`,
    `--devbridge.bundled-tool-root=${paths.bundledToolRoot}`,
    `--devbridge.download-temp-root=${downloadRoot}`,
    `--devbridge.log-capture-root=${captureRoot}`,
    `--devbridge.ai-config-root=${aiConfigRoot}`,
    `--devbridge.ai-agent-data-root=${aiAgentDataRoot}`,
    `--devbridge.tool-artifact-root=${toolArtifactRoot}`,
    `--devbridge.tool-audit-root=${toolAuditRoot}`,
  ], {
    // 所有未显式覆盖的相对运行文件也必须落在用户数据目录，不能污染签名资源目录。
    cwd: paths.userDataRoot,
    env: {
      ...process.env,
      DEVBRIDGE_CONTROL_PLANE_ENABLED: 'true',
      DEVBRIDGE_CONTROL_PLANE_TOKEN: controlPlaneToken,
    },
    windowsHide: true,
    stdio: ['ignore', output, output],
  });
  backendStartedByApp = true;
  backendProcess.on('exit', (code) => handleBackendExit(code));
}

/**
 * 复制后端 jar 到用户运行目录再启动，避免开发态重新准备资源时覆盖正在运行的 jar。
 *
 * @param {object} paths 资源路径集合
 * @returns {string} 运行副本 jar 路径
 */
function prepareRuntimeBackendJar(paths) {
  const runtimeBackendRoot = path.join(paths.userDataRoot, 'backend');
  const runtimeBackendJar = path.join(runtimeBackendRoot, 'devbridge-server-runtime.jar');
  fs.mkdirSync(runtimeBackendRoot, { recursive: true });
  // Spring Boot 嵌套依赖按需从 jar 读取；运行中的 jar 被覆盖会导致类加载和静态资源请求异常。
  fs.copyFileSync(paths.backendJar, runtimeBackendJar);
  return runtimeBackendJar;
}

/**
 * 等待后端启动完成，避免窗口过早加载导致页面接口初始化失败。
 */
async function waitForBackend() {
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    const health = await probeBackend();
    if (health.ok) {
      return;
    }
    await delay(500);
  }
  throw new Error('后端服务启动超时，请查看 Electron 日志目录中的 backend/devbridge-server.log。');
}

/**
 * 确保 ADB daemon 可用；已有 daemon 可直接通过诊断则不做额外重试。
 */
async function ensureAdbServiceReady() {
  let lastFailure = '未收到 ADB 诊断结果';
  for (let attempt = 1; attempt <= ADB_STARTUP_MAX_ATTEMPTS; attempt += 1) {
    updateStartupProgress(82 + attempt, '检查 ADB 服务', `正在执行 ADB 启动检查（${attempt}/${ADB_STARTUP_MAX_ATTEMPTS}）`);
    const result = await probeAdbService();
    if (result.ok) {
      updateStartupProgress(86, 'ADB 服务已就绪', result.message);
      return;
    }
    lastFailure = result.message;
    if (attempt < ADB_STARTUP_MAX_ATTEMPTS) {
      await delay(ADB_STARTUP_RETRY_DELAY_MS);
    }
  }
  throw new Error(`ADB 服务启动失败，已重试 ${ADB_STARTUP_MAX_ATTEMPTS} 次。\n失败原因：${lastFailure}`);
}

/**
 * 调用后端诊断接口验证 ADB 服务状态。
 *
 * @returns {Promise<{ok: boolean, message: string}>} ADB 检查结果
 */
async function probeAdbService() {
  try {
    const response = await httpGet(ADB_DIAGNOSTIC_PATH, 15000);
    if (response.statusCode !== 200) {
      return { ok: false, message: `ADB 诊断接口返回 HTTP ${response.statusCode}：${response.body}` };
    }
    return interpretAdbDiagnostic(response.body);
  } catch (error) {
    return { ok: false, message: `ADB 诊断接口请求失败：${formatRequestError(error)}` };
  }
}

/**
 * 解析 ADB 诊断 JSON，转换为启动页可读状态。
 *
 * @param {string} body 后端诊断响应体
 * @returns {{ok: boolean, message: string}} 检查结果
 */
function interpretAdbDiagnostic(body) {
  try {
    const diagnostic = JSON.parse(body);
    if (diagnostic.exitCode === 0 && !diagnostic.timedOut) {
      return { ok: true, message: summarizeAdbSuccess(diagnostic) };
    }
    return { ok: false, message: formatAdbDiagnosticFailure(diagnostic) };
  } catch (error) {
    return { ok: false, message: `ADB 诊断响应无法解析：${error.message || String(error)}；原始响应：${body}` };
  }
}

/**
 * 格式化 ADB 成功状态；没有设备连接也表示 daemon 已经正常启动。
 *
 * @param {object} diagnostic ADB 诊断结果
 * @returns {string} 成功摘要
 */
function summarizeAdbSuccess(diagnostic) {
  const stdout = diagnosticLines(diagnostic.stdout);
  const deviceLines = stdout.filter((line) => line && !line.startsWith('List of devices attached'));
  if (deviceLines.length > 0) {
    return `ADB 服务已启动，检测到 ${deviceLines.length} 个 Android 设备。`;
  }
  return 'ADB 服务已启动，当前未检测到 Android 设备。';
}

/**
 * 格式化 ADB 失败原因，保留命令、退出码、超时和 adb 输出。
 *
 * @param {object} diagnostic ADB 诊断结果
 * @returns {string} 失败摘要
 */
function formatAdbDiagnosticFailure(diagnostic) {
  const command = diagnosticLines(diagnostic.command).join(' ');
  const stdout = diagnosticLines(diagnostic.stdout).join('\n');
  const stderr = diagnosticLines(diagnostic.stderr).join('\n');
  const output = [stderr, stdout].filter(Boolean).join('\n');
  const timedOut = diagnostic.timedOut ? '是' : '否';
  return `命令：${command || 'adb devices -l'}\n退出码：${diagnostic.exitCode ?? 'unknown'}，是否超时：${timedOut}\n输出：${output || '无输出'}`;
}

/**
 * 将诊断字段安全转换为字符串数组，避免后端异常响应导致启动页二次报错。
 *
 * @param {unknown} value 诊断字段
 * @returns {string[]} 字符串数组
 */
function diagnosticLines(value) {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.map((line) => String(line));
}

/**
 * 格式化本机 HTTP 请求错误，给用户可执行的本地排查信息。
 *
 * @param {Error & {code?: string}} error 请求错误
 * @returns {string} 用户可读错误
 */
function formatRequestError(error) {
  if (error.code === 'ECONNREFUSED') {
    return `127.0.0.1:${BACKEND_PORT} 后端未监听。`;
  }
  return error.message || String(error);
}

/**
 * 创建主窗口并先加载 Electron 自有启动页。
 */
async function createMainWindow() {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 920,
    minWidth: 1180,
    minHeight: 720,
    title: APP_NAME,
    icon: APP_ICON_PATH,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      preload: path.join(__dirname, 'preload.js'),
    },
  });
  await mainWindow.loadFile(path.join(__dirname, 'splash', 'index.html'));
}

/**
 * 启动完成后切换到业务主页；主页仍由 DevBridge-Front 构建产物提供。
 */
async function loadMainPage() {
  if (!mainWindow || mainWindow.isDestroyed()) {
    return;
  }
  await mainWindow.loadURL(`http://${LOCAL_HOST}:${FRONTEND_PORT}/`);
}

/**
 * 处理后端异常退出；只有本应用启动的后端才需要提示。
 *
 * @param {number|null} code 退出码
 */
function handleBackendExit(code) {
  if (!appQuitting && backendStartedByApp) {
    dialog.showErrorBox(`${APP_NAME} 后端已退出`, `后端进程异常退出，退出码：${code ?? 'unknown'}`);
  }
}

/**
 * 请求本机后端接口。
 *
 * @param {string} requestPath 请求路径
 * @param {number} timeoutMs 超时时间
 * @returns {Promise<{statusCode: number, body: string}>} 响应内容
 */
function httpGet(requestPath, timeoutMs) {
  return new Promise((resolve, reject) => {
    const request = http.get({
      host: LOCAL_HOST,
      port: BACKEND_PORT,
      path: requestPath,
      timeout: timeoutMs,
      headers: {
        [CONTROL_PLANE_TOKEN_HEADER]: controlPlaneToken,
      },
    }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => resolve({
        statusCode: response.statusCode || 0,
        body: Buffer.concat(chunks).toString('utf8'),
      }));
    });
    request.on('timeout', () => request.destroy(new Error('request timeout')));
    request.on('error', reject);
  });
}

/**
 * 检查文件可读性，提前给出可理解的启动错误。
 *
 * @param {string} filePath 文件路径
 * @param {string} message 错误信息
 */
async function assertReadableFile(filePath, message) {
  try {
    await fs.promises.access(filePath, fs.constants.R_OK);
  } catch {
    throw new Error(`${message}: ${filePath}`);
  }
}

/**
 * 延迟指定时间，用于健康检查重试。
 *
 * @param {number} milliseconds 毫秒数
 */
function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

/**
 * 展示启动错误；打包后的客户端不能只把错误留在命令行。
 *
 * @param {Error} error 启动错误
 */
async function showStartupError(error) {
  await dialog.showMessageBox({
    type: 'error',
    title: `${APP_NAME} 启动失败`,
    message: '客户端启动失败',
    detail: error.stack || error.message,
  });
}

/**
 * 停止本应用启动的本地服务，避免退出后残留端口占用。
 */
function shutdownServices() {
  appQuitting = true;
  if (frontendServer) {
    frontendServer.close();
  }
  if (backendProcess && backendStartedByApp && !backendProcess.killed) {
    backendProcess.kill();
  }
}

app.whenReady().then(startApplication);
app.on('before-quit', shutdownServices);
app.on('window-all-closed', () => app.quit());
