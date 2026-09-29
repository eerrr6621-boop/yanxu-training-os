// Reviews the server-owned management group plan in the original users modal.
// No account, identity, role, institution, source path or candidate configuration is submitted.
const BASE = '/organization/management-group';
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const object = v => v !== null && typeof v === 'object' && !Array.isArray(v), text = v => typeof v === 'string' && v.length > 0;
const positive = v => Number.isSafeInteger(v) && v > 0, hex = v => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
const canonical = v => JSON.stringify(v, (_, x) => object(x) ? Object.fromEntries(Object.keys(x).sort().map(k => [k, x[k]])) : x);
const same = (a, b) => canonical(a) === canonical(b);
const refs = ['teacher-row-3', 'teacher-row-4', 'teacher-row-5', 'teacher-row-6', 'teacher-row-7', 'teacher-row-9', 'teacher-row-10', 'group-main-row-3', 'group-main-row-10', 'system-manager01'];
const roles = { admin: '系统管理员', manager: '业务管理员', viewer: '只读用户' };
const duties = { MEMBER: '内部人员', SUBMITTER: '需求填报', BRANCH_RESPONSIBLE: '分公司负责人', BRANCH_LEAD: '分公司牵头人', BP: 'BP', MANAGEMENT_ADMIN: '管理组' };
const operations = { 'demand.read': '查看需求', 'demand.write': '填报需求', 'bid.result': '登记招标结果', 'demand.accept': '受理需求', 'approval.review': '审批', 'catalog.read': '查看课程目录', 'catalog.manage': '维护课程目录', 'delivery.read': '查看交付', 'delivery.write': '维护交付', 'delivery.verify': '核验交付', 'settlement.submit': '提交课酬', 'settlement.review': '核准课酬', 'settlement.confirm': '确认课酬', 'settlement.correct': '更正课酬', 'settlement.pay': '登记实际收付', 'settlement.configure': '维护计酬配置', 'settlement.export': '导出结算', 'survey.preview': '预览问卷结果', 'survey.read': '查看问卷结果', 'survey.import': '导入问卷结果', 'survey.review': '审核问卷结果', 'survey.policy': '维护问卷口径', 'summary.read': '查看总结', 'summary.edit': '编写总结', 'summary.review.branch': '分公司审核总结', 'summary.review.bp': 'BP 审核总结', 'summary.export': '导出总结', 'reports.read': '查看报表', 'reports.export': '导出报表' };
const preservedKeys = ['oldRoleScopes', 'leaderAndBp', 'combinedApprovals', 'organizationsAndEnabledFlags', 'oldBindingsAndEnabledFlags', 'oldGrantsIncludingDeny', 'otherPeople', 'userPasswords', 'userStatus'];
const fail = () => { throw Error('管理组预览或回执未能完整核实，请重新读取。'); };
function rows(v, length) { if (!Array.isArray(v) || length !== undefined && v.length !== length) fail(); return v; }
function unique(v, key) { if (new Set(v.map(x => x[key])).size !== v.length) fail(); }
function sameSet(a, b) { return Array.isArray(a) && Array.isArray(b) && a.length === b.length && new Set(a).size === a.length && a.every(x => b.includes(x)); }
function identity(v) { if (!object(v) || !refs.includes(v.reference) || !positive(v.accountId) || !text(v.personCode) || !text(v.organizationCode) || !text(v.name)) fail(); }
function validate(v, published) {
  if (!object(v) || v.published !== published || v.accountsActivated !== false || v.passwordsChanged !== false || !text(v.expectedVersion) || !hex(v.snapshotFingerprint)) fail();
  const d = v.diff, e = v.evidence;
  if (!object(d) || !object(e) || e.policy !== 'M01-MANAGEMENT-GROUP-v1' || d.baseVersion !== v.expectedVersion || !text(d.candidateVersion) || d.candidateVersion === d.baseVersion || !hex(d.baseConfigurationFingerprint) || !hex(d.candidateConfigurationFingerprint) || !hex(d.evidenceFingerprint) || d.branchCount !== 37 || d.regionCount !== 5 || d.resourceActionCount !== 29 || d.published !== false || d.accountsActivated !== false || !object(d.preserved) || preservedKeys.some(k => d.preserved[k] !== true)) fail();
  rows(d.members, 10).forEach(identity); for (const k of ['reference', 'accountId', 'personCode']) unique(d.members, k);
  if (!sameSet(d.members.map(x => x.reference), refs)) fail();
  rows(d.userRoleChanges, 10); unique(d.userRoleChanges, 'accountId'); rows(d.personChanges, 10); unique(d.personChanges.map(x => x.identity || {}), 'reference');
  rows(d.addedPeople, 3); rows(d.addedAccountBindings, 3); unique(d.addedAccountBindings, 'accountId');
  if (!sameSet(d.addedRoleCodes, ['MANAGEMENT_ADMIN'])) fail();
  if (!object(e.original) || !object(e.supplement) || !object(e.manager)) fail();
  rows(e.original.oldSeven, 7); rows(e.supplement.newTwo, 2);
  for (const source of [e.original, e.supplement]) if (!text(source.batchKey) || !hex(source.sourceFingerprint) || !hex(source.receiptFingerprint) || !positive(source.intakeRevision)) fail();
  const branches = rows(e.branchCoverage, 37); unique(branches, 'organizationCode'); unique(branches, 'branch');
  if (branches.some(x => !object(x) || !text(x.branch) || !text(x.organizationCode) || !text(x.region) || !text(x.parentOrganizationCode) || typeof x.enabled !== 'boolean') || new Set(branches.map(x => x.region)).size !== 5) fail();
  const branchCodes = branches.map(x => x.organizationCode);
  const manager = d.members.find(x => x.reference === 'system-manager01');
  if (e.manager.reference !== manager.reference || e.manager.accountId !== manager.accountId || e.manager.personCode !== manager.personCode || e.manager.organizationCode !== manager.organizationCode || e.manager.verifiedUsername !== 'manager01' || e.manager.identityKind !== 'SERVER_GENERATED_TECHNICAL_PERSON' || e.manager.employeeIdentity !== false || e.manager.existingBindingAdopted !== false) fail();
  for (const member of d.members) {
    const account = d.userRoleChanges.find(x => x.accountId === member.accountId), change = d.personChanges.find(x => x.identity?.reference === member.reference);
    if (!account || account.reference !== member.reference || account.personCode !== member.personCode || !text(account.username) || member.reference === 'system-manager01' && account.username !== e.manager.verifiedUsername || !Object.hasOwn(roles, account.roleBefore) || account.roleAfter !== 'admin' || ![0, 1].includes(account.statusBefore) || account.statusAfter !== account.statusBefore || !change || !same(change.identity, member)) fail();
    const after = change.after, before = change.before;
    if (!object(after) || after.personCode !== member.personCode || after.organizationCode !== member.organizationCode || typeof after.enabled !== 'boolean' || !Array.isArray(after.roleCodes) || !after.roleCodes.every(text) || new Set(after.roleCodes).size !== after.roleCodes.length || !after.roleCodes.includes('MANAGEMENT_ADMIN') || !object(after.responsibleOrganizationsByRole) || !sameSet(after.responsibleOrganizationsByRole.MANAGEMENT_ADMIN, branchCodes)) fail();
    if (refs.slice(0, 7).includes(member.reference)) {
      const source = e.original.oldSeven.find(x => x.identity?.reference === member.reference);
      if (!source || !same(source.identity, member) || source.sourceName !== member.name || !text(source.sourceOrganization) || typeof source.bindingEnabled !== 'boolean' || typeof source.personEnabled !== 'boolean' || source.candidateReceiptBindingHomeMatched !== true || !object(before) || before.enabled !== after.enabled || source.personEnabled !== before.enabled || before.organizationCode !== after.organizationCode || before.personCode !== after.personCode || before.leaderPersonCode !== after.leaderPersonCode || before.bpPersonCode !== after.bpPersonCode || !Array.isArray(before.roleCodes) || !object(before.responsibleOrganizationsByRole) || before.roleCodes.some(role => !text(role) || !after.roleCodes.includes(role) || !Array.isArray(before.responsibleOrganizationsByRole[role]) || !before.responsibleOrganizationsByRole[role].every(text) || !same(before.responsibleOrganizationsByRole[role], after.responsibleOrganizationsByRole[role]))) fail();
    } else {
      const binding = d.addedAccountBindings.find(x => x.accountId === member.accountId);
      if (before !== null || !binding || binding.personCode !== member.personCode || binding.enabled !== false || !d.addedPeople.some(x => same(x, after))) fail();
      if (member.reference !== 'system-manager01') { const source = e.supplement.newTwo.find(x => x.identity?.reference === member.reference); if (!source || !same(source.identity, member) || !text(source.homeMapping) || source.newBindingEnabled !== false || typeof source.intakeAccountEnabled !== 'boolean') fail(); }
    }
  }
  rows(d.addedGrants, 29); unique(d.addedGrants, 'ruleId');
  if (new Set(d.addedGrants.map(x => x.resource)).size !== 29 || d.addedGrants.some(x => !text(x.ruleId) || x.roleCode !== 'MANAGEMENT_ADMIN' || !Object.hasOwn(operations, x.resource) || x.action !== (x.resource.endsWith('.read') ? 'VIEW' : x.resource.endsWith('.export') ? 'EXPORT' : 'HANDLE') || x.effect !== 'ALLOW' || x.scope !== 'NAMED_ORGS' || !sameSet(x.organizationCodes, branchCodes))) fail();
  if (published) {
    if (v.schema !== 'M01-MANAGEMENT-PUBLICATION-v1' || v.publicationKey !== 'm01-management-group-v1' || v.version !== d.candidateVersion || typeof v.replayed !== 'boolean' || Object.hasOwn(v, 'reviewToken') || !same(v.members, d.members) || !positive(v.createdBy) || v.managerPersonCode !== manager.personCode || !object(v.technicalIdentity) || v.technicalIdentity.accountId !== manager.accountId || v.technicalIdentity.personCode !== manager.personCode || v.technicalIdentity.username !== 'manager01' || v.technicalIdentity.employeeIdentity !== false) fail();
  } else {
    const c = v.configuration;
    if (v.ready !== true || !text(v.reviewToken) || v.reviewToken.length > 256 || !object(c) || c.version !== d.candidateVersion || !Array.isArray(c.organizations) || !Array.isArray(c.people) || !Array.isArray(c.accountBindings) || !Array.isArray(c.grants)) fail();
    for (const member of d.members) {
      const change = d.personChanges.find(x => x.identity.reference === member.reference), binding = c.accountBindings.find(x => x.accountId === member.accountId);
      const old = e.original.oldSeven.find(x => x.identity.reference === member.reference);
      if (!c.people.some(x => same(x, change.after)) || !binding || binding.personCode !== member.personCode || binding.enabled !== (old ? old.bindingEnabled : false)) fail();
    }
    for (const b of branches) if (!c.organizations.some(x => x.organizationCode === b.organizationCode && x.displayName === b.branch && x.parentOrganizationCode === b.parentOrganizationCode && x.enabled === b.enabled)) fail();
    if (d.addedGrants.some(g => !c.grants.some(x => same(x, g)))) fail();
  }
  return v;
}

export function createAccountManagementGroup(host) {
  for (const key of ['api', 'getUser', 'getModal', 'openModal', 'closeModal', 'onSessionInvalidated']) if (typeof host?.[key] !== 'function') throw TypeError('Management group host missing ' + key);
  const actor = host.getUser(); let alive = true, session = null;
  const active = () => alive && !host.signal?.aborted && (host.isCurrent?.() ?? true) && host.getUser() === actor && actor?.role === 'admin';
  const current = s => active() && session === s && !s.closed && host.getModal() === s.mask && s.mask?.isConnected !== false;
  const q = (s, key) => s.mask.querySelector('[data-management-group-' + key + ']');
  const say = (s, message) => { if (current(s)) q(s, 'notice').textContent = message; };
  const show = (node, visible) => { node.hidden = !visible; node.style.display = visible ? '' : 'none'; };
  const code = error => Number(error?.status || error?.code) || 0;
  function controls(s) {
    if (!current(s)) return;
    const busy = !!s.busy, receipt = s.value?.published === true, review = s.value?.published === false && s.value.ready === true;
    q(s, 'preview').disabled = busy || !s.readable || receipt || s.unknown || s.requireLogin;
    q(s, 'received').disabled = busy || s.requireLogin;
    show(q(s, 'confirm-area'), review && !s.unknown && !s.requireLogin);
    q(s, 'reviewed').disabled = busy || !review;
    q(s, 'confirm').disabled = busy || !review || !q(s, 'reviewed').checked || s.unknown || s.requireLogin;
    q(s, 'view').disabled = busy || !s.value;
    q(s, 'close').disabled = s.busy === 'commit';
    show(q(s, 'login'), s.requireLogin);
    q(s, 'login').disabled = busy;
    q(s, 'previous').disabled = busy || !s.value || s.page === 0;
    q(s, 'next').disabled = busy || !s.value || s.page + 1 >= s.pages;
    show(q(s, 'details'), !!s.value);
    s.mask.dataset.locked = s.busy === 'commit' ? 'true' : 'false';
  }
  function clear(s) { s.value = null; q(s, 'reviewed').checked = false; s.page = 0; s.pages = 0; for (const k of ['summary', 'table', 'pagination', 'source']) q(s, k).replaceChildren(); controls(s); }
  function dispose(s) { if (!s || s.closed) return; s.closed = true; s.ticket++; s.controller?.abort(); s.value = null; s.attempt = null; s.mask.dataset.locked = 'false'; if (session === s) session = null; }
  function ensure(s) { if (current(s)) return true; if (session === s && !active()) { dispose(s); if (host.getModal() === s.mask) host.closeModal(true); } return false; }
  const fresh = (s, ticket) => ensure(s) && s.ticket === ticket;
  function begin(s, kind) { s.controller?.abort(); s.controller = new AbortController(); const ticket = ++s.ticket; s.busy = kind; q(s, 'guard').replaceChildren(); controls(s); return ticket; }
  function end(s, ticket) { if (current(s) && s.ticket === ticket) { s.busy = false; controls(s); } }
  function authFailure(s, error) {
    const status = code(error); if (![401, 403].includes(status)) return false;
    clear(s); s.readable = false; s.unknown = false; s.attempt = null;
    if (status === 401) { host.onSessionInvalidated(); return true; }
    say(s, '当前账号已无配置管理组权限，请关闭后核对授权。'); controls(s); return true;
  }
  function guard(s, run, label = '放弃预览并关闭') {
    if (!current(s)) return true;
    if (s.busy === 'commit') { say(s, '正在提交管理组配置，请等待读取结果后关闭。'); return false; }
    if (!s.unknown && !(s.value?.published === false) && s.busy !== 'preview') return true;
    q(s, 'guard').innerHTML = '<p class="inline-note">' + (s.unknown ? '本次提交结果尚未核实。再次打开会先查询已发布回执。' : '当前配置预览尚未确认。') + '</p><div class="toolbar"><button type="button" class="btn" data-management-group-stay>继续核对</button><button type="button" class="btn gray" data-management-group-discard>' + esc(label) + '</button></div>';
    q(s, 'stay').onclick = () => { if (current(s)) q(s, 'guard').replaceChildren(); };
    q(s, 'discard').onclick = () => { if (!current(s) || s.busy === 'commit') return; s.ticket++; s.controller?.abort(); s.busy = false; clear(s); q(s, 'guard').replaceChildren(); run(); };
    return false;
  }
  function home(v, member) { return v.evidence.original.oldSeven.find(x => x.identity.reference === member.reference)?.sourceOrganization || v.evidence.supplement.newTwo.find(x => x.identity.reference === member.reference)?.homeMapping || '系统技术身份'; }
  function table(s) {
    if (!current(s) || !s.value) return;
    const v = s.value, d = v.diff, view = q(s, 'view').value, branchName = code => v.evidence.branchCoverage.find(x => x.organizationCode === code)?.branch || code;
    let columns, data;
    if (view === 'branches') { columns = ['区域', '明确覆盖的分公司', '机构原状态']; data = v.evidence.branchCoverage.map(b => [esc(b.region), esc(b.branch), b.enabled ? '启用（保留）' : '停用（保留）']); }
    else if (view === 'grants') { columns = ['新增许可', '操作', '范围']; data = d.addedGrants.map(g => [esc(operations[g.resource]), esc(({ VIEW: '查看', HANDLE: '办理', EXPORT: '导出' })[g.action]), '明确列出的 ' + g.organizationCodes.length + ' 家分公司']); }
    else if (view === 'preserved') {
      columns = ['人员', '原岗位及范围', '负责人 / BP 与人员状态'];
      data = d.personChanges.map(change => [esc(change.identity.name), change.before ? change.before.roleCodes.map(role => '<div>' + esc(duties[role] || role) + '：' + (change.before.responsibleOrganizationsByRole[role]?.length ? change.before.responsibleOrganizationsByRole[role].map(branchName).map(esc).join('、') : '无指定分公司范围') + '</div>').join('') : '新增管理组身份', change.before ? '原关系保留 · ' + (change.before.enabled ? '人员启用' : '人员停用') + '<details><summary>原关系编号</summary><span style="overflow-wrap:anywhere">负责人：' + esc(change.before.leaderPersonCode || '未设置') + '<br>BP：' + esc(change.before.bpPersonCode || '未设置') + '</span></details>' : '新增绑定保持停用']);
    } else {
      columns = ['人员 / 归属', '当前账号', '账号角色变化', '原状态 / 业务绑定'];
      data = d.members.map(member => { const account = d.userRoleChanges.find(x => x.accountId === member.accountId), old = v.evidence.original.oldSeven.find(x => x.identity.reference === member.reference); return [esc(member.name) + '<br><small>' + esc(home(v, member)) + '</small>', esc(account.username) + '<br><small>账号 #' + member.accountId + '</small>', esc(roles[account.roleBefore]) + ' → ' + esc(roles[account.roleAfter]), (account.statusBefore === 1 ? '账号启用（保留）' : '账号停用（保留）') + '<br><small>' + (old ? '原绑定' + (old.bindingEnabled ? '启用' : '停用') + '（保留）' : '新增绑定：停用') + '</small>']; });
    }
    const size = 10; s.pages = Math.max(1, Math.ceil(data.length / size)); s.page = Math.min(s.page, s.pages - 1);
    q(s, 'table').innerHTML = '<div class="table-wrap"><table class="tbl"><thead><tr>' + columns.map(c => '<th scope="col">' + esc(c) + '</th>').join('') + '</tr></thead><tbody>' + data.slice(s.page * size, (s.page + 1) * size).map(row => '<tr>' + row.map((cell, i) => '<td data-label="' + esc(columns[i]) + '">' + cell + '</td>').join('') + '</tr>').join('') + '</tbody></table></div>';
    q(s, 'pagination').textContent = '第 ' + (s.page + 1) + ' / ' + s.pages + ' 页 · 共 ' + data.length + ' 项'; controls(s);
  }
  function render(s, value) {
    s.value = value; s.page = 0; q(s, 'view').value = 'members'; q(s, 'reviewed').checked = false;
    q(s, 'summary').innerHTML = '<p class="inline-note">' + (value.published ? '已发布的管理组回执' : '待确认的管理组预览') + ' · ' + value.diff.members.length + ' 个独立身份 · ' + value.diff.branchCount + ' 家分公司 · 新增 ' + value.diff.resourceActionCount + ' 项许可</p><p class="modal-intro">原账号启停与密码、原岗位范围、负责人 / BP、兼任依据和已有拒绝规则（DENY）均保留。新增 3 个业务绑定保持停用，业务可用性仍以账号、绑定和机构当前状态为准。</p>';
    q(s, 'source').innerHTML = '<details><summary>查看版本与真实来源</summary><p style="overflow-wrap:anywhere">原配置：' + esc(value.expectedVersion) + '<br>' + (value.published ? '发布版本：' : '候选版本：') + esc(value.diff.candidateVersion) + '<br>快照：' + esc(value.snapshotFingerprint) + '</p><p style="overflow-wrap:anywhere">原批次：' + esc(value.evidence.original.batchKey) + ' · 接收修订 ' + value.evidence.original.intakeRevision + '<br>补充批次：' + esc(value.evidence.supplement.batchKey) + ' · 接收修订 ' + value.evidence.supplement.intakeRevision + '</p><p class="modal-intro">manager01 使用当前服务器已有的独立账号，技术身份由服务器生成。</p></details>';
    table(s); controls(s);
  }
  async function received(s) {
    if (!ensure(s) || s.busy || s.requireLogin) return false;
    const ticket = begin(s, 'received'), wasUnknown = s.unknown; clear(s); say(s, '正在查询管理组发布回执…');
    try {
      const raw = await host.api(BASE + '/received', { quiet: true, signal: s.controller.signal }); if (!fresh(s, ticket)) return false;
      if (raw?.published === false && raw.ready === false) { s.readable = true; s.unknown = false; s.attempt = null; say(s, wasUnknown ? '当前未查到已发布回执。请重新生成预览并核对，旧核对凭据已作废。' : '尚未发布管理组配置，请先生成预览。'); return true; }
      const value = validate(raw, true); s.readable = true; s.unknown = false; s.attempt = null;
      s.requireLogin = false; render(s, value); say(s, wasUnknown ? '已查询到服务器发布回执，请对照版本核对结果。不会重发确认请求。' : '已读取真实发布回执，不会重复配置。'); return true;
    } catch (error) {
      if (!fresh(s, ticket) || error?.name === 'AbortError') return false; clear(s); if (authFailure(s, error)) return false;
      s.readable = false; s.unknown = wasUnknown; say(s, code(error) === 409 ? (error.message || '已发布结果与当前资料不一致，请核对后续变更。') : code(error) === 503 ? '管理组配置来源尚未启用或无法核实，请由管理员核对。' : '回执暂时无法读取。请重新查询；不会发起确认请求。'); return false;
    } finally { end(s, ticket); }
  }
  async function preview(s) {
    if (!ensure(s) || s.busy || !s.readable || s.unknown || s.requireLogin || s.value?.published) return false;
    const ticket = begin(s, 'preview'); clear(s); say(s, '正在核对已接收身份、当前账号与完整配置差异…');
    try {
      const raw = await host.api(BASE + '/preview', { quiet: true, signal: s.controller.signal }); if (!fresh(s, ticket)) return false;
      const value = validate(raw, raw?.published === true); render(s, value); say(s, value.published ? '管理组已发布，已读取真实回执。' : '请核对下列 10 个身份、角色变化和范围，勾选已核对后再明确确认。'); return true;
    } catch (error) {
      if (!fresh(s, ticket) || error?.name === 'AbortError') return false; clear(s); if (authFailure(s, error)) return false;
      say(s, code(error) === 409 ? (error.message || '来源、账号或配置已变化，请重新预览。') : code(error) === 503 ? '管理组配置来源尚未启用或无法核实，请由管理员核对。' : error.message || '管理组预览暂时无法核实，请重新读取。'); return false;
    } finally { end(s, ticket); }
  }
  async function commit(s) {
    if (!ensure(s) || s.busy || s.unknown || s.requireLogin || s.value?.published !== false || !q(s, 'reviewed').checked) return false;
    const review = s.value, body = { expectedVersion: review.expectedVersion, snapshotFingerprint: review.snapshotFingerprint, reviewToken: review.reviewToken, reviewed: true };
    s.attempt = { expectedVersion: body.expectedVersion, snapshotFingerprint: body.snapshotFingerprint };
    const ticket = begin(s, 'commit'); say(s, '正在确认管理组配置，请等待本次结果…');
    try {
      const raw = await host.api(BASE + '/commit', { body, quiet: true, signal: s.controller.signal }); if (!fresh(s, ticket)) return false;
      const value = validate(raw, true);
      if (typeof raw.sessionInvalidated !== 'boolean' || value.expectedVersion !== review.expectedVersion || value.snapshotFingerprint !== review.snapshotFingerprint || !same(value.diff, review.diff) || !same(value.evidence, review.evidence)) fail();
      s.unknown = false; s.attempt = null; s.requireLogin = raw.sessionInvalidated; render(s, value);
      say(s, s.requireLogin ? '管理组配置已保存。回执已显示；当前会话已失效，请查看后重新登录。账号启停和密码保持原状。' : '管理组配置已保存。当前管理员会话继续有效，账号启停和密码保持原状。');
      if (!s.requireLogin) { try { await host.onSaved?.(); } catch { if (fresh(s, ticket)) say(s, '管理组配置已保存，回执保留；用户列表暂未刷新，请关闭后刷新页面。'); } }
      return true;
    } catch (error) {
      if (!fresh(s, ticket) || error?.name === 'AbortError') return false; clear(s); if (authFailure(s, error)) return false;
      if ([400, 404, 409].includes(code(error))) { s.unknown = false; s.attempt = null; say(s, (error.message || '本次核对已失效。') + ' 请重新生成预览，再核对后确认。'); }
      else { s.unknown = true; say(s, '本次确认结果尚未核实。请查询已发布回执；确认入口已锁定，不会重发或换凭据重复提交。'); }
      return false;
    } finally { end(s, ticket); }
  }
  async function open() {
    if (!active() || host.getModal()) return false;
    const s = { mask: null, closed: false, ticket: 0, controller: null, busy: false, value: null, readable: false, unknown: false, attempt: null, requireLogin: false, allowClose: false, page: 0, pages: 0 };
    s.mask = host.openModal('配置管理组', '<p class="modal-intro">从服务器已核实的接收记录配置管理组。先预览真实身份和权限差异，再明确确认。</p><p data-management-group-notice role="status" aria-live="polite"></p><div data-management-group-guard></div><div class="toolbar" style="flex-wrap:wrap;margin:12px 0"><button type="button" class="btn" data-management-group-preview disabled>生成管理组预览</button><button type="button" class="btn gray" data-management-group-received>查询已发布回执</button><button type="button" class="btn gray" data-management-group-close>关闭</button></div><div data-management-group-details hidden><div data-management-group-summary></div><div class="form-item" style="margin:12px 0"><label for="management-group-view">核对内容</label><select id="management-group-view" data-management-group-view><option value="members">10 位成员与账号角色</option><option value="branches">37 家分公司范围</option><option value="grants">29 项新增许可</option><option value="preserved">原岗位与关系保留情况</option></select></div><div data-management-group-table></div><div class="toolbar" style="flex-wrap:wrap;margin:12px 0"><button type="button" class="btn gray" data-management-group-previous>上一页</button><span data-management-group-pagination></span><button type="button" class="btn gray" data-management-group-next>下一页</button></div><div data-management-group-source></div></div><div data-management-group-confirm-area hidden style="margin-top:16px"><label><input type="checkbox" data-management-group-reviewed> 我已核对本次 10 个身份、角色变化、37 家分公司范围及 29 项新增许可</label><p class="modal-intro">确认后更新账号角色和机构许可，相关会话将更新。请先查看成功回执，再重新登录继续办理。</p><button type="button" class="btn" data-management-group-confirm disabled>确认配置管理组</button></div><button type="button" class="btn" data-management-group-login hidden>已查看回执，重新登录</button>', {
      noFoot: true, wide: true,
      beforeClose: () => s.allowClose || guard(s, () => { s.allowClose = true; host.closeModal(); }),
      onClose: () => { const login = s.requireLogin && active(); dispose(s); if (login) host.onSessionInvalidated(); },
    });
    if (!s.mask) return false; session = s;
    q(s, 'close').onclick = () => { if (current(s)) host.closeModal(); };
    q(s, 'login').onclick = () => { if (current(s) && !s.busy && s.requireLogin) { s.allowClose = true; host.closeModal(); } };
    q(s, 'preview').onclick = () => { if (!ensure(s) || s.busy) return; if (!s.value || guard(s, () => preview(s), '放弃并重新生成预览')) return preview(s); };
    q(s, 'received').onclick = () => { if (!ensure(s) || s.busy) return; if (s.unknown || !s.value || s.value.published || guard(s, () => received(s), '放弃预览并查询回执')) return received(s); };
    q(s, 'confirm').onclick = () => commit(s); q(s, 'reviewed').onchange = () => { if (ensure(s)) controls(s); };
    q(s, 'view').onchange = () => { if (ensure(s) && !s.busy && s.value) { s.page = 0; table(s); } };
    for (const [key, step] of [['previous', -1], ['next', 1]]) q(s, key).onclick = () => { if (!ensure(s) || s.busy || !s.value) return; s.page = Math.max(0, Math.min(s.pages - 1, s.page + step)); table(s); };
    controls(s); await received(s); return current(s);
  }
  function destroy() { if (!alive) return; alive = false; const s = session; dispose(s); if (s && host.getModal() === s.mask) host.closeModal(true); host.signal?.removeEventListener('abort', destroy); }
  if (host.signal?.aborted) alive = false; else host.signal?.addEventListener('abort', destroy, { once: true });
  return { open, destroy };
}
