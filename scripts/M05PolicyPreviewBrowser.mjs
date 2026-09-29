import { chromium } from 'playwright';
import { readFile, mkdir } from 'node:fs/promises';
import { createServer } from 'node:http';
import { resolve, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

// Isolated synthetic fixture on an OS-assigned port. Never connects to a running app/API.
const scriptDir = dirname(fileURLToPath(import.meta.url));
const app = process.env.M05_INTEGRATION_APP_ROOT || resolve(scriptDir, '..');
const moduleFile = process.env.M05_POLICY_UI_MODULE || resolve(app, 'web/modules/delivery-settlement/policy-preview.js');
const output = process.env.M05_POLICY_UI_SCREENSHOTS;
const host = await readFile(resolve(app, 'web/app.js'), 'utf8');
const modal = host.slice(host.indexOf('  function openModal('), host.indexOf('  function confirmBox('));
const icons = host.match(/  const icon =[^\n]+/)[0] + '\n' + host.slice(host.indexOf('  function refreshIcons('), host.indexOf('  const debounce ='));
assert.ok(modal.includes('function closeModal('), 'actual host modal helpers were located');
const styles = ['style', 'studio', 'ledger', 'v10', 'v13'];
const html = `<!doctype html><html lang="zh-CN" data-visual="ledger"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
${styles.map((name) => `<link rel="stylesheet" href="/${name}.css">`).join('')}
<body class="yx-v13"><main id="mount" class="content"><h1>课程与排期</h1><p>合成测试：原项目区挂载课酬预览</p><div id="project-panel"></div></main><script src="/lucide.min.js"></script><script type="module">
import { mountPolicyPreview } from '/modules/delivery-settlement/policy-preview.js';
const $=(s,el)=>(el||document).querySelector(s), $$=(s,el)=>Array.from((el||document).querySelectorAll(s));
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
${icons}
let modalPreviousFocus=null,chartInstances=[],activeRowMenu=null;
function closeRowMenu(){activeRowMenu=null;}
${modal}
const clone=v=>JSON.parse(JSON.stringify(v));
const base={dispatch_id:7,fact_version:3,service_date:'2026-09-22',actual_minutes:'60',actual_hours:'1.33',payable_hours:'1.33',
status:'CONDITIONAL_PREVIEW',can_confirm:false,raw_amount_is_conditional:true,amount_scope:'INDIVIDUAL',
rate:{unit_rate:'150',reference_grade:'SENIOR',activity:'TEACHING',source_reference:'ATTACHMENT-RATE-TABLE'},raw_amount:'199.5000000',final_amount:null,
pool_raw_amount:null,pool_final_amount:null,individual_raw_amount:null,individual_final_amount:null,
execution_basis:{adoption_decision:'用户明确按所提供管理办法执行',adoption_date:'2026-09-22',source_file_name:'金尊公司内部培训师管理办法2.docx',
source_consultation_draft:true,source_publication_date:null,effective_from:null,execution_version:'M05-USER-APPROVED-SOURCE-C7E8BAAA-V1',
source_sha256:'c7e8baaa7ac874b2d8fded1551f82413f62f920bf99efe748aec830450599572'},
issues:[{code:'SHARED_DELIVERY_APPROVAL_UNKNOWN',message:'共享交付部核准事实待核'},
{code:'MONEY_ROUNDING_NOT_CONFIGURED',message:'金额舍入待确定'}],formal_missing_items:[{code:'FORMAL_SETTLEMENT_CONFIRMATION_REQUIRED',message:'尚待正式结算确认'}],scenarios:[]};
const fixture={requests:[],nextError:0,defer:false,late:null,aborted:false,patch:{}};
async function api(path,options){fixture.requests.push({path,method:options.method,body:options.body});
 if(fixture.nextError){const code=fixture.nextError;fixture.nextError=0;throw Object.assign(new Error('private error'),{code});}
 if(fixture.defer){fixture.defer=false;return new Promise(resolve=>{fixture.late=()=>resolve({...clone(base),...clone(fixture.patch)});options.signal.addEventListener('abort',()=>{fixture.aborted=true;},{once:true});});}
 return {...clone(base),...clone(fixture.patch)};}
let component,route;
fixture.mount=async(mode='project')=>{component?.cleanup();closeModal();route=new AbortController();let root=$('#project-panel');
 if(mode==='modal'){const mask=openModal('授课记录与核对','<p>合成测试：原授课弹窗挂载</p><div id="preview-mount"></div>',{wide:true,noFoot:true,kicker:'课程与排期',onClose:()=>route.abort()});root=$('#preview-mount',mask);}
 component=mountPolicyPreview(root,{api,signal:route.signal});await component.open(7);};
fixture.open=()=>component.open(7);fixture.cleanup=()=>component.cleanup();fixture.abort=()=>route.abort();fixture.close=()=>closeModal();
fixture.scenarios=()=>{fixture.patch={status:'INCOMPLETE',rate:null,raw_amount:null,raw_amount_is_conditional:false,scenarios:[
 {reference_grade:'LECTURER',activity:'TEACHING',teaching_day_type:'WORKDAY',unit_rate:'100',payable_hours:'1.33',raw_amount:'133.00',conditional:true,condition:'计酬等级、研发身份和授课日别须经核实'},
 {reference_grade:'SENIOR',activity:'TEACHING',teaching_day_type:'REST_DAY',unit_rate:'300',payable_hours:'1.33',raw_amount:'399.00',conditional:true,condition:'仅在高级讲师标准及休息日授课事实核实后适用'}]};return fixture.open();};
window.fixture=fixture;await fixture.mount();window.ready=true;
</script></body></html>`;

const allowed = new Set([...styles.map((name) => `/${name}.css`), '/lucide.min.js']);
const server = createServer(async (req, res) => {
  try {
    const path = new URL(req.url, 'http://127.0.0.1').pathname;
    if (path === '/fixture') { res.setHeader('Content-Type', 'text/html;charset=utf-8'); res.end(html); return; }
    if (path === '/favicon.ico') { res.writeHead(204); res.end(); return; }
    const file = path === '/modules/delivery-settlement/policy-preview.js' ? moduleFile
      : allowed.has(path) ? resolve(app, 'web', path.slice(1)) : null;
    if (!file) { res.writeHead(404); res.end(); return; }
    res.setHeader('Content-Type', extname(file) === '.js' ? 'text/javascript;charset=utf-8' : 'text/css;charset=utf-8');
    res.end(await readFile(file));
  } catch { res.writeHead(404); res.end(); }
});
await new Promise((done) => server.listen(0, '127.0.0.1', done));
let browser, checks = 0;
const check = (condition, message) => { assert.ok(condition, message); checks++; };
try {
  browser = await chromium.launch({ executablePath: process.env.M05_CHROME || undefined, headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });
  const errors = []; page.on('pageerror', (error) => errors.push(String(error)));
  await page.goto(`http://127.0.0.1:${server.address().port}/fixture`); await page.waitForFunction(() => window.ready);
  const slot = (key) => page.locator(`[data-m05-policy-slot="${key}"]`);
  const refresh = () => page.locator('[data-m05-policy-action="refresh"]');
  const noOverflow = async (label) => {
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `no page horizontal overflow: ${label}`);
    check(await page.locator('[data-m05-policy-preview]').evaluate((el) => el.scrollWidth <= el.clientWidth), `no panel horizontal overflow: ${label}`);
  };
  for (const width of [1280, 390]) {
    await page.setViewportSize({ width, height: 1000 });
    await page.evaluate(async () => { fixture.patch = {}; await fixture.mount(); });
    await noOverflow(`project ${width}`);
    check(await slot('raw').textContent() === '199.5000000 元', `exact decimal displayed at ${width}`);
    check(await slot('final').textContent() === '待确定', `rounded amount pending at ${width}`);
    check(!await slot('sources').getAttribute('open'), `source details closed by default at ${width}`);
    check(await page.locator('[data-m05-policy-preview] button').count() === 1, `read-only controls at ${width}`);
    if (output) { await mkdir(output, { recursive: true }); await page.screenshot({ path: resolve(output, `m05-policy-preview-${width}.png`), fullPage: true, animations: 'disabled' }); }
    await slot('sources').locator('summary').click(); await noOverflow(`source details ${width}`);
    check(await slot('sources').locator('wbr').count() > 0, `long source identifiers have native wrapping ${width}`);
    await page.evaluate(() => fixture.scenarios()); await noOverflow(`scenarios ${width}`);
    check((await slot('scenarios').textContent()).includes('399.00 元'), `scenario exact arithmetic at ${width}`);
    const cells = slot('scenarios').locator('tbody td');
    check(await cells.evaluateAll((nodes) => nodes.every((node) => node.dataset.label && node.textContent.trim())), `scenario column labels and values present at ${width}`);
    if (width === 390) {
      check(await cells.first().evaluate((el) => getComputedStyle(el, '::before').content.includes('参照等级')), 'mobile generated label remains visible');
      check(await cells.evaluateAll((nodes) => nodes.every((node) => { const value = node.firstElementChild; return value && value.getBoundingClientRect().width > 40; })), 'mobile scenario values have readable width');
    }
    await page.evaluate(() => fixture.mount('modal')); await noOverflow(`host modal ${width}`);
    check(await page.locator('.modal').evaluate((el) => el.scrollWidth <= el.clientWidth), `no original modal overflow at ${width}`);
    await page.locator('#modal-x').click();
    check(await page.locator('[data-m05-policy-preview]').count() === 0, `host close cleans preview at ${width}`);
  }
  await page.evaluate(async () => { fixture.patch = { status: 'PREVIEW_READY', amount_scope: 'DEVELOPMENT_POOL',
    rate: { activity: 'JOINT_DEVELOPMENT', reference_grade: 'SENIOR', unit_rate: '150' }, raw_amount: null,
    pool_raw_amount: '199.50000', individual_raw_amount: null }; await fixture.mount(); });
  check(await slot('pool-raw').textContent() === '199.50000 元', 'joint pool is labelled independently');
  check(await slot('individual-raw').textContent() === '待核', 'joint unknown personal allocation is not zero');
  await page.evaluate(() => { fixture.defer = true; window.pending = fixture.open(); });
  check(await slot('content').textContent() === '', 'refresh immediately removes old amount and sources');
  await page.evaluate(() => fixture.abort());
  check(await page.locator('[data-m05-policy-preview]').count() === 0, 'abort removes owned DOM');
  check(await page.evaluate(() => fixture.aborted), 'abort cancels request');
  await page.evaluate(async () => { fixture.late(); await window.pending; });
  check(await page.locator('[data-m05-policy-preview]').count() === 0, 'late response cannot remount after cleanup');
  for (const code of [401, 403]) {
    await page.evaluate(async (code) => { fixture.patch = {}; await fixture.mount(); fixture.nextError = code; await fixture.open(); }, code);
    check(await slot('content').textContent() === '', `${code} removes sensitive preview`);
    check(await refresh().isDisabled(), `${code} disables refresh`);
  }
  await page.evaluate(async () => { fixture.patch = { issues: [{ code: 'TEST', message: '<img src=x onerror="globalThis.pwned=1">' }] }; await fixture.mount(); });
  check(await slot('issues').locator('img').count() === 0, 'server text creates no HTML element');
  check(await page.evaluate(() => !globalThis.pwned), 'server text cannot execute script');
  check(await page.evaluate(() => fixture.requests.every((request) => request.method === 'GET' && request.body === undefined
    && request.path === '/delivery-settlement/policy-preview?dispatch_id=7')), 'all requests are GET by ID only');
  check(errors.length === 0, `no browser runtime errors: ${errors.join(';')}`);
  console.log(`M05PolicyPreviewBrowser: ${checks} checks passed; original five CSS files and host modal; 1280/390px; isolated synthetic API only.`);
} finally {
  if (browser) await browser.close();
  await new Promise((done) => server.close(done));
}
