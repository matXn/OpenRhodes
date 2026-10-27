(() => {
'use strict';

const $ = id => document.getElementById(id);
const pad = $('pad'), levelEl = $('level'), toastEl = $('toast');
const modeBtns = [...document.querySelectorAll('#mode button')];
const breathEl = $('breath'), periodIn = $('period'), floorIn = $('floor'), shapeIn = $('shape');

// 本地视图状态；以设备 /api/state 为准，本地操作后短时间内不被轮询覆盖。
let st = { on: false, breathing: false };
let level = 50;                                   // 灯面亮度（1–100），关灯时保留上次亮度
let breath = { period: 6800, floor: 13, curve: 60 };

let lastLocalMs = 0, lastBreathEditMs = 0;
const LOCAL_HOLD_MS = 1200;
let failCount = 0;
let phaseAnchor = { phase: Math.PI, at: performance.now() };
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
function devicePhase(now) {
  return phaseAnchor.phase + 2 * Math.PI * (now - phaseAnchor.at) / breath.period;
}

// ---------- API ----------
// 固件要求写入接口带 X-Rhodes-Control: 1，参数为表单编码。
async function api(path, params) {
  const opt = { cache: 'no-store' };
  if (params) {
    opt.method = 'POST';
    opt.headers = { 'X-Rhodes-Control': '1', 'Content-Type': 'application/x-www-form-urlencoded' };
    opt.body = new URLSearchParams(params);
  }
  const sentAt = performance.now();
  const abort = new AbortController();
  opt.signal = abort.signal;
  const timeout = setTimeout(() => abort.abort(), 7000);
  try {
    const res = await fetch(path, opt);
    let data;
    try { data = await res.json(); }
    catch (error) {
      if (error.name === 'AbortError') throw error;
      throw new Error('连接响应异常，请确认已连到灯具网络');
    }
    if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
    data.phaseSampleAt = (sentAt + performance.now()) / 2;
    return data;
  } catch (error) {
    if (error.name === 'AbortError') throw new Error('连接超时，请确认仍连接着灯具网络后重试');
    throw error;
  } finally { clearTimeout(timeout); }
}

let toastTimer = null;
function toast(msg, bad = false) {
  toastEl.textContent = msg;
  toastEl.className = bad ? 'bad' : '';
  clearTimeout(toastTimer);
  if (msg) toastTimer = setTimeout(() => { toastEl.textContent = ''; }, 3500);
}

// ---------- 灯面渲染 ----------
function renderArtwork() {
  // Compress the displayed waveform, rather than multiplying an already tiny
  // brightness. Even a 1% setting must have a visible but gentle breath.
  const active = st.on && st.breathing;
  const wave = active ? breathLevel(devicePhase(performance.now())) : 1;
  const base = Math.pow(level / 100, 1.25);
  const litG = active ? .12 + .76 * base + (reducedMotion.matches ? 0 : .12 * (wave - .5)) : base;
  // Draw the off surface directly. Filtering the entire 3D card while its
  // blended reflection returns can leave stale compositor tiles on tap release.
  const g = st.on ? litG : base * .28;
  const hue = 28 + 17 * g, sat = st.on ? 88 + 10 * g : 12, lig = 8 + 67 * Math.pow(g, 1.05);
  pad.style.setProperty('--surface-reflection-scale', st.on ? '1' : '.35');
  const core = `hsl(${hue.toFixed(1)},${sat.toFixed(1)}%,${Math.min(88, lig + 8 * g).toFixed(1)}%)`;
  const body = `hsl(${hue.toFixed(1)},${sat.toFixed(1)}%,${lig.toFixed(1)}%)`;
  // Adapt the gradient ink itself; keep the lamp surface free of text backdrops.
  let inkMix = st.on ? Math.max(0, Math.min(1, (g - .25) / .5)) : 0;
  inkMix = inkMix * inkMix * (3 - 2 * inkMix);
  const mixInk = (a,b) => a.map((v,i) => Math.round(v+(b[i]-v)*inkMix)).join(',');
  pad.style.setProperty('--level-ink-top', `rgb(${mixInk([255,250,237],[63,35,10])})`);
  pad.style.setProperty('--level-ink-bottom', `rgb(${mixInk([232,210,176],[25,13,4])})`);
  const bA = Math.max(.15, Math.min(.95, .15 + .8 * g));
  const glowA = (.05 + .65 * g) * (st.on ? 1 : .2);
  pad.style.background = `radial-gradient(ellipse at 50% 50%, ${core} 0%, ${body} 100%)`;
  pad.style.borderColor = `hsla(${(30 + 15 * g).toFixed(1)},95%,${Math.min(85, lig + 15)}%,${bA.toFixed(2)})`;
  // The cast shadow belongs to the stationary stage, not the turning surface.
  pad.parentElement.style.boxShadow = `0 12px 36px rgba(0,0,0,.6), 0 0 ${Math.round(15 + 85 * g)}px hsla(${hue.toFixed(1)},95%,55%,${glowA.toFixed(2)}), 0 0 ${Math.round(30 + 120 * g)}px hsla(${hue.toFixed(1)},95%,45%,${(glowA / 2).toFixed(2)})`;

}
function renderPad() {
  renderArtwork();
  pad.classList.toggle('off', !st.on);
  pad.classList.toggle('lit', st.on && (8 + 67 * Math.pow(Math.pow(level / 100, 1.25), 1.05)) > 42);   // 灯面够亮时文字改深色
  pad.setAttribute('aria-valuenow', st.on ? Math.round(level) : 0);
  levelEl.innerHTML = st.on ? `${Math.round(level)}<small>%</small>` : '关';
  modeBtns.forEach(b => b.classList.toggle('on', (b.dataset.mode === 'breathing') === !!st.breathing));
  breathEl.classList.toggle('show', !!st.breathing);
}

// ---------- 亮度发送：始终只发最新值，同时最多一个请求在途 ----------
let sending = false, pendingLevel = null;
function sendLevel(v) {
  pendingLevel = Math.round(v);
  if (!sending) flushLevel();
}
async function flushLevel() {
  if (pendingLevel === null) return;
  const v = pendingLevel; pendingLevel = null; sending = true;
  try { applyState(await api('/api/light', { brightness: v }), true); }
  catch (e) { toast(e.message, true); }
  sending = false;
  if (pendingLevel !== null) flushLevel();
}

async function setPower(on) {
  lastLocalMs = Date.now();
  st.on = on; renderPad();
  try {
    // 开灯时带上当前亮度，避免设备端亮度为 0 时开出一个看不见的灯。
    applyState(await api('/api/light', on ? { on: 1, brightness: Math.max(1, Math.round(level)) } : { on: 0 }), true);
  } catch (e) { toast(e.message, true); refresh(); }
}

async function setMode(mode) {
  lastLocalMs = Date.now();
  try { applyState(await api('/api/mode', { mode }), true); }
  catch (e) { toast(e.message, true); }
}

// ---------- 触控板：拖动调光，轻点开关 ----------
let drag = null;
const padStage = $('pad-stage');
const surfaceAngles = { x: 0, y: 0 };
let environmentDirection = { x: 0, y: -.18 };
// Reflect a world-space light about the card normal. Pointer coordinates do not
// position the light; they only determine the card's existing tilt angles.
function renderViewReflection() {
  const rx = surfaceAngles.x * Math.PI / 180, ry = surfaceAngles.y * Math.PI / 180;
  const normal = { x: Math.sin(ry)*Math.cos(rx), y: -Math.sin(rx), z: Math.cos(ry)*Math.cos(rx) };
  const length = Math.hypot(environmentDirection.x, environmentDirection.y, 1);
  const light = { x: environmentDirection.x/length, y: environmentDirection.y/length, z: 1/length };
  const dot = normal.x*light.x + normal.y*light.y + normal.z*light.z;
  const reflected = { x: 2*dot*normal.x-light.x, y: 2*dot*normal.y-light.y, z: 2*dot*normal.z-light.z };
  const specular = (.2 + Math.pow(Math.max(0, reflected.z),18)) / (.2 + Math.pow(light.z,18));
  pad.style.setProperty('--view-shift-x', (-(reflected.x+light.x)*110).toFixed(3)+'%');
  pad.style.setProperty('--view-shift-y', (-(reflected.y+light.y)*80).toFixed(3)+'%');
  pad.style.setProperty('--view-rotate', (normal.x*32-normal.y*18).toFixed(3)+'deg');
  pad.style.setProperty('--view-specular', Math.max(.2,Math.min(1.9,1+(specular-1)*1.6)).toFixed(3));
  pad.style.setProperty('--view-glow-x', (-normal.x*90).toFixed(3)+'%');
  pad.style.setProperty('--view-glow-y', (-normal.y*90).toFixed(3)+'%');
  pad.style.setProperty('--view-rim-x', (-normal.x*11).toFixed(3)+'px');
  pad.style.setProperty('--view-rim-y', (-normal.y*11).toFixed(3)+'px');
}

function resetTilt() {
  surfaceAngles.x = 0; surfaceAngles.y = 0;
  renderViewReflection();
  pad.style.setProperty('--tilt-x', '0deg');
  pad.style.setProperty('--tilt-y', '0deg');
  pad.style.setProperty('--shift-x', '0px');
  pad.style.setProperty('--shift-y', '0px');
}
// Browser wall-clock time, shared across reloads; independent of pointer position.
// This is a simulated environment, not a measurement of room lighting.
function renderClockLight(timestamp = Date.now()) {
  if (reducedMotion.matches) return;
  const date = new Date(timestamp);
  const hour = date.getHours() + date.getMinutes()/60 + date.getSeconds()/3600;
  const daylight = .5 + .5 * Math.cos((hour - 14) / 24 * Math.PI * 2);
  const seconds = timestamp / 1000;
  // One continuous orbit: independent slow sine waves could cancel and look
  // motionless for many seconds. The paired axes never stop together.
  const orbit = seconds * Math.PI * 2 / 48;
  const x = .5 * Math.sin(orbit);
  const y = -.18 + .36 * Math.cos(orbit);
  environmentDirection = { x, y };
  pad.style.setProperty('--reflection-shift-x', (x * 22).toFixed(3) + '%');
  pad.style.setProperty('--reflection-shift-y', (y * 15).toFixed(3) + '%');
  pad.style.setProperty('--reflection-rotate', (x * 8 - y * 5).toFixed(3) + 'deg');
  const edge = Math.min(1, Math.hypot(x,y)/Math.SQRT2);
  const alignment = Math.max(0,1-Math.hypot(x+.25,y+.35)/2.2);
  pad.style.setProperty('--shine-x', (48-x*42).toFixed(2)+'%');
  pad.style.setProperty('--shine-y', (28-y*38).toFixed(2)+'%');
  pad.style.setProperty('--shine-band', (50-x*22+y*16).toFixed(2)+'%');
  pad.style.setProperty('--shine-angle', (120-x*10+y*6).toFixed(1)+'deg');
  pad.style.setProperty('--specular', ((.25+.35*daylight)*(.7+.3*Math.pow(alignment,4))).toFixed(3));
  pad.style.setProperty('--coat', (.35+.23*daylight+.1*edge).toFixed(3));
  pad.style.setProperty('--reflection-tint', `255,${Math.round(226+22*daylight)},${Math.round(190+54*daylight)}`);
  pad.style.setProperty('--rim-x', (-x*1.2).toFixed(2)+'px');
  pad.style.setProperty('--rim-y', (1-y*1.2).toFixed(2)+'px');
  renderViewReflection();
  pad.classList.add('sheen-active');
}
function tiltAt(e) {
  if (reducedMotion.matches) return;
  // Use the stationary wrapper; a transformed bounding box causes feedback/jitter.
  const r = drag.rect;
  const x = Math.max(-1, Math.min(1, (e.clientX - r.left) / r.width * 2 - 1));
  const y = Math.max(-1, Math.min(1, (e.clientY - r.top) / r.height * 2 - 1));
  surfaceAngles.x = -y*6; surfaceAngles.y = x*6;
  renderViewReflection();
  pad.style.setProperty('--tilt-x', (-y * 6).toFixed(2) + 'deg');
  pad.style.setProperty('--tilt-y', (x * 6).toFixed(2) + 'deg');
  pad.style.setProperty('--shift-x', (x * 4).toFixed(2) + 'px');
  pad.style.setProperty('--shift-y', (y * 4).toFixed(2) + 'px');
}
pad.addEventListener('pointerdown', e => {
  if (drag || (e.pointerType === 'mouse' && e.button !== 0)) return;
  drag = { id: e.pointerId, x: e.clientX, y: e.clientY, sx: e.clientX, sy: e.clientY, t: Date.now(), moved: false, rect: padStage.getBoundingClientRect() };
  pad.classList.add('dragging');
  tiltAt(e);
  try { pad.setPointerCapture(e.pointerId); } catch {}
});
pad.addEventListener('pointermove', e => {
  if (!drag || e.pointerId !== drag.id) return;
  tiltAt(e);
  const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
  drag.x = e.clientX; drag.y = e.clientY;
  if (!drag.moved && Math.hypot(e.clientX - drag.sx, e.clientY - drag.sy) < 6) return;
  drag.moved = true;
  // 右/上变亮，左/下变暗；划过灯面宽度约覆盖全程。
  const delta = (dx - dy) * (100 / pad.clientWidth) * 1.1;
  if (!delta) return;
  lastLocalMs = Date.now();
  level = Math.max(1, Math.min(100, level + delta));
  st.on = true;
  renderPad();
  sendLevel(level);
});
function endDrag(e) {
  if (!drag || e.pointerId !== drag.id) return;
  const tap = !drag.moved && Date.now() - drag.t < 350;
  drag = null;
  pad.classList.remove('dragging');
  resetTilt();
  try { pad.releasePointerCapture(e.pointerId); } catch {}
  if (tap && e.type === 'pointerup') setPower(!st.on);
}
pad.addEventListener('pointerup', endDrag);
pad.addEventListener('pointercancel', endDrag);
pad.addEventListener('lostpointercapture', endDrag);
function cancelInteraction() {
  if (drag) endDrag({ pointerId: drag.id, type: 'pointercancel' });
  else resetTilt();
}
window.addEventListener('blur', cancelInteraction);
window.addEventListener('resize', cancelInteraction);
// Keep native page scrolling outside the pad; guard touch movement on the pad
// for embedded/older WebKit views where CSS touch-action alone is insufficient.
pad.addEventListener('touchmove', e => { if (e.cancelable) e.preventDefault(); }, { passive: false });
pad.addEventListener('keydown', e => {
  const step = { ArrowRight: 2, ArrowUp: 2, ArrowLeft: -2, ArrowDown: -2 }[e.key];
  if (step) {
    e.preventDefault(); lastLocalMs = Date.now();
    level = Math.max(1, Math.min(100, level + step)); st.on = true; renderPad(); sendLevel(level);
  } else if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); setPower(!st.on); }
});
modeBtns.forEach(b => b.addEventListener('click', () => setMode(b.dataset.mode)));

// ---------- 呼吸函数：与固件 breathLevel() 同一公式 ----------
// w = (0.5 + 0.5·cos φ)^(curve/100)，输出 = floor + (1 - floor)·w
const CURVE_WINDOW_MS = 20000;
// Whole cycles keep the graph stationary, including when the cursor wraps.
function curveWindowMs() { return Math.ceil(CURVE_WINDOW_MS / breath.period) * breath.period; }
function breathLevel(phase) {
  const w = Math.pow(0.5 + 0.5 * Math.cos(phase), breath.curve / 100);
  const f = breath.floor / 100;
  return f + (1 - f) * w;
}
function renderBreath() {
  periodIn.value = breath.period / 1000;
  floorIn.value = breath.floor;
  shapeIn.value = breath.curve;
  $('periodOut').textContent = (breath.period / 1000).toFixed(1) + ' s';
  $('floorOut').textContent = breath.floor + ' %';
  $('shapeOut').textContent = (breath.curve / 100).toFixed(2);
  $('formula').textContent = `${(breath.period / 1000).toFixed(1)} s / 次`;
  renderCurve();
}
function renderCurve() {
  // 横轴固定为 CURVE_WINDOW_MS 的时间窗：周期越长 ω 越小，图里的波越宽、个数越少。
  let d = '';
  for (let i = 0; i <= 300; i++) {
    const t = i / 300 * curveWindowMs();
    d += (i ? 'L' : 'M') + i + ' ' + (88 - breathLevel(2 * Math.PI * t / breath.period) * 80).toFixed(1);
  }
  $('curvePath').setAttribute('d', d);
}
// The device supplies phase; interpolation continues locally between polls.
const onMotionChange = () => { if (reducedMotion.matches) resetTilt(); renderArtwork(); };
if (reducedMotion.addEventListener) reducedMotion.addEventListener('change', onMotionChange);
else if (reducedMotion.addListener) reducedMotion.addListener(onMotionChange);
renderClockLight();
let lastMotionFrame = 0;
(function animate(now) {
  if (!document.hidden && now - lastMotionFrame >= 33) {
    lastMotionFrame = now;
    renderClockLight();
    if (st.breathing && st.on && !reducedMotion.matches) {
      const phase = devicePhase(now);
      const windowMs = curveWindowMs();
      const plotTime = ((phase * breath.period / (2*Math.PI)) % windowMs + windowMs) % windowMs;
      const dot = $('curveDot');
      dot.style.left = (plotTime / windowMs * 100).toFixed(3) + '%';
      dot.style.top = ((88 - breathLevel(phase) * 80) / 96 * 100).toFixed(3) + '%';
    }
    if (st.breathing && st.on && !reducedMotion.matches) renderArtwork();
  }
  requestAnimationFrame(animate);
})(performance.now());

let breathTimer = null;
function onBreathInput() {
  lastBreathEditMs = Date.now();
  breath = { period: Math.round(+periodIn.value * 1000), floor: +floorIn.value, curve: +shapeIn.value };
  renderBreath();
  clearTimeout(breathTimer);
  breathTimer = setTimeout(async () => {
    try { applyState(await api('/api/breath', breath), true); }
    catch (e) { toast(e.message, true); }
  }, 150);
}
[periodIn, floorIn, shapeIn].forEach(el => el.addEventListener('input', onBreathInput));

// ---------- 状态同步 ----------
function applyState(s, fromWrite = false) {
  const busy = drag || sending || (!fromWrite && Date.now() - lastLocalMs < LOCAL_HOLD_MS);
  if (!busy) {
    st.on = !!s.on;
    if (s.brightness > 0) level = s.brightness;
  }
  st.breathing = !!s.breathing;
  if (Number.isFinite(s.breath_phase)) {
    const at = s.phaseSampleAt ?? performance.now(), tau = 2*Math.PI;
    const predicted = devicePhase(at);
    // Unwrap the device's 0..2π sample so polling cannot reset the cycle count.
    const phase = s.breath_phase + Math.round((predicted-s.breath_phase)/tau)*tau;
    phaseAnchor = { phase, at };
  }
  if (s.breath_period && Date.now() - lastBreathEditMs > LOCAL_HOLD_MS) {
    breath = { period: s.breath_period, floor: s.breath_floor, curve: s.breath_curve };
    renderBreath();
  }
  renderPad();
}

async function refresh() {
  try {
    applyState(await api('/api/state'));
    failCount = 0;
    $('link').className = 'ok';
  } catch {
    if (++failCount >= 2) $('link').className = 'bad';
  }
}

renderBreath();
renderPad();
refresh();
setInterval(() => { if (!document.hidden) refresh(); }, 1500);
document.addEventListener('visibilitychange', () => { if (document.hidden) cancelInteraction(); else refresh(); });
})();
