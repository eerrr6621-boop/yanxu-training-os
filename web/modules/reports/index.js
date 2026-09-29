const mounts = new WeakMap();
const LABELS = {
  TEACHING: '授课日期', CONFIRMATION: '确认日期', PAYMENT: '支付日期',
  ESTIMATED: '预计课时', PLANNED: '计划课时', ACTUAL: '实际课时', PAYABLE: '计酬课时',
  HOURS: '课时', FEE: '历史课酬', COMPETITION: '竞争排名 · 1, 2, 2, 4', DENSE: '密集排名 · 1, 2, 2, 3', ORDINAL: '顺序排名 · 编码升序破同分',
  CONFIRMED: '已确认', CANCELLED: '已取消', SUPERSEDED: '已被替代', ADJUSTMENT: '调整记录',
};
const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
const options = (values, chosen) => values.map(value => `<option value="${value}"${value === chosen ? ' selected' : ''}>${LABELS[value] || value}</option>`).join('');
const select = (name, label, values, chosen) => `<label class="yx-reports-field"><span>${label}</span><select name="${name}">${options(values, chosen)}</select></label>`;
const check = (name, value, label, checked) => `<label class="yx-reports-check"><input type="checkbox" name="${name}" value="${esc(value)}"${checked ? ' checked' : ''}><span>${esc(label)}</span></label>`;
const date = value => esc(value);

function monthRange(value) {
  if (typeof value !== 'string' || !/^[0-9]{4}-(0[1-9]|1[0-2])$/.test(value)) return null;
  const year = Number(value.slice(0, 4)), month = Number(value.slice(5, 7));
  if (year < 1 || year > 9999) return null;
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1];
  return { start: value + '-01', end: value + '-' + String(days) };
}
function monthForRange(start, end) {
  if (typeof start !== 'string' || typeof end !== 'string') return '';
  const month = start.slice(0, 7), range = monthRange(month);
  return range && start === range.start && end === range.end ? month : '';
}
function adjacentMonth(value, shift) {
  if (!monthRange(value) || (shift !== -1 && shift !== 1)) return '';
  const total = Number(value.slice(0, 4)) * 12 + Number(value.slice(5, 7)) - 1 + shift;
  const month = String(Math.floor(total / 12)).padStart(4, '0') + '-' + String(total % 12 + 1).padStart(2, '0');
  return monthRange(month) ? month : '';
}
function periodTitle(start, end) {
  const month = monthForRange(start, end);
  return month ? '月报 ' + month.slice(0, 4) + '年' + month.slice(5, 7) + '月' : '自定义日期范围 · ' + start + ' — ' + end;
}

/** Host integration: await mount(root, { mode, signal, request, user, notify, navigate }). */
export async function mount(root, context = {}) {
  if (!root?.ownerDocument || typeof root.replaceChildren !== 'function') throw new TypeError('mount root 必须是 DOM 容器');
  if (context.signal?.aborted) return () => {};
  mounts.get(root)?.();
  const doc = root.ownerDocument;
  const frame = doc.createElement('section');
  frame.className = 'yx-reports';
  frame.setAttribute('aria-label', '统计排名与编码导出');
  let disposed = false, result, model, tab = 'ranking', monthValidationError = '', workbookPending = false, exportTicket = 0;
  const urls = new Set(), timers = new Set();
  const onAbort = () => cleanup();
  function cleanup() {
    if (disposed) return;
    disposed = true;
    exportTicket += 1; workbookPending = false;
    context.signal?.removeEventListener('abort', onAbort);
    frame.removeEventListener('change', onChange);
    frame.removeEventListener('click', onClick);
    frame.removeEventListener('submit', onSubmit);
    timers.forEach(timer => clearTimeout(timer));
    urls.forEach(url => URL.revokeObjectURL(url));
    timers.clear(); urls.clear();
    if (mounts.get(root) === cleanup) { mounts.delete(root); frame.remove(); }
  }
  mounts.set(root, cleanup);
  context.signal?.addEventListener('abort', onAbort, { once: true });
  frame.innerHTML = `<link rel="stylesheet" href="${esc(new URL('./styles.css', import.meta.url).href)}"><div class="yx-reports-shell"><header class="yx-reports-header"><div><div class="yx-reports-eyebrow">研序 / M06 · 统计分析</div><h1>统计排名与导出</h1><p>让每一个汇总，都能回到原始事实。</p></div><span class="yx-reports-mode">${context.mode === 'demo' ? '合成数据演示' : '正式数据'}</span></header><div data-content></div></div>`;
  root.replaceChildren(frame);
  const content = frame.querySelector('[data-content]');
  if (context.mode !== 'demo') {
    content.innerHTML = `<div class="yx-reports-live"><span class="yx-reports-empty-mark" aria-hidden="true">—</span><h2>统计口径已确定，正式数据尚未接入</h2><p>月报格式已就绪。接入正式业务明细后，即可按已确定的口径生成月度概览、排名、机构汇总与明细对账。</p><div class="yx-reports-live-tags"><span>支持授课日期与支付日期</span><span>四类课时分别统计</span><span>更正按增减调整计入</span></div></div>`;
    return cleanup;
  }
  content.innerHTML = '<p class="yx-reports-loading" role="status">正在读取合成演示数据…</p>';
  try { model = await import('./demo-model.js'); } catch {
    if (!disposed) content.innerHTML = '<div class="yx-reports-error" role="alert">演示模块未能加载，请通过本地 HTTP 服务打开 demo.html。</div>';
    return cleanup;
  }
  if (disposed) return cleanup;
  const { DEFAULT_RULE: rule, DEFAULT_FILTER: filter, OPTIONS, DEMO_ORGS } = model;
  content.innerHTML = `<div class="yx-reports-demo-note"><span class="yx-reports-note-dot" aria-hidden="true"></span><div><strong>仅为合成演示</strong><span>不含真实讲师、机构或课程。历史课酬直接取事实记录，不在统计中重新计价。</span></div><code>${esc(rule.version)}</code></div>
    <form class="yx-reports-filters" novalidate>
      <div class="yx-reports-month-picker"><label class="yx-reports-field"><span>统计月份</span><input type="month" name="month" value="${monthForRange(filter.start, filter.end)}" min="0001-01" max="9999-12"></label><div class="yx-reports-month-controls"><button type="button" data-month-shift="-1">‹ 上月</button><button type="button" data-month-shift="1">下月 ›</button></div><div class="yx-reports-month-description"><strong data-month-status role="status"></strong><p>选择月份自动统计整月，也可在下方调整起止日期。</p></div></div>
      <div class="yx-reports-section-heading"><div><h2>统计口径</h2><p>以下为当前演示配置；调整条件后，明细、汇总与排名同步更新。</p></div><button type="button" class="yx-reports-text-button" data-reset>恢复演示口径</button></div>
      <div class="yx-reports-filter-grid">
        <label class="yx-reports-field"><span>开始日期</span><input type="date" name="start" value="${filter.start}" required></label>
        <label class="yx-reports-field"><span>结束日期</span><input type="date" name="end" value="${filter.end}" required></label>
        ${select('dateBasis', '日期基准', ['TEACHING', 'PAYMENT'], rule.dateBasis)}
        ${select('hourBasis', '课时类别', OPTIONS.hourBasis, rule.hourBasis)}
        ${select('rankMetric', '排名指标', OPTIONS.rankMetric, rule.rankMetric)}
        ${select('tieRule', '并列规则', OPTIONS.tieRule, rule.tieRule)}
      </div>
      <div class="yx-reports-choice-grid"><fieldset><legend>组织筛选 <small>不勾选表示全部组织</small></legend><div class="yx-reports-choices">${DEMO_ORGS.map(code => check('orgCodes', code, code, false)).join('')}</div></fieldset>
      <fieldset><legend>包含状态 <small>至少选择一项</small></legend><div class="yx-reports-choices">${OPTIONS.includedStatuses.map(status => check('includedStatuses', status, LABELS[status], rule.includedStatuses.includes(status))).join('')}</div></fieldset></div>
      <p class="yx-reports-filter-help">日期范围包含首尾两天。默认排除取消及被替代历史，调整记录按正负值净额计入。切换为支付日期，可查看未付款记录的缺失校验。</p>
      <button type="submit" class="yx-reports-sr-only">更新统计</button>
    </form>
    <div data-results aria-live="polite" aria-atomic="false"></div><p class="yx-reports-export-status" data-export-status role="status"></p>`;
  frame.addEventListener('change', onChange);
  frame.addEventListener('click', onClick);
  frame.addEventListener('submit', onSubmit);
  refresh();
  return cleanup;

  function getInputs() {
    const form = frame.querySelector('form');
    const value = name => form.elements.namedItem(name).value;
    const checked = name => [...form.querySelectorAll(`input[name="${name}"]:checked`)].map(input => input.value);
    return {
      rule: { version: model.RULE_VERSION, dateBasis: value('dateBasis'), hourBasis: value('hourBasis'), rankMetric: value('rankMetric'), tieRule: value('tieRule'), includedStatuses: checked('includedStatuses') },
      filter: { start: value('start'), end: value('end'), orgCodes: checked('orgCodes') },
    };
  }
  function syncMonthFromDates() {
    const form = frame.querySelector('form');
    const month = monthForRange(form.elements.start.value, form.elements.end.value);
    form.elements.month.value = month;
    frame.querySelector('[data-month-status]').textContent = month ? month.slice(0, 4) + '年' + month.slice(5, 7) + '月 · 自然月' : '自定义日期范围';
    const base = month || (monthRange(form.elements.start.value.slice(0, 7)) ? form.elements.start.value.slice(0, 7) : model.DEFAULT_FILTER.start.slice(0, 7));
    frame.querySelectorAll('[data-month-shift]').forEach(button => { button.disabled = !adjacentMonth(base, Number(button.dataset.monthShift)); });
  }
  function chooseMonth(value) {
    const range = monthRange(value);
    if (!range) { monthValidationError = '请选择有效统计月份，范围为 0001-01 至 9999-12，或直接调整起止日期。'; return; }
    const form = frame.querySelector('form');
    monthValidationError = '';
    form.elements.start.value = range.start; form.elements.end.value = range.end;
  }
  function onChange(event) {
    if (disposed || !event.target.closest('form')) return;
    if (event.target.name === 'month') chooseMonth(event.target.value);
    else if (event.target.name === 'start' || event.target.name === 'end') monthValidationError = '';
    refresh();
  }
  function onSubmit(event) { event.preventDefault(); if (!disposed) refresh(); }
  function onClick(event) {
    if (disposed) return;
    const button = event.target.closest('button');
    if (!button || !frame.contains(button)) return;
    if (button.hasAttribute('data-reset')) { frame.querySelector('form').reset(); tab = 'ranking'; monthValidationError = ''; refresh(); }
    if (button.dataset.monthShift) {
      const form = frame.querySelector('form');
      const base = monthRange(form.elements.month.value) ? form.elements.month.value : monthRange(form.elements.start.value.slice(0, 7)) ? form.elements.start.value.slice(0, 7) : model.DEFAULT_FILTER.start.slice(0, 7);
      const next = adjacentMonth(base, Number(button.dataset.monthShift));
      if (next) { chooseMonth(next); refresh(); }
    }
    if (button.dataset.tab) {
      tab = button.dataset.tab;
      frame.querySelectorAll('[data-tab]').forEach(item => { item.setAttribute('aria-pressed', String(item.dataset.tab === tab)); });
      frame.querySelectorAll('[data-panel]').forEach(item => { item.hidden = item.dataset.panel !== tab; });
    }
    if (button.dataset.export && result && !result.errors.length) {
      if (button.dataset.export === 'XLSX') void downloadMonthly();
      else download(button.dataset.export);
    }
  }
  function saveBlob(blob, filename) {
    if (disposed) return false;
    const url = URL.createObjectURL(blob); urls.add(url);
    const anchor = doc.createElement('a');
    anchor.href = url; anchor.download = filename; anchor.hidden = true;
    frame.append(anchor); anchor.click(); anchor.remove();
    if (disposed) return false;
    const timer = setTimeout(() => { URL.revokeObjectURL(url); urls.delete(url); timers.delete(timer); }, 1000);
    timers.add(timer);
    return true;
  }
  async function downloadMonthly() {
    if (disposed || workbookPending || !result || result.errors.length) return;
    const report = result, ticket = ++exportTicket;
    workbookPending = true;
    const button = frame.querySelector('[data-export="XLSX"]');
    button.disabled = true; button.textContent = '正在生成报表…';
    frame.querySelector('[data-export-status]').textContent = '正在生成月度报表…';
    try {
      const { exportMonthlyWorkbook } = await import('./monthly-workbook.js');
      if (disposed || ticket !== exportTicket || result !== report) return;
      const bytes = await exportMonthlyWorkbook(report, model);
      if (disposed || ticket !== exportTicket || result !== report) return;
      if (!(bytes instanceof Uint8Array)) throw new TypeError('月度报表内容无效');
      const month = monthForRange(report.filter.start, report.filter.end);
      const filename = 'M06-合成月报-' + (month || report.filter.start + '-至-' + report.filter.end) + '.xlsx';
      if (saveBlob(new Blob([bytes], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }), filename)) {
        frame.querySelector('[data-export-status]').textContent = '已生成月度报表，包含月度概览、讲师排名、机构汇总和明细对账。';
      }
    } catch {
      if (!disposed && ticket === exportTicket && result === report) frame.querySelector('[data-export-status]').textContent = '月度报表未能生成，请重试。';
    } finally {
      if (!disposed && ticket === exportTicket) {
        workbookPending = false;
        const current = frame.querySelector('[data-export="XLSX"]');
        if (current) { current.disabled = false; current.innerHTML = '导出月度报表 <span aria-hidden="true">↓</span>'; }
      }
    }
  }
  function download(type) {
    if (disposed) return;
    try {
      const isManifest = type === 'MANIFEST';
      const blob = new Blob([isManifest ? model.exportManifest(result) : model.exportCsv(result, type)], { type: isManifest ? 'application/json;charset=utf-8;' : 'text/csv;charset=utf-8;' });
      if (!saveBlob(blob, `M06-${type}-${model.RULE_VERSION}-${result.filter.start}-${result.filter.end}.${isManifest ? 'json' : 'csv'}`)) return;
      frame.querySelector('[data-export-status]').textContent = isManifest ? '已生成 JSON 清单，包含规则、日期与组织范围、总量及各导出行数，空结果也可追溯。' : `已生成${({ DETAIL: '同源明细', RANKING: '讲师排名', ORG_SUMMARY: '机构汇总' })[type]} CSV。请同时保存 JSON 清单；在 Excel 中按文本导入编码和精确数值列。`;
    } catch { if (!disposed) frame.querySelector('[data-export-status]').textContent = '导出未完成，请重新生成统计结果后重试。'; }
  }
  function refresh() {
    if (disposed) return;
    exportTicket += 1; workbookPending = false;
    if (!monthValidationError) syncMonthFromDates();
    else frame.querySelector('[data-month-status]').textContent = '月份无效，请重新选择';
    const { rule, filter } = getInputs();
    result = monthValidationError ? { errors: [monthValidationError] } : model.compute(model.DEMO_FACTS, rule, filter);
    const target = frame.querySelector('[data-results]');
    frame.querySelector('[data-export-status]').textContent = '';
    if (result.errors.length) {
      target.innerHTML = `<section class="yx-reports-error" role="alert"><h2>当前口径无法生成完整统计</h2><p>以下问题需要先处理，汇总与导出已暂停，未将有问题的记录静默排除。</p><ul>${result.errors.map(error => `<li>${esc(error.replace(/\b(TEACHING|CONFIRMATION|PAYMENT|ESTIMATED|PLANNED|ACTUAL|PAYABLE)\b/g, value => LABELS[value]))}</li>`).join('')}</ul></section>`;
      return;
    }
    const { totals, rankings, organizations, details } = result;
    const reconciled = result.reconcile.every(row => row.recordDifference === 0 && row.courseDifference === 0 && row.hourDifference === 0n && row.feeDifference === 0n);
    const f = value => model.decimal(value);
    const money = value => { const [whole, fraction] = f(value).split('.'); return whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + fraction; };
    const max = rankings.length ? rankings[0][rule.rankMetric === 'HOURS' ? 'hours' : 'fee'] : 0n;
    const percent = row => { const value = row[rule.rankMetric === 'HOURS' ? 'hours' : 'fee']; return max > 0n && value > 0n ? String(value * 100n / max) : '0'; };
    const stat = (label, value, unit, note) => `<div class="yx-reports-stat"><span>${label}</span><div><strong>${value}</strong><small>${unit}</small></div><p>${note}</p></div>`;
    target.innerHTML = `<section class="yx-reports-monthly-overview"><div class="yx-reports-overview-heading"><div><h2>月度概览</h2><p>${esc(periodTitle(filter.start, filter.end))}</p></div><button type="button" class="yx-reports-monthly-export" data-export="XLSX">导出月度报表 <span aria-hidden="true">↓</span></button></div><div class="yx-reports-result-line"><span><strong>${date(filter.start)} — ${date(filter.end)}</strong> <span class="yx-reports-result-basis">按${LABELS[rule.dateBasis]} · ${LABELS[rule.hourBasis]}</span></span><code>${esc(rule.version)}</code></div>
      <div class="yx-reports-stats">${stat('总记录数', totals.records, '条', '包含入选的调整事实')}${stat('唯一课程数', totals.courses.size, '门', '相同课程只统计一次')}${stat(LABELS[rule.hourBasis], f(totals.hours), '课时', '包含增减调整')}${stat('历史课酬', money(totals.fee), 'CNY', '取历史金额，不重算费率')}</div>
      <div class="yx-reports-hours-overview"><span>四类课时 <small>分别统计，不相加</small></span>${model.OPTIONS.hourBasis.map(basis => `<div><span>${LABELS[basis]}</span><strong>${result.hourTotals[basis] === null ? '数据待补齐' : f(result.hourTotals[basis])}</strong></div>`).join('')}</div>
      </section><section class="yx-reports-data-card">
        <div class="yx-reports-data-toolbar"><div class="yx-reports-view-buttons" role="group" aria-label="统计视图"><button type="button" data-tab="ranking" aria-pressed="${tab === 'ranking'}">讲师排名 <small>${rankings.length}</small></button><button type="button" data-tab="organizations" aria-pressed="${tab === 'organizations'}">机构课程汇总 <small>${organizations.length}</small></button><button type="button" data-tab="details" aria-pressed="${tab === 'details'}">同源明细 <small>${details.length}</small></button></div><button type="button" class="yx-reports-manifest" data-export="MANIFEST">导出清单 JSON</button></div>
        <div data-panel="ranking"${tab !== 'ranking' ? ' hidden' : ''}><div class="yx-reports-panel-heading"><p>按${LABELS[rule.rankMetric]}降序 · ${LABELS[rule.tieRule]}。同指标时以讲师编码升序展示。</p><button type="button" class="yx-reports-export" data-export="RANKING">导出排名 CSV <span aria-hidden="true">↓</span></button></div>${table(['排名', '讲师编码', '记录数', '唯一课程数', '课时', '历史课酬 / CNY'], rankings.map(row => `<tr><td><span class="yx-reports-rank${row.rank === 1 ? ' yx-reports-rank-first' : ''}">${row.rank}</span></td><td><code>${esc(row.code)}</code><span class="yx-reports-bar" aria-hidden="true"><i style="width:${percent(row)}%"></i></span></td><td>${row.records}</td><td>${row.courses.size}</td><td class="yx-reports-number">${f(row.hours)}</td><td class="yx-reports-number">${money(row.fee)}</td></tr>`), '讲师排名')}</div>
        <div data-panel="organizations"${tab !== 'organizations' ? ' hidden' : ''}><div class="yx-reports-panel-heading"><p>相同课程在每个组织内只统计一次；全局课程总数也会去除重复。</p><button type="button" class="yx-reports-export" data-export="ORG_SUMMARY">导出机构汇总 CSV <span aria-hidden="true">↓</span></button></div>${table(['组织编码', '记录数', '唯一课程数', '课时', '历史课酬 / CNY'], organizations.map(row => `<tr><td><code>${esc(row.code)}</code></td><td>${row.records}</td><td>${row.courses.size}</td><td class="yx-reports-number">${f(row.hours)}</td><td class="yx-reports-number">${money(row.fee)}</td></tr>`), '机构课程汇总')}</div>
        <div data-panel="details"${tab !== 'details' ? ' hidden' : ''}><div class="yx-reports-panel-heading"><p>展示全部 ${details.length} 条入选事实。课程与记录均以编码展示。</p><button type="button" class="yx-reports-export" data-export="DETAIL">导出明细 CSV <span aria-hidden="true">↓</span></button></div>${table(['记录编码', '课程编码', '讲师编码', '组织编码', LABELS[rule.dateBasis], '状态', '课时', '历史课酬 / CNY', '费用版本'], details.map(({ source: row, date: basisDate, hours, fee }) => `<tr><td><code>${esc(row.recordCode)}</code></td><td><code>${esc(row.courseCode)}</code></td><td><code>${esc(row.teacherCode)}</code></td><td><code>${esc(row.orgCode)}</code></td><td class="yx-reports-nowrap">${date(basisDate)}</td><td><span class="yx-reports-status${row.status === 'ADJUSTMENT' ? ' yx-reports-status-adjustment' : ''}">${LABELS[row.status]}</span></td><td class="yx-reports-number">${f(hours)}</td><td class="yx-reports-number">${money(fee)}</td><td><code>${esc(row.feeVersion)}</code></td></tr>`), '入选事实明细')}</div>
      </section>
      <section class="yx-reports-reconcile"><div class="yx-reports-section-heading"><div><h2>同源对账</h2><p>核对排名、机构汇总与当前筛选明细，确保课时和金额一致。</p></div><span class="yx-reports-reconciled">${reconciled ? '✓ 差额全部为零' : '存在差额，请核对'}</span></div>${table(['汇总来源', '记录数 / 差额', '唯一课程数 / 差额', '课时 / 差额', '历史课酬 CNY / 差额'], result.reconcile.map(row => `<tr><td>${({ DETAIL: '筛选明细', RANKING: '讲师排名汇总', ORG_SUMMARY: '机构汇总' })[row.type]}</td><td>${row.records} <span class="yx-reports-difference">/ ${row.recordDifference}</span></td><td>${row.courses.size} <span class="yx-reports-difference">/ ${row.courseDifference}</span></td><td class="yx-reports-number">${f(row.hours)} <span class="yx-reports-difference">/ ${f(row.hourDifference)}</span></td><td class="yx-reports-number">${money(row.fee)} <span class="yx-reports-difference">/ ${f(row.feeDifference)}</span></td></tr>`), '同源对账')}</section>
      <footer class="yx-reports-footer"><span>合成规则 ${esc(rule.version)} · 费用版本 DEMO-FEE-1</span><span>CSV 仅含编码字段；空结果仅表头，请同时保存 JSON 清单。Excel 请按文本导入，保留前导零及精确长数值。</span></footer>`;
  }
  function table(headers, rows, label) {
    return `<div class="yx-reports-table-scroll" tabindex="0" role="region" aria-label="${esc(label)}"><table><caption class="yx-reports-sr-only">${esc(label)}</caption><thead><tr>${headers.map(header => `<th scope="col">${esc(header)}</th>`).join('')}</tr></thead><tbody>${rows.length ? rows.join('') : `<tr><td colspan="${headers.length}" class="yx-reports-empty">当前条件下没有记录。可调整日期、组织或包含状态。</td></tr>`}</tbody></table></div>`;
  }
}
