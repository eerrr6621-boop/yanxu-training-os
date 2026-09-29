const DECIMAL = /^[0-9]+(?:\.[0-9]+)?$/;
const INTEGER = /^(0|[1-9][0-9]*)$/;
const DECIMAL_KEYS = ['actual_hours', 'actual_minutes', 'payable_hours', 'raw_amount', 'final_amount',
  'pool_raw_amount', 'pool_final_amount', 'individual_raw_amount', 'individual_final_amount'];
const GRADES = { LECTURER: '讲师', SENIOR: '高级讲师', SPECIAL: '特级讲师', DISTINGUISHED: '特聘讲师' };
const ACTIVITIES = { TEACHING: '授课', SOLO_DEVELOPMENT: '个人独立开发', JOINT_DEVELOPMENT: '共同开发', LEGACY_UNSETTLED: '历史待补发' };
const DAYS = { WORKDAY: '工作日', REST_DAY: '休息日', STATUTORY_HOLIDAY: '法定节假日' };
const STATUSES = {
  PREVIEW_READY: '计算值已可预览，正式结算仍待完成核对。',
  CONDITIONAL_PREVIEW: '资格或业务条件待核，以下计算值仅在相关条件成立时适用。',
  INCOMPLETE: '计酬依据尚不完整，请先补充待核事项。',
  NOT_ELIGIBLE: '当前条件尚未满足计酬要求，不能据此形成应付金额。',
  HISTORY_PROTECTED: '历史记录受保护，本页不按新依据重算。',
};
const MESSAGES = {
  MONEY_ROUNDING_NOT_CONFIGURED: '金额保留位数、舍入方式、按行处理范围及批准依据尚待确定；课时保留两位不代表金额也保留两位。',
  FORMAL_RULE_CONFIGURATION_NOT_CREATED: '正式结算的规则、版本与授权依据尚待配置核对。',
  FORMAL_SETTLEMENT_CONFIRMATION_REQUIRED: '尚未经过正式结算确认。',
  JOINT_INDIVIDUAL_ALLOCATION_PERCENT_MISSING: '目前仅测算共同开发总额，个人分配比例待核。',
};
const statusOf = (error) => Number(error?.code) >= 400 && Number(error?.code) < 600 ? Number(error.code) : Number(error?.status) || 0;
const known = (value, unit = '') => value == null ? '待核' : `${value}${unit}`;
const finalMoney = (value) => value == null ? '待确定' : `${value} 元`;
const safeText = (value) => typeof value === 'string' || typeof value === 'number' ? String(value) : '待核';
const labelFor = (labels, value, fallback = '待核') => Object.hasOwn(labels, value) ? labels[value] : fallback;
class PreviewDataError extends Error {}

function validate(preview, dispatchId) {
  if (!preview || String(preview.dispatch_id) !== dispatchId || preview.can_confirm !== false
      || !Object.hasOwn(STATUSES, preview.status) || !INTEGER.test(String(preview.fact_version))
      || (typeof preview.fact_version === 'number' && !Number.isSafeInteger(preview.fact_version))) {
    throw new PreviewDataError('预览数据尚不完整，请重新读取。');
  }
  const decimal = (value) => {
    if (value != null && (typeof value !== 'string' || !DECIMAL.test(value))) {
      throw new PreviewDataError('预览数值格式不正确，请重新读取。');
    }
  };
  const checkAmounts = (item) => {
    for (const key of DECIMAL_KEYS) decimal(item[key]);
    decimal(item.rate?.unit_rate);
  };
  checkAmounts(preview);
  for (const key of ['formal_missing_items', 'issues', 'scenarios']) {
    if (preview[key] != null && !Array.isArray(preview[key])) throw new PreviewDataError('预览数据尚不完整，请重新读取。');
  }
  for (const scenario of preview.scenarios || []) {
    if (!scenario || typeof scenario !== 'object' || scenario.conditional !== true) throw new PreviewDataError('预览数据尚不完整，请重新读取。');
    decimal(scenario.unit_rate);
    decimal(scenario.payable_hours);
    decimal(scenario.raw_amount);
  }
  return preview;
}

/** Append a read-only panel to an existing modal/project container; no routing or host writes. */
export function mountPolicyPreview(root, { api, signal } = {}) {
  if (!root?.ownerDocument || typeof root.appendChild !== 'function') throw new TypeError('课酬预览需要页面容器。');
  if (typeof api !== 'function') throw new TypeError('课酬预览需要读取接口。');
  const doc = root.ownerDocument;
  let disposed = false;
  let current = null;
  let loginExpired = false;

  function element(tag, text, className, slot) {
    const node = doc.createElement(tag);
    if (text != null) node.textContent = String(text);
    if (className) node.className = className;
    if (slot) node.setAttribute('data-m05-policy-slot', slot);
    return node;
  }

  const panel = element('section', null, 'panel');
  panel.setAttribute('data-m05-policy-preview', '');
  panel.setAttribute('aria-label', '课酬预览');
  const heading = element('div', null, 'panel-head');
  heading.appendChild(element('h2', '课酬预览'));
  const refresh = element('button', '读取最新预览', 'btn gray');
  refresh.type = 'button';
  refresh.disabled = true;
  refresh.setAttribute('data-m05-policy-action', 'refresh');
  heading.appendChild(refresh);
  panel.appendChild(heading);
  const notice = element('p', '选择授课安排后查看。', 'inline-note', 'notice');
  notice.setAttribute('role', 'status');
  notice.setAttribute('aria-live', 'polite');
  panel.appendChild(notice);
  const content = element('div', null, null, 'content');
  panel.appendChild(content);
  root.appendChild(panel);

  function cell(value, label, slot) {
    const result = element('td', null, null, slot);
    result.setAttribute('data-label', label);
    const text = element('span');
    // Native break opportunities keep long exact decimals/source identifiers inside host mobile cards.
    // wbr contributes no text, so copied values and decimal precision remain unchanged.
    for (const part of String(value).split(/([A-Za-z0-9_.:-]{24,})/)) {
      const chunks = /^[A-Za-z0-9_.:-]{24,}$/.test(part) ? part.match(/.{1,16}/g) : [part];
      for (let i = 0; i < chunks.length; i++) {
        if (i) text.appendChild(element('wbr'));
        text.appendChild(element('span', chunks[i]));
      }
    }
    result.appendChild(text);
    return result;
  }

  function table(parent, rows, label) {
    const wrap = element('div', null, 'table-wrap');
    wrap.tabIndex = 0;
    wrap.setAttribute('role', 'region');
    wrap.setAttribute('aria-label', label);
    const grid = element('table', null, 'tbl');
    const header = element('thead');
    const headerRow = element('tr');
    for (const title of ['核对项目', '本次结果']) {
      const th = element('th', title); th.setAttribute('scope', 'col'); headerRow.appendChild(th);
    }
    header.appendChild(headerRow);
    grid.appendChild(header);
    const body = element('tbody');
    for (const [name, value, slot] of rows) {
      const tr = element('tr');
      tr.appendChild(cell(name, '核对项目'));
      tr.appendChild(cell(value, '本次结果', slot));
      body.appendChild(tr);
    }
    grid.appendChild(body);
    wrap.appendChild(grid);
    parent.appendChild(wrap);
  }

  function messages(parent, title, items, slot) {
    if (!items?.length) return;
    const block = element('div', null, null, slot);
    block.appendChild(element('h3', title));
    const list = element('ul');
    for (const item of items) list.appendChild(element('li', Object.hasOwn(MESSAGES, item?.code) ? MESSAGES[item.code]
      : typeof item?.message === 'string' && item.message ? item.message : '相关依据待补充核对。'));
    block.appendChild(list);
    parent.appendChild(block);
  }

  function render(preview) {
    const rate = preview.rate;
    const joint = preview.amount_scope === 'DEVELOPMENT_POOL' || rate?.activity === 'JOINT_DEVELOPMENT';
    const legacy = preview.status === 'HISTORY_PROTECTED';
    const denied = preview.status === 'NOT_ELIGIBLE';
    const conditional = preview.status === 'CONDITIONAL_PREVIEW' || preview.raw_amount_is_conditional === true;
    const basis = preview.execution_basis;
    content.appendChild(element('p', STATUSES[preview.status], 'inline-note', 'status'));
    if (basis?.adoption_decision) {
      content.appendChild(element('p', `执行依据：${safeText(basis.adoption_decision)}${basis.adoption_date ? `（确认日期：${safeText(basis.adoption_date)}）` : ''}。`, null, 'basis'));
    }
    content.appendChild(element('p', '按管理办法只读测算。实际课时与计酬课时分别记录，未确定的信息显示待核。', 'inline-note', 'help'));
    const facts = element('div');
    table(facts, [
      ['授课安排', safeText(preview.dispatch_id), 'dispatch'],
      ['授课日期', preview.service_date == null ? '待核' : safeText(preview.service_date), 'date'],
      ['实际授课分钟', known(preview.actual_minutes, ' 分钟'), 'actual-minutes'],
      ['实际课时', known(preview.actual_hours, ' 课时'), 'actual-hours'],
      ['计酬课时', known(preview.payable_hours, ' 课时'), 'payable-hours'],
      ['计酬类别', legacy ? '历史记录' : labelFor(ACTIVITIES, rate?.activity), 'activity'],
      ['参照等级', legacy ? '依原记录核实' : labelFor(GRADES, rate?.reference_grade), 'grade'],
      [joint ? '总开发课酬单价' : '课酬单价', legacy ? '依原记录核实' : known(rate?.unit_rate, ' 元／课时'), 'unit-rate'],
    ], '本次课时与计酬依据');

    if (legacy) {
      content.appendChild(element('p', '已冻结的结算以原记录为准；历史待补发须依据原规则及当时支付资格核实，不套用本次管理办法的新费率。', 'inline-note', 'legacy'));
    }
    content.appendChild(element('h3', joint ? '总开发课酬与个人分配' : '课酬金额预览'));
    const rawValue = (value) => legacy ? '依原记录核实' : denied ? '计酬条件未满足' : known(value, ' 元');
    const finalValue = (value) => legacy || denied || conditional ? '待确定' : finalMoney(value);
    const suffix = conditional ? '（条件预览）' : '';
    const amountRows = joint ? [
      [`总开发课酬 · 未舍入计算值${suffix}`, rawValue(preview.pool_raw_amount), 'pool-raw'],
      ['总开发课酬 · 舍入后试算', finalValue(preview.pool_final_amount), 'pool-final'],
      [`个人分配 · 未舍入计算值${suffix}`, rawValue(preview.individual_raw_amount), 'individual-raw'],
      ['个人分配 · 舍入后试算', finalValue(preview.individual_final_amount), 'individual-final'],
    ] : [
      [`未舍入计算值${suffix}`, rawValue(preview.raw_amount), 'raw'],
      ['舍入后试算', finalValue(preview.final_amount), 'final'],
    ];
    table(content, amountRows, '课酬预览金额');
    content.appendChild(element('p', '未舍入计算值保留原始精度；舍入后试算也仅供核对。金额处理口径或相关依据未齐时显示待确定，正式金额须经正式结算核对。当前预览不可确认。', 'inline-note', 'amount-help'));
    if (joint) content.appendChild(element('p', '总开发课酬属于共同开发总额。个人课酬须依据已核准的团队分配比例，不能按人数平均分配。', 'inline-note', 'joint'));
    content.appendChild(element('h3', '课时与计酬依据'));
    content.appendChild(facts);
    messages(content, '本次待核事项', preview.issues, 'issues');
    const alreadyShown = new Set((preview.issues || []).map((item) => item?.code).filter(Boolean));
    messages(content, '正式结算还需补齐', preview.formal_missing_items?.filter((item) => !item?.code || !alreadyShown.has(item.code)), 'missing');
    renderScenarios(preview, legacy);
    const sources = element('details', null, null, 'sources');
    sources.appendChild(element('summary', '查看依据与记录来源'));
    table(sources, [
      ['记录版本', safeText(preview.fact_version)],
      ['源文件', safeText(basis?.source_file_name)],
      ['源文件状态', basis?.source_consultation_draft === true ? '源文件标注征求意见稿；本次执行采用用户确认的依据' : '待核'],
      ['源文件发布日期', basis?.source_publication_date == null ? '未提供' : safeText(basis.source_publication_date)],
      ['原文生效日期', basis?.effective_from == null ? '未提供；不能用用户确认日期代替' : safeText(basis.effective_from)],
      ['费率来源', rate?.source_reference == null ? '待核' : safeText(rate.source_reference)],
      ['执行版本标识', safeText(basis?.execution_version)],
      ['源文件校验值', safeText(basis?.source_sha256)],
    ], '预览来源记录');
    content.appendChild(sources);
  }

  function renderScenarios(preview, legacy) {
    if (legacy || !preview.scenarios?.length) return;
    const section = element('div', null, null, 'scenarios');
    section.appendChild(element('h3', '条件费率参考'));
    section.appendChild(element('p', '尚未选定本次费率。下表仅供核对适用条件，不代表本次应付金额。', 'inline-note'));
    const wrap = element('div', null, 'table-wrap');
    wrap.tabIndex = 0;
    wrap.setAttribute('role', 'region');
    wrap.setAttribute('aria-label', '条件费率参考');
    const grid = element('table', null, 'tbl');
    const header = element('thead');
    const headerRow = element('tr');
    const headings = ['参照等级', '适用类型', '条件单价', '按已录计酬课时试算（未舍入）', '待核条件'];
    for (const title of headings) {
      const cell = element('th', title);
      cell.setAttribute('scope', 'col');
      headerRow.appendChild(cell);
    }
    header.appendChild(headerRow);
    grid.appendChild(header);
    const body = element('tbody');
    for (const scenario of preview.scenarios) {
      const row = element('tr');
      const values = [labelFor(GRADES, scenario.reference_grade),
        scenario.activity === 'TEACHING' ? labelFor(DAYS, scenario.teaching_day_type, '日别待核')
          : scenario.activity === 'JOINT_DEVELOPMENT' ? '共同开发总额' : labelFor(ACTIVITIES, scenario.activity),
        known(scenario.unit_rate, ' 元／课时'), preview.status === 'NOT_ELIGIBLE' ? '计酬条件未满足' : known(scenario.raw_amount, ' 元'), safeText(scenario.condition)];
      values.forEach((value, i) => row.appendChild(cell(value, headings[i])));
      body.appendChild(row);
    }
    grid.appendChild(body);
    wrap.appendChild(grid);
    section.appendChild(wrap);
    content.appendChild(section);
  }

  function stop() {
    if (current) current.controller.abort();
    current = null;
    content.replaceChildren();
  }

  async function open(id) {
    if (disposed || signal?.aborted || loginExpired) return;
    stop();
    refresh.disabled = true;
    panel.setAttribute('aria-busy', 'false');
    const dispatchId = String(id);
    if (!/^[1-9][0-9]*$/.test(dispatchId) || (typeof id === 'number' && !Number.isSafeInteger(id))) {
      notice.textContent = '请选择有效的授课安排。';
      notice.setAttribute('role', 'alert');
      throw new TypeError('请选择有效的授课安排。');
    }
    const session = { dispatchId, controller: new AbortController(), denied: false };
    current = session;
    const active = () => !disposed && !session.controller.signal.aborted && current === session;
    notice.textContent = '正在读取课酬预览…';
    notice.setAttribute('role', 'status');
    panel.setAttribute('aria-busy', 'true');
    try {
      const preview = await api(`/delivery-settlement/policy-preview?dispatch_id=${encodeURIComponent(dispatchId)}`, {
        method: 'GET', signal: session.controller.signal, quiet: true,
      });
      if (!active()) return;
      render(validate(preview, dispatchId));
      notice.textContent = '预览已更新，当前结果仅供核对。';
    } catch (error) {
      if (!active()) return;
      content.replaceChildren();
      const status = statusOf(error);
      session.denied = status === 401 || status === 403;
      if (status === 401) {
        loginExpired = true;
        notice.textContent = '登录状态已失效，请重新登录后查看。';
      } else if (status === 403) {
        notice.textContent = '当前账号没有查看本次课酬预览的权限。';
      } else if (error?.name === 'AbortError') {
        notice.textContent = '本次读取已取消。';
      } else if (error instanceof PreviewDataError) {
        notice.textContent = error.message;
      } else {
        notice.textContent = '暂时无法读取课酬预览，请重新读取。';
      }
      notice.setAttribute('role', 'alert');
    } finally {
      if (active()) {
        panel.setAttribute('aria-busy', 'false');
        refresh.disabled = session.denied;
      }
    }
  }

  function onRefresh() {
    if (disposed || refresh.disabled || !current) return;
    return open(current.dispatchId);
  }
  function cleanup() {
    if (disposed) return;
    disposed = true;
    stop();
    refresh.removeEventListener('click', onRefresh);
    signal?.removeEventListener('abort', cleanup);
    panel.remove();
  }
  refresh.addEventListener('click', onRefresh);
  signal?.addEventListener('abort', cleanup, { once: true });
  if (signal?.aborted) cleanup();
  return { open, cleanup };
}
