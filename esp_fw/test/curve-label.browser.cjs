const {chromium,webkit}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
 const engine=process.env.BROWSER_ENGINE==='webkit'?webkit:chromium;
 const browser=await engine.launch({headless:true,...(engine===chromium?{channel:'msedge'}:{})});
 try {
  const context=await browser.newContext({viewport:{width:390,height:844}});
  let brightness=30,on=true,first=true,poll=0,startedAt;
  await context.route('http://curve.test/**',async route=>{
   if(new URL(route.request().url()).pathname==='/api/state') {
    const body={on,brightness,breathing:true,breath_period:6000,breath_floor:0,breath_curve:100};
    const now=await page.evaluate(()=>performance.now());
    if(first){startedAt=now;first=false;}
    body.breath_phase=((now-startedAt)*2*Math.PI/6000+(++poll%2?.03:-.03))%(2*Math.PI);
    return route.fulfill({contentType:'application/json',body:JSON.stringify(body)});
   }
   return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(__dirname,'http_preview/index.html'))});
  });
  const page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.clock.install();
  await page.goto('http://curve.test/');
  await page.waitForFunction(()=>document.querySelector('#link').className==='ok');
  const positions=[];
  const fixedPath=await page.locator('#curvePath').getAttribute('d');
  for(let i=0;i<6;i++) {
   await page.clock.runFor(i?3500:1000);
   positions.push(await page.locator('#curveDot').evaluate(el=>parseFloat(el.style.left)));
   const pathError=await page.evaluate(()=>{
    const dot=document.querySelector('#curveDot'),curve=document.querySelector('#curvePath');
    const x=parseFloat(dot.style.left)*3,y=parseFloat(dot.style.top)*.96;
    let lo=0,hi=curve.getTotalLength();
    for(let j=0;j<20;j++){const mid=(lo+hi)/2;if(curve.getPointAtLength(mid).x<x)lo=mid;else hi=mid;}
    return Math.abs(curve.getPointAtLength((lo+hi)/2).y-y);
   });
   assert.ok(pathError<1,'Marker must stay on the curve across all cycles');
   assert.equal(await page.locator('#curvePath').getAttribute('d'),fixedPath,'Polling phase jitter must never shift the graph');
  }
  assert.ok(positions.at(-1)>70,'Marker must reach the last cycles');
  assert.ok(positions.every((v,i)=>!i||v>positions[i-1]),'Marker must not reset on each 6-second breath');
  await page.clock.runFor(6500);
  assert.ok(await page.locator('#curveDot').evaluate(el=>parseFloat(el.style.left))<15,'Marker wraps only after the full four-cycle window');
  for(const width of [320,390,768]) {
   await page.setViewportSize({width,height:844});
   const rect=await page.locator('#curveDot').boundingBox();
   assert.ok(Math.abs(rect.width-rect.height)<.1,'Marker must remain round');
   assert.equal(Math.round(rect.width),8);
  }
  assert.ok(!(await page.locator('#pad').evaluate(el=>el.style.background)).includes('15, 9, 4'),'Lamp surface must not contain a dark text backdrop');
  const inks=[];
  for(const value of [1,25,40,50,60,75,100]) {
   brightness=value;
   await page.clock.fastForward(1800);
   await page.waitForFunction(v=>document.querySelector('#level').textContent===v+'%',value);
   const textStyles=await page.locator('#pad').evaluate(el=>[el.querySelector('h1'),el.querySelector('#level'),el.querySelector('#hint')].map(node=>({
    gradient:getComputedStyle(node).backgroundImage,stroke:getComputedStyle(node).webkitTextStrokeWidth,shadow:getComputedStyle(node).textShadow,
   })));
   assert.ok(textStyles.every(t=>t.gradient===textStyles[1].gradient&&t.gradient!=='none'&&parseFloat(t.stroke)===0&&t.shadow==='none'),'Title, percentage and hint must share the same gradient without stroke or shadow');
   inks.push(await page.locator('#level').evaluate(el=>{
    return {top:getComputedStyle(el).getPropertyValue('--level-ink-top'),bottom:getComputedStyle(el).getPropertyValue('--level-ink-bottom'),
      stroke:getComputedStyle(el).webkitTextStrokeWidth,shadow:getComputedStyle(el).textShadow,background:getComputedStyle(el).backgroundImage};
   }));
  }
  assert.ok(new Set(inks.map(i=>i.bottom)).size>=5,'Ink must blend through intermediate colors');
  assert.ok(inks.every(i=>i.top!==i.bottom&&parseFloat(i.stroke)===0&&i.shadow==='none'&&i.background!=='none'),'Gradient must remain available without any text stroke or shadow');
  on=false;
  await page.clock.fastForward(1800);
  await page.waitForFunction(()=>document.querySelector('#level').textContent==='关');
  assert.equal(await page.locator('#level').evaluate(el=>getComputedStyle(el).getPropertyValue('--level-ink-top')), 'rgb(255,250,237)', 'Off label must retain light ink even at a high saved brightness');
  on=true;
  await page.clock.fastForward(1800);
  await page.waitForFunction(()=>document.querySelector('#level').textContent==='100%');
  await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:path.join(__dirname,'http_preview/curve-label-'+engine.name()+'.png')});
  assert.deepEqual(errors,[]);
  console.log('PASS',engine.name(),'full-window marker travel, round marker, stationary graph under phase jitter, gradient ink without stroke or shadow');
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
