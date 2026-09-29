'use strict';
// Runs the original account menu, notifications, modal/table/API and workflow bridge.
// DOM and HTTP transport are synthetic; no real accounts, inboxes or external delivery.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const supportSource = fs.readFileSync(path.join(__dirname, 'IntegrationDeliveryCatalogUI.test.cjs'), 'utf8');
const globals = { require, __dirname, console, structuredClone, AbortController, DOMException, URL, URLSearchParams, Buffer, setImmediate };
const support = vm.runInNewContext(supportSource.slice(0, supportSource.lastIndexOf('\n(async () => {')) + '\n({ harness, NodeStub });', { ...globals });
// Model actual DOM reparenting and focus events for the shared portalled row menu.
// The earlier generic DOM is intentionally smaller and never opens these menus.
const { NodeStub } = support;
NodeStub.prototype.appendChild = function (node) {
  if (node.parentNode) node.parentNode.children = node.parentNode.children.filter(child => child !== node);
  node.parentNode = this; node.isConnected = true; this.children.push(node); return node;
};
Object.defineProperty(NodeStub.prototype, 'classList', { configurable: true, set() {}, get() {
  const read = () => new Set((this.attributes.class || '').split(/\s+/).filter(Boolean));
  const write = value => { this.attributes.class = [...value].join(' '); };
  return { contains: name => read().has(name), add: (...names) => { const value = read(); names.forEach(name => value.add(name)); write(value); }, remove: (...names) => { const value = read(); names.forEach(name => value.delete(name)); write(value); }, toggle: (name, force) => { const value = read(), on = force === undefined ? !value.has(name) : force; on ? value.add(name) : value.delete(name); write(value); return on; } };
} });
const match = NodeStub.prototype.matches, query = NodeStub.prototype.querySelector;
NodeStub.prototype.matches = function (selector) { return selector === ':disabled' ? this.disabled : match.call(this, selector); };
NodeStub.prototype.querySelector = function (selector) { return selector.startsWith(':scope > ') ? this.children.find(node => node.matches(selector.slice(9))) || null : query.call(this, selector); };
NodeStub.prototype.getBoundingClientRect = () => ({ top: 300, bottom: 332, left: 760, right: 792, width: 32, height: 32 });
Object.defineProperty(NodeStub.prototype, 'scrollHeight', { configurable: true, get: () => 100 });
NodeStub.prototype.focus = function () {
  const document = this.ownerDocument;
  if (!document || document.activeElement === this) return;
  document.activeElement = this;
  for (const handler of document.listeners.get('focusin') || []) handler({ type: 'focusin', target: this });
};
const app = fs.readFileSync(path.join(__dirname, '../web/app.js'), 'utf8');
const extract = (start, end) => { const a = app.indexOf(start), b = app.indexOf(end, a); assert.ok(a >= 0 && b > a, start); return app.slice(a, b); };
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
let checks = 0;
const check = (yes, label) => { assert.ok(yes, label); checks++; };
const equal = (actual, expected, label) => { assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected, label); checks++; };
const notice = n => ({ id: `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`, eventId: `event:${n}`, type: 'REVIEW_REQUIRED', title: `合成通知 ${n}`, body: '合成事项 <script>不得执行</script>', createdAt: '2026-09-22T00:00:00Z', readAt: null, actionable: true });
const target = (moduleId = 'M03', view = 'task', n = 1) => ({ moduleId, params: { recordId: '9', eventId: `event:${n}`, view, ...(moduleId === 'M03' ? { taskId: '9' } : {}) } });
const context = () => ({ person: { person_code: 'SYNTH-PERSON' }, people: [], organizations: [], teams: [] });
const workflow = () => ({ id: 9, version: 4, business_path: 'direct', form: { title: '准确的原需求', duration_minutes: '60' }, legacy: { unit: '合成单位', content: '完整的原需求资料' }, actions: {}, approval: { id: 9, businessId: 9, version: 3, stage: 'LEADER', status: 'LEADER_PENDING', participants: {}, actions: [{ action: 'APPROVE', label: '通过', enabled: true }, { action: 'RETURN', label: '退回', enabled: true }], history: [{ at: '2026-09-22T00:00:00Z', action: 'SUBMIT', actorId: 'SYNTH-PERSON', comment: '合成审批记录' }] } });
const taskView = () => { const { approval, ...base } = workflow(); return { task: { ...approval, workflow: base } }; };
function harness({ fetchOverride, count = 23, targetValue = target(), writer = false } = {}) {
  const records = Array.from({ length: count }, (_, i) => notice(i + 1));
  const h = support.harness({ page: 'dashboard', writer, fetchOverride: (url, opts, normal, respond) => {
    const fallback = () => {
      const query = new URL(url, 'http://synthetic');
      if (query.pathname === '/api/notifications') { const offset = Number(query.searchParams.get('offset')); return respond({ items: records.slice(offset, offset + 20), total: records.length, unreadCount: records.filter(row => row.readAt === null).length, offset, limit: 20 }); }
      if (url === '/api/notifications/channels') return respond({ channels: [{ channel: 'IN_APP', status: 'ready' }, { channel: 'EMAIL', status: 'adapter_not_connected' }, { channel: 'PUBLIC_ACCOUNT', status: 'unconfigured' }] });
      if (query.pathname === '/api/notifications/diagnostics') return respond({ items: [{ eventId: 'event:blocked', recordId: '9', createdAt: '2026-09-22T00:00:00Z', status: 'BLOCKED', reason: 'TEAM_RECIPIENTS_UNCONFIGURED' }], total: 1, offset: 0, limit: 20 });
      if (url.endsWith('/target')) return respond({ target: targetValue });
      if (url.endsWith('/read')) { const record = records.find(row => url.includes(row.id)); record.readAt ||= '2026-09-22T01:00:00Z'; return respond({ id: record.id, readAt: record.readAt }); }
      if (url.startsWith('/api/notifications/')) return respond({ notification: records.find(row => url.endsWith(row.id)) });
      if (url === '/api/approvals/tasks/9') return respond(taskView());
      if (url === '/api/demands/workflow?id=9') return respond({ workflow: workflow() });
      if (url === '/api/workflow/context') return respond(context());
      return normal(url, opts);
    };
    return fetchOverride ? fetchOverride(url, opts, fallback, respond) : fallback();
  } });
  h.sandbox.state.projectId = null;
  const noop = () => {};
  Object.assign(h.sandbox, { REGION_PROVINCES: [], NAV: [{ k: 'dashboard', l: '工作台', group: '业务', art: 'dashboard' }], location: { hash: '#/dashboard' }, brandSymbol: () => '',
    disposeLoginExperience: noop, navViewportCleanup: null, navKeyHandler: null, globalKeyHandler: null, scrollShadowHandler: null, v6PointerHandler: null, v7GlowHandler: null, loginRotTimer: null, loginRotSwap: null, environmentPanel: null,
    sceneBridge: { setMode: () => Promise.resolve(), setRoute: noop }, openCommandCenter: noop, showChangePwd: noop, showOrganizationBinding: noop, openShortcutGuide: noop, quickCreateDemand: noop, handleAppShortcut: noop,
    renderLogin: noop, clearInterval: noop });
  h.sandbox.window.innerWidth = 1024; h.sandbox.window.innerHeight = 768;
  h.sandbox.window.matchMedia = () => ({ matches: false, addEventListener: noop, removeEventListener: noop }); h.sandbox.window.removeEventListener = noop;
  h.sandbox.window.alert = h.sandbox.window.confirm = () => { throw Error('No native dialogs may be triggered'); };
  const appRoot = h.document.createElement('div'); appRoot.id = 'app'; h.body.appendChild(appRoot);
  h.sandbox.renderPage = () => { h.sandbox.beginRouteEpoch(); h.renders++; h.document.querySelector('#content').innerHTML = '<p>原首页当前待办保留</p>'; };
  vm.runInContext(extract('  function notificationTarget(', '  async function openCommandCenter()') + extract('  // ============ 原需求页面：提交、审批与团队受理', '  async function editForm(') + extract('  function renderLayout()', '  function showChangePwd()'), h.sandbox);
  h.mount = () => h.sandbox.renderLayout();
  h.open = () => h.sandbox.showMyNotifications();
  h.button = name => h.modal()?.querySelector('[data-notifications-' + name + ']');
  h.click = async name => { const el = h.button(name); check(el && !el.disabled, name + ' original button enabled'); await h.emit(el, 'click'); };
  h.act = async (label, id = notice(1).id) => { const binding = [...h.bindings].reverse().find(item => item.rows.some(row => row.id === id)); const item = binding.rows.find(row => row.id === id), action = binding.actions.find(action => action.l === label); check(action && (!action.show || action.show(item)), label + ' is an actual original table action'); await action.onClick(item); await tick(); };
  h.dispatch = async (node, type, values = {}) => {
    const event = { target: node, type, defaultPrevented: false, stopped: false, preventDefault() { this.defaultPrevented = true; }, stopPropagation() { this.stopped = true; }, ...values };
    const chain = []; for (let at = node; at; at = at.parentNode) chain.push(at);
    for (const at of chain) { await at['on' + type]?.(event); for (const fn of at.listeners.get(type) || []) await fn(event); if (event.stopped) break; }
    await tick(); return event;
  };
  h.uiClick = async node => {
    await h.dispatch(node, 'pointerdown'); node.focus(); await h.dispatch(node, 'pointerup');
    const click = await h.dispatch(node, 'click');
    if (!click.defaultPrevented && node.tagName === 'SUMMARY') { node.parentNode.open = !node.parentNode.open; await h.dispatch(node.parentNode, 'toggle'); }
    await tick();
  };
  h.uiMenu = async id => { const row = h.modal().querySelector('[data-row-id="' + id + '"]'), menu = row.querySelector('.row-more'); check(menu, 'Actual row has a more-actions menu'); await h.uiClick(menu.querySelector('summary')); return { menu, panel: h.document.querySelector('.v13-row-menu') }; };
  h.posts = () => h.calls.filter(call => call.opts.method === 'POST'); h.records = records;
  return h;
}
(async () => {
  const h = harness(); h.mount();
  check(h.document.querySelector('#btn-notifications').textContent.includes('我的通知'), 'Original account menu renders the notifications entry for a non-admin account');
  equal(h.calls, [], 'Rendering original home does not fetch notifications or diagnostics or change current queue');
  h.document.querySelector('#user-menu').setAttribute('open', ''); await h.emit(h.document.querySelector('#btn-notifications'), 'click'); await tick();
  check(!h.document.querySelector('#user-menu').hasAttribute('open') && h.modal(), 'Actual account-menu handler closes popover and opens original modal');
  check(h.modal().querySelector('tbody').children.length === 20 && h.modal().querySelector('#notifications-count').textContent.includes('未读 23 条'), 'Server page has bounded rows while unread count represents all visible notices');
  check(h.modal().querySelector('#notifications-channels').textContent.includes('邮件：发送尚未接通') && h.modal().querySelector('#notifications-channels').textContent.includes('公众号：尚未配置'), 'External channels retain actual unavailable states');
  check(!h.modal().querySelector('script') && h.modal().textContent.includes('<script>不得执行</script>'), 'Notification body is escaped by original table renderer');
  check(!h.calls.some(call => call.url.includes('diagnostics')), 'Audit is not probed automatically or inferred from old role');
  await h.click('next'); equal(h.modal().querySelector('tbody').children.length, 3, 'Next page shows only the returned final page');
  equal(new URL(h.calls.filter(call => call.url.startsWith('/api/notifications?')).at(-1).url, 'http://synthetic').searchParams.get('offset'), '20', 'Real next-page handler sends approved offset');
  await h.click('prev'); await h.act('查看内容');
  check(h.modal().querySelector('#notifications-detail').textContent.includes('合成通知 1') && h.posts().length === 0, 'Viewing content performs read-only notification detail without marking read');
  const renders = h.renders; await h.act('标为已读');
  equal(JSON.parse(h.posts()[0].opts.body), {}, 'Mark-read POST contains strict empty JSON and no identity or business action');
  check(h.records[0].readAt && h.modal().querySelector('#notifications-count').textContent.includes('未读 22 条') && h.renders === renders, 'Read receipt refreshes only inbox count and never the host workflow queue');
  check(!h.posts().some(call => /approvals|demands/.test(call.url)), 'Read action cannot mutate approval or demand state');
  await h.act('打开事项');
  check(h.modal().textContent.includes('准确的原需求') && h.modal().textContent.includes('合成审批记录') && h.modal().querySelector('[data-workflow-command="APPROVE"]'), 'Validated current M03 target opens the exact original workflow detail and current permitted actions');
  equal(h.sandbox.state.page, 'dashboard', 'Opening demand details from notification does not repurpose demand ID as project or route away');
  check(h.calls.some(call => call.url === '/api/approvals/tasks/9') && h.posts().length === 1, 'Approval target reads actual task and does not automatically mark or approve');
  const targetReads = h.calls.filter(call => call.url.endsWith('/target')).length;
  await h.modal().querySelector('[data-workflow-command="APPROVE"]').onclick();
  check(h.calls.filter(call => call.url.endsWith('/target')).length === targetReads + 1 && h.modal().querySelector('#workflow-action-notice'), 'Explicit handling click refetches target and latest server actions before entering original approval form');
  check(h.posts().length === 1, 'Opening original approval form still requires a separate user confirmation to mutate');

  for (const moduleId of ['M02', 'M03']) {
    const readOnly = harness({ targetValue: target(moduleId, 'detail') }); readOnly.mount(); await readOnly.open(); await readOnly.act('打开事项');
    check(readOnly.modal().textContent.includes('本窗口只读') && !readOnly.modal().querySelector('[data-workflow-command]') && readOnly.posts().length === 0, moduleId + ': historical detail stays read-only even when newer workflow actions exist');
    check(readOnly.sandbox.state.projectId === null && readOnly.modal().textContent.includes('完整的原需求资料'), moduleId + ': original demand record opens without a guessed project link');
  }
  let clicks = 0;
  const advanced = harness({ fetchOverride: (url, opts, normal, respond) => url.endsWith('/target') ? respond({ target: target('M03', ++clicks === 1 ? 'task' : 'detail') }) : normal() }); advanced.mount(); await advanced.open(); await advanced.act('打开事项'); await advanced.modal().querySelector('[data-workflow-command="APPROVE"]').onclick();
  check(advanced.modal().textContent.includes('本窗口只读') && !advanced.modal().querySelector('#workflow-action-notice') && advanced.posts().length === 0, 'Notification resolved between viewing and handling becomes read-only rather than opening a newer action');

  for (const bad of [
    { moduleId: 'M08', params: target().params }, { ...target(), url: 'https://invalid.test' }, { ...target(), params: { ...target().params, returnUrl: '#/projects' } },
    { ...target(), params: { ...target().params, recordId: '9007199254740992' } }, { ...target(), params: { ...target().params, eventId: 'event:other' } },
    { ...target(), params: { ...target().params, taskId: 'task:opaque' } }, { ...target('M02', 'detail'), params: { recordId: '9', eventId: 'event:1', view: 'task' } },
  ]) {
    const invalid = harness({ targetValue: bad }); invalid.mount(); await invalid.open(); const mask = invalid.modal(); await invalid.act('打开事项');
    check(invalid.modal() === mask && invalid.modal().textContent.includes('通知服务暂不可用') && !invalid.calls.some(call => /api\/(approvals|demands)/.test(call.url)), 'Unrecognized/forged target is rejected before any business read or navigation');
  }
  for (const mismatch of ['id', 'businessId', 'workflow']) {
    const invalid = harness({ fetchOverride: (url, opts, normal, respond) => { if (url !== '/api/approvals/tasks/9') return normal(); const value = taskView(); if (mismatch === 'workflow') value.task.workflow.id = 7; else value.task[mismatch] = 7; return respond(value); } }); invalid.mount(); await invalid.open(); const mask = invalid.modal(); await invalid.act('打开事项');
    check(invalid.modal() === mask && !invalid.modal().querySelector('[data-workflow-command]'), 'M03 ' + mismatch + ' must match on the actual rendered task response');
  }
  const wrongDemand = harness({ targetValue: target('M02', 'detail'), fetchOverride: (url, opts, normal, respond) => url === '/api/demands/workflow?id=9' ? respond({ workflow: { ...workflow(), id: 8 } }) : normal() }); wrongDemand.mount(); await wrongDemand.open(); const wrongMask = wrongDemand.modal(); await wrongDemand.act('打开事项'); check(wrongDemand.modal() === wrongMask, 'M02 requires exact returned demand ID');

  for (const status of [403, 404]) for (const operation of ['target', 'read', 'detail']) {
    const denied = harness({ fetchOverride: (url, opts, normal, respond) => url.includes('/notifications/00000000') && (operation === 'detail' ? !url.endsWith('/target') && !url.endsWith('/read') : url.endsWith('/' + operation)) ? respond(null, status) : normal() }); denied.mount(); await denied.open(); await denied.act(operation === 'target' ? '打开事项' : operation === 'read' ? '标为已读' : '查看内容');
    check(!denied.modal().querySelector('tbody') && denied.modal().querySelector('#notifications-count').textContent === '通知数量待核实', status + ' ' + operation + ': revoked/expired records and counts are cleared');
  }
  const failed = harness({ fetchOverride: (url, opts, normal, respond) => url.startsWith('/api/notifications?') ? respond(null, 503) : normal() }); failed.mount(); await failed.open();
  check(failed.document.querySelector('#content').textContent.includes('原首页当前待办保留') && failed.modal().textContent.includes('通知服务暂不可用'), 'Notification service failure is local and cannot break original home');
  const expired = harness({ fetchOverride: (url, opts, normal, respond) => url.endsWith('/target') ? respond(null, 401) : normal() }); expired.mount(); await expired.open(); await expired.act('打开事项'); check(expired.invalidations === 1 && !expired.modal(), '401 uses original host session invalidation and clears owned modal');
  const audit = harness(); audit.mount(); await audit.open(); await audit.click('audit');
  check(audit.modal().textContent.includes('承接团队收件人尚未配置') && !audit.modal().querySelector('[data-act]') && audit.posts().length === 0, 'Explicit diagnostics reuse original table with no delivery/consume/retry controls');
  const auditDenied = harness({ writer: true, fetchOverride: (url, opts, normal, respond) => url.includes('/notifications/diagnostics?') ? respond(null, 403) : normal() }); auditDenied.mount(); await auditDenied.open(); await auditDenied.click('audit'); check(auditDenied.modal().textContent.includes('没有此项查看权限') && !auditDenied.modal().querySelector('tbody'), 'Old admin role does not grant audit scope');

  const latePage = deferred(); const paging = harness({ fetchOverride: (url, opts, normal, respond) => url === '/api/notifications?offset=20&limit=20' ? latePage.promise.then(respond) : normal() }); paging.mount(); await paging.open(); const next = paging.click('next'); await tick(); await paging.click('inbox'); latePage.resolve({ items: [notice(23)], total: 23, unreadCount: 23, offset: 20, limit: 20 }); await next;
  check(paging.modal().querySelector('tbody').children.length === 20 && paging.calls.find(call => call.url.includes('offset=20')).opts.signal.aborted, 'Returning to inbox aborts and ignores an older page response');
  for (const departure of ['page', 'close', 'identity', 'project', 'replacement', 'pagination']) {
    const slowTarget = deferred(); const late = harness({ fetchOverride: (url, opts, normal, respond) => url.endsWith('/target') ? slowTarget.promise.then(respond) : normal() }); late.mount(); await late.open(); const opening = late.act('打开事项'); await tick(); let successor = null;
    if (departure === 'page') { late.sandbox.beginRouteEpoch(); late.sandbox.state.page = 'projects'; }
    if (departure === 'close') late.sandbox.closeModal();
    if (departure === 'identity') late.sandbox.state.user = { uid: 99, role: 'admin' };
    if (departure === 'project') late.sandbox.state.projectId = 7;
    if (departure === 'replacement') successor = late.sandbox.openModal('后来弹窗', '<p>保留后来内容</p>', { noFoot: true });
    if (departure === 'pagination') await late.click('next');
    slowTarget.resolve({ target: target() }); await opening;
    check(!late.calls.some(call => call.url === '/api/approvals/tasks/9') && late.posts().length === 0, departure + ': late target never reads/navigates to another business record');
    if (successor) check(late.modal() === successor, 'Late target cannot remove a successor modal');
    else if (departure === 'pagination') check(late.modal().querySelector('tbody').children.length === 3, 'Changing inbox page preserves the latest visible page');
    else check(!late.modal(), departure + ': obsolete owned modal is cleared');
  }
  const lateTask = deferred(); const changing = harness({ fetchOverride: (url, opts, normal, respond) => url === '/api/approvals/tasks/9' ? lateTask.promise.then(respond) : normal() }); changing.mount(); await changing.open(); const openingTask = changing.act('打开事项'); await tick(); await changing.click('next'); lateTask.resolve(taskView()); await openingTask;
  check(changing.modal().querySelector('tbody').children.length === 3 && !changing.modal().querySelector('[data-workflow-command]') && changing.calls.find(call => call.url === '/api/approvals/tasks/9').opts.signal.aborted, 'Pagination during latest task read aborts bridge and prevents late detail replacement');

  const portal = harness(); portal.mount(); await portal.open(); const portalMask = portal.modal();
  let menuState = await portal.uiMenu(notice(1).id);
  check(menuState.panel && menuState.panel.parentNode === portalMask && !portalMask.querySelector('table').contains(menuState.panel), 'Modal row menu is portalled outside table clipping but remains inside its own backdrop/focus boundary');
  const readButton = menuState.panel.querySelectorAll('button').find(button => button.textContent.includes('标为已读'));
  readButton.focus();
  check(portal.document.activeElement === readButton && menuState.menu.open && menuState.panel.parentNode === portalMask, 'Focusing the actual portalled button does not close or move its menu before click');
  await portal.uiClick(readButton);
  check(portal.posts().length === 1 && portal.posts()[0].url.endsWith('/read') && portal.records[0].readAt, 'Pointer/focus/click through real bindTableActions performs mark-read exactly once');
  check(portal.modal() === portalMask && !portal.document.querySelector('.v13-row-menu') && portalMask.querySelector('#notifications-count').textContent.includes('未读 22 条'), 'Successful real menu click closes its portal and refreshes reading count in the same modal');
  menuState = await portal.uiMenu(notice(2).id);
  const contentButton = menuState.panel.querySelectorAll('button').find(button => button.textContent.includes('查看内容')); await portal.uiClick(contentButton);
  check(portalMask.querySelector('#notifications-detail').textContent.includes('合成通知 2') && portal.posts().length === 1, 'Secondary content action also works through the portalled menu and remains read-only');
  menuState = await portal.uiMenu(notice(2).id); const keyboardSummary = menuState.menu.querySelector('summary');
  await portal.dispatch(keyboardSummary, 'keydown', { key: 'ArrowDown' });
  check(portal.document.activeElement === menuState.panel.querySelector('button'), 'Original ArrowDown menu opening focuses its first actual action');
  await portal.dispatch(portal.document.activeElement, 'keydown', { key: 'Escape' });
  check(portal.modal() === portalMask && !portal.document.querySelector('.v13-row-menu') && portal.document.activeElement === keyboardSummary, 'Escape closes only the owned menu and restores its trigger without closing the modal');
  menuState = await portal.uiMenu(notice(2).id);
  portal.button('reload').focus(); check(!portal.document.querySelector('.v13-row-menu') && portal.modal() === portalMask, 'Moving focus to another control closes only the menu');
  menuState = await portal.uiMenu(notice(2).id); portal.sandbox.closeModal();
  check(!portal.modal() && !portal.document.querySelector('.v13-row-menu'), 'Closing the owner modal cleans the active portal');
  const background = portal.document.querySelector('#content'); background.innerHTML = portal.sandbox.renderTable([{ k: 'title', l: '事项' }], [notice(1)], [{ l: '主操作', onClick() {} }, { l: '次操作', onClick() {} }]);
  portal.sandbox.bindTableActions(background, [notice(1)], [{ l: '主操作', onClick() {} }, { l: '次操作', onClick() {} }]);
  const backgroundMenu = background.querySelector('.row-more'); await portal.uiClick(backgroundMenu.querySelector('summary'));
  check(portal.document.querySelector('.v13-row-menu').parentNode === portal.body, 'Ordinary page tables preserve the original body portal placement');
  const replacement = portal.sandbox.openModal('后续弹窗', '<button type="button" id="safe-focus">弹窗控件</button>', { noFoot: true });
  check(!portal.document.querySelector('.v13-row-menu') && replacement === portal.modal(), 'Opening a modal clears a background page portal without admitting outside controls');
  const last = replacement.querySelector('#safe-focus'); last.focus(); const tab = await portal.dispatch(last, 'keydown', { key: 'Tab' });
  check(tab.defaultPrevented && portal.document.activeElement === replacement.querySelector('#modal-x'), 'Modal keyboard focus continues to wrap only within its own controls');

  const summarySource = fs.readFileSync(path.join(__dirname, 'IntegrationSummariesUI.test.cjs'), 'utf8');
  const summarySupport = vm.runInNewContext(summarySource.slice(0, summarySource.lastIndexOf('\n(async () => {')) + '\n({ harness });', { ...globals });
  const summary = summarySupport.harness(); await summary.mount(); await summary.open(); await summary.edit('issues', '未保存总结'); const summaryMask = summary.modal();
  vm.runInContext(extract('  function notificationTarget(', '  async function openCommandCenter()'), summary.sandbox); await summary.sandbox.showMyNotifications();
  check(summary.modal() === summaryMask && summary.field('issues').value === '未保存总结' && summary.button('stay') && !summary.calls.some(call => call.url.startsWith('/api/notifications')), 'Dirty M08 refuses notification modal cleanly without requests or mixed controls');

  const accountSource = fs.readFileSync(path.join(__dirname, 'IntegrationAccountBindingsUI.test.cjs'), 'utf8');
  const accounts = vm.runInNewContext(accountSource.slice(0, accountSource.lastIndexOf('\n(async () => {')) + '\n({ harness });', { ...globals });
  const self = accounts.harness({ fetchOverride: (url, opts, normal, respond) => url === '/api/organization/config' && opts.method === 'POST' ? respond({ version: 'saved-version', configuration: JSON.parse(opts.body).configuration, sessionInvalidated: true }) : normal(url, opts) });
  await self.mount(); self.clickAction(14, '绑定人员'); self.set('personCode', 'PERSON-00004'); self.set('enabled', 'false'); await self.save();
  check(self.invalidations === 1 && !self.modal() && self.sandbox.state.user === null, 'Actual account-binding save success naturally returns to login when publisher session is revoked');
  check(self.toasts.some(item => item.message === '账号配置已保存，请重新登录') && !self.toasts.some(item => item.error || item.message === '绑定已保存'), 'Success is not misreported as failure or followed by stale identity success UI');
  console.log(JSON.stringify({ ok: true, suite: 'original notifications host integration', checks, transport: 'synthetic', browser: false, database: false }));
})().catch(error => { console.error(error); process.exitCode = 1; });
