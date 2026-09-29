'use strict';
// Exercise the original pageReport, table, modal and authenticated API functions.
// The DOM and HTTP transport below are synthetic; browser/server verification is separate.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '../web/app.js'), 'utf8');
const between = (start, end) => { const from = source.indexOf(start), to = source.indexOf(end, from); assert.ok(from >= 0 && to > from, start); return source.slice(from, to); };
const oldSuite = fs.readFileSync(path.join(__dirname, 'IntegrationAccountBindingsUI.test.cjs'), 'utf8');
const componentChecks = fs.readFileSync(path.join(__dirname, 'M01-account-bindings-check.mjs'), 'utf8');
const { NodeStub } = vm.runInNewContext(componentChecks.slice(componentChecks.indexOf('const decode ='), componentChecks.indexOf('function harness(')) + oldSuite.slice(oldSuite.indexOf("Object.defineProperty(NodeStub.prototype, 'id'"), oldSuite.indexOf('let checks = 0;')) + '\n({ NodeStub });', { copy: structuredClone });
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const originalHtml = Object.getOwnPropertyDescriptor(NodeStub.prototype, 'innerHTML');
const html = node => `<${node.tagName.toLowerCase()}${Object.entries(node.attributes).map(([k, v]) => ` ${k}="${esc(v)}"`).join('')}>${node.innerHTML}</${node.tagName.toLowerCase()}>`;
Object.defineProperty(NodeStub.prototype, 'innerHTML', { set: originalHtml.set, get() { return esc(this._text) + this.children.map(html).join(''); } });
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
let checks = 0;
const check = (value, label) => { assert.ok(value, label); checks++; };
const equal = (actual, expected, label) => { assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected, label); checks++; };
const total = value => value === null ? { value: null, known_subtotal: '1.2300', missing_records: 1, complete: false } : { value, known_subtotal: value, missing_records: 0, complete: true };
const totals = () => ({ estimated: total(null), planned: total('9007199254740993.100'), actual: total('7.300'), payable: total(null) });
function fixture(count = 4) {
  return { population: 'REVIEWED_COMPLETED_DISPATCHES', date_basis: 'TEACHING', start: '2026-09-01', end: '2026-09-30', server_today: '2026-09-22',
    coverage_notice: '仅覆盖已建立可信机构来源的排课；未迁移旧项目不在本统计覆盖内。四类课时均来自已完成且已核对的同一批记录。',
    organizations: ['ORG-A'], available_organizations: ['ORG-A', 'ORG-B'], teaching_export_available: true, permissions: { read: true, export: true },
    included_count: count, excluded_count: 2, undated_excluded_count: 1, totals: totals(), fee: null, currency: null, course_count: null,
    adapter_version: 'DO-NOT-DISPLAY-ADAPTER', policy_version: 'DO-NOT-DISPLAY-POLICY', access_version: 'DO-NOT-DISPLAY-ACCESS', snapshot_version: 'a'.repeat(64),
    availability: { teaching: true, payment: false, fees: false, formal_export: false, reasons: [] },
    details: Array.from({ length: count }, (_, i) => ({ dispatch_id: i + 1, project_title: i ? `同批项目${i}` : '<script>danger</script>', subject: '同批授课内容', teacher_name: `讲师${i}`, organization_code: 'ORG-A', teaching_date: '2026-09-20', verified_at: '2026-09-21T00:00:00Z', hours: { estimated: null, planned: '9007199254740993.100', actual: '1.3300', payable: null } })),
    teacher_ranking: Array.from({ length: count }, (_, i) => ({ teacher_id: i + 1, teacher_name: `排名讲师${i}`, rank: [1, 2, 2, 4][i] || i + 1, dispatch_count: 1, hours: totals() })),
    organization_summary: [{ organization_code: 'ORG-A', dispatch_count: count, hours: totals() }],
    excluded: [{ dispatch_id: 999, organization_code: 'ORG-A', date_unknown: false, code: 'NOT_COMPLETED', message: '排课尚未完成<script>bad</script>' }, { dispatch_id: 998, organization_code: 'ORG-A', date_unknown: true, code: 'TEACHING_DATE_UNKNOWN', message: '授课日期缺失或无效，无法确定月份' }],
  };
}
const xlsxType = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const zipBytes = Uint8Array.of(80, 75, 3, 4, 1, 2, 3, 4); // Transport fixture, not a generated workbook.
const binaryResponse = (bytes = zipBytes, headers = {}, status = 200) => new Response(bytes, { status, headers: { 'Content-Type': xlsxType, 'Content-Disposition': 'attachment; filename="reviewed-teaching-20260901-20260930.xlsx"', ...headers } });
const legacyStats = { project_amount: 500, received: 300, profit: 200, eval_avg: 4, by_unit: [{ unit: '原客户', amt: 500 }], by_teacher: [{ name: '原讲师', hours: 10, fee: 300 }], cost_type: [{ type: '原费用', amt: 100 }], teacher_score: [{ name: '原讲师', score: 4 }] };
function harness({ view = fixture(), fetchOverride } = {}) {
  const document = new NodeStub('document'), body = new NodeStub('body'), content = new NodeStub('main', { id: 'content' });
  document.appendChild(body); body.appendChild(content); document.body = body; document.activeElement = body;
  document.createElement = name => { const node = new NodeStub(name); if (name === 'a') node.click = () => h.downloads.push({ href: node.href, filename: node.download, blob: h.urls.get(node.href) }); return node; };
  const h = { document, content, calls: [], charts: [], invalidations: 0, prints: 0, renders: 0, toasts: [], downloads: [], urls: new Map(), revoked: [], timers: new Map() };
  let timerId = 0, urlId = 0;
  const response = (data, status = 200, msg = `合成拒绝 ${status}`) => ({ status, text: async () => JSON.stringify(status === 200 ? { code: 0, data } : { code: status, msg }) });
  const normal = async (url, opts) => {
    if (opts.signal?.aborted) throw Object.assign(Error('aborted'), { name: 'AbortError' });
    if (url === '/api/stats/report') return response('原经营文字报告');
    if (url === '/api/stats/overview') return response(legacyStats);
    if (url.startsWith('/api/management-reports?')) return response(view);
    if (url.startsWith('/api/management-reports/teaching-export?')) return binaryResponse();
    throw Error(`Unexpected route ${url}`);
  };
  const noop = () => {};
  const sandbox = { document, window: { addEventListener: noop, print: () => h.prints++ }, console, AbortController, URLSearchParams, Intl, Blob, Uint8Array,
    URL: { createObjectURL: blob => { const url = 'blob:synthetic-' + ++urlId; h.urls.set(url, blob); return url; }, revokeObjectURL: url => { h.revoked.push(url); h.urls.delete(url); } },
    Date: class extends Date { constructor(...args) { super(...(args.length ? args : ['2026-09-30T16:30:00Z'])); } },
    setTimeout: callback => { const id = ++timerId; h.timers.set(id, callback); return id; }, clearTimeout: id => h.timers.delete(id), requestAnimationFrame: noop, navigator: { onLine: true }, localStorage: { removeItem: noop },
    state: { page: 'report', user: { uid: 11, role: 'viewer' }, filters: {} }, routeEpoch: 1, routeCleanups: [], chartInstances: [],
    $: (selector, root = document) => root.querySelector(selector), $$: (selector, root = document) => root.querySelectorAll(selector), esc,
    icon: name => `<i data-lucide="${name}"></i>`, countTag: value => `<span data-count="${value}">${value}</span>`, canWrite: () => false,
    refreshIcons: noop, ensureCharts: async () => {}, makeChart: (id, config) => h.charts.push({ id, config }),
    toast: (message, error) => h.toasts.push({ message, error }), renderPage: () => { h.renders++; sandbox.beginRouteEpoch(); },
    invalidateSession: () => { h.invalidations++; sandbox.beginRouteEpoch(); sandbox.state.user = null; sandbox.closeModal(); content.innerHTML = '<p>登录</p>'; },
    fetch: async (url, opts) => { h.calls.push({ url, opts }); return fetchOverride ? fetchOverride(url, opts, normal, response) : normal(url, opts); },
  };
  vm.createContext(sandbox);
  vm.runInContext(between('  function clearRouteAsync()', '  function positiveRouteId(') + between('  async function api(', '  function encodeBase64UrlUtf8(')
    + between('  function workflowLocalTime(', '  function workflowDetailHtml(') + between('  let modalPreviousFocus', '  // ============ 登录 ============') + between('  function mountReviewedReport(', '  // ============ 用户管理 ============'), sandbox);
  h.sandbox = sandbox;
  h.mount = async () => { await sandbox.pageReport(content); await tick(); };
  h.node = id => content.querySelector('#rp-reviewed-' + id);
  h.modal = () => document.querySelector('#modal-mask');
  h.emit = async (node, type) => { assert.ok(node, `${type} target exists`); await node[`on${type}`]?.({ target: node, type, preventDefault: noop }); await tick(); };
  h.set = async (key, value, event = 'change') => { h.node(key).value = value; await h.emit(h.node(key), event); };
  h.query = () => h.emit(h.node('query'), 'click');
  h.download = () => h.emit(h.node('teaching-export'), 'click');
  h.downloadCalls = () => h.calls.filter(call => call.url.startsWith('/api/management-reports/teaching-export?'));
  h.flushTimers = () => { const callbacks = [...h.timers.values()]; h.timers.clear(); callbacks.forEach(run => run()); };
  h.queries = () => h.calls.filter(call => call.url.startsWith('/api/management-reports?'));
  h.leave = () => { sandbox.beginRouteEpoch(); sandbox.state.page = 'dashboard'; content.innerHTML = '<p>后续页面</p>'; };
  return h;
}
(async () => {
  const h = harness(); await h.mount();
  const query = new URL(h.queries()[0].url, 'http://synthetic');
  equal(Object.fromEntries(query.searchParams), { start: '2026-10-01', end: '2026-10-31', date_basis: 'TEACHING' }, 'Default is current Shanghai natural month and strict query whitelist');
  equal(h.charts.map(x => x.id), ['an1', 'an2', 'an3', 'an4'], 'All four original charts remain');
  equal(h.charts[1].config.series.map(x => x.data), [[10], [30]], 'Old chart inputs remain independent of reviewed hours');
  check(h.content.textContent.includes('原经营统计（独立口径）') && h.content.querySelector('#rp-box').textContent === '原经营文字报告', 'Original report retains independent scope label');
  check(h.node('summary').textContent.includes('9007199254740993.100') && h.node('summary').textContent.includes('7.300'), 'Exact decimal strings retain precision and trailing zeroes');
  check(h.node('summary').textContent.includes('待补齐') && h.node('summary').textContent.includes('1.2300'), 'Unknown aggregate shows known subtotal without substituting it');
  check(!h.node('summary').querySelector('[data-count]'), 'New hour values never enter legacy counter animation');
  equal(h.node('organization').options.map(x => x.value), ['', 'ORG-A', 'ORG-B'], 'Organization choices only contain server-authorized candidates');
  check(h.node('export').disabled && h.node('basis').options[1].disabled, 'Formal export and payment basis stay unavailable despite export permission');
  check(!h.content.textContent.includes('DO-NOT-DISPLAY') && !h.content.textContent.includes('a'.repeat(64)), 'Technical versions do not leak into business presentation');
  equal(h.node('ranking').querySelector('tbody').children.map(row => row.children[0].textContent), ['1', '2', '2', '4'], 'Server competition ranks remain unchanged');
  check(h.node('result').textContent.includes('所选日期范围未计入：1') && h.node('result').textContent.includes('日期未知、无法归月：1'), 'Undated exclusions are separate from selected month count');
  await h.emit(h.node('details'), 'click');
  check(h.modal().textContent.includes('<script>danger</script>') && !h.modal().querySelector('script'), 'Original table safely renders source names as text');
  check(h.modal().textContent.includes('1.3300') && h.modal().textContent.includes('待补齐'), 'Detail values preserve exact string and null');
  await h.emit(h.node('excluded'), 'click');
  check(h.modal().textContent.includes('日期未知记录不代表都属于所选月份') && h.modal().textContent.includes('排课尚未完成<script>bad</script>') && !h.modal().querySelector('script'), 'Original modal shows escaped source reasons and date uncertainty');
  await h.set('month', '2028-02');
  equal([h.node('start').value, h.node('end').value], ['2028-02-01', '2028-02-29'], 'Month shortcut supports leap-year end date');
  check(!h.modal() && !h.node('summary') && h.queries()[0].opts.signal.aborted, 'Changing conditions closes owned modal, aborts old request and removes snapshot');
  await h.set('start', '2028-02-02', 'input'); check(h.node('filter-note').textContent.includes('自定义日期范围') && h.node('month').value === '', 'Manual date entry marks custom range');
  await h.set('organization', 'ORG-B'); await h.query();
  const scoped = new URL(h.queries().at(-1).url, 'http://synthetic');
  equal(scoped.searchParams.get('organizations'), 'ORG-B', 'Explicit organization is sent without display-name guessing');
  check(h.calls.filter(c => c.url.startsWith('/api/stats/')).length === 2 && h.charts.length === 4, 'Card queries do not rebuild old charts or refetch old reports');
  await h.set('organization', 'UNAUTHORIZED'); const beforeInvalid = h.queries().length; await h.query();
  check(h.queries().length === beforeInvalid && !h.node('summary') && h.node('result').textContent.includes('请选择当前可查看的机构'), 'Injected organization is rejected without expanding scope');
  await h.set('organization', ''); await h.set('start', '2028-02-30'); await h.query();
  check(h.queries().length === beforeInvalid && h.node('result').textContent.includes('有效的开始和结束日期'), 'Invalid calendar dates never query or preserve stale results');
  await h.set('start', '2028-03-01'); await h.query();
  check(h.node('result').textContent.includes('开始日期不能晚于结束日期'), 'Reversed date range is locally rejected');
  h.content.querySelector('#rp-print').onclick(); equal(h.prints, 1, 'Original print action is retained');
  check(h.calls.every(c => c.opts.method === 'GET') && h.queries().every(c => c.opts.credentials === 'same-origin' && c.opts.signal), 'New card only uses authenticated, cancellable read requests');
  check(!h.calls.some(c => c.url.includes('/export')), 'No formal/demo download branch is invoked');

  for (const status of [400, 403, 409, 500]) {
    let fail = false;
    const denied = harness({ fetchOverride: (url, opts, normal, response) => fail && url.startsWith('/api/management-reports?') ? response(null, status, status === 409 ? '当前机构授课来源超过一次查询上限，请缩小机构范围' : `合成拒绝 ${status}`) : normal(url, opts) });
    await denied.mount(); await denied.emit(denied.node('details'), 'click'); fail = true; await denied.query();
    check(!denied.node('summary') && !denied.modal() && denied.node('result').textContent.includes(status === 409 ? '缩小机构范围' : String(status)), `${status}: replaces prior snapshot and closes its modal with genuine error`);
    check(denied.content.querySelector('#rp-box').textContent === '原经营文字报告' && denied.charts.length === 4, `${status}: old report and charts remain usable`);
    if (status === 403) equal(denied.node('organization').options.map(x => x.value), [''], '403 also removes previously authorized organization options');
    denied.leave();
  }
  const unauthorized = harness({ fetchOverride: (url, opts, normal, response) => url.startsWith('/api/management-reports?') ? response(null, 401) : normal(url, opts) }); await unauthorized.mount();
  check(unauthorized.invalidations === 1 && unauthorized.content.textContent === '登录', '401 follows the original session invalidation and prevents subsequent updates');

  const malformed = harness({ view: { ...fixture(), totals: {} } }); await malformed.mount();
  check(malformed.node('result').textContent.includes('统计响应格式不完整') && !malformed.node('summary'), 'Malformed successful envelope is an error, never an empty report');
  const network = harness({ fetchOverride: (url, opts, normal) => { if (url.startsWith('/api/management-reports?')) throw Error('offline'); return normal(url, opts); } }); await network.mount();
  check(network.node('result').textContent.includes('网络连接失败') && !network.node('summary'), 'Network failure is distinct from no records');
  const emptyView = fixture(0); emptyView.totals = Object.fromEntries(['estimated', 'planned', 'actual', 'payable'].map(key => [key, total('0')])); emptyView.excluded = []; emptyView.excluded_count = 0; emptyView.undated_excluded_count = 0; emptyView.organization_summary = [];
  const empty = harness({ view: emptyView }); await empty.mount();
  check(empty.node('summary').textContent.includes('实际课时0') && empty.node('details').disabled && empty.node('excluded').disabled, 'Real empty response preserves explicit zeros and disables absent detail lists');
  check(empty.node('result').textContent.includes('该范围暂无已核对授课') && empty.node('result').textContent.includes('本卡只统计已核对的授课事实，不计算金额'), 'No records does not make unavailable financial statistics known zero');

  const manyView = fixture(45); manyView.organization_summary = Array.from({ length: 41 }, (_, i) => ({ organization_code: `ORG-${i}`, dispatch_count: 1, hours: totals() }));
  const many = harness({ view: manyView }); await many.mount();
  equal(many.node('ranking').querySelector('tbody').children.length, 20, 'Ranking renders at most 20 records per page');
  equal(many.node('organizations').querySelector('tbody').children.length, 20, 'Organization summary renders at most 20 records per page');
  await many.emit(many.node('ranking').querySelector('[data-reviewed-page="next"]'), 'click');
  check(many.node('ranking').textContent.includes('排名讲师20') && !many.node('ranking').textContent.includes('排名讲师0'), 'Ranking next page uses source rows without resorting');
  await many.emit(many.node('details'), 'click'); equal(many.modal().querySelector('tbody').children.length, 20, 'Large source details are paginated in original modal');
  await many.emit(many.modal().querySelector('[data-reviewed-page="next"]'), 'click');
  check(many.modal().textContent.includes('同批项目20') && !many.modal().textContent.includes('同批项目1实际'), 'Detail pager moves to next records');
  const successor = many.sandbox.openModal('后续弹窗', '<p>保留</p>', { noFoot: true }); many.leave();
  check(many.modal() === successor, 'Route cleanup cannot close a successor modal owned elsewhere'); many.sandbox.closeModal();

  const waiting = deferred();
  const late = harness({ fetchOverride: (url, opts, normal, response) => url.startsWith('/api/management-reports?') ? waiting.promise.then(response) : normal(url, opts) }); await late.mount();
  const pendingRequest = late.queries()[0]; await late.set('start', '2026-10-05', 'input'); waiting.resolve(fixture()); await tick();
  check(pendingRequest.opts.signal.aborted && !late.node('summary') && late.node('result').textContent.includes('筛选条件已变化'), 'A response arriving after date edits cannot restore stale data');
  const first = deferred(), second = deferred(); let queryCount = 0;
  const racing = harness({ fetchOverride: (url, opts, normal, response) => url.startsWith('/api/management-reports?') ? (++queryCount === 1 ? first : second).promise.then(response) : normal(url, opts) }); await racing.mount();
  const nextQuery = racing.query(); const latest = fixture(); latest.totals.actual = total('222.00'); second.resolve(latest); await nextQuery;
  first.resolve(fixture()); await tick(); check(racing.node('summary').textContent.includes('222.00') && !racing.node('summary').textContent.includes('7.300'), 'Ticket plus abort rejects older overlapping query completion');
  const leaving = deferred(); const departed = harness({ fetchOverride: (url, opts, normal, response) => url.startsWith('/api/management-reports?') ? leaving.promise.then(response) : normal(url, opts) }); await departed.mount(); departed.leave(); leaving.resolve(fixture()); await tick();
  check(departed.queries()[0].opts.signal.aborted && departed.content.textContent === '后续页面' && !departed.modal(), 'Leaving report aborts and prevents any stale page/modal write');
  const owned = harness(); await owned.mount(); await owned.emit(owned.node('details'), 'click'); owned.leave(); check(!owned.modal(), 'Leaving report closes its own existing detail modal');
  const exported = harness(); await exported.mount();
  check(!exported.node('teaching-export').disabled && exported.node('export').disabled, 'Server teaching export capability enables only the new original toolbar button');
  check(exported.node('download-status').textContent.includes('非结算文件') && exported.node('download-status').textContent.includes('不含金额和支付') && exported.node('download-status').textContent.includes('不是正式编码'), 'Original page clearly states the facts-only and system-ID boundary');
  // Change raw input properties without dispatching an event: export must still bind to the displayed snapshot.
  exported.node('start').value = '2020-01-01'; exported.node('organization').value = 'ORG-B';
  await exported.download(); const downloadRequest = exported.downloadCalls()[0];
  equal(Object.fromEntries(new URL(downloadRequest.url, 'http://synthetic').searchParams), { start: '2026-09-01', end: '2026-09-30', date_basis: 'TEACHING', organizations: 'ORG-A', snapshot_version: 'a'.repeat(64) }, 'Download sends only the validated displayed snapshot scope and version');
  check(downloadRequest.opts.credentials === 'same-origin' && downloadRequest.opts.method === 'GET' && downloadRequest.opts.cache === 'no-store' && downloadRequest.opts.signal instanceof AbortSignal && !downloadRequest.opts.body && !downloadRequest.opts.headers.Authorization, 'Binary request uses the real same-origin cookie and route-owned abort signal without body or client auth claims');
  equal(exported.downloads.map(d => d.filename), ['reviewed-teaching-20260901-20260930.xlsx'], 'Explicit original button click starts one download with the fixed safe range filename');
  check(exported.downloads[0].blob.type === xlsxType && exported.downloads[0].blob.size === 8 && !exported.node('teaching-export').disabled && exported.document.querySelectorAll('a').length === 0, 'Successful body creates a typed blob, removes temporary link and restores button');
  exported.flushTimers(); check(exported.urls.size === 0 && exported.revoked.length === 1, 'Created object URL is promptly revoked');
  check(exported.charts.length === 4 && exported.prints === 0 && exported.calls.filter(call => call.url.startsWith('/api/stats/')).length === 2, 'Download leaves original charts, report and printing untouched');
  await exported.download(); exported.leave(); check(exported.urls.size === 0 && exported.revoked.length === 2, 'Route cleanup revokes a successful download URL before its timer');

  const readOnlyView = fixture(); readOnlyView.teaching_export_available = false;
  const readOnly = harness({ view: readOnlyView }); await readOnly.mount(); await readOnly.download();
  check(readOnly.node('teaching-export').disabled && readOnly.downloadCalls().length === 0 && readOnly.node('download-status').textContent.includes('暂无授课统计导出权限'), 'A true legacy export flag never bypasses the dedicated server capability');
  for (const malformed of ['missing capability', 'string capability', 'invalid snapshot']) {
    const view = fixture(); if (malformed === 'missing capability') delete view.teaching_export_available; else if (malformed === 'string capability') view.teaching_export_available = 'true'; else view.snapshot_version = '../invalid';
    const invalid = harness({ view }); await invalid.mount();
    check(!invalid.node('teaching-export') && invalid.node('result').textContent.includes('响应格式'), malformed + ': invalid query response never obtains download qualification');
  }
  for (const status of [401, 403, 409, 500]) {
    const failed = harness({ fetchOverride: (url, opts, normal) => url.startsWith('/api/management-reports/teaching-export?') ? new Response(JSON.stringify({ code: status, msg: '合成导出拒绝 ' + status }), { status, headers: { 'Content-Type': 'application/json' } }) : normal(url, opts) });
    await failed.mount(); await failed.emit(failed.node('details'), 'click'); await failed.download();
    check(failed.downloads.length === 0 && failed.urls.size === 0 && !failed.node('summary') && !failed.modal(), status + ': JSON failure creates no file and removes stale snapshot and its owned detail modal');
    if (status === 401) check(failed.invalidations === 1 && failed.content.textContent === '登录', 'Export 401 follows original session invalidation and does not overwrite login');
    else check(failed.node('result').textContent.includes('合成导出拒绝 ' + status) && failed.node('result').textContent.includes('重新核对') && failed.charts.length === 4, status + ': failure preserves original report and requests explicit requery');
    if (status === 403) equal(failed.node('organization').options.map(option => option.value), [''], 'Export 403 also clears formerly authorized organization choices');
  }
  const badDownloads = [
    ['network', () => { throw Error('synthetic offline'); }],
    ['JSON success MIME', () => binaryResponse('{"code":403}', { 'Content-Type': 'application/json' })],
    ['HTML success MIME', () => binaryResponse('<html>login</html>', { 'Content-Type': 'text/html' })],
    ['JSON disguised as XLSX', () => binaryResponse('{"code":403}')],
    ['empty body', () => binaryResponse(new Uint8Array())],
    ['partial success', () => binaryResponse(zipBytes, {}, 206)],
    ['oversized declared length', () => binaryResponse(zipBytes, { 'Content-Length': '16777217' })],
    ['oversized streamed body', () => binaryResponse(new Uint8Array(16777217))],
  ];
  for (const [label, respond] of badDownloads) {
    const bad = harness({ fetchOverride: (url, opts, normal) => url.startsWith('/api/management-reports/teaching-export?') ? respond() : normal(url, opts) }); await bad.mount(); await bad.download();
    check(bad.downloads.length === 0 && bad.urls.size === 0 && !bad.node('summary') && bad.downloadCalls().length === 1 && bad.node('result').textContent.includes('重新核对'), label + ': failure never downloads or automatically retries and requires a fresh query');
  }
  const unsafeFilename = harness({ fetchOverride: (url, opts, normal) => url.startsWith('/api/management-reports/teaching-export?') ? binaryResponse(zipBytes, { 'Content-Disposition': 'attachment; filename="../../personal-data.html"' }) : normal(url, opts) }); await unsafeFilename.mount(); await unsafeFilename.download();
  equal(unsafeFilename.downloads.map(d => d.filename), ['reviewed-teaching-20260901-20260930.xlsx'], 'Untrusted response filename cannot alter the locally fixed snapshot filename'); unsafeFilename.flushTimers();

  for (const point of ['headers', 'body']) for (const transition of ['date', 'organization', 'query', 'route', 'identity']) {
    const pending = deferred(); let stream;
    const race = harness({ fetchOverride: (url, opts, normal) => {
      if (!url.startsWith('/api/management-reports/teaching-export?')) return normal(url, opts);
      if (point === 'headers') return pending.promise;
      return new Response(new ReadableStream({ start(controller) { stream = controller; } }), { headers: { 'Content-Type': xlsxType } });
    } });
    await race.mount(); const inFlight = race.download(); await tick(); await race.download();
    check(race.downloadCalls().length === 1 && race.node('teaching-export').disabled, point + '/' + transition + ': repeated clicks cannot duplicate a pending download');
    if (transition === 'date') await race.set('start', '2026-09-02', 'input');
    if (transition === 'organization') await race.set('organization', 'ORG-B');
    if (transition === 'query') await race.query();
    if (transition === 'route') race.leave();
    if (transition === 'identity') race.sandbox.state.user = { uid: 11, role: 'viewer' };
    if (point === 'headers') pending.resolve(binaryResponse()); else { stream.enqueue(zipBytes); stream.close(); }
    await inFlight;
    check(race.downloads.length === 0 && race.urls.size === 0, point + '/' + transition + ': late completion cannot create any object URL or download');
    if (transition !== 'identity') check(race.downloadCalls()[0].opts.signal.aborted, point + '/' + transition + ': transition aborts the original download request');
  }
  const oldIdentity = deferred();
  const obsolete401 = harness({ fetchOverride: (url, opts, normal) => url.startsWith('/api/management-reports/teaching-export?') ? oldIdentity.promise : normal(url, opts) }); await obsolete401.mount(); const oldDownload = obsolete401.download(); await tick(); obsolete401.sandbox.state.user = { uid: 22, role: 'viewer' }; oldIdentity.resolve(new Response('{"code":401}', { status: 401 })); await oldDownload;
  check(obsolete401.invalidations === 0 && obsolete401.downloads.length === 0, 'A late 401 from an old identity cannot log out its successor');

  console.log(JSON.stringify({ ok: true, suite: 'original reviewed report host integration', checks, transport: 'synthetic', browser: false, database: false }));
})().catch(error => { console.error(error); process.exitCode = 1; });
