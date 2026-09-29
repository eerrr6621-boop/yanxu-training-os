import { tmpdir as testTmpdir } from 'node:os';
import { chromium } from 'playwright';
import { writeFile, mkdir, mkdtemp, realpath, access } from 'node:fs/promises';
import { createServer } from 'node:net';
import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { dirname, resolve, join, delimiter, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes } from 'node:crypto';
import assert from 'node:assert/strict';

// Original SPA + actual Main/API. Fixed synthetic server manifest, new H2 for each viewport.
// Compile current src plus OrganizationAccountImportSourceTest.java and IntegrationAccountImportBrowserFixture.java.
// node scripts/IntegrationAccountImportBrowser.mjs --java PATH --classes ISOLATED_COMPILED_DIRECTORY
const app = resolve(dirname(fileURLToPath(import.meta.url)), '..'), args = {}, run = promisify(execFile);
for (let i = 2; i < process.argv.length; i += 2) { assert.ok(['--java', '--classes'].includes(process.argv[i]) && process.argv[i + 1]); args[process.argv[i]] = process.argv[i + 1]; }
assert.ok(args['--java'] && args['--classes'], 'Explicit Java and isolated classes required');
const classes = await realpath(args['--classes']); assert.ok(classes !== app && !classes.startsWith(app + sep));
for (const name of ['Main', 'IntegrationAccountImportBrowserFixture', 'OrganizationAccountImportSourceTest']) await access(join(classes, 'com/training/' + name + '.class'));
const own = await mkdtemp(join(testTmpdir(), 'yanxu-account-import-browser-')), password = randomBytes(24).toString('hex'), classpath = classes + delimiter + join(app, 'lib', '*');
const screenshots = [], browserErrors = [], outsideRequests = [], apiFailures = [], expectedFailures = [], tokens = {};
let browser, child, base, manifest, log = '', error = null, checks = 0;
const pause = ms => new Promise(yes => setTimeout(yes, ms));
function check(value, label) { assert.ok(value, label); checks++; }
function equal(value, expected, label) { assert.deepEqual(value, expected, label); checks++; }
const username = actor => actor === 'admin' ? 'admin' : 'synthetic-import-' + actor;
async function api(route, body, actor = 'admin') {
  const response = await fetch(base + '/api' + route, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json', ...(tokens[actor] ? { 'X-Token': tokens[actor] } : {}) }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000) });
  const envelope = await response.json(); assert.equal(response.status, 200, route + ': HTTP ' + response.status + ': ' + envelope.msg); assert.equal(envelope.code, 0, route + ': ' + envelope.msg); return envelope.data;
}
async function login(actor) { tokens[actor] = (await api('/login', { username: username(actor), password }, 'anonymous')).token; }
async function boot(label, configured = true) {
  for (const key of Object.keys(tokens)) delete tokens[key];
  const data = join(own, 'data-' + label); await mkdir(data);
  const server = createServer(); await new Promise((yes, no) => { server.once('error', no); server.listen(0, '127.0.0.1', yes); }); const port = server.address().port; await new Promise(yes => server.close(yes)); base = `http://127.0.0.1:${port}`;
  child = spawn(args['--java'], ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password, '-Ddata.dir=' + data, '-Dsemantic.port=', '-Dsemantic.token=', ...(configured ? ['-Daccount.import.manifest=' + manifest.manifest, '-Daccount.import.manifest.sha256=' + manifest.sha256] : []), '-cp', classpath, 'com.training.Main', String(port)], { cwd: app, env: { ...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: '' }, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.on('data', b => { log = (log + b).slice(-24000); }); child.stderr.on('data', b => { log = (log + b).slice(-24000); });
  let failed; child.once('error', e => { failed = e; });
  for (let i = 0; i < 180; i++) { if (failed) throw failed; assert.equal(child.exitCode, null, 'Temporary Main exited before login'); try { await login('admin'); return; } catch {} await pause(100); }
  throw Error('Temporary Main did not become ready');
}
async function stop() { if (!child || child.exitCode !== null || child.signalCode !== null) return; await new Promise(yes => { const timer = setTimeout(() => child.kill('SIGKILL'), 2500); child.once('exit', () => { clearTimeout(timer); yes(); }); child.kill('SIGTERM'); }); }
async function setup() {
  equal(await api('/organization/config'), { version: null, configuration: null }, 'Synthetic startup has no active business authority');
  const existing = await api('/users', { username: username('existing'), name: 'SYNTHETIC EXISTING PERSON', password, status: 1, role: 'viewer' });
  await api('/users', { username: username('manager'), name: 'SYNTHETIC MANAGER', password, status: 1, role: 'manager' });
  await login('existing'); await login('manager'); return { existing, users: await api('/users') };
}
async function contextFor(actor, width, unconfigured = false) {
  const context = await browser.newContext({ viewport: { width, height: 1000 }, reducedMotion: 'reduce', deviceScaleFactor: 1 });
  await context.route('**/*', route => { const url = new URL(route.request().url()); if (url.protocol === 'data:' || url.origin === base) { if (url.pathname === '/api/visitor-context') return route.abort(); return route.continue(); } outsideRequests.push(url.origin); return route.abort(); });
  const login = await context.request.post(base + '/api/login', { data: { username: username(actor), password } }); equal(login.status(), 200, `Cookie login ${actor}/${width}`); equal((await login.json()).code, 0, 'Real cookie session');
  const page = await context.newPage(); page.setDefaultTimeout(15000);
  page.on('pageerror', e => browserErrors.push({ width, actor, message: String(e) }));
  page.on('dialog', async dialog => { browserErrors.push({ width, actor, message: 'Unexpected native dialog ' + dialog.type() }); await dialog.dismiss(); });
  page.on('response', response => { const path = new URL(response.url()).pathname; if (path.startsWith('/api/') && response.status() >= 400) (unconfigured && path === '/api/organization/account-import/preview' && response.status() === 503 ? expectedFailures : apiFailures).push({ width, actor, path, status: response.status() }); });
  return { context, page };
}
async function noOverflow(page, label) {
  const sizes = await page.evaluate(() => ({ width: innerWidth, page: document.documentElement.scrollWidth, surfaces: [...document.querySelectorAll('.modal, #user-import-content, #user-import-content .table-wrap')].map(node => [node.clientWidth, node.scrollWidth]) }));
  check(sizes.page <= sizes.width + 1, label + ': no page overflow ' + JSON.stringify(sizes));
  check(sizes.surfaces.every(([a, b]) => b <= a + 1), label + ': no modal/import overflow ' + JSON.stringify(sizes));
}
async function screenshot(page, name) { const file = join(own, name + '.png'); await page.screenshot({ path: file, fullPage: false, animations: 'disabled' }); screenshots.push(file); }
async function originalAction(page, row, label) { const button = row.getByRole('button', { name: label, exact: true }); if (await button.isVisible()) return button.click(); await row.locator('summary').click(); await page.getByRole('button', { name: label, exact: true }).click(); }
async function openImport(page) { await page.locator('#user-import-open').click(); await page.locator('[data-import-progress]').waitFor(); await page.waitForFunction(() => document.querySelector('[data-import-progress]')?.textContent.includes('/ 7') || document.querySelector('[data-import-progress]')?.textContent.includes('7 人已导入')); }
async function verifyAdmin(fixture, width) {
  const { context, page } = await contextFor('admin', width), calls = [], commits = [];
  page.on('request', request => { if (request.url().includes('/api/organization/account-import/')) { calls.push(request); if (request.method() === 'POST') commits.push(request.postDataJSON()); } });
  try {
    await page.goto(base + '/#/users'); await page.locator('#tbl tbody tr').first().waitFor();
    equal(calls.length, 0, width + ': initial users page has no private roster request');
    const originalTable = await page.locator('#tbl').elementHandle(); await openImport(page);
    equal(calls.length, 1, width + ': explicit entry loads one server-pinned preview');
    check(!(await page.locator('#user-import-open').isVisible()), width + ': opened import has no stale visible launch button');
    equal(await page.locator('[data-import-table] tbody tr').count(), 7, width + ': all seven synthetic candidates are shown');
    check((await page.locator('[data-import-progress]').textContent()).includes('历史保留 2'), width + ': excluded history is not an import candidate');
    await page.locator('#user-import-content').scrollIntoViewIfNeeded(); await noOverflow(page, 'roster ' + width); await screenshot(page, 'account-import-roster-' + width);
    // Two deliberately matching names in the source still require explicit account ID and server proof.
    await page.locator('[data-import-search]').fill('SYNTHETIC PRIVATE 4'); equal(await page.locator('[data-import-table] tbody tr').count(), 2, width + ': search can show same-name candidates without merging');
    check(await page.locator('[data-import-commit]').isDisabled(), width + ': filtered list cannot bypass whole-batch review'); await page.locator('[data-import-search]').fill('');
    for (let index = 0; index < 7; index++) {
      await originalAction(page, page.locator(`[data-import-table] [data-row-id="${index + 1}"]`), '核对导入');
      equal(await page.locator('#modal-title').textContent(), '核对候选人员', width + ': original review modal');
      check(!(await page.locator('[data-import-reviewed]').isChecked()), width + ': no prechecked personal review');
      await page.locator('[data-k="action"]').selectOption(index ? 'CREATE_PENDING' : 'LINK_EXISTING');
      if (!index) {
        await page.locator('[data-k="accountId"]').fill(String(fixture.existing)); await page.locator('[data-import-lookup]').click();
        await page.waitForFunction(() => document.querySelector('[data-import-account-result]')?.textContent.includes('请核对确为同一人'));
        check((await page.locator('[data-import-account-result]').textContent()).includes(username('existing')), width + ': explicit lookup displays real existing account');
        await noOverflow(page, 'existing lookup ' + width); await screenshot(page, 'account-import-link-' + width);
      }
      if (index === 3) {
        check((await page.locator('.modal-body').textContent()).includes('兼任，仍需审批流程校验') && (await page.locator('.modal-body').textContent()).includes('SYNTHETIC REGION TWO'), width + ': combined preparation displays separate declared responsibilities');
        await noOverflow(page, 'combined preparation ' + width); await screenshot(page, 'account-import-scopes-' + width);
      }
      await page.locator('[data-import-reviewed]').check(); await page.locator('#modal-ok').click(); await page.locator('#modal-mask').waitFor({ state: 'detached' });
    }
    equal(commits.length, 0, width + ': local review does not yet mutate accounts'); check(!(await page.locator('[data-import-commit]').isDisabled()), width + ': all reviews enable explicit commit');
    await page.locator('[data-import-commit]').click(); equal(await page.locator('#modal-title').textContent(), '确认本批账号导入', width + ': final original confirmation');
    check((await page.locator('.modal-body').textContent()).includes('新建待启用账号 6 人，关联已有账号 1 人'), width + ': final review states complete batch composition');
    const receiptResponse = page.waitForResponse(response => response.url().endsWith('/account-import/commit') && response.request().method() === 'POST'); await page.locator('#modal-ok').click(); const receipt = (await (await receiptResponse).json()).data;
    await page.waitForFunction(() => document.querySelector('[data-import-progress]')?.textContent.includes('7 人已导入'));
    await page.waitForFunction(() => document.querySelectorAll('#tbl tbody tr').length === 9);
    equal(commits.length, 1, width + ': one confirmed import POST'); equal(Object.keys(commits[0]).sort(), ['decisions', 'expectedRevision', 'reviewToken', 'sourceFingerprint'], width + ': no browser-supplied source path or authority');
    check(commits[0].decisions[0].accountProof && commits[0].decisions[0].accountId === fixture.existing, width + ': explicit server account proof survives commit');
    equal([receipt.permissionsPublished, receipt.accountsActivated, receipt.rows.length], [false, false, 7], width + ': persisted receipt has no activation or publication');
    check(await originalTable.evaluate(node => node === document.querySelector('#tbl')), width + ': importing preserves original table container');
    check((await page.locator('#user-summary').textContent()).includes('9') && (await page.locator('#user-summary').textContent()).includes('3'), width + ': original counts refresh');
    const users = await api('/users'), created = users.filter(user => user.username.startsWith('pending_'));
    equal(created.length, 6, width + ': actual users contain six new accounts'); check(created.every(user => Number(user.status) === 0 && user.role === 'viewer'), width + ': all created accounts stay disabled viewers');
    equal(users.find(user => user.id === fixture.existing), fixture.users.find(user => user.id === fixture.existing), width + ': linked account fields remain unchanged');
    equal(await api('/organization/config'), { version: null, configuration: null }, width + ': import does not publish active organization configuration');
    check(!(await page.locator('#user-import-content').textContent()).includes('imp_p_'), width + ': internal person identifiers are not displayed as official IDs');
    await page.locator('[data-import-refresh]').click(); await page.waitForFunction(() => document.querySelector('[data-import-progress]')?.textContent.includes('7 人已导入'));
    equal(commits.length, 1, width + ': reading persisted receipt never reimports');
    await page.locator('#user-import-content').scrollIntoViewIfNeeded(); await noOverflow(page, 'receipt ' + width); await screenshot(page, 'account-import-receipt-' + width);
    await page.locator('#tbl').scrollIntoViewIfNeeded(); await noOverflow(page, 'updated users ' + width); await screenshot(page, 'account-import-users-' + width);
    await originalAction(page, page.locator(`#tbl [data-row-id="${fixture.existing}"]`), '编辑'); equal(await page.locator('#modal-title').textContent(), '编辑用户', width + ': original manual edit remains available'); await page.locator('#modal-x').click();
    await page.locator('#add-btn').click(); equal(await page.locator('#modal-title').textContent(), '新增用户', width + ': original manual add remains available'); await page.locator('#modal-x').click();
    // Hold an unmodified real GET response, navigate away, then release it to exercise cleanup.
    let release, intercepted = false; const gate = new Promise(yes => { release = yes; });
    await page.route('**/api/organization/account-import/preview', async route => { const response = await route.fetch(); intercepted = true; await gate; try { await route.fulfill({ response }); } catch {} });
    await page.locator('[data-import-refresh]').click(); for (let i = 0; !intercepted && i < 100; i++) await pause(20); check(intercepted, width + ': delayed preview is a real API response');
    await page.evaluate(() => { location.hash = '#/dashboard'; }); await page.locator('#priority-list').waitFor(); release(); await pause(80);
    equal(await page.locator('#user-import-content, [data-import-table], #modal-mask').count(), 0, width + ': late response cannot resurrect importer or modal after route change');
  } finally { await context.close(); }
}
async function verifyNonAdmin(width) {
  for (const actor of ['existing', 'manager']) {
    const { context, page } = await contextFor(actor, width), requests = [];
    page.on('request', request => { if (request.url().includes('/organization/account-import/')) requests.push(request.url()); });
    try { await page.goto(base + '/#/users'); await page.locator('#content').waitFor(); await page.waitForLoadState('networkidle'); equal(requests, [], width + ': ' + actor + ' never requests private import data'); equal(await page.locator('#user-import-open, #user-import-content').count(), 0, width + ': nonadmin has no import entry'); }
    finally { await context.close(); }
  }
}
async function verifyUnconfigured() {
  await boot('unconfigured', false); const { context, page } = await contextFor('admin', 320, true);
  try {
    await page.goto(base + '/#/users'); await page.locator('#tbl tbody tr').first().waitFor(); await page.locator('#user-import-open').click();
    await page.waitForFunction(() => document.querySelector('[data-import-notice]')?.textContent.includes('名单尚未就绪'));
    check((await page.locator('#tbl').textContent()).includes('admin'), 'Unconfigured source preserves original account table'); check(await page.locator('[data-import-commit]').isDisabled(), 'Unavailable source cannot import');
    check((await page.locator('[data-import-progress]').textContent()).includes('暂不可用') && !(await page.locator('#user-import-content').textContent()).includes('0 / 0'), 'Unconfigured source is unknown rather than a fabricated empty batch');
    check(!(await page.locator('#user-import-open').isVisible()), 'Opened unavailable import does not retain stale launch button');
    await page.locator('[data-import-notice]').scrollIntoViewIfNeeded(); await noOverflow(page, 'unconfigured 320'); await screenshot(page, 'account-import-unconfigured-320');
    await page.locator('#add-btn').click(); equal(await page.locator('#modal-title').textContent(), '新增用户', 'Unconfigured source does not block manual account creation'); await page.locator('#modal-x').click();
  } finally { await context.close(); await stop(); }
}
try {
  const generated = await run(args['--java'], ['-cp', classpath, 'com.training.IntegrationAccountImportBrowserFixture', join(own, 'fixture')], { cwd: app, timeout: 20000, maxBuffer: 65536 }); manifest = JSON.parse(generated.stdout.trim());
  equal([manifest.candidates, manifest.historicalExcluded], [7, 2], 'Only the synthetic seven-person/two-exclusion fixture is used');
  browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined, headless: true });
  await verifyUnconfigured();
  for (const width of [1280, 390, 320]) { await boot(String(width)); const fixture = await setup(); await verifyAdmin(fixture, width); await verifyNonAdmin(width); await stop(); }
  equal(browserErrors, [], 'No browser errors or native confirmations'); equal(outsideRequests, [], 'No external browser requests'); equal(apiFailures, [], 'No unexpected real business API errors');
} catch (caught) { error = caught; }
finally {
  if (browser) await browser.close(); await stop(); await writeFile(join(own, 'server.log'), log.replaceAll(password, '[synthetic password redacted]'), { mode: 0o600 });
  const result = { ok: !error, checks, app, classes, temporaryDirectory: own, screenshots, browserErrors, outsideRequests, apiFailures, expectedFailures, ...(error ? { error: error.stack || String(error) } : {}) }; await writeFile(join(own, 'result.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify(result, null, 2));
}
if (error) process.exitCode = 1;
