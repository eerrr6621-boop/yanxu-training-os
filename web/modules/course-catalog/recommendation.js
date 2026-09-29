// Optional course selection within the existing recommendation form.
// Only the authenticated server supplies scopes, confirmed courses and qualification.
export function mountRecommendationCourse(root, host) {
  const $ = selector => root.querySelector(selector);
  let alive = true, accessTicket = 0, scopeTicket = 0, scopes = [], snapshot = null, accessController, scopeController;
  const saved = host.draft || {};
  root.innerHTML = `<label><input type="checkbox" data-course-enabled ${saved.enabled === true ? 'checked' : ''}>按明确标准课程核验师资</label><p class="modal-intro" data-course-notice role="status">未选择标准课程，课程认证未核验。</p><div class="toolbar"><button type="button" class="btn gray" data-course-refresh>重新读取课程目录</button></div><div data-course-fields>${host.renderForm([
    { k: 'scope_id', label: '课程目录范围', type: 'select', options: [{ v: '', l: '请选择有权限的课程目录' }] },
    { k: 'course_code', label: '标准课程', type: 'select', options: [{ v: '', l: '先选择课程目录' }] },
    { k: 'as_of', label: '课程资格判定日期', type: 'date', hint: '请明确填写本次判定日期，不从客户文字推定日期。' },
    { k: 'accepted_levels', label: '允许等级（选填）', type: 'textarea', hint: '每行一个等级，留空表示不限。' },
    { k: 'allowed_cities', label: '允许常驻城市（选填）', type: 'textarea', hint: '每行一个城市，留空表示不限。' },
  ], { as_of: saved.as_of || '', accepted_levels: saved.accepted_levels || '', allowed_cities: saved.allowed_cities || '' })}</div>`;
  const enabled = $('[data-course-enabled]'), notice = $('[data-course-notice]'), fields = $('[data-course-fields]');
  const scope = $('[data-k="scope_id"]'), course = $('[data-k="course_code"]');
  const active = () => alive && !host.signal?.aborted && (host.isCurrent?.() ?? true);
  const say = message => { if (active()) notice.textContent = message; };
  const escape = host.esc;
  const changed = () => { if (active()) host.onChange?.(); };
  const display = () => { fields.hidden = !enabled.checked; say(enabled.checked ? '请明确选择目录、标准课程和判定日期。每次进入此表单需重新选择课程。' : '未选择标准课程，沿用原推荐规则；课程认证未核验。'); };
  function clearScope() { scopeTicket++; scopeController?.abort(); snapshot = null; course.innerHTML = '<option value="">先选择课程目录</option>'; course.disabled = true; }
  async function refresh(notify = true) {
    if (!active()) return false;
    const ticket = ++accessTicket; accessController?.abort(); accessController = new AbortController(); const request = accessController;
    clearScope(); scopes = []; scope.innerHTML = '<option value="">正在读取有权限的课程目录…</option>'; scope.disabled = true;
    if (notify) changed();
    try {
      const result = await host.api('/course-catalog/scopes', { quiet: true, signal: request.signal });
      if (!active() || ticket !== accessTicket || request.signal.aborted) return false;
      if (!Array.isArray(result.scopes) || !result.scopes.every(item => Number.isSafeInteger(item?.scope_id) && item.scope_id > 0) || new Set(result.scopes.map(item => item.scope_id)).size !== result.scopes.length) throw new Error('课程目录范围暂时无法核实');
      scopes = result.scopes;
      scope.innerHTML = '<option value="">请选择课程目录</option>' + scopes.map(item => `<option value="${item.scope_id}">${escape(item.organization_code || '机构待核实')} · 课程目录 ${item.scope_id}</option>`).join('');
      scope.disabled = scopes.length === 0;
      say(scopes.length ? enabled.checked ? '请选择课程目录，再选择其中已确认的启用课程。' : '可选择标准课程核验；未选择时课程认证未核验。' : '当前没有可查看的课程目录。可不选标准课程继续原推荐，但课程认证未核验。');
      return true;
    } catch (error) {
      if (!active() || ticket !== accessTicket || request.signal.aborted || error?.name === 'AbortError') return false;
      scope.innerHTML = '<option value="">课程目录暂不可用</option>';
      say([403, 404].includes(error.status || error.code) ? '当前账号没有可查看的课程目录，请核对授权。未选择课程时不会核验课程认证。' : '课程目录暂时无法读取。未选择课程时不会核验课程认证。'); return false;
    }
  }
  async function selectScope() {
    if (!active()) return;
    clearScope(); const id = scope.value; changed();
    if (!scopes.some(item => String(item.scope_id) === id)) { say('请从当前有权限的目录范围中选择。'); return; }
    const ticket = scopeTicket; scopeController = new AbortController(); const request = scopeController;
    say('正在读取已确认课程目录…');
    try {
      const result = await host.api('/course-catalog/scopes/' + encodeURIComponent(id), { quiet: true, signal: request.signal });
      if (!active() || ticket !== scopeTicket || scope.value !== id || request.signal.aborted) return;
      if (!Number.isSafeInteger(result.scope_id) || String(result.scope_id) !== id || !Number.isSafeInteger(result.version) || result.version < 0) throw new Error('课程目录响应不完整');
      if (result.version === 0 || result.status === 'NOT_CONFIGURED') { say('所选目录尚无已确认课程。不会自动移除课程条件；可重新选择，或明确取消标准课程核验。'); return; }
      if (result.status !== 'READY' || !Array.isArray(result.catalog?.courses) || !result.catalog.courses.every(item => typeof item?.course_code === 'string' && item.course_code && typeof item.active === 'boolean')) throw new Error('课程目录响应不完整');
      snapshot = result;
      const courses = result.catalog.courses.filter(item => item.active === true);
      course.innerHTML = '<option value="">请选择具体标准课程</option>' + courses.map(item => `<option value="${escape(item.course_code)}">${escape(item.course_name ?? item.name ?? item.course_code)}（${escape(item.course_code)}）</option>`).join('');
      course.disabled = courses.length === 0;
      say(courses.length ? '请选择具体课程并填写判定日期；开始推荐时将重新核对资格。' : '当前目录没有启用课程；不会回退为无课程条件的推荐。');
    } catch (error) {
      if (!active() || ticket !== scopeTicket || request.signal.aborted || error?.name === 'AbortError') return;
      snapshot = null; course.innerHTML = '<option value="">课程暂不可用</option>';
      if ([403, 404].includes(error.status || error.code)) { scopes = []; scope.innerHTML = '<option value="">请重新读取有权限的课程目录</option>'; scope.disabled = true; }
      say('所选课程目录无法核实，请重新读取后选择；不会自动取消课程条件。');
    }
  }
  function getDraft() {
    return { enabled: enabled.checked, scope_id: scope.value, course_code: course.value, as_of: $('[data-k="as_of"]').value, accepted_levels: $('[data-k="accepted_levels"]').value, allowed_cities: $('[data-k="allowed_cities"]').value };
  }
  function getSelection() {
    if (!enabled.checked) return undefined;
    const values = getDraft();
    if (!active() || !snapshot || !scopes.some(item => String(item.scope_id) === values.scope_id) || String(snapshot.scope_id) !== values.scope_id) throw new Error('请重新读取课程目录，并明确选择本次标准课程。');
    if (!snapshot.catalog.courses.some(item => item.active === true && item.course_code === values.course_code)) throw new Error('请选择已确认目录中的具体启用课程。');
    const date = values.as_of;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || date.startsWith('0000') || Number.isNaN(Date.parse(date + 'T00:00:00Z')) || new Date(date + 'T00:00:00Z').toISOString().slice(0, 10) !== date) throw new Error('请明确填写有效的课程资格判定日期。');
    const result = { scope_id: snapshot.scope_id, expected_version: snapshot.version, course_code: values.course_code, as_of: date };
    for (const key of ['accepted_levels', 'allowed_cities']) {
      const list = values[key].split(/\r?\n/).map(item => item.trim()).filter(Boolean);
      if (new Set(list).size !== list.length) throw new Error('等级或城市限制请每行填写一个精确值，不重复填写。');
      result[key] = list;
    }
    return result;
  }
  function invalidate(message) { if (!active()) return; accessTicket++; accessController?.abort(); clearScope(); scopes = []; scope.innerHTML = '<option value="">请重新读取课程目录</option>'; scope.disabled = true; say(message || '课程或权限依据已变化，请重新读取目录选择。'); changed(); }
  enabled.onchange = () => { display(); changed(); };
  scope.onchange = selectScope;
  for (const element of fields.querySelectorAll('input, textarea, select')) if (element !== scope) { element.oninput = changed; element.onchange = changed; }
  $('[data-course-refresh]').onclick = () => refresh();
  function destroy() { if (!alive) return; alive = false; accessTicket++; scopeTicket++; accessController?.abort(); scopeController?.abort(); host.signal?.removeEventListener('abort', destroy); snapshot = null; scopes = []; root.innerHTML = ''; }
  host.signal?.addEventListener('abort', destroy, { once: true });
  display();
  return { ready: refresh(false), refresh, getDraft, getSelection, invalidate, destroy };
}
