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
const repo = path.resolve(__dirname, '..'), classes = fs.realpathSync(args['--classes']);
assert.ok(classes !== repo && !classes.startsWith(repo + path.sep), 'Use isolated compiled classes outside the repository');
assert.ok(fs.existsSync(path.join(classes, 'com/training/IntegrationTeachingExportHttpFixture.class')), 'Compile the scripts-only fixture first');
const own = fs.mkdtempSync(path.join(os.tmpdir(), 'yanxu-teaching-export-http-'));
const data = path.join(own, 'data'); fs.mkdirSync(data);
const password = crypto.randomBytes(24).toString('hex'), tokens = {}, failures = [], servicePids = [];
const env = {...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: ''};
let child, port, checks = 0, phase = 'setup', fatal, configuration, serial = 0;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(ok, label) { checks++; if (!ok) failures.push(`${phase}: ${label}`); }
function same(actual, expected, label) { let ok = true; try { assert.deepEqual(actual, expected); } catch { ok = false; } check(ok, label); }
function options() { return ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password,
  '-Dintegration.teaching.export.root=' + own, '-Ddata.dir=' + data, '-Dsemantic.port=', '-Dsemantic.token=', '-cp', classes + path.delimiter + path.join(repo, 'lib', '*')]; }
async function request(route, body, actor = 'analyst', method) {
  const headers = tokens[actor] ? {'X-Token': tokens[actor]} : {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(`http://127.0.0.1:${port}/api${route}`, {method: method || (body === undefined ? 'GET' : 'POST'), headers,
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(5000)});
  const bytes = Buffer.from(await response.arrayBuffer()), text = response.headers.get('content-type')?.includes('application/json') ? bytes.toString('utf8') : '';
  const envelope = text ? JSON.parse(text) : null;
  return {status: response.status, headers: response.headers, envelope, text, bytes};
}
async function api(route, body, actor = 'analyst') {
  const r = await request(route, body, actor);
  assert.equal(r.status, 200, `${phase}: ${route}: HTTP ${r.status}: ${r.envelope?.msg}`);
  assert.equal(r.envelope?.code, 0, `${phase}: ${route}: ${r.envelope?.msg}`); return r.envelope.data;
}
async function denied(route, body, actor, status, label, method) {
  const r = await request(route, body, actor, method);
  check(r.status === status && (method === 'HEAD' ? r.text === '' : r.envelope?.code === status), `${label} (HTTP ${r.status}: ${r.envelope?.msg})`);
  check(!r.headers.has('content-disposition'), label + ' never attaches an error as a workbook');
  if (method !== 'HEAD') check(r.headers.get('content-type')?.includes('application/json'), label + ' remains JSON');
  return r;
}
async function login(actor, username = 'synthetic-teaching-export-' + actor) { tokens[actor] = (await api('/login', {username, password}, 'anonymous')).token; }
async function boot(fixture = false) {
  Object.keys(tokens).forEach(key => delete tokens[key]);
  const listener = net.createServer();
  await new Promise((resolve, reject) => { listener.once('error', reject); listener.listen(0, '127.0.0.1', resolve); });
  port = listener.address().port; await new Promise(resolve => listener.close(resolve));
  child = spawn(args['--java'], [...options(), fixture ? 'com.training.IntegrationTeachingExportHttpFixture' : 'com.training.Main', String(port)], {cwd: repo, env, stdio: 'ignore'});
  if (child.pid) servicePids.push(child.pid);
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
async function offline(operation, filename) {
  assert.ok(operation === 'xlsx' || !child, 'Offline database fixture cannot run against an active service');
  const result = await run(args['--java'], [...options(), 'com.training.IntegrationTeachingExportHttpFixture', operation, ...(filename ? [filename] : [])], {cwd: repo, env, timeout: 20000, maxBuffer: 4 * 1024 * 1024});
  return ['inspect', 'xlsx'].includes(operation) ? JSON.parse(result.stdout.trim()) : null;
}
const params = (changes = {}) => new URLSearchParams({start: '2025-01-01', end: '2025-01-31', date_basis: 'TEACHING', ...changes}).toString();
const query = (changes = {}) => '/management-reports?' + params(changes);
const download = (snapshot, changes = {}) => '/management-reports/teaching-export?' + params({organizations: snapshot.organizations.join(','), snapshot_version: snapshot.snapshot_version, ...changes});
// Configuration publication is an explicit authentication boundary, never an API retry policy.
async function renewPublishedSessions(label, actors = ['admin', 'leader', 'bp', 'team', 'outsider', 'reporter', 'analyst', 'filler', 'partial']) {
  for (const actor of actors) {
    if (!tokens[actor]) continue; // Some identities have not logged in since a process restart.
    await denied('/me', undefined, actor, 401, label + ' invalidates the old ' + actor + ' token');
    await login(actor, actor === 'admin' ? 'admin' : undefined);
  }
}
async function publish(changes, suffix) {
  const expectedVersion = configuration.version;
  configuration = {...structuredClone(configuration), ...changes, version: 'synthetic-teaching-export-http-' + suffix};
  await api('/organization/config', {expectedVersion, configuration}, 'admin');
  await renewPublishedSessions(suffix);
}
async function identities() {
  const ids = [(await api('/me', undefined, 'admin')).uid];
  for (const [actor, role] of [['leader', 'viewer'], ['bp', 'viewer'], ['team', 'manager'], ['outsider', 'viewer'], ['reporter', 'viewer'], ['analyst', 'viewer'], ['filler', 'manager'], ['unbound', 'viewer'], ['partial', 'viewer']]) {
    const id = await api('/users', {username: 'synthetic-teaching-export-' + actor, name: 'SYNTHETIC ' + actor, role, status: 1, password}, 'admin');
    ids.push(id); await login(actor);
  }
  configuration = workflowConfiguration(ids.slice(0, 5)); configuration.version = 'synthetic-teaching-export-http-v1';
  for (const person of configuration.people) if (['P2', 'P3', 'P4'].includes(person.personCode)) person.responsibleOrganizationCodes.push('999');
  for (const grant of configuration.grants) if (grant.scope === 'NAMED_ORGS' && grant.organizationCodes.includes('001')) grant.organizationCodes.push('999');
  const optional = {required: false, allowedTargetRoles: ['REPORTER', 'ANALYST', 'PARTIAL'], targetMustCoverOrganization: false, allowSelf: false};
  for (const [personCode, role, organizationCode, index] of [['P6', 'REPORTER', '001', 5], ['P7', 'ANALYST', '900', 6], ['P8', 'FILLER', '999', 7], ['P9', 'PARTIAL', '900', 9]]) {
    configuration.people.push({personCode, roleCodes: [role], organizationCode, responsibleOrganizationCodes: [], leaderPersonCode: role === 'FILLER' ? 'P2' : null, bpPersonCode: role === 'FILLER' ? 'P3' : null, enabled: true});
    configuration.accountBindings.push({accountId: ids[index], personCode, enabled: true});
  }
  for (const roleCode of ['REPORTER', 'ANALYST', 'PARTIAL']) { configuration.roleCodes.push(roleCode); configuration.relations.push({roleCode, leader: optional, bp: optional}); }
  for (const [roleCode, resource, action, organizations] of [['REPORTER', 'reports.read', 'VIEW', ['001']], ['ANALYST', 'reports.read', 'VIEW', ['001', '999']], ['ANALYST', 'reports.export', 'EXPORT', ['001', '999']], ['PARTIAL', 'reports.read', 'VIEW', ['001', '999']], ['PARTIAL', 'reports.export', 'EXPORT', ['001']]])
    configuration.grants.push({ruleId: roleCode + '-' + resource, roleCode, resource, action, effect: 'ALLOW', scope: 'NAMED_ORGS', organizationCodes: organizations});
  await api('/organization/config', {expectedVersion: null, configuration}, 'admin');
  await renewPublishedSessions('Initial binding');
}
async function project(actor, org, person) {
  const tag = 'REPORT-' + (++serial);
  let d = (await api('/demands/draft', {request_id: tag + '-DRAFT', title: 'PRIVATE_TEACHING_PROJECT_' + org + '=HYPERLINK("https://invalid.example")', unit: 'SYNTHETIC CUSTOMER', business_path: 'direct', organization_code: org, internal_contact_code: person,
    category_text: '业务技能', delivery_mode_text: '线上', period_text: '全天', duration_minutes: '90', participant_count: '1', budget_amount: '0', objectives: 'SYNTHETIC', content: 'SYNTHETIC', teacher_req: '', remark: 'SYNTHETIC'}, actor)).workflow;
  d = (await api('/demands/submit', {id: d.id, expected_version: d.version, request_id: tag + '-SUBMIT'}, actor)).workflow;
  for (const approver of ['leader', 'bp']) d = (await api('/approvals/tasks/' + d.id + '/actions', {action: 'APPROVE', expectedVersion: d.approval.version, expectedStage: d.approval.stage, requestId: tag + '-' + approver, comment: 'SYNTHETIC'}, approver)).workflow;
  d = (await api('/demands/accept', {id: d.id, expected_version: d.version, request_id: tag + '-ACCEPT', team_code: '900'}, 'team')).workflow;
  const p = (await api('/projects', undefined, 'team')).find(row => row.id === d.project_id);
  await api('/projects', {...p, hours: 10, amount: 0, owner: 'SYNTHETIC', start_date: '2025-01-01', end_date: '2025-01-31', delivery_mode: '线上'}, 'team');
  await api('/projects/start', {id: p.id}, 'team'); return p.id;
}
async function teaching(projectId, teacherId, subject, day, quantities, disposition = 'complete') {
  const id = await api('/dispatches', {project_id: projectId, teacher_id: teacherId, subject, teach_date: day, start_time: '09:00', end_time: '10:30', hours: 9, status: '待发送', material_status: '已就绪', remark: 'PRIVATE_TEACHING_REMARK'}, 'team');
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
const mime = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const sheetNames = ['说明', '四类汇总', '讲师排名', '机构汇总', '已核对明细', '未计入原因'];
const allCells = book => book.sheets.flatMap(s => s.rows.flat());
const cellValues = sheet => sheet.rows.map(row => row.map(cell => cell.value));
const sheet = (book, name) => { const found = book.sheets.find(s => s.name === name); assert.ok(found, 'Missing workbook sheet: ' + name); return found; };
function sameDecimal(value, expected) {
  const normalize = v => String(v).replace(/\.0+$/, '').replace(/(\.\d*?[1-9])0+$/, '$1');
  return normalize(value) === normalize(expected);
}
function dataTable(book, name, markers) {
  const rows = sheet(book, name).rows;
  const index = rows.findIndex(row => markers.every(marker => row.some(c => marker.test(c.value))));
  assert.ok(index >= 0, 'Workbook table header must identify ' + name + ': ' + markers.join(','));
  return {headers: rows[index].map(c => c.value), rows: rows.slice(index + 1)};
}
function column(table, pattern) {
  const indexes = table.headers.map((header, i) => pattern.test(header) ? i : -1).filter(i => i >= 0);
  assert.equal(indexes.length, 1, 'Unique column ' + pattern + ' in ' + table.headers.join('|')); return indexes[0];
}
function workbookContract(book, view, expected = {}) {
  same(book.sheets.map(s => s.name), sheetNames, 'Actual workbook has the six contracted sheets');
  check(book.sheets.every(s => !s.state || s.state === 'visible'), 'Workbook has no hidden sheet or identity map');
  check(book.formula_count === 0 && book.external_relationships === 0, 'Downloaded workbook has no formulas or external relationships');
  check(!book.entries.some(name => /vba|macro|externalLink|embedded|oleObject|connections/i.test(name)), 'ZIP contains no macros, embedded executable objects or external connection parts');
  for (const sentinel of ['PRIVATE_TEACHING_', '13987654321', 'teaching-private@example.test', 'HYPERLINK', 'https://invalid.example', 'SYNTHETIC HISTORICAL TEACHER', 'SYNTHETIC CORRUPT REVISION', 'SYNTHETIC DATE DRIFT', 'SYNTHETIC-PROOF-', '765432.1', '2024-12-25'])
    check(!book.all_xml.includes(sentinel), 'Workbook excludes private or unrelated sentinel: ' + sentinel);
  const notes = sheet(book, '说明').rows.flat().map(c => c.value).join(' ');
  check(/已核对/.test(notes) && /不是|非/.test(notes) && /结算/.test(notes) && /金额/.test(notes) && /支付/.test(notes), 'Explanation states reviewed teaching scope and excludes settlement, money and payment');
  check(/四类/.test(notes) && /不.*相加|不能.*相加/.test(notes), 'Explanation keeps four hour kinds separate');
  check(/预计/.test(notes) && /计划/.test(notes) && /已完成/.test(notes) && /已核对/.test(notes), 'Estimated and planned values disclose the same reviewed completed population');
  check(/未迁移旧项目/.test(notes), 'Workbook discloses unmigrated legacy coverage boundary');
  check(/系统记录ID/.test(book.all_xml) && /非正式编码/.test(book.all_xml), 'Technical identifiers are explicitly labeled system record IDs, not formal codes');
  check(allCells(book).every(c => !['str', 'e'].includes(c.type)), 'Workbook uses explicit text cells or numeric values, never formula strings or errors');
  for (const s of book.sheets.filter(s => s.name !== '说明')) {
    const headers = s.rows[0]?.map(c => c.value) || [];
    check(!headers.some(h => /姓名|标题|课程名称|授课主题|备注|电话|邮箱|金额|币种|单价|支付日期|核对人|证据/.test(h)), s.name + ' columns exclude personal/free text and payment fields');
  }
  const details = dataTable(book, '已核对明细', [/排课.*系统记录ID/, /实际/]);
  const dispatchColumn = column(details, /排课.*系统记录ID/), teacherColumn = column(details, /讲师.*系统记录ID/), projectColumn = column(details, /项目.*系统记录ID/);
  const organizationColumn = column(details, /机构/);
  const actualColumn = column(details, /实际/), payableColumn = column(details, /计酬/), estimatedColumn = column(details, /预计/), plannedColumn = column(details, /计划/);
  const detailRows = details.rows.filter(row => row[dispatchColumn]?.value && /^\d+$/.test(row[dispatchColumn].value));
  same(detailRows.map(row => Number(row[dispatchColumn].value)), view.details.map(d => d.dispatch_id), 'Workbook detail population and order equal current server snapshot');
  for (let i = 0; i < detailRows.length; i++) {
    const row = detailRows[i], fact = view.details[i];
    check(['inlineStr', 's'].includes(row[organizationColumn].type) && row[organizationColumn].value === fact.organization_code, 'Organization code stays exact text including leading zeros');
    for (const [index, value] of [[dispatchColumn, fact.dispatch_id], [teacherColumn, fact.teacher_id], [projectColumn, fact.project_id]])
      check(['inlineStr', 's'].includes(row[index].type) && row[index].value === String(value), 'System ID is exact text, not a numeric/formal code');
    for (const [index, kind] of [[estimatedColumn, 'estimated'], [plannedColumn, 'planned'], [actualColumn, 'actual'], [payableColumn, 'payable']])
      check(fact.hours[kind] === null ? row[index].value === '未知' : sameDecimal(row[index].value, fact.hours[kind]), 'Downloaded detail preserves independent ' + kind + ' value including unknown');
  }
  if (expected.precisionId) {
    const high = detailRows.find(row => row[dispatchColumn].value === String(expected.precisionId));
    check(high[estimatedColumn].value === '123456789012345.67' && ['inlineStr', 's'].includes(high[estimatedColumn].type), 'More than fifteen significant digits remain exact text');
  }
  const ranks = dataTable(book, '讲师排名', [/名次|排名/, /讲师.*系统记录ID/]);
  const rankColumn = column(ranks, /名次|排名/), rankedTeacher = column(ranks, /讲师.*系统记录ID/);
  const rankKind = column(ranks, /课时类别/);
  const ranked = ranks.rows.filter(row => /^\d+$/.test(row[rankedTeacher]?.value || '') && row[rankKind]?.value === '实际');
  same(ranked.map(row => Number(row[rankColumn].value)), view.teacher_ranking.map(row => row.rank), 'Workbook ranking preserves current tie order');
  same(ranked.map(row => Number(row[rankedTeacher].value)), view.teacher_ranking.map(row => row.teacher_id), 'Equal names cannot merge distinct teacher IDs');
  const kinds = {estimated: '预计', planned: '计划', actual: '实际', payable: '计酬'};
  const totals = dataTable(book, '四类汇总', [/课时类别/, /完整合计/]);
  const organizations = dataTable(book, '机构汇总', [/机构编码/, /课时类别/]);
  function aggregateRows(table, values, selection, label) {
    const kindIndex = column(table, /课时类别/), valueIndex = column(table, /完整合计/), knownIndex = column(table, /已知小计/), missingIndex = column(table, /缺失记录数/), completeIndex = column(table, /完整性/);
    const selected = table.rows.filter(selection);
    check(selected.length === 4, label + ' exports all four independent aggregate categories');
    for (const [kind, name] of Object.entries(kinds)) {
      const rows = selected.filter(row => row[kindIndex]?.value === name); check(rows.length === 1, label + ' has one ' + name + ' aggregate');
      if (rows.length !== 1) continue;
      const row = rows[0], total = values[kind];
      check(total.value === null ? row[valueIndex].value === '未知' : sameDecimal(row[valueIndex].value, total.value), label + ' preserves complete ' + kind + ' aggregate');
      check(sameDecimal(row[knownIndex].value, total.known_subtotal) && Number(row[missingIndex].value) === total.missing_records, label + ' preserves exact known subtotal and missing count for ' + kind);
      check(row[completeIndex].value === (total.complete ? '完整' : '存在未知'), label + ' clearly labels completeness for ' + kind);
    }
  }
  aggregateRows(totals, view.totals, row => Object.values(kinds).includes(row[0]?.value), 'Whole population');
  for (const teacher of view.teacher_ranking) aggregateRows(ranks, teacher.hours, row => row[rankedTeacher]?.value === String(teacher.teacher_id), 'Teacher ' + teacher.teacher_id);
  const organizationCode = column(organizations, /机构编码/);
  for (const organization of view.organization_summary) aggregateRows(organizations, organization.hours, row => row[organizationCode]?.value === organization.organization_code, 'Organization ' + organization.organization_code);
  for (const table of [details, ranks, totals, organizations]) check(!table.headers.some(h => /姓名|标题|课程名称|授课主题|备注|电话|邮箱|金额|币种|单价|支付日期|核对人|证据/.test(h)), 'Actual table headers expose no personal, free-text or monetary fields');
  for (const total of ['预计', '计划', '实际', '计酬']) check(sheet(book, '四类汇总').rows.flat().some(c => c.value.includes(total)), 'Four-kind summary includes ' + total);
  check(/已知/.test(sheet(book, '四类汇总').rows.flat().map(c => c.value).join(' ')) && /缺失/.test(sheet(book, '四类汇总').rows.flat().map(c => c.value).join(' ')), 'Summary separately labels known subtotal and missing count');
  const exclusions = dataTable(book, '未计入原因', [/排课.*系统记录ID/, /原因/]);
  const excludedId = column(exclusions, /排课.*系统记录ID/), reasonCode = column(exclusions, /原因码/);
  same(exclusions.rows.filter(row => /^\d+$/.test(row[excludedId]?.value || '')).map(row => row[reasonCode].value), view.excluded.map(row => row.code), 'Fixed exclusion reason codes equal the trusted server snapshot');
  same(exclusions.rows.filter(row => /^\d+$/.test(row[excludedId]?.value || '')).map(row => Number(row[excludedId].value)), view.excluded.map(row => row.dispatch_id), 'Excluded rows exactly match server exclusions');
  if (view.undated_excluded_count) check(/日期未知.*无法归月/.test(sheet(book, '未计入原因').rows.flat().map(c => c.value).join(' ')), 'Unknown date is explicitly unassignable to a month');
}
async function successfulDownload(view, filename, changes = {}, actor = 'analyst') {
  const r = await request(download(view, changes), undefined, actor);
  assert.equal(r.status, 200, phase + ': teaching download: ' + r.status + ': ' + r.envelope?.msg);
  check(r.headers.get('content-type')?.split(';')[0] === mime, 'Successful download has XLSX MIME');
  check(r.headers.get('content-disposition') === 'attachment; filename="reviewed-teaching-' + (changes.start || '2025-01-01').replaceAll('-', '') + '-' + (changes.end || '2025-01-31').replaceAll('-', '') + '.xlsx"', 'Successful download uses safe ASCII date-derived attachment name');
  check(r.headers.get('cache-control')?.includes('no-store'), 'Download disables response caching');
  check(r.bytes.subarray(0, 4).equals(Buffer.from([0x50, 0x4b, 0x03, 0x04])), 'Downloaded bytes are a ZIP workbook, not JSON or HTML');
  const target = path.join(own, filename); fs.writeFileSync(target, r.bytes, {mode: 0o600});
  return offline('xlsx', target);
}
async function runChecks() {
  await boot(true); await identities();
  const p1 = await project('admin', '001', 'P1'), p2 = await project('filler', '999', 'P8');
  const teachers = [];
  for (const name of ['A', 'SAME', 'SAME', 'ZERO']) teachers.push(await api('/teachers', {name: 'PRIVATE_TEACHING_NAME_' + name,
    base_province: '浙江', base_city: '杭州', teacher_level: '讲师', fee_rate: 99999, phone: '13987654321', email: 'teaching-private@example.test', intro: '=HYPERLINK("https://invalid.example")'}, 'admin'));
  const a = await teaching(p1, teachers[0], 'PRIVATE_TEACHING_SUBJECT_A', '2025-01-01', {actual_minutes: '90', estimated_hours: '123456789012345.67', planned_hours: '2.00', payable_hours: '1.00'});
  const b = await teaching(p1, teachers[1], 'PRIVATE_TEACHING_SUBJECT_B', '2025-01-31', {actual_minutes: '45', estimated_hours: null, planned_hours: '1.25', payable_hours: '0'});
  const c = await teaching(p2, teachers[2], 'PRIVATE_TEACHING_SUBJECT_C', '2025-01-15', {actual_minutes: '45', estimated_hours: '1.00', planned_hours: null, payable_hours: null});
  const zero = await teaching(p2, teachers[3], 'PRIVATE_TEACHING_SUBJECT_ZERO', '2025-01-20', {actual_minutes: '0', estimated_hours: '0', planned_hours: '0', payable_hours: '0'});
  await teaching(p1, teachers[0], 'PRIVATE_TEACHING_PENDING', '2025-01-12', {}, 'pending');
  await teaching(p1, teachers[0], 'PRIVATE_TEACHING_REJECTED', '2025-01-13', {}, 'rejected');
  await teaching(p1, teachers[0], 'PRIVATE_TEACHING_UNVERIFIED', '2025-01-14', {actual_minutes: '45'}, 'unverified');
  await teaching(p2, teachers[0], 'PRIVATE_TEACHING_OUTSIDE', '2024-12-31', {actual_minutes: '450', estimated_hours: '10', planned_hours: '10', payable_hours: '10'});
  await teaching(p1, teachers[0], 'SYNTHETIC CORRUPT REVISION', '2025-01-17', {actual_minutes: '45'});
  await teaching(p1, teachers[0], 'SYNTHETIC DATE DRIFT', '2025-01-18', {actual_minutes: '45'});
  await stop(); await offline('corrupt'); const before = await offline('inspect');
  await boot(); for (const actor of ['analyst', 'reporter', 'partial', 'outsider', 'unbound', 'team']) await login(actor);
  phase = 'real XLSX and read-only scope';
  const view = await api(query()), local = await api(query(), undefined, 'reporter'), partial = await api(query(), undefined, 'partial');
  check(view.teaching_export_available === true && view.availability.formal_export === false, 'Teaching export is independently available while formal monthly export stays closed');
  check(local.teaching_export_available === false && partial.teaching_export_available === false, 'No export grant or only partial selected-organization export grant cannot enable download');
  same(view.details.map(row => row.dispatch_id), [a, b, c, zero], 'Only current verified completed heads enter workbook population');
  same(view.teacher_ranking.map(row => row.rank), [1, 2, 2, 4], 'Source supports competition ranking with real zero teaching');
  const book = await successfulDownload(view, 'reviewed-teaching.xlsx'); workbookContract(book, view, {precisionId: a});
  const subset = await api(query({organizations: '001'}), undefined, 'partial');
  check(subset.teaching_export_available === true, 'Partial exporter may download a completely authorized subset');
  const subsetBook = await successfulDownload(subset, 'reviewed-teaching-subset.xlsx', {organizations: '001'}, 'partial'); workbookContract(subsetBook, subset, {precisionId: a});
  const emptyFilters = {start: '2024-11-01', end: '2024-11-30', organizations: '999'};
  const empty = await api(query(emptyFilters)); const emptyBook = await successfulDownload(empty, 'reviewed-teaching-empty.xlsx', emptyFilters); workbookContract(emptyBook, empty);
  check(empty.included_count === 0 && empty.excluded_count === 0, 'No data produces a normal six-sheet workbook without fabricated facts');
  for (const actor of ['anonymous', 'admin', 'unbound', 'outsider']) await denied(download(view), undefined, actor, actor === 'anonymous' ? 401 : 403, actor + ' cannot download by supplying a valid snapshot');
  await denied(download(local), undefined, 'reporter', 403, 'Read permission is insufficient to download');
  await denied(download(partial), undefined, 'partial', 403, 'Every selected organization needs export permission');
  await denied(download(subset, {organizations: '001,999'}), undefined, 'partial', 403, 'A permitted subset snapshot cannot extend export scope');
  await denied(download(view, {organizations: '001,002'}), undefined, 'analyst', 403, 'An unauthorized organization is rejected rather than silently removed');
  await denied(download(view), undefined, 'partial', 403, 'Another account snapshot never supplies missing permission');
  await denied(download(subset), undefined, 'analyst', 409, 'Another authorized account cannot reuse a different actor snapshot');
  await denied('/management-reports/teaching-export?' + params(), undefined, 'analyst', 400, 'Download requires snapshot_version');
  await denied(download(view, {snapshot_version: 'bad'}), undefined, 'analyst', 400, 'Snapshot format is strict');
  await denied(download(view, {snapshot_version: '0'.repeat(64)}), undefined, 'analyst', 409, 'Unknown snapshot cannot download current output');
  await denied(download(view, {start: '2025-01-02'}), undefined, 'analyst', 409, 'Changed filter cannot be paired with old snapshot');
  await denied(download(view, {date_basis: 'PAYMENT'}), undefined, 'analyst', 409, 'No payment-basis workbook is manufactured');
  await denied('/management-reports/export?' + params({organizations: view.organizations.join(','), snapshot_version: view.snapshot_version}), undefined, 'analyst', 409, 'Original formal export remains unavailable');
  for (const injection of [{facts: '[]'}, {details: '[]'}, {actor_code: 'P1'}, {permissions: 'EXPORT'}, {organization_code: '001'}, {amount: '999'}])
    await denied(download(view, injection), undefined, 'analyst', 400, 'Download rejects client fact/identity input ' + Object.keys(injection)[0]);
  for (const suffix of ['&start=2025-01-01', '&start=2024-01-01', '&%73tart=2025-01-01', '&snapshot_version=' + view.snapshot_version, '&organizations=001'])
    await denied(download(view) + suffix, undefined, 'analyst', 400, 'Duplicate decoded filter cannot alter interpretation ' + suffix.split('=')[0]);
  for (const method of ['POST', 'PUT', 'DELETE', 'HEAD']) await denied(download(view), method === 'POST' ? {details: view.details, facts: {verified: true}} : undefined, 'analyst', 405, 'Binary route rejects ' + method + ' input', method);
  await stop(); const after = await offline('inspect'); same(after, before, 'Real successful downloads and every rejected download leave all PUBLIC tables unchanged');
  fs.writeFileSync(path.join(own, 'readonly-database.json'), JSON.stringify({before, after}, null, 2), {mode: 0o600});
  phase = 'download-time revisions and authorization';
  await boot(); for (const actor of ['analyst', 'team', 'reporter', 'partial']) await login(actor);
  const stale = await api(query()), detail = await api('/delivery-settlement?dispatch_id=' + a, undefined, 'team');
  let changed = await api('/delivery-settlement/save', {dispatch_id: a, expected_version: detail.version, request_id: 'EXPORT-FACT-REVISION', actual_minutes: '135'}, 'team');
  await denied(download(stale), undefined, 'analyst', 409, 'Download rechecks latest unverified head after saved fact revision');
  let fresh = await api(query()); check(!fresh.details.some(d => d.dispatch_id === a) && fresh.excluded.some(d => d.dispatch_id === a && d.code === 'NOT_VERIFIED'), 'Old verified revision cannot survive current unverified head');
  changed = await api('/delivery-settlement/verify', {dispatch_id: a, expected_version: changed.version, request_id: 'EXPORT-REVERIFY', evidence_code: 'SYNTHETIC-PROOF-PRIVATE'}, 'team');
  await denied(download(stale), undefined, 'analyst', 409, 'Reverified changed source still rejects old snapshot');
  fresh = await api(query());check(fresh.totals.actual.value === '5.00' && fresh.details.find(d => d.dispatch_id === a).hours.actual === '3.00', 'Current head alone supplies actual 3.00 and total 5.00');
  const refreshedBook = await successfulDownload(fresh, 'reviewed-teaching-revised.xlsx'); workbookContract(refreshedBook, fresh, {precisionId: a});
  const grants = structuredClone(configuration.grants);
  await publish({grants: configuration.grants.filter(g => !(g.roleCode === 'ANALYST' && g.resource === 'reports.export'))}, 'revoke-export');
  await denied(download(fresh), undefined, 'analyst', 403, 'Fresh login cannot download cached result after export revocation');
  check((await api(query())).teaching_export_available === false, 'Current capability reflects revoked export grant');
  await publish({grants}, 'restore-export'); await denied(download(fresh), undefined, 'analyst', 409, 'Republished permission context invalidates old snapshot');
  const allowedAgain = await api(query());await successfulDownload(allowedAgain, 'reviewed-teaching-restored.xlsx');
  await publish({grants: configuration.grants.filter(g => !(g.roleCode === 'ANALYST' && g.resource === 'reports.read'))}, 'revoke-read');
  await denied(download(allowedAgain), undefined, 'analyst', 403, 'Export grant cannot substitute revoked read grant');
  await api('/logout', {}, 'analyst');await denied(download(allowedAgain), undefined, 'analyst', 401, 'Logged-out session cannot download cached file');
  return {readonly_tables: Object.keys(before).length, source_project_ids: [p1, p2], selected_dispatch_ids: [a, b, c, zero]};
}
let evidence;
runChecks().then(result => { evidence = result; }).catch(error => { fatal = String(error.message || error).replaceAll(password, '[redacted]'); process.exitCode = 1; }).finally(async () => {
  await stop();
  const serverProcessesStopped = servicePids.every(pid => { try { process.kill(pid, 0); return false; } catch (error) { return error.code === 'ESRCH'; } });
  check(serverProcessesStopped, 'Every temporary Main process has exited');
  const summary = {suite: 'teaching-export-http', server_processes_stopped: serverProcessesStopped, checks, failures, ...(fatal ? {error: fatal} : {}), ...(evidence || {}), production_touched: false, notifications_sent: false, artifacts: own};
  fs.writeFileSync(path.join(own, 'result.json'), JSON.stringify(summary, null, 2), {mode: 0o600}); console.log(JSON.stringify(summary));
  if (failures.length || fatal) process.exitCode = 1;
});
