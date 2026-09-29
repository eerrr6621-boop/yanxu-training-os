'use strict';
// Real project page, summary form/history, original modal/navigation and authenticated API.
// Only DOM and HTTP transport are synthetic. No database or real documents.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const original = fs.readFileSync(path.join(__dirname, 'IntegrationDeliveryCatalogUI.test.cjs'), 'utf8');
const support = vm.runInNewContext(original.slice(0, original.indexOf('(async () => {')) + '\n({ harness, project, NodeStub });', { require, __dirname, console, structuredClone, AbortController, DOMException, URL, URLSearchParams, Buffer, setImmediate });
const descriptor = Object.getOwnPropertyDescriptor(support.NodeStub.prototype, 'innerHTML');
Object.defineProperty(support.NodeStub.prototype, 'innerHTML', { get: descriptor.get, set(value) { descriptor.set.call(this, value); this.querySelectorAll('textarea').forEach(el => { el.value = el.textContent; }); } });
const app = fs.readFileSync(path.join(__dirname, '../web/app.js'), 'utf8');
const extract = (start, end) => { const a = app.indexOf(start), b = app.indexOf(end, a); assert.ok(a >= 0 && b > a); return app.slice(a, b); };
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
let checks = 0;
const check = (value, label) => { assert.ok(value, label); checks++; };
const equal = (actual, expected, label) => { assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected, label); checks++; };
const empty = () => ({ achievements: '', issues: '', nextSteps: '' });
const content = () => ({ achievements: ' 保留原成果\n第二行 ', issues: '原问题', nextSteps: '原计划', publicity: { title: '合成总结', introduction: '项目引言', sections: [{ heading: '课程理论', body: '章节正文' }, { heading: '互动研讨', body: '互动正文' }], photoCaptions: ['课堂授课', '互动研讨'] } });
const sources = (suffix = 'a') => ({ project: { status: 'AVAILABLE', value: { project_id: 5, start_date: null, end_date: '2026-09-22', participant_count: null, project_code: null, course_codes: null } }, feedback: { status: 'UNAVAILABLE', value: null }, delivery: { status: 'UNAVAILABLE', value: null }, source_version: suffix.repeat(64), codes: { project: 'UNASSIGNED', courses: 'UNASSIGNED' } });
const deliveryFacts = () => ({ dispatch_count: 3, reviewed_completed_count: 1, pending_count: 2, unverified_completed_count: 0, estimated: '8.50', planned: '6.25', actual: '1.33', payable: '1.25' });
const deliverySources = (facts = deliveryFacts(), extra = {}) => ({ ...sources(), delivery: { status: 'AVAILABLE', value: facts, ...extra } });
const deliveryRows = root => (root.querySelector('[data-summary-delivery]')?.querySelector('tbody')?.querySelectorAll('tr') || []).map(row => row.querySelectorAll('td').map(cell => cell.textContent));
const revision = (version = 1, body = content(), source = sources()) => ({ project_id: 5, revision: version, content: body, sources: source, saved_at: '2026-09-22T02:30:00Z', operation: version === 1 ? 'CREATE' : 'SAVE', actor_code: 'ACTOR-SYNTH', status: 'DRAFT', read_only: true, synthetic: false });
const view = (version = 1, body = content(), edit = true, source = sources()) => ({ project_id: 5, version, revision: version, latest_version: version, current: version ? revision(version, body, source) : null, sources: source, source_changed: false,
  synthetic: false, draft_only: true, replayed: false, reload_required: false, project_status: edit ? '进行中' : '已归档', capabilities: { read: true, edit, refresh_sources: edit && version > 0, submit: false, review: false, export: false } });
function harness({ initial = view(), writer = false, fetchOverride } = {}) {
  let remote = structuredClone(initial);
  const h = support.harness({ page: 'project_detail', writer, projectRows: [{ ...support.project(5), workflow_source: { business_path: 'direct' } }], fetchOverride: (url, opts, normal, respond) => {
    const fallback = () => {
      const u = new URL(url, 'http://synthetic');
      if (u.pathname === '/api/training-summaries') return respond(remote);
      if (u.pathname === '/api/training-summaries/save') { const body = JSON.parse(opts.body); remote = view(remote.version + 1, body.content, true, remote.sources); return respond(remote); }
      if (u.pathname === '/api/training-summaries/refresh') { remote = view(remote.version + 1, remote.current.content, true, sources('b')); return respond(remote); }
      if (u.pathname === '/api/training-summaries/history') { const offset = Number(u.searchParams.get('offset')); return respond({ project_id: 5, synthetic: false, offset, limit: 20, total: 23, items: Array.from({ length: Math.min(20, 23 - offset) }, (_, i) => ({ revision: 23 - offset - i, operation: 'SAVE', saved_at: '2026-09-22T02:30:00Z', status: 'DRAFT' })) }); }
      if (u.pathname === '/api/training-summaries/revision') return respond(revision(Number(u.searchParams.get('revision')), { ...empty(), achievements: '<script>历史只读正文</script>' }));
      return normal(url, opts);
    };
    return fetchOverride ? fetchOverride(url, opts, fallback, respond, { get: () => remote, set: value => { remote = value; } }) : fallback();
  } });
  h.beforeUnload = null;
  h.sandbox.window.confirm = () => { throw Error('Summary must use an inline confirmation, never a native dialog'); };
  h.sandbox.window.addEventListener = (event, fn) => { if (event === 'beforeunload') h.beforeUnload = fn; };
  h.sandbox.window.removeEventListener = (event, fn) => { if (event === 'beforeunload' && h.beforeUnload === fn) h.beforeUnload = null; };
  h.sandbox.sceneBridge = { setRoute() {} }; h.sandbox.renderLayout = () => { h.renders++; h.sandbox.beginRouteEpoch(); };
  h.sandbox.location = { hash: '#/project_detail?project=5' }; h.sandbox.lastRenderedHash = h.sandbox.location.hash;
  h.sandbox.history = { replaceState: (_, __, hash) => { h.sandbox.location.hash = hash; }, pushState: (_, __, hash) => { h.sandbox.location.hash = hash; } };
  h.sandbox.NAV = [{ k: 'dashboard', l: '工作台' }, { k: 'projects', l: '培训项目' }]; h.sandbox.PAGE_META = { dashboard: ['工作台', '查看工作进展'], projects: ['培训项目', '查看培训项目'] };
  vm.runInContext(extract('  function workflowLocalTime(', '  function workflowDetailHtml(') + extract('  function navigateTo(', '  function revealFocusedRow(') + extract('  function handleLocationRoute()', "  window.addEventListener('popstate'") + extract('  async function openCommandCenter()', '  function renderLayout()'), h.sandbox);
  const mount = h.mount; h.mount = async () => { await mount(); await tick(); };
  h.panel = () => h.content.querySelector('#project-summary-content');
  h.open = async () => { const node = h.panel().querySelector('[data-summary-open]'); check(node, 'Summary entry comes from the server capability'); await h.emit(node, 'click'); };
  h.button = key => h.modal()?.querySelector('[data-summary-' + key + ']');
  h.click = async key => { const node = h.button(key); check(node && !node.disabled, key + ' action is enabled'); await h.emit(node, 'click'); };
  h.field = key => h.modal()?.querySelector('[data-k="' + key + '"]');
  h.edit = async (key, value) => { const el = h.field(key); check(el && !el.disabled, key + ' field is editable'); el.value = value; await h.emit(el, 'input'); };
  h.requests = operation => h.calls.filter(call => call.url === '/api/training-summaries/' + operation);
  h.reads = () => h.calls.filter(call => call.url.startsWith('/api/training-summaries?'));
  return h;
}
(async () => {
  const h = harness(); await h.mount(); await h.open();
  check(h.sandbox.state.user.role === 'viewer' && h.button('save'), 'Old readonly role does not override explicit summary.edit capability');
  equal(h.field('achievements').value, content().achievements, 'Existing three-field content preserves surrounding whitespace and line breaks');
  equal([h.field('publicityTitle').value, h.field('heading1').value, h.field('body1').value, h.field('caption1').value], ['合成总结', '互动研讨', '互动正文', '互动研讨'], 'Complete publicity sections and captions populate original form controls');
  check(h.modal().querySelector('#summary-sources').textContent.includes('人数：待补齐') && h.modal().textContent.includes('不代表本项目没有授课或评价'), 'Unknown business sources are neither zero nor no-record assertions');
  check(!h.panel().textContent.includes('T02:30') && !h.modal().textContent.includes('a'.repeat(64)), 'Business view uses local time and hides technical source hashes');
  await h.edit('body1', '更新后的互动正文'); await h.click('add-section'); await h.edit('heading2', '组织保障'); await h.edit('body2', '新增完整章节'); await h.click('add-caption'); await h.edit('caption2', '成果展示');
  const originalMask = h.modal(); await h.click('save');
  equal(JSON.parse(h.requests('save')[0].opts.body).content, { ...content(), publicity: { ...content().publicity, sections: [{ heading: '课程理论', body: '章节正文' }, { heading: '互动研讨', body: '更新后的互动正文' }, { heading: '组织保障', body: '新增完整章节' }], photoCaptions: ['课堂授课', '互动研讨', '成果展示'] } }, 'Save submits full nested content without dropping old fields, sections or captions');
  equal(Object.keys(JSON.parse(h.requests('save')[0].opts.body)).sort(), ['content', 'expected_version', 'project_id', 'request_id'], 'Mutation excludes facts, permissions, actor and source snapshots');
  check(h.modal() === originalMask && h.renders === 0 && h.panel().textContent.includes('第 2 版'), 'Save refreshes only the summary panel and leaves same original editor open');
  await h.edit('issues', '保留待保存问题'); const beforeRefresh = h.requests('refresh').length; await h.click('refresh');
  check(h.requests('refresh').length === beforeRefresh && h.field('issues').value === '保留待保存问题' && h.modal().textContent.includes('请先保存或取消正文修改'), 'Source refresh is blocked while unsaved body exists');
  await h.click('save'); await h.click('refresh');
  equal(Object.keys(JSON.parse(h.requests('refresh')[0].opts.body)).sort(), ['expected_version', 'project_id', 'request_id'], 'Explicit source refresh carries version/request identity but never unsaved body');
  equal(h.field('issues').value, '保留待保存问题', 'Source refresh preserves the last saved full body');
  check(!h.calls.some(call => /training-summaries\/(submit|review|export)/.test(call.url)), 'No formal submit/review/export endpoint or demo exporter is used');
  await h.edit('nextSteps', '未保存计划'); await h.click('show-history');
  check(h.modal() === originalMask && h.field('nextSteps').value === '未保存计划', 'History opens within the existing editor and preserves unsaved text');
  equal(h.modal().querySelector('#summary-history').querySelector('tbody').children.length, 20, 'History uses bounded server pagination');
  await h.click('history-next');
  equal(new URL(h.calls.filter(call => call.url.startsWith('/api/training-summaries/history?')).at(-1).url, 'http://synthetic').searchParams.get('offset'), '20', 'Next history page requests offset20');
  const binding = h.bindings.at(-1); await binding.actions[0].onClick(binding.rows[0]); await tick();
  check(h.modal().querySelector('#summary-revision').textContent.includes('<script>历史只读正文</script>') && !h.modal().querySelector('#summary-revision').querySelector('script'), 'Specified historical revision is escaped and read-only');
  check(!h.modal().querySelector('#summary-revision').querySelector('input, textarea') && h.field('nextSteps').value === '未保存计划', 'Viewing history never loads a historical version into the editor');
  await h.click('close'); check(h.modal() === originalMask && h.field('nextSteps').value === '未保存计划' && h.button('stay'), 'Cancel displays inline protection while preserving unsaved body'); await h.click('stay'); check(!h.button('discard'), 'Continue editing removes the inline discard prompt');
  originalMask._keyHandler({ key: 'Escape', preventDefault() {} }); check(h.modal() === originalMask, 'Escape uses the same dirty-form protection');
  check(h.sandbox.openModal('其他弹窗', '<p>不替换</p>', { noFoot: true }) === null && h.modal() === originalMask, 'Attempting another modal cannot replace the dirty summary');
  h.sandbox.navigateTo('projects'); check(h.sandbox.state.page === 'project_detail' && h.modal() === originalMask, 'Original navigation stops before mutating project state when discard is declined');
  h.sandbox.location.hash = '#/dashboard'; h.sandbox.handleLocationRoute(); check(h.sandbox.state.page === 'project_detail' && h.sandbox.location.hash === '#/project_detail?project=5', 'Browser location navigation restores the existing route after declined discard');
  const unload = { prevented: false, preventDefault() { this.prevented = true; } }; h.beforeUnload(unload); check(unload.prevented && unload.returnValue === '', 'Browser close/reload receives unsaved-change protection');
  await h.click('discard'); check(!h.modal() && h.sandbox.state.page === 'project_detail' && !h.beforeUnload, 'Explicit discard closes summary and keeps current route until user chooses destination again'); h.sandbox.navigateTo('projects'); check(h.sandbox.state.page === 'projects', 'User can navigate normally after explicitly closing the summary');

  const shortcut = harness(); await shortcut.mount(); await shortcut.open(); await shortcut.edit('issues', '快捷入口保留正文');
  const shortcutMask = shortcut.modal(), projectsBefore = shortcut.calls.filter(call => call.url === '/api/projects').length;
  await shortcut.sandbox.openCommandCenter();
  check(shortcut.modal() === shortcutMask && shortcut.field('issues').value === '快捷入口保留正文' && !shortcut.modal().querySelector('#command-query'), 'Real command-center handler cancels replacement before binding or mixing its controls into summary');
  check(shortcut.calls.filter(call => call.url === '/api/projects').length === projectsBefore, 'Declined command center does not launch its project read');
  await shortcut.click('stay'); await shortcut.sandbox.openCommandCenter(); await shortcut.click('discard');
  check(!shortcut.modal() && shortcut.sandbox.state.page === 'project_detail', 'Discarding summary does not automatically open a previously requested shortcut');
  await shortcut.sandbox.openCommandCenter();
  check(shortcut.modal() !== shortcutMask && shortcut.modal().querySelector('#command-query') && !shortcut.button('save'), 'Re-invoking command center after explicit discard opens its original independent controls');
  const commandQuery = shortcut.modal().querySelector('#command-query'); commandQuery.value = '培训项目'; await shortcut.emit(commandQuery, 'input');
  check(shortcut.modal().querySelector('.command-result').textContent.includes('培训项目'), 'Command center still filters genuine original navigation commands');

  const workflows = harness({ fetchOverride: (url, opts, normal, respond) => {
    if (url === '/api/workflow/context') return respond({ person: { person_code: 'SYNTH-PERSON', organization_code: 'SYNTH-ORG' }, can_create: true, organizations: [{ code: 'SYNTH-ORG', label: '合成机构', can_write: true }], people: [], teams: [] });
    if (url.startsWith('/api/demands/workflow?')) return respond({ workflow: { id: 9, version: 1, draft: true, business_path: 'direct', organization_code: 'SYNTH-ORG', form: { title: '合成需求' }, legacy: {}, actions: { edit: true } } });
    return normal();
  } });
  workflows.sandbox.REGION_PROVINCES = [];
  vm.runInContext(extract('  const CRUD = {', '  async function fillOptions(') + extract('  // ============ 原需求页面：提交、审批与团队受理', '  async function editForm('), workflows.sandbox);
  await workflows.mount(); await workflows.open(); await workflows.edit('issues', '流程入口保留正文'); const workflowsMask = workflows.modal();
  await workflows.sandbox.showDemandWorkflow({ id: 9 });
  check(workflows.modal() === workflowsMask && workflows.field('issues').value === '流程入口保留正文' && !workflowsMask.dataset.workflow, 'Real workflow detail returns safely when original modal replacement is declined');
  await workflows.click('stay'); await workflows.sandbox.editWorkflowDemand({ id: 9 });
  check(workflows.modal() === workflowsMask && workflows.field('issues').value === '流程入口保留正文' && !workflows.modal().querySelector('#workflow-route-hint'), 'Real workflow editor never binds handlers into dirty summary when replacement is declined');
  check(workflows.calls.filter(call => /\/api\/(workflow\/context|demands\/workflow)/.test(call.url)).every(call => call.opts.signal.aborted), 'Declined workflow modal requests dispose their route scopes');

  const fresh = harness({ initial: view(0, empty()) }); await fresh.mount(); await fresh.open();
  check(fresh.modal().querySelector('#summary-sources').textContent.includes('首次保存时冻结') && !fresh.field('publicityTitle'), 'New blank draft preserves optional publicity and labels sources as not yet frozen');
  await fresh.click('save'); equal(JSON.parse(fresh.requests('save')[0].opts.body).content, empty(), 'All-empty drafts can save without inventing publicity or required text');
  await fresh.click('add-publicity');
  for (let i = 0; i < 12; i++) await fresh.click('add-section');
  for (let i = 0; i < 6; i++) await fresh.click('add-caption');
  check(fresh.button('add-section').disabled && fresh.button('add-caption').disabled, '12 section and 6 caption limits survive subsequent button state updates');
  const readOnly = harness({ writer: true, initial: view(1, content(), false) }); await readOnly.mount(); await readOnly.open();
  check(!readOnly.button('save') && !readOnly.button('refresh') && readOnly.field('body0').disabled, 'Archived/read-only server capability wins over old admin role');
  for (const status of [403, 409, 503]) {
    const denied = harness({ fetchOverride: (url, opts, normal, response) => url.startsWith('/api/training-summaries?') ? response(null, status) : normal() }); await denied.mount();
    check(!denied.panel().querySelector('[data-summary-open]') && denied.content.querySelector('.delivery-panel'), status + ': summary failure stays local to its panel');
  }
  for (const status of [401, 403]) {
    const revoked = harness({ fetchOverride: (url, opts, normal, response) => url.endsWith('/training-summaries/save') ? response(null, status) : normal() }); await revoked.mount(); await revoked.open(); await revoked.edit('achievements', '本地敏感正文'); await revoked.click('save');
    check(!revoked.modal(), status + ': session/permission revocation clears local editor despite dirty form');
    if (status === 401) check(revoked.invalidations === 1 && revoked.content.textContent === '登录', '401 follows original host invalidation');
    else check(!revoked.panel().querySelector('[data-summary-open]') && revoked.panel().textContent.includes('没有本项目总结权限'), '403 removes prior summary and actions');
  }
  let conflictOnce = true;
  const conflict = harness({ fetchOverride: (url, opts, normal, response, remote) => {
    if (url.endsWith('/training-summaries/save') && conflictOnce) { conflictOnce = false; remote.set(view(2, { ...content(), achievements: '服务器新成果' })); return response(null, 409); }
    return normal();
  } }); await conflict.mount(); await conflict.open(); await conflict.edit('achievements', '本地冲突成果'); await conflict.click('save');
  check(conflict.field('achievements').value === '本地冲突成果' && conflict.modal().querySelector('#summary-conflict').textContent.includes('服务器新成果') && conflict.button('save').disabled, '409 preserves body and requires explicit comparison before another save');
  await conflict.click('keep-local'); await conflict.click('save'); equal(JSON.parse(conflict.requests('save')[1].opts.body).expected_version, 2, 'Explicit keep-local choice rebases on freshly read current version');
  check(JSON.parse(conflict.requests('save')[0].opts.body).request_id !== JSON.parse(conflict.requests('save')[1].opts.body).request_id, 'A new resolved operation uses a new request identity');

  let overrideOnce = true;
  const override = harness({ fetchOverride: (url, opts, normal, response, remote) => {
    if (url.endsWith('/training-summaries/save') && overrideOnce) { overrideOnce = false; remote.set(view(2, { ...content(), issues: '服务器问题' })); return response(null, 409); }
    return normal();
  } }); await override.mount(); await override.open(); await override.edit('issues', '本地待保留问题'); await override.click('save'); await override.click('use-latest');
  check(override.field('issues').value === '本地待保留问题' && override.button('confirm-latest'), 'Server replacement first displays an inline explicit confirmation without changing local text');
  await override.click('stay'); check(override.field('issues').value === '本地待保留问题' && !override.button('confirm-latest'), 'Continue editing cancels replacement and retains local text');
  await override.click('use-latest'); await override.click('confirm-latest');
  check(override.field('issues').value === '服务器问题' && !override.button('save').disabled && !override.modal().querySelector('#summary-conflict').textContent, 'Only explicit inline confirmation adopts the displayed server body and current version');

  for (const replayed of [false, true]) {
    let writes = 0, failHead = false, headFailures = 0;
    const recover = harness({ fetchOverride: (url, opts, normal, response, remote) => {
      if (url.endsWith('/training-summaries/save')) {
        writes++; remote.set(view(3, { ...content(), nextSteps: '服务器第三版计划' }));
        if (replayed && writes === 1) throw Error('synthetic lost acknowledgement');
        failHead = true;
        return replayed ? response({ ...view(2, JSON.parse(opts.body).content), replayed: true, reload_required: true, latest_version: 3 }) : response(null, 409);
      }
      if (url.startsWith('/api/training-summaries?') && failHead && headFailures++ === 0) return response(null, 503);
      return normal();
    } }); await recover.mount(); await recover.open(); await recover.edit('nextSteps', '本地计划不能丢'); await recover.click('save');
    if (replayed) await recover.click('retry');
    check(recover.field('nextSteps').value === '本地计划不能丢' && recover.button('save').disabled && recover.button('retry').disabled && recover.button('read-current'), (replayed ? 'Replay' : '409') + ': failed current read keeps body, blocks stale new save and offers a real recovery action');
    const writeCount = recover.requests('save').length; await recover.click('read-current');
    check(recover.requests('save').length === writeCount && recover.field('nextSteps').value === '本地计划不能丢' && recover.modal().querySelector('#summary-conflict').textContent.includes('服务器第三版计划'), 'Read recovery does not replay a mutation or overwrite local text');
    await recover.click('keep-local');
    check(!recover.button('save').disabled && recover.field('nextSteps').value === '本地计划不能丢', 'Successful recovery requires explicit comparison before enabling a new revision');
  }

  let attempt = 0, acknowledged;
  const retry = harness({ fetchOverride: (url, opts, normal, response, remote) => {
    if (url.endsWith('/training-summaries/save')) {
      if (++attempt === 1) { acknowledged = view(2, JSON.parse(opts.body).content); remote.set(acknowledged); throw Error('synthetic lost acknowledgement'); }
      return response({ ...acknowledged, replayed: true });
    } return normal();
  } }); await retry.mount(); await retry.open(); await retry.edit('issues', '第一次保存问题'); await retry.click('save');
  check(retry.button('save').disabled && !retry.button('retry').disabled && retry.field('issues').value === '第一次保存问题', 'Uncertain write retains original operation and editable body with explicit retry');
  await retry.edit('issues', '网络失败后继续修改'); await retry.click('retry');
  equal(JSON.parse(retry.requests('save')[0].opts.body), JSON.parse(retry.requests('save')[1].opts.body), 'Network retry reuses byte-equivalent original body and request_id');
  equal(retry.field('issues').value, '网络失败后继续修改', 'Acknowledgement of old saved body does not overwrite subsequent local edits');
  check(retry.modal().textContent.includes('本地还有后续修改'), 'Newer unsaved edits remain visibly unsaved after replay');

  let replayAttempt = 0;
  const replay = harness({ fetchOverride: (url, opts, normal, response, remote) => {
    if (url.endsWith('/training-summaries/save')) {
      if (++replayAttempt === 1) { remote.set(view(3, { ...content(), achievements: '其他新版本成果' })); throw Error('lost'); }
      return response({ ...view(2, JSON.parse(opts.body).content), replayed: true, reload_required: true, latest_version: 3 });
    } return normal();
  } }); await replay.mount(); await replay.open(); await replay.edit('achievements', '本地尝试成果'); await replay.click('save'); await replay.edit('achievements', '重试前的新正文'); await replay.click('retry');
  check(replay.field('achievements').value === '重试前的新正文' && replay.modal().querySelector('#summary-conflict').textContent.includes('其他新版本成果') && replay.button('save').disabled, 'Stale replay reads current head without replacing local body or silently attaching latest_version');
  equal(replay.reads().length, 3, 'reload_required triggers a fresh current-head read');

  const pendingSave = deferred(); const saving = harness({ fetchOverride: (url, opts, normal, response) => url.endsWith('/training-summaries/save') ? pendingSave.promise.then(response) : normal() }); await saving.mount(); await saving.open(); await saving.edit('issues', '慢保存');
  const submit = saving.click('save'); await tick(); saving.sandbox.closeModal(); check(saving.modal() && saving.field('issues').disabled, 'In-flight save locks close and editing until its outcome');
  saving.sandbox.beginRouteEpoch(); saving.sandbox.state.page = 'projects'; pendingSave.resolve(view(2)); await submit; check(!saving.modal() && saving.requests('save')[0].opts.signal.aborted, 'Forced lifecycle cleanup aborts and ignores late save');
  const pendingRead = deferred(); let reads = 0; const switched = harness({ fetchOverride: (url, opts, normal, response) => url.startsWith('/api/training-summaries?') && ++reads === 2 ? pendingRead.promise.then(response) : normal() }); await switched.mount(); const opening = switched.open(); await tick(); switched.sandbox.state.user = { uid: 99, role: 'admin' }; pendingRead.resolve(view()); await opening;
  check(!switched.modal() && !switched.panel().textContent, 'Identity change clears an existing summary surface instead of accepting a late prior-account read');

  const delivered = harness({ initial: view(1, content(), true, deliverySources({ ...deliveryFacts(), excluded_count: 2, rejected_count: 1 })) });
  await delivered.mount(); await delivered.open();
  equal(deliveryRows(delivered.modal()), [['预计', '8.50', '有效排课'], ['计划', '6.25', '有效排课'], ['实际', '1.33', '已完成且核对的授课'], ['计酬', '1.25', '已完成且核对的授课']], 'Original summary renders four separate exact decimal sources and their distinct coverage');
  const deliveredText = delivered.modal().querySelector('[data-summary-delivery]').textContent;
  check(deliveredText.includes('有效排课 3 条') && deliveredText.includes('已完成且核对 1 条') && deliveredText.includes('未完成 2 条') && deliveredText.includes('已完成待核实 0 条'), 'Delivery record counts use their own server fields including genuine zero');
  check(deliveredText.includes('已拒绝 1 条（不计入）'), 'Rejected count is labelled as excluded rather than added to effective records');
  check(deliveredText.includes('另有 2 条来源待核实') && deliveredText.includes('45 分钟为 1 基本课时') && deliveredText.includes('计酬课时不代表已确认课酬或已支付'), 'Source explanation preserves optional exclusions, unit and non-payment boundary');
  check(!delivered.modal().querySelector('[data-summary-delivery]').querySelector('input, textarea, button'), 'Imported delivery facts are read-only inside original editor');

  const mixed = harness({ initial: view(1, content(), true, deliverySources({ ...deliveryFacts(), estimated: '0', planned: '0.00', actual: null, payable: null })) });
  await mixed.mount(); await mixed.open();
  equal(deliveryRows(mixed.modal()).map(row => row[1]), ['0', '0.00', '待补齐', '待补齐'], 'Zero decimal strings are rendered as zero while null actual/payable remain unknown');
  const noDispatch = harness({ initial: view(0, empty(), true, deliverySources({ dispatch_count: 0, reviewed_completed_count: 0, pending_count: 0, unverified_completed_count: 0, estimated: '0', planned: '0', actual: '0', payable: '0' }, { reason: 'NO_DISPATCHES' })) });
  await noDispatch.mount(); await noDispatch.open();
  equal(deliveryRows(noDispatch.modal()).map(row => row[1]), ['0', '0', '0', '0'], 'Real available empty-source zeros are not converted into unavailable history');
  check(noDispatch.modal().querySelector('#summary-sources').textContent.includes('首次保存时冻结'), 'Available delivery preview in a new summary is not labelled as already frozen');
  check(noDispatch.modal().querySelector('[data-summary-delivery]').textContent.includes('该来源版本尚无排课记录'), 'Only explicit NO_DISPATCHES source is described as having no records');
  const rejectedOnly = harness({ initial: view(1, content(), true, deliverySources({ dispatch_count: 0, reviewed_completed_count: 0, pending_count: 0, unverified_completed_count: 0, rejected_count: 2, total_dispatch_count: 2, estimated: '0', planned: '0', actual: '0', payable: '0' })) });
  await rejectedOnly.mount(); await rejectedOnly.open();
  const rejectedText = rejectedOnly.modal().querySelector('[data-summary-delivery]').textContent;
  check(rejectedText.includes('已拒绝 2 条（不计入）') && !rejectedText.includes('尚无排课记录'), 'All-rejected source with zero effective counts is not presented as no dispatch history');

  const malicious = '<img src=x onerror="globalThis.summaryInjected=true"><script>globalThis.summaryInjected=true</script>';
  const unsafe = harness({ initial: view(1, content(), true, deliverySources({ dispatch_count: malicious, reviewed_completed_count: -1, pending_count: 1.5, unverified_completed_count: '0', estimated: malicious, planned: 0, actual: '1e3', payable: '-1', excluded_count: malicious })) });
  await unsafe.mount(); await unsafe.open();
  equal(deliveryRows(unsafe.modal()).map(row => row[1]), ['待补齐', '待补齐', '待补齐', '待补齐'], 'Untrusted markup, JSON number, exponent and negative values never masquerade as decimal strings');
  const unsafeNode = unsafe.modal().querySelector('[data-summary-delivery]');
  check(!unsafeNode.querySelector('script, img') && !unsafeNode.textContent.includes('summaryInjected') && unsafe.sandbox.summaryInjected === undefined, 'Malicious source strings cannot create executable nodes or execute script');
  equal((unsafeNode.textContent.match(/待核实 条/g) || []).length, 4, 'Invalid or typed-as-string counts are unknown, not coerced to valid counts');
  check(!unsafeNode.textContent.includes('另有'), 'Malformed optional exclusion count does not render as trusted source explanation');

  for (const reason of ['ACCESS_NOT_GRANTED', 'DELIVERY_READ_NOT_GRANTED', 'DELIVERY_READ_REQUIRED', 'ACCESS_REDACTED']) {
    const hiddenSource = { ...sources(), visibility: 'DELIVERY_HIDDEN', delivery: { status: 'UNAVAILABLE', reason, value: { ...deliveryFacts(), actual: '987654.32' } } };
    const hidden = harness({ initial: view(1, content(), true, hiddenSource) }); await hidden.mount(); await hidden.open();
    const node = hidden.modal().querySelector('[data-summary-delivery]');
    check(node.textContent.includes('当前账号无权查看授课来源') && !node.textContent.includes('尚未带入'), reason + ': permission hiding is distinct from disconnected historical data');
    check(!node.querySelector('table') && !hidden.modal().textContent.includes('987654.32'), reason + ': unavailable value is ignored even if a defective response includes facts');
    check(hidden.button('save') && !hidden.field('issues').disabled, reason + ': summary edit capability remains independent from delivery visibility');
  }
  for (const delivery of [undefined, { status: 'UNAVAILABLE', reason: 'M05_SNAPSHOT_NOT_CONNECTED', value: null }]) {
    const oldSources = sources(); if (delivery === undefined) delete oldSources.delivery; else oldSources.delivery = delivery;
    const historical = harness({ initial: view(1, content(), true, deliverySources()), fetchOverride: (url, opts, normal, respond) => url.startsWith('/api/training-summaries/revision?') ? respond(revision(23, content(), oldSources)) : normal() });
    await historical.mount(); await historical.open(); await historical.click('show-history');
    const b = historical.bindings.at(-1); await b.actions[0].onClick(b.rows[0]); await tick();
    const historySource = historical.modal().querySelector('#summary-revision');
    check(historySource.textContent.includes('本版总结尚未带入授课来源') && !historySource.querySelector('[data-summary-delivery]').querySelector('table'), 'Historical disconnected or absent delivery remains explicit and unrecomputed');
    check(!historySource.textContent.includes('1.33') && historical.modal().querySelector('#summary-sources').textContent.includes('1.33'), 'Reading old history does not borrow current source facts or replace the editor source');
  }

  const frozenSource = deliverySources(), refreshedSource = deliverySources({ ...deliveryFacts(), actual: '2.67', payable: '2.50' });
  refreshedSource.source_version = 'c'.repeat(64);
  const frozen = harness({ initial: { ...view(1, content(), true, frozenSource), source_changed: true }, fetchOverride: (url, opts, normal, respond, remote) => {
    if (url.endsWith('/training-summaries/refresh')) { remote.set(view(remote.get().version + 1, remote.get().current.content, true, refreshedSource)); return respond(remote.get()); }
    if (url.startsWith('/api/training-summaries/revision?')) return respond(revision(1, content(), frozenSource));
    return normal();
  } }); await frozen.mount(); await frozen.open();
  check(frozen.modal().querySelector('#summary-notice').textContent.includes('来源已变化') && deliveryRows(frozen.modal())[2][1] === '1.33', 'Source-changed warning retains saved frozen facts until explicit refresh');
  const frozenMask = frozen.modal(); await frozen.edit('issues', '正文保存不刷新课时'); await frozen.click('save');
  equal(deliveryRows(frozen.modal()).map(row => row[1]), ['8.50', '6.25', '1.33', '1.25'], 'Ordinary body save continues displaying the original frozen delivery snapshot');
  equal(frozen.requests('refresh').length, 0, 'Saving body never implicitly sends source refresh');
  await frozen.click('refresh');
  equal(deliveryRows(frozen.modal()).map(row => row[1]), ['8.50', '6.25', '2.67', '2.50'], 'Explicit refresh adopts newly returned four-type source facts');
  check(frozen.modal() === frozenMask && frozen.field('issues').value === '正文保存不刷新课时' && frozen.renders === 0, 'Refresh updates sources in the same editor without rebuilding page or losing saved text');
  equal(Object.keys(JSON.parse(frozen.requests('refresh')[0].opts.body)).sort(), ['expected_version', 'project_id', 'request_id'], 'Delivery source refresh cannot submit client facts or a copied frozen source');
  await frozen.click('show-history'); const frozenHistory = frozen.bindings.at(-1); await frozenHistory.actions[0].onClick({ revision: 1 }); await tick();
  equal(deliveryRows(frozen.modal().querySelector('#summary-revision')).map(row => row[1]), ['8.50', '6.25', '1.33', '1.25'], 'Prior revision still renders its own frozen delivery values after current source refresh');
  console.log(JSON.stringify({ ok: true, suite: 'original summary draft host integration', checks, transport: 'synthetic', browser: false, database: false }));
})().catch(error => { console.error(error); process.exitCode = 1; });
