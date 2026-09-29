/* M05 standalone module. Demo values are synthetic fixed fixtures, never prices
 * calculated by JavaScript. Formal money must come from the decimal service. */
import { convertMinutesToClassHours } from './conversion.js';

const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (character) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character]));
const freeze = (value) => {
  Object.values(value).forEach((item) => { if (item && typeof item === 'object') freeze(item); });
  return Object.freeze(value);
};

// Read-only transcription of the supplied consultation draft, deliberately separate
// from the synthetic demo rules below. These strings never enter money calculation.
const CANDIDATE_POLICY_RATES = freeze([
  ['讲师', '100', '200', '300', '100'],
  ['高级讲师', '150', '300', '450', '150'],
  ['特级讲师', '200', '400', '600', '200'],
  ['特聘讲师', '200', '400', '600', '200'],
]);
const policyReference = () => `<section class="yx-settlement-card yx-settlement-policy" aria-label="这份办法里的标准">
  <div class="yx-settlement-section-title"><div><span class="yx-settlement-eyebrow">文件原文参考 · 只读</span><h3>这份办法里的标准</h3></div><span class="yx-settlement-tag">征求意见稿</span></div>
  <div class="yx-settlement-policy-body"><p>已读取《金尊公司内部培训师管理办法2.docx》。文件标注为征求意见稿，<strong>正式生效待确认</strong>；以下标准尚未启用为正式结算规则。</p>
  <div class="yx-settlement-table-wrap" tabindex="0" role="region" aria-label="候选费率表，可横向滚动"><table class="yx-settlement-rate-table"><caption>金额单位：元/课时 · 1课时 = 45分钟</caption><thead><tr><th scope="col">讲师级别</th><th scope="col">工作日授课</th><th scope="col">休息日授课</th><th scope="col">法定节假日授课</th><th scope="col">个人独立开发</th></tr></thead><tbody>${CANDIDATE_POLICY_RATES.map(([grade, ...rates]) => `<tr><th scope="row">${esc(grade)}</th>${rates.map((rate) => `<td>${esc(rate)}</td>`).join('')}</tr>`).join('')}</tbody></table></div>
  <ul class="yx-settlement-policy-notes"><li>教学研发团队成员授课、个人独立开发课件，均参照<strong>讲师标准</strong>。</li><li>个人独立开发费不因休息日或法定节假日提高。授课日期属于哪类时段，需要按实际安排核对。</li></ul>
  <p class="yx-settlement-policy-boundary">这里只展示文件里的标准；下面的演示费率和金额是另一组合成样例，不由本表生成。</p></div></section>`;

const RULES = freeze({
  'DEMO-RULE-V1': { version: 'DEMO-RULE-V1', rateVersion: 'DEMO-RATE-V1', effective: '2026-01-01', rate: '300.00', rounding: 'HALF_UP', scale: '2', previewAmount: '750.00' },
  'DEMO-RULE-V2': { version: 'DEMO-RULE-V2', rateVersion: 'DEMO-RATE-V2', effective: '2026-09-01', rate: '320.00', rounding: 'HALF_UP', scale: '2', previewAmount: '800.00' },
});
const RECORDS = freeze([
  { id: 95001, code: 'DEMO-DLV-001', projectCode: 'DEMO-PRJ-001', personCode: 'DEMO-TCH-001', courseCode: 'DEMO-CRS-001', orgCode: 'DEMO-ORG-001', date: '2026-09-14', expected: '4.00', planned: '3.50', actual: '3.00', payable: '2.50', status: 'preview', label: '未确认', note: '四类课时分别来自业务预计、排课计划、授课核对和计酬依据；不相互覆盖。' },
  { id: 95002, code: 'DEMO-DLV-002', projectCode: 'DEMO-PRJ-001', personCode: 'DEMO-TCH-002', courseCode: 'DEMO-CRS-002', orgCode: 'DEMO-ORG-001', date: '2026-08-28', expected: '3.50', planned: '3.00', actual: '3.00', payable: '2.50', status: 'corrected', label: '已更正', note: '当前课时引用已确认更正记录；原确认快照仍可查阅。' },
  { id: 95003, code: 'DEMO-DLV-003', projectCode: 'DEMO-PRJ-002', personCode: 'DEMO-TCH-003', courseCode: 'DEMO-CRS-003', orgCode: 'DEMO-ORG-002', date: '2026-09-16', expected: '2.00', planned: '2.00', actual: '1.50', payable: '1.00', status: 'unconfigured', label: '未配置', note: '该授课形式的费率和舍入规则尚未配置，不能生成金额或确认结果。' },
]);
const ORIGINAL = freeze({ code: 'DEMO-STL-002', deliveryId: 95002, rule: 'DEMO-RULE-V1', rateVersion: 'DEMO-RATE-V1', rate: '300.00', rounding: 'HALF_UP', scale: '2', expected: '3.50', planned: '3.00', actual: '2.50', payable: '2.00', amount: '600.00', confirmed: '2026-08-29 10:00', actorCode: 'DEMO-OP-001' });
const CORRECTION = freeze({ code: 'DEMO-COR-002-01', replaces: 'DEMO-STL-002', rule: 'DEMO-RULE-V1', rateVersion: 'DEMO-RATE-V1', rate: '300.00', rounding: 'HALF_UP', scale: '2', expected: '3.50', planned: '3.00', actual: '3.00', payable: '2.50', amount: '750.00', adjustment: '150.00', confirmed: '2026-08-30 11:00', actorCode: 'DEMO-OP-002', reason: '合成算例：补充已核对授课记录，实际课时与计酬课时分别更正。' });
const HOUR_LABELS = [['expected', '预计课时', '业务预计'], ['planned', '计划课时', '排课计划'], ['actual', '实际课时', '授课核对'], ['payable', '计酬课时', '计酬依据']];
const statusTag = (record) => `<span class="yx-settlement-tag yx-settlement-tag-${record.status}">${esc(record.label)}</span>`;
const hours = (record) => HOUR_LABELS.map(([key, label, source]) => `<div class="yx-settlement-hour"><span>${label}</span><strong>${esc(record[key] ?? '待核对')}<small>${record[key] == null ? '' : ' 课时'}</small></strong><small>${source}</small></div>`).join('');
const fact = (label, value) => `<div><dt>${label}</dt><dd>${esc(value)}</dd></div>`;
const csvCell = (value) => {
  const text = String(value ?? '');
  // Defense in depth for spreadsheet formula injection; demo strings are fixed.
  return `"${(/^[=+\-@\t\r\n]/.test(text) ? "'" : '') + text.replace(/"/g, '""')}"`;
};

function encodedCsv(rule, confirmedPreview) {
  const header = ['data_mode', 'record_kind', 'delivery_id', 'delivery_code', 'project_code', 'org_code', 'teacher_code', 'course_code', 'delivery_date', 'expected_hours', 'planned_hours', 'actual_hours', 'payable_hours', 'settlement_code', 'replaces_settlement_code', 'rule_version', 'rate_version', 'rate_per_hour', 'rounding_mode', 'money_scale', 'currency', 'settlement_amount', 'confirmed_total_amount', 'status'];
  const base = (record) => ['SYNTHETIC_DEMO', record.id, record.code, record.projectCode, record.orgCode, record.personCode, record.courseCode, record.date];
  const first = RECORDS[0];
  const chosen = confirmedPreview?.rule ?? rule;
  const firstAmount = confirmedPreview?.amount ?? chosen.previewAmount;
  const rows = [
    [base(first)[0], confirmedPreview ? 'CONFIRMED_DEMO' : 'PREVIEW_DEMO', ...base(first).slice(1), first.expected, first.planned, first.actual, first.payable, confirmedPreview?.code ?? '', '', chosen.version, chosen.rateVersion, chosen.rate, chosen.rounding, chosen.scale, 'CNY', confirmedPreview ? firstAmount : '', confirmedPreview ? firstAmount : '', confirmedPreview ? 'CONFIRMED_DEMO' : 'UNCONFIRMED_DEMO'],
    ['SYNTHETIC_DEMO', 'ORIGINAL_CONFIRMED', ...base(RECORDS[1]).slice(1), ORIGINAL.expected, ORIGINAL.planned, ORIGINAL.actual, ORIGINAL.payable, ORIGINAL.code, '', ORIGINAL.rule, ORIGINAL.rateVersion, ORIGINAL.rate, ORIGINAL.rounding, ORIGINAL.scale, 'CNY', ORIGINAL.amount, ORIGINAL.amount, 'RETAINED_ORIGINAL_DEMO'],
    ['SYNTHETIC_DEMO', 'CORRECTION_DELTA', ...base(RECORDS[1]).slice(1), CORRECTION.expected, CORRECTION.planned, CORRECTION.actual, CORRECTION.payable, CORRECTION.code, CORRECTION.replaces, CORRECTION.rule, CORRECTION.rateVersion, CORRECTION.rate, CORRECTION.rounding, CORRECTION.scale, 'CNY', CORRECTION.adjustment, CORRECTION.amount, 'CONFIRMED_CORRECTION_DEMO'],
    ['SYNTHETIC_DEMO', 'blocked_unconfigured', ...base(RECORDS[2]).slice(1), RECORDS[2].expected, RECORDS[2].planned, RECORDS[2].actual, RECORDS[2].payable, '', '', '', '', '', '', '', 'CNY', '', '', 'BLOCKED_UNCONFIGURED'],
  ];
  return '\uFEFF' + [header, ...rows].map((row) => row.map(csvCell).join(',')).join('\r\n') + '\r\n';
}

/** Host contract: only this root is mutated; abort/cleanup removes this mount. */
export async function mount(root, context = {}) {
  if (!root?.ownerDocument || typeof root.replaceChildren !== 'function') throw new TypeError('M05 mount requires a DOM root.');
  if (context.signal?.aborted) return () => {};
  const doc = root.ownerDocument;
  const view = doc.defaultView;
  const shell = doc.createElement('section');
  shell.className = 'yx-settlement';
  shell.setAttribute('aria-label', '排课交付与课酬');
  const stylesheet = doc.createElement('link');
  stylesheet.rel = 'stylesheet';
  stylesheet.href = new URL('./styles.css', import.meta.url).href;
  shell.append(stylesheet);
  const content = doc.createElement('div');
  shell.append(content);
  root.replaceChildren(shell);

  let disposed = false;
  let selectedId = 95001;
  let selectedRule = 'DEMO-RULE-V1';
  let filter = 'all';
  let confirmedPreview = null;
  let notice = '';
  let objectUrl = null;
  let minutes = '60';
  let convertedHours = convertMinutesToClassHours(minutes);
  let conversionError = '';

  const cleanup = () => {
    if (disposed) return;
    disposed = true;
    shell.removeEventListener('click', onClick);
    shell.removeEventListener('change', onChange);
    shell.removeEventListener('input', onInput);
    context.signal?.removeEventListener('abort', cleanup);
    if (objectUrl) view.URL.revokeObjectURL(objectUrl);
    objectUrl = null;
    shell.remove();
  };
  const emit = (message) => {
    if (disposed) return;
    notice = message;
    render();
    if (typeof context.notify === 'function') context.notify(message);
  };
  const effectiveRecord = (record) => record.id === 95001 && confirmedPreview ? { ...record, status: 'confirmed', label: '已确认（演示）' } : record;
  const panel = (record, rule) => {
    if (record.status === 'unconfigured') return `<div class="yx-settlement-blocked"><span class="yx-settlement-eyebrow">计费设置尚未确认</span><h3>金额尚未生成</h3><p>费率、舍入规则均未配置。课时已独立记录，课酬保留为空。</p><ul><li>费率版本：未配置</li><li>舍入方式及金额精度：未配置</li><li>正式生效、适用范围：待确认</li></ul><button type="button" disabled>规则未配置，无法确认</button></div>`;
    if (record.id === 95002) return `<div class="yx-settlement-result"><span class="yx-settlement-eyebrow">已确认更正结果 · 固定合成样例</span><div class="yx-settlement-amount">¥ ${CORRECTION.amount}</div><p>替代结果金额；本次差额 +¥ ${CORRECTION.adjustment}。原记录保留，不重复计入。</p><dl class="yx-settlement-facts">${fact('结果编码', CORRECTION.code)}${fact('引用原确认', ORIGINAL.code)}${fact('冻结规则 / 费率', `${CORRECTION.rule} / ${CORRECTION.rateVersion}`)}${fact('冻结单价 / 计酬课时', `${CORRECTION.rate} 元每课时 · ${CORRECTION.payable} 课时`)}${fact('舍入 / 金额小数位', `${CORRECTION.rounding} / ${CORRECTION.scale}`)}${fact('确认时间 / 操作人编码', `${CORRECTION.confirmed} · ${CORRECTION.actorCode}`)}</dl><div class="yx-settlement-info">切换上方预览规则不会改变此确认结果。</div></div>`;
    const frozen = confirmedPreview;
    const shownRule = frozen?.rule ?? rule;
    return `<div class="yx-settlement-result"><span class="yx-settlement-eyebrow">${frozen ? '本次会话已冻结 · 合成演示' : '未确认预览 · 合成固定算例'}</span><div class="yx-settlement-amount">¥ ${frozen?.amount ?? shownRule.previewAmount}</div><p>${frozen ? '确认时的金额、规则与课时已保留。刷新页面将重置演示。' : '示例展示：2.50 课时 × ' + shownRule.rate + ' 元每课时。金额来自预置样例。'}</p><dl class="yx-settlement-facts">${fact('结果编码', frozen?.code ?? '未确认，不生成正式结果编码')}${fact('规则版本', shownRule.version)}${fact('费率版本', shownRule.rateVersion)}${fact('示例单价', `${shownRule.rate} 元每课时`)}${fact('舍入 / 金额小数位', `${shownRule.rounding} / ${shownRule.scale}`)}</dl><button type="button" data-action="confirm" ${frozen ? 'disabled' : ''}>${frozen ? '演示结果已冻结' : '演示确认并冻结此结果'}</button><p class="yx-settlement-muted">仅改变当前页面内存，不提交、不持久化。正式金额由服务端十进制计算输出。</p></div>`;
  };
  const history = () => `<section class="yx-settlement-card yx-settlement-history"><div class="yx-settlement-section-title"><div><span class="yx-settlement-eyebrow">保留每一次依据</span><h3>冻结结果与更正记录</h3></div><span class="yx-settlement-tag">合成示例</span></div><div class="yx-settlement-timeline"><article><span class="yx-settlement-step">1</span><div><h4>原确认记录 · 保留</h4><p>${ORIGINAL.code} · ${ORIGINAL.confirmed} · ${ORIGINAL.actorCode}</p><p>实际 ${ORIGINAL.actual} 课时 / 计酬 ${ORIGINAL.payable} 课时 / 结果 ¥ ${ORIGINAL.amount}</p><small>${ORIGINAL.rule} · ${ORIGINAL.rateVersion} · ${ORIGINAL.rounding} / ${ORIGINAL.scale} 位</small></div></article><article><span class="yx-settlement-step">2</span><div><h4>更正结果 · 引用原确认</h4><p>${CORRECTION.code} → ${CORRECTION.replaces}</p><p>实际 ${CORRECTION.actual} 课时 / 计酬 ${CORRECTION.payable} 课时 / 替代结果 ¥ ${CORRECTION.amount} / 差额 +¥ ${CORRECTION.adjustment}</p><small>${CORRECTION.reason}</small><small>${CORRECTION.confirmed} · ${CORRECTION.actorCode} · 沿用冻结规则 ${CORRECTION.rule}</small></div></article></div><p class="yx-settlement-muted">该示例展示更正链的保留方式；正式取消、更正权限及差额处理规则待制度确认。</p></section>`;

  const conversionTool = () => `<section class="yx-settlement-card yx-settlement-conversion" aria-label="授课分钟转课时"><div class="yx-settlement-section-title"><div><span class="yx-settlement-eyebrow">实际课时换算参考</span><h3>授课分钟转课时</h3></div></div><div class="yx-settlement-conversion-body"><div class="yx-settlement-conversion-controls"><label>授课时长（分钟）<input type="text" inputmode="decimal" autocomplete="off" data-control="minutes" value="${esc(minutes)}" aria-invalid="${Boolean(conversionError)}" placeholder="例如60或67.5"></label><button type="button" data-action="convert">换算课时</button></div><p class="yx-settlement-conversion-output ${conversionError ? 'is-error' : ''}" data-conversion-result role="status" aria-live="polite">${conversionError ? esc(conversionError) : convertedHours ? `换算结果：<strong>${esc(convertedHours)} 课时</strong>` : '输入分钟后，点击换算课时。'}</p><p class="yx-settlement-muted">分钟 ÷ 45，课时保留两位小数，四舍五入。结果仅作实际课时参考，计酬课时需单独核对；不会改动下面的授课记录或已确认金额。</p></div></section>`;

  function render() {
    if (disposed || context.signal?.aborted) return;
    if (context.mode !== 'demo') {
      content.innerHTML = `<header class="yx-settlement-header"><div><span class="yx-settlement-eyebrow">M05 · 排课交付与课酬</span><h2>课时与课酬核对</h2></div><span class="yx-settlement-tag yx-settlement-tag-unconfigured">${context.mode === 'live' ? '正式环境' : '模式未配置'}</span></header><div class="yx-settlement-card yx-settlement-empty"><span class="yx-settlement-empty-mark" aria-hidden="true">—</span><h3>正式接口未接入</h3><p>当前页面尚未连接授课事实、费率规则、确认结果与编码导出接口。</p><p>接入并核验权限及规则版本后，可在这里查看正式数据。</p><dl class="yx-settlement-facts">${fact('当前数据', '未读取')}${fact('确认与导出', '暂不可用')}</dl></div>`;
      return;
    }
    const rule = RULES[selectedRule];
    const record = effectiveRecord(RECORDS.find((item) => item.id === selectedId));
    const visible = RECORDS.map(effectiveRecord).filter((item) => filter === 'all' || (filter === 'confirmed' ? ['corrected', 'confirmed'].includes(item.status) : item.status === filter));
    content.innerHTML = `<header class="yx-settlement-header"><div><span class="yx-settlement-eyebrow">M05 · 内部逻辑演示</span><h2>课时与课酬核对</h2><p>用于内部验证；正式功能接回原系统“课程与排期”和“课酬发放”。</p></div><button type="button" data-action="export" class="yx-settlement-secondary">导出编码 CSV（演示）</button></header>
      ${policyReference()}
      ${conversionTool()}
      <div class="yx-settlement-demo" role="note"><strong>合成数据演示</strong><span>以下授课记录中的编码、日期、费率和金额均为虚构样例，与上方办法标准分开展示。仅在当前页面演示，不可作为正式计费或权限验收依据。</span></div>
      <p class="yx-settlement-unit-note">课时单位：45分钟/课时（用户已确认）；分钟换算保留两位小数、四舍五入。金额舍入及正式生效仍待确认。</p>
      <div class="yx-settlement-toolbar"><div class="yx-settlement-rule-control"><label>预览规则 <select data-control="rule">${Object.values(RULES).map((item) => `<option value="${item.version}" ${selectedRule === item.version ? 'selected' : ''}>${item.version} · ${item.rate} 元每课时</option>`).join('')}</select></label><small>合成生效日 ${rule.effective} · 仅未确认预览随版本切换</small></div><button type="button" class="yx-settlement-text-button" data-action="reset">重置演示</button></div>
      <p class="yx-settlement-notice" role="status" aria-live="polite">${esc(notice || '选择一条授课记录，核对四类课时与对应规则。')}</p>
      <div class="yx-settlement-workspace"><section class="yx-settlement-card yx-settlement-records" aria-label="授课记录"><div class="yx-settlement-section-title"><h3>授课记录</h3><span class="yx-settlement-muted">3 条合成记录</span></div><div class="yx-settlement-filters" aria-label="记录状态筛选">${[['all', '全部'], ['preview', '未确认'], ['confirmed', '已确认 / 更正'], ['unconfigured', '未配置']].map(([value, label]) => `<button type="button" data-filter="${value}" aria-pressed="${filter === value}">${label}</button>`).join('')}</div><div class="yx-settlement-record-list">${visible.length ? visible.map((item) => `<button type="button" class="yx-settlement-record ${item.id === selectedId ? 'is-selected' : ''}" data-record="${item.id}" aria-pressed="${item.id === selectedId}"><span class="yx-settlement-record-top"><strong>${item.code}</strong>${statusTag(item)}</span><span>${item.courseCode} · ${item.personCode}</span><span class="yx-settlement-muted">${item.date} · ${item.projectCode}</span><span class="yx-settlement-record-hours">预计 ${item.expected} / 计划 ${item.planned}<br>实际 ${item.actual} / 计酬 ${item.payable} 课时</span></button>`).join('') : '<p class="yx-settlement-list-empty">此筛选下暂无记录。</p>'}</div></section>
      <section class="yx-settlement-card yx-settlement-detail" aria-label="所选记录详情"><div class="yx-settlement-section-title"><div><span class="yx-settlement-eyebrow">授课事实 · ${record.date}</span><h3>${record.code}</h3></div>${statusTag(record)}</div><dl class="yx-settlement-meta">${fact('业务记录 ID', record.id)}${fact('项目编码', record.projectCode)}${fact('课程编码', record.courseCode)}${fact('讲师编码', record.personCode)}</dl><div class="yx-settlement-hours">${hours(record)}</div><p class="yx-settlement-fact-note">${record.note}</p>${panel(record, rule)}</section></div>
      ${record.id === 95002 ? history() : ''}
      <footer class="yx-settlement-footer"><strong>编码导出范围</strong><p>CSV 包含四类课时、规则 / 费率版本、状态与结果引用。缺规则金额留空；原确认与更正差额分行，单列确认总额。所有行带 SYNTHETIC_DEMO 标签，不含实名、联系方式或解码映射。</p><p>核对已确认行时，settlement_amount 为原始金额或更正差额；confirmed_total_amount 为该次确认总额，不跨版本累加。未确认预览及未配置行的结算金额为空，不作为已确认应付。</p><p>现有办法已经读取。具体计费遇到不明确的地方，会用实际例子逐项确认；没有人力部门的表格时，先用系统编码清单继续核对。</p><small>正式生效、金额舍入及取消 / 更正规则仍待确认。取消规则未配置时，不自动将金额置零。</small></footer>`;
  }

  function onClick(event) {
    if (disposed || context.mode !== 'demo' || context.signal?.aborted) return;
    const button = event.target.closest('button');
    if (!button || !shell.contains(button) || button.disabled) return;
    if (button.dataset.action === 'convert') {
      try { convertedHours = convertMinutesToClassHours(minutes); conversionError = ''; }
      catch (error) { convertedHours = ''; conversionError = error.message; }
      render();
    } else if (button.dataset.record) {
      const next = RECORDS.find((item) => String(item.id) === button.dataset.record);
      if (next) { selectedId = next.id; notice = ''; render(); }
    } else if (button.dataset.filter) {
      filter = button.dataset.filter;
      const matching = RECORDS.map(effectiveRecord).filter((item) => filter === 'all' || (filter === 'confirmed' ? ['corrected', 'confirmed'].includes(item.status) : item.status === filter));
      if (matching.length && !matching.some((item) => item.id === selectedId)) selectedId = matching[0].id;
      notice = '';
      render();
    } else if (button.dataset.action === 'confirm' && selectedId === 95001 && !confirmedPreview) {
      const rule = RULES[selectedRule];
      confirmedPreview = freeze({ code: 'DEMO-STL-001-SESSION', rule, amount: rule.previewAmount, hours: { expected: RECORDS[0].expected, planned: RECORDS[0].planned, actual: RECORDS[0].actual, payable: RECORDS[0].payable } });
      emit('已在当前页面冻结合成示例。切换预览版本后，该结果仍保留确认时的规则与金额。');
    } else if (button.dataset.action === 'reset') {
      selectedId = 95001; selectedRule = 'DEMO-RULE-V1'; filter = 'all'; confirmedPreview = null;
      minutes = '60'; convertedHours = convertMinutesToClassHours(minutes); conversionError = '';
      emit('演示已重置为初始合成样例。');
    } else if (button.dataset.action === 'export') {
      if (!view?.URL?.createObjectURL || !view?.Blob) { emit('当前浏览环境不支持文件下载。'); return; }
      if (objectUrl) view.URL.revokeObjectURL(objectUrl);
      objectUrl = view.URL.createObjectURL(new view.Blob([encodedCsv(RULES[selectedRule], confirmedPreview)], { type: 'text/csv;charset=utf-8' }));
      const link = doc.createElement('a');
      link.href = objectUrl;
      link.download = 'M05_SYNTHETIC_DEMO_encoded_settlement.csv';
      link.hidden = true;
      shell.append(link);
      link.click();
      link.remove();
      emit('已导出全部合成记录。CSV 中缺规则金额为空，原确认与更正分别标记，避免重复汇总。');
    }
  }

  function onInput(event) {
    if (disposed || context.mode !== 'demo' || context.signal?.aborted || event.target.dataset.control !== 'minutes') return;
    minutes = event.target.value;
    convertedHours = ''; conversionError = '';
    event.target.setAttribute('aria-invalid', 'false');
    const output = content.querySelector('[data-conversion-result]');
    if (output) { output.classList.remove('is-error'); output.textContent = '输入分钟后，点击换算课时。'; }
  }

  function onChange(event) {
    if (disposed || context.mode !== 'demo' || context.signal?.aborted) return;
    if (event.target.dataset.control === 'rule' && Object.hasOwn(RULES, event.target.value)) {
      selectedRule = event.target.value;
      emit('预览规则已切换。已确认快照及更正记录保持原规则与原金额。');
    }
  }

  shell.addEventListener('click', onClick);
  shell.addEventListener('change', onChange);
  shell.addEventListener('input', onInput);
  context.signal?.addEventListener('abort', cleanup, { once: true });
  render();
  return cleanup;
}
