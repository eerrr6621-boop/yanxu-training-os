'use strict';
// Exercise original app.js handlers with synthetic DOM/API boundaries. No business network or database.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '../web/app.js'), 'utf8');
const between = (start, end) => { const from = source.indexOf(start); assert.ok(from >= 0); const to = source.indexOf(end, from); assert.ok(to > from); return source.slice(from, to); };
const block = between('  // ============ 原需求页面：提交、审批与团队受理', '  async function editForm(');
const collector = between('  function collectForm(', '  // ============ 表格');
const crud = between('  const CRUD = {', '  async function fillOptions(');
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
let checks = 0;
function check(value, label) { assert.ok(value, label); checks++; }
function equal(actual, expected, label) { assert.equal(actual, expected, label); checks++; }
function harness({ workflow = null, actor = 'submitter', create = true, failAction = false } = {}) {
  let modal, pendingForms = [], counter = 0, currentWorkflow = workflow, failure = failAction;
  const calls = [], messages = [], cleanups = [], renderedFields = [], renderedTables = [];
  const contextPayload = { person: { person_code: actor, organization_code: 'branch' }, can_create: create,
    organizations: [{ code: 'branch', label: '分公司', can_write: true, can_accept: true }, { code: 'other-source', label: '另一个需求来源', can_accept: true }],
    people: [{ code: 'submitter', label: '填报人', organization_code: 'branch' }, { code: 'leader', label: '负责人', organization_code: 'branch' }, { code: 'bp', label: 'BP', organization_code: 'branch' }], teams: [{ code: 'training', label: '培训团队' }] };
  function makeNode(value = '') {
    const item = { hidden: false, style: {}, error: { textContent: '' } };
    return { value: String(value ?? ''), disabled: false, isConnected: true, dataset: {}, attributes: {}, innerHTML: '', textContent: '',
      closest() { return item; }, removeAttribute(key) { delete this.attributes[key]; }, setAttribute(key, value) { this.attributes[key] = value; }, focus() {}, checkValidity() { return true; } };
  }
  const sandbox = { crypto: { randomUUID: () => 'request-' + ++counter }, AbortController, console, routeEpoch: 1,
    state: { user: { role: 'viewer', name: '测试账号' } }, REGION_PROVINCES: ['浙江'], esc: escapeHtml, num: String, money: String, tag: escapeHtml,
    isRouteCurrent: (epoch) => epoch === sandbox.routeEpoch, addRouteCleanup: (fn) => cleanups.push(fn),
    toast: (message) => messages.push(message), refreshIcons() {}, renderPage: () => { sandbox.renders++; }, renders: 0,
    $: (selector, root = modal) => selector === '#modal-mask' ? modal : selector === '.field-error' ? root?.error : root?.nodes?.get(selector),
    $$: (selector, root = modal) => selector === '[data-workflow-command]' ? [] : [...(root?.nodes?.values() || [])],
    renderForm(fields, data = {}) { pendingForms.push({ fields, data }); renderedFields.push(...fields); return '<div class="form-grid">test form</div>'; },
    renderTable(columns, rows) {
      const html = '<table>' + rows.map((row) => '<tr>' + columns.map((column) => '<td>' + (column.render ? column.render(row) : escapeHtml(row[column.k])) + '</td>').join('') + '</tr>').join('') + '</table>';
      renderedTables.push({ columns, rows, html }); return html;
    },
    openModal(title, body, options) {
      if (modal) { modal.isConnected = false; modal.options.onClose?.(); }
      const nodes = new Map();
      modal = { title, body, options, nodes, isConnected: true, dataset: {}, fields: pendingForms.flatMap((form) => form.fields) };
      const thisModal = modal;
      pendingForms.forEach(({ fields, data }) => fields.forEach((field) => nodes.set(`[data-k="${field.k}"]`, makeNode(data[field.k] ?? field.value ?? '')))); pendingForms = [];
      for (const id of ['workflow-save-notice', 'workflow-route-hint', 'workflow-action-notice']) nodes.set('#' + id, makeNode());
      nodes.set('.modal-foot', { insertAdjacentHTML() { nodes.set('#workflow-save-submit', makeNode()); } });
      return thisModal;
    },
    closeModal() { if (modal?.dataset.locked === 'true') return; modal.isConnected = false; modal.options.onClose?.(); },
    api: async (url, opts = {}) => {
      calls.push({ url, ...opts });
      if (opts.signal?.aborted) throw Object.assign(new Error('aborted'), { name: 'AbortError' });
      if (url === '/workflow/context') return contextPayload;
      if (url.startsWith('/demands/workflow')) return { workflow: currentWorkflow };
      if (url === '/demands/draft') {
        const data = opts.body;
        currentWorkflow = { ...(currentWorkflow || {}), id: data.id || 7, version: (currentWorkflow?.version || 0) + 1, draft: !currentWorkflow?.approval,
          business_path: data.business_path, filler_code: actor, organization_code: data.organization_code,
          form: { ...data, duration_minutes: data.duration_minutes || (data.hours === '1.500' ? '67.500' : '') },
          legacy: { unit: data.unit, original_hours: data.hours }, actions: { edit: true, submit: true } };
        return { workflow: currentWorkflow };
      }
      if (url === '/organization/me') return { status: 'BOUND', version: 'one', person: { personCode: actor, organizationCode: 'branch' } };
      if (failure === 'network-once' && url.includes('/actions')) {
        failure = false;
        throw new Error('连接中断，处理结果待核对');
      }
      if (failure && url.includes('/actions')) {
        currentWorkflow = { ...currentWorkflow, version: currentWorkflow.version + 1, approval: { ...currentWorkflow.approval, version: currentWorkflow.approval.version + 1 } };
        throw Object.assign(new Error('流程已更新'), { status: 409, code: 409, data: { approvalCode: 'STALE_VERSION' } });
      }
      return { workflow: currentWorkflow };
    },
  };
  vm.createContext(sandbox); vm.runInContext(crud + collector + block, sandbox);
  return { sandbox, calls, messages, cleanups, renderedFields, renderedTables, get modal() { return modal; }, contextPayload,
    evaluate: (code) => vm.runInContext(code, sandbox), set(key, value) { modal.nodes.get(`[data-k="${key}"]`).value = value; },
    setWorkflow(value) { currentWorkflow = value; },
  };
}
function fixture(overrides = {}) {
  return { id: 9, version: 2, data_revision: 1, draft: false, business_path: 'direct', organization_code: 'branch', filler_code: 'submitter',
    form: { title: '培训<script>', duration_minutes: '60', internal_contact_code: 'leader' }, legacy: { unit: '分公司', content: '<img src=x>', teacher_req: '老师', contact: '客户' },
    status_label: '待负责人审批', actions: { edit: false, submit: false, accept: false, bid_result: false },
    approval: { id: 11, businessId: 9, title: '培训', version: 1, stage: 'LEADER', status: 'LEADER_PENDING', participants: { submitterId: 'submitter', leaderId: 'leader', bpId: 'bp' },
      actions: [{ action: 'APPROVE', label: '通过', enabled: true }, { action: 'RETURN', label: '退回', enabled: true }],
      history: [{ at: '2026-09-22T00:00:00Z', action: 'SUBMIT', stage: 'NONE', actorId: 'submitter', comment: '<script>bad</script>' }] }, ...overrides };
}
function combinedFixture(approvalOverrides = {}) {
  const original = fixture();
  return fixture({ status_label: '', approval: { ...original.approval, reviewMode: 'SINGLE_EXPLICIT',
    participants: { submitterId: 'submitter', leaderId: 'combined', bpId: 'combined' },
    actions: [{ action: 'APPROVE_COMBINED', label: '通过', enabled: true }, { action: 'RETURN', label: '退回', enabled: true }], ...approvalOverrides } });
}
function demandActions(h, workflow) {
  if (!h.sandbox.buildActions) {
    h.sandbox.canWrite = () => false;
    vm.runInContext(between('  function buildActions(', '  const PROJECT_MODULE_NAMES'), h.sandbox);
  }
  return h.sandbox.buildActions({ mod: 'demands' }).filter((action) => !action.show || action.show({ id: workflow.id, workflow }));
}
(async () => {
  const h = harness();
  equal(h.sandbox.workflowBasicHours('60'), '1.33', '60 minutes rounds to 1.33 basic hours');
  equal(h.sandbox.workflowBasicHours('67.500'), '1.50', 'Exact half units retain two decimal places');
  equal(h.sandbox.workflowBasicHours('0.225'), '0.01', 'HALF_UP rounds decimal midpoint away from zero');
  equal(h.sandbox.workflowDecimal('12345678901234567890.123', 45n), '555555550555555555055.535', 'Duration never crosses floating point');
  for (const value of ['NaN', 'Infinity', '1e3', '-1', '.5', '1.', '0'.repeat(121)]) {
    assert.throws(() => h.sandbox.workflowDecimal(value)); checks++;
  }
  const payload = h.sandbox.workflowDraftPayload({ title: '名称', unit: '客户单位', contact: '客户', phone: '电话', teacher_req: '师资', remark: '原备注', content: '原内容', business_path: 'direct', duration_value: '1.500', duration_unit: 'hours', participant_count: '12345678901234567890', status: '已立项', filler_code: 'forged', external_approval_ref: '不应直承接带入' }, null, 'draft-1');
  equal(payload.hours, '1.500', 'Original hours input preserved verbatim');
  check(!('duration_minutes' in payload), 'Only one authoritative duration is submitted');
  equal(payload.participant_count, '12345678901234567890', 'Integer text keeps precision');
  check(!('status' in payload) && !('filler_code' in payload) && payload.external_approval_ref === '', 'Draft whitelist excludes controlled fields and direct-route bid reference');
  equal(h.sandbox.workflowDraftPayload({ duration_value: '', duration_unit: 'hours' }, null, 'empty').duration_minutes, '', 'Blank draft duration stays optional');
  equal(payload.remark, '原备注', 'Legacy notes survive M02 adaptation');
  equal(payload.teacher_req, '师资', 'Teacher requirements remain separate');

  await h.sandbox.editWorkflowDemand(null, () => { h.sandbox.renders++; });
  check(h.modal.fields.filter((field) => field.k !== 'filler_display').every((field) => !field.required), 'New incomplete draft can save');
  equal(h.modal.nodes.get('[data-k="external_approval_ref"]').closest().style.display, 'none', 'Direct path hides external signature despite original flex styling');
  h.set('business_path', 'bid'); h.modal.nodes.get('[data-k="business_path"]').onchange();
  equal(h.modal.nodes.get('[data-k="external_approval_ref"]').closest().style.display, '', 'Bid path restores original field layout');
  h.set('business_path', 'direct');
  h.set('title', '草稿'); h.set('duration_value', '1.500'); h.set('duration_unit', 'hours');
  await h.modal.nodes.get('#workflow-save-submit').onclick();
  equal(h.calls.filter((call) => call.body).map((call) => call.url).join(','), '/demands/draft,/demands/submit', 'Save and submit uses separate dedicated endpoints');
  const saved = h.calls.find((call) => call.url === '/demands/draft').body;
  equal(saved.hours, '1.500', 'Handler preserves hours string through API');
  equal(h.calls.find((call) => call.url === '/demands/submit').body.expected_version, 1, 'Submit uses saved current version');
  check(h.modal.isConnected === false, 'Successful submit closes original modal');

  const denied = harness({ create: false });
  await denied.sandbox.editWorkflowDemand(null);
  check(!denied.modal && denied.messages.length > 0, 'Unbound/unpermitted creation produces real configuration guidance');
  check(!denied.calls.some((call) => call.body), 'No synthetic or unauthorized draft is written');

  const flow = fixture();
  const compact = h.sandbox.workflowDetailHtml(flow, h.contextPayload, '<button data-workflow-command="APPROVE">通过</button>');
  check(compact.indexOf('data-workflow-command') < compact.indexOf('workflow-info'), 'Available commands appear before the material grid');
  check(compact.includes('demand-brief-grid workflow-info') && compact.includes('class="workflow-long"'), 'Details reuse the brief grid with full-width narrative fields');
  const instant = '2026-09-22T01:18:00Z';
  equal(h.sandbox.workflowLocalTime(instant), new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(instant)), 'Approval history uses the device timezone');
  equal(h.sandbox.workflowLocalTime('invalid'), '—', 'Bad timestamps do not display invented times');
  const viewer = harness({ workflow: flow, actor: 'leader', create: false });
  await viewer.sandbox.showDemandWorkflow({ id: 9 }, 'APPROVE');
  equal(viewer.modal.title, '通过', 'Viewer with server permission can open approval');
  check(viewer.modal.body.includes('&lt;script&gt;') && !viewer.modal.body.includes('<script>'), 'Untrusted title/history are escaped');
  check(viewer.modal.body.includes('主要培训内容') && viewer.modal.body.includes('客户联系人'), 'Approval sees complete original demand material');
  viewer.set('comment', '同意'); await viewer.modal.options.onOk();
  const approve = viewer.calls.find((call) => call.url.endsWith('/actions'));
  equal(approve.body.expectedVersion, 1, 'Approval carries current workflow version');
  equal(approve.body.expectedStage, 'LEADER', 'Approval carries current stage');
  equal(approve.body.action, 'APPROVE', 'Explicit decision sent');
  check(!('actorId' in approve.body) && !('dataRevision' in approve.body), 'No client-supplied approval identity/content revision');
  equal(approve.url, '/approvals/tasks/11/actions', 'Host API path avoids duplicate /api prefix');

  const combinedLabel = '同时完成负责人和BP审批';
  const combinedFlow = combinedFixture();
  const combined = harness({ workflow: combinedFlow, actor: 'combined', create: false });
  combined.contextPayload.people.push({ code: 'combined', label: '兼任测试人<script>', organization_code: 'branch' });
  const combinedCommands = combined.sandbox.workflowCommands(combinedFlow);
  equal(combinedCommands.filter((command) => command.action === 'APPROVE_COMBINED').length, 1, 'Explicit combined permission exposes one combined command');
  equal(combinedCommands.find((command) => command.action === 'APPROVE_COMBINED').label, combinedLabel, 'Combined command never inherits an ambiguous generic server label');
  check(!combinedCommands.some((command) => command.action === 'APPROVE'), 'Combined approval does not invent a separate ordinary approval');
  await combined.sandbox.showDemandWorkflow({ id: 9 });
  equal(combined.modal.body.match(/<button[^>]*data-workflow-command="APPROVE_COMBINED"[^>]*>([^<]*)<\/button>/)?.[1], combinedLabel, 'Demand detail button names both responsibilities explicitly');
  check(combined.modal.body.includes('待负责人及BP审批（兼任）'), 'Explicit review mode gives the combined pending state its own label');
  const combinedDemandActions = demandActions(combined, combinedFlow);
  equal(combinedDemandActions.filter((action) => action.l === combinedLabel).length, 1, 'Viewer sees one authorized combined demand action');
  check(!combinedDemandActions.some((action) => action.l === '办理审批'), 'Combined demand row avoids an ambiguous ordinary approval entry');
  await combinedDemandActions.find((action) => action.l === combinedLabel).onClick({ id: 9, workflow: combinedFlow });
  equal(combined.modal.title, combinedLabel, 'Combined demand action opens the explicitly named confirmation');
  equal(combined.modal.options.okText, combinedLabel, 'Combined confirmation button explicitly confirms both responsibilities');
  check(/一次[^<。]*(?:确认|完成)[^<。]*(?:两项|负责人和BP|负责人及BP|负责人、BP)/.test(combined.modal.body), 'Combined confirmation explains that one operation confirms both responsibilities');
  combined.set('comment', '已核对两项职责');
  await combined.modal.options.onOk();
  const combinedWrites = combined.calls.filter((call) => call.url.endsWith('/actions'));
  equal(combinedWrites.length, 1, 'One combined confirmation produces one approval request');
  equal(combinedWrites[0].body.action, 'APPROVE_COMBINED', 'Combined confirmation posts the exact combined action');
  equal(combinedWrites[0].body.expectedVersion, 1, 'Combined confirmation retains the current expected version');
  equal(combinedWrites[0].body.expectedStage, 'LEADER', 'Combined approval retains its actual leader stage');
  equal(combinedWrites[0].body.comment, '已核对两项职责', 'Combined confirmation retains the real actor comment');
  equal(Object.keys(combinedWrites[0].body).sort().join(','), 'action,comment,expectedStage,expectedVersion,requestId', 'Combined request does not claim identity, mode, assignment, or extra responsibilities');
  for (const [label, demands, approvals] of [
    ['demand source', [{ id: 9, title: '需求', workflow: combinedFlow }], []],
    ['approval source', [], [combinedFlow.approval]],
    ['merged sources', [{ id: 9, title: '需求', workflow: combinedFlow }], [combinedFlow.approval]],
  ]) {
    const tasks = combined.sandbox.workflowQueueTasks(demands, approvals);
    equal(tasks.length, 1, `Combined today task is present once from ${label}`);
    equal(tasks[0].action, combinedLabel, `Combined today action names both responsibilities from ${label}`);
    equal(tasks[0].label, '待负责人及BP审批（兼任）', `Combined today status retains explicit mode from ${label}`);
    check(tasks[0].workflowAuthorized && tasks[0].focusId === 9, `Combined today task targets the authorized demand from ${label}`);
  }

  const completedCombined = combinedFixture({ status: 'READY_FOR_TEAM', stage: 'NONE', actions: [], history: [
    { at: instant, action: 'APPROVE_COMBINED', stage: 'LEADER', actorId: 'combined', responsibilities: ['LEADER', 'BP'], comment: '一次明确确认' },
  ] });
  combined.sandbox.workflowDetailHtml(completedCombined, combined.contextPayload);
  const combinedHistory = combined.renderedTables.at(-1);
  equal(combinedHistory.rows.length, 1, 'Combined history retains one real event');
  equal((combinedHistory.html.match(/<tr>/g) || []).length, 1, 'Combined history renders one event row');
  equal((combinedHistory.html.match(/兼任测试人&lt;script&gt;/g) || []).length, 1, 'Combined history renders the single escaped real actor once');
  check(combinedHistory.html.includes('负责人、BP'), 'Single combined history row displays both responsibilities');
  check(combinedHistory.html.includes(combinedLabel) && !combinedHistory.html.includes('<script>'), 'Combined history uses the explicit action label and escapes the actor');

  for (const [label, unavailableFlow] of [
    ['mode without action', combinedFixture({ actions: [] })],
    ['disabled action', combinedFixture({ actions: [{ action: 'APPROVE_COMBINED', label: combinedLabel, enabled: false }] })],
    ['same IDs without explicit mode', combinedFixture({ reviewMode: 'SEQUENTIAL', actions: [] })],
  ]) {
    const unavailable = harness({ workflow: unavailableFlow, actor: 'combined', create: false });
    check(!unavailable.sandbox.workflowCommands(unavailableFlow).some((command) => command.action === 'APPROVE_COMBINED'), `No combined command is inferred from ${label}`);
    check(!demandActions(unavailable, unavailableFlow).some((action) => action.l === combinedLabel), `No combined demand action is inferred from ${label}`);
    equal(unavailable.sandbox.workflowQueueTasks([{ id: 9, workflow: unavailableFlow }], [unavailableFlow.approval]).length, 0, `No actionable today task is inferred from ${label}`);
    await unavailable.sandbox.showDemandWorkflow({ id: 9 }, 'APPROVE_COMBINED');
    check(!unavailable.modal.options.onOk && !unavailable.modal.body.includes('data-workflow-command="APPROVE_COMBINED"'), `Direct combined command falls back to detail when ${label}`);
    check(!unavailable.calls.some((call) => call.body), `Unavailable combined action makes no write for ${label}`);
  }
  const legacySamePersonFlow = combinedFixture({ reviewMode: 'SEQUENTIAL', actions: [{ action: 'APPROVE', label: '通过', enabled: true }], history: [
    { at: instant, action: 'APPROVE', stage: 'LEADER', actorId: 'combined', comment: '旧流程负责人审批' },
  ] });
  const legacySamePerson = harness({ workflow: legacySamePersonFlow, actor: 'combined', create: false });
  equal(legacySamePerson.sandbox.workflowCommands(legacySamePersonFlow).map((command) => command.action).join(','), 'APPROVE', 'Same participant IDs preserve an explicitly sequential action');
  check(demandActions(legacySamePerson, legacySamePersonFlow).some((action) => action.l === '办理审批'), 'Same participant IDs preserve the original demand action');
  equal(legacySamePerson.sandbox.workflowQueueTasks([], [legacySamePersonFlow.approval])[0].action, '办理审批', 'Same participant IDs preserve the original today action');
  legacySamePerson.sandbox.workflowDetailHtml(legacySamePersonFlow, legacySamePerson.contextPayload);
  check(!legacySamePerson.renderedTables.at(-1).html.includes('负责人、BP'), 'Legacy approval history is never upgraded to combined evidence by participant equality');

  const combinedRetry = harness({ workflow: combinedFixture(), failAction: 'network-once' });
  const pausedFlow = combinedFixture({ actions: [], paused: true, blocked_reason: '当前兼任职责变化 <核对>' });
  const pausedHtml = combinedRetry.sandbox.workflowDetailHtml(pausedFlow, combinedRetry.contextPayload);
  check(pausedHtml.includes('当前兼任职责变化 &lt;核对&gt;'), 'Paused combined workflow remains readable with escaped actionable reason');
  check(!combinedRetry.sandbox.workflowCommands(pausedFlow).some(command => command.action === 'APPROVE_COMBINED'), 'Paused workflow without server-enabled command cannot be approved');
  await combinedRetry.sandbox.showDemandWorkflow({ id: 9 }, 'APPROVE_COMBINED');
  equal(await combinedRetry.modal.options.onOk(), false, 'Uncertain combined write keeps the confirmation available for readback');
  equal(combinedRetry.calls.filter((call) => call.url.startsWith('/demands/workflow')).length, 2, 'Uncertain combined write reads back the authoritative state');
  await combinedRetry.modal.options.onOk();
  const retriedCombinedWrites = combinedRetry.calls.filter((call) => call.url.endsWith('/actions'));
  equal(retriedCombinedWrites.length, 2, 'Unchanged combined state allows one deliberate retry');
  equal(retriedCombinedWrites[0].body.requestId, retriedCombinedWrites[1].body.requestId, 'Combined timeout retry reuses the original idempotency key');
  check(retriedCombinedWrites.every((call) => call.body.action === 'APPROVE_COMBINED' && call.body.expectedVersion === 1), 'Combined retry preserves the original action and expected version');
  const combinedConflict = harness({ workflow: combinedFixture(), failAction: true });
  await combinedConflict.sandbox.showDemandWorkflow({ id: 9 }, 'APPROVE_COMBINED');
  await combinedConflict.modal.options.onOk();
  await combinedConflict.modal.options.onOk();
  equal(combinedConflict.calls.filter((call) => call.url.endsWith('/actions')).length, 1, 'Changed combined version blocks repost after conflict readback');

  const returned = harness({ workflow: flow });
  await returned.sandbox.showDemandWorkflow({ id: 9 }, 'RETURN');
  equal(await returned.modal.options.onOk(), false, 'Return requires reason');
  check(!returned.calls.some((call) => call.body), 'Missing return reason makes no write');
  returned.set('comment', '请补培训目标'); await returned.modal.options.onOk();
  equal(returned.calls.find((call) => call.body).body.comment, '请补培训目标', 'Return reason retained');

  const conflict = harness({ workflow: fixture(), failAction: true });
  await conflict.sandbox.showDemandWorkflow({ id: 9 }, 'APPROVE');
  await conflict.modal.options.onOk();
  check(conflict.calls.filter((call) => call.url.startsWith('/demands/workflow')).length === 2, '409 reads latest server state');
  await conflict.modal.options.onOk();
  equal(conflict.calls.filter((call) => call.url.endsWith('/actions')).length, 1, 'Old version cannot be resubmitted after readback');
  check(conflict.modal.nodes.get('#workflow-action-notice').textContent.includes('流程已更新'), 'Conflict keeps reviewable refresh guidance');

  const accept = harness({ workflow: fixture({ approval: null, actions: { accept: true } }) });
  await accept.sandbox.showDemandWorkflow({ id: 9 }, 'ACCEPT');
  const teamField = accept.modal.fields.find((field) => field.k === 'team_code');
  equal(teamField.options.filter((item) => item.v).map((item) => item.v).join(','), 'training', 'Team options use explicit teams, not readable demand organizations');
  accept.set('team_code', 'training'); await accept.modal.options.onOk();
  equal(accept.calls.find((call) => call.url === '/demands/accept').body.team_code, 'training', 'Authorized team sent to acceptance action');

  const bid = harness({ workflow: fixture({ business_path: 'bid', approval: null, actions: { bid_result: true } }) });
  await bid.sandbox.showDemandWorkflow({ id: 9 }, 'BID_RESULT');
  bid.set('result', 'won'); bid.set('external_approval_ref', '签报-1'); bid.set('result_date', '2026-09-22');
  await bid.modal.options.onOk();
  check(bid.calls.some((call) => call.url === '/demands/bid-result') && !bid.calls.some((call) => call.url === '/bids/win'), 'Won result does not call old auto-project endpoint');

  const reminder = harness({ workflow: fixture({ approval: { ...flow.approval, remind: { enabled: true } } }) });
  await reminder.sandbox.showDemandWorkflow({ id: 9 }, 'REMIND');
  await reminder.modal.options.onOk();
  equal(Object.keys(reminder.calls.find((call) => call.url.endsWith('/remind')).body).sort().join(','), 'expectedVersion,requestId', 'Reminder obeys its narrow request whitelist');

  const edit = harness({ workflow: fixture({ actions: { edit: true } }) });
  await edit.sandbox.editWorkflowDemand({ id: 9 });
  check(edit.modal.fields.some((field) => field.k === 'change_comment' && field.required), 'Pending revision needs change reason');
  check(!edit.modal.nodes.has('#workflow-save-submit'), 'Already submitted records do not use initial submit');
  edit.set('change_comment', '更新培训目标'); await edit.modal.options.onOk();
  equal(edit.calls.find((call) => call.url === '/demands/draft').body.change_comment, '更新培训目标', 'Revision reason reaches backend gate');

  const queue = viewer.sandbox.workflowQueueTasks([{ id: 9, title: '需求', unit: '单位', workflow: flow }], [flow.approval]);
  equal(queue.length, 1, 'Demand and approval todo merge by business ID');
  equal(queue[0].focusId, 9, 'Deep link focuses demand ID, not workflow ID');
  check(queue[0].workflowAuthorized && queue[0].action === '办理审批', 'Viewer approval task is not downgraded by legacy role');
  equal(viewer.sandbox.workflowQueueTasks([{ id: 9, workflow: fixture({ actions: {}, approval: { actions: [] } }) }], []).length, 0, 'Unauthorized records do not create actionable todos');
  check(h.sandbox.organizationBindingHtml({ status: 'BOUND', person: { personCode: 'person-one', organizationCode: 'branch' } }).includes('person-one'), 'M01 camelCase identity binding is understood');

  equal(h.sandbox.workflowBindingNotice({ status: 'BOUND' }), '', 'Bound identity does not add an irrelevant empty-state warning');
  check(h.sandbox.workflowBindingNotice({ status: 'NOT_BOUND' }).includes('无法显示'), 'Unbound identity is distinguished from genuinely empty work');
  check(h.sandbox.workflowBindingNotice({ status: 'NOT_CONFIGURED' }).includes('尚未配置'), 'Missing organization configuration is explained');
  check(h.sandbox.workflowBindingNotice({ _error: 'network' }).includes('无法核实'), 'Identity fetch failure is not treated as no pending work');

  check(h.evaluate("workflowContactEligible({ organization_code: '', responsible_organization_codes: ['branch'] }, 'branch')"), 'Cross-organization responsible contact remains selectable without exposing home organization');
  check(!h.evaluate("workflowContactEligible({ organization_code: 'other', responsible_organization_codes: [] }, 'branch')"), 'Unrelated people remain outside the selected organization');
  equal(h.sandbox.workflowProjectSource({ workflow_source: { business_path: 'direct' } }), '直接承接', 'Managed direct project preserves its source label');
  equal(h.sandbox.workflowProjectSource({ workflow_source: { business_path: 'bid' }, bid_id: null }), '投标中标后承接', 'Managed bid source does not become independent project when legacy bid ID is absent');
  h.sandbox.canWrite = () => true; h.sandbox.showProjectOverview = () => {}; h.sandbox.showProjectStart = () => {};
  vm.runInContext(between('  function buildActions(', '  const PROJECT_MODULE_NAMES'), h.sandbox);
  const projectDelete = h.sandbox.buildActions({ mod: 'projects' }).find((action) => action.l === '删除');
  check(!projectDelete.show({ id: 1, status: '待启动', workflow_source: { can_delete: false } }), 'Original project delete control respects source retention requirement');
  check(projectDelete.show({ id: 2, status: '待启动' }), 'Unrelated historical project behavior stays available');

  const cancelled = harness({ workflow: fixture() });
  await cancelled.sandbox.showDemandWorkflow({ id: 9 }, 'APPROVE');
  cancelled.sandbox.closeModal();
  equal(await cancelled.modal.options.onOk(), false, 'Closed modal cannot post');
  check(!cancelled.calls.some((call) => call.body), 'Closing cancels scope with no write');

  const opening = harness();
  let resolveContext;
  opening.sandbox.api = () => new Promise((resolve) => { resolveContext = resolve; });
  const slowOpen = opening.sandbox.editWorkflowDemand(null);
  opening.sandbox.openModal('另一条记录', 'latest', {});
  resolveContext(opening.contextPayload);
  await slowOpen;
  equal(opening.modal.title, '另一条记录', 'Late dialog request cannot replace a newer modal');

  // Verify the real host wrapper preserves structured error metadata for conflict readback.
  const wrapper = { fetch: async () => ({ status: 409, text: async () => JSON.stringify({ code: 409, msg: '版本已变', data: { approvalCode: 'STALE_VERSION' } }) }), navigator: { onLine: true }, toast() {} };
  vm.createContext(wrapper); vm.runInContext(between('  async function api(', '  function encodeBase64UrlUtf8('), wrapper);
  await assert.rejects(wrapper.api('/approvals/tasks/11/actions', { body: {}, quiet: true }), (error) => error.status === 409 && error.data.approvalCode === 'STALE_VERSION'); checks++;
  console.log(JSON.stringify({ ok: true, suite: 'original workflow UI handlers', checks, network: false, database: false }));
})().catch((error) => { console.error(error); process.exitCode = 1; });
