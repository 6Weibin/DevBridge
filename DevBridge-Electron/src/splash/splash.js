/**
 * DevBridge Electron 启动页脚本。
 *
 * by AI.Coding
 */
const progressBar = document.getElementById('progressBar');
const progressValue = document.getElementById('progressValue');
const stepTitle = document.getElementById('stepTitle');
const stepDetail = document.getElementById('stepDetail');
const brandVersion = document.getElementById('brandVersion');
const stepItems = Array.from(document.querySelectorAll('.steps li'));

/**
 * 渲染构建版本，让启动页和主界面展示同一个发布号。
 */
function renderBuildInfo() {
  const buildInfo = window.DEVBRIDGE_BUILD_INFO;
  if (!buildInfo || !buildInfo.version) {
    return;
  }
  brandVersion.textContent = buildInfo.version;
}

/**
 * 根据主进程推送的进度刷新启动页展示。
 *
 * @param {{percent: number, title: string, detail: string}} payload 启动进度
 */
function renderProgress(payload) {
  const percent = clampPercent(payload.percent);
  progressBar.style.width = `${percent}%`;
  progressValue.textContent = `${percent}%`;
  stepTitle.textContent = payload.title;
  stepDetail.textContent = payload.detail;
  updateStepState(percent);
}

/**
 * 限制进度值范围，避免异常输入破坏 UI。
 *
 * @param {number} value 原始进度
 * @returns {number} 规范化进度
 */
function clampPercent(value) {
  if (!Number.isFinite(value)) {
    return 0;
  }
  return Math.max(0, Math.min(100, Math.round(value)));
}

/**
 * 按进度切换步骤状态，让用户知道当前卡在哪个启动阶段。
 *
 * @param {number} percent 当前进度
 */
function updateStepState(percent) {
  // 主进程在 82% 后执行 ADB 服务检查，这里单独映射步骤，避免用户误以为卡在后端启动。
  const activeIndex = percent < 30 ? 0 : percent < 55 ? 1 : percent < 82 ? 2 : percent < 90 ? 3 : 4;
  stepItems.forEach((item, index) => {
    item.classList.toggle('done', index < activeIndex);
    item.classList.toggle('active', index === activeIndex);
  });
}

/**
 * 绑定主进程进度事件；如果 preload 不可用，页面仍展示默认初始化状态。
 */
function bindProgressEvents() {
  if (!window.devbridgeStartup) {
    return;
  }
  window.devbridgeStartup.onProgress(renderProgress);
}

renderBuildInfo();
bindProgressEvents();
