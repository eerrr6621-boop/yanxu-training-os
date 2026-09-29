'use strict';
// Original app.js pages/modal/forms/table/API plus real M04 and M05 components.
// Only DOM and authenticated HTTP transport are synthetic; no browser/server/database.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const { webcrypto } = require('node:crypto');
const source = fs.readFileSync(path.join(__dirname, '../web/app.js'), 'utf8');
const between = (start, end) => { const a = source.indexOf(start), b = source.indexOf(end, a); assert.ok(a >= 0 && b > a, start); return source.slice(a, b); };
const oldSuite = fs.readFileSync(path.join(__dirname, 'IntegrationAccountBindingsUI.test.cjs'), 'utf8');
const componentChecks = fs.readFileSync(path.join(__dirname, 'M01-account-bindings-check.mjs'), 'utf8');
const support = vm.runInNewContext(componentChecks.slice(componentChecks.indexOf('const decode ='), componentChecks.indexOf('function harness(')) + oldSuite.slice(oldSuite.indexOf("Object.defineProperty(NodeStub.prototype, 'id'"), oldSuite.indexOf('let checks = 0;')) + '\n({ NodeStub });', { copy: structuredClone });
const { NodeStub } = support;
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const inner = Object.getOwnPropertyDescriptor(NodeStub.prototype, 'innerHTML');
const html = node => `<${node.tagName.toLowerCase()}${Object.entries(node.attributes).map(([k, v]) => ` ${k}="${esc(v)}"`).join('')}>${node.innerHTML}</${node.tagName.toLowerCase()}>`;
Object.defineProperty(NodeStub.prototype, 'innerHTML', { set: inner.set, get() { return esc(this._text) + this.children.map(html).join(''); } });
Object.defineProperty(NodeStub.prototype, 'ownerDocument', { get() { return this.tagName === 'DOCUMENT' ? this : this.parentNode?.ownerDocument || this._doc; } });
NodeStub.prototype.append = function (...nodes) { nodes.forEach(n => this.appendChild(n)); };
NodeStub.prototype.getElementById = function (id) { return this.querySelector('#' + id); };
NodeStub.prototype.checkValidity = () => true;
NodeStub.prototype.scrollIntoView = () => {};
const dataModule = text => 'data:text/javascript;base64,' + Buffer.from(text).toString('base64');
let modules, checks = 0;
const check = (yes, label) => { assert.ok(yes, label); checks++; };
const equal = (a, b, label) => { assert.deepEqual(JSON.parse(JSON.stringify(a)), b, label); checks++; };
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
const project = (id, controlled = true) => ({ id, title: `合成项目 ${id}`, unit: '合成机构', owner: '合成人员', status: '进行中', hours: 2, delivery_controlled: controlled, amount: 0,
  delivery_hours: controlled ? { actual: '1.33', estimated: '2.00', planned: '2.00', payable: null, pending_count: 1, unverified_completed_count: 0 } : undefined });
const dispatch = (id, controlled = true) => ({ id, project_id: controlled ? 5 : 6, teacher_id: 8, project_title: '合成项目', project_status: '进行中', subject: `合成课程 ${id}`, teacher_name: '合成讲师', status: '已确认', material_status: '已就绪', hours: 9, teach_date: '2026-09-20', delivery_controlled: controlled });
function detail(save = true, verify = true) {
  return { dispatch_id: 7, project_id: 5, teacher_id: 8, organization_code: 'ORG-SYNTH', version: 1,
    fact: { hours: { estimated: '2.00', planned: '2.00', actual: null, payable: null }, verification: null }, conversion: null,
    capabilities: { current_server: true, fact_version: 1, permissions: { save, verify, complete: verify }, can_save: save, can_verify: false, can_complete: false, reasons: {} } };
}
function harness({ page = 'dispatches', writer = false, facts = detail(), identity = 'BOUND', scopes = [], moduleWait, moduleFailure, fetchOverride, projectRows, dispatchRows, contextProjectId = null } = {}) {
  const document = new NodeStub('document'), body = new NodeStub('body'), content = new NodeStub('main', { id: 'content' }); document.append(body); body.append(content); document.body = body; document.activeElement = body; document.defaultView = { crypto: webcrypto };
  document.createElement = name => { const el = new NodeStub(name); el._doc = document; return el; };
  const h = { document, content, body, calls: [], imports: [], mounts: [], bindings: [], toasts: [], renders: 0, invalidations: 0,
    facts: structuredClone(facts), projects: projectRows || [project(5), project(6, false)], dispatches: dispatchRows || [dispatch(7), dispatch(9, false)] };
  h.fees = h.projects.map(p => ({ id: p.id + 10, project_id: p.id, project_title: p.title, project_status: p.status, teacher_name: '合成讲师', hours: 9, rate: 100, amount: 900, status: '待发放', delivery_controlled: p.delivery_controlled }));
  const response = (data, status = 200) => ({ status, text: async () => JSON.stringify(status === 200 ? { code: 0, data } : { code: status, msg: `合成拒绝 ${status}` }) });
  const normal = async (url, opts) => {
    if (opts.signal?.aborted) throw Object.assign(new Error('aborted'), { name: 'AbortError' });
    const query = new URL(url, 'http://synthetic.test');
    const filter = rows => rows.filter(row => !query.searchParams.get('project_id') || String(row.project_id) === query.searchParams.get('project_id'));
    if (query.pathname === '/api/projects') return response(h.projects);
    if (query.pathname === '/api/dispatches') return response(filter(h.dispatches));
    if (query.pathname === '/api/fees') return response(filter(h.fees));
    if (query.pathname === '/api/teachers') return response([{ id: 8, name: '合成讲师', status: '在库' }]);
    if (['/api/questionnaires', '/api/charges', '/api/costs', '/api/demands', '/api/bids', '/api/teacher_evals'].includes(query.pathname)) return response([]);
    if (url === '/api/teacher-resumes/manage') return response({ items: [] });
    if (url === '/api/organization/me') return response({ status: identity });
    if (url === '/api/course-catalog/scopes') return response({ scopes });
    if (url === '/api/course-catalog/scopes/1') return response({ scope_id: 1, version: 0, status: 'NOT_CONFIGURED' });
    if (query.pathname === '/api/delivery-settlement') return response(h.facts);
    if (url === '/api/delivery-settlement/save') {
      const b = JSON.parse(opts.body); h.facts.version++; h.facts.capabilities.fact_version = h.facts.version;
      h.facts.fact.hours = { estimated: b.estimated_hours, planned: b.planned_hours, actual: b.actual_minutes === '60' ? '1.33' : null, payable: b.payable_hours };
      h.facts.conversion = { minutes: b.actual_minutes }; h.facts.fact.verification = null; h.facts.capabilities.can_verify = h.facts.capabilities.permissions.verify; h.facts.capabilities.can_complete = false;
      return response(h.facts);
    }
    if (url === '/api/delivery-settlement/verify') {
      const b = JSON.parse(opts.body); h.facts.version++; h.facts.capabilities.fact_version = h.facts.version;
      h.facts.fact.verification = { evidence_code: b.evidence_code, actor_code: 'SYNTH-ACTOR', checked_at: '2026-09-22T00:00:00Z' }; h.facts.capabilities.can_complete = true;
      return response(h.facts);
    }
    if (url === '/api/dispatches/complete') { h.facts.capabilities.can_complete = false; return response('授课已完成'); }
    if (['/api/fees/pay', '/api/fees/delete', '/api/fees/calc'].includes(url)) return response([]);
    throw Error(`Unexpected route ${url}`);
  };
  const noop = () => {};
  const sandbox = { document, window: { addEventListener: noop }, AbortController, DOMException, URLSearchParams, console, crypto: webcrypto,
    setTimeout: () => 1, clearTimeout: noop, requestAnimationFrame: noop, navigator: { onLine: true }, localStorage: { removeItem: noop },
    state: { page, filters: {}, contextProjectId, projectId: 5, teacherTab: 'library', user: { uid: 11, role: writer ? 'admin' : 'viewer' } }, routeEpoch: 1, routeCleanups: [], chartInstances: [],
    $: (selector, root = document) => root.querySelector(selector), $$: (selector, root = document) => root.querySelectorAll(selector),
    esc, icon: name => `<i data-lucide="${esc(name)}"></i>`, num: n => String(Number(n || 0)), money: n => Number(n || 0).toFixed(2), businessArt: () => '', countTag: String,
    canWrite: () => writer, refreshIcons: noop, animateCounters: noop, revealFocusedRow: noop, navigateTo: noop, writeRouteToUrl: noop, prefersReducedMotion: () => true,
    readonlyAction: () => '查看', workflowProjectSource: () => '直接承接', mergeContextLabels: list => list.join('；'), taskArtKind: () => 'faculty',
    teacherFormFields: () => [], teacherResidenceText: () => '待补充', teacherFeeRateText: () => '待确认', teacherEntryGuide: () => '', resumeStatusTag: () => '',
    teacherResumeItems: p => p.items || [], resumeStatusInfo: () => ({}), teacherResumeUploadConfig: {}, teacherProfileRequestSequence: 0,
    cancelTeacherRecommendation: noop, openTeacherCreate: noop, openTeacherResumeUpload: noop, showTeacherEvals: noop,
    debounce(fn) { fn.cancel = noop; return fn; },
    toast: (message, error) => h.toasts.push({ message, error }), renderPage: () => { h.renders++; sandbox.beginRouteEpoch(); },
    invalidateSession: () => { h.invalidations++; sandbox.beginRouteEpoch(); sandbox.state.user = null; sandbox.closeModal(); content.innerHTML = '<p>登录</p>'; },
    fetch: async (url, opts) => { h.calls.push({ url, opts }); return fetchOverride ? fetchOverride(url, opts, normal, response) : normal(url, opts); },
    loadHostModule: async url => {
      h.imports.push(url); if (moduleWait) await moduleWait.promise; if (moduleFailure) throw Error('模块暂时无法加载');
      if (url.includes('delivery-settlement')) return { mount(root, context) { h.mounts.push(context); return modules.delivery.mount(root, context); } };
      return { openCourseCatalog(context) { h.mounts.push(context); return modules.catalog.openCourseCatalog(context); } };
    },
  };
  vm.createContext(sandbox);
  const pages = between('  async function pageDispatches(c) {', '  // ============ 效果评估（问卷）') + between('  async function pageFees(c) {', '  // ============ 师资资源：')
    + between('  function renderTeacherLibrary(root, context) {', '  function renderResumeManagement(') + between('  async function pageTeachers(c) {', '  async function showTeacherEvals(')
    + between('  async function pageProjectDetail(c) {', '  // ============ 统计看板');
  vm.runInContext(between('  function clearRouteAsync()', '  function positiveRouteId(') + between('  async function api(', '  function encodeBase64UrlUtf8(')
    + between('  let modalPreviousFocus', '  // ============ 登录 ============') + between('  function deliveryHoursValue(', '  const PROJECT_MODULE_NAMES =')
    + between('  function collectionProgress(', '  const shiftIsoDate =') + pages.replaceAll("await import('/modules/", "await loadHostModule('/modules/"), sandbox);
  const bind = sandbox.bindTableActions; sandbox.bindTableActions = (node, rows, actions) => { h.bindings.push({ node, rows, actions }); bind(node, rows, actions); };
  h.sandbox = sandbox;
  h.mount = () => sandbox[({ dispatches: 'pageDispatches', fees: 'pageFees', teachers: 'pageTeachers', project_detail: 'pageProjectDetail' })[page]](content);
  h.modal = () => document.querySelector('#modal-mask');
  h.buttons = id => content.querySelectorAll('.btn[data-act]').filter(b => b.dataset.id === String(id)).map(b => b.textContent);
  h.act = async (id, label) => { const binding = [...h.bindings].reverse().find(item => item.rows.some(row => row.id === id)); const row = binding.rows.find(row => row.id === id); const action = binding.actions.find(a => a.l === label); check(action && (!action.show || action.show(row)), `${label} is available for ${id}`); await action.onClick(row); };
  h.field = key => h.modal()?.querySelector(`[data-k="${key}"]`);
  h.button = key => h.modal()?.querySelector(`[data-m05-action="${key}"]`);
  h.emit = async (node, type) => { const event = { target: node, type, preventDefault() {} }; for (let at = node; at; at = at.parentNode) { if (at === node) await at[`on${type}`]?.(event); for (const fn of at.listeners.get(type) || []) await fn(event); } await tick(); };
  h.edit = async (key, value) => { h.field(key).value = value; await h.emit(h.field(key), 'input'); };
  h.click = async key => { const b = h.button(key); check(b && !b.disabled && !b.hidden, `${key} enabled by server capability`); await h.emit(b, 'click'); };
  h.posts = () => h.calls.filter(c => c.opts.method === 'POST');
  h.leave = () => { sandbox.beginRouteEpoch(); sandbox.state.page = 'dashboard'; content.innerHTML = '<p>后续页面</p>'; };
  return h;
}
(async () => {
  const read = file => fs.readFileSync(path.join(__dirname, '../web/modules', file), 'utf8');
  modules = { catalog: await import(dataModule(read('course-catalog/index.js'))), delivery: await import(dataModule(read('delivery-settlement/index.js').replace("'../settlement/conversion.js'", JSON.stringify(dataModule(read('settlement/conversion.js')))))) };
  const h = harness(); await h.mount();
  equal(h.imports, [], 'Original dispatch page loads without importing optional component');
  check(h.buttons(7).includes('授课记录') && !h.buttons(7).includes('完成'), 'Controlled record offers capability-based detail, no legacy complete');
  check(!h.buttons(9).includes('授课记录'), 'Legacy row does not enter new record flow');
  await h.act(7, '授课记录'); const mask = h.modal(), row = h.content.querySelector('[data-row-id="7"]');
  check(h.imports[0].endsWith('?v=20260923history2'), 'Delivery import has the history resource version');
  check(h.mounts[0].api === h.sandbox.api && h.mounts[0].openModal === h.sandbox.openModal, 'Real module uses original authenticated API and modal');
  check(h.field('actual_minutes') && h.field('actual_hours').readOnly, 'Real host form displays minutes and readonly converted hours');
  check(!h.field('actual_minutes').disabled, 'Viewer with server save capability can enter facts');
  await h.edit('evidence_code', 'EVIDENCE-KEEP'); await h.edit('actual_minutes', '60');
  equal(h.field('actual_hours').value, '1.33', 'Actual module previews 60 minutes as 1.33');
  check(h.button('verify').disabled && h.button('complete').disabled, 'Unsaved changes block verify and completion');
  await h.click('save');
  equal(JSON.parse(h.posts()[0].opts.body), { dispatch_id: 7, expected_version: 1, request_id: JSON.parse(h.posts()[0].opts.body).request_id, estimated_hours: '2.00', planned_hours: '2.00', actual_minutes: '60', payable_hours: null }, 'Original API serializes exact decimal strings/null and no implied payable');
  check(h.modal() === mask && h.renders === 0, 'Save leaves same modal and never rebuilds page');
  check(h.content.querySelector('[data-row-id="7"]') === row && row.textContent.includes('实际 1.33'), 'Save updates only original course row');
  equal(h.field('evidence_code').value, 'EVIDENCE-KEEP', 'Host row update preserves verification evidence');
  await h.click('verify');
  check(h.modal() === mask && row.textContent.includes('已核对'), 'Verification preserves modal and refreshes original row');
  equal(h.field('evidence_code').value, 'EVIDENCE-KEEP', 'Verified evidence stays in the form');
  await h.click('complete');
  equal(JSON.parse(h.posts().at(-1).opts.body), { id: 7, expected_version: 3 }, 'Completion carries current fact version to backend gate');
  check(h.modal() === mask && h.renders === 0 && row.textContent.includes('已完成'), 'Completion updates original row and keeps component active');
  equal(h.content.querySelector('#dispatch-completed').textContent, '1', 'Course summary count follows local completed row');
  check(h.calls.filter(c => c.url.includes('delivery-settlement') || c.url.includes('/complete')).every(c => c.opts.signal && c.opts.credentials === 'same-origin'), 'Every new call uses host session and abort signal');
  h.leave(); check(!h.modal() && h.mounts[0].signal.aborted, 'Route cleanup destroys component and original modal');

  const denied = harness({ writer: true, facts: detail(false, false) }); await denied.mount(); await denied.act(7, '授课记录');
  check(denied.field('actual_minutes').disabled && denied.button('save').disabled, 'Old administrator role cannot replace new delivery capabilities');
  check(denied.button('verify').hidden && denied.button('complete').hidden && denied.posts().length === 0, 'No verify/complete capability produces no actionable mutations'); denied.leave();
  const legacy = harness({ writer: true }); await legacy.mount(); check(legacy.buttons(9).includes('完成'), 'Old project keeps original complete action');
  await legacy.act(9, '完成'); await legacy.modal().querySelector('#modal-ok').onclick();
  equal(JSON.parse(legacy.posts()[0].opts.body), { id: 9 }, 'Legacy complete keeps original body'); check(legacy.renders === 1, 'Legacy complete keeps original page refresh');

  for (const status of [401, 403, 409]) {
    const f = harness({ fetchOverride: (url, opts, normal, respond) => url === '/api/delivery-settlement/save' ? respond(null, status) : normal(url, opts) });
    await f.mount(); await f.act(7, '授课记录'); await f.edit('actual_minutes', '60'); await f.edit('evidence_code', 'KEEP-ERROR'); await f.click('save');
    check(f.posts().length === 1 && f.renders === 0, `${status}: no implicit mutation replay or page refresh`);
    if (status === 401) check(f.invalidations === 1 && !f.modal(), '401 uses original session invalidation');
    else { equal(f.field('actual_minutes').value, '60', `${status}: original modal retains minutes`); equal(f.field('evidence_code').value, 'KEEP-ERROR', `${status}: original modal retains evidence`); }
    f.leave();
  }
  const wait = deferred(), late = harness({ moduleWait: wait }); await late.mount(); const opening = late.act(7, '授课记录'); late.leave(); wait.resolve(); await opening;
  check(late.mounts.length === 0 && !late.calls.some(c => c.url.includes('delivery-settlement')), 'Late module import does not mount/fetch after leaving');
  const pending = deferred(), saving = harness({ fetchOverride: (url, opts, normal, respond) => url === '/api/delivery-settlement/save' ? pending.promise.then(respond) : normal(url, opts) });
  await saving.mount(); await saving.act(7, '授课记录'); await saving.edit('actual_minutes', '60'); const submit = saving.click('save'); await tick(); saving.leave(); pending.resolve(detail()); await submit;
  check(saving.posts()[0].opts.signal.aborted && saving.content.textContent === '后续页面' && !saving.modal(), 'Late save cannot update new page or reopen old modal');

  for (const [identity, expected] of [['NOT_BOUND', '人员绑定'], ['NOT_CONFIGURED', '尚未配置'], ['BOUND', '没有可查看']]) {
    const catalog = harness({ page: 'teachers', identity }); await catalog.mount();
    const button = catalog.content.querySelector('#teacher-course-catalog'); check(button && button.textContent === '课程与认证', 'Catalog is a secondary original library button for viewers');
    equal(catalog.imports, [], 'Catalog module stays lazy before opening'); await button.onclick();
    check(catalog.modal().textContent.includes(expected), `${identity}: actual catalog empty state appears in original modal`);
    check(catalog.posts().length === 0 && !catalog.modal().textContent.includes('导入确认'), 'Catalog entry exposes no formal import or writes');
    check(catalog.imports[0].endsWith('?v=20260923certmaintenance2'), 'Catalog import has a new resource version');
    catalog.leave(); check(!catalog.modal() && catalog.mounts[0].signal.aborted, 'Catalog route cleanup closes its owned original modal');
  }
  const scoped = harness({ page: 'teachers', scopes: [{ scope_id: 1, organization_code: 'ORG-SYNTH' }] }); await scoped.mount(); await scoped.content.querySelector('#teacher-course-catalog').onclick();
  check(!scoped.calls.some(c => c.url === '/api/course-catalog/scopes/1'), 'Single scope still requires explicit user selection');
  scoped.field('scope_id').value = '1'; await scoped.emit(scoped.field('scope_id'), 'change');
  check(scoped.modal().textContent.includes('尚未配置'), 'Selected unconfigured scope retains a clear empty state');
  const successor = scoped.sandbox.openModal('后续弹窗', '<p>保留</p>', { noFoot: true }); scoped.leave(); check(scoped.modal() === successor, 'Catalog cleanup never closes a successor modal'); scoped.sandbox.closeModal();
  const failed = harness({ page: 'teachers', moduleFailure: true }); await failed.mount(); await failed.content.querySelector('#teacher-course-catalog').onclick();
  check(failed.toasts.some(x => x.error) && failed.content.querySelector('table'), 'Failed catalog import preserves original teacher table');

  const fees = harness({ page: 'fees', writer: true, contextProjectId: 5 }); await fees.mount();
  check(!fees.content.querySelector('#calc-btn') && !fees.buttons(15).includes('发放') && !fees.buttons(15).includes('删除'), 'Controlled fee view has no old calc/pay/delete');
  check(fees.content.querySelector('#fees-total').textContent === '请核对项目结算摘要' && fees.content.querySelector('#fees-paid').textContent === '请核对当前账目', 'Formal amounts and actual payments are never inferred from old fee rows or shown as zero');
  check(fees.content.querySelector('#formal-cases-host') && !fees.content.querySelector('#formal-cases-host').hidden && fees.content.textContent.includes('正式课酬事项'), 'Controlled project exposes formal Cases alongside historical records');
  check(fees.content.textContent.includes('原有记录，仅供查阅'), 'Existing controlled fee rows are clearly historical');
  const mixed = harness({ page: 'fees', writer: true }); await mixed.mount();
  check(!mixed.buttons(15).includes('发放') && mixed.buttons(16).includes('发放'), 'Mixed list gates fees by each real project');
  mixed.content.querySelector('#calc-btn').onclick(); equal(mixed.field('project_id').options.map(o => o.value).filter(Boolean), ['6'], 'Old calculator lists only legacy projects'); mixed.sandbox.closeModal();
  mixed.content.querySelector('#flt-proj').value = '5'; await mixed.content.querySelector('#flt-proj').onchange();
  check(mixed.content.querySelector('#calc-btn').hidden && mixed.content.querySelector('#calc-btn').style.display === 'none' && mixed.content.querySelector('#fees-pending').textContent === '请核对当前账目', 'Filtering to controlled project really hides old calculator and replaces old totals with formal balances');
  mixed.content.querySelector('#flt-proj').value = '6'; await mixed.content.querySelector('#flt-proj').onchange();
  check(!mixed.content.querySelector('#calc-btn').hidden && mixed.content.querySelector('#calc-btn').style.display === '' && mixed.content.querySelector('#fees-pending').textContent === '¥ 900.00', 'Filtering back retains visible legacy calculation and original amounts');

  const finished = harness({ writer: true, dispatchRows: [{ ...dispatch(7), project_status: '已完成', status: '已完成' }, { ...dispatch(9, false), project_status: '已完成', status: '已完成' }] }); await finished.mount();
  check(finished.buttons(7).includes('编辑') && !finished.buttons(9).includes('编辑'), 'Only adopted completed project retains material maintenance');
  await finished.act(7, '编辑'); equal(finished.modal().querySelectorAll('[data-k]').map(x => x.dataset.k), ['material_status', 'remark'], 'Completed course editor exposes only preparation and remarks'); finished.sandbox.closeModal();
  const creating = harness({ writer: true }); await creating.mount();
  check(!creating.calls.some(call => call.url === '/api/teachers'), 'Original scheduling page defers the teacher directory until an actual editor needs it');
  await creating.content.querySelector('#add-btn').onclick();
  check(creating.calls.filter(call => call.url === '/api/teachers').length === 1 && creating.field('teacher_id').options.some(option => option.value === '8'), 'Explicit scheduling editor loads current teacher choices once');
  equal(creating.field('hours').getAttribute('step'), '0.01', 'Original schedule plan field permits two decimal places');
  equal(creating.field('hours').getAttribute('min'), '0.01', 'Original schedule accepts positive values below half a class hour');
  check(creating.modal().textContent.includes('60分钟填写1.33') && !creating.field('actual_minutes'), 'Plan field explains 45-minute units without impersonating actual minutes');
  for (const [k, v] of Object.entries({ project_id: '5', teacher_id: '8', subject: '合成排期', teach_date: '2026-09-20', hours: '1.33' })) creating.field(k).value = v;
  await creating.modal().querySelector('#modal-ok').onclick();
  equal(JSON.parse(creating.posts()[0].opts.body).hours, 1.33, 'Original schedule collection preserves 1.33 plan hours');

  const view = harness({ page: 'project_detail', dispatchRows: [{ ...dispatch(7), status: '已完成' }] }); await view.mount();
  check(view.content.querySelector('.workspace-metrics').textContent.includes('已核对实际课时1.33'), 'Project workspace uses new 1.33 actual summary instead of 9 old hours');
  check(view.content.textContent.includes('预计 2.00 / 计划 2.00 / 实际 1.33 / 计酬 待核对'), 'Four hours remain separately labeled without implied payable');
  const unknown = harness({ page: 'project_detail', projectRows: [{ ...project(5), delivery_hours: null, delivery_hours_reason: '没有当前机构授课查看权限' }] }); await unknown.mount();
  check(unknown.content.querySelector('.workspace-metrics').textContent.includes('已核对实际课时待核对'), 'Unavailable controlled hours do not collapse to numeric zero');
  check(unknown.content.textContent.includes('没有当前机构授课查看权限'), 'Backend read reason is visible');
  equal(unknown.sandbox.projectCompletedHours({ delivery_controlled: false }, [{ status: '已完成', hours: 9 }]), 9, 'Legacy completion aggregation stays unchanged');
  equal(unknown.sandbox.projectCompletedHours({ delivery_controlled: true, delivery_hours: { actual: '0.00' } }, []), '0.00', 'Explicit actual zero remains distinct from unknown');
  for (const writer of [false, true]) for (const status of ['进行中', '已完成', '已归档']) {
    const external = harness({ page: 'project_detail', writer, projectRows: [{ ...project(5), workflow_source: { business_path: 'direct' }, status }] }); await external.mount();
    const evaluation = external.content.querySelector('.evaluation-panel'), journey = external.content.querySelector('.project-journey');
    check(evaluation.textContent.includes('评分统计与复核') && evaluation.querySelector('[data-survey-formal-open]') && !evaluation.querySelector('[data-project-goto="questionnaires"]'), `${writer}/${status}: adopted project offers the formal summary entry without legacy questionnaire creation`);
    check(!external.content.querySelector('[data-project-goto="questionnaires"]') && !external.content.querySelector('.workspace-tasks').textContent.includes('评估'), `${writer}/${status}: new-project workspace has no questionnaire route or invalid priority task`);
    check(!external.content.textContent.includes('60%') && !external.content.textContent.includes('再次触达') && !external.calls.some(c => c.url.startsWith('/api/questionnaires')), `${writer}/${status}: new-project workspace never reads or applies old questionnaire recovery rules`);
    check(journey.textContent.includes('项目回款') && !journey.textContent.includes('回款结算') && journey.querySelector('[data-survey-stage]').getAttribute('aria-label') === '查看正式汇总与复核' && !journey.querySelector('[data-survey-stage]').disabled, `${writer}/${status}: payment label stays precise and evaluation stage opens formal summaries under their own capability gate`);
  }
  const legacyEmpty = harness({ page: 'project_detail', writer: true, projectRows: [project(5, false)] }); await legacyEmpty.mount();
  check(legacyEmpty.content.querySelector('.evaluation-panel').textContent.includes('创建评估') && legacyEmpty.content.querySelector('.workspace-tasks').textContent.includes('创建效果评估'), 'Legacy project retains its evaluation creation and priority task');
  check(legacyEmpty.content.querySelector('.project-journey').textContent.includes('回款结算'), 'Legacy collection stage keeps its existing label');
  const legacySurvey = harness({ page: 'project_detail', writer: true, projectRows: [project(5, false)], fetchOverride: (url, opts, normal, respond) => url.startsWith('/api/questionnaires') ? respond([{ send_total: 10, recv_total: 2 }]) : normal(url, opts) }); await legacySurvey.mount();
  check(legacySurvey.content.querySelector('.evaluation-panel').textContent.includes('回收率不足 60%，建议再次触达学员'), 'Legacy project retains its existing response-rate guidance');
  check(legacySurvey.content.querySelector('.evaluation-panel').textContent.includes('管理评估') && legacySurvey.content.querySelector('[data-project-goto="questionnaires"]'), 'Legacy questionnaires remain reachable from the original workspace');
  console.log(JSON.stringify({ ok: true, suite: 'original delivery and catalog host integration', checks, transport: 'synthetic', browser: false, database: false }));
})().catch(error => { console.error(error); process.exitCode = 1; });
