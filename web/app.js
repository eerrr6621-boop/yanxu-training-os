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
  function collectionProgress(contractValue, dueValue, receivedValue) {
    const contract = Number(contractValue) || 0;
    const due = Number(dueValue) || 0;
    const received = Number(receivedValue) || 0;
    const target = contract > 0 ? contract : due;
    // 金额按分呈现，避免 0.1 + 0.7 的浮点尾差生成 ¥0.00 回款待办。
    const outstanding = Math.max(0, Math.round((target - received) * 100) / 100);
    return {
      target,
      outstanding,
      rate: target > 0 ? (outstanding === 0 ? 100 : Math.min(100, Math.max(0, received / target * 100))) : 0,
      mismatch: contract > 0 && Math.abs(due - contract) > 0.005,
    };
  }
  function collectionStageStatus(progress, received) {
    // 客户回款和讲师课酬是两个独立流程；课酬不会阻止回款阶段完成。
    return progress.outstanding === 0 && !progress.mismatch ? 'done' : received > 0 ? 'current' : 'todo';
  }
  const shiftIsoDate = (date, days) => {
    if (!date) return '';
    const parts = String(date).split('-').map(Number);
    if (parts.length !== 3 || parts.some((part) => !Number.isFinite(part))) return '';
    const value = new Date(Date.UTC(parts[0], parts[1] - 1, parts[2] + days));
    return value.toISOString().slice(0, 10);
  };
  const icon = (name, cls = '') => `<i data-lucide="${esc(name)}" class="${esc(cls)}" aria-hidden="true"></i>`;
  const brandSymbol = (cls = '') => `<img class="brand-symbol ${esc(cls)}" src="/assets/yx-mark-v13.png?v=20260906v13" alt="" aria-hidden="true">`;
  const BUSINESS_ART = Object.freeze({
    dashboard: '/assets/icons/dashboard-v13.png',
    projects: '/assets/icons/projects-v13.png',
    documents: '/assets/icons/documents-v13.png',
    contract: '/assets/icons/contract-v13.png',
    calendar: '/assets/icons/calendar-v13.png',
    faculty: '/assets/icons/faculty-v13.png',
    evaluation: '/assets/icons/evaluation-v13.png',
    collection: '/assets/icons/collection-v13.png',
    fees: '/assets/icons/fees-v13.png',
    costs: '/assets/icons/costs-v13.png',
    report: '/assets/icons/report-v13.png',
    access: '/assets/icons/access-v13.png',
    materials: '/assets/icons/materials-v13.png',
    recommend: '/assets/icons/recommend-v13.png',
  });
  const businessArt = (kind) => `<img class="business-art" src="${Object.hasOwn(BUSINESS_ART, kind) ? BUSINESS_ART[kind] : BUSINESS_ART.documents}?v=20260906v13i1" alt="" aria-hidden="true" width="48" height="48" decoding="async">`;
  function taskArtKind(item) {
    if (['documents', 'calendar', 'faculty', 'collection', 'evaluation', 'fees', 'costs'].includes(item?.art)) return item.art;
    switch (item?.page) {
      case 'dispatches': return 'faculty';
      case 'charges': return 'collection';
      case 'questionnaires': return 'evaluation';
      case 'fees': return 'fees';
      case 'costs': return 'costs';
      default: return 'documents';
    }
  }
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

  const state = { user: null, page: 'dashboard', projectId: null, contextProjectId: null, focusId: null, initialFilter: null, filters: {}, cache: {}, teacherTab: 'library', teacherRequirementDraft: '', teacherRecommendationForm: { demandId: '', maxResults: '3', maxFeeRate: '', hardBudget: false } };
  let routeEpoch = 0;
  let routeCleanups = [];
  let lastRenderedHash = '';
  let navKeyHandler = null;
  let navViewportCleanup = null;
  let globalKeyHandler = null;
  let scrollShadowHandler = null;
  let v6PointerHandler = null;
  let loginRotTimer = null;
  let loginRotSwap = null;
  let v7GlowHandler = null;
  let loginMotion = null;
  let environmentPanel = null;
  let loginVerification = null;
  let loginVerificationGeneration = 0;
  function disposeLoginExperience() {
    loginVerificationGeneration++;
    loginVerification?.destroy(); loginVerification = null;
    for (const key of ['login-pwd', 'login-bind-email', 'login-code']) { const field = $('#' + key); if (field) field.value = ''; }
    environmentPanel?.destroy(); environmentPanel = null;
    try { loginMotion?.destroy(); } catch (_) { /* Optional artwork must not block navigation. */ }
    finally { loginMotion = null; }
  }

  function clearRouteAsync() {
    const cleanups = routeCleanups;
    routeCleanups = [];
    cleanups.forEach((cleanup) => { try { cleanup(); } catch (e) {} });
  }

  function beginRouteEpoch() {
    closeRowMenu();
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
    state.teacherTab = 'library';
    state.teacherRequirementDraft = '';
    state.teacherRecommendationForm = { demandId: '', maxResults: '3', maxFeeRate: '', hardBudget: false };
    localStorage.removeItem('token');
    clearTimeout(toastTimer);
    toastTimer = null;
    $('.toast')?.remove();
    const sessionModal = $('#modal-mask');
    if (sessionModal) sessionModal.dataset.locked = 'false';
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
        if (mode === 'shell' || document.body.classList.contains('yx-v13')) {
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
    closeRowMenu();
    const { projectId = null, focusId = null, filter = null, skipHistory = false, replaceHistory = false } = options;
    if (closeModal() === false) return;
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
      if (!opts.quiet) toast(navigator.onLine ? '服务暂时不可用，请稍后重试' : '网络已断开，恢复连接后请重试', true);
      throw new Error('网络连接失败');
    }
    const raw = await res.text();
    let j;
    try { j = JSON.parse(raw); } catch (e) {
      if (!opts.quiet) toast('服务返回了无法识别的数据，请稍后重试', true);
      throw new Error('响应格式错误');
    }
    if (j.code === 401) {
      localStorage.removeItem('token');
      if (path !== '/login') invalidateSession();
      const error = new Error(j.msg || '登录状态已失效，请重新登录');
      error.status = res.status; error.code = j.code; error.data = j.data || {};
      throw error;
    }
    if (j.code !== 0) {
      if (!opts.quiet) toast(j.msg || '操作失败', true);
      const error = new Error(j.msg || '操作失败');
      error.status = res.status; error.code = j.code; error.data = j.data || {};
      throw error;
    }
    if (path === '/organization/config' && opts.body !== undefined && j.data?.sessionInvalidated === true) {
      invalidateSession();
      toast('账号配置已保存，请重新登录');
    }
    return j.data;
  }

  function encodeBase64UrlUtf8(value) {
    const bytes = new TextEncoder().encode(String(value || ''));
    let binary = '';
    bytes.forEach((byte) => { binary += String.fromCharCode(byte); });
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
  }

  /** 讲师简历使用原始 PDF/PPTX 请求体上传，避免通用 JSON 请求器破坏文件内容。 */
  async function uploadTeacherResume(file, teacherId, opts = {}) {
    return new Promise((resolve, reject) => {
      const request = new XMLHttpRequest();
      const abort = () => request.abort();
      const cleanup = () => opts.signal?.removeEventListener('abort', abort);
      const fail = (message) => { cleanup(); toast(message, true); reject(new Error(message)); };
      request.open('POST', `/api/teacher-resumes/upload?teacher_id=${encodeURIComponent(teacherId)}`);
      request.setRequestHeader('Content-Type', /\.pptx$/i.test(file.name || '') ? 'application/vnd.openxmlformats-officedocument.presentationml.presentation' : 'application/pdf');
      request.setRequestHeader('X-Resume-Name', encodeBase64UrlUtf8(file.name));
      request.timeout = 10 * 60 * 1000;
      request.upload.onprogress = (event) => opts.onProgress?.(event.loaded, event.lengthComputable ? event.total : file.size);
      request.upload.onload = () => opts.onProgress?.(file.size, file.size);
      request.onerror = () => fail(navigator.onLine ? '简历上传失败，请稍后重试' : '网络已断开，恢复连接后请重试');
      request.ontimeout = () => fail('上传等待时间过长，请检查网络后重试');
      request.onabort = () => { cleanup(); reject(new DOMException('上传已取消', 'AbortError')); };
      request.onload = () => {
        cleanup();
        let payload;
        try { payload = JSON.parse(request.responseText); }
        catch (error) { fail(request.status === 413 ? '文件超过服务器允许的大小，请压缩后重试' : '上传服务暂时不可用，请稍后重试'); return; }
        if (payload.code === 401) {
          const activeModal = $('#modal-mask');
          if (activeModal) activeModal.dataset.locked = 'false';
          invalidateSession();
          reject(new Error(payload.msg || '登录状态已失效，请重新登录'));
          return;
        }
        if (request.status < 200 || request.status >= 300 || payload.code !== 0) { fail(payload.msg || '简历上传失败'); return; }
        resolve(payload.data);
      };
      if (opts.signal?.aborted) { reject(new DOMException('上传已取消', 'AbortError')); return; }
      opts.signal?.addEventListener('abort', abort, { once: true });
      request.send(file);
    });
  }

  async function downloadTeacherResume(teacherId, fallbackName = '讲师简历.pdf') {
    let res;
    try {
      res = await fetch(`/api/teacher-resumes/download?teacher_id=${encodeURIComponent(teacherId)}`, { credentials: 'same-origin' });
    } catch (error) {
      toast('简历下载失败，请检查网络后重试', true);
      return;
    }
    if (!res.ok || String(res.headers.get('content-type') || '').includes('application/json')) {
      let message = '简历下载失败';
      try {
        const payload = JSON.parse(await res.text());
        message = payload.msg || message;
        if (payload.code === 401) invalidateSession();
      } catch (error) { /* 保留通用提示 */ }
      toast(message, true);
      return;
    }
    const blob = await res.blob();
    const href = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = href;
    link.download = String(fallbackName || '讲师简历.pdf').replace(/[\\/:*?"<>|]/g, '_');
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(href), 1200);
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
    if (closeModal() === false) return null;
    closeRowMenu();
    modalPreviousFocus = document.activeElement;
    const mask = document.createElement('div');
    mask.className = 'mask';
    mask.id = 'modal-mask';
    mask._closeCleanup = opts.onClose;
    mask._beforeClose = opts.beforeClose;
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
      if (e.defaultPrevented) return;
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
  function closeModal(force = false) {
    const m = $('#modal-mask');
    if (!m) return true;
    if (force !== true && m._beforeClose && m._beforeClose() === false) return false;
    if (force !== true && m.dataset.locked === 'true') {
      $('.modal', m)?.classList.add('attention');
      setTimeout(() => $('.modal', m)?.classList.remove('attention'), 220);
      return false;
    }
    if (activeRowMenu?.owner === m) closeRowMenu();
    if (m._keyHandler) document.removeEventListener('keydown', m._keyHandler);
    m._closeCleanup?.();
    chartInstances = chartInstances.filter((chart) => {
      try { if (m.contains(chart.getDom())) { chart.dispose(); return false; } } catch (e) {}
      return true;
    });
    m.remove();
    document.body.classList.remove('modal-open');
    if (modalPreviousFocus && document.contains(modalPreviousFocus)) modalPreviousFocus.focus();
    modalPreviousFocus = null;
    return true;
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
      if (f.regionProvinceKey) {
        const cities = typeof YX_REGION_CITIES === 'undefined' ? [] : (YX_REGION_CITIES[data?.[f.regionProvinceKey]] || []);
        input = input.replace('<input ', `<input list="${id}-cities" data-region-province="${esc(f.regionProvinceKey)}" `) + `<datalist id="${id}-cities">${cities.map((city) => `<option value="${esc(city)}"></option>`).join('')}</datalist>`;
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
        if (v === '' && f.nullable) { data[f.k] = null; continue; }
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
  // Keep actions outside table clipping, without expanding or shifting the row.
  let activeRowMenu = null;
  function closeRowMenu(restoreFocus = false) {
    if (!activeRowMenu) return;
    const { menu, panel, summary } = activeRowMenu;
    activeRowMenu = null;
    menu.appendChild(panel);
    panel.classList.remove('v13-row-menu');
    panel.removeAttribute('style');
    menu.open = false;
    if (restoreFocus && summary.isConnected) summary.focus();
  }
  function openRowMenu(menu) {
    if (activeRowMenu?.menu === menu) return;
    closeRowMenu();
    const panel = menu.querySelector(':scope > div');
    const summary = menu.querySelector('summary');
    if (!panel || !summary) return;
    const owner = menu.closest('#modal-mask');
    activeRowMenu = { menu, panel, summary, owner };
    panel.classList.add('v13-row-menu');
    // Keep modal actions above their own backdrop and inside its focus boundary.
    // The mask is outside the dialog/table clipping while retaining viewport coordinates.
    (owner || document.body).appendChild(panel);
    const rect = summary.getBoundingClientRect();
    const width = Math.min(200, window.innerWidth - 24);
    panel.style.width = `${width}px`;
    panel.style.left = `${Math.max(12, Math.min(rect.right - width, window.innerWidth - width - 12))}px`;
    const below = window.innerHeight - rect.bottom - 16;
    const above = rect.top - 16;
    const height = Math.min(panel.scrollHeight, Math.max(below, above), 360);
    panel.style.maxHeight = `${Math.max(44, height)}px`;
    panel.style.top = `${below >= height ? rect.bottom + 6 : Math.max(12, rect.top - height - 6)}px`;
    panel.onkeydown = (event) => {
      const buttons = $$('.menu-action:not(:disabled)', panel);
      const index = buttons.indexOf(document.activeElement);
      if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
        event.preventDefault();
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length;
        buttons[next]?.focus();
      } else if (event.key === 'Tab' && ((event.shiftKey && index === 0) || (!event.shiftKey && index === buttons.length - 1))) {
        closeRowMenu(true);
        if (event.shiftKey) event.preventDefault();
      }
    };
  }
  document.addEventListener('pointerdown', (event) => {
    if (activeRowMenu && !activeRowMenu.panel.contains(event.target) && !activeRowMenu.summary.contains(event.target)) closeRowMenu();
  });
  document.addEventListener('focusin', (event) => {
    if (activeRowMenu && !activeRowMenu.panel.contains(event.target) && !activeRowMenu.summary.contains(event.target)) closeRowMenu();
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && activeRowMenu) { event.preventDefault(); closeRowMenu(true); }
  });
  window.addEventListener('resize', () => closeRowMenu());
  document.addEventListener('scroll', (event) => {
    if (activeRowMenu && !activeRowMenu.panel.contains(event.target)) closeRowMenu();
  }, true);

  function actionPriority(action, row) {
    const label = action.l;
    if (label === '补齐准备') return row && row.status === '已确认' ? -1 : 2;
    if (['提交需求', '办理审批', '同时完成负责人和BP审批', '团队受理', '登记结果', '中标', '启动项目', '收款', '记录通知', '确认', '完成', '授课记录', '发布', '发送链接', '发放', '入库'].includes(label)) return 0;
    if (['打开项目', '详情', '统计', '档案', '消息', '解析详情'].includes(label)) return 1;
    if (['编辑需求', '编辑', '评价', '重置密码', '编辑画像'].includes(label)) return 3;
    if (['归档', '关闭', '出库', '删除', '删除简历'].includes(label)) return 9;
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
    const map = { '提交需求': 'send', '办理审批': 'clipboard-check', '团队受理': 'handshake', '登记结果': 'file-check-2', '编辑需求': 'pencil', '详情': 'eye', '打开项目': 'arrow-up-right', '项目总览': 'panels-top-left', '中标': 'badge-check', '启动项目': 'play', '收款': 'circle-dollar-sign', '完成交付': 'circle-check-big', '归档': 'archive', '编辑': 'pencil', '删除': 'trash-2', '消息': 'message-square-text', '记录通知': 'message-square-share', '确认': 'check-circle-2', '完成': 'circle-check', '统计': 'chart-no-axes-column-increasing', '发布': 'rocket', '发微信': 'send', '关闭': 'circle-x', '发放': 'badge-dollar-sign', '评价': 'star', '出库': 'log-out', '入库': 'log-in', '重置密码': 'key-round' };
    return map[label] || 'arrow-right';
  }

  function bindTableActions(el, rows, actions) {
    if (activeRowMenu && !activeRowMenu.menu.isConnected) closeRowMenu();
    $$('.row-more', el).forEach((menu) => {
      menu.ontoggle = () => {
        if (menu.open) openRowMenu(menu);
        else if (activeRowMenu?.menu === menu) closeRowMenu();
      };
      $('summary', menu).onkeydown = (event) => {
        if (!['ArrowDown', 'Enter', ' '].includes(event.key)) {
          if (event.key === 'Tab' && activeRowMenu?.menu === menu) {
            if (event.shiftKey) closeRowMenu();
            else { event.preventDefault(); activeRowMenu.panel.querySelector('button')?.focus(); }
          }
          return;
        }
        event.preventDefault();
        if (event.key !== 'ArrowDown' && activeRowMenu?.menu === menu) { closeRowMenu(true); return; }
        menu.open = true;
        openRowMenu(menu);
        activeRowMenu?.panel.querySelector('button')?.focus();
      };
    });
    $$('.btn[data-act]', el).forEach((btn) => {
      btn.onclick = () => {
        const a = actions[Number(btn.dataset.act)];
        const row = rows.find((r) => String(r.id) === btn.dataset.id);
        closeRowMenu(true);
        a.onClick(row);
      };
    });
    refreshIcons(el);
  }

  const tagClass = (v) => {
    if (['已确认', '已完成', '已结清', '已发放', '已中标', '在库', '已立项', '已就绪', '启用', '可推荐'].includes(v)) return 'green';
    if (['待发送', '待评审', '待处理', '待发放', '未收费', '待启动', '待准备', '材料待补充', '待确认', '等待解析', '待负责人审批', '待BP审批', '待团队受理', '待投标结果'].includes(v)) return 'orange';
    if (['已拒绝', '未中标', '已流标', '解析失败', '已退回'].includes(v)) return 'red';
    if (['部分收费', '进行中', '已发送', '已投标', '准备中', '解析中'].includes(v)) return 'blue';
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
    disposeLoginExperience();
    state.user = null;
    document.title = '登录 · 研序';
    if (navKeyHandler) { document.removeEventListener('keydown', navKeyHandler); navKeyHandler = null; }
    if (navViewportCleanup) { navViewportCleanup(); navViewportCleanup = null; }
    if (globalKeyHandler) { document.removeEventListener('keydown', globalKeyHandler); globalKeyHandler = null; }
    if (scrollShadowHandler) { window.removeEventListener('scroll', scrollShadowHandler); scrollShadowHandler = null; }
    if (v7GlowHandler) { window.removeEventListener('pointermove', v7GlowHandler); v7GlowHandler = null; }
    clearCharts();
    sceneBridge.setMode('login');
    document.getElementById('app').innerHTML = `
      <main class="v6-login login-learning" aria-label="研序登录">
        <div class="login-ambient" aria-hidden="true"><span></span></div>
        <header class="login-topbar">
          <div class="orbit-brand">${brandSymbol()}<span>研序</span></div>
          <nav class="login-public-nav" aria-label="公共页面"><a class="login-materials" href="/materials.html">${businessArt('materials')}<span>培训资料下载</span>${icon('arrow-up-right')}</a><a class="login-history" href="/updates.html"><span>更新记录</span><img class="login-new" src="/assets/new-wordmark-v13r11.webp" alt="NEW" width="160" height="44"></a></nav>
        </header>
        <div class="login-layout">
          <section class="login-visual" data-login-visual aria-label="培训运营，就用研序">
            <div class="book-stage" data-login-book role="img" aria-label="培训运营、师资推荐、课程交付、项目管理，就用研序。主题自动轮播。" title="拖动查看书本">
              <div class="book-fallback" aria-hidden="true">
                <div class="book-fallback-left"><strong class="book-heading-art"><img src="/assets/book-headline-operations-v13r9.webp" alt="培训运营" width="960" height="240"></strong><span class="book-endorsement"><img src="/assets/book-endorsement-v13r8.png" alt="就用 →" width="960" height="320"></span></div>
                <div class="book-fallback-right">${brandSymbol()}<b>研序</b></div>
              </div>
            </div>
          </section>
          <section class="orbit-panel" aria-labelledby="login-heading">
            <div class="login-environment" data-environment><div class="environment-time"><time data-local-clock aria-label="设备本地时间">—</time><span data-local-date></span></div><div class="environment-weather" data-local-weather aria-label="所在城市天气">正在获取天气…</div></div>
            <div class="login-panel-heading"><span class="login-product-name">YANXU / WORKSPACE</span><h1 id="login-heading">欢迎回到研序</h1></div>
            <form id="login-form">
              <div class="login-err" id="login-err" role="alert" aria-live="polite"></div>
              <div class="login-credentials" id="login-password-step" style="grid-template-columns:minmax(0,1fr)">
                <div class="login-field"><label for="login-user">账号</label><div class="login-input"><input id="login-user" name="username" placeholder="输入您的账号" autocomplete="username" required aria-describedby="login-user-hint"></div></div>
                <div class="login-field"><label for="login-pwd">密码</label><div class="login-input"><input id="login-pwd" name="password" type="password" placeholder="输入密码" autocomplete="current-password" required aria-describedby="caps-lock-note"><button type="button" id="pwd-toggle" aria-label="显示密码">${icon('eye')}</button></div></div>
              </div>
              <div id="login-bind-step" hidden style="display:none">
                <p id="login-bind-hint" role="status" style="font-size:14px;line-height:1.65;margin:0 0 14px">账号验证通过。首次登录请填写本人常用邮箱，完成收信验证后即可进入工作台。</p>
                <div class="login-field"><label for="login-bind-email">本人邮箱</label><div class="login-input"><input id="login-bind-email" name="binding_email" type="email" autocomplete="email" maxlength="254" placeholder="输入本人可收信的邮箱" disabled aria-describedby="login-bind-hint"></div></div>
              </div>
              <div id="login-email-step" hidden style="display:none">
                <p id="login-email-hint" role="status" style="font-size:14px;line-height:1.65;overflow-wrap:anywhere;margin:0 0 14px"></p>
                <div class="login-field"><label for="login-code">邮箱验证码</label><div class="login-input"><input id="login-code" name="verification_code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" placeholder="输入 6 位数字验证码" disabled aria-describedby="login-email-hint login-code-time"></div></div>
                <p id="login-code-time" role="status" style="font-size:13px;line-height:1.6;margin:10px 0 0"></p>
              </div>
              <span class="sr-only" id="login-user-hint">请输入您的研序授权账号</span>
              <div class="v10-caps" id="caps-lock-note" role="status" aria-live="polite"></div>
              <button type="submit" class="login-submit" id="login-btn"><span>进入工作台</span>${icon('arrow-right')}</button>
              <div id="login-email-actions" class="toolbar" hidden style="display:none;flex-wrap:wrap;gap:10px;margin:12px 0 0"><button type="button" class="btn gray sm" id="login-resend">重新发送</button><button type="button" class="btn gray sm" id="login-back">返回账号密码</button></div>
            </form>
            <a class="login-release-link" href="/updates.html"><span>了解研序的每一次进步</span>${icon('arrow-up-right')}</a>
          </section>
        </div>
      </main>`;
    refreshIcons(document.getElementById('app'));
    environmentPanel = window.YanxuEnvironment?.mount($('[data-environment]'));
    const v6bg = $('.v6-bg');
    if (v6bg && !document.body.classList.contains('yx-v13') && !prefersReducedMotion() && window.matchMedia('(pointer: fine)').matches) {
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
    const loginForm = $('#login-form'), generation = loginVerificationGeneration;
    const currentLogin = () => generation === loginVerificationGeneration && loginForm.isConnected && $('#login-form') === loginForm && !state.user;
    const doLogin = async () => {
      if (!currentLogin() || $('#login-btn').disabled) return;
      if (loginVerification) return loginVerification.submit();
      const btn = $('#login-btn'); btn.disabled = true;
      $('#login-err').textContent = '';
      try {
        const module = await import('/modules/notifications/login-verification.js?v=20260924firstbind1');
        if (!currentLogin()) return;
        let mounted;
        mounted = module.mountLoginVerification(loginForm, {
          isCurrent: currentLogin,
          onDisposed: () => { if (loginVerification === mounted) loginVerification = null; },
          setSubmitLabel: (label, loading) => { btn.innerHTML = '<span>' + esc(label) + '</span>' + icon(loading ? 'loader-circle' : 'arrow-right', loading ? 'spin' : ''); refreshIcons(btn); },
          onPasswordHidden: () => { const toggle = $('#pwd-toggle', loginForm); toggle.innerHTML = icon('eye'); toggle.setAttribute('aria-label', '显示密码'); refreshIcons(toggle); },
          onPhase: value => { sceneBridge.setPhase(value); loginMotion?.setPhase(value); },
          onAuthenticated: (user, username) => {
            if (!currentLogin()) return;
            localStorage.removeItem('token'); localStorage.setItem('yx_last_username', username);
            state.user = user;
            if (!restoreRouteFromUrl()) {
              state.page = 'dashboard'; state.projectId = null; state.contextProjectId = null; state.focusId = null; state.initialFilter = null; writeRouteToUrl(true);
            }
            renderLayout();
          },
        });
        loginVerification = mounted;
        return await mounted.submit();
      } catch (_) {
        if (currentLogin()) { $('#login-pwd').value = ''; $('#login-err').textContent = '登录验证暂时无法加载，请稍后重试。'; btn.disabled = false; }
      }
    };
    loginForm.onsubmit = (e) => { e.preventDefault(); return doLogin(); };
    $('#pwd-toggle').onclick = () => {
      const input = $('#login-pwd');
      input.type = input.type === 'password' ? 'text' : 'password';
      $('#pwd-toggle').innerHTML = icon(input.type === 'password' ? 'eye' : 'eye-off');
      $('#pwd-toggle').setAttribute('aria-label', input.type === 'password' ? '显示密码' : '隐藏密码');
      refreshIcons($('#pwd-toggle'));
    };
    // Authentication remains usable if the optional visual cannot initialize.
    const loginRoot = $('.login-learning');
    try {
      if (typeof window.YanxuLoginMotion?.mount !== 'function') throw new Error('Visual module unavailable');
      loginMotion = window.YanxuLoginMotion.mount(loginRoot);
    } catch (_) { loginRoot.dataset.motionState = 'unavailable'; }
    // Do not steal focus or open a mobile keyboard on entry. Native Tab order remains intact.
  }

  // ============ 主布局 ============
  const NAV = [
    { k: 'dashboard', l: '今日运营', ico: 'layout-dashboard', art: 'dashboard', group: '工作台' },
    { k: 'projects', l: '项目总览', ico: 'folder-kanban', art: 'projects', group: '项目运营' },
    { k: 'demands', l: '培训需求', ico: 'inbox', art: 'documents', group: '项目运营' },
    { k: 'bids', l: '投标与立项', ico: 'file-check-2', art: 'contract', group: '项目运营' },
    { k: 'dispatches', l: '课程与排期', ico: 'calendar-clock', art: 'calendar', group: '交付协同' },
    { k: 'questionnaires', l: '效果评估', ico: 'clipboard-check', art: 'evaluation', group: '交付协同' },
    { k: 'teachers', l: '师资资源', ico: 'contact-round', art: 'faculty', group: '交付协同' },
    { k: 'charges', l: '项目回款', ico: 'badge-japanese-yen', art: 'collection', group: '财务结算' },
    { k: 'fees', l: '课酬发放', ico: 'wallet-cards', art: 'fees', group: '财务结算' },
    { k: 'costs', l: '成本费用', ico: 'receipt-text', art: 'costs', group: '财务结算' },
    { k: 'report', l: '经营洞察', ico: 'chart-no-axes-combined', art: 'report', group: '分析' },
    { k: 'users', l: '用户与权限', ico: 'shield-check', art: 'access', group: '系统', admin: true },
  ];

  const PAGE_META = {
    dashboard: ['今日运营', ''], demands: ['培训需求', '统一沉淀客户需求与培训目标'],
    bids: ['投标与立项', '管理投标方案、报价与立项结果'], projects: ['项目总览', '以项目为中心协同交付与结算'],
    dispatches: ['课程与排期', '安排课程、发送邀请并跟踪讲师确认'], questionnaires: ['效果评估', '汇总培训反馈与查看历史评估'],
    teachers: ['师资资源', '管理讲师档案、简历解析、智能匹配与授课评价'], charges: ['项目回款', '跟踪应收、回款进度与票据信息'],
    fees: ['课酬发放', '根据确认课时核算并发放课酬'], costs: ['成本费用', '记录项目交付成本与费用构成'],
    report: ['经营洞察', '分析合同、回款、已录成本与项目质量'], users: ['用户与权限', '管理系统账号、角色与启用状态'],
    project_detail: ['项目工作区', '围绕风险、交付、评估与结算推进单个项目'],
  };

  function quickCreateDemand() {
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

  function notificationTarget(value, eventId) {
    const exact = (object, keys) => object && typeof object === 'object' && !Array.isArray(object) && Object.keys(object).length === keys.length && keys.every(key => Object.prototype.hasOwnProperty.call(object, key));
    const id = value => typeof value === 'string' && /^[1-9]\d{0,15}$/.test(value) && BigInt(value) <= 9007199254740991n;
    const key = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(value);
    if (!exact(value, ['moduleId', 'params']) || !['M02', 'M03'].includes(value.moduleId)) throw new Error('通知事项暂时无法打开，请刷新后核对。');
    const p = value.params, approval = value.moduleId === 'M03';
    if (!exact(p, approval ? ['recordId', 'eventId', 'view', 'taskId'] : ['recordId', 'eventId', 'view']) || !id(p.recordId) || !key(p.eventId) || p.eventId !== eventId || !(approval ? ['task', 'detail'].includes(p.view) && key(p.taskId) && id(p.taskId) : p.view === 'detail')) throw new Error('通知事项暂时无法打开，请刷新后核对。');
    return value;
  }

  async function showMyNotifications() {
    if (!state.user) return;
    const identity = state.user, epoch = routeEpoch, page = state.page, projectId = state.projectId;
    let ended = false, ticket = 0, actionTicket = 0, mode = 'inbox', offset = 0, rows = [], listController = null, actionController = null;
    const controller = new AbortController();
    const mask = openModal('我的通知', '<p class="modal-intro">仅显示当前账号可查看的通知。打开事项不会自动标为已读；标为已读不代表已经办理。</p><div id="notifications-channels" class="modal-intro" role="status">正在核对通知渠道…</div><div class="toolbar"><button type="button" class="btn gray" data-notifications-inbox>本人通知</button><button type="button" class="btn gray" data-notifications-reload>刷新</button><button type="button" class="btn gray" data-notifications-audit>通知核查</button></div><p id="notifications-notice" class="modal-intro" role="status"></p><div id="notifications-count" class="modal-intro"></div><div id="notifications-list"></div><div id="notifications-pagination" class="toolbar"></div><div id="notifications-detail"></div>', { noFoot: true, wide: true, onClose: () => dispose() });
    if (!mask) return;
    const node = name => $('#notifications-' + name, mask);
    const current = () => !ended && state.user === identity && routeEpoch === epoch && state.page === page && state.projectId === projectId && $('#modal-mask') === mask;
    function dispose() { if (ended) return; ended = true; controller.abort(); listController?.abort(); actionController?.abort(); ticket++; actionTicket++; rows = []; }
    function checkCurrent() {
      if (current()) return;
      if ($('#modal-mask') === mask) closeModal(true); else dispose();
      throw new DOMException('页面已关闭', 'AbortError');
    }
    addRouteCleanup(() => { if ($('#modal-mask') === mask) closeModal(true); else dispose(); }, epoch);
    const call = async (path, options = {}) => {
      try { const value = await api(path, { ...options, quiet: true, signal: options.signal || controller.signal }); checkCurrent(); return value; }
      catch (error) { checkCurrent(); throw error; }
    };
    const validNotice = item => item && typeof item.id === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(item.id) && typeof item.eventId === 'string' && typeof item.title === 'string' && typeof item.body === 'string' && typeof item.createdAt === 'string' && (item.readAt === null || typeof item.readAt === 'string') && typeof item.actionable === 'boolean';
    function clearResults() { rows = []; node('list').innerHTML = ''; node('pagination').innerHTML = ''; node('detail').innerHTML = ''; node('count').textContent = '通知数量待核实'; }
    function failure(error, area = 'notice') {
      if (!current() || error?.name === 'AbortError') return;
      const status = error.status || error.code;
      if (status === 403 || status === 404) {
        clearResults(); node(area).textContent = status === 403 ? '当前账号没有此项查看权限。' : '该通知或事项已失效，或当前无权查看。请刷新本人通知。';
      } else node(area).textContent = '通知服务暂不可用，请稍后重试。';
    }
    function startAction() { actionController?.abort(); actionController = new AbortController(); const value = ++actionTicket; return { signal: actionController.signal, current: () => current() && value === actionTicket && !actionController.signal.aborted }; }
    async function act(item, operation) {
      if (!current() || !rows.some(row => row.id === item.id)) return;
      const scope = startAction(); node('notice').textContent = operation === 'read' ? '正在更新阅读状态…' : '正在核对通知事项…'; node('detail').innerHTML = '';
      if (operation === 'open') {
        await showDemandWorkflow(null, '', { notification: { id: item.id, eventId: item.eventId }, signal: scope.signal, current: scope.current, onUnavailable: error => { if (scope.current()) failure(error); } });
        return;
      }
      try {
        if (operation === 'read') {
          const result = await call('/notifications/' + encodeURIComponent(item.id) + '/read', { body: {}, signal: scope.signal });
          if (!scope.current()) return;
          if (result.id !== item.id || typeof result.readAt !== 'string' || !result.readAt) throw new Error('阅读状态响应不完整');
          // The receipt carries no business status. Re-read the page/count without inventing actionable.
          const loading = load(offset), readTicket = ticket; await loading;
          if (current() && ticket === readTicket) node('notice').textContent = '已标为已读，业务办理状态未改变。';
        } else {
          const result = await call('/notifications/' + encodeURIComponent(item.id), { signal: scope.signal });
          if (!scope.current()) return;
          const notice = result.notification;
          if (!validNotice(notice) || notice.id !== item.id || notice.eventId !== item.eventId) throw new Error('通知响应不完整');
          node('detail').innerHTML = `<section class="workspace-panel"><h3>${esc(notice.title)}</h3><p>${esc(notice.body)}</p><p class="modal-intro">${esc(workflowLocalTime(notice.createdAt))} · ${notice.readAt ? '已读' : '未读'}</p></section>`;
          node('notice').textContent = '';
        }
      } catch (error) { if (scope.current()) failure(error); }
    }
    async function load(nextOffset = 0, nextMode = mode) {
      if (!current()) return;
      mode = nextMode; offset = nextOffset; const requestedMode = mode, requestedOffset = offset, myTicket = ++ticket;
      listController?.abort(); listController = new AbortController(); const request = listController;
      actionController?.abort(); actionTicket++; clearResults(); node('notice').textContent = mode === 'audit' ? '正在核对通知记录；只有获授权的机构范围可查看。' : '正在读取本人通知…';
      try {
        const value = await call('/notifications' + (requestedMode === 'audit' ? '/diagnostics' : '') + '?' + new URLSearchParams({ offset: requestedOffset, limit: 20 }), { signal: request.signal });
        if (myTicket !== ticket || request.signal.aborted) return;
        if (!Array.isArray(value.items) || value.items.length > 20 || !Number.isSafeInteger(value.total) || value.total < 0 || value.offset !== requestedOffset || value.limit !== 20) throw new Error('通知分页响应不完整');
        if (requestedMode === 'inbox' && (!value.items.every(validNotice) || !Number.isSafeInteger(value.unreadCount) || value.unreadCount < 0 || value.unreadCount > value.total)) throw new Error('通知响应不完整');
        if (requestedMode === 'audit' && !value.items.every(item => item && typeof item.recordId === 'string' && /^[1-9]\d*$/.test(item.recordId) && item.status === 'BLOCKED' && typeof item.reason === 'string')) throw new Error('核查响应不完整');
        rows = value.items;
        node('count').textContent = requestedMode === 'inbox' ? `共 ${value.total} 条本人通知 · 未读 ${value.unreadCount} 条` : `共 ${value.total} 条待核查记录（只读）`;
        const reasons = { TEAM_RECIPIENTS_UNCONFIGURED: '承接团队收件人尚未配置', RECIPIENT_BINDING_OR_PERMISSION_UNPROVEN: '收件人绑定或权限待核实', HISTORICAL_BINDING_UNPROVEN: '历史收件人关系无法核实', UNSUPPORTED_BUSINESS_MOMENT: '该业务时点尚未配置提醒规则' };
        const actions = requestedMode === 'inbox' ? [
          { l: '打开事项', cls: 'gray', onClick: item => act(item, 'open') },
          { l: '标为已读', cls: 'gray', show: item => item.readAt === null, onClick: item => act(item, 'read') },
          { l: '查看内容', cls: 'gray', onClick: item => act(item, 'detail') },
        ] : [];
        const columns = requestedMode === 'inbox' ? [
          { k: 'createdAt', l: '时间', render: item => esc(workflowLocalTime(item.createdAt)) },
          { k: 'title', l: '事项', render: item => `<div><b>${esc(item.title)}</b><p>${esc(item.body)}</p></div>` },
          { k: 'readAt', l: '阅读状态', render: item => tag(item.readAt ? '已读' : '未读') },
        ] : [
          { k: 'createdAt', l: '时间', render: item => esc(workflowLocalTime(item.createdAt)) },
          { k: 'recordId', l: '需求编号' },
          { k: 'reason', l: '待核查原因', render: item => esc(reasons[item.reason] || '通知记录需要进一步核实') },
        ];
        const tableRows = requestedMode === 'audit' ? rows.map((item, i) => ({ ...item, id: i })) : rows;
        node('list').innerHTML = rows.length ? renderTable(columns, tableRows, actions) : `<p class="modal-intro">${requestedMode === 'inbox' ? '当前没有可查看的本人通知。' : '当前授权范围内没有待核查记录。'}</p>`;
        bindTableActions(node('list'), tableRows, actions);
        node('pagination').innerHTML = `<button type="button" class="btn gray" data-notifications-prev ${requestedOffset === 0 ? 'disabled' : ''}>上一页</button><span>第 ${Math.floor(requestedOffset / 20) + 1} 页</span><button type="button" class="btn gray" data-notifications-next ${requestedOffset + rows.length >= value.total ? 'disabled' : ''}>下一页</button>`;
        $('[data-notifications-prev]', mask).onclick = () => { if (requestedOffset > 0) load(Math.max(0, requestedOffset - 20)); };
        $('[data-notifications-next]', mask).onclick = () => { if (requestedOffset + rows.length < value.total) load(requestedOffset + 20); };
        node('notice').textContent = requestedMode === 'audit' ? '这里只核查通知生成情况，不发送、补发或改变业务状态。' : '';
        refreshIcons(mask);
      } catch (error) { if (myTicket === ticket && !request.signal.aborted) failure(error); }
    }
    $('[data-notifications-inbox]', mask).onclick = () => load(0, 'inbox');
    $('[data-notifications-reload]', mask).onclick = () => load(offset);
    $('[data-notifications-audit]', mask).onclick = () => load(0, 'audit');
    const channels = async () => {
      try {
        const value = await call('/notifications/channels');
        if (!Array.isArray(value.channels)) throw new Error('通知渠道响应不完整');
        const labels = { IN_APP: '站内通知', EMAIL: '邮件', PUBLIC_ACCOUNT: '公众号' }, statuses = { ready: '已接通', adapter_not_connected: '发送尚未接通', unconfigured: '尚未配置' };
        node('channels').textContent = value.channels.filter(item => Object.prototype.hasOwnProperty.call(labels, item.channel)).map(item => `${labels[item.channel]}：${statuses[item.status] || '状态待核实'}`).join('；') || '通知渠道状态待核实';
      } catch (error) { if (current() && error?.name !== 'AbortError') node('channels').textContent = '通知渠道状态暂时无法核实。'; }
    };
    await Promise.all([load(0), channels()]);
  }

  async function openCommandCenter() {
    const body = `<div class="command-palette">
      <label class="command-search"><span class="sr-only">搜索功能或项目</span>${icon('search')}<input id="command-query" placeholder="输入项目、单位或功能名称" autocomplete="off" autofocus role="combobox" aria-autocomplete="list" aria-expanded="true" aria-controls="command-results"><kbd>ESC</kbd></label>
      <div class="command-results" id="command-results" role="listbox" aria-label="可执行命令"></div>
      <div class="command-hint"><span>${icon('corner-down-left')}回车进入</span><span>${icon('arrow-up-down')}上下选择</span></div>
    </div>`;
    const mask = openModal('搜索功能或直接开始', body, { noFoot: true, wide: true, kicker: '快捷工作入口 · ⌘ K' });
    if (!mask) return;
    const input = $('#command-query');
    const resultRoot = $('#command-results');
    let projects = [];
    let visible = [];
    let activeIndex = 0;

    const commandItems = () => {
      const items = [];
      if (canWrite()) items.push({ label: '新建培训需求', meta: '录入客户目标、课时、师资要求与期望日期', keywords: '新建 创建 客户 需求', ico: 'plus', action: '新建', run: quickCreateDemand });
      if (canWrite()) items.push(
        { label: '上传讲师简历', meta: '进入师资资源的简历管理', keywords: '讲师 老师 PDF PPTX 上传 解析', ico: 'file-up', art: 'documents', action: '前往', run: () => { state.teacherTab = 'resumes'; navigateTo('teachers'); } },
        { label: '智能推荐讲师', meta: '识别客户需求并推荐匹配师资', keywords: '老师 推荐 匹配 客户 要求', ico: 'sparkles', art: 'recommend', action: '开始', run: () => { state.teacherTab = 'recommend'; navigateTo('teachers'); } },
      );
      NAV.filter((n) => !n.admin || state.user.role === 'admin').forEach((n) => items.push({
        label: n.l, meta: (PAGE_META[n.k] || [n.l, ''])[1], keywords: `${n.l} ${n.group}`, ico: n.ico, art: n.art, action: '前往', run: () => navigateTo(n.k),
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
      resultRoot.innerHTML = visible.length ? visible.map((item, i) => `<button type="button" id="command-option-${i}" class="command-result ${i === activeIndex ? 'active' : ''}" data-command-index="${i}" role="option" aria-selected="${i === activeIndex}"><span>${item.art ? businessArt(item.art) : icon(item.ico)}</span><span><b>${esc(item.label)}</b><small>${esc(item.meta)}</small></span><em>${esc(item.action)}${icon('arrow-right')}</em></button>`).join('') : `<div class="command-empty">${icon('search-x')}<b>没有匹配结果</b><span>换一个项目名、单位名或功能名称试试</span></div>`;
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
    if ($('#modal-mask')?._beforeClose && closeModal() === false) return;
    disposeLoginExperience();
    if (navViewportCleanup) { navViewportCleanup(); navViewportCleanup = null; }
    if (navKeyHandler) { document.removeEventListener('keydown', navKeyHandler); navKeyHandler = null; }
    if (globalKeyHandler) { document.removeEventListener('keydown', globalKeyHandler); globalKeyHandler = null; }
    const u = state.user;
    if (!u) { renderLogin(); return; }
    lastRenderedHash = location.hash;
    sceneBridge.setMode('shell', { route: state.page }).then(() => sceneBridge.setRoute(state.page));
    const selectedNavigation = state.page === 'project_detail' ? 'projects' : state.page;
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
          <a class="sidebar-materials" href="/materials.html">${businessArt('materials')}<span><b>${canWrite() ? '资料上传与管理' : '培训资料中心'}</b><small>${canWrite() ? '上传、上下架与下载' : '公开学习包下载'}</small></span>${icon('arrow-up-right')}</a>
          ${canWrite() ? `<button type="button" class="sidebar-create" id="sidebar-create">${icon('plus')}<span>新建培训需求</span><kbd>N</kbd></button>` : ''}
          <nav class="nav">
            ${groups.map((g) => `<div class="nav-group"><div class="nav-label">${esc(g.name)}</div>${g.items.map((n) =>
              `<button type="button" class="nav-item ${selectedNavigation === n.k ? 'active' : ''}" data-nav="${n.k}" ${selectedNavigation === n.k ? 'aria-current="page"' : ''}>${businessArt(n.art)}<span>${n.l}</span></button>`).join('')}</div>`).join('')}
          </nav>
          <a class="sidebar-updates" href="/updates.html">${icon('history')}<span>更新记录</span>${icon('arrow-up-right')}</a>
        </aside>
        <button type="button" class="sidebar-scrim" id="sidebar-scrim" aria-label="关闭导航" aria-hidden="true" tabindex="-1"></button>
        <div class="main">
          <span class="v7-glow" aria-hidden="true"></span>
          <div class="topbar">
            <div class="topbar-start"><button type="button" class="icon-btn menu-btn" id="menu-btn" aria-label="打开导航" aria-controls="primary-sidebar" aria-expanded="false">${icon('menu')}</button><div class="topbar-title"><div><div class="page-title" id="page-title"></div><div class="page-subtitle" id="page-subtitle"></div></div></div></div>
            <div class="topbar-actions">
              <div class="topbar-environment" data-environment><div class="environment-time"><time data-local-clock aria-label="设备本地时间">—</time><span data-local-date></span></div><div class="environment-weather" data-local-weather aria-label="所在城市天气">正在获取天气…</div></div>
              <button type="button" class="top-command" id="global-command" aria-label="搜索功能或项目" title="搜索功能或项目（⌘ K / Ctrl K）">${icon('search')}<span>搜索</span></button>
              <details class="user user-menu" id="user-menu">
                <summary aria-label="打开账户菜单" title="${esc(u.name || u.username)} · ${esc(u.roleName)}"><span class="user-avatar" aria-hidden="true">${icon('user-round')}</span><span class="user-name"><b>${esc(u.name || u.username)}</b></span>${icon('chevron-down')}</summary>
                <div class="user-popover">
                  <div class="user-popover-head"><span class="user-avatar large" aria-hidden="true">${icon('user-round')}</span><span><b>${esc(u.name || u.username)}</b><small>${esc(u.username)} · ${esc(u.roleName)}</small></span></div>
                  <button type="button" id="btn-chpwd">${icon('key-round')}<span>修改密码</span>${icon('chevron-right')}</button>
                  <button type="button" id="btn-notifications">${icon('bell')}<span>我的通知</span>${icon('chevron-right')}</button>
                  <button type="button" id="btn-business-identity">${icon('user-check')}<span>我的业务身份</span>${icon('chevron-right')}</button>
                  <button type="button" id="btn-help">${icon('command')}<span>快捷键与帮助</span>${icon('chevron-right')}</button>
                  <button type="button" id="btn-logout" class="danger">${icon('log-out')}<span>退出登录</span></button>
                </div>
              </details>
            </div>
          </div>
          <main class="content" id="content"></main>
        </div>
        <nav class="mobile-dock" aria-label="移动端快捷导航">
          <button type="button" data-mobile-nav="dashboard" class="${state.page === 'dashboard' ? 'active' : ''}" ${state.page === 'dashboard' ? 'aria-current="page"' : ''}>${businessArt('dashboard')}<span>今日</span></button>
          <button type="button" data-mobile-nav="projects" class="${['projects','project_detail'].includes(state.page) ? 'active' : ''}" ${['projects','project_detail'].includes(state.page) ? 'aria-current="page"' : ''}>${businessArt('projects')}<span>项目</span></button>
          ${canWrite() ? `<button type="button" class="mobile-create" id="mobile-create" aria-label="新建培训需求">${icon('plus')}<span>新建</span></button>` : ''}
          <button type="button" data-mobile-nav="dispatches" class="${state.page === 'dispatches' ? 'active' : ''}" ${state.page === 'dispatches' ? 'aria-current="page"' : ''}>${businessArt('calendar')}<span>排期</span></button>
          <button type="button" id="mobile-more" aria-controls="primary-sidebar" aria-expanded="false">${icon('menu')}<span>更多</span></button>
        </nav>
      </div>`;
    $$('.nav-item').forEach((n) => (n.onclick = () => navigateTo(n.dataset.nav)));
    environmentPanel = window.YanxuEnvironment?.mount($('[data-environment]'));
    $('#btn-logout').onclick = () => confirmBox('确定退出研序工作台？当前账号需要重新验证后才能继续访问。', async () => {
      // 只有服务端确认撤销会话后才切回登录页。网络失败时保留当前画面，
      // 避免 HttpOnly Cookie 仍有效却向用户显示“已安全退出”。
      await api('/logout', { body: {} });
      invalidateSession();
    });
    $('#btn-chpwd').onclick = () => { $('#user-menu')?.removeAttribute('open'); showChangePwd(); };
    $('#btn-notifications').onclick = () => { $('#user-menu')?.removeAttribute('open'); showMyNotifications(); };
    $('#btn-business-identity').onclick = () => { $('#user-menu')?.removeAttribute('open'); showOrganizationBinding(); };
    $('#btn-help').onclick = () => { $('#user-menu')?.removeAttribute('open'); openShortcutGuide(); };
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
    const navViewport = window.matchMedia('(max-width: 1024px)');
    const onNavViewportChange = () => setNavOpen(false, navViewport.matches && sidebar.contains(document.activeElement));
    navViewport.addEventListener('change', onNavViewportChange);
    navViewportCleanup = () => navViewport.removeEventListener('change', onNavViewportChange);
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
    if (sidebarCreate) sidebarCreate.onclick = quickCreateDemand;
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
    if ($('#modal-mask')?._beforeClose && closeModal() === false) return;
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
      if (state.user.role === 'viewer') c.insertAdjacentHTML('afterbegin', `<div class="readonly-banner">${icon('eye')}<span><b>只读浏览模式</b> 基础资料以查询为主；审批、需求提交、团队受理及授课核对由已绑定的业务身份和授权确定。</span></div>`);
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

  const REGION_PROVINCES = ['北京', '天津', '河北', '山西', '内蒙古', '辽宁', '吉林', '黑龙江', '上海', '江苏', '浙江', '安徽', '福建', '江西', '山东', '河南', '湖北', '湖南', '广东', '广西', '海南', '重庆', '四川', '贵州', '云南', '西藏', '陕西', '甘肃', '青海', '宁夏', '新疆', '香港', '澳门', '台湾', '境外'];
  const teacherResidenceText = (teacher) => teacher?.base_province && teacher?.base_city ? (teacher.base_province === teacher.base_city ? teacher.base_city : `${teacher.base_province} · ${teacher.base_city}`) : teacher?.base_province ? `${teacher.base_province} · 城市待完善` : '常驻城市待完善';
  const residenceFields = (required = true) => [
    { k: 'base_province', label: '常驻省份 / 地区', type: 'select', options: REGION_PROVINCES, required },
    { k: 'base_city', label: '常驻城市 / 地区', required, regionProvinceKey: 'base_province', placeholder: '选择省份后输入或选择城市', hint: required ? '讲师确认的常驻地，无需家庭地址。城市字典未覆盖的名称可手填，核对前不参与同城优先。' : '填写讲师确认的真实城市；暂不清楚可留空、先上传简历，补齐后再确认入库。请勿填写猜测城市。' },
  ];
  function refreshRegionSuggestions(provinceInput, preserveCity = false) {
    const grid = provinceInput.closest('.form-grid');
    const cityInput = grid?.querySelector(`[data-region-province="${provinceInput.dataset.k}"]`);
    if (!cityInput) return;
    const cities = typeof YX_REGION_CITIES === 'undefined' ? [] : (YX_REGION_CITIES[provinceInput.value] || []);
    const list = document.getElementById(cityInput.getAttribute('list'));
    if (list) list.innerHTML = cities.map((city) => `<option value="${esc(city)}"></option>`).join('');
    if (!preserveCity) cityInput.value = ['北京', '天津', '上海', '重庆', '香港', '澳门'].includes(provinceInput.value) ? provinceInput.value : '';
  }
  document.addEventListener('change', (event) => {
    const provinceInput = event.target;
    if (!['base_province', 'training_province'].includes(provinceInput?.dataset?.k)) return;
    refreshRegionSuggestions(provinceInput);
    const cityInput = provinceInput.closest('.form-grid')?.querySelector(`[data-region-province="${provinceInput.dataset.k}"]`);
    if (!cityInput) return;
    cityInput.dispatchEvent(new Event('input', { bubbles: true }));
  });

  const CRUD = {
    demands: {
      mod: 'demands', title: '培训需求', kw: '搜索：项目/单位/联系人',
      statusOptions: ['待处理', '已投标', '已立项', '进行中', '已完成', '已流标'],
      cols: [
        { k: 'title', l: '培训需求', render: (r) => `<span class="primary-cell"><b>${esc(r.title)}</b><small>#R-${String(r.id).padStart(4, '0')} · ${esc(r.unit || '需求单位待补充')}</small></span>` },
        { k: 'contact', l: '客户联系人', render: (r) => `<span class="secondary-cell"><b>${esc(r.contact || '—')}</b><small>${esc(r.phone || '未填写电话')}</small></span>` },
        { k: 'hours', l: '交付预期', render: (r) => `<span class="secondary-cell"><b>${r.workflow ? esc(workflowDurationText(r.workflow)) : num(r.hours) + ' 课时'}</b><small>${esc(r.expect_date || '时间待定')}</small></span>` },
        { k: 'status', l: '状态', render: (r) => `${tag(r.status)}${r.workflow ? `<br>${tag(workflowLabel(r.workflow))}` : ''}` },
      ],
      fields: [
        { k: 'title', label: '培训项目名称', required: true, span2: true },
        { k: 'unit', label: '需求单位', required: true },
        { k: 'contact', label: '联系人' },
        { k: 'phone', label: '联系电话', type: 'tel' },
        { k: 'hours', label: '预计课时', type: 'number', required: true, min: 0.5, max: 1000, step: 0.5 },
        { k: 'expect_date', label: '期望培训时间', type: 'date' },
        { k: 'training_province', label: '授课省份 / 地区', type: 'select', options: [{ v: '', l: '待确定' }, ...REGION_PROVINCES] },
        { k: 'training_city', label: '授课城市 / 地区', regionProvinceKey: 'training_province', placeholder: '选择省份后输入或选择城市' },
        { k: 'training_mode', label: '授课方式', type: 'select', options: ['待定', '线下', '线上'], value: '待定' },
        { k: 'training_period', label: '授课时段', type: 'select', options: ['待定', '上午', '下午', '全天'], value: '待定' },
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
        { k: 'title', l: '培训项目', render: (r) => `<span class="primary-cell"><b>${esc(r.title)}</b><small>#P-${String(r.id).padStart(4, '0')} · ${esc(r.unit || '委托单位待补充')} · ${esc(r.owner || '负责人待补充')}${r.workflow_source ? ' · ' + esc(workflowProjectSource(r)) : ''}</small></span>` },
        { k: 'start_date', l: '交付计划', render: (r) => `<span class="secondary-cell"><b>${esc(r.start_date || '待定')} — ${esc(r.end_date || '待定')}</b><small>${num(r.hours)} 课时 · ${Number(r.participant_count || 0) ? num(r.participant_count) + ' 人' : '人数待定'}${r.delivery_controlled === true ? `<br>已核对实际 ${esc(deliveryHoursText(r.delivery_hours?.actual))} 课时` : ''}</small></span>` },
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
        { k: 'hours', label: '计划课时', type: 'number', min: 0, max: 1000, step: 0.01, hint: '45分钟为1课时，可保留两位小数。' },
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
        f.options = ds.filter((d) => !d.workflow).map((d) => ({ v: d.id, l: `#${d.id} ${d.title}（${d.unit}）` }));
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
    if (mod === 'projects' && rows.some(hasLimitedProjectAccess)) {
      const active = rows.filter(row => row.status === '进行中').length;
      const limited = rows.filter(hasLimitedProjectAccess).length;
      return `<div class="module-summary three"><div><span>${icon('folder-kanban')}</span><small>可查看项目</small><b>${countTag(rows.length)}<em>个</em></b></div><div><span>${icon('calendar-check')}</span><small>进行中项目</small><b>${countTag(active)}<em>个</em></b></div><div><span>${icon('layers-3')}</span><small>按业务模块查看</small><b>${countTag(limited)}<em>个</em></b></div></div>`;
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
    const epoch = routeEpoch, identity = state.user;
    const current = () => state.user === identity && isRouteCurrent(epoch, c, cfg.mod);
    const contextProjectId = ['charges', 'costs'].includes(cfg.mod) ? state.contextProjectId : null;
    const savedFilter = { ...(state.filters[cfg.mod] || {}) };
    if (state.initialFilter && Object.prototype.hasOwnProperty.call(state.initialFilter, 'status')) savedFilter.status = String(state.initialFilter.status || '');
    state.filters[cfg.mod] = savedFilter;
    const initialStatus = String(savedFilter.status || '');
    const initialKeyword = String(savedFilter.kw || '');
    state.initialFilter = null;
    const [rows, contextProjects, workflowContext] = await Promise.all([
      api('/' + cfg.mod + (contextProjectId ? `?project_id=${encodeURIComponent(contextProjectId)}` : '')),
      contextProjectId ? api('/projects') : Promise.resolve([]),
      cfg.mod === 'demands' ? api('/workflow/context', { quiet: true }).catch((error) => ({ _error: error.message, can_create: false })) : Promise.resolve(null),
    ]);
    if (!current()) return;
    const contextProject = contextProjects.find((p) => String(p.id) === String(contextProjectId));
    const statusState = cfg.statusOptions ? `<input type="hidden" id="flt-status" value="${esc(initialStatus)}">` : '';
    const statusTabs = cfg.statusOptions ? `<div class="filter-tabs" aria-label="按状态筛选">${statusState}<button type="button" class="${initialStatus ? '' : 'active'}" data-status="" aria-pressed="${!initialStatus}">全部 <span>${rows.length}</span></button>${cfg.statusOptions.map((s) => `<button type="button" class="${initialStatus === s ? 'active' : ''}" data-status="${esc(s)}" aria-pressed="${initialStatus === s}">${esc(s)} <span>${rows.filter((r) => r.status === s).length}</span></button>`).join('')}</div>` : '';
    const toolbar = cfg.kw ? `<div class="toolbar"><label class="search-box"><span class="sr-only">搜索${esc(cfg.title)}列表</span>${icon('search')}<input id="flt-kw" value="${esc(initialKeyword)}" placeholder="${cfg.kw}" autocomplete="off"></label><button type="button" class="btn gray" id="flt-btn">${icon('search')}搜索</button><button type="button" class="btn ghost" id="flt-clear">清除</button></div>` : '';
    const canAdd = cfg.mod === 'demands' ? workflowContext?.can_create === true : canWrite() && contextProject?.status !== '已归档';
    const addLabel = cfg.mod === 'projects' ? '新建培训需求' : '新增' + cfg.title;
    const workflowNotice = cfg.mod === 'demands' ? workflowContextNotice(workflowContext) : '';
    const contextName = contextProject?.title || rows[0]?.project_title || `项目 #${contextProjectId || ''}`;
    c.innerHTML = `${contextProjectId ? `<div class="context-filter">${icon('link-2')}<span>项目上下文：<b>${esc(contextName)}</b></span><button type="button" id="context-project-home">返回项目工作区</button><button type="button" id="clear-context">查看全部</button></div>` : ''}<div id="module-summary">${renderModuleSummary(cfg.mod, rows)}</div><div class="card data-card">
      <div class="card-heading"><div><h2>${esc(cfg.title)}列表</h2><p>共 <span id="result-count">${rows.length}</span> 条记录</p></div>${canAdd ? `<button type="button" class="btn" id="add-btn">${icon('plus')}${esc(addLabel)}</button>` : ''}</div>
      ${workflowNotice ? `<p class="modal-intro" role="status">${esc(workflowNotice)}</p>` : ''}
      ${statusTabs}
      ${toolbar}
      <div id="tbl"></div>
    </div>`;

    const actions = buildActions(cfg, { epoch, content: c });
    const draw = (list) => {
      if (!current()) return;
      const table = $('#tbl', c);
      const resultCount = $('#result-count', c);
      if (!table || !resultCount) return;
      table.innerHTML = cfg.mod === 'projects' ? renderProjectListTable(list, actions) : renderTable(cfg.cols, list, actions, cfg.mod);
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
      if (!current()) return;
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
    if (addBtn) addBtn.onclick = () => cfg.mod === 'projects' ? quickCreateDemand() : editForm(cfg, null, () => renderPage());
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
    const canEditRow = (r) => !hasLimitedProjectAccess(r) && !archived(r) && !r.workflow;
    const canDeleteRow = (r) => {
      if (hasLimitedProjectAccess(r) || archived(r) || r.workflow || r.workflow_source?.can_delete === false) return false;
      if (cfg.mod === 'projects') return !['已完成', '已归档'].includes(r.status);
      if (cfg.mod === 'charges') return Number(r.received || 0) <= 0;
      return true;
    };
    if (cfg.mod === 'projects') acts.push({ l: '打开项目', cls: '', onClick: showProjectOverview });
    if (cfg.mod === 'demands' && canWrite()) acts.push({ l: '推荐师资', cls: '', icon: 'users-round', show: (row) => ['待处理', '已流标'].includes(row.status), onClick: (demand) => {
      invalidateTeacherRecommendations();
      state.teacherTab = 'recommend';
      state.teacherRequirementDraft = guidedRequirementText(demandGuidedFields(demand));
      state.teacherRecommendationForm = { demandId: String(demand.id), maxResults: '3', maxFeeRate: '', hardBudget: false, inputMode: 'guided', guided: demandGuidedFields(demand), rawDraft: demandRequirementText(demand), rawInitialized: true,
        logistics: { training_province: demand.training_province || '', training_city: demand.training_city || '', training_mode: demand.training_mode || '待定', training_period: demand.training_period || '待定', prefer_local: true } };
      navigateTo('teachers');
    } });
    if (cfg.mod === 'projects' && canWrite()) {
      acts.push({ l: '启动项目', cls: 'green', show: (r) => !hasLimitedProjectAccess(r) && r.status === '待启动', onClick: showProjectStart });
      acts.push({ l: '完成交付', cls: 'green', show: (r) => !hasLimitedProjectAccess(r) && r.status === '进行中', onClick: (r) => showProjectTransition(r, 'complete') });
      acts.push({ l: '归档', cls: 'gray', show: (r) => !hasLimitedProjectAccess(r) && r.status === '已完成', onClick: (r) => showProjectTransition(r, 'archive') });
    }
    if (cfg.mod === 'demands') {
      acts.push({ l: '编辑需求', cls: 'gray', show: (r) => workflowActionEnabled(r.workflow, 'edit'), onClick: (r) => editWorkflowDemand(r, () => renderPage()) });
      acts.push({ l: '提交需求', cls: '', show: (r) => workflowActionEnabled(r.workflow, 'submit'), onClick: (r) => showDemandWorkflow(r, 'SUBMIT') });
      acts.push({ l: WORKFLOW_ACTION.APPROVE_COMBINED, cls: '', show: (r) => approvalCan(r.workflow?.approval, 'APPROVE_COMBINED'), onClick: (r) => showDemandWorkflow(r, 'APPROVE_COMBINED') });
      acts.push({ l: '办理审批', cls: '', show: (r) => !approvalCan(r.workflow?.approval, 'APPROVE_COMBINED') && approvalCan(r.workflow?.approval, 'APPROVE'), onClick: (r) => showDemandWorkflow(r) });
      acts.push({ l: '团队受理', cls: 'green', show: (r) => workflowActionEnabled(r.workflow, 'accept'), onClick: (r) => showDemandWorkflow(r, 'ACCEPT') });
      acts.push({ l: '登记结果', cls: '', show: (r) => workflowActionEnabled(r.workflow, 'bid_result'), onClick: (r) => showDemandWorkflow(r, 'BID_RESULT') });
    }
    if (cfg.detail) acts.push({ l: '详情', cls: 'gray', onClick: (r) => cfg.mod === 'demands' && r.workflow ? showDemandWorkflow(r) : openModal('业务详情', `<div class="detail-text">${esc(cfg.detail(r))}</div>`, { noFoot: true, kicker: '记录信息' }) });
    if (cfg.mod === 'bids') acts.push({
      l: '中标', cls: 'green', show: (r) => canWrite() && !r.workflow && r.status === '待评审',
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

  function deliveryHoursValue(value) {
    return typeof value === 'string' && /^[0-9]+(?:\.[0-9]+)?$/.test(value) && Number.isFinite(Number(value)) ? value : null;
  }

  function deliveryHoursText(value) {
    return deliveryHoursValue(value) ?? '待核对';
  }

  function projectCompletedHours(project, dispatches) {
    if (project.delivery_controlled === true) return deliveryHoursValue(project.delivery_hours?.actual);
    return dispatches.filter((row) => row.status === '已完成').reduce((sum, row) => sum + Number(row.hours || 0), 0);
  }

  function deliverySummaryText(project) {
    if (!project.delivery_hours) return project.delivery_hours_reason || '授课汇总尚未取得，请核对当前查看权限。';
    const hours = project.delivery_hours;
    return `授课记录汇总：预计 ${deliveryHoursText(hours.estimated)} / 计划 ${deliveryHoursText(hours.planned)} / 实际 ${deliveryHoursText(hours.actual)} / 计酬 ${deliveryHoursText(hours.payable)}（课时）；待完成 ${hours.pending_count ?? '待核实'} 场，已完成未核对 ${hours.unverified_completed_count ?? '待核实'} 场。预计与计划计入全部有效排课；实际与计酬仅计已核对完成记录，四类课时分别统计。`;
  }

  // A module VIEW grants a project entry, never the legacy project editor or financial tables.
  function hasLimitedProjectAccess(row) {
    return row?.read_access?.limited === true;
  }
  function limitedProjectModules(project) {
    const access = project.read_access || {};
    return [access.delivery === true ? '排课与课酬' : '', access.feedback === true ? '正式问卷汇总' : '', access.summary === true ? '培训总结' : ''].filter(Boolean);
  }
  function renderProjectListTable(rows, actions) {
    const complete = rows.filter(row => !hasLimitedProjectAccess(row));
    const limited = rows.filter(hasLimitedProjectAccess);
    const fullTable = complete.length || !limited.length ? renderTable(CRUD.projects.cols, complete, actions, 'projects') : '';
    if (!limited.length) return fullTable;
    const cols = [
      { k: 'title', l: '培训项目', render: row => `<span class="primary-cell"><b>${esc(row.title)}</b><small>项目 #P-${esc(String(row.id).padStart(4, '0'))}</small></span>` },
      { k: 'status', l: '状态', render: row => tag(row.status) },
      { k: 'read_access', l: '可查看模块', render: row => esc(limitedProjectModules(row).join(' · ') || '当前暂无可查看模块') },
    ];
    // Keep original action indices for bindTableActions, but no legacy mutation can apply to these rows.
    const limitedActions = actions.map(action => action.l === '打开项目' ? action : { ...action, show: () => false });
    return fullTable + '<p class="modal-intro">以下项目仅显示当前可查看的业务模块。</p>' + renderTable(cols, limited, limitedActions, 'projects');
  }
  function renderLimitedProjectWorkspace(c, project, epoch) {
    const identity = state.user, controller = new AbortController();
    const current = () => !controller.signal.aborted && state.user === identity && String(state.projectId) === String(project.id) && isRouteCurrent(epoch, c, 'project_detail');
    const access = project.read_access || {};
    let cases = null, opening = false;
    addRouteCleanup(() => { controller.abort(); cases?.cleanup(); }, epoch);
    if (!current()) return;
    c.innerHTML = `<div class="workspace-back"><button type="button" id="project-back">${icon('chevron-left')}所有项目</button><span>项目 #P-${esc(String(project.id).padStart(4, '0'))}</span></div>
      <header class="project-workspace-head"><div><h1>${esc(project.title)}</h1><p>${tag(project.status)}</p></div></header>
      <p class="modal-intro">当前可查看以下业务模块；办理操作仍按各模块的权限与流程条件核对。</p>
      ${access.delivery === true ? `<section class="workspace-panel delivery-panel"><div class="workspace-panel-head"><div><h2>排课与课酬</h2><small>授课记录、正式应付与实际收付</small></div></div><div class="toolbar"><button type="button" class="btn gray" data-limited-dispatches>课程与排期</button><button type="button" class="btn gray" data-limited-cases>项目课酬事项</button></div><p class="modal-intro" data-limited-cases-notice role="status"></p><div data-limited-cases-content></div></section>` : ''}
      ${access.feedback === true ? '<section class="workspace-panel evaluation-panel"><div class="workspace-panel-head"><div><h2>正式问卷汇总</h2></div></div><div id="project-survey-formal"></div></section>' : ''}
      ${access.summary === true ? '<section class="workspace-panel summary-panel"><div class="workspace-panel-head"><div><h2>培训总结</h2></div></div><div id="project-summary-content"></div></section>' : ''}
      ${limitedProjectModules(project).length ? '' : '<p class="inline-note">当前暂无可查看模块，请重新打开项目核对权限。</p>'}`;
    $('#project-back', c).onclick = () => { if (current()) navigateTo('projects'); };
    if (access.delivery === true) {
      $('[data-limited-dispatches]', c).onclick = () => { if (current()) navigateTo('dispatches', { projectId: project.id }); };
      const button = $('[data-limited-cases]', c), notice = $('[data-limited-cases-notice]', c), root = $('[data-limited-cases-content]', c);
      button.onclick = async () => {
        if (!current() || opening || (cases && cases.beforeClose() === false)) return;
        opening = true; button.disabled = true; notice.textContent = '正在读取项目课酬事项…';
        try {
          const module = await import('/modules/delivery-settlement/cases-workspace.js?v=20260924coding1');
          if (!current()) return;
          if (!cases) cases = module.mountCasesWorkspace(root, { api, getUser: () => state.user, getModal: () => $('#modal-mask'), isCurrent: current, openModal, closeModal, signal: controller.signal });
          await cases.refresh(Number(project.id), project.title);
          if (current()) notice.textContent = '';
        } catch (error) {
          if (current() && error?.name !== 'AbortError') { cases?.cleanup(); cases = null; root.innerHTML = ''; notice.textContent = '项目课酬事项暂时无法读取，请重试。'; }
        } finally { opening = false; if (current()) button.disabled = false; }
      };
    }
    if (access.feedback === true) mountSurveyFormalEntry(c, $('#project-survey-formal', c), { epoch, page: 'project_detail', projectId: project.id, projects: [project] });
    if (access.summary === true) mountProjectSummary(c, project, epoch);
    refreshIcons(c);
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
      if (!mask) return;
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
    const summary = `<div class="transition-summary"><div><small>计划课时</small><b>${num(m.plan_hours)}</b></div><div><small>${check.delivery_controlled === true ? '已核对实际课时' : '已完成课时'}</small><b>${check.delivery_controlled === true ? esc(deliveryHoursText(check.delivery_hours?.actual)) : num(m.completed_hours)}</b></div><div><small>待回款</small><b>¥ ${money(m.outstanding)}</b></div><div><small>待发课酬</small><b>${check.delivery_controlled === true ? '支付待接入' : `¥ ${money(m.pending_fee_amount)}`}</b></div></div>${check.delivery_controlled === true ? `<p class="inline-note">${esc(deliverySummaryText(check))}</p>` : ''}`;

    if (blockers.length) {
      const mask = openModal(isArchive ? '项目暂时不能归档' : '项目暂时不能完成交付', `${summary}<div class="transition-intro blocked">${icon('shield-alert')}<span><b>还有 ${blockers.length} 项硬性条件未满足</b><small>系统不会让状态先走、业务后补。点击具体事项即可前往处理。</small></span></div><div class="transition-list">${issueRows(blockers, 'blocker')}</div>${warnings.length ? `<div class="transition-subhead">同时需要留意</div><div class="transition-list">${issueRows(warnings, 'warning')}</div>` : ''}`, { noFoot: true, kicker: '业务闭环校验' });
      if (!mask) return;
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
    if (!mask) return;
    $$('[data-transition-module]', mask).forEach((btn) => { btn.onclick = () => goToProjectIssue(project, btn.dataset.transitionModule); });
    refreshIcons(mask);
  }

  // ============ 原需求页面：提交、审批与团队受理 ============
  const WORKFLOW_STATUS = { LEADER_PENDING: '待负责人审批', BP_PENDING: '待BP审批', RETURNED: '已退回', WITHDRAWN: '已撤回', READY_FOR_TEAM: '待团队受理' };
  const WORKFLOW_ACTION = { SUBMIT: '提交需求', APPROVE: '通过', APPROVE_COMBINED: '同时完成负责人和BP审批', RETURN: '退回', RESUBMIT: '重新提交', WITHDRAW: '撤回', REVISE: '修改后重审', ACCEPT: '团队受理', BID_RESULT: '登记投标结果', REMIND: '催办' };
  const workflowActionEnabled = (workflow, action) => workflow?.actions?.[action] === true;
  const approvalActions = (approval) => Array.isArray(approval?.actions) ? approval.actions : [];
  const approvalCan = (approval, action) => approvalActions(approval).some((item) => item.action === action && item.enabled === true);
  const approvalStatusLabel = (approval) => approval?.reviewMode === 'SINGLE_EXPLICIT' && approval.status === 'LEADER_PENDING' ? '待负责人及BP审批（兼任）' : WORKFLOW_STATUS[approval?.status];
  const workflowRequestId = () => crypto.randomUUID();
  const workflowContactEligible = (person, organizationCode) => Boolean(organizationCode) && (person.organization_code === organizationCode || (person.responsible_organization_codes || []).includes(organizationCode));
  function workflowProjectSource(project, demand) {
    const path = project.workflow_source?.business_path || demand?.workflow?.business_path;
    if (path === 'direct') return '直接承接';
    if (path === 'bid') return '投标中标后承接';
    return project.bid_id ? `中标记录 #${project.bid_id}` : '独立立项';
  }
  const workflowName = (items, code) => (items || []).find((item) => String(item.code) === String(code))?.label || code || '待配置';
  const workflowLabel = (workflow) => (workflow?.approval?.reviewMode === 'SINGLE_EXPLICIT' && workflow.approval.status === 'LEADER_PENDING' ? approvalStatusLabel(workflow.approval) : workflow?.status_label) || approvalStatusLabel(workflow?.approval) || (workflow?.draft ? '草稿' : '状态待核对');

  // New duration input stays decimal text throughout; existing numeric forms are unchanged.
  function workflowDecimal(value, multiplier = 1n) {
    const raw = String(value ?? '').trim();
    if (!raw) return '';
    if (raw.length > 120 || !/^\d+(?:\.\d+)?$/.test(raw)) throw new Error('时长请填写普通非负小数，不使用科学计数法');
    const [whole, fraction = ''] = raw.split('.');
    const digits = (BigInt(whole + fraction) * multiplier).toString().padStart(fraction.length + 1, '0');
    return fraction ? digits.slice(0, -fraction.length) + '.' + digits.slice(-fraction.length) : digits;
  }
  function workflowBasicHours(minutes) {
    const raw = workflowDecimal(minutes);
    if (!raw) return '—';
    const [whole, fraction = ''] = raw.split('.');
    const numerator = BigInt(whole + fraction) * 100n;
    const denominator = 45n * (10n ** BigInt(fraction.length));
    const rounded = numerator / denominator + (numerator % denominator * 2n >= denominator ? 1n : 0n);
    const digits = rounded.toString().padStart(3, '0');
    return digits.slice(0, -2) + '.' + digits.slice(-2);
  }
  function workflowDurationText(workflow) {
    const minutes = workflow?.form?.duration_minutes;
    if (!minutes) return '时长待补充';
    try { return `${minutes} 分钟 · 基本课时 ${workflowBasicHours(minutes)}`; }
    catch (_) { return '历史时长待核对'; }
  }
  let workflowDialogSequence = 0;
  function workflowRouteScope() {
    const epoch = routeEpoch, identity = state.user, page = state.page, projectId = state.projectId;
    const dialogSequence = ++workflowDialogSequence;
    const originalModal = $('#modal-mask');
    const controller = new AbortController();
    addRouteCleanup(() => controller.abort(), epoch);
    const identityCurrent = () => isRouteCurrent(epoch) && state.user === identity && state.page === page && state.projectId === projectId;
    const current = () => !controller.signal.aborted && identityCurrent();
    return { epoch, controller, originalModal, identityCurrent, current, canOpen: () => current() && dialogSequence === workflowDialogSequence && $('#modal-mask') === originalModal };
  }
  function workflowContextNotice(context) {
    return context?._error || (!context?.person?.person_code ? '业务身份尚未绑定，请联系管理员配置所属机构和审批人员。' : !context.can_create ? '当前账号尚无新建需求权限，可继续查看已授权的记录。' : '');
  }
  function workflowFields(context, workflow) {
    const form = workflow?.form || {};
    const categories = ['亲子财商', '养老丰润', '服务资格认证', '其他类培训'];
    const categoryOptions = [{ v: '', l: '请选择培训分类' }, ...categories.map((value) => ({ v: value, l: value }))];
    const historicalCategory = String(form.category_text || '').trim();
    if (historicalCategory && !categories.includes(historicalCategory)) categoryOptions.push({ v: historicalCategory, l: `${historicalCategory}（历史分类）` });
    const organizations = (context.organizations || []).filter((item) => item.can_write || item.code === form.organization_code || item.code === workflow?.organization_code);
    const selectedOrganization = form.organization_code || workflow?.organization_code || context.person?.organization_code || '';
    const people = (context.people || []).filter((item) => workflowContactEligible(item, selectedOrganization));
    const fields = CRUD.demands.fields.filter((field) => !['status', 'hours'].includes(field.k)).map((field) => ({ ...field, required: false }));
    fields.splice(2, 0,
      { k: 'business_path', label: '承接路径', type: 'select', disabled: Boolean(workflow && !workflow.draft), options: [{ v: 'direct', l: '直接承接' }, { v: 'bid', l: '投标' }], value: 'direct' },
      { k: 'organization_code', label: '所属分公司 / 机构', type: 'select', disabled: Boolean(workflow), options: [{ v: '', l: '请选择机构' }, ...organizations.map((item) => ({ v: item.code, l: item.label }))], value: selectedOrganization },
      { k: 'internal_contact_code', label: '内部对接人', type: 'select', options: [{ v: '', l: '请选择内部对接人' }, ...people.map((item) => ({ v: item.code, l: item.label }))] },
      { k: 'filler_display', label: '实际填报人', type: 'readonly', value: workflowName(context.people, workflow?.filler_code || context.person?.person_code) },
      { k: 'category_text', label: '培训分类', type: 'select', options: categoryOptions },
      { k: 'participant_count', label: '预计参训人数', placeholder: '填写整数' },
      { k: 'duration_value', label: '预计课时 / 时长', hint: '1课时=45分钟。保留原始时长，基本课时按分钟÷45保留两位小数。' },
      { k: 'duration_unit', label: '时长单位', type: 'select', options: [{ v: 'hours', l: '课时' }, { v: 'minutes', l: '分钟' }], value: 'hours' },
      { k: 'budget_amount', label: '预算金额（元，选填）' },
      { k: 'objectives', label: '培训目标', type: 'textarea' },
      { k: 'external_approval_ref', label: '原办公签报引用', hint: '投标提交时填写已核实的原签报编号或引用。' }
    );
    if (['LEADER', 'BP'].includes(workflow?.approval?.stage)) fields.push({ k: 'change_comment', label: '本次修改原因', type: 'textarea', required: true, hint: '审批中修改资料后，将从分公司负责人重新审批。' });
    return fields.map((field) => {
      if (field.k === 'training_mode') return { ...field, options: [...new Set(['待定', '线下', '线上', '混合', form.delivery_mode_text].filter(Boolean))] };
      if (field.k === 'training_period') return { ...field, options: [...new Set(['待定', '上午', '下午', '晚间', '全天', form.period_text].filter(Boolean))] };
      return field;
    });
  }
  function workflowDraftPayload(data, workflow, requestId) {
    const legacyKeys = ['title', 'unit', 'contact', 'phone', 'content', 'teacher_req', 'remark', 'training_province', 'training_city', 'training_mode', 'training_period', 'expect_date'];
    const formKeys = ['business_path', 'organization_code', 'internal_contact_code', 'category_text', 'participant_count', 'budget_amount', 'objectives'];
    const result = {};
    [...legacyKeys, ...formKeys].forEach((key) => { result[key] = String(data[key] ?? '').trim(); });
    workflowDecimal(data.duration_value); // Validate without normalizing away the original decimal text.
    result[data.duration_value && data.duration_unit === 'hours' ? 'hours' : 'duration_minutes'] = String(data.duration_value || '').trim();
    result.delivery_mode_text = result.training_mode;
    result.period_text = result.training_period;
    result.expected_start_date = result.expect_date;
    result.external_approval_ref = result.business_path === 'bid' ? String(data.external_approval_ref || '').trim() : '';
    // Preserve optional stored fields which this compact form does not expose.
    ['customer_contact_code', 'expected_end_date', 'demand_code'].forEach((key) => {
      if (workflow?.form?.[key]) result[key] = workflow.form[key];
    });
    if (['LEADER', 'BP'].includes(workflow?.approval?.stage)) result.change_comment = String(data.change_comment || '').trim();
    if (workflow?.id) { result.id = workflow.id; result.expected_version = workflow.version; }
    result.request_id = requestId;
    return result;
  }
  function workflowFieldErrors(mask, error) {
    const errors = error.data?.errors;
    if (!errors || typeof errors !== 'object') return;
    const aliases = { hours: 'duration_value', duration_minutes: 'duration_value', delivery_mode_text: 'training_mode', period_text: 'training_period', expected_start_date: 'expect_date' };
    Object.entries(errors).forEach(([key, message]) => {
      const input = $(`[data-k="${aliases[key] || key}"]`, mask);
      if (!input) return;
      input.setAttribute('aria-invalid', 'true');
      const text = $('.field-error', input.closest('.form-item'));
      if (text) text.textContent = String(message);
    });
  }
  async function editWorkflowDemand(row, done) {
    const scope = workflowRouteScope();
    const [context, detail] = await Promise.all([
      api('/workflow/context', { signal: scope.controller.signal }),
      row?.id ? api('/demands/workflow?id=' + encodeURIComponent(row.id), { signal: scope.controller.signal }) : Promise.resolve(null),
    ]).catch(() => [null, null]);
    if (!context || !scope.canOpen()) return;
    let workflow = detail?.workflow || null;
    if ((!workflow && !context.can_create) || (workflow && !workflowActionEnabled(workflow, 'edit'))) {
      toast(workflow ? '该需求当前不能编辑，请刷新查看流程状态' : workflowContextNotice(context) || '当前账号不能新建需求', true); return;
    }
    const fields = workflowFields(context, workflow);
    const form = workflow?.form || {};
    const initial = { ...(workflow?.legacy || {}), ...form, business_path: workflow?.business_path || form.business_path || 'direct',
      organization_code: form.organization_code || workflow?.organization_code || context.person?.organization_code || '',
      training_mode: form.delivery_mode_text || workflow?.legacy?.training_mode || '待定',
      training_period: form.period_text || workflow?.legacy?.training_period || '待定',
      expect_date: form.expected_start_date || workflow?.legacy?.expect_date || '',
      duration_value: workflow?.legacy?.original_hours || form.duration_minutes || '', duration_unit: workflow?.legacy?.original_hours || !workflow ? 'hours' : 'minutes',
    };
    let busy = false, stale = false;
    const maySubmit = !workflow || workflowActionEnabled(workflow, 'submit');
    let saveRequestId = workflowRequestId(), submitRequestId = workflowRequestId();
    const save = async (submit) => {
      if (busy || stale || !scope.current() || !mask.isConnected) return false;
      const data = collectForm(mask, fields.filter((field) => field.k !== 'filler_display'));
      if (!data) return false;
      let payload;
      try { payload = workflowDraftPayload(data, workflow, saveRequestId); }
      catch (error) { toast(error.message, true); return false; }
      busy = true; mask.dataset.locked = 'true';
      const submitButton = $('#workflow-save-submit', mask);
      if (submitButton) submitButton.disabled = true;
      try {
        const saved = await api('/demands/draft', { body: payload, signal: scope.controller.signal });
        workflow = saved.workflow;
        if (!workflow?.id) throw new Error('需求保存响应不完整，请刷新核对');
        saveRequestId = workflowRequestId();
        if (submit) await api('/demands/submit', { body: { id: workflow.id, expected_version: workflow.version, request_id: submitRequestId }, signal: scope.controller.signal });
        if (!scope.current() || !mask.isConnected) return false;
        toast(submit ? (workflow.business_path === 'bid' ? '需求已提交，等待登记原签报结果' : '需求已提交审批') : workflow.draft ? '需求草稿已保存' : '需求修改已保存，请查看最新审批进展');
        done?.();
        return true;
      } catch (error) {
        if (!scope.current() || !mask.isConnected) return false;
        workflowFieldErrors(mask, error);
        if (error.status === 409 || error.code === 409) {
          stale = true;
          $('#workflow-save-notice', mask).textContent = '该记录已被更新。请关闭弹窗并重新打开，核对最新资料后再保存。';
        } else if (workflow?.id && !error.status) {
          try {
            const latest = await api('/demands/workflow?id=' + encodeURIComponent(workflow.id), { signal: scope.controller.signal, quiet: true });
            if (!scope.current() || !mask.isConnected) return false;
            stale = latest.workflow?.version !== workflow.version;
            $('#workflow-save-notice', mask).textContent = stale ? '需求状态已更新，请关闭后重新查看；已保存或提交的操作无需重复。' : '暂未确认本次操作，可使用同一次请求重试，或关闭后查看需求。';
          } catch (_) { if (mask.isConnected) $('#workflow-save-notice', mask).textContent = '暂时无法确认保存状态，请关闭后刷新核对。'; }
        } else if (workflow?.id && submit) {
          $('#workflow-save-notice', mask).textContent = '资料已保存；提交尚未确认。请按提示完善，或关闭后刷新查看最新流程。';
        }
        return false;
      } finally {
        busy = false; mask.dataset.locked = 'false';
        if (submitButton) submitButton.disabled = stale;
      }
    };
    const mask = openModal((workflow ? '编辑' : '新增') + '培训需求', `<p class="modal-intro" id="workflow-route-hint"></p>${renderForm(fields, initial)}<p class="modal-intro" id="workflow-save-notice" role="status">${workflow && !workflow.draft ? '审批中修改会从负责人重新审批；已退回或撤回的需求，保存后可在详情重新提交。' : '可以先保存不完整草稿，资料齐全后再提交。'}</p>`, { okText: workflow && !workflow.draft ? '保存修改' : '保存草稿', onOk: () => save(false), onClose: () => scope.controller.abort() });
    if (!mask) { scope.controller.abort(); return; }
    mask.dataset.workflow = 'true';
    if (maySubmit) {
      $('.modal-foot', mask).insertAdjacentHTML('beforeend', '<button type="button" class="btn" id="workflow-save-submit">保存并提交</button>');
      $('#workflow-save-submit', mask).onclick = async () => { if (await save(true)) closeModal(); };
    }
    const updateRoute = () => {
      const bid = $('[data-k="business_path"]', mask).value === 'bid';
      const referenceItem = $('[data-k="external_approval_ref"]', mask).closest('.form-item');
      referenceItem.hidden = !bid; referenceItem.style.display = bid ? '' : 'none';
      $('#workflow-route-hint', mask).textContent = bid ? '投标沿用原办公签报；登记中标结果后，由培训团队受理。' : '直接承接：分公司负责人审批 → BP审批 → 培训团队受理。';
    };
    $('[data-k="business_path"]', mask).onchange = updateRoute;
    $('[data-k="organization_code"]', mask).onchange = (event) => {
      const select = $('[data-k="internal_contact_code"]', mask);
      const previous = select.value;
      const people = (context.people || []).filter((item) => workflowContactEligible(item, event.target.value));
      select.innerHTML = `<option value="">请选择内部对接人</option>${people.map((item) => `<option value="${esc(item.code)}">${esc(item.label)}</option>`).join('')}`;
      if (people.some((item) => item.code === previous)) select.value = previous;
    };
    updateRoute(); refreshIcons(mask);
  }
  function workflowLocalTime(value) {
    const date = new Date(value);
    if (!value || !Number.isFinite(date.getTime())) return '—';
    return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(date);
  }
  function workflowDetailHtml(workflow, context, actionsHtml = '') {
    const form = workflow.form || {}, legacy = workflow.legacy || {}, approval = workflow.approval;
    const details = [
      ['需求单位', legacy.unit], ['承接路径', workflow.business_path === 'bid' ? '投标（原办公签报）' : '直接承接'],
      ['所属机构', workflowName(context.organizations, workflow.organization_code)], ['实际填报人', workflowName(context.people, workflow.filler_code)],
      ['内部对接人', workflowName(context.people, form.internal_contact_code)], ['客户联系人', [legacy.contact, legacy.phone].filter(Boolean).join(' · ')],
      ['预计时长', workflowDurationText(workflow)], ['人数与分类', [form.participant_count ? `${form.participant_count} 人` : '', form.category_text].filter(Boolean).join(' · ')],
      ['时间与授课方式', [form.expected_start_date, form.expected_end_date, form.delivery_mode_text, form.period_text].filter(Boolean).join(' · ')],
      ['授课地点', [legacy.training_province, legacy.training_city].filter(Boolean).join(' · ')],
    ];
    if (form.budget_amount) details.push(['预算金额（元）', form.budget_amount]);
    if (workflow.business_path === 'bid') details.push(['原办公签报引用', form.external_approval_ref]);
    details.push(['培训目标', form.objectives], ['主要培训内容', legacy.content], ['师资要求', legacy.teacher_req], ['备注', legacy.remark]);
    if (workflow.bid_result) {
      const result = workflow.bid_result;
      details.push(['投标结果', result.result === 'won' ? '中标' : '未中标'], ['结果确认日期', result.result_date], ['结果说明', result.result_note], ['结果登记人', workflowName(context.people, result.actor_code)]);
    }
    const longLabels = new Set(['培训目标', '主要培训内容', '师资要求', '备注', '结果说明', '原办公签报引用']);
    const currentAssignee = approval?.assigneeId ? `当前办理人：${workflowName(context.people, approval.assigneeId)}` : '';
    let html = `<section class="workflow-details"><div class="workflow-detail-summary"><div><h4>${esc(form.title || legacy.title || '培训需求')}</h4><p>${tag(workflowLabel(workflow))}${currentAssignee ? `<span>${esc(currentAssignee)}</span>` : ''}</p></div></div>${actionsHtml ? `<div class="toolbar workflow-actions" aria-label="需求可办理操作">${actionsHtml}</div>` : ''}<div class="demand-brief-grid workflow-info">${details.map(([label, value]) => `<div${longLabels.has(label) ? ' class="workflow-long"' : ''}><small>${esc(label)}</small><p>${esc(value || '（未填写）')}</p></div>`).join('')}</div>`;
    if (approval) {
      const participants = approval.participants || {};
      html += `<section class="workflow-history" aria-label="审批记录"><h4>审批记录</h4><p class="modal-intro">负责人：${esc(workflowName(context.people, participants.leaderId))} · BP：${esc(workflowName(context.people, participants.bpId))} · 时间按设备本地时区显示</p>`;
      html += renderTable([
        { k: 'at', l: '时间', render: (item) => esc(workflowLocalTime(item.at)) },
        { k: 'stage', l: '节点', render: (item) => esc(item.action === 'APPROVE_COMBINED' && Array.isArray(item.responsibilities) && item.responsibilities.includes('LEADER') && item.responsibilities.includes('BP') ? '负责人、BP' : ({ LEADER: '分公司负责人', BP: 'BP', NONE: '填报' })[item.stage] || '填报') },
        { k: 'action', l: '操作', render: (item) => esc(WORKFLOW_ACTION[item.action] || item.action) },
        { k: 'actorId', l: '办理人', render: (item) => esc(workflowName(context.people, item.actorId) + (item.onBehalfOf && item.onBehalfOf !== item.actorId ? `（代 ${workflowName(context.people, item.onBehalfOf)}）` : '')) },
        { k: 'comment', l: '意见' },
      ], approval.history || [], [], 'approval-history');
      const reasons = [...new Set([workflow.approval_blocked_reason, approval.blocked_reason,
        ...approvalActions(approval).filter((item) => !item.enabled && ['CONFIGURATION_REQUIRED', 'ASSIGNEE_INACTIVE', 'DIRECTORY_UNKNOWN', 'COMBINED_ASSIGNMENT_CHANGED'].includes(item.code)).map((item) => item.reason)].filter(Boolean))];
      if (reasons.length) html += `<p class="modal-intro" role="status">${esc(reasons.join('；'))}</p>`;
      html += '</section>';
    }
    return html + '</section>';
  }
  function workflowCommands(workflow) {
    const commands = [];
    if (workflowActionEnabled(workflow, 'submit')) commands.push({ action: 'SUBMIT', label: '提交需求' });
    if (workflowActionEnabled(workflow, 'accept')) commands.push({ action: 'ACCEPT', label: '团队受理' });
    if (workflowActionEnabled(workflow, 'bid_result')) commands.push({ action: 'BID_RESULT', label: '登记投标结果' });
    approvalActions(workflow.approval).filter((item) => item.enabled === true && item.action !== 'REVISE' && !(item.action === 'APPROVE' && approvalCan(workflow.approval, 'APPROVE_COMBINED'))).forEach((item) => commands.push(item.action === 'APPROVE_COMBINED' ? { ...item, label: WORKFLOW_ACTION.APPROVE_COMBINED } : item));
    if (workflow.approval?.remind?.enabled === true || approvalCan(workflow.approval, 'REMIND')) {
      if (!commands.some((item) => item.action === 'REMIND')) commands.push({ action: 'REMIND', label: '催办' });
    }
    return commands;
  }
  async function showDemandWorkflow(row, action = '', options = {}) {
    const scope = workflowRouteScope();
    const notification = options.notification;
    const current = () => scope.canOpen() && (!options.current || options.current());
    const abort = () => scope.controller.abort();
    options.signal?.addEventListener('abort', abort, { once: true });
    if (options.signal?.aborted) abort();
    const detach = () => options.signal?.removeEventListener('abort', abort);
    const read = async path => {
      const value = await api(path, { signal: scope.controller.signal, quiet: Boolean(notification) });
      if (!current()) {
        if (!scope.identityCurrent() && $('#modal-mask') === scope.originalModal) closeModal(true);
        throw new DOMException('页面已关闭', 'AbortError');
      }
      return value;
    };
    let workflow, context, target = null;
    try {
      if (notification) {
        const result = await read('/notifications/' + encodeURIComponent(notification.id) + '/target');
        target = notificationTarget(result.target, notification.eventId);
        if (row?.id && String(row.id) !== target.params.recordId) throw new Error('通知事项已变化，请重新打开通知。');
        const approval = target.moduleId === 'M03';
        const [detail, ctx] = await Promise.all([
          read(approval ? '/approvals/tasks/' + encodeURIComponent(target.params.taskId) : '/demands/workflow?id=' + encodeURIComponent(target.params.recordId)),
          read('/workflow/context'),
        ]);
        context = ctx;
        if (approval) {
          const task = detail.task;
          if (!task || String(task.id) !== target.params.taskId || String(task.businessId) !== target.params.recordId || String(task.workflow?.id) !== target.params.recordId) throw new Error('审批事项与通知不一致，请刷新后核对。');
          const { workflow: base, ...approvalView } = task;
          workflow = { ...base, approval: approvalView };
        } else workflow = detail.workflow;
        if (!workflow || String(workflow.id) !== target.params.recordId) throw new Error('需求事项与通知不一致，请刷新后核对。');
      } else {
        const [detail, ctx] = await Promise.all([read('/demands/workflow?id=' + encodeURIComponent(row.id)), read('/workflow/context')]);
        workflow = detail.workflow; context = ctx;
      }
    } catch (error) {
      if (!scope.identityCurrent() && $('#modal-mask') === scope.originalModal) closeModal(true);
      if (current() && error?.name !== 'AbortError' && notification) {
        if (options.onUnavailable) options.onUnavailable(error);
        else openModal('通知事项暂时无法查看', '<p class="modal-intro">' + esc([403, 404].includes(error.status || error.code) ? '该通知或事项已失效，或当前无权查看。请关闭后重新查看本人通知。' : '通知事项暂时无法打开，请稍后重新查看。') + '</p>', { noFoot: true });
      }
      detach(); scope.controller.abort(); return;
    }
    if (!current()) { detach(); scope.controller.abort(); return; }
    if (!workflow) { detach(); scope.controller.abort(); toast('该记录尚未接入需求流程，请刷新查看原记录', true); return; }
    const readOnly = target?.params.view === 'detail';
    const commands = readOnly ? [] : workflowCommands(workflow);
    if (action && !commands.some(item => item.action === action)) { toast('该操作权限或流程状态已变化，请查看最新记录', true); action = ''; }
    // The new workflow dialog owns its lifecycle after replacing the inbox.
    detach();
    if (action) { openWorkflowCommand(workflow, context, action, scope); return; }
    const buttons = commands.map(item => `<button type="button" class="btn ${item.action === 'RETURN' || item.action === 'WITHDRAW' ? 'gray' : ''}" data-workflow-command="${esc(item.action)}">${esc(item.label || WORKFLOW_ACTION[item.action])}</button>`).join('');
    const hint = notification ? `<p class="modal-intro">${readOnly ? '从历史通知查看原需求资料与审批记录，本窗口只读。' : '已重新核对当前事项，办理操作以当前权限与流程状态为准。'}打开事项不会改变通知阅读状态。</p>` : '';
    const mask = openModal('需求详情与审批记录', hint + workflowDetailHtml(workflow, context, buttons), { noFoot: true, wide: true, onClose: () => scope.controller.abort() });
    if (!mask) { scope.controller.abort(); return; }
    mask.dataset.workflow = 'true';
    addRouteCleanup(() => { if ($('#modal-mask') === mask) closeModal(true); }, scope.epoch);
    $$('[data-workflow-command]', mask).forEach(button => { button.onclick = () => showDemandWorkflow({ id: workflow.id }, button.dataset.workflowCommand, notification ? { notification } : {}); });
    refreshIcons(mask);
  }
  function openWorkflowCommand(workflow, context, action, scope) {
    const approval = workflow.approval;
    let fields = [];
    if (action === 'ACCEPT') fields = [{ k: 'team_code', label: '承接团队', type: 'select', required: true, options: [{ v: '', l: '请选择承接团队' }, ...(context.teams || []).map((item) => ({ v: item.code, l: item.label }))] }];
    else if (action === 'BID_RESULT') fields = [
      { k: 'result', label: '投标结果', type: 'select', required: true, options: [{ v: '', l: '请选择已确认结果' }, { v: 'won', l: '中标，待团队受理' }, { v: 'lost', l: '未中标，留档' }] },
      { k: 'external_approval_ref', label: '原办公签报引用', required: true, value: workflow.form?.external_approval_ref || '' },
      { k: 'result_date', label: '结果确认日期', type: 'date', required: true },
      { k: 'result_note', label: '结果说明', type: 'textarea' },
    ];
    else if (!['SUBMIT', 'REMIND'].includes(action)) fields = [{ k: 'comment', label: action === 'RETURN' ? '退回原因' : '办理意见', type: 'textarea', required: action === 'RETURN' }];
    const requestId = workflowRequestId();
    let stale = false;
    const mask = openModal(WORKFLOW_ACTION[action] || '办理需求', `<p class="modal-intro">${action === 'APPROVE_COMBINED' ? '您将以同一办理人一次确认负责人和BP两项审批职责。通过后进入团队受理，不会另造第二名审批人或第二次批准。' : action === 'ACCEPT' ? '确认受理后形成项目，后续在原项目工作区继续交付。' : action === 'REMIND' ? '待办停留满24小时后可催办，同一流程24小时内最多一次。' : '请核对以下需求资料后确认操作。'}</p>${renderForm(fields)}${workflowDetailHtml(workflow, context)}<p class="modal-intro" id="workflow-action-notice" role="status"></p>`, {
      wide: true, onClose: () => scope.controller.abort(), okText: action === 'APPROVE' ? '确认通过' : (WORKFLOW_ACTION[action] || '确认'), onOk: async () => {
        if (stale || !scope.current() || !mask.isConnected) return false;
        const values = collectForm(mask, fields);
        if (!values) return false;
        let path, body;
        if (['SUBMIT', 'ACCEPT', 'BID_RESULT'].includes(action)) {
          path = '/demands/' + ({ SUBMIT: 'submit', ACCEPT: 'accept', BID_RESULT: 'bid-result' })[action];
          body = { id: workflow.id, expected_version: workflow.version, request_id: requestId, ...values };
        } else {
          path = '/approvals/tasks/' + encodeURIComponent(approval.id) + (action === 'REMIND' ? '/remind' : '/actions');
          body = action === 'REMIND' ? { expectedVersion: approval.version, requestId } : { action, expectedVersion: approval.version, expectedStage: approval.stage, requestId, comment: values.comment || '' };
        }
        mask.dataset.locked = 'true';
        try {
          await api(path, { body, signal: scope.controller.signal });
          if (!scope.current() || !mask.isConnected) return false;
          toast(action === 'ACCEPT' ? '团队已受理，可在项目总览继续办理' : action === 'REMIND' ? '催办已登记' : '操作已完成');
          renderPage();
          return true;
        } catch (error) {
          if (!scope.current() || !mask.isConnected) return false;
          workflowFieldErrors(mask, error);
          // Read back after uncertain/contended writes, retaining the request ID for a safe retry.
          if (!error.status || error.status === 409 || error.code === 409) {
            try {
              const latest = await api('/demands/workflow?id=' + encodeURIComponent(workflow.id), { signal: scope.controller.signal, quiet: true });
              if (!scope.current() || !mask.isConnected) return false;
              stale = latest.workflow?.version !== workflow.version || latest.workflow?.approval?.version !== approval?.version;
              $('#workflow-action-notice', mask).textContent = stale ? '流程已更新，请关闭此窗口后重新查看；已完成的操作无需再次提交。' : '最新流程尚未变化。可重试本次操作，或关闭后刷新核对。';
            } catch (_) { if (mask.isConnected) $('#workflow-action-notice', mask).textContent = '暂时无法确认最新状态，请关闭后刷新核对，避免重复办理。'; }
          }
          return false;
        } finally { mask.dataset.locked = 'false'; }
      },
    });
    if (!mask) { scope.controller.abort(); return; }
    mask.dataset.workflow = 'true';
  }
  function workflowQueueTasks(demands, approvalTasks) {
    const tasks = new Map();
    (demands || []).forEach((row) => {
      const workflow = row.workflow;
      if (!workflow) return;
      const command = workflowCommands(workflow).find((item) => ['APPROVE_COMBINED', 'APPROVE', 'RESUBMIT', 'SUBMIT', 'ACCEPT', 'BID_RESULT'].includes(item.action));
      if (!command) return;
      tasks.set(String(row.id), { key: `demand:${row.id}`, page: 'demands', focusId: row.id, tone: 'neutral', score: ['APPROVE', 'APPROVE_COMBINED'].includes(command.action) ? 76 : 46, label: workflowLabel(workflow), title: row.title || workflow.form?.title || '培训需求', meta: row.unit || workflow.legacy?.unit || '需求单位待补充', action: command.action === 'APPROVE' ? '办理审批' : command.label, workflowAuthorized: true });
    });
    (approvalTasks || []).forEach((approval) => {
      if (!approvalCan(approval, 'APPROVE_COMBINED') && !approvalCan(approval, 'APPROVE') && !approvalCan(approval, 'RESUBMIT')) return;
      const id = approval.businessId;
      const old = tasks.get(String(id));
      tasks.set(String(id), { ...old, key: `demand:${id}`, page: 'demands', focusId: id, tone: 'neutral', score: 76, label: approvalStatusLabel(approval) || '审批待办', title: approval.title || old?.title || '培训需求', meta: old?.meta || '请查看需求资料及审批记录', action: approvalCan(approval, 'APPROVE_COMBINED') ? WORKFLOW_ACTION.APPROVE_COMBINED : approvalCan(approval, 'APPROVE') ? '办理审批' : '完善并重新提交', workflowAuthorized: true });
    });
    return [...tasks.values()];
  }
  function workflowBindingNotice(binding) {
    if (binding?._error) return '业务身份暂时无法核实，新需求与审批待办请稍后刷新确认。';
    if (binding?.status === 'NOT_CONFIGURED') return '组织业务权限尚未配置，当前工作台暂未包含新需求与审批待办。';
    if (binding?.status === 'NOT_BOUND') return '当前账号尚未绑定启用的业务身份，暂时无法显示其新需求与审批待办，请联系管理员核对。';
    return '';
  }
  function organizationBindingHtml(binding) {
    if (!binding || binding._error) return `<p class="modal-intro">${esc(binding?._error || '业务身份信息暂时无法读取，请稍后重试。')}</p>`;
    if (binding.status === 'NOT_CONFIGURED') return '<p class="modal-intro">组织业务身份尚未配置。请联系管理员补齐人员、机构和岗位关系。</p>';
    if (binding.status === 'NOT_BOUND') return '<p class="modal-intro">当前账号尚未绑定启用的业务身份。请联系管理员核对本人、所属机构和审批人员配置。</p>';
    const person = binding.person || {};
    const personCode = person.personCode || person.person_code || binding.person_code;
    if (binding.status !== 'BOUND' || !personCode) return '<p class="modal-intro">业务身份状态尚未核实，请刷新后重试。</p>';
    const organizationCode = person.organizationCode || person.organization_code || binding.organization_code || binding.organization?.organizationCode || '待配置';
    return `<div class="demand-brief-grid"><div><small>业务身份：已绑定</small><p>${esc(state.user?.name || '当前账号')}</p></div><div><small>人员编码</small><p>${esc(personCode)}</p></div><div><small>所属机构编码</small><p>${esc(organizationCode)}</p></div></div><p class="modal-intro">审批及受理权限按业务身份与机构授权确定。</p>`;
  }
  async function showOrganizationBinding() {
    const scope = workflowRouteScope();
    const binding = await api('/organization/me', { signal: scope.controller.signal }).catch(() => null);
    if (!binding || !scope.canOpen()) return;
    openModal('我的业务身份', organizationBindingHtml(binding), { noFoot: true });
  }

  async function editForm(cfg, row, done) {
    if (cfg.mod === 'demands' && (!row || row.workflow)) return editWorkflowDemand(row, done);
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
    if (!formMask) return;
  }

  function showProjectOverview(project) {
    navigateTo('project_detail', { projectId: project.id });
  }

  async function pageProjectDetail(c) {
    const epoch = routeEpoch;
    const identity = state.user;
    const projects = await api('/projects');
    if (state.user !== identity || !isRouteCurrent(epoch, c, 'project_detail')) return;
    const project = projects.find((p) => String(p.id) === String(state.projectId));
    if (!project) throw new Error('未找到该项目，可能已被删除');
    if (hasLimitedProjectAccess(project)) return renderLimitedProjectWorkspace(c, project, epoch);
    const externalEvaluation = Boolean(project.workflow_source);
    const [dispatches, questionnaires, charges, fees, costs, demands, bids] = await Promise.all([
      api('/dispatches?project_id=' + project.id), externalEvaluation ? Promise.resolve([]) : api('/questionnaires?project_id=' + project.id), api('/charges?project_id=' + project.id),
      api('/fees?project_id=' + project.id), api('/costs?project_id=' + project.id), api('/demands'), api('/bids?demand_id=' + (project.demand_id || '')),
    ]);
    if (!isRouteCurrent(epoch, c, 'project_detail')) return;
    const demand = demands.find((d) => String(d.id) === String(project.demand_id));
    const validDispatches = dispatches.filter((d) => d.status !== '已拒绝');
    const scheduledHours = validDispatches.reduce((s, d) => s + Number(d.hours || 0), 0);
    const confirmedHours = dispatches.filter((d) => ['已确认', '已完成'].includes(d.status)).reduce((s, d) => s + Number(d.hours || 0), 0);
    const completedHours = projectCompletedHours(project, dispatches);
    const missingHours = Math.max(0, Number(project.hours || 0) - scheduledHours);
    const received = charges.reduce((s, r) => s + Number(r.received || 0), 0);
    const due = charges.reduce((s, r) => s + Number(r.amount || 0), 0);
    const collection = collectionProgress(project.amount, due, received);
    const outstanding = collection.outstanding;
    const deliveryActive = !['已完成', '已归档'].includes(project.status);
    const settlementNotice = !charges.length && collection.target > 0 ? '尚未登记应收。待回款按合同额计算，请先核对应收计划。' : collection.mismatch ? '已登记应收与合同金额不一致。待回款按合同额计算，归档前请核对。' : '';
    function formalProjectSettlementHtml(project) {
      const value = project.delivery_settlement;
      const isMoney = amount => typeof amount === 'string' && /^-?\d+(?:\.\d{1,2})?$/.test(amount);
      const valid = value && value.project_id === project.id && value.payment_integration === 'CONFIGURED'
        && ['AVAILABLE', 'INCOMPLETE'].includes(value.status)
        && Number.isSafeInteger(value.missing_claim_count) && value.missing_claim_count >= 0
        && typeof value.pending_cases === 'boolean' && typeof value.can_archive === 'boolean'
        && [value.known_confirmed_amount, value.known_paid_amount, value.known_balance].every(isMoney)
        && (value.status === 'AVAILABLE'
          ? [value.confirmed_amount, value.paid_amount, value.balance].every(isMoney)
          : [value.confirmed_amount, value.paid_amount, value.balance].every(amount => amount === null));
      if (!valid) return `<p class="v13-finance-notice">${esc(project.delivery_settlement_reason || '课酬结算摘要暂不可读取，请核对权限或重新读取。')}</p>`;
      const complete = value.status === 'AVAILABLE';
      const due = complete ? value.confirmed_amount : value.known_confirmed_amount;
      const paid = complete ? value.paid_amount : value.known_paid_amount;
      const balance = complete ? value.balance : value.known_balance;
      const partial = complete ? '' : '已核对部分';
      const balanceLabel = /^-/.test(balance) ? '待退款余额' : /^0+(?:\.0+)?$/.test(balance) ? '当前余额' : '待付款余额';
      return `<div data-project-formal-settlement><h3>课酬结算</h3><dl class="v13-finance-list">
        <div><dt>${partial}应付</dt><dd>¥ ${esc(due)}</dd></div>
        <div><dt>已登记净实付</dt><dd>¥ ${esc(paid)}</dd></div>
        <div><dt>${partial}${balanceLabel}</dt><dd>¥ ${esc(balance)}</dd></div>
      </dl><p class="v13-finance-notice">${complete ? '当前课酬事项已完整汇总。' : `完整金额尚待核对；${value.missing_claim_count} 条授课尚无确认课酬${value.pending_cases ? '，还有未结清或未完成的事项' : ''}。以上仅为已核对部分，不能当作全项目总额。`}</p>
      ${value.archive_reason ? `<p class="v13-finance-notice">${esc(value.archive_reason)}</p>` : ''}
      <p class="modal-intro">应付和实际付款分别登记；项目归档还须核对回款与其他流程条件。</p></div>`;
    }
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
    const urgentPending = dispatches.filter((d) => deliveryActive && ['待发送', '已拒绝'].includes(d.status) && dayDiff(d.teach_date) !== null && dayDiff(d.teach_date) <= 3);
    const materialRisks = dispatches.filter((d) => deliveryActive && !['已拒绝', '已完成'].includes(d.status) && dayDiff(d.teach_date) !== null && dayDiff(d.teach_date) <= 3 && d.material_status !== '已就绪');
    const awaitingConfirm = dispatches.filter((d) => deliveryActive && d.status === '已发送');
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
    materialRisks.forEach((d) => upsertIssue(`dispatch:${d.id}`, { tone: dayDiff(d.teach_date) <= 1 ? 'critical' : 'warning', score: dayDiff(d.teach_date) <= 1 ? 88 : 68, type: '交付准备', art: 'documents', title: `${d.subject} · 材料${d.material_status || '状态待补充'}`, detail: `${d.teach_date} 开课 · ${d.venue || '场地尚未确定'}`, page: 'dispatches', focusId: d.id, action: '补齐交付准备' }));
    awaitingConfirm.forEach((d) => upsertIssue(`dispatch:${d.id}`, { tone: 'warning', score: 78, type: '师资确认', title: `${d.subject} · 等待讲师确认`, detail: `${d.teacher_name || '待定讲师'} · 确认截止 ${d.confirm_deadline || '待定'}`, page: 'dispatches', focusId: d.id, action: '记录确认结果' }));
    if (deliveryActive && missingHours > 0) upsertIssue('schedule-gap', { tone: 'warning', score: 74, type: '排课缺口', art: 'calendar', title: `计划课时尚缺 ${num(missingHours)} 课时`, detail: `计划 ${num(project.hours)} 课时，目前已有效排课 ${num(scheduledHours)} 课时`, page: 'dispatches', action: `安排 ${num(missingHours)} 课时` });
    if (settlementNotice && project.status !== '已归档') upsertIssue('receivable-plan', { tone: 'warning', score: 54, type: '应收核对', title: charges.length ? '应收计划与合同金额不一致' : '尚未登记应收计划', detail: settlementNotice, page: 'charges', action: '核对应收计划' });
    if (outstanding > 0) upsertIssue('payment', { tone: 'neutral', score: 52, type: '回款跟进', title: `还有 ¥ ${money(outstanding)} 尚未回款`, detail: `当前回款率 ${Math.round(collection.rate)}%`, page: 'charges', action: '登记本次回款' });
    if (!externalEvaluation && !questionnaires.length && project.status !== '已归档') upsertIssue('evaluation', { tone: 'neutral', score: 42, type: '评估准备', title: '本项目尚未创建效果评估', detail: '按项目需要准备培训反馈回收', page: 'questionnaires', action: '创建效果评估' });
    if (project.delivery_controlled !== true && feePending > 0) upsertIssue('fees', { tone: 'neutral', score: 36, type: '课酬结算', title: `待发放课酬 ¥ ${money(feePending)}`, detail: `${fees.filter((r) => r.status === '待发放').length} 笔课酬等待处理`, page: 'fees', action: '核对待发课酬' });
    const issues = [...issueMap.values()].sort((a, b) => b.score - a.score);
    if (!canWrite() || project.status === '已归档') issues.forEach((item) => { item.action = readonlyAction(item.page); });
    const health = issues.some((x) => x.tone === 'critical') ? ['有风险', 'critical'] : issues.some((x) => x.tone === 'warning') ? ['需关注', 'warning'] : ['正常', 'good'];
    const milestone = ['已完成', '已归档'].includes(project.status) ? project.status : startIn === null ? '开课日期待定' : startIn < 0 ? (project.status === '待启动' ? '等待启动' : '项目交付中') : startIn === 0 ? '今天开课' : startIn === 1 ? '明天开课' : `距离开课 ${startIn} 天`;
    const paymentRate = collection.rate;
    const projectJourney = [
      { label: '项目资料', value: project.contract_no && project.owner && project.start_date ? '信息已齐' : '仍需补充', page: 'projects', status: project.contract_no && project.owner && project.start_date ? 'done' : 'current', art: 'documents', ico: 'file-check-2' },
      { label: '课程排期', value: `${num(scheduledHours)} / ${num(project.hours)} 课时`, page: 'dispatches', status: scheduledHours >= Number(project.hours || 0) && Number(project.hours || 0) > 0 ? 'done' : scheduledHours > 0 ? 'current' : 'todo', art: 'calendar', ico: 'calendar-clock' },
      { label: '课程交付', value: project.delivery_controlled === true ? `${deliveryHoursText(completedHours)} 已核对实际` : `${num(completedHours)} 已完成 · ${num(confirmedHours)} 已确认`, page: 'dispatches', status: completedHours !== null && Number(completedHours) >= Number(project.hours || 0) && Number(project.hours || 0) > 0 ? 'done' : confirmedHours > 0 ? 'current' : 'todo', art: 'faculty', ico: 'badge-check' },
      { label: '效果评估', value: externalEvaluation ? '原问卷结果导入待接入' : questionnaires.length ? `${questionnaires.length} 份问卷 · ${responseRate}% 回收` : '尚未创建问卷', page: 'questionnaires', unavailable: externalEvaluation, status: externalEvaluation ? 'todo' : responseRate >= 60 ? 'done' : questionnaires.length ? 'current' : 'todo', art: 'evaluation', ico: 'clipboard-check' },
      { label: externalEvaluation ? '项目回款' : '回款结算', value: collection.target > 0 ? `${paymentRate.toFixed(0)}% 已回款` : '无需回款', page: 'charges', status: collectionStageStatus(collection, received), art: 'collection', ico: 'badge-japanese-yen' },
    ];

    const writableProject = canWrite() && project.status !== '已归档';
    const taskHtml = (item, priority = false) => `<button type="button" class="workspace-task ${item.tone} ${priority ? 'is-priority' : ''}" data-project-goto="${item.page}" ${item.focusId ? `data-focus-id="${item.focusId}"` : ''}><span class="task-signal">${businessArt(taskArtKind(item))}</span><span><small>${priority ? (writableProject ? '优先处理 · ' : '优先查看 · ') : ''}${esc(item.type)}</small><b>${esc(item.title)}</b><em>${esc(item.detail)}</em><strong>${esc(item.action)}${icon('chevron-right')}</strong></span></button>`;
    c.innerHTML = `
      <div class="workspace-back"><button type="button" id="project-back">${icon('chevron-left')}所有项目</button><span>项目 #P-${String(project.id).padStart(4, '0')}</span></div>
      ${project.status === '已归档' ? `<div class="readonly-banner">${icon('archive')}<span><b>项目已归档</b> 交付与财务事实已锁定，可查看但不能修改。</span></div>` : ''}
      <header class="project-workspace-head">
        <div><div class="workspace-kicker"><span class="health-dot ${health[1]}"></span>${health[0]} · ${esc(milestone)}</div><h1>${esc(project.title)}</h1><p>${esc(project.unit || '委托单位待补充')}<span></span>${esc(project.owner || '负责人待补充')}<span></span>${esc(project.start_date || '待定')} — ${esc(project.end_date || '待定')}</p></div>
        <div class="workspace-head-actions">${writableProject ? `<button type="button" class="btn gray" id="project-edit">${icon('pencil')}编辑资料</button>${project.status === '待启动' ? `<button type="button" class="btn" id="project-start">${icon('play')}启动项目</button>` : project.status === '进行中' ? `<button type="button" class="btn gray" id="project-transition" data-action="complete">${icon('check')}完成交付</button>` : project.status === '已完成' ? `<button type="button" class="btn gray" id="project-transition" data-action="archive">${icon('archive')}归档项目</button>` : ''}` : ''}</div>
      </header>

      <div class="project-metrics workspace-metrics" aria-label="项目关键进度">
        <div><small>已安排课时</small><b>${num(scheduledHours)}<em> / ${num(project.hours)}</em></b><span class="metric-note">已排 / 计划课时</span></div>
        <div><small>${project.delivery_controlled === true ? '已核对实际课时' : '已交付课时'}</small><b>${project.delivery_controlled === true ? esc(deliveryHoursText(completedHours)) : num(completedHours)}<em> / ${num(project.hours)}</em></b><span class="metric-note">${project.delivery_controlled === true ? '仅计已核对且完成的授课记录' : `${num(confirmedHours)} 课时已确认`}</span></div>
        <div><small>待回款</small><b>¥ ${money(outstanding)}</b><span class="metric-note">${collection.target > 0 ? `已回款 ${paymentRate.toFixed(0)}%` : '无需回款'}</span></div>
        <div><small>估算余额</small><b>${project.delivery_controlled === true ? '待核算' : `¥ ${money(estimatedBalance)}`}</b><span class="metric-note">${project.delivery_controlled === true ? '需完整核对课酬与成本' : '按已录成本 · 非最终利润'}</span></div>
      </div>

      ${project.delivery_controlled === true ? `<p class="inline-note">${esc(deliverySummaryText(project))}</p>` : ''}
      <nav class="project-journey" aria-label="项目推进路径">${projectJourney.map((step) => `<button type="button" class="${step.status}" ${step.unavailable ? `data-survey-stage disabled aria-label="${esc(step.value)}"` : `data-project-goto="${step.page}"`} ${step.page === 'projects' ? `data-focus-id="${project.id}"` : ''}><span class="journey-icon">${businessArt(step.art)}</span><span class="journey-copy"><b>${step.label}</b><small>${step.unavailable ? '待接入' : project.status === '已归档' && step.page === 'questionnaires' && !questionnaires.length ? '未设置' : step.status === 'done' ? '已就绪' : step.status === 'current' ? '跟进中' : '待推进'}</small></span>${icon('chevron-right')}</button>`).join('')}</nav>

      <div class="v13-workspace-columns">
        <div class="v13-workspace-main">
          <section class="workspace-panel risk-panel">
            <div class="workspace-panel-head"><div><h2>${writableProject ? '接下来要做' : '项目关注事项'}</h2><small>按紧急程度排序</small></div><span class="v13-section-count">${issues.length} 项</span></div>
            <div class="workspace-tasks">${issues.length ? issues.slice(0, 3).map((item, index) => taskHtml(item, index === 0)).join('') : `<div class="workspace-clear">${icon('circle-check')}<div><b>${project.status === '已归档' ? '项目已归档，暂无需关注事项' : '当前没有待处理事项'}</b><span>${project.status === '已归档' ? '可继续查阅历史交付与结算记录' : '继续按计划跟踪课程与回款节点'}</span></div></div>`}${issues.length > 3 ? `<details class="workspace-more-tasks"><summary>查看其余 ${issues.length - 3} 项待办${icon('chevron-down')}</summary>${issues.slice(3).map((item) => taskHtml(item)).join('')}</details>` : ''}</div>
          </section>

          <section class="workspace-panel delivery-panel">
            <div class="workspace-panel-head"><div><h2>课程安排</h2><small>讲师、时间与交付准备</small></div><button type="button" data-project-goto="dispatches">全部课程${icon('chevron-right')}</button></div>
            <div class="delivery-rows">${dispatches.length ? dispatches.slice(0, 5).map((d) => `<button type="button" class="delivery-row" data-project-goto="dispatches" data-focus-id="${d.id}"><time datetime="${esc(d.teach_date || '')}" aria-label="${esc(d.teach_date || '日期待定')}"><small>${esc((d.teach_date || '').slice(5, 7) || '--')}月</small><b>${esc((d.teach_date || '').slice(8) || '--')}</b></time><span><b>${esc(d.subject)}</b><small>${esc(d.teacher_name || '讲师待定')} · ${esc([d.start_time, d.end_time].filter(Boolean).join('—') || `${num(d.hours)} 课时`)}<em>${esc(d.venue || '场地待定')}</em></small></span><span class="delivery-tags">${tag(d.material_status || '材料待补充')}${tag(d.status)}${icon('chevron-right')}</span></button>`).join('') : `<div class="workspace-empty">${businessArt('calendar')}<b>尚未安排课程</b><span>先完成讲师与排期安排</span><button type="button" data-project-goto="dispatches">${writableProject ? '安排课程' : '查看排期'}${icon('chevron-right')}</button></div>`}</div>
          </section>

          <section class="workspace-panel evaluation-panel">
            <div class="workspace-panel-head"><div><h2>效果评估</h2><small>${externalEvaluation ? '原问卷结果' : '反馈回收与培训质量'}</small></div>${externalEvaluation ? '' : `<button type="button" data-project-goto="questionnaires">${writableProject ? '管理评估' : '查看评估'}${icon('chevron-right')}</button>`}</div>
            ${externalEvaluation ? `<div class="workspace-empty">${businessArt('evaluation')}<b id="project-survey-title">评分统计与复核</b><span id="project-survey-description">继续使用原问卷平台；草案预览与正式汇总分开，正式保存和复核须有明确政策与权限。</span><div id="project-survey-preview"></div><div id="project-survey-formal"></div></div>` : questionnaires.length ? `<div class="evaluation-focus"><div><small>问卷</small><b>${questionnaires.length}<em> 份</em></b></div><div><small>计划触达</small><b>${sendTotal}<em> 人次</em></b></div><div><small>已回收</small><b>${recvTotal}<em> · ${responseRate}%</em></b></div></div><p class="evaluation-note">${project.status === '已归档' ? icon('archive') + `历史回收率 ${responseRate}%，项目已归档` : responseRate >= 60 ? icon('circle-check') + '已达到基础复盘回收要求' : icon('info') + '回收率不足 60%，建议再次触达学员'}</p>` : `<div class="workspace-empty">${businessArt('evaluation')}<b>${project.status === '已归档' ? '本项目未设置评估' : '尚未创建评估问卷'}</b><span>${project.status === '已归档' ? '历史状态，可在项目资料中核对' : '按项目需要准备培训反馈回收'}</span><button type="button" data-project-goto="questionnaires">${writableProject ? '创建评估' : '查看评估'}${icon('chevron-right')}</button></div>`}
          </section>
          <section class="workspace-panel summary-panel"><div class="workspace-panel-head"><div><h2>培训总结</h2><small>草稿正文与版本留存</small></div></div><div id="project-summary-content"></div></section>
        </div>

        <aside class="v13-workspace-aside" aria-label="结算与项目资料">
          <section class="workspace-panel finance-panel">
            <div class="workspace-panel-head"><div><h2>结算概览</h2></div><button type="button" data-project-goto="charges">查看${icon('chevron-right')}</button></div>
            <dl class="v13-finance-list"><div><dt>合同金额</dt><dd>¥ ${money(project.amount)}</dd></div><div><dt>应收金额</dt><dd>¥ ${money(due)}</dd></div><div><dt>已回款</dt><dd>¥ ${money(received)}</dd></div><div class="is-outstanding"><dt>待回款</dt><dd>¥ ${money(outstanding)}</dd></div></dl>${settlementNotice ? `<p class="v13-finance-notice">${icon('info')}${esc(settlementNotice)}</p>` : ''}
            ${project.delivery_controlled === true ? formalProjectSettlementHtml(project) : ''}
            <div class="v13-finance-links"><button type="button" data-project-goto="fees"><span>课酬发放<small>${project.delivery_controlled === true ? '查看应付与实际收付' : `已录 ¥ ${money(feeTotal)} · 待发 ¥ ${money(feePending)}`}</small></span>${icon('chevron-right')}</button><button type="button" data-project-goto="costs"><span>成本费用<small>已录 ¥ ${money(costTotal)}</small></span>${icon('chevron-right')}</button></div>
            <details class="finance-definition"><summary>${icon('info')}估算余额如何计算${icon('chevron-down')}</summary><div><p>${project.delivery_controlled === true ? '课酬应付、实际收付与成本须分别核对，当前不据局部金额推算项目利润。' : '合同额 − 已录课酬 − 已录成本。未排课程的课酬和尚未登记的费用不会自动计入，当前余额不代表最终利润。'}</p><button type="button" data-project-goto="dispatches">检查排课</button><button type="button" data-project-goto="costs">${writableProject ? '补录成本' : '查看成本'}</button></div></details>
          </section>

          <details class="v13-project-brief"><summary>${icon('text-align-start')}项目资料${icon('chevron-down')}</summary><div class="demand-brief-grid"><div><small>培训目标与内容</small><p>${esc(demand?.content || project.remark || '尚未记录详细培训目标')}</p></div><div><small>师资要求</small><p>${esc(demand?.teacher_req || '尚未记录师资要求')}</p></div><div><small>交付信息</small><p>${esc([project.contract_no ? `合同 ${project.contract_no}` : '合同编号待补充', project.participant_count ? `${project.participant_count} 人` : '人数待定', project.delivery_mode || '授课方式待定', project.venue || '场地待定'].join(' · '))}</p></div><div><small>客户联系人</small><p>${esc(demand ? `${demand.contact || '—'} · ${demand.phone || '—'}` : '—')}</p></div><div><small>来源记录</small><p>${esc(demand?.title || '未关联原始需求')} · ${esc(workflowProjectSource(project, demand))}${demand?.workflow ? `<br>${tag(workflowLabel(demand.workflow))}<button type="button" class="btn ghost" id="project-workflow-detail">查看需求及审批记录</button>` : ''}</p></div></div></details>
        </aside>
      </div>`;

    if (externalEvaluation) mountSurveyPreviewEntry(c, $('#project-survey-preview', c), { epoch, page: 'project_detail', projectId: project.id, onReady: () => {
      $('#project-survey-title', c).textContent = '评分统计与复核';
      $('#project-survey-description', c).textContent = '原评分草案可预览；正式保存、独立复核与历史修订使用旁边的正式汇总入口。';
      const stage = $('[data-survey-stage]', c);
      if (stage) { stage.setAttribute('aria-label', '查看正式汇总与复核；评分草案另行预览'); $('small', stage).textContent = '汇总与复核'; }
    } });
    if (externalEvaluation) {
      const formal = mountSurveyFormalEntry(c, $('#project-survey-formal', c), { epoch, page: 'project_detail', projectId: project.id, projects: [project] });
      const stage = $('[data-survey-stage]', c);
      if (stage && formal) { stage.disabled = false; stage.setAttribute('aria-label', '查看正式汇总与复核'); $('small', stage).textContent = '汇总与复核'; stage.onclick = formal.open; }
    }
    mountProjectSummary(c, project, epoch);
    const workflowDetailButton = $('#project-workflow-detail', c);
    if (workflowDetailButton) workflowDetailButton.onclick = () => showDemandWorkflow(demand);
    $('#project-back').onclick = () => navigateTo('projects');
    const projectEdit = $('#project-edit');
    if (projectEdit) projectEdit.onclick = () => editForm(editableCrudConfig(CRUD.projects, project), project, () => { state.page = 'project_detail'; renderLayout(); });
    const projectStart = $('#project-start');
    if (projectStart) projectStart.onclick = () => showProjectStart(project);
    const projectTransition = $('#project-transition');
    if (projectTransition) projectTransition.onclick = () => showProjectTransition(project, projectTransition.dataset.action);
    $$('[data-project-goto]', c).forEach((btn) => { btn.onclick = () => {
      if (btn.closest('.project-journey') && btn.dataset.projectGoto === 'projects') {
        const brief = $('.v13-project-brief', c);
        brief.open = true;
        brief.scrollIntoView({ behavior: prefersReducedMotion() ? 'auto' : 'smooth', block: 'start' });
        $('summary', brief).focus({ preventScroll: true });
        return;
      }
      navigateTo(btn.dataset.projectGoto, {
        projectId: btn.dataset.projectGoto === 'projects' ? null : project.id,
        focusId: btn.dataset.focusId || null,
      });
    }; });
    refreshIcons(c);
  }

  function mountProjectSummary(c, project, epoch) {
    const root = $('#project-summary-content', c), identity = state.user, projectId = Number(project.id);
    const lifetime = new AbortController();
    let disposed = false, loadTicket = 0, loadController = null, view = null, owned = null;
    const current = () => !disposed && !lifetime.signal.aborted && state.user === identity && String(state.projectId) === String(projectId) && isRouteCurrent(epoch, c, 'project_detail');
    const copy = value => JSON.parse(JSON.stringify(value));
    const empty = () => ({ achievements: '', issues: '', nextSteps: '' });
    const deliverySourceHtml = (source, compact = false, hidden = false) => {
      if (hidden || source?.status !== 'AVAILABLE' || !source.value) {
        return `<p class="modal-intro" data-summary-delivery>${compact ? hidden ? '授课来源按当前权限隐藏。' : '本版尚未带入授课来源。' : hidden ? '当前账号无权查看授课来源，仍可填写获授权的总结正文。' : '本版总结尚未带入授课来源，可在权限和记录齐备后刷新业务来源。'}</p>`;
      }
      const facts = source.value;
      const hours = value => typeof value === 'string' && /^\d+(?:\.\d+)?$/.test(value) ? esc(value) : '待补齐';
      const count = value => Number.isSafeInteger(value) && value >= 0 ? String(value) : '待核实';
      return `<div data-summary-delivery><p class="modal-intro"><b>授课记录</b> · 有效排课 ${count(facts.dispatch_count)} 条 · 已完成且核对 ${count(facts.reviewed_completed_count)} 条 · 未完成 ${count(facts.pending_count)} 条 · 已完成待核实 ${count(facts.unverified_completed_count)} 条${Number.isSafeInteger(facts.rejected_count) && facts.rejected_count > 0 ? ` · 已拒绝 ${facts.rejected_count} 条（不计入）` : ''}</p><div class="table-wrap"><table class="tbl"><thead><tr><th scope="col">课时类型</th><th scope="col">课时</th><th scope="col">统计范围</th></tr></thead><tbody>${[['预计', 'estimated', '有效排课'], ['计划', 'planned', '有效排课'], ['实际', 'actual', '已完成且核对的授课'], ['计酬', 'payable', '已完成且核对的授课']].map(([label, key, basis]) => `<tr><td data-label="课时类型">${label}</td><td data-label="课时">${hours(facts[key])}</td><td data-label="统计范围">${basis}</td></tr>`).join('')}</tbody></table></div><p class="modal-intro">${source.reason === 'NO_DISPATCHES' ? '该来源版本尚无排课记录。' : ''}45 分钟为 1 基本课时。缺少依据的数值显示“待补齐”；计酬课时不代表已确认课酬或已支付。${Number.isSafeInteger(facts.excluded_count) && facts.excluded_count > 0 ? `另有 ${facts.excluded_count} 条来源待核实，未计入已核对授课。` : ''}</p></div>`;
    };
    const nonnegative = value => Number.isSafeInteger(value) && value >= 0;
    const exact = (value, keys) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));
    const flowLabel = (status, mode = null) => mode === 'SINGLE_EXPLICIT' && status === 'SUBMITTED' ? '待负责人及BP复核（兼任）' : ({ DRAFT: '待提交', SUBMITTED: '复核中', RETURNED: '已退回', APPROVED: '已通过两方复核' })[status];
    const roleLabel = role => ({ BRANCH: '分公司负责人', BP: 'BP', COMBINED: '负责人及BP（兼任）' })[role] || '未指定';
    const validResponsibilities = (role, value) => {
      const expected = role === 'COMBINED' ? ['BRANCH', 'BP'] : [role];
      return Array.isArray(value) && value.length === expected.length && expected.every((item, i) => value[i] === item);
    };
    const validDecision = decision => {
      const keys = ['review_role', 'actor_code', 'reviewed_at', 'workflow_version'];
      if (Object.hasOwn(decision || {}, 'responsibilities')) keys.push('responsibilities');
      return exact(decision, keys) && ['BRANCH', 'BP', 'COMBINED'].includes(decision.review_role) && typeof decision.actor_code === 'string' && typeof decision.reviewed_at === 'string' && nonnegative(decision.workflow_version) && (decision.responsibilities === undefined ? decision.review_role !== 'COMBINED' : validResponsibilities(decision.review_role, decision.responsibilities));
    };
    const validWorkflow = (value, revision, mutation = false) => {
      const keys = ['project_id', 'revision', 'workflow_version', 'status', 'submitted_revision', 'submitter_code', 'branch_reviewer_code', 'bp_reviewer_code', 'review_order', 'policy_version', 'policy_configured', 'decisions', 'capabilities', 'photo_policy', 'synthetic'];
      if (Object.hasOwn(value || {}, 'review_mode') || Object.hasOwn(value || {}, 'review_blocked_reason')) keys.push('review_mode', 'review_blocked_reason');
      if (mutation) keys.push('replayed', 'result_workflow_version');
      const c = value?.capabilities, optional = field => value[field] === null || typeof value[field] === 'string';
      if (!exact(value, keys) || value.project_id !== projectId || !nonnegative(value.revision) || value.revision !== revision || !nonnegative(value.workflow_version) || !flowLabel(value.status) || value.synthetic !== false || !['TEXT_PLACEHOLDERS_ONLY', 'PROJECT_UPLOADS_VERSIONED'].includes(value.photo_policy) || !(value.submitted_revision === null || nonnegative(value.submitted_revision)) || !['submitter_code', 'branch_reviewer_code', 'bp_reviewer_code', 'policy_version'].every(optional) || ![null, 'BRANCH_THEN_BP', 'UNORDERED', 'COMBINED'].includes(value.review_order) || typeof value.policy_configured !== 'boolean' || !exact(c, ['edit', 'refresh_sources', 'submit', 'review', 'review_role', 'export']) || !['edit', 'refresh_sources', 'submit', 'review', 'export'].every(key => typeof c[key] === 'boolean') || ![null, 'BRANCH', 'BP', 'COMBINED'].includes(c.review_role) || c.review !== (c.review_role !== null) || !Array.isArray(value.decisions) || value.decisions.length > 2 || !value.decisions.every(validDecision) || new Set(value.decisions.map(d => d.review_role)).size !== value.decisions.length || (mutation && (typeof value.replayed !== 'boolean' || !nonnegative(value.result_workflow_version) || value.result_workflow_version > value.workflow_version))) throw new Error('总结流程响应不完整，请重新读取');
      const mode = value.review_mode;
      if ((mode !== undefined && ![null, 'DUAL_REVIEW', 'SINGLE_EXPLICIT'].includes(mode))
        || (value.review_blocked_reason !== undefined && value.review_blocked_reason !== null && typeof value.review_blocked_reason !== 'string')
        || (value.review_blocked_reason != null && c.review)
        || (mode === 'SINGLE_EXPLICIT' ? value.review_order !== 'COMBINED' || ![null, 'COMBINED'].includes(c.review_role) || value.decisions.length > 1 || value.decisions.some(d => d.review_role !== 'COMBINED') : value.review_order === 'COMBINED' || c.review_role === 'COMBINED' || value.decisions.some(d => d.review_role === 'COMBINED'))) throw new Error('总结复核职责不一致，请重新读取');
      return value;
    };
    const feedbackHidden = sources => ['FEEDBACK_HIDDEN', 'DELIVERY_AND_FEEDBACK_HIDDEN'].includes(sources?.visibility);
    const feedbackSourceHtml = sources => {
      const source = sources?.feedback;
      if (feedbackHidden(sources) || source?.reason === 'SURVEY_READ_REQUIRED') return '<p data-summary-feedback>评价来源按当前权限隐藏。</p>';
      if (source?.status !== 'AVAILABLE' || source?.format !== 'M07-REVIEWED-SOURCE-1' || !Array.isArray(source.value?.results)) return '<p data-summary-feedback>本版尚未带入已复核评价；不可用来源不代表没有评价。可在权限和记录齐备后刷新业务来源。</p>';
      const count = value => nonnegative(value) ? String(value) : '待核实';
      const decimal = value => typeof value === 'string' && /^\d+(?:\.\d+)?$/.test(value) ? esc(value) : '待补齐';
      return `<section data-summary-feedback><h3>已复核评价</h3><p>各份汇总独立展示，每题使用自己的有效样本数；不合并均分。</p>${source.value.results.map((result, index) => `<section><h4>评价汇总 ${index + 1}</h4><p>答卷行数：${count(result.summary?.responseRowCount)} · 复核时间：${esc(workflowLocalTime(result.review?.reviewed_at))}</p><div class="table-wrap"><table class="tbl"><thead><tr><th>题目</th><th>均分</th><th>有效样本</th><th>空白</th><th>无效</th></tr></thead><tbody>${(Array.isArray(result.summary?.questions) ? result.summary.questions : []).map(q => `<tr><td>${esc(q.label)}</td><td>${decimal(q.averageText)}</td><td>${count(q.validCount)}</td><td>${count(q.blankCount)}</td><td>${count(q.invalidCount)}</td></tr>`).join('')}</tbody></table></div></section>`).join('')}</section>`;
    };
    const sourceHtml = (sources, saved = true, compact = false) => {
      const value = sources?.project?.value;
      if (compact) return `<section data-summary-preview-sources><h3>保存时的业务数据</h3><p>${value ? `项目日期：${esc(value.start_date ?? '待补齐')} 至 ${esc(value.end_date ?? '待补齐')}；人数：${esc(value.participant_count ?? '待补齐')}；项目状态：${esc(value.project_status ?? '待补齐')}。` : '项目来源暂不可用。'}</p>${deliverySourceHtml(sources?.delivery, true, ['DELIVERY_HIDDEN', 'DELIVERY_AND_FEEDBACK_HIDDEN'].includes(sources?.visibility))}${feedbackSourceHtml(sources)}</section>`;
      return `<div class="inline-note"><span><b>${saved ? '已冻结的业务来源' : '当前可用来源（首次保存时冻结）'}</b><br>${value ? `项目日期：${esc(value.start_date ?? '待补齐')} 至 ${esc(value.end_date ?? '待补齐')}；人数：${esc(value.participant_count ?? '待补齐')}。` : '项目来源暂不可用。'}<br>不可用来源不代表本项目没有授课或评价。评价与授课分别按当前权限展示；实际照片按保存版本查看，文字图注单独保留。</span></div>${deliverySourceHtml(sources?.delivery, false, ['DELIVERY_HIDDEN', 'DELIVERY_AND_FEEDBACK_HIDDEN'].includes(sources?.visibility))}${feedbackSourceHtml(sources)}`;
    };
    const contentHtml = content => {
      const p = content?.publicity;
      const text = value => `<p style="white-space:pre-wrap;overflow-wrap:anywhere">${esc(value ?? '')}</p>`;
      return `<div class="detail-text" style="overflow-wrap:anywhere">${p ? `<h3>${esc(p.title || '未填写总结标题')}</h3>${text(p.introduction)}<ol data-summary-sections>${p.sections.map(section => `<li><h4>${esc(section.heading)}</h4>${text(section.body)}</li>`).join('')}</ol>${p.photoCaptions.length ? `<h4>文字图注</h4><ol data-summary-captions>${p.photoCaptions.map(caption => `<li>${text(caption)}</li>`).join('')}</ol>` : ''}` : '<p>未设置宣传正文。</p>'}<h4>培训成果</h4>${text(content?.achievements)}<h4>问题与改进</h4>${text(content?.issues)}<h4>下一步计划</h4>${text(content?.nextSteps)}</div>`;
    };
    const validPhotos = photos => {
      if (!Array.isArray(photos) || photos.length > 10 || photos.some(p => !exact(p, ['photo_id', 'caption', 'width', 'height', 'content_type', 'byte_size']) || !/^[a-f0-9]{32}$/.test(p.photo_id) || typeof p.caption !== 'string' || p.caption.length > 500 || !['image/png', 'image/jpeg'].includes(p.content_type) || !Number.isSafeInteger(p.byte_size) || p.byte_size < 1 || p.byte_size > 5 * 1024 * 1024 || !Number.isSafeInteger(p.width) || p.width < 1 || !Number.isSafeInteger(p.height) || p.height < 1) || new Set(photos.map(p => p.photo_id)).size !== photos.length || photos.reduce((sum, p) => sum + p.byte_size, 0) > 12 * 1024 * 1024) throw new Error('总结照片响应不完整');
      return photos;
    };
    const validRevision = (result, number) => {
      const content = result?.content, p = content?.publicity;
      if (!result || result.project_id !== projectId || result.revision !== number || result.read_only !== true || result.synthetic !== false || result.status !== 'DRAFT' || typeof result.saved_at !== 'string' || !content || !['achievements', 'issues', 'nextSteps'].every(key => typeof content[key] === 'string') || (p != null && (typeof p.title !== 'string' || typeof p.introduction !== 'string' || !Array.isArray(p.sections) || !p.sections.every(section => typeof section?.heading === 'string' && typeof section?.body === 'string') || !Array.isArray(p.photoCaptions) || !p.photoCaptions.every(caption => typeof caption === 'string')))) throw new Error('版本响应不完整');
      if (Object.hasOwn(result, 'photos')) validPhotos(result.photos);
      return result;
    };
    const previewHtml = result => `<article data-summary-preview style="overflow-wrap:anywhere"><header><h2>已保存总结预览</h2><p class="modal-intro">草稿 · 第 ${result.revision} 版 · 保存于 ${esc(workflowLocalTime(result.saved_at))}</p><p class="inline-note">本页展示已保存内容；未保存修改不参与，草稿尚未完成正式复核。</p></header>${contentHtml(result.content)}${sourceHtml(result.sources, true, true)}</article>`;
    const deliveryHidden = sources => ['DELIVERY_HIDDEN', 'DELIVERY_AND_FEEDBACK_HIDDEN'].includes(sources?.visibility);
    function comparisonHtml(result) {
      const hidden = deliveryHidden(result.before?.sources) || deliveryHidden(result.after.sources), hideFeedback = feedbackHidden(result.before?.sources) || feedbackHidden(result.after.sources);
      const sourceStatus = value => ({ AVAILABLE: '可用', UNAVAILABLE: '不可用' })[value] || '未接入';
      const flatten = revision => {
        const map = new Map(); if (!revision) return map;
        const put = (key, label, value) => map.set(key, { label, value });
        const content = revision.content, p = content.publicity;
        for (const [key, label] of [['achievements', '培训成果'], ['issues', '问题与改进'], ['nextSteps', '下一步计划']]) put('content.' + key, label, content[key]);
        put('publicity', '宣传正文结构', p ? '已设置' : '未设置');
        if (p) {
          put('publicity.title', '总结标题', p.title); put('publicity.introduction', '项目介绍与引言', p.introduction);
          put('publicity.sections.count', '章节数量（按位置对照）', p.sections.length);
          p.sections.forEach((section, index) => { put('sections.' + index + '.heading', `位置 ${index + 1} · 章节标题`, section.heading); put('sections.' + index + '.body', `位置 ${index + 1} · 章节正文`, section.body); });
          put('publicity.captions.count', '文字图注数量（按位置对照）', p.photoCaptions.length);
          p.photoCaptions.forEach((caption, index) => put('captions.' + index, `位置 ${index + 1} · 文字图注`, caption));
        }
        const sources = revision.sources;
        put('source.project.status', '项目来源状态', sourceStatus(sources?.project?.status));
        for (const [key, label] of [['start_date', '项目开始日期'], ['end_date', '项目结束日期'], ['participant_count', '项目人数'], ['project_status', '项目状态']]) put('source.project.' + key, label, sources?.project?.status === 'AVAILABLE' ? sources.project.value?.[key] ?? null : null);
        put('source.feedback.status', '评价来源状态', hideFeedback ? '按当前权限隐藏' : sourceStatus(sources?.feedback?.status));
        put('source.delivery.status', '授课来源状态', hidden ? '按当前权限隐藏' : sourceStatus(sources?.delivery?.status));
        if (!hidden && sources?.delivery?.status === 'AVAILABLE' && sources.delivery.value) {
          const values = sources.delivery.value;
          for (const [key, label] of [['estimated', '预计课时'], ['planned', '计划课时'], ['actual', '已核对实际课时'], ['payable', '计酬课时']]) put('source.delivery.' + key, label, typeof values[key] === 'string' && /^\d+(?:\.\d+)?$/.test(values[key]) ? values[key] : null);
          for (const [key, label] of [['dispatch_count', '有效排课数'], ['reviewed_completed_count', '已完成且核对数'], ['pending_count', '未完成数'], ['unverified_completed_count', '已完成待核实数'], ['rejected_count', '已拒绝数'], ['excluded_count', '未计入已核对授课数']]) if (Object.hasOwn(values, key)) put('source.delivery.' + key, label, Number.isSafeInteger(values[key]) && values[key] >= 0 ? values[key] : null);
        }
        return map;
      };
      const before = flatten(result.before), after = flatten(result.after), keys = [...new Set([...before.keys(), ...after.keys()])];
      const value = item => item === undefined ? '未设置' : item === null ? '未记录' : item === '' ? '（空白）' : String(item);
      const rows = keys.filter(key => JSON.stringify(before.get(key)?.value) !== JSON.stringify(after.get(key)?.value)).map(key => ({ label: (after.get(key) || before.get(key)).label, before: before.get(key)?.value, after: after.get(key)?.value }));
      return `<article data-summary-comparison><header><h2>已保存版本对照</h2><p class="modal-intro">草稿 · ${result.from_revision ? '第 ' + result.from_revision + ' 版' : '首次保存前（尚无已保存版本）'} → 第 ${result.to_revision} 版 · 最新第 ${result.latest_version} 版</p><p class="modal-intro">${result.before ? `上一版保存于 ${esc(workflowLocalTime(result.before.saved_at))}；` : ''}本版保存于 ${esc(workflowLocalTime(result.after.saved_at))}</p><p class="inline-note">仅对照当前可见字段。章节与图注按位置比较，不推断是否为同一章节；未保存填写不参与。</p>${hidden ? '<p>授课来源在两侧均按当前权限隐藏。</p>' : ''}${hideFeedback ? '<p>评价来源在两侧均按当前权限隐藏。</p>' : ''}</header>${rows.length ? `<div class="table-wrap"><table class="tbl"><thead><tr><th scope="col">项目</th><th scope="col">${result.from_revision ? '上一版' : '首次保存前（尚无已保存版本）'}</th><th scope="col">本版</th></tr></thead><tbody>${rows.map(row => `<tr><td data-label="项目">${esc(row.label)}</td><td data-label="${result.from_revision ? '上一版' : '首次保存前（尚无已保存版本）'}" style="white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word">${esc(result.before ? value(row.before) : '首次保存前（尚无已保存版本）')}</td><td data-label="本版" style="white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word">${esc(value(row.after))}</td></tr>`).join('')}</tbody></table></div>` : '<p>当前可见字段未发现变化。</p>'}</article>`;
    }
    const validate = response => {
      if (!response || response.project_id !== projectId || response.synthetic !== false || ![true, false].includes(response.draft_only) || response.capabilities?.read !== true || !Number.isSafeInteger(response.version) || response.version < 0 || (response.current && (!response.current.content || response.current.project_id !== projectId))) throw new Error('总结响应不完整，请重新读取');
      if (response.draft_only === false) {
        validWorkflow(response.workflow, response.version);
        if ((response.version === 0) !== (response.current === null) || !['TEXT_PLACEHOLDERS_ONLY', 'PROJECT_UPLOADS_VERSIONED'].includes(response.photo_policy) || response.workflow.photo_policy !== response.photo_policy || response.revision !== response.version || !nonnegative(response.latest_version) || response.latest_version < response.version || !exact(response.capabilities, ['read', 'edit', 'refresh_sources', 'submit', 'review', 'review_role', 'export']) || Object.keys(response.workflow.capabilities).some(key => response.capabilities[key] !== response.workflow.capabilities[key])) throw new Error('总结与流程版本不一致，请重新读取');
        if (response.current) { validRevision(response.current, response.version); if (response.photo_policy === 'PROJECT_UPLOADS_VERSIONED') validPhotos(response.current.photos); }
      } else if (response.workflow || response.capabilities.submit || response.capabilities.review || response.capabilities.export) throw new Error('草稿响应不能启用正式流程');
      return response;
    };
    const closeOwned = () => { if (owned) { const mask = owned.mask; owned.dispose(); if ($('#modal-mask') === mask) closeModal(true); } };
    addRouteCleanup(() => { disposed = true; lifetime.abort(); loadController?.abort(); view = null; closeOwned(); }, epoch);
    const denied = () => {
      view = null; closeOwned();
      if (current()) root.innerHTML = '<p class="modal-intro" role="status">当前账号没有本项目总结权限，或受理来源暂时无法核实。</p>';
    };
    const panel = () => {
      if (!current() || !view) return;
      root.innerHTML = `<p class="modal-intro">${view.current ? `已保存草稿 · 第 ${view.version} 版 · ${esc(workflowLocalTime(view.current.saved_at))}` : '尚未保存总结草稿，可先填写不完整的正文。'}${view.source_changed ? '<br>业务来源已变化，当前草稿仍保留上次冻结来源。' : ''}</p><div class="toolbar"><button type="button" class="btn gray" data-summary-open>${icon('file-pen-line')}${view.capabilities.edit ? '填写总结草稿' : '查看总结草稿'}</button><button type="button" class="btn gray" data-summary-preview-open ${view.version ? '' : 'disabled'}>预览已保存版</button><button type="button" class="btn gray" data-summary-history ${view.version ? '' : 'disabled'}>版本历史</button></div><p class="modal-intro">${view.sources?.delivery?.status === 'AVAILABLE' ? '已带入授课来源，可在草稿中查看；后续更正需刷新业务来源。' : '授课来源状态可在草稿中查看。'} ${view.workflow ? '流程：' + esc(flowLabel(view.workflow.status, view.workflow.review_mode)) + '。打开总结查看当前可办理操作。' : '当前服务仅提供草稿保存与版本查阅。'}</p>`;
      $('[data-summary-open]', root).onclick = () => open(false);
      $('[data-summary-history]', root).onclick = () => open(true);
      $('[data-summary-preview-open]', root).onclick = () => { if (view?.version) return open(false, true); };
      refreshIcons(root);
    };
    const reload = async () => {
      if (!current()) return;
      const ticket = ++loadTicket; loadController?.abort(); const request = new AbortController(); loadController = request;
      root.innerHTML = '<p class="modal-intro" role="status">正在读取总结草稿…</p>';
      try {
        const response = await api('/training-summaries?' + new URLSearchParams({ project_id: projectId }), { quiet: true, signal: request.signal });
        if (!current() || request.signal.aborted || ticket !== loadTicket) return;
        view = validate(response); panel();
      } catch (error) {
        if (!current() || request.signal.aborted || ticket !== loadTicket || error?.name === 'AbortError') return;
        if (error.status === 403 || error.code === 403) { denied(); return; }
        view = null; root.innerHTML = `<p class="modal-intro" role="status">总结草稿暂时无法读取：${esc(error.message || '请稍后重试')}</p><button type="button" class="btn gray" data-summary-reload>重新读取</button>`;
        $('[data-summary-reload]', root).onclick = reload;
      }
    };
    async function open(showHistory, previewSaved = false) {
      if (!current()) return;
      const controller = new AbortController();
      let photoDraft = [], photoBaseline = '[]'; const newlyUploaded = new Set(), photoGroups = new Map();
      let reviewNote = '', workflowHistoryTicket = 0, workflowHistoryController = null;
      const objectUrls = new Set();
      let snapshot = null, draft = empty(), baseline = '', fields = [], pending = null, conflict = null, busy = false, ready = false, ended = false, historyTicket = 0, revisionTicket = 0, historyController = null, revisionController = null, viewerMode = false, viewerBusy = false;
      const mask = openModal('培训总结', '<p id="summary-intro" class="modal-intro">正文保存后生成不可变版本；正式状态与复核进度单独记录。图注只接受文字，暂不接入照片文件。</p><div id="summary-notice" class="modal-intro" role="status">正在读取…</div><div id="summary-discard" role="status"></div><div id="summary-view-tools"></div><div id="summary-sources"></div><div id="summary-editor"></div><div id="summary-formal"></div><div id="summary-flow-history"></div><div id="summary-conflict"></div><div id="summary-history"></div><div id="summary-revision"></div>', { noFoot: true, wide: true, onClose: () => dispose() });
      if (!mask) return;
      const node = name => $('#summary-' + name, mask);
      const active = () => !ended && !controller.signal.aborted && current() && $('#modal-mask') === mask;
      const photoReferences = () => photoDraft.map(({ photo_id, caption }) => ({ photo_id, caption }));
      const dirty = () => ready && (JSON.stringify(readDraft()) !== baseline || JSON.stringify(photoReferences()) !== photoBaseline);
      const beforeClose = () => {
        if (!current() || ended) return true;
        if (busy) { node('notice').textContent = '正在保存，请等待结果后再关闭。'; return false; }
        if (!dirty() && !pending && !reviewNote) return true;
        node('discard').innerHTML = '<p class="inline-note">' + (pending ? '上次操作结果尚未确认，关闭后请重新打开核对已保存版本。' : '总结有未保存的修改。') + '可继续编辑，或放弃本地修改并关闭总结；关闭后请重新选择要前往的入口。</p><div class="toolbar"><button type="button" class="btn" data-summary-stay>继续编辑</button><button type="button" class="btn gray" data-summary-discard>放弃并关闭总结</button></div>';
        $('[data-summary-stay]', mask).onclick = () => { node('discard').innerHTML = ''; };
        $('[data-summary-discard]', mask).onclick = () => { if (active() && !busy) closeModal(true); };
        node('discard').scrollIntoView({ block: 'nearest' }); $('[data-summary-stay]', mask).focus();
        return false;
      };
      mask._beforeClose = beforeClose;
      const beforeUnload = event => { if (active() && (busy || pending || dirty() || reviewNote)) { event.preventDefault(); event.returnValue = ''; } };
      window.addEventListener('beforeunload', beforeUnload);
      function dispose() {
        if (ended) return;
        ended = true; controller.abort(); [...photoGroups.keys()].forEach(clearPhotoGroup); photoDraft = []; newlyUploaded.clear(); workflowHistoryController?.abort(); workflowHistoryTicket++; objectUrls.forEach(url => URL.revokeObjectURL(url)); objectUrls.clear(); reviewNote = ''; historyController?.abort(); revisionController?.abort(); historyTicket++; revisionTicket++;
        window.removeEventListener?.('beforeunload', beforeUnload);
        mask._beforeClose = null; mask.dataset.locked = 'false'; draft = empty(); pending = null; conflict = null; snapshot = null;
        if (owned?.mask === mask) owned = null;
      }
      owned = { mask, dispose };
      const field = (key, label, max = 12000, type = 'textarea') => ({ k: key, label, type, span2: true, maxLength: max });
      function readDraft() {
        if (!ready) return draft;
        const values = {};
        fields.forEach(item => { const el = $(`[data-k="${item.k}"]`, mask); values[item.k] = el ? el.value : ''; });
        const content = { achievements: values.achievements, issues: values.issues, nextSteps: values.nextSteps };
        if (draft.publicity) content.publicity = { title: values.publicityTitle, introduction: values.publicityIntroduction,
          sections: draft.publicity.sections.map((_, i) => ({ heading: values['heading' + i], body: values['body' + i] })), photoCaptions: draft.publicity.photoCaptions.map((_, i) => values['caption' + i]) };
        return content;
      }
      function viewerLayout(focused) {
        viewerMode = focused;
        for (const name of ['intro', 'notice', 'sources', 'editor', 'history', 'conflict', 'formal', 'flow-history']) node(name).hidden = focused;
        node('view-tools').innerHTML = focused ? '<div class="toolbar"><button type="button" class="btn gray" data-summary-return>返回草稿</button><button type="button" class="btn gray" data-summary-view-close>关闭</button></div>' : '';
        if (focused) {
          $('[data-summary-return]', mask).onclick = () => { if (active()) clearViewer(); };
          $('[data-summary-view-close]', mask).onclick = () => closeModal();
        }
      }
      function clearViewer() {
        [...photoGroups.keys()].filter(key => key !== 'editor').forEach(clearPhotoGroup);
        revisionController?.abort(); revisionTicket++; viewerBusy = false; node('revision').innerHTML = ''; viewerLayout(false); updateButtons();
      }
      function clearReads() {
        clearViewer(); workflowHistoryController?.abort(); workflowHistoryTicket++; node('flow-history').innerHTML = ''; historyController?.abort(); historyTicket++; node('history').innerHTML = '';
      }
      function updateButtons() {
        const editable = snapshot?.capabilities?.edit === true;
        $$('input, textarea, select', node('editor')).forEach(el => { el.disabled = busy || !editable; });
        $$('[data-summary-edit]', node('editor')).forEach(el => { el.disabled = busy || !editable; });
        const save = $('[data-summary-save]', mask), refresh = $('[data-summary-refresh]', mask), retry = $('[data-summary-retry]', mask);
        if (save) save.disabled = busy || !editable || Boolean(pending || conflict);
        if (refresh) refresh.disabled = busy || snapshot?.capabilities?.refresh_sources !== true || Boolean(pending || conflict);
        if (retry) retry.disabled = busy || !pending;
        const addSection = $('[data-summary-add-section]', mask), addCaption = $('[data-summary-add-caption]', mask);
        if (addSection) addSection.disabled = busy || !editable || draft.publicity.sections.length >= 12;
        if (addCaption) addCaption.disabled = busy || !editable || draft.publicity.photoCaptions.length >= 6;
        const preview = $('[data-summary-preview]', mask), history = $('[data-summary-show-history]', mask);
        if (preview) preview.disabled = busy || viewerBusy || !!pending || !snapshot?.version;
        if (history) history.disabled = busy || viewerBusy || !!pending;
        $$('[data-summary-photo-edit]', mask).forEach(el => { el.disabled = busy || !!pending || !!conflict || snapshot?.capabilities.edit !== true || el.dataset.photoLimit === 'true'; });
        const blocked = busy || viewerBusy || Boolean(pending || conflict) || dirty();
        for (const key of ['submit', 'approve', 'review-return', 'export']) { const el = $('[data-summary-' + key + ']', mask); if (el) el.disabled = blocked; }
        const flowHistory = $('[data-summary-flow-history]', mask); if (flowHistory) flowHistory.disabled = busy || Boolean(pending);
        const note = $('[data-summary-review-note]', mask); if (note) note.disabled = busy || Boolean(pending);
        mask.dataset.locked = busy ? 'true' : 'false';
      }
      function renderEditor() {
        if (!active()) return;
        const editable = snapshot.capabilities.edit === true;
        const legacy = [field('achievements', '培训成果'), field('issues', '问题与改进'), field('nextSteps', '下一步计划')];
        fields = [...legacy];
        const values = { achievements: draft.achievements, issues: draft.issues, nextSteps: draft.nextSteps };
        let publicity = '';
        if (draft.publicity) {
          const p = draft.publicity, intro = [field('publicityTitle', '总结标题', 300, 'text'), field('publicityIntroduction', '项目介绍与引言')];
          fields.push(...intro); values.publicityTitle = p.title; values.publicityIntroduction = p.introduction;
          publicity = renderForm(intro, values) + p.sections.map((section, i) => {
            const sectionFields = [field('heading' + i, `章节 ${i + 1} 标题`, 100, 'text'), field('body' + i, `章节 ${i + 1} 正文`)];
            fields.push(...sectionFields); values['heading' + i] = section.heading; values['body' + i] = section.body;
            return `<section>${renderForm(sectionFields, values)}${editable ? `<button type="button" class="btn gray sm" data-summary-edit data-summary-remove-section="${i}">移除本章节</button>` : ''}</section>`;
          }).join('') + (editable ? `<div class="toolbar"><button type="button" class="btn gray" data-summary-edit data-summary-add-section ${p.sections.length >= 12 ? 'disabled' : ''}>添加章节</button></div>` : '')
            + '<p class="modal-intro">照片位置仅为文字图注，最多六处。</p>' + p.photoCaptions.map((caption, i) => {
              const item = field('caption' + i, `照片 ${i + 1} 图注`, 300, 'text'); fields.push(item); values[item.k] = caption;
              return `${renderForm([item], values)}${editable ? `<button type="button" class="btn gray sm" data-summary-edit data-summary-remove-caption="${i}">移除本图注</button>` : ''}`;
            }).join('') + (editable ? '<div class="toolbar"><button type="button" class="btn gray" data-summary-edit data-summary-add-caption>添加文字图注</button></div>' : '');
        } else publicity = `<p class="modal-intro">当前草稿未设置宣传正文，原有三项正文将完整保留。</p>${editable ? '<button type="button" class="btn gray" data-summary-edit data-summary-add-publicity>添加宣传正文</button>' : ''}`;
        node('editor').innerHTML = `<p class="modal-intro">${editable ? '当前编辑' : '只读查看'} · ${snapshot.version ? '已保存第 ' + snapshot.version + ' 版' : '尚未保存'}。草稿各项均可留空。</p>${publicity}<details open><summary>培训成果、问题与下一步计划</summary>${renderForm(legacy, values)}</details><div class="toolbar">${editable ? '<button type="button" class="btn" data-summary-save>保存草稿</button><button type="button" class="btn gray" data-summary-refresh>刷新业务来源</button>' : ''}<button type="button" class="btn gray" data-summary-preview>预览已保存版</button><button type="button" class="btn gray" data-summary-show-history>版本历史</button><button type="button" class="btn gray" data-summary-close>${editable ? '取消 / 关闭' : '关闭'}</button><button type="button" class="btn gray" data-summary-retry>重试上次请求</button></div><p class="modal-intro">刷新来源只使用已保存正文；请先保存或取消未保存修改。</p><div data-summary-photo-slot>照片位置当前仅保存文字图注。</div>`;
        fields.forEach(item => { const el = $(`[data-k="${item.k}"]`, mask); el.setAttribute('maxlength', item.maxLength); el.oninput = () => { if (active()) { node('notice').textContent = '正文有未保存的修改。'; updateButtons(); } }; });
        const editStructure = change => { if (!active() || busy || !editable) return; draft = readDraft(); change(draft); renderEditor(); node('notice').textContent = '正文有未保存的修改。'; };
        const bind = (selector, callback) => { const el = $(selector, mask); if (el) el.onclick = callback; };
        bind('[data-summary-add-publicity]', () => editStructure(value => { value.publicity = { title: '', introduction: '', sections: [], photoCaptions: [] }; }));
        bind('[data-summary-add-section]', () => editStructure(value => { if (value.publicity.sections.length < 12) value.publicity.sections.push({ heading: '', body: '' }); }));
        bind('[data-summary-add-caption]', () => editStructure(value => { if (value.publicity.photoCaptions.length < 6) value.publicity.photoCaptions.push(''); }));
        $$('[data-summary-remove-section]', mask).forEach(el => { el.onclick = () => editStructure(value => value.publicity.sections.splice(Number(el.dataset.summaryRemoveSection), 1)); });
        $$('[data-summary-remove-caption]', mask).forEach(el => { el.onclick = () => editStructure(value => value.publicity.photoCaptions.splice(Number(el.dataset.summaryRemoveCaption), 1)); });
        bind('[data-summary-save]', () => mutate('save'));
        bind('[data-summary-refresh]', () => mutate('refresh'));
        bind('[data-summary-retry]', () => pending?.photo ? uploadPhoto(null, true) : pending?.formal ? formalAction(null, true) : mutate(null, true));
        bind('[data-summary-close]', () => closeModal());
        bind('[data-summary-show-history]', () => historyPage(0));
        bind('[data-summary-preview]', () => revision(snapshot.version, true));
        renderFormal(); renderPhotos(); updateButtons(); refreshIcons(mask);
      }
      function clearPhotoGroup(key) {
        const group = photoGroups.get(key); if (!group) return;
        group.controller.abort(); group.urls.forEach(url => URL.revokeObjectURL(url)); photoGroups.delete(key);
      }
      async function loadPhotoGroup(rootNode, photos, revisionFor, key) {
        clearPhotoGroup(key); const group = { controller: new AbortController(), urls: new Set() }; photoGroups.set(key, group);
        const alive = () => active() && photoGroups.get(key) === group && !group.controller.signal.aborted;
        await Promise.all(photos.map(async (photo, index) => {
          const target = $('[data-photo-preview="' + index + '"]', rootNode); if (!target) return;
          try {
            const response = await fetch('/api/training-summaries/photos/content?' + new URLSearchParams({ project_id: projectId, photo_id: photo.photo_id, revision: revisionFor(photo) }), { credentials: 'same-origin', cache: 'no-store', headers: { Accept: photo.content_type }, signal: group.controller.signal });
            if (state.user !== identity || String(state.projectId) !== String(projectId)) { closeOwned(); if (root.isConnected) root.innerHTML = ''; }
            if (!alive()) return;
            if (response.status === 401) { localStorage.removeItem('token'); invalidateSession(); return; }
            if (response.status === 403) { denied(); return; }
            const expectedName = 'photo-' + photo.photo_id + (photo.content_type === 'image/png' ? '.png' : '.jpg');
            if (response.status !== 200 || response.redirected || response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase() !== photo.content_type || response.headers.get('Content-Disposition') !== 'attachment; filename="' + expectedName + '"' || !/(?:^|,)\s*no-store\s*(?:,|$)/i.test(response.headers.get('Cache-Control') || '')) throw new Error('图片暂不可预览');
            const declared = response.headers.get('Content-Length'); if (declared && (!/^\d+$/.test(declared) || Number(declared) !== photo.byte_size)) throw new Error('图片大小不一致');
            const reader = response.body?.getReader(); if (!reader) throw new Error('图片内容无法读取');
            let size = 0; const chunks = [];
            try { while (true) { const chunk = await reader.read(); if (!alive()) throw Object.assign(new Error('已取消'), { name: 'AbortError' }); if (chunk.done) break; size += chunk.value.byteLength; if (size > 5 * 1024 * 1024 || size > photo.byte_size) throw new Error('图片大小无效'); chunks.push(chunk.value); } }
            catch (error) { await reader.cancel(); throw error; } finally { reader.releaseLock(); }
            if (size !== photo.byte_size) throw new Error('图片内容不完整');
            const blob = new Blob(chunks, { type: photo.content_type }), signature = new Uint8Array(await blob.slice(0, 8).arrayBuffer()); if (!alive()) return;
            if (photo.content_type === 'image/png' ? signature.length < 8 || ![137,80,78,71,13,10,26,10].every((v,i) => signature[i] === v) : signature.length < 3 || signature[0] !== 255 || signature[1] !== 216 || signature[2] !== 255) throw new Error('图片格式不正确');
            const url = URL.createObjectURL(blob); group.urls.add(url);
            target.innerHTML = '<img alt="总结照片 ' + (index + 1) + '" style="display:block;max-width:100%;max-height:280px;object-fit:contain">'; $('img', target).src = url;
          } catch (error) { if (alive() && error.name !== 'AbortError') target.textContent = '图片暂不可预览；可重新打开总结核对权限与版本。'; }
        }));
      }
      function savedPhotosHtml(result, key) {
        if (!result?.photos?.length) return '';
        return `<section data-saved-photo-group="${key}"><h3>第 ${result.revision} 版照片</h3>${result.photos.map((photo,index) => `<figure><div data-photo-preview="${index}">正在读取授权图片…</div><figcaption style="white-space:pre-wrap;overflow-wrap:anywhere">${esc(photo.caption || '未填写图注')}</figcaption></figure>`).join('')}</section>`;
      }
      function loadSavedPhotos(result, key) {
        const target = $('[data-saved-photo-group="' + key + '"]', node('revision'));
        if (target) void loadPhotoGroup(target, result.photos, () => result.revision, key);
      }
      function renderPhotos() {
        const slot = $('[data-summary-photo-slot]', mask); if (!slot) return;
        clearPhotoGroup('editor');
        if (snapshot.photo_policy !== 'PROJECT_UPLOADS_VERSIONED') return;
        node('intro').textContent = '正文与照片引用共同保存为不可变版本。上传后请保存正文；复核中不能更改，已批准版变更后需要重新复核。原宣传正文中的文字图位仍独立保留。';
        const editable = snapshot.capabilities.edit === true;
        slot.innerHTML = `<section><h3>项目总结照片</h3><p>最多 10 张 JPEG / PNG，单张最多 5 MiB，保存版合计最多 12 MiB。上传只建立待保存照片，图注、顺序和移除操作须保存总结后生效；旧版本保留原照片。</p>${editable ? `<label>选择照片<input type="file" accept="image/jpeg,image/png" data-summary-photo-edit data-summary-photo-file ${photoDraft.length >= 10 ? 'data-photo-limit="true" disabled' : ''}></label><button type="button" class="btn gray" data-summary-photo-edit data-summary-photo-upload ${photoDraft.length >= 10 ? 'data-photo-limit="true" disabled' : ''}>上传所选照片</button>` : ''}<div data-summary-photo-list>${photoDraft.map((photo,index) => `<figure><div data-photo-preview="${index}">正在读取授权图片…</div>${editable ? `<label>照片 ${index + 1} 图注<textarea maxlength="500" data-summary-photo-edit data-photo-caption="${index}">${esc(photo.caption)}</textarea></label><div class="toolbar"><button type="button" class="btn gray" data-summary-photo-edit data-photo-up="${index}" ${index === 0 ? 'data-photo-limit="true" disabled' : ''}>上移</button><button type="button" class="btn gray" data-summary-photo-edit data-photo-down="${index}" ${index === photoDraft.length-1 ? 'data-photo-limit="true" disabled' : ''}>下移</button><button type="button" class="btn gray" data-summary-photo-edit data-photo-remove="${index}">移除本版照片</button></div>` : `<figcaption style="white-space:pre-wrap;overflow-wrap:anywhere">${esc(photo.caption || '未填写图注')}</figcaption>`}</figure>`).join('')}</div>${photoDraft.length ? '' : '<p>本版尚无实际照片。</p>'}</section>`;
        const canEdit = () => active() && !busy && !pending && !conflict && snapshot.capabilities.edit === true;
        const upload = $('[data-summary-photo-upload]', slot); if (upload) upload.onclick = () => uploadPhoto($('[data-summary-photo-file]', slot)?.files?.[0]);
        $$('[data-photo-caption]', slot).forEach(el => { const index = Number(el.dataset.photoCaption); el.value = photoDraft[index].caption; el.oninput = () => { if (!canEdit()) return; photoDraft[index].caption = el.value; node('notice').textContent = '照片图注尚未保存。'; updateButtons(); }; });
        const change = action => { if (!canEdit()) return; action(); renderPhotos(); updateButtons(); node('notice').textContent = '照片变更尚未保存。'; };
        $$('[data-photo-remove]', slot).forEach(el => { el.onclick = () => change(() => photoDraft.splice(Number(el.dataset.photoRemove),1)); });
        for (const [attr, delta] of [['photoUp', -1], ['photoDown', 1]]) $$('[data-' + (delta < 0 ? 'photo-up' : 'photo-down') + ']', slot).forEach(el => { el.onclick = () => change(() => { const index = Number(el.dataset[attr]), next = index + delta; if (next >= 0 && next < photoDraft.length) [photoDraft[index],photoDraft[next]] = [photoDraft[next],photoDraft[index]]; }); });
        void loadPhotoGroup(slot, photoDraft, photo => newlyUploaded.has(photo.photo_id) ? 0 : snapshot.version, 'editor');
      }
      async function uploadPhoto(file, retry = false) {
        if (!active() || busy || !ready || snapshot.photo_policy !== 'PROJECT_UPLOADS_VERSIONED' || snapshot.capabilities.edit !== true) return;
        if (!retry && (pending || conflict)) return;
        if (!retry && (!file || !['image/jpeg', 'image/png'].includes(file.type) || !Number.isSafeInteger(file.size) || file.size <= 0 || file.size > 5 * 1024 * 1024 || photoDraft.length >= 10 || photoDraft.reduce((sum,p) => sum+p.byte_size,0) + file.size > 12*1024*1024)) { node('notice').textContent = '请选择符合张数和大小限制的 JPEG 或 PNG 照片。'; return; }
        busy = true; updateButtons(); node('notice').textContent = '正在上传照片…';
        try {
          if (!retry) {
            const data = new Uint8Array(await file.arrayBuffer()); if (!active()) return;
            if (data.byteLength !== file.size || (file.type === 'image/png' ? data.length < 8 || ![137,80,78,71,13,10,26,10].every((v,i) => data[i] === v) : data.length < 3 || data[0] !== 255 || data[1] !== 216 || data[2] !== 255)) throw Object.assign(new Error('文件内容与图片格式不符。'), { status:400 });
            let binary = ''; for (let offset=0;offset<data.length;offset+=8192) binary += String.fromCharCode(...data.subarray(offset,offset+8192));
            pending = { photo:true, operation:'photos/upload', body:{project_id:projectId,expected_version:snapshot.version,request_id:crypto.randomUUID(),content_type:file.type,data_base64:btoa(binary)} };
          }
          if (!pending?.photo) return;
          const response = await call('/training-summaries/photos/upload',{body:pending.body});
          if (!exact(response,['photo_id','project_id','width','height','content_type','byte_size','uploaded_at','replayed']) || response.project_id !== projectId || typeof response.uploaded_at !== 'string' || typeof response.replayed !== 'boolean') throw new Error('上传结果不完整');
          const photo={photo_id:response.photo_id,caption:'',width:response.width,height:response.height,content_type:response.content_type,byte_size:response.byte_size};validPhotos([photo]);
          if (photo.content_type !== pending.body.content_type) throw new Error('上传结果图片格式不一致');
          if (!photoDraft.some(item=>item.photo_id===photo.photo_id)) { if (photoDraft.reduce((sum,p)=>sum+p.byte_size,0)+photo.byte_size>12*1024*1024) throw Object.assign(new Error('规范化后的照片合计超过 12 MiB，未加入本版；请减少照片后再上传。'), {status:400}); validPhotos([...photoDraft,photo]);photoDraft.push(photo); }
          newlyUploaded.add(photo.photo_id);pending=null;renderPhotos();node('notice').textContent='照片已上传，尚未保存到总结版本。请填写图注并保存总结。';
        } catch (error) {
          if (!active() || error.name === 'AbortError') return;
          const status=error.status||error.code;
          if (status===409) {pending=null;await loadLatestConflict();node('notice').textContent='上传时版本已变化，本地正文与照片引用已保留，请核对当前版本。';}
          else if (status && status<500) {pending=null;node('notice').textContent=error.message||'照片上传未完成。';}
          else node('notice').textContent=pending?.photo ? '照片上传结果尚未确认。请点击“重试上次请求”核实同一次上传。' : '所选照片无法读取，请重新选择后上传。';
        } finally {if(active()){busy=false;updateButtons();}}
      }

      function renderFormal() {
        const flow = snapshot.workflow;
        if (!flow) { node('formal').innerHTML = '<p>当前服务仅提供草稿保存与版本查阅。</p>'; return; }
        const cap = flow.capabilities;
        const currentReview = flow.submitted_revision === flow.revision;
        node('formal').innerHTML = `<section><h3>正式总结流程</h3><p data-summary-flow-status>${esc(flowLabel(flow.status, flow.review_mode))} · 正文第 ${flow.revision} 版 · 流程第 ${flow.workflow_version} 版</p><p>${flow.policy_configured ? flow.review_mode === 'SINGLE_EXPLICIT' ? '由同一兼任人员一次明确完成负责人和 BP 两项复核。' : flow.review_order === 'BRANCH_THEN_BP' ? '分公司负责人通过后，由 BP 复核。' : '按服务器指定的两方岗位复核。' : '复核规则尚未配置，可先保存正文。'}${flow.status === 'RETURNED' ? ' 请查看退回意见，保存新修订后再提交。' : ''}</p>${flow.review_blocked_reason ? `<p class="inline-note" role="status">${esc(flow.review_blocked_reason)}</p>` : ''}${flow.decisions.length && !currentReview ? `<p class="inline-note" data-summary-historical-review><b>以下为第 ${esc(flow.submitted_revision ?? '待核实')} 版历史复核，本版须重新复核。</b></p>` : ''}${flow.decisions.map(d => `<p data-summary-decision>${currentReview ? '' : '历史记录 · '}${d.review_role === 'COMBINED' ? '负责人及BP（兼任）：同一人员同时完成两岗位复核' : roleLabel(d.review_role) + '已通过'} · ${esc(workflowLocalTime(d.reviewed_at))}</p>`).join('')}${cap.review ? `<label>复核意见（退回必填，最多 4000 字）<textarea data-summary-review-note maxlength="4000">${esc(reviewNote)}</textarea></label><p>本次办理岗位：${roleLabel(cap.review_role)}</p>` : ''}<div class="toolbar">${cap.submit ? '<button type="button" class="btn" data-summary-submit>提交两方复核</button>' : ''}${cap.review ? `<button type="button" class="btn" data-summary-approve>${cap.review_role === 'COMBINED' ? '同时完成两岗位复核' : '复核通过'}</button><button type="button" class="btn gray" data-summary-review-return>退回修改</button>` : ''}${cap.export && identity?.role === 'admin' ? '<button type="button" class="btn" data-summary-export>下载正式 Word</button>' : ''}<button type="button" class="btn gray" data-summary-flow-history>流程历史</button></div><p>提交和导出使用当前已保存正文。请先保存或取消本地修改。正式 Word 仅由有导出权限的管理员生成。</p></section>`;
        const note = $('[data-summary-review-note]', mask);
        if (note) { note.value = reviewNote; note.oninput = () => { if (active()) reviewNote = note.value; }; }
        for (const [key, operation] of [['submit', 'submit'], ['approve', cap.review_role === 'COMBINED' ? 'APPROVE_COMBINED' : 'APPROVE'], ['review-return', 'RETURN'], ['export', 'export']]) { const el = $('[data-summary-' + key + ']', mask); if (el) el.onclick = () => formalAction(operation); }
        $('[data-summary-flow-history]', mask).onclick = () => workflowHistory(0);
      }
      async function workflowHistory(offset) {
        if (!active() || busy || pending) return;
        const ticket = ++workflowHistoryTicket; workflowHistoryController?.abort(); const request = new AbortController(); workflowHistoryController = request;
        node('flow-history').innerHTML = '<p>正在读取流程历史…</p>';
        try {
          const result = await call('/training-summaries/workflow-history?' + new URLSearchParams({ project_id: projectId, offset, limit: 20 }), { signal: request.signal });
          if (ticket !== workflowHistoryTicket) return;
          const eventValid = row => {
            if (!row || typeof row !== 'object') return false;
            const keys = ['workflow_version', 'revision', 'action', 'actor_code', 'created_at'];
            const review = ['APPROVE', 'APPROVE_COMBINED', 'RETURN'].includes(row.action);
            if (row.action === 'SUBMIT') {
              keys.push('review_order', 'policy_version', 'branch_reviewer_code', 'bp_reviewer_code');
              if (Object.hasOwn(row, 'review_mode')) keys.push('review_mode');
              if (row.review_mode !== undefined && !['DUAL_REVIEW', 'SINGLE_EXPLICIT'].includes(row.review_mode)) return false;
              if ((row.review_mode === 'SINGLE_EXPLICIT') !== (row.review_order === 'COMBINED')) return false;
            } else if (review) {
              keys.push('review_role', 'note');
              if (Object.hasOwn(row, 'responsibilities')) keys.push('responsibilities');
              if (!['BRANCH', 'BP', 'COMBINED'].includes(row.review_role) || typeof row.note !== 'string' || (row.responsibilities === undefined ? row.review_role === 'COMBINED' : !validResponsibilities(row.review_role, row.responsibilities))) return false;
              if (row.review_role === 'COMBINED' ? !['APPROVE_COMBINED', 'RETURN'].includes(row.action) : row.action === 'APPROVE_COMBINED') return false;
            } else if (row.action === 'EXPORT') keys.push('filename'); else return false;
            return exact(row, keys) && nonnegative(row.workflow_version) && row.workflow_version > 0 && nonnegative(row.revision) && typeof row.actor_code === 'string' && typeof row.created_at === 'string';
          };
          if (!exact(result, ['project_id', 'workflow_version', 'items', 'total', 'offset', 'limit', 'read_only']) || result.project_id !== projectId || result.read_only !== true || !nonnegative(result.workflow_version) || !nonnegative(result.total) || result.offset !== offset || result.limit !== 20 || !Array.isArray(result.items) || result.items.length > 20 || !result.items.every(eventValid)) throw new Error('流程历史响应不完整');
          node('flow-history').innerHTML = `<h3>流程历史</h3>${result.items.length ? result.items.map(row => `<article><p>流程第 ${row.workflow_version} 版 · 正文第 ${row.revision} 版 · ${esc(({ SUBMIT: '提交复核', APPROVE: '复核通过', APPROVE_COMBINED: '同时完成两岗位复核', RETURN: '退回修改', EXPORT: '生成 Word' })[row.action])}${row.review_role ? ' · ' + roleLabel(row.review_role) : ''} · ${esc(workflowLocalTime(row.created_at))}</p>${typeof row.note === 'string' ? `<p style="white-space:pre-wrap;overflow-wrap:anywhere">${esc(row.note || '未填写意见')}</p>` : ''}${row.action === 'EXPORT' ? '<p>生成记录不表示文件已完成下载。</p>' : ''}</article>`).join('') : '<p>尚无正式流程记录。</p>'}<div class="toolbar"><button type="button" class="btn gray" data-summary-flow-prev ${offset === 0 ? 'disabled' : ''}>上一页</button><span>共 ${result.total} 条</span><button type="button" class="btn gray" data-summary-flow-next ${offset + result.items.length >= result.total ? 'disabled' : ''}>下一页</button></div>`;
          $('[data-summary-flow-prev]', mask).onclick = () => workflowHistory(Math.max(0, offset - 20));
          $('[data-summary-flow-next]', mask).onclick = () => workflowHistory(offset + 20);
        } catch (error) { if (active() && ticket === workflowHistoryTicket && error.name !== 'AbortError') node('flow-history').textContent = '流程历史暂不可读取：' + error.message; }
      }
      async function downloadWord(request) {
        const mime = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
        const alive = () => { if (state.user !== identity || String(state.projectId) !== String(projectId)) { closeOwned(); if (root.isConnected) root.innerHTML = ''; } if (!active()) throw Object.assign(new Error('已取消'), { name: 'AbortError' }); };
        const response = await fetch('/api/training-summaries/export', { method: 'POST', credentials: 'same-origin', cache: 'no-store', headers: { 'Content-Type': 'application/json', Accept: mime }, body: JSON.stringify(request.body), signal: controller.signal });
        alive();
        if (response.status !== 200) {
          const error = Object.assign(new Error('正式 Word 未生成，请核对当前权限及版本。'), { status: response.status });
          if (response.status === 401) { localStorage.removeItem('token'); invalidateSession(); }
          else if (response.status === 403) denied();
          throw error;
        }
        const filename = `training-summary-${projectId}-v${request.body.expected_version}.docx`;
        if (response.redirected || response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase() !== mime || response.headers.get('Content-Disposition') !== `attachment; filename="${filename}"` || !/(?:^|,)\s*no-store\s*(?:,|$)/i.test(response.headers.get('Cache-Control') || '')) throw new Error('Word 响应格式不正确，未保存文件');
        const max = 16 * 1024 * 1024, length = response.headers.get('Content-Length');
        if (length && (!/^\d+$/.test(length) || Number(length) > max)) throw new Error('Word 文件大小无效，未保存文件');
        const reader = response.body?.getReader(); if (!reader) throw new Error('Word 内容无法读取');
        let size = 0; const chunks = [];
        try { while (true) { const chunk = await reader.read(); alive(); if (chunk.done) break; size += chunk.value.byteLength; if (size > max) throw new Error('Word 文件超过大小限制'); chunks.push(chunk.value); } }
        catch (error) { await reader.cancel(); throw error; } finally { reader.releaseLock(); }
        const file = new Blob(chunks, { type: mime }); const signature = new Uint8Array(await file.slice(0, 4).arrayBuffer()); alive();
        if (signature.length !== 4 || signature[0] !== 80 || signature[1] !== 75 || signature[2] !== 3 || signature[3] !== 4 || (length && Number(length) !== size)) throw new Error('Word 内容校验失败，未保存文件');
        const url = URL.createObjectURL(file); objectUrls.add(url); const link = document.createElement('a'); link.href = url; link.download = filename; link.hidden = true;
        try { document.body.appendChild(link); alive(); link.click(); }
        finally { link.remove(); setTimeout(() => { URL.revokeObjectURL(url); objectUrls.delete(url); }, 1000); }
      }
      async function formalAction(operation, retry = false) {
        if (!active() || busy || !ready || !snapshot.workflow) return;
        if (!retry) {
          if (pending || conflict || dirty()) return;
          const cap = snapshot.workflow.capabilities;
          if (operation === 'submit' && !cap.submit || operation === 'export' && (!cap.export || identity?.role !== 'admin') || ['APPROVE', 'APPROVE_COMBINED', 'RETURN'].includes(operation) && !cap.review) return;
          if (['APPROVE', 'APPROVE_COMBINED', 'RETURN'].includes(operation) && (reviewNote.length > 4000 || operation === 'RETURN' && !reviewNote.trim())) { node('notice').textContent = '退回必须填写意见，复核意见最多 4000 字。'; return; }
          if (['APPROVE', 'APPROVE_COMBINED'].includes(operation) && (operation === 'APPROVE_COMBINED') !== (cap.review_role === 'COMBINED')) return;
          const body = { project_id: projectId, expected_version: snapshot.version, expected_workflow_version: snapshot.workflow.workflow_version, request_id: crypto.randomUUID() };
          if (['APPROVE', 'APPROVE_COMBINED', 'RETURN'].includes(operation)) Object.assign(body, { review_role: cap.review_role, decision: operation, note: reviewNote });
          pending = { formal: true, operation: ['APPROVE', 'APPROVE_COMBINED', 'RETURN'].includes(operation) ? 'review' : operation, body };
        }
        if (!pending?.formal) return;
        draft = readDraft(); clearReads(); busy = true; updateButtons(); const request = pending;
        node('notice').textContent = '正在核对当前正文与流程版本…';
        try {
          if (request.operation === 'export') await downloadWord(request);
          else {
            const result = await call('/training-summaries/' + request.operation, { body: request.body });
            validWorkflow(result, result?.revision, true);
            if (result.revision !== request.body.expected_version && result.replayed !== true) throw new Error('操作与正文版本不一致');
          }
          // Keep the exact request until a fresh detail read confirms current capabilities.
          const latest = validate(await call('/training-summaries?' + new URLSearchParams({ project_id: projectId })));
          pending = null; reviewNote = ''; view = latest; panel();
          if (latest.version !== snapshot.version) { conflictView(latest); node('notice').textContent = '本次操作已确认，服务器另有正文修订。请核对后继续。'; }
          else { snapshot = latest; baseline = JSON.stringify(latest.current?.content || empty()); node('sources').innerHTML = sourceHtml(latest.sources); renderEditor(); node('notice').textContent = request.operation === 'export' ? '正式 Word 已生成下载；流程历史中的生成记录不代表文件已送达。' : '操作已确认：' + flowLabel(latest.workflow.status) + '。'; }
        } catch (error) {
          if (!active() || error.name === 'AbortError') return;
          const status = error.status || error.code;
          if (status === 409) { pending = null; await loadLatestConflict(); node('notice').textContent = '版本或来源已变化，正文和复核意见已保留。请核对当前版本后重新决定。'; }
          else if (status && status < 500) { pending = null; node('notice').textContent = error.message || '本次操作未完成。'; }
          else node('notice').textContent = '本次操作结果尚未确认。请仅点击“重试上次请求”核实同一次操作；正文和意见已保留。';
        } finally { if (active()) { busy = false; updateButtons(); } }
      }

      async function call(path, opts = {}) {
        try {
          const response = await api(path, { ...opts, quiet: true, signal: opts.signal || controller.signal });
          if (state.user !== identity || String(state.projectId) !== String(projectId)) { closeOwned(); if (root.isConnected) root.innerHTML = ''; }
          if (!active() || opts.signal?.aborted) throw Object.assign(new Error('已取消'), { name: 'AbortError' });
          return response;
        } catch (error) {
          if (state.user !== identity || String(state.projectId) !== String(projectId)) { closeOwned(); if (root.isConnected) root.innerHTML = ''; }
          if (([401, 403].includes(error.status) || [401, 403].includes(error.code)) && active()) denied();
          throw error;
        }
      }
      function conflictView(latest) {
        if (!active()) return;
        clearReads(); conflict = latest;
        node('conflict').innerHTML = `<div class="inline-note"><span>服务器已有当前版本，您的正文仍保留。请对照后明确选择，再保存。</span></div><details><summary>查看服务器第 ${latest.version} 版正文</summary>${contentHtml(latest.current?.content)}</details><div class="toolbar"><button type="button" class="btn gray" data-summary-use-latest>载入服务器正文</button><button type="button" class="btn gray" data-summary-keep-local>保留本地正文继续编辑</button></div>`;
        const choose = (useLatest, confirmed = false) => {
          if (!active() || busy) return;
          if (useLatest && dirty() && !confirmed) {
            node('discard').innerHTML = '<p class="inline-note">载入服务器正文将替换本地未保存修改，请确认要采用已显示的服务器版本。</p><div class="toolbar"><button type="button" class="btn" data-summary-stay>继续编辑</button><button type="button" class="btn gray" data-summary-confirm-latest>确认载入服务器正文</button></div>';
            $('[data-summary-stay]', mask).onclick = () => { node('discard').innerHTML = ''; };
            $('[data-summary-confirm-latest]', mask).onclick = () => choose(true, true);
            node('discard').scrollIntoView({ block: 'nearest' }); $('[data-summary-stay]', mask).focus();
            return;
          }
          clearReads(); node('discard').innerHTML = '';
          draft = useLatest ? copy(latest.current?.content || empty()) : readDraft(); if (useLatest) { photoDraft = copy(latest.current?.photos || []); newlyUploaded.clear(); } photoBaseline = JSON.stringify((latest.current?.photos || []).map(({photo_id,caption}) => ({photo_id,caption}))); snapshot = latest; baseline = JSON.stringify(latest.current?.content || empty()); conflict = null; pending = null;
          node('conflict').innerHTML = ''; node('sources').innerHTML = sourceHtml(latest.sources); renderEditor();
          node('notice').textContent = useLatest ? '已载入服务器正文。' : '本地正文已保留；下次保存将基于当前版本生成新修订。';
        };
        $('[data-summary-use-latest]', mask).onclick = () => choose(true);
        $('[data-summary-keep-local]', mask).onclick = () => choose(false);
        updateButtons();
      }
      async function loadLatestConflict() {
        if (!active()) return;
        conflict = {}; updateButtons();
        try {
          const latest = validate(await call('/training-summaries?' + new URLSearchParams({ project_id: projectId })));
          view = latest; panel(); conflictView(latest);
        } catch (error) {
          if (!active() || error?.name === 'AbortError') return;
          node('notice').textContent = '正文已保留。服务器当前版本暂时无法读取，请重新读取后对照；暂不能保存新修订。';
          node('conflict').innerHTML = '<button type="button" class="btn gray" data-summary-read-current>重新读取当前版本</button>';
          $('[data-summary-read-current]', mask).onclick = async event => { if (!active()) return; event.target.disabled = true; await loadLatestConflict(); };
          updateButtons();
        }
      }
      async function mutate(operation, retry = false) {
        if (!active() || busy || !ready || snapshot.capabilities.edit !== true) return;
        draft = readDraft();
        if (!retry) {
          if (pending || conflict) return;
          if (operation === 'refresh' && (snapshot.capabilities.refresh_sources !== true || dirty())) { node('notice').textContent = '请先保存或取消正文修改，再刷新已保存草稿的业务来源。'; return; }
          for (const item of fields) if (($(`[data-k="${item.k}"]`, mask)?.value || '').length > item.maxLength) { node('notice').textContent = `${item.label}超过${item.maxLength}字限制，正文已保留。`; return; }
          const body = { project_id: projectId, expected_version: snapshot.version, request_id: crypto.randomUUID() };
          if (operation === 'save') { if (photoDraft.some(photo => photo.caption.length > 500)) { node('notice').textContent = '照片图注最多 500 字，修改已保留。'; return; } body.content = copy(draft); if (snapshot.photo_policy === 'PROJECT_UPLOADS_VERSIONED') body.photos = photoReferences(); }
          pending = { operation, body };
        }
        if (!pending) return;
        clearReads(); const request = pending; busy = true; updateButtons(); node('notice').textContent = request.operation === 'save' ? '正在保存草稿…' : '正在刷新已保存正文的来源…';
        try {
          const response = validate(await call('/training-summaries/' + request.operation, { body: request.body }));
          pending = null;
          if (response.reload_required === true) {
            node('notice').textContent = '上次操作已成功，但服务器已有后续版本。本地正文未被替换，请核对当前版本。';
            await loadLatestConflict();
          } else {
            const local = readDraft(); if (response.photo_policy === 'PROJECT_UPLOADS_VERSIONED') { photoDraft = copy(response.current?.photos || []); photoBaseline = JSON.stringify(photoReferences()); newlyUploaded.clear(); } snapshot = response; baseline = JSON.stringify(response.current?.content || empty()); draft = local; view = response; panel();
            node('sources').innerHTML = sourceHtml(response.sources); renderEditor();
            node('notice').textContent = `已保存第 ${response.version} 版${response.replayed ? '（已确认上次请求）' : ''}。${dirty() ? '本地还有后续修改，尚未保存。' : ''}`;
            node('history').innerHTML = ''; node('revision').innerHTML = ''; historyController?.abort(); revisionController?.abort(); historyTicket++; revisionTicket++;
          }
        } catch (error) {
          if (!active() || error?.name === 'AbortError') return;
          const status = error.status || error.code;
          if (status === 409) {
            pending = null; conflict = {}; node('notice').textContent = '保存或来源刷新冲突，正文已保留。正在核对服务器版本…';
            await loadLatestConflict();
          } else if (status && status < 500) { pending = null; node('notice').textContent = error.message || '保存未完成，正文已保留。'; }
          else node('notice').textContent = '本次操作结果尚未确认，正文已保留。请点击“重试上次请求”核实同一次操作。';
        } finally { if (active()) { busy = false; updateButtons(); } }
      }
      async function historyPage(offset) {
        if (!active() || busy || pending || viewerBusy) return;
        const ticket = ++historyTicket; historyController?.abort(); historyController = new AbortController();
        node('history').innerHTML = '<p class="modal-intro">正在读取版本历史…</p>';
        try {
          const result = await call('/training-summaries/history?' + new URLSearchParams({ project_id: projectId, offset, limit: 20 }), { signal: historyController.signal });
          if (ticket !== historyTicket) return;
          if (result.project_id !== projectId || result.synthetic !== false || !Array.isArray(result.items)) throw new Error('版本列表响应不完整');
          const rows = result.items.map(row => ({ ...row, id: row.revision }));
          const actions = [{ l: '查看版本', cls: 'gray', onClick: row => revision(row.revision) }, { l: '与上一版对照', cls: 'gray', onClick: row => revision(row.revision, true, true) }];
          node('history').innerHTML = `<div class="section-title"><span>不可变版本历史</span></div>${rows.length ? renderTable([{ k: 'revision', l: '版本' }, { k: 'operation', l: '变更', render: row => esc(({ CREATE: '首次保存', SAVE: '保存正文', REFRESH_SOURCES: '刷新来源' })[row.operation] || '草稿修订') }, { k: 'saved_at', l: '保存时间', render: row => esc(workflowLocalTime(row.saved_at)) }, { k: 'status', l: '状态', render: () => '草稿' }], rows, actions) : '<p class="modal-intro">尚无已保存版本。</p>'}<div class="toolbar"><button type="button" class="btn gray" data-summary-history-prev ${offset === 0 ? 'disabled' : ''}>上一页</button><span>共 ${esc(result.total)} 版</span><button type="button" class="btn gray" data-summary-history-next ${offset + result.items.length >= result.total ? 'disabled' : ''}>下一页</button></div>`;
          bindTableActions(node('history'), rows, actions);
          $('[data-summary-history-prev]', mask).onclick = () => { if (offset > 0) historyPage(Math.max(0, offset - 20)); };
          $('[data-summary-history-next]', mask).onclick = () => { if (offset + result.items.length < result.total) historyPage(offset + 20); };
          refreshIcons(node('history'));
        } catch (error) { if (active() && ticket === historyTicket && error?.name !== 'AbortError') node('history').innerHTML = `<p class="modal-intro">历史版本暂时无法读取：${esc(error.message)}</p>`; }
      }
      async function revision(number, focused = false, compare = false) {
        if (!active() || busy || pending || !ready || !Number.isSafeInteger(number) || number < 1) return;
        clearViewer(); const ticket = ++revisionTicket, editingSnapshot = snapshot;
        revisionController = new AbortController(); viewerBusy = true; viewerLayout(focused); updateButtons();
        node('revision').innerHTML = '<p class="modal-intro">正在读取已保存版本…</p>';
        try {
          const path = compare ? '/training-summaries/comparison?' + new URLSearchParams({ project_id: projectId, from_revision: number - 1, to_revision: number }) : '/training-summaries/revision?' + new URLSearchParams({ project_id: projectId, revision: number });
          const result = await call(path, { signal: revisionController.signal });
          if (ticket !== revisionTicket || snapshot !== editingSnapshot) return;
          if (compare) {
            if (result.project_id !== projectId || result.from_revision !== number - 1 || result.to_revision !== number || result.read_only !== true || result.synthetic !== false || result.draft_only !== true || !Number.isSafeInteger(result.latest_version) || result.latest_version < number || (number === 1 ? result.before !== null : !result.before)) throw new Error('版本对照响应不完整');
            validRevision(result.after, number); if (result.before) validRevision(result.before, number - 1);
            node('revision').innerHTML = comparisonHtml(result) + savedPhotosHtml(result.before, 'before') + savedPhotosHtml(result.after, 'after');
            if (result.before) loadSavedPhotos(result.before, 'before'); loadSavedPhotos(result.after, 'after');
          } else {
            validRevision(result, number);
            node('revision').innerHTML = focused ? previewHtml(result) : `<div class="section-title"><span>第 ${number} 版 · 草稿只读 · ${esc(workflowLocalTime(result.saved_at))}</span></div>${sourceHtml(result.sources)}${contentHtml(result.content)}`;
            node('revision').innerHTML += savedPhotosHtml(result, 'saved'); loadSavedPhotos(result, 'saved');
          }
          if (focused) node('view-tools').scrollIntoView({ block: 'start' });
        } catch (error) { if (active() && ticket === revisionTicket && error?.name !== 'AbortError') node('revision').innerHTML = '<p class="modal-intro">已保存版本暂时无法读取，请返回后重试。</p>'; }
        finally { if (active() && ticket === revisionTicket) { viewerBusy = false; updateButtons(); } }
      }
      try {
        snapshot = validate(await call('/training-summaries?' + new URLSearchParams({ project_id: projectId })));
        photoDraft = copy(snapshot.current?.photos || []); photoBaseline = JSON.stringify(photoReferences()); draft = copy(snapshot.current?.content || empty()); baseline = JSON.stringify(draft); view = snapshot; ready = true; panel();
        node('sources').innerHTML = sourceHtml(snapshot.sources, snapshot.version > 0); renderEditor(); node('notice').textContent = snapshot.source_changed ? '来源已变化。当前仍显示已保存的来源，可在正文保存后显式刷新。' : '草稿与正式复核结果分开保留。';
        if (showHistory) await historyPage(0);
        else if (previewSaved && snapshot.version) await revision(snapshot.version, true);
      } catch (error) { if (active() && error?.name !== 'AbortError') node('notice').textContent = `总结暂时无法读取：${error.message || '请重试'}`; }
    }
    return reload();
  }

  // Insert before mountSurveyPreviewEntry. This entry never loosens its draft adapter.
  function mountSurveyPolicyEntry(c, entry, { epoch, page } = {}) {
    if (!entry || state.user?.role !== 'admin') { entry?.replaceChildren(); return null; }
    const owner = state.user, ownerKey = JSON.stringify(owner);
    let disposed = false, modalScope = null;
    const current = () => !disposed && owner === state.user && owner.role === 'admin' && JSON.stringify(state.user) === ownerKey && isRouteCurrent(epoch, c, page) && entry.isConnected;
    const abortError = () => Object.assign(new Error('已关闭统计规则'), { name: 'AbortError' });
    const release = scope => { scope.controller.abort(); scope.module?.destroy(); scope.module = null; if (modalScope === scope) modalScope = null; };
    const destroy = () => { disposed = true; const scope = modalScope; if (scope) { release(scope); if ($('#modal-mask') === scope.mask) closeModal(true); } };
    addRouteCleanup(destroy, epoch);
    entry.innerHTML = '<button type="button" class="btn gray" data-survey-policy-open>统计规则</button>';
    const open = async () => {
      if (!current()) return;
      const scope = { controller: new AbortController(), module: null, mask: null };
      const mask = openModal('统计规则', '<p class="field-error" data-survey-policy-error role="status"></p><div data-survey-policy-content></div>', {
        noFoot: true, wide: true, onClose: () => release(scope), beforeClose: () => scope.module?.beforeClose() ?? true,
      });
      if (!mask) return;
      scope.mask = mask; modalScope = scope;
      const active = () => current() && !scope.controller.signal.aborted && $('#modal-mask') === mask && mask.isConnected;
      const hideUnavailable = () => {
        if (!active()) return;
        disposed = true; entry.replaceChildren(); release(scope); if ($('#modal-mask') === mask) closeModal(true);
      };
      const scopedApi = async (url, options = {}) => {
        if (!active() || options.signal?.aborted) throw abortError();
        const body = options.body, keys = body && typeof body === 'object' && !Array.isArray(body) ? Object.keys(body).sort().join(',') : '';
        const read = url === '/survey-results/policies' && options.method === 'GET' && body === undefined;
        const publish = url === '/survey-results/policies/publish' && options.method === 'POST' && keys === 'acknowledged,expected_version,organization_code,publication_context'
          && typeof body.organization_code === 'string' && body.organization_code.trim() && body.organization_code.length <= 120
          && (body.expected_version === null || typeof body.expected_version === 'string' && /^[A-Za-z0-9_.:-]{1,96}$/.test(body.expected_version))
          && typeof body.publication_context === 'string' && /^[a-f0-9]{64}$/.test(body.publication_context) && body.acknowledged === true;
        if (!read && !publish) throw new Error('不支持的统计规则操作');
        const result = await api(url, { method: options.method, ...(body === undefined ? {} : { body }), quiet: true, signal: options.signal || scope.controller.signal });
        if (!active() || options.signal?.aborted) throw abortError();
        return result;
      };
      try {
        const { mount } = await import('/modules/survey-results/policy-workspace.js?v=20260924policy1');
        if (!active()) return;
        const mounted = mount($('[data-survey-policy-content]', mask), {
          api: scopedApi, getUser: () => state.user, isCurrent: active, signal: scope.controller.signal,
          onForbidden: hideUnavailable, onUnauthorized: hideUnavailable,
        });
        if (!active()) { mounted.destroy(); return; }
        scope.module = mounted;
        await scope.module.ready;
      } catch (error) {
        if (active() && error?.name !== 'AbortError') $('[data-survey-policy-error]', mask).textContent = '统计规则暂时无法加载，请关闭后重试。';
      }
    };
    $('[data-survey-policy-open]', entry).onclick = open;
    return { open, destroy };
  }

  function mountSurveyFormalEntry(c, entry, { epoch, page, projectId = null, projects = [] } = {}) {
    if (!entry) return null;
    const owner = state.user;
    let disposed = false, modalScope = null;
    const choices = projects.filter(p => Number.isSafeInteger(p?.id) && p.id > 0 && typeof p.title === 'string');
    const current = () => !disposed && owner === state.user && isRouteCurrent(epoch, c, page) && entry.isConnected;
    const abortError = () => Object.assign(new Error('已关闭正式汇总'), { name: 'AbortError' });
    const release = scope => { scope.controller.abort(); scope.module?.destroy(); scope.module = null; if (modalScope === scope) modalScope = null; };
    const destroy = () => { disposed = true; const scope = modalScope; if (scope) { release(scope); if ($('#modal-mask') === scope.mask) closeModal(true); } };
    addRouteCleanup(destroy, epoch);
    entry.innerHTML = `${projectId == null ? `<label>培训项目 <select data-survey-formal-project><option value="">请选择项目</option>${choices.map(p => `<option value="${p.id}">${esc(p.title)}</option>`).join('')}</select></label>` : ''}<button type="button" class="btn gray" data-survey-formal-open${projectId == null ? ' disabled' : ''}>正式汇总与复核</button><p class="modal-intro">按项目查看当前与历史结果；政策及权限齐备后才能保存、独立复核或提交修正版。草案预览另行保留。</p>`;
    const selector = $('[data-survey-formal-project]', entry), button = $('[data-survey-formal-open]', entry);
    if (selector) selector.onchange = () => { if (current()) button.disabled = !choices.some(p => String(p.id) === selector.value); };
    const open = async () => {
      if (!current()) return;
      const selected = projectId == null ? choices.find(p => String(p.id) === selector.value) : choices.find(p => p.id === projectId) || { id: projectId, title: '' };
      if (!selected || !Number.isSafeInteger(selected.id) || selected.id <= 0) return;
      const scope = { controller: new AbortController(), module: null, mask: null };
      const mask = openModal('正式评分汇总与复核', '<p class="field-error" data-survey-formal-error role="status"></p><div data-survey-formal-content></div>', {
        noFoot: true, wide: true, onClose: () => release(scope), beforeClose: () => scope.module?.beforeClose() ?? true,
      });
      if (!mask) return;
      scope.mask = mask; modalScope = scope;
      const active = () => current() && !scope.controller.signal.aborted && $('#modal-mask') === mask && mask.isConnected;
      const scopedApi = async (url, options = {}) => {
        if (!active() || options.signal?.aborted) throw abortError();
        const b = options.body, keys = b && typeof b === 'object' && !Array.isArray(b) ? Object.keys(b).sort().join(',') : '';
        const read = options.method === 'GET' && b === undefined && url === '/survey-results?project_id=' + selected.id;
        const prepare = options.method === 'POST' && url === '/survey-response-imports/prepare' && keys === 'fileName,projectId,reason,replacesId,xlsxBase64' && b.projectId === selected.id;
        const confirm = options.method === 'POST' && url === '/survey-results/confirm' && keys === 'acknowledged,preview_token,project_id,request_id' && b.project_id === selected.id;
        const review = options.method === 'POST' && url === '/survey-results/review' && keys === 'decision,expected_version,import_id,project_id,reason,request_id' && b.project_id === selected.id;
        if (!read && !prepare && !confirm && !review) throw new Error('不支持的正式汇总操作');
        // Reuse the original cookie/JSON transport. The server routes prepare through its existing 7MiB upload boundary.
        const result = await api(url, { method: options.method, ...(b === undefined ? {} : { body: b }), quiet: true, signal: options.signal || scope.controller.signal });
        if (!active() || options.signal?.aborted) throw abortError();
        return result;
      };
      try {
        const { mount } = await import('/modules/survey-results/formal-workspace.js?v=20260924policy1');
        if (!active()) return;
        scope.module = mount($('[data-survey-formal-content]', mask), {
          projectId: selected.id, projectName: selected.title, api: scopedApi, getUser: () => state.user,
          isCurrent: active, signal: scope.controller.signal, confirmDiscard: message => window.confirm(message),
        });
        await scope.module.ready;
      } catch (error) {
        if (active() && error?.name !== 'AbortError') $('[data-survey-formal-error]', mask).textContent = '正式汇总页面暂时无法加载，请关闭后重试；原评分草案预览仍可单独使用。';
      }
    };
    button.onclick = open;
    return { open, destroy };
  }

  function mountSurveyPreviewEntry(c, entry, { epoch, page, projectId = null, onReady } = {}) {
    const controller = new AbortController();
    let disposed = false, modalScope = null;
    const current = () => !disposed && !controller.signal.aborted && isRouteCurrent(epoch, c, page) && entry.isConnected;
    const abortError = () => Object.assign(new Error('已取消评分预览'), { name: 'AbortError' });
    const releaseModal = (scope, forget = true) => {
      scope.controller.abort(); scope.release?.(); scope.release = null;
      if (forget && modalScope === scope) modalScope = null;
    };
    addRouteCleanup(() => {
      disposed = true; controller.abort();
      const scope = modalScope;
      if (scope) { releaseModal(scope); if ($('#modal-mask') === scope.mask) closeModal(); }
    }, epoch);
    const errorText = (error) => ({
      400: '评分预览参数无效，请重新选择文件和项目。',
      403: '当前账号没有评分草案预览权限，或项目来源暂时无法核实。',
      408: '本次处理超时，请稍后重新预览。',
      409: '项目或预览条件已变化，请关闭弹窗后重新打开。',
      413: '所选文件超过预览大小限制，请检查后重试。',
      415: '文件或请求格式不支持，请选择原平台导出的评分 Excel。',
      429: '已有评分预览正在处理，请稍后重试。',
      503: '评分草案预览服务暂时不可用，请稍后重试。',
    }[error?.status || error?.code] || '评分草案预览暂时无法加载，请稍后重试。');
    const inspectConfig = (config) => {
      if (!config || config.synthetic !== false || config.canCommit !== false || config.policyConfirmed !== false || config.historyAvailable !== false
        || (config.ready === true && (!Array.isArray(config.projects) || config.projects.some(item => !Number.isSafeInteger(item?.id) || item.id <= 0 || typeof item.name !== 'string')))) throw new Error('评分草案配置尚未就绪');
      if (config.ready !== true) return '评分草案预览尚未接入。';
      if (!config.projects.length) return '当前没有可预览评分的已受理项目。';
      if (projectId != null && !config.projects.some(item => String(item.id) === String(projectId))) return '当前项目没有评分草案预览权限，或项目来源暂时无法核实。';
      return '';
    };
    const openPreview = async () => {
      if (!current()) return;
      const scope = { controller: new AbortController(), release: null, mask: null };
      const mask = openModal('评分 Excel 汇总草案', '<p class="modal-intro">仅生成当前文件的统计草案，口径待核实；历史重复检查未接入，不保存、覆盖或用于正式评价。</p><p class="field-error" id="survey-preview-notice" role="status"></p><div id="survey-preview-content"></div>', { noFoot: true, wide: true, onClose: () => releaseModal(scope) });
      if (!mask) return;
      scope.mask = mask; modalScope = scope;
      const modalCurrent = () => current() && !scope.controller.signal.aborted && $('#modal-mask') === mask;
      const notice = $('#survey-preview-notice', mask);
      const request = async (url, options = {}) => {
        if (!modalCurrent() || options.signal?.aborted) throw abortError();
        const configRequest = url === '/api/survey-response-imports/config' && options.method === 'GET' && options.body === undefined;
        const previewRequest = url === '/api/survey-response-imports/preview' && options.method === 'POST';
        if (!configRequest && !previewRequest) throw new Error('不支持的评分预览操作');
        notice.textContent = '';
        try {
          const body = previewRequest ? JSON.parse(options.body) : undefined;
          if (previewRequest && (!body || Object.keys(body).sort().join(',') !== 'fileName,projectId,xlsxBase64' || !Number.isSafeInteger(body.projectId) || body.projectId <= 0 || typeof body.fileName !== 'string' || typeof body.xlsxBase64 !== 'string')) throw new Error('评分预览参数不完整');
          const result = await api(url.slice('/api'.length), { method: options.method, body, quiet: true, signal: options.signal || scope.controller.signal });
          if (!modalCurrent() || options.signal?.aborted) throw abortError();
          if (configRequest) {
            const unavailable = inspectConfig(result);
            if (unavailable) { notice.textContent = unavailable; releaseModal(scope, false); throw abortError(); }
            return projectId == null ? result : { ...result, defaultProjectId: result.projects.find(item => String(item.id) === String(projectId)).id };
          }
          if (result?.historyAvailable !== false || result?.duplicateCheck?.status !== 'NOT_CHECKED' || result?.canCommit !== false || result?.policyConfirmed !== false || result?.synthetic !== false) throw new Error('评分草案返回约定不完整');
          return result;
        } catch (error) {
          if (modalCurrent() && !options.signal?.aborted && error?.name !== 'AbortError') {
            notice.textContent = errorText(error);
            if (error.status === 403 || error.code === 403) {
              entry.innerHTML = `<p class="modal-intro" role="status">${esc(errorText(error))}</p>`;
              releaseModal(scope, false);
            }
          }
          throw error;
        }
      };
      try {
        const { mount } = await import('/modules/survey-results/index.js?v=20260924policy1');
        if (!modalCurrent()) return;
        const release = await mount($('#survey-preview-content', mask), { mode: 'live', signal: scope.controller.signal, request });
        if (!modalCurrent()) { release?.(); return; }
        scope.release = release;
      } catch (error) {
        if (modalCurrent() && error?.name !== 'AbortError') notice.textContent = '评分预览组件暂时无法加载，请关闭弹窗后重试。';
      }
    };
    entry.innerHTML = '<p class="modal-intro" role="status">正在核实评分草案预览范围…</p>';
    const ready = (async () => {
      try {
        const config = await api('/survey-response-imports/config', { quiet: true, signal: controller.signal });
        if (!current()) return;
        const unavailable = inspectConfig(config);
        if (unavailable) { entry.innerHTML = `<p class="modal-intro" role="status">${esc(unavailable)}</p>`; return; }
        entry.innerHTML = `<button type="button" class="btn gray" data-survey-preview>${icon('file-spreadsheet')}导入评分 Excel</button><p class="modal-intro">仅预览草案 · 口径待核实 · 历史重复未检查 · 不保存正式结果</p>`;
        $('[data-survey-preview]', entry).onclick = openPreview;
        onReady?.(); refreshIcons(entry);
      } catch (error) {
        if (!current() || error?.name === 'AbortError') return;
        entry.innerHTML = `<p class="modal-intro" role="status">${esc(errorText(error))}</p>`;
      }
    })();
    return ready;
  }

  // ============ 统计看板 ============
  async function pageDashboard(c) {
    const epoch = routeEpoch;
    const [d, demands, projects, dispatches, charges, fees, approvalsPayload, bindingPayload] = await Promise.all([
      api('/stats/overview'), api('/demands'), api('/projects'), api('/dispatches'), api('/charges'), api('/fees'),
      api('/approvals/tasks', { quiet: true }).catch((error) => ({ tasks: [], _error: error.message })),
      api('/organization/me', { quiet: true }).catch((error) => ({ _error: error.message })),
    ]);
    if (!isRouteCurrent(epoch, c, 'dashboard')) return;
    const bindingNotice = workflowBindingNotice(bindingPayload);
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
      upsertTask({ key: `dispatch:${x.id}`, page: 'dispatches', projectId: x.project_id, focusId: x.id, tone: dayDiff(x.teach_date) <= 1 ? 'critical' : 'warning', score: dayDiff(x.teach_date) <= 1 ? 88 : 68, label: '交付准备', art: 'documents', title: `${x.subject} · 材料${x.material_status || '状态待补充'}`, meta: `${x.teach_date} · ${x.venue || '场地尚未确定'}`, action: '补齐交付准备' });
    });
    activeProjects.forEach((project) => {
      const projectDispatches = dispatches.filter((x) => String(x.project_id) === String(project.id) && x.status !== '已拒绝');
      const scheduled = projectDispatches.reduce((s, x) => s + Number(x.hours || 0), 0);
      const gap = Math.max(0, Number(project.hours || 0) - scheduled);
      if (gap > 0) upsertTask({ key: `project:${project.id}:schedule`, page: 'dispatches', projectId: project.id, tone: 'warning', score: 74, label: '排课缺口', art: 'calendar', title: `${project.title} 尚缺 ${num(gap)} 课时`, meta: `计划 ${num(project.hours)} 课时 · 已排 ${num(scheduled)} 课时`, action: `安排 ${num(gap)} 课时` });
    });
    charges.filter((x) => x.status !== '已结清').forEach((x) => {
      const left = Math.max(0, Number(x.amount || 0) - Number(x.received || 0));
      if (left > 0) upsertTask({ key: `charge:${x.id}`, page: 'charges', projectId: x.project_id, focusId: x.id, tone: 'neutral', score: 48, label: '回款跟进', title: `${x.project_title} 待收 ¥ ${money(left)}`, meta: `${x.project_unit || '委托单位待补充'} · 最近收款 ${x.charge_date || '尚未登记'}`, action: '登记本次回款' });
    });
    demands.filter((x) => !x.workflow && x.status === '待处理').forEach((x) => upsertTask({ key: `demand:${x.id}`, page: 'demands', focusId: x.id, tone: 'neutral', score: 44, label: '需求判断', title: x.title, meta: `${x.unit} · 期望 ${x.expect_date || '时间待定'}`, action: '判断并推进需求' }));
    const feePending = fees.filter((x) => x.status === '待发放').reduce((s, x) => s + Number(x.amount || 0), 0);
    if (feePending > 0) upsertTask({ key: 'fees:pending', page: 'fees', focusId: fees.find((x) => x.status === '待发放')?.id, tone: 'neutral', score: 40, label: '课酬结算', title: `${fees.filter((x) => x.status === '待发放').length} 笔课酬待发放`, meta: `合计 ¥ ${money(feePending)}`, action: '核对待发课酬' });
    workflowQueueTasks(demands, approvalsPayload.tasks || []).forEach(upsertTask);
    const tasks = [...taskMap.values()];
    tasks.sort((a, b) => b.score - a.score);
    if (!canWrite()) tasks.forEach((task) => { if (!task.workflowAuthorized) task.action = readonlyAction(task.page); });
    const criticalCount = tasks.filter((x) => x.tone === 'critical').length;
    const healthRank = { critical: 0, warning: 1, good: 2 };
    const projectRows = activeProjects.map((project) => {
      const pd = dispatches.filter((x) => String(x.project_id) === String(project.id));
      const valid = pd.filter((x) => x.status !== '已拒绝');
      const scheduled = valid.reduce((s, x) => s + Number(x.hours || 0), 0);
      const confirmed = pd.filter((x) => ['已确认', '已完成'].includes(x.status)).reduce((s, x) => s + Number(x.hours || 0), 0);
      const completed = projectCompletedHours(project, pd);
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
        <span class="v9-task-art">${businessArt(taskArtKind(x))}</span>
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
            <h1>今日运营</h1>
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
          ${bindingNotice ? `<p class="modal-intro" role="status">${esc(bindingNotice)}</p>` : ''}
          ${approvalsPayload._error ? `<p class="modal-intro" role="status">审批待办暂时无法读取：${esc(approvalsPayload._error)}</p>` : ''}
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
                <span class="v9-proj-mid"><span class="v9-proj-bar"><i style="width:${p.completed !== null && Number(p.hours) ? Math.min(100, Number(p.completed) / Number(p.hours) * 100) : 0}%"></i></span><em>${p.delivery_controlled === true ? `${esc(deliveryHoursText(p.completed))} 已核对实际课时` : `${num(p.completed)} 已完成 / ${num(p.confirmed)} 已确认`}</em></span>
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
      list.innerHTML = filtered.length ? shown.map(rowHtml).join('') : `<div class="v9-queue-empty">${icon('circle-check-big')}<b>${curTone ? '该分类暂无事项' : bindingNotice ? '业务待办待核实' : '今天没有阻塞项'}</b><span>${curTone ? '切换其他筛选查看' : bindingNotice ? '请先核对业务身份与机构配置' : '所有关键事项都已推进'}</span></div>`;
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
    const identity = state.user;
    const deliveryController = new AbortController();
    const current = () => !deliveryController.signal.aborted && state.user === identity && isRouteCurrent(epoch, c, 'dispatches');
    let delivery = null, deliveryOpening = false, visibleRows = [], policySession = null, formalSession = null;
    addRouteCleanup(() => { deliveryController.abort(); delivery?.cleanup(); policySession?.cleanup(); formalSession?.cleanup(); }, epoch);
    const attachPolicyPreview = (mask, dispatchId) => {
      if (!mask || !current() || !mask.querySelector('[data-m05-delivery]')) return null;
      policySession?.cleanup();
      const controller = new AbortController();
      const slot = document.createElement('div');
      slot.setAttribute('data-delivery-policy-host', '');
      const basis = document.createElement('p');
      basis.className = 'inline-note';
      basis.textContent = '课酬预览只读取已保存的授课记录；未保存修改不参与。预览不代表正式计酬确认或支付。';
      slot.appendChild(basis);
      $('.modal-body', mask).appendChild(slot);
      const session = { dispatchId, mask, controller, preview: null, ready: null, cleanup: null };
      policySession = session;
      const active = () => current() && !controller.signal.aborted && policySession === session && mask.isConnected && $('#modal-mask') === mask;
      const originalClose = mask._closeCleanup;
      session.cleanup = () => {
        if (controller.signal.aborted) return;
        controller.abort(); session.preview?.cleanup(); slot.remove();
        if (policySession === session) policySession = null;
      };
      mask._closeCleanup = () => { originalClose?.(); session.cleanup(); };
      session.ready = (async () => {
        try {
          const { mountPolicyPreview } = await import('/modules/delivery-settlement/policy-preview.js?v=20260923policycombined1');
          if (!active()) return;
          session.preview = mountPolicyPreview(slot, { api, signal: controller.signal });
          await session.preview.open(dispatchId);
        } catch (error) {
          if (active() && error?.name !== 'AbortError') {
            session.preview?.cleanup(); session.preview = null;
            basis.textContent = '课酬预览暂时无法加载，请关闭后重新打开授课记录。授课记录可继续按当前权限办理。';
          }
        }
      })();
      return session;
    };
    // Formal operations stay inside the original delivery modal; they never rebuild its fact editor.
    const attachFormalWorkflow = (mask, dispatchId) => {
      if (!mask || !current() || !mask.querySelector('[data-m05-delivery]')) return null;
      formalSession?.cleanup();
      const controller = new AbortController(), slot = document.createElement('div');
      slot.setAttribute('data-delivery-formal-host', '');
      $('.modal-body', mask).appendChild(slot);
      const session = { dispatchId, mask, controller, formal: null, ready: null, factPending: false, cleanup: null };
      formalSession = session;
      const active = () => current() && formalSession === session && !controller.signal.aborted && mask.isConnected && $('#modal-mask') === mask;
      const previousClose = mask._closeCleanup, previousGuard = mask._beforeClose;
      const combinedGuard = () => previousGuard?.() !== false && (session.formal?.beforeClose() ?? true);
      const changedFact = (event) => {
        if (!active() || !event.target?.closest?.('[data-m05-delivery]')) return;
        if (!['estimated_hours', 'planned_hours', 'actual_minutes', 'payable_hours'].includes(event.target.dataset?.k)) return;
        session.factPending = true;
        session.formal?.setFactPending(true);
      };
      mask.addEventListener('input', changedFact);
      mask.addEventListener('change', changedFact);
      mask._beforeClose = combinedGuard;
      session.cleanup = () => {
        if (controller.signal.aborted) return;
        controller.abort(); session.formal?.cleanup();
        mask.removeEventListener('input', changedFact); mask.removeEventListener('change', changedFact);
        if (mask._beforeClose === combinedGuard) mask._beforeClose = previousGuard;
        slot.remove();
        if (formalSession === session) formalSession = null;
      };
      mask._closeCleanup = () => { previousClose?.(); session.cleanup(); };
      session.ready = (async () => {
        try {
          const { mountFormalWorkspace } = await import('/modules/delivery-settlement/formal-workspace.js?v=20260924coding1');
          if (!active()) return;
          session.formal = mountFormalWorkspace(slot, {
            api, getUser: () => state.user, getModal: () => $('#modal-mask'), isCurrent: active,
            signal: controller.signal,
            onDiscardRequested: () => { if (active()) closeModal(); },
            onChanged: () => { if (active()) return refreshPolicyPreview(dispatchId); },
          });
          await session.formal.open(Number(dispatchId));
          if (active()) session.formal.setFactPending(session.factPending);
        } catch (error) {
          if (active() && error?.name !== 'AbortError') {
            session.formal?.cleanup(); session.formal = null;
            slot.textContent = '课酬办理暂时无法加载，请关闭后重新打开。原授课记录仍可按当前权限维护。';
          }
        }
      })();
      return session;
    };
    const refreshFormalWorkflow = (dispatchId) => {
      const session = formalSession;
      if (!session || String(session.dispatchId) !== String(dispatchId)) return;
      session.factPending = false;
      const refresh = () => {
        if (!current() || session.controller.signal.aborted || formalSession !== session) return;
        session.formal?.setFactPending(false);
        return session.formal?.refresh();
      };
      return session.formal ? refresh() : session.ready?.then(refresh);
    };
    const refreshPolicyPreview = (dispatchId) => {
      const session = policySession;
      if (!session || String(session.dispatchId) !== String(dispatchId)) return;
      // Keep the saved facts and evidence inputs in place while the read-only panel clears and reloads.
      const refresh = () => { if (current() && !session.controller.signal.aborted && policySession === session) return session.preview?.open(dispatchId); };
      if (session.preview) return refresh();
      return session.ready?.then(refresh);
    };
    const openDelivery = async (row) => {
      if (!current() || deliveryOpening || row.delivery_controlled !== true || (hasLimitedProjectAccess(row) && row.read_access.delivery !== true)) return;
      deliveryOpening = true;
      try {
        const { mount } = await import('/modules/delivery-settlement/index.js?v=20260923history2');
        if (!current()) return;
        if (!delivery) delivery = mount(c, {
          api, renderForm, openModal, closeModal, signal: deliveryController.signal,
          getUser: () => state.user, getModal: () => $('#modal-mask'), isCurrent: current,
          onSaved: (detail) => { refreshDeliveryRow(detail); refreshFormalWorkflow(detail.dispatch_id); return refreshPolicyPreview(detail.dispatch_id); },
          onVerified: (detail) => { refreshDeliveryRow(detail); refreshFormalWorkflow(detail.dispatch_id); return refreshPolicyPreview(detail.dispatch_id); },
          onComplete: async ({ dispatch_id, expected_version, signal }) => {
            if (!current() || signal.aborted) throw new DOMException('页面已关闭', 'AbortError');
            await api('/dispatches/complete', { body: { id: dispatch_id, expected_version }, signal, quiet: true });
            if (current() && !signal.aborted) { refreshDeliveryRow({ dispatch_id }, true); refreshPolicyPreview(dispatch_id); refreshFormalWorkflow(dispatch_id); }
          },
        });
        const opening = delivery.open(row.id);
        const preview = attachPolicyPreview($('#modal-mask'), row.id);
        const formal = attachFormalWorkflow($('#modal-mask'), row.id);
        await opening;
        if (preview) await preview.ready;
        if (formal) await formal.ready;
      } catch (error) {
        if (current() && error?.name !== 'AbortError') toast(error.message || '授课记录暂时无法加载，请重试。', true);
      } finally { deliveryOpening = false; }
    };
    const contextProjectId = state.contextProjectId;
    const selectedProjectId = contextProjectId || String(state.filters.dispatches?.projectId || '');
    const [rows, projects] = await Promise.all([
      api('/dispatches' + (selectedProjectId ? `?project_id=${encodeURIComponent(selectedProjectId)}` : '')), api('/projects'),
    ]);
    if (!current()) return;
    const writableProjects = projects.filter(p => !hasLimitedProjectAccess(p));
    const activeProjects = writableProjects.filter((p) => ['待启动', '进行中'].includes(p.status));
    const activeProjectIds = new Set(activeProjects.map((p) => String(p.id)));
    const contextProject = projects.find((p) => String(p.id) === String(contextProjectId));
    const pending = rows.filter((r) => ['待发送', '已拒绝'].includes(r.status)).length;
    const confirmed = rows.filter((r) => r.status === '已确认').length;
    const completed = rows.filter((r) => r.status === '已完成').length;
    c.innerHTML = `${contextProjectId ? `<div class="context-filter">${icon('link-2')}<span>项目上下文：<b>${esc(contextProject?.title || `项目 #${contextProjectId}`)}</b></span><button type="button" id="context-project-home">返回项目工作区</button><button type="button" id="clear-context">查看全部</button></div>` : ''}<div class="module-summary three"><div><span>${icon('send')}</span><small>待处理安排</small><b><span id="dispatch-pending">${pending}</span><em>条</em></b></div><div><span>${icon('calendar-check')}</span><small>已确认</small><b><span id="dispatch-confirmed">${confirmed}</span><em>场</em></b></div><div><span>${icon('circle-check-big')}</span><small>已完成</small><b><span id="dispatch-completed">${completed}</span><em>场</em></b></div></div><div class="card data-card">
      <div class="card-heading"><div><h2>师资调度列表</h2><p>当前显示 <span id="result-count">${rows.length}</span> 条授课安排</p></div>${canWrite() && activeProjects.length > 0 && (!contextProjectId || activeProjectIds.has(String(contextProjectId))) ? `<button type="button" class="btn" id="add-btn">${icon('calendar-plus')}新建调度</button>` : ''}</div>
      <div class="toolbar">
        <label class="select-filter"><span class="sr-only">${contextProjectId ? '当前项目上下文' : '按培训项目筛选'}</span><select id="flt-proj" ${contextProjectId ? 'disabled aria-label="当前项目上下文已锁定"' : ''}><option value="">全部项目</option>${projects.map((p) => `<option value="${p.id}">#${p.id} ${esc(p.title)}</option>`).join('')}</select></label>
        <button type="button" class="btn gray" id="flt-btn" ${contextProjectId ? 'disabled' : ''}>${icon('list-filter')}筛选</button>
      </div>
      <div id="tbl"></div>
    </div>`;

    const fields = [
      { k: 'project_id', label: '培训项目', type: 'select', required: true, span2: true, options: writableProjects.map((p) => ({ v: p.id, l: `#${p.id} ${p.title}` })) },
      { k: 'teacher_id', label: '授课师资（在库）', type: 'select', required: true, span2: true, options: [] },
      { k: 'subject', label: '授课主题', required: true, span2: true },
      { k: 'teach_date', label: '授课日期', type: 'date', required: true },
      { k: 'confirm_deadline', label: '讲师确认截止', type: 'date' },
      { k: 'start_time', label: '开始时间', type: 'time' },
      { k: 'end_time', label: '结束时间', type: 'time' },
      { k: 'hours', label: '排期计划课时', type: 'number', required: true, min: 0.01, max: 24, step: 0.01, hint: '45分钟为1课时；60分钟填写1.33。实际授课分钟请在授课记录中另行填写。' },
      { k: 'material_status', label: '材料准备', type: 'select', options: ['待准备', '准备中', '已就绪'], value: '待准备' },
      { k: 'venue', label: '授课场地', span2: true, placeholder: '线下教室或线上会议地址' },
      { k: 'remark', label: '备注', type: 'textarea' },
    ];

    // The faculty directory is needed only by an actual legacy scheduling editor.
    let teacherRead = null;
    const loadDispatchTeachers = async () => {
      if (!current() || !canWrite()) return false;
      try {
        if (!teacherRead) teacherRead = api('/teachers', { signal: deliveryController.signal });
        const teachers = await teacherRead;
        if (!current()) return false;
        fields.find(field => field.k === 'teacher_id').options = teachers.filter(t => t.status === '在库').map(t => ({ v: t.id, l: `${t.name}｜${t.title || ''}｜${t.field || ''}｜${t.fee_rate}元/课时` }));
        return true;
      } catch (error) {
        teacherRead = null;
        if (current() && error?.name !== 'AbortError') toast('师资列表暂时无法读取，请重试。', true);
        return false;
      }
    };
    const actions = [
      { l: '消息', cls: 'gray', show: r => !hasLimitedProjectAccess(r), onClick: (r) => {
        const logs = String(r.msg_log || '').split('\n').map((line) => line.trim()).filter(Boolean);
        openModal('通知与确认记录', logs.length ? `<div class="activity-log">${logs.map((line, i) => `<div><span>${String(i + 1).padStart(2, '0')}</span><p>${esc(line)}</p></div>`).join('')}</div>` : `<div class="workspace-empty">${businessArt('faculty')}<b>暂无通知记录</b><span>通过线下、电话或其他渠道通知师资后，可在这里记录操作轨迹。</span></div>`, { noFoot: true, kicker: '师资协同轨迹' });
      } },
      { l: '记录通知', cls: '', show: (r) => !hasLimitedProjectAccess(r) && canWrite() && ['待启动', '进行中'].includes(r.project_status) && ['待发送', '已拒绝'].includes(r.status), onClick: (r) => confirmBox(`请先通过电话、微信或其他实际渠道联系师资“${r.teacher_name}”。确认已经通知《${r.subject}》的授课安排，并在系统中记录这次通知？`, async () => { const res = await api('/dispatches/send', { body: { id: r.id } }); toast(res || '已记录师资通知'); renderPage(); }) },
      { l: '确认', cls: 'green', show: (r) => !hasLimitedProjectAccess(r) && canWrite() && ['待启动', '进行中'].includes(r.project_status) && r.status === '已发送', onClick: (r) => {
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
      { l: '授课记录', cls: 'gray', icon: 'clipboard-check', show: (r) => r.delivery_controlled === true && (!hasLimitedProjectAccess(r) || r.read_access.delivery === true), onClick: openDelivery },
      { l: '完成', cls: 'orange', show: (r) => !hasLimitedProjectAccess(r) && r.delivery_controlled !== true && canWrite() && ['待启动', '进行中'].includes(r.project_status) && r.status === '已确认', onClick: (r) => confirmBox('确认该次授课已完成？系统将校验授课日期，并计入项目已完成课时。', async () => { const res = await api('/dispatches/complete', { body: { id: r.id } }); toast(res); renderPage(); }) },
    ];
    if (canWrite()) {
      const openDispatchEditor = async (r) => {
        if (!current() || hasLimitedProjectAccess(r)) return;
        if (['待发送', '已拒绝'].includes(r.status) && !await loadDispatchTeachers()) return;
        const editFields = ['待发送', '已拒绝'].includes(r.status) ? fields : fields.filter((f) => ['material_status', 'remark'].includes(f.k));
        openModal('编辑调度', renderForm(editFields, r), { onOk: async () => { const d = collectForm($('#modal-mask'), editFields); if (!d) return false; d.id = r.id; d.status = r.status; await api('/dispatches', { body: { ...r, ...d } }); toast('已保存'); renderPage(); } });
      };
      actions.push({ l: '补齐准备', cls: 'gray', show: (r) => !hasLimitedProjectAccess(r) && (['待启动', '进行中'].includes(r.project_status) || (r.delivery_controlled === true && r.project_status === '已完成' && r.status === '已完成')) && r.material_status !== '已就绪', onClick: openDispatchEditor });
      actions.push({ l: '编辑', cls: 'gray', show: (r) => !hasLimitedProjectAccess(r) && (['待启动', '进行中'].includes(r.project_status) || (r.delivery_controlled === true && r.project_status === '已完成' && r.status === '已完成')) && r.material_status === '已就绪', onClick: openDispatchEditor });
      actions.push({ l: '删除', cls: 'red', show: (r) => !hasLimitedProjectAccess(r) && ['待启动', '进行中'].includes(r.project_status) && ['待发送', '已拒绝'].includes(r.status), onClick: (r) => confirmBox(`确定删除该调度记录？`, async () => { await api('/dispatches/delete', { body: { id: r.id } }); toast('已删除'); renderPage(); }) });
    }

    const cols = [
      { k: 'subject', l: '课程安排', render: (r) => `<span class="primary-cell"><b>${esc(r.subject)}</b><small>#D-${String(r.id).padStart(4, '0')} · ${esc(r.project_title)}</small></span>` },
      { k: 'teacher_name', l: '授课师资', render: (r) => `<span class="secondary-cell"><b>${esc(r.teacher_name || '讲师待定')}</b><small>${esc(r.venue || r.teacher_phone || '场地待定')}</small></span>` },
      { k: 'teach_date', l: '日期与课时', render: (r) => `<span class="secondary-cell"><b>${esc(r.teach_date || '日期待定')} ${esc([r.start_time, r.end_time].filter(Boolean).join('—'))}</b><small>${num(r.hours)} ${r.delivery_controlled === true ? '排期课时' : '课时'} · 确认截止 ${esc(r.confirm_deadline || '待定')}</small>${r._deliveryDetail ? `<small>实际 ${esc(deliveryHoursText(r._deliveryDetail.fact?.hours?.actual))} 课时 · ${r._deliveryDetail.fact?.verification ? '已核对' : '未核对'}</small>` : ''}</span>` },
      { k: 'status', l: '交付状态', render: (r) => `<span class="status-stack">${tag(r.status)}${tag(r.material_status || '材料待补充')}</span>` },
      { k: 'sent_at', l: '最近通知', mobileHide: true, render: (r) => `<span class="secondary-cell"><b>${esc(r.sent_at || '尚未发送')}</b><small>${r.confirmed_at ? `确认于 ${esc(r.confirmed_at)}` : '等待状态更新'}</small></span>` },
    ];
    const updateDeliveryCounts = () => {
      $('#dispatch-pending', c).textContent = visibleRows.filter((r) => ['待发送', '已拒绝'].includes(r.status)).length;
      $('#dispatch-confirmed', c).textContent = visibleRows.filter((r) => r.status === '已确认').length;
      $('#dispatch-completed', c).textContent = visibleRows.filter((r) => r.status === '已完成').length;
    };
    const refreshDeliveryRow = (detail, completed = false) => {
      if (!current()) return;
      const row = visibleRows.find((item) => String(item.id) === String(detail.dispatch_id));
      if (!row) return;
      if (detail.fact !== undefined) row._deliveryDetail = detail;
      if (completed) row.status = '已完成';
      const original = $(`tr[data-row-id="${row.id}"]`, c);
      if (!original) return;
      const holder = document.createElement('div');
      holder.innerHTML = renderTable(cols, [row], actions, 'dispatches');
      original.innerHTML = $('tr[data-row-id]', holder).innerHTML;
      bindTableActions(original, [row], actions);
      updateDeliveryCounts();
    };
    const draw = (list) => {
      if (!current()) return;
      visibleRows = list;
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
      if (contextProjectId || !current()) return;
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
    if (addBtn) addBtn.onclick = async () => {
      if (!await loadDispatchTeachers()) return;
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
    const legacyProjects = projects.filter(project => !project.workflow_source && project.delivery_controlled !== true);
    const legacyRecord = row => !projects.some(project => String(project.id) === String(row.project_id) && (project.workflow_source || project.delivery_controlled === true));
    const externalEvaluation = Boolean(contextProject?.workflow_source || contextProject?.delivery_controlled === true);
    c.innerHTML = `${contextProjectId ? `<div class="context-filter">${icon('link-2')}<span>项目上下文：<b>${esc(contextProject?.title || `项目 #${contextProjectId}`)}</b></span><button type="button" id="context-project-home">返回项目工作区</button><button type="button" id="clear-context">查看全部</button></div>` : ''}${externalEvaluation ? '' : `<div class="module-summary three"><div><span>${icon('clipboard-list')}</span><small>历史研序问卷</small><b>${countTag(rows.length)}<em>份</em></b></div><div><span>${icon('send')}</span><small>历史计划触达</small><b>${countTag(sendTotal)}<em>人次</em></b></div><div><span>${icon('message-square-check')}</span><small>历史回收率</small><b>${countTag(sendTotal ? Math.min(100, recvTotal / sendTotal * 100) : 0, 'pct')}<em>%</em></b></div></div>`}<div class="card data-card">
      <div class="card-heading"><div><h2>效果评估列表</h2><p>原平台评分可预览草案；正式汇总按明确政策保存、独立复核，各批次分别保留。历史研序问卷继续原有查看方式。</p></div>${canWrite() && !externalEvaluation && legacyProjects.some(project => project.status !== '已归档') && contextProject?.status !== '已归档' ? `<button type="button" class="btn" id="add-btn">${icon('clipboard-plus')}新建问卷</button>` : ''}</div>
      ${state.user?.role === 'admin' ? '<div class="toolbar" id="survey-policy-entry"></div>' : ''}<div class="toolbar" id="survey-preview-entry"></div><div class="toolbar" id="survey-formal-entry"></div><div id="tbl"></div>
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
      { l: '发布', cls: 'green', show: (r) => canWrite() && legacyRecord(r) && r.project_status !== '已归档' && r.status === '草稿', onClick: (r) => confirmBox('发布后问卷可通过微信发送，确定发布？', () => finishAction(() => api('/q/publish', { body: { id: r.id } }), '已发布')) },
      { l: '发送链接', cls: 'orange', icon: 'send', show: (r) => canWrite() && legacyRecord(r) && r.project_status !== '已归档' && r.status === '已发布', onClick: (r) => showQSend(r, pageContext) },
      { l: '关闭', cls: 'red', show: (r) => canWrite() && legacyRecord(r) && r.project_status !== '已归档' && r.status === '已发布', onClick: (r) => confirmBox('关闭后作答链接将失效，确定关闭？', () => finishAction(() => api('/q/close', { body: { id: r.id } }), '已关闭')) },
    ];
    if (canWrite()) {
      actions.push({ l: '编辑', cls: 'gray', show: (r) => legacyRecord(r) && r.project_status !== '已归档' && r.status === '草稿', onClick: (r) => showQForm(r, legacyProjects, pageContext) });
      actions.push({ l: '删除', cls: 'red', show: (r) => legacyRecord(r) && r.project_status !== '已归档' && r.status === '草稿' && Number(r.send_total || 0) === 0, onClick: (r) => confirmBox('确定删除该草稿问卷？', () => finishAction(() => api('/questionnaires/delete', { body: { id: r.id } }), '已删除')) });
    }
    const table = $('#tbl', c);
    const draw = (list) => { table.innerHTML = renderTable(cols, list, actions, 'questionnaires'); bindTableActions(table, list, actions); };
    if (externalEvaluation && !rows.length) table.innerHTML = '<p class="modal-intro">本项目继续使用原问卷平台；评分草案与正式汇总分别从上方入口查看，不在研序创建或发放问卷。</p>';
    else draw(rows);
    mountSurveyPreviewEntry(c, $('#survey-preview-entry', c), { epoch, page: 'questionnaires', projectId: contextProjectId });
    mountSurveyFormalEntry(c, $('#survey-formal-entry', c), { epoch, page: 'questionnaires', projectId: contextProjectId, projects });
    if (state.user?.role === 'admin') mountSurveyPolicyEntry(c, $('#survey-policy-entry', c), { epoch, page: 'questionnaires' });
    const addBtn = $('#add-btn', c);
    if (addBtn) addBtn.onclick = () => showQForm(null, legacyProjects.filter((p) => p.status !== '已归档'), pageContext);
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
    if (!formMask) return;

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
    if (!sendMask) return;
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
    if (!mask) return;
    refreshIcons(mask);
  }

  // ============ 课酬管理 ============
  async function pageFees(c) {
    const epoch = routeEpoch;
    const identity = state.user;
    const current = () => state.user === identity && isRouteCurrent(epoch, c, 'fees');
    const contextProjectId = state.contextProjectId;
    const selectedProjectId = contextProjectId || (state.focusId ? '' : String(state.filters.fees?.projectId || ''));
    let currentFeeProjectId = selectedProjectId;
    const [rows, projects] = await Promise.all([api('/fees' + (selectedProjectId ? `?project_id=${encodeURIComponent(selectedProjectId)}` : '')), api('/projects')]);
    if (!current()) return;
    const contextProject = projects.find((p) => String(p.id) === String(contextProjectId));
    const calcProjects = projects.filter((p) => p.status !== '已归档' && p.delivery_controlled !== true);
    const controlledSelection = () => projects.find((p) => String(p.id) === String(currentFeeProjectId))?.delivery_controlled === true;
    const total = rows.reduce((s, r) => s + Number(r.amount || 0), 0);
    const pending = rows.filter((r) => r.status === '待发放').reduce((s, r) => s + Number(r.amount || 0), 0);
    c.innerHTML = `${contextProjectId ? `<div class="context-filter">${icon('link-2')}<span>项目上下文：<b>${esc(contextProject?.title || `项目 #${contextProjectId}`)}</b></span><button type="button" id="context-project-home">返回项目工作区</button><button type="button" id="clear-context">查看全部</button></div>` : ''}<div class="module-summary three"><div><span>${icon('wallet-cards')}</span><small>课酬总额</small><b id="fees-total">¥ ${money(total)}</b></div><div><span>${icon('clock-3')}</span><small>待发放</small><b id="fees-pending">¥ ${money(pending)}</b></div><div><span>${icon('circle-check-big')}</span><small>已发放</small><b id="fees-paid">¥ ${money(total - pending)}</b></div></div><div class="card data-card">
      <div class="card-heading"><div><h2>课酬发放列表</h2><p>当前显示 <span id="result-count">${rows.length}</span> 条记录 · <span id="fees-basis">已有课酬记录</span></p></div>${canWrite() && calcProjects.length && contextProject?.delivery_controlled !== true && contextProject?.status !== '已归档' ? `<button type="button" class="btn" id="calc-btn">${icon('calculator')}自动计算课酬</button>` : ''}</div>
      <div class="toolbar">
        <label class="select-filter"><span class="sr-only">${contextProjectId ? '当前项目上下文' : '按培训项目筛选'}</span><select id="flt-proj" ${contextProjectId ? 'disabled aria-label="当前项目上下文已锁定"' : ''}><option value="">全部项目</option>${projects.map((p) => `<option value="${p.id}">#${p.id} ${esc(p.title)}</option>`).join('')}</select></label>
        <button type="button" class="btn gray" id="flt-btn" ${contextProjectId ? 'disabled' : ''}>${icon('list-filter')}筛选</button>
      </div>
      <div class="inline-note" id="fees-rule-notice"></div>
      <div id="tbl"></div>
    </div>`;

    // Formal cases share the existing project filter and original modal.
    const casesRoot = document.createElement('section');
    casesRoot.id = 'formal-cases-host';
    c.appendChild(casesRoot);
    let casesController = null, casesEpoch = 0;
    const casesModule = () => import('/modules/delivery-settlement/cases-workspace.js?v=20260924coding1');
    const syncCases = async () => {
      const ticket = ++casesEpoch;
      const selected = projects.find(p => String(p.id) === String(currentFeeProjectId));
      const enabled = selected?.delivery_controlled === true;
      casesRoot.hidden = !enabled;
      if (!enabled) { if (casesController) await casesController.refresh(null); return; }
      try {
        const module = await casesModule();
        if (!current() || ticket !== casesEpoch) return;
        if (!casesController) casesController = module.mountCasesWorkspace(casesRoot, {
          api, getUser: () => state.user, getModal: () => $('#modal-mask'),
          isCurrent: current, openModal, closeModal,
        });
        await casesController.refresh(Number(selected.id), selected.title);
      } catch (error) {
        if (current() && ticket === casesEpoch) casesRoot.innerHTML = '<p class="inline-note">课酬事项暂未加载，请重新选择项目后重试。原有记录仍可查阅。</p>';
      }
    };
    addRouteCleanup(() => { casesEpoch++; casesController?.cleanup(); }, epoch);

    const cols = [
      { k: 'id', l: '编号', mobileHide: true }, { k: 'project_title', l: '培训项目' }, { k: 'teacher_name', l: '师资' },
      { k: 'hours', l: '课时数', align: 'right' }, { k: 'rate', l: '课酬标准(元/课时)', align: 'right', render: (r) => money(r.rate) },
      { k: 'amount', l: '课酬金额(元)', align: 'right', render: (r) => `<b>¥ ${money(r.amount)}</b>` },
      { k: 'status', l: '状态', render: (r) => `${tag(r.status)}${r.delivery_controlled === true ? '<small>原有记录，仅供查阅</small>' : ''}` }, { k: 'pay_date', l: '发放日期' },
    ];
    const actions = [
      { l: '发放', cls: 'green', show: (r) => r.delivery_controlled !== true && canWrite() && r.project_status !== '已归档' && r.status === '待发放', onClick: (r) => confirmBox(`确认向【${r.teacher_name}】发放课酬 ${money(r.amount)} 元？系统将校验对应授课是否已完成。`, async () => { await api('/fees/pay', { body: { id: r.id } }); toast('课酬已发放'); renderPage(); }) },
    ];
    if (canWrite()) actions.push({ l: '删除', cls: 'red', show: (r) => r.delivery_controlled !== true && r.project_status !== '已归档' && r.status === '待发放', onClick: (r) => confirmBox('确定删除该笔待发课酬记录？', async () => { await api('/fees/delete', { body: { id: r.id } }); toast('已删除'); renderPage(); }) });

    const draw = (list) => {
      if (!current()) return;
      const table = $('#tbl', c);
      if (!table) return;
      const controlled = controlledSelection();
      const containsControlled = controlled || (!currentFeeProjectId && projects.some((p) => p.delivery_controlled === true));
      table.innerHTML = controlled && !list.length ? '<div class="empty-state"><h3>当前没有原有课酬记录</h3><p>授课申报请在课程与排期办理；开发、旧账、更正与免付从下方项目课酬事项办理。</p></div>' : renderTable(cols, list, actions, 'fees');
      $('#fees-basis', c).textContent = containsControlled ? '原有记录仅供查阅，请按项目办理正式课酬事项' : '按已确认课时与讲师标准核算';
      $('#fees-rule-notice', c).textContent = containsControlled
        ? '采用授课核对流程的项目，请在课程与排期申报授课，在下方办理开发、旧账、更正或免付。原有金额不能替代正式应付及实际收付记录。'
        : '已确认课时可提前核算，实际授课完成后才能发放；重新计算会保留已发放记录，只补齐尚未覆盖的课时。';
      const calc = $('#calc-btn', c);
      if (calc) { calc.hidden = controlled; calc.style.display = controlled ? 'none' : ''; }
      bindTableActions(table, list, actions);
      const visibleTotal = list.reduce((sum, row) => sum + Number(row.amount || 0), 0);
      const visiblePending = list.filter((row) => row.status === '待发放').reduce((sum, row) => sum + Number(row.amount || 0), 0);
      $('#result-count', c).textContent = list.length;
      $('#fees-total', c).textContent = controlled ? '请核对项目结算摘要' : `¥ ${money(visibleTotal)}`;
      $('#fees-pending', c).textContent = controlled ? '请核对当前账目' : `¥ ${money(visiblePending)}`;
      $('#fees-paid', c).textContent = controlled ? '请核对当前账目' : `¥ ${money(visibleTotal - visiblePending)}`;
    };
    draw(rows);
    await syncCases();
    revealFocusedRow($('#tbl', c));
    if (selectedProjectId) $('#flt-proj', c).value = String(selectedProjectId);
    let filterController = null;
    addRouteCleanup(() => filterController?.abort(), epoch);
    const runProjectFilter = async () => {
      if (contextProjectId || !current()) return;
      if (casesController && !casesController.beforeClose()) return;
      const v = $('#flt-proj', c).value;
      state.filters.fees = { projectId: v };
      currentFeeProjectId = v;
      void syncCases();
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
      if (controlledSelection()) return;
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

  // ============ 师资资源：档案、简历与智能推荐 ============
  const teacherFeeRateText = (value) => Number.isFinite(Number(value)) && Number(value) > 0 ? `¥ ${money(value)}/课时` : '待确认';
  const teacherFormFields = (allowIncompleteResidence = false) => [
    { k: 'name', label: '姓名', required: true },
    { k: 'gender', label: '性别', type: 'select', options: ['男', '女'] },
    { k: 'org', label: '所在单位' },
    { k: 'title', label: '职称/职务' },
    { k: 'teacher_level', label: '讲师等级', type: 'select', options: [{ v: '', l: '待补充' }, '讲师', '高级讲师', '特级讲师', '特聘讲师'], hint: '由录入人确认。先就近、再比匹配度，仅同分时优先较高等级；不确定可暂留空。' },
    ...residenceFields(!allowIncompleteResidence),
    { k: 'field', label: '专业领域', span2: true },
    { k: 'phone', label: '联系电话' },
    { k: 'email', label: '电子邮箱', type: 'email' },
    { k: 'fee_rate', label: '课酬标准（元/课时）', type: 'number', nullable: true, min: 0, step: 100, placeholder: '待确认时留空', hint: '仅填写已确认的课酬；未知请留空，不用 0 代替未知价格。' },
    { k: 'in_date', label: '入库日期', type: 'date' },
    { k: 'intro', label: '师资简介', type: 'textarea' },
  ];

  let teacherResumeUploadConfig = {};
  let teacherRecommendationSequence = 0;
  let teacherRecommendationController = null;
  let teacherProfileRequestSequence = 0;

  function cancelTeacherRecommendation() {
    teacherRecommendationSequence += 1;
    teacherRecommendationController?.abort();
    teacherRecommendationController = null;
  }

  function resumeLimitBytes(extension) {
    const ext = String(extension || '').toLowerCase();
    const fallback = ext === 'pptx' ? 200 * 1024 * 1024 : 15 * 1024 * 1024;
    const config = teacherResumeUploadConfig || {};
    const nested = config.limits?.[ext] || config.upload_limits?.[ext] || {};
    const byteValues = [config[`max_${ext}_bytes`], config[`${ext}_max_bytes`], nested.max_bytes, nested.bytes];
    const byteValue = byteValues.map(Number).find((value) => Number.isFinite(value) && value > 0);
    if (byteValue) return byteValue;
    const mbValues = [config[`max_${ext}_mb`], config[`${ext}_max_mb`], nested.max_mb, nested.mb];
    const mbValue = mbValues.map(Number).find((value) => Number.isFinite(value) && value > 0);
    return mbValue ? mbValue * 1024 * 1024 : fallback;
  }

  const resumeLimitMb = (extension) => Math.round(resumeLimitBytes(extension) / 1024 / 1024);
  const invalidateTeacherRecommendations = () => {
    cancelTeacherRecommendation();
    delete state.cache.teacherRecommendations;
    delete state.cache.teacherRecommendationKey;
  };

  function teacherRecommendationKey() {
    const form = state.teacherRecommendationForm;
    return JSON.stringify([state.teacherRequirementDraft.trim(), String(form.demandId || ''), Number(form.maxResults), String(form.maxFeeRate || '').trim(), Boolean(form.hardBudget), form.logistics || {}, form.standardCourse || {}]);
  }

  function teacherResumeItems(payload) {
    if (Array.isArray(payload)) return payload;
    if (Array.isArray(payload?.items)) return payload.items;
    if (Array.isArray(payload?.resumes)) return payload.resumes;
    if (Array.isArray(payload?.rows)) return payload.rows;
    return [];
  }

  function listText(value) {
    if (Array.isArray(value)) return value.flatMap(listText).filter(Boolean);
    if (value && typeof value === 'object') return Object.values(value).flatMap(listText).filter(Boolean);
    if (value === undefined || value === null) return [];
    return String(value).split(/\n+|[；;]\s*/).map((item) => item.trim()).filter(Boolean);
  }

  function resumeProfileText(value) {
    if (value === undefined || value === null || value === '') return '';
    if (typeof value === 'string') return value;
    try { return JSON.stringify(value, null, 2); } catch (error) { return String(value); }
  }

  function formatResumeBytes(value) {
    const bytes = Number(value || 0);
    if (!Number.isFinite(bytes) || bytes <= 0) return '大小待确认';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10240 ? 1 : 0)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }

  function formatResumeTime(value) {
    if (!value) return '—';
    return String(value).replace('T', ' ').replace(/\.\d+.*$/, '').slice(0, 16);
  }

  function resumeStatusInfo(value) {
    const raw = String(value || '未上传').trim();
    const key = raw.toLowerCase();
    if (!raw || ['none', 'missing', 'not_uploaded', '未上传'].includes(key)) return { label: '未上传', tone: 'empty' };
    if (key.includes('fail') || key.includes('error') || raw.includes('失败')) return { label: '解析失败', tone: 'failed' };
    if (key.includes('needs_ocr') || key.includes('ocr') || raw.includes('需人工')) return { label: '需人工补充', tone: 'review' };
    if (key.includes('review') || key.includes('confirm') || raw.includes('待确认')) return { label: '待确认', tone: 'review' };
    if (key.includes('parsing') || key.includes('processing') || raw.includes('解析中')) return { label: '解析中', tone: 'processing' };
    if (['ready', 'parsed', 'completed', 'success', '可推荐', '已解析'].includes(key) || raw.includes('可推荐')) return { label: '可推荐', tone: 'ready' };
    if (key.includes('pending') || key.includes('queued') || key.includes('uploaded') || raw.includes('待解析') || raw.includes('等待')) return { label: '等待解析', tone: 'pending' };
    return { label: raw, tone: 'empty' };
  }

  const resumeStatusTag = (resume) => tag(resumeStatusInfo(resume?.parse_status).label);
  const resumeHasFile = (resume) => Boolean(resume && (resume.file_name || !['empty'].includes(resumeStatusInfo(resume.parse_status).tone)));

  function teacherEntryGuide() {
    return `<div class="teacher-entry-guide"><b>首次添加讲师</b><ol aria-label="讲师资料准备流程"><li>1. 新建讲师档案</li><li>2. 关联并上传简历</li><li>3. 核对解析结果</li></ol><p>已有档案可直接上传简历，无需重复建档。暂不清楚常驻城市？先填姓名保存为「待完善」，上传后可继续补充资料；补齐真实省市并确认入库前不进入推荐。</p></div>`;
  }

  function openTeacherCreate() {
    if (!canWrite()) return;
    const fields = teacherFormFields(true);
    return openModal('新建讲师档案', `<p class="teacher-create-note">保存讲师的基本信息与常驻地区（已知时填写），建档后再上传简历。常驻城市可后补；缺失时先保存为「待完善」，不会进入推荐。课酬、等级不确定请留空。已有档案请勿重复创建。</p>${renderForm(fields, { in_date: new Date().toISOString().slice(0, 10) })}`, {
      okText: '保存讲师档案',
      onOk: async () => {
        const data = collectForm($('#modal-mask'), fields);
        if (!data) return false;
        data.status = data.base_province && data.base_city ? '在库' : '待完善';
        await api('/teachers', { body: data });
        invalidateTeacherRecommendations();
        toast(`${data.status === '待完善' ? '待完善档案已保存' : '讲师档案已建立'}，下一步可关联并上传简历`);
        renderPage();
      },
    });
  }

  function openTeacherResumeUpload(teachers, selectedTeacherId = '') {
    if (!canWrite()) return;
    if (!teachers.length) {
      return openModal('先建立讲师档案', `<p class="teacher-create-note">目前还没有可关联的讲师档案。先填写姓名即可保存待完善档案并上传简历；真实常驻城市可后补。上传简历不会自动新建档案。</p>`, {
        sm: true, kicker: '首次添加讲师', okText: '新建讲师档案', okIcon: 'user-plus',
        onOk: () => { openTeacherCreate(); return false; },
      });
    }
    const selected = String(selectedTeacherId || '');
    const ownerResidenceFields = (id) => residenceFields(teachers.find((teacher) => String(teacher.id) === String(id))?.status !== '待完善');
    const options = teachers.map((teacher) => `<option value="${esc(teacher.id)}" ${String(teacher.id) === selected ? 'selected' : ''}>${esc(teacher.name)}｜${esc(teacherResidenceText(teacher))}｜${esc(teacher.org || '单位待补充')}</option>`).join('');
    const mask = openModal('上传讲师简历', `
      <div class="resume-upload-lead"><span>${icon('scan-text')}</span><div><b>先建讲师档案，再关联简历</b><p>已有档案：直接选择下方讲师并上传。系统会提取 PDF / PPTX 中的专业经历，请核对解析结果后用于推荐。</p></div></div>
      <div class="resume-upload-fields">
        <div class="form-item"><label for="teacher-resume-owner">关联已建档讲师<span class="req">*</span></label><select id="teacher-resume-owner" required aria-describedby="teacher-resume-owner-help teacher-resume-owner-error" ${selected ? '' : 'autofocus'}><option value="" disabled ${selected ? '' : 'selected'}>请选择已建立档案的讲师</option>${options}</select><small id="teacher-resume-owner-help">找不到讲师？请先取消上传，在「师资档案」点击「新建讲师档案」。上传简历不会自动新建档案。</small><span class="field-error" id="teacher-resume-owner-error" aria-live="polite"></span></div>
        <div id="resume-residence-fields">${renderForm(ownerResidenceFields(selected), teachers.find((teacher) => String(teacher.id) === selected))}</div>
        <p class="resume-residence-note">真实常驻地区用于线下培训的同城优先参考。「待完善」档案可留空或只填已确认省份、先上传简历；请在「师资档案 → 完善并入库」补齐后确认。修改会单独保存，不会被简历解析结果覆盖。</p>
      </div>
      <label class="resume-drop-zone" id="teacher-resume-drop" for="teacher-resume-file">
        <input class="sr-only" id="teacher-resume-file" type="file" accept=".pdf,.pptx,application/pdf,application/vnd.openxmlformats-officedocument.presentationml.presentation">
        <span class="resume-drop-icon">${icon('file-up')}</span>
        <b>选择或拖入讲师简历</b>
        <small>PDF ≤ ${resumeLimitMb('pdf')} MB · PPTX ≤ ${resumeLimitMb('pptx')} MB；再次上传会替换旧文件</small>
      </label>
      <div class="resume-file-selected" id="teacher-resume-selected" role="status" aria-live="polite"><span>${icon('file-text')}</span><p><b>尚未选择文件</b><small>请选择需要解析的讲师简历</small></p></div>
      <div class="resume-upload-progress" id="teacher-resume-progress" hidden><progress id="teacher-resume-progress-bar" max="100" value="0" aria-label="简历上传进度"></progress><span id="teacher-resume-progress-text" role="status" aria-live="polite">正在准备上传…</span></div>
      <div class="resume-privacy-note">${icon('shield-check')}简历可能包含联系方式等个人信息，仅管理员和业务管理员可下载原件或维护画像。</div>
    `, {
      wide: true,
      kicker: '师资智能档案',
      okIcon: 'sparkles',
      okText: '上传并解析',
      onOk: async () => {
        const teacherId = $('#teacher-resume-owner', mask).value;
        const ownerError = $('#teacher-resume-owner-error', mask);
        ownerError.textContent = '';
        $('#teacher-resume-owner', mask).removeAttribute('aria-invalid');
        if (!teacherId) { ownerError.textContent = '请选择已建档讲师；尚未建档请按上方提示先建档'; $('#teacher-resume-owner', mask).setAttribute('aria-invalid', 'true'); $('#teacher-resume-owner', mask).focus(); return false; }
        const residence = collectForm($('#resume-residence-fields', mask), ownerResidenceFields(teacherId));
        if (!residence) return false;
        if (!chosenFile) { toast('请选择 PDF 或 PPTX 讲师简历', true); $('#teacher-resume-file', mask).focus(); return false; }
        const extension = /\.pptx$/i.test(chosenFile.name || '') ? 'pptx' : 'pdf';
        const signature = new Uint8Array(await chosenFile.slice(0, 1024).arrayBuffer());
        const signatureText = Array.from(signature.slice(0, 1024)).map((byte) => String.fromCharCode(byte)).join('');
        const validSignature = extension === 'pdf' ? signatureText.includes('%PDF-') : signature[0] === 0x50 && signature[1] === 0x4b;
        if (!validSignature) { toast(`文件内容不是有效的 ${extension.toUpperCase()}`, true); return false; }
        mask.dataset.locked = 'true';
        $('#modal-x', mask).disabled = true;
        $('#modal-cancel', mask).disabled = true;
        $('#teacher-resume-owner', mask).disabled = true;
        $('#teacher-resume-file', mask).disabled = true;
        $$('#resume-residence-fields input, #resume-residence-fields select', mask).forEach((input) => { input.disabled = true; });
        $('#teacher-resume-progress', mask).hidden = false;
        $('#teacher-resume-progress-bar', mask).value = 0;
        $('#teacher-resume-progress-text', mask).textContent = '正在准备上传…';
        const uploadFile = chosenFile;
        let residenceSaved = false;
        try {
          const owner = teachers.find((teacher) => String(teacher.id) === teacherId);
          if (owner?.base_province !== residence.base_province || owner?.base_city !== residence.base_city) {
            await api('/teachers/residence', { body: { id: Number(teacherId), ...residence } });
            if (owner) Object.assign(owner, residence);
            residenceSaved = true;
            invalidateTeacherRecommendations();
          }
          const result = await uploadTeacherResume(uploadFile, teacherId, {
            onProgress: (loaded, total) => {
              if (!mask.isConnected) return;
              const percent = Math.min(100, Math.round(loaded / Math.max(1, total) * 100));
              $('#teacher-resume-progress-bar', mask).value = percent;
              $('#teacher-resume-progress-text', mask).textContent = loaded >= total
                ? '文件上传完成，正在提取讲师信息，请稍候…'
                : `正在上传 ${percent}% · ${formatResumeBytes(loaded)} / ${formatResumeBytes(total)}`;
            },
          });
          invalidateTeacherRecommendations();
          state.teacherTab = 'resumes';
          toast(result?.status === 'needs_ocr' ? '简历已安全保存，请补充人工专业画像' : '简历上传与解析已完成');
          renderPage();
        } catch (error) {
          const message = `${residenceSaved ? '常驻地区已保存；简历上传未完成：' : ''}${error?.message || '请重试'}`;
          if (mask.isConnected) $('#teacher-resume-progress-text', mask).textContent = message;
          throw new Error(message);
        } finally {
          if (mask.isConnected) {
            mask.dataset.locked = 'false';
            $('#modal-x', mask).disabled = false;
            $('#modal-cancel', mask).disabled = false;
            $('#teacher-resume-owner', mask).disabled = false;
            $('#teacher-resume-file', mask).disabled = false;
            $$('#resume-residence-fields input, #resume-residence-fields select', mask).forEach((input) => { input.disabled = false; });
          }
        }
      },
    });
    if (!mask) return;
    const fileInput = $('#teacher-resume-file', mask);
    $('#teacher-resume-owner', mask).onchange = () => {
      $('#teacher-resume-owner-error', mask).textContent = '';
      $('#teacher-resume-owner', mask).removeAttribute('aria-invalid');
      const owner = teachers.find((teacher) => String(teacher.id) === $('#teacher-resume-owner', mask).value);
      $('#resume-residence-fields', mask).innerHTML = renderForm(ownerResidenceFields(owner?.id), owner);
    };
    const drop = $('#teacher-resume-drop', mask);
    const selectedBox = $('#teacher-resume-selected', mask);
    let chosenFile = null;
    const clearChosen = () => {
      chosenFile = null;
      fileInput.value = '';
      drop.classList.remove('has-file');
      selectedBox.innerHTML = `<span>${icon('file-text')}</span><p><b>尚未选择文件</b><small>请选择需要解析的讲师简历</small></p>`;
      refreshIcons(selectedBox);
    };
    const choose = (file) => {
      if (mask.dataset.locked === 'true') return;
      if (!file) return;
      const extension = /\.pptx$/i.test(file.name || '') ? 'pptx' : /\.pdf$/i.test(file.name || '') ? 'pdf' : '';
      if (!extension || !file.size) { clearChosen(); toast('请选择有效的 PDF 或 PPTX 文件', true); return; }
      if (file.size > resumeLimitBytes(extension)) { clearChosen(); toast(`${extension.toUpperCase()} 不能超过 ${resumeLimitMb(extension)} MB`, true); return; }
      chosenFile = file;
      drop.classList.add('has-file');
      selectedBox.innerHTML = `<span>${icon('file-check-2')}</span><p><b>${esc(file.name)}</b><small>${formatResumeBytes(file.size)} · 已准备上传</small></p>`;
      refreshIcons(selectedBox);
    };
    fileInput.onchange = () => choose(fileInput.files?.[0]);
    ['dragenter', 'dragover'].forEach((name) => drop.addEventListener(name, (event) => { event.preventDefault(); drop.classList.add('is-dragover'); }));
    ['dragleave', 'drop'].forEach((name) => drop.addEventListener(name, (event) => { event.preventDefault(); drop.classList.remove('is-dragover'); }));
    drop.addEventListener('drop', (event) => choose(event.dataTransfer?.files?.[0]));
  }

  function resumeClaimFacts(claims = {}) {
    claims = claims && typeof claims === 'object' ? claims : {};
    const definitions = [
      ['claimed_training_hours', '自述累计课时', '课时'],
      ['claimed_sessions', '自述授课场次', '场'],
      ['claimed_satisfaction_percent', '自述满意度', '%'],
      ['claimed_experience_years', '自述从业年限', '年'],
    ];
    return definitions.flatMap(([key, label, unit]) => {
      const raw = claims[key];
      if (raw === undefined || raw === null || raw === '') return [];
      const value = String(raw).trim();
      return [{ key, label, value: value.endsWith(unit) ? value : `${value}${unit}` }];
    });
  }

  function resumeClaimMarkup(claims = {}) {
    const facts = resumeClaimFacts(claims);
    return facts.length
      ? `<dl class="resume-claim-facts">${facts.map((fact) => `<div><dt>${esc(fact.label)}</dt><dd>${esc(fact.value)}</dd></div>`).join('')}</dl><p class="resume-fact-caption">以上数值来自简历或管理员校准，请结合原件核对时间范围与计量口径。</p>`
      : '<p class="resume-derived-empty">资料中暂未提取到明确的课时、场次、满意度或从业年限。</p>';
  }

  function manualProfileDraft(profile = {}) {
    const groups = [
      ['专业概述', profile.summary], ['擅长主题', profile.tags], ['行业经验', profile.industries],
      ['授课对象', profile.audiences], ['专业资历', profile.credentials], ['代表课程', profile.courses],
      ['授课形式', profile.delivery_modes], ['服务案例', profile.service_cases],
    ];
    const lines = groups.flatMap(([label, values]) => {
      const content = listText(values).join('；');
      return content ? [`${label}：${content}`] : [];
    });
    resumeClaimFacts(profile.resume_claims).forEach((fact) => lines.push(`${fact.label}：${fact.value}`));
    return lines.join('\n');
  }

  async function openResumeProfileEditor(resume, teacher) {
    if (!canWrite()) return;
    const epoch = routeEpoch;
    const ticket = ++teacherProfileRequestSequence;
    let detail = resume;
    if (!resume.profile) {
      try { detail = await api(`/teacher-resumes/profile?teacher_id=${encodeURIComponent(resume.teacher_id)}`); }
      catch (error) { return; }
    }
    if (!isRouteCurrent(epoch, null, 'teachers') || ticket !== teacherProfileRequestSequence) return;
    const fields = [...residenceFields(teacher?.status !== '待完善'), {
      k: 'manual_profile', label: '管理员校准画像', type: 'textarea', required: true,
      placeholder: '请完整填写讲师擅长主题、行业经验、授课对象、课程和资历，并注明需要进一步确认的条件。',
      hint: '已带入现有专业事实供您核对。请保留仍然有效的内容并修正遗漏；保存后，这份完整画像将优先用于推荐。最多 10000 字。',
    }];
    openModal(`编辑专业画像 · ${teacher?.name || detail.teacher_name || '讲师'}`, renderForm(fields, { ...teacher, manual_profile: resumeProfileText(detail.manual_profile) || manualProfileDraft(detail.profile) }), {
      wide: true,
      kicker: '人工校准',
      okIcon: 'save',
      okText: '保存画像',
      onOk: async () => {
        const data = collectForm($('#modal-mask'), fields);
        if (!data) return false;
        if (data.manual_profile.length > 10000) { toast('校准画像不能超过 10000 字', true); return false; }
        const target = Number(detail.id) > 0 ? { id: Number(detail.id) } : { teacher_id: Number(detail.teacher_id) };
        await api('/teacher-resumes/profile', { body: { ...target, manual_profile: data.manual_profile, base_province: data.base_province, base_city: data.base_city } });
        invalidateTeacherRecommendations();
        state.teacherTab = 'resumes';
        toast('人工专业画像已保存');
        renderPage();
      },
    });
  }

  function reparseTeacherResume(resume) {
    if (!canWrite()) return;
    confirmBox(`重新解析【${resume.teacher_name || '该讲师'}】的简历？人工专业画像会保留。`, async () => {
      await api('/teacher-resumes/reparse', { body: { teacher_id: Number(resume.teacher_id) } });
      invalidateTeacherRecommendations();
      state.teacherTab = 'resumes';
      toast('已重新提交解析');
      renderPage();
    });
  }

  function deleteTeacherResume(resume) {
    if (!canWrite()) return;
    confirmBox(`确定删除【${resume.teacher_name || '该讲师'}】的简历原件与解析结果？讲师基础档案和历史评价不会删除。`, async () => {
      await api('/teacher-resumes/delete', { body: { teacher_id: Number(resume.teacher_id) } });
      invalidateTeacherRecommendations();
      state.teacherTab = 'resumes';
      toast('讲师简历已删除');
      renderPage();
    });
  }

  async function openResumeDetails(resume, teacher) {
    if (!canWrite()) return;
    const epoch = routeEpoch;
    const ticket = ++teacherProfileRequestSequence;
    let detail;
    try {
      detail = await api(`/teacher-resumes/profile?teacher_id=${encodeURIComponent(resume.teacher_id)}`);
    } catch (error) {
      return;
    }
    if (!isRouteCurrent(epoch, null, 'teachers') || ticket !== teacherProfileRequestSequence) return;
    const manualProfile = resumeProfileText(detail.manual_profile);
    const parsedProfile = detail.profile && typeof detail.profile === 'object' ? detail.profile : {};
    const profileRows = [
      ['擅长主题', listText(parsedProfile.tags)],
      ['行业经验', listText(parsedProfile.industries)],
      ['授课对象', listText(parsedProfile.audiences)],
      ['专业资历', listText(parsedProfile.credentials)],
      ['代表课程', listText(parsedProfile.courses)],
      ['授课形式', listText(parsedProfile.delivery_modes)],
    ].filter(([, values]) => values.length);
    const parsedProfileHtml = `${parsedProfile.summary ? `<p class="resume-derived-summary">${esc(parsedProfile.summary)}</p>` : ''}${profileRows.length ? `<dl class="resume-derived-list">${profileRows.map(([label, values]) => `<div><dt>${esc(label)}</dt><dd>${values.map((value) => `<span>${esc(value)}</span>`).join('')}</dd></div>`).join('')}</dl>` : '<div class="resume-derived-empty">暂未提取到可展示的结构化画像</div>'}`;
    const parseMessage = String(detail.parse_message || '').trim();
    const serviceCases = listText(parsedProfile.service_cases);
    const evidence = recommendationEvidence(parsedProfile.evidence);
    openModal(`${teacher?.name || detail.teacher_name || '讲师'} · 简历解析`, `
      <div class="resume-detail-head"><span class="person-avatar large">${esc((teacher?.name || detail.teacher_name || '讲师').slice(-2))}</span><div><h4>${esc(teacher?.name || detail.teacher_name || '讲师')} ${resumeStatusTag(detail)}</h4><p>${esc(teacher?.org || '单位待补充')} · ${esc(teacher?.title || '职称待补充')}</p></div><small>${esc(formatResumeTime(detail.updated_at))}</small></div>
      <div class="resume-meta-strip"><span>${icon('file-text')}<b>${esc(detail.file_name || '简历文件')}</b><small>${esc(formatResumeBytes(detail.file_size))}</small></span><span>${icon('files')}<b>${esc(detail.page_count || '—')}</b><small>${/\.pptx$/i.test(detail.file_name || '') ? '幻灯片' : '页数'}</small></span><span>${icon('scan-text')}<b>${esc(resumeStatusInfo(detail.parse_status).label)}</b><small>解析状态</small></span></div>
      ${parseMessage ? `<div class="resume-parse-message">${icon(resumeStatusInfo(detail.parse_status).tone === 'failed' ? 'circle-alert' : 'info')}<span>${esc(parseMessage)}</span></div>` : ''}
      <div class="resume-claim-banner">${icon('badge-info')}<span>简历自述和管理员校准用于理解讲师能力；自述课时、满意度、客户案例不计入系统履约记录。</span></div>
      <div class="resume-detail-grid">
        <section><div class="section-title"><div><span>管理员校准画像</span><small>已保存的完整专业事实优先用于推荐</small></div></div><div class="resume-profile-copy">${manualProfile ? esc(manualProfile) : '尚未维护，可点击“编辑人工画像”核对并完善。'}</div></section>
        <section><div class="section-title"><div><span>专业画像</span><small>${manualProfile ? '根据已校准资料整理，请核对关键信息' : '根据简历整理，请核对关键信息'}</small></div></div><div class="resume-profile-copy resume-derived-profile">${parsedProfileHtml}</div></section>
        <section class="resume-facts-section"><div class="section-title"><div><span>简历自述数据</span><small>保留资料口径，供您核对</small></div></div>${resumeClaimMarkup(parsedProfile.resume_claims)}</section>
        <section class="resume-facts-section"><div class="section-title"><div><span>行业与客户案例</span><small>来自讲师资料，项目情况需进一步确认</small></div></div>${serviceCases.length ? `<ul class="resume-service-cases">${serviceCases.map((item) => `<li>${esc(item)}</li>`).join('')}</ul>` : '<p class="resume-derived-empty">暂未提取到明确案例，可在校准画像中补充。</p>'}</section>
      </div>
      ${evidence.length ? `<details class="teacher-evidence"><summary>${icon('file-search')}查看资料依据</summary><ul>${evidence.map((item) => `<li>${esc(item)}</li>`).join('')}</ul></details>` : ''}
      <div class="resume-detail-actions"><button type="button" class="btn gray" id="resume-detail-download">${icon('download')}下载原件</button><button type="button" class="btn gray" id="resume-detail-reparse">${icon('refresh-cw')}重新解析</button><button type="button" class="btn" id="resume-detail-edit">${icon('sliders-horizontal')}编辑人工画像</button></div>
    `, { noFoot: true, wide: true, kicker: '简历与画像' });
    $('#resume-detail-download').onclick = () => downloadTeacherResume(detail.teacher_id, detail.file_name);
    $('#resume-detail-reparse').onclick = () => { closeModal(); reparseTeacherResume(resume); };
    $('#resume-detail-edit').onclick = () => { closeModal(); openResumeProfileEditor({ ...resume, ...detail }, teacher); };
  }

  function renderTeacherLibrary(root, context) {
    context.stopTeacherLibrary?.();
    let libraryAlive = true;
    const { epoch, c, rows, projects, dispatches, resumeByTeacher } = context;
    const savedFilter = state.filters.teachers || {};
    const fields = teacherFormFields();
    root.innerHTML = `<div class="card data-card teacher-library-card">
      <div class="card-heading"><div><h2>师资档案</h2><p>当前显示 <span id="result-count">${rows.length}</span> 条记录 · 档案、简历状态与履约评价统一查看</p></div><div class="teacher-head-actions">${state.user?.role === 'admin' ? `<button type="button" class="btn gray" id="teacher-roster-import">${icon('users-round')}接收教师名单</button>` : ''}<button type="button" class="btn gray" id="teacher-course-catalog">${icon('book-open-check')}课程与认证</button>${canWrite() ? `<button type="button" class="btn gray" id="teacher-add-manual">${icon('user-plus')}新建讲师档案</button><button type="button" class="btn" id="teacher-upload-resume">${icon('file-up')}上传讲师简历</button>` : ''}</div></div>
      ${canWrite() ? teacherEntryGuide() : ''}
      <div class="toolbar">
        <label class="search-box"><span class="sr-only">搜索师资姓名、单位或领域</span>${icon('search')}<input id="flt-kw" value="${esc(savedFilter.kw || '')}" placeholder="搜索：姓名/单位/领域" autocomplete="off"></label>
        <label class="select-filter"><span class="sr-only">按师资状态筛选</span><select id="flt-status"><option value="">全部状态</option><option ${savedFilter.status === '待完善' ? 'selected' : ''}>待完善</option><option ${savedFilter.status === '在库' ? 'selected' : ''}>在库</option><option ${savedFilter.status === '出库' ? 'selected' : ''}>出库</option></select></label>
        <button type="button" class="btn gray" id="flt-btn">${icon('list-filter')}筛选</button>
      </div>
      <div id="tbl"></div>
    </div>`;

    const cols = [
      { k: 'id', l: '编号', mobileHide: true, render: (row) => `<span class="project-id">#T-${String(row.id).padStart(4, '0')}</span>` },
      { k: 'name', l: '师资', render: (row) => `<div class="person-cell"><span class="person-avatar">${esc(row.name.slice(-2))}</span><span><b>${esc(row.name)}</b><small>${esc(row.teacher_level || '等级待补充')} · ${esc(row.title || '职务待补充')}</small></span></div>` },
      { k: 'org', l: '单位' },
      { k: 'base_city', l: '常驻地区', render: (row) => esc(teacherResidenceText(row)) },
      { k: 'field', l: '专业领域' },
      ...(canWrite() ? [{ k: 'resume_status', l: '简历状态', render: (row) => resumeStatusTag(resumeByTeacher.get(String(row.id))) }] : []),
      { k: 'fee_rate', l: '课酬标准', align: 'right', render: (row) => teacherFeeRateText(row.fee_rate) },
      { k: 'status', l: '状态', render: (row) => tag(row.status) },
    ];
    const actions = [{ l: '档案', cls: '', icon: 'contact-round', onClick: (row) => showTeacherEvals(row, projects, dispatches) }];
    if (canWrite()) {
      actions.push({ l: '常驻地区', cls: 'gray', icon: 'map-pin', onClick: (row) => openModal(`常驻地区 · ${row.name}`, renderForm(residenceFields(), row), { onOk: async () => { const data = collectForm($('#modal-mask'), residenceFields()); if (!data) return false; await api('/teachers/residence', { body: { id: row.id, ...data } }); invalidateTeacherRecommendations(); toast('常驻地区已保存'); renderPage(); } }) });
      actions.push({ l: '上传简历', cls: 'gray', icon: 'file-up', onClick: (row) => openTeacherResumeUpload(rows, row.id) });
      actions.push({ l: '出库', cls: 'orange', show: (row) => row.status === '在库', onClick: (row) => confirmBox(`确定将师资【${row.name}】移出师资库？出库后不可参与新调度。`, async () => { await api('/teachers/checkout', { body: { id: row.id } }); invalidateTeacherRecommendations(); toast('已出库'); renderPage(); }) });
      actions.push({ l: '入库', cls: 'green', show: (row) => row.status === '出库', onClick: (row) => confirmBox(`确定将师资【${row.name}】重新入库？`, async () => { await api('/teachers/checkin', { body: { id: row.id } }); invalidateTeacherRecommendations(); toast('已重新入库'); renderPage(); }) });
      actions.push({ l: '完善并入库', cls: 'green', show: (row) => row.status === '待完善', onClick: (row) => openModal('完善讲师档案并入库', `<p class="teacher-create-note">请确认真实常驻省市。其他已知资料可在此补充，未知课酬和等级继续留空；保存后该讲师才会进入推荐候选范围。</p>${renderForm(fields, row)}`, { okText: '保存并确认入库', onOk: async () => { const data = collectForm($('#modal-mask'), fields); if (!data) return false; await api('/teachers', { body: { ...row, ...data } }); await api('/teachers/checkin', { body: { id: row.id } }); invalidateTeacherRecommendations(); toast('档案已完善并入库'); renderPage(); } }) });
      actions.push({ l: '编辑', cls: 'gray', onClick: (row) => { const editFields = teacherFormFields(row.status === '待完善'); return openModal('编辑师资', renderForm(editFields, row), { onOk: async () => { const data = collectForm($('#modal-mask'), editFields); if (!data) return false; await api('/teachers', { body: { ...row, ...data } }); invalidateTeacherRecommendations(); toast(row.status === '待完善' ? '待完善资料已保存；补齐常驻省市后可点击「完善并入库」' : '已保存'); renderPage(); } }); } });
      actions.push({ l: '删除', cls: 'red', onClick: (row) => confirmBox(`仅未产生排课、课酬或评价的师资可以删除。确定检查并删除【${row.name}】？`, async () => { await api('/teachers/delete', { body: { id: row.id } }); invalidateTeacherRecommendations(); toast('已删除'); renderPage(); }) });
    }
    const draw = (list) => {
      if (!libraryAlive || !isRouteCurrent(epoch, c, 'teachers') || !root.isConnected) return;
      const table = $('#tbl', root);
      if (!table) return;
      table.innerHTML = renderTable(cols, list, actions, 'teachers');
      bindTableActions(table, list, actions);
      $('#result-count', root).textContent = list.length;
    };
    draw(rows);
    let filterController = null;
    const runFilter = async () => {
      if (!libraryAlive || !isRouteCurrent(epoch, c, 'teachers') || !$('#flt-kw', root)) return;
      const params = new URLSearchParams();
      const keyword = $('#flt-kw', root);
      const status = $('#flt-status', root);
      if (keyword.value) params.set('kw', keyword.value);
      if (status.value) params.set('status', status.value);
      state.filters.teachers = { ...state.filters.teachers, kw: keyword.value.trim(), status: status.value };
      writeRouteToUrl(true);
      filterController?.abort();
      const controller = new AbortController();
      filterController = controller;
      try {
        const list = await api('/teachers?' + params.toString(), { signal: controller.signal });
        if (controller === filterController) draw(list);
      } catch (error) {
        if (!error || error.name !== 'AbortError') return;
      }
    };
    $('#flt-btn', root).onclick = runFilter;
    $('#flt-status', root).onchange = runFilter;
    const liveFilter = debounce(runFilter, 280);
    context.stopTeacherLibrary = () => { libraryAlive = false; filterController?.abort(); liveFilter.cancel(); };
    addRouteCleanup(context.stopTeacherLibrary, epoch);
    $('#flt-kw', root).oninput = liveFilter;
    $('#flt-kw', root).onkeydown = (event) => {
      if (event.key === 'Enter') { event.preventDefault(); runFilter(); }
      if (event.key === 'Escape') { event.preventDefault(); $('#flt-kw', root).value = ''; runFilter(); }
    };
    if (savedFilter.kw || savedFilter.status) runFilter();
    $('#teacher-course-catalog', root).onclick = context.openCatalog;
    if ($('#teacher-roster-import', root)) $('#teacher-roster-import', root).onclick = context.openRoster;
    if ($('#teacher-upload-resume', root)) $('#teacher-upload-resume', root).onclick = () => openTeacherResumeUpload(rows);
    if ($('#teacher-add-manual', root)) $('#teacher-add-manual', root).onclick = openTeacherCreate;
  }

  function renderResumeManagement(root, context) {
    if (!canWrite()) {
      root.innerHTML = `<div class="recommend-permission-card"><span>${icon('lock-keyhole')}</span><h2>当前账号无权访问简历管理</h2><p>请使用管理员或业务管理员账号维护讲师简历与专业画像。</p></div>`;
      return;
    }
    const { rows, resumes, resumeLoadError } = context;
    const savedFilter = state.filters.teacherResumes || {};
    const teacherById = new Map(rows.map((teacher) => [String(teacher.id), teacher]));
    const resumeById = new Map(resumes.map((resume) => [String(resume.teacher_id), resume]));
    const knownIds = new Set(rows.map((teacher) => String(teacher.id)));
    const records = rows.map((teacher) => ({
      ...(resumeById.get(String(teacher.id)) || {}),
      id: teacher.id,
      teacher_id: teacher.id,
      teacher_name: teacher.name,
      teacher_title: teacher.title,
      teacher_org: teacher.org,
      teacher_status: teacher.status,
      parse_status: resumeById.get(String(teacher.id))?.parse_status || '未上传',
    }));
    resumes.filter((resume) => !knownIds.has(String(resume.teacher_id))).forEach((resume) => records.push({ ...resume, id: resume.teacher_id }));
    root.innerHTML = `${resumeLoadError ? `<div class="resume-load-error" role="alert">${icon('cloud-alert')}<span><b>简历状态暂时无法同步</b><small>${esc(resumeLoadError)}</small></span></div>` : ''}<div class="card data-card resume-manage-card">
      <div class="card-heading"><div><h2>简历管理</h2><p>跟踪 PDF/PPTX 解析、人工画像与推荐可用状态</p></div><div class="teacher-head-actions"><button type="button" class="btn gray" id="resume-manage-refresh">${icon('refresh-cw')}刷新状态</button>${canWrite() ? `<button type="button" class="btn" id="resume-manage-upload">${icon('file-up')}上传讲师简历</button>` : ''}</div></div>
      ${teacherEntryGuide()}
      ${canWrite() ? '' : `<div class="resume-viewer-note">${icon('shield')}当前账号无权访问此区域。</div>`}
      <div class="toolbar resume-toolbar"><label class="search-box"><span class="sr-only">搜索讲师</span>${icon('search')}<input id="resume-flt-keyword" value="${esc(savedFilter.keyword || '')}" placeholder="搜索讲师姓名" autocomplete="off"></label><label class="select-filter"><span class="sr-only">按解析状态筛选</span><select id="resume-flt-status"><option value="">全部解析状态</option>${['未上传', '等待解析', '解析中', '待确认', '需人工补充', '可推荐', '解析失败'].map((status) => `<option ${savedFilter.status === status ? 'selected' : ''}>${status}</option>`).join('')}</select></label></div>
      ${resumes.some((resume) => ['pending', 'processing'].includes(resumeStatusInfo(resume.parse_status).tone)) ? '<p class="resume-refresh-note" role="status">正在解析的简历会自动更新状态；也可以点击“刷新状态”。</p>' : ''}
      <div id="resume-table"></div>
    </div>`;
    const cols = canWrite() ? [
      { k: 'teacher_name', l: '讲师', render: (record) => `<div class="person-cell"><span class="person-avatar">${esc((record.teacher_name || '讲师').slice(-2))}</span><span><b>${esc(record.teacher_name || '未知讲师')}</b><small>${esc(record.teacher_title || '讲师')} · ${esc(record.teacher_status || '状态待确认')}</small></span></div>` },
      { k: 'file_name', l: '简历文件', render: (record) => resumeHasFile(record) ? `<span class="resume-file-cell"><b>${esc(record.file_name || '简历文件')}</b><small>${esc(formatResumeBytes(record.file_size))}${record.page_count ? ` · ${esc(record.page_count)} ${/\.pptx$/i.test(record.file_name || '') ? '张' : '页'}` : ''}</small></span>` : '<span class="muted-cell">尚未上传</span>' },
      { k: 'parse_status', l: '解析状态', render: resumeStatusTag },
      { k: 'manual_profile', l: '人工画像', render: (record) => `<span class="resume-profile-cell">${esc((resumeProfileText(record.manual_profile) || '尚未维护').slice(0, 72))}</span>` },
      { k: 'updated_at', l: '最近更新', render: (record) => esc(formatResumeTime(record.updated_at)) },
    ] : [
      { k: 'teacher_name', l: '讲师', render: (record) => `<div class="person-cell"><span class="person-avatar">${esc((record.teacher_name || '讲师').slice(-2))}</span><span><b>${esc(record.teacher_name || '未知讲师')}</b><small>${esc(record.teacher_title || '讲师')}</small></span></div>` },
      { k: 'parse_status', l: '解析状态', render: resumeStatusTag },
      { k: 'updated_at', l: '最近更新', render: (record) => esc(formatResumeTime(record.updated_at)) },
    ];
    const actions = canWrite() ? [
      { l: '解析详情', cls: '', icon: 'scan-text', show: resumeHasFile, onClick: (record) => openResumeDetails(record, teacherById.get(String(record.teacher_id))) },
      { l: '上传简历', cls: 'gray', icon: 'file-up', show: (record) => !resumeHasFile(record), onClick: (record) => openTeacherResumeUpload(rows, record.teacher_id) },
      { l: '替换简历', cls: 'gray', icon: 'replace', show: resumeHasFile, onClick: (record) => openTeacherResumeUpload(rows, record.teacher_id) },
      { l: '编辑画像', cls: 'gray', icon: 'sliders-horizontal', show: resumeHasFile, onClick: (record) => openResumeProfileEditor(record, teacherById.get(String(record.teacher_id))) },
      { l: '下载原件', cls: 'gray', icon: 'download', show: resumeHasFile, onClick: (record) => downloadTeacherResume(record.teacher_id, record.file_name) },
      { l: '重新解析', cls: 'gray', icon: 'refresh-cw', show: resumeHasFile, onClick: reparseTeacherResume },
      { l: '删除简历', cls: 'red', icon: 'trash-2', show: resumeHasFile, onClick: deleteTeacherResume },
    ] : null;
    const draw = () => {
      const keyword = String($('#resume-flt-keyword', root)?.value || '').trim().toLowerCase();
      const status = String($('#resume-flt-status', root)?.value || '');
      state.filters.teacherResumes = { keyword, status };
      const visible = records.filter((record) => (!keyword || String(record.teacher_name || '').toLowerCase().includes(keyword)) && (!status || resumeStatusInfo(record.parse_status).label === status));
      const table = $('#resume-table', root);
      table.innerHTML = renderTable(cols, visible, actions, 'teacher-resumes');
      if (actions) bindTableActions(table, visible, actions);
    };
    draw();
    const filter = debounce(draw, 160);
    $('#resume-flt-keyword', root).oninput = filter;
    $('#resume-flt-status', root).onchange = draw;
    if ($('#resume-manage-upload', root)) $('#resume-manage-upload', root).onclick = () => openTeacherResumeUpload(rows);
    $('#resume-manage-refresh', root).onclick = () => renderPage();
    let stopped = false;
    let timer;
    let pollController;
    let attempts = 0;
    const stop = () => { stopped = true; clearTimeout(timer); pollController?.abort(); filter.cancel(); };
    context.stopTeacherTabPolling = stop;
    addRouteCleanup(stop, context.epoch);
    const stamp = (items) => JSON.stringify(items.map((item) => [item.teacher_id, item.parse_status, item.updated_at]));
    const originalStamp = stamp(resumes);
    const poll = async () => {
      if (stopped || state.teacherTab !== 'resumes' || !isRouteCurrent(context.epoch, context.c, 'teachers') || !root.isConnected) return;
      pollController = new AbortController();
      try {
        const payload = await api('/teacher-resumes/manage', { signal: pollController.signal });
        if (stopped || !isRouteCurrent(context.epoch, context.c, 'teachers') || state.teacherTab !== 'resumes') return;
        if (stamp(teacherResumeItems(payload)) !== originalStamp) { stop(); renderPage(); return; }
      } catch (error) {
        if (error?.name === 'AbortError') return;
        stop();
        return;
      }
      if (++attempts < 12) timer = setTimeout(poll, 2500);
      else {
        const note = $('.resume-refresh-note', root);
        if (note) note.textContent = '解析仍在进行，可稍后点击“刷新状态”查看结果。';
      }
    };
    if (resumes.some((resume) => ['pending', 'processing'].includes(resumeStatusInfo(resume.parse_status).tone))) timer = setTimeout(poll, 1500);
  }

  function demandRequirementText(demand) {
    if (!demand) return '';
    return [
      demand.unit ? `客户单位：${demand.unit}` : '',
      demand.title ? `培训主题：${demand.title}` : '',
      demand.content ? `培训内容：${demand.content}` : '',
      demand.teacher_req ? `师资要求：${demand.teacher_req}` : '',
      demand.hours ? `预计课时：${demand.hours}` : '',
      demand.expect_date ? `期望日期：${demand.expect_date}` : '',
      demand.remark ? `补充说明：${demand.remark}` : '',
    ].filter(Boolean).join('\n');
  }

  function guidedRequirementText(draft) {
    const fields = [['unit', '客户单位'], ['topic', '培训主题'], ['audience', '参训对象'], ['goals', '希望解决的问题'], ['date', '期望日期'], ['hours', '预计课时'], ['preference', '师资要求'], ['extra', '补充说明']];
    return fields.map(([key, label]) => {
      const value = String(draft?.[key] ?? '').trim();
      return value ? `${label}：${value}` : '';
    }).filter(Boolean).join('\n');
  }

  function guidedRequirementContract(draft) {
    const fields = Object.fromEntries(['unit', 'topic', 'audience', 'goals', 'date', 'hours', 'preference', 'extra'].map((key) => [key, String(draft?.[key] ?? '')]));
    return { schema_version: 'guided_requirement_v1', fields };
  }

  function demandGuidedFields(demand) {
    return { unit: String(demand?.unit || ''), topic: String(demand?.title || ''), audience: '', goals: String(demand?.content || ''), date: String(demand?.expect_date || ''), hours: Number(demand?.hours) > 0 ? String(demand.hours) : '', preference: String(demand?.teacher_req || ''), extra: String(demand?.remark || '') };
  }

  function recommendationPercent(value) {
    const score = Number(value);
    if (!Number.isFinite(score)) return 0;
    return Math.max(0, Math.min(100, Math.round(score * 10) / 10));
  }

  function teacherSystemMetrics(teacherId, dispatches = [], evaluations = [], fallback = {}) {
    const id = String(teacherId || '');
    const completed = dispatches.filter((item) => String(item.teacher_id) === id && item.status === '已完成');
    const teacherEvaluations = evaluations.filter((item) => String(item.teacher_id) === id);
    const completedSessions = completed.length || Number(fallback.completed_sessions || fallback.completed_count || 0);
    const completedHours = completed.length
      ? completed.reduce((total, item) => total + Number(item.hours || 0), 0)
      : Number(fallback.completed_hours || 0);
    const evaluationCount = teacherEvaluations.length || Number(fallback.evaluation_count || fallback.eval_count || 0);
    const evaluationAverage = teacherEvaluations.length
      ? teacherEvaluations.reduce((total, item) => total + Number(item.score || 0), 0) / teacherEvaluations.length
      : Number(fallback.evaluation_score || fallback.evaluation_average || 0);
    return {
      completedSessions: Number.isFinite(completedSessions) ? completedSessions : 0,
      completedHours: Number.isFinite(completedHours) ? completedHours : 0,
      evaluationCount: Number.isFinite(evaluationCount) ? evaluationCount : 0,
      evaluationAverage: Number.isFinite(evaluationAverage) && evaluationCount > 0 ? evaluationAverage : 0,
    };
  }

  function recommendationEvidence(value) {
    return (Array.isArray(value) ? value : listText(value)).map((item) => {
      if (!item || typeof item !== 'object') return String(item || '');
      const page = item.page || item.page_number;
      const text = item.text || item.quote || item.evidence || item.content || '';
      return [page ? `第 ${page} 页` : '', text].filter(Boolean).join(' · ');
    }).filter(Boolean);
  }

  function breakdownLabel(key) {
    return ({ topic: '主题契合', topics: '主题契合', industry: '行业经验', industries: '行业经验', audiences: '授课对象', credentials: '专业资历', performance: '历史履约', budget: '课酬预算', keyword: '关键能力', keywords: '关键能力', profile: '专业画像', experience: '项目经验', evaluation: '履约评价', delivery: '授课适配', resume: '简历证据' })[String(key).toLowerCase()] || '其他匹配条件';
  }

  function semanticEvidenceMarkup(semantic) {
    const score = semantic?.similarity;
    if (semantic?.status !== 'ready' || typeof score !== 'number' || !Number.isFinite(score) || score < -1 || score > 1) return '';
    const evidence = Array.isArray(semantic.evidence) ? semantic.evidence.filter((value) => typeof value === 'string').slice(0, 2) : [];
    if (!evidence.length) return '';
    return `<details class="teacher-evidence teacher-semantic-evidence"><summary>${icon('scan-search')}语义相关原文 <span>相似度 ${score.toFixed(3)}</span></summary><div class="resume-claim-note">取自当前有效专业档案；仅表示文字相关，不代表资历已核实或胜任概率。</div><ul>${evidence.map((quote) => `<li>${esc(quote)}</li>`).join('')}</ul></details>`;
  }

  function railVerificationMarkup(dispatchFit) {
    // Context references carry one city-level observation, never two timed legs.
    const context = dispatchFit?.context_planning_reference;
    const contextKinds = {
      context_roundtrip_shared_v2: ['context_shared_prebid_opt_in_v1', 'context-shared-planning-display-v1'],
      context_current_baseline_shared_v2: ['context_shared_prebid_opt_in_v2', 'context-baseline-planning-display-v2']
    };
    if (dispatchFit?.planning_included === true && (context || Object.hasOwn(contextKinds, dispatchFit.planning_reference_kind || ''))) {
      const pending = '<div class="teacher-rail-query"><div><b>城市交通参考待复核</b><small>当前资料尚不完整，实际出行需另行确认。</small></div></div>';
      const kind = dispatchFit.planning_reference_kind, contract = contextKinds[kind];
      const shown = dispatchFit.context_planning_display, observation = context?.shared_observation;
      if (!Object.hasOwn(contextKinds, kind || '') || !contract || dispatchFit.planning_policy !== contract[0] ||
          context?.reference_kind !== kind || shown?.version !== contract[1] || context.reference_status !== 'active' ||
          context.endpoint_scope !== 'city_summary' || !Array.isArray(context.stations) || context.stations.length !== 2 || context.stations.some(value => value !== null) ||
          context.independent_direction_observations !== 0 ||
          ['transport_verified', 'strict_eligibility', 'value_is_proven_travel_upper_bound', 'rail_exclusion_complete', 'air_fallback_trigger', 'time_score_applicable', 'semantic_annotations_are_language_proof'].some(key => context[key] !== false) ||
          !Number.isSafeInteger(observation?.reference_minutes) || observation.reference_minutes <= 0 || observation.reference_minutes >= 240 ||
          ['label', 'endpoint_label', 'notice', 'basis_note'].some(key => typeof shown[key] !== 'string' || !shown[key].trim()) ||
          ['source_published_on', 'reviewed_on', 'review_due_on'].some(key => typeof context[key] !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(context[key]))) return pending;
      const minutes = observation.reference_minutes;
      const band = minutes <= 60 ? 'planning_0_1h' : minutes <= 120 ? 'planning_1_2h' : minutes <= 180 ? 'planning_2_3h' : 'planning_3_4h';
      if (dispatchFit.context_planning_band_id !== band) return pending;
      const source = /^https:\/\/[^\s<>"']+$/.test(context.source_url || '') ? `<a href="${esc(context.source_url)}" target="_blank" rel="noopener noreferrer">查看参考来源 ↗</a>` : '';
      return `<div class="teacher-rail-query"><div><b>${esc(shown.label)}</b><span>${esc(shown.endpoint_label)}</span><small>${esc(shown.notice)}</small><details><summary>查看依据</summary><small>${esc(shown.basis_note)}不是门到门耗时，不代表授课日行程已确认。</small><small>原发布 ${esc(context.source_published_on)} · 资料核对 ${esc(context.reviewed_on)} · 复核到期 ${esc(context.review_due_on)} ${source}</small></details></div></div>`;
    }
    const planning = dispatchFit?.planning_reference;
    if (dispatchFit?.planning_included === true && (planning?.status === 'shared_city_planning_reference' || dispatchFit?.planning_reference_kind === 'explicit_roundtrip_shared_v1')) {
      const pending = '<div class="teacher-rail-query"><div><b>共享城市交通参考待核</b><small>尚未取得完整的单值规划说明。</small></div></div>';
      const shared = planning?.shared_reference, duration = shared?.duration;
      const forbidden = ['outbound', 'inbound', 'return', 'selection_reference', 'planning_band_id', 'reference_minutes', 'duration_bounds'];
      if (dispatchFit.planning_policy !== 'explicit_roundtrip_shared_planning_v1' || dispatchFit.planning_reference_kind !== 'explicit_roundtrip_shared_v1' ||
          planning?.status !== 'shared_city_planning_reference' || planning.reference_kind !== 'explicit_roundtrip_shared_v1' ||
          planning.purpose !== 'prebid_city_planning_only' || planning.protected_envelope_validated !== true ||
          ['strict_eligibility', 'transport_verified', 'time_score_applicable', 'air_fallback_trigger', 'rail_exclusion_complete', 'semantic_annotations_are_language_proof'].some(key => planning[key] !== false) ||
          !shared || forbidden.some(key => Object.hasOwn(planning, key) || Object.hasOwn(shared, key)) ||
          shared.verification_method !== 'dual_review_explicit_roundtrip_shared_v1' || shared.direction_semantics !== 'explicit_roundtrip_context' ||
          shared.duration_scope !== 'single_journey_shared_summary' || shared.independent_direction_observations !== 0 ||
          shared.value_is_proven_travel_upper_bound !== false || shared.transfer_time_estimated !== false || shared.semantic_annotations_are_language_proof !== false ||
          !duration || Object.keys(duration).sort().join(',') !== 'kind,unit,value' || shared.precision !== duration.kind ||
          !Number.isSafeInteger(duration.value) || duration.value <= 0 || duration.value > 2880) return pending;
      let label = '', minutes = 0;
      if (duration.kind === 'approximate' && duration.unit === 'minute') { label = `约${duration.value}分钟`; minutes = duration.value; }
      else if (duration.kind === 'reported_minutes' && duration.unit === 'minute') { label = `${duration.value}分钟（报道参考）`; minutes = duration.value; }
      else if (duration.kind === 'nominal_hour' && duration.unit === 'hour') { label = `${duration.value}小时（名义值）`; minutes = duration.value * 60; }
      else if (duration.kind === 'nominal_half_hour' && duration.unit === 'half_hour') { label = `${duration.value / 2}小时（名义半小时参考）`; minutes = duration.value * 30; }
      if (!label || minutes >= 240) return pending;
      const bandId = minutes <= 60 ? 'planning_0_1h' : minutes <= 120 ? 'planning_1_2h' : minutes <= 180 ? 'planning_2_3h' : 'planning_3_4h';
      const bands = { planning_0_1h: '不超过1小时参考范围', planning_1_2h: '1–2小时参考范围', planning_2_3h: '2–3小时参考范围', planning_3_4h: '3–4小时参考范围' };
      if (dispatchFit.shared_planning_band_id !== bandId || !shared.endpoint_a || !shared.endpoint_b || !shared.source_published_on || !shared.reviewed_on || !shared.review_due_on) return pending;
      const scopes = { city_summary: '城市概述，未指定站点', main_urban_station: '城区站点', urban_subcentre_station: '城市副中心站点，非市中心耗时' };
      if (!Object.hasOwn(scopes, shared.endpoint_scope?.a) || !Object.hasOwn(scopes, shared.endpoint_scope?.b)) return pending;
      const source = /^https:\/\/[^\s<>"']+$/.test(shared.source_url || '') ? `<a href="${esc(shared.source_url)}" target="_blank" rel="noopener noreferrer">查看参考来源 ↗</a>` : '';
      const description = duration.kind === 'approximate' ? '资料给出往返单程约值，未分别记录去返程。' : '资料给出往返列车的单程时间参考，未分别记录去返程。';
      return `<div class="teacher-rail-query"><div><b>铁路出行参考 · ${esc(label)} · ${esc(bands[bandId])}</b><span>${esc(shared.endpoint_a)}（${esc(scopes[shared.endpoint_scope.a])}） ↔ ${esc(shared.endpoint_b)}（${esc(scopes[shared.endpoint_scope.b])}）</span><small>用于投标前初筛，实际出行待核，市内接驳另行确认。</small><details><summary>查看依据</summary><small>${esc(description)}不是门到门耗时，授课日班次、余票及到场安排仍需确认。</small><small>原发布 ${esc(shared.source_published_on)} · 资料核对 ${esc(shared.reviewed_on)} · 复核到期 ${esc(shared.review_due_on)} ${source}</small></details></div></div>`;
    }
    if (dispatchFit?.planning_included === true && planning?.status === 'planning_reference') {
      const bands = { planning_0_1h: '不超过1小时档', planning_1_2h: '1–2小时档', planning_2_3h: '2–3小时档', planning_3_4h: '3–4小时档' };
      const band = bands[planning.planning_band_id];
      if (!band) return '<div class="teacher-rail-query"><div><b>城市交通参考待核</b><small>尚未取得完整的就近范围说明。</small></div></div>';
      const uiMethods = { dual_review_curated_public_ui_sample_v1: 'official_public_rail_ui_sample', dual_review_curated_public_stop_ui_sample_v1: 'official_public_rail_stop_ui_sample', dual_review_curated_public_popup_ui_sample_v1: 'official_public_rail_popup_ui_sample' };
      const uiMethod = planning.verification_method;
      const uiSample = Object.hasOwn(uiMethods, uiMethod) || ['outbound', 'inbound'].some(side =>
        Object.values(uiMethods).includes(planning[side]?.source_type) || Object.hasOwn(planning[side] || {}, 'source_date_basis') || Object.hasOwn(planning.presentation?.[side] || {}, 'date_basis'));
      if (uiSample) {
        const pending = '<div class="teacher-rail-query"><div><b>铁路出行参考</b><small>时长说明待核。用于投标前初筛，实际出行及市内接驳另行确认。</small></div></div>';
        const exact = (value, keys) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).sort().join(',') === [...keys].sort().join(',');
        const date = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value + 'T00:00:00Z')) && new Date(value + 'T00:00:00Z').toISOString().slice(0, 10) === value;
        const stopTable = uiMethod === 'dual_review_curated_public_stop_ui_sample_v1';
        const popupTable = uiMethod === 'dual_review_curated_public_popup_ui_sample_v1';
        const sourcePage = value => typeof value === 'string' && (stopTable ? /^https:\/\/hzfw\.12306\.cn\/zgzfw\/resources\/web\/skcx\.html(?:\?[^#\s<>"']*)?$/ : /^https:\/\/kyfw\.12306\.cn\/otn\/leftTicket\/init(?:\?[^#\s<>"']*)?$/).test(value);
        const validDateBasis = leg => {
          if (!date(leg.source_query_date) || !date(leg.source_service_date) || !date(leg.reviewed_on) || !date(leg.review_due_on)) return false;
          if (leg.source_date_basis === 'query_service_day_v1') return leg.source_query_date === leg.source_service_date && leg.reviewed_on >= leg.source_service_date;
          if (leg.source_date_basis !== 'query_and_service_day_v2') return false;
          const query = Date.parse(leg.source_query_date + 'T00:00:00Z'), service = Date.parse(leg.source_service_date + 'T00:00:00Z');
          return service >= query && service - query <= 14 * 86400000 && leg.reviewed_on >= leg.source_query_date && Date.parse(leg.review_due_on + 'T00:00:00Z') <= query + 90 * 86400000;
        };
        const selection = planning.selection_reference, hasView = Object.hasOwn(planning, 'presentation'), view = planning.presentation;
        if (!Object.hasOwn(uiMethods, uiMethod) || planning.protected_envelope_validated !== true || planning.purpose !== 'prebid_city_planning_only' ||
            ['transport_verified', 'time_score_applicable', 'air_fallback_trigger', 'rail_exclusion_complete', 'semantic_annotations_are_language_proof'].some(key => planning[key] !== false) ||
            !exact(selection, ['basis', 'buffer_applied', 'confirmed_itinerary', 'outbound', 'inbound']) || selection.basis !== 'unbuffered_city_reference_v1' || selection.buffer_applied !== false || selection.confirmed_itinerary !== false) return pending;
        if (hasView && (!exact(view, ['version', 'purpose', 'affects_selection', 'status', 'revision', 'outbound', 'inbound']) || view.version !== 'city-planning-presentation-v2' ||
            view.purpose !== 'presentation_only' || view.affects_selection !== false || view.status !== 'ready' || !Number.isSafeInteger(view.revision) || view.revision <= 0)) return pending;
        const rows = [], values = [];
        for (const [side, label] of [['outbound', '去程'], ['inbound', '返程']]) {
          const leg = planning[side], picked = selection[side], shown = hasView ? view[side] : null;
          if (!leg || leg.verification_method !== uiMethod || leg.source_type !== uiMethods[uiMethod] || leg.scope !== 'station_service_pair' || leg.service_state !== 'observed_public_ui_service_sample' ||
              leg.sample_scope !== 'selected_service_sample_not_fastest_typical_or_upper_bound' || leg.station_to_teaching_location_minutes !== null || leg.policy_value_is_proven_travel_upper_bound !== false ||
              !Object.hasOwn(leg, 'source_published_on') || leg.source_published_on !== null || !validDateBasis(leg) ||
              leg.review_due_on < leg.reviewed_on || !sourcePage(leg.source_url) ||
              ((stopTable || popupTable) && (!/^[GDC][0-9]{1,5}$/.test(leg.query_service_id || '') || !/^[GDC][0-9]{1,5}$/.test(leg.displayed_service_id || '') || leg.query_display_equivalence_claimed !== false)) ||
              (popupTable && leg.query_service_id !== leg.displayed_service_id) ||
              !exact(leg.endpoint_scope, ['from', 'to']) || leg.endpoint_scope.from !== 'named_city_station' || leg.endpoint_scope.to !== 'named_city_station' ||
              !leg.from_endpoint || !leg.to_endpoint || !leg.from?.id || !leg.to?.id || leg.from.id === leg.to.id || !leg.source_document ||
              leg.precision !== 'same_service_clocks' || !exact(picked, ['reference_minutes', 'unit', 'precision', 'upper_exclusive']) || picked.unit !== 'minute' || picked.precision !== leg.precision || picked.upper_exclusive !== false ||
              !Number.isSafeInteger(picked.reference_minutes) || picked.reference_minutes <= 0 || picked.reference_minutes >= 240 ||
              !exact(leg.source_duration, ['kind', 'arrival_day_offset', 'calculated_minutes']) || leg.source_duration.kind !== leg.precision || leg.source_duration.arrival_day_offset !== 0 || leg.source_duration.calculated_minutes !== picked.reference_minutes) return pending;
          if (hasView && (!exact(shown, ['source_qualifier', 'precision', 'duration', 'from_registry_id', 'to_registry_id', 'source_document', 'annotation_id', 'date_basis']) ||
              shown.source_qualifier !== 'general_reported' || shown.precision !== leg.precision || shown.from_registry_id !== leg.from.id || shown.to_registry_id !== leg.to.id || shown.source_document !== leg.source_document ||
              !shown.annotation_id || !exact(shown.duration, ['kind', 'unit', 'value']) || shown.duration.kind !== leg.precision || shown.duration.unit !== 'minute' || shown.duration.value !== picked.reference_minutes ||
              !exact(shown.date_basis, ['kind', 'query_date', 'service_date']) || shown.date_basis.kind !== leg.source_date_basis || shown.date_basis.query_date !== leg.source_query_date || shown.date_basis.service_date !== leg.source_service_date)) return pending;
          values.push(picked.reference_minutes);
          rows.push({line: `<span>${label}：高铁参考时长 ${esc(picked.reference_minutes)} 分钟</span><small>${esc(leg.from_endpoint)} → ${esc(leg.to_endpoint)}（城市站点，市内接驳另核）</small>`,
            evidence: `<small>${label}资料更新于 ${esc(leg.reviewed_on)} <a href="${esc(leg.source_url)}" target="_blank" rel="noopener noreferrer">查看参考来源 ↗</a></small>`});
        }
        if (planning.outbound.from.id !== planning.inbound.to.id || planning.outbound.to.id !== planning.inbound.from.id) return pending;
        const maximum = Math.max(...values), expectedBand = maximum <= 60 ? 'planning_0_1h' : maximum <= 120 ? 'planning_1_2h' : maximum <= 180 ? 'planning_2_3h' : 'planning_3_4h';
        if (planning.planning_band_id !== expectedBand) return pending;
        return `<div class="teacher-rail-query"><div><b>铁路出行参考 · ${esc(band.replace('档', '参考范围'))}</b>${rows.map(row => row.line).join('')}<small>用于投标前初筛，实际出行待核，市内接驳另行确认。</small><details><summary>查看依据</summary>${rows.map(row => row.evidence).join('')}<small>时长来自已采集的列车样本，不代表最快、典型用时或未来行程保证；市内接驳未计入。</small></details></div></div>`;
      }
      if (Object.hasOwn(planning, 'presentation')) {
        const view = planning.presentation;
        const pending = `<div class="teacher-rail-query"><div><b>铁路出行参考 · ${esc(band)}</b><small>时长说明待核。用于投标前初筛，实际出行及市内接驳另行确认。</small></div></div>`;
        const exactKeys = (value, keys) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).sort().join(',') === [...keys].sort().join(',');
        if (!exactKeys(view, ['version', 'purpose', 'affects_selection', 'status', 'revision', 'outbound', 'inbound']) ||
            !['city-planning-presentation-v1', 'city-planning-presentation-v2'].includes(view.version) || view.purpose !== 'presentation_only' || view.affects_selection !== false || view.status !== 'ready' ||
            !Number.isSafeInteger(view.revision) || view.revision <= 0 || planning.purpose !== 'prebid_city_planning_only' ||
            ['transport_verified', 'time_score_applicable', 'air_fallback_trigger', 'rail_exclusion_complete'].some(key => planning[key] !== false) ||
            planning.selection_reference?.basis !== 'unbuffered_city_reference_v1' || planning.selection_reference.buffer_applied !== false || planning.selection_reference.confirmed_itinerary !== false) return pending;
        const scopes = { city_summary: '城市概述，未指定站点', main_urban_station: '城区站点', urban_subcentre_station: '城市副中心站点，非市中心耗时' };
        const rows = [];
        for (const [side, direction] of [['outbound', '去程'], ['inbound', '返程']]) {
          const shown = view[side], leg = planning[side], selected = planning.selection_reference[side], d = shown?.duration;
          const shownKeys = ['source_qualifier', 'precision', 'duration', 'from_registry_id', 'to_registry_id', 'source_document', 'annotation_id'];
          const institutional = view.version === 'city-planning-presentation-v2' && Object.hasOwn(shown || {}, 'source_context');
          if (institutional) shownKeys.push('source_context');
          if ((institutional && (shown.source_context !== 'institution_location' || shown.source_qualifier !== 'general_reported')) ||
              !exactKeys(shown, shownKeys) ||
              !['reported_fastest', 'general_reported', 'unspecified'].includes(shown.source_qualifier) || !leg || !selected ||
              shown.from_registry_id !== leg.from?.id || shown.to_registry_id !== leg.to?.id || shown.source_document !== leg.source_document ||
              shown.precision !== leg.precision || shown.precision !== selected.precision || shown.precision !== d?.kind ||
              !Number.isSafeInteger(selected.reference_minutes) || selected.reference_minutes <= 0 || selected.unit !== 'minute' ||
              !leg.from_endpoint || !leg.to_endpoint || !leg.source_published_on || !leg.reviewed_on || !leg.review_due_on) return pending;
          if (leg.endpoint_scope && (!Object.hasOwn(scopes, leg.endpoint_scope.from) || !Object.hasOwn(scopes, leg.endpoint_scope.to))) return pending;
          let label = '', minutes = 0;
          const integer = value => Number.isSafeInteger(value) && value > 0 && value < 100000;
          if (['upper_bound', 'bounded_range'].includes(d.kind)) {
            const range = d.kind === 'bounded_range';
            if (!exactKeys(d, range ? ['kind', 'unit', 'lower', 'upper', 'lower_inclusive', 'upper_inclusive'] : ['kind', 'unit', 'upper', 'upper_inclusive']) ||
                d.unit !== 'minute' || !integer(d.upper) || typeof d.upper_inclusive !== 'boolean' || selected.upper_exclusive !== !d.upper_inclusive ||
                (range && (!integer(d.lower) || d.lower >= d.upper || typeof d.lower_inclusive !== 'boolean'))) return pending;
            minutes = d.upper;
            label = range ? `${d.lower_inclusive ? '' : '超过'}${d.lower}至${d.upper_inclusive ? '' : '不到'}${d.upper}分钟` : `${d.upper_inclusive ? '不超过' : '不到'}${d.upper}分钟`;
          } else {
            if (!exactKeys(d, ['kind', 'unit', 'value']) || !integer(d.value) || selected.upper_exclusive !== false) return pending;
            if (['reported_minute', 'reported_minutes', 'computed_same_service', 'same_service_clocks'].includes(d.kind) && d.unit === 'minute') { minutes = d.value; label = `${d.value}分钟`; }
            else if (d.kind === 'approximate' && d.unit === 'minute') { minutes = d.value; label = `约${d.value}分钟`; }
            else if (d.kind === 'nominal_hour' && d.unit === 'hour') { minutes = d.value * 60; label = `${d.value}小时（小时级参考）`; }
            else if (d.kind === 'nominal_half_hour' && d.unit === 'half_hour') { minutes = d.value * 30; label = `${d.value / 2}小时（半小时级参考）`; }
          }
          if (!label || minutes !== selected.reference_minutes || minutes > 240 || (minutes === 240 && selected.upper_exclusive !== true)) return pending;
          const nature = institutional ? '机构区位参考' : shown.source_qualifier === 'reported_fastest' ? '报道最快' : ['computed_same_service', 'same_service_clocks'].includes(d.kind) ? '公告时刻差参考' : '报道用时';
          const endpoint = side => `${leg[side + '_endpoint']}${Object.hasOwn(scopes, leg.endpoint_scope?.[side]) ? `（${scopes[leg.endpoint_scope[side]]}）` : leg.scope === 'city_summary' ? '（城市概述，未指定站点）' : ''}`;
          const source = /^https:\/\/[^\s<>"']+$/.test(leg.source_url || '') ? `<a href="${esc(leg.source_url)}" target="_blank" rel="noopener noreferrer">查看参考来源 ↗</a>` : '';
          rows.push({line: `<span>${direction}：${esc(nature)}${esc(label)}</span><small>${esc(endpoint('from'))} → ${esc(endpoint('to'))}</small>`,
            evidence: `<small>${direction}：原发布 ${esc(leg.source_published_on)} · 资料核对 ${esc(leg.reviewed_on)} · 复核到期 ${esc(leg.review_due_on)} ${source}${shown.source_qualifier === 'unspecified' ? '；资料未单独标注是否最快。' : ''}</small>`});
        }
        return `<div class="teacher-rail-query"><div><b>铁路出行参考 · ${esc(band.replace('档', '参考范围'))}</b>${rows.map(row => row.line).join('')}<small>用于投标前初筛，实际出行待核，市内接驳另行确认。</small><details><summary>查看依据</summary>${rows.map(row => row.evidence).join('')}<small>用时口径以各方向标注为准，不是所有班次或到场保证；不是门到门耗时。</small></details></div></div>`;
      }
      const legs = [['去程', planning.outbound], ['返程', planning.inbound]].map(([direction, leg]) => {
        if (!leg) return '';
        const source = /^https:\/\/[^\s<>"']+$/.test(leg.source_url || '') ? `<a href="${esc(leg.source_url)}" target="_blank" rel="noopener noreferrer">查看参考来源 ↗</a>` : '';
        const endpoints = leg.from_endpoint && leg.to_endpoint ? `<small>参考端点：${esc(leg.from_endpoint)} → ${esc(leg.to_endpoint)}</small>` : '';
        return `<span>${direction}：${esc(leg.from?.city || '')} → ${esc(leg.to?.city || '')}</span>${endpoints}<small>原发布 ${esc(leg.source_published_on || '')} · 复核日期 ${esc(leg.review_due_on || '')} ${source}</small>`;
      }).join('');
      return `<div class="teacher-rail-query"><div><b>铁路初筛 · ${esc(band)}</b>${legs}<small>依据公开旅时参考，出行待核。时间档按城际原参考值，不额外叠加缓冲；不是门到门耗时，也不参与专业能力赋分。</small><small>仅用于投标前选人；授课日班次、余票、接驳及到场安排仍需确认。</small></div></div>`;
    }
    const reference = dispatchFit?.route_reference;
    if (reference?.status === 'not_required') return '';
    if (reference?.status === 'same_city') return '<div class="teacher-rail-query"><div><b>同城 · 通勤待确认</b><small>' + esc(reference.message) + '</small></div></div>';
    if (reference?.status === 'city_transport_reference' && ['rail', 'air'].includes(reference.eligibility)) {
      const publicFacts = reference.reference_basis === 'public_city_reference';
      const label = (reference.eligibility === 'rail' ? '高铁优先' : '航空备选') + (publicFacts ? ' · 公开通达参考' : ' · 城市交通参考');
      const legs = [['去程', reference.outbound], ['返程', reference.return]].map(([direction, leg]) => {
        if (!leg) return '';
        const bounds = leg.duration_bounds;
        const typed = bounds && ['exact', 'upper_bound', 'open_interval'].includes(bounds.kind) && typeof bounds.original_text === 'string' && bounds.original_text.trim();
        const duration = typed ? bounds.original_text : Number.isFinite(leg.reference_minutes) && leg.reference_minutes > 0 ? `${leg.reference_minutes} 分钟` : '';
        if (!duration) return '';
        const source = /^https:\/\/[^\s<>"']+$/.test(leg.source_url || '') ? `<a href="${esc(leg.source_url)}" target="_blank" rel="noopener noreferrer">查看参考来源 ↗</a>` : '';
        const endpoints = publicFacts ? `<small>${typed ? '城市范围' : '站点范围'}：${esc(leg.from_endpoint || leg.from_city)} → ${esc(leg.to_endpoint || leg.to_city)} · 原发布 ${esc(leg.source_published_on)}</small>` : '';
        const baseline = publicFacts && leg.service_state === 'published_schedule_baseline' ? `<small>已生效运行图公告参考 · 公告实施日期 ${esc(leg.effective_from)}；未核验出行当天运行情况。</small>` : '';
        const precision = typed && bounds.kind !== 'exact' ? `<small>时间范围：${esc(bounds.display || bounds.original_text)}${bounds.inference_notice ? ` · ${esc(bounds.inference_notice)}` : '；不是精确耗时。'}</small>` : '';
        return `<span>${direction}：${esc(leg.from_city)} → ${esc(leg.to_city)} · ${esc(duration)}</span>${endpoints}${baseline}${precision}<small>资料核对 ${esc(leg.research_on)} ${source}</small>`;
      }).join('');
      return `<div class="teacher-rail-query"><div><b>${label}</b>${legs}<small>复核日期 ${esc(reference.review_due_on)} · 参考版本 ${esc(reference.version)}</small>${reference.near_threshold ? '<small>接近范围边界，确定人选前重点复核交通时间。</small>' : ''}<small>用于授课城市初选，不代表授课日班次、余票或到场保证；实际行程仍需确认。</small></div></div>`;
    }
    if (reference?.status === 'city_reference_pending') {
      return `<div class="teacher-rail-query"><div><b>城市交通参考待复核</b><small>当前资料尚不满足自动就近推荐要求，暂不据此判断可达或不可达。</small></div></div>`;
    }
    if (reference?.status === 'air_time_reference') {
      return '<div class="teacher-rail-query"><div><b>航空备选 · 双向直飞小于3小时</b>' + [['去程',reference.outbound],['返程',reference.return]].map(([label,leg]) => {
        if (!leg) return '';
        const source = /^https:\/\/[^\s<>"']+$/.test(leg.source_url || '') ? `<a href="${esc(leg.source_url)}" target="_blank" rel="noopener noreferrer">查看航班来源 ↗</a>` : '';
        return `<span>${label}：${esc(leg.from_airport_code)} → ${esc(leg.to_airport_code)} · ${esc(leg.flight_no)} · ${esc(leg.flight_minutes)} 分钟</span><small>${esc(leg.departure_time)}—${esc(leg.arrival_time)} · 来源日期 ${esc(leg.source_service_date)} · 核对 ${esc(leg.observed_on)} ${source}</small>`;
      }).join('') + `<small>${esc(reference.message)}</small></div></div>`;
    }
    if (reference?.status === 'rail_time_reference') {
      const legs = [['去程', reference.outbound], ['返程', reference.return]];
      return '<div class="teacher-rail-query"><div><b>高铁优先 · 站到站参考</b>' + legs.map(([label, leg]) => {
        if (!leg) return '';
        const link = /^https:\/\/[^\s<>"']+$/.test(leg.source_url || '') ? `<a href="${esc(leg.source_url)}" target="_blank" rel="noopener noreferrer">查看时刻来源 ↗</a>` : '';
        return `<span>${label}：${esc(leg.from_station)} → ${esc(leg.to_station)} · 高铁参考时长 ${esc(leg.rail_minutes)} 分钟</span><small>资料更新于 ${esc(leg.observed_on)} ${link}</small>`;
      }).join('') + `<small>${esc(reference.message || '仅作城市初选参考；授课日班次、接驳和到场仍需确认。')}</small></div></div>`;
    }
    if (reference) {
      const duration = Number(reference.upper_minutes);
      const summary = reference.status === 'reference_available' && Number.isFinite(duration) && duration > 0
        ? '去程门到门参考不超过 ' + Math.round(duration) + ' 分钟' + (Number.isFinite(Number(reference.return_upper_minutes)) && Number(reference.return_upper_minutes) > 0 ? '；返程不超过 ' + Math.round(Number(reference.return_upper_minutes)) + ' 分钟' : '')
        : '跨城交通耗时待核实';
      return '<div class="teacher-rail-query"><div><b>就近调度参考</b><span>' + esc(summary) + '</span><small>' +
        esc(reference.message || '常驻地不代表当前出发地，仍需确认行程衔接。') +
        (reference.checked_on ? ' · 核对日期 ' + esc(reference.checked_on) : '') +
        '</small></div></div>';
    }
    if (!dispatchFit?.rail) return '';
    const rail = dispatchFit.rail;
    const route = [rail.departure_city || '出发地待确认', rail.destination_city || '目的地待确认'].map(esc).join(' → ');
    // Never take a navigation URL from untrusted profile/model/provider output.
    return `<div class="teacher-rail-query"><div><b>铁路行程待核验</b><span>${route}${rail.travel_date ? ` · ${esc(rail.travel_date)}` : ''}</span><small>出发城市暂参考常驻地。尚未连接班次数据，不代表有票或可达。</small></div><a href="https://www.12306.cn/index/" target="_blank" rel="noopener noreferrer">前往 12306 核验${icon('arrow-up-right')}</a></div>`;
  }

  function adjacentScheduleMarkup(rows) {
    if (!Array.isArray(rows) || !rows.length) return '';
    return `<div class="teacher-adjacent-schedule"><b>前后一天已有安排</b><ul>${rows.map((row) => `<li>${esc(row.date || '')} ${esc([row.start_time, row.end_time].filter(Boolean).join('—') || '时段待确认')} · ${esc(row.venue || '场地待确认')} · ${esc(row.status || '')}</li>`).join('')}</ul><small>这些是排课记录，不是实时位置；请核对实际出发地及行程衔接。</small></div>`;
  }

  function requirementInputMarkup(input) {
    if (input?.schema_version !== 'guided_requirement_v1' || !Array.isArray(input.field_ledger)) return '';
    const pending = input.field_ledger.filter((field) => field?.status === 'requires_review');
    return `<p class="recommend-condition-note">已按填写字段保留培训主题与参训对象；培训目标不会被当作讲师已有授课经历。</p>${pending.length ? `<details class="recommend-excluded" open><summary>${icon('info')}补充要求待核对 · ${pending.length} 项</summary><ul>${pending.map((field) => `<li><b>${esc(field.label || '补充要求')}</b><span>${esc(field.raw_value || '')}</span></li>`).join('')}</ul><p>这些内容已保留，尚未完整结构化，核对前不会视为全部满足。</p></details>` : ''}`;
  }

  function recommendationQualificationMatches(value, selected) {
    // This only binds the response to the visible selection; authorization stays on the server.
    if (!selected && value == null) return true;
    if (value?.schema_version !== 'm04_recommendation_qualification_v1') return false;
    if (!selected) return value.selected === false && value.status === 'NOT_SELECTED';
    if (value.selected !== true || !['READY', 'NO_ELIGIBLE', 'NOT_CONFIGURED', 'INVALID_SELECTION'].includes(value.status)) return false;
    const query = value.qualification_context;
    if (query?.schema_version !== 'm04_qualification_context_v1' || query.scope_id !== selected.scope_id || query.version !== selected.expected_version || query.course_code !== selected.course_code || query.as_of !== selected.as_of) return false;
    const normalize = (items) => {
      if (!Array.isArray(items) || !items.every(item => typeof item === 'string' && item.trim())) return null;
      const normalized = items.map(item => item.trim());
      return new Set(normalized).size === normalized.length ? normalized.sort() : null;
    };
    return ['accepted_levels', 'allowed_cities'].every(key => {
      const actual = normalize(query[key]), expected = normalize(selected[key]);
      return actual !== null && expected !== null && JSON.stringify(actual) === JSON.stringify(expected);
    });
  }

  function renderRecommendationQualification(root, value, context) {
    if (!root) return;
    const states = { NOT_SELECTED: '未选择标准课程，未核验课程认证。', READY: '本次已核验具体课程资格；仍需核对原评分与调度条件。', NO_ELIGIBLE: '没有满足本次标准课程条件的在库师资。', NOT_CONFIGURED: '所选课程目录尚未配置。', INVALID_SELECTION: '所选课程当前不能核验。' };
    const known = value && Object.prototype.hasOwnProperty.call(states, value.status);
    root.innerHTML = `<h3>标准课程资格</h3><p role="status">${esc(known ? value.message || states[value.status] : '本次未取得可信课程认证核验结果，请勿将推荐名单视为已认证师资。')}</p><p class="modal-intro">课程认证符合后，仍需核对内容、预算、档期和交通。</p>`;
    if (!known || value.selected !== true) return;
    const query = value.qualification_context;
    if (query) root.insertAdjacentHTML('beforeend', `<p class="modal-intro">课程：${esc(query.course_code)}；判定日期：${esc(query.as_of)}；等级：${esc(Array.isArray(query.accepted_levels) && query.accepted_levels.length ? query.accepted_levels.join('、') : '未附加限制')}；城市：${esc(Array.isArray(query.allowed_cities) && query.allowed_cities.length ? query.allowed_cities.join('、') : '未附加限制')}。</p>`);
    root.insertAdjacentHTML('beforeend', `<p class="modal-intro">本次课程资格符合：${Number.isSafeInteger(value.eligible_count) ? value.eligible_count + ' 人' : '待核实'}；进入原评分范围：${Number.isSafeInteger(value.scoring_pool_count) ? value.scoring_pool_count + ' 人' : '待核实'}。</p>`);
    const names = new Map((context.rows || []).map(row => [String(row.id), row.name]));
    const groups = [['gaps', '课程资格缺口'], ['pool_exclusions', '未进入评分范围'], ['issues', '条件待核对']];
    groups.forEach(([key, title]) => {
      const rows = Array.isArray(value[key]) ? value[key] : [];
      if (!rows.length) return;
      const section = root.ownerDocument.createElement('section'); root.appendChild(section); let page = 0;
      const draw = () => {
        if (!section.isConnected) return;
        const items = rows.slice(page * 20, (page + 1) * 20).map((item, i) => ({ id: i, name: names.get(String(item.teacher_id)) || item.teacher_code || (item.teacher_id ? '师资档案 #' + item.teacher_id : '查询条件'), reason: (Array.isArray(item.reasons) ? item.reasons.map(reason => reason.message || '待核实').join('；') : item.message) || '资格依据待核实' }));
        section.innerHTML = `<h4>${esc(title)} · ${rows.length} 条</h4>${renderTable([{ k: 'name', l: key === 'issues' ? '条件' : '师资' }, { k: 'reason', l: '说明' }], items, [])}<div class="toolbar"><button type="button" class="btn gray" data-qualification-prev ${page === 0 ? 'disabled' : ''}>上一页</button><span>第 ${page + 1} 页</span><button type="button" class="btn gray" data-qualification-next ${(page + 1) * 20 >= rows.length ? 'disabled' : ''}>下一页</button></div>`;
        $('[data-qualification-prev]', section).onclick = () => { if (page > 0) { page--; draw(); } };
        $('[data-qualification-next]', section).onclick = () => { if ((page + 1) * 20 < rows.length) { page++; draw(); } };
      };
      draw();
    });
  }

  function renderRecommendationResults(target, payload, context) {
    const analysis = payload?.analysis || payload?.requirement_analysis || {};
    const dispatchPreferences = analysis.dispatch_preferences || {};
    const semanticStatus = analysis.semantic_matching;
    const recommendations = payload?.recommendations || payload?.results || payload?.candidates || [];
    const selection = dispatchPreferences.selection || {};
    const pendingGroups = [
      ['内容待补证', payload?.review_candidates],
      ['交通待核实', selection.travel_pending],
      ['模型待评分', selection.scoring_pending],
    ];
    const pendingHtml = pendingGroups.map(([title, items]) => !Array.isArray(items) || !items.length ? '' :
      '<details class="recommend-excluded"><summary>' + icon('info') + esc(title) + ' · ' + items.length +
      ' 位（不计入推荐人数）</summary><ul>' + items.map(item => '<li><b>' + esc(item.name || item.teacher_name || '讲师') +
      '</b><span>' + esc(title === '模型待评分' ? '模型尚未返回此讲师的分数，未用规则分混入模型排名；补齐评分后重新比较。' : title === '交通待核实' ? item.dispatch_fit?.nearby_pending_reason || '需确认实际出发地与交通参考后再进入就近范围' :
      (item.gaps || []).filter(gap => String(gap).includes('需求包含') || String(gap).includes('硬性') || String(gap).includes('并列要求')).join('；') || '有相关表述，尚需核对完整课程内容与授课经历') +
      '</span></li>').join('') + '</ul></details>').join('');
    const poolHtml = '<p class="teacher-semantic-status">' + esc(
      selection.pool_size != null ? '本轮比较范围共 ' + selection.pool_size + ' 位' +
      (selection.expanded_upper_minutes ? '，已扩展至门到门参考 ' + selection.expanded_upper_minutes + ' 分钟的城市' : '') +
      (selection.expanded_rail_reference_minutes ? '，已扩展至站到站铁路参考 ' + selection.expanded_rail_reference_minutes + ' 分钟的城市层' : '') +
      (selection.candidate_routes_complete === false ? '；仍有讲师交通范围待核对，当前不是完整范围的最终排名' : '') +
      (selection.model_scoring_complete === false ? selection.scoring_mode === 'model_only' ? '；部分候选待评分，未混入模型排名' : '；模型评分暂不可用，当前仅为文字依据参考顺序' : '') +
      (selection.ties_included ? '；末位同分，额外保留 ' + selection.ties_included + ' 位' : '') : '') + '</p>';
    const groups = [
      ['培训主题', listText(analysis.topics)],
      ['客户行业', listText(analysis.industries)],
      ['授课对象', listText(analysis.audiences)],
      ['专业资历', listText(analysis.credentials)],
    ].filter((group) => group[1].length);
    const conditionFacts = [analysis.expected_date ? `授课日期：${analysis.expected_date}` : '', Number(analysis.hours) > 0 ? `课时：${num(analysis.hours)}` : '', Number(analysis.max_fee_rate) > 0 ? `课酬上限：¥ ${money(analysis.max_fee_rate)}/课时` : '', dispatchPreferences.training_city ? `授课地区：${dispatchPreferences.training_province} · ${dispatchPreferences.training_city}` : '', dispatchPreferences.training_mode ? `方式：${dispatchPreferences.training_mode}` : '', dispatchPreferences.training_start_time ? `时段：${dispatchPreferences.training_start_time}—${dispatchPreferences.training_end_time}` : dispatchPreferences.training_period ? `时段：${dispatchPreferences.training_period}` : ''].filter(Boolean);
    const shortfall = Number(payload?.shortfall ?? Math.max(0, 3 - recommendations.length));
    const pendingResidence = Number(payload?.residence_pending_count || 0);
    const shortageHtml = shortfall > 0 || pendingResidence > 0 ? `<div class="recommend-shortage" role="status">${icon('users-round')}<div><b>${shortfall > 0 ? `找到 ${recommendations.length} 位相关候选，距 3 位目标还缺 ${shortfall} 位` : `已找到 ${recommendations.length} 位相关候选`}</b><p>${shortfall > 0 ? '请检查下方内容待补证、交通待核实及排除原因；不会用无关、冲突或超出硬预算的老师凑数。' : ''}${pendingResidence > 0 ? ` 其中 ${pendingResidence} 位常驻地区未补齐，仍是待补资料候选，不能视为调度已核实。` : ''}</p></div></div>` : '';
    const excluded = Array.isArray(payload?.excluded) ? payload.excluded : [];
    const excludedHtml = excluded.length ? `<details class="recommend-excluded" ${recommendations.length ? '' : 'open'}><summary>${icon('calendar-x')}已排除 ${excluded.length} 位候选，查看原因</summary><ul>${excluded.map((item) => `<li><b>${esc(item.teacher_name || item.name || '讲师')}</b><span>${esc(item.reason || '当前条件不适合，请进一步确认')}</span></li>`).join('')}</ul></details>` : '';
    const analysisHtml = `<section class="recommend-analysis"><header class="recommend-profile-head"><h2>${icon('scan-search')}需求画像</h2><span>请核对识别结果</span></header>${groups.length ? `<dl class="recommend-profile-grid">${groups.map(([label, values]) => `<div><dt>${esc(label)}</dt><dd>${values.map((value) => esc(value)).join(' · ')}</dd></div>`).join('')}</dl>` : `<p class="recommend-profile-empty">${esc(analysis.summary || '暂未识别到明确专业条件，请补充培训主题与参训对象。')}</p>`}${conditionFacts.length ? `<p class="recommend-condition-facts">${conditionFacts.map((fact) => `<span>${esc(fact)}</span>`).join('')}</p>` : ''}${requirementInputMarkup(analysis.requirement_input)}<p class="recommend-condition-note">地点、差旅及特殊安排仍需人工确认。</p></section>`;
    if (!recommendations.length) {
      target.innerHTML = `<section class="card" data-recommend-qualification></section>${analysisHtml}${shortageHtml}${poolHtml}${pendingHtml}${excludedHtml}<div class="recommend-empty">${icon('user-round-search')}<b>本轮推荐名单暂为空</b><p>${esc(payload?.notice || '请核对待补证或交通待核实名单；当前不会用资料不全的候选凑满三位。')}</p></div>`;
      renderRecommendationQualification($('[data-recommend-qualification]', target), payload?.course_qualification, context);
      refreshIcons(target);
      return;
    }
    const teacherById = new Map(context.rows.map((teacher) => [String(teacher.id), teacher]));
    const cards = recommendations.map((item, index) => {
      const teacher = item.teacher || teacherById.get(String(item.teacher_id)) || {};
      const dispatchFit = item.dispatch_fit || {};
      const name = item.teacher_name || teacher.name || '候选讲师';
      const rawScore = item.model_score ?? item.match_score ?? item.score;
      const score = typeof rawScore === 'number' && Number.isFinite(rawScore) ? recommendationPercent(rawScore) : null;
      const reasons = listText(item.reasons || item.match_reasons);
      const gaps = listText(item.gaps || item.risks);
      const evidence = recommendationEvidence(item.evidence || item.resume_evidence);
      const verified = teacherSystemMetrics(item.teacher_id || teacher.id, [], [], item.system_metrics || { ...item.performance, evaluation_score: item.performance?.average_evaluation });
      const hasResume = Boolean(item.resume_id || item.resume_status);
      const resumeLabel = hasResume ? resumeStatusTag({ parse_status: item.resume_status || '待确认' }) : tag('仅基础档案');
      const breakdown = Array.isArray(item.score_breakdown)
        ? item.score_breakdown.map((entry, i) => [entry.label || entry.name || `维度 ${i + 1}`, entry.score ?? entry.value])
        : Object.entries(item.score_breakdown || {});
      return `<article class="teacher-match-card ${index === 0 ? 'is-top' : ''}">
        <div class="teacher-match-main">
          <header><span class="person-avatar large">${esc(name.slice(-2))}</span><div><h3>${esc(name)} ${resumeLabel}</h3><p>${esc(item.org || teacher.org || '单位待补充')} · ${esc(item.title || teacher.title || '讲师')}</p><small>${esc(item.field || teacher.field || '专业领域待补充')}</small></div><div class="teacher-match-score" aria-label="${score === null ? '模型未评分' : `模型匹配分 ${score}，满分 100`}"><b>${score === null ? '—' : score}${score === null ? '' : '<em>/ 100</em>'}</b><small>${score === null ? '模型未评分' : '模型匹配分'}</small></div></header>
          <div class="teacher-match-facts"><span>常驻 <b>${esc(teacherResidenceText(item))}</b></span><span>就近优先 <b>${typeof dispatchFit.priority_score === 'number' ? `${dispatchFit.priority_score} 分 · ${esc(dispatchFit.priority_label)}` : dispatchFit.time_score_applicable === false ? esc(dispatchFit.priority_label || '铁路近程范围') : '未启用 / 待核实'}</b></span><span>讲师等级 <b>${esc(item.teacher_level || teacher.teacher_level || '待补充')}</b></span><span>课酬 <b>${teacherFeeRateText(item.fee_rate ?? teacher.fee_rate)}</b></span><span>系统已完成 <b>${num(verified.completedSessions)} 场</b></span><span>授课评价 <b>${verified.evaluationCount ? `${verified.evaluationAverage.toFixed(2)} / 5` : '暂无记录'}</b></span></div>
          <details class="teacher-dispatch-fit"><summary>${icon('map-pin')}<b>${esc(dispatchFit.label || '调度待核实')}</b><span>${dispatchFit.arrival_day_conflict ? '提前到达日有授课记录，需核对衔接' : dispatchFit.arrival_day_before ? '异地上午课 · 提前一天到达待核实' : '查看调度核对事项'}</span>${icon('chevron-down')}</summary><ul>${listText(dispatchFit.notes).map((note) => `<li>${esc(note)}</li>`).join('')}</ul>${adjacentScheduleMarkup(dispatchFit.adjacent_schedule)}${railVerificationMarkup(dispatchFit)}</details>
          <div class="teacher-match-detail">
            <section class="match-reasons"><b>${icon('badge-check')}推荐理由</b>${reasons.length ? `<ul>${reasons.map((reason) => `<li>${esc(reason)}</li>`).join('')}</ul>` : '<p>暂无细分理由</p>'}</section>
            <section class="match-gaps"><b>${icon('triangle-alert')}缺口与待确认</b>${gaps.length ? `<ul>${gaps.map((gap) => `<li>${esc(gap)}</li>`).join('')}</ul>` : '<p>未发现明显缺口</p>'}</section>
          </div>
          <details class="teacher-match-audit"><summary>${icon('chart-no-axes-column')}匹配评分与授课记录${icon('chevron-down')}</summary>
            ${!item.score_source && breakdown.length ? `<div class="teacher-score-breakdown">${breakdown.map(([label, value]) => { const pct = recommendationPercent(value); const displayLabel = item.score_breakdown_details?.[label]?.label || breakdownLabel(label); return `<div><span><small>${esc(displayLabel)}</small><b>${pct}%</b></span><i><em style="width:${pct}%"></em></i></div>`; }).join('')}</div>` : ''}
            <section class="teacher-verified-proof"><div><span>${icon('shield-check')}系统履约记录</span><small>仅统计本系统已完成课程与已提交评价</small></div><dl><div><dt>已完成场次</dt><dd>${num(verified.completedSessions)}</dd></div><div><dt>已完成课时</dt><dd>${num(verified.completedHours)}</dd></div><div><dt>评价均分</dt><dd>${verified.evaluationCount ? `${verified.evaluationAverage.toFixed(2)} <small>/ 5 分 · ${num(verified.evaluationCount)} 份</small>` : '暂无评价'}</dd></div></dl></section>
          </details>
          ${Array.isArray(item.requirement_coverage) && item.requirement_coverage.length ? `<details class="teacher-evidence"><summary>${icon('list-checks')}逐项内容依据</summary><ul>${item.requirement_coverage.map(part => `<li><b>${esc(part.criterion)}</b> · ${part.status === 'source_supported' ? '原文有依据' : '待补证'}<p>${esc(part.evidence || '尚未找到明确依据')}</p></li>`).join('')}</ul></details>` : ''}
          ${item.recommendation_score_rule ? `<details class="teacher-evidence"><summary>${icon('chart-no-axes-column')}匹配分计算规则</summary><p>${esc(item.recommendation_score_rule)}</p><ul>${Object.entries(item.recommendation_score_parts || {}).map(([label, value]) => `<li>${esc(label)}：${Number(value).toFixed(1)}</li>`).join('')}</ul></details>` : ''}
          ${semanticEvidenceMarkup(item.semantic)}
          ${resumeClaimFacts(item.resume_claims).length ? `<details class="teacher-evidence"><summary>${icon('badge-info')}查看简历自述数据</summary><div class="recommend-resume-claims">${resumeClaimMarkup(item.resume_claims)}</div></details>` : ''}
          ${evidence.length ? `<details class="teacher-evidence"><summary>${icon('file-search')}查看简历自述依据 <span>${evidence.length}</span></summary><div class="resume-claim-note">简历中的课时、满意度和客户案例属于讲师资料自述，不计入上方系统履约记录。</div><ul>${evidence.map((item) => `<li>${esc(item)}</li>`).join('')}</ul></details>` : ''}
          <footer>${!dispatchFit.residence_complete ? `<button type="button" class="btn gray" data-recommend-residence="${esc(item.teacher_id || teacher.id || '')}">${icon('map-pin')}补充常驻地区</button>` : ''}${hasResume ? `<button type="button" class="btn gray" data-view-recommend-resume="${esc(item.teacher_id || teacher.id || '')}">${icon('file-search')}简历与画像</button>` : ''}<button type="button" class="btn gray" data-view-recommend-teacher="${esc(item.teacher_id || teacher.id || '')}">${icon('contact-round')}档案与授课记录</button></footer>
        </div>
      </article>`;
    }).join('');
    target.innerHTML = `<section class="card" data-recommend-qualification></section>${analysisHtml}${shortageHtml}${poolHtml}${semanticStatus?.message ? `<p class="teacher-semantic-status" role="status">${icon('scan-search')}${esc(semanticStatus.message)}</p>` : ''}<div class="recommend-results-head"><h2>推荐候选 <em>${recommendations.length}</em></h2><small>${esc(dispatchPreferences.ranking_policy || '按资料匹配程度排序')}</small></div><div class="teacher-match-list">${cards}</div>${pendingHtml}${excludedHtml}<div class="recommend-notice">${icon('info')}<span>本名单用于投标前选师资，不表示老师已确认授课。匹配分不是胜任概率；无交通记录不能判断可达，系统不会自动建项目、排课或产生课酬。</span></div>`;
    renderRecommendationQualification($('[data-recommend-qualification]', target), payload?.course_qualification, context);
    $$('[data-recommend-residence]', target).forEach((button) => { button.onclick = () => {
      const teacher = teacherById.get(String(button.dataset.recommendResidence));
      if (!teacher) return;
      openModal(`常驻地区 · ${teacher.name}`, renderForm(residenceFields(), teacher), { onOk: async () => {
        const data = collectForm($('#modal-mask'), residenceFields()); if (!data) return false;
        await api('/teachers/residence', { body: { id: teacher.id, ...data } });
        invalidateTeacherRecommendations(); toast('地区已保存，请重新匹配更新顺序'); renderPage();
      } });
    }; });
    $$('[data-view-recommend-resume]', target).forEach((button) => {
      button.onclick = () => {
        const teacherId = button.dataset.viewRecommendResume;
        openResumeDetails({ teacher_id: teacherId }, teacherById.get(String(teacherId)));
      };
    });
    $$('[data-view-recommend-teacher]', target).forEach((button) => {
      button.onclick = () => {
        const teacher = teacherById.get(String(button.dataset.viewRecommendTeacher));
        if (teacher) showTeacherEvals(teacher, context.projects, context.dispatches);
        else toast('该讲师完整档案暂不可用', true);
      };
    });
    refreshIcons(target);
  }

  function renderTeacherRecommendation(root, context) {
    if (!canWrite()) {
      root.innerHTML = `<div class="recommend-permission-card"><span>${icon('lock-keyhole')}</span><h2>智能推荐仅向授权运营人员开放</h2><p>只读账号可以查看师资基础档案，但不能访问讲师简历、解析结果或执行智能推荐。</p></div>`;
      return;
    }
    const form = state.teacherRecommendationForm;
    if (form.demandId && !context.demands.some((demand) => String(demand.id) === String(form.demandId))) {
      form.demandId = '';
      invalidateTeacherRecommendations();
    }
    invalidateTeacherRecommendations();
    const identity = state.user, tabController = new AbortController();
    let alive = true, courseSelection = null, priorityController = null;
    const current = () => alive && !tabController.signal.aborted && state.user === identity && state.teacherTab === 'recommend' && isRouteCurrent(context.epoch, context.c, 'teachers') && root.isConnected;
    let inputMode = form.inputMode || (state.teacherRequirementDraft ? 'raw' : 'guided');
    let rawInitialized = form.rawInitialized ?? (inputMode === 'raw');
    const guided = form.guided || {};
    const logisticsFields = [
      { k: 'training_province', label: '授课省份 / 地区', type: 'select', options: [{ v: '', l: '待确定' }, ...REGION_PROVINCES] },
      { k: 'training_city', label: '授课城市 / 地区', regionProvinceKey: 'training_province', placeholder: '选择省份后输入或选择城市' },
      { k: 'training_mode', label: '授课方式', type: 'select', options: ['待定', '线下', '线上'], value: '待定' },
      { k: 'training_period', label: '授课时段', type: 'select', options: ['待定', '上午', '下午', '全天'], value: '待定' },
      { k: 'training_start_time', label: '开始时间（选填）', type: 'time', hint: '填写日期和起止时间后，可按实际时段检查冲突。' },
      { k: 'training_end_time', label: '结束时间（选填）', type: 'time', hint: '未填写具体时间时，仍按整日检查；时间不冲突不等于交通可达。' },
    ];
    const guidedField = (key, label, placeholder, limit = 200, type = 'text') => `<div class="form-item"><label for="recommend-guide-${key}">${esc(label)}${key === 'topic' ? '<span class="req">*</span>' : ''}</label><input id="recommend-guide-${key}" data-recommend-guide="${key}" type="${type}" maxlength="${limit}" value="${esc(guided[key] || '')}" placeholder="${esc(placeholder)}" ${key === 'topic' ? 'aria-required="true" aria-describedby="recommend-topic-error"' : ''}>${key === 'topic' ? '<span class="field-error" id="recommend-topic-error" aria-live="polite"></span>' : ''}</div>`;
    root.innerHTML = `<div class="teacher-recommend-workbench">
      <section class="recommend-input-card">
        <div class="recommend-section-head"><div><h2>培训需求简报</h2><small>投标前准备 · 推荐师资 → 客户选师资 → 投标立项</small></div></div>
        <div class="form-item recommend-import"><label for="recommend-demand">带入已有需求</label><select id="recommend-demand" aria-describedby="recommend-demand-help"><option value="">直接填写，或选择一条培训需求</option>${context.demands.map((demand) => `<option value="${esc(demand.id)}" ${String(form.demandId) === String(demand.id) ? 'selected' : ''}>#R-${String(demand.id).padStart(4, '0')}｜${esc(demand.title)}｜${esc(demand.unit || '单位待补充')}</option>`).join('')}</select><small id="recommend-demand-help">带入后可编辑，以当前文字为准。</small></div>
        <div class="recommend-entry-modes" role="group" aria-label="需求填写方式"><button type="button" data-recommend-mode="guided" aria-pressed="${inputMode === 'guided'}" aria-controls="recommend-guided">填写要点</button><button type="button" data-recommend-mode="raw" aria-pressed="${inputMode === 'raw'}" aria-controls="recommend-raw">粘贴客户原话</button><small>两种草稿分别保留，以当前方式匹配。</small></div>
        <div id="recommend-guided" ${inputMode === 'guided' ? '' : 'hidden'}>
          <p class="recommend-guide-help">仅培训主题必填，其余信息可稍后补充。</p>
          <div class="recommend-guide-grid">
            ${guidedField('topic', '培训主题', '例如：客户投诉处理与服务礼仪')}
            ${guidedField('audience', '参训对象', '例如：网点负责人、一线员工')}
            ${guidedField('unit', '客户单位 / 行业', '例如：某商业银行 / 金融行业')}
            ${guidedField('goals', '培训目标', '例如：提升投诉沟通能力，掌握实用话术', 1600)}
          </div>
          <details class="recommend-guide-more" ${guided.date || guided.hours || guided.preference || guided.extra ? 'open' : ''}><summary>补充时间与讲师偏好<span>选填</span>${icon('chevron-down')}</summary><div class="recommend-guide-grid">
            ${guidedField('date', '计划授课日期', '', 80, 'date')}
            <div class="recommend-hours-field">${guidedField('hours', '预计课时', '例如：6，未确定可留空', 32, 'number')}<span class="field-error" id="recommend-hours-error" aria-live="polite"></span></div>
            ${guidedField('preference', '讲师经验 / 授课偏好', '例如：有银行授课经历、擅长案例演练', 1200)}
            ${guidedField('extra', '其他要求', '例如：地点、线上或线下、授课风格', 1200)}
          </div></details>
          <details class="recommend-brief-preview"><summary>查看整理后的需求${icon('chevron-down')}</summary><p id="recommend-brief-text"></p></details>
        </div>
        <div class="form-item recommend-requirement-field" id="recommend-raw" ${inputMode === 'raw' ? '' : 'hidden'}><label for="recommend-requirement">客户原话<span class="req">*</span></label><textarea id="recommend-requirement" maxlength="10000" aria-describedby="recommend-requirement-help recommend-requirement-error" placeholder="直接粘贴客户的消息，也可以补充或修改。">${esc(form.rawDraft ?? state.teacherRequirementDraft ?? '')}</textarea><small id="recommend-requirement-help">将按这段文字匹配，不叠加另一种方式的草稿。</small><span class="field-error" id="recommend-requirement-error" aria-live="polite"></span></div>
        <section class="recommend-logistics" aria-labelledby="recommend-logistics-title"><div class="recommend-logistics-heading"><h3 id="recommend-logistics-title">授课地点与调度</h3><label><input type="checkbox" id="recommend-prefer-local" ${form.logistics?.prefer_local !== false ? 'checked' : ''}>先就近，再选匹配前三</label></div><div id="recommend-logistics-fields">${renderForm(logisticsFields, form.logistics)}</div><details class="recommend-city-presets"><summary>查看机构与高铁时间优先级${icon('chevron-down')}</summary><div class="form-item"><label for="recommend-organization">机构城市参考（选填）</label><select id="recommend-organization"><option value="">正在读取机构名单…</option></select><small id="recommend-organization-note">以本次实际授课城市为准；机构选择不会覆盖已填写地点。</small><button type="button" class="btn gray" id="recommend-apply-organization" disabled>带入参考城市</button></div><div id="recommend-priority-table" aria-live="polite">读取高铁时间参考…</div></details><small>同城及所有铁路小于4小时的合格老师统一比较；铁路不足时再看经完整核对的直飞小于3小时备选。同分看等级，末位同分全留。接驳和值机另行确认，不按公里数估算。</small></section>
        <section class="recommend-logistics recommend-course" aria-labelledby="recommend-course-title"><h3 id="recommend-course-title">标准课程资格（选填）</h3><div id="recommend-course"><p class="modal-intro">正在读取可选课程；未选择标准课程时，课程认证未核验。</p></div></section>
        <details class="recommend-settings" id="recommend-settings" ${form.maxFeeRate || form.hardBudget ? 'open' : ''}><summary>${icon('sliders-horizontal')}筛选条件<small id="recommend-settings-summary"></small>${icon('chevron-down')}</summary><div class="recommend-settings-grid">
          <div class="form-item recommend-budget"><label for="recommend-max-fee">最高课酬（元/课时）</label><input id="recommend-max-fee" type="number" min="0" step="0.01" inputmode="decimal" value="${esc(form.maxFeeRate)}" placeholder="留空表示不限" aria-describedby="recommend-budget-help recommend-budget-error"><small id="recommend-budget-help">按每课时金额筛选，不是总项目预算。</small><label class="recommend-budget-toggle"><input id="recommend-hard-budget" type="checkbox" ${form.hardBudget ? 'checked' : ''}><span>严格排除超出课酬上限的讲师</span></label><span class="field-error" id="recommend-budget-error" aria-live="polite"></span></div>
          <div class="form-item recommend-input-options"><label for="recommend-count">推荐规则</label><select id="recommend-count" disabled><option value="3">匹配前三 · 第三名同分全部保留</option></select><small>从就近范围内所有合格讲师中比较；不足三位会提示原因，不用无关讲师补位。</small></div>
        </div></details>
        <div class="recommend-submit-row"><p id="recommend-status" role="status">请开始新的匹配，重新核对当前资料与课程资格。</p><button type="button" class="btn recommend-run" id="recommend-run">${icon('arrow-right')}开始匹配讲师</button></div>
        <p class="recommend-input-foot">${icon('shield-check')}专业档案在本地匹配，语义辅助不替代资历核验。铁路行程仍需官方核验；推荐不会自动立项或安排授课。</p>
      </section>
      <section class="recommend-output" id="recommend-output" aria-label="讲师推荐结果" aria-live="polite" tabindex="-1" hidden></section>
    </div>`;
    const demandSelect = $('#recommend-demand', root);
    const logisticsRoot = $('#recommend-logistics-fields', root);
    const logisticsInputs = $$('[data-k]', logisticsRoot);
    const preferLocal = $('#recommend-prefer-local', root);
    const readLogistics = () => ({ ...Object.fromEntries(logisticsInputs.map((input) => [input.dataset.k, input.value])), prefer_local: preferLocal.checked });
    const requirement = $('#recommend-requirement', root);
    const guideInputs = $$('[data-recommend-guide]', root);
    const hoursInput = $('#recommend-guide-hours', root);
    hoursInput.min = '0'; hoursInput.step = 'any'; hoursInput.inputMode = 'decimal';
    hoursInput.setAttribute('aria-describedby', 'recommend-hours-error');
    const modeButtons = $$('[data-recommend-mode]', root);
    const readGuided = () => Object.fromEntries(guideInputs.map((input) => [input.dataset.recommendGuide, input.value]));
    const currentRequirement = () => inputMode === 'guided' ? guidedRequirementText(readGuided()) : requirement.value;
    const updateBrief = () => { $('#recommend-brief-text', root).textContent = guidedRequirementText(readGuided()) || '填写上方要点后，这里会自动整理。不确定的信息可以留空。'; };
    const displayMode = () => {
      $('#recommend-guided', root).hidden = inputMode !== 'guided';
      $('#recommend-raw', root).hidden = inputMode !== 'raw';
      modeButtons.forEach((button) => button.setAttribute('aria-pressed', String(button.dataset.recommendMode === inputMode)));
    };
    updateBrief(); displayMode();
    const output = $('#recommend-output', root);
    const count = $('#recommend-count', root);
    const maxFee = $('#recommend-max-fee', root);
    const hardBudget = $('#recommend-hard-budget', root);
    const settings = $('#recommend-settings', root);
    const status = $('#recommend-status', root);
    const updateSettingsSummary = () => {
      const fee = Number(maxFee.value);
      $('#recommend-settings-summary', root).textContent = `${maxFee.value && fee > 0 ? `¥ ${money(fee)}/课时${hardBudget.checked ? ' · 严格限制' : ''}` : '课酬不限'} · 目标 ${count.value} 位`;
    };
    updateSettingsSummary();
    let activeRequest = null;
    const persistForm = () => {
      state.teacherRequirementDraft = currentRequirement();
      state.teacherRecommendationForm = { demandId: demandSelect.value, maxResults: count.value, maxFeeRate: maxFee.value, hardBudget: hardBudget.checked, inputMode, guided: readGuided(), rawDraft: requirement.value, rawInitialized, logistics: readLogistics(), standardCourse: courseSelection?.getDraft() || form.standardCourse || { enabled: false } };
    };
    const markRecommendationDirty = () => {
      persistForm();
      invalidateTeacherRecommendations();
      if (!output.hidden) status.textContent = '条件已更新，请重新匹配。';
      output.hidden = true;
      output.innerHTML = '';
      updateSettingsSummary();
    };
    const organizationSelect = $('#recommend-organization', root);
    const organizationNote = $('#recommend-organization-note', root);
    const organizationApply = $('#recommend-apply-organization', root);
    const priorityTable = $('#recommend-priority-table', root);
    const provinceInput = logisticsInputs.find(input => input.dataset.k === 'training_province');
    const cityInput = logisticsInputs.find(input => input.dataset.k === 'training_city');
    let organizationRows = [], priorityRequest = 0;
    const updateOrganizationState = () => {
      const organization = organizationSelect.value === '' ? null : organizationRows[Number(organizationSelect.value)];
      organizationApply.disabled = !organization || !organization.province || !organization.city || /conflict|uncertain|withdrawn|inactive/.test(organization.status || '') || Boolean(provinceInput.value || cityInput.value);
      organizationNote.textContent = organization ? `${organization.city ? `${organization.province} · ${organization.city}。` : '城市待确认。'}${organization.note || ''}以本次实际授课城市为准；已有地点不会被覆盖。` : '以本次实际授课城市为准；机构选择不会覆盖已填写地点。';
    };
    organizationSelect.onchange = () => { updateOrganizationState(); markRecommendationDirty(); };
    const loadPriorities = async () => {
      const ticket = ++priorityRequest; priorityController?.abort(); priorityController = new AbortController(); const request = priorityController;
      updateOrganizationState();
      try {
        const catalog = await api('/teacher-resumes/dispatch-priorities?' + new URLSearchParams({ province: provinceInput.value, city: cityInput.value, date: $('#recommend-guide-date', root).value }), { quiet: true, signal: request.signal });
        if (!current() || ticket !== priorityRequest || request.signal.aborted || !priorityTable.isConnected) return;
        if (!organizationRows.length) {
          organizationRows = Array.isArray(catalog.organizations) ? catalog.organizations : [];
          organizationSelect.innerHTML = '<option value="">选择机构，仅作城市参考</option>' + organizationRows.map((row, i) => `<option value="${i}">${esc(row.name)}${!row.city ? ' · 城市待确认' : ''}</option>`).join('');
          updateOrganizationState();
        }
        const priorities = Array.isArray(catalog.priorities) ? catalog.priorities : [];
        priorityTable.innerHTML = `<p>${esc(catalog.rule || '交通优先表暂不可用')}</p><p>城市清单 ${esc(catalog.enumerated_count || 0)} / ${esc(catalog.universe_count || 0)} · 已有结论 ${esc(catalog.resolved_count || 0)} · 待核对 ${esc(catalog.unknown_count || 0)}。${catalog.transport_scope_complete ? '当前目录范围已核对。' : '清单已列全不等于交通已查全，不能把未知城市当作不符合。'}</p>` + (priorities.length ? `<dl class="recommend-priority-bands">${[0, 1, 2, 3, 4, null].map(tier => {
          const rows = priorities.filter(row => row.tier === tier);
          const band = tier === null ? '铁路近程范围 · 双向小于4小时，按专业匹配比较' : `${[100, 85, 70, 55, 40][tier]} 分 · ${['同城', '铁路参考 ≤ 2 小时', '铁路参考 ≤ 3 小时', '铁路参考 < 4 小时', '航空备选 · 直飞 < 3 小时'][tier]}`;
          return rows.length ? `<div><dt>${band}</dt><dd>${rows.map(row => `<div><b>${esc(row.province)}·${esc(row.city)}</b>${row.outbound ? `<details><summary>${row.duration_representation === 'typed_city_bounds' ? '双向铁路时间范围' : `去程 ${esc(row.outbound_reference_minutes)} 分钟 / 返程 ${esc(row.return_reference_minutes)} 分钟`} · 查看依据</summary>${railVerificationMarkup({route_reference: row})}</details>` : ''}</div>`).join('')}</dd></div>` : '';
        }).join('')}</dl>` : '<p>请先选择实际授课省份和城市；未覆盖的地区需人工核对。</p>') +
          (catalog.pending_routes?.length ? `<details><summary>路线待补查 · ${catalog.pending_routes.length} 个城市</summary><p>${catalog.pending_routes.map(row => esc(row.city)).join('、')}</p><small>未查到、只有慢车样本、过期或缺返程均不算查完；也不直接转为航空备选。</small></details>` : '') +
          (catalog.outside_nearby_routes?.length ? `<details><summary>已核对不满足交通范围 · ${catalog.outside_nearby_routes.length} 个城市</summary>${catalog.outside_nearby_routes.map(row => `<p><b>${esc(row.city)}</b> ${esc(row.message)}</p>`).join('')}</details>` : '') +
          `<small>${esc(catalog.notice || '')} ${esc(catalog.attribution || '')}</small>`;
      } catch (error) {
        if (!current() || ticket !== priorityRequest || request.signal.aborted || error?.name === 'AbortError' || !priorityTable.isConnected) return;
        organizationSelect.innerHTML = '<option value="">机构名单暂不可用</option>';
        priorityTable.textContent = '高铁时间优先表暂不可用，请稍后重试；不会据此假定交通可达。';
      }
    };
    organizationApply.onclick = () => {
      const organization = organizationSelect.value === '' ? null : organizationRows[Number(organizationSelect.value)];
      if (organizationApply.disabled || !organization || provinceInput.value || cityInput.value) return;
      provinceInput.value = organization.province;
      refreshRegionSuggestions(provinceInput, true);
      cityInput.value = organization.city;
      markRecommendationDirty(); loadPriorities();
    };
    loadPriorities();
    guideInputs.forEach((input) => { input.oninput = () => {
      if (input.dataset.recommendGuide === 'topic') {
        $('#recommend-topic-error', root).textContent = '';
        $('#recommend-guide-topic', root).removeAttribute('aria-invalid');
      }
      if (input.dataset.recommendGuide === 'hours') { $('#recommend-hours-error', root).textContent = ''; hoursInput.removeAttribute('aria-invalid'); }
      updateBrief(); markRecommendationDirty();
      if (input.dataset.recommendGuide === 'date') loadPriorities();
    }; });
    logisticsInputs.forEach((input) => { input.oninput = () => { markRecommendationDirty(); updateOrganizationState(); }; input.onchange = () => { markRecommendationDirty(); loadPriorities(); }; });
    preferLocal.onchange = markRecommendationDirty;
    modeButtons.forEach((button) => { button.onclick = () => {
      if (button.dataset.recommendMode === inputMode) return;
      if (button.dataset.recommendMode === 'raw' && !rawInitialized) { requirement.value = guidedRequirementText(readGuided()); rawInitialized = true; }
      inputMode = button.dataset.recommendMode;
      displayMode(); markRecommendationDirty();
    }; });
    demandSelect.onchange = () => {
      const demand = context.demands.find((item) => String(item.id) === demandSelect.value);
      if (demand) {
        requirement.value = demandRequirementText(demand);
        rawInitialized = true;
        const imported = demandGuidedFields(demand);
        logisticsInputs.forEach((input) => { input.value = demand[input.dataset.k] || (['training_mode', 'training_period'].includes(input.dataset.k) ? '待定' : ''); });
        refreshRegionSuggestions(logisticsInputs.find((input) => input.dataset.k === 'training_province'), true);
        guideInputs.forEach((input) => { input.value = imported[input.dataset.recommendGuide] || ''; });
        if (imported.date || imported.hours || imported.preference || imported.extra) $('.recommend-guide-more', root).open = true;
        $('#recommend-requirement-error', root).textContent = '';
        requirement.removeAttribute('aria-invalid');
        $('#recommend-topic-error', root).textContent = '';
        $('#recommend-guide-topic', root).removeAttribute('aria-invalid');
        $('#recommend-hours-error', root).textContent = ''; hoursInput.removeAttribute('aria-invalid');
        updateBrief();
        (inputMode === 'guided' ? $('#recommend-guide-topic', root) : requirement).focus();
      }
      markRecommendationDirty(); loadPriorities();
    };
    requirement.oninput = () => { rawInitialized = true; $('#recommend-requirement-error', root).textContent = ''; requirement.removeAttribute('aria-invalid'); markRecommendationDirty(); };
    count.onchange = markRecommendationDirty;
    maxFee.oninput = () => { $('#recommend-budget-error', root).textContent = ''; markRecommendationDirty(); };
    hardBudget.onchange = () => { $('#recommend-budget-error', root).textContent = ''; markRecommendationDirty(); };
    const destroy = () => {
      if (!alive) return; alive = false; tabController.abort(); priorityController?.abort(); courseSelection?.destroy();
      if (activeRequest) { activeRequest.abort(); if (teacherRecommendationController === activeRequest) cancelTeacherRecommendation(); }
      output.innerHTML = ''; output.hidden = true;
    };
    context.stopTeacherTabPolling = destroy;
    addRouteCleanup(destroy, context.epoch);
    (async () => {
      try {
        const { mountRecommendationCourse } = await import('/modules/course-catalog/recommendation.js?v=20260922recommend1');
        if (!current()) return;
        courseSelection = mountRecommendationCourse($('#recommend-course', root), { api, renderForm, esc, draft: form.standardCourse, signal: tabController.signal, isCurrent: current, onChange: markRecommendationDirty });
        await courseSelection.ready;
      } catch (error) {
        if (current() && error?.name !== 'AbortError') $('#recommend-course', root).innerHTML = '<p class="modal-intro">标准课程选择暂不可用；不选标准课程时可继续原推荐，但课程认证未核验。</p>';
      }
    })();
    $('#recommend-run', root).onclick = async () => {
      if (!current()) return;
      $('#recommend-requirement-error', root).textContent = '';
      $('#recommend-budget-error', root).textContent = '';
      const text = currentRequirement().trim();
      if (inputMode === 'guided' && !$('#recommend-guide-topic', root).value.trim()) {
        $('#recommend-topic-error', root).textContent = '请填写培训主题，例如：客户服务。';
        $('#recommend-guide-topic', root).setAttribute('aria-invalid', 'true');
        $('#recommend-guide-topic', root).focus(); return;
      }
      if (!text) { $('#recommend-requirement-error', root).textContent = '请填写客户培训需求'; requirement.setAttribute('aria-invalid', 'true'); requirement.focus(); return; }
      if (inputMode === 'guided' && (hoursInput.validity.badInput || (hoursInput.value.trim() && (!Number.isFinite(Number(hoursInput.value)) || Number(hoursInput.value) <= 0)))) {
        $('.recommend-guide-more', root).open = true;
        $('#recommend-hours-error', root).textContent = '课时应为大于 0 的数字，未确定可留空。';
        hoursInput.setAttribute('aria-invalid', 'true'); hoursInput.focus(); return;
      }
      if (text.length > 10000) { status.textContent = '需求内容超过 10000 字，请精简后重试。'; return; }
      const logistics = collectForm(logisticsRoot, logisticsFields);
      if (!logistics) return;
      if (Boolean(logistics.training_province) !== Boolean(logistics.training_city)) {
        status.textContent = '授课省份和城市请一起填写；地点未确定时可以都留空。';
        $('[data-k="' + (logistics.training_province ? 'training_city' : 'training_province') + '"]', logisticsRoot).focus(); return;
      }
      const feeValue = maxFee.value.trim();
      const fee = Number(feeValue);
      if (maxFee.validity.badInput || (feeValue && (!Number.isFinite(fee) || fee <= 0))) {
        settings.open = true; $('#recommend-budget-error', root).textContent = '最高课酬应为大于 0 的金额，或留空不限制'; maxFee.focus(); return;
      }
      if (hardBudget.checked && !feeValue) {
        settings.open = true; $('#recommend-budget-error', root).textContent = '使用严格限制前，请先填写最高课酬'; maxFee.focus(); return;
      }
      let standardCourse;
      try {
        if (!courseSelection && form.standardCourse?.enabled === true) throw new Error('标准课程选择暂不可用，请重新进入此表单核对后再推荐。');
        standardCourse = courseSelection?.getSelection();
      } catch (error) { status.textContent = error.message; return; }
      persistForm();
      cancelTeacherRecommendation();
      const requestSequence = teacherRecommendationSequence;
      const requestKey = teacherRecommendationKey();
      const controller = new AbortController();
      teacherRecommendationController = controller;
      activeRequest = controller;
      delete state.cache.teacherRecommendations;
      delete state.cache.teacherRecommendationKey;
      const button = $('#recommend-run', root);
      const old = button.innerHTML;
      const controls = [demandSelect, requirement, count, maxFee, hardBudget, preferLocal, organizationSelect, organizationApply, ...logisticsInputs, ...guideInputs, ...modeButtons];
      controls.forEach((control) => { control.disabled = true; });
      button.disabled = true;
      button.classList.add('is-loading');
      button.innerHTML = `${icon('loader-circle', 'spin')}正在识别与匹配`;
      output.hidden = false;
      status.textContent = '正在核对需求与讲师资料…';
      output.setAttribute('aria-busy', 'true');
      output.innerHTML = `<div class="matching-progress"><div>${icon('scan-search')}<b>正在核对讲师资料</b></div><p>分析培训需求，检索对应经历与推荐依据。</p><i aria-hidden="true"></i></div>`;
      refreshIcons(output);
      refreshIcons(button);
      try {
        const body = { requirement: text, max_results: Number(count.value), hard_budget: hardBudget.checked };
        if (inputMode === 'guided') {
          body.requirement_contract = guidedRequirementContract(readGuided());
          if (demandSelect.value) body.demand_id = Number(demandSelect.value);
        }
        Object.assign(body, logistics, { prefer_local: preferLocal.checked });
        if (feeValue) body.max_fee_rate = fee;
        if (standardCourse) body.standard_course = standardCourse;
        const result = await api('/teacher-recommendations', { body, signal: controller.signal });
        if (!current() || controller.signal.aborted || requestSequence !== teacherRecommendationSequence || requestKey !== teacherRecommendationKey() || !output.isConnected) return;
        if (!recommendationQualificationMatches(result.course_qualification, standardCourse)) throw new Error('本次未返回所选课程的可信核验结果，或核验条件与本次选择不一致，请重新分析。');
        if (standardCourse && ['NO_ELIGIBLE', 'NOT_CONFIGURED', 'INVALID_SELECTION'].includes(result.course_qualification?.status) && [result.recommendations, result.results, result.candidates, result.review_candidates, result.analysis?.dispatch_preferences?.selection?.travel_pending, result.analysis?.dispatch_preferences?.selection?.scoring_pending].some(items => Array.isArray(items) && items.length)) throw new Error('课程条件尚未通过，不能显示未核验的推荐名单。');
        renderRecommendationResults(output, result, context);
        status.textContent = Number(result.shortfall) > 0 ? `已找到 ${result.returned_count} 位相关候选，距 3 位目标还缺 ${result.shortfall} 位。` : '已生成候选名单，请先核对资料与调度条件，再与客户确认。';
        output.focus({ preventScroll: true });
        output.scrollIntoView({ block: 'start', behavior: 'auto' });
      } catch (error) {
        if (!current() || error?.name === 'AbortError' || controller.signal.aborted || requestSequence !== teacherRecommendationSequence || !output.isConnected) return;
        if (standardCourse && [403, 404, 409].includes(error.status || error.code)) courseSelection?.invalidate('课程、权限或师资依据已变化，请重新读取目录选择。');
        output.innerHTML = `<div class="recommend-empty">${icon('cloud-alert')}<b>本次推荐未完成</b><p>${esc(error?.message || '请稍后重试，已输入的客户要求会继续保留。')}</p></div>`;
        status.textContent = '本次匹配未完成，您填写的内容已保留。';
        refreshIcons(output);
      } finally {
        if (teacherRecommendationController === controller) teacherRecommendationController = null;
        if (activeRequest === controller && current() && output.isConnected) {
          activeRequest = null;
          output.removeAttribute('aria-busy');
          controls.forEach((control) => { control.disabled = false; });
          count.disabled = true; updateOrganizationState();
          if (button.isConnected) { button.disabled = false; button.classList.remove('is-loading'); button.innerHTML = old; refreshIcons(button); }
        }
      }
    };
  }

  async function pageTeachers(c) {
    const epoch = routeEpoch, identity = state.user;
    const catalogController = new AbortController();
    const current = () => !catalogController.signal.aborted && state.user === identity && isRouteCurrent(epoch, c, 'teachers');
    let catalog = null, catalogOpening = false, roster = null, rosterOpening = false, rosterTicket = 0, teacherRefreshController = null;
    const rosterCurrent = () => current() && state.user === identity && identity?.role === 'admin' && state.teacherTab === 'library';
    addRouteCleanup(() => { catalogController.abort(); catalog?.destroy(); rosterTicket++; teacherRefreshController?.abort(); roster?.destroy(); }, epoch);
    const openCatalog = async () => {
      if (!current() || catalogOpening) return;
      catalogOpening = true;
      try {
        const { openCourseCatalog } = await import('/modules/course-catalog/index.js?v=20260923certmaintenance2');
        if (!current()) return;
        catalog?.destroy();
        catalog = openCourseCatalog({ api, openModal, closeModal, renderTable, renderForm, collectForm,
          getUser: () => state.user, getModal: () => $('#modal-mask'),
          onCatalogChanged: () => { if (current()) invalidateTeacherRecommendations(); },
          signal: catalogController.signal, isCurrent: current });
        await catalog.ready;
      } catch (error) {
        if (current() && error?.name !== 'AbortError') toast(error.message || '课程与认证暂时无法加载，请重试。', true);
      } finally { catalogOpening = false; }
    };
    const openRoster = async () => {
      if (!rosterCurrent() || rosterOpening) return;
      rosterOpening = true; const ticket = ++rosterTicket;
      try {
        const { openTeacherRosterImport } = await import('/modules/course-catalog/roster-import.js?v=20260923teacherroster1');
        if (!rosterCurrent() || ticket !== rosterTicket) return;
        roster?.destroy();
        roster = openTeacherRosterImport({ api, openModal, closeModal, renderTable, getUser: () => state.user, getModal: () => $('#modal-mask'), signal: catalogController.signal, isCurrent: rosterCurrent,
          onImported: async () => {
            if (!rosterCurrent()) return;
            invalidateTeacherRecommendations(); teacherRefreshController?.abort(); const controller = new AbortController(); teacherRefreshController = controller;
            const fresh = await api('/teachers', { quiet: true, signal: controller.signal });
            if (!rosterCurrent() || teacherRefreshController !== controller || controller.signal.aborted) return;
            if (!Array.isArray(fresh)) throw Error('师资列表尚未核实');
            rows.splice(0, rows.length, ...fresh);
            $('[data-teacher-total]', c).innerHTML = rows.length + '<em>人</em>';
            $('[data-teacher-in-library]', c).innerHTML = rows.filter(row => row.status === '在库').length + '<em>人</em>';
            renderTeacherLibrary(panel, context); refreshIcons(panel);
          },
        });
        await roster.ready;
      } catch (error) { if (rosterCurrent() && error?.name !== 'AbortError') toast('教师名单暂时无法加载，请重试；原师资档案仍可维护。', true); }
      finally { rosterOpening = false; }
    };
    const resumeRequest = canWrite() ? api('/teacher-resumes/manage').catch((error) => ({ items: [], _loadError: error.message || '加载失败' })) : Promise.resolve({ items: [] });
    const evaluationRequest = canWrite() ? api('/teacher_evals') : Promise.resolve([]);
    const demandRequest = canWrite() ? api('/demands') : Promise.resolve([]);
    const [rows, projects, dispatches, demands, evaluations, resumePayload] = await Promise.all([api('/teachers'), api('/projects'), api('/dispatches'), demandRequest, evaluationRequest, resumeRequest]);
    if (!isRouteCurrent(epoch, c, 'teachers')) return;
    teacherResumeUploadConfig = resumePayload?.config || resumePayload?.upload_config || resumePayload || {};
    const resumes = teacherResumeItems(resumePayload);
    const resumeByTeacher = new Map(resumes.map((resume) => [String(resume.teacher_id), resume]));
    const inLib = rows.filter((row) => row.status === '在库').length;
    const ready = resumes.filter((resume) => resumeStatusInfo(resume.parse_status).tone === 'ready').length;
    const attention = resumes.filter((resume) => ['failed', 'review', 'pending', 'processing'].includes(resumeStatusInfo(resume.parse_status).tone)).length;
    const tabs = [{ key: 'library', label: '师资库', art: 'faculty' }];
    if (canWrite()) tabs.push(
      { key: 'resumes', label: '简历管理', art: 'documents', count: attention || '' },
      { key: 'recommend', label: '智能推荐', art: 'recommend' },
    );
    if (!tabs.some((tabItem) => tabItem.key === state.teacherTab)) state.teacherTab = 'library';
    const confirmedRates = rows.map((row) => Number(row.fee_rate)).filter((rate) => Number.isFinite(rate) && rate > 0);
    const avgRate = confirmedRates.length ? confirmedRates.reduce((total, rate) => total + rate, 0) / confirmedRates.length : 0;
    const summary = canWrite()
      ? `<div class="module-summary four teacher-summary"><div><span>${icon('users-round')}</span><small>当前师资</small><b data-teacher-total>${rows.length}<em>人</em></b></div><div><span>${icon('user-check')}</span><small>当前在库</small><b data-teacher-in-library>${inLib}<em>人</em></b></div><div><span>${icon('file-check-2')}</span><small>已解析简历</small><b>${ready}<em>份</em></b></div><div><span>${icon('scan-line')}</span><small>解析待处理</small><b>${attention}<em>份</em></b></div></div>`
      : `<div class="module-summary three teacher-summary"><div><span>${icon('users-round')}</span><small>当前师资</small><b data-teacher-total>${rows.length}<em>人</em></b></div><div><span>${icon('user-check')}</span><small>当前在库</small><b data-teacher-in-library>${inLib}<em>人</em></b></div><div><span>${icon('badge-japanese-yen')}</span><small>已确认平均课酬</small><b>${confirmedRates.length ? `¥ ${money(avgRate)}` : '待确认'}</b></div></div>`;
    const tabBar = canWrite() ? `<div class="teacher-mode-tabs" role="tablist" aria-label="师资资源功能">${tabs.map((tabItem) => `<button type="button" role="tab" id="teacher-tab-${tabItem.key}" aria-controls="teacher-tab-panel" aria-selected="${state.teacherTab === tabItem.key}" tabindex="${state.teacherTab === tabItem.key ? '0' : '-1'}" data-teacher-tab="${tabItem.key}" class="${state.teacherTab === tabItem.key ? 'active' : ''}">${businessArt(tabItem.art)}<span>${tabItem.label}</span>${tabItem.count ? `<em>${tabItem.count}</em>` : ''}</button>`).join('')}</div>` : '';
    c.innerHTML = `<div class="teacher-console">
      <div class="teacher-console-head">${tabBar}${summary}</div>
      <div class="teacher-tab-panel" id="teacher-tab-panel" role="${canWrite() ? 'tabpanel' : 'region'}" ${canWrite() ? `aria-labelledby="teacher-tab-${esc(state.teacherTab)}"` : 'aria-label="师资库"'}></div>
    </div>`;
    const panel = $('#teacher-tab-panel', c);
    const context = { epoch, c, rows, projects, dispatches, demands, evaluations, resumes, resumeByTeacher, openCatalog, openRoster, resumeLoadError: resumePayload?._loadError || '' };
    addRouteCleanup(() => { cancelTeacherRecommendation(); context.stopTeacherTabPolling?.(); teacherProfileRequestSequence += 1; }, epoch);
    const showTab = (key, focus = false) => {
      if (key !== 'library') { context.stopTeacherLibrary?.(); rosterTicket++; roster?.destroy(); teacherRefreshController?.abort(); }
      cancelTeacherRecommendation();
      context.stopTeacherTabPolling?.();
      context.stopTeacherTabPolling = null;
      teacherProfileRequestSequence += 1;
      state.teacherTab = key;
      $$('[data-teacher-tab]', c).forEach((button) => {
        const active = button.dataset.teacherTab === key;
        button.classList.toggle('active', active);
        button.setAttribute('aria-selected', String(active));
        button.tabIndex = active ? 0 : -1;
        if (active && focus) button.focus();
      });
      if (canWrite()) panel.setAttribute('aria-labelledby', `teacher-tab-${key}`);
      if (key === 'resumes') renderResumeManagement(panel, context);
      else if (key === 'recommend') renderTeacherRecommendation(panel, context);
      else renderTeacherLibrary(panel, context);
      refreshIcons(panel);
    };
    $$('[data-teacher-tab]', c).forEach((button, index, buttons) => {
      button.onclick = () => showTab(button.dataset.teacherTab);
      button.onkeydown = (event) => {
        if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
        event.preventDefault();
        let next = index;
        if (event.key === 'ArrowLeft') next = (index - 1 + buttons.length) % buttons.length;
        if (event.key === 'ArrowRight') next = (index + 1) % buttons.length;
        if (event.key === 'Home') next = 0;
        if (event.key === 'End') next = buttons.length - 1;
        showTab(buttons[next].dataset.teacherTab, true);
      };
    });
    showTab(state.teacherTab);
  }

  async function showTeacherEvals(t, projects, dispatches = []) {
    const epoch = routeEpoch;
    const ticket = ++teacherProfileRequestSequence;
    const evals = await api('/teacher_evals?teacher_id=' + t.id);
    if (!isRouteCurrent(epoch, null, 'teachers') || ticket !== teacherProfileRequestSequence) return;
    const completedDeliveries = dispatches.filter((item) => String(item.teacher_id) === String(t.id) && item.status === '已完成').sort((left, right) => String(right.teach_date || '').localeCompare(String(left.teach_date || '')) || Number(right.id) - Number(left.id));
    const deliveredProjectIds = new Set(completedDeliveries.map((item) => String(item.project_id)));
    const projectById = new Map(projects.map((project) => [String(project.id), project]));
    const eligibleProjects = projects.filter((project) => project.status !== '已归档' && deliveredProjectIds.has(String(project.id)));
    const avg = evals.length ? (evals.reduce((s, e) => s + Number(e.score || 0), 0) / evals.length).toFixed(2) : '—';
    const verified = teacherSystemMetrics(t.id, dispatches, evals);
    openModal(t.name, `
      <div class="teacher-profile"><span class="person-avatar large">${esc(t.name.slice(-2))}</span><div><h3>${esc(t.name)} ${tag(t.status)}</h3><p>${esc(t.org || '未填写单位')} · ${esc(t.title || '未填写职称')}</p><div><span>${icon('tags')}${esc(t.field || '未填写专业领域')}</span><span>${icon('badge-japanese-yen')}${teacherFeeRateText(t.fee_rate)}</span></div></div><strong>${avg}<small>系统履约评分</small></strong></div>
      <section class="teacher-profile-proof"><div><span>${icon('shield-check')}系统履约记录</span><small>仅统计研序中已完成的课程与已提交评价，不等同于简历自述。</small></div><dl><div><dt>已完成场次</dt><dd>${num(verified.completedSessions)}</dd></div><div><dt>已完成课时</dt><dd>${num(verified.completedHours)}</dd></div><div><dt>评价均分</dt><dd>${verified.evaluationCount ? verified.evaluationAverage.toFixed(2) : '—'}</dd></div><div><dt>评价数量</dt><dd>${num(verified.evaluationCount)}</dd></div></dl></section>
      <div class="teacher-info"><p><small>常驻地区</small><span>${esc(teacherResidenceText(t))}</span></p><p><small>联系电话</small><span>${esc(t.phone || '—')}</span></p><p><small>电子邮箱</small><span>${esc(t.email || '—')}</span></p><p><small>入库日期</small><span>${esc(t.in_date || '—')}</span></p><p><small>授课评价</small><span>${evals.length} 条</span></p></div>
      <div class="teacher-intro"><small>师资简介</small><p>${esc(t.intro || '暂无简介')}</p></div>
      <div class="section-title response-title"><div><span>授课记录</span><small>每条已完成排课计为一场；新增课程请在项目排课中维护并确认完成。</small></div></div>
      ${completedDeliveries.length ? renderTable([
        { k: 'teach_date', l: '授课日期' }, { k: 'subject', l: '授课主题' },
        { k: 'project_title', l: '培训项目', render: (record) => projectById.has(String(record.project_id)) ? `<button type="button" class="teacher-delivery-project" data-teacher-delivery-project="${esc(record.project_id)}">${esc(record.project_title || projectById.get(String(record.project_id))?.title || '查看项目')}${icon('arrow-up-right')}</button>` : esc(record.project_title || '项目待确认') },
        { k: 'hours', l: '课时', align: 'right', render: (record) => num(record.hours) },
      ], completedDeliveries, null, 'teacher-deliveries') : '<div class="inline-note">暂未记录已完成课程。录入项目排课并确认完成后，场次与课时会自动汇总到此处。</div>'}
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
    $$('[data-teacher-delivery-project]', $('#modal-mask')).forEach((button) => {
      button.onclick = () => { closeModal(); navigateTo('project_detail', { projectId: button.dataset.teacherDeliveryProject }); };
    });
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
      invalidateTeacherRecommendations();
      toast('评价已提交');
      closeModal();
      showTeacherEvals(t, projects, dispatches);
    };
  }

  // ============ 分析报告 ============
  function mountReviewedReport(c, epoch) {
    const root = $('#rp-reviewed', c);
    const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit' }).formatToParts(new Date());
    const month = `${parts.find(part => part.type === 'year').value}-${parts.find(part => part.type === 'month').value}`;
    const monthRange = (value) => {
      if (!/^[0-9]{4}-(0[1-9]|1[0-2])$/.test(value) || value.startsWith('0000')) return null;
      const last = new Date(`${value}-01T00:00:00Z`);
      last.setUTCMonth(last.getUTCMonth() + 1); last.setUTCDate(0);
      return { start: `${value}-01`, end: last.toISOString().slice(0, 10) };
    };
    const initial = monthRange(month);
    const hourColumns = [['estimated', '预计课时'], ['planned', '计划课时'], ['actual', '实际课时'], ['payable', '计酬课时']];
    const identity = state.user;
    let disposed = false, sequence = 0, controller = null, snapshot = null, ownedModal = null, organizations = [];
    let downloadSequence = 0, downloadController = null;
    const objectUrls = new Map();
    const current = () => !disposed && state.user === identity && root.isConnected && isRouteCurrent(epoch, c, 'report');
    const releaseUrl = url => { if (objectUrls.has(url)) { clearTimeout(objectUrls.get(url)); URL.revokeObjectURL(url); objectUrls.delete(url); } };
    const cancelDownload = () => {
      downloadSequence += 1; downloadController?.abort(); downloadController = null;
      for (const url of objectUrls.keys()) releaseUrl(url);
    };
    const closeOwnedModal = () => {
      if (ownedModal && $('#modal-mask') === ownedModal) closeModal();
      ownedModal = null;
    };
    const cancel = () => {
      sequence += 1; controller?.abort(); cancelDownload(); snapshot = null; closeOwnedModal();
    };
    addRouteCleanup(() => { disposed = true; cancel(); }, epoch);
    root.innerHTML = `<div class="card-heading"><div><h2>已核对授课</h2><p>四类课时均来自已完成且已核对的同一批授课记录，各自统计，不相加。</p></div></div>
      <div class="toolbar" id="rp-reviewed-toolbar">
        <label class="reviewed-filter" for="rp-reviewed-month"><span>月份</span><input type="month" id="rp-reviewed-month" aria-label="已核对授课月份" value="${esc(month)}"></label>
        <label class="reviewed-filter" for="rp-reviewed-start"><span>开始</span><input type="date" id="rp-reviewed-start" aria-label="授课开始日期" value="${initial.start}"></label>
        <label class="reviewed-filter" for="rp-reviewed-end"><span>结束</span><input type="date" id="rp-reviewed-end" aria-label="授课结束日期" value="${initial.end}"></label>
        <select class="select-filter" id="rp-reviewed-basis" aria-label="已核对授课日期口径"><option value="TEACHING">授课日期</option><option value="PAYMENT" disabled>支付日期请在课酬统计查询</option></select>
        <select class="select-filter" id="rp-reviewed-organization" aria-label="已核对授课机构"><option value="">全部可查看机构</option></select>
        <button type="button" class="btn" id="rp-reviewed-query">${icon('search')}查询</button>
      </div>
      <p class="modal-intro" id="rp-reviewed-filter-note">筛选仅作用于本卡片，原经营图表与文字报告使用各自口径。</p>
      <div id="rp-reviewed-result" aria-live="polite"></div>`;
    const result = $('#rp-reviewed-result', root);
    const control = (name) => $('#rp-reviewed-' + name, root);
    const exactHours = (total) => {
      if (total?.complete === true && typeof total.value === 'string') return esc(total.value);
      const known = typeof total?.known_subtotal === 'string' ? `已知 ${esc(total.known_subtotal)} 课时，${esc(total.missing_records)} 条缺失` : '统计结果未提供';
      return `待补齐<br><small>${known}</small>`;
    };
    const aggregateColumns = hourColumns.map(([key, label]) => ({ k: key, l: label, align: 'right', render: row => exactHours(row.hours?.[key]) }));
    const pageTable = (holder, columns, rows, idKey, valid = current) => {
      let page = 0;
      const pageSize = 20;
      const draw = () => {
        if (!valid()) return;
        const pages = Math.max(1, Math.ceil(rows.length / pageSize));
        holder.innerHTML = rows.length ? renderTable(columns, rows.slice(page * pageSize, (page + 1) * pageSize).map(row => ({ ...row, id: row[idKey] })), null)
          + `<div class="toolbar"><button type="button" class="btn gray" data-reviewed-page="previous" ${page === 0 ? 'disabled' : ''}>上一页</button><span>第 ${page + 1} / ${pages} 页 · 共 ${rows.length} 条</span><button type="button" class="btn gray" data-reviewed-page="next" ${page + 1 >= pages ? 'disabled' : ''}>下一页</button></div>`
          : '<div class="empty-state"><h3>该范围暂无已核对授课</h3><p>可调整日期与机构范围后查询。</p></div>';
        const previous = $('[data-reviewed-page="previous"]', holder), next = $('[data-reviewed-page="next"]', holder);
        if (previous) previous.onclick = () => { if (valid() && page > 0) { page--; draw(); } };
        if (next) next.onclick = () => { if (valid() && page + 1 < pages) { page++; draw(); } };
        refreshIcons(holder);
      };
      draw();
    };
    const showRows = (excluded) => {
      if (!current() || !snapshot) return;
      const view = snapshot, ticket = sequence;
      const note = excluded
        ? `所选日期范围未计入 ${view.excluded_count - view.undated_excluded_count} 条；日期未知、无法归月 ${view.undated_excluded_count} 条。日期未知记录不代表都属于所选月份。`
        : '以下明细与本卡片的四类课时、实际课时排名及机构汇总来自同一次查询。正式课程、讲师编码及课酬币种尚未接入。';
      const mask = openModal(excluded ? '未计入授课原因' : '已核对授课明细', `<p class="modal-intro">${esc(view.start)} 至 ${esc(view.end)} · ${esc(view.organizations.join('、'))}</p><p class="modal-intro">${esc(note)}</p><div id="rp-reviewed-modal-table"></div>`, { wide: true, noFoot: true, onClose: () => { if (ownedModal === mask) ownedModal = null; } });
      if (!mask) return;
      ownedModal = mask;
      const columns = excluded ? [
        { k: 'dispatch_id', l: '排课记录' }, { k: 'organization_code', l: '机构' },
        { k: 'date_unknown', l: '日期归属', render: row => row.date_unknown ? '日期未知，无法归月' : '所选日期范围' }, { k: 'message', l: '未计入原因' },
      ] : [
        { k: 'teaching_date', l: '授课日期' }, { k: 'project_title', l: '项目' }, { k: 'subject', l: '授课内容' },
        { k: 'teacher_name', l: '讲师' }, { k: 'organization_code', l: '机构' },
        ...hourColumns.map(([key, label]) => ({ k: key, l: label, align: 'right', render: row => row.hours[key] === null ? '待补齐' : esc(row.hours[key]) })),
        { k: 'verified_at', l: '核对时间', render: row => esc(workflowLocalTime(row.verified_at)) },
      ];
      pageTable($('#rp-reviewed-modal-table', mask), columns, excluded ? view.excluded : view.details, 'dispatch_id', () => current() && snapshot === view && ticket === sequence && $('#modal-mask') === mask);
    };
    const downloadTeaching = async () => {
      if (!current() || downloadController || snapshot?.teaching_export_available !== true) return;
      const view = snapshot, queryTicket = sequence, ticket = ++downloadSequence, request = new AbortController();
      downloadController = request;
      const valid = () => current() && snapshot === view && sequence === queryTicket && downloadSequence === ticket && downloadController === request && !request.signal.aborted;
      const button = control('teaching-export'), notice = control('download-status');
      button.disabled = true; notice.textContent = '正在核对下载权限与本次授课统计…';
      const mime = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
      const readBody = async (response, maximum) => {
        const length = response.headers.get('Content-Length');
        if (length && (!/^[0-9]+$/.test(length) || Number(length) > maximum)) throw new Error('下载文件过大，请缩小机构范围后重新查询');
        const reader = response.body?.getReader();
        if (!reader) throw new Error('下载内容无法读取，请重新查询后再试');
        let size = 0; const chunks = [];
        try {
          while (true) {
            const chunk = await reader.read();
            if (!valid()) { await reader.cancel(); throw Object.assign(new Error('下载已取消'), { name: 'AbortError' }); }
            if (chunk.done) break;
            size += chunk.value.byteLength;
            if (size > maximum) { await reader.cancel(); throw new Error('下载文件过大，请缩小机构范围后重新查询'); }
            chunks.push(chunk.value);
          }
        } finally { reader.releaseLock(); }
        return new Blob(chunks, { type: mime });
      };
      try {
        const query = new URLSearchParams({ start: view.start, end: view.end, date_basis: view.date_basis, organizations: view.organizations.join(','), snapshot_version: view.snapshot_version });
        const response = await fetch('/api/management-reports/teaching-export?' + query.toString(), { method: 'GET', credentials: 'same-origin', cache: 'no-store', headers: { Accept: mime }, signal: request.signal });
        if (!valid()) return;
        if (!response.ok) {
          const failure = new Error('授课统计下载未完成，请重新查询后再试'); failure.status = response.status;
          try { const body = await readBody(response, 64 * 1024); const data = JSON.parse(await body.text()); if (typeof data.msg === 'string') failure.message = data.msg; } catch (error) { if (error.name === 'AbortError') throw error; }
          throw failure;
        }
        if (response.status !== 200 || response.redirected || response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase() !== mime) throw new Error('下载格式不正确，未保存文件，请重新查询');
        const file = await readBody(response, 16 * 1024 * 1024);
        const signature = new Uint8Array(await file.slice(0, 4).arrayBuffer());
        if (!valid()) return;
        if (signature.length !== 4 || signature[0] !== 80 || signature[1] !== 75 || signature[2] !== 3 || signature[3] !== 4) throw new Error('下载内容不是有效的授课统计文件，未保存文件，请重新查询');
        // The filename is derived only from validated snapshot dates, never a response header.
        const filename = 'reviewed-teaching-' + view.start.replaceAll('-', '') + '-' + view.end.replaceAll('-', '') + '.xlsx';
        const url = URL.createObjectURL(file); objectUrls.set(url, null);
        const link = document.createElement('a'); link.href = url; link.download = filename; link.hidden = true;
        try { document.body.appendChild(link); link.click(); }
        finally { link.remove(); objectUrls.set(url, setTimeout(() => releaseUrl(url), 1000)); }
        notice.textContent = '授课统计已生成下载；这是授课事实统计，非结算文件，不含金额和支付。';
      } catch (error) {
        if (!valid() || error.name === 'AbortError') return;
        if (error.status === 401) {
          cancel(); localStorage.removeItem('token'); invalidateSession(); toast('登录状态已失效，请重新登录', true); return;
        }
        cancel();
        if (error.status === 403) { organizations = []; control('organization').innerHTML = '<option value="">全部可查看机构（重新查询以核实）</option>'; }
        result.innerHTML = `<div class="error-state"><h3>授课统计下载未完成</h3><p>${esc(error.message || '网络连接失败')}</p><p>旧结果已清除，请点击“查询”重新核对后再下载。原经营图表与文字报告仍可查看。</p></div>`;
        refreshIcons(result);
      } finally {
        if (downloadController === request) {
          downloadController = null;
          if (current() && snapshot === view && queryTicket === sequence && ticket === downloadSequence && button.isConnected) button.disabled = false;
        }
      }
    };
    const renderView = (view) => {
      const knownDateExcluded = view.excluded_count - view.undated_excluded_count;
      result.innerHTML = `<p class="modal-intro">${esc(view.coverage_notice)}</p>
        <p class="modal-intro">授课日期：${esc(view.start)} 至 ${esc(view.end)} · 机构：${esc(view.organizations.join('、'))}<br>已核对授课记录数：${esc(view.included_count)}；所选日期范围未计入：${knownDateExcluded}；日期未知、无法归月：${esc(view.undated_excluded_count)}。尚未到达的授课日期不计入。</p>
        <div class="module-summary four" id="rp-reviewed-summary">${hourColumns.map(([key, label]) => `<div><small>${label}</small><b>${exactHours(view.totals[key])}</b></div>`).join('')}</div>
        <div class="toolbar"><button type="button" class="btn gray" id="rp-reviewed-details" ${view.included_count ? '' : 'disabled'}>查看同批明细</button><button type="button" class="btn gray" id="rp-reviewed-excluded" ${view.excluded_count ? '' : 'disabled'}>查看未计入原因</button><button type="button" class="btn gray" id="rp-reviewed-teaching-export" ${view.teaching_export_available === true ? '' : 'disabled'}>导出授课统计</button><button type="button" class="btn gray" id="rp-reviewed-export" disabled>结算导出请到课酬统计</button></div>
        <p class="modal-intro" id="rp-reviewed-download-status" role="status">授课事实统计，非结算文件，不含金额和支付；文件仅使用机构编码与明确标注的系统记录ID，系统记录ID不是正式编码。${view.teaching_export_available === true ? '' : ' 当前范围暂无授课统计导出权限。'}</p>
        <p class="modal-intro">本卡只统计已核对的授课事实，不计算金额；课酬、支付及编码结算导出见上方“课酬与支付统计”。授课记录数不是唯一课程数。</p>
        <div class="card-heading"><div><h2>实际课时讲师排名</h2><p>按实际课时降序，并列名次保留；授课记录数不是唯一课程数。</p></div></div><div id="rp-reviewed-ranking"></div>
        <div class="card-heading"><div><h2>机构授课汇总</h2><p>与讲师排名、明细使用同一批已核对且已完成记录。</p></div></div><div id="rp-reviewed-organizations"></div>`;
      const stillSnapshot = () => current() && snapshot === view;
      pageTable(control('ranking'), [{ k: 'rank', l: '名次' }, { k: 'teacher_name', l: '讲师' }, { k: 'dispatch_count', l: '授课记录数', align: 'right' }, ...aggregateColumns], view.teacher_ranking, 'teacher_id', stillSnapshot);
      pageTable(control('organizations'), [{ k: 'organization_code', l: '机构' }, { k: 'dispatch_count', l: '授课记录数', align: 'right' }, ...aggregateColumns], view.organization_summary, 'organization_code', stillSnapshot);
      control('details').onclick = () => showRows(false);
      control('excluded').onclick = () => showRows(true);
      control('teaching-export').onclick = downloadTeaching;
      refreshIcons(root);
    };
    const validDate = (value) => /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value) && !value.startsWith('0000') && Number.isFinite(Date.parse(value + 'T00:00:00Z')) && new Date(value + 'T00:00:00Z').toISOString().slice(0, 10) === value;
    const validateView = (view) => {
      const decimal = value => typeof value === 'string' && /^-?[0-9]+(?:\.[0-9]+)?$/.test(value);
      const aggregate = hours => hours && hourColumns.every(([key]) => {
        const total = hours[key];
        return total && decimal(total.known_subtotal) && Number.isSafeInteger(total.missing_records) && total.missing_records >= 0 && (total.complete === true ? decimal(total.value) && total.missing_records === 0 : total.complete === false && total.value === null && total.missing_records > 0);
      });
      const code = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,95}$/.test(value);
      if (!view || view.population !== 'REVIEWED_COMPLETED_DISPATCHES' || view.date_basis !== 'TEACHING' || !validDate(view.start) || !validDate(view.end)
        || ![view.details, view.excluded, view.teacher_ranking, view.organization_summary, view.organizations, view.available_organizations].every(Array.isArray)
        || !view.organizations.length || !view.available_organizations.every(code) || !view.organizations.every(org => code(org) && view.available_organizations.includes(org))
        || typeof view.coverage_notice !== 'string' || view.permissions?.read !== true || !aggregate(view.totals)
        || typeof view.snapshot_version !== 'string' || !/^[a-f0-9]{64}$/.test(view.snapshot_version) || typeof view.teaching_export_available !== 'boolean'
        || view.included_count !== view.details.length || view.excluded_count !== view.excluded.length || !Number.isSafeInteger(view.undated_excluded_count) || view.undated_excluded_count < 0 || view.undated_excluded_count > view.excluded_count
        || !view.details.every(row => row?.hours && hourColumns.every(([key]) => row.hours[key] === null || decimal(row.hours[key])))
        || !view.teacher_ranking.every(row => aggregate(row?.hours)) || !view.organization_summary.every(row => aggregate(row?.hours))) throw new Error('统计响应格式不完整，请重新查询');
    };
    const reload = async () => {
      if (!current()) return;
      cancel();
      const ticket = sequence, request = new AbortController(); controller = request;
      result.innerHTML = '<p class="modal-intro" role="status">正在查询已核对授课…</p>';
      root.setAttribute('aria-busy', 'true');
      try {
        const start = control('start').value, end = control('end').value, organization = control('organization').value, basis = control('basis').value;
        if (!validDate(start) || !validDate(end)) throw new Error('请填写有效的开始和结束日期');
        if (start > end) throw new Error('开始日期不能晚于结束日期');
        if (basis !== 'TEACHING') throw new Error('本卡仅按授课日期统计；请在课酬与支付统计中选择支付日期');
        if (organization && !organizations.includes(organization)) throw new Error('请选择当前可查看的机构');
        const query = new URLSearchParams({ start, end, date_basis: basis });
        if (organization) query.set('organizations', organization);
        const view = await api('/management-reports?' + query.toString(), { quiet: true, signal: request.signal });
        if (!current() || request.signal.aborted || ticket !== sequence) return;
        validateView(view);
        organizations = [...view.available_organizations];
        control('organization').innerHTML = '<option value="">全部可查看机构</option>' + organizations.map(org => `<option value="${esc(org)}">${esc(org)}</option>`).join('');
        control('organization').value = organization;
        snapshot = view;
        renderView(view);
      } catch (error) {
        if (!current() || request.signal.aborted || ticket !== sequence || error?.name === 'AbortError') return;
        snapshot = null;
        if (error.status === 403 || error.code === 403) {
          organizations = [];
          control('organization').innerHTML = '<option value="">全部可查看机构（重新查询以核实）</option>';
        }
        result.innerHTML = `<div class="error-state"><h3>已核对授课暂时无法加载</h3><p>${esc(error.message || '请稍后重新查询')}</p><p>原经营图表与文字报告仍可查看。调整条件后可点击“查询”重试。</p></div>`;
        refreshIcons(result);
      } finally {
        if (current() && ticket === sequence) root.setAttribute('aria-busy', 'false');
      }
    };
    const change = () => {
      if (!current()) return;
      cancel(); root.setAttribute('aria-busy', 'false');
      const range = monthRange(control('month').value);
      if (!range || range.start !== control('start').value || range.end !== control('end').value) control('month').value = '';
      control('filter-note').textContent = `${control('month').value ? '自然月' : '自定义日期范围'}；筛选仅作用于本卡片，原经营图表与文字报告使用各自口径。`;
      result.innerHTML = '<p class="modal-intro" role="status">筛选条件已变化，请点击“查询”查看本次结果。</p>';
    };
    control('month').onchange = () => {
      const range = monthRange(control('month').value);
      if (range) { control('start').value = range.start; control('end').value = range.end; }
      change();
    };
    ['start', 'end'].forEach(key => { control(key).oninput = change; control(key).onchange = change; });
    ['organization', 'basis'].forEach(key => { control(key).onchange = change; });
    control('query').onclick = reload;
    refreshIcons(root);
    return reload();
  }

  async function pageReport(c) {
    const epoch = routeEpoch;
    const [txt, d] = await Promise.all([api('/stats/report'), api('/stats/overview')]);
    if (!isRouteCurrent(epoch, c, 'report')) return;
    c.innerHTML = `
      <section class="report-hero"><div><span class="eyebrow">OPERATIONS INSIGHT</span><h1>培训经营分析中心</h1><p>从合同与回款、已录支出、师资履约与交付质量四个维度审视业务表现。</p></div><div><button type="button" class="btn light" id="rp-refresh">${icon('refresh-cw')}刷新数据</button><button type="button" class="btn outline-light" id="rp-print">${icon('printer')}打印完整报告</button></div></section>
      <div class="module-summary four"><div><span>${icon('badge-japanese-yen')}</span><small>合同总额</small><b>${countTag(d.project_amount, 'money')}</b></div><div><span>${icon('circle-dollar-sign')}</span><small>实收金额</small><b>${countTag(d.received, 'money')}</b></div><div><span>${icon('chart-spline')}</span><small>按已录支出估算余额</small><b>${countTag(d.profit, 'money')}</b></div><div><span>${icon('star')}</span><small>讲师履约评分</small><b>${countTag(d.eval_avg)}<em>分</em></b></div></div>
      <section class="card data-card" id="rp-financial" aria-label="课酬与支付统计"></section>
      <section class="card data-card" id="rp-reviewed" aria-label="已核对授课统计"></section>
      <div class="section-title"><div><span>原经营统计（独立口径）</span><small>下方图表与文字报告不受上方授课筛选影响</small></div></div>
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
    mountReviewedReport(c, epoch);
    const financialIdentity = state.user, financialController = new AbortController();
    let financialReport = null;
    const financialCurrent = () => !financialController.signal.aborted && state.user === financialIdentity && isRouteCurrent(epoch, c, 'report');
    addRouteCleanup(() => { financialController.abort(); financialReport?.destroy(); }, epoch);
    (async () => {
      try {
        const { mount } = await import('/modules/reports/financial-reports.js?v=20260923financial1');
        if (!financialCurrent()) return;
        financialReport = mount($('#rp-financial', c), {
          api, getUser: () => state.user, isCurrent: financialCurrent, renderTable, refreshIcons,
          signal: financialController.signal,
          fetchDownload: (path, options) => fetch('/api' + path, options),
          saveFile: (blob, filename) => {
            const url = URL.createObjectURL(blob), link = document.createElement('a');
            let released = false, timer;
            const release = () => { if (!released) { released = true; clearTimeout(timer); URL.revokeObjectURL(url); } };
            link.href = url; link.download = filename; link.hidden = true;
            try { document.body.appendChild(link); link.click(); }
            catch (error) { release(); throw error; }
            finally { link.remove(); }
            timer = setTimeout(release, 1000);
            return release;
          },
          onUnauthorized: () => { if (financialCurrent()) { localStorage.removeItem('token'); invalidateSession(); toast('登录状态已失效，请重新登录', true); } },
        });
        await financialReport.ready;
      } catch (error) {
        if (financialCurrent() && error?.name !== 'AbortError') $('#rp-financial', c).textContent = '课酬统计暂时无法加载，请刷新后重试。';
      }
    })();
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
    const identity = state.user;
    const controller = new AbortController();
    const current = () => !controller.signal.aborted && state.user === identity && isRouteCurrent(epoch, c, 'users');
    addRouteCleanup(() => controller.abort(), epoch);
    const roleName = (r) => ({ admin: '系统管理员', manager: '业务管理员', viewer: '只读用户' }[r] || r);
    c.innerHTML = `<div class="card"><div class="card-heading"><div><h2>当前账号业务身份</h2></div></div><div id="user-own-binding" role="status" aria-live="polite">正在读取当前业务身份…</div></div><div id="user-summary" class="module-summary three" hidden></div><div class="card data-card">
      <div class="card-heading"><div><h2>用户与权限</h2><p>账号角色管理基础功能；需求、审批与受理权限另由业务身份及机构授权确定</p></div><div class="toolbar" style="flex-wrap:wrap">${state.user?.role === 'admin' ? '<button type="button" class="btn gray" id="user-provisioning-open">配置人员岗位</button><button type="button" class="btn gray" id="user-management-group-open">配置管理组</button>' : ''}<button type="button" class="btn" id="add-btn">${icon('user-plus')}新增用户</button></div></div>
      <div id="tbl"></div>
    </div>${state.user?.role === 'admin' ? '<div class="card"><div class="card-heading"><div><h2>名册导入</h2><p>核对已确认的人员名册，新建账号保持待启用；业务身份和审批授权另行办理。</p></div><div class="toolbar" style="flex-wrap:wrap"><button type="button" class="btn gray" id="user-import-open">打开名册导入</button><button type="button" class="btn gray" id="user-management-supplement-open">补充管理人员</button></div></div><p id="user-import-status" role="status" aria-live="polite"></p></div><div id="user-import-content"></div>' : ''}`;
    const updateSummary = (rows) => {
      if (!current()) return;
      const summary = $('#user-summary', c);
      summary.hidden = false;
      summary.innerHTML = `<div><span>${icon('users-round')}</span><small>系统用户</small><b>${countTag(rows.length)}<em>人</em></b></div><div><span>${icon('user-check')}</span><small>启用账号</small><b>${countTag(rows.filter((r) => Number(r.status) === 1).length)}<em>个</em></b></div><div><span>${icon('shield-check')}</span><small>管理员</small><b>${countTag(rows.filter((r) => r.role === 'admin').length)}<em>人</em></b></div>`;
      refreshIcons(summary);
      animateCounters(summary);
    };
    const ownBindingReady = api('/organization/me', { quiet: true, signal: controller.signal }).then((ownBinding) => {
      if (current()) $('#user-own-binding', c).innerHTML = organizationBindingHtml(ownBinding);
    }).catch((error) => {
      if (current()) $('#user-own-binding', c).innerHTML = organizationBindingHtml({ _error: error.message });
    });
    const fields = [
      { k: 'username', label: '用户名', required: true },
      { k: 'name', label: '姓名', required: true },
      { k: 'role', label: '角色', type: 'select', required: true, hint: '遵循最小权限原则；请根据实际职责选择', options: [{ v: 'admin', l: '系统管理员' }, { v: 'manager', l: '业务管理员' }, { v: 'viewer', l: '只读用户' }] },
      { k: 'status', label: '状态', type: 'select', options: [{ v: 1, l: '启用' }, { v: 0, l: '停用' }] },
      { k: 'password', label: '登录密码', type: 'password', span2: true, placeholder: '编辑时留空则不修改', autocomplete: 'new-password', hint: '至少 8 位字符' },
    ];
    const cols = [
      { k: 'username', l: '账号', render: (r) => `<span class="secondary-cell"><b>${esc(r.name)}</b><small>${esc(r.username)} · #${esc(r.id)}</small></span>` },
      { k: 'role', l: '角色/状态', render: (r) => `<span class="status-stack">${tag(roleName(r.role))}${tag(Number(r.status) === 1 ? '启用' : '停用')}</span>` },
      { k: 'created_at', l: '创建时间', render: (r) => {
        const value = String(r.created_at || '—');
        const parts = /^(\d{4}-\d{2}-\d{2})[ T](.+)$/.exec(value);
        return `<span class="secondary-cell"><b>${esc(parts ? parts[1] : value)}</b>${parts ? `<small>${esc(parts[2])}</small>` : ''}</span>`;
      } },
    ];
    const bindingColumns = [
      { k: 'bindingPerson', l: '业务身份', render: (r) => `<span class="secondary-cell"><b>${esc(r.bindingPerson)}</b><small>机构：${esc(r.bindingOrg)}</small></span>` },
      { k: 'bindingLeader', l: '负责人/BP', render: (r) => `<span class="secondary-cell"><b>负责人：${esc(r.bindingLeader)}</b><small>BP：${esc(r.bindingBp)}</small></span>` },
      { k: 'bindingStatus', l: '绑定状态', render: (r) => `<span class="secondary-cell">${tag(r.bindingStatus)}${['人员停用', '机构停用'].includes(r.bindingPersonStatus) ? `<small>${esc(r.bindingPersonStatus)}</small>` : ''}</span>` },
    ];
    let emailMaintenance = null, emailMaintenanceLoading = false;
    addRouteCleanup(() => { emailMaintenance?.destroy(); emailMaintenance = null; }, epoch);
    const openEmailMaintenance = async row => {
      if (!current() || state.user?.role !== 'admin' || ![0, 1].includes(Number(row.status)) || emailMaintenanceLoading || $('#modal-mask')) return;
      const targetUserId = Number(row.id);
      if (!Number.isSafeInteger(targetUserId) || targetUserId <= 0) return;
      emailMaintenanceLoading = true;
      try {
        if (!emailMaintenance) {
          const { createAccountEmailMaintenance } = await import('/modules/notifications/account-email-maintenance.js?v=20260923emailmaint1');
          if (!current() || state.user?.role !== 'admin') return;
          emailMaintenance = createAccountEmailMaintenance({
            api, getUser: () => state.user, getModal: () => $('#modal-mask'), openModal, closeModal,
            renderForm, collectForm, renderTable, toast, isCurrent: current, signal: controller.signal,
            newRequestId: () => crypto.randomUUID(),
            reauthenticate: () => { invalidateSession(); toast('邮箱已保存，请重新登录完成核验。'); },
          });
        }
        if (current()) await emailMaintenance.open(targetUserId);
      } catch (error) {
        if (current() && error?.name !== 'AbortError') toast('核验邮箱暂时无法打开，请稍后重试。', true);
      } finally { if (current()) emailMaintenanceLoading = false; }
    };
    let emailPreparation = null, emailLoading = false;
    addRouteCleanup(() => { emailPreparation?.destroy(); emailPreparation = null; }, epoch);
    const openEmailPreparation = async row => {
      if (!current() || state.user?.role !== 'admin' || Number(row.status) !== 0 || row.role !== 'viewer' || emailLoading || $('#modal-mask')) return;
      const targetUserId = Number(row.id);
      if (!Number.isSafeInteger(targetUserId) || targetUserId <= 0) return;
      const targetLabel = { name: String(row.name || ''), username: String(row.username || '') };
      emailLoading = true;
      try {
        if (!emailPreparation) {
          const { createAccountEmailPreparation } = await import('/modules/notifications/account-email-preparation.js?v=20260923emailprep2');
          if (!current() || state.user?.role !== 'admin') return;
          emailPreparation = createAccountEmailPreparation({
            api, getUser: () => state.user, getModal: () => $('#modal-mask'), openModal, closeModal,
            renderForm, collectForm, renderTable, toast, isCurrent: current, signal: controller.signal,
            newRequestId: () => crypto.randomUUID(),
          });
        }
        if (current()) await emailPreparation.open(targetUserId, targetLabel);
      } catch (error) {
        if (current() && error?.name !== 'AbortError') toast('邮箱登记暂时无法打开，请稍后重试；现有账号仍可维护。', true);
      } finally { if (current()) emailLoading = false; }
    };
    let accountPermissions = null, permissionsLoading = false;
    addRouteCleanup(() => { accountPermissions?.destroy(); accountPermissions = null; }, epoch);
    let accountRelationships = null, relationshipsLoading = false;
    addRouteCleanup(() => { accountRelationships?.destroy(); accountRelationships = null; }, epoch);
    const actions = [
      { l: '维护负责人/BP', cls: 'gray', show: () => state.user?.role === 'admin', onClick: openAccountRelationships },
      { l: '查看业务权限', cls: 'gray', show: () => state.user?.role === 'admin', onClick: openAccountPermissions },
      { l: '登记邮箱', cls: 'gray', show: r => state.user?.role === 'admin' && Number(r.status) === 0 && r.role === 'viewer', onClick: openEmailPreparation },
      { l: '维护核验邮箱', cls: 'gray', show: r => state.user?.role === 'admin' && [0, 1].includes(Number(r.status)), onClick: openEmailMaintenance },
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
    const table = $('#tbl', c);
    let bindings;
    const refreshFallback = async () => {
      const rows = await api('/users', { quiet: true, signal: controller.signal });
      if (!current()) return;
      const fallback = $('[data-users-fallback]', table);
      if (!fallback) return;
      const availableActions = actions.filter((action) => action.l !== '删除');
      fallback.innerHTML = renderTable(cols, rows, availableActions, 'users');
      bindTableActions(fallback, rows, availableActions);
      updateSummary(rows);
    };
    try {
      const { mountAccountBindings } = await import('/modules/identity/account-bindings.js?v=20260923rolescopes1');
      if (!current()) return;
      bindings = mountAccountBindings(table, {
        api, getUser: () => state.user,
        openModal, closeModal, renderForm, collectForm, renderTable, bindTableActions, toast,
        columns: cols, bindingColumns, actions, onLoaded: updateSummary,
        isCurrent: current, signal: controller.signal,
      });
      addRouteCleanup(() => bindings.destroy(), epoch);
      await Promise.all([ownBindingReady, bindings.ready]);
    } catch (error) {
      if (!current() || error?.name === 'AbortError') return;
      bindings?.destroy();
      bindings = null;
      // Loading the optional adapter must not remove the original account maintenance.
      // Deletion stays unavailable until binding references can be verified.
      table.innerHTML = '<p role="status">人员绑定功能暂时无法加载，仍可维护账号；请重新加载页面后再核对绑定。</p><div data-users-fallback></div>';
      try {
        await refreshFallback();
      } catch (readError) {
        if (current()) $('[data-users-fallback]', table).textContent = readError.message || '账号列表读取失败，请重新加载页面。';
      }
      await ownBindingReady;
    }
    async function openAccountPermissions(row) {
      if (!current() || state.user?.role !== 'admin' || permissionsLoading || $('#modal-mask')) return;
      const accountId = Number(row.id);
      if (!Number.isSafeInteger(accountId) || accountId <= 0) return;
      permissionsLoading = true;
      try {
        if (!accountPermissions) {
          const { createAccountPermissions } = await import('/modules/identity/account-permissions.js?v=20260923accountaccess1');
          if (!current() || state.user?.role !== 'admin') return;
          accountPermissions = createAccountPermissions({ api, getUser: () => state.user, getModal: () => $('#modal-mask'), openModal, closeModal, renderTable, toast, isCurrent: current, signal: controller.signal });
        }
        if (current()) await accountPermissions.open(accountId);
      } catch (error) {
        if (current() && error?.name !== 'AbortError') toast('业务权限查询暂时无法打开，请稍后重试；现有账号仍可维护。', true);
      } finally { if (current()) permissionsLoading = false; }
    }
    async function openAccountRelationships(row) {
      if (!current() || state.user?.role !== 'admin' || relationshipsLoading || $('#modal-mask')) return;
      const accountId = Number(row.id);
      if (!Number.isSafeInteger(accountId) || accountId <= 0) return;
      relationshipsLoading = true;
      try {
        if (!accountRelationships) {
          const { createAccountRelationships } = await import('/modules/identity/account-relationships.js?v=20260923accountrelationships1');
          if (!current() || state.user?.role !== 'admin') return;
          accountRelationships = createAccountRelationships({
            api, getUser: () => state.user, getModal: () => $('#modal-mask'), openModal, closeModal, renderTable, toast, isCurrent: current, signal: controller.signal,
            onSessionInvalidated: () => { if (current()) invalidateSession(); },
            onSaved: async () => {
              if (!current()) return;
              if (bindings) { if (await bindings.refresh() === false) throw new Error('账号关系列表尚未刷新'); }
              else await refreshFallback();
            },
          });
        }
        if (current()) await accountRelationships.open(accountId);
      } catch (error) {
        if (current() && error?.name !== 'AbortError') toast('负责人/BP维护暂时无法打开，请稍后重试；现有账号仍可维护。', true);
      } finally { if (current()) relationshipsLoading = false; }
    }
    const provisioningButton = $('#user-provisioning-open', c);
    let accountProvisioning = null, provisioningLoading = false;
    addRouteCleanup(() => { accountProvisioning?.destroy(); accountProvisioning = null; }, epoch);
    if (provisioningButton) provisioningButton.onclick = async () => {
      if (!current() || state.user?.role !== 'admin' || provisioningLoading || $('#modal-mask')) return;
      provisioningLoading = true; provisioningButton.disabled = true;
      try {
        if (!accountProvisioning) {
          const { createAccountProvisioning } = await import('/modules/identity/account-provisioning.js?v=20260923accountprovisioning1');
          if (!current() || state.user?.role !== 'admin') return;
          accountProvisioning = createAccountProvisioning({
            api, getUser: () => state.user, getModal: () => $('#modal-mask'), openModal, closeModal, renderTable, toast, isCurrent: current, signal: controller.signal,
            onSessionInvalidated: () => { if (current()) invalidateSession(); },
            onSaved: async () => {
              if (!current()) return;
              if (bindings) { if (await bindings.refresh() === false) throw new Error('岗位配置列表尚未刷新'); }
              else await refreshFallback();
              if (!current()) return;
              const own = await api('/organization/me', { quiet: true, signal: controller.signal });
              if (current()) $('#user-own-binding', c).innerHTML = organizationBindingHtml(own);
            },
          });
        }
        if (current()) await accountProvisioning.open();
      } catch (error) {
        if (current() && error?.name !== 'AbortError') toast('人员岗位配置暂时无法打开，请稍后重试；现有账号仍可维护。', true);
      } finally { if (current()) { provisioningLoading = false; provisioningButton.disabled = false; } }
    };
    const managementGroupButton = $('#user-management-group-open', c);
    let managementGroup = null, managementGroupLoading = false;
    addRouteCleanup(() => { managementGroup?.destroy(); managementGroup = null; }, epoch);
    if (managementGroupButton) managementGroupButton.onclick = async () => {
      if (!current() || state.user?.role !== 'admin' || managementGroupLoading || $('#modal-mask')) return;
      managementGroupLoading = true; managementGroupButton.disabled = true;
      try {
        if (!managementGroup) {
          const { createAccountManagementGroup } = await import('/modules/identity/account-management-group.js?v=20260924managementgroup1');
          if (!current() || state.user?.role !== 'admin') return;
          managementGroup = createAccountManagementGroup({
            api, getUser: () => state.user, getModal: () => $('#modal-mask'), openModal, closeModal,
            isCurrent: current, signal: controller.signal,
            onSessionInvalidated: () => { if (current()) invalidateSession(); },
            onSaved: async () => {
              if (!current()) return;
              if (bindings) { if (await bindings.refresh() === false) throw new Error('管理组用户列表尚未刷新'); }
              else await refreshFallback();
              if (!current()) return;
              const own = await api('/organization/me', { quiet: true, signal: controller.signal });
              if (current()) $('#user-own-binding', c).innerHTML = organizationBindingHtml(own);
            },
          });
        }
        if (current()) await managementGroup.open();
      } catch (error) {
        if (current() && error?.name !== 'AbortError') toast('管理组配置暂时无法打开，请稍后重试。', true);
      } finally { if (current()) { managementGroupLoading = false; managementGroupButton.disabled = false; } }
    };
    const importButton = $('#user-import-open', c), supplementButton = $('#user-management-supplement-open', c), importRoot = $('#user-import-content', c);
    if (!current() || state.user?.role !== 'admin' || !importButton || !supplementButton || !importRoot) return;
    let importer = null, loadingImport = false, importKind = null;
    const importButtons = [importButton, supplementButton];
    addRouteCleanup(() => { importer?.destroy(); importer = null; }, epoch);
    const openImport = async kind => {
      if (!current() || state.user?.role !== 'admin' || loadingImport || importer && importKind === kind) return;
      if (importer && !importer.canSwitchBatch()) { toast('请先完成或关闭当前核对弹窗，并等待提交结果后再切换名单。', true); return; }
      importer?.destroy(); importer = null; importKind = kind;
      loadingImport = true; for (const button of importButtons) { button.disabled = true; button.hidden = false; button.style.display = ''; }
      const selectedButton = kind === 'management-supplement' ? supplementButton : importButton;
      const notice = $('#user-import-status', c); notice.textContent = kind === 'management-supplement' ? '正在打开管理人员核对…' : '正在打开名册核对…';
      try {
        const { mountAccountImport } = await import('/modules/identity/account-import.js?v=20260924management1');
        if (!current() || state.user?.role !== 'admin') return;
        let importMask = null;
        importer = mountAccountImport(importRoot, {
          api, getUser: () => state.user, renderForm, collectForm, renderTable, bindTableActions, toast,
          ...(kind === 'management-supplement' ? { importKind: 'management-supplement' } : {}),
          openModal: (...args) => { const mask = openModal(...args); if (mask) importMask = mask; return mask; },
          closeModal: () => { if (importMask && $('#modal-mask') === importMask) closeModal(); },
          isCurrent: current, signal: controller.signal,
          onImported: async () => {
            if (!current() || state.user?.role !== 'admin') return;
            if (bindings) await bindings.refresh(); else await refreshFallback();
          },
        });
        notice.textContent = ''; selectedButton.hidden = true; selectedButton.style.display = 'none';
        await importer.ready;
      } catch (error) {
        if (!current() || error?.name === 'AbortError') return;
        importer?.destroy(); importer = null;
        notice.textContent = kind === 'management-supplement' ? '管理人员补充暂时无法打开，可重试；现有账号仍可维护。' : '名册导入暂时无法打开，可重试；现有账号仍可维护。';
        selectedButton.hidden = false; selectedButton.style.display = '';
      } finally { if (current()) { loadingImport = false; for (const button of importButtons) button.disabled = false; } }
    };
    importButton.onclick = () => openImport('original');
    supplementButton.onclick = () => openImport('management-supplement');
  }

  // ============ 启动 ============
  (async function boot() {
    // 清理旧版浏览器令牌；认证由服务端 HttpOnly Cookie 承载。
    localStorage.removeItem('token');
    try {
      const { restoreInitialSession } = await import('/modules/notifications/trusted-device.js?v=20260924device1');
      state.user = await restoreInitialSession();
      if (!state.user) { renderLogin(); return; }
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
    if ($('#modal-mask')?._beforeClose && closeModal() === false) { writeRouteToUrl(true); return; }
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
