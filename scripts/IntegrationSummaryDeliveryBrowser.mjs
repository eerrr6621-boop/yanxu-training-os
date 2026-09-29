import { tmpdir as testTmpdir } from 'node:os';
import { chromium } from 'playwright';
import { writeFile, mkdir, mkdtemp, realpath, access } from 'node:fs/promises';
import { createServer } from 'node:net';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { dirname, resolve, join, delimiter, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes } from 'node:crypto';
import assert from 'node:assert/strict';

// Actual original SPA + Main APIs, synthetic identities and a fresh isolated H2 directory only.
// No business HTTP mocks, private documents, app/data, external requests or native confirmations.
// node scripts/IntegrationSummaryDeliveryBrowser.mjs --java PATH --classes ISOLATED_COMPILED_SRC
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
const own = await mkdtemp(join(testTmpdir(), 'yanxu-summary-delivery-browser-')), data = join(own, 'data');
await mkdir(data);
const password = randomBytes(24).toString('hex'), tokens = {}, actors = ['admin', 'leader', 'bp', 'team', 'outsider'];
const pause = ms => new Promise(yes => setTimeout(yes, ms));
const username = actor => actor === 'admin' ? 'admin' : 'synthetic-summary-browser-' + actor;
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
async function boot() {
  const listener = createServer(); await new Promise((yes, no) => { listener.once('error', no); listener.listen(0, '127.0.0.1', yes); });
  const port = listener.address().port; await new Promise(yes => listener.close(yes)); base = `http://127.0.0.1:${port}`;
  child = spawn(args['--java'], ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password, '-Ddata.dir=' + data, '-Dsemantic.port=', '-Dsemantic.token=', '-cp', classes + delimiter + join(app, 'lib', '*'), 'com.training.Main', String(port)], { cwd: app, env: { ...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: '' }, stdio: ['ignore', 'pipe', 'pipe'] });
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
const factCommand = (dispatch_id, expected_version, request_id) => ({ dispatch_id, expected_version, request_id });
async function setup() {
  equal(await api('/organization/config', undefined, 'admin'), { version: null, configuration: null }, 'Fresh DB has no implicit organization or authorization seed');
  const ids = [(await api('/me', undefined, 'admin')).uid];
  for (const actor of actors.slice(1)) ids.push(await api('/users', { username: username(actor), password, name: 'SYNTHETIC ' + actor.toUpperCase(), role: actor === 'team' ? 'manager' : 'viewer', status: 1 }, 'admin'));
  const configuration = workflowConfiguration(ids); configuration.version = 'SYNTHETIC-SUMMARY-BROWSER-V1';
  // TEAM can read delivery; BP can write the same summary but cannot read delivery.
  for (const roleCode of ['TEAM', 'BP']) for (const [resource, action] of [['summary.read', 'VIEW'], ['summary.edit', 'HANDLE']]) configuration.grants.push({ ruleId: roleCode + '-' + resource, roleCode, resource, action, effect: 'ALLOW', scope: 'NAMED_ORGS', organizationCodes: ['001'] });
  await api('/organization/config', { expectedVersion: null, configuration }, 'admin'); for (const actor of actors) await login(actor);
  const teacher = await api('/teachers', { name: 'SYNTHETIC SUMMARY TEACHER', status: '在库', teacher_level: '讲师', base_province: '浙江', base_city: '杭州', fee_rate: 99999 }, 'admin');
  const projects = [];
  for (const width of [1280, 390]) {
    const project = await acceptedProject(String(width)), dispatches = [];
    for (const [index, label] of ['COMPLETED', 'PENDING'].entries()) {
      const id = await api('/dispatches', { project_id: project, teacher_id: teacher, subject: 'SYNTHETIC SUMMARY ' + label, teach_date: '2025-01-01', hours: 9, status: '待发送', material_status: '已就绪' });
      await api('/dispatches/send', { id }); await api('/dispatches/confirm', { id, accept: 1 });
      let fact = await api('/delivery-settlement/save', { ...factCommand(id, 0, 'SUMMARY-BROWSER-FACT-' + width + '-' + index), estimated_hours: index ? '4' : '2', planned_hours: index ? '5' : '3', actual_minutes: index ? null : '60', payable_hours: null });
      if (!index) { fact = await api('/delivery-settlement/verify', { ...factCommand(id, fact.version, 'SUMMARY-BROWSER-VERIFY-' + width), evidence_code: 'SYNTHETIC-SUMMARY-TEACHING' }); await api('/dispatches/complete', { id, expected_version: fact.version }); }
      dispatches.push(id);
    }
    const fresh = await api('/training-summaries?project_id=' + project);
    check(fresh.version === 0 && fresh.current === null && fresh.sources.delivery.status === 'AVAILABLE', 'Real summary API exposes current M05 source without saving a draft');
    const values = fresh.sources.delivery.value;
    equal([values.estimated, values.planned, values.actual, values.payable], ['6', '8', '1.33', null], 'Real source sums planned/estimated separately and keeps unknown payable');
    equal([values.dispatch_count, values.reviewed_completed_count, values.pending_count, values.unverified_completed_count], [2, 1, 1, 0], 'Source coverage distinguishes pending and reviewed completed dispatches');
    projects.push({ project, dispatches });
  }
  return { projects, emptyProject: await acceptedProject('EMPTY') };
}
async function contextFor(actor, width) {
  const context = await browser.newContext({ viewport: { width, height: 1000 }, deviceScaleFactor: 1, reducedMotion: 'reduce' });
  await context.route('**/*', route => {
    const url = new URL(route.request().url());
    if (url.protocol === 'data:' || url.origin === base) {
      if (url.pathname === '/api/visitor-context') return route.abort(); // prevent unrelated server IP/weather lookup
      return route.continue();
    }
    outsideRequests.push(url.origin); return route.abort();
  });
  const login = await context.request.post(base + '/api/login', { data: { username: username(actor), password } });
  equal(login.status(), 200, `Real cookie authentication ${actor}/${width}`); equal((await login.json()).code, 0, 'Cookie session established');
  const page = await context.newPage(); page.setDefaultTimeout(15000);
  page.on('pageerror', e => browserErrors.push({ width, actor, message: String(e) }));
  page.on('dialog', async dialog => { browserErrors.push({ width, actor, message: 'Unexpected native dialog: ' + dialog.type() }); await dialog.dismiss(); });
  page.on('response', response => {
    const path = new URL(response.url()).pathname;
    if (!path.startsWith('/api/') || response.status() < 400) return;
    // The unrelated preview module may explicitly deny a synthetic actor without its own grant.
    (path === '/api/survey-response-imports/config' && response.status() === 403 ? expectedDenials : apiFailures).push({ width, actor, path, status: response.status() });
  });
  return { context, page };
}
async function noOverflow(page, label) {
  const sizes = await page.evaluate(() => ({ width: innerWidth, page: document.documentElement.scrollWidth, surfaces: [...document.querySelectorAll('.modal, #summary-sources, [data-summary-delivery] .table-wrap')].map(el => [el.clientWidth, el.scrollWidth]) }));
  check(sizes.page <= sizes.width + 1, label + ': original page has no horizontal overflow ' + JSON.stringify(sizes));
  check(sizes.surfaces.every(([a, b]) => b <= a + 1), label + ': original summary/table has no horizontal overflow ' + JSON.stringify(sizes));
}
async function screenshot(page, name) { const file = join(own, name + '.png'); await page.screenshot({ path: file, fullPage: false, animations: 'disabled' }); screenshots.push(file); }
const rowValues = (page, scope = '#summary-sources') => page.locator(scope + ' [data-summary-delivery] tbody tr').evaluateAll(rows => rows.map(row => [...row.cells].map(cell => cell.textContent.trim())));
async function openSummary(page, project) {
  await page.goto(base + '/#/project_detail?project=' + project); await page.locator('[data-summary-open]').click();
  await page.locator('[data-k="achievements"]').waitFor(); equal(await page.locator('#modal-title').textContent(), '培训总结草稿', 'Summary opens in original project modal');
}
async function mutateSummary(page, operation) {
  const response = page.waitForResponse(r => r.url().endsWith('/api/training-summaries/' + operation) && r.request().method() === 'POST');
  await page.locator('[data-summary-' + operation + ']').click(); const envelope = await (await response).json();
  equal(envelope.code, 0, 'Real original summary ' + operation + ' succeeded');
  await page.waitForFunction(version => document.querySelector('#summary-notice')?.textContent.includes('已保存第 ' + version + ' 版'), envelope.data.version);
  return envelope.data;
}
async function inspectHistory(page, version) {
  await page.locator('[data-summary-show-history]').click(); const row = page.locator(`#summary-history [data-row-id="${version}"]`); await row.waitFor();
  await row.getByRole('button', { name: '查看版本', exact: true }).click(); await page.locator('#summary-revision [data-summary-delivery]').waitFor();
}
async function verifySources(fixture, width) {
  const { context, page } = await contextFor('team', width), writes = [];
  page.on('request', request => { if (request.method() === 'POST' && request.url().includes('/api/training-summaries/')) writes.push({ path: new URL(request.url()).pathname, body: request.postDataJSON() }); });
  try {
    await openSummary(page, fixture.project); const mask = await page.locator('#modal-mask').elementHandle();
    equal(await rowValues(page), [['预计', '6', '有效排课'], ['计划', '8', '有效排课'], ['实际', '1.33', '已完成且核对的授课'], ['计酬', '待补齐', '已完成且核对的授课']], width + ': real original editor displays four separate trusted quantities');
    check((await page.locator('#summary-sources').textContent()).includes('首次保存时冻结'), width + ': unsaved source is labelled as a preview');
    await page.locator('#summary-sources').scrollIntoViewIfNeeded(); await noOverflow(page, 'summary source ' + width); await screenshot(page, 'summary-delivery-' + width);
    await page.locator('[data-k="achievements"]').fill('SYNTHETIC 原页面成果 ' + width); const first = await mutateSummary(page, 'save');
    equal(first.version, 1, width + ': first actual save creates revision one');
    check(await mask.evaluate(node => node === document.querySelector('#modal-mask')), width + ': save retains original modal');
    let fact = await api('/delivery-settlement?dispatch_id=' + fixture.dispatches[1]);
    fact = await api('/delivery-settlement/save', { ...factCommand(fixture.dispatches[1], fact.version, 'SUMMARY-BROWSER-CHANGED-' + width), actual_minutes: '60', payable_hours: '1.25' });
    fact = await api('/delivery-settlement/verify', { ...factCommand(fixture.dispatches[1], fact.version, 'SUMMARY-BROWSER-CHANGED-VERIFY-' + width), evidence_code: 'SYNTHETIC-SECOND-CLASS' });
    await api('/dispatches/complete', { id: fixture.dispatches[1], expected_version: fact.version });
    const changed = await api('/training-summaries?project_id=' + fixture.project);
    check(changed.source_changed === true && changed.sources.delivery.value.actual === '1.33', width + ': current read detects live source change but still returns frozen draft facts');
    await page.locator('[data-k="issues"]').fill('SYNTHETIC 正文保存保留冻结课时'); const second = await mutateSummary(page, 'save');
    equal(second.sources.delivery.value.actual, '1.33', width + ': ordinary save retains original actual');
    equal((await rowValues(page))[2][1], '1.33', width + ': original form keeps frozen actual after save');
    equal(writes.filter(w => w.path.endsWith('/refresh')).length, 0, width + ': ordinary save sends no source refresh');
    const third = await mutateSummary(page, 'refresh');
    equal([third.sources.delivery.value.actual, third.sources.delivery.value.payable], ['2.66', null], width + ': explicit refresh includes second completed record but propagates unknown payable');
    equal((await rowValues(page))[2][1], '2.66', width + ': refresh updates displayed trusted actual');
    equal(await page.locator('[data-k="issues"]').inputValue(), 'SYNTHETIC 正文保存保留冻结课时', width + ': source refresh preserves saved body');
    check(await mask.evaluate(node => node === document.querySelector('#modal-mask')), width + ': refresh preserves original modal');
    check(writes.every(w => !('sources' in w.body) && !('actual' in w.body)), width + ': browser never submits client source facts');
    await inspectHistory(page, 1); equal((await rowValues(page, '#summary-revision'))[2][1], '1.33', width + ': historical first revision remains frozen after refresh');
    await page.locator('#summary-revision').scrollIntoViewIfNeeded(); await noOverflow(page, 'summary history ' + width); await screenshot(page, 'summary-delivery-history-' + width);
    await page.locator('[data-summary-close]').click(); equal(await page.locator('#modal-mask').count(), 0, width + ': cancel closes saved original editor');
  } finally { await context.close(); }
}
async function verifyHidden(project, width) {
  const { context, page } = await contextFor('bp', width);
  try {
    const hidden = await api('/training-summaries?project_id=' + project, undefined, 'bp');
    equal(hidden.sources.delivery, { status: 'UNAVAILABLE', reason: 'DELIVERY_READ_REQUIRED', value: null }, width + ': API removes delivery source for summary-only editor');
    await openSummary(page, project);
    check((await page.locator('#summary-sources [data-summary-delivery]').textContent()).includes('当前账号无权查看授课来源'), width + ': original editor distinguishes permission hiding from missing delivery');
    equal(await page.locator('#summary-sources [data-summary-delivery] table').count(), 0, width + ': hidden facts have no table');
    check(!(await page.locator('#summary-sources').textContent()).includes('2.66'), width + ': hidden snapshot leaks no current actual');
    await page.locator('#summary-sources').scrollIntoViewIfNeeded(); await noOverflow(page, 'hidden delivery ' + width); await screenshot(page, 'summary-delivery-hidden-' + width);
    await page.locator('[data-k="nextSteps"]').fill('SYNTHETIC 无授课权限仍可保存总结'); await mutateSummary(page, 'save');
    const visible = await api('/training-summaries?project_id=' + project);
    equal(visible.sources.delivery.value.actual, '2.66', width + ': summary-only save does not replace frozen source with redacted view');
    await inspectHistory(page, 1); check((await page.locator('#summary-revision').textContent()).includes('当前账号无权查看授课来源') && !(await page.locator('#summary-revision').textContent()).includes('1.33'), width + ': historical delivery uses current caller visibility');
    await page.locator('[data-summary-close]').click();
  } finally { await context.close(); }
}
async function verifyEmpty(project, width) {
  const { context, page } = await contextFor('team', width);
  try {
    await openSummary(page, project); equal((await rowValues(page)).map(row => row[1]), ['0', '0', '0', '0'], width + ': genuine available no-dispatch source displays zero instead of unknown');
    await page.locator('#summary-sources').scrollIntoViewIfNeeded(); await noOverflow(page, 'empty delivery ' + width); await screenshot(page, 'summary-delivery-empty-' + width);
    await page.locator('[data-summary-close]').click();
  } finally { await context.close(); }
}
try {
  await boot(); const fixture = await setup(); browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined, headless: true });
  for (const [index, width] of [1280, 390].entries()) { await verifySources(fixture.projects[index], width); await verifyHidden(fixture.projects[index].project, width); await verifyEmpty(fixture.emptyProject, width); }
  equal(browserErrors, [], 'No runtime errors or native dialogs'); equal(outsideRequests, [], 'No external browser request'); equal(apiFailures, [], 'No unexpected business API failure');
} catch (caught) { error = caught; }
finally {
  if (browser) await browser.close(); await stop();
  await writeFile(join(own, 'server.log'), log.replaceAll(password, '[redacted synthetic password]'), { mode: 0o600 });
  const result = { ok: !error, checks, app, classes, temporaryDirectory: own, syntheticDatabase: data, screenshots, browserErrors, outsideRequests, apiFailures, expectedDenials, ...(error ? { error: error.stack || String(error) } : {}) };
  await writeFile(join(own, 'result.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify(result, null, 2));
}
if (error) process.exitCode = 1;
