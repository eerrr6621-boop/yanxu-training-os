// Inline account-import review using the original host controls. No source uploads or role publication.
const escape = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
const copy = value => JSON.parse(JSON.stringify(value));
const nonempty = value => typeof value === 'string' && value.trim().length > 0;
const id = value => Number.isSafeInteger(value) && value > 0;
const revision = value => nonempty(value) || Number.isSafeInteger(value) && value >= 0;
const roleNames = { BRANCH_RESPONSIBLE: '分公司负责人', BRANCH_LEAD: '分公司牵头人', BP: 'BP' };
const statusOf = error => Number(error?.status || error?.code);
const parseAccountId = value => /^[1-9][0-9]*$/.test(value) && id(Number(value)) ? Number(value) : null;
const validReceipt = (value, references, batchKey) => value && value.imported === true && value.batchKey === batchKey && revision(value.revision) &&
  typeof value.replayed === 'boolean' && value.permissionsPublished === false && value.accountsActivated === false && Array.isArray(value.rows) && value.rows.length === references.length &&
  new Set(value.rows.map(row => row?.reference)).size === references.length && value.rows.every(row => row && references.includes(row.reference) &&
    nonempty(row.personCode) && id(row.accountId) && ['CREATE_PENDING', 'LINK_EXISTING'].includes(row.action)) &&
  new Set(value.rows.map(row => row.personCode)).size === references.length && new Set(value.rows.map(row => row.accountId)).size === references.length;

function validPreview(value) {
  if (!value || !nonempty(value.batchKey) || !revision(value.revision) || typeof value.imported !== 'boolean' ||
      !Array.isArray(value.rows) || !value.rows.length || value.rows.length > 10000 ||
      !value.summary || value.summary.candidates !== value.rows.length || !Number.isSafeInteger(value.summary.historicalExcluded) || value.summary.historicalExcluded < 0 ||
      new Set(value.rows.map(row => row?.reference)).size !== value.rows.length) return false;
  if (!value.rows.every(row => row && nonempty(row.reference) && nonempty(row.name) && nonempty(row.organization) &&
      Array.isArray(row.identities) && row.identities.every(nonempty) && Array.isArray(row.approvalRoles) &&
      row.approvalRoles.every(role => role && Object.hasOwn(roleNames, role.kind) && role.canApprove === false &&
        Array.isArray(role.proposedBranches) && role.proposedBranches.every(nonempty) && Array.isArray(role.proposedRegions) && role.proposedRegions.every(nonempty)))) return false;
  if (value.imported) return validReceipt(value.receipt, value.rows.map(row => row.reference), value.batchKey);
  return nonempty(value.sourceFingerprint) && nonempty(value.reviewToken) && !value.rows.some(row => row.alreadyImported === true);
}

// Coverage is a read-only projection. An invalid extension never changes the original import proof.
function validCoverage(preview) {
  const branches = preview.branchCoverage;
  if (!Array.isArray(branches) || !branches.length || branches.length > 10000 || new Set(branches.map(row => row?.branch)).size !== branches.length) return false;
  const expectedBranches = [...new Set(preview.rows.flatMap(row => row.approvalRoles.flatMap(role => role.proposedBranches)))];
  if (JSON.stringify(expectedBranches.sort()) !== JSON.stringify(branches.map(row => row?.branch).sort())) return false;
  const people = new Map(preview.rows.map(row => [row.reference, row])), received = new Map((preview.receipt?.rows || []).map(row => [row.reference, row]));
  return branches.every(branch => {
    if (!branch || !nonempty(branch.branch) || !nonempty(branch.region) || branch.defaultHandlerReference !== null || branch.canApprove !== false || branch.readOnly !== true || branch.preparationOnly !== true ||
        !['ROLE_EVIDENCE_PREPARED', 'MULTIPLE_IDENTITIES_RETAINED_ROUTING_PENDING'].includes(branch.routingStatus) ||
        !Array.isArray(branch.leaderCandidates) || !branch.leaderCandidates.length || !Array.isArray(branch.bpCandidates) || branch.bpCandidates.length !== 1 || !Array.isArray(branch.combinedCandidates) ||
        new Set(branch.combinedCandidates).size !== branch.combinedCandidates.length) return false;
    const candidates = (list, bp) => {
      const expectedReferences = preview.rows.filter(person => person.approvalRoles.some(role => (role.kind === 'BP') === bp && role.proposedBranches.includes(branch.branch))).map(person => person.reference);
      return JSON.stringify([...expectedReferences].sort()) === JSON.stringify(list.map(row => row?.reference).sort()) && new Set(list.map(row => row?.reference)).size === list.length && list.every(candidate => {
      const person = people.get(candidate?.reference), receipt = received.get(candidate?.reference);
      if (!person || !Array.isArray(candidate.roleKinds) || !candidate.roleKinds.length || new Set(candidate.roleKinds).size !== candidate.roleKinds.length ||
          !candidate.roleKinds.every(kind => bp ? kind === 'BP' : ['BRANCH_RESPONSIBLE', 'BRANCH_LEAD'].includes(kind))) return false;
      if (bp && !person.approvalRoles.some(role => role.kind === 'BP' && role.proposedBranches.includes(branch.branch) && role.proposedRegions.includes(branch.region))) return false;
      const expected = person.approvalRoles.filter(role => (role.kind === 'BP') === bp && role.proposedBranches.includes(branch.branch)).map(role => role.kind);
      if (JSON.stringify([...expected].sort()) !== JSON.stringify([...candidate.roleKinds].sort())) return false;
      return preview.imported ? person.alreadyImported === true && receipt && nonempty(person.personCode) && person.personCode === receipt.personCode &&
        id(candidate.accountId) && candidate.accountId === person.accountId && candidate.accountId === receipt.accountId && ['RECEIVED_DISABLED', 'RECEIVED_ENABLED'].includes(candidate.accountState) :
        person.alreadyImported !== true && person.accountId == null && person.personCode == null && candidate.accountId === null && candidate.accountState === 'NOT_RECEIVED';
      });
    };
    if (!candidates(branch.leaderCandidates, false) || !candidates(branch.bpCandidates, true)) return false;
    const expectedCombined = branch.leaderCandidates.filter(leader => branch.bpCandidates.some(bp => bp.reference === leader.reference)).map(candidate => candidate.reference);
    return JSON.stringify([...expectedCombined].sort()) === JSON.stringify([...branch.combinedCandidates].sort()) &&
      branch.combinedCandidates.every(reference => people.get(reference)?.combinedDutiesRequiresWorkflowReview === true) &&
      branch.routingStatus === (branch.leaderCandidates.length > 1 ? 'MULTIPLE_IDENTITIES_RETAINED_ROUTING_PENDING' : 'ROLE_EVIDENCE_PREPARED');
  });
}

/**
 * mountAccountImport(root, host) -> { ready: Promise, refresh(): Promise, canSwitchBatch(), destroy() }
 * host uses the existing api/getUser/modal/form/table/toast controls; API results are unwrapped data.
 * Optional isCurrent/signal own the page lifecycle; onImported(receipt) refreshes host account views.
 * Optional importKind is the fixed 'management-supplement' entry; omission retains the original batch.
 * The server must enforce every authority and proof.
 */
export function mountAccountImport(root, host) {
  for (const key of ['api', 'getUser', 'openModal', 'closeModal', 'renderTable', 'renderForm', 'collectForm', 'bindTableActions', 'toast']) {
    if (typeof host?.[key] !== 'function') throw new TypeError(`Missing host function: ${key}`);
  }
  if (host.importKind !== undefined && host.importKind !== 'management-supplement') throw new TypeError('Unknown account import kind');
  const supplement = host.importKind === 'management-supplement';
  const apiBase = supplement ? '/organization/management-supplement' : '/organization/account-import';
  let alive = true, generation = 0, fetchController = null, snapshot = null, receipt = null;
  let loading = false, posting = false, blocked = false, ownedModal = null;
  let decisions = new Map(), display = 'people', branchQuery = '';
  let coverage = null;
  const filters = { query: '', organization: '', status: '' };
  const lifetime = new AbortController();
  const active = () => alive && !host.signal?.aborted && (host.isCurrent?.() ?? true);
  const admin = () => host.getUser()?.role === 'admin';
  const current = modal => active() && ownedModal === modal && !modal.closed && modal.mask?.isConnected !== false;
  const find = selector => root.querySelector(selector);
  const say = message => { if (active()) { const node = find('[data-import-notice]'); if (node) node.textContent = message; } };
  const notify = (message, error = false) => { if (active()) host.toast(message, error); };
  const mayReview = () => display === 'people' && active() && admin() && snapshot && !snapshot.imported && !receipt && !blocked && !loading && !posting;
  const allReviewed = () => mayReview() && snapshot.rows.every(row => decisions.get(row.reference)?.reviewed === true);
  const ctl = { ready: null, refresh, canSwitchBatch: () => active() && !posting && !ownedModal, destroy };
  let reportedReceipt = null;
  function reportImported() {
    if (!active() || !admin() || !receipt || typeof host.onImported !== 'function') return;
    const key = JSON.stringify([receipt.batchKey, receipt.revision]);
    if (reportedReceipt === key) return;
    reportedReceipt = key;
    const saved = copy(receipt);
    Promise.resolve().then(() => {
      if (active() && admin() && reportedReceipt === key) return host.onImported(saved);
    }).catch(() => {
      if (active() && admin()) notify('导入已核实，账号列表暂时无法更新，请重新打开用户页核对。', true);
    });
  }

  function closeOwned() { if (ownedModal && current(ownedModal)) host.closeModal(); }
  function revoke(message = '登录或管理权限已失效，请重新确认账号。') {
    if (!active()) return;
    fetchController?.abort(); generation++; snapshot = null; receipt = null; coverage = null; decisions.clear(); blocked = true; loading = false; display = 'people'; branchQuery = '';
    filters.query = ''; filters.organization = ''; filters.status = '';
    closeOwned(); shell(); render(); say(message);
  }
  function authorized() { if (!active()) return false; if (!admin()) { revoke('当前账号无权核对导入，请联系管理员。'); return false; } return true; }
  function shell() {
    if (!active()) return;
    root.innerHTML = `<section class="card data-card"><div class="card-heading"><div><h2>${supplement ? '补充管理人员' : '批量导入账号'}</h2><p>逐人核对后导入，新建账号保持待启用，审批权限不在这里发布。</p></div><button type="button" class="btn gray" data-import-refresh>刷新核对名单</button></div>
      <div class="toolbar" data-import-view-switch><button type="button" class="btn gray" data-import-view-people>人员</button><button type="button" class="btn gray" data-import-view-branches>按分公司核对岗位</button></div>
      <div class="toolbar" data-import-review-info><p data-import-notice role="status" aria-live="polite"></p><p data-import-progress aria-live="polite"></p></div>
      <div class="toolbar" data-import-person-filters><label class="search-box"><input type="search" aria-label="搜索姓名或机构" data-import-search placeholder="搜索姓名或机构" value="${escape(filters.query)}"></label><select aria-label="所属机构" data-import-organization><option value="">全部机构</option></select><select aria-label="核对状态" data-import-status><option value="">全部状态</option><option value="pending">未核对</option><option value="reviewed">已核对</option></select><button type="button" class="btn gray" data-import-pending>查看所有未核对</button></div>
      <div data-import-person-content><div class="toolbar"><p data-import-visible></p></div><div data-import-table></div><div class="toolbar"><button type="button" class="btn" data-import-commit disabled>确认导入</button></div></div>
      <div data-import-branches hidden><p>核对各分公司当前准备的负责人、牵头人和 BP。岗位准备不代表账号已启用或已获审批权限。</p><div class="toolbar"><label class="search-box"><input type="search" aria-label="搜索分公司或岗位人员" data-import-branch-search placeholder="搜索分公司或岗位人员" value="${escape(branchQuery)}"></label><p data-import-branch-count role="status"></p></div><div data-import-branch-table></div></div></section>`;
    const switchDisplay = next => { if (!authorized() || posting || ownedModal || supplement && next === 'branches') return; display = next; render(); };
    find('[data-import-view-people]').onclick = () => switchDisplay('people');
    find('[data-import-view-branches]').onclick = () => switchDisplay('branches');
    find('[data-import-branch-search]').oninput = event => { if (!authorized()) return; branchQuery = event.target.value; renderCoverage(); };
    find('[data-import-search]').oninput = event => { if (!authorized()) return; filters.query = event.target.value; renderTable(); };
    find('[data-import-organization]').onchange = event => { if (!authorized()) return; filters.organization = event.target.value; renderTable(); };
    find('[data-import-status]').value = filters.status;
    find('[data-import-status]').onchange = event => { if (!authorized()) return; filters.status = event.target.value; renderTable(); };
    find('[data-import-pending]').onclick = () => { if (!authorized()) return; filters.query = ''; filters.organization = ''; filters.status = 'pending'; find('[data-import-search]').value = ''; find('[data-import-organization]').value = ''; find('[data-import-status]').value = 'pending'; renderTable(); };
    find('[data-import-refresh]').onclick = () => { void refresh(); };
    find('[data-import-commit]').onclick = confirmImport;
  }

  function roleDescription(row) {
    if (!row.approvalRoles.length) return supplement ? '审批权限另行办理' : '无审批岗位';
    return row.approvalRoles.map(role => `${roleNames[role.kind]}：${role.proposedRegions.length ? `${role.proposedRegions.join('、')}；` : ''}${role.proposedBranches.length ? role.proposedBranches.join('、') : '负责范围待核对'}`).join('；') + (row.approvalRoles.length > 1 ? '（兼任，仍需审批流程校验）' : '');
  }
  function roleSummary(row) {
    const scope = (values, label) => values.length ? `${values[0]}${values.length > 1 ? `等 ${values.length} 个${label}` : ''}` : '范围待核对';
    const roles = row.approvalRoles.map(role => `${roleNames[role.kind]} · ${role.kind === 'BP' && role.proposedRegions.length ? scope(role.proposedRegions, '区域') : scope(role.proposedBranches, '机构')}`);
    return `<div>${escape(row.identities.join('、'))}</div><small class="text-muted">${roles.length ? roles.map(escape).join('<br>') : supplement ? '审批权限另行办理' : '无审批岗位'}${roles.length > 1 ? '<br>兼任 · 需流程校验' : ''}</small>`;
  }
  function progress() {
    if (!active()) return;
    const total = snapshot?.rows.length || 0;
    const reviewed = snapshot ? snapshot.rows.filter(row => decisions.has(row.reference)).length : 0;
    find('[data-import-progress]').textContent = !snapshot ? loading ? '正在核实完整名单…' : '名单尚未核实，核对进度暂不可用。' : receipt ? `本批 ${total} 人已导入；新建账号未启用，审批权限未发布。` : `全批已核对 ${reviewed} / ${total} 人，尚有 ${total - reviewed} 人未核对。历史保留 ${snapshot.summary.historicalExcluded} 人不在候选中。`;
    find('[data-import-commit]').disabled = !allReviewed();
    find('[data-import-refresh]').disabled = posting;
    const viewSwitch = find('[data-import-view-switch]'); viewSwitch.hidden = supplement; viewSwitch.style.display = supplement ? 'none' : '';
    for (const key of ['people', 'branches']) { const button = find('[data-import-view-' + key + ']'); button.disabled = posting; button.setAttribute('aria-pressed', String(display === key)); }
    const branches = display === 'branches';
    for (const key of ['review-info', 'person-filters', 'person-content']) { const section = find('[data-import-' + key + ']'); section.hidden = branches; section.style.display = branches ? 'none' : ''; }
    const section = find('[data-import-branches]'); section.hidden = !branches; section.style.display = branches ? '' : 'none';
  }
  function renderTable() {
    if (!active()) return;
    if (display === 'branches') { find('[data-import-table]').innerHTML = ''; renderCoverage(); progress(); return; }
    find('[data-import-branch-table]').innerHTML = '';
    const rows = (snapshot?.rows || []).map((row, index) => {
      const imported = receipt?.rows.find(item => item.reference === row.reference);
      return { ...row, id: index + 1, reviewState: receipt ? '已导入' : decisions.has(row.reference) ? '已核对' : '未核对',
        importChoice: imported ? `${imported.action === 'LINK_EXISTING' ? '已关联账号' : '已建待启用账号'} #${imported.accountId}` : decisions.get(row.reference)?.action === 'LINK_EXISTING' ? `关联已有账号 #${decisions.get(row.reference).accountId}` : decisions.has(row.reference) ? '新建待启用账号' : '待逐人选择' };
    });
    const query = filters.query.trim().toLocaleLowerCase();
    const visible = rows.filter(row => (!query || `${row.name} ${row.organization}`.toLocaleLowerCase().includes(query)) && (!filters.organization || row.organization === filters.organization) &&
      (!filters.status || (filters.status === 'pending' ? row.reviewState === '未核对' : row.reviewState === '已核对')));
    const hiddenPending = rows.filter(row => row.reviewState === '未核对' && !visible.includes(row)).length;
    find('[data-import-visible]').textContent = snapshot ? `当前显示 ${visible.length} / ${rows.length} 人${hiddenPending ? `；另有 ${hiddenPending} 位未核对人员被筛选隐藏，仍须全部核对。` : '。'}` : '';
    const actions = [{ l: '核对导入', icon: 'clipboard-check', cls: 'gray', show: () => Boolean(mayReview()), onClick: row => openReview(row.reference) }];
    const columns = [{ k: 'person', l: '人员', render: row => `<div>${escape(row.name)}</div><small class="text-muted">${escape(row.organization)}</small>` },
      { k: 'approvalRoles', l: '身份 / 岗位范围', render: roleSummary },
      { k: 'reviewState', l: '核对状态', render: row => `<div>${escape(row.reviewState)}</div><small class="text-muted">${row.sourceIdentityEvidence === 'AVAILABLE' ? '来源编号有证据，仍需核对本人' : '来源编号待核对，候选保留'}</small>` },
      { k: 'importChoice', l: '账号处理' }];
    const table = find('[data-import-table]');
    table.innerHTML = !snapshot ? '' : visible.length ? host.renderTable(columns, visible, actions, 'users') : '<div class="empty-state"><p>当前没有匹配的候选人员，请调整筛选。</p></div>';
    host.bindTableActions(table, visible, actions); progress();
  }
  function renderCoverage() {
    if (!active()) return;
    const table = find('[data-import-branch-table]');
    find('[data-import-branch-count]').textContent = '';
    if (!snapshot || !coverage) { table.innerHTML = '<div class="empty-state"><p>' + (loading ? '正在核实分公司岗位资料…' : '分公司岗位资料暂不可核实，请刷新名单后重试。') + '</p></div>'; return; }
    const people = new Map(snapshot.rows.map(row => [row.reference, row]));
    const query = branchQuery.trim().toLocaleLowerCase();
    const rows = coverage.map((branch, index) => ({ ...branch, id: index + 1 })).filter(branch => !query || [branch.branch, branch.region, ...[...branch.leaderCandidates, ...branch.bpCandidates].map(candidate => people.get(candidate.reference).name)].join(' ').toLocaleLowerCase().includes(query));
    const candidateHtml = (candidates, branch) => candidates.length ? '<div style="min-width:0">' + candidates.map(candidate => {
      const person = people.get(candidate.reference);
      const state = { NOT_RECEIVED: '待接收', RECEIVED_DISABLED: '已接收 · 账号停用', RECEIVED_ENABLED: '已接收 · 账号启用' }[candidate.accountState];
      return '<div style="overflow-wrap:anywhere;margin-bottom:10px"><b>' + escape(person.name) + '</b><br><small>' + escape(candidate.roleKinds.map(kind => roleNames[kind]).join('、')) + (branch.combinedCandidates.includes(candidate.reference) ? ' · 明确兼任准备' : '') + '</small><br><small class="text-muted">' + state + (candidate.accountId === null ? '' : ' · 账号 ID ' + candidate.accountId) + '</small></div>';
    }).join('') + '</div>' : '<span class="text-muted">暂无准备人员</span>';
    find('[data-import-branch-count]').textContent = '当前显示 ' + rows.length + ' / ' + coverage.length + ' 家分公司';
    const columns = [{ k: 'branch', l: '分公司', render: branch => '<b>' + escape(branch.branch) + '</b><br><small class="text-muted">' + escape(branch.region) + '</small>' },
      { k: 'leaderCandidates', l: '负责人 / 牵头人', render: branch => candidateHtml(branch.leaderCandidates, branch) },
      { k: 'bpCandidates', l: '对应 BP', render: branch => candidateHtml(branch.bpCandidates, branch) },
      { k: 'routingStatus', l: '岗位准备情况', render: branch => '<div><div>' + (branch.routingStatus === 'MULTIPLE_IDENTITIES_RETAINED_ROUTING_PENDING' ? '多人均保留，办理安排待核对。' : '岗位材料已准备，审批安排待核对。') + '</div><small class="text-muted">未指定默认办理人。' + (branch.combinedCandidates.length ? '兼任人员的两项职责仍需流程核对。' : '') + '</small></div>' }];
    table.innerHTML = rows.length ? host.renderTable(columns, rows, [], 'users') : '<div class="empty-state"><p>未找到匹配的分公司或岗位人员。</p></div>';
  }
  function render() {
    if (!active()) return;
    const organizations = [...new Set((snapshot?.rows || []).map(row => row.organization))];
    find('[data-import-organization]').innerHTML = `<option value="">全部机构</option>${organizations.map(org => `<option value="${escape(org)}">${escape(org)}</option>`).join('')}${filters.organization && !organizations.includes(filters.organization) ? `<option value="${escape(filters.organization)}">${escape(filters.organization)}</option>` : ''}`;
    find('[data-import-organization]').value = filters.organization; renderTable();
  }

  async function refresh(options = {}) {
    if (!active()) return false;
    if (!admin()) { revoke('当前账号无权核对导入，请联系管理员。'); return false; }
    if (posting || ownedModal) { notify('请先完成或关闭当前核对弹窗，再刷新。', true); return false; }
    const ticket = ++generation; fetchController?.abort(); fetchController = new AbortController();
    snapshot = null; receipt = null; coverage = null; branchQuery = ''; decisions.clear(); blocked = true; loading = true; shell(); render(); say(options.resolveUnknown ? '正在核实导入回执，请勿重复提交…' : '正在读取已确认的候选名单…');
    try {
      const data = await host.api(`${apiBase}/preview`, { quiet: true, signal: fetchController.signal });
      if (!active() || ticket !== generation) return false;
      if (!admin()) { revoke(); return false; }
      if (!validPreview(data)) { say('候选名单或导入回执尚未核实，暂不能导入，请刷新或联系管理员。'); return false; }
      snapshot = copy(data); receipt = data.imported ? copy(data.receipt) : null; coverage = validCoverage(data) ? copy(data.branchCoverage) : null; blocked = false;
      reportImported();
      say(receipt ? '本批已完成导入，不会重复创建。新建账号仍待启用，审批权限未发布。' : options.resolveUnknown ? '当前未查到已完成回执；请重新逐人核对后再决定是否提交。' : '请逐人选择账号处理方式并核对。来源编号缺失不会排除候选，搜索与筛选不会减少本批人数。');
      return true;
    } catch (error) {
      if (!active() || ticket !== generation) return false;
      if ([401, 403].includes(statusOf(error))) revoke();
      else say(options.resolveUnknown ? '暂时无法核实导入结果。请刷新查询回执，不要重复提交。' : statusOf(error) === 503 ? '导入名单尚未就绪，请联系管理员确认来源后重试；现有账号仍可维护。' : '候选名单读取失败，请刷新后重试。');
      return false;
    } finally { if (active() && ticket === generation) { loading = false; render(); } }
  }

  function modalError(modal, message) {
    if (!current(modal)) return;
    modal.mask.querySelector('[data-import-error]').textContent = message; notify(message, true);
  }
  function clearReviewCheck(modal) { const checkbox = modal.mask.querySelector('[data-import-reviewed]'); if (checkbox) checkbox.checked = false; }
  function openReview(reference) {
    if (!authorized() || !mayReview() || ownedModal) return;
    const person = snapshot.rows.find(row => row.reference === reference); if (!person) return;
    const original = snapshot, previous = decisions.get(reference);
    const fields = [{ k: 'action', label: '账号处理方式', type: 'select', required: true, options: [{ v: '', l: '请逐项核对后选择' }, { v: 'CREATE_PENDING', l: '新建待启用账号' }, { v: 'LINK_EXISTING', l: '关联已核实的现有账号' }] },
      { k: 'accountId', label: '已有账号编号', placeholder: '仅在关联已有账号时填写', hint: '填写系统已有账号的数字编号并查询，不能用姓名或来源工号代替。' }];
    const modal = { mask: null, closed: false, lookup: null, lookupSequence: 0, proof: null };
    const html = `<p><strong>${escape(person.name)}</strong> · ${escape(person.organization)}</p><p>${escape(person.identities.join('、'))}</p><p>${escape(roleDescription(person))}</p><p>来源编号${person.sourceIdentityEvidence === 'AVAILABLE' ? '有证据，仍需核对本人' : '待核对；保留此候选'}。此次导入不启用账号、不发布审批权限。</p>${host.renderForm(fields, { action: previous?.action || '', accountId: previous?.accountId || '' })}
      <div data-import-account-tools><button type="button" class="btn gray" data-import-lookup>查询已有账号</button><div data-import-account-result role="status" aria-live="polite"></div></div>
      <label><input type="checkbox" data-import-reviewed> 我已逐项核对该人员、机构及账号处理方式</label><p data-import-error role="alert" aria-live="assertive"></p>`;
    modal.mask = host.openModal('核对候选人员', html, { okText: '保存本人的核对', onClose: () => { modal.closed = true; modal.lookup?.abort(); if (ownedModal === modal) ownedModal = null; }, onOk: () => saveReview(modal, original, person, fields) });
    if (!modal.mask) return; ownedModal = modal;
    const action = modal.mask.querySelector('[data-k="action"]'), account = modal.mask.querySelector('[data-k="accountId"]');
    const change = () => {
      if (!current(modal)) return;
      modal.lookup?.abort(); modal.lookupSequence++; modal.proof = null; clearReviewCheck(modal);
      modal.mask.querySelector('[data-import-account-tools]').hidden = action.value !== 'LINK_EXISTING'; account.disabled = action.value !== 'LINK_EXISTING';
      modal.mask.querySelector('[data-import-account-result]').textContent = ''; modal.mask.querySelector('[data-import-error]').textContent = '';
    };
    action.onchange = change; account.oninput = change; account.onchange = change;
    modal.mask.querySelector('[data-import-lookup]').onclick = () => { void lookupAccount(modal); }; change();
  }
  async function lookupAccount(modal) {
    if (!current(modal) || !authorized() || !mayReview()) return;
    const action = modal.mask.querySelector('[data-k="action"]'), input = modal.mask.querySelector('[data-k="accountId"]');
    const accountId = parseAccountId(input.value);
    modal.proof = null; clearReviewCheck(modal);
    if (action.value !== 'LINK_EXISTING' || !accountId) { modalError(modal, '请输入已有账号的正整数编号，再查询核对。'); return; }
    modal.lookup?.abort(); modal.lookup = new AbortController(); const ticket = ++modal.lookupSequence;
    modal.mask.querySelector('[data-import-account-result]').textContent = '正在查询账号…';
    try {
      const result = await host.api(`${apiBase}/accounts/${accountId}`, { quiet: true, signal: modal.lookup.signal });
      if (!current(modal) || ticket !== modal.lookupSequence || !authorized()) return;
      if (action.value !== 'LINK_EXISTING' || parseAccountId(input.value) !== accountId) return;
      if (!result || result.accountId !== accountId || !nonempty(result.username) || !nonempty(result.name) || !nonempty(result.role) ||
          ![0, 1, '0', '1'].includes(result.status) || typeof result.available !== 'boolean') { modalError(modal, '账号资料未核实，请重新查询。'); return; }
      modal.mask.querySelector('[data-import-account-result]').textContent = `账号 #${result.accountId} · ${result.username} · ${result.name} · ${result.status === 1 || result.status === '1' ? '启用' : '停用'} · ${{ admin: '管理员', manager: '业务管理员', viewer: '只读用户' }[result.role] || result.role}${result.available ? '；请核对确为同一人。' : '；此账号不可关联。'}`;
      if (result.available && nonempty(result.proof)) modal.proof = { accountId, accountProof: result.proof };
      else modalError(modal, '此账号当前不可关联，请核对账号或选择新建待启用账号。');
    } catch (error) {
      if (!current(modal) || ticket !== modal.lookupSequence) return;
      if ([401, 403].includes(statusOf(error))) revoke();
      else modalError(modal, '账号查询失败，尚未确认关联。请重新查询。');
    }
  }
  function saveReview(modal, original, person, fields) {
    if (!current(modal) || !authorized() || !mayReview() || snapshot !== original) return false;
    if (!host.collectForm(modal.mask, fields.filter(field => field.k === 'action'))) return false;
    const action = modal.mask.querySelector('[data-k="action"]').value;
    if (!['CREATE_PENDING', 'LINK_EXISTING'].includes(action)) { modalError(modal, '请选择本人的账号处理方式。'); return false; }
    if (!modal.mask.querySelector('[data-import-reviewed]').checked) { modalError(modal, '请逐项核对后勾选确认。'); return false; }
    let accountId = null, accountProof = null;
    if (action === 'LINK_EXISTING') {
      accountId = parseAccountId(modal.mask.querySelector('[data-k="accountId"]').value);
      if (!modal.proof || modal.proof.accountId !== accountId) { modalError(modal, '请先查询并核对当前已有账号。'); return false; }
      if ([...decisions].some(([reference, choice]) => reference !== person.reference && choice.accountId === accountId)) { modalError(modal, '此账号已被另一候选选用，请逐人核对，不能重复关联。'); return false; }
      accountProof = modal.proof.accountProof;
    }
    decisions.set(person.reference, { reference: person.reference, action, accountId, accountProof, reviewed: true });
    if (current(modal)) host.closeModal(); renderTable(); say('已保存本人的核对；全部候选核对完成后才能确认导入。'); return false;
  }

  function confirmImport() {
    if (!authorized() || !allReviewed() || ownedModal) return;
    const original = snapshot, planned = original.rows.map(row => copy(decisions.get(row.reference)));
    const linked = planned.filter(choice => choice.action === 'LINK_EXISTING').length;
    const modal = { mask: null, closed: false };
    modal.mask = host.openModal('确认本批账号导入', `<p>已逐人核对 ${planned.length} 人，其中新建待启用账号 ${planned.length - linked} 人，关联已有账号 ${linked} 人。</p><p>本次不启用新账号，不发布业务审批权限。历史保留人员不在此次导入范围。</p><p data-import-error role="alert" aria-live="assertive"></p>`, {
      okText: '确认导入', onClose: () => { modal.closed = true; if (ownedModal === modal) ownedModal = null; },
      onOk: () => commit(modal, original, planned),
    });
    if (modal.mask) ownedModal = modal;
  }
  async function commit(modal, original, planned) {
    // Returning false prevents the original host from closing a successor modal on delayed resolution.
    if (!current(modal) || !authorized() || !allReviewed() || snapshot !== original || posting) return false;
    posting = true; progress(); let resolveUnknown = false;
    try {
      const response = await host.api(`${apiBase}/commit`, { method: 'POST', quiet: true, signal: lifetime.signal,
        body: { expectedRevision: original.revision, sourceFingerprint: original.sourceFingerprint, reviewToken: original.reviewToken, decisions: planned } });
      if (!active()) return false;
      if (!admin()) { revoke(); return false; }
      if (!validReceipt(response, original.rows.map(row => row.reference), original.batchKey) ||
          !planned.every(choice => response.rows.some(row => row.reference === choice.reference && row.action === choice.action && (choice.action !== 'LINK_EXISTING' || row.accountId === choice.accountId)))) {
        blocked = true; decisions.clear(); say('保存结果尚未核实，正在查询回执；不会重复提交。'); resolveUnknown = true; closeOwned(); return false;
      }
      receipt = copy(response); snapshot = { ...original, imported: true, receipt }; coverage = null; decisions.clear(); blocked = true;
      if (current(modal)) host.closeModal(); render(); say(response.replayed ? '已核实本批导入回执，没有重复创建账号。' : '导入已完成。新建账号待启用，审批权限未发布。'); notify('本批账号导入已核实');
      reportImported();
    } catch (error) {
      if (!active()) return false;
      if ([401, 403].includes(statusOf(error))) { revoke(); return false; }
      if (statusOf(error) === 409) {
        blocked = true; decisions.clear(); modalError(modal, '名单或账号状态已变化。请关闭弹窗、刷新名单，并重新逐人核对。'); say('旧核对已失效，请刷新名单并重新逐人核对。');
        // The original modal restores disabled=false after onOk returns false. Remove this obsolete
        // submit action instead, keeping its cancel/close controls available for the refresh path.
        if (current(modal)) { const submit = modal.mask.querySelector('#modal-ok'); if (submit) submit.remove(); }
      } else if (statusOf(error) >= 400 && statusOf(error) < 500) {
        modalError(modal, '本次未导入，请关闭弹窗核对各人的账号处理后再提交。');
      } else {
        blocked = true; decisions.clear(); say('导入结果暂时不明，正在查询回执；不会重复提交。'); resolveUnknown = true; closeOwned();
      }
    } finally {
      posting = false;
      if (active()) { render(); if (resolveUnknown && !ownedModal) void refresh({ resolveUnknown: true }); }
    }
    return false;
  }

  function destroy() {
    if (!alive) return;
    const shouldClear = active();
    if (ownedModal && !ownedModal.closed && ownedModal.mask?.isConnected !== false) host.closeModal();
    alive = false; generation++; fetchController?.abort(); lifetime.abort();
    ownedModal?.lookup?.abort(); ownedModal = null; snapshot = null; receipt = null; coverage = null; decisions.clear();
    host.signal?.removeEventListener('abort', destroy);
    if (shouldClear || host.signal?.aborted) root.replaceChildren();
  }
  if (!active()) { alive = false; ctl.ready = Promise.resolve(false); return ctl; }
  host.signal?.addEventListener('abort', destroy, { once: true });
  shell(); ctl.ready = refresh(); return ctl;
}
