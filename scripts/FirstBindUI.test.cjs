'use strict';
// Executes the real candidate renderLogin/disposal and browser module against the established
// synthetic DOM/HTTP harness. No browser, server, DB, real account or mail is accessed.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const shared = path.resolve(__dirname, '..');
const candidate = path.resolve(__dirname, '..');
const candidateApp = fs.readFileSync(path.join(candidate, 'web/app.js'), 'utf8');
const candidateModule = fs.readFileSync(path.join(candidate, 'web/modules/notifications/login-verification.js'), 'utf8');
const oldSuite = fs.readFileSync(path.join(shared, 'scripts/IntegrationLoginVerificationUI.test.cjs'), 'utf8').replaceAll('20260923loginverify1', '20260924firstbind1');
const proxiedFs = new Proxy(fs, { get(target, key) {
  if (key !== 'readFileSync') return Reflect.get(target, key);
  return (file, ...args) => {
    const absolute = path.resolve(String(file));
    if (absolute === path.join(shared, 'web/app.js')) return candidateApp;
    if (absolute === path.join(shared, 'web/modules/notifications/login-verification.js')) return candidateModule;
    return target.readFileSync(file, ...args);
  };
} });
const harnessRequire = name => name === 'node:fs' ? proxiedFs : require(name);
const sandbox = { require: harnessRequire, __dirname: path.join(shared, 'scripts'), console, structuredClone, AbortController, DOMException, URL, URLSearchParams, Buffer, setImmediate };
const support = vm.runInNewContext(oldSuite.slice(0, oldSuite.indexOf('\n(async()=>{')) + '\n({harness});', sandbox);
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
let checks = 0, scenarios = 0;
const failures = [];
const check = (value, label) => { assert.ok(value, label); checks++; };
const equal = (actual, expected, label) => { assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected, label); checks++; };
const scenario = async (name, action) => { scenarios++; try { await action(); } catch (error) { failures.push({ name, error: error.stack }); } };
function harness({ override, normalLogin = false, moduleGate } = {}) {
  let enrollmentNo = 100, challengeNo = 200, expiry;
  const h = support.harness({ moduleGate, override: (url, opts, original, respond, x) => {
    const normal = () => {
      if (url === '/api/login') {
        if (normalLogin) return original();
        expiry = new Date(x.now + 600000).toISOString();
        return respond({ status: 'EMAIL_BIND_REQUIRED', enrollment_id: (++enrollmentNo).toString(16).padStart(64, '0'), expires_at: expiry });
      }
      if (url === '/api/login/email/bind/send' || url === '/api/login/email/bind/resend') return respond({ status: 'EMAIL_BIND_CODE_REQUIRED', challenge_id: (++challengeNo).toString(16).padStart(64, '0'), masked_email: '***@***', expires_at: expiry, resend_after: new Date(x.now + 60000).toISOString() });
      if (url === '/api/login/email/bind/verify') return respond(x.success());
      if (url === '/api/login/email/bind/cancel' || url === '/api/logout') return respond(null);
      return original();
    };
    return override ? override(url, opts, normal, respond, x) : normal();
  } });
  const decorate = () => {
    h.field('login-bind-step').hidden = h.field('login-bind-step').hasAttribute('hidden');
    h.field('login-bind-email').checkValidity = function () { return this.value.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.value.trim()); };
  };
  const mount = h.mount; h.mount = () => { mount(); decorate(); }; decorate();
  h.email = async (value = 'synthetic-owner@example.invalid') => { h.set('login-bind-email', value); return h.submit(); };
  h.bindingCancels = () => h.requests.filter(r => r.url === '/api/login/email/bind/cancel');
  h.proofCalls = () => h.requests.filter(r => !r.url.endsWith('/cancel'));
  return h;
}
function assertView(h, expected) {
  for (const [name, parent, input] of [['credentials', 'login-password-step', 'login-pwd'], ['fill-email', 'login-bind-step', 'login-bind-email'], ['code', 'login-email-step', 'login-code']]) {
    const shown = name === expected;
    equal(h.field(parent).hidden, !shown, name + ': visibility matches phase');
    equal(h.field(input).required, shown, name + ': required matches phase');
    equal(h.field(input).disabled, !shown, name + ': enabled matches phase');
  }
}
function privateCleared(h) {
  for (const id of ['login-pwd', 'login-bind-email', 'login-code']) equal(h.field(id).value, '', id + ' cleared');
  equal(h.intervals.size, 0, 'No pending timer remains');
  equal(h.layouts, 0, 'No unverified workspace entry');
  equal([...h.storage.entries()], [['token', 'OLD-UNUSED']], 'Pending proof never alters storage');
}
(async () => {
  await scenario('same V13 card, explicit phases, exact new routes and final workspace', async () => {
    const h = harness();
    check(h.field('login-bind-step').hidden && h.field('login-bind-email').disabled, 'First render keeps new input hidden and disabled');
    check(h.root.innerHTML.includes('orbit-panel') && h.root.innerHTML.includes('login-input'), 'Existing V13 card and field styles retained');
    await h.start(); assertView(h, 'fill-email'); equal(h.layouts, 0, 'Enrollment never authenticates');
    equal(h.field('login-pwd').value, '', 'Password removed before enrollment UI');
    check(h.field('login-resend').hidden && !h.field('login-back').disabled, 'Fill-email exposes return but no resend');
    equal([...h.storage.entries()], [['token', 'OLD-UNUSED']], 'Enrollment has no persistent state');
    await h.email('not-an-email'); equal(h.calls('/bind/send').length, 0, 'Bad local email sends nothing');
    await h.email('synthetic-owner@example.invalid'); assertView(h, 'code');
    const enrollmentId = '65'.padStart(64, '0');
    equal(JSON.parse(h.calls('/bind/send')[0].opts.body), { enrollment_id: enrollmentId, email: 'synthetic-owner@example.invalid' }, 'Send body binds only enrollment and own email');
    equal(h.field('login-bind-email').value, '', 'Candidate email clears after server accepts');
    check(!h.root.textContent.includes('synthetic-owner@example.invalid') && h.field('login-email-hint').textContent.includes('***@***'), 'Code phase only displays mask');
    await h.code('123'); equal(h.calls('/bind/verify').length, 0, 'Short code blocked');
    await h.resend(); equal(h.calls('/bind/resend').length, 0, 'Cooldown blocks direct callback');
    h.advance(60001); await h.resend();
    equal(JSON.parse(h.calls('/bind/resend')[0].opts.body), { challenge_id: 'c9'.padStart(64, '0') }, 'Binding resend sends only previous challenge');
    await h.code();
    equal(JSON.parse(h.calls('/bind/verify')[0].opts.body), { challenge_id: 'ca'.padStart(64, '0'), code: '123456' }, 'Binding verifies rotated challenge only');
    equal(h.layouts, 1, 'Only final response enters existing workspace');
    equal(h.bindingCancels().length, 0, 'Successful completion does not cancel completed enrollment');
    equal([...h.storage.entries()], [['yx_last_username', 'synthetic-login']], 'Final host remembers only existing username');
    check(h.requests.every(r => !/example.invalid|enrollment_id|challenge_id/.test(r.url)), 'No private fields in request URLs');
  });
  for (const phase of ['fill-email', 'code']) for (const exit of ['back', 'pagehide', 'destroy']) await scenario(phase + ' ' + exit + ' clears and cancels owned enrollment', async () => {
    const h = harness(); await h.start(); if (phase === 'code') await h.email();
    h.set('login-bind-email', 'draft@example.invalid'); h.set('login-code', '123456');
    h[exit](); await tick(); privateCleared(h);
    equal(JSON.parse(h.bindingCancels()[0].opts.body), { enrollment_id: '65'.padStart(64, '0') }, 'Cancel uses enrollment capability only');
    check(h.bindingCancels()[0].opts.keepalive, 'Page exit cancellation uses keepalive');
    equal(h.requests.filter(r => r.url === '/api/login/email/cancel').length, 0, 'Binding never cancels ordinary login challenge');
    check(h.field('login-bind-step').hidden && h.field('login-email-step').hidden, 'Private phases disappear');
  });
  await scenario('expired enrollment clears pending address and requires credentials', async () => {
    const h = harness(); await h.start(); h.set('login-bind-email', 'draft@example.invalid'); h.advance(600001);
    assertView(h, 'credentials'); privateCleared(h); equal(h.bindingCancels().length, 1, 'Expiry retires enrollment');
    check(h.field('login-err').textContent.includes('过期'), 'Expiry message is visible'); h.destroy();
  });
  for (const status of [400, 401, 409, 429, 503]) await scenario('binding verify HTTP ' + status + ' remains safe', async () => {
    const h = harness({ override: (url, opts, normal, respond) => url.endsWith('/bind/verify') ? respond(null, status) : normal() });
    await h.start(); await h.email(); await h.code();
    assertView(h, [401, 409, 503].includes(status) ? 'credentials' : 'code');
    equal(h.layouts, 0, 'Failure cannot authenticate'); equal(h.field('login-code').value, '', 'Submitted code is forgotten'); equal(h.invalidations, 0, 'Binding error never invalidates another session'); h.destroy();
  });
  for (const phase of ['login', 'send', 'resend']) await scenario('late ' + phase + ' success after pageleave retires capability', async () => {
    const gate = deferred(); const h = harness({ override: (url, opts, normal) => (phase === 'login' ? url === '/api/login' : url.endsWith('/bind/' + phase)) ? gate.promise.then(normal) : normal() });
    let pending;
    if (phase === 'login') pending = h.start();
    else { await h.start(); if (phase === 'resend') { await h.email(); h.advance(60001); pending = h.resend(); } else pending = h.email(); }
    await tick(); const request = h.proofCalls().at(-1); h.pagehide(); gate.resolve(); await pending; await tick();
    check(request.opts.signal.aborted, 'Pageleave aborts transport'); privateCleared(h);
    check(h.bindingCancels().length >= 1, 'Late enrollment or challenge is cancelled');
    equal(JSON.parse(h.bindingCancels().at(-1).opts.body).enrollment_id, '65'.padStart(64, '0'), 'Late cancellation cannot target another enrollment');
  });
  for (const stage of ['send', 'resend']) await scenario('return during ' + stage + ' then new login ignores old reply', async () => {
    const gate = deferred(); let held = false;
    const h = harness({ override: (url, opts, normal, respond, x) => {
      if (!held && url.endsWith('/bind/' + stage)) { held = true; return gate.promise.then(() => respond({ status: 'EMAIL_BIND_CODE_REQUIRED', challenge_id: 'a'.repeat(64), masked_email: '***@***', expires_at: new Date(x.now + 500000).toISOString(), resend_after: new Date(x.now + 30000).toISOString() })); }
      return normal();
    } });
    await h.start(); if (stage === 'resend') { await h.email(); h.advance(60001); }
    const pending = stage === 'send' ? h.email() : h.resend(); await tick(); h.back(); await h.start();
    assertView(h, 'fill-email'); gate.resolve(); await pending; assertView(h, 'fill-email');
    check(h.bindingCancels().every(r => JSON.parse(r.opts.body).enrollment_id === '65'.padStart(64, '0')), 'Old reply cancellation leaves newer enrollment untouched');
    await h.email(); equal(JSON.parse(h.calls('/bind/send').at(-1).opts.body).enrollment_id, '66'.padStart(64, '0'), 'New proof remains active'); h.destroy();
  });
  await scenario('late verification success cannot revive disposed workspace', async () => {
    const gate = deferred(); const h = harness({ override: (url, opts, normal) => url.endsWith('/bind/verify') ? gate.promise.then(normal) : normal() });
    await h.start(); await h.email(); const pending = h.code(); await tick();
    check(h.field('login-back').disabled, 'In-flight verify preserves original identity lock');
    h.back(); equal(h.bindingCancels().length, 0, 'Back callback cannot switch identity during verify');
    h.pagehide(); gate.resolve(); await pending; privateCleared(h); equal(h.bindingCancels().length, 1, 'Leaving retires binding proof');
  });
  const badEnrollments = [
    ['short id', x => ({ ...x, enrollment_id: 'bad' })], ['numeric expiry', x => ({ ...x, expires_at: 1 })],
    ['bad expiry', x => ({ ...x, expires_at: 'later' })], ['extra token', x => ({ ...x, token: 'SYNTHETIC' })],
    ['extra user', x => ({ ...x, user: { uid: 97 } })], ['purpose', x => ({ ...x, purpose: 'EMAIL_LOGIN' })],
    ['wrong status', x => ({ ...x, status: 'EMAIL_REQUIRED' })],
  ];
  for (const [label, change] of badEnrollments) await scenario('malformed enrollment ' + label, async () => {
    const h = harness({ override: (url, opts, normal, respond, x) => url === '/api/login' ? respond(change({ status: 'EMAIL_BIND_REQUIRED', enrollment_id: 'e'.repeat(64), expires_at: new Date(x.now + 600000).toISOString() })) : normal() });
    await h.start(); assertView(h, 'credentials'); privateCleared(h); equal(h.calls('/bind/send').length, 0, 'Malformed proof never sends'); h.destroy();
  });
  const badChallenges = [
    ['wrong purpose', x => ({ ...x, status: 'EMAIL_REQUIRED' })], ['raw email', x => ({ ...x, masked_email: 'raw@example.invalid' })],
    ['unchallenged final', () => ({ token: 'SYNTHETIC', user: { uid: 97, username: 'synthetic-login', role: 'viewer' } })],
    ['invalid id', x => ({ ...x, challenge_id: 'bad' })], ['extra enrollment', x => ({ ...x, enrollment_id: 'e'.repeat(64) })],
    ['extra token', x => ({ ...x, token: 'SYNTHETIC' })], ['extended expiry', x => ({ ...x, expires_at: '2099-01-01T00:00:00Z' })],
    ['resend after expiry', x => ({ ...x, resend_after: '2099-01-01T00:00:00Z' })],
  ];
  for (const [label, change] of badChallenges) await scenario('malformed send response ' + label, async () => {
    const h = harness({ override: (url, opts, normal, respond, x) => url.endsWith('/bind/send') ? respond(change({ status: 'EMAIL_BIND_CODE_REQUIRED', challenge_id: 'a'.repeat(64), masked_email: '***@***', expires_at: new Date(x.now + 500000).toISOString(), resend_after: new Date(x.now + 60000).toISOString() })) : normal() });
    await h.start(); await h.email(); assertView(h, 'credentials'); privateCleared(h);
    check(h.bindingCancels().length >= 1, 'Malformed reply cancels owned enrollment'); check(!h.root.textContent.includes('raw@example.invalid'), 'Untrusted raw email never rendered'); h.destroy();
  });
  await scenario('ordinary login rejects first-binding challenge and vice versa', async () => {
    const h = harness({ normalLogin: true, override: (url, opts, normal, respond, x) => url === '/api/login' ? respond({ ...x.challenge(), status: 'EMAIL_BIND_CODE_REQUIRED' }) : normal() });
    await h.start(); assertView(h, 'credentials'); equal(h.layouts, 0, 'Cross-purpose response cannot authenticate'); h.destroy();
  });
  await scenario('same challenge on binding resend is rejected and retired', async () => {
    const h = harness({ override: (url, opts, normal, respond, x) => url.endsWith('/bind/resend') ? respond({ status: 'EMAIL_BIND_CODE_REQUIRED', challenge_id: JSON.parse(opts.body).challenge_id, masked_email: '***@***', expires_at: new Date(x.now + 400000).toISOString(), resend_after: new Date(x.now + 60000).toISOString() }) : normal() });
    await h.start(); await h.email(); h.advance(60001); await h.resend(); assertView(h, 'credentials'); privateCleared(h); h.destroy();
  });
  await scenario('module-load navigation clears new email input without a mounted verifier', async () => {
    const gate = deferred(), h = harness({ moduleGate: gate }); const pending = h.start(); await tick();
    const oldEmail = h.field('login-bind-email'); oldEmail.value = 'draft@example.invalid'; h.mount(); gate.resolve(); await pending;
    equal(oldEmail.value, '', 'Original disposal clears candidate address even before module mounting'); equal(h.requests.length, 0, 'Late import never starts old login'); h.destroy();
  });
  await scenario('late decoded enrollment after pagehide is retired', async () => {
    const gate = deferred();
    const h = harness({ override: (url, opts, normal, respond, x) => url === '/api/login' ? { ok: true, status: 200, text: () => gate.promise } : normal() });
    const pending = h.start(); await tick(); h.pagehide();
    gate.resolve(JSON.stringify({ code: 0, data: { status: 'EMAIL_BIND_REQUIRED', enrollment_id: 'e'.repeat(64), expires_at: '2026-09-23T08:10:00Z' } })); await pending;
    privateCleared(h); equal(JSON.parse(h.bindingCancels().at(-1).opts.body).enrollment_id, 'e'.repeat(64), 'Late JSON proof cancelled');
  });
  await scenario('double send is locked and proof conflict requires credentials', async () => {
    const gate = deferred(); const h = harness({ override: (url, opts, normal, respond) => url.endsWith('/bind/send') ? gate.promise.then(() => respond(null, 409)) : normal() });
    await h.start(); const pending = h.email(); await tick(); await h.submit();
    equal(h.calls('/bind/send').length, 1, 'Double send cannot create multiple challenges');
    gate.resolve(); await pending; assertView(h, 'credentials'); privateCleared(h);
    check(!/占用|重复|其他账号/.test(h.field('login-err').textContent), 'Conflict message discloses no mailbox availability'); h.destroy();
  });
  await scenario('first-binding verify refuses every intermediate status', async () => {
    const h = harness({ override: (url, opts, normal, respond, x) => url.endsWith('/bind/verify') ? respond({ status: 'EMAIL_BIND_REQUIRED', enrollment_id: 'e'.repeat(64), expires_at: new Date(x.now + 600000).toISOString() }) : normal() });
    await h.start(); await h.email(); await h.code(); assertView(h, 'credentials'); privateCleared(h);
    check(h.bindingCancels().some(r => JSON.parse(r.opts.body).enrollment_id === 'e'.repeat(64)), 'Unexpected returned proof is cancelled'); h.destroy();
  });
  for (const stage of ['login', 'ordinary-verify', 'binding-verify']) for (const newIdentity of [false, true]) await scenario('late final ' + stage + (newIdentity ? ' preserves newer identity' : ' after pageleave'), async () => {
    const gate = deferred(), oldToken = 'SYNTHETIC-OLD-SESSION', newToken = 'SYNTHETIC-NEW-SESSION';
    const oldUser = { uid: 97, username: 'synthetic-old', role: 'viewer' }, newUser = { uid: 98, username: 'synthetic-new', role: 'viewer' };
    let oldPending = false, credentialRequests = 0, cookieToken = null;
    const revoked = [];
    const h = harness({ normalLogin: stage === 'ordinary-verify', override: (url, opts, normal, respond) => {
      if (url === '/api/logout') {
        const target = opts.credentials === 'omit' ? opts.headers['X-Token'] : cookieToken || opts.headers['X-Token'];
        revoked.push(target); if (opts.credentials !== 'omit') cookieToken = null;
        return respond(null);
      }
      if (url === '/api/login') {
        credentialRequests++;
        if (credentialRequests > 1) { cookieToken = newToken; return respond({ token: newToken, user: newUser }); }
      }
      if (!oldPending && (stage === 'login' ? url === '/api/login' : url === (stage === 'ordinary-verify' ? '/api/login/email/verify' : '/api/login/email/bind/verify'))) {
        oldPending = true; return gate.promise.then(() => respond({ token: oldToken, user: oldUser }));
      }
      return normal();
    } });
    let pending;
    if (stage === 'login') pending = h.start();
    else { await h.start(); if (stage === 'binding-verify') await h.email(); pending = h.code(); }
    await tick(); h.pagehide();
    if (newIdentity) { h.mount(); await h.start(); equal(h.layouts, 1, 'New identity reaches workspace once'); }
    gate.resolve(); await pending; await tick();
    const logouts = h.requests.filter(r => r.url === '/api/logout'); equal(logouts.length, 1, 'Exactly one targeted late-session logout');
    const opts = logouts[0].opts;
    equal(opts.headers, { 'Content-Type': 'application/json', 'X-Token': oldToken }, 'Only old final token authorizes targeted revocation');
    equal({ method: opts.method, credentials: opts.credentials, mode: opts.mode, cache: opts.cache, redirect: opts.redirect, body: opts.body, keepalive: opts.keepalive }, { method: 'POST', credentials: 'omit', mode: 'same-origin', cache: 'no-store', redirect: 'error', body: '{}', keepalive: true }, 'Revocation omits cookies and ignores clearing Set-Cookie');
    check(!Object.hasOwn(opts, 'signal'), 'Revocation is independent of disposed controller');
    equal(revoked, [oldToken], 'Only stale session/device identity is revoked');
    equal(h.layouts, newIdentity ? 1 : 0, 'Late final never revives workspace');
    if (newIdentity) { equal(h.sandbox.state.user.uid, newUser.uid, 'New workspace identity survives'); equal(cookieToken, newToken, 'Current cookie is not sent or cleared'); }
    check(!h.requests.some(r => r.url.includes(oldToken)), 'Late token never enters URL');
  });
  check(!/localStorage|sessionStorage|console\.|location\.|indexedDB/.test(candidateModule), 'Auth module never writes storage, URL, DB or log');
  check(candidateApp.lastIndexOf('  (async function boot()') > 0, 'Existing boot boundary found');
  equal(candidateApp.slice(candidateApp.lastIndexOf('  (async function boot()')), fs.readFileSync(path.join(shared, 'web/app.js'), 'utf8').slice(fs.readFileSync(path.join(shared, 'web/app.js'), 'utf8').lastIndexOf('  (async function boot()')), 'Completed trusted-device boot remains byte-identical');
  console.log(JSON.stringify({ ok: !failures.length, suite: 'R26 first-login binding UI', scenarios, checks, realBrowser: false, network: false, database: false, smtp: false }));
  if (failures.length) { for (const failure of failures) console.error(failure.name + '\n' + failure.error); process.exitCode = 1; }
})().catch(error => { console.error(error); process.exitCode = 1; });
