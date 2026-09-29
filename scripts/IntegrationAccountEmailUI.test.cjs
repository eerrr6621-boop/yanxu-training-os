'use strict';
// Real original users page/bindings/modal/form/table/API plus email component; synthetic DOM and HTTP only.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const original = fs.readFileSync(path.join(__dirname, 'IntegrationAccountBindingsUI.test.cjs'), 'utf8');
const supportText = original.slice(0, original.indexOf('(async () => {')).replace('pages.replace(', 'pages.replaceAll(').replace('"await import(\'/modules/identity/"', '"await import(\'/modules/"').replace('"await loadIdentityModule(\'/modules/identity/"', '"await loadIdentityModule(\'/modules/"');
const support = vm.runInNewContext(supportText + '\n({ harness, NodeStub });', { require, __dirname, console, structuredClone, AbortController, setImmediate });
support.NodeStub.prototype.checkValidity = () => true; // Browser-native input validation is covered by the actual-SPA suite.
const component = fs.readFileSync(path.join(__dirname, '../web/modules/notifications/account-email-preparation.js'), 'utf8');
const tick = () => new Promise(yes => setImmediate(yes));
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
let checks = 0;
const check = (value, label) => { assert.ok(value, label); checks++; };
const equal = (actual, expected, label) => { assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected, label); checks++; };
const row = (revision, email, actor_user_id = 11) => ({ revision, email, status: email === null ? 'MISSING' : 'PENDING_VERIFICATION', actor_user_id, recorded_at: '2026-09-24T01:02:03Z' });
const view = (user_id = 13, revision = 0, email = null, duplicate_email = false) => ({ user_id, revision, email, status: email === null ? 'MISSING' : 'PENDING_VERIFICATION', history: revision ? [row(revision, email)] : [], can_save: true, duplicate_email });
function harness({ initial = view(), role = 'admin', moduleWait, moduleFailure, fetchOverride } = {}) {
  let remote = structuredClone(initial), requestSequence = 0, controller, host;
  const h = support.harness({ fetchOverride: (url, opts, normal, respond) => {
    const fallback = () => {
      if (url.startsWith('/api/account-email-preparation?')) return respond(remote);
      if (url === '/api/account-email-preparation') {
        const body = JSON.parse(opts.body);
        if (body.expected_revision !== remote.revision) return respond(null, 409);
        const email = body.email.trim() || null;
        if (email !== remote.email) { const previous = remote; remote = view(body.user_id, previous.revision + 1, email); remote.history = [...previous.history, row(remote.revision, email)]; }
        return respond(remote);
      }
      return normal(url, opts);
    };
    return fetchOverride ? fetchOverride(url, opts, fallback, respond, { get: () => remote, set: value => { remote = value; } }) : fallback();
  } });
  h.sandbox.state.user.role = role; h.sandbox.crypto.randomUUID = () => '00000000-0000-4000-8000-' + String(++requestSequence).padStart(12, '0');
  vm.runInContext('{ ' + component.replace('export function createAccountEmailPreparation', 'function createAccountEmailPreparation') + ';globalThis.emailPreparationUnderTest=createAccountEmailPreparation; }', h.sandbox);
  const loader = h.sandbox.loadIdentityModule;
  h.sandbox.loadIdentityModule = async url => {
    if (!url.includes('/account-email-preparation.js?')) return loader(url);
    h.imports.push(url); if (moduleWait) await moduleWait.promise; if (moduleFailure) throw Error('SYNTHETIC module unavailable');
    return { createAccountEmailPreparation(context) { host = context; controller = h.sandbox.emailPreparationUnderTest(context); return controller; } };
  };
  h.open = (id = 13) => h.modules[0].host.actions.find(action => action.l === '登记邮箱').onClick(h.users.find(user => user.id === id)); h.control = key => h.modal()?.querySelector('[data-email-' + key + ']');
  h.email = () => h.modal()?.querySelector('[data-k="email"]');
  h.edit = async email => { h.email().value = email; h.email().oninput?.(); await tick(); };
  h.click = async key => { const button = h.control(key); check(button && !button.disabled, key + ' is enabled'); await button.onclick(); await tick(); };
  h.writes = () => h.calls.filter(call => call.url === '/api/account-email-preparation'); h.reads = () => h.calls.filter(call => call.url.startsWith('/api/account-email-preparation?'));
  h.controller = () => controller; h.host = () => host; h.remote = () => remote;
  return h;
}
(async () => {
  const h = harness(); await h.mount();
  equal(h.reads().length, 0, 'Rendering users never reads an email for every row');
  check(h.actions(13).some(action => action.textContent === '登记邮箱'), 'Disabled viewer has an explicit original-row action');
  check(!h.actions(12).some(action => action.textContent === '登记邮箱') && !h.actions(11).some(action => action.textContent === '登记邮箱'), 'Enabled viewers and administrators have no email preparation action');
  await h.open(); const mask = h.modal();
  equal(h.reads()[0].url, '/api/account-email-preparation?user_id=13', 'GET URL includes only positive target ID');
  equal(h.modal().querySelector('#modal-title').textContent, '登记邮箱', 'Component uses original modal');
  check(h.control('target').textContent.includes('合成账号 13') && h.control('target').textContent.includes('synthetic-13') && h.control('target').textContent.includes('内部账号 #13'), 'Target name and system account are explicit without claiming verified email ownership');
  check(h.control('state').textContent.includes('未登记') && h.control('history').textContent.includes('尚无登记记录') && h.control('notice').textContent === '', 'No candidate address/history is invented and successful read needs no repeated notice');
  await h.edit('candidate@example.invalid'); await h.click('save');
  const first = JSON.parse(h.writes()[0].opts.body);
  equal(Object.keys(first).sort(), ['email', 'expected_revision', 'request_id', 'user_id'], 'Write has only preparation contract fields');
  check(/^[0-9a-f-]{36}$/.test(first.request_id) && first.expected_revision === 0 && first.user_id === 13, 'First save binds UUID, target and loaded revision');
  check(h.modal() === mask && h.renders === 0 && h.control('state').textContent.includes('待本人验证'), 'Saving updates same original modal without page reconstruction or implying verification');
  check(h.control('history').textContent.includes('candidate@example.invalid') && h.control('history').textContent.includes('账号 #11'), 'Server history displays candidate and recording account');
  equal(h.control('notice').textContent, '已保存，待本人验证。', 'Success states candidate status concisely without claiming verification');
  await h.click('save'); equal(h.remote().revision, 1, 'Server no-change response with same revision is acknowledged normally');
  check(!h.control('retry') || h.control('retry').disabled, 'No-op acknowledgement is not treated as an unknown write');
  await h.click('clear'); await h.click('save'); equal(JSON.parse(h.writes().at(-1).opts.body).email, '', 'Explicit clear submits empty string');
  check(h.control('state').textContent.includes('未登记') && h.control('history').textContent.includes('已清除') && h.remote().history.length === 2, 'Clear keeps the immutable modification history');
  check(!h.calls.some(call => /send|activate|resetpwd/.test(call.url)), 'Email registration never sends mail or changes account credentials');

  const duplicate = harness({ initial: view(13, 1, 'same@example.invalid', true) }); await duplicate.mount(); await duplicate.open();
  check(duplicate.control('duplicate').textContent.includes('该邮箱已用于其他登记，请核对。') && !duplicate.control('duplicate').textContent.includes('账号 #'), 'Duplicate warning does not identify another person or account');
  const markup = '<img src=x onerror=alert(1)>', evil = harness({ initial: view(13, 1, markup) }); evil.users.find(user => user.id === 13).name = markup; await evil.mount(); await evil.open();
  check(!evil.modal().querySelector('img, script') && evil.control('history').textContent.includes(markup), 'Untrusted history/email strings remain escaped text');
  check(evil.control('target').textContent.includes(markup) && !evil.control('target').querySelector('img'), 'Row display label is escaped inside the original target note');

  for (const status of [401, 403, 404, 409]) {
    const failedRead = harness({ fetchOverride: (url, opts, normal, respond) => url.startsWith('/api/account-email-preparation?') ? respond(null, status) : normal() }); await failedRead.mount(); await failedRead.open();
    if ([401, 403].includes(status)) check(!failedRead.modal(), status + ': unauthorized GET closes and clears private modal');
    else check(failedRead.email().value === '' && failedRead.control('save').disabled && !failedRead.control('history').textContent, status + ': ineligible target clears data and stays unwritable');
    equal(failedRead.writes().length, 0, status + ': opening never writes an ineligible target');
  }
  for (const status of [401, 403, 404]) {
    const revoked = harness({ initial: view(13, 1, 'private@example.invalid'), fetchOverride: (url, opts, normal, respond) => url === '/api/account-email-preparation' ? respond(null, status) : normal() }); await revoked.mount(); await revoked.open(); await revoked.edit('new@example.invalid'); await revoked.click('save');
    if ([401, 403].includes(status)) check(!revoked.modal(), status + ': write revocation removes email and history');
    else check(revoked.email().value === '' && revoked.control('save').disabled && !revoked.control('history').textContent, '404 write eligibility loss removes prior data');
  }
  let conflicted = false;
  const cas = harness({ initial: view(13, 1, 'old@example.invalid'), fetchOverride: (url, opts, normal, respond, remote) => {
    if (url === '/api/account-email-preparation' && !conflicted) { conflicted = true; remote.set(view(13, 2, 'server@example.invalid')); return respond(null, 409); } return normal();
  } }); await cas.mount(); await cas.open(); await cas.edit('stale-draft@example.invalid'); await cas.click('save');
  check(cas.email().value === '' && cas.control('save').disabled && !cas.control('history').textContent.includes('old@example.invalid'), 'CAS conflict clears previous draft and old private history');
  equal(cas.reads().length, 2, 'CAS conflict reads fresh server eligibility and revision exactly once');
  check(cas.control('notice').textContent.includes('请重新填写') && cas.control('history').textContent.includes('server@example.invalid'), 'Conflict requires human refill after showing newly fetched history');
  equal(cas.writes().length, 1, 'Conflict never automatically resubmits stale body');
  await cas.edit('refilled@example.invalid'); await cas.click('save'); equal(JSON.parse(cas.writes()[1].opts.body).expected_revision, 2, 'Human refill uses refreshed CAS revision');
  check(JSON.parse(cas.writes()[0].opts.body).request_id !== JSON.parse(cas.writes()[1].opts.body).request_id, 'Resolved conflict is a new explicit operation');

  for (const failure of ['network', 500]) {
    let firstAttempt = true;
    const uncertain = harness({ fetchOverride: (url, opts, normal, respond) => { if (url === '/api/account-email-preparation' && firstAttempt) { firstAttempt = false; if (failure === 'network') throw Error('SYNTHETIC lost acknowledgement'); return respond(null, failure); } return normal(); } });
    await uncertain.mount(); await uncertain.open(); await uncertain.edit('original@example.invalid'); await uncertain.click('save');
    check(uncertain.email().disabled && uncertain.control('save').disabled && !uncertain.control('retry').disabled, failure + ': unknown result exposes explicit retry and blocks a new draft operation');
    equal(uncertain.writes().length, 1, failure + ': no automatic POST retry');
    uncertain.email().value = 'tampered-after-request@example.invalid'; await uncertain.click('retry');
    equal(JSON.parse(uncertain.writes()[0].opts.body), JSON.parse(uncertain.writes()[1].opts.body), failure + ': manual retry reuses same UUID and exact original email despite later field changes');
    equal(uncertain.email().value, 'original@example.invalid', failure + ': retry result reflects the acknowledged original request');
  }
  let invalid = true;
  const badEmail = harness({ fetchOverride: (url, opts, normal, respond) => url === '/api/account-email-preparation' && invalid ? respond(null, 400) : normal() }); await badEmail.mount(); await badEmail.open(); await badEmail.edit('bad@example.invalid'); await badEmail.click('save');
  check(!badEmail.email().disabled && !badEmail.control('save').disabled && badEmail.control('retry').disabled, '400 releases request for explicit format correction'); invalid = false; await badEmail.edit('fixed@example.invalid'); await badEmail.click('save');
  check(JSON.parse(badEmail.writes()[0].opts.body).request_id !== JSON.parse(badEmail.writes()[1].opts.body).request_id, 'Corrected email gets a new request ID');

  const pending = deferred(), slow = harness({ fetchOverride: (url, opts, normal, respond) => url === '/api/account-email-preparation' ? pending.promise.then(respond) : normal() }); await slow.mount(); await slow.open(); await slow.edit('slow@example.invalid');
  const save = slow.control('save').onclick(); await tick(); await slow.control('save').onclick(); equal(slow.writes().length, 1, 'Repeated callbacks while POST is pending cannot duplicate submission');
  slow.sandbox.closeModal(); check(slow.modal() && slow.email().disabled, 'Original modal stays locked during unresolved in-flight save');
  slow.sandbox.beginRouteEpoch(); pending.resolve(view(13, 1, 'slow@example.invalid')); await save;
  check(!slow.modal() && slow.writes()[0].opts.signal.aborted, 'Forced route cleanup aborts request and discards late write result');
  for (const mode of ['identity', 'role']) {
    const waiting = deferred(), changed = harness({ fetchOverride: (url, opts, normal, respond) => url.startsWith('/api/account-email-preparation?') ? waiting.promise.then(respond) : normal() }); await changed.mount(); const opening = changed.open(); await tick();
    if (mode === 'identity') changed.sandbox.state.user = { uid: 88, role: 'admin' }; else changed.sandbox.state.user.role = 'viewer';
    waiting.resolve(view(13, 1, 'old-person@example.invalid')); await opening; check(!changed.modal(), mode + ': identity loss clears old modal instead of presenting late private data');
  }
  const delayed = deferred(), switched = harness({ fetchOverride: (url, opts, normal, respond) => url.includes('user_id=13') ? delayed.promise.then(respond) : url.includes('user_id=15') ? respond(view(15, 1, 'correct-target@example.invalid')) : normal() }); await switched.mount(); const opening = switched.open(); await tick(); switched.sandbox.closeModal();
  check(!switched.modal() && switched.reads()[0].opts.signal.aborted, 'Ordinary close cancels an in-flight read without locking navigation');
  await switched.controller().open(15); delayed.resolve(view(13, 2, 'wrong-target@example.invalid')); await opening;
  equal(switched.email().value, 'correct-target@example.invalid', 'Late prior-target response cannot overwrite a successor target modal');
  switched.sandbox.closeModal(); switched.sandbox.openModal('已有手工窗口', '<p>保留内容</p>', { noFoot: true }); const successor = switched.modal(); switched.controller().destroy();
  check(switched.modal() === successor, 'Destroy never closes another component modal');
  const guard = harness(); await guard.mount(); guard.sandbox.openModal('原未保存总结', '<p>不可替换</p>', { noFoot: true }); const guardedMask = guard.modal(); guardedMask._beforeClose = () => false; await guard.open();
  check(guard.modal() === guardedMask && guard.reads().length === 0 && !guard.imports.some(url => url.includes('account-email')), 'Existing modal prevents loading, GET and replacement by email action');
  for (const mode of ['route', 'identity']) {
    const gate = deferred(), loading = harness({ moduleWait: gate }); await loading.mount(); const open = loading.open(); await tick();
    if (mode === 'route') loading.sandbox.beginRouteEpoch(); else loading.sandbox.state.user = { uid: 99, role: 'admin' };
    gate.resolve(); await open; check(!loading.modal() && !loading.reads().length, mode + ': late dynamic module cannot open stale target');
  }
  for (const role of ['manager', 'viewer']) { const ordinary = harness({ role }); await ordinary.mount(); check(!ordinary.actions(13).some(action => action.textContent === '登记邮箱') && ordinary.reads().length === 0, role + ': no private action or preparation requests'); }
  const unavailable = harness({ moduleFailure: true }); await unavailable.mount(); await unavailable.open(); check(!unavailable.modal() && unavailable.content.querySelector('#tbl').textContent.includes('synthetic-13'), 'Failed optional module leaves original accounts usable');
  check(h.calls.every(call => !call.url.includes('example.invalid')) && h.toasts.every(item => !String(item.message).includes('example.invalid')), 'Email never enters URLs or toasts');
  console.log(JSON.stringify({ ok: true, suite: 'original account email preparation host integration', checks, transport: 'synthetic', browser: false, database: false }));
})().catch(error => { console.error(error); process.exitCode = 1; });
