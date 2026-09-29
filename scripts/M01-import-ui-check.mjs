// Synthetic-only DOM substitute. Tests below check behavior, not browser layout.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = await readFile(new URL('../web/modules/identity/account-import.js', import.meta.url), 'utf8');
const { mountAccountImport } = await import('data:text/javascript;base64,' + Buffer.from(source + '\n//# sourceURL=M01-account-import-under-test.js').toString('base64'));
const copy = value => structuredClone(value);
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const httpError = status => Object.assign(new Error('Synthetic error'), { status });
let assertions = 0, scenarios = 0; const failures = [];
function check(label, actual, expected = true) { assert.deepEqual(actual, expected, label); assertions++; }
async function scenario(label, test) { scenarios++; try { await test(); console.log('PASS ' + label); } catch (error) { failures.push(label); console.error('FAIL ' + label + ': ' + (error.stack || error)); } }

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
  return { batchKey: 'SYNTHETIC-BATCH-1', sourceFingerprint: 'SYNTHETIC-PRIVATE-FINGERPRINT', revision: 'REV-1', reviewToken: 'SYNTHETIC-PRIVATE-REVIEW-TOKEN', imported: false,
    rows: [
      { reference: 'teacher-row-3', name: '合成人员甲', organization: '合成机构甲', identities: ['兼职教师'], approvalRoles: [], approvalEligibility: 'NO_APPROVAL_ROLE', sourceIdentityEvidence: 'NOT_FOUND_IN_OLD_ROSTER' },
      { reference: 'teacher-row-4', name: '合成人员乙', organization: '合成机构乙', identities: ['兼职教师'], approvalRoles: [{ kind: 'BRANCH_RESPONSIBLE', proposedBranches: ['合成机构乙'], proposedRegions: [], canApprove: false }], approvalEligibility: 'BRANCH_SCOPE_PREPARED', sourceIdentityEvidence: 'AVAILABLE' },
      { reference: 'teacher-row-5', name: '合成人员丙', organization: '合成总部', identities: ['兼职教师'], approvalRoles: [{ kind: 'BP', proposedBranches: ['合成机构甲', '合成机构乙'], proposedRegions: ['合成区域'], canApprove: false }, { kind: 'BRANCH_RESPONSIBLE', proposedBranches: ['合成机构甲'], proposedRegions: [], canApprove: false }], approvalEligibility: 'BP_SCOPE_PREPARED', sourceIdentityEvidence: 'AVAILABLE' },
    ], summary: { candidates: 3, historicalExcluded: 2 } };
}
function makeReceipt(preview, decisions, options = {}) {
  return { batchKey: preview.batchKey, revision: 'REV-2', imported: true, replayed: false, summary: copy(preview.summary), permissionsPublished: false, accountsActivated: false,
    rows: decisions.map((choice, index) => ({ reference: choice.reference, personCode: `PERSON-${String(index + 1).padStart(3, '0')}`, accountId: choice.accountId || index + 201, action: choice.action })), ...options };
}
function harness({ role = 'admin', preview = fixture(), apiOverride, signal } = {}) {
  const h = { role, current: true, calls: [], preview: copy(preview), tables: [], forms: [], toasts: [], modals: [], closed: 0, root: new NodeStub() };
  const normal = async (path, options = {}) => {
    if (path === '/organization/account-import/preview') return copy(h.preview);
    if (path.startsWith('/organization/account-import/accounts/')) { const accountId = Number(path.split('/').at(-1)); return { accountId, username: `synthetic-${accountId}`, name: '合成既有账号', status: 1, role: 'viewer', proof: `SYNTHETIC-ACCOUNT-PROOF-${accountId}`, available: true }; }
    if (path === '/organization/account-import/commit') { const receipt = makeReceipt(h.preview, options.body.decisions); h.preview = { ...h.preview, imported: true, receipt }; return copy(receipt); }
    throw new Error(`Unexpected synthetic path ${path}`);
  };
  h.host = {
    async api(path, options = {}) { h.calls.push({ path, options: { ...options, ...(options.body ? { body: copy(options.body) } : {}) } }); return apiOverride ? apiOverride(path, options, h, normal) : normal(path, options); },
    getUser: () => ({ role: h.role }), isCurrent: () => h.current, ...(signal ? { signal } : {}),
    openModal(title, html, options = {}) { h.host.closeModal(); const mask = new NodeStub('div', { id: 'modal-mask' }); mask.innerHTML = `${html}<button id="modal-ok">保存</button><button id="modal-cancel">取消</button>`; const modal = { title, html, options, mask, closed: false }; h.modals.push(modal); h.modal = modal; return mask; },
    closeModal() { if (!h.modal || h.modal.closed) return; h.modal.closed = true; h.modal.mask.isConnected = false; h.closed++; h.modal.options.onClose?.(); },
    renderForm(fields, data = {}) { h.forms.push({ fields: copy(fields), data: copy(data) }); return fields.map(field => {
      const value = data[field.k] ?? field.value ?? '';
      if (field.type === 'select') return `<select data-k="${encode(field.k)}">${(field.options || []).map(option => `<option value="${encode(option.v)}"${String(option.v) === String(value) ? ' selected' : ''}>${encode(option.l)}</option>`).join('')}</select>`;
      return `<input data-k="${encode(field.k)}" value="${encode(value)}"${field.type === 'readonly' ? ' readonly' : ''}>`;
    }).join(''); },
    collectForm(mask, fields) { return Object.fromEntries(fields.map(field => [field.k, mask.querySelector(`[data-k="${field.k}"]`)?.value ?? ''])); },
    renderTable(columns, rows, actions, kind) { h.tables.push({ columns, rows: copy(rows), actions, kind }); return '<table><tbody></tbody></table>'; },
    bindTableActions(root, rows, actions) { h.bound = { root, rows, actions }; },
    toast(message, error) { h.toasts.push({ message, error }); },
  };
  h.mount = () => (h.controller = mountAccountImport(h.root, h.host));
  h.rootNode = key => h.root.querySelector(`[data-import-${key}]`);
  h.posts = () => h.calls.filter(call => call.options.body);
  h.open = reference => { const action = h.bound.actions.find(item => item.l === '核对导入'); action.onClick(h.bound.rows.find(row => row.reference === reference) || { reference }); return h.modal; };
  h.field = key => h.modal.mask.querySelector(`[data-k="${key}"]`);
  h.set = (key, value) => { const node = h.field(key); node.value = value; node.dispatchEvent({ type: key === 'accountId' ? 'input' : 'change' }); };
  h.checked = value => { h.modal.mask.querySelector('[data-import-reviewed]').checked = value; };
  h.lookup = async accountId => { h.set('accountId', String(accountId)); h.modal.mask.querySelector('[data-import-lookup]').click(); await tick(); };
  h.save = () => h.modal.options.onOk();
  h.review = async (reference, accountId = null) => { h.open(reference); h.set('action', accountId == null ? 'CREATE_PENDING' : 'LINK_EXISTING'); if (accountId != null) await h.lookup(accountId); h.checked(true); await h.save(); };
  h.reviewAll = async () => { for (const row of h.preview.rows) await h.review(row.reference); };
  h.confirm = () => { h.rootNode('commit').click(); return h.modal; };
  return h;
}

await scenario('admin loads preview only and shows every candidate including missing source ID', async () => {
  const h = harness(); const ctl = h.mount(); check('同步控制器接口', [typeof ctl.refresh, typeof ctl.destroy, typeof ctl.ready.then], ['function', 'function', 'function']); await ctl.ready;
  check('只读取导入预览而不拉原件或账号全表', h.calls.map(call => call.path), ['/organization/account-import/preview']);
  check('候选全部保留', h.bound.rows.length, 3); check('未核对时导入禁用', h.rootNode('commit').disabled);
  check('历史明确排除', h.rootNode('progress').textContent.includes('历史保留 2 人'));
  const columns = h.tables.at(-1).columns; check('表格仅四个内容列加操作列', columns.length, 4); check('人员列合并姓名与机构', columns.find(col => col.k === 'person').render(h.bound.rows[0]).includes('合成人员甲') && columns.find(col => col.k === 'person').render(h.bound.rows[0]).includes('合成机构甲'));
  check('缺编号明确保留候选', columns.find(col => col.k === 'reviewState').render(h.bound.rows[0]).includes('候选保留'));
  check('兼任单独标明', columns.find(col => col.k === 'approvalRoles').render(h.bound.rows[2]).includes('兼任'));
  check('技术凭据不展示给用户', !h.root.textContent.includes('SYNTHETIC-PRIVATE-REVIEW-TOKEN')); ctl.destroy();
});

for (const role of ['viewer', 'manager']) await scenario(`${role} cannot access private import routes`, async () => {
  const h = harness({ role }); await h.mount().ready; await h.controller.refresh(); h.rootNode('commit').click();
  check('非管理员不请求任何私有接口', h.calls.length, 0); check('非管理员看不到候选', h.bound.rows.length, 0); check('非管理员无法导入', h.rootNode('commit').disabled); h.controller.destroy();
});

await scenario('each person requires explicit action and unchecked review confirmation', async () => {
  const h = harness(); await h.mount().ready; h.open('teacher-row-3');
  check('不默认新建或按名关联', h.field('action').value, ''); check('核对复选框初始未勾选', Boolean(h.modal.mask.querySelector('[data-import-reviewed]').checked), false);
  await h.save(); check('未选择操作不能确认', !h.modal.closed);
  h.set('action', 'CREATE_PENDING'); await h.save(); check('未勾逐项核对仍不能确认', !h.modal.closed);
  h.checked(true); await h.save(); check('逐人核对仅保存在内存', h.posts().length, 0); check('一人完成不允许全批导入', h.rootNode('commit').disabled); check('进度精确', h.rootNode('progress').textContent.includes('1 / 3'));
  h.open('teacher-row-3'); check('重新打开需要再次勾选', Boolean(h.modal.mask.querySelector('[data-import-reviewed]').checked), false); h.host.closeModal(); h.controller.destroy();
});

await scenario('search cannot hide outstanding review from batch completion and progress persists', async () => {
  const h = harness(); await h.mount().ready; await h.review('teacher-row-3');
  h.rootNode('search').value = '合成人员甲'; h.rootNode('search').dispatchEvent({ type: 'input' });
  check('搜索仅过滤显示', h.bound.rows.length, 1); check('隐藏未核对仍被计数提醒', h.rootNode('visible').textContent.includes('2 位未核对')); check('全批进度不改为当前过滤人数', h.rootNode('progress').textContent.includes('1 / 3')); check('筛选不能解锁导入', h.rootNode('commit').disabled);
  h.rootNode('pending').click(); check('一键只切换未核对视图而不确认', h.bound.rows.length, 2); check('全批仍一人已核对', h.rootNode('progress').textContent.includes('1 / 3'));
  await h.review('teacher-row-4'); check('核对后仍保留当前筛选', h.rootNode('status').value, 'pending'); check('剩余未核对可继续查看', h.bound.rows.length, 1); h.controller.destroy();
});

await scenario('linking uses explicit account ID lookup and exact current proof, never a name match', async () => {
  const h = harness(); await h.mount().ready; h.open('teacher-row-3'); h.set('action', 'LINK_EXISTING'); h.set('accountId', '51'); h.checked(true); await h.save();
  check('未查询就填写ID不能关联', !h.modal.closed); check('尚无自动账号查询', h.calls.length, 1);
  await h.lookup(51); check('查询按明确数字ID', h.calls.at(-1).path, '/organization/account-import/accounts/51');
  check('显示账号名供人工核对', h.modal.mask.querySelector('[data-import-account-result]').textContent.includes('synthetic-51'));
  h.checked(true); h.set('accountId', '52'); check('更换ID自动取消核对勾选', Boolean(h.modal.mask.querySelector('[data-import-reviewed]').checked), false); h.checked(true); await h.save(); check('旧ID证明不能用于新ID', !h.modal.closed);
  await h.lookup(52); h.checked(true); await h.save(); check('查询后可保存关联决策', h.bound.rows[0].importChoice, '关联已有账号 #52');
  await h.review('teacher-row-4'); await h.review('teacher-row-5'); h.confirm(); await h.save(); const decision = h.posts()[0].options.body.decisions[0];
  check('仅提交查询得到的同账号证明', decision, { reference: 'teacher-row-3', action: 'LINK_EXISTING', accountId: 52, accountProof: 'SYNTHETIC-ACCOUNT-PROOF-52', reviewed: true }); h.controller.destroy();
});

await scenario('duplicate chosen account, unavailable account, and malformed numeric IDs are rejected', async () => {
  const h = harness({ apiOverride: async (path, options, _h, normal) => { const result = await normal(path, options); return path.endsWith('/99') ? { ...result, available: false } : result; } });
  await h.mount().ready; await h.review('teacher-row-3', 51); h.open('teacher-row-4'); h.set('action', 'LINK_EXISTING');
  for (const value of ['0', '-1', '1e2', '51x', '9007199254740993', '合成人员甲']) { await h.lookup(value); }
  check('无效ID不调用账号接口', h.calls.filter(call => call.path.includes('/accounts/')).length, 1);
  await h.lookup(99); h.checked(true); await h.save(); check('不可用账号不接受', !h.modal.closed);
  await h.lookup(51); h.checked(true); await h.save(); check('同账号不能在本批重复选用', !h.modal.closed); check('重复账号保留待核对进度', h.rootNode('progress').textContent.includes('1 / 3')); h.controller.destroy();
});

await scenario('all decisions required and commit payload contains only review contract', async () => {
  const h = harness(); await h.mount().ready; await h.reviewAll(); check('所有人完成才启用导入', h.rootNode('commit').disabled, false);
  h.confirm(); check('导入前呈现明确人数确认', h.modal.html.includes('逐人核对 3 人')); await h.save();
  check('仅提交一次', h.posts().length, 1); const body = h.posts()[0].options.body;
  check('提交顶层仅有核对契约', Object.keys(body).sort(), ['decisions', 'expectedRevision', 'reviewToken', 'sourceFingerprint']);
  check('版本和服务端核对凭据原样携带', [body.expectedRevision, body.reviewToken, body.sourceFingerprint], ['REV-1', 'SYNTHETIC-PRIVATE-REVIEW-TOKEN', 'SYNTHETIC-PRIVATE-FINGERPRINT']);
  check('每人仅发送reference与核对动作', body.decisions.every(choice => JSON.stringify(Object.keys(choice).sort()) === JSON.stringify(['accountId', 'accountProof', 'action', 'reference', 'reviewed'])));
  check('新建均待启用且不带伪造账号', body.decisions.every(choice => choice.action === 'CREATE_PENDING' && choice.accountId === null && choice.accountProof === null && choice.reviewed === true));
  check('请求不含个人名或机构原件证据', !JSON.stringify(body).includes('合成人员') && !JSON.stringify(body).includes('approvalRoles') && !JSON.stringify(body).includes('path'));
  check('收到回执后禁止再导入', h.rootNode('commit').disabled); check('回执显示可核对的账号编号且不暴露内部人员编码', h.bound.rows[0].importChoice === '已建待启用账号 #201'); check('明示账号未启用/权限未发布', h.rootNode('progress').textContent.includes('未启用，审批权限未发布')); h.controller.destroy();
});

await scenario('409 invalidates all reviews and blocks old callbacks until refresh and full review', async () => {
  let conflict = true; const h = harness({ apiOverride: (path, options, _h, normal) => { if (options.body && conflict) throw httpError(409); return normal(path, options); } });
  await h.mount().ready; await h.reviewAll(); h.confirm(); const oldSave = h.modal.options.onOk; await h.save(); await oldSave();
  check('409不重复提交旧快照', h.posts().length, 1); check('全部复核清零', h.rootNode('progress').textContent.includes('0 / 3')); check('409旧导入锁定', h.rootNode('commit').disabled);
  h.host.closeModal(); conflict = false; h.preview.revision = 'REV-NEW'; h.preview.reviewToken = 'SYNTHETIC-NEW-TOKEN'; await h.controller.refresh();
  check('刷新后也须全部重新核对', h.rootNode('commit').disabled); await h.reviewAll(); h.confirm(); await h.save(); check('重核后使用新修订', h.posts()[1].options.body.expectedRevision, 'REV-NEW'); h.controller.destroy();
});

await scenario('400 preserves reviews for correction without automatic retry', async () => {
  let fail = true; const h = harness({ apiOverride: (path, options, _h, normal) => { if (options.body && fail) throw httpError(400); return normal(path, options); } });
  await h.mount().ready; await h.reviewAll(); h.confirm(); await h.save(); await tick(); check('400不自动重试', h.posts().length, 1); check('400核对数据保留', h.rootNode('progress').textContent.includes('3 / 3'));
  h.host.closeModal(); await h.review('teacher-row-3', 51); fail = false; h.confirm(); await h.save(); check('人工纠正后可重新提交', h.posts()[1].options.body.decisions[0].action, 'LINK_EXISTING'); h.controller.destroy();
});

for (const fail of [new TypeError('Synthetic lost response'), httpError(500)]) await scenario(`uncertain ${fail.status || 'network'} response performs GET receipt check without POST retry`, async () => {
  const h = harness({ apiOverride: (path, options, _h, normal) => { if (options.body) { const receipt = makeReceipt(h.preview, options.body.decisions); h.preview = { ...h.preview, imported: true, receipt }; throw fail; } return normal(path, options); } });
  await h.mount().ready; await h.reviewAll(); h.confirm(); await h.save(); await tick();
  check('未知结果只POST一次', h.posts().length, 1); check('未知结果随后只GET核实回执', h.calls.at(-1).path, '/organization/account-import/preview'); check('核实回执后显示已导入', h.bound.rows.every(row => row.reviewState === '已导入')); check('不能再次导入', h.rootNode('commit').disabled); h.controller.destroy();
});

await scenario('unknown POST with no receipt requires new individual reviews', async () => {
  const h = harness({ apiOverride: (path, options, _h, normal) => { if (options.body) throw new TypeError('Synthetic unknown'); return normal(path, options); } });
  await h.mount().ready; await h.reviewAll(); h.confirm(); await h.save(); await tick();
  check('无回执不自动再次POST', h.posts().length, 1); check('无回执核对全部清零', h.rootNode('progress').textContent.includes('0 / 3')); check('无回执要求重核', h.rootNode('notice').textContent.includes('重新逐人核对')); h.controller.destroy();
});

for (const status of [401, 403]) await scenario(`${status} clears all private preview data and invalidates stale callbacks`, async () => {
  const h = harness({ apiOverride: (path, options, _h, normal) => { if (options.body) throw httpError(status); return normal(path, options); } });
  await h.mount().ready; await h.reviewAll(); h.rootNode('search').value = '合成人员甲'; h.rootNode('search').dispatchEvent({ type: 'input' }); h.confirm(); const oldSave = h.modal.options.onOk; await h.save(); await oldSave();
  check('授权失效清空候选', h.bound.rows.length, 0); check('不再提交', h.posts().length, 1); check('失效后导入禁用', h.rootNode('commit').disabled); check('失效关闭自己的私有弹窗', h.modal.closed); check('授权失效同时清空含人名搜索', h.rootNode('search').value, ''); h.controller.destroy();
});

await scenario('out-of-order account lookup cannot supply proof for a later selected ID', async () => {
  const pending = deferred(); const h = harness({ apiOverride: (path, options, _h, normal) => path.endsWith('/51') ? pending.promise : normal(path, options) });
  await h.mount().ready; h.open('teacher-row-3'); h.set('action', 'LINK_EXISTING'); await h.lookup(51); await h.lookup(52);
  pending.resolve({ accountId: 51, username: 'synthetic-51', name: '合成旧查询', status: 1, role: 'viewer', proof: 'OLD', available: true }); await tick();
  check('迟到旧账号不覆盖当前卡片', h.modal.mask.querySelector('[data-import-account-result]').textContent.includes('synthetic-52'));
  h.checked(true); await h.save(); check('关联决策仍为当前账号', h.bound.rows[0].importChoice, '关联已有账号 #52'); h.controller.destroy();
});

await scenario('refresh preserves search filters but resets prior review proofs', async () => {
  const h = harness(); await h.mount().ready; await h.review('teacher-row-3'); h.rootNode('search').value = '甲'; h.rootNode('search').dispatchEvent({ type: 'input' }); await h.controller.refresh();
  check('刷新保留搜索内容', h.rootNode('search').value, '甲'); check('刷新仍显示原筛选', h.bound.rows.length, 1); check('刷新须重新核对全批', h.rootNode('progress').textContent.includes('0 / 3')); h.controller.destroy();
});

await scenario('invalid receipt never claims successful activation or publishing', async () => {
  const h = harness({ apiOverride: (path, options, _h, normal) => options.body ? makeReceipt(h.preview, options.body.decisions, { permissionsPublished: true }) : normal(path, options) });
  await h.mount().ready; await h.reviewAll(); h.confirm(); await h.save(); await tick();
  check('不接受意外发布权限的回执', h.bound.rows.every(row => row.reviewState === '未核对')); check('异常回执只查GET不重POST', h.posts().length, 1); h.controller.destroy();
});

await scenario('duplicate/malformed preview fails closed', async () => {
  const data = fixture(); data.rows[1].reference = data.rows[0].reference; const h = harness({ preview: data }); await h.mount().ready;
  check('重复引用名单不开放核对', h.bound.rows.length, 0); check('格式异常禁用导入', h.rootNode('commit').disabled); h.controller.destroy();
});

await scenario('pending submission suppresses duplicate commits and does not close a successor modal', async () => {
  const pending = deferred(); const h = harness({ apiOverride: (path, options, _h, normal) => options.body ? pending.promise : normal(path, options) });
  await h.mount().ready; await h.reviewAll(); h.confirm(); const saving = h.save(); await tick(); await h.save(); check('请求期间重复回调不提交第二次', h.posts().length, 1);
  h.host.closeModal(); h.host.openModal('合成其他弹窗', '<p>其他任务</p>', {}); const successor = h.modal, closed = h.closed;
  pending.resolve(makeReceipt(h.preview, h.posts()[0].options.body.decisions)); await saving; check('迟到结果不关闭继任弹窗', successor.closed, false); check('关闭计数保持', h.closed, closed); h.controller.destroy();
});

for (const mode of ['destroy', 'abort', 'isCurrent']) await scenario(`${mode} blocks stale review callbacks and post requests`, async () => {
  const signal = new AbortController(), h = harness({ signal: signal.signal }); await h.mount().ready; await h.reviewAll(); h.confirm(); const oldSave = h.modal.options.onOk;
  if (mode === 'destroy') h.controller.destroy(); else if (mode === 'abort') signal.abort(); else h.current = false;
  const writes = h.root.ledger.writes; await oldSave(); await h.controller.refresh(); check('生命周期结束不提交', h.posts().length, 0); check('生命周期结束不更新页面', h.root.ledger.writes, writes); h.controller.destroy();
});

await scenario('destroy ignores delayed GET and delayed POST results', async () => {
  const lateRead = deferred(), h = harness({ apiOverride: () => lateRead.promise }); const ctl = h.mount(); await tick(); ctl.destroy(); const writes = h.root.ledger.writes;
  lateRead.resolve(fixture()); await ctl.ready; check('销毁后GET不复显', h.root.ledger.writes, writes);
  const pending = deferred(), p = harness({ apiOverride: (path, options, _h, normal) => options.body ? pending.promise : normal(path, options) }); await p.mount().ready; await p.reviewAll(); p.confirm(); const saving = p.save(); await tick(); p.controller.destroy(); const counts = [p.root.ledger.writes, p.calls.length, p.toasts.length, p.closed];
  pending.resolve(makeReceipt(p.preview, p.posts()[0].options.body.decisions)); await saving; await tick(); check('销毁后POST不写页面/刷新/通知/关窗', [p.root.ledger.writes, p.calls.length, p.toasts.length, p.closed], counts);
});

await scenario('already aborted mount and losing admin role make no new private calls', async () => {
  const signal = new AbortController(); signal.abort(); const h = harness({ signal: signal.signal }); const writes = h.root.ledger.writes; await h.mount().ready; h.controller.destroy(); check('预取消无读取无写入', [h.calls.length, h.root.ledger.writes], [0, writes]);
  const p = harness(); await p.mount().ready; p.role = 'viewer'; p.open('teacher-row-3'); check('失去管理员立即清理数据', p.bound.rows.length, 0); check('角色变化不再打开私有核对框', p.modals.length, 0); p.controller.destroy();
});

await scenario('verified receipt notifies optional host once and isolates callback data', async () => {
  const h = harness(), received = []; h.host.onImported = receipt => { received.push(copy(receipt)); receipt.rows.length = 0; };
  await h.mount().ready; check('未提交候选不触发账号刷新', received.length, 0);
  await h.reviewAll(); h.confirm(); await h.save(); await tick();
  check('成功导入仅回调一次', received.length, 1); check('回调含完整持久回执', received[0].rows.length, 3);
  await h.controller.refresh(); await tick(); check('同生命周期相同回执不重复刷新', received.length, 1);
  check('宿主修改副本不影响回执展示', h.bound.rows.every(row => row.reviewState === '已导入')); h.controller.destroy();
});
await scenario('receipt GET recovery invokes optional host without another POST', async () => {
  const h = harness({ apiOverride: (path, options, _h, normal) => { if (options.body) { h.preview = { ...h.preview, imported: true, receipt: makeReceipt(h.preview, options.body.decisions) }; throw new TypeError('SYNTHETIC lost acknowledgement'); } return normal(path, options); } });
  let imported = 0; h.host.onImported = () => { imported++; }; await h.mount().ready; await h.reviewAll(); h.confirm(); await h.save(); await tick(); await tick();
  check('GET核实成功刷新宿主一次', imported, 1); check('回调不导致POST重试', h.posts().length, 1); h.controller.destroy();
});
await scenario('existing receipt triggers account refresh and callback rejection keeps import successful', async () => {
  const preview = fixture(); preview.imported = true; preview.receipt = makeReceipt(preview, preview.rows.map(row => ({ reference: row.reference, action: 'CREATE_PENDING' })));
  const h = harness({ preview }); let imported = 0; h.host.onImported = async () => { imported++; throw Error('SYNTHETIC local refresh unavailable'); };
  await h.mount().ready; await tick(); check('首次读取持久回执回调一次', imported, 1);
  check('局部账号刷新失败不回退为未导入', h.bound.rows.every(row => row.reviewState === '已导入'));
  check('局部刷新失败不重新POST', h.posts().length, 0); check('回调失败有明确局部提示', h.toasts.some(row => row.message.includes('账号列表暂时无法更新'))); h.controller.destroy();
});
await scenario('destroyed lifecycle never notifies host about late receipt', async () => {
  const pending = deferred(), h = harness({ apiOverride: (path, options, _h, normal) => options.body ? pending.promise : normal(path, options) }); let imported = 0; h.host.onImported = () => { imported++; };
  await h.mount().ready; await h.reviewAll(); h.confirm(); const saving = h.save(); h.controller.destroy(); pending.resolve(makeReceipt(h.preview, h.posts()[0].options.body.decisions)); await saving; await tick();
  check('销毁后晚到回执不刷新宿主', imported, 0);
});

console.log(`M01 import UI: ${scenarios} scenarios; ${assertions} assertions passed; ${failures.length} scenarios failed.`);
if (failures.length) process.exitCode = 1;
