// Original reports-page adapter. Server snapshots own accounting, ranking and authorization.
const MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const BASE = '/management-settlement-reports';
const MAX_FILE = 16 * 1024 * 1024;
const OWNER = Symbol('financial-report-owner');
const HOURS = [['ESTIMATED', '预计课时'], ['PLANNED', '计划课时'], ['ACTUAL', '实际课时'], ['PAYABLE', '计酬课时']];
const ACTIVITIES = { TEACHING: '授课', SOLO_DEVELOPMENT: '独立研发', JOINT_DEVELOPMENT: '联合研发', LEGACY: '旧制已核准记录' };
const KINDS = { CONFIRMED: '核准', LEGACY_OPENING: '旧制期初', ADJUSTMENT: '调整', REVERSAL: '冲回', REBOOK: '重记', PAYMENT: '支付', REFUND: '退款' };
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const code = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$/.test(value);
const text = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 4096;
const quantity = value => typeof value === 'string' && /^-?(0|[1-9][0-9]{0,69})(\.[0-9]{1,8})?$/.test(value);
const money = value => typeof value === 'string' && /^-?(0|[1-9][0-9]{0,69})\.[0-9]{2}$/.test(value);
const natural = value => Number.isSafeInteger(value) && value >= 0;
const positive = value => natural(value) && value > 0;
const nullableId = value => value === null || positive(value);
const nullableCode = value => value === null || code(value);
const validDate = value => typeof value === 'string' && /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value) && !value.startsWith('0000') && Number.isFinite(Date.parse(value + 'T00:00:00Z')) && new Date(value + 'T00:00:00Z').toISOString().slice(0, 10) === value;
const list = value => Array.isArray(value) && value.length <= 50000;
const unique = values => new Set(values).size === values.length;
const fail = () => { throw Error('统计结果无法核实，请重新查询。'); };

function aggregate(row, payment) {
  if (!object(row) || !money(row.amount) || !money(row.positive_amount) || !money(row.negative_amount) || !natural(row.entry_count) || !object(row.hours)) return false;
  return HOURS.every(([key]) => {
    const h = row.hours[key];
    if (!object(h) || h.applicable !== !payment) return false;
    if (payment) return h.total === null && h.known_subtotal === null && h.missing_count === null;
    return quantity(h.known_subtotal) && natural(h.missing_count) && h.missing_count <= row.entry_count && (h.missing_count === 0 ? quantity(h.total) : h.total === null);
  });
}

function validate(view, filters) {
  if (!object(view) || view.schema_version !== 'M06-SETTLEMENT-1' || view.currency !== 'CNY' || view.tie_rule !== 'COMPETITION'
    || view.source_coverage !== 'APPROVED_FROZEN_LEDGER_ONLY' || !text(view.coverage_note)
    || !/^[a-f0-9]{64}$/.test(view.snapshot_version || '') || view.permissions?.read !== true || typeof view.permissions.export !== 'boolean'
    || typeof view.export_available !== 'boolean' || view.can_export !== view.permissions.export
    || view.export_available && !view.can_export || !validDate(view.start) || !validDate(view.end)
    || ['start', 'end', 'date_basis', 'rank_metric', 'hour_basis'].some(key => view[key] !== filters[key])) fail();
  const payment = view.date_basis === 'PAYMENT';
  if (view.hours_applicable !== !payment || !aggregate(view.totals, payment)) fail();
  for (const key of ['available_organizations', 'available_organization_options', 'selected_organizations', 'teachers', 'organizations', 'details', 'code_gaps', 'activity_totals']) if (!list(view[key])) fail();
  const available = view.available_organizations, selected = view.selected_organizations;
  if (!available.length || !available.every(code) || !unique(available) || !selected.length || !selected.every(v => available.includes(v)) || !unique(selected)) fail();
  const expected = filters.organizations ? [filters.organizations] : available;
  if (selected.length !== expected.length || !selected.every(v => expected.includes(v))) fail();
  const options = view.available_organization_options;
  if (options.length !== available.length || !options.every(v => object(v) && available.includes(v.organization_code) && text(v.display_name)) || !unique(options.map(v => v.organization_code))) fail();
  if (!view.organizations.every(r => selected.includes(r?.organization_code) && aggregate(r, payment)) || !unique(view.organizations.map(r => r.organization_code))) fail();
  if (!view.teachers.every(r => object(r) && nullableId(r.teacher_id) && nullableCode(r.teacher_code) && list(r.teacher_codes) && r.teacher_codes.every(code)
    && (r.teacher_display_name === null || text(r.teacher_display_name)) && aggregate(r, payment)
    && (r.rank_value === null ? r.rank === null : (view.rank_metric === 'FEE' ? money(r.rank_value) : quantity(r.rank_value)) && positive(r.rank)))) fail();
  if (view.details.length !== view.totals.entry_count || !view.details.every(r => object(r) && code(r.entry_code) && selected.includes(r.organization_code)
    && validDate(r.date) && r.date >= view.start && r.date <= view.end && Object.hasOwn(KINDS, r.kind) && Object.hasOwn(ACTIVITIES, r.activity)
    && positive(r.project_id) && nullableId(r.teacher_id) && nullableId(r.course_id) && nullableCode(r.teacher_code) && nullableCode(r.course_code)
    && (r.teacher_display_name === null || text(r.teacher_display_name)) && money(r.amount) && object(r.hours)
    && typeof r.hours_counted === 'boolean' && (!payment || r.hours_counted === false)
    && ['hours', 'hours_before', 'hours_after'].every(field => object(r[field]) && HOURS.every(([key]) => payment ? r[field][key] === null : r[field][key] === null || quantity(r[field][key]))))) fail();
  if (view.export_available !== (view.can_export && view.code_gaps.length === 0)) fail();
  return view;
}

function initialRange(now) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit' }).formatToParts(now);
  const month = parts.find(p => p.type === 'year').value + '-' + parts.find(p => p.type === 'month').value;
  const end = new Date(month + '-01T00:00:00Z'); end.setUTCMonth(end.getUTCMonth() + 1); end.setUTCDate(0);
  return { start: month + '-01', end: end.toISOString().slice(0, 10) };
}

/**
 * mount(root, {api,getUser,isCurrent,renderTable,fetchDownload,saveFile,onUnauthorized?,signal?,now?})
 * api: original host API (relative /management-... path, unpacked data).
 * fetchDownload: same-origin raw Response, accepts original API-relative path and fetch options.
 * saveFile: synchronous browser download of the validated Blob/filename; may return URL cleanup.
 * Call destroy() on route cleanup. No module navigation, global styles or business writes.
 */
export function mount(root, host) {
  for (const name of ['api', 'getUser', 'isCurrent', 'renderTable', 'fetchDownload', 'saveFile']) if (typeof host?.[name] !== 'function') throw TypeError('Missing reports host: ' + name);
  if (!root?.querySelector) throw TypeError('Missing reports root');
  root[OWNER]?.destroy();
  const identity = host.getUser(), initial = initialRange(host.now?.() || new Date());
  let alive = true, sequence = 0, request = null, fileRequest = null, snapshot = null, organizations = [], tab = 'teachers', page = 0;
  const releases = new Set(), owner = { destroy }; root[OWNER] = owner;
  const active = () => alive && root[OWNER] === owner && !host.signal?.aborted && root.isConnected !== false && host.getUser() === identity && host.isCurrent();
  const ensure = () => { if (active()) return true; destroy(); return false; };
  const node = key => root.querySelector('[data-financial-' + key + ']');
  const icons = () => host.refreshIcons?.(root);
  function cancel() { sequence++; request?.abort(); fileRequest?.abort(); request = fileRequest = null; snapshot = null; for (const release of releases) release(); releases.clear(); }
  function destroy() { if (!alive) return; alive = false; cancel(); host.signal?.removeEventListener('abort', destroy); if (root[OWNER] === owner) { delete root[OWNER]; root.replaceChildren(); } }
  root.innerHTML = `<div class="card-heading"><div><h2>课酬与支付统计</h2><p>按授课日期查看应计金额，或按支付日期查看收付流水；课时与金额均来自已核准记录。</p></div></div>
    <div class="toolbar" style="flex-wrap:wrap">
      <label class="reviewed-filter"><span>开始日期</span><input type="date" data-financial-start aria-label="财务统计开始日期" value="${initial.start}"></label>
      <label class="reviewed-filter"><span>结束日期</span><input type="date" data-financial-end aria-label="财务统计结束日期" value="${initial.end}"></label>
      <select class="select-filter" data-financial-basis aria-label="财务统计日期口径"><option value="TEACHING">授课日期（应计）</option><option value="PAYMENT">支付日期（收付）</option></select>
      <select class="select-filter" data-financial-organization aria-label="财务统计机构"><option value="">全部可查看机构</option></select>
      <select class="select-filter" data-financial-rank aria-label="讲师排名指标"><option value="HOURS">按课时排名</option><option value="FEE">按金额排名</option></select>
      <select class="select-filter" data-financial-hour aria-label="排名课时口径">${HOURS.map(([key, label]) => `<option value="${key}"${key === 'ACTUAL' ? ' selected' : ''}>${label}</option>`).join('')}</select>
      <button type="button" class="btn" data-financial-query>查询</button>
    </div><p class="modal-intro">筛选仅作用于本卡片，原经营图表与文字报告使用各自口径。</p><div data-financial-result aria-live="polite"></div>`;
  function resetOptions() { organizations = []; node('organization').innerHTML = '<option value="">全部可查看机构（重新查询以核实）</option>'; }
  function showError(error, downloading = false) {
    const status = Number(error?.status || error?.code || 0);
    cancel(); if ([401, 403].includes(status)) resetOptions();
    const message = status === 401 ? '登录已失效，请重新登录。' : status === 403 ? '当前账号无法查看或导出所选机构，请重新核对权限。'
      : status === 409 ? '账目来源或权限已变化，旧结果已清除，请重新查询。'
      : status === 503 ? '统计来源暂时无法核实，请稍后重试；这不表示没有金额。'
      : error?.localMessage || '统计结果暂时无法核实，请重新查询。';
    node('result').innerHTML = `<div class="error-state"><h3>${downloading ? '下载未完成' : '课酬统计暂时无法加载'}</h3><p>${esc(message)}</p></div>`;
    root.setAttribute('aria-busy', 'false'); if (status === 401) host.onUnauthorized?.(); icons();
  }
  function filters() {
    const selected = { start: node('start').value, end: node('end').value, date_basis: node('basis').value, rank_metric: node('rank').value, hour_basis: node('hour').value };
    const org = node('organization').value;
    if (!validDate(selected.start) || !validDate(selected.end) || selected.start > selected.end) throw Object.assign(Error(), { localMessage: '请填写有效的起止日期，开始日期不能晚于结束日期。' });
    if (!['TEACHING', 'PAYMENT'].includes(selected.date_basis) || !['HOURS', 'FEE'].includes(selected.rank_metric) || !HOURS.some(([key]) => key === selected.hour_basis)
      || selected.date_basis === 'PAYMENT' && selected.rank_metric !== 'FEE' || org && !organizations.some(v => v.organization_code === org)) fail();
    if (org) selected.organizations = org;
    return selected;
  }
  const hourText = h => !h.applicable ? '不适用' : h.total !== null ? esc(h.total) : `待补齐<br><small>已知 ${esc(h.known_subtotal)} · 缺 ${h.missing_count} 条</small>`;
  const teacherText = row => esc(row.teacher_display_name || row.teacher_code || '讲师资料待核实');
  const organizationText = code => esc(organizations.find(v => v.organization_code === code)?.display_name || '机构资料待核实');
  function renderTable() {
    if (!ensure() || !snapshot) return;
    const view = snapshot, payment = view.date_basis === 'PAYMENT';
    const common = [{ k: 'entry_count', l: '分录数', align: 'right' }, ...HOURS.map(([key, label]) => ({ k: key, l: label, align: 'right', render: r => hourText(r.hours[key]) })), { k: 'amount', l: payment ? '收付净额（元）' : '应计净额（元）', align: 'right', render: r => esc(r.amount) }];
    let columns, rows;
    if (tab === 'teachers') { rows = view.teachers; columns = [{ k: 'rank', l: '名次', render: r => r.rank === null ? '未排名' : String(r.rank) }, { k: 'teacher_display_name', l: '讲师', render: teacherText }, ...common]; }
    else if (tab === 'organizations') { rows = view.organizations; columns = [{ k: 'organization_code', l: '机构', render: r => organizationText(r.organization_code) }, ...common]; }
    else { rows = view.details; columns = [{ k: 'date', l: payment ? '支付日期' : '授课日期' }, { k: 'teacher_display_name', l: '讲师', render: teacherText }, { k: 'organization_code', l: '机构', render: r => organizationText(r.organization_code) },
      { k: 'activity', l: '业务', render: r => ACTIVITIES[r.activity] }, { k: 'kind', l: '分录类型', render: r => KINDS[r.kind] }, { k: 'project_id', l: '项目系统记录 ID' },
      ...HOURS.map(([key, label]) => ({ k: key, l: label + '增减', align: 'right', render: r => payment ? '不适用' : r.hours[key] === null ? '无法比较' : esc(r.hours[key]) })),
      { k: 'hours_counted', l: '课时统计', render: r => payment ? '不适用' : r.hours_counted ? '本日期净课时' : '历史修订' },
      ...HOURS.map(([key, label]) => ({ k: 'after_' + key, l: '调整后' + label, align: 'right', render: r => payment ? '不适用' : r.hours_after[key] === null ? '待补齐' : esc(r.hours_after[key]) })), { k: 'amount', l: '金额（元）', align: 'right', render: r => esc(r.amount) }]; }
    const pages = Math.max(1, Math.ceil(rows.length / 20)); page = Math.max(0, Math.min(page, pages - 1));
    node('table').innerHTML = rows.length ? host.renderTable(columns, rows.slice(page * 20, (page + 1) * 20).map((row, index) => ({ ...row, id: page * 20 + index + 1 })), null)
      + `<div class="toolbar"><button type="button" class="btn gray" data-financial-prev ${page === 0 ? 'disabled' : ''}>上一页</button><span>第 ${page + 1} / ${pages} 页 · 共 ${rows.length} 条</span><button type="button" class="btn gray" data-financial-next ${page + 1 === pages ? 'disabled' : ''}>下一页</button></div>`
      : '<div class="empty-state"><h3>所选范围暂无已核准账目</h3><p>未核准或未迁移的记录不计入本统计，不能据此推断全部业务没有金额。</p></div>';
    for (const key of ['prev', 'next']) if (node(key)) node(key).onclick = () => { if (!ensure() || snapshot !== view) return; if (key === 'prev' ? page > 0 : page + 1 < pages) { page += key === 'prev' ? -1 : 1; renderTable(); } };
    for (const button of root.querySelectorAll('[data-financial-tab]')) button.setAttribute('aria-pressed', String(button.dataset.financialTab === tab));
    icons();
  }
  function render() {
    const view = snapshot, payment = view.date_basis === 'PAYMENT';
    node('result').innerHTML = `<p class="modal-intro">${esc(view.coverage_note)}</p><p class="modal-intro">${payment ? '金额按支付/退款发生日期统计；支付流水不分摊四类课时。' : '四类课时分别统计，不相加；课时按各事项在该日期的最终记录计算，历史修订不重复计入。金额包含本范围内核准及调整明细。'}</p>
      <div class="module-summary four">${HOURS.map(([key, label]) => `<div><small>${label}</small><b>${hourText(view.totals.hours[key])}</b></div>`).join('')}</div>
      <div class="module-summary three"><div><small>${payment ? '收付净额' : '应计净额'}（元）</small><b>${esc(view.totals.amount)}</b></div><div><small>正向金额（元）</small><b>${esc(view.totals.positive_amount)}</b></div><div><small>冲减 / 退款（元）</small><b>${esc(view.totals.negative_amount)}</b></div></div>
      <p class="modal-intro">共 ${view.totals.entry_count} 条分录。讲师名次按服务端${view.rank_metric === 'FEE' ? '金额' : HOURS.find(([key]) => key === view.hour_basis)[1]}排列；并列后跳位。下方排名、汇总与明细来自同一次查询。</p>
      <div class="toolbar" style="flex-wrap:wrap"><button type="button" class="btn gray" data-financial-tab="teachers">讲师排名</button><button type="button" class="btn gray" data-financial-tab="organizations">机构汇总</button><button type="button" class="btn gray" data-financial-tab="details">逐笔明细</button><button type="button" class="btn" data-financial-export ${view.export_available ? '' : 'disabled'}>下载编码 Excel</button></div>
      <p class="modal-intro" data-financial-download-status>${view.export_available ? '下载仅含编码，不含讲师姓名。' : view.can_export ? '当前结果缺少正式讲师或课程编码，补齐并重新查询后可下载。' : '当前账号没有所选机构的导出权限。'}</p><div data-financial-table></div>`;
    for (const button of root.querySelectorAll('[data-financial-tab]')) button.onclick = () => { if (!ensure() || snapshot !== view) return; tab = button.dataset.financialTab; page = 0; renderTable(); };
    node('export').onclick = download; renderTable();
  }
  async function reload() {
    if (!ensure()) return false;
    cancel(); const ticket = sequence, controller = new AbortController(); request = controller;
    node('result').innerHTML = '<p class="modal-intro" role="status">正在核对课酬与支付统计…</p>'; root.setAttribute('aria-busy', 'true');
    try {
      const selected = filters(); const value = await host.api(BASE + '?' + new URLSearchParams(selected), { method: 'GET', quiet: true, signal: controller.signal });
      if (!ensure() || controller.signal.aborted || ticket !== sequence) return false;
      snapshot = validate(value, selected); organizations = [...value.available_organization_options];
      node('organization').innerHTML = '<option value="">全部可查看机构</option>' + organizations.map(o => `<option value="${esc(o.organization_code)}">${esc(o.display_name)}</option>`).join('');
      node('organization').value = selected.organizations || ''; tab = 'teachers'; page = 0; render(); return true;
    } catch (error) { if (ensure() && !controller.signal.aborted && ticket === sequence && error?.name !== 'AbortError') showError(error); return false; }
    finally { if (active() && ticket === sequence) { request = null; root.setAttribute('aria-busy', 'false'); } }
  }
  async function download() {
    if (!ensure() || !snapshot?.export_available || fileRequest) return false;
    const view = snapshot, ticket = sequence, controller = new AbortController(); fileRequest = controller;
    const valid = () => ensure() && snapshot === view && ticket === sequence && fileRequest === controller && !controller.signal.aborted;
    node('export').disabled = true; node('download-status').textContent = '正在重核本次统计及导出权限…';
    try {
      const query = new URLSearchParams({ start: view.start, end: view.end, date_basis: view.date_basis, rank_metric: view.rank_metric, hour_basis: view.hour_basis,
        organizations: view.selected_organizations.join(','), snapshot_version: view.snapshot_version });
      const response = await host.fetchDownload(BASE + '/export?' + query, { method: 'GET', credentials: 'same-origin', cache: 'no-store', redirect: 'error', headers: { Accept: MIME }, signal: controller.signal });
      if (!valid()) return false;
      if (!response?.ok) { await response?.body?.cancel?.(); throw Object.assign(Error('Download failed'), { status: response?.status }); }
      const filename = `settlement-${view.date_basis.toLowerCase()}-${view.start.replaceAll('-', '')}-${view.end.replaceAll('-', '')}.xlsx`;
      const disposition = response.headers.get('Content-Disposition');
      const length = response.headers.get('Content-Length');
      if (response.status !== 200 || response.redirected || response.headers.get('Content-Type')?.trim().toLowerCase() !== MIME
        || disposition !== `attachment; filename="${filename}"` || length !== null && (!/^[1-9][0-9]*$/.test(length) || !Number.isSafeInteger(Number(length)) || Number(length) > MAX_FILE)) {
        await response.body?.cancel?.(); throw Object.assign(Error(), { localMessage: '下载文件格式、名称或大小无法核实，未保存文件。' });
      }
      const reader = response.body?.getReader(); if (!reader) throw Error('Missing download stream');
      let size = 0; const chunks = [];
      try {
        while (true) {
          const chunk = await reader.read();
          if (!valid()) { await reader.cancel(); return false; }
          if (chunk.done) break;
          size += chunk.value.byteLength;
          if (size > MAX_FILE) { await reader.cancel(); throw Error('Download limit'); }
          chunks.push(chunk.value);
        }
      } finally { reader.releaseLock(); }
      if (!size || length !== null && Number(length) !== size) throw Error('Download size mismatch');
      const blob = new Blob(chunks, { type: MIME }), signature = new Uint8Array(await blob.slice(0, 4).arrayBuffer());
      if (!valid()) return false;
      if (signature.length !== 4 || signature[0] !== 80 || signature[1] !== 75 || signature[2] !== 3 || signature[3] !== 4) throw Error('Invalid XLSX signature');
      const release = host.saveFile(blob, filename); if (typeof release === 'function') releases.add(release);
      if (valid()) node('download-status').textContent = '编码 Excel 已下载，讲师姓名未包含在导出中。'; return true;
    } catch (error) { if (valid() && error?.name !== 'AbortError') showError(error, true); return false; }
    finally { if (active() && fileRequest === controller) { fileRequest = null; if (snapshot === view) node('export').disabled = !view.export_available; } }
  }
  function changed() {
    if (!ensure()) return; cancel(); root.setAttribute('aria-busy', 'false');
    const payment = node('basis').value === 'PAYMENT';
    if (payment) node('rank').value = 'FEE'; node('rank').disabled = payment; node('hour').disabled = payment || node('rank').value !== 'HOURS';
    node('result').innerHTML = '<p class="modal-intro" role="status">筛选条件已变化，请点击“查询”。</p>';
  }
  for (const key of ['start', 'end']) { node(key).oninput = changed; node(key).onchange = changed; }
  for (const key of ['basis', 'organization', 'rank', 'hour']) node(key).onchange = changed;
  node('query').onclick = reload;
  if (host.signal?.aborted) destroy(); else host.signal?.addEventListener('abort', destroy, { once: true });
  icons(); return { ready: active() ? reload() : Promise.resolve(false), refresh: reload, destroy };
}
