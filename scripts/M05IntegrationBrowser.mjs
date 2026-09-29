import { chromium } from 'playwright';
import { readFile, mkdir } from 'node:fs/promises';
import { createServer } from 'node:http';
import { resolve, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
const scriptDir=dirname(fileURLToPath(import.meta.url));
const app=process.env.M05_INTEGRATION_APP_ROOT || resolve(scriptDir,'..');
const moduleFile=process.env.M05_UI_MODULE || resolve(app,'web/modules/delivery-settlement/index.js');
const output=process.env.M05_UI_SCREENSHOTS;
const host=await readFile(resolve(app,'web/app.js'),'utf8');
const modal=host.slice(host.indexOf('  function openModal('),host.indexOf('  function confirmBox('));
const form=host.slice(host.indexOf('  function renderForm('),host.indexOf('  function collectForm('));
const icons=host.match(/  const icon =[^\n]+/)[0]+'\n'+host.slice(host.indexOf('  function refreshIcons('),host.indexOf('  const debounce ='));
assert.ok(modal.includes('function closeModal(')&&form.includes('form-grid'),'actual host modal/form functions located');
const html=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
${['style','studio','ledger','v10','v13'].map(x=>`<link rel="stylesheet" href="/${x}.css">`).join('')}
<body><main id="mount"></main><script src="/lucide.min.js"></script><script type="module">
import {mount} from '/modules/delivery-settlement/index.js';
const $=(s,el)=>(el||document).querySelector(s), $$=(s,el)=>Array.from((el||document).querySelectorAll(s));
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
${icons}
let modalPreviousFocus=null,chartInstances=[],formSeq=0;
${modal}
${form}
const clone=value=>JSON.parse(JSON.stringify(value));
const fixture={mode:'draft',post:[],gets:0,callbacks:0,nextError:0,late:null,defer:false};
let stored={dispatch_id:101,project_id:10,teacher_id:20,organization_code:'001',version:1,
 fact:{hours:{estimated:'2.00',planned:'2.00',actual:'1.33',payable:null},verification:null},
 conversion:{minutes:'60',class_hours:'1.33'}};
const requests=new Map();
function caps(detail=stored){const v=fixture.mode==='reviewer',w=fixture.mode!=='readonly';return {current_server:true,fact_version:stored.version,
 permissions:{save:w,verify:v,complete:v},can_save:w,can_verify:v&&stored.conversion?.minutes!=null,can_complete:v&&!!stored.fact.verification,
 reasons:{save:w?[]:[{code:'MISSING_PERMISSION',message:'没有授课记录保存权限'}],verify:v?[]:[{code:'MISSING_PERMISSION',message:'没有授课记录核对权限'}],complete:v&&stored.fact.verification?[]:[{code:'TEACHING_NOT_VERIFIED',message:'请先核对当前授课记录'}]}};}
function response(detail=stored){return {...clone(detail),capabilities:caps()};}
function failure(code){return Object.assign(new Error('合成测试错误 '+code),{code,status:code});}
async function api(path,opts={}){
 if(opts.signal?.aborted)throw new DOMException('aborted','AbortError');
 if(!opts.body){fixture.gets++;if(fixture.defer){fixture.defer=false;return new Promise((res,rej)=>{fixture.late=()=>res(response());opts.signal.addEventListener('abort',()=>{fixture.aborted=true;rej(new DOMException('aborted','AbortError'));},{once:true});});}return response();}
 const body=clone(opts.body);fixture.post.push({path,body});let err=fixture.nextError;fixture.nextError=0;
 if(err&&err!==599){if(err===409){stored.version++;stored.fact.hours.planned='4.00';}if(err===403)fixture.mode='draft';throw failure(err);}
 if(requests.has(body.request_id))return response(requests.get(body.request_id));
 if(body.expected_version!==stored.version)throw failure(409);
 stored.version++;
 if(path.endsWith('/save')){stored.fact.hours={estimated:body.estimated_hours,planned:body.planned_hours,actual:body.actual_minutes===null?null:document.querySelector('[data-k="actual_hours"]').value,payable:body.payable_hours};stored.fact.verification=null;stored.conversion=body.actual_minutes===null?null:{minutes:body.actual_minutes,class_hours:stored.fact.hours.actual};}
 else if(path.endsWith('/verify'))stored.fact.verification={actor_code:'0002',checked_at:'2026-09-22T01:00:00Z',evidence_code:body.evidence_code};
 else throw new Error('Unexpected write '+path);
 requests.set(body.request_id,clone(stored));
 if(err===599)throw new Error('响应中断，提交结果未知');
 return response();
}
let route=new AbortController();
function make(){route=new AbortController();return mount($('#mount'),{api,renderForm,openModal,closeModal,signal:route.signal,onSaved:()=>{fixture.callbacks++;},onVerified:()=>{fixture.callbacks++;}});}
let component=make();
Object.assign(fixture,{open:()=>component.open(101),abort:()=>route.abort(),remount:()=>{component.cleanup();component=make();},bump:()=>{stored.version++;stored.fact.hours.estimated='8.00';},close:()=>component.cleanup(),snapshot:()=>response()});
window.fixture=fixture;window.ready=true;
</script></body></html>`;
const server=createServer(async(req,res)=>{try{
 const path=new URL(req.url,'http://127.0.0.1').pathname;
 if(path==='/fixture'){res.setHeader('Content-Type','text/html;charset=utf-8');res.end(html);return;}
 if(path==='/favicon.ico'){res.writeHead(204);res.end();return;}
 const file=path==='/modules/delivery-settlement/index.js'?moduleFile:resolve(app,'web','.'+path);
 if(file!==moduleFile&&!file.startsWith(resolve(app,'web')+'/')){res.writeHead(403);res.end();return;}
 res.setHeader('Content-Type',extname(file)==='.js'?'text/javascript;charset=utf-8':extname(file)==='.css'?'text/css;charset=utf-8':'application/octet-stream');res.end(await readFile(file));
 }catch(e){res.writeHead(404);res.end();}});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
let browser,checks=0;const check=(condition,msg)=>{assert.ok(condition,msg);checks++;};
try{
 browser=await chromium.launch({executablePath:process.env.M05_CHROME||undefined,headless:true});
 const page=await browser.newPage({viewport:{width:1280,height:1000}});const errors=[];page.on('pageerror',e=>errors.push(String(e)));
 await page.goto('http://127.0.0.1:'+server.address().port+'/fixture');await page.waitForFunction(()=>window.ready);await page.evaluate(()=>fixture.open());
 const field=k=>page.locator('[data-k="'+k+'"]'),button=k=>page.locator('[data-m05-action="'+k+'"]');
 const idle=()=>page.waitForFunction(()=>!document.querySelector('[data-m05-status]').textContent.includes('正在处理'));
 check(await field('actual_hours').inputValue()==='1.33','60 minutes actual preview exact');
 check(await field('payable_hours').inputValue()==='','unknown payable remains blank');
 check(!await button('verify').isVisible(),'draft-only user sees no verification button');
 check(!await button('complete').isVisible(),'completion is not enabled without host callback');
 check(!await button('retry').isVisible(),'retry hidden without ambiguous request');
 await field('planned_hours').fill('3.14');await button('save').click();await idle();
 let sent=await page.evaluate(()=>fixture.post.at(-1));check(sent.body.planned_hours==='3.14'&&sent.body.actual_minutes==='60'&&sent.body.payable_hours===null,'save sends decimal strings/null');
 check(!('actual_hours' in sent.body)&&!('actor_code' in sent.body)&&!('organization_code' in sent.body),'save whitelist excludes authority and derived actual');
 await page.evaluate(()=>{fixture.mode='reviewer';return fixture.open();});
 await field('actual_minutes').fill('90');check(await field('actual_hours').inputValue()==='2.00','90 minutes preview exact');check(await button('verify').isDisabled(),'dirty draft cannot be verified');
 await field('evidence_code').fill('EVIDENCE-ONE');await button('save').click();await idle();check(await field('evidence_code').inputValue()==='EVIDENCE-ONE','normal save preserves unsubmitted verification evidence');await button('verify').click();await idle();
 sent=await page.evaluate(()=>fixture.post.at(-1));check(sent.path.endsWith('/verify')&&sent.body.evidence_code==='EVIDENCE-ONE','verify uses evidence-only trusted endpoint');
 check((await page.locator('[data-m05-verification]').textContent()).includes('已核对'),'successful review shown');
 await field('estimated_hours').fill('7.25');let count=await page.evaluate(()=>fixture.post.length);await page.evaluate(()=>{fixture.nextError=400;});await button('save').click();await idle();
 check(await field('estimated_hours').inputValue()==='7.25','400 preserves typed input');check(await page.evaluate(()=>fixture.post.length)===count+1,'400 triggers no automatic retry');check(await button('save').isDisabled(),'400 blocks unchanged payload until input is edited');
 await field('estimated_hours').fill('7.26');await page.evaluate(()=>{fixture.nextError=409;});await button('save').click();await idle();
 check(await field('estimated_hours').inputValue()==='7.26'&&await button('save').isDisabled(),'409 preserves input and blocks repeated submission');
 count=await page.evaluate(()=>fixture.post.length);await button('refresh').click();await idle();check(await field('estimated_hours').inputValue()==='7.26','explicit conflict refresh preserves input');
 check(await page.evaluate(()=>fixture.post.length)===count,'refresh does not submit a mutation');await button('save').click();await idle();
 await field('estimated_hours').fill('9.00');await field('evidence_code').fill('EVIDENCE-PENDING');await page.evaluate(()=>{fixture.nextError=599;});await button('save').click();await idle();
 const original=await page.evaluate(()=>fixture.post.at(-1).body);check(await button('retry').isVisible(),'ambiguous submit exposes explicit retry');
 await page.evaluate(()=>fixture.bump());let gets=await page.evaluate(()=>fixture.gets);await button('retry').click();await idle();
 sent=await page.evaluate(()=>fixture.post.at(-1));check(JSON.stringify(sent.body)===JSON.stringify(original),'explicit retry preserves request ID and full original payload');
 check(await page.evaluate(()=>fixture.gets)===gets+1,'old idempotent response triggers one read of current capabilities');
 check(await field('estimated_hours').inputValue()==='9.00','old response refresh preserves typed draft');check(await field('evidence_code').inputValue()==='EVIDENCE-PENDING','stale idempotent response and follow-up read preserve unsubmitted evidence');
 await field('estimated_hours').fill('10.00');await page.evaluate(()=>{fixture.nextError=403;});gets=await page.evaluate(()=>fixture.gets);await button('save').click();await idle();
 check(await page.evaluate(()=>fixture.gets)===gets+1,'403 rereads actual capabilities');check(await field('estimated_hours').inputValue()==='10.00','403 preserves input');check(!await button('verify').isVisible(),'revoked verify permission hides review action');
 for(const [width,height] of [[1280,1000],[390,844]]){
  await page.setViewportSize({width,height});check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'no page horizontal overflow '+width);
  check(await page.locator('.modal').evaluate(el=>el.scrollWidth<=el.clientWidth),'no modal horizontal overflow '+width);
  if(output){await mkdir(output,{recursive:true});await page.screenshot({path:resolve(output,'m05-delivery-'+width+'.png'),fullPage:true});}
 }
 await page.evaluate(()=>{fixture.nextError=401;});await button('save').click();await idle();check(await button('save').isDisabled(),'401 disables subsequent interaction');
 await page.evaluate(()=>{fixture.remount();fixture.defer=true;window.opening=fixture.open();});await page.waitForFunction(()=>typeof fixture.late==='function');await page.evaluate(()=>fixture.abort());
 check(await page.locator('#modal-mask').count()===0,'route abort removes host modal');check(await page.evaluate(()=>fixture.aborted===true),'route abort cancels in-flight request');
 await page.evaluate(async()=>{fixture.late();await window.opening;});check(await page.locator('#modal-mask').count()===0,'late read does not reopen modal');
 check(errors.length===0,'no browser runtime errors: '+errors.join(';'));
 console.log('M05IntegrationBrowser: '+checks+' checks passed using actual host modal/form and original styles (synthetic local fixture only)');
}finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
