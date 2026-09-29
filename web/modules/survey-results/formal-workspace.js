// Formal M07 workspace. Draft preview remains a separate, unchanged entry.
const MAX_FILE = 5 * 1024 * 1024, PAGE = 20;
const owners = new WeakMap();
const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
const int = (n, min = 0, max = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(n) && n >= min && n <= max;
const text = (s, max, empty = false) => typeof s === 'string' && s.length <= max && (empty || s.length > 0);
const stamp = value => typeof value === 'string' && /^\d{4}-\d\d-\d\dT/.test(value) && Number.isFinite(Date.parse(value));
const STATUS = { PENDING_REVIEW: '待复核', APPROVED: '已复核', REJECTED: '已退回', SUPERSEDED: '已被修正版替换', SUPERSEDED_PENDING: '待审版本已替换' };
const fail = () => { throw Object.assign(new Error('汇总返回内容不完整，已停止展示，请刷新核对。'), { viewError: true }); };
function policy(value) {
  if (!value || typeof value.zeroValid !== 'boolean' || value.confirmed !== true || !text(value.version, 96) || !text(value.evidenceRef, 96)
    || !Array.isArray(value.reviewerRoles) || !value.reviewerRoles.length || value.reviewerRoles.length > 32 || !value.reviewerRoles.every(x => text(x, 96))
    || value.blank !== 'EXCLUDE' || value.invalid !== 'EXCLUDE' || value.denominator !== 'PER_QUESTION_VALID' || value.rows !== 'KEEP_ALL'
    || value.displayScale !== 2 || value.rounding !== 'HALF_UP' || value.overallQuestionKey !== 'q10' || value.independentReviewer !== true || value.replacement !== 'REVIEW_BEFORE_REPLACE') fail();
  return { version: value.version, zeroValid: value.zeroValid, evidenceRef: value.evidenceRef, reviewerRoles: [...value.reviewerRoles].sort() };
}
function summary(value) {
  if (!value || !int(value.responseRowCount, 1, 10000) || value.overallQuestionKey !== 'q10' || !Array.isArray(value.questions) || value.questions.length !== 10) fail();
  const questions = value.questions.map((q, i) => {
    if (!q || q.key !== 'q' + (i + 1) || !text(q.label, 300) || ![q.validCount, q.blankCount, q.invalidCount].every(n => int(n, 0, 10000))
      || q.validCount + q.blankCount + q.invalidCount !== value.responseRowCount
      || (q.validCount === 0 ? q.averageText !== null : typeof q.averageText !== 'string' || !/^(?:[0-9]\.[0-9]{2}|10\.00)$/.test(q.averageText))) fail();
    return { key: q.key, label: q.label, validCount: q.validCount, blankCount: q.blankCount, invalidCount: q.invalidCount, averageText: q.averageText };
  });
  return { responseRowCount: value.responseRowCount, questions };
}
function snapshot(value, projectId) {
  if (!value || value.synthetic !== false || value.project_id !== projectId || !int(value.version, 0, 1000) || !Array.isArray(value.records) || value.records.length > value.version
    || !value.capabilities || typeof value.capabilities.prepare !== 'boolean' || typeof value.capabilities.review !== 'boolean') fail();
  const currentPolicy = value.policy === null ? null : policy(value.policy);
  if (!currentPolicy && (value.capabilities.prepare || value.capabilities.review)) fail();
  const records = [], byId = new Map(), tips = new Map(), active = new Set(); let last = 0;
  for (const raw of value.records) {
    if (!raw || !int(raw.import_id, last + 1, value.version) || !int(raw.series_id, 1, raw.import_id) || !int(raw.replaces_id, 0, raw.import_id - 1)
      || !Object.hasOwn(STATUS, raw.state) || typeof raw.current !== 'boolean' || raw.current && raw.state !== 'APPROVED'
      || !text(raw.reason, 500, true) || !text(raw.review_reason, 500, true) || !stamp(raw.imported_at) || !text(raw.importer_code, 120)) fail();
    if (raw.replaces_id === 0 ? raw.series_id !== raw.import_id || raw.reason !== '' : !raw.reason.trim() || byId.get(raw.replaces_id)?.series_id !== raw.series_id || tips.get(raw.series_id) !== raw.replaces_id) fail();
    const reviewed = ['APPROVED', 'REJECTED', 'SUPERSEDED'].includes(raw.state);
    if (reviewed ? !stamp(raw.reviewed_at) || !int(raw.review_event_id, raw.import_id + 1, value.version) || !text(raw.reviewer_code, 120) || raw.reviewer_code === raw.importer_code || raw.state === 'REJECTED' && !raw.review_reason.trim()
      : raw.review_reason !== '' || raw.reviewed_at !== '' || raw.reviewer_code !== '' || raw.review_event_id !== 0) fail();
    if (raw.current && active.has(raw.series_id)) fail();
    if (raw.current) active.add(raw.series_id);
    const record = { import_id: raw.import_id, series_id: raw.series_id, replaces_id: raw.replaces_id, current: raw.current, state: raw.state,
      summary: summary(raw.summary), policy: policy(raw.policy), reason: raw.reason, review_reason: raw.review_reason, imported_at: raw.imported_at, reviewed_at: raw.reviewed_at };
    records.push(record); byId.set(record.import_id, record); tips.set(record.series_id, record.import_id); last = record.import_id;
  }
  for (const r of records) if (r.state === 'PENDING_REVIEW' && tips.get(r.series_id) !== r.import_id || r.state === 'SUPERSEDED_PENDING' && tips.get(r.series_id) === r.import_id) fail();
  return { version: value.version, records, tips, policy: currentPolicy, capabilities: { prepare: value.capabilities.prepare, review: value.capabilities.review } };
}
const samePolicy = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const errorText = error => error?.viewError ? error.message : ({
  400: '操作内容不完整，请核对所选文件、记录和意见。', 401: '登录状态已失效，请重新登录。', 403: '当前账号无权完成此操作；复核还须由另一名获授权人员办理。',
  408: '处理已超时，请稍后重试。', 409: '该机构尚未启用正式统计规则，或项目、权限、规则及记录版本已变化。请刷新核对；尚未启用时，由获授权管理员从“统计规则”入口启用。评分草案仍可单独预览。',
  413: '文件超过大小上限，请选择不超过 5 MiB 的原平台 Excel。', 415: '文件格式不支持，请选择原平台导出的 .xlsx 工作簿。',
  429: '已有文件正在处理，请稍后重试。', 503: '正式汇总暂时无法读取，请稍后重试；这不表示没有已保存记录。',
}[error?.code || error?.status] || '连接或返回结果异常，请先核对记录，勿重复创建操作。');
const when = value => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未复核';
function encodeBytes(buffer) {
  const bytes = new Uint8Array(buffer), parts = [];
  for (let n = 0; n < bytes.length; n += 8192) parts.push(String.fromCharCode(...bytes.subarray(n, n + 8192)));
  return btoa(parts.join(''));
}

/** Dedicated modal slot. api uses the existing host's relative /api helper and cookie session. */
export function mount(root, context = {}) {
  if (!root || typeof root.querySelector !== 'function' || !int(context.projectId, 1) || typeof context.api !== 'function' || typeof context.getUser !== 'function') throw new TypeError('正式汇总需要明确项目、当前身份和原系统请求工具。');
  owners.get(root)?.destroy();
  const identity = context.getUser(), identityKey = JSON.stringify(identity), life = new AbortController();
  const state = { data: null, file: null, preview: null, reason: '', replaces: 0, reviewId: 0, reviewReason: '', selected: 0,
    acknowledged: false, busy: '', notice: '', tab: 'current', page: 0, intent: null, inspected: false, blocked: false };
  let alive = true, epoch = 0, readAbort = null, dirtyBefore = false;
  const node = key => root.querySelector('[data-formal-' + key + ']');
  const now = () => context.now ? +context.now() : Date.now();
  const dirty = () => !!(state.file || state.preview || state.reason || state.reviewReason || state.intent || ['reading', 'prepare', 'write'].includes(state.busy));
  const controller = { ready: null, refresh: () => load(), destroy, isDirty: dirty, beforeClose: () => !current() || !dirty() || (typeof context.confirmDiscard === 'function' && context.confirmDiscard(state.intent ? '操作结果尚未核实，关闭后请先查询项目记录，不要重复提交。确定关闭？' : '尚有未保存的评分汇总准备或意见，确定关闭？') === true) };
  owners.set(root, controller);
  function current() {
    if (!alive) return false;
    if (life.signal.aborted || context.signal?.aborted || root.isConnected === false || context.isCurrent?.() === false || context.getUser() !== identity || JSON.stringify(context.getUser()) !== identityKey || !identity) { destroy(); return false; }
    return true;
  }
  function destroy() {
    if (!alive) return; alive = false; epoch++; life.abort(); readAbort?.abort(); context.signal?.removeEventListener('abort', destroy);
    state.data = state.file = state.preview = state.intent = null; state.reason = state.reviewReason = ''; state.busy = '';
    if (owners.get(root) === controller) { owners.delete(root); root.replaceChildren(); }
    if (dirtyBefore) context.onDirtyChange?.(false);
  }
  const disabled = yes => yes ? ' disabled' : '';
  const rowLabel = r => {
    const series = [...new Set(state.data.records.map(x => x.series_id))];
    return '第 ' + (series.indexOf(r.series_id) + 1) + ' 批 · 第 ' + (state.data.records.filter(x => x.series_id === r.series_id).indexOf(r) + 1) + ' 次导入';
  };
  const tip = r => state.data?.tips.get(r.series_id) === r.import_id;
  const canPrepare = () => !state.busy && !state.intent && !state.blocked && state.data?.capabilities.prepare === true;
  const canReview = r => r && r.state === 'PENDING_REVIEW' && tip(r) && state.data?.capabilities.review === true && !state.busy && !state.intent && !state.blocked;
  const canConfirm = () => canPrepare() && state.preview?.expected_version === state.data.version && state.acknowledged && now() < Date.parse(state.preview.expires_at);
  function policyHtml(p, title = '本次统计口径') {
    if (!p) return '<p class="inline-note">该机构尚未启用正式统计规则，暂不能保存或复核。请由获授权管理员从“统计规则”入口启用；仍可通过原入口预览评分草案。</p>';
    return `<p class="modal-intro"><b>${esc(title)}</b>（口径版本 ${esc(p.version)}）：${p.zeroValid ? '0–10 分有效，零分参与统计' : '大于 0 且不超过 10 分有效，零分不参与统计'}；空白与异常分别排除；每题使用自己的有效评分数；逐行保留、不按人去重；均值按四舍五入保留两位小数。总体满意度取第 10 题。</p>`;
  }
  function summaryHtml(s, previous = null) {
    const value = q => q.averageText === null ? '—（暂无有效评分）' : esc(q.averageText);
    return `<p class="modal-intro">答卷记录数（未去重）：<b>${s.responseRowCount}</b>${previous ? `；当前生效版本：${previous.responseRowCount}` : ''}。各批次分别保留，不相加、不再平均。</p><div class="table-wrap"><table class="tbl"><thead><tr><th>题目</th><th>有效 / 空白 / 异常</th><th>${previous ? '所选版本均值' : '平均分'}</th>${previous ? '<th>当前生效版本均值</th><th>当前有效 / 空白 / 异常</th>' : ''}</tr></thead><tbody>${s.questions.map((q, i) => `<tr><td>${i + 1}. ${esc(q.label)}</td><td>${q.validCount} / ${q.blankCount} / ${q.invalidCount}</td><td>${value(q)}</td>${previous ? `<td>${value(previous.questions[i])}</td><td>${previous.questions[i].validCount} / ${previous.questions[i].blankCount} / ${previous.questions[i].invalidCount}</td>` : ''}</tr>`).join('')}</tbody></table></div>`;
  }
  function recordView() {
    const record = state.data?.records.find(r => r.import_id === state.selected); if (!record) return '';
    const active = state.data.records.find(r => r.series_id === record.series_id && r.current && r.import_id !== record.import_id);
    return `<section class="card"><h3>${esc(rowLabel(record))} · ${STATUS[record.state]}${record.current ? ' · 当前生效' : ''}</h3><p class="modal-intro">导入：${esc(when(record.imported_at))}；复核：${esc(when(record.reviewed_at))}</p><p><b>更正原因：</b>${esc(record.reason || '新独立批次，无更正原因')}</p><p><b>${record.state === 'REJECTED' ? '退回原因' : '复核意见'}：</b>${esc(record.review_reason || (record.reviewed_at ? '未填写意见' : '尚未复核'))}</p>${policyHtml(record.policy, '此版本冻结口径')}${active ? policyHtml(active.policy, '当前生效版本口径') : ''}${summaryHtml(record.summary, active?.summary)}
      ${state.reviewId === record.import_id && canReview(record) ? `<div class="form-item"><label>复核意见 / 退回原因<textarea data-formal-review-reason maxlength="500" rows="3" placeholder="退回必须填写原因；勿填写答卷或人员明细">${esc(state.reviewReason)}</textarea></label></div><p class="modal-intro">须由另一名获授权人员独立复核；批准后本版本才成为本批次当前结果。待审修正版不会提前替换原结果。</p><div class="toolbar"><button class="btn" type="button" data-formal-approve>确认复核通过</button><button class="btn red" type="button" data-formal-reject>退回修正</button></div>` : ''}</section>`;
  }
  function recordsHtml() {
    if (!state.data) return '';
    const list = state.data.records.filter(r => state.tab === 'history' || state.tab === 'current' && r.current || state.tab === 'pending' && r.state === 'PENDING_REVIEW');
    state.page = Math.max(0, Math.min(state.page, Math.ceil(list.length / PAGE) - 1));
    return `<div class="toolbar">${[['current', '当前生效'], ['pending', '待复核'], ['history', '全部历史']].map(([key, label]) => `<button type="button" class="btn sm ${state.tab === key ? '' : 'gray'}" data-formal-tab="${key}" aria-pressed="${state.tab === key}">${label}</button>`).join('')}</div><div class="table-wrap"><table class="tbl"><thead><tr><th>批次 / 导入次序</th><th>状态</th><th>答卷记录数</th><th>总体满意度</th><th>导入时间</th><th>操作</th></tr></thead><tbody>${list.slice(state.page * PAGE, (state.page + 1) * PAGE).map(r => `<tr><td>${esc(rowLabel(r))}</td><td>${STATUS[r.state]}${r.current ? '（当前生效）' : ''}</td><td>${r.summary.responseRowCount}</td><td>${esc(r.summary.questions[9].averageText ?? '—')}</td><td>${esc(when(r.imported_at))}</td><td><button type="button" class="btn gray sm" data-formal-detail="${r.import_id}">查看</button>${tip(r) && state.data.capabilities.prepare ? `<button type="button" class="btn gray sm" data-formal-correct="${r.import_id}"${disabled(!canPrepare())}>提交修正版</button>` : ''}${r.state === 'PENDING_REVIEW' && state.data.capabilities.review ? `<button type="button" class="btn sm" data-formal-review="${r.import_id}"${disabled(!canReview(r))}>复核</button>` : ''}</td></tr>`).join('') || '<tr><td colspan="6">当前没有此类记录。未复核的内容不会进入当前生效结果。</td></tr>'}</tbody></table></div><div class="toolbar"><button type="button" class="btn gray sm" data-formal-prev${disabled(state.page === 0)}>上一页</button><span>第 ${state.page + 1} 页 · 共 ${list.length} 条</span><button type="button" class="btn gray sm" data-formal-next${disabled((state.page + 1) * PAGE >= list.length)}>下一页</button></div>${recordView()}`;
  }
  function previewHtml() {
    const p = state.preview; if (!p) return '';
    const duplicate = { NONE: '未发现同文件重复。', SAME_SCORES_CANDIDATE: '发现评分统计相同的记录；这不能证明同一批，请核对是否确为另一份问卷。', EXPLICIT_POLICY_RECALCULATION: '本次是同一原文件按新政策重新统计的修正版。必须重新确认并由另一人复核，原生效结果不会自动重算。' }[p.duplicate];
    return `<section class="card"><h3>确认前预览 · 尚未保存</h3>${policyHtml(p.policy)}<p class="inline-note">${esc(duplicate)}</p><p><b>更正原因：</b>${esc(p.reason || '新独立批次，无更正原因')}</p>${summaryHtml(p.summary)}<label><input type="checkbox" data-formal-ack${state.acknowledged ? ' checked' : ''}${disabled(!!state.busy || !!state.intent)}>我已核对项目、批次、统计口径及更正原因，确认提交待复核</label><p class="modal-intro">凭证有效至 ${esc(when(p.expires_at))}；此操作不会直接形成正式生效结果。</p><div class="toolbar"><button type="button" class="btn" data-formal-confirm${disabled(!canConfirm())}>确认提交待复核</button><button type="button" class="btn gray" data-formal-discard-preview${disabled(!!state.busy || !!state.intent)}>放弃本次预览</button></div></section>`;
  }
  function render() {
    if (!current()) return;
    const pending = !!state.intent, disabledInput = !canPrepare();
    root.innerHTML = `<section aria-label="正式评分汇总"><p class="modal-intro">${esc(context.projectName || '当前项目')} · 原平台 Excel 只提取逐题汇总，不发问卷，不显示原答卷或个人明细。</p><div class="toolbar"><button type="button" class="btn gray" data-formal-refresh${disabled(!!state.busy)}>${pending ? '核对操作结果' : '刷新正式记录'}</button></div><p role="status" aria-live="polite" class="field-error" data-formal-notice>${esc(state.notice || (state.busy ? '正在处理，请稍候…' : ''))}</p>
      ${pending ? `<p class="inline-note">上次操作结果尚未核实。请先读取服务器记录，再重取原操作回执；期间不能创建新提交。</p><button type="button" class="btn gray" data-formal-retry${disabled(!state.inspected || !!state.busy || !state.data || state.blocked || !(state.intent.kind === 'confirm' ? state.data.capabilities.prepare : state.data.capabilities.review))}>重取原操作回执</button>` : ''}
      ${state.data ? `${policyHtml(state.data.policy)}${state.data.capabilities.prepare ? `<details ${state.file || state.reason || state.replaces || state.preview ? 'open' : ''}><summary>准备正式汇总 / 修正版</summary><div class="form-grid"><div class="form-item"><label>提交类型<select data-formal-replaces${disabled(disabledInput)}><option value="0"${state.replaces === 0 ? ' selected' : ''}>新独立批次（不与其他批次合并）</option>${state.data.records.filter(tip).map(r => `<option value="${r.import_id}"${state.replaces === r.import_id ? ' selected' : ''}>修正：${esc(rowLabel(r))} · ${STATUS[r.state]}</option>`).join('')}</select></label></div><div class="form-item"><label>原平台评分 Excel（最大 5 MiB）<input type="file" data-formal-file accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"${disabled(disabledInput)}></label><small>${state.file ? '文件已在本页准备，尚未提交' : '只处理 .xlsx；不保留原始答卷'}</small></div></div><div class="form-item"><label>更正原因<textarea data-formal-reason maxlength="500" rows="2"${disabled(disabledInput || state.replaces === 0)} placeholder="修正版必须说明更正原因，勿填写答卷或人员明细">${esc(state.reason)}</textarea></label></div><div class="toolbar"><button type="button" class="btn" data-formal-prepare${disabled(!canPrepare() || !state.file || state.replaces > 0 && !state.reason.trim())}>生成确认前预览</button><button type="button" class="btn gray" data-formal-clear${disabled(!!state.busy || pending)}>清空准备内容</button></div></details>` : '<p class="modal-intro">当前仅提供已获授权的查看或复核操作；没有正式保存权限时，仍可使用原草案入口。</p>'}${previewHtml()}${recordsHtml()}` : ''}</section>`;
    const wire = (key, fn, event = 'onclick') => { const element = node(key); if (element) element[event] = fn; };
    wire('refresh', () => load()); wire('retry', () => write(state.intent, true)); wire('file', event => readFile(event?.target?.files?.[0] || node('file')?.files?.[0]), 'onchange');
    wire('replaces', () => { if (!current() || !canPrepare()) return; state.replaces = Number(node('replaces').value); state.reason = ''; invalidate(); render(); }, 'onchange');
    wire('reason', () => { if (!current() || !canPrepare() || !state.replaces) return; state.reason = node('reason').value; invalidate(); updateInputControls(); }, 'oninput');
    wire('ack', () => { if (!current() || state.busy || state.intent) return; state.acknowledged = node('ack').checked === true; node('confirm').disabled = !canConfirm(); }, 'onchange');
    wire('prepare', prepare); wire('confirm', confirm); wire('discard-preview', () => { if (!current() || state.busy || state.intent) return; invalidate(); render(); });
    wire('clear', () => { if (!current() || state.busy || state.intent) return; clearForm(); render(); });
    wire('review-reason', () => { if (!current() || state.busy || state.intent) return; state.reviewReason = node('review-reason').value; notifyDirty(); }, 'oninput');
    wire('approve', () => review('APPROVE')); wire('reject', () => review('REJECT'));
    for (const b of root.querySelectorAll('[data-formal-tab]')) b.onclick = () => { if (!current()) return; state.tab = b.dataset.formalTab; state.page = 0; render(); };
    wire('prev', () => { if (!current() || node('prev').disabled) return; state.page--; render(); }); wire('next', () => { if (!current() || node('next').disabled) return; state.page++; render(); });
    for (const b of root.querySelectorAll('[data-formal-detail], [data-formal-correct], [data-formal-review]')) b.onclick = () => {
      if (!current() || state.busy || state.intent) return;
      const id = Number(b.dataset.formalDetail || b.dataset.formalCorrect || b.dataset.formalReview), r = state.data.records.find(x => x.import_id === id); if (!r) return;
      if ((b.dataset.formalCorrect && dirty() || state.reviewReason && id !== state.selected) && context.confirmDiscard?.('切换记录会清除尚未提交的准备内容或复核意见，确定继续？') !== true) return;
      if (b.dataset.formalCorrect) { if (!canPrepare() || !tip(r)) return; clearForm(); state.replaces = id; }
      if (b.dataset.formalReview) { if (!canReview(r)) return; state.reviewId = id; state.reviewReason = ''; }
      else { state.reviewId = 0; state.reviewReason = ''; }
      state.selected = id; render();
    };
    notifyDirty();
  }
  function notifyDirty() { const changed = dirty(); if (changed !== dirtyBefore) { dirtyBefore = changed; context.onDirtyChange?.(changed); } }
  function updateInputControls() {
    if (node('prepare')) node('prepare').disabled = !canPrepare() || !state.file || state.replaces > 0 && !state.reason.trim();
    // An edited reason invalidates the old preview immediately, without replacing the focused textarea.
    if (node('confirm')) { render(); node('reason')?.focus(); } else notifyDirty();
  }
  function invalidate() { state.preview = null; state.acknowledged = false; }
  function clearForm() { invalidate(); state.file = null; state.reason = ''; state.replaces = 0; state.reviewId = 0; state.reviewReason = ''; }
  async function request(path, body, signal = life.signal) { return context.api(path, { method: body === undefined ? 'GET' : 'POST', ...(body === undefined ? {} : { body }), quiet: true, signal }); }
  function denied(error) {
    if (![401, 403].includes(error?.code || error?.status)) return false;
    state.data = state.file = state.preview = state.intent = null; state.reason = state.reviewReason = ''; state.blocked = true;
    if ((error.code || error.status) === 401) context.onUnauthorized?.(); return true;
  }
  async function load(successMessage = '') {
    if (!current() || ['reading', 'prepare', 'write'].includes(state.busy)) return false;
    const ticket = ++epoch; readAbort?.abort(); readAbort = new AbortController(); state.busy = 'read'; state.data = null; invalidate(); state.reviewId = 0; state.reviewReason = ''; state.inspected = false; state.notice = successMessage; render();
    try {
      const result = await request('/survey-results?project_id=' + context.projectId, undefined, readAbort.signal);
      if (!current() || ticket !== epoch) return false;
      state.data = snapshot(result, context.projectId); state.blocked = false; state.inspected = !!state.intent;
      if (!state.data.records.some(r => r.import_id === state.replaces && tip(r))) { state.replaces = 0; state.reason = ''; }
      state.notice = successMessage || (state.intent ? '已重新读取服务器记录。请重取原操作回执以确认结果，勿新建重复提交。' : ''); return true;
    } catch (error) {
      if (!current() || ticket !== epoch) return false; denied(error);
      state.notice = (successMessage ? successMessage + ' 但列表暂未刷新，请再次读取核对。' : '') + errorText(error); return false;
    } finally { if (current() && ticket === epoch) { state.busy = ''; render(); } }
  }
  async function readFile(file) {
    if (!current() || !canPrepare() || !file) return false;
    invalidate(); state.file = null; state.busy = 'reading'; state.notice = ''; const ticket = ++epoch; render();
    try {
      if (typeof file.name !== 'string' || !/\.xlsx$/i.test(file.name) || file.name.length > 255 || /[\x00-\x1f\x7f]/.test(file.name) || !int(file.size, 1, MAX_FILE)) throw Object.assign(Error('请选择不超过 5 MiB 的原平台 .xlsx 工作簿。'), { viewError: true });
      const bytes = await file.arrayBuffer(); if (!current() || ticket !== epoch) return false;
      if (!int(bytes.byteLength, 1, MAX_FILE) || bytes.byteLength !== file.size) throw Object.assign(Error('文件读取不完整，请重新选择。'), { viewError: true });
      state.file = { fileName: file.name, xlsxBase64: encodeBytes(bytes) }; return true;
    } catch (error) { if (current() && ticket === epoch) state.notice = errorText(error); return false; }
    finally { if (current() && ticket === epoch) { state.busy = ''; render(); } }
  }
  async function prepare() {
    if (!current() || !canPrepare() || !state.file) return false;
    if (!int(state.replaces) || !text(state.reason, 500, true) || state.replaces > 0 && (!state.reason.trim() || !state.data.records.some(r => r.import_id === state.replaces && tip(r))) || state.replaces === 0 && state.reason !== '') { state.notice = '请指定最新记录并填写不超过 500 字的更正原因。'; render(); return false; }
    const body = { fileName: state.file.fileName, xlsxBase64: state.file.xlsxBase64, projectId: context.projectId, replacesId: state.replaces, reason: state.reason };
    const expected = state.data.version, savedPolicy = state.data.policy, ticket = ++epoch; state.busy = 'prepare'; state.notice = ''; invalidate(); render();
    try {
      const result = await request('/survey-response-imports/prepare', body); if (!current() || ticket !== epoch) return false;
      if (result?.state === 'ERROR' && result.canConfirm === false) { state.notice = '评分文件未通过检查。请用原草案预览查看安全的格式提示，核对后重新选择文件。'; return false; }
      if (result?.state === 'DUPLICATE' && result.canConfirm === false && result.duplicate?.status === 'EXACT_FILE') { state.notice = '该项目已经导入过这份文件，不能重复保存。请查询已有记录；正式政策变更重算须明确指定修正版。'; return false; }
      if (!result || result.state !== 'READY_TO_CONFIRM' || result.canConfirm !== true || result.policyConfirmed !== true || result.review_required !== true
        || result.project_id !== context.projectId || result.expected_version !== expected || result.replaces_id !== body.replacesId || result.reason !== body.reason
        || !/^[a-f0-9]{64}$/.test(result.preview_token) || !stamp(result.expires_at) || Date.parse(result.expires_at) <= now()
        || !['NONE', 'SAME_SCORES_CANDIDATE', 'EXPLICIT_POLICY_RECALCULATION'].includes(result.duplicate?.status)) fail();
      const p = policy(result.policy); if (!samePolicy(savedPolicy, p) || result.duplicate.status === 'EXPLICIT_POLICY_RECALCULATION' && !body.replacesId) fail();
      state.preview = { project_id: result.project_id, expected_version: expected, preview_token: result.preview_token, expires_at: result.expires_at, replaces_id: result.replaces_id,
        reason: result.reason, summary: summary(result.summary), policy: p, duplicate: result.duplicate.status }; return true;
    } catch (error) { if (current() && ticket === epoch) { denied(error); state.notice = errorText(error); } return false; }
    finally { if (current() && ticket === epoch) { state.file = null; state.busy = ''; render(); } }
  }
  function requestId() { const id = context.requestId ? context.requestId() : 'm07-' + globalThis.crypto.randomUUID(); if (!/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}$/.test(id)) fail(); return id; }
  async function confirm() {
    if (!current() || !canConfirm()) { if (current() && state.preview && now() >= Date.parse(state.preview.expires_at)) { invalidate(); state.notice = '确认凭证已过期，请重新选择文件并生成预览。'; render(); } return false; }
    return write({ kind: 'confirm', body: { project_id: context.projectId, preview_token: state.preview.preview_token, request_id: requestId(), acknowledged: true } });
  }
  async function review(decision) {
    const r = state.data?.records.find(x => x.import_id === state.reviewId); if (!current() || !canReview(r)) return false;
    if (!text(state.reviewReason, 500, true) || decision === 'REJECT' && !state.reviewReason.trim()) { state.notice = '退回必须填写原因，复核意见不得超过 500 字。'; render(); return false; }
    return write({ kind: 'review', body: { project_id: context.projectId, import_id: r.import_id, expected_version: state.data.version, decision, reason: state.reviewReason, request_id: requestId() } });
  }
  async function write(intent, retry = false) {
    if (!current() || !intent || state.busy || state.blocked || retry && (!state.inspected || state.intent !== intent) || !retry && state.intent
      || !(intent.kind === 'confirm' ? state.data?.capabilities.prepare : state.data?.capabilities.review)) return false;
    state.intent = intent; state.inspected = false; state.busy = 'write'; state.notice = ''; const ticket = ++epoch; render(); let knownSuccess = false, reload = false;
    try {
      const result = await request('/survey-results/' + intent.kind, intent.body); if (!current() || ticket !== epoch) return false;
      const expectedState = intent.kind === 'confirm' ? 'PENDING_REVIEW' : intent.body.decision === 'APPROVE' ? 'APPROVED' : 'REJECTED';
      if (!result || result.project_id !== context.projectId || !int(result.result_version, 1, 1000) || result.reload_required !== true || typeof result.replayed !== 'boolean'
        || (result.replayed ? result.state !== 'RECORDED' : result.state !== expectedState)) fail();
      knownSuccess = true; state.intent = null; clearForm(); state.selected = 0; state.notice = '操作已记录，正在重新读取服务器记录。'; reload = true;
    } catch (error) {
      if (!current() || ticket !== epoch) return false;
      const status = error?.code || error?.status;
      if (denied(error)) state.notice = errorText(error);
      else if ([400, 409, 413, 415, 429].includes(status)) { state.intent = null; invalidate(); state.data = null; state.notice = errorText(error); reload = true; }
      else { state.data = null; invalidate(); state.notice = '操作结果尚未核实。' + errorText(error); }
    } finally {
      if (current() && ticket === epoch) { state.busy = ''; render(); }
    }
    if (current() && reload) await load(knownSuccess ? '操作已记录。' : state.notice);
    if (knownSuccess && current()) { try { await context.onSaved?.(); } catch { /* A host refresh failure never turns a committed action into an unknown write. */ } }
    return knownSuccess;
  }
  context.signal?.addEventListener('abort', destroy, { once: true });
  controller.ready = load(); return controller;
}
