import { tmpdir as testTmpdir } from 'node:os';
import { chromium } from 'playwright';
import { mkdir, mkdtemp, realpath, access, writeFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { randomBytes, randomUUID } from 'node:crypto';
import { dirname, resolve, join, delimiter, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

// Actual Main, original SPA, cookie login and synthetic H2 only. No business HTTP is mocked.
// The retry test commits through the actual API and drops only its response to simulate a lost acknowledgement.
// Run with --java PATH --classes ISOLATED_COMPILED_DIRECTORY (including IntegrationAccountEmailHttpFixture).
const app = resolve(dirname(fileURLToPath(import.meta.url)), '..'), args = {};
for (let i = 2; i < process.argv.length; i += 2) { assert.ok(['--java', '--classes', '--mode'].includes(process.argv[i]) && process.argv[i + 1]); args[process.argv[i]] = process.argv[i + 1]; }
assert.ok(args['--java'] && args['--classes'], 'Explicit Java and isolated classes required');
assert.ok(!args['--mode'] || args['--mode'] === 'visual', 'Optional mode must be visual');
const classes = await realpath(args['--classes']); assert.ok(classes !== app && !classes.startsWith(app + sep));
for (const name of ['Main', 'IntegrationAccountEmailHttpFixture']) await access(join(classes, 'com/training/' + name + '.class'));
const own = await mkdtemp(join(testTmpdir(), 'yanxu-account-email-http-browser-')), password = randomBytes(24).toString('hex'), classpath = classes + delimiter + join(app, 'lib', '*');
const screenshots = [], browserErrors = [], outsideRequests = [], apiFailures = [], expectedFailures = [], tokens = {};
let child, browser, base, error, log = '', checks = 0;
const pause = ms => new Promise(yes => setTimeout(yes, ms));
function check(value, label) { assert.ok(value, label); checks++; }
function equal(value, expected, label) { assert.deepEqual(value, expected, label); checks++; }
const username = actor => actor === 'admin' ? 'admin' : 'synthetic-email-' + actor;
async function api(route, body, actor = 'admin') {
  const response = await fetch(base + '/api' + route, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json', ...(tokens[actor] ? { 'X-Token': tokens[actor] } : {}) }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000) });
  const result = await response.json(); assert.equal(response.status, 200, route + ': HTTP ' + response.status); assert.equal(result.code, 0, route + ': unsuccessful envelope'); return result.data;
}
async function login(actor) { tokens[actor] = (await api('/login', { username: username(actor), password }, 'anonymous')).token; }
async function boot(width) {
  for (const key of Object.keys(tokens)) delete tokens[key];
  const data = join(own, 'data-' + width); await mkdir(data);
  const listener = createServer(); await new Promise((yes, no) => { listener.once('error', no); listener.listen(0, '127.0.0.1', yes); }); const port = listener.address().port; await new Promise(yes => listener.close(yes)); base = `http://127.0.0.1:${port}`;
  child = spawn(args['--java'], ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password, '-Dintegration.account.email.root=' + own, '-Ddata.dir=' + data, '-Dsemantic.port=', '-Dsemantic.token=', '-cp', classpath, 'com.training.IntegrationAccountEmailHttpFixture', String(port)], { cwd: app, env: { ...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: '' }, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.on('data', b => { log = (log + b).slice(-24000); }); child.stderr.on('data', b => { log = (log + b).slice(-24000); });
  let startupError; child.once('error', caught => { startupError = caught; });
  for (let i = 0; i < 180; i++) { if (startupError) throw startupError; assert.equal(child.exitCode, null, 'Synthetic Main exited during startup'); try { await login('admin'); await login('admin2'); return; } catch {} await pause(100); }
  throw Error('Synthetic Main did not become ready');
}
async function stop() { if (!child || child.exitCode !== null || child.signalCode !== null) return; await new Promise(yes => { const timer = setTimeout(() => child.kill('SIGKILL'), 2500); child.once('exit', () => { clearTimeout(timer); yes(); }); child.kill('SIGTERM'); }); }
async function contextFor(actor, width) {
  const context = await browser.newContext({ viewport: { width, height: 1000 }, deviceScaleFactor: 1, reducedMotion: 'reduce' });
  await context.route('**/*', route => { const url = new URL(route.request().url()); if (url.protocol === 'data:' || url.origin === base) { if (url.pathname === '/api/visitor-context') return route.abort(); return route.continue(); } outsideRequests.push(url.origin); return route.abort(); });
  const response = await context.request.post(base + '/api/login', { data: { username: username(actor), password } }); equal(response.status(), 200, width + ': actual cookie login ' + actor); equal((await response.json()).code, 0, 'Cookie session envelope');
  const page = await context.newPage(); page.setDefaultTimeout(12000);
  page.on('pageerror', caught => browserErrors.push({ width, actor, error: String(caught) }));
  page.on('dialog', async dialog => { browserErrors.push({ width, actor, error: 'Unexpected native ' + dialog.type() }); await dialog.dismiss(); });
  page.on('response', response => { const url = new URL(response.url()); if (url.pathname.startsWith('/api/') && response.status() >= 400) (url.pathname === '/api/account-email-preparation' && [404, 409].includes(response.status()) ? expectedFailures : apiFailures).push({ width, actor, path: url.pathname, status: response.status() }); });
  return { context, page };
}
async function noOverflow(page, label) {
  const sizes = await page.evaluate(() => ({ width: innerWidth, page: document.documentElement.scrollWidth, surfaces: [...document.querySelectorAll('.modal, [data-email-history], [data-email-history] .table-wrap, #modal-mask .toolbar')].map(node => [node.clientWidth, node.scrollWidth]) }));
  check(sizes.page <= sizes.width + 1, label + ': no page overflow ' + JSON.stringify(sizes));
  check(sizes.surfaces.every(([width, scroll]) => scroll <= width + 1), label + ': no component overflow ' + JSON.stringify(sizes));
}
async function screenshot(page, name) { const file = join(own, name + '.png'); await page.screenshot({ path: file, fullPage: false, animations: 'disabled' }); screenshots.push(file); }
async function originalAction(page, id, label) {
  const row = page.locator(`#tbl [data-row-id="${id}"]`), direct = row.getByRole('button', { name: label, exact: true });
  if (await direct.isVisible()) return direct.click(); await row.locator('summary').click(); await page.getByRole('button', { name: label, exact: true }).click();
}
async function openEmail(page, id = 5) { await originalAction(page, id, '登记邮箱'); await page.waitForFunction(() => document.querySelector('[data-k="email"]')?.disabled === false); }
async function save(page, text) {
  if (text !== undefined) await page.locator('[data-k="email"]').fill(text);
  const response = page.waitForResponse(response => new URL(response.url()).pathname === '/api/account-email-preparation' && response.request().method() === 'POST');
  await page.locator('[data-email-save]').click(); const result = await response; await page.waitForFunction(() => document.querySelector('#modal-mask')?.dataset.locked !== 'true'); return result;
}
async function latest(userId = 5) { return api('/account-email-preparation?user_id=' + userId); }
async function postCandidate(userId, email, actor = 'admin2') { const state = await latest(userId); return api('/account-email-preparation', { user_id: userId, expected_revision: state.revision, request_id: randomUUID(), email }, actor); }
async function verify(width) {
  const before = await api('/users'), { context, page } = await contextFor('admin', width), requests = [], writes = [];
  page.on('request', request => { if (new URL(request.url()).pathname === '/api/account-email-preparation') { requests.push(request); if (request.method() === 'POST') writes.push(request.postDataJSON()); } });
  try {
    await page.goto(base + '/#/users'); await page.locator('#tbl tbody tr').first().waitFor(); equal(requests.length, 0, width + ': no private email reads on list load');
    for (const id of [5, 6, 7, 9]) check((await page.locator(`#tbl [data-row-id="${id}"]`).textContent()).includes('登记邮箱'), width + ': disabled viewer has local entry ' + id);
    for (const id of [1, 8, 10]) check(!(await page.locator(`#tbl [data-row-id="${id}"]`).textContent()).includes('登记邮箱'), width + ': other rows have no entry ' + id);
    await openEmail(page); equal(requests.length, 1, width + ': explicit action reads just its target');
    equal(await page.locator('#modal-title').textContent(), '登记邮箱', width + ': original modal host');
    const target = await page.locator('[data-email-target]').textContent(); check(target.includes('SYNTHETIC EMAIL pending-a') && target.includes('synthetic-email-pending-a') && target.includes('#5'), width + ': clear current name, username and internal ID');
    check((await page.locator('[data-email-state]').textContent()).includes('未登记'), width + ': missing is distinct from verified');
    await noOverflow(page, 'empty ' + width); await screenshot(page, 'account-email-empty-' + width);
    const candidate = 'Synthetic.Person.With.Long.Local.Part@EXAMPLE.TEST';
    equal((await save(page, candidate)).status(), 200, width + ': real candidate save');
    let persisted = await latest(); equal([persisted.revision, persisted.status, persisted.email], [1, 'PENDING_VERIFICATION', candidate.replace('EXAMPLE.TEST', 'example.test')], width + ': persisted candidate normalizes domain and stays unverified');
    equal(Object.keys(writes[0]).sort(), ['email', 'expected_revision', 'request_id', 'user_id'], width + ': exact four-key request'); check(/^[a-f0-9-]{36}$/.test(writes[0].request_id), width + ': UUID generated');
    equal(await page.locator('[data-email-notice]').textContent(), '已保存，待本人验证。', width + ': save confirms candidate status concisely');
    equal((await save(page)).status(), 200, width + ': same-address save accepted'); equal((await latest()).revision, 1, width + ': same candidate does not add history');
    await postCandidate(6, candidate.toLowerCase()); await page.locator('[data-email-read]').click(); await page.waitForFunction(() => document.querySelector('[data-email-duplicate]')?.textContent.includes('该邮箱已用于其他登记，请核对。'));
    check(!(await page.locator('.modal-body').textContent()).includes('pending-b'), width + ': duplicate warning never exposes another identity');
    await noOverflow(page, 'history ' + width); await screenshot(page, 'account-email-history-' + width);
    await page.locator('[data-email-clear]').click(); equal(await page.locator('[data-k="email"]').inputValue(), '', width + ': clear first changes only the draft'); equal((await latest()).revision, 1, width + ': no implicit clear POST');
    equal((await save(page)).status(), 200, width + ': explicit clear saves'); persisted = await latest(); equal([persisted.revision, persisted.email, persisted.status, persisted.history.length], [2, null, 'MISSING', 2], width + ': clear retains audit history');
    await postCandidate(5, 'concurrent@example.test'); equal((await save(page, 'stale-draft@example.test')).status(), 409, width + ': real CAS rejects stale revision');
    await page.waitForFunction(() => document.querySelector('[data-email-notice]')?.textContent.includes('旧填写已清除'));
    equal(await page.locator('[data-k="email"]').inputValue(), '', width + ': CAS removes stale candidate'); check(await page.locator('[data-email-save]').isDisabled(), width + ': CAS requires deliberate human refill');
    check((await page.locator('[data-email-history]').textContent()).includes('concurrent@example.test'), width + ': refreshed current server history');
    equal((await save(page, 'reviewed-after-conflict@example.test')).status(), 200, width + ': explicit refill uses refreshed revision'); equal(writes.at(-1).expected_revision, 3, width + ': fresh CAS version');
    // Commit a real request and deliberately lose only its response; retry reaches the same real server.
    let dropped = false;
    await page.route('**/api/account-email-preparation', async route => { if (route.request().method() !== 'POST' || dropped) return route.continue(); await route.fetch(); dropped = true; await route.abort('failed'); });
    await page.locator('[data-k="email"]').fill('lost-ack@example.test'); await page.locator('[data-email-save]').click();
    await page.waitForFunction(() => document.querySelector('[data-email-notice]')?.textContent.includes('保存结果尚未确认'));
    check(dropped && (await latest()).revision === 5, width + ': lost acknowledgement follows actual server commit');
    const unknown = { ...writes.at(-1) }, count = writes.length; await pause(80); equal(writes.length, count, width + ': uncertain save does not automatically resend');
    check(await page.locator('[data-k="email"]').isDisabled() && await page.locator('[data-email-save]').isDisabled(), width + ': uncertain request freezes draft');
    await noOverflow(page, 'unknown save ' + width); await screenshot(page, 'account-email-retry-' + width);
    const retried = page.waitForResponse(response => new URL(response.url()).pathname === '/api/account-email-preparation' && response.request().method() === 'POST'); await page.locator('[data-email-retry]').click(); equal((await retried).status(), 200, width + ': explicit retry reaches server');
    await page.waitForFunction(() => document.querySelector('[data-email-notice]')?.textContent.includes('已保存，待本人验证。')); equal(writes.at(-1), unknown, width + ': same UUID, version and immutable candidate on retry'); equal((await latest()).revision, 5, width + ': server replay creates no second revision'); await page.unroute('**/api/account-email-preparation');
    await page.locator('#modal-x').click(); equal(await page.locator('[data-email-history], [data-k="email"]').count(), 0, width + ': close removes all private email DOM');
    await originalAction(page, 7, '登记邮箱'); await page.waitForFunction(() => document.querySelector('[data-email-notice]')?.textContent.includes('不符合'));
    equal(await page.locator('[data-k="email"]').inputValue(), '', width + ': server-ineligible row cannot display last target'); check(await page.locator('[data-email-save]').isDisabled(), width + ': server eligibility controls saving'); equal(await page.locator('[data-email-history]').textContent(), '', width + ': ineligible response clears history'); await page.locator('#modal-x').click();
    await originalAction(page, 1, '编辑'); equal(await page.locator('#modal-title').textContent(), '编辑用户', width + ': original edit retained'); await page.locator('#modal-x').click(); await page.locator('#add-btn').click(); equal(await page.locator('#modal-title').textContent(), '新增用户', width + ': original new user retained'); await page.locator('#modal-x').click();
    // A held response is fetched from Main unchanged; cancellation and route changes must clear it.
    let release, intercepted = false; const gate = new Promise(yes => { release = yes; });
    await page.route('**/api/account-email-preparation?user_id=5', async route => { const response = await route.fetch(); intercepted = true; await gate; try { await route.fulfill({ response }); } catch {} });
    await originalAction(page, 5, '登记邮箱'); for (let i = 0; !intercepted && i < 100; i++) await pause(20); check(intercepted, width + ': late read comes from real Main');
    await page.locator('#modal-x').click(); equal(await page.locator('#modal-mask').count(), 0, width + ': reading can be cancelled'); await page.evaluate(() => { location.hash = '#/dashboard'; }); await page.locator('#priority-list').waitFor(); release(); await pause(100);
    equal(await page.locator('[data-email-history], [data-k="email"], #modal-mask').count(), 0, width + ': late read cannot reopen old target after leaving route');
    equal(await api('/users'), before, width + ': no account fields, activation or permissions changed'); equal(await api('/organization/config'), { version: null, configuration: null }, width + ': candidate registration needs no formal organization publication');
    check(requests.every(request => !request.url().includes('@') && !request.url().includes('%40')), width + ': candidates never enter request URLs');
    const storage = await page.evaluate(() => Object.values(localStorage).join('\n')); check(!storage.includes('@example.test'), width + ': candidates never enter localStorage');
  } finally { await context.close(); }
}
async function verifyVisual(width) {
  await postCandidate(5, 'Synthetic.Person.With.Long.Local.Part@example.test');
  await postCandidate(6, 'synthetic.person.with.long.local.part@example.test');
  const { context, page } = await contextFor('admin', width);
  try {
    await page.goto(base + '/#/users'); await page.locator('#tbl tbody tr').first().waitFor(); await openEmail(page);
    equal(await page.locator('[data-email-notice]').textContent(), '', width + ': successful read needs no repeated notice');
    equal(await page.locator('[data-email-state]').textContent(), '登记状态：待本人验证', width + ': concise state');
    equal(await page.locator('[data-email-duplicate]').textContent(), '该邮箱已用于其他登记，请核对。', width + ': generic concise duplicate warning');
    check(!(await page.locator('[data-email-target]').textContent()).includes('验证'), width + ': identity box contains only current account identity');
    check((await page.locator('.modal-body').textContent()).includes('请填写此人的常用邮箱，保存后需本人验证。'), width + ': single introductory instruction');
    check((await page.locator('.modal-body').textContent()).includes('留空并保存可清除登记，修改记录会保留。'), width + ': concise clearing hint');
    await noOverflow(page, 'revised history ' + width); await screenshot(page, 'account-email-history-' + width);
  } finally { await context.close(); }
}
async function verifyNonAdmin(width) {
  for (const actor of ['manager', 'viewer']) {
    const { context, page } = await contextFor(actor, width), calls = [];
    page.on('request', request => { if (new URL(request.url()).pathname === '/api/account-email-preparation') calls.push(request); });
    try { await page.goto(base + '/#/users'); await page.locator('#content').waitFor(); await page.waitForLoadState('networkidle'); equal(calls.length, 0, width + ': ' + actor + ' makes no email preparation request'); equal(await page.getByRole('button', { name: '登记邮箱', exact: true }).count(), 0, width + ': ' + actor + ' has no email action'); }
    finally { await context.close(); }
  }
}
try {
  browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined, headless: true });
  for (const width of args['--mode'] === 'visual' ? [1280, 390] : [1280, 390, 320]) { await boot(width); if (args['--mode'] === 'visual') await verifyVisual(width); else { await verify(width); await verifyNonAdmin(width); } await stop(); }
  equal(browserErrors, [], 'No page errors or native confirmations'); equal(outsideRequests, [], 'No external browser requests'); equal(apiFailures, [], 'No unexpected API failures');
} catch (caught) { error = caught; }
finally {
  if (browser) await browser.close(); await stop();
  await writeFile(join(own, 'server.log'), log.replaceAll(password, '[synthetic password redacted]'), { mode: 0o600 });
  const result = { ok: !error, checks, mode: args['--mode'] || 'full', classes, temporaryDirectory: own, screenshots, browserErrors, outsideRequests, apiFailures, expectedFailures, temporaryServiceStopped: !child || child.exitCode !== null || child.signalCode !== null, ...(error ? { error: error.stack || String(error) } : {}) };
  await writeFile(join(own, 'result.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify(result, null, 2));
}
if (error) process.exitCode = 1;
