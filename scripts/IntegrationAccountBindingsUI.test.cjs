'use strict';
// Real users-page, form, modal, table and API functions + real M01 adapter.
// Only the DOM and HTTP transport are synthetic. No browser/server/database.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '../web/app.js'), 'utf8');
const component = fs.readFileSync(path.join(__dirname, '../web/modules/identity/account-bindings.js'), 'utf8');
const componentChecks = fs.readFileSync(path.join(__dirname, 'M01-account-bindings-check.mjs'), 'utf8');
const between = (start, end) => { const from = source.indexOf(start), to = source.indexOf(end, from); assert.ok(from >= 0 && to > from); return source.slice(from, to); };
// Reuse the component's small DOM/fixture without executing its independent test suite.
const support = vm.runInNewContext(componentChecks.slice(componentChecks.indexOf('const decode ='), componentChecks.indexOf('function harness(')) + '\n({ NodeStub, fixture });', { copy: structuredClone });
const { NodeStub, fixture } = support;
Object.defineProperty(NodeStub.prototype, 'id', { get() { return this.attributes.id; }, set(value) { this.attributes.id = value; } });
Object.defineProperty(NodeStub.prototype, 'className', { get() { return this.attributes.class; }, set(value) { this.attributes.class = value; } });
NodeStub.prototype.appendChild = function (node) { node.parentNode = this; this.children.push(node); return node; };
NodeStub.prototype.matches = function (selector) {
  if (selector.includes(',')) return selector.split(',').some(part => this.matches(part.trim()));
  const exclusions = [...selector.matchAll(/:not\(([^)]+)\)/g)].map(match => match[1]);
  if (exclusions.some(part => this.matches(part))) return false;
  const clean = selector.replace(/:not\([^)]+\)/g, '');
  const tag = clean.match(/^[\w-]+/)?.[0];
  if (tag && this.tagName !== tag.toUpperCase()) return false;
  const id = clean.match(/#([\w-]+)/)?.[1]; if (id && this.id !== id) return false;
  for (const match of clean.matchAll(/\.([\w-]+)/g)) if (!this.classList.contains(match[1])) return false;
  for (const match of clean.matchAll(/\[([\w-]+)(?:=["']?([^\]"']+)["']?)?\]/g)) {
    if (match[1] === 'disabled' ? !this.disabled : !this.hasAttribute(match[1])) return false;
    if (match[2] !== undefined && this.getAttribute(match[1]) !== match[2]) return false;
  }
  return true;
};
let checks = 0;
const check = (value, label) => { assert.ok(value, label); checks++; };
const equal = (actual, expected, label) => { assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected, label); checks++; };
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
function harness({ configuration = fixture(), getConfigStatus, postStatus, moduleWait, moduleFailure, fetchOverride } = {}) {
  const document = new NodeStub('document'), body = new NodeStub('body'), content = new NodeStub('main', { id: 'content' }); document.appendChild(body); body.appendChild(content); document.body = body; document.activeElement = body; document.createElement = name => new NodeStub(name);
  const users = [11, 12, 13, 14].map(id => ({ id, username: `synthetic-${id}`, name: `合成账号 ${id}`, role: id === 11 ? 'admin' : 'viewer', status: id === 13 ? 0 : 1, created_at: '2026-09-22 09:05:12' }));
  const h = { document, body, content, users, configuration: structuredClone(configuration), calls: [], imports: [], toasts: [], renders: 0, invalidations: 0, modules: [] };
  const response = (data, status = 200) => ({ status, text: async () => JSON.stringify(status === 200 ? { code: 0, data } : { code: status, msg: `合成拒绝 ${status}`, data: { reason: 'synthetic' } }) });
  const normalFetch = async (url, opts) => {
    if (opts.signal?.aborted) throw Object.assign(new Error('aborted'), { name: 'AbortError' });
    if (url === '/api/organization/me') return response({ status: 'BOUND', version: h.configuration?.version, person: fixture().people[0] });
    if (url === '/api/organization/config' && opts.method === 'POST') {
      if (postStatus) return response(null, postStatus);
      h.configuration = JSON.parse(opts.body).configuration;
      return response({ version: h.configuration.version, configuration: h.configuration });
    }
    if (url === '/api/organization/config') return getConfigStatus ? response(null, getConfigStatus) : response({ version: h.configuration?.version ?? null, configuration: h.configuration });
    if (url === '/api/users' && opts.method === 'GET') return response(users);
    if (url === '/api/users' || url === '/api/users/resetpwd' || url === '/api/users/delete') return response({});
    throw Error(`Unexpected route: ${url}`);
  };
  const sandbox = { document, window: { addEventListener() {} }, console, AbortController, setTimeout: () => 1, clearTimeout() {}, requestAnimationFrame() {},
    crypto: { randomUUID: (() => { let id = 0; return () => `synthetic-new-${++id}`; })() },
    state: { page: 'users', user: { uid: 11, name: '合成管理员', role: 'admin' } }, routeEpoch: 1, routeCleanups: [], chartInstances: [],
    navigator: { onLine: true }, localStorage: { removeItem() {} },
    $: (selector, root = document) => root.querySelector(selector), $$: (selector, root = document) => root.querySelectorAll(selector),
    esc: escapeHtml, icon: name => `<i data-lucide="${escapeHtml(name)}"></i>`, tag: escapeHtml, countTag: String, refreshIcons() {}, animateCounters() {}, canWrite: () => true,
    toast: (message, error) => h.toasts.push({ message, error }),
    renderPage: () => { h.renders++; sandbox.beginRouteEpoch(); },
    invalidateSession: () => { h.invalidations++; sandbox.beginRouteEpoch(); sandbox.state.user = null; sandbox.closeModal(); content.innerHTML = '<p>登录</p>'; },
    fetch: async (url, opts) => { h.calls.push({ url, opts }); return fetchOverride ? fetchOverride(url, opts, normalFetch, response) : normalFetch(url, opts); },
    loadIdentityModule: async url => { h.imports.push(url); if (moduleWait) await moduleWait.promise; if (moduleFailure) throw Error('synthetic import failed'); return { mountAccountBindings(root, host) { const instance = sandbox.mountAccountBindings(root, host); h.modules.push({ root, host, instance }); return instance; } }; },
  };
  vm.createContext(sandbox);
  const pages = between('  async function pageUsers(c) {', '  // ============ 启动 ============');
  check(/await import\('\/modules\/identity\/account-bindings\.js\?v=[^']+'\)/.test(pages), 'Host uses a versioned native module import');
  vm.runInContext(between('  function clearRouteAsync()', '  function positiveRouteId(') + between('  async function api(', '  function encodeBase64UrlUtf8(') + between('  let modalPreviousFocus', '  // ============ 登录 ============') + between('  function organizationBindingHtml(', '  async function showOrganizationBinding(') + component.replace('export function mountAccountBindings', 'function mountAccountBindings') + pages.replace("await import('/modules/identity/", "await loadIdentityModule('/modules/identity/"), sandbox);
  h.sandbox = sandbox; h.mount = () => sandbox.pageUsers(content); h.modal = () => document.querySelector('#modal-mask');
  h.set = (key, value) => { const field = h.modal().querySelector(`[data-k="${key}"]`); check(Boolean(field), `Original form has ${key}`); field.value = value; field.onchange?.(); };
  h.actions = id => content.querySelectorAll('.btn[data-act]').filter(button => button.dataset.id === String(id));
  h.clickAction = (id, label) => { const button = h.actions(id).find(item => item.textContent === label); check(Boolean(button), `Original row action ${label} exists`); return button.onclick(); };
  h.posts = () => h.calls.filter(call => call.opts.method === 'POST');
  h.save = () => h.modal().querySelector('#modal-ok').onclick();
  return h;
}
(async () => {
  const scopedConfig = fixture();
  const scopedPerson = scopedConfig.people[3];
  scopedPerson.roleCodes = ['ROLE-STAFF', 'ROLE-LEADER'];
  scopedPerson.responsibleOrganizationCodes = ['ORG-001'];
  scopedPerson.responsibleOrganizationsByRole = { 'ROLE-STAFF': ['ORG-001'], 'ROLE-LEADER': ['ORG-001'] };
  scopedConfig.combinedApprovals = [{ organizationCode: 'ORG-001', personCode: scopedPerson.personCode, leaderRoleCode: 'ROLE-LEADER', bpRoleCode: 'ROLE-STAFF', evidenceRef: 'SYNTHETIC-DUTY-EVIDENCE' }];
  const scoped = harness({ configuration: scopedConfig }); await scoped.mount(); scoped.clickAction(14, '绑定人员'); scoped.set('personCode', scopedPerson.personCode); scoped.set('enabled', 'true');
  const scopeField = scoped.modal().querySelector('[data-k="bindingRoleScopes"]');
  check(scopeField.value.includes('ROLE-STAFF：ORG-001') && scopeField.value.includes('ROLE-LEADER：ORG-001'), 'Original binding modal shows roles separately');
  equal(scoped.modal().querySelector('[data-k="bindingCombined"]').value, 'ORG-001', 'Combined duty shows only explicit configured organization');
  await scoped.save();
  const scopedPosted = JSON.parse(scoped.posts()[0].opts.body).configuration;
  equal(scopedPosted.people[3].responsibleOrganizationsByRole, scopedPerson.responsibleOrganizationsByRole, 'Binding edit preserves explicit role ranges');
  equal(scopedPosted.combinedApprovals, scopedConfig.combinedApprovals, 'Binding edit preserves exact duty evidence');
  scoped.sandbox.beginRouteEpoch();
  for (const key of ['responsibleOrganizationsByRole', 'combinedApprovals']) {
    const bad = structuredClone(scopedConfig);
    if (key === 'combinedApprovals') bad[key] = null; else bad.people[3][key] = null;
    const invalid = harness({ configuration: bad }); await invalid.mount();
    check(!invalid.actions(14).some(button => button.textContent === '绑定人员'), 'Invalid optional extension does not fall back: ' + key);
    invalid.sandbox.beginRouteEpoch();
  }
  const h = harness(); await h.mount();
  equal(h.calls.filter(call => call.opts.method === 'GET').map(call => call.url).sort(), ['/api/organization/config', '/api/organization/me', '/api/users'], 'Only one initial users request, with real API prefix');
  check(h.calls.every(call => call.opts.credentials === 'same-origin' && call.opts.headers['Content-Type'] === 'application/json' && call.opts.signal), 'All identity requests use original authenticated API and abort signals');
  check(h.content.querySelectorAll('table').length === 1, 'The original users table is extended once');
  equal(h.content.querySelectorAll('th').map(column => column.textContent), ['账号', '角色/状态', '创建时间', '业务身份', '负责人/BP', '绑定状态', '操作'], 'Original account table has six information columns plus original actions');
  const accountRow = h.content.querySelector('[data-row-id="11"]');
  const cells = accountRow.querySelectorAll('td');
  check(cells[0].textContent.includes('合成账号 11') && cells[0].textContent.includes('synthetic-11') && cells[0].textContent.includes('#11'), 'Compact account cell retains name, username and ID');
  check(cells[1].querySelectorAll('.tag').length === 2 && cells[1].textContent.includes('系统管理员') && cells[1].textContent.includes('启用'), 'Role and account state retain both original badges');
  check(cells[2].querySelector('b').textContent === '2026-09-22' && cells[2].querySelector('small').textContent === '09:05:12', 'Created date and time remain complete on separate lines');
  check(cells[3].querySelector('b').textContent === 'PERSON-00001' && cells[3].querySelector('small').textContent === '机构：ORG-001', 'Person and organization codes remain distinct in a grouped cell');
  check(cells[4].textContent.includes('负责人：未配置') && cells[4].textContent.includes('BP：未配置'), 'Relationship values keep explicit labels');
  check(cells[5].querySelectorAll('small').length === 0 && cells[5].textContent === '启用', 'Normal binding state does not repeat a second enabled status');
  check(h.content.querySelector('[data-row-id="13"]').querySelectorAll('td')[5].textContent.includes('人员停用'), 'Disabled person exception remains visible beside binding state');
  const ownCard = h.content.querySelector('#user-own-binding');
  check(ownCard.querySelectorAll('.demand-brief-grid').length === 1 && ownCard.querySelectorAll('.detail-text').length === 0, 'Own identity uses the original compact brief grid');
  check(ownCard.textContent.includes('业务身份：已绑定') && ownCard.textContent.includes('PERSON-00001') && ownCard.textContent.includes('ORG-001') && ownCard.textContent.includes('机构授权'), 'Own identity keeps binding, person, organization and permission explanation');
  const groupedHost = h.modules[0].host;
  const hostileRow = { id: 15, name: '<img src=x>', username: '<script>', created_at: '<img src=y>', bindingPerson: '<svg>', bindingOrg: '<img>', bindingLeader: '<script>', bindingBp: '<img>', bindingStatus: '<svg>' };
  const rendered = [...groupedHost.columns, ...groupedHost.bindingColumns].map(column => column.render(hostileRow)).join('');
  check(!/<(?:img|script|svg)[ >]/.test(rendered) && rendered.includes('&lt;img'), 'Grouped account and binding fields still use the original HTML escaping');
  h.sandbox.state.user.name = '<svg onload=x>';
  const ownHtml = h.sandbox.organizationBindingHtml({ status: 'BOUND', person: { personCode: '<img>', organizationCode: '<script>' } });
  check(!/<(?:img|script|svg)[ >]/.test(ownHtml) && ownHtml.includes('&lt;svg'), 'Own identity grid escapes account and code fields');
  check(h.sandbox.organizationBindingHtml({ status: 'NOT_BOUND' }).includes('尚未绑定') && !h.sandbox.organizationBindingHtml({ status: 'NOT_BOUND' }).includes('已绑定'), 'Unbound identity never becomes a bound card');
  check(h.sandbox.organizationBindingHtml({ status: 'NOT_CONFIGURED' }).includes('尚未配置'), 'Absent organization configuration stays distinct from an unbound account');
  check(h.sandbox.organizationBindingHtml({ status: 'UNRECOGNIZED', person: { personCode: 'PERSON-00001' } }).includes('未核实') && h.sandbox.organizationBindingHtml({}).includes('未核实'), 'Unknown or empty identity responses do not claim a binding state');
  h.sandbox.state.user.name = '合成管理员';
  check(!h.content.querySelector('#user-summary').hidden && h.content.querySelector('#user-summary').textContent.includes('系统用户4'), 'Summary uses component-loaded accounts');
  equal(h.actions(14).map(button => button.textContent).sort(), ['删除', '编辑', '绑定人员', '重置密码', '查看业务权限', '维护负责人/BP', '维护核验邮箱'].sort(), 'Unbound account preserves original actions with permission, relationship and verified-email entries');
  check(!h.actions(12).some(button => button.textContent === '删除'), 'Disabled binding still protects account deletion');
  const original = structuredClone(h.configuration);
  h.clickAction(14, '绑定人员'); h.set('personCode', 'PERSON-00004'); h.set('enabled', 'false');
  check(h.modal().querySelector('[data-k="bindingOrg"]').value === 'ORG-001', 'Original form updates readonly organization');
  await h.save();
  check(!h.modal(), 'Real modal closes after verified save');
  const save = h.posts()[0]; equal([save.url, save.opts.method], ['/api/organization/config', 'POST'], 'Save uses real config endpoint');
  const payload = JSON.parse(save.opts.body); equal(Object.keys(payload).sort(), ['configuration', 'expectedVersion'], 'Real API serializes the contract body exactly');
  equal(payload.expectedVersion, original.version, 'CAS expectedVersion comes from GET');
  equal(payload.configuration.accountBindings.at(-1), { accountId: 14, personCode: 'PERSON-00004', enabled: false }, 'Account number, exact person code and boolean survive host collection');
  const untouched = value => Object.fromEntries(Object.entries(value).filter(([key]) => !['version', 'accountBindings'].includes(key)));
  equal(untouched(payload.configuration), JSON.parse(JSON.stringify(untouched(original))), 'Host preserves all people, organization, relation and grant data');
  check(h.content.textContent.includes('PERSON-00004') && h.toasts.some(item => item.message === '绑定已保存'), 'Only verified response redraws saved binding and success');
  h.sandbox.beginRouteEpoch();

  for (const mode of ['missing', 'failed']) {
    const m = harness(mode === 'missing' ? { configuration: null } : { getConfigStatus: 503 }); await m.mount();
    check(m.content.querySelectorAll('table').length === 1 && m.content.textContent.includes('未核实'), `${mode}: original accounts stay visible with unknown binding state`);
    check(!m.actions(14).some(button => button.textContent === '绑定人员' || button.textContent === '删除'), `${mode}: unknown bindings cannot mutate/delete`);
    m.clickAction(14, '编辑'); m.set('name', '更新合成账号'); await m.save();
    check(m.posts().some(call => call.url === '/api/users') && !m.posts().some(call => call.url === '/api/organization/config'), `${mode}: account edits still use the existing endpoint`);
    check(m.renders === 1, `${mode}: account edit refreshes original route`);
  }
  const reset = harness({ configuration: null }); await reset.mount(); reset.clickAction(14, '重置密码'); reset.set('password', 'synthetic-only-password'); await reset.save();
  check(reset.posts().some(call => call.url === '/api/users/resetpwd'), 'Password reset remains available without organization configuration'); reset.sandbox.beginRouteEpoch();
  const add = harness({ configuration: null }); await add.mount(); add.content.querySelector('#add-btn').onclick(); add.set('username', 'new-synthetic'); add.set('name', '新增合成'); add.set('role', 'viewer'); add.set('password', 'synthetic-only-password'); await add.save();
  check(add.posts().some(call => call.url === '/api/users' && JSON.parse(call.opts.body).username === 'new-synthetic'), 'New account still uses original modal and endpoint');

  const conflict = harness({ postStatus: 409 }); await conflict.mount(); conflict.clickAction(14, '绑定人员'); conflict.set('personCode', 'PERSON-00004'); conflict.set('enabled', 'false'); await conflict.save();
  check(Boolean(conflict.modal()) && !conflict.modal().querySelector('#modal-ok').disabled, '409 leaves original modal and restores save button');
  equal(conflict.modal().querySelector('[data-k="personCode"]').value, 'PERSON-00004', '409 retains selected person');
  equal(conflict.modal().querySelector('[data-k="enabled"]').value, 'false', '409 retains selected disabled state');
  await conflict.save(); check(conflict.posts().length === 1, 'Old conflicting draft cannot replay');
  check(conflict.content.textContent.includes('未核实'), '409 removes the old binding claims'); conflict.sandbox.closeModal();
  await conflict.modules[0].instance.refresh(); conflict.clickAction(14, '绑定人员'); check(Boolean(conflict.modal()), 'Close and explicit refresh allow a newly reviewed draft'); conflict.sandbox.beginRouteEpoch();

  for (const status of [401, 403]) {
    const denied = harness({ postStatus: status }); await denied.mount(); denied.clickAction(14, '绑定人员'); denied.set('personCode', 'PERSON-00004'); denied.set('enabled', 'false'); await denied.save();
    check(!denied.content.textContent.includes('synthetic-14'), `${status}: loaded admin account data is cleared`);
    check(denied.posts().length === 1 && !denied.toasts.some(item => item.message === '绑定已保存'), `${status}: no false success`);
    check(status === 401 ? denied.invalidations === 1 && !denied.modal() : Boolean(denied.modal()), `${status}: original session invalidation or retained failure modal applies`);
    denied.sandbox.beginRouteEpoch();
  }

  const pending = deferred(); const leaving = harness({ fetchOverride: (url, opts, normal, respond) => url === '/api/organization/config' && opts.method === 'POST' ? pending.promise.then(data => respond(data)) : normal(url, opts) });
  await leaving.mount(); leaving.clickAction(14, '绑定人员'); leaving.set('personCode', 'PERSON-00004'); leaving.set('enabled', 'false'); const saving = leaving.save(); await tick();
  const candidate = JSON.parse(leaving.posts()[0].opts.body).configuration;
  leaving.sandbox.beginRouteEpoch(); leaving.sandbox.state.page = 'dashboard'; leaving.content.innerHTML = '<p>新页面</p>'; const callsBefore = leaving.calls.length;
  check(leaving.posts()[0].opts.signal.aborted && !leaving.modal(), 'Route change aborts pending binding save and closes its modal');
  pending.resolve({ version: candidate.version, configuration: candidate }); await saving; await tick();
  equal(leaving.content.textContent, '新页面', 'Late successful POST does not modify another route');
  check(leaving.calls.length === callsBefore && !leaving.toasts.some(item => item.message === '绑定已保存'), 'Route cleanup prevents late refresh and success toast');

  const closingPending = deferred(); const closing = harness({ fetchOverride: (url, opts, normal, respond) => url === '/api/organization/config' && opts.method === 'POST' ? closingPending.promise.then(data => respond(data)) : normal(url, opts) });
  await closing.mount(); closing.clickAction(14, '绑定人员'); closing.set('personCode', 'PERSON-00004'); closing.set('enabled', 'false'); const closingSave = closing.save(); await tick();
  closing.sandbox.closeModal(); const successor = closing.sandbox.openModal('后续弹窗', '<p>保留</p>', { noFoot: true });
  const closingCandidate = JSON.parse(closing.posts()[0].opts.body).configuration; closingPending.resolve({ version: closingCandidate.version, configuration: closingCandidate }); await closingSave; await tick();
  check(closing.modal() === successor, 'Late binding save never closes a later original modal');
  check(!closing.toasts.some(item => item.message === '绑定已保存'), 'Closed binding modal does not announce late success'); closing.sandbox.closeModal(); closing.sandbox.beginRouteEpoch();

  const moduleWait = deferred(); const importing = harness({ moduleWait }); const importReady = importing.mount(); importing.sandbox.beginRouteEpoch(); importing.sandbox.state.page = 'dashboard'; importing.content.innerHTML = '后续页面'; moduleWait.resolve(); await importReady;
  check(importing.modules.length === 0 && importing.calls.every(call => call.url === '/api/organization/me'), 'Late dynamic import cannot mount or fetch admin data after route departure');
  const fallback = harness({ moduleFailure: true }); await fallback.mount();
  check(fallback.content.textContent.includes('人员绑定功能暂时无法加载') && fallback.actions(14).some(button => button.textContent === '编辑'), 'Module load failure keeps original account maintenance and a clear notice');
  check(!fallback.actions(14).some(button => button.textContent === '删除') && !fallback.calls.some(call => call.url.includes('demo')), 'Module failure never assumes no bindings or loads demo data'); fallback.sandbox.beginRouteEpoch();
  console.log(JSON.stringify({ ok: true, suite: 'original account bindings host integration', checks, transport: 'synthetic', browser: false, database: false }));
})().catch(error => { console.error(error); process.exitCode = 1; });
