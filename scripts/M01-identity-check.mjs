// 零依赖：验证合成规则、内存交互和挂载生命周期；真实布局由浏览器另验。
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const fixturePath = new URL('../web/modules/identity/fixture.js', import.meta.url);
const indexPath = new URL('../web/modules/identity/index.js', import.meta.url);
const dataModule = source => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`;
const fixtureURL = dataModule(await readFile(fixturePath, 'utf8'));
const { demoConfiguration: config, createDemoPeople, evaluateDemoAccess, validateDemoPerson, resolveDemoScope, DEMO_VERSION } = await import(fixtureURL);
const indexSource = await readFile(indexPath, 'utf8');
const { mount } = await import(dataModule(indexSource.replace("'./fixture.js'", JSON.stringify(fixtureURL))));
let checks = 0;
function check(label, value, expected = true) { assert.deepEqual(value, expected, label); checks++; }
const people = createDemoPeople();
const decision = (personCode, organizationCode, action) => evaluateDemoAccess(people, config, { personCode, organizationCode, action });
check('演示版本', DEMO_VERSION, 'demo-m01-v1');
check('正式规则未配置', config.officialRuleVersion, null);
check('每次获取独立内存数据', createDemoPeople() !== people);
check('数字记录 ID 与编码分开', typeof people[0].recordId === 'number' && typeof people[0].personCode === 'string');
check('填报可以看所属机构', decision('PERSON-001', 'ORG-001', 'view').allowed);
check('填报不能看另一机构', decision('PERSON-001', 'ORG-002', 'view').allowed, false);
check('填报没有可办权限', decision('PERSON-001', 'ORG-001', 'handle').allowed, false);
check('填报没有导出权限', decision('PERSON-001', 'ORG-001', 'export').allowed, false);
check('负责人可办负责机构', decision('PERSON-002', 'ORG-001', 'handle').allowed);
check('负责人不可办非负责机构', decision('PERSON-002', 'ORG-002', 'handle').allowed, false);
check('负责人不继承总部权限', decision('PERSON-002', 'ORG-000', 'view').allowed, false);
check('负责人导出缺失', decision('PERSON-002', 'ORG-001', 'export').allowed, false);
check('BP可办机构乙', decision('PERSON-003', 'ORG-002', 'handle').allowed);
check('BP不因所属总部获得总部权限', decision('PERSON-003', 'ORG-000', 'view').allowed, false);
check('培训可导出指定机构', decision('PERSON-004', 'ORG-002', 'export').allowed);
check('培训不继承总部权限', decision('PERSON-004', 'ORG-000', 'handle').allowed, false);
check('停用人员不允许', decision('PERSON-005', 'ORG-002', 'view').allowed, false);
check('缺失权限岗位不允许', decision('PERSON-006', 'ORG-002', 'view').allowed, false);
check('缺失权限说明未配置', decision('PERSON-006', 'ORG-002', 'view').title.includes('未配置'));
check('未知人员不允许', decision('PERSON-999', 'ORG-001', 'view').allowed, false);
check('未知机构不允许', decision('PERSON-004', 'ORG-999', 'view').allowed, false);
check('未知操作不允许', decision('PERSON-004', 'ORG-001', 'delete').allowed, false);
check('未知范围不推定', resolveDemoScope(people[0], { scope: 'UNKNOWN' }), null);
check('负责人范围按人员明确负责机构解析', resolveDemoScope(people[1], { scope: 'RESPONSIBLE_ORGS' }), ['ORG-001']);
const validate = (index, patch) => validateDemoPerson({ ...people[index], ...patch }, people, config);
check('基础样例关系有效', validate(0, {}), []);
check('填报缺负责人拒绝', validate(0, { managerCode: null }).some(error => error.includes('必须配置负责人')));
check('填报缺BP拒绝', validate(0, { bpCode: null }).some(error => error.includes('必须配置BP')));
check('错误目标岗位拒绝', validate(0, { managerCode: 'PERSON-003' }).some(error => error.includes('目标岗位')));
check('未知机构拒绝', validate(0, { organizationCode: 'ORG-999' }).some(error => error.includes('所属机构')));
check('负责机构重复拒绝', validate(1, { responsibleOrganizationCodes: ['ORG-001', 'ORG-001'] }).some(error => error.includes('重复')));
check('自引用拒绝', validate(1, { managerCode: 'PERSON-002' }).some(error => error.includes('本人')));
check('停用被引用负责人拒绝', validate(1, { active: false }).some(error => error.includes('不可用')));
check('更改负责人岗位保护全体关系', validate(1, { roleCode: 'ROLE-TRAINING' }).some(error => error.startsWith('PERSON-001') && error.includes('目标岗位')));
check('清空负责机构保护全体关系', validate(1, { responsibleOrganizationCodes: [] }).some(error => error.startsWith('PERSON-001') && error.includes('未明确负责')));
check('恢复无引用停用人员有效', validate(4, { active: true }), []);
check('修改不可变编码拒绝', validate(0, { personCode: 'PERSON-999' }).some(error => error.includes('不可')));
check('未知负责人拒绝', validate(0, { managerCode: 'PERSON-999' }).some(error => error.includes('未配置')));
const cyclicPeople = createDemoPeople();
cyclicPeople[3] = { ...cyclicPeople[3], roleCode: 'ROLE-LEADER', organizationCode: 'ORG-001', responsibleOrganizationCodes: ['ORG-001'], managerCode: 'PERSON-002' };
check('两人负责人循环拒绝', validateDemoPerson({ ...cyclicPeople[1], managerCode: 'PERSON-004' }, cyclicPeople, config).some(error => error.includes('循环')));

// 最小 DOM 替身只验证模块行为，不冒充浏览器渲染。
class FakeElement {
  constructor(owner, attributes = {}) { this.owner = owner; this.attributes = { ...attributes }; this.dataset = {}; this.hidden = false; this.value = ''; this.listeners = new Map(); this.writes = 0; this._html = ''; this.textContent = ''; for (const [name, value] of Object.entries(attributes)) if (name.startsWith('data-')) this.dataset[name.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] = value; }
  set innerHTML(value) { this._html = value; this.writes++; }
  get innerHTML() { return this._html; }
  replaceChildren() { this.innerHTML = ''; }
  addEventListener(name, handler) { this.listeners.set(name, handler); }
  removeEventListener(name, handler) { if (this.listeners.get(name) === handler) this.listeners.delete(name); }
  setAttribute(name, value) { this.attributes[name] = value; }
  hasAttribute(name) { return Object.hasOwn(this.attributes, name); }
  closest(selector) { return selector === 'button' ? this : null; }
  matches(selector) { return selector === '[data-edit-form]' && this.hasAttribute('data-edit-form'); }
  focus() { this.focused = true; }
  scrollIntoView() {}
}
class FakeRoot extends FakeElement {
  constructor() { super(null); this.owner = this; this.elements = new Map(); }
  querySelector(selector) { if (!this.elements.has(selector)) this.elements.set(selector, new FakeElement(this)); return this.elements.get(selector); }
  querySelectorAll() { return []; }
  contains(element) { return element.owner === this; }
  emit(name, target) { this.listeners.get(name)?.({ target, preventDefault() {} }); }
}
const oldElement = globalThis.Element;
const oldFormData = globalThis.FormData;
globalThis.Element = FakeElement;
globalThis.FormData = class { constructor(form) { this.values = form.values; } get(key) { const value = this.values[key]; return Array.isArray(value) ? value[0] ?? null : value ?? null; } getAll(key) { const value = this.values[key]; return Array.isArray(value) ? value : value == null ? [] : [value]; } };
try {
  let requests = 0; let notifications = 0;
  const context = mode => ({ mode, request() { requests++; throw new Error('不可请求网络'); }, get user() { throw new Error('不可将宿主身份作为演示身份'); }, notify() { notifications++; } });
  const liveRoot = new FakeRoot();
  const liveCleanup = await mount(liveRoot, context('live'));
  check('live明确未接入', liveRoot.innerHTML.includes('正式服务尚未接入'));
  check('live不渲染合成人员', !liveRoot.innerHTML.includes('PERSON-001'));
  liveCleanup();
  check('live清理根元素', liveRoot.innerHTML, '');
  check('live移除全部监听', liveRoot.listeners.size, 0);
  const preAborted = new AbortController(); preAborted.abort();
  const untouched = new FakeRoot(); untouched.innerHTML = '宿主原有内容';
  const abortedContext = context('live'); abortedContext.signal = preAborted.signal;
  const abortedCleanup = await mount(untouched, abortedContext);
  abortedCleanup();
  check('已取消的挂载及其清理不写根元素', untouched.innerHTML, '宿主原有内容');

  const root = new FakeRoot(); const controller = new AbortController();
  const demoContext = context('demo'); demoContext.signal = controller.signal;
  const cleanup = await mount(root, demoContext);
  check('demo明确合成数据', root.innerHTML.includes('合成数据 · 本地演示'));
  check('demo初始显示六名人员', (root.querySelector('[data-people]').innerHTML.match(/<article/g) || []).length, 6);
  check('demo初始范围允许', root.querySelector('[data-access-result]').innerHTML.includes('合成配置允许'));
  check('初始主体控件与判断一致', root.querySelector('[data-simulation="personCode"]').value, 'PERSON-001');
  check('初始目标机构控件与判断一致', root.querySelector('[data-simulation="organizationCode"]').value, 'ORG-001');
  check('初始操作控件与判断一致', root.querySelector('[data-simulation="action"]').value, 'view');
  const search = new FakeElement(root, { 'data-filter': 'query' }); search.value = 'person-003'; root.emit('input', search);
  check('编码搜索不区分大小写', (root.querySelector('[data-people]').innerHTML.match(/<article/g) || []).length, 1);
  search.value = 'PERSON-999'; root.emit('input', search);
  check('无匹配显示空态', root.querySelector('[data-people]').innerHTML.includes('没有匹配'));
  search.value = ''; root.emit('input', search);
  const sim = new FakeElement(root, { 'data-simulation': 'personCode' }); sim.value = 'PERSON-006'; root.emit('change', sim);
  check('切换主体仅模拟缺失权限', root.querySelector('[data-access-result]').innerHTML.includes('未配置 · 不允许'));
  root.emit('click', new FakeElement(root, { 'data-status': '101' }));
  check('停用先打开未保存表单', root.querySelector('[data-editor]').innerHTML.includes('尚未保存'));
  check('停用未保存前不更改卡片', root.querySelector('[data-people]').innerHTML.includes('data-status="101">停用'));
  root.emit('click', new FakeElement(root, { 'data-cancel': '' }));
  check('取消清空编辑区', root.querySelector('[data-editor]').innerHTML, '');
  check('取消后卡片状态不变', root.querySelector('[data-people]').innerHTML.includes('data-status="101">停用'));
  root.emit('click', new FakeElement(root, { 'data-edit': '101' }));
  root.emit('click', new FakeElement(root, { 'data-edit': '102' }));
  check('未保存编辑不能悄然丢失', root.querySelector('[data-message]').textContent.includes('先保存或取消'));
  const form = new FakeElement(root, { 'data-edit-form': '' });
  form.values = { organizationCode: 'ORG-001', roleCode: 'ROLE-STAFF', responsibleOrganizationCodes: [], managerCode: '', bpCode: 'PERSON-003', active: 'true' };
  root.emit('submit', form);
  check('无效保存反馈必填校验', root.querySelector('[data-errors]').innerHTML.includes('必须配置负责人'));
  check('无效保存保留表单', root.querySelector('[data-editor]').innerHTML.includes('data-edit-form'));
  form.values.managerCode = 'PERSON-002'; form.values.active = 'false'; root.emit('submit', form);
  check('有效保存关闭表单', root.querySelector('[data-editor]').innerHTML, '');
  check('有效保存更新内存状态', root.querySelector('[data-people]').innerHTML.includes('data-status="101">恢复'));
  check('保存通知说明内存性质', root.querySelector('[data-message]').textContent.includes('页面内存'));
  check('demo与live均不发业务网络请求', requests, 0);
  check('操作正常产生宿主通知', notifications > 0);
  const capturedInput = root.listeners.get('input');
  controller.abort();
  check('取消信号清除界面', root.innerHTML, '');
  check('取消信号移除全部监听', root.listeners.size, 0);
  const writesAfterAbort = root.writes;
  search.value = 'PERSON-001'; capturedInput({ target: search }); cleanup();
  check('销毁后回调和重复清理不更新', root.writes, writesAfterAbort);
  const fresh = new FakeRoot(); const freshCleanup = await mount(fresh, context('demo'));
  check('重新挂载恢复合成初始状态', fresh.querySelector('[data-people]').innerHTML.includes('data-status="101">停用'));
  freshCleanup();
} finally { globalThis.Element = oldElement; globalThis.FormData = oldFormData; }
console.log(`M01 identity checks passed: ${checks} assertions (fixture + memory interactions + lifecycle).`);
