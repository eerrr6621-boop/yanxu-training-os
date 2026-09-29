import { chromium } from 'playwright';
import { readFile, writeFile, realpath, access } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve, sep } from 'node:path';
import vm from 'node:vm';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import assert from 'node:assert/strict';

// Actual original pageReport + actual Main and M06 export, fresh synthetic H2 only.
// Uses the existing Reports HTTP fixture setup; no business API mocks or private files.
// node scripts/IntegrationTeachingExportBrowser.mjs --java PATH --classes ISOLATED_COMPILED_SRC
const require = createRequire(import.meta.url), __dirname = dirname(fileURLToPath(import.meta.url));
const app = resolve(__dirname, '..'), run = promisify(execFile), args = {};
for (let i = 2; i < process.argv.length; i += 2) { assert.ok(['--java', '--classes'].includes(process.argv[i]) && process.argv[i + 1]); args[process.argv[i]] = process.argv[i + 1]; }
assert.ok(args['--java'] && args['--classes'], 'Explicit Java and isolated compiled classes required');
const classes = await realpath(args['--classes']); assert.ok(classes !== app && !classes.startsWith(app + sep), 'Never use repository build output');
await access(join(classes, 'com/training/IntegrationReportsHttpFixture.class'));
const fixtureSource = await readFile(join(__dirname, 'IntegrationReportsHttp.cjs'), 'utf8');
const fixtureEnd = fixtureSource.indexOf('\nfunction minor('); assert.ok(fixtureEnd > 0, 'Shared Reports HTTP fixture setup is available');
const f = vm.runInNewContext(fixtureSource.slice(0, fixtureEnd) + '\n({boot,stop,api,login,identities,project,teaching,publish,offline,own,data,password,tokens,params,getBase:()=>`http://127.0.0.1:${port}`,getConfiguration:()=>structuredClone(configuration),getFailures:()=>failures});', { require, __dirname, process, console, fetch, AbortSignal, URLSearchParams, structuredClone, setTimeout, clearTimeout });
let browser, error, checks = 0, base, teachingId;
const screenshots = [], downloads = [], browserErrors = [], outsideRequests = [], apiFailures = [], expectedDenials = [];
const check = (value, label) => { assert.ok(value, label); checks++; };
const equal = (actual, expected, label) => { assert.deepEqual(actual, expected, label); checks++; };
const mime = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
async function setup() {
  await f.boot(true); await f.identities();
  const project = await f.project('admin', '001', 'P1');
  const teacher = await f.api('/teachers', { name: 'SYNTHETIC EXPORT PRIVATE NAME', base_province: '浙江', base_city: '杭州', teacher_level: '讲师', fee_rate: 98765 }, 'admin');
  teachingId = await f.teaching(project, teacher, 'SYNTHETIC EXPORT PRIVATE SUBJECT', '2025-01-10', { estimated_hours: null, planned_hours: '9007199254740993.10', actual_minutes: '60', payable_hours: null });
  const second = await f.api('/teachers', { name: 'SYNTHETIC EXPORT ZERO NAME', base_province: '浙江', base_city: '杭州', teacher_level: '讲师' }, 'admin');
  await f.teaching(project, second, 'SYNTHETIC EXPORT ZERO SUBJECT', '2025-01-11', { estimated_hours: '0', planned_hours: '0', actual_minutes: '0', payable_hours: '0' });
  // Check read-only effects separately from fixture setup and later deliberate revision changes.
  await f.stop(); const before = await f.offline('inspect'); await f.boot();
  for (const actor of ['analyst', 'reporter', 'team']) await f.login(actor);
  base = f.getBase(); return before;
}
async function contextFor(actor, width) {
  const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce', acceptDownloads: true });
  await context.route('**/*', route => {
    const url = new URL(route.request().url());
    if (url.protocol === 'data:' || url.origin === base) { if (url.pathname === '/api/visitor-context') return route.abort(); return route.continue(); }
    outsideRequests.push(url.origin); return route.abort();
  });
  const login = await context.request.post(base + '/api/login', { data: { username: 'synthetic-reports-' + actor, password: f.password } });
  equal(login.status(), 200, actor + ': actual cookie login'); equal((await login.json()).code, 0, actor + ': cookie session accepted');
  const page = await context.newPage(); page.setDefaultTimeout(15000);
  page.on('pageerror', e => browserErrors.push(String(e)));
  page.on('dialog', async dialog => { browserErrors.push('Unexpected native dialog: ' + dialog.type()); await dialog.dismiss(); });
  page.on('response', response => { const path = new URL(response.url()).pathname; if (path.startsWith('/api/') && response.status() >= 400) (path === '/api/management-reports/teaching-export' && [401, 403, 409].includes(response.status()) ? expectedDenials : apiFailures).push({ path, status: response.status() }); });
  await page.goto(base + '/#/report'); await page.locator('#rp-reviewed-teaching-export').waitFor();
  return { context, page };
}
async function query(page, organization = '') {
  await page.locator('#rp-reviewed-start').fill('2025-01-01'); await page.locator('#rp-reviewed-end').fill('2025-01-31');
  await page.locator('#rp-reviewed-organization').selectOption(organization);
  const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/management-reports');
  await page.locator('#rp-reviewed-query').click(); const result = await (await response).json(); equal(result.code, 0, 'Original query succeeds');
  await page.locator('#rp-reviewed-summary').waitFor(); return result.data;
}
async function noOverflow(page, label) {
  const sizes = await page.evaluate(() => ({ width: innerWidth, page: document.documentElement.scrollWidth, card: [document.querySelector('#rp-reviewed').clientWidth, document.querySelector('#rp-reviewed').scrollWidth] }));
  check(sizes.page <= sizes.width + 1 && sizes.card[1] <= sizes.card[0] + 1, label + ': original report/card has no horizontal overflow ' + JSON.stringify(sizes));
}
async function screenshot(page, name) { const file = join(f.own, name + '.png'); await page.screenshot({ path: file, fullPage: false, animations: 'disabled' }); screenshots.push(file); }
async function verifyDownload(width) {
  const { context, page } = await contextFor('analyst', width);
  try {
    let requests = 0; page.on('request', req => { if (new URL(req.url()).pathname === '/api/management-reports/teaching-export') requests++; });
    equal(requests, 0, width + ': opening report never automatically downloads');
    const view = await query(page); check(view.teaching_export_available === true, width + ': backend capability enables teaching export');
    check(await page.locator('#rp-reviewed-export').isDisabled(), width + ': formal month export remains unavailable');
    check((await page.locator('#rp-reviewed-summary').textContent()).includes('9007199254740993.10'), width + ': exact large planned hours remain text');
    const chart = await page.locator('#an1').elementHandle(), report = await page.locator('#rp-box').textContent();
    await page.locator('#rp-reviewed-teaching-export').scrollIntoViewIfNeeded(); await noOverflow(page, 'download ' + width); await screenshot(page, 'teaching-export-' + width);
    const responsePromise = page.waitForResponse(r => new URL(r.url()).pathname === '/api/management-reports/teaching-export');
    const event = page.waitForEvent('download'); await page.locator('#rp-reviewed-teaching-export').click();
    const download = await event, response = await responsePromise;
    equal(response.status(), 200, width + ': actual binary export succeeds'); equal(response.headers()['content-type'].split(';')[0], mime, width + ': real response is XLSX');
    equal(Object.fromEntries(new URL(response.url()).searchParams), { start: view.start, end: view.end, date_basis: view.date_basis, organizations: view.organizations.join(','), snapshot_version: view.snapshot_version }, width + ': actual request uses exact displayed snapshot');
    equal(download.suggestedFilename(), 'reviewed-teaching-20250101-20250131.xlsx', width + ': browser has safe fixed filename');
    const file = join(f.own, 'teaching-export-' + width + '.xlsx'); await download.saveAs(file); downloads.push(file); equal(await download.failure(), null, width + ': actual browser download completes');
    const bytes = await readFile(file); check(bytes.length > 100 && bytes[0] === 80 && bytes[1] === 75, width + ': saved download contains ZIP workbook bytes');
    const unpack = await run('/usr/bin/unzip', ['-p', file], { maxBuffer: 1024 * 1024 });
    check(!/SYNTHETIC EXPORT PRIVATE|SYNTHETIC EXPORT ZERO|SYNTHETIC CUSTOMER|SYNTHETIC 001 REPORT PROJECT/.test(unpack.stdout), width + ': saved workbook contains no synthetic personal/project/content sentinel');
    check(unpack.stdout.includes('9007199254740993.10') && unpack.stdout.includes('未知') && unpack.stdout.includes('非正式编码'), width + ': real workbook preserves precise hours, unknown and system ID explanation');
    check(await chart.evaluate(node => node === document.querySelector('#an1')), width + ': export preserves original chart instance'); equal(await page.locator('#rp-box').textContent(), report, width + ': export preserves original report text');
    equal(requests, 1, width + ': one explicit click makes one real GET');
    await page.locator('#rp-reviewed-start').fill('2025-01-02'); equal(await page.locator('#rp-reviewed-teaching-export').count(), 0, width + ': changed filter immediately removes download eligibility');
  } finally { await context.close(); }
}
async function verifyReadOnly() {
  const { context, page } = await contextFor('reporter', 390);
  try { const view = await query(page); check(view.teaching_export_available === false && await page.locator('#rp-reviewed-teaching-export').isDisabled(), 'Viewer with real reports.read has no export fallback'); check((await page.locator('#rp-reviewed-download-status').textContent()).includes('暂无授课统计导出权限'), 'Read-only export denial is local and explicit'); await page.locator('#rp-reviewed-download-status').scrollIntoViewIfNeeded(); await noOverflow(page, 'read only'); await screenshot(page, 'teaching-export-read-only-390'); }
  finally { await context.close(); }
}
async function expectDownloadFailure(page, status) {
  let files = 0; const downloaded = () => files++; page.on('download', downloaded);
  const pending = page.waitForResponse(r => new URL(r.url()).pathname === '/api/management-reports/teaching-export'); await page.locator('#rp-reviewed-teaching-export').click();
  equal((await pending).status(), status, 'Actual export returns ' + status);
  if (status === 401) await page.locator('#login-form').waitFor(); else await page.getByRole('heading', { name: '授课统计下载未完成', exact: true }).waitFor();
  equal(files, 0, status + ': no browser download event'); equal(await page.locator('#rp-reviewed-summary').count(), 0, status + ': old result cleared'); page.off('download', downloaded);
}
async function verifyStaleAndSession() {
  const { context, page } = await contextFor('analyst', 1280);
  try {
    await query(page); const old = await f.api('/delivery-settlement?dispatch_id=' + teachingId, undefined, 'team');
    let updated = await f.api('/delivery-settlement/save', { dispatch_id: teachingId, expected_version: old.version, request_id: 'EXPORT-BROWSER-CHANGED', actual_minutes: '90' }, 'team');
    await f.api('/delivery-settlement/verify', { dispatch_id: teachingId, expected_version: updated.version, request_id: 'EXPORT-BROWSER-REVERIFY', evidence_code: 'SYNTHETIC-EXPORT-CHANGED' }, 'team');
    await expectDownloadFailure(page, 409); check((await page.locator('#rp-reviewed-result').textContent()).includes('重新核对'), 'Snapshot conflict asks for explicit requery');
    await query(page); await context.request.post(base + '/api/logout', { data: {} }); await expectDownloadFailure(page, 401);
  } finally { await context.close(); }
}
async function verifyRevoked() {
  const { context, page } = await contextFor('analyst', 390);
  try {
    await query(page); const cfg = f.getConfiguration(); await f.publish({ grants: cfg.grants.filter(g => !(g.roleCode === 'ANALYST' && g.resource === 'reports.export')) }, 'browser-export-revoked');
    // Re-authenticate explicitly to isolate download-time permission denial from old-session expiry.
    const login = await context.request.post(base + '/api/login', { data: { username: 'synthetic-reports-analyst', password: f.password } }); equal((await login.json()).code, 0, 'Explicit new cookie session after grant publication');
    await expectDownloadFailure(page, 403); equal(await page.locator('#rp-reviewed-organization option').count(), 1, 'Revocation clears old organization candidates'); await query(page); check(await page.locator('#rp-reviewed-teaching-export').isDisabled(), 'Fresh read reflects revoked export capability');
    await page.locator('#rp-reviewed-download-status').scrollIntoViewIfNeeded(); await noOverflow(page, 'revoked'); await screenshot(page, 'teaching-export-revoked-390');
  } finally { await context.close(); }
}
try {
  const before = await setup(); browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined, headless: true });
  for (const width of [1280, 390]) await verifyDownload(width); await verifyReadOnly();
  await f.stop(); const after = await f.offline('inspect'); equal(JSON.parse(JSON.stringify(after)), JSON.parse(JSON.stringify(before)), 'Real query/download/browser viewing leaves every business table unchanged');
  await f.boot(); for (const actor of ['analyst', 'reporter', 'team', 'leader', 'bp']) await f.login(actor); base = f.getBase();
  await verifyStaleAndSession(); await verifyRevoked();
  equal(browserErrors, [], 'No original UI runtime error or native dialog'); equal(outsideRequests, [], 'No external browser request'); equal(apiFailures, [], 'No unexpected business API failure'); equal(JSON.parse(JSON.stringify(f.getFailures())), [], 'Shared synthetic setup checks pass');
} catch (caught) { error = caught; }
finally {
  if (browser) await browser.close(); await f.stop();
  const result = { ok: !error, checks, app, classes, temporaryDirectory: f.own, syntheticDatabase: f.data, screenshots, downloads, browserErrors, outsideRequests, apiFailures, expectedDenials, ...(error ? { error: String(error.stack || error).replaceAll(f.password, '[redacted synthetic password]') } : {}) };
  await writeFile(join(f.own, 'browser-result.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify(result, null, 2));
}
if (error) process.exitCode = 1;
