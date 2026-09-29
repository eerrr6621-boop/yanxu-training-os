/**
 * Run: node scripts/S01-notifications-ui-test.mjs
 * Node built-ins only. Contract/event checks use a minimal DOM stub;
 * responsive layout and browser accessibility require a real-browser check.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { mount } from '../web/modules/notifications/index.js';

class Element {
  constructor(tag, document) {
    this.tagName = tag;
    this.ownerDocument = document;
    this.children = [];
    this.className = '';
    this.attributes = {};
    this.listeners = {};
    this._text = '';
    this.disabled = false;
  }
  set innerHTML(_) { throw new Error('Unsafe HTML rendering'); }
  set textContent(value) { this._text = String(value); this.children = []; this.ownerDocument.mutations++; }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  append(...children) {
    for (const child of children) { child.parentNode = this; this.children.push(child); }
    this.ownerDocument.mutations++;
  }
  replaceChildren(...children) {
    this.children.forEach(child => { child.parentNode = null; });
    this.children = [];
    this._text = '';
    this.append(...children);
  }
  remove() {
    if (!this.parentNode) return;
    this.parentNode.children = this.parentNode.children.filter(child => child !== this);
    this.parentNode = null;
    this.ownerDocument.mutations++;
  }
  setAttribute(key, value) { this.attributes[key] = value; }
  addEventListener(event, callback) { (this.listeners[event] ??= []).push(callback); }
  click() { if (!this.disabled) for (const callback of this.listeners.click ?? []) callback(); }
  focus() { this.ownerDocument.activeElement = this; }
  querySelector(selector) { return this.querySelectorAll(selector)[0] ?? null; }
  querySelectorAll(selector) {
    const matches = [];
    const walk = node => {
      for (const child of node.children) {
        if (selector.startsWith('.') ? child.className.split(' ').includes(selector.slice(1)) : child.tagName === selector) matches.push(child);
        walk(child);
      }
    };
    walk(this);
    return matches;
  }
}

function newRoot() {
  const document = { mutations: 0, createElement(tag) { return new Element(tag, this); } };
  return new Element('main', document);
}
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
const button = (root, label) => root.querySelectorAll('button').find(node => node.textContent === label);
const unreadCount = root => Number(root.querySelectorAll('.yx-notifications__metric-value')[0]?.textContent);
const taskCount = root => Number(root.querySelectorAll('.yx-notifications__metric-value')[1]?.textContent);
const sampleItem = {
  id: 'opaque/id?value=%', eventId: 'event:1', type: 'REVIEW_REQUIRED',
  title: '<img src=x onerror=alert(1)>', body: '合约测试',
  createdAt: '2026-09-21T09:00:00+08:00', readAt: null, actionable: true
};
const list = () => ({
  state: 'ready', items: [{ ...sampleItem }],
  channels: [
    { channel: 'IN_APP', status: 'ready' },
    { channel: 'EMAIL', status: 'unconfigured' },
    { channel: 'PUBLIC_ACCOUNT', status: 'unconfigured' }
  ]
});
const target = (moduleId = 'M03', params = {}) => ({
  state: 'ready', target: { moduleId, params: { recordId: '4101', eventId: sampleItem.eventId, taskId: 'task:2', view: 'task', ...params } }
});
let groupCount = 0;
function pass(name) { groupCount++; console.log(`PASS ${name}`); }

{
  const root = newRoot();
  let calls = 0;
  const cleanup = await mount(root, { mode: 'demo', request: () => { calls++; throw Error(); }, navigate: () => { calls++; } });
  assert.equal(root.querySelectorAll('.yx-notifications__item').length, 4);
  assert.equal(unreadCount(root), 3);
  assert.equal(taskCount(root), 3);
  ['申请等待负责人审批', '申请已退回，请补充材料', '团队有待承接事项', '团队已承接事项'].forEach(title => assert(root.textContent.includes(title)));
  for (let i = 0; i < 4; i++) {
    root.querySelectorAll('.yx-notifications__open-button')[i].click();
    await flush();
    const detail = root.querySelector('.yx-notifications__detail');
    assert(detail.textContent.includes(String(4101 + i)));
    assert(detail.textContent.includes(`demo-task-${i + 1}`));
    button(root, '返回通知').click();
  }
  button(root, '只看未读').click();
  assert.equal(root.querySelectorAll('.yx-notifications__item').length, 3);
  button(root, '标为已读').click();
  await flush();
  assert.equal(unreadCount(root), 2);
  assert.equal(taskCount(root), 3);
  assert.equal(root.querySelectorAll('.yx-notifications__item').length, 2);
  button(root, '消息').click();
  assert(root.textContent.includes('这里没有未读通知'));
  button(root, '只看未读').click();
  assert.equal(root.querySelectorAll('.yx-notifications__item').length, 1);
  assert.equal(calls, 0);
  cleanup();
  assert.equal(root.children.length, 0);
  pass('four demo events, distinct item/task details, unread/category filters, independent read/business states, zero requests');
}

for (const context of [
  { mode: 'live', user: null, request: () => { throw Error('should not run'); } },
  { mode: 'live', user: {}, request: () => { throw Error('should not run'); } },
  { mode: 'live', user: { id: 'u1' } }, { mode: 'unknown' }
]) {
  const root = newRoot();
  const cleanup = await mount(root, context);
  await flush();
  assert(root.textContent.includes('暂时无法查看通知'));
  assert.equal(root.querySelectorAll('.yx-notifications__item').length, 0);
  cleanup();
}
pass('missing authenticated host context never loads demo data');

const badLists = [
  null, {}, { ...list(), state: 'pending' },
  { ...list(), items: [{ ...sampleItem, readAt: undefined }] },
  { ...list(), items: [{ ...sampleItem, createdAt: 'today' }] },
  { ...list(), items: [{ ...sampleItem, actionable: 'yes' }] },
  { ...list(), items: [sampleItem, sampleItem] }, { ...list(), channels: [] },
  { ...list(), channels: [{ channel: 'IN_APP', status: 'ready' }, { channel: 'IN_APP', status: 'ready' }, { channel: 'EMAIL', status: 'ready' }] }
];
for (const response of badLists) {
  const root = newRoot();
  const cleanup = await mount(root, { mode: 'live', user: { id: 'u1' }, request: async () => response });
  await flush();
  assert(root.textContent.includes('暂时无法查看通知'));
  assert.equal(root.querySelectorAll('.yx-notifications__item').length, 0);
  cleanup();
}
pass('9 malformed/unsupported live list payloads fail closed');

{
  const root = newRoot();
  const calls = [];
  const navigations = [];
  const cleanup = await mount(root, {
    mode: 'live', user: { uid: 'u1' },
    request: async (path, options) => {
      calls.push([path, options]);
      if (path === '/api/notifications') return list();
      if (path.endsWith('/target')) return target();
      return { state: 'ready', item: { ...sampleItem, readAt: '2026-09-21T10:00:00+08:00' } };
    },
    navigate: (...args) => navigations.push(args)
  });
  await flush();
  assert(root.textContent.includes(sampleItem.title));
  assert.equal(root.querySelectorAll('img').length, 0);
  button(root, '打开事项 →').click();
  await flush();
  button(root, '打开事项 →').click();
  await flush();
  assert.equal(navigations.length, 2);
  assert.equal(calls.filter(([path]) => path.endsWith('/target')).length, 2);
  assert.deepEqual(navigations[0], ['M03', { recordId: '4101', eventId: sampleItem.eventId, view: 'task', taskId: 'task:2' }]);
  assert(calls.some(([path]) => path === '/api/notifications/opaque%2Fid%3Fvalue%3D%25/target'));
  button(root, '标为已读').click();
  await flush();
  assert.equal(unreadCount(root), 0);
  assert.equal(taskCount(root), 1);
  const readCall = calls.find(([path]) => path.endsWith('/read'));
  assert.equal(readCall[1].method, 'POST');
  assert.equal(readCall[1].body, '{}');
  assert(readCall[1].signal instanceof AbortSignal);
  cleanup();
  pass('uid session, opaque path encoding, escaped text, fresh target on every open, confirmed read response');
}

const missingTask = target();
delete missingTask.target.params.taskId;
const targetCases = [
  ['colon identifiers', target(), true],
  ['maximum safe record', target('M03', { recordId: '9007199254740991' }), true],
  ['unsafe record', target('M03', { recordId: '9007199254740992' }), false],
  ['enormous record', target('M03', { recordId: '9999999999999999999999999' }), false],
  ['M02 detail', target('M02', { view: 'detail' }), true],
  ['M02 task', target('M02'), false], ['M03 detail', target('M03', { view: 'detail' }), true],
  ['M03 task missing taskId', missingTask, false],
  ['URL task', target('M03', { taskId: 'https://evil.test' }), false],
  ['query task', target('M03', { taskId: 'task?x' }), false],
  ['fragment task', target('M03', { taskId: 'task#x' }), false],
  ['backslash task', target('M03', { taskId: 'task\\x' }), false],
  ['unknown view', target('M03', { view: 'approval' }), false],
  ['extra params', target('M03', { url: 'https://evil.test' }), false],
  ['128-char token', target('M03', { taskId: 't'.repeat(128) }), true],
  ['129-char token', target('M03', { taskId: 't'.repeat(129) }), false],
  ['null', null, false], ['empty', {}, false], ['unavailable', { ...target(), state: 'denied' }, false],
  ['arbitrary module', target('https://evil.test'), false],
  ['numeric record', target('M03', { recordId: 4101 }), false],
  ['zero record', target('M03', { recordId: '0' }), false],
  ['negative record', target('M03', { recordId: '-1' }), false],
  ['wrong event', target('M03', { eventId: 'other-event' }), false],
  ['null task', target('M03', { taskId: null }), false],
  ['target URL', { state: 'ready', target: { ...target().target, url: 'https://evil.test' } }, false]
];
for (const [name, response, allowed] of targetCases) {
  const root = newRoot();
  let navigations = 0;
  const cleanup = await mount(root, {
    mode: 'live', user: { uid: 'user:1' },
    request: async path => path === '/api/notifications' ? list() : response,
    navigate: () => { navigations++; }
  });
  await flush();
  button(root, '打开事项 →').click();
  await flush();
  assert.equal(navigations, Number(allowed), name);
  if (!allowed) assert(root.textContent.includes('暂时无法打开此事项'), name);
  cleanup();
}
pass(`${targetCases.length} target cases enforce module/view/task/opaque-token/safe-integer/parameter whitelist`);

for (const response of [
  { state: 'ready', item: { ...sampleItem, readAt: null } },
  { state: 'ready', item: { ...sampleItem, id: 'wrong', readAt: '2026-09-21T10:00:00Z' } },
  { state: 'unavailable' }
]) {
  const root = newRoot();
  const cleanup = await mount(root, { mode: 'live', user: { id: 'u1' }, request: async path => path === '/api/notifications' ? list() : response });
  await flush();
  button(root, '标为已读').click();
  await flush();
  assert.equal(unreadCount(root), 1);
  assert(root.textContent.includes('阅读状态未改变'));
  cleanup();
}
pass('unconfirmed/mismatched read responses never mark a notification read');

for (const operation of ['load', 'target', 'read']) {
  const root = newRoot();
  const external = new AbortController();
  let resolve;
  let notifications = 0;
  let navigation = 0;
  let requestSignal;
  const pending = new Promise(done => { resolve = done; });
  const cleanup = await mount(root, {
    mode: 'live', signal: external.signal, user: { id: 'u1' },
    request: async (path, options) => {
      requestSignal = options.signal;
      return operation === 'load' || path !== '/api/notifications' ? pending : list();
    },
    notify: () => { notifications++; }, navigate: () => { navigation++; }
  });
  await flush();
  if (operation !== 'load') button(root, operation === 'target' ? '打开事项 →' : '标为已读').click();
  external.abort();
  const mutations = root.ownerDocument.mutations;
  resolve(operation === 'load' ? list() : operation === 'target' ? target() : { state: 'ready', item: { ...sampleItem, readAt: '2026-09-21T10:00:00Z' } });
  await flush();
  assert.equal(root.ownerDocument.mutations, mutations);
  assert.equal(notifications, 0);
  assert.equal(navigation, 0);
  assert(requestSignal.aborted);
  assert.equal(root.children.length, 0);
  cleanup();
}
pass('AbortSignal blocks late DOM writes, navigation and notifications during load/target/read');

{
  const root = newRoot();
  const external = new AbortController();
  external.abort();
  await mount(root, { mode: 'demo', signal: external.signal });
  assert.equal(root.children.length, 0);
  const liveRoot = newRoot();
  let resolve;
  const pending = new Promise(done => { resolve = done; });
  const cleanup = await mount(liveRoot, { mode: 'live', user: { id: 'u' }, request: () => pending });
  cleanup();
  const mutations = liveRoot.ownerDocument.mutations;
  resolve(list());
  await flush();
  assert.equal(liveRoot.ownerDocument.mutations, mutations);
  pass('already-aborted mount and explicit cleanup stay inert');
}

{
  const source = readFileSync(new URL('../web/modules/notifications/index.js', import.meta.url), 'utf8');
  assert(!/innerHTML|outerHTML|insertAdjacentHTML|\bfetch\(|localStorage|sessionStorage/.test(source));
  const css = readFileSync(new URL('../web/modules/notifications/style.css', import.meta.url), 'utf8');
  for (const block of css.matchAll(/([^{}]+)\{/g)) {
    const selectors = block[1].trim();
    if (selectors.startsWith('@')) continue;
    for (const selector of selectors.split(',')) assert(selector.trim().startsWith('.yx-notifications'), selector);
  }
  pass('safe DOM rendering and complete stylesheet scope');
}

console.log(`ALL ${groupCount} CONTRACT GROUPS PASSED`);
