import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const moduleRoot = new URL('../web/modules/catalog/', import.meta.url);
const asModule = source => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`;
const modelSource = await readFile(new URL('model.js', moduleRoot), 'utf8');
const modelURL = asModule(modelSource);
const { preview, qualify } = await import(modelURL);
const demoSource = await readFile(new URL('demo.js', moduleRoot), 'utf8');
const demoURL = asModule(demoSource);
const { createDemoCatalog, createDemoRequest } = await import(demoURL);
let passed = 0;
function test(name, run) { run(); passed++; console.log(`PASS ${name}`); }
function fixture(edit) { const p = createDemoCatalog(); edit?.(p); return p; }
const q = edit => { const r = createDemoRequest(); edit?.(r); return r; };
const issue = (r, code) => r.issues.some(i => i.code === code);
const reason = (r, teacher, code) => r.gaps.find(t => t.teacher_code === teacher)?.reasons.some(i => i.code === code);
function rejected(p, code) { const r = preview(p); assert.equal(r.ready, false); assert.equal(r.data, null); assert.ok(issue(r, code), code); }

test('valid synthetic batch retains all rows and metadata warnings', () => {
  const r = preview(fixture()); assert.equal(r.ready, true); assert.deepEqual(r.counts, { courses: 2, teachers: 3, certifications: 3 });
  assert.ok(issue(r, 'UNKNOWN_CERTIFICATION')); assert.ok(issue(r, 'UNKNOWN_METADATA'));
});
test('only exact course certification qualifies, unknown never qualifies', () => {
  const r = qualify(fixture(), q()); assert.equal(r.ready, true);
  assert.deepEqual(r.eligible.map(t => t.teacher_code), ['DEMO-T01']);
  assert.deepEqual(r.gaps.map(t => t.teacher_code), ['DEMO-T02', 'DEMO-T03']);
  assert.ok(reason(r, 'DEMO-T02', 'CERTIFICATION_UNKNOWN')); assert.ok(reason(r, 'DEMO-T03', 'CERTIFICATION_MISSING'));
  assert.equal(r.ranking_status, 'not_configured');
});
for (const table of ['courses', 'teachers', 'certifications']) test(`${table} duplicate blocks entire batch`, () => rejected(fixture(p => p[table].push({ ...p[table][0] })), 'DUPLICATE'));
for (const field of ['teacher_code', 'course_code']) test(`dangling ${field} blocks batch`, () => rejected(fixture(p => p.certifications[0][field] = 'MISSING'), 'UNKNOWN_REFERENCE'));
test('certified source is required', () => rejected(fixture(p => p.certifications[0].source_ref = ''), 'SOURCE_REQUIRED'));
for (const date of ['2026-02-29', '2026-13-01', '2026-1-01', '0000-01-01']) test(`invalid date ${date}`, () => rejected(fixture(p => p.certifications[0].valid_from = date), 'INVALID_DATE'));
test('reversed bounds block batch', () => rejected(fixture(p => p.certifications[0].valid_from = '2027-01-01'), 'DATE_ORDER'));
test('date boundaries are inclusive', () => {
  for (const as_of of ['2026-01-01', '2026-12-31']) assert.equal(qualify(fixture(), q(r => r.as_of = as_of)).eligible[0].teacher_code, 'DEMO-T01');
});
test('not yet valid and expired certification excluded', () => {
  assert.ok(reason(qualify(fixture(), q(r => r.as_of = '2025-12-31')), 'DEMO-T01', 'NOT_YET_VALID'));
  assert.ok(reason(qualify(fixture(), q(r => r.as_of = '2027-01-01')), 'DEMO-T01', 'EXPIRED'));
});
test('empty bounds warn without inventing expiry', () => {
  const p = fixture(p => { p.certifications[0].valid_from = ''; p.certifications[0].valid_to = ''; });
  assert.ok(issue(preview(p), 'VALIDITY_UNSPECIFIED')); assert.equal(qualify(p, q()).eligible[0].teacher_code, 'DEMO-T01');
});
for (const [status, code] of [['not_certified', 'NOT_CERTIFIED'], ['revoked', 'CERTIFICATION_REVOKED']]) test(`${status} excluded`, () => {
  assert.ok(reason(qualify(fixture(p => p.certifications[0].status = status), q()), 'DEMO-T01', code));
});
for (const [table, field] of [['catalog', 'name'], ['courses', 'domain'], ['teachers', 'phone'], ['certifications', 'teacher_name']]) test(`unknown ${table}.${field} blocked`, () => {
  rejected(fixture(p => { (table === 'catalog' ? p : p[table][0])[field] = 'unsupported'; }), 'UNKNOWN_FIELD');
});
test('numeric code rejected, leading zeros retained', () => {
  rejected(fixture(p => p.teachers[0].teacher_code = 1), 'INVALID_TYPE');
  const p = fixture(p => { p.teachers[0].teacher_code = '001'; p.certifications[0].teacher_code = '001'; });
  assert.equal(qualify(p, q()).eligible[0].teacher_code, '001');
});
test('unsupported and missing schema rejected', () => {
  rejected(fixture(p => p.schema_version = 'v2'), 'SCHEMA_VERSION'); rejected(fixture(p => delete p.schema_version), 'SCHEMA_VERSION');
});
test('optional filters use exact equality with no ordering or aliases', () => {
  assert.equal(qualify(fixture(), q(r => { r.accepted_levels = ['L1']; r.allowed_cities = ['演示甲城']; })).eligible.length, 1);
  assert.ok(reason(qualify(fixture(), q(r => r.accepted_levels = ['L2'])), 'DEMO-T01', 'LEVEL_NOT_ALLOWED'));
  assert.ok(reason(qualify(fixture(), q(r => r.allowed_cities = ['演示甲'])), 'DEMO-T01', 'CITY_NOT_ALLOWED'));
});
test('unknown metadata passes only absent corresponding filters', () => {
  const p = fixture(p => { p.teachers[0].teacher_level = ''; p.teachers[0].city = ''; });
  assert.equal(qualify(p, q()).eligible.length, 1);
  const r = qualify(p, q(r => { r.accepted_levels = ['L1']; r.allowed_cities = ['演示甲城']; }));
  assert.ok(reason(r, 'DEMO-T01', 'LEVEL_UNKNOWN')); assert.ok(reason(r, 'DEMO-T01', 'CITY_UNKNOWN'));
});
test('invalid request shape fields, filters and date block results', () => {
  for (const request of [null, [], {}, q(r => r.name = 'x'), q(r => r.as_of = ''), q(r => r.accepted_levels = ['L1', 'L1']), q(r => r.allowed_cities = [2])]) {
    const r = qualify(fixture(), request); assert.equal(r.ready, false); assert.deepEqual(r.eligible, []); assert.deepEqual(r.gaps, []);
  }
});
test('unknown or inactive course blocked', () => {
  assert.ok(issue(qualify(fixture(), q(r => r.course_code = 'MISSING')), 'UNKNOWN_REFERENCE'));
  assert.ok(issue(qualify(fixture(p => p.courses[0].active = false), q()), 'COURSE_INACTIVE'));
});
test('malformed payload safely reports validation errors', () => {
  for (const p of [null, [], 5, 'x', {}, fixture(p => p.teachers = {}), fixture(p => p.certifications[0] = null)]) {
    const r = preview(p); assert.equal(r.ready, false); assert.equal(r.data, null);
  }
});
test('sparse arrays cannot bypass required rows or filter validation', () => {
  rejected(fixture(p => p.teachers = Array(2)), 'INVALID_TYPE');
  assert.equal(qualify(fixture(), q(r => r.accepted_levels = Array(2))).ready, false);
});
test('limit enforcement blocks oversized batches and filters', () => {
  rejected(fixture(p => p.teachers = Array.from({ length: 5001 }, () => p.teachers[0])), 'LIMIT');
  assert.ok(issue(qualify(fixture(), q(r => r.accepted_levels = Array(5001).fill('L1'))), 'LIMIT'));
});
test('control characters and untrimmed fields rejected', () => {
  rejected(fixture(p => p.teachers[0].city = ' City'), 'INVALID_TYPE');
  rejected(fixture(p => p.certifications[0].source_ref = 'SRC\n01'), 'INVALID_TYPE');
});
for (const value of ['\u00a0', '\u200b', '\ufeff', '\u0085']) test(`invisible source reference rejected U+${value.charCodeAt(0).toString(16)}`, () => {
  rejected(fixture(p => p.certifications[0].source_ref = value), 'INVALID_TYPE');
  rejected(fixture(p => p.catalog_version = value), 'INVALID_TYPE');
});
test('past preview and qualification are isolated from caller edits', () => {
  const p = fixture(), request = q(), a = preview(p), b = qualify(p, request);
  p.teachers[0].teacher_code = 'CHANGED'; p.certifications[0].source_ref = 'CHANGED'; request.course_code = 'CHANGED';
  assert.equal(a.data.teachers[0].teacher_code, 'DEMO-T01');
  assert.equal(a.data.certifications[0].source_ref, 'DEMO-SRC-01');
  assert.equal(b.eligible[0].teacher_code, 'DEMO-T01'); assert.equal(b.eligible[0].evidence.source_ref, 'DEMO-SRC-01');
});
console.log(`M04 model checks passed: ${passed}`);

// Small DOM test double: exercises actual mount handlers and lifecycle, not browser layout.
class Element {
  constructor(tag, doc) { this.tagName = tag.toUpperCase(); this.ownerDocument = doc; this.nodeType = 1; this.childNodes = []; this.parentNode = null; this.dataset = {}; this.attributes = {}; this.events = new Map(); this._text = ''; }
  set textContent(value) { this.ownerDocument.writes++; this._text = String(value); this.childNodes.forEach(n => n.parentNode = null); this.childNodes = []; }
  get textContent() { return this._text + this.childNodes.map(n => n.textContent).join(''); }
  set value(value) { this._value = value; }
  get value() { return this._value ?? (this.tagName === 'SELECT' ? this.childNodes[0]?.value ?? '' : ''); }
  setAttribute(key, value) { this.ownerDocument.writes++; this.attributes[key] = value; }
  append(...nodes) { this.ownerDocument.writes++; for (const child of nodes) { child.parentNode = this; this.childNodes.push(child); } }
  replaceChildren(...nodes) { this.textContent = ''; this.append(...nodes); }
  remove() { if (this.parentNode) { this.ownerDocument.writes++; this.parentNode.childNodes = this.parentNode.childNodes.filter(n => n !== this); this.parentNode = null; } }
  addEventListener(name, fn) { if (!this.events.has(name)) this.events.set(name, new Set()); this.events.get(name).add(fn); }
  removeEventListener(name, fn) { this.events.get(name)?.delete(fn); }
  dispatch(name) { const event = { target: this, preventDefault() {} }, pending = []; for (let node = this; node; node = node.parentNode) for (const fn of [...node.events.get(name) ?? []]) pending.push(fn(event)); return Promise.all(pending); }
}
function dom() { const doc = { writes: 0, createElement(tag) { return new Element(tag, this); } }; return { doc, root: doc.createElement('main') }; }
function all(node, predicate) { return [...(predicate(node) ? [node] : []), ...node.childNodes.flatMap(child => all(child, predicate))]; }
function find(node, predicate) { return all(node, predicate)[0]; }
const byRole = (root, role) => find(root, el => el.dataset.role === role);
const indexSource = await readFile(new URL('index.js', moduleRoot), 'utf8');
const { mount } = await import(asModule(indexSource.replace("'./model.js'", JSON.stringify(modelURL)).replace("'./demo.js'", JSON.stringify(demoURL)).replace('import.meta.url', JSON.stringify(new URL('index.js', moduleRoot).href))));
async function testAsync(name, run) { await run(); passed++; console.log(`PASS ${name}`); }

await testAsync('demo mount makes zero requests and renders default evidence and gaps', async () => {
  const { root } = dom(); let requests = 0;
  const cleanup = await mount(root, { mode: 'demo', request() { requests++; throw new Error('unexpected request'); } });
  assert.equal(requests, 0); assert.match(root.textContent, /合成样例演示/); assert.match(root.textContent, /课程认证符合 · 1 人/); assert.match(root.textContent, /资格缺口 · 2 人/);
  assert.match(root.textContent, /CERTIFICATION_UNKNOWN/); assert.match(root.textContent, /CERTIFICATION_MISSING/); assert.match(root.textContent, /真实性尚未验证/);
  assert.equal(all(root, el => el.tagName === 'BUTTON' && /^(保存|提交)/.test(el.textContent)).length, 0);
  cleanup();
});
await testAsync('live mount stays disconnected without synthetic data or requests', async () => {
  const { root } = dom(); let requests = 0;
  const cleanup = await mount(root, { mode: 'live', request() { requests++; } });
  assert.equal(requests, 0); assert.match(root.textContent, /接口未接入/); assert.doesNotMatch(root.textContent, /DEMO-T01/);
  assert.equal(all(root, el => ['TEXTAREA', 'FORM'].includes(el.tagName)).length, 0); cleanup();
});
await testAsync('pre-aborted mount leaves root and sibling untouched', async () => {
  const { root, doc } = dom(), controller = new AbortController(); const sibling = doc.createElement('p'); root.append(sibling); controller.abort();
  const before = doc.writes; const cleanup = await mount(root, { mode: 'demo', signal: controller.signal });
  assert.equal(doc.writes, before); cleanup(); assert.deepEqual(root.childNodes, [sibling]);
});
await testAsync('editor changes invalidate results; invalid batches never show prior qualifications', async () => {
  const { root } = dom(); const cleanup = await mount(root, { mode: 'demo' });
  const editor = byRole(root, 'editor'); editor.value = JSON.stringify(fixture(p => p.teachers.push({ ...p.teachers[0] })));
  await editor.dispatch('input'); assert.equal(byRole(root, 'results').textContent, '');
  await find(root, el => el.dataset.action === 'preview').dispatch('click');
  assert.match(root.textContent, /整批未通过/); assert.match(root.textContent, /DUPLICATE/); assert.equal(byRole(root, 'results').textContent, ''); cleanup();
});
await testAsync('abort blocks late file result and stale handlers, cleanup preserves sibling', async () => {
  const { root, doc } = dom(), controller = new AbortController(); const sibling = doc.createElement('p'); root.append(sibling);
  const cleanup = await mount(root, { mode: 'demo', signal: controller.signal }); const upload = byRole(root, 'upload'); const editor = byRole(root, 'editor');
  let finish; upload.files = [{ size: 10, text: () => new Promise(resolve => finish = resolve) }];
  const pending = upload.dispatch('change'); controller.abort(); const before = doc.writes;
  finish(JSON.stringify(fixture())); await pending; await editor.dispatch('input'); cleanup();
  assert.equal(doc.writes, before); assert.deepEqual(root.childNodes, [sibling]);
});
await testAsync('new editor input wins over a pending file upload', async () => {
  const { root } = dom(); const cleanup = await mount(root, { mode: 'demo' });
  const upload = byRole(root, 'upload'), editor = byRole(root, 'editor'); let finish;
  upload.files = [{ size: 10, text: () => new Promise(resolve => finish = resolve) }]; const pending = upload.dispatch('change');
  editor.value = '{"editing": true}'; await editor.dispatch('input'); finish(JSON.stringify(fixture())); await pending;
  assert.equal(editor.value, '{"editing": true}'); assert.equal(byRole(root, 'results').textContent, ''); cleanup();
});
await testAsync('untrusted markup is rendered only as text', async () => {
  const { root } = dom(); const cleanup = await mount(root, { mode: 'demo' }); const editor = byRole(root, 'editor');
  editor.value = JSON.stringify(fixture(p => p.courses[0].course_name = '<img src=x onerror=alert(1)>'));
  await find(root, el => el.dataset.action === 'preview').dispatch('click');
  assert.equal(all(root, el => ['IMG', 'SCRIPT'].includes(el.tagName)).length, 0);
  assert.match(root.textContent, /<img src=x onerror=alert\(1\)>/); cleanup();
});
await testAsync('matrix pagination bounds displayed cells without truncating qualification', async () => {
  const { root } = dom(); const cleanup = await mount(root, { mode: 'demo' }); const editor = byRole(root, 'editor');
  const p = fixture(p => { p.teachers = Array.from({ length: 41 }, (_, i) => ({ teacher_code: `T${i}`, teacher_level: 'L1', city: '合成城市' })); p.certifications = []; p.courses = Array.from({ length: 7 }, (_, i) => ({ course_code: `C${i}`, course_name: `合成课程${i}`, active: true })); });
  editor.value = JSON.stringify(p); await find(root, el => el.dataset.action === 'preview').dispatch('click');
  const matrix = byRole(root, 'matrix'); assert.equal(all(matrix, el => el.className === 'yx-catalog__matrix-cell').length, 100);
  assert.match(matrix.textContent, /教师 1–20 \/ 41；课程 1–5 \/ 7/); assert.match(byRole(root, 'results').textContent, /资格缺口 · 41 人/);
  await find(matrix, el => el.tagName === 'BUTTON' && el.textContent === '下一组教师').dispatch('click'); assert.match(matrix.textContent, /教师 21–40 \/ 41/); cleanup();
});
await testAsync('large invalid batches paginate issues without partial data or DOM explosion', async () => {
  const { root } = dom(); const cleanup = await mount(root, { mode: 'demo' }); const editor = byRole(root, 'editor');
  editor.value = JSON.stringify(fixture(p => { p.teachers = Array.from({ length: 1000 }, () => ({})); p.certifications = []; }));
  await find(root, el => el.dataset.action === 'preview').dispatch('click');
  assert.match(root.textContent, /3000 个错误/); const previewArea = byRole(root, 'preview');
  assert.equal(all(previewArea, el => el.tagName === 'TR').length, 21); assert.match(previewArea.textContent, /校验问题 1–20 \/ 3000/);
  await find(previewArea, el => el.tagName === 'BUTTON' && el.textContent === '下一页问题').dispatch('click');
  assert.match(previewArea.textContent, /校验问题 21–40 \/ 3000/); assert.equal(byRole(root, 'results').textContent, ''); cleanup();
});
await testAsync('repeated preview uses only four delegated listeners', async () => {
  const { root } = dom(); const cleanup = await mount(root, { mode: 'demo' });
  for (let i = 0; i < 15; i++) await find(root, el => el.dataset.action === 'preview').dispatch('click');
  assert.equal(all(root, () => true).reduce((total, el) => total + [...el.events.values()].reduce((n, set) => n + set.size, 0), 0), 4);
  assert.match(root.textContent, /课程认证符合 · 1 人/); cleanup();
});
assert.doesNotMatch(indexSource, /context\.request\s*\(|\bfetch\s*\(|localStorage|sessionStorage|\.innerHTML\s*=/);
console.log(`M04 model and UI checks passed: ${passed}`);
