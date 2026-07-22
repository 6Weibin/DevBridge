/**
 * Electron 长回复渲染验收：挂载生产 MarkdownContent，验证 1M Token 数据完整保留且 Renderer 内存有界。
 *
 * by AI.Coding
 */
import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { build } from "vite";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const workspace = path.resolve(root, "..");
const target = path.join(root, "target", "ai-electron-renderer");
const source = path.join(root, "src", "app", "ai");
const electron = path.join(workspace, "DevBridge-Electron", "node_modules", ".bin", "electron");
fs.rmSync(target, { recursive: true, force: true });
fs.mkdirSync(target, { recursive: true });

const entry = `
import React from "react";
import { createRoot } from "react-dom/client";
import { MarkdownContent } from ${JSON.stringify(path.join(source, "MarkdownContent.tsx"))};
import { appendContentSegment } from ${JSON.stringify(path.join(source, "aiStreamSegments.ts"))};
const targetCharacters = 4_000_000;
const chunk = "## 设备检查\\n\\n- 网络正常\\n- 日志证据完整\\n\\n".repeat(40);
let segments = [];
let characters = 0;
while (characters < targetCharacters) {
  const next = chunk.slice(0, Math.min(chunk.length, targetCharacters - characters));
  segments = appendContentSegment(segments, next);
  characters += next.length;
}
const expectedCharacters = segments.reduce((total, segment) => total + segment.length, 0);
let persistedCharacters = 0;
for (let cycle = 0; cycle < 6; cycle += 1) {
  const payload = JSON.stringify({ messages: [
    { id: 1, role: "assistant", content: "", contentSegments: segments },
    { id: 2, role: "assistant", content: "", contentSegments: segments },
    { id: 3, role: "assistant", content: "", contentSegments: segments },
  ] });
  const restored = JSON.parse(payload);
  persistedCharacters = restored.messages.reduce((total, message) => total
    + message.contentSegments.reduce((sum, segment) => sum + segment.length, 0), 0);
}
createRoot(document.getElementById("root")).render(React.createElement(MarkdownContent, { content: "", segments }));
setTimeout(() => {
  const root = document.getElementById("root");
  const retainedSegments = Number(root.querySelector("[data-markdown-segment-count]")?.dataset.markdownSegmentCount || 0);
  const mountedSegments = root.querySelectorAll("[data-markdown-segment]").length;
  window.__AI_STRESS_RESULT__ = {
    ok: expectedCharacters === targetCharacters
      && persistedCharacters === targetCharacters * 3
      && retainedSegments === segments.length
      && mountedSegments <= 32,
    characters: expectedCharacters,
    persistedCharacters,
    retainedSegments,
    mountedSegments,
    segments: segments.length,
  };
}, 100);
`;
fs.writeFileSync(path.join(target, "entry.tsx"), entry);
fs.writeFileSync(path.join(target, "index.html"), '<div id="root"></div><script type="module" src="/entry.tsx"></script>');
await build({
  root: target,
  base: "./",
  logLevel: "error",
  build: { outDir: "dist", emptyOutDir: true },
});

const main = `
const { app, BrowserWindow } = require("electron");
app.disableHardwareAcceleration();
app.commandLine.appendSwitch("js-flags", "--max-old-space-size=768");
app.whenReady().then(() => {
  const win = new BrowserWindow({ show: false, webPreferences: { contextIsolation: true, sandbox: true } });
  const timeout = setTimeout(() => { console.error("renderer timeout"); app.exit(2); }, 120000);
  win.webContents.on("console-message", (_event, _level, message) => console.error("renderer:", message));
  win.webContents.on("did-fail-load", (_event, code, description) => console.error("load failed:", code, description));
  win.webContents.on("render-process-gone", (_event, details) => console.error("renderer gone:", details.reason));
  win.webContents.on("did-finish-load", () => {
    const poll = setInterval(async () => {
      try {
        const result = await win.webContents.executeJavaScript("window.__AI_STRESS_RESULT__ || null");
        if (!result) return;
        const rendererPid = win.webContents.getOSProcessId();
        const renderer = app.getAppMetrics().find(item => item.pid === rendererPid);
        result.privateMemoryKb = Math.round(
          renderer?.memory?.privateBytes || renderer?.memory?.workingSetSize || 0,
        );
        result.ok = result.ok && result.privateMemoryKb > 0 && result.privateMemoryKb < 786432;
        console.log(JSON.stringify(result));
        clearInterval(poll);
        clearTimeout(timeout);
        app.exit(result.ok ? 0 : 3);
      } catch (error) {
        console.error("poll failed:", error.message);
      }
    }, 250);
  });
  win.loadFile(${JSON.stringify(path.join(target, "dist", "index.html"))});
});
`;
fs.writeFileSync(path.join(target, "main.cjs"), main);

const child = spawn(electron, [path.join(target, "main.cjs")], {
  stdio: ["ignore", "pipe", "pipe"],
  env: { ...process.env, ELECTRON_DISABLE_SECURITY_WARNINGS: "true" },
});
let stdout = "";
let stderr = "";
child.stdout.on("data", value => { stdout += value; });
child.stderr.on("data", value => { stderr += value; });
const code = await new Promise(resolve => child.on("exit", resolve));
if (code !== 0) throw new Error(`Electron Markdown 压力验收失败(code=${code}): ${stderr || stdout}`);
process.stdout.write(stdout.trim() + "\n");
