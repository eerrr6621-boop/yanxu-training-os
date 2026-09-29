'use strict';
// Synthetic DOM and transport only. No service, upload, real workbook or database.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const repo = process.env.YANXU_APP || path.resolve(__dirname, '..');
const support = fs.readFileSync(path.join(repo, 'scripts/M01-account-bindings-check.mjs'), 'utf8');
const { NodeStub } = vm.runInNewContext(support.slice(support.indexOf('const decode ='), support.indexOf('function fixture()')) + '\n({NodeStub})', {});
const filename = path.join(__dirname, '../web/modules/survey-results/formal-workspace.js'), source = fs.readFileSync(filename, 'utf8');
const sandbox = { AbortController, Uint8Array, Date, console, btoa: s => Buffer.from(s, 'binary').toString('base64') };
vm.createContext(sandbox); vm.runInContext(source.replace('export function mount', 'function mount') + '\nglobalThis.mount = mount;', sandbox);
const clone = structuredClone, err = status => Object.assign(Error('PRIVATE server /tmp/secret raw-row'), { status }), tick = () => new Promise(r => setImmediate(r));
const defer = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
let checks = 0, scenarios = 0;
const check = (value, label) => { assert.ok(value, label); checks++; };
const equal = (actual, expected, label) => { assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected, label); checks++; };
const at = '2026-09-23T01:00:00Z';
function policy() { return { version: 'P-1', zeroValid: true, evidenceRef: 'PRIVATE-EVIDENCE', confirmed: true, reviewerRoles: ['PRIVATE-ROLE'], blank: 'EXCLUDE', invalid: 'EXCLUDE', denominator: 'PER_QUESTION_VALID', rows: 'KEEP_ALL', displayScale: 2, rounding: 'HALF_UP', overallQuestionKey: 'q10', independentReviewer: true, replacement: 'REVIEW_BEFORE_REPLACE' }; }
function summary() { return { responseRowCount: 2, overallQuestionKey: 'q10', questions: Array.from({ length: 10 }, (_, i) => ({ key: 'q' + (i + 1), label: '合成题目 ' + (i + 1), column: String.fromCharCode(65 + i), validCount: 1, blankCount: 1, invalidCount: 0, sumText: '8.5', averageText: '8.50' })) }; }
function record(id = 1, patch = {}) { return { import_id: id, series_id: id, replaces_id: 0, state: 'PENDING_REVIEW', current: false, summary: summary(), policy: policy(), importer_code: 'PRIVATE-P1', imported_at: at, reason: '', review_reason: '', reviewer_code: '', reviewed_at: '', review_event_id: 0, ...patch }; }
function snapshot(records = [], version = records.length) { return { project_id: 12, version, records, policy: policy(), capabilities: { prepare: true, review: true }, synthetic: false, private_file_hash: 'PRIVATE-HASH' }; }
function approved() { return record(1, { state: 'APPROVED', current: true, reviewer_code: 'PRIVATE-P2', reviewed_at: at, review_event_id: 2, review_reason: '独立复核意见' }); }
function prepared(body, version = 0) { return { state: 'READY_TO_CONFIRM', canConfirm: true, policyConfirmed: true, review_required: true, summary: summary(), policy: policy(), project_id: body.projectId, expected_version: version, replaces_id: body.replacesId, reason: body.reason, preview_token: 'a'.repeat(64), expires_at: '2026-09-23T01:05:00Z', duplicate: { status: 'NONE', import_ids: [] } }; }
function receipt(body, kind, version = 1, replayed = false) { return { project_id: body.project_id, result_version: version, state: replayed ? 'RECORDED' : kind === 'confirm' ? 'PENDING_REVIEW' : body.decision === 'APPROVE' ? 'APPROVED' : 'REJECTED', replayed, reload_required: true }; }
function harness({ data = snapshot(), api, root = new NodeStub(), signal } = {}) {
  const h = { root, calls: [], user: { uid: 1, role: 'manager' }, current: true, now: Date.parse(at), unauthorized: 0, saved: 0, dirty: [], discard: false, data: clone(data), requestN: 0 };
  h.host = { projectId: 12, projectName: '合成项目', getUser: () => h.user, isCurrent: () => h.current, signal, now: () => h.now,
    requestId: () => 'synthetic-request-' + (++h.requestN), confirmDiscard: () => h.discard,
    onUnauthorized: () => h.unauthorized++, onSaved: () => h.saved++, onDirtyChange: value => h.dirty.push(value),
    api: async (url, opts) => { h.calls.push({ url, method: opts.method, body: clone(opts.body), signal: opts.signal }); if (api) return api(url, opts, h);
      if (!opts.body) return clone(h.data);
      if (url.endsWith('/prepare')) return prepared(opts.body, h.data.version);
      if (url.endsWith('/confirm')) { h.data = snapshot([record(1)], 1); return receipt(opts.body, 'confirm'); }
      if (url.endsWith('/review')) { h.data = snapshot([record(1, { state: opts.body.decision === 'APPROVE' ? 'APPROVED' : 'REJECTED', current: opts.body.decision === 'APPROVE', review_reason: opts.body.reason, reviewed_at: at, reviewer_code: 'PRIVATE-P2', review_event_id: 2 })], 2); return receipt(opts.body, 'review', 2); }
      throw Error('unexpected route'); } };
  h.node = key => h.root.querySelector('[data-formal-' + key + ']');
  h.click = key => { check(!!h.node(key), 'control exists: ' + key); return h.node(key).onclick(); };
  h.change = (key, value, event = 'onchange') => { const n = h.node(key); check(!!n, 'field exists: ' + key); n.value = value; return n[event](); };
  h.selectFile = async (patch = {}) => { const file = { name: 'synthetic.xlsx', size: 4, arrayBuffer: async () => Uint8Array.from([80, 75, 3, 4]).buffer, ...patch }; h.node('file').files = [file]; await h.node('file').onchange(); };
  h.ack = () => { h.node('ack').checked = true; h.node('ack').onchange(); };
  h.posts = () => h.calls.filter(c => c.body); h.controller = sandbox.mount(root, h.host); return h;
}
async function readyPreview(h) { await h.controller.ready; await h.selectFile(); await h.click('prepare'); h.ack(); }
async function scenario(label, test) { await test(); scenarios++; console.log('PASS ' + label); }
(async () => {
  await scenario('No policy preserves a read-only explanation and never prepares', async () => {
    const data = snapshot(); data.policy = null; data.capabilities = { prepare: false, review: false };
    const h = harness({ data }); check(await h.controller.ready, 'read succeeds'); check(h.root.textContent.includes('该机构尚未启用正式统计规则') && h.root.textContent.includes('草案'), 'draft remains separate'); check(!h.node('file') && !h.node('prepare'), 'no enabled formal input'); equal(h.posts().length, 0, 'GET only'); h.controller.destroy();
  });
  await scenario('Prepare has five upload fields; confirmation needs acknowledgement and re-reads', async () => {
    const h = harness(); await h.controller.ready; equal(h.calls[0].url, '/survey-results?project_id=12', 'exact fixed project query'); await h.selectFile(); check(h.controller.isDirty(), 'local file dirty'); equal(h.posts().length, 0, 'selecting file is local'); await h.click('prepare');
    equal(Object.keys(h.posts()[0].body).sort(), ['fileName', 'projectId', 'reason', 'replacesId', 'xlsxBase64'], '5-field upload'); equal(h.posts()[0].url, '/survey-response-imports/prepare', '7MiB existing specialized route'); check(h.node('confirm').disabled, 'checkbox required'); await h.click('confirm'); equal(h.posts().length, 1, 'unchecked synthetic click denied'); h.ack(); await h.click('confirm');
    equal(Object.keys(h.posts()[1].body).sort(), ['acknowledged', 'preview_token', 'project_id', 'request_id'], 'confirmation does not post aggregate'); equal(h.posts()[1].body.preview_token, 'a'.repeat(64), 'same token used'); equal(h.calls.at(-1).method, 'GET', 'success authoritative reread'); equal(h.saved, 1, 'host saved notified'); check(!h.node('confirm'), 'token removed'); check(h.root.textContent.includes('操作已记录'), 'commit acknowledged'); h.controller.destroy();
  });
  await scenario('Review and import reasons stay distinct; only latest tip can be corrected', async () => {
    const old = approved(), next = record(3, { series_id: 1, replaces_id: 1, reason: '修正遗漏的评分', summary: { ...summary(), responseRowCount: 2 } }); const h = harness({ data: snapshot([old, next], 3) }); await h.controller.ready;
    h.root.querySelector('[data-formal-detail="1"]').onclick(); check(h.root.textContent.includes('独立复核意见'), 'review reason visible on persisted record'); check(!h.root.querySelector('[data-formal-correct="1"]'), 'active old record not latest correction target');
    h.root.querySelector('[data-formal-tab="pending"]').onclick(); h.root.querySelector('[data-formal-review="3"]').onclick(); check(h.root.textContent.includes('当前生效版本均值'), 'side-by-side active vs pending'); check(h.root.textContent.includes('修正遗漏的评分'), 'correction reason retained'); await h.click('reject'); equal(h.posts().length, 0, 'blank rejection blocked');
    await h.change('review-reason', '请核对空白题', 'oninput'); await h.click('reject'); const post = h.posts()[0]; equal(post.body, { project_id: 12, import_id: 3, expected_version: 3, decision: 'REJECT', reason: '请核对空白题', request_id: 'synthetic-request-1' }, 'server version and selected import plus independent review reason'); h.controller.destroy();
  });
  await scenario('Stored rejected review reason is visible and current approved result remains', async () => {
    const h = harness({ data: snapshot([approved(), record(3, { series_id: 1, replaces_id: 1, state: 'REJECTED', reason: '导入更正说明', review_reason: '<复核退回说明>', reviewer_code: 'PRIVATE-P2', reviewed_at: at, review_event_id: 4 })], 4) }); await h.controller.ready;
    check(h.root.textContent.includes('当前生效'), 'current result remains'); h.root.querySelector('[data-formal-tab="history"]').onclick(); h.root.querySelector('[data-formal-detail="3"]').onclick(); check(['更正原因：', '导入更正说明', '退回原因：', '<复核退回说明>'].every(s => h.root.textContent.includes(s)), 'separate persisted fields'); check(!h.root.querySelector('复核退回说明'), 'reason plain text'); check(h.root.textContent.includes('所选版本均值')&&!h.root.textContent.includes('待复核版本均值'),'rejected historical record is not mislabeled pending'); h.controller.destroy();
  });
  await scenario('Explicit correction requires reason and exposes policy recalculation notice', async () => {
    const h = harness({ data: snapshot([approved()], 2), api: (url, opts, h) => !opts.body ? clone(h.data) : { ...prepared(opts.body, 2), duplicate: { status: 'EXPLICIT_POLICY_RECALCULATION', import_ids: [1] } } }); await h.controller.ready;
    h.root.querySelector('[data-formal-correct="1"]').onclick(); equal(h.node('replaces').value, '1', 'exact selected tip'); await h.selectFile(); check(h.node('prepare').disabled, 'correction reason required'); await h.click('prepare'); equal(h.posts().length, 0, 'handler also rejects blank reason');
    h.change('reason', '按明确新口径重新统计', 'oninput'); await h.click('prepare'); equal(h.posts()[0].body.replacesId, 1, 'explicit replacement target'); check(h.root.textContent.includes('原生效结果不会自动重算'), 'recalculation explicit'); h.controller.destroy();
  });
  await scenario('Unknown confirmation first queries then replays exact request, never makes a new ID', async () => {
    let writes = 0; const h = harness({ api: (url, opts, h) => { if (!opts.body) return clone(h.data); if (url.endsWith('/prepare')) return prepared(opts.body); if (++writes === 1) { h.data = snapshot([record()], 1); throw err(503); } return receipt(opts.body, 'confirm', 1, true); } });
    await readyPreview(h); await h.click('confirm'); check(h.root.textContent.includes('操作结果尚未核实'), 'unknown acknowledged'); check(h.node('retry').disabled && !h.node('prepare'), 'no write before query'); await h.click('retry'); equal(writes, 1, 'forced click cannot bypass query'); check(h.controller.beforeClose() === false, 'close guard preserves unknown intent');
    await h.click('refresh'); check(!h.node('retry').disabled, 'retry only after successful read'); await h.click('retry'); const posts = h.posts().filter(p => p.url.endsWith('/confirm')); equal(posts.length, 2, 'one explicit replay'); equal(posts[0].body, posts[1].body, 'same request all fields'); equal(h.requestN, 1, 'no new request id'); check(!h.node('retry') && !h.node('confirm'), 'receipt resolved and token cleared'); equal(h.saved, 1, 'only known success notified'); h.controller.destroy();
  });
  await scenario('Failed unknown-state query prevents replay; stale conflict discards credential', async () => {
    let broken = false; const h = harness({ api: (url, opts) => { if (!opts.body) { if (broken) throw err(503); return snapshot(); } if (url.endsWith('/prepare')) return prepared(opts.body); broken = true; throw err(503); } }); await readyPreview(h); await h.click('confirm'); await h.click('refresh'); check(h.node('retry').disabled, 'failed read not inspected'); await h.click('retry'); equal(h.posts().length, 2, 'only original prepare+confirm'); h.controller.destroy();
    const conflict = harness({ api: (url, opts) => !opts.body ? snapshot() : url.endsWith('/prepare') ? prepared(opts.body) : Promise.reject(err(409)) }); await readyPreview(conflict); await conflict.click('confirm'); check(!conflict.node('confirm') && !conflict.node('retry'), '409 discards preview and does not replay'); check(conflict.root.textContent.includes('草案仍可单独预览'), '409 useful policy/version message'); equal(conflict.calls.at(-1).method, 'GET', '409 rechecks read'); conflict.controller.destroy();
  });
  await scenario('Known success followed by refresh failure remains success, not retriable mutation', async () => {
    let committed = false; const h = harness({ api: (url, opts) => { if (!opts.body) { if (committed) throw err(503); return snapshot(); } if (url.endsWith('/prepare')) return prepared(opts.body); committed = true; return receipt(opts.body, 'confirm'); } }); await readyPreview(h); check(await h.click('confirm'), 'success returned'); check(h.root.textContent.includes('操作已记录') && h.root.textContent.includes('列表暂未刷新'), 'refresh error distinct'); check(!h.node('retry') && !h.node('confirm'), 'successful request never reusable in UI'); equal(h.saved, 1, 'save hook still called'); h.controller.destroy();
  });
  await scenario('Permission/session failures clear data and do not expose raw error text', async () => {
    for (const status of [401, 403]) { let fail = false; const h = harness({ data: snapshot([approved()], 2), api: (_, __, h) => { if (fail) throw err(status); return clone(h.data); } }); await h.controller.ready; fail = true; await h.controller.refresh(); check(!h.root.textContent.includes('8.50') && !h.node('file'), 'old facts and controls cleared'); check(!h.root.textContent.includes('/tmp/secret'), 'no private error'); if (status === 401) equal(h.unauthorized, 1, 'host notified'); h.controller.destroy(); }
  });
  await scenario('File limits, token expiry, and edited preview invalidation', async () => {
    const h = harness(); await h.controller.ready;
    for (const patch of [{ name: 'wrong.csv' }, { size: 0 }, { size: 5 * 1024 * 1024 + 1 }, { name: 'bad\n.xlsx' }, { size: 3 }]) { await h.selectFile(patch); check(h.node('prepare').disabled, 'invalid file refused'); }
    equal(h.posts().length, 0, 'no invalid uploads'); await h.selectFile(); await h.click('prepare'); h.ack(); h.now += 300001; await h.click('confirm'); check(!h.node('confirm') && h.root.textContent.includes('凭证已过期'), 'expired token never posted'); equal(h.posts().length, 1, 'prepare only'); h.controller.destroy();
    const edit = harness({ data: snapshot([approved()], 2) }); await edit.controller.ready; edit.root.querySelector('[data-formal-correct="1"]').onclick(); edit.change('reason', '第一版原因', 'oninput'); await edit.selectFile(); await edit.click('prepare'); check(!!edit.node('confirm'), 'preview ready'); edit.change('reason', '原因已更正', 'oninput'); check(!edit.node('confirm'), 'reason changes invalidate token immediately'); edit.controller.destroy();
  });
  await scenario('Malformed response and cross-project or changed-policy preview fail closed', async () => {
    const badRead = [d => d.project_id = 99, d => d.synthetic = true, d => d.capabilities.prepare = 'true', d => d.records[0].summary.questions[9].averageText = 8.5,
      d => d.records[0].review_reason = null, d => d.records[0].summary.questions[0].validCount = 2, d => d.policy = null, d => d.records[0].current = true];
    for (const mutate of badRead) { const d = snapshot([record()], 1); mutate(d); const h = harness({ data: d }); equal(await h.controller.ready, false, 'bad snapshot rejected'); check(!h.node('file') && !h.root.textContent.includes('8.50'), 'malformed response has no usable facts'); h.controller.destroy(); }
    const badPrepare = [p => p.project_id = 999, p => p.expected_version = 1, p => p.reason = 'foreign reason', p => p.policy.zeroValid = false, p => p.preview_token = 'bad', p => p.canConfirm = false, p => p.summary.questions[0].averageText = null];
    for (const mutate of badPrepare) { const h = harness({ api: (url, opts) => { if (!opts.body) return snapshot(); const p = prepared(opts.body); mutate(p); return p; } }); await h.controller.ready; await h.selectFile(); await h.click('prepare'); check(!h.node('confirm'), 'bad preview cannot confirm'); equal(h.posts().length, 1, 'no implicit follow-up write'); h.controller.destroy(); }
  });
  await scenario('Privacy whitelist, escaped text and null are preserved', async () => {
    const r = approved(); r.review_reason = '<img src=x onerror=alert(1)>'; r.summary.questions[0] = { ...r.summary.questions[0], validCount: 0, blankCount: 2, sumText: '0', averageText: null }; r.raw_answers = 'PRIVATE-ANSWERS';
    const h = harness({ data: snapshot([r], 2) }); await h.controller.ready; h.root.querySelector('[data-formal-detail="1"]').onclick(); check(!h.root.querySelector('img,script'), 'text escaped'); check(h.root.textContent.includes('—（暂无有效评分）'), 'null not zero');
    for (const privateValue of ['PRIVATE-HASH', 'PRIVATE-P1', 'PRIVATE-P2', 'PRIVATE-EVIDENCE', 'PRIVATE-ROLE', 'PRIVATE-ANSWERS', 'a'.repeat(64)]) check(!h.root.textContent.includes(privateValue), 'not rendered ' + privateValue); h.controller.destroy();
  });
  await scenario('Prepare duplicates/errors never generate confirmation', async () => {
    for (const state of ['DUPLICATE', 'ERROR']) { const h = harness({ api: (_, opts) => !opts.body ? snapshot() : { state, canConfirm: false, duplicate: { status: 'EXACT_FILE' }, preview: { raw: 'PRIVATE-ERROR' } } }); await h.controller.ready; await h.selectFile(); await h.click('prepare'); check(!h.node('confirm'), 'nonready cannot commit'); check(!h.root.textContent.includes('PRIVATE-ERROR'), 'raw parse response not rendered'); h.controller.destroy(); }
  });
  await scenario('Late reads/previews, identity mutation, route disposal, modal ownership and duplicate clicks', async () => {
    const first = defer(); let reads = 0; const h = harness({ api: (_, opts) => !opts.body && ++reads === 1 ? first.promise : snapshot() }); const newer = h.controller.refresh(); await newer; check(h.calls[0].signal.aborted, 'stale read aborted'); first.resolve(snapshot([approved()], 2)); await h.controller.ready; check(!h.root.textContent.includes('8.50'), 'late prior read ignored');
    h.user = { uid: 1, role: 'manager' }; await h.controller.refresh(); equal(h.root.textContent, '', 'same identity fields in new session object clears component');
    const gate = defer(), x = harness({ api: (_, opts) => opts.body ? gate.promise : snapshot() }); await x.controller.ready; await x.selectFile(); const pending = x.click('prepare'); await x.click('prepare'); equal(x.posts().length, 1, 'double click sends once'); const replacement = harness({ root: x.root }); await replacement.controller.ready; gate.resolve(prepared({ projectId: 12, replacesId: 0, reason: '' })); await pending; check(!replacement.node('confirm'), 'old modal late response cannot repaint replacement'); replacement.controller.destroy();
    const signal = new AbortController(), canceled = harness({ signal: signal.signal }); await canceled.controller.ready; await canceled.selectFile(); signal.abort(); equal(canceled.root.textContent, '', 'abort clears');
    const route = defer(), y = harness({ api: () => route.promise }); y.current = false; route.resolve(snapshot()); await y.controller.ready; equal(y.root.textContent, '', 'route changed before result');
    const z = harness(); await z.controller.ready; await z.selectFile(); z.user.role = 'viewer'; check(z.controller.beforeClose(), 'expired identity never blocks logout'); equal(z.root.textContent, '', 'mutated identity destroys data');
  });
  await scenario('History pagination and capabilities control actions at handler time', async () => {
    const records = Array.from({ length: 41 }, (_, i) => record(i + 1)); const h = harness({ data: snapshot(records, 41) }); await h.controller.ready; h.root.querySelector('[data-formal-tab="history"]').onclick(); equal(h.root.querySelectorAll('[data-formal-detail]').length, 20, 'page bounded'); await h.click('next'); check(h.root.textContent.includes('第 21 批'), 'next page'); await h.click('next'); check(h.node('next').disabled, 'last page'); h.root.querySelector('[data-formal-tab="current"]').onclick(); check(h.node('prev').disabled, 'tab resets page'); h.controller.destroy();
    const d = snapshot([record()], 1); d.capabilities = { prepare: false, review: false }; const ro = harness({ data: d }); await ro.controller.ready; ro.root.querySelector('[data-formal-tab="pending"]').onclick(); check(!ro.node('file') && !ro.root.querySelector('[data-formal-review]') && !ro.root.querySelector('[data-formal-correct]'), 'read-only no write actions'); ro.controller.destroy();
  });
  console.log(JSON.stringify({ scenarios, checks, failed: 0, component: filename }));
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
