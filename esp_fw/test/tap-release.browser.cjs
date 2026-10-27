const {chromium,webkit}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{for(const engine of [chromium,webkit]){
 const browser=await engine.launch({headless:true,...(engine===chromium?{channel:'msedge'}:{})});
 try{
 const page=await browser.newPage({viewport:{width:768,height:800}}),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 let on=true,brightness=100;
 await page.route('http://tap.test/**',async r=>{
  const req=r.request();
  if(new URL(req.url()).pathname.startsWith('/api/')){
   if(req.method()==='POST') {const args=new URLSearchParams(req.postData());if(args.has('on'))on=args.get('on')==='1';}
   return r.fulfill({contentType:'application/json',body:JSON.stringify({on,brightness,breathing:false})});
  }
  return r.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(__dirname,'http_preview/index.html'))});
 });
 await page.goto('http://tap.test/');await page.waitForFunction(()=>document.querySelector('#link').className==='ok');
 for(const level of [100,1]){
  brightness=level;await page.waitForTimeout(1600);
  for(const [name,x,y] of [['off',.85,.15],['on',.15,.85]]){
   const box=await page.locator('#pad-stage').boundingBox();
   await page.mouse.move(box.x+box.width*x,box.y+box.height*y);await page.mouse.down();await page.waitForTimeout(40);await page.mouse.up();
   await page.waitForFunction(expected=>document.querySelector('#pad').classList.contains('off')===expected,name==='off');
   for(const [frame,delay] of [['start',0],['mid',80],['end',350]]){
    await page.waitForTimeout(delay);
    const style=await page.locator('#pad').evaluate(el=>({filter:getComputedStyle(el).filter,blend:getComputedStyle(el,'::before').mixBlendMode,transition:getComputedStyle(el).transitionProperty,clip:getComputedStyle(el).clipPath,shadow:getComputedStyle(el).boxShadow}));
    assert.equal(style.filter,'none');assert.equal(style.blend,'normal');assert.ok(!style.transition.includes('filter'));assert.notEqual(style.clip,'none');assert.equal(style.shadow,'none');
    await page.screenshot({path:path.join(__dirname,`http_preview/tap-${engine.name()}-${level}-${name}-${frame}.png`)});
   }
  }
 }
 assert.deepEqual(errors,[]);console.log('PASS',engine.name(),'real pointerup on/off at 1% and 100%, release frames without filters or blend surfaces');
 }finally{await browser.close();}
}})().catch(e=>{console.error(e);process.exitCode=1;});
