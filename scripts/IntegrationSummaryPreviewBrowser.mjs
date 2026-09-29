import { tmpdir as testTmpdir } from 'node:os';
import { chromium } from 'playwright';
import { writeFile, readFile, mkdir, mkdtemp, realpath, access } from 'node:fs/promises';
import { createServer } from 'node:net';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { dirname, resolve, join, delimiter, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes, randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';

// Actual original SPA + Main APIs, synthetic identities and a fresh isolated H2 directory only.
// No business HTTP mocks, private documents, app/data, external requests or native confirmations.
// node scripts/IntegrationSummaryPreviewBrowser.mjs --java PATH --classes ISOLATED_COMPILED_SRC
const require = createRequire(import.meta.url);
const { workflowConfiguration } = require('./IntegrationWorkflowHttpFixture.cjs');
const app = resolve(dirname(fileURLToPath(import.meta.url)), '..'), args = {};
for (let i = 2; i < process.argv.length; i += 2) {
  assert.ok(['--java', '--classes'].includes(process.argv[i]) && process.argv[i + 1], 'Use --java PATH --classes ISOLATED_COMPILED_SRC');
  args[process.argv[i]] = process.argv[i + 1];
}
assert.ok(args['--java'] && args['--classes'], 'Explicit Java and compiled classes are required');
const classes = await realpath(args['--classes']);
assert.ok(classes !== app && !classes.startsWith(app + sep), 'Classes must be outside the repository');
await access(join(classes, 'com/training/Main.class'));
await access(join(classes, 'com/training/IntegrationSummaryComparisonHttpFixture.class'));
const own = await mkdtemp(join(testTmpdir(), 'yanxu-summary-comparison-http-browser-'));
let data;
const password = randomBytes(24).toString('hex'), tokens = {}, actors = ['admin', 'leader', 'bp', 'team', 'outsider', 'readonly'];
const pause = ms => new Promise(yes => setTimeout(yes, ms));
const username = actor => actor === 'admin' ? 'admin' : 'synthetic-summary-preview-' + actor;
let child, browser, base, checks = 0, error = null, log = '';
const screenshots = [], browserErrors = [], outsideRequests = [], apiFailures = [], expectedDenials = [];
function check(value, label) { assert.ok(value, label); checks++; }
function equal(actual, expected, label) { assert.deepEqual(actual, expected, label); checks++; }
async function api(route, body, actor = 'team') {
  const response = await fetch(base + '/api' + route, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json', ...(tokens[actor] ? { 'X-Token': tokens[actor] } : {}) }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000) });
  const envelope = await response.json();
  assert.equal(response.status, 200, `${route} HTTP ${response.status}: ${envelope.msg}`);
  assert.equal(envelope.code, 0, `${route}: ${envelope.msg}`); return envelope.data;
}
async function login(actor) { tokens[actor] = (await api('/login', { username: username(actor), password }, 'anonymous')).token; }
async function boot(width) {
  data = join(own, 'data-' + width); await mkdir(data);
  await writeFile(join(own, 'control-request.json'), JSON.stringify({ nonce: randomUUID(), operation: 'snapshot' }));
  for (const key of Object.keys(tokens)) delete tokens[key];
  const listener = createServer(); await new Promise((yes, no) => { listener.once('error', no); listener.listen(0, '127.0.0.1', yes); });
  const port = listener.address().port; await new Promise(yes => listener.close(yes)); base = `http://127.0.0.1:${port}`;
  child = spawn(args['--java'], ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password, '-Ddata.dir=' + data, '-Dintegration.summary.comparison.root=' + own, '-Dsemantic.port=', '-Dsemantic.token=', '-cp', classes + delimiter + join(app, 'lib', '*'), 'com.training.IntegrationSummaryComparisonHttpFixture', String(port)], { cwd: app, env: { ...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: '' }, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.on('data', value => { log = (log + value).slice(-24000); }); child.stderr.on('data', value => { log = (log + value).slice(-24000); });
  let launchError; child.once('error', e => { launchError = e; });
  for (let i = 0; i < 180; i++) {
    if (launchError) throw launchError; assert.equal(child.exitCode, null, 'Isolated Main exited before startup');
    try { await login('admin'); return; } catch {}
    await pause(100);
  }
  throw Error('Isolated Main login timeout');
}
async function stop() {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  await new Promise(yes => { const timer = setTimeout(() => child.kill('SIGKILL'), 2500); child.once('exit', () => { clearTimeout(timer); yes(); }); child.kill('SIGTERM'); });
}
async function acceptedProject(label) {
  let d = (await api('/demands/draft', { request_id: 'SUMMARY-BROWSER-DRAFT-' + label, title: 'SYNTHETIC SUMMARY ' + label, unit: 'SYNTHETIC CUSTOMER', business_path: 'direct', organization_code: '001', internal_contact_code: 'P1', category_text: '合成浏览器验收', delivery_mode_text: '线上', period_text: '全天', duration_minutes: '60', participant_count: '1', objectives: 'SYNTHETIC ONLY', content: 'SYNTHETIC ONLY' }, 'admin')).workflow;
  d = (await api('/demands/submit', { id: d.id, expected_version: d.version, request_id: 'SUMMARY-BROWSER-SUBMIT-' + label }, 'admin')).workflow;
  for (const actor of ['leader', 'bp']) d = (await api('/approvals/tasks/' + d.id + '/actions', { action: 'APPROVE', expectedVersion: d.approval.version, expectedStage: d.approval.stage, requestId: 'SUMMARY-BROWSER-APPROVE-' + label + '-' + actor, comment: 'SYNTHETIC' }, actor)).workflow;
  d = (await api('/demands/accept', { id: d.id, expected_version: d.version, request_id: 'SUMMARY-BROWSER-ACCEPT-' + label, team_code: '900' })).workflow;
  const project = (await api('/projects')).find(row => row.id === d.project_id);
  check(project.delivery_controlled === true && project.workflow_source?.demand_id === d.id, 'Summary fixture has a real accepted and controlled source chain');
  await api('/projects', { ...project, owner: 'SYNTHETIC OWNER', start_date: '2025-01-01', end_date: '2025-01-02', delivery_mode: '线上', hours: 8, amount: 0 });
  await api('/projects/start', { id: project.id }); return project.id;
}
const headline = 'SYNTHETIC 已保存培训总结';
const text = { achievements: 'SYNTHETIC 保存的成果\n保留第二行', issues: 'SYNTHETIC 问题与改进', nextSteps: 'SYNTHETIC 下一步计划', publicity: { title: headline, introduction: 'SYNTHETIC 项目引言\n  保留空白与顺序。\n' + '课程围绕实际场景展开，学员通过讨论整理方法。'.repeat(14), sections: [{ heading: '第一章：理论与练习', body: 'SYNTHETIC 第一章长正文\n' + 'LongUnbrokenSyntheticText'.repeat(80) }, { heading: '第二章：交流与复盘', body: 'SYNTHETIC 第二章正文\n' + '课堂交流记录。'.repeat(45) }], photoCaptions: ['SYNTHETIC 图注一：课堂授课', 'SYNTHETIC 图注二：交流研讨 <img src=x onerror=alert(1)>'] } };
const command = (project_id, expected_version) => ({ project_id, expected_version, request_id: 'PREVIEW-' + randomUUID() });
async function control(operation) {
  const nonce = randomUUID(); await writeFile(join(own, 'control-request.json'), JSON.stringify({ nonce, operation }));
  for (let i = 0; i < 100; i++) { try { const result = JSON.parse(await readFile(join(own, 'control-result.json'), 'utf8')); if (result.nonce === nonce) { assert.equal(result.ok, true); return; } } catch {} await pause(20); } throw Error('Synthetic control timeout');
}
async function setup() {
  const ids = [(await api('/me', undefined, 'admin')).uid];
  for (const actor of actors.slice(1)) ids.push(await api('/users', { username: username(actor), password, name: 'SYNTHETIC ' + actor.toUpperCase(), role: actor === 'team' ? 'manager' : 'viewer', status: 1 }, 'admin'));
  const configuration = workflowConfiguration(ids.slice(0, 5));
  for (const roleCode of ['TEAM', 'BP']) for (const [resource, action] of [['summary.read', 'VIEW'], ['summary.edit', 'HANDLE']]) configuration.grants.push({ ruleId: roleCode + '-' + resource, roleCode, resource, action, effect: 'ALLOW', scope: 'NAMED_ORGS', organizationCodes: ['001'] });
  configuration.roleCodes.push('READONLY'); const optional = { required: false, allowedTargetRoles: ['READONLY'], targetMustCoverOrganization: false, allowSelf: false };
  configuration.relations.push({ roleCode: 'READONLY', leader: optional, bp: optional }); configuration.people.push({ personCode: 'P6', organizationCode: '001', roleCodes: ['READONLY'], responsibleOrganizationCodes: [], leaderPersonCode: null, bpPersonCode: null, enabled: true }); configuration.accountBindings.push({ accountId: ids[5], personCode: 'P6', enabled: true });
  for (const resource of ['demand.read', 'summary.read', 'delivery.read']) configuration.grants.push({ ruleId: 'readonly-' + resource, roleCode: 'READONLY', resource, action: 'VIEW', effect: 'ALLOW', scope: 'OWN_ORG', organizationCodes: [] });
  await api('/organization/config', { expectedVersion: null, configuration }, 'admin'); for (const actor of actors) await login(actor);
  const project = await acceptedProject('COMPARISON PRIMARY'), other = await acceptedProject('COMPARISON SECOND');
  const primary = (await api('/projects')).find(row => row.id === project); await api('/projects', { ...primary, participant_count: 0 });
  const teacher = await api('/teachers', { name: 'SYNTHETIC PREVIEW TEACHER', status: '在库', teacher_level: '讲师', base_province: '浙江', base_city: '杭州', fee_rate: 100 }, 'admin');
  const dispatch = await api('/dispatches', { project_id: project, teacher_id: teacher, subject: 'SYNTHETIC PREVIEW COURSE', teach_date: '2025-01-01', hours: 2, status: '待发送', material_status: '已就绪' });
  await api('/dispatches/send', { id: dispatch }); await api('/dispatches/confirm', { id: dispatch, accept: 1 });
  let fact = await api('/delivery-settlement/save', { dispatch_id: dispatch, expected_version: 0, request_id: 'FIRST-FACT', actual_minutes: '60', estimated_hours: '0', planned_hours: null, payable_hours: '9007199254740993.12345678' });
  fact = await api('/delivery-settlement/verify', { dispatch_id: dispatch, expected_version: fact.version, request_id: 'FIRST-VERIFY', evidence_code: 'SYNTHETIC-PREVIEW' }); await api('/dispatches/complete', { id: dispatch, expected_version: fact.version });
  await api('/training-summaries/save', { ...command(project, 0), content: text }, 'bp');
  await api('/training-summaries/save', { ...command(other, 0), content: { achievements: 'SYNTHETIC SECOND PROJECT ONLY', issues: '', nextSteps: '' } });
  return { project, other, dispatch };
}
async function contextFor(actor, width) {
  const context = await browser.newContext({ viewport: { width, height: 1000 }, deviceScaleFactor: 1, reducedMotion: 'reduce' });
  await context.route('**/*', route => { const url = new URL(route.request().url()); if (url.protocol === 'data:' || url.origin === base) { if (url.pathname === '/api/visitor-context') return route.abort(); return route.continue(); } outsideRequests.push(url.origin); return route.abort(); });
  const response = await context.request.post(base + '/api/login', { data: { username: username(actor), password } }); equal(response.status(), 200, `${width}: actual cookie login ${actor}`); equal((await response.json()).code, 0, 'Real cookie session');
  const page = await context.newPage(); page.setDefaultTimeout(15000);
  page.on('pageerror', e => browserErrors.push({ width, actor, message: String(e) })); page.on('dialog', async dialog => { browserErrors.push({ width, actor, message: 'Unexpected native ' + dialog.type() }); await dialog.dismiss(); });
  page.on('response', response => { const path = new URL(response.url()).pathname; if (path.startsWith('/api/') && response.status() >= 400) ((path === '/api/survey-response-imports/config' && response.status() === 403) || (path.startsWith('/api/training-summaries') && [401, 403, 409].includes(response.status())) ? expectedDenials : apiFailures).push({ width, actor, path, status: response.status() }); });
  return { context, page };
}
async function noOverflow(page, label) {
  const size = await page.evaluate(() => ({ viewport: innerWidth, page: document.documentElement.scrollWidth, surfaces: [...document.querySelectorAll('.modal, #summary-revision, #summary-revision .table-wrap')].map(node => [node.clientWidth, node.scrollWidth]) }));
  check(size.page <= size.viewport + 1 && size.surfaces.every(([width, scroll]) => scroll <= width + 1), label + ': no overflow ' + JSON.stringify(size));
}
async function shot(page, name) { const file = join(own, name + '.png'); await page.screenshot({ path: file, fullPage: false, animations: 'disabled' }); screenshots.push(file); }
async function openEditor(page, project) { await page.goto(base + '/#/project_detail?project=' + project); await page.locator('[data-summary-open]').click(); await page.locator('[data-k="achievements"]').waitFor(); }
async function preview(page, number) { await page.locator('[data-summary-preview]').click(); await page.locator('article[data-summary-preview]').waitFor(); check((await page.locator('article[data-summary-preview]').textContent()).includes('第 ' + number + ' 版'), 'Requested saved preview version'); }
async function mutation(page, operation) { const response = page.waitForResponse(response => response.url().endsWith('/api/training-summaries/' + operation) && response.request().method() === 'POST'); await page.locator('[data-summary-' + operation + ']').click(); const result = await response; return { status: result.status(), envelope: await result.json() }; }
async function compareAction(page, row) { const button = row.getByRole('button', { name: '与上一版对照', exact: true }); if (await button.isVisible()) await button.click(); else { await row.locator('summary').click(); await page.getByRole('button', { name: '与上一版对照', exact: true }).click(); } }
async function comparison(page, number) {
  await page.locator('[data-summary-show-history]').click(); const row = page.locator(`#summary-history [data-row-id="${number}"]`); await row.waitFor();
  await compareAction(page, row); await page.locator('article[data-summary-comparison]').waitFor();
}
const readSummary = project => api('/training-summaries?project_id=' + project);
async function verify(fixture, width) {
  const { context, page } = await contextFor('team', width), reads = [], writes = [];
  page.on('request', request => { if (request.url().includes('/api/training-summaries/')) { if (request.method() === 'GET') reads.push(request); else writes.push(request); } });
  try {
    await openEditor(page, fixture.project); const input = await page.locator('[data-k="body1"]').elementHandle(), mask = await page.locator('#modal-mask').elementHandle();
    check((await page.locator('#summary-sources').textContent()).includes('尚未带入') && !(await page.locator('#summary-sources').textContent()).includes('当前账号无权'), width + ': historic frozen absence is distinct from current permission');
    await page.locator('[data-k="body1"]').fill('SYNTHETIC UNSAVED LOCAL BODY'); await preview(page, 1);
    check(await input.evaluate(node => node === document.querySelector('[data-k="body1"]')) && await mask.evaluate(node => node === document.querySelector('#modal-mask')), width + ': preview retains same form nodes and modal');
    check(!(await page.locator('article[data-summary-preview]').textContent()).includes('UNSAVED LOCAL BODY'), width + ': saved viewer excludes local draft');
    equal(await page.locator('[data-summary-sections] h4').allTextContents(), ['第一章：理论与练习', '第二章：交流与复盘'], width + ': chapter order retained'); equal(await page.locator('[data-summary-captions] li').count(), 2, width + ': text captions retained'); equal(await page.locator('article[data-summary-preview] img, article[data-summary-preview] script').count(), 0, width + ': markup remains inert');
    await noOverflow(page, 'saved preview ' + width); await shot(page, 'summary-preview-' + width);
    await page.locator('[data-summary-view-close]').click(); await page.locator('[data-summary-stay]').waitFor(); check(await page.locator('#modal-mask').count() === 1, width + ': viewer close uses inline dirty guard'); await page.locator('[data-summary-stay]').click(); await page.locator('[data-summary-return]').click(); equal(await page.locator('[data-k="body1"]').inputValue(), 'SYNTHETIC UNSAVED LOCAL BODY', width + ': return retains unsaved draft');
    let saved = await mutation(page, 'save'); equal(saved.status, 200, width + ': original draft save after preview'); await page.waitForFunction(() => document.querySelector('#summary-notice')?.textContent.includes('已保存第 2 版')); equal(saved.envelope.data.sources.delivery.status, 'UNAVAILABLE', width + ': ordinary save keeps frozen unavailable source');
    saved = await mutation(page, 'refresh'); equal(saved.status, 200, width + ': explicit source refresh'); await page.waitForFunction(() => document.querySelector('#summary-notice')?.textContent.includes('已保存第 3 版')); await comparison(page, 3);
    equal(saved.envelope.data.sources.delivery.value.estimated, '0', width + ': real source keeps genuine decimal zero'); equal(saved.envelope.data.sources.delivery.value.planned, null, width + ': real source keeps unknown planned hours distinct from zero');
    check((await page.locator('[data-summary-comparison]').textContent()).includes('9007199254740993.12345678') && (await page.locator('[data-summary-comparison]').textContent()).includes('1.33'), width + ': current authorized projection shows unavailable-to-available frozen change');
    await noOverflow(page, 'source comparison ' + width); await shot(page, 'summary-source-comparison-' + width); await page.locator('[data-summary-return]').click();
    let fact = await api('/delivery-settlement?dispatch_id=' + fixture.dispatch); fact = await api('/delivery-settlement/save', { dispatch_id: fixture.dispatch, expected_version: fact.version, request_id: 'CHANGE-' + width, actual_minutes: '90' }); fact = await api('/delivery-settlement/verify', { dispatch_id: fixture.dispatch, expected_version: fact.version, request_id: 'VERIFY-' + width, evidence_code: 'SYNTHETIC-CORRECTION' });
    const project = (await api('/projects')).find(project => project.id === fixture.project); await api('/projects', { ...project, participant_count: 7 }); saved = await mutation(page, 'refresh'); equal(saved.status, 200, width + ': refresh updated trusted source'); await page.waitForFunction(() => document.querySelector('#summary-notice')?.textContent.includes('已保存第 4 版'));
    await comparison(page, 4); const changes = await page.locator('[data-summary-comparison] tbody tr').evaluateAll(rows => rows.map(row => [...row.cells].map(cell => cell.textContent)));
    check(changes.some(row => row[0] === '已核对实际课时' && row[1] === '1.33' && row[2] === '2.00'), width + ': exact actual source comparison'); check(changes.some(row => row[0] === '项目人数' && row[1] === '0' && row[2] === '7'), width + ': genuine zero participants remain exact ' + JSON.stringify(changes)); await page.locator('[data-summary-return]').click();
    const current = await readSummary(fixture.project); await api('/training-summaries/save', { ...command(fixture.project, current.version), content: { ...current.current.content, issues: 'SYNTHETIC CONCURRENT ISSUE' } });
    await comparison(page, 3); check((await page.locator('[data-summary-comparison]').textContent()).includes('最新第 5 版'), width + ': atomic comparison reports latest metadata without replacing selection'); await page.locator('[data-summary-return]').click(); await page.locator('[data-k="issues"]').fill('SYNTHETIC LOCAL CONFLICT');
    equal((await mutation(page, 'save')).status, 409, width + ': viewing latest metadata does not silently change edit CAS'); await page.locator('[data-summary-keep-local]').waitFor(); equal(await page.locator('[data-k="issues"]').inputValue(), 'SYNTHETIC LOCAL CONFLICT', width + ': conflict retains local input'); await page.locator('[data-summary-keep-local]').click(); equal((await mutation(page, 'save')).status, 200, width + ': explicit keep-local resolution saves'); await page.waitForFunction(() => document.querySelector('#summary-notice')?.textContent.includes('已保存第 6 版'));
    await comparison(page, 1); check((await page.locator('[data-summary-comparison]').textContent()).includes('首次保存前（尚无已保存版本） → 第 1 版'), width + ': initial version has honest empty origin'); await page.locator('[data-summary-return]').click();
    let release, intercepted = false; const gate = new Promise(yes => { release = yes; }); await page.route('**/api/training-summaries/comparison?**', async route => { const response = await route.fetch(); intercepted = true; await gate; try { await route.fulfill({ response }); } catch {} });
    await page.locator('[data-summary-show-history]').click(); await compareAction(page, page.locator('#summary-history [data-row-id="1"]'));  for (let i = 0; !intercepted && i < 100; i++) await pause(20); check(intercepted, width + ': delayed pair is fetched from real server');
    await page.locator('[data-summary-return]').click(); await page.locator('[data-summary-close]').click(); await page.goto(base + '/#/project_detail?project=' + fixture.other); await page.locator('[data-summary-preview-open]').click(); await page.locator('article[data-summary-preview]').waitFor(); release(); await pause(100);
    check((await page.locator('#summary-revision').textContent()).includes('SECOND PROJECT ONLY') && !(await page.locator('#summary-revision').textContent()).includes(headline), width + ': delayed old-project pair cannot replace new-project saved preview'); await page.unroute('**/api/training-summaries/comparison?**');
    await page.locator('[data-summary-return]').click(); equal((await context.request.post(base + '/api/logout', { data: {} })).status(), 200, width + ': actual session logout succeeds'); await page.locator('[data-summary-preview]').click(); await page.waitForFunction(() => !document.querySelector('#modal-mask')); equal(await page.locator('#summary-revision').count(), 0, width + ': real expired cookie clears preview and private form');
    check(reads.every(request => request.method() === 'GET') && writes.every(request => !request.url().includes('/comparison') && !request.url().includes('/revision')), width + ': readers remain read only');
  } finally { await context.close(); }
}
async function verifyHiddenAndArchived(fixture, width) {
  for (const actor of ['bp', 'readonly']) {
    if (actor === 'readonly') await control('archive'); const { context, page } = await contextFor(actor, width);
    try {
      await openEditor(page, fixture.project); await comparison(page, 4);
      if (actor === 'bp') { check((await page.locator('[data-summary-comparison]').textContent()).includes('两侧均按当前权限隐藏') && !(await page.locator('[data-summary-comparison]').textContent()).includes('已核对实际课时'), width + ': no-delivery reader sees neither side values'); await noOverflow(page, 'hidden pair ' + width); await shot(page, 'summary-hidden-comparison-' + width); }
      else { check((await page.locator('[data-summary-comparison]').textContent()).includes('2.00'), width + ': archived readonly account retains authorized frozen comparison'); equal(await page.locator('[data-summary-save]').count(), 0, width + ': archived viewer has no write action'); await page.locator('[data-summary-return]').click(); await preview(page, 6); check((await page.locator('article[data-summary-preview]').textContent()).includes('草稿'), width + ': archive never upgrades draft to approved'); }
    } finally { await context.close(); }
  }
}
try {
  browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined, headless: true });
  for (const width of [1280, 390, 320]) { await boot(width); const fixture = await setup(); await verify(fixture, width); await verifyHiddenAndArchived(fixture, width); await stop(); }
  equal(browserErrors, [], 'No browser errors or native dialogs'); equal(outsideRequests, [], 'No external browser requests'); equal(apiFailures, [], 'No unexpected API failure');
} catch (caught) { error = caught; }
finally {
  if (browser) await browser.close(); await stop(); await writeFile(join(own, 'server.log'), log.replaceAll(password, '[synthetic password redacted]'), { mode: 0o600 });
  const result = { ok: !error, checks, classes, temporaryDirectory: own, screenshots, browserErrors, outsideRequests, apiFailures, expectedDenials, temporaryServiceStopped: !child || child.exitCode !== null || child.signalCode !== null, ...(error ? { error: error.stack || String(error) } : {}) }; await writeFile(join(own, 'result.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify(result, null, 2));
}
if (error) process.exitCode = 1;
