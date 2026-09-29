// Explicit, version-bound coding association inside the original financial modal.
// The API owns identities, catalog bindings, captures and all financial facts.
const BASE = '/delivery-settlement/coding';
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const text = value => typeof value === 'string' && value.length > 0;
const integer = value => Number.isSafeInteger(value) && value >= 0;
const positive = value => integer(value) && value > 0;
const nullableText = value => value === null || typeof value === 'string';
const instant = value => text(value) && Number.isFinite(Date.parse(value));
const canonical = value => JSON.stringify(value, (_, item) => object(item) ? Object.fromEntries(Object.keys(item).sort().map(key => [key, item[key]])) : item);
const activityLabels = { TEACHING: '授课', LEGACY: '旧账', SOLO_DEVELOPMENT: '独立开发', JOINT_DEVELOPMENT: '合作开发' };
const catalogLabels = { NOT_CONFIGURED: '目录尚未发布完整课程', TEACHER_UNBOUND: '该教师尚无固定讲师编码绑定', TEACHER_NOT_IN_CATALOG: '该教师的固定编码不在本版目录中', READY: '可核对编码' };
const fail = () => { throw Error('结算编码资料不完整或来源不一致，请重新读取。'); };
function binding(value, teacherId, code) {
  if (!object(value) || value.teacher_id !== teacherId || value.teacher_code !== code || !positive(value.created_version)) fail();
}
function capture(value, teacherId) {
  if (!object(value) || value.schema_version !== 'm04_coding_capture_v1' || !positive(value.catalog_scope_id) || !positive(value.catalog_revision) || !text(value.catalog_organization_code) || !text(value.catalog_version) || !text(value.catalog_digest) || value.teacher_id !== teacherId || !text(value.teacher_code) || !text(value.course_code) || value.course_id !== null) fail();
  binding(value.teacher_binding, teacherId, value.teacher_code);
  if (value.teacher_binding.created_version > value.catalog_revision || !object(value.catalog_teacher) || value.catalog_teacher.teacher_code !== value.teacher_code || !object(value.course) || value.course.course_code !== value.course_code || !text(value.course.course_name) || typeof value.course.active !== 'boolean' || !object(value.revision_audit)) fail();
  return value;
}
function record(value, teacherId) {
  if (!object(value) || !positive(value.version) || !text(value.record_code) || !instant(value.confirmed_at) || !positive(value.account_id) || !text(value.actor_code) || !text(value.evidence_note) || !nullableText(value.evidence_reference) || !nullableText(value.reason_note) || !nullableText(value.reason_reference)) fail();
  capture(value.capture, teacherId);
  if (value.version > 1 && !text(value.reason_note)) fail();
  return value;
}
function validateView(value, reference, previous) {
  if (!object(value) || value.chain_code !== reference.chain_code || value.project_id !== reference.project_id || value.teacher_id !== reference.teacher_id || !text(value.organization_code) || typeof value.teacher_name !== 'string' || !Object.hasOwn(activityLabels, value.activity) || !text(value.current_entry_code) || !integer(value.version) || !['NOT_LINKED', 'LINKED'].includes(value.status) || !Array.isArray(value.history) || !object(value.capabilities) || ['can_preview', 'can_confirm', 'can_correct'].some(key => typeof value.capabilities[key] !== 'boolean')) fail();
  if (previous && value.organization_code !== previous.organization_code) fail();
  value.history.forEach(item => record(item, value.teacher_id));
  if (new Set(value.history.map(item => item.version)).size !== value.history.length || new Set(value.history.map(item => item.record_code)).size !== value.history.length || value.history.some(item => item.version > value.version)) fail();
  if (value.status === 'NOT_LINKED') { if (value.version !== 0 || value.current !== null || value.history.length) fail(); }
  else {
    record(value.current, value.teacher_id);
    if (value.current.version !== value.version || value.history.length !== value.version - 1 || value.history.some(item => item.version >= value.version)) fail();
  }
  return value;
}
function validateOptions(value, view, scopeId) {
  if (!object(value) || value.chain_code !== view.chain_code || value.project_id !== view.project_id || value.teacher_id !== view.teacher_id) fail();
  const c = value.catalog;
  if (!object(c) || c.catalog_scope_id !== scopeId || c.teacher_id !== view.teacher_id || !text(c.catalog_organization_code) || !integer(c.catalog_revision) || !nullableText(c.catalog_version) || !text(c.catalog_digest) || !Object.hasOwn(catalogLabels, c.status) || typeof c.can_capture !== 'boolean' || !Array.isArray(c.courses) || c.courses.some(row => !object(row) || !text(row.course_code) || !text(row.course_name) || typeof row.active !== 'boolean') || new Set(c.courses.map(row => row.course_code)).size !== c.courses.length) fail();
  if (c.teacher_binding !== null) binding(c.teacher_binding, view.teacher_id, c.teacher_code);
  else if (c.teacher_code !== null) fail();
  if (c.can_capture && (c.status !== 'READY' || !positive(c.catalog_revision) || !text(c.catalog_version) || !text(c.teacher_code) || !c.teacher_binding)) fail();
  return c;
}
function validatePreview(value, pending, view, catalog) {
  const body = JSON.parse(pending.json);
  if (!object(value) || !text(value.preview_code) || !instant(value.expires_at) || value.chain_code !== view.chain_code || value.version !== body.expected_version || value.expected_entry_code !== body.expected_entry_code || typeof value.can_confirm !== 'boolean') fail();
  const c = capture(value.candidate, view.teacher_id);
  if (c.catalog_scope_id !== body.scope_id || c.catalog_revision !== body.expected_catalog_version || c.catalog_digest !== catalog.catalog_digest || c.teacher_code !== catalog.teacher_code || c.course_code !== body.course_code || value.evidence_note !== body.evidence_note || (value.evidence_reference ?? null) !== (body.evidence_reference ?? null) || (value.reason_note ?? null) !== (body.reason_note ?? null) || (value.reason_reference ?? null) !== (body.reason_reference ?? null)) fail();
  return value;
}

export function mountCodingWorkspace(root, host) {
  for (const key of ['api', 'getUser', 'getModal', 'isCurrent']) if (typeof host?.[key] !== 'function') throw TypeError('Coding host missing ' + key);
  const doc = root?.ownerDocument || host.document;
  if (!root || !doc?.createElement) throw TypeError('Original financial host required');
  const identity = host.getUser(), modal = host.getModal(), section = doc.createElement('section');
  section.className = 'panel'; section.setAttribute('data-coding-workspace', ''); root.appendChild(section);
  let alive = true, references = [], session = null, blocked = false;
  const active = () => alive && !host.signal?.aborted && host.getUser() === identity && host.getModal() === modal && host.isCurrent() && root.isConnected !== false;
  const owns = s => active() && session === s && !s.dead;
  const q = key => section.querySelector('[data-coding-' + key + ']');
  const ensure = s => { if (owns(s)) return true; if (!active()) cleanup(); return false; };
  const fresh = (s, ticket) => ensure(s) && s.ticket === ticket;
  const say = message => { if (active() && q('notice')) q('notice').textContent = message; };
  const status = error => Number(error?.status || error?.code) || 0;
  const isLocked = () => !!session && (!!session.busy || !!session.pending);
  const hasWork = () => !!session && (!!session.editing || !!session.preview || isLocked());
  const canEdit = s => !!s.view?.capabilities.can_preview && (s.view.status !== 'LINKED' || s.view.capabilities.can_correct);
  const uuid = () => { const crypto = doc.defaultView?.crypto ?? globalThis.crypto; if (!crypto?.randomUUID) throw Error('当前浏览器无法生成安全请求号。'); return crypto.randomUUID(); };
  const changed = () => host.onStateChanged?.();
  section.innerHTML = '<div class="panel-head"><h3>结算编码</h3></div><p class="modal-intro">结算编码用于账目归类；课程与讲师资格仍按原流程核验。关联历史保留当时目录版本。</p><div data-coding-chains></div><p data-coding-notice role="status" aria-live="polite"></p><div data-coding-guard></div><div data-coding-detail></div>';
  function chainList() {
    section.hidden = references.length === 0;
    q('chains').innerHTML = references.length ? '<div class="table-wrap"><table class="tbl"><thead><tr><th scope="col">已确认账目</th><th scope="col">操作</th></tr></thead><tbody>' + references.map(ref => '<tr><td data-label="已确认账目">' + esc(ref.teacher_name || '教师档案 #' + ref.teacher_id) + (ref.activity ? ' · ' + esc(activityLabels[ref.activity] || ref.activity) : '') + (ref.current_service_date ? ' · ' + esc(ref.current_service_date) : '') + '<details><summary>账目编号</summary><span style="overflow-wrap:anywhere">' + esc(ref.chain_code) + '</span></details></td><td data-label="操作"><button type="button" class="btn gray" data-coding-open="' + esc(ref.chain_code) + '">核对结算编码</button></td></tr>').join('') + '</tbody></table></div>' : '';
    for (const button of section.querySelectorAll('[data-coding-open]')) button.onclick = () => open(button.dataset.codingOpen);
    controls();
  }
  function controls() {
    if (!active()) return;
    const s = session, locked = isLocked(), unavailable = blocked || locked;
    for (const button of section.querySelectorAll('[data-coding-open]')) button.disabled = unavailable;
    if (!s) return;
    if (q('read')) q('read').disabled = locked;
    if (q('start')) { const visible = !!s.view && canEdit(s); q('start').hidden = !visible; q('start').style.display = visible ? '' : 'none'; q('start').disabled = unavailable || !s.view || s.editing; }
    for (const field of section.querySelectorAll('[data-coding-input]')) field.disabled = unavailable || !s.view || !canEdit(s) || field.dataset.codingInput === 'course_code' && !s.catalog?.can_capture;
    if (q('scope')) q('scope').disabled = unavailable || !s.scopes.length || !canEdit(s);
    if (q('load-scopes')) q('load-scopes').disabled = unavailable || !s.view || !canEdit(s);
    if (q('preview')) q('preview').disabled = unavailable || !canEdit(s) || !s.catalog?.can_capture;
    if (q('confirm')) { const visible = !!s.view?.capabilities.can_confirm && !!s.preview?.can_confirm; q('confirm').hidden = !visible; q('confirm').style.display = visible ? '' : 'none'; q('confirm').disabled = unavailable || !visible || Date.parse(s.preview.expires_at) <= Date.now(); }
    if (q('cancel')) q('cancel').disabled = locked;
    if (q('retry')) { q('retry').hidden = !s.pending; q('retry').style.display = s.pending ? '' : 'none'; q('retry').disabled = !!s.busy || blocked; }
  }
  function source(c) {
    return '<div class="table-wrap"><table class="tbl"><tbody><tr><th scope="row">正式讲师编码</th><td>' + esc(c.teacher_code) + ' · 档案 #' + c.teacher_id + '</td></tr><tr><th scope="row">正式课程</th><td>' + esc(c.course.course_name) + '（' + esc(c.course_code) + '）' + (c.course.active ? '' : ' · 该来源版本已停用') + '</td></tr><tr><th scope="row">来源目录</th><td>' + esc(c.catalog_organization_code) + ' · 目录 #' + c.catalog_scope_id + ' · 修订 ' + c.catalog_revision + ' · 材料版本 ' + esc(c.catalog_version) + '</td></tr><tr><th scope="row">固定绑定</th><td>建立于目录修订 ' + c.teacher_binding.created_version + '</td></tr></tbody></table></div><details><summary>查看来源摘要</summary><p style="overflow-wrap:anywhere">' + esc(c.catalog_digest) + '</p></details>';
  }
  function evidence(r) {
    return '<p style="white-space:pre-line;overflow-wrap:anywhere">核对说明：' + esc(r.evidence_note) + (r.evidence_reference ? '\n材料位置：' + esc(r.evidence_reference) : '') + (r.reason_note ? '\n更正原因：' + esc(r.reason_note) : '') + (r.reason_reference ? '\n更正依据：' + esc(r.reason_reference) : '') + '</p>';
  }
  function viewMarkup(v) {
    const records = [...v.history, ...(v.current ? [v.current] : [])];
    return '<p class="inline-note">' + esc(v.teacher_name || '教师档案 #' + v.teacher_id) + ' · ' + esc(activityLabels[v.activity]) + ' · ' + (v.status === 'LINKED' ? '已关联，第 ' + v.version + ' 版' : '尚未关联结算编码') + '</p><details><summary>当前账目来源</summary><p style="overflow-wrap:anywhere">项目 #' + v.project_id + ' · ' + esc(v.organization_code) + '\n账链：' + esc(v.chain_code) + '\n当前账目：' + esc(v.current_entry_code) + '</p></details>' + (v.current ? source(v.current.capture) + evidence(v.current) : '') + (records.length ? '<details data-coding-history><summary>编码关联历史（' + records.length + ' 次）</summary>' + records.sort((a, b) => b.version - a.version).map(r => '<div class="section-title">第 ' + r.version + ' 版 · ' + esc(r.confirmed_at) + '</div><p style="overflow-wrap:anywhere">经办 ' + esc(r.actor_code) + ' · 账号 #' + r.account_id + ' · 记录 ' + esc(r.record_code) + '</p>' + source(r.capture) + evidence(r)).join('') + '</details>' : '');
  }
  function detail(s) {
    q('detail').innerHTML = '<div data-coding-snapshot>' + (s.view ? viewMarkup(s.view) : '') + '</div><div class="toolbar" style="flex-wrap:wrap"><button type="button" class="btn gray" data-coding-read>重新读取编码</button><button type="button" class="btn" data-coding-start>' + (s.view?.status === 'LINKED' ? '更正编码关联' : '选择结算编码') + '</button></div><div data-coding-editor></div><div data-coding-preview-result></div><button type="button" class="btn orange" data-coding-retry hidden>重试原提交并核对结果</button>';
    q('read').onclick = () => { if (!ensure(s) || isLocked()) return; if (!s.editing || guard(s, '重新读取将放弃本地选择和填写。', () => { resetEdit(s); read(s); })) read(s); };
    q('start').onclick = () => start(s);
    q('retry').onclick = () => send(s);
    if (s.editing && s.view && canEdit(s)) editor(s);
    controls();
  }
  const field = (key, label, type = 'text', limit = 500) => '<div class="form-item"><label>' + label + '</label>' + (type === 'textarea' ? '<textarea aria-label="' + esc(label) + '" rows="3" maxlength="' + limit + '" data-coding-input="' + key + '"></textarea>' : '<input aria-label="' + esc(label) + '" maxlength="' + limit + '" data-coding-input="' + key + '">') + '</div>';
  function editor(s) {
    q('editor').innerHTML = '<div class="section-title">' + (s.view.status === 'LINKED' ? '更正本账链的编码关联' : '核对本账链的编码') + '</div><div class="toolbar"><button type="button" class="btn gray" data-coding-load-scopes>重新读取课程目录</button></div><div class="form-grid"><div class="form-item"><label>课程目录范围</label><select data-coding-scope><option value="">请选择有权限的目录</option></select></div><div class="form-item"><label>目录中的正式课程</label><select data-coding-input="course_code"><option value="">先选择课程目录</option></select></div></div><div data-coding-catalog></div><div class="form-grid">' + field('evidence_note', '本次关联核对说明（必填）', 'textarea', 2000) + field('evidence_reference', '材料位置 / 凭证号（选填）') + (s.view.status === 'LINKED' ? field('reason_note', '本次更正原因（必填）', 'textarea', 2000) + field('reason_reference', '更正依据（选填）') : '') + '</div><div class="toolbar" style="flex-wrap:wrap"><button type="button" class="btn" data-coding-preview>生成核对预览</button><button type="button" class="btn gray" data-coding-cancel>取消本次编码办理</button></div>';
    for (const input of section.querySelectorAll('[data-coding-input]')) {
      input.value = s.draft[input.dataset.codingInput] || '';
      input.oninput = input.onchange = () => { if (!ensure(s) || isLocked() || blocked) return; s.draft[input.dataset.codingInput] = input.value; invalidatePreview(s); controls(); };
    }
    q('scope').onchange = () => selectScope(s);
    q('load-scopes').onclick = () => loadScopes(s);
    q('preview').onclick = () => preview(s);
    q('cancel').onclick = () => { if (!ensure(s) || isLocked()) return; resetEdit(s); detail(s); say('已取消本地填写；已确认的编码关联保留。'); changed(); };
    controls();
  }
  function invalidatePreview(s) { s.preview = null; if (s.timer) clearTimeout(s.timer); s.timer = null; if (q('preview-result')) q('preview-result').innerHTML = ''; }
  function resetEdit(s) { s.editing = false; s.draft = {}; s.scopes = []; s.catalog = null; s.rejected = null; invalidatePreview(s); }
  function start(s) { if (!ensure(s) || blocked || isLocked() || !canEdit(s)) return false; s.editing = true; editor(s); changed(); return loadScopes(s); }
  function begin(s, kind) { s.controller?.abort(); s.controller = new AbortController(); s.busy = kind; const ticket = ++s.ticket; controls(); changed(); return ticket; }
  function end(s, ticket) { if (owns(s) && ticket === s.ticket) { s.busy = false; controls(); changed(); } }
  function privateClear(s) { s.view = null; s.pending = null; resetEdit(s); detail(s); }
  async function read(s, message = '') {
    if (!ensure(s) || isLocked()) return false;
    const previous = s.view, ticket = begin(s, 'read'); s.view = null; s.catalog = null; s.scopes = []; invalidatePreview(s); detail(s); say('正在读取当前编码关联…');
    try {
      const value = await host.api(BASE + '?chain_code=' + encodeURIComponent(s.reference.chain_code), { quiet: true, signal: s.controller.signal });
      if (!fresh(s, ticket)) return false;
      s.view = validateView(value, s.reference, previous); detail(s); say(message || (canEdit(s) ? '' : '当前可查阅已确认编码和历史；办理权限以服务器授权为准。')); return true;
    } catch (error) {
      if (!fresh(s, ticket) || error?.name === 'AbortError') return false;
      if ([401, 403].includes(status(error))) privateClear(s);
      say([401, 403].includes(status(error)) ? '登录或权限已变化，原编码资料已清除。' : error.message || '读取失败，请重新读取。'); return false;
    } finally { end(s, ticket); }
  }
  async function loadScopes(s) {
    if (!ensure(s) || blocked || isLocked() || !canEdit(s)) return false;
    const ticket = begin(s, 'scopes'); s.scopes = []; s.catalog = null; s.draft.course_code = ''; invalidatePreview(s);
    q('scope').innerHTML = '<option value="">正在读取有权限的目录…</option>'; section.querySelector('[data-coding-input="course_code"]').innerHTML = '<option value="">先选择课程目录</option>'; q('catalog').innerHTML = ''; say('正在读取有权限的课程目录…');
    try {
      const value = await host.api('/course-catalog/scopes', { quiet: true, signal: s.controller.signal });
      if (!fresh(s, ticket)) return false;
      if (!object(value) || !Array.isArray(value.scopes) || value.scopes.some(row => !object(row) || !positive(row.scope_id) || !text(row.organization_code)) || new Set(value.scopes.map(row => row.scope_id)).size !== value.scopes.length) fail();
      s.scopes = value.scopes.filter(row => row.can_read === true); q('scope').innerHTML = '<option value="">请选择课程目录</option>' + s.scopes.map(row => '<option value="' + row.scope_id + '">' + esc(row.organization_code) + ' · 目录 #' + row.scope_id + '</option>').join('');
      say(s.scopes.length ? '请选择目录，再核对固定讲师编码与本次课程。' : '当前没有可查看的课程目录，请核对目录授权。'); return true;
    } catch (error) {
      if (!fresh(s, ticket) || error?.name === 'AbortError') return false;
      if (status(error) === 401) privateClear(s);
      else q('scope').innerHTML = '<option value="">目录暂不可用</option>';
      say(status(error) === 401 ? '登录已失效，原编码资料已清除，请重新登录。' : '课程目录读取失败，请重新读取或核对目录授权。'); return false;
    } finally { end(s, ticket); }
  }
  async function selectScope(s) {
    if (!ensure(s) || blocked || isLocked() || !canEdit(s)) return false;
    const scopeId = Number(q('scope').value); s.catalog = null; s.draft.course_code = ''; invalidatePreview(s); q('catalog').innerHTML = ''; section.querySelector('[data-coding-input="course_code"]').innerHTML = '<option value="">请选择目录中的课程</option>';
    if (!s.scopes.some(row => row.scope_id === scopeId)) { controls(); return false; }
    const ticket = begin(s, 'catalog'); say('正在核对目录与该账链教师的固定绑定…');
    try {
      const value = await host.api(BASE + '/options?chain_code=' + encodeURIComponent(s.reference.chain_code) + '&scope_id=' + scopeId, { quiet: true, signal: s.controller.signal });
      if (!fresh(s, ticket)) return false;
      s.catalog = validateOptions(value, s.view, scopeId); const c = s.catalog;
      q('catalog').innerHTML = '<p class="inline-note">' + esc(catalogLabels[c.status]) + '</p><p>正式讲师编码：' + esc(c.teacher_code || '尚未绑定') + ' · 档案 #' + c.teacher_id + '</p><p>目录修订 ' + c.catalog_revision + ' · 材料版本 ' + esc(c.catalog_version || '尚未发布') + '</p>' + (c.teacher_binding ? '<p class="modal-intro">固定绑定建立于目录修订 ' + c.teacher_binding.created_version + '。</p>' : '');
      section.querySelector('[data-coding-input="course_code"]').innerHTML = '<option value="">请选择本次正式课程</option>' + c.courses.map(row => '<option value="' + esc(row.course_code) + '">' + esc(row.course_name) + '（' + esc(row.course_code) + '）' + (row.active ? '' : ' · 已停用') + '</option>').join('');
      say(c.can_capture ? '停用课程仅按真实历史业务选择，停用状态会随来源版本保留。' : catalogLabels[c.status] + '，请先维护真实目录资料。'); return true;
    } catch (error) {
      if (!fresh(s, ticket) || error?.name === 'AbortError') return false;
      if ([401, 403].includes(status(error))) privateClear(s);
      say(error.message || '目录或绑定读取失败，请重新读取。'); return false;
    } finally { end(s, ticket); }
  }
  function proof(s, key, required) {
    const note = (s.draft[key + '_note'] || '').trim(), reference = (s.draft[key + '_reference'] || '').trim();
    if (required && !note || note.length > 2000 || reference.length > 500 || reference && !note) throw Error(key === 'reason' ? '请填写本次更正原因，说明限 2000 字，依据限 500 字。' : '请填写真实的关联核对说明，说明限 2000 字，材料位置限 500 字。');
    return { ...(note ? { [key + '_note']: note } : {}), ...(reference ? { [key + '_reference']: reference } : {}) };
  }
  async function preview(s) {
    if (!ensure(s) || blocked || isLocked() || !canEdit(s) || !s.catalog?.can_capture) return false;
    try {
      if (!s.catalog.courses.some(row => row.course_code === s.draft.course_code)) throw Error('请从当前目录明确选择本次课程。');
      const body = { chain_code: s.view.chain_code, expected_version: s.view.version, expected_entry_code: s.view.current_entry_code, scope_id: s.catalog.catalog_scope_id, expected_catalog_version: s.catalog.catalog_revision, course_code: s.draft.course_code, ...proof(s, 'evidence', true), ...(s.view.status === 'LINKED' ? proof(s, 'reason', true) : {}) };
      if (canonical(body) === s.rejected) throw Error('请修改被拒绝的填写或重新读取后核对。');
      body.request_id = uuid(); invalidatePreview(s); s.pending = { operation: 'preview', json: JSON.stringify(body), view: s.view, catalog: s.catalog }; return send(s);
    } catch (error) { say(error.message); return false; }
  }
  function renderPreview(s) {
    const p = s.preview;
    q('preview-result').innerHTML = '<div class="section-title">请核对后明确确认</div><p class="inline-note">本次预览尚未生效。有效至 ' + esc(p.expires_at) + '。</p>' + source(p.candidate) + evidence(p) + '<div class="toolbar"><button type="button" class="btn" data-coding-confirm>' + (s.view.status === 'LINKED' ? '确认本次编码更正' : '确认关联结算编码') + '</button></div>';
    q('confirm').onclick = () => confirm(s);
    const remaining = Date.parse(p.expires_at) - Date.now();
    if (remaining > 0) s.timer = setTimeout(() => { if (owns(s) && s.preview === p) { invalidatePreview(s); say('本次核对预览已过期，请重新生成预览。'); controls(); } }, Math.min(remaining, 2147483647));
    controls();
  }
  async function confirm(s) {
    if (!ensure(s) || blocked || isLocked() || !s.preview?.can_confirm || !s.view?.capabilities.can_confirm) return false;
    if (Date.parse(s.preview.expires_at) <= Date.now()) { invalidatePreview(s); say('预览已过期，请重新生成。'); controls(); return false; }
    const p = s.preview, body = { chain_code: s.view.chain_code, expected_version: p.version, expected_entry_code: p.expected_entry_code, preview_code: p.preview_code, request_id: uuid() };
    s.pending = { operation: 'confirm', json: JSON.stringify(body), view: s.view, preview: p }; return send(s);
  }
  async function send(s) {
    if (!ensure(s) || s.busy || !s.pending || blocked) return false;
    const pending = s.pending, ticket = begin(s, pending.operation); say(pending.operation === 'preview' ? '正在生成本次核对预览…' : '正在确认本次编码关联，请等待结果…');
    try {
      const value = await host.api(BASE + '/' + pending.operation, { body: JSON.parse(pending.json), quiet: true, signal: s.controller.signal });
      if (!fresh(s, ticket)) return false;
      if (pending.operation === 'preview') {
        const previewValue = validatePreview(value, pending, pending.view, pending.catalog);
        // can_confirm is a live capability: the pre-preview GET intentionally returns false.
        const latest = await host.api(BASE + '?chain_code=' + encodeURIComponent(s.reference.chain_code), { quiet: true, signal: s.controller.signal });
        if (!fresh(s, ticket)) return false;
        const currentView = validateView(latest, s.reference, pending.view);
        if (currentView.version !== previewValue.version || currentView.current_entry_code !== previewValue.expected_entry_code) throw Object.assign(Error('账目或编码关联在预览期间已变化。'), { status: 409 });
        s.view = currentView; s.preview = previewValue; s.pending = null; s.rejected = null; q('snapshot').innerHTML = viewMarkup(s.view); renderPreview(s); say('请核对正式编码、来源版本和说明，再点击确认。'); return true;
      }
      const receipt = validateView(value, s.reference, pending.view);
      if (receipt.version !== pending.view.version + 1 || canonical(receipt.current.capture) !== canonical(pending.preview.candidate)) fail();
      s.pending = null; s.view = receipt; resetEdit(s); detail(s); s.busy = false;
      const ok = await read(s, '本次编码关联已确认，当前记录与历史已重新读取。');
      if (owns(s) && !ok) say('本次编码关联已确认；最新资料暂未读到，请重新读取，不要重复确认。');
      if (owns(s)) { try { await host.onChanged?.({ chainCode: s.reference.chain_code, projectId: s.reference.project_id }); } catch { say('编码关联已确认，原页面摘要暂未刷新，请稍后刷新。'); } }
      return true;
    } catch (error) {
      if (!fresh(s, ticket) || error?.name === 'AbortError') return false;
      const code = status(error);
      if ([401, 403].includes(code)) { privateClear(s); say('登录或办理权限已变化，原编码资料已清除，请重新读取。'); }
      else if (code === 400) { const body = JSON.parse(pending.json); delete body.request_id; s.rejected = canonical(body); s.pending = null; invalidatePreview(s); say(error.message || '填写未通过核对，请修改后重新预览。'); }
      else if (code === 409) {
        s.pending = null; s.catalog = null; s.scopes = []; invalidatePreview(s); s.busy = false;
        await read(s, (error.message || '账目、目录、绑定或权限已变化。') + ' 已重新读取当前关联，说明保留；请重新选择目录并生成预览。');
      } else { invalidatePreview(s); say('本次提交结果尚未核实。请用下方按钮重试原请求并核对结果；原请求号与内容已保留。'); }
      return false;
    } finally { end(s, ticket); }
  }
  function guard(s, message, discard) {
    if (!ensure(s)) return true;
    q('guard').innerHTML = '<p class="inline-note">' + esc(message) + '</p><div class="toolbar"><button type="button" class="btn" data-coding-stay>继续核对</button><button type="button" class="btn gray" data-coding-discard>放弃本地填写并继续</button></div>';
    q('stay').onclick = () => { if (owns(s)) q('guard').innerHTML = ''; };
    q('discard').onclick = () => { if (!owns(s) || s.busy) return; q('guard').innerHTML = ''; discard(); };
    return false;
  }
  async function open(chainCode) {
    if (!active() || blocked || isLocked()) return false;
    const reference = references.find(row => row.chain_code === chainCode); if (!reference) return false;
    if (session && hasWork()) return guard(session, '存在尚未完成的编码核对，请先处理当前填写。', () => { dispose(session); session = null; open(chainCode); });
    dispose(session); session = { reference, ticket: 0, controller: null, busy: false, pending: null, view: null, catalog: null, scopes: [], editing: false, draft: {}, preview: null, timer: null, dead: false };
    q('guard').innerHTML = ''; detail(session); return read(session);
  }
  function beforeClose() {
    if (!active() || !session || !hasWork()) return true;
    const s = session;
    if (s.busy) { say('编码办理正在进行，请等待读取或提交结果后关闭。'); return false; }
    return guard(s, s.pending ? '提交结果尚未核实。关闭不会取消服务器操作；再次打开时须先读取当前记录。' : '本次编码选择和说明尚未确认，关闭将放弃本地填写。', () => { s.pending = null; resetEdit(s); detail(s); changed(); host.onDiscardRequested?.(); });
  }
  function dispose(s) { if (!s || s.dead) return; s.dead = true; s.ticket++; s.controller?.abort(); if (s.timer) clearTimeout(s.timer); s.pending = null; s.view = null; s.preview = null; s.catalog = null; s.draft = {}; }
  function setChains(rows) {
    if (!active()) { cleanup(); return false; }
    if (!Array.isArray(rows) || rows.some(row => !object(row) || !text(row.chain_code) || !positive(row.project_id) || !positive(row.teacher_id) || !text(row.current_entry_code)) || new Set(rows.map(row => row.chain_code)).size !== rows.length) throw TypeError('Verified financial chain references required');
    const next = rows.map(row => ({ ...row }));
    if (canonical(next) === canonical(references)) return true;
    references = next;
    if (session) {
      const same = references.find(row => row.chain_code === session.reference.chain_code && row.project_id === session.reference.project_id && row.teacher_id === session.reference.teacher_id);
      if (!same) { dispose(session); session = null; q('detail').innerHTML = ''; q('guard').innerHTML = ''; }
      else if (same.current_entry_code !== session.reference.current_entry_code) {
        session.reference = same; invalidatePreview(session); say('当前账目已更新，请重新读取编码后核对。');
        if (!session.pending) { session.ticket++; session.controller?.abort(); session.busy = false; session.view = null; session.catalog = null; detail(session); }
      }
    }
    chainList(); changed(); return true;
  }
  function setBlocked(value) { if (!active()) return; const next = value === true; if (next && !blocked && session?.preview && !isLocked()) { invalidatePreview(session); say('原业务正在修改，请保存并重新核对编码预览。'); } blocked = next; controls(); }
  function refresh() { if (!session || !ensure(session) || isLocked()) return Promise.resolve(false); if (hasWork()) { invalidatePreview(session); say('原账目已刷新，请重新读取编码，再核对本次填写。'); controls(); return Promise.resolve(false); } return read(session); }
  function cleanup() { if (!alive) return; dispose(session); session = null; alive = false; references = []; section.remove(); host.signal?.removeEventListener('abort', cleanup); }
  host.signal?.addEventListener('abort', cleanup, { once: true });
  if (host.signal?.aborted) cleanup(); else chainList();
  return { setChains, setBlocked, refresh, beforeClose, isLocked, hasWork, cleanup };
}
