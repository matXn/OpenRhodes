(() => {
'use strict';

const pad = document.getElementById('pad');

let currentLevel = 65;
let isDragging = false;
let lastX = 0, lastY = 0;
let activePointerId = null;
let apiTimer = null;

// 根据亮度百分比计算温润灯光的颜色、边框与光晕 (1% ~ 100%)
function renderLuminance(val) {
  const t = val / 100.0;
  const gamma = Math.pow(t, 1.25);

  // 色相：从暗夜微光红琥珀 (28度) 到 明亮暖金阳光 (45度)
  const hue = 28 + 17 * gamma;
  const sat = 88 + 10 * gamma;
  // 亮度：从暗部 8% 到 极亮 75%
  const lig = 8 + 67 * Math.pow(gamma, 1.05);

  // 核心发光点（中心稍亮）与边缘底色
  const coreLig = Math.min(88, lig + 8 * gamma);
  const coreColor = `hsl(${hue.toFixed(1)}, ${sat.toFixed(1)}%, ${coreLig.toFixed(1)}%)`;
  const bodyColor = `hsl(${hue.toFixed(1)}, ${sat.toFixed(1)}%, ${lig.toFixed(1)}%)`;

  // 边框发光
  const borderAlpha = Math.max(0.15, Math.min(0.95, 0.15 + 0.8 * gamma));
  const borderHue = 30 + 15 * gamma;
  const borderColor = `hsla(${borderHue.toFixed(1)}, 95%, ${Math.min(85, lig + 15)}%, ${borderAlpha.toFixed(2)})`;

  // 环境漫反射光晕 (Box Shadow)
  const glowAlpha = (0.05 + 0.65 * gamma).toFixed(2);
  const glowRadius = Math.round(15 + 85 * gamma);
  const haloRadius = Math.round(30 + 120 * gamma);
  const shadow = `0 12px 36px rgba(0, 0, 0, 0.6), 0 0 ${glowRadius}px hsla(${hue.toFixed(1)}, 95%, 55%, ${glowAlpha}), 0 0 ${haloRadius}px hsla(${hue.toFixed(1)}, 95%, 45%, ${(glowAlpha * 0.5).toFixed(2)})`;

  // 极速无延迟直写样式
  pad.style.background = `radial-gradient(ellipse at 50% 50%, ${coreColor} 0%, ${bodyColor} 100%)`;
  pad.style.border = `1px solid ${borderColor}`;
  pad.style.boxShadow = shadow;
  pad.setAttribute('aria-valuenow', Math.round(val));
}

function updateLevel(newVal, notify = true) {
  currentLevel = Math.max(1, Math.min(100, newVal));
  renderLuminance(currentLevel);

  if (notify) {
    clearTimeout(apiTimer);
    apiTimer = setTimeout(async () => {
      try {
        await fetch('/api/light', {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({ brightness: Math.round(currentLevel), on: 1 })
        });
      } catch {}
    }, 60);
  }
}

/* ================= 极度跟手的 1:1 双向触控 =================
   向右 (dx > 0) -> 变亮
   向上 (dy < 0) -> 变亮
   向左 (dx < 0) -> 变暗
   向下 (dy > 0) -> 变暗
   采用增量步进 + 零延迟直出，手指滑到哪，光就亮到哪。
   =========================================================== */

function onPointerDown(e) {
  if (e.button !== 0 && e.pointerType === 'mouse') return;
  isDragging = true;
  activePointerId = e.pointerId;
  lastX = e.clientX;
  lastY = e.clientY;

  pad.classList.add('dragging');
  try {
    pad.setPointerCapture(e.pointerId);
  } catch {}
}

function onPointerMove(e) {
  if (!isDragging || e.pointerId !== activePointerId) return;

  const dx = e.clientX - lastX;
  const dy = e.clientY - lastY;
  lastX = e.clientX;
  lastY = e.clientY;

  // 灵敏度：1px 约变化 0.48%，约划过大半个触控板 (210px) 即可覆盖 1% 到 100%
  // 向右: dx > 0
  // 向上: -dy > 0
  const delta = (dx - dy) * 0.48;
  if (delta !== 0) {
    updateLevel(currentLevel + delta);
  }
}

function onPointerUp(e) {
  if (!isDragging || e.pointerId !== activePointerId) return;
  isDragging = false;
  activePointerId = null;
  pad.classList.remove('dragging');

  try {
    pad.releasePointerCapture(e.pointerId);
  } catch {}
}

pad.addEventListener('pointerdown', onPointerDown);
pad.addEventListener('pointermove', onPointerMove);
pad.addEventListener('pointerup', onPointerUp);
pad.addEventListener('pointercancel', onPointerUp);
pad.addEventListener('lostpointercapture', onPointerUp);

// 键盘无障碍方向键（右/上亮，左/下暗）
window.addEventListener('keydown', e => {
  if (e.key === 'ArrowRight' || e.key === 'ArrowUp') {
    e.preventDefault();
    updateLevel(currentLevel + 2);
  } else if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') {
    e.preventDefault();
    updateLevel(currentLevel - 2);
  }
});

// 初始化读取状态
async function init() {
  try {
    const res = await fetch('/api/state', { cache: 'no-store' });
    const data = await res.json();
    if (data && data.brightness !== undefined) {
      updateLevel(data.brightness, false);
      return;
    }
  } catch {}
  updateLevel(65, false);
}

init();

})();
