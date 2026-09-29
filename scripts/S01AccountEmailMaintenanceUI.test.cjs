'use strict';
// Candidate pageUsers is transformed only in memory. The shared app and old preparation stay read-only.
// Reuses the existing original-UI harness: real pageUsers, modal, form, table and API; synthetic DOM/HTTP.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const shared = process.env.S01_SHARED_APP || path.resolve(__dirname, '..');
const sharedScripts = path.join(shared, 'scripts');
const appPath = path.join(shared, 'web/app.js');
const originalApp = fs.readFileSync(appPath, 'utf8');
const candidateApp = originalApp;
const proxiedFs = new Proxy(fs, { get(target, key) {
  if (key !== 'readFileSync') return Reflect.get(target, key);
  return (name, ...args) => path.resolve(String(name)) === appPath ? candidateApp : target.readFileSync(name, ...args);
} });
const harnessRequire = name => name === 'node:fs' ? proxiedFs : require(name);
const emailSuite = fs.readFileSync(path.join(sharedScripts, 'IntegrationAccountEmailUI.test.cjs'), 'utf8');
const supportPrefix = emailSuite.slice(0, emailSuite.indexOf('const component ='));
assert.ok(supportPrefix.includes('support.NodeStub.prototype.checkValidity'), 'Expected original email harness prefix');
const support = vm.runInNewContext(supportPrefix + '\n support;', { require: harnessRequire, __dirname: sharedScripts, console, structuredClone, AbortController, setImmediate });
const component = fs.readFileSync(path.join(__dirname, '../web/modules/notifications/account-email-maintenance.js'), 'utf8');
const oldComponent = fs.readFileSync(path.join(shared, 'web/modules/notifications/account-email-preparation.js'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const clone = value => JSON.parse(JSON.stringify(value));
let checks = 0, scenarios = 0;
const failures = [];
const check = (value, label) => { assert.ok(value, label); checks++; };
const equal = (actual, expected, label) => { assert.deepEqual(clone(actual), clone(expected), label); checks++; };
const scenario = async (name, test) => { scenarios++; try { await test(); } catch (error) { failures.push({ scenario: name, message: error.stack }); } };
const row = (revision, email, actor_user_id = 11) => ({ revision, email, status: email === null ? 'MISSING' : 'PENDING_VERIFICATION', actor_user_id, recorded_at: '2026-09-23T01:02:03Z' });
function view(user_id = 13, emails = [], overrides = {}) {
  const email = emails.at(-1) ?? null;
  return { user_id, username: `synthetic-${user_id}`, name: `合成账号 ${user_id}`, account_kind: 'EXISTING', revision: emails.length,
    email, status: email === null ? 'MISSING' : 'PENDING_VERIFICATION', history: emails.map((email, i) => row(i + 1, email)),
    can_save: true, duplicate_email: false, reauthentication_required: false, ...overrides };
}
function harness({ initial = view(), role = 'admin', moduleWait, moduleFailure, fetchOverride } = {}) {
  let remote = structuredClone(initial), requestSequence = 0, controller, host;
  const h = support.harness({ fetchOverride: (url, opts, normal, respond) => {
    const fallback = () => {
      if (url.startsWith('/api/account-email-maintenance?')) return respond(remote);
      if (url === '/api/account-email-maintenance') {
        const body = JSON.parse(opts.body);
        if (body.expected_revision !== remote.revision || body.confirmed_username !== remote.username || body.purpose !== 'LOGIN_VERIFICATION') return respond(null, 409);
        const email = body.email.trim() || null;
        if (email !== remote.email) remote = { ...remote, revision: remote.revision + 1, email, status: email === null ? 'MISSING' : 'PENDING_VERIFICATION', history: [...remote.history, row(remote.revision + 1, email)] };
        return respond(remote);
      }
      if (url.startsWith('/api/account-email-preparation?')) return respond({ user_id: 13, revision: 0, email: null, status: 'MISSING', history: [], can_save: true, duplicate_email: false });
      return normal(url, opts);
    };
    return fetchOverride ? fetchOverride(url, opts, fallback, respond, { get: () => remote, set: value => { remote = value; } }) : fallback();
  } });
  h.sandbox.state.user.role = role;
  h.sandbox.crypto.randomUUID = () => '00000000-0000-4000-8000-' + String(++requestSequence).padStart(12, '0');
  vm.runInContext('{ ' + component.replace('export function createAccountEmailMaintenance', 'function createAccountEmailMaintenance') + ';globalThis.emailMaintenanceUnderTest=createAccountEmailMaintenance; }', h.sandbox);
  vm.runInContext('{ ' + oldComponent.replace('export function createAccountEmailPreparation', 'function createAccountEmailPreparation') + ';globalThis.emailPreparationUnderTest=createAccountEmailPreparation; }', h.sandbox);
  const loader = h.sandbox.loadIdentityModule;
  h.sandbox.loadIdentityModule = async url => {
    if (url.includes('/account-email-preparation.js?')) return { createAccountEmailPreparation: h.sandbox.emailPreparationUnderTest };
    if (!url.includes('/account-email-maintenance.js?')) return loader(url);
    h.imports.push(url); if (moduleWait) await moduleWait.promise; if (moduleFailure) throw Error('SYNTHETIC optional module unavailable');
    return { createAccountEmailMaintenance(context) { host = context; controller = h.sandbox.emailMaintenanceUnderTest(context); return controller; } };
  };
  // Exercise the original navigation guard; rendering and history remain synthetic boundaries.
  h.navigations = 0;
  h.sandbox.sceneBridge = { setRoute() {} }; h.sandbox.writeRouteToUrl = () => {};
  h.sandbox.renderLayout = () => { h.navigations++; h.sandbox.beginRouteEpoch(); };
  h.sandbox.state.filters = {};
  vm.runInContext(originalApp.slice(originalApp.indexOf('  function navigateTo('), originalApp.indexOf('  function routeUrl(')), h.sandbox);
  h.open = (id = initial.user_id) => h.modules[0].host.actions.find(action => action.l === '维护核验邮箱').onClick(h.users.find(user => user.id === id));
  h.control = key => h.modal()?.querySelector('[data-email-maintenance-' + key + ']');
  h.field = key => h.modal()?.querySelector('[data-k="' + key + '"]');
  h.email = () => h.field('email');
  h.editField = async (key, value) => { const input = h.field(key); check(Boolean(input), 'Original field exists: ' + key); input.value = value; input.oninput?.(); await tick(); };
  h.edit = email => h.editField('email', email);
  h.confirm = async (username = remote.username) => { await h.editField('confirmed_username', username); h.control('purpose').checked = true; h.control('purpose').onchange(); await tick(); };
  h.click = async key => { const button = h.control(key); check(button && !button.disabled, key + ' is enabled'); await button.onclick(); await tick(); };
  h.writes = () => h.calls.filter(call => call.url === '/api/account-email-maintenance');
  h.reads = () => h.calls.filter(call => call.url.startsWith('/api/account-email-maintenance?'));
  h.controller = () => controller; h.host = () => host; h.remote = () => remote;
  h.cleanup = () => h.sandbox.beginRouteEpoch();
  return h;
}
function cleared(mask, label) {
  check(mask.querySelector('[data-k="email"]').value === '' && mask.querySelector('[data-k="confirmed_username"]').value === '', label + ': private fields cleared');
  check(['target', 'state', 'duplicate', 'history'].every(key => mask.querySelector('[data-email-maintenance-' + key + ']').textContent === ''), label + ': target and private history cleared');
  check(!mask.querySelector('[data-email-maintenance-purpose]').checked, label + ': purpose confirmation cleared');
}

(async () => {
  await scenario('original entries, exact contract, confirmation, history and clear', async () => {
    const h = harness(); await h.mount();
    equal(h.reads().length, 0, 'Rendering users does not collect per-account email data');
    for (const id of [11, 12, 13, 14]) check(h.actions(id).some(action => action.textContent === '维护核验邮箱'), id + ': active, inactive and own accounts have explicit maintenance action');
    check(h.actions(13).some(action => action.textContent === '登记邮箱'), 'Original inactive viewer preparation entry retained');
    for (const id of [11, 12, 14]) check(!h.actions(id).some(action => action.textContent === '登记邮箱'), id + ': original preparation eligibility unchanged');
    const oldAction = h.modules[0].host.actions.find(action => action.l === '登记邮箱'); await oldAction.onClick(h.users.find(user => user.id === 13));
    check(h.modal().querySelector('#modal-title').textContent === '登记邮箱' && h.calls.some(call => call.url === '/api/account-email-preparation?user_id=13'), 'Old action still loads unchanged original component and endpoint');
    h.sandbox.closeModal(); await h.open(); const mask = h.modal();
    equal(h.reads()[0].url, '/api/account-email-maintenance?user_id=13', 'GET contains only positive target ID');
    equal(mask.querySelector('#modal-title').textContent, '维护核验邮箱', 'Uses original modal');
    check(h.control('target').textContent.includes('合成账号 13') && h.control('target').textContent.includes('synthetic-13') && h.control('target').textContent.includes('内部账号 #13'), 'Server-returned name, username and numeric identity shown');
    check(h.control('state').textContent.includes('未登记') && h.control('history').textContent.includes('尚无修改记录'), 'Absent email/history is not invented');
    check(h.field('confirmed_username').value === '' && !h.control('purpose').checked && h.control('save').disabled, 'Confirmation is never prefilled or prechecked');
    await h.edit('candidate@example.invalid'); await h.control('save').onclick(); equal(h.writes().length, 0, 'Direct callback cannot save without typed username and purpose');
    await h.editField('confirmed_username', 'synthetic-13'); await h.control('save').onclick(); equal(h.writes().length, 0, 'Username alone cannot authorize purpose');
    for (const wrong of ['SYNTHETIC-13', ' synthetic-13', 'synthetic-13 ', '合成账号 13']) {
      await h.confirm(wrong); check(h.control('save').disabled, 'Exact current username required: ' + JSON.stringify(wrong)); await h.control('save').onclick();
    }
    equal(h.writes().length, 0, 'Wrong or trimmed identity confirmations never POST');
    await h.confirm(); await h.click('save');
    const first = JSON.parse(h.writes()[0].opts.body);
    equal(Object.keys(first).sort(), ['confirmed_username', 'email', 'expected_revision', 'purpose', 'request_id', 'user_id'], 'POST has exactly six maintenance fields');
    equal({ ...first, request_id: undefined }, { user_id: 13, expected_revision: 0, email: 'candidate@example.invalid', confirmed_username: 'synthetic-13', purpose: 'LOGIN_VERIFICATION' }, 'POST binds real target, reviewed CAS and explicit purpose');
    check(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(first.request_id), 'Request ID is a canonical synthetic UUID');
    check(h.modal() === mask && h.renders === 0 && h.control('state').textContent.includes('待本人验证'), 'Success updates same original modal and stays unverified');
    check(h.control('history').textContent.includes('candidate@example.invalid') && h.control('history').textContent.includes('账号 #11') && h.control('history').textContent.includes('待本人验证'), 'Candidate, actor and unverified history are visible');
    check(!h.control('purpose').checked && h.field('confirmed_username').value === '' && h.control('save').disabled, 'Each acknowledged operation requires fresh confirmation');
    await h.confirm(); await h.click('save'); equal(h.remote().revision, 1, 'Same-value save accepts unchanged revision');
    check(h.control('retry').disabled && !h.email().disabled, 'No-op acknowledgment releases pending state');
    await h.confirm(); await h.edit('other@example.invalid'); check(!h.control('purpose').checked && h.control('save').disabled, 'Editing email revokes prior purpose confirmation');
    await h.click('clear'); check(h.email().value === '' && h.field('confirmed_username').value === '' && !h.control('purpose').checked, 'Clear empties draft and both confirmations');
    await h.confirm(); await h.click('save'); equal(JSON.parse(h.writes().at(-1).opts.body).email, '', 'Explicit confirmed clear submits empty email');
    check(h.control('state').textContent.includes('未登记') && h.control('history').textContent.includes('已清除') && h.remote().history.length === 2, 'Clear retains previous email and clearing history');
    check(!h.calls.some(call => /send|activate|resetpwd/.test(call.url)), 'Maintenance never sends mail or changes credentials');
    check([...h.reads(), ...h.writes()].every(call => call.opts.credentials === 'same-origin' && call.opts.headers['Content-Type'] === 'application/json' && call.opts.signal), 'Maintenance retains original authenticated API settings and cancellation signals');
    check(h.calls.every(call => !call.url.includes('example.invalid')) && h.toasts.every(item => !String(item.message).includes('example.invalid')), 'Email remains out of URLs and toasts');
    h.cleanup(); cleared(mask, 'route cleanup');
  });

  for (const account_kind of ['IMPORTED', 'EXISTING']) await scenario(account_kind + ' source and server identity', async () => {
    const h = harness({ initial: view(12, ['previous@example.invalid'], { account_kind, username: 'renamed-on-server', name: '服务端当前姓名', duplicate_email: true }) });
    await h.mount(); await h.open();
    check(h.control('target').textContent.includes('renamed-on-server') && h.control('target').textContent.includes('服务端当前姓名'), 'Current target identity comes from GET, not stale account row');
    await h.confirm('synthetic-12'); check(h.control('save').disabled, 'Stale row username cannot confirm server-renamed account');
    check(h.control('duplicate').textContent.includes('重复') && !h.control('duplicate').textContent.includes('账号 #'), 'Duplicate flag reveals no other account identity');
    await h.confirm(); await h.click('save'); equal(JSON.parse(h.writes()[0].opts.body).confirmed_username, 'renamed-on-server', 'Exact current username is sent');
    check(!Object.hasOwn(JSON.parse(h.writes()[0].opts.body), 'account_kind'), 'Client cannot choose source kind'); h.cleanup();
  });

  await scenario('XSS-safe target, candidate and historical values', async () => {
    const markup = '<img src=x onerror=alert(1)>', h = harness({ initial: view(13, [markup], { username: '<svg onload=x>', name: markup }) });
    await h.mount(); await h.open();
    check(!h.modal().querySelector('img, script, svg'), 'Target and historical strings do not create executable markup');
    check(h.control('target').textContent.includes(markup) && h.control('target').textContent.includes('<svg onload=x>'), 'Server identity strings remain escaped text');
    check(h.control('history').textContent.includes(markup) && h.email().value === markup, 'Untrusted history and field retain text without HTML interpolation'); h.cleanup();
  });

  for (const conflict of ['CAS', 'duplicate']) await scenario(conflict + ' conflict clears old draft and requires new UUID', async () => {
    let attempted = false;
    const h = harness({ initial: view(13, ['old@example.invalid']), fetchOverride: (url, opts, normal, respond, remote) => {
      if (url === '/api/account-email-maintenance' && !attempted) { attempted = true; remote.set(view(13, ['old@example.invalid', 'server@example.invalid'], { duplicate_email: conflict === 'duplicate' })); return respond(null, 409); }
      return normal();
    } }); await h.mount(); await h.open(); await h.edit('stale-draft@example.invalid'); await h.confirm(); await h.click('save');
    check(h.email().value === '' && h.field('confirmed_username').value === '' && !h.control('purpose').checked && h.control('save').disabled, '409 clears draft and all confirmations');
    equal(h.reads().length, 2, '409 makes exactly one fresh GET');
    check(h.control('history').textContent.includes('server@example.invalid') && h.control('notice').textContent.includes('重新填写'), 'Fresh current history is shown with explicit refill instruction');
    equal(h.writes().length, 1, '409 never auto-replays old body');
    await h.control('save').onclick(); equal(h.writes().length, 1, 'Direct callback cannot resubmit discarded conflict draft');
    await h.edit('reviewed@example.invalid'); await h.confirm(); await h.click('save');
    const bodies = h.writes().map(call => JSON.parse(call.opts.body));
    equal(bodies[1].expected_revision, 2, 'Fresh explicit action binds refreshed revision');
    check(bodies[0].request_id !== bodies[1].request_id, 'Resolved conflict requires new request UUID'); h.cleanup();
  });

  for (const failure of ['network', 500, 503, 'bad-ack']) await scenario(failure + ' unknown outcome locks draft and retains immutable request', async () => {
    let first = true;
    const h = harness({ fetchOverride: (url, opts, normal, respond) => {
      if (url === '/api/account-email-maintenance' && first) { first = false; if (failure === 'network') throw Error('SYNTHETIC lost acknowledgment'); if (failure === 'bad-ack') return respond({ user_id: 13 }); return respond(null, failure); }
      return normal();
    } }); await h.mount(); await h.open(); await h.edit('original@example.invalid'); await h.confirm(); await h.click('save');
    const mask = h.modal(), firstBody = JSON.parse(h.writes()[0].opts.body);
    check(h.email().disabled && h.field('confirmed_username').disabled && h.control('purpose').disabled && h.control('save').disabled && h.control('clear').disabled && !h.control('retry').disabled, 'Unknown outcome permits only same-request retry or GET');
    equal(h.writes().length, 1, 'Unknown outcome has no automatic POST retry');
    equal(h.sandbox.closeModal(), false, 'Ordinary close is blocked while request is unresolved');
    h.sandbox.navigateTo('dashboard'); check(h.sandbox.state.page === 'users' && h.navigations === 0 && h.modal() === mask, 'Original navigation guard preserves unresolved request');
    await h.click('read');
    check(h.control('save').disabled && !h.control('retry').disabled && h.email().disabled && h.modal().dataset.locked === 'true', 'GET cannot release pending request even if current state is readable');
    check(h.control('notice').textContent.includes('仍需') && h.control('notice').textContent.includes('重试上次保存'), 'GET does not claim unknown save outcome resolved');
    h.email().value = 'tampered@example.invalid'; h.field('confirmed_username').value = 'another-user'; h.control('purpose').checked = false;
    await h.control('save').onclick(); await h.control('clear').onclick(); equal(h.writes().length, 1, 'Disabled/direct save or clear cannot replace pending operation');
    await h.click('retry'); equal(JSON.parse(h.writes()[1].opts.body), firstBody, 'Retry preserves all six immutable fields including original UUID');
    equal(h.email().value, 'original@example.invalid', 'Acknowledged retry shows original submitted address');
    check(h.control('retry').disabled && !h.email().disabled && h.modal().dataset.locked === 'false', 'Only acknowledged retry releases pending lock');
    h.sandbox.navigateTo('dashboard'); check(h.sandbox.state.page === 'dashboard' && h.navigations === 1 && !h.modal(), 'Acknowledgment permits original navigation'); cleared(mask, 'acknowledged navigation');
  });

  await scenario('failed GET during unknown result never releases immutable pending body', async () => {
    let reads = 0, posts = 0;
    const h = harness({ fetchOverride: (url, opts, normal, respond) => {
      if (url.startsWith('/api/account-email-maintenance?') && ++reads === 2) return respond(null, 503);
      if (url === '/api/account-email-maintenance' && ++posts === 1) return respond(null, 503);
      return normal();
    } }); await h.mount(); await h.open(); await h.edit('private@example.invalid'); await h.confirm(); await h.click('save'); await h.click('read');
    check(h.email().value === '' && h.control('save').disabled && !h.control('retry').disabled && h.modal().dataset.locked === 'true', 'Unreadable GET clears visible private data but pending survives');
    await h.click('retry'); equal(JSON.parse(h.writes()[1].opts.body), JSON.parse(h.writes()[0].opts.body), 'Failed reread cannot alter immutable retry'); h.cleanup();
  });
  await scenario('GET matching a committed unknown write still needs same-request replay acknowledgment', async () => {
    let first = true, committedBody;
    const h = harness({ fetchOverride: (url, opts, normal, respond, remote) => {
      if (url === '/api/account-email-maintenance') {
        if (first) { first = false; committedBody = JSON.parse(opts.body); normal(); throw Error('SYNTHETIC committed response lost'); }
        if (opts.body === JSON.stringify(committedBody)) return respond(remote.get());
      }
      return normal();
    } }); await h.mount(); await h.open(); await h.edit('committed@example.invalid'); await h.confirm(); await h.click('save'); await h.click('read');
    check(h.control('state').textContent.includes('1') && h.control('history').textContent.includes('committed@example.invalid'), 'GET may show server-committed current address and revision');
    check(h.control('save').disabled && !h.control('retry').disabled && h.sandbox.closeModal() === false, 'Matching current GET does not prove UUID acknowledgment or release pending lock');
    await h.click('retry'); equal(JSON.parse(h.writes()[1].opts.body), committedBody, 'Committed unknown result resolved only by exact replay request');
    check(h.control('retry').disabled && !h.email().disabled && h.remote().revision === 1, 'Replay acknowledgment releases lock without invented additional revision'); h.cleanup();
  });

  for (const status of [400, 415, 422]) await scenario(status + ' known rejection requires correction and fresh confirmation', async () => {
    let first = true;
    const h = harness({ fetchOverride: (url, opts, normal, respond) => { if (url === '/api/account-email-maintenance' && first) { first = false; return respond(null, status); } return normal(); } });
    await h.mount(); await h.open(); await h.edit('bad@example.invalid'); await h.confirm(); await h.click('save');
    check(!h.email().disabled && h.control('save').disabled && h.control('retry').disabled && h.field('confirmed_username').value === '' && !h.control('purpose').checked, 'Known client rejection releases request but removes confirmation');
    await h.control('save').onclick(); equal(h.writes().length, 1, 'Rejected draft cannot immediately resubmit');
    await h.edit('corrected@example.invalid'); await h.confirm(); await h.click('save');
    check(JSON.parse(h.writes()[0].opts.body).request_id !== JSON.parse(h.writes()[1].opts.body).request_id, 'Corrected explicit request gets fresh UUID'); h.cleanup();
  });

  for (const method of ['GET', 'POST']) for (const status of [401, 403, 404, 409, 503]) await scenario(method + ' ' + status + ' private-state handling', async () => {
    const h = harness({ initial: view(13, ['private@example.invalid']), fetchOverride: (url, opts, normal, respond) => {
      if (method === 'GET' ? url.startsWith('/api/account-email-maintenance?') : url === '/api/account-email-maintenance') return respond(null, status);
      return normal();
    } }); await h.mount(); await h.open(); const mask = h.modal();
    if (method === 'POST') { await h.edit('new@example.invalid'); await h.confirm(); await h.click('save'); }
    if ([401, 403].includes(status)) { check(!h.modal(), 'Permission loss closes private modal'); if (mask) cleared(mask, status + ' rejection'); }
    else if (method === 'GET' || status === 404) check(h.email().value === '' && h.control('save').disabled && !h.control('history').textContent, 'Failed eligibility/read clears private data and blocks save');
    else if (status === 409) check(h.email().value === '' && h.control('save').disabled && h.field('confirmed_username').value === '', 'Conflict requires refreshed human draft');
    else check(h.control('save').disabled && !h.control('retry').disabled, 'Server failure retains unresolved POST');
    if (method === 'GET') equal(h.writes().length, 0, 'Opening never writes failed target');
    if (status === 401) equal(h.invalidations, 1, 'Original API handles unauthorized session invalidation once'); h.cleanup();
  });

  const invalidDtos = [
    ['wrong target', v => { v.user_id = 14; }], ['string target', v => { v.user_id = '13'; }], ['missing username', v => { delete v.username; }], ['empty username', v => { v.username = ''; }],
    ['invalid name', v => { v.name = 0; }], ['invalid source', v => { v.account_kind = 'CLIENT'; }], ['negative revision', v => { v.revision = -1; }], ['fraction revision', v => { v.revision = 1.1; }],
    ['unsafe revision', v => { v.revision = Number.MAX_SAFE_INTEGER + 1; }], ['verified status', v => { v.status = 'VERIFIED'; }], ['missing with address', v => { v.status = 'MISSING'; }],
    ['pending without address', v => { v.email = ''; }], ['cannot save', v => { v.can_save = false; }], ['string can_save', v => { v.can_save = 'true'; }],
    ['missing duplicate boolean', v => { delete v.duplicate_email; }], ['string reauthentication', v => { v.reauthentication_required = 'false'; }],
    ['other user reauthentication', v => { v.reauthentication_required = true; }], ['no history', v => { v.history = null; }], ['gap history', v => { v.history[0].revision = 2; }],
    ['short history', v => { v.history = []; }], ['history verified', v => { v.history[0].status = 'VERIFIED'; }], ['invalid actor', v => { v.history[0].actor_user_id = 0; }],
    ['string actor', v => { v.history[0].actor_user_id = '11'; }], ['invalid timestamp type', v => { v.history[0].recorded_at = null; }], ['mismatched latest email', v => { v.history[0].email = 'different@example.invalid'; }],
  ];
  for (const [label, mutate] of invalidDtos) await scenario('malformed GET DTO: ' + label, async () => {
    const initial = view(13, ['private@example.invalid']); mutate(initial); const h = harness({ initial }); await h.mount(); await h.open(13);
    check(h.email().value === '' && h.field('confirmed_username').value === '' && h.control('save').disabled && !h.control('history').textContent && !h.control('target').textContent, 'Malformed DTO never becomes writable private state');
    equal(h.writes().length, 0, 'Malformed GET never POSTs'); h.cleanup();
  });
  await scenario('revision zero cannot assert a current candidate with no audit history', async () => {
    const h = harness({ initial: view(13, [], { email: 'orphan@example.invalid', status: 'PENDING_VERIFICATION' }) }); await h.mount(); await h.open();
    check(h.email().value === '' && h.control('save').disabled && !h.control('target').textContent, 'Revision zero with present email is inconsistent and must remain unreadable'); h.cleanup();
  });
  for (const [label, mutate] of invalidDtos) await scenario('malformed POST DTO: ' + label, async () => {
    let first = true;
    const h = harness({ fetchOverride: (url, opts, normal, respond) => {
      if (url === '/api/account-email-maintenance' && first) { first = false; const response = view(13, ['candidate@example.invalid']); mutate(response); return respond(response); }
      return normal();
    } }); await h.mount(); await h.open(); await h.edit('candidate@example.invalid'); await h.confirm(); await h.click('save');
    check(h.email().disabled && h.control('save').disabled && !h.control('retry').disabled && h.control('notice').textContent.includes('尚未确认'), 'Malformed acknowledgment retains unresolved request without false success');
    const body = JSON.parse(h.writes()[0].opts.body); await h.click('retry'); equal(JSON.parse(h.writes()[1].opts.body), body, 'Malformed acknowledgment retries same immutable request'); h.cleanup();
  });
  await scenario('older successful acknowledgment cannot move revision backward', async () => {
    const h = harness({ initial: view(13, ['first@example.invalid', 'current@example.invalid']), fetchOverride: (url, opts, normal, respond) => url === '/api/account-email-maintenance' ? respond(view(13, ['older@example.invalid'])) : normal() });
    await h.mount(); await h.open(); await h.edit('new@example.invalid'); await h.confirm(); await h.click('save');
    check(h.email().disabled && !h.control('retry').disabled && h.control('state').textContent.includes('2'), 'A structurally valid older revision remains an unknown acknowledgment'); h.cleanup();
  });

  await scenario('self maintenance goes through original invalidation and login flow', async () => {
    const h = harness({ initial: view(11), fetchOverride: (url, opts, normal, respond) => url === '/api/account-email-maintenance' ? respond(view(11, [JSON.parse(opts.body).email], { reauthentication_required: true })) : normal() });
    let removedToken = false, loginRenders = 0;
    h.sandbox.toastTimer = null;
    h.sandbox.localStorage.removeItem = key => { if (key === 'token') removedToken = true; };
    h.sandbox.renderLogin = () => { loginRenders++; h.content.innerHTML = '<p>原登录流程</p>'; };
    vm.runInContext(originalApp.slice(originalApp.indexOf('  function invalidateSession()'), originalApp.indexOf('  const sceneBridge =')), h.sandbox);
    await h.mount(); await h.open(); const mask = h.modal(); await h.edit('self@example.invalid'); await h.confirm(); await h.click('save');
    check(h.sandbox.state.user === null && removedToken && loginRenders === 1 && !h.modal(), 'Self-change invokes original session clearing, token removal and login render');
    check(h.content.textContent === '原登录流程' && h.toasts.some(item => item.message === '邮箱已保存，请重新登录完成核验。'), 'Host displays successful save and returns to original verification login');
    cleared(mask, 'self invalidation'); equal(h.writes().length, 1, 'Self invalidation never retries committed write');
  });
  await scenario('self GET cannot demand reauthentication', async () => {
    const h = harness({ initial: view(11, ['private@example.invalid'], { reauthentication_required: true }) }); await h.mount(); await h.open();
    check(h.control('save').disabled && !h.control('target').textContent && h.invalidations === 0, 'GET reauthentication flag is rejected without false logout/success'); h.cleanup();
  });
  await scenario('no-op self acknowledgment keeps session', async () => {
    const h = harness({ initial: view(11, ['self@example.invalid']) }); await h.mount(); await h.open(); await h.confirm(); await h.click('save');
    check(h.invalidations === 0 && h.sandbox.state.user?.uid === 11 && h.modal(), 'Server false reauthentication flag keeps valid no-op self session'); h.cleanup();
  });

  await scenario('in-flight POST double submit and forced route cleanup', async () => {
    const pending = deferred(), h = harness({ fetchOverride: (url, opts, normal, respond) => url === '/api/account-email-maintenance' ? pending.promise.then(respond) : normal() });
    await h.mount(); await h.open(); await h.edit('slow@example.invalid'); await h.confirm(); const mask = h.modal();
    const saving = h.control('save').onclick(); await tick(); await h.control('save').onclick(); await h.control('retry').onclick(); equal(h.writes().length, 1, 'Concurrent callbacks cannot duplicate unresolved POST');
    check(h.sandbox.closeModal() === false && h.modal() === mask, 'In-flight POST blocks ordinary modal close');
    h.sandbox.navigateTo('dashboard'); check(h.sandbox.state.page === 'users', 'In-flight POST blocks ordinary navigation');
    h.cleanup(); h.sandbox.state.page = 'dashboard'; h.content.innerHTML = '后续页面'; cleared(mask, 'forced route cleanup');
    check(!h.modal() && h.writes()[0].opts.signal.aborted, 'Forced cleanup aborts owned request');
    const calls = h.calls.length; pending.resolve(view(13, ['slow@example.invalid'])); await saving;
    check(h.content.textContent === '后续页面' && h.calls.length === calls && h.toasts.every(item => !String(item.message).includes('已保存')), 'Late write cannot mutate next route, refetch or toast success');
  });
  for (const method of ['GET', 'POST']) for (const mode of ['identity', 'role', 'route']) await scenario(method + ' pending ' + mode + ' race clears private data', async () => {
    const gate = deferred(), h = harness({ initial: view(13, ['private@example.invalid']), fetchOverride: (url, opts, normal, respond) => (method === 'GET' ? url.startsWith('/api/account-email-maintenance?') : url === '/api/account-email-maintenance') ? gate.promise.then(respond) : normal() });
    await h.mount(); let waiting = h.open(); if (method === 'POST') { await waiting; await h.edit('new@example.invalid'); await h.confirm(); waiting = h.control('save').onclick(); }
    await tick(); const mask = h.modal();
    if (mode === 'identity') h.sandbox.state.user = { uid: 88, role: 'admin' }; else if (mode === 'role') h.sandbox.state.user.role = 'viewer'; else h.cleanup();
    gate.resolve(view(13, ['late-private@example.invalid'])); await waiting;
    check(!h.modal(), 'Stale ' + mode + ' response cannot retain private modal'); cleared(mask, mode + ' response cleanup');
    check(h.toasts.every(item => !String(item.message).includes('已保存')), 'Stale response cannot announce success');
  });
  await scenario('late old GET cannot overwrite successor target or modal', async () => {
    const gate = deferred(), h = harness({ fetchOverride: (url, opts, normal, respond) => url.includes('user_id=13') ? gate.promise.then(respond) : url.includes('user_id=12') ? respond(view(12, ['correct@example.invalid'])) : normal() });
    await h.mount(); const opening = h.open(); await tick(); const oldMask = h.modal();
    check(h.sandbox.closeModal() === true && h.reads()[0].opts.signal.aborted, 'Ordinary close may cancel pending read'); cleared(oldMask, 'read close');
    await h.controller().open(12); gate.resolve(view(13, ['wrong@example.invalid'])); await opening;
    equal(h.email().value, 'correct@example.invalid', 'Old target response cannot overwrite successor');
    h.sandbox.closeModal(); const successor = h.sandbox.openModal('后续手工窗口', '<p>保留</p>', { noFoot: true }); h.controller().destroy();
    check(h.modal() === successor, 'Destroy only closes owned modal'); h.sandbox.closeModal(); h.cleanup();
  });
  for (const mode of ['route', 'identity', 'role']) await scenario('delayed module ' + mode + ' guard', async () => {
    const gate = deferred(), h = harness({ moduleWait: gate }); await h.mount(); const opening = h.open(); await tick();
    if (mode === 'route') h.cleanup(); else if (mode === 'identity') h.sandbox.state.user = { uid: 88, role: 'admin' }; else h.sandbox.state.user.role = 'viewer';
    gate.resolve(); await opening; check(!h.modal() && h.reads().length === 0, 'Late import cannot create stale private modal or GET'); h.cleanup();
  });
  await scenario('existing modal and invalid row guards', async () => {
    const h = harness(); await h.mount(); const mask = h.sandbox.openModal('已有窗口', '<p>保留</p>', { noFoot: true }); mask._beforeClose = () => false; await h.open();
    check(h.modal() === mask && h.reads().length === 0 && !h.imports.some(url => url.includes('account-email-maintenance')), 'Existing modal prevents import, request and replacement');
    h.sandbox.closeModal(true);
    const action = h.modules[0].host.actions.find(item => item.l === '维护核验邮箱');
    for (const value of [{ id: 0, status: 1 }, { id: -1, status: 1 }, { id: 1.2, status: 1 }, { id: Number.MAX_SAFE_INTEGER + 1, status: 1 }, { id: 13, status: 2 }]) await action.onClick(value);
    check(!h.modal() && h.reads().length === 0, 'Invalid IDs and unsupported status cannot open endpoint'); h.cleanup();
  });
  for (const role of ['manager', 'viewer']) await scenario(role + ' cannot access private maintenance', async () => {
    const h = harness({ role }); await h.mount(); check(!h.actions(13).some(action => action.textContent === '维护核验邮箱' || action.textContent === '登记邮箱'), 'Non-system admin has neither private email action');
    await h.open(); check(h.reads().length === 0 && !h.modal(), 'Direct stale row callback cannot bypass role guard'); h.cleanup();
  });
  await scenario('optional module load failure preserves original accounts', async () => {
    const h = harness({ moduleFailure: true }); await h.mount(); await h.open();
    check(!h.modal() && h.reads().length === 0 && h.content.querySelector('#tbl').textContent.includes('synthetic-13'), 'Failure leaves original users usable with no private request');
    check(h.toasts.some(item => String(item.message).includes('暂时无法打开')), 'Optional load failure gives concise retry feedback'); h.cleanup();
  });
  const result = { ok: failures.length === 0, suite: 'S01 original account email maintenance UI integration', scenarios, checks, transport: 'synthetic', browser: false, database: false, smtp: false };
  console.log(JSON.stringify(result));
  if (failures.length) { for (const failure of failures) console.error(failure.scenario + '\n' + failure.message); process.exitCode = 1; }
})().catch(error => { console.error(error); process.exitCode = 1; });
