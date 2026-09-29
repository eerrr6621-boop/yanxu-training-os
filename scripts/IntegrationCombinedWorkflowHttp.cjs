'use strict';
// Real Main/Api and fresh synthetic loopback H2. No runtime test endpoint or application data access.
const fs = require('node:fs'), os = require('node:os'), path = require('node:path'), net = require('node:net');
const crypto = require('node:crypto'), assert = require('node:assert/strict'), { spawn } = require('node:child_process');
const { workflowConfiguration } = require('./IntegrationWorkflowHttpFixture.cjs');
const args = {};
for (let i = 2; i < process.argv.length; i += 2) {
  assert.ok(['--java', '--classes'].includes(process.argv[i]) && process.argv[i + 1], 'Use --java PATH --classes ISOLATED_DIRECTORY');
  args[process.argv[i]] = process.argv[i + 1];
}
assert.ok(args['--java'] && args['--classes'], 'Explicit Java and isolated classes required');
const repo = path.resolve(__dirname, '..'), classes = path.resolve(args['--classes']);
assert.ok(classes !== path.join(repo, 'out') && !classes.startsWith(path.join(repo, 'out') + path.sep), 'No shared out directory');
assert.ok(fs.existsSync(path.join(classes, 'com/training/Main.class')), 'Compile current sources into isolated classes first');
const own = fs.mkdtempSync(path.join(os.tmpdir(), 'yanxu-combined-http-')), data = path.join(own, 'data');
const password = crypto.randomBytes(24).toString('hex'), tokens = {}, failures = [];
let child, port, checks = 0, configuration, version = 0, fatal;
const actors = ['owner', 'leader', 'bp', 'team', 'outsider'];
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(value, label) { checks++; if (!value) failures.push(label); }
async function request(route, body, actor = 'owner') {
  const r = await fetch(`http://127.0.0.1:${port}/api${route}`, {
    method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json', ...(tokens[actor] ? { 'X-Token': tokens[actor] } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(8000),
  });
  return { status: r.status, envelope: await r.json() };
}
async function api(route, body, actor) {
  const r = await request(route, body, actor); assert.equal(r.status, 200, `${route}: ${r.envelope.msg}`); assert.equal(r.envelope.code, 0); return r.envelope.data;
}
async function denied(route, body, actor, status, label) {
  const r = await request(route, body, actor); check(r.status === status && r.envelope.code === status, `${label}: ${r.status}/${r.envelope.code}`);
}
async function login(actor) { tokens[actor] = (await api('/login', { username: actor === 'owner' ? 'admin' : 'synthetic-' + actor, password }, 'anonymous')).token; }
async function freshLogins() { for (const actor of actors) await login(actor); }
async function boot() {
  const listener = net.createServer(); await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve)); port = listener.address().port; await new Promise(resolve => listener.close(resolve));
  child = spawn(args['--java'], ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password,
    '-Ddata.dir=' + data, '-Dsemantic.port=', '-Dsemantic.token=', '-cp', classes + path.delimiter + path.join(repo, 'lib', '*'), 'com.training.Main', String(port)],
    { cwd: repo, env: { ...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: '' }, stdio: 'ignore' });
  for (let i = 0; i < 100; i++) { assert.equal(child.exitCode, null, 'Isolated Main stopped'); try { await login('owner'); return; } catch {} await pause(100); }
  throw new Error('Isolated Main startup timeout');
}
async function stop() {
  if (!child || child.exitCode !== null) return;
  const running = child;
  await new Promise(resolve => { running.once('exit', resolve); running.kill('SIGTERM'); const timer = setTimeout(() => { if (running.exitCode === null) running.kill('SIGKILL'); }, 3000); timer.unref(); });
}
async function publish(c) {
  const next = { ...structuredClone(c), version: 'synthetic-combined-http-' + (++version) };
  await api('/organization/config', { expectedVersion: configuration?.version || null, configuration: next }); configuration = next; await freshLogins();
}
const command = (d, request_id) => ({ id: d.id, expected_version: d.version, request_id });
const action = (d, action, requestId) => ({ action, expectedVersion: d.approval.version, expectedStage: d.approval.stage, requestId, comment: 'SYNTHETIC REVIEW' });
const route = d => '/approvals/tasks/' + d.id + '/actions';
const current = async d => (await api('/demands/workflow?id=' + d.id)).workflow;
const draft = async key => (await api('/demands/draft', { request_id: key, title: 'SYNTHETIC COMBINED REVIEW', unit: 'SYNTHETIC CUSTOMER',
  business_path: 'direct', organization_code: '001', internal_contact_code: 'P1', category_text: '业务技能', delivery_mode_text: '线上', period_text: '全天',
  duration_minutes: '60', participant_count: '1', budget_amount: '0', objectives: 'SYNTHETIC', content: 'SYNTHETIC' })).workflow;
async function submitted(key) { const d = await draft(key); return (await api('/demands/submit', command(d, key + '-submit'))).workflow; }
async function run() {
  await boot();
  const ids = [(await api('/me')).uid];
  for (const actor of actors.slice(1)) ids.push(await api('/users', { username: 'synthetic-' + actor, name: 'SYNTHETIC ' + actor, role: actor === 'team' ? 'manager' : 'viewer', status: 1, password }));
  const c = workflowConfiguration(ids); c.people[0].bpPersonCode = 'P2'; c.people[1].roleCodes = ['LEADER', 'BP'];
  c.people[1].responsibleOrganizationsByRole = { LEADER: ['001'], BP: ['001'] };
  c.combinedApprovals = [{ organizationCode: '001', personCode: 'P2', leaderRoleCode: 'LEADER', bpRoleCode: 'BP', evidenceRef: 'SYNTHETIC-COMBINED-EVIDENCE' }];
  for (const role of ['LEADER', 'BP']) c.grants.push({ ruleId: 'notify-' + role, roleCode: role, resource: 'notifications.read', action: 'VIEW', effect: 'ALLOW', scope: 'NAMED_ORGS', organizationCodes: ['001'] });
  c.grants.push({ ruleId: 'audit', roleCode: 'FILLER', resource: 'notifications.audit', action: 'VIEW', effect: 'ALLOW', scope: 'OWN_ORG', organizationCodes: [] });
  await publish(c); const original = structuredClone(configuration);
  let d = await submitted('combined');
  check(d.approval.reviewMode === 'SINGLE_EXPLICIT' && d.approval.history.length === 1, 'Real HTTP submission freezes explicit combined mode');
  check(d.status_label === '待负责人及BP审批（兼任）', 'Host status explains combined review');
  const tasks = (await api('/approvals/tasks', undefined, 'leader')).tasks;
  check(tasks.some(t => t.id === d.id && t.actions.some(a => a.action === 'APPROVE_COMBINED' && a.enabled)), 'Real task list exposes only authorized combined action');
  let inbox = await api('/notifications', undefined, 'leader');
  check(inbox.total === 1 && inbox.items[0].actionable, 'One actionable original submitted notice reaches reviewer');
  await denied(route(d), action(d, 'APPROVE_COMBINED', 'anonymous'), 'anonymous', 401, 'Real authentication required');
  await denied(route(d), action(d, 'APPROVE_COMBINED', 'filler'), 'owner', 403, 'Filler cannot approve own request');
  await denied(route(d), action(d, 'APPROVE_COMBINED', 'not-assignee'), 'bp', 403, 'Different BP cannot impersonate frozen combined reviewer');
  await denied(route(d), action(d, 'APPROVE_COMBINED', 'outside'), 'outsider', 403, 'Wrong institution has no action access');
  await denied(route(d), action(d, 'APPROVE', 'old-button'), 'leader', 422, 'Ordinary approve cannot implicitly confirm both duties');
  await denied(route(d), { ...action(d, 'APPROVE_COMBINED', 'inject'), actorId: 'P2' }, 'leader', 422, 'Client actor claim rejected');
  await denied(route(d), { ...action(d, 'APPROVE_COMBINED', 'inject-policy'), policy: { reviewMode: 'SINGLE_EXPLICIT' } }, 'leader', 422, 'Client policy rejected');
  const approve = action(d, 'APPROVE_COMBINED', 'approve-once');
  const race = await Promise.all([api(route(d), approve, 'leader'), api(route(d), approve, 'leader')]); d = race[0].workflow;
  check(race.every(r => r.workflow.approval.version === 2 && r.workflow.approval.status === 'READY_FOR_TEAM'), 'Concurrent identical HTTP request yields one committed transition');
  check(d.approval.history.length === 2 && d.approval.history[1].actorId === 'P2' && d.approval.history[1].responsibilities.join(',') === 'LEADER,BP', 'Single event records one actor and both duties');
  check((await api('/notifications', undefined, 'bp')).total === 0, 'No extra BP notice');
  inbox = await api('/notifications', undefined, 'leader'); check(inbox.total === 1 && !inbox.items[0].actionable, 'Original combined todo resolves after approval');
  const diagnostics = await api('/notifications/diagnostics');
  check(diagnostics.items.filter(i => i.recordId === String(d.id) && i.reason === 'TEAM_RECIPIENTS_UNCONFIGURED').length === 1, 'Exactly one frozen team-ready event; no invented team recipient');
  const frozenApproval = value => [value.status, value.stage, value.version, value.dataRevision, value.round, value.policyVersion, value.reviewMode, value.participants, value.history];
  const persisted = frozenApproval(d.approval); await stop(); await boot(); await freshLogins(); d = await current(d);
  assert.deepStrictEqual(frozenApproval(d.approval), persisted, 'Real process restart preserves combined snapshot and event duties'); checks++;
  await api(route(d), approve, 'leader'); check((await current(d)).approval.history.length === 2, 'Replay after restart creates no new history');
  await publish(original); d = await current(d); check(!d.approval.paused && d.queue === 'ready', 'Directory version-only update preserves frozen validity');
  const changed = structuredClone(original); changed.combinedApprovals[0].evidenceRef = 'SYNTHETIC-CHANGED-EVIDENCE'; await publish(changed);
  let paused = await current(d); check(paused.approval.paused && paused.approval.history.length === 2 && paused.approval.actions.every(a => !a.enabled) && !paused.actions.accept, 'Changed evidence preserves history and disables further actions');
  await denied('/demands/accept', { ...command(paused, 'paused-accept'), team_code: '900' }, 'team', 409, 'Changed evidence prevents team acceptance');
  await denied(route(d), approve, 'leader', 409, 'Idempotent replay rechecks current evidence');
  await publish(original); let pending = await submitted('pending');
  const revoked = structuredClone(original); revoked.grants = revoked.grants.filter(g => g.ruleId !== 'review-LEADER'); await publish(revoked);
  paused = await current(pending); check(paused.approval.paused && paused.approval.actions.every(a => !a.enabled), 'One remaining duty grant cannot authorize both');
  await denied(route(pending), action(pending, 'APPROVE_COMBINED', 'revoked'), 'leader', 409, 'Revoked one-duty grant blocks action');
  await denied('/approvals/tasks/' + pending.id + '/remind', { expectedVersion: pending.approval.version, requestId: 'paused-remind' }, 'owner', 409, 'Paused combined relationship prevents reminder');
  const badScope = structuredClone(original); badScope.version = 'synthetic-invalid-scope'; badScope.people[1].responsibleOrganizationsByRole.LEADER = ['002'];
  await denied('/organization/config', { expectedVersion: configuration.version, configuration: badScope }, 'owner', 400, 'Wrong leader institution cannot publish combined evidence');
  const different = structuredClone(original); different.people[0].bpPersonCode = 'P3'; await publish(different);
  check((await current(pending)).approval.paused, 'Changed filler relations leave old combined flow paused');
  let normal = await submitted('normal'); check(normal.approval.reviewMode === 'SEQUENTIAL', 'New ordinary two-person flow stays sequential');
  normal = (await api(route(normal), action(normal, 'APPROVE', 'normal-leader'), 'leader')).workflow;
  check(normal.approval.status === 'BP_PENDING', 'Ordinary leader approval does not skip BP');
  normal = (await api(route(normal), action(normal, 'APPROVE', 'normal-bp'), 'bp')).workflow;
  check(normal.approval.status === 'READY_FOR_TEAM' && normal.approval.history.length === 3, 'Ordinary two approvals retain separate real events');
  await publish(original); pending = await current(pending);
  pending = (await api(route(pending), action(pending, 'RETURN', 'return-combined'), 'leader')).workflow;
  pending = (await api(route(pending), action(pending, 'RESUBMIT', 'resubmit-combined'))).workflow;
  check(pending.approval.status === 'LEADER_PENDING' && pending.approval.round === 2 && pending.approval.reviewMode === 'SINGLE_EXPLICIT', 'Return resubmit restarts explicit combined review');
  d = await current(d); d = (await api('/demands/accept', { ...command(d, 'accepted'), team_code: '900' }, 'team')).workflow;
  check(d.project_id > 0 && d.queue === 'accepted', 'Separate team acceptance succeeds from valid one-action evidence');
}
run().catch(error => { fatal = String(error.message).replaceAll(password, '[redacted]'); process.exitCode = 1; }).finally(async () => {
  await stop(); const summary = { suite: 'combined-workflow-http', checks, failures, ...(fatal ? { error: fatal } : {}), production_touched: false, notifications_sent: false, artifacts: own };
  fs.writeFileSync(path.join(own, 'result.json'), JSON.stringify(summary, null, 2), { mode: 0o600 }); console.log(JSON.stringify(summary)); if (failures.length || fatal) process.exitCode = 1;
});
