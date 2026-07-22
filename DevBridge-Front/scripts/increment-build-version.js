/**
 * DevBridge 构建版本递增脚本。
 *
 * by AI.Coding
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const frontRoot = path.resolve(scriptDir, "..");
const workspaceRoot = path.resolve(frontRoot, "..");
const statePath = path.join(workspaceRoot, "build-version.json");
const frontBuildInfoPath = path.join(frontRoot, "src", "app", "buildInfo.ts");
const serverVersionPath = path.join(workspaceRoot, "DevBridge-Server", "src", "main", "resources", "devbridge-version.properties");
const electronVersionPath = path.join(workspaceRoot, "DevBridge-Electron", "src", "splash", "build-info.js");
const shouldIncrement = process.argv.includes("--increment");

/**
 * 读取并规范化版本状态，避免手工编辑导致构建号异常。
 *
 * @returns {{productName: string, year: number, month: number, buildCount: number}} 版本状态
 */
function readState() {
  if (!fs.existsSync(statePath)) {
    return { productName: "Ai DevBridge", year: 0, month: 0, buildCount: 0 };
  }
  const raw = JSON.parse(fs.readFileSync(statePath, "utf8"));
  return {
    productName: typeof raw.productName === "string" && raw.productName.trim() ? raw.productName : "Ai DevBridge",
    year: Number(raw.year) || 0,
    month: Number(raw.month) || 0,
    buildCount: Number(raw.buildCount) || 0,
  };
}

/**
 * 生成当前构建版本；年月变化时构建次数从 1 重新开始。
 *
 * @param {{productName: string, year: number, month: number, buildCount: number}} state 当前状态
 * @returns {{productName: string, year: number, month: number, buildCount: number, version: string}} 新状态
 */
function nextVersion(state) {
  const now = new Date();
  const year = now.getFullYear();
  const month = now.getMonth() + 1;
  const samePeriod = state.year === year && state.month === month;
  const buildCount = shouldIncrement ? (samePeriod ? state.buildCount + 1 : 1) : state.buildCount;
  return {
    productName: state.productName,
    year,
    month,
    buildCount,
    version: `V${year}.${month}.${String(buildCount).padStart(4, "0")}`,
  };
}

/**
 * 写入 JSON 文件，保持版本状态可审计。
 *
 * @param {string} filePath 目标路径
 * @param {unknown} data JSON 数据
 */
function writeJson(filePath, data) {
  fs.writeFileSync(filePath, `${JSON.stringify(data, null, 2)}\n`);
}

/**
 * 写入文本文件，并确保父目录存在。
 *
 * @param {string} filePath 目标路径
 * @param {string} content 文件内容
 */
function writeText(filePath, content) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, content);
}

const build = nextVersion(readState());
writeJson(statePath, {
  productName: build.productName,
  year: build.year,
  month: build.month,
  buildCount: build.buildCount,
});

writeText(frontBuildInfoPath, `/**
 * Ai DevBridge 前端构建信息，由 scripts/increment-build-version.js 自动生成。
 *
 * by AI.Coding
 */
export const APP_NAME = "${build.productName}";
export const APP_VERSION = "${build.version}";
`);

writeText(serverVersionPath, `# Ai DevBridge 构建版本，由 DevBridge-Front/scripts/increment-build-version.js 自动生成。
app.name=${build.productName}
app.version=${build.version}
`);

writeText(electronVersionPath, `/**
 * Ai DevBridge Electron 启动页构建信息，由前端构建脚本自动生成。
 *
 * by AI.Coding
 */
window.DEVBRIDGE_BUILD_INFO = {
  name: "${build.productName}",
  version: "${build.version}"
};
`);

console.log(`${build.productName} ${build.version}`);
