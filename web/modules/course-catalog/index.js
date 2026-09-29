// Existing host adapter. Each read/manage capability is supplied by the server.
const PAGE_SIZE = 20;
const ROOT = '/course-catalog/scopes';
const CERT_STATUS = { certified: '已记录认证', not_certified: '未认证', unknown: '认证未知', revoked: '已撤销' };
const text = value => value === undefined || value === null || value === '' ? '—' : String(value);
const rows = value => Array.isArray(value) ? value : [];
const fields = [
  { k: 'course_code', label: '具体启用课程编码', required: true, hint: '从课程表选择，或填写完整编码；按原值精确匹配。' },
  { k: 'as_of', label: '判定日期', type: 'date', required: true, hint: '请明确选择日期，不自动采用今天。' },
  { k: 'accepted_levels', label: '允许等级（精确值，每行一个）', type: 'textarea', hint: '留空不限等级；可填写多个值，不推断等级高低关系。' },
  { k: 'allowed_cities', label: '允许城市（精确值，每行一个）', type: 'textarea', hint: '留空不限城市；填写后逐项精确匹配，不推断邻近或包含关系。' },
];
function dependencies(context, modal = false) {
  for (const key of ['api', 'renderTable', 'renderForm', 'collectForm', ...(modal ? ['openModal'] : [])]) {
    if (typeof context?.[key] !== 'function') throw new TypeError(`Missing host function: ${key}`);
  }
}
function errorMessage(error) {
  const codes = [Number(error?.status), Number(error?.code)];
  if (codes.includes(403)) return '无权限：当前账号不能查看此课程目录或查询资格，请联系管理员核对授权。';
  if (codes.includes(401)) return '登录状态已失效，请重新登录后查看课程与认证。';
  if (codes.includes(409)) return '目录或资格依据已变化，请重新读取目录后再查询。';
  if (error?.message === '网络连接失败') return '网络连接失败，暂时无法读取课程与认证，请恢复连接后重试。';
  return `课程与认证读取失败：${error?.message || '请稍后重试。'}`;
}
const inert = () => ({ ready: Promise.resolve(false), refresh: async () => false, destroy() {} });
function qualificationMatches(result, snapshot, request, scope) {
  if (result.status !== 'READY' || result.course_code !== request.course_code || result.as_of !== request.as_of ||
      result.catalog_version !== snapshot.catalog.catalog_version || !Array.isArray(result.eligible) || !Array.isArray(result.gaps)) return false;
  const context = result.qualification_context;
  {
    if (!context || context.schema_version !== 'm04_qualification_context_v1' || String(context.scope_id) !== scope || context.version !== snapshot.version || context.catalog_version !== result.catalog_version ||
        context.organization_code !== snapshot.organization_code || context.course_code !== request.course_code || context.as_of !== request.as_of) return false;
    for (const key of ['accepted_levels', 'allowed_cities']) {
      if (!Array.isArray(context[key]) || !context[key].every(value => typeof value === 'string') || new Set(context[key]).size !== context[key].length ||
          JSON.stringify([...context[key]].sort()) !== JSON.stringify([...request[key]].sort())) return false;
    }
  }
  const teachers = new Set(snapshot.catalog.teachers.map(item => item.teacher_code));
  const bindings = new Map(snapshot.bindings.map(item => [item.teacher_code, item.teacher_id]));
  if (teachers.size !== snapshot.catalog.teachers.length || bindings.size !== teachers.size || snapshot.bindings.length !== teachers.size ||
      new Set(snapshot.bindings.map(item => item.teacher_id)).size !== teachers.size || [...teachers].some(code => typeof code !== 'string' || !code || !bindings.has(code))) return false;
  const seen = new Set();
  for (const [key, eligible] of [['eligible', true], ['gaps', false]]) {
    for (const item of result[key]) {
      if (!item || !teachers.has(item.teacher_code) || seen.has(item.teacher_code) || item.teacher_id !== bindings.get(item.teacher_code) ||
          !Number.isSafeInteger(item.teacher_id) || item.teacher_id < 1 || item.eligible !== eligible || !Array.isArray(item.reasons)) return false;
      seen.add(item.teacher_code);
    }
  }
  return seen.size === teachers.size;
}

/** Open in the existing host modal. No navigation or independent page shell. */
export function openCourseCatalog(context) {
  dependencies(context, true);
  if (context.signal?.aborted || context.isCurrent?.() === false) return inert();
  let mounted, closed = false;
  const onClose = () => { closed = true; context.signal?.removeEventListener('abort', destroy); mounted?.destroy(); };
  const mask = context.openModal('课程与认证', '<div data-course-catalog-mount></div>', {
    wide: true, noFoot: true, onClose, beforeClose: () => mounted?.requestLeave?.(() => context.closeModal?.()) ?? true,
  });
  if (!mask) return inert();
  const root = mask.querySelector('[data-course-catalog-mount]');
  if (!root) throw new Error('Host modal is missing the course catalog mount slot');
  mounted = mountCourseCatalog(root, { ...context, isCurrent: () => (context.isCurrent?.() ?? true) && (!context.getModal || context.getModal() === mask) });
  function destroy() {
    mounted?.destroy();
    context.signal?.removeEventListener('abort', destroy);
    // Never close a newer modal that replaced this one.
    if (!closed && typeof context.closeModal === 'function' && mask.ownerDocument?.getElementById('modal-mask') === mask) context.closeModal(true);
    closed = true;
  }
  context.signal?.addEventListener('abort', destroy, { once: true });
  return { ready: mounted.ready, refresh: mounted.refresh, destroy };
}

/** Required host functions: api, renderTable, renderForm, collectForm.
 * Optional: signal, isCurrent. Returns {ready, refresh, destroy} immediately.
 * Host api prefixes /api, unwraps data, and accepts quiet + AbortSignal.
 */
export function mountCourseCatalog(root, context) {
  dependencies(context);
  if (!root?.ownerDocument) throw new TypeError('mountCourseCatalog requires a DOM root');
  if (context.signal?.aborted || context.isCurrent?.() === false) return inert();
  const doc = root.ownerDocument;
  let alive = true, accessEpoch = 0, scopeEpoch = 0, queryEpoch = 0, readEpoch = 0, editorEpoch = 0;
  let accessController, scopeController, queryController, readController, scopes = [], selected = '', snapshot = null;
  let queryForm = null, queryButton = null, resultsArea = null, maintenance = null, editorMode = 'courses', editorBody = null;
  const identity = context.getUser?.();
  const pages = new Map();
  const active = () => alive && !context.signal?.aborted && (context.isCurrent?.() ?? true) && (!context.getUser || context.getUser() === identity);
  const node = (tag, value, className) => {
    const el = doc.createElement(tag);
    if (value !== undefined) el.textContent = String(value);
    if (className) el.className = className;
    return el;
  };
  const button = (label, action, disabled = false) => {
    const el = node('button', label, 'btn gray sm'); el.type = 'button'; el.dataset.catalogAction = action; el.disabled = disabled; return el;
  };
  const wrap = node('section'); wrap.setAttribute('aria-label', '课程目录与资格');
  const toolbar = node('div', undefined, 'toolbar'); toolbar.append(button('刷新课程空间', 'refresh'));
  const notice = node('p'); notice.setAttribute('role', 'status'); notice.setAttribute('aria-live', 'polite');
  const scopeArea = node('div'), detailArea = node('div'), readArea = node('div'), managementArea = node('div');
  wrap.append(toolbar, notice, scopeArea, detailArea); root.append(wrap);
  const say = message => { if (active()) notice.textContent = message; };

  function invalidateQuery(message = '') {
    queryEpoch++; queryController?.abort(); queryController = null;
    for (const key of ['eligible', 'gaps', 'issues']) pages.delete(key);
    if (resultsArea) resultsArea.replaceChildren(...(message ? [node('p', message)] : []));
    if (queryButton) queryButton.disabled = false;
  }
  function clearRead() {
    readEpoch++; readController?.abort(); readController = null; invalidateQuery();
    snapshot = null; queryForm = queryButton = resultsArea = null; readArea.replaceChildren();
  }
  function clearScope() {
    scopeEpoch++; editorEpoch++; editorBody = null; scopeController?.abort(); scopeController = null;
    clearRead();
    maintenance?.destroy(); maintenance = null;
    pages.clear(); readArea.replaceChildren(); managementArea.replaceChildren(); detailArea.replaceChildren(managementArea, readArea);
  }
  function pagedTable(key, title, columns, data, parent) {
    const area = node('section'); parent.append(area);
    let page = 0;
    const render = () => {
      if (!active()) return;
      area.replaceChildren(node('h4', `${title} · ${data.length} 条`));
      if (!data.length) { area.append(node('p', '暂无记录。')); return; }
      const shown = data.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);
      const table = node('div');
      // Host renderTable escapes all ordinary cell values. The only custom
      // renderer below emits fixed markup and locally generated integer indexes.
      table.innerHTML = context.renderTable(columns, shown, [], '');
      const nav = node('div', undefined, 'toolbar');
      nav.append(node('span', `${title} ${page * PAGE_SIZE + 1}–${page * PAGE_SIZE + shown.length} / ${data.length}`),
        button('上一页', `page:${key}:-1`, page === 0), button('下一页', `page:${key}:1`, (page + 1) * PAGE_SIZE >= data.length));
      area.append(table, nav);
    };
    pages.set(key, delta => { const next = page + delta; if (next >= 0 && next * PAGE_SIZE < data.length) { page = next; render(); } });
    render();
  }
  const evidence = value => value ? `${text(CERT_STATUS[value.status] || value.status)}；来源引用：${text(value.source_ref)}；${text(value.valid_from)} 至 ${text(value.valid_to)}` : '无该课认证记录';
  const reasons = value => rows(value).map(item => text(item.message)).join('；') || '本次查询无资格缺口';

  function renderSnapshot() {
    const catalog = snapshot.catalog;
    readArea.replaceChildren();
    readArea.append(node('p', `机构编码：${text(snapshot.organization_code)}；空间：${text(snapshot.scope_id)}；目录版本：${text(snapshot.version)}；材料版本：${text(catalog.catalog_version)}`));
    readArea.append(node('p', '目录中的等级和城市是目录快照；资格查询使用当前师资档案。认证状态及来源引用仅为已记录信息，不表示来源真实性已核验。日期空缺表示未提供该边界。'));
    const courses = catalog.courses.map((course, index) => ({ ...course, id: `course-${index}`, course_label: course.course_name ?? course.name, active_label: course.active === true ? '启用' : '停用', index }));
    pagedTable('courses', '课程', [
      { k: 'course_code', l: '课程编码' }, { k: 'course_label', l: '课程名称' }, { k: 'active_label', l: '状态' },
      { l: '查询选择', render: row => `<button type="button" class="btn gray sm" data-catalog-action="choose:${row.index}" ${row.active === true ? '' : 'disabled'}>选择此课程</button>` },
    ], courses, readArea);
    const bindings = new Map(snapshot.bindings.map(item => [item.teacher_code, item.teacher_id]));
    pagedTable('teachers', '师资', [{ k: 'teacher_code', l: '师资编码' }, { k: 'teacher_id', l: '关联档案编号' }, { k: 'teacher_level', l: '目录等级' }, { k: 'city', l: '目录城市' }],
      catalog.teachers.map((item, index) => ({ ...item, id: `teacher-${index}`, teacher_id: text(bindings.get(item.teacher_code)), teacher_level: text(item.teacher_level), city: text(item.city) })), readArea);
    pagedTable('certifications', '课程认证记录', [{ k: 'teacher_code', l: '师资编码' }, { k: 'course_code', l: '课程编码' }, { k: 'status_label', l: '记录状态' }, { k: 'source_ref', l: '来源引用' }, { k: 'valid_from', l: '有效起日' }, { k: 'valid_to', l: '有效止日' }],
      catalog.certifications.map((item, index) => ({ ...item, id: `cert-${index}`, status_label: CERT_STATUS[item.status] || text(item.status), source_ref: text(item.source_ref), valid_from: text(item.valid_from), valid_to: text(item.valid_to) })), readArea);
    readArea.append(node('h4', '按具体课程查询资格'), node('p', '本查询只核验具体课程认证、当前在库状态及已填等级／城市限制，不计算交通条件或推荐排名；课程资格不等于整体派单准入。结果按目录顺序展示。'));
    if (!catalog.courses.some(course => course.active === true)) { readArea.append(node('p', '当前没有启用的课程，暂不能查询资格。')); return; }
    queryForm = node('form'); queryForm.dataset.catalogForm = 'query';
    queryForm.innerHTML = context.renderForm(fields, {});
    queryButton = button('查询资格', 'qualify'); queryButton.type = 'submit';
    const actions = node('div', undefined, 'toolbar'); actions.append(queryButton, button('重新读取目录', 'reload'));
    queryForm.append(actions); resultsArea = node('div'); resultsArea.setAttribute('aria-live', 'polite');
    readArea.append(queryForm, resultsArea);
  }
  async function refresh() {
    if (!active()) return false;
    const epoch = ++accessEpoch; accessController?.abort(); accessController = new AbortController();
    const signal = accessController.signal;
    selected = ''; scopes = []; clearScope(); scopeArea.replaceChildren(); say('正在读取本人业务身份…');
    const valid = () => active() && epoch === accessEpoch;
    try {
      const me = await context.api('/organization/me', { quiet: true, signal });
      if (!valid()) return false;
      if (me?.status === 'NOT_BOUND') { say('当前账号没有有效的人员绑定，暂不能查看课程与认证。'); return false; }
      if (me?.status === 'NOT_CONFIGURED') { say('组织与人员关系尚未配置，暂不能查看课程与认证。'); return false; }
      if (me?.status !== 'BOUND') throw new Error('本人业务身份响应格式不完整');
      say('正在读取课程目录空间…');
      const result = await context.api(ROOT, { quiet: true, signal });
      if (!valid()) return false;
      if (!Array.isArray(result?.scopes) || !result.scopes.every(item => item && Number.isSafeInteger(item.scope_id) && item.scope_id > 0)) throw new Error('课程空间响应格式不完整');
      scopes = result.scopes;
      if (!scopes.length) { say('当前没有可查看的课程目录空间。'); return false; }
      scopeArea.innerHTML = context.renderForm([{ k: 'scope_id', label: '课程目录空间', type: 'select', required: true,
        options: [{ v: '', l: '请选择课程目录空间' }, ...scopes.map(item => ({ v: String(item.scope_id), l: `机构 ${text(item.organization_code)} · 空间 ${item.scope_id}` }))] }], {});
      say('请选择要查看的课程目录空间。'); return true;
    } catch (error) { if (valid() && error?.name !== 'AbortError') say(errorMessage(error)); return false; }
  }
  async function readScope(epoch = scopeEpoch) {
    const ticket = ++readEpoch, scopeId = selected; readController?.abort(); readController = new AbortController();
    const result = await context.api(`${ROOT}/${encodeURIComponent(scopeId)}`, { quiet: true, signal: readController.signal });
    if (!active() || epoch !== scopeEpoch || ticket !== readEpoch || scopeId !== selected) return null;
    if (!result || String(result.scope_id) !== selected || !Number.isSafeInteger(result.version) || result.version < 0) throw new Error('目录响应格式不完整');
    if (result.version === 0) { readArea.replaceChildren(node('p', '此课程目录尚未配置，当前没有可查询的正式课程与认证。')); return false; }
    if (!['READY', 'NOT_CONFIGURED'].includes(result.status) || !result.catalog || !['courses', 'teachers', 'certifications'].every(key => Array.isArray(result.catalog[key])) || !Array.isArray(result.bindings)) throw new Error('目录响应格式不完整');
    snapshot = result; renderSnapshot(); return true;
  }
  async function loadScope(id) {
    if (!active()) return false;
    clearScope(); selected = String(id || '');
    const scope = scopes.find(item => String(item.scope_id) === selected);
    if (!scope) { selected = ''; say('请选择要查看的课程目录空间。'); return false; }
    const epoch = scopeEpoch; scopeController = new AbortController();
    const valid = () => active() && epoch === scopeEpoch;
    say('正在读取课程目录…');
    try {
      if (scope.can_manage === true) {
        const tabs = node('div', undefined, 'toolbar'); tabs.style.flexWrap = 'wrap';
        tabs.setAttribute('aria-label', '选择维护内容');
        tabs.append(button('维护课程', 'editor:courses'), button('维护认证', 'editor:certifications'));
        editorBody = node('div'); managementArea.append(tabs, editorBody);
        await mountEditor('courses', scope, epoch);
        if (!valid()) return false;
      }
      if (scope.can_read !== false) {
        const configured = await readScope(epoch);
        if (!valid() || configured === null) return false;
        say(configured ? '课程目录已读取；请明确选择具体启用课程和判定日期。' : '此课程目录尚未配置，当前没有可查询的正式课程与认证。');
      } else say('当前空间开放课程维护；目录查看与资格查询需另有授权。');
      return true;
    } catch (error) { if (valid() && error?.name !== 'AbortError') { if ([401, 403].includes(Number(error?.status || error?.code))) clearScope(); say(errorMessage(error)); } return false; }
  }
  async function mountEditor(mode, scope, epoch = scopeEpoch) {
    if (!active() || epoch !== scopeEpoch || !editorBody) return false;
    const ticket = ++editorEpoch; maintenance?.destroy(); maintenance = null; editorBody.replaceChildren(); editorMode = mode;
    for (const target of managementArea.querySelectorAll('[data-catalog-action]')) {
      const selectedMode = target.dataset.catalogAction === 'editor:' + mode;
      target.disabled = selectedMode; target.setAttribute('aria-pressed', String(selectedMode));
    }
    const valid = () => active() && epoch === scopeEpoch && ticket === editorEpoch;
    try {
      const module = mode === 'certifications'
        ? await import('/modules/course-catalog/certification-maintenance.js?v=20260923certmaintenance2')
        : await import('/modules/course-catalog/course-maintenance.js?v=20260923certmaintenance2');
      if (!valid()) return false;
      const mount = mode === 'certifications' ? module.mountCertificationMaintenance : module.mountCourseMaintenance;
      maintenance = mount(editorBody, { ...context, isCurrent: valid,
        onConfirming: () => { if (valid()) { clearRead(); context.onCatalogChanged?.({ scopeId: scope.scope_id }); } },
        onAccessLost: () => { if (valid()) { clearScope(); say('当前登录或维护权限已变化，请重新读取课程空间。'); } },
        onSaved: async () => { if (!valid()) return; clearRead(); if (scope.can_read !== false) await readScope(epoch); },
      }, scope);
      return await maintenance.ready;
    } catch (error) {
      if (valid() && error?.name !== 'AbortError') { editorBody.replaceChildren(node('p', '维护内容暂时无法加载，请切换后重试。')); say(errorMessage(error)); }
      return false;
    }
  }
  function requestLeave(action) { return maintenance?.requestLeave(action) ?? true; }
  async function qualify() {
    if (!active() || !snapshot || !queryForm || queryButton.disabled) return false;
    invalidateQuery();
    const form = context.collectForm(queryForm, fields);
    if (!form) return false;
    const course = snapshot.catalog.courses.find(item => item.course_code === form.course_code);
    if (!course || course.active !== true) { say('请选择目录中的具体启用课程，课程编码须完全一致。'); return false; }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(form.as_of) || form.as_of.startsWith('0000') || Number.isNaN(Date.parse(`${form.as_of}T00:00:00Z`)) || new Date(`${form.as_of}T00:00:00Z`).toISOString().slice(0, 10) !== form.as_of) { say('请填写有效的判定日期。'); return false; }
    const request = { course_code: form.course_code, as_of: form.as_of };
    for (const key of ['accepted_levels', 'allowed_cities']) {
      const list = String(form[key] || '').split(/\r?\n/).map(value => value.trim()).filter(Boolean);
      if (new Set(list).size !== list.length) { say('等级或城市限制包含重复值，请保留每个精确值一行。'); return false; }
      request[key] = list;
    }
    const version = snapshot.version, scope = selected, epoch = ++queryEpoch, source = snapshot;
    queryController = new AbortController(); queryButton.disabled = true;
    const valid = () => active() && queryEpoch === epoch && snapshot === source && selected === scope;
    say('正在查询课程资格…');
    try {
      const result = await context.api(`${ROOT}/${encodeURIComponent(scope)}/qualify`, { method: 'POST', quiet: true, signal: queryController.signal, body: { expected_version: version, request } });
      if (!valid()) return false;
      if (!result || String(result.scope_id) !== scope || result.version !== version) throw new Error('资格结果与当前目录版本不一致，请重新读取目录');
      if (result.ready !== true) {
        say(result.status === 'NOT_CONFIGURED' ? '此课程目录尚未配置，暂不能查询资格。' : '本次条件未通过资格查询校验，请核对具体课程和判定日期。');
        pagedTable('issues', '查询问题', [{ k: 'message', l: '说明' }], rows(result.issues).map((item, index) => ({ ...item, id: `issue-${index}` })), resultsArea);
        return false;
      }
      if (!qualificationMatches(result, source, request, scope)) throw new Error('资格结果与本次查询条件或关联档案不一致，请重新查询');
      resultsArea.replaceChildren(node('p', `查询条件：课程 ${request.course_code}；日期 ${request.as_of}；等级 ${request.accepted_levels.join('、') || '不限'}；城市 ${request.allowed_cities.join('、') || '不限'}。目录版本 ${version}；材料版本 ${text(result.catalog_version)}。`),
        node('p', '结果基于本次查询时的师资档案；后续档案或权限变化需重新查询。来源引用不代表真实性认可。本查询不计算交通条件或推荐排名；课程资格不等于整体派单准入。'));
      for (const [key, title] of [['eligible', '本次课程资格符合'], ['gaps', '资格缺口']]) pagedTable(key, title,
        [{ k: 'teacher_code', l: '师资编码' }, { k: 'teacher_id', l: '关联档案编号' }, { k: 'teacher_level', l: '当前等级' }, { k: 'city', l: '当前城市' }, { k: 'reason_text', l: '依据／缺口' }, { k: 'evidence_text', l: '认证来源与有效期' }],
        result[key].map((item, index) => ({ ...item, id: `${key}-${index}`, teacher_id: text(item.teacher_id), teacher_level: text(item.teacher_level), city: text(item.city), reason_text: reasons(item.reasons), evidence_text: evidence(item.evidence) })), resultsArea);
      say('资格查询完成，结果只适用于上方已列明的条件。'); return true;
    } catch (error) {
      if (valid() && error?.name !== 'AbortError') {
        const message = errorMessage(error);
        if ([401, 403, 409].some(code => Number(error?.status) === code || Number(error?.code) === code)) {
          clearScope(); detailArea.append(button('重新读取目录', 'reload'));
        }
        say(message);
      }
      return false;
    } finally { if (valid()) queryButton.disabled = false; }
  }
  function click(event) {
    if (!active()) return;
    let target = event.target;
    while (target && target !== wrap && !target.dataset?.catalogAction) target = target.parentNode;
    if (!target?.dataset?.catalogAction || target.disabled) return;
    const action = target.dataset.catalogAction;
    if (action === 'qualify') return; // The form submit event is the only query trigger.
    event.preventDefault();
    if (action === 'refresh') { if (requestLeave(() => refresh())) return refresh(); return; }
    if (action === 'reload') { if (requestLeave(() => loadScope(selected))) return loadScope(selected); return; }
    const [kind, key, delta] = action.split(':');
    if (kind === 'editor' && ['courses', 'certifications'].includes(key) && key !== editorMode) {
      const scope = scopes.find(item => String(item.scope_id) === selected);
      if (scope?.can_manage !== true) return;
      const go = () => mountEditor(key, scope); if (requestLeave(go)) return go(); return;
    }
    if (kind === 'page') return pages.get(key)?.(Number(delta));
    if (kind === 'choose' && snapshot && queryForm) {
      const course = snapshot.catalog.courses[Number(key)];
      if (course?.active === true) { queryForm.querySelector('[data-k="course_code"]').value = course.course_code; invalidateQuery('课程已更改，请重新查询。'); say(`已选择课程：${text(course.course_name ?? course.name)}（${course.course_code}）。请填写判定日期。`); }
    }
  }
  function change(event) {
    if (!active()) return;
    if (scopeArea.contains(event.target) && event.target.dataset?.k === 'scope_id') {
      const next = event.target.value; event.target.value = selected;
      const go = () => { event.target.value = next; return loadScope(next); };
      if (requestLeave(go)) return go(); return;
    }
    if (queryForm?.contains(event.target)) { invalidateQuery('条件已更改，请重新查询。'); say('查询条件已更改，之前结果已失效。'); }
  }
  function input(event) { if (active() && queryForm?.contains(event.target)) { invalidateQuery('条件已更改，请重新查询。'); say('查询条件已更改，之前结果已失效。'); } }
  function submit(event) { if (active() && event.target === queryForm) { event.preventDefault(); return qualify(); } }
  const listeners = { click, change, input, submit };
  for (const [type, handler] of Object.entries(listeners)) wrap.addEventListener(type, handler);
  function destroy() {
    if (!alive) return;
    alive = false; accessEpoch++; scopeEpoch++; queryEpoch++; readEpoch++; editorEpoch++;
    accessController?.abort(); scopeController?.abort(); queryController?.abort(); readController?.abort();
    context.signal?.removeEventListener('abort', destroy);
    for (const [type, handler] of Object.entries(listeners)) wrap.removeEventListener(type, handler);
    maintenance?.destroy(); maintenance = null;
    scopes = []; snapshot = queryForm = queryButton = resultsArea = null; pages.clear(); wrap.remove();
  }
  context.signal?.addEventListener('abort', destroy, { once: true });
  return { ready: refresh(), refresh: () => requestLeave(() => refresh()) ? refresh() : Promise.resolve(false), requestLeave, destroy };
}
