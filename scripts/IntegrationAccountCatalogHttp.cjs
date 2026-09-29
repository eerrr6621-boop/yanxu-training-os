'use strict';
// Real Main/Api HTTP only. Two fresh synthetic H2 databases, random passwords, loopback, no compilation.
// Compile IntegrationAccountCatalogHttpFixture.java into the supplied isolated classes beforehand.
const fs = require('node:fs'), os = require('node:os'), path = require('node:path'), net = require('node:net');
const crypto = require('node:crypto'), assert = require('node:assert/strict');
const {spawn, execFile} = require('node:child_process');
const {promisify} = require('node:util');
const run = promisify(execFile);
const args = {};
for (let i = 2; i < process.argv.length; i += 2) {
  assert.ok(['--java', '--classes'].includes(process.argv[i]) && process.argv[i + 1], 'Use --java PATH --classes ISOLATED_DIRECTORY');
  args[process.argv[i]] = process.argv[i + 1];
}
assert.ok(args['--java'] && args['--classes'], 'Explicit Java and isolated compiled classes are required');
const repo = path.resolve(__dirname, '..'), classes = path.resolve(args['--classes']);
assert.ok(fs.existsSync(path.join(classes, 'com/training/IntegrationAccountCatalogHttpFixture.class')), 'Compile the scripts-only fixture into the isolated classes first');
const own = fs.mkdtempSync(path.join(os.tmpdir(), 'yanxu-account-catalog-http-'));
const password = crypto.randomBytes(24).toString('hex');
const failures = [], tokens = {};
let child, port, checks = 0, phase = '', fatal = null;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(ok, label) { checks++; if (!ok) failures.push(`${phase}: ${label}`); }
function same(actual, expected, label) { let ok = true; try { assert.deepEqual(actual, expected); } catch { ok = false; } check(ok, label); }
function options(data) {
  return ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password,
    '-Dintegration.account.catalog.root=' + own, '-Ddata.dir=' + data, '-Dsemantic.port=', '-Dsemantic.token=',
    '-cp', classes + path.delimiter + path.join(repo, 'lib', '*')];
}
const childOptions = {cwd: repo, env: {...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: ''}, stdio: 'ignore'};
async function request(route, body, actor = 'admin', overrides = {}) {
  const method = overrides.method || (body === undefined ? 'GET' : 'POST');
  const headers = {...(tokens[actor] ? {'X-Token': tokens[actor]} : {})};
  if (body !== undefined && overrides.contentType !== null) headers['Content-Type'] = overrides.contentType || 'application/json';
  const response = await fetch(`http://127.0.0.1:${port}/api${route}`, {
    method, headers, body: body === undefined ? undefined : overrides.raw ? body : JSON.stringify(body),
    signal: AbortSignal.timeout(5000),
  });
  const text = await response.text();
  let envelope = null;
  if (text) { try { envelope = JSON.parse(text); } catch { throw new Error(`Non-JSON response: ${route}, HTTP ${response.status}`); } }
  return {status: response.status, headers: response.headers, text, envelope};
}
async function api(route, body, actor = 'admin') {
  const r = await request(route, body, actor);
  assert.equal(r.status, 200, `${phase}: ${route}: HTTP ${r.status}: ${r.envelope?.msg}`);
  assert.equal(r.envelope?.code, 0, `${phase}: ${route}: ${r.envelope?.msg}`);
  return r.envelope.data;
}
async function denied(route, body, actor, status, label, overrides = {}) {
  const r = await request(route, body, actor, overrides);
  check(r.status === status && r.envelope?.code === status, `${label} (HTTP ${r.status}, code ${r.envelope?.code})`);
  return r.envelope;
}
async function login(actor, username = 'synthetic-' + actor) { tokens[actor] = (await api('/login', {username, password}, 'anonymous')).token; }
async function boot(name, fixture = false) {
  phase = name;
  for (const key of Object.keys(tokens)) delete tokens[key];
  const data = path.join(own, name); fs.mkdirSync(data);
  const listener = net.createServer();
  await new Promise((resolve, reject) => { listener.once('error', reject); listener.listen(0, '127.0.0.1', resolve); });
  port = listener.address().port;
  await new Promise(resolve => listener.close(resolve));
  child = spawn(args['--java'], [...options(data), fixture ? 'com.training.IntegrationAccountCatalogHttpFixture' : 'com.training.Main', String(port)], childOptions);
  let launchError;
  child.once('error', error => { launchError = error; });
  for (let i = 0; i < 150; i++) {
    if (launchError) throw launchError;
    assert.equal(child.exitCode, null, `Isolated ${name} service exited before login`);
    try { await login('admin', 'admin'); if (tokens.admin) return data; } catch {}
    await pause(100);
  }
  throw new Error(`Isolated ${name} service did not become ready`);
}
async function stop() {
  const running = child; child = null;
  if (!running || running.exitCode !== null || running.signalCode !== null) return;
  await new Promise(resolve => {
    const timer = setTimeout(() => { if (running.exitCode === null && running.signalCode === null) running.kill('SIGKILL'); }, 2000);
    running.once('exit', () => { clearTimeout(timer); resolve(); });
    running.kill('SIGTERM');
  });
}
async function inspect(data) {
  const result = await run(args['--java'], [...options(data), 'com.training.IntegrationAccountCatalogHttpFixture', 'inspect'],
    {cwd: repo, env: childOptions.env, timeout: 15000, maxBuffer: 16384});
  return JSON.parse(result.stdout.trim());
}
function identity(ids, version = 'synthetic-account-http-v1') {
  const optional = {required: false, allowedTargetRoles: ['CATALOG'], targetMustCoverOrganization: false, allowSelf: false};
  return {version, codeRules: {organizationPattern: '[0-9]{3}', personPattern: 'P[0-9]+', rolePattern: '[A-Z]+'},
    roleCodes: ['CATALOG'], organizations: [{organizationCode: '001', parentOrganizationCode: null, enabled: true}],
    people: ['P1', 'P2', 'P3'].map(personCode => ({personCode, organizationCode: '001', responsibleOrganizationCodes: [],
      leaderPersonCode: personCode === 'P1' ? 'P3' : null, bpPersonCode: personCode === 'P1' ? 'P3' : null,
      roleCodes: ['CATALOG'], enabled: true})),
    relations: [{roleCode: 'CATALOG', leader: optional, bp: optional}],
    accountBindings: [{accountId: ids.active, personCode: 'P1', enabled: true},
      {accountId: ids.disabled, personCode: 'P2', enabled: false}, {accountId: ids.leader, personCode: 'P3', enabled: true}],
    grants: [['read', 'catalog.read', 'VIEW'], ['write', 'catalog.manage', 'HANDLE']].map(([ruleId, resource, action]) =>
      ({ruleId, roleCode: 'CATALOG', resource, action, effect: 'ALLOW', scope: 'OWN_ORG', organizationCodes: []}))};
}
async function accountChecks() {
  const data = await boot('empty-account');
  const admin = await api('/me');
  same(await api('/organization/config'), {version: null, configuration: null}, 'Startup creates no identity configuration or grants');
  check((await api('/organization/me')).status === 'NOT_CONFIGURED', 'Unconfigured administrator has no person binding');
  await denied('/course-catalog/scopes', undefined, 'admin', 403, 'Administrator is not implicitly granted catalog access');
  await denied('/course-catalog/scopes', undefined, 'anonymous', 401, 'Catalog requires a real HTTP login');
  const self = await denied('/users/delete', {id: admin.uid}, 'admin', 400, 'Cannot delete the current account');
  check(/当前/.test(self?.msg || ''), 'Self-delete retains the specific existing explanation');
  const last = await denied('/users', {id: admin.uid, username: 'admin', name: 'SYNTHETIC ADMIN', role: 'viewer', status: 1}, 'admin', 400, 'Cannot remove the last enabled administrator role');
  check(/至少保留.*管理员/.test(last?.msg || ''), 'Last-administrator safeguard retains its explanation');
  const lastDisable = await denied('/users', {id: admin.uid, username: 'admin', name: 'SYNTHETIC ADMIN', role: 'admin', status: 0}, 'admin', 400, 'Cannot disable the last enabled administrator');
  check(/至少保留.*管理员/.test(lastDisable?.msg || ''), 'Last-administrator disable safeguard runs before self-change');
  check((await api('/me')).role === 'admin', 'Rejected administrator mutations preserve the live account');
  const ids = {};
  for (const actor of ['active', 'disabled', 'leader', 'unbound', 'replacement']) {
    ids[actor] = await api('/users', {username: 'synthetic-' + actor, name: 'SYNTHETIC ' + actor, role: 'manager', status: 1, password});
    await login(actor);
  }
  const configuration = identity(ids);
  await api('/organization/config', {expectedVersion: null, configuration});
  for (const actor of ['active','disabled','leader']) {
    await denied('/me', undefined, actor, 401, 'Initial binding invalidates old ' + actor + ' token');
    await login(actor);
  }
  await denied('/organization/config', undefined, 'active', 403, 'Nonadministrator cannot read the configuration roster');
  await denied('/organization/config', {expectedVersion: configuration.version, configuration: {...configuration, version: 'forbidden'}}, 'active', 403, 'Nonadministrator cannot publish configuration');
  same((await api('/course-catalog/scopes', undefined, 'active')).scopes, [], 'Explicit grants alone do not create any catalog space');
  check((await api('/course-catalog/scopes', undefined, 'active')).status === 'NOT_CONFIGURED', 'Empty catalog has the explicit unconfigured state');
  await denied('/course-catalog/scopes', undefined, 'disabled', 403, 'Disabled binding does not confer business access');
  const before = await api('/organization/config');
  for (const actor of ['active', 'disabled']) {
    const rejected = await denied('/users/delete', {id: ids[actor]}, 'admin', 400, `${actor} M01 binding prevents account deletion`);
    check(/绑定|组织权限|关联人员/.test(rejected?.msg || '') && /保留|停用/.test(rejected?.msg || ''), `${actor} binding refusal is an actionable explanation`);
    check((await api('/users')).some(user => user.id === ids[actor]), `${actor} account remains saved`);
    same(await api('/organization/config'), before, `${actor} refusal preserves version, mappings, leader and BP relations`);
  }
  await api('/users/delete', {id: ids.unbound});
  check(!(await api('/users')).some(user => user.id === ids.unbound), 'Unbound account still deletes normally');
  same(await api('/organization/config'), before, 'Unbound deletion leaves organization relationships unchanged');
  const changed = structuredClone(configuration);
  changed.version = 'synthetic-account-http-v2';
  changed.accountBindings[0].accountId = ids.replacement;
  await api('/organization/config', {expectedVersion: configuration.version, configuration: changed});
  await denied('/organization/config', {expectedVersion: configuration.version, configuration: {...changed, version: 'synthetic-account-http-v3'}}, 'admin', 409, 'Old configuration version cannot overwrite explicit rebinding');
  same((await api('/organization/config')).configuration, changed, 'Stale publication preserves the exact accepted rebinding');
  await denied('/course-catalog/scopes', undefined, 'active', 401, 'Removed binding revokes the old account session');
  await denied('/organization/me', undefined, 'replacement', 401, 'New binding revokes the replacement account old session');
  await login('active');await denied('/course-catalog/scopes', undefined, 'active', 403, 'Fresh login cannot recover a removed binding');
  await login('replacement');
  check((await api('/organization/me', undefined, 'replacement')).person.personCode === 'P1', 'Replacement HTTP session uses the explicitly assigned person');
  await api('/users/delete', {id: ids.active});
  check(!(await api('/users')).some(user => user.id === ids.active), 'Account explicitly removed from current binding can delete normally');
  same((await api('/organization/config')).configuration, changed, 'Deletion after rebinding preserves the accepted configuration');
  await stop();
  const counts = await inspect(data);
  for (const [table, count] of Object.entries(counts)) if (table.startsWith('m04_')) check(count === 0, `Startup and account work leave ${table} empty`);
  check(counts.organization_access_config === 2, 'Only the two explicit configuration publications were persisted');
}
function importBody(teacherId, expectedVersion = 0, catalogVersion = 'synthetic-catalog-material-v1') {
  return {expected_version: expectedVersion, change_comment: 'SYNTHETIC explicit source import', bindings: [{teacher_code: '0001', teacher_id: teacherId}],
    catalog: {schema_version: 'm04_catalog_v1', catalog_version: catalogVersion,
      courses: [{course_code: '001', course_name: 'SYNTHETIC STANDARD COURSE', active: true}],
      teachers: [{teacher_code: '0001', teacher_level: '讲师', city: '杭州'}],
      certifications: [{teacher_code: '0001', course_code: '001', status: 'certified', source_ref: 'SYNTHETIC-SOURCE-001', valid_from: '2026-01-01', valid_to: '2026-12-31'}]}};
}
async function catalogChecks() {
  const data = await boot('configured-catalog', true);
  for (const actor of ['manager', 'reader', 'other', 'colleague', 'unbound']) await login(actor);
  const root = '/course-catalog/scopes';
  const managerScopes = await api(root, undefined, 'manager'), otherScopes = await api(root, undefined, 'other');
  check(managerScopes.scopes.length === 1 && managerScopes.scopes[0].organization_code === '001', 'Catalog manager sees only the explicitly granted organization');
  check(otherScopes.scopes.length === 1 && otherScopes.scopes[0].organization_code === '002', 'Other organization sees only its separately provisioned scope');
  const scope = managerScopes.scopes[0].scope_id, otherScope = otherScopes.scopes[0].scope_id, route = root + '/' + scope;
  check(scope !== otherScope, 'Trusted fixture registered distinct synthetic scopes');
  await denied(root, undefined, 'admin', 403, 'Unbound administrator does not inherit configured catalog permissions');
  await denied(root, undefined, 'unbound', 403, 'Legacy manager role cannot replace a person binding');
  await denied(route, undefined, 'other', 403, 'Stored scope ownership rejects other organization read');
  const initial = await api(route, undefined, 'reader');
  check(initial.status === 'NOT_CONFIGURED' && initial.version === 0 && initial.catalog === null && initial.bindings.length === 0, 'Registered empty scope has no material or teacher binding');
  const head = await request(route, undefined, 'reader', {method: 'HEAD'});
  check(head.status === 200 && head.text === '', 'Real Api sends a bodyless successful HEAD response');
  const method = await denied(route + '/preview', undefined, 'manager', 405, 'Read method cannot invoke preview');
  check(Boolean(method?.msg), 'Method rejection has a JSON error envelope');
  const put = await request(root, undefined, 'manager', {method: 'PUT'});
  check(put.status === 405 && put.headers.get('allow')?.includes('GET'), 'Unsupported method returns HTTP 405 and Allow');
  await denied(route + '?organization_code=002', undefined, 'manager', 400, 'Client organization query cannot override stored ownership');
  await denied(route + '/unknown', undefined, 'manager', 404, 'Unknown catalog action is not accepted');
  await denied(root + '/9007199254740992', undefined, 'manager', 400, 'Unsafe numeric scope identifier is rejected');
  await denied(route + '/history?limit=1&limit=2', undefined, 'manager', 400, 'Repeated history query keys are rejected');
  const teacher = (await api('/teachers')).find(row => row.name === 'SYNTHETIC CATALOG TEACHER');
  assert.ok(teacher, 'Fixture teacher must be accessible through the original teacher API');
  const body = importBody(teacher.id), previewRoute = route + '/preview';
  await denied(previewRoute, JSON.stringify(body), 'manager', 415, 'Api rejects non-JSON media before parsing', {raw: true, contentType: 'text/plain'});
  await denied(previewRoute, JSON.stringify(body), 'manager', 415, 'Api rejects an omitted JSON content type', {raw: true, contentType: null});
  await denied(previewRoute, '{"expected_version":', 'manager', 400, 'Api rejects malformed JSON', {raw: true});
  await denied(previewRoute, '{"expected_version":0,"expected_version":0}', 'manager', 400, 'Api rejects duplicate JSON keys', {raw: true});
  await denied(previewRoute, '[]', 'manager', 400, 'Api rejects a non-object JSON body', {raw: true});
  await denied(previewRoute, JSON.stringify({padding: 'x'.repeat(1024 * 1024)}), 'manager', 413, 'Api enforces the actual one MiB request limit', {raw: true});
  await denied(previewRoute, body, 'reader', 403, 'Read grant cannot import a catalog');
  await denied(previewRoute, body, 'other', 403, 'Other organization cannot import into this scope');
  await denied(previewRoute, {...body, organization_code: '002'}, 'manager', 400, 'Preview rejects client ownership injection');
  const qualification = {expected_version: 0, request: {course_code: '001', as_of: '2026-09-22', accepted_levels: [], allowed_cities: []}};
  const empty = await api(route + '/qualify', qualification, 'reader');
  check(empty.status === 'NOT_CONFIGURED' && empty.ready === false && empty.eligible.length === 0, 'Empty scope never manufactures qualified teachers');
  const preview = await api(previewRoute, body, 'manager');
  check(preview.ready === true && preview.confirmation_required === true && typeof preview.batch_id === 'string', 'HTTP preview produces a persisted confirmable batch');
  same(await api(route, undefined, 'reader'), initial, 'Preview leaves current catalog and bindings unchanged');
  check((await api(route + '/history', undefined, 'manager')).events.length === 0, 'Preview creates no confirmation audit event');
  const confirmation = {batch_id: preview.batch_id, expected_version: 0, confirm: true};
  await denied(route + '/confirm', confirmation, 'reader', 403, 'Read-only actor cannot confirm');
  await denied(route + '/confirm', confirmation, 'other', 403, 'Other organization cannot confirm');
  await denied(route + '/confirm', confirmation, 'colleague', 403, 'Same-organization colleague cannot confirm another account batch');
  await denied(root + '/' + otherScope + '/confirm', confirmation, 'other', 404, 'Batch is not disclosed through another authorized scope');
  await denied(route + '/confirm', {...confirmation, confirm: 'true'}, 'manager', 400, 'Confirmation must be an explicit JSON boolean');
  await denied(route + '/confirm', {...confirmation, expected_version: 1}, 'manager', 409, 'Confirmation must carry the original preview version');
  const confirmed = await api(route + '/confirm', confirmation, 'manager');
  check(confirmed.status === 'CONFIRMED' && confirmed.version === 1, 'First confirmation publishes version one');
  const replay = await api(route + '/confirm', confirmation, 'manager');
  check(replay.status === 'ALREADY_CONFIRMED' && replay.version === 1 && replay.current_version === 1, 'Repeated HTTP confirmation is idempotent');
  const saved = await api(route, undefined, 'reader'), history = await api(route + '/history', undefined, 'manager');
  check(saved.version === 1 && saved.bindings[0].teacher_id === teacher.id && saved.bindings[0].teacher_code === '0001', 'Published binding preserves explicit ID and leading-zero code');
  check(history.events.length === 1 && history.events[0].batch_id === preview.batch_id, 'Replay creates exactly one published history event');
  same((await api(route + '/versions/1', undefined, 'manager')).catalog, saved.catalog, 'Historical endpoint returns the confirmed server snapshot');
  await denied(route + '/history', undefined, 'reader', 403, 'Read grant does not reveal manage-only history');
  const eligible = await api(route + '/qualify', {...qualification, expected_version: 1}, 'reader');
  check(eligible.ready === true && eligible.eligible.length === 1 && eligible.eligible[0].teacher_id === teacher.id && eligible.metadata_source === 'current_teacher_record', 'Qualification uses confirmed catalog, persistent binding and current teacher facts');
  await denied(route + '/qualify', {...qualification, expected_version: 1, eligible: [teacher.id]}, 'reader', 400, 'Client-supplied eligibility is rejected');
  const deletion = await denied('/teachers/delete', {id: teacher.id}, 'admin', 400, 'Teacher referenced only by catalog cannot be deleted');
  check(/课程|目录/.test(deletion?.msg || '') && /出库|保留/.test(deletion?.msg || ''), 'Catalog-only deletion returns an actionable archive/outbound explanation');
  check((await api('/teachers')).some(row => row.id === teacher.id), 'Rejected delete preserves the teacher record');
  same(await api(route, undefined, 'reader'), saved, 'Rejected teacher deletion preserves the catalog and stable binding');
  const unused = await api('/teachers', {name: 'SYNTHETIC UNBOUND TEACHER', status: '在库', teacher_level: '讲师', base_province: '浙江', base_city: '杭州'});
  await api('/teachers/delete', {id: unused});
  check(!(await api('/teachers')).some(row => row.id === unused), 'Teacher without binding or other history still deletes normally');
  await denied(previewRoute, body, 'manager', 409, 'Stale expected version cannot preview over the published catalog');
  const pending = await api(previewRoute, importBody(teacher.id, 1, 'synthetic-catalog-material-v2'), 'manager');
  const identitySnapshot = await api('/organization/config');
  const changed = {...identitySnapshot.configuration, version: 'synthetic-catalog-http-v2'};
  await api('/organization/config', {expectedVersion: identitySnapshot.version, configuration: changed});
  await denied(route + '/confirm', {batch_id: pending.batch_id, expected_version: 1, confirm: true}, 'manager', 409, 'Changed M01 version invalidates an unconfirmed preview even with unchanged grants');
  check((await api(route, undefined, 'reader')).version === 1 && (await api(route + '/history', undefined, 'manager')).events.length === 1, 'Rejected stale confirmation creates no new version or event');
  await stop();
  const counts = await inspect(data);
  same(counts, {m04_catalog_scopes: 2, m04_catalog_batches: 2, m04_catalog_revisions: 1, m04_teacher_bindings: 1, m04_catalog_events: 1, organization_access_config: 2}, 'Offline inspection confirms exact persisted counts after all HTTP operations');
}
(async () => {
  await accountChecks();
  await catalogChecks();
})().catch(error => {
  fatal = String(error.message || error).replaceAll(password, '[redacted]');
  process.exitCode = 1;
}).finally(async () => {
  await stop();
  const summary = {suite: 'account-catalog-http', checks, failures, ...(fatal ? {error: fatal} : {}),
    production_touched: false, notifications_sent: false, artifacts: own};
  fs.writeFileSync(path.join(own, 'result.json'), JSON.stringify(summary, null, 2), {mode: 0o600});
  console.log(JSON.stringify(summary));
  if (failures.length || fatal) process.exitCode = 1;
});
