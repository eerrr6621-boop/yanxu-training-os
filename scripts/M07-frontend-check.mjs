import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createPreviewHtml, HOST_STYLESHEETS } from './M07-build-preview.mjs';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const moduleDir = path.resolve(process.env.M07_MODULE_DIR || path.join(scriptDir, '../web/modules/survey-results'));
const hostWeb = path.resolve(process.env.M07_HOST_WEB_DIR || path.join(moduleDir, '../..'));
const playwrightPath = process.env.M07_PLAYWRIGHT_MODULE || 'playwright';
const playwrightUrl = path.isAbsolute(playwrightPath) ? pathToFileURL(playwrightPath).href : playwrightPath;
const { chromium } = await import(playwrightUrl);
const executablePath = process.env.M07_BROWSER_EXECUTABLE || undefined;
const source = fs.readFileSync(path.join(moduleDir, 'index.js'), 'utf8');
const style = fs.readFileSync(path.join(moduleDir, 'styles.css'), 'utf8');
const hostStyle = HOST_STYLESHEETS.map(name => fs.readFileSync(path.join(hostWeb, name), 'utf8')).join('\n');
const noMotionCss = '*,*::before,*::after{animation:none!important;transition:none!important;scroll-behavior:auto!important;}';
const fixtures = await import('data:text/javascript;base64,' + Buffer.from(source + '\nexport { demoPreview };').toString('base64'));
const synthetic = fixtures.demoPreview('normal', 9001);
const mixed = fixtures.demoPreview('mixed', 9001);
const rulesDraft = synthetic.rulesDraft;
const safeConfig = { ready: true, adapterId: 'internal-survey-xlsx-v1', synthetic: false, projects: [{ id: 11, key: 'P11', name: '测试项目甲' }, { id: 12, key: 'P12', name: '测试项目乙' }], rulesDraft, maxBytes: 5 * 1024 * 1024 };
const safePreview = { ...synthetic, adapterId: safeConfig.adapterId, synthetic: false, projectId: 11, projectName: '测试项目甲' };
const payload = Buffer.from('synthetic-workbook-bytes');
const scratchDir = fs.mkdtempSync(path.join(os.tmpdir(), 'M07-frontend-check-'));
const previewPath = path.join(scratchDir, 'preview.html');
fs.writeFileSync(previewPath, createPreviewHtml(source, style + '\n' + noMotionCss, HOST_STYLESHEETS.map(name => pathToFileURL(path.join(hostWeb, name)).href)));
let browser;
try {
  browser = await chromium.launch({ executablePath, headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1100 }, deviceScaleFactor: 1, reducedMotion: 'reduce' });
page.setDefaultTimeout(5000);
const errors = []; page.on('pageerror', error => errors.push(error.message));
let groups = 0;
const pass = label => { groups++; console.log(`PASS ${groups}: ${label}`); };
async function reset() {
  await page.setContent('<html><body class="yx-v13"><div id="outside">HOST</div><div class="mask"><div class="modal wide"><div class="modal-head"><h3>导入评分 Excel</h3></div><main class="modal-body" id="root"></main></div></div></body></html>');
  await page.addStyleTag({ content: hostStyle + '\n' + style + '\n' + noMotionCss });
  await page.addScriptTag({ content: '(() => {\n' + source.replace('export async function mount', 'async function mount') + '\nwindow.testMount = mount;\n})();' });
}
async function mountLive(config = safeConfig, preview = safePreview) {
  await reset();
  await page.evaluate(async ({ config, preview }) => {
    window.calls = [];
    window.cleanup = await window.testMount(document.getElementById('root'), { mode: 'live', request: async (url, options) => {
      window.calls.push({ url, method: options.method, body: options.body });
      return url.endsWith('/config') ? config : preview;
    } });
  }, { config, preview });
  await page.waitForFunction(() => !document.body.textContent.includes('正在检查主系统接入状态'));
}
async function upload(buffer = payload, name = 'anonymous.xlsx') {
  await page.locator('input[type=file]').setInputFiles({ name, mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer });
  await page.waitForFunction(() => !document.body.textContent.includes('正在读取所选文件'));
}
async function selectAndPreview() {
  await page.locator('[data-project]').selectOption('11');
  await page.locator('[data-action=preview]').click();
}
  await page.goto(pathToFileURL(previewPath).href);
  assert.equal(await page.locator('link[rel=stylesheet]').count(), 5);
  assert.equal(await page.evaluate(() => [...document.styleSheets].filter(sheet => sheet.href?.startsWith('file:')).length), 5);
  assert.ok((await page.evaluate(() => getComputedStyle(document.body).getPropertyValue('--primary'))).trim().startsWith('#'));
  assert.equal(await page.locator('.yx-survey-results h1, .yx-survey-results h2').count(), 0);
  assert.deepEqual(await page.locator('.modal').evaluate(element => ({ opacity: getComputedStyle(element).opacity, animation: getComputedStyle(element).animationName, background: getComputedStyle(element).backgroundColor })), { opacity: '1', animation: 'none', background: 'rgb(255, 255, 255)' });
  assert.equal(await page.locator('.mask').evaluate(element => getComputedStyle(element).opacity), '1');
  assert.equal(await page.locator('tbody tr').count(), 10);
  assert.equal(await page.locator('input[type=file]').count(), 0);
  assert.equal(await page.locator('.yx-x-overall-number strong').textContent(), '9.00');
  assert.ok((await page.locator('.yx-x-summary-stats').textContent()).includes('答卷记录数（未去重）'));
  await page.screenshot({ path: path.join(scratchDir, 'xlsx-frontend-normal.png'), fullPage: true, animations: 'disabled' });
  await page.getByRole('button', { name: '含空白与异常' }).click();
  assert.equal(await page.locator('.yx-x-overall-number strong').textContent(), '9.50');
  assert.equal(await page.locator('.yx-x-issues li').count(), 3);
  assert.ok((await page.locator('.yx-x-overall-note').textContent()).includes('有效 2 · 空白 1 · 异常 1'));
  assert.ok((await page.locator('tbody tr').nth(3).textContent()).includes('10.00'));
  assert.ok(!(await page.locator('#m07-preview').textContent()).includes('合成异常'));
  await page.screenshot({ path: path.join(scratchDir, 'xlsx-frontend-mixed.png'), fullPage: true, animations: 'disabled' });
  await page.locator('[data-action=filter][data-value=review]').click();
  assert.equal(await page.locator('tbody tr').count(), 5);
  pass('standalone demo shows 10 question aggregates, independent q10 mean, separate counts and filter without individual values');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: path.join(scratchDir, 'xlsx-frontend-mobile.png'), fullPage: true, animations: 'disabled' });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await page.locator('tbody tr').first().scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(scratchDir, 'xlsx-frontend-mobile-table.png'), fullPage: true, animations: 'disabled' });
  assert.equal(await page.locator('tbody tr').first().locator('td[data-label]').count(), 5);
  pass('original five stylesheets load; component has no new page heading and 390px uses host table cards');
  await page.setViewportSize({ width: 1440, height: 1100 });

  await reset();
  await page.evaluate(async () => {
    window.reads = 0; window.calls = [];
    window.cleanup = await window.testMount(document.getElementById('root'), { mode: 'demo', request: () => { window.calls.push('unexpected'); } });
    const input = document.createElement('input'); input.type = 'file'; input.dataset.file = '';
    Object.defineProperty(input, 'files', { value: [{ name: 'private.xlsx', size: 1, arrayBuffer: async () => { window.reads++; return new ArrayBuffer(1); } }] });
    document.querySelector('.yx-survey-results').append(input);
    input.dispatchEvent(new Event('change', { bubbles: true }));
  });
  assert.equal(await page.evaluate(() => window.reads), 0);
  await page.locator('[data-project]').selectOption('9002');
  assert.deepEqual(await page.evaluate(() => window.calls), []);
  assert.equal(await page.locator('#outside').textContent(), 'HOST');
  pass('demo rejects forged real-file events and project/sample changes make no requests');

  for (const config of [{ ...safeConfig, ready: false }, { ...safeConfig, synthetic: true }, { ...safeConfig, rulesDraft: { ...rulesDraft, confirmed: true } }]) {
    await mountLive(config);
    assert.equal(await page.locator('input[type=file]').isDisabled(), true);
    assert.equal(await page.locator('tbody tr').count(), 0);
    assert.ok(!(await page.locator('#root').textContent()).includes('合成项目'));
    await page.evaluate(() => {
      window.reads = 0;
      const input = document.querySelector('input[type=file]');
      Object.defineProperty(input, 'files', { value: [{ name: 'private.xlsx', size: 1, arrayBuffer: async () => { window.reads++; return new ArrayBuffer(1); } }] });
      input.dispatchEvent(new Event('change', { bubbles: true }));
    });
    assert.equal(await page.evaluate(() => window.reads), 0);
  }
  await page.screenshot({ path: path.join(scratchDir, 'xlsx-frontend-live-closed.png'), fullPage: true, animations: 'disabled' });
  pass('unready, synthetic or invalid live configs cannot read/upload files and never show demo data');

  await mountLive();
  await upload(payload, 'PRIVATE_EMPLOYEE_123.xlsx');
  assert.equal(await page.locator('[data-action=preview]').isDisabled(), true);
  assert.equal((await page.evaluate(() => window.calls)).length, 1);
  assert.ok(!(await page.locator('#root').innerHTML()).includes('PRIVATE_EMPLOYEE_123'));
  await selectAndPreview();
  await page.locator('tbody tr').first().waitFor();
  const posted = await page.evaluate(() => window.calls.find(call => call.method === 'POST'));
  const body = JSON.parse(posted.body);
  assert.equal(posted.url, '/api/survey-response-imports/preview');
  assert.deepEqual(Buffer.from(body.xlsxBase64, 'base64'), payload);
  assert.equal(body.projectId, 11);
  assert.equal(body.fileName, 'PRIVATE_EMPLOYEE_123.xlsx');
  assert.equal(Object.hasOwn(body, 'revisionHint'), false);
  pass('file stays local until explicit preview with visible project; exact bytes only go to authorized preview endpoint');

  await mountLive({ ...safeConfig, maxBytes: 10 * 1024 * 1024 });
  await page.evaluate(() => {
    window.reads = 0;
    const input = document.querySelector('input[type=file]');
    Object.defineProperty(input, 'files', { value: [{ name: 'too-large.xlsx', size: 5 * 1024 * 1024 + 1, arrayBuffer: async () => { window.reads++; return new ArrayBuffer(1); } }] });
    input.dispatchEvent(new Event('change', { bubbles: true }));
  });
  assert.equal(await page.evaluate(() => window.reads), 0);
  assert.ok((await page.getByRole('alert').textContent()).includes('大小上限'));
  await upload(Buffer.alloc(5 * 1024 * 1024));
  await page.locator('[data-project]').selectOption('11');
  assert.equal(await page.locator('[data-action=preview]').isDisabled(), false);
  await upload(payload, 'wrong.csv');
  assert.ok((await page.getByRole('alert').textContent()).includes('.xlsx'));
  assert.equal((await page.evaluate(() => window.calls)).length, 1);
  pass('5 MiB exact limit is readable, one byte above is rejected before read; file type rejection sends nothing');

  const privateMarker = 'PRIVATE_PERSON_999';
  const attack = '<img src=x onerror="window.attackExecuted=true">';
  const dirty = { ...safePreview, questions: safePreview.questions.map((question, index) => ({ ...question, label: index === 0 ? attack : question.label, rawRows: [[privateMarker]] })),
    issues: [{ row: 4, column: 'D', code: 'INVALID_SCORE', severity: 'WARNING', message: privateMarker, value: privateMarker }], rawRows: [[privateMarker]], warnings: [privateMarker], fileFingerprint: privateMarker, scoreFingerprint: privateMarker };
  await mountLive(safeConfig, dirty);
  await upload(payload, `${privateMarker}.xlsx`);
  await selectAndPreview();
  await page.locator('tbody tr').first().waitFor();
  assert.ok(!(await page.locator('#root').innerHTML()).includes(privateMarker));
  assert.equal(await page.locator('#root img').count(), 0);
  assert.equal(await page.evaluate(() => window.attackExecuted), undefined);
  assert.ok((await page.locator('#root').textContent()).includes(attack));
  pass('private extra fields, issue values/messages, warnings, fingerprints and filenames never enter DOM; HTML is escaped');

  await reset();
  await page.evaluate(async config => {
    window.calls = [];
    window.cleanup = await window.testMount(document.getElementById('root'), { mode: 'live', request: async (url, options) => { window.calls.push(url); if (url.endsWith('/config')) return config; throw new Error('PRIVATE_PERSON_999 raw row'); } });
  }, safeConfig);
  await upload(); await selectAndPreview(); await page.getByRole('alert').waitFor();
  assert.ok(!(await page.locator('#root').innerHTML()).includes(privateMarker));
  assert.equal(await page.locator('tbody tr').count(), 0);
  pass('request failure hides raw server messages and does not fall back to synthetic success');

  await mountLive();
  await page.evaluate(() => {
    const inject = (name, resolveKey) => {
      const input = document.querySelector('input[type=file]');
      Object.defineProperty(input, 'files', { value: [{ name, size: 1, arrayBuffer: () => new Promise(resolve => { window[resolveKey] = resolve; }) }] });
      input.dispatchEvent(new Event('change', { bubbles: true }));
    };
    inject('A.xlsx', 'resolveA'); inject('B.xlsx', 'resolveB');
    window.resolveB(new Uint8Array([66]).buffer);
  });
  await page.waitForFunction(() => !document.body.textContent.includes('正在读取所选文件'));
  await page.evaluate(() => window.resolveA(new Uint8Array([65]).buffer));
  await selectAndPreview(); await page.locator('tbody tr').first().waitFor();
  const lastFile = JSON.parse(await page.evaluate(() => window.calls.find(call => call.method === 'POST').body));
  assert.equal(lastFile.fileName, 'B.xlsx'); assert.equal(lastFile.xlsxBase64, 'Qg==');
  pass('late file A read cannot overwrite the newer file B');

  await reset();
  await page.evaluate(async config => {
    window.cleanup = await window.testMount(document.getElementById('root'), { mode: 'live', request: (url, options) => {
      if (url.endsWith('/config')) return Promise.resolve(config);
      window.previewSignal = options.signal;
      return new Promise(resolve => { window.resolvePreview = resolve; });
    } });
  }, safeConfig);
  await upload(); await selectAndPreview();
  await page.locator('[data-project]').selectOption('12');
  assert.equal(await page.evaluate(() => window.previewSignal.aborted), true);
  await page.evaluate(response => window.resolvePreview(response), safePreview);
  await page.waitForTimeout(10);
  assert.equal(await page.locator('tbody tr').count(), 0);
  assert.equal(await page.locator('[data-project]').inputValue(), '12');
  pass('project change aborts the old preview and discards its late result');

  await mountLive();
  await page.evaluate(() => {
    const input = document.querySelector('input[type=file]');
    Object.defineProperty(input, 'files', { value: [{ name: 'pending.xlsx', size: 1, arrayBuffer: () => new Promise(resolve => { window.resolveFile = resolve; }) }] });
    input.dispatchEvent(new Event('change', { bubbles: true }));
    window.cleanup(); window.resolveFile(new Uint8Array([1]).buffer);
  });
  await page.waitForTimeout(10);
  assert.equal(await page.locator('#root').innerHTML(), '');
  assert.equal((await page.evaluate(() => window.calls)).length, 1);
  pass('cleanup while reading discards file bytes and cannot initiate upload or update DOM');

  await reset();
  await page.evaluate(async config => {
    window.aborter = new AbortController();
    window.cleanup = await window.testMount(document.getElementById('root'), { mode: 'live', signal: window.aborter.signal, request: (url, options) => {
      if (url.endsWith('/config')) return Promise.resolve(config);
      window.previewSignal = options.signal;
      return new Promise(resolve => { window.resolvePreview = resolve; });
    } });
  }, safeConfig);
  await upload(); await selectAndPreview();
  await page.evaluate(response => { window.aborter.abort(); window.resolvePreview(response); }, safePreview);
  await page.waitForTimeout(10);
  assert.equal(await page.evaluate(() => window.previewSignal.aborted), true);
  assert.equal(await page.locator('#root').innerHTML(), '');
  assert.equal(await page.locator('#outside').textContent(), 'HOST');
  pass('abort during upload cancels the request, clears owned content, and ignores late response');

  for (const bad of [{ ...safePreview, synthetic: true }, { ...safePreview, canCommit: true }, { ...safePreview, policyConfirmed: true }, { ...safePreview, projectId: 12 }, { ...safePreview, mode: 'COMMIT' }, { ...safePreview, state: 'ERROR' }]) {
    await mountLive(safeConfig, bad); await upload(); await selectAndPreview(); await page.getByRole('alert').waitFor();
    assert.equal(await page.locator('tbody tr').count(), 0);
  }
  pass('preview rejects synthetic, writable, confirmed, wrong-project and inconsistent-state results');

  await mountLive({ ...safeConfig, defaultProjectId: 12 });
  assert.equal(await page.locator('[data-project]').inputValue(), '12');
  await mountLive({ ...safeConfig, defaultProjectId: 999 });
  assert.equal(await page.locator('[data-project]').inputValue(), '');
  pass('host default project only preselects a project in the authorized visible list');

  const truncated = { ...safePreview, issueCount: 2007, issuesTruncated: true, issues: Array.from({ length: 2000 }, (_, index) => ({ row: index + 2, column: 'A', code: 'INVALID_SCORE', severity: 'WARNING' })) };
  await mountLive(safeConfig, truncated); await upload(); await selectAndPreview(); await page.locator('tbody tr').first().waitFor();
  assert.equal(await page.locator('.yx-x-issues li').count(), 2000);
  assert.ok((await page.locator('.yx-x-issues').textContent()).includes('共 2007 条问题，当前显示前 2000 条；题目统计包含全部记录'));
  pass('truncated issue list reports total and visible count while retaining full question statistics');

  const fileError = { ...safePreview, state: 'ERROR', questions: [], responseRowCount: 0, issues: [{ row: 0, column: '', code: 'SHEET_MISSING', severity: 'ERROR' }] };
  await mountLive(safeConfig, fileError); await upload(); await selectAndPreview();
  await page.getByText('这份 Excel 还不能生成汇总', { exact: true }).waitFor();
  assert.equal(await page.locator('.yx-x-overview').count(), 0);
  assert.equal(await page.locator('tbody tr').count(), 0);
  assert.ok((await page.locator('.yx-x-issues').textContent()).includes('没有找到'));
  pass('file-level ERROR shows failure and issue locations rather than a completed statistical summary');

  const noOverall = structuredClone(safePreview);
  noOverall.questions[0].averageText = '0.00';
  noOverall.questions[9] = { ...noOverall.questions[9], validCount: 0, blankCount: 4, invalidCount: 0, averageText: null };
  await mountLive(safeConfig, noOverall); await upload(); await selectAndPreview(); await page.locator('tbody tr').first().waitFor();
  assert.equal(await page.locator('.yx-x-overall-number strong').textContent(), '—');
  assert.ok((await page.locator('.yx-x-overall-note').textContent()).includes('暂无有效评分'));
  assert.ok(!(await page.locator('.yx-x-overall-number').textContent()).includes('%'));
  assert.deepEqual(errors, []);
  pass('no valid overall score remains missing, cannot borrow another question or convert to percentage; no browser errors');
  console.log(`Verified ${groups} new XLSX scenario groups.`);
} finally {
  try { await browser?.close(); } finally { fs.rmSync(scratchDir, { recursive: true, force: true }); }
}
