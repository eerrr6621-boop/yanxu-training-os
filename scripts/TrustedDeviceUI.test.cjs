'use strict';
// Synthetic fetch, Web Locks and clock only; no browser, server, storage, SMTP or network.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '../web/modules/notifications/trusted-device.js'), 'utf8');
const USER = { uid: 11, username: 'synthetic-admin', name: '合成用户', role: 'admin', roleName: '系统管理员' };
const freshUser = () => ({ ...USER });
const response = (status = 200, data = freshUser(), code = 0) => ({ status, json: async () => ({ code, data }) });
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const tick = () => new Promise(resolve => setImmediate(resolve));
let checks = 0, scenarios = 0;
const failures = [];
function check(value, label) { assert.ok(value, label); checks++; }
function equal(actual, expected, label) { assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected, label); checks++; }
async function scenario(name, action) { scenarios++; try { await action(); } catch (error) { failures.push({ name, error: error.stack }); } }
function runtime({ transport, defaultLocks } = {}) {
  let now = 0, nextId = 0;
  const timers = new Map(), calls = [], forbidden = [];
  const forbid = name => { forbidden.push(name); throw Error('Forbidden UI side effect: ' + name); };
  const sandbox = {
    AbortController, navigator: { locks: defaultLocks },
    setTimeout(callback, ms) { const id = ++nextId; timers.set(id, { callback, due: now + ms }); return id; },
    clearTimeout(id) { timers.delete(id); },
    fetch: (url, options) => { calls.push({ url, options }); return transport ? transport(url, options, calls.length) : Promise.resolve(response(401)); },
    console: new Proxy({}, { get: (_, key) => () => forbid('console.' + String(key)) }),
  };
  for (const name of ['localStorage', 'sessionStorage', 'document', 'indexedDB', 'caches']) Object.defineProperty(sandbox, name, { get: () => forbid(name) });
  vm.createContext(sandbox);
  vm.runInContext(source.replace('export async function restoreInitialSession', 'async function restoreInitialSession') + '\nglobalThis.run = restoreInitialSession;', sandbox);
  return {
    run: options => sandbox.run(options), calls, forbidden, timers,
    async advance(ms) {
      const target = now + ms;
      while (true) {
        const due = [...timers].filter(([, timer]) => timer.due <= target).sort((a, b) => a[1].due - b[1].due)[0];
        if (!due) break;
        now = due[1].due; timers.delete(due[0]); due[1].callback(); await tick();
      }
      now = target; await tick();
    },
  };
}
function lockManager() {
  const queue = [], calls = []; let held = false;
  function drain() {
    if (held || !queue.length) return;
    const item = queue.shift();
    if (item.options.signal.aborted) { item.reject(Object.assign(Error('cancelled'), { name: 'AbortError' })); drain(); return; }
    held = true; item.held = true;
    Promise.resolve().then(() => item.callback({ name: item.name, mode: 'exclusive' })).then(item.resolve, item.reject).finally(() => {
      held = false; item.options.signal.removeEventListener('abort', item.onAbort); drain();
    });
  }
  return { calls, request(name, options, callback) {
    calls.push({ name, options });
    return new Promise((resolve, reject) => {
      const item = { name, options, callback, resolve, reject, held: false };
      item.onAbort = () => { if (item.held) return; const index = queue.indexOf(item); if (index !== -1) queue.splice(index, 1); reject(Object.assign(Error('cancelled'), { name: 'AbortError' })); };
      options.signal.addEventListener('abort', item.onAbort, { once: true }); queue.push(item); drain();
    });
  } };
}
function terminal(h, label) {
  equal(h.forbidden, [], label + ': no secret persistence, DOM or logging');
  equal(h.timers.size, 0, label + ': no timer survives completion');
  check(h.calls.every(call => call.options.signal?.aborted), label + ': completed operation cancels remaining response work');
}

(async () => {
  await scenario('existing short session bypasses lock and device endpoint', async () => {
    let locks = 0; const h = runtime({ transport: () => response(), defaultLocks: { request() { locks++; throw Error('unneeded lock'); } } });
    equal(await h.run(), USER, 'Valid GET returns current user');
    equal(h.calls.map(call => call.url), ['/api/me'], 'Current short session needs only GET /me'); equal(locks, 0, 'No lock needed for existing session');
    const options = h.calls[0].options;
    equal({ method: options.method, credentials: options.credentials, mode: options.mode, cache: options.cache, redirect: options.redirect }, { method: 'GET', credentials: 'same-origin', mode: 'same-origin', cache: 'no-store', redirect: 'error' }, 'Raw initial GET is cookie-authenticated, uncached and same-origin');
    check(!Object.hasOwn(options, 'body') && !Object.hasOwn(options.headers, 'Authorization'), 'GET has no token header or body'); terminal(h, 'existing session');
  });
  for (const role of ['admin', 'manager', 'viewer']) await scenario('valid role ' + role, async () => {
    const user = { ...USER, role, name: '', roleName: '' }, h = runtime({ transport: () => response(200, user) });
    equal(await h.run(), user, 'Minimal existing user supports allowed role and empty display strings'); terminal(h, role);
  });
  await scenario('no Web Locks performs one strict restore with no retry', async () => {
    const h = runtime({ transport: (url, options) => url === '/api/me' ? response(401) : response(200, { token: 'SYNTHETIC-UNUSED-TOKEN', user: freshUser() }) });
    equal(await h.run(), USER, 'One restore returns only user');
    equal(h.calls.map(call => [call.url, call.options.method]), [['/api/me', 'GET'], ['/api/login/device/restore', 'POST']], 'No-lock path sends one strict POST');
    const options = h.calls[1].options;
    equal(options.body, '{}', 'Restore body is exact empty JSON'); equal(options.headers['Content-Type'], 'application/json', 'Restore declares JSON');
    check(options.credentials === 'same-origin' && options.cache === 'no-store' && options.mode === 'same-origin' && options.redirect === 'error', 'Restore retains same-origin no-store settings'); terminal(h, 'no locks');
  });
  await scenario('single tab locks and checks current session again', async () => {
    const locks = lockManager(), h = runtime({ defaultLocks: locks, transport: url => url === '/api/me' ? response(401) : response(200, { user: freshUser() }) });
    equal(await h.run(), USER, 'Locked restore returns valid user');
    equal(h.calls.map(call => call.url), ['/api/me', '/api/me', '/api/login/device/restore'], 'GET runs again inside lock before single restore');
    equal(locks.calls.map(call => [call.name, call.options.mode]), [['yanxu-login-device-restore-v1', 'exclusive']], 'Lock name and mode are fixed across tabs'); terminal(h, 'single locked tab');
  });
  await scenario('second tab reuses first tab short session without second rotation', async () => {
    const locks = lockManager(); let active = false, posts = 0;
    const transport = url => { if (url === '/api/me') return response(active ? 200 : 401); posts++; active = true; return response(200, { token: 'SYNTHETIC-UNUSED-TOKEN', user: freshUser() }); };
    const a = runtime({ defaultLocks: locks, transport }), b = runtime({ defaultLocks: locks, transport });
    const results = await Promise.all([a.run(), b.run()]); equal(results, [USER, USER], 'Both tabs obtain current valid user');
    equal(posts, 1, 'Two concurrent tabs perform one rotation'); equal(locks.calls.length, 2, 'Both expired-session tabs use same serialized lock');
    equal(a.calls.filter(call => call.url === '/api/me').length + b.calls.filter(call => call.url === '/api/me').length, 4, 'Both tabs recheck short session inside lock');
    terminal(a, 'tab A'); terminal(b, 'tab B');
  });
  await scenario('lock wait may find a session established by another tab', async () => {
    const h = runtime({ defaultLocks: lockManager(), transport: (url, opts, number) => response(number === 1 ? 401 : 200) });
    equal(await h.run(), USER, 'Valid locked GET short-circuits restore'); equal(h.calls.length, 2, 'No POST when another tab has established session'); terminal(h, 'other tab');
  });
  for (const status of [400, 403, 404, 409, 429, 500, 503, 204, 302]) await scenario('initial HTTP ' + status + ' never restores', async () => {
    const h = runtime({ transport: () => response(status) }); equal(await h.run(), null, 'Initial non-401 failure falls back to ordinary login'); equal(h.calls.length, 1, 'Only HTTP401 can start device recovery'); terminal(h, 'HTTP ' + status);
  });
  for (const status of [400, 401, 403, 404, 409, 429, 500, 503]) await scenario('failed/disabled device HTTP ' + status, async () => {
    const h = runtime({ transport: url => response(url === '/api/me' ? 401 : status) }); equal(await h.run(), null, 'Failed device recovery falls back to ordinary login');
    equal(h.calls.filter(call => call.options.method === 'POST').length, 1, 'Failure never retries strict restore'); terminal(h, 'device ' + status);
  });
  for (const stage of ['initial', 'locked', 'restore']) for (const failure of ['network', 'malformed-json', 'nonzero-envelope', 'no-envelope', 'no-json']) await scenario(stage + ' ' + failure + ' returns null', async () => {
    const locks = stage === 'locked' ? lockManager() : undefined;
    const h = runtime({ defaultLocks: locks, transport: (url, options, number) => {
      if ((stage === 'locked' && number === 1) || (stage === 'restore' && url === '/api/me')) return response(401);
      if (failure === 'network') throw Error('synthetic network failure');
      if (failure === 'malformed-json') return { status: 200, json: () => Promise.reject(Error('synthetic invalid JSON')) };
      if (failure === 'no-envelope') return { status: 200, json: async () => null };
      if (failure === 'no-json') return { status: 200 };
      return response(200, freshUser(), 401);
    } });
    equal(await h.run(), null, 'Failure returns ordinary login result');
    equal(h.calls.filter(call => call.options.method === 'POST').length, stage === 'restore' ? 1 : 0, 'Failure does not trigger a new restore or retry'); terminal(h, stage + failure);
  });
  const invalidUsers = [
    ['null', () => null], ['array', () => []], ['uid zero', u => ({ ...u, uid: 0 })], ['uid negative', u => ({ ...u, uid: -1 })], ['uid fraction', u => ({ ...u, uid: 1.1 })],
    ['uid string', u => ({ ...u, uid: '11' })], ['uid unsafe', u => ({ ...u, uid: Number.MAX_SAFE_INTEGER + 1 })], ['uid NaN', u => ({ ...u, uid: NaN })],
    ['empty username', u => ({ ...u, username: '' })], ['space username', u => ({ ...u, username: ' \t' })], ['missing username', u => ({ ...u, username: undefined })],
    ['null name', u => ({ ...u, name: null })], ['number name', u => ({ ...u, name: 1 })], ['unsupported role', u => ({ ...u, role: 'superadmin' })],
    ['wrong role case', u => ({ ...u, role: 'ADMIN' })], ['missing roleName', u => ({ ...u, roleName: undefined })], ['null roleName', u => ({ ...u, roleName: null })],
  ];
  for (const stage of ['initial', 'locked', 'restore']) for (const [label, invalid] of invalidUsers) await scenario(stage + ' invalid DTO ' + label, async () => {
    const user = invalid(freshUser()), h = runtime({ defaultLocks: stage === 'locked' ? lockManager() : undefined, transport: (url, opts, number) => {
      if ((stage === 'locked' && number === 1) || (stage === 'restore' && url === '/api/me')) return response(401);
      return response(200, stage === 'restore' ? { token: 'SYNTHETIC-UNUSED-TOKEN', user } : user);
    } });
    equal(await h.run(), null, 'Invalid user DTO never reaches UI'); equal(h.calls.filter(call => call.options.method === 'POST').length, stage === 'restore' ? 1 : 0, 'Invalid successful DTO cannot initiate another restoration'); terminal(h, label);
  });
  await scenario('only five user fields returned and token is never inspected', async () => {
    const user = { ...USER, token: 'SYNTHETIC-SECRET-EXTRA', device: 'SYNTHETIC-SECRET-EXTRA' }, data = { user };
    Object.defineProperty(data, 'token', { get() { throw Error('Response token must not be accessed'); } });
    const h = runtime({ transport: url => url === '/api/me' ? response(401) : response(200, data) });
    equal(await h.run(), USER, 'Projection strips extra user secret-like fields'); terminal(h, 'secret projection');
  });
  await scenario('abort before invocation sends no requests and accesses no lock', async () => {
    const controller = new AbortController(); controller.abort(); const h = runtime({ defaultLocks: { request() { throw Error('Unexpected lock'); } } });
    equal(await h.run({ signal: controller.signal }), null, 'Already-aborted boot returns null'); equal(h.calls.length, 0, 'Already-aborted boot has no transport'); terminal(h, 'early abort');
  });
  for (const stage of ['initial-fetch', 'initial-json', 'locked-fetch', 'locked-json', 'restore-fetch', 'restore-json']) for (const cause of ['abort', 'timeout']) await scenario(stage + ' ' + cause + ' blocks late continuation', async () => {
    const gate = deferred(), controller = new AbortController(); let suspended = false;
    const locks = stage.startsWith('locked') ? lockManager() : undefined;
    const h = runtime({ defaultLocks: locks, transport: (url, opts, number) => {
      if ((stage.startsWith('locked') && number === 1) || (stage.startsWith('restore') && url === '/api/me')) return response(401);
      suspended = true;
      return stage.endsWith('json') ? { status: 200, json: () => gate.promise } : gate.promise;
    } });
    const result = h.run({ signal: controller.signal }); await tick(); check(suspended, 'Requested stage reached before cancellation');
    if (cause === 'abort') controller.abort(); else { await h.advance(7999); check(h.calls.at(-1).options.signal.aborted === false, 'Request remains active before 8-second deadline'); await h.advance(1); }
    equal(await result, null, 'Cancellation settles ordinary login immediately'); const count = h.calls.length;
    gate.resolve(stage.endsWith('json') ? { code: 0, data: stage.startsWith('restore') ? { user: freshUser() } : freshUser() } : response(401)); await tick();
    equal(h.calls.length, count, 'Late completion cannot issue a restore or retry after null'); terminal(h, stage + cause);
  });
  for (const cause of ['abort', 'timeout']) await scenario('queued lock ' + cause + ' cancels and ignores late callback', async () => {
    const gate = deferred(), controller = new AbortController(); let callback, lockSignal;
    const locks = { request(name, options, action) { callback = action; lockSignal = options.signal; return gate.promise; } };
    const h = runtime({ defaultLocks: locks }); const result = h.run({ signal: controller.signal }); await tick(); check(typeof callback === 'function', 'Lock request is waiting');
    if (cause === 'abort') controller.abort(); else { await h.advance(11999); check(!lockSignal.aborted, 'Lock may wait until 12-second deadline'); await h.advance(1); }
    equal(await result, null, 'Cancelled lock queue returns ordinary login'); check(lockSignal.aborted, 'Native queued lock request receives abort signal');
    equal(await callback({ name: 'late', mode: 'exclusive' }), null, 'Even a noncompliant late lock callback cannot resume restoration'); gate.resolve(freshUser()); await tick();
    equal(h.calls.length, 1, 'Late lock grant cannot perform another GET or POST'); terminal(h, 'queued ' + cause);
  });
  for (const mode of ['sync-throw', 'async-reject', 'no-lock']) await scenario('lock failure ' + mode + ' has no unsafe fallback', async () => {
    const locks = { request(name, options, callback) { if (mode === 'sync-throw') throw Error('synthetic lock failure'); if (mode === 'async-reject') return Promise.reject(Error('synthetic lock failure')); return callback(null); } };
    const h = runtime({ defaultLocks: locks }); equal(await h.run(), null, 'Failed lock returns ordinary login'); equal(h.calls.length, 1, 'Lock failure never restores outside coordination'); terminal(h, mode);
  });
  await scenario('acquired lock deadline is replaced by per-request deadline', async () => {
    const gate = deferred(), h = runtime({ defaultLocks: lockManager(), transport: (url, opts, number) => number === 1 ? response(401) : gate.promise });
    const result = h.run(); await tick(); equal(h.timers.size, 1, 'Acquired lock leaves only current request timer');
    await h.advance(8000); equal(await result, null, 'Locked current-session GET has 8-second request bound'); gate.resolve(response(401)); await tick();
    equal(h.calls.length, 2, 'Late locked GET cannot begin restore'); terminal(h, 'acquired request timeout');
  });
  await scenario('injected fetch and locks replace globals without touching persisted credentials', async () => {
    const h = runtime({ transport: () => { throw Error('Global fetch should be replaced'); }, defaultLocks: { request() { throw Error('Global locks should be replaced'); } } });
    let calls = 0; const user = await h.run({ fetch: async () => { calls++; return response(); }, locks: null });
    equal(user, USER, 'Explicit fetch injection works'); equal(calls, 1, 'Injected fetch invoked once'); equal(h.calls.length, 0, 'Default fetch untouched'); terminal(h, 'dependency injection');
  });
  await scenario('missing fetch returns null without other work', async () => {
    const h = runtime(); equal(await h.run({ fetch: null }), null, 'Absent fetch uses ordinary login'); equal(h.calls.length, 0, 'No callable fetch means no request'); terminal(h, 'no fetch');
  });
  console.log(JSON.stringify({ ok: failures.length === 0, suite: 'R25 trusted device initial-session UI', scenarios, checks, transport: 'synthetic', browser: false, database: false, smtp: false }));
  if (failures.length) { failures.forEach(failure => console.error(failure.name + '\n' + failure.error)); process.exitCode = 1; }
})().catch(error => { console.error(error); process.exitCode = 1; });
