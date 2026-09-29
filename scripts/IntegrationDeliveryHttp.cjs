'use strict';
// Real Main/Api, random credentials, loopback and fresh temporary H2 only. Never compiles or opens app/data.
// Compile the scripts-only IntegrationDeliveryHttpFixture.java into --classes before running.
const fs = require('node:fs'), os = require('node:os'), path = require('node:path'), net = require('node:net');
const crypto = require('node:crypto'), assert = require('node:assert/strict');
const {spawn, execFile} = require('node:child_process'), {promisify} = require('node:util');
const {workflowConfiguration} = require('./IntegrationWorkflowHttpFixture.cjs');
const run = promisify(execFile), args = {};
for (let i = 2; i < process.argv.length; i += 2) {
  assert.ok(['--java', '--classes'].includes(process.argv[i]) && process.argv[i + 1], 'Use --java PATH --classes ISOLATED_DIRECTORY');
  args[process.argv[i]] = process.argv[i + 1];
}
assert.ok(args['--java'] && args['--classes'], 'Explicit Java and isolated classes are required');
const repo = path.resolve(__dirname, '..'), classes = path.resolve(args['--classes']);
assert.ok(!classes.startsWith(path.join(repo, 'out')), 'Use isolated temporary compiled classes');
assert.ok(fs.existsSync(path.join(classes, 'com/training/IntegrationDeliveryHttpFixture.class')), 'Compile the scripts-only fixture first');
const own = fs.mkdtempSync(path.join(os.tmpdir(), 'yanxu-delivery-http-'));
const password = crypto.randomBytes(24).toString('hex'), tokens = {}, failures = [];
let child, port, checks = 0, phase = 'startup', fatal = null, configuration;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(ok, label) { checks++; if (!ok) failures.push(`${phase}: ${label}`); }
function same(a, b, label) { let ok = true; try { assert.deepEqual(a, b); } catch { ok = false; } check(ok, label); }
function decimal(value, expected, label) { check(typeof value === 'string' && Number(value) === Number(expected), label); }
function options(data) {
  return ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password,
    '-Dintegration.delivery.root=' + own, '-Ddata.dir=' + data, '-Dsemantic.port=', '-Dsemantic.token=',
    '-cp', classes + path.delimiter + path.join(repo, 'lib', '*')];
}
const env = {...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: ''};
async function request(route, body, actor = 'worker', overrides = {}) {
  const headers = {...(tokens[actor] ? {'X-Token': tokens[actor]} : {})};
  if (body !== undefined && overrides.contentType !== null) headers['Content-Type'] = overrides.contentType || 'application/json';
  const response = await fetch(`http://127.0.0.1:${port}/api${route}`, {
    method: overrides.method || (body === undefined ? 'GET' : 'POST'), headers,
    body: body === undefined ? undefined : overrides.raw ? body : JSON.stringify(body), signal: AbortSignal.timeout(5000),
  });
  const text = await response.text(); let envelope = null;
  if (text) { try { envelope = JSON.parse(text); } catch { throw new Error(`${route}: non-JSON HTTP ${response.status}`); } }
  return {status: response.status, headers: response.headers, text, envelope};
}
async function api(route, body, actor = 'worker') {
  const r = await request(route, body, actor);
  assert.equal(r.status, 200, `${phase}: ${route}: HTTP ${r.status}: ${r.envelope?.msg}`);
  assert.equal(r.envelope?.code, 0, `${phase}: ${route}: ${r.envelope?.msg}`); return r.envelope.data;
}
async function denied(route, body, actor, status, label, overrides = {}) {
  const r = await request(route, body, actor, overrides);
  check(r.status === status && r.envelope?.code === status, `${label} (HTTP ${r.status}, code ${r.envelope?.code}, ${r.envelope?.msg})`);
  return r.envelope;
}
async function login(actor, username = 'synthetic-' + actor) { tokens[actor] = (await api('/login', {username, password}, 'anonymous')).token; }
async function boot(data, fixture) {
  Object.keys(tokens).forEach(key => delete tokens[key]);
  const listener = net.createServer();
  await new Promise((resolve, reject) => { listener.once('error', reject); listener.listen(0, '127.0.0.1', resolve); });
  port = listener.address().port; await new Promise(resolve => listener.close(resolve));
  child = spawn(args['--java'], [...options(data), fixture ? 'com.training.IntegrationDeliveryHttpFixture' : 'com.training.Main', String(port)], {cwd: repo, env, stdio: 'ignore'});
  let launchError; child.once('error', error => { launchError = error; });
  for (let i = 0; i < 150; i++) {
    if (launchError) throw launchError;
    assert.equal(child.exitCode, null, 'Isolated service exited before login');
    try { await login('admin', 'admin'); if (tokens.admin) return; } catch {}
    await pause(100);
  }
  throw new Error('Isolated delivery service did not become ready');
}
async function stop() {
  const running = child; child = null;
  if (!running || running.exitCode !== null || running.signalCode !== null) return;
  await new Promise(resolve => {
    const timer = setTimeout(() => { if (running.exitCode === null && running.signalCode === null) running.kill('SIGKILL'); }, 2000);
    running.once('exit', () => { clearTimeout(timer); resolve(); }); running.kill('SIGTERM');
  });
}
async function inspect(data) {
  const r = await run(args['--java'], [...options(data), 'com.training.IntegrationDeliveryHttpFixture', 'inspect'], {cwd: repo, env, timeout: 15000, maxBuffer: 16384});
  return JSON.parse(r.stdout.trim());
}
const cmd = (id, version, request_id) => ({dispatch_id: id, expected_version: version, request_id});
const detail = id => api('/delivery-settlement?dispatch_id=' + id);
// Configuration publication is an explicit authentication boundary, never an API retry policy.
async function renewPublishedSessions(label, actors = ['admin', 'leader', 'bp', 'team', 'outsider', 'worker', 'reader', 'disabled']) {
  for (const actor of actors) {
    if (!tokens[actor]) continue; // Some identities have not logged in since a process restart.
    await denied('/me', undefined, actor, 401, label + ' invalidates the old ' + actor + ' token');
    await login(actor, actor === 'admin' ? 'admin' : undefined);
  }
}
async function publish(changes, suffix) {
  const previous = configuration.version;
  configuration = {...structuredClone(configuration), ...changes, version: 'synthetic-delivery-http-' + suffix};
  await api('/organization/config', {expectedVersion: previous, configuration}, 'admin');
  await renewPublishedSessions(suffix);
}
async function setupIdentity() {
  const ids = [(await api('/me', undefined, 'admin')).uid], all = {};
  for (const [actor, role] of [['leader', 'viewer'], ['bp', 'viewer'], ['team', 'manager'], ['outsider', 'manager'], ['worker', 'viewer'], ['reader', 'viewer'], ['disabled', 'manager'], ['unbound', 'manager']]) {
    const id = await api('/users', {username: 'synthetic-' + actor, name: 'SYNTHETIC ' + actor, role, status: 1, password}, 'admin');
    all[actor] = id; if (ids.length < 5) ids.push(id); await login(actor);
  }
  configuration = workflowConfiguration(ids); configuration.version = 'synthetic-delivery-http-v1';
  // The generic workflow fixture may grant delivery for other suites; this suite defines exact roles below.
  configuration.grants = configuration.grants.filter(g => !g.resource.startsWith('delivery.') && !g.resource.startsWith('settlement.'));
  const optional = {required: false, allowedTargetRoles: ['DELIVERY', 'READER'], targetMustCoverOrganization: false, allowSelf: false};
  configuration.roleCodes.push('DELIVERY', 'READER');
  for (const [actor, code, role] of [['worker', 'P6', 'DELIVERY'], ['reader', 'P7', 'READER'], ['disabled', 'P8', 'DELIVERY']]) {
    configuration.people.push({personCode: code, organizationCode: '001', roleCodes: [role], responsibleOrganizationCodes: [], leaderPersonCode: null, bpPersonCode: null, enabled: true});
    configuration.accountBindings.push({accountId: all[actor], personCode: code, enabled: actor !== 'disabled'});
  }
  for (const roleCode of ['DELIVERY', 'READER']) configuration.relations.push({roleCode, leader: optional, bp: optional});
  const grant = (roleCode, resource, action = 'HANDLE', scope = 'OWN_ORG', organizationCodes = []) =>
    configuration.grants.push({ruleId: roleCode + '-' + resource, roleCode, resource, action, effect: 'ALLOW', scope, organizationCodes});
  for (const role of ['DELIVERY', 'OUT']) for (const resource of ['delivery.read', 'delivery.write', 'delivery.verify', 'settlement.configure', 'settlement.confirm', 'settlement.correct', 'settlement.export'])
    grant(role, resource, resource.endsWith('.read') ? 'VIEW' : resource.endsWith('.export') ? 'EXPORT' : 'HANDLE');
  for (const role of ['DELIVERY', 'READER']) grant(role, 'demand.read', 'VIEW');
  grant('READER', 'delivery.read', 'VIEW'); grant('TEAM', 'delivery.read', 'VIEW', 'NAMED_ORGS', ['001']);
  await api('/organization/config', {expectedVersion: null, configuration}, 'admin');
  await renewPublishedSessions('Initial binding');
  return all;
}
async function acceptedProject() {
  phase = 'new accepted project';
  let d = (await api('/demands/draft', {request_id: 'delivery-draft', title: 'SYNTHETIC NEW DELIVERY', unit: 'SYNTHETIC CUSTOMER', business_path: 'direct', organization_code: '001', internal_contact_code: 'P1', category_text: '业务技能', delivery_mode_text: '线上', period_text: '全天', duration_minutes: '90', participant_count: '1', budget_amount: '0', objectives: 'SYNTHETIC', content: 'SYNTHETIC', teacher_req: '', remark: 'SYNTHETIC'}, 'admin')).workflow;
  const workflowCommand = (d, request_id) => ({id: d.id, expected_version: d.version, request_id});
  d = (await api('/demands/submit', workflowCommand(d, 'delivery-submit'), 'admin')).workflow;
  for (const actor of ['leader', 'bp']) d = (await api('/approvals/tasks/' + d.id + '/actions', {action: 'APPROVE', expectedVersion: d.approval.version, expectedStage: d.approval.stage, requestId: 'delivery-' + actor, comment: 'SYNTHETIC'}, actor)).workflow;
  d = (await api('/demands/accept', {...workflowCommand(d, 'delivery-accept'), team_code: '900'}, 'team')).workflow;
  let p = (await api('/projects', undefined, 'team')).find(row => row.id === d.project_id);
  check(p?.delivery_controlled === true && p.workflow_source?.demand_id === d.id, 'Real approval and acceptance produces a controlled project with trusted source');
  await api('/projects', {...p, hours: 2, owner: 'SYNTHETIC', start_date: '2025-01-01', end_date: '2025-01-02', delivery_mode: '线上'}, 'team');
  await api('/projects/start', {id: p.id}, 'team'); return p.id;
}
async function runChecks() {
  const data = path.join(own, 'data'); fs.mkdirSync(data); await boot(data, true);
  same(await api('/organization/config', undefined, 'admin'), {version: null, configuration: null}, 'Startup seeds no identity or permissions');
  const baseline = await api('/dispatches', undefined, 'admin');
  const legacy = baseline.find(row => row.subject === 'SYNTHETIC LEGACY CONFIRMED');
  assert.ok(legacy, 'Trusted fixture must contain the visible synthetic legacy row');
  await denied('/delivery-settlement?dispatch_id=' + legacy.id, undefined, 'admin', 403, 'Unconfigured administrator has no implicit M05 read');
  await denied('/delivery-settlement?dispatch_id=' + legacy.id, undefined, 'anonymous', 401, 'M05 requires real HTTP authentication');
  await setupIdentity();
  const historic = (await api('/dispatches', undefined, 'team')).find(row => row.subject === 'SYNTHETIC HISTORICAL COMPLETED');
  assert.ok(historic, 'Explicit source access exposes controlled historical fixture');
  const initial = await detail(historic.id);
  check(initial.version === 0 && initial.fact === null && initial.configuration === null && initial.snapshots.length === 0, 'Historical controlled row does not create facts or formal configuration automatically');
  check(initial.teacher_code_kind === 'SYSTEM_TEACHER_ID', 'System teacher reference is explicitly distinguished from personnel code');
  for (const actor of ['admin', 'unbound', 'disabled', 'outsider']) await denied('/delivery-settlement?dispatch_id=' + historic.id, undefined, actor, 403, actor + ' has no implicit or out-of-scope M05 access');
  await denied('/delivery-settlement?dispatch_id=' + legacy.id, undefined, 'worker', 409, 'Unmigrated legacy source cannot infer an organization');
  const projectId = await acceptedProject();
  const limitedProject = (await api('/projects', undefined, 'admin')).find(row => row.id === projectId);
  check(limitedProject.delivery_controlled === true && limitedProject.delivery_hours === null && Boolean(limitedProject.delivery_hours_reason), 'Original project visibility does not implicitly expose M05 totals without delivery.read');
  check((await api('/me')).role === 'viewer' && !configuration.grants.some(g => g.roleCode === 'DELIVERY' && g.resource === 'demand.write'), 'Dedicated delivery operator uses old viewer role and has no demand.write grant');
  const teachers = await api('/teachers', undefined, 'admin');
  const teacherId = teachers.find(row => row.name === 'SYNTHETIC DELIVERY TEACHER').id;
  const alternateId = teachers.find(row => row.name === 'SYNTHETIC ALTERNATE TEACHER').id;
  const dispatchBody = {project_id: projectId, teacher_id: teacherId, subject: 'SYNTHETIC REAL HTTP CLASS', teach_date: '2025-01-02', start_time: '09:00', end_time: '10:00', hours: 9, status: '待发送', material_status: '已就绪', remark: 'SYNTHETIC'};
  await denied('/dispatches', {...dispatchBody, status: '已完成'}, 'team', 409, 'Generic create cannot manufacture a completed controlled course');
  const dispatchId = await api('/dispatches', dispatchBody, 'team');
  await api('/dispatches/send', {id: dispatchId}, 'team'); await api('/dispatches/confirm', {id: dispatchId, accept: 1}, 'team');
  let row = (await api('/dispatches', undefined, 'team')).find(row => row.id === dispatchId);
  check(row.delivery_controlled === true && row.hours === 9, 'Original row retains schedule hours and signals controlled delivery');
  await denied('/dispatches', {...row, status: '已完成'}, 'team', 409, 'Generic update cannot bypass verified completion');
  phase = 'HTTP protocol and decimal boundary';
  const route = '/delivery-settlement', save = route + '/save', verify = route + '/verify';
  const first = {...cmd(dispatchId, 0, 'FACT-1'), actual_minutes: '60', estimated_hours: '2.50', planned_hours: '2.00', payable_hours: null};
  const head = await request(route + '?dispatch_id=' + dispatchId, undefined, 'worker', {method: 'HEAD'});
  check(head.status === 405 && head.text === '', 'M05 GET-only read rejects HEAD without a body');
  await denied(save, undefined, 'worker', 405, 'GET cannot save facts');
  await denied(route + '?dispatch_id=' + dispatchId, undefined, 'worker', 405, 'PUT cannot read facts', {method: 'PUT'});
  await denied(route + '?dispatch_id=' + dispatchId + '&organization_code=999', undefined, 'worker', 400, 'Client query cannot override server organization');
  await denied(route + '?dispatch_id=9007199254740992', undefined, 'worker', 400, 'Unsafe query identifier is rejected');
  await denied(save, JSON.stringify(first), 'worker', 415, 'Non-JSON media is rejected before M05', {raw: true, contentType: 'text/plain'});
  await denied(save, '{"dispatch_id":1,"dispatch_id":1}', 'worker', 400, 'Duplicate JSON keys are rejected', {raw: true});
  await denied(save, '{', 'worker', 400, 'Malformed JSON is rejected', {raw: true});
  await denied(save, [], 'worker', 400, 'Non-object body is rejected');
  for (const field of ['actual_minutes', 'estimated_hours', 'planned_hours', 'payable_hours']) await denied(save, {...first, [field]: 1.33}, 'worker', 400, field + ' requires decimal text');
  for (const field of ['organization_code', 'actor_code', 'teacher_id', 'project_id', 'service_date', 'actual_hours', 'verification', 'amount']) await denied(save, {...first, [field]: 'SYNTHETIC-INJECTION'}, 'worker', 400, 'Server-owned ' + field + ' cannot be injected');
  for (const actor of ['reader', 'outsider', 'admin']) await denied(save, first, actor, 403, actor + ' cannot save this fact');
  await denied('/dispatches/complete', {id: dispatchId, expected_version: 0}, 'worker', 409, 'No saved fact cannot complete');
  await denied(save, {...first, expected_version: 1, request_id: 'FACT-FUTURE'}, 'worker', 409, 'Future fact version cannot overwrite an absent head');
  let d = await api(save, first);
  check(d.version === 1 && d.capabilities.current_server === true && d.capabilities.fact_version === 1, 'Viewer with explicit M01 delivery grant can save via real Api');
  same(d.fact.hours, {estimated: '2.50', planned: '2.00', actual: '1.33', payable: null}, 'Four hour values remain independent decimal text and unknown payable stays null');
  check(d.conversion.minutes === '60' && d.conversion.class_hours === '1.33' && d.conversion.minutes_per_class_hour === 45, '60 minutes converts to 1.33 with persisted 45-minute evidence');
  check(d.fact.verification === null && d.capabilities.can_verify === true && d.capabilities.can_complete === false, 'Save does not claim verification or completion');
  const preview = await api(route + '/preview?dispatch_id=' + dispatchId);
  check(preview.status === 'NOT_CONFIGURED' && preview.amount === null, 'No formal policy produces no calculated amount');
  same(await api(route + '/ledger?dispatch_id=' + dispatchId), [], 'No formal confirmation produces no ledger entries');
  await denied(route + '/ledger?dispatch_id=' + dispatchId, undefined, 'reader', 403, 'Read permission does not grant export');
  await denied('/dispatches/complete', {id: dispatchId, expected_version: 1}, 'worker', 409, 'Saved but unverified minutes cannot complete');
  await denied(verify, {...cmd(dispatchId, 1, 'VERIFY-INJECT'), evidence_code: 'EVIDENCE-1', actor_code: 'P1'}, 'worker', 400, 'Client cannot choose verification actor');
  await denied(verify, {...cmd(dispatchId, 1, 'VERIFY-READER'), evidence_code: 'EVIDENCE-1'}, 'reader', 403, 'Read-only actor cannot verify');
  await denied(verify, {...cmd(dispatchId, 0, 'VERIFY-STALE'), evidence_code: 'EVIDENCE-1'}, 'worker', 409, 'Verification cannot use a stale fact revision');
  d = await api(verify, {...cmd(dispatchId, 1, 'VERIFY-1'), evidence_code: 'EVIDENCE-1'});
  check(d.version === 2 && d.fact.verification.actor_code === 'P6' && Boolean(d.fact.verification.checked_at) && d.capabilities.can_complete === true, 'Server records actual bound verifier and permits current version completion');
  for (const bad of [null, -1, 1.5, true, '2.0', '9007199254740992']) await denied('/dispatches/complete', {id: dispatchId, expected_version: bad}, 'worker', 400, 'Completion rejects invalid expected_version ' + JSON.stringify(bad));
  await denied('/dispatches/complete', {id: dispatchId}, 'worker', 400, 'Completion requires an explicit fact version');
  for (const bad of [1.5, '9007199254740992']) await denied('/dispatches/complete', {id: bad, expected_version: 2}, 'worker', 400, 'Completion rejects unsafe or fractional dispatch ID ' + bad);
  for (const field of ['actor_code', 'organization_code', 'verification']) await denied('/dispatches/complete', {id: dispatchId, expected_version: 2, [field]: 'SYNTHETIC-INJECTION'}, 'worker', 400, 'Completion rejects client-owned claim ' + field);
  await denied('/dispatches/complete', {id: dispatchId, expected_version: 1}, 'worker', 409, 'Stale version cannot complete');
  for (const actor of ['reader', 'outsider', 'team', 'admin']) await denied('/dispatches/complete', {id: dispatchId, expected_version: 2}, actor, 403, actor + ' cannot substitute old role or demand permission for verification');
  phase = 'revisions and live permission';
  const allGrants = structuredClone(configuration.grants);
  await publish({grants: configuration.grants.filter(g => !(g.roleCode === 'DELIVERY' && g.resource === 'delivery.verify'))}, 'revoke');
  await denied('/dispatches/complete', {id: dispatchId, expected_version: 2}, 'worker', 403, 'Freshly authenticated account still lacks completion permission after grant removal');
  d = await detail(dispatchId); check(!d.capabilities.permissions.verify && !d.capabilities.can_complete, 'Live capabilities reflect revoked verification grant');
  await publish({grants: allGrants}, 'restore');
  d = await api(save, {...cmd(dispatchId, 2, 'FACT-2'), planned_hours: '1.75'});
  same(d.fact.hours, {estimated: '2.50', planned: '1.75', actual: '1.33', payable: null}, 'Partial update preserves absent quantities without copying another hour kind');
  check(d.version === 3 && d.fact.verification === null, 'Any fact edit clears previous verification');
  await denied('/dispatches/complete', {id: dispatchId, expected_version: 3}, 'worker', 409, 'Edited fact requires fresh verification');
  const replay = await api(save, first);
  check(replay.version === 1 && replay.capabilities.fact_version === 3, 'Idempotent historical response keeps old data and current capability version');
  await denied(save, {...first, actual_minutes: '61'}, 'worker', 409, 'Same request key cannot be reused for another payload');
  await publish({grants: configuration.grants.filter(g => !(g.roleCode === 'DELIVERY' && g.resource === 'delivery.write'))}, 'write-revoke');
  await denied(save, first, 'worker', 403, 'Idempotent replay rechecks current write permission');
  await publish({grants: allGrants}, 'write-restore');
  d = await api(save, {...cmd(dispatchId, 3, 'FACT-NULL'), actual_minutes: null, estimated_hours: null, payable_hours: '0.50'});
  same(d.fact.hours, {estimated: null, planned: '1.75', actual: null, payable: '0.50'}, 'Explicit null clears actual evidence without copying payable or plan');
  check(d.conversion === null, 'Clearing actual minutes also clears conversion evidence');
  await denied(verify, {...cmd(dispatchId, 4, 'VERIFY-NULL'), evidence_code: 'EVIDENCE-NULL'}, 'worker', 409, 'Missing actual minutes cannot be verified');
  d = await api(save, {...cmd(dispatchId, 4, 'FACT-RESTORE'), actual_minutes: '60', estimated_hours: '2.50', planned_hours: '2.00', payable_hours: null});
  d = await api(verify, {...cmd(dispatchId, 5, 'VERIFY-2'), evidence_code: 'EVIDENCE-2'});
  phase = 'legacy source gates';
  row = (await api('/dispatches', undefined, 'team')).find(row => row.id === dispatchId);
  for (const [field, value] of [['project_id', legacy.project_id], ['teacher_id', alternateId], ['teach_date', '2025-01-03']]) await denied('/dispatches', {...row, [field]: value}, 'team', 409, 'Saved fact prevents old edit of ' + field);
  await denied('/dispatches/delete', {id: dispatchId}, 'team', 409, 'Saved fact prevents deleting its source dispatch');
  await api('/dispatches/complete', {id: dispatchId, expected_version: String(d.version)});
  row = (await api('/dispatches', undefined, 'team')).find(row => row.id === dispatchId);
  check(row.status === '已完成' && row.hours === 9, 'Explicit-grant viewer completes while original scheduled hours remain unchanged');
  await api('/dispatches', {...row, material_status: '准备中', remark: 'SYNTHETIC MATERIAL UPDATE'}, 'team');
  row = (await api('/dispatches', undefined, 'team')).find(row => row.id === dispatchId);
  check(row.status === '已完成' && row.material_status === '准备中' && row.remark === 'SYNTHETIC MATERIAL UPDATE', 'Completed course still allows ordinary material and remark edits');
  await denied('/dispatches/complete', {id: dispatchId, expected_version: 5}, 'worker', 409, 'Already-completed shortcut still rejects stale fact version');
  await publish({grants: configuration.grants.filter(g => !(g.roleCode === 'DELIVERY' && g.resource === 'delivery.verify'))}, 'complete-revoke');
  await denied('/dispatches/complete', {id: dispatchId, expected_version: 6}, 'worker', 403, 'Already-completed shortcut still rejects revoked permission');
  await publish({grants: allGrants}, 'complete-restore');
  phase = 'project checked actual totals';
  let state = await api('/projects/check?id=' + projectId, undefined, 'team');
  decimal(state.delivery_hours.actual, '1.33', 'Project check uses reviewed actual 1.33 instead of original schedule 9');
  same({estimated: state.delivery_hours.estimated, planned: state.delivery_hours.planned, payable: state.delivery_hours.payable}, {estimated: '2.50', planned: '2.00', payable: null}, 'Project totals expose each independent hour value and null payable');
  check(state.ready === false && state.blockers.some(b => b.code === 'hours_gap'), 'Verified actual below project plan prevents project completion');
  await denied('/projects/complete', {id: projectId}, 'team', 400, 'Project transition cannot use inflated old schedule hours');
  let project = (await api('/projects', undefined, 'team')).find(p => p.id === projectId);
  await api('/projects', {...project, hours: 1.33}, 'team');
  d = await api(save, {...cmd(dispatchId, 6, 'FACT-COMPLETED-EDIT'), planned_hours: '1.33'});
  state = await api('/projects/check?id=' + projectId, undefined, 'team');
  check(state.ready === false && state.delivery_hours.unverified_completed_count === 1 && state.delivery_hours.actual === null, 'Editing completed fact clears reviewed totals and blocks project completion');
  await denied('/projects/complete', {id: projectId}, 'team', 400, 'Completed but unverified fact blocks project transition');
  await api(verify, {...cmd(dispatchId, d.version, 'VERIFY-COMPLETED'), evidence_code: 'EVIDENCE-COMPLETED'});
  const pending = await api('/dispatches', {...dispatchBody, subject: 'SYNTHETIC PENDING', teach_date: '2025-01-04'}, 'team');
  state = await api('/projects/check?id=' + projectId, undefined, 'team');
  check(state.ready === false && state.delivery_hours.pending_count === 1, 'Unfinished course blocks project completion even when verified actual meets plan');
  await denied('/projects/complete', {id: projectId}, 'team', 400, 'Pending course prevents project transition');
  await api('/dispatches/send', {id: pending}, 'team'); await api('/dispatches/confirm', {id: pending, accept: 0, reason: 'SYNTHETIC REFUSAL'}, 'team');
  state = await api('/projects/check?id=' + projectId, undefined, 'team');
  check(state.ready === true && state.delivery_hours.dispatch_count === 1 && state.delivery_hours.pending_count === 0, 'Rejected schedule is excluded and reviewed actual can satisfy plan');
  await denied(save, {...cmd(pending, 0, 'REJECTED-FACT'), actual_minutes: '60'}, 'worker', 409, 'Rejected schedule cannot acquire delivery facts');
  await api('/projects/complete', {id: projectId}, 'team');
  row = (await api('/dispatches', undefined, 'team')).find(row => row.id === dispatchId);
  await api('/dispatches', {...row, material_status: '已就绪', remark: 'SYNTHETIC AFTER PROJECT COMPLETE'}, 'team');
  row = (await api('/dispatches', undefined, 'team')).find(row => row.id === dispatchId);
  check(row.remark === 'SYNTHETIC AFTER PROJECT COMPLETE' && row.material_status === '已就绪' && row.status === '已完成', 'Completed project still permits original material and remark maintenance');
  for (const [field, value] of [['teacher_id', alternateId], ['teach_date', '2025-01-03'], ['hours', 10]]) await denied('/dispatches', {...row, [field]: value}, 'team', field === 'hours' ? 400 : 409, 'Completed project ordinary edit still protects ' + field);
  await denied(save, {...cmd(dispatchId, 8, 'CLOSED-PROJECT-FACT'), actual_minutes: '90'}, 'worker', 409, 'Completed project no longer permits changing reviewed delivery facts');
  await denied('/projects/archive', {id: projectId}, 'team', 409, 'Controlled project archive is blocked until formal payment integration exists');
  state = await api('/projects/check?id=' + projectId + '&action=archive', undefined, 'team');
  check(state.ready === false && state.delivery_hours.payment_integration === 'NOT_CONFIGURED', 'Archive check exposes missing formal payment integration');
  phase = 'old fee gates and legacy compatibility';
  const historicFees = (await api('/fees', undefined, 'team')).filter(f => f.project_id === historic.project_id);
  check(historicFees.length === 2 && historicFees.every(f => f.delivery_controlled === true), 'Both paid and pending old fee rows identify their controlled project');
  for (const pid of [projectId, historic.project_id]) await denied('/fees/calc', {project_id: pid}, 'team', 409, 'Old calculator cannot replace controlled project fees');
  await denied('/fees', {project_id: projectId, teacher_id: teacherId, hours: 1, rate: 100, amount: 100}, 'team', 409, 'Old fee create cannot inject controlled project settlement');
  for (const fee of historicFees) {
    await denied('/fees/pay', {id: fee.id}, 'team', 409, 'Old pay gate precedes ' + fee.status + ' shortcut');
    await denied('/fees/delete', {id: fee.id}, 'team', 409, 'Old delete cannot remove ' + fee.status + ' controlled history');
    await denied('/fees', {...fee, remark: 'SYNTHETIC ALTER'}, 'team', 409, 'Old fee save cannot alter controlled history');
    await denied('/fees', {...fee, project_id: legacy.project_id}, 'team', 409, 'Old fee cannot move out of controlled project');
  }
  same((await api('/fees', undefined, 'team')).filter(f => f.project_id === historic.project_id), historicFees, 'All denied fee operations leave exact historical rows unchanged');
  await api('/dispatches/complete', {id: legacy.id}, 'admin');
  const oldRows = await api('/fees/calc', {project_id: legacy.project_id}, 'admin');
  check(oldRows.length === 1 && oldRows[0].hours === 2 && oldRows[0].amount === 200, 'Unmigrated old project retains legacy calculation');
  let oldFee = (await api('/fees', undefined, 'admin')).find(f => f.id === oldRows[0].id);
  await denied('/fees', {...oldFee, project_id: historic.project_id}, 'team', 409, 'Old fee cannot move into controlled project');
  await api('/fees', {...oldFee, remark: 'SYNTHETIC OLD EDIT'}, 'admin');
  await api('/fees/delete', {id: oldFee.id}, 'admin');
  const regenerated = await api('/fees/calc', {project_id: legacy.project_id}, 'admin');
  await api('/fees/pay', {id: regenerated[0].id}, 'admin'); await api('/fees/pay', {id: regenerated[0].id}, 'admin');
  await api('/projects/complete', {id: legacy.project_id}, 'admin'); await api('/projects/archive', {id: legacy.project_id}, 'admin');
  check((await api('/projects', undefined, 'admin')).find(p => p.id === legacy.project_id)?.status === '已归档', 'Old save/delete/pay replay and project completion/archive remain compatible');
  phase = 'closed formal settlement and persistence';
  for (const operation of ['configure', 'confirm', 'correct']) {
    for (const method of ['GET', 'POST', 'PUT']) await denied(route + '/' + operation, method === 'POST' ? {...cmd(dispatchId, 8, 'FORMAL-' + operation), expected_config_version: null} : undefined, 'worker', 409, operation + ' stays closed despite explicit formal grant (' + method + ')', {method});
    await denied(route + '/' + operation, {}, 'anonymous', 401, operation + ' still requires authentication');
  }
  const beforeRestart = await detail(dispatchId);
  await api('/logout', {}, 'reader'); await denied(route + '?dispatch_id=' + dispatchId, undefined, 'reader', 401, 'Logged-out token cannot access delivery');
  await stop();
  const counts = await inspect(data);
  check(counts.m05_delivery_facts === 1 && counts.m05_fact_revisions === 8 && counts.m05_requests === 8, 'Only successful M05 revisions and request ledger entries persist');
  for (const table of ['m05_policy_versions', 'm05_settlement_configs', 'm05_settlement_snapshots']) check(counts[table] === 0, 'Closed formal host leaves ' + table + ' empty');
  check(counts.legacy_m05_facts === 0, 'Legacy operations never silently migrate old facts');
  same(counts.historical_fees.map(f => ({status: f.status, hours: f.hours, amount: f.amount})), [{status: '待发放', hours: 1, amount: 100}, {status: '已发放', hours: 1, amount: 100}], 'Offline inspection preserves paid and pending historical fee amounts');
  await boot(data, false); await login('worker');
  same(await detail(dispatchId), beforeRestart, 'Real Main restart retains exact facts, verification and current capability state');
  return data;
}
runChecks().catch(error => { fatal = String(error.message || error).replaceAll(password, '[redacted]'); process.exitCode = 1; }).finally(async () => {
  await stop();
  const summary = {suite: 'delivery-http', checks, failures, ...(fatal ? {error: fatal} : {}), production_touched: false, notifications_sent: false, artifacts: own};
  fs.writeFileSync(path.join(own, 'result.json'), JSON.stringify(summary, null, 2), {mode: 0o600}); console.log(JSON.stringify(summary));
  if (failures.length || fatal) process.exitCode = 1;
});
