import { createDemoStore, demoPeople } from './demo.js';

const statuses = { LEADER_PENDING: '待负责人审批', BP_PENDING: '待 BP 审批', RETURNED: '已退回', WITHDRAWN: '已撤回', READY_FOR_TEAM: '可交培训团队' };
const actionNames = { SUBMIT: '提交申请', APPROVE: '同意', RETURN: '退回', RESUBMIT: '重新提交', WITHDRAW: '撤回', REVISE: '更新材料' };
const supportedActions = ['APPROVE', 'RETURN', 'RESUBMIT', 'WITHDRAW'];
const pending = task => ['LEADER_PENDING', 'BP_PENDING'].includes(task.status);
const value = item => item === null || item === undefined || item === '' ? '未提供' : String(item);
const requestId = () => globalThis.crypto?.randomUUID?.() || `m03-${Date.now()}-${Math.random().toString(36).slice(2)}`;
const validTask = task => task && (typeof task.id === 'string' || Number.isSafeInteger(task.id)) && task.id !== '' && Object.hasOwn(statuses, task.status) && ['LEADER', 'BP', 'NONE'].includes(task.stage) && Number.isSafeInteger(task.version) && task.version >= 1;

/** INTERNAL TEST FIXTURE ONLY. Formal UI reuses the original app.js pages; see README.md. */
export async function mount(root, context = {}) {
  if (!root?.ownerDocument) throw new TypeError('审批模块需要有效的挂载节点。');
  if (context.signal?.aborted) return () => {};
  const document = root.ownerDocument;
  const controller = new AbortController();
  const demo = context.mode === 'demo';
  const connected = context.mode === 'live' && typeof context.request === 'function';
  const store = demo ? createDemoStore() : null;
  let alive = true;
  let readSequence = 0;
  const state = { tasks: [], selected: null, loading: false, detailLoading: false, saving: false, stale: false, error: '', notice: '', search: '', filter: 'pending', actorId: 'DEMO-LEADER', confirmation: null, comment: '', revision: '' };
  const active = () => alive && !controller.signal.aborted;
  function el(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = String(text);
    return element;
  }
  function button(label, className, callback, disabled = false) {
    const element = el('button', className, label);
    element.type = 'button';
    element.disabled = disabled;
    element.addEventListener('click', () => { if (active() && !element.disabled) callback(); });
    return element;
  }
  const shell = el('section', 'yx-approvals');
  shell.setAttribute('aria-label', '审批与手机待办');
  const stylesheet = el('link');
  stylesheet.rel = 'stylesheet';
  stylesheet.href = new URL('./style.css', import.meta.url).href;
  root.replaceChildren(stylesheet, shell);
  function cleanup() {
    if (!alive) return;
    alive = false;
    readSequence += 1;
    controller.abort();
    context.signal?.removeEventListener('abort', cleanup);
    if (shell.parentNode === root) shell.remove();
    if (stylesheet.parentNode === root) stylesheet.remove();
  }
  context.signal?.addEventListener('abort', cleanup, { once: true });

  async function request(path, options = {}) {
    const response = await context.request(path, { ...options, signal: controller.signal });
    if (!active()) throw new Error('已离开审批页面');
    if (response && typeof response.json === 'function') {
      if (response.ok === false) { const error = new Error(`接口请求失败（${response.status}）`); error.status = response.status; throw error; }
      return response.json();
    }
    return response;
  }
  function unwrapTask(result) {
    const task = result?.task ?? result;
    if (!validTask(task)) throw new Error('未接入：审批接口返回格式不完整。');
    return task;
  }
  function handleReadError(error) {
    const status = error?.status ?? error?.statusCode;
    state.error = [404, 501].includes(status) ? '未接入：审批接口尚未提供。' : (String(error?.message || '').startsWith('未接入') ? error.message : '暂时无法加载审批，请检查连接后重试。');
  }
  async function loadList() {
    if (!active() || state.saving) return;
    const sequence = ++readSequence;
    state.loading = true; state.error = ''; state.notice = ''; state.confirmation = null;
    render();
    try {
      const result = demo ? store.list(state.actorId) : await request('/api/approvals/tasks');
      const tasks = Array.isArray(result) ? result : result?.tasks;
      if (!Array.isArray(tasks) || tasks.some(task => !validTask(task))) throw new Error('未接入：审批列表格式不完整。');
      if (!active() || sequence !== readSequence) return;
      state.tasks = tasks; state.selected = null; state.stale = false;
    } catch (error) { if (active() && sequence === readSequence) { state.tasks = []; state.selected = null; handleReadError(error); } }
    finally { if (active() && sequence === readSequence) { state.loading = false; render(); } }
  }
  async function openTask(id) {
    if (!active() || state.saving) return;
    const sequence = ++readSequence;
    state.detailLoading = true; state.selected = null; state.error = ''; state.notice = ''; state.confirmation = null;
    render();
    try {
      const task = demo ? store.get(id, state.actorId) : unwrapTask(await request(`/api/approvals/tasks/${encodeURIComponent(id)}`));
      if (!active() || sequence !== readSequence) return;
      if (String(task.id) !== String(id)) throw new Error('未接入：审批详情与请求单据不一致。');
      state.selected = task; state.stale = false;
      state.tasks = state.tasks.map(item => String(item.id) === String(task.id) ? task : item);
    } catch (error) { if (active() && sequence === readSequence) handleReadError(error); }
    finally { if (active() && sequence === readSequence) { state.detailLoading = false; state.loading = false; render(); } }
  }
  async function submitAction() {
    if (!active() || state.saving || state.stale || !state.selected || !state.confirmation) return;
    const task = state.selected;
    const action = state.confirmation;
    if (!Array.isArray(task.actions) || !task.actions.some(item => item?.action === action && item.enabled === true)) return;
    const comment = state.comment.trim();
    if (action === 'RETURN' && !comment) { state.error = '请填写退回原因，方便发起人补充材料。'; render(); return; }
    const payload = { action, expectedVersion: task.version, expectedStage: task.stage, requestId: requestId(), comment };
    if (demo && action === 'RESUBMIT') {
      const revision = Number(state.revision);
      if (!Number.isSafeInteger(revision) || revision <= Number(task.dataRevision ?? 0)) { state.error = '请输入已保存且大于当前版本的材料版本号。'; render(); return; }
      payload.dataRevision = revision;
    }
    state.saving = true; state.error = ''; state.notice = '';
    render();
    try {
      const result = demo ? store.act(task.id, payload, state.actorId) : await request(`/api/approvals/tasks/${encodeURIComponent(task.id)}/actions`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
      if (!active()) return;
      const returnedTask = result?.task ?? result;
      const updated = demo ? result : validTask(returnedTask) ? returnedTask : unwrapTask(await request(`/api/approvals/tasks/${encodeURIComponent(task.id)}`));
      if (!active()) return;
      if (String(updated.id) !== String(task.id)) throw new Error('返回单据不一致');
      state.selected = updated;
      state.tasks = state.tasks.map(item => String(item.id) === String(updated.id) ? updated : item);
      state.confirmation = null; state.comment = ''; state.revision = ''; state.stale = false;
      state.notice = `${actionNames[action]}已完成${demo ? '（合成演示）' : ''}。`;
    } catch (error) {
      if (active()) { state.stale = true; state.confirmation = null; state.error = demo ? error.message : '提交结果未确认。请先刷新单据状态，再继续办理。'; }
    } finally { if (active()) { state.saving = false; render(); } }
  }
  function badge(task) { return el('span', `yx-approvals__badge yx-approvals__badge--${task.status.toLowerCase()}`, statuses[task.status]); }
  function panel(title, copy) { const node = el('div', 'yx-approvals__empty'); node.append(el('div', 'yx-approvals__empty-symbol', '↗'), el('h3', '', title), el('p', '', copy)); return node; }
  function currentId() { return demo ? state.actorId : context.user?.personCode ?? context.user?.person_code; }
  function visibleTasks() {
    return state.tasks.filter(task => (state.filter !== 'pending' || pending(task)) && (state.filter !== 'mine' || (currentId() !== undefined && String(task.participants?.submitterId) === String(currentId()))) && `${task.title || ''} ${task.businessId || ''}`.toLocaleLowerCase().includes(state.search.trim().toLocaleLowerCase()));
  }
  function renderList() {
    const list = el('aside', 'yx-approvals__list');
    const top = el('div', 'yx-approvals__list-top');
    top.append(el('h2', '', '工作清单'), el('span', 'yx-approvals__count', state.tasks.filter(pending).length));
    const filters = el('div', 'yx-approvals__filters');
    filters.setAttribute('aria-label', '单据范围');
    [['pending', '待审批'], ['mine', '我发起'], ['all', '全部']].forEach(([filter, label]) => {
      const control = button(label, state.filter === filter ? 'is-active' : '', () => { state.filter = filter; render(); }, state.saving);
      control.setAttribute('aria-pressed', String(state.filter === filter)); filters.append(control);
    });
    const search = el('input', 'yx-approvals__search'); search.type = 'search'; search.placeholder = '搜索主题或业务单号'; search.value = state.search; search.disabled = state.saving;
    search.setAttribute('aria-label', '搜索主题或业务单号');
    search.addEventListener('input', () => { if (!active()) return; state.search = search.value; renderRows(); });
    const rows = el('div', 'yx-approvals__rows');
    function renderRows() {
      if (!active()) return;
      rows.replaceChildren();
      if (state.loading) { rows.append(panel('正在获取待办', '审批状态以最新记录为准。')); return; }
      const tasks = visibleTasks();
      if (!tasks.length) { rows.append(panel('这里暂时没有单据', state.search ? '试试其他主题或业务单号。' : state.filter === 'mine' && !demo && currentId() === undefined ? '当前身份信息未接入，暂不能筛选我发起的单据。' : '更换查看范围，或刷新获取最新状态。')); return; }
      tasks.forEach(task => {
        const card = button('', `yx-approvals__task${String(state.selected?.id) === String(task.id) ? ' is-selected' : ''}`, () => openTask(task.id), state.saving);
        card.setAttribute('aria-label', `办理 ${task.title || task.businessId || task.id}`);
        const meta = el('div', 'yx-approvals__task-meta'); meta.append(el('span', '', value(task.businessId)), badge(task));
        const bottom = el('div', 'yx-approvals__task-bottom'); bottom.append(el('span', '', `材料 v${value(task.dataRevision)} · 状态 v${task.version}`), el('span', 'yx-approvals__arrow', '↗'));
        card.append(meta, el('h3', '', task.title || '直接承接申请'), bottom); rows.append(card);
      });
    }
    renderRows(); list.append(top, filters, search, rows); return list;
  }
  function renderFlow(task) {
    const flow = el('ol', 'yx-approvals__flow');
    const progress = task.status === 'READY_FOR_TEAM' ? 2 : task.status === 'BP_PENDING' ? 1 : 0;
    ['负责人审批', 'BP 审批', '培训团队承接'].forEach((label, index) => {
      const current = pending(task) && (task.stage === 'LEADER' ? index === 0 : index === 1);
      const item = el('li', `${index < progress ? 'is-done' : ''}${current ? ' is-current' : ''}`);
      item.append(el('span', 'yx-approvals__flow-dot', index < progress ? '✓' : String(index + 1)), el('span', '', label));
      if (current) item.setAttribute('aria-current', 'step'); flow.append(item);
    });
    return flow;
  }
  function renderDetail() {
    const detail = el('section', `yx-approvals__detail${state.selected || state.detailLoading ? ' is-open' : ''}`);
    if (state.detailLoading) { detail.append(panel('正在读取详情', '正在核对当前节点与可执行操作。')); return detail; }
    const task = state.selected;
    if (!task) { detail.append(panel('每一步审批，都有据可循', '选择一张单据，查看材料版本、审批进度与办理记录。')); return detail; }
    detail.append(button('← 返回清单', 'yx-approvals__back', () => { state.selected = null; state.confirmation = null; state.error = ''; render(); }, state.saving));
    const title = el('div', 'yx-approvals__detail-title'); title.append(el('p', 'yx-approvals__eyebrow', `直接承接 / ${value(task.businessId)}`), badge(task), el('h2', '', task.title || '直接承接申请'));
    detail.append(title, renderFlow(task));
    if (task.status === 'READY_FOR_TEAM') detail.append(el('p', 'yx-approvals__ready', '负责人和 BP 已依次通过，可由培训团队接续承接。'));
    if (task.status === 'RETURNED') detail.append(el('p', 'yx-approvals__policy', '单据已退回。请按办理记录补充材料；重提路径以已配置规则为准。'));
    const fields = el('dl', 'yx-approvals__facts');
    [['发起人 ID', task.participants?.submitterId], ['负责人 ID', task.participants?.leaderId], ['BP ID', task.participants?.bpId], ['当前办理人 ID', task.assigneeId], ['材料版本', task.dataRevision], ['状态版本', task.version]].forEach(([name, field]) => { const row = el('div'); row.append(el('dt', '', name), el('dd', '', value(field))); fields.append(row); });
    detail.append(fields);
    const actions = el('section', 'yx-approvals__actions'); actions.append(el('h3', '', '办理此单'));
    const available = Array.isArray(task.actions) ? task.actions.filter(item => item && supportedActions.includes(item.action)) : [];
    if (!available.length) actions.append(el('p', 'yx-approvals__muted', '当前无可办理操作；权限或异常流程规则待配置时，将由审批服务提示。'));
    if (state.stale) actions.append(button('刷新单据状态', 'yx-approvals__primary', () => openTask(task.id), state.saving));
    const actionRow = el('div', 'yx-approvals__action-row');
    available.forEach(item => {
      const actionBox = el('div', 'yx-approvals__action-item');
      const label = item.label || actionNames[item.action];
      const control = button(label, item.action === 'APPROVE' || item.action === 'RESUBMIT' ? 'yx-approvals__primary' : 'yx-approvals__secondary', () => { state.confirmation = item.action; state.comment = ''; state.revision = demo && item.action === 'RESUBMIT' ? String(Number(task.dataRevision || 0) + 1) : ''; state.error = ''; render(); }, state.saving || state.stale || item.enabled !== true);
      actionBox.append(control);
      if (item.enabled !== true) actionBox.append(el('small', '', item.reason || '待配置：服务尚未开放此操作。'));
      actionRow.append(actionBox);
    });
    actions.append(actionRow);
    if (state.confirmation) {
      const action = state.confirmation;
      const form = el('div', 'yx-approvals__confirmation');
      form.append(el('h4', '', `确认${actionNames[action]}`), el('p', 'yx-approvals__muted', `当前状态 v${task.version}，提交时将重新校验节点与权限。`));
      const commentLabel = el('label', '', action === 'RETURN' ? '退回原因（必填）' : '办理意见（选填）');
      const comment = el('textarea'); comment.value = state.comment; comment.rows = 3; comment.maxLength = 2000; comment.disabled = state.saving; comment.placeholder = action === 'RETURN' ? '写清需要补充或调整的内容' : '补充本次办理意见';
      comment.addEventListener('input', () => { if (active()) state.comment = comment.value; }); commentLabel.append(comment); form.append(commentLabel);
      if (demo && action === 'RESUBMIT') {
        const revisionLabel = el('label', '', '已保存的材料版本号'); const revision = el('input'); revision.type = 'number'; revision.min = String(Number(task.dataRevision || 0) + 1); revision.step = '1'; revision.value = state.revision; revision.disabled = state.saving; revision.addEventListener('input', () => { if (active()) state.revision = revision.value; }); revisionLabel.append(revision);
        form.append(revisionLabel, el('p', 'yx-approvals__muted', '合成演示：用递增版本模拟材料补充，重新从负责人节点开始。'));
      }
      if (!demo && action === 'RESUBMIT') form.append(el('p', 'yx-approvals__muted', '将以业务单据中已保存的内容重提；材料版本及重提节点由审批服务核验。'));
      const buttons = el('div', 'yx-approvals__confirm-buttons'); buttons.append(button(state.saving ? '正在提交…' : `确认${actionNames[action]}`, 'yx-approvals__primary', submitAction, state.saving), button('取消', 'yx-approvals__secondary', () => { state.confirmation = null; state.error = ''; render(); }, state.saving)); form.append(buttons); actions.append(form);
    }
    detail.append(actions);
    const timeline = el('section', 'yx-approvals__history'); timeline.append(el('h3', '', '办理记录'));
    const entries = Array.isArray(task.history) ? task.history : [];
    if (!entries.length) timeline.append(el('p', 'yx-approvals__muted', '暂无办理记录。'));
    const history = el('ol');
    [...entries].reverse().forEach(entry => {
      if (!entry || typeof entry !== 'object') return;
      const item = el('li'); const heading = el('div', 'yx-approvals__history-heading'); heading.append(el('strong', '', actionNames[entry.action] || value(entry.action)), el('span', '', `v${value(entry.version)}`));
      const at = new Date(entry.at); const time = Number.isNaN(at.getTime()) ? value(entry.at) : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short', timeZone: 'Asia/Shanghai' }).format(at);
      item.append(heading, el('p', 'yx-approvals__muted', `${value(entry.actorId)} · ${time}`), el('p', '', `${statuses[entry.from] || '开始'} → ${statuses[entry.to] || value(entry.to)}`));
      if (entry.onBehalfOf !== undefined && entry.onBehalfOf !== null && entry.onBehalfOf !== '') item.append(el('p', 'yx-approvals__muted', `代 ${value(entry.onBehalfOf)} 办理（上方为实际办理人 ID）`));
      if (entry.comment) item.append(el('p', 'yx-approvals__comment', entry.comment)); history.append(item);
    });
    timeline.append(history); detail.append(timeline); return detail;
  }
  function render() {
    if (!active()) return;
    shell.replaceChildren();
    shell.setAttribute('aria-busy', String(state.loading || state.detailLoading || state.saving));
    const header = el('header', 'yx-approvals__header'); const heading = el('div'); heading.append(el('p', 'yx-approvals__eyebrow', '内部测试 / 审批逻辑'), el('h1', '', '审批待办'), el('p', 'yx-approvals__subtitle', '内部流程验证；正式办理沿用原系统界面。'));
    const tools = el('div', 'yx-approvals__header-tools'); tools.append(el('span', `yx-approvals__mode${demo ? ' is-demo' : ''}`, demo ? '合成演示 · 本地运行' : connected ? '接口契约测试' : '未接入'));
    if (demo || connected) tools.append(button('刷新', 'yx-approvals__refresh', () => state.selected ? openTask(state.selected.id) : loadList(), state.saving || state.loading || state.detailLoading));
    header.append(heading, tools); shell.append(header);
    if (demo) {
      const banner = el('div', 'yx-approvals__demo-banner'); const note = el('div'); note.append(el('strong', '', '正在查看合成流程'), el('p', '', '所有人员和单据均为虚构。演示约定：退回发起人、退回或撤回后重提从头、审批完成前可撤回；正式办理需完成人员配置与接口接入。'));
      const roleLabel = el('label', '', '演示身份'); const select = el('select'); select.setAttribute('aria-label', '切换合成演示身份'); select.disabled = state.saving;
      demoPeople.forEach(person => { const option = el('option', '', `${person.label} / ${person.id}`); option.value = person.id; select.append(option); }); select.value = state.actorId;
      select.addEventListener('change', () => { if (!active() || state.saving) return; state.actorId = select.value; state.selected = null; loadList(); }); roleLabel.append(select); banner.append(note, roleLabel); shell.append(banner);
    }
    if (!demo && !connected) { shell.append(panel('未接入', '审批模块尚未接入正式接口。接入后将显示当前身份获准查看的单据及操作。')); return; }
    if (state.error) { const alert = el('div', 'yx-approvals__alert', state.error); alert.setAttribute('role', 'alert'); if (!state.selected) alert.append(button('重试', 'yx-approvals__secondary', loadList, state.loading || state.detailLoading)); shell.append(alert); }
    if (state.notice) { const notice = el('div', 'yx-approvals__notice', state.notice); notice.setAttribute('role', 'status'); shell.append(notice); }
    const workspace = el('div', `yx-approvals__workspace${state.selected || state.detailLoading ? ' has-detail' : ''}`); workspace.append(renderList(), renderDetail()); shell.append(workspace);
    shell.append(el('p', 'yx-approvals__footer', '审批顺序固定为负责人 → BP → 培训团队。退回、重提、撤回等例外规则以正式配置为准。'));
  }
  render();
  if (demo || connected) void loadList();
  return cleanup;
}
