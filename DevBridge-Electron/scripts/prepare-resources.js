/**
 * DevBridge Electron 资源准备脚本。
 *
 * by AI.Coding
 */
const childProcess = require('child_process');
const fs = require('fs');
const path = require('path');

const projectRoot = path.resolve(__dirname, '..', '..');
const electronRoot = path.resolve(__dirname, '..');
const resourcesRoot = path.join(electronRoot, 'resources');
const FRONTEND_SOURCE_API_BASE = 'http://127.0.0.1:8080';
const ELECTRON_API_BASE = 'http://127.0.0.1:18180';

/**
 * 执行资源准备入口。
 */
function main() {
  cleanResources();
  copyFrontendDist();
  copyBackendJar();
  copyTools();
  createJavaRuntime();
  console.log(`[Ai DevBridge-Electron] resources prepared at ${resourcesRoot}`);
}

/**
 * 清理旧资源，确保客户端包只包含本次构建产物。
 */
function cleanResources() {
  fs.rmSync(resourcesRoot, { recursive: true, force: true });
  fs.mkdirSync(resourcesRoot, { recursive: true });
}

/**
 * 复制前端 Vite 构建产物；Electron 自己托管这些静态文件，不依赖后端静态资源。
 */
function copyFrontendDist() {
  const source = path.join(projectRoot, 'DevBridge-Front', 'dist');
  const target = path.join(resourcesRoot, 'frontend');
  assertDirectory(source, '前端 dist 不存在，请先执行前端构建');
  fs.cpSync(source, target, { recursive: true });
  patchFrontendApiBase(target);
}

/**
 * 修正 Electron 资源中的 API 地址；不修改 Front 源码，只处理客户端打包产物。
 *
 * @param {string} frontendRoot Electron 内前端资源目录
 */
function patchFrontendApiBase(frontendRoot) {
  const patchedFiles = [];
  for (const filePath of walkFiles(frontendRoot)) {
    if (!isTextAsset(filePath)) {
      continue;
    }
    const content = fs.readFileSync(filePath, 'utf8');
    if (!content.includes(FRONTEND_SOURCE_API_BASE)) {
      continue;
    }
    // 前端源码仍固定 8080；Electron 运行态后端使用 18180，必须在资源阶段改写。
    fs.writeFileSync(filePath, content.replaceAll(FRONTEND_SOURCE_API_BASE, ELECTRON_API_BASE));
    patchedFiles.push(path.relative(frontendRoot, filePath));
  }
  if (patchedFiles.length === 0) {
    throw new Error(`前端构建产物中没有找到 ${FRONTEND_SOURCE_API_BASE}，请检查 Front API 地址是否已变化。`);
  }
  console.log(`[Ai DevBridge-Electron] frontend API base patched: ${patchedFiles.join(', ')}`);
}

/**
 * 遍历目录内全部文件；资源准备阶段文件量可控，使用同步遍历保持脚本简单可靠。
 *
 * @param {string} directory 目录路径
 * @returns {string[]} 文件路径列表
 */
function walkFiles(directory) {
  const entries = fs.readdirSync(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const filePath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...walkFiles(filePath));
    } else if (entry.isFile()) {
      files.push(filePath);
    }
  }
  return files;
}

/**
 * 判断是否为需要替换 API 地址的文本资源。
 *
 * @param {string} filePath 文件路径
 * @returns {boolean} 文本资源返回 true
 */
function isTextAsset(filePath) {
  return ['.html', '.js', '.css', '.map'].includes(path.extname(filePath).toLowerCase());
}

/**
 * 复制 Spring Boot fat jar，并统一重命名，避免主进程依赖版本号。
 */
function copyBackendJar() {
  const source = path.join(projectRoot, 'DevBridge-Server', 'target', 'devbridge-server-0.1.0-SNAPSHOT.jar');
  const targetDirectory = path.join(resourcesRoot, 'backend');
  assertFile(source, '后端 jar 不存在，请先执行后端打包');
  fs.mkdirSync(targetDirectory, { recursive: true });
  fs.copyFileSync(source, path.join(targetDirectory, 'devbridge-server.jar'));
}

/**
 * 复制内置设备工具。downloads 目录只用于源码侧留档，客户端运行不需要携带。
 */
function copyTools() {
  const source = path.join(projectRoot, 'DevBridge-Server', 'tools');
  const target = path.join(resourcesRoot, 'tools');
  assertDirectory(source, '后端 tools 目录不存在');
  fs.cpSync(source, target, {
    recursive: true,
    filter: (entry) => !entry.includes(`${path.sep}downloads${path.sep}`) && !entry.endsWith(`${path.sep}downloads`),
  });
}

/**
 * 使用本机 JDK 生成精简运行时。若环境没有 jlink，则客户端会回退使用系统 java。
 */
function createJavaRuntime() {
  const target = path.join(resourcesRoot, 'jre');
  const jlink = findExecutable('jlink');
  if (!jlink) {
    console.warn('[Ai DevBridge-Electron] jlink not found, packaged app will use system java.');
    return;
  }
  const result = childProcess.spawnSync(jlink, [
    '--add-modules',
    'ALL-MODULE-PATH',
    '--strip-debug',
    '--no-header-files',
    '--no-man-pages',
    '--output',
    target,
  ], { stdio: 'inherit' });
  if (result.status !== 0) {
    console.warn('[Ai DevBridge-Electron] jlink failed, packaged app will use system java.');
    fs.rmSync(target, { recursive: true, force: true });
  }
}

/**
 * 查找命令路径；构建脚本只做最小路径判断，不执行 shell 拼接。
 *
 * @param {string} name 命令名
 * @returns {string} 可执行路径
 */
function findExecutable(name) {
  const pathValue = process.env.PATH || '';
  const suffix = process.platform === 'win32' ? '.exe' : '';
  for (const directory of pathValue.split(path.delimiter)) {
    const candidate = path.join(directory, `${name}${suffix}`);
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }
  return '';
}

/**
 * 断言目录存在。
 *
 * @param {string} directory 目录路径
 * @param {string} message 错误信息
 */
function assertDirectory(directory, message) {
  if (!fs.existsSync(directory) || !fs.statSync(directory).isDirectory()) {
    throw new Error(`${message}: ${directory}`);
  }
}

/**
 * 断言文件存在。
 *
 * @param {string} filePath 文件路径
 * @param {string} message 错误信息
 */
function assertFile(filePath, message) {
  if (!fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
    throw new Error(`${message}: ${filePath}`);
  }
}

main();
