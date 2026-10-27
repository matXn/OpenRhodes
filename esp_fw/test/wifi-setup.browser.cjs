const {chromium}=require('playwright');
const fs=require('node:fs'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try {
  const context=await browser.newContext({viewport:{width:390,height:844}});
  const requests=[],errors=[];
  let connecting=true, target='Missing-old-router', scanning=false, hold=false, scanFailure=false;
  let networks=[{ssid:'New & <Hotspot>',rssi:-45,secure:true},{ssid:'Old Home',rssi:-60,secure:true}];
  let lateScanResolve;
  await context.route('http://rhodes.test/**',async route=>{
   const request=route.request(),url=new URL(request.url());
   if(!url.pathname.startsWith('/api/'))return route.fulfill({contentType:'text/html',body:fs.readFileSync('test/http_preview/setup.html','utf8')});
   const form=Object.fromEntries(new URLSearchParams(request.postData()||''));
   requests.push([request.method(),url.pathname,form]);
   if(request.method()==='POST')assert.equal(request.headers()['x-rhodes-control'],'1');
   if(url.pathname==='/api/wifi/setup'&&form.action==='hold'){hold=true;connecting=false;}
   if(url.pathname==='/api/wifi/setup'&&form.action==='release')hold=false;
   if(url.pathname==='/api/wifi'){connecting=true;target=form.ssid;scanning=false;}
   if(url.pathname==='/api/scan'){
    if(request.method()==='POST'){
     assert.ok(hold,'Setup must get priority before its initial scan');
     scanning=true;
     return route.fulfill({contentType:'application/json',body:JSON.stringify({scanning:true,networks:[],error:''})});
    }
    if(lateScanResolve){const wait=lateScanResolve;lateScanResolve=null;await wait;}
    scanning=false;
    return route.fulfill({contentType:'application/json',body:JSON.stringify({scanning:false,networks:scanFailure?[]:networks,error:scanFailure?'扫描中断':''})});
   }
   return route.fulfill({contentType:'application/json',body:JSON.stringify({connected:false,connecting,target_ssid:target,ssid:target,wifi_profiles:[{ssid:'Old Home',priority:1}]})});
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  await page.goto('http://rhodes.test/setup');
  await page.waitForFunction(()=>document.querySelectorAll('#nets li').length===2);
  assert.ok(requests.findIndex(r=>r[1]==='/api/wifi/setup')<requests.findIndex(r=>r[1]==='/api/scan'));
  assert.match(await page.locator('#nets').innerText(),/New & <Hotspot>/);
  await page.locator('#nets li').first().click();await page.locator('#pass').fill('newpass88');
  // A missing old router must not disable submission of the selected new one.
  connecting=true;
  await page.waitForTimeout(1600);
  assert.equal(await page.locator('#connect').isEnabled(),true);
  await page.locator('#connect').click();
  await page.waitForFunction(()=>document.querySelector('#toast').textContent.includes('正在连接'));
  assert.equal(target,'New & <Hotspot>');
  // Scan errors retain the last successful list instead of turning it into empty SSIDs.
  scanFailure=true;
  await page.locator('#scan').click();
  await page.waitForFunction(()=>document.querySelector('#toast').textContent.includes('扫描中断'));
  assert.equal(await page.locator('#nets li').count(),2);
  // A scan that finishes after a new connection may not overwrite the picker.
  scanFailure=false;let releaseLate;
  lateScanResolve=new Promise(resolve=>releaseLate=resolve);
  await page.locator('#scan').click();
  await page.waitForTimeout(900);
  await page.locator('#ssid').fill('Override');await page.locator('#pass').fill('override88');
  await page.locator('#connect').click();
  await page.waitForFunction(()=>document.querySelector('#toast').textContent.includes('Override'));
  networks=[];releaseLate();await page.waitForTimeout(300);
  assert.equal(await page.locator('#nets li').count(),2);
  assert.ok(!(await page.locator('#toast').innerText()).includes('没有扫描到'));
  for(const width of [320,390,768]){await page.setViewportSize({width,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);}
  assert.deepEqual(errors,[]);
  console.log('PASS: immediate setup scan, selectable real SSIDs, connecting-router override, cached results on failure, late-scan cancellation, mobile layout');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
