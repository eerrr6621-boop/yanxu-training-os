'use strict';
// Actual Main/API/Auth/M01/M05/M08 with synthetic records and a private temporary loopback H2 only.
const fs = require('node:fs'), os = require('node:os'), path = require('node:path'), net = require('node:net');
const crypto = require('node:crypto'), assert = require('node:assert/strict'), { spawn, execFile } = require('node:child_process');
const { promisify } = require('node:util'), run = promisify(execFile);
const { workflowConfiguration } = require('./IntegrationWorkflowHttpFixture.cjs');
const args = {};
for (let i = 2; i < process.argv.length; i += 2) {
  assert.ok(['--java', '--classes'].includes(process.argv[i]) && process.argv[i + 1], 'Use --java PATH --classes ISOLATED_DIRECTORY'); args[process.argv[i]] = process.argv[i + 1];
}
assert.ok(args['--java'] && args['--classes'], 'Explicit Java and isolated classes required');
const repo = path.resolve(__dirname, '..'), classes = path.resolve(args['--classes']);
assert.ok(classes !== path.join(repo, 'out') && !classes.startsWith(path.join(repo, 'out') + path.sep), 'No shared out');
assert.ok(fs.existsSync(path.join(classes, 'com/training/IntegrationSummaryDeliveryHttpFixture.class')), 'Compile current sources and the scripts-only fixture first');
const own = fs.mkdtempSync(path.join(os.tmpdir(), 'yanxu-summary-delivery-http-')), data = path.join(own, 'data'); fs.mkdirSync(data);
const password = crypto.randomBytes(24).toString('hex'), tokens = {}, failures = [];
const env = { ...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: '' };
const actors = ['admin', 'leader', 'bp', 'team', 'outsider', 'editor', 'textonly'];
let child, port, checks = 0, serial = 0, configuration, phase = 'setup', fatal;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(value, label) { checks++; if (!value) failures.push(`${phase}: ${label}`); }
function same(actual, expected, label) { let match = true; try { assert.deepStrictEqual(actual, expected); } catch { match = false; } check(match, label); }
function hours(value, expected, label) { check(typeof value === 'string' && /^\d+(?:\.\d+)?$/.test(value) && Number(value) === expected, label); }
function options() { return ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password,
  '-Dintegration.summary.delivery.root=' + own, '-Ddata.dir=' + data, '-Dsemantic.port=', '-Dsemantic.token=', '-cp', classes + path.delimiter + path.join(repo, 'lib', '*')]; }
async function request(route, body, actor = 'editor') {
  const response = await fetch(`http://127.0.0.1:${port}/api${route}`, { method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', ...(tokens[actor] ? { 'X-Token': tokens[actor] } : {}) }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000) });
  return { status: response.status, envelope: await response.json() };
}
async function api(route, body, actor) { const r = await request(route, body, actor); assert.equal(r.status, 200, `${phase}: ${route}: HTTP ${r.status}: ${r.envelope.msg}`); assert.equal(r.envelope.code, 0); return r.envelope.data; }
async function denied(route, body, actor, status, label) { const r = await request(route, body, actor); check(r.status === status && r.envelope.code === status, `${label}: ${r.status}`); }
async function login(actor) { tokens[actor] = (await api('/login', { username: actor === 'admin' ? 'admin' : 'synthetic-summary-delivery-' + actor, password }, 'anonymous')).token; }
async function logins() { for (const actor of actors) await login(actor); }
async function boot() {
  for (const key of Object.keys(tokens)) delete tokens[key];
  const listener = net.createServer(); await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve)); port = listener.address().port; await new Promise(resolve => listener.close(resolve));
  child = spawn(args['--java'], [...options(), 'com.training.Main', String(port)], { cwd: repo, env, stdio: 'ignore' });
  let launchError; child.once('error', error => { launchError = error; });
  for (let i = 0; i < 150; i++) { if (launchError) throw launchError; assert.equal(child.exitCode, null, 'Isolated Main exited'); try { await login('admin'); return; } catch {} await pause(100); }
  throw new Error('Isolated Main startup timeout');
}
async function stop() {
  const running = child; child = null; if (!running || running.exitCode !== null || running.signalCode !== null) return;
  await new Promise(resolve => { const timer = setTimeout(() => { if (running.exitCode === null && running.signalCode === null) running.kill('SIGKILL'); }, 3000); running.once('exit', () => { clearTimeout(timer); resolve(); }); running.kill('SIGTERM'); });
}
async function offline(operation) {
  assert.ok(!child, 'Stop isolated server before offline inspection');
  const result = await run(args['--java'], [...options(), 'com.training.IntegrationSummaryDeliveryHttpFixture', operation], { cwd: repo, env, timeout: 20000, maxBuffer: 65536 }); return JSON.parse(result.stdout.trim());
}
async function publish(c) { const next = { ...structuredClone(c), version: 'synthetic-summary-delivery-' + (++serial) }; await api('/organization/config', { expectedVersion: configuration?.version || null, configuration: next }, 'admin'); configuration = next; await logins(); }
async function identities() {
  const ids = [(await api('/me', undefined, 'admin')).uid];
  for (const actor of actors.slice(1)) ids.push(await api('/users', { username: 'synthetic-summary-delivery-' + actor, name: 'SYNTHETIC ' + actor, role: actor === 'team' ? 'manager' : 'viewer', status: 1, password }, 'admin'));
  const c = workflowConfiguration(ids.slice(0, 5));
  const optional = { required: false, allowedTargetRoles: ['EDITOR', 'TEXTONLY'], targetMustCoverOrganization: false, allowSelf: false };
  for (const [index, role] of [[5, 'EDITOR'], [6, 'TEXTONLY']]) {
    c.roleCodes.push(role); c.relations.push({ roleCode: role, leader: optional, bp: optional });
    c.people.push({ personCode: 'P' + (index + 1), organizationCode: '001', responsibleOrganizationCodes: [], roleCodes: [role], leaderPersonCode: null, bpPersonCode: null, enabled: true });
    c.accountBindings.push({ accountId: ids[index], personCode: 'P' + (index + 1), enabled: true });
    for (const [resource, action] of [['summary.read', 'VIEW'], ['summary.edit', 'HANDLE']]) c.grants.push({ ruleId: role + '-' + resource, roleCode: role, resource, action, effect: 'ALLOW', scope: 'OWN_ORG', organizationCodes: [] });
  }
  c.grants.push({ ruleId: 'editor-delivery', roleCode: 'EDITOR', resource: 'delivery.read', action: 'VIEW', effect: 'ALLOW', scope: 'OWN_ORG', organizationCodes: [] }); await publish(c);
}
async function project(title) {
  const key = 'PROJECT-' + (++serial); let d = (await api('/demands/draft', { request_id: key, title, unit: 'SYNTHETIC CUSTOMER', business_path: 'direct', organization_code: '001', internal_contact_code: 'P1', category_text: '业务技能', delivery_mode_text: '线上', period_text: '全天', duration_minutes: '180', participant_count: '1', budget_amount: '0', objectives: 'SYNTHETIC', content: 'SYNTHETIC' }, 'admin')).workflow;
  d = (await api('/demands/submit', { id: d.id, expected_version: d.version, request_id: key + '-SUBMIT' }, 'admin')).workflow;
  for (const actor of ['leader', 'bp']) d = (await api('/approvals/tasks/' + d.id + '/actions', { action: 'APPROVE', expectedVersion: d.approval.version, expectedStage: d.approval.stage, requestId: key + '-' + actor, comment: 'SYNTHETIC' }, actor)).workflow;
  const p = (await api('/demands/accept', { id: d.id, expected_version: d.version, request_id: key + '-ACCEPT', team_code: '900' }, 'team')).workflow.project_id;
  let row = (await api('/projects', undefined, 'team')).find(r => r.id === p);
  await api('/projects', { ...row, owner: 'SYNTHETIC OWNER', start_date: '2025-01-01', end_date: '2025-01-05', delivery_mode: '线上', hours: 4, amount: 0 }, 'team'); await api('/projects/start', { id: p }, 'team'); return p;
}
async function dispatch(project_id, teacher_id, day, rejected = false) {
  const id = await api('/dispatches', { project_id, teacher_id, subject: 'SYNTHETIC PRIVATE COURSE ' + day, teach_date: '2025-01-0' + day, hours: 9, status: '待发送', material_status: '已就绪' }, 'team');
  await api('/dispatches/send', { id }, 'team'); await api('/dispatches/confirm', { id, accept: rejected ? 0 : 1, ...(rejected ? { reason: 'SYNTHETIC REJECTION' } : {}) }, 'team'); return id;
}
async function fact(id, expected_version, minutes, payable, estimated = '2', planned = '1.5') {
  return api('/delivery-settlement/save', { dispatch_id: id, expected_version, request_id: 'FACT-' + (++serial), estimated_hours: estimated, planned_hours: planned, actual_minutes: minutes, payable_hours: payable }, 'team');
}
async function verify(id, saved) { return api('/delivery-settlement/verify', { dispatch_id: id, expected_version: saved.version, request_id: 'VERIFY-' + (++serial), evidence_code: 'SYNTHETIC-PRIVATE-VERIFICATION-EVIDENCE' }, 'team'); }
const base = '/training-summaries', read = id => base + '?project_id=' + id, revision = (id, n) => base + '/revision?project_id=' + id + '&revision=' + n;
const cmd = (project_id, expected_version, request_id) => ({ project_id, expected_version, request_id });
const saveBody = (p, v, key, text) => ({ ...cmd(p, v, key), content: { achievements: text, issues: '', nextSteps: '' } });
const hiddenDelivery = { status: 'UNAVAILABLE', reason: 'DELIVERY_READ_REQUIRED', value: null };
function hiddenSources(sources, label) {
  same(sources.delivery, hiddenDelivery, label + ' has only fixed hidden delivery');
  same(sources.feedback, { status: 'UNAVAILABLE', reason: 'SURVEY_READ_REQUIRED', value: null }, label + ' has only fixed hidden feedback');
  check(sources.source_version === null && sources.visibility === 'DELIVERY_AND_FEEDBACK_HIDDEN', label + ' hides the combined source digest for both ungranted modules');
}
function hidden(view, label) { hiddenSources(view.sources, label + ' top'); if (view.current) hiddenSources(view.current.sources, label + ' current'); }
function clean(view, label) {
  const raw = JSON.stringify(view);
  check(!raw.includes('SYNTHETIC-PRIVATE-TEACHER') && !raw.includes('SYNTHETIC-PRIVATE-VERIFICATION-EVIDENCE') && !raw.includes('SYNTHETIC PRIVATE COURSE'), label + ' excludes teacher, course and verification text');
  const inspect = value => { if (!value || typeof value !== 'object') return true; return Object.entries(value).every(([key, child]) => !['amount', 'fee_rate', 'rate', 'teacher_id', 'teacher_name', 'evidence_code', 'verification'].includes(key) && inspect(child)); };
  check(inspect(view.sources?.delivery || view), label + ' has no fee, teacher identity or verification fields');
}
async function runChecks() {
  await boot(); await identities(); const original = structuredClone(configuration);
  const p = await project('SYNTHETIC SUMMARY DELIVERY MAIN'), empty = await project('SYNTHETIC SUMMARY DELIVERY NO GRANT'), legacy = await project('SYNTHETIC SUMMARY DELIVERY LEGACY');
  const teacher = await api('/teachers', { name: 'SYNTHETIC-PRIVATE-TEACHER', status: '在库', field: 'SYNTHETIC', base_province: '浙江', base_city: '杭州', fee_rate: 987.65 }, 'admin');
  const done = await dispatch(p, teacher, 1), pending = await dispatch(p, teacher, 2); await dispatch(p, teacher, 3, true);
  let doneFact = await verify(done, await fact(done, 0, '60', '1.25'));
  await api('/dispatches/complete', { id: done, expected_version: doneFact.version }, 'team'); await fact(pending, 0, '45', '0.75', '3', '4');
  phase = 'initial verified delivery source';
  await denied(read(p), undefined, 'anonymous', 401, 'Summary requires real Auth'); await denied(read(p), undefined, 'outsider', 403, 'Other organization cannot read summary');
  const fresh = await api(read(p)); const a = fresh.sources.delivery;
  check(fresh.version === 0 && a.status === 'AVAILABLE' && a.policy_version === 'M08-M05-SOURCE-20260923-1', 'Fresh summary uses current trusted M05 source');
  hours(a.value.estimated, 5, 'Estimated includes both effective records'); hours(a.value.planned, 5.5, 'Planned remains separate'); hours(a.value.actual, 1.33, 'Only reviewed completed actual included'); hours(a.value.payable, 1.25, 'Payable stays independent');
  for (const [key, expected] of Object.entries({ dispatch_count: 2, reviewed_completed_count: 1, pending_count: 1, unverified_completed_count: 0, rejected_count: 1, total_dispatch_count: 3, fact_count: 2, missing_fact_count: 0, verified_count: 1 })) check(a.value[key] === expected, 'Actual M05 coverage count ' + key);
  check(a.coverage.actual_payable === 'REVIEWED_COMPLETED_AS_OF_DATE' && a.coverage.unknown === 'NULL_PROPAGATES' && a.coverage.hour_unit === 'CLASS45', 'Source states precise coverage and unknown contract'); clean(fresh, 'Fresh source');
  const firstBody = saveBody(p, 0, 'SUMMARY-FIRST', 'SYNTHETIC FIRST BODY'); let summary = await api(base + '/save', firstBody); const first = await api(revision(p, 1));
  same(summary.sources.delivery, a, 'First save freezes actual source'); same(summary.current.sources.delivery, a, 'First current revision freezes same source');
  const hiddenFirst = await api(read(p), undefined, 'textonly'); hidden(hiddenFirst, 'Never granted reader'); check(!hiddenFirst.source_changed, 'Missing delivery grant does not signal hidden change');
  const limitedProject = (await api('/projects', undefined, 'textonly')).find(row => row.id === p);
  same(Object.keys(limitedProject || {}).sort(), ['delivery_controlled', 'id', 'read_access', 'status', 'title'], 'Summary-only project selector exposes exactly the limited source whitelist');
  same(limitedProject?.read_access, { limited: true, delivery: false, summary: true, feedback: false }, 'Summary-only selector retains independent module permissions');
  check(limitedProject?.delivery_controlled === true, 'Summary-only selector retains verified managed-project association');
  same(await api('/demands', undefined, 'textonly'), [], 'Summary-only reader cannot browse source demands');
  phase = 'fact correction and explicit refresh';
  doneFact = await fact(done, doneFact.version, '90', '1.75');
  summary = await api(read(p)); same(summary.sources.delivery, a, 'Correction never replaces frozen summary'); check(summary.source_changed, 'Correction marks visible source changed');
  summary = await api(base + '/save', saveBody(p, 1, 'SUMMARY-BODY-ONLY', 'SYNTHETIC EDITED BODY')); same(summary.sources.delivery, a, 'Ordinary save retains previous frozen source'); check(summary.source_changed, 'Ordinary save does not hide stale source');
  doneFact = await verify(done, doneFact); summary = await api(base + '/refresh', cmd(p, 2, 'SUMMARY-REFRESH')); const b = summary.sources.delivery;
  hours(b.value.actual, 2, 'Refresh incorporates corrected reviewed actual'); hours(b.value.payable, 1.75, 'Refresh incorporates independent payable'); check(!summary.source_changed && summary.current.operation === 'REFRESH_SOURCES', 'Refresh appends coherent source revision');
  check(summary.current.content.achievements === 'SYNTHETIC EDITED BODY', 'Refresh preserves body'); same((await api(revision(p, 1))).sources, first.sources, 'First historical source remains exact'); same((await api(revision(p, 2))).sources.delivery, a, 'Ordinary-save revision remains original source'); clean(summary, 'Refreshed source');
  phase = 'revoked delivery permission and invisible source changes';
  await publish({ ...original, grants: original.grants.filter(g => g.ruleId !== 'editor-delivery') });
  for (const [label, value] of [['GET', await api(read(p))], ['replay', await api(base + '/save', firstBody)]]) { hidden(value, label); check(!value.source_changed, label + ' hides delivery-only difference'); }
  const oldHidden = await api(revision(p, 1)); hiddenSources(oldHidden.sources, 'Historical revision');
  check((await api(base + '/history?project_id=' + p)).items.every(item => !('sources' in item) && !('delivery' in item)), 'History metadata exposes no delivery facts');
  doneFact = await verify(done, await fact(done, doneFact.version, '120', '2.25'));
  summary = await api(read(p)); hidden(summary, 'After invisible live change'); check(!summary.source_changed, 'Live hidden correction cannot be inferred from source_changed');
  const hiddenSaveBody = saveBody(p, 3, 'SUMMARY-HIDDEN-SAVE', 'SYNTHETIC BODY WITHOUT DELIVERY'); summary = await api(base + '/save', hiddenSaveBody); hidden(summary, 'Hidden ordinary save'); check(summary.version === 4 && !summary.source_changed, 'Summary edit remains usable without delivery permission');
  let projectRow = (await api('/projects', undefined, 'team')).find(r => r.id === p); await api('/projects', { ...projectRow, participant_count: 7 }, 'team');
  check((await api(read(p))).source_changed, 'Visible project change remains detectable without delivery grant');
  summary = await api(base + '/refresh', cmd(p, 4, 'SUMMARY-HIDDEN-REFRESH')); hidden(summary, 'Hidden explicit refresh'); check(summary.sources.project.value.participant_count === 7 && !summary.source_changed && summary.version === 5, 'Hidden refresh updates visible project only');
  const neverGrantedBody = saveBody(empty, 0, 'SUMMARY-NO-GRANT', 'SYNTHETIC TEXT ONLY'); const noGrant = await api(base + '/save', neverGrantedBody, 'textonly'); hidden(noGrant, 'Never granted first save');
  phase = 'regrant preserves old frozen values'; await publish(original);
  summary = await api(read(p)); same(summary.sources.delivery, b, 'Regrant reveals exact pre-revocation frozen source'); check(summary.source_changed, 'Regranted user sees outstanding real delivery change');
  same((await api(revision(p, 4))).sources.delivery, b, 'Hidden save did not write response projection into history'); same((await api(revision(p, 5))).sources.delivery, b, 'Hidden refresh preserved stored delivery');
  same((await api(base + '/save', firstBody)).sources.delivery, a, 'Regrant can replay original first frozen delivery');
  summary = await api(base + '/refresh', cmd(p, 5, 'SUMMARY-REFRESH-AUTHORIZED')); hours(summary.sources.delivery.value.actual, 2.67, 'Authorized refresh explicitly imports latest reviewed hours'); hours(summary.sources.delivery.value.payable, 2.25, 'Authorized refresh preserves payable precision'); clean(summary, 'Final source');
  const withTextDelivery = structuredClone(original); withTextDelivery.grants.push({ ruleId: 'textonly-delivery', roleCode: 'TEXTONLY', resource: 'delivery.read', action: 'VIEW', effect: 'ALLOW', scope: 'OWN_ORG', organizationCodes: [] }); await publish(withTextDelivery);
  const laterGranted = await api(read(empty), undefined, 'textonly'); same(laterGranted.sources.delivery, hiddenDelivery, 'Initial no-grant snapshot remains unchanged after grant'); check(laterGranted.source_changed, 'New grant requires explicit source refresh');
  const emptyRefreshed = await api(base + '/refresh', cmd(empty, 1, 'SUMMARY-NO-GRANT-REFRESH'), 'textonly'); check(emptyRefreshed.sources.delivery.reason === 'NO_DISPATCHES' && emptyRefreshed.sources.delivery.value.total_dispatch_count === 0, 'Explicit refresh of genuinely empty project records no-dispatch facts'); hours(emptyRefreshed.sources.delivery.value.actual, 0, 'No-dispatch actual zero is explicit');
  phase = 'legacy snapshot compatibility and restart'; await api(base + '/save', saveBody(legacy, 0, 'LEGACY-FIRST', 'SYNTHETIC LEGACY BODY'));
  await stop(); const beforeLegacy = await offline('inspect'); check(beforeLegacy.M05_SETTLEMENT_SNAPSHOTS.count === 0 && beforeLegacy.M05_SETTLEMENT_CONFIGS.count === 0, 'Summary source reads never configure or settle fees');
  await offline('legacy-source'); await boot(); await logins();
  const legacyFirst = await api(revision(legacy, 1)); same(legacyFirst.sources.delivery, { status: 'UNAVAILABLE', reason: 'M05_SNAPSHOT_NOT_CONNECTED', value: null }, 'Historical unconnected snapshot reads without implicit recalculation');
  const legacyRead = await api(read(legacy)); check(legacyRead.source_changed, 'Authorized old snapshot detects available new source');
  const legacySaved = await api(base + '/save', saveBody(legacy, 1, 'LEGACY-SAVE', 'SYNTHETIC LEGACY EDIT')); same(legacySaved.sources.delivery, legacyFirst.sources.delivery, 'Ordinary save preserves old unconnected snapshot');
  const legacyNew = await api(base + '/refresh', cmd(legacy, 2, 'LEGACY-REFRESH')); check(legacyNew.sources.delivery.reason === 'NO_DISPATCHES', 'Explicit refresh upgrades only new revision'); same(await api(revision(legacy, 1)), legacyFirst, 'Old historical source remains byte-semantically unchanged');
  await stop(); const beforeReads = await offline('inspect'); await boot(); await logins();
  const restarted = await api(read(p)); same(restarted.sources, summary.sources, 'Real restart preserves final project and delivery snapshot');
  for (const id of [p, empty, legacy]) { await api(read(id)); await api(revision(id, 1)); await api(base + '/history?project_id=' + id); }
  await api(base + '/save', firstBody); await api(base + '/save', hiddenSaveBody); await stop();
  same(await offline('inspect'), beforeReads, 'GET, history and exact replay do not alter any persisted table');
}
runChecks().catch(error => { fatal = String(error.message).replaceAll(password, '[redacted]'); process.exitCode = 1; }).finally(async () => {
  await stop(); const result = { suite: 'summary-delivery-http', phase, checks, failures, ...(fatal ? { error: fatal } : {}), production_touched: false, notifications_sent: false, artifacts: own };
  fs.writeFileSync(path.join(own, 'result.json'), JSON.stringify(result, null, 2), { mode: 0o600 }); console.log(JSON.stringify(result)); if (fatal || failures.length) process.exitCode = 1;
});
