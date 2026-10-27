(() => {
'use strict';

const $ = id => document.getElementById(id);
const scanBtn = $('scan'), netsEl = $('nets'), joinEl = $('join'), pickerEl = $('picker');
const ssidIn = $('ssid'), passIn = $('pass'), connectBtn = $('connect'), toastEl = $('toast');
const LOCK = '<svg class="lock" viewBox="0 0 12 12" aria-label="加密"><rect x="2" y="5.5" width="8" height="5.5" rx="1"/><path d="M4 5.5V4a2 2 0 0 1 4 0v1.5"/></svg>';
const ICON_OK = '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="8" cy="8" r="7"/><path d="M4.8 8.2l2.2 2.2 4.2-4.6"/></svg>';
const ICON_BAD = '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="8" cy="8" r="7"/><path d="M8 4.5v4.2M8 11.2v.3"/></svg>';
const ICON_COPY = '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="5.5" y="5.5" width="8" height="8" rx="1.5"/><path d="M10.5 5.5V3.5a1 1 0 0 0-1-1h-6a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2"/></svg>';
const ICON_DONE = '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M3.5 8.5l3 3 6-6.5"/></svg>';

let lastState = {};
let profileSignature = "", profileBusy = false;
let pickerOpen = false;   // 已连接时网络列表默认收起，点「更换网络」再展开
let scanEpoch = 0, joinRequestPending = false;

function invalidateScan() {
  ++scanEpoch;
  scanBtn.disabled = false; scanBtn.textContent = '重新扫描';
}
async function holdSetup() {
  try { await api('/api/wifi/setup', { action: 'hold' }); } catch {}
}
function releaseSetup() {
  fetch('/api/wifi/setup', { method: 'POST', keepalive: true,
    headers: { 'X-Rhodes-Control': '1', 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'action=release' }).catch(() => {});
}

// 写入接口要求 X-Rhodes-Control: 1，参数为表单编码。
async function api(path, params) {
  const opt = { cache: 'no-store' };
  if (params) {
    opt.method = 'POST';
    opt.headers = { 'X-Rhodes-Control': '1', 'Content-Type': 'application/x-www-form-urlencoded' };
    opt.body = new URLSearchParams(params);
  }
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
  if (msg) toastTimer = setTimeout(() => { toastEl.textContent = ''; }, 4000);
}
function esc(s) { return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }

// ---------- 信号格：强绿、中黄、弱橙 ----------
function signalLevel(rssi) { return rssi >= -60 ? 'good' : rssi >= -72 ? 'mid' : 'weak'; }
function signalText(rssi) { return { good: '信号强', mid: '信号一般', weak: '信号弱' }[signalLevel(rssi)]; }
function bars(rssi) {
  const n = rssi >= -55 ? 4 : rssi >= -65 ? 3 : rssi >= -75 ? 2 : 1;
  return `<span class="bars ${signalLevel(rssi)}">` +
    [4, 7, 10, 12].map((h, i) => `<span class="${i < n ? 'f' : ''}" style="height:${h}px"></span>`).join('') + '</span>';
}

// ---------- 复制 ----------
let copiedText = '', copiedUntil = 0;
function copyBtn(text) {
  const done = text === copiedText && Date.now() < copiedUntil;
  return `<button class="copy${done ? ' done' : ''}" type="button" data-copy="${esc(text)}" aria-label="复制">${done ? ICON_DONE : ICON_COPY}</button>`;
}
// 灯的页面是 http，浏览器不开放 navigator.clipboard；退回到 execCommand('copy')。
async function copyText(text) {
  if (navigator.clipboard && window.isSecureContext) {
    try { await navigator.clipboard.writeText(text); return true; } catch {}
  }
  const ta = document.createElement('textarea');
  ta.value = text; ta.setAttribute('readonly', '');
  ta.style.cssText = 'position:fixed;top:0;left:0;opacity:0';
  document.body.appendChild(ta);
  ta.select(); ta.setSelectionRange(0, text.length);
  let ok = false;
  try { ok = document.execCommand('copy'); } catch {}
  ta.remove();
  return ok;
}

// ---------- 状态区 ----------
function renderState(s) {
  lastState = s;
  renderProfiles(s);
  const box = $('status');
  const collapsed = s.connected && !pickerOpen;
  const change = collapsed ? '<button type="button" id="change">更换网络</button>' : '';
  if (s.connected) {
    box.className = 'ok';
    box.innerHTML = `<div class="top"><span class="tag">${ICON_OK}已接入家庭网络</span>${change}</div>
      <div class="big">${esc(s.ssid)}</div>
      <div class="sub">${bars(s.rssi)}${signalText(s.rssi)} · ${s.rssi} dBm</div>
      ${s.ip ? `<div class="addr"><small>在家里的网络中用浏览器打开</small>
        <div class="line"><a href="http://rhodeslight.local/">rhodeslight.local</a>${copyBtn('http://rhodeslight.local/')}</div>
        <div class="line"><a class="ip" href="http://${esc(s.ip)}/">${esc(s.ip)}</a>${copyBtn('http://' + s.ip + '/')}</div>
        <span class="note">部分安卓手机打不开 rhodeslight.local，请用下面的 IP</span></div>` : ''}`;
  } else if (s.connecting) {
    box.className = 'busy';
    box.innerHTML = `<span class="tag"><i class="spin"></i>正在连接</span><div class="big">${esc(s.target_ssid || s.ssid || '')}</div>
      <div class="sub">通常需要 5–15 秒，热点不会断开</div>`;
  } else if (s.wifi_error) {
    box.className = 'bad';
    box.innerHTML = `<span class="tag">${ICON_BAD}连接失败</span><div class="big">${esc(s.wifi_error)}</div>
      ${s.target_ssid ? `<div class="sub">${esc(s.target_ssid)}</div>` : ''}`;
  } else {
    box.className = '';
    box.innerHTML = `<span class="tag">尚未接入</span><div class="big">选择家里的 Wi-Fi</div>
      ${s.saved_ssid ? `<div class="sub">已保存 ${esc(s.saved_ssid)}</div>` : ''}`;
  }
  pickerEl.classList.toggle('hide', collapsed);
  connectBtn.disabled = joinRequestPending;
}

document.addEventListener('click', async e => {
  if (e.target.closest('#change')) {
    pickerOpen = true; pickerEl.classList.remove('hide'); renderState(lastState); scan();
    return;
  }
  const b = e.target.closest('.copy');
  if (!b) return;
  const text = b.dataset.copy;
  if (await copyText(text)) {
    copiedText = text; copiedUntil = Date.now() + 2000;
    renderState(lastState);
    setTimeout(() => renderState(lastState), 2050);
  } else {
    toast('当前浏览器不允许复制，请长按地址手动复制', true);
  }
});

// ---------- 已保存网络 / 优先级 ----------
function isSaved(ssid) {
  return (lastState.wifi_profiles || []).some(n => n.ssid === ssid);
}
function renderProfiles(s) {
  const list = s.wifi_profiles || [];
  const signature = JSON.stringify([list, s.connecting, s.connected ? s.ssid : '']);
  if (signature === profileSignature) return;
  profileSignature = signature;
  $('savedNets').innerHTML = list.length ? list.map((n, i) => `<li>
    <div class="savedRow"><span class="name">${i + 1}. ${esc(n.ssid)}${s.connected && s.ssid === n.ssid ? ' · 已连接' : ''}</span></div>
    <div class="actions">
      <button type="button" data-profile="connect" data-ssid="${esc(n.ssid)}">连接</button>
      <button type="button" data-profile="move" data-priority="${i}" data-ssid="${esc(n.ssid)}" aria-label="提高 ${esc(n.ssid)} 的优先级" ${i === 0 || s.connecting ? 'disabled' : ''}>上移</button>
      <button type="button" data-profile="move" data-priority="${i + 2}" data-ssid="${esc(n.ssid)}" aria-label="降低 ${esc(n.ssid)} 的优先级" ${i === list.length - 1 || s.connecting ? 'disabled' : ''}>下移</button>
      <button type="button" class="forget" data-profile="forget" data-ssid="${esc(n.ssid)}" ${s.connecting ? 'disabled' : ''}>忘记</button>
    </div></li>`).join('') : '<li>尚未保存网络</li>';
}
$('savedNets').addEventListener('click', async e => {
  const b = e.target.closest('button[data-profile]');
  if (!b || b.disabled || profileBusy) return;
  const ssid = b.dataset.ssid, action = b.dataset.profile;
  if (action === 'forget' && !window.confirm(`忘记 ${ssid}？下次连接需重新输入密码。`)) return;
  profileBusy = true;
  try {
    if (action === 'connect') {
      invalidateScan();
      await api('/api/wifi', { ssid, use_saved: 1 });
      toast(`正在连接 ${ssid}`);
      await refresh();
    } else {
      renderState(await api('/api/wifi/profiles', { ssid, action, priority: b.dataset.priority || '' }));
      toast(action === 'forget' ? '已忘记网络；当前连接可继续使用' : '连接顺序已保存，下次自动连接时生效');
    }
  } catch (err) { toast(err.message, true); }
  finally { profileBusy = false; }
});

// ---------- 网络列表 ----------
function renderNets(list) {
  netsEl.innerHTML = '';
  list.forEach(n => {
    const li = document.createElement('li');
    li.innerHTML = `<span class="name">${esc(n.ssid)}</span>${n.secure ? LOCK : ''}${bars(n.rssi)}`;
    li.addEventListener('click', () => pickNet(n, li));
    netsEl.appendChild(li);
  });
}

function pickNet(n, li) {
  [...netsEl.children].forEach(x => x.classList.toggle('sel', x === li));
  ssidIn.value = n.ssid;
  passIn.value = '';
  passIn.style.display = n.secure ? '' : 'none';
  passIn.placeholder = isSaved(n.ssid) ? '留空使用已保存的密码' : '密码';
  joinEl.classList.add('show');
  if (n.secure) passIn.focus();
}

async function scan() {
  const epoch = ++scanEpoch;
  scanBtn.disabled = true; scanBtn.textContent = '扫描中…';
  try {
    let r = await api('/api/scan', { start: 1 });
    for (let i = 0; r.scanning && i < 20; i++) {
      await new Promise(ok => setTimeout(ok, 800));
      if (epoch !== scanEpoch) return;
      r = await api('/api/scan');
    }
    if (epoch !== scanEpoch) return;
    if (r.scanning) toast('扫描仍在进行，请稍后重新扫描', true);
    else if (r.error) toast(r.error, true);
    else {
      renderNets(r.networks || []);
      if (!(r.networks || []).length) toast('没有扫描到可见的 2.4 GHz 网络');
    }
    joinEl.classList.add('show');
    passIn.style.display = '';
  } catch (e) { if (epoch === scanEpoch) toast(e.message, true); }
  finally { if (epoch === scanEpoch) { scanBtn.disabled = false; scanBtn.textContent = '重新扫描'; } }
}

joinEl.addEventListener('submit', async e => {
  e.preventDefault();
  const ssid = ssidIn.value, password = passIn.value;
  if (!ssid) return toast('请输入 Wi-Fi 名称', true);
  if (password && password.length < 8) return toast('密码至少 8 位', true);
  const params = { ssid, password };
  if (!password && isSaved(ssid)) params.use_saved = 1;
  if (joinRequestPending) return;
  invalidateScan();joinRequestPending = true;connectBtn.disabled = true;
  try {
    await api('/api/wifi', params);
    pickerOpen = false;           // 连上后自动收起列表
    toast(`正在连接 ${ssid}`);
    setTimeout(refresh, 1000);
  } catch (err) { toast(err.message, true); }
  finally { joinRequestPending = false; connectBtn.disabled = false; }
});
scanBtn.addEventListener('click', scan);

async function refresh() {
  try { renderState(await api('/api/state')); } catch {}
}
(async () => {
  await holdSetup();
  await refresh();
  if (!lastState.connected) { pickerOpen = true; await scan(); }
})();
setInterval(() => { if (!document.hidden) refresh(); }, 1500);
setInterval(() => { if (!document.hidden) holdSetup(); }, 10000);
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) { holdSetup(); refresh(); } else releaseSetup();
});
window.addEventListener('pagehide', releaseSetup);
})();
