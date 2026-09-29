import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// Same dependency-free adapter approach as M05IntegrationUI.test.mjs. This module creates
// real DOM nodes (no HTML template), so the adapter checks text leaves and lifecycle ownership.
const modulePath = process.env.M05_POLICY_UI_MODULE || fileURLToPath(new URL('../web/modules/delivery-settlement/policy-preview.js', import.meta.url));
const source = await readFile(modulePath, 'utf8');
const { mountPolicyPreview } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

class Node {
  constructor(tagName, doc) {
    this.tagName = tagName.toUpperCase(); this.ownerDocument = doc;
    this.children = []; this.parentNode = null; this.attrs = new Map(); this.listeners = new Map();
    this.text = ''; this.disabled = false; this.open = false;
  }
  set textContent(value) { this.replaceChildren(); this.text = String(value); }
  get textContent() { return this.text + this.children.map((child) => child.textContent).join(''); }
  set innerHTML(_) { assert.fail('preview must never use innerHTML'); }
  setAttribute(key, value) { this.attrs.set(key, String(value)); }
  getAttribute(key) { return this.attrs.get(key) ?? null; }
  appendChild(child) { child.remove(); this.children.push(child); child.parentNode = this; return child; }
  replaceChildren(...nodes) { this.children.forEach((child) => { child.parentNode = null; }); this.children = []; this.text = ''; nodes.forEach((child) => this.appendChild(child)); }
  remove() { if (this.parentNode) this.parentNode.children = this.parentNode.children.filter((child) => child !== this); this.parentNode = null; }
  addEventListener(type, handler) { if (!this.listeners.has(type)) this.listeners.set(type, new Set()); this.listeners.get(type).add(handler); }
  removeEventListener(type, handler) { this.listeners.get(type)?.delete(handler); }
  async emit(type) { if (this.disabled) return; for (const handler of this.listeners.get(type) || []) await handler({ target: this }); }
}
const all = (node) => [node, ...node.children.flatMap(all)];
const visibleText = (node) => node.text + (node.tagName === 'DETAILS' && !node.open
  ? node.children.filter((child) => child.tagName === 'SUMMARY').map(visibleText).join('')
  : node.children.map(visibleText).join(''));

const issue = (code, message) => ({ code, message });
function preview(overrides = {}) {
  return {
    dispatch_id: 7, fact_version: 3, service_date: '2026-09-22', actual_minutes: '60', actual_hours: '1.33', payable_hours: '1.33',
    status: 'PREVIEW_READY', can_confirm: false, amount_scope: 'INDIVIDUAL', raw_amount_is_conditional: false,
    execution_basis: {
      execution_version: 'M05-USER-APPROVED-SOURCE-C7E8BAAA-V1', adoption_date: '2026-09-22',
      adoption_decision: '用户明确按所提供管理办法执行', source_file_name: '金尊公司内部培训师管理办法2.docx',
      source_sha256: 'c7e8baaa7ac874b2d8fded1551f82413f62f920bf99efe748aec830450599572',
      source_consultation_draft: true, source_publication_date: null, effective_from: null,
    },
    rate: { unit_rate: '150', reference_grade: 'SENIOR', activity: 'TEACHING', source_reference: 'ATTACHMENT-RATE-TABLE' },
    raw_amount: '199.50', final_amount: null, pool_raw_amount: null, pool_final_amount: null,
    individual_raw_amount: null, individual_final_amount: null,
    issues: [issue('MONEY_ROUNDING_NOT_CONFIGURED', '金额舍入口径尚未配置')],
    formal_missing_items: [issue('MONEY_ROUNDING_NOT_CONFIGURED', '金额舍入口径尚未配置'),
      issue('FORMAL_RULE_CONFIGURATION_NOT_CREATED', '正式配置尚待核对'),
      issue('FORMAL_SETTLEMENT_CONFIRMATION_REQUIRED', '正式确认尚待完成')],
    scenarios: [], ...structuredClone(overrides),
  };
}
function scenario(overrides = {}) {
  return { reference_grade: 'LECTURER', activity: 'TEACHING', teaching_day_type: 'WORKDAY',
    unit_rate: '100', condition: '计酬等级、研发身份和授课日别须经核实', conditional: true,
    payable_hours: '1.33', raw_amount: '133.00', ...overrides };
}
function fixture(steps, { aborted = false } = {}) {
  const doc = { nodes: [], createElement(tag) { const node = new Node(tag, doc); doc.nodes.push(node); return node; } };
  const root = doc.createElement('main');
  const original = doc.createElement('p'); original.textContent = '原项目区内容'; root.appendChild(original);
  const route = new AbortController(); if (aborted) route.abort();
  const requests = [];
  const controller = mountPolicyPreview(root, { signal: route.signal, api: async (path, options = {}) => {
    const step = steps.shift(); requests.push({ path, ...options });
    assert.ok(step, `unexpected request: ${path}`);
    return typeof step === 'function' ? step(path, options) : structuredClone(step);
  } });
  const slot = (name) => all(root).find((node) => node.getAttribute('data-m05-policy-slot') === name);
  const refresh = () => all(root).find((node) => node.getAttribute('data-m05-policy-action') === 'refresh');
  return { doc, root, original, route, requests, controller, slot, refresh,
    text: () => visibleText(root), open: (id = 7) => controller.open(id),
    readAgain: () => refresh().emit('click'), value: (name) => slot(name)?.textContent };
}
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const fail = (code, message = `PRIVATE_ERROR_${code}`) => () => { throw Object.assign(new Error(message), { code }); };

test('mount appends one original-style panel and only reads by dispatch ID', async () => {
  const f = fixture([preview()]); await f.open();
  assert.equal(f.root.children[0], f.original);
  assert.equal(f.root.children.length, 2);
  assert.equal(f.root.children[1].className, 'panel');
  assert.equal(f.requests[0].path, '/delivery-settlement/policy-preview?dispatch_id=7');
  assert.deepEqual(Object.keys(f.requests[0]).sort(), ['method', 'path', 'quiet', 'signal']);
  assert.equal(f.requests[0].method, 'GET');
  assert.equal(f.requests[0].quiet, true);
  assert.equal(all(f.root).filter((node) => node.tagName === 'BUTTON').length, 1);
  assert.equal(f.refresh().textContent, '读取最新预览');
  assert.equal(f.refresh().className, 'btn gray');
  assert.doesNotMatch(f.text(), /确认付款|发放课酬|保存草稿/);
});

test('raw calculation remains available while rounding is missing; source hash is closed by default', async () => {
  const p = preview(); const f = fixture([p]); await f.open();
  assert.equal(f.value('raw'), '199.50 元');
  assert.equal(f.value('final'), '待确定');
  assert.match(f.text(), /未舍入计算值/);
  assert.match(f.text(), /金额保留位数、舍入方式、按行处理范围/);
  assert.equal(f.text().match(/金额保留位数、舍入方式、按行处理范围/g).length, 1, 'duplicate missing issue only needs to be shown once');
  assert.match(f.text(), /用户明确按所提供管理办法执行/);
  assert.match(f.text(), /当前预览不可确认/);
  assert.equal(f.slot('sources').open, false);
  assert.ok(!f.text().includes(p.execution_basis.source_sha256));
  assert.ok(!f.text().includes(p.execution_basis.execution_version));
  assert.ok(f.slot('sources').textContent.includes(p.execution_basis.source_sha256));
  assert.match(f.slot('sources').textContent, /不能用用户确认日期代替/);
});

test('qualification uncertainty explicitly marks conditional raw arithmetic and cannot show final amount', async () => {
  const f = fixture([preview({ status: 'CONDITIONAL_PREVIEW', raw_amount_is_conditional: true,
    final_amount: '199.50', issues: [issue('APPOINTMENT_FACT_UNKNOWN', '工作开始前是否获聘待核'),
      issue('SHARED_DELIVERY_APPROVAL_UNKNOWN', '共享交付部尚未核准')] })]);
  await f.open();
  assert.equal(f.value('raw'), '199.50 元');
  assert.match(f.text(), /未舍入计算值（条件预览）/);
  assert.match(f.value('status'), /条件成立时适用/);
  assert.match(f.value('issues'), /是否获聘待核.*尚未核准/);
  assert.equal(f.value('final'), '待确定');
});

test('explicitly failed eligibility cannot show an accidentally supplied payable amount', async () => {
  const f = fixture([preview({ status: 'NOT_ELIGIBLE', raw_amount: '9911.12345', final_amount: '9911.12',
    scenarios: [scenario({ raw_amount: '8822.123' })],
    issues: [issue('WORK_BEFORE_APPOINTMENT', '获聘前工作不符合支付条件')] })]);
  await f.open();
  assert.equal(f.value('raw'), '计酬条件未满足');
  assert.equal(f.value('final'), '待确定');
  assert.match(f.value('status'), /尚未满足计酬要求/);
  assert.doesNotMatch(f.root.textContent, /9911|8822/);
});

test('history protection prevents new-rate or scenario amounts even in an inconsistent response', async () => {
  const f = fixture([preview({ status: 'HISTORY_PROTECTED', raw_amount: '7722.10', final_amount: '7722.10',
    rate: { activity: 'TEACHING', reference_grade: 'SPECIAL', unit_rate: '4433', source_reference: 'ATTACHMENT-RATE-TABLE' },
    scenarios: [scenario({ unit_rate: '8899', raw_amount: '11999' })],
    issues: [issue('HISTORICAL_FROZEN_SNAPSHOT_PRESERVED', '已冻结记录读取原记录')] })]);
  await f.open();
  assert.match(f.value('legacy'), /已冻结.*原记录.*历史待补发.*原规则/);
  assert.equal(f.value('unit-rate'), '依原记录核实');
  assert.equal(f.value('raw'), '依原记录核实');
  assert.equal(f.slot('scenarios'), undefined);
  assert.doesNotMatch(f.root.textContent, /7722|4433|8899|11999/);
});

test('joint development separates pool and individual and does not allocate by headcount', async () => {
  const f = fixture([preview({ amount_scope: 'DEVELOPMENT_POOL', raw_amount: '9999',
    rate: { activity: 'JOINT_DEVELOPMENT', reference_grade: 'SENIOR', unit_rate: '150' },
    pool_raw_amount: '199.5000', individual_raw_amount: null,
    issues: [issue('JOINT_INDIVIDUAL_ALLOCATION_PERCENT_MISSING', '个人分配比例待核')] })]);
  await f.open();
  assert.equal(f.value('pool-raw'), '199.5000 元');
  assert.equal(f.value('individual-raw'), '待核');
  assert.equal(f.value('pool-final'), '待确定');
  assert.equal(f.value('individual-final'), '待确定');
  assert.equal(f.slot('raw'), undefined);
  assert.match(f.text(), /不能按人数平均分配/);
  assert.doesNotMatch(f.text(), /9999/);
});

test('approved joint shares show only returned individual arithmetic, without rounding it', async () => {
  const f = fixture([preview({ amount_scope: 'DEVELOPMENT_POOL',
    rate: { activity: 'JOINT_DEVELOPMENT', reference_grade: 'SENIOR', unit_rate: '150' },
    pool_raw_amount: '199.5000', individual_raw_amount: '79.800000000', individual_final_amount: null })]);
  await f.open();
  assert.equal(f.value('individual-raw'), '79.800000000 元');
  assert.equal(f.value('individual-final'), '待确定');
});

test('unknown payable hours remain unknown despite recorded actual minutes and hours', async () => {
  const f = fixture([preview({ status: 'INCOMPLETE', payable_hours: null, raw_amount: null, rate: null,
    scenarios: [scenario({ payable_hours: null, raw_amount: null })] })]);
  await f.open();
  assert.equal(f.value('actual-minutes'), '60 分钟');
  assert.equal(f.value('actual-hours'), '1.33 课时');
  assert.equal(f.value('payable-hours'), '待核');
  assert.equal(f.value('raw'), '待核');
  assert.equal(f.value('final'), '待确定');
  assert.match(f.value('scenarios'), /按已录计酬课时试算（未舍入）/);
  assert.doesNotMatch(f.value('scenarios'), /133(?:\.00)? 元/);
});

test('conditional scenarios display exact server values and Chinese conditions without selecting a rate', async () => {
  const f = fixture([preview({ status: 'INCOMPLETE', rate: null, raw_amount: null,
    scenarios: [scenario({ raw_amount: '133.00000' }), scenario({ reference_grade: 'SENIOR',
      teaching_day_type: 'REST_DAY', unit_rate: '300', raw_amount: '399.00000' })] })]);
  await f.open();
  assert.equal(f.value('unit-rate'), '待核');
  assert.equal(f.value('raw'), '待核');
  assert.match(f.value('scenarios'), /尚未选定本次费率/);
  assert.match(f.value('scenarios'), /133\.00000 元.*高级讲师.*休息日.*399\.00000 元/);
  assert.match(f.value('scenarios'), /等级、研发身份和授课日别须经核实/);
});

test('decimal strings, long precision and explicit zeros survive unchanged', async () => {
  const long = '123456789012345678901234567890.12345678901234567890';
  const f = fixture([preview({ actual_minutes: '0', actual_hours: '0.00', payable_hours: '0.000',
    raw_amount: long, final_amount: '0.00' })]);
  await f.open();
  assert.equal(f.value('actual-minutes'), '0 分钟');
  assert.equal(f.value('payable-hours'), '0.000 课时');
  assert.equal(f.value('raw'), `${long} 元`);
  assert.equal(f.value('final'), '0.00 元');
});

test('numeric or non-decimal amounts fail closed including conditional rows', async () => {
  for (const patch of [{ raw_amount: 199.5 }, { payable_hours: '1e2' }, { final_amount: '-1' },
    { rate: { unit_rate: 150 } }, { scenarios: [scenario({ raw_amount: 133 })] }]) {
    const f = fixture([preview(patch)]); await f.open();
    assert.equal(f.slot('content').children.length, 0);
    assert.match(f.value('notice'), /数值格式不正确/);
  }
});

test('all server text is inert text; no server value becomes HTML or an attribute', async () => {
  const attack = '<img src=x onerror="globalThis.pwned=1"><script>globalThis.pwned=2</script>';
  const f = fixture([preview({ execution_basis: { ...preview().execution_basis, adoption_decision: attack, source_file_name: attack },
    rate: null, scenarios: [scenario({ condition: attack })], issues: [issue('UNKNOWN_TEST_ISSUE', attack)] })]);
  await f.open();
  assert.match(f.value('basis'), /<img src=x/);
  assert.ok(f.value('issues').includes(attack));
  assert.ok(f.value('scenarios').includes(attack));
  assert.ok(f.slot('sources').textContent.includes(attack));
  assert.equal(f.doc.nodes.filter((node) => ['IMG', 'SCRIPT', 'IFRAME'].includes(node.tagName)).length, 0);
  assert.ok(f.doc.nodes.every((node) => [...node.attrs.values()].every((value) => !value.includes(attack))));
  assert.doesNotMatch(source, /innerHTML|insertAdjacentHTML|outerHTML|eval\(/);
});

test('switching dispatch clears all previous facts, amounts and sources immediately; late success cannot overwrite', async () => {
  const late = deferred();
  const f = fixture([preview(), () => late.promise, preview({ dispatch_id: 8, raw_amount: '888.000' })]);
  await f.open();
  const oldRefresh = f.readAgain();
  assert.equal(f.slot('content').children.length, 0);
  assert.equal(f.slot('sources'), undefined);
  assert.doesNotMatch(f.text(), /199\.50/);
  await f.open(8);
  assert.equal(f.requests[1].signal.aborted, true);
  assert.equal(f.value('dispatch'), '8');
  late.resolve(preview({ raw_amount: '111.11' })); await oldRefresh;
  assert.equal(f.value('raw'), '888.000 元');
  assert.equal(f.value('dispatch'), '8');
  assert.equal(f.refresh().disabled, false);
});

test('late error from a cancelled session cannot clear the current preview or change its status', async () => {
  const late = deferred(); const f = fixture([() => late.promise, preview({ dispatch_id: 8 })]);
  const oldOpen = f.open(); await f.open(8);
  late.reject(Object.assign(new Error('old auth error'), { code: 401 })); await oldOpen;
  assert.equal(f.value('dispatch'), '8');
  assert.equal(f.value('raw'), '199.50 元');
  assert.match(f.value('notice'), /预览已更新/);
  assert.equal(f.refresh().disabled, false);
});

test('refresh clears values before request and a network failure never leaves old amounts', async () => {
  const late = deferred(); const f = fixture([preview(), () => late.promise, preview({ raw_amount: '250.125' })]);
  await f.open(); const pending = f.readAgain();
  assert.equal(f.slot('content').children.length, 0);
  assert.equal(f.refresh().disabled, true);
  await f.readAgain(); assert.equal(f.requests.length, 2, 'busy refresh makes no duplicate request');
  late.reject(new Error('private stack/record/199.50')); await pending;
  assert.equal(f.slot('content').children.length, 0);
  assert.match(f.value('notice'), /暂时无法读取/);
  assert.doesNotMatch(f.text(), /private|199\.50/);
  assert.equal(f.refresh().disabled, false);
  await f.readAgain(); assert.equal(f.value('raw'), '250.125 元');
});

test('401 and 403 remove old data and disable refresh without automatic follow-up requests', async () => {
  for (const status of [401, 403]) {
    const f = fixture([preview(), fail(status)]); await f.open(); await f.readAgain();
    assert.equal(f.slot('content').children.length, 0);
    assert.equal(f.refresh().disabled, true);
    assert.doesNotMatch(f.text(), /199\.50|原文件|PRIVATE_ERROR/);
    assert.match(f.value('notice'), status === 401 ? /重新登录/ : /没有查看.*权限/);
    await f.readAgain(); assert.equal(f.requests.length, 2);
    if (status === 401) { await f.open(); assert.equal(f.requests.length, 2, 'expired login requires a fresh host session'); }
  }
});

test('route abort cancels pending request, removes only owned DOM, and ignores late data', async () => {
  const late = deferred(); const f = fixture([preview(), () => late.promise]);
  await f.open(); const pending = f.readAgain(); const detachedPanel = f.root.children[1];
  f.route.abort();
  assert.equal(f.requests[1].signal.aborted, true);
  assert.deepEqual(f.root.children, [f.original]);
  assert.doesNotMatch(detachedPanel.textContent, /199\.50/);
  late.resolve(preview()); await pending;
  assert.deepEqual(f.root.children, [f.original]);
  f.controller.cleanup(); f.controller.cleanup(); await f.open();
  assert.equal(f.requests.length, 2);
});

test('cleanup clears resolved amounts and unregisters the refresh listener', async () => {
  const f = fixture([preview()]); await f.open(); const button = f.refresh(); const content = f.slot('content');
  f.controller.cleanup();
  assert.equal(content.children.length, 0);
  assert.equal(button.listeners.get('click').size, 0);
  assert.equal(f.root.textContent, '原项目区内容');
  await button.emit('click'); assert.equal(f.requests.length, 1);
});

test('an already aborted host signal creates no visible panel and cannot issue requests', async () => {
  const f = fixture([], { aborted: true }); await f.open();
  assert.deepEqual(f.root.children, [f.original]); assert.equal(f.requests.length, 0);
});

test('bad IDs clear old preview and fail before making a request', async () => {
  for (const id of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1, '01', '7&actual_hours=999', undefined]) {
    const f = fixture([preview()]); await f.open();
    await assert.rejects(f.controller.open(id), /有效的授课安排/);
    assert.equal(f.requests.length, 1);
    assert.equal(f.slot('content').children.length, 0);
    assert.equal(f.refresh().disabled, true);
  }
});

test('mismatched identity, missing version, unknown status or confirmation authority fails closed', async () => {
  for (const patch of [{ dispatch_id: 8 }, { fact_version: null }, { fact_version: 1.1 },
    { status: 'READY_TO_PAY' }, { can_confirm: true }, { scenarios: {} }, { scenarios: [scenario({ conditional: false })] }]) {
    const f = fixture([preview(patch)]); await f.open();
    assert.equal(f.slot('content').children.length, 0);
    assert.match(f.value('notice'), /数据尚不完整/);
    assert.equal(f.value('raw'), undefined);
  }
});
