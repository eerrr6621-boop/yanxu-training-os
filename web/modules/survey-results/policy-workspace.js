// Administrator-only fixed policy publication. The server owns every rule and reviewer role.
const owners = new WeakMap();
const PAGE = 20;
const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
const text = (value, max) => typeof value === 'string' && value.trim().length > 0 && value.length <= max;
const code = value => text(value, 96) && /^[A-Za-z0-9_.:-]+$/.test(value);
const stamp = value => typeof value === 'string' && value.length <= 40 && /^\d{4}-\d\d-\d\dT/.test(value) && Number.isFinite(Date.parse(value));
const fail = () => { throw Object.assign(new Error('统计规则返回内容不完整，请重新读取后核对。'), { viewError: true }); };
const rule = (value, approved = false) => {
  if (!value || !text(value.label, 200) || !Array.isArray(value.description) || value.description.length < 1 || value.description.length > 12 || !value.description.every(line => text(line, 1000)) || approved && !code(value.version)) fail();
  return { label: value.label, description: [...value.description], ...(approved ? { version: value.version } : {}) };
};
function snapshot(value) {
  if (!value || value.format !== 'M07_POLICY_WORKSPACE_1' || value.synthetic !== false || typeof value.publication_context !== 'string' || !/^[a-f0-9]{64}$/.test(value.publication_context)
      || !Array.isArray(value.organizations) || value.organizations.length > 10000) fail();
  const approved = rule(value.approved_rule, true), seen = new Set();
  const organizations = value.organizations.map(item => {
    if (!item || !text(item.organization_code, 120) || !text(item.organization_name, 200) || seen.has(item.organization_code)
        || typeof item.matches_approved !== 'boolean' || typeof item.can_publish !== 'boolean') fail();
    seen.add(item.organization_code);
    if (item.current_version === null ? item.published_at !== null || item.current_rule !== null || item.matches_approved
      : !code(item.current_version) || !stamp(item.published_at) || !item.current_rule) fail();
    return { organization_code: item.organization_code, organization_name: item.organization_name, current_version: item.current_version,
      published_at: item.published_at, current_rule: item.current_rule === null ? null : rule(item.current_rule), matches_approved: item.matches_approved, can_publish: item.can_publish };
  });
  return { approved, organizations, publication_context: value.publication_context };
}
const errorText = error => error?.viewError ? error.message : ({
  400: '启用请求未被接受，请重新读取统计规则。', 401: '登录状态已失效，请重新登录。',
  403: '当前账号没有统计规则管理权限。', 409: '统计规则或管理范围已变化，请重新读取后核对。',
  429: '正在处理其他请求，请稍后重新读取。', 503: '统计规则服务暂时不可用，请稍后重新读取。',
}[error?.status || error?.code] || '连接或返回结果异常，请重新读取当前状态。');
const when = value => new Date(value).toLocaleString('zh-CN', { hour12: false });

export function mount(root, context = {}) {
  if (!root || typeof root.replaceChildren !== 'function' || typeof context.api !== 'function' || typeof context.getUser !== 'function') throw new TypeError('统计规则需要原系统身份及请求工具。');
  owners.get(root)?.destroy();
  const identity = context.getUser(), identityKey = JSON.stringify(identity), life = new AbortController();
  const state = { data: null, selected: null, acknowledged: false, busy: '', notice: '', noticeError: false, blocked: false, uncertain: null, page: 0 };
  let alive = true, epoch = 0, readAbort = null;
  const controller = { ready: null, refresh: () => load(), destroy, beforeClose: () => !current() || state.busy !== 'publish' };
  owners.set(root, controller);
  function current() {
    if (!alive) return false;
    if (life.signal.aborted || context.signal?.aborted || root.isConnected === false || context.isCurrent?.() === false || !identity || identity.role !== 'admin'
        || context.getUser() !== identity || JSON.stringify(context.getUser()) !== identityKey) { destroy(); return false; }
    return true;
  }
  function destroy() {
    if (!alive) return;
    alive = false; epoch++; life.abort(); readAbort?.abort(); context.signal?.removeEventListener('abort', destroy);
    root.removeEventListener('click', click); root.removeEventListener('change', change);
    state.data = state.selected = state.uncertain = null; state.acknowledged = false;
    if (owners.get(root) === controller) { owners.delete(root); root.replaceChildren(); }
  }
  const disabled = value => value ? ' disabled' : '';
  const canPublish = () => !!state.data && !!state.selected && !state.busy && !state.blocked && !state.uncertain && state.selected.can_publish && !state.selected.matches_approved && state.acknowledged;
  const ruleHtml = value => `<p><b>${esc(value.label)}</b></p><ul>${value.description.map(line => `<li>${esc(line)}</li>`).join('')}</ul>`;
  function render() {
    if (!current()) return;
    const data = state.data, selected = state.selected;
    const pages = data ? Math.max(1, Math.ceil(data.organizations.length / PAGE)) : 1;
    state.page = Math.min(state.page, pages - 1);
    root.innerHTML = `<section aria-label="满意度统计规则"><p class="modal-intro">将已确认的规则启用于有管理权限的机构。规则由系统统一提供；此操作不导入问卷、不修改人员岗位，也不会直接替换历史评分结果。</p>
      <div class="toolbar"><button type="button" class="btn gray" data-policy-refresh${disabled(!!state.busy)}>${state.uncertain ? '重新读取当前状态' : '刷新状态'}</button></div>
      <p class="${state.noticeError ? 'field-error' : 'modal-note'}" role="status" aria-live="polite" data-policy-notice>${esc(state.notice || (state.busy === 'read' ? '正在读取统计规则…' : state.busy === 'publish' ? '正在启用，请等待结果…' : ''))}</p>
      ${state.uncertain ? '<p class="inline-note">上次启用的结果尚未确认。请先重新读取当前状态，不要重复点击启用。</p>' : ''}
      ${data ? `${ruleHtml(data.approved)}<div class="table-wrap"><table class="tbl"><thead><tr><th>机构</th><th>当前状态</th><th>启用时间</th><th>操作</th></tr></thead><tbody>${data.organizations.slice(state.page * PAGE, (state.page + 1) * PAGE).map((item, index) => `<tr><td>${esc(item.organization_name)}</td><td><span class="tag ${item.matches_approved ? 'green' : item.current_version === null ? 'gray' : 'orange'}">${item.matches_approved ? '已按本次规则生效' : item.current_version === null ? '尚未启用' : '已有其他规则'}</span></td><td>${item.published_at ? esc(when(item.published_at)) : '—'}</td><td>${item.matches_approved ? '<span>无需重复启用</span>' : `<button type="button" class="btn gray sm" data-policy-select="${state.page * PAGE + index}"${disabled(!!state.busy || state.blocked || !!state.uncertain || !item.can_publish)}>查看并启用</button>`}</td></tr>`).join('') || '<tr><td colspan="4">当前没有可管理统计规则的机构。</td></tr>'}</tbody></table></div>
      ${pages > 1 ? `<div class="toolbar"><button type="button" class="btn gray sm" data-policy-prev${disabled(state.page === 0 || !!state.busy)}>上一页</button><span>第 ${state.page + 1} / ${pages} 页</span><button type="button" class="btn gray sm" data-policy-next${disabled(state.page + 1 >= pages || !!state.busy)}>下一页</button></div>` : ''}
      ${selected ? `<section class="card" aria-label="确认启用统计规则"><h3>${esc(selected.organization_name)}</h3>${selected.current_rule ? `<details><summary>查看当前使用的规则</summary>${ruleHtml(selected.current_rule)}</details><p class="inline-note">旧评分结果保留为历史；需按新口径重新生成修正版并由另一名获授权管理员复核，才能继续作为正式来源。</p>` : '<p>该机构尚未启用统计规则。启用后，获授权人员可按此规则办理正式评分汇总。</p>'}
      <label><input type="checkbox" data-policy-ack${state.acknowledged ? ' checked' : ''}${disabled(!!state.busy || state.blocked || !!state.uncertain)}>我已核对机构及上述已确认规则，确认启用</label>
      <div class="toolbar"><button type="button" class="btn" data-policy-publish${disabled(!canPublish())}>确认启用</button><button type="button" class="btn gray" data-policy-cancel${disabled(!!state.busy)}>取消</button></div></section>` : ''}` : ''}</section>`;
  }
  function deny(error) {
    const status = error?.status || error?.code;
    if (![401, 403].includes(status)) return false;
    state.data = state.selected = state.uncertain = null; state.acknowledged = false; state.blocked = true;
    if (status === 403) context.onForbidden?.(); else context.onUnauthorized?.();
    return true;
  }
  async function load(message = '') {
    if (!current() || state.busy === 'publish') return false;
    const ticket = ++epoch, uncertain = state.uncertain;
    readAbort?.abort(); readAbort = new AbortController(); state.data = state.selected = null; state.acknowledged = false; state.busy = 'read'; state.notice = message; state.noticeError = false; render();
    try {
      const result = await context.api('/survey-results/policies', { method: 'GET', quiet: true, signal: readAbort.signal });
      if (!current() || ticket !== epoch) return false;
      state.data = snapshot(result); state.blocked = false; state.uncertain = null;
      state.notice = message || (uncertain ? state.data.organizations.find(item => item.organization_code === uncertain)?.matches_approved ? '已重新核对：该机构当前已按本次规则生效。' : '已重新读取当前状态，请核对后再选择是否启用。' : '');
      return true;
    } catch (error) {
      if (!current() || ticket !== epoch) return false;
      deny(error); state.noticeError = true; state.notice = (message ? `${message} 状态刷新未完成。` : '') + errorText(error); return false;
    } finally { if (current() && ticket === epoch) { state.busy = ''; render(); } }
  }
  async function publish() {
    if (!current() || !canPublish()) return false;
    const selected = state.selected, ticket = ++epoch;
    const body = { organization_code: selected.organization_code, expected_version: selected.current_version, publication_context: state.data.publication_context, acknowledged: true };
    state.busy = 'publish'; state.notice = ''; state.noticeError = false; render();
    try {
      const result = await context.api('/survey-results/policies/publish', { method: 'POST', body, quiet: true, signal: life.signal });
      if (!current() || ticket !== epoch) return false;
      if (!result || result.organization_code !== selected.organization_code || !code(result.current_version) || !stamp(result.published_at) || typeof result.already_current !== 'boolean' || result.reload_required !== true
          || result.already_current && result.current_version !== selected.current_version) fail();
      state.selected = null; state.acknowledged = false; state.busy = '';
      await load(result.already_current ? '该机构已按本次规则生效，未重复创建版本。' : '统计规则已启用。');
      return true;
    } catch (error) {
      if (!current() || ticket !== epoch) return false;
      const status = error?.status || error?.code;
      if (!deny(error)) {
        state.selected = null; state.acknowledged = false; state.blocked = true;
        if (![400, 409].includes(status)) state.uncertain = selected.organization_code;
      }
      state.noticeError = true; state.notice = errorText(error); return false;
    } finally { if (current() && ticket === epoch) { state.busy = ''; render(); } }
  }
  function click(event) {
    if (!current()) return;
    const button = event.target.closest?.('button'); if (!button || !root.contains(button) || button.disabled) return;
    if (button.hasAttribute('data-policy-refresh')) void load();
    else if (button.hasAttribute('data-policy-select') && state.data && !state.busy && !state.blocked && !state.uncertain) {
      const index = Number(button.dataset.policySelect), item = Number.isSafeInteger(index) ? state.data.organizations[index] : null;
      if (item?.can_publish && !item.matches_approved) { state.selected = item; state.acknowledged = false; state.notice = ''; render(); }
    } else if (button.hasAttribute('data-policy-publish')) void publish();
    else if (button.hasAttribute('data-policy-cancel') && !state.busy) { state.selected = null; state.acknowledged = false; render(); }
    else if (button.hasAttribute('data-policy-prev') && !state.busy) { state.page--; render(); }
    else if (button.hasAttribute('data-policy-next') && !state.busy) { state.page++; render(); }
  }
  function change(event) {
    if (!current() || !event.target.matches?.('[data-policy-ack]') || state.busy || state.blocked || state.uncertain) return;
    state.acknowledged = event.target.checked === true;
    const button = root.querySelector('[data-policy-publish]'); if (button) button.disabled = !canPublish();
  }
  root.addEventListener('click', click); root.addEventListener('change', change);
  context.signal?.addEventListener('abort', destroy, { once: true });
  controller.ready = load();
  return controller;
}
