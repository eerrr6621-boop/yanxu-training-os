'use strict';
// Actual Main/Api HTTP, synthetic identities and fresh H2 only. No app/data, production or notification channel.
const fs = require('node:fs'), os = require('node:os'), path = require('node:path'), net = require('node:net');
const crypto = require('node:crypto'), assert = require('node:assert/strict');
const {spawn, execFile} = require('node:child_process'), {promisify} = require('node:util');
const {workflowConfiguration} = require('./IntegrationWorkflowHttpFixture.cjs');
const run = promisify(execFile), args = {};
for (let i = 2; i < process.argv.length; i += 2) {
  assert.ok(['--java', '--classes'].includes(process.argv[i]) && process.argv[i + 1], 'Use --java PATH --classes ISOLATED_DIRECTORY');
  args[process.argv[i]] = process.argv[i + 1];
}
assert.ok(args['--java'] && args['--classes'], 'Explicit Java and isolated compiled classes required');
const repo = path.resolve(__dirname, '..'), classes = path.resolve(args['--classes']);
assert.ok(!classes.startsWith(path.join(repo, 'out')), 'Use isolated compiled classes');
assert.ok(fs.existsSync(path.join(classes, 'com/training/IntegrationReportsHttpFixture.class')), 'Compile the scripts-only fixture first');
const own = fs.mkdtempSync(path.join(os.tmpdir(), 'yanxu-reports-http-'));
const data = path.join(own, 'data'); fs.mkdirSync(data);
const password = crypto.randomBytes(24).toString('hex'), tokens = {}, failures = [];
const env = {...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: ''};
let child, port, checks = 0, phase = 'setup', fatal, configuration, serial = 0;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(ok, label) { checks++; if (!ok) failures.push(`${phase}: ${label}`); }
function same(actual, expected, label) { let ok = true; try { assert.deepEqual(actual, expected); } catch { ok = false; } check(ok, label); }
function options() { return ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password,
  '-Dintegration.reports.root=' + own, '-Ddata.dir=' + data, '-Dsemantic.port=', '-Dsemantic.token=', '-cp', classes + path.delimiter + path.join(repo, 'lib', '*')]; }
async function request(route, body, actor = 'analyst', method) {
  const headers = tokens[actor] ? {'X-Token': tokens[actor]} : {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(`http://127.0.0.1:${port}/api${route}`, {method: method || (body === undefined ? 'GET' : 'POST'), headers,
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(5000)});
  const text = await response.text(); const envelope = text ? JSON.parse(text) : null;
  return {status: response.status, headers: response.headers, envelope, text};
}
async function api(route, body, actor = 'analyst') {
  const r = await request(route, body, actor);
  assert.equal(r.status, 200, `${phase}: ${route}: HTTP ${r.status}: ${r.envelope?.msg}`);
  assert.equal(r.envelope?.code, 0, `${phase}: ${route}: ${r.envelope?.msg}`); return r.envelope.data;
}
async function denied(route, body, actor, status, label, method) {
  const r = await request(route, body, actor, method);
  check(r.status === status && (method === 'HEAD' ? r.text === '' : r.envelope?.code === status), `${label} (HTTP ${r.status}: ${r.envelope?.msg})`);
  return r;
}
async function login(actor, username = 'synthetic-reports-' + actor) { tokens[actor] = (await api('/login', {username, password}, 'anonymous')).token; }
async function boot(fixture = false) {
  Object.keys(tokens).forEach(key => delete tokens[key]);
  const listener = net.createServer();
  await new Promise((resolve, reject) => { listener.once('error', reject); listener.listen(0, '127.0.0.1', resolve); });
  port = listener.address().port; await new Promise(resolve => listener.close(resolve));
  child = spawn(args['--java'], [...options(), fixture ? 'com.training.IntegrationReportsHttpFixture' : 'com.training.Main', String(port)], {cwd: repo, env, stdio: 'ignore'});
  let launchError; child.once('error', error => { launchError = error; });
  for (let i = 0; i < 150; i++) {
    if (launchError) throw launchError;
    assert.equal(child.exitCode, null, 'Isolated service exited before login');
    try { await login('admin', 'admin'); return; } catch {}
    await pause(100);
  }
  throw new Error('Isolated reports service did not become ready');
}
async function stop() {
  const running = child; child = null;
  if (!running || running.exitCode !== null || running.signalCode !== null) return;
  await new Promise(resolve => {
    const timer = setTimeout(() => { if (running.exitCode === null && running.signalCode === null) running.kill('SIGKILL'); }, 2000);
    running.once('exit', () => { clearTimeout(timer); resolve(); }); running.kill('SIGTERM');
  });
}
async function offline(operation) {
  assert.ok(!child, 'Offline fixture cannot run against an active service');
  const result = await run(args['--java'], [...options(), 'com.training.IntegrationReportsHttpFixture', operation], {cwd: repo, env, timeout: 20000, maxBuffer: 65536});
  return operation === 'inspect' ? JSON.parse(result.stdout.trim()) : null;
}
const params = (changes = {}) => new URLSearchParams({start: '2025-01-01', end: '2025-01-31', date_basis: 'TEACHING', ...changes}).toString();
const query = (changes = {}) => '/management-reports?' + params(changes);
const download = (snapshot, changes = {}) => '/management-reports/export?' + params({organizations: snapshot.organizations.join(','), snapshot_version: snapshot.snapshot_version, ...changes});
// Configuration publication is an explicit authentication boundary, never an API retry policy.
async function renewPublishedSessions(label, actors = ['admin', 'leader', 'bp', 'team', 'outsider', 'reporter', 'analyst', 'filler']) {
  for (const actor of actors) {
    if (!tokens[actor]) continue; // Some identities have not logged in since a process restart.
    await denied('/me', undefined, actor, 401, label + ' invalidates the old ' + actor + ' token');
    await login(actor, actor === 'admin' ? 'admin' : undefined);
  }
}
async function publish(changes, suffix) {
  const expectedVersion = configuration.version;
  configuration = {...structuredClone(configuration), ...changes, version: 'synthetic-reports-http-' + suffix};
  await api('/organization/config', {expectedVersion, configuration}, 'admin');
  await renewPublishedSessions(suffix);
}
async function identities() {
  const ids = [(await api('/me', undefined, 'admin')).uid];
  for (const [actor, role] of [['leader', 'viewer'], ['bp', 'viewer'], ['team', 'manager'], ['outsider', 'viewer'], ['reporter', 'viewer'], ['analyst', 'viewer'], ['filler', 'manager'], ['unbound', 'viewer']]) {
    const id = await api('/users', {username: 'synthetic-reports-' + actor, name: 'SYNTHETIC ' + actor, role, status: 1, password}, 'admin');
    ids.push(id); await login(actor);
  }
  configuration = workflowConfiguration(ids.slice(0, 5)); configuration.version = 'synthetic-reports-http-v1';
  for (const person of configuration.people) if (['P2', 'P3', 'P4'].includes(person.personCode)) person.responsibleOrganizationCodes.push('999');
  for (const grant of configuration.grants) if (grant.scope === 'NAMED_ORGS' && grant.organizationCodes.includes('001')) grant.organizationCodes.push('999');
  const optional = {required: false, allowedTargetRoles: ['REPORTER', 'ANALYST'], targetMustCoverOrganization: false, allowSelf: false};
  for (const [personCode, role, organizationCode, index] of [['P6', 'REPORTER', '001', 5], ['P7', 'ANALYST', '900', 6], ['P8', 'FILLER', '999', 7]]) {
    configuration.people.push({personCode, roleCodes: [role], organizationCode, responsibleOrganizationCodes: [], leaderPersonCode: role === 'FILLER' ? 'P2' : null, bpPersonCode: role === 'FILLER' ? 'P3' : null, enabled: true});
    configuration.accountBindings.push({accountId: ids[index], personCode, enabled: true});
  }
  for (const roleCode of ['REPORTER', 'ANALYST']) { configuration.roleCodes.push(roleCode); configuration.relations.push({roleCode, leader: optional, bp: optional}); }
  for (const [roleCode, resource, action, organizations] of [['REPORTER', 'reports.read', 'VIEW', ['001']], ['ANALYST', 'reports.read', 'VIEW', ['001', '999']], ['ANALYST', 'reports.export', 'EXPORT', ['001', '999']]])
    configuration.grants.push({ruleId: roleCode + '-' + resource, roleCode, resource, action, effect: 'ALLOW', scope: 'NAMED_ORGS', organizationCodes: organizations});
  await api('/organization/config', {expectedVersion: null, configuration}, 'admin');
  await renewPublishedSessions('Initial binding');
}
async function project(actor, org, person) {
  const tag = 'REPORT-' + (++serial);
  let d = (await api('/demands/draft', {request_id: tag + '-DRAFT', title: 'SYNTHETIC ' + org + ' REPORT PROJECT', unit: 'SYNTHETIC CUSTOMER', business_path: 'direct', organization_code: org, internal_contact_code: person,
    category_text: '业务技能', delivery_mode_text: '线上', period_text: '全天', duration_minutes: '90', participant_count: '1', budget_amount: '0', objectives: 'SYNTHETIC', content: 'SYNTHETIC', teacher_req: '', remark: 'SYNTHETIC'}, actor)).workflow;
  d = (await api('/demands/submit', {id: d.id, expected_version: d.version, request_id: tag + '-SUBMIT'}, actor)).workflow;
  for (const approver of ['leader', 'bp']) d = (await api('/approvals/tasks/' + d.id + '/actions', {action: 'APPROVE', expectedVersion: d.approval.version, expectedStage: d.approval.stage, requestId: tag + '-' + approver, comment: 'SYNTHETIC'}, approver)).workflow;
  d = (await api('/demands/accept', {id: d.id, expected_version: d.version, request_id: tag + '-ACCEPT', team_code: '900'}, 'team')).workflow;
  const p = (await api('/projects', undefined, 'team')).find(row => row.id === d.project_id);
  await api('/projects', {...p, hours: 10, amount: 0, owner: 'SYNTHETIC', start_date: '2025-01-01', end_date: '2025-01-31', delivery_mode: '线上'}, 'team');
  await api('/projects/start', {id: p.id}, 'team'); return p.id;
}
async function teaching(projectId, teacherId, subject, day, quantities, disposition = 'complete') {
  const id = await api('/dispatches', {project_id: projectId, teacher_id: teacherId, subject, teach_date: day, start_time: '09:00', end_time: '10:30', hours: 9, status: '待发送', material_status: '已就绪', remark: 'SYNTHETIC'}, 'team');
  if (disposition === 'pending') return id;
  await api('/dispatches/send', {id}, 'team');
  await api('/dispatches/confirm', {id, accept: disposition === 'rejected' ? 0 : 1, reason: 'SYNTHETIC'}, 'team');
  if (disposition === 'rejected') return id;
  let fact = await api('/delivery-settlement/save', {dispatch_id: id, expected_version: 0, request_id: 'SAVE-' + id, ...quantities}, 'team');
  fact = await api('/delivery-settlement/verify', {dispatch_id: id, expected_version: fact.version, request_id: 'VERIFY-' + id, evidence_code: 'SYNTHETIC-' + id}, 'team');
  await api('/dispatches/complete', {id, expected_version: fact.version}, 'team');
  if (disposition === 'unverified') await api('/delivery-settlement/save', {dispatch_id: id, expected_version: fact.version, request_id: 'UNVERIFY-' + id, ...quantities}, 'team');
  return id;
}
function minor(value) {
  assert.match(value, /^\d+(?:\.\d{1,2})?$/); const [whole, fraction = ''] = value.split('.');
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
}
function samePopulation(view) {
  const categories = ['estimated', 'planned', 'actual', 'payable'];
  for (const [label, groups] of [['total', [{dispatch_count: view.included_count, hours: view.totals, selected: view.details}]],
    ['teacher', view.teacher_ranking.map(row => ({...row, selected: view.details.filter(d => d.teacher_id === row.teacher_id)}))],
    ['organization', view.organization_summary.map(row => ({...row, selected: view.details.filter(d => d.organization_code === row.organization_code)}))]]) {
    for (const group of groups) {
      check(group.dispatch_count === group.selected.length, label + ' count uses selected details');
      for (const kind of categories) {
        const values = group.selected.map(d => d.hours[kind]), missing = values.filter(v => v === null).length;
        const sum = values.filter(v => v !== null).reduce((total, v) => total + minor(v), 0n), total = group.hours[kind];
        check(total.missing_records === missing && total.complete === (missing === 0) && minor(total.known_subtotal) === sum &&
          (missing ? total.value === null : typeof total.value === 'string' && minor(total.value) === sum), label + ' ' + kind + ' uses same population and preserves unknown quantities');
      }
    }
  }
}
async function runChecks() {
  await boot(true);
  await denied(query(), undefined, 'anonymous', 401, 'Reports require real authentication');
  await denied(query(), undefined, 'admin', 403, 'Unconfigured administrator has no report role fallback');
  await identities();
  await denied(query(), undefined, 'admin', 403, 'Configured workflow administrator still has no implicit report permission');
  await denied(query(), undefined, 'unbound', 403, 'Unbound session cannot read reports');
  const p1 = await project('admin', '001', 'P1'), p2 = await project('filler', '999', 'P8');
  const teachers = [];
  for (const name of ['A', 'SAME', 'SAME', 'D']) teachers.push(await api('/teachers', {name: 'SYNTHETIC REPORT ' + name, base_province: '浙江', base_city: '杭州', teacher_level: '讲师', fee_rate: null}, 'admin'));
  const a = await teaching(p1, teachers[0], 'SYNTHETIC JANUARY START', '2025-01-01', {actual_minutes: '90', estimated_hours: '3.00', planned_hours: '2.00', payable_hours: '1.00'});
  const b = await teaching(p1, teachers[1], 'SYNTHETIC JANUARY END', '2025-01-31', {actual_minutes: '45', estimated_hours: null, planned_hours: '1.25', payable_hours: '0'});
  const c = await teaching(p2, teachers[2], 'SYNTHETIC OTHER ORGANIZATION', '2025-01-15', {actual_minutes: '45', estimated_hours: '1.00', planned_hours: null, payable_hours: null});
  const zero = await teaching(p2, teachers[3], 'SYNTHETIC ZERO MINUTES', '2025-01-20', {actual_minutes: '0', estimated_hours: '0', planned_hours: '0', payable_hours: '0'});
  const pending = await teaching(p1, teachers[0], 'SYNTHETIC PENDING', '2025-01-12', {}, 'pending');
  const rejected = await teaching(p1, teachers[0], 'SYNTHETIC REJECTED', '2025-01-13', {}, 'rejected');
  const unverified = await teaching(p1, teachers[0], 'SYNTHETIC UNVERIFIED', '2025-01-14', {actual_minutes: '45'}, 'unverified');
  const outside = await teaching(p2, teachers[0], 'SYNTHETIC OUTSIDE MONTH', '2024-12-31', {actual_minutes: '450', estimated_hours: '10', planned_hours: '10', payable_hours: '10'});
  const invalid = await teaching(p1, teachers[0], 'SYNTHETIC CORRUPT REVISION', '2025-01-17', {actual_minutes: '45'});
  const moved = await teaching(p1, teachers[0], 'SYNTHETIC DATE DRIFT', '2025-01-18', {actual_minutes: '45'});
  const historic = await api('/dispatches', undefined, 'team');
  const named = name => historic.find(row => row.subject === name).id;
  const missing = named('SYNTHETIC HISTORICAL FACT MISSING'), undated = named('SYNTHETIC HISTORICAL DATE UNKNOWN'), legacy = named('SYNTHETIC UNMIGRATED HOURS'), future = named('SYNTHETIC HISTORICAL FUTURE');
  await stop(); await offline('corrupt'); const before = await offline('inspect');
  await boot(); for (const actor of ['reporter', 'analyst', 'team', 'outsider', 'unbound']) await login(actor);
  phase = 'read-only protocol and scope';
  for (const actor of ['admin', 'outsider', 'unbound']) await denied(query(), undefined, actor, 403, actor + ' cannot infer report access from old role or scope');
  await denied(query(), {}, 'analyst', 405, 'POST cannot mutate reports');
  await denied(query(), undefined, 'analyst', 405, 'HEAD is not a report query', 'HEAD');
  await denied(query(), undefined, 'analyst', 405, 'PUT cannot invoke reports', 'PUT');
  await denied('/management-reports/export?' + params(), {}, 'analyst', 405, 'POST cannot export');
  await denied(query({date_basis: 'PAYMENT'}), undefined, 'analyst', 409, 'Payment basis is unavailable without payment source');
  for (const filter of [{start: '2025-02-30'}, {start: '2025-02-01'}, {organizations: ''}, {organizations: '001,001'}, {organizations: '001, 999'}, {hourBasis: 'actual'}, {snapshot_version: '0'.repeat(64)}])
    await denied(query(filter), undefined, 'analyst', 400, 'Invalid query is rejected: ' + JSON.stringify(filter));
  await denied(query({organizations: '001,999'}), undefined, 'reporter', 403, 'Mixed authorized and unauthorized organizations are rejected as a whole');
  await denied(query({organizations: '001,002'}), undefined, 'analyst', 403, 'Explicit unauthorized organization cannot be silently removed');
  const local = await api(query(), undefined, 'reporter');
  same(local.organizations, ['001'], 'All organizations means only reporter authorized institutions');
  same(local.available_organizations, ['001'], 'Institution selector reveals only authorized codes');
  same(local.details.map(d => d.dispatch_id), [a, b], 'Reports-only viewer reads same-institution delivery without demand or delivery grants');
  check(local.permissions.read === true && local.permissions.export === false, 'View grant does not grant export');
  check(!(await api('/projects', undefined, 'reporter')).some(p => [p1, p2].includes(p.id)), 'Reports-only viewer has no workflow-project visibility');
  check(local.details.every(d => d.organization_code === '001') && local.excluded.every(d => d.organization_code === '001'), 'No unauthorized institution appears in detail or excluded rows');
  let view = await api(query());
  phase = 'same-batch exact statistics';
  same(view.details.map(d => d.dispatch_id), [a, b, c, zero], 'Both date boundaries are inclusive and only reviewed complete records enter the month');
  check(view.included_count === 4 && view.population === 'REVIEWED_COMPLETED_DISPATCHES', 'Counts describe teaching records, not unique courses');
  same(view.teacher_ranking.map(r => r.rank), [1, 2, 2, 4], 'Actual-hour ranking follows competition tie order');
  same(view.teacher_ranking.map(r => r.teacher_id), teachers, 'Equal teacher names remain separate identities with stable identifier tie-break');
  check(view.teacher_ranking[3].hours.actual.value === '0.00', 'Known zero-minute teaching remains a real zero ranking entry');
  same(view.totals.actual, {value: '4.00', known_subtotal: '4.00', missing_records: 0, complete: true}, 'Actual total excludes old scheduled hours and all invalid sources');
  same(view.totals.estimated, {value: null, known_subtotal: '4.00', missing_records: 1, complete: false}, 'Unknown estimated quantity preserves a known subtotal');
  same(view.totals.planned, {value: null, known_subtotal: '3.25', missing_records: 1, complete: false}, 'Planned hours remain an independent incomplete category');
  same(view.totals.payable, {value: null, known_subtotal: '1.00', missing_records: 1, complete: false}, 'Known payable zero is distinct from unknown payable');
  samePopulation(view);
  check(view.fee === null && view.currency === null && view.course_count === null && view.availability.formal_export === false, 'Missing formal sources stay unavailable, never zero or guessed');
  check(view.coverage_notice.includes('未迁移旧项目') && ![...view.details, ...view.excluded].some(d => d.dispatch_id === legacy), 'Unmigrated legacy hours remain outside the disclosed population');
  const reasons = new Map(view.excluded.map(e => [e.dispatch_id, e.code]));
  for (const [id, reason] of [[pending, 'NOT_COMPLETED'], [rejected, 'REJECTED'], [unverified, 'NOT_VERIFIED'], [missing, 'FACT_MISSING'], [undated, 'TEACHING_DATE_UNKNOWN'], [invalid, 'VERIFICATION_SOURCE_INVALID'], [moved, 'SOURCE_ASSOCIATION_CHANGED']])
    check(reasons.get(id) === reason, 'Invalid source explains exclusion: ' + reason);
  check(view.excluded_count === 7 && view.undated_excluded_count === 1, 'Unknown-date records are separately counted and not asserted to belong to the month');
  check(!reasons.has(outside) && !reasons.has(future), 'Known out-of-range records do not become month exclusions');
  const futureView = await api(query({start: '2099-01-01', end: '2099-01-31', organizations: '001'}));
  check(futureView.excluded.some(e => e.dispatch_id === future && e.code === 'FUTURE_TEACHING_DATE'), 'Historically completed future date is excluded');
  const empty = await api(query({start: '2024-11-01', end: '2024-11-30', organizations: '999'}));
  check(empty.included_count === 0 && empty.excluded_count === 0 && empty.details.length === 0 && empty.teacher_ranking.length === 0 && empty.organization_summary.length === 0, 'A truly empty institution-month is a successful empty result');
  for (const total of Object.values(empty.totals)) same(total, {value: '0', known_subtotal: '0', missing_records: 0, complete: true}, 'Empty population produces complete string zero');
  check(empty.fee === null && empty.course_count === null, 'Empty population cannot invent formal zero fees or courses');
  const unknownMonth = await api(query({start: '2024-11-01', end: '2024-11-30', organizations: '001'}));
  check(unknownMonth.included_count === 0 && unknownMonth.excluded_count === 1 && unknownMonth.undated_excluded_count === 1, 'Unknown teaching date cannot be silently assigned to or dropped from a selected month');
  same((await api(query())).snapshot_version, view.snapshot_version, 'Repeated unchanged real HTTP reads keep the same projection snapshot');
  phase = 'export gates and read-only verification';
  await denied(download(local), undefined, 'reporter', 403, 'Download independently requires reports.export');
  await denied('/management-reports/export?' + params(), undefined, 'analyst', 400, 'Authorized export requires a snapshot version');
  const unavailable = await denied(download(view), undefined, 'analyst', 409, 'Authorized current snapshot cannot export without formal code and currency sources');
  check(unavailable.envelope.msg.includes('正式月报导出暂不可用') && !unavailable.headers.has('content-disposition') && unavailable.headers.get('content-type').includes('application/json'), 'Unavailable export returns an explanation, never a blank or placeholder file');
  const wrong = await denied(download(view, {snapshot_version: '0'.repeat(64)}), undefined, 'analyst', 409, 'Wrong snapshot is rejected before formal export availability');
  check(wrong.envelope.msg.includes('统计数据或权限范围已变化'), 'Snapshot mismatch has its own actionable reason');
  await denied(download(view), undefined, 'anonymous', 401, 'Download rechecks real session');
  check(typeof await api('/stats/report', undefined, 'admin') === 'string', 'Original business report remains callable');
  check(typeof await api('/stats/overview', undefined, 'admin') === 'object', 'Original business overview remains callable');
  await stop(); const after = await offline('inspect');
  same(after, before, 'Every public business table retains exactly the same row count and content hash after all report reads and denials');
  phase = 'fresh facts and live permission';
  await boot(); for (const actor of ['reporter', 'analyst', 'team']) await login(actor);
  const beforeEdit = await api(query());
  const detail = await api('/delivery-settlement?dispatch_id=' + a, undefined, 'team');
  let changed = await api('/delivery-settlement/save', {dispatch_id: a, expected_version: detail.version, request_id: 'CHANGED-ACTUAL', actual_minutes: '135'}, 'team');
  changed = await api('/delivery-settlement/verify', {dispatch_id: a, expected_version: changed.version, request_id: 'REVIEW-CHANGED-ACTUAL', evidence_code: 'SYNTHETIC-CHANGED'}, 'team');
  view = await api(query());
  check(view.snapshot_version !== beforeEdit.snapshot_version && view.totals.actual.value === '5.00', 'Freshly reverified actual minutes change both aggregate and snapshot');
  const stale = await denied(download(beforeEdit), undefined, 'analyst', 409, 'Previous snapshot cannot export after real fact revision');
  check(stale.envelope.msg.includes('统计数据或权限范围已变化'), 'Old fact snapshot reports changed data');
  const allGrants = structuredClone(configuration.grants);
  await publish({grants: configuration.grants.filter(g => !(g.roleCode === 'ANALYST' && g.resource === 'reports.export'))}, 'revoke-export');
  await denied(download(view), undefined, 'analyst', 403, 'Fresh login cannot export a cached snapshot after grant revocation');
  const readOnly = await api(query()); check(readOnly.permissions.export === false, 'Current export capability follows explicit grant removal');
  await publish({grants: allGrants}, 'restore-export');
  const configChanged = await denied(download(view), undefined, 'analyst', 409, 'Re-published authorization invalidates an old projection context');
  check(configChanged.envelope.msg.includes('统计数据或权限范围已变化'), 'Configuration version change is not mistaken for missing source material');
  await publish({grants: configuration.grants.filter(g => !(g.roleCode === 'REPORTER' && g.resource === 'reports.read'))}, 'revoke-read');
  await denied(query(), undefined, 'reporter', 403, 'Fresh login remains denied after viewing grant revocation');
  await api('/logout', {}, 'analyst'); await denied(query(), undefined, 'analyst', 401, 'Logged-out real token cannot query a cached report');
  check(changed.fact.verification !== null, 'Report verification did not modify the genuine teaching evidence');
  return {source_project_ids: [p1, p2], selected_dispatch_ids: [a, b, c, zero], readonly_tables: Object.keys(before).length};
}
let evidence;
runChecks().then(result => { evidence = result; }).catch(error => { fatal = String(error.message || error).replaceAll(password, '[redacted]'); process.exitCode = 1; }).finally(async () => {
  await stop();
  const summary = {suite: 'reports-http', checks, failures, ...(fatal ? {error: fatal} : {}), ...(evidence || {}), production_touched: false, notifications_sent: false, artifacts: own};
  fs.writeFileSync(path.join(own, 'result.json'), JSON.stringify(summary, null, 2), {mode: 0o600}); console.log(JSON.stringify(summary));
  if (failures.length || fatal) process.exitCode = 1;
});
