/**
 * Electron 开发运行时准备脚本，负责补全二进制并修复 macOS 本地开发签名。
 *
 * by AI.Coding
 */
const childProcess = require('child_process');
const fs = require('fs');
const path = require('path');

const electronRoot = path.dirname(require.resolve('electron/package.json'));
const distRoot = path.join(electronRoot, 'dist');
const appPath = path.join(distRoot, 'Electron.app');

/** 准备当前平台可直接启动的 Electron 运行时。 */
function main() {
  ensureElectronBinary();
  if (process.platform === 'darwin') {
    ensureMacDevelopmentSignature();
  }
}

/** 二进制缺失时调用 Electron 官方安装器，下载过程仍执行内置 SHA-256 校验。 */
function ensureElectronBinary() {
  if (fs.existsSync(electronExecutable())) {
    return;
  }
  const installScript = path.join(electronRoot, 'install.js');
  const mirror = process.env.ELECTRON_MIRROR
    || process.env.npm_config_electron_mirror
    || 'https://npmmirror.com/mirrors/electron/';
  const result = childProcess.spawnSync(process.execPath, [installScript], {
    stdio: 'inherit',
    env: { ...process.env, ELECTRON_MIRROR: mirror },
  });
  if (result.status !== 0 || !fs.existsSync(electronExecutable())) {
    throw new Error('Electron 开发运行时下载失败，请检查网络或 ELECTRON_MIRROR 配置。');
  }
}

/**
 * macOS 新版本会拒绝签名结构不完整的通用 Electron.app；仅对已校验的开发依赖执行本地重签名。
 */
function ensureMacDevelopmentSignature() {
  if (run('codesign', ['--verify', '--deep', '--strict', appPath]).status === 0) {
    return;
  }
  const signed = run('codesign', ['--force', '--deep', '--sign', '-', appPath], true);
  const verified = run('codesign', ['--verify', '--deep', '--strict', appPath]);
  if (signed.status !== 0 || verified.status !== 0) {
    throw new Error('Electron.app 本地开发签名修复失败。');
  }
  console.log('[Ai DevBridge-Electron] Electron.app development signature prepared.');
}

/** 返回当前平台 Electron 可执行文件路径。 */
function electronExecutable() {
  if (process.platform === 'darwin') {
    return path.join(appPath, 'Contents', 'MacOS', 'Electron');
  }
  return path.join(distRoot, process.platform === 'win32' ? 'electron.exe' : 'electron');
}

/** 执行不经过 Shell 的本机命令。 */
function run(command, args, inherit = false) {
  return childProcess.spawnSync(command, args, { stdio: inherit ? 'inherit' : 'ignore' });
}

main();
