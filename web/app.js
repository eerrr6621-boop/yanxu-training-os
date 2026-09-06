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
  function disposeLoginExperience() {
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
    if (m.dataset.locked === 'true') {
      $('.modal', m)?.classList.add('attention');
      setTimeout(() => $('.modal', m)?.classList.remove('attention'), 220);
      return;
    }
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
    activeRowMenu = { menu, panel, summary };
    panel.classList.add('v13-row-menu');
    document.body.appendChild(panel);
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
    if (['中标', '启动项目', '收款', '记录通知', '确认', '完成', '发布', '发送链接', '发放', '入库'].includes(label)) return 0;
    if (['打开项目', '详情', '统计', '档案', '消息', '解析详情'].includes(label)) return 1;
    if (['编辑', '评价', '重置密码', '编辑画像'].includes(label)) return 3;
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
    const map = { '详情': 'eye', '打开项目': 'arrow-up-right', '项目总览': 'panels-top-left', '中标': 'badge-check', '启动项目': 'play', '收款': 'circle-dollar-sign', '完成交付': 'circle-check-big', '归档': 'archive', '编辑': 'pencil', '删除': 'trash-2', '消息': 'message-square-text', '记录通知': 'message-square-share', '确认': 'check-circle-2', '完成': 'circle-check', '统计': 'chart-no-axes-column-increasing', '发布': 'rocket', '发微信': 'send', '关闭': 'circle-x', '发放': 'badge-dollar-sign', '评价': 'star', '出库': 'log-out', '入库': 'log-in', '重置密码': 'key-round' };
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
    if (['待发送', '待评审', '待处理', '待发放', '未收费', '待启动', '待准备', '材料待补充', '待确认', '等待解析'].includes(v)) return 'orange';
    if (['已拒绝', '未中标', '已流标', '解析失败'].includes(v)) return 'red';
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
              <div class="login-credentials">
                <div class="login-field"><label for="login-user">账号</label><div class="login-input"><input id="login-user" name="username" placeholder="输入您的账号" autocomplete="username" required aria-describedby="login-user-hint"></div></div>
                <div class="login-field"><label for="login-pwd">密码</label><div class="login-input"><input id="login-pwd" name="password" type="password" placeholder="输入密码" autocomplete="current-password" required aria-describedby="caps-lock-note"><button type="button" id="pwd-toggle" aria-label="显示密码">${icon('eye')}</button></div></div>
              </div>
              <span class="sr-only" id="login-user-hint">请输入您的研序授权账号</span>
              <div class="v10-caps" id="caps-lock-note" role="status" aria-live="polite"></div>
              <button type="submit" class="login-submit" id="login-btn"><span>进入工作台</span>${icon('arrow-right')}</button>
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
    const doLogin = async () => {
      const form = $('#login-form');
      const error = $('#login-err');
      const userInput = $('#login-user');
      const passwordInput = $('#login-pwd');
      const btn = $('#login-btn');
      if (btn.disabled) return;
      error.textContent = '';
      const username = userInput.value.trim();
      const password = passwordInput.value;
      const current = () => form.isConnected && $('#login-form') === form;
      btn.disabled = true;
      form.setAttribute('aria-busy', 'true');
      btn.innerHTML = `${icon('loader-circle', 'spin')}<span>正在验证</span>`;
      sceneBridge.setPhase('loading');
      loginMotion?.setPhase('loading');
      refreshIcons(btn);
      try {
        const r = await api('/login', { body: { username, password } });
        if (!current()) return;
        localStorage.removeItem('token');
        localStorage.setItem('yx_last_username', username);
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
        loginMotion?.setPhase('success');
        renderLayout();
      } catch (e) {
        if (!current()) return;
        sceneBridge.setPhase('error');
        loginMotion?.setPhase('error');
        error.textContent = e.message || '登录失败';
        form.removeAttribute('aria-busy');
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
    dispatches: ['课程与排期', '安排课程、发送邀请并跟踪讲师确认'], questionnaires: ['效果评估', '创建问卷并回收培训反馈'],
    teachers: ['师资资源', '管理讲师档案、简历解析、智能匹配与授课评价'], charges: ['项目回款', '跟踪应收、回款进度与票据信息'],
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

  const REGION_PROVINCES = ['北京', '天津', '河北', '山西', '内蒙古', '辽宁', '吉林', '黑龙江', '上海', '江苏', '浙江', '安徽', '福建', '江西', '山东', '河南', '湖北', '湖南', '广东', '广西', '海南', '重庆', '四川', '贵州', '云南', '西藏', '陕西', '甘肃', '青海', '宁夏', '新疆', '香港', '澳门', '台湾', '境外'];
  const teacherResidenceText = (teacher) => teacher?.base_province && teacher?.base_city ? (teacher.base_province === teacher.base_city ? teacher.base_city : `${teacher.base_province} · ${teacher.base_city}`) : '待补充';
  const residenceFields = () => [
    { k: 'base_province', label: '常驻省份 / 地区', type: 'select', options: REGION_PROVINCES, required: true },
    { k: 'base_city', label: '常驻城市 / 地区', required: true, regionProvinceKey: 'base_province', placeholder: '选择省份后输入或选择城市', hint: '讲师确认的常驻地，无需家庭地址。城市字典未覆盖的名称可手填，核对前不参与同城优先。' },
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
    if (cfg.mod === 'demands' && canWrite()) acts.push({ l: '推荐师资', cls: '', icon: 'users-round', show: (row) => ['待处理', '已流标'].includes(row.status), onClick: (demand) => {
      invalidateTeacherRecommendations();
      state.teacherTab = 'recommend';
      state.teacherRequirementDraft = guidedRequirementText(demandGuidedFields(demand));
      state.teacherRecommendationForm = { demandId: String(demand.id), maxResults: '3', maxFeeRate: '', hardBudget: false, inputMode: 'guided', guided: demandGuidedFields(demand), rawDraft: demandRequirementText(demand), rawInitialized: true,
        logistics: { training_province: demand.training_province || '', training_city: demand.training_city || '', training_mode: demand.training_mode || '待定', training_period: demand.training_period || '待定', prefer_local: true } };
      navigateTo('teachers');
    } });
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
    const due = charges.reduce((s, r) => s + Number(r.amount || 0), 0);
    const collection = collectionProgress(project.amount, due, received);
    const outstanding = collection.outstanding;
    const deliveryActive = !['已完成', '已归档'].includes(project.status);
    const settlementNotice = !charges.length && collection.target > 0 ? '尚未登记应收。待回款按合同额计算，请先核对应收计划。' : collection.mismatch ? '已登记应收与合同金额不一致。待回款按合同额计算，归档前请核对。' : '';
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
    if (!questionnaires.length && project.status !== '已归档') upsertIssue('evaluation', { tone: 'neutral', score: 42, type: '评估准备', title: '本项目尚未创建效果评估', detail: '按项目需要准备培训反馈回收', page: 'questionnaires', action: '创建效果评估' });
    if (feePending > 0) upsertIssue('fees', { tone: 'neutral', score: 36, type: '课酬结算', title: `待发放课酬 ¥ ${money(feePending)}`, detail: `${fees.filter((r) => r.status === '待发放').length} 笔课酬等待处理`, page: 'fees', action: '核对待发课酬' });
    const issues = [...issueMap.values()].sort((a, b) => b.score - a.score);
    if (!canWrite() || project.status === '已归档') issues.forEach((item) => { item.action = readonlyAction(item.page); });
    const health = issues.some((x) => x.tone === 'critical') ? ['有风险', 'critical'] : issues.some((x) => x.tone === 'warning') ? ['需关注', 'warning'] : ['正常', 'good'];
    const milestone = ['已完成', '已归档'].includes(project.status) ? project.status : startIn === null ? '开课日期待定' : startIn < 0 ? (project.status === '待启动' ? '等待启动' : '项目交付中') : startIn === 0 ? '今天开课' : startIn === 1 ? '明天开课' : `距离开课 ${startIn} 天`;
    const paymentRate = collection.rate;
    const projectJourney = [
      { label: '项目资料', value: project.contract_no && project.owner && project.start_date ? '信息已齐' : '仍需补充', page: 'projects', status: project.contract_no && project.owner && project.start_date ? 'done' : 'current', art: 'documents', ico: 'file-check-2' },
      { label: '课程排期', value: `${num(scheduledHours)} / ${num(project.hours)} 课时`, page: 'dispatches', status: scheduledHours >= Number(project.hours || 0) && Number(project.hours || 0) > 0 ? 'done' : scheduledHours > 0 ? 'current' : 'todo', art: 'calendar', ico: 'calendar-clock' },
      { label: '课程交付', value: `${num(completedHours)} 已完成 · ${num(confirmedHours)} 已确认`, page: 'dispatches', status: completedHours >= Number(project.hours || 0) && Number(project.hours || 0) > 0 ? 'done' : confirmedHours > 0 ? 'current' : 'todo', art: 'faculty', ico: 'badge-check' },
      { label: '效果评估', value: questionnaires.length ? `${questionnaires.length} 份问卷 · ${responseRate}% 回收` : '尚未创建问卷', page: 'questionnaires', status: responseRate >= 60 ? 'done' : questionnaires.length ? 'current' : 'todo', art: 'evaluation', ico: 'clipboard-check' },
      { label: '回款结算', value: collection.target > 0 ? `${paymentRate.toFixed(0)}% 已回款` : '无需回款', page: 'charges', status: collectionStageStatus(collection, received), art: 'collection', ico: 'badge-japanese-yen' },
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
        <div><small>已交付课时</small><b>${num(completedHours)}<em> / ${num(project.hours)}</em></b><span class="metric-note">${num(confirmedHours)} 课时已确认</span></div>
        <div><small>待回款</small><b>¥ ${money(outstanding)}</b><span class="metric-note">${collection.target > 0 ? `已回款 ${paymentRate.toFixed(0)}%` : '无需回款'}</span></div>
        <div><small>估算余额</small><b>¥ ${money(estimatedBalance)}</b><span class="metric-note">按已录成本 · 非最终利润</span></div>
      </div>

      <nav class="project-journey" aria-label="项目推进路径">${projectJourney.map((step) => `<button type="button" class="${step.status}" data-project-goto="${step.page}" ${step.page === 'projects' ? `data-focus-id="${project.id}"` : ''}><span class="journey-icon">${businessArt(step.art)}</span><span class="journey-copy"><b>${step.label}</b><small>${project.status === '已归档' && step.page === 'questionnaires' && !questionnaires.length ? '未设置' : step.status === 'done' ? '已就绪' : step.status === 'current' ? '跟进中' : '待推进'}</small></span>${icon('chevron-right')}</button>`).join('')}</nav>

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
            <div class="workspace-panel-head"><div><h2>效果评估</h2><small>反馈回收与培训质量</small></div><button type="button" data-project-goto="questionnaires">${writableProject ? '管理评估' : '查看评估'}${icon('chevron-right')}</button></div>
            ${questionnaires.length ? `<div class="evaluation-focus"><div><small>问卷</small><b>${questionnaires.length}<em> 份</em></b></div><div><small>计划触达</small><b>${sendTotal}<em> 人次</em></b></div><div><small>已回收</small><b>${recvTotal}<em> · ${responseRate}%</em></b></div></div><p class="evaluation-note">${project.status === '已归档' ? icon('archive') + `历史回收率 ${responseRate}%，项目已归档` : responseRate >= 60 ? icon('circle-check') + '已达到基础复盘回收要求' : icon('info') + '回收率不足 60%，建议再次触达学员'}</p>` : `<div class="workspace-empty">${businessArt('evaluation')}<b>${project.status === '已归档' ? '本项目未设置评估' : '尚未创建评估问卷'}</b><span>${project.status === '已归档' ? '历史状态，可在项目资料中核对' : '按项目需要准备培训反馈回收'}</span><button type="button" data-project-goto="questionnaires">${writableProject ? '创建评估' : '查看评估'}${icon('chevron-right')}</button></div>`}
          </section>
        </div>

        <aside class="v13-workspace-aside" aria-label="结算与项目资料">
          <section class="workspace-panel finance-panel">
            <div class="workspace-panel-head"><div><h2>结算概览</h2></div><button type="button" data-project-goto="charges">查看${icon('chevron-right')}</button></div>
            <dl class="v13-finance-list"><div><dt>合同金额</dt><dd>¥ ${money(project.amount)}</dd></div><div><dt>应收金额</dt><dd>¥ ${money(due)}</dd></div><div><dt>已回款</dt><dd>¥ ${money(received)}</dd></div><div class="is-outstanding"><dt>待回款</dt><dd>¥ ${money(outstanding)}</dd></div></dl>${settlementNotice ? `<p class="v13-finance-notice">${icon('info')}${esc(settlementNotice)}</p>` : ''}
            <div class="v13-finance-links"><button type="button" data-project-goto="fees"><span>课酬发放<small>已录 ¥ ${money(feeTotal)} · 待发 ¥ ${money(feePending)}</small></span>${icon('chevron-right')}</button><button type="button" data-project-goto="costs"><span>成本费用<small>已录 ¥ ${money(costTotal)}</small></span>${icon('chevron-right')}</button></div>
            <details class="finance-definition"><summary>${icon('info')}估算余额如何计算${icon('chevron-down')}</summary><div><p>合同额 − 已录课酬 − 已录成本。未排课程的课酬和尚未登记的费用不会自动计入，当前余额不代表最终利润。</p><button type="button" data-project-goto="dispatches">检查排课</button><button type="button" data-project-goto="costs">${writableProject ? '补录成本' : '查看成本'}</button></div></details>
          </section>

          <details class="v13-project-brief"><summary>${icon('text-align-start')}项目资料${icon('chevron-down')}</summary><div class="demand-brief-grid"><div><small>培训目标与内容</small><p>${esc(demand?.content || project.remark || '尚未记录详细培训目标')}</p></div><div><small>师资要求</small><p>${esc(demand?.teacher_req || '尚未记录师资要求')}</p></div><div><small>交付信息</small><p>${esc([project.contract_no ? `合同 ${project.contract_no}` : '合同编号待补充', project.participant_count ? `${project.participant_count} 人` : '人数待定', project.delivery_mode || '授课方式待定', project.venue || '场地待定'].join(' · '))}</p></div><div><small>客户联系人</small><p>${esc(demand ? `${demand.contact || '—'} · ${demand.phone || '—'}` : '—')}</p></div><div><small>来源记录</small><p>${esc(demand?.title || '未关联原始需求')} · ${project.bid_id ? `中标记录 #${esc(project.bid_id)}` : '独立立项'}</p></div></div></details>
        </aside>
      </div>`;

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
        openModal('通知与确认记录', logs.length ? `<div class="activity-log">${logs.map((line, i) => `<div><span>${String(i + 1).padStart(2, '0')}</span><p>${esc(line)}</p></div>`).join('')}</div>` : `<div class="workspace-empty">${businessArt('faculty')}<b>暂无通知记录</b><span>通过线下、电话或其他渠道通知师资后，可在这里记录操作轨迹。</span></div>`, { noFoot: true, kicker: '师资协同轨迹' });
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

  // ============ 师资资源：档案、简历与智能推荐 ============
  const teacherFeeRateText = (value) => Number.isFinite(Number(value)) && Number(value) > 0 ? `¥ ${money(value)}/课时` : '待确认';
  const teacherFormFields = () => [
    { k: 'name', label: '姓名', required: true },
    { k: 'gender', label: '性别', type: 'select', options: ['男', '女'] },
    { k: 'org', label: '所在单位' },
    { k: 'title', label: '职称/职务' },
    ...residenceFields(),
    { k: 'field', label: '专业领域', span2: true },
    { k: 'phone', label: '联系电话' },
    { k: 'email', label: '电子邮箱', type: 'email' },
    { k: 'fee_rate', label: '课酬标准（元/课时）', type: 'number', required: true, min: 0, step: 100, hint: '尚未确认时可填写 0，系统会显示为“待确认”。' },
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
    const fallback = ext === 'pptx' ? 80 * 1024 * 1024 : 15 * 1024 * 1024;
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
    return JSON.stringify([state.teacherRequirementDraft.trim(), String(form.demandId || ''), Number(form.maxResults), String(form.maxFeeRate || '').trim(), Boolean(form.hardBudget), form.logistics || {}]);
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
    return `<div class="teacher-entry-guide"><b>首次添加讲师</b><ol aria-label="讲师资料准备流程"><li>1. 新建讲师档案</li><li>2. 关联并上传简历</li><li>3. 核对解析结果</li></ol><p>已有档案可直接上传简历，无需重复建档。</p></div>`;
  }

  function openTeacherCreate() {
    if (!canWrite()) return;
    const fields = teacherFormFields();
    return openModal('新建讲师档案', `<p class="teacher-create-note">先保存讲师的基本信息与常驻地区，建档后再上传简历。已有档案请勿重复创建。</p>${renderForm(fields, { in_date: new Date().toISOString().slice(0, 10) })}`, {
      okText: '保存讲师档案',
      onOk: async () => {
        const data = collectForm($('#modal-mask'), fields);
        if (!data) return false;
        data.status = '在库';
        await api('/teachers', { body: data });
        invalidateTeacherRecommendations();
        toast('讲师档案已建立，下一步可关联并上传简历');
        renderPage();
      },
    });
  }

  function openTeacherResumeUpload(teachers, selectedTeacherId = '') {
    if (!canWrite()) return;
    if (!teachers.length) {
      return openModal('先建立讲师档案', `<p class="teacher-create-note">目前还没有可关联的讲师档案。请先填写姓名、常驻地区等基本信息，保存后再上传这位讲师的简历。上传简历不会自动新建档案。</p>`, {
        sm: true, kicker: '首次添加讲师', okText: '新建讲师档案', okIcon: 'user-plus',
        onOk: () => { openTeacherCreate(); return false; },
      });
    }
    const selected = String(selectedTeacherId || '');
    const options = teachers.map((teacher) => `<option value="${esc(teacher.id)}" ${String(teacher.id) === selected ? 'selected' : ''}>${esc(teacher.name)}｜${esc(teacherResidenceText(teacher))}｜${esc(teacher.org || '单位待补充')}</option>`).join('');
    const mask = openModal('上传讲师简历', `
      <div class="resume-upload-lead"><span>${icon('scan-text')}</span><div><b>先建讲师档案，再关联简历</b><p>已有档案：直接选择下方讲师并上传。系统会提取 PDF / PPTX 中的专业经历，请核对解析结果后用于推荐。</p></div></div>
      <div class="resume-upload-fields">
        <div class="form-item"><label for="teacher-resume-owner">关联已建档讲师<span class="req">*</span></label><select id="teacher-resume-owner" required aria-describedby="teacher-resume-owner-help teacher-resume-owner-error" ${selected ? '' : 'autofocus'}><option value="" disabled ${selected ? '' : 'selected'}>请选择已建立档案的讲师</option>${options}</select><small id="teacher-resume-owner-help">找不到讲师？请先取消上传，在「师资档案」点击「新建讲师档案」。上传简历不会自动新建档案。</small><span class="field-error" id="teacher-resume-owner-error" aria-live="polite"></span></div>
        <div id="resume-residence-fields">${renderForm(residenceFields(), teachers.find((teacher) => String(teacher.id) === selected))}</div>
        <p class="resume-residence-note">请确认该讲师的常驻地区，用于就近匹配。修改后会单独保存，不会被简历解析结果覆盖。</p>
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
        const residence = collectForm($('#resume-residence-fields', mask), residenceFields());
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
    const fileInput = $('#teacher-resume-file', mask);
    $('#teacher-resume-owner', mask).onchange = () => {
      $('#teacher-resume-owner-error', mask).textContent = '';
      $('#teacher-resume-owner', mask).removeAttribute('aria-invalid');
      const owner = teachers.find((teacher) => String(teacher.id) === $('#teacher-resume-owner', mask).value);
      $('#resume-residence-fields', mask).innerHTML = renderForm(residenceFields(), owner);
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
    const fields = [...residenceFields(), {
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
    const { epoch, c, rows, projects, dispatches, resumeByTeacher } = context;
    const savedFilter = state.filters.teachers || {};
    const fields = teacherFormFields();
    root.innerHTML = `<div class="card data-card teacher-library-card">
      <div class="card-heading"><div><h2>师资档案</h2><p>当前显示 <span id="result-count">${rows.length}</span> 条记录 · 档案、简历状态与履约评价统一查看</p></div>${canWrite() ? `<div class="teacher-head-actions"><button type="button" class="btn gray" id="teacher-add-manual">${icon('user-plus')}新建讲师档案</button><button type="button" class="btn" id="teacher-upload-resume">${icon('file-up')}上传讲师简历</button></div>` : ''}</div>
      ${canWrite() ? teacherEntryGuide() : ''}
      <div class="toolbar">
        <label class="search-box"><span class="sr-only">搜索师资姓名、单位或领域</span>${icon('search')}<input id="flt-kw" value="${esc(savedFilter.kw || '')}" placeholder="搜索：姓名/单位/领域" autocomplete="off"></label>
        <label class="select-filter"><span class="sr-only">按师资状态筛选</span><select id="flt-status"><option value="">全部状态</option><option ${savedFilter.status === '在库' ? 'selected' : ''}>在库</option><option ${savedFilter.status === '出库' ? 'selected' : ''}>出库</option></select></label>
        <button type="button" class="btn gray" id="flt-btn">${icon('list-filter')}筛选</button>
      </div>
      <div id="tbl"></div>
    </div>`;

    const cols = [
      { k: 'id', l: '编号', mobileHide: true, render: (row) => `<span class="project-id">#T-${String(row.id).padStart(4, '0')}</span>` },
      { k: 'name', l: '师资', render: (row) => `<div class="person-cell"><span class="person-avatar">${esc(row.name.slice(-2))}</span><span><b>${esc(row.name)}</b><small>${esc(row.title || '讲师')}</small></span></div>` },
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
      actions.push({ l: '编辑', cls: 'gray', onClick: (row) => openModal('编辑师资', renderForm(fields, row), { onOk: async () => { const data = collectForm($('#modal-mask'), fields); if (!data) return false; await api('/teachers', { body: { ...row, ...data } }); invalidateTeacherRecommendations(); toast('已保存'); renderPage(); } }) });
      actions.push({ l: '删除', cls: 'red', onClick: (row) => confirmBox(`仅未产生排课、课酬或评价的师资可以删除。确定检查并删除【${row.name}】？`, async () => { await api('/teachers/delete', { body: { id: row.id } }); invalidateTeacherRecommendations(); toast('已删除'); renderPage(); }) });
    }
    const draw = (list) => {
      if (!isRouteCurrent(epoch, c, 'teachers') || !root.isConnected) return;
      const table = $('#tbl', root);
      if (!table) return;
      table.innerHTML = renderTable(cols, list, actions, 'teachers');
      bindTableActions(table, list, actions);
      $('#result-count', root).textContent = list.length;
    };
    draw(rows);
    let filterController = null;
    addRouteCleanup(() => filterController?.abort(), epoch);
    const runFilter = async () => {
      if (!isRouteCurrent(epoch, c, 'teachers') || !$('#flt-kw', root)) return;
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
    addRouteCleanup(liveFilter.cancel, epoch);
    $('#flt-kw', root).oninput = liveFilter;
    $('#flt-kw', root).onkeydown = (event) => {
      if (event.key === 'Enter') { event.preventDefault(); runFilter(); }
      if (event.key === 'Escape') { event.preventDefault(); $('#flt-kw', root).value = ''; runFilter(); }
    };
    if (savedFilter.kw || savedFilter.status) runFilter();
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

  function renderRecommendationResults(target, payload, context) {
    const analysis = payload?.analysis || payload?.requirement_analysis || {};
    const dispatchPreferences = analysis.dispatch_preferences || {};
    const recommendations = payload?.recommendations || payload?.results || payload?.candidates || [];
    const groups = [
      ['培训主题', listText(analysis.topics)],
      ['客户行业', listText(analysis.industries)],
      ['授课对象', listText(analysis.audiences)],
      ['专业资历', listText(analysis.credentials)],
    ].filter((group) => group[1].length);
    const conditionFacts = [analysis.expected_date ? `授课日期：${analysis.expected_date}` : '', Number(analysis.hours) > 0 ? `课时：${num(analysis.hours)}` : '', Number(analysis.max_fee_rate) > 0 ? `课酬上限：¥ ${money(analysis.max_fee_rate)}/课时` : '', dispatchPreferences.training_city ? `授课地区：${dispatchPreferences.training_province} · ${dispatchPreferences.training_city}` : '', dispatchPreferences.training_mode ? `方式：${dispatchPreferences.training_mode}` : '', dispatchPreferences.training_period ? `时段：${dispatchPreferences.training_period}` : ''].filter(Boolean);
    const shortfall = Number(payload?.shortfall ?? Math.max(0, 3 - recommendations.length));
    const pendingResidence = Number(payload?.residence_pending_count || 0);
    const shortageHtml = shortfall > 0 || pendingResidence > 0 ? `<div class="recommend-shortage" role="status">${icon('users-round')}<div><b>${shortfall > 0 ? `找到 ${recommendations.length} 位相关候选，距 3 位目标还缺 ${shortfall} 位` : `已找到 ${recommendations.length} 位相关候选`}</b><p>${shortfall > 0 ? '请补充相应专业师资或人工调整需求条件。不会用无关、冲突或超出硬预算的老师凑数。' : ''}${pendingResidence > 0 ? ` 其中 ${pendingResidence} 位常驻地区未补齐，仍是待补资料候选，不能视为调度已核实。` : ''}</p></div></div>` : '';
    const excluded = Array.isArray(payload?.excluded) ? payload.excluded : [];
    const excludedHtml = excluded.length ? `<details class="recommend-excluded" ${recommendations.length ? '' : 'open'}><summary>${icon('calendar-x')}已排除 ${excluded.length} 位候选，查看原因</summary><ul>${excluded.map((item) => `<li><b>${esc(item.teacher_name || item.name || '讲师')}</b><span>${esc(item.reason || '当前条件不适合，请进一步确认')}</span></li>`).join('')}</ul></details>` : '';
    const analysisHtml = `<section class="recommend-analysis"><header class="recommend-profile-head"><h2>${icon('scan-search')}需求画像</h2><span>请核对识别结果</span></header>${groups.length ? `<dl class="recommend-profile-grid">${groups.map(([label, values]) => `<div><dt>${esc(label)}</dt><dd>${values.map((value) => esc(value)).join(' · ')}</dd></div>`).join('')}</dl>` : `<p class="recommend-profile-empty">${esc(analysis.summary || '暂未识别到明确专业条件，请补充培训主题与参训对象。')}</p>`}${conditionFacts.length ? `<p class="recommend-condition-facts">${conditionFacts.map((fact) => `<span>${esc(fact)}</span>`).join('')}</p>` : ''}<p class="recommend-condition-note">地点、差旅及特殊安排仍需人工确认。</p></section>`;
    if (!recommendations.length) {
      target.innerHTML = `${analysisHtml}${shortageHtml}${excludedHtml}<div class="recommend-empty">${icon('user-round-search')}<b>暂未找到合适候选</b><p>${excluded.length ? '请查看上方排除原因，再调整日期、课酬条件或补充更多讲师。' : '可补充培训主题、参训对象与行业后重试，也请检查在库讲师的专业资料是否完善。'}</p></div>`;
      refreshIcons(target);
      return;
    }
    const teacherById = new Map(context.rows.map((teacher) => [String(teacher.id), teacher]));
    const cards = recommendations.map((item, index) => {
      const teacher = item.teacher || teacherById.get(String(item.teacher_id)) || {};
      const dispatchFit = item.dispatch_fit || {};
      const name = item.teacher_name || teacher.name || '候选讲师';
      const score = recommendationPercent(item.match_score ?? item.score);
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
          <header><span class="person-avatar large">${esc(name.slice(-2))}</span><div><h3>${esc(name)} ${resumeLabel}</h3><p>${esc(item.org || teacher.org || '单位待补充')} · ${esc(item.title || teacher.title || '讲师')}</p><small>${esc(item.field || teacher.field || '专业领域待补充')}</small></div><div class="teacher-match-score" aria-label="匹配参考分 ${score}，满分 100"><b>${score}<em>/ 100</em></b><small>匹配参考分</small></div></header>
          <div class="teacher-match-facts"><span>常驻 <b>${esc(teacherResidenceText(item))}</b></span><span>课酬 <b>${teacherFeeRateText(item.fee_rate ?? teacher.fee_rate)}</b></span><span>系统已完成 <b>${num(verified.completedSessions)} 场</b></span><span>授课评价 <b>${verified.evaluationCount ? `${verified.evaluationAverage.toFixed(2)} / 5` : '暂无记录'}</b></span></div>
          <details class="teacher-dispatch-fit"><summary>${icon('map-pin')}<b>${esc(dispatchFit.label || '调度待核实')}</b><span>${dispatchFit.arrival_day_conflict ? '提前到达日有授课记录，需核对衔接' : dispatchFit.arrival_day_before ? '异地上午课 · 提前一天到达待核实' : '查看调度核对事项'}</span>${icon('chevron-down')}</summary><ul>${listText(dispatchFit.notes).map((note) => `<li>${esc(note)}</li>`).join('')}</ul></details>
          <div class="teacher-match-detail">
            <section class="match-reasons"><b>${icon('badge-check')}推荐理由</b>${reasons.length ? `<ul>${reasons.map((reason) => `<li>${esc(reason)}</li>`).join('')}</ul>` : '<p>暂无细分理由</p>'}</section>
            <section class="match-gaps"><b>${icon('triangle-alert')}缺口与待确认</b>${gaps.length ? `<ul>${gaps.map((gap) => `<li>${esc(gap)}</li>`).join('')}</ul>` : '<p>未发现明显缺口</p>'}</section>
          </div>
          <details class="teacher-match-audit"><summary>${icon('chart-no-axes-column')}匹配评分与授课记录${icon('chevron-down')}</summary>
            ${breakdown.length ? `<div class="teacher-score-breakdown">${breakdown.map(([label, value]) => { const pct = recommendationPercent(value); const displayLabel = item.score_breakdown_details?.[label]?.label || breakdownLabel(label); return `<div><span><small>${esc(displayLabel)}</small><b>${pct}%</b></span><i><em style="width:${pct}%"></em></i></div>`; }).join('')}</div>` : ''}
            <section class="teacher-verified-proof"><div><span>${icon('shield-check')}系统履约记录</span><small>仅统计本系统已完成课程与已提交评价</small></div><dl><div><dt>已完成场次</dt><dd>${num(verified.completedSessions)}</dd></div><div><dt>已完成课时</dt><dd>${num(verified.completedHours)}</dd></div><div><dt>评价均分</dt><dd>${verified.evaluationCount ? `${verified.evaluationAverage.toFixed(2)} <small>/ 5 分 · ${num(verified.evaluationCount)} 份</small>` : '暂无评价'}</dd></div></dl></section>
          </details>
          ${resumeClaimFacts(item.resume_claims).length ? `<details class="teacher-evidence"><summary>${icon('badge-info')}查看简历自述数据</summary><div class="recommend-resume-claims">${resumeClaimMarkup(item.resume_claims)}</div></details>` : ''}
          ${evidence.length ? `<details class="teacher-evidence"><summary>${icon('file-search')}查看简历自述依据 <span>${evidence.length}</span></summary><div class="resume-claim-note">简历中的课时、满意度和客户案例属于讲师资料自述，不计入上方系统履约记录。</div><ul>${evidence.map((item) => `<li>${esc(item)}</li>`).join('')}</ul></details>` : ''}
          <footer>${!dispatchFit.residence_complete ? `<button type="button" class="btn gray" data-recommend-residence="${esc(item.teacher_id || teacher.id || '')}">${icon('map-pin')}补充常驻地区</button>` : ''}${hasResume ? `<button type="button" class="btn gray" data-view-recommend-resume="${esc(item.teacher_id || teacher.id || '')}">${icon('file-search')}简历与画像</button>` : ''}<button type="button" class="btn gray" data-view-recommend-teacher="${esc(item.teacher_id || teacher.id || '')}">${icon('contact-round')}档案与授课记录</button></footer>
        </div>
      </article>`;
    }).join('');
    target.innerHTML = `${analysisHtml}${shortageHtml}<div class="recommend-results-head"><h2>推荐候选 <em>${recommendations.length}</em></h2><small>${esc(dispatchPreferences.ranking_policy || '按资料匹配程度排序')}</small></div><div class="teacher-match-list">${cards}</div>${excludedHtml}<div class="recommend-notice">${icon('info')}<span>本名单用于投标前选师资，不表示老师已确认授课。专业分不是胜任概率；无交通记录不能判断可达，系统不会自动建项目、排课或产生课酬。</span></div>`;
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
    const cached = state.cache.teacherRecommendationKey === teacherRecommendationKey() ? state.cache.teacherRecommendations : null;
    let inputMode = form.inputMode || (state.teacherRequirementDraft ? 'raw' : 'guided');
    let rawInitialized = form.rawInitialized ?? (inputMode === 'raw');
    const guided = form.guided || {};
    const logisticsFields = [
      { k: 'training_province', label: '授课省份 / 地区', type: 'select', options: [{ v: '', l: '待确定' }, ...REGION_PROVINCES] },
      { k: 'training_city', label: '授课城市 / 地区', regionProvinceKey: 'training_province', placeholder: '选择省份后输入或选择城市' },
      { k: 'training_mode', label: '授课方式', type: 'select', options: ['待定', '线下', '线上'], value: '待定' },
      { k: 'training_period', label: '授课时段', type: 'select', options: ['待定', '上午', '下午', '全天'], value: '待定' },
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
        <section class="recommend-logistics" aria-labelledby="recommend-logistics-title"><div class="recommend-logistics-heading"><h3 id="recommend-logistics-title">授课地点与调度</h3><label><input type="checkbox" id="recommend-prefer-local" ${form.logistics?.prefer_local !== false ? 'checked' : ''}>同档匹配优先同城</label></div><div id="recommend-logistics-fields">${renderForm(logisticsFields, form.logistics)}</div><small>这些条件在两种填写方式中共用。异地耗时、票价和差旅待核实；线上课程不参与地区排序。</small></section>
        <details class="recommend-settings" id="recommend-settings" ${form.maxFeeRate || form.hardBudget ? 'open' : ''}><summary>${icon('sliders-horizontal')}筛选条件<small id="recommend-settings-summary"></small>${icon('chevron-down')}</summary><div class="recommend-settings-grid">
          <div class="form-item recommend-budget"><label for="recommend-max-fee">最高课酬（元/课时）</label><input id="recommend-max-fee" type="number" min="0" step="0.01" inputmode="decimal" value="${esc(form.maxFeeRate)}" placeholder="留空表示不限" aria-describedby="recommend-budget-help recommend-budget-error"><small id="recommend-budget-help">按每课时金额筛选，不是总项目预算。</small><label class="recommend-budget-toggle"><input id="recommend-hard-budget" type="checkbox" ${form.hardBudget ? 'checked' : ''}><span>严格排除超出课酬上限的讲师</span></label><span class="field-error" id="recommend-budget-error" aria-live="polite"></span></div>
          <div class="form-item recommend-input-options"><label for="recommend-count">推荐目标人数</label><select id="recommend-count">${[3, 5, 10].map((count) => `<option value="${count}" ${String(form.maxResults) === String(count) ? 'selected' : ''}>${count} 位</option>`).join('')}</select><small>至少 3 位供比较；相关师资不足时提示缺口，不用无关讲师补位。</small></div>
        </div></details>
        <div class="recommend-submit-row"><p id="recommend-status" role="status">${cached ? '已保留上次推荐结果，可调整需求后重新匹配。' : '提交后将在下方展示需求分析与推荐结果。'}</p><button type="button" class="btn recommend-run" id="recommend-run">${icon('arrow-right')}开始匹配讲师</button></div>
        <p class="recommend-input-foot">${icon('shield-check')}本地规则匹配，不是大模型或实时交通查询。客户意向不等于讲师确认；推荐不会自动立项或安排授课。</p>
      </section>
      <section class="recommend-output" id="recommend-output" aria-label="讲师推荐结果" aria-live="polite" tabindex="-1" ${cached ? '' : 'hidden'}></section>
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
      state.teacherRecommendationForm = { demandId: demandSelect.value, maxResults: count.value, maxFeeRate: maxFee.value, hardBudget: hardBudget.checked, inputMode, guided: readGuided(), rawDraft: requirement.value, rawInitialized, logistics: readLogistics() };
    };
    const markRecommendationDirty = () => {
      persistForm();
      invalidateTeacherRecommendations();
      if (!output.hidden) status.textContent = '条件已更新，请重新匹配。';
      output.hidden = true;
      output.innerHTML = '';
      updateSettingsSummary();
    };
    guideInputs.forEach((input) => { input.oninput = () => {
      if (input.dataset.recommendGuide === 'topic') {
        $('#recommend-topic-error', root).textContent = '';
        $('#recommend-guide-topic', root).removeAttribute('aria-invalid');
      }
      if (input.dataset.recommendGuide === 'hours') { $('#recommend-hours-error', root).textContent = ''; hoursInput.removeAttribute('aria-invalid'); }
      updateBrief(); markRecommendationDirty();
    }; });
    logisticsInputs.forEach((input) => { input.oninput = markRecommendationDirty; input.onchange = markRecommendationDirty; });
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
      markRecommendationDirty();
    };
    requirement.oninput = () => { rawInitialized = true; $('#recommend-requirement-error', root).textContent = ''; requirement.removeAttribute('aria-invalid'); markRecommendationDirty(); };
    count.onchange = markRecommendationDirty;
    maxFee.oninput = () => { $('#recommend-budget-error', root).textContent = ''; markRecommendationDirty(); };
    hardBudget.onchange = () => { $('#recommend-budget-error', root).textContent = ''; markRecommendationDirty(); };
    if (cached) renderRecommendationResults(output, cached, context);
    $('#recommend-run', root).onclick = async () => {
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
      const controls = [demandSelect, requirement, count, maxFee, hardBudget, preferLocal, ...logisticsInputs, ...guideInputs, ...modeButtons];
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
        Object.assign(body, logistics, { prefer_local: preferLocal.checked });
        if (feeValue) body.max_fee_rate = fee;
        const result = await api('/teacher-recommendations', { body, signal: controller.signal });
        if (controller.signal.aborted || requestSequence !== teacherRecommendationSequence || requestKey !== teacherRecommendationKey() || !isRouteCurrent(context.epoch, context.c, 'teachers') || state.teacherTab !== 'recommend' || !output.isConnected) return;
        state.cache.teacherRecommendations = result;
        state.cache.teacherRecommendationKey = requestKey;
        renderRecommendationResults(output, result, context);
        status.textContent = Number(result.shortfall) > 0 ? `已找到 ${result.returned_count} 位相关候选，距 3 位目标还缺 ${result.shortfall} 位。` : '已生成候选名单，请先核对资料与调度条件，再与客户确认。';
        output.focus({ preventScroll: true });
        output.scrollIntoView({ block: 'start', behavior: 'auto' });
      } catch (error) {
        if (error?.name === 'AbortError' || controller.signal.aborted || requestSequence !== teacherRecommendationSequence || !isRouteCurrent(context.epoch, context.c, 'teachers') || !output.isConnected) return;
        output.innerHTML = `<div class="recommend-empty">${icon('cloud-alert')}<b>本次推荐未完成</b><p>${esc(error?.message || '请稍后重试，已输入的客户要求会继续保留。')}</p></div>`;
        status.textContent = '本次匹配未完成，您填写的内容已保留。';
        refreshIcons(output);
      } finally {
        if (teacherRecommendationController === controller) teacherRecommendationController = null;
        if (activeRequest === controller && output.isConnected) {
          activeRequest = null;
          output.removeAttribute('aria-busy');
          controls.forEach((control) => { control.disabled = false; });
          if (button.isConnected) { button.disabled = false; button.classList.remove('is-loading'); button.innerHTML = old; refreshIcons(button); }
        }
      }
    };
  }

  async function pageTeachers(c) {
    const epoch = routeEpoch;
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
      ? `<div class="module-summary four teacher-summary"><div><span>${icon('users-round')}</span><small>当前师资</small><b>${rows.length}<em>人</em></b></div><div><span>${icon('user-check')}</span><small>当前在库</small><b>${inLib}<em>人</em></b></div><div><span>${icon('file-check-2')}</span><small>简历可推荐</small><b>${ready}<em>份</em></b></div><div><span>${icon('scan-line')}</span><small>解析待处理</small><b>${attention}<em>份</em></b></div></div>`
      : `<div class="module-summary three teacher-summary"><div><span>${icon('users-round')}</span><small>当前师资</small><b>${rows.length}<em>人</em></b></div><div><span>${icon('user-check')}</span><small>当前在库</small><b>${inLib}<em>人</em></b></div><div><span>${icon('badge-japanese-yen')}</span><small>已确认平均课酬</small><b>${confirmedRates.length ? `¥ ${money(avgRate)}` : '待确认'}</b></div></div>`;
    const tabBar = canWrite() ? `<div class="teacher-mode-tabs" role="tablist" aria-label="师资资源功能">${tabs.map((tabItem) => `<button type="button" role="tab" id="teacher-tab-${tabItem.key}" aria-controls="teacher-tab-panel" aria-selected="${state.teacherTab === tabItem.key}" tabindex="${state.teacherTab === tabItem.key ? '0' : '-1'}" data-teacher-tab="${tabItem.key}" class="${state.teacherTab === tabItem.key ? 'active' : ''}">${businessArt(tabItem.art)}<span>${tabItem.label}</span>${tabItem.count ? `<em>${tabItem.count}</em>` : ''}</button>`).join('')}</div>` : '';
    c.innerHTML = `<div class="teacher-console">
      <div class="teacher-console-head">${tabBar}${summary}</div>
      <div class="teacher-tab-panel" id="teacher-tab-panel" role="${canWrite() ? 'tabpanel' : 'region'}" ${canWrite() ? `aria-labelledby="teacher-tab-${esc(state.teacherTab)}"` : 'aria-label="师资库"'}></div>
    </div>`;
    const panel = $('#teacher-tab-panel', c);
    const context = { epoch, c, rows, projects, dispatches, demands, evaluations, resumes, resumeByTeacher, resumeLoadError: resumePayload?._loadError || '' };
    addRouteCleanup(() => { cancelTeacherRecommendation(); context.stopTeacherTabPolling?.(); teacherProfileRequestSequence += 1; }, epoch);
    const showTab = (key, focus = false) => {
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
