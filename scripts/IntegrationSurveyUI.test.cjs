'use strict';
// Real app page/project/modal/API bridge plus the real survey-results component.
// Reuses the established synthetic DOM/HTTP harness, without running its independent suite.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const hostSuite = fs.readFileSync(path.join(__dirname, 'IntegrationDeliveryCatalogUI.test.cjs'), 'utf8');
let harnessSource = hostSuite.slice(0, hostSuite.indexOf('(async () => {'));
harnessSource = harnessSource.replace("const pages = between('  async function pageDispatches(c) {'", "const pages = between('  async function pageQuestionnaires(c) {', '  // 问卷构建器') + between('  async function pageDispatches(c) {'")
  .replace("dispatches: 'pageDispatches',", "questionnaires: 'pageQuestionnaires', dispatches: 'pageDispatches',")
  .replace("if (url.includes('delivery-settlement'))", "if (url.includes('survey-results')) return { mount(root, context) { h.mounts.push(context); return modules.survey.mount(root, context); } };\n      if (url.includes('delivery-settlement'))");
const support = vm.runInNewContext(harnessSource + '\n({ harness, project, dispatch, NodeStub, setModule: survey => { modules = { survey }; } });', { require, __dirname, console, structuredClone, AbortController, DOMException, URL, URLSearchParams, Buffer, setImmediate });
support.NodeStub.prototype.replaceChildren = function (...nodes) { this.innerHTML = ''; nodes.forEach(node => this.appendChild(node)); };
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
let checks = 0;
const check = (value, label) => { assert.ok(value, label); checks++; };
const equal = (value, expected, label) => { assert.deepEqual(JSON.parse(JSON.stringify(value)), expected, label); checks++; };
const rules = { id: 'draft-per-question-v1', minScore: '0', maxScore: '10', confirmed: false };
const config = () => ({ ready: true, synthetic: false, adapterId: 'internal-survey-xlsx-v1', maxBytes: 5242880, canCommit: false, policyConfirmed: false, historyAvailable: false,
  projects: [{ id: 5, key: '5', name: '合成项目甲' }, { id: 7, key: '7', name: '<script>合成项目乙</script>' }], rulesDraft: rules });
const preview = (projectId = 5) => ({ mode: 'RESPONSE_SUMMARY_PREVIEW', state: 'PREVIEW', adapterId: 'internal-survey-xlsx-v1', synthetic: false, canCommit: false, policyConfirmed: false, historyAvailable: false,
  projectId, projectName: '不得替代配置内项目名称', rulesDraft: rules, responseRowCount: 2, overallQuestionKey: 'q10', duplicateCheck: { status: 'NOT_CHECKED', recordIds: [] }, issues: [], issueCount: 0, issuesTruncated: false,
  questions: [{ key: 'q1', label: '课程内容（满分10分）', column: 'A', validCount: 0, blankCount: 1, invalidCount: 1, averageText: null }, { key: 'q10', label: '本次课程总体满意度（满分10分）', column: 'J', validCount: 2, blankCount: 0, invalidCount: 0, averageText: '9.500' }],
});
const bytes = Buffer.from('synthetic-workbook-bytes');
function harness({ page = 'questionnaires', writer = false, projectId = null, configuration = config(), result = preview(), moduleWait, fetchOverride, legacy = false } = {}) {
  const h = support.harness({ page, writer, contextProjectId: projectId, moduleWait,
    projectRows: [{ ...support.project(5, !legacy), ...(legacy ? {} : { workflow_source: { business_path: 'direct' } }) }, support.project(6, false)],
    fetchOverride: (url, opts, normal, response) => {
      const fallback = () => url === '/api/survey-response-imports/config' ? response(configuration) : url === '/api/survey-response-imports/preview' ? response(result) : normal(url, opts);
      return fetchOverride ? fetchOverride(url, opts, fallback, response) : fallback();
    },
  });
  const mount = h.mount; h.mount = async () => { await mount(); await tick(); };
  h.entry = () => h.content.querySelector('[data-survey-preview]');
  h.open = async () => { check(h.entry() && !h.entry().disabled, 'Authorized original entry exists'); await h.emit(h.entry(), 'click'); };
  h.select = async id => { const node = h.modal().querySelector('[data-project]'); node.value = String(id); await h.emit(node, 'change'); };
  h.upload = async ({ name = 'anonymous.xlsx', size = bytes.length, buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength), waiting } = {}) => {
    const node = h.modal().querySelector('[data-file]'); node.files = [{ name, size, arrayBuffer: async () => { h.reads = (h.reads || 0) + 1; return waiting ? waiting.promise : buffer; } }]; await h.emit(node, 'change');
  };
  h.run = async () => { const node = h.modal().querySelector('[data-action="preview"]'); check(node && !node.disabled, 'Preview action requires selected file and authorized project'); await h.emit(node, 'click'); };
  h.surveyCalls = () => h.calls.filter(call => call.url.startsWith('/api/survey-response-imports'));
  return h;
}
(async () => {
  const moduleSource = fs.readFileSync(path.join(__dirname, '../web/modules/survey-results/index.js'), 'utf8');
  support.setModule(await import('data:text/javascript;base64,' + Buffer.from(moduleSource).toString('base64')));
  const h = harness(); await h.mount();
  check(h.entry() && h.sandbox.state.user.role === 'viewer', 'Explicit config enables preview for a legacy readonly-role account');
  equal(h.imports, [], 'Component is lazy until original entry is used');
  check(h.content.textContent.includes('历史回收率') && h.content.textContent.includes('历史研序问卷'), 'Original legacy report remains explicitly separate from the draft import entry');
  await h.open();
  check(h.imports[0].endsWith('/survey-results/index.js?v=20260924policy1') && h.mounts[0].mode === 'live', 'Real component runs in live mode with a versioned module');
  check(h.modal().querySelector('.modal') && h.modal().querySelector('.yx-survey-results'), 'Real component is mounted inside the original modal');
  equal(h.modal().querySelector('[data-project]').options.map(item => item.value), ['', '5', '7'], 'Project choices come only from config, never the old project directory');
  check(!h.modal().querySelector('script') && h.modal().textContent.includes('<script>合成项目乙</script>'), 'Authorized project labels remain escaped');
  await h.upload(); equal(h.posts().length, 0, 'Choosing a file alone makes no upload');
  await h.select(5); await h.run();
  equal(JSON.parse(h.posts()[0].opts.body), { fileName: 'anonymous.xlsx', xlsxBase64: bytes.toString('base64'), projectId: 5 }, 'Bridge sends only selected file/name/project exactly once as JSON');
  check(h.posts()[0].url === '/api/survey-response-imports/preview' && h.posts()[0].opts.credentials === 'same-origin' && h.posts()[0].opts.signal, 'Preview uses original same-origin authenticated API and cancellation');
  check(h.modal().textContent.includes('9.500') && h.modal().textContent.includes('暂无有效评分'), 'Component preserves exact server average and null as unknown');
  check(h.modal().textContent.includes('答卷记录数（未去重）') && h.modal().textContent.includes('历史重复检查尚未接入') && h.modal().textContent.includes('仅预览 · 尚未保存'), 'Draft count, unchecked history and no persistence remain explicit');
  check(!h.modal().textContent.includes('不得替代配置内项目名称') && !h.modal().querySelector('#modal-ok'), 'Authorized config names are used and no save/commit action appears');
  check(h.posts().length === 1 && h.renders === 0, 'Preview neither writes another endpoint nor rebuilds old page');
  h.leave(); check(!h.modal() && h.mounts[0].signal.aborted, 'Leaving page aborts component and closes its own modal');

  const scoped = harness({ page: 'project_detail' }); await scoped.mount();
  check(scoped.content.querySelector('.evaluation-panel').textContent.includes('评分统计与复核') && scoped.entry() && scoped.content.querySelector('[data-survey-formal-open]') && !scoped.content.querySelector('[data-project-goto="questionnaires"]'), 'New-flow project retains the draft entry alongside formal review without old questionnaire navigation');
  check(!scoped.calls.some(call => call.url.startsWith('/api/questionnaires')), 'New-flow project does not query old questionnaires');
  await scoped.open(); equal(scoped.modal().querySelector('[data-project]').value, '5', 'Current trusted project is preselected from the live config only');
  await scoped.upload(); await scoped.run();
  const successor = scoped.sandbox.openModal('另一项工作', '<p>保留</p>', { noFoot: true }); scoped.leave(); check(scoped.modal() === successor, 'Survey cleanup does not close a later unrelated modal'); scoped.sandbox.closeModal();
  const newContext = harness({ projectId: 5, writer: true }); await newContext.mount();
  check(newContext.entry() && !newContext.content.querySelector('#add-btn') && !newContext.content.querySelector('.module-summary'), 'New-flow evaluation context has no survey creation or misleading legacy response rate');
  check(newContext.content.textContent.includes('不在研序创建或发放问卷'), 'New project explicitly retains original-platform collection boundary');
  const legacy = harness({ writer: true, legacy: true }); await legacy.mount();
  check(legacy.content.querySelector('#add-btn'), 'Legacy questionnaire creation remains available for original projects');
  const mixed = harness({ writer: true, fetchOverride: (url, opts, normal, response) => url === '/api/questionnaires' ? response([{ id: 15, project_id: 5, title: '旧留存问卷', status: '草稿', send_total: 0 }, { id: 16, project_id: 6, title: '历史项目问卷', status: '草稿', send_total: 0 }]) : normal() }); await mixed.mount();
  let builderProjects;
  mixed.sandbox.showQForm = (row, projects) => { builderProjects = projects; };
  mixed.content.querySelector('#add-btn').onclick();
  equal(builderProjects.map(item => item.id), [6], 'Original new-questionnaire control only offers legacy projects');
  const questionnaireActions = mixed.bindings.at(-1).actions;
  for (const [label, status] of [['发布', '草稿'], ['发送链接', '已发布'], ['关闭', '已发布'], ['编辑', '草稿'], ['删除', '草稿']]) {
    const action = questionnaireActions.find(item => item.l === label);
    check(!action.show({ project_id: 5, status, send_total: 0 }) && action.show({ project_id: 6, status, send_total: 0 }), label + ': original questionnaire mutations retain legacy behavior and exclude new-flow project');
  }
  const legacyProject = harness({ page: 'project_detail', legacy: true }); await legacyProject.mount();
  check(legacyProject.content.querySelector('[data-project-goto="questionnaires"]') && !legacyProject.surveyCalls().length, 'Legacy project retains the original evaluation path without querying new config');

  for (const [name, configuration] of [['empty', { ...config(), projects: [] }], ['unready', { ...config(), ready: false }], ['synthetic', { ...config(), synthetic: true }], ['commit', { ...config(), canCommit: true }]]) {
    const denied = harness({ writer: true, configuration }); await denied.mount();
    check(!denied.entry() && denied.content.querySelector('#tbl') && denied.imports.length === 0, `${name}: no draft capability is inferred from old administrator role`);
  }
  const wrongProject = harness({ projectId: 6 }); await wrongProject.mount(); check(!wrongProject.entry() && wrongProject.content.textContent.includes('当前项目没有评分草案预览权限'), 'A context project outside config is not guessed or silently replaced');
  for (const status of [403, 409, 503]) {
    const denied = harness({ writer: true, fetchOverride: (url, opts, normal, response) => url.endsWith('/survey-response-imports/config') ? response(null, status) : normal() }); await denied.mount();
    check(!denied.entry() && denied.content.querySelector('#tbl'), `${status}: config error is local and preserves the old page`);
  }
  const expired = harness({ fetchOverride: (url, opts, normal, response) => url.endsWith('/survey-response-imports/config') ? response(null, 401) : normal() }); await expired.mount();
  check(expired.invalidations === 1 && expired.content.textContent === '登录', 'Config 401 uses original session invalidation');

  for (const status of [403, 409, 413, 429]) {
    const failure = harness({ fetchOverride: (url, opts, normal, response) => url.endsWith('/survey-response-imports/preview') ? response(null, status) : normal() });
    await failure.mount(); await failure.open(); await failure.select(5); await failure.upload(); await failure.run();
    check(failure.modal().querySelector('#survey-preview-notice').textContent.length > 0 && !failure.modal().querySelector('.yx-x-overview'), `${status}: no stale summary survives a rejected preview`);
    if (status === 403) check(!failure.modal().querySelector('[data-file]') && !failure.entry() && failure.mounts[0].signal.aborted, '403 discards selected file/component and revokes page entry');
    failure.leave(); check(!failure.modal(), `${status}: route cleanup still owns and closes the error modal`);
  }
  const invalidResult = harness({ result: { ...preview(), canCommit: true } }); await invalidResult.mount(); await invalidResult.open(); await invalidResult.select(5); await invalidResult.upload(); await invalidResult.run();
  check(!invalidResult.modal().querySelector('.yx-x-overview') && !invalidResult.modal().querySelector('#modal-ok'), 'Response cannot turn a draft preview into a commit capability');
  const malformed = harness({ result: { ...preview(), state: 'ERROR', questions: [], responseRowCount: 0, issues: [{ row: 0, column: '', code: 'INVALID_XLSX', severity: 'ERROR' }], issueCount: 1 } });
  await malformed.mount(); await malformed.open(); await malformed.select(5); await malformed.upload(); await malformed.run();
  check(malformed.modal().textContent.includes('这份 Excel 还不能生成汇总') && !malformed.modal().querySelector('.yx-x-overview'), 'Successful HTTP containing file ERROR is not treated as successful statistics');
  const big = harness(); await big.mount(); await big.open(); await big.select(5); await big.upload({ size: 5242881 });
  check(!big.reads && !big.posts().length && big.modal().textContent.includes('超过当前大小上限'), 'Oversize file is rejected before reading or sending');

  const waitingConfig = deferred(); const configLate = harness({ fetchOverride: (url, opts, normal, response) => url.endsWith('/survey-response-imports/config') ? waitingConfig.promise.then(response) : normal() }); await configLate.mount(); configLate.leave(); waitingConfig.resolve(config()); await tick();
  check(configLate.content.textContent === '后续页面' && configLate.surveyCalls()[0].opts.signal.aborted, 'Late entry config cannot restore an entry on a later page');
  let configurationReads = 0;
  const revokedContext = harness({ page: 'project_detail', fetchOverride: (url, opts, normal, response) => url.endsWith('/survey-response-imports/config') && ++configurationReads > 1 ? response({ ...config(), projects: [] }) : normal() }); await revokedContext.mount(); await revokedContext.open();
  check(!revokedContext.modal().querySelector('[data-file]') && revokedContext.modal().textContent.includes('当前没有可预览评分的已受理项目'), 'Opening rechecks current configuration and removes file handling when project access disappears'); revokedContext.leave(); check(!revokedContext.modal(), 'A now-unavailable configuration modal is still cleaned up by its page');
  const previewExpired = harness({ fetchOverride: (url, opts, normal, response) => url.endsWith('/survey-response-imports/preview') ? response(null, 401) : normal() }); await previewExpired.mount(); await previewExpired.open(); await previewExpired.select(5); await previewExpired.upload(); await previewExpired.run();
  check(previewExpired.invalidations === 1 && !previewExpired.modal() && previewExpired.content.textContent === '登录', 'Preview 401 clears the original session and owned component');
  const waitImport = deferred(); const importLate = harness({ moduleWait: waitImport }); await importLate.mount(); const opening = importLate.open(); await tick(); importLate.leave(); waitImport.resolve(); await opening;
  check(!importLate.mounts.length && !importLate.modal(), 'Late module import never mounts after leaving');
  const pending = deferred(); const changed = harness({ fetchOverride: (url, opts, normal, response) => url.endsWith('/survey-response-imports/preview') ? pending.promise.then(response) : normal() });
  await changed.mount(); await changed.open(); await changed.select(5); await changed.upload(); await changed.run(); const request = changed.posts()[0];
  await changed.select(7); pending.resolve(preview(5)); await tick();
  check(request.opts.signal.aborted && !changed.modal().querySelector('.yx-x-overview'), 'Changing project cancels and rejects a prior-project response');
  const waitingFile = deferred(); const closed = harness(); await closed.mount(); await closed.open(); await closed.select(5); await closed.upload({ waiting: waitingFile }); closed.sandbox.closeModal();
  waitingFile.resolve(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength)); await tick();
  check(!closed.posts().length && !closed.modal(), 'Late file read after modal close neither uploads nor reopens the modal');
  const waitingPreview = deferred(); const closedPreview = harness({ fetchOverride: (url, opts, normal, response) => url.endsWith('/survey-response-imports/preview') ? waitingPreview.promise.then(response) : normal() });
  await closedPreview.mount(); await closedPreview.open(); await closedPreview.select(5); await closedPreview.upload(); await closedPreview.run(); closedPreview.sandbox.closeModal(); waitingPreview.resolve(preview()); await tick();
  check(closedPreview.posts()[0].opts.signal.aborted && !closedPreview.modal(), 'Closing modal aborts a preview and ignores late response');
  console.log(JSON.stringify({ ok: true, suite: 'original survey preview host integration', checks, transport: 'synthetic', browser: false, database: false }));
})().catch(error => { console.error(error); process.exitCode = 1; });
