// Host adapter for the existing users table. No theme, navigation, identity seeds or role grants.
const copy = value => JSON.parse(JSON.stringify(value));
const canonical = value => JSON.stringify(value, (_, item) => item && typeof item === 'object' && !Array.isArray(item)
  ? Object.fromEntries(Object.keys(item).sort().map(key => [key, item[key]])) : item);
const code = value => typeof value === 'string' && value.length > 0 && value.length <= 128 && value.trim() === value;
const accountId = value => typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
const enabledAccount = user => user?.status === 1 || user?.status === '1';
const optionalCode = value => value === null || code(value);

// GET is already validated by the server. This guards missing/incompatible responses;
// Java regexes and business authorization remain exclusively server-side.
function configurationProblem(result) {
  if (!result || !result.configuration) return '人员和岗位关系尚未配齐，可浏览账号，暂不能维护绑定。';
  const c = result.configuration;
  const arrays = ['roleCodes', 'organizations', 'people', 'relations', 'accountBindings', 'grants'];
  if (!code(result.version) || c.version !== result.version || !c.codeRules ||
      !['organizationPattern', 'personPattern', 'rolePattern'].every(k => typeof c.codeRules[k] === 'string' && c.codeRules[k]) ||
      !arrays.every(k => Array.isArray(c[k])) || !c.people.length || !c.organizations.length || !c.roleCodes.length) {
    return '人员和岗位配置不完整，暂不能维护绑定；请先补齐关系。';
  }
  const orgs = new Set(c.organizations.map(o => o?.organizationCode));
  const roles = new Set(c.roleCodes);
  const people = new Set(c.people.map(p => p?.personCode));
  const relationRule = r => r && typeof r.required === 'boolean' && typeof r.targetMustCoverOrganization === 'boolean' &&
    typeof r.allowSelf === 'boolean' && Array.isArray(r.allowedTargetRoles) && r.allowedTargetRoles.every(role => roles.has(role));
  if (orgs.size !== c.organizations.length || people.size !== c.people.length || roles.size !== c.roleCodes.length ||
      !c.roleCodes.every(code) || !c.organizations.every(o => o && code(o.organizationCode) && optionalCode(o.parentOrganizationCode) &&
        (o.parentOrganizationCode === null || orgs.has(o.parentOrganizationCode)) && typeof o.enabled === 'boolean') ||
      !c.people.every(p => p && code(p.personCode) && orgs.has(p.organizationCode) && typeof p.enabled === 'boolean' &&
        Array.isArray(p.roleCodes) && p.roleCodes.length > 0 && p.roleCodes.every(r => roles.has(r)) &&
        Array.isArray(p.responsibleOrganizationCodes) && p.responsibleOrganizationCodes.every(o => orgs.has(o)) &&
        ['leaderPersonCode', 'bpPersonCode'].every(k => optionalCode(p[k]) && (p[k] === null || people.has(p[k])))) ||
      !c.roleCodes.every(role => c.relations.filter(r => r?.roleCode === role && relationRule(r.leader) && relationRule(r.bp)).length === 1) ||
      !c.people.every(p => p.roleCodes.every(role => {
        const r = c.relations.find(r => r.roleCode === role);
        return (!r.leader.required || p.leaderPersonCode !== null) && (!r.bp.required || p.bpPersonCode !== null);
      }))) return '人员、机构或负责人/BP关系未配齐，暂不能维护绑定。';
  const accounts = new Set(), boundPeople = new Set();
  for (const b of c.accountBindings) {
    if (!b || !accountId(b.accountId) || !people.has(b.personCode) || typeof b.enabled !== 'boolean' ||
        accounts.has(b.accountId) || boundPeople.has(b.personCode)) return '现有账号绑定有缺项或重复，请先核对配置。';
    accounts.add(b.accountId); boundPeople.add(b.personCode);
  }
  if (c.people.some(p => Object.hasOwn(p, 'responsibleOrganizationsByRole') && (
    !p.responsibleOrganizationsByRole || Array.isArray(p.responsibleOrganizationsByRole) ||
    Object.keys(p.responsibleOrganizationsByRole).length !== p.roleCodes.length ||
    !p.roleCodes.every(role => Array.isArray(p.responsibleOrganizationsByRole[role]) &&
      p.responsibleOrganizationsByRole[role].every(org => orgs.has(org)))))) return '岗位负责范围未核实，暂不能维护绑定。';
  if (Object.hasOwn(c, 'combinedApprovals') && (!Array.isArray(c.combinedApprovals) || c.combinedApprovals.some(a =>
    !a || !orgs.has(a.organizationCode) || !people.has(a.personCode) || !roles.has(a.leaderRoleCode) ||
    !roles.has(a.bpRoleCode) || a.leaderRoleCode === a.bpRoleCode || !code(a.evidenceRef)))) return '兼任审批依据未核实，暂不能维护绑定。';
  return '';
}

function organizationEnabled(configuration, orgCode) {
  const seen = new Set();
  while (orgCode !== null) {
    if (seen.has(orgCode)) return false;
    seen.add(orgCode);
    const org = configuration.organizations.find(o => o.organizationCode === orgCode);
    if (!org || !org.enabled) return false;
    orgCode = org.parentOrganizationCode;
  }
  return true;
}

function nextVersion() {
  // A release identifier, not a credential. The server rejects all reused versions.
  if (!globalThis.crypto?.randomUUID) throw new Error('当前浏览器无法生成保存标识，请使用支持安全连接的浏览器。');
  return `binding-${globalThis.crypto.randomUUID()}`;
}

/**
 * Inline adapter for the original #tbl slot (not a page).
 * host: api, getUser, openModal, closeModal, renderForm, collectForm,
 *       renderTable, bindTableActions, toast; optional columns, bindingColumns, actions,
 *       onLoaded(users), isCurrent(), signal.
 * api uses original relative paths and returns unpacked data.
 * Returns immediately: {ready: Promise, refresh(): Promise, destroy()}.
 */
export function mountAccountBindings(root, host) {
  for (const name of ['api', 'getUser', 'openModal', 'closeModal', 'renderForm', 'collectForm', 'renderTable', 'bindTableActions', 'toast']) {
    if (typeof host?.[name] !== 'function') throw new TypeError(`Missing host function: ${name}`);
  }
  let alive = true, generation = 0, requestController = null, snapshot = null, users = [], editable = false;
  let ownedModal = null, saving = false;
  const lifetime = new AbortController();
  const active = () => alive && !host.signal?.aborted && (host.isCurrent?.() ?? true);
  const admin = () => host.getUser()?.role === 'admin'; // UI gate only; server revalidates actual admin.
  const notify = (message, error = false) => { if (active()) host.toast(message, error); };
  const say = message => { if (active()) root.querySelector('[data-binding-notice]').textContent = message; };
  const current = modal => active() && ownedModal === modal && !modal.closed && modal.mask?.isConnected !== false;
  const controller = { ready: null, refresh, destroy };

  function shell() {
    root.innerHTML = '<div class="toolbar"><button type="button" class="btn gray" data-binding-refresh>刷新绑定</button></div><p data-binding-notice role="status" aria-live="polite"></p><div data-binding-table></div>';
    root.querySelector('[data-binding-refresh]').onclick = () => { void refresh(); };
  }
  function clearConfiguration() { snapshot = null; editable = false; }
  function renderUsers() {
    if (!active()) return;
    const c = snapshot?.configuration;
    const rows = users.map(user => {
      const b = c?.accountBindings.find(b => b.accountId === user.id);
      const p = c?.people.find(p => p.personCode === b?.personCode);
      return { ...user, bindingPerson: !c ? '未核实' : p?.personCode || '未绑定', bindingOrg: !c ? '未核实' : p?.organizationCode || '—',
        bindingLeader: !c ? '未核实' : p?.leaderPersonCode || '未配置', bindingBp: !c ? '未核实' : p?.bpPersonCode || '未配置',
        bindingStatus: !c ? '绑定状态未核实' : !b ? '未绑定' : b.enabled ? '启用' : '停用',
        bindingPersonStatus: !c ? '未核实' : !p ? '—' : !p.enabled ? '人员停用' : !organizationEnabled(c, p.organizationCode) ? '机构停用' : '启用' };
    });
    const base = host.columns || [
      { k: 'username', l: '账号' }, { k: 'name', l: '姓名' },
      { k: 'status', l: '账号状态', render: r => enabledAccount(r) ? '启用' : '停用' },
    ];
    const bindingCols = host.bindingColumns ?? [{ k: 'bindingPerson', l: '人员编码' }, { k: 'bindingOrg', l: '机构编码' },
      { k: 'bindingLeader', l: '负责人编码' }, { k: 'bindingBp', l: 'BP编码' },
      { k: 'bindingPersonStatus', l: '人员/机构状态' }, { k: 'bindingStatus', l: '绑定状态' }];
    const cols = [...base, ...bindingCols];
    // The legacy delete action would strand a binding and invalidate future publishes.
    // Backend deletion protection is still required; this is only the original table's UI guard.
    const accountActions = (host.actions || []).map(action => action.l === '删除'
      ? { ...action, show: row => Boolean(c) && !c.accountBindings.some(b => b.accountId === row.id) && (!action.show || action.show(row)),
        onClick: row => {
          if (!active() || !admin() || !snapshot || snapshot.configuration.accountBindings.some(b => b.accountId === row.id)) return;
          action.onClick(row);
        } }
      : action);
    const actions = [...accountActions, { l: '绑定人员', cls: 'gray', icon: 'link',
      show: () => editable && admin(), onClick: row => edit(row.id) }];
    const table = root.querySelector('[data-binding-table]');
    table.innerHTML = host.renderTable(cols, rows, actions, 'users');
    host.bindTableActions(table, rows, actions);
  }

  async function refresh() {
    if (!active()) return false;
    if (saving || ownedModal) { notify('请先完成或关闭当前绑定弹窗，再刷新。', true); return false; }
    const ticket = ++generation;
    requestController?.abort();
    requestController = new AbortController();
    const signal = requestController.signal;
    clearConfiguration(); users = []; shell(); say('正在读取账号绑定…');
    const valid = () => active() && generation === ticket;
    if (!admin()) {
      // Never request the administrator endpoints and hide their result afterwards.
      try {
        const me = await host.api('/organization/me', { quiet: true, signal });
        if (!valid()) return false;
        if (me?.status === 'BOUND' && me.person) {
          say(`本人：${me.person.personCode}；所属机构：${me.person.organizationCode}。具体办理权限以业务检查为准。`);
        } else say('本人业务身份尚未配置或绑定；请联系管理员核对。');
      } catch (error) { if (valid()) say(error.message || '无法读取本人业务身份，请稍后刷新。'); }
      return false;
    }
    const results = await Promise.allSettled([
      host.api('/users', { quiet: true, signal }), host.api('/organization/config', { quiet: true, signal }),
    ]);
    if (!valid()) return false;
    if (!admin()) { say('账号权限已变化，请刷新查看本人身份。'); return false; }
    const [userResult, configResult] = results;
    const forbidden = results.find(r => r.status === 'rejected' && [401, 403].includes(Number(r.reason?.status || r.reason?.code)));
    if (forbidden) { say('无法读取管理资料，请确认当前账号仍有管理权限。'); return false; }
    if (userResult.status === 'fulfilled' && Array.isArray(userResult.value) &&
        userResult.value.every(u => u && accountId(u.id)) && new Set(userResult.value.map(u => u.id)).size === userResult.value.length) {
      users = copy(userResult.value);
    } else { say('账号列表读取失败，请刷新后重试。'); return false; }
    let problem;
    if (configResult.status === 'rejected') problem = `组织关系读取失败，仍可浏览账号。${configResult.reason?.message || '请稍后刷新。'}`;
    else {
      problem = configurationProblem(configResult.value);
      if (!problem) {
        snapshot = copy(configResult.value);
        const userIds = new Set(users.map(u => u.id));
        if (snapshot.configuration.accountBindings.some(b => !userIds.has(b.accountId))) {
          problem = '现有绑定包含已不在账号列表中的账号，请先核对遗留关系；本页保留原关系，暂不能保存。';
        } else editable = true;
      }
    }
    renderUsers();
    say(problem || '选择账号对应的现有人员；绑定不会新增岗位或审批权限。');
    host.onLoaded?.(copy(users));
    return editable;
  }

  function edit(id) {
    if (!active() || !admin() || !editable || !snapshot || saving || ownedModal) return;
    const user = users.find(u => u.id === id);
    if (!user) return;
    const original = copy(snapshot), config = original.configuration;
    const binding = config.accountBindings.find(b => b.accountId === id);
    const available = config.people.filter(p => !config.accountBindings.some(b => b.accountId !== id && b.personCode === p.personCode));
    const fields = [
      { k: 'account', label: '账号', type: 'readonly', value: `${user.username}（${enabledAccount(user) ? '启用' : '停用'}）` },
      { k: 'personCode', label: '绑定人员编码', type: 'select', required: true, options: [
        { v: '', l: '请选择现有人员' }, ...available.map(p => ({ v: p.personCode,
          l: `${p.personCode} · ${p.organizationCode}${p.enabled ? '' : ' · 人员停用'}` })),
      ], hint: '已绑定其他账号的人员不能重复选择，停用绑定也保留原对应。' },
      { k: 'enabled', label: '绑定状态', type: 'select', required: true,
        options: [{ v: 'true', l: '启用' }, { v: 'false', l: '停用' }] },
      { k: 'bindingOrg', label: '所属机构编码', type: 'readonly' },
      { k: 'bindingLeader', label: '负责人编码', type: 'readonly' },
      { k: 'bindingBp', label: 'BP编码', type: 'readonly' },
      { k: 'bindingRoleScopes', label: '各岗位负责机构', type: 'readonly' },
      { k: 'bindingCombined', label: '已配置兼任审批机构', type: 'readonly' },
      { k: 'bindingPersonStatus', label: '人员/机构状态', type: 'readonly' },
    ];
    const modal = { mask: null, closed: false, blocked: false };
    ownedModal = modal;
    const formHtml = host.renderForm(fields, { personCode: binding?.personCode || '', enabled: String(binding?.enabled ?? enabledAccount(user)) });
    modal.mask = host.openModal('绑定现有人员', `<p>仅调整此账号与人员的对应，机构、负责人/BP及岗位权限按已有配置保留。</p>${formHtml}<p data-binding-error role="alert" aria-live="assertive"></p>`, {
      okText: '保存绑定',
      onClose: () => { modal.closed = true; if (ownedModal === modal) ownedModal = null; },
      onOk: () => save(modal, user, original, fields, available),
    });
    const updateDetails = () => {
      if (!current(modal)) return;
      const p = config.people.find(p => p.personCode === modal.mask.querySelector('[data-k="personCode"]').value);
      const scopes = p?.responsibleOrganizationsByRole;
      const scopeText = !p ? '—' : scopes && Object.keys(scopes).length
        ? Object.entries(scopes).map(([role, orgs]) => `${role}：${orgs.length ? orgs.join('、') : '无负责机构'}`).join('；')
        : '沿用原机构范围（尚未分别配置岗位范围）';
      const combined = (config.combinedApprovals || []).filter(a => a.personCode === p?.personCode).map(a => a.organizationCode);
      for (const [k, value] of Object.entries({ bindingOrg: p?.organizationCode || '—', bindingLeader: p?.leaderPersonCode || '未配置',
        bindingBp: p?.bpPersonCode || '未配置', bindingRoleScopes: scopeText, bindingCombined: combined.length ? combined.join('、') : '未配置',
        bindingPersonStatus: !p ? '—' : !p.enabled ? '人员停用' :
          !organizationEnabled(config, p.organizationCode) ? '机构停用' : '启用' })) {
        modal.mask.querySelector(`[data-k="${k}"]`).value = value;
      }
    };
    modal.mask.querySelector('[data-k="personCode"]').onchange = updateDetails;
    updateDetails();
  }

  function modalError(modal, message) {
    if (!current(modal)) return;
    modal.mask.querySelector('[data-binding-error]').textContent = message;
    say(message);
    notify(message, true);
  }

  async function save(modal, user, original, fields, available) {
    // Always return false: the host's onOk otherwise closes whichever modal is now global.
    if (!current(modal) || saving) return false;
    if (!admin()) { clearConfiguration(); renderUsers(); modal.blocked = true; modalError(modal, '管理权限已变化，请关闭弹窗并刷新。'); return false; }
    if (modal.blocked) { modalError(modal, '请先关闭弹窗并刷新，再重新确认绑定。'); return false; }
    const collected = host.collectForm(modal.mask, fields);
    if (!collected) return false;
    // Select values are read exactly; collectForm trims all text in the original host.
    const personCode = modal.mask.querySelector('[data-k="personCode"]').value;
    const enabledValue = modal.mask.querySelector('[data-k="enabled"]').value;
    const person = available.find(p => p.personCode === personCode);
    if (!person || !['true', 'false'].includes(enabledValue)) { modalError(modal, '请选择现有人员及有效的绑定状态。'); return false; }
    const enabled = enabledValue === 'true';
    if (enabled && (!enabledAccount(user) || !person.enabled || !organizationEnabled(original.configuration, person.organizationCode))) {
      modalError(modal, '账号、人员或所属机构已停用，不能启用此绑定。'); return false;
    }
    const previous = original.configuration.accountBindings.find(b => b.accountId === user.id);
    if (previous?.personCode === personCode && previous.enabled === enabled) { modalError(modal, '绑定未改变，无需保存。'); return false; }
    const candidate = copy(original.configuration);
    const replacement = { accountId: user.id, personCode, enabled };
    const index = candidate.accountBindings.findIndex(b => b.accountId === user.id);
    if (index < 0) candidate.accountBindings.push(replacement); else candidate.accountBindings[index] = replacement;
    try { candidate.version = nextVersion(); } catch (error) { modalError(modal, error.message); return false; }
    saving = true;
    modal.mask.querySelector('[data-binding-error]').textContent = '正在保存，请等待结果。';
    let refreshAfterClose = false;
    try {
      const result = await host.api('/organization/config', { method: 'POST', quiet: true, signal: lifetime.signal,
        body: { expectedVersion: original.version, configuration: candidate } });
      if (!active()) return false;
      if (!current(modal)) { clearConfiguration(); refreshAfterClose = !ownedModal; return false; }
      if (configurationProblem(result) || result.version !== candidate.version || canonical(result.configuration) !== canonical(candidate)) {
        modal.blocked = true; clearConfiguration(); renderUsers();
        modalError(modal, '保存结果无法核实，请关闭弹窗并刷新查看；不要重复提交。'); return false;
      }
      snapshot = copy(result); editable = admin(); renderUsers();
      say('账号绑定已保存；岗位和审批权限仍按已有配置执行。'); notify('绑定已保存');
      // Close only the owned, still-current modal; a late response never closes a successor.
      host.closeModal();
    } catch (error) {
      if (!active()) return false;
      const status = Number(error.status || error.code);
      if (!current(modal)) { clearConfiguration(); refreshAfterClose = !ownedModal; return false; }
      if (status === 409) {
        modal.blocked = true; clearConfiguration(); renderUsers();
        modalError(modal, '配置已被更新或保存标识已使用。当前选择已保留，请关闭弹窗、刷新后重新核对。');
      } else if (status === 401 || status === 403) {
        modal.blocked = true; clearConfiguration(); users = []; renderUsers();
        modalError(modal, '登录或管理权限已失效，请关闭弹窗并重新确认账号。');
      } else if (status >= 400 && status < 500) {
        modalError(modal, `未保存：${error.message || '请核对绑定信息后重试。'}`);
      } else {
        modal.blocked = true; clearConfiguration(); renderUsers();
        modalError(modal, '未能确认保存结果，服务端可能已收到。请关闭弹窗并刷新查看，不要重复提交。');
      }
    } finally {
      saving = false;
      if (refreshAfterClose && active()) void refresh();
    }
    return false;
  }

  function destroy() {
    if (!alive) return;
    const shouldClear = active();
    alive = false; generation++; requestController?.abort(); lifetime.abort();
    host.signal?.removeEventListener('abort', destroy);
    // onClose invalidates all captured callbacks without touching another host modal.
    if (ownedModal && !ownedModal.closed && ownedModal.mask?.isConnected !== false) host.closeModal();
    ownedModal = null; clearConfiguration(); users = [];
    if (shouldClear || host.signal?.aborted) root.replaceChildren();
  }
  if (host.signal?.aborted || !(host.isCurrent?.() ?? true)) {
    alive = false; controller.ready = Promise.resolve(false); return controller;
  }
  host.signal?.addEventListener('abort', destroy, { once: true });
  controller.ready = refresh();
  return controller;
}
