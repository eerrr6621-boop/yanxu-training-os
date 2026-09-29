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

// Original teacher SPA + real Main/API. Synthetic pinned source and isolated H2 only.
// node scripts/IntegrationTeacherRosterBrowser.mjs --java PATH --classes ISOLATED_COMPILED_DIRECTORY
const app = resolve(dirname(fileURLToPath(import.meta.url)), '..'), args = {}, run = promisify(execFile);
for (let i = 2; i < process.argv.length; i += 2) { assert.ok(['--java', '--classes'].includes(process.argv[i]) && process.argv[i + 1]); args[process.argv[i]] = process.argv[i + 1]; }
assert.ok(args['--java'] && args['--classes'], 'Explicit Java and isolated classes required');
const classes = await realpath(args['--classes']); assert.ok(classes !== app && !classes.startsWith(app + sep));
for (const name of ['Main', 'IntegrationTeacherRosterHttpFixture', 'OrganizationAccountImportSourceTest']) await access(join(classes, 'com/training/' + name + '.class'));
const own = await mkdtemp(join(testTmpdir(), 'yanxu-teacher-roster-http-browser-')), password = randomBytes(24).toString('hex'), classpath = classes + delimiter + join(app, 'lib', '*');
const screenshots = [], browserErrors = [], outsideRequests = [], apiFailures = [], expectedFailures = [], tokens = {};
let browser, child, base, manifest, log = '', error = null, checks = 0;
const pause = ms => new Promise(yes => setTimeout(yes, ms));
function check(value, label) { assert.ok(value, label); checks++; }
function equal(value, expected, label) { assert.deepEqual(value, expected, label); checks++; }
const username = actor => actor === 'admin' ? 'admin' : 'synthetic-roster-' + actor;
async function api(route, body, actor = 'admin') {
  const response = await fetch(base + '/api' + route, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json', ...(tokens[actor] ? { 'X-Token': tokens[actor] } : {}) }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000) });
  const envelope = await response.json(); assert.equal(response.status, 200, route + ': HTTP ' + response.status + ': ' + envelope.msg); assert.equal(envelope.code, 0, route + ': ' + envelope.msg); return envelope.data;
}
async function login(actor) { tokens[actor] = (await api('/login', { username: username(actor), password }, 'anonymous')).token; }
async function boot(label) {
  for (const key of Object.keys(tokens)) delete tokens[key];
  const data = join(own, 'data-' + label); await mkdir(data);
  const shared = ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password, '-Ddata.dir=' + data, '-Dintegration.teacher.roster.root=' + own];
  if (!manifest) { const result = await run(args['--java'], [...shared, '-cp', classpath, 'com.training.IntegrationTeacherRosterHttpFixture', 'prepare'], { cwd: app, timeout: 20000 }); manifest = JSON.parse(result.stdout.trim()); equal([manifest.candidates, manifest.teachers], [7, 6], 'Only seven synthetic account candidates and six synthetic teachers'); }
  const server = createServer(); await new Promise((yes, no) => { server.once('error', no); server.listen(0, '127.0.0.1', yes); }); const port = server.address().port; await new Promise(yes => server.close(yes)); base = `http://127.0.0.1:${port}`;
  child = spawn(args['--java'], [...shared, '-Dsemantic.port=', '-Dsemantic.token=', '-Daccount.import.manifest=' + manifest.manifest, '-Daccount.import.manifest.sha256=' + manifest.sha256, '-cp', classpath, 'com.training.IntegrationTeacherRosterHttpFixture', String(port)], { cwd: app, env: { ...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: '' }, stdio: ['ignore', 'pipe', 'pipe'] });
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
  page.on('response', response => { const path = new URL(response.url()).pathname; if (path.startsWith('/api/') && response.status() >= 400) (path === '/api/teacher-roster/preview' && response.status() === 401 ? expectedFailures : apiFailures).push({ width, path, status: response.status() }); });
  return { context, page };
}
async function prepareAccounts() {
  equal(await api('/teachers'), [], 'Synthetic original teacher library starts empty');
  const preview = await api('/organization/account-import/preview');
  const receipt = await api('/organization/account-import/commit', { expectedRevision: preview.revision, sourceFingerprint: preview.sourceFingerprint, reviewToken: preview.reviewToken, decisions: preview.rows.map(row => ({ reference: row.reference, action: 'CREATE_PENDING', accountId: null, accountProof: null, reviewed: true })) });
  equal(receipt.rows.length, 7, 'Actual M01 receives all seven synthetic accounts first');
  equal(await api('/organization/config'), { version: null, configuration: null }, 'No authority configuration is fabricated');
}
async function noOverflow(page, label) {
  const size = await page.evaluate(() => ({ width: innerWidth, page: document.documentElement.scrollWidth, surfaces: [...document.querySelectorAll('.modal, [data-roster-table], [data-roster-table] .table-wrap, .teacher-head-actions')].filter(n => n.clientWidth).map(n => [n.clientWidth, n.scrollWidth]) }));
  check(size.page <= size.width + 1 && size.surfaces.every(([a, b]) => b <= a + 1), label + ': no overflow ' + JSON.stringify(size));
}
async function shot(page, label) { const file = join(own, label + '.png'); await page.screenshot({ path: file, fullPage: false, animations: 'disabled' }); screenshots.push(file); }
async function open(page) { await page.goto(base + '/#/teachers'); await page.locator('#teacher-roster-import').click(); await page.waitForFunction(() => document.querySelector('[data-roster-summary]')?.textContent.includes('6 位教师')); }
async function rowAction(page, id, label) { const row = page.locator(`#tbl [data-row-id="${id}"]`), button = row.getByRole('button', { name: label, exact: true }); if (await button.isVisible()) await button.click(); else { await row.locator('summary').click(); await page.getByRole('button', { name: label, exact: true }).click(); } }
async function verify(width) {
  const { context, page } = await contextFor(width), calls = [], imports = [];
  page.on('request', request => { if (request.url().includes('/api/teacher-roster/')) { calls.push(request); if (request.method() === 'POST') imports.push(request); } });
  try {
    await page.goto(base + '/#/teachers'); await page.locator('#teacher-roster-import').waitFor(); equal(calls.length, 0, width + ': original library defers private roster read');
    check(await page.locator('#teacher-course-catalog').isVisible() && await page.locator('#teacher-add-manual').isVisible(), width + ': original catalog/manual actions coexist'); await noOverflow(page, 'original teacher toolbar ' + width);
    await page.locator('#teacher-roster-import').click(); await page.locator('[data-roster-table] tbody tr').first().waitFor();
    equal(await page.locator('[data-roster-table] tbody tr').count(), 6, width + ': complete synthetic teacher preview'); check((await page.locator('[data-roster-summary]').textContent()).includes('1 位额外牵头人') && (await page.locator('[data-roster-summary]').textContent()).includes('2 位历史人员'), width + ': only master teacher rows are received');
    check((await page.locator('[data-roster-table]').textContent()).includes('SYNTHETIC JOB 3') && (await page.locator('[data-roster-table]').textContent()).includes('特级讲师') && (await page.locator('[data-roster-table]').textContent()).includes('待补充'), width + ': exact jobs/levels and unknown job preserved');
    await page.locator('[data-roster-search]').fill('SYNTHETIC PRIVATE 4'); equal(await page.locator('[data-roster-table] tbody tr').count(), 2, width + ': same-name people remain separate'); check((await page.locator('[data-roster-receive]').textContent()).includes('6'), width + ': filtering does not change batch'); await page.locator('[data-roster-search]').fill('');
    await noOverflow(page, 'roster preview ' + width); await shot(page, 'teacher-roster-preview-' + width);
    const modal = await page.locator('#modal-mask').elementHandle(); const received = page.waitForResponse(r => r.url().endsWith('/api/teacher-roster/import') && r.request().method() === 'POST'); await page.locator('[data-roster-receive]').click(); equal((await received).status(), 200, width + ': explicit reception POST succeeds');
    await page.waitForFunction(() => document.querySelector('[data-roster-notice]')?.textContent.includes('本批已接收')); equal(imports.length, 1, width + ': exactly one reception POST'); equal(Object.keys(imports[0].postDataJSON()).sort(), ['batchKey', 'reviewToken', 'sourceFingerprint'], width + ': no client-supplied names or target IDs');
    check(await modal.evaluate(node => node === document.querySelector('#modal-mask')), width + ': local list refresh retains original result modal'); equal((await page.locator('[data-teacher-total]').textContent()).trim(), '6人', width + ': original teacher total updates'); equal((await page.locator('[data-teacher-in-library]').textContent()).trim(), '0人', width + ': pending teachers are not ready-to-use');
    check(!(await page.locator('[data-roster-receive]').isVisible()) && (await page.locator('[data-roster-table]').textContent()).includes('名单等级') && (await page.locator('[data-roster-table]').textContent()).includes('待完善'), width + ': received source is read-only and correctly labeled');
    const teachers = await api('/teachers'); equal(teachers.length, 6, width + ': original list received exactly six profiles'); check(teachers.every(row => row.status === '待完善' && row.base_city == null && row.base_province == null && row.fee_rate == null), width + ': no invented residence, rate or activated status');
    equal(new Set(teachers.filter(row => row.name === 'SYNTHETIC PRIVATE 4').map(row => row.org)).size, 2, width + ': same names in distinct organizations never merge');
    await noOverflow(page, 'received roster ' + width); await shot(page, 'teacher-roster-received-' + width); await page.locator('[data-roster-close]').click();
    await page.locator('#tbl').scrollIntoViewIfNeeded(); await noOverflow(page, 'original refreshed library ' + width); await shot(page, 'teacher-roster-library-' + width);
    await rowAction(page, teachers[0].id, '编辑'); equal(await page.locator('#modal-title').textContent(), '编辑师资', width + ': original teacher editor remains'); await page.locator('[data-k="name"]').fill('SYNTHETIC EDITED TEACHER'); await page.locator('[data-k="teacher_level"]').selectOption('高级讲师'); const edit = page.waitForResponse(r => new URL(r.url()).pathname === '/api/teachers' && r.request().method() === 'POST'); await page.locator('#modal-ok').click(); equal((await edit).status(), 200, width + ': original pending profile edit succeeds'); await page.locator('#teacher-roster-import').waitFor();
    await page.locator('#teacher-roster-import').click(); await page.waitForFunction(() => document.querySelector('[data-roster-notice]')?.textContent.includes('本批已接收')); check((await page.locator('[data-roster-notice]').textContent()).includes('当前档案以师资列表为准') && !(await page.locator('[data-roster-table]').textContent()).includes('SYNTHETIC EDITED TEACHER'), width + ': source preview clearly stays frozen after legitimate editing');
    equal(imports.length, 1, width + ': reopening imported batch never creates duplicates'); await page.locator('[data-roster-close]').click(); check((await page.locator('#tbl').textContent()).includes('SYNTHETIC EDITED TEACHER'), width + ': current library preserves edited profile');
    let release, intercepted = false; const gate = new Promise(yes => { release = yes; }); await page.route('**/api/teacher-roster/preview', async route => { const response = await route.fetch(); intercepted = true; await gate; try { await route.fulfill({ response }); } catch {} });
    await page.locator('#teacher-roster-import').click(); for (let i = 0; !intercepted && i < 100; i++) await pause(20); check(intercepted, width + ': delayed preview is real HTTP'); await page.locator('[data-roster-close]').click(); await page.evaluate(() => { location.hash = '#/dashboard'; }); await page.locator('#priority-list').waitFor(); release(); await pause(80); equal(await page.locator('[data-roster-table], #modal-mask').count(), 0, width + ': closed/routed modal cannot reappear from old response'); await page.unroute('**/api/teacher-roster/preview');
    await open(page); equal((await context.request.post(base + '/api/logout', { data: {} })).status(), 200, width + ': real session logout'); await page.locator('[data-roster-refresh]').click(); await page.waitForFunction(() => !document.querySelector('#modal-mask')); equal(await page.locator('[data-roster-table]').count(), 0, width + ': expired session clears private roster');
    equal(await api('/organization/config'), { version: null, configuration: null }, width + ': roster never publishes role configuration');
  } finally { await context.close(); }
}
try {
  browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined, headless: true });
  for (const width of [1280, 390, 320]) { await boot(width); await prepareAccounts(); await verify(width); await stop(); }
  equal(browserErrors, [], 'No browser errors or native dialogs'); equal(outsideRequests, [], 'No external requests'); equal(apiFailures, [], 'No unexpected API failures');
} catch (caught) { error = caught; }
finally {
  if (browser) await browser.close(); await stop(); await writeFile(join(own, 'server.log'), log.replaceAll(password, '[synthetic password redacted]'), { mode: 0o600 });
  const result = { ok: !error, checks, classes, temporaryDirectory: own, screenshots, browserErrors, outsideRequests, apiFailures, expectedFailures, temporaryServiceStopped: !child || child.exitCode !== null || child.signalCode !== null, ...(error ? { error: error.stack || String(error) } : {}) }; await writeFile(join(own, 'result.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify(result, null, 2));
}
if (error) process.exitCode = 1;
