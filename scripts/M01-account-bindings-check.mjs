// Synthetic data only. No package dependencies, browser, server, or production API.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const modulePath = new URL('../web/modules/identity/account-bindings.js', import.meta.url);
const source = await readFile(modulePath, 'utf8');
const { mountAccountBindings } = await import(`data:text/javascript;base64,${Buffer.from(`${source}\n//# sourceURL=M01-account-bindings-under-test.js`).toString('base64')}`);
const copy = value => structuredClone(value);
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const httpError = status => Object.assign(new Error(`合成错误 ${status}`), { status });
let assertions = 0;
const failures = [];
function check(label, actual, expected = true) { assert.deepEqual(actual, expected, label); assertions++; }
async function scenario(name, test) { try { await test(); console.log(`PASS ${name}`); } catch (error) { failures.push(name); console.error(`FAIL ${name}: ${error.stack || error}`); } }

// A deliberately small DOM substitute: selectors, form values, events, and mutation tracking.
// It checks component behavior, not browser parsing fidelity, visual layout, or accessibility.
const decode = text => String(text).replace(/&quot;/g, '"').replace(/&#39;|&#x27;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
const encode = text => String(text ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
class NodeStub {
  constructor(tag = 'div', attributes = {}, ledger = { writes: 0 }) {
    this.tagName = tag.toUpperCase(); this.attributes = { ...attributes }; this.children = []; this.listeners = new Map(); this.ledger = ledger; this.dataset = {}; this.style = {}; this.hidden = false; this.disabled = 'disabled' in attributes; this.readOnly = 'readonly' in attributes; this.value = decode(attributes.value ?? ''); this.isConnected = true; this._html = ''; this._text = '';
    this.classList = { add() {}, remove() {}, toggle() {}, contains: name => (this.attributes.class || '').split(/\s+/).includes(name) };
    for (const [key, value] of Object.entries(attributes)) if (key.startsWith('data-')) this.dataset[key.slice(5).replace(/-([a-z])/g, (_, char) => char.toUpperCase())] = value;
  }
  set innerHTML(html) {
    this.ledger.writes++; this._html = String(html); this._text = ''; this.children = [];
    const stack = [this]; const tokens = this._html.match(/<[^>]*>|[^<]+/g) || [];
    for (const token of tokens) {
      if (token.startsWith('</')) { if (stack.length > 1) stack.pop(); continue; }
      if (!token.startsWith('<')) { stack.at(-1)._text += decode(token); continue; }
      const tag = /^<([\w-]+)/.exec(token)?.[1]; if (!tag) continue;
      const attrs = {}; const attrText = token.slice(tag.length + 1, -1);
      for (const match of attrText.matchAll(/([\w-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g)) attrs[match[1]] = decode(match[2] ?? match[3] ?? match[4] ?? '');
      const child = new NodeStub(tag, attrs, this.ledger); child.parentNode = stack.at(-1); stack.at(-1).children.push(child);
      if (!['input', 'br', 'hr', 'img', 'meta', 'link'].includes(tag) && !token.endsWith('/>')) stack.push(child);
    }
    for (const select of this.querySelectorAll('select')) select.value = select.children.find(child => child.tagName === 'OPTION' && child.hasAttribute('selected'))?.value ?? select.children.find(child => child.tagName === 'OPTION')?.value ?? '';
  }
  get innerHTML() { return this._html; }
  set textContent(text) { this.ledger.writes++; this._text = String(text); this._html = ''; this.children = []; }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  get options() { return this.children.filter(child => child.tagName === 'OPTION'); }
  setAttribute(name, value) { this.attributes[name] = String(value); if (name === 'disabled') this.disabled = true; if (name === 'readonly') this.readOnly = true; this.ledger.writes++; }
  removeAttribute(name) { delete this.attributes[name]; if (name === 'disabled') this.disabled = false; this.ledger.writes++; }
  getAttribute(name) { return this.attributes[name] ?? null; }
  hasAttribute(name) { return Object.hasOwn(this.attributes, name); }
  matches(selector) {
    if (selector.includes(',')) return selector.split(',').some(part => this.matches(part.trim()));
    const clean = selector.replace(/:not\(\[disabled\]\)/g, ''); if (selector.includes(':not([disabled])') && this.disabled) return false;
    if (clean.startsWith('#')) return this.attributes.id === clean.slice(1);
    if (clean.startsWith('.')) return this.classList.contains(clean.slice(1));
    const attribute = /^(?:([\w-]+))?\[([\w-]+)(?:=["']?([^\]"']+)["']?)?\]$/.exec(clean);
    if (attribute) return (!attribute[1] || this.tagName === attribute[1].toUpperCase()) && this.hasAttribute(attribute[2]) && (attribute[3] === undefined || this.getAttribute(attribute[2]) === attribute[3]);
    return this.tagName === clean.toUpperCase();
  }
  querySelectorAll(selector) { const descendants = this.children.flatMap(child => [child, ...child.querySelectorAll('*')]); return selector === '*' ? descendants : descendants.filter(child => child.matches(selector)); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  closest(selector) { return this.matches(selector) ? this : this.parentNode?.closest(selector) || null; }
  contains(node) { return node === this || this.children.some(child => child.contains(node)); }
  addEventListener(name, callback) { const list = this.listeners.get(name) || new Set(); list.add(callback); this.listeners.set(name, list); }
  removeEventListener(name, callback) { this.listeners.get(name)?.delete(callback); }
  dispatchEvent(event) { const normalized = { type: event.type, target: this, preventDefault() {} }; this[`on${event.type}`]?.(normalized); for (const callback of this.listeners.get(event.type) || []) callback(normalized); }
  click() { this.dispatchEvent({ type: 'click' }); }
  focus() { this.focused = true; }
  replaceChildren() { this.innerHTML = ''; }
  remove() { this.isConnected = false; if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(child => child !== this); this.ledger.writes++; }
}

function fixture() {
  const optionalRelation = { required: false, allowedTargetRoles: ['ROLE-LEADER'], targetMustCoverOrganization: true, allowSelf: false };
  return {
    version: 'synthetic-bindings-v01',
    codeRules: { organizationPattern: 'ORG-[0-9]{3}', personPattern: 'PERSON-[0-9]{5}', rolePattern: 'ROLE-[A-Z]+' },
    roleCodes: ['ROLE-STAFF', 'ROLE-LEADER'],
    organizations: [{ organizationCode: 'ORG-001', parentOrganizationCode: null, enabled: true }],
    people: Array.from({ length: 5 }, (_, index) => ({ personCode: `PERSON-${String(index + 1).padStart(5, '0')}`, organizationCode: 'ORG-001', responsibleOrganizationCodes: index === 1 ? ['ORG-001'] : [], leaderPersonCode: null, bpPersonCode: null, roleCodes: [index === 1 ? 'ROLE-LEADER' : 'ROLE-STAFF'], enabled: index !== 2 })),
    relations: ['ROLE-STAFF', 'ROLE-LEADER'].map(roleCode => ({ roleCode, leader: copy(optionalRelation), bp: copy(optionalRelation) })),
    accountBindings: [{ accountId: 11, personCode: 'PERSON-00001', enabled: true }, { accountId: 12, personCode: 'PERSON-00002', enabled: false }, { accountId: 13, personCode: 'PERSON-00003', enabled: true }],
    grants: [{ ruleId: 'synthetic-view', roleCode: 'ROLE-STAFF', resource: 'training.record', action: 'VIEW', effect: 'ALLOW', scope: 'OWN_ORG', organizationCodes: [] }],
  };
}

function harness({ role = 'admin', configuration = fixture(), apiOverride, signal, isCurrent, actions = [], columns, bindingColumns } = {}) {
  const h = { calls: [], tables: [], forms: [], modals: [], toasts: [], loaded: [], closed: 0, configuration: copy(configuration), role, current: true };
  h.users = [11, 12, 13, 14].map(id => ({ id, username: `synthetic-${id}`, name: `合成账号 ${id}`, role: id === 11 ? 'admin' : 'viewer', status: 1 }));
  h.root = new NodeStub(); h.root.innerHTML = '<div data-binding-notice></div><button data-binding-refresh>刷新</button><div data-binding-table></div>';
  const normalApi = async (path, options) => {
    if (path === '/users') return copy(h.users);
    if (path === '/organization/me') return { version: 'synthetic-me-v01', status: 'BOUND', person: fixture().people[0], organizations: [{ organizationCode: 'ORG-001' }] };
    if (path === '/organization/config' && !options.body) return { version: h.configuration?.version ?? null, configuration: copy(h.configuration) };
    if (path === '/organization/config' && options.body) { h.configuration = copy(options.body.configuration); return { version: h.configuration.version, configuration: copy(h.configuration) }; }
    throw new Error(`Unexpected synthetic route: ${path}`);
  };
  h.host = {
    async api(path, options = {}) { h.calls.push({ path, options: { ...options, ...(options.body ? { body: copy(options.body) } : {}) } }); return apiOverride ? apiOverride(path, options, h, normalApi) : normalApi(path, options); },
    getUser: () => ({ role: h.role }),
    actions, columns, bindingColumns,
    openModal(title, html, options = {}) { const mask = new NodeStub('div', { id: 'modal-mask' }); mask.innerHTML = `${html}<button id="modal-ok">保存</button><button id="modal-cancel">取消</button>`; const modal = { title, html, options, mask, closed: false }; h.modals.push(modal); h.modal = modal; return mask; },
    closeModal() { if (!h.modal || h.modal.closed) return; h.modal.closed = true; h.modal.mask.isConnected = false; h.closed++; h.modal.options.onClose?.(); },
    renderForm(fields, data = {}) { h.forms.push({ fields: copy(fields), data: copy(data) }); return fields.map(field => {
      const value = data[field.k] ?? field.value ?? '';
      if (field.type === 'select') return `<select data-k="${encode(field.k)}"${field.disabled ? ' disabled' : ''}>${(field.options || []).map(option => { const key = typeof option === 'object' ? option.v : option; const label = typeof option === 'object' ? option.l : option; return `<option value="${encode(key)}"${String(key) === String(value) ? ' selected' : ''}>${encode(label)}</option>`; }).join('')}</select>`;
      return `<input data-k="${encode(field.k)}" value="${encode(value)}"${field.type === 'readonly' ? ' readonly' : ''}${field.disabled ? ' disabled' : ''}>`;
    }).join(''); },
    collectForm(mask, fields) { return Object.fromEntries(fields.map(field => [field.k, mask.querySelector(`[data-k="${field.k}"]`)?.value ?? ''])); },
    renderTable(columns, rows, actions, kind) { h.tables.push({ columns, rows: copy(rows), actions, kind }); return '<table><tbody></tbody></table>'; },
    bindTableActions(root, rows, actions) { h.bound = { root, rows, actions }; },
    toast(message, error) { h.toasts.push({ message, error }); },
    onLoaded: data => h.loaded.push(data),
    isCurrent: () => isCurrent ? isCurrent() : h.current,
    ...(signal ? { signal } : {}),
  };
  h.posts = () => h.calls.filter(call => call.options.body);
  h.open = async id => { const action = h.bound?.actions?.find(item => item.l === '绑定人员'); if (!action) return null; const row = h.bound.rows.find(item => String(item.id) === String(id)) || h.users.find(item => String(item.id) === String(id)); await action.onClick(row); return h.modal; };
  h.field = key => h.modal?.mask.querySelector(`[data-k="${key}"]`);
  h.set = (key, value) => { const element = h.field(key); assert.ok(element, `Missing form field ${key}`); element.value = value; element.dispatchEvent({ type: 'change' }); };
  h.save = async () => h.modal.options.onOk();
  h.mount = () => (h.controller = mountAccountBindings(h.root, h.host));
  return h;
}

await scenario('administrator loads users and configuration in parallel', async () => {
  const users = deferred(), config = deferred();
  const h = harness({ apiOverride: path => path === '/users' ? users.promise : config.promise });
  const controller = h.mount(); check('mount返回同步控制器', typeof controller.refresh, 'function'); check('ready是Promise', typeof controller.ready?.then, 'function');
  await tick(); check('两项GET在任一响应前启动', h.calls.map(call => call.path).sort(), ['/organization/config', '/users']);
  users.resolve(copy(h.users)); config.resolve({ version: h.configuration.version, configuration: copy(h.configuration) }); await controller.ready;
  check('表格保留全部合成账号', h.bound.rows.length, 4); check('管理员绑定动作存在', h.bound.actions.some(action => action.l === '绑定人员')); controller.destroy();
});

await scenario('default binding columns remain compatible and host can group their presentation', async () => {
  const original = harness(); await original.mount().ready;
  check('默认保留原六个绑定列', original.tables.at(-1).columns.slice(3).map(column => column.k), ['bindingPerson', 'bindingOrg', 'bindingLeader', 'bindingBp', 'bindingPersonStatus', 'bindingStatus']);
  original.controller.destroy();
  const columns = [{ k: 'username', l: '账号' }];
  const bindingColumns = [{ k: 'bindingPerson', l: '业务身份', render: row => row.bindingPerson + ' · ' + row.bindingOrg },
    { k: 'bindingLeader', l: '负责人/BP', render: row => row.bindingLeader + ' / ' + row.bindingBp },
    { k: 'bindingStatus', l: '绑定状态', render: row => row.bindingStatus + ' / ' + row.bindingPersonStatus }];
  const grouped = harness({ columns, bindingColumns }); await grouped.mount().ready;
  const table = grouped.tables.at(-1);
  check('宿主三列替代默认六列而非追加', table.columns.map(column => column.k), ['username', 'bindingPerson', 'bindingLeader', 'bindingStatus']);
  check('展示回调保持原对象', table.columns.slice(1).every((column, index) => column === bindingColumns[index]));
  check('分组展示仍得到全部机构数据', bindingColumns[0].render(table.rows[0]), 'PERSON-00001 · ORG-001');
  check('人员停用仍传到自定义列', table.rows.find(row => row.id === 13).bindingPersonStatus, '人员停用');
  check('宿主列数组不被修改', [columns.length, bindingColumns.length], [1, 3]);
  await grouped.open(14); check('分组不影响绑定动作与原弹窗', grouped.forms.at(-1).fields.some(field => field.k === 'personCode')); grouped.controller.destroy();
  const unknown = harness({ columns, bindingColumns, configuration: null }); await unknown.mount().ready;
  check('未知配置的自定义展示仍明确未核实', bindingColumns[0].render(unknown.tables.at(-1).rows[0]), '未核实 · 未核实');
  unknown.controller.destroy();
});

await scenario('save preserves full configuration except accountBindings and generated version', async () => {
  const h = harness(); const before = copy(h.configuration); await h.mount().ready; await h.open(14);
  const fields = h.forms.at(-1).fields;
  check('人员使用下拉选择', fields.find(field => field.k === 'personCode').type, 'select');
  check('enabled使用true/false下拉', fields.find(field => field.k === 'enabled').options.map(option => String(typeof option === 'object' ? option.v : option)).sort(), ['false', 'true']);
  check('版本不是用户输入项', !fields.some(field => /version/i.test(field.k)));
  check('其余详情不可编辑', fields.filter(field => !['personCode', 'enabled'].includes(field.k)).every(field => field.type === 'readonly' || field.disabled));
  h.set('personCode', 'PERSON-00004'); h.set('enabled', 'false'); await h.save();
  check('只提交一次', h.posts().length, 1); const body = h.posts()[0].options.body;
  check('请求只有契约的两个字段', Object.keys(body).sort(), ['configuration', 'expectedVersion']); check('expectedVersion取读取的版本', body.expectedVersion, before.version);
  check('组件生成不同的新版本', typeof body.configuration.version === 'string' && body.configuration.version.length > 0 && body.configuration.version !== before.version);
  const untouched = config => Object.fromEntries(Object.entries(config).filter(([key]) => !['accountBindings', 'version'].includes(key)));
  check('人员机构岗位授权等全部保持', untouched(body.configuration), untouched(before));
  check('原有绑定保持', body.configuration.accountBindings.filter(binding => binding.accountId !== 14), before.accountBindings);
  check('前导零及停用值精确保留', body.configuration.accountBindings.find(binding => binding.accountId === 14), { accountId: 14, personCode: 'PERSON-00004', enabled: false }); h.controller.destroy();
});

await scenario('one-to-one includes disabled bindings and forged unknown codes', async () => {
  const h = harness(); await h.mount().ready; await h.open(14);
  for (const code of ['PERSON-00001', 'PERSON-00002', 'PERSON-99999']) { h.set('personCode', code); h.set('enabled', 'true'); await h.save(); }
  check('已占用含停用绑定和未知编码均不提交', h.posts().length, 0);
  h.host.closeModal(); await h.open(11); h.set('personCode', 'PERSON-00001'); h.set('enabled', 'false'); await h.save();
  check('同账号可保留自己的编码并停用', h.posts().length, 1); check('同账号绑定不追加重复记录', h.posts()[0].options.body.configuration.accountBindings.filter(binding => binding.accountId === 11).length, 1); h.controller.destroy();
});

await scenario('409 preserves input and blocks retry until close refresh reopen', async () => {
  let fail = true; const deleted = [];
  const h = harness({ actions: [{ l: '删除', show: () => true, onClick: row => deleted.push(row.id) }], apiOverride: (path, options, _h, normal) => { if (options.body && fail) throw httpError(409); return normal(path, options); } });
  await h.mount().ready;
  const oldDelete = h.bound.actions.find(action => action.l === '删除').onClick;
  const oldUnboundRow = h.bound.rows.find(row => row.id === 14);
  await h.open(14); h.set('personCode', 'PERSON-00004'); h.set('enabled', 'false');
  check('409返回false保留弹窗', await h.save(), false); check('409保留选择', h.field('personCode').value, 'PERSON-00004'); check('409保留状态', h.field('enabled').value, 'false');
  check('409清快照后全部绑定状态待核实', h.bound.rows.map(row => row.bindingStatus), Array(4).fill('绑定状态未核实'));
  check('409不把旧绑定误报为未绑定', h.bound.rows.every(row => row.bindingPerson === '未核实'));
  await oldDelete(oldUnboundRow); check('409清快照后旧删除回调无效', deleted, []);
  await h.save(); check('不能重复提交旧快照', h.posts().length, 1);
  h.host.closeModal(); h.configuration.version = 'synthetic-concurrent-v02'; fail = false; await h.controller.refresh(); await h.open(14); h.set('personCode', 'PERSON-00005'); await h.save();
  check('刷新重开后使用新expectedVersion', h.posts().at(-1).options.body.expectedVersion, 'synthetic-concurrent-v02'); h.controller.destroy();
});

await scenario('400 preserves input and permits corrected retry', async () => {
  let attempt = 0;
  const h = harness({ apiOverride: (path, options, _h, normal) => { if (options.body && attempt++ === 0) throw httpError(400); return normal(path, options); } });
  await h.mount().ready; await h.open(14); h.set('personCode', 'PERSON-00004'); h.set('enabled', 'true');
  check('400保留弹窗', await h.save(), false); check('400保留输入', h.field('personCode').value, 'PERSON-00004'); h.set('personCode', 'PERSON-00005'); await h.save();
  check('纠正后可再次提交', h.posts().length, 2); check('重试提交纠正的编码', h.posts()[1].options.body.configuration.accountBindings.find(binding => binding.accountId === 14).personCode, 'PERSON-00005'); h.controller.destroy();
});

for (const error of [new TypeError('synthetic connection lost'), httpError(500), httpError(503)]) await scenario(`uncertain result ${error.status || 'network'} never auto-retries POST`, async () => {
  const h = harness({ apiOverride: (path, options, _h, normal) => { if (options.body) throw error; return normal(path, options); } });
  await h.mount().ready; await h.open(14); h.set('personCode', 'PERSON-00004'); h.set('enabled', 'true'); await h.save(); await tick(); await h.save();
  check('结果不明不重试旧请求', h.posts().length, 1); check('结果不明保留输入', h.field('personCode').value, 'PERSON-00004'); check('用户可见刷新提示', /刷新/.test(h.root.textContent + h.modal.mask.textContent + h.toasts.map(item => item.message).join(''))); h.controller.destroy();
});

for (const status of [401, 403]) await scenario(`${status} stops editing and stale callbacks cannot publish`, async () => {
  const h = harness({ apiOverride: (path, options, _h, normal) => { if (options.body) throw httpError(status); return normal(path, options); } });
  await h.mount().ready; await h.open(14); h.set('personCode', 'PERSON-00004'); h.set('enabled', 'true'); const staleSave = h.modal.options.onOk;
  await h.save(); const modalCount = h.modals.length; await staleSave(); await h.open(11);
  check('认证失败后不再提交', h.posts().length, 1); check('认证失败后不再开放编辑', h.modals.length, modalCount); check('认证失败后清除已加载的管理行', h.bound.rows.length, 0); h.controller.destroy();
});

for (const role of ['viewer', 'manager']) await scenario(`${role} reads only own identity`, async () => {
  const h = harness({ role }); await h.mount().ready; await h.controller.refresh();
  check('非管理员只请求me', h.calls.every(call => call.path === '/organization/me' && !call.options.body));
  check('非管理员没有绑定动作', !h.bound?.actions?.some(action => action.l === '绑定人员')); check('非管理员不加载账号列表', !h.calls.some(call => call.path === '/users')); h.controller.destroy();
});

await scenario('missing configuration never creates people or privileges', async () => {
  const h = harness({ configuration: null }); await h.mount().ready;
  check('未配置仍展示账号', h.bound.rows.length, 4); check('未配置绑定状态不误报为未绑定', h.bound.rows.map(row => row.bindingStatus), Array(4).fill('绑定状态未核实'));
  await h.open(14); check('未配置不开放绑定弹窗', h.modals.length, 0); check('未配置不提交初始化配置', h.posts().length, 0); h.controller.destroy();
});

await scenario('configuration read failure preserves account browsing without claiming no binding', async () => {
  const h = harness({ apiOverride: (path, options, _h, normal) => path === '/organization/config' ? Promise.reject(httpError(503)) : normal(path, options) });
  await h.mount().ready;
  check('配置读取失败仍保留全部账号', h.bound.rows.map(row => row.id), [11, 12, 13, 14]);
  check('读取失败状态明确待核实', h.bound.rows.map(row => row.bindingStatus), Array(4).fill('绑定状态未核实'));
  check('读取失败人员列不误报未绑定', h.bound.rows.every(row => row.bindingPerson === '未核实'));
  await h.open(14); check('读取失败禁止绑定编辑', h.modals.length, 0); check('读取失败有可见提示', /读取失败/.test(h.root.querySelector('[data-binding-notice]').textContent)); h.controller.destroy();
});

await scenario('orphaned account binding retains existing accounts and disables binding changes', async () => {
  const configuration = fixture(); configuration.accountBindings.push({ accountId: 99, personCode: 'PERSON-00004', enabled: false });
  const h = harness({ configuration }); await h.mount().ready;
  check('遗留绑定不隐藏现有账号', h.bound.rows.map(row => row.id), [11, 12, 13, 14]);
  check('遗留关系有明确提示', /遗留关系/.test(h.root.querySelector('[data-binding-notice]').textContent));
  check('遗留关系下绑定动作不可见', h.bound.actions.find(action => action.l === '绑定人员').show(h.bound.rows[0]), false);
  await h.open(14); check('遗留关系下旧绑定入口也不能打开', h.modals.length, 0); check('遗留关系未被自动清理或补造', h.configuration, configuration); check('遗留关系不生成配置提交', h.posts().length, 0); h.controller.destroy();
});

await scenario('delete action protects enabled and disabled bindings while preserving host behavior', async () => {
  const deleted = [], shown = []; let originalAllows = true;
  const original = { l: '删除', cls: 'red', show: row => { shown.push(row.id); return originalAllows; }, onClick: row => deleted.push(row) };
  const h = harness({ actions: [original] }); await h.mount().ready;
  const wrapped = h.bound.actions.find(action => action.l === '删除');
  for (const id of [11, 12, 13]) {
    const row = h.bound.rows.find(item => item.id === id); check(`账号${id}已有绑定不能显示删除`, wrapped.show(row), false); await wrapped.onClick(row);
  }
  check('所有已有绑定含停用绑定均不调用删除', deleted, []);
  const unbound = h.bound.rows.find(row => row.id === 14);
  check('未绑定账号保留宿主show的允许结果', wrapped.show(unbound));
  originalAllows = false; check('未绑定账号保留宿主show的拒绝结果', wrapped.show(unbound), false);
  check('宿主show收到原账号行', shown, [14, 14]); originalAllows = true;
  await wrapped.onClick(unbound); check('未绑定账号调用原onClick且保留行对象', deleted.length === 1 && deleted[0] === unbound);
  check('原操作对象未被原地改写', original.onClick !== wrapped.onClick && original.show !== wrapped.show && original.cls === wrapped.cls); h.controller.destroy();
});

for (const unavailable of ['missing', 'read-failed']) await scenario(`unknown configuration ${unavailable} disables account deletion`, async () => {
  const deleted = [];
  const h = harness({ configuration: unavailable === 'missing' ? null : fixture(), actions: [{ l: '删除', show: () => true, onClick: row => deleted.push(row.id) }],
    ...(unavailable === 'read-failed' ? { apiOverride: (path, options, _h, normal) => path === '/organization/config' ? Promise.reject(httpError(500)) : normal(path, options) } : {}) });
  await h.mount().ready; const action = h.bound.actions.find(item => item.l === '删除');
  check('未知配置全部账号隐藏删除', h.bound.rows.every(row => action.show(row) === false));
  for (const row of h.bound.rows) await action.onClick(row);
  check('未知配置直接调用删除回调也无操作', deleted, []); h.controller.destroy();
});

await scenario('old delete callback is invalidated when refresh clears its snapshot', async () => {
  const deleted = []; let failConfig = false;
  const h = harness({ actions: [{ l: '删除', show: () => true, onClick: row => deleted.push(row.id) }], apiOverride: (path, options, _h, normal) => failConfig && path === '/organization/config' ? Promise.reject(httpError(503)) : normal(path, options) });
  await h.mount().ready; const row = h.bound.rows.find(item => item.id === 14); const action = h.bound.actions.find(item => item.l === '删除');
  check('刷新前未绑定账号原本可删除', action.show(row)); failConfig = true; await h.controller.refresh();
  await action.onClick(row); check('刷新清快照后旧删除回调无操作', deleted, []); check('刷新失败后账号绑定状态待核实', h.bound.rows.every(item => item.bindingStatus === '绑定状态未核实')); h.controller.destroy();
});

await scenario('duplicate submit is suppressed while POST is pending', async () => {
  const pending = deferred();
  const h = harness({ apiOverride: (path, options, _h, normal) => options.body ? pending.promise : normal(path, options) });
  await h.mount().ready; await h.open(14); h.set('personCode', 'PERSON-00004'); h.set('enabled', 'true'); const first = h.save(); await tick(); const second = h.save(); await tick();
  check('提交中重复回调不再POST', h.posts().length, 1); const configuration = copy(h.posts()[0].options.body.configuration); pending.resolve({ version: configuration.version, configuration }); await Promise.all([first, second]); h.controller.destroy();
});

await scenario('destroy ignores delayed reads and removes refresh behavior', async () => {
  const pending = deferred(); const h = harness({ apiOverride: () => pending.promise }); const controller = h.mount(); await tick(); controller.destroy();
  const counts = { writes: h.root.ledger.writes, tables: h.tables.length, loaded: h.loaded.length, toasts: h.toasts.length, calls: h.calls.length };
  pending.resolve({ version: 'late', configuration: fixture() }); await controller.ready; await controller.refresh(); h.root.querySelector('[data-binding-refresh]')?.click(); await tick();
  check('销毁后延迟读取不更新页面', h.root.ledger.writes, counts.writes); check('销毁后不再渲染表格', h.tables.length, counts.tables); check('销毁后不发通知或onLoaded', [h.toasts.length, h.loaded.length], [counts.toasts, counts.loaded]); check('销毁后刷新不请求', h.calls.length, counts.calls);
});

for (const shutdown of ['destroy', 'abort', 'isCurrent']) await scenario(`${shutdown} prevents stale modal submission`, async () => {
  const signal = new AbortController(); const h = harness({ signal: signal.signal }); await h.mount().ready; await h.open(14); h.set('personCode', 'PERSON-00004'); h.set('enabled', 'true'); const staleSave = h.modal.options.onOk;
  if (shutdown === 'destroy') h.controller.destroy(); else if (shutdown === 'abort') signal.abort(); else h.current = false;
  const writes = h.root.ledger.writes; await staleSave(); check('失效弹窗不发送POST', h.posts().length, 0); check('失效弹窗不修改根元素', h.root.ledger.writes, writes); h.controller.destroy();
});

await scenario('destroy ignores delayed POST result without auto-refresh', async () => {
  const pending = deferred(); const h = harness({ apiOverride: (path, options, _h, normal) => options.body ? pending.promise : normal(path, options) });
  await h.mount().ready; await h.open(14); h.set('personCode', 'PERSON-00004'); h.set('enabled', 'true'); const saving = h.save(); await tick(); h.controller.destroy();
  const counts = { writes: h.root.ledger.writes, calls: h.calls.length, closed: h.closed, toasts: h.toasts.length, loaded: h.loaded.length };
  const configuration = copy(h.posts()[0].options.body.configuration); pending.resolve({ version: configuration.version, configuration }); await saving; await tick();
  check('延迟保存结果不改页面', h.root.ledger.writes, counts.writes); check('延迟保存结果不自动刷新', h.calls.length, counts.calls); check('延迟保存结果不再通知或关闭宿主弹窗', [h.toasts.length, h.closed, h.loaded.length], [counts.toasts, counts.closed, counts.loaded]);
});

await scenario('already aborted mount has no effects', async () => {
  const abort = new AbortController(); abort.abort(); const h = harness({ signal: abort.signal }); const writes = h.root.ledger.writes;
  const controller = h.mount(); await controller.ready; await controller.refresh(); controller.destroy(); check('预取消不请求API', h.calls.length, 0); check('预取消不改已有根元素', h.root.ledger.writes, writes);
});

console.log(`M01 account bindings: ${assertions} assertions passed; ${failures.length} scenarios failed.`);
if (failures.length) process.exitCode = 1;
