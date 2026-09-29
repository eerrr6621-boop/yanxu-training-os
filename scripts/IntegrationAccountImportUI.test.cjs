'use strict';
// Real original users page + bindings + importer, original form/modal/table and API transport.
// Only DOM and HTTP are synthetic; no actual accounts or source files are used.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const bindingSuite = fs.readFileSync(path.join(__dirname, 'IntegrationAccountBindingsUI.test.cjs'), 'utf8');
const support = vm.runInNewContext(bindingSuite.slice(0, bindingSuite.indexOf('(async () => {')).replace('pages.replace(', 'pages.replaceAll(') + '\n({ harness });', { require, __dirname, console, structuredClone, AbortController, setImmediate });
const importer = fs.readFileSync(path.join(__dirname, '../web/modules/identity/account-import.js'), 'utf8');
const componentSuite = fs.readFileSync(path.join(__dirname, 'M01-import-ui-check.mjs'), 'utf8');
const fixtures = vm.runInNewContext(componentSuite.slice(componentSuite.indexOf('function fixture()'), componentSuite.indexOf('function harness(')) + '\n({fixture,makeReceipt});', { copy: structuredClone });
const tick = () => new Promise(yes => setImmediate(yes));
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
let checks = 0;
const check = (yes, label) => { assert.ok(yes, label); checks++; };
const equal = (actual, expected, label) => { assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected, label); checks++; };
function harness({ role = 'admin', importFailure = false, bindingFailure = false, importWait, preview = fixtures.fixture(), fetchOverride } = {}) {
  let currentPreview = structuredClone(preview), host, instance;
  const h = support.harness({ moduleFailure: bindingFailure, fetchOverride: (url, opts, normal, respond) => {
    const fallback = () => {
      if (url === '/api/organization/account-import/preview') return respond(currentPreview);
      if (url.startsWith('/api/organization/account-import/accounts/')) { const accountId = Number(url.split('/').at(-1)); return respond({ accountId, username: 'synthetic-' + accountId, name: '<img src=x onerror=alert(1)> 合成已有账号', status: 1, role: 'viewer', available: true, proof: 'SYNTHETIC-PROOF-' + accountId }); }
      if (url === '/api/organization/account-import/commit') {
        const decisions = JSON.parse(opts.body).decisions, receipt = fixtures.makeReceipt(currentPreview, decisions);
        currentPreview = { ...currentPreview, imported: true, receipt };
        receipt.rows.filter(row => row.action === 'CREATE_PENDING').forEach(row => h.users.push({ id: row.accountId, username: 'pending_synthetic_' + row.accountId, name: '合成待启用', role: 'viewer', status: 0 }));
        return respond(receipt);
      }
      return normal(url, opts);
    };
    return fetchOverride ? fetchOverride(url, opts, fallback, respond) : fallback();
  } });
  h.sandbox.state.user.role = role;
  vm.runInContext('{ ' + importer.replace('export function mountAccountImport', 'function mountAccountImport') + '; globalThis.actualAccountImport = mountAccountImport; }', h.sandbox);
  const loadBindings = h.sandbox.loadIdentityModule;
  h.sandbox.loadIdentityModule = async url => {
    if (!url.includes('/account-import.js?')) return loadBindings(url);
    h.imports.push(url); if (importWait) await importWait.promise; if (importFailure) throw Error('SYNTHETIC module unavailable');
    return { mountAccountImport(root, context) { host = context; instance = h.sandbox.actualAccountImport(root, context); return instance; } };
  };
  h.importRoot = () => h.content.querySelector('#user-import-content'); h.importHost = () => host; h.importer = () => instance;
  h.openImport = () => h.content.querySelector('#user-import-open').onclick();
  h.previewRequests = () => h.calls.filter(call => call.url === '/api/organization/account-import/preview');
  h.importPosts = () => h.calls.filter(call => call.url === '/api/organization/account-import/commit');
  h.review = async (index, account = null) => {
    const table = h.importRoot().querySelector('[data-import-table]'), button = table.querySelectorAll('button').find(el => el.dataset.id === String(index + 1) && el.textContent.includes('核对导入'));
    check(button, 'Original table supplies scoped import review action'); await button.onclick();
    equal(h.modal().querySelector('#modal-title').textContent, '核对候选人员', 'Importer uses original modal');
    h.set('action', account == null ? 'CREATE_PENDING' : 'LINK_EXISTING');
    if (account != null) { h.set('accountId', String(account)); h.modal().querySelector('[data-import-lookup]').onclick(); await tick(); }
    h.modal().querySelector('[data-import-reviewed]').checked = true; await h.save();
  };
  h.commit = async () => { h.importRoot().querySelector('[data-import-commit]').onclick(); await h.save(); await tick(); await tick(); };
  return h;
}
(async () => {
  const h = harness(); await h.mount();
  equal(h.previewRequests().length, 0, 'Original users page does not load private import data before explicit entry');
  equal(h.imports.length, 1, 'Only original binding module loads at first');
  check(h.content.querySelector('#tbl') && h.content.querySelector('#add-btn') && h.content.querySelector('#user-import-open'), 'Original users table, manual add and localized import entry coexist');
  const table = h.content.querySelector('#tbl'), summary = h.content.querySelector('#user-summary'); await h.openImport();
  equal(h.previewRequests().length, 1, 'Explicit import entry fetches server preview once');
  check(h.imports.some(url => url === '/modules/identity/account-import.js?v=20260924management1'), 'Importer is versioned and loaded on demand');
  check(h.importRoot().textContent.includes('全批已核对 0 / 3') && h.importRoot().textContent.includes('历史保留 2'), 'Original localized component preserves complete candidate and exclusion counts');
  await h.review(0); await h.review(1, 14); await h.review(2);
  equal(h.importPosts().length, 0, 'Individual review never imports accounts');
  check(!h.importRoot().querySelector('[data-import-commit]').disabled, 'All three decisions enable batch confirmation');
  await h.commit();
  equal(h.importPosts().length, 1, 'Original final confirmation sends one import');
  const payload = JSON.parse(h.importPosts()[0].opts.body);
  equal(Object.keys(payload).sort(), ['decisions', 'expectedRevision', 'reviewToken', 'sourceFingerprint'], 'Host never adds source paths, authority or personal facts to commit');
  equal(payload.decisions[1].accountProof, 'SYNTHETIC-PROOF-14', 'Link submits the server lookup proof for the explicit existing account');
  check(h.content.querySelector('#tbl') === table && h.content.querySelector('#user-summary') === summary && h.renders === 0, 'Verified import refreshes accounts and counters locally without recreating page');
  check(table.textContent.includes('pending_synthetic_201') && table.textContent.includes('pending_synthetic_203'), 'New pending accounts appear in original account table');
  check(summary.textContent.includes('系统用户6人') && summary.textContent.includes('启用账号3个'), 'Original counters update account total without falsely activating pending accounts');
  check(h.importRoot().textContent.includes('审批权限未发布') && !h.importRoot().textContent.includes('PERSON-001'), 'Receipt preserves unpublished authority and does not display internal person IDs as formal codes');
  const userReads = h.calls.filter(call => call.url === '/api/users').length; await h.importer().refresh(); await tick();
  equal(h.calls.filter(call => call.url === '/api/users').length, userReads, 'Same persisted receipt does not repeatedly refresh original accounts');
  h.clickAction(14, '编辑'); equal(h.modal().querySelector('#modal-title').textContent, '编辑用户', 'Original account edit remains available after import'); h.sandbox.closeModal();
  h.content.querySelector('#add-btn').onclick(); equal(h.modal().querySelector('#modal-title').textContent, '新增用户', 'Original manual creation still opens its own form'); h.sandbox.closeModal();

  const evil = fixtures.fixture(); evil.rows[0].name = '<img src=x onerror=alert(1)>'; evil.rows[0].organization = '<script>bad()</script>';
  const escaped = harness({ preview: evil }); await escaped.mount(); await escaped.openImport();
  check(!escaped.importRoot().querySelector('img, script') && escaped.importRoot().textContent.includes('<img'), 'Candidate names and organizations are displayed as escaped text');
  await escaped.review(0, 14); check(!escaped.modal(), 'Safe account lookup and review finish in original modal');

  for (const role of ['viewer', 'manager']) {
    const ordinary = harness({ role }); await ordinary.mount();
    check(!ordinary.content.querySelector('#user-import-open') && !ordinary.content.querySelector('#user-import-content'), role + ': no import entry or private container');
    equal(ordinary.previewRequests().length, 0, role + ': no private preview HTTP');
    check(!ordinary.imports.some(url => url.includes('account-import.js')), role + ': no import module');
  }
  const failed = harness({ importFailure: true }); await failed.mount(); await failed.openImport();
  check(failed.content.querySelector('#tbl').textContent.includes('synthetic-14') && failed.content.querySelector('#user-import-status').textContent.includes('现有账号仍可维护'), 'Dynamic import failure is local and retains original user table');
  check(!failed.content.querySelector('#user-import-open').disabled && !failed.content.querySelector('#user-import-open').hidden, 'Failed lazy import has a usable retry');
  for (const status of [403, 503]) {
    const unavailable = harness({ fetchOverride: (url, opts, normal, respond) => url.endsWith('/account-import/preview') ? respond(null, status) : normal() }); await unavailable.mount(); await unavailable.openImport();
    check(unavailable.content.querySelector('#tbl').textContent.includes('synthetic-14') && unavailable.importRoot().querySelector('[data-import-commit]').disabled, status + ': denied/unconfigured source does not break ordinary account maintenance');
    check(!unavailable.importRoot().textContent.includes('合成人员甲'), status + ': no stale candidate shown');
    check(unavailable.importRoot().querySelector('[data-import-progress]').textContent.includes('暂不可用') && !unavailable.importRoot().textContent.includes('0 / 0'), status + ': unknown source never appears as a trusted zero-person batch');
  }
  const fallback = harness({ bindingFailure: true }); await fallback.mount(); await fallback.openImport(); await fallback.review(0); await fallback.review(1); await fallback.review(2); await fallback.commit();
  check(fallback.content.querySelector('[data-users-fallback]').textContent.includes('pending_synthetic_201') && fallback.content.querySelector('#user-summary').textContent.includes('系统用户7人'), 'Import also locally refreshes original fallback table when binding adapter fails');
  const recovered = harness({ fetchOverride: async (url, opts, normal) => { const result = await normal(); if (url.endsWith('/account-import/commit')) throw Error('SYNTHETIC acknowledgement lost'); return result; } });
  await recovered.mount(); await recovered.openImport(); await recovered.review(0); await recovered.review(1); await recovered.review(2); await recovered.commit(); await tick();
  equal(recovered.importPosts().length, 1, 'Unknown result never automatically repeats original POST');
  equal(recovered.previewRequests().length, 2, 'Unknown result is recovered with one preview GET');
  check(recovered.content.querySelector('#tbl').textContent.includes('pending_synthetic_201') && recovered.content.querySelector('#user-summary').textContent.includes('系统用户7人'), 'GET receipt recovery refreshes original account rows and counts');
  check(recovered.importRoot().textContent.includes('本批 3 人已导入'), 'Recovered receipt shows persisted outcome rather than failed import');

  for (const mode of ['route', 'identity']) {
    const gate = deferred(), stale = harness({ importWait: gate }); await stale.mount(); const loading = stale.openImport();
    if (mode === 'route') stale.sandbox.beginRouteEpoch(); else stale.sandbox.state.user = { uid: 55, role: 'admin' };
    gate.resolve(); await loading; equal(stale.previewRequests().length, 0, mode + ': late module result cannot mount or fetch');
  }
  const lateRead = deferred(), late = harness({ fetchOverride: (url, opts, normal, respond) => url.endsWith('/account-import/preview') ? lateRead.promise.then(respond) : normal() }); await late.mount(); const opening = late.openImport(); await tick(); late.sandbox.beginRouteEpoch(); lateRead.resolve(fixtures.fixture()); await opening;
  check(!late.importRoot().textContent && !late.modal(), 'Route cancellation removes importer and ignores late candidate response');
  check(late.previewRequests()[0].opts.signal.aborted, 'Route cleanup cancels actual preview request');
  const pendingPost = deferred(), posting = harness({ fetchOverride: (url, opts, normal, respond) => url.endsWith('/account-import/commit') ? pendingPost.promise.then(respond) : normal() });
  await posting.mount(); await posting.openImport(); await posting.review(0); await posting.review(1); await posting.review(2);
  const committing = posting.commit(); await tick(); const posted = JSON.parse(posting.importPosts()[0].opts.body), readsBefore = posting.calls.filter(call => call.url === '/api/users').length;
  posting.sandbox.beginRouteEpoch(); pendingPost.resolve(fixtures.makeReceipt(fixtures.fixture(), posted.decisions)); await committing;
  equal(posting.calls.filter(call => call.url === '/api/users').length, readsBefore, 'Late successful commit after route change never refreshes new account context');
  check(!posting.importRoot().textContent && !posting.modal(), 'Late receipt cannot resurrect prior import table or modal');
  const guarded = harness(); await guarded.mount(); await guarded.openImport();
  const originalMask = guarded.sandbox.openModal('原未保存总结', '<p>保留原内容</p>', { noFoot: true }); originalMask._beforeClose = () => false;
  const review = guarded.importRoot().querySelector('[data-import-table]').querySelector('button'); await review.onclick();
  check(guarded.modal() === originalMask && guarded.modal().textContent.includes('保留原内容') && !guarded.modal().querySelector('[data-import-reviewed]'), 'Declined original modal replacement cleanly exits without binding import fields into another modal');
  originalMask._beforeClose = null; guarded.sandbox.closeModal(); await review.onclick(); guarded.sandbox.openModal('后继手工编辑', '<p>后继窗口</p>', { noFoot: true }); const successor = guarded.modal();
  guarded.importHost().closeModal(); guarded.importer().destroy();
  check(guarded.modal() === successor, 'Importer host close/destroy checks actual mask identity and cannot close a successor modal');
  console.log(JSON.stringify({ ok: true, suite: 'original account import host integration', checks, transport: 'synthetic', browser: false, database: false }));
})().catch(error => { console.error(error); process.exitCode = 1; });
