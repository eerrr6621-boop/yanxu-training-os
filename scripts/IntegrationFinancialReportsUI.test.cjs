'use strict';
// Component plus original renderTable, synthetic DOM/transport. No service or database.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const repo = process.env.YANXU_APP || path.resolve(__dirname, '..');
const support = fs.readFileSync(path.join(repo, 'scripts/M01-account-bindings-check.mjs'), 'utf8');
const { NodeStub } = vm.runInNewContext(support.slice(support.indexOf('const decode ='), support.indexOf('function fixture()')) + '\n({NodeStub})', {});
const app = fs.readFileSync(path.join(repo, 'web/app.js'), 'utf8');
const originalTable = app.slice(app.indexOf('  function renderTable('), app.indexOf('  function actionIcon('));
const source = fs.readFileSync(path.join(repo, 'web/modules/reports/financial-reports.js'), 'utf8');
const keys = ['ESTIMATED', 'PLANNED', 'ACTUAL', 'PAYABLE'], mime = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const clone = structuredClone;
let checks = 0, scenarios = 0;
const check = (v, label) => { assert.ok(v, label); checks++; };
const equal = (v, expected, label) => { assert.deepEqual(JSON.parse(JSON.stringify(v)), expected, label); checks++; };
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const error = status => Object.assign(Error('Synthetic error'), { status });
const tick = () => new Promise(r => setImmediate(r));
const hours = (payment, missing = false) => Object.fromEntries(keys.map(k => [k, { total: payment || missing ? null : '1.125', known_subtotal: payment ? null : '1.125', missing_count: payment ? null : missing ? 1 : 0, applicable: !payment }]));
function fixture(query = new URLSearchParams()) {
  const payment = query.get('date_basis') === 'PAYMENT', date = query.get('start') || '2026-09-01';
  const sum = { amount: '5.00', positive_amount: '5.00', negative_amount: '0.00', entry_count: 3, hours: hours(payment) };
  const teachers = [1, 2, 3].map((n, i) => ({ ...clone(sum), entry_count: 1, teacher_id: n, teacher_code: 'T-' + n, teacher_codes: ['T-' + n], teacher_display_name: '合成讲师 ' + n, rank: [1, 1, 3][i], rank_value: ['2.00', '2.00', '1.00'][i] }));
  const selected = query.get('organizations')?.split(',') || ['ORG-A', 'ORG-B'];
  return { schema_version: 'M06-SETTLEMENT-1', start: date, end: query.get('end') || '2026-09-30', date_basis: payment ? 'PAYMENT' : 'TEACHING', rank_metric: query.get('rank_metric') || 'HOURS', hour_basis: query.get('hour_basis') || 'ACTUAL',
    currency: 'CNY', hours_applicable: !payment, tie_rule: 'COMPETITION', source_coverage: 'APPROVED_FROZEN_LEDGER_ONLY', coverage_note: '仅统计可信已核准冻结账。',
    available_organizations: ['ORG-A', 'ORG-B'], available_organization_options: [{ organization_code: 'ORG-A', display_name: '合成分公司甲' }, { organization_code: 'ORG-B', display_name: '合成分公司乙' }], selected_organizations: selected,
    permissions: { read: true, export: true }, can_export: true, export_available: true, totals: sum, activity_totals: [], teachers,
    organizations: [{ organization_code: selected[0], ...clone(sum) }], code_gaps: [], source_versions: [{ source_version: 'e'.repeat(64) }], access_version: 'private-access-hash', snapshot_version: 'a'.repeat(64), generated_at: '2026-09-23T08:00:00Z',
    details: teachers.map((r, i) => ({ entry_code: 'ENTRY-' + i, organization_code: selected[0], project_id: 9, teacher_id: r.teacher_id, teacher_code: r.teacher_code, teacher_display_name: r.teacher_display_name, course_id: 5, course_code: 'C-5', activity: 'TEACHING', kind: payment ? 'PAYMENT' : 'CONFIRMED', date, amount: '1.00', hours: Object.fromEntries(keys.map(k => [k, payment ? null : '1.125'])), hours_before: Object.fromEntries(keys.map(k => [k, payment ? null : '0'])), hours_after: Object.fromEntries(keys.map(k => [k, payment ? null : '1.125'])), hours_counted: !payment })) };
}
function file(query, options = {}) {
  const bytes = options.bytes || Uint8Array.from([80, 75, 3, 4, 0, 0, 0, 0]);
  const filename = `settlement-${query.get('date_basis').toLowerCase()}-${query.get('start').replaceAll('-', '')}-${query.get('end').replaceAll('-', '')}.xlsx`;
  const headers = new Headers({ 'Content-Type': mime, 'Content-Disposition': `attachment; filename="${filename}"`, 'Content-Length': String(bytes.length), ...options.headers });
  if (options.noLength) headers.delete('Content-Length');
  let index = 0, canceled = false;
  const stream = { getReader: () => ({ read: async () => index++ === 0 ? { done: false, value: bytes } : { done: true }, cancel: async () => { canceled = true; }, releaseLock() {} }), cancel: async () => { canceled = true; } };
  return { status: options.status || 200, ok: !options.status || options.status === 200, redirected: options.redirected || false, headers, body: options.body || stream, canceled: () => canceled };
}
function harness({ apiOverride, downloadOverride, root = new NodeStub(), signal } = {}) {
  const h = { root, calls: [], downloads: [], saved: [], unauthorized: 0, current: true, identity: { uid: 1 }, released: 0 };
  const sandbox = { AbortController, URLSearchParams, Date, Intl, Blob, Uint8Array, console };
  vm.createContext(sandbox);
  vm.runInContext(source.replace('export function mount', 'function mount') + '\n' + originalTable + '\nglobalThis.create = mount;globalThis.table = renderTable;', sandbox);
  h.host = { api: async (url, opts) => { h.calls.push({ url, opts }); const query = new URLSearchParams(url.split('?')[1]); return apiOverride ? apiOverride(url, opts, query, h) : fixture(query); },
    getUser: () => h.identity, isCurrent: () => h.current, renderTable: sandbox.table,
    fetchDownload: async (url, opts) => { h.downloads.push({ url, opts }); const query = new URLSearchParams(url.split('?')[1]); return downloadOverride ? downloadOverride(url, opts, query, h) : file(query); },
    saveFile: (blob, filename) => { h.saved.push({ blob, filename }); return () => { h.released++; }; },
    onUnauthorized: () => { h.unauthorized++; }, signal, now: () => new Date('2026-09-23T01:00:00Z') };
  h.node = key => root.querySelector('[data-financial-' + key + ']');
  h.change = (key, value) => { h.node(key).value = value; h.node(key).onchange(); };
  h.click = key => h.node(key).onclick(); h.tab = key => root.querySelector('[data-financial-tab="' + key + '"]').onclick();
  h.controller = sandbox.create(root, h.host); return h;
}
async function scenario(name, run) { await run(); scenarios++; console.log('PASS ' + name); }
(async () => {
  await scenario('Original table and exact accounting values', async () => {
    const h = harness({ apiOverride: (_, __, q) => { const v = fixture(q); v.totals.amount = '9007199254740993.01'; v.teachers[0].hours.PAYABLE = { applicable: true, total: null, known_subtotal: '0.25', missing_count: 1 }; return v; } });
    check(await h.controller.ready, 'Valid source renders'); check(h.root.querySelector('table')?.classList.contains('tbl'), 'Original table.tbl reused');
    check(h.root.textContent.includes('9007199254740993.01'), 'Amount retains exact decimal string'); check(h.root.textContent.includes('合成讲师 1'), 'Trusted teacher name shown');
    check(h.node('organization').textContent.includes('合成分公司甲'), 'Organization display names shown');
    equal(h.node('table').querySelector('tbody').children.map(row => row.children[0].textContent), ['1', '1', '3'], 'Server competition ranking retained');
    check(h.node('table').textContent.includes('待补齐'), 'Unknown hours never rendered zero');
    for (const privateText of ['a'.repeat(64), 'e'.repeat(64), 'private-access-hash']) check(!h.root.textContent.includes(privateText), 'Technical hash not displayed');
    const calls = h.calls.length; h.tab('organizations'); check(h.node('table').textContent.includes('合成分公司甲'), 'Organization summary uses name');
    h.tab('details'); check(h.node('table').textContent.includes('项目系统记录 ID'), 'Trace ID labelled as system record'); equal(h.calls.length, calls, 'Details are from same snapshot without refetch');
    await h.click('export'); equal(h.saved.length, 1, 'Valid file downloaded'); equal(h.saved[0].filename, 'settlement-teaching-20260901-20260930.xlsx', 'Frozen dates determine filename');
    const query = new URLSearchParams(h.downloads[0].url.split('?')[1]); equal(query.get('snapshot_version'), 'a'.repeat(64), 'Exact server CAS included'); equal(query.get('organizations'), 'ORG-A,ORG-B', 'Same selected scope exported');
    equal(h.downloads[0].opts.credentials, 'same-origin', 'Same origin credentials'); equal(h.downloads[0].opts.redirect, 'error', 'No redirected file');
    h.controller.destroy(); equal(h.released, 1, 'Download resource cleanup runs'); equal(h.root.textContent, '', 'Destroy clears financial data');
  });
  await scenario('Unknown hours corrected to known display final state without summing history', async () => {
    const h = harness({ apiOverride: (_, __, q) => { const v = fixture(q); v.details[0].hours.ACTUAL=null; v.details[0].hours_before.ACTUAL=null; v.details[0].hours_after.ACTUAL='2'; v.details[0].hours_counted=true; v.details[1].hours_counted=false; return v; } });
    await h.controller.ready; h.tab('details'); check(h.node('table').textContent.includes('实际课时增减'), 'Delta explicitly labelled'); check(h.node('table').textContent.includes('调整后实际课时'), 'After state separately labelled'); check(h.node('table').textContent.includes('本日期净课时') && h.node('table').textContent.includes('历史修订'), 'Current versus history count explicit'); check(h.node('table').textContent.includes('无法比较'), 'Unknown delta is not unknown final state'); h.controller.destroy();
  });
  await scenario('PAYMENT filters and non-applicable hours', async () => {
    const h = harness(); await h.controller.ready; h.change('basis', 'PAYMENT'); equal(h.node('rank').value, 'FEE', 'Payment forces server fee metric'); check(h.node('rank').disabled && h.node('hour').disabled, 'Invalid payment hour-ranking disabled');
    check(!h.root.textContent.includes('合成讲师'), 'Changed filter immediately clears old facts'); await h.controller.refresh();
    check(h.root.textContent.includes('支付流水不分摊四类课时') && h.root.textContent.includes('不适用'), 'Payment null explicitly non-applicable'); h.tab('details'); check(h.node('table').textContent.includes('不适用'), 'Details also avoid zero hours');
    await h.click('export'); equal(h.saved[0].filename, 'settlement-payment-20260901-20260930.xlsx', 'Payment filename retained'); h.controller.destroy();
  });
  await scenario('Exact filters, no default guessing and local date rejection', async () => {
    const h = harness(); await h.controller.ready; h.change('organization', 'ORG-A'); h.change('start', '2026-09-10'); h.change('end', '2026-09-11'); h.change('hour', 'PAYABLE'); await h.controller.refresh();
    const q = new URLSearchParams(h.calls.at(-1).url.split('?')[1]); equal(Object.fromEntries(q), { start: '2026-09-10', end: '2026-09-11', date_basis: 'TEACHING', rank_metric: 'HOURS', hour_basis: 'PAYABLE', organizations: 'ORG-A' }, 'Request uses exact chosen filters');
    const before = h.calls.length; h.change('start', '2026-02-30'); await h.controller.refresh(); equal(h.calls.length, before, 'Invalid calendar date never requested'); check(!h.root.textContent.includes('合成讲师'), 'Invalid date clears old facts'); h.controller.destroy();
  });
  await scenario('Pagination and tab reset', async () => {
    const h = harness({ apiOverride: (_, __, q) => { const v = fixture(q); v.teachers = Array.from({ length: 41 }, (_, i) => ({ ...clone(v.teachers[0]), teacher_id: i + 1, teacher_display_name: '合成第' + (i + 1) + '位', rank: i + 1 })); return v; } });
    await h.controller.ready; h.click('next'); check(h.node('table').textContent.includes('合成第21位'), 'Next page reads same array'); h.click('next'); check(h.node('next').disabled, 'Last-page bound');
    h.tab('organizations'); check(h.node('prev').disabled, 'Switching dataset resets page'); h.tab('teachers'); check(h.node('table').textContent.includes('合成第1位'), 'Switching back starts page one'); h.controller.destroy();
  });
  await scenario('Malformed DTO fails closed', async () => {
    const mutations = [v => v.snapshot_version = 'bad', v => v.permissions.read = false, v => v.available_organization_options = [], v => v.selected_organizations = ['HIDDEN'], v => v.date_basis = 'PAYMENT', v => v.totals.amount = 5,
      v => v.totals.hours.ACTUAL.total = 0, v => v.teachers[0].rank = -1, v => delete v.teachers[0].teacher_display_name, v => v.details[0].organization_code = 'HIDDEN', v => v.details[0].amount = 1, v => delete v.details[0].hours_after, v => v.details[0].hours_counted = 'true', v => v.totals.entry_count = 99, v => v.export_available = false];
    for (const mutate of mutations) { const h = harness({ apiOverride: (_, __, q) => { const v = fixture(q); mutate(v); return v; } }); check(await h.controller.ready === false, 'Malformed response rejected'); check(!h.node('export') && !h.root.textContent.includes('合成讲师'), 'Bad DTO yields no retained data or export'); h.controller.destroy(); }
  });
  await scenario('Escaped names and coverage text', async () => {
    const x = '<img src=x onerror=alert(1)>', h = harness({ apiOverride: (_, __, q) => { const v = fixture(q); v.teachers[0].teacher_display_name = x; v.coverage_note = x; v.available_organization_options[0].display_name = x; return v; } });
    await h.controller.ready; check(!h.root.querySelector('img,script'), 'Server text cannot create markup'); check(h.root.textContent.includes(x), 'Original text remains readable'); h.controller.destroy();
  });
  await scenario('Queries cancel stale results and identity changes', async () => {
    const gate = deferred(); let requests = 0; const h = harness({ apiOverride: (_, __, q) => ++requests === 1 ? gate.promise : fixture(q) });
    const firstSignal = h.calls[0].opts.signal; h.change('basis', 'PAYMENT'); const newer = h.controller.refresh(); await newer;
    check(firstSignal.aborted, 'Old query aborted'); gate.resolve(fixture()); await h.controller.ready; check(h.root.textContent.includes('收付净额') && !h.root.textContent.includes('应计净额（元）'), 'Late earlier snapshot cannot replace payment query');
    h.identity = { uid: 2 }; await h.controller.refresh(); equal(h.root.textContent, '', 'Changed account clears entire component');
    const late = deferred(), route = harness({ apiOverride: () => late.promise }); route.current = false; late.resolve(fixture()); await route.controller.ready; equal(route.root.textContent, '', 'Late response after route change leaves no private data');
    const signal = new AbortController(), canceled = harness({ signal: signal.signal }); await canceled.controller.ready; signal.abort(); equal(canceled.root.textContent, '', 'Host abort destroys data');
  });
  await scenario('Query errors clear stale data without interpreting missing source as zero', async () => {
    for (const status of [401, 403, 409, 503]) { let broken = false; const h = harness({ apiOverride: (_, __, q) => { if (broken) throw error(status); return fixture(q); } }); await h.controller.ready; broken = true; await h.controller.refresh();
      check(!h.root.textContent.includes('合成讲师') && !h.node('export'), 'Error clears prior snapshot'); if (status === 401) equal(h.unauthorized, 1, 'Expired session reported to host'); if ([401, 403].includes(status)) check(!h.node('organization').textContent.includes('合成分公司'), 'Authorization failure clears organization names'); if (status === 503) check(h.root.textContent.includes('不表示没有金额'), 'Unavailable source never becomes zero'); h.controller.destroy(); }
  });
  await scenario('Download errors and strict file contract', async () => {
    const badFiles = [q => file(q, { status: 401 }), q => file(q, { status: 403 }), q => file(q, { status: 409 }), q => file(q, { headers: { 'Content-Type': 'application/json' } }), q => file(q, { headers: { 'Content-Disposition': 'attachment; filename="../../x.xlsx"' } }),
      q => file(q, { headers: { 'Content-Length': String(16 * 1024 * 1024 + 1) } }), q => file(q, { headers: { 'Content-Length': '9' } }), q => file(q, { redirected: true }), q => file(q, { bytes: Uint8Array.from([60, 104, 116, 109]) }), q => file(q, { bytes: new Uint8Array(16 * 1024 * 1024 + 1), noLength: true })];
    for (const bad of badFiles) { const h = harness({ downloadOverride: (_, __, q) => bad(q) }); await h.controller.ready; await h.click('export'); equal(h.saved.length, 0, 'Invalid download never saved'); check(!h.node('export') && !h.root.textContent.includes('合成讲师'), 'Failed download requires requery and clears stale facts'); h.controller.destroy(); }
  });
  await scenario('Download races and unavailable export', async () => {
    const gate = deferred(), h = harness({ downloadOverride: (_, __, q) => gate.promise.then(() => file(q)) }); await h.controller.ready; const pending = h.click('export'); await tick(); h.click('export'); equal(h.downloads.length, 1, 'Only one in-flight download');
    h.change('basis', 'PAYMENT'); gate.resolve(); await pending; equal(h.saved.length, 0, 'Changed filters cancel pending file'); check(h.downloads[0].opts.signal.aborted, 'Download signal aborted on change'); h.controller.destroy();
    for (const permitted of [true, false]) { const x = harness({ apiOverride: (_, __, q) => { const v = fixture(q); v.can_export = v.permissions.export = permitted; v.export_available = false; if (permitted) v.code_gaps = [{ entry_code: 'ENTRY-1', organization_code: 'ORG-A', missing_fields: ['teacher_code'] }]; return v; } }); await x.controller.ready;
      check(x.node('export').disabled, 'Unavailable export remains disabled'); await x.click('export'); equal(x.downloads.length, 0, 'Handler cannot bypass export condition'); check(x.root.textContent.includes(permitted ? '缺少正式讲师或课程编码' : '没有所选机构的导出权限'), 'Correct unavailable-export reason'); x.controller.destroy(); }
  });
  console.log(JSON.stringify({ scenarios, checks, failed: 0, component: path.join(repo, 'web/modules/reports/financial-reports.js') }));
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
