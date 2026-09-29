// Main teacher roster reception in the original modal. Server proof remains private.
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const copy = value => JSON.parse(JSON.stringify(value));
const nonempty = value => typeof value === 'string' && value.trim().length > 0;
const positive = value => Number.isSafeInteger(value) && value > 0;
const statusOf = error => Number(error?.status || error?.code);
const sameSummary = (a, b) => ['teachers', 'excludedLeads', 'historicalExcluded'].every(key => a?.[key] === b?.[key]);
const validSummary = (value, count) => value && value.teachers === count && ['teachers', 'excludedLeads', 'historicalExcluded'].every(key => Number.isSafeInteger(value[key]) && value[key] >= 0);
function validPreview(value) {
  return value && nonempty(value.batchKey) && value.batchKey.length <= 128 && typeof value.sourceFingerprint === 'string' && /^[a-f0-9]{64}$/.test(value.sourceFingerprint) && typeof value.imported === 'boolean' &&
    (value.imported ? value.reviewToken === null : typeof value.reviewToken === 'string' && /^[a-f0-9]{64}$/.test(value.reviewToken)) && Array.isArray(value.rows) && value.rows.length > 0 && value.rows.length <= 10000 && validSummary(value.summary, value.rows.length) &&
    new Set(value.rows.map(row => row?.reference)).size === value.rows.length && new Set(value.rows.map(row => row?.accountId)).size === value.rows.length &&
    (!value.imported || new Set(value.rows.map(row => row?.teacherId)).size === value.rows.length) && value.rows.every(row => row && nonempty(row.reference) && nonempty(row.name) && row.name.length <= 64 && nonempty(row.organization) && row.organization.length <= 200 &&
      (row.job === null || typeof row.job === 'string' && row.job.length <= 64) && ['讲师', '高级讲师', '特级讲师'].includes(row.teacherLevel) && positive(row.accountId) &&
      (value.imported ? positive(row.teacherId) && ['待完善', '在库', '出库'].includes(row.status) : row.teacherId === null && row.status === '待接收'));
}
function validReceipt(value, preview) {
  return value && value.batchKey === preview.batchKey && value.sourceFingerprint === preview.sourceFingerprint && value.imported === true && typeof value.replayed === 'boolean' &&
    Array.isArray(value.rows) && value.rows.length === preview.rows.length && validSummary(value.summary, value.rows.length) && sameSummary(value.summary, preview.summary) &&
    new Set(value.rows.map(row => row?.teacherId)).size === value.rows.length && value.rows.every((row, index) => row && row.reference === preview.rows[index].reference && row.accountId === preview.rows[index].accountId && positive(row.teacherId));
}
function matchesReceipt(preview, receipt) {
  return preview.imported && preview.batchKey === receipt.batchKey && preview.sourceFingerprint === receipt.sourceFingerprint && sameSummary(preview.summary, receipt.summary) &&
    preview.rows.length === receipt.rows.length && preview.rows.every((row, index) => row.reference === receipt.rows[index].reference && row.accountId === receipt.rows[index].accountId && row.teacherId === receipt.rows[index].teacherId);
}
const inert = () => ({ ready: Promise.resolve(false), refresh: async () => false, destroy() {} });

/** Host API is unwrapped and accepts AbortSignal. onImported refreshes only the original teacher list. */
export function openTeacherRosterImport(host) {
  for (const key of ['api', 'getUser', 'getModal', 'openModal', 'closeModal', 'renderTable']) if (typeof host?.[key] !== 'function') throw TypeError('Missing roster host: ' + key);
  const identity = host.getUser();
  if (identity?.role !== 'admin' || host.signal?.aborted || host.isCurrent?.() === false) return inert();
  let alive = true, mask = null, snapshot = null, confirmedReceipt = null, pendingSource = null, reported = null;
  let readController = null, writeController = null, sequence = 0, reading = false, posting = false, denied = false, query = '', page = 0, listRefreshFailed = false;
  const current = () => alive && !host.signal?.aborted && (host.isCurrent?.() ?? true) && host.getUser() === identity && identity.role === 'admin' && mask?.isConnected !== false && host.getModal() === mask;
  const node = key => mask?.querySelector('[data-roster-' + key + ']');
  const notice = message => { if (current()) node('notice').textContent = message; };
  function clear() { snapshot = null; query = ''; page = 0; if (mask) { node('summary').textContent = ''; node('table').innerHTML = ''; node('count').textContent = ''; node('search').value = ''; } }
  function buttons() {
    if (!current()) return;
    node('receive').disabled = posting || reading || denied || !snapshot || snapshot.imported || Boolean(confirmedReceipt);
    node('receive').style.display = confirmedReceipt || snapshot?.imported ? 'none' : '';
    node('receive').textContent = snapshot ? `确认接收 ${snapshot.summary.teachers} 位教师` : '确认接收教师名单';
    node('refresh').disabled = posting || denied;
    node('search').disabled = posting || reading || !snapshot;
    node('previous').disabled = posting || reading || page <= 0;
    node('next').disabled = posting || reading || !snapshot || (page + 1) * 20 >= filtered().length;
  }
  const filtered = () => (snapshot?.rows || []).filter(row => !query.trim() || `${row.name} ${row.organization}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  function draw() {
    if (!current()) return;
    if (!snapshot) { buttons(); return; }
    const rows = filtered(), pages = Math.max(1, Math.ceil(rows.length / 20)); page = Math.min(page, pages - 1);
    const { teachers, excludedLeads, historicalExcluded } = snapshot.summary;
    node('summary').textContent = `本批共 ${teachers} 位教师；${excludedLeads} 位额外牵头人、${historicalExcluded} 位历史人员不在接收范围。`;
    node('count').textContent = `显示 ${rows.length ? page * 20 + 1 : 0}–${Math.min((page + 1) * 20, rows.length)} / ${rows.length} 位 · 第 ${page + 1} / ${pages} 页`;
    node('table').innerHTML = rows.length ? host.renderTable([
      { k: 'name', l: snapshot.imported ? '名单姓名' : '姓名', render: row => `<span style="overflow-wrap:anywhere">${escape(row.name)}</span>` },
      { k: 'organization', l: snapshot.imported ? '名单单位' : '单位', render: row => `<span style="overflow-wrap:anywhere">${escape(row.organization)}</span>` },
      { k: 'job', l: snapshot.imported ? '名单岗位' : '岗位', render: row => escape(row.job || '待补充') },
      { k: 'teacherLevel', l: snapshot.imported ? '名单等级' : '等级', render: row => escape(row.teacherLevel) },
      { k: 'accountId', l: '账号 ID' }, { k: 'status', l: '教师状态', render: row => escape(row.status) },
    ], rows.slice(page * 20, (page + 1) * 20).map((row, index) => ({ ...row, id: page * 20 + index + 1 })), []) : '<div class="empty-state"><p>没有匹配的教师，请调整搜索。</p></div>';
    buttons();
  }
  function dispose() { if (!alive) return; alive = false; sequence++; readController?.abort(); writeController?.abort(); clear(); confirmedReceipt = null; pendingSource = null; host.signal?.removeEventListener('abort', destroy); }
  function destroy() { const owned = mask && host.getModal() === mask; dispose(); if (owned) host.closeModal(true); }
  function checkCurrent() { if (current()) return true; if (alive && (host.getUser() !== identity || identity.role !== 'admin' || host.isCurrent?.() === false || host.signal?.aborted)) destroy(); return false; }
  async function refreshOriginal(receipt) {
    if (!current() || typeof host.onImported !== 'function') return;
    const key = JSON.stringify([receipt.batchKey, receipt.sourceFingerprint]);
    if (reported === key && !listRefreshFailed) return;
    try { await host.onImported(copy(receipt)); if (current()) { reported = key; listRefreshFailed = false; } }
    catch { if (current()) listRefreshFailed = true; }
  }
  async function refresh() {
    if (!checkCurrent() || posting || denied) return false;
    const ticket = ++sequence; readController?.abort(); readController = new AbortController(); reading = true; clear(); buttons();
    notice(confirmedReceipt ? '已接收，正在刷新当前名单…' : '正在核实教师名单…');
    try {
      const data = await host.api('/teacher-roster/preview', { quiet: true, signal: readController.signal });
      if (!checkCurrent() || ticket !== sequence) return false;
      if (!validPreview(data) || pendingSource && (data.batchKey !== pendingSource.batchKey || data.sourceFingerprint !== pendingSource.sourceFingerprint || !sameSummary(data.summary, pendingSource.summary) || data.rows.length !== pendingSource.rows.length || data.rows.some((row, index) => row.reference !== pendingSource.rows[index].reference || row.accountId !== pendingSource.rows[index].accountId)) || confirmedReceipt && !matchesReceipt(data, confirmedReceipt)) throw Error('UNVERIFIED_PREVIEW');
      snapshot = copy(data); pendingSource = null;
      if (data.imported) {
        const receipt = confirmedReceipt || { batchKey: data.batchKey, sourceFingerprint: data.sourceFingerprint, imported: true, rows: data.rows.map(({ reference, accountId, teacherId }) => ({ reference, accountId, teacherId })), summary: data.summary };
        await refreshOriginal(receipt);
        if (!checkCurrent() || ticket !== sequence) return false;
      }
      draw(); notice(data.imported ? listRefreshFailed ? '已接收，师资列表刷新失败，请刷新；不会重复建档。' : '本批已接收。以下为原名单资料，当前档案以师资列表为准；教师状态为当前值，不会重复建档。' : '接收后建立待完善档案。常驻地区和逐课授课资格需另行核对。'); return true;
    } catch (error) {
      if (!checkCurrent() || ticket !== sequence || error?.name === 'AbortError') return false;
      clear();
      if ([401, 403].includes(statusOf(error))) { denied = true; confirmedReceipt = null; pendingSource = null; notice('登录或管理权限已失效，请关闭后重新确认账号。'); }
      else notice(confirmedReceipt ? '已接收，当前名单刷新失败，请刷新；不会重复建档。' : [409, 503].includes(statusOf(error)) ? '教师名单尚未就绪或依据已变化，请刷新后再核对。' : '教师名单暂时无法核实，请刷新后重试。');
      return false;
    } finally { if (current() && ticket === sequence) { reading = false; buttons(); } }
  }
  async function receive() {
    if (!checkCurrent() || posting || reading || denied || !snapshot || snapshot.imported || confirmedReceipt) return;
    const original = snapshot; pendingSource = { batchKey: original.batchKey, sourceFingerprint: original.sourceFingerprint, rows: original.rows.map(({ reference, accountId }) => ({ reference, accountId })), summary: copy(original.summary) }; posting = true; readController?.abort(); sequence++; writeController = new AbortController(); buttons(); notice('正在接收教师名单，请稍候…');
    let reread = false;
    try {
      const result = await host.api('/teacher-roster/import', { body: { batchKey: original.batchKey, sourceFingerprint: original.sourceFingerprint, reviewToken: original.reviewToken }, quiet: true, signal: writeController.signal });
      if (!checkCurrent()) return;
      if (!validReceipt(result, original)) throw Error('UNVERIFIED_RECEIPT');
      confirmedReceipt = copy(result); clear(); reread = true; notice('已接收，正在刷新师资列表…');
      await refreshOriginal(result);
    } catch (error) {
      if (!checkCurrent() || error?.name === 'AbortError') return;
      clear();
      if ([401, 403].includes(statusOf(error))) { denied = true; confirmedReceipt = null; notice('登录或管理权限已失效，请关闭后重新确认账号。'); }
      else if ([409, 503].includes(statusOf(error))) { pendingSource = null; notice('接收依据已变化或暂不可用，请刷新名单核对后再操作。'); }
      else { notice('接收结果暂时无法核实，正在查询当前状态；不会自动重复提交。'); reread = true; }
    } finally { if (current()) { posting = false; buttons(); if (reread) await refresh(); } }
  }
  mask = host.openModal('接收教师名单', '<p class="modal-intro" data-roster-summary></p><p class="modal-intro" data-roster-notice role="status" aria-live="polite"></p><div class="toolbar" style="flex-wrap:wrap"><label class="search-box"><input type="search" data-roster-search aria-label="搜索教师姓名或单位" placeholder="搜索教师姓名或单位"></label><button type="button" class="btn gray" data-roster-refresh>刷新名单</button></div><div data-roster-table></div><div class="toolbar" style="flex-wrap:wrap"><p data-roster-count></p><button type="button" class="btn gray" data-roster-previous>上一页</button><button type="button" class="btn gray" data-roster-next>下一页</button></div><div class="toolbar" style="flex-wrap:wrap"><button type="button" class="btn" data-roster-receive disabled>确认接收教师名单</button><button type="button" class="btn gray" data-roster-close>关闭</button></div>', { noFoot: true, wide: true, onClose: dispose });
  if (!mask) { alive = false; return inert(); }
  node('refresh').onclick = refresh; node('receive').onclick = receive; node('close').onclick = destroy;
  node('search').oninput = event => { if (!checkCurrent() || posting || reading || !snapshot) return; query = event.target.value; page = 0; draw(); };
  node('previous').onclick = () => { if (checkCurrent() && !reading && !posting && page > 0) { page--; draw(); } };
  node('next').onclick = () => { if (checkCurrent() && !reading && !posting && snapshot && (page + 1) * 20 < filtered().length) { page++; draw(); } };
  host.signal?.addEventListener('abort', destroy, { once: true });
  return { ready: refresh(), refresh, destroy };
}
