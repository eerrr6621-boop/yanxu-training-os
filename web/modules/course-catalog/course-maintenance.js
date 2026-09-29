// Course-only editor. The server retains teachers, certifications and bindings.
const SIZE = 20;
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const integer = n => Number.isSafeInteger(n) && n >= 0;
const code = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(value);
const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const validCourses = list => Array.isArray(list) && list.length <= 5000 && new Set(list.map(row => row?.course_code)).size === list.length && list.every(row => row && Object.keys(row).sort().join(',') === 'active,course_code,course_name' && code(row.course_code) && typeof row.course_name === 'string' && row.course_name.length > 0 && row.course_name.length <= 200 && typeof row.active === 'boolean');
const retainedEmpty = changes => changes && ['teachers', 'certifications', 'bindings'].every(key => changes[key] && ['added', 'updated', 'removed'].every(op => Array.isArray(changes[key][op]) && changes[key][op].length === 0));
function differences(before, after) {
  const original = new Map(before.map(row => [row.course_code, row]));
  return after.flatMap(row => {
    const old = original.get(row.course_code);
    return !old ? [{ course_code: row.course_code, kind: '新增', before: '尚无此课程', after: `${row.course_name} · ${row.active ? '启用' : '停用'}` }]
      : old.course_name !== row.course_name || old.active !== row.active ? [{ course_code: row.course_code, kind: [old.course_name !== row.course_name ? '改名' : '', old.active !== row.active ? (row.active ? '启用' : '停用') : ''].filter(Boolean).join('、'), before: `${old.course_name} · ${old.active ? '启用' : '停用'}`, after: `${row.course_name} · ${row.active ? '启用' : '停用'}` }] : [];
  });
}
function errorText(error) {
  const status = Number(error?.status || error?.code);
  if (status === 401) return '登录状态已失效，请重新登录。';
  if (status === 403) return '当前已无课程维护权限，请重新核对授权。';
  if (status === 409) return '目录版本或师资依据已变化，请刷新后核对再填写。' + (error?.message ? ` ${error.message}` : '');
  return error?.message || '暂时无法完成，请稍后重试。';
}
export function mountCourseMaintenance(root, context, scope) {
  const doc = root.ownerDocument, identity = context.getUser?.();
  const url = `/course-catalog/scopes/${scope.scope_id}`;
  let alive = true, epoch = 0, controller, current = null, courses = [], catalogVersion = '', comment = '', page = 0;
  let busy = false, preview = null, unknown = false, leaveAction = null;
  const node = (tag, value, className) => { const el = doc.createElement(tag); if (value !== undefined) el.textContent = value; if (className) el.className = className; return el; };
  const wrap = node('section'); wrap.setAttribute('data-course-maintenance', ''); wrap.setAttribute('aria-label', '维护课程');
  const intro = node('p', '可新增课程、修改名称或启停。已有课程编码保持不变，停用课程仍保留原认证记录。');
  const notice = node('p'); notice.setAttribute('role', 'status'); notice.setAttribute('data-maintenance-notice', '');
  const summary = node('p'), form = node('div'), rowsArea = node('div'), result = node('div'), guard = node('div'); guard.setAttribute('role', 'status');
  const actions = node('div', undefined, 'toolbar'); actions.style.flexWrap = 'wrap';
  const button = (label, action) => { const b = node('button', label, 'btn gray sm'); b.type = 'button'; b.dataset.maintenanceAction = action; return b; };
  const refreshButton = button('刷新维护资料', 'refresh'), addButton = button('新增课程', 'add'), previewButton = button('保存预览', 'preview'), confirmButton = button('确认保存', 'confirm');
  previewButton.className = confirmButton.className = 'btn sm'; actions.append(addButton, previewButton, confirmButton, refreshButton);
  wrap.append(node('h3', '维护课程'), intro, notice, summary, form, rowsArea, actions, result, guard); root.append(wrap);
  const active = () => alive && !context.signal?.aborted && (context.isCurrent?.() ?? true) && (!context.getUser || context.getUser() === identity);
  function permitted() { if (active()) return true; destroy(); return false; }
  const dirty = () => !!current && (!same(courses, current.courses) || catalogVersion !== (current.catalog_version || '') || comment !== '') || !!preview || unknown;
  const changed = () => !!current && differences(current.courses, courses).length > 0;
  function controls() {
    const locked = busy || unknown || !current;
    for (const field of wrap.querySelectorAll('[data-maintenance-field]')) field.disabled = locked || field.dataset.locked === 'true';
    addButton.disabled = locked || courses.length >= 5000; previewButton.disabled = locked || !changed();
    for (const b of wrap.querySelectorAll('[data-maintenance-action="previous"], [data-maintenance-action="next"]')) b.disabled = locked || (b.dataset.maintenanceAction === 'previous' ? page === 0 : (page + 1) * SIZE >= courses.length);
    confirmButton.disabled = busy || !preview?.ready; confirmButton.style.display = preview?.ready ? '' : 'none';
    confirmButton.textContent = unknown ? '重试确认本次保存' : '确认保存'; refreshButton.disabled = busy;
    for (const b of wrap.querySelectorAll('[data-maintenance-remove]')) b.disabled = locked;
  }
  function invalidate() { preview = null; unknown = false; result.replaceChildren(); guard.replaceChildren(); leaveAction = null; controls(); }
  function clear() { current = null; courses = []; catalogVersion = comment = ''; preview = null; unknown = false; form.replaceChildren(); rowsArea.replaceChildren(); summary.textContent = ''; result.replaceChildren(); guard.replaceChildren(); leaveAction = null; controls(); }
  function renderRows() {
    page = Math.max(0, Math.min(page, Math.ceil(courses.length / SIZE) - 1));
    const originalCount = current.courses.length;
    const visible = courses.slice(page * SIZE, (page + 1) * SIZE).map((row, index) => ({ ...row, index: page * SIZE + index }));
    rowsArea.innerHTML = context.renderTable([
      { l: '课程编码', render: row => `<div class="form-item" style="min-width:0;width:100%"><input aria-label="课程编码 ${row.index + 1}" data-maintenance-field="course_code" data-row="${row.index}" ${row.index < originalCount ? 'data-locked="true" disabled' : ''} value="${esc(row.course_code)}" maxlength="64" style="width:100%;min-width:0;box-sizing:border-box"></div>` },
      { l: '课程名称', render: row => `<div class="form-item" style="min-width:0;width:100%"><input aria-label="课程名称 ${row.index + 1}" data-maintenance-field="course_name" data-row="${row.index}" value="${esc(row.course_name)}" maxlength="200" style="width:100%;min-width:0;box-sizing:border-box"></div>` },
      { l: '启用状态', render: row => `<div class="form-item" style="min-width:0;width:100%"><select aria-label="启用状态 ${row.index + 1}" data-maintenance-field="active" data-row="${row.index}" style="min-width:0;width:100%;max-width:100%"><option value="true" ${row.active ? 'selected' : ''}>启用</option><option value="false" ${!row.active ? 'selected' : ''}>停用</option></select></div>` },
      { l: '行操作', render: row => row.index < originalCount ? '已有编码不可删除' : `<button class="btn gray sm" type="button" data-maintenance-remove="${row.index}">移除新行</button>` },
    ], visible, [], '暂无课程，请新增。');
    const nav = node('div', undefined, 'toolbar'); nav.style.flexWrap = 'wrap';
    const prev = button('上一页', 'previous'), next = button('下一页', 'next'); prev.disabled = page === 0; next.disabled = (page + 1) * SIZE >= courses.length;
    nav.append(node('span', courses.length ? `${page * SIZE + 1}–${Math.min((page + 1) * SIZE, courses.length)} / ${courses.length} 门` : '0 门课程'), prev, next); rowsArea.append(nav); controls();
  }
  function renderCurrent() {
    summary.textContent = `已保存版本：${current.version}；保留 ${current.retained_counts.teachers} 位师资、${current.retained_counts.certifications} 条认证、${current.retained_counts.bindings} 条档案关联。`;
    form.innerHTML = context.renderForm([{ k: 'catalog_version', label: '版本名称', required: true }, { k: 'change_comment', label: '修改说明', type: 'textarea', required: true }], { catalog_version: catalogVersion, change_comment: comment });
    for (const key of ['catalog_version', 'change_comment']) form.querySelector(`[data-k="${key}"]`).dataset.maintenanceField = key;
    renderRows(); controls();
  }
  function validCurrent(value) {
    return value && value.scope_id === scope.scope_id && value.organization_code === scope.organization_code && integer(value.version) && value.can_manage === true && validCourses(value.courses) && value.retained_counts && ['teachers', 'certifications', 'bindings'].every(key => integer(value.retained_counts[key])) &&
      (value.version === 0 ? value.catalog_version === null && value.courses.length === 0 && Object.values(value.retained_counts).every(n => n === 0) : typeof value.catalog_version === 'string' && value.catalog_version.length > 0);
  }
  async function refresh(afterSave = false) {
    if (!permitted()) return false;
    const id = ++epoch; controller?.abort(); controller = new AbortController(); busy = true; clear(); notice.textContent = afterSave ? '已保存，正在刷新课程资料…' : '正在读取课程维护资料…'; controls();
    try {
      const value = await context.api(`${url}/course-management`, { quiet: true, signal: controller.signal });
      if (!permitted() || epoch !== id) return false;
      if (!validCurrent(value)) throw new Error('维护资料不完整或与当前课程空间不一致。');
      current = value; courses = value.courses.map(row => ({ ...row })); catalogVersion = value.catalog_version || ''; comment = ''; page = 0; renderCurrent();
      notice.textContent = afterSave ? '已保存，课程资料已刷新。' : value.version === 0 ? '此空间尚无已保存课程，可新增首批课程。' : '修改课程后，请填写新的版本名称和修改说明，再保存预览。'; return true;
    } catch (error) { if (permitted() && id === epoch && error?.name !== 'AbortError') { clear(); notice.textContent = `${afterSave ? '已保存，当前列表刷新失败，请刷新。 ' : ''}${errorText(error)}`; if ([401, 403].includes(Number(error?.status || error?.code))) context.onAccessLost?.(); } return false; }
    finally { if (active() && epoch === id) { busy = false; controls(); } }
  }
  function validPreview(value, body) {
    if (!value || value.schema_version !== 'm04_preview_v1' || value.scope_id !== scope.scope_id || value.organization_code !== scope.organization_code || value.current_version !== current.version || value.catalog_version !== body.catalog_version || typeof value.ready !== 'boolean' || !Array.isArray(value.issues)) return false;
    if (!value.ready) return value.batch_id === null && value.confirmation_required === false && value.changes === null && value.data === null;
    if (!uuid(value.batch_id) || value.confirmation_required !== true || value.issues.some(issue => issue?.severity === 'error') || !retainedEmpty(value.changes) || !validCourses(value.data?.courses) || !same(value.data.courses, body.courses) || value.data.catalog_version !== body.catalog_version) return false;
    const delta = differences(current.courses, body.courses), added = delta.filter(row => row.kind === '新增').map(row => row.course_code), updated = delta.filter(row => row.kind !== '新增').map(row => row.course_code);
    const changes = value.changes.courses;
    return delta.length > 0 && changes && same(changes.added, added) && same(changes.updated, updated) && same(changes.removed, []) && value.counts?.courses === body.courses.length && value.counts?.teachers === current.retained_counts.teachers && value.counts?.certifications === current.retained_counts.certifications;
  }
  async function savePreview() {
    if (!permitted() || busy || unknown || !current || !changed()) return false;
    if (!catalogVersion.trim() || catalogVersion.length > 120 || catalogVersion === current.catalog_version || !comment.trim() || comment.length > 500) { notice.textContent = '请填写新的版本名称（最多 120 字）及修改说明（最多 500 字）。'; return false; }
    invalidate(); const id = ++epoch; controller?.abort(); controller = new AbortController(); busy = true; controls(); notice.textContent = '正在保存预览…';
    const body = { expected_version: current.version, catalog_version: catalogVersion, courses: courses.map(row => ({ ...row })), change_comment: comment };
    try {
      const value = await context.api(`${url}/courses-preview`, { method: 'POST', body, quiet: true, signal: controller.signal });
      if (!permitted() || id !== epoch) return false;
      if (!validPreview(value, body)) throw new Error('预览内容无法与本次课程修改核实，请重新保存预览。');
      if (!value.ready) { notice.textContent = '预览未通过校验，请修改下列问题后再保存预览。'; result.innerHTML = context.renderTable([{ k: 'message', l: '校验问题' }], value.issues, [], ''); return false; }
      preview = value; notice.textContent = '预览已保存，尚未生效。请核对以下课程变化，再确认保存。';
      result.innerHTML = (value.issues.length ? context.renderTable([{ k: 'message', l: '核对提示' }], value.issues, [], '') : '') + context.renderTable([{ k: 'course_code', l: '课程编码' }, { k: 'kind', l: '变化' }, { k: 'before', l: '保存前' }, { k: 'after', l: '确认后' }], differences(current.courses, body.courses), [], ''); return true;
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
      preview = null; unknown = false; current = null; courses = []; result.replaceChildren(); controls();
      // A confirmed write is known even if either subsequent read fails.
      let hostFailed = false;
      try { await context.onSaved?.({ scopeId: scope.scope_id, version: value.version }); } catch { hostFailed = true; }
      if (!permitted() || id !== epoch) return false;
      const refreshed = await refresh(true); if (active() && hostFailed && refreshed) notice.textContent = '已保存，课程维护资料已刷新；只读目录刷新失败，请重新读取。'; return true;
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
    guard.replaceChildren(node('p', unknown ? '本次确认结果尚未核实。离开后请刷新核对是否已保存。' : '当前有未确认的课程修改，是否放弃并继续？'), button('继续编辑', 'stay'), button('放弃并继续', 'discard'));
    guard.scrollIntoView?.({ block: 'nearest' }); return false;
  }
  function edit(event) {
    if (!permitted() || busy || unknown || !current) return;
    const field = event.target.dataset?.maintenanceField; if (!field || event.target.dataset.locked === 'true') return;
    if (['catalog_version', 'change_comment'].includes(field)) { if (field === 'catalog_version') catalogVersion = event.target.value; else comment = event.target.value; }
    else { const row = courses[Number(event.target.dataset.row)]; if (!row) return; row[field] = field === 'active' ? event.target.value === 'true' : event.target.value; }
    invalidate(); notice.textContent = changed() ? '内容已更改，请重新保存预览后确认。' : '请先新增课程、修改名称或启停；仅修改版本名称或说明不能保存。';
  }
  function click(event) {
    if (!permitted()) return;
    let target = event.target; while (target && target !== wrap && !target.dataset?.maintenanceAction && target.dataset?.maintenanceRemove === undefined) target = target.parentNode;
    if (!target || target.disabled) return;
    const action = target.dataset?.maintenanceAction, removal = target.dataset?.maintenanceRemove; if (!action && removal === undefined) return; event.preventDefault();
    if (action === 'stay') { guard.replaceChildren(); leaveAction = null; return; }
    if (action === 'discard') { const next = leaveAction; clear(); busy = false; ++epoch; controller?.abort(); next?.(); return; }
    if (action === 'refresh') { if (requestLeave(() => refresh())) return refresh(); return; }
    if (action === 'previous' || action === 'next') { if (busy || unknown || !current) return; page += action === 'next' ? 1 : -1; renderRows(); return; }
    if (action === 'preview') return savePreview(); if (action === 'confirm') return confirm();
    if (busy || unknown || !current) return;
    if (action === 'add') { courses.push({ course_code: '', course_name: '', active: true }); page = Math.floor((courses.length - 1) / SIZE); invalidate(); renderRows(); }
    if (removal !== undefined && Number(removal) >= current.courses.length) { courses.splice(Number(removal), 1); invalidate(); renderRows(); }
  }
  wrap.addEventListener('click', click); wrap.addEventListener('input', edit); wrap.addEventListener('change', edit);
  function destroy() { if (!alive) return; alive = false; epoch++; controller?.abort(); context.signal?.removeEventListener('abort', destroy); wrap.removeEventListener('click', click); wrap.removeEventListener('input', edit); wrap.removeEventListener('change', edit); clear(); wrap.remove(); }
  context.signal?.addEventListener('abort', destroy, { once: true });
  return { ready: refresh(), refresh, requestLeave, destroy };
}
