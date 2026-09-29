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

// Original users SPA + real Main/API. Synthetic pinned source and isolated H2 only.
// node scripts/IntegrationBranchCoverageBrowser.mjs --java PATH --classes ISOLATED_COMPILED_DIRECTORY
const app = resolve(dirname(fileURLToPath(import.meta.url)), '..'), args = {}, run = promisify(execFile);
for (let i = 2; i < process.argv.length; i += 2) { assert.ok(['--java', '--classes'].includes(process.argv[i]) && process.argv[i + 1]); args[process.argv[i]] = process.argv[i + 1]; }
assert.ok(args['--java'] && args['--classes'], 'Explicit Java and isolated classes required');
const classes = await realpath(args['--classes']); assert.ok(classes !== app && !classes.startsWith(app + sep));
for (const name of ['Main', 'IntegrationBranchCoverageHttpFixture', 'OrganizationAccountImportSourceTest']) await access(join(classes, 'com/training/' + name + '.class'));
const own = await mkdtemp(join(testTmpdir(), 'yanxu-branch-coverage-http-browser-')), password = randomBytes(24).toString('hex'), classpath = classes + delimiter + join(app, 'lib', '*');
const screenshots = [], browserErrors = [], outsideRequests = [], apiFailures = [], expectedFailures = [], tokens = {};
let browser, child, base, manifest, log = '', error = null, checks = 0;
const pause = ms => new Promise(yes => setTimeout(yes, ms));
function check(value, label) { assert.ok(value, label); checks++; }
function equal(value, expected, label) { assert.deepEqual(value, expected, label); checks++; }
const username = actor => actor === 'admin' ? 'admin' : 'synthetic-branch-' + actor;
async function api(route, body, actor = 'admin') {
  const response = await fetch(base + '/api' + route, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json', ...(tokens[actor] ? { 'X-Token': tokens[actor] } : {}) }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000) });
  const envelope = await response.json(); assert.equal(response.status, 200, route + ': HTTP ' + response.status + ': ' + envelope.msg); assert.equal(envelope.code, 0, route + ': ' + envelope.msg); return envelope.data;
}
async function login(actor) { tokens[actor] = (await api('/login', { username: username(actor), password }, 'anonymous')).token; }
async function boot(label) {
  for (const key of Object.keys(tokens)) delete tokens[key];
  const data = join(own, 'data-' + label); await mkdir(data);
  const shared = ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password, '-Ddata.dir=' + data, '-Dintegration.branch.coverage.root=' + own];
  if (!manifest) { const result = await run(args['--java'], [...shared, '-cp', classpath, 'com.training.IntegrationBranchCoverageHttpFixture', 'prepare'], { cwd: app, timeout: 20000 }); manifest = JSON.parse(result.stdout.trim()); equal([manifest.candidates, manifest.branches], [7, 3], 'Only seven synthetic people and three synthetic branches'); }
  const server = createServer(); await new Promise((yes, no) => { server.once('error', no); server.listen(0, '127.0.0.1', yes); }); const port = server.address().port; await new Promise(yes => server.close(yes)); base = `http://127.0.0.1:${port}`;
  child = spawn(args['--java'], [...shared, '-Dsemantic.port=', '-Dsemantic.token=', '-Daccount.import.manifest=' + manifest.manifest, '-Daccount.import.manifest.sha256=' + manifest.sha256, '-cp', classpath, 'com.training.IntegrationBranchCoverageHttpFixture', String(port)], { cwd: app, env: { ...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: '' }, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.on('data', b => { log = (log + b).slice(-24000); }); child.stderr.on('data', b => { log = (log + b).slice(-24000); });
  let failed; child.once('error', e => { failed = e; });
  for (let i = 0; i < 180; i++) { if (failed) throw failed; assert.equal(child.exitCode, null, 'Temporary Main exited before login'); try { await login('admin'); return; } catch {} await pause(100); }
  throw Error('Temporary Main did not become ready');
}
async function stop() { if (!child || child.exitCode !== null || child.signalCode !== null) return; await new Promise(yes => { const timer = setTimeout(() => child.kill('SIGKILL'), 2500); child.once('exit', () => { clearTimeout(timer); yes(); }); child.kill('SIGTERM'); }); }
async function contextFor(width) {
  const context = await browser.newContext({ viewport: { width, height: 1000 }, reducedMotion: 'reduce', deviceScaleFactor: 1 });
  await context.route('**/*', route => { const url = new URL(route.request().url()); if (url.protocol === 'data:' || url.origin === base) { if (url.pathname === '/api/visitor-context') return route.abort(); return route.continue(); } outsideRequests.push(url.origin); return route.abort(); });
  const login = await context.request.post(base + '/api/login', { data: { username: 'admin', password } }); equal(login.status(), 200, `${width}: real cookie login`); equal((await login.json()).code, 0, 'Cookie session authenticated');
  const page = await context.newPage(); page.setDefaultTimeout(15000);
  page.on('pageerror', e => browserErrors.push({ width, message: String(e) })); page.on('dialog', async dialog => { browserErrors.push({ width, message: 'Unexpected native dialog ' + dialog.type() }); await dialog.dismiss(); });
  page.on('response', response => { const path = new URL(response.url()).pathname; if (path.startsWith('/api/') && response.status() >= 400) (path === '/api/organization/account-import/preview' && response.status() === 401 ? expectedFailures : apiFailures).push({ width, path, status: response.status() }); });
  return { context, page };
}
async function noOverflow(page, label) {
  const size = await page.evaluate(() => ({ width: innerWidth, page: document.documentElement.scrollWidth, surfaces: [...document.querySelectorAll('#user-import-content, [data-import-branch-table], [data-import-branch-table] .table-wrap')].filter(n => n.clientWidth).map(n => [n.clientWidth, n.scrollWidth]) }));
  check(size.page <= size.width + 1 && size.surfaces.every(([a, b]) => b <= a + 1), label + ': no overflow ' + JSON.stringify(size));
}
async function shot(page, label) { const file = join(own, label + '.png'); await page.screenshot({ path: file, fullPage: false, animations: 'disabled' }); screenshots.push(file); }
async function open(page) { await page.goto(base + '/#/users'); await page.locator('#tbl tbody tr').first().waitFor(); await page.locator('#user-import-open').click(); await page.waitForFunction(() => document.querySelector('[data-import-progress]')?.textContent.includes('/ 7') || document.querySelector('[data-import-progress]')?.textContent.includes('7 人已导入')); }
async function switchTo(page, mode) { await page.locator('[data-import-view-' + mode + ']').click(); await page.locator(mode === 'branches' ? '[data-import-branches]' : '[data-import-person-content]').waitFor(); }
async function review(page, index, account = null) {
  const row = page.locator(`[data-import-table] [data-row-id="${index + 1}"]`); await row.getByRole('button', { name: '核对导入', exact: true }).click(); await page.locator('[data-k="action"]').selectOption(account === null ? 'CREATE_PENDING' : 'LINK_EXISTING');
  if (account !== null) { await page.locator('[data-k="accountId"]').fill(String(account)); await page.locator('[data-import-lookup]').click(); await page.waitForFunction(() => document.querySelector('[data-import-account-result]')?.textContent.includes('请核对确为同一人')); }
  await page.locator('[data-import-reviewed]').check(); await page.locator('#modal-ok').click(); await page.locator('#modal-mask').waitFor({ state: 'detached' });
}
async function verify(width) {
  const existing = await api('/users', { username: username('existing'), name: 'SYNTHETIC EXISTING LEADER', password, status: 1, role: 'viewer' });
  const { context, page } = await contextFor(width), calls = [];
  page.on('request', request => { if (request.url().includes('/api/organization/account-import/')) calls.push(request); });
  try {
    await open(page); const table = await page.locator('#tbl').elementHandle(); await review(page, 0); equal(calls.filter(r => r.method() === 'POST').length, 0, width + ': individual review remains local');
    await switchTo(page, 'branches'); equal(calls.filter(r => r.method() === 'GET').length, 1, width + ': view switch reuses trusted GET');
    check(!(await page.locator('[data-import-person-filters]').isVisible()) && !(await page.locator('[data-import-review-info]').isVisible()), width + ': original toolbar CSS cannot reveal hidden personnel controls');
    equal(await page.locator('[data-import-branch-table] tbody tr').count(), 3, width + ': original table presents all three branches');
    check(await page.locator('[data-import-branch-table] td[data-label="负责人 / 牵头人"]').evaluateAll(cells => cells.every(cell => cell.childElementCount === 1)), width + ': candidate lists stay together inside original mobile table cells');
    equal(await page.locator('[data-import-branch-table] button,[data-import-branch-table] input,[data-import-branch-table] select').count(), 0, width + ': no branch write or approver-selection actions');
    const a = page.locator('[data-import-branch-table] [data-row-id="1"]'); check((await a.textContent()).includes('SYNTHETIC PRIVATE 4') && (await a.textContent()).includes('SYNTHETIC PRIVATE 5') && (await a.textContent()).includes('SYNTHETIC PRIVATE 8'), width + ': all three prepared leaders/lead retained');
    check((await a.textContent()).includes('待接收') && (await a.textContent()).includes('未指定默认办理人'), width + ': no account or default handler invented');
    await page.locator('[data-import-view-switch]').evaluate(node => { node.scrollIntoView({ block: 'start' }); window.scrollBy(0, -90); }); await noOverflow(page, 'all branches ' + width); await shot(page, 'branch-coverage-all-' + width);
    await page.locator('[data-import-branch-search]').fill('BRANCH C'); equal(await page.locator('[data-import-branch-table] tbody tr').count(), 1, width + ': branch search only filters display');
    check((await page.locator('[data-import-branch-table]').textContent()).includes('明确兼任准备') && (await page.locator('[data-import-branch-table]').textContent()).includes('两项职责'), width + ': explicit combined preparation shown');
    await page.locator('[data-import-view-switch]').evaluate(node => { node.scrollIntoView({ block: 'start' }); window.scrollBy(0, -90); }); await noOverflow(page, 'combined branch ' + width); await shot(page, 'branch-coverage-combined-' + width);
    await switchTo(page, 'people'); check((await page.locator('[data-import-progress]').textContent()).includes('1 / 7'), width + ': branch reading preserved review progress');
    for (let i = 1; i < 7; i++) await review(page, i, i === 1 ? existing : null);
    await page.locator('[data-import-commit]').click(); const committed = page.waitForResponse(r => r.url().endsWith('/api/organization/account-import/commit') && r.request().method() === 'POST'); await page.locator('#modal-ok').click(); equal((await committed).status(), 200, width + ': unchanged original import commit succeeds'); await page.waitForFunction(() => document.querySelector('[data-import-progress]')?.textContent.includes('7 人已导入'));
    await switchTo(page, 'branches'); check(!(await page.locator('[data-import-branch-table]').textContent()).includes('待接收'), width + ': confirmed receipt invalidates old pending coverage');
    await page.locator('[data-import-refresh]').click(); await page.waitForFunction(() => document.querySelector('[data-import-branch-table]')?.textContent.includes('账号启用'));
    const received = await api('/organization/account-import/preview'), first = received.branchCoverage[0].leaderCandidates.find(c => c.reference === 'teacher-row-4'); equal(first.accountId, existing, width + ': original explicit linked account remains exact');
    check(received.rows.find(row => row.reference === 'teacher-row-3').accountId !== existing, width + ': same-name ordinary teacher was never merged with leader');
    check((await page.locator('[data-import-branch-table]').textContent()).includes('已接收 · 账号启用') && (await page.locator('[data-import-branch-table]').textContent()).includes('已接收 · 账号停用'), width + ': actual account states are distinct from approval rights');
    equal(calls.filter(r => r.method() === 'POST').length, 1, width + ': branch reads never repeat import'); equal(await table.evaluate(node => node === document.querySelector('#tbl')), true, width + ': original users table survives'); equal(await api('/organization/config'), { version: null, configuration: null }, width + ': no approval configuration published');
    await page.locator('[data-import-view-switch]').evaluate(node => { node.scrollIntoView({ block: 'start' }); window.scrollBy(0, -90); }); await noOverflow(page, 'received branches ' + width); await shot(page, 'branch-coverage-received-' + width);
    let release, intercepted = false; const gate = new Promise(yes => { release = yes; }); await page.route('**/api/organization/account-import/preview', async route => { const response = await route.fetch(); intercepted = true; await gate; try { await route.fulfill({ response }); } catch {} });
    await page.locator('[data-import-refresh]').click(); for (let i = 0; !intercepted && i < 100; i++) await pause(20); check(intercepted, width + ': delayed response comes from actual Main'); check(!(await page.locator('[data-import-branch-table]').textContent()).includes('SYNTHETIC PRIVATE'), width + ': pending refresh clears old people');
    await page.evaluate(() => { location.hash = '#/dashboard'; }); await page.locator('#priority-list').waitFor(); release(); await pause(80); equal(await page.locator('[data-import-branches]').count(), 0, width + ': delayed response cannot restore a previous route'); await page.unroute('**/api/organization/account-import/preview');
    await open(page); await switchTo(page, 'branches'); equal((await context.request.post(base + '/api/logout', { data: {} })).status(), 200, width + ': real cookie logout'); await page.locator('[data-import-refresh]').click(); await page.waitForFunction(() => !document.querySelector('#user-import-content')); equal(await page.locator('[data-import-branch-table]').count(), 0, width + ': expired session clears branch data');
  } finally { await context.close(); }
}
try {
  browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined, headless: true });
  for (const width of [1280, 390, 320]) { await boot(width); await verify(width); await stop(); }
  equal(browserErrors, [], 'No browser errors or native dialogs'); equal(outsideRequests, [], 'No external requests'); equal(apiFailures, [], 'No unexpected API failures');
} catch (caught) { error = caught; }
finally {
  if (browser) await browser.close(); await stop(); await writeFile(join(own, 'server.log'), log.replaceAll(password, '[synthetic password redacted]'), { mode: 0o600 });
  const result = { ok: !error, checks, classes, temporaryDirectory: own, screenshots, browserErrors, outsideRequests, apiFailures, expectedFailures, temporaryServiceStopped: !child || child.exitCode !== null || child.signalCode !== null, ...(error ? { error: error.stack || String(error) } : {}) }; await writeFile(join(own, 'result.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify(result, null, 2));
}
if (error) process.exitCode = 1;
