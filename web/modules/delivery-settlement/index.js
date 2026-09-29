import { convertMinutesToClassHours } from '../settlement/conversion.js';

const DECIMAL = /^[0-9]+(?:\.[0-9]+)?$/;
const SAFE_CODE = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,95}$/;
const editableKeys = ['estimated_hours', 'planned_hours', 'actual_minutes', 'payable_hours'];
const fields = [
  { k: 'estimated_hours', label: '预计课时', type: 'text', placeholder: '未知时留空' },
  { k: 'planned_hours', label: '计划课时', type: 'text', placeholder: '未知时留空' },
  { k: 'actual_minutes', label: '实际授课分钟', type: 'text', placeholder: '例如 60 或 67.5', hint: '45 分钟为 1 课时，保留两位小数。' },
  { k: 'actual_hours', label: '实际课时', type: 'readonly', placeholder: '待录入实际分钟', hint: '仅根据实际分钟换算。' },
  { k: 'payable_hours', label: '计酬课时', type: 'text', placeholder: '未知时留空', hint: '按计酬依据单独填写，不自动使用实际课时。' },
  { k: 'evidence_code', label: '核对依据编号', type: 'text', placeholder: '例如 RECORD-20260922-01', hint: '核对时必填：96 字符以内的字母、数字、点、下划线、冒号或短横线。' },
];
const snapshot = (detail) => ({
  estimated_hours: detail.fact?.hours?.estimated ?? null,
  planned_hours: detail.fact?.hours?.planned ?? null,
  actual_minutes: detail.conversion?.minutes ?? null,
  payable_hours: detail.fact?.hours?.payable ?? null,
});
const statusOf = (error) => Number(error?.code) >= 400 && Number(error?.code) < 600 ? Number(error.code) : Number(error?.status) || 0;
const errorText = (error) => error?.message || '操作未完成，请稍后重试。';
const requestId = (doc) => {
  const crypto = doc.defaultView?.crypto ?? globalThis.crypto;
  if (!crypto?.getRandomValues) throw new Error('当前浏览器不支持安全请求编号，请更换浏览器后重试。');
  if (crypto.randomUUID) return `M05-${crypto.randomUUID()}`;
  return `M05-${Array.from(crypto.getRandomValues(new Uint8Array(16)), (n) => n.toString(16).padStart(2, '0')).join('')}`;
};

const historyLabels = { estimated_hours: '预计课时', planned_hours: '计划课时', actual_minutes: '实际分钟', actual_hours: '实际课时', payable_hours: '计酬课时', verification_status: '核对状态', verification_actor_code: '核对人员标识', verification_checked_at: '核对时间', verification_evidence_code: '核对依据编号' };
const historyEscape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const historyInteger = (value, min = 0) => Number.isSafeInteger(value) && value >= min;
const historyText = value => value === null || typeof value === 'string';
function deliveryHistory(root, host) {
  if (!root) return { reset() {}, render() {}, cleanup() {} }; // Older host test adapters may not model optional regions.
  let opened = false, busy = false, blocked = false, payload = null, selected = null, message = '', request = null, ticket = 0, disposed = false;
  const active = () => !disposed && host.active();
  const allowed = () => host.canRead() && !host.busy();
  const clear = () => { ticket++; request?.abort(); request = null; busy = false; payload = null; selected = null; };
  function reset() { clear(); opened = false; blocked = false; message = ''; render(); }
  const valueText = (field, value) => value === null ? '未记录' : field === 'verification_status' ? ({ VERIFIED: '已核对', UNVERIFIED: '未核对' })[value] : value;
  const table = (columns, rows) => '<div class="table-wrap"><table class="tbl"><thead><tr>' + columns.map(column => '<th scope="col">' + column + '</th>').join('') + '</tr></thead><tbody>' + rows.map(row => '<tr>' + row.map((cell, index) => '<td data-label="' + columns[index] + '"><span style="overflow-wrap:anywhere;word-break:break-word">' + cell + '</span></td>').join('') + '</tr>').join('') + '</tbody></table></div>';
  function render() {
    if (!active()) return;
    const item = payload?.items.find(item => item.version === selected);
    let content = '';
    if (payload) {
      content = payload.total === 0 ? '<p>尚无保存或核对记录。</p>' : table(['修订版本', '操作', '操作标识', '时间', '对照'], payload.items.map(item => [String(item.version), item.event_type === 'SAVE' ? '保存' : '核对', historyEscape('系统人员标识 ' + (item.actor_code ?? '未记录') + ' · 账号 ID ' + (item.account_id ?? '未记录')), historyEscape(item.created_at), '<button type="button" class="btn gray sm" data-m05-history-action="select" data-version="' + item.version + '" aria-pressed="' + (item.version === selected) + '">查看对照</button>'])) + '<div class="toolbar"><button type="button" class="btn gray" data-m05-history-action="prev" ' + (busy || payload.page <= 1 ? 'disabled' : '') + '>上一页</button><span>第 ' + payload.page + ' / ' + Math.max(1, Math.ceil(payload.total / payload.page_size)) + ' 页 · 共 ' + payload.total + ' 次修订</span><button type="button" class="btn gray" data-m05-history-action="next" ' + (busy || payload.page * payload.page_size >= payload.total ? 'disabled' : '') + '>下一页</button></div>';
      if (item) {
        content += '<div data-m05-history-comparison><h4>版本 ' + item.version + ' · ' + (item.event_type === 'SAVE' ? '保存' : '核对') + '前后对照</h4><p>已保存课时：预计 ' + historyEscape(item.estimated_hours ?? '未记录') + ' / 计划 ' + historyEscape(item.planned_hours ?? '未记录') + ' / 实际 ' + historyEscape(item.actual_hours ?? '未记录') + ' / 计酬 ' + historyEscape(item.payable_hours ?? '未记录') + '；实际分钟 ' + historyEscape(item.actual_minutes ?? '未记录') + '。</p>';
        if (item.verification_invalidated) content += '<p class="inline-note">本次保存使原核对失效。</p>';
        content += item.changes.length ? table(['项目', '上一版本', '本次版本'], item.changes.map(change => [historyLabels[change.field], historyEscape(valueText(change.field, change.before)), historyEscape(valueText(change.field, change.after))])) : '<p>本次修订的课时与核对内容无变化。</p>';
        content += '</div>';
      }
    }
    root.innerHTML = '<button type="button" class="btn gray" data-m05-history-action="toggle" aria-expanded="' + opened + '" ' + (!opened && !allowed() ? 'disabled' : '') + '>' + (opened ? '收起修订历史与前后对照' : '修订历史与前后对照') + '</button>' + (opened ? '<div data-m05-history-panel><p class="modal-intro">仅对照已保存版本，未保存填写不参与。</p><p data-m05-history-status role="status" aria-live="polite">' + historyEscape(busy ? '正在读取修订历史…' : message) + '</p>' + content + (!busy && !payload && !blocked && allowed() ? '<button type="button" class="btn gray" data-m05-history-action="reload">重新读取历史</button>' : '') + '</div>' : '');
  }
  function validate(response, detail, page) {
    const bad = () => { throw Error('历史响应不完整'); };
    if (!response || String(response.dispatch_id) !== String(detail.dispatch_id) || String(response.project_id) !== String(detail.project_id) || String(response.teacher_id) !== String(detail.teacher_id) || response.organization_code !== detail.organization_code || String(response.current_version) !== String(detail.version) || response.page !== page || response.page_size !== 20 || !historyInteger(response.total) || !Array.isArray(response.items) || response.items.length > 20 || response.total < response.items.length || (response.total === 0 ? page !== 1 || response.items.length !== 0 : page > Math.ceil(response.total / 20))) bad();
    let previous = Number(detail.version) + 1;
    for (const item of response.items) {
      if (!historyInteger(item.version, 1) || item.version >= previous || !['SAVE', 'VERIFY'].includes(item.event_type) || !historyText(item.actor_code) || !historyInteger(item.account_id, 1) || typeof item.created_at !== 'string' || typeof item.verification_invalidated !== 'boolean' || !Array.isArray(item.changes)) bad();
      previous = item.version;
      for (const key of ['estimated_hours', 'planned_hours', 'actual_minutes', 'actual_hours', 'payable_hours']) if (item[key] !== null && (typeof item[key] !== 'string' || !DECIMAL.test(item[key]))) bad();
      if (item.verification !== null && (!item.verification || !['actor_code', 'checked_at', 'evidence_code'].every(key => typeof item.verification[key] === 'string'))) bad();
      const seen = new Set();
      for (const change of item.changes) {
        if (!change || !Object.hasOwn(historyLabels, change.field) || seen.has(change.field)) bad(); seen.add(change.field);
        for (const value of [change.before, change.after]) {
          if (!historyText(value)) bad();
          if (value !== null && (change.field === 'verification_status' ? !['UNVERIFIED', 'VERIFIED'].includes(value) : !change.field.startsWith('verification_') && !DECIMAL.test(value))) bad();
        }
      }
    }
    return response;
  }
  async function read(page = 1) {
    if (!active() || !opened || busy || blocked || !allowed()) return;
    clear(); const id = ticket, detail = host.detail(); request = new AbortController(); busy = true; message = ''; render();
    try {
      const response = await host.api('/delivery-settlement/history?dispatch_id=' + encodeURIComponent(detail.dispatch_id) + '&expected_version=' + encodeURIComponent(detail.version) + '&page=' + page + '&page_size=20', { quiet: true, signal: request.signal });
      if (!active() || id !== ticket || !opened || host.detail() !== detail) return;
      payload = validate(response, detail, page); selected = payload.items[0]?.version ?? null;
    } catch (error) {
      if (!active() || id !== ticket || error?.name === 'AbortError') return;
      payload = null; selected = null;
      const status = statusOf(error);
      blocked = [401, 403, 409].includes(status);
      message = status === 409 ? '记录已更新或历史暂不可读，请先点击“读取最新记录”；未保存填写会保留。' : [401, 403].includes(status) ? '当前无法查看修订历史，请重新读取记录或登录。' : '修订历史暂时无法读取，请重试。';
      if ([401, 403, 409].includes(status)) host.denied(status);
    } finally { if (active() && id === ticket) { busy = false; render(); } }
  }
  const click = async event => {
    const button = event.target?.closest?.('[data-m05-history-action]');
    if (!active() || !button || button.disabled || !root.contains(button)) return;
    const action = button.dataset.m05HistoryAction;
    if (action === 'toggle') { if (opened) { clear(); opened = false; render(); } else if (allowed()) { opened = true; render(); if (!blocked) await read(); } return; }
    if (busy || !allowed()) return;
    if (action === 'select' && payload?.items.some(item => String(item.version) === button.dataset.version)) { selected = Number(button.dataset.version); render(); root.querySelector('[data-m05-history-comparison]')?.scrollIntoView?.({ block: 'nearest' }); }
    else if (action === 'prev' && payload?.page > 1) await read(payload.page - 1);
    else if (action === 'next' && payload && payload.page * payload.page_size < payload.total) await read(payload.page + 1);
    else if (action === 'reload') await read();
  };
  root.addEventListener('click', click); render();
  return { reset, render, cleanup() { if (disposed) return; clear(); disposed = true; root.removeEventListener('click', click); root.innerHTML = ''; } };
}

/** Host modal adapter: docs/modules/M05/DELIVERY_COMPONENT.md. Adds no menu/page frame. */
export function mount(root, context = {}) {
  if (!root?.ownerDocument) throw new TypeError('授课记录组件需要页面根节点。');
  for (const name of ['api', 'renderForm', 'openModal']) {
    if (typeof context[name] !== 'function') throw new TypeError(`授课记录组件缺少 ${name}。`);
  }
  const doc = root.ownerDocument;
  let disposed = false;
  let current = null;
  const identity = context.getUser?.();

  function closeSession(session) {
    if (!session || session.closed) return;
    session.closed = true;
    session.controller.abort();
    session.history?.cleanup();
    session.mask?.removeEventListener('input', session.onInput);
    session.mask?.removeEventListener('click', session.onClick);
    if (current === session) current = null;
  }

  function dismiss(session) {
    if (!session) return;
    closeSession(session);
    if (session.mask?.isConnected && (!context.getModal || context.getModal() === session.mask)) {
      // The host close button also removes its focus-trap listener and restores focus.
      if (typeof context.closeModal === 'function') context.closeModal();
      else session.mask.querySelector('#modal-x')?.click();
    }
  }

  function cleanup() {
    if (disposed) return;
    disposed = true;
    context.signal?.removeEventListener('abort', cleanup);
    dismiss(current);
  }

  async function open(dispatchId) {
    if (disposed || context.signal?.aborted || context.isCurrent?.() === false || (context.getUser && context.getUser() !== identity)) return;
    if (!/^[1-9][0-9]*$/.test(String(dispatchId)) || (typeof dispatchId === 'number' && !Number.isSafeInteger(dispatchId))) {
      throw new TypeError('请选择有效的授课安排。');
    }
    dismiss(current);
    const session = {
      dispatchId, controller: new AbortController(), closed: false, unauthorized: false,
      detail: null, baseline: null, busy: false, conflict: false, pending: null, rejected: null,
      message: '', isError: false, mask: null,
    };
    current = session;
    const active = () => !disposed && !session.closed && !session.unauthorized && !session.controller.signal.aborted && current === session && context.isCurrent?.() !== false && (!context.getUser || context.getUser() === identity) && session.mask?.isConnected !== false && (!context.getModal || context.getModal() === session.mask);
    const query = (selector) => session.mask?.querySelector(selector);
    const field = (key) => query(`[data-k="${key}"]`);
    const draft = () => Object.fromEntries(editableKeys.map((key) => [key, field(key).value === '' ? null : field(key).value]));
    const dirty = () => !session.baseline || editableKeys.some((key) => draft()[key] !== session.baseline[key]);
    const unchangedRejection = (action) => session.rejected?.action === action && (action === 'save'
      ? editableKeys.every((key) => draft()[key] === session.rejected.body[key])
      : field('evidence_code').value === session.rejected.body.evidence_code);
    const capabilitiesCurrent = () => session.detail?.capabilities?.current_server === true
      && String(session.detail.capabilities.fact_version) === String(session.detail.version);
    const permitted = (action) => capabilitiesCurrent() && session.detail.capabilities.permissions?.[action] === true;
    const allowed = (action) => permitted(action) && session.detail.capabilities[`can_${action}`] === true;
    const reason = (action) => (session.detail?.capabilities?.reasons?.[action] || []).map((item) => item.message).filter(Boolean).join('；');
    const notice = (message, isError = false) => { session.message = message; session.isError = isError; };

    function validDetail(detail) {
      if (!detail || String(detail.dispatch_id) !== String(dispatchId) || !/^(0|[1-9][0-9]*)$/.test(String(detail.version))) {
        throw new Error('授课记录响应不完整，请重新读取。');
      }
      for (const value of Object.values(snapshot(detail))) {
        if (value !== null && (typeof value !== 'string' || !DECIMAL.test(value))) throw new Error('课时数据格式不正确，请重新读取。');
      }
      return detail;
    }

    function applyDetail(detail, replaceInput) {
      const initialRead = !session.detail;
      session.history?.reset();
      session.detail = validDetail(detail);
      session.baseline = snapshot(detail);
      if (replaceInput) {
        for (const key of editableKeys) field(key).value = session.baseline[key] ?? '';
      }
      if (initialRead) field('evidence_code').value = detail.fact?.verification?.evidence_code ?? '';
    }

    function render() {
      if (!session.mask || session.closed) return;
      const blocked = session.busy || session.unauthorized || !!session.pending;
      for (const key of editableKeys) {
        field(key).disabled = blocked || !allowed('save');
        field(key).setAttribute('inputmode', 'decimal');
        field(key).setAttribute('autocomplete', 'off');
      }
      field('evidence_code').disabled = blocked || !permitted('verify');
      field('evidence_code').maxLength = 96;
      field('actual_hours').disabled = !session.detail || session.unauthorized;
      let actual = '';
      let conversionMessage = '未知的课时保持空白，四类课时分别记录。';
      const minutes = field('actual_minutes').value;
      if (minutes !== '') {
        try {
          actual = convertMinutesToClassHours(minutes);
          conversionMessage = `${minutes} 分钟 = ${actual} 课时。此处为预览，保存后以服务端记录为准。`;
        } catch (error) { conversionMessage = errorText(error); }
      }
      field('actual_hours').value = actual;
      query('[data-m05-conversion]').textContent = conversionMessage;
      const detail = session.detail;
      query('[data-m05-meta]').textContent = detail
        ? `授课安排 ${detail.dispatch_id} · 项目 ${detail.project_id ?? '未知'} · 师资 ${detail.teacher_id ?? '未知'} · 机构 ${detail.organization_code ?? '未知'} · 版本 ${detail.version}`
        : '正在读取授课记录…';
      const hours = detail?.fact?.hours || {};
      query('[data-m05-saved]').textContent = detail
        ? `已保存：预计 ${hours.estimated ?? '未填写'} / 计划 ${hours.planned ?? '未填写'} / 实际 ${hours.actual ?? '未填写'} / 计酬 ${hours.payable ?? '未填写'}（课时）`
        : '';
      const verification = detail?.fact?.verification;
      query('[data-m05-verification]').textContent = verification
        ? `已核对 · 核对人 ${verification.actor_code} · 时间 ${verification.checked_at} · 依据 ${verification.evidence_code}`
        : '尚未核对';
      const save = query('[data-m05-action="save"]');
      const verify = query('[data-m05-action="verify"]');
      const complete = query('[data-m05-action="complete"]');
      const visible = (element, show) => { element.hidden = !show; element.style.display = show ? '' : 'none'; };
      save.disabled = blocked || session.conflict || unchangedRejection('save') || !allowed('save') || !dirty();
      visible(verify, permitted('verify'));
      verify.disabled = blocked || session.conflict || unchangedRejection('verify') || dirty() || !allowed('verify');
      visible(complete, typeof context.onComplete === 'function' && permitted('complete'));
      complete.disabled = blocked || session.conflict || dirty() || !allowed('complete');
      query('[data-m05-action="refresh"]').disabled = session.busy || session.unauthorized;
      const retry = query('[data-m05-action="retry"]');
      visible(retry, !!session.pending);
      retry.disabled = session.busy || session.unauthorized || !session.pending || !permitted(session.pending.action);
      let capabilityMessage = '';
      if (detail && !capabilitiesCurrent()) capabilityMessage = '权限信息尚未取得，请重新读取后再操作。';
      else if (detail && !permitted('verify')) capabilityMessage = permitted('save')
        ? '当前账号可保存草稿，授课核对须由有权限的人员完成。'
        : '当前账号仅可查看授课记录。';
      else if (detail && dirty()) capabilityMessage = '有尚未保存的修改，请先保存草稿，再核对或完成授课。';
      else if (detail && !allowed('verify')) capabilityMessage = reason('verify');
      if (detail && !allowed('save') && reason('save')) capabilityMessage = [capabilityMessage, reason('save')].filter(Boolean).join(' ');
      if (detail && typeof context.onComplete === 'function' && permitted('complete') && !allowed('complete') && reason('complete')) {
        capabilityMessage = [capabilityMessage, reason('complete')].filter(Boolean).join(' ');
      }
      query('[data-m05-capability]').textContent = capabilityMessage;
      const status = query('[data-m05-status]');
      status.textContent = session.busy ? '正在处理，请稍候…' : session.message;
      status.setAttribute('role', session.isError ? 'alert' : 'status');
      session.history?.render();
    }

    function invalid(key, message) {
      const control = field(key);
      control.setAttribute('aria-invalid', 'true');
      const error = control.closest('.form-item')?.querySelector('.field-error');
      if (error) error.textContent = message;
      notice(message, true);
      control.focus();
      render();
    }

    function validateDraft() {
      const values = draft();
      for (const key of editableKeys) {
        const value = values[key];
        if (value === null) continue;
        const label = fields.find((item) => item.k === key).label;
        if (value.length > 40 || !DECIMAL.test(value)) {
          invalid(key, `${label}须填写非负十进制数；未知时留空，不要输入空格。`);
          return null;
        }
        if (key === 'actual_minutes') {
          try { convertMinutesToClassHours(value); }
          catch (error) { invalid(key, errorText(error)); return null; }
        }
      }
      return values;
    }

    function stopUnauthorized() {
      session.history?.reset();
      session.unauthorized = true;
      session.pending = null;
      session.controller.abort();
      notice('登录状态已失效，请重新登录。', true);
      render();
      session.mask.removeEventListener('input', session.onInput);
      session.mask.removeEventListener('click', session.onClick);
    }

    async function read({ replaceInput = false, permissionRefresh = false, successMessage = '' } = {}) {
      if (!active() || session.busy) return;
      session.history?.reset();
      session.busy = true;
      render();
      try {
        const detail = await context.api(`/delivery-settlement?dispatch_id=${encodeURIComponent(dispatchId)}`, { signal: session.controller.signal, quiet: true });
        if (!active()) return;
        applyDetail(detail, replaceInput || !session.detail);
        session.conflict = false;
        notice(successMessage || (permissionRefresh ? '权限已重新读取，输入已保留；请根据当前权限手动操作。'
          : session.pending ? '已读取最新记录，输入已保留。上次提交结果仍待确认，可使用同一请求重试。'
            : replaceInput ? '' : '已读取最新记录，输入已保留；请核对已保存内容后手动保存。'));
      } catch (error) {
        if (!active() || error?.name === 'AbortError') return;
        if (statusOf(error) === 401) stopUnauthorized();
        else {
          session.conflict = true;
          // Failed permission refresh must not leave old capabilities actionable.
          if (permissionRefresh && session.detail) session.detail = { ...session.detail, capabilities: null };
          notice(`${errorText(error)} 输入已保留，请重新读取。`, true);
        }
      } finally { session.busy = false; render(); }
    }

    async function mutate(action, body) {
      if (!active() || session.busy) return;
      session.history?.reset();
      session.busy = true;
      render();
      let refreshPermission = false;
      let callback = null;
      let result = null;
      let staleResult = false;
      try {
        result = await context.api(`/delivery-settlement/${action}`, { body, signal: session.controller.signal, quiet: true });
        if (!active()) return;
        validDetail(result);
        session.pending = null;
        session.rejected = null;
        session.conflict = false;
        staleResult = result.capabilities?.current_server === true
          && String(result.capabilities.fact_version) !== String(result.version);
        applyDetail(result, action === 'save' && !staleResult);
        notice(action === 'save' ? '草稿已保存。' : '授课记录已核对。');
        callback = action === 'save' ? context.onSaved : context.onVerified;
      } catch (error) {
        if (!active() || error?.name === 'AbortError') return;
        const status = statusOf(error);
        if (status === 401) stopUnauthorized();
        else if (status === 403) {
          session.pending = null;
          session.conflict = true;
          refreshPermission = true;
          notice(`${errorText(error)} 正在重新读取权限，输入已保留。`, true);
        } else if (status === 409) {
          session.pending = null;
          session.conflict = true;
          notice(`${errorText(error)} 输入已保留，请先点击“读取最新记录”，核对后手动保存。`, true);
        } else if (status === 400 || (status >= 400 && status < 500)) {
          session.pending = null;
          session.rejected = { action, body };
          notice(`${errorText(error)} 输入已保留，请修改后再提交。`, true);
        } else {
          session.pending = { action, body };
          notice(`${errorText(error)} 提交结果尚不确定，输入已保留；可明确选择“重试上次提交”，将使用相同请求编号。`, true);
        }
      } finally { session.busy = false; render(); }
      if (refreshPermission && active()) await read({ permissionRefresh: true });
      if (staleResult && active()) await read({ successMessage: '上次提交已确认，现已读取当前版本。输入已保留，请核对已保存内容后再操作。' });
      if (callback && active()) {
        try { await callback(session.detail); }
        catch (_) { if (active()) { notice('记录已提交成功，但列表刷新未完成，请刷新页面。', true); render(); } }
      }
    }

    async function complete() {
      if (!active() || session.busy || session.pending || session.conflict || dirty() || !allowed('complete') || typeof context.onComplete !== 'function') return;
      session.history?.reset();
      session.busy = true;
      render();
      let refresh = false;
      let permissionRefresh = false;
      try {
        await context.onComplete({ dispatch_id: dispatchId, expected_version: session.detail.version, signal: session.controller.signal });
        if (!active()) return;
        refresh = true;
        notice('授课已完成。');
      } catch (error) {
        if (!active() || error?.name === 'AbortError') return;
        if (statusOf(error) === 401) stopUnauthorized();
        else {
          session.conflict = true;
          permissionRefresh = statusOf(error) === 403;
          notice(`${errorText(error)} 输入已保留，请读取最新记录后再操作。`, true);
        }
      } finally { session.busy = false; render(); }
      if ((refresh || permissionRefresh) && active()) await read({ permissionRefresh, successMessage: refresh ? '授课已完成，记录已更新。' : '' });
    }

    session.onInput = (event) => {
      const control = event.target?.closest?.('[data-k]');
      if (!active() || session.busy || !control || !session.mask.contains(control)) return;
      control.removeAttribute('aria-invalid');
      const error = control.closest('.form-item')?.querySelector('.field-error');
      if (error) error.textContent = '';
      render();
    };
    session.onClick = async (event) => {
      const button = event.target?.closest?.('[data-m05-action]');
      if (!active() || session.busy || !button || button.disabled || !session.mask.contains(button)) return;
      const action = button.dataset.m05Action;
      if (action === 'refresh') return read();
      if (action === 'retry') {
        const pending = session.pending;
        if (pending && permitted(pending.action)) return mutate(pending.action, pending.body);
        return;
      }
      if (action === 'complete') return complete();
      if (session.pending || session.conflict || unchangedRejection(action) || !allowed(action)) return;
      let payload = {};
      if (action === 'save') {
        if (!dirty()) return;
        payload = validateDraft();
        if (!payload) return;
      } else if (action === 'verify') {
        if (dirty()) { notice('请先保存草稿，再核对授课。', true); render(); return; }
        const evidence = field('evidence_code').value;
        if (!SAFE_CODE.test(evidence)) { invalid('evidence_code', '请填写有效的核对依据编号（96 字符以内）。'); return; }
        payload.evidence_code = evidence;
      } else return;
      let id;
      try { id = requestId(doc); }
      catch (error) { notice(errorText(error), true); render(); return; }
      return mutate(action, { dispatch_id: dispatchId, expected_version: session.detail.version, request_id: id, ...payload });
    };

    const form = context.renderForm(fields.map((item) => ({ ...item, disabled: true })), {});
    session.mask = context.openModal('授课记录与核对', `
      <section data-m05-delivery aria-label="授课记录与核对">
        <p class="inline-note" data-m05-meta></p>
        <p data-m05-saved></p>
        ${form}
        <p class="inline-note" data-m05-conversion role="status" aria-live="polite"></p>
        <p data-m05-verification></p>
        <p class="inline-note" data-m05-capability></p>
        <p data-m05-status role="status" aria-live="polite"></p>
        <div class="modal-foot" style="flex-wrap:wrap">
          <button type="button" class="btn gray" data-m05-action="refresh">读取最新记录</button>
          <button type="button" class="btn gray" data-m05-action="retry" hidden>重试上次提交</button>
          <button type="button" class="btn" data-m05-action="save" disabled>保存草稿</button>
          <button type="button" class="btn green" data-m05-action="verify" hidden disabled>核对授课</button>
          <button type="button" class="btn orange" data-m05-action="complete" hidden disabled>完成授课</button>
        </div>
        <section data-m05-history aria-label="修订历史与前后对照"></section>
      </section>`, { noFoot: true, wide: true, kicker: '课程与排期', onClose: () => closeSession(session) });
    if (!session.mask?.querySelector) { closeSession(session); throw new TypeError('openModal 必须返回本次弹窗节点。'); }
    session.history = deliveryHistory(query('[data-m05-history]'), { api: context.api, active, detail: () => session.detail, busy: () => session.busy || !!session.pending, canRead: () => !!session.detail && capabilitiesCurrent() && !session.conflict, denied: status => { if (status === 401) stopUnauthorized(); else { session.conflict = true; if (status === 403) session.detail = { ...session.detail, capabilities: null }; render(); } } });
    session.mask.addEventListener('input', session.onInput);
    session.mask.addEventListener('click', session.onClick);
    await read({ replaceInput: true });
  }

  context.signal?.addEventListener('abort', cleanup, { once: true });
  if (context.signal?.aborted) cleanup();
  return { open, cleanup };
}
