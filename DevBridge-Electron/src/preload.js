/**
 * DevBridge Electron preload。
 *
 * by AI.Coding
 */
const { contextBridge, ipcRenderer } = require('electron');

/**
 * 向启动页暴露只读进度订阅能力；不开放任意 IPC，避免业务页面获得 Node 权限。
 */
contextBridge.exposeInMainWorld('devbridgeStartup', {
  /**
   * 订阅主进程启动进度。
   *
   * @param {(payload: {percent: number, title: string, detail: string}) => void} callback 回调函数
   */
  onProgress(callback) {
    ipcRenderer.on('startup-progress', (_event, payload) => callback(payload));
  },
});
