const {chromium,webkit}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{for(const engine of [chromium,webkit]){
 const browser=await engine.launch({headless:true,...(engine===chromium?{channel:'msedge'}:{})});try{
 const page=await browser.newPage({viewport:{width:390,height:600}});let brightness=1,on=true;
 await page.route('http://ink.test/**',r=>r.fulfill(new URL(r.request().url()).pathname.startsWith('/api/')?{contentType:'application/json',body:JSON.stringify({on,brightness,breathing:false})}:{contentType:'text/html',body:fs.readFileSync(path.join(__dirname,'http_preview/index.html'))}));
 const colors=[];
 for(const value of [1,55,100,0]){
  brightness=value||100;on=value>0;await page.goto('http://ink.test/');await page.waitForFunction(()=>document.querySelector('#link').className==='ok');
  const styles=await page.evaluate(()=>['#pad h1','#level','#hint'].map(selector=>{const s=getComputedStyle(document.querySelector(selector));return {gradient:s.backgroundImage,top:s.getPropertyValue('--level-ink-top'),bottom:s.getPropertyValue('--level-ink-bottom'),stroke:s.webkitTextStrokeWidth,shadow:s.textShadow};}));
  assert.deepEqual(styles[0],styles[1]);assert.deepEqual(styles[1],styles[2]);assert.notEqual(styles[0].gradient,'none');assert.equal(parseFloat(styles[0].stroke),0);assert.equal(styles[0].shadow,'none');colors.push(styles[0].top);
  if(value===55)await page.screenshot({path:path.join(__dirname,'http_preview/text-gradients-'+engine.name()+'.png')});
 }
 assert.equal(new Set(colors.slice(0,3)).size,3);console.log('PASS',engine.name(),'shared title/percentage/hint gradient at 1%,55%,100%,off; no stroke or shadow');
 }finally{await browser.close();}
}})().catch(e=>{console.error(e);process.exitCode=1;});
