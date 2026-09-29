import { tmpdir as testTmpdir } from 'node:os';
import { chromium } from 'playwright';
import { mkdir, mkdtemp, realpath, access, writeFile, readFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { randomBytes, randomUUID } from 'node:crypto';
import { dirname, resolve, join, delimiter, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

// Actual original SPA/Main, synthetic identities, empty isolated H2. No business HTTP mocks.
// --java PATH --classes ISOLATED_CLASSES including IntegrationDeliveryHistoryHttpFixture.
const app = resolve(dirname(fileURLToPath(import.meta.url)), '..'), args = {};
for (let i = 2; i < process.argv.length; i += 2) { assert.ok(['--java', '--classes'].includes(process.argv[i]) && process.argv[i + 1]); args[process.argv[i]] = process.argv[i + 1]; }
assert.ok(args['--java'] && args['--classes']); const classes = await realpath(args['--classes']); assert.ok(!classes.startsWith(app + sep) && classes !== app);
for (const name of ['Main', 'IntegrationDeliveryHistoryHttpFixture']) await access(join(classes, 'com/training/' + name + '.class'));
const own = await mkdtemp(join(testTmpdir(), 'yanxu-delivery-history-http-browser-')), password = randomBytes(24).toString('hex'), tokens = {}, screenshots = [], browserErrors = [], outsideRequests = [], apiFailures = [], expectedFailures = [];
let base, child, browser, error, log = '', checks = 0;
const pause = ms => new Promise(yes => setTimeout(yes, ms));
const check = (value, label) => { assert.ok(value, label); checks++; }, equal = (value, expected, label) => { assert.deepEqual(value, expected, label); checks++; };
const username = actor => actor === 'admin' ? 'admin' : 'synthetic-history-' + actor;
async function api(route, body, actor = 'worker') {
  const response = await fetch(base + '/api' + route, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json', ...(tokens[actor] ? { 'X-Token': tokens[actor] } : {}) }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000) });
  const result = await response.json(); assert.equal(response.status, 200, route + ': HTTP ' + response.status + ' ' + result.msg); assert.equal(result.code, 0); return result.data;
}
async function boot(width) {
  for (const key of Object.keys(tokens)) delete tokens[key]; const data = join(own, 'data-' + width); await mkdir(data);
  const listener = createServer(); await new Promise((yes, no) => { listener.once('error', no); listener.listen(0, '127.0.0.1', yes); }); const port = listener.address().port; await new Promise(yes => listener.close(yes)); base = `http://127.0.0.1:${port}`;
  child = spawn(args['--java'], ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password, '-Dintegration.delivery.history.root=' + own, '-Ddata.dir=' + data, '-Dsemantic.port=', '-Dsemantic.token=', '-cp', classes + delimiter + join(app, 'lib', '*'), 'com.training.IntegrationDeliveryHistoryHttpFixture', String(port)], { cwd: app, env: { ...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: '' }, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.on('data', data => { log = (log + data).slice(-24000); }); child.stderr.on('data', data => { log = (log + data).slice(-24000); }); let launchError; child.once('error', caught => { launchError = caught; });
  for (let i = 0; i < 180; i++) { if (launchError) throw launchError; assert.equal(child.exitCode, null, 'Synthetic server unexpectedly exited'); try { tokens.worker = (await api('/login', { username: username('worker'), password }, 'anonymous')).token; return; } catch {} await pause(100); } throw Error('Synthetic history Main failed to start');
}
async function stop() { if (!child || child.exitCode !== null || child.signalCode !== null) return; await new Promise(yes => { const timer = setTimeout(() => child.kill('SIGKILL'), 2500); child.once('exit', () => { clearTimeout(timer); yes(); }); child.kill('SIGTERM'); }); }
async function control(operation) {
  const nonce = randomUUID(); await writeFile(join(own, 'control-request.json'), JSON.stringify({ nonce, operation }));
  for (let i = 0; i < 100; i++) { try { const result = JSON.parse(await readFile(join(own, 'control-result.json'), 'utf8')); if (result.nonce === nonce) { assert.equal(result.ok, true); return; } } catch {} await pause(20); } throw Error('Synthetic control timeout');
}
async function contextFor(actor, width) {
  const context = await browser.newContext({ viewport: { width, height: 1000 }, reducedMotion: 'reduce', deviceScaleFactor: 1 });
  await context.route('**/*', route => { const url = new URL(route.request().url()); if (url.protocol === 'data:' || url.origin === base) { if (url.pathname === '/api/visitor-context') return route.abort(); return route.continue(); } outsideRequests.push(url.origin); return route.abort(); });
  const response = await context.request.post(base + '/api/login', { data: { username: username(actor), password } }); equal(response.status(), 200, width + ': real cookie login ' + actor); equal((await response.json()).code, 0, 'Valid cookie session');
  const page = await context.newPage(); page.setDefaultTimeout(12000);
  page.on('pageerror', caught => browserErrors.push({ width, actor, message: String(caught) })); page.on('dialog', async dialog => { browserErrors.push({ width, actor, message: 'Unexpected native ' + dialog.type() }); await dialog.dismiss(); });
  page.on('response', response => { const path = new URL(response.url()).pathname; if (path.startsWith('/api/') && response.status() >= 400) (path === '/api/delivery-settlement/history' && [401, 403, 409].includes(response.status()) ? expectedFailures : apiFailures).push({ width, actor, path, status: response.status() }); });
  return { context, page };
}
async function openRecord(page, id = 1) {
  const row = page.locator(`#tbl [data-row-id="${id}"]`), button = row.getByRole('button', { name: '授课记录', exact: true });
  if (await button.isVisible()) await button.click(); else { await row.locator('summary').click(); await page.getByRole('button', { name: '授课记录', exact: true }).click(); }
  await page.waitForFunction(() => document.querySelector('[data-m05-meta]')?.textContent.includes('版本 '));
}
async function historyAction(page, action = 'toggle') { await page.locator('[data-m05-history-action="' + action + '"]').click(); if (action !== 'toggle' || await page.locator('[data-m05-history-panel]').count()) await page.waitForFunction(() => !document.querySelector('[data-m05-history-status]')?.textContent.includes('正在读取')); }
async function mutate(page, action) { const response = page.waitForResponse(response => new URL(response.url()).pathname === '/api/delivery-settlement/' + action && response.request().method() === 'POST'); await page.locator('[data-m05-action="' + action + '"]').click(); equal((await response).status(), 200, 'Real original form ' + action); await page.waitForFunction(() => !document.querySelector('[data-m05-status]')?.textContent.includes('正在处理')); }
async function noOverflow(page, label) {
  const size = await page.evaluate(() => ({ viewport: innerWidth, page: document.documentElement.scrollWidth, surfaces: [...document.querySelectorAll('.modal, [data-m05-history], [data-m05-history] .table-wrap')].map(node => [node.clientWidth, node.scrollWidth]), buttonsInside: [...document.querySelectorAll('[data-m05-delivery] .modal-foot button:not([hidden])')].every(node => { const box = node.getBoundingClientRect(), parent = document.querySelector('.modal').getBoundingClientRect(); return !box.width || (box.left >= parent.left && box.right <= parent.right); }) }));
  check(size.page <= size.viewport + 1 && size.buttonsInside && size.surfaces.every(([width, scroll]) => scroll <= width + 1), label + ': no horizontal overflow ' + JSON.stringify(size));
}
async function screenshot(page, name) { const file = join(own, name + '.png'); await page.screenshot({ path: file, fullPage: false, animations: 'disabled' }); screenshots.push(file); }
const detail = () => api('/delivery-settlement?dispatch_id=1');
async function saveFact(estimated, actual = '67.5') { const current = await detail(); return api('/delivery-settlement/save', { dispatch_id: 1, expected_version: current.version, request_id: 'HISTORY-BROWSER-' + randomUUID(), estimated_hours: estimated, planned_hours: null, actual_minutes: actual, payable_hours: '9007199254740993.12345678' }); }
async function verify(width) {
  const { context, page } = await contextFor('worker', width), calls = [], posts = [];
  page.on('request', request => { if (new URL(request.url()).pathname === '/api/delivery-settlement/history') calls.push(request); if (request.method() === 'POST') posts.push(request); });
  try {
    await page.goto(base + '/#/dispatches'); await page.locator('#tbl tbody tr').first().waitFor(); await openRecord(page);
    equal(calls.length, 0, width + ': initial record does not request history'); await noOverflow(page, 'original form ' + width); await historyAction(page); check((await page.locator('[data-m05-history]').textContent()).includes('尚无保存或核对记录'), width + ': honest initial empty history');
    await page.locator('[data-k="estimated_hours"]').fill('0'); await page.locator('[data-k="planned_hours"]').fill(''); await page.locator('[data-k="actual_minutes"]').fill('60'); await page.locator('[data-k="payable_hours"]').fill('9007199254740993.12345678');
    await page.locator('[data-m05-delivery] > .modal-foot').evaluate(node => node.scrollIntoView({ block: 'end' })); await noOverflow(page, 'action bar ' + width); await screenshot(page, 'delivery-history-form-' + width);
    await mutate(page, 'save'); equal(await page.locator('[data-m05-history-panel]').count(), 0, width + ': save clears prior history'); equal((await detail()).version, 1, width + ': first saved revision');
    await page.locator('[data-k="evidence_code"]').fill('SYNTHETIC-HISTORY-EVIDENCE'); await mutate(page, 'verify'); equal((await detail()).version, 2, width + ': verification is a separate real revision');
    await page.locator('[data-k="actual_minutes"]').fill('67.5'); await historyAction(page);
    const comparison = page.locator('[data-m05-history-comparison]'); check((await comparison.textContent()).includes('实际 1.33') && !(await comparison.textContent()).includes('实际 1.50'), width + ': dirty preview is excluded from saved comparison'); equal(await page.locator('[data-k="actual_minutes"]').inputValue(), '67.5', width + ': reading preserves draft');
    const beforeRead = posts.length; await page.locator('[data-m05-history-action="select"][data-version="1"]').click(); equal(posts.length, beforeRead, width + ': comparing saved versions never posts');
    await mutate(page, 'save'); await historyAction(page); check((await comparison.textContent()).includes('本次保存使原核对失效') && (await comparison.textContent()).includes('实际 1.50'), width + ': save invalidates prior verification and displays exact new actual');
    check((await comparison.textContent()).includes('预计 0') && (await comparison.textContent()).includes('计划 未记录') && (await comparison.textContent()).includes('9007199254740993.12345678'), width + ': zero/null/high precision stay distinct');
    await page.locator('[data-m05-history]').evaluate(node => node.scrollIntoView({ block: 'start' })); await noOverflow(page, 'timeline ' + width); await screenshot(page, 'delivery-history-timeline-' + width);
    await comparison.evaluate(node => node.scrollIntoView({ block: 'start' })); await noOverflow(page, 'comparison ' + width); await screenshot(page, 'delivery-history-comparison-' + width);
    for (let version = 4; version <= 22; version++) await saveFact(String(version));
    await page.locator('[data-k="actual_minutes"]').fill('75'); await historyAction(page); await historyAction(page);
    check((await page.locator('[data-m05-history-status]').textContent()).includes('读取最新记录'), width + ': stale expected version yields explicit conflict'); equal(await page.locator('[data-m05-history] table').count(), 0, width + ': conflict clears prior table'); check(await page.locator('[data-m05-action="save"]').isDisabled(), width + ': stale history locks write until explicit read');
    await page.locator('[data-m05-action="refresh"]').click(); await page.waitForFunction(() => document.querySelector('[data-m05-meta]')?.textContent.includes('版本 22')); equal(await page.locator('[data-k="actual_minutes"]').inputValue(), '75', width + ': refresh keeps unsubmitted input');
    await historyAction(page); equal(await page.locator('[data-m05-history-action="select"]').count(), 20, width + ': server page has 20 revisions'); await historyAction(page, 'next'); equal(await page.locator('[data-m05-history-action="select"]').count(), 2, width + ': second page has remaining revisions');
    check((await comparison.textContent()).includes('版本 2') && (await comparison.textContent()).includes('未核对') && (await comparison.textContent()).includes('已核对'), width + ': page boundary comparison has predecessor outside current selection'); check(await page.locator('[data-m05-history-action="next"]').isDisabled(), width + ': final page has no extra navigation');
    await comparison.evaluate(node => node.scrollIntoView({ block: 'start' })); await noOverflow(page, 'page two ' + width); await screenshot(page, 'delivery-history-pagination-' + width);
    // Hold an unchanged response from the actual server, then switch target. No replacement data is mocked.
    await historyAction(page); let release, intercepted = false; const gate = new Promise(yes => { release = yes; });
    await page.route('**/api/delivery-settlement/history?dispatch_id=1&**', async route => { const response = await route.fetch(); intercepted = true; await gate; try { await route.fulfill({ response }); } catch {} });
    await page.locator('[data-m05-history-action="toggle"]').click(); for (let i = 0; !intercepted && i < 100; i++) await pause(20); check(intercepted, width + ': delayed response is real server history');
    await page.locator('#modal-x').click(); await openRecord(page, 2); await historyAction(page); release(); await pause(100);
    check((await page.locator('[data-m05-meta]').textContent()).includes('授课安排 2') && (await page.locator('[data-m05-history]').textContent()).includes('尚无保存或核对记录'), width + ': late previous record cannot contaminate successor'); equal(await page.locator('[data-m05-history] table').count(), 0, width + ': no predecessor private table leaks'); await page.unroute('**/api/delivery-settlement/history?dispatch_id=1&**');
    await page.locator('#modal-x').click(); await openRecord(page); await historyAction(page); await control('revoke-worker'); await historyAction(page, 'next');
    await page.waitForFunction(() => !document.querySelector('[data-m05-history]')); equal(await page.locator('[data-m05-history-comparison], #modal-mask').count(), 0, width + ': real session revocation clears all history and modal');
    check(calls.every(request => request.method() === 'GET'), width + ': every history operation is read only');
  } finally { await context.close(); }
}
async function verifyReader(width) {
  const { context, page } = await contextFor('reader', width);
  try { await page.goto(base + '/#/dispatches'); await page.locator('#tbl tbody tr').first().waitFor(); await openRecord(page); check(await page.locator('[data-m05-action="save"]').isDisabled(), width + ': read-only account cannot edit'); await historyAction(page); equal(await page.locator('[data-m05-history-action="select"]').count(), 20, width + ': read-only account can inspect authorized history'); }
  finally { await context.close(); }
}
try {
  browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined, headless: true });
  for (const width of [1280, 390, 320]) { await boot(width); await verify(width); await verifyReader(width); await stop(); }
  equal(browserErrors, [], 'No browser errors/native confirmations'); equal(outsideRequests, [], 'No external browser requests'); equal(apiFailures, [], 'No unexpected business API errors');
} catch (caught) { error = caught; }
finally {
  if (browser) await browser.close(); await stop(); await writeFile(join(own, 'server.log'), log.replaceAll(password, '[synthetic password redacted]'), { mode: 0o600 });
  const result = { ok: !error, checks, classes, temporaryDirectory: own, screenshots, browserErrors, outsideRequests, apiFailures, expectedFailures, temporaryServiceStopped: !child || child.exitCode !== null || child.signalCode !== null, ...(error ? { error: error.stack || String(error) } : {}) }; await writeFile(join(own, 'result.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify(result, null, 2));
}
if (error) process.exitCode = 1;
