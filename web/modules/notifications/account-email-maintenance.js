// Original users-page modal. Email ownership is established by the separate login verification flow.
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const positiveId = value => Number.isSafeInteger(value) && value > 0;
const statusOf = error => Number(error?.status || error?.code);
const validState = value => value && ['MISSING', 'PENDING_VERIFICATION'].includes(value.status) &&
  (value.status === 'MISSING' ? value.email === null : typeof value.email === 'string' && value.email.length > 0);
function validated(value, userId, selfId, reading = false) {
  if (!value || value.user_id !== userId || typeof value.username !== 'string' || !value.username ||
      !(value.name === null || typeof value.name === 'string') || !['IMPORTED', 'EXISTING'].includes(value.account_kind) ||
      !Number.isSafeInteger(value.revision) || value.revision < 0 || !validState(value) || (value.revision === 0 && value.email !== null) || value.can_save !== true ||
      typeof value.duplicate_email !== 'boolean' || typeof value.reauthentication_required !== 'boolean' ||
      (value.reauthentication_required && (reading || userId !== selfId)) || !Array.isArray(value.history) || value.history.length !== value.revision ||
      !value.history.every((row, index) => row?.revision === index + 1 && validState(row) && positiveId(row.actor_user_id) && typeof row.recorded_at === 'string') ||
      (value.revision && (value.history.at(-1).email !== value.email || value.history.at(-1).status !== value.status)))
    throw Error('邮箱资料暂时无法核实，请重新读取。');
  return value;
}
const localTime = value => { const date = new Date(value); return Number.isFinite(date.getTime()) ? date.toLocaleString('zh-CN', { hour12: false }) : '时间待核实'; };

export function createAccountEmailMaintenance(host) {
  for (const key of ['api', 'getUser', 'getModal', 'openModal', 'closeModal', 'renderForm', 'collectForm', 'renderTable', 'toast', 'reauthenticate'])
    if (typeof host?.[key] !== 'function') throw TypeError('Missing email maintenance host: ' + key);
  const identity = host.getUser();
  let alive = true, owned = null;
  const active = () => alive && !host.signal?.aborted && (host.isCurrent?.() ?? true) && host.getUser() === identity && identity?.role === 'admin';
  const current = scope => active() && owned === scope && !scope.closed && scope.mask?.isConnected !== false && host.getModal() === scope.mask;
  const ensureCurrent = scope => { if (current(scope)) return true; if (owned === scope && !active()) closeOwned(); return false; };
  const node = (scope, key) => scope.mask.querySelector('[data-email-maintenance-' + key + ']');
  const field = (scope, key) => scope.mask.querySelector('[data-k="' + key + '"]');
  const notice = (scope, message) => { if (current(scope)) node(scope, 'notice').textContent = message; };
  function clear(scope) {
    scope.ready = false; scope.snapshot = null;
    if (!scope.mask) return;
    for (const key of ['email', 'confirmed_username']) field(scope, key).value = '';
    node(scope, 'purpose').checked = false;
    for (const key of ['target', 'state', 'duplicate', 'history']) node(scope, key).textContent = '';
  }
  function resetConfirmation(scope) { field(scope, 'confirmed_username').value = ''; node(scope, 'purpose').checked = false; }
  function buttons(scope) {
    if (!current(scope)) return;
    const blocked = scope.busy || !scope.ready || Boolean(scope.pending);
    for (const key of ['email', 'confirmed_username']) field(scope, key).disabled = blocked;
    node(scope, 'purpose').disabled = blocked;
    node(scope, 'save').disabled = blocked || !node(scope, 'purpose').checked || field(scope, 'confirmed_username').value !== scope.snapshot?.username;
    node(scope, 'clear').disabled = blocked;
    node(scope, 'read').disabled = scope.busy;
    node(scope, 'retry').disabled = scope.busy || !scope.pending;
    node(scope, 'retry').style.display = scope.pending ? '' : 'none';
    scope.mask.dataset.locked = scope.pending ? 'true' : 'false';
  }
  function render(scope, result, refill) {
    scope.snapshot = result; scope.ready = true;
    node(scope, 'target').innerHTML = `<span style="overflow-wrap:anywhere"><b>${escape(result.name || '账号')}</b><br>系统账号：${escape(result.username)} · 内部账号 #${scope.userId}</span>`;
    if (!scope.pending) { field(scope, 'email').value = refill ? result.email || '' : ''; resetConfirmation(scope); }
    node(scope, 'state').textContent = (result.status === 'MISSING' ? '邮箱状态：未登记' : '邮箱状态：待本人验证') + ' · 当前修订 ' + result.revision;
    node(scope, 'duplicate').textContent = result.duplicate_email ? '该邮箱与其他账号重复，请核对并更换。' : '';
    node(scope, 'history').innerHTML = '<div class="section-title"><span>修改记录</span></div>' + (result.history.length ? host.renderTable([
      { k: 'revision', l: '版本' }, { k: 'email', l: '核验邮箱', render: row => `<span style="overflow-wrap:anywhere;word-break:break-word">${escape(row.email ?? '已清除')}</span>` },
      { k: 'status', l: '登记状态', render: row => row.status === 'MISSING' ? '未登记' : '待本人验证' },
      { k: 'actor_user_id', l: '操作账号', render: row => '账号 #' + row.actor_user_id },
      { k: 'recorded_at', l: '修改时间', render: row => escape(localTime(row.recorded_at)) },
    ], result.history.map(row => ({ ...row, id: row.revision })), []) : '<p class="modal-intro">尚无修改记录。</p>');
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
    if (!ensureCurrent(scope) || scope.busy) return;
    const pending = scope.pending, ticket = ++scope.readTicket;
    scope.readController?.abort(); scope.readController = new AbortController(); scope.busy = true;
    if (!pending) clear(scope);
    buttons(scope); notice(scope, '正在读取当前账号与邮箱资料…');
    try {
      const response = await host.api('/account-email-maintenance?user_id=' + scope.userId, { quiet: true, signal: scope.readController.signal });
      if (!ensureCurrent(scope) || ticket !== scope.readTicket) return;
      render(scope, validated(response, scope.userId, Number(identity.uid), true), refill);
      notice(scope, pending ? '已读取当前记录；上次保存是否完成仍需点击“重试上次保存”核实，请勿另建请求。' : refill ? '' : '邮箱重复或资料已变化，旧填写已清除。请核对最新记录，重新填写邮箱并确认账号和用途。');
    } catch (error) {
      if (!ensureCurrent(scope) || ticket !== scope.readTicket || error?.name === 'AbortError') return;
      if ([401, 403].includes(statusOf(error))) { closeOwned(); return; }
      clear(scope);
      notice(scope, pending ? '当前记录暂时无法读取，上次保存结果仍未确认。请重试上次保存。' : '邮箱资料暂时无法读取，请重新读取；如仍失败，请核对账号状态。');
    } finally { if (current(scope) && ticket === scope.readTicket) { scope.busy = false; buttons(scope); } }
  }
  async function save(scope, retry = false) {
    if (!ensureCurrent(scope) || scope.busy) return;
    if (!retry) {
      if (!scope.ready || scope.pending || !node(scope, 'purpose').checked || field(scope, 'confirmed_username').value !== scope.snapshot.username) return;
      const values = host.collectForm(scope.mask, scope.fields); if (!values) return;
      // The exact typed confirmation is retained; generic form collection trims other values.
      scope.pending = Object.freeze({ user_id: scope.userId, expected_revision: scope.snapshot.revision,
        request_id: host.newRequestId ? host.newRequestId() : globalThis.crypto.randomUUID(), email: values.email,
        confirmed_username: field(scope, 'confirmed_username').value, purpose: 'LOGIN_VERIFICATION' });
    }
    if (!scope.pending) return;
    const request = scope.pending; scope.busy = true; buttons(scope); notice(scope, '正在保存核验邮箱…');
    let conflict = false;
    try {
      const response = await host.api('/account-email-maintenance', { body: { ...request }, quiet: true, signal: scope.controller.signal });
      if (!ensureCurrent(scope)) return;
      const result = validated(response, scope.userId, Number(identity.uid));
      if (result.revision < request.expected_revision) throw Error('保存回执尚未核实');
      scope.pending = null;
      if (result.reauthentication_required) { closeOwned(); host.reauthenticate(); return; }
      render(scope, result, true);
      notice(scope, result.status === 'MISSING' ? '已清除邮箱，修改记录已保留。' : '已保存，待本人登录时验证。');
    } catch (error) {
      if (!ensureCurrent(scope) || error?.name === 'AbortError') return;
      const status = statusOf(error);
      if ([401, 403].includes(status)) { scope.pending = null; closeOwned(); return; }
      if (status === 409) { scope.pending = null; clear(scope); conflict = true; }
      else if (status === 404) { scope.pending = null; clear(scope); notice(scope, '当前账号无法维护邮箱，请关闭后核对账号状态。'); }
      else if (status >= 400 && status < 500) { scope.pending = null; resetConfirmation(scope); notice(scope, '邮箱未保存，请核对地址格式，再确认账号和用途。'); }
      else notice(scope, '本次保存结果尚未确认。可重新读取记录，或点击“重试上次保存”核实同一次请求。');
    } finally { if (current(scope)) { scope.busy = false; buttons(scope); if (conflict) await read(scope, false); } }
  }
  async function open(userId) {
    if (!active() || !positiveId(userId)) return false;
    if (host.getModal()) { host.toast('请先完成或关闭当前窗口，再维护邮箱。', true); return false; }
    const scope = { userId, mask: null, controller: new AbortController(), readController: null, readTicket: 0, closed: false, busy: false, ready: false, pending: null, snapshot: null,
      fields: [{ k: 'email', label: '本人核验邮箱', type: 'email', span2: true, autocomplete: 'off', hint: '留空并保存可清除邮箱，修改记录会保留。' },
        { k: 'confirmed_username', label: '输入上方系统账号以确认', required: true, span2: true, autocomplete: 'off', hint: '请核对当前姓名和系统账号，不能只按姓名判断。' }] };
    scope.mask = host.openModal('维护核验邮箱', '<div class="inline-note" data-email-maintenance-target></div><p class="modal-intro">用于本人登录时接收验证码。保存邮箱后仍需本人验证；修改或清除会使该账号原登录失效。</p><p class="modal-intro" data-email-maintenance-notice role="status" aria-live="polite"></p><p class="modal-intro" data-email-maintenance-state></p><p class="modal-intro" data-email-maintenance-duplicate role="status"></p>' + host.renderForm(scope.fields) + '<p class="modal-intro"><label><input type="checkbox" data-email-maintenance-purpose> 我已核对账号，确认此邮箱设置用于登录身份核验。</label></p><div class="toolbar" style="flex-wrap:wrap"><button type="button" class="btn" data-email-maintenance-save disabled>保存核验邮箱</button><button type="button" class="btn gray" data-email-maintenance-clear disabled>清空填写</button><button type="button" class="btn gray" data-email-maintenance-read>重新读取</button><button type="button" class="btn gray" data-email-maintenance-retry disabled>重试上次保存</button></div><div data-email-maintenance-history></div>', {
      noFoot: true, onClose: () => dispose(scope), beforeClose: () => { if (!scope.pending) return true; notice(scope, '本次保存结果尚未确认，请先核实或重试上次保存。'); return false; },
    });
    if (!scope.mask) return false;
    owned = scope;
    node(scope, 'save').onclick = () => save(scope);
    node(scope, 'retry').onclick = () => save(scope, true);
    node(scope, 'read').onclick = () => read(scope);
    node(scope, 'clear').onclick = () => { if (!current(scope) || scope.busy || scope.pending || !scope.ready) return; field(scope, 'email').value = ''; resetConfirmation(scope); notice(scope, '填写已清空。确认账号和用途后保存，将清除核验邮箱。'); buttons(scope); };
    for (const key of ['email', 'confirmed_username']) field(scope, key).oninput = () => { if (current(scope) && !scope.busy && !scope.pending) { node(scope, 'purpose').checked = false; buttons(scope); } };
    node(scope, 'purpose').onchange = () => buttons(scope);
    await read(scope); return current(scope);
  }
  function destroy() { if (!alive) return; closeOwned(); alive = false; host.signal?.removeEventListener('abort', destroy); }
  if (host.signal?.aborted) alive = false; else host.signal?.addEventListener('abort', destroy, { once: true });
  return { open, destroy };
}
