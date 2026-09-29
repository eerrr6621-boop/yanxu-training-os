// Certification-only editor; teacher/course choices come from current trusted bindings.
const SIZE = 20;
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const integer = n => Number.isSafeInteger(n) && n >= 0;
const code = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(value);
const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const STATUS = { unknown: '认证未知', certified: '已认证', not_certified: '未认证', revoked: '已撤销' };
const CERT_KEYS = ['teacher_code', 'course_code', 'status', 'source_ref', 'valid_from', 'valid_to'];
const keyOf = row => row.teacher_code + '/' + row.course_code;
const sameCerts = (a, b) => a.length === b.length && a.every((row, i) => CERT_KEYS.every(key => row[key] === b[i][key]));
const validDate = value => typeof value === 'string' && (value === '' || /^\d{4}-\d{2}-\d{2}$/.test(value) && !value.startsWith('0000') && !Number.isNaN(Date.parse(value + 'T00:00:00Z')) && new Date(value + 'T00:00:00Z').toISOString().slice(0, 10) === value);
const validCourses = list => Array.isArray(list) && list.length <= 5000 && new Set(list.map(row => row?.course_code)).size === list.length && list.every(row => row && code(row.course_code) && typeof row.course_name === 'string' && row.course_name.length > 0 && row.course_name.length <= 200 && typeof row.active === 'boolean');
const validCerts = (list, teachers, courses) => Array.isArray(list) && list.length <= 5000 && new Set(list.map(row => row && keyOf(row))).size === list.length && list.every(row => row && Object.keys(row).sort().join(',') === [...CERT_KEYS].sort().join(',') && teachers.includes(row.teacher_code) && courses.some(course => course.course_code === row.course_code) && Object.hasOwn(STATUS, row.status) && typeof row.source_ref === 'string' && row.source_ref.length <= 240 && validDate(row.valid_from) && validDate(row.valid_to) && (!row.valid_from || !row.valid_to || row.valid_from <= row.valid_to) && (row.status !== 'certified' || row.source_ref.length > 0));
const retainedEmpty = changes => changes && ['courses', 'teachers', 'bindings'].every(key => changes[key] && ['added', 'updated', 'removed'].every(op => Array.isArray(changes[key][op]) && changes[key][op].length === 0));
const describe = row => row ? [STATUS[row.status] || row.status, '认定来源：' + (row.source_ref || '未提供'), '有效起日：' + (row.valid_from || '未提供'), '有效止日：' + (row.valid_to || '未提供')].join('；') : '尚无此认证记录';
function differences(before, after) {
  const original = new Map(before.map(row => [keyOf(row), row]));
  return after.flatMap(row => { const old = original.get(keyOf(row)); return !old || !CERT_KEYS.every(key => old[key] === row[key]) ? [{ pair_code: keyOf(row), kind: old ? '修改' : '新增', before: describe(old), after: describe(row) }] : []; });
}
function errorText(error) {
  const status = Number(error?.status || error?.code);
  if (status === 401) return '登录状态已失效，请重新登录。';
  if (status === 403) return '当前已无认证维护权限，请重新核对授权。';
  if (status === 409) return '目录版本或师资依据已变化，请刷新后核对再填写。' + (error?.message ? ` ${error.message}` : '');
  return error?.message || '暂时无法完成，请稍后重试。';
}
export function mountCertificationMaintenance(root, context, scope) {
  const doc = root.ownerDocument, identity = context.getUser?.();
  const url = `/course-catalog/scopes/${scope.scope_id}`;
  let alive = true, epoch = 0, controller, current = null, certifications = [], catalogVersion = '', comment = '', page = 0;
  let busy = false, preview = null, unknown = false, leaveAction = null, picker = null;
  const node = (tag, value, className) => { const el = doc.createElement(tag); if (value !== undefined) el.textContent = value; if (className) el.className = className; return el; };
  const wrap = node('section'); wrap.setAttribute('data-certification-maintenance', ''); wrap.setAttribute('aria-label', '维护认证');
  const intro = node('p', '仅维护已绑定教师的具体课程认证。原配对不可删除，取消认定请改为已撤销；日期留空表示该边界未提供。已认证仍需满足课程启用、教师在库及有效期规则。');
  const notice = node('p'); notice.setAttribute('role', 'status'); notice.setAttribute('data-certification-notice', '');
  const summary = node('p'), form = node('div'), rowsArea = node('div'), pickerArea = node('div'), result = node('div'), guard = node('div'); guard.setAttribute('role', 'status');
  const actions = node('div', undefined, 'toolbar'); actions.style.flexWrap = 'wrap';
  const button = (label, action) => { const b = node('button', label, 'btn gray sm'); b.type = 'button'; b.dataset.certificationAction = action; return b; };
  const refreshButton = button('刷新维护资料', 'refresh'), addButton = button('新增认证', 'add'), previewButton = button('保存预览', 'preview'), confirmButton = button('确认保存', 'confirm');
  previewButton.className = confirmButton.className = 'btn sm'; actions.append(addButton, previewButton, confirmButton, refreshButton);
  wrap.append(node('h3', '维护认证'), intro, notice, summary, form, rowsArea, pickerArea, actions, result, guard); root.append(wrap);
  const active = () => alive && !context.signal?.aborted && (context.isCurrent?.() ?? true) && (!context.getUser || context.getUser() === identity);
  function permitted() { if (active()) return true; destroy(); return false; }
  const dirty = () => !!current && (!same(certifications, current.certifications) || catalogVersion !== (current.catalog_version || '') || comment !== '') || !!preview || unknown;
  const changed = () => !!current && differences(current.certifications, certifications).length > 0;
  function controls() {
    const locked = busy || unknown || !current;
    for (const field of wrap.querySelectorAll('[data-certification-field]')) field.disabled = locked || field.dataset.locked === 'true';
    addButton.disabled = locked || certifications.length >= 5000 || !current?.teacher_codes.length || !current?.courses.length; previewButton.disabled = locked || !changed();
    for (const b of wrap.querySelectorAll('[data-certification-action="previous"], [data-certification-action="next"]')) b.disabled = locked || (b.dataset.certificationAction === 'previous' ? page === 0 : (page + 1) * SIZE >= certifications.length);
    confirmButton.disabled = busy || !preview?.ready; confirmButton.style.display = preview?.ready ? '' : 'none';
    confirmButton.textContent = unknown ? '重试确认本次保存' : '确认保存'; refreshButton.disabled = busy;
    for (const b of wrap.querySelectorAll('[data-certification-remove], [data-certification-pick], [data-certification-choice]')) b.disabled = locked;
  }
  function invalidate() { picker = null; pickerArea.replaceChildren(); preview = null; unknown = false; result.replaceChildren(); guard.replaceChildren(); leaveAction = null; controls(); }
  function clear() { picker = null; pickerArea.replaceChildren(); current = null; certifications = []; catalogVersion = comment = ''; preview = null; unknown = false; form.replaceChildren(); rowsArea.replaceChildren(); summary.textContent = ''; result.replaceChildren(); guard.replaceChildren(); leaveAction = null; controls(); }
  function renderRows() {
    page = Math.max(0, Math.min(page, Math.ceil(certifications.length / SIZE) - 1));
    const originalCount = current.certifications.length;
    const visible = certifications.slice(page * SIZE, (page + 1) * SIZE).map((row, index) => ({ ...row, index: page * SIZE + index }));
    const input = (row, field, label, type = 'text', max = 240) => `<div class="form-item" style="min-width:0;width:100%"><input type="${type}" aria-label="${label} ${row.index + 1}" data-certification-field="${field}" data-row="${row.index}" ${row.index < originalCount && ['teacher_code', 'course_code'].includes(field) ? 'data-locked="true" disabled' : ''} value="${esc(row[field])}" maxlength="${max}" style="width:100%;min-width:0;max-width:100%;box-sizing:border-box;${type === 'date' ? 'font-size:12px;padding:6px 2px;' : ''}">${row.index >= originalCount && ['teacher_code', 'course_code'].includes(field) ? `<button class="btn gray sm" type="button" data-certification-pick="${field}:${row.index}">选择${field === 'teacher_code' ? '教师' : '课程'}</button>` : ''}</div>`;
    rowsArea.innerHTML = context.renderTable([
      { l: '教师编码', render: row => input(row, 'teacher_code', '教师编码', 'text', 64) },
      { l: '具体课程编码', render: row => input(row, 'course_code', '具体课程编码', 'text', 64) },
      { l: '认证状态', render: row => `<div class="form-item" style="min-width:0;width:100%"><select aria-label="认证状态 ${row.index + 1}" data-certification-field="status" data-row="${row.index}" style="min-width:0;width:100%;max-width:100%;font-size:13px;padding:6px">${Object.entries(STATUS).map(([value, label]) => `<option value="${value}" ${row.status === value ? 'selected' : ''}>${label}</option>`).join('')}</select></div>` },
      { l: '认定来源', render: row => input(row, 'source_ref', '认定来源') },
      { l: '有效期', render: row => `<div style="min-width:0"><small>起日</small>${input(row, 'valid_from', '有效起日', 'date', 10)}<small>止日</small>${input(row, 'valid_to', '有效止日', 'date', 10)}</div>` },
      { l: '行操作', render: row => row.index < originalCount ? '原组合保留' : `<button class="btn gray sm" type="button" data-certification-remove="${row.index}">移除新行</button>` },
    ], visible, [], '暂无认证记录。');
    const nav = node('div', undefined, 'toolbar'); nav.style.flexWrap = 'wrap';
    const prev = button('上一页', 'previous'), next = button('下一页', 'next'); prev.disabled = page === 0; next.disabled = (page + 1) * SIZE >= certifications.length;
    nav.append(node('span', certifications.length ? `${page * SIZE + 1}–${Math.min((page + 1) * SIZE, certifications.length)} / ${certifications.length} 条` : '0 条认证'), prev, next); rowsArea.append(nav); controls();
  }
  function renderPicker(reset = false) {
    if (!picker || !current) return;
    if (reset) {
      pickerArea.replaceChildren(node('h4', '为第 ' + (picker.row + 1) + ' 行选择' + (picker.field === 'teacher_code' ? '教师编码' : '具体课程')));
      const toolbar = node('div', undefined, 'toolbar'); toolbar.style.flexWrap = 'wrap';
      const search = node('input'); search.type = 'search'; search.setAttribute('aria-label', '筛选可选编码'); search.setAttribute('data-certification-search', ''); search.value = picker.search;
      toolbar.append(search, button('收起选择', 'picker-close')); pickerArea.append(toolbar, node('div')); picker.host = pickerArea.children[pickerArea.children.length - 1];
    }
    const list = picker.field === 'teacher_code' ? current.teacher_codes.map(value => ({ value, label: value })) : current.courses.map(row => ({ value: row.course_code, label: row.course_name + (row.active ? ' · 启用' : ' · 停用') }));
    picker.filtered = list.filter(row => (row.value + ' ' + row.label).includes(picker.search));
    picker.page = Math.max(0, Math.min(picker.page, Math.ceil(picker.filtered.length / SIZE) - 1));
    const shown = picker.filtered.slice(picker.page * SIZE, (picker.page + 1) * SIZE).map((row, index) => ({ ...row, index: picker.page * SIZE + index }));
    picker.host.innerHTML = context.renderTable([{ k: 'value', l: '编码' }, { k: 'label', l: '说明' }, { l: '选择', render: row => `<button class="btn gray sm" type="button" data-certification-choice="${row.index}">选择</button>` }], shown, [], '没有匹配的当前编码。');
    const nav = node('div', undefined, 'toolbar'); nav.style.flexWrap = 'wrap';
    const prev = button('上一页候选', 'picker-previous'), next = button('下一页候选', 'picker-next'); prev.disabled = picker.page === 0; next.disabled = (picker.page + 1) * SIZE >= picker.filtered.length;
    nav.append(node('span', '共 ' + picker.filtered.length + ' 个可选编码'), prev, next); picker.host.append(nav); controls();
  }

  function renderCurrent() {
    summary.textContent = `已保存版本：${current.version}；保留 ${current.retained_counts.courses} 门课程、${current.retained_counts.teachers} 位师资、${current.retained_counts.bindings} 条档案关联。`;
    form.innerHTML = context.renderForm([{ k: 'catalog_version', label: '版本名称', required: true }, { k: 'change_comment', label: '修改说明', type: 'textarea', required: true }], { catalog_version: catalogVersion, change_comment: comment });
    for (const key of ['catalog_version', 'change_comment']) form.querySelector(`[data-k="${key}"]`).dataset.certificationField = key;
    renderRows(); controls();
  }
  function validCurrent(value) {
    return value && value.scope_id === scope.scope_id && value.organization_code === scope.organization_code && integer(value.version) && value.can_manage === true && validCourses(value.courses) && Array.isArray(value.teacher_codes) && value.teacher_codes.length <= 5000 && value.teacher_codes.every(code) && new Set(value.teacher_codes).size === value.teacher_codes.length && validCerts(value.certifications, value.teacher_codes, value.courses) && value.retained_counts && ['courses', 'teachers', 'bindings'].every(key => integer(value.retained_counts[key])) && value.retained_counts.courses === value.courses.length && value.retained_counts.teachers === value.teacher_codes.length &&
      (value.version === 0 ? value.catalog_version === null && value.courses.length === 0 && value.teacher_codes.length === 0 && value.certifications.length === 0 && Object.values(value.retained_counts).every(n => n === 0) : typeof value.catalog_version === 'string' && value.catalog_version.length > 0);
  }
  async function refresh(afterSave = false) {
    if (!permitted()) return false;
    const id = ++epoch; controller?.abort(); controller = new AbortController(); busy = true; clear(); notice.textContent = afterSave ? '已保存，正在刷新认证资料…' : '正在读取认证维护资料…'; controls();
    try {
      const value = await context.api(`${url}/certification-management`, { quiet: true, signal: controller.signal });
      if (!permitted() || epoch !== id) return false;
      if (!validCurrent(value)) throw new Error('维护资料不完整或与当前课程空间不一致。');
      current = value; certifications = value.certifications.map(row => ({ ...row })); catalogVersion = value.catalog_version || ''; comment = ''; page = 0; renderCurrent();
      notice.textContent = afterSave ? '已保存，认证资料已刷新。' : !value.teacher_codes.length || !value.courses.length ? '当前没有可配对的已绑定教师或具体课程，暂不能新增认证。' : '修改认证后，请填写新的版本名称和修改说明，再保存预览。'; return true;
    } catch (error) { if (permitted() && id === epoch && error?.name !== 'AbortError') { clear(); notice.textContent = `${afterSave ? '已保存，当前列表刷新失败，请刷新。 ' : ''}${errorText(error)}`; if ([401, 403].includes(Number(error?.status || error?.code))) context.onAccessLost?.(); } return false; }
    finally { if (active() && epoch === id) { busy = false; controls(); } }
  }
  function validPreview(value, body) {
    if (!value || value.schema_version !== 'm04_preview_v1' || value.scope_id !== scope.scope_id || value.organization_code !== scope.organization_code || value.current_version !== current.version || value.catalog_version !== body.catalog_version || typeof value.ready !== 'boolean' || !Array.isArray(value.issues)) return false;
    if (!value.ready) return value.batch_id === null && value.confirmation_required === false && value.changes === null && value.data === null;
    if (!uuid(value.batch_id) || value.confirmation_required !== true || value.issues.some(issue => issue?.severity === 'error') || !retainedEmpty(value.changes) || !validCerts(value.data?.certifications, current.teacher_codes, current.courses) || !sameCerts(value.data.certifications, body.certifications) || value.data.catalog_version !== body.catalog_version) return false;
    const delta = differences(current.certifications, body.certifications), added = delta.filter(row => row.kind === '新增').map(row => row.pair_code), updated = delta.filter(row => row.kind !== '新增').map(row => row.pair_code);
    const changes = value.changes.certifications;
    return delta.length > 0 && changes && same(changes.added, added) && same(changes.updated, updated) && same(changes.removed, []) && value.counts?.certifications === body.certifications.length && value.counts?.teachers === current.retained_counts.teachers && value.counts?.courses === current.retained_counts.courses;
  }
  async function savePreview() {
    if (!permitted() || busy || unknown || !current || !changed()) return false;
    if (!catalogVersion.trim() || catalogVersion.length > 120 || catalogVersion === current.catalog_version || !comment.trim() || comment.length > 500) { notice.textContent = '请填写新的版本名称（最多 120 字）及修改说明（最多 500 字）。'; return false; }
    invalidate(); const id = ++epoch; controller?.abort(); controller = new AbortController(); busy = true; controls(); notice.textContent = '正在保存预览…';
    const body = { expected_version: current.version, catalog_version: catalogVersion, certifications: certifications.map(row => ({ ...row })), change_comment: comment };
    try {
      const value = await context.api(`${url}/certifications-preview`, { method: 'POST', body, quiet: true, signal: controller.signal });
      if (!permitted() || id !== epoch) return false;
      if (!validPreview(value, body)) throw new Error('预览内容无法与本次认证修改核实，请重新保存预览。');
      if (!value.ready) { notice.textContent = '预览未通过校验，请修改下列问题后再保存预览。'; result.innerHTML = context.renderTable([{ k: 'message', l: '校验问题' }], value.issues, [], ''); return false; }
      preview = value; notice.textContent = '预览已保存，尚未生效。请核对以下认证变化，再确认保存。';
      result.innerHTML = (value.issues.length ? context.renderTable([{ k: 'message', l: '核对提示' }], value.issues, [], '') : '') + context.renderTable([{ k: 'pair_code', l: '教师 / 课程编码' }, { k: 'kind', l: '变化' }, { k: 'before', l: '保存前' }, { k: 'after', l: '确认后' }], differences(current.certifications, body.certifications), [], ''); return true;
    } catch (error) {
      if (permitted() && epoch === id && error?.name !== 'AbortError') { const status = Number(error?.status || error?.code); if ([401, 403, 409].includes(status)) clear(); if ([401, 403].includes(status)) context.onAccessLost?.(); notice.textContent = errorText(error); }
      return false;
    } finally { if (active() && id === epoch) { busy = false; controls(); } }
  }
  async function confirm() {
    if (!permitted() || busy || !preview?.ready || !current) return false;
    const pending = preview, version = current.version, id = ++epoch; controller?.abort(); controller = new AbortController(); busy = true; controls(); notice.textContent = '正在确认保存…';
    try {
      context.onConfirming?.();
      const value = await context.api(`${url}/confirm`, { method: 'POST', quiet: true, signal: controller.signal, body: { batch_id: pending.batch_id, expected_version: version, confirm: true } });
      if (!permitted() || id !== epoch) return false;
      if (!value || !['CONFIRMED', 'ALREADY_CONFIRMED'].includes(value.status) || value.scope_id !== scope.scope_id || value.batch_id !== pending.batch_id || value.version !== version + 1 || !integer(value.current_version) || value.current_version < value.version || value.status === 'CONFIRMED' && (value.current_version !== value.version || !retainedEmpty(value.changes))) throw new Error('保存回执未能核实。');
      preview = null; unknown = false; current = null; certifications = []; result.replaceChildren(); controls();
      // A confirmed write is known even if either subsequent read fails.
      let hostFailed = false;
      try { await context.onSaved?.({ scopeId: scope.scope_id, version: value.version }); } catch { hostFailed = true; }
      if (!permitted() || id !== epoch) return false;
      const refreshed = await refresh(true); if (active() && hostFailed && refreshed) notice.textContent = '已保存，认证维护资料已刷新；只读目录刷新失败，请重新读取。'; return true;
    } catch (error) {
      if (permitted() && id === epoch && error?.name !== 'AbortError') {
        const status = Number(error?.status || error?.code);
        if ([400, 401, 403, 404, 409].includes(status)) { clear(); notice.textContent = errorText(error); if ([401, 403].includes(status)) context.onAccessLost?.(); }
        else { unknown = true; notice.textContent = '尚未确认本次保存结果。请重试确认同一次保存，或稍后刷新核对；不会另建预览。'; }
      }
      return false;
    } finally { if (active() && id === epoch) { busy = false; controls(); } }
  }
  function requestLeave(action) {
    if (!permitted()) return true;
    if (!dirty() && !busy) return true;
    leaveAction = action;
    guard.replaceChildren(node('p', unknown ? '本次确认结果尚未核实。离开后请刷新核对是否已保存。' : '当前有未确认的认证修改，是否放弃并继续？'), button('继续编辑', 'stay'), button('放弃并继续', 'discard'));
    guard.scrollIntoView?.({ block: 'nearest' }); return false;
  }
  function edit(event) {
    if (!permitted() || busy || unknown || !current) return;
    if (event.target.hasAttribute?.('data-certification-search') && picker) { picker.search = event.target.value; picker.page = 0; renderPicker(); return; }
    const field = event.target.dataset?.certificationField; if (!field || event.target.dataset.locked === 'true') return;
    if (['catalog_version', 'change_comment'].includes(field)) { if (field === 'catalog_version') catalogVersion = event.target.value; else comment = event.target.value; }
    else { const row = certifications[Number(event.target.dataset.row)]; if (!row) return; if (!CERT_KEYS.includes(field) || Number(event.target.dataset.row) < current.certifications.length && ['teacher_code', 'course_code'].includes(field)) return; row[field] = event.target.value; }
    invalidate(); notice.textContent = changed() ? '内容已更改，请重新保存预览后确认。' : '请先新增或修改认证；仅修改版本名称或说明不能保存。';
  }
  function click(event) {
    if (!permitted()) return;
    let target = event.target; while (target && target !== wrap && !target.dataset?.certificationAction && target.dataset?.certificationRemove === undefined && target.dataset?.certificationPick === undefined && target.dataset?.certificationChoice === undefined) target = target.parentNode;
    if (!target || target.disabled) return;
    const action = target.dataset?.certificationAction, removal = target.dataset?.certificationRemove, pick = target.dataset?.certificationPick, choice = target.dataset?.certificationChoice; if (!action && removal === undefined && pick === undefined && choice === undefined) return; event.preventDefault();
    if (action === 'stay') { guard.replaceChildren(); leaveAction = null; return; }
    if (action === 'discard') { const next = leaveAction; clear(); busy = false; ++epoch; controller?.abort(); next?.(); return; }
    if (action === 'refresh') { if (requestLeave(() => refresh())) return refresh(); return; }
    if (action === 'previous' || action === 'next') { if (busy || unknown || !current) return; picker = null; pickerArea.replaceChildren(); page += action === 'next' ? 1 : -1; renderRows(); return; }
    if (action === 'preview') return savePreview(); if (action === 'confirm') return confirm();
    if (busy || unknown || !current) return;
    if (action === 'picker-close') { picker = null; pickerArea.replaceChildren(); return; }
    if (action === 'picker-previous' || action === 'picker-next') { if (picker) { picker.page += action === 'picker-next' ? 1 : -1; renderPicker(); } return; }
    if (pick !== undefined) { const [field, row] = pick.split(':'); if (!['teacher_code', 'course_code'].includes(field) || Number(row) < current.certifications.length || !certifications[Number(row)]) return; picker = { field, row: Number(row), page: 0, search: '', filtered: [] }; renderPicker(true); pickerArea.scrollIntoView?.({ block: 'nearest' }); return; }
    if (choice !== undefined && picker) { const selected = picker.filtered[Number(choice)]; if (selected && certifications[picker.row]) { certifications[picker.row][picker.field] = selected.value; invalidate(); renderRows(); notice.textContent = '已带入所选编码，请核对状态、来源和日期，再保存预览。'; } return; }
    if (action === 'add') { if (!current.teacher_codes.length || !current.courses.length || certifications.length >= 5000) return; certifications.push({ teacher_code: '', course_code: '', status: 'unknown', source_ref: '', valid_from: '', valid_to: '' }); page = Math.floor((certifications.length - 1) / SIZE); invalidate(); renderRows(); }
    if (removal !== undefined && Number(removal) >= current.certifications.length) { certifications.splice(Number(removal), 1); invalidate(); renderRows(); }
  }
  wrap.addEventListener('click', click); wrap.addEventListener('input', edit); wrap.addEventListener('change', edit);
  function destroy() { if (!alive) return; alive = false; epoch++; controller?.abort(); context.signal?.removeEventListener('abort', destroy); wrap.removeEventListener('click', click); wrap.removeEventListener('input', edit); wrap.removeEventListener('change', edit); clear(); wrap.remove(); }
  context.signal?.addEventListener('abort', destroy, { once: true });
  return { ready: refresh(), refresh, requestLeave, destroy };
}
