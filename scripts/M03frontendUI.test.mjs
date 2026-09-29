import test from 'node:test';
import assert from 'node:assert/strict';

// Dependency-free DOM surface for the approval module's public mount contract.
class MiniEvent {
  constructor(type, options = {}) { this.type = type; Object.assign(this, options); this.bubbles ??= true; }
  preventDefault() { this.defaultPrevented = true; }
  stopPropagation() { this.stopped = true; }
}

class MiniNode {
  constructor(tag = '', ownerDocument) {
    this.tagName = tag.toUpperCase(); this.ownerDocument = ownerDocument;
    this.parentNode = null; this.childNodes = []; this.attributes = new Map();
    this.listeners = new Map(); this.style = {}; this.value = ''; this.disabled = false;
    this.dataset = new Proxy({}, {
      get: (_, key) => this.getAttribute(`data-${String(key).replace(/[A-Z]/g, c => `-${c.toLowerCase()}`)}`),
      set: (_, key, value) => { this.setAttribute(`data-${String(key).replace(/[A-Z]/g, c => `-${c.toLowerCase()}`)}`, value); return true; },
    });
    this.classList = {
      add: (...names) => { this.className = [...new Set([...this.className.split(/\s+/).filter(Boolean), ...names])].join(' '); },
      remove: (...names) => { this.className = this.className.split(/\s+/).filter(n => !names.includes(n)).join(' '); },
      contains: name => this.className.split(/\s+/).includes(name),
      toggle: (name, force) => { const on = force ?? !this.classList.contains(name); this.classList[on ? 'add' : 'remove'](name); return on; },
    };
  }
  get children() { return this.childNodes.filter(n => n.tagName); }
  get firstChild() { return this.childNodes[0] ?? null; }
  get lastChild() { return this.childNodes.at(-1) ?? null; }
  get parentElement() { return this.parentNode; }
  get isConnected() { return this === this.ownerDocument?.body || Boolean(this.parentNode?.isConnected); }
  get className() { return this.getAttribute('class') ?? ''; }
  set className(value) { this.setAttribute('class', value); }
  get id() { return this.getAttribute('id') ?? ''; }
  set id(value) { this.setAttribute('id', value); }
  get textContent() { return this.tagName ? this.childNodes.map(n => n.textContent).join('') : this._text ?? ''; }
  set textContent(value) {
    if (!this.tagName) { this._text = String(value ?? ''); return; }
    this.replaceChildren(String(value ?? ''));
  }
  get innerHTML() { return this.childNodes.map(n => n.outerHTML).join(''); }
  set innerHTML(value) {
    if (value !== '') throw new Error('Test DOM intentionally supports only empty innerHTML; use safe DOM construction.');
    this.replaceChildren();
  }
  get outerHTML() {
    if (!this.tagName) return JSON.stringify(this._text ?? '');
    return `<${this.tagName} ${JSON.stringify([...this.attributes])} disabled=${this.disabled}>${this.innerHTML}</${this.tagName}>`;
  }
  setAttribute(key, value) { this.attributes.set(key, String(value)); if (key === 'disabled') this.disabled = true; }
  getAttribute(key) { return this.attributes.get(key) ?? null; }
  hasAttribute(key) { return this.attributes.has(key); }
  removeAttribute(key) { this.attributes.delete(key); if (key === 'disabled') this.disabled = false; }
  append(...nodes) { for (const n of nodes) this.appendChild(typeof n === 'string' ? this.ownerDocument.createTextNode(n) : n); }
  appendChild(node) { node.remove(); node.parentNode = this; this.childNodes.push(node); return node; }
  prepend(...nodes) { for (const n of nodes.reverse()) { const node = typeof n === 'string' ? this.ownerDocument.createTextNode(n) : n; node.remove(); node.parentNode = this; this.childNodes.unshift(node); } }
  replaceChildren(...nodes) { for (const node of this.childNodes) node.parentNode = null; this.childNodes = []; this.append(...nodes); }
  removeChild(node) { const index = this.childNodes.indexOf(node); if (index >= 0) this.childNodes.splice(index, 1); node.parentNode = null; return node; }
  remove() { this.parentNode?.removeChild(this); }
  contains(node) { return node === this || this.childNodes.some(child => child.contains(node)); }
  addEventListener(type, callback) { const callbacks = this.listeners.get(type) ?? []; callbacks.push(callback); this.listeners.set(type, callbacks); }
  removeEventListener(type, callback) { this.listeners.set(type, (this.listeners.get(type) ?? []).filter(c => c !== callback)); }
  dispatchEvent(event) {
    event.target ??= this; event.currentTarget = this;
    for (const callback of this.listeners.get(event.type) ?? []) callback.call(this, event);
    if (event.bubbles && !event.stopped) this.parentNode?.dispatchEvent(event);
    return !event.defaultPrevented;
  }
  click() { if (!this.disabled) this.dispatchEvent(new MiniEvent('click')); }
  focus() { this.ownerDocument.activeElement = this; }
  matches(selector) {
    return selector.split(',').some(part => {
      part = part.trim();
      if (part.includes(' ')) { const bits = part.split(/\s+/); const last = bits.pop(); if (!this.matches(last)) return false; let parent = this.parentNode; while (parent) { if (parent.matches(bits.join(' '))) return true; parent = parent.parentNode; } return false; }
      const tag = part.match(/^[a-zA-Z][\w-]*/)?.[0];
      if (tag && this.tagName !== tag.toUpperCase()) return false;
      const id = part.match(/#([\w-]+)/)?.[1]; if (id && this.id !== id) return false;
      for (const [, name] of part.matchAll(/\.([\w-]+)/g)) if (!this.classList.contains(name)) return false;
      for (const [, name, , value] of part.matchAll(/\[([^=\]]+)(?:=(["']?)(.*?)\2)?\]/g)) {
        if (!this.hasAttribute(name)) return false;
        if (value !== undefined && this.getAttribute(name) !== value) return false;
      }
      return Boolean(this.tagName);
    });
  }
  querySelectorAll(selector) { return this.childNodes.flatMap(child => [...(child.matches(selector) ? [child] : []), ...child.querySelectorAll(selector)]); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] ?? null; }
  closest(selector) { return this.matches(selector) ? this : this.parentNode?.closest(selector) ?? null; }
}

function createDOM() {
  const document = {
    createElement(tag) { return new MiniNode(tag, this); },
    createTextNode(text) { const node = new MiniNode('', this); node.textContent = text; return node; },
    createDocumentFragment() { return new MiniNode('fragment', this); },
    querySelector(selector) { return this.body.querySelector(selector); },
    querySelectorAll(selector) { return this.body.querySelectorAll(selector); },
    addEventListener() {}, removeEventListener() {},
  };
  document.body = document.createElement('body'); document.head = document.createElement('head');
  globalThis.document = document; globalThis.HTMLElement = MiniNode; globalThis.Element = MiniNode;
  globalThis.Event = MiniEvent;
  globalThis.window = { document, location: { href: 'https://m03.test/' }, addEventListener() {}, removeEventListener() {} };
  const root = document.createElement('div'); document.body.append(root); return root;
}

const { mount } = await import('../web/modules/approvals/index.js');
const tick = () => new Promise(resolve => setImmediate(resolve));
async function settle() { for (let i = 0; i < 8; i++) await tick(); }
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }
const task = {
  id: 501, businessId: 9001, title: '接口测试唯一审批事项', status: 'LEADER_PENDING', stage: 'LEADER',
  version: 1, dataRevision: 1, participants: { submitterId: 's', leaderId: 'l', bpId: 'b' },
  assigneeId: 'l', actions: [{ action: 'APPROVE', label: '同意', enabled: true, reason: '' }], history: [],
};
const response = () => ({ ...task, task: structuredClone(task), tasks: [structuredClone(task)] });
function button(root, label) {
  const result = root.querySelectorAll('button').find(node => node.textContent.trim() === label);
  assert.ok(result, `Expected button ${label}; rendered: ${root.textContent}`); return result;
}
async function openTask(root, title = task.title) {
  const entry = root.querySelectorAll('button').find(node => node.textContent.includes(title));
  assert.ok(entry, `Expected task entry ${title}; rendered: ${root.textContent}`);
  entry.click(); await settle();
}
function fill(node, text) {
  assert.ok(node, 'Expected editable field'); node.value = String(text); node.dispatchEvent(new MiniEvent('input'));
}
async function confirmAction(root, label, comment) {
  const action = button(root, label); assert.equal(action.disabled, false, `${label} is enabled`);
  action.click(); await settle();
  if (comment !== undefined) fill(root.querySelector('textarea'), comment);
  button(root, `确认${label}`).click(); await settle();
}
async function switchDemoRole(root, actorId) {
  const select = root.querySelector('select'); assert.ok(select, 'Demo exposes role selection');
  select.value = actorId; select.dispatchEvent(new MiniEvent('change')); await settle();
}
function detailStatus(root) {
  const detail = root.querySelector('.yx-approvals__detail');
  const badge = detail?.querySelector('.yx-approvals__badge');
  assert.ok(badge, 'Selected task exposes a status badge'); return badge.textContent;
}
async function mountPromptly(root, context) {
  let timer;
  try {
    const result = await Promise.race([mount(root, context), new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('mount must return cleanup without waiting for the request')), 250); })]);
    assert.equal(typeof result, 'function', 'mount resolves to cleanup'); return result;
  } finally { clearTimeout(timer); }
}

test('demo remains local even when a request adapter is supplied', async () => {
  const root = createDOM(); let calls = 0;
  const cleanup = await mountPromptly(root, { mode: 'demo', request: async () => { calls++; throw new Error('Unexpected network request'); } });
  try {
    await settle(); assert.equal(calls, 0); assert.ok(root.textContent.trim().length > 0);
    const entry = root.querySelectorAll('button').find(node => node.getAttribute('aria-label')?.startsWith('办理 '));
    assert.ok(entry, 'demo contains an interactive synthetic task'); entry.click(); await settle();
    button(root, '同意').click(); await settle(); button(root, '确认同意').click(); await settle();
    button(root, '刷新').click(); await settle();
    assert.equal(calls, 0, 'demo details, mutations and refresh also remain local');
  }
  finally { cleanup(); }
});

test('live without a request adapter clearly reports 未接入', async () => {
  const root = createDOM(); const cleanup = await mountPromptly(root, { mode: 'live' });
  try { await settle(); assert.match(root.textContent, /未接入/); }
  finally { cleanup(); }
});

test('external abort prevents a late initial response from updating DOM', async () => {
  const root = createDOM(); const pending = deferred(); const controller = new AbortController(); let calls = 0;
  const cleanup = await mountPromptly(root, { mode: 'live', signal: controller.signal, request: () => { calls++; return pending.promise; } });
  try {
    await settle(); assert.equal(calls, 1); controller.abort(); await settle();
    const snapshot = root.innerHTML; pending.resolve(response()); await settle();
    assert.equal(root.innerHTML, snapshot); assert.ok(!root.textContent.includes(task.title));
  } finally { cleanup(); }
});

test('cleanup prevents a late initial response from updating DOM', async () => {
  const root = createDOM(); const pending = deferred();
  const cleanup = await mountPromptly(root, { mode: 'live', request: () => pending.promise });
  await settle(); cleanup(); const snapshot = root.innerHTML;
  pending.resolve(response()); await settle(); assert.equal(root.innerHTML, snapshot);
});

test('live request failures are readable and do not show local demo records', async () => {
  const root = createDOM(); let calls = 0;
  const cleanup = await mountPromptly(root, { mode: 'live', request: async () => { calls++; throw new Error('NETWORK_TEST_FAILURE'); } });
  try {
    await settle(); assert.equal(calls, 1); assert.match(root.textContent, /失败|异常|错误|网络|无法加载/);
    assert.doesNotMatch(root.textContent, /本地演示|演示数据|Demo/i);
    assert.ok(!root.textContent.includes(task.title));
  } finally { cleanup(); }
});

for (const wrapped of [true, false]) {
  test(`live accepts ${wrapped ? 'wrapped' : 'plain'} task list and detail responses`, async () => {
    const root = createDOM(); let calls = 0;
    const request = async () => {
      calls++;
      return calls === 1
        ? (wrapped ? { tasks: [structuredClone(task)] } : [structuredClone(task)])
        : (wrapped ? { task: structuredClone(task) } : structuredClone(task));
    };
    const cleanup = await mountPromptly(root, { mode: 'live', user: { id: 'l' }, request });
    try {
      await settle(); assert.match(root.textContent, new RegExp(task.title));
      const open = root.querySelectorAll('button').find(node => node.textContent.includes(task.title))
        ?? root.querySelectorAll('button').find(node => node.textContent.trim() === '办理');
      assert.ok(open, 'the returned task can be opened'); open.click(); await settle();
      assert.match(root.textContent, new RegExp(task.title)); assert.ok(button(root, '同意'));
    } finally { cleanup(); }
  });
}

test('duplicate confirmation clicks send one authoritative payload without actorId', async () => {
  const root = createDOM(); const posted = []; const pending = deferred();
  const request = async (path, options = {}) => {
    if ((options.method ?? 'GET').toUpperCase() === 'POST') { posted.push({ path, options }); return pending.promise; }
    return response();
  };
  const cleanup = await mountPromptly(root, { mode: 'live', user: { id: 'l' }, request });
  try {
    await settle();
    const open = root.querySelectorAll('button').find(node => node.textContent.includes(task.title))
      ?? root.querySelectorAll('button').find(node => node.textContent.trim() === '办理');
    assert.ok(open, `Expected task entry; rendered: ${root.textContent}`); open.click(); await settle();
    button(root, '同意').click(); await settle();
    const comment = root.querySelector('textarea'); assert.ok(comment, 'Confirmation offers a comment field');
    comment.value = '核对无误，同意'; comment.dispatchEvent(new MiniEvent('input')); comment.dispatchEvent(new MiniEvent('change'));
    const confirm = button(root, '确认同意'); confirm.click(); confirm.click(); await settle();
    assert.equal(posted.length, 1, 'only one action POST is allowed while in flight');
    assert.equal(typeof posted[0].options.body, 'string', 'Fetch-compatible JSON body');
    const payload = JSON.parse(posted[0].options.body);
    assert.equal(payload.expectedVersion, task.version); assert.equal(payload.expectedStage, task.stage);
    assert.equal(payload.comment, '核对无误，同意'); assert.equal(payload.action, 'APPROVE');
    assert.equal(typeof payload.requestId, 'string'); assert.ok(payload.requestId.length > 0);
    assert.equal(Object.hasOwn(payload, 'actorId'), false, 'the server derives actor identity');
  } finally { cleanup(); pending.resolve(response()); await settle(); }
});

test('live resubmission leaves the saved material revision to the server', async () => {
  const root = createDOM(); const posted = [];
  const returned = {
    ...structuredClone(task), status: 'RETURNED', stage: 'NONE', version: 4, dataRevision: 7,
    assigneeId: 's', actions: [{ action: 'RESUBMIT', label: '重新提交', enabled: true, reason: '' }],
  };
  const request = async (_path, options = {}) => {
    if ((options.method ?? 'GET').toUpperCase() === 'POST') {
      posted.push(JSON.parse(options.body));
      // Resubmitting unchanged saved material is a valid server response.
      return { task: { ...structuredClone(returned), status: 'LEADER_PENDING', stage: 'LEADER', version: 5, assigneeId: 'l', actions: [] } };
    }
    return { ...returned, task: structuredClone(returned), tasks: [structuredClone(returned)] };
  };
  const cleanup = await mountPromptly(root, { mode: 'live', user: { id: 'account-1', personCode: 's' }, request });
  try {
    await settle(); button(root, '全部').click(); await settle(); await openTask(root);
    button(root, '重新提交').click(); await settle();
    assert.equal(root.querySelectorAll('input').filter(node => node.type === 'number').length, 0, 'live mode does not ask users to provide a material revision');
    fill(root.querySelector('textarea'), '按已保存材料重新提交'); button(root, '确认重新提交').click(); await settle();
    assert.equal(posted.length, 1, 'unchanged material can be resubmitted');
    assert.deepEqual(Object.keys(posted[0]).sort(), ['action', 'comment', 'expectedStage', 'expectedVersion', 'requestId'].sort());
    assert.equal(posted[0].action, 'RESUBMIT'); assert.equal(posted[0].expectedVersion, 4);
    assert.equal(posted[0].expectedStage, 'NONE'); assert.equal(posted[0].comment, '按已保存材料重新提交');
    assert.equal(typeof posted[0].requestId, 'string'); assert.ok(posted[0].requestId.length > 0);
    assert.equal(Object.hasOwn(posted[0], 'dataRevision'), false);
    assert.equal(detailStatus(root), '待负责人审批');
    const materialFact = root.querySelectorAll('dl div').find(node => node.querySelector('dt')?.textContent === '材料版本');
    assert.equal(materialFact?.querySelector('dd')?.textContent, '7', 'the server-owned revision remains unchanged');
  } finally { cleanup(); }
});

test('live 我发起 uses personCode or person_code and never substitutes the account id', async () => {
  const cases = [
    { user: { id: 'different-account', personCode: 's' }, expected: true },
    { user: { id: 'different-account', person_code: 's' }, expected: true },
    { user: { id: 's' }, expected: false },
    { user: { id: 's', personCode: 'different-person' }, expected: false },
    { user: { id: 's', person_code: 'different-person' }, expected: false },
  ];
  for (const { user, expected } of cases) {
    const root = createDOM();
    const cleanup = await mountPromptly(root, { mode: 'live', user, request: async () => response() });
    try {
      await settle(); button(root, '我发起').click(); await settle();
      const entry = root.querySelectorAll('button').find(node => node.textContent.includes(task.title));
      assert.equal(Boolean(entry), expected, `person identity mapping for ${JSON.stringify(user)}`);
      if (!Object.hasOwn(user, 'personCode') && !Object.hasOwn(user, 'person_code')) assert.match(root.textContent, /当前身份信息未接入/);
    } finally { cleanup(); }
  }
});

test('rejected POST requires an explicit refresh before a new action attempt', async () => {
  const root = createDOM(); const posted = []; let currentVersion = 1; let detailReads = 0;
  const request = async (path, options = {}) => {
    if ((options.method ?? 'GET').toUpperCase() === 'POST') {
      posted.push(JSON.parse(options.body));
      if (posted.length === 1) throw Object.assign(new Error('stale version'), { status: 409 });
      return { task: { ...structuredClone(task), status: 'BP_PENDING', stage: 'BP', version: currentVersion + 1, actions: [] } };
    }
    if (path.endsWith(`/${task.id}`)) detailReads++;
    const current = { ...structuredClone(task), version: currentVersion };
    return { ...current, task: current, tasks: [current] };
  };
  const cleanup = await mountPromptly(root, { mode: 'live', user: { id: 'l' }, request });
  try {
    await settle(); await openTask(root); await confirmAction(root, '同意', '初次办理');
    assert.equal(posted.length, 1); assert.match(root.textContent, /提交结果未确认|刷新单据状态/);
    const disabledAction = button(root, '同意'); assert.equal(disabledAction.disabled, true);
    disabledAction.click(); await settle(); assert.equal(posted.length, 1, 'failure cannot silently retry');
    assert.equal(detailReads, 1, 'failure does not silently fetch and retry');
    currentVersion = 2; button(root, '刷新单据状态').click(); await settle();
    assert.equal(detailReads, 2); await confirmAction(root, '同意', '刷新后再次核对');
    assert.equal(posted.length, 2); assert.equal(posted[1].expectedVersion, 2);
    assert.equal(posted[1].expectedStage, 'LEADER'); assert.notEqual(posted[1].requestId, posted[0].requestId);
    assert.equal(detailStatus(root), '待 BP 审批');
  } finally { cleanup(); }
});

test('external abort during a mutation cancels its signal and prevents late DOM changes', async () => {
  const root = createDOM(); const pending = deferred(); const controller = new AbortController();
  let mutationSignal; let calls = 0;
  const request = async (_path, options = {}) => {
    calls++;
    if ((options.method ?? 'GET').toUpperCase() === 'POST') { mutationSignal = options.signal; return pending.promise; }
    return response();
  };
  const cleanup = await mountPromptly(root, { mode: 'live', signal: controller.signal, request });
  try {
    await settle(); await openTask(root); await confirmAction(root, '同意', '待发送');
    assert.ok(mutationSignal); assert.equal(mutationSignal.aborted, false);
    controller.abort(); await settle(); assert.equal(mutationSignal.aborted, true);
    const snapshot = root.innerHTML; const callsAtAbort = calls;
    pending.resolve({ task: { ...structuredClone(task), status: 'BP_PENDING', stage: 'BP', version: 2 } });
    await settle(); assert.equal(root.innerHTML, snapshot); assert.equal(calls, callsAtAbort);
  } finally { cleanup(); pending.resolve(response()); await settle(); }
});

test('unsupported or omitted modes remain unconnected and never call the adapter', async () => {
  for (const mode of [undefined, 'preview', 'LIVE']) {
    const root = createDOM(); let calls = 0;
    const cleanup = await mountPromptly(root, { mode, request: async () => { calls++; return response(); } });
    try {
      await settle(); assert.equal(calls, 0); assert.match(root.textContent, /未接入/);
      assert.doesNotMatch(root.textContent, /DEMO-|合成流程/);
      assert.equal(root.querySelectorAll('button').length, 0);
    } finally { cleanup(); }
  }
});

test('malformed live lists fail closed without local records or executable task entries', async () => {
  const malformedLists = [
    { tasks: 'not an array' },
    { tasks: [{ ...task, version: undefined }] },
    [structuredClone(task), { ...task, status: 'UNKNOWN_STATUS' }],
    { tasks: [{ ...task, stage: 'UNKNOWN_STAGE' }] },
    null,
  ];
  for (const result of malformedLists) {
    const root = createDOM(); let calls = 0;
    const cleanup = await mountPromptly(root, { mode: 'live', request: async () => { calls++; return result; } });
    try {
      await settle(); assert.equal(calls, 1); assert.match(root.textContent, /未接入/);
      assert.doesNotMatch(root.textContent, /DEMO-|合成流程/);
      assert.ok(!root.textContent.includes(task.title), 'partially valid lists are not rendered');
      assert.equal(root.querySelectorAll('button').filter(node => node.getAttribute('aria-label')?.startsWith('办理 ')).length, 0);
    } finally { cleanup(); }
  }
});

test('markup in remote titles and history comments is preserved as text nodes', async () => {
  const root = createDOM();
  const hostileTitle = '<img src=x onerror="globalThis.compromised=true"><script>alert(1)</script>';
  const hostileComment = '</p><svg onload="alert(2)"><script>alert(3)</script></svg>';
  const hostileTask = { ...structuredClone(task), title: hostileTitle, history: [{ action: 'SUBMIT', actorId: 's', from: null, to: 'LEADER_PENDING', version: 1, at: '2026-09-21T09:00:00+08:00', comment: hostileComment }] };
  const cleanup = await mountPromptly(root, { mode: 'live', request: async () => ({ ...hostileTask, task: hostileTask, tasks: [hostileTask] }) });
  try {
    await settle(); await openTask(root, hostileTitle);
    const title = root.querySelectorAll('h2').find(node => node.textContent === hostileTitle);
    const comment = root.querySelectorAll('p').find(node => node.textContent === hostileComment);
    for (const node of [title, comment]) {
      assert.ok(node, 'literal untrusted text is visible'); assert.equal(node.children.length, 0);
      assert.equal(node.childNodes.length, 1); assert.equal(node.childNodes[0].tagName, '');
    }
    assert.equal(root.querySelectorAll('img, script, svg').length, 0, 'untrusted markup creates no elements');
  } finally { cleanup(); }
});

test('demo leader approval hands off to BP and BP approval reaches training readiness', async () => {
  const root = createDOM(); let calls = 0;
  const cleanup = await mountPromptly(root, { mode: 'demo', request: async () => { calls++; throw new Error('Demo attempted live request'); } });
  try {
    await settle(); await openTask(root, 'DEMO-BIZ-001'); assert.equal(detailStatus(root), '待负责人审批');
    await confirmAction(root, '同意', '负责人已核验'); assert.equal(detailStatus(root), '待 BP 审批');
    assert.equal(button(root, '同意').disabled, true, 'leader cannot approve the BP step');
    await switchDemoRole(root, 'DEMO-BP'); await openTask(root, 'DEMO-BIZ-001');
    await confirmAction(root, '同意', 'BP 已核验'); assert.equal(detailStatus(root), '可交培训团队');
    assert.equal(button(root, '同意').disabled, true, 'completed task has no further approval');
    const detail = root.querySelector('.yx-approvals__detail');
    assert.match(detail.textContent, /负责人已核验/); assert.match(detail.textContent, /BP 已核验/);
    assert.equal(calls, 0);
  } finally { cleanup(); }
});

test('demo return validates comment, then submitter revises, resubmits and withdraws', async () => {
  const root = createDOM(); let calls = 0;
  const cleanup = await mountPromptly(root, { mode: 'demo', request: async () => { calls++; throw new Error('Demo attempted live request'); } });
  try {
    await settle(); await openTask(root, 'DEMO-BIZ-001');
    button(root, '退回').click(); await settle(); button(root, '确认退回').click(); await settle();
    assert.equal(detailStatus(root), '待负责人审批'); assert.match(root.textContent, /请填写退回原因/);
    fill(root.querySelector('textarea'), '请补充课程目标'); button(root, '确认退回').click(); await settle();
    assert.equal(detailStatus(root), '已退回'); assert.equal(button(root, '重新提交').disabled, true);
    const returnedAssignee = root.querySelectorAll('dl div').find(node => node.querySelector('dt')?.textContent === '当前办理人 ID');
    assert.equal(returnedAssignee?.querySelector('dd')?.textContent, 'DEMO-SUBMITTER', 'returned work is assigned back to the submitter');
    await switchDemoRole(root, 'DEMO-SUBMITTER'); button(root, '全部').click(); await settle();
    await openTask(root, 'DEMO-BIZ-001'); button(root, '重新提交').click(); await settle();
    const revision = root.querySelectorAll('input').find(node => node.type === 'number');
    fill(revision, '1'); button(root, '确认重新提交').click(); await settle();
    assert.equal(detailStatus(root), '已退回'); assert.match(root.textContent, /大于当前版本/);
    fill(root.querySelectorAll('input').find(node => node.type === 'number'), '2');
    fill(root.querySelector('textarea'), '已补充课程目标'); button(root, '确认重新提交').click(); await settle();
    assert.equal(detailStatus(root), '待负责人审批');
    const materialFact = root.querySelectorAll('dl div').find(node => node.querySelector('dt')?.textContent === '材料版本');
    assert.equal(materialFact?.querySelector('dd')?.textContent, '2');
    await confirmAction(root, '撤回', '暂缓本次申请'); assert.equal(detailStatus(root), '已撤回');
    assert.equal(button(root, '同意').disabled, true); assert.equal(button(root, '重新提交').disabled, false);
    await confirmAction(root, '重新提交', '恢复申请'); assert.equal(detailStatus(root), '待负责人审批');
    assert.equal(calls, 0);
  } finally { cleanup(); }
});

test('demo baseline allows only the submitter to withdraw returned work before team readiness', async () => {
  const root = createDOM(); let calls = 0;
  const cleanup = await mountPromptly(root, { mode: 'demo', request: async () => { calls++; throw new Error('Demo attempted live request'); } });
  try {
    await settle(); assert.match(root.textContent, /正式办理需完成人员配置与接口接入/);
    button(root, '全部').click(); await settle(); await openTask(root, 'DEMO-BIZ-003');
    assert.equal(detailStatus(root), '已退回'); assert.equal(button(root, '撤回').disabled, true);
    await switchDemoRole(root, 'DEMO-SUBMITTER'); await openTask(root, 'DEMO-BIZ-003');
    assert.equal(button(root, '撤回').disabled, false);
    await confirmAction(root, '撤回', '本次申请暂缓'); assert.equal(detailStatus(root), '已撤回');
    await openTask(root, 'DEMO-BIZ-004'); assert.equal(detailStatus(root), '可交培训团队');
    assert.equal(button(root, '撤回').disabled, true);
    assert.equal(calls, 0);
  } finally { cleanup(); }
});
