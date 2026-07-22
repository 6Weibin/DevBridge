/**
 * DevBridge H5 PoC 主界面，只展示本机 Spring Boot API 返回的真实设备数据。
 *
 * by AI.Coding
 */
import React, { useState, useEffect, useRef, useCallback } from "react";
import {
  Smartphone, Apple, Cpu, Folder, FolderOpen, File,
  RefreshCw, Download, Play, Pause, Trash2, Search,
  ChevronRight, ChevronDown, CheckCircle, XCircle, WifiOff,
  Sun, Moon, Monitor, AlertTriangle, Info, HardDrive,
  Zap, Battery, Layers, Terminal, PlugZap, Package, Pencil, Copy,
} from "lucide-react";
import { APP_NAME, APP_VERSION } from "./buildInfo";
import { AiAssistantShell } from "./ai/AiAssistantShell";
import { AiDeviceContext, AiLogLine as AiAssistantLogLine } from "./ai/aiTypes";

// ─── Theme hook ──────────────────────────────────────────────────────────────

type ThemeMode = "light" | "dark" | "system";

function useTheme() {
  const [mode, setMode] = useState<ThemeMode>(() => (localStorage.getItem("theme") as ThemeMode) || "system");
  const [resolved, setResolved] = useState<"light" | "dark">("light");

  useEffect(() => {
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const update = () => setResolved(mode === "system" ? (mq.matches ? "dark" : "light") : mode);
    update();
    mq.addEventListener("change", update);
    return () => mq.removeEventListener("change", update);
  }, [mode]);

  useEffect(() => {
    document.documentElement.classList.toggle("dark", resolved === "dark");
  }, [resolved]);

  const setTheme = (m: ThemeMode) => { setMode(m); localStorage.setItem("theme", m); };
  return { mode, resolved, setTheme };
}

// ─── Types ───────────────────────────────────────────────────────────────────

type Platform = "android" | "ios" | "harmony";
type DeviceStatus = "connected" | "unauthorized" | "offline";
type Tab = "info" | "files" | "logs" | "apps";
type PreviewKind = "image" | "video";

interface Device {
  id: string; serial: string; model: string; platform: Platform;
  osVersion: string; status: DeviceStatus; battery?: number;
  resolution?: string; cpu?: string; storage?: string;
  // Android
  brand?: string; apiLevel?: string; gpu?: string; ram?: string;
  density?: string; buildFingerprint?: string; securityPatch?: string;
  bootloader?: string; kernelVersion?: string; imei?: string; baseband?: string;
  // iOS
  deviceName?: string; buildNumber?: string; ecid?: string; activationState?: string;
  modelIdentifier?: string; cpuArchitecture?: string; hardwareModel?: string;
  hardwarePlatform?: string; deviceClass?: string; modelNumber?: string; nfcSupport?: boolean;
  // HarmonyOS
  hdcSerial?: string; harmonyApiVersion?: string; buildType?: string;
  emui?: string; hiSiliconSoc?: string;
}
interface FileNode {
  name: string; path: string; type: "dir" | "file";
  size?: string; sizeBytes?: number;
  modified?: string; created?: string; accessed?: string;
  permissions?: string; owner?: string; group?: string;
  mimeType?: string; md5?: string; inode?: number;
  children?: FileNode[];
}
interface LogLine {
  id: number; timestamp: string; level: "V" | "D" | "I" | "W" | "E" | "F";
  tag: string; pid: string; message: string;
}
interface InstalledApp {
  name: string; packageName: string; versionName: string; versionCode: string; systemApp: boolean;
}
interface AppDetail {
  name: string; packageName: string; versionName: string; versionCode: string;
  uid: string; minSdk: string; targetSdk: string;
  firstInstallTime: string; lastUpdateTime: string; installerPackageName: string;
  codePath: string; resourcePath: string; dataDir: string;
  systemApp: boolean; enabledState: string;
  installed: boolean; hidden: boolean; stopped: boolean; suspended: boolean;
  requestedPermissions: string[]; grantedPermissions: string[];
}
interface AppContextMenu {
  app: InstalledApp; x: number; y: number;
}
interface FileContextMenu {
  file: FileNode; x: number; y: number;
}
type LogLevelFilter = "ALL" | LogLine["level"];
type LogBuckets = Record<LogLevelFilter, LogLine[]>;
type DirectoryAccessState = "empty" | "inaccessible";
interface ToolStatus {
  name: string; command: string; available: boolean; path: string; version: string; message: string;
}
interface RuntimeEnvironment {
  osName: string; osArch: string; toolDirectoryName: string; bundledToolRoot: string; javaVersion: string;
  appName: string; appVersion: string;
}
interface ApiError {
  code: string; message: string; detail: string; timestamp: string;
}
/**
 * 设备截图快照缓存；截图属于设备上下文，页签切换时不能跟随面板卸载而丢失。
 */
interface ScreenshotSnapshot {
  url: string; time: string; error: string;
}

// ─── Constants ───────────────────────────────────────────────────────────────

const PLT_ICON: Record<Platform, React.ReactNode> = {
  android: <Smartphone size={14}/>, ios: <Apple size={14}/>, harmony: <Cpu size={14}/>,
};
const PLT_LABEL: Record<Platform, string> = { android:"Android", ios:"iOS", harmony:"HarmonyOS" };
const TAB_LABEL: Record<Tab, string> = { info:"设备信息", files:"文件管理", logs:"实时日志", apps:"应用管理" };
const PLT_COLOR: Record<Platform, string> = {
  android:"text-emerald-500 bg-emerald-500/10",
  ios:"text-blue-500 bg-blue-500/10",
  harmony:"text-amber-500 bg-amber-500/10",
};
const STATUS_DOT: Record<DeviceStatus, string> = {
  connected:"bg-emerald-500", unauthorized:"bg-amber-400", offline:"bg-zinc-400",
};
const LOG_COLOR: Record<LogLine["level"], string> = {
  V:"text-muted-foreground", D:"text-blue-500 dark:text-blue-400",
  I:"text-foreground/80", W:"text-amber-500 dark:text-amber-400",
  E:"text-red-500 dark:text-red-400", F:"text-red-600 font-semibold",
};
const API_BASE = "http://127.0.0.1:8080";
const DEVICE_REFRESH_INTERVAL_MS = 3000;
const LOG_RENDER_LIMIT = 1000;
const LOG_FLUSH_INTERVAL_MS = 120;
const LOG_FLUSH_BATCH_SIZE = 80;
const LOG_TOOL_ERROR_EVENT = "tool-error";
const LOG_LEVELS = ["ALL","V","D","I","W","E","F"] as const;
const IMAGE_EXTENSIONS = new Set(["jpg", "jpeg", "png", "gif", "webp", "bmp"]);
const VIDEO_EXTENSIONS = new Set(["mp4", "webm", "mov", "m4v", "3gp"]);
const SCREENSHOT_PANEL_WIDTH_PX = 360;
const SCREENSHOT_FRAME_MAX_WIDTH_PX = 260;
const SCREENSHOT_FRAME_HEIGHT_OFFSET_PX = 230;
const WAITING_DEVICE: Device = {
  id: "__waiting__",
  serial: "—",
  model: "等待设备连接",
  platform: "android",
  osVersion: "",
  status: "offline",
};
const EMPTY_TOOL_ROWS: { n: string; ok: boolean; detail: string }[] = [];

// ─── API helpers ─────────────────────────────────────────────────────────────

/**
 * 请求后端 JSON 接口；这里集中处理 HTTP 状态，避免各处重复判断。
 */
async function fetchJson<T>(path: string): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, { headers: { Accept: "application/json" } });
  if (!response.ok) {
    throw await apiError(response);
  }
  return response.json() as Promise<T>;
}

/**
 * 将后端统一错误响应转换成 Error，前端只展示用户可读摘要。
 */
async function apiError(response: Response): Promise<Error> {
  try {
    const payload = await response.json() as ApiError;
    const summary = payload.message || payload.code || `HTTP ${response.status}`;
    const detail = payload.detail && payload.detail !== summary ? `：${payload.detail}` : "";
    return new Error(`${summary}${detail}`);
  } catch {
    return new Error(`HTTP ${response.status}`);
  }
}

/**
 * 将后端设备模型补齐为前端详情页需要的展示字段。
 */
function normalizeDevice(device: Device): Device {
  return {
    ...device,
    brand: device.brand ?? (device.platform === "android" ? "Android" : device.platform === "harmony" ? "HarmonyOS" : "Apple"),
    osVersion: device.osVersion || (device.platform === "android" ? "Android" : device.platform === "harmony" ? "HarmonyOS" : "iOS"),
    battery: device.battery,
    resolution: device.resolution ?? "—",
  };
}

/**
 * 判断是否为设备列表接口返回的平台占位型号；占位值不能覆盖详情接口读取到的真实型号。
 */
function isGenericModel(device: Device): boolean {
  return ["Android Device", "iOS Device", "HarmonyOS Device", "等待设备连接"].includes(device.model);
}

/**
 * 判断是否为前端补齐的平台占位系统版本；占位版本不能覆盖详情接口读取到的真实版本。
 */
function isGenericOsVersion(device: Device): boolean {
  return device.osVersion === PLT_LABEL[device.platform] || device.osVersion === "";
}

/**
 * 判断是否为前端补齐的平台占位品牌；占位品牌不能覆盖详情接口读取到的真实厂商品牌。
 */
function isGenericBrand(device: Device): boolean {
  return ["Android", "HarmonyOS"].includes(device.brand ?? "");
}

/**
 * 合并轮询文本字段；新值只是占位且旧值更完整时，保留旧值避免列表和详情来回闪烁。
 */
function stableSnapshotText(previous: Device | undefined, snapshot: Device, field: "model" | "osVersion"): string {
  const previousValue = previous?.[field] ?? "";
  const nextValue = snapshot[field] ?? "";
  const nextIsGeneric = field === "model" ? isGenericModel(snapshot) : isGenericOsVersion(snapshot);
  if (previousValue && nextIsGeneric) {
    return previousValue;
  }
  return nextValue || previousValue;
}

/**
 * 合并品牌字段；列表轮询只有平台占位品牌时，保留详情页已读取到的真实品牌。
 */
function stableSnapshotBrand(previous: Device | undefined, snapshot: Device): string | undefined {
  const previousValue = previous?.brand ?? "";
  const nextValue = snapshot.brand ?? "";
  if (previousValue && isGenericBrand(snapshot)) {
    return previousValue;
  }
  return nextValue || previousValue || undefined;
}

/**
 * 判断两个设备对象是否等价；轮询数据未变化时复用旧引用，避免 React 重渲染造成页面闪烁。
 */
function sameDevice(left: Device | undefined, right: Device | undefined): boolean {
  if (left === right) return true;
  if (!left || !right) return false;
  const keys = new Set<keyof Device>([
    ...Object.keys(left) as (keyof Device)[],
    ...Object.keys(right) as (keyof Device)[],
  ]);
  for (const key of keys) {
    if (left[key] !== right[key]) {
      return false;
    }
  }
  return true;
}

/**
 * 判断设备列表是否等价；列表顺序和设备内容均一致时不触发列表刷新。
 */
function sameDeviceList(left: Device[], right: Device[]): boolean {
  return left.length === right.length && left.every((device, index) => sameDevice(device, right[index]));
}

/**
 * 将后端日志级别限制在前端表格支持的集合内，避免异常数据破坏渲染。
 */
function normalizeLog(line: LogLine): LogLine {
  const allowed: LogLine["level"][] = ["V", "D", "I", "W", "E", "F"];
  return {
    ...line,
    level: allowed.includes(line.level) ? line.level : "I",
  };
}

/**
 * 创建按日志级别分桶的缓存；每个级别独立保留最近 1000 行，避免低频级别被总量裁剪挤掉。
 */
function createLogBuckets(): LogBuckets {
  return LOG_LEVELS.reduce((buckets, level) => {
    buckets[level] = [];
    return buckets;
  }, {} as LogBuckets);
}

/**
 * 获取指定设备的日志缓存；日志按设备隔离，避免切换设备时把旧设备输出混入当前页面。
 */
function ensureLogBuckets(bucketsByDevice: Record<string, LogBuckets>, deviceKey: string): LogBuckets {
  if (!bucketsByDevice[deviceKey]) {
    bucketsByDevice[deviceKey] = createLogBuckets();
  }
  return bucketsByDevice[deviceKey];
}

/**
 * 向总缓存和对应级别缓存写入日志；分桶裁剪是为了切换级别后仍能看到该级别最近 1000 行。
 */
function appendLogToBuckets(buckets: LogBuckets, line: LogLine) {
  appendLogToBucket(buckets.ALL, line);
  appendLogToBucket(buckets[line.level], line);
}

/**
 * 保持单个缓存桶大小稳定，避免高频日志持续增长占用内存。
 */
function appendLogToBucket(bucket: LogLine[], line: LogLine) {
  bucket.push(line);
  if (bucket.length > LOG_RENDER_LIMIT) {
    bucket.splice(0, bucket.length - LOG_RENDER_LIMIT);
  }
}

/**
 * 生成日志采集设备 key；实时日志必须绑定到当前选中设备，避免切换设备后继续显示旧设备输出。
 */
function logDeviceKey(device: Device): string {
  return `${device.platform}:${device.serial}`;
}

/**
 * 判断当前设备是否支持屏幕截图；当前后端只实现 Android ADB 截图能力。
 */
function supportsDeviceScreenshot(device: Device): boolean {
  return device.platform === "android";
}

/**
 * 解析设备分辨率；后端和工具输出可能使用 x、X 或 ×，前端必须兜底为稳定比例，避免截图加载前手机框高度塌陷。
 */
function parseScreenResolution(resolution?: string): { width: number; height: number } {
  const match = resolution?.match(/(\d{2,5})\s*[xX×]\s*(\d{2,5})/);
  const width = Number(match?.[1]);
  const height = Number(match?.[2]);
  if (width > 0 && height > 0) {
    return { width, height };
  }
  return { width: 9, height: 19.5 };
}

/**
 * 构造设备维度的目录状态 key，避免不同设备相同路径的空目录/不可访问状态互相污染。
 */
function directoryStateKey(device: Device, path: string): string {
  return `${logDeviceKey(device)}:${path}`;
}

/**
 * 判断目录是否已知为空或不可访问；只依据真实接口探测后的目录状态缓存。
 */
function directoryAccessState(node: FileNode, states: Record<string, DirectoryAccessState>, device: Device): DirectoryAccessState | null {
  if (node.type !== "dir") return null;
  return states[directoryStateKey(device, node.path)] ?? null;
}

/**
 * 生成目录状态提示文案，供文件区提示和节点 title 复用。
 */
function directoryAccessTip(state: DirectoryAccessState, path: string): string {
  return state === "empty" ? `目录为空：${path}` : `目录不可访问或无权限查看：${path}`;
}

/**
 * 拆分路径显示片段；长路径只省略中间/前置部分，最后一级目录必须完整保留。
 */
function splitDisplayPath(path: string): { prefix: string; leaf: string } {
  if (path === "/") return { prefix: "", leaf: "/" };
  const normalized = path.endsWith("/") ? path.slice(0, -1) : path;
  const lastSlash = normalized.lastIndexOf("/");
  if (lastSlash <= 0) return { prefix: "", leaf: normalized };
  return {
    prefix: normalized.slice(0, lastSlash + 1),
    leaf: normalized.slice(lastSlash + 1),
  };
}

/**
 * 将后端远端文件模型补齐为前端文件树展示字段。
 */
function normalizeFile(node: FileNode): FileNode {
  return {
    ...node,
    mimeType: node.mimeType ?? inferMimeType(node.name),
    size: node.size ?? (node.sizeBytes !== undefined ? `${(node.sizeBytes / 1024).toFixed(1)} KB` : undefined),
    children: node.children ? sortFileNodes(node.children.map(normalizeFile)) : undefined,
  };
}

/**
 * 按文件树展示规则排序：目录优先，同类型按名称升序，避免切换目录时列表顺序跳动。
 */
function sortFileNodes(nodes: FileNode[]): FileNode[] {
  return [...nodes].sort((left, right) => {
    if (left.type !== right.type) {
      return left.type === "dir" ? -1 : 1;
    }
    return left.name.localeCompare(right.name, "zh-Hans-CN", { numeric: true, sensitivity: "base" });
  });
}

/**
 * 按应用名称和包名排序，确保刷新后列表位置稳定。
 */
function sortInstalledApps(apps: InstalledApp[]): InstalledApp[] {
  return [...apps].sort((left, right) => {
    const nameCompare = left.name.localeCompare(right.name, "zh-Hans-CN", { numeric: true, sensitivity: "base" });
    return nameCompare === 0
      ? left.packageName.localeCompare(right.packageName, "zh-Hans-CN", { numeric: true, sensitivity: "base" })
      : nameCompare;
  });
}

/**
 * 统一详情字段的空值展示，避免弹窗出现空白单元格。
 */
function detailText(value: string | boolean | undefined): string {
  if (typeof value === "boolean") return value ? "是" : "否";
  if (!value || !value.trim() || value.trim().toLowerCase() === "null") return "—";
  return value;
}

/**
 * 提取远端文件扩展名，文件列表没有 MIME 时用于前端预览类型判断。
 */
function fileExtension(name: string): string {
  const index = name.lastIndexOf(".");
  return index >= 0 ? name.slice(index + 1).toLowerCase() : "";
}

/**
 * 根据扩展名推断常见图片/视频 MIME，避免后端 ls 输出缺失 MIME 时无法预览。
 */
function inferMimeType(name: string): string | undefined {
  const extension = fileExtension(name);
  if (["jpg", "jpeg"].includes(extension)) return "image/jpeg";
  if (extension === "png") return "image/png";
  if (extension === "gif") return "image/gif";
  if (extension === "webp") return "image/webp";
  if (extension === "bmp") return "image/bmp";
  if (extension === "mp4") return "video/mp4";
  if (extension === "webm") return "video/webm";
  if (extension === "mov") return "video/quicktime";
  if (extension === "m4v") return "video/x-m4v";
  if (extension === "3gp") return "video/3gpp";
  return undefined;
}

/**
 * 判断文件是否支持右侧预览；视频只返回预览类型，不触发自动加载。
 */
function previewKind(file: FileNode | null): PreviewKind | null {
  if (!file || file.type !== "file") return null;
  const extension = fileExtension(file.name);
  if ((file.mimeType?.startsWith("image/") || IMAGE_EXTENSIONS.has(extension))) return "image";
  if ((file.mimeType?.startsWith("video/") || VIDEO_EXTENSIONS.has(extension))) return "video";
  return null;
}

/**
 * 合并设备轮询快照；轮询只更新连接状态和头部基础字段，保留详情页、电量、日志和文件上下文。
 */
function mergeDeviceSnapshot(previous: Device | undefined, snapshot: Device): Device {
  const normalized = normalizeDevice(snapshot);
  return {
    ...previous,
    ...normalized,
    // 设备列表接口只负责连接状态快照，不能用占位型号/系统版本覆盖详情接口的真实值。
    brand: stableSnapshotBrand(previous, normalized),
    model: stableSnapshotText(previous, normalized, "model"),
    osVersion: stableSnapshotText(previous, normalized, "osVersion"),
    battery: normalized.battery ?? previous?.battery,
    resolution: previous?.resolution ?? normalized.resolution,
    storage: previous?.storage ?? normalized.storage,
  };
}

// ─── Small components ─────────────────────────────────────────────────────────

/**
 * 构造远端媒体预览地址，复用后端受控 preview 接口而不是下载接口。
 */
function previewFileUrl(device: Device, file: FileNode): string {
  return `${API_BASE}/api/devices/${device.platform}/${encodeURIComponent(device.serial)}/files/preview?path=${encodeURIComponent(file.path)}`;
}

/**
 * 文件详情媒体预览区；视频必须点击播放后才加载，避免选中文件时自动拉取大视频。
 */
function FilePreviewPanel({ device, file, online }: { device: Device; file: FileNode; online: boolean }) {
  const kind = previewKind(file);
  if (!kind || !online || device.platform !== "android") {
    return null;
  }
  const src = previewFileUrl(device, file);
  return (
    <section>
      <p className="text-[10px] font-semibold uppercase tracking-widest text-muted-foreground mb-2">预览</p>
      {kind === "image" ? (
        <div className="border border-border bg-black/3 dark:bg-white/4 rounded-lg overflow-hidden">
          <img src={src} alt={file.name} loading="lazy" className="w-full max-h-80 object-contain bg-black/5 dark:bg-black/20"/>
        </div>
      ) : (
        <div className="border border-border bg-black/3 dark:bg-white/4 rounded-lg overflow-hidden">
          <video src={src} controls preload="none" className="w-full max-h-80 bg-black"/>
        </div>
      )}
    </section>
  );
}

function PlatformChip({ platform }: { platform: Platform }) {
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-medium ${PLT_COLOR[platform]}`}>
      {PLT_ICON[platform]}{PLT_LABEL[platform]}
    </span>
  );
}

function BatteryStrip({ v }: { v: number }) {
  const c = v > 40 ? "bg-emerald-500" : v > 15 ? "bg-amber-400" : "bg-red-500";
  return (
    <div className="flex items-center gap-1.5">
      <div className="w-20 h-1 rounded-full bg-black/10 dark:bg-white/10 overflow-hidden">
        <div className={`h-full rounded-full ${c}`} style={{width:`${v}%`}}/>
      </div>
      <span className="text-[11px] text-muted-foreground">{v}%</span>
    </div>
  );
}

function ThemeToggle({ mode, setTheme }: { mode: ThemeMode; setTheme:(m:ThemeMode)=>void }) {
  const opts: {m: ThemeMode; icon: React.ReactNode; tip: string}[] = [
    { m:"light",  icon:<Sun size={12}/>,     tip:"浅色" },
    { m:"dark",   icon:<Moon size={12}/>,    tip:"深色" },
    { m:"system", icon:<Monitor size={12}/>, tip:"跟随系统" },
  ];
  return (
    <div className="flex items-center gap-0.5 p-0.5 rounded-lg bg-black/6 dark:bg-white/8">
      {opts.map(o=>(
        <button key={o.m} title={o.tip} onClick={()=>setTheme(o.m)}
          className={`p-1.5 rounded-md transition-all duration-150
            ${mode===o.m ? "bg-white dark:bg-white/15 shadow-sm text-foreground" : "text-muted-foreground hover:text-foreground"}`}>
          {o.icon}
        </button>
      ))}
    </div>
  );
}

// ─── PropList & InfoSection ───────────────────────────────────────────────────

function PropList({ rows }: { rows: { k: string; v: string; mono: boolean }[] }) {
  return (
    <div className="divide-y divide-border/35">
      {rows.map(r => (
        <div key={r.k} className="flex items-start gap-5 py-2">
          <span className="w-20 shrink-0 text-[11px] text-muted-foreground leading-relaxed pt-px">{r.k}</span>
          <span className={`text-[12px] break-all leading-relaxed flex-1 ${r.mono ? "font-mono text-foreground/65" : "text-foreground/85"}`}>{r.v}</span>
        </div>
      ))}
    </div>
  );
}

function InfoSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <p className="text-[10px] font-semibold uppercase tracking-widest text-muted-foreground mb-2">{title}</p>
      {children}
    </section>
  );
}

// ─── Screenshot Panel ─────────────────────────────────────────────────────────

interface ScreenshotPanelProps {
  device: Device;
  backendOnline: boolean;
  autoCaptureKey: string;
  snapshot?: ScreenshotSnapshot;
  onAutoCapture: (deviceKey: string) => void;
  onSnapshot: (deviceKey: string, snapshot: ScreenshotSnapshot) => void;
}

function ScreenshotPanel({ device, backendOnline, autoCaptureKey, snapshot, onAutoCapture, onSnapshot }: ScreenshotPanelProps) {
  const [capturing, setCapturing] = useState(false);
  const deviceKey = logDeviceKey(device);
  const shotUrl = snapshot?.url || "";
  const shotTime = snapshot?.time || "";
  const error = snapshot?.error || "";

  /**
   * 从后端截取当前设备屏幕；仅使用受控 REST 接口，避免前端拼接本机命令。
   */
  const capture = useCallback(async () => {
    if (!backendOnline) {
      onSnapshot(deviceKey, {url: shotUrl, time: shotTime, error: "本机后端未连接，无法截图"});
      return;
    }
    if (device.status !== "connected") {
      onSnapshot(deviceKey, {url: shotUrl, time: shotTime, error: "设备已断开，无法截图"});
      return;
    }
    setCapturing(true);
    onSnapshot(deviceKey, {url: shotUrl, time: shotTime, error: ""});
    try {
      const response = await fetch(`${API_BASE}/api/devices/${device.platform}/${encodeURIComponent(device.serial)}/screenshot`);
      if (!response.ok) throw await apiError(response);
      const objectUrl = URL.createObjectURL(await response.blob());
      onSnapshot(deviceKey, {url: objectUrl, time: new Date().toLocaleString("zh-CN", { hour12: false }), error: ""});
    } catch (captureError) {
      onSnapshot(deviceKey, {
        url: shotUrl,
        time: shotTime,
        error: captureError instanceof Error ? captureError.message : "设备截图失败",
      });
    } finally {
      setCapturing(false);
    }
  },[backendOnline, device.platform, device.serial, device.status, deviceKey, onSnapshot, shotTime, shotUrl]);

  useEffect(()=>{
    if (!autoCaptureKey) return;
    // 自动截图只在当前设备首次进入信息页时触发一次，避免轮询刷新导致重复调用截图接口。
    onAutoCapture(autoCaptureKey);
    capture();
  },[autoCaptureKey, capture, onAutoCapture]);

  /**
   * 保存当前截图到本地；截图 URL 来自后端返回的 PNG Blob。
   */
  const saveShot = () => {
    if (!shotUrl) return;
    const link = document.createElement("a");
    link.href = shotUrl;
    link.download = `screenshot-${device.serial}.png`;
    link.click();
  };

  // 按设备分辨率计算手机框比例；截图未返回前也要有稳定占位尺寸。
  const screenSize = parseScreenResolution(device.resolution);
  const screenAspect = screenSize.width / screenSize.height;
  const isLandscape = screenAspect > 1;

  return (
    <aside
      className="border-l border-border/50 flex flex-col bg-white/30 dark:bg-white/[0.03] backdrop-blur-sm"
      style={{flex:`0 0 ${SCREENSHOT_PANEL_WIDTH_PX}px`, width: SCREENSHOT_PANEL_WIDTH_PX}}
    >
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-border/40">
        <p className="text-[10px] font-semibold uppercase tracking-widest text-muted-foreground">屏幕截图</p>
        <button
          onClick={capture}
          disabled={capturing || !backendOnline || device.status !== "connected"}
          className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-primary text-white text-[11px] font-medium hover:opacity-90 disabled:opacity-50 transition-all"
        >
          <RefreshCw size={10} className={capturing ? "animate-spin" : ""} />
          {capturing ? "截取中…" : "截图"}
        </button>
      </div>

      {/* Preview：严格按照设备分辨率比例，在容器内等比缩放 */}
      <div className="flex-1 flex flex-col items-center justify-center gap-3 p-4 overflow-hidden">
        {/*
          使用 CSS object-fit 思路：外层容器铺满可用空间，
          内层 div 通过 max-width + max-height + aspect-ratio 严格保持分辨率比例，
          浏览器自动在两个维度中取较小值来约束尺寸。
        */}
        <div className="flex-1 w-full flex items-center justify-center overflow-hidden">
          <div
            className="relative rounded-[18px] overflow-hidden shadow-2xl ring-1 ring-black/12 dark:ring-white/10 bg-zinc-900"
            style={{
              aspectRatio: `${screenSize.width} / ${screenSize.height}`,
              maxWidth: "100%",
              maxHeight: "100%",
              width: isLandscape
                ? "100%"
                : `min(100%, ${SCREENSHOT_FRAME_MAX_WIDTH_PX}px, calc((100vh - ${SCREENSHOT_FRAME_HEIGHT_OFFSET_PX}px) * ${screenAspect}))`,
            }}
          >
            {/* Dynamic Island（iOS）*/}
            {device.platform === "ios" && (
              <div className="absolute top-[3%] left-1/2 -translate-x-1/2 z-10"
                style={{width:"30%", height:"4.5%", background:"#000", borderRadius:"999px"}}/>
            )}
            {/* 打孔摄像头（Android / HarmonyOS）*/}
            {device.platform !== "ios" && (
              <div className="absolute top-[2.5%] left-1/2 -translate-x-1/2 z-10 rounded-full bg-black/80"
                style={{width:"2.8%", aspectRatio:"1/1"}}/>
            )}

            {/* 截图内容 */}
            {shotUrl ? (
              <img
                src={shotUrl}
                alt={`${device.model} 屏幕截图`}
                className={`w-full h-full object-contain transition-opacity duration-500 ${capturing ? "opacity-20" : "opacity-100"}`}
              />
            ) : (
              <div className="w-full h-full flex items-center justify-center bg-zinc-800">
                {capturing
                  ? <RefreshCw size={18} className="text-zinc-500 animate-spin" />
                  : <Smartphone size={24} className="text-zinc-600" />}
              </div>
            )}

            {/* 扫描线动画 */}
            {capturing && (
              <div className="absolute inset-0 overflow-hidden pointer-events-none">
                <div
                  className="absolute left-0 right-0 h-px bg-primary/80 shadow-[0_0_10px_2px_theme(colors.blue.400)]"
                  style={{ animation: "scanline 1.4s linear forwards" }}
                />
              </div>
            )}
          </div>
        </div>

        {/* 元信息 + 保存 */}
        <div className="shrink-0 flex flex-col items-center gap-1">
          <p className="text-[11px] font-mono text-muted-foreground">{device.resolution ?? "—"}</p>
          <p className="text-[10px] text-muted-foreground/50">{shotTime || "尚未截图"}</p>
          {error && <p className="max-w-[240px] text-center text-[10px] text-red-500">{error}</p>}
          {shotUrl && (
            <button onClick={saveShot} className="mt-1 flex items-center gap-1.5 text-[11px] text-muted-foreground hover:text-foreground transition-colors">
              <Download size={11} />保存到本地
            </button>
          )}
        </div>
      </div>

      {/* Scanline keyframe */}
      <style>{`
        @keyframes scanline {
          from { top: 0%; }
          to   { top: 100%; }
        }
      `}</style>
    </aside>
  );
}

// ─── File tree ────────────────────────────────────────────────────────────────

function TreeNode({ node, depth=0, onSelect, onOpenDir, onContextMenu, sel, directoryStates, device }: {
  node:FileNode; depth?:number; onSelect:(n:FileNode)=>void; onOpenDir:(n:FileNode)=>void;
  onContextMenu:(event:React.MouseEvent,n:FileNode)=>void; sel:string|null;
  directoryStates: Record<string, DirectoryAccessState>; device: Device;
}) {
  const [open, setOpen] = useState(false);
  const isDir = node.type==="dir";
  // 箭头只表达本地树节点展开状态；远端目录进入会替换列表，不能把顶层目录默认视为已展开。
  const hasNestedChildren = isDir && Boolean(node.children?.length);
  const expanded = open && hasNestedChildren;
  const active = sel===node.path;
  const accessState = directoryAccessState(node, directoryStates, device);
  const accessTip = accessState ? directoryAccessTip(accessState, node.path) : "";

  /**
   * 目录进入动作独立于选中动作，避免单击目录时直接跳转而无法查看目录信息。
   */
  const openDirectory = () => {
    if (!isDir) return;
    // 已知空目录或不可访问目录不再展开，只给出明确提示，避免用户误以为点击无效。
    if (!accessState && hasNestedChildren) setOpen(p=>!p);
    onOpenDir(node);
  };

  return (
    <div>
      <div
        onContextMenu={event=>onContextMenu(event, node)}
        className={`w-full flex items-center gap-1 rounded-lg text-[12px] transition-all duration-100
          ${active ? "bg-primary/12 text-primary font-medium" : "text-foreground/75 hover:bg-black/5 dark:hover:bg-white/6 hover:text-foreground"}`}
        style={{paddingLeft:`${10+depth*16}px`}}>
        {isDir
          ? (
            <button
              type="button"
              title={accessTip || "进入目录"}
              onClick={openDirectory}
              className={`w-5 h-7 flex items-center justify-center shrink-0 hover:text-foreground ${accessState ? "text-muted-foreground/35" : "text-muted-foreground"}`}
            >
              {expanded?<ChevronDown size={12}/>:<ChevronRight size={12}/>}
            </button>
          )
          : <span className="w-3 shrink-0"/>}
        <button
          type="button"
          title={accessTip || node.path}
          onClick={()=>onSelect(node)}
          onDoubleClick={openDirectory}
          className={`min-w-0 flex-1 flex items-center gap-2 py-1.5 pr-2 text-left ${accessState ? "text-muted-foreground/45" : ""}`}
        >
          {isDir
            ? (expanded
              ? <FolderOpen size={13} className={`shrink-0 ${accessState ? "text-amber-400/30" : "text-amber-400"}`}/>
              : <Folder size={13} className={`shrink-0 ${accessState ? "text-amber-400/25" : "text-amber-400/70"}`}/>)
            : <File size={13} className="shrink-0 text-muted-foreground"/>}
          <span className="truncate flex-1">{node.name}</span>
          {!isDir && node.size && <span className="text-[10px] text-muted-foreground shrink-0">{node.size}</span>}
        </button>
      </div>
      {isDir && expanded && node.children?.map(c=>(
        <TreeNode key={c.path} node={c} depth={depth+1} onSelect={onSelect} onOpenDir={onOpenDir} onContextMenu={onContextMenu} sel={sel} directoryStates={directoryStates} device={device}/>
      ))}
    </div>
  );
}

// ─── App ─────────────────────────────────────────────────────────────────────

export default function App() {
  const { mode, setTheme } = useTheme();
  const [devices, setDevices] = useState<Device[]>([]);
  const [sel, setSel]       = useState<Device>(WAITING_DEVICE);
  const [tab, setTab]       = useState<Tab>("info");
  const [logs, setLogs]     = useState<LogLine[]>([]);
  const [tools, setTools]   = useState<ToolStatus[]>([]);
  const [runtime, setRuntime] = useState<RuntimeEnvironment|null>(null);
  const [apiHint, setApiHint] = useState("正在连接本机后端…");
  const [backendOnline, setBackendOnline] = useState(false);
  const [remoteFiles, setRemoteFiles] = useState<FileNode[]>([]);
  const [filePath, setFilePath] = useState("/");
  const [directoryStates, setDirectoryStates] = useState<Record<string, DirectoryAccessState>>({});
  const [topTip, setTopTip] = useState("");
  const [actionHint, setActionHint] = useState("");
  const [streamingDeviceKeys, setStreamingDeviceKeys] = useState<string[]>([]);
  const [filter, setFilter] = useState("");
  const [level, setLevel]   = useState<LogLevelFilter>("ALL");
  const [appFilter, setAppFilter] = useState("");
  const [appsByDevice, setAppsByDevice] = useState<Record<string, InstalledApp[]>>({});
  const [appsLoading, setAppsLoading] = useState(false);
  const [appsError, setAppsError] = useState("");
  const [appDetail, setAppDetail] = useState<AppDetail|null>(null);
  const [appDetailPackage, setAppDetailPackage] = useState("");
  const [appDetailLoading, setAppDetailLoading] = useState(false);
  const [appDetailError, setAppDetailError] = useState("");
  const [appContextMenu, setAppContextMenu] = useState<AppContextMenu|null>(null);
  const [uninstallAppTarget, setUninstallAppTarget] = useState<InstalledApp|null>(null);
  const [uninstallPackageInput, setUninstallPackageInput] = useState("");
  const [uninstalling, setUninstalling] = useState(false);
  const [uninstallError, setUninstallError] = useState("");
  const [fileContextMenu, setFileContextMenu] = useState<FileContextMenu|null>(null);
  const [deleteFileTarget, setDeleteFileTarget] = useState<FileNode|null>(null);
  const [deleteFileNameInput, setDeleteFileNameInput] = useState("");
  const [deletingFile, setDeletingFile] = useState(false);
  const [deleteFileError, setDeleteFileError] = useState("");
  const [renameFileTarget, setRenameFileTarget] = useState<FileNode|null>(null);
  const [renameFileNameInput, setRenameFileNameInput] = useState("");
  const [renamingFile, setRenamingFile] = useState(false);
  const [renameFileError, setRenameFileError] = useState("");
  const [file, setFile]     = useState<FileNode|null>(null);
  const [spinning, setSpinning] = useState(false);
  const [screenshotsByDevice, setScreenshotsByDevice] = useState<Record<string, ScreenshotSnapshot>>({});
  const logEnd = useRef<HTMLDivElement>(null);
  const eventSourcesRef = useRef<Record<string, EventSource>>({});
  const logBucketsByDeviceRef = useRef<Record<string, LogBuckets>>({});
  const pendingLogLinesRef = useRef<Record<string, LogLine[]>>({});
  const logFlushTimerRef = useRef<number|null>(null);
  const devicePollingRef = useRef(false);
  const devicesRef = useRef<Device[]>([]);
  const backendOnlineRef = useRef(false);
  const selectedLogDeviceKeyRef = useRef(logDeviceKey(WAITING_DEVICE));
  const loadedFileDeviceIdRef = useRef<string|null>(null);
  const loadedAppsDeviceKeysRef = useRef<Set<string>>(new Set());
  const tabByDeviceRef = useRef<Record<string, Tab>>({[logDeviceKey(WAITING_DEVICE)]: "info"});
  const autoScreenshotDeviceKeysRef = useRef<Set<string>>(new Set());
  const screenshotsByDeviceRef = useRef<Record<string, ScreenshotSnapshot>>({});
  const topTipTimerRef = useRef<number|null>(null);
  const selectedLogDeviceKey = logDeviceKey(sel);
  const streaming = streamingDeviceKeys.includes(selectedLogDeviceKey);

  useEffect(()=>{
    devicesRef.current = devices;
  },[devices]);

  useEffect(()=>{
    backendOnlineRef.current = backendOnline;
  },[backendOnline]);

  useEffect(()=>{
    screenshotsByDeviceRef.current = screenshotsByDevice;
  },[screenshotsByDevice]);

  /**
   * 切换当前设备页签，并记录到设备维度；用户在多设备间往返时应回到该设备上次使用的操作区域。
   */
  const selectTab = useCallback((nextTab: Tab)=>{
    tabByDeviceRef.current[logDeviceKey(sel)] = nextTab;
    setTab(nextTab);
  },[sel]);

  /**
   * 切换设备时保存当前设备页签，并恢复目标设备的历史页签，避免设备列表点击强制回到设备信息。
   */
  const selectDevice = useCallback((device: Device)=>{
    tabByDeviceRef.current[logDeviceKey(sel)] = tab;
    const nextTab = tabByDeviceRef.current[logDeviceKey(device)] || "info";
    setSel(device);
    setTab(nextTab);
  },[sel, tab]);

  /**
   * 记录设备已执行过自动截图；只记录自动行为，不影响用户后续手动点击截图。
   */
  const markAutoScreenshot = useCallback((deviceKey: string)=>{
    autoScreenshotDeviceKeysRef.current.add(deviceKey);
  },[]);

  /**
   * 显示顶部浮层提示；文件树的空目录/无权限反馈不能占用目录路径下方的小字状态区。
   */
  const showTopTip = useCallback((message: string)=>{
    setTopTip(message);
    if (topTipTimerRef.current !== null) {
      window.clearTimeout(topTipTimerRef.current);
    }
    topTipTimerRef.current = window.setTimeout(() => {
      setTopTip("");
      topTipTimerRef.current = null;
    }, 2600);
  },[]);

  /**
   * 记录目录访问状态；只缓存明确的空目录或不可访问结果，正常目录会清理旧状态。
   */
  const updateDirectoryState = useCallback((path: string, state: DirectoryAccessState | null)=>{
    const key = directoryStateKey(sel, path);
    setDirectoryStates(current => {
      if (state) return current[key] === state ? current : {...current, [key]: state};
      if (!current[key]) return current;
      const next = {...current};
      delete next[key];
      return next;
    });
  },[sel]);

  /**
   * 当前目录加载完成后预探测子目录状态，让空目录和不可访问目录在列表展示时立即变浅。
   */
  const preloadDirectoryStates = useCallback(async(nodes: FileNode[])=>{
    if (!backendOnline || sel.platform !== "android" || sel.status !== "connected") return;
    const directories = nodes.filter(node => node.type === "dir");
    for (let index = 0; index < directories.length; index += 4) {
      const batch = directories.slice(index, index + 4);
      await Promise.all(batch.map(async(directory) => {
        try {
          const query = encodeURIComponent(directory.path);
          const childNodes = await fetchJson<FileNode[]>(`/api/devices/${sel.platform}/${encodeURIComponent(sel.serial)}/files?path=${query}`);
          updateDirectoryState(directory.path, childNodes.length === 0 ? "empty" : null);
        } catch {
          // 预探测失败通常表示目录无权限或不可访问，记录状态用于列表浅色展示和双击提示。
          updateDirectoryState(directory.path, "inaccessible");
        }
      }));
    }
  },[backendOnline, sel.platform, sel.serial, sel.status, updateDirectoryState]);

  /**
   * 保存设备截图快照；截图属于设备上下文，不能跟随截图面板卸载而丢失。
   */
  const updateScreenshotSnapshot = useCallback((deviceKey: string, snapshot: ScreenshotSnapshot)=>{
    setScreenshotsByDevice(current => {
      const previousUrl = current[deviceKey]?.url;
      if (previousUrl && previousUrl !== snapshot.url) {
        URL.revokeObjectURL(previousUrl);
      }
      // 同步更新 ref，确保页面快速卸载时也能释放最新创建的 Blob URL。
      const next = {...current, [deviceKey]: snapshot};
      screenshotsByDeviceRef.current = next;
      return next;
    });
  },[]);

  const resetLogHistory = useCallback((lines: LogLine[] = [], deviceKey = selectedLogDeviceKeyRef.current)=>{
    const buckets = createLogBuckets();
    lines.map(normalizeLog).forEach(line => appendLogToBuckets(buckets, line));
    logBucketsByDeviceRef.current[deviceKey] = buckets;
    pendingLogLinesRef.current[deviceKey] = [];
    if (deviceKey === selectedLogDeviceKeyRef.current) {
      setLogs([...buckets.ALL]);
    }
  },[]);

  const flushPendingLogs = useCallback((deviceKey?: string)=>{
    const keys = deviceKey ? [deviceKey] : Object.keys(pendingLogLinesRef.current);
    let shouldRenderSelected = false;
    keys.forEach(key => {
      const pending = pendingLogLinesRef.current[key] || [];
      if (pending.length === 0) return;
      pendingLogLinesRef.current[key] = [];
      const buckets = ensureLogBuckets(logBucketsByDeviceRef.current, key);
      pending.forEach(line => appendLogToBuckets(buckets, line));
      shouldRenderSelected = shouldRenderSelected || key === selectedLogDeviceKeyRef.current;
    });
    if (shouldRenderSelected) {
      // React 状态只承载当前设备的重绘信号；其它设备后台采集时不污染当前页面。
      const buckets = ensureLogBuckets(logBucketsByDeviceRef.current, selectedLogDeviceKeyRef.current);
      setLogs([...buckets.ALL]);
    }
  },[]);

  const queueLogLine = useCallback((line: LogLine, deviceKey = selectedLogDeviceKeyRef.current)=>{
    const pending = pendingLogLinesRef.current[deviceKey] || [];
    pending.push(normalizeLog(line));
    pendingLogLinesRef.current[deviceKey] = pending;
    if (pending.length >= LOG_FLUSH_BATCH_SIZE) {
      if (logFlushTimerRef.current !== null) {
        window.clearTimeout(logFlushTimerRef.current);
        logFlushTimerRef.current = null;
      }
      flushPendingLogs(deviceKey);
      return;
    }
    if (logFlushTimerRef.current === null) {
      // iOS 系统日志吞吐高，按批刷新可以避免每行日志触发一次整表渲染。
      logFlushTimerRef.current = window.setTimeout(()=>{
        logFlushTimerRef.current = null;
        flushPendingLogs();
      }, LOG_FLUSH_INTERVAL_MS);
    }
  },[flushPendingLogs]);

  const selectedDeviceLogBuckets = logBucketsByDeviceRef.current[selectedLogDeviceKey];
  const selectedLevelLogs = selectedDeviceLogBuckets
    ? selectedDeviceLogBuckets[level]
    : [];
  const selectedApps = appsByDevice[selectedLogDeviceKey] || [];
  const filteredApps = selectedApps.filter(app => {
    const q = appFilter.toLowerCase();
    return !q
      || app.name.toLowerCase().includes(q)
      || app.packageName.toLowerCase().includes(q)
      || app.versionName.toLowerCase().includes(q)
      || app.versionCode.toLowerCase().includes(q);
  });
  const filteredLogs = selectedLevelLogs.filter(l=>{
    const q=filter.toLowerCase();
    return !q || l.tag.toLowerCase().includes(q) || l.message.toLowerCase().includes(q);
  });
  // 过滤后再限制渲染行数，避免原始日志达到 1000 行时把当前级别的有效日志过早裁掉。
  const visibleLogs = filteredLogs.slice(-LOG_RENDER_LIMIT);

  useEffect(()=>{ if(streaming) logEnd.current?.scrollIntoView({behavior:"smooth"}); },[logs,streaming]);

  useEffect(()=>{
    selectedLogDeviceKeyRef.current = selectedLogDeviceKey;
    flushPendingLogs(selectedLogDeviceKey);
    const buckets = ensureLogBuckets(logBucketsByDeviceRef.current, selectedLogDeviceKey);
    setLogs([...buckets.ALL]);
  },[flushPendingLogs, selectedLogDeviceKey]);

  /**
   * 将后端设备快照同步到页面；后端在线时严格展示真实设备，空列表保持等待设备空态。
   */
  const applyDeviceSnapshot = useCallback((deviceData: Device[])=>{
    const previousById = new Map(devicesRef.current.map(device => [device.id, device]));
    if (deviceData.length > 0) {
      const normalized = deviceData.map(device => mergeDeviceSnapshot(previousById.get(device.id), device));
      setDevices(current => sameDeviceList(current, normalized) ? current : normalized);
      setSel(current => {
        const matched = normalized.find(d => d.id === current.id);
        if (matched) {
          const next = mergeDeviceSnapshot(current, matched);
          return sameDevice(current, next) ? current : next;
        }
        // 当前选中设备被拔掉时保留页面上下文，仅把头部连接态改成离线。
        if (current.id !== WAITING_DEVICE.id && current.status === "connected") {
          const next = {...current, status: "offline" as DeviceStatus};
          return sameDevice(current, next) ? current : next;
        }
        return normalized[0];
      });
      setApiHint("已连接本机后端，正在实时同步真实设备");
      return;
    }
    setDevices(current => current.length === 0 ? current : []);
    setSel(current => sameDevice(current, WAITING_DEVICE) ? current : WAITING_DEVICE);
    setApiHint("本机后端可用，正在等待 USB 设备连接");
  },[]);

  /**
   * 轻量刷新设备列表；轮询只查设备快照，避免覆盖日志和其它正在操作的面板状态。
   */
  const refreshDeviceSnapshot = useCallback(async()=>{
    if (devicePollingRef.current) return;
    devicePollingRef.current = true;
    try {
      const deviceData = await fetchJson<Device[]>("/api/devices");
      setBackendOnline(true);
      applyDeviceSnapshot(deviceData);
    } catch {
      setDevices(current => current.length === 0 ? current : []);
      setSel(current => sameDevice(current, WAITING_DEVICE) ? current : WAITING_DEVICE);
      setRuntime(current => current === null ? current : null);
      setBackendOnline(current => current ? false : current);
      setApiHint("未连接本机后端，请先启动本机服务");
    } finally {
      devicePollingRef.current = false;
    }
  },[applyDeviceSnapshot]);

  /**
   * 从本机后端刷新完整数据；后端不可达时保持空态，不注入任何本地静态数据。
   */
  const refresh = useCallback(async()=>{
    setSpinning(true);
    try {
      const [toolData, deviceData, runtimeData] = await Promise.all([
        fetchJson<ToolStatus[]>("/api/tools/status"),
        fetchJson<Device[]>("/api/devices"),
        fetchJson<RuntimeEnvironment>("/api/runtime/environment"),
      ]);
      setTools(toolData);
      // 运行环境由后端进程决定，用于确认当前加载 Mac 还是 Windows 内置工具目录。
      setRuntime(runtimeData);
      setBackendOnline(true);
      applyDeviceSnapshot(deviceData);
    } catch (error) {
      setDevices(current => current.length === 0 ? current : []);
      setSel(current => sameDevice(current, WAITING_DEVICE) ? current : WAITING_DEVICE);
      setRuntime(current => current === null ? current : null);
      setBackendOnline(current => current ? false : current);
      setRemoteFiles(current => current.length === 0 ? current : []);
      setApiHint("未连接本机后端，请先启动本机服务");
    } finally {
      setSpinning(false);
    }
  },[applyDeviceSnapshot]);

  /**
   * 加载 Android/iOS 设备详情，并合并到当前设备与设备列表。
   */
  const loadDeviceDetail = useCallback(async(device: Device)=>{
    if (!backendOnline || !["android", "ios"].includes(device.platform) || device.status !== "connected") return;
    try {
      const detail = await fetchJson<Device>(`/api/devices/${device.platform}/${encodeURIComponent(device.serial)}/detail`);
      setSel(current => {
        if (current.id !== device.id) return current;
        const next = normalizeDevice({...current, ...detail});
        return sameDevice(current, next) ? current : next;
      });
      setDevices(current => {
        let changed = false;
        const nextDevices = current.map(item => {
          if (item.id !== device.id) return item;
          const next = normalizeDevice({...item, ...detail});
          if (sameDevice(item, next)) return item;
          changed = true;
          return next;
        });
        // 详情轮询未产生新值时保留原列表引用，避免设备列表和详情区域闪烁。
        return changed ? nextDevices : current;
      });
    } catch (error) {
      setActionHint(error instanceof Error ? error.message : "设备详情读取失败");
    }
  },[backendOnline]);

  /**
   * 加载 Android 远端目录；失败时保留当前文件树并显示错误摘要。
   */
  const loadFiles = useCallback(async(path = "/")=>{
    if (!backendOnline || sel.platform !== "android" || sel.status !== "connected") {
      setRemoteFiles(current => current.length === 0 ? current : []);
      return null;
    }
    try {
      const query = encodeURIComponent(path);
      const nodes = await fetchJson<FileNode[]>(`/api/devices/${sel.platform}/${encodeURIComponent(sel.serial)}/files?path=${query}`);
      const normalizedNodes = sortFileNodes(nodes.map(normalizeFile));
      setRemoteFiles(normalizedNodes);
      setFilePath(path);
      setFile(null);
      updateDirectoryState(path, normalizedNodes.length === 0 ? "empty" : null);
      void preloadDirectoryStates(normalizedNodes);
      // 记录当前设备已完成文件树初始化，避免页签切回时重置用户正在浏览的目录。
      loadedFileDeviceIdRef.current = sel.id;
      if (normalizedNodes.length === 0) {
        showTopTip(directoryAccessTip("empty", path));
      }
      return normalizedNodes;
    } catch (error) {
      updateDirectoryState(path, "inaccessible");
      showTopTip(error instanceof Error ? error.message : directoryAccessTip("inaccessible", path));
      return null;
    }
  },[backendOnline, preloadDirectoryStates, sel, showTopTip, updateDirectoryState]);

  /**
   * 返回 Android 文件树上一级；根目录保持不变，避免生成空路径。
   */
  const loadParentFiles = useCallback(()=>{
    if (filePath === "/") return;
    const parentPath = filePath.split("/").slice(0, -1).join("/") || "/";
    loadFiles(parentPath);
  },[filePath, loadFiles]);

  /**
   * 双击复制当前远端路径；失败时用顶部 tips 告知用户，避免静默失败。
   */
  const copyFilePath = useCallback(async()=>{
    try {
      await navigator.clipboard.writeText(filePath);
      showTopTip(`已复制路径：${filePath}`);
    } catch {
      showTopTip("路径复制失败，请手动复制");
    }
  },[filePath, showTopTip]);

  /**
   * 复制当前文件节点名称；右键菜单和详情标题按钮共用，保证 Electron 与浏览器访问行为一致。
   */
  const copyFileName = useCallback(async(node: FileNode)=>{
    try {
      await navigator.clipboard.writeText(node.name);
      setFileContextMenu(null);
      showTopTip(`已复制文件名称：${node.name}`);
    } catch {
      showTopTip("文件名称复制失败，请手动复制");
    }
  },[showTopTip]);

  /**
   * 选择文件节点；目录单击只展示详情，避免用户无法查看目录元信息。
   */
  const selectFileNode = useCallback((node: FileNode)=>{
    setFile(node);
  },[]);

  /**
   * 进入文件目录；由目录双击或目录前置箭头触发，和单击选中保持解耦。
   */
  const openFileDirectory = useCallback(async(node: FileNode)=>{
    if (node.type !== "dir") return;
    const knownState = directoryAccessState(node, directoryStates, sel);
    if (knownState) {
      showTopTip(directoryAccessTip(knownState, node.path));
      return;
    }
    const loadedNodes = await loadFiles(node.path);
    if (loadedNodes && loadedNodes.length === 0) {
      showTopTip(directoryAccessTip("empty", node.path));
    }
  },[directoryStates, loadFiles, sel, showTopTip]);

  /**
   * 打开文件右键菜单；当前高风险变更只对文件启用，目录仍通过选中查看详情。
   */
  const openFileContextMenu = useCallback((event: React.MouseEvent, node: FileNode)=>{
    event.preventDefault();
    setFile(node);
    if (node.type !== "file" || sel.platform !== "android") {
      return;
    }
    const menuWidth = 172;
    const menuHeight = 236;
    setFileContextMenu({
      file: node,
      x: Math.min(event.clientX, window.innerWidth - menuWidth - 8),
      y: Math.min(event.clientY, window.innerHeight - menuHeight - 8),
    });
  },[sel.platform]);

  /**
   * 下载指定 Android 文件；右键菜单和详情按钮共用同一受控下载逻辑。
   */
  const downloadFileNode = useCallback(async(target: FileNode)=>{
    if (target.type !== "file" || !backendOnline || sel.platform !== "android") return;
    setFileContextMenu(null);
    try {
      const url = `${API_BASE}/api/devices/${sel.platform}/${encodeURIComponent(sel.serial)}/files/download?path=${encodeURIComponent(target.path)}`;
      const response = await fetch(url);
      if (!response.ok) throw await apiError(response);
      const blob = await response.blob();
      const objectUrl = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = objectUrl;
      link.download = target.name;
      link.click();
      URL.revokeObjectURL(objectUrl);
      setActionHint(`已下载 ${target.name}`);
    } catch (error) {
      setActionHint(error instanceof Error ? error.message : "文件下载失败");
    }
  },[backendOnline, sel.platform, sel.serial]);

  /**
   * 读取远端文件最新详情；右键“文件详情”不只展示缓存节点，避免操作后元数据过期。
   */
  const showFileDetail = useCallback(async(target: FileNode)=>{
    setFileContextMenu(null);
    setFile(target);
    if (target.type !== "file" || !backendOnline || sel.platform !== "android") return;
    try {
      const detail = await fetchJson<FileNode>(
        `/api/devices/${sel.platform}/${encodeURIComponent(sel.serial)}/files/detail?path=${encodeURIComponent(target.path)}`
      );
      setFile(normalizeFile(detail));
    } catch (error) {
      setActionHint(error instanceof Error ? error.message : "文件详情读取失败");
    }
  },[backendOnline, sel.platform, sel.serial]);

  /**
   * 打开删除文件确认弹窗；必须输入完整文件名，降低右键菜单误操作风险。
   */
  const requestDeleteFile = useCallback((target: FileNode)=>{
    setFileContextMenu(null);
    if (target.type !== "file") return;
    setDeleteFileTarget(target);
    setDeleteFileNameInput("");
    setDeleteFileError("");
  },[]);

  /**
   * 关闭删除文件弹窗，并清理输入与错误状态。
   */
  const closeDeleteFileDialog = useCallback(()=>{
    setDeleteFileTarget(null);
    setDeleteFileNameInput("");
    setDeleteFileError("");
  },[]);

  /**
   * 执行文件删除；后端再次校验文件类型和路径，前端负责刷新当前目录状态。
   */
  const confirmDeleteFile = useCallback(async()=>{
    if (!deleteFileTarget || deleteFileNameInput !== deleteFileTarget.name || deletingFile) return;
    setDeletingFile(true);
    setDeleteFileError("");
    try {
      const url = `${API_BASE}/api/devices/${sel.platform}/${encodeURIComponent(sel.serial)}/files?path=${encodeURIComponent(deleteFileTarget.path)}`;
      const response = await fetch(url, { method: "DELETE" });
      if (!response.ok) throw await apiError(response);
      setActionHint(`已删除 ${deleteFileTarget.name}`);
      if (file?.path === deleteFileTarget.path) {
        setFile(null);
      }
      closeDeleteFileDialog();
      await loadFiles(filePath);
    } catch (error) {
      const message = error instanceof Error ? error.message : "文件删除失败";
      setDeleteFileError(message);
      setActionHint(message);
    } finally {
      setDeletingFile(false);
    }
  },[closeDeleteFileDialog, deleteFileNameInput, deleteFileTarget, deletingFile, file?.path, filePath, loadFiles, sel.platform, sel.serial]);

  /**
   * 打开重命名弹窗；输入框默认填入当前文件名，用户只需要修改单级名称。
   */
  const requestRenameFile = useCallback((target: FileNode)=>{
    setFileContextMenu(null);
    if (target.type !== "file") return;
    setRenameFileTarget(target);
    setRenameFileNameInput(target.name);
    setRenameFileError("");
  },[]);

  /**
   * 在当前目录创建文件副本；副本名由后端查重生成，前端只负责刷新和选中新节点。
   */
  const copyFileNode = useCallback(async(target: FileNode)=>{
    if (target.type !== "file" || !backendOnline || sel.platform !== "android") return;
    setFileContextMenu(null);
    try {
      const response = await fetch(
        `${API_BASE}/api/devices/${sel.platform}/${encodeURIComponent(sel.serial)}/files/copy?path=${encodeURIComponent(target.path)}`,
        { method: "POST", headers: { Accept: "application/json" } }
      );
      if (!response.ok) throw await apiError(response);
      const copied = normalizeFile(await response.json() as FileNode);
      const refreshed = await loadFiles(filePath);
      setFile(refreshed?.find(node => node.path === copied.path) ?? copied);
      setActionHint(`已创建副本 ${copied.name}`);
    } catch (error) {
      setActionHint(error instanceof Error ? error.message : "文件副本创建失败");
    }
  },[backendOnline, filePath, loadFiles, sel.platform, sel.serial]);

  /**
   * 关闭重命名弹窗，并清理输入与错误状态。
   */
  const closeRenameFileDialog = useCallback(()=>{
    setRenameFileTarget(null);
    setRenameFileNameInput("");
    setRenameFileError("");
  },[]);

  /**
   * 执行文件重命名；新名称只作为 query 参数传给后端，由后端完成同目录约束校验。
   */
  const confirmRenameFile = useCallback(async()=>{
    const nextName = renameFileNameInput.trim();
    if (!renameFileTarget || !nextName || renamingFile) return;
    setRenamingFile(true);
    setRenameFileError("");
    try {
      const params = new URLSearchParams({ path: renameFileTarget.path, newName: nextName });
      const response = await fetch(
        `${API_BASE}/api/devices/${sel.platform}/${encodeURIComponent(sel.serial)}/files/rename?${params}`,
        { method: "POST", headers: { Accept: "application/json" } }
      );
      if (!response.ok) throw await apiError(response);
      const renamed = normalizeFile(await response.json() as FileNode);
      const refreshed = await loadFiles(filePath);
      setFile(refreshed?.find(node => node.path === renamed.path) ?? renamed);
      setActionHint(`已重命名为 ${renamed.name}`);
      closeRenameFileDialog();
    } catch (error) {
      const message = error instanceof Error ? error.message : "文件重命名失败";
      setRenameFileError(message);
      setActionHint(message);
    } finally {
      setRenamingFile(false);
    }
  },[closeRenameFileDialog, filePath, loadFiles, renameFileNameInput, renameFileTarget, renamingFile, sel.platform, sel.serial]);

  /**
   * 加载 Android 已安装应用列表；结果按设备缓存，页签往返时保留列表和搜索体验。
   */
  const loadApps = useCallback(async(force = false)=>{
    const deviceKey = logDeviceKey(sel);
    if (!backendOnline || sel.platform !== "android" || sel.status !== "connected") {
      setAppsError(sel.platform === "android" ? "设备未连接，无法读取应用列表" : "当前平台暂不支持应用管理");
      return;
    }
    if (!force && loadedAppsDeviceKeysRef.current.has(deviceKey)) {
      return;
    }
    setAppsLoading(true);
    setAppsError("");
    try {
      const apps = await fetchJson<InstalledApp[]>(`/api/devices/${sel.platform}/${encodeURIComponent(sel.serial)}/apps`);
      setAppsByDevice(current => ({...current, [deviceKey]: sortInstalledApps(apps)}));
      loadedAppsDeviceKeysRef.current.add(deviceKey);
    } catch (error) {
      const message = error instanceof Error ? error.message : "应用列表读取失败";
      setAppsError(message);
      setActionHint(message);
    } finally {
      setAppsLoading(false);
    }
  },[backendOnline, sel]);

  /**
   * 加载单个 Android 应用详情；弹窗独立失败，不清空应用列表。
   */
  const loadAppDetail = useCallback(async(app: InstalledApp)=>{
    setAppContextMenu(null);
    if (!backendOnline || sel.platform !== "android" || sel.status !== "connected") {
      setAppDetailError("设备未连接，无法读取应用详情");
      setAppDetailPackage(app.packageName);
      setAppDetail(null);
      return;
    }
    setAppDetailPackage(app.packageName);
    setAppDetailLoading(true);
    setAppDetailError("");
    setAppDetail(null);
    try {
      const detail = await fetchJson<AppDetail>(
        `/api/devices/${sel.platform}/${encodeURIComponent(sel.serial)}/apps/${encodeURIComponent(app.packageName)}/detail`
      );
      setAppDetail(detail);
    } catch (error) {
      const message = error instanceof Error ? error.message : "应用详情读取失败";
      setAppDetailError(message);
      setActionHint(message);
    } finally {
      setAppDetailLoading(false);
    }
  },[backendOnline, sel]);

  /**
   * 打开应用右键菜单，并把菜单位置限制在当前视口内。
   */
  const openAppContextMenu = useCallback((event: React.MouseEvent, app: InstalledApp)=>{
    event.preventDefault();
    const menuWidth = 168;
    const menuHeight = 92;
    setAppContextMenu({
      app,
      x: Math.min(event.clientX, window.innerWidth - menuWidth - 8),
      y: Math.min(event.clientY, window.innerHeight - menuHeight - 8),
    });
  },[]);

  /**
   * 打开卸载确认弹窗；输入框必须重新输入包名，防止误操作。
   */
  const requestUninstallApp = useCallback((app: InstalledApp)=>{
    setAppContextMenu(null);
    if (app.systemApp) {
      setActionHint("系统应用不支持直接卸载");
      return;
    }
    setUninstallAppTarget(app);
    setUninstallPackageInput("");
    setUninstallError("");
  },[]);

  /**
   * 关闭卸载确认弹窗，并清理输入和错误状态。
   */
  const closeUninstallDialog = useCallback(()=>{
    setUninstallAppTarget(null);
    setUninstallPackageInput("");
    setUninstallError("");
  },[]);

  /**
   * 执行应用卸载；必须输入完整包名后才允许调用后端删除接口。
   */
  const confirmUninstallApp = useCallback(async()=>{
    if (!uninstallAppTarget || uninstallPackageInput !== uninstallAppTarget.packageName || uninstalling) return;
    if (uninstallAppTarget.systemApp) {
      setUninstallError("系统应用不支持直接卸载");
      return;
    }
    setUninstalling(true);
    setUninstallError("");
    try {
      const url = `${API_BASE}/api/devices/${sel.platform}/${encodeURIComponent(sel.serial)}/apps/${encodeURIComponent(uninstallAppTarget.packageName)}`;
      const response = await fetch(url, { method: "DELETE" });
      if (!response.ok) throw await apiError(response);
      const deviceKey = logDeviceKey(sel);
      // 卸载成功后立即从当前设备列表移除，随后强制刷新以同步设备实际状态。
      setAppsByDevice(current => ({
        ...current,
        [deviceKey]: (current[deviceKey] || []).filter(app => app.packageName !== uninstallAppTarget.packageName),
      }));
      loadedAppsDeviceKeysRef.current.delete(deviceKey);
      setActionHint(`已卸载 ${uninstallAppTarget.packageName}`);
      if (appDetailPackage === uninstallAppTarget.packageName) {
        setAppDetail(null);
        setAppDetailPackage("");
        setAppDetailError("");
      }
      setUninstallAppTarget(null);
      setUninstallPackageInput("");
      await loadApps(true);
    } catch (error) {
      const message = error instanceof Error ? error.message : "应用卸载失败";
      setUninstallError(message);
      setActionHint(message);
    } finally {
      setUninstalling(false);
    }
  },[appDetailPackage, loadApps, sel, uninstallAppTarget, uninstallPackageInput, uninstalling]);

  /**
   * 关闭应用详情弹窗并清理上一次错误，避免下次打开残留旧状态。
   */
  const closeAppDetail = useCallback(()=>{
    setAppDetail(null);
    setAppDetailPackage("");
    setAppDetailError("");
    setAppDetailLoading(false);
  },[]);

  useEffect(()=>{
    closeAppDetail();
    setAppContextMenu(null);
    closeUninstallDialog();
    setFileContextMenu(null);
    closeDeleteFileDialog();
    closeRenameFileDialog();
  },[closeAppDetail, closeDeleteFileDialog, closeRenameFileDialog, closeUninstallDialog, selectedLogDeviceKey]);

  useEffect(()=>{
    if (!appContextMenu) return;
    const closeMenu = () => setAppContextMenu(null);
    // 右键菜单是瞬时浮层，任何页面点击、滚动或窗口变化都应关闭，避免误点过期菜单。
    window.addEventListener("click", closeMenu);
    window.addEventListener("scroll", closeMenu, true);
    window.addEventListener("resize", closeMenu);
    return () => {
      window.removeEventListener("click", closeMenu);
      window.removeEventListener("scroll", closeMenu, true);
      window.removeEventListener("resize", closeMenu);
    };
  },[appContextMenu]);

  useEffect(()=>{
    if (!fileContextMenu) return;
    const closeMenu = () => setFileContextMenu(null);
    // 文件右键菜单绑定当前设备和当前目录，页面滚动或切换尺寸后应立即关闭。
    window.addEventListener("click", closeMenu);
    window.addEventListener("scroll", closeMenu, true);
    window.addEventListener("resize", closeMenu);
    return () => {
      window.removeEventListener("click", closeMenu);
      window.removeEventListener("scroll", closeMenu, true);
      window.removeEventListener("resize", closeMenu);
    };
  },[fileContextMenu]);

  /**
   * 下载当前选中的 Android 文件。
   */
  const downloadSelectedFile = useCallback(async()=>{
    if (!file) return;
    await downloadFileNode(file);
  },[downloadFileNode, file]);

  /**
   * 启动后端 SSE 日志流；Android 走 logcat，iOS 走 idevicesyslog，后端不可达时只提示错误。
   */
  const startLogStream = useCallback(()=>{
    const currentLogDeviceKey = logDeviceKey(sel);
    // 同一设备只允许一个采集连接，避免重复点击造成同一日志行被写入多次。
    if (eventSourcesRef.current[currentLogDeviceKey] || streamingDeviceKeys.includes(currentLogDeviceKey)) {
      return;
    }
    if (!backendOnline) {
      setActionHint("本机后端未连接，无法启动实时日志");
      return;
    }
    if (!["android", "ios"].includes(sel.platform) || sel.status !== "connected") {
      setActionHint("设备已断开，无法启动实时日志");
      return;
    }
    // 级别和文本过滤在前端即时生效，SSE 保持全量输入，避免切换日志类型时重建连接并清空列表。
    const params = new URLSearchParams({ level: "ALL", filter: "" });
    const url = `${API_BASE}/api/devices/${sel.platform}/${encodeURIComponent(sel.serial)}/logs/stream?${params}`;
    const source = new EventSource(url);
    eventSourcesRef.current[currentLogDeviceKey] = source;
    resetLogHistory([], currentLogDeviceKey);
    setStreamingDeviceKeys(keys => keys.includes(currentLogDeviceKey) ? keys : [...keys, currentLogDeviceKey]);
    source.addEventListener("log", event => {
      const line = normalizeLog(JSON.parse((event as MessageEvent).data) as LogLine);
      queueLogLine(line, currentLogDeviceKey);
    });
    source.addEventListener(LOG_TOOL_ERROR_EVENT, event => {
      if ((event as MessageEvent).data) {
        setActionHint((JSON.parse((event as MessageEvent).data) as LogLine).message);
      }
    });
    source.onerror = () => {
      flushPendingLogs(currentLogDeviceKey);
      source.close();
      delete eventSourcesRef.current[currentLogDeviceKey];
      setStreamingDeviceKeys(keys => keys.filter(key => key !== currentLogDeviceKey));
    };
  },[backendOnline, flushPendingLogs, queueLogLine, resetLogHistory, sel, streamingDeviceKeys]);

  /**
   * 停止指定设备日志流，关闭 EventSource 即可触发后端 SSE 清理。
   */
  const stopLogStream = useCallback((deviceKey = selectedLogDeviceKeyRef.current)=>{
    flushPendingLogs(deviceKey);
    eventSourcesRef.current[deviceKey]?.close();
    delete eventSourcesRef.current[deviceKey];
    setStreamingDeviceKeys(keys => keys.filter(key => key !== deviceKey));
  },[flushPendingLogs]);

  /**
   * 设备断开时只关闭对应设备日志流；切换设备不停止后台采集，保证切回后还能继续查看。
   */
  useEffect(()=>{
    streamingDeviceKeys.forEach(deviceKey => {
      const device = devices.find(item => logDeviceKey(item) === deviceKey);
      if (!device || device.status !== "connected") {
        stopLogStream(deviceKey);
      }
    });
  },[devices, stopLogStream, streamingDeviceKeys]);

  /**
   * 导出本次实时采集日志；后端会先停止采集，再把当前会话保留的滚动日志压缩成 zip。
   */
  const exportLogs = useCallback(async()=>{
    if (!backendOnline) return;
    if (!streaming) {
      setActionHint("请先开始采集日志");
      return;
    }
    if (!["android", "ios"].includes(sel.platform)) {
      setActionHint("当前平台暂不支持日志导出");
      return;
    }
    try {
      const response = await fetch(`${API_BASE}/api/devices/${sel.platform}/${encodeURIComponent(sel.serial)}/logs/export`);
      if (!response.ok) throw await apiError(response);
      const blob = await response.blob();
      const objectUrl = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = objectUrl;
      link.download = `device-logs-${sel.serial}.zip`;
      link.click();
      URL.revokeObjectURL(objectUrl);
      const currentLogDeviceKey = logDeviceKey(sel);
      flushPendingLogs(currentLogDeviceKey);
      eventSourcesRef.current[currentLogDeviceKey]?.close();
      delete eventSourcesRef.current[currentLogDeviceKey];
      setStreamingDeviceKeys(keys => keys.filter(key => key !== currentLogDeviceKey));
      setActionHint("日志导出完成");
    } catch (error) {
      setActionHint(error instanceof Error ? error.message : "日志导出失败");
    }
  },[backendOnline, flushPendingLogs, sel, streaming]);

  useEffect(()=>{
    // 首次进入页面才做完整刷新；后续设备热插拔由轻量轮询负责，避免覆盖实时日志和文件面板。
    refresh();
  },[]);

  /**
   * 定时同步设备连接状态；用短轮询模拟本机 USB 热插拔事件，兼顾 macOS 和 Windows 可用性。
   */
  useEffect(()=>{
    const timer = window.setInterval(refreshDeviceSnapshot, DEVICE_REFRESH_INTERVAL_MS);
    return () => window.clearInterval(timer);
  },[refreshDeviceSnapshot]);

  useEffect(()=>{
    loadDeviceDetail(sel);
  },[sel.id, sel.status, loadDeviceDetail]);

  /**
   * 定时刷新当前设备详情，只更新头部电量和设备信息，不触碰日志列表与文件树。
   */
  useEffect(()=>{
    if (!backendOnline || !["android", "ios"].includes(sel.platform) || sel.status !== "connected") return;
    const timer = window.setInterval(()=>loadDeviceDetail(sel), DEVICE_REFRESH_INTERVAL_MS);
    return () => window.clearInterval(timer);
  },[backendOnline, loadDeviceDetail, sel.id, sel.platform, sel.serial, sel.status]);

  useEffect(()=>{
    if (tab !== "files" || sel.platform !== "android" || sel.status !== "connected") return;
    if (loadedFileDeviceIdRef.current === sel.id) return;
    // 文件页只在首次进入当前设备时自动加载根目录，后续页签切换必须保留用户浏览现场。
    loadFiles("/");
  },[loadFiles, sel.id, sel.platform, sel.status, tab]);

  useEffect(()=>{
    if (tab !== "apps") return;
    if (sel.platform !== "android" || sel.status !== "connected") {
      setAppsError(sel.platform === "android" ? "设备未连接，无法读取应用列表" : "当前平台暂不支持应用管理");
      return;
    }
    loadApps();
  },[loadApps, sel.platform, sel.status, tab]);

  useEffect(()=>{
    return () => {
      Object.values(eventSourcesRef.current).forEach(source => source.close());
      eventSourcesRef.current = {};
      // 退出页面时统一释放设备截图缓存，避免长期切换设备后遗留 Blob URL。
      Object.values(screenshotsByDeviceRef.current).forEach(snapshot => {
        if (snapshot.url) URL.revokeObjectURL(snapshot.url);
      });
      screenshotsByDeviceRef.current = {};
      if (logFlushTimerRef.current !== null) {
        window.clearTimeout(logFlushTimerRef.current);
      }
      if (topTipTimerRef.current !== null) {
        window.clearTimeout(topTipTimerRef.current);
      }
    };
  },[]);

  const connCount = devices.filter(d=>d.status==="connected").length;
  const toolRows = tools.length > 0
    ? tools.filter(t=>["adb","hdc","idevice_id"].includes(t.name)).map(t=>({ n:t.name, ok:t.available, detail:t.message || t.version || t.path }))
    : EMPTY_TOOL_ROWS;
  const runtimeLabel = runtime ? `${runtime.toolDirectoryName}` : "未连接";
  const appVersion = runtime?.appVersion || APP_VERSION;
  const runtimeTitle = runtime
    ? `${runtime.appName || APP_NAME} ${runtime.appVersion || APP_VERSION} / ${runtime.osName} / ${runtime.osArch} / Java ${runtime.javaVersion} / ${runtime.bundledToolRoot}`
    : "本机后端未连接";
  const showDisconnectDialog = backendOnline
    && sel.id !== WAITING_DEVICE.id
    && sel.status === "offline"
    && (tab === "files" || tab === "logs" || tab === "apps");
  const showNoDeviceState = sel.id === WAITING_DEVICE.id || devices.length === 0;
  const showOfflineState = !showNoDeviceState && sel.status === "offline" && !showDisconnectDialog;
  const showWorkspace = !showNoDeviceState && (sel.status === "connected" || showDisconnectDialog);
  const showScreenshotPanel = tab === "info" && supportsDeviceScreenshot(sel);
  const autoScreenshotKey = showScreenshotPanel
    && backendOnline
    && sel.status === "connected"
    && !autoScreenshotDeviceKeysRef.current.has(logDeviceKey(sel))
    ? logDeviceKey(sel)
    : "";
  const displayPath = splitDisplayPath(filePath);
  const appDetailBaseRows = appDetail ? [
    ["应用名称", appDetail.name],
    ["包名", appDetail.packageName],
    ["版本名", appDetail.versionName],
    ["版本号", appDetail.versionCode],
    ["UID", appDetail.uid],
    ["最低 SDK", appDetail.minSdk],
    ["目标 SDK", appDetail.targetSdk],
    ["类型", appDetail.systemApp ? "系统应用" : "用户应用"],
    ["启用状态", appDetail.enabledState],
    ["已安装", appDetail.installed],
    ["已隐藏", appDetail.hidden],
    ["已停止", appDetail.stopped],
    ["已挂起", appDetail.suspended],
  ] as Array<[string, string | boolean]> : [];
  const appDetailPathRows = appDetail ? [
    ["首次安装", appDetail.firstInstallTime],
    ["最后更新", appDetail.lastUpdateTime],
    ["安装来源", appDetail.installerPackageName],
    ["代码路径", appDetail.codePath],
    ["资源路径", appDetail.resourcePath],
    ["数据目录", appDetail.dataDir],
  ] as Array<[string, string]> : [];

  /**
   * 构造 AI 助手只读设备上下文；AI 模块不能直接持有主界面完整设备对象，避免耦合现有业务状态。
   */
  const aiDeviceContext: AiDeviceContext | null = showNoDeviceState ? null : {
    platform: sel.platform,
    serial: sel.serial,
    model: sel.model,
    osVersion: sel.osVersion,
    status: sel.status,
  };

  /**
   * 提供当前设备最近日志快照；AI 分析只读快照，不修改日志页过滤和采集状态。
   */
  const getRecentAiLogs = useCallback((): AiAssistantLogLine[] => logs.slice(-500).map(line => ({
    timestamp: line.timestamp,
    level: line.level,
    pid: line.pid,
    tag: line.tag,
    message: line.message,
  })), [logs]);

  // commands per platform
  const quickCmds = sel.platform==="android" ? [
    { cmd:`adb -s ${sel.serial} shell getprop ro.build.version.release`, desc:"系统版本" },
    { cmd:`adb -s ${sel.serial} shell dumpsys battery`,                   desc:"电池信息" },
    { cmd:`adb -s ${sel.serial} logcat -d > device.log`,                  desc:"导出日志" },
    { cmd:`adb -s ${sel.serial} shell ls /sdcard/`,                       desc:"存储目录" },
  ] : sel.platform==="harmony" ? [
    { cmd:`hdc -t ${sel.serial} shell version`,            desc:"系统版本" },
    { cmd:`hdc -t ${sel.serial} hilog -d > device.log`,   desc:"导出 HiLog" },
    { cmd:`hdc -t ${sel.serial} file recv /sdcard/ ./`,   desc:"拉取存储" },
  ] : [
    { cmd:`idevice_id -l`,                                     desc:"列出设备" },
    { cmd:`idevicesyslog -u ${sel.serial.slice(0,8)}…`,        desc:"系统日志" },
    { cmd:`idevicebackup2 backup --full ~/backup/`,            desc:"完整备份" },
  ];

  return (
    <div className="flex h-screen bg-background text-foreground overflow-hidden select-none"
      style={{fontFamily:"'Inter',-apple-system,BlinkMacSystemFont,sans-serif"}}>
      {/* 顶部浮层提示用于目录空/无权限这类瞬时反馈，避免挤占文件树路径状态区。 */}
      {topTip && (
        <div className="fixed top-4 left-1/2 z-50 -translate-x-1/2 pointer-events-none">
          <div className="max-w-[520px] rounded-lg border border-white/10 bg-zinc-950/88 px-4 py-2 text-[12px] text-white shadow-[0_12px_36px_rgba(0,0,0,0.28)] backdrop-blur-xl">
            <span className="mr-2 inline-block h-2 w-2 rounded-full bg-amber-300 align-middle"/>
            {topTip}
          </div>
        </div>
      )}
      {appContextMenu && (
        <div
          className="fixed z-50 w-40 overflow-hidden rounded-lg border border-border bg-background py-1 shadow-xl"
          style={{left: appContextMenu.x, top: appContextMenu.y}}
          onClick={event=>event.stopPropagation()}
        >
          <button
            onClick={()=>loadAppDetail(appContextMenu.app)}
            className="flex w-full items-center gap-2 px-3 py-2 text-left text-[12px] text-foreground transition-colors hover:bg-black/5 dark:hover:bg-white/8"
          >
            <Info size={13}/>
            查看详情
          </button>
          <button
            onClick={()=>requestUninstallApp(appContextMenu.app)}
            disabled={appContextMenu.app.systemApp}
            title={appContextMenu.app.systemApp ? "系统应用不支持直接卸载" : "卸载应用"}
            className="flex w-full items-center gap-2 px-3 py-2 text-left text-[12px] text-red-500 transition-colors hover:bg-red-500/8 disabled:cursor-not-allowed disabled:text-muted-foreground disabled:hover:bg-transparent"
          >
            <Trash2 size={13}/>
            卸载应用
          </button>
        </div>
      )}
      {fileContextMenu && (
        <div
          className="fixed z-50 w-44 overflow-hidden rounded-lg border border-border bg-background py-1 shadow-xl"
          style={{left: fileContextMenu.x, top: fileContextMenu.y}}
          onClick={event=>event.stopPropagation()}
        >
          <button
            onClick={()=>showFileDetail(fileContextMenu.file)}
            className="flex w-full items-center gap-2 px-3 py-2 text-left text-[12px] text-foreground transition-colors hover:bg-black/5 dark:hover:bg-white/8"
          >
            <Info size={13}/>
            文件详情
          </button>
          <button
            onClick={()=>copyFileName(fileContextMenu.file)}
            className="flex w-full items-center gap-2 px-3 py-2 text-left text-[12px] text-foreground transition-colors hover:bg-black/5 dark:hover:bg-white/8"
          >
            <Copy size={13}/>
            复制文件名称
          </button>
          <button
            onClick={()=>requestRenameFile(fileContextMenu.file)}
            className="flex w-full items-center gap-2 px-3 py-2 text-left text-[12px] text-foreground transition-colors hover:bg-black/5 dark:hover:bg-white/8"
          >
            <Pencil size={13}/>
            重命名
          </button>
          <button
            onClick={()=>copyFileNode(fileContextMenu.file)}
            disabled={!backendOnline}
            className="flex w-full items-center gap-2 px-3 py-2 text-left text-[12px] text-foreground transition-colors hover:bg-black/5 disabled:cursor-not-allowed disabled:text-muted-foreground dark:hover:bg-white/8"
          >
            <Copy size={13}/>
            创建副本
          </button>
          <button
            onClick={()=>downloadFileNode(fileContextMenu.file)}
            disabled={!backendOnline}
            className="flex w-full items-center gap-2 px-3 py-2 text-left text-[12px] text-foreground transition-colors hover:bg-black/5 disabled:cursor-not-allowed disabled:text-muted-foreground dark:hover:bg-white/8"
          >
            <Download size={13}/>
            拉取到本地
          </button>
          <button
            onClick={()=>requestDeleteFile(fileContextMenu.file)}
            disabled={!backendOnline}
            className="flex w-full items-center gap-2 px-3 py-2 text-left text-[12px] text-red-500 transition-colors hover:bg-red-500/8 disabled:cursor-not-allowed disabled:text-muted-foreground disabled:hover:bg-transparent"
          >
            <Trash2 size={13}/>
            删除文件
          </button>
        </div>
      )}

      {/* ════════════════════════ SIDEBAR ════════════════════════ */}
      {/*
        Floats over the bg with frosted-glass: absolute positioning trick isn't
        needed — we give it a high backdrop-blur and semi-transparent bg so the
        page bg bleeds through, creating the glass feel without z-index games.
      */}
      <aside className="w-52 shrink-0 flex flex-col border-r border-border/60
        bg-white/60 dark:bg-[#1c1c1e]/70 backdrop-blur-2xl">

        {/* Brand */}
        <div className="flex items-center gap-2.5 px-4 h-12 border-b border-border/40">
          <div className="w-7 h-7 rounded-lg bg-white/80 dark:bg-white/10 flex items-center justify-center shadow-sm ring-1 ring-border/60 overflow-hidden">
            {/* 主界面品牌图标必须与 Electron 原生图标使用同一份 DevBridge_logo，避免多端标识不一致。 */}
            <img src="/DevBridge_logo.png" alt="" className="h-full w-full object-cover" aria-hidden="true" />
          </div>
          <div className="leading-none">
            <p className="text-[13px] font-semibold text-foreground">{APP_NAME}</p>
            <p className="text-[10px] text-muted-foreground mt-0.5">{appVersion}</p>
          </div>
        </div>

        {/* Devices section */}
        <div className="px-3 pt-4 pb-1">
          <p className="text-[10px] font-semibold uppercase tracking-widest text-muted-foreground px-1 mb-2">设备列表</p>
        </div>
        <nav className="flex-1 overflow-y-auto px-2 space-y-0.5">
          {devices.length === 0 && (
            <div className="mx-1 rounded-xl border border-dashed border-border/70 px-3 py-4 text-center">
              <PlugZap size={16} className="mx-auto mb-2 text-muted-foreground"/>
              <p className="text-[12px] font-medium text-foreground/75">等待设备连接</p>
              <p className="mt-1 text-[10px] leading-relaxed text-muted-foreground">通过 USB 连接并完成调试授权后会自动显示</p>
            </div>
          )}
          {devices.map(d=>{
            const active = sel.id===d.id;
            return (
              <button key={d.id} onClick={()=>selectDevice(d)}
                className={`group w-full flex items-center gap-2.5 px-2.5 py-2 rounded-xl text-left transition-all duration-150
                  ${active ? "bg-primary/12 dark:bg-primary/15" : "hover:bg-black/5 dark:hover:bg-white/6"}`}>
                {/* Platform icon circle */}
                <div className={`w-7 h-7 rounded-lg flex items-center justify-center shrink-0 transition-colors
                  ${PLT_COLOR[d.platform]} ${active ? "opacity-100":"opacity-70 group-hover:opacity-90"}`}>
                  {PLT_ICON[d.platform]}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-1.5">
                    <span className={`text-[12px] font-medium truncate leading-none
                      ${active ? "text-primary" : "text-foreground/85"}`}>{d.model}</span>
                    <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${STATUS_DOT[d.status]}`}/>
                  </div>
                  <span className="text-[10px] text-muted-foreground mt-0.5 block">{PLT_LABEL[d.platform]} {d.osVersion.split(" ")[1]||""}</span>
                </div>
              </button>
            );
          })}
        </nav>

        {/* Divider */}
        <div className="mx-3 h-px bg-border/50 my-2"/>

        {/* Tool status */}
        <div className="px-3 pb-4 space-y-1">
          <p className="text-[10px] font-semibold uppercase tracking-widest text-muted-foreground px-1 mb-2">工具状态</p>
          {toolRows.length === 0 && (
            <div className="px-2 py-2 text-[11px] leading-relaxed text-muted-foreground/70">
              启动本机后端后显示工具状态
            </div>
          )}
          {toolRows.map(t=>(
            <div key={t.n} title={t.detail} className="flex items-center gap-2 px-2 py-1.5 rounded-lg hover:bg-black/4 dark:hover:bg-white/5 transition-colors">
              {t.ok
                ? <CheckCircle size={12} className="text-emerald-500 shrink-0"/>
                : <XCircle size={12} className="text-red-400 shrink-0"/>}
              <span className={`text-[12px] font-mono flex-1 ${t.ok?"text-foreground/70":"text-muted-foreground/50"}`}>{t.n}</span>
              <span className={`text-[10px] font-medium ${t.ok?"text-emerald-500":"text-red-400"}`}>{t.ok?"就绪":"缺失"}</span>
            </div>
          ))}
        </div>
      </aside>

      {/* ════════════════════════ MAIN ════════════════════════ */}
      <div className="flex-1 flex flex-col overflow-hidden">

        {/* ── Topbar ── */}
        <header className="flex items-center gap-3 px-5 h-12 border-b border-border/60
          bg-white/60 dark:bg-[#1c1c1e]/70 backdrop-blur-2xl shrink-0">
          {/* Device summary */}
          <div className="flex items-center gap-2">
            <div className={`w-7 h-7 rounded-lg flex items-center justify-center ${PLT_COLOR[sel.platform]}`}>
              {PLT_ICON[sel.platform]}
            </div>
            <div className="leading-none">
              <p className="text-[13px] font-semibold text-foreground">{sel.model}</p>
              <p className="text-[10px] font-mono text-muted-foreground">{sel.serial.length>18?sel.serial.slice(0,18)+"…":sel.serial}</p>
            </div>
          </div>
          {/* Status pill */}
          {sel.status==="connected" && (
            <span className="flex items-center gap-1.5 px-2 py-0.5 rounded-full bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 text-[11px] font-medium">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-500"/>已连接
            </span>
          )}
          {sel.status==="unauthorized" && (
            <span className="flex items-center gap-1.5 px-2 py-0.5 rounded-full bg-amber-500/10 text-amber-600 dark:text-amber-400 text-[11px] font-medium">
              <span className="w-1.5 h-1.5 rounded-full bg-amber-400"/>待授权
            </span>
          )}
          {!showNoDeviceState && sel.status==="offline" && (
            <span className="flex items-center gap-1.5 px-2 py-0.5 rounded-full bg-zinc-500/10 text-zinc-500 text-[11px] font-medium">
              <span className="w-1.5 h-1.5 rounded-full bg-zinc-400"/>离线
            </span>
          )}
          {sel.battery!==undefined && sel.status==="connected" && (
            <BatteryStrip v={sel.battery}/>
          )}

          <div className="ml-auto flex items-center gap-2">
            <span className="text-[11px] text-muted-foreground hidden lg:block max-w-[320px] truncate" title={apiHint}>{apiHint}</span>
            {actionHint && <span className="text-[11px] text-muted-foreground hidden xl:block max-w-[220px] truncate" title={actionHint}>{actionHint}</span>}
            <span
              title={runtimeTitle}
              className="hidden md:inline-flex items-center gap-1.5 px-2 py-1 rounded-lg bg-black/5 dark:bg-white/6 text-[11px] text-muted-foreground max-w-[150px]"
            >
              <Monitor size={12} className="shrink-0"/>
              <span className="truncate">{runtimeLabel}</span>
            </span>
            <span className="text-[11px] text-muted-foreground hidden md:block">{connCount} 台在线</span>
            <button onClick={()=>refresh()}
              className="p-1.5 rounded-lg hover:bg-black/6 dark:hover:bg-white/8 transition-colors text-muted-foreground hover:text-foreground">
              <RefreshCw size={13} className={spinning?"animate-spin text-primary":""}/>
            </button>
            <ThemeToggle mode={mode} setTheme={setTheme}/>
          </div>
        </header>

        {/* ── State: no real device ── */}
        {showNoDeviceState && (
          <div className="flex-1 flex items-center justify-center">
            <div className="text-center max-w-sm space-y-4">
              <div className="w-16 h-16 rounded-3xl bg-primary/10 flex items-center justify-center mx-auto">
                <PlugZap size={28} className="text-primary"/>
              </div>
              <h2 className="text-base font-semibold">等待设备连接</h2>
              <p className="text-sm text-muted-foreground leading-relaxed">
                请通过 USB 连接设备，并确认 Android USB 调试或 iOS 信任授权已完成。
              </p>
              {!backendOnline && (
                <p className="text-[12px] text-muted-foreground/70">当前本机后端未连接，无法读取真实设备数据。</p>
              )}
            </div>
          </div>
        )}

        {/* ── State: non-connected ── */}
        {sel.status==="unauthorized" && (
          <div className="flex-1 flex items-center justify-center">
            <div className="text-center max-w-sm space-y-4">
              <div className="w-16 h-16 rounded-3xl bg-amber-500/10 flex items-center justify-center mx-auto">
                <AlertTriangle size={28} className="text-amber-500"/>
              </div>
              <h2 className="text-base font-semibold">需要设备授权</h2>
              <p className="text-sm text-muted-foreground leading-relaxed">请在设备上开启 USB 调试并在弹出的授权对话框中点击"允许"。</p>
              <div className="rounded-xl bg-black/4 dark:bg-white/6 border border-border px-4 py-2.5 text-left">
                <code className="text-[12px] font-mono text-muted-foreground">adb devices</code>
              </div>
            </div>
          </div>
        )}
        {showOfflineState && (
          <div className="flex-1 flex items-center justify-center">
            <div className="text-center max-w-sm space-y-4">
              <div className="w-16 h-16 rounded-3xl bg-zinc-500/10 flex items-center justify-center mx-auto">
                <WifiOff size={28} className="text-zinc-400"/>
              </div>
              <h2 className="text-base font-semibold">设备已离线</h2>
              <p className="text-sm text-muted-foreground">请通过 USB 连接设备并确认设备已开机。</p>
            </div>
          </div>
        )}

        {/* ── Connected view ── */}
        {showWorkspace && (
          <>
            {/* Tabs */}
            <div className="flex items-center gap-0.5 px-5 border-b border-border/60
              bg-white/40 dark:bg-[#1c1c1e]/50 backdrop-blur-xl shrink-0">
              {([
                {id:"info"  as Tab, label:"设备信息", icon:<Info size={12}/>},
                {id:"files" as Tab, label:"文件管理", icon:<HardDrive size={12}/>},
                {id:"logs"  as Tab, label:"实时日志", icon:<Terminal size={12}/>},
                {id:"apps"  as Tab, label:"应用管理", icon:<Package size={12}/>},
              ]).map(t=>(
                <button key={t.id} onClick={()=>selectTab(t.id)}
                  className={`flex items-center gap-1.5 px-3.5 py-3 text-[12px] font-medium border-b-2 transition-all duration-150
                    ${tab===t.id
                      ? "border-primary text-primary"
                      : "border-transparent text-muted-foreground hover:text-foreground"}`}>
                  {t.icon}{t.label}
                </button>
              ))}
            </div>

            {/* Tab content */}
            <div className="flex-1 overflow-hidden bg-background/80">

              {/* ── Info ── */}
              {tab==="info" && (
                <div className="h-full flex overflow-hidden">
                  {/* 左侧：设备信息滚动区；详情内容需要支持鼠标选择和复制。 */}
                  <div className="overflow-y-auto select-text" style={{flex: "1 1 auto", minWidth: 0}}>
                  <div className="px-8 py-7 space-y-8">

                    {/* ── Android ── */}
                    {sel.platform==="android" && <>
                      <InfoSection title="设备标识">
                        <PropList rows={[
                          {k:"品牌 / 型号",  v:`${sel.brand??""} ${sel.model}`.trim(), mono:false},
                          {k:"序列号",       v:sel.serial,          mono:true},
                        ]}/>
                      </InfoSection>
                      <InfoSection title="系统">
                        <PropList rows={[
                          {k:"Android 版本", v:sel.osVersion,        mono:false},
                          {k:"API 级别",     v:sel.apiLevel??"—",    mono:true},
                          {k:"安全补丁",     v:sel.securityPatch??"—", mono:false},
                          {k:"内核版本",     v:sel.kernelVersion??"—", mono:true},
                          {k:"基带版本",     v:sel.baseband??"—",    mono:true},
                          {k:"Bootloader",   v:sel.bootloader??"—",  mono:true},
                          {k:"Build 指纹",   v:sel.buildFingerprint??"—", mono:true},
                        ]}/>
                      </InfoSection>
                      <InfoSection title="硬件">
                        <PropList rows={[
                          {k:"处理器",  v:sel.cpu??"—",        mono:false},
                          {k:"GPU",     v:sel.gpu??"—",        mono:false},
                          {k:"内存",    v:sel.ram??"—",        mono:false},
                          {k:"存储",    v:sel.storage??"—",    mono:false},
                          {k:"分辨率",  v:sel.resolution??"—", mono:false},
                          {k:"像素密度", v:sel.density??"—",   mono:false},
                        ]}/>
                      </InfoSection>
                      <InfoSection title="电量">
                        <PropList rows={[
                          {k:"当前电量", v:sel.battery!==undefined?`${sel.battery}%`:"—", mono:false},
                          {k:"充电状态", v:"USB 充电中", mono:false},
                        ]}/>
                      </InfoSection>
                    </>}

                    {/* ── iOS ── */}
                    {sel.platform==="ios" && <>
                      <InfoSection title="设备标识">
                        <PropList rows={[
                          {k:"设备名称",       v:sel.deviceName??"—",            mono:false},
                          {k:"型号",           v:sel.model,                    mono:false},
                          {k:"机型标识符",     v:sel.modelIdentifier??"—",     mono:true},
                          {k:"UDID",           v:sel.serial,                   mono:true},
                          {k:"ECID",           v:sel.ecid??"—",                mono:true},
                          {k:"激活状态",       v:sel.activationState??"—",     mono:false},
                        ]}/>
                      </InfoSection>
                      <InfoSection title="系统">
                        <PropList rows={[
                          {k:"iOS 版本",    v:sel.osVersion,       mono:false},
                          {k:"Build 号",   v:sel.buildNumber??"—", mono:true},
                        ]}/>
                      </InfoSection>
                      <InfoSection title="硬件">
                        <PropList rows={[
                          {k:"设备类型",    v:sel.deviceClass??"—",                       mono:false},
                          {k:"硬件型号",    v:sel.hardwareModel??"—",                    mono:true},
                          {k:"硬件平台",    v:sel.hardwarePlatform??"—",                 mono:true},
                          {k:"销售型号",    v:sel.modelNumber??"—",                      mono:true},
                          {k:"处理器",      v:sel.cpu??"—",                              mono:false},
                          {k:"CPU 架构",    v:sel.cpuArchitecture??"—",                  mono:true},
                          {k:"内存",        v:sel.ram??"—",                              mono:false},
                          {k:"存储",        v:sel.storage??"—",                          mono:false},
                          {k:"分辨率",      v:sel.resolution??"—",                       mono:false},
                          {k:"像素密度",    v:sel.density??"—",                          mono:false},
                          {k:"NFC",         v:sel.nfcSupport===undefined?"—":sel.nfcSupport?"支持":"不支持", mono:false},
                        ]}/>
                      </InfoSection>
                      <InfoSection title="电量">
                        <PropList rows={[
                          {k:"当前电量", v:sel.battery!==undefined?`${sel.battery}%`:"—", mono:false},
                          {k:"充电状态", v:"USB 充电中", mono:false},
                        ]}/>
                      </InfoSection>
                    </>}

                    {/* ── HarmonyOS ── */}
                    {sel.platform==="harmony" && <>
                      <InfoSection title="设备标识">
                        <PropList rows={[
                          {k:"品牌 / 型号",    v:`${sel.brand??""} ${sel.model}`.trim(), mono:false},
                          {k:"序列号",         v:sel.serial,               mono:true},
                          {k:"HDC 序列号",     v:sel.hdcSerial??"—",       mono:true},
                        ]}/>
                      </InfoSection>
                      <InfoSection title="系统">
                        <PropList rows={[
                          {k:"HarmonyOS 版本", v:sel.osVersion,               mono:false},
                          {k:"API 版本",       v:sel.harmonyApiVersion??"—",  mono:true},
                          {k:"EMUI 版本",      v:sel.emui??"—",              mono:false},
                          {k:"安全补丁",       v:sel.securityPatch??"—",      mono:false},
                          {k:"编译类型",       v:sel.buildType??"—",          mono:true},
                        ]}/>
                      </InfoSection>
                      <InfoSection title="硬件">
                        <PropList rows={[
                          {k:"SoC",      v:sel.hiSiliconSoc??"—",  mono:false},
                          {k:"处理器",   v:sel.cpu??"—",            mono:false},
                          {k:"GPU",      v:sel.gpu??"—",            mono:false},
                          {k:"内存",     v:sel.ram??"—",            mono:false},
                          {k:"存储",     v:sel.storage??"—",        mono:false},
                          {k:"分辨率",   v:sel.resolution??"—",     mono:false},
                          {k:"像素密度", v:sel.density??"—",        mono:false},
                        ]}/>
                      </InfoSection>
                      <InfoSection title="电量">
                        <PropList rows={[
                          {k:"当前电量", v:sel.battery!==undefined?`${sel.battery}%`:"—", mono:false},
                          {k:"充电状态", v:"USB 充电中", mono:false},
                        ]}/>
                      </InfoSection>
                    </>}

                    {/* 常用命令（三平台通用） */}
                    <InfoSection title="常用命令">
                      <div className="space-y-0 divide-y divide-border/30">
                        {quickCmds.map(c=>(
                          <div key={c.cmd} className="flex items-center gap-4 py-2">
                            <code className="flex-1 text-[11px] font-mono text-foreground/65 truncate">{c.cmd}</code>
                            <span className="text-[11px] text-muted-foreground/50 shrink-0 hidden sm:block">{c.desc}</span>
                          </div>
                        ))}
                      </div>
                    </InfoSection>

                  </div>
                  </div>

                  {/* 右侧：仅 Android 渲染截图预览面板，其它平台当前没有后端截图能力。 */}
                  {showScreenshotPanel && (
                    <ScreenshotPanel
                      device={sel}
                      backendOnline={backendOnline}
                      autoCaptureKey={autoScreenshotKey}
                      snapshot={screenshotsByDevice[logDeviceKey(sel)]}
                      onAutoCapture={markAutoScreenshot}
                      onSnapshot={updateScreenshotSnapshot}
                    />
                  )}
                </div>
              )}

              {/* ── Files ── */}
              {tab==="files" && (
                <div className="flex h-full overflow-hidden">
                  {/* Tree */}
                  <div className="w-60 border-r border-border/50 bg-white/40 dark:bg-white/3 backdrop-blur-sm overflow-y-auto py-3 shrink-0">
                    <p className="text-[10px] font-semibold uppercase tracking-widest text-muted-foreground px-4 mb-2">文件</p>
                    {sel.platform==="ios" ? (
                      <div className="px-4 py-8 text-center space-y-3">
                        <Apple size={22} className="text-muted-foreground mx-auto"/>
                        <p className="text-[12px] text-muted-foreground leading-relaxed">iOS 不支持直接浏览文件，请通过备份提取。</p>
                        <div className="rounded-xl bg-black/4 dark:bg-white/6 border border-border px-3 py-2">
                          <code className="text-[10px] font-mono text-muted-foreground">idevicebackup2 backup --full</code>
                        </div>
                      </div>
                    ) : (
                      <div className="px-2">
                        <div className="px-2 pb-2 space-y-1">
                          <button
                            type="button"
                            onDoubleClick={copyFilePath}
                            title={`${filePath}（双击复制）`}
                            className="w-full min-w-0 flex items-center text-left text-[10px] font-mono text-muted-foreground hover:text-foreground transition-colors"
                          >
                            {displayPath.prefix && (
                              <span className="min-w-0 truncate">{displayPath.prefix}</span>
                            )}
                            <span className="shrink-0">{displayPath.leaf}</span>
                          </button>
                          {filePath !== "/" && backendOnline && (
                            <button onClick={loadParentFiles}
                              className="text-[11px] text-primary hover:underline">返回上级</button>
                          )}
                        </div>
                        {remoteFiles.length === 0 && (
                          <div className="px-2 py-8 text-center">
                            <Folder size={18} className="mx-auto mb-2 text-muted-foreground/50"/>
                            <p className="text-[12px] text-muted-foreground">当前目录没有可显示内容</p>
                          </div>
                        )}
                        {remoteFiles.map(n=>(
                          <TreeNode
                            key={n.path}
                            node={n}
                            onSelect={selectFileNode}
                            onOpenDir={openFileDirectory}
                            onContextMenu={openFileContextMenu}
                            sel={file?.path??null}
                            directoryStates={directoryStates}
                            device={sel}
                          />
                        ))}
                      </div>
                    )}
                  </div>

                  {/* Detail：文件详情内容支持鼠标选择和复制。 */}
                  <div className="flex-1 p-6 overflow-y-auto select-text">
                    {file ? (
                      <div className="max-w-xl space-y-7">
                        {/* 文件标题 */}
                        <div className="flex items-center gap-3">
                          <div className={`w-9 h-9 rounded-xl flex items-center justify-center shrink-0 ${file.type==="dir"?"bg-amber-500/10":"bg-primary/10"}`}>
                            {file.type==="dir" ? <Folder size={18} className="text-amber-500"/> : <File size={18} className="text-primary"/>}
                          </div>
                          <div className="min-w-0">
                            <div className="flex min-w-0 items-center gap-1.5">
                              <p className="truncate text-[14px] font-semibold text-foreground leading-none" title={file.name}>{file.name}</p>
                              <button
                                type="button"
                                onClick={()=>copyFileName(file)}
                                title="复制文件名称"
                                className="shrink-0 rounded-md p-1 text-muted-foreground transition-colors hover:bg-black/5 hover:text-foreground dark:hover:bg-white/8"
                              >
                                <Copy size={12}/>
                              </button>
                            </div>
                            <p className="text-[11px] text-muted-foreground mt-1">{file.type==="dir"?"目录":"文件"}</p>
                          </div>
                        </div>

                        {file.type==="file" && <FilePreviewPanel device={sel} file={file} online={backendOnline}/>}

                        {/* ── 基本信息 ── */}
                        <section>
                          <p className="text-[10px] font-semibold uppercase tracking-widest text-muted-foreground mb-1">基本信息</p>
                          <PropList rows={[
                            {k:"完整路径", v:file.path,   mono:true},
                            {k:"类型",     v:file.type==="dir"?"目录":`文件${file.mimeType?` (${file.mimeType})`:""}`, mono:false},
                            ...(file.size?[{k:"大小",    v:`${file.size}${file.sizeBytes?` (${file.sizeBytes.toLocaleString()} 字节)`:""}`, mono:false}]:[]),
                            ...(file.inode!==undefined?[{k:"Inode", v:String(file.inode), mono:true}]:[]),
                          ]}/>
                        </section>

                        {/* ── 时间信息 ── */}
                        {(file.modified||file.created||file.accessed) && (
                          <section>
                            <p className="text-[10px] font-semibold uppercase tracking-widest text-muted-foreground mb-1">时间</p>
                            <PropList rows={[
                              ...(file.modified?[{k:"修改时间", v:file.modified, mono:false}]:[]),
                              ...(file.created? [{k:"创建时间", v:file.created,  mono:false}]:[]),
                              ...(file.accessed?[{k:"访问时间", v:file.accessed, mono:false}]:[]),
                            ]}/>
                          </section>
                        )}

                        {/* ── 权限信息 ── */}
                        {(file.permissions||file.owner||file.group) && (
                          <section>
                            <p className="text-[10px] font-semibold uppercase tracking-widest text-muted-foreground mb-1">权限</p>
                            <PropList rows={[
                              ...(file.permissions?[{k:"权限",  v:file.permissions, mono:true}]:[]),
                              ...(file.owner?      [{k:"所有者", v:file.owner,       mono:true}]:[]),
                              ...(file.group?      [{k:"所属组", v:file.group,       mono:true}]:[]),
                            ]}/>
                          </section>
                        )}

                        {/* ── 校验信息（仅文件） ── */}
                        {file.type==="file" && file.md5 && (
                          <section>
                            <p className="text-[10px] font-semibold uppercase tracking-widest text-muted-foreground mb-1">完整性</p>
                            <PropList rows={[{k:"MD5", v:file.md5, mono:true}]}/>
                          </section>
                        )}

                        {/* ── 操作 ── */}
                        {file.type==="file" && (
                          <section className="space-y-3 pt-1">
                            <button onClick={downloadSelectedFile}
                              className="flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-white text-[12px] font-medium hover:opacity-90 transition-opacity disabled:opacity-50"
                              disabled={!backendOnline || sel.platform!=="android"}>
                              <Download size={12}/>拉取到本地
                            </button>
                            <div>
                              <p className="text-[10px] font-semibold uppercase tracking-widest text-muted-foreground mb-2">执行命令</p>
                              <code className="block text-[11px] font-mono text-foreground/60 break-all leading-relaxed border-l-2 border-primary/30 pl-3">
                                {sel.platform==="android"
                                  ?`adb -s ${sel.serial} pull "${file.path}" ./`
                                  :`hdc -t ${sel.serial} file recv "${file.path}" ./`}
                              </code>
                            </div>
                          </section>
                        )}
                      </div>
                    ) : (
                      <div className="h-full flex items-center justify-center">
                        <p className="text-[13px] text-muted-foreground">选择文件或目录以查看详情</p>
                      </div>
                    )}
                  </div>
                </div>
              )}

              {/* ── Logs ── */}
              {tab==="logs" && (
                <div className="flex flex-col h-full overflow-hidden">
                  {/* Toolbar */}
                  <div className="flex items-center gap-2 px-4 py-2.5 border-b border-border/50
                    bg-white/40 dark:bg-white/3 backdrop-blur-sm shrink-0 flex-wrap">
                    <button onClick={()=>streaming ? stopLogStream() : startLogStream()}
                      className={`flex items-center gap-1.5 px-3.5 py-1.5 rounded-lg text-[12px] font-medium transition-all shadow-sm
                        ${streaming
                          ?"bg-red-500/10 text-red-500 border border-red-500/20 hover:bg-red-500/16"
                          :"bg-primary text-white hover:opacity-88"}`}>
                      {streaming?<><Pause size={11}/>暂停</>:<><Play size={11}/>开始采集</>}
                    </button>
                    {[
                      {icon:<Trash2 size={11}/>, label:"清除", action:()=>resetLogHistory()},
                      {icon:<Download size={11}/>, label:"导出", action:exportLogs},
                    ].map(b=>(
                      <button key={b.label} onClick={b.action}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[12px] font-medium border border-border/60 text-muted-foreground hover:text-foreground hover:border-foreground/20 transition-all bg-white/60 dark:bg-white/4">
                        {b.icon}{b.label}
                      </button>
                    ))}
                    <div className="h-4 w-px bg-border/50"/>
                    {/* Level filter */}
                    <div className="flex items-center gap-0.5 p-0.5 rounded-lg bg-black/5 dark:bg-white/6">
                      {LOG_LEVELS.map(l=>(
                        <button key={l} onClick={()=>setLevel(l)}
                          className={`px-2.5 py-1 rounded-md text-[10px] font-mono font-semibold transition-all duration-100
                            ${level===l ? "bg-white dark:bg-white/15 shadow-sm text-foreground" : "text-muted-foreground hover:text-foreground"}`}>
                          {l}
                        </button>
                      ))}
                    </div>
                    {/* Search */}
                    <div className="ml-auto flex items-center gap-2 bg-white/70 dark:bg-white/6 border border-border/60 rounded-lg px-3 py-1.5 shadow-sm">
                      <Search size={12} className="text-muted-foreground"/>
                      <input value={filter} onChange={e=>setFilter(e.target.value)}
                        placeholder="过滤 tag 或内容…"
                        className="bg-transparent text-[12px] text-foreground placeholder:text-muted-foreground outline-none w-40"/>
                    </div>
                    <span className="text-[10px] font-mono text-muted-foreground">
                      {visibleLogs.length}{filteredLogs.length > visibleLogs.length ? ` / ${filteredLogs.length}` : ""} 行
                    </span>
                  </div>

                  {/* Log output：日志输出内容支持鼠标选择和复制。 */}
                  <div className="flex-1 overflow-y-auto select-text"
                    style={{fontFamily:"'JetBrains Mono',monospace",scrollbarWidth:"thin",scrollbarColor:"rgba(0,0,0,0.1) transparent"}}>
                    <table className="w-full text-[11px]">
                      <thead className="sticky top-0 z-10 bg-white/80 dark:bg-[#1c1c1e]/80 backdrop-blur-sm">
                        <tr className="border-b border-border/40">
                          {["时间","级别","PID","Tag","消息"].map(h=>(
                            <th key={h} className="text-left px-3 py-2 text-[10px] font-semibold text-muted-foreground font-sans">{h}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {visibleLogs.map(line=>(
                          <tr key={line.id} className="border-b border-border/20 hover:bg-black/3 dark:hover:bg-white/4 transition-colors">
                            <td className="px-3 py-1.5 text-muted-foreground/60 whitespace-nowrap text-[10px]">{line.timestamp}</td>
                            <td className="px-3 py-1.5 whitespace-nowrap">
                              <span className={`font-bold ${LOG_COLOR[line.level]}`}>{line.level}</span>
                            </td>
                            <td className="px-3 py-1.5 text-muted-foreground/50 text-[10px]">{line.pid}</td>
                            <td className="px-3 py-1.5 text-primary/60 max-w-[140px] truncate text-[10px]">{line.tag}</td>
                            <td className={`px-3 py-1.5 ${LOG_COLOR[line.level]}`}>{line.message}</td>
                          </tr>
                        ))}
                        {visibleLogs.length===0&&(
                          <tr><td colSpan={5} className="py-16 text-center text-[13px] text-muted-foreground font-sans">无匹配日志</td></tr>
                        )}
                      </tbody>
                    </table>
                    <div ref={logEnd}/>
                  </div>
                </div>
              )}

              {/* ── Apps ── */}
              {tab==="apps" && (
                <div className="flex flex-col h-full overflow-hidden">
                  <div className="flex items-center gap-2 px-4 py-2.5 border-b border-border/50
                    bg-white/40 dark:bg-white/3 backdrop-blur-sm shrink-0 flex-wrap">
                    <button onClick={()=>loadApps(true)}
                      disabled={appsLoading || sel.platform!=="android" || sel.status!=="connected" || !backendOnline}
                      className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[12px] font-medium border border-border/60 text-muted-foreground hover:text-foreground hover:border-foreground/20 transition-all bg-white/60 dark:bg-white/4 disabled:opacity-50">
                      <RefreshCw size={11} className={appsLoading ? "animate-spin" : ""}/>刷新
                    </button>
                    <div className="ml-auto flex items-center gap-2 bg-white/70 dark:bg-white/6 border border-border/60 rounded-lg px-3 py-1.5 shadow-sm">
                      <Search size={12} className="text-muted-foreground"/>
                      <input value={appFilter} onChange={e=>setAppFilter(e.target.value)}
                        placeholder="搜索应用名称、包名或版本…"
                        className="bg-transparent text-[12px] text-foreground placeholder:text-muted-foreground outline-none w-56"/>
                    </div>
                    <span className="text-[10px] font-mono text-muted-foreground">
                      {filteredApps.length}{selectedApps.length > filteredApps.length ? ` / ${selectedApps.length}` : ""} 个应用
                    </span>
                  </div>

                  {sel.platform!=="android" ? (
                    <div className="flex-1 flex items-center justify-center">
                      <div className="text-center max-w-sm space-y-3">
                        <Package size={24} className="mx-auto text-muted-foreground"/>
                        <p className="text-[13px] text-muted-foreground">当前平台暂不支持应用管理</p>
                      </div>
                    </div>
                  ) : appsLoading && selectedApps.length===0 ? (
                    <div className="flex-1 flex items-center justify-center text-[13px] text-muted-foreground">
                      正在读取已安装应用…
                    </div>
                  ) : appsError && selectedApps.length===0 ? (
                    <div className="flex-1 flex items-center justify-center text-[13px] text-red-500">
                      {appsError}
                    </div>
                  ) : (
                    <div className="flex-1 overflow-y-auto select-text" style={{scrollbarWidth:"thin",scrollbarColor:"rgba(0,0,0,0.1) transparent"}}>
                      <table className="w-full text-[12px]">
                        <thead className="sticky top-0 z-10 bg-white/80 dark:bg-[#1c1c1e]/80 backdrop-blur-sm">
                          <tr className="border-b border-border/40">
                            {["应用名称","包名","版本名","版本号","类型"].map(h=>(
                              <th key={h} className="text-left px-4 py-2.5 text-[10px] font-semibold text-muted-foreground">{h}</th>
                            ))}
                          </tr>
                        </thead>
                        <tbody>
                          {filteredApps.map(app=>(
                            <tr key={app.packageName} className="border-b border-border/20 hover:bg-black/3 dark:hover:bg-white/4 transition-colors">
                              <td className="px-4 py-2.5 font-medium max-w-[220px]">
                                <button
                                  onClick={()=>loadAppDetail(app)}
                                  onContextMenu={event=>openAppContextMenu(event, app)}
                                  className="max-w-full truncate text-left text-primary hover:text-primary/80 hover:underline underline-offset-2"
                                  title={app.name}
                                >
                                  {app.name}
                                </button>
                              </td>
                              <td className="px-4 py-2.5 font-mono text-[11px] text-primary/70 select-text break-all">{app.packageName}</td>
                              <td className="px-4 py-2.5 text-muted-foreground">{app.versionName || "—"}</td>
                              <td className="px-4 py-2.5 font-mono text-[11px] text-muted-foreground/80">{app.versionCode || "—"}</td>
                              <td className="px-4 py-2.5">
                                <span className={`px-2 py-0.5 rounded-full text-[10px] font-medium ${app.systemApp ? "bg-amber-500/10 text-amber-600 dark:text-amber-400" : "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400"}`}>
                                  {app.systemApp ? "系统" : "用户"}
                                </span>
                              </td>
                            </tr>
                          ))}
                          {filteredApps.length===0&&(
                            <tr><td colSpan={5} className="py-16 text-center text-[13px] text-muted-foreground">{selectedApps.length===0 ? "未读取到应用" : "无匹配应用"}</td></tr>
                          )}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>
              )}
            </div>
            {uninstallAppTarget && (
              <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 px-4 backdrop-blur-sm">
                <div className="w-[min(460px,100%)] rounded-xl border border-border bg-background px-5 py-5 shadow-2xl">
                  <div className="flex items-start gap-3">
                    <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-red-500/10 text-red-500">
                      <Trash2 size={18}/>
                    </div>
                    <div className="min-w-0 flex-1">
                      <h2 className="text-sm font-semibold text-foreground">确认卸载应用</h2>
                      <p className="mt-1 break-all text-[12px] leading-relaxed text-muted-foreground">
                        将从当前 Android 设备卸载 <span className="font-mono text-foreground">{uninstallAppTarget.packageName}</span>。
                      </p>
                    </div>
                  </div>
                  <div className="mt-4 rounded-lg border border-red-500/20 bg-red-500/6 px-3 py-2 text-[12px] leading-relaxed text-red-500">
                    卸载操作不可自动恢复。请输入完整应用包名后再确认。
                  </div>
                  <input
                    value={uninstallPackageInput}
                    onChange={event=>setUninstallPackageInput(event.target.value.trim())}
                    onKeyDown={event=>{
                      if (event.key === "Enter" && uninstallPackageInput === uninstallAppTarget.packageName) {
                        confirmUninstallApp();
                      }
                    }}
                    placeholder={uninstallAppTarget.packageName}
                    className="mt-4 w-full rounded-lg border border-border bg-white/70 px-3 py-2 font-mono text-[12px] text-foreground outline-none transition-colors placeholder:text-muted-foreground focus:border-primary dark:bg-white/6"
                    disabled={uninstalling}
                  />
                  {uninstallError && (
                    <p className="mt-2 text-[12px] text-red-500">{uninstallError}</p>
                  )}
                  <div className="mt-5 flex justify-end gap-2">
                    <button
                      onClick={closeUninstallDialog}
                      disabled={uninstalling}
                      className="rounded-lg border border-border px-3 py-1.5 text-[12px] font-medium text-muted-foreground transition-colors hover:text-foreground disabled:opacity-50"
                    >
                      取消
                    </button>
                    <button
                      onClick={confirmUninstallApp}
                      disabled={uninstalling || uninstallPackageInput !== uninstallAppTarget.packageName}
                      className="flex items-center gap-1.5 rounded-lg bg-red-500 px-3 py-1.5 text-[12px] font-medium text-white transition-opacity hover:opacity-90 disabled:opacity-40"
                    >
                      {uninstalling && <RefreshCw size={12} className="animate-spin"/>}
                      确认卸载
                    </button>
                  </div>
                </div>
              </div>
            )}
            {deleteFileTarget && (
              <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 px-4 backdrop-blur-sm">
                <div className="w-[min(460px,100%)] rounded-xl border border-border bg-background px-5 py-5 shadow-2xl">
                  <div className="flex items-start gap-3">
                    <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-red-500/10 text-red-500">
                      <Trash2 size={18}/>
                    </div>
                    <div className="min-w-0 flex-1">
                      <h2 className="text-sm font-semibold text-foreground">确认删除文件</h2>
                      <p className="mt-1 break-all text-[12px] leading-relaxed text-muted-foreground">
                        将从当前 Android 设备删除 <span className="font-mono text-foreground">{deleteFileTarget.path}</span>。
                      </p>
                    </div>
                  </div>
                  <div className="mt-4 rounded-lg border border-red-500/20 bg-red-500/6 px-3 py-2 text-[12px] leading-relaxed text-red-500">
                    删除操作不可自动恢复。请输入完整文件名后再确认。
                  </div>
                  <input
                    value={deleteFileNameInput}
                    onChange={event=>setDeleteFileNameInput(event.target.value)}
                    onKeyDown={event=>{
                      if (event.key === "Enter" && deleteFileNameInput === deleteFileTarget.name) {
                        confirmDeleteFile();
                      }
                    }}
                    placeholder={deleteFileTarget.name}
                    className="mt-4 w-full rounded-lg border border-border bg-white/70 px-3 py-2 font-mono text-[12px] text-foreground outline-none transition-colors placeholder:text-muted-foreground focus:border-primary dark:bg-white/6"
                    disabled={deletingFile}
                  />
                  {deleteFileError && (
                    <p className="mt-2 text-[12px] text-red-500">{deleteFileError}</p>
                  )}
                  <div className="mt-5 flex justify-end gap-2">
                    <button
                      onClick={closeDeleteFileDialog}
                      disabled={deletingFile}
                      className="rounded-lg border border-border px-3 py-1.5 text-[12px] font-medium text-muted-foreground transition-colors hover:text-foreground disabled:opacity-50"
                    >
                      取消
                    </button>
                    <button
                      onClick={confirmDeleteFile}
                      disabled={deletingFile || deleteFileNameInput !== deleteFileTarget.name}
                      className="flex items-center gap-1.5 rounded-lg bg-red-500 px-3 py-1.5 text-[12px] font-medium text-white transition-opacity hover:opacity-90 disabled:opacity-40"
                    >
                      {deletingFile && <RefreshCw size={12} className="animate-spin"/>}
                      确认删除
                    </button>
                  </div>
                </div>
              </div>
            )}
            {renameFileTarget && (
              <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4 backdrop-blur-sm">
                <div className="w-[min(460px,100%)] rounded-xl border border-border bg-background px-5 py-5 shadow-2xl">
                  <div className="flex items-start gap-3">
                    <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                      <Pencil size={18}/>
                    </div>
                    <div className="min-w-0 flex-1">
                      <h2 className="text-sm font-semibold text-foreground">重命名文件</h2>
                      <p className="mt-1 break-all text-[12px] leading-relaxed text-muted-foreground">
                        当前文件：<span className="font-mono text-foreground">{renameFileTarget.path}</span>
                      </p>
                    </div>
                  </div>
                  <input
                    value={renameFileNameInput}
                    onChange={event=>setRenameFileNameInput(event.target.value)}
                    onKeyDown={event=>{
                      if (event.key === "Enter" && renameFileNameInput.trim()) {
                        confirmRenameFile();
                      }
                    }}
                    className="mt-4 w-full rounded-lg border border-border bg-white/70 px-3 py-2 font-mono text-[12px] text-foreground outline-none transition-colors focus:border-primary dark:bg-white/6"
                    disabled={renamingFile}
                  />
                  <p className="mt-2 text-[11px] text-muted-foreground">只输入新文件名，不支持包含 / 或 \ 的路径。</p>
                  {renameFileError && (
                    <p className="mt-2 text-[12px] text-red-500">{renameFileError}</p>
                  )}
                  <div className="mt-5 flex justify-end gap-2">
                    <button
                      onClick={closeRenameFileDialog}
                      disabled={renamingFile}
                      className="rounded-lg border border-border px-3 py-1.5 text-[12px] font-medium text-muted-foreground transition-colors hover:text-foreground disabled:opacity-50"
                    >
                      取消
                    </button>
                    <button
                      onClick={confirmRenameFile}
                      disabled={renamingFile || !renameFileNameInput.trim()}
                      className="flex items-center gap-1.5 rounded-lg bg-primary px-3 py-1.5 text-[12px] font-medium text-white transition-opacity hover:opacity-90 disabled:opacity-40"
                    >
                      {renamingFile && <RefreshCw size={12} className="animate-spin"/>}
                      确认重命名
                    </button>
                  </div>
                </div>
              </div>
            )}
            {(appDetailPackage || appDetailLoading || appDetailError) && (
              <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4 backdrop-blur-sm">
                <div className="flex max-h-[86vh] w-[min(860px,100%)] flex-col overflow-hidden rounded-xl border border-border bg-background shadow-2xl">
                  <div className="flex items-start gap-3 border-b border-border/60 px-5 py-4">
                    <div className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                      <Package size={18}/>
                    </div>
                    <div className="min-w-0 flex-1">
                      <h2 className="truncate text-sm font-semibold text-foreground">
                        {appDetail?.name || appDetailPackage || "应用详情"}
                      </h2>
                      <p className="mt-1 break-all font-mono text-[11px] text-muted-foreground">
                        {appDetail?.packageName || appDetailPackage}
                      </p>
                    </div>
                    <button
                      onClick={closeAppDetail}
                      className="rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-black/5 hover:text-foreground dark:hover:bg-white/8"
                      title="关闭"
                    >
                      <XCircle size={17}/>
                    </button>
                  </div>
                  <div className="min-h-[260px] overflow-y-auto px-5 py-4" style={{scrollbarWidth:"thin",scrollbarColor:"rgba(0,0,0,0.1) transparent"}}>
                    {appDetailLoading ? (
                      <div className="flex h-52 items-center justify-center gap-2 text-[13px] text-muted-foreground">
                        <RefreshCw size={14} className="animate-spin"/>
                        正在读取应用详情…
                      </div>
                    ) : appDetailError ? (
                      <div className="flex h-52 flex-col items-center justify-center gap-3 text-center">
                        <AlertTriangle size={22} className="text-red-500"/>
                        <p className="max-w-md text-[13px] text-red-500">{appDetailError}</p>
                      </div>
                    ) : appDetail ? (
                      <div className="space-y-5">
                        <section>
                          <h3 className="mb-2 text-[11px] font-semibold text-muted-foreground">基础信息</h3>
                          <div className="grid grid-cols-1 overflow-hidden rounded-lg border border-border/60 md:grid-cols-2">
                            {appDetailBaseRows.map(([label,value])=>(
                              <div key={label} className="grid grid-cols-[96px_minmax(0,1fr)] border-b border-border/40 px-3 py-2 text-[12px] last:border-b-0 md:[&:nth-last-child(-n+2)]:border-b-0">
                                <span className="text-muted-foreground">{label}</span>
                                <span className="min-w-0 break-all font-medium text-foreground/85">{detailText(value)}</span>
                              </div>
                            ))}
                          </div>
                        </section>
                        <section>
                          <h3 className="mb-2 text-[11px] font-semibold text-muted-foreground">路径与时间</h3>
                          <div className="overflow-hidden rounded-lg border border-border/60">
                            {appDetailPathRows.map(([label,value])=>(
                              <div key={label} className="grid grid-cols-[96px_minmax(0,1fr)] border-b border-border/40 px-3 py-2 text-[12px] last:border-b-0">
                                <span className="text-muted-foreground">{label}</span>
                                <span className="min-w-0 break-all font-mono text-[11px] text-foreground/85">{detailText(value)}</span>
                              </div>
                            ))}
                          </div>
                        </section>
                        <section className="grid grid-cols-1 gap-4 lg:grid-cols-2">
                          <div>
                            <h3 className="mb-2 text-[11px] font-semibold text-muted-foreground">
                              申请权限（{appDetail.requestedPermissions.length}）
                            </h3>
                            <div className="max-h-48 overflow-y-auto rounded-lg border border-border/60 px-3 py-2 font-mono text-[11px] text-muted-foreground">
                              {appDetail.requestedPermissions.length > 0
                                ? appDetail.requestedPermissions.map(permission=>(
                                  <div key={permission} className="break-all py-1">{permission}</div>
                                ))
                                : <div className="py-6 text-center">—</div>}
                            </div>
                          </div>
                          <div>
                            <h3 className="mb-2 text-[11px] font-semibold text-muted-foreground">
                              已授权权限（{appDetail.grantedPermissions.length}）
                            </h3>
                            <div className="max-h-48 overflow-y-auto rounded-lg border border-border/60 px-3 py-2 font-mono text-[11px] text-muted-foreground">
                              {appDetail.grantedPermissions.length > 0
                                ? appDetail.grantedPermissions.map(permission=>(
                                  <div key={permission} className="break-all py-1">{permission}</div>
                                ))
                                : <div className="py-6 text-center">—</div>}
                            </div>
                          </div>
                        </section>
                      </div>
                    ) : null}
                  </div>
                </div>
              </div>
            )}
            {showDisconnectDialog && (
              <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/35 backdrop-blur-sm">
                <div className="w-[min(360px,calc(100vw-32px))] rounded-xl border border-border bg-background px-5 py-5 shadow-2xl">
                  <div className="w-12 h-12 rounded-2xl bg-amber-500/10 flex items-center justify-center mx-auto mb-4">
                    <AlertTriangle size={22} className="text-amber-500"/>
                  </div>
                  <h2 className="text-sm font-semibold text-center">设备连接已断开</h2>
                  <p className="mt-2 text-[12px] leading-relaxed text-muted-foreground text-center">
                    当前{TAB_LABEL[tab]}内容已保留，重新连接设备后会自动恢复可操作状态。
                  </p>
                  <div className="mt-4 flex justify-center">
                    <button
                      onClick={()=>selectTab("info")}
                      className="px-3 py-1.5 rounded-lg bg-primary text-white text-[12px] font-medium hover:opacity-90 transition-opacity"
                    >
                      返回设备信息
                    </button>
                  </div>
                </div>
              </div>
            )}
          </>
        )}
      </div>
      <AiAssistantShell
        backendOnline={backendOnline}
        device={aiDeviceContext}
        streaming={streaming}
        getRecentLogs={getRecentAiLogs}
        onStartLogCapture={startLogStream}
      />
    </div>
  );
}
