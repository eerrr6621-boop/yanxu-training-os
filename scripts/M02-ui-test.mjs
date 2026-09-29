import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

// Data URLs load the browser ES modules without changing the shared package type.
const modelPath = new URL('../web/modules/intake/model.js', import.meta.url);
const indexPath = new URL('../web/modules/intake/index.js', import.meta.url);
const modelSource = await readFile(modelPath, 'utf8');
const modelUrl = `data:text/javascript;base64,${Buffer.from(modelSource).toString('base64')}`;
const model = await import(modelUrl);
const uiSource = (await readFile(indexPath, 'utf8')).replace("'./model.js'", JSON.stringify(modelUrl));
const { mount } = await import(`data:text/javascript;base64,${Buffer.from(uiSource).toString('base64')}`);
let checks = 0;
function check(ok, message) { assert.ok(ok, message); checks += 1; }
const valid = {
  title: '合成需求', business_path: 'direct', organization_code: 'DEMO-ORG',
  internal_contact_code: 'DEMO-INTERNAL', customer_contact_code: 'DEMO-CUSTOMER',
  category_text: '待确认分类', delivery_mode_text: '待确认方式', period_text: '待确认时段',
  duration_minutes: '090.00', participant_count: '24', objectives: '合成目标',
};

check(model.validateDraft({}, 'draft').valid, 'empty draft is allowed');
for (const value of [null, undefined, [], 1, 'invalid']) check(!model.validateDraft(value).valid, 'non-object input rejected');
check(model.validateDraft({ duration_minutes: null }).valid, 'null draft field is empty');
check(!model.validateDraft({ duration_minutes: 22.5 }).valid, 'numeric value must be a decimal string');
check(!model.validateDraft({ participant_count: 24 }).valid, 'count must be an integer string');
check(!model.validateDraft({ filler_code: 'FORGED' }).valid, 'unknown session field rejected');
check(!model.validateDraft(JSON.parse('{"__proto__":"FORGED"}')).valid, 'unknown prototype field is safely rejected');
check(model.validateDraft(valid, 'submit').valid, 'complete direct submission passes');
for (const key of Object.keys(valid)) {
  if (key === 'customer_contact_code') continue;
  check(!model.validateDraft({ ...valid, [key]: '   ' }, 'submit').valid, `${key} is required for submission`);
}
check(model.validateDraft({ ...valid, customer_contact_code: '' }, 'submit').valid, 'customer contact may be completed later');
const withoutCustomer = { ...valid }; delete withoutCustomer.customer_contact_code;
check(model.validateDraft(withoutCustomer, 'submit').valid, 'missing optional customer contact does not block submission');
check(model.validateDraft(model.INITIAL_DRAFT, 'draft').valid, 'defaults allow saving a draft');
check(!model.validateDraft(model.INITIAL_DRAFT, 'submit').valid, 'defaults do not bypass required information');
check(model.INITIAL_DRAFT.business_path === 'direct' && model.INITIAL_DRAFT.period_text === '待协调', 'default route and unresolved period explicit');
check(model.validateDraft({ ...valid, category_text: '自定义类别', delivery_mode_text: '客户指定安排', period_text: '待协调' }, 'submit').valid, 'starter suggestions remain editable');
check(!model.validateDraft({ ...valid, business_path: 'other' }).valid, 'unknown path rejected');
check(!model.validateDraft({ ...valid, business_path: 'bid' }, 'submit').valid, 'bid needs office reference');
check(model.validateDraft({ ...valid, business_path: 'bid', external_approval_ref: 'DEMO-OA' }, 'submit').valid, 'bid reference accepted');
check(!model.validateDraft({ ...valid, external_approval_ref: 'DEMO-OA' }).valid, 'direct reference conflict rejected');
for (const field of ['duration_minutes', 'participant_count', 'budget_amount']) {
  for (const value of ['NaN', 'Infinity', '-Infinity', '1e3', '0x10', '12abc', '+1', '-1', '.5', '1.', '1 2', '１']) {
    check(!model.validateDraft({ [field]: value }).valid, `${field} rejects ${value} even on draft`);
  }
  check(!model.validateDraft({ [field]: '1'.repeat(121) }).valid, `${field} 120-character technical bound`);
  check(model.validateDraft({ [field]: '1'.repeat(120) }).valid, `${field} supports large exact strings`);
}
for (const value of ['0', '0.0', '000.000']) check(!model.validateDraft({ duration_minutes: value }).valid, 'zero minutes rejected');
for (const value of ['0', '1.0', '1.5']) check(!model.validateDraft({ participant_count: value }).valid, 'count must be positive integer syntax');
for (const value of ['0', '0.00', '000', '22.50']) check(model.validateDraft({ budget_amount: value }).valid, 'nonnegative budget allowed');
check(model.validateDraft({ duration_minutes: ' 090.00 ' }).draft.duration_minutes === '090.00', 'outer whitespace trimmed; decimal precision and zeros preserved');
check(model.validateDraft({ participant_count: '001' }).draft.participant_count === '001', 'integer text preserved');
check(model.validateDraft({ budget_amount: '  ' }).valid, 'blank optional budget allowed');
check(!model.validateDraft({ organization_code: 'x'.repeat(121) }).valid, 'code length bound');
check(!model.validateDraft({ objectives: 'x'.repeat(4001) }).valid, 'objective length bound');
check(!model.validateDraft({ title: 'x'.repeat(501) }).valid, 'text length bound');
for (const value of ['0000-01-01', '2025-02-29', '2026-04-31', '2026-13-01', '2026-00-10', '2026-01-00', '26-01-01', '2026-1-01']) {
  check(!model.validateDraft({ expected_start_date: value }).valid, `invalid date ${value}`);
}
for (const value of ['0001-01-01', '2024-02-29', '9999-12-31']) check(model.validateDraft({ expected_start_date: value }).valid, `valid date ${value}`);
check(model.validateDraft({ expected_end_date: '2026-01-01' }).valid, 'one optional date allowed');
check(model.validateDraft({ expected_start_date: '2026-01-01', expected_end_date: '2026-01-01' }).valid, 'same-day allowed');
check(!model.validateDraft({ expected_start_date: '2026-02-01', expected_end_date: '2026-01-31' }).valid, 'date order enforced');

for (const [minutes, numerator, denominator, repeating] of [
  ['45', '1', '1', false], ['22.5', '1', '2', false], ['0.1', '1', '450', true],
  ['15', '1', '3', true], ['50', '10', '9', true], ['090.00', '2', '1', false],
  ['0.000000000000000000000000000000000000000000045', '1', '1000000000000000000000000000000000000000000000', false],
]) {
  const result = model.lessonUnits(minutes);
  check(result.valid && result.numerator === numerator && result.denominator === denominator && result.repeating === repeating, `${minutes} exact rational units`);
  check(!result.text.includes('/'), 'business display does not expose fraction notation');
  check(result.text.includes(minutes) && result.originalMinutes === minutes, 'every display retains original minutes');
}
check(model.lessonUnits('22.5').text === '0.50 基本课时（原始时长 22.5 分钟）', 'terminating result also has two decimals and original minutes');
for (const [minutes, hours] of [
  ['60', '1.33'], ['50', '1.11'], ['45', '1.00'], ['090.00', '2.00'], ['67.50', '1.50'],
  ['0.001', '0.00'], ['0.22499999', '0.00'], ['0.225', '0.01'], ['0.22500001', '0.01'],
  ['44.77499999', '0.99'], ['44.775', '1.00'], ['45.22499999', '1.00'], ['45.225', '1.01'],
  ['67.275', '1.50'], ['9007199254740993', '200159983438688.73'],
]) {
  const converted = model.lessonUnits(minutes);
  check(converted.classHours === hours && converted.text.includes(`${hours} 基本课时`), `M05-matched two decimal value for ${minutes}`);
  check(converted.originalMinutes === minutes && !converted.text.includes('待确认'), 'raw minutes retained without obsolete precision warning');
  check(converted.ruleVersion === 'M05-CLASS45-2DP-V1' && converted.roundingPolicy === 'HALF_UP' && converted.scale === 2, 'M05-matched display rule metadata');
}
check(model.lessonUnits('0.000000000000000000000000000045').classHours === '0.00', 'existing M02 high precision input remains supported');
check(!('payable_hours' in model.lessonUnits('60')) && !('amount' in model.lessonUnits('60')), 'basic conversion never produces pay facts');
for (const value of ['', '0', 'NaN', '1e3', '1'.repeat(121)]) check(!model.lessonUnits(value).valid, 'invalid unit input is safe');
check(!('filler_code' in model.createDraft({ filler_code: 'forged' })), 'filler is not a draft field');
check(!('status' in model.createDraft({ status: 'approved' })), 'client draft cannot supply approval status');

const records = model.createSyntheticQueue();
check(records.length === 6 && records.every(record => record.synthetic === true), 'six explicitly synthetic samples');
check(records.filter(model.canAccept).length === 2, 'only approved direct and won bid are ready');
for (const record of records.filter(record => ['leader_pending', 'bp_pending', 'bid_pending', 'lost'].includes(record.status))) {
  check(!model.acceptSynthetic(record).ok, `${record.status} cannot be accepted`);
}
for (const record of records.filter(model.canAccept)) {
  const accepted = model.acceptSynthetic(record);
  check(accepted.ok && accepted.record.status === 'accepted' && record.status !== 'accepted', 'accept is immutable');
  check(!model.acceptSynthetic(accepted.record).ok, 'duplicate acceptance rejected');
  check(!model.acceptSynthetic({ ...record, synthetic: false }).ok, 'nonsynthetic record cannot enter demo acceptance');
}
const pendingBid = records.find(record => record.status === 'bid_pending');
for (const result of ['won', 'lost']) {
  const registered = model.registerSyntheticBidResult(pendingBid, result, 'DEMO-RESULT');
  check(registered.ok && registered.record.status === result, `${result} registered`);
  check(!model.registerSyntheticBidResult(registered.record, result, 'DUP').ok, 'result cannot be overwritten');
  check(model.canAccept(registered.record) === (result === 'won'), 'only won result enables acceptance');
}
check(!model.registerSyntheticBidResult(pendingBid, 'other', 'REF').ok, 'unknown result rejected');
check(!model.registerSyntheticBidResult(pendingBid, 'won', '  ').ok, 'result reference required');
check(!model.registerSyntheticBidResult(pendingBid, 'won', 'x'.repeat(501)).ok, 'result reference bound');
check(!model.registerSyntheticBidResult(records[0], 'won', 'REF').ok, 'direct record has no bid result');
check(!model.createSyntheticSubmission({}, 'DEMO-NEW', '合成会话').ok, 'incomplete submission blocked');
const direct = model.createSyntheticSubmission(valid, 'DEMO-NEW', '合成会话').record;
check(direct.status === 'leader_pending' && !model.canAccept(direct), 'direct submission starts at leader pending');
check(!('bid_result' in direct) && !('external_approval_ref' in direct), 'direct submission has no fake bid fields');
check(direct.duration_minutes === '090.00', 'submitted minutes preserve decimal precision');
const bid = model.createSyntheticSubmission({ ...valid, business_path: 'bid', external_approval_ref: 'DEMO-OA' }, 'DEMO-BID', '合成会话').record;
check(bid.status === 'bid_pending' && !model.canAccept(bid), 'bid submission waits for result');

// Minimal root doubles test lifecycle and root ownership; real DOM interactions
// are verified separately in the browser by the integration owner.
class TestNode {
  constructor() { this.textContent = ''; this.innerHTML = ''; this.hidden = false; this.disabled = false; this.classList = { toggle() {} }; }
}
class TestRoot {
  constructor() { this.html = 'original'; this.writes = 0; this.nodes = new Map(); this.listeners = new Map(); this.removed = []; }
  set innerHTML(value) { this.html = value; this.writes += 1; }
  get innerHTML() { return this.html; }
  replaceChildren() {}
  querySelector(selector) { if (!this.nodes.has(selector)) this.nodes.set(selector, new TestNode()); return this.nodes.get(selector); }
  addEventListener(type, listener) { this.listeners.set(type, listener); }
  removeEventListener(type, listener) { if (this.listeners.get(type) === listener) this.listeners.delete(type); this.removed.push(type); }
}
let requests = 0;
const context = { mode: 'demo', user: { synthetic: true, user_code: 'DEMO-A', display_name: '<img src=x onerror=alert(1)>' }, request() { requests += 1; throw new Error('No requests allowed'); } };
const preAbort = new AbortController(); preAbort.abort();
const untouched = new TestRoot();
const earlyCleanup = await mount(untouched, { ...context, signal: preAbort.signal });
earlyCleanup();
check(untouched.writes === 0 && untouched.listeners.size === 0, 'pre-aborted mount never writes or listens');
const live = new TestRoot();
const liveCleanup = await mount(live, { ...context, mode: 'live' });
check(live.innerHTML.includes('正式环境尚未接入') && !live.innerHTML.includes('SAMPLE-'), 'live explicitly unavailable, no demo fallback');
liveCleanup(); liveCleanup();
check(live.writes === 1, 'live cleanup idempotent and leaves host DOM ownership');
const invalidMode = new TestRoot();
await mount(invalidMode, { ...context, mode: undefined });
check(invalidMode.innerHTML.includes('正式环境尚未接入'), 'unspecified mode cannot silently activate demo');
const abort = new AbortController(), root = new TestRoot();
const dispose = await mount(root, { ...context, signal: abort.signal });
check(root.innerHTML.includes('&lt;img') && !root.innerHTML.includes('<img src=x'), 'session text HTML-escaped');
check(root.listeners.size === 4, 'demo listeners limited to its root');
check(root.innerHTML.includes('data-draft-form') && root.querySelector('[data-queue]').innerHTML.includes('SAMPLE-06'), 'demo renders form and synthetic queue');
const staleHandlers = [...root.listeners.values()];
const before = root.innerHTML + [...root.nodes.values()].map(node => node.innerHTML + node.textContent).join();
abort.abort(); dispose();
check(root.listeners.size === 0, 'abort and cleanup remove all listeners');
for (const handler of staleHandlers) handler({});
const after = root.innerHTML + [...root.nodes.values()].map(node => node.innerHTML + node.textContent).join();
check(before === after, 'stale events after disposal cannot update page');
const missingUser = new TestRoot();
await mount(missingUser, { ...context, user: { user_code: 'REAL-DO-NOT-COPY', display_name: 'real user' } });
check(!missingUser.innerHTML.includes('REAL-DO-NOT-COPY') && !missingUser.innerHTML.includes('real user'), 'demo does not copy real session identity');
check(missingUser.innerHTML.includes('未提供合成会话') && /type="submit"[^>]*disabled/.test(missingUser.innerHTML), 'demo submission needs synthetic session');
check(requests === 0, 'demo/live/lifecycle make zero context requests');
console.log(`M02 UI/model: ${checks} checks passed (validation, exact units, synthetic transitions, escaping, request isolation, lifecycle)`);
