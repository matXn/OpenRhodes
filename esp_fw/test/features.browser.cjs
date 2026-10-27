// Run tools/build_web.py first. Requires Playwright and a Chromium installation.
// These are UI integration tests with a mock API, not device/NVS tests.
const { chromium, webkit } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
  const engine = process.env.BROWSER_ENGINE === 'webkit' ? webkit : chromium;
  const browser = await engine.launch({ headless: true, ...(engine === chromium && process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}) });
  try {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, timezoneId: 'Asia/Singapore' });
    let profiles = [{ ssid: 'Home & <lamp>', priority: 1 }, { ssid: 'Studio', priority: 2 }];
    let on = true, breathing = true, brightness = 50;
    const epoch = Date.now(), requests = [], errors = [];
    const state = () => ({ on, brightness, breathing,
      breath_period: 2000, breath_floor: 0, breath_curve: 100,
      breath_phase: ((Date.now() - epoch) % 2000) / 2000 * Math.PI * 2,
      wifi_profiles: profiles, connected: true, connecting: false,
      ssid: 'Studio', saved_ssid: profiles[0]?.ssid || '', ip: '192.0.2.10', rssi: -50 });
    await context.route('http://rhodes.test/**', async route => {
      const req = route.request(), url = new URL(req.url());
      if (!url.pathname.startsWith('/api/')) {
        const file = url.pathname === '/setup' ? 'setup.html' : 'index.html';
        return route.fulfill({ contentType: 'text/html', body: fs.readFileSync(path.join(__dirname, 'http_preview', file)) });
      }
      let result = state();
      if (req.method() === 'POST') {
        assert.equal(req.headers()['x-rhodes-control'], '1');
        const params = Object.fromEntries(new URLSearchParams(req.postData()));
        requests.push({ path: url.pathname, params });
        if (url.pathname === '/api/light') {
          if ('on' in params) on = params.on === '1';
          if ('brightness' in params) brightness = Number(params.brightness);
        }
        if (url.pathname === '/api/mode') breathing = params.mode === 'breathing';
        if (url.pathname === '/api/wifi/profiles') {
          const i = profiles.findIndex(p => p.ssid === params.ssid);
          assert.ok(i >= 0);
          const [item] = profiles.splice(i, 1);
          if (params.action === 'move') profiles.splice(Number(params.priority) - 1, 0, item);
          profiles.forEach((p, n) => p.priority = n + 1);
        }
        result = state();
      }
      if (url.pathname === '/api/scan') result = { scanning: false, networks: [], error: '' };
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify(result) });
    });
    const page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    await page.goto('http://rhodes.test/');
    await page.waitForFunction(() => document.querySelector('#level').textContent === '50%');
    const backgrounds = new Set(), labels = new Set();
    for (let i = 0; i < 24; i++) {
      const sample = await page.evaluate(() => ({ bg: document.querySelector('#pad').style.background,
        label: document.querySelector('#level').textContent, ink: getComputedStyle(document.querySelector('#level')).color }));
      backgrounds.add(sample.bg); labels.add(sample.label + sample.ink);
      await page.waitForTimeout(100);
    }
    assert.ok(backgrounds.size > 8, 'Artwork should breathe');
    assert.equal(labels.size, 1, 'Brightness label/ink must remain stable');
    // Low brightness used to erase the animation almost completely.
    for (const target of [1, 100]) {
      brightness = target;
      await page.waitForFunction(v => document.querySelector('#level').textContent === v + '%', target);
      const lights = [];
      for (let i = 0; i < 22; i++) {
        const sample = await page.evaluate(() => ({
          background: document.querySelector('#pad').style.background,
          label: document.querySelector('#level').textContent,
        }));
        const matches = [...sample.background.matchAll(/rgb\((\d+),\s*(\d+),\s*(\d+)\)/g)];
        assert.ok(matches.length >= 2);
        const rgb = matches[1].slice(1).map(Number);
        lights.push((Math.max(...rgb) + Math.min(...rgb)) / 510 * 100);
        assert.equal(sample.label, target + '%');
        await page.waitForTimeout(100);
      }
      const swing = Math.max(...lights) - Math.min(...lights);
      assert.ok(swing > 5 && swing < 12, `Gentle visible lightness swing at ${target}%: ${swing}`);
    }
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const still = await page.locator('#pad').evaluate(el => el.style.background);
    await page.waitForTimeout(500);
    assert.equal(await page.locator('#pad').evaluate(el => el.style.background), still);
    assert.equal(await page.locator('#curveDot').isVisible(), false);
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await page.getByRole('button', { name: '常亮', exact: true }).click();
    await page.waitForTimeout(200);
    const steady = await page.locator('#pad').evaluate(el => el.style.background);
    await page.waitForTimeout(350);
    assert.equal(await page.locator('#pad').evaluate(el => el.style.background), steady);

    assert.equal(await page.locator('#power, #liveOutput').count(), 0);
    brightness = 30;
    await page.waitForFunction(() => document.querySelector('#level').textContent === '30%');
    const box = await page.locator('#pad-stage').boundingBox();
    const hoverRequestCount = requests.length;
    const readSurface = () => page.locator('#pad').evaluate(el => ({
      x: el.style.getPropertyValue('--shine-x'),
      angle: el.style.getPropertyValue('--shine-angle'),
      strength: el.style.getPropertyValue('--specular'),
      background: getComputedStyle(el, '::after').backgroundImage,
      active: el.classList.contains('sheen-active'),
    }));
    // Dispatch opposite pointer positions in the same task (no animation tick):
    // Neither hovering nor pressing moves the light; card tilt changes its reflection.
    const pointerIndependent = await page.locator('#pad').evaluate(el => {
      const r = el.getBoundingClientRect();
      const sample = () => ['--shine-x','--shine-y','--shine-band','--shine-angle','--specular','--reflection-shift-x','--reflection-shift-y'].map(k => el.style.getPropertyValue(k));
      const before = sample();
      const viewBefore = ['--view-shift-x','--view-shift-y','--view-rotate','--view-specular'].map(k=>el.style.getPropertyValue(k));
      el.dispatchEvent(new PointerEvent('pointermove', {pointerId: 99, pointerType:'mouse', clientX:r.left+10, clientY:r.bottom-10}));
      el.dispatchEvent(new PointerEvent('pointerdown', {pointerId: 99, pointerType:'touch', clientX:r.right-10, clientY:r.top+10}));
      const after = sample();
      const viewAfter = ['--view-shift-x','--view-shift-y','--view-rotate','--view-specular'].map(k=>el.style.getPropertyValue(k));
      el.dispatchEvent(new PointerEvent('pointercancel', {pointerId: 99, pointerType:'touch'}));
      return {before,after,viewBefore,viewAfter};
    });
    assert.deepEqual(pointerIndependent.before, pointerIndependent.after, 'Touch must not position the environment light');
    assert.notDeepEqual(pointerIndependent.viewBefore, pointerIndependent.viewAfter, 'Turning the card must change its reflection');
    const reflection1 = await readSurface();
    assert.equal(reflection1.active, true);
    assert.equal(await page.locator('#pad').evaluate(el => getComputedStyle(el, '::after').pointerEvents), 'none');
    await page.mouse.move(0, 0);
    await page.waitForTimeout(1000);
    const reflection2 = await readSurface();
    assert.notDeepEqual(reflection1, reflection2, 'Environment light must drift without interaction');
    assert.equal(reflection2.active, true, 'Light persists after the pointer leaves');
    assert.equal(requests.length, hoverRequestCount, 'Ambient effects must not control the physical light');
    const corners = [[.15,.85,-1,1], [.85,.15,1,-1], [.15,.15,-1,-1], [.85,.85,1,1]];
    for (const [x,y,sx,sy] of corners) {
      await page.mouse.move(box.x + box.width*x, box.y + box.height*y);
      await page.mouse.down();
      const tilt = await page.locator('#pad').evaluate(el => ({
        x: parseFloat(el.style.getPropertyValue('--shift-x')),
        y: parseFloat(el.style.getPropertyValue('--shift-y')),
        rx: parseFloat(el.style.getPropertyValue('--tilt-x')),
        ry: parseFloat(el.style.getPropertyValue('--tilt-y')),
      }));
      const reflection = await page.locator('#pad').evaluate(el => ({ x:parseFloat(el.style.getPropertyValue('--view-shift-x')), y:parseFloat(el.style.getPropertyValue('--view-shift-y')) }));
      assert.ok(reflection.x*sx < 0 && reflection.y*sy < 0, 'Reflection must travel against the turned surface');
      assert.ok(tilt.x*sx > 0 && tilt.y*sy > 0 && tilt.rx*sy < 0 && tilt.ry*sx > 0, 'Tilt follows the touched corner');
      const layers = await page.locator('#pad').evaluate(el => ({
        shadow:getComputedStyle(el).boxShadow, clip:getComputedStyle(el).clipPath,
        promotion:getComputedStyle(el).willChange,
        stageShadow:getComputedStyle(el.parentElement).boxShadow,
        stageTransform:getComputedStyle(el.parentElement).transform,
      }));
      assert.equal(layers.shadow,'none','Cast shadow must not be attached to the moving card');
      assert.notEqual(layers.stageShadow,'none');
      assert.equal(layers.stageTransform,'none','Shadow stage must stay stationary');
      assert.notEqual(layers.clip,'none','Reflections must be explicitly clipped to one rounded surface');
      assert.equal(layers.promotion,'transform','Compositor layer must persist through release');
      if (sx === -1 && sy === 1) {
        await page.waitForTimeout(100);
        await page.screenshot({ path: path.join(__dirname, 'http_preview', 'card-tilt.png') });
      }
      await page.waitForTimeout(360); // Hold so this is not a tap.
      await page.mouse.up();
      await page.waitForTimeout(400);
      assert.equal(await page.locator('#pad').evaluate(el => parseFloat(el.style.getPropertyValue('--tilt-x'))), 0);
      assert.equal(await page.locator('#pad').evaluate(el => parseFloat(el.style.getPropertyValue('--view-shift-x'))), 0);
      assert.equal(await page.locator('#pad').evaluate(el => el.classList.contains('sheen-active')), true);
    }
    const beforeTap = on;
    await page.locator('#pad').click();
    await page.waitForTimeout(100);
    assert.equal(on, !beforeTap, 'Card tap still toggles the light');
    // Drag changes brightness, keeps the page still, and never toggles power.
    const powerCalls = requests.filter(r => r.path === '/api/light' && 'on' in r.params).length;
    const scrollBefore = await page.evaluate(() => scrollY);
    await page.mouse.move(box.x+box.width*.5, box.y+box.height*.5);
    await page.mouse.down();
    await page.mouse.move(box.x+box.width*.7, box.y+box.height*.3, {steps: 8});
    await page.mouse.up();
    await page.waitForTimeout(150);
    assert.equal(requests.filter(r => r.path === '/api/light' && 'on' in r.params).length, powerCalls);
    assert.equal(await page.evaluate(() => scrollY), scrollBefore);
    await page.locator('#pad').dispatchEvent('pointerdown', { pointerId: 42, pointerType: 'touch', clientX: box.x+box.width*.15, clientY: box.y+box.height*.85 });
    assert.ok(await page.locator('#pad').evaluate(el => parseFloat(el.style.getPropertyValue('--shift-x')) < 0));
    await page.locator('#pad').dispatchEvent('pointercancel', { pointerId: 42, pointerType: 'touch' });
    assert.equal(await page.locator('#pad').evaluate(el => parseFloat(el.style.getPropertyValue('--tilt-x'))), 0);
    assert.equal(requests.filter(r => r.path === '/api/light' && 'on' in r.params).length, powerCalls);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.mouse.move(box.x+box.width*.2, box.y+box.height*.8);
    await page.mouse.down();
    assert.equal(await page.locator('#pad').evaluate(el => getComputedStyle(el).transform), 'none');
    assert.equal(await page.locator('#pad').evaluate(el => getComputedStyle(el, '::after').display), 'none');
    await page.waitForTimeout(360);
    await page.mouse.up();
    await page.emulateMedia({ reducedMotion: 'no-preference' });

    await page.goto('http://rhodes.test/setup');
    await page.waitForFunction(() => document.querySelectorAll('#savedNets li').length === 2);
    assert.match(await page.locator('#savedNets').innerText(), /Home & <lamp>/);
    await page.getByRole('button', { name: '提高 Studio 的优先级' }).click();
    await page.waitForFunction(() => document.querySelector('#savedNets li .name').textContent.startsWith('1. Studio'));
    await page.locator('#savedNets li').first().getByRole('button', { name: '连接', exact: true }).click();
    await page.waitForTimeout(100);
    assert.ok(requests.some(r => r.path === '/api/wifi' && r.params.ssid === 'Studio' && r.params.use_saved === '1' && !r.params.password));
    page.on('dialog', d => d.accept());
    await page.locator('#savedNets li').last().getByRole('button', { name: '忘记' }).click();
    await page.waitForFunction(() => document.querySelectorAll('#savedNets li').length === 1);
    for (const width of [320, 390, 768]) {
      await page.setViewportSize({ width, height: 844 });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, 'No horizontal overflow');
    }
    await page.screenshot({ path: path.join(__dirname, 'http_preview', 'wifi-priority.png'), fullPage: true });
    const timePage = await context.newPage();
    timePage.on('pageerror', e => errors.push(e.message));
    await timePage.clock.setFixedTime(new Date('2026-10-16T14:00:00+08:00'));
    await timePage.goto('http://rhodes.test/');
    await timePage.waitForFunction(() => document.querySelector('#link').className === 'ok');
    const sampleLight = () => timePage.locator('#pad').evaluate(el =>
      ['--shine-x','--shine-y','--shine-angle','--specular','--reflection-tint'].map(k => el.style.getPropertyValue(k)));
    const afternoon = await sampleLight();
    await timePage.reload();
    await timePage.waitForFunction(() => document.querySelector('#link').className === 'ok');
    assert.deepEqual(await sampleLight(), afternoon, 'Same wall-clock time must retain its light phase across reloads');
    await timePage.clock.setFixedTime(new Date('2026-10-16T02:00:00+08:00'));
    await timePage.waitForTimeout(100);
    const night = await sampleLight();
    assert.notEqual(night[4], afternoon[4], 'Local time changes the color temperature');
    assert.ok(parseFloat(night[3]) < parseFloat(afternoon[3]), 'Night reflection should be softer');
    await timePage.clock.setFixedTime(new Date('2026-10-16T14:00:01+08:00'));
    await timePage.waitForTimeout(100);
    const later = await sampleLight();
    assert.notEqual(later[0], afternoon[0], 'Wall-clock seconds move the reflection');
    assert.ok(Math.abs(parseFloat(later[0])-parseFloat(afternoon[0])) < 3, 'Motion should remain slow');
    // At every point in the cycle, a 3-second interval must move the actual
    // reflection layer, not merely update gradient parameters offscreen.
    for (let second = 0; second < 48; second += 4) {
      await timePage.clock.setFixedTime(new Date(1792130400000 + second*1000));
      await timePage.waitForTimeout(50);
      const before = await timePage.locator('#pad').evaluate(el => getComputedStyle(el, '::before').transform);
      await timePage.clock.setFixedTime(new Date(1792130400000 + (second+3)*1000));
      await timePage.waitForTimeout(50);
      const after = await timePage.locator('#pad').evaluate(el => getComputedStyle(el, '::before').transform);
      assert.notEqual(before, 'none');
      assert.notEqual(before, after, 'Reflection transform must move throughout the cycle');
    }
    await timePage.waitForLoadState('networkidle');
    await timePage.close();
    assert.deepEqual(errors, []);
    console.log('PASS: local-time light/day-night/reload continuity, breathing, four-corner tilt/return, card tap/drag, touch cancellation, reduced motion, Wi-Fi profiles, mobile layout');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
