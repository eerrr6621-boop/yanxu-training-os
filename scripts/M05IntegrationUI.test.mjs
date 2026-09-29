import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { webcrypto } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// Dependency-free host/DOM adapter tests; the host browser fixture checks real layout.
const webRoot = process.env.M05_WEB_ROOT || fileURLToPath(new URL('../web/', import.meta.url));
const conversion = await readFile(`${webRoot}/modules/settlement/conversion.js`, 'utf8');
const toModule = (text) => `data:text/javascript;base64,${Buffer.from(text).toString('base64')}`;
const source = await readFile(process.env.M05_UI_MODULE || `${webRoot}/modules/delivery-settlement/index.js`, 'utf8');
const { mount } = await import(toModule(source.replace("'../settlement/conversion.js'", JSON.stringify(toModule(conversion)))));

class Control {
  constructor(key, kind = 'field') {
    this.kind = kind;
    this.value = '';
    this.textContent = '';
    this.disabled = false;
    this.hidden = false;
    this.style = {};
    this.attrs = new Map();
    this.dataset = kind === 'button' ? { m05Action: key } : { k: key };
    this.error = { textContent: '' };
    this.item = { querySelector: () => this.error };
  }
  setAttribute(key, value) { this.attrs.set(key, value); }
  removeAttribute(key) { this.attrs.delete(key); }
  closest(selector) {
    if (selector === '.form-item') return this.item;
    return (selector === '[data-k]' && this.kind === 'field') || (selector === '[data-m05-action]' && this.kind === 'button') ? this : null;
  }
  focus() { this.focused = true; }
}

function detail(overrides = {}) {
  const result = {
    dispatch_id: 7, project_id: 5, teacher_id: 8, organization_code: 'ORG-1', version: 1,
    fact: { hours: { estimated: '4.00', planned: '3.50', actual: '1.33', payable: null }, verification: null },
    conversion: { minutes: '60', class_hours: '1.33' },
    capabilities: { fact_version: 1, current_server: true, permissions: { save: true, verify: true, complete: true }, can_save: true, can_verify: true, can_complete: false, reasons: {} },
  };
  return Object.assign(result, structuredClone(overrides));
}

function revised(version, overrides = {}) {
  const result = detail({ version, ...overrides });
  result.capabilities.fact_version = version;
  return result;
}

function fixture(steps, extras = {}) {
  const requests = [];
  const masks = [];
  const route = new AbortController();
  let definitions = [];
  const context = {
    signal: route.signal,
    api: async (path, options = {}) => {
      const step = steps.shift();
      requests.push({ path, ...options });
      assert.ok(step, `unexpected request ${path}`);
      return typeof step === 'function' ? step(path, options) : structuredClone(step);
    },
    renderForm: (items) => { definitions = items; return '<div class="form-grid"></div>'; },
    openModal: (title, html, options) => {
      const elements = new Map();
      for (const field of definitions) elements.set(`[data-k="${field.k}"]`, new Control(field.k));
      for (const action of ['save', 'verify', 'complete', 'refresh', 'retry']) elements.set(`[data-m05-action="${action}"]`, new Control(action, 'button'));
      for (const name of ['meta', 'saved', 'conversion', 'verification', 'capability', 'status']) elements.set(`[data-m05-${name}]`, new Control(name, 'slot'));
      const listeners = new Map();
      const mask = {
        title, html, isConnected: true, elements, listeners, hostTrapRemoved: false,
        querySelector: (selector) => elements.get(selector),
        contains: (element) => [...elements.values()].includes(element),
        addEventListener(type, listener) { listeners.set(type, listener); },
        removeEventListener(type, listener) { if (listeners.get(type) === listener) listeners.delete(type); },
        async emit(type, target) { await listeners.get(type)?.({ target }); },
      };
      elements.set('#modal-x', { click() { options.onClose(); mask.isConnected = false; mask.hostTrapRemoved = true; } });
      masks.push(mask);
      return mask;
    },
    ...extras,
  };
  const controller = mount({ ownerDocument: { defaultView: { crypto: webcrypto } } }, context);
  const current = () => masks.at(-1);
  return {
    controller, requests, masks, route, context, current,
    field: (key) => current().querySelector(`[data-k="${key}"]`),
    button: (key) => current().querySelector(`[data-m05-action="${key}"]`),
    text: (key) => current().querySelector(`[data-m05-${key}]`).textContent,
    async edit(key, value) { const control = this.field(key); control.value = value; await current().emit('input', control); },
    async click(key) { await current().emit('click', this.button(key)); },
    async open(id = 7) { await controller.open(id); },
  };
}
const fail = (status, message = `ERROR-${status}`) => () => { const error = new Error(message); error.status = status; throw error; };

test('decimal strings/null remain separate; 60 previews 1.33; save body has only allowlisted fields', async () => {
  const f = fixture([detail(), revised(2)]);
  await f.open();
  assert.equal(f.field('actual_hours').value, '1.33');
  assert.equal(f.field('payable_hours').value, '');
  await f.edit('estimated_hours', '0');
  await f.edit('planned_hours', '');
  await f.edit('evidence_code', 'EVIDENCE-PREPARED');
  await f.click('save');
  const body = f.requests[1].body;
  assert.deepEqual(Object.keys(body).sort(), ['dispatch_id', 'expected_version', 'request_id', 'estimated_hours', 'planned_hours', 'actual_minutes', 'payable_hours'].sort());
  assert.equal(body.estimated_hours, '0');
  assert.equal(body.planned_hours, null);
  assert.equal(body.actual_minutes, '60');
  assert.equal(body.payable_hours, null);
  assert.equal(body.expected_version, 1);
  assert.match(body.request_id, /^M05-/);
  assert.equal(f.field('evidence_code').value, 'EVIDENCE-PREPARED');
});

test('dirty actual blocks verify/complete until saved response; evidence is sent only on verify', async () => {
  const verified = detail();
  verified.fact.verification = { actor_code: 'ACTOR', checked_at: '2026-09-22', evidence_code: 'EV-1' };
  verified.capabilities.can_complete = true;
  const saved = revised(2);
  const f = fixture([verified, saved, revised(3)], { onComplete: async () => assert.fail('must not complete dirty form') });
  await f.open();
  await f.edit('actual_minutes', '90');
  assert.equal(f.button('verify').disabled, true);
  assert.equal(f.button('complete').disabled, true);
  await f.click('verify');
  await f.click('complete');
  assert.equal(f.requests.length, 1);
  await f.click('save');
  await f.edit('evidence_code', 'EV-2');
  assert.equal(f.button('verify').disabled, false);
  await f.click('verify');
  assert.deepEqual(Object.keys(f.requests[2].body).sort(), ['dispatch_id', 'expected_version', 'request_id', 'evidence_code'].sort());
  assert.equal(f.requests[2].body.expected_version, 2);
});

test('missing permission hides verification and completion despite old canWrite context', async () => {
  const d = detail();
  d.capabilities.permissions.verify = false;
  d.capabilities.permissions.complete = false;
  const f = fixture([d], { canWrite: true, onComplete: async () => {} });
  await f.open();
  assert.equal(f.button('verify').hidden, true);
  assert.equal(f.button('verify').style.display, 'none');
  assert.equal(f.button('complete').hidden, true);
  assert.match(f.text('capability'), /草稿/);
  await f.click('verify');
  assert.equal(f.requests.length, 1);
});

test('missing or mismatched current capabilities fail closed', async () => {
  for (const capabilities of [null, {}, { ...detail().capabilities, fact_version: 99 }, { ...detail().capabilities, current_server: false }]) {
    const f = fixture([detail({ capabilities })]);
    await f.open();
    assert.equal(f.field('actual_minutes').disabled, true);
    assert.equal(f.button('verify').hidden, true);
    assert.equal(f.button('save').disabled, true);
  }
});

test('400 retains all input and prevents repeating identical rejected data', async () => {
  const f = fixture([detail(), fail(400, '实际分钟不符合要求'), revised(2)]);
  await f.open();
  await f.edit('actual_minutes', '90');
  await f.click('save');
  assert.equal(f.field('actual_minutes').value, '90');
  assert.match(f.text('status'), /实际分钟不符合要求/);
  assert.equal(f.button('save').disabled, true);
  await f.edit('evidence_code', 'EV-2');
  await f.click('save');
  assert.equal(f.requests.length, 2);
  await f.edit('actual_minutes', '91');
  assert.equal(f.button('save').disabled, false);
  await f.click('save');
  assert.equal(f.requests[2].body.actual_minutes, '91');
});

test('409 requires explicit refresh and manual save using new version, retaining draft', async () => {
  const newest = revised(3);
  newest.conversion.minutes = '135';
  newest.fact.hours.actual = '3.00';
  const f = fixture([detail(), fail(409, '版本已变化'), newest, revised(4)]);
  await f.open();
  await f.edit('actual_minutes', '90');
  await f.click('save');
  assert.equal(f.requests.length, 2);
  assert.equal(f.field('actual_minutes').value, '90');
  assert.match(f.text('status'), /读取最新记录/);
  await f.edit('actual_minutes', '91');
  await f.click('save');
  assert.equal(f.requests.length, 2);
  await f.click('refresh');
  assert.equal(f.field('actual_minutes').value, '91');
  assert.match(f.text('saved'), /3.00/);
  assert.equal(f.requests.length, 3);
  await f.click('save');
  assert.equal(f.requests[3].body.expected_version, 3);
  assert.notEqual(f.requests[3].body.request_id, f.requests[1].body.request_id);
});

test('403 only refreshes capabilities and retains input without another mutation', async () => {
  const revoked = revised(2);
  revoked.capabilities.permissions = { save: false, verify: false, complete: false };
  const f = fixture([detail(), fail(403), revoked]);
  await f.open();
  await f.edit('actual_minutes', '90');
  await f.click('save');
  assert.equal(f.requests.length, 3);
  assert.equal(f.requests[2].body, undefined);
  assert.equal(f.field('actual_minutes').value, '90');
  assert.equal(f.field('actual_minutes').disabled, true);
  assert.equal(f.button('verify').hidden, true);
});

test('failed capability refresh after 403 does not retain actionable grants', async () => {
  const f = fixture([detail(), fail(403), fail(500)]);
  await f.open();
  await f.edit('actual_minutes', '90');
  await f.click('save');
  assert.equal(f.button('save').disabled, true);
  assert.equal(f.button('verify').hidden, true);
});

test('unknown network outcome is only retried explicitly with identical body/request id', async () => {
  const f = fixture([detail(), fail(0, '网络断开'), revised(2)]);
  await f.open();
  await f.edit('actual_minutes', '90');
  await f.click('save');
  assert.equal(f.requests.length, 2);
  assert.equal(f.button('retry').hidden, false);
  assert.equal(f.field('actual_minutes').disabled, true);
  await f.click('save');
  assert.equal(f.requests.length, 2);
  await f.click('retry');
  assert.deepEqual(f.requests[2].body, f.requests[1].body);
  assert.equal(f.button('retry').hidden, true);
});

test('idempotent older result triggers one read, retains submitted input, no automatic mutation', async () => {
  const replay = revised(2);
  replay.conversion.minutes = '90';
  replay.capabilities.fact_version = 3;
  const latest = revised(3);
  latest.conversion.minutes = '135';
  latest.fact.hours.actual = '3.00';
  const f = fixture([detail(), fail(0), replay, latest]);
  await f.open();
  await f.edit('actual_minutes', '90');
  await f.edit('evidence_code', 'EVIDENCE-RETRY');
  await f.click('save');
  await f.click('retry');
  assert.equal(f.requests.length, 4);
  assert.equal(f.requests[3].body, undefined);
  assert.equal(f.field('actual_minutes').value, '90');
  assert.equal(f.field('evidence_code').value, 'EVIDENCE-RETRY');
  assert.match(f.text('saved'), /3.00/);
  assert.equal(f.button('verify').disabled, true);
  assert.equal(f.button('save').disabled, false);
});

test('401 aborts interaction, removes component listeners, no retry offered', async () => {
  const f = fixture([detail(), fail(401)]);
  await f.open();
  await f.edit('actual_minutes', '90');
  await f.click('save');
  assert.equal(f.requests[1].signal.aborted, true);
  assert.equal(f.current().listeners.size, 0);
  assert.equal(f.button('save').disabled, true);
  assert.equal(f.button('retry').hidden, true);
  assert.match(f.text('status'), /重新登录/);
});

test('route cleanup closes host modal, removes listeners, aborts and ignores late response', async () => {
  let finish;
  const f = fixture([() => new Promise((resolve) => { finish = resolve; })]);
  const opening = f.open();
  const mask = f.current();
  f.route.abort();
  assert.equal(f.requests[0].signal.aborted, true);
  assert.equal(mask.listeners.size, 0);
  assert.equal(mask.isConnected, false);
  assert.equal(mask.hostTrapRemoved, true);
  finish(detail());
  await opening;
  assert.equal(mask.querySelector('[data-k="actual_minutes"]').value, '');
  await f.open();
  assert.equal(f.masks.length, 1);
});

test('closing one record aborts its request and cannot overwrite newly opened record', async () => {
  let finish;
  const f = fixture([() => new Promise((resolve) => { finish = resolve; }), detail()]);
  const first = f.open();
  const old = f.current();
  await f.open();
  assert.equal(f.requests[0].signal.aborted, true);
  assert.equal(old.hostTrapRemoved, true);
  finish(detail({ conversion: { minutes: '999' } }));
  await first;
  assert.equal(f.field('actual_minutes').value, '60');
});

test('blank actual minutes stay null and cannot be substituted from planned/payable', async () => {
  const d = detail({ conversion: null });
  d.fact.hours.actual = null;
  d.fact.hours.payable = '7.00';
  d.capabilities.can_verify = false;
  const f = fixture([d, revised(2)]);
  await f.open();
  assert.equal(f.field('actual_minutes').value, '');
  assert.equal(f.field('actual_hours').value, '');
  await f.edit('estimated_hours', '8');
  await f.click('save');
  assert.equal(f.requests[1].body.actual_minutes, null);
  assert.equal(f.requests[1].body.payable_hours, '7.00');
});

test('invalid decimal or evidence never reaches mutation API', async () => {
  const f = fixture([detail()]);
  await f.open();
  for (const value of ['1e3', '-1', ' 60', '60 ', 'Infinity', '1.000000001']) {
    await f.edit('actual_minutes', value);
    await f.click('save');
    assert.equal(f.requests.length, 1);
  }
  await f.edit('actual_minutes', '60');
  for (const value of ['', '<img>', 'A'.repeat(97)]) {
    await f.edit('evidence_code', value);
    await f.click('verify');
    assert.equal(f.requests.length, 1);
  }
});

test('no completion hook hides action and component never calls legacy completion API', async () => {
  const d = detail();
  d.capabilities.can_complete = true;
  const f = fixture([d]);
  await f.open();
  assert.equal(f.button('complete').hidden, true);
  await f.click('complete');
  assert.equal(f.requests.length, 1);
  assert.equal(source.includes('/dispatches/complete'), false);
});
