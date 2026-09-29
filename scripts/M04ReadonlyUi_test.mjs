import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(new URL('../web/modules/course-catalog/index.js', import.meta.url), 'utf8');
const { mountCourseCatalog, openCourseCatalog } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
const decode = value => value.replace(/&(amp|lt|gt|quot|#39);/g, (_, key) => ({ amp: '&', lt: '<', gt: '>', quot: '"', '#39': "'" })[key]);
const camel = key => key.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());

// Deliberately small DOM: tests real exports, events and async ownership, not layout.
class Element {
  constructor(tag, doc) { this.tagName = tag.toUpperCase(); this.ownerDocument = doc; this.childNodes = []; this.parentNode = null; this.attributes = {}; this.dataset = {}; this.events = new Map(); this._text = ''; this.disabled = false; }
  set textContent(value) { this.ownerDocument.writes++; this._text = String(value); this.childNodes.forEach(node => node.parentNode = null); this.childNodes = []; }
  get textContent() { return this._text + this.childNodes.map(node => node.textContent).join(''); }
  set innerHTML(html) {
    this.textContent = ''; const stack = [this];
    for (const token of String(html).match(/<[^>]*>|[^<]+/g) || []) {
      if (token.startsWith('</')) { stack.pop(); continue; }
      if (token.startsWith('<')) {
        const [, tag, rest] = token.match(/^<([\w-]+)(.*?)>$/s) || [];
        if (!tag) continue;
        const child = this.ownerDocument.createElement(tag);
        for (const match of rest.matchAll(/([\w-]+)(?:="([^"]*)")?/g)) child.setAttribute(match[1], decode(match[2] ?? ''));
        stack.at(-1).append(child);
        if (!['INPUT', 'BR', 'HR', 'IMG'].includes(child.tagName)) stack.push(child);
      } else { const child = this.ownerDocument.createElement('#text'); child.textContent = decode(token); stack.at(-1).append(child); }
    }
  }
  set value(value) { this._value = String(value); }
  get value() {
    if (this._value !== undefined) return this._value;
    if (this.tagName === 'SELECT') { const options = this.childNodes.filter(node => node.tagName === 'OPTION'); return (options.find(node => 'selected' in node.attributes) || options[0])?.value || ''; }
    if (this.tagName === 'TEXTAREA') return this.textContent;
    return this.attributes.value || '';
  }
  setAttribute(key, value) { this.ownerDocument.writes++; this.attributes[key] = String(value); if (key.startsWith('data-')) this.dataset[camel(key.slice(5))] = String(value); if (key === 'disabled') this.disabled = true; }
  append(...nodes) { this.ownerDocument.writes++; for (const node of nodes) { node.parentNode = this; this.childNodes.push(node); } }
  replaceChildren(...nodes) { this.textContent = ''; this.append(...nodes); }
  remove() { if (this.parentNode) { this.ownerDocument.writes++; this.parentNode.childNodes = this.parentNode.childNodes.filter(node => node !== this); this.parentNode = null; } }
  contains(other) { for (let node = other; node; node = node.parentNode) if (node === this) return true; return false; }
  querySelector(selector) {
    const match = selector.match(/^\[([^=\]]+)(?:="([^"]*)")?\]$/);
    if (!match) throw new Error(`Unsupported test selector ${selector}`);
    return find(this, node => {
      const key = match[1], actual = key.startsWith('data-') ? node.dataset[camel(key.slice(5))] : node.attributes[key];
      return actual !== undefined && (match[2] === undefined || actual === match[2]);
    });
  }
  addEventListener(type, fn) { if (!this.events.has(type)) this.events.set(type, new Set()); this.events.get(type).add(fn); }
  removeEventListener(type, fn) { this.events.get(type)?.delete(fn); }
  dispatch(type) { const event = { target: this, preventDefault() {} }, pending = []; for (let node = this; node; node = node.parentNode) for (const fn of [...node.events.get(type) || []]) pending.push(fn(event)); return Promise.all(pending); }
}
const all = (root, predicate) => [...(predicate(root) ? [root] : []), ...root.childNodes.flatMap(node => all(node, predicate))];
const find = (root, predicate) => all(root, predicate)[0];
function dom() {
  const doc = { writes: 0, createElement(tag) { return new Element(tag, this); }, getElementById(id) { return find(this.body, node => node.attributes.id === id); } };
  doc.body = doc.createElement('body'); const root = doc.createElement('main'); doc.body.append(root); return { root, doc };
}
const codeError = code => Object.assign(new Error('权限或版本变化'), { status: code, code });
function snapshot(scope = 1, count = 3) {
  return { status: 'READY', scope_id: scope, organization_code: `00${scope}`, version: 2, bindings: Array.from({ length: count }, (_, i) => ({ teacher_code: `T${i}`, teacher_id: i + 1 })), catalog: {
    catalog_version: `MATERIAL-${scope}`, courses: Array.from({ length: count }, (_, i) => ({ course_code: `C${i}`, course_name: `课程${i}`, active: i !== 1 })),
    teachers: Array.from({ length: count }, (_, i) => ({ teacher_code: `T${i}`, teacher_level: 'L1', city: '甲城' })),
    certifications: Array.from({ length: count }, (_, i) => ({ teacher_code: `T${i}`, course_code: 'C0', status: 'certified', source_ref: 'SOURCE-1', valid_from: '2026-01-01', valid_to: '' })),
  } };
}
function qualification(scope = 1, count = 3) {
  const snap = snapshot(scope, count);
  return { status: 'READY', ready: true, scope_id: scope, version: 2, catalog_version: `MATERIAL-${scope}`, course_code: 'C0', as_of: '2026-09-22', ranking_status: 'not_configured',
    qualification_context: { schema_version: 'm04_qualification_context_v1', scope_id: scope, organization_code: `00${scope}`, version: 2, catalog_version: `MATERIAL-${scope}`, course_code: 'C0', as_of: '2026-09-22', accepted_levels: [], allowed_cities: [] },
    eligible: snap.catalog.teachers.slice(0, -1).map((teacher, i) => ({ ...teacher, teacher_id: i + 1, eligible: true, reasons: [], evidence: snap.catalog.certifications[i] })),
    gaps: [{ teacher_code: `T${count - 1}`, teacher_id: count, teacher_level: '', city: '', eligible: false, reasons: [{ code: 'CERTIFICATION_MISSING', message: '缺少该课认证' }], evidence: null }],
  };
}
function host(env, overrides = {}) {
  const calls = [], renderedTables = [], renderedForms = [];
  const ctx = {
    async api(path, options) { calls.push({ path, options });
      if (overrides.api) return overrides.api(path, options, calls);
      if (path === '/organization/me') return { status: 'BOUND' };
      if (path === '/course-catalog/scopes') return { status: 'READY', scopes: [{ scope_id: 1, organization_code: '001', version: 2 }, { scope_id: 2, organization_code: '002', version: 2 }] };
      if (path.endsWith('/qualify')) { const result = qualification(Number(path.split('/').at(-2))); Object.assign(result.qualification_context, options.body.request); return result; }
      return snapshot(Number(path.split('/').at(-1)));
    },
    renderTable(columns, data, actions) {
      renderedTables.push({ columns, data, actions });
      return `<table><tbody>${data.map(row => `<tr>${columns.map(col => `<td>${col.render ? col.render(row) : esc(row[col.k])}</td>`).join('')}</tr>`).join('')}</tbody></table>`;
    },
    renderForm(fields, data) {
      renderedForms.push({ fields, data });
      return `<div>${fields.map(field => `<label>${esc(field.label)}${field.type === 'select'
        ? `<select data-k="${field.k}">${field.options.map(option => `<option value="${esc(option.v)}">${esc(option.l)}</option>`).join('')}</select>`
        : field.type === 'textarea' ? `<textarea data-k="${field.k}">${esc(data[field.k])}</textarea>` : `<input data-k="${field.k}" value="${esc(data[field.k])}" type="${field.type || 'text'}">`}<small>${esc(field.hint)}</small></label>`).join('')}</div>`;
    },
    collectForm(root, fields) { const result = {}; for (const field of fields) { result[field.k] = root.querySelector(`[data-k="${field.k}"]`).value.trim(); if (field.required && !result[field.k]) return null; } return result; },
    openModal(title, html, options) {
      ctx.closeModal(); const mask = env.doc.createElement('div'); mask.setAttribute('id', 'modal-mask'); mask.innerHTML = html; mask.onClose = options.onClose; env.doc.body.append(mask); ctx.lastModalOptions = options; return mask;
    },
    closeModal() { const mask = env.doc.getElementById('modal-mask'); if (mask) { mask.onClose?.(); mask.remove(); } },
    ...(overrides.signal ? { signal: overrides.signal } : {}), ...(overrides.isCurrent ? { isCurrent: overrides.isCurrent } : {}),
  };
  return { ctx, calls, renderedTables, renderedForms };
}
const action = (root, value) => find(root, node => node.dataset.catalogAction === value);
const field = (root, key) => root.querySelector(`[data-k="${key}"]`);
const queryForm = root => find(root, node => node.dataset.catalogForm === 'query');
async function select(root, value = '1') { const el = field(root, 'scope_id'); el.value = value; await el.dispatch('change'); }
async function query(root, edit = {}) {
  for (const [key, value] of Object.entries({ course_code: 'C0', as_of: '2026-09-22', accepted_levels: '', allowed_cities: '', ...edit })) field(root, key).value = value;
  return queryForm(root).dispatch('submit');
}
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
let passed = 0;
async function test(name, run) { await run(); console.log(`PASS ${name}`); passed++; }

for (const [status, message] of [['NOT_BOUND', /没有有效的人员绑定/], ['NOT_CONFIGURED', /组织与人员关系尚未配置/]]) await test(`${status} only reads self identity`, async () => {
  const env = dom(), h = host(env, { api: async () => ({ status }) }); const mount = mountCourseCatalog(env.root, h.ctx); await mount.ready;
  assert.equal(h.calls.length, 1); assert.equal(h.calls[0].path, '/organization/me'); assert.match(env.root.textContent, message); mount.destroy();
});
await test('BOUND with an empty list does not invent an ID or claim forbidden access', async () => {
  const env = dom(), h = host(env, { api: async path => path === '/organization/me' ? { status: 'BOUND' } : { status: 'NOT_CONFIGURED', scopes: [] } });
  const mount = mountCourseCatalog(env.root, h.ctx); await mount.ready;
  assert.equal(h.calls.length, 2); assert.match(env.root.textContent, /当前没有可查看的课程目录空间/); assert.doesNotMatch(env.root.textContent, /无权限/); mount.destroy();
});
await test('even one scope waits for explicit selection, paths use unpacked host API', async () => {
  const env = dom(), h = host(env, { api: async path => path === '/organization/me' ? { status: 'BOUND' } : path.endsWith('/scopes') ? { scopes: [{ scope_id: 8, organization_code: '008' }] } : snapshot(8) });
  const mount = mountCourseCatalog(env.root, h.ctx); await mount.ready; assert.equal(h.calls.length, 2); assert.equal(field(env.root, 'scope_id').value, '');
  await select(env.root, '999'); assert.equal(h.calls.length, 2); await select(env.root, '8'); assert.equal(h.calls[2].path, '/course-catalog/scopes/8');
  assert.match(env.root.textContent, /MATERIAL-8/); assert.ok(h.calls.every(call => call.options.quiet && call.options.signal instanceof AbortSignal)); mount.destroy();
});
for (const [error, message, exclude] of [[codeError(403), /无权限/, /网络连接失败/], [new Error('网络连接失败'), /网络连接失败/, /无权限/], [new Error('响应格式错误'), /响应格式错误/, /无权限/]]) await test(`read errors remain distinct: ${message}`, async () => {
  const env = dom(), h = host(env, { api: async path => { if (path === '/organization/me') return { status: 'BOUND' }; if (path.endsWith('/scopes')) return { scopes: [{ scope_id: 1, organization_code: '001' }] }; throw error; } });
  const mount = mountCourseCatalog(env.root, h.ctx); await mount.ready; await select(env.root); assert.match(env.root.textContent, message); assert.doesNotMatch(env.root.textContent, exclude); assert.equal(queryForm(env.root), undefined); mount.destroy();
});
await test('version zero stays unconfigured and exposes no query or write controls', async () => {
  const env = dom(), h = host(env, { api: async path => path === '/organization/me' ? { status: 'BOUND' } : path.endsWith('/scopes') ? { scopes: [{ scope_id: 1 }] } : { status: 'NOT_CONFIGURED', scope_id: 1, version: 0, catalog: null, bindings: [] } });
  const mount = mountCourseCatalog(env.root, h.ctx); await mount.ready; await select(env.root);
  assert.match(env.root.textContent, /尚未配置/); assert.equal(queryForm(env.root), undefined); assert.doesNotMatch(env.root.textContent, /导入|确认|上传|创建空间/); mount.destroy();
});
await test('course/date are explicit; only active exact course and valid date can query', async () => {
  const env = dom(), h = host(env), mount = mountCourseCatalog(env.root, h.ctx); await mount.ready; await select(env.root);
  assert.equal(field(env.root, 'as_of').value, ''); assert.equal(field(env.root, 'course_code').value, '');
  await query(env.root, { course_code: 'C1' }); await query(env.root, { course_code: 'C' }); await query(env.root, { as_of: '' }); await query(env.root, { as_of: '2026-02-29' });
  assert.equal(h.calls.filter(call => call.options.method === 'POST').length, 0);
  await action(env.root, 'choose:0').dispatch('click'); assert.equal(field(env.root, 'course_code').value, 'C0');
  await query(env.root, { accepted_levels: 'L1\nL3', allowed_cities: '甲城\n乙城' });
  const last = h.calls.at(-1); assert.equal(last.path, '/course-catalog/scopes/1/qualify');
  assert.deepEqual(last.options.body, { expected_version: 2, request: { course_code: 'C0', as_of: '2026-09-22', accepted_levels: ['L1', 'L3'], allowed_cities: ['甲城', '乙城'] } });
  assert.match(env.root.textContent, /本次课程资格符合 · 2 条/); assert.match(env.root.textContent, /缺少该课认证/); assert.doesNotMatch(env.root.textContent, /CERTIFICATION_MISSING/); assert.match(env.root.textContent, /来源引用不代表真实性认可/); mount.destroy();
});
await test('empty restrictions are explicit unrestricted arrays and edits remove prior results', async () => {
  const env = dom(), h = host(env), mount = mountCourseCatalog(env.root, h.ctx); await mount.ready; await select(env.root); await query(env.root);
  assert.deepEqual(h.calls.at(-1).options.body.request.accepted_levels, []); assert.deepEqual(h.calls.at(-1).options.body.request.allowed_cities, []);
  assert.match(env.root.textContent, /等级 不限；城市 不限/); field(env.root, 'accepted_levels').value = 'L9'; await field(env.root, 'accepted_levels').dispatch('input');
  assert.doesNotMatch(env.root.textContent, /本次课程资格符合/); assert.match(env.root.textContent, /之前结果已失效/); mount.destroy();
});
await test('all tables paginate without truncating submitted qualification or creating thousands of options', async () => {
  const env = dom(), h = host(env, { api: async path => path === '/organization/me' ? { status: 'BOUND' } : path.endsWith('/scopes') ? { scopes: [{ scope_id: 1 }] } : path.endsWith('/qualify') ? qualification(1, 1001) : snapshot(1, 1001) });
  const mount = mountCourseCatalog(env.root, h.ctx); await mount.ready; await select(env.root);
  assert.ok(h.renderedTables.every(table => table.data.length <= 20)); assert.match(env.root.textContent, /课程 1–20 \/ 1001/);
  await action(env.root, 'page:courses:1').dispatch('click'); assert.match(env.root.textContent, /课程 21–40 \/ 1001/);
  await action(env.root, 'choose:20').dispatch('click'); assert.equal(field(env.root, 'course_code').value, 'C20');
  await query(env.root); assert.match(env.root.textContent, /本次课程资格符合 · 1000 条/); assert.ok(h.renderedTables.every(table => table.data.length <= 20));
  await action(env.root, 'page:eligible:1').dispatch('click'); assert.match(env.root.textContent, /本次课程资格符合 21–40 \/ 1000/);
  assert.ok(all(env.root, node => node.tagName === 'TR').length <= 81); assert.ok(all(env.root, node => node.tagName === 'OPTION').length < 5); mount.destroy();
});
await test('malicious values and source references are text, never executable markup or links', async () => {
  const bad = '<img src=x onerror=alert(1)>'; const snap = snapshot(); snap.catalog.courses[0].course_name = bad; snap.catalog.certifications[0].source_ref = 'javascript:alert(1)';
  const result = qualification(); result.gaps[0].reasons[0].message = bad; result.eligible[0].evidence.source_ref = bad;
  const env = dom(), h = host(env, { api: async path => path === '/organization/me' ? { status: 'BOUND' } : path.endsWith('/scopes') ? { scopes: [{ scope_id: 1, organization_code: bad }] } : path.endsWith('/qualify') ? result : snap });
  const mount = mountCourseCatalog(env.root, h.ctx); await mount.ready; await select(env.root); await query(env.root);
  assert.match(env.root.textContent, /<img src=x onerror=alert\(1\)>/); assert.equal(all(env.root, node => ['IMG', 'SCRIPT', 'A'].includes(node.tagName)).length, 0); mount.destroy();
});
await test('stale identity and scope results cannot replace newer selection', async () => {
  const pending = deferred(), env = dom(), h = host(env, { api: async (path, options, calls) => {
    if (path === '/organization/me') return calls.length === 1 ? pending.promise : { status: 'BOUND' };
    if (path.endsWith('/scopes')) return { scopes: [{ scope_id: 1 }, { scope_id: 2 }] };
    return snapshot(Number(path.split('/').at(-1)));
  } });
  const mount = mountCourseCatalog(env.root, h.ctx); await mount.refresh(); pending.resolve({ status: 'NOT_BOUND' }); await mount.ready;
  assert.doesNotMatch(env.root.textContent, /没有有效的人员绑定/); assert.equal(h.calls[0].options.signal.aborted, true); mount.destroy();
  const wait = deferred(), env2 = dom(), h2 = host(env2, { api: async path => path === '/organization/me' ? { status: 'BOUND' } : path.endsWith('/scopes') ? { scopes: [{ scope_id: 1 }, { scope_id: 2 }] } : path.endsWith('/1') ? wait.promise : snapshot(2) });
  const m2 = mountCourseCatalog(env2.root, h2.ctx); await m2.ready; const slow = select(env2.root, '1'); await select(env2.root, '2'); wait.resolve(snapshot(1)); await slow;
  assert.match(env2.root.textContent, /MATERIAL-2/); assert.doesNotMatch(env2.root.textContent, /MATERIAL-1/); m2.destroy();
});
await test('editing or changing scope during qualification discards a late result even if API ignores abort', async () => {
  for (const switchScope of [false, true]) {
    const pending = deferred(), env = dom(), h = host(env, { api: async path => path === '/organization/me' ? { status: 'BOUND' } : path.endsWith('/scopes') ? { scopes: [{ scope_id: 1 }, { scope_id: 2 }] } : path.endsWith('/qualify') ? pending.promise : snapshot(Number(path.split('/').at(-1))) });
    const mount = mountCourseCatalog(env.root, h.ctx); await mount.ready; await select(env.root); const slow = query(env.root);
    if (switchScope) await select(env.root, '2'); else await field(env.root, 'as_of').dispatch('input');
    pending.resolve(qualification()); await slow; assert.doesNotMatch(env.root.textContent, /本次课程资格符合/); assert.equal(h.calls.find(call => call.options.method === 'POST').options.signal.aborted, true); mount.destroy();
  }
});
await test('409 clears stale snapshot; 403 never falls back to local qualification', async () => {
  for (const code of [409, 403]) {
    const env = dom(), h = host(env, { api: async path => { if (path === '/organization/me') return { status: 'BOUND' }; if (path.endsWith('/scopes')) return { scopes: [{ scope_id: 1 }] }; if (path.endsWith('/qualify')) throw codeError(code); return snapshot(); } });
    const mount = mountCourseCatalog(env.root, h.ctx); await mount.ready; await select(env.root); await query(env.root);
    assert.match(env.root.textContent, code === 409 ? /依据已变化/ : /无权限/); assert.equal(queryForm(env.root), undefined); assert.doesNotMatch(env.root.textContent, /本次课程资格符合/); mount.destroy();
  }
});
for (const [name, edit] of [
  ['wrong status', result => result.status = 'INVALID_REQUEST'],
  ['wrong scope', result => result.scope_id = 2],
  ['wrong version', result => result.version = 3],
  ['wrong material version', result => result.catalog_version = 'OTHER'],
  ['wrong course', result => result.course_code = 'C2'],
  ['wrong date', result => result.as_of = '2026-09-23'],
  ['wrong bound record', result => result.eligible[0].teacher_id = 999],
  ['numeric ID coerced to text', result => result.eligible[0].teacher_id = '1'],
  ['unknown teacher', result => result.eligible[0].teacher_code = 'UNKNOWN'],
  ['incorrect eligible flag', result => result.eligible[0].eligible = false],
  ['incorrect gap flag', result => result.gaps[0].eligible = true],
  ['duplicate teacher across groups', result => result.gaps[0] = { ...result.eligible[0], eligible: false }],
  ['missing teacher', result => result.eligible.pop()],
  ['missing reasons array', result => delete result.eligible[0].reasons],
]) await test(`ready result rejects ${name} without displaying candidates`, async () => {
  const result = qualification(); edit(result);
  const env = dom(), h = host(env, { api: async path => path === '/organization/me' ? { status: 'BOUND' } : path.endsWith('/scopes') ? { scopes: [{ scope_id: 1 }] } : path.endsWith('/qualify') ? result : snapshot() });
  const mount = mountCourseCatalog(env.root, h.ctx); await mount.ready; await select(env.root); await query(env.root);
  assert.match(env.root.textContent, /不一致/); assert.doesNotMatch(env.root.textContent, /本次课程资格符合/); mount.destroy();
});
const resultContext = () => ({ schema_version: 'm04_qualification_context_v1', scope_id: 1, organization_code: '001', version: 2, catalog_version: 'MATERIAL-1', course_code: 'C0', as_of: '2026-09-22', accepted_levels: ['L1', 'L3'], allowed_cities: ['乙城', '甲城'] });
await test('qualification context accepts normalized exact arrays without showing technical summaries', async () => {
  const result = qualification(); result.qualification_context = { ...resultContext(), context_id: 'opaque-context-fingerprint', teacher_facts_id: 'opaque-facts-fingerprint' };
  const env = dom(), h = host(env, { api: async path => path === '/organization/me' ? { status: 'BOUND' } : path.endsWith('/scopes') ? { scopes: [{ scope_id: 1 }] } : path.endsWith('/qualify') ? result : snapshot() });
  const mount = mountCourseCatalog(env.root, h.ctx); await mount.ready; await select(env.root); await query(env.root, { accepted_levels: 'L3\nL1', allowed_cities: '甲城\n乙城' });
  assert.match(env.root.textContent, /本次课程资格符合 · 2 条/); assert.doesNotMatch(env.root.textContent, /opaque-context|opaque-facts|排名尚未配置|规则尚未配置|档案 ID/); mount.destroy();
});
for (const [name, edit] of [
  ['missing context', result => delete result.qualification_context],
  ['wrong context schema', result => result.qualification_context.schema_version = 'legacy'],
  ['null context', result => result.qualification_context = null],
  ['scope', result => result.qualification_context.scope_id = 2],
  ['organization', result => result.qualification_context.organization_code = '002'],
  ['version', result => result.qualification_context.version = 3],
  ['material version', result => result.qualification_context.catalog_version = 'OTHER'],
  ['course', result => result.qualification_context.course_code = 'C2'],
  ['date', result => result.qualification_context.as_of = '2026-09-23'],
  ['levels', result => result.qualification_context.accepted_levels = ['L1']],
  ['cities', result => result.qualification_context.allowed_cities = ['甲城']],
  ['duplicate context values', result => result.qualification_context.accepted_levels = ['L1', 'L3', 'L3']],
  ['missing context list', result => delete result.qualification_context.accepted_levels],
]) await test(`required context rejects mismatched ${name}`, async () => {
  const result = qualification(); result.qualification_context = resultContext(); edit(result);
  const env = dom(), h = host(env, { api: async path => path === '/organization/me' ? { status: 'BOUND' } : path.endsWith('/scopes') ? { scopes: [{ scope_id: 1 }] } : path.endsWith('/qualify') ? result : snapshot() });
  const mount = mountCourseCatalog(env.root, h.ctx); await mount.ready; await select(env.root); await query(env.root, { accepted_levels: 'L3\nL1', allowed_cities: '甲城\n乙城' });
  assert.match(env.root.textContent, /不一致/); assert.doesNotMatch(env.root.textContent, /本次课程资格符合/); mount.destroy();
});
await test('server-invalid query only displays Chinese issues and never prior results', async () => {
  const env = dom(), h = host(env, { api: async path => path === '/organization/me' ? { status: 'BOUND' } : path.endsWith('/scopes') ? { scopes: [{ scope_id: 1 }] } : path.endsWith('/qualify') ? { ...qualification(), status: 'INVALID_REQUEST', ready: false, issues: [{ code: 'COURSE_INACTIVE', message: '此课程已停用' }] } : snapshot() });
  const mount = mountCourseCatalog(env.root, h.ctx); await mount.ready; await select(env.root); await query(env.root);
  assert.match(env.root.textContent, /此课程已停用/); assert.doesNotMatch(env.root.textContent, /本次课程资格符合|COURSE_INACTIVE/); mount.destroy();
});
await test('pre-abort is inert; cleanup preserves siblings and prevents late writes', async () => {
  const env = dom(), abort = new AbortController(); abort.abort(); const h = host(env, { signal: abort.signal }); const before = env.doc.writes;
  const inert = mountCourseCatalog(env.root, h.ctx); await inert.ready; inert.destroy(); assert.equal(env.doc.writes, before); assert.equal(h.calls.length, 0);
  const pending = deferred(), env2 = dom(), signal = new AbortController(), h2 = host(env2, { signal: signal.signal, api: async () => pending.promise });
  const sibling = env2.doc.createElement('p'); env2.root.append(sibling); const m2 = mountCourseCatalog(env2.root, h2.ctx); const oldButton = action(env2.root, 'refresh');
  signal.abort(); const writes = env2.doc.writes; pending.resolve({ status: 'BOUND' }); await m2.ready; await oldButton.dispatch('click'); m2.destroy();
  assert.equal(env2.doc.writes, writes); assert.deepEqual(env2.root.childNodes, [sibling]); assert.equal(h2.calls.length, 1); assert.equal(h2.calls[0].options.signal.aborted, true);
});
await test('isCurrent suppresses late reads and delegated listeners remain bounded', async () => {
  const env = dom(), h = host(env), mount = mountCourseCatalog(env.root, h.ctx); await mount.ready;
  for (let i = 0; i < 8; i++) { await select(env.root); await query(env.root); }
  const listeners = all(env.root, () => true).reduce((total, node) => total + [...node.events.values()].reduce((sum, handlers) => sum + handlers.size, 0), 0);
  assert.equal(listeners, 4); mount.destroy();
  let current = true; const pending = deferred(), env2 = dom(), h2 = host(env2, { isCurrent: () => current, api: async () => pending.promise }); const m2 = mountCourseCatalog(env2.root, h2.ctx);
  current = false; const writes = env2.doc.writes; pending.resolve({ status: 'BOUND' }); await m2.ready; assert.equal(env2.doc.writes, writes); assert.equal(h2.calls.length, 1); m2.destroy();
});
await test('modal close, route abort and replacement clean only the owned modal', async () => {
  const env = dom(), h = host(env), opened = openCourseCatalog(h.ctx); await opened.ready;
  assert.equal(h.ctx.lastModalOptions.noFoot, true); h.ctx.closeModal(); assert.equal(env.doc.getElementById('modal-mask'), undefined); opened.destroy();
  const signal = new AbortController(), h2 = host(env, { signal: signal.signal }), second = openCourseCatalog(h2.ctx); await second.ready; signal.abort(); assert.equal(env.doc.getElementById('modal-mask'), undefined);
  const third = openCourseCatalog(h.ctx); await third.ready; const replacement = h.ctx.openModal('other', '<div>other</div>', {}); third.destroy(); assert.equal(env.doc.getElementById('modal-mask'), replacement); h.ctx.closeModal();
});
assert.doesNotMatch(source, /\bfetch\s*\(|localStorage|sessionStorage|createDemo|\/preview|\/confirm|\/history|\.css/);
console.log(`M04 read-only UI checks passed: ${passed}`);
