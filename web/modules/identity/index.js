import { DEMO_VERSION, demoConfiguration, createDemoPeople, evaluateDemoAccess, validateDemoPerson } from './fixture.js';

const actionLabels = { view: '可看', handle: '可办', export: '可导出' };
const scopeLabels = { OWN_ORG: '本人所属机构', RESPONSIBLE_ORGS: '明确负责机构', NAMED_ORGS: '指定机构' };
const grantMarkup = rule => rule ? `<span class="yx-identity__scope">${scopeLabels[rule.scope] || '范围未配置'}</span><small>${escapeHtml(rule.scope)}</small>${rule.scope === 'NAMED_ORGS' ? rule.organizations.map(code => `<span class="yx-identity__code">${escapeHtml(code)}</span>`).join(' ') : ''}` : '<span class="yx-identity__unset">未配置 · 不允许</span>';
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);

export async function mount(root, context = {}) {
  if (!root || typeof root.replaceChildren !== 'function') throw new TypeError('M01 mount 需要有效的根元素。');
  let disposed = false;
  const cleanup = () => {
    if (disposed) return;
    disposed = true;
    root.removeEventListener('click', onClick);
    root.removeEventListener('input', onInput);
    root.removeEventListener('change', onChange);
    root.removeEventListener('submit', onSubmit);
    context.signal?.removeEventListener('abort', cleanup);
    root.replaceChildren();
  };
  if (context.signal?.aborted) { disposed = true; return cleanup; }
  const state = { people: createDemoPeople(), query: '', organization: '', status: '', editing: null, tab: 'people', simulation: { personCode: 'PERSON-001', organizationCode: 'ORG-001', action: 'view' } };
  const canDemo = context.mode === 'demo';
  const find = selector => root.querySelector(selector);
  const roleLabel = code => demoConfiguration.roles.find(item => item.code === code)?.label || '未配置角色';
  const orgLabel = code => demoConfiguration.organizations.find(item => item.code === code)?.label || '未配置机构';
  const badge = (text, tone = '') => `<span class="yx-identity__badge ${tone ? `yx-identity__badge--${tone}` : ''}">${escapeHtml(text)}</span>`;

  root.innerHTML = `<section class="yx-identity" aria-label="M01 基础数据与账号权限">
    <header class="yx-identity__header"><div><p class="yx-identity__eyebrow">M01 / 基础数据与账号权限</p><h1>组织与人员</h1><p class="yx-identity__subtitle">看清关系，逐项配置权限。</p></div>${badge(canDemo ? '合成数据 · 本地演示' : 'LIVE · 未接入', canDemo ? 'demo' : 'muted')}</header>
    ${canDemo ? `<aside class="yx-identity__notice"><strong>这是一份可操作的合成样例</strong><p>编辑仅保存在当前页面内存，刷新即重置。模拟主体不是登录身份，权限结果不作后端鉴权依据；正式判断以后端校验为准。</p><div class="yx-identity__ruleline">基础演示配置 ${DEMO_VERSION} · 含 UI 缺失权限补充<span>正式编码、关系与权限规则：未配置</span></div></aside>
    <div class="yx-identity__summary" data-summary></div>
    <nav class="yx-identity__tabs" aria-label="模块视图"><button type="button" data-tab="people" aria-pressed="true">人员维护</button><button type="button" data-tab="access" aria-pressed="false">权限演示</button></nav>
    <div class="yx-identity__message" data-message role="status" aria-live="polite" hidden></div>
    <section data-panel="people" aria-label="人员维护">
      <div class="yx-identity__section-title"><h2>编码人员</h2><span data-result-count></span></div>
      <div class="yx-identity__filters"><label class="yx-identity__search">搜索人员或角色编码<input type="search" data-filter="query" placeholder="例如 PERSON-001" autocomplete="off"></label><label>所属机构<select data-filter="organization"><option value="">全部机构</option>${demoConfiguration.organizations.map(item => `<option value="${item.code}">${item.code}</option>`).join('')}</select></label><label>启停状态<select data-filter="status"><option value="">全部状态</option><option value="active">启用</option><option value="inactive">停用</option></select></label></div>
      <div data-editor></div><div class="yx-identity__cards" data-people></div>
      <p class="yx-identity__footnote">人员编码与数字记录 ID 分开保存；样例编码仅用于演示，不代表正式编码规则。未填写的关系会显示“未配置”。</p>
    </section>
    <section data-panel="access" aria-label="权限演示" hidden>
      <div class="yx-identity__section-title"><h2>选择合成场景</h2>${badge('模拟 · 非认证', 'demo')}</div>
      <p class="yx-identity__muted">选择下方人员只切换演示主体，不创建或替换当前会话。已知人员编码不能证明身份。</p>
      <div class="yx-identity__sim-controls"><label>演示主体<select data-simulation="personCode"></select></label><label>目标机构<select data-simulation="organizationCode">${demoConfiguration.organizations.map(item => `<option value="${item.code}">${item.code}${item.active ? '' : ' · 停用'}</option>`).join('')}<option value="ORG-UNCONFIGURED">未配置机构</option></select></label><label>操作<select data-simulation="action">${Object.entries(actionLabels).map(([action, label]) => `<option value="${action}">${label} · ${action}</option>`).join('')}</select></label></div>
      <div data-access-result aria-live="polite"></div>
      <div class="yx-identity__section-title"><h2>岗位与机构范围</h2><span>training.record · 合成配置</span></div>
      <div class="yx-identity__roles">${demoConfiguration.roles.map(role => `<article class="yx-identity__role"><div><h3>${escapeHtml(role.label)}</h3><code>${role.code}</code></div><dl>${Object.entries(actionLabels).map(([action, label]) => `<div><dt>${label}<small>${action}</small></dt><dd>${grantMarkup(role.grants[action])}</dd></div>`).join('')}</dl></article>`).join('')}</div>
      <aside class="yx-identity__rules"><strong>演示判断顺序</strong><p>人员、所属机构、目标机构和岗位均需启用。仅演示 training.record：单岗位对应操作先有明确授权，再按所属机构（OWN_ORG）、负责机构（RESPONSIBLE_ORGS）或列明机构（NAMED_ORGS）解析范围。范围为空或权限缺失均不允许；上下级机构不自动继承权限，负责人/BP 关系不自动授予权限。</p><p>关系维护使用显式合成配置：填报岗位必填负责人/BP；其他岗位可空。填写后目标岗位分别须为 ROLE-LEADER / ROLE-BP，且明确负责人员所属机构；启用人员不可引用停用目标，不允许自引用或负责人循环。保存前检查整份临时人员集，防止破坏他人关系。正式制度仍待确认。</p></aside>
    </section>` : `<section class="yx-identity__unconnected"><div class="yx-identity__empty-mark" aria-hidden="true">○</div><h2>正式服务尚未接入</h2><p>本轮未提供后端路由或统一会话接入。此视图没有读取、保存或推断正式人员与权限。</p><dl><div><dt>后端接口</dt><dd>未接入</dd></div><div><dt>正式规则版本</dt><dd>未配置</dd></div><div><dt>身份验证</dt><dd>等待宿主统一会话</dd></div></dl></section>`}
    <footer class="yx-identity__footer"><span>研序 · M01</span><span>${canDemo ? '合成演示 / 无业务网络请求' : '等待总控接入'}</span></footer>
  </section>`;

  function notify(message) {
    if (disposed) return;
    const target = find('[data-message]');
    if (target) { target.hidden = false; target.textContent = message; }
    // 宿主可选择显示通知；本模块不调用 request 或 navigate。
    if (typeof context.notify === 'function') context.notify(message);
  }

  function renderSummary() {
    if (disposed || !canDemo) return;
    find('[data-summary]').innerHTML = `<div><strong>${state.people.length}</strong><span>合成人员</span></div><div><strong>${state.people.filter(item => item.active).length}</strong><span>启用中</span></div><div><strong>${demoConfiguration.organizations.length}</strong><span>配置机构</span></div>`;
  }

  function renderPeople() {
    if (disposed || !canDemo) return;
    const query = state.query.trim().toUpperCase();
    const people = state.people.filter(person => (!query || `${person.personCode} ${person.roleCode}`.includes(query)) && (!state.organization || person.organizationCode === state.organization) && (!state.status || person.active === (state.status === 'active')));
    find('[data-result-count]').textContent = `${people.length} / ${state.people.length} 条`;
    find('[data-people]').innerHTML = people.length ? people.map(person => `<article class="yx-identity__person"><header><div><h3>${person.personCode}</h3><span class="yx-identity__record">记录 ID ${person.recordId}</span></div>${badge(person.active ? '启用' : '停用', person.active ? 'active' : 'muted')}</header><div class="yx-identity__person-role"><span>${escapeHtml(roleLabel(person.roleCode))}</span><code>${person.roleCode}</code></div><dl><div><dt>所属机构</dt><dd><span class="yx-identity__code">${person.organizationCode}</span><small>${escapeHtml(orgLabel(person.organizationCode))}</small></dd></div><div><dt>负责机构</dt><dd>${person.responsibleOrganizationCodes.length ? person.responsibleOrganizationCodes.map(code => `<span class="yx-identity__code">${code}</span>`).join(' ') : '<span class="yx-identity__unset">未配置</span>'}</dd></div><div><dt>负责人</dt><dd>${person.managerCode ? `<span class="yx-identity__code">${person.managerCode}</span>` : '<span class="yx-identity__unset">未配置</span>'}</dd></div><div><dt>BP</dt><dd>${person.bpCode ? `<span class="yx-identity__code">${person.bpCode}</span>` : '<span class="yx-identity__unset">未配置</span>'}</dd></div></dl><footer><button type="button" data-edit="${person.recordId}">编辑关系</button><button type="button" class="yx-identity__button-subtle" data-status="${person.recordId}">${person.active ? '停用' : '恢复'}</button></footer></article>`).join('') : '<div class="yx-identity__empty"><strong>没有匹配的合成人员</strong><p>请调整编码、机构或启停筛选。</p><button type="button" data-reset-filters>清空筛选</button></div>';
  }

  function renderSimulation() {
    if (disposed || !canDemo) return;
    const select = find('[data-simulation="personCode"]');
    select.innerHTML = state.people.map(person => `<option value="${person.personCode}">${person.personCode}${person.active ? '' : ' · 停用'}</option>`).join('');
    select.value = state.simulation.personCode;
    find('[data-simulation="organizationCode"]').value = state.simulation.organizationCode;
    find('[data-simulation="action"]').value = state.simulation.action;
    const result = evaluateDemoAccess(state.people, demoConfiguration, state.simulation);
    const person = state.people.find(item => item.personCode === state.simulation.personCode);
    find('[data-access-result]').innerHTML = `<div class="yx-identity__decision ${result.allowed ? 'yx-identity__decision--allow' : 'yx-identity__decision--deny'}"><span class="yx-identity__eyebrow">仅对当前合成场景的判断</span><h3>${escapeHtml(result.title)}</h3><p>${escapeHtml(result.reason)}</p><div>${escapeHtml(person?.personCode || '未配置')}<span> → </span>${escapeHtml(person?.roleCode || '未配置')}<span> → </span>${escapeHtml(state.simulation.organizationCode)}</div><small>不触发查看、办理或导出，也不对正式接口授予权限。</small></div>`;
  }

  function openEditor(recordId, changeStatus = false) {
    if (state.editing) { notify('当前编辑尚未保存，请先保存或取消。'); find('[data-edit-form]').scrollIntoView({ block: 'nearest', behavior: 'auto' }); return; }
    const person = state.people.find(item => item.recordId === recordId);
    if (!person || disposed) return;
    state.editing = { ...person, responsibleOrganizationCodes: [...person.responsibleOrganizationCodes], active: changeStatus ? !person.active : person.active };
    const selected = (value, actual) => value === actual ? ' selected' : '';
    const refOptions = current => `<option value="">未配置</option>${state.people.map(item => `<option value="${item.personCode}"${selected(item.personCode, current)}>${item.personCode}${item.active ? '' : ' · 停用'}</option>`).join('')}`;
    find('[data-editor]').innerHTML = `<form class="yx-identity__editor" data-edit-form novalidate><div class="yx-identity__section-title"><h2>${changeStatus ? person.active ? '停用合成人员' : '恢复合成人员' : '编辑合成关系'}</h2>${badge('尚未保存', 'demo')}</div><p class="yx-identity__editor-id"><strong>${person.personCode}</strong><span>记录 ID ${person.recordId} · 编码不可修改</span></p><div class="yx-identity__form-grid"><label>所属机构<select name="organizationCode"><option value="">请选择机构</option>${demoConfiguration.organizations.map(item => `<option value="${item.code}"${selected(item.code, person.organizationCode)}>${item.code} · ${escapeHtml(item.label)}</option>`).join('')}</select></label><label>角色<select name="roleCode"><option value="">请选择岗位</option>${demoConfiguration.roles.map(item => `<option value="${item.code}"${selected(item.code, person.roleCode)}>${item.code} · ${escapeHtml(item.label)}</option>`).join('')}</select></label><fieldset><legend>负责机构 <span>可多选；空选即未配置</span></legend><div class="yx-identity__checkboxes">${demoConfiguration.organizations.map(item => `<label><input type="checkbox" name="responsibleOrganizationCodes" value="${item.code}"${person.responsibleOrganizationCodes.includes(item.code) ? ' checked' : ''}>${item.code}${item.active ? '' : ' · 停用'}</label>`).join('')}</div></fieldset><label>负责人<select name="managerCode">${refOptions(person.managerCode)}</select></label><label>BP<select name="bpCode">${refOptions(person.bpCode)}</select></label><label>启停状态<select name="active"><option value="true"${state.editing.active ? ' selected' : ''}>启用</option><option value="false"${!state.editing.active ? ' selected' : ''}>停用</option></select></label></div><div class="yx-identity__errors" data-errors role="alert" hidden></div><p class="yx-identity__muted">填报岗位必须配置负责人/BP；其他岗位可空。保存前校验所有合成人员的目标岗位、负责范围、启停状态与循环关系。</p><div class="yx-identity__editor-actions"><button class="yx-identity__button-primary" type="submit">保存演示更改</button><button type="button" data-cancel>取消</button></div></form>`;
    find('[name="organizationCode"]').focus();
    find('[data-editor]').scrollIntoView({ block: 'nearest', behavior: 'auto' });
  }

  function closeEditor() { state.editing = null; find('[data-editor]').replaceChildren(); }

  function onClick(event) {
    if (disposed || !canDemo || !(event.target instanceof Element)) return;
    const button = event.target.closest('button');
    if (!button || !root.contains(button)) return;
    if (button.hasAttribute('data-tab')) {
      state.tab = button.dataset.tab;
      root.querySelectorAll('[data-tab]').forEach(item => item.setAttribute('aria-pressed', String(item.dataset.tab === state.tab)));
      root.querySelectorAll('[data-panel]').forEach(item => { item.hidden = item.dataset.panel !== state.tab; });
    } else if (button.hasAttribute('data-edit')) openEditor(Number(button.dataset.edit));
    else if (button.hasAttribute('data-status')) openEditor(Number(button.dataset.status), true);
    else if (button.hasAttribute('data-cancel')) { closeEditor(); notify('已取消；合成数据未更改。'); }
    else if (button.hasAttribute('data-reset-filters')) {
      state.query = ''; state.organization = ''; state.status = '';
      root.querySelectorAll('[data-filter]').forEach(item => { item.value = ''; });
      renderPeople();
    }
  }

  function onInput(event) {
    if (disposed || !canDemo) return;
    if (event.target?.dataset?.filter === 'query') { state.query = event.target.value; renderPeople(); }
  }

  function onChange(event) {
    if (disposed || !canDemo) return;
    const filter = event.target?.dataset?.filter;
    if (filter && Object.hasOwn(state, filter)) { state[filter] = event.target.value; renderPeople(); }
    const simulation = event.target?.dataset?.simulation;
    if (simulation && Object.hasOwn(state.simulation, simulation)) { state.simulation[simulation] = event.target.value; renderSimulation(); }
  }

  function onSubmit(event) {
    if (disposed || !canDemo || !event.target?.matches?.('[data-edit-form]')) return;
    event.preventDefault();
    if (!state.editing) return;
    const data = new FormData(event.target);
    const candidate = { ...state.editing, organizationCode: data.get('organizationCode'), responsibleOrganizationCodes: data.getAll('responsibleOrganizationCodes'), roleCode: data.get('roleCode'), managerCode: data.get('managerCode') || null, bpCode: data.get('bpCode') || null, active: data.get('active') === 'true' };
    const errors = validateDemoPerson(candidate, state.people, demoConfiguration);
    if (errors.length) {
      const target = find('[data-errors]'); target.hidden = false;
      target.innerHTML = `<strong>尚未保存，请检查：</strong><ul>${errors.map(error => `<li>${escapeHtml(error)}</li>`).join('')}</ul>`;
      target.setAttribute('tabindex', '-1'); target.focus(); return;
    }
    state.people = state.people.map(item => item.recordId === candidate.recordId ? candidate : item);
    closeEditor(); renderSummary(); renderPeople(); renderSimulation();
    notify(`${candidate.personCode} 已保存到页面内存；${candidate.active ? '当前启用' : '当前停用'}。刷新后恢复合成初始数据。`);
  }

  if (canDemo) { renderSummary(); renderPeople(); renderSimulation(); }
  root.addEventListener('click', onClick);
  root.addEventListener('input', onInput);
  root.addEventListener('change', onChange);
  root.addEventListener('submit', onSubmit);
  context.signal?.addEventListener('abort', cleanup, { once: true });
  return cleanup;
}
