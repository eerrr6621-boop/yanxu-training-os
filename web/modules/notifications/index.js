// Internal S01 logic/API test harness only. Production UI stays in the original app.js shell.
const CHANNEL_LABELS = { IN_APP: '站内通知', EMAIL: '电子邮箱', PUBLIC_ACCOUNT: '微信公众号' };
const CHANNEL_STATES = new Set(['ready', 'unconfigured', 'adapter_not_connected']);
const own = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const token = value => typeof value === 'string' && value.length > 0 && value.length <= 512 && value.trim() === value && !/[\u0000-\u001f\u007f]/.test(value);
const isoDate = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value) && Number.isFinite(Date.parse(value));
const plainText = value => typeof value === 'string' && value.length <= 10000;
const DEMO_TARGETS = {
  'demo-notice-a': { recordId: '4101', taskId: 'demo-task-1', stage: '等待负责人先审' },
  'demo-notice-b': { recordId: '4102', taskId: 'demo-task-2', stage: '退回补充材料' },
  'demo-notice-c': { recordId: '4103', taskId: 'demo-task-3', stage: '团队待承接' },
  'demo-notice-d': { recordId: '4104', taskId: 'demo-task-4', stage: '团队已承接' }
};

function validItem(item) {
  return record(item) && token(item.id) && token(item.eventId) && token(item.type)
    && plainText(item.title) && item.title.trim().length > 0 && plainText(item.body)
    && isoDate(item.createdAt) && (item.readAt === null || isoDate(item.readAt))
    && typeof item.actionable === 'boolean';
}

function validList(data) {
  return record(data) && data.state === 'ready' && Array.isArray(data.items) && data.items.length <= 5000
    && data.items.every(validItem) && new Set(data.items.map(item => item.id)).size === data.items.length
    && Array.isArray(data.channels) && data.channels.length === 3
    && data.channels.every(channel => record(channel) && own(CHANNEL_LABELS, channel.channel) && CHANNEL_STATES.has(channel.status))
    && new Set(data.channels.map(channel => channel.channel)).size === 3;
}

function validTarget(data) {
  if (!record(data) || data.state !== 'ready' || !record(data.target)) return false;
  const { target } = data;
  if (!['M02', 'M03'].includes(target.moduleId) || Object.keys(target).some(key => !['moduleId', 'params'].includes(key))) return false;
  const params = target.params;
  if (!record(params) || Object.keys(params).some(key => !['recordId', 'taskId', 'eventId', 'view'].includes(key))) return false;
  const opaque = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(value);
  return typeof params.recordId === 'string' && /^[1-9]\d*$/.test(params.recordId)
    && Number.isSafeInteger(Number(params.recordId))
    && ['task', 'detail'].includes(params.view) && opaque(params.eventId)
    && (!own(params, 'taskId') || opaque(params.taskId))
    && (target.moduleId !== 'M03' || params.view !== 'task' || own(params, 'taskId'))
    && (target.moduleId !== 'M02' || params.view === 'detail');
}

function hasIdentity(user) {
  return record(user) && ['id', 'uid', 'userId', 'userCode', 'user_code'].some(key => token(user[key]) || (Number.isSafeInteger(user[key]) && user[key] > 0));
}

function demoData() {
  return {
    state: 'ready',
    channels: [
      { channel: 'IN_APP', status: 'ready' },
      { channel: 'EMAIL', status: 'unconfigured' },
      { channel: 'PUBLIC_ACCOUNT', status: 'unconfigured' }
    ],
    items: [
      { id: 'demo-notice-a', eventId: 'demo-event-a', type: 'REVIEW_REQUIRED', title: '申请等待负责人审批', body: '合成事项「家庭预算小实验」进入负责人先审环节。打开事项可查看合成详情。', createdAt: '2026-09-21T09:40:00+08:00', readAt: null, actionable: true },
      { id: 'demo-notice-b', eventId: 'demo-event-b', type: 'RETURNED', title: '申请已退回，请补充材料', body: '合成事项「认识需要与想要」已退回补充。阅读通知不会提交材料或重新发起审批。', createdAt: '2026-09-21T09:15:00+08:00', readAt: null, actionable: true },
      { id: 'demo-notice-c', eventId: 'demo-event-c', type: 'HANDOVER_REQUIRED', title: '团队有待承接事项', body: '合成事项「零用钱计划」已进入团队待承接环节。具体办理以合成事项详情说明为准。', createdAt: '2026-09-21T08:50:00+08:00', readAt: null, actionable: true },
      { id: 'demo-notice-d', eventId: 'demo-event-d', type: 'HANDOVER_ACCEPTED', title: '团队已承接事项', body: '合成事项「小小采购员」已由演示团队承接。这是一条事项动态，不需要在通知页办理。', createdAt: '2026-09-20T16:20:00+08:00', readAt: '2026-09-20T16:40:00+08:00', actionable: false }
    ]
  };
}

/** S01 notification module. Host owns authentication, routing, and stylesheet loading. */
export async function mount(root, context = {}) {
  if (!root || typeof root.replaceChildren !== 'function') throw new TypeError('S01 requires a root element.');
  if (context.signal?.aborted) return () => {};

  const document = root.ownerDocument;
  const controller = new AbortController();
  const demo = context.mode === 'demo';
  let disposed = false;
  let loadVersion = 0;
  const shell = document.createElement('div');
  shell.className = 'yx-notifications';
  const state = { phase: 'loading', items: [], channels: [], category: 'all', unreadOnly: false, pending: new Set(), notice: '', noticeKind: 'info', detail: null };

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = String(text);
    return node;
  }

  function button(text, className, action, disabled = false) {
    const node = el('button', className, text);
    node.type = 'button';
    node.disabled = disabled;
    node.addEventListener('click', () => { if (!disposed) action(); });
    return node;
  }

  function announce(message, kind = 'info') {
    if (disposed) return;
    state.notice = message;
    state.noticeKind = kind;
    render();
    if (typeof context.notify === 'function') {
      try { context.notify(message); } catch { /* A host toast must not prevent local feedback. */ }
    }
  }

  function cleanup() {
    if (disposed) return;
    disposed = true;
    loadVersion += 1;
    controller.abort();
    context.signal?.removeEventListener('abort', cleanup);
    shell.remove();
  }

  context.signal?.addEventListener('abort', cleanup, { once: true });
  root.replaceChildren(shell);

  async function request(path, options = {}) {
    if (disposed || controller.signal.aborted) throw new Error('aborted');
    const response = await context.request(path, { ...options, signal: controller.signal });
    if (disposed) throw new Error('aborted');
    if (response && typeof response.json === 'function') {
      if (response.ok === false) throw new Error('unavailable');
      return await response.json();
    }
    return response;
  }

  async function load() {
    const version = ++loadVersion;
    if (disposed) return;
    state.phase = 'loading';
    state.notice = '';
    render();
    if (demo) {
      const data = demoData();
      state.items = data.items;
      state.channels = data.channels;
      state.phase = 'ready';
      render();
      return;
    }
    if (context.mode !== 'live' || !hasIdentity(context.user) || typeof context.request !== 'function') {
      state.phase = 'unconnected';
      state.notice = context.mode === 'live' && !hasIdentity(context.user) ? '登录后才能查看通知。' : '通知服务暂不可用，请稍后重试。';
      render();
      return;
    }
    try {
      const data = await request('/api/notifications', { method: 'GET' });
      if (disposed || version !== loadVersion) return;
      if (!validList(data)) throw new Error('schema');
      state.items = data.items.map(item => ({ ...item }));
      state.channels = data.channels.map(channel => ({ ...channel }));
      state.phase = 'ready';
    } catch {
      if (disposed || version !== loadVersion) return;
      state.items = [];
      state.channels = [];
      state.phase = 'unconnected';
      state.notice = '通知服务暂不可用，请稍后重试。';
    }
    render();
  }

  async function markRead(id) {
    const item = state.items.find(entry => entry.id === id);
    if (!item || item.readAt !== null || state.pending.has(id) || disposed) return;
    state.pending.add(id);
    render();
    try {
      let updated;
      if (demo) updated = { ...item, readAt: '2026-09-21T10:00:00+08:00' };
      else {
        const data = await request(`/api/notifications/${encodeURIComponent(id)}/read`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
        if (disposed) return;
        if (!record(data) || data.state !== 'ready' || !validItem(data.item) || data.item.id !== id || data.item.eventId !== item.eventId || data.item.readAt === null) throw new Error('schema');
        updated = data.item;
      }
      if (disposed) return;
      state.items = state.items.map(entry => entry.id === id ? { ...updated } : entry);
      state.pending.delete(id);
      announce(demo ? '已在本地演示中标为已读；事项仍需单独办理。' : '已标为已读；阅读状态不代表事项已办理。', 'success');
    } catch {
      if (disposed) return;
      state.pending.delete(id);
      announce('暂时无法标为已读，请稍后重试。本条阅读状态未改变。', 'error');
    }
  }

  async function openItem(id) {
    const item = state.items.find(entry => entry.id === id);
    if (!item || state.pending.has(id) || disposed) return;
    state.pending.add(id);
    render();
    try {
      if (demo) {
        state.detail = { title: item.title, body: item.body, actionable: item.actionable, eventId: item.eventId, ...DEMO_TARGETS[id] };
        state.pending.delete(id);
        render();
        shell.querySelector('.yx-notifications__detail-title')?.focus();
        return;
      }
      const data = await request(`/api/notifications/${encodeURIComponent(id)}/target`, { method: 'GET' });
      if (disposed) return;
      if (!validTarget(data) || data.target.params.eventId !== item.eventId) throw new Error('target');
      if (typeof context.navigate !== 'function') throw new Error('navigation');
      state.pending.delete(id);
      render();
      const params = { recordId: data.target.params.recordId, eventId: data.target.params.eventId, view: data.target.params.view };
      if (own(data.target.params, 'taskId')) params.taskId = data.target.params.taskId;
      await context.navigate(data.target.moduleId, params);
    } catch {
      if (disposed) return;
      state.pending.delete(id);
      announce('暂时无法打开此事项，可能已失效或暂无访问权限。请稍后重试。', 'error');
    }
  }

  function metric(label, value, detail, className) {
    const card = el('div', `yx-notifications__metric ${className || ''}`);
    const top = el('div', 'yx-notifications__metric-top');
    top.append(el('span', '', label), el('span', 'yx-notifications__metric-decoration', '↗'));
    card.append(top, el('strong', 'yx-notifications__metric-value', value), el('p', '', detail));
    return card;
  }

  function renderChannels() {
    const panel = el('section', 'yx-notifications__panel yx-notifications__channels');
    const heading = el('div', 'yx-notifications__section-heading');
    heading.append(el('h2', '', '通知渠道'), el('span', 'yx-notifications__tiny-label', 'CHANNELS'));
    panel.append(heading, el('p', 'yx-notifications__muted', '各渠道独立展示接入状态。'));
    for (const [key, label] of Object.entries(CHANNEL_LABELS)) {
      const channel = state.channels.find(entry => entry.channel === key);
      const status = channel?.status;
      const available = status === 'ready';
      const row = el('div', 'yx-notifications__channel-row');
      const icon = el('span', `yx-notifications__channel-icon ${available ? 'is-ready' : ''}`, key === 'IN_APP' ? '站' : key === 'EMAIL' ? '邮' : '微');
      icon.setAttribute('aria-hidden', 'true');
      const text = el('div', 'yx-notifications__channel-copy');
      text.append(el('strong', '', label), el('span', '', demo && key === 'IN_APP' ? '仅本地合成展示' : status === 'unconfigured' ? '尚未配置' : status === 'adapter_not_connected' ? '尚未连接' : available ? '已连接' : '尚未连接'));
      row.append(icon, text, el('span', `yx-notifications__status ${available ? 'is-ready' : ''}`, demo && key === 'IN_APP' ? '演示' : available ? '可用' : '未接入'));
      panel.append(row);
    }
    panel.append(el('p', 'yx-notifications__channel-note', demo ? '本页不会发送站内、邮箱或公众号消息，演示状态不代表接入完成。' : '本页仅查看通知与渠道状态，不执行邮箱或公众号外发。'));
    return panel;
  }

  function renderFlow() {
    const panel = el('section', 'yx-notifications__flow');
    panel.append(el('span', 'yx-notifications__tiny-label', '演示情境'), el('h2', '', '事项向前走，消息跟得上'));
    const route = el('div', 'yx-notifications__route');
    ['负责人先审', 'BP', '团队'].forEach((label, index) => {
      if (index > 0) route.append(el('span', 'yx-notifications__route-arrow', '→'));
      route.append(el('span', 'yx-notifications__route-step', label));
    });
    panel.append(route, el('p', '', '以上顺序仅作合成展示说明。正式收件人与流转安排以正式业务规则为准。'));
    return panel;
  }

  function renderDetail() {
    const detail = el('section', 'yx-notifications__detail');
    detail.setAttribute('aria-label', '合成事项详情');
    const heading = el('div', 'yx-notifications__section-heading');
    heading.append(el('span', 'yx-notifications__eyebrow', '合成事项详情'), button('返回通知', 'yx-notifications__text-button', () => { state.detail = null; render(); }));
    const title = el('h2', 'yx-notifications__detail-title', state.detail.title);
    title.tabIndex = -1;
    detail.append(heading, title, el('p', 'yx-notifications__detail-body', state.detail.body));
    const facts = el('dl', 'yx-notifications__facts');
    [['合成事项编号', state.detail.recordId], ['合成任务编号', state.detail.taskId], ['合成事件编号', state.detail.eventId], ['数据来源', '本地合成数据'], ['演示状态', state.detail.stage], ['处理说明', '阅读通知不会推进业务流程']].forEach(([label, value]) => {
      const pair = el('div', '');
      pair.append(el('dt', '', label), el('dd', '', value));
      facts.append(pair);
    });
    detail.append(facts, el('p', 'yx-notifications__detail-note', '此处仅展示合成事项详情，不执行审批、办理或消息外发。正式使用时，打开前会确认事项是否仍可访问。'));
    return detail;
  }

  function renderInbox() {
    const panel = el('section', 'yx-notifications__panel yx-notifications__inbox');
    const heading = el('div', 'yx-notifications__section-heading');
    const headingText = el('div', '');
    headingText.append(el('h2', '', '我的通知'), el('p', 'yx-notifications__muted', '未读是阅读状态，待办是业务状态。'));
    heading.append(headingText, el('span', 'yx-notifications__count', `${state.items.length} 条`));
    panel.append(heading);
    const toolbar = el('div', 'yx-notifications__toolbar');
    const tabs = el('div', 'yx-notifications__tabs');
    tabs.setAttribute('aria-label', '通知分类');
    tabs.setAttribute('role', 'group');
    [['all', '全部'], ['tasks', '待办'], ['messages', '消息']].forEach(([key, label]) => {
      const tab = button(label, `yx-notifications__tab ${state.category === key ? 'is-selected' : ''}`, () => { state.category = key; render(); });
      tab.setAttribute('aria-pressed', String(state.category === key));
      tabs.append(tab);
    });
    const unread = button('只看未读', `yx-notifications__unread-toggle ${state.unreadOnly ? 'is-selected' : ''}`, () => { state.unreadOnly = !state.unreadOnly; render(); });
    unread.setAttribute('aria-pressed', String(state.unreadOnly));
    toolbar.append(tabs, unread);
    panel.append(toolbar);
    const filtered = state.items.filter(item => (!state.unreadOnly || item.readAt === null) && (state.category === 'all' || (state.category === 'tasks' ? item.actionable : !item.actionable)));
    if (!filtered.length) {
      const empty = el('div', 'yx-notifications__empty');
      empty.append(el('span', 'yx-notifications__empty-symbol', '✓'), el('h3', '', state.unreadOnly ? '这里没有未读通知' : '这里暂时没有通知'), el('p', '', state.unreadOnly ? '可切换到全部通知，查看已读与待办事项。' : '新的事项与消息会在接入后显示于此。'));
      panel.append(empty);
    }
    const list = el('ul', 'yx-notifications__list');
    for (const item of filtered) {
      const busy = state.pending.has(item.id);
      const row = el('li', `yx-notifications__item ${item.readAt === null ? 'is-unread' : ''}`);
      const icon = el('span', `yx-notifications__item-icon ${item.actionable ? 'is-task' : ''}`, item.actionable ? '待' : '讯');
      icon.setAttribute('aria-hidden', 'true');
      const copy = el('div', 'yx-notifications__item-copy');
      const meta = el('div', 'yx-notifications__item-meta');
      const time = el('time', '', new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Shanghai' }).format(new Date(item.createdAt)));
      time.dateTime = item.createdAt;
      meta.append(el('span', `yx-notifications__kind ${item.actionable ? 'is-task' : ''}`, item.actionable ? '待办' : '消息'), time, el('span', `yx-notifications__read-label ${item.readAt === null ? 'is-unread' : ''}`, item.readAt === null ? '未读' : '已读'));
      copy.append(meta, el('h3', '', item.title), el('p', 'yx-notifications__item-body', item.body));
      const actions = el('div', 'yx-notifications__item-actions');
      const open = button(busy ? '处理中…' : '打开事项 →', 'yx-notifications__open-button', () => { void openItem(item.id); }, busy);
      open.setAttribute('aria-label', `打开事项：${item.title}`);
      actions.append(open);
      if (item.readAt === null) {
        const read = button('标为已读', 'yx-notifications__text-button', () => { void markRead(item.id); }, busy);
        read.setAttribute('aria-label', `标为已读：${item.title}`);
        actions.append(read);
      }
      copy.append(actions);
      row.append(icon, copy);
      list.append(row);
    }
    panel.append(list, el('p', 'yx-notifications__list-footer', demo ? '全部为合成通知 · 刷新后恢复演示状态' : '打开事项时确认当前状态与访问权限'));
    return panel;
  }

  function render() {
    if (disposed) return;
    const page = el('div', 'yx-notifications__page');
    const header = el('header', 'yx-notifications__header');
    const identity = el('div', 'yx-notifications__identity');
    const logo = el('span', 'yx-notifications__logo', '序');
    logo.setAttribute('aria-hidden', 'true');
    identity.append(logo, el('span', 'yx-notifications__brand', '研序'), el('span', 'yx-notifications__brand-divider', '/'), el('span', 'yx-notifications__brand-module', '通知中心'));
    header.append(identity, el('span', `yx-notifications__mode ${demo ? 'is-demo' : ''}`, demo ? '内部逻辑测试' : '内部接口联调'));
    page.append(header);
    const intro = el('div', 'yx-notifications__intro');
    const introText = el('div', '');
    introText.append(el('p', 'yx-notifications__eyebrow', '把关注留给重要的事'), el('h1', '', '每条消息，都有清楚的去处。'), el('p', 'yx-notifications__subtitle', '查看待办、阅读动态，从一条通知回到相关事项。'));
    intro.append(introText, button(demo ? '重置演示' : '刷新通知', 'yx-notifications__refresh', () => { state.detail = null; state.unreadOnly = false; state.category = 'all'; void load(); }, state.phase === 'loading' || state.pending.size > 0));
    page.append(intro);
    const banner = el('div', 'yx-notifications__banner');
    banner.append(el('span', 'yx-notifications__banner-symbol', demo ? 'i' : '✓'), el('p', '', demo ? '仅供内部逻辑测试，全部数据为合成。正式功能沿用原研序工作台，此页不作为正式界面交付。' : '此页仅用于内部接口联调，正式界面沿用原研序工作台。'));
    page.append(banner);
    if (state.notice) {
      const notice = el('div', `yx-notifications__notice is-${state.noticeKind}`, state.notice);
      notice.setAttribute('role', state.noticeKind === 'error' ? 'alert' : 'status');
      page.append(notice);
    }
    if (state.phase !== 'ready') {
      const empty = el('section', 'yx-notifications__panel yx-notifications__connection');
      empty.setAttribute('aria-live', 'polite');
      empty.append(el('span', 'yx-notifications__empty-symbol', state.phase === 'loading' ? '···' : '—'), el('h2', '', state.phase === 'loading' ? '正在读取通知' : '暂时无法查看通知'), el('p', '', state.phase === 'loading' ? '请稍候，正在读取与你相关的通知。' : '登录并连接通知服务后，即可在这里查看与你相关的事项。'));
      page.append(empty);
    } else {
      const metrics = el('section', 'yx-notifications__metrics');
      metrics.setAttribute('aria-label', '通知概览');
      metrics.append(metric('未读通知', state.items.filter(item => item.readAt === null).length, '阅读后可单条标记已读', 'is-primary'), metric('待办事项', state.items.filter(item => item.actionable).length, '已读也可能仍然需要办理'), metric('事项消息', state.items.filter(item => !item.actionable).length, '同步与你相关的事项动态'));
      page.append(metrics);
      if (state.detail) page.append(renderDetail());
      const layout = el('div', 'yx-notifications__layout');
      const aside = el('aside', 'yx-notifications__aside');
      aside.append(renderChannels());
      if (demo) aside.append(renderFlow());
      layout.append(renderInbox(), aside);
      page.append(layout);
    }
    page.append(el('footer', 'yx-notifications__footer', demo ? '研序 · S01 通知体验演示 / 全部数据均为合成' : '研序 · S01 通知中心'));
    shell.replaceChildren(page);
  }

  void load();
  return cleanup;
}
