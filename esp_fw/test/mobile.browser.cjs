// Browser-engine and device-profile tests with a mock API. Not physical phones.
const { chromium, webkit, devices } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const reports = [];

async function check(engine, name, profile, legacy = false) {
  const { defaultBrowserType, ...options } = profile;
  const context = await engine.newContext({ ...options, timezoneId: 'Asia/Singapore' });
  const errors = [], writes = [];
  let state = { on: true, brightness: 50, breathing: true, breath_period: 6000, breath_floor: 0,
    breath_curve: 100, breath_phase: 1, connected: true, connecting: false,
    ssid: 'Mobile test', saved_ssid: 'Mobile test', wifi_phase: 'connected', ip: '192.0.2.10', rssi: -55,
    wifi_profiles: [{ ssid: 'Mobile test', priority: 1 }, { ssid: 'Office', priority: 2 }] };
  let stallLight = false;
  try {
    if (legacy) await context.addInitScript(() => {
      const original = window.matchMedia.bind(window);
      window.matchMedia = q => {
        const media = original(q);
        Object.defineProperty(media, 'addEventListener', { value: undefined });
        return media;
      };
    });
    await context.route('http://mobile.test/**', async route => {
      const request = route.request(), url = new URL(request.url());
      if (!url.pathname.startsWith('/api/')) {
        const file = url.pathname === '/setup' ? 'setup.html' : 'index.html';
        return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(__dirname,'http_preview',file))});
      }
      if (url.pathname === '/api/light' && stallLight) return; // AbortController must release the UI.
      if (request.method() === 'POST') {
        const args = Object.fromEntries(new URLSearchParams(request.postData()));
        writes.push({url:url.pathname,args});
        assert.equal(request.headers()['x-rhodes-control'], '1');
        if (url.pathname === '/api/light') {
          if ('on' in args) state.on = args.on === '1';
          if ('brightness' in args) { state.brightness=Number(args.brightness);state.on=true; }
          state.breathing=false;
        }
        if (url.pathname === '/api/mode') state.breathing=args.mode==='breathing';
        if (url.pathname === '/api/wifi') state.ssid=args.ssid;
      }
      const body = url.pathname === '/api/scan' ? {scanning:false,networks:[{ssid:'Mobile test',rssi:-55,secure:true}],error:''} : state;
      await route.fulfill({contentType:'application/json',body:JSON.stringify(body)});
    });
    const page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    await page.goto('http://mobile.test/');
    await page.waitForFunction(() => document.querySelector('#level').textContent === '50%');
    const styles = await page.locator('#pad').evaluate(el => ({
      rect: {width:el.clientWidth,height:el.clientHeight},
      glare:getComputedStyle(el,'::before').backgroundImage,
      touch:getComputedStyle(el).touchAction,
      overflow:document.documentElement.scrollWidth>innerWidth,
    }));
    assert.ok(Math.abs(styles.rect.width/styles.rect.height-4/3)<.02, name+' card ratio');
    assert.notEqual(styles.glare,'none', name+' reflection gradient');
    assert.equal(styles.touch,'none');assert.equal(styles.overflow,false);
    await page.locator('#pad').tap();
    await page.waitForFunction(() => document.querySelector('#level').textContent === '关');
    await page.locator('#pad').tap();
    await page.waitForFunction(() => document.querySelector('#level').textContent !== '关');
    const box = await page.locator('#pad-stage').boundingBox();
    const powerBefore = writes.filter(w => 'on' in w.args).length;
    const scrollBefore = await page.evaluate(() => scrollY);
    // Touch movement on Chromium uses the native touch input pipeline; WebKit
    // uses PointerEvents here (native touch taps above are tested on both engines).
    if (engine.browserType().name() === 'chromium') {
      const cdp = await context.newCDPSession(page);
      const x=box.x+box.width*.5,y=box.y+box.height*.5;
      await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});
      for(let i=1;i<=8;i++)await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:x+i*3,y:y-i*3}]});
      await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
      await cdp.detach();
    } else {
      await page.locator('#pad').dispatchEvent('pointerdown',{pointerId:41,pointerType:'touch',clientX:box.x+box.width*.5,clientY:box.y+box.height*.5});
      await page.locator('#pad').dispatchEvent('pointermove',{pointerId:41,pointerType:'touch',clientX:box.x+box.width*.7,clientY:box.y+box.height*.3});
      await page.locator('#pad').dispatchEvent('pointerup',{pointerId:41,pointerType:'touch'});
    }
    await page.waitForTimeout(200);
    assert.ok(writes.some(w => 'brightness' in w.args && Number(w.args.brightness)>50),name+' upward swipe');
    assert.equal(writes.filter(w=>'on' in w.args).length,powerBefore,name+' swipe must not toggle');
    assert.equal(await page.evaluate(()=>scrollY),scrollBefore,name+' pad swipe must not scroll');
    const protectedTouch = await page.locator('#pad').evaluate(el => {
      const event = new Event('touchmove',{bubbles:true,cancelable:true});el.dispatchEvent(event);return event.defaultPrevented;
    });
    assert.equal(protectedTouch,true);
    await page.locator('#pad').dispatchEvent('pointerdown',{pointerId:42,pointerType:'touch',clientX:box.x+15,clientY:box.y+box.height-15});
    await page.setViewportSize({width:profile.viewport.height,height:profile.viewport.width});
    await page.waitForFunction(()=>!document.querySelector('#pad').classList.contains('dragging'));
    await page.emulateMedia({reducedMotion:'reduce'});
    await page.waitForTimeout(100);
    assert.equal(await page.locator('#pad').evaluate(el=>getComputedStyle(el).transform),'none');
    assert.equal(await page.locator('#pad').evaluate(el=>getComputedStyle(el,'::after').display),'none');
    await page.setViewportSize(profile.viewport);
    await page.goto('http://mobile.test/setup');
    await page.waitForFunction(()=>document.querySelectorAll('#savedNets li').length===2);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,name+' setup overflow');
    await page.getByRole('button',{name:'更换网络',exact:true}).tap();
    await page.locator('#nets li').filter({hasText:'Mobile test'}).tap();
    assert.equal(await page.locator('#ssid').inputValue(), 'Mobile test');
    await page.locator('#pass').fill('example-pass');
    assert.ok(await page.locator('#pass').evaluate(el=>parseFloat(getComputedStyle(el).fontSize)>=16),name+' input zoom prevention');
    await page.locator('#connect').tap();
    await page.waitForTimeout(200);
    assert.ok(writes.some(w=>w.url==='/api/wifi'&&w.args.ssid==='Mobile test'),name+' setup submission');
    await page.screenshot({path:path.join(__dirname,'http_preview',name+'.png'),fullPage:true});
    if (name === 'iphone-portrait' || name === 'android-portrait') {
      await page.goto('http://mobile.test/');
      await page.waitForFunction(()=>document.querySelector('#level').textContent!=='--');
      stallLight=true;
      await page.locator('#pad').press('ArrowUp');
      await page.waitForFunction(()=>document.querySelector('#toast').textContent.includes('连接超时'),{},{timeout:10000});
      stallLight=false;
      const recoveryCount=writes.length;
      await page.locator('#pad').press('ArrowUp');
      for(let i=0;i<20&&writes.length===recoveryCount;i++)await page.waitForTimeout(50);
      assert.ok(writes.length>recoveryCount,name+' brightness queue must recover after timeout');
    }
    assert.deepEqual(errors,[],name+' script errors');
    reports.push({name,engine:engine.browserType().name(),viewport:profile.viewport,status:'PASS',legacyMedia:legacy});
    console.log('PASS',name,engine.browserType().name());
  } finally { await context.close(); }
}

(async()=>{
  const chrome=await chromium.launch({headless:true,channel:process.env.PLAYWRIGHT_CHANNEL||'msedge'});
  const safari=await webkit.launch({headless:true});
  try {
    const runs = await Promise.allSettled([
      (async()=>{for(const [name,device,legacy] of [['iphone-portrait','iPhone 13',false],['iphone-landscape','iPhone 13 landscape',false],['ipad','iPad Mini',false],['iphone-legacy-media','iPhone 13 Mini',true]])await check(safari,name,devices[device],legacy);})(),
      (async()=>{for(const [name,device] of [['android-portrait','Pixel 7'],['android-landscape','Pixel 7 landscape']])await check(chrome,name,devices[device]);await check(chrome,'android-small',{...devices['Pixel 7'],viewport:{width:320,height:640}});})()
    ]);
    fs.writeFileSync(path.join(__dirname,'http_preview','mobile-results.json'),JSON.stringify(reports,null,2));
    for(const r of runs)if(r.status==='rejected')console.error(r.reason);
    if(runs.some(r=>r.status==='rejected'))throw new Error('Mobile profile tests failed; see errors above');
    console.log('PASS: 7 device profiles across WebKit and Chromium; physical phones/captive portals not tested.');
  } finally {await Promise.all([chrome.close(),safari.close()]);}
})().catch(e=>{console.error(e);process.exitCode=1;});
