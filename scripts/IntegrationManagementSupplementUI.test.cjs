'use strict';
// Actual original pageUsers, shared importer, modal, forms, tables and API transport.
// Synthetic DOM and HTTP only: no source files, service, database or actual accounts.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const originalSuite = fs.readFileSync(path.join(__dirname, 'IntegrationAccountImportUI.test.cjs'), 'utf8');
const support = vm.runInNewContext(originalSuite.slice(0, originalSuite.indexOf('\n(async () => {')) + '\n({ harness, fixtures });', { require, __dirname, console, structuredClone, AbortController, setImmediate });
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
let checks = 0;
const check = (value, label) => { assert.ok(value, label); checks++; };
const equal = (actual, expected, label) => { assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected, label); checks++; };
const base = '/api/organization/management-supplement';
function managementFixture() {
  const preview = support.fixtures.fixture();
  return { ...preview, batchKey: 'SYNTHETIC-MANAGEMENT-BATCH', sourceFingerprint: 'SYNTHETIC-MANAGEMENT-FINGERPRINT', reviewToken: 'SYNTHETIC-MANAGEMENT-TOKEN',
    rows: preview.rows.slice(0, 2).map((row, index) => ({ ...row, reference: 'management-row-' + (index + 1), name: '合成管理人员' + (index + 1), identities: [index ? '合成公司分管领导' : '合成培训管理人员'], approvalRoles: [], approvalEligibility: 'NO_APPROVAL_ROLE' })),
    summary: { candidates: 2, historicalExcluded: 0 }, branchCoverage: [] };
}
function harness({ fetchOverride, ...options } = {}) {
  let preview = managementFixture();
  const h = support.harness({ ...options, fetchOverride: (url, opts, original, respond) => {
    const normal = async () => {
      if (url === base + '/preview') return respond(preview);
      if (/^\/api\/organization\/management-supplement\/accounts\/[1-9][0-9]*$/.test(url)) {
        const accountId = Number(url.split('/').at(-1));
        return respond({ accountId, username: 'synthetic-' + accountId, name: '合成已有账号', status: 1, role: 'viewer', available: true, proof: 'SYNTHETIC-MANAGEMENT-PROOF-' + accountId });
      }
      if (url === base + '/commit') {
        const decisions = JSON.parse(opts.body).decisions, receipt = support.fixtures.makeReceipt(preview, decisions);
        receipt.rows.forEach((row, index) => { row.personCode = 'MANAGEMENT-' + (index + 1); if (row.action === 'CREATE_PENDING') row.accountId = 401 + index; });
        preview = { ...preview, imported: true, receipt };
        receipt.rows.filter(row => row.action === 'CREATE_PENDING').forEach(row => h.users.push({ id: row.accountId, username: 'pending_management_' + row.accountId, name: '合成待启用管理人员', role: 'viewer', status: 0 }));
        return respond(receipt);
      }
      return original();
    };
    return fetchOverride ? fetchOverride(url, opts, normal, respond) : normal();
  } });
  h.openSupplement = () => h.content.querySelector('#user-management-supplement-open').onclick();
  h.supplementReads = () => h.calls.filter(call => call.url === base + '/preview');
  h.supplementPosts = () => h.calls.filter(call => call.url === base + '/commit');
  h.reviewSupplement = async () => { await h.review(0); await h.review(1); };
  h.reviewButton = index => h.importRoot().querySelector('[data-import-table]').querySelectorAll('button').find(button => button.dataset.id === String(index + 1) && button.textContent.includes('核对导入'));
  return h;
}
(async () => {
  const h = harness(); await h.mount();
  check(h.content.querySelector('#user-import-open') && h.content.querySelector('#user-management-supplement-open'), 'Both entries remain in the original users page');
  equal(h.supplementReads().length + h.previewRequests().length, 0, 'Neither private batch is read before explicit entry');
  await h.openImport(); const original = h.importer(); await h.review(0);
  const filter = h.importRoot().querySelector('[data-import-search]'); filter.value = '合成人员'; filter.oninput({ target: filter });
  await h.openSupplement();
  check(h.importer() !== original && !original.canSwitchBatch(), 'Changing batches destroys the old instance');
  equal(h.importHost().importKind, 'management-supplement', 'Host selects only the fixed supplement mode');
  equal(h.supplementReads().length, 1, 'Supplement opens with its own fixed preview route');
  equal(h.importRoot().querySelector('[data-import-search]').value, '', 'Old name filter is not copied into supplement');
  check(h.importRoot().textContent.includes('全批已核对 0 / 2'), 'Old reviewed decisions do not carry into the two-person batch');
  check(h.importRoot().textContent.includes('合成公司分管领导') && h.importRoot().textContent.includes('合成培训管理人员') && !h.importRoot().textContent.includes('兼职教师'), 'Each source identity is displayed without relabeling management as teachers');
  check(h.importRoot().textContent.includes('审批权限另行办理'), 'Empty branch role preparation does not misstate management authority');
  const switcher = h.importRoot().querySelector('[data-import-view-switch]');
  check(switcher.hidden && switcher.style.display === 'none' && h.importRoot().querySelector('[data-import-branches]').hidden, 'Empty branch coverage and its switch are hidden for supplement');
  h.importRoot().querySelector('[data-import-view-branches]').onclick();
  check(!h.importRoot().querySelector('[data-import-person-content]').hidden, 'A stale branch callback cannot expose a coverage view in supplement mode');
  for (const kind of ['/organization/account-import', '../account-import', 'https://example.invalid/source', 'original']) {
    assert.throws(() => h.sandbox.actualAccountImport(h.importRoot(), { ...h.importHost(), importKind: kind }), /Unknown account import kind/); checks++;
  }
  await h.review(0, 14); await h.review(1); await h.commit();
  equal(h.supplementPosts().length, 1, 'Explicit confirmation submits the supplement once');
  equal(h.importPosts().length, 0, 'Supplement confirmation never submits the original batch');
  check(h.calls.some(call => call.url === base + '/accounts/14'), 'Existing account lookup uses fixed supplement route');
  const payload = JSON.parse(h.supplementPosts()[0].opts.body);
  equal(Object.keys(payload).sort(), ['decisions', 'expectedRevision', 'reviewToken', 'sourceFingerprint'], 'Supplement sends only server review proof and explicit per-person decisions');
  equal(payload.decisions.map(row => row.reference), ['management-row-1', 'management-row-2'], 'Only management references are committed');
  equal(payload.decisions[0], { reference: 'management-row-1', action: 'LINK_EXISTING', accountId: 14, accountProof: 'SYNTHETIC-MANAGEMENT-PROOF-14', reviewed: true }, 'Linking retains the explicitly queried ID and supplement proof');
  equal(payload.sourceFingerprint, 'SYNTHETIC-MANAGEMENT-FINGERPRINT', 'Commit uses this batch fingerprint');
  check(h.importRoot().textContent.includes('本批 2 人已导入') && h.importRoot().textContent.includes('新建账号未启用，审批权限未发布'), 'Verified receipt does not claim activation or permission publication');
  check(h.content.querySelector('#tbl').textContent.includes('pending_management_402') && h.content.querySelector('#user-summary').textContent.includes('启用账号3个'), 'Original account table refreshes while enabled count stays unchanged');
  await h.openImport();
  check(h.importRoot().textContent.includes('全批已核对 0 / 3') && !h.importRoot().querySelector('[data-import-view-switch]').hidden, 'Returning to original restores its own full batch and branch view');
  check(h.importHost().importKind === undefined, 'Original host still uses the omitted default mode');
  await h.openSupplement();
  check(h.importRoot().textContent.includes('本批 2 人已导入'), 'Returning to supplement verifies its persisted receipt');
  equal(h.supplementPosts().length, 1, 'Reopening a received batch never resubmits');
  h.sandbox.beginRouteEpoch();

  const cancel = harness(); await cancel.mount(); await cancel.openSupplement();
  await cancel.reviewButton(0).onclick(); const oldSave = cancel.modal().querySelector('#modal-ok').onclick;
  cancel.set('action', 'CREATE_PENDING'); cancel.modal().querySelector('[data-import-reviewed]').checked = true;
  const firstInstance = cancel.importer(); await cancel.openImport();
  check(cancel.importer() === firstInstance && cancel.modal(), 'Open person review blocks batch switching');
  equal(cancel.previewRequests().length, 0, 'Blocked switching does not start old preview');
  cancel.sandbox.closeModal(); await cancel.openImport(); await cancel.reviewButton(0).onclick(); const nextReview = cancel.modal(); await oldSave();
  check(cancel.importRoot().textContent.includes('全批已核对 0 / 3') && cancel.modal() === nextReview, 'Cancelled supplement callback cannot save or close another batch modal');
  cancel.sandbox.closeModal();
  equal(cancel.supplementPosts().length, 0, 'Cancel never commits supplement');
  cancel.sandbox.beginRouteEpoch();

  const postGate = deferred(), posting = harness({ fetchOverride: (url, opts, normal, respond) => url === base + '/commit' ? postGate.promise.then(respond) : normal() });
  await posting.mount(); await posting.openSupplement(); await posting.reviewSupplement();
  const committing = posting.commit(); await tick(); posting.sandbox.closeModal();
  const pendingInstance = posting.importer(); await posting.openImport();
  check(posting.importer() === pendingInstance && !pendingInstance.canSwitchBatch(), 'Pending submit blocks switching even after its modal is cancelled');
  equal(posting.previewRequests().length, 0, 'Pending submit never fetches a different batch');
  posting.sandbox.openModal('后继窗口', '<p>保留</p>', { noFoot: true }); const successor = posting.modal();
  const submitted = JSON.parse(posting.supplementPosts()[0].opts.body);
  postGate.resolve(support.fixtures.makeReceipt(managementFixture(), submitted.decisions)); await committing;
  check(posting.modal() === successor, 'Delayed supplement receipt cannot close a successor modal');
  equal(posting.supplementPosts().length, 1, 'Pending submission remains exactly one POST');
  posting.sandbox.beginRouteEpoch();

  const lost = harness({ fetchOverride: async (url, opts, normal) => { const result = await normal(); if (url === base + '/commit') throw Error('SYNTHETIC lost acknowledgement'); return result; } });
  await lost.mount(); await lost.openSupplement(); await lost.reviewSupplement(); await lost.commit(); await tick();
  equal(lost.supplementPosts().length, 1, 'Lost supplement acknowledgement never automatically repeats POST');
  equal(lost.supplementReads().length, 2, 'Lost acknowledgement checks the same batch receipt once');
  check(lost.importRoot().textContent.includes('本批 2 人已导入'), 'Unknown result recovers only from a valid supplement receipt');
  equal(lost.previewRequests().length, 0, 'Supplement recovery cannot read the original batch receipt');
  lost.sandbox.beginRouteEpoch();

  let unavailable = false;
  const unknown = harness({ fetchOverride: (url, opts, normal, respond) => {
    if (url === base + '/commit') { unavailable = true; throw Error('SYNTHETIC uncertain submit'); }
    return url === base + '/preview' && unavailable ? respond(null, 503) : normal();
  } });
  await unknown.mount(); await unknown.openSupplement(); await unknown.reviewSupplement(); await unknown.commit(); await tick(); await tick();
  check(unknown.importRoot().querySelector('[data-import-commit]').disabled && unknown.importRoot().textContent.includes('不要重复提交'), 'Unverified recovery remains blocked and asks for an explicit receipt recheck');
  equal(unknown.supplementPosts().length, 1, 'Failed recovery never auto-retries the POST');
  equal(unknown.supplementReads().length, 2, 'Failed recovery does not poll indefinitely');
  unavailable = false; await unknown.importer().refresh();
  check(unknown.importRoot().textContent.includes('全批已核对 0 / 2') && unknown.importRoot().querySelector('[data-import-commit]').disabled, 'Explicit refresh requires new individual decisions when no receipt exists');
  unknown.sandbox.beginRouteEpoch();

  for (const mode of ['route', 'identity']) {
    const gate = deferred(), late = harness({ fetchOverride: (url, opts, normal, respond) => url === base + '/preview' ? gate.promise.then(respond) : normal() });
    await late.mount(); const opening = late.openSupplement(); await tick();
    if (mode === 'route') late.sandbox.beginRouteEpoch(); else late.sandbox.state.user = { uid: 99, role: 'admin' };
    gate.resolve(managementFixture()); await opening;
    check(!late.importRoot().textContent.includes('合成管理人员'), mode + ': delayed preview cannot restore private management identities');
    equal(late.supplementPosts().length, 0, mode + ': stale preview cannot commit');
    if (mode === 'route') check(late.supplementReads()[0].opts.signal.aborted, 'Route cleanup aborts supplement preview request');
    late.sandbox.beginRouteEpoch();
  }
  for (const mode of ['route', 'identity']) {
    const gate = deferred(), late = harness({ fetchOverride: (url, opts, normal, respond) => url === base + '/commit' ? gate.promise.then(respond) : normal() });
    await late.mount(); await late.openSupplement(); await late.reviewSupplement(); const committing = late.commit(); await tick();
    const reads = late.calls.filter(call => call.url === '/api/users').length, submitted = JSON.parse(late.supplementPosts()[0].opts.body);
    if (mode === 'route') late.sandbox.beginRouteEpoch(); else late.sandbox.state.user = { uid: 99, role: 'admin' };
    gate.resolve(support.fixtures.makeReceipt(managementFixture(), submitted.decisions)); await committing;
    equal(late.calls.filter(call => call.url === '/api/users').length, reads, mode + ': delayed receipt does not refresh another identity or route');
    check(!late.importRoot().textContent.includes('本批 2 人已导入'), mode + ': delayed receipt does not announce success in stale context');
    equal(late.supplementPosts().length, 1, mode + ': delayed receipt never repeats a submit');
    late.sandbox.beginRouteEpoch();
  }
  const lookupGate = deferred(), lookup = harness({ fetchOverride: (url, opts, normal, respond) => url === base + '/accounts/14' ? lookupGate.promise.then(respond) : normal() });
  await lookup.mount(); await lookup.openSupplement(); await lookup.reviewButton(0).onclick(); lookup.set('action', 'LINK_EXISTING'); lookup.set('accountId', '14');
  lookup.modal().querySelector('[data-import-lookup]').onclick(); await tick(); lookup.sandbox.closeModal(); await lookup.openImport(); await lookup.reviewButton(0).onclick(); const lookupSuccessor = lookup.modal();
  lookupGate.resolve({ accountId: 14, username: 'synthetic-14', name: '迟到的合成管理人员账号', status: 1, role: 'viewer', available: true, proof: 'LATE-MANAGEMENT-PROOF' }); await tick();
  check(lookup.modal() === lookupSuccessor && !lookup.modal().textContent.includes('迟到的合成管理人员账号'), 'Cancelled supplement lookup cannot populate a subsequent original-batch review');
  check(lookup.calls.find(call => call.url === base + '/accounts/14').opts.signal.aborted, 'Cancelling the original modal aborts supplement lookup');
  equal(lookup.supplementPosts().length, 0, 'Late account proof does not submit supplement'); lookup.sandbox.beginRouteEpoch();
  for (const status of [403, 503]) {
    const unavailable = harness({ fetchOverride: (url, opts, normal, respond) => url === base + '/preview' ? respond(null, status) : normal() });
    await unavailable.mount(); await unavailable.openSupplement();
    check(unavailable.importRoot().querySelector('[data-import-commit]').disabled && !unavailable.importRoot().textContent.includes('合成管理人员') && !unavailable.importRoot().textContent.includes('0 / 0'), status + ': unavailable supplement source remains unknown and blocked');
    check(unavailable.content.querySelector('#tbl').textContent.includes('synthetic-14'), status + ': original accounts stay maintainable');
    unavailable.sandbox.beginRouteEpoch();
  }
  const expired = harness({ fetchOverride: (url, opts, normal, respond) => url === base + '/commit' ? respond(null, 401) : normal() });
  await expired.mount(); await expired.openSupplement(); await expired.reviewSupplement(); await expired.commit();
  check(expired.invalidations === 1 && expired.sandbox.state.user === null && !expired.importRoot() && !expired.modal(), 'Expired session clears private supplement table and modal through original session handling');
  equal(expired.supplementPosts().length, 1, 'Expired session never retries supplement submission');
  for (const role of ['viewer', 'manager']) {
    const denied = harness({ role }); await denied.mount();
    check(!denied.content.querySelector('#user-management-supplement-open'), role + ': supplement entry is absent');
    equal(denied.supplementReads().length, 0, role + ': no private supplement read');
    denied.sandbox.beginRouteEpoch();
  }
  const failed = harness({ importFailure: true }); await failed.mount(); await failed.openSupplement();
  check(!failed.content.querySelector('#user-management-supplement-open').disabled && !failed.content.querySelector('#user-management-supplement-open').hidden && failed.content.querySelector('#tbl').textContent.includes('synthetic-14'), 'Supplement module failure retains retry and original account maintenance');
  failed.sandbox.beginRouteEpoch();
  console.log(JSON.stringify({ ok: true, suite: 'original users management supplement UI', checks, transport: 'synthetic', browser: false, database: false }));
})().catch(error => { console.error(error); process.exitCode = 1; });
