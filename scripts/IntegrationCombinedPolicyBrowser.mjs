import { tmpdir as testTmpdir } from 'node:os';
import { chromium } from 'playwright';
import { readFile, writeFile, mkdir, mkdtemp, realpath, access } from 'node:fs/promises';
import { createServer } from 'node:net';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { dirname, resolve, join, delimiter, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes } from 'node:crypto';
import assert from 'node:assert/strict';

// Real Main + original SPA, no extracted HTML or business-route mocks. All database data is synthetic.
// Example: node scripts/IntegrationCombinedPolicyBrowser.mjs --java PATH --classes /private/tmp/isolated-classes
// --phase policy may exercise M05 with an older sequential-workflow build; full is the required final check.
const require = createRequire(import.meta.url);
const { workflowConfiguration } = require('./IntegrationWorkflowHttpFixture.cjs');
const app = resolve(dirname(fileURLToPath(import.meta.url)), '..'), args = {};
for (let i = 2; i < process.argv.length; i += 2) {
  assert.ok(['--java', '--classes', '--phase'].includes(process.argv[i]) && process.argv[i + 1], 'Use --java PATH --classes ISOLATED_DIRECTORY [--phase full|policy]');
  args[process.argv[i]] = process.argv[i + 1];
}
assert.ok(args['--java'] && args['--classes'], 'Explicit Java and isolated classes are required');
const classes = await realpath(args['--classes']), phase = args['--phase'] || 'full';
assert.ok(['full', 'policy'].includes(phase), 'Unknown phase');
assert.ok(classes !== app && !classes.startsWith(app + sep), 'Classes must be outside repository');
await access(join(classes, 'com/training/Main.class'));
const own = await mkdtemp(join(testTmpdir(), 'yanxu-combined-policy-browser-')), data = join(own, 'data');
await mkdir(data);
const password = randomBytes(24).toString('hex'), tokens = {}, combinedLabel = '同时完成负责人和BP审批';
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
let child, browser, base, checks = 0, error = null, log = '';
const screenshots = [], browserErrors = [], outsideRequests = [], apiFailures = [];
function check(value, label) { assert.ok(value, label); checks++; }
function equal(actual, expected, label) { assert.deepEqual(actual, expected, label); checks++; }
async function api(route, body, actor = 'admin') {
  const response = await fetch(base + '/api' + route, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json', ...(tokens[actor] ? { 'X-Token': tokens[actor] } : {}) }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000) });
  const envelope = await response.json();
  assert.equal(response.status, 200, `${route} HTTP ${response.status}: ${envelope.msg}`);
  assert.equal(envelope.code, 0, `${route}: ${envelope.msg}`);
  return envelope.data;
}
const username = actor => actor === 'admin' ? 'admin' : 'synthetic-combined-' + actor;
async function login(actor) { tokens[actor] = (await api('/login', { username: username(actor), password }, 'anonymous')).token; }
async function boot() {
  const listener = createServer(); await new Promise((yes, no) => { listener.once('error', no); listener.listen(0, '127.0.0.1', yes); });
  const port = listener.address().port; await new Promise(yes => listener.close(yes)); base = `http://127.0.0.1:${port}`;
  child = spawn(args['--java'], ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password, '-Ddata.dir=' + data, '-Dsemantic.port=', '-Dsemantic.token=', '-cp', classes + delimiter + join(app, 'lib', '*'), 'com.training.Main', String(port)], { cwd: app, env: { ...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: '' }, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.on('data', value => { log = (log + value).slice(-24000); }); child.stderr.on('data', value => { log = (log + value).slice(-24000); });
  let launchError; child.once('error', e => { launchError = e; });
  for (let i = 0; i < 180; i++) {
    if (launchError) throw launchError;
    assert.equal(child.exitCode, null, 'Isolated Main exited before startup');
    try { await login('admin'); return; } catch {}
    await pause(100);
  }
  throw Error('Isolated Main login timeout');
}
async function stop() {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  await new Promise(resolve => { const timer = setTimeout(() => child.kill('SIGKILL'), 2500); child.once('exit', () => { clearTimeout(timer); resolve(); }); child.kill('SIGTERM'); });
}
async function newDemand(suffix) {
  let d = (await api('/demands/draft', { request_id: 'BROWSER-DRAFT-' + suffix, title: 'SYNTHETIC ' + suffix, unit: 'SYNTHETIC CUSTOMER', business_path: 'direct', organization_code: '001', internal_contact_code: 'P1', category_text: '合成浏览器检查', delivery_mode_text: '线上', period_text: '全天', duration_minutes: '60', participant_count: '1', objectives: 'SYNTHETIC ONLY', content: 'SYNTHETIC ONLY' })).workflow;
  d = (await api('/demands/submit', { id: d.id, expected_version: d.version, request_id: 'BROWSER-SUBMIT-' + suffix })).workflow;
  return d;
}
async function approve(d, suffix) {
  if (phase === 'full') return (await api('/approvals/tasks/' + d.id + '/actions', { action: 'APPROVE_COMBINED', expectedVersion: d.approval.version, expectedStage: d.approval.stage, requestId: 'BROWSER-APPROVE-' + suffix, comment: 'SYNTHETIC BOTH DUTIES' }, 'leader')).workflow;
  for (const actor of ['leader', 'bp']) d = (await api('/approvals/tasks/' + d.id + '/actions', { action: 'APPROVE', expectedVersion: d.approval.version, expectedStage: d.approval.stage, requestId: 'BROWSER-APPROVE-' + suffix + '-' + actor, comment: 'SYNTHETIC' }, actor)).workflow;
  return d;
}
async function setup() {
  equal(await api('/organization/config'), { version: null, configuration: null }, 'Fresh temporary DB has no organization/authority seed');
  const ids = [(await api('/me')).uid];
  for (const [actor, role] of [['leader', 'viewer'], ['bp', 'viewer'], ['team', 'manager'], ['outsider', 'viewer']]) ids.push(await api('/users', { username: username(actor), password, name: 'SYNTHETIC ' + actor.toUpperCase(), role, status: 1 }));
  const configuration = workflowConfiguration(ids); configuration.version = 'SYNTHETIC-COMBINED-BROWSER-V1';
  if (phase === 'full') {
    configuration.people[0].bpPersonCode = 'P2';
    configuration.people[1].roleCodes = ['LEADER', 'BP'];
    configuration.people[1].responsibleOrganizationsByRole = { LEADER: ['001'], BP: ['001'] };
    configuration.combinedApprovals = [{ organizationCode: '001', personCode: 'P2', leaderRoleCode: 'LEADER', bpRoleCode: 'BP', evidenceRef: 'SYNTHETIC-EXPLICIT-DUAL-DUTY-20260923' }];
  }
  await api('/organization/config', { expectedVersion: null, configuration });
  for (const actor of ['admin', 'leader', 'bp', 'team', 'outsider']) await login(actor);
  const demands = phase === 'full' ? [await newDemand('DESKTOP COMBINED'), await newDemand('MOBILE COMBINED')] : [];
  for (const d of demands) {
    const view = (await api('/demands/workflow?id=' + d.id, undefined, 'leader')).workflow;
    check(view.approval.reviewMode === 'SINGLE_EXPLICIT' && view.approval.actions.some(a => a.action === 'APPROVE_COMBINED' && a.enabled === true), 'Real API grants explicitly verified combined action');
  }
  let deliveryDemand = await approve(await newDemand('DELIVERY PROJECT'), 'DELIVERY');
  deliveryDemand = (await api('/demands/accept', { id: deliveryDemand.id, expected_version: deliveryDemand.version, request_id: 'BROWSER-ACCEPT-DELIVERY', team_code: '900' }, 'team')).workflow;
  const project = (await api('/projects', undefined, 'team')).find(p => p.id === deliveryDemand.project_id);
  check(project.delivery_controlled === true && project.workflow_source?.demand_id === deliveryDemand.id, 'Delivery fixture enters original project through actual acceptance');
  await api('/projects', { ...project, owner: 'SYNTHETIC OWNER', start_date: '2025-01-01', end_date: '2025-01-02', delivery_mode: '线上', hours: 4, amount: 0 }, 'team');
  await api('/projects/start', { id: project.id }, 'team');
  const teacher = await api('/teachers', { name: 'SYNTHETIC BROWSER TEACHER', status: '在库', teacher_level: '讲师', base_province: '浙江', base_city: '杭州', fee_rate: 99999 });
  const dispatches = [];
  for (const label of ['DESKTOP', 'MOBILE', 'EMPTY']) {
    const id = await api('/dispatches', { project_id: project.id, teacher_id: teacher, subject: 'SYNTHETIC ' + label + ' CLASS', teach_date: '2025-01-01', hours: 1.33, status: '待发送', material_status: '已就绪' }, 'team');
    await api('/dispatches/send', { id }, 'team'); await api('/dispatches/confirm', { id, accept: 1 }, 'team'); dispatches.push(id);
  }
  return { demands, project: project.id, dispatches };
}
async function contextFor(actor, width) {
  const context = await browser.newContext({ viewport: { width, height: 1000 }, deviceScaleFactor: 1, reducedMotion: 'reduce' });
  await context.route('**/*', route => {
    const u = new URL(route.request().url());
    if (u.protocol === 'data:' || u.origin === base) {
      // This task does not need IP/weather lookup; prevent a server-side external provider request.
      if (u.pathname === '/api/visitor-context') return route.abort();
      return route.continue();
    }
    outsideRequests.push(u.origin); return route.abort();
  });
  const response = await context.request.post(base + '/api/login', { data: { username: username(actor), password } });
  equal(response.status(), 200, `Real cookie login ${actor}/${width}`);
  const envelope = await response.json(); equal(envelope.code, 0, 'Session established by real API');
  const page = await context.newPage();
  page.on('pageerror', e => browserErrors.push({ width, actor, message: String(e) }));
  page.on('dialog', async dialog => { browserErrors.push({ width, actor, message: 'Unexpected native dialog: ' + dialog.type() }); await dialog.dismiss(); });
  page.on('response', response => { if (response.url().includes('/api/') && response.status() >= 400) apiFailures.push({ width, actor, path: new URL(response.url()).pathname, status: response.status() }); });
  page.setDefaultTimeout(15000);
  return { context, page };
}
async function screenshot(page, name) {
  const file = join(own, name + '.png'); await page.screenshot({ path: file, fullPage: false, animations: 'disabled' }); screenshots.push(file);
}
async function noOverflow(page, label) {
  const dimensions = await page.evaluate(() => ({ width: innerWidth, page: document.documentElement.scrollWidth, modal: [...document.querySelectorAll('.modal')].map(e => [e.clientWidth, e.scrollWidth]), policy: [...document.querySelectorAll('[data-m05-policy-preview]')].map(e => [e.clientWidth, e.scrollWidth]) }));
  check(dimensions.page <= dimensions.width + 1, label + ': no page horizontal overflow ' + JSON.stringify(dimensions));
  check(dimensions.modal.every(([a, b]) => b <= a + 1), label + ': no modal horizontal overflow');
  check(dimensions.policy.every(([a, b]) => b <= a + 1), label + ': no policy panel horizontal overflow');
}
async function clickRowAction(page, id, label) {
  const row = page.locator(`[data-row-id="${id}"]`);
  await row.waitFor();
  const action = row.getByRole('button', { name: label, exact: true });
  if (await action.isVisible()) return action.click();
  await row.locator('summary').click();
  await page.getByRole('button', { name: label, exact: true }).click();
}
async function verifyCombined(fixture, width, index) {
  const { context, page } = await contextFor('leader', width), d = fixture.demands[index];
  const commands = []; page.on('request', request => { if (request.url().endsWith('/actions') && request.method() === 'POST') commands.push(request.postDataJSON()); });
  try {
    await page.goto(base + '/#/dashboard');
    const task = page.locator(`#priority-list [data-focus-id="${d.id}"]`); await task.waitFor();
    equal(await task.count(), 1, `${width}: original today task contains one combined obligation`);
    check((await task.textContent()).includes(combinedLabel), `${width}: today task explicitly names both duties`);
    await noOverflow(page, 'combined dashboard ' + width);
    await task.click(); await page.locator(`#tbl [data-row-id="${d.id}"]`).waitFor();
    await clickRowAction(page, d.id, combinedLabel); await page.locator('#modal-mask').waitFor();
    equal(await page.locator('#modal-title').textContent(), combinedLabel, `${width}: original demand confirmation title is explicit`);
    equal((await page.locator('#modal-ok').textContent()).trim(), combinedLabel, `${width}: original confirmation names combined action`);
    check((await page.locator('.modal-body').textContent()).includes('同一办理人一次确认负责人和BP'), `${width}: confirmation explains one actor and both responsibilities`);
    await noOverflow(page, 'combined confirmation ' + width);
    await screenshot(page, 'combined-confirmation-' + width);
    await page.locator('[data-k="comment"]').fill('SYNTHETIC BROWSER APPROVED BOTH');
    await page.locator('#modal-ok').click(); await page.locator('#modal-mask').waitFor({ state: 'detached' });
    equal(commands.length, 1, `${width}: one explicit approval writes one request`);
    equal(commands[0].action, 'APPROVE_COMBINED', `${width}: real host sends combined action without ordinary approval`);
    const after = (await api('/demands/workflow?id=' + d.id, undefined, 'leader')).workflow;
    equal(after.approval.status, 'READY_FOR_TEAM', `${width}: real workflow is ready for separate team acceptance`);
    equal(after.approval.reviewMode, 'SINGLE_EXPLICIT', `${width}: persisted mode remains explicit`);
    const events = after.approval.history.filter(e => e.action === 'APPROVE_COMBINED');
    equal(events.length, 1, `${width}: server freezes one combined event`);
    equal(events[0].actorId, 'P2', `${width}: event has single true actor`);
    equal(events[0].responsibilities, ['LEADER', 'BP'], `${width}: event records both responsibilities`);
    check(!after.approval.history.some(e => e.action === 'APPROVE'), `${width}: no synthetic second approval event`);
    await clickRowAction(page, d.id, '详情'); await page.locator('.table-approval-history').waitFor();
    const combinedRows = page.locator('.table-approval-history tbody tr').filter({ hasText: combinedLabel });
    equal(await combinedRows.count(), 1, `${width}: actual history table renders one combined row`);
    check((await combinedRows.textContent()).includes('负责人、BP'), `${width}: history row displays two duties`);
    const actors = combinedRows.locator('td[data-label="办理人"]'); equal(await actors.count(), 1, `${width}: history has one actor cell`);
    check((await actors.textContent()).includes('P2') || (await actors.textContent()).includes('SYNTHETIC LEADER'), `${width}: history displays real combined approver`);
    await combinedRows.scrollIntoViewIfNeeded(); await noOverflow(page, 'combined history ' + width); await screenshot(page, 'combined-history-' + width);
    await page.locator('#modal-x').click(); await page.goto(base + '/#/dashboard'); await page.locator('#priority-list').waitFor();
    equal(await page.locator(`#priority-list [data-focus-id="${d.id}"]`).count(), 0, `${width}: no second BP todo after one approval`);
    const tasks = (await api('/approvals/tasks', undefined, 'leader')).tasks;
    check(!tasks.some(t => t.businessId === d.id && t.actions.some(a => a.enabled && ['APPROVE', 'APPROVE_COMBINED'].includes(a.action))), `${width}: real task API exposes no additional approval for same actor`);
  } finally { await context.close(); }
}
async function verifyPolicy(fixture, width, index) {
  const { context, page } = await contextFor('team', width), id = fixture.dispatches[index];
  const reads = [], writes = []; page.on('request', r => { if (r.url().includes('/delivery-settlement/policy-preview?')) reads.push(r); if (r.url().endsWith('/delivery-settlement/save')) writes.push(r.postDataJSON()); });
  try {
    await page.goto(base + '/#/dispatches?project=' + fixture.project); await page.locator(`#tbl [data-row-id="${id}"]`).waitFor();
    check((await page.content()).includes('/app.js?v=20260924r31policy1'), `${width}: actual index loads current original app.js`);
    equal(reads.length, 0, `${width}: no preview before opening original course action`);
    await clickRowAction(page, id, '授课记录');
    const policy = page.locator('[data-m05-policy-preview]'), slot = key => policy.locator(`[data-m05-policy-slot="${key}"]`);
    await policy.waitFor(); await page.waitForFunction(() => document.querySelector('[data-m05-policy-slot="notice"]')?.textContent.includes('预览已更新'));
    equal(await slot('actual-hours').textContent(), '待核', `${width}: empty actual evidence stays unknown`);
    equal(await slot('payable-hours').textContent(), '待核', `${width}: empty payable does not inherit schedule hours`);
    const mask = await page.locator('#modal-mask').elementHandle();
    await page.locator('[data-k="actual_minutes"]').fill('60'); await page.locator('[data-k="payable_hours"]').fill('1.25'); await page.locator('[data-k="evidence_code"]').fill('SYNTHETIC-BROWSER-' + width);
    equal(await page.locator('[data-k="actual_hours"]').inputValue(), '1.33', `${width}: original delivery component previews 60 minutes exactly`);
    const saveRead = page.waitForResponse(r => r.url().includes('/delivery-settlement/policy-preview?dispatch_id=' + id) && r.request().method() === 'GET');
    await page.locator('[data-m05-action="save"]').click(); await saveRead;
    await page.waitForFunction(() => document.querySelector('[data-m05-policy-slot="payable-hours"]')?.textContent === '1.25 课时');
    equal(writes.length, 1, `${width}: one saved fact submission`);
    equal(writes[0].actual_minutes, '60', `${width}: real save transports minutes as decimal string`);
    equal(writes[0].payable_hours, '1.25', `${width}: independent payable hours stay explicit`);
    equal(await slot('actual-hours').textContent(), '1.33 课时', `${width}: server preview refreshes saved actual hours`);
    check((await slot('scenarios').textContent()).includes('125.00 元'), `${width}: conditional scenarios use independent 1.25 payable hours`);
    equal(await slot('raw').textContent(), '待核', `${width}: unknown applicable rate does not select a main amount`);
    equal(await slot('final').textContent(), '待确定', `${width}: formal amount remains undetermined`);
    check(await mask.evaluate(e => e === document.querySelector('#modal-mask')), `${width}: successful save preserves original modal`);
    equal(await page.locator('[data-k="evidence_code"]').inputValue(), 'SYNTHETIC-BROWSER-' + width, `${width}: save preserves evidence input`);
    check((await page.locator(`#tbl [data-row-id="${id}"]`).textContent()).includes('实际 1.33'), `${width}: original course row updates locally`);
    equal(await policy.locator('button').count(), 1, `${width}: read-only preview has no settlement/payment action`);
    check((await slot('scenarios').textContent()).includes('条件'), `${width}: scenario table is explicitly conditional`);
    await policy.scrollIntoViewIfNeeded(); await noOverflow(page, 'policy panel ' + width); await screenshot(page, 'policy-preview-' + width);
    await slot('scenarios').scrollIntoViewIfNeeded(); await noOverflow(page, 'policy scenarios ' + width);
    if (width === 390) check(await slot('scenarios').locator('tbody td').evaluateAll(nodes => nodes.every(n => n.dataset.label && n.getBoundingClientRect().width <= innerWidth)), 'Mobile scenario cards retain labels within viewport');
    await screenshot(page, 'policy-scenarios-' + width);
    await page.locator('#modal-x').click(); equal(await policy.count(), 0, `${width}: cancel removes preview`);
    await clickRowAction(page, fixture.dispatches[2], '授课记录'); await page.waitForFunction(() => document.querySelector('[data-m05-policy-slot="notice"]')?.textContent.includes('预览已更新'));
    equal(await slot('dispatch').textContent(), String(fixture.dispatches[2]), `${width}: opening another original row identifies new dispatch`);
    equal(await slot('actual-hours').textContent(), '待核', `${width}: new row never retains prior 1.33 actual`);
    equal(await slot('payable-hours').textContent(), '待核', `${width}: new row never retains prior 1.25 payable`);
    // Delay an actual Main response, then navigate away; no fake business payload is supplied.
    let release; const gate = new Promise(resolve => { release = resolve; }); let intercepted;
    await page.route('**/api/delivery-settlement/policy-preview?dispatch_id=' + fixture.dispatches[2], async route => {
      const response = await route.fetch(); intercepted = true; await gate;
      try { await route.fulfill({ response }); } catch { /* Original abort may already have discarded this response. */ }
    });
    await policy.locator('[data-m05-policy-action="refresh"]').click(); await page.waitForFunction(() => document.querySelector('[data-m05-policy-slot="content"]')?.textContent === '');
    for (let i = 0; !intercepted && i < 100; i++) await pause(20);
    check(intercepted, `${width}: delayed response comes from actual Main`);
    await page.evaluate(() => { location.hash = '#/projects'; }); await page.locator('.table-projects').waitFor(); release(); await pause(100);
    equal(await page.locator('[data-m05-policy-preview]').count(), 0, `${width}: route cleanup discards old preview`);
    equal(await page.locator('#modal-mask').count(), 0, `${width}: late response never reopens original modal`);
    check(reads.every(r => r.method() === 'GET' && r.postData() === null), `${width}: every preview request is GET without client fact/authority body`);
    await noOverflow(page, 'after policy route change ' + width);
  } finally { await context.close(); }
}
async function verifyAccountScopes(width) {
  const { context, page } = await contextFor('admin', width);
  const leader = (await api('/users')).find(user => user.username === username('leader'));
  const configurationPosts = [], imports = [];
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().endsWith('/organization/config')) configurationPosts.push(request);
    if (request.url().includes('/modules/identity/account-bindings.js?')) imports.push(request.url());
  });
  try {
    await page.goto(base + '/#/users'); await page.locator(`#tbl [data-row-id="${leader.id}"]`).waitFor();
    await clickRowAction(page, leader.id, '绑定人员'); await page.locator('#modal-mask').waitFor();
    equal(await page.locator('#modal-title').textContent(), '绑定现有人员', `${width}: original user action opens original binding modal`);
    check(imports.some(url => url.endsWith('v=20260923rolescopes1')), `${width}: current role scope binding component loaded`);
    equal(await page.locator('[data-k="personCode"]').inputValue(), 'P2', `${width}: selected binding is the synthetic combined approver`);
    const scopes = page.locator('[data-k="bindingRoleScopes"]'), combined = page.locator('[data-k="bindingCombined"]');
    equal(await scopes.inputValue(), 'LEADER：001；BP：001', `${width}: role-specific scopes come from real organization configuration`);
    equal(await combined.inputValue(), '001', `${width}: combined approval organization comes from explicit configuration`);
    check(await scopes.evaluate(node => node.readOnly) && await combined.evaluate(node => node.readOnly), `${width}: both authority evidence fields are read-only`);
    equal(await page.locator('[data-k="bindingOrg"]').inputValue(), '002', `${width}: original home organization remains distinct from responsible scopes`);
    await page.locator('.modal-body').evaluate(node => { node.scrollTop = node.scrollHeight; });
    check(await scopes.isVisible() && await combined.isVisible(), `${width}: original form exposes both scope fields`);
    const footerTop = await page.locator('.modal-foot').evaluate(node => node.getBoundingClientRect().top);
    check(await combined.evaluate((node, top) => node.getBoundingClientRect().bottom <= top, footerTop), `${width}: combined scope remains reachable above original sticky footer`);
    await noOverflow(page, 'account role scopes ' + width); await screenshot(page, 'account-role-scopes-' + width);
    await page.locator('#modal-x').click(); equal(await page.locator('#modal-mask').count(), 0, `${width}: original binding cancel closes modal`);
    equal(configurationPosts.length, 0, `${width}: read-only inspection and cancel never publish configuration`);
  } finally { await context.close(); }
}
try {
  await boot(); const fixture = await setup();
  browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined, headless: true });
  for (const [index, width] of [1280, 390].entries()) {
    if (phase === 'full') { await verifyCombined(fixture, width, index); await verifyAccountScopes(width); }
    await verifyPolicy(fixture, width, index);
  }
  equal(browserErrors, [], 'No browser runtime errors or native confirmation dialogs');
  equal(outsideRequests, [], 'Original routes make no external network requests');
  equal(apiFailures, [], 'Real browser API requests all succeed');
} catch (e) { error = e; }
finally {
  if (browser) await browser.close(); await stop();
  await writeFile(join(own, 'server.log'), log.replaceAll(password, '[redacted synthetic password]'), { mode: 0o600 });
  const result = { ok: !error, phase, checks, app, classes, temporaryDirectory: own, syntheticDatabase: data, screenshots, browserErrors, outsideRequests, apiFailures, ...(error ? { error: error.stack || String(error) } : {}) };
  await writeFile(join(own, 'result.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify(result, null, 2));
}
if (error) process.exitCode = 1;
