/* 培训全流程管理系统 - 前端 SPA */
(function () {
  'use strict';

  document.documentElement.dataset.visual = 'ledger';

  // ============ 基础工具 ============
  const $ = (s, el) => (el || document).querySelector(s);
  const $$ = (s, el) => Array.from((el || document).querySelectorAll(s));
  const esc = (v) => String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const money = (n) => (Number(n || 0)).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const compactMoney = (n) => {
    const value = Number(n || 0);
    if (Math.abs(value) >= 10000) return `${(value / 10000).toLocaleString('zh-CN', { maximumFractionDigits: 2 })} 万`;
    return value.toLocaleString('zh-CN', { maximumFractionDigits: 0 });
  };
  const num = (n) => (Number(n || 0)).toLocaleString('zh-CN');
  const shiftIsoDate = (date, days) => {
    if (!date) return '';
    const parts = String(date).split('-').map(Number);
    if (parts.length !== 3 || parts.some((part) => !Number.isFinite(part))) return '';
    const value = new Date(Date.UTC(parts[0], parts[1] - 1, parts[2] + days));
    return value.toISOString().slice(0, 10);
  };
  const icon = (name, cls = '') => `<i data-lucide="${esc(name)}" class="${esc(cls)}" aria-hidden="true"></i>`;
  const brandSymbol = (cls = '') => `<img class="brand-symbol ${esc(cls)}" src="/assets/yx-mark-v10.png?v=20260813v10r3" alt="" aria-hidden="true">`;
  const mergeContextLabels = (values) => {
    const unique = [...new Set(values.filter(Boolean))];
    if (unique.length < 2) return unique[0] || '';
    const parts = unique.map((value) => String(value).split(' · '));
    if (parts.every((part) => part.length > 1 && part[0] === parts[0][0])) {
      return `${parts[0][0]} · ${[...new Set(parts.map((part) => part.slice(1).join(' · ')))].join(' + ')}`;
    }
    return unique.join('；');
  };

  function refreshIcons(root = document) {
    if (!window.lucide || !root) return;
    try { window.lucide.createIcons({ root, attrs: { 'stroke-width': 1.75 } }); } catch (e) { /* 图标失败不影响业务 */ }
  }

  const debounce = (fn, wait = 120) => {
    let timer;
    const wrapped = (...args) => { clearTimeout(timer); timer = setTimeout(() => fn(...args), wait); };
    wrapped.cancel = () => { clearTimeout(timer); timer = null; };
    return wrapped;
  };

  const prefersReducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const countTag = (value, kind = 'int') => {
    const v = Number(value);
    if (!Number.isFinite(v)) return esc(value);
    const shown = kind === 'money' ? `¥ ${money(v)}` : kind === 'compact' ? `¥ ${compactMoney(v)}` : kind === 'pct' ? v.toFixed(1) : num(v);
    return `<span data-countup="${v}" data-countup-kind="${esc(kind)}">${shown}</span>`;
  };
  function animateCounters(root) {
    const els = $$('[data-countup]', root);
    if (!els.length || prefersReducedMotion()) return;
    els.forEach((el, idx) => {
      const target = Number(el.dataset.countup);
      const kind = el.dataset.countupKind || 'int';
      if (!Number.isFinite(target)) return;
      const fmt = (v, done) => kind === 'money' ? `¥ ${money(v)}` : kind === 'compact' ? `¥ ${compactMoney(v)}` : kind === 'pct' ? v.toFixed(1) : num(done ? target : Math.round(v));
      const dur = 750;
      const t0 = performance.now() + 140 + idx * 55;
      el.textContent = fmt(0, false);
      const step = (t) => {
        const p = Math.min(1, Math.max(0, (t - t0) / dur));
        const done = p >= 1;
        el.textContent = fmt(target * (1 - Math.pow(1 - p, 3)), done);
        if (!done) requestAnimationFrame(step);
      };
      requestAnimationFrame(step);
    });
  }

  let chartInstances = [];
  let chartLoaderPromise = null;
  function ensureCharts() {
    if (window.echarts) return Promise.resolve(window.echarts);
    if (chartLoaderPromise) return chartLoaderPromise;
    chartLoaderPromise = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      let settled = false;
      const finish = (error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timeout);
        script.onload = null;
        script.onerror = null;
        if (error) { script.remove(); reject(error); }
        else if (window.echarts) resolve(window.echarts);
        else { script.remove(); reject(new Error('图表组件加载失败')); }
      };
      script.src = '/echarts.min.js?v=20260813v10r3';
      script.async = true;
      script.dataset.yxCharts = 'true';
      script.onload = () => finish();
      script.onerror = () => finish(new Error('图表组件加载失败，请检查网络后重试'));
      const timeout = setTimeout(() => {
        script.remove();
        finish(new Error('图表组件加载超时，请稍后重试'));
      }, 10000);
      document.head.appendChild(script);
    }).catch((error) => {
      chartLoaderPromise = null;
      throw error;
    });
    return chartLoaderPromise;
  }
  function clearCharts() {
    chartInstances.forEach((chart) => { try { chart.dispose(); } catch (e) {} });
    chartInstances = [];
  }
  function makeChart(id, option, root = document) {
    const el = $('#' + id, root);
    if (!el || !window.echarts) return null;
    el.innerHTML = '';
    const chart = echarts.init(el);
    chart.setOption(option);
    chartInstances.push(chart);
    return chart;
  }
  window.addEventListener('resize', debounce(() => chartInstances.forEach((chart) => chart.resize())));

  const state = { user: null, page: 'dashboard', projectId: null, contextProjectId: null, focusId: null, initialFilter: null, filters: {}, cache: {} };
  let routeEpoch = 0;
  let routeCleanups = [];
  let lastRenderedHash = '';
  let navKeyHandler = null;
  let globalKeyHandler = null;
  let scrollShadowHandler = null;
  let v6PointerHandler = null;
  let loginRotTimer = null;
  let loginRotSwap = null;
  let v7GlowHandler = null;

  function clearRouteAsync() {
    const cleanups = routeCleanups;
    routeCleanups = [];
    cleanups.forEach((cleanup) => { try { cleanup(); } catch (e) {} });
  }

  function beginRouteEpoch() {
    clearRouteAsync();
    routeEpoch += 1;
    return routeEpoch;
  }

  function addRouteCleanup(cleanup, epoch = routeEpoch) {
    if (epoch !== routeEpoch) { try { cleanup(); } catch (e) {} return; }
    routeCleanups.push(cleanup);
  }

  function isRouteCurrent(epoch, content = null, page = null) {
    if (epoch !== routeEpoch || (page && state.page !== page)) return false;
    if (!content) return true;
    return content.isConnected && $('#content') === content;
  }

  function positiveRouteId(value) {
    const raw = value == null ? '' : String(value);
    return /^[1-9]\d*$/.test(raw) ? raw : null;
  }

  function cssEscape(value) {
    if (window.CSS && typeof window.CSS.escape === 'function') return window.CSS.escape(String(value));
    return String(value).replace(/[^a-zA-Z0-9_-]/g, (char) => `\\${char.codePointAt(0).toString(16)} `);
  }

  function invalidateSession() {
    const alreadyOnLogin = !state.user && Boolean($('.v6-login'));
    beginRouteEpoch();
    state.user = null;
    state.projectId = null;
    state.contextProjectId = null;
    state.focusId = null;
    state.initialFilter = null;
    state.filters = {};
    state.cache = {};
    localStorage.removeItem('token');
    clearTimeout(toastTimer);
    toastTimer = null;
    $('.toast')?.remove();
    closeModal();
    if (!alreadyOnLogin) renderLogin();
  }

  const sceneBridge = (() => {
    let modulePromise;
    let controller;
    let generation = 0;
    const load = () => (modulePromise ||= import('/scene/yx-scene.js?v=20260813v10r3'));
    const call = async (method, ...args) => {
      const ticket = ++generation;
      try {
        const mod = await load();
        if (ticket !== generation && method === 'setMode') return;
        controller ||= mod.createExperience(document.getElementById('yx-scene-root'));
        controller?.[method]?.(...args);
      } catch (error) {
        document.documentElement.dataset.sceneFallback = 'true';
      }
    };
    return {
      setMode(mode, payload = {}) {
        document.documentElement.dataset.sceneMode = mode;
        if (mode === 'shell') {
          generation += 1;
          controller?.destroy?.();
          controller = undefined;
          return Promise.resolve();
        }
        return call('setMode', mode, payload).then(() => controller?.resume?.());
      },
      setRoute(page) { controller?.setRoute?.(page); },
      setPhase(phase) { controller?.setPhase?.(phase); },
    };
  })();

  function navigateTo(page, options = {}) {
    const { projectId = null, focusId = null, filter = null, skipHistory = false, replaceHistory = false } = options;
    closeModal();
    state.page = page;
    state.focusId = focusId;
    const resetTargetFilter = !filter && Boolean(focusId || (projectId && page !== 'project_detail'));
    state.initialFilter = filter || (resetTargetFilter ? { status: '', kw: '' } : null);
    if (resetTargetFilter) state.filters[page] = {};
    if (filter && Object.prototype.hasOwnProperty.call(filter, 'status')) {
      state.filters[page] = { ...(state.filters[page] || {}), status: String(filter.status || '') };
    }
    if (page === 'project_detail') {
      state.projectId = projectId || state.projectId;
      state.contextProjectId = null;
    } else {
      state.projectId = null;
      state.contextProjectId = projectId || null;
    }
    if (!skipHistory) writeRouteToUrl(replaceHistory);
    sceneBridge.setRoute(page);
    renderLayout();
  }

  function routeUrl() {
    const params = new URLSearchParams();
    const projectId = state.page === 'project_detail' ? state.projectId : state.contextProjectId;
    if (projectId) params.set('project', projectId);
    if (state.focusId) params.set('focus', state.focusId);
    const routeFilter = state.initialFilter || state.filters[state.page];
    if (routeFilter?.status) params.set('status', routeFilter.status);
    return `#/${encodeURIComponent(state.page)}${params.toString() ? `?${params}` : ''}`;
  }

  function writeRouteToUrl(replace = false) {
    const next = routeUrl();
    if (location.hash === next) { lastRenderedHash = next; return; }
    history[replace ? 'replaceState' : 'pushState']({ yanxu: true }, '', next);
    lastRenderedHash = next;
  }

  function restoreRouteFromUrl() {
    const raw = location.hash.replace(/^#\/?/, '');
    if (!raw) return false;
    const [encodedPage, query = ''] = raw.split('?');
    let page;
    try { page = decodeURIComponent(encodedPage); } catch (e) { return false; }
    const allowed = new Set([...NAV.map((item) => item.k), 'project_detail']);
    if (!allowed.has(page) || (page === 'users' && state.user?.role !== 'admin')) return false;
    const params = new URLSearchParams(query);
    const rawProjectId = params.get('project');
    const rawFocusId = params.get('focus');
    const projectId = rawProjectId === null ? null : positiveRouteId(rawProjectId);
    const focusId = rawFocusId === null ? null : positiveRouteId(rawFocusId);
    if ((rawProjectId !== null && !projectId) || (rawFocusId !== null && !focusId)) return false;
    if (page === 'project_detail' && !projectId) return false;
    const status = params.get('status') || '';
    state.page = page;
    state.projectId = page === 'project_detail' ? projectId : null;
    state.contextProjectId = page === 'project_detail' ? null : projectId;
    state.focusId = focusId;
    state.initialFilter = status ? { status } : null;
    state.filters[page] = { ...(state.filters[page] || {}), status };
    return true;
  }

  function revealFocusedRow(root) {
    const id = state.focusId;
    if (!id || !root) return;
    const row = $(`[data-row-id="${cssEscape(id)}"]`, root);
    state.focusId = null;
    if (!row) return;
    row.tabIndex = -1;
    row.classList.add('is-targeted');
    row.scrollIntoView({ block: 'center', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
    row.focus({ preventScroll: true });
    setTimeout(() => row && row.classList.remove('is-targeted'), 2400);
  }

  async function api(path, opts = {}) {
    const headers = { 'Content-Type': 'application/json' };
    let res;
    try {
      res = await fetch('/api' + path, {
        method: opts.method || (opts.body !== undefined ? 'POST' : 'GET'),
        headers,
        body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
        credentials: 'same-origin',
        signal: opts.signal,
      });
    } catch (e) {
      if (e && e.name === 'AbortError') throw e;
      toast(navigator.onLine ? '服务暂时不可用，请稍后重试' : '网络已断开，恢复连接后请重试', true);
      throw new Error('网络连接失败');
    }
    const raw = await res.text();
    let j;
    try { j = JSON.parse(raw); } catch (e) {
      toast('服务返回了无法识别的数据，请稍后重试', true);
      throw new Error('响应格式错误');
    }
    if (j.code === 401) {
      localStorage.removeItem('token');
      if (path !== '/login') invalidateSession();
      throw new Error(j.msg || '登录状态已失效，请重新登录');
    }
    if (j.code !== 0) { toast(j.msg || '操作失败', true); throw new Error(j.msg); }
    return j.data;
  }

  let toastTimer = null;
  function toast(msg, isErr) {
    clearTimeout(toastTimer);
    $('.toast') && $('.toast').remove();
    const d = document.createElement('div');
    d.className = 'toast' + (isErr ? ' err' : '');
    d.setAttribute('role', isErr ? 'alert' : 'status');
    d.setAttribute('aria-live', 'polite');
    d.innerHTML = `${icon(isErr ? 'circle-alert' : 'circle-check')}<span>${esc(msg)}</span>`;
    document.body.appendChild(d);
    refreshIcons(d);
    toastTimer = setTimeout(() => d.remove(), 3200);
  }

  // ============ 弹窗 ============
  let modalPreviousFocus = null;
  function openModal(title, bodyHtml, opts = {}) {
    closeModal();
    modalPreviousFocus = document.activeElement;
    const mask = document.createElement('div');
    mask.className = 'mask';
    mask.id = 'modal-mask';
    mask.innerHTML = `
      <div class="modal ${opts.sm ? 'sm' : ''} ${opts.wide ? 'wide' : ''}" role="dialog" aria-modal="true" aria-labelledby="modal-title" tabindex="-1">
        <div class="modal-head"><div><span class="modal-kicker">${opts.kicker ? esc(opts.kicker) : '研序运营中心'}</span><h3 id="modal-title">${esc(title)}</h3></div><button type="button" class="x" id="modal-x" aria-label="关闭弹窗">${icon('x')}</button></div>
        <div class="modal-body">${bodyHtml}</div>
        ${opts.noFoot ? '' : `<div class="modal-foot">
          <button type="button" class="btn gray" id="modal-cancel">取消</button>
          <button type="button" class="btn" id="modal-ok">${icon(opts.okIcon || 'check')}<span>${opts.okText || '保存'}</span></button>
        </div>`}
      </div>`;
    document.body.appendChild(mask);
    document.body.classList.add('modal-open');
    refreshIcons(mask);
    $('#modal-x').onclick = closeModal;
    if (!opts.noFoot) {
      $('#modal-cancel').onclick = closeModal;
      $('#modal-ok').onclick = async () => {
        const btn = $('#modal-ok');
        if (btn.disabled) return;
        const old = btn.innerHTML;
        btn.disabled = true;
        btn.classList.add('is-loading');
        btn.innerHTML = `${icon('loader-circle', 'spin')}<span>处理中</span>`;
        refreshIcons(btn);
        try {
          const r = opts.onOk && (await opts.onOk());
          if (r !== false) closeModal();
          else if (document.body.contains(btn)) { btn.disabled = false; btn.classList.remove('is-loading'); btn.innerHTML = old; refreshIcons(btn); }
        } catch (e) {
          if (document.body.contains(btn)) { btn.disabled = false; btn.classList.remove('is-loading'); btn.innerHTML = old; refreshIcons(btn); }
        }
      };
    }
    mask.onclick = (e) => {
      if (e.target !== mask) return;
      const modal = $('.modal', mask);
      modal.classList.add('attention');
      setTimeout(() => modal && modal.classList.remove('attention'), 220);
    };
    mask._keyHandler = (e) => {
      if (e.key === 'Escape') { e.preventDefault(); closeModal(); return; }
      if (e.key !== 'Tab') return;
      const focusables = $$('button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])', mask);
      if (!focusables.length) return;
      const first = focusables[0], last = focusables[focusables.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    };
    document.addEventListener('keydown', mask._keyHandler);
    requestAnimationFrame(() => {
      const first = $('[autofocus]', mask) || $('input:not([disabled]), select:not([disabled]), textarea:not([disabled]), #modal-ok, #modal-x', mask);
      first && first.focus();
    });
    return mask;
  }
  function closeModal() {
    const m = $('#modal-mask');
    if (!m) return;
    if (m._keyHandler) document.removeEventListener('keydown', m._keyHandler);
    chartInstances = chartInstances.filter((chart) => {
      try { if (m.contains(chart.getDom())) { chart.dispose(); return false; } } catch (e) {}
      return true;
    });
    m.remove();
    document.body.classList.remove('modal-open');
    if (modalPreviousFocus && document.contains(modalPreviousFocus)) modalPreviousFocus.focus();
    modalPreviousFocus = null;
  }

  function confirmBox(msg, onYes) {
    openModal('确认这项操作？', `<div class="confirm-content"><span class="confirm-icon">${icon('circle-help')}</span><p>${esc(msg)}</p></div>`, {
      sm: true, kicker: '请核对影响范围', okText: '确认继续', onOk: async () => { await onYes(); },
    });
  }

  // ============ 表单 ============
  // field: {k,label,type:text|number|date|select|textarea|readonly,options,required,span2,value,placeholder}
  let formSeq = 0;
  function renderForm(fields, data) {
    return `<div class="form-grid">` + fields.map((f) => {
      const hasRecordValue = data && data[f.k] !== undefined && data[f.k] !== null;
      const v = hasRecordValue ? data[f.k] : (f.value !== undefined ? f.value : '');
      const req = f.required ? '<span class="req">*</span>' : '';
      const id = `field-${f.k}-${++formSeq}`;
      const hintId = f.hint ? `${id}-hint` : '';
      const errorId = `${id}-error`;
      const describedBy = [hintId, errorId].filter(Boolean).join(' ');
      const common = `${f.required ? 'required' : ''} ${f.disabled ? 'disabled' : ''} ${f.min !== undefined ? `min="${f.min}"` : ''} ${f.max !== undefined ? `max="${f.max}"` : ''} ${f.step !== undefined ? `step="${f.step}"` : ''}`;
      let input = '';
      if (f.type === 'select') {
        const needsPlaceholder = f.required && String(v) === '' && !(f.options || []).some((o) => String(typeof o === 'object' ? o.v : o) === '');
        input = `<select id="${id}" data-k="${f.k}" aria-describedby="${describedBy}" ${common}>
          ${needsPlaceholder ? `<option value="" selected disabled>请选择${esc(f.label || '')}</option>` : ''}
          ${(f.options || []).map((o) => {
            const ov = typeof o === 'object' ? o.v : o, ol = typeof o === 'object' ? o.l : o;
            return `<option value="${esc(ov)}" ${String(ov) === String(v) ? 'selected' : ''}>${esc(ol)}</option>`;
          }).join('')}</select>`;
      } else if (f.type === 'textarea') {
        input = `<textarea id="${id}" data-k="${f.k}" placeholder="${esc(f.placeholder || '')}" aria-describedby="${describedBy}" ${common}>${esc(v)}</textarea>`;
      } else {
        input = `<input id="${id}" data-k="${f.k}" type="${f.type === 'readonly' ? 'text' : (f.type || 'text')}" value="${esc(v)}" placeholder="${esc(f.placeholder || '')}" aria-describedby="${describedBy}" ${f.type === 'readonly' ? 'readonly' : ''} ${f.autocomplete ? `autocomplete="${esc(f.autocomplete)}"` : ''} ${common}>`;
      }
      return `<div class="form-item ${f.span2 || f.type === 'textarea' ? 'span2' : ''}"><label for="${id}">${esc(f.label)}${req}</label>${input}${f.hint ? `<small id="${hintId}">${esc(f.hint)}</small>` : ''}<span class="field-error" id="${errorId}" aria-live="polite"></span></div>`;
    }).join('') + `</div>`;
  }

  function collectForm(mask, fields) {
    const data = {};
    for (const f of fields) {
      const el = $(`[data-k="${f.k}"]`, mask);
      if (!el) continue;
      let v = el.value.trim();
      const item = el.closest('.form-item');
      const error = item && $('.field-error', item);
      if (error) error.textContent = '';
      el.removeAttribute('aria-invalid');
      const invalid = (message) => {
        if (error) error.textContent = message;
        el.setAttribute('aria-invalid', 'true');
        el.focus();
        toast(message, true);
      };
      if (f.required && !v) { invalid(`请填写${f.label || '必填信息'}`); return null; }
      if (f.type === 'number') {
        v = v === '' ? 0 : Number(v);
        if (!Number.isFinite(v)) { invalid(`${f.label || '数值'}格式不正确`); return null; }
        if (f.min !== undefined && v < f.min) { invalid(`${f.label}不能小于 ${f.min}`); return null; }
        if (f.max !== undefined && v > f.max) { invalid(`${f.label}不能大于 ${f.max}`); return null; }
      }
      if (f.type === 'email' && v && !el.checkValidity()) { invalid('请输入有效的邮箱地址'); return null; }
      data[f.k] = v;
    }
    return data;
  }

  // ============ 表格 ============
  function actionPriority(action, row) {
    const label = action.l;
    if (label === '补齐准备') return row && row.status === '已确认' ? -1 : 2;
    if (['中标', '启动项目', '收款', '记录通知', '确认', '完成', '发布', '发送链接', '发放', '入库'].includes(label)) return 0;
    if (['打开项目', '详情', '统计', '档案', '消息'].includes(label)) return 1;
    if (['编辑', '评价', '重置密码'].includes(label)) return 3;
    if (['归档', '关闭', '出库', '删除'].includes(label)) return 9;
    return 5;
  }

  function renderRowActions(actions, row) {
    const visible = actions.map((a, i) => ({ a, i })).filter(({ a }) => !a.show || a.show(row)).sort((x, y) => actionPriority(x.a, row) - actionPriority(y.a, row));
    if (!visible.length) return '';
    const makeButton = ({ a, i }, menu = false) => `<button type="button" class="btn sm ${menu ? 'menu-action' : ''} ${a.cls || 'gray'}" data-act="${i}" data-id="${row.id}">${icon(a.icon || actionIcon(a.l))}<span>${esc(a.l)}</span></button>`;
    if (visible.length === 1 && visible[0].a.l !== '删除') return makeButton(visible[0]);
    const primary = visible[0].a.l === '删除' ? null : visible.shift();
    return `${primary ? makeButton(primary) : ''}<details class="row-more"><summary aria-label="更多操作" title="更多操作">${icon('ellipsis')}</summary><div>${visible.map((item) => makeButton(item, true)).join('')}</div></details>`;
  }

  function renderTable(cols, rows, actions, kind = '') {
    if (!rows || rows.length === 0) {
      const hint = canWrite() ? '调整筛选条件，或新增一条业务记录。' : '调整筛选条件，查看其他业务记录。';
      return `<div class="empty-state">${icon('inbox')}<h3>暂时没有数据</h3><p>${hint}</p></div>`;
    }
    const hasActions = Array.isArray(actions) && actions.some((a) => rows.some((r) => !a.show || a.show(r)));
    return `<div class="table-wrap ${kind ? `table-${esc(kind)}` : ''}"><table class="tbl"><thead><tr>
      ${cols.map((c) => `<th scope="col" class="${c.align === 'right' ? 'align-right ' : ''}${c.mobileHide ? 'mobile-hide' : ''}">${esc(c.l)}</th>`).join('')}${hasActions ? `<th scope="col" class="actions-head">操作</th>` : ''}
      </tr></thead><tbody>
      ${rows.map((r) => `<tr data-row-id="${esc(r.id)}">
        ${cols.map((c) => `<td data-label="${esc(c.l)}" class="${c.align === 'right' ? 'align-right numeric ' : ''}${c.mobileHide ? 'mobile-hide' : ''}">${c.render ? c.render(r) : esc(r[c.k])}</td>`).join('')}
        ${hasActions ? `<td data-label="操作" class="actions-cell"><div class="row-actions">${renderRowActions(actions, r)}</div></td>` : ''}
      </tr>`).join('')}
      </tbody></table></div>`;
  }

  function actionIcon(label) {
    const map = { '详情': 'eye', '打开项目': 'arrow-up-right', '项目总览': 'panels-top-left', '中标': 'badge-check', '启动项目': 'play', '收款': 'circle-dollar-sign', '完成交付': 'circle-check-big', '归档': 'archive', '编辑': 'pencil', '删除': 'trash-2', '消息': 'message-square-text', '记录通知': 'message-square-share', '确认': 'check-circle-2', '完成': 'circle-check', '统计': 'chart-no-axes-column-increasing', '发布': 'rocket', '发微信': 'send', '关闭': 'circle-x', '发放': 'badge-dollar-sign', '评价': 'star', '出库': 'log-out', '入库': 'log-in', '重置密码': 'key-round' };
    return map[label] || 'arrow-right';
  }

  function bindTableActions(el, rows, actions) {
    $$('.btn[data-act]', el).forEach((btn) => {
      btn.onclick = () => {
        const a = actions[Number(btn.dataset.act)];
        const row = rows.find((r) => String(r.id) === btn.dataset.id);
        const menu = btn.closest('details');
        if (menu) menu.removeAttribute('open');
        a.onClick(row);
      };
    });
    refreshIcons(el);
  }

  const tagClass = (v) => {
    if (['已确认', '已完成', '已结清', '已发放', '已中标', '在库', '已立项', '已就绪', '启用'].includes(v)) return 'green';
    if (['待发送', '待评审', '待处理', '待发放', '未收费', '待启动', '待准备', '材料待补充'].includes(v)) return 'orange';
    if (['已拒绝', '未中标', '已流标'].includes(v)) return 'red';
    if (['部分收费', '进行中', '已发送', '已投标', '准备中'].includes(v)) return 'blue';
    if (['已发布'].includes(v)) return 'violet';
    return 'gray';
  };
  const tag = (v) => `<span class="tag ${tagClass(v)}"><i aria-hidden="true"></i>${esc(v)}</span>`;

  // ============ 登录 ============
  const V6_JOBS = ['项目主管', '运营统筹', '教务排课', '师资管理', '投标方案', '财务结算', '质量评估', '经营分析'].map((name) => ({ name }));
  const V6_ROT_WORDS = V6_JOBS.map((j) => j.name);
  const V6_JOB_ICONS = {
    '项目主管': 'radar', '运营统筹': 'calendar-range', '教务排课': 'clock-3', '师资管理': 'contact-round',
    '投标方案': 'file-check-2', '财务结算': 'badge-japanese-yen', '质量评估': 'clipboard-check', '经营分析': 'chart-no-axes-combined',
  };

  function renderLogin() {
    state.user = null;
    document.title = '登录 · 研序';
    if (navKeyHandler) { document.removeEventListener('keydown', navKeyHandler); navKeyHandler = null; }
    if (globalKeyHandler) { document.removeEventListener('keydown', globalKeyHandler); globalKeyHandler = null; }
    if (scrollShadowHandler) { window.removeEventListener('scroll', scrollShadowHandler); scrollShadowHandler = null; }
    if (v7GlowHandler) { window.removeEventListener('pointermove', v7GlowHandler); v7GlowHandler = null; }
    clearCharts();
    sceneBridge.setMode('login');
    document.getElementById('app').innerHTML = `
      <main class="v6-login" aria-label="研序登录">
        <div class="v6-bg" aria-hidden="true"><i class="v6-aurora a1"></i><i class="v6-aurora a2"></i><i class="v6-aurora a3"></i><span class="v6-gridlines"></span><span class="v6-glow"></span></div>
        <header class="v6-top">
          <div class="v6-brand">${brandSymbol()}<span><b>研序</b><small>TRAINING OPERATIONS</small></span></div>
          <span class="v6-top-mini"><i aria-hidden="true"></i>培训运营中枢</span>
        </header>
        <section class="v6-hero">
          <div class="v6-hero-copy">
            <span class="v10-kicker">YANXU · OPERATIONS CLOUD</span>
            <h1 aria-label="专业的培训人，都在用研序。">专业<span class="v6-rotword" aria-hidden="true">${V6_ROT_WORDS[0]}</span>，<br>都在用<em>研序</em>。</h1>
            <p>让需求、项目、师资、交付与结算在同一条运营轨道上持续推进。</p>
            <div class="v10-proof" aria-label="研序核心能力"><span>${icon('workflow')}全流程协同</span><span>${icon('shield-check')}角色权限</span><span>${icon('chart-spline')}经营洞察</span></div>
            <div class="v6-pulse" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i><span class="v6-pulse-run"></span></div>
          </div>
          <div class="v6-card">
            <div class="v6-card-head"><span class="v6-dot"></span><span><b>进入研序</b><small>使用授权账号继续</small></span><em>SECURE</em></div>
            <form id="login-form">
              <div class="login-err" id="login-err" role="alert" aria-live="polite"></div>
              <label class="sr-only" for="login-user">账号</label>
              <div class="login-input">${icon('user-round')}<input id="login-user" name="username" placeholder="账号" autocomplete="username" required aria-describedby="login-user-hint"></div>
              <span class="sr-only" id="login-user-hint">请输入您的研序授权账号</span>
              <label class="sr-only" for="login-pwd">密码</label>
              <div class="login-input">${icon('lock-keyhole')}<input id="login-pwd" name="password" type="password" placeholder="密码" autocomplete="current-password" required aria-describedby="caps-lock-note"><button type="button" id="pwd-toggle" aria-label="显示密码">${icon('eye')}</button></div>
              <div class="v10-caps" id="caps-lock-note" role="status" aria-live="polite"></div>
              <button type="submit" class="login-submit" id="login-btn"><span>进入工作台</span>${icon('arrow-right')}</button>
            </form>
            <p class="v6-card-foot">${icon('lock-keyhole')}连接已加密 · 仅限授权用户访问</p>
          </div>
        </section>
        <section class="v6-marquee" aria-hidden="true">
          <div class="v6-marquee-track">${V6_JOBS.concat(V6_JOBS, V6_JOBS, V6_JOBS).map((j, idx) => `<span class="v6-chip tone-${idx % V6_JOBS.length}">${icon(V6_JOB_ICONS[j.name])}<b>${j.name}</b></span>`).join('')}</div>
        </section>
        <footer class="v6-foot"><span>研序 · 培训运营中心</span><span>需求 → 项目 → 交付 → 结算</span></footer>
      </main>`;
    refreshIcons(document.getElementById('app'));
    const v6bg = $('.v6-bg');
    if (v6bg && !prefersReducedMotion() && window.matchMedia('(pointer: fine)').matches) {
      if (v6PointerHandler) window.removeEventListener('pointermove', v6PointerHandler);
      v6PointerHandler = (e) => { v6bg.style.setProperty('--mx', `${e.clientX}px`); v6bg.style.setProperty('--my', `${e.clientY}px`); };
      window.addEventListener('pointermove', v6PointerHandler, { passive: true });
    }
    const rotEl = $('.v6-rotword');
    if (loginRotTimer) { clearInterval(loginRotTimer); loginRotTimer = null; }
    if (loginRotSwap) { clearTimeout(loginRotSwap); loginRotSwap = null; }
    if (rotEl && !prefersReducedMotion()) {
      let ri = 0;
      loginRotTimer = setInterval(() => {
        rotEl.classList.remove('v6-swap');
        void rotEl.offsetWidth;
        rotEl.classList.add('v6-swap');
        loginRotSwap = setTimeout(() => { ri = (ri + 1) % V6_ROT_WORDS.length; rotEl.textContent = V6_ROT_WORDS[ri]; }, 320);
      }, 2600);
    }
    const lastUser = localStorage.getItem('yx_last_username') || '';
    if (lastUser) $('#login-user').value = lastUser;
    const capsNote = $('#caps-lock-note');
    const updateCapsLock = (e) => {
      const on = Boolean(e.getModifierState && e.getModifierState('CapsLock'));
      capsNote.textContent = on ? '大写锁定已开启' : '';
      capsNote.classList.toggle('show', on);
    };
    $('#login-pwd').addEventListener('keyup', updateCapsLock);
    $('#login-pwd').addEventListener('mousedown', updateCapsLock);
    const doLogin = async () => {
      $('#login-err').textContent = '';
      const btn = $('#login-btn');
      if (btn.disabled) return;
      btn.disabled = true;
      btn.innerHTML = `${icon('loader-circle', 'spin')}<span>正在验证</span>`;
      sceneBridge.setPhase('loading');
      refreshIcons(btn);
      try {
        const r = await api('/login', { body: { username: $('#login-user').value.trim(), password: $('#login-pwd').value } });
        localStorage.removeItem('token');
        localStorage.setItem('yx_last_username', $('#login-user').value.trim());
        state.user = r.user;
        if (!restoreRouteFromUrl()) {
          state.page = 'dashboard';
          state.projectId = null;
          state.contextProjectId = null;
          state.focusId = null;
          state.initialFilter = null;
          writeRouteToUrl(true);
        }
        sceneBridge.setPhase('success');
        renderLayout();
      } catch (e) {
        sceneBridge.setPhase('error');
        $('#login-err').textContent = e.message || '登录失败';
        btn.disabled = false;
        btn.innerHTML = `<span>进入工作台</span>${icon('arrow-right')}`;
        refreshIcons(btn);
      }
    };
    $('#login-form').onsubmit = (e) => { e.preventDefault(); doLogin(); };
    $('#pwd-toggle').onclick = () => {
      const input = $('#login-pwd');
      input.type = input.type === 'password' ? 'text' : 'password';
      $('#pwd-toggle').innerHTML = icon(input.type === 'password' ? 'eye' : 'eye-off');
      $('#pwd-toggle').setAttribute('aria-label', input.type === 'password' ? '显示密码' : '隐藏密码');
      refreshIcons($('#pwd-toggle'));
    };
    requestAnimationFrame(() => (lastUser ? $('#login-pwd') : $('#login-user')).focus());
  }

  // ============ 主布局 ============
  const NAV = [
    { k: 'dashboard', l: '今日运营', ico: 'scan-line', group: '工作台' },
    { k: 'projects', l: '项目总览', ico: 'folder-kanban', group: '项目运营' },
    { k: 'demands', l: '培训需求', ico: 'inbox', group: '项目运营' },
    { k: 'bids', l: '投标与立项', ico: 'file-check-2', group: '项目运营' },
    { k: 'dispatches', l: '课程与排期', ico: 'calendar-clock', group: '交付协同' },
    { k: 'questionnaires', l: '效果评估', ico: 'clipboard-check', group: '交付协同' },
    { k: 'teachers', l: '师资资源', ico: 'users-round', group: '交付协同' },
    { k: 'charges', l: '项目回款', ico: 'circle-dollar-sign', group: '财务结算' },
    { k: 'fees', l: '课酬发放', ico: 'wallet-cards', group: '财务结算' },
    { k: 'costs', l: '成本费用', ico: 'receipt-text', group: '财务结算' },
    { k: 'report', l: '经营洞察', ico: 'chart-no-axes-combined', group: '分析' },
    { k: 'users', l: '用户与权限', ico: 'shield-check', group: '系统', admin: true },
  ];

  const PAGE_META = {
    dashboard: ['今日运营', ''], demands: ['培训需求', '统一沉淀客户需求与培训目标'],
    bids: ['投标与立项', '管理投标方案、报价与立项结果'], projects: ['项目总览', '以项目为中心协同交付与结算'],
    dispatches: ['课程与排期', '安排课程、发送邀请并跟踪讲师确认'], questionnaires: ['效果评估', '创建问卷并回收培训反馈'],
    teachers: ['师资资源', '管理讲师档案、专长与授课评价'], charges: ['项目回款', '跟踪应收、回款进度与票据信息'],
    fees: ['课酬发放', '根据确认课时核算并发放课酬'], costs: ['成本费用', '记录项目交付成本与费用构成'],
    report: ['经营洞察', '分析合同、回款、已录成本与项目质量'], users: ['用户与权限', '管理系统账号、角色与启用状态'],
    project_detail: ['项目工作区', '围绕风险、交付、评估与结算推进单个项目'],
  };

  function quickCreateDemand() {
    if (!canWrite()) return;
    editForm(CRUD.demands, null, () => navigateTo('demands'));
  }

  function isTypingTarget(el) {
    return el && (/^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName) || el.isContentEditable);
  }

  function handleAppShortcut(e) {
    const key = String(e.key || '').toLowerCase();
    if ((e.metaKey || e.ctrlKey) && key === 'k') {
      e.preventDefault();
      openCommandCenter();
      return;
    }
    if (!isTypingTarget(e.target) && !$('#modal-mask') && key === 'n' && canWrite()) {
      e.preventDefault();
      quickCreateDemand();
      return;
    }
    if (!isTypingTarget(e.target) && !$('#modal-mask') && e.key === '/') {
      const search = $('.search-box input');
      if (search) { e.preventDefault(); search.focus(); }
      else { e.preventDefault(); openCommandCenter(); }
      return;
    }
    if (!isTypingTarget(e.target) && !$('#modal-mask') && (e.key === '?' || (e.shiftKey && e.key === '/'))) {
      e.preventDefault();
      openShortcutGuide();
    }
  }

  function openShortcutGuide() {
    openModal('快捷键与高效操作', `<div class="shortcut-grid">
      <div><kbd>⌘ K</kbd><span><b>全局搜索</b><small>查找功能或项目</small></span></div>
      ${canWrite() ? `<div><kbd>N</kbd><span><b>新建需求</b><small>随时开始录入培训需求</small></span></div>` : ''}
      <div><kbd>/</kbd><span><b>当前页搜索</b><small>列表页直接聚焦搜索框</small></span></div>
      <div><kbd>?</kbd><span><b>快捷键帮助</b><small>再次查看本说明</small></span></div>
      <div><kbd>ESC</kbd><span><b>返回或关闭</b><small>关闭弹窗与移动导航</small></span></div>
    </div>`, { noFoot: true, kicker: '效率中心' });
  }

  async function openCommandCenter() {
    const body = `<div class="command-palette">
      <label class="command-search"><span class="sr-only">搜索功能或项目</span>${icon('search')}<input id="command-query" placeholder="输入项目、单位或功能名称" autocomplete="off" autofocus role="combobox" aria-autocomplete="list" aria-expanded="true" aria-controls="command-results"><kbd>ESC</kbd></label>
      <div class="command-results" id="command-results" role="listbox" aria-label="可执行命令"></div>
      <div class="command-hint"><span>${icon('corner-down-left')}回车进入</span><span>${icon('arrow-up-down')}上下选择</span></div>
    </div>`;
    openModal('搜索功能或直接开始', body, { noFoot: true, wide: true, kicker: '快捷工作入口 · ⌘ K' });
    const input = $('#command-query');
    const resultRoot = $('#command-results');
    let projects = [];
    let visible = [];
    let activeIndex = 0;

    const commandItems = () => {
      const items = [];
      if (canWrite()) items.push({ label: '新建培训需求', meta: '录入客户目标、课时、师资要求与期望日期', keywords: '新建 创建 客户 需求', ico: 'plus', action: '新建', run: quickCreateDemand });
      NAV.filter((n) => !n.admin || state.user.role === 'admin').forEach((n) => items.push({
        label: n.l, meta: (PAGE_META[n.k] || [n.l, ''])[1], keywords: `${n.l} ${n.group}`, ico: n.ico, action: '前往', run: () => navigateTo(n.k),
      }));
      projects.forEach((p) => items.push({
        label: p.title, meta: `${p.unit || '委托单位待补充'} · ${p.owner || '负责人待补充'} · ${p.status}`, keywords: `${p.title} ${p.unit || ''} ${p.owner || ''} ${p.status || ''}`, ico: 'folder-kanban', action: '打开项目', run: () => navigateTo('project_detail', { projectId: p.id }),
      }));
      return items;
    };

    const bindResultClicks = () => {
      $$('.command-result', resultRoot).forEach((btn) => {
        btn.onclick = () => {
          const item = visible[Number(btn.dataset.commandIndex)];
          if (!item) return;
          closeModal();
          item.run();
        };
      });
      refreshIcons(resultRoot);
    };

    const draw = () => {
      const query = input.value.trim().toLowerCase();
      visible = commandItems().filter((item) => !query || `${item.label} ${item.meta} ${item.keywords}`.toLowerCase().includes(query)).slice(0, 12);
      activeIndex = Math.min(activeIndex, Math.max(0, visible.length - 1));
      resultRoot.innerHTML = visible.length ? visible.map((item, i) => `<button type="button" id="command-option-${i}" class="command-result ${i === activeIndex ? 'active' : ''}" data-command-index="${i}" role="option" aria-selected="${i === activeIndex}"><span>${icon(item.ico)}</span><span><b>${esc(item.label)}</b><small>${esc(item.meta)}</small></span><em>${esc(item.action)}${icon('arrow-right')}</em></button>`).join('') : `<div class="command-empty">${icon('search-x')}<b>没有匹配结果</b><span>换一个项目名、单位名或功能名称试试</span></div>`;
      input.setAttribute('aria-activedescendant', visible.length ? `command-option-${activeIndex}` : '');
      bindResultClicks();
      requestAnimationFrame(() => $(`.command-result[data-command-index="${activeIndex}"]`, resultRoot)?.scrollIntoView({ block: 'nearest' }));
    };

    input.oninput = () => { activeIndex = 0; draw(); };
    input.onkeydown = (e) => {
      if (!visible.length) return;
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        activeIndex = (activeIndex + (e.key === 'ArrowDown' ? 1 : -1) + visible.length) % visible.length;
        draw();
      } else if (e.key === 'Enter') {
        e.preventDefault();
        const button = $(`.command-result[data-command-index="${activeIndex}"]`, resultRoot);
        button && button.click();
      }
    };
    draw();
    try { projects = await api('/projects'); if ($('#command-query') === input) draw(); } catch (e) { /* 功能入口仍可使用 */ }
  }

  function renderLayout() {
    if (navKeyHandler) { document.removeEventListener('keydown', navKeyHandler); navKeyHandler = null; }
    if (globalKeyHandler) { document.removeEventListener('keydown', globalKeyHandler); globalKeyHandler = null; }
    const u = state.user;
    if (!u) { renderLogin(); return; }
    lastRenderedHash = location.hash;
    sceneBridge.setMode('shell', { route: state.page }).then(() => sceneBridge.setRoute(state.page));
    const groups = [];
    NAV.filter((n) => !n.admin || u.role === 'admin').forEach((n) => {
      let group = groups.find((g) => g.name === n.group);
      if (!group) { group = { name: n.group, items: [] }; groups.push(group); }
      group.items.push(n);
    });
    document.getElementById('app').innerHTML = `
      <div class="layout page-${esc(state.page)}">
        <div class="v7-ambient" aria-hidden="true"><i class="a1"></i><i class="a2"></i><i class="a3"></i></div>
        <aside class="sidebar" id="primary-sidebar" aria-label="主导航">
          <div class="logo">${brandSymbol()}<span><b>研序</b><small>TRAINING OS</small></span></div>
          ${canWrite() ? `<button type="button" class="sidebar-create" id="sidebar-create">${icon('plus')}<span>新建培训需求</span><kbd>N</kbd></button>` : ''}
          <nav class="nav">
            ${groups.map((g) => `<div class="nav-group"><div class="nav-label">${esc(g.name)}</div>${g.items.map((n) =>
              `<button type="button" class="nav-item ${state.page === n.k ? 'active' : ''}" data-nav="${n.k}" ${state.page === n.k ? 'aria-current="page"' : ''}>${icon(n.ico, 'ico')}<span>${n.l}</span></button>`).join('')}</div>`).join('')}
          </nav>
        </aside>
        <button type="button" class="sidebar-scrim" id="sidebar-scrim" aria-label="关闭导航" aria-hidden="true" tabindex="-1"></button>
        <div class="main">
          <span class="v7-glow" aria-hidden="true"></span>
          <div class="topbar">
            <div class="topbar-start"><button type="button" class="icon-btn menu-btn" id="menu-btn" aria-label="打开导航" aria-controls="primary-sidebar" aria-expanded="false">${icon('menu')}</button><div class="topbar-title"><div><div class="page-title" id="page-title"></div><div class="page-subtitle" id="page-subtitle"></div></div></div></div>
            <div class="topbar-actions">
              <button type="button" class="top-command" id="global-command">${icon('search')}<span>搜索功能或项目</span><kbd>⌘ K</kbd></button>
              ${canWrite() ? `<button type="button" class="top-create" id="top-create">${icon('plus')}新建需求</button>` : ''}
              <span class="today">${new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' }).format(new Date())}</span>
              <button type="button" class="icon-btn top-help" id="btn-help" aria-label="查看快捷键" title="快捷键">${icon('circle-help')}</button>
              <details class="user user-menu" id="user-menu">
                <summary aria-label="打开账户菜单"><span class="user-avatar">${esc((u.name || u.username).slice(-2))}</span><span class="user-name"><b>${esc(u.name)}</b><small>${esc(u.name === u.roleName ? u.username : u.roleName)}</small></span>${icon('chevron-down')}</summary>
                <div class="user-popover"><div class="user-popover-head"><span class="user-avatar large">${esc((u.name || u.username).slice(-2))}</span><span><b>${esc(u.name)}</b><small>${esc(u.username)} · ${esc(u.roleName)}</small></span></div><button type="button" id="btn-chpwd">${icon('key-round')}<span><b>修改登录密码</b><small>更新当前账号凭据</small></span></button><button type="button" id="btn-logout" class="danger">${icon('log-out')}<span><b>退出登录</b><small>安全结束本次会话</small></span></button></div>
              </details>
            </div>
          </div>
          <main class="content" id="content"></main>
        </div>
        <nav class="mobile-dock" aria-label="移动端快捷导航">
          <button type="button" data-mobile-nav="dashboard" class="${state.page === 'dashboard' ? 'active' : ''}" ${state.page === 'dashboard' ? 'aria-current="page"' : ''}>${icon('house')}<span>今日</span></button>
          <button type="button" data-mobile-nav="projects" class="${['projects','project_detail'].includes(state.page) ? 'active' : ''}" ${['projects','project_detail'].includes(state.page) ? 'aria-current="page"' : ''}>${icon('folder-kanban')}<span>项目</span></button>
          ${canWrite() ? `<button type="button" class="mobile-create" id="mobile-create" aria-label="新建培训需求">${icon('plus')}<span>新建</span></button>` : ''}
          <button type="button" data-mobile-nav="dispatches" class="${state.page === 'dispatches' ? 'active' : ''}" ${state.page === 'dispatches' ? 'aria-current="page"' : ''}>${icon('calendar-clock')}<span>排期</span></button>
          <button type="button" id="mobile-more" aria-controls="primary-sidebar" aria-expanded="false">${icon('menu')}<span>更多</span></button>
        </nav>
      </div>`;
    $$('.nav-item').forEach((n) => (n.onclick = () => navigateTo(n.dataset.nav)));
    $('#btn-logout').onclick = () => confirmBox('确定退出研序工作台？当前账号需要重新验证后才能继续访问。', async () => {
      // 只有服务端确认撤销会话后才切回登录页。网络失败时保留当前画面，
      // 避免 HttpOnly Cookie 仍有效却向用户显示“已安全退出”。
      await api('/logout', { body: {} });
      invalidateSession();
    });
    $('#btn-chpwd').onclick = () => { $('#user-menu')?.removeAttribute('open'); showChangePwd(); };
    $('#btn-help').onclick = openShortcutGuide;
    const layout = $('.layout');
    const userMenu = $('#user-menu');
    layout.addEventListener('click', (e) => { if (userMenu && !e.target.closest('#user-menu')) userMenu.removeAttribute('open'); });
    if (scrollShadowHandler) window.removeEventListener('scroll', scrollShadowHandler);
    if (v6PointerHandler) { window.removeEventListener('pointermove', v6PointerHandler); v6PointerHandler = null; }
    if (v7GlowHandler) { window.removeEventListener('pointermove', v7GlowHandler); v7GlowHandler = null; }
    if (loginRotTimer) { clearInterval(loginRotTimer); loginRotTimer = null; }
    if (loginRotSwap) { clearTimeout(loginRotSwap); loginRotSwap = null; }
    scrollShadowHandler = () => { const tb = $('.topbar'); if (tb) tb.classList.toggle('is-scrolled', window.scrollY > 6); };
    window.addEventListener('scroll', scrollShadowHandler, { passive: true });
    scrollShadowHandler();
    const mainEl = $('.main');
    if (mainEl && !prefersReducedMotion() && window.matchMedia('(pointer: fine)').matches) {
      v7GlowHandler = (e) => { mainEl.style.setProperty('--mx', `${e.clientX - mainEl.getBoundingClientRect().left}px`); mainEl.style.setProperty('--my', `${e.clientY}px`); };
      window.addEventListener('pointermove', v7GlowHandler, { passive: true });
    }
    const sidebar = $('#primary-sidebar');
    const menuBtn = $('#menu-btn');
    const mobileMore = $('#mobile-more');
    const scrim = $('#sidebar-scrim');
    let navOpener = menuBtn;
    const setNavOpen = (open, restoreFocus = false, opener = null) => {
      if (open && opener) navOpener = opener;
      layout.classList.toggle('nav-open', open);
      menuBtn.setAttribute('aria-expanded', String(open));
      if (mobileMore) mobileMore.setAttribute('aria-expanded', String(open));
      scrim.setAttribute('aria-hidden', String(!open));
      scrim.tabIndex = open ? 0 : -1;
      const isCompact = window.matchMedia('(max-width: 1024px)').matches;
      if (isCompact) sidebar.setAttribute('aria-hidden', String(!open));
      else sidebar.removeAttribute('aria-hidden');
      sidebar.inert = isCompact && !open;
      if (open) requestAnimationFrame(() => $('.nav-item', sidebar)?.focus());
      else if (restoreFocus) navOpener?.focus();
    };
    setNavOpen(false);
    menuBtn.onclick = () => setNavOpen(true, false, menuBtn);
    scrim.onclick = () => setNavOpen(false, true);
    navKeyHandler = (e) => {
      if (!layout.classList.contains('nav-open')) return;
      if (e.key === 'Escape') { e.preventDefault(); setNavOpen(false, true); return; }
      if (e.key !== 'Tab') return;
      const focusables = $$('button:not([disabled]), [href], [tabindex]:not([tabindex="-1"])', sidebar);
      if (!focusables.length) return;
      const first = focusables[0], last = focusables[focusables.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    };
    document.addEventListener('keydown', navKeyHandler);
    const sidebarCreate = $('#sidebar-create');
    const topCreate = $('#top-create');
    if (sidebarCreate) sidebarCreate.onclick = quickCreateDemand;
    if (topCreate) topCreate.onclick = quickCreateDemand;
    if ($('#mobile-create')) $('#mobile-create').onclick = quickCreateDemand;
    if ($('#global-command')) $('#global-command').onclick = openCommandCenter;
    $$('.mobile-dock [data-mobile-nav]').forEach((btn) => { btn.onclick = () => navigateTo(btn.dataset.mobileNav); });
    if (mobileMore) mobileMore.onclick = () => setNavOpen(true, false, mobileMore);
    globalKeyHandler = handleAppShortcut;
    document.addEventListener('keydown', globalKeyHandler);
    refreshIcons(document.getElementById('app'));
    renderPage();
  }

  function showChangePwd() {
    openModal('修改登录密码', renderForm([
      { k: 'old', label: '当前密码', type: 'password', required: true, span2: true, autocomplete: 'current-password' },
      { k: 'new', label: '新密码', type: 'password', required: true, span2: true, autocomplete: 'new-password', hint: '至少 8 位字符' },
      { k: 'new2', label: '再次输入新密码', type: 'password', required: true, span2: true, autocomplete: 'new-password' },
    ]), {
      sm: true,
      onOk: async () => {
        const d = collectForm($('#modal-mask'), [{ k: 'old', label: '当前密码', required: true }, { k: 'new', label: '新密码', required: true }, { k: 'new2', label: '确认密码', required: true }]);
        if (!d || !d.old || !d.new) { toast('请填写完整', true); return false; }
        if (d.new.length < 8) { toast('新密码至少需要 8 位', true); return false; }
        if (d.new !== d.new2) { toast('两次输入的新密码不一致', true); return false; }
        await api('/password', { body: { old: d.old, new: d.new } });
        invalidateSession();
        toast('密码修改成功，请使用新密码重新登录');
      },
    });
  }

  function setTitle(page) {
    const meta = PAGE_META[page] || ['', ''];
    $('#page-title').textContent = meta[0];
    $('#page-subtitle').textContent = meta[1];
    document.title = `${meta[0]} · 研序`;
  }

  function enhancePageSemantics(root, page) {
    let heading = $('h1', root);
    if (!heading) {
      heading = document.createElement('h1');
      heading.className = 'sr-only page-content-title';
      heading.textContent = (PAGE_META[page] || ['', ''])[0];
      root.prepend(heading);
    }
    if (!heading.id) heading.id = 'page-content-title';
    heading.tabIndex = -1;
    const labels = {
      'flt-kw': '搜索当前列表',
      'flt-status': '按状态筛选',
      'flt-proj': '按培训项目筛选',
    };
    Object.entries(labels).forEach(([id, label]) => {
      const el = $('#' + id, root);
      if (el && !el.getAttribute('aria-label')) el.setAttribute('aria-label', label);
    });
    const resultCount = $('#result-count', root);
    if (resultCount) {
      resultCount.setAttribute('role', 'status');
      resultCount.setAttribute('aria-live', 'polite');
      resultCount.setAttribute('aria-atomic', 'true');
    }
    return heading;
  }

  // ============ 页面路由 ============
  function pageSkeleton(page) {
    const metrics = '<div class="skeleton-metrics"><span></span><span></span><span></span><span></span></div>';
    const panels = '<div class="skeleton-panels"><span></span><span></span></div>';
    if (page === 'dashboard' || page === 'report')
      return `<div class="page-skeleton page-skeleton-dashboard" role="status" aria-label="正在加载运营数据" aria-busy="true"><div class="skeleton-command"></div>${metrics}${panels}</div>`;
    if (page === 'project_detail')
      return `<div class="page-skeleton page-skeleton-workspace" role="status" aria-label="正在加载项目工作区" aria-busy="true"><div class="skeleton-workspace-head"></div>${metrics}${panels}</div>`;
    return `<div class="page-skeleton page-skeleton-list" role="status" aria-label="正在加载业务列表" aria-busy="true"><div class="skeleton-summary"><span></span><span></span><span></span></div><div class="skeleton-list-panel"><div class="skeleton-list-head"></div><div class="skeleton-filter"></div><div class="skeleton-rows"><span></span><span></span><span></span><span></span><span></span></div></div></div>`;
  }

  async function renderPage() {
    const epoch = beginRouteEpoch();
    const p = state.page;
    const hasRequestedRowFocus = Boolean(state.focusId);
    setTitle(p);
    const c = $('#content');
    clearCharts();
    c.innerHTML = pageSkeleton(p);
    try {
      if (p === 'dashboard') await pageDashboard(c);
      else if (p === 'project_detail') await pageProjectDetail(c);
      else if (p === 'report') await pageReport(c);
      else if (p === 'dispatches') await pageDispatches(c);
      else if (p === 'questionnaires') await pageQuestionnaires(c);
      else if (p === 'fees') await pageFees(c);
      else if (p === 'teachers') await pageTeachers(c);
      else if (p === 'users') await pageUsers(c);
      else if (CRUD[p]) await pageCrud(c, CRUD[p]);
      if (!isRouteCurrent(epoch, c, p)) return;
      if (state.user.role === 'viewer') c.insertAdjacentHTML('afterbegin', `<div class="readonly-banner">${icon('eye')}<span><b>只读浏览模式</b> 您可以查看完整业务信息，但不能新增、编辑或执行流程操作。</span></div>`);
      const contentHeading = enhancePageSemantics(c, p);
      refreshIcons(c);
      animateCounters(c);
      if (!hasRequestedRowFocus) {
        contentHeading.focus({ preventScroll: true });
      }
    } catch (e) {
      if (!isRouteCurrent(epoch, c, p) || (e && e.name === 'AbortError')) return;
      c.innerHTML = `<div class="error-state">${icon('cloud-alert')}<h3>页面暂时无法加载</h3><p>${esc(e.message || '请稍后重试')}</p><button type="button" class="btn gray" id="retry-page">${icon('refresh-cw')}重新加载</button></div>`;
      $('#retry-page').onclick = renderPage;
      refreshIcons(c);
    }
  }

  // ============ 通用 CRUD 页 ============
  const canWrite = () => ['admin', 'manager'].includes(state.user.role);
  const readonlyAction = (page) => ({
    demands: '查看需求记录', projects: '查看项目进度', project_detail: '查看项目进度', dispatches: '查看排课记录',
    questionnaires: '查看评估记录', charges: '查看回款记录', fees: '查看课酬记录', costs: '查看成本记录', report: '查看经营分析',
  }[page] || '查看详情');
  const roleAction = (action, page) => canWrite() ? action : readonlyAction(page);

  const CRUD = {
    demands: {
      mod: 'demands', title: '培训需求', kw: '搜索：项目/单位/联系人',
      statusOptions: ['待处理', '已投标', '已立项', '进行中', '已完成', '已流标'],
      cols: [
        { k: 'title', l: '培训需求', render: (r) => `<span class="primary-cell"><b>${esc(r.title)}</b><small>#R-${String(r.id).padStart(4, '0')} · ${esc(r.unit || '需求单位待补充')}</small></span>` },
        { k: 'contact', l: '客户联系人', render: (r) => `<span class="secondary-cell"><b>${esc(r.contact || '—')}</b><small>${esc(r.phone || '未填写电话')}</small></span>` },
        { k: 'hours', l: '交付预期', render: (r) => `<span class="secondary-cell"><b>${num(r.hours)} 课时</b><small>${esc(r.expect_date || '时间待定')}</small></span>` },
        { k: 'status', l: '状态', render: (r) => tag(r.status) },
      ],
      fields: [
        { k: 'title', label: '培训项目名称', required: true, span2: true },
        { k: 'unit', label: '需求单位', required: true },
        { k: 'contact', label: '联系人' },
        { k: 'phone', label: '联系电话', type: 'tel' },
        { k: 'hours', label: '预计课时', type: 'number', required: true, min: 0.5, max: 1000, step: 0.5 },
        { k: 'expect_date', label: '期望培训时间', type: 'date' },
        { k: 'status', label: '状态', type: 'select', options: ['待处理', '已投标', '已立项', '进行中', '已完成', '已流标'], value: '待处理' },
        { k: 'content', label: '主要培训内容', type: 'textarea' },
        { k: 'teacher_req', label: '师资要求', type: 'textarea' },
        { k: 'remark', label: '备注', type: 'textarea' },
      ],
      detail: (r) => `【主要内容】\n${r.content || '（无）'}\n\n【师资要求】\n${r.teacher_req || '（无）'}\n\n【备注】\n${r.remark || '（无）'}`,
    },
    bids: {
      mod: 'bids', title: '项目投标', kw: '',
      cols: [
        { k: 'demand_title', l: '投标项目', render: (r) => `<span class="primary-cell"><b>${esc(r.demand_title)}</b><small>#B-${String(r.id).padStart(4, '0')} · ${esc(r.demand_unit || '需求单位待补充')}</small></span>` },
        { k: 'amount', l: '报价与日期', align: 'right', render: (r) => `<span class="secondary-cell align-right"><b>¥ ${money(r.amount)}</b><small>${esc(r.bid_date || '日期待定')}</small></span>` },
        { k: 'status', l: '评审状态', render: (r) => tag(r.status) },
      ],
      fields: [
        { k: 'demand_id', label: '培训需求', type: 'select', required: true, span2: true, options: [] },
        { k: 'amount', label: '投标金额（元）', type: 'number', required: true, min: 0.01, step: 0.01 },
        { k: 'bid_date', label: '投标日期', type: 'date' },
        { k: 'proposal', label: '投标方案', type: 'textarea' },
        { k: 'review', label: '评审意见', type: 'textarea' },
      ],
      detail: (r) => `【投标方案】\n${r.proposal || '（无）'}\n\n【评审意见】\n${r.review || '（无）'}`,
    },
    projects: {
      mod: 'projects', title: '培训项目', kw: '搜索：项目/单位',
      statusOptions: ['待启动', '进行中', '已完成', '已归档'],
      cols: [
        { k: 'title', l: '培训项目', render: (r) => `<span class="primary-cell"><b>${esc(r.title)}</b><small>#P-${String(r.id).padStart(4, '0')} · ${esc(r.unit || '委托单位待补充')} · ${esc(r.owner || '负责人待补充')}</small></span>` },
        { k: 'start_date', l: '交付计划', render: (r) => `<span class="secondary-cell"><b>${esc(r.start_date || '待定')} — ${esc(r.end_date || '待定')}</b><small>${num(r.hours)} 课时 · ${Number(r.participant_count || 0) ? num(r.participant_count) + ' 人' : '人数待定'}</small></span>` },
        { k: 'amount', l: '合同金额', align: 'right', render: (r) => `<b class="money-cell">¥ ${money(r.amount)}</b>` },
        { k: 'status', l: '状态', render: (r) => tag(r.status) },
      ],
      fields: [
        { k: 'title', label: '项目名称', required: true, span2: true },
        { k: 'unit', label: '委托单位', required: true },
        { k: 'owner', label: '项目负责人', placeholder: '负责统筹该项目的同事' },
        { k: 'participant_count', label: '预计参训人数', type: 'number', min: 0, max: 100000, step: 1 },
        { k: 'delivery_mode', label: '授课方式', type: 'select', options: [{ v: '', l: '待确定' }, '线下集中', '线上直播', '线上线下结合'] },
        { k: 'venue', label: '培训场地', span2: true, placeholder: '线下场地或线上会议地址' },
        { k: 'contract_no', label: '合同编号', placeholder: '用于结算与归档' },
        { k: 'hours', label: '计划课时', type: 'number', min: 0, max: 1000, step: 0.5 },
        { k: 'amount', label: '合同金额（元）', type: 'number', min: 0, step: 0.01 },
        { k: 'start_date', label: '开始日期', type: 'date' },
        { k: 'end_date', label: '结束日期', type: 'date' },
        { k: 'remark', label: '备注', type: 'textarea' },
      ],
    },
    charges: {
      mod: 'charges', title: '项目收费', kw: '',
      cols: [
        { k: 'project_title', l: '培训项目', render: (r) => `<span class="primary-cell"><b>${esc(r.project_title)}</b><small>#C-${String(r.id).padStart(4, '0')} · ${esc(r.project_unit || '委托单位待补充')}</small></span>` },
        { k: 'amount', l: '应收 / 已收', align: 'right', render: (r) => `<span class="secondary-cell align-right"><b>¥ ${money(r.amount)} / ${money(r.received)}</b><small>待收 ¥ ${money(Math.max(0, Number(r.amount || 0) - Number(r.received || 0)))}</small></span>` },
        { k: 'status', l: '状态', render: (r) => tag(r.status) },
        { k: 'charge_date', l: '收款记录', mobileHide: true, render: (r) => `<span class="secondary-cell"><b>${esc(r.charge_date || '日期待定')}</b><small>${esc(r.invoice || '未登记发票')}</small></span>` },
      ],
      fields: [
        { k: 'project_id', label: '培训项目', type: 'select', required: true, span2: true, options: [] },
        { k: 'amount', label: '应收金额（元）', type: 'number', required: true, min: 0.01, step: 0.01 },
        { k: 'charge_date', label: '最近收款日期', type: 'date' },
        { k: 'invoice', label: '发票号' },
        { k: 'remark', label: '备注', type: 'textarea' },
      ],
      detail: (r) => `【培训项目】\n${r.project_title || '（无）'}\n\n【收费情况】\n应收：¥ ${money(r.amount)}\n已收：¥ ${money(r.received)}\n状态：${r.status || '—'}\n\n【发票号】\n${r.invoice || '（未填写）'}\n\n【备注】\n${r.remark || '（无）'}`,
    },
    costs: {
      mod: 'costs', title: '成本费用', kw: '',
      cols: [
        { k: 'project_title', l: '培训项目', render: (r) => `<span class="primary-cell"><b>${esc(r.project_title)}</b><small>#E-${String(r.id).padStart(4, '0')} · ${esc(r.note || '无费用说明')}</small></span>` },
        { k: 'type', l: '成本类型', render: (r) => tag(r.type) },
        { k: 'amount', l: '金额与日期', align: 'right', render: (r) => `<span class="secondary-cell align-right"><b>¥ ${money(r.amount)}</b><small>${esc(r.cost_date || '日期待定')}</small></span>` },
      ],
      fields: [
        { k: 'project_id', label: '培训项目', type: 'select', required: true, span2: true, options: [] },
        { k: 'type', label: '成本类型', type: 'select', options: ['差旅费', '物料费', '场地费', '其他'] },
        { k: 'amount', label: '金额（元）', type: 'number', required: true, min: 0.01, step: 0.01 },
        { k: 'cost_date', label: '发生日期', type: 'date' },
        { k: 'note', label: '费用说明', type: 'textarea' },
      ],
    },
  };

  async function fillOptions(fields) {
    // 动态下拉：需求/项目
    for (const f of fields) {
      if (f.k === 'demand_id') {
        const ds = await api('/demands');
        f.options = ds.map((d) => ({ v: d.id, l: `#${d.id} ${d.title}（${d.unit}）` }));
      }
      if (f.k === 'project_id') {
        const ps = await api('/projects');
        f.options = ps.filter((p) => p.status !== '已归档').map((p) => ({ v: p.id, l: `#${p.id} ${p.title}` }));
      }
    }
    return fields;
  }

  function renderModuleSummary(mod, rows) {
    if (mod === 'charges') {
      const due = rows.reduce((s, r) => s + Number(r.amount || 0), 0);
      const received = rows.reduce((s, r) => s + Number(r.received || 0), 0);
      const rate = due ? Math.min(100, received / due * 100) : 0;
      return `<div class="module-summary four"><div><span>${icon('landmark')}</span><small>应收总额</small><b>${countTag(due, 'money')}</b></div><div><span>${icon('circle-check-big')}</span><small>已收金额</small><b>${countTag(received, 'money')}</b></div><div><span>${icon('clock-3')}</span><small>待收金额</small><b>${countTag(Math.max(0, due - received), 'money')}</b></div><div><span>${icon('gauge')}</span><small>整体收款率</small><b>${countTag(rate, 'pct')}%</b></div></div>`;
    }
    if (mod === 'costs') {
      const total = rows.reduce((s, r) => s + Number(r.amount || 0), 0);
      const types = new Set(rows.map((r) => r.type)).size;
      return `<div class="module-summary three"><div><span>${icon('receipt-text')}</span><small>累计成本</small><b>${countTag(total, 'money')}</b></div><div><span>${icon('layers-3')}</span><small>费用类型</small><b>${countTag(types)}<em>类</em></b></div><div><span>${icon('notebook-tabs')}</span><small>费用记录</small><b>${countTag(rows.length)}<em>笔</em></b></div></div>`;
    }
    if (mod === 'projects') {
      const active = rows.filter((r) => r.status === '进行中').length;
      const total = rows.reduce((s, r) => s + Number(r.amount || 0), 0);
      const hours = rows.reduce((s, r) => s + Number(r.hours || 0), 0);
      return `<div class="module-summary three"><div><span>${icon('folder-kanban')}</span><small>进行中项目</small><b>${countTag(active)}<em>个</em></b></div><div><span>${icon('badge-japanese-yen')}</span><small>合同总额</small><b>${countTag(total, 'money')}</b></div><div><span>${icon('clock')}</span><small>计划课时</small><b>${countTag(hours)}<em>课时</em></b></div></div>`;
    }
    if (mod === 'demands') {
      const pending = rows.filter((r) => r.status === '待处理').length;
      const landed = rows.filter((r) => ['已立项', '进行中', '已完成'].includes(r.status)).length;
      return `<div class="module-summary three"><div><span>${icon('inbox')}</span><small>需求总量</small><b>${countTag(rows.length)}<em>项</em></b></div><div><span>${icon('hourglass')}</span><small>待处理</small><b>${countTag(pending)}<em>项</em></b></div><div><span>${icon('badge-check')}</span><small>已转化</small><b>${countTag(landed)}<em>项</em></b></div></div>`;
    }
    return '';
  }

  async function pageCrud(c, cfg) {
    const epoch = routeEpoch;
    const contextProjectId = ['charges', 'costs'].includes(cfg.mod) ? state.contextProjectId : null;
    const savedFilter = { ...(state.filters[cfg.mod] || {}) };
    if (state.initialFilter && Object.prototype.hasOwnProperty.call(state.initialFilter, 'status')) savedFilter.status = String(state.initialFilter.status || '');
    state.filters[cfg.mod] = savedFilter;
    const initialStatus = String(savedFilter.status || '');
    const initialKeyword = String(savedFilter.kw || '');
    state.initialFilter = null;
    const [rows, contextProjects] = await Promise.all([
      api('/' + cfg.mod + (contextProjectId ? `?project_id=${encodeURIComponent(contextProjectId)}` : '')),
      contextProjectId ? api('/projects') : Promise.resolve([]),
    ]);
    if (!isRouteCurrent(epoch, c, cfg.mod)) return;
    const contextProject = contextProjects.find((p) => String(p.id) === String(contextProjectId));
    const statusState = cfg.statusOptions ? `<input type="hidden" id="flt-status" value="${esc(initialStatus)}">` : '';
    const statusTabs = cfg.statusOptions ? `<div class="filter-tabs" aria-label="按状态筛选">${statusState}<button type="button" class="${initialStatus ? '' : 'active'}" data-status="" aria-pressed="${!initialStatus}">全部 <span>${rows.length}</span></button>${cfg.statusOptions.map((s) => `<button type="button" class="${initialStatus === s ? 'active' : ''}" data-status="${esc(s)}" aria-pressed="${initialStatus === s}">${esc(s)} <span>${rows.filter((r) => r.status === s).length}</span></button>`).join('')}</div>` : '';
    const toolbar = cfg.kw ? `<div class="toolbar"><label class="search-box"><span class="sr-only">搜索${esc(cfg.title)}列表</span>${icon('search')}<input id="flt-kw" value="${esc(initialKeyword)}" placeholder="${cfg.kw}" autocomplete="off"></label><button type="button" class="btn gray" id="flt-btn">${icon('search')}搜索</button><button type="button" class="btn ghost" id="flt-clear">清除</button></div>` : '';
    const contextName = contextProject?.title || rows[0]?.project_title || `项目 #${contextProjectId || ''}`;
    c.innerHTML = `${contextProjectId ? `<div class="context-filter">${icon('link-2')}<span>项目上下文：<b>${esc(contextName)}</b></span><button type="button" id="context-project-home">返回项目工作区</button><button type="button" id="clear-context">查看全部</button></div>` : ''}<div id="module-summary">${renderModuleSummary(cfg.mod, rows)}</div><div class="card data-card">
      <div class="card-heading"><div><h2>${esc(cfg.title)}列表</h2><p>共 <span id="result-count">${rows.length}</span> 条记录</p></div>${canWrite() && contextProject?.status !== '已归档' ? `<button type="button" class="btn" id="add-btn">${icon('plus')}新增${cfg.title}</button>` : ''}</div>
      ${statusTabs}
      ${toolbar}
      <div id="tbl"></div>
    </div>`;

    const actions = buildActions(cfg, { epoch, content: c });
    const draw = (list) => {
      if (!isRouteCurrent(epoch, c, cfg.mod)) return;
      const table = $('#tbl', c);
      const resultCount = $('#result-count', c);
      if (!table || !resultCount) return;
      table.innerHTML = renderTable(cfg.cols, list, actions, cfg.mod);
      bindTableActions(table, list, actions);
      resultCount.textContent = list.length;
      const summary = $('#module-summary', c);
      if (summary) {
        summary.innerHTML = renderModuleSummary(cfg.mod, list);
        refreshIcons(summary);
      }
    };
    draw(initialStatus ? rows.filter((r) => String(r.status) === initialStatus) : rows);
    revealFocusedRow($('#tbl', c));

    let filterController = null;
    addRouteCleanup(() => filterController?.abort(), epoch);
    const runFilter = async () => {
      if (!isRouteCurrent(epoch, c, cfg.mod)) return;
      const p = new URLSearchParams();
      const kw = $('#flt-kw', c); const st = $('#flt-status', c);
      if (kw && kw.value) p.set('kw', kw.value);
      if (st && st.value) p.set('status', st.value);
      if (contextProjectId) p.set('project_id', contextProjectId);
      state.filters[cfg.mod] = { kw: kw ? kw.value.trim() : '', status: st ? st.value : '' };
      writeRouteToUrl(true);
      filterController?.abort();
      const controller = new AbortController();
      filterController = controller;
      try {
        const list = await api('/' + cfg.mod + '?' + p.toString(), { signal: controller.signal });
        if (controller === filterController) draw(list);
      } catch (error) {
        if (!error || error.name !== 'AbortError') return;
      }
    };
    const fltBtn = $('#flt-btn', c);
    if (fltBtn) fltBtn.onclick = runFilter;
    const kwInput = $('#flt-kw', c);
    if (kwInput) {
      const liveFilter = debounce(runFilter, 280);
      addRouteCleanup(liveFilter.cancel, epoch);
      kwInput.oninput = liveFilter;
      kwInput.onkeydown = (e) => {
        if (e.key === 'Enter') { e.preventDefault(); runFilter(); }
        if (e.key === 'Escape') { kwInput.value = ''; runFilter(); }
      };
    }
    const fltClear = $('#flt-clear', c);
    if (fltClear) fltClear.onclick = () => {
      filterController?.abort();
      if (kwInput) kwInput.value = '';
      if ($('#flt-status', c)) $('#flt-status', c).value = '';
      $$('.filter-tabs button', c).forEach((b) => {
        const active = b.dataset.status === '';
        b.classList.toggle('active', active);
        b.setAttribute('aria-pressed', String(active));
      });
      state.filters[cfg.mod] = { kw: '', status: '' };
      writeRouteToUrl(true);
      draw(rows);
    };
    $$('.filter-tabs button', c).forEach((btn) => { btn.onclick = () => {
      $$('.filter-tabs button', c).forEach((b) => { b.classList.remove('active'); b.setAttribute('aria-pressed', 'false'); });
      btn.classList.add('active');
      btn.setAttribute('aria-pressed', 'true');
      if ($('#flt-status', c)) $('#flt-status', c).value = btn.dataset.status;
      runFilter();
    }; });
    const addBtn = $('#add-btn', c);
    if (addBtn) addBtn.onclick = () => editForm(cfg, null, () => renderPage());
    const contextHome = $('#context-project-home', c);
    if (contextHome) contextHome.onclick = () => navigateTo('project_detail', { projectId: contextProjectId });
    const clearContext = $('#clear-context', c);
    if (clearContext) clearContext.onclick = () => { state.contextProjectId = null; navigateTo(cfg.mod, { replaceHistory: true }); };
    if (initialKeyword) await runFilter();
    refreshIcons(c);
  }

  function editableCrudConfig(cfg, row) {
    let fields = cfg.fields;
    if (cfg.mod === 'charges' && Number(row.received || 0) > 0)
      fields = fields.filter((f) => !['project_id', 'amount'].includes(f.k));
    if (cfg.mod === 'projects' && row.status === '已完成')
      fields = fields.filter((f) => !['hours', 'amount', 'start_date', 'end_date'].includes(f.k));
    return fields === cfg.fields ? cfg : { ...cfg, fields };
  }

  function buildActions(cfg, pageContext = {}) {
    const epoch = pageContext.epoch ?? routeEpoch;
    const content = pageContext.content || null;
    const stillOnPage = () => isRouteCurrent(epoch, content, cfg.mod);
    const acts = [];
    const archived = (r) => r.status === '已归档' || r.project_status === '已归档';
    const canEditRow = (r) => !archived(r);
    const canDeleteRow = (r) => {
      if (archived(r)) return false;
      if (cfg.mod === 'projects') return !['已完成', '已归档'].includes(r.status);
      if (cfg.mod === 'charges') return Number(r.received || 0) <= 0;
      return true;
    };
    if (cfg.mod === 'projects') acts.push({ l: '打开项目', cls: '', onClick: showProjectOverview });
    if (cfg.mod === 'projects' && canWrite()) {
      acts.push({ l: '启动项目', cls: 'green', show: (r) => r.status === '待启动', onClick: showProjectStart });
      acts.push({ l: '完成交付', cls: 'green', show: (r) => r.status === '进行中', onClick: (r) => showProjectTransition(r, 'complete') });
      acts.push({ l: '归档', cls: 'gray', show: (r) => r.status === '已完成', onClick: (r) => showProjectTransition(r, 'archive') });
    }
    if (cfg.detail) acts.push({ l: '详情', cls: 'gray', onClick: (r) => openModal('业务详情', `<div class="detail-text">${esc(cfg.detail(r))}</div>`, { noFoot: true, kicker: '记录信息' }) });
    if (cfg.mod === 'bids') acts.push({
      l: '中标', cls: 'green', show: (r) => canWrite() && r.status === '待评审',
      onClick: (r) => confirmBox(`确定该投标中标？中标后将自动生成立项项目，同需求的其他投标将标记为未中标。`, async () => {
        const res = await api('/bids/win', { body: { id: r.id } });
        if (!stillOnPage()) return;
        toast(res.msg || '已中标并立项');
        renderPage();
      }),
    });
    if (cfg.mod === 'charges') acts.push({
      l: '收款', cls: 'green', show: (r) => canWrite() && r.status !== '已结清' && r.project_status !== '已归档',
      onClick: (r) => {
        const outstanding = Math.max(0, Number(r.amount || 0) - Number(r.received || 0));
        openModal(`收款登记 - ${r.project_title || ''}`, `<div class="amount-callout"><small>当前待收</small><b>¥ ${money(outstanding)}</b></div>${renderForm([{ k: 'amount', label: '本次收款金额', type: 'number', required: true, min: 0.01, max: outstanding || undefined, step: 0.01, span2: true }])}`, {
          sm: true,
          onOk: async () => {
            const d = collectForm($('#modal-mask'), [{ k: 'amount', label: '本次收款金额', type: 'number', required: true, min: 0.01, max: outstanding || undefined }]);
            if (!d) return false;
            const res = await api('/charges/receive', { body: { id: r.id, amount: d.amount } });
            if (!stillOnPage()) return false;
            toast(res);
            renderPage();
          },
        });
      },
    });
    if (canWrite()) {
      acts.push({ l: '编辑', cls: 'gray', show: canEditRow, onClick: (r) => editForm(editableCrudConfig(cfg, r), r, () => renderPage()) });
      acts.push({ l: '删除', cls: 'red', show: canDeleteRow, onClick: (r) => confirmBox(`确定删除“${r.title || r.project_title || r.name || ('编号 ' + r.id)}”吗？此操作无法撤销。`, async () => { await api('/' + cfg.mod + '/delete', { body: { id: r.id } }); if (!stillOnPage()) return; toast('已删除'); renderPage(); }) });
    }
    return acts;
  }

  const PROJECT_MODULE_NAMES = { projects: '项目信息', dispatches: '课程与排期', questionnaires: '效果评估', charges: '项目回款', fees: '课酬发放', costs: '成本费用' };

  function goToProjectIssue(project, module) {
    closeModal();
    if (module === 'projects') { showProjectOverview(project); return; }
    navigateTo(module || 'project_detail', { projectId: project.id });
  }

  function showProjectStart(project) {
    const checks = [
      ['项目负责人', project.owner], ['开始日期', project.start_date], ['结束日期', project.end_date],
      ['计划课时', Number(project.hours) > 0], ['授课方式', project.delivery_mode],
      ...(Number(project.amount || 0) > 0 ? [['合同编号', project.contract_no]] : []),
    ];
    const missing = checks.filter(([, value]) => !value).map(([label]) => label);
    if (missing.length) {
      const mask = openModal('启动前完善项目资料', `<div class="transition-intro blocked">${icon('list-checks')}<span><b>还有 ${missing.length} 项启动信息待补齐</b><small>补齐后项目才能从“待启动”进入正式交付。</small></span></div><div class="start-checklist">${checks.map(([label, value]) => `<div class="${value ? 'done' : 'missing'}">${icon(value ? 'circle-check' : 'circle-dashed')}<span>${esc(label)}</span><b>${value ? '已完成' : '待补充'}</b></div>`).join('')}</div><button type="button" class="btn start-edit" id="start-edit">${icon('pencil')}完善项目信息</button>`, { noFoot: true, kicker: '项目启动清单' });
      $('#start-edit', mask).onclick = () => {
        closeModal();
        editForm(editableCrudConfig(CRUD.projects, project), project, () => renderPage());
      };
      refreshIcons(mask);
      return;
    }
    confirmBox(`确认启动“${project.title}”？启动后项目将进入正式交付阶段，并同步推进关联需求状态。`, async () => {
      const result = await api('/projects/start', { body: { id: project.id } });
      toast(result || '项目已启动');
      renderPage();
    });
  }

  async function showProjectTransition(project, action) {
    const epoch = routeEpoch;
    let check;
    try { check = await api(`/projects/check?id=${encodeURIComponent(project.id)}&action=${action}`); }
    catch (e) { return; }
    if (!isRouteCurrent(epoch)) return;
    const isArchive = action === 'archive';
    const blockers = check.blockers || [], warnings = check.warnings || [], m = check.metrics || {};
    const issueRows = (items, tone) => items.map((item) => `<button type="button" class="transition-issue ${tone}" data-transition-module="${esc(item.module || 'projects')}"><span>${icon(tone === 'blocker' ? 'circle-x' : 'triangle-alert')}</span><span><b>${esc(item.title)}</b><small>${esc(item.detail)}</small><em>前往${esc(PROJECT_MODULE_NAMES[item.module] || '项目工作区')}处理${icon('arrow-up-right')}</em></span></button>`).join('');
    const summary = `<div class="transition-summary"><div><small>计划课时</small><b>${num(m.plan_hours)}</b></div><div><small>已完成课时</small><b>${num(m.completed_hours)}</b></div><div><small>待回款</small><b>¥ ${money(m.outstanding)}</b></div><div><small>待发课酬</small><b>¥ ${money(m.pending_fee_amount)}</b></div></div>`;

    if (blockers.length) {
      const mask = openModal(isArchive ? '项目暂时不能归档' : '项目暂时不能完成交付', `${summary}<div class="transition-intro blocked">${icon('shield-alert')}<span><b>还有 ${blockers.length} 项硬性条件未满足</b><small>系统不会让状态先走、业务后补。点击具体事项即可前往处理。</small></span></div><div class="transition-list">${issueRows(blockers, 'blocker')}</div>${warnings.length ? `<div class="transition-subhead">同时需要留意</div><div class="transition-list">${issueRows(warnings, 'warning')}</div>` : ''}`, { noFoot: true, kicker: '业务闭环校验' });
      $$('[data-transition-module]', mask).forEach((btn) => { btn.onclick = () => goToProjectIssue(project, btn.dataset.transitionModule); });
      refreshIcons(mask);
      return;
    }

    const body = `${summary}<div class="transition-intro ready">${icon(isArchive ? 'archive' : 'badge-check')}<span><b>${isArchive ? '归档条件已满足' : '交付条件已满足'}</b><small>${isArchive ? '归档后项目将退出日常运营视图。' : '标记完成只代表课程交付结束，后续财务与评估事项仍会保留。'}</small></span></div>${warnings.length ? `<div class="transition-subhead">继续前请确认 ${warnings.length} 项提醒</div><div class="transition-list">${issueRows(warnings, 'warning')}</div>` : '<div class="transition-clear">当前没有需要额外确认的事项。</div>'}`;
    const mask = openModal(isArchive ? `归档项目 · ${project.title}` : `完成交付 · ${project.title}`, body, {
      kicker: isArchive ? '退出日常运营视图' : '课程交付闭环', sm: true,
      okText: isArchive ? '确认归档项目' : '确认完成交付', okIcon: isArchive ? 'archive' : 'badge-check',
      onOk: async () => {
        const result = await api(isArchive ? '/projects/archive' : '/projects/complete', { body: { id: project.id } });
        toast(result.msg || (isArchive ? '项目已归档' : '项目已完成交付'));
        if (state.page === 'project_detail') navigateTo('projects', { replaceHistory: true });
        else renderPage();
      },
    });
    $$('[data-transition-module]', mask).forEach((btn) => { btn.onclick = () => goToProjectIssue(project, btn.dataset.transitionModule); });
    refreshIcons(mask);
  }

  async function editForm(cfg, row, done) {
    const epoch = routeEpoch;
    let fields = await fillOptions(JSON.parse(JSON.stringify(cfg.fields)));
    if (!isRouteCurrent(epoch)) return;
    const contextProjectId = !row && state.contextProjectId && fields.some((field) => field.k === 'project_id') ? state.contextProjectId : null;
    if (contextProjectId) fields = fields.map((field) => field.k === 'project_id' ? { ...field, disabled: true } : field);
    const initialData = row || (contextProjectId ? { project_id: contextProjectId } : null);
    const contextNote = contextProjectId ? `<div class="context-create-note">${icon('link-2')}已关联当前项目，保存后会返回本项目视图。</div>` : '';
    const formMask = openModal((row ? '编辑' : '新增') + cfg.title, `${contextNote}${renderForm(fields, initialData)}`, {
      onOk: async () => {
        if (!isRouteCurrent(epoch) || !formMask.isConnected || $('#modal-mask') !== formMask) return false;
        const d = collectForm(formMask, fields);
        if (!d) return false;
        if (cfg.mod === 'projects' && d.start_date && d.end_date && d.start_date > d.end_date) { toast('项目结束日期不能早于开始日期', true); return false; }
        if (cfg.mod === 'charges' && Number(d.received || 0) > Number(d.amount || 0)) { toast('已收金额不能大于应收金额', true); return false; }
        const payload = row ? { ...row, ...d, id: row.id } : { ...d };
        if (!row && cfg.mod === 'bids') payload.status = '待评审';
        if (!row && cfg.mod === 'projects') payload.status = '待启动';
        if (!row && cfg.mod === 'charges') { payload.received = 0; payload.status = '未收费'; }
        await api('/' + cfg.mod, { body: payload });
        if (!isRouteCurrent(epoch) || !formMask.isConnected || $('#modal-mask') !== formMask) return false;
        toast('保存成功');
        done && done();
      },
    });
  }

  function showProjectOverview(project) {
    navigateTo('project_detail', { projectId: project.id });
  }

  async function pageProjectDetail(c) {
    const epoch = routeEpoch;
    const projects = await api('/projects');
    if (!isRouteCurrent(epoch, c, 'project_detail')) return;
    const project = projects.find((p) => String(p.id) === String(state.projectId));
    if (!project) throw new Error('未找到该项目，可能已被删除');
    const [dispatches, questionnaires, charges, fees, costs, demands, bids] = await Promise.all([
      api('/dispatches?project_id=' + project.id), api('/questionnaires?project_id=' + project.id), api('/charges?project_id=' + project.id),
      api('/fees?project_id=' + project.id), api('/costs?project_id=' + project.id), api('/demands'), api('/bids?demand_id=' + (project.demand_id || '')),
    ]);
    if (!isRouteCurrent(epoch, c, 'project_detail')) return;
    const demand = demands.find((d) => String(d.id) === String(project.demand_id));
    const validDispatches = dispatches.filter((d) => d.status !== '已拒绝');
    const scheduledHours = validDispatches.reduce((s, d) => s + Number(d.hours || 0), 0);
    const confirmedHours = dispatches.filter((d) => ['已确认', '已完成'].includes(d.status)).reduce((s, d) => s + Number(d.hours || 0), 0);
    const completedHours = dispatches.filter((d) => d.status === '已完成').reduce((s, d) => s + Number(d.hours || 0), 0);
    const missingHours = Math.max(0, Number(project.hours || 0) - scheduledHours);
    const received = charges.reduce((s, r) => s + Number(r.received || 0), 0);
    const due = charges.reduce((s, r) => s + Number(r.amount || 0), 0) || Number(project.amount || 0);
    const outstanding = Math.max(0, due - received);
    const feeTotal = fees.reduce((s, r) => s + Number(r.amount || 0), 0);
    const feePending = fees.filter((r) => r.status === '待发放').reduce((s, r) => s + Number(r.amount || 0), 0);
    const costTotal = costs.reduce((s, r) => s + Number(r.amount || 0), 0);
    const estimatedBalance = Number(project.amount || 0) - feeTotal - costTotal;
    const sendTotal = questionnaires.reduce((s, q) => s + Number(q.send_total || 0), 0);
    const recvTotal = questionnaires.reduce((s, q) => s + Number(q.recv_total || 0), 0);
    const responseRate = sendTotal ? Math.round(recvTotal / sendTotal * 100) : 0;
    const today = new Date(); today.setHours(0, 0, 0, 0);
    const dayDiff = (date) => date ? Math.ceil((new Date(date + 'T00:00:00') - today) / 86400000) : null;
    const startIn = dayDiff(project.start_date);
    const urgentPending = dispatches.filter((d) => ['待发送', '已拒绝'].includes(d.status) && dayDiff(d.teach_date) !== null && dayDiff(d.teach_date) <= 3);
    const materialRisks = dispatches.filter((d) => !['已拒绝', '已完成'].includes(d.status) && dayDiff(d.teach_date) !== null && dayDiff(d.teach_date) <= 3 && d.material_status !== '已就绪');
    const awaitingConfirm = dispatches.filter((d) => d.status === '已发送');
    const issueMap = new Map();
    const upsertIssue = (key, item) => {
      const old = issueMap.get(key);
      if (!old) { issueMap.set(key, { ...item, notes: [item.detail], titles: [item.title], actions: [item.action] }); return; }
      const winner = item.score > old.score ? item : old;
      const notes = [...new Set([...(old.notes || [old.detail]), item.detail].filter(Boolean))];
      const titles = [...new Set([...(old.titles || [old.title]), item.title].filter(Boolean))];
      const actions = [...new Set([...(old.actions || [old.action]), item.action].filter(Boolean))];
      issueMap.set(key, { ...winner, score: Math.max(item.score, old.score), notes, titles, actions, title: mergeContextLabels(titles), action: actions.join(' + '), detail: notes.join('；') });
    };
    urgentPending.forEach((d) => upsertIssue(`dispatch:${d.id}`, { tone: 'critical', score: dayDiff(d.teach_date) <= 1 ? 100 : 86, type: dayDiff(d.teach_date) <= 1 ? '紧急交付' : '交付风险', title: `${d.subject} · ${d.status === '已拒绝' ? '讲师已拒绝' : '通知尚未记录'}`, detail: `${d.teach_date} 开课 · ${d.teacher_name || '讲师待定'} · ${num(d.hours)} 课时`, page: 'dispatches', focusId: d.id, action: d.status === '已拒绝' ? '重新安排讲师' : '记录师资通知' }));
    materialRisks.forEach((d) => upsertIssue(`dispatch:${d.id}`, { tone: dayDiff(d.teach_date) <= 1 ? 'critical' : 'warning', score: dayDiff(d.teach_date) <= 1 ? 88 : 68, type: '交付准备', title: `${d.subject} · 材料${d.material_status || '状态待补充'}`, detail: `${d.teach_date} 开课 · ${d.venue || '场地尚未确定'}`, page: 'dispatches', focusId: d.id, action: '补齐交付准备' }));
    awaitingConfirm.forEach((d) => upsertIssue(`dispatch:${d.id}`, { tone: 'warning', score: 78, type: '师资确认', title: `${d.subject} · 等待讲师确认`, detail: `${d.teacher_name || '待定讲师'} · 确认截止 ${d.confirm_deadline || '待定'}`, page: 'dispatches', focusId: d.id, action: '记录确认结果' }));
    if (missingHours > 0) upsertIssue('schedule-gap', { tone: 'warning', score: 74, type: '排课缺口', title: `计划课时尚缺 ${num(missingHours)} 课时`, detail: `计划 ${num(project.hours)} 课时，目前已有效排课 ${num(scheduledHours)} 课时`, page: 'dispatches', action: `安排 ${num(missingHours)} 课时` });
    if (outstanding > 0) upsertIssue('payment', { tone: 'neutral', score: 52, type: '回款跟进', title: `还有 ¥ ${money(outstanding)} 尚未回款`, detail: `当前回款率 ${due ? Math.round(received / due * 100) : 0}%`, page: 'charges', action: '登记本次回款' });
    if (!questionnaires.length) upsertIssue('evaluation', { tone: 'neutral', score: 42, type: '评估准备', title: '本项目尚未创建效果评估', detail: '建议在课程结束前准备并发布问卷', page: 'questionnaires', action: '创建效果评估' });
    if (feePending > 0) upsertIssue('fees', { tone: 'neutral', score: 36, type: '课酬结算', title: `待发放课酬 ¥ ${money(feePending)}`, detail: `${fees.filter((r) => r.status === '待发放').length} 笔课酬等待处理`, page: 'fees', action: '核对待发课酬' });
    const issues = [...issueMap.values()].sort((a, b) => b.score - a.score);
    if (!canWrite()) issues.forEach((item) => { item.action = readonlyAction(item.page); });
    const primary = issues[0] || { tone: 'good', type: '项目状态', title: '当前没有阻塞项目推进的事项', detail: '继续按计划跟踪交付与结算进度', page: 'projects', action: roleAction('检查项目进度', 'projects') };
    const health = issues.some((x) => x.tone === 'critical') ? ['有风险', 'critical'] : issues.some((x) => x.tone === 'warning') ? ['需关注', 'warning'] : ['正常', 'good'];
    const milestone = startIn === null ? '开课日期待定' : startIn < 0 ? '项目交付中' : startIn === 0 ? '今天开课' : startIn === 1 ? '明天开课' : `距离开课 ${startIn} 天`;
    const paymentRate = due ? Math.min(100, received / due * 100) : 0;
    const deliveryRate = Number(project.hours || 0) ? Math.min(100, completedHours / Number(project.hours) * 100) : 0;
    const projectJourney = [
      { label: '项目资料', value: project.contract_no && project.owner && project.start_date ? '信息已齐' : '仍需补充', page: 'projects', status: project.contract_no && project.owner && project.start_date ? 'done' : 'current', ico: 'file-check-2' },
      { label: '课程排期', value: `${num(scheduledHours)} / ${num(project.hours)} 课时`, page: 'dispatches', status: scheduledHours >= Number(project.hours || 0) && Number(project.hours || 0) > 0 ? 'done' : scheduledHours > 0 ? 'current' : 'todo', ico: 'calendar-clock' },
      { label: '课程交付', value: `${num(completedHours)} 已完成 · ${num(confirmedHours)} 已确认`, page: 'dispatches', status: completedHours >= Number(project.hours || 0) && Number(project.hours || 0) > 0 ? 'done' : confirmedHours > 0 ? 'current' : 'todo', ico: 'badge-check' },
      { label: '效果评估', value: questionnaires.length ? `${questionnaires.length} 份问卷 · ${responseRate}% 回收` : '尚未创建问卷', page: 'questionnaires', status: responseRate >= 60 ? 'done' : questionnaires.length ? 'current' : 'todo', ico: 'clipboard-check' },
      { label: '回款结算', value: `${paymentRate.toFixed(0)}% 已回款`, page: 'charges', status: paymentRate >= 100 ? 'done' : received > 0 ? 'current' : 'todo', ico: 'circle-dollar-sign' },
    ];

    c.innerHTML = `
      <div class="workspace-back"><button type="button" id="project-back">${icon('arrow-left')}返回项目总览</button><span>项目 #P-${String(project.id).padStart(4, '0')}</span></div>
      ${project.status === '已归档' ? `<div class="readonly-banner">${icon('archive')}<span><b>项目已归档</b> 交付与财务事实已锁定，可继续查看但不能再修改业务记录。</span></div>` : ''}<section class="project-workspace-head">
        <div><div class="workspace-kicker"><span class="health-dot ${health[1]}"></span>${health[0]} · ${esc(milestone)}</div><h1>${esc(project.title)}</h1><p>${esc(project.unit || '委托单位待补充')}<span></span>${esc(project.owner || '负责人待补充')}<span></span>${esc(project.delivery_mode || '授课方式待定')}<span></span>${esc(project.start_date || '待定')} — ${esc(project.end_date || '待定')}</p></div>
        <div class="workspace-head-actions">${canWrite() ? `${project.status !== '已归档' ? `<button type="button" class="btn gray" id="project-edit">${icon('pencil')}编辑项目信息</button>` : ''}${project.status === '待启动' ? `<button type="button" class="btn green" id="project-start">${icon('play')}启动项目</button>` : project.status === '进行中' ? `<button type="button" class="btn green" id="project-transition" data-action="complete">${icon('badge-check')}完成交付</button>` : project.status === '已完成' ? `<button type="button" class="btn gray" id="project-transition" data-action="archive">${icon('archive')}归档项目</button>` : ''}` : ''}<button type="button" class="btn" data-project-goto="${primary.page}" ${primary.focusId ? `data-focus-id="${primary.focusId}"` : ''}>${icon('arrow-up-right')}${esc(primary.action)}</button></div>
      </section>

      <nav class="project-journey" aria-label="项目推进路径">${projectJourney.map((step, index) => `<button type="button" class="${step.status}" data-project-goto="${step.page}" ${step.page === 'projects' ? `data-focus-id="${project.id}"` : ''}><span class="journey-index">0${index + 1}</span><span class="journey-icon">${icon(step.ico)}</span><span><b>${step.label}</b><small>${esc(step.value)}</small></span>${step.status === 'done' ? icon('check') : icon('arrow-right')}</button>`).join('')}</nav>

      <section class="project-next ${primary.tone}"><span class="project-next-index">下一步</span><div><small>${esc(primary.type)}</small><h2>${esc(primary.title)}</h2><p>${esc(primary.detail)}</p></div><button type="button" data-project-goto="${primary.page}" ${primary.focusId ? `data-focus-id="${primary.focusId}"` : ''}>${esc(primary.action)}${icon('arrow-right')}</button></section>

      <div class="project-metrics workspace-metrics">
        <div><small>计划 / 已排课时</small><b>${num(project.hours)} <em>/ ${num(scheduledHours)}</em></b><span class="metric-line"><i style="width:${Number(project.hours) ? Math.min(100, scheduledHours / Number(project.hours) * 100) : 0}%"></i></span></div>
        <div><small>已完成 / 已确认课时</small><b>${num(completedHours)} <em>/ ${num(confirmedHours)}</em></b><span class="metric-line"><i style="width:${deliveryRate}%"></i></span></div>
        <div><small>回款进度</small><b>${paymentRate.toFixed(0)}<em>%</em></b><span class="metric-line"><i style="width:${paymentRate}%"></i></span></div>
        <div><small>按已录成本估算余额</small><b>¥ ${money(estimatedBalance)}</b><span class="metric-note">未完整排课时不代表最终利润</span></div>
      </div>

      <div class="workspace-grid">
        <section class="workspace-panel risk-panel">
          <div class="workspace-panel-head"><div><span>${canWrite() ? '待处理事项' : '需关注事项'}</span><small>同一业务记录已合并风险，按影响程度排序</small></div><b>${issues.length || 0}</b></div>
          <div class="workspace-tasks">${issues.length ? issues.slice(0, 5).map((item) => `<button type="button" class="workspace-task ${item.tone}" data-project-goto="${item.page}" ${item.focusId ? `data-focus-id="${item.focusId}"` : ''}><span class="task-signal"></span><span><small>${esc(item.type)}</small><b>${esc(item.title)}</b><em>${esc(item.detail)}</em><strong>${esc(item.action)}${icon('arrow-right')}</strong></span></button>`).join('') : `<div class="workspace-clear">${icon('badge-check')}<div><b>当前没有待处理风险</b><span>项目正在按计划推进，可以检查未来课程与回款节点</span></div></div>`}</div>
        </section>

        <section class="workspace-panel delivery-panel">
          <div class="workspace-panel-head"><div><span>交付准备度</span><small>已完成 ${num(completedHours)} / 已确认 ${num(confirmedHours)} / 已排 ${num(scheduledHours)} 课时</small></div><button type="button" data-project-goto="dispatches">查看全部${icon('arrow-right')}</button></div>
          <div class="delivery-rows">${dispatches.length ? dispatches.slice(0, 5).map((d) => `<div class="delivery-row"><time><b>${esc((d.teach_date || '').slice(8) || '--')}</b><small>${esc((d.teach_date || '').slice(5, 7) || '--')}月</small></time><span><b>${esc(d.subject)}</b><small>${esc(d.teacher_name || '讲师待定')} · ${esc([d.start_time, d.end_time].filter(Boolean).join('—') || `${num(d.hours)} 课时`)} · ${esc(d.venue || '场地待定')}</small></span><span class="delivery-tags">${tag(d.material_status || '材料待补充')}${tag(d.status)}</span></div>`).join('') : `<div class="workspace-empty">${icon('calendar-plus')}<b>尚未安排课程</b><span>先完成讲师与排期安排</span></div>`}</div>
        </section>

        <section class="workspace-panel finance-panel">
          <div class="workspace-panel-head"><div><span>财务结算</span><small>合同、回款、课酬与已录成本</small></div><button type="button" data-project-goto="charges">进入结算${icon('arrow-right')}</button></div>
          <div class="finance-focus"><div><small>合同 / 应收</small><b>¥ ${money(due)}</b></div><div><small>已回款</small><b>¥ ${money(received)}</b></div><div><small>待回款</small><b>¥ ${money(outstanding)}</b></div></div>
          <div class="finance-bar"><span><i style="width:${paymentRate}%"></i></span><small>${paymentRate.toFixed(0)}% 已回款</small></div>
          <div class="finance-ledger"><p><span>已录课酬</span><b>¥ ${money(feeTotal)}</b></p><p><span>其他成本</span><b>¥ ${money(costTotal)}</b></p><p><span>待发课酬</span><b>¥ ${money(feePending)}</b></p></div>
          <details class="finance-definition"><summary>${icon('calculator')}估算余额 = 合同额 − 已录课酬 − 已录成本${icon('chevron-down')}</summary><div><p>未来未排课程的课酬和尚未登记的费用不会自动计入。</p><button type="button" data-project-goto="dispatches">检查排课</button><button type="button" data-project-goto="costs">${canWrite() ? '补录成本' : '查看成本'}</button></div></details>
        </section>

        <section class="workspace-panel evaluation-panel">
          <div class="workspace-panel-head"><div><span>效果评估</span><small>反馈回收与培训质量</small></div><button type="button" data-project-goto="questionnaires">${canWrite() ? '管理评估' : '查看评估'}${icon('arrow-right')}</button></div>
          ${questionnaires.length ? `<div class="evaluation-focus"><div><small>问卷</small><b>${questionnaires.length}</b><span>份</span></div><div><small>计划触达</small><b>${sendTotal}</b><span>人次</span></div><div><small>回收</small><b>${recvTotal}</b><span>${responseRate}%</span></div></div><div class="evaluation-note">${responseRate >= 60 ? icon('badge-check') + '当前回收率达到基础复盘要求' : icon('circle-alert') + '回收率不足 60%，建议再次触达学员'}</div>` : `<div class="workspace-empty">${icon('clipboard-plus')}<b>尚未创建评估问卷</b><span>在交付结束前准备反馈回收</span><button type="button" data-project-goto="questionnaires">${canWrite() ? '创建评估' : '查看评估'}</button></div>`}
        </section>
      </div>

      <section class="workspace-panel demand-brief">
        <div class="workspace-panel-head"><div><span>客户需求底稿</span><small>${esc(demand ? demand.title : '该项目未关联原始需求')}</small></div><span>${project.bid_id ? `中标记录 #${project.bid_id}` : '独立立项'}</span></div>
        <div class="demand-brief-grid"><div><small>培训目标与内容</small><p>${esc(demand?.content || project.remark || '尚未记录详细培训目标')}</p></div><div><small>师资要求</small><p>${esc(demand?.teacher_req || '尚未记录师资要求')}</p></div><div><small>交付信息</small><p>${esc([project.contract_no ? `合同 ${project.contract_no}` : '合同编号待补充', project.participant_count ? `${project.participant_count} 人` : '人数待定', project.venue || '场地待定'].join(' · '))}</p><small>客户联系人</small><p>${esc(demand ? `${demand.contact || '—'} · ${demand.phone || '—'}` : '—')}</p></div></div>
      </section>`;

    $('#project-back').onclick = () => navigateTo('projects');
    const projectEdit = $('#project-edit');
    if (projectEdit) projectEdit.onclick = () => editForm(editableCrudConfig(CRUD.projects, project), project, () => { state.page = 'project_detail'; renderLayout(); });
    const projectStart = $('#project-start');
    if (projectStart) projectStart.onclick = () => showProjectStart(project);
    const projectTransition = $('#project-transition');
    if (projectTransition) projectTransition.onclick = () => showProjectTransition(project, projectTransition.dataset.action);
    $$('[data-project-goto]').forEach((btn) => { btn.onclick = () => navigateTo(btn.dataset.projectGoto, {
      projectId: btn.dataset.projectGoto === 'projects' ? null : project.id,
      focusId: btn.dataset.focusId || null,
    }); });
    refreshIcons(c);
  }

  // ============ 统计看板 ============
  async function pageDashboard(c) {
    const epoch = routeEpoch;
    const [d, demands, projects, dispatches, charges, fees] = await Promise.all([
      api('/stats/overview'), api('/demands'), api('/projects'), api('/dispatches'), api('/charges'), api('/fees'),
    ]);
    if (!isRouteCurrent(epoch, c, 'dashboard')) return;
    const now = new Date(); now.setHours(0, 0, 0, 0);
    const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    const dayDiff = (date) => date ? Math.ceil((new Date(date + 'T00:00:00') - now) / 86400000) : null;
    const outstanding = charges.reduce((s, r) => s + Math.max(0, Number(r.amount || 0) - Number(r.received || 0)), 0);
    const activeProjects = projects.filter((p) => ['待启动', '进行中'].includes(p.status));
    const upcomingAll = dispatches.filter((x) => x.teach_date >= today && x.status !== '已拒绝').sort((a, b) => a.teach_date.localeCompare(b.teach_date));
    const upcoming = upcomingAll.slice(0, 5);
    const nextSevenDays = upcomingAll.filter((x) => dayDiff(x.teach_date) <= 7);
    const taskMap = new Map();
    const upsertTask = (task) => {
      const old = taskMap.get(task.key);
      if (!old) { taskMap.set(task.key, { ...task, notes: [task.meta], titles: [task.title], actions: [task.action] }); return; }
      const winner = task.score > old.score ? task : old;
      const notes = [...new Set([...(old.notes || [old.meta]), task.meta].filter(Boolean))];
      const titles = [...new Set([...(old.titles || [old.title]), task.title].filter(Boolean))];
      const actions = [...new Set([...(old.actions || [old.action]), task.action].filter(Boolean))];
      taskMap.set(task.key, { ...winner, score: Math.max(old.score, task.score), notes, titles, actions, title: mergeContextLabels(titles), action: actions.join(' + '), meta: notes.join('；') });
    };
    dispatches.filter((x) => ['待发送', '已拒绝'].includes(x.status)).forEach((x) => {
      const days = dayDiff(x.teach_date);
      upsertTask({ key: `dispatch:${x.id}`, page: 'dispatches', projectId: x.project_id, focusId: x.id, tone: days !== null && days <= 3 ? 'critical' : 'warning', score: days !== null && days <= 1 ? 100 : 82, label: days !== null && days < 0 ? `已逾期 ${Math.abs(days)} 天` : days === 0 ? '今天开课' : days === 1 ? '明天开课' : days !== null && days > 1 ? `${days} 天后开课` : '排课待处理', title: `${x.subject} · ${x.status === '已拒绝' ? '讲师已拒绝' : '通知尚未记录'}`, meta: `${x.project_title} · ${x.teacher_name || '讲师待定'}`, action: x.status === '已拒绝' ? '重新安排讲师' : '记录师资通知' });
    });
    dispatches.filter((x) => x.status === '已发送' && dayDiff(x.teach_date) !== null && dayDiff(x.teach_date) <= 3).forEach((x) => {
      upsertTask({ key: `dispatch:${x.id}`, page: 'dispatches', projectId: x.project_id, focusId: x.id, tone: 'critical', score: 92, label: '等待确认', title: `${x.subject} · 讲师尚未确认`, meta: `${x.teach_date} · ${x.teacher_name || '讲师待定'}`, action: '记录确认结果' });
    });
    dispatches.filter((x) => !['已拒绝', '已完成'].includes(x.status) && dayDiff(x.teach_date) !== null && dayDiff(x.teach_date) <= 3 && x.material_status !== '已就绪').forEach((x) => {
      upsertTask({ key: `dispatch:${x.id}`, page: 'dispatches', projectId: x.project_id, focusId: x.id, tone: dayDiff(x.teach_date) <= 1 ? 'critical' : 'warning', score: dayDiff(x.teach_date) <= 1 ? 88 : 68, label: '交付准备', title: `${x.subject} · 材料${x.material_status || '状态待补充'}`, meta: `${x.teach_date} · ${x.venue || '场地尚未确定'}`, action: '补齐交付准备' });
    });
    activeProjects.forEach((project) => {
      const projectDispatches = dispatches.filter((x) => String(x.project_id) === String(project.id) && x.status !== '已拒绝');
      const scheduled = projectDispatches.reduce((s, x) => s + Number(x.hours || 0), 0);
      const gap = Math.max(0, Number(project.hours || 0) - scheduled);
      if (gap > 0) upsertTask({ key: `project:${project.id}:schedule`, page: 'dispatches', projectId: project.id, tone: 'warning', score: 74, label: '排课缺口', title: `${project.title} 尚缺 ${num(gap)} 课时`, meta: `计划 ${num(project.hours)} 课时 · 已排 ${num(scheduled)} 课时`, action: `安排 ${num(gap)} 课时` });
    });
    charges.filter((x) => x.status !== '已结清').forEach((x) => {
      const left = Math.max(0, Number(x.amount || 0) - Number(x.received || 0));
      if (left > 0) upsertTask({ key: `charge:${x.id}`, page: 'charges', projectId: x.project_id, focusId: x.id, tone: 'neutral', score: 48, label: '回款跟进', title: `${x.project_title} 待收 ¥ ${money(left)}`, meta: `${x.project_unit || '委托单位待补充'} · 最近收款 ${x.charge_date || '尚未登记'}`, action: '登记本次回款' });
    });
    demands.filter((x) => x.status === '待处理').forEach((x) => upsertTask({ key: `demand:${x.id}`, page: 'demands', focusId: x.id, tone: 'neutral', score: 44, label: '需求判断', title: x.title, meta: `${x.unit} · 期望 ${x.expect_date || '时间待定'}`, action: '判断并推进需求' }));
    const feePending = fees.filter((x) => x.status === '待发放').reduce((s, x) => s + Number(x.amount || 0), 0);
    if (feePending > 0) upsertTask({ key: 'fees:pending', page: 'fees', focusId: fees.find((x) => x.status === '待发放')?.id, tone: 'neutral', score: 40, label: '课酬结算', title: `${fees.filter((x) => x.status === '待发放').length} 笔课酬待发放`, meta: `合计 ¥ ${money(feePending)}`, action: '核对待发课酬' });
    const tasks = [...taskMap.values()];
    tasks.sort((a, b) => b.score - a.score);
    if (!canWrite()) tasks.forEach((task) => { task.action = readonlyAction(task.page); });
    const criticalCount = tasks.filter((x) => x.tone === 'critical').length;
    const healthRank = { critical: 0, warning: 1, good: 2 };
    const projectRows = activeProjects.map((project) => {
      const pd = dispatches.filter((x) => String(x.project_id) === String(project.id));
      const valid = pd.filter((x) => x.status !== '已拒绝');
      const scheduled = valid.reduce((s, x) => s + Number(x.hours || 0), 0);
      const confirmed = pd.filter((x) => ['已确认', '已完成'].includes(x.status)).reduce((s, x) => s + Number(x.hours || 0), 0);
      const completed = pd.filter((x) => x.status === '已完成').reduce((s, x) => s + Number(x.hours || 0), 0);
      const gap = Math.max(0, Number(project.hours || 0) - scheduled);
      const pc = charges.filter((x) => String(x.project_id) === String(project.id));
      const left = pc.reduce((s, x) => s + Math.max(0, Number(x.amount || 0) - Number(x.received || 0)), 0);
      const pending = pd.filter((x) => ['待发送', '已发送', '已拒绝'].includes(x.status));
      const materialRisk = pd.some((x) => { const days = dayDiff(x.teach_date); return x.status !== '已拒绝' && days !== null && days <= 3 && x.material_status !== '已就绪'; });
      const tone = pending.some((x) => { const days = dayDiff(x.teach_date); return ['待发送', '已拒绝'].includes(x.status) && days !== null && days <= 3; }) || materialRisk ? 'critical' : gap > 0 || pending.length || left > 0 ? 'warning' : 'good';
      const next = pending.length ? `${pending.length} 场师资待确认` : materialRisk ? '完成交付材料与场地准备' : gap > 0 ? `补排 ${num(gap)} 课时` : left > 0 ? `跟进 ¥ ${money(left)} 回款` : '按计划推进';
      return { ...project, scheduled, confirmed, completed, gap, left, tone, next };
    }).sort((a, b) => healthRank[a.tone] - healthRank[b.tone]);
    const recordedCost = Number(d.fee_amount || 0) + Number(d.cost_amount || 0);
    const stages = ['待处理', '已投标', '已立项', '进行中', '已完成'].map((status) => ({ status, count: demands.filter((x) => x.status === status).length }));
    const pendingDemands = demands.filter((x) => x.status === '待处理').length;
    const todayLabel = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(now);
    const warningCount = tasks.filter((x) => x.tone === 'warning').length;
    const neutralCount = tasks.filter((x) => x.tone === 'neutral').length;
    const healthTone = criticalCount ? 'critical' : warningCount ? 'warning' : 'good';
    const healthText = criticalCount ? '有紧急事项' : warningCount ? '需要关注' : '运行正常';
    const rowHtml = (x) => `
      <button type="button" class="v9-row ${x.tone}" data-goto="${x.page}" ${x.projectId ? `data-project-id="${x.projectId}"` : ''} ${x.focusId ? `data-focus-id="${x.focusId}"` : ''}>
        <span class="v9-rail"></span>
        <span class="v9-row-main"><small>${esc(x.label)} · ${esc(x.meta)}</small><b>${esc(x.title)}</b></span>
        <span class="v9-row-action">${esc(x.action)}${icon('arrow-up-right')}</span>
      </button>`;
    const dayGroups = [];
    upcomingAll.slice(0, 8).forEach((x) => {
      const g = dayGroups.find((dd) => dd.date === x.teach_date);
      if (g) g.items.push(x); else dayGroups.push({ date: x.teach_date, items: [x] });
    });
    const tlItem = (x) => {
      const days = dayDiff(x.teach_date);
      const materialPending = x.material_status !== '已就绪' && days !== null && days <= 3;
      const tone = ['待发送', '已拒绝'].includes(x.status) || materialPending ? 'critical' : x.status === '已发送' ? 'warning' : 'good';
      return `<button type="button" class="v9-tl-item ${tone}" data-goto="dispatches" data-project-id="${x.project_id}" data-focus-id="${x.id}"><i></i><span><b>${esc(x.subject)}</b><small>${esc(x.teacher_name || '讲师待定')} · ${esc([x.start_time, x.end_time].filter(Boolean).join('—') || `${num(x.hours)} 课时`)} · ${esc(x.status)}${materialPending ? ' · 材料待准备' : ''}</small></span>${icon('arrow-up-right')}</button>`;
    };

    c.innerHTML = `
      <div class="v9-cockpit">
        <header class="v9-hero">
          <div class="v9-hero-l">
            <span class="v9-date">${esc(todayLabel)} · 运营台账</span>
            <h1>今日<em>运营</em></h1>
            <div class="v9-health ${healthTone}"><i></i><span>${healthText}</span><b>${criticalCount}</b><small>紧急</small><b>${warningCount}</b><small>关注</small><b>${tasks.length}</b><small>待推进</small></div>
          </div>
          <div class="v9-kpis" aria-label="今日运营指标">
            <button type="button" data-goto="demands" data-status="待处理"><small>待处理需求</small><b>${countTag(pendingDemands)}</b></button>
            <button type="button" data-goto="projects"><small>交付中项目</small><b>${countTag(activeProjects.length)}</b></button>
            <button type="button" data-goto="dispatches"><small>7 日内开课</small><b>${countTag(nextSevenDays.length)}</b></button>
            <button type="button" class="${outstanding > 0 ? 'attention' : ''}" data-goto="charges" aria-label="待回款 ${money(outstanding)} 元"><small>待回款</small><b>${countTag(outstanding, 'compact')}</b><em class="v9-kpi-exact">¥ ${money(outstanding)}</em></button>
          </div>
        </header>

        <section class="v9-queue" aria-labelledby="v9-queue-title">
          <div class="v9-queue-head">
            <div class="v9-sec"><span>NOW / 01</span><h2 id="v9-queue-title">${canWrite() ? '待处理事项' : '需关注事项'}</h2></div>
            <div class="v9-chips">
              <button type="button" class="v9-chip-f active" data-tone="" aria-pressed="true">全部 ${tasks.length}</button>
              <button type="button" class="v9-chip-f" data-tone="critical" aria-pressed="false">紧急 ${criticalCount}</button>
              <button type="button" class="v9-chip-f" data-tone="warning" aria-pressed="false">关注 ${warningCount}</button>
              <button type="button" class="v9-chip-f" data-tone="neutral" aria-pressed="false">常规 ${neutralCount}</button>
            </div>
            ${tasks.length > 4 ? `<button type="button" class="v9-more" id="priority-toggle" aria-expanded="false" aria-controls="priority-list"><span>查看全部</span>${icon('chevron-down')}</button>` : ''}
          </div>
          <div class="v9-queue-list" id="priority-list"></div>
        </section>

        <div class="v9-grid">
          <section class="v9-pane">
            <div class="v9-sec"><span>SCHEDULE / 02</span><h2>近期开课</h2><button type="button" data-goto="dispatches">完整排期${icon('arrow-right')}</button></div>
            <div class="v9-tl">${dayGroups.length ? dayGroups.map((g) => {
              const days = dayDiff(g.date);
              const rel = days === 0 ? '今天' : days === 1 ? '明天' : `${days} 天后`;
              return `<div class="v9-day"><time><b>${esc((g.date || '').slice(8) || '--')}</b><small>${esc((g.date || '').slice(5, 7) || '--')}月 · ${rel}</small></time><div>${g.items.map(tlItem).join('')}</div></div>`;
            }).join('') : `<div class="v9-empty">${icon('calendar-check')}<b>未来没有待执行课程</b><span>新的课程安排会显示在这里。</span>${canWrite() ? `<button type="button" data-goto="dispatches">新增课程安排</button>` : ''}</div>`}</div>
          </section>
          <section class="v9-pane">
            <div class="v9-sec"><span>DELIVERY / 03</span><h2>项目健康</h2><button type="button" data-goto="projects">全部项目${icon('arrow-right')}</button></div>
            <div class="v9-proj">${projectRows.length ? projectRows.slice(0, 5).map((p) => `
              <button type="button" class="v9-proj-row" data-project-id="${p.id}" data-goto="project_detail">
                <span class="v9-proj-top"><b>${esc(p.title)}</b><span class="v9-state ${p.tone}"><i></i>${p.tone === 'critical' ? '需关注' : p.tone === 'warning' ? '推进中' : '正常'}</span></span>
                <small>${esc(p.unit || '委托单位待补充')} · ${esc(p.owner || '负责人待补充')}</small>
                <span class="v9-proj-mid"><span class="v9-proj-bar"><i style="width:${Number(p.hours) ? Math.min(100, p.completed / Number(p.hours) * 100) : 0}%"></i></span><em>${num(p.completed)} 已完成 / ${num(p.confirmed)} 已确认</em></span>
                <span class="v9-proj-next">下一步 · ${esc(p.next)}</span>
              </button>`).join('') : `<div class="v9-empty">${icon('folder-check')}<b>还没有交付中的项目</b><span>完成立项后，项目会出现在这里。</span>${canWrite() ? `<button type="button" id="empty-new-demand">新建培训需求</button>` : ''}</div>`}</div>
          </section>
        </div>

        <section class="v9-snap" aria-label="经营快照">
          <div class="v9-snap-cell big"><small>合同总额</small><b>${countTag(d.project_amount, 'money')}</b><span class="v9-snap-note">${activeProjects.length} 个项目正在交付</span></div>
          <div class="v9-snap-cell"><small>已回款</small><b>${countTag(d.received, 'money')}</b><span class="v9-bar"><i style="width:${Number(d.project_amount) ? Math.min(100, Number(d.received) / Number(d.project_amount) * 100) : 0}%"></i></span></div>
          <div class="v9-snap-cell"><small>已录课酬与成本</small><b>${countTag(recordedCost, 'money')}</b><span class="v9-bar"><i style="width:${Number(d.project_amount) ? Math.min(100, recordedCost / Number(d.project_amount) * 100) : 0}%"></i></span></div>
          <div class="v9-snap-cell"><small>估算余额</small><b>${countTag(d.profit, 'money')}</b><span class="v9-snap-note">按已录成本</span></div>
          <div class="v9-snap-cell stages"><small>需求进程</small><div class="v9-stages">${stages.map((x) => `<button type="button" data-goto="demands" data-status="${esc(x.status)}" class="${x.count ? 'on' : ''}"><i></i><b>${x.count}</b><small>${esc(x.status)}</small></button>`).join('')}</div></div>
        </section>
      </div>`;

    const bindGotos = (root) => $$('[data-goto]', root).forEach((btn) => { btn.onclick = () => navigateTo(btn.dataset.goto, {
      projectId: btn.dataset.projectId || null,
      focusId: btn.dataset.focusId || null,
      filter: btn.dataset.status ? { status: btn.dataset.status } : null,
    }); });
    bindGotos(c);
    const list = $('#priority-list');
    let curTone = '';
    let expanded = false;
    const drawQueue = () => {
      const filtered = curTone ? tasks.filter((t) => t.tone === curTone) : tasks;
      const shown = (curTone || expanded) ? filtered : filtered.slice(0, 4);
      list.innerHTML = filtered.length ? shown.map(rowHtml).join('') : `<div class="v9-queue-empty">${icon('circle-check-big')}<b>${curTone ? '该分类暂无事项' : '今天没有阻塞项'}</b><span>${curTone ? '切换其他筛选查看' : '所有关键事项都已推进'}</span></div>`;
      bindGotos(list);
      refreshIcons(list);
    };
    drawQueue();
    $$('.v9-chip-f', c).forEach((chip) => { chip.onclick = () => {
      $$('.v9-chip-f', c).forEach((x) => {
        const active = x === chip;
        x.classList.toggle('active', active);
        x.setAttribute('aria-pressed', String(active));
      });
      curTone = chip.dataset.tone;
      drawQueue();
    }; });
    const priorityToggle = $('#priority-toggle');
    if (priorityToggle) priorityToggle.onclick = () => {
      expanded = !expanded;
      priorityToggle.setAttribute('aria-expanded', String(expanded));
      priorityToggle.classList.toggle('is-expanded', expanded);
      $('span', priorityToggle).textContent = expanded ? '收起' : '查看全部';
      drawQueue();
    };
    if ($('#empty-new-demand')) $('#empty-new-demand').onclick = quickCreateDemand;
    refreshIcons(c);
  }

  // ============ 师资调度 ============
  async function pageDispatches(c) {
    const epoch = routeEpoch;
    const contextProjectId = state.contextProjectId;
    const selectedProjectId = contextProjectId || String(state.filters.dispatches?.projectId || '');
    const [rows, projects, teachers] = await Promise.all([
      api('/dispatches' + (selectedProjectId ? `?project_id=${encodeURIComponent(selectedProjectId)}` : '')), api('/projects'), api('/teachers'),
    ]);
    if (!isRouteCurrent(epoch, c, 'dispatches')) return;
    const activeProjects = projects.filter((p) => ['待启动', '进行中'].includes(p.status));
    const activeProjectIds = new Set(activeProjects.map((p) => String(p.id)));
    const contextProject = projects.find((p) => String(p.id) === String(contextProjectId));
    const inLib = teachers.filter((t) => t.status === '在库');
    const pending = rows.filter((r) => ['待发送', '已拒绝'].includes(r.status)).length;
    const confirmed = rows.filter((r) => r.status === '已确认').length;
    const completed = rows.filter((r) => r.status === '已完成').length;
    c.innerHTML = `${contextProjectId ? `<div class="context-filter">${icon('link-2')}<span>项目上下文：<b>${esc(contextProject?.title || `项目 #${contextProjectId}`)}</b></span><button type="button" id="context-project-home">返回项目工作区</button><button type="button" id="clear-context">查看全部</button></div>` : ''}<div class="module-summary three"><div><span>${icon('send')}</span><small>待处理安排</small><b><span id="dispatch-pending">${pending}</span><em>条</em></b></div><div><span>${icon('calendar-check')}</span><small>已确认</small><b><span id="dispatch-confirmed">${confirmed}</span><em>场</em></b></div><div><span>${icon('circle-check-big')}</span><small>已完成</small><b><span id="dispatch-completed">${completed}</span><em>场</em></b></div></div><div class="card data-card">
      <div class="card-heading"><div><h2>师资调度列表</h2><p>当前显示 <span id="result-count">${rows.length}</span> 条授课安排</p></div>${canWrite() && (!contextProjectId || activeProjectIds.has(String(contextProjectId))) ? `<button type="button" class="btn" id="add-btn">${icon('calendar-plus')}新建调度</button>` : ''}</div>
      <div class="toolbar">
        <label class="select-filter"><span class="sr-only">${contextProjectId ? '当前项目上下文' : '按培训项目筛选'}</span><select id="flt-proj" ${contextProjectId ? 'disabled aria-label="当前项目上下文已锁定"' : ''}><option value="">全部项目</option>${projects.map((p) => `<option value="${p.id}">#${p.id} ${esc(p.title)}</option>`).join('')}</select></label>
        <button type="button" class="btn gray" id="flt-btn" ${contextProjectId ? 'disabled' : ''}>${icon('list-filter')}筛选</button>
      </div>
      <div id="tbl"></div>
    </div>`;

    const fields = [
      { k: 'project_id', label: '培训项目', type: 'select', required: true, span2: true, options: projects.map((p) => ({ v: p.id, l: `#${p.id} ${p.title}` })) },
      { k: 'teacher_id', label: '授课师资（在库）', type: 'select', required: true, span2: true, options: inLib.map((t) => ({ v: t.id, l: `${t.name}｜${t.title || ''}｜${t.field || ''}｜${t.fee_rate}元/课时` })) },
      { k: 'subject', label: '授课主题', required: true, span2: true },
      { k: 'teach_date', label: '授课日期', type: 'date', required: true },
      { k: 'confirm_deadline', label: '讲师确认截止', type: 'date' },
      { k: 'start_time', label: '开始时间', type: 'time' },
      { k: 'end_time', label: '结束时间', type: 'time' },
      { k: 'hours', label: '课时数', type: 'number', required: true, min: 0.5, max: 24, step: 0.5 },
      { k: 'material_status', label: '材料准备', type: 'select', options: ['待准备', '准备中', '已就绪'], value: '待准备' },
      { k: 'venue', label: '授课场地', span2: true, placeholder: '线下教室或线上会议地址' },
      { k: 'remark', label: '备注', type: 'textarea' },
    ];

    const actions = [
      { l: '消息', cls: 'gray', onClick: (r) => {
        const logs = String(r.msg_log || '').split('\n').map((line) => line.trim()).filter(Boolean);
        openModal('通知与确认记录', logs.length ? `<div class="activity-log">${logs.map((line, i) => `<div><span>${String(i + 1).padStart(2, '0')}</span><p>${esc(line)}</p></div>`).join('')}</div>` : `<div class="workspace-empty">${icon('message-square-dashed')}<b>暂无通知记录</b><span>通过线下、电话或其他渠道通知师资后，可在这里记录操作轨迹。</span></div>`, { noFoot: true, kicker: '师资协同轨迹' });
      } },
      { l: '记录通知', cls: '', show: (r) => canWrite() && ['待启动', '进行中'].includes(r.project_status) && ['待发送', '已拒绝'].includes(r.status), onClick: (r) => confirmBox(`请先通过电话、微信或其他实际渠道联系师资“${r.teacher_name}”。确认已经通知《${r.subject}》的授课安排，并在系统中记录这次通知？`, async () => { const res = await api('/dispatches/send', { body: { id: r.id } }); toast(res || '已记录师资通知'); renderPage(); }) },
      { l: '确认', cls: 'green', show: (r) => canWrite() && ['待启动', '进行中'].includes(r.project_status) && r.status === '已发送', onClick: (r) => {
        openModal('记录师资确认结果', `<div class="confirm-content"><span class="confirm-icon">${icon('user-check')}</span><p>请选择师资“${esc(r.teacher_name)}”对本次授课安排的反馈。</p></div>`, {
          sm: true, okText: '师资确认授课',
          onOk: async () => { await api('/dispatches/confirm', { body: { id: r.id, accept: 1 } }); toast('师资已确认'); renderPage(); },
        });
        const foot = $('.modal-foot');
        const rej = document.createElement('button');
        rej.className = 'btn red'; rej.innerHTML = `${icon('x')}<span>师资拒绝</span>`;
        rej.onclick = () => openModal('记录师资拒绝原因', renderForm([{ k: 'reason', label: '拒绝原因', type: 'textarea', required: true, span2: true, placeholder: '例如：时间冲突、授课主题不匹配或行程无法协调' }]), {
          sm: true, okText: '确认记录拒绝', okIcon: 'x',
          onOk: async () => { const d = collectForm($('#modal-mask'), [{ k: 'reason', label: '拒绝原因', required: true }]); if (!d) return false; await api('/dispatches/confirm', { body: { id: r.id, accept: 0, reason: d.reason } }); toast('已记录拒绝原因，请重新调度'); renderPage(); },
        });
        foot.insertBefore(rej, foot.firstChild);
        refreshIcons(foot);
      } },
      { l: '完成', cls: 'orange', show: (r) => canWrite() && ['待启动', '进行中'].includes(r.project_status) && r.status === '已确认', onClick: (r) => confirmBox('确认该次授课已完成？系统将校验授课日期，并计入项目已完成课时。', async () => { const res = await api('/dispatches/complete', { body: { id: r.id } }); toast(res); renderPage(); }) },
    ];
    if (canWrite()) {
      const openDispatchEditor = (r) => {
        const editFields = ['待发送', '已拒绝'].includes(r.status) ? fields : fields.filter((f) => ['material_status', 'remark'].includes(f.k));
        openModal('编辑调度', renderForm(editFields, r), { onOk: async () => { const d = collectForm($('#modal-mask'), editFields); if (!d) return false; d.id = r.id; d.status = r.status; await api('/dispatches', { body: { ...r, ...d } }); toast('已保存'); renderPage(); } });
      };
      actions.push({ l: '补齐准备', cls: 'gray', show: (r) => ['待启动', '进行中'].includes(r.project_status) && r.material_status !== '已就绪', onClick: openDispatchEditor });
      actions.push({ l: '编辑', cls: 'gray', show: (r) => ['待启动', '进行中'].includes(r.project_status) && r.material_status === '已就绪', onClick: openDispatchEditor });
      actions.push({ l: '删除', cls: 'red', show: (r) => ['待启动', '进行中'].includes(r.project_status) && ['待发送', '已拒绝'].includes(r.status), onClick: (r) => confirmBox(`确定删除该调度记录？`, async () => { await api('/dispatches/delete', { body: { id: r.id } }); toast('已删除'); renderPage(); }) });
    }

    const cols = [
      { k: 'subject', l: '课程安排', render: (r) => `<span class="primary-cell"><b>${esc(r.subject)}</b><small>#D-${String(r.id).padStart(4, '0')} · ${esc(r.project_title)}</small></span>` },
      { k: 'teacher_name', l: '授课师资', render: (r) => `<span class="secondary-cell"><b>${esc(r.teacher_name || '讲师待定')}</b><small>${esc(r.venue || r.teacher_phone || '场地待定')}</small></span>` },
      { k: 'teach_date', l: '日期与课时', render: (r) => `<span class="secondary-cell"><b>${esc(r.teach_date || '日期待定')} ${esc([r.start_time, r.end_time].filter(Boolean).join('—'))}</b><small>${num(r.hours)} 课时 · 确认截止 ${esc(r.confirm_deadline || '待定')}</small></span>` },
      { k: 'status', l: '交付状态', render: (r) => `<span class="status-stack">${tag(r.status)}${tag(r.material_status || '材料待补充')}</span>` },
      { k: 'sent_at', l: '最近通知', mobileHide: true, render: (r) => `<span class="secondary-cell"><b>${esc(r.sent_at || '尚未发送')}</b><small>${r.confirmed_at ? `确认于 ${esc(r.confirmed_at)}` : '等待状态更新'}</small></span>` },
    ];
    const draw = (list) => {
      if (!isRouteCurrent(epoch, c, 'dispatches')) return;
      const table = $('#tbl', c);
      if (!table) return;
      table.innerHTML = renderTable(cols, list, actions, 'dispatches');
      bindTableActions(table, list, actions);
      $('#result-count', c).textContent = list.length;
      $('#dispatch-pending', c).textContent = list.filter((r) => ['待发送', '已拒绝'].includes(r.status)).length;
      $('#dispatch-confirmed', c).textContent = list.filter((r) => r.status === '已确认').length;
      $('#dispatch-completed', c).textContent = list.filter((r) => r.status === '已完成').length;
    };
    draw(rows);
    revealFocusedRow($('#tbl', c));
    if (selectedProjectId) $('#flt-proj', c).value = String(selectedProjectId);
    let filterController = null;
    addRouteCleanup(() => filterController?.abort(), epoch);
    const runProjectFilter = async () => {
      if (contextProjectId || !isRouteCurrent(epoch, c, 'dispatches')) return;
      const v = $('#flt-proj', c).value;
      state.filters.dispatches = { projectId: v };
      filterController?.abort();
      const controller = new AbortController();
      filterController = controller;
      try {
        const list = await api('/dispatches' + (v ? '?project_id=' + encodeURIComponent(v) : ''), { signal: controller.signal });
        if (controller === filterController) draw(list);
      } catch (error) {
        if (!error || error.name !== 'AbortError') return;
      }
    };
    $('#flt-btn', c).onclick = runProjectFilter;
    $('#flt-proj', c).onchange = runProjectFilter;
    if ($('#context-project-home', c)) $('#context-project-home', c).onclick = () => navigateTo('project_detail', { projectId: contextProjectId });
    if ($('#clear-context', c)) $('#clear-context', c).onclick = () => { state.contextProjectId = null; state.filters.dispatches = { projectId: '' }; navigateTo('dispatches', { replaceHistory: true }); };
    const addBtn = $('#add-btn', c);
    if (addBtn) addBtn.onclick = () => {
      const addFields = fields.map((f) => f.k === 'project_id' ? { ...f, disabled: Boolean(contextProjectId), options: f.options.filter((o) => activeProjectIds.has(String(o.v))) } : f);
      const currentProjectId = contextProjectId || $('#flt-proj', c)?.value || '';
      const initialProject = projects.find((project) => String(project.id) === String(currentProjectId));
      const prefilledProjectId = initialProject && activeProjectIds.has(String(initialProject.id)) ? initialProject.id : '';
      const initialData = prefilledProjectId ? { project_id: prefilledProjectId, venue: initialProject?.venue || '' } : null;
      const contextNote = contextProjectId ? `<div class="context-create-note">${icon('link-2')}已关联“${esc(contextProject?.title || `项目 #${contextProjectId}`)}”，并带入项目场地。</div>` : '';
      openModal('新建师资调度', `${contextNote}${renderForm(addFields, initialData)}`, {
        onOk: async () => { const d = collectForm($('#modal-mask'), addFields); if (!d) return false; d.status = '待发送'; await api('/dispatches', { body: d }); toast('调度已创建，请及时发送'); renderPage(); },
      });
      const projectSelect = $('[data-k="project_id"]', $('#modal-mask'));
      const teachDate = $('[data-k="teach_date"]', $('#modal-mask'));
      const deadline = $('[data-k="confirm_deadline"]', $('#modal-mask'));
      const venue = $('[data-k="venue"]', $('#modal-mask'));
      if (projectSelect && venue) projectSelect.onchange = () => {
        const selected = projects.find((project) => String(project.id) === String(projectSelect.value));
        if (!venue.value && selected?.venue) venue.value = selected.venue;
      };
      if (teachDate && deadline) teachDate.onchange = () => {
        if (!deadline.value || deadline.dataset.autoDate === 'true') {
          deadline.value = shiftIsoDate(teachDate.value, -3);
          deadline.dataset.autoDate = 'true';
        }
      };
    };
  }

  // ============ 效果评估（问卷） ============
  async function pageQuestionnaires(c) {
    const epoch = routeEpoch;
    const contextProjectId = state.contextProjectId;
    const [rows, projects] = await Promise.all([api('/questionnaires' + (contextProjectId ? `?project_id=${encodeURIComponent(contextProjectId)}` : '')), api('/projects')]);
    if (!isRouteCurrent(epoch, c, 'questionnaires')) return;
    const sendTotal = rows.reduce((s, r) => s + Number(r.send_total || 0), 0);
    const recvTotal = rows.reduce((s, r) => s + Number(r.recv_total || 0), 0);
    const contextProject = projects.find((p) => String(p.id) === String(contextProjectId));
    c.innerHTML = `${contextProjectId ? `<div class="context-filter">${icon('link-2')}<span>项目上下文：<b>${esc(contextProject?.title || `项目 #${contextProjectId}`)}</b></span><button type="button" id="context-project-home">返回项目工作区</button><button type="button" id="clear-context">查看全部</button></div>` : ''}<div class="module-summary three"><div><span>${icon('clipboard-list')}</span><small>评估问卷</small><b>${countTag(rows.length)}<em>份</em></b></div><div><span>${icon('send')}</span><small>累计计划触达</small><b>${countTag(sendTotal)}<em>人次</em></b></div><div><span>${icon('message-square-check')}</span><small>整体回收率</small><b>${countTag(sendTotal ? Math.min(100, recvTotal / sendTotal * 100) : 0, 'pct')}<em>%</em></b></div></div><div class="card data-card">
      <div class="card-heading"><div><h2>效果评估列表</h2><p>创建问卷、生成作答链接并自动汇总结果</p></div>${canWrite() && contextProject?.status !== '已归档' ? `<button type="button" class="btn" id="add-btn">${icon('clipboard-plus')}新建问卷</button>` : ''}</div>
      <div id="tbl"></div>
    </div>`;

    const cols = [
      { k: 'id', l: '编号', mobileHide: true }, { k: 'title', l: '问卷标题' },
      { k: 'target', l: '评估对象', mobileHide: true, render: (r) => tag(r.target) },
      { k: 'project_title', l: '关联项目' },
      { k: 'status', l: '状态', render: (r) => tag(r.status) },
      { k: 'send_total', l: '计划触达', mobileHide: true }, { k: 'recv_total', l: '回收份数' },
      { k: 'rate', l: '回收率', render: (r) => `${Number(r.send_total) ? Math.min(100, Number(r.recv_total || 0) / Number(r.send_total) * 100).toFixed(1) : 0}%` },
    ];

    const finishAction = async (request, message) => {
      await request();
      if (!isRouteCurrent(epoch, c, 'questionnaires')) return;
      toast(message);
      renderPage();
    };
    const pageContext = { epoch, content: c };
    const actions = [
      { l: '统计', cls: '', onClick: (r) => showQStats(r, pageContext) },
      { l: '发布', cls: 'green', show: (r) => canWrite() && r.project_status !== '已归档' && r.status === '草稿', onClick: (r) => confirmBox('发布后问卷可通过微信发送，确定发布？', () => finishAction(() => api('/q/publish', { body: { id: r.id } }), '已发布')) },
      { l: '发送链接', cls: 'orange', icon: 'send', show: (r) => canWrite() && r.project_status !== '已归档' && r.status === '已发布', onClick: (r) => showQSend(r, pageContext) },
      { l: '关闭', cls: 'red', show: (r) => canWrite() && r.project_status !== '已归档' && r.status === '已发布', onClick: (r) => confirmBox('关闭后作答链接将失效，确定关闭？', () => finishAction(() => api('/q/close', { body: { id: r.id } }), '已关闭')) },
    ];
    if (canWrite()) {
      actions.push({ l: '编辑', cls: 'gray', show: (r) => r.project_status !== '已归档' && r.status === '草稿', onClick: (r) => showQForm(r, projects, pageContext) });
      actions.push({ l: '删除', cls: 'red', show: (r) => r.project_status !== '已归档' && r.status === '草稿' && Number(r.send_total || 0) === 0, onClick: (r) => confirmBox('确定删除该草稿问卷？', () => finishAction(() => api('/questionnaires/delete', { body: { id: r.id } }), '已删除')) });
    }
    const table = $('#tbl', c);
    const draw = (list) => { table.innerHTML = renderTable(cols, list, actions, 'questionnaires'); bindTableActions(table, list, actions); };
    draw(rows);
    const addBtn = $('#add-btn', c);
    if (addBtn) addBtn.onclick = () => showQForm(null, projects.filter((p) => p.status !== '已归档'), pageContext);
    const contextHome = $('#context-project-home', c);
    if (contextHome) contextHome.onclick = () => navigateTo('project_detail', { projectId: contextProjectId });
    const clearContext = $('#clear-context', c);
    if (clearContext) clearContext.onclick = () => { state.contextProjectId = null; navigateTo('questionnaires', { replaceHistory: true }); };
  }

  // 问卷构建器
  function showQForm(row, projects, pageContext = {}) {
    const epoch = pageContext.epoch ?? routeEpoch;
    const content = pageContext.content || null;
    let questions = [];
    try { questions = row && row.questions ? JSON.parse(row.questions) : []; } catch (e) {}
    if (questions.length === 0) questions = [{ type: 'score', title: '您对本次培训的总体满意度', options: [] }];

    let meta = [
      { k: 'title', label: '问卷标题', required: true, span2: true },
      { k: 'target', label: '评估对象', type: 'select', options: ['参训学员', '需求单位'] },
      { k: 'project_id', label: '关联培训项目', type: 'select', required: true, options: projects.filter((p) => p.status !== '已归档').map((p) => ({ v: p.id, l: `#${p.id} ${p.title}` })) },
    ];
    if (!row && state.contextProjectId) meta = meta.map((field) => field.k === 'project_id' ? { ...field, disabled: true } : field);

    const formMask = openModal((row ? '编辑' : '新建') + '评估问卷', `
      ${row && row.status !== '草稿' ? `<div class="readonly-banner">${icon('circle-alert')}<span>此问卷当前为“${esc(row.status)}”状态。修改题目会影响后续统计口径，请确认后再保存。</span></div>` : ''}
      ${renderForm(meta, row || (state.contextProjectId ? { project_id: state.contextProjectId } : null))}
      <div style="margin-top:16px;display:flex;justify-content:space-between;align-items:center">
        <b>题目设置</b>
        <div>
          <button type="button" class="btn sm gray" id="q-add-score">${icon('star')}评分题</button>
          <button type="button" class="btn sm gray" id="q-add-single">${icon('circle-dot')}单选题</button>
          <button type="button" class="btn sm gray" id="q-add-text">${icon('text-cursor-input')}填空题</button>
        </div>
      </div>
      <div id="q-list" style="margin-top:10px"></div>
      <p style="color:var(--text-2);font-size:12px;margin-top:6px">评分题采用5分制（星标）；单选题选项用顿号"、"或逗号分隔。</p>
    `, {
      onOk: async () => {
        if (!isRouteCurrent(epoch, content, 'questionnaires') || !formMask.isConnected || $('#modal-mask') !== formMask) return false;
        const d = collectForm(formMask, meta);
        if (!d) return false;
        const qs = [];
        let invalidQuestion = '';
        $$('.q-item', formMask).forEach((el, index) => {
          const type = $('.q-type', el).value;
          const title = $('.q-title', el).value.trim();
          if (!title) { invalidQuestion = `请填写第 ${index + 1} 题的题目标题`; return; }
          const q = { type, title };
          if (type === 'single') {
            q.options = $('.q-opts', el).value.split(/[、,，]/).map((s) => s.trim()).filter(Boolean);
            if (q.options.length < 2) invalidQuestion = `第 ${index + 1} 题至少需要两个选项`;
          }
          qs.push(q);
        });
        if (invalidQuestion) { toast(invalidQuestion, true); return false; }
        if (qs.length === 0) { toast('请至少添加一道题目', true); return false; }
        d.questions = JSON.stringify(qs);
        if (row) { d.id = row.id; d.status = row.status; }
        else d.status = '草稿';
        await api('/questionnaires', { body: d });
        if (!isRouteCurrent(epoch, content, 'questionnaires')) return false;
        toast('问卷已保存');
        renderPage();
      },
    });

    const drawQs = () => {
      $('#q-list', formMask).innerHTML = questions.map((q, i) => `
        <div class="q-item" data-i="${i}">
          <div class="row">
            <select class="q-type">
              <option value="score" ${q.type === 'score' ? 'selected' : ''}>评分题</option>
              <option value="single" ${q.type === 'single' ? 'selected' : ''}>单选题</option>
              <option value="text" ${q.type === 'text' ? 'selected' : ''}>填空题</option>
            </select>
            <input class="q-title" style="flex:1" placeholder="题目标题" value="${esc(q.title)}">
            <button type="button" class="btn sm gray q-up" title="上移" aria-label="上移题目">${icon('arrow-up')}</button>
            <button type="button" class="btn sm gray q-down" title="下移" aria-label="下移题目">${icon('arrow-down')}</button>
            <button type="button" class="btn sm red q-del">${icon('trash-2')}删除</button>
          </div>
          <div class="row q-opts-row" style="display:${q.type === 'single' ? 'flex' : 'none'}">
            <input class="q-opts" style="flex:1" placeholder="选项，如：非常满意、满意、一般、不满意" value="${esc((q.options || []).join('、'))}">
          </div>
        </div>`).join('');
      $$('.q-item', formMask).forEach((el) => {
        $('.q-type', el).onchange = (e) => { $('.q-opts-row', el).style.display = e.target.value === 'single' ? 'flex' : 'none'; };
        $('.q-del', el).onclick = () => el.remove();
        $('.q-up', el).onclick = () => { const prev = el.previousElementSibling; if (prev) el.parentElement.insertBefore(el, prev); };
        $('.q-down', el).onclick = () => { const next = el.nextElementSibling; if (next) el.parentElement.insertBefore(next, el); };
      });
      refreshIcons($('#q-list', formMask));
    };
    $('#q-add-score', formMask).onclick = () => addQ('score');
    $('#q-add-single', formMask).onclick = () => addQ('single');
    $('#q-add-text', formMask).onclick = () => addQ('text');
    function addQ(type) {
      const div = document.createElement('div');
      div.className = 'q-item';
      div.innerHTML = `
        <div class="row">
          <select class="q-type"><option value="score" ${type === 'score' ? 'selected' : ''}>评分题</option><option value="single" ${type === 'single' ? 'selected' : ''}>单选题</option><option value="text" ${type === 'text' ? 'selected' : ''}>填空题</option></select>
          <input class="q-title" style="flex:1" placeholder="题目标题">
          <button type="button" class="btn sm gray q-up" title="上移" aria-label="上移题目">${icon('arrow-up')}</button>
          <button type="button" class="btn sm gray q-down" title="下移" aria-label="下移题目">${icon('arrow-down')}</button>
          <button type="button" class="btn sm red q-del">${icon('trash-2')}删除</button>
        </div>
        <div class="row q-opts-row" style="display:${type === 'single' ? 'flex' : 'none'}"><input class="q-opts" style="flex:1" placeholder="选项，如：非常满意、满意、一般、不满意"></div>`;
      $('#q-list', formMask).appendChild(div);
      $('.q-type', div).onchange = (e) => { $('.q-opts-row', div).style.display = e.target.value === 'single' ? 'flex' : 'none'; };
      $('.q-del', div).onclick = () => div.remove();
      $('.q-up', div).onclick = () => { const prev = div.previousElementSibling; if (prev) div.parentElement.insertBefore(div, prev); };
      $('.q-down', div).onclick = () => { const next = div.nextElementSibling; if (next) div.parentElement.insertBefore(next, div); };
      refreshIcons(div);
    }
    drawQs();
  }

  // 生成问卷分享链接
  function showQSend(r, pageContext = {}) {
    const epoch = pageContext.epoch ?? routeEpoch;
    const content = pageContext.content || null;
    const sendMask = openModal(`生成作答链接 - ${r.title}`, `<p class="modal-intro">填写本次触达对象与答卷上限，系统将生成可分享的匿名作答链接；达到上限后链接会停止接收。</p>${renderForm([
      { k: 'target_desc', label: '发送对象', required: true, span2: true, placeholder: '如：某市国资委参训学员群' },
      { k: 'send_count', label: '本批答卷上限', type: 'number', required: true, min: 1, max: 100000, value: 30, hint: '达到此数量后，本链接将停止接收新答卷' },
    ])}`, {
      sm: true, okText: '生成链接', okIcon: 'link',
      onOk: async () => {
        if (!isRouteCurrent(epoch, content, 'questionnaires') || !sendMask.isConnected || $('#modal-mask') !== sendMask) return false;
        const d = collectForm(sendMask, [{ k: 'target_desc', label: '发送对象', required: true }, { k: 'send_count', label: '本批答卷上限', type: 'number', required: true, min: 1, max: 100000 }]);
        if (!d) return false;
        const res = await api('/q/send', { body: { id: r.id, target_desc: d.target_desc, send_count: d.send_count } });
        if (!isRouteCurrent(epoch, content, 'questionnaires') || !sendMask.isConnected || $('#modal-mask') !== sendMask) return false;
        const link = location.origin + res.link;
        const resultMask = openModal('作答链接已生成', `
          <div class="share-success">${icon('circle-check-big')}<div><b>可以开始收集反馈了</b><p>复制以下链接，通过工作群、短信或邮件发送给评估对象。</p></div></div>
          <div class="share-link"><input id="q-link" readonly value="${esc(link)}"><button type="button" class="btn" id="q-copy">${icon('copy')}复制链接</button></div>
          <p class="modal-note">系统会自动统计回收份数、评分分布和文本反馈。关闭问卷后，该链接将停止接收新答卷。</p>
        `, { noFoot: true });
        $('#q-copy', resultMask).onclick = async () => {
          try { await navigator.clipboard.writeText(link); toast('作答链接已复制'); }
          catch (e) { $('#q-link', resultMask).select(); document.execCommand('copy'); toast('作答链接已复制'); }
        };
        refreshIcons(resultMask);
        renderPage();
        return false; // 保留结果弹窗
      },
    });
  }

  // 问卷统计
  async function showQStats(r, pageContext = {}) {
    const epoch = pageContext.epoch ?? routeEpoch;
    const content = pageContext.content || null;
    const d = await api('/stats/q?id=' + r.id);
    if (!isRouteCurrent(epoch, content, 'questionnaires')) return;
    const blocks = d.questions.map((q, i) => {
      if (q.type === 'score') {
        return `<div class="chart-box q-stat-card"><div class="q-stat-title"><h4>Q${i + 1} ${esc(q.title)}</h4><span>${q.avg} 分</span></div><div class="chart" id="qs${i}"><div class="mini-empty">${icon('loader-circle', 'spin')}正在加载图表</div></div></div>`;
      } else if (q.type === 'single') {
        return `<div class="chart-box q-stat-card"><div class="q-stat-title"><h4>Q${i + 1} ${esc(q.title)}</h4><span>单选题</span></div><div class="chart" id="qs${i}"><div class="mini-empty">${icon('loader-circle', 'spin')}正在加载图表</div></div></div>`;
      } else {
        return `<div class="chart-box q-stat-card"><div class="q-stat-title"><h4>Q${i + 1} ${esc(q.title)}</h4><span>${q.texts.length} 条回答</span></div>
          <div class="text-responses">${q.texts.length ? q.texts.map((t) => `<p>${icon('message-square-quote')}<span>${esc(t)}</span></p>`).join('') : `<div class="mini-empty">${icon('message-square')}暂无文本反馈</div>`}</div></div>`;
      }
    }).join('');

    const mask = openModal(`统计分析 - ${d.title}`, `
      <div class="q-stats-summary"><div><span>${icon('users-round')}</span><small>评估对象</small><b>${esc(d.target)}</b></div><div><span>${icon('files')}</span><small>回收问卷</small><b>${d.total} 份</b></div><div><span>${icon('star')}</span><small>综合平均分</small><b>${d.avg_score} 分</b></div></div>
      <div class="q-stats-grid">${blocks}</div>
      <div class="section-title response-title"><div><span>作答明细</span><small>最近提交的问卷记录</small></div></div>
      ${renderTable([
        { k: 'respondent', l: '作答人' },
        { k: 'avg_score', l: '平均分' },
        { k: 'submitted_at', l: '提交时间' },
      ], d.responses, null, 'responses')}
    `, { noFoot: true, wide: true, kicker: '问卷数据洞察' });

    try { await ensureCharts(); }
    catch (error) {
      if (isRouteCurrent(epoch, content, 'questionnaires') && mask.isConnected && $('#modal-mask') === mask) {
        $$('.chart', mask).forEach((el) => { el.innerHTML = `<div class="mini-empty">${icon('chart-no-axes-column')}图表暂时无法加载，统计数字与明细仍可正常查看</div>`; });
        refreshIcons(mask);
        toast(error.message || '图表组件加载失败', true);
      }
      return;
    }
    if (!isRouteCurrent(epoch, content, 'questionnaires') || !mask.isConnected || $('#modal-mask') !== mask) return;

    d.questions.forEach((q, i) => {
      const el = $('#qs' + i, mask);
      if (!el) return;
      if (q.type === 'score') {
        makeChart('qs' + i, {
          aria: { enabled: true, decal: { show: true } },
          tooltip: { trigger: 'axis', backgroundColor: '#0e1116', borderWidth: 0, textStyle: { color: '#fff' } }, grid: { left: 32, right: 12, top: 18, bottom: 25 },
          xAxis: { type: 'category', data: ['1分', '2分', '3分', '4分', '5分'], axisLine: { show: false }, axisTick: { show: false } },
          yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#e4e6df' } } },
          series: [{ type: 'bar', barMaxWidth: 24, data: [q.dist[1], q.dist[2], q.dist[3], q.dist[4], q.dist[5]], itemStyle: { color: '#4d5dfb', borderRadius: [4,4,0,0] } }],
        }, mask);
      } else if (q.type === 'single') {
        makeChart('qs' + i, {
          aria: { enabled: true, decal: { show: true } },
          color: ['#4d5dfb', '#8b5cf6', '#22d3ee', '#7c3aed', '#8a90a3'], tooltip: { trigger: 'item', backgroundColor: '#0e1116', borderWidth: 0, textStyle: { color: '#fff' } },
          legend: { bottom: 0, icon: 'circle', itemWidth: 7 }, series: [{ type: 'pie', radius: ['42%', '68%'], center: ['50%', '43%'], padAngle: 3, itemStyle: { borderRadius: 4 }, label: { show: false }, data: Object.entries(q.options).map(([k, v]) => ({ name: k, value: v })) }],
        }, mask);
      }
    });
    refreshIcons(mask);
  }

  // ============ 课酬管理 ============
  async function pageFees(c) {
    const epoch = routeEpoch;
    const contextProjectId = state.contextProjectId;
    const selectedProjectId = contextProjectId || (state.focusId ? '' : String(state.filters.fees?.projectId || ''));
    const [rows, projects] = await Promise.all([api('/fees' + (selectedProjectId ? `?project_id=${encodeURIComponent(selectedProjectId)}` : '')), api('/projects')]);
    if (!isRouteCurrent(epoch, c, 'fees')) return;
    const contextProject = projects.find((p) => String(p.id) === String(contextProjectId));
    const total = rows.reduce((s, r) => s + Number(r.amount || 0), 0);
    const pending = rows.filter((r) => r.status === '待发放').reduce((s, r) => s + Number(r.amount || 0), 0);
    c.innerHTML = `${contextProjectId ? `<div class="context-filter">${icon('link-2')}<span>项目上下文：<b>${esc(contextProject?.title || `项目 #${contextProjectId}`)}</b></span><button type="button" id="context-project-home">返回项目工作区</button><button type="button" id="clear-context">查看全部</button></div>` : ''}<div class="module-summary three"><div><span>${icon('wallet-cards')}</span><small>课酬总额</small><b id="fees-total">¥ ${money(total)}</b></div><div><span>${icon('clock-3')}</span><small>待发放</small><b id="fees-pending">¥ ${money(pending)}</b></div><div><span>${icon('circle-check-big')}</span><small>已发放</small><b id="fees-paid">¥ ${money(total - pending)}</b></div></div><div class="card data-card">
      <div class="card-heading"><div><h2>课酬发放列表</h2><p>当前显示 <span id="result-count">${rows.length}</span> 条记录 · 按已确认课时与讲师标准核算</p></div>${canWrite() && contextProject?.status !== '已归档' ? `<button type="button" class="btn" id="calc-btn">${icon('calculator')}自动计算课酬</button>` : ''}</div>
      <div class="toolbar">
        <label class="select-filter"><span class="sr-only">${contextProjectId ? '当前项目上下文' : '按培训项目筛选'}</span><select id="flt-proj" ${contextProjectId ? 'disabled aria-label="当前项目上下文已锁定"' : ''}><option value="">全部项目</option>${projects.map((p) => `<option value="${p.id}">#${p.id} ${esc(p.title)}</option>`).join('')}</select></label>
        <button type="button" class="btn gray" id="flt-btn" ${contextProjectId ? 'disabled' : ''}>${icon('list-filter')}筛选</button>
      </div>
      <div class="inline-note">${icon('info')}已确认课时可提前核算，实际授课完成后才能发放；重新计算会保留已发放记录，只补齐尚未覆盖的课时。</div>
      <div id="tbl"></div>
    </div>`;

    const cols = [
      { k: 'id', l: '编号', mobileHide: true }, { k: 'project_title', l: '培训项目' }, { k: 'teacher_name', l: '师资' },
      { k: 'hours', l: '课时数', align: 'right' }, { k: 'rate', l: '课酬标准(元/课时)', align: 'right', render: (r) => money(r.rate) },
      { k: 'amount', l: '课酬金额(元)', align: 'right', render: (r) => `<b>¥ ${money(r.amount)}</b>` },
      { k: 'status', l: '状态', render: (r) => tag(r.status) }, { k: 'pay_date', l: '发放日期' },
    ];
    const actions = [
      { l: '发放', cls: 'green', show: (r) => canWrite() && r.project_status !== '已归档' && r.status === '待发放', onClick: (r) => confirmBox(`确认向【${r.teacher_name}】发放课酬 ${money(r.amount)} 元？系统将校验对应授课是否已完成。`, async () => { await api('/fees/pay', { body: { id: r.id } }); toast('课酬已发放'); renderPage(); }) },
    ];
    if (canWrite()) actions.push({ l: '删除', cls: 'red', show: (r) => r.project_status !== '已归档' && r.status === '待发放', onClick: (r) => confirmBox('确定删除该笔待发课酬记录？', async () => { await api('/fees/delete', { body: { id: r.id } }); toast('已删除'); renderPage(); }) });

    const draw = (list) => {
      if (!isRouteCurrent(epoch, c, 'fees')) return;
      const table = $('#tbl', c);
      if (!table) return;
      table.innerHTML = renderTable(cols, list, actions, 'fees');
      bindTableActions(table, list, actions);
      const visibleTotal = list.reduce((sum, row) => sum + Number(row.amount || 0), 0);
      const visiblePending = list.filter((row) => row.status === '待发放').reduce((sum, row) => sum + Number(row.amount || 0), 0);
      $('#result-count', c).textContent = list.length;
      $('#fees-total', c).textContent = `¥ ${money(visibleTotal)}`;
      $('#fees-pending', c).textContent = `¥ ${money(visiblePending)}`;
      $('#fees-paid', c).textContent = `¥ ${money(visibleTotal - visiblePending)}`;
    };
    draw(rows);
    revealFocusedRow($('#tbl', c));
    if (selectedProjectId) $('#flt-proj', c).value = String(selectedProjectId);
    let filterController = null;
    addRouteCleanup(() => filterController?.abort(), epoch);
    const runProjectFilter = async () => {
      if (contextProjectId || !isRouteCurrent(epoch, c, 'fees')) return;
      const v = $('#flt-proj', c).value;
      state.filters.fees = { projectId: v };
      filterController?.abort();
      const controller = new AbortController();
      filterController = controller;
      try {
        const list = await api('/fees' + (v ? '?project_id=' + encodeURIComponent(v) : ''), { signal: controller.signal });
        if (controller === filterController) draw(list);
      } catch (error) {
        if (!error || error.name !== 'AbortError') return;
      }
    };
    $('#flt-btn', c).onclick = runProjectFilter;
    $('#flt-proj', c).onchange = runProjectFilter;
    if ($('#context-project-home', c)) $('#context-project-home', c).onclick = () => navigateTo('project_detail', { projectId: contextProjectId });
    if ($('#clear-context', c)) $('#clear-context', c).onclick = () => { state.contextProjectId = null; state.filters.fees = { projectId: '' }; navigateTo('fees', { replaceHistory: true }); };
    const calcBtn = $('#calc-btn', c);
    if (calcBtn) calcBtn.onclick = () => {
      const calcProjects = projects.filter((p) => p.status !== '已归档');
      const currentProjectId = contextProjectId || $('#flt-proj', c)?.value || '';
      const prefilledProjectId = calcProjects.some((project) => String(project.id) === String(currentProjectId)) ? currentProjectId : '';
      const calcFields = [{ k: 'project_id', label: '选择培训项目', type: 'select', required: true, span2: true, disabled: Boolean(contextProjectId), options: calcProjects.map((p) => ({ v: p.id, l: `#${p.id} ${p.title}` })) }];
      const contextNote = contextProjectId ? `<div class="context-create-note">${icon('link-2')}将按当前项目“${esc(contextProject?.title || `#${contextProjectId}`)}”计算。</div>` : '';
      openModal('自动计算课酬', `${contextNote}${renderForm(calcFields, prefilledProjectId ? { project_id: prefilledProjectId } : null)}`, {
        sm: true, okText: '开始计算',
        onOk: async () => {
          const d = collectForm($('#modal-mask'), calcFields);
          if (!d) return false;
          const created = await api('/fees/calc', { body: { project_id: d.project_id } });
          const amount = created.reduce((s, x) => s + Number(x.amount || 0), 0);
          openModal('课酬计算完成', created.length ? `<div class="amount-callout"><small>本次补充生成 ${created.length} 条待发课酬</small><b>¥ ${money(amount)}</b></div>
            ${renderTable([
              { k: 'teacher_name', l: '师资' }, { k: 'hours', l: '课时' },
              { k: 'rate', l: '标准', render: (x) => money(x.rate) },
              { k: 'amount', l: '课酬金额', render: (x) => money(x.amount) },
            ], created, null, 'fee-results')}` : `<div class="workspace-clear">${icon('badge-check')}<div><b>无需新增课酬</b><span>已发放记录已经覆盖当前确认课时。</span></div></div>`, { noFoot: true });
          renderPage();
          return false;
        },
      });
    };
  }

  // ============ 师资库 ============
  async function pageTeachers(c) {
    const epoch = routeEpoch;
    const [rows, projects, dispatches] = await Promise.all([api('/teachers'), api('/projects'), api('/dispatches')]);
    if (!isRouteCurrent(epoch, c, 'teachers')) return;
    const savedFilter = state.filters.teachers || {};
    const inLib = rows.filter((r) => r.status === '在库').length;
    const avgRate = rows.length ? rows.reduce((s, r) => s + Number(r.fee_rate || 0), 0) / rows.length : 0;
    c.innerHTML = `<div class="module-summary three"><div><span>${icon('users-round')}</span><small>当前师资</small><b><span id="teachers-total">${rows.length}</span><em>人</em></b></div><div><span>${icon('user-check')}</span><small>当前在库</small><b><span id="teachers-inlib">${inLib}</span><em>人</em></b></div><div><span>${icon('badge-japanese-yen')}</span><small>当前平均课酬</small><b id="teachers-rate">¥ ${money(avgRate)}</b></div></div><div class="card data-card">
      <div class="card-heading"><div><h2>师资档案</h2><p>当前显示 <span id="result-count">${rows.length}</span> 条记录 · 统一管理专长、联系方式与授课评价</p></div>${canWrite() ? `<button type="button" class="btn" id="add-btn">${icon('user-plus')}师资入库</button>` : ''}</div>
      <div class="toolbar">
        <label class="search-box"><span class="sr-only">搜索师资姓名、单位或领域</span>${icon('search')}<input id="flt-kw" value="${esc(savedFilter.kw || '')}" placeholder="搜索：姓名/单位/领域" autocomplete="off"></label>
        <label class="select-filter"><span class="sr-only">按师资状态筛选</span><select id="flt-status"><option value="">全部状态</option><option ${savedFilter.status === '在库' ? 'selected' : ''}>在库</option><option ${savedFilter.status === '出库' ? 'selected' : ''}>出库</option></select></label>
        <button type="button" class="btn gray" id="flt-btn">${icon('list-filter')}筛选</button>
      </div>
      <div id="tbl"></div>
    </div>`;

    const fields = [
      { k: 'name', label: '姓名', required: true },
      { k: 'gender', label: '性别', type: 'select', options: ['男', '女'] },
      { k: 'org', label: '所在单位' },
      { k: 'title', label: '职称/职务' },
      { k: 'field', label: '专业领域', span2: true },
      { k: 'phone', label: '联系电话' },
      { k: 'email', label: '电子邮箱', type: 'email' },
      { k: 'fee_rate', label: '课酬标准（元/课时）', type: 'number', required: true, min: 0, step: 100 },
      { k: 'in_date', label: '入库日期', type: 'date' },
      { k: 'intro', label: '师资简介', type: 'textarea' },
    ];

    const cols = [
      { k: 'id', l: '编号', mobileHide: true, render: (r) => `<span class="project-id">#T-${String(r.id).padStart(4, '0')}</span>` }, { k: 'name', l: '师资', render: (r) => `<div class="person-cell"><span class="person-avatar">${esc(r.name.slice(-2))}</span><span><b>${esc(r.name)}</b><small>${esc(r.title || '讲师')}</small></span></div>` }, { k: 'gender', l: '性别', mobileHide: true },
      { k: 'org', l: '单位' }, { k: 'title', l: '职称', mobileHide: true }, { k: 'field', l: '专业领域' },
      { k: 'fee_rate', l: '课酬标准', align: 'right', render: (r) => `¥ ${money(r.fee_rate)}/课时` },
      { k: 'status', l: '状态', render: (r) => tag(r.status) },
    ];

    const actions = [
      { l: '档案', cls: '', icon: 'contact-round', onClick: (r) => showTeacherEvals(r, projects, dispatches) },
    ];
    if (canWrite()) {
      actions.push({ l: '出库', cls: 'orange', show: (r) => r.status === '在库', onClick: (r) => confirmBox(`确定将师资【${r.name}】移出师资库？出库后不可参与新调度。`, async () => { await api('/teachers/checkout', { body: { id: r.id } }); toast('已出库'); renderPage(); }) });
      actions.push({ l: '入库', cls: 'green', show: (r) => r.status === '出库', onClick: (r) => confirmBox(`确定将师资【${r.name}】重新入库？`, async () => { await api('/teachers/checkin', { body: { id: r.id } }); toast('已重新入库'); renderPage(); }) });
      actions.push({ l: '编辑', cls: 'gray', onClick: (r) => openModal('编辑师资', renderForm(fields, r), { onOk: async () => { const d = collectForm($('#modal-mask'), fields); if (!d) return false; await api('/teachers', { body: { ...r, ...d } }); toast('已保存'); renderPage(); } }) });
      actions.push({ l: '删除', cls: 'red', onClick: (r) => confirmBox(`仅未产生排课、课酬或评价的师资可以删除。确定检查并删除【${r.name}】？`, async () => { await api('/teachers/delete', { body: { id: r.id } }); toast('已删除'); renderPage(); }) });
    }
    const draw = (list) => {
      if (!isRouteCurrent(epoch, c, 'teachers')) return;
      const table = $('#tbl', c);
      if (!table) return;
      table.innerHTML = renderTable(cols, list, actions, 'teachers');
      bindTableActions(table, list, actions);
      const visibleInLib = list.filter((row) => row.status === '在库').length;
      const visibleAvg = list.length ? list.reduce((sum, row) => sum + Number(row.fee_rate || 0), 0) / list.length : 0;
      $('#result-count', c).textContent = list.length;
      $('#teachers-total', c).textContent = list.length;
      $('#teachers-inlib', c).textContent = visibleInLib;
      $('#teachers-rate', c).textContent = `¥ ${money(visibleAvg)}`;
    };
    draw(rows);
    let filterController = null;
    addRouteCleanup(() => filterController?.abort(), epoch);
    const runFilter = async () => {
      if (!isRouteCurrent(epoch, c, 'teachers')) return;
      const p = new URLSearchParams();
      const keyword = $('#flt-kw', c);
      const status = $('#flt-status', c);
      if (keyword.value) p.set('kw', keyword.value);
      if (status.value) p.set('status', status.value);
      state.filters.teachers = { kw: keyword.value.trim(), status: status.value };
      writeRouteToUrl(true);
      filterController?.abort();
      const controller = new AbortController();
      filterController = controller;
      try {
        const list = await api('/teachers?' + p.toString(), { signal: controller.signal });
        if (controller === filterController) draw(list);
      } catch (error) {
        if (!error || error.name !== 'AbortError') return;
      }
    };
    $('#flt-btn', c).onclick = runFilter;
    $('#flt-status', c).onchange = runFilter;
    const liveFilter = debounce(runFilter, 280);
    addRouteCleanup(liveFilter.cancel, epoch);
    $('#flt-kw', c).oninput = liveFilter;
    $('#flt-kw', c).onkeydown = (event) => {
      if (event.key === 'Enter') { event.preventDefault(); runFilter(); }
      if (event.key === 'Escape') { event.preventDefault(); $('#flt-kw', c).value = ''; runFilter(); }
    };
    if (savedFilter.kw || savedFilter.status) await runFilter();
    const addBtn = $('#add-btn', c);
    if (addBtn) addBtn.onclick = () => openModal('师资入库', renderForm(fields, { in_date: new Date().toISOString().slice(0, 10) }), {
      onOk: async () => { const d = collectForm($('#modal-mask'), fields); if (!d) return false; d.status = '在库'; await api('/teachers', { body: d }); toast('师资已入库'); renderPage(); },
    });
  }

  async function showTeacherEvals(t, projects, dispatches = []) {
    const epoch = routeEpoch;
    const evals = await api('/teacher_evals?teacher_id=' + t.id);
    if (!isRouteCurrent(epoch, null, 'teachers')) return;
    const deliveredProjectIds = new Set(dispatches.filter((item) => String(item.teacher_id) === String(t.id) && item.status === '已完成').map((item) => String(item.project_id)));
    const eligibleProjects = projects.filter((project) => project.status !== '已归档' && deliveredProjectIds.has(String(project.id)));
    const avg = evals.length ? (evals.reduce((s, e) => s + Number(e.score || 0), 0) / evals.length).toFixed(2) : '—';
    openModal(t.name, `
      <div class="teacher-profile"><span class="person-avatar large">${esc(t.name.slice(-2))}</span><div><h3>${esc(t.name)} ${tag(t.status)}</h3><p>${esc(t.org || '未填写单位')} · ${esc(t.title || '未填写职称')}</p><div><span>${icon('tags')}${esc(t.field || '未填写专业领域')}</span><span>${icon('badge-japanese-yen')}¥ ${money(t.fee_rate)} / 课时</span></div></div><strong>${avg}<small>综合评分</small></strong></div>
      <div class="teacher-info"><p><small>联系电话</small><span>${esc(t.phone || '—')}</span></p><p><small>电子邮箱</small><span>${esc(t.email || '—')}</span></p><p><small>入库日期</small><span>${esc(t.in_date || '—')}</span></p><p><small>授课评价</small><span>${evals.length} 条</span></p></div>
      <div class="teacher-intro"><small>师资简介</small><p>${esc(t.intro || '暂无简介')}</p></div>
      <div class="section-title response-title"><div><span>历史评价</span><small>来自培训项目的真实反馈</small></div></div>
      ${renderTable([
        { k: 'project_title', l: '培训项目' }, { k: 'score', l: '评分' },
        { k: 'comment', l: '评价内容' }, { k: 'evaluator', l: '评价人' }, { k: 'eval_date', l: '日期' },
      ], evals, null, 'teacher-evals')}
      ${canWrite() && eligibleProjects.length ? `<div class="eval-form"><div class="section-title"><div><span>新增评价</span><small>仅可评价已有完成授课记录的项目</small></div></div>
        <div style="margin-top:10px">${renderForm([
          { k: 'project_id', label: '培训项目', type: 'select', required: true, options: eligibleProjects.map((p) => ({ v: p.id, l: `#${p.id} ${p.title}` })) },
          { k: 'score', label: '评分（1-5）', type: 'select', required: true, options: ['5', '4.5', '4', '3.5', '3', '2.5', '2', '1.5', '1'] },
          { k: 'evaluator', label: '评价人', value: state.user.name || state.user.username },
          { k: 'eval_date', label: '评价日期', type: 'date', value: new Date().toISOString().slice(0, 10) },
          { k: 'comment', label: '评价内容', type: 'textarea' },
        ])}</div>
        <div style="text-align:right;margin-top:10px"><button type="button" class="btn" id="eval-add">${icon('send')}提交评价</button></div>
      </div>` : canWrite() ? `<div class="inline-note">${icon('info')}该讲师暂无已完成授课记录，完成课程交付后即可新增评价。</div>` : ''}
    `, { noFoot: true, wide: true, kicker: '师资完整档案' });
    const btn = $('#eval-add');
    if (btn) btn.onclick = async () => {
      const d = collectForm($('#modal-mask'), [
        { k: 'project_id', required: true }, { k: 'score', required: true },
        { k: 'evaluator' }, { k: 'eval_date' }, { k: 'comment' },
      ]);
      if (!d) return;
      d.teacher_id = t.id;
      d.score = Number(d.score);
      await api('/teacher_evals', { body: d });
      toast('评价已提交');
      closeModal();
      showTeacherEvals(t, projects, dispatches);
    };
  }

  // ============ 分析报告 ============
  async function pageReport(c) {
    const epoch = routeEpoch;
    const [txt, d] = await Promise.all([api('/stats/report'), api('/stats/overview')]);
    if (!isRouteCurrent(epoch, c, 'report')) return;
    c.innerHTML = `
      <section class="report-hero"><div><span class="eyebrow">OPERATIONS INSIGHT</span><h1>培训经营分析中心</h1><p>从合同与回款、已录支出、师资履约与交付质量四个维度审视业务表现。</p></div><div><button type="button" class="btn light" id="rp-refresh">${icon('refresh-cw')}刷新数据</button><button type="button" class="btn outline-light" id="rp-print">${icon('printer')}打印完整报告</button></div></section>
      <div class="module-summary four"><div><span>${icon('badge-japanese-yen')}</span><small>合同总额</small><b>${countTag(d.project_amount, 'money')}</b></div><div><span>${icon('circle-dollar-sign')}</span><small>实收金额</small><b>${countTag(d.received, 'money')}</b></div><div><span>${icon('chart-spline')}</span><small>按已录支出估算余额</small><b>${countTag(d.profit, 'money')}</b></div><div><span>${icon('star')}</span><small>讲师履约评分</small><b>${countTag(d.eval_avg)}<em>分</em></b></div></div>
      <div class="charts analysis-charts">
        <section class="chart-box"><div class="panel-head"><div><h2>客户贡献</h2><p>按委托单位统计合同金额</p></div></div><div class="chart" id="an1" role="img" aria-label="各委托单位合同金额条形图"><div class="mini-empty">${icon('loader-circle', 'spin')}正在加载图表</div></div></section>
        <section class="chart-box"><div class="panel-head"><div><h2>师资交付效率</h2><p>授课课时与单位课酬并列对照</p></div></div><div class="chart" id="an2" role="img" aria-label="各讲师授课课时与单位课酬并列条形图"><div class="mini-empty">${icon('loader-circle', 'spin')}正在加载图表</div></div></section>
        <section class="chart-box"><div class="panel-head"><div><h2>成本构成</h2><p>项目交付费用分类</p></div></div><div class="chart" id="an3" role="img" aria-label="项目成本构成环形图"><div class="mini-empty">${icon('loader-circle', 'spin')}正在加载图表</div></div></section>
        <section class="chart-box"><div class="panel-head"><div><h2>讲师履约评分</h2><p>基于已录入项目评价</p></div></div><div class="chart" id="an4" role="img" aria-label="讲师履约评分条形图"><div class="mini-empty">${icon('loader-circle', 'spin')}正在加载图表</div></div></section>
      </div>
      <div class="section-title report-title"><div><span>经营分析报告</span><small>由当前业务数据实时生成</small></div></div>
      <article class="report-box" id="rp-box">${esc(txt)}</article>`;
    $('#rp-refresh', c).onclick = () => renderPage();
    $('#rp-print', c).onclick = () => window.print();
    refreshIcons(c);
    const loadCharts = async () => {
      try { await ensureCharts(); }
      catch (error) {
        if (isRouteCurrent(epoch, c, 'report')) {
          $$('.chart', c).forEach((el) => { el.innerHTML = `<div class="mini-empty">${icon('chart-no-axes-column')}图表暂时无法加载，指标与文字报告仍可正常查看</div>`; });
          refreshIcons(c);
          toast(error.message || '图表组件加载失败', true);
        }
        return;
      }
      if (!isRouteCurrent(epoch, c, 'report')) return;
      const tooltip = { trigger: 'axis', backgroundColor: '#0e1116', borderWidth: 0, textStyle: { color: '#fff' } };
    makeChart('an1', {
      aria: { enabled: true, decal: { show: true } },
      color: ['#4d5dfb'], tooltip, grid: { left: 12, right: 65, top: 15, bottom: 15, containLabel: true },
      xAxis: { type: 'value', splitLine: { lineStyle: { color: '#e4e6df' } }, axisLabel: { formatter: (v) => v >= 10000 ? v / 10000 + '万' : v } },
      yAxis: { type: 'category', data: d.by_unit.map((x) => x.unit).reverse(), axisLine: { show: false }, axisTick: { show: false } },
      series: [{ type: 'bar', barMaxWidth: 22, data: d.by_unit.map((x) => x.amt).reverse(), itemStyle: { borderRadius: [0,5,5,0] }, label: { show: true, position: 'right', formatter: (x) => x.value >= 10000 ? (x.value / 10000).toFixed(1) + '万' : x.value } }],
    }, c);
    makeChart('an2', {
      aria: { enabled: true, decal: { show: true } },
      color: ['#4d5dfb', '#22d3ee'], tooltip, legend: { data: ['课时', '单位课酬'], right: 0, icon: 'circle' }, grid: { left: 45, right: 58, top: 45, bottom: 50 },
      xAxis: { type: 'category', data: d.by_teacher.map((x) => x.name), axisLine: { show: false }, axisTick: { show: false } },
      yAxis: [{ type: 'value', splitLine: { lineStyle: { color: '#e4e6df' } } }, { type: 'value', splitLine: { show: false }, axisLabel: { formatter: (v) => v >= 10000 ? v / 10000 + '万' : v } }],
      series: [{ name: '课时', type: 'bar', barMaxWidth: 20, data: d.by_teacher.map((x) => x.hours), itemStyle: { borderRadius: [5,5,0,0] } }, { name: '单位课酬', type: 'bar', barMaxWidth: 20, yAxisIndex: 1, data: d.by_teacher.map((x) => Number(x.hours || 0) ? Number(x.fee || 0) / Number(x.hours) : 0), itemStyle: { borderRadius: [5,5,0,0] } }],
    }, c);
    makeChart('an3', {
      aria: { enabled: true, decal: { show: true } },
      color: ['#4d5dfb', '#8b5cf6', '#22d3ee', '#7c3aed', '#8a90a3'], tooltip: { trigger: 'item', backgroundColor: '#0e1116', borderWidth: 0, textStyle: { color: '#fff' } }, legend: { bottom: 0, icon: 'circle', itemWidth: 8 },
      series: [{ type: 'pie', radius: ['48%', '70%'], center: ['50%', '44%'], padAngle: 2, itemStyle: { borderRadius: 3 }, label: { formatter: '{b}\n¥{c}', color: '#626d67' }, data: d.cost_type.map((x) => ({ name: x.type, value: x.amt })) }],
    }, c);
    makeChart('an4', {
      aria: { enabled: true, decal: { show: true } },
      color: ['#4d5dfb'], tooltip, grid: { left: 12, right: 42, top: 18, bottom: 18, containLabel: true },
      xAxis: { type: 'value', max: 5, splitLine: { lineStyle: { color: '#e4e6df' } } }, yAxis: { type: 'category', data: d.teacher_score.map((x) => x.name).reverse(), axisLine: { show: false }, axisTick: { show: false } },
      series: [{ type: 'bar', barMaxWidth: 22, data: d.teacher_score.map((x) => x.score).reverse(), itemStyle: { borderRadius: [0,5,5,0] }, label: { show: true, position: 'right' } }],
    }, c);
    };
    loadCharts();
  }

  // ============ 用户管理 ============
  async function pageUsers(c) {
    const epoch = routeEpoch;
    const rows = await api('/users');
    if (!isRouteCurrent(epoch, c, 'users')) return;
    const roleName = (r) => ({ admin: '系统管理员', manager: '业务管理员', viewer: '只读用户' }[r] || r);
    const enabled = rows.filter((r) => Number(r.status) === 1).length;
    c.innerHTML = `<div class="module-summary three"><div><span>${icon('users-round')}</span><small>系统用户</small><b>${countTag(rows.length)}<em>人</em></b></div><div><span>${icon('user-check')}</span><small>启用账号</small><b>${countTag(enabled)}<em>个</em></b></div><div><span>${icon('shield-check')}</span><small>管理员</small><b>${countTag(rows.filter((r) => r.role === 'admin').length)}<em>人</em></b></div></div><div class="card data-card">
      <div class="card-heading"><div><h2>用户与权限</h2><p>管理员拥有完整权限，业务管理员可操作业务，只读用户仅查询分析</p></div><button type="button" class="btn" id="add-btn">${icon('user-plus')}新增用户</button></div>
      <div id="tbl"></div>
    </div>`;
    const fields = [
      { k: 'username', label: '用户名', required: true },
      { k: 'name', label: '姓名', required: true },
      { k: 'role', label: '角色', type: 'select', required: true, hint: '遵循最小权限原则；请根据实际职责选择', options: [{ v: 'admin', l: '系统管理员' }, { v: 'manager', l: '业务管理员' }, { v: 'viewer', l: '只读用户' }] },
      { k: 'status', label: '状态', type: 'select', options: [{ v: 1, l: '启用' }, { v: 0, l: '停用' }] },
      { k: 'password', label: '登录密码', type: 'password', span2: true, placeholder: '编辑时留空则不修改', autocomplete: 'new-password', hint: '至少 8 位字符' },
    ];
    const cols = [
      { k: 'id', l: '编号', mobileHide: true }, { k: 'username', l: '用户名' }, { k: 'name', l: '姓名' },
      { k: 'role', l: '角色', render: (r) => tag(roleName(r.role)) },
      { k: 'status', l: '状态', render: (r) => tag(Number(r.status) === 1 ? '启用' : '停用') },
      { k: 'created_at', l: '创建时间', mobileHide: true },
    ];
    const actions = [
      { l: '重置密码', cls: 'orange', onClick: (r) => {
        openModal(`重置密码 - ${r.username}`, renderForm([{ k: 'password', label: '新密码', type: 'password', required: true, span2: true, autocomplete: 'new-password', hint: '至少 8 位字符' }]), {
          sm: true,
          onOk: async () => {
            const d = collectForm($('#modal-mask'), [{ k: 'password', label: '新密码', required: true }]);
            if (!d) return false;
            if (d.password.length < 8) { toast('密码至少需要 8 位', true); return false; }
            await api('/users/resetpwd', { body: { id: r.id, password: d.password } });
            toast('密码已重置');
          },
        });
      } },
      { l: '编辑', cls: 'gray', onClick: (r) => openModal('编辑用户', renderForm(fields, { ...r, password: '' }), {
        onOk: async () => {
          const d = collectForm($('#modal-mask'), fields);
          if (!d) return false;
          if (d.password && d.password.length < 8) { toast('密码至少需要 8 位', true); return false; }
          d.id = r.id;
          d.status = Number(d.status);
          await api('/users', { body: d });
          toast('已保存');
          renderPage();
        },
      }) },
      { l: '删除', cls: 'red', show: (r) => r.username !== 'admin' && String(r.id) !== String(state.user.uid), onClick: (r) => confirmBox(`确定删除用户【${r.username}】？删除后该账号将不能继续登录。`, async () => { await api('/users/delete', { body: { id: r.id } }); toast('已删除'); renderPage(); }) },
    ];
    const table = $('#tbl', c);
    const draw = (list) => { table.innerHTML = renderTable(cols, list, actions, 'users'); bindTableActions(table, list, actions); };
    draw(rows);
    $('#add-btn', c).onclick = () => openModal('新增用户', renderForm(fields, { status: 1 }), {
      onOk: async () => {
        const d = collectForm($('#modal-mask'), fields.map((f) => (f.k === 'password' ? { ...f, required: true } : f)));
        if (!d) return false;
        if (d.password.length < 8) { toast('密码至少需要 8 位', true); return false; }
        d.status = Number(d.status);
        await api('/users', { body: d });
        toast('用户已创建');
        renderPage();
      },
    });
  }

  // ============ 启动 ============
  (async function boot() {
    // 清理旧版浏览器令牌；认证由服务端 HttpOnly Cookie 承载。
    localStorage.removeItem('token');
    try {
      state.user = await api('/me');
      restoreRouteFromUrl();
      writeRouteToUrl(true);
      renderLayout();
    } catch (e) {
      if (!$('.v6-login')) renderLogin();
    }
  })();

  function handleLocationRoute() {
    if (!state.user) { document.title = '登录 · 研序'; return; }
    if (location.hash === lastRenderedHash) return;
    if (!restoreRouteFromUrl()) {
      writeRouteToUrl(true);
      lastRenderedHash = location.hash;
      return;
    }
    renderLayout();
  }

  window.addEventListener('popstate', handleLocationRoute);
  window.addEventListener('hashchange', handleLocationRoute);
})();
