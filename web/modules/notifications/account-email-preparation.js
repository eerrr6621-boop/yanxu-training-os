// Candidate email registration only. The server determines eligibility; this never sends mail or activates accounts.
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const positiveId = value => Number.isSafeInteger(value) && value > 0;
const statusOf = error => Number(error?.status || error?.code);
const validState = value => value && ['MISSING', 'PENDING_VERIFICATION'].includes(value.status) &&
  (value.status === 'MISSING' ? value.email === null : typeof value.email === 'string' && value.email.length > 0);
function validated(value, userId) {
  if (!value || value.user_id !== userId || !Number.isSafeInteger(value.revision) || value.revision < 0 || !validState(value) || value.can_save !== true || typeof value.duplicate_email !== 'boolean' || !Array.isArray(value.history) ||
      !value.history.every(row => Number.isSafeInteger(row?.revision) && row.revision > 0 && row.revision <= value.revision && validState(row) && positiveId(row.actor_user_id) && typeof row.recorded_at === 'string')) throw Error('登记资料暂时无法核实，请重新读取。');
  return value;
}
const localTime = value => { const date = new Date(value); return Number.isFinite(date.getTime()) ? date.toLocaleString('zh-CN', { hour12: false }) : '时间待核实'; };

/** Original-page modal controller. getModal must return the actual current original modal element. */
export function createAccountEmailPreparation(host) {
  for (const key of ['api', 'getUser', 'getModal', 'openModal', 'closeModal', 'renderForm', 'collectForm', 'renderTable', 'toast']) if (typeof host?.[key] !== 'function') throw TypeError('Missing email preparation host: ' + key);
  const identity = host.getUser();
  let alive = true, owned = null;
  const active = () => alive && !host.signal?.aborted && (host.isCurrent?.() ?? true) && host.getUser() === identity && identity?.role === 'admin';
  const current = scope => active() && owned === scope && !scope.closed && scope.mask?.isConnected !== false && host.getModal() === scope.mask;
  const ensureCurrent = scope => { if (current(scope)) return true; if (owned === scope && !active()) closeOwned(); return false; };
  const node = (scope, key) => scope.mask.querySelector('[data-email-' + key + ']');
  const input = scope => scope.mask.querySelector('[data-k="email"]');
  const notice = (scope, text) => { if (current(scope)) node(scope, 'notice').textContent = text; };
  function clear(scope) {
    scope.ready = false; scope.snapshot = null;
    if (scope.mask) { input(scope).value = ''; node(scope, 'state').textContent = ''; node(scope, 'duplicate').textContent = ''; node(scope, 'history').innerHTML = ''; }
  }
  function buttons(scope) {
    if (!current(scope)) return;
    const blocked = scope.busy || !scope.ready || Boolean(scope.pending);
    input(scope).disabled = blocked;
    node(scope, 'save').disabled = blocked || scope.requireInput;
    node(scope, 'clear').disabled = blocked;
    node(scope, 'read').disabled = scope.busy || Boolean(scope.pending);
    node(scope, 'retry').disabled = scope.busy || !scope.pending;
    node(scope, 'retry').style.display = scope.pending ? '' : 'none';
    scope.mask.dataset.locked = scope.busy && scope.pending ? 'true' : 'false';
  }
  function render(scope, result, refill) {
    scope.snapshot = result; scope.ready = true; scope.requireInput = !refill;
    input(scope).value = refill ? result.email || '' : '';
    node(scope, 'state').textContent = result.status === 'MISSING' ? '登记状态：未登记' : '登记状态：待本人验证';
    node(scope, 'duplicate').textContent = result.duplicate_email ? '该邮箱已用于其他登记，请核对。' : '';
    const rows = result.history.map(row => ({ ...row, id: row.revision }));
    node(scope, 'history').innerHTML = '<div class="section-title"><span>修改记录</span></div>' + (rows.length ? host.renderTable([
      { k: 'revision', l: '版本' }, { k: 'email', l: '候选邮箱', render: row => `<span style="overflow-wrap:anywhere;word-break:break-word">${escape(row.email ?? '已清除')}</span>` },
      { k: 'status', l: '登记状态', render: row => row.status === 'MISSING' ? '未登记' : '待本人验证' },
      { k: 'actor_user_id', l: '登记账号', render: row => '账号 #' + row.actor_user_id },
      { k: 'recorded_at', l: '登记时间', render: row => escape(localTime(row.recorded_at)) },
    ], rows, []) : '<p class="modal-intro">尚无登记记录。</p>');
  }
  function dispose(scope) {
    if (!scope || scope.closed) return;
    scope.closed = true; scope.controller.abort(); scope.readController?.abort(); scope.readTicket++; scope.pending = null;
    clear(scope); if (owned === scope) owned = null;
  }
  function closeOwned() {
    const scope = owned; if (!scope) return;
    const mask = scope.mask; dispose(scope);
    if (host.getModal() === mask) host.closeModal(true);
  }
  async function read(scope, refill = true) {
    if (!ensureCurrent(scope) || scope.busy || scope.pending) return;
    const ticket = ++scope.readTicket; scope.readController?.abort(); scope.readController = new AbortController();
    scope.busy = true; clear(scope); buttons(scope); notice(scope, '正在核实登记资格与邮箱资料…');
    try {
      const response = await host.api('/account-email-preparation?user_id=' + scope.userId, { quiet: true, signal: scope.readController.signal });
      if (!ensureCurrent(scope) || ticket !== scope.readTicket) return;
      render(scope, validated(response, scope.userId), refill);
      notice(scope, refill ? '' : '登记资料已更新，旧填写已清除。请重新填写候选邮箱；如需清除登记，请先点击“清空填写”再保存。');
    } catch (error) {
      if (!ensureCurrent(scope) || ticket !== scope.readTicket || error?.name === 'AbortError') return;
      clear(scope);
      if ([401, 403].includes(statusOf(error))) { closeOwned(); return; }
      notice(scope, [404, 409].includes(statusOf(error)) ? '当前账号不符合待启用邮箱登记条件，或资格已变化。请关闭后核对账号状态。' : '邮箱资料暂时无法读取，请重新读取后再登记。');
    } finally { if (current(scope) && ticket === scope.readTicket) { scope.busy = false; buttons(scope); } }
  }
  async function save(scope, retry = false) {
    if (!ensureCurrent(scope) || scope.busy) return;
    if (!retry) {
      if (!scope.ready || scope.pending || scope.requireInput) return;
      const values = host.collectForm(scope.mask, scope.fields); if (!values) return;
      scope.pending = { user_id: scope.userId, expected_revision: scope.snapshot.revision, request_id: host.newRequestId ? host.newRequestId() : globalThis.crypto.randomUUID(), email: values.email };
    }
    if (!scope.pending) return;
    const request = scope.pending;
    scope.busy = true; buttons(scope); notice(scope, '正在保存候选邮箱…');
    let conflict = false;
    try {
      const response = await host.api('/account-email-preparation', { body: { ...request }, quiet: true, signal: scope.controller.signal });
      if (!ensureCurrent(scope)) return;
      const result = validated(response, scope.userId);
      if (result.revision < request.expected_revision) throw Error('保存回执尚未核实');
      scope.pending = null; render(scope, result, true);
      notice(scope, result.status === 'MISSING' ? '已清除，修改记录已保留。' : '已保存，待本人验证。');
    } catch (error) {
      if (!ensureCurrent(scope) || error?.name === 'AbortError') return;
      const status = statusOf(error);
      if ([401, 403].includes(status)) { scope.pending = null; clear(scope); closeOwned(); return; }
      if (status === 409) { scope.pending = null; clear(scope); conflict = true; }
      else if (status === 404) { scope.pending = null; clear(scope); notice(scope, '当前账号不符合邮箱登记条件，请关闭后核对账号状态。'); }
      else if (status >= 400 && status < 500) { scope.pending = null; notice(scope, '邮箱未保存，请核对候选地址格式后再提交。'); }
      else notice(scope, '本次保存结果尚未确认。请点击“重试上次保存”核实同一次请求；不会自动重发。');
    } finally {
      if (current(scope)) { scope.busy = false; buttons(scope); if (conflict) await read(scope, false); }
    }
  }
  async function open(userId, display = {}) {
    if (!active() || !positiveId(userId)) return false;
    if (host.getModal()) { host.toast('请先完成或关闭当前窗口，再登记邮箱。', true); return false; }
    const scope = { userId, mask: null, controller: new AbortController(), readController: null, readTicket: 0, closed: false, busy: false, ready: false, requireInput: false, pending: null, snapshot: null,
      fields: [{ k: 'email', label: '候选邮箱', type: 'email', span2: true, autocomplete: 'off', hint: '留空并保存可清除登记，修改记录会保留。' }] };
    scope.mask = host.openModal('登记邮箱', `<div class="inline-note" data-email-target><span style="overflow-wrap:anywhere"><b>${escape(display.name || '待启用账号')}</b><br>系统账号：${escape(display.username || '未提供')} · 内部账号 #${userId}</span></div><p class="modal-intro">请填写此人的常用邮箱，保存后需本人验证。</p><p data-email-notice class="modal-intro" role="status" aria-live="polite"></p><p data-email-state class="modal-intro"></p><p data-email-duplicate class="modal-intro" role="status"></p>` + host.renderForm(scope.fields) + '<div class="toolbar"><button type="button" class="btn" data-email-save disabled>保存邮箱</button><button type="button" class="btn gray" data-email-clear disabled>清空填写</button><button type="button" class="btn gray" data-email-read>重新读取</button><button type="button" class="btn gray" data-email-retry disabled>重试上次保存</button></div><div data-email-history></div>', { noFoot: true, onClose: () => dispose(scope) });
    if (!scope.mask) return false;
    owned = scope;
    node(scope, 'save').onclick = () => save(scope);
    node(scope, 'retry').onclick = () => save(scope, true);
    node(scope, 'read').onclick = () => read(scope);
    node(scope, 'clear').onclick = () => { if (!current(scope) || scope.busy || scope.pending || !scope.ready) return; input(scope).value = ''; scope.requireInput = false; notice(scope, '填写已清空，保存后将清除候选邮箱登记。'); buttons(scope); };
    input(scope).oninput = () => { if (current(scope) && !scope.busy && !scope.pending && scope.ready) { scope.requireInput = false; buttons(scope); } };
    await read(scope); return current(scope);
  }
  function destroy() { if (!alive) return; closeOwned(); alive = false; host.signal?.removeEventListener('abort', destroy); }
  if (host.signal?.aborted) alive = false; else host.signal?.addEventListener('abort', destroy, { once: true });
  return { open, destroy };
}
