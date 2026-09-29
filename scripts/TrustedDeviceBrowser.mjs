import { tmpdir as testTmpdir } from 'node:os';
import { chromium } from 'playwright';
import { readFile, writeFile, mkdir, mkdtemp, realpath, access, rename } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { randomBytes, randomUUID, createHash } from 'node:crypto';
import { dirname, join, delimiter, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

// Limited original-Chrome acceptance. Only two candidate JS assets are overlaid.
// Every auth/business response is from actual Main and a fresh isolated fixture H2.
const here = dirname(fileURLToPath(import.meta.url)), args = {};
for (let i = 2; i < process.argv.length; i += 2) {
  assert.ok(['--java', '--classes', '--baseline', '--overlay', '--app'].includes(process.argv[i]) && process.argv[i + 1]);
  args[process.argv[i]] = process.argv[i + 1];
}
for (const key of ['--java', '--classes', '--baseline', '--overlay', '--app']) assert.ok(args[key], key + ' required');
const app = await realpath(args['--app']), classes = await realpath(args['--classes']), baseline = await realpath(args['--baseline']), overlay = await realpath(args['--overlay']);
for (const dir of [classes, baseline, overlay]) assert.ok(dir !== app && !dir.startsWith(app + sep), 'Private immutable class directories required');
await access(join(classes, 'com/training/TrustedDeviceHost.class')); await access(join(baseline, 'com/training/Main.class')); await access(join(overlay, 'com/training/TrustedDeviceHttpFixture.class'));
const appFile = join(here, '../web/app.js'), moduleFile = join(here, '../web/modules/notifications/trusted-device.js');
const patchedApp = await readFile(appFile, 'utf8'), deviceModule = await readFile(moduleFile, 'utf8');
const sourceHashes = {}, sha = value => createHash('sha256').update(value).digest('hex');
for (const file of [appFile, moduleFile, ...['Auth', 'Api', 'Db', 'LoginVerificationHost', 'NotificationChannelsAccountEmailMaintenance', 'OrganizationAccessStore', 'TrustedDevices', 'TrustedDeviceHost'].map(name => join(classes, 'com/training/' + name + '.class'))]) sourceHashes[file] = sha(await readFile(file));
const own = await mkdtemp(join(testTmpdir(), 'yanxu-trusted-device-http-browser-')), data = join(own, 'data'); await mkdir(data, { mode: 0o700 });
const password = randomBytes(24).toString('hex'), env = { ...process.env }, contexts = new Set(), processes = [], screenshots = [], errors = [], outside = [], consoleLeaks = [], requests = [], phases = [];
for (const key of Object.keys(env)) if (/^(?:YANXU_LOGIN_(?:SMTP_|MAIL_)|QWEATHER_|YANXU_WEATHER_|YANXU_IP_DATABASE_DIR)/.test(key)) delete env[key];
Object.assign(env, { YANXU_LOGIN_MAIL_TRANSPORT: 'disabled', YANXU_WEATHER_ENABLED: 'false', JAVA_TOOL_OPTIONS: '', JDK_JAVA_OPTIONS: '', _JAVA_OPTIONS: '', YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: '', YANXU_CITY_PLANNING_COLLECTION_FILE: '' });
let child, browser, port, base, fixture, error, log = '', checks = 0, phase = 'setup';
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (value, label) => { assert.ok(value, phase + ': ' + label); checks++; };
const equal = (a, b, label) => { assert.deepEqual(a, b, phase + ': ' + label); checks++; };
function enter(name) { phase = name; phases.push(name); }
async function control(operation, more = {}) {
  const nonce = randomUUID(); await writeFile(join(own, 'control-request.next'), JSON.stringify({ nonce, operation, ...more }), { mode: 0o600 }); await rename(join(own, 'control-request.next'), join(own, 'control-request.json'));
  for (let i = 0; i < 250; i++) { let result; try { result = JSON.parse(await readFile(join(own, 'control-result.json'), 'utf8')); } catch {} if (result?.nonce === nonce) { assert.equal(result.ok, true); return result.data; } await pause(20); }
  throw Error('Synthetic control timeout: ' + operation);
}
async function boot(resume = false) {
  if (!port) { const listener = createServer(); await new Promise((yes, no) => { listener.once('error', no); listener.listen(0, '127.0.0.1', yes); }); port = listener.address().port; await new Promise(yes => listener.close(yes)); base = 'http://127.0.0.1:' + port; }
  child = spawn(args['--java'], ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Dbootstrap.admin.password=' + password,
    '-Ddata.dir=' + data, '-Dlogin.email.mode=required', '-Dlogin.device.maxAgeSeconds=3600', '-Dlogin.device.origin=' + base,
    '-Dintegration.trusted.device.root=' + own, '-Dintegration.trusted.device.resume=' + resume,
    '-Dsemantic.port=', '-Dsemantic.token=', '-Daccount.import.manifest=', '-Daccount.import.manifest.sha256=',
    '-cp', [overlay, classes, baseline, join(app, 'lib', '*')].join(delimiter), 'com.training.TrustedDeviceHttpFixture', String(port)], { cwd: app, env, stdio: ['ignore', 'pipe', 'pipe'] });
  processes.push(child); let launchError; child.once('error', err => { launchError = err; });
  child.stdout.on('data', bytes => { log = (log + bytes).slice(-30000); }); child.stderr.on('data', bytes => { log = (log + bytes).slice(-30000); });
  for (let i = 0; i < 180; i++) { if (launchError) throw launchError; assert.equal(child.exitCode, null, 'Synthetic Main must remain alive');
    try { const result = await fetch(base + '/api/me', { signal: AbortSignal.timeout(1000) }); await result.text(); if (result.status === 401) { fixture = JSON.parse(await readFile(join(own, 'fixture.json'), 'utf8')); return; } } catch {} await pause(100); }
  throw Error('Synthetic Main startup timed out');
}
async function stop() {
  const current = child; child = null; if (!current || current.exitCode !== null || current.signalCode !== null) return;
  await new Promise(resolve => { const timer = setTimeout(() => current.kill('SIGKILL'), 2500); current.once('exit', () => { clearTimeout(timer); resolve(); }); current.kill('SIGTERM'); });
}
async function makeContext(storageState) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce', serviceWorkers: 'block', ...(storageState ? { storageState } : {}) }); contexts.add(context);
  await context.route('**/*', route => {
    const url = new URL(route.request().url());
    if (url.origin === base) {
      if (url.pathname === '/app.js') return route.fulfill({ status: 200, contentType: 'text/javascript; charset=utf-8', body: patchedApp });
      if (url.pathname === '/modules/notifications/trusted-device.js') return route.fulfill({ status: 200, contentType: 'text/javascript; charset=utf-8', body: deviceModule });
      return route.continue();
    }
    if (url.protocol === 'data:' || url.protocol === 'blob:') return route.continue();
    outside.push(url.origin); return route.abort();
  });
  context.on('request', request => { const url = new URL(request.url()); if (url.origin === base && url.pathname.startsWith('/api/')) requests.push({ path: url.pathname, method: request.method(), phase }); });
  context.on('page', page => {
    page.setDefaultTimeout(15000); page.on('pageerror', e => errors.push(String(e))); page.on('dialog', async dialog => { errors.push('Unexpected ' + dialog.type() + ' dialog'); await dialog.dismiss(); });
    page.on('console', message => { const value = message.text(); if (value.includes(password) || /@example\.invalid|yx_device_local=|__Host-yx_device=|yx_session=/.test(value)) consoleLeaks.push('Synthetic sensitive value in console'); });
  });
  return context;
}
async function closeContext(context) { await context.close(); contexts.delete(context); }
const restoreCount = () => requests.filter(item => item.path === '/api/login/device/restore' && item.method === 'POST').length;
async function pageFor(context, authenticated = false) { const page = await context.newPage(); await page.goto(base + '/#/dashboard'); await page.locator(authenticated ? '#user-menu' : '#login-user').waitFor({ state: 'visible' }); return page; }
async function me(context, expected = 200) { const response = await context.request.get(base + '/api/me'); equal(response.status(), expected, 'Actual current-session endpoint'); return expected === 200 ? (await response.json()).data : null; }
async function cookies(context) { return context.cookies(base); }
const deviceCookie = jar => jar.find(item => item.name === 'yx_device_local' || item.name === '__Host-yx_device');
async function codeFor(actor) { const mailbox = JSON.parse(await readFile(join(own, 'mailbox.json'), 'utf8')); const item = mailbox.filter(item => item.email === fixture.initial_emails[actor]).at(-1); check(item && /^[0-9]{6}$/.test(item.code) && item.held_lock === false, 'Code comes only from private memory sender outside business lock'); return item.code; }
async function passwordStep(page, actor) {
  await page.locator('#login-user').fill(fixture.usernames[actor]); await page.locator('#login-pwd').fill(password);
  const responseWait = page.waitForResponse(response => new URL(response.url()).pathname === '/api/login' && response.request().method() === 'POST');
  await page.locator('#login-btn').click(); const response = await responseWait; equal(response.status(), 200, 'Original password form reaches actual login');
  const value = await response.json(); equal(value.code, 0, 'Password challenge envelope'); equal(value.data.status, 'EMAIL_REQUIRED', 'Password alone still requires email proof'); check(!value.data.token, 'Password step returns no short session token');
  await page.locator('#login-email-step').waitFor({ state: 'visible' }); check(await page.locator('#login-code').isEnabled(), 'Original email verification field remains usable');
}
async function finishCode(page, context, actor, expectDevice = true) {
  await page.locator('#login-code').fill(await codeFor(actor));
  const responseWait = page.waitForResponse(response => new URL(response.url()).pathname === '/api/login/email/verify' && response.request().method() === 'POST');
  await page.locator('#login-btn').click(); const response = await responseWait; equal(response.status(), 200, 'Original code form verifies actual synthetic code');
  const value = await response.json(); equal(Object.keys(value.data).sort(), ['token', 'user'], 'Verification preserves original response contract without device secret');
  await page.locator('#user-menu').waitFor({ state: 'visible' }); equal((await me(context)).uid, fixture.accounts[actor], 'Original UI authenticates expected fixture user');
  const jar = await cookies(context), session = jar.find(cookie => cookie.name === 'yx_session'), device = deviceCookie(jar);
  check(session?.httpOnly === true, 'Short session cookie remains HttpOnly');
  if (expectDevice) check(device?.httpOnly === true && device.sameSite === 'Strict' && device.path === '/' && device.domain === '127.0.0.1' && device.expires > Date.now() / 1000, 'Device is browser-managed persistent HttpOnly cookie on loopback');
  else check(!device, 'Missing default configuration creates no remembered-device cookie');
  await privateStorage(page, context); return device;
}
async function privateStorage(page, context) {
  const secretValues = (await cookies(context)).filter(cookie => cookie.name.startsWith('yx_') || cookie.name.startsWith('__Host-yx_')).map(cookie => cookie.value);
  const result = await page.evaluate(values => {
    const stored = JSON.stringify([...Object.entries(localStorage), ...Object.entries(sessionStorage)]);
    return { tokenAbsent: localStorage.getItem('token') === null, cookieInvisible: !/yx_device_local|__Host-yx_device|yx_session/.test(document.cookie), secretsAbsent: values.every(value => !stored.includes(value)), passwordAbsent: !stored.includes(values.at(-1)) };
  }, [...secretValues, password]);
  check(result.tokenAbsent && result.cookieInvisible && result.secretsAbsent && result.passwordAbsent, 'JavaScript cannot read auth cookies and storage has no tokens or password');
}
async function memoryState(context) {
  const state = await context.storageState();
  check(state.origins.every(origin => origin.localStorage.every(item => item.name !== 'token')), 'Reopen state has no localStorage session token');
  return state; // Intentionally memory-only; never serialize credentials to evidence files.
}
async function shot(page, name) { const file = join(own, name + '.png'); await page.screenshot({ path: file, animations: 'disabled' }); screenshots.push(file); }
async function layout(page) { check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'Original 1440px layout has no horizontal overflow'); }

try {
  await boot();
  browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined, headless: true,
    args: ['--disable-background-networking', '--disable-component-update', '--disable-domain-reliability', '--no-first-run', '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1'] });
  enter('original password and email login enroll device');
  let context = await makeContext(), page = await pageFor(context); equal((await control('stats')).mail_count, 0, 'Startup restore does not send mail');
  await passwordStep(page, 'native'); check(!deviceCookie(await cookies(context)) && !(await cookies(context)).some(item => item.name === 'yx_session'), 'Password alone creates neither auth cookie');
  await shot(page, '01-original-email-verification'); const firstDevice = await finishCode(page, context, 'native'); await layout(page);
  const afterLoginMail = (await control('stats')).mail_count; equal(afterLoginMail, 1, 'One actual synthetic delivery for initial proof');
  let state = await memoryState(context); await closeContext(context);

  enter('reopened browser after short-session loss auto restores');
  await control('clear-short-sessions'); const restoreBefore = restoreCount(); context = await makeContext(state); page = await pageFor(context, true);
  equal(restoreCount() - restoreBefore, 1, 'Reopened browser issues one real restore'); equal((await me(context)).uid, fixture.accounts.native, 'Device restores exact same current user');
  check(deviceCookie(await cookies(context)).value !== firstDevice.value, 'Restore rotates strict one-time device cookie'); equal((await control('stats')).mail_count, afterLoginMail, 'Automatic restore sends no new mail');
  equal(await page.locator('#login-form').count(), 0, 'Restored browser directly shows original shell'); await privateStorage(page, context); state = await memoryState(context); await closeContext(context);

  enter('cold Main restart and reopened browser restore persisted device');
  await stop(); await boot(true); equal((await control('stats')).sessions, 0, 'Restart has no in-memory short sessions');
  const coldRestores = restoreCount(), oldCookie = deviceCookie(state.cookies); context = await makeContext(state); page = await pageFor(context, true);
  equal(restoreCount() - coldRestores, 1, 'Cold Main receives one actual device restore'); equal((await me(context)).uid, fixture.accounts.native, 'Persistent device restores original UI identity after restart');
  check(deviceCookie(await cookies(context)).value !== oldCookie.value, 'Cold restore rotates cookie'); equal((await control('stats')).mail_count, 0, 'Cold restore invokes no mailbox sender');
  await privateStorage(page, context); await layout(page); await shot(page, '02-cold-reopened-original-shell');

  enter('two real tabs coordinate through Web Locks');
  check(await page.evaluate(() => typeof navigator.locks?.request === 'function'), 'Real Chrome supplies Web Locks on loopback');
  // Hold the actual same-origin lock until both new tabs have received their real 401.
  await page.evaluate(() => { window.syntheticLockReady = false; window.syntheticLockHold = navigator.locks.request('yanxu-login-device-restore-v1', () => { window.syntheticLockReady = true; return new Promise(resolve => { window.syntheticLockRelease = resolve; }); }); });
  await page.waitForFunction(() => window.syntheticLockReady === true); await control('clear-short-sessions'); const twinBefore = restoreCount();
  const tabA = await context.newPage(), tabB = await context.newPage();
  await Promise.all([tabA.goto(base + '/#/dashboard'), tabB.goto(base + '/#/dashboard')]);
  await page.waitForFunction(async () => (await navigator.locks.query()).pending.filter(item => item.name === 'yanxu-login-device-restore-v1').length === 2);
  check((await control('stats')).sessions === 0 && restoreCount() === twinBefore, 'Both tabs await shared lock after actual unauthorized short-session checks');
  await page.evaluate(() => window.syntheticLockRelease());
  await Promise.all([tabA.locator('#user-menu').waitFor({ state: 'visible' }), tabB.locator('#user-menu').waitFor({ state: 'visible' })]);
  equal(restoreCount() - twinBefore, 1, 'Two tab boots perform exactly one actual HTTP restore'); equal((await control('stats')).mail_count, 0, 'Dual-tab recovery sends no new email');
  equal((await me(context)).uid, fixture.accounts.native, 'Both tabs share valid current original session'); await privateStorage(tabA, context); await privateStorage(tabB, context); await shot(tabA, '03-two-tab-restored-shell');
  await page.close(); await tabB.close(); page = tabA;

  enter('unremembered browser still requires original email step');
  const freshContext = await makeContext(), freshPage = await pageFor(freshContext); equal((await control('stats')).mail_count, 0, 'New browser restore failure sends no email');
  await control('advance', { millis: 61000 }); await passwordStep(freshPage, 'native'); await me(freshContext, 401);
  check(!deviceCookie(await cookies(freshContext)), 'New browser has no device before email proof'); equal((await control('stats')).mail_count, 1, 'New browser requires a new synthetic email code');
  await freshPage.locator('#login-back').click(); await freshPage.locator('#login-password-step').waitFor({ state: 'visible' }); await closeContext(freshContext);

  enter('original logout revokes current device and requires email again');
  const beforeLogoutDevice = deviceCookie(await cookies(context)), logoutRestoreCount = restoreCount();
  await page.locator('#user-menu > summary').click(); await page.locator('#btn-logout').click();
  const logoutWait = page.waitForResponse(response => new URL(response.url()).pathname === '/api/logout' && response.request().method() === 'POST'); await page.locator('#modal-ok').click(); equal((await logoutWait).status(), 200, 'Original logout reaches actual server');
  await page.locator('#login-user').waitFor({ state: 'visible' }); check(!deviceCookie(await cookies(context)) && !(await cookies(context)).some(item => item.name === 'yx_session'), 'Logout clears browser short and persistent cookies');
  equal(restoreCount(), logoutRestoreCount, 'Logout does not automatically attempt device restoration'); await me(context, 401);
  const deviceRows = (await control('device-storage')).devices; check(deviceRows.find(row => row.selector === beforeLogoutDevice.value.split('.')[0])?.revoked === true, 'Actual logout persistently revokes current device');
  await control('advance', { millis: 61000 }); await passwordStep(page, 'native'); await me(context, 401); await shot(page, '04-logout-requires-email-again');
  await page.locator('#login-back').click(); await page.locator('#login-password-step').waitFor({ state: 'visible' }); await privateStorage(page, context); await closeContext(context);

  enter('missing default configuration never remembers devices');
  await control('device-disable'); context = await makeContext(); page = await pageFor(context);
  await passwordStep(page, 'admin2'); await finishCode(page, context, 'admin2', false); state = await memoryState(context); await closeContext(context);
  await control('clear-short-sessions'); const disabledMail = (await control('stats')).mail_count; context = await makeContext(state); page = await pageFor(context);
  check(!deviceCookie(await cookies(context)), 'Disabled policy remains without device cookie on reopen'); await me(context, 401); equal((await control('stats')).mail_count, disabledMail, 'Disabled auto-restore failure sends no email'); await privateStorage(page, context); await closeContext(context);

  equal((await control('stats')).sender_lock_violations, 0, 'All mailbox sends remain outside business lock'); equal(errors, [], 'No browser runtime errors'); equal(outside, [], 'No requested off-origin resources'); equal(consoleLeaks, [], 'No credentials or private candidate addresses in console');
  for (const [file, hash] of Object.entries(sourceHashes)) equal(sha(await readFile(file)), hash, 'Candidate file remains immutable: ' + file.split('/').at(-1));
} catch (caught) { error = caught; }
finally {
  for (const context of contexts) await context.close().catch(() => {}); await browser?.close().catch(() => {}); await stop();
  await writeFile(join(own, 'server.log'), log.replaceAll(password, '[synthetic password redacted]'), { mode: 0o600 });
  const result = { ok: !error, suite: 'R25 original Chrome trusted-device acceptance', checks, width: 1440, phases, classes, baseline, fixtureClasses: overlay,
    temporaryDirectory: own, screenshots, sourceHashes, browserErrors: errors, outsideRequests: outside, consoleLeaks, actualRestoreRequests: restoreCount(),
    requests: requests.filter(item => ['/api/me', '/api/login', '/api/login/email/verify', '/api/login/device/restore', '/api/logout'].includes(item.path)),
    realAuthResponses: true, syntheticDatabase: true, realSmtpDisabled: true, storageStateEvidence: 'memory-only',
    processes: processes.map(item => ({ pid: item.pid, stopped: item.exitCode !== null || item.signalCode !== null })),
    ...(error ? { error: error.stack || String(error), failedPhase: phase } : {}) };
  await writeFile(join(own, 'result.json'), JSON.stringify(result, null, 2), { mode: 0o600 });
  console.log(JSON.stringify({ ok: result.ok, checks, result: join(own, 'result.json'), screenshots, ...(error ? { error: error.message, phase } : {}) }));
}
if (error) process.exitCode = 1;
