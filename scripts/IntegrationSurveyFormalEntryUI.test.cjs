'use strict';
// Checks the proposed original-modal adapter, without changing/executing the shared app.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const repo = process.env.YANXU_APP || path.resolve(__dirname, '..');
const support = fs.readFileSync(path.join(repo, 'scripts/M01-account-bindings-check.mjs'), 'utf8');
const { NodeStub } = vm.runInNewContext(support.slice(support.indexOf('const decode ='), support.indexOf('function fixture()')) + '\n({NodeStub})', {});
const appSource = fs.readFileSync(path.join(repo, 'web/app.js'), 'utf8');
const adapterSource = appSource.slice(appSource.indexOf('  function mountSurveyFormalEntry('), appSource.indexOf('  function mountSurveyPreviewEntry('));
const moduleImport = /await import\('\/modules\/survey-results\/formal-workspace\.js(?:\?[^']*)?'\)/g;
assert.equal([...adapterSource.matchAll(moduleImport)].length, 1, 'Expected one formal survey module import');
const source = adapterSource.replace(moduleImport, 'await loadModule()');
const tick = () => new Promise(r => setImmediate(r));
let checks = 0;
const check = (value, msg) => { assert.ok(value, msg); checks++; };
const eq = (actual, expected, msg) => { assert.deepEqual(actual, expected, msg); checks++; };
function harness(projectId = 12) {
  const h = { calls: [], cleanups: [], state: { user: { uid: 1 } }, current: true, mask: null, closed: 0, released: 0, permitClose: true, root: new NodeStub(), entry: new NodeStub(), imports: 0 };
  const host = { AbortController, state: h.state, Number, window: { confirm: () => h.permitClose }, esc: value => String(value).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]),
    $: (selector, within) => within ? within.querySelector(selector) : selector === '#modal-mask' ? h.mask : null,
    isRouteCurrent: () => h.current, addRouteCleanup: fn => h.cleanups.push(fn),
    api: async (url, opts) => { h.calls.push({ url, opts }); return h.reply ? h.reply(url, opts) : { ok: true }; },
    closeModal: force => { if (!h.mask) return true; if (!force && h.opts?.beforeClose?.() === false) return false; const old = h.mask; h.mask = null; old.isConnected = false; h.closed++; h.opts?.onClose?.(); return true; },
    openModal: (_, html, opts) => { if (host.closeModal() === false) return null; h.mask = new NodeStub(); h.mask.innerHTML = html; h.opts = opts; return h.mask; },
    loadModule: async () => { h.imports++; return { mount: (_, context) => { h.ctx = context; return { ready: Promise.resolve(), beforeClose: () => h.permitClose, destroy: () => h.released++ }; } }; },
  };
  vm.createContext(host); vm.runInContext(source + '\nglobalThis.create = mountSurveyFormalEntry;', host);
  h.controller = host.create(h.root, h.entry, { epoch: 1, page: 'questionnaires', projectId, projects: [{ id: 12, title: '合成项目甲' }, { id: 13, title: '合成项目乙' }] }); return h;
}
(async () => {
  const h = harness(); await h.controller.open(); check(!!h.ctx, 'mounted only within owned modal'); eq(h.ctx.projectId, 12, 'fixed numeric project'); eq(h.ctx.getUser(), h.state.user, 'same stable original identity');
  const calls = [
    ['/survey-results?project_id=12', { method: 'GET' }],
    ['/survey-response-imports/prepare', { method: 'POST', body: { fileName: 'synthetic.xlsx', projectId: 12, reason: '', replacesId: 0, xlsxBase64: 'UEsDBA==' } }],
    ['/survey-results/confirm', { method: 'POST', body: { acknowledged: true, preview_token: 'a'.repeat(64), project_id: 12, request_id: 'x' } }],
    ['/survey-results/review', { method: 'POST', body: { decision: 'APPROVE', expected_version: 1, import_id: 1, project_id: 12, reason: '', request_id: 'y' } }],
  ];
  for (const [url, opts] of calls) await h.ctx.api(url, opts);
  eq(h.calls.length, 4, 'four whitelisted routes'); check(h.calls.every(c => c.opts.quiet && c.opts.signal), 'same host quiet/cancelled transport');
  for (const [url, opts] of [
    ['/survey-results?project_id=13', { method: 'GET' }], ['/survey-results?project_id=12&extra=1', { method: 'GET' }],
    ['/q/publish', { method: 'POST', body: { id: 12 } }], ['/survey-response-imports/preview', calls[1][1]],
    [calls[1][0], { method: 'POST', body: { ...calls[1][1].body, projectId: 13 } }],
    [calls[2][0], { method: 'POST', body: { ...calls[2][1].body, role: 'admin' } }],
    [calls[3][0], { method: 'POST', body: { ...calls[3][1].body, project_id: 13 } }],
  ]) { await assert.rejects(() => h.ctx.api(url, opts)); checks++; }
  eq(h.calls.length, 4, 'invalid route/body never enters original API');
  h.permitClose = false; eq(h.opts.beforeClose(), false, 'original close guard consults child'); h.permitClose = true;
  const oldMask = h.mask; h.cleanups[0](); check(h.ctx.signal.aborted, 'route abort'); eq(h.released, 1, 'module destroyed once'); eq(h.closed, 1, 'own modal closed'); check(!oldMask.isConnected, 'owned modal removed');
  const other = harness(); await other.controller.open(); other.mask = new NodeStub(); other.cleanups[0](); eq(other.closed, 0, 'unrelated newer modal never closed'); eq(other.released, 1, 'old content still destroyed');
  const select = harness(null); const picker = select.entry.querySelector('[data-survey-formal-project]'), button = select.entry.querySelector('[data-survey-formal-open]'); check(button.disabled, 'no guessed project'); await select.controller.open(); eq(select.imports, 0, 'empty selection cannot open'); picker.value = '13'; picker.onchange(); check(!button.disabled, 'known explicit choice enables'); await select.controller.open(); eq(select.ctx.projectId, 13, 'selected project frozen'); picker.value = '12'; picker.onchange(); eq(select.ctx.projectId, 13, 'outside choice does not change open workspace');
  let resolve; const delayed = new Promise(r => resolve = r), late = harness(); await late.controller.open(); late.reply = () => delayed; const pending = late.ctx.api('/survey-results?project_id=12', { method: 'GET' }); await tick(); late.current = false; resolve({ private: 'late result' }); await assert.rejects(() => pending, { name: 'AbortError' }); checks++;
  const changed = harness(); await changed.controller.open(); changed.state.user = { uid: 2 }; await assert.rejects(() => changed.ctx.api('/survey-results?project_id=12', { method: 'GET' }), { name: 'AbortError' }); checks++; eq(changed.calls.length, 0, 'new account cannot send prior project request');
  console.log(JSON.stringify({ checks, failed: 0, scope: 'proposed original modal adapter, synthetic only' }));
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
